package ir.v2max.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import ir.v2max.app.R

/** The recording is bundled with the app; no TTS engine or network is required. */
internal class AndroidConnectionCuePlayer(private val context: Context) : ConnectionCuePlayer {
    // Constructed on the main looper, so MediaPlayer callbacks use that looper.
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

internal fun connectionCue(context: Context): ConnectionCue {
    val applicationContext = context.applicationContext
    val handler = Handler(Looper.getMainLooper())
    return ConnectionCue(
        dispatch = { delay, task ->
            if (delay == 0L) handler.post { task() } else handler.postDelayed({ task() }, delay)
        },
        createPlayer = { AndroidConnectionCuePlayer(applicationContext) },
        // Do not log media exceptions, which can include platform-specific paths.
        onFailure = { Log.w("V2maxVpn", "connection sound unavailable") }
    )
}
