package ir.v2max.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import ir.v2max.app.core.ConfigBuilder
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
        connectJob = scope.launch { doConnect(serverId) }
    }

    private suspend fun doConnect(serverId: String) {
        try {
            val server = Repo.serverById(serverId) ?: Repo.servers.value.firstOrNull()
            ?: throw IllegalStateException("هیچ سروری انتخاب نشده است")

            currentServer = server
            Repo.setSelected(server.id)

            publish(
                TunnelUiState(
                    status = TunnelStatus.CONNECTING,
                    serverId = server.id,
                    serverName = server.displayName(),
                    serverProto = server.proto.label,
                    ping = server.bestPing(),
                    message = "در حال اتصال…"
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
            val config = ConfigBuilder.vpnConfig(server, settings)
            XrayCore.start(config, fd.fd)

            if (!XrayCore.isRunning) {
                throw IllegalStateException("هسته نتوانست اجرا شود")
            }

            running = true
            connectedAt = System.currentTimeMillis()
            totalUp = 0
            totalDown = 0
            failoverAttempts = 0

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
            Log.e(TAG, "connect failed", t)
            fail(t.message ?: t.javaClass.simpleName)
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
        runCatching { builder.addDisallowedApplication(packageName) }

        return try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "establish failed", t)
            null
        }
    }

    // --------------------------------------------------------------- monitor

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val settings = Repo.settings.value
            var lastTick = System.currentTimeMillis()
            var lastHealth = System.currentTimeMillis()
            var healthFailures = 0

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

                val updated = state.copy(
                    downSpeed = traffic[1] * 1000L / elapsed,
                    upSpeed = traffic[0] * 1000L / elapsed,
                    totalDown = totalDown,
                    totalUp = totalUp,
                    connectedAt = connectedAt
                )
                publish(updated)

                if (now - lastHealth > 45_000L) {
                    lastHealth = now
                    val ping = withContext(Dispatchers.IO) { XrayCore.measureDelay(settings.delayTestUrl) }
                    if (ping > 0) {
                        healthFailures = 0
                        publish(Repo.tunnel.value.copy(ping = ping.toInt()))
                        Repo.setTestResult(
                            Repo.tunnel.value.serverId,
                            Repo.serverById(Repo.tunnel.value.serverId)?.tcpPing ?: -1,
                            ping.toInt()
                        )
                    } else {
                        healthFailures++
                        if (healthFailures >= 2 && settings.smartFailover) {
                            val next = pickFailoverServer()
                            if (next != null && failoverAttempts < 3) {
                                failoverAttempts++
                                Log.i(TAG, "failover to ${next.name}")
                                connect(next.id)
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pickFailoverServer(): ServerConfig? {
        val currentId = currentServer?.id
        return Repo.servers.value
            .filter { it.id != currentId && it.bestPing() > 0 }
            .minByOrNull { it.bestPing() }
            ?: Repo.servers.value.firstOrNull { it.id != currentId }
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
