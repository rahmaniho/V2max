package ir.v2max.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import ir.v2max.app.BuildConfig
import ir.v2max.core.domain.ServerListPolicy
import ir.v2max.core.domain.SubscriptionMerger
import ir.v2max.core.geo.CountryFlags
import ir.v2max.core.model.PublicSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * مخزن مرکزی داده‌ها.
 * همه‌ی وضعیت برنامه (سرورها، اشتراک‌ها، تنظیمات) در حافظه نگهداری و روی دیسک ذخیره می‌شود.
 */
object Repo {

    private const val PREF_NAME = "v2max_store"
    private const val KEY_SERVERS = "servers_v1"
    private const val KEY_SUBS = "subs_v1"
    private const val KEY_SETTINGS = "settings_v1"
    private const val KEY_SELECTED = "selected_v1"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check_v1"
    private const val KEY_UPDATE_CHECK_VERSION = "update_checked_for_version_v1"
    private const val KEY_UPDATE_INFO = "pending_update_v1"
    private const val KEY_UPDATE_INFO_VERSION = "pending_update_for_version_v1"
    private const val KEY_UPDATE_NOTIFIED = "last_notified_update_v1"
    private const val KEY_TRANSACTION_MARKER = "storage_transaction_marker_v1"
    // This file is excluded from Android cloud and device-transfer backups.
    private const val CONTROL_PREF_NAME = "v2max_control_consent"
    private const val KEY_EXTERNAL_AUTOMATION = "allow_external_automation"

    private val gson = Gson()
    private var prefs: SharedPreferences? = null
    private var controlPrefs: SharedPreferences? = null
    private var canWriteServers = true
    private var canWriteSubs = true
    private var canWriteSettings = true
    // Invalidates downloads/parses that started before an explicit restore/reset.
    private var dataEpoch = 0L

    /** Warning is populated only when legacy local data could not be fully read. */
    val recoveryWarning = MutableStateFlow("")

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subs: StateFlow<List<Subscription>> = _subs.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    // Invalidates automatic checks even if the user switches the setting off and back on.
    private var automaticUpdateEpoch = 0L
    // Automatic subscription downloads started under an old opt-in must not
    // commit if that opt-in was revoked and subsequently enabled again.
    private var automaticRefreshEpoch = 0L

    enum class AutomaticRefresh { STARTUP_OR_PERIODIC, FAILOVER }
    data class AutomaticRefreshPermit internal constructor(
        val dataEpoch: Long,
        internal val settingsEpoch: Long,
        internal val kind: AutomaticRefresh
    )

    private val _selectedId = MutableStateFlow("")
    val selectedId: StateFlow<String> = _selectedId.asStateFlow()

    val tunnel = MutableStateFlow(TunnelUiState())
    val scan = MutableStateFlow(ScanState())
    val sync = MutableStateFlow(SyncState())
    val network = MutableStateFlow(NetworkAssessment())
    val update = MutableStateFlow(UpdateInfo())

    /** تاریخچه‌ی تأخیر برای نمودار زنده‌ی صفحه‌ی خانه (آخرین ۶۰ نمونه) */
    val latencyHistory = MutableStateFlow<List<Int>>(emptyList())

    /** تعداد سرورهای استخر فعال در اتصال جاری */
    val poolSize = MutableStateFlow(0)

    fun pushLatency(ms: Int) {
        if (ms <= 0) return
        val current = latencyHistory.value
        latencyHistory.value = (current + ms).takeLast(60)
    }

    fun clearLatencyHistory() {
        latencyHistory.value = emptyList()
    }

    /** پیام نتیجه‌ی نصب به‌روزرسانی (از PackageInstaller) */
    val installMessage = MutableStateFlow("")

    /** پیشرفت دانلود به‌روزرسانی: 0..1 و صفر یعنی غیرفعال */
    val updateProgress = MutableStateFlow(0f)

    @Volatile
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val control = app.getSharedPreferences(CONTROL_PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        controlPrefs = control
        // Recover records independently without overwriting damaged documents.
        // If any section is damaged, all user data stays read-only until an
        // explicit restore/reset; background actions are also paused.
        val servers = StoredDataRecovery.list(p, KEY_SERVERS, ServerConfig())
        val subs = StoredDataRecovery.list(p, KEY_SUBS, Subscription())
        val savedSettings = StoredDataRecovery.settings(p, KEY_SETTINGS)
        _servers.value = servers.value
        _subs.value = subs.value
        canWriteServers = servers.writesAllowed
        canWriteSubs = subs.writesAllowed
        canWriteSettings = savedSettings.writesAllowed
        val damaged = servers.damaged || subs.damaged || savedSettings.damaged
        if (damaged) {
            recoveryWarning.value = "بخشی از داده‌های ذخیره‌شده ناسازگار است و ممکن است نمایش داده نشود. " +
                "اصل داده بدون تغییر مانده؛ ویرایش، اتصال خودکار و دریافت خودکار اشتراک متوقف شده‌اند. " +
                "برای ادامه، پشتیبان معتبر را بازیابی کنید یا پس از بررسی، داده‌ها و تنظیمات را بازنشانی کنید."
        }
        val restoredSettings = StoredDataRecovery.guardSettings(savedSettings.value, damaged)
        val consent = runCatching { control.getBoolean(KEY_EXTERNAL_AUTOMATION, false) }.getOrDefault(false)
        _settings.value = restoredSettings.copy(
            allowExternalAutomation = consent && !damaged && !restoredSettings.appLock
        )
        // Neither a lock nor damaged data may resurrect a prior consent.
        if (consent && (damaged || restoredSettings.appLock)) {
            runCatching { persistControlConsent(control, false) }
        }
        _selectedId.value = runCatching { p.getString(KEY_SELECTED, "") }.getOrNull().orEmpty()
        // A pending update was compared against a particular installed version.
        // Do not show it after the package itself has been upgraded.
        update.value = if (runCatching { p.getString(KEY_UPDATE_INFO_VERSION, "") }.getOrNull() == BuildConfig.VERSION_NAME) {
            runCatching { p.getString(KEY_UPDATE_INFO, null) }.getOrNull()
                ?.let { Migrations.heal(it, UpdateInfo()) } ?: UpdateInfo()
        } else UpdateInfo()
        loaded = true
        loadProfiles()
    }

