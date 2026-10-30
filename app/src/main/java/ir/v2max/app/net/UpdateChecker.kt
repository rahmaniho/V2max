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
 * ابتدا فهرست انتشارها خوانده می‌شود (چون بعضی تگ‌ها عددی نیستند مثل «apk-latest»)
 * و نسخه از تگ، نام، متن یادداشت‌ها یا نام فایل APK استخراج می‌شود.
 */
object UpdateChecker {

    private const val OWNER = "rahmaniho"
    private const val REPO = "V2max"
    private const val API_LIST = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val PAGE = "https://github.com/$OWNER/$REPO/releases/latest"
    private const val APK = "https://github.com/$OWNER/$REPO/releases/latest/download/app-universal-release.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** خروجی: اطلاعات نسخه‌ی جدید یا null اگر به‌روز باشیم */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching { checkList() }.getOrNull()
            ?: runCatching { checkLatest() }.getOrNull()
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "V2max/${BuildConfig.VERSION_NAME}")
        .get()
        .build()

    /** جدیدترین نسخه را از میان انتشارهای اخیر انتخاب می‌کند */
    private fun checkList(): UpdateInfo? {
        val body = client.newCall(request(API_LIST)).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        } ?: return null
        if (body.isBlank()) return null

        val releases = JsonParser.parseString(body).asJsonArray.map { it.asJsonObject }

        var bestVersion = ""
        var bestJson: JsonObject? = null
        releases.forEach { json ->
            // انتشارهای آزمایشی هرگز پیشنهاد نمی‌شوند؛ فقط نسخه‌ی پایدار
            if (json.get("prerelease")?.asBoolean == true || json.get("draft")?.asBoolean == true) {
                return@forEach
            }
            val notes = json.get("body")?.asString.orEmpty()
            val version = normalize(json.get("tag_name")?.asString.orEmpty())
                .ifBlank { normalize(json.get("name")?.asString.orEmpty()) }
                .ifBlank { versionFromNotes(notes) }
                .ifBlank { versionFromAssets(json) }
            if (version.isNotBlank() && compare(version, bestVersion) > 0) {
                bestVersion = version
                bestJson = json
            }
        }

        val json = bestJson ?: return null
        if (compare(bestVersion, normalize(BuildConfig.VERSION_NAME)) <= 0) return null
        return toInfo(bestVersion, json)
    }

    /** مسیر پشتیبان: تنها انتشار «latest» */
    private fun checkLatest(): UpdateInfo? {
        val body = client.newCall(request(API_LATEST)).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        } ?: return null
        if (body.isBlank()) return null

        val json = JsonParser.parseString(body).asJsonObject
        val notes = json.get("body")?.asString.orEmpty()
        val version = normalize(json.get("tag_name")?.asString.orEmpty())
            .ifBlank { normalize(json.get("name")?.asString.orEmpty()) }
            .ifBlank { versionFromNotes(notes) }
            .ifBlank { versionFromAssets(json) }
        if (version.isBlank()) return null
        if (compare(version, normalize(BuildConfig.VERSION_NAME)) <= 0) return null
        return toInfo(version, json)
    }

    private fun toInfo(version: String, json: JsonObject): UpdateInfo {
        val assetUrl = runCatching {
            json.getAsJsonArray("assets").map { it.asJsonObject }
                .firstOrNull { it.get("name")?.asString?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.asString
        }.getOrNull()

        val notes = json.get("body")?.asString.orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(12)
            .joinToString("\n")
            .take(900)

        return UpdateInfo(
            version = version,
            notes = notes,
            pageUrl = PAGE,
            apkUrl = assetUrl ?: APK,
            publishedAt = json.get("published_at")?.asString.orEmpty().take(10)
        )
    }

    /** نسخه را از نام فایل‌های APK استخراج می‌کند */
    private fun versionFromAssets(json: JsonObject): String = runCatching {
        json.getAsJsonArray("assets").map { it.asJsonObject.get("name")?.asString.orEmpty() }
            .firstNotNullOfOrNull { name ->
                Regex("""\d+\.\d+(?:\.\d+)?""").find(name)?.value
            }.orEmpty()
    }.getOrDefault("")

    /** استخراج نسخه از متن یادداشت‌های انتشار، مثلاً «نسخه‌ی 1.3.0» */
    private fun versionFromNotes(notes: String): String {
        val match = Regex("""(?:نسخه‌ی|version|v)\s*([0-9]+(?:\.[0-9]+){1,2})""", RegexOption.IGNORE_CASE)
            .find(notes) ?: return ""
        return match.groupValues.getOrNull(1).orEmpty()
    }

    /** نسخه‌ها را به شکل عددی نرمال می‌کند: v1.2.0 → 1.2.0 */
    private fun normalize(raw: String): String {
        val digits = raw.trim().removePrefix("v").removePrefix("V")
        val match = Regex("""\d+(\.\d+)*""").find(digits) ?: return ""
        return match.value
    }

    /** مقایسه‌ی نسخه‌ها؛ مثبت یعنی a جدیدتر است */
    private fun compare(a: String, b: String): Int {
        if (a.isBlank() && b.isBlank()) return 0
        if (a.isBlank()) return -1
        if (b.isBlank()) return 1
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
