package ir.v2max.app.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
    const val SCHEMA_VERSION = 2

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

    /** یک شیء JSON را با مدل فعلی هم‌تراز می‌کند */
    fun <T> heal(json: String, defaults: T): T? = runCatching {
        val parsed = JsonParser.parseString(json)
        if (!parsed.isJsonObject) return@runCatching null
        val obj = parsed.asJsonObject
        fill(obj, template(defaults))
        gson.fromJson(obj, defaults!!::class.java)
    }.getOrNull()

    /** یک آرایه‌ی JSON را با مدل فعلی هم‌تراز می‌کند */
    fun <T> healList(json: String, defaults: T): List<T>? = runCatching {
        val parsed = JsonParser.parseString(json)
        if (!parsed.isJsonArray) return@runCatching null
        val array: JsonArray = parsed.asJsonArray
        val tpl = template(defaults)
        array.forEach { item ->
            if (item.isJsonObject) fill(item.asJsonObject, tpl)
        }
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, defaults!!::class.java).type
        gson.fromJson<List<T>>(array, type)
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
        return copy(countryCode = resolved.first, countryName = resolved.second)
    }
}
