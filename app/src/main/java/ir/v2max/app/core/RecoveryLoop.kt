package ir.v2max.app.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Retry only after a full new preflight. A stopped service or newer user
 * action invalidates the loop both before and after each potentially slow scan.
 */
internal object RecoveryLoop {
    private val delaysMs = longArrayOf(10_000L, 20_000L, 40_000L, 80_000L, 120_000L)

    internal fun delayFor(attempt: Int): Long = delaysMs[attempt.coerceIn(0, delaysMs.lastIndex)]

    suspend fun <T : Any> awaitVerified(
        isCurrent: () -> Boolean,
        initialAttempt: Int = 0,
        wait: suspend (Long) -> Unit = { delay(it) },
        onAttempt: (nextAttempt: Int) -> Unit = {},
        findVerified: suspend () -> T?
    ): T? {
        var attempt = initialAttempt.coerceIn(0, delaysMs.lastIndex)
        while (isCurrent()) {
            wait(delayFor(attempt))
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return null
            val nextAttempt = (attempt + 1).coerceAtMost(delaysMs.lastIndex)
            // Remember the backoff across a verified profile that then fails to
            // start, otherwise a flapping server could trigger rapid retries.
            onAttempt(nextAttempt)
            val result = findVerified()
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return null
            if (result != null) return result
            attempt = nextAttempt
        }
        return null
    }
}
