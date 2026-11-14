package ir.v2max.app

import ir.v2max.app.net.SubscriptionBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class SubscriptionBodyTest {
    @Test fun `accepts a response exactly at the byte limit`() {
        val bytes = "۱۲۳-vless".toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, SubscriptionBody.read(ByteArrayInputStream(bytes), bytes.size.toLong(), bytes.size))
    }

    @Test fun `rejects advertised oversize before reading`() {
        val input = object : InputStream() {
            override fun read(): Int = error("oversized response must not be read")
        }
        assertThrows(SubscriptionBody.TooLarge::class.java) { SubscriptionBody.read(input, 11, 10) }
    }

    @Test fun `rejects streamed oversize even without a content length`() {
        val bytes = ByteArray(20) { it.toByte() }
        assertThrows(SubscriptionBody.TooLarge::class.java) {
            SubscriptionBody.read(ByteArrayInputStream(bytes), -1, 10)
        }
    }

    @Test fun `rejects streamed oversize even when the server advertises a smaller length`() {
        val bytes = ByteArray(20) { it.toByte() }
        assertThrows(SubscriptionBody.TooLarge::class.java) {
            SubscriptionBody.read(ByteArrayInputStream(bytes), 2, 10)
        }
    }

    @Test fun `propagates stream failures`() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("broken")
        }
        assertThrows(IOException::class.java) { SubscriptionBody.read(input, -1, 10) }
    }
}
