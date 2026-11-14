package ir.v2max.app

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
    @Before fun setUp() {
        Repo.resetEverything()
        Repo.tunnel.value = TunnelUiState()
    }

    @After fun cleanUp() {
        Repo.tunnel.value = TunnelUiState()
        Repo.resetEverything()
    }

    private fun server(id: String, source: String, address: String = "$id.example.org") =
        ServerConfig(id = id, name = id, address = address, uuid = id, subId = source)

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
