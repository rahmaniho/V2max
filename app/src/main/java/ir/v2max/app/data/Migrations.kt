package ir.v2max.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ir.v2max.core.geo.CountryFlags

/**
 * مهاجرت‌های داده (معادل schema version برای مخزن JSON برنامه).
 *
 * چرا لازم است: Gson شیء را بدون فراخوانی سازنده می‌سازد، پس اگر نسخه‌ی جدید یک فیلد
 * غیرnullable به مدل اضافه کند، داده‌های قدیمی روی دستگاه کاربر آن فیلد را «null»
 * تحویل می‌دهند و برنامه در اولین خواندن کرش می‌کند. این کلاس کلیدهای جدید را
 * با مقدار پیش‌فرض پر می‌کند تا ارتقای نسخه بدون از دست رفتن داده انجام شود.
 */
object Migrations {

    /** نسخه‌ی فعلی ساختار داده */
    const val SCHEMA_VERSION = 3

    private val gson = Gson()

    private fun template(obj: Any): JsonObject = gson.toJsonTree(obj).asJsonObject

    /** کلیدهای گمشده/تهی را از الگوی پیش‌فرض پر می‌کند */
    private fun fill(target: JsonObject, defaults: JsonObject) {
        for ((key, value) in defaults.entrySet()) {
            val current = target.get(key)
            if (current == null || current.isJsonNull) {
                target.add(key, value.deepCopy())
            } else if (current.isJsonObject && value.isJsonObject) {
                fill(current.asJsonObject, value.asJsonObject)
            }
        }
    }

    /** A partially damaged list should not hide its other valid entries. */
    data class ListRecovery<T>(val values: List<T>, val rejected: Int, val duplicateIds: Int)

    /** یک شیء JSON را با مدل فعلی هم‌تراز و پیش از استفاده اعتبارسنجی می‌کند. */
    fun <T : Any> heal(json: String, defaults: T): T? = runCatching {
        val parsed = JsonParser.parseString(json)
        require(parsed.isJsonObject)
        decodeStoredObject(parsed.asJsonObject, defaults)
    }.getOrNull()

