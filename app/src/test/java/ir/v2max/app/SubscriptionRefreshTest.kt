package ir.v2max.app

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.ServerDisplayOrder
import ir.v2max.app.data.Subscription
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.net.ProbeResult
import ir.v2max.app.net.StartupMaintenance
import ir.v2max.app.net.SubscriptionFetcher
import ir.v2max.app.net.SubscriptionRefresh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SubscriptionRefreshTest {
    private lateinit var previous: AppSettings
    private val ping = ProbeResult(42, "https://example.org/probe", false, FragmentProfile.BALANCED)

    @Before fun setUp() {
        previous = Repo.settings.value.copy()
        assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
        assertTrue(Repo.saveSettings(AppSettings(autoRefreshOnOpen = true)))
        Repo.tunnel.value = TunnelUiState()
    }

    @After fun tearDown() {
        Repo.tunnel.value = TunnelUiState()
        assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
        Repo.saveSettings(previous)
    }

    private fun source(id: String) = Subscription(id = id, name = id, url = "https://example.org/$id")
    private fun server(id: String, sub: String = "") =
        ServerConfig(id = id, address = "$id.example.org", uuid = id, subId = sub)

    private fun apply(
        sources: List<Repo.StartupSourceSnapshot>,
        permit: Repo.AutomaticRefreshPermit?,
        token: Long,
        results: Map<String, Boolean?>
    ): StartupMaintenance.Result {
        val snapshots = Repo.servers.value.map { it.copy() }
        val observations = snapshots.filter { it.id in results }.map { cfg ->
            val result = results[cfg.id]
            Repo.StartupObservation(cfg, 10, if (result == true) ping else null, conclusive = result != null)
        }
        val applied = Repo.applyStartupHealth(sources, snapshots, observations, permit, token, { true }, now = 1000L)
        return StartupMaintenance.Result(
            if (applied.applied) StartupMaintenance.Status.COMPLETE else StartupMaintenance.Status.CHANGED,
            applied
        )
    }

    @Test fun `manual refresh works without auto opt in and keeps a measured protected duplicate`() = runBlocking {
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = false) })
        val sub = source("single")
        assertTrue(Repo.addSubscription(sub))
        val good = server("good")
        val dead = server("dead")
        val unknown = server("unknown")
        val personal = good.copy(id = "personal", tcpPing = 1)
        Repo.updateServer(personal)
        Repo.setSelected(personal.id)
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        val fetched = SubscriptionRefresh.run(listOf(sub), epoch, token, fetch = {
            SubscriptionFetcher.Result(configs = listOf(good, dead, unknown))
        })
        assertEquals(1, fetched.updated)
        val finished = StartupMaintenance.finishPrepared(fetched.sources, epoch, null, token) {
            apply(fetched.sources, null, token, mapOf(good.id to true, dead.id to false, unknown.id to null))
        }
        assertEquals(StartupMaintenance.Status.COMPLETE, finished.status)
        assertEquals(1, finished.health.removed)
        assertEquals(1, finished.duplicatesRemoved)
        assertNull(Repo.serverById(dead.id))
        assertNotNull("An indeterminate native result is not a dead server", Repo.serverById(unknown.id))
        assertNull("The unprotected duplicate should be collapsed", Repo.serverById(good.id))
        assertEquals("The user's selected identity must survive", personal.id, Repo.selectedId.value)
        assertEquals("The protected identical copy should receive the real measurement", 42, Repo.serverById(personal.id)?.recordedRealPing())
    }

    @Test fun `bulk and periodic phases check all sources before dedupe`() = runBlocking {
        val first = source("first")
        val second = source("second")
        listOf(first, second).forEach { assertTrue(Repo.addSubscription(it)) }
        val selected = server("selected")
        Repo.updateServer(selected)
        Repo.setSelected(selected.id)
        val good = server("good")
        val duplicate = good.copy(id = "duplicate")
        val dead = server("dead")
        val permit = requireNotNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC))
        val token = Repo.startupHealthToken()
        val progress = mutableListOf<Int>()
        val fetched = SubscriptionRefresh.run(listOf(first, second), permit.dataEpoch, token, permit,
            parallelism = 2,
            fetch = { sub -> SubscriptionFetcher.Result(configs = if (sub.id == first.id) listOf(good, dead) else listOf(duplicate)) },
            onProgress = { done, _ -> progress.add(done) })
        assertEquals(2, fetched.updated)
        assertEquals(listOf(1, 2), progress)
        assertNotNull("A representative from each source must exist before the scan", Repo.serverById(duplicate.id))
        val finished = StartupMaintenance.finishPrepared(fetched.sources, permit.dataEpoch, permit, token) {
            // One identical fingerprint represents both freshly fetched sources.
            apply(fetched.sources, permit, token, mapOf(good.id to true, dead.id to false))
        }
        assertEquals(StartupMaintenance.Status.COMPLETE, finished.status)
        assertEquals(1, finished.health.removed)
        assertEquals(1, finished.duplicatesRemoved)
        assertNull(Repo.serverById(dead.id))
        assertEquals(1, Repo.servers.value.count { it.fingerprint() == good.fingerprint() })
        assertEquals(42, Repo.servers.value.first { it.fingerprint() == good.fingerprint() }.recordedRealPing())
    }

    @Test fun `offline or broken probe collapses only duplicates and keeps unknown profiles`() = runBlocking {
        val first = source("first")
        val other = source("other")
        listOf(first, other).forEach { assertTrue(Repo.addSubscription(it)) }
        val selected = server("selected")
        Repo.updateServer(selected)
        Repo.setSelected(selected.id)
        val one = server("one")
        val duplicate = one.copy(id = "duplicate", subId = other.id)
        Repo.updateServer(duplicate)
        val unknown = server("unknown")
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        val fetched = SubscriptionRefresh.run(listOf(first), epoch, token, fetch = {
            SubscriptionFetcher.Result(configs = listOf(one, unknown))
        })
        val finished = StartupMaintenance.finishPrepared(fetched.sources, epoch, null, token) {
            throw IllegalStateException("native setup failed")
        }
        assertEquals(StartupMaintenance.Status.INCOMPLETE, finished.status)
        assertEquals(1, finished.duplicatesRemoved)
        assertEquals(1, Repo.servers.value.count { it.fingerprint() == one.fingerprint() })
        assertNotNull(Repo.serverById(unknown.id))
        assertEquals(0, Repo.subById(first.id)?.healthFailureChecks)
    }

    @Test fun `source revision changed during scan prevents final cleanup`() = runBlocking {
        val first = source("first")
        val other = source("other")
        listOf(first, other).forEach { assertTrue(Repo.addSubscription(it)) }
        val selected = server("selected")
        Repo.updateServer(selected)
        Repo.setSelected(selected.id)
        val one = server("one")
        Repo.updateServer(one.copy(id = "duplicate", subId = other.id))
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        val fetched = SubscriptionRefresh.run(listOf(first), epoch, token, fetch = {
            SubscriptionFetcher.Result(configs = listOf(one))
        })
        Repo.updateSubscription(requireNotNull(Repo.subById(first.id)).copy(url = "https://example.org/changed"))
        val finished = StartupMaintenance.finishPrepared(fetched.sources, epoch, null, token) {
            StartupMaintenance.Result(StartupMaintenance.Status.NO_NETWORK)
        }
        assertEquals(StartupMaintenance.Status.CHANGED, finished.status)
        assertEquals("An obsolete refresh must not dedupe newer data", 2,
            Repo.servers.value.count { it.fingerprint() == one.fingerprint() })
    }

    @Test fun `late automatic response cannot commit after opt out and reenable`() = runBlocking {
        val sub = source("periodic")
        assertTrue(Repo.addSubscription(sub))
        val old = server("old", sub.id)
        Repo.updateServer(old)
        val permit = requireNotNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC))
        val token = Repo.startupHealthToken()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            SubscriptionRefresh.run(listOf(sub), permit.dataEpoch, token, permit, fetch = {
                entered.complete(Unit)
                release.await()
                SubscriptionFetcher.Result(configs = listOf(server("late")))
            })
        }
        withTimeout(5_000) { entered.await() }
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = false) })
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = true) })
        release.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertNotNull(Repo.serverById(old.id))
        assertNull(Repo.serverById("late"))
    }

    @Test fun `user or recovery cancels a tracked refresh and leaves its source intact`() = runBlocking {
        val sub = source("cancel")
        assertTrue(Repo.addSubscription(sub))
        val old = server("old", sub.id)
        Repo.updateServer(old)
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            StartupMaintenance.track(token) {
                SubscriptionRefresh.run(listOf(sub), epoch, token, fetch = {
                    entered.complete(Unit)
                    awaitCancellation()
                })
            }
        }
        withTimeout(5_000) { entered.await() }
        StartupMaintenance.cancelAll()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertNotNull(Repo.serverById(old.id))
        assertEquals("", Repo.subById(sub.id)?.lastError)
    }

    @Test fun `concurrent initial imports do not duplicate an exact subscription URL`() = runBlocking {
        val epoch = Repo.currentDataEpoch()
        val one = source("import-one").copy(url = "https://example.org/feed?profile=one")
        val two = source("import-two").copy(url = " https://example.org/feed?profile=one ")
        val accepted = listOf(one, two).map { sub ->
            async(Dispatchers.Default) {
                Repo.addFetchedSubscription(sub, listOf(server(sub.id)), epoch, deferBuiltInCap = true)
            }
        }.awaitAll()
        assertEquals(1, accepted.count { it })
        assertEquals(1, Repo.subs.value.size)
        // Personal URL paths and query parameters are not normalized away.
        val different = source("different").copy(url = "https://example.org/feed?profile=two")
        assertTrue(Repo.addFetchedSubscription(different, listOf(server("different")), epoch))
        assertEquals(2, Repo.subs.value.size)
    }

    @Test fun `fresh failure clears an old ping but retains a protected personal duplicate`() = runBlocking {
        val sub = source("mixed")
        assertTrue(Repo.addSubscription(sub))
        val good = server("good")
        val failed = server("failed")
        val selected = failed.copy(id = "personal", subId = "", realPing = 12, testedAt = 100, testedUrl = ping.url)
        Repo.updateServer(selected)
        Repo.setSelected(selected.id)
        val epoch = Repo.currentDataEpoch()
        val token = Repo.startupHealthToken()
        val fetched = SubscriptionRefresh.run(listOf(sub), epoch, token, fetch = {
            SubscriptionFetcher.Result(configs = listOf(good, failed))
        })
        val finished = StartupMaintenance.finishPrepared(fetched.sources, epoch, null, token) {
            apply(fetched.sources, null, token, mapOf(good.id to true, failed.id to false))
        }
        assertEquals(StartupMaintenance.Status.COMPLETE, finished.status)
        assertNull(Repo.serverById(failed.id))
        assertNotNull(Repo.serverById(selected.id))
        assertEquals(selected.id, Repo.selectedId.value)
        assertEquals(-1, Repo.serverById(selected.id)?.recordedRealPing())
    }

    @Test fun `a new real measurement reaches protected duplicates without deleting or reordering them`() {
        val personal = server("same").copy(id = "personal", subId = "")
        val other = server("other")
        val favorite = personal.copy(id = "favorite", subId = "feed", favorite = true,
            realPing = 42, testedAt = 100, testedUrl = ping.url)
        val editing = personal.copy(id = "editing", subId = "feed")
        listOf(personal, other, favorite, editing).forEach(Repo::updateServer)
        Repo.setSelected(personal.id)
        assertTrue(Repo.beginServerEdit(editing.id))
        assertEquals(0, Repo.dedupeAllServers())
        assertEquals(listOf("personal", "other", "favorite", "editing"), Repo.servers.value.map { it.id })
        assertEquals(42, Repo.serverById(personal.id)?.recordedRealPing())
        assertEquals(-1, Repo.serverById(editing.id)?.recordedRealPing())
        Repo.endServerEdit(editing.id)
    }

    @Test fun `fastest display uses real ping not tcp and leaves unknown last`() {
        val fast = server("fast").copy(realPing = 35, testedAt = 100, testedUrl = ping.url)
        val slow = server("slow").copy(realPing = 90, testedAt = 200, testedUrl = ping.url)
        val tcpOnly = server("tcp").copy(tcpPing = 1)
        val incomplete = server("incomplete").copy(realPing = 2, testedAt = 0, testedUrl = "")
        val recentTie = fast.copy(id = "recent-tie", testedAt = 200)
        val ordered = listOf(tcpOnly, slow, fast, incomplete, recentTie).sortedWith(ServerDisplayOrder.fastest)
        assertEquals(listOf("recent-tie", "fast", "slow"), ordered.take(3).map { it.id })
        assertEquals(setOf("tcp", "incomplete"), ordered.drop(3).map { it.id }.toSet())
    }
}