    /** کشور سرور را از نامش تشخیص می‌دهد (آفلاین) */
    private fun withCountry(cfg: ServerConfig): ServerConfig {
        if (cfg.countryCode.isNotBlank()) return cfg
        val resolved = CountryFlags.resolve(cfg.name) ?: return cfg
        cfg.countryCode = resolved.code
        cfg.countryName = resolved.name
        return cfg
    }

    @Volatile
    private var persistSuspensions = 0

    /** A damaged section makes cross-linked user data read-only until explicit recovery. */
    @Synchronized
    fun isStorageReadOnly(): Boolean = !canWriteServers || !canWriteSubs || !canWriteSettings

    @Synchronized
    fun currentDataEpoch(): Long = dataEpoch

    private fun autoRefreshEnabled(kind: AutomaticRefresh): Boolean = when (kind) {
        AutomaticRefresh.STARTUP_OR_PERIODIC -> _settings.value.autoRefreshOnOpen
        AutomaticRefresh.FAILOVER -> _settings.value.autoUpdateSubs
    }

    @Synchronized
    fun beginAutomaticRefresh(kind: AutomaticRefresh): AutomaticRefreshPermit? =
        if (!isStorageReadOnly() && autoRefreshEnabled(kind)) {
            AutomaticRefreshPermit(dataEpoch, automaticRefreshEpoch, kind)
        } else null

    @Synchronized
    fun automaticRefreshIsCurrent(permit: AutomaticRefreshPermit): Boolean =
        !isStorageReadOnly() && permit.dataEpoch == dataEpoch && permit.settingsEpoch == automaticRefreshEpoch &&
            autoRefreshEnabled(permit.kind)

    /** Batch server/subscription writes; overlapping refreshes each own a suspension. */
    @Synchronized
    fun suspendPersist(suspended: Boolean) {
        persistSuspensions = if (suspended) persistSuspensions + 1 else (persistSuspensions - 1).coerceAtLeast(0)
    }

    /** ذخیره‌ی فوری روی دیسک */
    @Synchronized
    fun persistNow() {
        persistServers()
        persistSubs()
    }

    private fun persistServers() {
        if (persistSuspensions > 0 || isStorageReadOnly()) return
        prefs?.edit()?.putString(KEY_SERVERS, gson.toJson(_servers.value))?.apply()
    }

    private fun persistSubs() {
        if (persistSuspensions > 0 || isStorageReadOnly()) return
        prefs?.edit()?.putString(KEY_SUBS, gson.toJson(_subs.value))?.apply()
    }

    private fun persistSettings() {
        // Settings changed during a long subscription sync must not be lost.
        if (isStorageReadOnly()) return
        prefs?.edit()?.putString(KEY_SETTINGS, gson.toJson(_settings.value))?.apply()
    }

    // ---------------------------------------------------------------- servers

    fun serverById(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    fun selectedServer(): ServerConfig? {
        val id = _selectedId.value
        return _servers.value.firstOrNull { it.id == id } ?: _servers.value.firstOrNull()
    }

    @Synchronized
    fun setSelected(id: String) {
        _selectedId.value = id
        // Manual connection remains possible, but the selection is temporary
        // while its underlying damaged catalogue is protected.
        if (!isStorageReadOnly()) prefs?.edit()?.putString(KEY_SELECTED, id)?.apply()
    }

    private fun capped(list: List<ServerConfig>): List<ServerConfig> =
        ServerListPolicy.capBuiltIn(
            list = list,
            builtInSubscriptionIds = _subs.value.filter { it.builtIn }.map { it.id }.toSet(),
            selectedId = _selectedId.value,
            protectedIds = setOfNotNull(tunnel.value.serverId.takeIf { tunnel.value.isActive && it.isNotBlank() }),
            idOf = { it.id },
            subscriptionIdOf = { it.subId },
            isFavorite = { it.favorite },
            scoreOf = { it.score() },
        )

    /**
     * افزودن کانفیگ‌های جدید با حذف تکراری‌ها.
     *
     * قانون حذف تکراری در `ServerListPolicy.appendNew` تست شده است: کانفیگی که
     * از قبل در فهرست است هرگز جایگزین نمی‌شود، پس واردکردن دوباره‌ی یک سرور
     * تاریخچه‌ی پینگ و نامی که کاربر رویش گذاشته از بین نمی‌برد.
     *
     * @return تعداد کانفیگ‌های افزوده‌شده
     */
    @Synchronized
    fun addServers(incoming: List<ServerConfig>, expectedEpoch: Long? = null): Int {
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch)) return 0
        val result = ServerListPolicy.appendNew(
            existing = _servers.value,
            incoming = incoming,
            fingerprintOf = { it.fingerprint() },
            isUsable = { it.hasUsableEndpoint() },
            prepare = ::withCountry,
        )
        if (!result.changed) return 0

