package ir.v2max.app.data

import android.content.SharedPreferences

/** Read legacy preference documents without letting a damaged entry crash startup. */
internal object StoredDataRecovery {
    private const val ARCHIVE_PREFIX = "recovery_copy_"

    data class Result<T>(val value: T, val damaged: Boolean = false, val writesAllowed: Boolean = true)

    private fun present(prefs: SharedPreferences, key: String): Boolean =
        runCatching { prefs.contains(key) }.getOrDefault(true)

    private fun raw(prefs: SharedPreferences, key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    private fun preserve(prefs: SharedPreferences, key: String, value: String?): Boolean {
        if (value == null) return false // A non-string preference cannot be copied as a JSON document.
        return runCatching {
            val archive = ARCHIVE_PREFIX + key
            if (prefs.contains(archive)) {
                // Do not overwrite an earlier recovery copy with a different broken document.
                prefs.getString(archive, null) == value
            } else {
                prefs.edit().putString(archive, value).commit()
            }
        }.getOrDefault(false)
    }

    fun <T : Any> list(prefs: SharedPreferences, key: String, defaults: T): Result<List<T>> {
        if (!present(prefs, key)) return Result(emptyList())
        val text = raw(prefs, key)
        val recovered = text?.let { Migrations.recoverList(it, defaults) }
        val damaged = recovered == null || recovered.rejected > 0
        return Result(recovered?.values.orEmpty(), damaged, !damaged || preserve(prefs, key, text))
    }

    fun settings(prefs: SharedPreferences, key: String): Result<AppSettings> {
        if (!present(prefs, key)) return Result(AppSettings())
        val text = raw(prefs, key)
        val recovered = text?.let { Migrations.heal(it, AppSettings()) }
        return if (recovered != null) Result(recovered) else Result(
            Migrations.safeSettingsAfterFailure(text), damaged = true,
            writesAllowed = preserve(prefs, key, text)
        )
    }

    /** Do not automatically choose or refresh from an incomplete recovered list. */
    fun guardSettings(settings: AppSettings, damaged: Boolean): AppSettings = if (!damaged) settings else settings.copy(
        autoConnectOnOpen = false, autoConnectOnBoot = false, autoRefreshOnOpen = false,
        autoUpdateSubs = false, useFreeSources = false, allowExternalAutomation = false
    )

    /** A deliberate data reset also removes copies containing private credentials. */
    fun removeArchive(prefs: SharedPreferences, vararg keys: String) {
        runCatching {
            val editor = prefs.edit()
            keys.forEach { editor.remove(ARCHIVE_PREFIX + it) }
            editor.apply()
        }
    }
}
