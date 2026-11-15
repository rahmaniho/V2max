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
    // Open editors are protected from subscription refresh, deduplication and
    // automatic cleanup. This is process-local, not part of a portable backup.
    private val editingServerIds = HashMap<String, Int>()
    private var startupHealthEpoch = 0L

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
    fun init(context: Context) = initializeSafely { loadStoredData(context) }

    /** A storage access failure must not crash the Activity or enable automation. */
    @Synchronized
    internal fun initializeSafely(load: () -> Unit) {
        if (loaded) return
        try {
            load()
        } catch (_: Exception) {
            prefs = null
            controlPrefs = null
            canWriteServers = false
            canWriteSubs = false
            canWriteSettings = false
            dataEpoch++
            startupHealthEpoch++
            editingServerIds.clear()
            automaticUpdateEpoch++
            automaticRefreshEpoch++
            _servers.value = emptyList()
            _subs.value = emptyList()
            _selectedId.value = ""
            _profiles.value = emptyMap()
            _settings.value = Migrations.safeSettingsAfterFailure(null)
            update.value = UpdateInfo()
            loaded = false // A later Activity or service may retry once storage is accessible.
            recoveryWarning.value = "دسترسی به داده‌های برنامه ممکن نشد؛ ویرایش و کارهای خودکار متوقف شدند. " +
                "برای تلاش دوباره برنامه را باز کنید و فضای ذخیره‌سازی دستگاه را بررسی کنید."
        }
    }

    private fun loadStoredData(context: Context) {
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
        } else {
            recoveryWarning.value = ""
        }
        val restoredSettings = StoredDataRecovery.guardSettings(savedSettings.value, damaged)
        _settings.value = restoredSettings.copy(
            allowExternalAutomation = effectiveControlConsent(control, damaged, restoredSettings.appLock)
        )
        _selectedId.value = runCatching { p.getString(KEY_SELECTED, "") }.getOrNull().orEmpty()
        // A pending update was compared against a particular installed version.
        // Do not show it after the package itself has been upgraded.
        update.value = if (runCatching { p.getString(KEY_UPDATE_INFO_VERSION, "") }.getOrNull() == BuildConfig.VERSION_NAME) {
            runCatching { p.getString(KEY_UPDATE_INFO, null) }.getOrNull()
                ?.let { Migrations.heal(it, UpdateInfo()) } ?: UpdateInfo()
        } else UpdateInfo()
        loadProfiles()
        loaded = true
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

    @Synchronized
    fun beginServerEdit(id: String): Boolean {
        if (_servers.value.none { it.id == id }) return false
        editingServerIds[id] = (editingServerIds[id] ?: 0) + 1
        return true
    }

    @Synchronized
    fun endServerEdit(id: String) {
        val count = editingServerIds[id] ?: return
        if (count <= 1) editingServerIds.remove(id) else editingServerIds[id] = count - 1
    }

    /** Invalidate a pending cleanup before a newer user action can run. */
    @Synchronized
    fun invalidateStartupHealth() { startupHealthEpoch++ }

    @Synchronized
    fun startupHealthToken(): Long = startupHealthEpoch

    @Synchronized
    fun startupHealthIsCurrent(token: Long): Boolean = token == startupHealthEpoch

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

    // Keep a bounded sample from each represented source when older feeds have
    // filled the public catalogue. The policy apportions this against capacity.
    private const val MAX_SOURCE_RESERVE = 50

    private fun capped(
        list: List<ServerConfig>, limit: Int = ServerListPolicy.MAX_BUILT_IN_SERVERS
    ): List<ServerConfig> = ServerListPolicy.capBuiltIn(
        list = list,
        builtInSubscriptionIds = _subs.value.filter { it.builtIn }.map { it.id }.toSet(),
        selectedId = _selectedId.value,
        limit = limit,
        protectedIds = editingServerIds.keys +
            setOfNotNull(tunnel.value.serverId.takeIf { tunnel.value.isActive && it.isNotBlank() }) +
            list.filter { it.realPing > 0 && it.testedUrl.isNotBlank() }.map { it.id },
        sourceReserve = MAX_SOURCE_RESERVE,
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
    fun replaceSubscriptionServers(
        subId: String, incoming: List<ServerConfig>, deferBuiltInCap: Boolean = false,
        preserveOmitted: Boolean = false
    ) {
        if (isStorageReadOnly()) return
        // A delayed HTTP response must not resurrect a subscription removed by the user.
        val source = _subs.value.firstOrNull { it.id == subId } ?: return
        val existing = _servers.value.filter { it.subId == subId }
        // A partial response cannot prove its omitted members disappeared. Keep
        // the existing personal list; for public feeds bound the old unverified
        // history by its capacity policy while protecting verified profiles.
        // This per-source cap does not touch newly received profiles that are
        // waiting for startup's health test, even across multiple sources.
        val retained = if (!preserveOmitted) emptyList() else if (source.builtIn) capped(existing) else existing
        val merged = SubscriptionMerger.merge(
            existing = existing,
            incoming = incoming,
            liveId = tunnel.value.serverId.takeIf { tunnel.value.isActive },
            protectedIds = editingServerIds.keys + existing.filter { it.favorite }.map { it.id } +
                retained.map { it.id } + setOf(_selectedId.value).filter { it.isNotBlank() },
            fingerprintOf = { it.fingerprint() },
            isUsable = { it.hasUsableEndpoint() },
            idOf = { it.id },
            carryOver = { old, updated ->
                if (old.id in editingServerIds) old else carryOverExisting(old, updated)
            },
        )
        val others = _servers.value.filter { it.subId != subId }
        // During startup every received member must be available for its real
        // health check before a global capacity limit can discard anything.
        _servers.value = if (deferBuiltInCap) others + merged else capped(others + merged)
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

    /** Do not overwrite a profile changed by a refresh, another edit, or a restore. */
    @Synchronized
    fun saveEditedServer(edited: ServerConfig, expectedFingerprint: String, expectedEpoch: Long): Boolean {
        if (isStorageReadOnly() || expectedEpoch != dataEpoch) return false
        val list = _servers.value.toMutableList()
        val index = list.indexOfFirst { it.id == edited.id }
        if (index < 0) return false
        val current = list[index]
        if (current.fingerprint() != expectedFingerprint) return false
        val sameConnection = current.fingerprint() == edited.fingerprint()
        list[index] = edited.copy(
            subId = current.subId, favorite = current.favorite, addedAt = current.addedAt,
            countryCode = current.countryCode, countryName = current.countryName,
            tcpPing = if (sameConnection) current.tcpPing else -1,
            realPing = if (sameConnection) current.realPing else -1,
            googlePing = if (sameConnection) current.googlePing else -1,
            instagramPing = if (sameConnection) current.instagramPing else -1,
            youtubePing = if (sameConnection) current.youtubePing else -1,
            speedKbps = if (sameConnection) current.speedKbps else 0,
            successCount = if (sameConnection) current.successCount else 0,
            failCount = if (sameConnection) current.failCount else 0,
            testedAt = if (sameConnection) current.testedAt else 0L,
            testedUrl = if (sameConnection) current.testedUrl else "",
            testedFragment = sameConnection && current.testedFragment,
            testedFragmentProfile = if (sameConnection) current.testedFragmentProfile else FragmentProfile.BALANCED
        )
        _servers.value = list
        persistServers()
        return true
    }

    /** A clone is an independent personal profile, never a subscription-owned test result. */
    @Synchronized
    fun cloneEditedServer(copy: ServerConfig, sourceId: String, expectedFingerprint: String, expectedEpoch: Long): Boolean {
        if (isStorageReadOnly() || expectedEpoch != dataEpoch || copy.id.isBlank()) return false
        val current = _servers.value.firstOrNull { it.id == sourceId } ?: return false
        if (current.fingerprint() != expectedFingerprint || _servers.value.any { it.id == copy.id }) return false
        val clone = copy.copy(
            subId = "", favorite = false, addedAt = System.currentTimeMillis(),
            tcpPing = -1, realPing = -1, googlePing = -1, instagramPing = -1, youtubePing = -1,
            speedKbps = 0, successCount = 0, failCount = 0, testedAt = 0L, testedUrl = "",
            testedFragment = false, testedFragmentProfile = FragmentProfile.BALANCED
        )
        _servers.value = _servers.value + clone
        persistServers()
        setSelected(clone.id)
        return true
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
        if (result != null && result.delayMs > 0 && result.url.isNotBlank() && !isStorageReadOnly()) {
            val sub = subById(old.subId)
            if (sub != null && (sub.healthFailureChecks > 0 || sub.healthDismissedAt > 0)) {
                updateSubscription(sub.withHealthEvidence(ir.v2max.core.domain.SubscriptionHealthEvidence()))
            }
        }
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
        val previous = list[idx]
        // Failure history belongs to the source identity, not merely its ID.
        // A renamed, replaced, or reclassified source must earn fresh evidence.
        val sameIdentity = previous.id == sub.id && previous.url == sub.url &&
            previous.name == sub.name && previous.builtIn == sub.builtIn
        list[idx] = if (sameIdentity) sub else sub.withHealthEvidence(ir.v2max.core.domain.SubscriptionHealthEvidence())
        _subs.value = list
        persistSubs()
    }

    private fun sameRefreshSource(current: Subscription, expected: Subscription): Boolean =
        current.id == expected.id && current.url == expected.url && current.name == expected.name &&
            current.builtIn == expected.builtIn && current.updatedAt == expected.updatedAt

    private fun sameHealthCandidate(current: Subscription, expected: Subscription): Boolean =
        sameRefreshSource(current, expected) && current.healthEvidence() == expected.healthEvidence()

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
        automaticPermit: AutomaticRefreshPermit? = null,
        preserveOmitted: Boolean = false
    ): Boolean = commitSubscriptionRefreshSnapshot(
        expected, incoming, upload, download, total, expireAt, expectedEpoch, automaticPermit,
        preserveOmitted = preserveOmitted
    ) != null

    /** Return the actual committed revision atomically, not a later concurrent refresh. */
    @Synchronized
    fun commitSubscriptionRefreshSnapshot(
        expected: Subscription,
        incoming: List<ServerConfig>,
        upload: Long,
        download: Long,
        total: Long,
        expireAt: Long,
        expectedEpoch: Long? = null,
        automaticPermit: AutomaticRefreshPermit? = null,
        deferBuiltInCap: Boolean = false,
        startupToken: Long? = null,
        preserveOmitted: Boolean = false
    ): Subscription? {
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch) ||
            (startupToken != null && startupToken != startupHealthEpoch) ||
            (automaticPermit != null && !automaticRefreshIsCurrent(automaticPermit))) return null
        val current = subById(expected.id) ?: return null
        if (!sameRefreshSource(current, expected) || incoming.isEmpty()) return null
        val prepared = incoming.map { it.copy(subId = current.id) }
        replaceSubscriptionServers(
            current.id, prepared, deferBuiltInCap = deferBuiltInCap, preserveOmitted = preserveOmitted
        )
        val revision = current.copy(
            // Two successful responses in the same millisecond still have distinct revisions.
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1),
            count = prepared.size, upload = upload, download = download,
            total = total, expireAt = expireAt, lastError = ""
        )
        updateSubscription(revision)
        return revision.copy()
    }

    @Synchronized
    fun recordSubscriptionRefreshError(
        expected: Subscription, message: String, expectedEpoch: Long? = null,
        automaticPermit: AutomaticRefreshPermit? = null, startupToken: Long? = null
    ) {
        if (isStorageReadOnly() || (expectedEpoch != null && expectedEpoch != dataEpoch) ||
            (startupToken != null && startupToken != startupHealthEpoch) ||
            (automaticPermit != null && !automaticRefreshIsCurrent(automaticPermit))) return
        val current = subById(expected.id) ?: return
        if (sameRefreshSource(current, expected)) updateSubscription(current.copy(lastError = message))
    }

    /** Identities received before global deduplication/capping; incomplete feeds cannot prove total failure. */
    data class StartupSourceSnapshot(
        val subscription: Subscription,
        val fingerprints: Set<String>,
        val complete: Boolean = true
    )
    data class StartupObservation(
        val server: ServerConfig,
        val tcpPing: Int,
        val result: ir.v2max.app.net.ProbeResult?,
        /** False when any unsuccessful variant had a setup/unknown error. */
        val conclusive: Boolean = true
    )
    data class StartupHealthResult(
        val applied: Boolean = false,
        val tested: Int = 0,
        val healthy: Int = 0,
        val removed: Int = 0,
        val unknownSources: Int = 0
    )

    /**
     * Publish only a completed startup scan. Fingerprints carry credentials and
     * are strictly in-memory. A real success in this same scan is required before
     * any negative result can remove a subscription-owned profile. TCP failure,
     * a vanished profile, a changed source, or an incomplete scan is never proof.
     */
    @Synchronized
    fun applyStartupHealth(
        sources: List<StartupSourceSnapshot>,
        snapshots: List<ServerConfig>,
        observations: List<StartupObservation>,
        permit: AutomaticRefreshPermit,
        token: Long,
        stillCurrent: () -> Boolean,
        networkValidated: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): StartupHealthResult {
        if (isStorageReadOnly() || token != startupHealthEpoch || !automaticRefreshIsCurrent(permit) ||
            !stillCurrent()) return StartupHealthResult()
        val sourceById = sources.filter { expected ->
            subById(expected.subscription.id)?.let { sameRefreshSource(it, expected.subscription) } == true
        }.associateBy { it.subscription.id }
        // A stale concurrent refresh or user edit invalidates the whole pass:
        // callers must not auto-connect on the basis of a partially changed set.
        if (sourceById.isEmpty() || sourceById.size != sources.size) return StartupHealthResult()
        val currentById = _servers.value.associateBy { it.id }
        val valid = observations.filter { obs -> currentById[obs.server.id] == obs.server }
        // A running, selected, favourite, or edited record may deliberately be
        // kept even when the new feed no longer contains it. Its result cannot
        // prove that failures of the refreshed feed are safe to delete.
        val fromCurrentSources = valid.filter { obs ->
            sourceById[obs.server.subId]?.fingerprints?.contains(obs.server.fingerprint()) == true
        }
        // Never convert a failed local setup or an unfamiliar native error into
        // a server failure, even if another profile succeeded in this pass.
        val byFingerprint = fromCurrentSources.filter { obs ->
            obs.conclusive || (obs.result?.let { it.delayMs > 0 && it.url.isNotBlank() } == true)
        }.associateBy { it.server.fingerprint() }
        val networkProven = fromCurrentSources.any { it.result != null && it.result.delayMs > 0 && it.result.url.isNotBlank() }
        val flags = byFingerprint.mapValues { (_, obs) ->
            obs.result != null && obs.result.delayMs > 0 && obs.result.url.isNotBlank()
        }
        val originalById = snapshots.associateBy { it.id }
        // If an untested duplicate was edited while scanning, a shared result no
        // longer proves that duplicate or its subscription is unchanged.
        val changedFingerprints = snapshots.asSequence()
            .filter { it != currentById[it.id] || it.id in editingServerIds }
            .mapTo(HashSet()) { it.fingerprint() }
        val evidenceFlags = flags.filterKeys { it !in changedFingerprints }
        val liveId = tunnel.value.serverId.takeIf { tunnel.value.isActive }
        var removed = 0
        var changedServers = false
        if (networkProven) {
            val revised = _servers.value.mapNotNull { current ->
                val source = sourceById[current.subId]
                val key = current.fingerprint()
                val obs = byFingerprint[key]
                if (source == null || key !in source.fingerprints || obs == null ||
                    originalById[current.id] != current || current.id in editingServerIds) return@mapNotNull current
                val success = obs.result?.takeIf { it.delayMs > 0 && it.url.isNotBlank() }
                if (success == null && _settings.value.dropDeadServers &&
                    !current.favorite && current.id != _selectedId.value && current.id != liveId) {
                    removed++
                    changedServers = true
                    null
                } else {
                    changedServers = true
                    current.copy(
                        tcpPing = obs.tcpPing,
                        realPing = success?.delayMs ?: -1,
                        googlePing = if (success?.url == PingTargets.GOOGLE.url) success.delayMs else -1,
                        instagramPing = -1, youtubePing = -1,
                        testedAt = if (success != null) now else 0L,
                        testedUrl = success?.url.orEmpty(),
                        testedFragment = success?.fragment ?: false,
                        testedFragmentProfile = success?.profile ?: current.testedFragmentProfile
                    )
                }
            }
            if (changedServers) _servers.value = revised
        }
        var unknown = 0
        var changedSubs = false
        val revisedSubs = _subs.value.map { sub ->
            val source = sourceById[sub.id] ?: return@map sub
            // Android's validation plus a successful subscription fetch may
            // support a cautious prompt, but never automatic profile deletion.
            val observed = ir.v2max.core.domain.SubscriptionHealthPolicy.classify(
                source.fingerprints, evidenceFlags, networkProven || networkValidated
            )
            // One positive response establishes health, but failure of an
            // incomplete/truncated feed cannot describe its omitted members.
            val check = if (!source.complete && observed == ir.v2max.core.domain.SubscriptionCheck.NO_RESPONSE)
                ir.v2max.core.domain.SubscriptionCheck.UNKNOWN else observed
            if (check == ir.v2max.core.domain.SubscriptionCheck.UNKNOWN) unknown++
            val evidence = ir.v2max.core.domain.SubscriptionHealthPolicy.observe(sub.healthEvidence(), check, now, sub.updatedAt)
            val revised = sub.withHealthEvidence(evidence)
            if (revised != sub) changedSubs = true
            revised
        }
        if (changedSubs) _subs.value = revisedSubs
        if (changedServers) persistServers()
        if (changedSubs) persistSubs()
        return StartupHealthResult(true, valid.size, flags.values.count { it }, removed, unknown)
    }

    /** A prompt is only a suggestion. Revalidate it under lock on either action. */
    @Synchronized
    fun deferUnhealthySubscription(expected: Subscription, now: Long = System.currentTimeMillis()): Boolean {
        if (isStorageReadOnly()) return false
        val current = subById(expected.id) ?: return false
        if (!sameHealthCandidate(current, expected) || current.lastError.isNotBlank() ||
            !ir.v2max.core.domain.SubscriptionHealthPolicy.shouldSuggest(current.healthEvidence(), current.updatedAt, now)) return false
        updateSubscription(current.copy(healthDismissedAt = now))
        return true
    }

    @Synchronized
    fun removeUnhealthySubscription(expected: Subscription, now: Long = System.currentTimeMillis()): Boolean {
        if (isStorageReadOnly()) return false
        val current = subById(expected.id) ?: return false
        if (!sameHealthCandidate(current, expected) || current.lastError.isNotBlank() ||
            !ir.v2max.core.domain.SubscriptionHealthPolicy.shouldSuggest(current.healthEvidence(), current.updatedAt, now)) return false
        val owned = _servers.value.filter { it.subId == current.id }
        if (owned.any { it.id in editingServerIds || (tunnel.value.isActive && it.id == tunnel.value.serverId) }) return false
        removeSubscription(current.id)
        return true
    }

    @Synchronized
    fun removeSubscription(id: String) {
        if (isStorageReadOnly()) return
        val removed = subById(id)
        // An explicitly deleted default source must not be silently recreated at
        // next startup. The catalogue picker can add it again deliberately.
        val catalogue = removed?.takeIf { it.builtIn }?.let { PublicSources.forUrl(it.url) }
        if (catalogue != null) {
            val disabled = (_settings.value.disabledBuiltInSources + PublicSources.normalize(catalogue.url)).distinct()
            saveSettings(_settings.value.copy(disabledBuiltInSources = disabled))
        }
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
        val disabled = _settings.value.disabledBuiltInSources.map(PublicSources::normalize).toSet()
        val catalogue = if (includeAll) PublicSources.ALL else PublicSources.DEFAULT
        val toAdd = catalogue.filter {
            val url = PublicSources.normalize(it.url)
            url !in known && (includeAll || url !in disabled)
        }.map { Subscription(name = it.name, url = it.url, builtIn = true) }
        if (toAdd.isEmpty()) return 0
        if (includeAll) {
            val readded = toAdd.map { PublicSources.normalize(it.url) }.toSet()
            saveSettings(_settings.value.copy(disabledBuiltInSources =
                _settings.value.disabledBuiltInSources.filterNot { PublicSources.normalize(it) in readded }))
        }
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
        if (_settings.value.disabledBuiltInSources.any { PublicSources.normalize(it) == normalized }) {
            saveSettings(_settings.value.copy(disabledBuiltInSources =
                _settings.value.disabledBuiltInSources.filterNot { PublicSources.normalize(it) == normalized }))
        }
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
            val protected = group.filter {
                // Standalone imports belong to the user, not a refreshed feed.
                it.subId.isBlank() || it.favorite || it.id in editingServerIds ||
                    it.id == liveId || (selected.isNotBlank() && it.id == selected)
            }
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
     * ضعیف‌ترین/قدیمی‌ترین آن‌ها حذف می‌شوند. منابع شخصی نباید باعث شوند
     * نمونه‌های محافظت‌شدهٔ منابع عمومی نیز از فهرست پاک شوند.
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
        // Personal records are never trimmed. Preserve the normal public-source
        // capacity and its fair shares as well, even if personal imports alone
        // already exceed maxTotal. Protected data can make this a soft limit.
        val capacityKept = capped(all, minOf(maxTotal.coerceAtLeast(0), ServerListPolicy.MAX_BUILT_IN_SERVERS))
            .asSequence().filter { it.subId in builtInIds }.mapTo(HashSet()) { it.id }
        val now = System.currentTimeMillis()
        val liveId = tunnel.value.serverId.takeIf { tunnel.value.isActive }
        val removable = all
            .filter { it.subId in builtInIds && it.id !in capacityKept && !it.favorite && it.id !in editingServerIds &&
                it.id != _selectedId.value && it.id != liveId &&
                !(it.realPing > 0 && it.testedUrl.isNotBlank()) }
            .sortedWith(
                compareBy<ServerConfig> { if (it.bestPing() > 0) 1 else 0 }
                    .thenByDescending { now - it.testedAt }
                    .thenByDescending { it.addedAt }
            )
        val toRemove = (all.size - maxTotal.coerceAtLeast(0)).coerceAtMost(removable.size)
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
            // Failure history is tied to this device/network, not a portable fact.
            subs = _subs.value.map { it.withHealthEvidence(ir.v2max.core.domain.SubscriptionHealthEvidence()) },
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

    /** A failed revocation must not let an old consent reappear after a restart. */
    internal fun effectiveControlConsent(storage: SharedPreferences, damaged: Boolean, appLock: Boolean): Boolean {
        val consent = runCatching { storage.getBoolean(KEY_EXTERNAL_AUTOMATION, false) }.getOrDefault(false)
        if (consent && (damaged || appLock)) {
            check(persistControlConsent(storage, false)) { "Could not revoke external automation consent" }
            return false
        }
        return consent
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
        val decoded = Migrations.decodeBackup(json) ?: return@runCatching null
        val payload = decoded.copy(subs = decoded.subs.map {
            it.withHealthEvidence(ir.v2max.core.domain.SubscriptionHealthEvidence())
        })
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
        startupHealthEpoch++
        editingServerIds.clear()
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
        startupHealthEpoch++
        editingServerIds.clear()
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
