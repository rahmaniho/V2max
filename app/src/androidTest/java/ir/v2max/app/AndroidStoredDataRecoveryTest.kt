package ir.v2max.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.StoredDataRecovery
import ir.v2max.app.data.Subscription
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated preference file; no production servers or settings are changed. */
@RunWith(AndroidJUnit4::class)
class AndroidStoredDataRecoveryTest {
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = context.getSharedPreferences("v2max_recovery_fixture", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
    }

    @After fun cleanUp() { prefs.edit().clear().commit() }

    @Test fun damagedListsKeepValidEntriesReadOnlyWithoutCopyingOriginal() {
        val original = """[{"address":"one.example.org","uuid":"secret"},null,{"address":"two.example.org"}]"""
        assertTrue(prefs.edit().putString("servers_v1", original).commit())
        val result = StoredDataRecovery.list(prefs, "servers_v1", ServerConfig())
        assertTrue(result.damaged)
        assertFalse(result.writesAllowed)
        assertEquals(2, result.value.size)
        assertEquals(2, result.value.map { it.id }.toSet().size)
        assertEquals(result.value.map { it.id }, StoredDataRecovery.list(prefs, "servers_v1", ServerConfig()).value.map { it.id })
        assertEquals(original, prefs.getString("servers_v1", null))
        assertFalse(prefs.contains("recovery_copy_servers_v1"))
    }

    @Test fun resetDeletesPreReleaseRecoveryCopiesInSameTransaction() {
        val old = "[{\"id\":\"older\"}]"
        val damaged = "[null]"
        assertTrue(prefs.edit().putString("recovery_copy_servers_v1", old)
            .putString("servers_v1", damaged).commit())
        val result = StoredDataRecovery.list(prefs, "servers_v1", ServerConfig())
        assertTrue(result.damaged)
        assertFalse(result.writesAllowed)
        assertEquals(old, prefs.getString("recovery_copy_servers_v1", null))
        assertEquals(damaged, prefs.getString("servers_v1", null))
        assertTrue(Repo.persistReset(prefs, "{}"))
        assertEquals("[]", prefs.getString("servers_v1", null))
        assertFalse(prefs.contains("recovery_copy_servers_v1"))
    }

    @Test fun corruptSettingsAndPreferenceTypesDoNotGrantAutomationOrStartConnections() {
        assertTrue(prefs.edit().putInt("settings_v1", 1).commit())
        val result = StoredDataRecovery.settings(prefs, "settings_v1")
        assertTrue(result.damaged)
        assertFalse(result.writesAllowed)
        assertTrue(result.value.appLock)
        assertFalse(result.value.allowExternalAutomation)
        assertFalse(result.value.autoConnectOnOpen)
        assertFalse(result.value.useFreeSources)

        val original = """{"appLock":true,"routingMode":"FUTURE"}"""
        assertTrue(prefs.edit().putString("settings_v1", original).commit())
        val malformed = StoredDataRecovery.settings(prefs, "settings_v1")
        assertTrue(malformed.damaged)
        assertFalse(malformed.writesAllowed)
        assertTrue(malformed.value.appLock)
        assertEquals(original, prefs.getString("settings_v1", null))
        assertFalse(prefs.contains("recovery_copy_settings_v1"))
    }

    @Test fun explicitBackupRestoreCanReplaceNonStringPreferenceValues() {
        assertTrue(prefs.edit().putInt("servers_v1", 5).putBoolean("settings_v1", true)
            .putString("recovery_copy_servers_v1", "older credentials").commit())
        assertTrue(Repo.persistBackup(prefs, "[]", "[]", "{}", "chosen"))
        assertEquals("[]", prefs.getString("servers_v1", null))
        assertEquals("{}", prefs.getString("settings_v1", null))
        assertEquals("chosen", prefs.getString("selected_v1", null))
        assertEquals("older credentials", prefs.getString("recovery_copy_servers_v1", null))
    }

    @Test fun legacySubscriptionsGetDistinctIdsWithoutCreatingAnArchive() {
        val original = """[{"url":"https://one.example.org"},{"url":"https://two.example.org"}]"""
        assertTrue(prefs.edit().putString("subs_v1", original).commit())
        val result = StoredDataRecovery.list(prefs, "subs_v1", Subscription())
        assertFalse(result.damaged)
        assertTrue(result.writesAllowed)
        assertEquals(2, result.value.map { it.id }.toSet().size)
        assertEquals(result.value.map { it.id }, StoredDataRecovery.list(prefs, "subs_v1", Subscription()).value.map { it.id })
        assertFalse(prefs.contains("recovery_copy_subs_v1"))
    }
}
