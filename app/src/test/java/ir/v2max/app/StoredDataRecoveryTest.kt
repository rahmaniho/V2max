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

    @Test fun `failed archival prevents automatic writes over original`() {
        val fake = Preferences()
        val original = """[{"address":"valid.example.org"},null]"""
        fake.data["servers_v1"] = original
        fake.failNextCommit = true
        val result = StoredDataRecovery.list(fake.prefs, "servers_v1", ServerConfig())
        assertEquals(1, result.value.size)
        assertTrue(result.damaged)
        assertFalse(result.writesAllowed)
        assertEquals(original, fake.data["servers_v1"])
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

    @Test fun `failed backup commit restores previous values and their types in memory`() {
        val fake = Preferences()
        fake.data["servers_v1"] = 12
        fake.data["subs_v1"] = "old subscriptions"
        fake.data["settings_v1"] = true
        fake.failNextCommit = true
        assertFalse(Repo.persistBackup(fake.prefs, "new servers", "new subs", "new settings", "new selected"))
        assertEquals(12, fake.data["servers_v1"])
        assertEquals("old subscriptions", fake.data["subs_v1"])
        assertEquals(true, fake.data["settings_v1"])
        assertFalse(fake.data.containsKey("selected_v1"))
    }
}
