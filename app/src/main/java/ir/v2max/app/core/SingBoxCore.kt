package ir.v2max.app.core

import android.content.Context
import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import ir.v2max.app.data.AppSettings

/**
 * لایه‌ی امن روی هسته‌ی sing-box (libbox) — برای پروتکل‌های Hysteria2 / TUIC / AnyTLS / WireGuard.
 *
 * چرخه‌ی عمر libbox 1.14:
 *   setup → newCommandServer(handler, platform) → startOrReloadService(config) → closeService/close
 *
 * نکته‌ی مهم: TUN را خودِ VpnService می‌سازد و از طریق [SingBoxPlatform.openTun] به هسته می‌دهد؛
 * به همین دلیل تنظیمات مسیرها، DNS و split-tunneling دقیقاً مثل مسیر Xray اعمال می‌شود.
 */
object SingBoxCore {

    private const val TAG = "V2maxSingBox"

    @Volatile
    private var commandServer: CommandServer? = null

    @Volatile
    private var setupDone = false

    @Volatile
    private var lastError: String = ""

    val isRunning: Boolean
        get() = commandServer != null

    fun lastError(): String = lastError

    /** آماده‌سازی محیط نیتیو (یک‌بار در هر پروسه) */
    @Synchronized
    fun setup(context: Context) {
        if (setupDone) return
        val options = SetupOptions().apply {
            basePath = context.filesDir.absolutePath
            workingPath = context.cacheDir.absolutePath
            tempPath = context.cacheDir.absolutePath
            fixAndroidStack = true
            debug = false
            logMaxLines = 300
            commandServerListenPort = 0
        }
        Libbox.setup(options)
        setupDone = true
        log("sing-box ${Libbox.version()} آماده شد")
    }

    /** اعتبارسنجی کانفیگ پیش از اجرا؛ null یعنی معتبر */
    fun validate(config: String): String? = try {
        Libbox.checkConfig(config)
        null
    } catch (t: Throwable) {
        t.message ?: "کانفیگ نامعتبر است"
    }

    /**
     * اجرای هسته با کانفیگ داده‌شده.
     * @param vpnService سرویس VPN فعلی؛ برای ساخت TUN و protect کردن سوکت‌ها
     */
    @Synchronized
    fun start(
        context: Context,
        config: String,
        vpnService: VpnService?,
        settings: AppSettings,
        buildTun: () -> Int = { -1 }
    ): Boolean {
        setup(context)
        stop()

        validate(config)?.let { error ->
            lastError = error
            log("کانفیگ رد شد: $error")
            return false
        }

        val platform = SingBoxPlatform(
            vpnService = vpnService,
            settings = settings,
            buildTun = buildTun,
            onLog = { message -> log(message) }
        )
        val handler = Handler()

        return try {
            val server = Libbox.newCommandServer(handler, platform)
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            commandServer = server
            lastError = ""
            log("هسته اجرا شد")
            true
        } catch (t: Throwable) {
            lastError = t.message ?: "اجرای هسته ناموفق بود"
            log("خطا در اجرا: ${t.message}")
            runCatching { commandServer?.close() }
            commandServer = null
            false
        }
    }

    @Synchronized
    fun stop() {
        val server = commandServer ?: return
        commandServer = null
        runCatching { server.closeService() }
        runCatching { server.close() }
        log("هسته متوقف شد")
    }

    fun version(): String = runCatching { Libbox.version() }.getOrDefault("نامشخص")

    // ---- آمار ترافیک تقریبی (برای پروتکل‌های sing-box)
    private var lastTx = 0L
    private var lastRx = 0L

    /**
     * ترافیک مصرف‌شده از آخرین فراخوانی: [uplink, downlink] بر حسب بایت.
     * از آمار سوکت‌های همین پروسه استفاده می‌شود؛ برای نمایش سرعت در رابط کاربری کافی است.
     */
    fun trafficSinceLastQuery(): LongArray {
        val uid = android.os.Process.myUid()
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        if (tx < 0 || rx < 0) return longArrayOf(0L, 0L)
        if (lastTx == 0L && lastRx == 0L) {
            lastTx = tx
            lastRx = rx
            return longArrayOf(0L, 0L)
        }
        val dTx = (tx - lastTx).coerceAtLeast(0L)
        val dRx = (rx - lastRx).coerceAtLeast(0L)
        lastTx = tx
        lastRx = rx
        return longArrayOf(dTx, dRx)
    }

    fun resetTrafficCounters() {
        lastTx = 0L
        lastRx = 0L
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XrayCore.log("[sing-box] $message")
    }

    /** پاسخ‌های CommandServerHandler؛ برای اجرای ساده لازم نیست کاری انجام دهند */
    private class Handler : CommandServerHandler {
        override fun connectSSHAgent(): Int = -1

        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus().apply {
                available = false
                enabled = false
            }

        override fun serviceReload() = Unit

        override fun serviceStop() = Unit

        override fun setSystemProxyEnabled(enabled: Boolean) = Unit

        override fun triggerNativeCrash() = Unit

        override fun writeDebugMessage(message: String) {
            Log.d(TAG, message)
        }
    }

    /** برای تست‌ها و نمایش: پورت‌های محلی که هسته استفاده می‌کند */
    fun platformSupports(proto: ir.v2max.app.data.Proto): Boolean = SingBoxConfig.supports(proto)
}