    /**
     * Missing IDs are stable per record across restarts, not copied from one
     * template. Invalid elements are skipped individually and duplicate IDs are
     * reported so the caller can preserve the original before writing.
     */
    fun <T : Any> recoverList(json: String, defaults: T): ListRecovery<T>? = runCatching {
        val parsed = JsonParser.parseString(json)
        require(parsed.isJsonArray)
        val seen = HashSet<String>()
        // Reserve all existing IDs, including entries later in the array, so a
        // generated legacy ID cannot collide with one that has not been read yet.
        val reserved = parsed.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val id = item.asJsonObject.get("id")
            if (id != null && id.isJsonPrimitive && id.asJsonPrimitive.isString) id.asString.takeIf { it.isNotBlank() }
            else null
        }.toHashSet()
        val values = ArrayList<T>()
        val expected = template(defaults)
        var rejected = 0
        var duplicateIds = 0
        parsed.asJsonArray.forEachIndexed { index, item ->
            val value = runCatching {
                require(item.isJsonObject)
                val obj = item.asJsonObject
                if (defaults is ServerConfig || defaults is Subscription) {
                    val id = obj.get("id")
                    val text = if (id != null && id.isJsonPrimitive && id.asJsonPrimitive.isString) id.asString else ""
                    val duplicate = text.isNotBlank() && text in seen
                    if (id == null || id.isJsonNull || (id.isJsonPrimitive && id.asJsonPrimitive.isString && text.isBlank()) || duplicate) {
                        if (duplicate) duplicateIds++
                        var attempt = 0
                        var generated: String
                        do {
                            // Stable across restarts without persisting or hashing
                            // credentials from the damaged/legacy record.
                            val kind = if (defaults is ServerConfig) "server" else "subscription"
                            val seed = "v2max-legacy:$kind:$index:${attempt++}"
                            generated = java.util.UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
                        } while (generated in reserved || generated in seen)
                        obj.addProperty("id", generated)
                    }
                }
                decodeStoredObject(obj, defaults, expected)
            }.getOrNull()
            if (value == null) {
                rejected++
            } else {
                val id = when (value) {
                    is ServerConfig -> value.id
                    is Subscription -> value.id
                    else -> null
                }
                if (id != null) seen.add(id)
                values.add(value)
            }
        }
        ListRecovery(values, rejected, duplicateIds)
    }.getOrNull()

    fun <T : Any> healList(json: String, defaults: T): List<T>? = recoverList(json, defaults)?.values

    private fun <T : Any> decodeStoredObject(obj: JsonObject, defaults: T, expected: JsonObject = template(defaults)): T {
        if (defaults is AppSettings) {
            // Explicitly corrupt security/privacy flags must not be healed into
            // an unlocked app or an unexpectedly audible connection.
            listOf("appLock", "connectionVoice").forEach { key ->
                if (obj.has(key)) {
                    val value = obj.get(key)
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
                }
            }
        }
        if (defaults is ServerConfig) {
            // Do not turn an explicitly corrupted protocol/transport/security
            // into a different connection. Truly missing legacy fields still
            // receive their documented defaults.
            listOf("proto", "network", "security").forEach { key ->
                require(!obj.has(key) || !obj.get(key).isJsonNull)
            }
        }
        fill(obj, expected)
        requireShape(obj, expected)
        validateKnownFields(obj, defaults)
        // Gson's tree reader can truncate overflowing integers; its streaming
        // reader rejects them, so decode through JSON text instead.
        return requireNotNull(gson.fromJson(obj.toString(), defaults::class.java))
    }

    private fun validateKnownFields(obj: JsonObject, defaults: Any) {
        fun enumField(key: String, names: List<String>) = require(obj.get(key).asString in names)
        when (defaults) {
            is ServerConfig -> {
                enumField("proto", Proto.entries.map { it.name })
                enumField("testedFragmentProfile", FragmentProfile.entries.map { it.name })
            }
            is AppSettings -> {
                enumField("routingMode", RoutingMode.entries.map { it.name })
                enumField("fragmentProfile", FragmentProfile.entries.map { it.name })
                enumField("perAppMode", PerAppMode.entries.map { it.name })
                listOf("customRoutingRules", "perAppPackages", "disabledBuiltInSources").forEach { key ->
                    require(obj.getAsJsonArray(key).all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                }
            }
            is NetworkProfile -> enumField("fragmentProfile", FragmentProfile.entries.map { it.name })
        }
    }

    /** Damaged settings never silently turn off a lock or start a connection. */
    fun safeSettingsAfterFailure(raw: String?): AppSettings {
        val lock = runCatching {
            val obj = JsonParser.parseString(raw).asJsonObject
            val value = obj.get("appLock")
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else true
        }.getOrDefault(true)
        return AppSettings(
            appLock = lock, autoConnectOnOpen = false, autoConnectOnBoot = false,
            autoRefreshOnOpen = false, autoUpdateSubs = false, useFreeSources = false,
            autoUpdateCheck = false, geoAssets = false, allowExternalAutomation = false,
            // A damaged settings document must not unexpectedly reveal a VPN
            // connection through a speaker when the user's preference is unknown.
            connectionVoice = false
        )
    }

    /** Remembered network scores are optional; never load a malformed/null entry. */
    fun healProfiles(json: String): Map<String, NetworkProfile>? = runCatching {
        val parsed = JsonParser.parseString(json)
        require(parsed.isJsonObject)
        parsed.asJsonObject.entrySet().mapNotNull { (key, value) ->
            if (key.isBlank() || !value.isJsonObject) return@mapNotNull null
            heal(value.toString(), NetworkProfile())?.copy(key = key)?.let { key to it }
        }.toMap()
    }.getOrNull()

    /** Reject Gson's permissive primitive coercions before creating live models. */
    private fun requireShape(target: JsonObject, defaults: JsonObject) {
        for ((key, expected) in defaults.entrySet()) {
            val value = target.get(key) ?: error("missing field: $key")
            when {
                expected.isJsonObject -> {
                    require(value.isJsonObject)
                    requireShape(value.asJsonObject, expected.asJsonObject)
                }
                expected.isJsonArray -> require(value.isJsonArray)
                expected.isJsonPrimitive -> {
                    require(value.isJsonPrimitive)
                    val actual = value.asJsonPrimitive
                    val wanted = expected.asJsonPrimitive
                    when {
                        wanted.isString -> require(actual.isString)
                        wanted.isBoolean -> require(actual.isBoolean)
                        wanted.isNumber -> {
                            require(actual.isNumber)
                            // All numeric fields in these backup models are integral.
                            actual.asBigDecimal.toBigIntegerExact()
                        }
                    }
                }
            }
        }
    }

    /**
     * Validate the entire document before constructing Kotlin models. Gson can
     * otherwise populate non-null fields with null (including explicit JSON nulls).
     * Heal array elements too, and reject future schemas we cannot interpret.
     */
    fun decodeBackup(json: String): BackupPayload? = runCatching {
        val parsed = JsonParser.parseString(json)
        require(parsed.isJsonObject)
        val obj = parsed.asJsonObject
        // Missing legacy fields can be filled, but corrupt sections, the lock
        // or the audio preference must not silently become unsafe defaults.
        listOf("servers", "subs", "settings").forEach { key ->
            require(!obj.has(key) || !obj.get(key).isJsonNull)
        }
        obj.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject?.let { settings ->
            listOf("appLock", "connectionVoice").forEach { key ->
                if (settings.has(key)) {
                    val value = settings.get(key)
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
                }
            }
        }
        val versionElement = obj.get("version")
        val version = if (versionElement == null) 1 else {
            require(versionElement.isJsonPrimitive && versionElement.asJsonPrimitive.isNumber)
            val number = versionElement.asBigDecimal.toBigIntegerExact()
            require(number >= java.math.BigInteger.ONE && number <= java.math.BigInteger.valueOf(SCHEMA_VERSION.toLong()))
            number.toInt()
        }
        require(version in 1..SCHEMA_VERSION)
        val backupTemplate = template(BackupPayload())
        fill(obj, backupTemplate)
        obj.addProperty("version", version)
        requireShape(obj, backupTemplate)
        val servers = obj.get("servers")
        val subs = obj.get("subs")
        require(servers.isJsonArray && subs.isJsonArray && obj.get("settings").isJsonObject)
        servers.asJsonArray.forEach { item ->
            require(item.isJsonObject)
            // Defaults include a generated ID, so each element needs its own template.
            val server = item.asJsonObject
            listOf("proto", "network", "security").forEach { key ->
                require(!server.has(key) || !server.get(key).isJsonNull)
            }
            val defaults = template(ServerConfig())
            fill(server, defaults)
            requireShape(server, defaults)
            require(Proto.entries.any { it.name == server.get("proto").asString })
            require(FragmentProfile.entries.any { it.name == server.get("testedFragmentProfile").asString })
        }
        subs.asJsonArray.forEach { item ->
            require(item.isJsonObject)
            val defaults = template(Subscription())
            fill(item.asJsonObject, defaults)
            requireShape(item.asJsonObject, defaults)
        }
        val settings = obj.getAsJsonObject("settings")
        require(RoutingMode.entries.any { it.name == settings.get("routingMode").asString })
        require(FragmentProfile.entries.any { it.name == settings.get("fragmentProfile").asString })
        require(PerAppMode.entries.any { it.name == settings.get("perAppMode").asString })
        listOf("customRoutingRules", "perAppPackages").forEach { key ->
            val array = settings.get(key)
            require(array.isJsonArray && array.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
        }
        // Parse via the streaming reader: Gson's tree reader silently truncates
        // overflowing Int/Long fields instead of rejecting them.
        val payload = gson.fromJson(obj.toString(), BackupPayload::class.java)
        require(payload.servers.all { it.id.isNotBlank() } && payload.subs.all { it.id.isNotBlank() })
        require(payload.servers.map { it.id }.distinct().size == payload.servers.size)
        require(payload.subs.map { it.id }.distinct().size == payload.subs.size)
        migrateBackup(payload)
    }.getOrNull()

    /** نرمال‌سازی داده‌های واردشده از پشتیبان قدیمی */
    fun migrateBackup(payload: BackupPayload): BackupPayload {
        if (payload.version >= SCHEMA_VERSION) return payload
        val settings = heal(gson.toJson(payload.settings), AppSettings()) ?: payload.settings
        val servers = payload.servers.map { server ->
            heal(gson.toJson(server), ServerConfig()) ?: server
        }.map { it.withCountryIfNeeded() }
        return payload.copy(
            version = SCHEMA_VERSION,
            settings = settings,
            servers = servers
        )
    }

    /** تشخیص کشور برای سرورهای قدیمی که این فیلد را ندارند */
    private fun ServerConfig.withCountryIfNeeded(): ServerConfig {
        if (countryCode.isNotBlank()) return this
        val resolved = CountryFlags.resolve(name) ?: CountryFlags.resolve("$host $path $sni") ?: return this
        return copy(countryCode = resolved.code, countryName = resolved.name)
    }
}
