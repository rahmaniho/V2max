package ir.v2max.app

import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.app.service.AndroidConnectionCuePlayer
import ir.v2max.app.service.ConnectionCue
import ir.v2max.app.service.ConnectionCuePlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real bundled recording and Android media API on an emulator. */
@RunWith(AndroidJUnit4::class)
class AndroidConnectionCueTest {
    @Test fun bundledRecordingPreparesStartsCompletesAndReleases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.resources.openRawResource(R.raw.vpn_connected).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("Wrong bundled voice recording",
            "5aedd569be17e5d4d6cdacb582c75bcf881a58d804561d6762f8b71fead62101", digest)

        // openRawResourceFd and setDataSource both require an uncompressed asset.
        val source = context.resources.openRawResourceFd(R.raw.vpn_connected)
        assertNotNull("Bundled audio was compressed", source)
        val metadata = MediaMetadataRetriever()
        try {
            source!!.use { metadata.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            assertTrue("Unexpected clip duration: $duration", duration != null && duration in 2_000L..6_000L)
        } finally {
            metadata.release()
        }

        val handler = Handler(Looper.getMainLooper())
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val released = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val cue = ConnectionCue(
            dispatch = { delay, task ->
                if (delay == 0L) handler.post { task() } else handler.postDelayed({ task() }, delay)
            },
            createPlayer = {
                val real = AndroidConnectionCuePlayer(context)
                object : ConnectionCuePlayer {
                    override fun prepare(onPrepared: () -> Unit, onCompletion: () -> Unit, onError: () -> Unit) {
                        real.prepare(onPrepared, {
                            onCompletion()
                            completed.countDown()
                        }, {
                            failure.compareAndSet(null, "MediaPlayer callback reported an error")
                            onError()
                            started.countDown()
                            completed.countDown()
                        })
                    }
                    override fun start() {
                        real.start()
                        started.countDown()
                    }
                    override fun release() {
                        try { real.release() } finally { released.countDown() }
                    }
                }
            },
            onFailure = {
                failure.compareAndSet(null, "MediaPlayer setup or playback failed")
                started.countDown()
                completed.countDown()
            }
        )
        try {
            cue.announce { true }
            assertTrue("Audio did not start in time", started.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue("Audio did not finish in time", completed.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue("MediaPlayer was not released", released.await(5, TimeUnit.SECONDS))
        } finally {
            cue.cancel()
        }
    }
}
