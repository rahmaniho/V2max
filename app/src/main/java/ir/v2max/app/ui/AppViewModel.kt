package ir.v2max.app.ui

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.core.TunnelController
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.PublicSources
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.LinkParser
import ir.v2max.app.net.UpdateChecker
import ir.v2max.app.net.SubscriptionFetcher
import ir.v2max.app.util.Format
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.delay
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
    private var pendingSmartScan = false
    private var pendingRestart = false
    private var pendingQuickConnect = false
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

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
        viewModelScope.launch(errorHandler) { startup() }
    }

    /**
     * جریان شروع برنامه:
     *  ۱) منابع رایگان داخلی ساخته می‌شوند
     *  ۲) همه‌ی اشتراک‌ها به‌صورت موازی به‌روزرسانی می‌شوند
     *  ۳) پیش از هر اتصالی، همه‌ی سرورها با سه مقصد (گوگل/اینستاگرام/یوتیوب) پینگ می‌شوند
     *  ۴) سرورهای بدون پاسخ حذف و بهترین گزینه وصل می‌شود
     */
    private suspend fun startup() {
        val app = getApplication<Application>()
        val settings = Repo.settings.value

        // اگر دفعه‌ی قبل برنامه بسته شده باشد، علت را نشان بده (به‌جای سکوت)
        val crash = runCatching { ir.v2max.app.util.CrashLog.lastSummary() }.getOrDefault("")
        if (crash.isNotBlank()) {
            showMessage("دفعه‌ی قبل برنامه بسته شد: $crash")
        }

        if (settings.useFreeSources) runCatching { Repo.ensureBuiltInSources() }

        // ۱) بررسی عمیق شبکه‌ی اپراتور (DNS/DPI/تأخیر) پیش از هر کاری
        if (settings.deepProbe) SmartConnect.assessNetwork(app, force = true)

        // به‌روزرسانی خودکار فقط اگر داده‌ها کهنه باشند (پیش‌فرض ۳ ساعت) — شروع سریع‌تر
        val newest = Repo.subs.value.maxOfOrNull { it.updatedAt } ?: 0L
        val stale = System.currentTimeMillis() - newest > 3 * 60 * 60 * 1000L
        if (settings.autoRefreshOnOpen && stale) {
            delay(200)
            syncAll(announce = false)
        }

        // بررسی نسخه‌ی جدید (حداکثر یک‌بار در ۶ ساعت)
        if (settings.autoUpdateCheck) checkUpdates(manual = false)

        if (Repo.servers.value.isEmpty()) return

        if (!autoConnectAllowed(app)) return
        val needsPermission = VpnService.prepare(app) != null
        if (needsPermission) {
            if (settings.autoConnectOnOpen) {
                pendingSmartScan = true
                pendingScanSubId = null
                _pendingVpnRequest.value = true
            }
            return
        }

        val lastScan = Repo.scan.value.finishedAt
        val scannedRecently = lastScan > 0 && System.currentTimeMillis() - lastScan < 90_000L

        if (!settings.autoRefreshOnOpen && scannedRecently) {
            // همین چند لحظه پیش اسکن شده؛ از همان نتیجه استفاده می‌شود
            if (settings.autoConnectOnOpen && !Repo.tunnel.value.isActive) {
                SmartConnect.fastStart(app)
            }
            return
        }

        // اسکن سه‌مرحله‌ای و اتصال به بهترین سرور (اتصال پس از پینگ انجام می‌شود)
        SmartConnect.scanAndConnect(
            app,
            autoConnect = settings.autoConnectOnOpen,
            limit = 450
        )
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
        if (Repo.tunnel.value.isActive) {
            TunnelController.stop(getApplication())
        } else {
            quickConnect()
        }
    }

    fun disconnectTunnel() {
        TunnelController.stop(getApplication())
    }

    /** درخواست اتصال؛ در صورت نیاز مجوز VPN از کاربر گرفته می‌شود */
    fun requestConnect(serverId: String?) {
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingServerId = serverId
            pendingSmartScan = false
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
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingServerId = null
            pendingSmartScan = true
            _pendingVpnRequest.value = true
            pendingScanSubId = subscriptionId
        } else {
            launchSmartConnect(subscriptionId)
        }
    }

    private var pendingScanSubId: String? = null

    fun onVpnPermissionResult(granted: Boolean) {
        _pendingVpnRequest.value = false
        if (!granted) {
            showMessage("برای اتصال، اجازه‌ی ساخت VPN لازم است")
            return
        }
        if (pendingRestart) {
            pendingRestart = false
            launchRestart()
        } else if (pendingQuickConnect) {
            pendingQuickConnect = false
            viewModelScope.launch(errorHandler) { SmartConnect.quickConnect(getApplication()) }
        } else if (pendingSmartScan) {
            launchSmartConnect(pendingScanSubId)
        } else {
            TunnelController.start(getApplication(), pendingServerId)
        }
        pendingSmartScan = false
        pendingServerId = null
        pendingScanSubId = null
    }

    private fun launchSmartConnect(subscriptionId: String?) {
        viewModelScope.launch(errorHandler) {
            val best = SmartConnect.scanAndConnect(getApplication(), autoConnect = true, subscriptionId = subscriptionId)
            if (best == null) showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
        }
    }

    fun quickConnect() {
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingSmartScan = true
            pendingScanSubId = null
            pendingServerId = null
            pendingQuickConnect = true
            _pendingVpnRequest.value = true
            return
        }
        viewModelScope.launch(errorHandler) { SmartConnect.quickConnect(app) }
    }

    // ----------------------------------------------------------------- tests

    fun testAll() {
        viewModelScope.launch(errorHandler) {
            SmartConnect.scanAndConnect(getApplication(), autoConnect = false)
        }
    }

    fun testServer(id: String) {
        viewModelScope.launch(errorHandler + Dispatchers.IO) {
            val server = Repo.serverById(id) ?: return@launch
            val settings = Repo.settings.value
            val tcp = HealthScanner.tcpPing(server)
            Repo.setTestResult(id, tcp, -1)
            if (tcp <= 0) return@launch

            val google = HealthScanner.realDelay(server, settings, ir.v2max.app.data.PingTargets.GOOGLE.url)
            val instagram = if (settings.multiPing)
                HealthScanner.realDelay(server, settings, ir.v2max.app.data.PingTargets.INSTAGRAM.url) else -1
            val youtube = if (settings.multiPing)
                HealthScanner.realDelay(server, settings, ir.v2max.app.data.PingTargets.YOUTUBE.url) else -1
            Repo.setMultiPing(id, google, instagram, youtube, persist = true)
        }
    }

    /**
     * «ری‌استارت جستجو» از صفحه‌ی خانه:
     * نتایج قبلی پاک می‌شوند و کل چرخه (اسکن سه‌مرحله‌ای + انتخاب بهترین + اتصال) از نو اجرا می‌شود.
     * اگر VPN وصل باشد، اتصال تا پیدا شدن گزینه‌ی بهتر حفظ می‌شود.
     */
    fun restartScan() {
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingServerId = null
            pendingSmartScan = true
            pendingScanSubId = null
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
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) {
            showMessage("آدرس اشتراک را وارد کنید")
            return
        }
        viewModelScope.launch(errorHandler) {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(cleanUrl) }
            _busy.value = false
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
            Repo.addSubscription(sub)
            result.configs.forEach { it.subId = sub.id }
            Repo.replaceSubscriptionServers(sub.id, result.configs)
            showMessage("${Format.number(result.configs.size)} کانفیگ دریافت شد؛ در حال انتخاب سریع‌ترین…")
            launchSmartConnect(sub.id)
        }
    }

    fun refreshSubscription(id: String) {
        val sub = Repo.subById(id) ?: return
        viewModelScope.launch(errorHandler) {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(sub.url) }
            _busy.value = false
            if (!result.ok) {
                Repo.updateSubscription(sub.copy(lastError = result.error))
                showMessage(result.error)
                return@launch
            }
            result.configs.forEach { it.subId = sub.id }
            Repo.replaceSubscriptionServers(sub.id, result.configs)
            Repo.updateSubscription(
                sub.copy(
                    updatedAt = System.currentTimeMillis(),
                    count = result.configs.size,
                    upload = result.upload,
                    download = result.download,
                    total = result.total,
                    expireAt = result.expireAt,
                    lastError = ""
                )
            )
            showMessage("اشتراک به‌روزرسانی شد (${Format.number(result.configs.size)} کانفیگ)")
        }
    }

    fun refreshAllSubscriptions(silent: Boolean = false) {
        if (Repo.subs.value.isEmpty()) {
            if (!silent) showMessage("اشتراکی برای به‌روزرسانی وجود ندارد")
            return
        }
        viewModelScope.launch(errorHandler) {
            val (updated, configs) = syncAll(announce = !silent)
            if (!silent) {
                showMessage(
                    if (updated == 0) "به‌روزرسانی ناموفق بود؛ اتصال اینترنت را بررسی کنید"
                    else "${Format.number(updated)} منبع به‌روزرسانی شد · ${Format.number(configs)} کانفیگ"
                )
            }
        }
    }

    /**
     * دریافت موازی همه‌ی اشتراک‌ها (حداکثر ۶ درخواست همزمان)، حذف تکراری‌ها
     * و ذخیره‌ی نتیجه.
     * @return (تعداد منابع موفق، تعداد کل کانفیگ‌ها)
     */
    private suspend fun syncAll(announce: Boolean): Pair<Int, Int> = syncMutex.withLock {
        val targets = Repo.subs.value
        if (targets.isEmpty()) return@withLock 0 to Repo.servers.value.size

        Repo.sync.value = ir.v2max.app.data.SyncState(
            running = true,
            done = 0,
            total = targets.size,
            message = "به‌روزرسانی ${Format.number(targets.size)} منبع…"
        )

        val semaphore = Semaphore(6)
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        val okCount = java.util.concurrent.atomic.AtomicInteger(0)

        // نوشتن انبوه روی دیسک موقتاً متوقف می‌شود تا همگام‌سازی سریع بماند
        Repo.suspendPersist(true)
        try {
        coroutineScope {
            targets.map { sub ->
                async(Dispatchers.IO) {
                    val result = semaphore.withPermit { SubscriptionFetcher.fetch(sub.url) }
                    if (result.ok) {
                        val cap = PublicSources.forUrl(sub.url)?.maxConfigs ?: 400
                        val trimmed = result.configs.take(cap)
                        trimmed.forEach { it.subId = sub.id }
                        Repo.replaceSubscriptionServers(sub.id, trimmed)
                        Repo.updateSubscription(
                            sub.copy(
                                updatedAt = System.currentTimeMillis(),
                                count = trimmed.size,
                                upload = result.upload,
                                download = result.download,
                                total = result.total,
                                expireAt = result.expireAt,
                                lastError = ""
                            )
                        )
                        okCount.incrementAndGet()
                    } else {
                        Repo.updateSubscription(sub.copy(lastError = result.error))
                    }
                    val d = doneCount.incrementAndGet()
                    Repo.sync.value = Repo.sync.value.copy(
                        done = d,
                        message = "دریافت منابع (${Format.number(d)}/${Format.number(targets.size)})"
                    )
                }
            }.awaitAll()
        }

        } finally {
            Repo.suspendPersist(false)
        }

        val removed = Repo.dedupeAllServers()
        Repo.trimBuiltInServers()
        Repo.persistNow()
        val total = Repo.servers.value.size
        Repo.sync.value = ir.v2max.app.data.SyncState(
            running = false,
            done = targets.size,
            total = targets.size,
            message = "${Format.number(okCount.get())} منبع به‌روزرسانی شد · ${Format.number(total)} کانفیگ" +
                if (removed > 0) " (${Format.number(removed)} تکراری حذف شد)" else ""
        )
        if (announce) showMessage(Repo.sync.value.message)
        okCount.get() to total
    }

    // ------------------------------------------------------- منابع رایگان

    fun addFreeSources() {
        val added = Repo.ensureBuiltInSources()
        if (added == 0) {
            showMessage("منابع رایگان از قبل اضافه شده‌اند")
            return
        }
        updateSettings { it.copy(useFreeSources = true) }
        showMessage("${Format.number(added)} منبع رایگان اضافه شد؛ در حال دریافت کانفیگ‌ها…")
        viewModelScope.launch(errorHandler) {
            syncAll(announce = false)
            launchSmartConnect(null)
        }
    }

    fun setFreeSourcesEnabled(enabled: Boolean) {
        updateSettings { it.copy(useFreeSources = enabled) }
        if (enabled) {
            val added = Repo.ensureBuiltInSources()
            if (added > 0) viewModelScope.launch(errorHandler) { syncAll(announce = false) }
            showMessage(if (added > 0) "${Format.number(added)} منبع رایگان فعال شد" else "منابع رایگان فعال است")
        } else {
            Repo.removeBuiltInSources()
            showMessage("منابع رایگان غیرفعال و حذف شد")
        }
    }

    fun removeSubscription(id: String) {
        Repo.removeSubscription(id)
        showMessage("اشتراک حذف شد")
    }

    // ------------------------------------------------------------ import/CRUD

    fun importText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            addSubscription("", trimmed)
            return
        }
        viewModelScope.launch(errorHandler + Dispatchers.IO) {
            val configs = LinkParser.parseAll(trimmed)
            if (configs.isEmpty()) {
                showMessage("لینک معتبری پیدا نشد")
            } else {
                val added = Repo.addServers(configs)
                if (added == 0) {
                    showMessage("این کانفیگ قبلاً اضافه شده بود")
                } else {
                    showMessage("${Format.number(added)} کانفیگ اضافه شد")
                    Repo.setSelected(configs.first().id)
                }
            }
        }
    }

    fun deleteServer(id: String) {
        Repo.removeServer(id)
        showMessage("سرور حذف شد")
    }

    fun toggleFavorite(id: String) {
        val server = Repo.serverById(id) ?: return
        Repo.setFavorite(id, !server.favorite)
    }

    fun selectServer(id: String) {
        Repo.setSelected(id)
        if (Repo.tunnel.value.isActive) {
            requestConnect(id)
        }
    }

    fun saveEditedServer(server: ServerConfig) {
        Repo.updateServer(server)
        showMessage("کانفیگ ذخیره شد")
    }

    fun cloneServer(copy: ServerConfig) {
        Repo.updateServer(copy)
        Repo.setSelected(copy.id)
        showMessage("نسخه‌ی کپی ساخته شد")
    }

    fun deleteServers(ids: List<String>) {
        Repo.removeServers(ids)
        showMessage("${Format.number(ids.size)} سرور حذف شد")
    }

    fun setFavorite(ids: List<String>, favorite: Boolean) {
        ids.forEach { Repo.setFavorite(it, favorite) }
        showMessage(if (favorite) "به علاقه‌مندی‌ها اضافه شد" else "از علاقه‌مندی‌ها حذف شد")
    }

    fun shareLink(serverId: String): String? {
        val server = Repo.serverById(serverId) ?: return null
        return LinkParser.toLink(server)
    }

    // ------------------------------------------------------- بررسی به‌روزرسانی

    fun checkUpdates(manual: Boolean = true) {
        val app = getApplication<Application>()
        if (!manual) {
            val last = Repo.lastUpdateCheck()
            if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return
        }
        viewModelScope.launch(errorHandler) {
            if (manual) showMessage("در حال بررسی نسخه‌ی جدید…")
            Repo.markUpdateChecked()
            val info = UpdateChecker.checkForUpdate(app)
            if (info != null) {
                Repo.update.value = info
                if (!manual) showMessage("نسخه‌ی ${info.version} منتشر شده است")
            } else {
                Repo.update.value = ir.v2max.app.data.UpdateInfo()
                if (manual) showMessage("برنامه به‌روز است (نسخه‌ی فعلی)")
            }
        }
    }

    fun dismissUpdate() {
        Repo.update.value = ir.v2max.app.data.UpdateInfo()
    }

    /** دانلود و نصب مستقیم نسخه‌ی جدید (با بررسی امضا) */
    fun downloadAndInstallUpdate() {
        val info = Repo.update.value
        val url = info.apkUrl.ifBlank { "" }
        val app = getApplication<Application>()
        if (url.isBlank()) {
            showMessage("لینک APK در دسترس نیست؛ صفحه‌ی انتشار باز می‌شود")
            openUpdatePage()
            return
        }
        viewModelScope.launch(errorHandler) {
            Repo.updateProgress.value = 0.01f
            showMessage("در حال دانلود به‌روزرسانی…")
            val result = ir.v2max.app.net.UpdateInstaller.download(app, url) { done, total ->
                if (total > 0) Repo.updateProgress.value = (done.toFloat() / total).coerceIn(0f, 0.99f)
            }
            Repo.updateProgress.value = 0f
            when (result) {
                is ir.v2max.app.net.UpdateInstaller.Result.Ready -> {
                    val started = ir.v2max.app.net.UpdateInstaller.install(app, result.file)
                    showMessage(
                        if (started) "نصب‌کننده باز شد؛ تأیید کنید"
                        else "شروع نصب ممکن نشد"
                    )
                }
                is ir.v2max.app.net.UpdateInstaller.Result.Failure -> showMessage(result.message)
                is ir.v2max.app.net.UpdateInstaller.Result.Progress -> Unit
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

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        Repo.updateSettings(block)
    }

    fun exportBackup(): String = Repo.exportBackup()

    fun importBackup(text: String) {
        if (Repo.restoreBackup(text)) {
            showMessage("پشتیبان با موفقیت بازیابی شد")
        } else {
            showMessage("فایل پشتیبان معتبر نیست")
        }
    }

    /** پشتیبان رمزدار (AES-GCM + PBKDF2) */
    fun exportEncryptedBackup(password: String): String? =
        ir.v2max.app.util.SecureBackup.encrypt(exportBackup(), password)

    fun importEncryptedBackup(text: String, password: String): Boolean {
        if (!ir.v2max.app.util.SecureBackup.isEncrypted(text)) return false
        val plain = ir.v2max.app.util.SecureBackup.decrypt(text, password) ?: return false
        return Repo.restoreBackup(plain)
    }

    fun resetData() {
        TunnelController.stop(getApplication())
        Repo.resetEverything()
        showMessage("همه‌ی داده‌ها پاک شد")
    }

    fun coreVersion(): String =
        runCatching { ir.v2max.app.core.XrayCore.version() }.getOrDefault("نامشخص")

    fun previewSmartConnect() {
        requestSmartConnect(null)
    }
}
