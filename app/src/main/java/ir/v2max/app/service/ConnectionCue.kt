package ir.v2max.app.service

import java.util.concurrent.atomic.AtomicLong

/** Player callbacks must run on the same serial dispatcher as the controller. */
internal interface ConnectionCuePlayer {
    fun prepare(onPrepared: () -> Unit, onCompletion: () -> Unit, onError: () -> Unit)
    fun start()
    fun release()
}

/**
 * A best-effort, one-shot announcement. The VPN never waits for media setup or
 * playback. Public methods may be called on any thread; player operations and
 * callbacks are serialized by [dispatch]. A revision immediately invalidates
 * queued work even if the dispatcher has not yet processed cancellation.
 */
internal class ConnectionCue(
    private val dispatch: (delayMs: Long, task: () -> Unit) -> Boolean,
    private val createPlayer: () -> ConnectionCuePlayer,
    private val onFailure: () -> Unit = {}
) {
    private val revision = AtomicLong(0)
    // Accessed only on the dispatcher, never while holding the VPN lifecycle lock.
    private var player: ConnectionCuePlayer? = null

    /** Call once after a new tunnel has passed its post-start verification. */
    fun announce(stillEligible: () -> Boolean) {
        val request = revision.incrementAndGet()
        post {
            if (revision.get() != request) return@post
            releaseCurrent()
            if (!eligible(stillEligible)) return@post

            val created = try {
                createPlayer()
            } catch (_: Throwable) {
                reportFailure()
                return@post
            }
            player = created
            // A broken media service must not retain a player indefinitely.
            if (!post(MAX_LIFETIME_MS) { finish(created) }) {
                finish(created)
                return@post
            }
            var started = false
            try {
                created.prepare(
                    onPrepared = {
                        if (player === created && !started) {
                            if (revision.get() == request && eligible(stillEligible)) {
                                started = true
                                try {
                                    created.start()
                                } catch (_: Throwable) {
                                    reportFailure()
                                    finish(created)
                                }
                            } else finish(created)
                        }
                    },
                    onCompletion = { finish(created) },
                    onError = {
                        if (player === created) {
                            reportFailure()
                            finish(created)
                        }
                    }
                )
            } catch (_: Throwable) {
                reportFailure()
                finish(created)
            }
        }
    }

    /** Stop pending or playing audio without waiting for the media service. */
    fun cancel() {
        val request = revision.incrementAndGet()
        post {
            // A newer announcement releases the old player itself. A late cancel
            // must never release that newer announcement instead.
            if (revision.get() == request) releaseCurrent()
        }
    }

    private fun eligible(test: () -> Boolean): Boolean = runCatching { test() }.getOrDefault(false)

    private fun finish(expected: ConnectionCuePlayer) {
        if (player === expected) releaseCurrent()
    }

    private fun releaseCurrent() {
        val previous = player ?: return
        player = null
        try {
            previous.release()
        } catch (_: Throwable) {
            reportFailure()
        }
    }

    private fun post(delayMs: Long = 0, task: () -> Unit): Boolean = try {
        dispatch(delayMs) {
            // No media, scheduler, or callback failure may escape to the VPN.
            try {
                task()
            } catch (_: Throwable) {
                reportFailure()
                releaseCurrent()
            }
        }.also { if (!it) reportFailure() }
    } catch (_: Throwable) {
        reportFailure()
        false
    }

    private fun reportFailure() {
        runCatching { onFailure() }
    }

    private companion object {
        // Includes both preparation and the short recording's playback.
        private const val MAX_LIFETIME_MS = 10_000L
    }
}
