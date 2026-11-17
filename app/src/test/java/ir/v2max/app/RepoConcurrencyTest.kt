package ir.v2max.app

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.core.model.PublicSources
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
        assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
        Repo.saveSettings(AppSettings())
        Repo.tunnel.value = TunnelUiState()
    }

    @After fun cleanUp() {
        Repo.tunnel.value = TunnelUiState()
        assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
        Repo.saveSettings(previousSettings)
    }

    private fun server(id: String, source: String, address: String = "$id.example.org") =
        ServerConfig(id = id, name = id, address = address, uuid = id, subId = source)

    private fun restore(json: String): Boolean = Repo.restoreBackup(json) { _, _, _, _ -> true }

    @Test fun `only an explicit catalogue selection adds a built in source`() {
        val source = PublicSources.ALL.last()
        assertNull(Repo.addBuiltInSource(source.url + "?token=private"))
        assertTrue(Repo.subs.value.isEmpty())
        val added = Repo.addBuiltInSource(" ${source.url}/ ")
        assertNotNull(added)
        assertTrue(added!!.builtIn)
        assertEquals(source.url, added.url)
        assertNull(Repo.addBuiltInSource(source.url))
        assertEquals(1, Repo.subs.value.size)

        // An already-added personal subscription must never be silently converted.
        val personal = PublicSources.ALL.first()
        Repo.addSubscription(Subscription(id = "personal", url = personal.url, builtIn = false))
        assertNull(Repo.addBuiltInSource(personal.url))
        assertFalse(Repo.subById("personal")!!.builtIn)
    }

    @Test fun `add all includes the complete catalogue but startup uses defaults`() {
        assertEquals(PublicSources.DEFAULT.size, Repo.ensureBuiltInSources())
        assertEquals(PublicSources.ALL.size - PublicSources.DEFAULT.size, Repo.ensureBuiltInSources(includeAll = true))
        assertEquals(PublicSources.ALL.size, Repo.subs.value.size)
        assertTrue(Repo.subs.value.all { it.builtIn })
        assertEquals(0, Repo.ensureBuiltInSources(includeAll = true))
    }

    @Test fun `new default feeds are added once without replacing an existing subscription`() {
        val iboxz = "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/mix"
        val thomas = "https://raw.githubusercontent.com/ThomasJasperthecat/sub/main/sublist1.txt"
        // Simulate an existing installation with the old defaults and an
        // explicitly added Thomas subscription, including a saved profile.
        PublicSources.DEFAULT.take(6).forEachIndexed { index, source ->
            assertTrue(Repo.addSubscription(Subscription(id = "old-$index", url = source.url, builtIn = true)))
        }
        val previous = Subscription(id = "existing-thomas", name = "نام من", url = thomas, builtIn = true, updatedAt = 42)
        assertTrue(Repo.addSubscription(previous))
        val saved = server("saved", previous.id)
        Repo.updateServer(saved)

        assertEquals(1, Repo.ensureBuiltInSources(requireEnabled = true))
        assertEquals(previous, Repo.subById(previous.id))
        assertEquals(saved, Repo.serverById(saved.id))
        assertEquals(1, Repo.subs.value.count { PublicSources.normalize(it.url) == thomas })
        assertTrue(Repo.subs.value.single { it.url == iboxz }.builtIn)
        assertEquals(0, Repo.ensureBuiltInSources(requireEnabled = true))
    }

    @Test fun `new defaults respect free source opt out personal imports and explicit deletion`() {
        val iboxz = "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/mix"
        val thomas = "https://raw.githubusercontent.com/ThomasJasperthecat/sub/main/sublist1.txt"
        assertTrue(Repo.updateSettings { it.copy(useFreeSources = false) })
        assertEquals(0, Repo.ensureBuiltInSources(requireEnabled = true))
        assertTrue(Repo.subs.value.isEmpty())

        val personal = Subscription(id = "personal-thomas", url = "$thomas/", builtIn = false)
        assertTrue(Repo.addSubscription(personal))
        assertTrue(Repo.updateSettings { it.copy(useFreeSources = true) })
        Repo.ensureBuiltInSources(requireEnabled = true)
        assertEquals(personal, Repo.subById(personal.id))
        assertEquals(1, Repo.subs.value.count { PublicSources.normalize(it.url) == thomas })
        val removed = Repo.subs.value.single { it.url == iboxz }
        Repo.removeSubscription(removed.id)
        Repo.ensureBuiltInSources(requireEnabled = true)
        assertFalse("A source explicitly removed by the user must stay removed", Repo.subs.value.any { it.url == iboxz })
        assertNotNull("The source picker can deliberately restore it", Repo.addBuiltInSource(iboxz))
        assertEquals(1, Repo.subs.value.count { it.url == iboxz })

        // Removing a personal subscription with the exact catalogue address is
        // also an explicit removal; startup must not recreate it as public.
        Repo.removeSubscription(personal.id)
        Repo.ensureBuiltInSources(requireEnabled = true)
        assertFalse("A deleted personal copy must not reappear as a default", Repo.subs.value.any {
            PublicSources.normalize(it.url) == thomas
        })
        val restored = requireNotNull(Repo.addBuiltInSource(thomas))
        val private = Subscription(id = "private-query", url = "$thomas?token=private-fixture", builtIn = false)
        assertTrue(Repo.addSubscription(private))
        Repo.removeSubscription(private.id)
        assertNotNull(Repo.subById(restored.id))
        assertFalse("Private URL parameters must not enter the public opt-out list",
            Repo.settings.value.disabledBuiltInSources.any { "private-fixture" in it })
    }

    @Test fun `full public catalogue does not starve newly refreshed requested feeds`() {
        val previous = Subscription(id = "old", url = PublicSources.DEFAULT.first().url, builtIn = true)
        assertTrue(Repo.addSubscription(previous))
        val old = (0 until 700).map { server("old-$it", previous.id) }
        Repo.replaceSubscriptionServers(previous.id, old)
        Repo.setSelected("old-1")
        Repo.setFavorite("old-699", true)
        Repo.tunnel.value = TunnelUiState(status = TunnelStatus.CONNECTED, serverId = "old-2")
        val iboxz = requireNotNull(Repo.addBuiltInSource(
            "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/mix"
        ))
        val thomas = requireNotNull(Repo.addBuiltInSource(
            "https://raw.githubusercontent.com/ThomasJasperthecat/sub/main/sublist1.txt"
        ))
        assertTrue(Repo.commitSubscriptionRefresh(iboxz, (0 until 150).map { server("ibox-$it", iboxz.id) }, 0, 0, 0, 0))
        assertTrue(Repo.commitSubscriptionRefresh(thomas, (0 until 17).map { server("thomas-$it", thomas.id) }, 0, 0, 0, 0))

        assertEquals(700, Repo.servers.value.size)
        assertEquals("A full catalogue must leave a usable sample from the newly added feed", 50,
            Repo.servers.value.count { it.subId == iboxz.id })
        assertEquals(17, Repo.servers.value.count { it.subId == thomas.id })
        assertNotNull(Repo.serverById("old-1"))
        assertNotNull(Repo.serverById("old-2"))
        assertTrue(requireNotNull(Repo.serverById("old-699")).favorite)
    }

    @Test fun `all default sources retain a sample regardless of refresh order`() {
        listOf(false, true).forEach { reverse ->
            assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
            val sources = PublicSources.DEFAULT.mapIndexed { index, item ->
                Subscription(id = "source-$index", url = item.url, builtIn = true).also {
                    assertTrue(Repo.addSubscription(it))
                }
            }
            val order = if (reverse) sources.reversed() else sources
            order.forEach { sub ->
                val incoming = (0 until 150).map { server("${sub.id}-$it", sub.id) }
                assertTrue(Repo.commitSubscriptionRefresh(sub, incoming, 0, 0, 0, 0))
            }
            assertEquals(700, Repo.servers.value.size)
            sources.forEach { sub ->
                assertTrue("Source ${sub.id} lost its sample with reverse=$reverse",
                    Repo.servers.value.count { it.subId == sub.id } >= 50)
            }
        }
    }

    @Test fun `capacity keeps measured servers and public source samples beside a large personal list`() {
        val previous = Subscription(id = "old", url = PublicSources.DEFAULT.first().url, builtIn = true)
        assertTrue(Repo.addSubscription(previous))
        val measured = (0 until 700).map {
            server("healthy-$it", previous.id).copy(realPing = 40, testedUrl = "https://example.org/probe")
        }
        Repo.replaceSubscriptionServers(previous.id, measured)
        val iboxz = requireNotNull(Repo.addBuiltInSource(
            "https://raw.githubusercontent.com/iboxz/free-v2ray-collector/main/main/mix"
        ))
        val thomas = requireNotNull(Repo.addBuiltInSource(
            "https://raw.githubusercontent.com/ThomasJasperthecat/sub/main/sublist1.txt"
        ))
        assertTrue(Repo.commitSubscriptionRefresh(iboxz, (0 until 150).map { server("ibox-$it", iboxz.id) }, 0, 0, 0, 0))
        assertTrue(Repo.commitSubscriptionRefresh(thomas, (0 until 17).map { server("thomas-$it", thomas.id) }, 0, 0, 0, 0))
        assertEquals("Recorded real successes must survive the soft ceiling", 700,
            Repo.servers.value.count { it.subId == previous.id })
        assertEquals(50, Repo.servers.value.count { it.subId == iboxz.id })
        assertEquals(17, Repo.servers.value.count { it.subId == thomas.id })

        // Personal imports do not consume the public sources' capacity, even
        // when the separate total-size cleanup is triggered.
        val personal = (0 until 3001).map { server("personal-$it", "") }
        assertEquals(personal.size, Repo.addServers(personal))
        repeat(40) { Repo.updateServer(server("extra-$it", previous.id)) }
        assertEquals(40, Repo.trimBuiltInServers())
        assertEquals(3001, Repo.servers.value.count { it.subId.isBlank() })
        assertEquals(700, Repo.servers.value.count { it.subId == previous.id })
        assertEquals(50, Repo.servers.value.count { it.subId == iboxz.id })
        assertEquals(17, Repo.servers.value.count { it.subId == thomas.id })
    }

    @Test fun `editor preserves newer health on rename and clears it for a different connection`() {
        val original = server("edit", "personal").copy(
            tcpPing = 20, realPing = 30, googlePing = 30, instagramPing = 40, youtubePing = 50,
            speedKbps = 100, successCount = 4, failCount = 2, testedAt = 123,
            testedUrl = "https://example.org/probe", testedFragment = true
        )
        Repo.updateServer(original)
        val oldEditorSnapshot = original.copy()
        val epoch = Repo.currentDataEpoch()
        Repo.setSpeed(original.id, 900)
        Repo.setFavorite(original.id, true)
        assertTrue(Repo.saveEditedServer(oldEditorSnapshot.copy(name = "renamed"), original.fingerprint(), epoch))
        val renamed = requireNotNull(Repo.serverById(original.id))
        assertEquals("renamed", renamed.name)
        assertEquals(900, renamed.speedKbps)
        assertTrue(renamed.favorite)
        assertEquals(30, renamed.realPing)
        assertEquals(123L, renamed.testedAt)

        assertTrue(Repo.saveEditedServer(renamed.copy(uuid = "different"), renamed.fingerprint(), epoch))
        val changed = requireNotNull(Repo.serverById(original.id))
        assertEquals(-1, changed.tcpPing)
        assertEquals(-1, changed.realPing)
        assertEquals(-1, changed.googlePing)
        assertEquals(-1, changed.instagramPing)
        assertEquals(-1, changed.youtubePing)
        assertEquals(0, changed.speedKbps)
        assertEquals(0, changed.successCount)
        assertEquals(0, changed.failCount)
        assertEquals(0L, changed.testedAt)
        assertEquals("", changed.testedUrl)
        assertFalse(changed.testedFragment)
        assertTrue(changed.favorite)
        assertEquals("personal", changed.subId)
        Repo.removeServer(changed.id)
        assertFalse(Repo.saveEditedServer(changed, changed.fingerprint(), epoch))
        assertNull(Repo.serverById(changed.id))
    }

    @Test fun `stale edits and clones cannot overwrite changed or restored data`() {
        val original = server("edit", "source").copy(realPing = 55, testedAt = 100, testedUrl = "https://example.org")
        Repo.updateServer(original)
        val oldEpoch = Repo.currentDataEpoch()
        val newProfile = original.copy(uuid = "new credential")
        Repo.updateServer(newProfile)
        val clone = original.copy(id = "clone")
        assertFalse(Repo.saveEditedServer(original.copy(name = "stale"), original.fingerprint(), oldEpoch))
        assertFalse(Repo.cloneEditedServer(clone, original.id, original.fingerprint(), oldEpoch))
        assertEquals("new credential", Repo.serverById(original.id)?.uuid)
        assertNull(Repo.serverById(clone.id))

        val beforeRestore = Repo.currentDataEpoch()
        assertTrue(restore(Repo.exportBackup()))
        assertFalse(Repo.saveEditedServer(newProfile.copy(name = "stale after restore"), newProfile.fingerprint(), beforeRestore))
        assertFalse(Repo.cloneEditedServer(clone, newProfile.id, newProfile.fingerprint(), beforeRestore))
        assertFalse(Repo.cloneEditedServer(clone.copy(id = newProfile.id), newProfile.id, newProfile.fingerprint(), Repo.currentDataEpoch()))
        assertEquals("edit", Repo.serverById(newProfile.id)?.name)
    }

    @Test fun `clone is independent of its subscription and has no inherited health`() {
        val source = Subscription(id = "source", url = "https://example.org/sub")
        Repo.addSubscription(source)
        val original = server("edit", source.id).copy(
            favorite = true, tcpPing = 3, realPing = 4, googlePing = 5, instagramPing = 6,
            youtubePing = 7, speedKbps = 100, successCount = 2, failCount = 1,
            testedAt = 100, testedUrl = "https://example.org", testedFragment = true
        )
        Repo.updateServer(original)
        val epoch = Repo.currentDataEpoch()
        assertTrue(Repo.cloneEditedServer(original.copy(id = "clone"), original.id, original.fingerprint(), epoch))
        val clone = requireNotNull(Repo.serverById("clone"))
        assertEquals("", clone.subId)
        assertFalse(clone.favorite)
        assertEquals(-1, clone.tcpPing)
        assertEquals(-1, clone.realPing)
        assertEquals(-1, clone.googlePing)
        assertEquals(-1, clone.instagramPing)
        assertEquals(-1, clone.youtubePing)
        assertEquals(0, clone.speedKbps)
        assertEquals(0, clone.successCount)
        assertEquals(0, clone.failCount)
        assertEquals(0L, clone.testedAt)
        assertEquals("", clone.testedUrl)
        assertFalse(clone.testedFragment)
        assertEquals(clone.id, Repo.selectedId.value)
        Repo.removeSubscription(source.id)
        assertNotNull(Repo.serverById(clone.id))
    }

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

    @Test fun `automatic refresh cannot publish after opt out and reenable but manual refresh still works`() {
        val source = Subscription(id = "personal", url = "https://example.org/private", updatedAt = 1)
        Repo.addSubscription(source)
        Repo.updateServer(server("old", source.id))
        val startup = requireNotNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC))
        val failover = requireNotNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.FAILOVER))
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = false) })
        assertNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC))
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = true) })
        val incoming = listOf(server("new", source.id))
        assertFalse(Repo.automaticRefreshIsCurrent(startup))
        assertFalse(Repo.commitSubscriptionRefresh(source, incoming, 0, 0, 0, 0, automaticPermit = startup))
        Repo.recordSubscriptionRefreshError(source, "stale error", automaticPermit = startup)
        assertEquals("", Repo.subById(source.id)?.lastError)
        assertEquals("old", Repo.servers.value.single().id)
        assertFalse(Repo.automaticRefreshIsCurrent(failover))
        val newFailover = requireNotNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.FAILOVER))
        assertTrue(Repo.updateSettings { it.copy(autoUpdateSubs = false) })
        assertTrue(Repo.updateSettings { it.copy(autoUpdateSubs = true) })
        assertFalse(Repo.automaticRefreshIsCurrent(newFailover))
        assertTrue(Repo.automaticRefreshIsCurrent(requireNotNull(
            Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC))))
        assertTrue(Repo.commitSubscriptionRefresh(source, incoming, 0, 0, 0, 0))
        assertEquals("new", Repo.servers.value.single().id)
    }

    @Test fun `unavailable preferences fail closed and allow initialization to be retried`() {
        Repo.updateServer(server("old", "personal"))
        val epoch = Repo.currentDataEpoch()
        Repo.initializeSafely { throw SecurityException("storage unavailable") }
        assertTrue(Repo.isStorageReadOnly())
        assertNotEquals(epoch, Repo.currentDataEpoch())
        assertTrue(Repo.settings.value.appLock)
        assertFalse(Repo.settings.value.allowExternalAutomation)
        assertFalse(Repo.settings.value.autoConnectOnOpen)
        assertFalse(Repo.settings.value.autoConnectOnBoot)
        assertFalse(Repo.settings.value.autoRefreshOnOpen)
        assertFalse(Repo.settings.value.autoUpdateSubs)
        assertFalse(Repo.settings.value.autoUpdateCheck)
        assertNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC))
        assertEquals(0, Repo.addServers(listOf(server("new", "personal"))))
        assertTrue(Repo.recoveryWarning.value.isNotBlank())
        var retried = false
        Repo.initializeSafely { retried = true }
        assertTrue(retried)
    }

    @Test fun `read only repository rejects edits and failed reset preserves data`() {
        val source = Subscription(id = "source", url = "https://example.org/private")
        Repo.addSubscription(source)
        Repo.updateServer(server("keep", source.id))
        Repo.setSelected("keep")
        val flag = Repo::class.java.getDeclaredField("canWriteServers").apply { isAccessible = true }
        val previous = flag.getBoolean(null)
        try {
            flag.setBoolean(null, false)
            assertTrue(Repo.isStorageReadOnly())
            assertEquals(0, Repo.addServers(listOf(server("new", source.id))))
            assertFalse(Repo.addSubscription(Subscription(id = "new-sub")))
            Repo.removeServer("keep")
            Repo.removeSubscription(source.id)
            Repo.updateServer(server("edited", source.id))
            assertFalse(Repo.updateSettings { it.copy(appLock = true) })
            assertNotNull(Repo.serverById("keep"))
            assertNotNull(Repo.subById(source.id))
            assertFalse(Repo.settings.value.appLock)

            var persisted = false
            assertFalse(Repo.resetEverything(revokeConsent = { false }, persist = { persisted = true; true }))
            assertFalse(persisted)
            assertFalse(Repo.resetEverything(revokeConsent = { true }, persist = { false }))
            assertTrue(Repo.isStorageReadOnly())
            assertNotNull(Repo.serverById("keep"))
            assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
            assertFalse(Repo.isStorageReadOnly())
            assertTrue(Repo.servers.value.isEmpty())
            assertTrue(Repo.subs.value.isEmpty())
            assertFalse(Repo.settings.value.autoConnectOnOpen)
            assertFalse(Repo.settings.value.autoRefreshOnOpen)
            assertFalse(Repo.settings.value.useFreeSources)
        } finally {
            flag.setBoolean(null, previous)
        }
    }

    @Test fun `restore and reset invalidate earlier downloads even for the same source`() {
        val source = Subscription(id = "source", url = "https://example.org/private", updatedAt = 1)
        Repo.addSubscription(source)
        Repo.updateServer(server("old", source.id))
        val beforeRestore = Repo.currentDataEpoch()
        val automatic = requireNotNull(Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC))
        assertTrue(restore(Repo.exportBackup()))
        assertNotEquals(beforeRestore, Repo.currentDataEpoch())
        assertFalse(Repo.automaticRefreshIsCurrent(automatic))
        assertFalse(Repo.commitSubscriptionRefresh(
            source, listOf(server("stale", source.id)), 0, 0, 0, 0, expectedEpoch = beforeRestore
        ))
        assertEquals("old", Repo.servers.value.single().id)
        assertEquals(0, Repo.addServers(listOf(server("stale-import", "")), expectedEpoch = beforeRestore))
        assertFalse(Repo.addFetchedSubscription(
            Subscription(id = "stale-sub"), listOf(server("stale-node", "stale-sub")), beforeRestore
        ))
        val beforeReset = Repo.currentDataEpoch()
        assertTrue(Repo.resetEverything(revokeConsent = { true }, persist = { true }))
        assertNotEquals(beforeReset, Repo.currentDataEpoch())
        assertFalse(Repo.addFetchedSubscription(source, emptyList(), beforeReset))
        var restoredStale = false
        val backup = """{"servers":[{"id":"old","address":"example.org"}]}"""
        assertFalse(Repo.restoreBackup(backup, expectedEpoch = beforeReset) { _, _, _, _ -> restoredStale = true; true })
        assertFalse(restoredStale)
        assertTrue(Repo.subs.value.isEmpty())
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

    @Test fun `backup cannot grant device local automation consent`() {
        val forged = """{"version":3,"servers":[{"id":"incoming","address":"example.org"}],
            "settings":{"appLock":false,"allowExternalAutomation":true}}"""
        var writtenSettings = ""
        assertTrue(Repo.restoreBackup(forged) { _, _, settings, _ -> writtenSettings = settings; true })
        assertFalse(Repo.settings.value.allowExternalAutomation)
        assertFalse(writtenSettings.contains("allowExternalAutomation"))
        assertFalse(Repo.exportBackup().contains("allowExternalAutomation"))

        // An existing local opt-in survives a restore; enabling the app lock
        // must revoke it so a subsequent unlock cannot silently re-enable it.
        Repo.updateSettings { it.copy(allowExternalAutomation = true) }
        assertTrue(restore(forged))
        assertTrue(Repo.settings.value.allowExternalAutomation)
        assertTrue(restore(forged.replace("\"appLock\":false", "\"appLock\":true")))
        assertFalse(Repo.settings.value.allowExternalAutomation)
        Repo.updateSettings { it.copy(appLock = false) }
        assertFalse(Repo.settings.value.allowExternalAutomation)
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
            "{\"version\":2,\"servers\":null,\"subs\":[{}]}",
            "{\"version\":2,\"servers\":[{}],\"subs\":null}",
            "{\"version\":2,\"servers\":[{}],\"settings\":null}",
            "{\"version\":null,\"servers\":[{}]}",
            "{\"version\":2,\"servers\":\"not an array\"}",
            "{\"version\":2,\"servers\":[{\"proto\":\"unknown\"}]}",
            "{\"version\":2,\"servers\":[{\"proto\":null}]}",
            "{\"version\":2,\"servers\":[{\"network\":null}]}",
            "{\"version\":2,\"servers\":[{\"security\":null}]}",
            "{\"version\":2,\"servers\":[{}],\"settings\":{\"appLock\":null}}",
            "{\"version\":2,\"servers\":[{}],\"settings\":{\"appLock\":\"false\"}}",
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
