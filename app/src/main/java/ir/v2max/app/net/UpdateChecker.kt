package ir.v2max.app.net

import android.os.Build
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ir.v2max.app.BuildConfig
import ir.v2max.app.data.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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
    private const val RELEASE_PAGE_PREFIX = "https://github.com/$OWNER/$REPO/releases/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Available(val info: UpdateInfo) : Result
        data object Current : Result
        data object Unavailable : Result
    }

    /** A failed network request is not evidence that the app is up to date. */
    suspend fun checkForUpdate(): Result = checkForUpdate(::body, BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())

    /** Injectable I/O for deterministic tests; production always reads the GitHub API. */
    internal suspend fun checkForUpdate(
        fetch: (String) -> String,
        currentVersion: String,
        supportedAbis: List<String>
    ): Result = withContext(Dispatchers.IO) {
        try {
            val info = try {
                checkList(fetch, currentVersion, supportedAbis)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                checkLatest(fetch, currentVersion, supportedAbis)
            }
            if (info == null) Result.Current else Result.Available(info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.Unavailable
        }
    }

    private fun body(url: String): String = client.newCall(request(url)).execute().use { response ->
        if (!response.isSuccessful) throw IOException("release API returned ${response.code}")
        response.body?.string()?.takeIf { it.isNotBlank() } ?: throw IOException("empty release response")
    }

    private fun JsonObject.stringOrEmpty(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "V2max/${BuildConfig.VERSION_NAME}")
        .get()
        .build()

    /** جدیدترین نسخه را از میان انتشارهای اخیر انتخاب می‌کند */
    private fun checkList(fetch: (String) -> String, currentVersion: String, abis: List<String>): UpdateInfo? {
        val releases = JsonParser.parseString(fetch(API_LIST)).asJsonArray.map { it.asJsonObject }

        var bestVersion = ""
        var bestJson: JsonObject? = null
        releases.forEach { json ->
            // انتشارهای آزمایشی هرگز پیشنهاد نمی‌شوند؛ فقط نسخه‌ی پایدار
            if (json.booleanOrFalse("prerelease") || json.booleanOrFalse("draft")) {
                return@forEach
            }
            val notes = json.stringOrEmpty("body")
            val version = normalize(json.stringOrEmpty("tag_name"))
                .ifBlank { normalize(json.stringOrEmpty("name")) }
                .ifBlank { versionFromNotes(notes) }
                .ifBlank { versionFromAssets(json) }
            if (version.isNotBlank() && compare(version, bestVersion) > 0) {
                bestVersion = version
                bestJson = json
            }
        }

        val json = bestJson ?: throw IOException("no versioned stable release")
        if (compare(bestVersion, normalize(currentVersion)) <= 0) return null
        return toInfo(bestVersion, json, abis)
    }

    /** مسیر پشتیبان: تنها انتشار «latest» */
    private fun checkLatest(fetch: (String) -> String, currentVersion: String, abis: List<String>): UpdateInfo? {
        val json = JsonParser.parseString(fetch(API_LATEST)).asJsonObject
        if (json.booleanOrFalse("prerelease") || json.booleanOrFalse("draft")) {
            throw IOException("latest release is not stable")
        }
        val notes = json.stringOrEmpty("body")
        val version = normalize(json.stringOrEmpty("tag_name"))
            .ifBlank { normalize(json.stringOrEmpty("name")) }
            .ifBlank { versionFromNotes(notes) }
            .ifBlank { versionFromAssets(json) }
        if (version.isBlank()) throw IOException("release version is missing")
        if (compare(version, normalize(currentVersion)) <= 0) return null
        return toInfo(version, json, abis)
    }

    private fun toInfo(version: String, json: JsonObject, abis: List<String>): UpdateInfo {
        val assets = runCatching {
            json.getAsJsonArray("assets").mapNotNull { element ->
                val asset = element.asJsonObject
                val name = asset.stringOrEmpty("name")
                val url = asset.stringOrEmpty("browser_download_url")
                if (name.isBlank() || url.isBlank()) null else UpdateAssets.Asset(name, url)
            }
        }.getOrDefault(emptyList())
        val assetUrl = UpdateAssets.select(assets, abis)?.url.orEmpty()
        val releasePage = json.stringOrEmpty("html_url")
            .takeIf { it.startsWith(RELEASE_PAGE_PREFIX, ignoreCase = true) } ?: PAGE

        val notes = json.stringOrEmpty("body")
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(12)
            .joinToString("\n")
            .take(900)

        return UpdateInfo(
            version = version,
            notes = notes,
            pageUrl = releasePage,
            apkUrl = assetUrl,
            publishedAt = json.stringOrEmpty("published_at").take(10)
        )
    }

    /** نسخه را از نام فایل‌های APK استخراج می‌کند */
    private fun versionFromAssets(json: JsonObject): String = runCatching {
        json.getAsJsonArray("assets").map { it.asJsonObject.stringOrEmpty("name") }
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

    /** Do not mistake the 2 in the product name V2max for a release version. */
    private fun normalize(raw: String): String {
        val text = raw.trim()
        val dotted = Regex("""(?<![A-Za-z0-9])v?(\d+(?:\.\d+){1,3})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
            .find(text)
        if (dotted != null) return dotted.groupValues[1]
        return if (Regex("""[vV]?\d+""").matches(text)) text.removePrefix("v").removePrefix("V") else ""
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
