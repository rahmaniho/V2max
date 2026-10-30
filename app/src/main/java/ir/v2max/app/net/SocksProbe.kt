package ir.v2max.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * سنجش تأخیر از طریق inbound محلی SOCKS هسته.
 * برای هر دو هسته (Xray و sing-box) یکسان کار می‌کند و در مسیر sing-box
 * جایگزین `XrayCore.measureDelay` است.
 */
object SocksProbe {

    suspend fun delay(
        port: Int = 10888,
        url: String,
        timeoutMs: Long = 8_000L
    ): Long = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "V2max")
            .build()
        val started = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code in 200..399) {
                    System.currentTimeMillis() - started
                } else {
                    -1L
                }
            }
        } catch (t: Throwable) {
            -1L
        }
    }
}
