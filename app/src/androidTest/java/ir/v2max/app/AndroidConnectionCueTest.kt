package ir.v2max.app

import android.media.MediaMetadataRetriever
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.app.service.AndroidConnectionCue
import ir.v2max.app.service.AndroidConnectionCuePlayer
import ir.v2max.app.service.ConnectionCuePlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val released = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val sound = AndroidConnectionCue(
            context,
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
                        check(Looper.myLooper() != null && Looper.myLooper() != Looper.getMainLooper())
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
            sound.cue.announce { true }
            assertTrue("Audio did not start in time", started.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue("Audio did not finish in time", completed.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue("MediaPlayer was not released", released.await(5, TimeUnit.SECONDS))
        } finally {
            sound.close()
            sound.worker.join(5_000)
        }
        assertFalse("Audio worker did not stop", sound.worker.isAlive)
    }

    @Test fun stoppingWorkerReleasesAnUnpreparedPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preparationStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val starts = AtomicInteger()
        val releases = AtomicInteger()
        val failures = AtomicInteger()
        val sound = AndroidConnectionCue(
            context,
            createPlayer = {
                object : ConnectionCuePlayer {
                    override fun prepare(onPrepared: () -> Unit, onCompletion: () -> Unit, onError: () -> Unit) {
                        // Simulate a media service that never reports preparation.
                        preparationStarted.countDown()
                    }
                    override fun start() { starts.incrementAndGet() }
                    override fun release() { releases.incrementAndGet(); released.countDown() }
                }
            },
            onFailure = { failures.incrementAndGet() }
        )
        try {
            sound.cue.announce { true }
            assertTrue("Player was not created", preparationStarted.await(10, TimeUnit.SECONDS))
        } finally {
            sound.close()
            sound.close() // teardown is idempotent
            sound.worker.join(5_000)
        }
        assertTrue("Pending player was not released", released.await(5, TimeUnit.SECONDS))
        assertEquals(0, starts.get())
        assertEquals(1, releases.get())
        assertEquals(0, failures.get())
        assertFalse("Audio worker did not stop", sound.worker.isAlive)
    }
}
