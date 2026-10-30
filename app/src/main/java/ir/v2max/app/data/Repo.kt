package ir.v2max.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subs: StateFlow<List<Subscription>> = _subs.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

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
        val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        // خواندن با مهاجرت ساختار داده (پر کردن کلیدهای جدید با مقدار پیش‌فرض)
        _servers.value = p.getString(KEY_SERVERS, null)?.let {
            Migrations.healList(it, ServerConfig())
        } ?: readList<ServerConfig>(null, object : TypeToken<List<ServerConfig>>() {}.type)
        _subs.value = readList<Subscription>(
            p.getString(KEY_SUBS, null),
            object : TypeToken<List<Subscription>>() {}.type
        )
        _settings.value = runCatching {
            p.getString(KEY_SETTINGS, null)?.let {
                Migrations.heal(it, AppSettings()) ?: gson.fromJson(it, AppSettings::class.java)
            }
        }.getOrNull() ?: AppSettings()
        _selectedId.value = p.getString(KEY_SELECTED, "") ?: ""
        loaded = true
        loadProfiles()
    }

    /** کشور سرور را از نامش تشخیص می‌دهد (آفلاین) */
    private fun withCountry(cfg: ServerConfig): ServerConfig {
        if (cfg.countryCode.isNotBlank()) return cfg
        val resolved = CountryFlags.resolve(cfg.name) ?: return cfg
        cfg.countryCode = resolved.first
        cfg.countryName = resolved.second
        return cfg
    }

    private fun <T> readList(json: String?, type: java.lang.reflect.Type): List<T> = runCatching {
        if (json.isNullOrBlank()) emptyList() else gson.fromJson<List<T>>(json, type) ?: emptyList()
    }.getOrDefault(emptyList())

    @Volatile
    private var persistSuspended = false

    /** در زمان به‌روزرسانی انبوه، نوشتن روی دیسک را موقتاً متوقف می‌کند */
    fun suspendPersist(suspended: Boolean) {
        persistSuspended = suspended
    }

    /** ذخیره‌ی فوری روی دیسک */
    fun persistNow() {
        persistServers()
        persistSubs()
    }

    private fun persistServers() {
        if (persistSuspended) return
        prefs?.edit()?.putString(KEY_SERVERS, gson.toJson(_servers.value))?.apply()
    }

    private fun persistSubs() {
        if (persistSuspended) return
        prefs?.edit()?.putString(KEY_SUBS, gson.toJson(_subs.value))?.apply()
    }

    private fun persistSettings() {
        if (persistSuspended) return
        prefs?.edit()?.putString(KEY_SETTINGS, gson.toJson(_settings.value))?.apply()
    }

    // ---------------------------------------------------------------- servers

    fun serverById(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    fun selectedServer(): ServerConfig? {
        val id = _selectedId.value
        return _servers.value.firstOrNull { it.id == id } ?: _servers.value.firstOrNull()
    }

    fun setSelected(id: String) {
        _selectedId.value = id
        prefs?.edit()?.putString(KEY_SELECTED, id)?.apply()
    }

    /**
     * افزودن کانفیگ‌های جدید با حذف تکراری‌ها.
     * @return تعداد کانفیگ‌های افزوده‌شده
     */
    @Synchronized
    fun addServers(incoming: List<ServerConfig>): Int {
        if (incoming.isEmpty()) return 0
        val current = _servers.value.toMutableList()
        val known = current.map { it.fingerprint() }.toHashSet()
        var added = 0
        incoming.filter { it.hasUsableEndpoint() }.map { withCountry(it) }.forEach { cfg ->
            val key = cfg.fingerprint()
            if (known.add(key)) {
                current.add(cfg)
                added++
            }
        }
        if (added > 0) {
            _servers.value = current
            persistServers()
            if (_selectedId.value.isBlank()) setSelected(current.lastOrNull()?.id.orEmpty())
        }
        return added
    }

    /**
     * جایگزینی هوشمند سرورهای یک اشتراک.
     * کانفیگ‌های موجود (بر اساس اثر انگشت) با حفظ شناسه، علاقه‌مندی و نتیجه تست نگه داشته می‌شوند.
     */
    @Synchronized
    fun replaceSubscriptionServers(subId: String, incoming: List<ServerConfig>) {
        val existing = _servers.value.filter { it.subId == subId }
        val byKey = existing.associateBy { it.fingerprint() }
        val merged = mutableListOf<ServerConfig>()
        val seen = HashSet<String>()
        incoming.filter { it.hasUsableEndpoint() }.forEach { cfg ->
            val key = cfg.fingerprint()
            if (!seen.add(key)) return@forEach
            val old = byKey[key]
            merged.add(
                if (old != null) {
                    cfg.id = old.id
                    cfg.favorite = old.favorite
                    cfg.tcpPing = old.tcpPing
                    cfg.realPing = old.realPing
                    cfg.googlePing = old.googlePing
                    cfg.instagramPing = old.instagramPing
                    cfg.youtubePing = old.youtubePing
                    cfg.speedKbps = old.speedKbps
                    cfg.countryCode = old.countryCode
                    cfg.countryName = old.countryName
                    cfg.successCount = old.successCount
                    cfg.failCount = old.failCount
                    cfg.testedAt = old.testedAt
                    cfg.addedAt = old.addedAt
                    cfg
                } else cfg
            )
        }
        val others = _servers.value.filter { it.subId != subId }
        _servers.value = others + merged
        persistServers()
        if (serverById(_selectedId.value) == null) {
            setSelected(_servers.value.firstOrNull()?.id.orEmpty())
        }
    }

    @Synchronized
    fun updateServer(server: ServerConfig) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        _servers.value = list
        persistServers()
    }

    @Synchronized
    fun updateServers(updated: Collection<ServerConfig>) {
        if (updated.isEmpty()) return
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

    /** ثبت نتیجه‌ی پینگ چندگانه (گوگل/اینستاگرام/یوتیوب) */
    @Synchronized
    fun setMultiPing(
        id: String,
        google: Int,
        instagram: Int,
        youtube: Int,
        persist: Boolean = false
    ) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(
            googlePing = google,
            instagramPing = instagram,
            youtubePing = youtube,
            realPing = google,
            testedAt = System.currentTimeMillis()
        )
        _servers.value = list
        if (persist) persistServers()
    }

    /** پاک‌کردن همه‌ی نتایج تست (برای «ری‌استارت جستجو») */
    @Synchronized
    fun clearTestResults() {
        _servers.value = _servers.value.map {
            it.copy(tcpPing = -1, realPing = -1, googlePing = -1, instagramPing = -1, youtubePing = -1, testedAt = 0L)
        }
        persistServers()
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(favorite = favorite)
        _servers.value = list
        persistServers()
    }

    @Synchronized
    fun removeServer(id: String) {
        _servers.value = _servers.value.filterNot { it.id == id }
        persistServers()
        if (_selectedId.value == id) setSelected(_servers.value.firstOrNull()?.id.orEmpty())
    }

    @Synchronized
    fun removeServers(ids: Collection<String>) {
        val set = ids.toHashSet()
        _servers.value = _servers.value.filterNot { it.id in set }
        persistServers()
        if (_selectedId.value in set) setSelected(_servers.value.firstOrNull()?.id.orEmpty())
    }

    @Synchronized
    fun removeServersOfSubscription(subId: String) {
        _servers.value = _servers.value.filterNot { it.subId == subId }
        persistServers()
        if (serverById(_selectedId.value) == null) setSelected(_servers.value.firstOrNull()?.id.orEmpty())
    }

    // ----------------------------------------------------------- subscriptions

    fun subById(id: String): Subscription? = _subs.value.firstOrNull { it.id == id }

    @Synchronized
    fun addSubscription(sub: Subscription) {
        _subs.value = _subs.value + sub
        persistSubs()
    }

    @Synchronized
    fun updateSubscription(sub: Subscription) {
        val list = _subs.value.toMutableList()
        val idx = list.indexOfFirst { it.id == sub.id }
        if (idx >= 0) list[idx] = sub else list.add(sub)
        _subs.value = list
        persistSubs()
    }

    @Synchronized
    fun removeSubscription(id: String) {
        _subs.value = _subs.value.filterNot { it.id == id }
        persistSubs()
        removeServersOfSubscription(id)
    }

    /** منابع رایگان داخلی را به لیست اشتراک‌ها اضافه می‌کند */
    @Synchronized
    fun ensureBuiltInSources(): Int {
        val known = _subs.value.map { it.url.trim().trimEnd('/') }.toHashSet()
        val toAdd = PublicSources.DEFAULT.filter { it.url.trim().trimEnd('/') !in known }.map {
            Subscription(name = it.name, url = it.url, builtIn = true)
        }
        if (toAdd.isEmpty()) return 0
        _subs.value = _subs.value + toAdd
        persistSubs()
        return toAdd.size
    }

    /** حذف کانفیگ‌ها و اشتراک‌های منابع رایگان داخلی */
    @Synchronized
    fun removeBuiltInSources() {
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
    fun dedupeAllServers(): Int {
        val all = _servers.value
        if (all.size < 2) return 0
        val keep = LinkedHashMap<String, ServerConfig>()
        all.forEach { cfg ->
            val key = cfg.fingerprint()
            val current = keep[key]
            if (current == null) {
                keep[key] = cfg
            } else {
                val better = when {
                    cfg.favorite != current.favorite -> if (cfg.favorite) cfg else current
                    else -> {
                        val a = cfg.bestPing()
                        val b = current.bestPing()
                        when {
                            a > 0 && (b <= 0 || a < b) -> cfg
                            else -> current
                        }
                    }
                }
                keep[key] = better
            }
        }
        if (keep.size == all.size) return 0
        val removed = all.size - keep.size
        val survivors = keep.values.toList()
        _servers.value = survivors
        persistServers()
        if (survivors.none { it.id == _selectedId.value }) {
            setSelected(survivors.firstOrNull()?.id.orEmpty())
        }
        return removed
    }

    /** ثبت نتیجه‌ی سنجش سرعت */
    @Synchronized
    fun setSpeed(id: String, kbps: Int, persist: Boolean = false) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
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
    fun trimBuiltInServers(maxTotal: Int = 3000): Int {
        val all = _servers.value
        if (all.size <= maxTotal) return 0
        val builtInIds = _subs.value.filter { it.builtIn }.map { it.id }.toHashSet()
        val now = System.currentTimeMillis()
        val removable = all
            .filter { it.subId in builtInIds && !it.favorite && it.id != _selectedId.value }
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
            .filter { it.bestPing() > 0 && now - it.testedAt <= maxAgeMs }
            .sortedBy { it.bestPing() }
    }

    // ------------------------------------------------------ network profiles

    private const val KEY_PROFILES = "network_profiles_v1"

    private val _profiles = MutableStateFlow<Map<String, NetworkProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, NetworkProfile>> = _profiles.asStateFlow()

    private fun loadProfiles() {
        _profiles.value = runCatching {
            val json = prefs?.getString(KEY_PROFILES, null)
            if (json.isNullOrBlank()) emptyMap() else {
                val type = object : TypeToken<Map<String, NetworkProfile>>() {}.type
                gson.fromJson<Map<String, NetworkProfile>>(json, type) ?: emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun persistProfiles() {
        if (persistSuspended) return
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

    fun lastUpdateCheck(): Long =
        prefs?.getLong(KEY_LAST_UPDATE_CHECK, 0L) ?: 0L

    fun markUpdateChecked() {
        prefs?.edit()?.putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis())?.apply()
    }

    // ---------------------------------------------------------------- settings

    fun saveSettings(s: AppSettings) {
        _settings.value = s
        persistSettings()
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        saveSettings(block(_settings.value))
    }

    // ------------------------------------------------------------------- misc

    // --------------------------------------------------------- backup/restore

    fun exportBackup(): String = gson.toJson(
        BackupPayload(
            servers = _servers.value,
            subs = _subs.value,
            settings = _settings.value,
            selectedId = _selectedId.value
        )
    )

    @Synchronized
    fun restoreBackup(json: String): Boolean {
        val raw = runCatching { gson.fromJson(json, BackupPayload::class.java) }.getOrNull()
            ?: return false
        val payload = Migrations.migrateBackup(raw)
        if (payload.servers.isEmpty() && payload.subs.isEmpty()) return false
        _servers.value = payload.servers
        _subs.value = payload.subs
        _settings.value = payload.settings
        persistServers()
        persistSubs()
        persistSettings()
        setSelected(payload.selectedId.ifBlank { payload.servers.firstOrNull()?.id.orEmpty() })
        return true
    }

    fun resetEverything() {
        _servers.value = emptyList()
        _subs.value = emptyList()
        _selectedId.value = ""
        persistServers()
        persistSubs()
        prefs?.edit()?.putString(KEY_SELECTED, "")?.apply()
    }
}
