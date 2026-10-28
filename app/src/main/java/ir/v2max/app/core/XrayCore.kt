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

    @Volatile
    private var initialized = false

    private var controller: CoreController? = null

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "core started")
            return 0
        }

        override fun shutdown(): Long {
            Log.i(TAG, "core stopped")
            return 0
        }

        override fun onEmitStatus(code: Long, message: String?): Long {
            if (!message.isNullOrBlank()) Log.i(TAG, "core: $message")
            return 0
        }
    }

    /** آماده‌سازی محیط نیتیو؛ فقط یک‌بار در هر پروسه */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        try {
            Seq.setContext(context.applicationContext)
            val assetPath = context.getDir("assets", Context.MODE_PRIVATE).absolutePath
            Libv2ray.initCoreEnv(assetPath, deviceKey(context))
            initialized = true
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
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

    @Synchronized
    fun stop() {
        try {
            val c = controller ?: return
            if (c.isRunning) c.stopLoop()
        } catch (t: Throwable) {
            Log.e(TAG, "stop failed", t)
        }
    }

    /** تأخیر واقعی از طریق هسته‌ی در حال اجرا (میلی‌ثانیه) */
    fun measureDelay(url: String): Long {
        return try {
            val c = controller ?: return -1L
            if (!c.isRunning) return -1L
            c.measureDelay(url)
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
        } catch (t: Throwable) {
            Log.w(TAG, "stats failed: ${t.message}")
        }
        return result
    }

    /** تأخیر یک کانفیگ مستقل (بدون اجرای تانل) */
    fun measureOutboundDelay(config: String, url: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config, url)
        } catch (t: Throwable) {
            -1L
        }
    }

    fun version(): String = try {
        Libv2ray.checkVersionX()
    } catch (t: Throwable) {
        "نامشخص"
    }
}
