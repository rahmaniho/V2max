package ir.v2max.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * مدیریت فایل‌های geodata هسته (geoip.dat / geosite.dat).
 *
 * چرا لازم است: مسیریابی «دامنه‌های ایرانی مستقیم» با لیست کوچک CIDR تقریبی است.
 * با فایل‌های رسمی geoip/geosite، دامنه و IPهای ایران کامل و به‌روز تشخیص داده می‌شوند؛
 * نتیجه: سایت‌های داخلی سریع‌تر باز می‌شوند و ترافیک/باتری کمتری مصرف می‌شود.
 *
 * طراحی محافظه‌کارانه:
 *  • اگر فایل موجود نباشد، کانفیگ دقیقاً مثل قبل (CIDR دستی) ساخته می‌شود.
 *  • نام tag قبل از استفاده در فایل جست‌وجو می‌شود؛ اگر پیدا نشد از آن tag استفاده نمی‌کنیم
 *    تا هسته به‌خاطر فایل ناسازگار از کار نیفتد.
 */
object GeoAssets {

    private const val TAG = "GeoAssets"
    private const val GEOIP_FILE = "geoip.dat"
    private const val GEOSITE_FILE = "geosite.dat"

    /** حداکثر عمر مجاز فایل‌ها (۷ روز) */
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private const val GEOIP_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
    private const val GEOSITE_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"

    @Volatile
    private var dir: File? = null

    @Volatile
    private var geoipTag: String? = null

    @Volatile
    private var geositeTag: String? = null

    /** مسیر پوشه‌ی assetهای هسته (همان مسیری که XrayCore.init استفاده می‌کند) */
    fun init(context: Context) {
        if (dir != null) return
        val d = context.applicationContext.getDir("assets", Context.MODE_PRIVATE)
        dir = d
        refreshTags(d)
    }

    private fun refreshTags(d: File) {
        geoipTag = findTag(File(d, GEOIP_FILE), listOf("ir"))
        geositeTag = findTag(File(d, GEOSITE_FILE), listOf("category-ir", "geolocation-ir", "ir"))
    }

    /**
     * tag موجود در فایل protobuf را پیدا می‌کند؛ چون tagها به‌صورت رشته در فایل ذخیره شده‌اند
     * جست‌وجوی بایتی کافی و ارزان است.
     */
    private fun findTag(file: File, candidates: List<String>): String? {
        if (!file.exists() || file.length() < 1024) return null
        return runCatching {
            val bytes = file.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            candidates.firstOrNull { text.contains(it) }
        }.getOrNull()
    }

    fun hasGeoip(): Boolean = geoipTag != null

    fun hasGeosite(): Boolean = geositeTag != null

    fun geoipTag(): String? = geoipTag

    fun geositeTag(): String? = geositeTag

    fun isReady(): Boolean = hasGeoip() || hasGeosite()

    fun lastUpdate(context: Context): Long {
        val d = context.applicationContext.getDir("assets", Context.MODE_PRIVATE)
        val a = File(d, GEOIP_FILE).lastModified()
        val b = File(d, GEOSITE_FILE).lastModified()
        return maxOf(a, b)
    }

    private fun isStale(context: Context): Boolean {
        val d = context.applicationContext.getDir("assets", Context.MODE_PRIVATE)
        val geoip = File(d, GEOIP_FILE)
        val geosite = File(d, GEOSITE_FILE)
        if (!geoip.exists() || !geosite.exists()) return true
        if (geoip.length() < 100_000L || geosite.length() < 100_000L) return true
        return System.currentTimeMillis() - lastUpdate(context) > MAX_AGE_MS
    }

    /**
     * دانلود به‌روزترین فایل‌ها در صورت نبود یا کهنه بودن.
     * خطاها بی‌صدا نادیده گرفته می‌شوند (اپ باید بدون این فایل‌ها هم کار کند).
     */
    suspend fun syncIfNeeded(context: Context, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            init(context)
            val d = dir ?: return@withContext false
            if (!force && !isStale(context)) return@withContext isReady()

            var ok = false
            ok = download(File(d, GEOIP_FILE), GEOIP_URL) || ok
            ok = download(File(d, GEOSITE_FILE), GEOSITE_URL) || ok
            refreshTags(d)
            Log.i(TAG, "geo assets updated: geoip=${hasGeoip()} geosite=${hasGeosite()}")
            ok
        }

    private fun download(target: File, url: String): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "V2max")
        }
        conn.inputStream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()
        if (tmp.length() < 100_000L) {
            tmp.delete()
            return@runCatching false
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }.getOrDefault(false)
}
