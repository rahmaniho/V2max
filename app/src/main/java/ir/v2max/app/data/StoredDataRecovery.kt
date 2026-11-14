package ir.v2max.app.data

import android.content.SharedPreferences

/** Read legacy preference documents without letting a damaged entry crash startup. */
internal object StoredDataRecovery {
    // Pre-release builds briefly kept copies in the primary preference file.
    // Restore preserves them; an explicit reset deletes them with the live data.
    private const val LEGACY_ARCHIVE_PREFIX = "recovery_copy_"

    data class Result<T>(val value: T, val damaged: Boolean = false, val writesAllowed: Boolean = true)

    private fun present(prefs: SharedPreferences, key: String): Boolean =
        runCatching { prefs.contains(key) }.getOrDefault(true)

    private fun raw(prefs: SharedPreferences, key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    fun <T : Any> list(prefs: SharedPreferences, key: String, defaults: T): Result<List<T>> {
        if (!present(prefs, key)) return Result(emptyList())
        val text = raw(prefs, key)
        val recovered = text?.let { Migrations.recoverList(it, defaults) }
        val damaged = recovered == null || recovered.rejected > 0 || recovered.duplicateIds > 0
        // Do not copy credentials or overwrite the original. A deliberate
        // restore/reset is required before writes can resume.
        return Result(recovered?.values.orEmpty(), damaged, writesAllowed = !damaged)
    }

    fun settings(prefs: SharedPreferences, key: String): Result<AppSettings> {
        if (!present(prefs, key)) return Result(AppSettings())
        val text = raw(prefs, key)
        val recovered = text?.let { Migrations.heal(it, AppSettings()) }
        return if (recovered != null) Result(recovered) else Result(
            Migrations.safeSettingsAfterFailure(text), damaged = true, writesAllowed = false
        )
    }

    /** Do not automatically choose or refresh from an incomplete recovered list. */
    fun guardSettings(settings: AppSettings, damaged: Boolean): AppSettings = if (!damaged) settings else settings.copy(
        autoConnectOnOpen = false, autoConnectOnBoot = false, autoRefreshOnOpen = false,
        autoUpdateSubs = false, useFreeSources = false, allowExternalAutomation = false
    )

    fun legacyArchiveKeys(vararg keys: String): List<String> = keys.map { LEGACY_ARCHIVE_PREFIX + it }

    fun removeLegacyArchives(editor: SharedPreferences.Editor, vararg keys: String) {
        legacyArchiveKeys(*keys).forEach { editor.remove(it) }
    }
}
