package ir.v2max.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.Failover
import ir.v2max.app.core.SingBoxCore
import ir.v2max.app.core.XrayCore
import ir.v2max.app.net.SocksProbe
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
        const val ACTION_NEXT = "ir.v2max.app.action.NEXT_SERVER"
        const val ACTION_RETEST = "ir.v2max.app.action.RETEST"

        /** رفع مسدودی Kill Switch و بازگرداندن اینترنت عادی */
        const val ACTION_UNBLOCK = "ir.v2max.app.action.UNBLOCK"
        const val ACTION_STOP = "ir.v2max.app.action.STOP_TUNNEL"
        const val EXTRA_SERVER_ID = "extra_server_id"

        /** هر چند ثانیه یک‌بار کیفیت اتصال سنجیده می‌شود (پایش هر ثانیه‌ای) */
        private const val PROBE_INTERVAL_MS = 2_000L

        /** فاصله‌ی پروب وقتی صفحه خاموش است (صرفه‌جویی باتری) */
        private const val PROBE_INTERVAL_IDLE_MS = 15_000L

        /** فاصله‌ی پروب در حالت ذخیره‌ی انرژی یا باتری کم */
        private const val PROBE_INTERVAL_SAVER_MS = 30_000L

        /** چند شکست پیاپی یعنی «اتصال عملاً مرده است» */
        private const val FAIL_THRESHOLD = 2

        /** سقف زمان انتظار پیش از جایگزینی اجباری */
        private const val MAX_FAIL_RESILIENCE = 15

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
    /** آیا اتصال جاری با هسته‌ی sing-box اجرا می‌شود */
    @Volatile
    private var usingSingBox = false

    private fun coreRunning(): Boolean = if (usingSingBox) SingBoxCore.isRunning else XrayCore.isRunning

    private fun coreTraffic(): LongArray =
        if (usingSingBox) SingBoxCore.trafficSinceLastQuery() else XrayCore.trafficSinceLastQuery()

    private var totalDown = 0L
    private var failoverAttempts = 0
    private var connectToken = 0
    private var failoverPending = false

    /** شبکه عوض شده و باید سریع دوباره سنجیده شود */
    @Volatile
    private var networkChanged = false

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifier.ensureChannel(this)
        registerNetworkWatcher()
    }

    /** با تغییر شبکه (وای‌فای ↔ داده) بلافاصله کیفیت دوباره سنجیده می‌شود */
    private fun registerNetworkWatcher() {
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    networkChanged = true
                }

                override fun onLost(network: android.net.Network) {
                    networkChanged = true
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    networkChanged = true
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkWatcher() {
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP || action == ACTION_UNBLOCK) {
            stopTunnel()
            return START_NOT_STICKY
        }

        if (action == ACTION_NEXT) {
            // تغییر فوری به سرور بعدی از فهرست سالم‌ها
            val current = currentServer?.id ?: Repo.tunnel.value.serverId
            scope.launch {
                val next = Failover.findReplacement(current)
                if (next != null) {
                    Failover.markFailed(current)
                    failoverPending = true
                    connect(next.id)
                }
            }
            return START_STICKY
        }

        if (action == ACTION_RETEST) {
            // تست دوباره‌ی سرور فعلی و جابه‌جایی در صورت خرابی
            scope.launch {
                val id = currentServer?.id ?: Repo.tunnel.value.serverId
                val server = Repo.serverById(id)
                if (server != null) {
                    val ping = withContext(Dispatchers.IO) {
                        ir.v2max.app.net.HealthScanner.realDelay(server, Repo.settings.value)
                    }
                    if (ping <= 0) {
                        rotate("تست مجدد ناموفق بود")
                    } else {
                        Repo.setTestResult(server.id, server.tcpPing, ping)
                        publish(Repo.tunnel.value.copy(ping = ping, message = "تست مجدد: ${ping}ms"))
                    }
                }
            }
            return START_STICKY
        }

        // اعلان فوری برای رعایت مهلت startForeground
        publish(Repo.tunnel.value.copy(status = TunnelStatus.CONNECTING, message = "در حال آماده‌سازی…"))

        val serverId = intent?.getStringExtra(EXTRA_SERVER_ID).orEmpty()
            .ifBlank { Repo.selectedId.value }
            .ifBlank { Repo.servers.value.firstOrNull()?.id.orEmpty() }

        // اگر برنامه از تایل/بوت/همیشه‌روشن اجرا شده و هیچ نتیجه‌ی تازه‌ای نداریم،
        // ابتدا چرخه‌ی کامل تست اجرا می‌شود و بعد اتصال برقرار می‌گردد.
        val settings = Repo.settings.value
        val hasFreshResults = Repo.servers.value.any {
            it.bestPing() > 0 && System.currentTimeMillis() - it.testedAt < 10 * 60 * 1000L
        }
        if (settings.requireFullTest && !hasFreshResults && Repo.servers.value.isNotEmpty()) {
            publish(
                Repo.tunnel.value.copy(
                    status = TunnelStatus.CONNECTING,
                    message = "اجرای تست کامل پیش از اتصال…"
                )
            )
            scope.launch {
                runCatching {
                    ir.v2max.app.core.SmartConnect.scanAndConnect(
                        applicationContext,
                        autoConnect = false
                    )
                }
                val best = Repo.healthyServers(15 * 60 * 1000L).firstOrNull()
                    ?: Repo.serverById(serverId)
                if (best != null) connect(best.id)
                else fail("هیچ سرور سالمی پیدا نشد. اتصال اینترنت را بررسی کنید.")
            }
            return START_STICKY
        }

        connect(serverId)
        return START_STICKY
    }

    /** اگر کاربر برنامه را از فهرست اخیر پاک کرد، تانل نباید قطع شود */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, keeping tunnel")
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        unregisterNetworkWatcher()
        monitorJob?.cancel()
        connectJob?.cancel()
        if (usingSingBox) SingBoxCore.stop() else XrayCore.stop()
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

            val baseSettings = Repo.settings.value

            // پروتکل‌های Hysteria2 / TUIC / AnyTLS / WireGuard با هسته‌ی sing-box اجرا می‌شوند
            if (server.needsSingBox()) {
                doConnectSingBox(server, baseSettings, token)
                return
            }

            usingSingBox = false
            val ladder = buildPenetrationLadder(baseSettings)

            // MTU رابط TUN با MTU پیشنهادی همان شبکه تنظیم می‌شود (جلوگیری از تکه‌شدن بی‌مورد)
            val tunSettings = baseSettings.copy(mtu = ladder.firstOrNull()?.mtu ?: baseSettings.mtu)
            val fd = establishTun(tunSettings)
                ?: throw IllegalStateException("دسترسی VPN داده نشده است. دوباره تلاش کنید.")

            tun = fd

            XrayCore.init(applicationContext)

            Repo.clearLatencyHistory()

            // استخر سرورها: چند سرور سالم برتر برای انتخاب خودکار توسط هسته (balancer)
            val pool = if (baseSettings.balancer) {
                val healthy = Repo.servers.value
                    .filter { it.id != server.id && it.hasUsableEndpoint() }
                    .filter { it.bestPing() > 0 || it.tcpPing > 0 }
                    .sortedBy { it.score() }
                    .take((baseSettings.balancerSize - 1).coerceIn(0, 5))
                listOf(server) + healthy
            } else {
                listOf(server)
            }
            Repo.poolSize.value = pool.size

            // ---- نردبان نفوذ: پروفایل‌های فرگمنت به‌ترتیب امتحان می‌شوند تا تانل واقعاً بالا بیاید
            val attempts = ladder
            var working: AppSettings? = null
            var lastError: Throwable? = null

            for ((index, attempt) in attempts.withIndex()) {
                if (token != connectToken) {
                    XrayCore.stop()
                    closeTun()
                    return
                }
                val label = when {
                    !attempt.fragment -> "بدون فرگمنت"
                    else -> attempt.profile.label
                }
                publish(
                    Repo.tunnel.value.copy(
                        status = TunnelStatus.CONNECTING,
                        message = "تلاش ${index + 1} از ${attempts.size}: $label"
                    )
                )
                val settings = baseSettings.copy(
                    fragment = attempt.fragment,
                    fragmentProfile = attempt.profile,
                    mtu = attempt.mtu
                )
                try {
                    if (XrayCore.isRunning) XrayCore.stop()
                    XrayCore.start(ConfigBuilder.vpnConfig(server, settings, pool), fd.fd)
                    if (!XrayCore.isRunning) throw IllegalStateException("هسته اجرا نشد")

                    // اعتبارسنجی واقعی: تا وقتی ترافیک از تانل عبور نکند، «وصل» حساب نمی‌شود
                    val verify = withContext(Dispatchers.IO) {
                        XrayCore.measureDelay(settings.delayTestUrl)
                    }
                    if (verify <= 0) {
                        throw IllegalStateException("تانل بالا آمد ولی ترافیک عبور نکرد")
                    }
                    working = settings
                    Log.i(TAG, "tunnel established with $label (${verify}ms)")
                    break
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "penetration attempt '$label' failed: ${t.message}")
                    runCatching { XrayCore.stop() }
                }
            }

            val settings = working ?: run {
                throw IllegalStateException(
                    "هیچ ترکیبی از تنظیمات از شبکه عبور نکرد: " +
                        (lastError?.message ?: "خطای ناشناخته")
                )
            }

            // اگر پروفایل کاری با تنظیمات قبلی تفاوت داشت، برای بارهای بعد ذخیره می‌شود
            if (settings.fragmentProfile != baseSettings.fragmentProfile || settings.mtu != baseSettings.mtu) {
                Repo.updateSettings {
                    it.copy(fragmentProfile = settings.fragmentProfile, mtu = settings.mtu)
                }
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
            Repo.recordOutcome(server.id, true)
            ir.v2max.app.core.SmartConnect.rememberWorkingNetwork(Repo.serverById(server.id) ?: server)

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

            // اگر اتصال اولیه هم شکست خورد، به‌جای تسلیم شدن، سرور بعدی امتحان می‌شود
            if (!isFailover && Repo.settings.value.smartFailover && failoverAttempts < MAX_FAILOVERS) {
                if (rotate("اتصال با این سرور برقرار نشد")) return
            }

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

            currentServer?.let { Repo.recordOutcome(it.id, false) }
            val raw = (t.message ?: t.javaClass.simpleName).take(180)
            fail("اتصال برقرار نشد: $raw")
        }
    }

    /** یک پله از نردبان تلاش برای عبور از شبکه */
    private data class Attempt(
        val fragment: Boolean,
        val profile: ir.v2max.app.data.FragmentProfile,
        val mtu: Int
    )

    /**
     * ساخت نردبان نفوذ بر اساس نتیجه‌ی بررسی عمیق شبکه:
     * ابتدا پروفایل پیشنهادی همان اپراتور، بعد گزینه‌های جایگزین و در آخر بدون فرگمنت.
     */
    private fun buildPenetrationLadder(settings: AppSettings): List<Attempt> {
        val assessment = Repo.network.value
        val mtu = if (settings.mtu in 1280..1500) settings.mtu
        else assessment.recommendedMtu.coerceIn(1280, 1500)

        if (!settings.fragment) {
            return listOf(Attempt(false, settings.fragmentProfile, mtu))
        }

        val preferred = when {
            settings.autoTuneFragment && assessment.fresh -> assessment.recommendedFragment
            else -> settings.fragmentProfile
        }
        val order = LinkedHashSet<ir.v2max.app.data.FragmentProfile>()
        order.add(preferred)
        if (settings.autoTuneFragment && assessment.fresh) order.add(settings.fragmentProfile)
        ir.v2max.app.data.FragmentProfile.entries.forEach { order.add(it) }

        val attempts = ArrayList<Attempt>()
        order.take(3).forEach { attempts.add(Attempt(true, it, mtu)) }
        // آخرین راه: بدون فرگمنت (بعضی شبکه‌ها/سرورها با فرگمنت سازگار نیستند)
        attempts.add(Attempt(false, settings.fragmentProfile, mtu))
        return attempts
    }

    /**
     * اتصال با هسته‌ی sing-box برای پروتکل‌هایی که Xray پشتیبانی نمی‌کند.
     * TUN توسط همین سرویس ساخته و به هسته تحویل داده می‌شود؛ اعتبارسنجی اتصال با
     * یک درخواست واقعی از طریق inbound محلی SOCKS انجام می‌شود.
     */
    private suspend fun doConnectSingBox(server: ServerConfig, baseSettings: AppSettings, token: Int) {
        usingSingBox = true
        SingBoxCore.resetTrafficCounters()

        publish(
            Repo.tunnel.value.copy(
                status = TunnelStatus.CONNECTING,
                message = "آماده‌سازی هسته‌ی ${server.proto.label}…"
            )
        )

        val tunSettings = baseSettings.copy(mtu = baseSettings.mtu.coerceIn(1280, 9000))

        val started = try {
            SingBoxCore.start(
                context = applicationContext,
                config = ir.v2max.app.core.SingBoxConfig.vpnConfig(server, tunSettings),
                vpnService = this,
                settings = tunSettings,
                buildTun = {
                    // این بلوک از داخل خود هسته فراخوانی می‌شود؛ TUN باید همان‌جا ساخته شود
                    val pfd = establishTun(tunSettings)
                    if (pfd == null) {
                        -1
                    } else {
                        tun = pfd
                        pfd.fd
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sing-box start failed", t)
            false
        }

        if (token != connectToken) return

        if (!started) {
            closeTun()
            usingSingBox = false
            fail("اجرای هسته‌ی ${server.proto.label} ناموفق بود: ${SingBoxCore.lastError().ifBlank { "خطای ناشناخته" }}")
            return
        }

        // اعتبارسنجی واقعی: تا وقتی ترافیک از تانل عبور نکند «وصل» حساب نمی‌شود
        val delay = SocksProbe.delay(ir.v2max.app.core.SingBoxConfig.SOCKS_PORT, baseSettings.delayTestUrl)
        if (delay <= 0) {
            SingBoxCore.stop()
            closeTun()
            usingSingBox = false
            Repo.recordOutcome(server.id, false)
            if (!rotate("سرور ${server.proto.label} پاسخ نداد")) {
                fail("اتصال با هسته‌ی ${server.proto.label} برقرار نشد")
            }
            return
        }

        currentServer = server
        connectedAt = System.currentTimeMillis()
        Failover.reset()
        Repo.recordOutcome(server.id, true)
        Repo.setTestResult(server.id, server.tcpPing, delay.toInt())
        Repo.pushLatency(delay.toInt())
        publish(
            TunnelUiState(
                status = TunnelStatus.CONNECTED,
                serverId = server.id,
                serverName = server.displayName(),
                serverProto = server.proto.label,
                ping = delay.toInt(),
                connectedAt = connectedAt,
                message = "متصل (${server.proto.label})"
            )
        )
        startMonitor()
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
            var lastTick = System.currentTimeMillis()
            var lastProbe = System.currentTimeMillis()
            var probeIndex = 0
            var failures = 0

            while (isActive) {
                delay(1000)
                val state = Repo.tunnel.value
                if (state.status != TunnelStatus.CONNECTED) break

                val traffic = withContext(Dispatchers.IO) { coreTraffic() }
                totalUp += traffic[0]
                totalDown += traffic[1]

                // سقف مصرف روزانه
                ir.v2max.app.util.DataUsage.add(this@V2maxVpnService, traffic[0] + traffic[1])
                val limitMb = Repo.settings.value.dailyLimitMb
                if (limitMb > 0) {
                    val usedMb = ir.v2max.app.util.DataUsage.todayBytes(this@V2maxVpnService) / (1024L * 1024L)
                    if (usedMb >= limitMb) {
                        Notifier.dailyLimitReached(this@V2maxVpnService, limitMb)
                        stopTunnel()
                        return@launch
                    }
                }

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

                if (networkChanged) {
                    // شبکه عوض شده؛ همان لحظه کیفیت را دوباره بسنج
                    networkChanged = false
                    lastProbe = 0
                    failures = 0
                }
                val interval = probeInterval()
                if (now - lastProbe < interval) continue
                lastProbe = now

                // اگر هسته از کار افتاده باشد، اتصال بدون دخالت کاربر بازسازی می‌شود
                if (withContext(Dispatchers.IO) { !coreRunning() }) {
                    Log.w(TAG, "core stopped unexpectedly")
                    if (Repo.settings.value.smartFailover) {
                        if (rotate("بازسازی هسته")) return@launch
                    }
                    fail("اتصال قطع شد")
                    return@launch
                }

                // پینگ چرخشی روی مقصدهای مختلف تا کیفیت واقعی اینترنت سنجیده شود
                val targets = Repo.settings.value.let {
                    if (it.multiPing) ir.v2max.app.data.PingTargets.ALL
                    else listOf(ir.v2max.app.data.PingTargets.GOOGLE)
                }
                val target = targets[probeIndex % targets.size]
                probeIndex++

                val ping = withContext(Dispatchers.IO) {
                    if (usingSingBox) SocksProbe.delay(ir.v2max.app.core.SingBoxConfig.SOCKS_PORT, target.url)
                    else XrayCore.measureDelay(target.url)
                }

                if (ping > 0) {
                    if (failures > 0) Log.i(TAG, "connection recovered after $failures failures")
                    failures = 0
                    Repo.pushLatency(ping.toInt())
                    Repo.setTestResult(
                        Repo.tunnel.value.serverId,
                        Repo.serverById(Repo.tunnel.value.serverId)?.tcpPing ?: -1,
                        ping.toInt()
                    )
                    publish(
                        Repo.tunnel.value.copy(
                            ping = ping.toInt(),
                            message = "متصل"
                        )
                    )
                    continue
                }

                failures++
                Log.w(TAG, "probe on ${target.name} failed ($failures)")

                if (!Repo.settings.value.smartFailover) continue

                // مقصد گوگل معیار اصلی است؛ افت اینستاگرام/یوتیوب تنها به‌تنهایی
                // باعث جابه‌جایی نمی‌شود تا اتصال بی‌دلیل عوض نشود
                val critical = target.url == ir.v2max.app.data.PingTargets.GOOGLE.url
                val threshold = if (critical) FAIL_THRESHOLD else FAIL_THRESHOLD + 2

                if (failures == threshold) {
                    Repo.tunnel.value.serverId.takeIf { it.isNotBlank() }
                        ?.let { Repo.recordOutcome(it, false) }
                    publish(
                        Repo.tunnel.value.copy(message = "کیفیت شبکه افت کرد؛ در حال یافتن مسیر بهتر…")
                    )
                    if (rotate("سرور فعلی پاسخ نمی‌دهد")) return@launch
                } else if (failures >= MAX_FAIL_RESILIENCE) {
                    // تلاش نهایی: کاوش گسترده؛ اگر چیزی نبود اتصال را قطع نمی‌کنیم
                    val replacement = Failover.probeAsync(limit = 120)
                        ?: Failover.findReplacement(currentServer?.id)
                    if (replacement != null) {
                        failoverAttempts++
                        failoverPending = true
                        Log.i(TAG, "hard switch → ${replacement.endpoint}")
                        connect(replacement.id)
                        return@launch
                    }
                    failures = threshold
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
            Notifier.update(this, Repo.tunnel.value, false, Repo.settings.value.hideNotificationContent)
        }

        // توقف همگام تا مطمئن شویم هسته و رابط TUN واقعاً آزاد شده‌اند
        if (usingSingBox) SingBoxCore.stop() else XrayCore.stop()
        usingSingBox = false
        closeTun()
        running = false
        Repo.poolSize.value = 0
        Repo.tunnel.value = TunnelUiState(status = TunnelStatus.DISCONNECTED)
        Notifier.cancel(this)
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail(message: String) {
        running = false
        if (usingSingBox) SingBoxCore.stop() else XrayCore.stop()
        usingSingBox = false

        // Kill Switch: رابط TUN باز می‌ماند تا هیچ بسته‌ای بیرون از تانل نرود
        val killSwitch = Repo.settings.value.killSwitch && tun != null
        if (killSwitch) {
            Repo.tunnel.value = Repo.tunnel.value.copy(
                status = TunnelStatus.ERROR,
                message = "ترافیک مسدود شد (Kill Switch) — $message",
                downSpeed = 0,
                upSpeed = 0
            )
            Notifier.blocked(this, message)
            return
        }

        closeTun()
        Repo.tunnel.value = Repo.tunnel.value.copy(
            status = TunnelStatus.ERROR,
            message = message,
            downSpeed = 0,
            upSpeed = 0
        )
        Notifier.update(this, Repo.tunnel.value, false, Repo.settings.value.hideNotificationContent)
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * فاصله‌ی مناسب بین تست‌های سلامت بر اساس وضعیت صفحه، حالت ذخیره‌ی انرژی و شارژ باتری.
     * این کار مصرف باتری را در استفاده‌ی شبانه تا حدود یک‌سوم کاهش می‌دهد.
     */
    private fun probeInterval(): Long {
        if (!Repo.settings.value.batterySaver) return PROBE_INTERVAL_MS
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val interactive = pm.isInteractive
            val powerSave = pm.isPowerSaveMode
            val batteryLow = runCatching {
                val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) ?: 100
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                scale > 0 && level * 100 / scale <= 15
            }.getOrDefault(false)

            when {
                powerSave || batteryLow -> PROBE_INTERVAL_SAVER_MS
                !interactive -> PROBE_INTERVAL_IDLE_MS
                else -> PROBE_INTERVAL_MS
            }
        } catch (t: Throwable) {
            PROBE_INTERVAL_MS
        }
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    // ------------------------------------------------------------ publishing

    private fun publish(state: TunnelUiState) {
        Repo.tunnel.value = state
        runCatching { V2maxWidget.refresh(this) }
        if (!isForeground) {
            startForegroundCompat(state)
            isForeground = true
        } else {
            Notifier.update(
                this,
                state,
                Repo.settings.value.showSpeedInNotification && !Repo.settings.value.hideNotificationContent,
                Repo.settings.value.hideNotificationContent
            )
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
