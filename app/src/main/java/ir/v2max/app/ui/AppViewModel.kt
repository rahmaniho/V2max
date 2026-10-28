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
import ir.v2max.app.data.Subscription
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.LinkParser
import ir.v2max.app.net.SubscriptionFetcher
import ir.v2max.app.util.Format
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

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingVpnRequest = MutableStateFlow(false)
    val pendingVpnRequest: StateFlow<Boolean> = _pendingVpnRequest.asStateFlow()

    private var pendingServerId: String? = null
    private var pendingSmartScan = false
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    init {
        viewModelScope.launch { startup() }
    }

    /**
     * جریان شروع برنامه:
     *  ۱) منابع رایگان داخلی ساخته می‌شوند
     *  ۲) اتصال فوری از آخرین سرور سالم (بدون انتظار)
     *  ۳) به‌روزرسانی همه‌ی اشتراک‌ها به‌صورت موازی
     *  ۴) اسکن هوشمند و جابه‌جایی به بهترین سرور
     */
    private suspend fun startup() {
        val app = getApplication<Application>()
        val settings = Repo.settings.value

        if (settings.useFreeSources) Repo.ensureBuiltInSources()

        val needsPermission = VpnService.prepare(app) != null
        if (settings.autoConnectOnOpen) {
            if (needsPermission) {
                // همان ابتدا اجازه‌ی VPN گرفته می‌شود؛ بعد از تأیید، اتصال هوشمند اجرا می‌شود
                pendingSmartScan = true
                pendingScanSubId = null
                _pendingVpnRequest.value = true
            } else {
                SmartConnect.fastStart(app)
            }
        }

        if (settings.autoRefreshOnOpen) {
            delay(500)
            syncAll(announce = false)

            if (Repo.servers.value.isEmpty()) return
            if (needsPermission) return

            val lastScan = Repo.scan.value.finishedAt
            val scannedRecently = lastScan > 0 && System.currentTimeMillis() - lastScan < 90_000L
            if (scannedRecently) return

            val hasCache = Repo.servers.value.any { it.bestPing() > 0 }
            SmartConnect.scanAndConnect(
                app,
                autoConnect = settings.autoConnectOnOpen,
                limit = if (hasCache) 600 else 300
            )
        } else if (settings.autoConnectOnOpen && !Repo.tunnel.value.isActive && !needsPermission) {
            SmartConnect.scanAndConnect(app, autoConnect = true, limit = 400)
        } else {
            SmartConnect.refreshSelectedIfNeeded(app)
        }
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
            requestConnect(null)
        }
    }

    /** درخواست اتصال؛ در صورت نیاز مجوز VPN از کاربر گرفته می‌شود */
    fun requestConnect(serverId: String?) {
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingServerId = serverId
            pendingSmartScan = false
            _pendingVpnRequest.value = true
        } else {
            TunnelController.start(app, serverId)
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
        if (pendingSmartScan) {
            launchSmartConnect(pendingScanSubId)
        } else {
            TunnelController.start(getApplication(), pendingServerId)
        }
        pendingSmartScan = false
        pendingServerId = null
        pendingScanSubId = null
    }

    private fun launchSmartConnect(subscriptionId: String?) {
        viewModelScope.launch {
            val best = SmartConnect.scanAndConnect(getApplication(), autoConnect = true, subscriptionId = subscriptionId)
            if (best == null) showMessage(Repo.scan.value.message.ifBlank { "سرور سالمی پیدا نشد" })
        }
    }

    fun quickConnect() {
        val app = getApplication<Application>()
        if (VpnService.prepare(app) != null) {
            pendingSmartScan = false
            pendingServerId = Repo.selectedId.value
            _pendingVpnRequest.value = true
            return
        }
        viewModelScope.launch { SmartConnect.quickConnect(app) }
    }

    // ----------------------------------------------------------------- tests

    fun testAll() {
        viewModelScope.launch {
            SmartConnect.scanAndConnect(getApplication(), autoConnect = false)
        }
    }

    fun testServer(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val server = Repo.serverById(id) ?: return@launch
            val tcp = HealthScanner.tcpPing(server)
            Repo.setTestResult(id, tcp, -1)
            if (tcp > 0) {
                val real = HealthScanner.realDelay(server, Repo.settings.value)
                Repo.setTestResult(id, tcp, real)
            }
        }
    }

    // ---------------------------------------------------------- subscriptions

    fun addSubscription(name: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) {
            showMessage("آدرس اشتراک را وارد کنید")
            return
        }
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            syncAll(announce = false)
            launchSmartConnect(null)
        }
    }

    fun setFreeSourcesEnabled(enabled: Boolean) {
        updateSettings { it.copy(useFreeSources = enabled) }
        if (enabled) {
            val added = Repo.ensureBuiltInSources()
            if (added > 0) viewModelScope.launch { syncAll(announce = false) }
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
        viewModelScope.launch(Dispatchers.IO) {
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

    fun shareLink(serverId: String): String? {
        val server = Repo.serverById(serverId) ?: return null
        return LinkParser.toLink(server)
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

    fun resetData() {
        TunnelController.stop(getApplication())
        Repo.resetEverything()
        showMessage("همه‌ی داده‌ها پاک شد")
    }

    fun coreVersion(): String = runCatching { ir.v2max.app.core.XrayCore.version() }.getOrDefault("نامشخص")

    fun previewSmartConnect() {
        requestSmartConnect(null)
    }
}
