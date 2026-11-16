package ir.v2max.app.core

import android.content.Context
import android.util.Log
import ir.v2max.core.geo.GeoAssetInstaller
import ir.v2max.core.geo.GeoAssetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * مدیریت فایل‌های geodata هسته (geoip.dat / geosite.dat).
 *
 * فایل‌های شخص ثالث، دسته‌های IP و دامنه را برای قواعد مسیریابی فراهم می‌کنند؛
 * یافتن نام دسته‌ها تضمین پوشش کامل یا درستی محتوای فایل‌ها نیست.
 *
 * طراحی محافظه‌کارانه:
 *  • در نبود فایل، قواعد جایگزین ساخته می‌شوند و اتصال صرفاً به وجود فایل وابسته نیست.
 *  • نام tag قبل از استفاده در فایل جست‌وجو می‌شود؛ اگر پیدا نشد از آن tag استفاده نمی‌کنیم
 *    تا احتمال خطای هسته در فایل ناسازگار کاهش یابد.
 */
object GeoAssets {

    private const val TAG = "GeoAssets"
    private const val GEOIP_FILE = "geoip.dat"
    private const val GEOSITE_FILE = "geosite.dat"
    private val GEOIP_TAGS = listOf("ir")
    private val GEOSITE_TAGS = listOf("category-ir", "geolocation-ir", "ir")

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

    // Null until inspection finishes, during refresh, or when a read/parse
    // fails. A failed inspection must not be reported as a missing file.
    private val _diagnosticReadiness = MutableStateFlow<Boolean?>(null)
    val diagnosticReadiness = _diagnosticReadiness.asStateFlow()
    private val syncMutex = Mutex()

    /** مسیر پوشه‌ی assetهای هسته (همان مسیری که XrayCore.init استفاده می‌کند) */
    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = context.applicationContext.getDir("assets", Context.MODE_PRIVATE)
        refreshTags(d)
        dir = d
    }

    @Synchronized
    private fun refreshTags(d: File) {
        _diagnosticReadiness.value = null
        val ip = GeoAssetStatus.inspect(File(d, GEOIP_FILE), GEOIP_TAGS)
        val site = GeoAssetStatus.inspect(File(d, GEOSITE_FILE), GEOSITE_TAGS)
        // Keep the existing fail-closed Xray rule generation on read errors.
        geoipTag = ip.getOrNull()
        geositeTag = site.getOrNull()
        _diagnosticReadiness.value = GeoAssetStatus.readiness(ip, site)
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
        return GeoAssetStatus.needsRefresh(
            File(d, GEOIP_FILE), File(d, GEOSITE_FILE), System.currentTimeMillis(), MAX_AGE_MS
        )
    }

    /**
     * دانلود به‌روزترین فایل‌ها در صورت نبود یا کهنه بودن.
     * خطاها بی‌صدا نادیده گرفته می‌شوند (اپ باید بدون این فایل‌ها هم کار کند).
     */
    suspend fun syncIfNeeded(context: Context, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            // Startup and an explicit refresh can overlap. Do not let two
            // downloaders write the same .part file or race the readiness state.
            syncMutex.withLock {
                init(context)
                val d = dir ?: return@withLock false
                // A fresh timestamp alone does not repair a malformed file or
                // a file whose required tag is missing.
                if (!force && !isStale(context) && _diagnosticReadiness.value == true) return@withLock isReady()

                val ipFile = File(d, GEOIP_FILE)
                val siteFile = File(d, GEOSITE_FILE)
                val ipTemp = File(d, "$GEOIP_FILE.part")
                val siteTemp = File(d, "$GEOSITE_FILE.part")
                var refreshedUnderGate = false
                _diagnosticReadiness.value = null
                try {
                    // Network I/O is outside the connection gate, so a slow
                    // download does not hold up preflight or the live tunnel.
                    val ipDownloaded = download(ipTemp, GEOIP_URL)
                    currentCoroutineContext().ensureActive()
                    val siteDownloaded = download(siteTemp, GEOSITE_URL)
                    currentCoroutineContext().ensureActive()
                    if (!ipDownloaded && !siteDownloaded) false else {
                        // A handover builds its config and starts Xray under the
                        // same gate. Install and publish matching tags together.
                        ConnectionTestBarrier.connection {
                            try {
                                val ipUpdated = ipDownloaded && GeoAssetInstaller.install(ipTemp, ipFile, GEOIP_TAGS)
                                currentCoroutineContext().ensureActive()
                                val siteUpdated = siteDownloaded && GeoAssetInstaller.install(siteTemp, siteFile, GEOSITE_TAGS)
                                currentCoroutineContext().ensureActive()
                                ipUpdated || siteUpdated
                            } finally {
                                refreshTags(d)
                                refreshedUnderGate = true
                            }
                        }
                    }
                } finally {
                    ipTemp.delete()
                    siteTemp.delete()
                    // A canceled or failed download never changed the installed
                    // files, but its temporary unknown state still needs closing.
                    if (!refreshedUnderGate) refreshTags(d)
                    Log.i(TAG, "geo assets inspected: geoip=${hasGeoip()} geosite=${hasGeosite()}")
                }
            }
        }

    /** Download only to a sibling temporary file; no installed file is touched. */
    private fun download(tmp: File, url: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "V2max")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
