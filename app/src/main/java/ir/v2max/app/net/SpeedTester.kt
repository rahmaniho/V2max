package ir.v2max.app.net

import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * سنجش سرعت دانلود واقعی از طریق یک کانفیگ، پیش از اتصال تانل.
 *
 * روش کار: هسته با یک inbound از نوع SOCKS روی پورت محلی اجرا می‌شود، سپس یک فایل
 * تست کوچک از طریق همان پروکسی دانلود و پهنای باند محاسبه می‌شود. در پایان هسته متوقف می‌شود.
 * این کار فقط زمانی انجام می‌شود که تانلی در حال اجرا نباشد.
 */
object SpeedTester {

    /** منبع تست سبک و پرسرعت (Cloudflare) */
    private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=1200000"

    private const val SOCKS_PORT = 10888
    private const val MAX_BYTES = 1_500_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT)))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * سرعت دانلود را بر حسب کیلوبیت بر ثانیه برمی‌گرداند؛ ۰ یعنی ناموفق.
     * @return کیلوبیت بر ثانیه
     */
    suspend fun measure(server: ServerConfig, settings: AppSettings): Int =
        withContext(Dispatchers.IO) {
            if (XrayCore.isRunning) return@withContext 0
            val config = ConfigBuilder.socksTestConfig(server, settings, SOCKS_PORT)
            var lease: Long? = null
            try {
                lease = XrayCore.startSpeedTest(config) ?: return@withContext 0
                if (!XrayCore.isRunning) return@withContext 0
                // Proceed as soon as the local inbound is actually listening.
                if (!awaitSocksReady()) return@withContext 0

                val request = Request.Builder()
                    .url(TEST_URL)
                    .header("User-Agent", "V2max-SpeedTest")
                    .get()
                    .build()

                downloadKbps(request)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            } finally {
                lease?.let { runCatching { XrayCore.stopSpeedTest(it) } }
            }
        }

    /** Cancellation closes the HTTP stream rather than waiting for the read timeout. */
    private suspend fun downloadKbps(request: Request): Int = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        val start = System.nanoTime()
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(0)
            }

            override fun onResponse(call: Call, response: Response) {
                val kbps = try {
                    response.use {
                        val body = it.body
                        if (!it.isSuccessful || body == null) 0 else {
                            var totalBytes = 0L
                            val stream = body.byteStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read <= 0) break
                                totalBytes += read
                                if (totalBytes >= MAX_BYTES ||
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > 12_000L) break
                            }
                            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                            if (totalBytes < 20_000) 0 else ((totalBytes * 8L) / elapsed).coerceIn(0, 999_999).toInt()
                        }
                    }
                } catch (_: Exception) {
                    0
                }
                if (continuation.isActive) continuation.resume(kbps)
            }
        })
    }

    private suspend fun awaitSocksReady(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val ready = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 100) }
                true
            } catch (_: Exception) {
                false
            }
            if (ready) return true
            delay(25)
        }
        return false
    }
}
