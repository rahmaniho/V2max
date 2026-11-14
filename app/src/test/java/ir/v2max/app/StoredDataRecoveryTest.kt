package ir.v2max.app

import android.content.SharedPreferences
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.StoredDataRecovery
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** SharedPreferences proxy models commit failure updating its in-memory cache. */
class StoredDataRecoveryTest {
    private class Preferences {
        val data = linkedMapOf<String, Any>()
        var failNextCommit = false

        val prefs: SharedPreferences = proxy(SharedPreferences::class.java) { _, name, args ->
            val key = args.firstOrNull() as? String
            when (name) {
                "getAll" -> data.toMap()
                "contains" -> data.containsKey(key)
                "getString" -> data[key]?.let { it as String } ?: args[1]
                "getBoolean" -> data[key]?.let { it as Boolean } ?: args[1]
                "getLong" -> data[key]?.let { it as Long } ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected SharedPreferences call: $name")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            return proxy(SharedPreferences.Editor::class.java) { self, name, args ->
                when {
                    name.startsWith("put") -> { changes[args[0] as String] = args[1]; self }
                    name == "remove" -> { changes[args[0] as String] = null; self }
                    name == "apply" || name == "commit" -> {
                        changes.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
                        if (name == "commit") {
                            val success = !failNextCommit
                            failNextCommit = false
                            success
                        } else null
                    }
                    else -> error("Unexpected Editor call: $name")
                }
            }
        }

        private fun <T> proxy(type: Class<T>, call: (Any, String, Array<out Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                call(self, method.name, args ?: emptyArray())
            })
    }

    @Test fun `damaged lists remain read only without copying credentials`() {
        val fake = Preferences()
        val original = """[{"address":"valid.example.org","uuid":"secret"},null]"""
        fake.data["servers_v1"] = original
        val result = StoredDataRecovery.list(fake.prefs, "servers_v1", ServerConfig())
        assertEquals(1, result.value.size)
        assertTrue(result.damaged)
        assertFalse(result.writesAllowed)
        assertEquals(original, fake.data["servers_v1"])
        assertEquals(setOf("servers_v1"), fake.data.keys)
    }

    @Test fun `duplicate ids keep original and pause writes and automatic actions`() {
        val fake = Preferences()
        val original = """[{"id":"same","address":"one.example.org"},{"id":"same","address":"two.example.org"}]"""
        fake.data["servers_v1"] = original
        val result = StoredDataRecovery.list(fake.prefs, "servers_v1", ServerConfig())
        assertTrue(result.damaged)
        assertFalse(result.writesAllowed)
        assertEquals(original, fake.data["servers_v1"])
        assertFalse(fake.data.containsKey("recovery_copy_servers_v1"))
        assertEquals(2, result.value.map { it.id }.toSet().size)
        assertFalse(StoredDataRecovery.guardSettings(AppSettings(), result.damaged).autoConnectOnOpen)
    }

    @Test fun `wrong preference type fails closed without overwriting it`() {
        val fake = Preferences()
        fake.data["settings_v1"] = 12
        val result = StoredDataRecovery.settings(fake.prefs, "settings_v1")
        assertTrue(result.damaged)
        assertFalse(result.writesAllowed)
        assertTrue(result.value.appLock)
        assertFalse(result.value.allowExternalAutomation)
        assertEquals(12, fake.data["settings_v1"])
    }

    @Test fun `damaged lists pause automatic actions but do not change lock or failover`() {
        val settings = AppSettings(appLock = true, smartFailover = true, allowExternalAutomation = true)
        assertSame(settings, StoredDataRecovery.guardSettings(settings, false))
        val guarded = StoredDataRecovery.guardSettings(settings, true)
        assertTrue(guarded.appLock)
        assertTrue(guarded.smartFailover)
        assertFalse(guarded.allowExternalAutomation)
        assertFalse(guarded.autoConnectOnOpen)
        assertFalse(guarded.autoConnectOnBoot)
        assertFalse(guarded.autoRefreshOnOpen)
        assertFalse(guarded.autoUpdateSubs)
        assertFalse(guarded.useFreeSources)
    }

    @Test fun `consent revocation is retried durably after a failed commit`() {
        val fake = Preferences()
        fake.data["allow_external_automation"] = true
        fake.failNextCommit = true
        assertFalse(Repo.persistControlConsent(fake.prefs, false))
        assertEquals(true, fake.data["allow_external_automation"])
        assertTrue(Repo.persistControlConsent(fake.prefs, false))
        assertEquals(false, fake.data["allow_external_automation"])
        val marker = fake.data["storage_transaction_marker_v1"]
        assertTrue(Repo.persistControlConsent(fake.prefs, false))
        assertNotEquals(marker, fake.data["storage_transaction_marker_v1"])
    }

