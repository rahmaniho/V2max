package ir.v2max.app

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Pure state-policy checks; no Android preferences or network are needed. */
class RepoConcurrencyTest {
    private lateinit var previousSettings: AppSettings

    @Before fun setUp() {
        previousSettings = Repo.settings.value.copy()
        Repo.resetEverything()
        Repo.saveSettings(AppSettings())
        Repo.tunnel.value = TunnelUiState()
    }

    @After fun cleanUp() {
        Repo.tunnel.value = TunnelUiState()
        Repo.resetEverything()
        Repo.saveSettings(previousSettings)
    }

    private fun server(id: String, source: String, address: String = "$id.example.org") =
        ServerConfig(id = id, name = id, address = address, uuid = id, subId = source)

    private fun restore(json: String): Boolean = Repo.restoreBackup(json) { _, _, _, _ -> true }

    @Test fun `only one overlapping response can replace a source revision`() {
        val source = Subscription(id = "source", url = "https://example.org/sub", updatedAt = 1)
        Repo.addSubscription(source)
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val workers = (1..2).map { number ->
            thread {
                start.await()
                results.add(Repo.commitSubscriptionRefresh(
                    source.copy(), listOf(server("s$number", source.id)), 0, 0, 0, 0
                ))
            }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)); assertFalse("worker timed out", it.isAlive) }
        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
        assertEquals(1, Repo.servers.value.count { it.subId == source.id })
        Repo.recordSubscriptionRefreshError(source.copy(), "outdated")
        assertEquals("", Repo.subById(source.id)?.lastError)
    }

    @Test fun `live and manually selected ids survive refresh cap and trim`() {
        val source = Subscription(id = "public", url = "https://example.org/sub", builtIn = true)
        Repo.addSubscription(source)
        val live = server("live", source.id)
        val selected = server("selected", source.id)
        Repo.updateServer(live)
        Repo.updateServer(selected)
        Repo.setSelected(selected.id)
        Repo.tunnel.value = TunnelUiState(status = TunnelStatus.CONNECTED, serverId = live.id)
        Repo.replaceSubscriptionServers(source.id, (0..710).map { server("new$it", source.id) })
        assertNotNull(Repo.serverById(live.id))
        assertNotNull(Repo.serverById(selected.id))
        assertEquals(700, Repo.servers.value.size)
        Repo.trimBuiltInServers(maxTotal = 1)
        assertEquals(setOf(live.id, selected.id), Repo.servers.value.map { it.id }.toSet())
    }

    @Test fun `version two backup restores all sections with new field defaults`() {
        val legacy = """{
            "version":2,
            "servers":[{"id":"legacy","name":"old","address":"example.org","port":443,
                        "obfsPassword":"pw"}],
            "subs":[{"id":"old-sub","url":"https://example.org/sub"}],
            "settings":{"multiPing":true,"fragment":false},
            "selectedId":"legacy"
        }""".trimIndent()
        assertTrue(restore(legacy))
        assertEquals("legacy", Repo.selectedId.value)
        assertEquals("old-sub", Repo.subs.value.single().id)
        assertTrue(Repo.settings.value.multiPing)
        assertFalse(Repo.settings.value.fragment)
        val restored = Repo.servers.value.single()
        assertEquals("pw", restored.obfsPassword)
        assertEquals("", restored.hoppingPorts)
        assertEquals("", restored.obfsMinPacketSize)
        assertTrue(restore(Repo.exportBackup()))
        assertEquals("legacy", Repo.selectedId.value)
        assertEquals("", Repo.servers.value.single().hoppingPorts)
    }

    @Test fun `legacy records with missing ids receive distinct generated ids`() {
        assertTrue(restore("""{"version":2,"servers":[
            {"address":"one.example.org"},{"address":"two.example.org"}
        ],"subs":[{"url":"https://one.example.org"},{"url":"https://two.example.org"}]}"""))
        assertEquals(2, Repo.servers.value.map { it.id }.toSet().size)
        assertEquals(2, Repo.subs.value.map { it.id }.toSet().size)
    }

    @Test fun `failed or unavailable storage never reports a successful restore`() {
        val backup = """{"version":2,"servers":[{"id":"incoming","address":"example.org"}]}"""
        Repo.updateServer(server("keep", "personal"))
        Repo.setSelected("keep")
        // Unit tests do not initialize Android SharedPreferences.
        assertFalse(Repo.restoreBackup(backup))
        assertFalse(Repo.restoreBackup(backup) { _, _, _, _ -> false })
        assertFalse(Repo.restoreBackup(backup) { _, _, _, _ -> throw IllegalStateException("storage failed") })
        assertEquals("keep", Repo.selectedId.value)
        assertEquals("keep", Repo.servers.value.single().id)
    }

    @Test fun `successful restore commits all sections before publishing state`() {
        val backup = """{"version":2,"servers":[{"id":"incoming","address":"example.org"}],
            "subs":[{"id":"source","url":"https://example.org/sub"}],
            "settings":{"multiPing":true},"selectedId":"incoming"}"""
        var writes = 0
        assertTrue(Repo.restoreBackup(backup) { servers, subs, settings, selected ->
            writes++
            assertTrue(Repo.servers.value.isEmpty())
            assertTrue(servers.contains("incoming"))
            assertTrue(subs.contains("source"))
            assertTrue(settings.contains("multiPing"))
            assertEquals("incoming", selected)
            true
        })
        assertEquals(1, writes)
        assertEquals("incoming", Repo.selectedId.value)
        assertEquals("source", Repo.subs.value.single().id)
        assertTrue(Repo.settings.value.multiPing)
    }

    @Test fun `invalid and future backups leave repository untouched`() {
        Repo.updateServer(server("keep", "personal"))
        Repo.setSelected("keep")
        listOf(
            "not json", "[]", "{\"version\":999,\"servers\":[{}]}",
            "{\"version\":2,\"servers\":[null]}",
            "{\"version\":2,\"servers\":\"not an array\"}",
            "{\"version\":2,\"servers\":[{\"proto\":\"unknown\"}]}",
            "{\"version\":2,\"servers\":[{}],\"settings\":{\"perAppPackages\":[null]}}",
            "{\"version\":2.5,\"servers\":[{}]}",
            "{\"version\":\"2\",\"servers\":[{}]}",
            "{\"version\":2,\"servers\":[{\"address\":99}]}",
            "{\"version\":2,\"servers\":[{\"port\":1.5}]}",
            "{\"version\":2,\"servers\":[{\"port\":2147483648}]}",
            "{\"version\":2,\"servers\":[{}],\"settings\":{\"multiPing\":\"false\"}}",
            "{\"version\":2,\"servers\":[{\"id\":\"same\"},{\"id\":\"same\"}]}",
            "{\"version\":2,\"subs\":[{\"id\":\"same\"},{\"id\":\"same\"}]}"
        ).forEach { input ->
            assertFalse("Unexpectedly restored: $input", restore(input))
            assertEquals("keep", Repo.selectedId.value)
            assertEquals("keep", Repo.servers.value.single().id)
        }
    }

    @Test fun `dedupe preserves separate live selected and favorite identities`() {
        val source = Subscription(id = "public", url = "https://example.org/sub")
        Repo.addSubscription(source)
        val live = server("live", source.id, "same.example.org")
        val selected = live.copy(id = "selected")
        val favorite = live.copy(id = "favorite", favorite = true)
        val duplicate = live.copy(id = "duplicate")
        listOf(live, selected, favorite, duplicate).forEach(Repo::updateServer)
        Repo.setSelected(selected.id)
        Repo.tunnel.value = TunnelUiState(status = TunnelStatus.CONNECTED, serverId = live.id)
        assertEquals(1, Repo.dedupeAllServers())
        assertEquals(setOf("live", "selected", "favorite"), Repo.servers.value.map { it.id }.toSet())
    }
}
