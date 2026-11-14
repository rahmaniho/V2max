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
import ir.v2max.app.net.SubscriptionBootstrap
import ir.v2max.app.net.SubscriptionPolicy
import ir.v2max.core.format.Format
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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

    private val _pendingVpnRequest = MutableStateFlow(false)
    val pendingVpnRequest: StateFlow<Boolean> = _pendingVpnRequest.asStateFlow()

    private var pendingServerId: String? = null
    private var pendingScanSubId: String? = null
    private var pendingSmartScan = false
    private var pendingRestart = false
    private var pendingQuickConnect = false
    private var startupJob: Job? = null
    private var startupRefreshJob: Job? = null
    private val dataJobs = java.util.Collections.synchronizedSet(mutableSetOf<Job>())
    private val syncMutex = kotlinx.coroutines.sync.Mutex()
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
        startupJob?.cancel()
        startupJob = null
        startupRefreshJob?.cancel()
        startupRefreshJob = null
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
        val text = throwable.message.orEmpty().take(120)
        _message.value = if (text.isBlank()) "خطای غیرمنتظره رخ داد؛ دوباره تلاش کنید" else "خطا: $text"
    }

    init {
        startupJob = viewModelScope.launch(errorHandler) { startup() }
    }

    /** Use cached profiles immediately; network refresh never certifies a profile. */
    private suspend fun startup() {
        val app = getApplication<Application>()
        val settings = Repo.settings.value
        val crash = runCatching { ir.v2max.app.util.CrashLog.lastSummary() }.getOrDefault("")
        if (crash.isNotBlank()) showMessage("دفعه‌ی قبل برنامه بسته شد: $crash")
        if (settings.useFreeSources) runCatching { Repo.ensureBuiltInSources(requireEnabled = true) }
        if (settings.autoUpdateCheck) checkUpdates(manual = false)

        val now = System.currentTimeMillis()
        val refreshPermit = Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC)
        val refresh = refreshPermit != null && Repo.subs.value.any {
            now - it.updatedAt > 3 * 60 * 60 * 1000L
        }
        var syncFinished = false
        try {
            if (refresh && refreshPermit != null && Repo.automaticRefreshIsCurrent(refreshPermit) && Repo.servers.value.isEmpty()) {
                // First install: race sources, but still test the obtained configs with Xray.
                val epoch = refreshPermit.dataEpoch
                val sources = Repo.subs.value.sortedBy { it.builtIn }
                Repo.sync.value = ir.v2max.app.data.SyncState(
                    running = true, total = sources.size, message = "دریافت نخستین منبع قابل استفاده…"
                )
                val first = try {
                    SubscriptionBootstrap.firstUsable(sources) { done, total ->
                        Repo.sync.value = Repo.sync.value.copy(done = done, total = total)
                    }
                } finally {
                    Repo.sync.value = Repo.sync.value.copy(running = false)
                }
                if (first != null) {
                    withContext(Dispatchers.IO) {
                        applyFetchedSubscription(first.subscription, first.result, epoch, automaticPermit = refreshPermit)
                    }
                } else {
                    syncFinished = true // all sources were already attempted
                    Repo.sync.value = Repo.sync.value.copy(message = "دریافت منابع ناموفق بود؛ دوباره تلاش کنید")
                }
            }

            // With auto-connect off, do not launch unsolicited preflight probes.
            if (!Repo.settings.value.autoConnectOnOpen || Repo.servers.value.isEmpty() || !autoConnectAllowed(app)) return
            // Do not restart a connection or compete with a scan the user already started.
            if (Repo.tunnel.value.isActive || Repo.scan.value.running) return
            if (VpnService.prepare(app) != null) {
                if (settings.autoConnectOnOpen) requestSmartConnectInternal()
                return
            }

            val best = SmartConnect.scanAndConnect(app, autoConnect = settings.autoConnectOnOpen, limit = 450)
            // If the first source or all cached profiles fail, refresh the other stale
            // sources and retry once rather than leaving the user disconnected.
            if (best == null && refresh && refreshPermit != null && !syncFinished) {
                syncFinished = true
                val (updated, _) = syncAll(announce = false, onlyStale = true, automaticPermit = refreshPermit)
                if (updated > 0 && !Repo.tunnel.value.isActive && !Repo.scan.value.running) {
                    SmartConnect.scanAndConnect(app, autoConnect = settings.autoConnectOnOpen, limit = 450)
                }
            }
        } finally {
            // Fetching unrelated subscriptions is not a pre-connect test. Keep it off
            // the hot path and preserve the selected/live profile while it runs.
            if (currentCoroutineContext().isActive && refresh && refreshPermit != null && !syncFinished &&
                Repo.automaticRefreshIsCurrent(refreshPermit)) {
                startupRefreshJob = viewModelScope.launch(errorHandler) {
                    syncAll(announce = false, onlyStale = true, automaticPermit = refreshPermit)
                }
            }
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

    /** درخواست اتصال؛ در صورت نیاز مجوز VPN از کاربر گرفته می‌شود */
    fun requestConnect(serverId: String?) {
        cancelStartupForUserAction()
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingServerId = serverId
            pendingScanSubId = null
            pendingSmartScan = false
            pendingQuickConnect = false
            pendingRestart = false
            _pendingVpnRequest.value = true
            return
        }
        if (serverId == null) {
            viewModelScope.launch(errorHandler) { SmartConnect.quickConnect(app) }
            return
        }
        // پیش از اتصال، همان سرور تست می‌شود (یا در صورت خرابی، چرخه‌ی کامل اجرا می‌شود)
        viewModelScope.launch(errorHandler) {
            val ok = SmartConnect.connectVerified(app, serverId)
            if (!ok) showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
        }
    }

    fun requestSmartConnect(subscriptionId: String? = null) {
        cancelStartupForUserAction()
        requestSmartConnectInternal(subscriptionId)
    }

    private fun requestSmartConnectInternal(subscriptionId: String? = null) {
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingServerId = null
            pendingSmartScan = true
            pendingQuickConnect = false
            pendingRestart = false
            _pendingVpnRequest.value = true
            pendingScanSubId = subscriptionId
        } else {
            launchSmartConnect(subscriptionId)
        }
    }

    private fun clearPendingVpnRequest() {
        _pendingVpnRequest.value = false
        pendingServerId = null
        pendingScanSubId = null
        pendingSmartScan = false
        pendingQuickConnect = false
        pendingRestart = false
    }

    fun onVpnPermissionResult(granted: Boolean) {
        // Ignore a permission result arriving after the request was cancelled/reset.
        if (!_pendingVpnRequest.value) return
        val id = pendingServerId
        val subscriptionId = pendingScanSubId
        val restart = pendingRestart
        val quickConnect = pendingQuickConnect
        val smartScan = pendingSmartScan
        clearPendingVpnRequest()
        if (!granted) {
            showMessage("برای اتصال، اجازه‌ی ساخت VPN لازم است")
            return
        }
        if (restart) {
            launchRestart()
        } else if (quickConnect) {
            viewModelScope.launch(errorHandler) { SmartConnect.quickConnect(getApplication()) }
        } else if (smartScan) {
            launchSmartConnect(subscriptionId)
        } else {
            viewModelScope.launch(errorHandler) {
                val app = getApplication<Application>()
                if (id == null) SmartConnect.quickConnect(app)
                else if (!SmartConnect.connectVerified(app, id)) {
                    showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
                }
            }
        }
    }

    private fun launchSmartConnect(subscriptionId: String?) {
        viewModelScope.launch(errorHandler) {
            val best = SmartConnect.scanAndConnect(getApplication(), autoConnect = true, subscriptionId = subscriptionId)
            if (best == null) showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
        }
    }

    fun quickConnect() {
        cancelStartupForUserAction()
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingSmartScan = false
            pendingScanSubId = null
            pendingServerId = null
            pendingQuickConnect = true
            pendingRestart = false
            _pendingVpnRequest.value = true
            return
        }
        viewModelScope.launch(errorHandler) { SmartConnect.quickConnect(app) }
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
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingServerId = null
            pendingSmartScan = false
            pendingScanSubId = null
            pendingQuickConnect = false
            pendingRestart = true
            _pendingVpnRequest.value = true
            return
        }
        launchRestart()
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
        val epoch = Repo.currentDataEpoch()
        trackDataJob(viewModelScope.launch(errorHandler) {
            _busy.value = true
            val result = try {
                withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(cleanUrl) }
            } finally {
                _busy.value = false
            }
            if (!result.ok) {
                showMessage(result.error)
                return@launch
            }
            val title = name.trim().ifBlank {
                result.title.ifBlank { cleanUrl.substringAfter("://").substringBefore("/").take(28) }
            }
            val sub = Subscription(
                name = title,
                url = cleanUrl,
                updatedAt = System.currentTimeMillis(),
                count = result.configs.size,
                upload = result.upload,
                download = result.download,
                total = result.total,
                expireAt = result.expireAt
            )
            if (!Repo.addFetchedSubscription(sub, result.configs, epoch)) {
                if (requireWritableData()) showMessage("داده‌ها هنگام دریافت تغییر کردند؛ دوباره تلاش کنید")
                return@launch
            }
            showMessage("${Format.number(result.configs.size)} کانفیگ دریافت شد؛ برای اتصال، سرور را انتخاب کنید")
            // A completed download is not permission to replace a live or recovering tunnel.
        })
    }

    /** Apply only a still-existing source and preserve user subscriptions without a count cap. */
    private fun applyFetchedSubscription(
        sub: Subscription, result: SubscriptionFetcher.Result, epoch: Long,
        automaticPermit: Repo.AutomaticRefreshPermit? = null
    ): Int {
        if (!result.ok) return 0
        val configs = SubscriptionPolicy.candidates(sub, result.configs)
        if (configs.isEmpty()) return 0
        return if (Repo.commitSubscriptionRefresh(
                sub, configs, result.upload, result.download, result.total, result.expireAt,
                expectedEpoch = epoch, automaticPermit = automaticPermit
            )) configs.size else 0
    }

    fun refreshSubscription(id: String) {
        if (!requireWritableData()) return
        val epoch = Repo.currentDataEpoch()
        val sub = Repo.subById(id) ?: return
        trackDataJob(viewModelScope.launch(errorHandler) {
            _busy.value = true
            val result = try {
                withContext(Dispatchers.IO) { SubscriptionPolicy.fetch(sub) }
            } finally {
                _busy.value = false
            }
            if (!result.ok) {
                Repo.recordSubscriptionRefreshError(sub, result.error, expectedEpoch = epoch)
                showMessage(result.error)
                return@launch
            }
            val count = withContext(Dispatchers.IO) { applyFetchedSubscription(sub, result, epoch) }
            if (count == 0) {
                Repo.recordSubscriptionRefreshError(sub, "کانفیگ قابل استفاده‌ای دریافت نشد", expectedEpoch = epoch)
                showMessage("کانفیگ قابل استفاده‌ای دریافت نشد یا اشتراک هم‌زمان تغییر کرد؛ فهرست فعلی حفظ شد")
            } else {
                showMessage("اشتراک به‌روزرسانی شد (${Format.number(count)} کانفیگ)")
            }
        })
    }

    fun refreshAllSubscriptions(silent: Boolean = false) {
        if (!requireWritableData()) return
        if (Repo.subs.value.isEmpty()) {
            if (!silent) showMessage("اشتراکی برای به‌روزرسانی وجود ندارد")
            return
        }
        trackDataJob(viewModelScope.launch(errorHandler) {
            val (updated, configs) = syncAll(announce = !silent)
            if (!silent) {
                showMessage(
                    if (updated == 0) "به‌روزرسانی ناموفق بود؛ اتصال اینترنت را بررسی کنید"
                    else "${Format.number(updated)} منبع به‌روزرسانی شد · ${Format.number(configs)} کانفیگ"
                )
            }
        })
    }

    /**
     * دریافت موازی همه‌ی اشتراک‌ها (حداکثر ۶ درخواست همزمان)، حذف تکراری‌ها
     * و ذخیره‌ی نتیجه.
     * @return (تعداد منابع موفق، تعداد کل کانفیگ‌ها)
     */
    private suspend fun syncAll(
        announce: Boolean, onlyStale: Boolean = false, automaticPermit: Repo.AutomaticRefreshPermit? = null
    ): Pair<Int, Int> = syncMutex.withLock {
        if (Repo.isStorageReadOnly() || (automaticPermit != null && !Repo.automaticRefreshIsCurrent(automaticPermit))) {
            return@withLock 0 to Repo.servers.value.size
        }
        val epoch = automaticPermit?.dataEpoch ?: Repo.currentDataEpoch()
        val now = System.currentTimeMillis()
        val targets = Repo.subs.value.filter { !onlyStale || now - it.updatedAt > 3 * 60 * 60 * 1000L }
        if (targets.isEmpty()) return@withLock 0 to Repo.servers.value.size

        Repo.sync.value = ir.v2max.app.data.SyncState(
            running = true, done = 0, total = targets.size,
            message = "به‌روزرسانی ${Format.number(targets.size)} منبع…"
        )
        val semaphore = Semaphore(6)
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        val okCount = java.util.concurrent.atomic.AtomicInteger(0)
        Repo.suspendPersist(true)
        try {
            coroutineScope {
                targets.map { sub ->
                    async(Dispatchers.IO) {
                        val result = semaphore.withPermit {
                            if (automaticPermit != null && !Repo.automaticRefreshIsCurrent(automaticPermit)) return@async
                            SubscriptionPolicy.fetch(sub)
                        }
                        currentCoroutineContext().ensureActive()
                        if (automaticPermit != null && !Repo.automaticRefreshIsCurrent(automaticPermit)) return@async
                        val count = if (result.ok) applyFetchedSubscription(sub, result, epoch, automaticPermit) else 0
                        if (count > 0) {
                            okCount.incrementAndGet()
                        } else {
                            val error = result.error.ifBlank { "کانفیگ قابل استفاده‌ای دریافت نشد" }
                            Repo.recordSubscriptionRefreshError(sub, error, expectedEpoch = epoch, automaticPermit = automaticPermit)
                        }
                        val done = doneCount.incrementAndGet()
                        Repo.sync.value = Repo.sync.value.copy(
                            done = done,
                            message = "دریافت منابع (${Format.number(done)}/${Format.number(targets.size)})"
                        )
                    }
                }.awaitAll()
            }
        } finally {
            Repo.suspendPersist(false)
            // A cancellation must not lose results already applied in memory.
            withContext(NonCancellable + Dispatchers.IO) { Repo.persistNow() }
            Repo.sync.value = Repo.sync.value.copy(running = false)
        }

        val removed = withContext(Dispatchers.IO) {
            val n = Repo.dedupeAllServers(expectedEpoch = epoch, automaticPermit = automaticPermit)
            Repo.trimBuiltInServers(expectedEpoch = epoch, automaticPermit = automaticPermit)
            Repo.persistNow()
            n
        }
        val total = Repo.servers.value.size
        Repo.sync.value = ir.v2max.app.data.SyncState(
            running = false, done = targets.size, total = targets.size,
            message = "${Format.number(okCount.get())} منبع به‌روزرسانی شد · ${Format.number(total)} کانفیگ" +
                if (removed > 0) " (${Format.number(removed)} تکراری حذف شد)" else ""
        )
        if (announce) showMessage(Repo.sync.value.message)
        okCount.get() to total
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
        trackDataJob(viewModelScope.launch(errorHandler) {
            val result = withContext(Dispatchers.IO) { SubscriptionPolicy.fetch(sub) }
            val count = withContext(Dispatchers.IO) { applyFetchedSubscription(sub, result, epoch) }
            if (count == 0) {
                if (!result.ok) Repo.recordSubscriptionRefreshError(sub, result.error, expectedEpoch = epoch)
                showMessage(if (!result.ok) result.error else "کانفیگ قابل استفاده‌ای دریافت نشد یا منبع تغییر کرده است")
            } else {
                showMessage("${Format.number(count)} کانفیگ دریافت شد؛ برای اتصال، سرور را انتخاب کنید")
            }
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
        trackDataJob(viewModelScope.launch(errorHandler) {
            val (updated, _) = syncAll(announce = false)
            showMessage(if (updated > 0) "${Format.number(updated)} منبع به‌روزرسانی شد؛ سرور دلخواه را انتخاب کنید"
                else "دریافت منابع ناموفق بود؛ برای تلاش مجدد، اشتراک‌ها را به‌روزرسانی کنید")
        })
    }

    fun setFreeSourcesEnabled(enabled: Boolean) {
        if (!requireWritableData()) return
        if (!updateSettings { it.copy(useFreeSources = enabled) }) return
        if (enabled) {
            val added = Repo.ensureBuiltInSources(requireEnabled = true)
            if (added > 0) trackDataJob(viewModelScope.launch(errorHandler) { syncAll(announce = false) })
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

    fun selectServer(id: String) {
        cancelStartupForUserAction()
        Repo.setSelected(id)
        if (Repo.tunnel.value.isActive) {
            requestConnect(id)
        }
        if (Repo.isStorageReadOnly()) showMessage("انتخاب سرور تا بستن برنامه موقت است؛ داده‌ها فقط‌خواندنی‌اند")
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
