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

    @Volatile
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        _servers.value = readList<ServerConfig>(
            p.getString(KEY_SERVERS, null),
            object : TypeToken<List<ServerConfig>>() {}.type
        )
        _subs.value = readList<Subscription>(
            p.getString(KEY_SUBS, null),
            object : TypeToken<List<Subscription>>() {}.type
        )
        _settings.value = runCatching {
            p.getString(KEY_SETTINGS, null)?.let { gson.fromJson(it, AppSettings::class.java) }
        }.getOrNull() ?: AppSettings()
        _selectedId.value = p.getString(KEY_SELECTED, "") ?: ""
        loaded = true
    }

    private fun <T> readList(json: String?, type: java.lang.reflect.Type): List<T> = runCatching {
        if (json.isNullOrBlank()) emptyList() else gson.fromJson<List<T>>(json, type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun persistServers() {
        prefs?.edit()?.putString(KEY_SERVERS, gson.toJson(_servers.value))?.apply()
    }

    private fun persistSubs() {
        prefs?.edit()?.putString(KEY_SUBS, gson.toJson(_subs.value))?.apply()
    }

    private fun persistSettings() {
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
        incoming.forEach { cfg ->
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
        incoming.forEach { cfg ->
            val key = cfg.fingerprint()
            if (!seen.add(key)) return@forEach
            val old = byKey[key]
            merged.add(
                if (old != null) {
                    cfg.id = old.id
                    cfg.favorite = old.favorite
                    cfg.tcpPing = old.tcpPing
                    cfg.realPing = old.realPing
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
    fun setTestResult(id: String, tcpPing: Int, realPing: Int, testedAt: Long = System.currentTimeMillis()) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(tcpPing = tcpPing, realPing = realPing, testedAt = testedAt)
        _servers.value = list
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

    // ---------------------------------------------------------------- settings

    fun saveSettings(s: AppSettings) {
        _settings.value = s
        persistSettings()
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        saveSettings(block(_settings.value))
    }

    // ------------------------------------------------------------------- misc

    fun resetEverything() {
        _servers.value = emptyList()
        _subs.value = emptyList()
        _selectedId.value = ""
        persistServers()
        persistSubs()
        prefs?.edit()?.putString(KEY_SELECTED, "")?.apply()
    }
}
