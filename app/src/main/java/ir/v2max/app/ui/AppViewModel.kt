package ir.v2max.app.ui

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.core.TunnelController
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.LinkParser
import ir.v2max.app.net.UpdateChecker
import ir.v2max.app.net.UpdateCoordinator
import ir.v2max.app.net.SubscriptionFetcher
import ir.v2max.app.net.StartupMaintenance
import ir.v2max.app.net.SubscriptionPolicy
import ir.v2max.app.net.SubscriptionRefresh
import ir.v2max.app.net.SubscriptionSyncGate
import ir.v2max.core.format.Format
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val servers = Repo.servers
    val subs = Repo.subs
    val settings = Repo.settings
    val selectedId = Repo.selectedId
    val tunnel = Repo.tunnel
    val scan = Repo.scan
    val sync = Repo.sync
    val network = Repo.network
    val update = Repo.update

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private sealed class VpnAction {
        data class Server(val id: String) : VpnAction()
        data class Smart(
            val subscriptionId: String?, val automaticOnOpen: Boolean = false, val startupToken: Long? = null
        ) : VpnAction()
        object Quick : VpnAction()
        object Restart : VpnAction()
    }

    private val vpnPermissions = VpnPermissionQueue<VpnAction>()
    private val _vpnPermissionState = MutableStateFlow(vpnPermissions.snapshot())
    internal val vpnPermissionState: StateFlow<VpnPermissionState> = _vpnPermissionState.asStateFlow()
    private val _notificationPermissionInFlight = MutableStateFlow(false)
    internal val notificationPermissionInFlight: StateFlow<Boolean> = _notificationPermissionInFlight.asStateFlow()
    private var notificationPermissionAttempted = false
    internal var lastVpnPermissionLaunchError: String = ""
        private set
    private var startupJob: Job? = null
    private var editingServerId: String? = null
    private val dataJobs = java.util.Collections.synchronizedSet(mutableSetOf<Job>())
    private val updateDownloadLock = Any()
    private var updateDownloadActive = false
    private var updateDownloadAttempt = 0L

    private fun cancelStartupForUserAction() {
        cancelStartup()
        clearPendingVpnRequest()
        // A newer connection action supersedes queued scans and service failover,
        // but must not tear down an already working tunnel.
        SmartConnect.cancelScan()
        if (Repo.tunnel.value.isActive) TunnelController.cancelPreflight(getApplication())
    }

    /** Importing data does not cancel an unrelated VPN or its recovery. */
    private fun cancelStartup() {
        // Invalidate and cancel all housekeeping (including a periodic or manual
        // refresh) so a newer user action or recovery does not wait behind it.
        // An unrelated live VPN itself is not stopped.
        StartupMaintenance.cancelAll()
        startupJob?.cancel()
        startupJob = null
    }

    private fun requireWritableData(): Boolean {
        if (!Repo.isStorageReadOnly()) return true
        showMessage("داده‌ها فقط‌خواندنی‌اند؛ پشتیبان معتبر را بازیابی یا داده‌ها را بازنشانی کنید")
        return false
    }

    private fun trackDataJob(job: Job) {
        dataJobs.add(job)
        job.invokeOnCompletion { dataJobs.remove(job) }
    }

    private fun cancelDataJobs() {
        val jobs = synchronized(dataJobs) { dataJobs.toList() }
        jobs.forEach { it.cancel() }
    }

    /**
     * هیچ خطای پیش‌بینی‌نشده‌ای در کارهای پس‌زمینه نباید برنامه را ببندد.
     * خطا فقط ثبت می‌شود و در صورت لزوم به کاربر پیام داده می‌شود.
     */
    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        ir.v2max.app.util.CrashLog.record(throwable, "viewModel")
        runCatching { _busy.value = false }
        _message.value = "خطای غیرمنتظره رخ داد؛ دوباره تلاش کنید"
    }

    init {
        // Capture before scheduling: a service action can supersede startup
        // before its coroutine has even begun or registered with maintenance.
        val startupToken = Repo.startupHealthToken()
        startupJob = viewModelScope.launch(errorHandler) { startup(startupToken) }
    }

    /** Refresh, then protocol health, then the separate full connection preflight. */
    private suspend fun startup(token: Long) {
        if (!Repo.startupHealthIsCurrent(token)) return
        val app = getApplication<Application>()
        val settings = Repo.settings.value
        if (ir.v2max.app.util.CrashLog.hasRecorded()) {
            showMessage("یک خطای محلی ثبت شده است؛ خلاصهٔ خصوصی در تنظیمات موجود است")
        }
        if (settings.useFreeSources) runCatching { Repo.ensureBuiltInSources(requireEnabled = true) }
        if (settings.autoUpdateCheck) checkUpdates(manual = false)

        if (!Repo.startupHealthIsCurrent(token)) return
        val permit = Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC)
        if (permit != null && Repo.subs.value.isNotEmpty()) {
            // Attempt all current sources, even recently updated ones. User
            // actions and service recovery can cancel this entire phase.
            val complete = StartupMaintenance.track(token) {
                SubscriptionSyncGate.mutex.withLock {
                    Repo.sync.value = ir.v2max.app.data.SyncState(running = true, message = "آماده‌سازی اشتراک‌ها…")
                    try {
                        // A service action may have invalidated startup just
                        // before this job registered or acquired the gate.
                        if (!Repo.startupHealthIsCurrent(token) || !Repo.automaticRefreshIsCurrent(permit)) return@withLock false
                        val refreshed = refreshLocked(Repo.subs.value, permit.dataEpoch, token, permit)
                        currentCoroutineContext().ensureActive()
                        if (!Repo.startupHealthIsCurrent(token) || !Repo.automaticRefreshIsCurrent(permit)) return@withLock false
                        if (refreshed.sources.isEmpty()) {
                            showMessage("به‌روزرسانی اشتراک‌ها انجام نشد؛ فهرست قبلی حفظ شد و اتصال خودکار آغاز نشد")
                            return@withLock false
                        }
                        Repo.sync.value = ir.v2max.app.data.SyncState(running = true, message = "سنجش سرورهای اشتراک…")
                        val checked = try {
                            withContext(Dispatchers.IO) {
                                StartupMaintenance.finish(app, refreshed.sources, permit.dataEpoch, permit, token) { phase, done, total ->
                                    Repo.sync.value = Repo.sync.value.copy(
                                        done = done, total = total,
                                        message = if (phase.startsWith("پیش")) "پیش‌بررسی سرورهای اشتراک…"
                                            else "سنجش واقعی سرورهای اشتراک…"
                                    )
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            showMessage("سنجش اشتراک‌ها کامل نشد؛ فهرست حفظ شد و اتصال خودکار آغاز نشد")
                            return@withLock false
                        }
                        currentCoroutineContext().ensureActive()
                        when (checked.status) {
                            StartupMaintenance.Status.COMPLETE -> {
                                when {
                                    checked.health.healthy == 0 && checked.health.unknownSources > 0 -> showMessage(
                                        "هیچ تستی موفق نشد و نتیجهٔ برخی بررسی‌ها نامشخص بود؛ کانفیگ‌ها حفظ شدند"
                                    )
                                    checked.health.healthy == 0 -> showMessage(
                                        "هیچ سروری در این نوبت پاسخ نداد؛ مشکل ممکن است از شبکه یا مقصد تست باشد و کانفیگ‌ها حفظ شدند"
                                    )
                                    checked.health.removed > 0 -> showMessage(
                                        "${Format.number(checked.health.removed)} کانفیگ اشتراک در این بررسی پاسخ نداد و کنار گذاشته شد"
                                    )
                                    checked.health.unknownSources > 0 -> showMessage(
                                        "سنجش برخی اشتراک‌ها کامل نبود؛ کانفیگ‌های نامشخص حفظ شدند"
                                    )
                                }
                                true
                            }
                            StartupMaintenance.Status.NO_NETWORK -> {
                                showMessage("شبکه‌ای برای سنجش پیدا نشد؛ کانفیگ‌ها حفظ شدند")
                                false
                            }
                            StartupMaintenance.Status.NO_PROFILES -> {
                                showMessage("کانفیگ‌های دریافت‌شده برای سنجش در دسترس نبودند؛ اتصال خودکار آغاز نشد")
                                false
                            }
                            StartupMaintenance.Status.INCOMPLETE, StartupMaintenance.Status.CHANGED -> {
                                showMessage("سنجش کامل نشد یا شبکه و تنظیمات تغییر کردند؛ اتصال خودکار آغاز نشد")
                                false
                            }
                        }
                    } finally {
                        // The indicator is owned only while holding the gate;
                        // a canceled pass cannot hide a newer manual refresh.
                        Repo.sync.value = Repo.sync.value.copy(running = false)
                    }
                }
            }
            if (!complete) return
        }

        currentCoroutineContext().ensureActive()
        if (!Repo.startupHealthIsCurrent(token) || !Repo.settings.value.autoConnectOnOpen ||
            Repo.servers.value.isEmpty() || !autoConnectAllowed(app)) return
        // Never restart a working or recovering connection while opening the UI.
        if (Repo.tunnel.value.isActive || Repo.scan.value.running) return
        val needsPermission = vpnPermissionRequired() ?: return
        if (needsPermission || vpnPermissions.snapshot().inFlightId != null) {
            if (Repo.settings.value.autoConnectOnOpen) {
                requestSmartConnectInternal(automaticOnOpen = true, startupToken = token)
            }
            return
        }
        // Keep automatic preflight cancellable by a newer service action too.
        StartupMaintenance.track(token) {
            SmartConnect.scanAndConnect(app, autoConnect = true, limit = 450, preferFastest = true)
        }
    }

    /** اگر «فقط وای‌فای» روشن باشد و شبکه داده باشد، اتصال خودکار انجام نمی‌شود */
    private fun autoConnectAllowed(app: android.app.Application): Boolean {
        if (!Repo.settings.value.wifiOnly) return true
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
        if (!onWifi) showMessage("اتصال خودکار فقط روی وای‌فای فعال است")
        return onWifi
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = ""
    }

    // --------------------------------------------------------------- connect

    fun toggleConnection() {
        // Capture the state before cancellation can asynchronously update it.
        val wasScanning = Repo.scan.value.running
        val wasConnected = Repo.tunnel.value.status == ir.v2max.app.data.TunnelStatus.CONNECTED
        cancelStartupForUserAction()
        if (wasScanning) {
            if (wasConnected) {
                // Cancellation above stops the search, not the working VPN underneath it.
                showMessage("بررسی لغو شد؛ اتصال فعلی برقرار است")
            } else {
                if (Repo.tunnel.value.isActive) TunnelController.stop(getApplication())
                showMessage("تست اتصال لغو شد")
            }
        } else if (Repo.tunnel.value.isActive) {
            TunnelController.stop(getApplication())
        } else {
            quickConnect()
        }
    }

    fun disconnectTunnel() {
        cancelStartupForUserAction()
        SmartConnect.cancelScan()
        TunnelController.stop(getApplication())
    }

    /** A row or its explicit connect control selects and tests that same profile. */
    fun requestConnect(serverId: String?) {
        cancelStartupForUserAction()
        if (serverId == null) {
            if (hasServers()) runOrRequestVpn(VpnAction.Quick)
        } else if (Repo.serverById(serverId) == null) {
            showMessage("سرور انتخاب‌شده دیگر در فهرست نیست؛ دوباره انتخاب کنید")
        } else {
            Repo.setSelected(serverId)
            if (Repo.isStorageReadOnly()) showMessage("انتخاب سرور تا بستن برنامه موقت است؛ داده‌ها فقط‌خواندنی‌اند")
            runOrRequestVpn(VpnAction.Server(serverId))
        }
    }

    fun requestSmartConnect(subscriptionId: String? = null) {
        cancelStartupForUserAction()
        requestSmartConnectInternal(subscriptionId)
    }

    private fun requestSmartConnectInternal(
        subscriptionId: String? = null, automaticOnOpen: Boolean = false, startupToken: Long? = null
    ) {
        if (hasServers(subscriptionId)) runOrRequestVpn(VpnAction.Smart(subscriptionId, automaticOnOpen, startupToken))
    }

    private fun hasServers(subscriptionId: String? = null): Boolean {
        if (Repo.servers.value.any { subscriptionId == null || it.subId == subscriptionId }) return true
        val text = "سروری برای اتصال وجود ندارد؛ ابتدا یک اشتراک یا کانفیگ اضافه کنید"
        Repo.scan.value = ir.v2max.app.data.ScanState(finished = true, message = text)
        showMessage(text)
        return false
    }

    /** Keep a permission failure visible after its short snackbar disappears. */
    private fun showVpnPermissionProblem(text: String) {
        if (!Repo.tunnel.value.isActive) {
            Repo.scan.value = ir.v2max.app.data.ScanState(finished = true, message = text)
        }
        showMessage(text)
    }

    /** Null means Android could not determine consent; never start a tunnel in that case. */
    private fun vpnPermissionRequired(): Boolean? = try {
        VpnService.prepare(getApplication<Application>()) != null
    } catch (_: Exception) {
        showVpnPermissionProblem("بررسی مجوز VPN ممکن نشد؛ محدودیت‌های دستگاه و VPNهای دیگر را بررسی کنید")
        null
    }

    private fun runOrRequestVpn(action: VpnAction) {
        if (!automaticActionIsCurrent(action)) return
        val needsPermission = vpnPermissionRequired() ?: return
        if (needsPermission || vpnPermissions.snapshot().inFlightId != null) {
            vpnPermissions.enqueue(action)
            _vpnPermissionState.value = vpnPermissions.snapshot()
        } else {
            executeVpnAction(action)
        }
    }

    private fun clearPendingVpnRequest() {
        vpnPermissions.cancel()
        _vpnPermissionState.value = vpnPermissions.snapshot()
    }

    private fun automaticActionIsCurrent(action: VpnAction): Boolean =
        action !is VpnAction.Smart || !action.automaticOnOpen ||
            (action.startupToken != null && Repo.startupHealthIsCurrent(action.startupToken) &&
                Repo.settings.value.autoConnectOnOpen && !Repo.tunnel.value.isActive)

    /** Claim before launching so recreation does not launch a second system dialog. */
    internal fun claimVpnPermissionRequest(): Boolean {
        vpnPermissions.cancelIf { !automaticActionIsCurrent(it) }
        val claimed = vpnPermissions.claim()
        if (claimed) lastVpnPermissionLaunchError = ""
        _vpnPermissionState.value = vpnPermissions.snapshot()
        return claimed
    }

    internal fun onVpnPermissionResult(granted: Boolean) {
        val action = vpnPermissions.complete()
        _vpnPermissionState.value = vpnPermissions.snapshot()
        // A canceled or superseded request must not consume this old dialog result.
        if (action == null || !automaticActionIsCurrent(action)) return
        if (!granted) {
            showVpnPermissionProblem("برای اتصال، اجازه‌ی ساخت VPN لازم است؛ اگر پنجره باز نشد، VPN همیشه‌روشن یا محدودیت دستگاه را بررسی کنید")
            return
        }
        when (vpnPermissionRequired()) {
            false -> executeVpnAction(action)
            true -> showVpnPermissionProblem("مجوز VPN فعال نشد؛ محدودیت‌های دستگاه و VPNهای دیگر را بررسی کنید")
            null -> Unit // vpnPermissionRequired already reported the failure
        }
    }

    internal fun onVpnPermissionLaunchFailure(error: Throwable) {
        // The system VPN intent contains no server credentials. Keep this only
        // in process memory for diagnostics, never in the user-facing message.
        lastVpnPermissionLaunchError = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(240)}"
        val action = vpnPermissions.complete()
        _vpnPermissionState.value = vpnPermissions.snapshot()
        if (action != null && automaticActionIsCurrent(action)) {
            showVpnPermissionProblem("پنجره‌ی مجوز VPN باز نشد؛ محدودیت‌های دستگاه و VPNهای دیگر را بررسی کنید")
        }
    }

    /** The optional notification prompt must not overlap the VPN consent dialog. */
    internal fun claimNotificationPermission(): Boolean {
        if (notificationPermissionAttempted || vpnPermissions.snapshot().inFlightId != null) return false
        notificationPermissionAttempted = true
        _notificationPermissionInFlight.value = true
        return true
    }

    internal fun onNotificationPermissionResult() {
        _notificationPermissionInFlight.value = false
    }

    private fun executeVpnAction(action: VpnAction) {
        when (action) {
            is VpnAction.Server -> viewModelScope.launch(errorHandler) {
                if (!SmartConnect.connectVerified(getApplication(), action.id)) {
                    showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
                }
            }
            is VpnAction.Smart -> {
                if (automaticActionIsCurrent(action)) {
                    launchSmartConnect(action.subscriptionId, action.automaticOnOpen, action.startupToken)
                }
            }
            VpnAction.Quick -> viewModelScope.launch(errorHandler) {
                if (!SmartConnect.quickConnect(getApplication())) {
                    showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
                }
            }
            VpnAction.Restart -> launchRestart()
        }
    }

    private fun launchSmartConnect(subscriptionId: String?, automaticOnOpen: Boolean = false, startupToken: Long? = null) {
        val job = viewModelScope.launch(errorHandler) {
            if (automaticOnOpen && (startupToken == null || !Repo.startupHealthIsCurrent(startupToken) ||
                    !Repo.settings.value.autoConnectOnOpen || Repo.tunnel.value.isActive)) return@launch
            suspend fun scan() = SmartConnect.scanAndConnect(
                getApplication(), autoConnect = true, subscriptionId = subscriptionId, preferFastest = true
            )
            val best = if (automaticOnOpen) StartupMaintenance.track(requireNotNull(startupToken)) { scan() } else scan()
            if (best == null) showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
        }
        if (automaticOnOpen) startupJob = job
    }

    fun quickConnect() {
        cancelStartupForUserAction()
        if (hasServers()) runOrRequestVpn(VpnAction.Quick)
    }

    // ----------------------------------------------------------------- tests

    fun testAll() {
        cancelStartupForUserAction()
        viewModelScope.launch(errorHandler) {
            SmartConnect.scanAndConnect(getApplication(), autoConnect = false, exhaustive = true)
        }
    }

    fun testServer(id: String) {
        cancelStartupForUserAction()
        viewModelScope.launch(errorHandler + Dispatchers.IO) {
            val server = Repo.serverById(id) ?: return@launch
            val settings = Repo.settings.value
            showMessage("در حال تست واقعی کانفیگ…")
            val result = HealthScanner.scan(
                context = getApplication(),
                servers = listOf(server),
                settings = settings,
                preferredId = id,
                onTcp = { cfg, ping -> Repo.setPretest(cfg.id, ping, cfg.fingerprint()) },
                onReal = { cfg, probe -> Repo.setVerifiedResult(cfg.id, probe, expectedFingerprint = cfg.fingerprint()) },
                onMulti = { cfg -> Repo.setMultiPing(cfg.id, cfg.googlePing, cfg.instagramPing, cfg.youtubePing, expectedFingerprint = cfg.fingerprint()) },
                onSpeed = { cfg, kbps -> Repo.setSpeed(cfg.id, kbps, persist = false, expectedFingerprint = cfg.fingerprint()) }
            )
            Repo.persistNow()
            showMessage(result.best?.let { "تست واقعی موفق بود: ${it.realPing}ms" } ?: result.message)
        }
    }

    /**
     * «ری‌استارت جستجو» از صفحه‌ی خانه:
     * نتایج قبلی پاک می‌شوند و کل چرخه (اسکن سه‌مرحله‌ای + انتخاب بهترین + اتصال) از نو اجرا می‌شود.
     * اگر VPN وصل باشد، اتصال تا پیدا شدن گزینه‌ی بهتر حفظ می‌شود.
     */
    fun restartScan() {
        cancelStartupForUserAction()
        if (hasServers()) runOrRequestVpn(VpnAction.Restart)
    }

    private fun launchRestart() {
        viewModelScope.launch(errorHandler) {
            Repo.scan.value = ir.v2max.app.data.ScanState(
                running = true,
                phase = "آماده‌سازی جستجوی دوباره",
                message = "نتایج قبلی پاک شد"
            )
            val best = SmartConnect.restart(getApplication(), autoConnect = true)
            if (best == null) showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
        }
    }

    // ---------------------------------------------------------- subscriptions

    fun addSubscription(name: String, url: String) {
        if (!requireWritableData()) return
        cancelStartup()
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) {
            showMessage("آدرس اشتراک را وارد کنید")
            return
        }
        val existing = Repo.subs.value.firstOrNull { it.url.trim() == cleanUrl }
        if (existing != null) {
            showMessage("این اشتراک قبلاً اضافه شده است؛ در حال به‌روزرسانی…")
            refreshSubscription(existing.id)
            return
        }
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        trackDataJob(viewModelScope.launch(errorHandler) {
            _busy.value = true
            val result = try {
                withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(cleanUrl) }
            } finally {
                _busy.value = false
            }
            currentCoroutineContext().ensureActive()
            if (!result.ok) {
                showMessage(result.error)
                return@launch
            }
            val configs = result.configs.filter { it.hasUsableEndpoint() }
            if (configs.isEmpty()) {
                showMessage("کانفیگ قابل استفاده‌ای در اشتراک یافت نشد")
                return@launch
            }
            val title = name.trim().ifBlank {
                result.title.ifBlank { cleanUrl.substringAfter("://").substringBefore("/").take(28) }
            }
            val sub = Subscription(
                name = title,
                url = cleanUrl,
                updatedAt = System.currentTimeMillis(),
                count = configs.size,
                upload = result.upload,
                download = result.download,
                total = result.total,
                expireAt = result.expireAt
            )
            // Commit the import immediately, even while an unrelated VPN is
            // recovering. A racing identical import cannot add a second source.
            if (!Repo.addFetchedSubscription(sub, configs, epoch, deferBuiltInCap = true)) {
                if (Repo.subs.value.any { it.url.trim() == cleanUrl }) showMessage("این اشتراک قبلاً اضافه شده است")
                else if (requireWritableData()) showMessage("داده‌ها هنگام دریافت تغییر کردند؛ دوباره تلاش کنید")
                return@launch
            }
            showMessage("${Format.number(configs.size)} کانفیگ دریافت شد؛ موارد نامشخص تا سنجش حفظ می‌شوند")
            // A later user action may supersede housekeeping, but not the import
            // or the live/recovering tunnel. Never start a late scan in that case.
            if (!Repo.startupHealthIsCurrent(token) || epoch != Repo.currentDataEpoch()) return@launch
            val source = Repo.StartupSourceSnapshot(
                sub.copy(), configs.mapTo(HashSet()) { it.fingerprint() }, SubscriptionPolicy.isComplete(result, configs)
            )
            StartupMaintenance.track(token) {
                SubscriptionSyncGate.mutex.withLock {
                    if (!Repo.startupHealthIsCurrent(token)) return@withLock
                    Repo.sync.value = ir.v2max.app.data.SyncState(running = true, message = "سنجش سرورهای اشتراک…")
                    try {
                        val checked = withContext(Dispatchers.IO) {
                            StartupMaintenance.finish(getApplication(), listOf(source), epoch, null, token)
                        }
                        currentCoroutineContext().ensureActive()
                        showMessage(refreshSummary(SyncResult(1, Repo.servers.value.size, configs.size, health = checked)))
                    } finally {
                        Repo.sync.value = Repo.sync.value.copy(running = false)
                    }
                }
            }
            // A completed download or health check is never permission to connect.
        })
    }

    private data class SyncResult(
        val updated: Int = 0,
        val total: Int = 0,
        val received: Int = 0,
        val firstError: String = "",
        val health: StartupMaintenance.Result? = null
    )

    /** The caller owns SubscriptionSyncGate through the later health pass. */
    private suspend fun refreshLocked(
        targets: List<Subscription>, epoch: Long, token: Long,
        permit: Repo.AutomaticRefreshPermit? = null
    ): SubscriptionRefresh.Result {
        Repo.sync.value = ir.v2max.app.data.SyncState(
            running = true, total = targets.size,
            message = "به‌روزرسانی ${Format.number(targets.size)} منبع…"
        )
        return SubscriptionRefresh.run(targets, epoch, token, permit) { done, total ->
            Repo.sync.value = Repo.sync.value.copy(
                done = done, total = total,
                message = "دریافت منابع (${Format.number(done)}/${Format.number(total)})"
            )
        }
    }

    private suspend fun refreshManually(
        targets: List<Subscription>, epoch: Long, token: Long
    ): SyncResult = StartupMaintenance.track(token) {
        SubscriptionSyncGate.mutex.withLock {
            try {
                val fetched = refreshLocked(targets, epoch, token)
                currentCoroutineContext().ensureActive()
                if (fetched.sources.isEmpty()) {
                    return@withLock SyncResult(total = Repo.servers.value.size, firstError = fetched.firstError)
                }
                Repo.sync.value = ir.v2max.app.data.SyncState(running = true, message = "سنجش سرورهای اشتراک…")
                val checked = withContext(Dispatchers.IO) {
                    StartupMaintenance.finish(getApplication(), fetched.sources, epoch, null, token) { phase, done, total ->
                        Repo.sync.value = Repo.sync.value.copy(
                            done = done, total = total,
                            message = if (phase.startsWith("پیش")) "پیش‌بررسی سرورهای اشتراک…"
                                else "سنجش واقعی سرورهای اشتراک…"
                        )
                    }
                }
                SyncResult(fetched.updated, Repo.servers.value.size, fetched.received, fetched.firstError, checked)
            } finally {
                // The gate owner alone can clear its indicator; cancellation of an
                // older refresh cannot hide a newer one.
                Repo.sync.value = Repo.sync.value.copy(running = false)
            }
        }
    }

    private fun refreshSummary(result: SyncResult): String {
        if (result.updated == 0) return result.firstError.ifBlank {
            "به‌روزرسانی انجام نشد یا اشتراک تغییر کرده است؛ فهرست فعلی حفظ شد"
        }
        val prefix = "${Format.number(result.updated)} منبع به‌روزرسانی شد · ${Format.number(result.total)} کانفیگ"
        val checked = result.health ?: return "$prefix؛ سنجش انجام نشد"
        val duplicates = if (checked.duplicatesRemoved > 0)
            " · ${Format.number(checked.duplicatesRemoved)} تکراری حذف شد" else ""
        val detail = when (checked.status) {
            StartupMaintenance.Status.COMPLETE -> when {
                checked.health.healthy == 0 -> "؛ هیچ تست واقعی موفق نشد و سرورهای نامطمئن حفظ شدند"
                checked.health.unknownSources > 0 -> "؛ نتیجهٔ برخی تست‌ها نامشخص بود و حفظ شدند"
                checked.health.removed > 0 -> " · ${Format.number(checked.health.removed)} کانفیگ بی‌پاسخ حذف شد"
                else -> ""
            }
            StartupMaintenance.Status.NO_NETWORK -> "؛ شبکه برای سنجش در دسترس نبود و کانفیگ‌ها حفظ شدند"
            StartupMaintenance.Status.NO_PROFILES -> "؛ کانفیگی برای سنجش در دسترس نبود"
            StartupMaintenance.Status.INCOMPLETE, StartupMaintenance.Status.CHANGED ->
                "؛ سنجش کامل نشد و نتایج نامطمئن حفظ شدند"
        }
        return prefix + duplicates + detail
    }

    fun refreshSubscription(id: String) {
        if (!requireWritableData()) return
        cancelStartup()
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        val sub = Repo.subById(id) ?: return
        trackDataJob(viewModelScope.launch(errorHandler) {
            _busy.value = true
            try {
                showMessage(refreshSummary(refreshManually(listOf(sub), epoch, token)))
            } finally {
                _busy.value = false
            }
        })
    }

    fun refreshAllSubscriptions(silent: Boolean = false) {
        if (!requireWritableData()) return
        cancelStartup()
        if (Repo.subs.value.isEmpty()) {
            if (!silent) showMessage("اشتراکی برای به‌روزرسانی وجود ندارد")
            return
        }
        // Capture both before scheduling so a newer user/service action wins.
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        trackDataJob(viewModelScope.launch(errorHandler) {
            syncAll(announce = !silent, epoch = epoch, token = token)
        })
    }

    private suspend fun syncAll(announce: Boolean, epoch: Long, token: Long): SyncResult {
        val result = refreshManually(Repo.subs.value, epoch, token)
        if (announce) showMessage(refreshSummary(result))
        return result
    }

    // ------------------------------------------------------- منابع رایگان

    /** A catalogue item is public by explicit choice; pasted URLs remain personal. */
    fun addFreeSource(url: String) {
        if (!requireWritableData()) return
        cancelStartup()
        val epoch = Repo.currentDataEpoch()
        val sub = Repo.addBuiltInSource(url, expectedEpoch = epoch)
        if (sub == null) {
            showMessage("منبع انتخاب‌شده معتبر نیست یا از قبل اضافه شده است")
            return
        }
        showMessage("منبع اضافه شد؛ در حال دریافت کانفیگ‌ها…")
        val token = Repo.startupHealthToken()
        trackDataJob(viewModelScope.launch(errorHandler) {
            showMessage(refreshSummary(refreshManually(listOf(sub), epoch, token)))
        })
    }

    fun addFreeSources() {
        if (!requireWritableData()) return
        cancelStartup()
        if (!updateSettings { it.copy(useFreeSources = true) }) return
        val added = Repo.ensureBuiltInSources(includeAll = true, requireEnabled = true)
        if (added == 0) {
            showMessage("منابع رایگان از قبل اضافه شده‌اند")
            return
        }
        showMessage("${Format.number(added)} منبع رایگان اضافه شد؛ در حال دریافت کانفیگ‌ها…")
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        trackDataJob(viewModelScope.launch(errorHandler) {
            showMessage(refreshSummary(syncAll(announce = false, epoch = epoch, token = token)))
        })
    }

    fun setFreeSourcesEnabled(enabled: Boolean) {
        if (!requireWritableData()) return
        if (!updateSettings { it.copy(useFreeSources = enabled) }) return
        if (enabled) {
            val added = Repo.ensureBuiltInSources(requireEnabled = true)
            if (added > 0) {
                cancelStartup()
                val epoch = Repo.currentDataEpoch()
                val token = Repo.startupHealthToken()
                trackDataJob(viewModelScope.launch(errorHandler) { syncAll(announce = false, epoch = epoch, token = token) })
            }
            showMessage(if (added > 0) "${Format.number(added)} منبع رایگان فعال شد" else "منابع رایگان فعال است")
        } else {
            Repo.removeBuiltInSources()
            showMessage("منابع رایگان غیرفعال و حذف شد")
        }
    }

    fun removeSubscription(id: String) {
        if (!requireWritableData()) return
        Repo.removeSubscription(id)
        showMessage("اشتراک حذف شد")
    }

    fun deferUnhealthySubscription(sub: Subscription) {
        if (!Repo.deferUnhealthySubscription(sub)) {
            showMessage("وضعیت اشتراک تغییر کرده است؛ پیشنهاد دوباره بررسی می‌شود")
        }
    }

    fun removeUnhealthySubscription(sub: Subscription) {
        if (!requireWritableData()) return
        if (Repo.removeUnhealthySubscription(sub)) showMessage("اشتراک حذف شد")
        else showMessage("اشتراک تغییر کرده یا در اتصال و ویرایش است؛ حذف نشد")
    }

    // ------------------------------------------------------------ import/CRUD

    fun importText(text: String) {
        if (!requireWritableData()) return
        cancelStartup()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            addSubscription("", trimmed)
            return
        }
        val epoch = Repo.currentDataEpoch()
        trackDataJob(viewModelScope.launch(errorHandler + Dispatchers.IO) {
            val configs = LinkParser.parseAll(trimmed)
            currentCoroutineContext().ensureActive()
            if (configs.isEmpty()) {
                showMessage("لینک معتبری پیدا نشد")
            } else {
                val added = Repo.addServers(configs, expectedEpoch = epoch)
                if (added == 0 && !requireWritableData()) {
                    return@launch
                } else if (added == 0 && epoch != Repo.currentDataEpoch()) {
                    showMessage("داده‌ها هنگام واردکردن تغییر کردند؛ دوباره تلاش کنید")
                } else if (added == 0) {
                    showMessage("این کانفیگ قبلاً اضافه شده بود")
                } else {
                    showMessage("${Format.number(added)} کانفیگ اضافه شد")
                }
            }
        })
    }

    fun deleteServer(id: String) {
        if (!requireWritableData()) return
        Repo.removeServer(id)
        showMessage("سرور حذف شد")
    }

    fun toggleFavorite(id: String) {
        if (!requireWritableData()) return
        val server = Repo.serverById(id) ?: return
        Repo.setFavorite(id, !server.favorite)
    }

    /** Tapping a server behaves the same whether or not a VPN is already running. */
    fun selectServer(id: String) = requestConnect(id)

    fun beginServerEdit(id: String): Boolean {
        if (editingServerId == id) return true
        if (!Repo.beginServerEdit(id)) return false
        editingServerId?.let(Repo::endServerEdit)
        editingServerId = id
        cancelStartup()
        return true
    }

    fun endServerEdit() {
        editingServerId?.let(Repo::endServerEdit)
        editingServerId = null
    }

    override fun onCleared() {
        endServerEdit()
        super.onCleared()
    }

    fun saveEditedServer(server: ServerConfig, expectedFingerprint: String, expectedEpoch: Long): Boolean {
        if (!requireWritableData()) return false
        cancelStartup()
        if (!Repo.saveEditedServer(server, expectedFingerprint, expectedEpoch)) {
            if (requireWritableData()) showMessage("سرور یا داده‌ها هنگام ویرایش تغییر کردند؛ ویرایشگر را دوباره باز کنید")
            return false
        }
        val state = Repo.tunnel.value
        showMessage(when {
            state.serverId == server.id && state.status == ir.v2max.app.data.TunnelStatus.CONNECTED ->
                "کانفیگ ذخیره شد؛ اتصال فعلی حفظ شد. برای اعمال تغییرات دوباره وصل شوید"
            state.status == ir.v2max.app.data.TunnelStatus.CONNECTING ->
                "کانفیگ ذخیره شد؛ وضعیت فرایند اتصال را بررسی کنید"
            else -> "کانفیگ ذخیره شد"
        })
        return true
    }

    fun cloneServer(copy: ServerConfig, sourceId: String, expectedFingerprint: String, expectedEpoch: Long): Boolean {
        if (!requireWritableData()) return false
        cancelStartup()
        if (!Repo.cloneEditedServer(copy, sourceId, expectedFingerprint, expectedEpoch)) {
            if (requireWritableData()) showMessage("سرور یا داده‌ها هنگام ویرایش تغییر کردند؛ ویرایشگر را دوباره باز کنید")
            return false
        }
        showMessage(if (Repo.tunnel.value.status == ir.v2max.app.data.TunnelStatus.CONNECTED)
            "کپی مستقل ساخته شد؛ اتصال فعلی تغییر نکرد" else "کپی مستقل ساخته شد")
        return true
    }

    fun deleteServers(ids: List<String>) {
        if (!requireWritableData()) return
        Repo.removeServers(ids)
        showMessage("${Format.number(ids.size)} سرور حذف شد")
    }

    fun setFavorite(ids: List<String>, favorite: Boolean) {
        if (!requireWritableData()) return
        ids.forEach { Repo.setFavorite(it, favorite) }
        showMessage(if (favorite) "به علاقه‌مندی‌ها اضافه شد" else "از علاقه‌مندی‌ها حذف شد")
    }

    fun shareLink(serverId: String): String? {
        val server = Repo.serverById(serverId) ?: return null
        return LinkParser.toLink(server)
    }

    // ------------------------------------------------------- بررسی به‌روزرسانی

    fun checkUpdates(manual: Boolean = true) {
        if (!manual) {
            val last = Repo.lastUpdateCheck()
            if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return
        }
        viewModelScope.launch(errorHandler) {
            if (manual) showMessage("در حال بررسی نسخه‌ی جدید…")
            val result = UpdateCoordinator.check(automatic = !manual) { info ->
                if (info.version.isBlank()) ir.v2max.app.service.Notifier.cancelUpdate(getApplication())
            } ?: return@launch
            when (result) {
                is UpdateChecker.Result.Available -> {
                    if (!manual) showMessage("نسخه‌ی ${result.info.version} منتشر شده است")
                }
                UpdateChecker.Result.Current -> {
                    if (manual) showMessage("برنامه به‌روز است (نسخه‌ی فعلی)")
                }
                UpdateChecker.Result.Unavailable -> {
                    if (manual) showMessage("بررسی نسخه انجام نشد؛ اتصال اینترنت را بررسی کنید")
                }
            }
        }
    }

    fun dismissUpdate() {
        Repo.saveUpdateInfo(ir.v2max.app.data.UpdateInfo())
    }

    /** دانلود و نصب مستقیم نسخه‌ی جدید (با بررسی امضا) */
    fun downloadAndInstallUpdate() {
        val url = Repo.update.value.apkUrl
        val app = getApplication<Application>()
        if (url.isBlank()) {
            showMessage("لینک APK در دسترس نیست؛ صفحه‌ی انتشار باز می‌شود")
            openUpdatePage()
            return
        }
        if (!ir.v2max.app.net.UpdateInstaller.canRequestInstalls(app)) {
            val opened = ir.v2max.app.net.UpdateInstaller.openInstallSettings(app)
            showMessage(if (opened) "اجازهٔ نصب از این منبع را در تنظیمات روشن کنید؛ سپس دوباره نصب را بزنید"
                else "اجازهٔ نصب از این منبع را در تنظیمات اندروید بررسی کنید")
            return
        }
        // Set before launching so two quick taps cannot start concurrent installs.
        val attempt = synchronized(updateDownloadLock) {
            if (updateDownloadActive || Repo.updateProgress.value > 0f) null else {
                updateDownloadActive = true
                Repo.updateProgress.value = 0.01f
                updateDownloadAttempt += 1L
                updateDownloadAttempt
            }
        }
        if (attempt == null) {
            showMessage("دریافت به‌روزرسانی در حال انجام است")
            return
        }
        val job = viewModelScope.launch(errorHandler) {
            showMessage("در حال دانلود به‌روزرسانی…")
            val result = ir.v2max.app.net.UpdateInstaller.download(app, url) { done, total ->
                synchronized(updateDownloadLock) {
                    if (updateDownloadActive && updateDownloadAttempt == attempt && total > 0) {
                        Repo.updateProgress.value = (done.toFloat() / total).coerceIn(0.01f, 0.99f)
                    }
                }
            }
            when (result) {
                is ir.v2max.app.net.UpdateInstaller.Result.Ready -> {
                    // Copying a large APK into PackageInstaller must not block the UI thread.
                    val installed = try {
                        withContext(Dispatchers.IO) {
                            ir.v2max.app.net.UpdateInstaller.install(app, result.file)
                        }
                    } finally {
                        result.file.delete()
                    }
                    showMessage(if (installed) "درخواست نصب ارسال شد؛ منتظر تأیید اندروید بمانید" else "شروع نصب ممکن نشد")
                }
                is ir.v2max.app.net.UpdateInstaller.Result.Failure -> showMessage(result.message)
                is ir.v2max.app.net.UpdateInstaller.Result.Progress -> Unit
            }
        }
        job.invokeOnCompletion {
            synchronized(updateDownloadLock) {
                if (updateDownloadAttempt == attempt) {
                    updateDownloadActive = false
                    Repo.updateProgress.value = 0f
                }
            }
        }
    }

    fun openUpdatePage() {
        val info = Repo.update.value
        val url = info.apkUrl.ifBlank { info.pageUrl }.ifBlank {
            "https://github.com/rahmaniho/V2max/releases/latest"
        }
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }.onFailure { showMessage("باز کردن صفحه‌ی دانلود ناموفق بود") }
    }

    // -------------------------------------------------------- بررسی شبکه

    fun refreshNetworkAssessment() {
        viewModelScope.launch(errorHandler) {
            SmartConnect.assessNetwork(getApplication(), force = true)
            val n = Repo.network.value
            showMessage(n.message.ifBlank { "بررسی شبکه انجام شد" })
        }
    }

    // ------------------------------------------------------------- QR

    /** افزودن اشتراک از کد QR اسکن‌شده (لینک اشتراک یا لینک کانفیگ) */
    fun onQrScanned(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        when {
            value.startsWith("http://") || value.startsWith("https://") -> addSubscription("", value)
            value.contains("://") -> importText(value)
            else -> showMessage("کد QR شامل لینک معتبری نیست")
        }
    }

    // -------------------------------------------------------------- settings

    fun updateSettings(block: (AppSettings) -> AppSettings): Boolean {
        val before = Repo.settings.value
        if (!Repo.updateSettings(block)) {
            if (requireWritableData()) showMessage("ذخیرهٔ تنظیمات امنیتی ممکن نشد؛ فضای دستگاه را بررسی کنید")
            return false
        }
        val after = Repo.settings.value
        if ((before.autoConnectOnOpen && !after.autoConnectOnOpen) ||
            (before.autoRefreshOnOpen && !after.autoRefreshOnOpen)) cancelStartup()
        if (before.autoConnectOnOpen && !after.autoConnectOnOpen) {
            vpnPermissions.cancelIf { it is VpnAction.Smart && it.automaticOnOpen }
            _vpnPermissionState.value = vpnPermissions.snapshot()
        }
        if (before.autoRefreshOnOpen != after.autoRefreshOnOpen) syncSubscriptionSchedule()
        if (before.autoUpdateCheck != after.autoUpdateCheck) syncUpdateSchedule()
        return true
    }

    /** Security settings use durable commits; never wait for disk on the UI thread. */
    fun updateSecuritySettings(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch(errorHandler) {
            withContext(Dispatchers.IO) {
                if (!Repo.updateSettings(block) && requireWritableData()) {
                    showMessage("ذخیرهٔ تنظیمات امنیتی ممکن نشد؛ فضای دستگاه را بررسی کنید")
                }
            }
        }
    }

    private fun syncSubscriptionSchedule() {
        runCatching {
            val app = getApplication<Application>()
            if (Repo.settings.value.autoRefreshOnOpen) ir.v2max.app.service.SubSyncWorker.schedule(app)
            else ir.v2max.app.service.SubSyncWorker.cancel(app)
        }
    }

    private fun syncUpdateSchedule() {
        runCatching {
            val app = getApplication<Application>()
            if (Repo.settings.value.autoUpdateCheck) ir.v2max.app.service.UpdateCheckWorker.schedule(app)
            else ir.v2max.app.service.UpdateCheckWorker.cancel(app)
        }
    }

    fun exportBackup(): String = Repo.exportBackup()

    fun restoreSuccessMessage(): String = when (Repo.tunnel.value.status) {
        ir.v2max.app.data.TunnelStatus.CONNECTED ->
            "پشتیبان بازیابی شد؛ اتصال فعلی حفظ شد. برای اعمال تنظیمات، دوباره وصل شوید"
        ir.v2max.app.data.TunnelStatus.CONNECTING ->
            "پشتیبان بازیابی شد؛ وضعیت فرایند اتصال را بررسی کنید"
        else -> "پشتیبان با موفقیت بازیابی شد"
    }

    private suspend fun restoreAndReschedule(text: String, epoch: Long): Boolean = withContext(NonCancellable) {
        // Once a durable commit starts, lifecycle cancellation must not leave
        // WorkManager out of sync with the settings that actually reached disk.
        val restored = withContext(Dispatchers.IO) { Repo.restoreBackup(text, expectedEpoch = epoch) }
        syncSubscriptionSchedule()
        if (restored) syncUpdateSchedule()
        restored
    }

    fun importBackup(text: String) {
        val epoch = Repo.currentDataEpoch()
        cancelStartup()
        cancelDataJobs()
        ir.v2max.app.service.SubSyncWorker.cancel(getApplication())
        viewModelScope.launch(errorHandler) {
            val restored = restoreAndReschedule(text, epoch)
            if (restored) {
                showMessage(restoreSuccessMessage())
            } else {
                showMessage("بازیابی انجام نشد؛ فایل و فضای ذخیره‌سازی را بررسی کنید")
            }
        }
    }

    /** پشتیبان رمزدار (AES-GCM + PBKDF2) */
    fun exportEncryptedBackup(password: String): String? =
        ir.v2max.app.util.SecureBackup.encrypt(exportBackup(), password)

    suspend fun importEncryptedBackup(text: String, password: String): Boolean {
        if (!ir.v2max.app.util.SecureBackup.isEncrypted(text)) return false
        val epoch = Repo.currentDataEpoch()
        val plain = withContext(Dispatchers.Default) {
            ir.v2max.app.util.SecureBackup.decrypt(text, password)
        } ?: return false
        if (epoch != Repo.currentDataEpoch()) return false
        cancelStartup()
        cancelDataJobs()
        ir.v2max.app.service.SubSyncWorker.cancel(getApplication())
        return restoreAndReschedule(plain, epoch)
    }

    fun resetData() {
        cancelStartupForUserAction()
        cancelDataJobs()
        ir.v2max.app.service.SubSyncWorker.cancel(getApplication())
        TunnelController.stop(getApplication())
        viewModelScope.launch(errorHandler) {
            val reset = withContext(NonCancellable) {
                val result = withContext(Dispatchers.IO) { Repo.resetEverything() }
                syncSubscriptionSchedule()
                if (result) syncUpdateSchedule()
                result
            }
            if (reset) {
                showMessage("سرورها، اشتراک‌ها و تنظیمات بازنشانی شدند")
            } else {
                showMessage("بازنشانی کامل نشد؛ فضای ذخیره‌سازی را بررسی و دوباره تلاش کنید")
            }
        }
    }

    fun coreVersion(): String =
        runCatching { ir.v2max.app.core.XrayCore.version() }.getOrDefault("نامشخص")

    fun previewSmartConnect() {
        requestSmartConnect(null)
    }
}
