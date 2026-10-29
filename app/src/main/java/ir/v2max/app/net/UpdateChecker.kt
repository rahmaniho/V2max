package ir.v2max.app.net

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ir.v2max.app.BuildConfig
import ir.v2max.app.data.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * بررسی نسخه‌ی جدید از صفحه‌ی انتشار گیت‌هاب.
 * اطلاعات نسخه، یادداشت‌های انتشار و لینک مستقیم APK خوانده می‌شود.
 */
object UpdateChecker {

    private const val OWNER = "rahmaniho"
    private const val REPO = "V2max"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val PAGE = "https://github.com/$OWNER/$REPO/releases/latest"
    private const val APK = "https://github.com/$OWNER/$REPO/releases/latest/download/app-universal-release.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** خروجی: اطلاعات نسخه‌ی جدید یا null اگر به‌روز باشیم */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "V2max/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null

                val json = JsonParser.parseString(body).asJsonObject
                val tag = json.get("tag_name")?.asString.orEmpty()
                val name = json.get("name")?.asString.orEmpty()
                val notes = json.get("body")?.asString.orEmpty()
                val published = json.get("published_at")?.asString.orEmpty()

                val remote = normalize(tag.ifBlank { name })
                if (remote.isBlank()) return@withContext null
                if (compare(remote, normalize(BuildConfig.VERSION_NAME)) <= 0) return@withContext null

                val assetUrl = runCatching {
                    json.getAsJsonArray("assets")
                        .map { it.asJsonObject }
                        .firstOrNull { it.get("name")?.asString?.endsWith(".apk") == true }
                        ?.get("browser_download_url")?.asString
                }.getOrNull()

                // نام و یادداشت‌ها ممکن است شامل «آخرین نسخه» باشد؛ پیام کوتاه و تمیز می‌سازیم
                val cleanNotes = notes
                    .lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .take(12)
                    .joinToString("\n")
                    .take(900)

                UpdateInfo(
                    version = remote,
                    notes = cleanNotes.ifBlank { name },
                    pageUrl = PAGE,
                    apkUrl = assetUrl ?: APK,
                    publishedAt = published.take(10)
                )
            }
        }.getOrNull()
    }

    /** نسخه‌ها را به شکل عددی نرمال می‌کند: v1.2.0 → 1.2.0 */
    private fun normalize(raw: String): String {
        val digits = raw.trim().removePrefix("v").removePrefix("V")
        val match = Regex("""\d+(\.\d+)*""").find(digits) ?: return ""
        return match.value
    }

    /** مقایسه‌ی نسخه‌ها؛ مثبت یعنی a جدیدتر است */
    private fun compare(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
