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
import ir.v2max.app.BuildConfig
import ir.v2max.app.core.AppliedTunnelPolicy
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.ConnectionTestBarrier
import ir.v2max.app.core.Failover
import ir.v2max.app.core.PreflightTickets
import ir.v2max.app.core.RecoveryLoop
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
import ir.v2max.app.net.StartupMaintenance
import ir.v2max.core.domain.PerAppRouting
import ir.v2max.core.model.PerAppMode as CorePerAppMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
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

        /** حداکثر تعداد جابه‌جایی پیاپی قبل از وقفه و تلاش دوباره */
        private const val MAX_FAILOVERS = 10
        private const val STABLE_RECOVERY_RESET_NANOS = 120_000_000_000L

        // Service callbacks and coroutine work run on different threads. Share
        // this gate across service instances so a newer user action invalidates
        // old preflight completions before they can replace a working tunnel.
        private val coreStartStopLock = Any()
        private val connectToken = java.util.concurrent.atomic.AtomicInteger(0)
        private val resourceOwner = TunnelResourceOwnership() // guarded by coreStartStopLock
        private val serviceOwner = TunnelResourceOwnership()  // guarded by coreStartStopLock
        private val nextServiceInstance = java.util.concurrent.atomic.AtomicInteger(0)

        // Disposable-emulator fault injection only; release/preview builds ignore it.
        @Volatile internal var connectionCueForTest: ConnectionCue? = null

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
        Log.e(TAG, "unhandled service error")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + serviceErrorHandler)
    private val serviceInstance = nextServiceInstance.incrementAndGet()
    @Volatile private var connectedSound: AndroidConnectionCue? = null
    @Volatile private var latestStartId = 0

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var currentServer: ServerConfig? = null
    private var monitorJob: Job? = null
    private var connectJob: Job? = null
    private var preflightJob: Job? = null
    private var actionJob: Job? = null
    private val connectMutex = Mutex()

    private var connectedAt = 0L
    private var connectedAtNanos = 0L
    private var totalUp = 0L
    private var totalDown = 0L

    private fun coreRunning(): Boolean = XrayCore.isRunning

    /** Call under coreStartStopLock. */
    private fun connectedForAction(): Boolean =
        serviceOwner.isOwner(serviceInstance) && running && coreRunning() && tun != null &&
            Repo.tunnel.value.status == TunnelStatus.CONNECTED

    private fun ownsGeneration(generation: Int): Boolean = synchronized(coreStartStopLock) {
        serviceOwner.isOwner(serviceInstance) && generation == connectToken.get()
    }

    private fun coreTraffic(): LongArray = XrayCore.trafficSinceLastQuery()

    @Volatile private var failoverAttempts = 0
    @Volatile private var recoveryAttempt = 0

    /** شبکه عوض شده و باید سریع دوباره سنجیده شود */
    @Volatile
    private var networkChanged = false

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(coreStartStopLock) { serviceOwner.claim(serviceInstance) }
        Repo.init(this)
        Notifier.ensureChannel(this)
        // Audio is optional: even creating its dispatcher must not affect VPN setup.
        connectedSound = runCatching { AndroidConnectionCue(applicationContext) }
            .onFailure { Log.w(TAG, "connection sound unavailable") }.getOrNull()
        scope.launch {
            Repo.settings.collect { settings ->
                if (!settings.connectionVoice) cancelConnectedSound()
            }
        }
        registerNetworkWatcher()
    }

    private fun cancelConnectedSound(cancelTestCue: Boolean = true) {
        runCatching { connectedSound?.cue?.cancel() }
        if (BuildConfig.DEBUG && cancelTestCue) runCatching { connectionCueForTest?.cancel() }
    }

    private fun closeConnectedSound(cancelTestCue: Boolean) {
        val sound = connectedSound
        connectedSound = null
        runCatching { sound?.close() }
        if (BuildConfig.DEBUG && cancelTestCue) runCatching { connectionCueForTest?.cancel() }
    }

    /** Called only after post-start verification, not for CONNECTED state updates. */
    private fun announceConnected(generation: Int, serverId: String) {
        if (!Repo.settings.value.connectionVoice) return
        runCatching {
            val cue = if (BuildConfig.DEBUG) connectionCueForTest ?: connectedSound?.cue else connectedSound?.cue
            cue?.announce {
                synchronized(coreStartStopLock) {
                    serviceOwner.isOwner(serviceInstance) && connectToken.get() == generation &&
                        running && tun != null && currentServer?.id == serverId &&
                        Repo.tunnel.value.status == TunnelStatus.CONNECTED && Repo.settings.value.connectionVoice
                }
            }
        }.onFailure { Log.w(TAG, "connection sound unavailable") }
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
        latestStartId = startId
        val action = intent?.action
        if (!synchronized(coreStartStopLock) { serviceOwner.isOwner(serviceInstance) }) {
            return ignoreStaleCommand(intent, startId)
        }
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
                if (!ownsGeneration(generation)) return START_STICKY
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
                cancelConnectedSound()
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

        // Next/retest are meaningful only for an actual running tunnel. Check
        // under the same lock used to cancel work so a queued notification
        // cannot cancel a newly started recovery in the intervening gap.
        if (action == ACTION_NEXT) {
            val generation = synchronized(coreStartStopLock) {
                if ((commandGeneration != null && commandGeneration != connectToken.get()) || !connectedForAction()) {
                    return ignoreStaleCommand(intent, startId)
                }
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
                    if (!ownsGeneration(generation)) return@launch
                    Failover.markFailed(current)
                    val next = Failover.findReplacement(applicationContext, current)
                    coroutineContext.ensureActive()
                    if (!ownsGeneration(generation) || !Repo.tunnel.value.isActive) return@launch
                    if (next != null) {
                        connect(next, generation, isFailover = true, requestStartId = startId)
                    } else {
                        Failover.markWorking(current)
                        preflightFailed("سرور جایگزین تأییدشده‌ای پیدا نشد", generation, startId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    preflightFailed("بررسی سرور جایگزین ناموفق بود", generation, startId)
                }
            }
            return START_STICKY
        }

        if (action == ACTION_RETEST) {
            val generation = synchronized(coreStartStopLock) {
                if ((commandGeneration != null && commandGeneration != connectToken.get()) || !connectedForAction()) {
                    return ignoreStaleCommand(intent, startId)
                }
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
                    if (!ownsGeneration(generation)) return@launch
                    StartupMaintenance.cancelAll()
                    val id = currentServer?.id ?: Repo.tunnel.value.serverId
                    val server = Repo.serverById(id)?.copy()
                    if (server != null) {
                        val result = ConnectionTestBarrier.tests {
                            ProbePlanner.verify(server, Repo.settings.value.copy(), ir.v2max.app.net.HealthScanner::realDelay)
                        }
                        coroutineContext.ensureActive()
                        if (!ownsGeneration(generation) || Repo.tunnel.value.status != TunnelStatus.CONNECTED ||
                            !running || !coreRunning() || currentServer?.id != id ||
                            Repo.serverById(id)?.fingerprint() != server.fingerprint()) {
                            resumeMonitorIfCurrent(generation)
                            return@launch
                        }
                        if (result == null) {
                            if (!rotate("تست مجدد ناموفق بود", generation, startId)) resumeMonitorIfCurrent(generation)
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
                    preflightFailed("تست مجدد ناموفق بود", generation, startId)
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
            connect(selection, acceptedGeneration, requestStartId = startId)
            return START_STICKY
        }
        if (!ticket.isNullOrBlank()) {
            // An old request must neither reconnect after Disconnect nor cancel
            // another connection already being established.
            if (previous.status == TunnelStatus.CONNECTING &&
                (connectJob?.isActive == true || preflightJob?.isActive == true)) {
                publishIfCurrent(previous, acceptedGeneration)
            } else preflightFailed("نتیجه‌ی تست لغو شد یا اعتبار آن پایان یافت؛ دوباره تلاش کنید", acceptedGeneration, startId)
            return if (previous.isActive) START_STICKY else START_NOT_STICKY
        }
        if (Repo.servers.value.isEmpty()) {
            preflightFailed("هیچ کانفیگی برای بررسی وجود ندارد", acceptedGeneration, startId)
            return START_NOT_STICKY
        }

        val generation = synchronized(coreStartStopLock) {
            if (!ownsGeneration(acceptedGeneration)) return START_STICKY
            preflightJob?.cancel()
            connectJob?.cancel()
            actionJob?.cancel()
            monitorJob?.cancel()
            connectToken.incrementAndGet()
        }
        publishIfCurrent(Repo.tunnel.value.copy(message = "اجرای تست‌های پیش از اتصال…"), generation)
        preflightJob = scope.launch {
            try {
                if (!ownsGeneration(generation)) return@launch
                StartupMaintenance.cancelAll()
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
                if (best != null) connect(best, generation, requestStartId = startId)
                else preflightFailed(Repo.scan.value.message.ifBlank { "هیچ سرور تأییدشده‌ای پیدا نشد" }, generation, startId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preflightFailed("تست پیش از اتصال ناموفق بود", generation, startId)
            }
        }
        return START_STICKY
    }

    private fun ignoreStaleCommand(intent: Intent?, startId: Int): Int = synchronized(coreStartStopLock) {
        // A delayed intent must not undo a newer action. A stale foreground
        // start still needs promotion (or prompt shutdown) on Android O+.
        PreflightTickets.take(intent?.getStringExtra(EXTRA_PREFLIGHT_TICKET))
        if (!serviceOwner.isOwner(serviceInstance)) {
            // The newer instance owns the global core and notification. Stop
            // only this Android service instance, not its foreground state.
            stopSelf(startId)
            return@synchronized START_NOT_STICKY
        }
        if (!isForeground && !running && (intent == null || intent.action == ACTION_START)) {
            startForegroundCompat()
            isForeground = true
        }
        // The recovery job is published under this same lock; without it an old
        // command could observe the brief gap before the new job is assigned.
        if (!running && preflightJob?.isActive != true && connectJob?.isActive != true &&
            actionJob?.isActive != true && monitorJob?.isActive != true) {
            stopForegroundCompat()
            stopSelf(startId)
            return@synchronized START_NOT_STICKY
        }
        START_STICKY
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
        val wasOwner = synchronized(coreStartStopLock) {
            val ownsService = serviceOwner.release(serviceInstance)
            cancelConnectedSound(cancelTestCue = ownsService)
            if (ownsService) {
                connectToken.incrementAndGet()
                resourceOwner.clear()
                XrayCore.stop()
                closeTun()
                running = false
                activeIdentity = null
                val state = Repo.tunnel.value
                if (state.killSwitchRetained) {
                    // Once the service is destroyed its retained descriptor is
                    // gone; do not keep advertising an active Kill Switch.
                    Repo.tunnel.value = state.copy(
                        status = if (state.status == TunnelStatus.ERROR) TunnelStatus.ERROR else TunnelStatus.DISCONNECTED,
                        killSwitchRetained = false,
                        message = "سرویس VPN متوقف شد؛ اینترنت ممکن است مستقیم باشد"
                    )
                } else if (state.status != TunnelStatus.DISCONNECTED && state.status != TunnelStatus.ERROR) {
                    Repo.tunnel.value = TunnelUiState(status = TunnelStatus.DISCONNECTED)
                }
            } else {
                // A later service instance owns the process-global core. The old
                // instance may close only its own descriptor, never the new VPN.
                closeTun()
            }
            ownsService
        }
        // Ownership is invalidated before closing audio, so a last successful
        // worker cannot enqueue a new sound after teardown has started.
        closeConnectedSound(cancelTestCue = wasOwner)
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- connecting

    private fun connect(
        selection: VerifiedSelection,
        expectedGeneration: Int,
        isFailover: Boolean = false,
        requestStartId: Int
    ): Boolean = synchronized(coreStartStopLock) {
        // Check-and-advance as one operation; an old failover may finish
        // between a newer manual action and delivery of its service intent.
        if (!ownsGeneration(expectedGeneration)) return@synchronized false
        // A replacement takes precedence over housekeeping. The connection gate
        // below still waits until any in-flight native probes have returned.
        StartupMaintenance.cancelAll()
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
                if (ownsGeneration(token)) doConnect(selection, token, isFailover, requestStartId)
            }
        }
        true
    }

    private class PreflightChanged : IllegalStateException()

    private suspend fun doConnect(selection: VerifiedSelection, token: Int, isFailover: Boolean, requestStartId: Int) {
        var replacedTunnel = false
        try {
            ConnectionTestBarrier.connection {
                val server = selection.server()
                val baseSettings = Repo.settings.value.copy()
                var settings = baseSettings.copy(
                    fragment = server.testedFragment,
                    fragmentProfile = server.testedFragmentProfile,
                    balancer = false
                )
                // Prepare both the Xray configuration and Android's per-app/DNS
                // rules before interrupting a live tunnel. Builder configuration
                // does not establish an interface; a rejected rule discards it.
                val initialConfig = ConfigBuilder.vpnConfig(server, settings, listOf(server))
                val preparedTun = prepareTun(settings)
                synchronized(coreStartStopLock) {
                    if (!ownsGeneration(token)) throw CancellationException("superseded")
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
                    cancelConnectedSound()
                    running = false
                    activeIdentity = null
                    XrayCore.stop()
                    closeTun()
                    currentServer = server
                    Repo.setSelected(server.id)
                    if (isFailover) {
                        Log.i(TAG, "failover: connecting to a verified replacement")
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
                    if (!ownsGeneration(token)) throw CancellationException("superseded")
                    val created = establishTun(preparedTun)
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
                    if (!ownsGeneration(token)) throw CancellationException("superseded")
                    val config = prebuilt ?: ConfigBuilder.vpnConfig(server, currentSettings, pool)
                    synchronized(coreStartStopLock) {
                        if (!ownsGeneration(token)) throw CancellationException("superseded")
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
                        XrayCore.log(XrayCore.Event.GEO_FALLBACK)
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
                        XrayCore.log(XrayCore.Event.POOL_FALLBACK)
                        verified = startAndVerify(settings, listOf(server))
                        Repo.poolSize.value = 1
                    }
                }
                coroutineContext.ensureActive()
                if (!ownsGeneration(token)) throw CancellationException("superseded")
                Repo.setVerifiedResult(server.id, ProbeResult(
                    primaryVerification.first.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                    primaryVerification.second, settings.fragment, settings.fragmentProfile
                ), clearOtherTargets = false, expectedFingerprint = server.fingerprint())
                Repo.persistNow()

                synchronized(coreStartStopLock) {
                    // A stop/new action may arrive while persistence or the native
                    // post-start probe is running. Never publish its stale result.
                    if (!ownsGeneration(token)) throw CancellationException("superseded")
                    // A geo/balancer fallback may differ from the user's request;
                    // remember what was actually started, not the requested policy.
                    activeIdentity = identity(server, settings)
                    running = true
                    connectedAt = System.currentTimeMillis()
                    connectedAtNanos = System.nanoTime()
                    totalUp = 0
                    totalDown = 0
                    if (!isFailover) {
                        failoverAttempts = 0
                        recoveryAttempt = 0
                    }
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
                    // The callback is only queued here. Media preparation and
                    // playback never run under the VPN lifecycle lock.
                    announceConnected(token, server.id)
                }
                // Persisting a large server list must not hold the lifecycle gate.
                if (ownsGeneration(token)) runCatching {
                    Repo.recordOutcome(server.id, true)
                    SmartConnect.rememberWorkingNetwork(Repo.serverById(server.id) ?: server)
                }.onFailure { Log.w(TAG, "could not persist connection statistics") }
            }
        } catch (e: CancellationException) {
            if (replacedTunnel) releaseOwnedResources(token)
            throw e
        } catch (e: PerAppRouting.InvalidRules) {
            val message = when (e.reason) {
                PerAppRouting.Failure.EMPTY_SELECTION ->
                    "برای حالت «فقط انتخاب‌شده‌ها» دست‌کم یک برنامه انتخاب کنید"
                PerAppRouting.Failure.RULE_UNAVAILABLE ->
                    "قوانین برنامه‌به‌برنامه اعمال نشد؛ فهرست برنامه‌های انتخاب‌شده را بررسی کنید"
            }
            // Preparation failed before touching the previous VPN. Its old rules,
            // not the newly requested ones, remain in force if it is still alive.
            preflightFailed(message, token, requestStartId, "اتصال فعلی با تنظیمات قبلی حفظ شد")
        } catch (e: PreflightChanged) {
            val message = "کانفیگ، شبکه یا تنظیمات پس از تست تغییر کرد"
            if (isFailover && !running && Repo.settings.value.smartFailover) beginRecovery(message, token, requestStartId)
            else preflightFailed("$message؛ دوباره تلاش کنید", token, requestStartId)
        } catch (_: Exception) {
            if (!ownsGeneration(token)) {
                if (replacedTunnel) releaseOwnedResources(token)
                return
            }
            Log.e(TAG, "connect failed")
            if (!replacedTunnel) {
                // Configuration construction failed before we touched the old VPN.
                preflightFailed("آماده‌سازی اتصال جدید ناموفق بود", token, requestStartId)
                return
            }

            // اگر اتصال اولیه هم شکست خورد، به‌جای تسلیم شدن، سرور بعدی امتحان می‌شود
            if (!isFailover && Repo.settings.value.smartFailover && failoverAttempts < MAX_FAILOVERS) {
                if (rotate("اتصال با این سرور برقرار نشد", token, requestStartId)) return
            }

            // اگر همین تلاشِ جایگزینی شکست خورد، سرور بعدی را امتحان می‌کنیم
            if (isFailover && Repo.settings.value.smartFailover && failoverAttempts < MAX_FAILOVERS) {
                val failedId = currentServer?.id.orEmpty()
                Failover.markFailed(failedId)
                val next = findReplacementSafely(failedId)
                coroutineContext.ensureActive()
                if (next != null && connect(next, token, isFailover = true, requestStartId = requestStartId)) {
                    Log.i(TAG, "failover retry #$failoverAttempts")
                    return
                }
            }

            if (ownsGeneration(token)) currentServer?.let { server ->
                runCatching { Repo.recordOutcome(server.id, false) }
                    .onFailure { Log.w(TAG, "could not persist failed connection statistics") }
            }
            val message = "اتصال برقرار نشد؛ تنظیمات و شبکه را بررسی کنید"
            if (Repo.settings.value.smartFailover) beginRecovery(message, token, requestStartId)
            else fail(message, token, requestStartId)
        }
    }

    /** Configure a new builder without establishing it or replacing a live VPN. */
    private fun prepareTun(settings: AppSettings): Builder {
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

        // Android's allow/disallow lists are mutually exclusive. A missing or
        // rejected package must never silently turn a restricted list into all apps.
        applyPerAppRules(builder, settings)
        return builder
    }

    private fun establishTun(builder: Builder): ParcelFileDescriptor? = try {
        builder.establish()
    } catch (_: Throwable) {
        Log.e(TAG, "establish failed")
        null
    }

    /** Configure every rule or discard this unestablished builder. */
    private fun applyPerAppRules(builder: Builder, settings: AppSettings) {
        val mode = when (settings.perAppMode) {
            ir.v2max.app.data.PerAppMode.OFF -> CorePerAppMode.OFF
            ir.v2max.app.data.PerAppMode.ONLY_SELECTED -> CorePerAppMode.ONLY_SELECTED
            ir.v2max.app.data.PerAppMode.EXCEPT_SELECTED -> CorePerAppMode.EXCEPT_SELECTED
        }
        // Some Android versions' Builder.verifyApp ignores a null result from
        // the package service even though its API documents NameNotFoundException.
        // The public PackageManager method reliably rejects an absent package.
        @Suppress("DEPRECATION")
        fun installed(pkg: String) = packageManager.getApplicationInfo(pkg, 0)
        PerAppRouting.apply(
            mode = mode,
            requestedPackages = settings.perAppPackages,
            vpnPackage = packageName,
            allow = { pkg ->
                // Android routes by UID. Allowing another package sharing this
                // app's UID would route our own Xray sockets back into the VPN.
                val app = installed(pkg)
                check(app.uid != applicationInfo.uid)
                builder.addAllowedApplication(pkg)
            },
            disallow = { pkg ->
                installed(pkg)
                builder.addDisallowedApplication(pkg)
            },
        )
    }

    // --------------------------------------------------------------- monitor

    private fun publishIfCurrent(state: TunnelUiState, generation: Int): Boolean = synchronized(coreStartStopLock) {
        if (!ownsGeneration(generation)) return@synchronized false
        publish(state)
        true
    }

    private fun resumeMonitorIfCurrent(generation: Int) = synchronized(coreStartStopLock) {
        // The core may have stopped during a retest. Resume monitoring so it
        // can detect the failure and start recovery instead of going silent.
        if (ownsGeneration(generation) && running && tun != null) startMonitor()
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        val monitorGeneration = connectToken.get()
        val monitorStartId = latestStartId
        monitorJob = scope.launch {
            var lastTick = System.currentTimeMillis()
            var lastProbe = System.currentTimeMillis()
            var probeIndex = 0
            var failures = 0

            while (isActive) {
                delay(1000)
                if (!ownsGeneration(monitorGeneration)) return@launch
                val state = Repo.tunnel.value
                if (state.status != TunnelStatus.CONNECTED) break

                // A stopped core is detectable without a network probe. Check on
                // every monitor tick instead of waiting up to the battery-saver
                // probe interval before starting recovery.
                if (withContext(Dispatchers.IO) { !coreRunning() }) {
                    // Replacement scans can take a long time. Stop reporting a
                    // dead core as CONNECTED before starting any of that work,
                    // while retaining the current interface until a decision.
                    val stopped = synchronized(coreStartStopLock) {
                        if (!ownsGeneration(monitorGeneration)) return@launch
                        if (coreRunning()) false else {
                            cancelConnectedSound()
                            running = false
                            activeIdentity = null
                            publish(Repo.tunnel.value.copy(
                                status = TunnelStatus.CONNECTING,
                                downSpeed = 0,
                                upSpeed = 0,
                                killSwitchRetained = Repo.settings.value.killSwitch && tun != null,
                                message = "هسته متوقف شد؛ در حال بررسی بازیابی…"
                            ))
                            true
                        }
                    }
                    if (!stopped) continue
                    Log.w(TAG, "core stopped unexpectedly")
                    if (Repo.settings.value.smartFailover) {
                        if (rotate("بازسازی هسته", monitorGeneration, monitorStartId)) return@launch
                        if (Repo.settings.value.smartFailover) {
                            beginRecovery("هسته متوقف شد", monitorGeneration, monitorStartId)
                            return@launch
                        }
                    }
                    fail("اتصال قطع شد", monitorGeneration, monitorStartId)
                    return@launch
                }

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
                        if (stopTunnel(startId = monitorStartId, expectedGeneration = monitorGeneration)) {
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

                // Use the destination that actually worked and a second independent
                // endpoint before treating a temporary destination outage as a tunnel failure.
                val options = Repo.settings.value
                val primary = Repo.serverById(state.serverId)?.testedUrl.orEmpty()
                val multiUrls = if (options.multiPing) ir.v2max.app.data.PingTargets.ALL.map { it.url } else emptyList()
                val rotated = if (multiUrls.isNotEmpty()) multiUrls[probeIndex++ % multiUrls.size] else primary
                val urls = (listOf(rotated, primary) + ProbePlanner.testUrls(options)).filter { it.isNotBlank() }.distinct()
                val ping = ConnectionTestBarrier.monitoring {
                    var measured = -1L
                    for (url in urls) {
                        coroutineContext.ensureActive()
                        measured = withContext(Dispatchers.IO) { XrayCore.measureDelay(url) }
                        if (measured > 0) break
                    }
                    measured
                }
                if (ping > 0) {
                    if (failures > 0) Log.i(TAG, "connection recovered after $failures failures")
                    failures = 0
                    if (!publishIfCurrent(Repo.tunnel.value.copy(ping = ping.toInt(), message = "متصل"), monitorGeneration))
                        return@launch
                    if (System.nanoTime() - connectedAtNanos >= STABLE_RECOVERY_RESET_NANOS) {
                        synchronized(coreStartStopLock) {
                            if (ownsGeneration(monitorGeneration)) {
                                recoveryAttempt = 0
                                failoverAttempts = 0
                            }
                        }
                    }
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
                    if (rotate("سرور فعلی پاسخ نمی‌دهد", monitorGeneration, monitorStartId)) return@launch
                } else if (failures >= MAX_FAIL_RESILIENCE) {
                    // تلاش نهایی: کاوش گسترده؛ اگر چیزی نبود اتصال را قطع نمی‌کنیم
                    val replacement = Failover.probeAsync(applicationContext, limit = 120, currentId = currentServer?.id)
                        ?: Failover.findReplacement(applicationContext, currentServer?.id)
                    coroutineContext.ensureActive()
                    if (replacement != null && connect(replacement, monitorGeneration, isFailover = true, requestStartId = monitorStartId)) {
                        Log.i(TAG, "hard switch to a verified replacement")
                        return@launch
                    }
                    if (!ownsGeneration(monitorGeneration)) return@launch
                    failures = threshold
                }
            }
        }
    }

    /** Keep retrying after the core actually stops, without pretending traffic is protected. */
    private fun beginRecovery(message: String, generation: Int, requestStartId: Int) = synchronized(coreStartStopLock) {
        if (!ownsGeneration(generation)) return@synchronized
        cancelConnectedSound()
        monitorJob?.cancel()
        resourceOwner.clear()
        XrayCore.stop()
        val blocked = Repo.settings.value.killSwitch && tun != null
        if (!blocked) closeTun()
        running = false
        activeIdentity = null
        Repo.poolSize.value = 0
        failoverAttempts = 0
        publish(Repo.tunnel.value.copy(
            status = TunnelStatus.CONNECTING,
            downSpeed = 0,
            upSpeed = 0,
            killSwitchRetained = blocked,
            message = if (blocked) "$message؛ رابط VPN حفظ شد و اتصال دوباره بررسی می‌شود"
                else "$message؛ VPN قطع است و اینترنت ممکن است مستقیم باشد. در حال تلاش دوباره…"
        ))
        val startingAttempt = recoveryAttempt
        monitorJob = scope.launch {
            val next = RecoveryLoop.awaitVerified(
                isCurrent = { ownsGeneration(generation) && Repo.settings.value.smartFailover },
                initialAttempt = startingAttempt,
                onAttempt = { nextAttempt ->
                    synchronized(coreStartStopLock) {
                        if (ownsGeneration(generation)) recoveryAttempt = nextAttempt
                    }
                },
                findVerified = {
                    // Previously failed profiles may work again on a recovered
                    // network. Re-test them; never reuse a cached success.
                    Failover.reset()
                    findReplacementSafely(null)
                }
            )
            coroutineContext.ensureActive()
            if (next != null) {
                connect(next, generation, isFailover = true, requestStartId = requestStartId)
            } else if (ownsGeneration(generation) && !Repo.settings.value.smartFailover) {
                fail("بازیابی خودکار خاموش شد", generation, requestStartId)
            }
        }
    }

    private suspend fun findReplacementSafely(currentId: String?): VerifiedSelection? = try {
        Failover.findReplacement(applicationContext, currentId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Log.w(TAG, "replacement search failed")
        null
    }

    /** جابه‌جایی تنها به سروری که تست واقعی را گذرانده است. */
    private suspend fun rotate(reason: String, generation: Int, requestStartId: Int): Boolean {
        if (!ownsGeneration(generation)) return false
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

        val next = findReplacementSafely(currentId) ?: return false
        coroutineContext.ensureActive()
        if (!connect(next, generation, isFailover = true, requestStartId = requestStartId)) return false
        Log.i(TAG, "failover #$failoverAttempts ($reason)")
        return true
    }

    // -------------------------------------------------------------- teardown

    private fun stopTunnel(startId: Int? = null, expectedGeneration: Int? = null): Boolean {
        val requestStartId = startId ?: latestStartId
        val stopped = synchronized(coreStartStopLock) {
            if (!serviceOwner.isOwner(serviceInstance) ||
                (expectedGeneration != null && expectedGeneration != connectToken.get())) return@synchronized false
            cancelConnectedSound()
            connectToken.incrementAndGet()
            StartupMaintenance.cancelAll()
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
            if (requestStartId > 0) stopSelf(requestStartId) else stopSelf()
        }
        return stopped
    }

    /** A failed/stale preflight must not tear down an already working VPN. */
    private fun preflightFailed(
        message: String,
        generation: Int,
        requestStartId: Int,
        preservedMessage: String = "اتصال فعلی حفظ شد"
    ) = synchronized(coreStartStopLock) {
        if (!ownsGeneration(generation)) return@synchronized
        val active = currentServer
        if (running && coreRunning() && tun != null && active != null) {
            publish(Repo.tunnel.value.copy(
                status = TunnelStatus.CONNECTED, serverId = active.id,
                serverName = active.displayName(), serverProto = active.proto.label,
                message = "$message؛ $preservedMessage"
            ))
            startMonitor()
        } else if (running && active != null && Repo.settings.value.smartFailover) {
            // The live core may have stopped while another preflight was in
            // progress. Resume recovery instead of leaving monitoring canceled.
            beginRecovery(message, generation, requestStartId)
        } else {
            fail(message, generation, requestStartId)
        }
    }

    private fun fail(message: String, generation: Int, requestStartId: Int) = synchronized(coreStartStopLock) {
        if (!ownsGeneration(generation)) return@synchronized
        cancelConnectedSound()
        running = false
        activeIdentity = null
        resourceOwner.clear()
        XrayCore.stop()

        // Keep the VPN interface only for included applications. This is not
        // Android's system-wide always-on/lockdown mode and ends with the service.
        val killSwitch = Repo.settings.value.killSwitch && tun != null
        if (killSwitch) {
            Repo.tunnel.value = Repo.tunnel.value.copy(
                status = TunnelStatus.ERROR,
                killSwitchRetained = true,
                message = "رابط VPN حفظ شد (Kill Switch) — $message",
                downSpeed = 0,
                upSpeed = 0
            )
            Notifier.blocked(this, message)
            return@synchronized
        }

        closeTun()
        Repo.tunnel.value = Repo.tunnel.value.copy(
            status = TunnelStatus.ERROR,
            killSwitchRetained = false,
            message = message,
            downSpeed = 0,
            upSpeed = 0
        )
        Notifier.update(this, Repo.tunnel.value, false, Repo.settings.value.hideNotificationContent)
        stopForegroundCompat()
        // Do not let a late failure stop a newer service start already queued by Android.
        if (requestStartId > 0) stopSelf(requestStartId) else stopSelf()
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
        } catch (_: Throwable) {
            Log.e(TAG, "startForeground failed")
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
        } catch (_: Throwable) {
            Log.w(TAG, "stopForeground failed")
        }
        isForeground = false
    }
}
