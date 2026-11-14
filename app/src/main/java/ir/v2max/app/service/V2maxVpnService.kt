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
import ir.v2max.app.core.AppliedTunnelPolicy
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.Failover
import ir.v2max.app.core.PreflightTickets
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.core.TunnelResourceOwnership
import ir.v2max.app.core.VerifiedSelection
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.net.CarrierProbe
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
        const val ACTION_CANCEL_PREFLIGHT = "ir.v2max.app.action.CANCEL_PREFLIGHT"

        /** رفع مسدودی Kill Switch و بازگرداندن اینترنت عادی */
        const val ACTION_UNBLOCK = "ir.v2max.app.action.UNBLOCK"
        const val ACTION_STOP = "ir.v2max.app.action.STOP_TUNNEL"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_PREFLIGHT_TICKET = "extra_preflight_ticket"
        const val EXTRA_SMART_SELECT = "extra_smart_select"
        internal const val EXTRA_ACTION_GENERATION = "extra_action_generation"

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

        // Service callbacks and coroutine work run on different threads. Share
        // this gate across service instances so a newer user action invalidates
        // old preflight completions before they can replace a working tunnel.
        private val coreStartStopLock = Any()
        private val connectToken = java.util.concurrent.atomic.AtomicInteger(0)
        private val resourceOwner = TunnelResourceOwnership() // guarded by coreStartStopLock

        internal fun currentActionGeneration(): Int = connectToken.get()
        internal fun invalidatePendingActions(): Int = synchronized(coreStartStopLock) {
            connectToken.incrementAndGet()
        }

        @Volatile
        var running: Boolean = false
            private set

        private data class ActiveIdentity(val id: String, val digest: String, val policy: AppliedTunnelPolicy)
        @Volatile private var activeIdentity: ActiveIdentity? = null

        private fun identity(server: ServerConfig, settings: AppSettings): ActiveIdentity {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(server.fingerprint().toByteArray(Charsets.UTF_8))
            return ActiveIdentity(
                server.id, bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) },
                AppliedTunnelPolicy.from(server, settings)
            )
        }

        /** An edited profile or changed VPN policy is not the configuration currently running. */
        internal fun activeProfileMatches(server: ServerConfig, settings: AppSettings = Repo.settings.value): Boolean =
            running && activeIdentity == identity(server, settings)
    }

    /** هیچ خطایی در کارهای سرویس نباید باعث بسته‌شدن اجباری برنامه شود */
    private val serviceErrorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        ir.v2max.app.util.CrashLog.record(throwable, "vpnService")
        Log.e(TAG, "unhandled service error", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + serviceErrorHandler)

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var currentServer: ServerConfig? = null
    private var monitorJob: Job? = null
    private var connectJob: Job? = null
    private var preflightJob: Job? = null
    private var actionJob: Job? = null
    private val connectMutex = Mutex()

    private var connectedAt = 0L
    private var totalUp = 0L
    private var totalDown = 0L

    private fun coreRunning(): Boolean = XrayCore.isRunning

    private fun coreTraffic(): LongArray = XrayCore.trafficSinceLastQuery()

    @Volatile private var failoverAttempts = 0

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
        val commandGeneration = if (intent?.hasExtra(EXTRA_ACTION_GENERATION) == true)
            intent.getIntExtra(EXTRA_ACTION_GENERATION, -1) else null
        if (commandGeneration != null && commandGeneration != connectToken.get()) {
            return ignoreStaleCommand(intent, startId)
        }
        if (action == ACTION_STOP || action == ACTION_UNBLOCK) {
            val stopped = stopTunnel(startId, commandGeneration)
            return if (stopped) START_NOT_STICKY else START_STICKY
        }

        if (action == ACTION_CANCEL_PREFLIGHT) {
            val generation = commandGeneration ?: invalidatePendingActions()
            synchronized(coreStartStopLock) {
                if (generation != connectToken.get()) return START_STICKY
                preflightJob?.cancel()
                actionJob?.cancel()
                connectJob?.cancel()
                monitorJob?.cancel()
                val active = currentServer
                if (running && coreRunning() && tun != null && active != null) {
                    Failover.markWorking(active.id)
                    publish(Repo.tunnel.value.copy(status = TunnelStatus.CONNECTED, message = "متصل"))
                    startMonitor()
                    return START_STICKY
                }
                // A canceled attempt may already have replaced the old tunnel.
                // Close it rather than leaving a false CONNECTING/CONNECTED state.
                resourceOwner.clear()
                XrayCore.stop()
                closeTun()
                currentServer = null
                running = false
                activeIdentity = null
                Repo.poolSize.value = 0
                Repo.tunnel.value = TunnelUiState(status = TunnelStatus.DISCONNECTED)
                Notifier.cancel(this)
                stopForegroundCompat()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        if (action == ACTION_NEXT) {
            val generation = synchronized(coreStartStopLock) {
                if (commandGeneration != null && commandGeneration != connectToken.get()) return START_STICKY
                SmartConnect.cancelScan()
                preflightJob?.cancel()
                connectJob?.cancel()
                actionJob?.cancel()
                monitorJob?.cancel()
                connectToken.incrementAndGet()
            }
            refreshActionNotification()
            val current = currentServer?.id ?: Repo.tunnel.value.serverId
            actionJob = scope.launch {
                try {
                    if (generation != connectToken.get()) return@launch
                    Failover.markFailed(current)
                    val next = Failover.findReplacement(applicationContext, current)
                    coroutineContext.ensureActive()
                    if (generation != connectToken.get() || !Repo.tunnel.value.isActive) return@launch
                    if (next != null) {
                        connect(next, generation, isFailover = true)
                    } else {
                        Failover.markWorking(current)
                        preflightFailed("سرور جایگزین تأییدشده‌ای پیدا نشد", generation)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    preflightFailed("بررسی سرور جایگزین ناموفق بود", generation)
                }
            }
            return START_STICKY
        }

        if (action == ACTION_RETEST) {
            val generation = synchronized(coreStartStopLock) {
                if (commandGeneration != null && commandGeneration != connectToken.get()) return START_STICKY
                SmartConnect.cancelScan()
                preflightJob?.cancel()
                connectJob?.cancel()
                actionJob?.cancel()
                monitorJob?.cancel()
                connectToken.incrementAndGet()
            }
            refreshActionNotification()
            actionJob = scope.launch {
                try {
                    if (generation != connectToken.get()) return@launch
                    val id = currentServer?.id ?: Repo.tunnel.value.serverId
                    val server = Repo.serverById(id)?.copy()
                    if (server != null) {
                        val result = ProbePlanner.verify(server, Repo.settings.value, ir.v2max.app.net.HealthScanner::realDelay)
                        coroutineContext.ensureActive()
                        if (generation != connectToken.get() || Repo.tunnel.value.status != TunnelStatus.CONNECTED ||
                            currentServer?.id != id || Repo.serverById(id)?.fingerprint() != server.fingerprint()) {
                            resumeMonitorIfCurrent(generation)
                            return@launch
                        }
                        if (result == null) {
                            if (!rotate("تست مجدد ناموفق بود", generation)) resumeMonitorIfCurrent(generation)
                        } else {
                            Repo.setVerifiedResult(server.id, result, expectedFingerprint = server.fingerprint())
                            Repo.persistNow()
                            publishIfCurrent(Repo.tunnel.value.copy(ping = result.delayMs, message = "تست مجدد: ${result.delayMs}ms"), generation)
                            resumeMonitorIfCurrent(generation)
                        }
                    } else resumeMonitorIfCurrent(generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    preflightFailed("تست مجدد ناموفق بود", generation)
                }
            }
            return START_STICKY
        }

        // Keep reporting a working tunnel as connected while a replacement is tested.
        val acceptedGeneration = commandGeneration ?: connectToken.get()
        val previous = Repo.tunnel.value
        val alreadyConnected = previous.status == TunnelStatus.CONNECTED && running && coreRunning()
        if (!publishIfCurrent(previous.copy(
            status = if (alreadyConnected) TunnelStatus.CONNECTED else TunnelStatus.CONNECTING,
            message = if (alreadyConnected) "در حال بررسی اتصال جدید؛ اتصال فعلی برقرار است…" else "در حال آماده‌سازی…"
        ), acceptedGeneration)) return ignoreStaleCommand(intent, startId)

        val serverId = intent?.getStringExtra(EXTRA_SERVER_ID).orEmpty()
            .ifBlank { Repo.selectedId.value }
            .ifBlank { Repo.servers.value.firstOrNull()?.id.orEmpty() }

        // A process-local, single-use receipt proves that all enabled tests
        // finished for these exact options and this exact profile. Old persisted
        // pings or a boolean extra alone must never bypass preflight.
        val ticket = intent?.getStringExtra(EXTRA_PREFLIGHT_TICKET)
        val selection = PreflightTickets.take(ticket)?.takeIf {
            it.id == serverId && it.validFor(Repo.serverById(serverId), Repo.settings.value, CarrierProbe.networkHandle(this))
        }
        if (selection != null) {
            connect(selection, acceptedGeneration)
            return START_STICKY
        }
        if (!ticket.isNullOrBlank()) {
            // An old request must neither reconnect after Disconnect nor cancel
            // another connection already being established.
            if (previous.status == TunnelStatus.CONNECTING &&
                (connectJob?.isActive == true || preflightJob?.isActive == true)) {
                publishIfCurrent(previous, acceptedGeneration)
            } else preflightFailed("نتیجه‌ی تست لغو شد یا اعتبار آن پایان یافت؛ دوباره تلاش کنید", acceptedGeneration)
            return if (previous.isActive) START_STICKY else START_NOT_STICKY
        }
        if (Repo.servers.value.isEmpty()) {
            preflightFailed("هیچ کانفیگی برای بررسی وجود ندارد", acceptedGeneration)
            return START_NOT_STICKY
        }

        val generation = synchronized(coreStartStopLock) {
            if (acceptedGeneration != connectToken.get()) return START_STICKY
            preflightJob?.cancel()
            connectJob?.cancel()
            actionJob?.cancel()
            monitorJob?.cancel()
            connectToken.incrementAndGet()
        }
        publishIfCurrent(Repo.tunnel.value.copy(message = "اجرای تست‌های پیش از اتصال…"), generation)
        preflightJob = scope.launch {
            try {
                if (generation != connectToken.get()) return@launch
                val preferred = serverId.takeIf {
                    it.isNotBlank() && intent?.getBooleanExtra(EXTRA_SMART_SELECT, false) != true
                }?.let { id ->
                    SmartConnect.scanAndConnect(applicationContext, autoConnect = false, limit = 1, preferredId = id)
                }
                coroutineContext.ensureActive()
                val best = preferred ?: SmartConnect.scanAndConnect(
                    applicationContext, autoConnect = false,
                    excludedIds = if (serverId.isNotBlank() && intent?.getBooleanExtra(EXTRA_SMART_SELECT, false) != true)
                        setOf(serverId) else emptySet()
                )
                coroutineContext.ensureActive()
                if (best != null) connect(best, generation)
                else preflightFailed(Repo.scan.value.message.ifBlank { "هیچ سرور تأییدشده‌ای پیدا نشد" }, generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preflightFailed("تست پیش از اتصال ناموفق بود", generation)
            }
        }
        return START_STICKY
    }

    private fun ignoreStaleCommand(intent: Intent?, startId: Int): Int {
        // A delayed intent must not undo a newer action. A stale foreground
        // start still needs promotion (or prompt shutdown) on Android O+.
        PreflightTickets.take(intent?.getStringExtra(EXTRA_PREFLIGHT_TICKET))
        if (!isForeground && !running && (intent == null || intent.action == ACTION_START)) {
            startForegroundCompat()
            isForeground = true
        }
        if (!running && preflightJob?.isActive != true && connectJob?.isActive != true && actionJob?.isActive != true) {
            stopForegroundCompat()
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
        preflightJob?.cancel()
        actionJob?.cancel()
        monitorJob?.cancel()
        connectJob?.cancel()
        synchronized(coreStartStopLock) {
            connectToken.incrementAndGet()
            resourceOwner.clear()
            XrayCore.stop()
            closeTun()
            running = false
            activeIdentity = null
        }
        val status = Repo.tunnel.value.status
        if (status != TunnelStatus.DISCONNECTED && status != TunnelStatus.ERROR) {
            Repo.tunnel.value = TunnelUiState(status = TunnelStatus.DISCONNECTED)
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- connecting

    private fun connect(selection: VerifiedSelection, expectedGeneration: Int, isFailover: Boolean = false): Boolean =
        synchronized(coreStartStopLock) {
            // Check-and-advance as one operation; an old failover may finish
            // between a newer manual action and delivery of its service intent.
            if (expectedGeneration != connectToken.get()) return@synchronized false
            // Only the accepted generation may cancel other work. A stale
            // receipt must not cancel a newer preflight before this check.
            preflightJob?.cancel()
            actionJob?.cancel()
            connectJob?.cancel()
            val token = connectToken.incrementAndGet()
            refreshActionNotification()
            if (isFailover) failoverAttempts++
            connectJob = scope.launch {
                connectMutex.withLock {
                    coroutineContext.ensureActive()
                    if (token == connectToken.get()) doConnect(selection, token, isFailover)
                }
            }
            true
        }

    private class PreflightChanged : IllegalStateException()

    private suspend fun doConnect(selection: VerifiedSelection, token: Int, isFailover: Boolean = false) {
        var replacedTunnel = false
        try {
            val server = selection.server()
            val baseSettings = Repo.settings.value.copy()
            var settings = baseSettings.copy(
                fragment = server.testedFragment,
                fragmentProfile = server.testedFragmentProfile,
                balancer = false
            )
            // Catch malformed VPN/routing settings before interrupting a live tunnel.
            val initialConfig = ConfigBuilder.vpnConfig(server, settings, listOf(server))
            synchronized(coreStartStopLock) {
                if (token != connectToken.get()) throw CancellationException("superseded")
                val current = Repo.serverById(server.id)
                val network = CarrierProbe.networkHandle(this)
                if (!selection.validFor(current, baseSettings, network) ||
                    !selection.validFor(current, Repo.settings.value, network) ||
                    AppliedTunnelPolicy.from(server, baseSettings) != AppliedTunnelPolicy.from(server, Repo.settings.value)) {
                    throw PreflightChanged()
                }
                monitorJob?.cancel()
                monitorJob = null
                // Claim before touching the process-global core. A canceled job
                // from a destroyed Service instance may finish much later.
                resourceOwner.claim(token)
                replacedTunnel = true
                running = false
                activeIdentity = null
                XrayCore.stop()
                closeTun()
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
            }

            // Stop and cancellation must not slip between establishing the fd
            // and registering it for cleanup.
            val fd = synchronized(coreStartStopLock) {
                if (token != connectToken.get()) throw CancellationException("superseded")
                val created = establishTun(settings)
                    ?: throw IllegalStateException("دسترسی VPN داده نشده است. دوباره تلاش کنید.")
                tun = created
                Repo.clearLatencyHistory()
                Repo.poolSize.value = 1
                created
            }
            XrayCore.init(applicationContext)

            suspend fun startAndVerify(
                currentSettings: AppSettings,
                pool: List<ServerConfig>,
                prebuilt: String? = null
            ): Pair<Long, String> {
                coroutineContext.ensureActive()
                if (token != connectToken.get()) throw CancellationException("superseded")
                val config = prebuilt ?: ConfigBuilder.vpnConfig(server, currentSettings, pool)
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

            if (!publishIfCurrent(Repo.tunnel.value.copy(message = "راه‌اندازی با تنظیمات تست‌شده…"), token))
                throw CancellationException("superseded")
            var verified = try {
                startAndVerify(settings, listOf(server), initialConfig)
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
            // Pool members must come from this completed preflight, not from
            // arbitrary persisted pings that may lack the enabled optional tests.
            val pool = if (baseSettings.balancer) {
                listOf(server) + selection.pool()
                    .filter { it.id != server.id && it.testedFragment == settings.fragment }
                    .filter { !it.testedFragment || it.testedFragmentProfile == settings.fragmentProfile }
                    .filter { Repo.serverById(it.id)?.fingerprint() == it.fingerprint() }
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
            ), clearOtherTargets = false, expectedFingerprint = server.fingerprint())
            Repo.persistNow()

            synchronized(coreStartStopLock) {
                // A stop/new action may arrive while persistence or the native
                // post-start probe is running. Never publish its stale result.
                if (token != connectToken.get()) throw CancellationException("superseded")
                // A geo/balancer fallback may differ from the user's request;
                // remember what was actually started, not the requested policy.
                activeIdentity = identity(server, settings)
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
                        ping = verified.first.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                        connectedAt = connectedAt,
                        message = "متصل"
                    )
                )
                startMonitor()
            }
            // Persisting a large server list must not hold the lifecycle gate.
            if (token == connectToken.get()) runCatching {
                Repo.recordOutcome(server.id, true)
                SmartConnect.rememberWorkingNetwork(Repo.serverById(server.id) ?: server)
            }.onFailure { Log.w(TAG, "could not persist connection statistics") }
        } catch (e: CancellationException) {
            if (replacedTunnel) releaseOwnedResources(token)
            throw e
        } catch (e: PreflightChanged) {
            preflightFailed("کانفیگ، شبکه یا تنظیمات پس از تست تغییر کرد؛ دوباره تلاش کنید", token)
        } catch (t: Exception) {
            if (token != connectToken.get()) {
                if (replacedTunnel) releaseOwnedResources(token)
                return
            }
            Log.e(TAG, "connect failed", t)
            if (!replacedTunnel) {
                // Configuration construction failed before we touched the old VPN.
                preflightFailed("آماده‌سازی اتصال جدید ناموفق بود", token)
                return
            }

            // اگر اتصال اولیه هم شکست خورد، به‌جای تسلیم شدن، سرور بعدی امتحان می‌شود
            if (!isFailover && Repo.settings.value.smartFailover && failoverAttempts < MAX_FAILOVERS) {
                if (rotate("اتصال با این سرور برقرار نشد", token)) return
            }

            // اگر همین تلاشِ جایگزینی شکست خورد، سرور بعدی را امتحان می‌کنیم
            if (isFailover && Repo.settings.value.smartFailover && failoverAttempts < MAX_FAILOVERS) {
                val failedId = currentServer?.id.orEmpty()
                Failover.markFailed(failedId)
                val next = Failover.findReplacement(applicationContext, failedId)
                coroutineContext.ensureActive()
                if (next != null && connect(next, token, isFailover = true)) {
                    Log.i(TAG, "failover retry #$failoverAttempts")
                    return
                }
            }

            if (token == connectToken.get()) currentServer?.let { Repo.recordOutcome(it.id, false) }
            val raw = (t.message ?: t.javaClass.simpleName).take(180)
            fail("اتصال برقرار نشد: $raw", token)
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

    private fun publishIfCurrent(state: TunnelUiState, generation: Int): Boolean = synchronized(coreStartStopLock) {
        if (generation != connectToken.get()) return@synchronized false
        publish(state)
        true
    }

    private fun resumeMonitorIfCurrent(generation: Int) = synchronized(coreStartStopLock) {
        if (generation == connectToken.get() && running && coreRunning() && tun != null) startMonitor()
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        val monitorGeneration = connectToken.get()
        monitorJob = scope.launch {
            var lastTick = System.currentTimeMillis()
            var lastProbe = System.currentTimeMillis()
            var probeIndex = 0
            var failures = 0

            while (isActive) {
                delay(1000)
                if (monitorGeneration != connectToken.get()) return@launch
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
                        // A slow traffic query must not stop a newer user action.
                        if (stopTunnel(expectedGeneration = monitorGeneration)) {
                            Notifier.dailyLimitReached(this@V2maxVpnService, limitMb)
                        }
                        return@launch
                    }
                }

                val now = System.currentTimeMillis()
                val elapsed = (now - lastTick).coerceAtLeast(1L)
                lastTick = now

                if (!publishIfCurrent(
                    state.copy(
                        downSpeed = traffic[1] * 1000L / elapsed,
                        upSpeed = traffic[0] * 1000L / elapsed,
                        totalDown = totalDown,
                        totalUp = totalUp,
                        connectedAt = connectedAt
                    ), monitorGeneration
                )) return@launch

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
                        if (rotate("بازسازی هسته", monitorGeneration)) return@launch
                    }
                    fail("اتصال قطع شد", monitorGeneration)
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
                    if (!publishIfCurrent(Repo.tunnel.value.copy(ping = ping.toInt(), message = "متصل"), monitorGeneration))
                        return@launch
                    Repo.pushLatency(ping.toInt())
                    continue
                }

                failures++
                Log.w(TAG, "all connection probes failed ($failures)")
                if (!options.smartFailover) continue
                val threshold = FAIL_THRESHOLD

                if (failures == threshold) {
                    Repo.tunnel.value.serverId.takeIf { it.isNotBlank() }
                        ?.let { Repo.recordOutcome(it, false) }
                    if (!publishIfCurrent(
                        Repo.tunnel.value.copy(message = "کیفیت شبکه افت کرد؛ در حال یافتن مسیر بهتر…"), monitorGeneration
                    )) return@launch
                    if (rotate("سرور فعلی پاسخ نمی‌دهد", monitorGeneration)) return@launch
                } else if (failures >= MAX_FAIL_RESILIENCE) {
                    // تلاش نهایی: کاوش گسترده؛ اگر چیزی نبود اتصال را قطع نمی‌کنیم
                    val replacement = Failover.probeAsync(applicationContext, limit = 120, currentId = currentServer?.id)
                        ?: Failover.findReplacement(applicationContext, currentServer?.id)
                    coroutineContext.ensureActive()
                    if (replacement != null && connect(replacement, monitorGeneration, isFailover = true)) {
                        Log.i(TAG, "hard switch to a verified replacement")
                        return@launch
                    }
                    if (monitorGeneration != connectToken.get()) return@launch
                    failures = threshold
                }
            }
        }
    }

    /** جابه‌جایی تنها به سروری که تست واقعی را گذرانده است. */
    private suspend fun rotate(reason: String, generation: Int): Boolean {
        if (generation != connectToken.get()) return false
        val currentId = currentServer?.id ?: Repo.tunnel.value.serverId
        Failover.markFailed(currentId)

        if (failoverAttempts >= MAX_FAILOVERS) {
            Log.w(TAG, "failover limit reached")
            return false
        }

        if (!publishIfCurrent(
            Repo.tunnel.value.copy(
                message = "$reason؛ در حال یافتن سرور جایگزین…",
                downSpeed = 0,
                upSpeed = 0
            ), generation
        )) return false

        val next = Failover.findReplacement(applicationContext, currentId) ?: return false
        coroutineContext.ensureActive()
        if (!connect(next, generation, isFailover = true)) return false
        Log.i(TAG, "failover #$failoverAttempts ($reason)")
        return true
    }

    // -------------------------------------------------------------- teardown

    private fun stopTunnel(startId: Int? = null, expectedGeneration: Int? = null): Boolean {
        val stopped = synchronized(coreStartStopLock) {
            if (expectedGeneration != null && expectedGeneration != connectToken.get()) return@synchronized false
            connectToken.incrementAndGet()
            SmartConnect.cancelScan()
            preflightJob?.cancel()
            preflightJob = null
            actionJob?.cancel()
            actionJob = null
            monitorJob?.cancel()
            monitorJob = null
            connectJob?.cancel()
            connectJob = null

            if (Repo.tunnel.value.status != TunnelStatus.DISCONNECTED) {
                Repo.tunnel.value = Repo.tunnel.value.copy(status = TunnelStatus.DISCONNECTING)
                Notifier.update(this, Repo.tunnel.value, false, Repo.settings.value.hideNotificationContent)
            }
            resourceOwner.clear()
            XrayCore.stop()
            closeTun()
            currentServer = null
            running = false
            activeIdentity = null
            Repo.poolSize.value = 0
            Repo.tunnel.value = TunnelUiState(status = TunnelStatus.DISCONNECTED)
            Notifier.cancel(this)
            stopForegroundCompat()
            true
        }
        if (stopped) {
            // Do not stop a newer Android service start that has already been queued.
            if (startId == null) stopSelf() else stopSelf(startId)
        }
        return stopped
    }

    /** A failed/stale preflight must not tear down an already working VPN. */
    private fun preflightFailed(message: String, generation: Int) = synchronized(coreStartStopLock) {
        if (generation != connectToken.get()) return@synchronized
        val active = currentServer
        if (running && coreRunning() && tun != null && active != null) {
            publish(Repo.tunnel.value.copy(
                status = TunnelStatus.CONNECTED, serverId = active.id,
                serverName = active.displayName(), serverProto = active.proto.label,
                message = "$message؛ اتصال فعلی حفظ شد"
            ))
            startMonitor()
        } else {
            fail(message, generation)
        }
    }

    private fun fail(message: String, generation: Int) = synchronized(coreStartStopLock) {
        if (generation != connectToken.get()) return@synchronized
        running = false
        activeIdentity = null
        resourceOwner.clear()
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
            return@synchronized
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

    private fun releaseOwnedResources(owner: Int) = synchronized(coreStartStopLock) {
        if (resourceOwner.release(owner)) {
            XrayCore.stop()
            closeTun()
        }
    }

    // ------------------------------------------------------------ publishing

    private fun refreshActionNotification() {
        if (!isForeground) return
        val options = Repo.settings.value
        Notifier.update(this, Repo.tunnel.value,
            options.showSpeedInNotification && !options.hideNotificationContent, options.hideNotificationContent)
    }

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
