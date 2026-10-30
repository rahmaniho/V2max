package ir.v2max.app.net

import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

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
            try {
                XrayCore.start(config, 0)
                if (!XrayCore.isRunning) return@withContext 0
                // کمی صبر تا inbound آماده شود
                delay(350)

                val request = Request.Builder()
                    .url(TEST_URL)
                    .header("User-Agent", "V2max-SpeedTest")
                    .get()
                    .build()

                var totalBytes = 0L
                val start = System.currentTimeMillis()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext 0
                    val body = response.body ?: return@withContext 0
                    val stream = body.byteStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes >= MAX_BYTES) break
                        if (System.currentTimeMillis() - start > 12_000L) break
                    }
                }
                val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                if (totalBytes < 20_000) return@withContext 0

                // کیلوبیت بر ثانیه
                val kbps = ((totalBytes * 8L) / elapsedMs).toInt()
                kbps.coerceIn(0, 999_999)
            } catch (t: Throwable) {
                0
            } finally {
                runCatching { XrayCore.stop() }
            }
        }
}
