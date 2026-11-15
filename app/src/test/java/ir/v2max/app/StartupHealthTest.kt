package ir.v2max.app

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.net.ProbeResult
import ir.v2max.core.domain.SubscriptionHealthPolicy
import ir.v2max.core.model.PublicSources
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StartupHealthTest {
    private lateinit var settings: AppSettings
    private val day = SubscriptionHealthPolicy.MIN_INTERVAL_MS
    private val start = 10_000_000_000L
    private val ping = ProbeResult(42, "https://example.org/probe", false, FragmentProfile.BALANCED)

    @Before fun setUp() {
        settings = Repo.settings.value.copy()
        assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
        assertTrue(Repo.saveSettings(AppSettings(autoRefreshOnOpen = true)))
        Repo.tunnel.value = TunnelUiState()
    }

    @After fun tearDown() {
        Repo.tunnel.value = TunnelUiState()
        assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
        Repo.saveSettings(settings)
    }

    private fun sub(id: String = "sub", updated: Long = 1) =
        Subscription(id = id, name = id, url = "https://example.org/$id", updatedAt = updated)

    private fun server(id: String, subId: String = "sub") =
        ServerConfig(id = id, name = id, address = "$id.example.org", uuid = id, subId = subId)

    private fun permit() = requireNotNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC))
    private fun snapshot(source: Subscription, vararg configs: ServerConfig) =
        Repo.StartupSourceSnapshot(source.copy(), configs.mapTo(HashSet()) { it.fingerprint() })
    private fun observation(s: ServerConfig, good: Boolean) = Repo.StartupObservation(s.copy(), -1, if (good) ping else null)

    @Test fun `only completed real failures are removed while important or edited profiles survive`() {
        val source = sub()
        Repo.addSubscription(source)
        val good = server("good")
        val bad = server("bad")
        val favorite = server("favorite").copy(favorite = true)
        val selected = server("selected")
        val live = server("live")
        val editing = server("editing")
        val editedDuringScan = server("renamed")
        val standalone = server("standalone", "")
        val all = listOf(good, bad, favorite, selected, live, editing, editedDuringScan, standalone)
        all.forEach(Repo::updateServer)
        Repo.setSelected(selected.id)
        Repo.tunnel.value = TunnelUiState(status = TunnelStatus.CONNECTED, serverId = live.id)
        assertTrue(Repo.beginServerEdit(editing.id))
        val captured = Repo.servers.value.map { it.copy() }
        val observations = captured.map { observation(it, it.id == good.id) }
        Repo.updateServer(editedDuringScan.copy(name = "a new user name"))

        val result = Repo.applyStartupHealth(
            listOf(snapshot(source, *all.dropLast(1).toTypedArray())), captured, observations,
            permit(), Repo.startupHealthToken(), { true }, now = start
        )
        assertTrue(result.applied)
        assertEquals(1, result.removed)
        assertNull(Repo.serverById(bad.id))
        assertEquals(42, Repo.serverById(good.id)?.realPing)
        assertNotNull(Repo.serverById(favorite.id))
        assertNotNull(Repo.serverById(selected.id))
        assertNotNull(Repo.serverById(live.id))
        assertNotNull(Repo.serverById(editing.id))
        assertEquals("a new user name", Repo.serverById(editedDuringScan.id)?.name)
        assertNotNull("Personal standalone imports are never purged by subscription cleanup", Repo.serverById(standalone.id))
        assertEquals(selected.id, Repo.selectedId.value)
        assertEquals(live.id, Repo.tunnel.value.serverId)
        Repo.endServerEdit(editing.id)
    }

    @Test fun `a global failure never deletes profiles and uncertain or missing probes cannot count`() {
        val source = sub()
        val one = server("one")
        val missing = server("missing")
        Repo.addSubscription(source)
        Repo.updateServer(one)
        Repo.updateServer(missing)
        val captured = Repo.servers.value.map { it.copy() }
        val sourceSnapshot = snapshot(source, one, missing)
        val unproven = Repo.applyStartupHealth(listOf(sourceSnapshot), captured,
            listOf(observation(one, false), observation(missing, false)), permit(), Repo.startupHealthToken(),
            { true }, networkValidated = false, now = start)
        assertTrue(unproven.applied)
        assertEquals(0, unproven.removed)
        assertEquals(0, Repo.subById(source.id)?.healthFailureChecks)
        val incomplete = Repo.applyStartupHealth(listOf(sourceSnapshot), captured, listOf(observation(one, false)),
            permit(), Repo.startupHealthToken(), { true }, networkValidated = true, now = start)
        assertEquals(1, incomplete.unknownSources)
        assertEquals(0, Repo.subById(source.id)?.healthFailureChecks)
        assertEquals(setOf(one.id, missing.id), Repo.servers.value.map { it.id }.toSet())
    }

    @Test fun `two complete separated failures can only suggest deletion with fresh evidence and confirmation`() {
        val source = sub()
        val profile = server("only")
        Repo.addSubscription(source)
        Repo.updateServer(profile)
        val saved = Repo.servers.value.map { it.copy() }
        fun check(expected: Subscription, now: Long) = Repo.applyStartupHealth(
            listOf(snapshot(expected, profile)), saved, listOf(observation(profile, false)),
            permit(), Repo.startupHealthToken(), { true }, networkValidated = true, now = now
        )
        check(source, start)
        assertEquals(1, Repo.subById(source.id)?.healthFailureChecks)
        assertFalse(Repo.removeUnhealthySubscription(requireNotNull(Repo.subById(source.id)), start))
        check(requireNotNull(Repo.subById(source.id)), start + day - 1)
        assertEquals(1, Repo.subById(source.id)?.healthFailureChecks)
        check(requireNotNull(Repo.subById(source.id)), start + day)
        val warned = requireNotNull(Repo.subById(source.id))
        assertTrue(SubscriptionHealthPolicy.shouldSuggest(warned.healthEvidence(), warned.updatedAt, start + day))
        assertNotNull("An all-failed scan must not physically prune profiles", Repo.serverById(profile.id))
        assertTrue(Repo.deferUnhealthySubscription(warned, start + day))
        assertFalse(Repo.removeUnhealthySubscription(requireNotNull(Repo.subById(source.id)), start + day))
        check(requireNotNull(Repo.subById(source.id)), start + 8 * day)
        val renewed = requireNotNull(Repo.subById(source.id))
        assertTrue(Repo.beginServerEdit(profile.id))
        assertFalse("An open editor must not be deleted", Repo.removeUnhealthySubscription(renewed, start + 8 * day))
        Repo.endServerEdit(profile.id)
        Repo.tunnel.value = TunnelUiState(status = TunnelStatus.CONNECTED, serverId = profile.id)
        assertFalse("A live tunnel must not lose its backing profile", Repo.removeUnhealthySubscription(renewed, start + 8 * day))
        Repo.tunnel.value = TunnelUiState()
        assertTrue(Repo.removeUnhealthySubscription(renewed, start + 8 * day))
        assertNull(Repo.subById(source.id))
        assertNull(Repo.serverById(profile.id))
    }

    @Test fun `revoked permit invalid token changed network or refreshed source cannot publish`() {
        val source = sub()
        val good = server("good")
        val bad = server("bad")
        Repo.addSubscription(source)
        Repo.updateServer(good)
        Repo.updateServer(bad)
        val saved = Repo.servers.value.map { it.copy() }
        val sources = listOf(snapshot(source, good, bad))
        val observations = listOf(observation(good, true), observation(bad, false))
        val oldPermit = permit()
        val token = Repo.startupHealthToken()
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = false) })
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = true) })
        assertFalse(Repo.applyStartupHealth(sources, saved, observations, oldPermit, token, { true }).applied)
        Repo.invalidateStartupHealth()
        assertFalse(Repo.applyStartupHealth(sources, saved, observations, permit(), token, { true }).applied)
        assertFalse(Repo.applyStartupHealth(sources, saved, observations, permit(), Repo.startupHealthToken(), { false }).applied)
        Repo.updateSubscription(source.copy(updatedAt = 2))
        assertFalse(Repo.applyStartupHealth(sources, saved, observations, permit(), Repo.startupHealthToken(), { true }).applied)
        assertNotNull(Repo.serverById(bad.id))
    }

    @Test fun `deduplicated missing source members are unknown not evidence of failure`() {
        val source = sub("one")
        val other = sub("two")
        val good = server("good", source.id)
        val missing = server("missing", other.id)
        Repo.addSubscription(source)
        Repo.addSubscription(other)
        Repo.updateServer(good)
        val saved = Repo.servers.value.map { it.copy() }
        val result = Repo.applyStartupHealth(
            listOf(snapshot(source, good), snapshot(other, missing)), saved, listOf(observation(good, true)),
            permit(), Repo.startupHealthToken(), { true }, now = start
        )
        assertTrue(result.applied)
        assertEquals(1, result.unknownSources)
        assertEquals(0, Repo.subById(other.id)?.healthFailureChecks)
        assertNotNull(Repo.subById(other.id))
    }

    @Test fun `favorite and open editor survive a changed feed and explicit built in removal stays removed`() {
        val source = sub()
        val favorite = server("favorite").copy(favorite = true)
        val editing = server("editing")
        Repo.addSubscription(source)
        Repo.updateServer(favorite)
        Repo.updateServer(editing)
        assertTrue(Repo.beginServerEdit(editing.id))
        assertTrue(Repo.commitSubscriptionRefresh(source, listOf(server("new")), 0, 0, 0, 0))
        assertNotNull(Repo.serverById(favorite.id))
        assertNotNull(Repo.serverById(editing.id))
        Repo.endServerEdit(editing.id)

        val builtIn = PublicSources.DEFAULT.first()
        val added = requireNotNull(Repo.addBuiltInSource(builtIn.url))
        Repo.removeSubscription(added.id)
        Repo.ensureBuiltInSources()
        assertFalse(Repo.subs.value.any { it.url == builtIn.url })
        assertNotNull("The explicit source picker can restore a removed default", Repo.addBuiltInSource(builtIn.url))
    }

    @Test fun `changed source identity clears history and stale prompt cannot act`() {
        val source = sub().copy(healthFirstFailureAt = start, healthLastFailureAt = start + day,
            healthFailureChecks = 2, healthCheckedRevision = 1)
        Repo.addSubscription(source)
        val stale = requireNotNull(Repo.subById(source.id)).copy()
        Repo.updateSubscription(source.copy(url = "https://example.org/new-source"))
        val replaced = requireNotNull(Repo.subById(source.id))
        assertEquals(0, replaced.healthFailureChecks)
        assertFalse(Repo.removeUnhealthySubscription(stale, start + day))
        assertFalse(Repo.deferUnhealthySubscription(stale, start + day))
        assertNotNull(Repo.subById(source.id))
    }

    @Test fun `a concurrent refresh of one source invalidates the whole startup pass`() {
        val a = sub("one")
        val b = sub("two")
        val good = server("good", a.id)
        val bad = server("bad", b.id)
        Repo.addSubscription(a)
        Repo.addSubscription(b)
        Repo.updateServer(good)
        Repo.updateServer(bad)
        val captured = Repo.servers.value.map { it.copy() }
        val sources = listOf(snapshot(a, good), snapshot(b, bad))
        Repo.updateSubscription(b.copy(updatedAt = 2))
        val result = Repo.applyStartupHealth(sources, captured, listOf(observation(good, true), observation(bad, false)),
            permit(), Repo.startupHealthToken(), { true }, networkValidated = true, now = start)
        assertFalse(result.applied)
        assertNotNull(Repo.serverById(bad.id))
        assertEquals(0, Repo.subById(a.id)?.healthFailureChecks)
    }

    @Test fun `edited duplicate is not evidence for another source`() {
        val a = sub("one")
        val b = sub("two")
        val bad = server("bad", a.id)
        val duplicate = bad.copy(id = "duplicate", subId = b.id)
        Repo.addSubscription(a)
        Repo.addSubscription(b)
        Repo.updateServer(bad)
        Repo.updateServer(duplicate)
        val captured = Repo.servers.value.map { it.copy() }
        Repo.updateServer(duplicate.copy(name = "edited during scan"))
        val result = Repo.applyStartupHealth(
            listOf(snapshot(a, bad), snapshot(b, duplicate)), captured, listOf(observation(bad, false)),
            permit(), Repo.startupHealthToken(), { true }, networkValidated = true, now = start
        )
        assertTrue(result.applied)
        assertEquals(0, Repo.subById(b.id)?.healthFailureChecks)
        assertEquals(0, Repo.subById(a.id)?.healthFailureChecks)
        assertEquals(2, result.unknownSources)
    }

    @Test fun `standalone duplicate success cannot authorize cleanup of a failed feed`() {
        val source = sub()
        val failed = server("failed")
        val standalone = server("standalone", "").copy(address = failed.address, uuid = failed.uuid)
        Repo.addSubscription(source)
        Repo.updateServer(failed)
        Repo.updateServer(standalone)
        val captured = Repo.servers.value.map { it.copy() }
        val result = Repo.applyStartupHealth(
            listOf(snapshot(source, failed)), captured, listOf(observation(standalone, true)),
            permit(), Repo.startupHealthToken(), { true }, networkValidated = true, now = start
        )
        assertEquals(0, result.healthy)
        assertEquals(0, result.removed)
        assertNotNull(Repo.serverById(failed.id))
        assertEquals(0, Repo.subById(source.id)?.healthFailureChecks)
    }

    @Test fun `automatic dedupe never deletes standalone user imports`() {
        val source = sub()
        val standalone = server("personal", "")
        val standalone2 = standalone.copy(id = "personal-two")
        val fromFeed = standalone.copy(id = "feed-copy", subId = source.id)
        Repo.addSubscription(source)
        listOf(standalone, standalone2, fromFeed).forEach(Repo::updateServer)
        Repo.dedupeAllServers(automaticPermit = permit())
        assertNotNull(Repo.serverById(standalone.id))
        assertNotNull(Repo.serverById(standalone2.id))
    }

    @Test fun `built in cap keeps an open editor`() {
        val catalogue = requireNotNull(Repo.addBuiltInSource(PublicSources.DEFAULT.first().url))
        val editing = server("editor", catalogue.id)
        Repo.updateServer(editing)
        Repo.updateServer(server("other", catalogue.id))
        assertTrue(Repo.beginServerEdit(editing.id))
        assertEquals(1, Repo.trimBuiltInServers(maxTotal = 1))
        assertNotNull(Repo.serverById(editing.id))
        Repo.endServerEdit(editing.id)
    }

    @Test fun `backup and restore do not transfer device specific failure evidence`() {
        val source = sub().copy(healthFirstFailureAt = start, healthLastFailureAt = start + day,
            healthFailureChecks = 2, healthCheckedRevision = 1)
        Repo.addSubscription(source)
        Repo.updateServer(server("one"))
        val exported = Repo.exportBackup()
        assertTrue("Export must not carry failure evidence", exported.contains("\"healthFailureChecks\":0"))
        assertTrue(Repo.restoreBackup(exported) { _, _, _, _ -> true })
        assertEquals(0, Repo.subById(source.id)?.healthFailureChecks)
    }
}
