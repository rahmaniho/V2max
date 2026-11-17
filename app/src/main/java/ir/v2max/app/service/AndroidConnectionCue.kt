package ir.v2max.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import ir.v2max.app.R
import java.util.concurrent.atomic.AtomicBoolean

/** The recording is bundled with the app; no TTS engine or network is required. */
internal class AndroidConnectionCuePlayer(private val context: Context) : ConnectionCuePlayer {
    // Constructed on the dedicated audio looper, which also receives callbacks.
    private val mediaPlayer = MediaPlayer()

    override fun prepare(onPrepared: () -> Unit, onCompletion: () -> Unit, onError: () -> Unit) {
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val source = context.resources.openRawResourceFd(R.raw.vpn_connected)
            ?: throw IllegalStateException("connection audio is not available as an asset")
        source.use { mediaPlayer.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        mediaPlayer.setOnPreparedListener { onPrepared() }
        mediaPlayer.setOnCompletionListener { onCompletion() }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            onError()
            true
        }
        mediaPlayer.prepareAsync()
    }

    override fun start() = mediaPlayer.start()

    override fun release() = mediaPlayer.release()
}

/** Owns a worker looper so even slow media setup/release cannot block the UI or VPN. */
internal class AndroidConnectionCue(
    context: Context,
    createPlayer: () -> ConnectionCuePlayer = { AndroidConnectionCuePlayer(context.applicationContext) },
    onFailure: () -> Unit = { Log.w("V2maxVpn", "connection sound unavailable") }
) : AutoCloseable {
    // Visible to instrumentation so it can verify teardown actually terminates it.
    internal val worker = HandlerThread("V2maxConnectionCue")
    private val closed = AtomicBoolean(false)
    val cue: ConnectionCue

    init {
        try {
            worker.start()
            val handler = Handler(worker.looper)
            cue = ConnectionCue(
                dispatch = { delay, task ->
                    if (delay == 0L) handler.post { task() } else handler.postDelayed({ task() }, delay)
                },
                createPlayer = createPlayer,
                // Do not log media exceptions, which can include platform-specific paths.
                onFailure = onFailure
            )
        } catch (failure: Throwable) {
            runCatching { worker.quitSafely() }
            throw failure
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // quitSafely drains the already-posted release but drops future timeouts.
            cue.cancel()
            worker.quitSafely()
        }
    }
}
