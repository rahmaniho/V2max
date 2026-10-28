package ir.v2max.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.Failover
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * سرویس اصلی VPN: رابط TUN سیستم را می‌سازد، هسته‌ی Xray را اجرا می‌کند
 * و وضعیت/سرعت را برای رابط کاربری منتشر می‌کند.
 */
class V2maxVpnService : VpnService() {

    companion object {
        private const val TAG = "V2maxVpn"
        const val ACTION_START = "ir.v2max.app.action.START_TUNNEL"
        const val ACTION_STOP = "ir.v2max.app.action.STOP_TUNNEL"
        const val EXTRA_SERVER_ID = "extra_server_id"

        /** فاصله‌ی بررسی سلامت اتصال */
        private const val HEALTH_INTERVAL_MS = 15_000L

        /** حداکثر تعداد جابه‌جایی خودکار در یک نشست */
        private const val MAX_FAILOVERS = 10

        @Volatile
        var running: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var tun: ParcelFileDescriptor? = null
    private var currentServer: ServerConfig? = null
    private var monitorJob: Job? = null
    private var connectJob: Job? = null

    private var connectedAt = 0L
    private var totalUp = 0L
    private var totalDown = 0L
    private var failoverAttempts = 0
    private var connectToken = 0
    private var failoverPending = false

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }

        // اعلان فوری برای رعایت مهلت startForeground
        publish(Repo.tunnel.value.copy(status = TunnelStatus.CONNECTING, message = "در حال آماده‌سازی…"))

        val serverId = intent?.getStringExtra(EXTRA_SERVER_ID).orEmpty()
            .ifBlank { Repo.selectedId.value }
            .ifBlank { Repo.servers.value.firstOrNull()?.id.orEmpty() }

        connect(serverId)
        return START_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        monitorJob?.cancel()
        connectJob?.cancel()
        XrayCore.stop()
        closeTun()
        running = false
        val status = Repo.tunnel.value.status
        if (status != TunnelStatus.DISCONNECTED && status != TunnelStatus.ERROR) {
            Repo.tunnel.value = TunnelUiState(status = TunnelStatus.DISCONNECTED)
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- connecting

    private fun connect(serverId: String) {
        connectJob?.cancel()
        val token = ++connectToken
        val isFailover = failoverPending
        failoverPending = false
        connectJob = scope.launch { doConnect(serverId, token, isFailover) }
    }

    private suspend fun doConnect(serverId: String, token: Int, isFailover: Boolean = false) {
        try {
            val server = Repo.serverById(serverId) ?: Repo.servers.value.firstOrNull()
            ?: throw IllegalStateException("هیچ سروری انتخاب نشده است")

            currentServer = server
            Repo.setSelected(server.id)

            if (isFailover) {
                Log.i(TAG, "failover: connecting to ${server.endpoint}")
            } else {
                Failover.reset()
            }

            publish(
                TunnelUiState(
                    status = TunnelStatus.CONNECTING,
                    serverId = server.id,
                    serverName = server.displayName(),
                    serverProto = server.proto.label,
                    ping = server.bestPing(),
                    message = if (isFailover) "تغییر خودکار به سرور جایگزین…" else "در حال اتصال…"
                )
            )

            // پاک‌سازی اجرای قبلی
            monitorJob?.cancel()
            monitorJob = null
            XrayCore.stop()
            closeTun()

            val settings = Repo.settings.value
            val fd = establishTun(settings)
                ?: throw IllegalStateException("دسترسی VPN داده نشده است. دوباره تلاش کنید.")

            tun = fd

            XrayCore.init(applicationContext)

            // اگر کانفیگ با تنظیمات فعلی بالا نیامد (مثلاً فرگمنت روی این هسته پشتیبانی نشود)
            // یک‌بار بدون فرگمنت تلاش می‌کنیم تا اتصال کاربر قطع نشود.
            try {
                XrayCore.start(ConfigBuilder.vpnConfig(server, settings), fd.fd)
            } catch (first: Throwable) {
                if (settings.fragment) {
                    Log.w(TAG, "start with fragment failed, retrying without fragment", first)
                    XrayCore.stop()
                    XrayCore.start(ConfigBuilder.vpnConfig(server, settings.copy(fragment = false)), fd.fd)
                } else {
                    throw first
                }
            }

            if (!XrayCore.isRunning) {
                throw IllegalStateException("هسته نتوانست اجرا شود")
            }
            if (token != connectToken) {
                // در این فاصله درخواست جدیدی آمده است؛ این تلاش منقضی شده
                XrayCore.stop()
                closeTun()
                return
            }

            running = true
            connectedAt = System.currentTimeMillis()
            totalUp = 0
            totalDown = 0
            if (!isFailover) failoverAttempts = 0
            Failover.markWorking(server.id)

            publish(
                TunnelUiState(
                    status = TunnelStatus.CONNECTED,
                    serverId = server.id,
                    serverName = server.displayName(),
                    serverProto = server.proto.label,
                    ping = server.bestPing(),
                    connectedAt = connectedAt,
                    message = "متصل"
                )
            )

            // اندازه‌گیری تأخیر واقعی (بدون بلاک کردن)
            scope.launch {
                val delayMs = withContext(Dispatchers.IO) {
                    XrayCore.measureDelay(settings.delayTestUrl)
                }
                if (delayMs > 0 && Repo.tunnel.value.status == TunnelStatus.CONNECTED) {
                    Repo.setTestResult(server.id, server.tcpPing, delayMs.toInt())
                    publish(Repo.tunnel.value.copy(ping = delayMs.toInt()))
                }
            }

            startMonitor()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) return
            if (token != connectToken) return
            Log.e(TAG, "connect failed", t)

            // اگر همین تلاشِ جایگزینی شکست خورد، سرور بعدی را امتحان می‌کنیم
            if (isFailover && failoverAttempts < MAX_FAILOVERS) {
                val failedId = currentServer?.id.orEmpty()
                Failover.markFailed(failedId)
                val next = Failover.findReplacement(failedId)
                if (next != null) {
                    failoverAttempts++
                    failoverPending = true
                    Log.i(TAG, "failover retry #$failoverAttempts → ${next.endpoint}")
                    connect(next.id)
                    return
                }
            }

            val raw = (t.message ?: t.javaClass.simpleName).take(180)
            fail("اتصال برقرار نشد: $raw")
        }
    }

