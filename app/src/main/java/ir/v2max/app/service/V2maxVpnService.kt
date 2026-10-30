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
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.net.ProbePlanner
import ir.v2max.app.net.ProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
        const val EXTRA_PRETEST_COMPLETE = "extra_pretest_complete"

        /** هر چند ثانیه یک‌بار کیفیت اتصال سنجیده می‌شود (پایش هر ثانیه‌ای) */
        private const val PROBE_INTERVAL_MS = 30_000L

        /** فاصله‌ی پروب وقتی صفحه خاموش است (صرفه‌جویی باتری) */
        private const val PROBE_INTERVAL_IDLE_MS = 60_000L

        /** فاصله‌ی پروب در حالت ذخیره‌ی انرژی یا باتری کم */
        private const val PROBE_INTERVAL_SAVER_MS = 120_000L

        /** چند شکست پیاپی یعنی «اتصال عملاً مرده است» */
        private const val FAIL_THRESHOLD = 3

        /** سقف زمان انتظار پیش از جایگزینی اجباری */
        private const val MAX_FAIL_RESILIENCE = 15

        /** حداکثر تعداد جابه‌جایی خودکار در یک نشست */
        private const val MAX_FAILOVERS = 10

        @Volatile
        var running: Boolean = false
            private set
    }

    /** هیچ خطایی در کارهای سرویس نباید باعث بسته‌شدن اجباری برنامه شود */
    private val serviceErrorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        ir.v2max.app.util.CrashLog.record(throwable, "vpnService")
        Log.e(TAG, "unhandled service error", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + serviceErrorHandler)

    private var tun: ParcelFileDescriptor? = null
    private var currentServer: ServerConfig? = null
    private var monitorJob: Job? = null
    private var connectJob: Job? = null
    private var preflightJob: Job? = null
    private val connectMutex = Mutex()
    private val coreStartStopLock = Any()

    private var connectedAt = 0L
    private var totalUp = 0L
    private var totalDown = 0L

    private fun coreRunning(): Boolean = XrayCore.isRunning

    private fun coreTraffic(): LongArray = XrayCore.trafficSinceLastQuery()

    private var failoverAttempts = 0
    private val connectToken = java.util.concurrent.atomic.AtomicInteger(0)
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
            val generation = connectToken.get()
            scope.launch {
                Failover.markFailed(current)
                val next = Failover.findReplacement(applicationContext, current)
                coroutineContext.ensureActive()
                if (generation != connectToken.get() || !Repo.tunnel.value.isActive) return@launch
                if (next != null) {
                    failoverPending = true
                    connect(next.id)
                } else Failover.markWorking(current)
            }
            return START_STICKY
        }

        if (action == ACTION_RETEST) {
            // تست دوباره‌ی سرور فعلی و جابه‌جایی در صورت خرابی
            scope.launch {
                val id = currentServer?.id ?: Repo.tunnel.value.serverId
                val server = Repo.serverById(id)
                if (server != null) {
                    val result = ProbePlanner.verify(server, Repo.settings.value, ir.v2max.app.net.HealthScanner::realDelay)
                    if (Repo.tunnel.value.status != TunnelStatus.CONNECTED || currentServer?.id != id) return@launch
                    if (result == null) {
                        rotate("تست مجدد ناموفق بود")
                    } else {
                        Repo.setVerifiedResult(server.id, result)
                        Repo.persistNow()
                        publish(Repo.tunnel.value.copy(ping = result.delayMs, message = "تست مجدد: ${result.delayMs}ms"))
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

        // UI, boot, tile and automation all enter through the same verified path.
        // A fresh result for a different server is not proof that this one works.
        preflightJob?.cancel()
        val selected = Repo.serverById(serverId)
        val fresh = intent?.getBooleanExtra(EXTRA_PRETEST_COMPLETE, false) == true &&
            selected != null && selected.realPing > 0 && selected.testedUrl.isNotBlank() &&
            System.currentTimeMillis() - selected.testedAt in 0..(10 * 60 * 1000L)
        if (!fresh && Repo.servers.value.isNotEmpty()) {
            connectJob?.cancel()
            val generation = connectToken.incrementAndGet()
            publish(Repo.tunnel.value.copy(
                status = TunnelStatus.CONNECTING,
                message = "اجرای تست واقعی پیش از اتصال…"
            ))
            preflightJob = scope.launch {
                try {
                    val preferred = serverId.takeIf { it.isNotBlank() }?.let { id ->
                        ir.v2max.app.core.SmartConnect.scanAndConnect(
                            applicationContext, autoConnect = false, limit = 1, preferredId = id
                        )
                    }
                    coroutineContext.ensureActive()
                    val best = preferred ?: ir.v2max.app.core.SmartConnect.scanAndConnect(
                        applicationContext, autoConnect = false
                    )
                    coroutineContext.ensureActive()
                    if (generation != connectToken.get()) return@launch
                    if (best != null) connect(best.id)
                    else fail(Repo.scan.value.message.ifBlank { "هیچ سرور تأییدشده‌ای پیدا نشد" })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == connectToken.get()) fail("تست پیش از اتصال ناموفق بود")
                }
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
        connectToken.incrementAndGet()
        preflightJob?.cancel()
        monitorJob?.cancel()
        connectJob?.cancel()
        synchronized(coreStartStopLock) {
            XrayCore.stop()
            closeTun()
        }
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
        val token = connectToken.incrementAndGet()
        val isFailover = failoverPending
        failoverPending = false
        connectJob = scope.launch {
            connectMutex.withLock {
                coroutineContext.ensureActive()
                if (token == connectToken.get()) doConnect(serverId, token, isFailover)
            }
        }
    }

    private suspend fun doConnect(serverId: String, token: Int, isFailover: Boolean = false) {
        try {
            val server = Repo.serverById(serverId)
                ?: throw IllegalStateException("سرور انتخاب‌شده دیگر در فهرست نیست")
            require(server.realPing > 0 && server.testedUrl.isNotBlank()) {
                "پیش از اتصال باید تست واقعی این سرور کامل شود"
            }

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
            synchronized(coreStartStopLock) {
                if (token != connectToken.get()) throw CancellationException("superseded")
                XrayCore.stop()
                closeTun()
            }

            val baseSettings = Repo.settings.value
            // Use the exact fragment profile and destination that passed the pre-connect test.
            var settings = baseSettings.copy(
                fragment = server.testedFragment,
                fragmentProfile = server.testedFragmentProfile,
                balancer = false
            )
            val fd = establishTun(settings)
                ?: throw IllegalStateException("دسترسی VPN داده نشده است. دوباره تلاش کنید.")
            tun = fd
            XrayCore.init(applicationContext)
            Repo.clearLatencyHistory()
            Repo.poolSize.value = 1

            suspend fun startAndVerify(currentSettings: AppSettings, pool: List<ServerConfig>): Pair<Long, String> {
                coroutineContext.ensureActive()
                if (token != connectToken.get()) throw CancellationException("superseded")
                val config = ConfigBuilder.vpnConfig(server, currentSettings, pool)
                synchronized(coreStartStopLock) {
                    if (token != connectToken.get()) throw CancellationException("superseded")
                    XrayCore.stop()
                    XrayCore.start(config, fd.fd)
                }
                if (!XrayCore.isRunning) throw IllegalStateException("هسته اجرا نشد")
                // This probes the running Xray outbound; it does not by itself prove Android TUN traffic.
                val urls = (listOf(server.testedUrl) + ProbePlanner.testUrls(currentSettings))
                    .filter { it.isNotBlank() }.distinct()
                for (url in urls) {
                    coroutineContext.ensureActive()
                    val ping = withContext(Dispatchers.IO) { XrayCore.measureDelay(url) }
                    if (ping > 0) return ping to url
                }
                throw IllegalStateException("پروکسی به مقصدهای تست پاسخ نداد")
            }

            publish(Repo.tunnel.value.copy(message = "راه‌اندازی با تنظیمات تست‌شده…"))
            var verified = try {
                startAndVerify(settings, listOf(server))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A damaged geodata file must not make every otherwise-valid profile unusable.
                val message = e.message.orEmpty().lowercase()
                if (settings.geoAssets && (message.contains("geoip") || message.contains("geosite"))) {
                    XrayCore.log("فایل مسیریابی جغرافیایی نامعتبر بود؛ اتصال بدون آن امتحان می‌شود")
                    settings = settings.copy(geoAssets = false)
                    startAndVerify(settings, listOf(server))
                } else throw e
            }

            val primaryVerification = verified
            // Optional pool: only include freshly protocol-tested servers using the same
            // fragment settings. A pool failure falls back to the already-verified primary.
            val pool = if (baseSettings.balancer) {
                listOf(server) + Repo.healthyServers(10 * 60 * 1000L)
                    .filter { it.id != server.id && it.testedFragment == settings.fragment }
                    .filter { !it.testedFragment || it.testedFragmentProfile == settings.fragmentProfile }
                    .take((baseSettings.balancerSize - 1).coerceIn(0, 5))
            } else listOf(server)
            if (pool.size > 1) {
                try {
                    val poolSettings = settings.copy(balancer = true)
                    val poolVerified = startAndVerify(poolSettings, pool)
                    settings = poolSettings
                    verified = poolVerified
                    Repo.poolSize.value = pool.size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    XrayCore.log("استخر پاسخ نداد؛ بازگشت به سرور انتخابی")
                    verified = startAndVerify(settings, listOf(server))
                    Repo.poolSize.value = 1
                }
            }
            coroutineContext.ensureActive()
            if (token != connectToken.get()) throw CancellationException("superseded")
            Repo.setVerifiedResult(server.id, ProbeResult(
                primaryVerification.first.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                primaryVerification.second, settings.fragment, settings.fragmentProfile
            ))
            Repo.persistNow()

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
                    ping = verified.first.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                    connectedAt = connectedAt,
                    message = "متصل"
                )
            )

            startMonitor()
        } catch (e: CancellationException) {
            synchronized(coreStartStopLock) {
                XrayCore.stop()
                closeTun()
            }
            throw e
        } catch (t: Exception) {
            if (token != connectToken.get()) return
            Log.e(TAG, "connect failed", t)

            // اگر اتصال اولیه هم شکست خورد، به‌جای تسلیم شدن، سرور بعدی امتحان می‌شود
            if (!isFailover && Repo.settings.value.smartFailover && failoverAttempts < MAX_FAILOVERS) {
                if (rotate("اتصال با این سرور برقرار نشد")) return
            }

            // اگر همین تلاشِ جایگزینی شکست خورد، سرور بعدی را امتحان می‌کنیم
            if (isFailover && Repo.settings.value.smartFailover && failoverAttempts < MAX_FAILOVERS) {
                val failedId = currentServer?.id.orEmpty()
                Failover.markFailed(failedId)
                val next = Failover.findReplacement(applicationContext, failedId)
                coroutineContext.ensureActive()
                if (token != connectToken.get()) return
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

                // Use the destination that actually worked and a second independent
                // endpoint before treating a temporary destination outage as a tunnel failure.
                val options = Repo.settings.value
                val primary = Repo.serverById(state.serverId)?.testedUrl.orEmpty()
                val multiUrls = if (options.multiPing) ir.v2max.app.data.PingTargets.ALL.map { it.url } else emptyList()
                val rotated = if (multiUrls.isNotEmpty()) multiUrls[probeIndex++ % multiUrls.size] else primary
                val urls = (listOf(rotated, primary) + ProbePlanner.testUrls(options)).filter { it.isNotBlank() }.distinct()
                var ping = -1L
                for (url in urls) {
                    coroutineContext.ensureActive()
                    ping = withContext(Dispatchers.IO) { XrayCore.measureDelay(url) }
                    if (ping > 0) break
                }
                if (ping > 0) {
                    if (failures > 0) Log.i(TAG, "connection recovered after $failures failures")
                    failures = 0
                    Repo.pushLatency(ping.toInt())
                    publish(Repo.tunnel.value.copy(ping = ping.toInt(), message = "متصل"))
                    continue
                }

                failures++
                Log.w(TAG, "all connection probes failed ($failures)")
                if (!options.smartFailover) continue
                val threshold = FAIL_THRESHOLD

                if (failures == threshold) {
                    Repo.tunnel.value.serverId.takeIf { it.isNotBlank() }
                        ?.let { Repo.recordOutcome(it, false) }
                    publish(
                        Repo.tunnel.value.copy(message = "کیفیت شبکه افت کرد؛ در حال یافتن مسیر بهتر…")
                    )
                    if (rotate("سرور فعلی پاسخ نمی‌دهد")) return@launch
                } else if (failures >= MAX_FAIL_RESILIENCE) {
                    // تلاش نهایی: کاوش گسترده؛ اگر چیزی نبود اتصال را قطع نمی‌کنیم
                    val generation = connectToken.get()
                    val replacement = Failover.probeAsync(applicationContext, limit = 120)
                        ?: Failover.findReplacement(applicationContext, currentServer?.id)
                    coroutineContext.ensureActive()
                    if (generation != connectToken.get()) return@launch
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

    /** جابه‌جایی تنها به سروری که تست واقعی را گذرانده است. */
    private suspend fun rotate(reason: String): Boolean {
        val generation = connectToken.get()
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

        val next = Failover.findReplacement(applicationContext, currentId) ?: return false
        coroutineContext.ensureActive()
        if (generation != connectToken.get()) return false

        failoverAttempts++
        Log.i(TAG, "failover #$failoverAttempts → ${next.endpoint} ($reason)")
        failoverPending = true
        connect(next.id)
        return true
    }

    // -------------------------------------------------------------- teardown

    private fun stopTunnel() {
        connectToken.incrementAndGet()
        preflightJob?.cancel()
        preflightJob = null
        monitorJob?.cancel()
        monitorJob = null
        connectJob?.cancel()
        connectJob = null

        if (Repo.tunnel.value.status != TunnelStatus.DISCONNECTED) {
            Repo.tunnel.value = Repo.tunnel.value.copy(status = TunnelStatus.DISCONNECTING)
            Notifier.update(this, Repo.tunnel.value, false, Repo.settings.value.hideNotificationContent)
        }

        // توقف همگام تا مطمئن شویم هسته و رابط TUN واقعاً آزاد شده‌اند
        synchronized(coreStartStopLock) {
            XrayCore.stop()
            closeTun()
        }
        running = false
        Repo.poolSize.value = 0
        Repo.tunnel.value = TunnelUiState(status = TunnelStatus.DISCONNECTED)
        Notifier.cancel(this)
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail(message: String) {
        running = false
        XrayCore.stop()

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
