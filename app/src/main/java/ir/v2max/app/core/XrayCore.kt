package ir.v2max.app.core

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * پوشش امن و thread-safe روی کتابخانه‌ی نیتیو Xray (libv2ray).
 */
object XrayCore {

    private const val TAG = "V2maxCore"

    /** Fixed local events only. Native log messages can contain credentials and addresses. */
    enum class Event(val text: String) {
        STARTED("هسته آغاز شد"),
        STOPPED("هسته متوقف شد"),
        INIT_FAILED("آماده‌سازی هسته ناموفق بود"),
        STOP_FAILED("توقف هسته ناموفق بود"),
        GEO_FALLBACK("فایل مسیریابی جغرافیایی نامعتبر بود؛ اتصال بدون آن امتحان می‌شود"),
        POOL_FALLBACK("استخر پاسخ نداد؛ بازگشت به سرور انتخابی"),
    }

    private val logLines = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val logListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun logs(): List<String> = logLines.toList()

    fun clearLogs() = logLines.clear()

    fun addLogListener(listener: (String) -> Unit) {
        logListeners.addIfAbsent(listener)
    }

    fun removeLogListener(listener: (String) -> Unit) {
        logListeners.remove(listener)
    }

    /** Keep only app-owned categorical events in the in-memory view. */
    fun log(event: Event) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logLines.addLast("[$stamp] ${event.text}")
        while (logLines.size > 400) logLines.pollFirst()
        logListeners.forEach { runCatching { it(event.text) } }
    }

    @Volatile
    private var initialized = false

    @Volatile private var controller: CoreController? = null
    // The temporary SOCKS instance is separate from the live VPN controller.
    // Both startLoop calls are synchronized here because libv2ray sets a
    // process-global TUN fd before constructing an instance.
    private var speedController: CoreController? = null
    private var speedLease = 0L
    private var nextLease = 0L

    private val speedCallback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long = 0
    }

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "core started")
            log(Event.STARTED)
            return 0
        }

        override fun shutdown(): Long {
            Log.i(TAG, "core stopped")
            log(Event.STOPPED)
            return 0
        }

        // Xray messages may contain server addresses, URL paths and credentials.
        override fun onEmitStatus(code: Long, message: String?): Long = 0
    }

    /** آماده‌سازی محیط نیتیو؛ فقط یک‌بار در هر پروسه */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        try {
            Seq.setContext(context.applicationContext)
            val assetPath = context.getDir("assets", Context.MODE_PRIVATE).absolutePath
            Libv2ray.initCoreEnv(assetPath, deviceKey(context))
            GeoAssets.init(context)
            initialized = true
        } catch (t: Throwable) {
            Log.e(TAG, "init failed")
            log(Event.INIT_FAILED)
            throw t
        }
    }

    private fun deviceKey(context: Context): String = try {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
        val raw = androidId.toByteArray(Charsets.UTF_8).copyOf(32)
        Base64.encodeToString(raw, Base64.NO_PADDING or Base64.URL_SAFE)
    } catch (t: Throwable) {
        ""
    }

    val isRunning: Boolean
        get() = try {
            controller?.isRunning == true
        } catch (t: Throwable) {
            false
        }

    /** اجرای هسته با کانفیگ و fd تانل (۰ = بدون تانل) */
    @Synchronized
    @Throws(Exception::class)
    fun start(config: String, tunFd: Int) {
        val c = controller ?: Libv2ray.newCoreController(callback).also { controller = it }
        c.startLoop(config, tunFd)
    }

    /** Start a separate no-TUN core; never reuse or stop the live VPN instance. */
    @Synchronized
    fun startSpeedTest(config: String): Long? {
        if (speedLease != 0L) return null
        val c = speedController ?: Libv2ray.newCoreController(speedCallback).also { speedController = it }
        val lease = ++nextLease
        speedLease = lease
        try {
            c.startLoop(config, 0)
            if (!c.isRunning) {
                speedLease = 0L
                return null
            }
            return lease
        } catch (t: Throwable) {
            speedLease = 0L
            // A partially started core must not hold the local SOCKS port open.
            runCatching { if (c.isRunning) c.stopLoop() }
            throw t
        }
    }

    /** Visible to instrumentation: a test lease is not the VPN's running state. */
    @Synchronized
    fun isSpeedTestRunning(): Boolean = speedLease != 0L && speedController?.isRunning == true

    @Synchronized
    fun stopSpeedTest(lease: Long) {
        if (speedLease != lease) return
        speedLease = 0L
        try {
            speedController?.let { if (it.isRunning) it.stopLoop() }
        } catch (_: Throwable) {
            Log.e(TAG, "speed test core stop failed")
        }
    }

    @Synchronized
    fun stop() {
        try {
            val c = controller ?: return
            if (c.isRunning) c.stopLoop()
        } catch (_: Throwable) {
            Log.e(TAG, "stop failed")
            log(Event.STOP_FAILED)
        }
    }

    /** تأخیر واقعی از طریق هسته‌ی در حال اجرا (میلی‌ثانیه) */
    fun measureDelay(url: String): Long {
        return try {
            val c = controller ?: return -1L
            if (!c.isRunning) return -1L
            NativeDelay.normalize(c.measureDelay(url))
        } catch (t: Throwable) {
            -1L
        }
    }

    /** ترافیک مصرفی: آرایه‌ی [uplink, downlink] بر حسب بایت (از آخرین فراخوانی) */
    fun trafficSinceLastQuery(): LongArray {
        val result = longArrayOf(0L, 0L)
        try {
            val c = controller ?: return result
            if (!c.isRunning) return result
            val payload = c.queryAllOutboundTrafficStats() ?: return result
            payload.split(';').forEach { entry ->
                if (entry.isBlank()) return@forEach
                val parts = entry.split(',')
                if (parts.size != 3) return@forEach
                val value = parts[2].toLongOrNull() ?: return@forEach
                when (parts[1]) {
                    "uplink" -> result[0] += value
                    "downlink" -> result[1] += value
                }
            }
        } catch (_: Throwable) {
            Log.w(TAG, "stats failed")
        }
        return result
    }

    @Volatile private var lastProbeProblem: String = ""

    fun clearProbeProblem() { lastProbeProblem = "" }
    fun probeProblem(): String = lastProbeProblem

    /** تأخیر مستقل؛ خطای نامشخص، مدرک کافی برای حذف کانفیگ نیست. */
    fun measureOutboundDelay(config: String, url: String): Long {
        return try {
            val delay = Libv2ray.measureOutboundDelay(config, url)
            if (delay < 0) NativeDelay.INDETERMINATE else NativeDelay.normalize(delay)
        } catch (t: Throwable) {
            val reason = t.message.orEmpty()
            val safe = when {
                reason.contains("allowInsecure", ignoreCase = true) ->
                    "هسته‌ی Xray گزینه‌ی allowInsecure را حذف کرده است؛ گواهی معتبر یا اثرانگشت گواهی لازم است"
                reason.contains("without TLS", ignoreCase = true) ->
                    "این نسخه‌ی Xray کانفیگ VLESS یا Trojan بدون TLS روی سرور عمومی را نمی‌پذیرد"
                reason.contains("config load error", ignoreCase = true) ||
                    reason.contains("instance creation failed", ignoreCase = true) ->
                    "فرمت یا تنظیمات کانفیگ با هسته‌ی Xray سازگار نیست"
                else -> ""
            }
            if (safe.isNotBlank()) lastProbeProblem = safe
            NativeDelay.outboundFailure(reason)
        }
    }

    fun version(): String = try {
        Libv2ray.checkVersionX()
    } catch (t: Throwable) {
        "نامشخص"
    }
}
