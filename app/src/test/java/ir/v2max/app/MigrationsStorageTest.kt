package ir.v2max.app

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.Migrations
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import org.junit.Assert.*
import org.junit.Test

class MigrationsStorageTest {
    @Test fun `missing and duplicate identifiers are unique and valid records survive`() {
        val json = """[
            {"address":"one.example.org","name":"one"},
            {"address":"two.example.org","name":"two","id":null},
            {"address":"three.example.org","id":"same"},
            {"address":"four.example.org","id":"same"},
            null,
            {"address":"bad.example.org","proto":"FUTURE_PROTOCOL"},
            {"address":"overflow.example.org","port":2147483648},
            {"address":42},
            {"address":"security.example.org","security":null},
            {"address":"last.example.org","obfsPassword":"secret"}
        ]""".trimIndent()
        val recovered = Migrations.recoverList(json, ServerConfig())!!
        assertEquals(5, recovered.rejected)
        assertEquals(1, recovered.duplicateIds)
        assertEquals(5, recovered.values.size)
        assertEquals(5, recovered.values.map { it.id }.toSet().size)
        assertEquals(recovered.values.map { it.id }, Migrations.recoverList(json, ServerConfig())!!.values.map { it.id })
        assertTrue(recovered.values.all { it.id.isNotBlank() })
        assertEquals("same", recovered.values[2].id)
        assertEquals("secret", recovered.values.last().obfsPassword)
        assertEquals("", recovered.values.last().hoppingPorts)
        assertNull(Migrations.healList("{\"not\":\"an array\"}", ServerConfig()))
    }

    @Test fun `generated legacy ids do not collide with a later existing id`() {
        val reserved = java.util.UUID.nameUUIDFromBytes("v2max-legacy:server:0:0".toByteArray()).toString()
        val json = """[{"address":"first.example.org"},{"id":"$reserved","address":"second.example.org"}]"""
        val first = Migrations.recoverList(json, ServerConfig())!!
        val second = Migrations.recoverList(json, ServerConfig())!!
        assertEquals(0, first.duplicateIds)
        assertEquals(reserved, first.values.last().id)
        assertNotEquals(reserved, first.values.first().id)
        assertEquals(first.values.map { it.id }, second.values.map { it.id })
    }

    @Test fun `legacy subscriptions receive defaults and bad entries are isolated`() {
        val json = """[
            {"url":"https://one.example.org/private","id":null},
            {"url":"https://two.example.org/private"},
            {"url":null,"id":"valid-null"},
            {"url":"https://bad.example.org","builtIn":"true"}
        ]""".trimIndent()
        val result = Migrations.recoverList(json, Subscription())!!
        assertEquals(1, result.rejected)
        assertEquals(3, result.values.size)
        assertEquals(3, result.values.map { it.id }.toSet().size)
        assertEquals("", result.values.last().url)
        assertTrue(result.values.none { it.builtIn })
    }

    @Test fun `corrupt settings do not disable a lock or enable background actions`() {
        val legacy = Migrations.heal("""{"multiPing":true}""", AppSettings())!!
        assertTrue(legacy.multiPing)
        assertFalse(legacy.appLock)
        assertTrue("Legacy settings should get the requested default", legacy.connectionVoice)
        assertFalse(Migrations.heal("""{"connectionVoice":false}""", AppSettings())!!.connectionVoice)
        assertNull(Migrations.heal("""{"connectionVoice":"false"}""", AppSettings()))
        assertNull(Migrations.heal("""{"connectionVoice":null}""", AppSettings()))
        assertNull(Migrations.heal("""{"appLock":null}""", AppSettings()))
        assertNull(Migrations.heal("""{"routingMode":"UNKNOWN"}""", AppSettings()))
        assertNull(Migrations.heal("""{"perAppPackages":[null]}""", AppSettings()))

        for (broken in listOf("not json", """{"appLock":null}""", """{"routingMode":"UNKNOWN"}""")) {
            val safe = Migrations.safeSettingsAfterFailure(broken)
            assertTrue(safe.appLock)
            assertFalse(safe.autoConnectOnOpen)
            assertFalse(safe.autoRefreshOnOpen)
            assertFalse(safe.useFreeSources)
            assertFalse(safe.allowExternalAutomation)
            assertFalse(safe.connectionVoice)
        }
        // Preserve an explicitly readable disabled lock even if another field is bad.
        assertFalse(Migrations.safeSettingsAfterFailure("""{"appLock":false,"routingMode":"UNKNOWN"}""").appLock)
    }

    @Test fun `connection voice survives backups and legacy data gets the default`() {
        val legacy = Migrations.decodeBackup("""{"version":2,"settings":{}}""")!!
        assertTrue(legacy.settings.connectionVoice)
        val disabled = Migrations.decodeBackup("""{"version":3,"settings":{"connectionVoice":false}}""")!!
        assertFalse(disabled.settings.connectionVoice)
        assertNull(Migrations.decodeBackup("""{"version":3,"settings":{"connectionVoice":null}}"""))
        assertNull(Migrations.decodeBackup("""{"version":3,"settings":{"connectionVoice":"false"}}"""))
    }

    @Test fun `remembered network entries are individually healed`() {
        val profiles = Migrations.healProfiles("""{
            "wifi":{"serverId":"good","fragmentProfile":"MAHSA"},
            "cell":{"serverId":null},
            "bad":null,
            "bad-enum":{"fragmentProfile":"FUTURE"}
        }""")!!
        assertEquals(setOf("wifi", "cell"), profiles.keys)
        assertEquals("wifi", profiles.getValue("wifi").key)
        assertEquals(FragmentProfile.MAHSA, profiles.getValue("wifi").fragmentProfile)
        assertEquals("", profiles.getValue("cell").serverId)
        assertNull(Migrations.healProfiles("[]"))
    }
}