        _servers.value = capped(result.servers)
        persistServers()
        if (_selectedId.value.isBlank()) setSelected(_servers.value.lastOrNull()?.id.orEmpty())
        return result.added
    }

    /**
     * جایگزینی هوشمند سرورهای یک اشتراک.
     * کانفیگ‌های موجود (بر اساس اثر انگشت) با حفظ شناسه، علاقه‌مندی و نتیجه تست نگه داشته می‌شوند.
     */
    @Synchronized
    fun replaceSubscriptionServers(subId: String, incoming: List<ServerConfig>) {
        if (isStorageReadOnly()) return
        // A delayed HTTP response must not resurrect a subscription removed by the user.
        if (_subs.value.none { it.id == subId }) return
        val existing = _servers.value.filter { it.subId == subId }
        val merged = SubscriptionMerger.merge(
            existing = existing,
            incoming = incoming,
            liveId = tunnel.value.serverId.takeIf { tunnel.value.isActive },
            protectedIds = setOf(_selectedId.value).filter { it.isNotBlank() }.toSet(),
            fingerprintOf = { it.fingerprint() },
            isUsable = { it.hasUsableEndpoint() },
            idOf = { it.id },
            carryOver = ::carryOverExisting,
        )
        val others = _servers.value.filter { it.subId != subId }
        _servers.value = capped(others + merged)
        persistServers()
        if (serverById(_selectedId.value) == null) {
            setSelected(_servers.value.firstOrNull()?.id.orEmpty())
        }
    }

    /**
     * هویت و تاریخچه‌ی تست کانفیگ قبلی را روی کانفیگ تازه‌ی همان اشتراک نگه می‌دارد.
     *
     * این فهرست، همان چیزی است که نباید در یک به‌روزرسانی اشتراک از دست برود:
     * شناسه (تا انتخاب کاربر نشکند)، علاقه‌مندی، نتایج پینگ و سرعت، کشور، و
     * اطلاعات تست فرگمنت. قوانین ادغام در `SubscriptionMerger` تست شده‌اند.
     */
    private fun carryOverExisting(old: ServerConfig, updated: ServerConfig): ServerConfig {
        updated.id = old.id
        updated.favorite = old.favorite
        updated.tcpPing = old.tcpPing
        updated.realPing = old.realPing
        updated.googlePing = old.googlePing
        updated.instagramPing = old.instagramPing
        updated.youtubePing = old.youtubePing
        updated.speedKbps = old.speedKbps
        updated.countryCode = old.countryCode
        updated.countryName = old.countryName
        updated.successCount = old.successCount
        updated.failCount = old.failCount
        updated.testedAt = old.testedAt
        updated.testedFragment = old.testedFragment
        updated.testedFragmentProfile = old.testedFragmentProfile
        updated.testedUrl = old.testedUrl
        updated.addedAt = old.addedAt
        return updated
    }

    @Synchronized
    fun updateServer(server: ServerConfig) {
        if (isStorageReadOnly()) return
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        _servers.value = list
        persistServers()
    }

    @Synchronized
    fun updateServers(updated: Collection<ServerConfig>) {
        if (isStorageReadOnly() || updated.isEmpty()) return
        val byId = updated.associateBy { it.id }
        _servers.value = _servers.value.map { byId[it.id] ?: it }
        persistServers()
    }

    @Synchronized
    /**
     * ثبت نتیجه‌ی تست یک سرور.
     * در زمان اسکن انبوه با persist=false فراخوانی می‌شود و در پایان با [persistNow] یک‌جا ذخیره می‌گردد.
     */
    fun setTestResult(
        id: String,
        tcpPing: Int,
        realPing: Int,
        testedAt: Long = System.currentTimeMillis(),
        persist: Boolean = true
    ) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(tcpPing = tcpPing, realPing = realPing, testedAt = testedAt)
        _servers.value = list
        if (persist) persistServers()
    }

    /** TCP is only a preliminary result and must not erase an older real test. */
    @Synchronized
    fun setPretest(id: String, tcpPing: Int, expectedFingerprint: String? = null) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0 || (expectedFingerprint != null && list[idx].fingerprint() != expectedFingerprint)) return
        list[idx] = list[idx].copy(tcpPing = tcpPing)
        _servers.value = list
    }

    @Synchronized
    fun setVerifiedResult(
        id: String,
        result: ir.v2max.app.net.ProbeResult?,
        clearOtherTargets: Boolean = true,
        expectedFingerprint: String? = null
    ) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = list[idx]
        // A result for an old snapshot must never certify an edited config with the same ID.
        if (expectedFingerprint != null && old.fingerprint() != expectedFingerprint) return
        list[idx] = old.copy(
            realPing = result?.delayMs ?: -1,
            googlePing = if (result?.url == PingTargets.GOOGLE.url) result.delayMs else -1,
            instagramPing = if (clearOtherTargets) -1 else old.instagramPing,
            youtubePing = if (clearOtherTargets) -1 else old.youtubePing,
            testedAt = if (result != null) System.currentTimeMillis() else 0L,
            testedUrl = result?.url.orEmpty(),
            testedFragment = result?.fragment ?: false,
            testedFragmentProfile = result?.profile ?: old.testedFragmentProfile
        )
        _servers.value = list
    }

    /** ثبت نتیجه‌ی پینگ چندگانه (گوگل/اینستاگرام/یوتیوب) */
    @Synchronized
    fun setMultiPing(
        id: String,
        google: Int,
        instagram: Int,
        youtube: Int,
        persist: Boolean = false,
        expectedFingerprint: String? = null
    ) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0 || (expectedFingerprint != null && list[idx].fingerprint() != expectedFingerprint)) return
        list[idx] = list[idx].copy(
            googlePing = google,
            instagramPing = instagram,
            youtubePing = youtube,
            realPing = if (google > 0) google else list[idx].realPing,
            testedAt = if (list[idx].realPing > 0) list[idx].testedAt else System.currentTimeMillis()
        )
        _servers.value = list
        if (persist) persistServers()
    }

    /** پاک‌کردن همه‌ی نتایج تست (برای «ری‌استارت جستجو») */
    @Synchronized
    fun clearTestResults() {
        _servers.value = _servers.value.map {
            it.copy(tcpPing = -1, realPing = -1, googlePing = -1, instagramPing = -1, youtubePing = -1, testedAt = 0L, testedUrl = "")
        }
        persistServers()
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean) {
        if (isStorageReadOnly()) return
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(favorite = favorite)
        _servers.value = list
        persistServers()
    }

    @Synchronized
    fun removeServer(id: String) {
        if (isStorageReadOnly()) return
        _servers.value = _servers.value.filterNot { it.id == id }
        persistServers()
        if (_selectedId.value == id) setSelected(_servers.value.firstOrNull()?.id.orEmpty())
    }

    @Synchronized
    fun removeServers(ids: Collection<String>) {
        if (isStorageReadOnly()) return
        val set = ids.toHashSet()
        _servers.value = _servers.value.filterNot { it.id in set }
        persistServers()
        if (_selectedId.value in set) setSelected(_servers.value.firstOrNull()?.id.orEmpty())
    }

    @Synchronized
    fun removeServersOfSubscription(subId: String) {
        if (isStorageReadOnly()) return
        _servers.value = _servers.value.filterNot { it.subId == subId }
        persistServers()
        if (serverById(_selectedId.value) == null) setSelected(_servers.value.firstOrNull()?.id.orEmpty())
    }

    // ----------------------------------------------------------- subscriptions

    fun subById(id: String): Subscription? = _subs.value.firstOrNull { it.id == id }

    @Synchronized
    fun addSubscription(sub: Subscription): Boolean {
        if (isStorageReadOnly()) return false
        _subs.value = _subs.value + sub
        persistSubs()
        return true
    }

    /** Discard a completed download if a restore/reset superseded its request. */
    @Synchronized
    fun addFetchedSubscription(sub: Subscription, incoming: List<ServerConfig>, expectedEpoch: Long): Boolean {
        if (isStorageReadOnly() || expectedEpoch != dataEpoch) return false
        if (!addSubscription(sub)) return false
        replaceSubscriptionServers(sub.id, incoming.map { it.copy(subId = sub.id) })
        return true
    }

    @Synchronized
    fun updateSubscription(sub: Subscription) {
        if (isStorageReadOnly()) return
        val list = _subs.value.toMutableList()
        val idx = list.indexOfFirst { it.id == sub.id }
        if (idx < 0) return
        list[idx] = sub
        _subs.value = list
        persistSubs()
    }

    private fun sameRefreshSource(current: Subscription, expected: Subscription): Boolean =
        current.id == expected.id && current.url == expected.url &&
            current.builtIn == expected.builtIn && current.updatedAt == expected.updatedAt

    /**
     * Apply a response only if no other request refreshed or edited this source
     * since it was started. Both list and metadata change under the same lock.
     */
    @Synchronized
    fun commitSubscriptionRefresh(
        expected: Subscription,
        incoming: List<ServerConfig>,
        upload: Long,
        download: Long,
        total: Long,
        expireAt: Long,
        expectedEpoch: Long? = null,
        automaticPermit: AutomaticRefreshPermit? = null
    ): Boolean {
        // Check under the same lock used by saveSettings and restore/reset.
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch) ||
            (automaticPermit != null && !automaticRefreshIsCurrent(automaticPermit))) return false
        val current = subById(expected.id) ?: return false
        if (!sameRefreshSource(current, expected) || incoming.isEmpty()) return false
        val prepared = incoming.map { it.copy(subId = current.id) }
        replaceSubscriptionServers(current.id, prepared)
        updateSubscription(current.copy(
            // Two successful responses in the same millisecond still have distinct revisions.
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1),
            count = prepared.size, upload = upload, download = download,
            total = total, expireAt = expireAt, lastError = ""
        ))
        return true
    }

    @Synchronized
    fun recordSubscriptionRefreshError(
        expected: Subscription, message: String, expectedEpoch: Long? = null,
        automaticPermit: AutomaticRefreshPermit? = null
    ) {
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch) ||
            (automaticPermit != null && !automaticRefreshIsCurrent(automaticPermit))) return
        val current = subById(expected.id) ?: return
        if (sameRefreshSource(current, expected)) updateSubscription(current.copy(lastError = message))
    }

    @Synchronized
    fun removeSubscription(id: String) {
        if (isStorageReadOnly()) return
        _subs.value = _subs.value.filterNot { it.id == id }
        persistSubs()
        removeServersOfSubscription(id)
    }

    /** Startup uses a small default set; the source picker can request all. */
    @Synchronized
    fun ensureBuiltInSources(
        includeAll: Boolean = false, expectedEpoch: Long? = null, requireEnabled: Boolean = false
    ): Int {
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch) ||
            (requireEnabled && !_settings.value.useFreeSources)) return 0
        val known = _subs.value.map { PublicSources.normalize(it.url) }.toHashSet()
        val catalogue = if (includeAll) PublicSources.ALL else PublicSources.DEFAULT
        val toAdd = catalogue.filter { PublicSources.normalize(it.url) !in known }.map {
            Subscription(name = it.name, url = it.url, builtIn = true)
        }
        if (toAdd.isEmpty()) return 0
        _subs.value = _subs.value + toAdd
        persistSubs()
        return toAdd.size
    }

    /** Only an explicit catalogue selection may create a built-in source. */
    @Synchronized
    fun addBuiltInSource(url: String, expectedEpoch: Long? = null): Subscription? {
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch)) return null
        val source = PublicSources.forUrl(url) ?: return null
        val normalized = PublicSources.normalize(source.url)
        if (_subs.value.any { PublicSources.normalize(it.url) == normalized }) return null
        val sub = Subscription(name = source.name, url = source.url, builtIn = true)
        _subs.value = _subs.value + sub
        persistSubs()
        return sub.copy()
    }

    /** حذف کانفیگ‌ها و اشتراک‌های منابع رایگان داخلی */
    @Synchronized
    fun removeBuiltInSources() {
        if (isStorageReadOnly()) return
        val ids = _subs.value.filter { it.builtIn }.map { it.id }.toHashSet()
        if (ids.isEmpty()) return
        _subs.value = _subs.value.filterNot { it.id in ids }
        _servers.value = _servers.value.filterNot { it.subId in ids }
        persistSubs()
        persistServers()
        if (serverById(_selectedId.value) == null) {
            setSelected(_servers.value.firstOrNull()?.id.orEmpty())
        }
    }

    fun builtInSummary(): Pair<Int, Int> {
        val subs = _subs.value.filter { it.builtIn }
        val ids = subs.map { it.id }.toHashSet()
        return subs.size to _servers.value.count { it.subId in ids }
    }

    /**
     * حذف تکراری‌های سراسری بین همه‌ی اشتراک‌ها.
     * از هر گروه، کانفیگ علاقه‌مندی/سالم‌تر نگه داشته می‌شود.
     */
    @Synchronized
    fun dedupeAllServers(expectedEpoch: Long? = null, automaticPermit: AutomaticRefreshPermit? = null): Int {
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch) ||
            (automaticPermit != null && !automaticRefreshIsCurrent(automaticPermit))) return 0
        val all = _servers.value
        if (all.size < 2) return 0
        val liveId = tunnel.value.serverId.takeIf { tunnel.value.isActive }
        val selected = _selectedId.value
        val groups = all.groupBy { it.fingerprint() }
        val survivors = groups.values.flatMap { group ->
            // If two identical profiles are independently live and selected,
            // retain both identities; otherwise a refresh can erase the user's choice.
            val protected = group.filter { it.favorite || it.id == liveId || (selected.isNotBlank() && it.id == selected) }
            if (protected.isNotEmpty()) protected else listOf(group.reduce { current, cfg ->
                when {
                    cfg.favorite != current.favorite -> if (cfg.favorite) cfg else current
                    cfg.bestPing() > 0 && (current.bestPing() <= 0 || cfg.bestPing() < current.bestPing()) -> cfg
                    else -> current
                }
            })
        }
        if (survivors.size == all.size) return 0
        val removed = all.size - survivors.size
        _servers.value = survivors
        persistServers()
        if (survivors.none { it.id == _selectedId.value }) {
            setSelected(survivors.firstOrNull()?.id.orEmpty())
        }
        return removed
    }

    /** ثبت نتیجه‌ی سنجش سرعت */
    @Synchronized
    fun setSpeed(id: String, kbps: Int, persist: Boolean = false, expectedFingerprint: String? = null) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0 || (expectedFingerprint != null && list[idx].fingerprint() != expectedFingerprint)) return
        list[idx] = list[idx].copy(speedKbps = kbps)
        _servers.value = list
        if (persist) persistServers()
    }

    /** ثبت اتصال موفق/ناموفق برای محاسبه‌ی پایداری */
    @Synchronized
    fun recordOutcome(id: String, success: Boolean) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cfg = list[idx]
        list[idx] = if (success) {
            cfg.copy(successCount = cfg.successCount + 1)
        } else {
            cfg.copy(failCount = cfg.failCount + 1)
        }
        _servers.value = list
        persistServers()
    }

    /**
     * محدود کردن حجم داده‌ها: اگر تعداد کانفیگ‌های منابع رایگان از حد گذشت،
     * ضعیف‌ترین/قدیمی‌ترین آن‌ها حذف می‌شوند.
     */
    @Synchronized
    fun trimBuiltInServers(
        maxTotal: Int = 3000, expectedEpoch: Long? = null, automaticPermit: AutomaticRefreshPermit? = null
    ): Int {
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch) ||
            (automaticPermit != null && !automaticRefreshIsCurrent(automaticPermit))) return 0
        val all = _servers.value
        if (all.size <= maxTotal) return 0
        val builtInIds = _subs.value.filter { it.builtIn }.map { it.id }.toHashSet()
        val now = System.currentTimeMillis()
        val liveId = tunnel.value.serverId.takeIf { tunnel.value.isActive }
        val removable = all
            .filter { it.subId in builtInIds && !it.favorite && it.id != _selectedId.value && it.id != liveId }
            .sortedWith(
                compareBy<ServerConfig> { if (it.bestPing() > 0) 1 else 0 }
                    .thenByDescending { now - it.testedAt }
                    .thenByDescending { it.addedAt }
            )
        val toRemove = (all.size - maxTotal).coerceAtMost(removable.size)
        if (toRemove <= 0) return 0
        val removeIds = removable.take(toRemove).map { it.id }.toHashSet()
        _servers.value = all.filterNot { it.id in removeIds }
        persistServers()
        return toRemove
    }

    /** بهترین سرورهای سالم و تازه‌تست‌شده (برای سوییچ سریع) */
    fun healthyServers(maxAgeMs: Long = 30 * 60 * 1000L): List<ServerConfig> {
        val now = System.currentTimeMillis()
        return _servers.value
            .filter { it.realPing > 0 && it.testedUrl.isNotBlank() && now - it.testedAt in 0..maxAgeMs }
            .sortedBy { it.score() }
    }

    // ------------------------------------------------------ network profiles

    private const val KEY_PROFILES = "network_profiles_v1"

    private val _profiles = MutableStateFlow<Map<String, NetworkProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, NetworkProfile>> = _profiles.asStateFlow()

    private fun loadProfiles() {
        _profiles.value = runCatching {
            prefs?.getString(KEY_PROFILES, null)?.let(Migrations::healProfiles) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun persistProfiles() {
        if (isStorageReadOnly()) return
        prefs?.edit()?.putString(KEY_PROFILES, gson.toJson(_profiles.value))?.apply()
    }

    fun profileFor(key: String): NetworkProfile? = _profiles.value[key]

    @Synchronized
    fun saveProfile(profile: NetworkProfile) {
        if (profile.key.isBlank()) return
        val map = _profiles.value.toMutableMap()
        map[profile.key] = profile
        _profiles.value = map
        persistProfiles()
    }

    @Synchronized
    fun removeProfile(key: String) {
        val map = _profiles.value.toMutableMap()
        map.remove(key)
        _profiles.value = map
        persistProfiles()
    }

    fun clearProfiles() {
        _profiles.value = emptyMap()
        persistProfiles()
    }

    // ------------------------------------------------------- update check meta

    @Synchronized
    fun lastUpdateCheck(): Long = runCatching {
        if (prefs?.getString(KEY_UPDATE_CHECK_VERSION, "") == BuildConfig.VERSION_NAME) {
            prefs?.getLong(KEY_LAST_UPDATE_CHECK, 0L) ?: 0L
        } else 0L
    }.getOrDefault(0L)

    @Synchronized
    fun markUpdateChecked() {
        prefs?.edit()?.putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis())
            ?.putString(KEY_UPDATE_CHECK_VERSION, BuildConfig.VERSION_NAME)?.apply()
    }

    @Synchronized
    fun saveUpdateInfo(info: UpdateInfo) {
        update.value = info
        prefs?.edit()?.putString(KEY_UPDATE_INFO, gson.toJson(info))
            ?.putString(KEY_UPDATE_INFO_VERSION, BuildConfig.VERSION_NAME)?.apply()
    }

    @Synchronized
    fun lastNotifiedUpdate(): String = runCatching { prefs?.getString(KEY_UPDATE_NOTIFIED, "").orEmpty() }.getOrDefault("")

    @Synchronized
    fun markUpdateNotified(version: String) {
        prefs?.edit()?.putString(KEY_UPDATE_NOTIFIED, version)?.apply()
    }

    /**
     * Atomically gate automatic results against a setting change. The short
     * notification callback runs under the same lock so disabling checks can
     * never be followed by a late notification from an already-finished check.
     */
    @Synchronized
    internal fun beginAutomaticUpdateCheck(): Long? =
        automaticUpdateEpoch.takeIf { _settings.value.autoUpdateCheck }

    @Synchronized
    internal fun automaticUpdateCheckIsCurrent(epoch: Long): Boolean =
        _settings.value.autoUpdateCheck && automaticUpdateEpoch == epoch

    @Synchronized
    internal fun completeUpdateCheck(info: UpdateInfo, automaticEpoch: Long?, onApplied: (UpdateInfo) -> Unit): Boolean {
        if (automaticEpoch != null && !automaticUpdateCheckIsCurrent(automaticEpoch)) return false
        saveUpdateInfo(info)
        markUpdateChecked()
        runCatching { onApplied(info) }
        return true
    }

    // ---------------------------------------------------------------- settings

    @Synchronized
    fun saveSettings(s: AppSettings): Boolean {
        if (isStorageReadOnly()) return false
        val normalized = if (s.appLock && s.allowExternalAutomation) s.copy(allowExternalAutomation = false) else s
        val old = _settings.value
        val lockChanged = old.appLock != normalized.appLock
        if (old.allowExternalAutomation != normalized.allowExternalAutomation || lockChanged) {
            // Do not resurrect device-local consent after a lock or restore.
            val control = controlPrefs
            if ((control == null && loaded) ||
                (control != null && !persistControlConsent(control, normalized.allowExternalAutomation))) return false
        }
        if (lockChanged) {
            // The UI must not claim a lock was enabled if its settings write
            // was only queued and the process died before it reached disk.
            val storage = prefs
            val encoded = runCatching { gson.toJson(normalized) }.getOrNull()
            val written = encoded != null && (storage != null && persistLockSettings(storage, encoded) ||
                storage == null && !loaded)
            if (!written) {
                // Consent may already have been durably revoked in the other file.
                if (old.allowExternalAutomation && !normalized.allowExternalAutomation) {
                    _settings.value = old.copy(allowExternalAutomation = false)
                }
                return false
            }
        }
        if (old.autoUpdateCheck != normalized.autoUpdateCheck) automaticUpdateEpoch++
        if (old.autoRefreshOnOpen != normalized.autoRefreshOnOpen || old.autoUpdateSubs != normalized.autoUpdateSubs) {
            automaticRefreshEpoch++
        }
        _settings.value = normalized
        if (!lockChanged) persistSettings()
        return true
    }

    @Synchronized
    fun updateSettings(block: (AppSettings) -> AppSettings): Boolean {
        if (isStorageReadOnly()) return false
        return saveSettings(block(_settings.value))
    }

    // ------------------------------------------------------------------- misc

    // --------------------------------------------------------- backup/restore

    @Synchronized
    fun exportBackup(): String = gson.toJson(
        BackupPayload(
            servers = _servers.value,
            subs = _subs.value,
            settings = _settings.value,
            selectedId = _selectedId.value
        )
    )

    /** Call from an I/O dispatcher: restore succeeds only after durable storage confirms the write. */
    fun restoreBackup(json: String, expectedEpoch: Long? = null): Boolean {
        val storage = synchronized(this) { prefs } ?: return false
        return restoreBackup(json, expectedEpoch) { servers, subscriptions, settings, selected ->
            persistBackup(storage, servers, subscriptions, settings, selected)
        }
    }

    /** A failed commit may still change SharedPreferences' in-memory map. */
    private fun durableTransaction(
        storage: SharedPreferences, keys: List<String>, change: (SharedPreferences.Editor) -> Unit
    ): Boolean {
        val before = runCatching { storage.all.toMap() }.getOrNull() ?: return false
        val written = runCatching {
            val editor = storage.edit()
            change(editor)
            // Force a real write even on a retry after a failed commit whose
            // changes remain in the process-local SharedPreferences cache.
            var marker: String
            do { marker = java.util.UUID.randomUUID().toString() }
            while (marker == before[KEY_TRANSACTION_MARKER])
            editor.putString(KEY_TRANSACTION_MARKER, marker).commit()
        }.getOrDefault(false)
        if (!written) runCatching {
            val editor = storage.edit()
            (keys + KEY_TRANSACTION_MARKER).distinct().forEach { key ->
                when (val value = before[key]) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            // Best-effort rollback of memory; a disk failure cannot be promised
            // away and the operation is always reported as unsuccessful.
            editor.apply()
        }
        return written
    }

    internal fun persistBackup(
        storage: SharedPreferences, servers: String, subscriptions: String, settings: String, selected: String
    ): Boolean = durableTransaction(storage, listOf(KEY_SERVERS, KEY_SUBS, KEY_SETTINGS, KEY_SELECTED)) { editor ->
        // Older recovery copies may contain records not present in this backup.
        // Preserve them until the user explicitly resets all data.
        editor.putString(KEY_SERVERS, servers).putString(KEY_SUBS, subscriptions)
            .putString(KEY_SETTINGS, settings).putString(KEY_SELECTED, selected)
    }

    internal fun persistReset(storage: SharedPreferences, settings: String): Boolean {
        val dataKeys = listOf(KEY_SERVERS, KEY_SUBS, KEY_SETTINGS, KEY_SELECTED)
        val legacyKeys = StoredDataRecovery.legacyArchiveKeys(*dataKeys.toTypedArray())
        return durableTransaction(storage, dataKeys + KEY_PROFILES + legacyKeys) { editor ->
            editor.putString(KEY_SERVERS, "[]").putString(KEY_SUBS, "[]")
                .putString(KEY_SETTINGS, settings).putString(KEY_SELECTED, "").remove(KEY_PROFILES)
            StoredDataRecovery.removeLegacyArchives(editor, *dataKeys.toTypedArray())
        }
    }

    internal fun persistControlConsent(storage: SharedPreferences, enabled: Boolean): Boolean =
        durableTransaction(storage, listOf(KEY_EXTERNAL_AUTOMATION)) {
            it.putBoolean(KEY_EXTERNAL_AUTOMATION, enabled)
        }

    internal fun persistLockSettings(storage: SharedPreferences, settings: String): Boolean =
        durableTransaction(storage, listOf(KEY_SETTINGS)) { it.putString(KEY_SETTINGS, settings) }

    private fun revokeControlConsent(storage: SharedPreferences): Boolean =
        durableTransaction(storage, listOf(KEY_EXTERNAL_AUTOMATION)) { it.remove(KEY_EXTERNAL_AUTOMATION) }

    private data class PreparedBackup(
        val payload: BackupPayload, val servers: String, val subscriptions: String, val selected: String
    )

    /** Parse and encode potentially large, untrusted input without holding the UI's repository lock. */
    private fun prepareBackup(json: String): PreparedBackup? = runCatching {
        val payload = Migrations.decodeBackup(json) ?: return@runCatching null
        if (payload.servers.isEmpty() && payload.subs.isEmpty()) return@runCatching null
        val selected = payload.selectedId.takeIf { id -> payload.servers.any { it.id == id } }
            ?: payload.servers.firstOrNull()?.id.orEmpty()
        PreparedBackup(payload, gson.toJson(payload.servers), gson.toJson(payload.subs), selected)
    }.getOrNull()

    /** Storage is injectable so failure and all-or-nothing publication can be tested without Android. */
    internal fun restoreBackup(
        json: String, expectedEpoch: Long? = null, persist: (String, String, String, String) -> Boolean
    ): Boolean {
        if (expectedEpoch != null && expectedEpoch != currentDataEpoch()) return false
        val prepared = prepareBackup(json) ?: return false
        return restorePreparedBackup(prepared, expectedEpoch, persist)
    }

    @Synchronized
    private fun restorePreparedBackup(
        prepared: PreparedBackup, expectedEpoch: Long?, persist: (String, String, String, String) -> Boolean
    ): Boolean {
        if (expectedEpoch != null && expectedEpoch != dataEpoch) return false
        val payload = prepared.payload
        // Backups cannot grant this permission. Preserve a previously granted
        // local consent only when the restored settings do not enable app lock.
        val restoredSettings = payload.settings.copy(
            allowExternalAutomation = _settings.value.allowExternalAutomation && !payload.settings.appLock
        )
        val encodedSettings = runCatching { gson.toJson(restoredSettings) }.getOrNull() ?: return false
        if (!restoredSettings.allowExternalAutomation) {
            val control = controlPrefs
            if ((control == null && loaded) || (control != null && !persistControlConsent(control, false))) return false
            // Revocation stays in force even if the following backup write fails.
            if (_settings.value.allowExternalAutomation) {
                _settings.value = _settings.value.copy(allowExternalAutomation = false)
            }
        }
        // One transaction replaces all sections, even during suspended list writes.
        if (!runCatching { persist(prepared.servers, prepared.subscriptions, encodedSettings, prepared.selected) }.getOrDefault(false)) {
            return false
        }
        // An explicit successful restore replaces the damaged sections and
        // invalidates any older in-flight import/refresh.
        dataEpoch++
        canWriteServers = true
        canWriteSubs = true
        canWriteSettings = true
        recoveryWarning.value = ""
        _servers.value = payload.servers
        _subs.value = payload.subs
        if (_settings.value.autoUpdateCheck != restoredSettings.autoUpdateCheck) automaticUpdateEpoch++
        _settings.value = restoredSettings
        _selectedId.value = prepared.selected
        return true
    }

    /** Call on an I/O dispatcher; never claim deletion when a commit failed. */
    @Synchronized
    fun resetEverything(): Boolean {
        val storage = prefs ?: return false
        val control = controlPrefs ?: return false
        return resetEverything(
            revokeConsent = { revokeControlConsent(control) },
            persist = { settings -> persistReset(storage, gson.toJson(settings)) }
        )
    }

    /** Injectable transactions make both partial-failure cases testable. */
    @Synchronized
    internal fun resetEverything(revokeConsent: () -> Boolean, persist: (AppSettings) -> Boolean): Boolean {
        // Revoke device-local automation before resetting a possible app lock.
        if (!runCatching { revokeConsent() }.getOrDefault(false)) return false
        if (_settings.value.allowExternalAutomation) {
            _settings.value = _settings.value.copy(allowExternalAutomation = false)
        }
        // Resetting must not silently start fetching public sources or connecting.
        val defaults = AppSettings(
            autoConnectOnOpen = false, autoConnectOnBoot = false,
            autoRefreshOnOpen = false, autoUpdateSubs = false, useFreeSources = false,
            allowExternalAutomation = false
        )
        if (!runCatching { persist(defaults) }.getOrDefault(false)) return false
        dataEpoch++
        canWriteServers = true
        canWriteSubs = true
        canWriteSettings = true
        recoveryWarning.value = ""
        _servers.value = emptyList()
        _subs.value = emptyList()
        _selectedId.value = ""
        _profiles.value = emptyMap()
        if (_settings.value.autoUpdateCheck != defaults.autoUpdateCheck) automaticUpdateEpoch++
        _settings.value = defaults
        return true
    }
}