    private fun establishTun(settings: AppSettings): ParcelFileDescriptor? {
        val builder = Builder()
        builder.setSession("V2max")
        builder.setMtu(settings.mtu.coerceIn(1280, 9000))

        builder.addAddress("172.19.0.1", 30)
        builder.addRoute("0.0.0.0", 0)

        if (settings.ipv6) {
            runCatching {
                builder.addAddress("fdfe:dcba:9876::1", 126)
                builder.addRoute("::", 0)
            }
        }

        val dnsServers = settings.vpnDns.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains(":") }
        if (dnsServers.isEmpty()) {
            builder.addDnsServer("1.1.1.1")
        } else {
            dnsServers.forEach { builder.addDnsServer(it) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.setMetered(false) }
        }

        // خود برنامه از تانل خارج می‌شود تا سوکت‌های هسته در حلقه نیفتند
        applyPerAppRules(builder, settings)

        return try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish failed", t)
            null
        }
    }

    /** اعمال قوانین پروکسی برنامه‌به‌برنامه */
    private fun applyPerAppRules(builder: Builder, settings: AppSettings) {
        val selected = settings.perAppPackages.filter { it.isNotBlank() }
        when (settings.perAppMode) {
            ir.v2max.app.data.PerAppMode.OFF -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }

            ir.v2max.app.data.PerAppMode.ONLY_SELECTED -> {
                if (selected.isEmpty()) {
                    runCatching { builder.addDisallowedApplication(packageName) }
                } else {
                    var added = 0
                    selected.forEach { pkg ->
                        if (pkg == packageName) return@forEach
                        runCatching {
                            builder.addAllowedApplication(pkg)
                            added++
                        }
                    }
                    if (added == 0) {
                        runCatching { builder.addDisallowedApplication(packageName) }
                    }
                }
            }

            ir.v2max.app.data.PerAppMode.EXCEPT_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                selected.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
        }
    }

    // --------------------------------------------------------------- monitor

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val settings = Repo.settings.value
            var lastTick = System.currentTimeMillis()
            var lastHealth = System.currentTimeMillis()

            while (isActive) {
                delay(1000)
                val state = Repo.tunnel.value
                if (state.status != TunnelStatus.CONNECTED) break

                val traffic = withContext(Dispatchers.IO) { XrayCore.trafficSinceLastQuery() }
                totalUp += traffic[0]
                totalDown += traffic[1]

                val now = System.currentTimeMillis()
                val elapsed = (now - lastTick).coerceAtLeast(1L)
                lastTick = now

                publish(
                    state.copy(
                        downSpeed = traffic[1] * 1000L / elapsed,
                        upSpeed = traffic[0] * 1000L / elapsed,
                        totalDown = totalDown,
                        totalUp = totalUp,
                        connectedAt = connectedAt
                    )
                )

                if (now - lastHealth < HEALTH_INTERVAL_MS) continue
                lastHealth = now

                if (withContext(Dispatchers.IO) { !XrayCore.isRunning }) {
                    Log.w(TAG, "core stopped unexpectedly")
                    if (settings.smartFailover) {
                        if (rotate("هسته متوقف شد")) return@launch
                    }
                    fail("اتصال قطع شد")
                    return@launch
                }

                var ping = withContext(Dispatchers.IO) { XrayCore.measureDelay(settings.delayTestUrl) }
                if (ping <= 0) {
                    // یک تلاش دوباره‌ی کوتاه تا لرزش‌های شبکه باعث سوییچ بی‌مورد نشوند
                    delay(1500)
                    if (!isActive) break
                    ping = withContext(Dispatchers.IO) { XrayCore.measureDelay(settings.delayTestUrl) }
                }
                if (ping > 0) {
                    publish(Repo.tunnel.value.copy(ping = ping.toInt()))
                    Repo.setTestResult(
                        Repo.tunnel.value.serverId,
                        Repo.serverById(Repo.tunnel.value.serverId)?.tcpPing ?: -1,
                        ping.toInt()
                    )
                } else {
                    Log.w(TAG, "health check failed → failover")
                    // یک شکست کافی است تا کاربر بدون معطلی به سرور سالم منتقل شود
                    if (settings.smartFailover) {
                        if (rotate("سرور فعلی پاسخ نمی‌دهد")) return@launch
                        fail("سرور فعلی از دسترس خارج شد و جایگزینی پیدا نشد")
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * جابه‌جایی به سرور جایگزین.
     * @return true اگر فرمان اتصال به سرور جدید صادر شده باشد
     */
    private suspend fun rotate(reason: String): Boolean {
        val currentId = currentServer?.id ?: Repo.tunnel.value.serverId
        Failover.markFailed(currentId)

        if (failoverAttempts >= MAX_FAILOVERS) {
            Log.w(TAG, "failover limit reached")
            return false
        }

        publish(
            Repo.tunnel.value.copy(
                message = "$reason؛ در حال یافتن سرور جایگزین…",
                downSpeed = 0,
                upSpeed = 0
            )
        )

        val next = Failover.findReplacement(currentId) ?: return false

        failoverAttempts++
        Log.i(TAG, "failover #$failoverAttempts → ${next.endpoint} ($reason)")
        failoverPending = true
        connect(next.id)
        return true
    }

    // -------------------------------------------------------------- teardown

    private fun stopTunnel() {
        monitorJob?.cancel()
        monitorJob = null
        connectJob?.cancel()
        connectJob = null

        if (Repo.tunnel.value.status != TunnelStatus.DISCONNECTED) {
            Repo.tunnel.value = Repo.tunnel.value.copy(status = TunnelStatus.DISCONNECTING)
            Notifier.update(this, Repo.tunnel.value, false)
        }

        // توقف همگام تا مطمئن شویم هسته و رابط TUN واقعاً آزاد شده‌اند
        XrayCore.stop()
        closeTun()
        running = false
        Repo.tunnel.value = TunnelUiState(status = TunnelStatus.DISCONNECTED)
        Notifier.cancel(this)
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail(message: String) {
        running = false
        XrayCore.stop()
        closeTun()
        Repo.tunnel.value = Repo.tunnel.value.copy(
            status = TunnelStatus.ERROR,
            message = message,
            downSpeed = 0,
            upSpeed = 0
        )
        Notifier.update(this, Repo.tunnel.value, false)
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    // ------------------------------------------------------------ publishing

    private fun publish(state: TunnelUiState) {
        Repo.tunnel.value = state
        if (!isForeground) {
            startForegroundCompat(state)
            isForeground = true
        } else {
            Notifier.update(this, state, Repo.settings.value.showSpeedInNotification)
        }
    }

    private var isForeground = false

    private fun startForegroundCompat(state: TunnelUiState = Repo.tunnel.value) {
        val notification = Notifier.build(this, state, Repo.settings.value.showSpeedInNotification)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifier.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notifier.NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground failed: ${t.message}")
        }
        isForeground = false
    }
}
