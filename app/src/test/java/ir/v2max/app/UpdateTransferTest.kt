package ir.v2max.app

import ir.v2max.app.net.UpdateTransfer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UpdateTransferTest {
    @get:Rule val files = TemporaryFolder()

    private class FakeCall : Call {
        private val request = Request.Builder().url("https://example.org/update.apk").build()
        var callback: Callback? = null
        @Volatile var cancelled = false
        var onCancel: () -> Unit = {}
        override fun request(): Request = request
        override fun execute(): Response = throw IOException("not used")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true; onCancel() }
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall()
        fun reply(body: ResponseBody) {
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
            checkNotNull(callback).onResponse(this, response)
        }
    }

    @Test fun `download writes response and reports progress`() = runBlocking {
        val call = FakeCall()
        val file = files.newFile("download.apk")
        var progress = 0L
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            UpdateTransfer.save(call, file, { done, _ -> progress = done })
        }
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        call.reply(bytes.toResponseBody())
        assertEquals(UpdateTransfer.Result.Complete, task.await())
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), progress)
    }

    @Test fun `cancelling the download cancels its network call`() = runBlocking {
        val call = FakeCall()
        val file = files.newFile("cancelled.apk")
        val task = async(start = CoroutineStart.UNDISPATCHED) { UpdateTransfer.save(call, file, { _, _ -> }) }
        assertNotNull(call.callback)
        task.cancelAndJoin()
        assertTrue(call.cancelled)
        // A response racing with cancellation must not recreate an abandoned APK.
        call.reply(ByteArray(20).toResponseBody())
        assertFalse(file.exists())
    }

    @Test fun `cancelling a blocked response closes the call and removes its partial file`() = runBlocking {
        val call = FakeCall()
        val file = files.newFile("partial.apk")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        call.onCancel = { release.countDown() }
        val body = object : ResponseBody() {
            private val blocking = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    throw IOException("cancelled")
                }
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() { release.countDown() }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = blocking
        }
        val task = async(start = CoroutineStart.UNDISPATCHED) { UpdateTransfer.save(call, file, { _, _ -> }) }
        val responseThread = thread { call.reply(body) }
        assertTrue("Response never started reading", entered.await(5, TimeUnit.SECONDS))
        task.cancelAndJoin()
        responseThread.join(5_000)
        assertFalse("Response thread stayed blocked", responseThread.isAlive)
        assertTrue(call.cancelled)
        assertFalse(file.exists())
    }

    @Test fun `unknown length responses cannot exceed the maximum`() = runBlocking {
        val call = FakeCall()
        val file = files.newFile("oversize.apk")
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            UpdateTransfer.save(call, file, { _, _ -> }, maxBytes = 10)
        }
        val body = object : ResponseBody() {
            private val content = Buffer().write(ByteArray(11))
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = content
        }
        call.reply(body)
        assertTrue(task.await() is UpdateTransfer.Result.Failure)
        assertTrue(file.length() <= 10)
    }
}
