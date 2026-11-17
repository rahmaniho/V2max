package ir.v2max.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.core.geo.GeoAssetInstaller
import ir.v2max.core.geo.GeoAssetStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises same-directory replacement on Android's actual app filesystem. */
@RunWith(AndroidJUnit4::class)
class AndroidGeoAssetTest {
    private fun varint(value: Int): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        while (n >= 128) {
            out.write((n and 127) or 128)
            n = n ushr 7
        }
        out.write(n)
        return out.toByteArray()
    }

    private fun field(number: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(((number shl 3) or 2).toByte()) + varint(bytes.size) + bytes

    private fun asset(code: String, content: Char): ByteArray =
        field(1, field(1, code.toByteArray()) + field(2, content.toString().repeat(100_100).toByteArray()))

    private fun <T> withDir(block: (File) -> T): T {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "geo-test-${System.nanoTime()}")
        assertTrue("Could not create private test directory", directory.mkdir())
        try {
            return block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun malformedDownloadsPreserveInstalledFilesAndRemainUnknown() = withDir { directory ->
        val target = File(directory, "geoip.dat")
        val temporary = File(directory, "geoip.dat.part")
        val previous = asset("IR", 'a')
        target.writeBytes(previous)
        temporary.writeBytes(asset("IR", 'b') + byteArrayOf(10, 100))

        assertTrue("A malformed file must not be diagnosed as absent", GeoAssetStatus.inspect(temporary, listOf("ir")).isFailure)
        assertFalse("A truncated download must not replace the installed file", GeoAssetInstaller.install(temporary, target, listOf("ir")))
        assertArrayEquals(previous, target.readBytes())
        temporary.writeBytes(asset("US", 'c'))
        assertFalse("A download without the requested tag must not replace the installed file", GeoAssetInstaller.install(temporary, target, listOf("ir")))
        assertArrayEquals(previous, target.readBytes())
        assertEquals(false, GeoAssetStatus.readiness(Result.success(null), Result.success("ir")))
        assertNull(GeoAssetStatus.readiness(GeoAssetStatus.inspect(File(directory, "not-a-directory/file"), listOf("ir")), Result.success("ir")))
    }

    @Test fun siblingReplacementDoesNotExposeADeletedOrPartialFile() = withDir { directory ->
        val target = File(directory, "geoip.dat")
        val temporary = File(directory, "geoip.dat.part")
        val first = asset("IR", 'a')
        val second = asset("ir", 'b')
        target.writeBytes(first)
        val started = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val reader = thread(name = "geo-test-reader") {
            started.countDown()
            while (!stop.get()) {
                try {
                    val bytes = target.readBytes()
                    if (!bytes.contentEquals(first) && !bytes.contentEquals(second)) {
                        throw AssertionError("A partial or unexpected file was visible during replacement")
                    }
                    reads.incrementAndGet()
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                    break
                }
            }
        }
        try {
            assertTrue("Concurrent reader did not start", started.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reads.get() == 0 && failure.get() == null && System.nanoTime() < deadline) Thread.yield()
            assertTrue("Concurrent reader did not inspect the initial file", reads.get() > 0)
            repeat(12) { index ->
                temporary.writeBytes(if (index % 2 == 0) second else first)
                assertTrue("A validated file did not replace the previous copy", GeoAssetInstaller.install(temporary, target, listOf("ir")))
            }
        } finally {
            stop.set(true)
            reader.join(5_000)
        }
        assertFalse("Concurrent reader did not stop", reader.isAlive)
        assertNull("A deleted or partial file became visible", failure.get())
        assertTrue(reads.get() > 0)
    }
}
