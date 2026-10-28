package ir.v2max.app.net

import android.util.Base64
import ir.v2max.app.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * دریافت و تحلیل اشتراک از طریق HTTP.
 */
object SubscriptionFetcher {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

    suspend fun fetch(url: String): Result = withContext(Dispatchers.IO) {
        val cleaned = url.trim()
        if (cleaned.isEmpty()) return@withContext Result(error = "آدرس اشتراک خالی است")
        try {
            val request = Request.Builder()
                .url(cleaned)
                .header("User-Agent", "V2max/1.0 (Android) v2rayNG")
                .header("Accept", "*/*")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result(error = "خطای سرور: ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result(error = "پاسخ اشتراک خالی بود")
                }
                val configs = LinkParser.parseAll(body)
                if (configs.isEmpty()) {
                    return@withContext Result(error = "هیچ کانفیگ معتبری در اشتراک یافت نشد")
                }
                val usage = parseUserInfo(response.header("subscription-userinfo"))
                val title = response.header("profile-title")?.let { decodeTitle(it) }.orEmpty()
                Result(
                    configs = configs,
                    upload = usage[0],
                    download = usage[1],
                    total = usage[2],
                    expireAt = usage[3],
                    title = title
                )
            }
        } catch (t: Throwable) {
            Result(error = "اتصال به سرور اشتراک ناموفق بود: ${t.message ?: "خطای شبکه"}")
        }
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

    /** upload=..; download=..; total=..; expire=.. */
    private fun parseUserInfo(header: String?): LongArray {
        val out = longArrayOf(0, 0, 0, 0)
        if (header.isNullOrBlank()) return out
        header.split(';').forEach { part ->
            val key = part.substringBefore('=').trim().lowercase()
            val value = part.substringAfter('=', "").trim().toLongOrNull() ?: return@forEach
            when (key) {
                "upload" -> out[0] = value
                "download" -> out[1] = value
                "total" -> out[2] = value
                "expire" -> out[3] = value
            }
        }
        return out
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