    @Test fun `lock change and restore fail rather than keep unconfirmed automation consent`() {
        val fake = Preferences()
        fake.data["allow_external_automation"] = true
        val field = Repo::class.java.getDeclaredField("controlPrefs").apply { isAccessible = true }
        val previousControl = field.get(null)
        val previousSettings = Repo.settings.value.copy()
        try {
            assertTrue(Repo.saveSettings(AppSettings(appLock = false, allowExternalAutomation = false)))
            field.set(null, fake.prefs)
            fake.failNextCommit = true
            assertFalse(Repo.updateSettings { it.copy(appLock = true) })
            assertFalse(Repo.settings.value.appLock)
            assertEquals(true, fake.data["allow_external_automation"])
            assertTrue(Repo.updateSettings { it.copy(appLock = true) })
            assertTrue(Repo.settings.value.appLock)
            assertEquals(false, fake.data["allow_external_automation"])

            fake.failNextCommit = true
            var backupWritten = false
            val backup = """{"servers":[{"id":"incoming","address":"example.org"}]}"""
            assertFalse(Repo.restoreBackup(backup) { _, _, _, _ -> backupWritten = true; true })
            assertFalse(backupWritten)
            assertTrue(Repo.settings.value.appLock)
        } finally {
            field.set(null, previousControl)
            Repo.saveSettings(previousSettings)
        }
    }

    @Test fun `app lock is not published until its settings are durably written`() {
        val control = Preferences()
        val storage = Preferences()
        val controlField = Repo::class.java.getDeclaredField("controlPrefs").apply { isAccessible = true }
        val storageField = Repo::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val previousControl = controlField.get(null)
        val previousStorage = storageField.get(null)
        val previousSettings = Repo.settings.value.copy()
        try {
            assertTrue(Repo.saveSettings(AppSettings(appLock = false, allowExternalAutomation = true)))
            control.data["allow_external_automation"] = true
            storage.data["settings_v1"] = "old settings"
            controlField.set(null, control.prefs)
            storageField.set(null, storage.prefs)
            storage.failNextCommit = true
            assertFalse(Repo.updateSettings { it.copy(appLock = true) })
            assertFalse(Repo.settings.value.appLock)
            assertFalse("Durably revoked consent must stay revoked", Repo.settings.value.allowExternalAutomation)
            assertEquals(false, control.data["allow_external_automation"])
            assertEquals("old settings", storage.data["settings_v1"])

            assertTrue(Repo.updateSettings { it.copy(appLock = true) })
            assertTrue(Repo.settings.value.appLock)
            assertTrue((storage.data["settings_v1"] as String).contains("\"appLock\":true"))
            assertFalse(Repo.settings.value.allowExternalAutomation)
        } finally {
            controlField.set(null, previousControl)
            storageField.set(null, previousStorage)
            Repo.saveSettings(previousSettings)
        }
    }

    @Test fun `reset commits deletion and removes legacy credential copies together`() {
        val fake = Preferences()
        fake.data["servers_v1"] = "old secret"
        fake.data["subs_v1"] = 12
        fake.data["settings_v1"] = true
        fake.data["selected_v1"] = "old"
        fake.data["network_profiles_v1"] = "private profile"
        fake.data["recovery_copy_servers_v1"] = "archived secret"
        fake.failNextCommit = true
        assertFalse(Repo.persistReset(fake.prefs, "new settings"))
        assertEquals("old secret", fake.data["servers_v1"])
        assertEquals(12, fake.data["subs_v1"])
        assertEquals(true, fake.data["settings_v1"])
        assertEquals("archived secret", fake.data["recovery_copy_servers_v1"])
        assertEquals("private profile", fake.data["network_profiles_v1"])
        assertTrue(Repo.persistReset(fake.prefs, "new settings"))
        assertEquals("[]", fake.data["servers_v1"])
        assertEquals("[]", fake.data["subs_v1"])
        assertEquals("new settings", fake.data["settings_v1"])
        assertEquals("", fake.data["selected_v1"])
        assertFalse(fake.data.containsKey("network_profiles_v1"))
        assertFalse(fake.data.containsKey("recovery_copy_servers_v1"))
    }

    @Test fun `failed backup commit restores previous values and their types in memory`() {
        val fake = Preferences()
        fake.data["servers_v1"] = 12
        fake.data["subs_v1"] = "old subscriptions"
        fake.data["settings_v1"] = true
        fake.data["recovery_copy_servers_v1"] = "older credentials"
        fake.failNextCommit = true
        assertFalse(Repo.persistBackup(fake.prefs, "new servers", "new subs", "new settings", "new selected"))
        assertEquals(12, fake.data["servers_v1"])
        assertEquals("old subscriptions", fake.data["subs_v1"])
        assertEquals(true, fake.data["settings_v1"])
        assertFalse(fake.data.containsKey("selected_v1"))
        assertTrue(Repo.persistBackup(fake.prefs, "new servers", "new subs", "new settings", "new selected"))
        assertEquals("older credentials", fake.data["recovery_copy_servers_v1"])
    }
}
