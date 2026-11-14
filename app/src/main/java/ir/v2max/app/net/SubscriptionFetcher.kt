package ir.v2max.app.net

import android.util.Base64
import ir.v2max.app.data.ServerConfig
import ir.v2max.core.model.SubscriptionUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * دریافت و تحلیل اشتراک از طریق HTTP.
 */
object SubscriptionFetcher {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    data class Result(
        val configs: List<ServerConfig> = emptyList(),
        val upload: Long = 0L,
        val download: Long = 0L,
        val total: Long = 0L,
        val expireAt: Long = 0L,
        val title: String = "",
        val error: String = ""
    ) {
        val ok: Boolean get() = error.isEmpty()
    }

    /**
     * آدرس‌های جایگزین برای منابع گیت‌هابی.
     * چون raw.githubusercontent.com در ایران معمولاً مسدود/کند است،
     * اگر اتصال مستقیم شکست بخورد از آینه‌ی jsDelivr استفاده می‌شود.
     */
    fun mirrorsFor(url: String): List<String> {
        val out = ArrayList<String>()
        out.add(url)
        val clean = url.trim()
        val prefix = "https://raw.githubusercontent.com/"
        if (clean.startsWith(prefix)) {
            val rest = clean.removePrefix(prefix).split('/')
            if (rest.size >= 4) {
                val owner = rest[0]
                val repo = rest[1]
                val branch = rest[2]
                val path = rest.drop(3).joinToString("/")
                out.add("https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path")
                out.add("https://ghproxy.net/$prefix$owner/$repo/$branch/$path")
            }
        }
        return out
    }

    suspend fun fetch(url: String, maxConfigs: Int = 0): Result = withContext(Dispatchers.IO) {
        val cleaned = url.trim()
        if (cleaned.isEmpty()) return@withContext Result(error = "آدرس اشتراک خالی است")

        var last = Result(error = "دریافت اشتراک ناموفق بود")
        val candidates = mirrorsFor(cleaned)
        for ((index, candidate) in candidates.withIndex()) {
            val result = fetchOnce(candidate, maxConfigs)
            if (result.ok) return@withContext result
            last = result
            if (index < candidates.size - 1) {
                kotlinx.coroutines.delay(if (index == 0) 600 else 1200)
            }
        }
        last
    }

    private suspend fun fetchOnce(url: String, maxConfigs: Int): Result = withContext(Dispatchers.IO) {
        val cleaned = url.trim()
        try {
            val request = Request.Builder()
                .url(cleaned)
                .header("User-Agent", "V2max/1.0 (Android) v2rayNG")
                .header("Accept", "*/*")
                .get()
                .build()

            val response = awaitResponse(request)
            currentCoroutineContext().ensureActive()
            if (response.error.isNotEmpty()) return@withContext Result(error = response.error)
            if (response.body.isBlank()) return@withContext Result(error = "پاسخ اشتراک خالی بود")
            val configs = LinkParser.parseAll(response.body, maxConfigs = maxConfigs)
            currentCoroutineContext().ensureActive()
            if (configs.isEmpty()) return@withContext Result(error = "هیچ کانفیگ معتبری در اشتراک یافت نشد")
            val usage = SubscriptionUsage.parseHeader(response.userInfo)
            val title = response.title?.let { decodeTitle(it) }.orEmpty()
            Result(
                configs = configs,
                upload = usage.upload,
                download = usage.download,
                total = usage.total,
                expireAt = usage.expireAt,
                title = title
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result(error = "اتصال به سرور اشتراک ناموفق بود")
        }
    }

    private data class ResponseData(
        val body: String = "",
        val userInfo: String? = null,
        val title: String? = null,
        val error: String = ""
    )

    /** Cancelling a bootstrap/refresh also closes a slow in-flight HTTP call. */
    private suspend fun awaitResponse(request: Request): ResponseData = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(ResponseData(error = "اتصال به سرور اشتراک ناموفق بود"))
            }

            override fun onResponse(call: Call, response: Response) {
                val data = try {
                    response.use {
                        if (!it.isSuccessful) ResponseData(error = "خطای سرور: ${it.code}")
                        else ResponseData(
                            body = it.body?.string().orEmpty(),
                            userInfo = it.header("subscription-userinfo"),
                            title = it.header("profile-title")
                        )
                    }
                } catch (_: Exception) {
                    ResponseData(error = "خواندن پاسخ اشتراک ناموفق بود")
                }
                if (continuation.isActive) continuation.resume(data)
            }
        })
    }

    private fun decodeTitle(value: String): String {
        val raw = value.trim()
        val base64Part = if (raw.startsWith("base64:")) raw.removePrefix("base64:") else return raw
        return try {
            String(Base64.decode(base64Part, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (t: Throwable) {
            raw
        }
    }

    /** خواندن متن کانفیگ از آدرس (برای import لینک subscription یا QR) */
    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", "V2max/1.0").build()
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (t: Throwable) {
            null
        }
    }
}
