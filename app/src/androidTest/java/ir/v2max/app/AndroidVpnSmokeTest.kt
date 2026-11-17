package ir.v2max.app

import android.app.Application
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ir.v2max.app.core.Failover
import ir.v2max.app.core.PreflightTickets
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.core.TunnelController
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.PerAppMode
import ir.v2max.app.data.Proto
import ir.v2max.app.data.Repo
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.data.UpdateInfo
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.ProbeResult
import ir.v2max.app.net.CarrierProbe
import ir.v2max.app.net.LinkParser
import ir.v2max.app.net.SpeedTester
import ir.v2max.app.net.UpdateInstaller
import ir.v2max.app.service.Notifier
import ir.v2max.app.service.SubSyncWorker
import ir.v2max.app.service.V2maxVpnService
import ir.v2max.app.service.V2maxWidget
import ir.v2max.app.service.WidgetActionReceiver
import ir.v2max.app.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * CI runs an isolated Xray server on the emulator host. 198.51.100.1 has no direct
 * route to the local HTTP fixture; the response must traverse Android's VPN/TUN.
 */
@RunWith(AndroidJUnit4::class)
class AndroidVpnSmokeTest {
    private fun fixture(id: String, port: Int, favorite: Boolean = false) = ServerConfig(
        id = id, name = id, proto = Proto.VLESS, address = "10.0.2.2", port = port,
        uuid = "11111111-2222-3333-4444-555555555555", method = "none", network = "tcp",
        security = "none", subId = "fixture", favorite = favorite
    )

    @Test fun updaterAcceptsOnlyTheInstalledPackageIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val installedApk = File(context.applicationInfo.sourceDir)
        val testApk = File(instrumentation.context.applicationInfo.sourceDir)
        assertTrue("Installed APK did not match its own package and signer",
            UpdateInstaller.sameSignature(context, installedApk))
        assertTrue("A different test package was accepted as an update",
            !UpdateInstaller.sameSignature(context, testApk))
    }

    @Test fun updateNoticeIsPostedAndPendingVersionIsPersisted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Repo.init(context)
        val oldSettings = Repo.settings.value.copy()
        val previous = Repo.update.value
        // Avoid racing the periodic network check against this deterministic fixture.
        Repo.updateSettings { it.copy(autoUpdateCheck = false) }
        try {
            val update = UpdateInfo(version = "99.0.0", pageUrl = "https://github.com/rahmaniho/V2max/releases")
            Repo.saveUpdateInfo(update)
            val saved = context.getSharedPreferences("v2max_store", Context.MODE_PRIVATE)
                .getString("pending_update_v1", "").orEmpty()
            assertTrue("Pending update was not persisted", saved.contains("99.0.0"))
            assertTrue("Update notification was not posted", Notifier.updateAvailable(context, update.version))
            val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
            // NotificationManager enqueues delivery on a system thread; notify()
            // may return before activeNotifications reflects the new record.
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline &&
                manager.activeNotifications.none { it.id == 1003 }) SystemClock.sleep(50)
            assertTrue("No system notification for update", manager.activeNotifications.any { it.id == 1003 })
        } finally {
            Notifier.cancelUpdate(context)
            Repo.saveUpdateInfo(previous)
            Repo.saveSettings(oldSettings)
        }
    }

    @Test fun backupRestoreWritesAllSectionsDuringSuspendedListPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Repo.init(context)
        val previous = Repo.exportBackup()
        val oldSettings = Repo.settings.value.copy()
        val oldSelection = Repo.selectedId.value
        val backup = """{"version":3,
            "servers":[{"id":"backup-server","address":"example.org","uuid":"fixture"}],
            "subs":[{"id":"backup-source","url":"https://example.org/sub"}],
            "settings":{"multiPing":true,"autoUpdateCheck":false,"allowExternalAutomation":true},
            "selectedId":"backup-server"}"""
        Repo.updateSettings { it.copy(appLock = false, allowExternalAutomation = false) }
        Repo.suspendPersist(true)
        try {
            assertTrue("Backup restore failed", Repo.restoreBackup(backup))
            val storage = context.getSharedPreferences("v2max_store", Context.MODE_PRIVATE)
            assertTrue(storage.getString("servers_v1", "").orEmpty().contains("backup-server"))
            assertTrue(storage.getString("subs_v1", "").orEmpty().contains("backup-source"))
            val storedSettings = storage.getString("settings_v1", "").orEmpty()
            assertTrue(storedSettings.contains("\"multiPing\":true"))
            assertTrue("Backup enabled external automation", !Repo.settings.value.allowExternalAutomation)
            assertTrue("Device consent was serialized in portable settings", !storedSettings.contains("allowExternalAutomation"))
            assertTrue("Backup enabled the local consent preference", !context.getSharedPreferences(
                "v2max_control_consent", Context.MODE_PRIVATE
            ).getBoolean("allow_external_automation", false))
            assertEquals("backup-server", storage.getString("selected_v1", ""))
            assertEquals("backup-server", Repo.selectedId.value)
        } finally {
            Repo.suspendPersist(false)
            if (!Repo.restoreBackup(previous)) {
                // An empty initial repository has no importable profiles.
                Repo.resetEverything()
                Repo.setSelected(oldSelection)
            }
            Repo.saveSettings(oldSettings)
        }
    }

    @Test fun repositoryRejectsResultsForAnEditedProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Repo.init(context)
        val original = fixture("fingerprint-guard", 18080).copy(address = "old.example.org")
        Repo.addServers(listOf(original))
        try {
            val changed = Repo.serverById(original.id)!!.copy(address = "new.example.org", testedAt = 0, realPing = -1, testedUrl = "")
            Repo.updateServer(changed)
            Repo.setVerifiedResult(
                original.id, ProbeResult(25, "https://example.org", false, original.testedFragmentProfile),
                expectedFingerprint = original.fingerprint()
            )
            assertEquals(-1, Repo.serverById(original.id)?.realPing)
            assertTrue(Repo.serverById(original.id)?.testedUrl.isNullOrEmpty())
        } finally {
            Repo.removeServer(original.id)
        }
    }

    @Test fun selectedProfileSurvivesRefreshAndRemovedSourceIsNotResurrected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Repo.init(context)
        val previousSelection = Repo.selectedId.value
        val sub = Subscription(id = "refresh-fixture", name = "fixture", url = "https://example.org/sub")
        val original = fixture("selected-fixture", 18080).copy(subId = sub.id, address = "selected.example.org")
        val newer = fixture("new-fixture", 18080).copy(subId = sub.id, address = "new.example.org")
        Repo.addSubscription(sub)
        try {
            Repo.replaceSubscriptionServers(sub.id, listOf(original))
            Repo.setSelected(original.id)
            Repo.replaceSubscriptionServers(sub.id, listOf(newer))
            assertEquals(original.id, Repo.serverById(original.id)?.id)
            assertEquals(newer.id, Repo.serverById(newer.id)?.id)
            Repo.removeSubscription(sub.id)
            Repo.updateSubscription(sub)
            Repo.replaceSubscriptionServers(sub.id, listOf(newer))
            assertNull(Repo.subById(sub.id))
            assertNull(Repo.serverById(newer.id))
        } finally {
            Repo.removeSubscription(sub.id)
            if (previousSelection.isBlank() || Repo.serverById(previousSelection) != null) Repo.setSelected(previousSelection)
        }
    }

    @Test fun olderSubscriptionResponseCannotOverwriteANewerRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Repo.init(context)
        val source = Subscription(id = "race-fixture", name = "fixture", url = "https://example.org/race", updatedAt = 10)
        val fresh = fixture("race-new", 18080).copy(subId = source.id, address = "new.example.org")
        val stale = fixture("race-old", 18080).copy(subId = source.id, address = "old.example.org")
        Repo.addSubscription(source)
        try {
            assertTrue(Repo.commitSubscriptionRefresh(source.copy(), listOf(fresh), 0, 0, 0, 0))
            assertTrue(!Repo.commitSubscriptionRefresh(source.copy(), listOf(stale), 0, 0, 0, 0))
            Repo.recordSubscriptionRefreshError(source.copy(), "stale error")
            assertEquals("", Repo.subById(source.id)?.lastError)
            assertEquals(fresh.id, Repo.serverById(fresh.id)?.id)
            assertNull(Repo.serverById(stale.id))
        } finally {
            Repo.removeSubscription(source.id)
        }
    }

    @Test fun distinctLiveAndSelectedProfilesSurviveDedupeAndTrimming() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Repo.init(context)
        val previousSelection = Repo.selectedId.value
        val previousTunnel = Repo.tunnel.value
        val sub = Subscription(id = "protect-fixture", name = "fixture", url = "https://example.org/protect", builtIn = true)
        val live = fixture("protect-live", 18080).copy(subId = sub.id, address = "same.example.org")
        val selected = live.copy(id = "protect-selected")
        Repo.addSubscription(sub)
        try {
            Repo.updateServer(live)
            Repo.updateServer(selected)
            Repo.setSelected(selected.id)
            Repo.tunnel.value = TunnelUiState(status = TunnelStatus.CONNECTED, serverId = live.id)
            Repo.dedupeAllServers()
            Repo.trimBuiltInServers(maxTotal = 1)
            assertEquals(live.id, Repo.serverById(live.id)?.id)
            assertEquals(selected.id, Repo.serverById(selected.id)?.id)
            assertEquals(selected.id, Repo.selectedId.value)
        } finally {
            Repo.tunnel.value = previousTunnel
            Repo.removeSubscription(sub.id)
            if (previousSelection.isBlank() || Repo.serverById(previousSelection) != null) Repo.setSelected(previousSelection)
        }
    }

    @Test fun manualBulkAndWorkerRefreshUseRealHealthBeforeDeduplication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Repo.init(context)
        assertTrue("Fixture requires no active VPN", !Repo.tunnel.value.isActive && !V2maxVpnService.running)
        val previousSettings = Repo.settings.value.copy()
        val previousSelection = Repo.selectedId.value
        val previousSources = Repo.subs.value.map { it.copy() }
        val previousServers = Repo.servers.value.map { it.copy() }
        val source = Subscription(
            id = "refresh-health-fixture", name = "Mixed local fixture", updatedAt = System.currentTimeMillis(),
            url = "http://10.0.2.2:18081/subscription-mixed"
        )
        // This is intentionally identical to the good feed member. A selected
        // personal copy must survive dedupe and receive the real measurement.
        val personal = LinkParser.parseAll(
            "vless://22222222-3333-4444-5555-666666666666@10.0.2.2:18080?type=tcp&security=none#personal"
        ).single().copy(id = "refresh-personal-fixture", subId = "")
        val store = ViewModelStore()
        lateinit var vm: AppViewModel

        fun awaitUiRefresh(previousRevision: Long) {
            val deadline = SystemClock.elapsedRealtime() + 95_000
            while (((Repo.subById(source.id)?.updatedAt ?: 0L) <= previousRevision || Repo.sync.value.running) &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("The subscription did not refresh", (Repo.subById(source.id)?.updatedAt ?: 0L) > previousRevision)
            assertTrue("Subscription health did not finish", !Repo.sync.value.running)
        }

        fun assertCleaned(stage: String) {
            val kept = requireNotNull(Repo.serverById(personal.id))
            assertEquals("$stage changed the user's selected server", personal.id, Repo.selectedId.value)
            assertTrue("$stage did not publish a fresh real Xray result", kept.recordedRealPing() > 0)
            assertEquals("$stage did not collapse the duplicate", 1,
                Repo.servers.value.count { it.fingerprint() == personal.fingerprint() })
            val fromFeed = Repo.servers.value.filter { it.subId == source.id }
            assertTrue("$stage removed an indeterminate native setup failure",
                fromFeed.any { it.method == "invalid-fixture-encryption" })
            assertTrue("$stage retained a confirmed unanswered server", fromFeed.none { it.port == 18082 })
            assertTrue("$stage unexpectedly started a VPN", !Repo.tunnel.value.isActive && !V2maxVpnService.running)
        }

        fun clearMeasurement() {
            val current = requireNotNull(Repo.serverById(personal.id))
            Repo.updateServer(current.copy(realPing = -1, googlePing = -1, instagramPing = -1,
                youtubePing = -1, testedAt = 0L, testedUrl = ""))
        }

        try {
            assertTrue(Repo.updateSettings {
                it.copy(autoConnectOnOpen = false, autoRefreshOnOpen = false, autoUpdateSubs = false,
                    autoUpdateCheck = false, useFreeSources = false, deepProbe = false, fragment = false,
                    balancer = false, multiPing = false, speedTest = false, geoAssets = false,
                    dropDeadServers = true, routingMode = RoutingMode.GLOBAL,
                    delayTestUrl = "http://198.51.100.1:18081/probe")
            })
            // Isolate the worker's all-source pass without losing the emulator's
            // prior fixtures; everything is restored in finally.
            previousSources.forEach { Repo.removeSubscription(it.id) }
            Repo.removeServers(Repo.servers.value.map { it.id })
            assertTrue(Repo.addSubscription(source))
            Repo.updateServer(personal)
            Repo.setSelected(personal.id)
            instrumentation.runOnMainSync {
                val app = context.applicationContext as Application
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
                vm.refreshSubscription(source.id)
            }
            awaitUiRefresh(source.updatedAt)
            assertCleaned("single")

            clearMeasurement()
            val beforeBulk = requireNotNull(Repo.subById(source.id)).updatedAt
            instrumentation.runOnMainSync { vm.refreshAllSubscriptions() }
            awaitUiRefresh(beforeBulk)
            assertCleaned("bulk")

            clearMeasurement()
            val beforeWorker = requireNotNull(Repo.subById(source.id)).updatedAt
            assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = true) })
            val request = OneTimeWorkRequestBuilder<SubSyncWorker>().build()
            val manager = WorkManager.getInstance(context)
            manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
            val deadline = SystemClock.elapsedRealtime() + 95_000
            var info: WorkInfo? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                info = manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)
                if (info?.state?.isFinished == true) break
                SystemClock.sleep(100)
            }
            assertEquals("The periodic refresh worker did not complete", WorkInfo.State.SUCCEEDED, info?.state)
            assertTrue("The worker did not refresh the source", (Repo.subById(source.id)?.updatedAt ?: 0L) > beforeWorker)
            assertCleaned("worker")

            // With no working replacement already stored, the real failover
            // path must refresh, clean, and then perform its own preflight.
            Repo.removeServer(personal.id)
            val current = fixture("refresh-current-fixture", 18082)
            Repo.updateServer(current)
            Repo.setSelected(current.id)
            Repo.updateSubscription(requireNotNull(Repo.subById(source.id)).copy(updatedAt = 0L))
            assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = false, autoUpdateSubs = true) })
            val replacement = runBlocking { withTimeout(95_000) { Failover.findReplacement(context, current.id) } }
            assertNotNull("Failover did not obtain a newly verified replacement", replacement)
            assertTrue("Failover returned no real protocol measurement", requireNotNull(replacement).server().recordedRealPing() > 0)
            assertTrue("Failover did not refresh the stale source", (Repo.subById(source.id)?.updatedAt ?: 0L) > 0)
            assertTrue("Failover retained a confirmed unanswered feed member",
                Repo.servers.value.none { it.subId == source.id && it.port == 18082 })
            assertTrue("Failover removed an indeterminate setup failure",
                Repo.servers.value.any { it.subId == source.id && it.method == "invalid-fixture-encryption" })
            assertTrue("Failover preflight unexpectedly started a VPN", !Repo.tunnel.value.isActive && !V2maxVpnService.running)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            Repo.removeSubscription(source.id)
            Repo.removeServer(personal.id)
            Repo.removeServer("refresh-current-fixture")
            previousSources.forEach { if (Repo.subById(it.id) == null) Repo.addSubscription(it) }
            previousServers.forEach(Repo::updateServer)
            Repo.setSelected(previousSelection)
            Repo.saveSettings(previousSettings)
        }
    }

    @Test fun stagedScanUsesRealXrayWithoutPrecheckingTheEntireList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = ir.v2max.app.data.AppSettings(
            fragment = false, balancer = false, geoAssets = false, multiPing = false, speedTest = false,
            routingMode = RoutingMode.GLOBAL, delayTestUrl = "http://198.51.100.1:18081/probe"
        )
        val profiles = listOf(fixture("live", 18080, favorite = true)) +
            (1 until 300).map { fixture("dead-$it", 18082) }
        val outcome = runBlocking { HealthScanner.scan(context, profiles, options) }
        assertEquals("live", outcome.best?.id)
        assertEquals(24, outcome.precheckedCount)
        assertEquals(2, outcome.tcpConnections)
        assertTrue("Unexpected number of real tests: ${outcome.testedCount}", outcome.testedCount in 1..6)
        println("V2MAX_SCAN_BENCH profiles=300 prechecked=${outcome.precheckedCount} " +
            "tcp=${outcome.tcpConnections} real=${outcome.testedCount} elapsed_ms=${outcome.elapsedMs}")
    }

    @Test fun stagedScanContinuesPastAnUnusableFirstWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = ir.v2max.app.data.AppSettings(
            fragment = false, balancer = false, geoAssets = false, multiPing = false, speedTest = false,
            routingMode = RoutingMode.GLOBAL, delayTestUrl = "http://198.51.100.1:18081/probe"
        )
        val profiles = (0 until 25).map { fixture("dead-$it", 18082) } + fixture("live-later", 18080)
        val outcome = runBlocking { HealthScanner.scan(context, profiles, options) }
        assertEquals("live-later", outcome.best?.id)
        assertEquals(26, outcome.precheckedCount)
        assertEquals(2, outcome.tcpConnections)
        assertTrue(outcome.testedCount >= 1)
    }

    @Test fun selectedProfileCarriesTrafficThroughAndroidTun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Repo.init(context)
        Repo.updateSettings {
            it.copy(
                autoConnectOnOpen = false, autoRefreshOnOpen = false, autoUpdateSubs = false, autoUpdateCheck = false,
                appLock = false, allowExternalAutomation = false,
                useFreeSources = false, deepProbe = false, fragment = false, balancer = false,
                geoAssets = false, multiPing = false, speedTest = false, smartFailover = false,
                killSwitch = false, perAppMode = ir.v2max.app.data.PerAppMode.OFF,
                routingMode = RoutingMode.GLOBAL,
                delayTestUrl = "http://198.51.100.1:18081/probe"
            )
        }
        val server = ServerConfig(
            name = "Local Xray fixture", proto = Proto.VLESS, address = "10.0.2.2", port = 18080,
            uuid = "11111111-2222-3333-4444-555555555555", method = "none", network = "tcp", security = "none"
        )
        // This emulator is disposable. Isolate recovery from profiles left by
        // other instrumentation cases so there is exactly one available fixture.
        Repo.removeServers(Repo.servers.value.map { it.id })
        Repo.addServers(listOf(server))
        Repo.setSelected(server.id)
        assertNull("CI must grant ACTIVATE_VPN app-op before this test", VpnService.prepare(context))
        val networkBeforeVpn = CarrierProbe.networkHandle(context)
        assertNotNull("No active network before starting the VPN", networkBeforeVpn)
        assertDirectFixtureReachable(instrumentation)

        try {
            assertTrue("Pre-connect protocol test failed: ${Repo.scan.value.message}\n${XrayCore.logs().joinToString("\n")}",
                runBlocking { SmartConnect.connectVerified(context, server.id) })
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < deadline &&
                Repo.tunnel.value.status != TunnelStatus.CONNECTED && Repo.tunnel.value.status != TunnelStatus.ERROR) {
                SystemClock.sleep(200)
            }
            assertEquals("VPN did not connect: ${Repo.tunnel.value.message}\n${XrayCore.logs().joinToString("\n")}",
                TunnelStatus.CONNECTED, Repo.tunnel.value.status)

            assertTunTrafficEventually(instrumentation, "initial")
            assertEquals("The VPN app's underlying network changed during tunnel setup",
                networkBeforeVpn, CarrierProbe.networkHandle(context))
            assertQrImportsPreserveLiveTunnel(instrumentation, server)
            assertEditingPreservesLiveTunnel(instrumentation, server)
            assertPerAppRulesAndPreservedTunnel(instrumentation, server)

            // Explicit broadcasts from the shell UID simulate an unrelated app.
            // Neither the old exported widget action nor private receiver may
            // stop a working tunnel. The real widget PendingIntent must work.
            val widgetInfo = context.packageManager.getReceiverInfo(
                android.content.ComponentName(context, WidgetActionReceiver::class.java), 0
            )
            assertTrue("Widget action receiver is exported", !widgetInfo.exported)
            val generation = V2maxVpnService.currentActionGeneration()
            val auto = "${context.packageName}/ir.v2max.app.service.AutoReceiver"
            shell(instrumentation, "am broadcast -n $auto -a ${MainActivity.ACTION_DISCONNECT}")
            shell(instrumentation, "am broadcast -n $auto -a ${MainActivity.ACTION_TOGGLE}")
            shell(instrumentation, "am broadcast -n ${context.packageName}/ir.v2max.app.service.V2maxWidget -a ${V2maxWidget.ACTION_TOGGLE}")
            shell(instrumentation, "am broadcast -n ${context.packageName}/ir.v2max.app.service.WidgetActionReceiver -a ${V2maxWidget.ACTION_TOGGLE}")
            val activity = "${context.packageName}/ir.v2max.app.MainActivity"
            shell(instrumentation, "am start -W -n $activity -a ${MainActivity.ACTION_DISCONNECT}")
            shell(instrumentation, "am start -W -n $activity -a ${MainActivity.ACTION_TOGGLE}")
            shell(instrumentation, "am start -W -n $activity -a ${MainActivity.ACTION_CONNECT}")
            SystemClock.sleep(500)
            assertEquals("External broadcast changed the VPN action generation", generation, V2maxVpnService.currentActionGeneration())
            assertEquals("External broadcast disconnected the VPN", TunnelStatus.CONNECTED, Repo.tunnel.value.status)
            assertTunTraffic(instrumentation)

            val widgetClick = V2maxWidget.toggleIntent(context)
            widgetClick.send()
            val widgetStopDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < widgetStopDeadline && Repo.tunnel.value.status != TunnelStatus.DISCONNECTED) SystemClock.sleep(50)
            assertEquals("Legitimate widget could not stop the tunnel", TunnelStatus.DISCONNECTED, Repo.tunnel.value.status)
            assertDirectFixtureReachable(instrumentation)
            widgetClick.send()
            val widgetStartDeadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < widgetStartDeadline &&
                Repo.tunnel.value.status != TunnelStatus.CONNECTED && Repo.tunnel.value.status != TunnelStatus.ERROR) SystemClock.sleep(100)
            assertEquals("Legitimate widget could not reconnect: ${Repo.tunnel.value.message}", TunnelStatus.CONNECTED, Repo.tunnel.value.status)
            assertTunTrafficEventually(instrumentation, "widget-restart")

            // Very fast local responses may take less than one whole millisecond.
            // Repeated preflights must accept them without interrupting the live VPN.
            repeat(5) { attempt ->
                val result = runBlocking {
                    SmartConnect.scanAndConnect(context, autoConnect = false, limit = 1, preferredId = server.id)
                }
                assertNotNull("Preflight $attempt failed: ${Repo.scan.value.message}", result)
                assertEquals(TunnelStatus.CONNECTED, Repo.tunnel.value.status)
            }
            assertTunTraffic(instrumentation)

            // Test real native Xray controllers with actual traffic before, during,
            // and after two downloads. A slow local fixture guarantees overlap.
            runBlocking {
                repeat(2) {
                    val options = Repo.settings.value.copy(speedTest = true, speedTestCount = 1)
                    val scan = async(Dispatchers.IO) {
                        HealthScanner.scanPrepared(
                            listOf(server), options,
                            HealthScanner.Operations(
                                tcp = HealthScanner::tcpPing,
                                real = HealthScanner::realDelay,
                                speed = { cfg, variant ->
                                    SpeedTester.measure(cfg, variant, "http://198.51.100.1:18081/speed")
                                }
                            )
                        )
                    }
                    withTimeout(30_000) {
                        while (!XrayCore.isSpeedTestRunning() && !scan.isCompleted) delay(20)
                    }
                    assertTrue("Speed core never started: ${XrayCore.logs()}", XrayCore.isSpeedTestRunning())
                    assertTunTraffic(instrumentation)
                    assertTrue("Download did not overlap the TUN probe", XrayCore.isSpeedTestRunning())
                    val result = withTimeout(30_000) { scan.await() }
                    assertEquals(server.id, result.best?.id)
                    assertTrue("Real download did not succeed: ${result.message}", result.speedCompared)
                    assertTrue(result.best!!.speedKbps > 0)
                    assertTrue(XrayCore.isRunning)
                    assertTunTraffic(instrumentation)
                }

                // Cancellation must stop only the temporary controller, not the VPN.
                val download = async(Dispatchers.IO) {
                    SpeedTester.measure(server, Repo.settings.value, "http://198.51.100.1:18081/speed")
                }
                withTimeout(30_000) {
                    while (!XrayCore.isSpeedTestRunning() && !download.isCompleted) delay(20)
                }
                assertTrue("Speed core never started for cancellation", XrayCore.isSpeedTestRunning())
                withTimeout(10_000) { download.cancelAndJoin() }
                assertTrue("Temporary core still running after cancellation", !XrayCore.isSpeedTestRunning())
                assertTrue("Live core stopped with the temporary core", XrayCore.isRunning)
                assertTunTraffic(instrumentation)
            }

            // A revoked/invalid service handoff must not tear down a live tunnel.
            context.startService(Intent(context, V2maxVpnService::class.java).apply {
                action = V2maxVpnService.ACTION_START
                putExtra(V2maxVpnService.EXTRA_SERVER_ID, server.id)
                putExtra(V2maxVpnService.EXTRA_PREFLIGHT_TICKET, "revoked-test-ticket")
            })
            val staleDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < staleDeadline &&
                !Repo.tunnel.value.message.contains("اتصال فعلی حفظ شد")) SystemClock.sleep(50)
            assertEquals(TunnelStatus.CONNECTED, Repo.tunnel.value.status)
            assertTrue("Stale handoff was not handled", Repo.tunnel.value.message.contains("اتصال فعلی حفظ شد"))
            assertTunTraffic(instrumentation)

            val staleStopGeneration = V2maxVpnService.currentActionGeneration()
            val oldNotificationStop = Notifier.build(context, Repo.tunnel.value, false).actions.last().actionIntent
            TunnelController.cancelPreflight(context)
            // A pending action from an earlier notification must remain stale,
            // even after the current notification has been refreshed.
            oldNotificationStop.send()
            // An older STOP delivered after a newer action must not drop the live tunnel.
            context.startService(Intent(context, V2maxVpnService::class.java).apply {
                action = V2maxVpnService.ACTION_STOP
                putExtra(V2maxVpnService.EXTRA_ACTION_GENERATION, staleStopGeneration)
            })
            SystemClock.sleep(300)
            assertEquals(TunnelStatus.CONNECTED, Repo.tunnel.value.status)
            assertTunTraffic(instrumentation)

            // Editing a profile can keep its ID while changing the actual outbound.
            // A manual connect must use the freshly tested new version, not mistake
            // the old live tunnel for the updated configuration.
            val edited = server.copy(uuid = "22222222-3333-4444-5555-666666666666")
            assertTrue(V2maxVpnService.activeProfileMatches(server))
            assertTrue(!V2maxVpnService.activeProfileMatches(edited))
            Repo.updateServer(edited)
            val editedPreflight = runBlocking { SmartConnect.connectVerified(context, edited.id) }
            assertTrue("Edited profile preflight failed: ${Repo.scan.value.message}", editedPreflight)
            val editDeadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < editDeadline &&
                !(Repo.tunnel.value.status == TunnelStatus.CONNECTED && V2maxVpnService.activeProfileMatches(edited))) {
                SystemClock.sleep(100)
            }
            assertTrue("Edited profile was not applied: ${Repo.tunnel.value.message}", V2maxVpnService.activeProfileMatches(edited))
            assertTunTrafficEventually(instrumentation, "profile-switch")

            // Simulate the native core stopping. With no other fixture profile,
            // recovery must re-test this profile rather than trust its old ping.
            val oldRetestAction = Notifier.build(context, Repo.tunnel.value, false).actions[1].actionIntent
            Repo.updateSettings { it.copy(smartFailover = true, autoUpdateSubs = false, killSwitch = false) }
            XrayCore.stop()
            val recoveryStartDeadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < recoveryStartDeadline &&
                Repo.tunnel.value.status != TunnelStatus.CONNECTING) SystemClock.sleep(50)
            assertEquals("Unexpected core stop was not detected", TunnelStatus.CONNECTING, Repo.tunnel.value.status)
            assertTrue("Recovery falsely claimed the VPN was running", !V2maxVpnService.running)
            assertEquals("Only Stop should be offered during recovery", 1,
                Notifier.build(context, Repo.tunnel.value, false).actions.size)
            oldRetestAction.send() // an action queued before the core stopped
            ContextCompat.startForegroundService(context, Intent(context, V2maxVpnService::class.java).apply {
                action = V2maxVpnService.ACTION_START
                putExtra(V2maxVpnService.EXTRA_ACTION_GENERATION, V2maxVpnService.currentActionGeneration() - 1)
            })
            SystemClock.sleep(400)
            assertEquals("A stale service action canceled recovery", TunnelStatus.CONNECTING, Repo.tunnel.value.status)
            val recoveryDeadline = SystemClock.elapsedRealtime() + 90_000
            while (SystemClock.elapsedRealtime() < recoveryDeadline &&
                !(Repo.tunnel.value.status == TunnelStatus.CONNECTED && V2maxVpnService.activeProfileMatches(edited))) {
                SystemClock.sleep(100)
            }
            assertTrue("VPN did not recover: ${Repo.tunnel.value.message}", V2maxVpnService.activeProfileMatches(edited))
            assertTunTrafficEventually(instrumentation, "automatic-recovery")

            // Hold an actual alternate-server probe in flight. A stopped core
            // must be reported as reconnecting immediately, not CONNECTED for
            // the duration of a slow replacement scan.
            val heldReplacement = server.copy(id = "held-replacement", name = "held replacement")
            Repo.updateServer(heldReplacement)
            Repo.updateSettings { it.copy(killSwitch = true, delayTestUrl = "http://198.51.100.1:18081/hold-probe") }
            XrayCore.stop()
            val blockedDeadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < blockedDeadline &&
                Repo.tunnel.value.status != TunnelStatus.CONNECTING) SystemClock.sleep(50)
            assertEquals("Second core stop was not detected", TunnelStatus.CONNECTING, Repo.tunnel.value.status)
            awaitHeldProbe()
            assertEquals("Stopped core was still shown as connected during replacement scanning", TunnelStatus.CONNECTING, Repo.tunnel.value.status)
            assertTrue("Stopped core still reported running", !V2maxVpnService.running && !XrayCore.isRunning)
            assertTrue("Recovery did not report the retained VPN interface", Repo.tunnel.value.killSwitchRetained)
            assertDirectFixtureBlocked(instrumentation)
            assertEquals("Recovery restarted the core during the blocking probe", TunnelStatus.CONNECTING, Repo.tunnel.value.status)
            assertQrImportsPreserveRecovery(instrumentation)
            // A user stop must win even when a slow native probe finishes later.
            TunnelController.stop(context)
            fixtureControl("hold-release")
            Repo.removeServer(heldReplacement.id)
            val recoveryStopDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < recoveryStopDeadline &&
                Repo.tunnel.value.status != TunnelStatus.DISCONNECTED) SystemClock.sleep(50)
            SystemClock.sleep(21_000) // beyond the first and second recovery backoffs
            assertEquals("Recovery reconnected after the user stopped it", TunnelStatus.DISCONNECTED, Repo.tunnel.value.status)
            assertTrue("Core restarted after the user stopped it", !XrayCore.isRunning && !V2maxVpnService.running)
            assertTrue("The retained-interface indicator survived stop", !Repo.tunnel.value.killSwitchRetained)
            assertDirectFixtureReachable(instrumentation)

            Repo.updateSettings {
                it.copy(smartFailover = false, killSwitch = false, delayTestUrl = "http://198.51.100.1:18081/probe")
            }
            assertTrue("Preflight after recovery stop failed: ${Repo.scan.value.message}",
                runBlocking { SmartConnect.connectVerified(context, edited.id) })
            val restartDeadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < restartDeadline &&
                !(Repo.tunnel.value.status == TunnelStatus.CONNECTED && V2maxVpnService.activeProfileMatches(edited))) {
                SystemClock.sleep(100)
            }
            assertTrue("Manual reconnect did not start: ${Repo.tunnel.value.message}", V2maxVpnService.activeProfileMatches(edited))
            assertTunTrafficEventually(instrumentation, "manual-restart")

            // A retained interface also occurs after a terminal error. Android
            // may keep a stopped VpnService bound while the VPN still exists;
            // verify whichever lifecycle outcome occurred instead of assuming
            // stopService synchronously invokes onDestroy.
            Repo.updateSettings { it.copy(killSwitch = true, smartFailover = false) }
            XrayCore.stop()
            val terminalDeadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < terminalDeadline &&
                Repo.tunnel.value.status != TunnelStatus.ERROR) SystemClock.sleep(50)
            assertEquals("Core failure did not enter terminal error", TunnelStatus.ERROR, Repo.tunnel.value.status)
            assertTrue("Terminal failure did not retain the interface", Repo.tunnel.value.killSwitchRetained)
            assertDirectFixtureBlocked(instrumentation)
            assertTrue("Service was not running for teardown", context.stopService(Intent(context, V2maxVpnService::class.java)))
            val destroyedDeadline = SystemClock.elapsedRealtime() + 1_000
            while (SystemClock.elapsedRealtime() < destroyedDeadline && Repo.tunnel.value.killSwitchRetained) SystemClock.sleep(50)
            val afterStop = Repo.tunnel.value
            if (afterStop.killSwitchRetained) {
                // stopService only stops the started part; Android may still bind
                // the live VpnService. Confirm it really blocks, then revoke the
                // app-op and ask Android to unprepare this VPN (onRevoke).
                assertDirectFixtureBlocked(instrumentation)
                shell(instrumentation, "appops set ${context.packageName} ACTIVATE_VPN ignore")
                try {
                    assertNotNull("VPN permission was not revoked", VpnService.prepare(context))
                    val revokeDeadline = SystemClock.elapsedRealtime() + 10_000
                    while (SystemClock.elapsedRealtime() < revokeDeadline && Repo.tunnel.value.killSwitchRetained) SystemClock.sleep(50)
                    assertTrue("Revoked VPN still claims a retained interface", !Repo.tunnel.value.killSwitchRetained)
                    assertDirectFixtureReachable(instrumentation)
                } finally {
                    shell(instrumentation, "appops set ${context.packageName} ACTIVATE_VPN allow")
                }
                assertNull("VPN permission was not restored", VpnService.prepare(context))
            } else {
                assertTrue("Destroyed service did not warn about direct internet", afterStop.message.contains("ممکن است مستقیم"))
                assertDirectFixtureReachable(instrumentation)
            }
            Repo.updateSettings { it.copy(killSwitch = false) }
            assertTrue("Preflight after service teardown failed: ${Repo.scan.value.message}",
                runBlocking { SmartConnect.connectVerified(context, edited.id) })
            val teardownRestartDeadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < teardownRestartDeadline &&
                !(Repo.tunnel.value.status == TunnelStatus.CONNECTED && V2maxVpnService.activeProfileMatches(edited))) {
                SystemClock.sleep(100)
            }
            assertTrue("VPN did not restart after service teardown: ${Repo.tunnel.value.message}", V2maxVpnService.activeProfileMatches(edited))
            assertTunTrafficEventually(instrumentation, "service-restart")

            // Start a slow automatic replacement, then supersede it. Even if its
            // native probe returns later, it must not override the current tunnel.
            val alternate = server.copy(id = "slow-replacement", name = "replacement")
            Repo.updateServer(alternate)
            Repo.updateSettings { it.copy(delayTestUrl = "http://198.51.100.1:18081/slow-probe") }
            val beforeNext = V2maxVpnService.currentActionGeneration()
            context.startService(Intent(context, V2maxVpnService::class.java).apply {
                action = V2maxVpnService.ACTION_NEXT
            })
            val nextDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < nextDeadline &&
                V2maxVpnService.currentActionGeneration() == beforeNext) SystemClock.sleep(20)
            assertTrue("Replacement request was not received", V2maxVpnService.currentActionGeneration() != beforeNext)
            SystemClock.sleep(300)
            TunnelController.cancelPreflight(context)
            SystemClock.sleep(4_500)
            assertEquals(TunnelStatus.CONNECTED, Repo.tunnel.value.status)
            assertTrue("Canceled replacement superseded the live profile", V2maxVpnService.activeProfileMatches(edited))
            assertTunTraffic(instrumentation)
            Repo.removeServer(alternate.id)

            // Stop while the post-start native probe is still blocked, then
            // deliver an older, otherwise valid handoff. Neither can resurrect it.
            val selection = requireNotNull(runBlocking {
                SmartConnect.scanAndConnect(context, autoConnect = false, limit = 1, preferredId = edited.id)
            }) { "Slow preflight failed: ${Repo.scan.value.message}" }
            val staleGeneration = V2maxVpnService.currentActionGeneration()
            val staleTicket = PreflightTickets.issue(selection)
            TunnelController.startVerified(context, selection)
            val connectingDeadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < connectingDeadline &&
                !(Repo.tunnel.value.status == TunnelStatus.CONNECTING && XrayCore.isRunning)) SystemClock.sleep(20)
            assertTrue("Did not catch the post-start probe in flight: ${Repo.tunnel.value.message}",
                Repo.tunnel.value.status == TunnelStatus.CONNECTING && XrayCore.isRunning)
            TunnelController.stop(context)
            ContextCompat.startForegroundService(context, Intent(context, V2maxVpnService::class.java).apply {
                action = V2maxVpnService.ACTION_START
                putExtra(V2maxVpnService.EXTRA_SERVER_ID, edited.id)
                putExtra(V2maxVpnService.EXTRA_PREFLIGHT_TICKET, staleTicket)
                putExtra(V2maxVpnService.EXTRA_ACTION_GENERATION, staleGeneration)
            })
            val stopDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < stopDeadline && Repo.tunnel.value.status != TunnelStatus.DISCONNECTED)
                SystemClock.sleep(50)
            SystemClock.sleep(4_500)
            assertEquals("A canceled connection returned after stop", TunnelStatus.DISCONNECTED, Repo.tunnel.value.status)
            assertTrue("Native core kept running after stop", !V2maxVpnService.running && !XrayCore.isRunning)

            // With no existing VPN, an empty allow-list must not become Android's
            // default all-apps VPN either. The protocol preflight can succeed,
            // but the service must report the invalid routing settings and stop.
            Repo.updateSettings { it.copy(perAppMode = PerAppMode.ONLY_SELECTED, perAppPackages = emptyList(),
                delayTestUrl = "http://198.51.100.1:18081/probe") }
            val beforeEmpty = V2maxVpnService.currentActionGeneration()
            assertTrue("Disconnected per-app preflight failed: ${Repo.scan.value.message}",
                runBlocking { SmartConnect.connectVerified(context, edited.id) })
            val emptyDeadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < emptyDeadline &&
                !(V2maxVpnService.currentActionGeneration() != beforeEmpty &&
                    Repo.tunnel.value.status == TunnelStatus.ERROR && Repo.tunnel.value.message.contains("دست‌کم یک برنامه"))) {
                SystemClock.sleep(50)
            }
            assertEquals("Empty allow-list did not refuse a new VPN: ${Repo.tunnel.value.message}",
                TunnelStatus.ERROR, Repo.tunnel.value.status)
            assertTrue("Empty allow-list error was not reported",
                V2maxVpnService.currentActionGeneration() != beforeEmpty && Repo.tunnel.value.message.contains("دست‌کم یک برنامه"))
            assertTrue("Empty allow-list started a VPN", !V2maxVpnService.running && !XrayCore.isRunning)
            assertDirectFixtureReachable(instrumentation)
            Repo.updateSettings { it.copy(perAppMode = PerAppMode.OFF, perAppPackages = emptyList()) }
            android.util.Log.i("V2maxPerAppTest", "V2MAX_PER_APP_ROUTING invalid=refused live=preserved only=passed except=passed disconnected=refused")
        } finally {
            SmartConnect.cancelScan()
            TunnelController.stop(context)
            runCatching { fixtureControl("hold-release") }
            Repo.removeServer("held-replacement")
        }
    }

    /** Exercise Android's actual allow/disallow builders without sacrificing a live tunnel on invalid input. */
    private fun assertPerAppRulesAndPreservedTunnel(instrumentation: Instrumentation, active: ServerConfig) {
        val context = instrumentation.targetContext
        val original = Repo.settings.value.copy()
        val missingPackage = "ir.v2max.missing.fixture.app"
        val shellPackage = "com.android.shell"
        @Suppress("DEPRECATION")
        val shellApp = context.packageManager.getApplicationInfo(shellPackage, 0)
        assertNotNull("The external-UID fixture package must be installed", shellApp)

        try {
            val invalid = listOf(
                Triple(PerAppMode.ONLY_SELECTED, emptyList(), "دست‌کم یک برنامه"),
                // A partial allow-list must not be applied if another rule failed.
                Triple(PerAppMode.ONLY_SELECTED, listOf(shellPackage, missingPackage), "قوانین برنامه‌به‌برنامه اعمال نشد"),
                Triple(PerAppMode.EXCEPT_SELECTED, listOf(missingPackage), "قوانین برنامه‌به‌برنامه اعمال نشد"),
            )
            for ((mode, packages, reason) in invalid) {
                val before = V2maxVpnService.currentActionGeneration()
                assertTrue(Repo.updateSettings { it.copy(perAppMode = mode, perAppPackages = packages) })
                assertTrue("Protocol preflight for invalid routing failed: ${Repo.scan.value.message}",
                    runBlocking { SmartConnect.connectVerified(context, active.id) })
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (SystemClock.elapsedRealtime() < deadline &&
                    !(V2maxVpnService.currentActionGeneration() != before &&
                        Repo.tunnel.value.message.contains(reason) &&
                        Repo.tunnel.value.message.contains("اتصال فعلی با تنظیمات قبلی حفظ شد"))) {
                    SystemClock.sleep(50)
                }
                assertTrue("Invalid app rule for $mode was not reported: ${Repo.tunnel.value.message}",
                    V2maxVpnService.currentActionGeneration() != before && Repo.tunnel.value.message.contains(reason) &&
                        Repo.tunnel.value.message.contains("اتصال فعلی با تنظیمات قبلی حفظ شد"))
                assertEquals("Invalid app rule dropped the previous VPN", TunnelStatus.CONNECTED, Repo.tunnel.value.status)
                assertTrue("Invalid app rule changed the active VPN", V2maxVpnService.activeProfileMatches(active, original))
                assertTrue("Invalid app rule stopped the Xray core", XrayCore.isRunning)
                assertTunTraffic(instrumentation)
            }

            suspend fun requestAndWait(mode: PerAppMode, packages: List<String>, phase: String) {
                assertTrue(Repo.updateSettings { it.copy(perAppMode = mode, perAppPackages = packages) })
                assertTrue("Per-app protocol preflight failed: ${Repo.scan.value.message}",
                    SmartConnect.connectVerified(context, active.id))
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (SystemClock.elapsedRealtime() < deadline &&
                    !(Repo.tunnel.value.status == TunnelStatus.CONNECTED && V2maxVpnService.activeProfileMatches(active))) {
                    SystemClock.sleep(50)
                }
                assertTrue("Per-app $phase was not applied: ${Repo.tunnel.value.message}",
                    Repo.tunnel.value.status == TunnelStatus.CONNECTED && V2maxVpnService.activeProfileMatches(active))
            }

            // The fixture shell is a separate UID. Allowing just that package
            // must still carry its local test traffic across Android's TUN.
            runBlocking { requestAndWait(PerAppMode.ONLY_SELECTED, listOf(shellPackage), "allow") }
            assertTunTrafficEventually(instrumentation, "per-app-allowed")

            // Excluding that same UID must prevent it from reaching the address
            // which only the local VPN fixture can serve.
            runBlocking { requestAndWait(PerAppMode.EXCEPT_SELECTED, listOf(shellPackage), "exclude") }
            assertDirectFixtureReachable(instrumentation)
            val bypassed = shell(instrumentation, "sh /data/local/tmp/v2max-tun-probe.sh")
            assertTrue("Excluded UID unexpectedly reached the TUN-only fixture: $bypassed",
                !bypassed.contains(" 200 ") && !bypassed.contains("v2max-tun-ok"))

            runBlocking { requestAndWait(original.perAppMode, original.perAppPackages, "restore") }
            assertTunTrafficEventually(instrumentation, "per-app-restored")
        } finally {
            Repo.updateSettings { it.copy(perAppMode = original.perAppMode, perAppPackages = original.perAppPackages) }
        }
    }

    /** The actual QR handler downloads/imports, but must not supersede a live tunnel. */
    private fun assertQrImportsPreserveLiveTunnel(instrumentation: Instrumentation, active: ServerConfig) {
        val context = instrumentation.targetContext
        val url = "http://10.0.2.2:18081/subscription"
        val qrHost = "qr-import.example.invalid"
        val store = ViewModelStore()
        lateinit var vm: AppViewModel
        val generation = V2maxVpnService.currentActionGeneration()
        val selected = Repo.selectedId.value
        try {
            instrumentation.runOnMainSync {
                val app = context.applicationContext as Application
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
                vm.onQrScanned(url)
            }
            val subscriptionDeadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < subscriptionDeadline &&
                Repo.subs.value.none { sub -> sub.url == url && Repo.servers.value.any { it.subId == sub.id } }) {
                assertEquals("QR subscription interrupted the VPN", TunnelStatus.CONNECTED, Repo.tunnel.value.status)
                SystemClock.sleep(50)
            }
            assertTrue("QR subscription was not imported: ${vm.message.value}",
                Repo.subs.value.any { sub -> sub.url == url && Repo.servers.value.any { it.subId == sub.id } })
            val sourceId = requireNotNull(Repo.subs.value.firstOrNull { it.url == url }).id
            val healthDeadline = SystemClock.elapsedRealtime() + 95_000
            while (SystemClock.elapsedRealtime() < healthDeadline &&
                (Repo.sync.value.running || Repo.servers.value.none { it.subId == sourceId && it.recordedRealPing() > 0 })) {
                assertEquals("Initial subscription health interrupted the VPN", TunnelStatus.CONNECTED, Repo.tunnel.value.status)
                SystemClock.sleep(50)
            }
            assertTrue("The imported subscription was not actually checked: ${vm.message.value}",
                Repo.servers.value.any { it.subId == sourceId && it.recordedRealPing() > 0 } && !Repo.sync.value.running)
            assertEquals("Subscription health changed the VPN generation", generation, V2maxVpnService.currentActionGeneration())
            assertTunTraffic(instrumentation)
            instrumentation.runOnMainSync {
                vm.onQrScanned("vless://11111111-2222-3333-4444-555555555555@$qrHost:443?security=tls#QR")
            }
            val linkDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < linkDeadline && Repo.servers.value.none { it.address == qrHost }) {
                assertEquals("QR profile import interrupted the VPN", TunnelStatus.CONNECTED, Repo.tunnel.value.status)
                SystemClock.sleep(50)
            }
            assertTrue("QR profile was not imported: ${vm.message.value}", Repo.servers.value.any { it.address == qrHost })
            assertEquals("Import changed the active VPN generation", generation, V2maxVpnService.currentActionGeneration())
            assertEquals("Import changed the user's selection", selected, Repo.selectedId.value)
            assertEquals(TunnelStatus.CONNECTED, Repo.tunnel.value.status)
            assertTrue("Import replaced the live profile", V2maxVpnService.activeProfileMatches(active))
            assertTunTraffic(instrumentation)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            Repo.subs.value.filter { it.url == url }.forEach { Repo.removeSubscription(it.id) }
            Repo.removeServers(Repo.servers.value.filter { it.address == qrHost }.map { it.id })
        }
    }

    /** Editing or cloning a profile must not supersede a working VPN. */
    private fun assertEditingPreservesLiveTunnel(instrumentation: Instrumentation, active: ServerConfig) {
        val context = instrumentation.targetContext
        val selected = Repo.selectedId.value
        val original = requireNotNull(Repo.serverById(active.id)).copy()
        val generation = V2maxVpnService.currentActionGeneration()
        val cloneId = "live-edit-clone"
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val app = context.applicationContext as Application
                val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
                val edited = original.copy(uuid = "33333333-4444-5555-6666-777777777777")
                val epoch = Repo.currentDataEpoch()
                assertTrue(vm.saveEditedServer(edited, original.fingerprint(), epoch))
                assertTrue(vm.cloneServer(edited.copy(id = cloneId, name = "edited clone"),
                    original.id, edited.fingerprint(), epoch))
            }
            assertEquals("Editor did not save the modified profile", "33333333-4444-5555-6666-777777777777",
                Repo.serverById(active.id)?.uuid)
            assertEquals("Changed profile retained an old successful test", -1, Repo.serverById(active.id)?.realPing)
            assertEquals("Clone was not selected", cloneId, Repo.selectedId.value)
            assertEquals("Editing changed the active VPN generation", generation, V2maxVpnService.currentActionGeneration())
            assertEquals(TunnelStatus.CONNECTED, Repo.tunnel.value.status)
            assertTrue("Editing replaced the live profile", V2maxVpnService.activeProfileMatches(active))
            assertTunTraffic(instrumentation)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            Repo.removeServer(cloneId)
            Repo.updateServer(original)
            Repo.setSelected(selected)
        }
    }

    /** Import must not cancel a service-owned recovery or release its retained interface. */
    private fun assertQrImportsPreserveRecovery(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val url = "http://10.0.2.2:18081/subscription"
        val qrHost = "qr-recovery.example.invalid"
        val generation = V2maxVpnService.currentActionGeneration()
        val selected = Repo.selectedId.value
        val store = ViewModelStore()
        lateinit var vm: AppViewModel
        try {
            instrumentation.runOnMainSync {
                val app = context.applicationContext as Application
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
                vm.onQrScanned(url)
                vm.onQrScanned("vless://11111111-2222-3333-4444-555555555555@$qrHost:443?security=tls#QR")
            }
            val deadline = SystemClock.elapsedRealtime() + 6_000
            while (SystemClock.elapsedRealtime() < deadline &&
                (Repo.subs.value.none { sub -> sub.url == url && Repo.servers.value.any { it.subId == sub.id } } ||
                    Repo.servers.value.none { it.address == qrHost })) {
                assertEquals("QR import interrupted recovery", TunnelStatus.CONNECTING, Repo.tunnel.value.status)
                assertTrue("QR import released the retained interface", Repo.tunnel.value.killSwitchRetained)
                SystemClock.sleep(30)
            }
            assertTrue("QR subscription was not imported during recovery: ${vm.message.value}",
                Repo.subs.value.any { sub -> sub.url == url && Repo.servers.value.any { it.subId == sub.id } })
            assertTrue("QR profile was not imported during recovery: ${vm.message.value}",
                Repo.servers.value.any { it.address == qrHost })
            assertEquals("QR import changed the recovery generation", generation, V2maxVpnService.currentActionGeneration())
            assertEquals("QR import changed the selection", selected, Repo.selectedId.value)
            assertEquals(TunnelStatus.CONNECTING, Repo.tunnel.value.status)
            assertTrue("Recovery no longer retains the interface", Repo.tunnel.value.killSwitchRetained)
            assertTrue("Recovery core unexpectedly restarted", !V2maxVpnService.running && !XrayCore.isRunning)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            Repo.subs.value.filter { it.url == url }.forEach { Repo.removeSubscription(it.id) }
            Repo.removeServers(Repo.servers.value.filter { it.address == qrHost }.map { it.id })
        }
    }

    private fun assertTunTrafficEventually(instrumentation: Instrumentation, phase: String) {
        // A new VPN interface may need a short handover after Xray's outbound
        // probe succeeds. Record retries rather than hiding that interruption;
        // tests of an already-running tunnel still use the strict single probe.
        val start = SystemClock.elapsedRealtime()
        val deadline = start + 12_000
        var attempts = 0
        var lastFailure: AssertionError? = null
        do {
            attempts++
            try {
                assertTunTraffic(instrumentation)
                assertTrue("Core stopped while waiting for TUN", V2maxVpnService.running && XrayCore.isRunning)
                android.util.Log.i("V2maxTunTest", "V2MAX_TUN_READY phase=$phase attempts=$attempts elapsed_ms=${SystemClock.elapsedRealtime() - start}")
                return
            } catch (failure: AssertionError) {
                lastFailure = failure
            }
            if (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Android TUN did not pass within the handover window ($phase, $attempts attempts)", lastFailure)
    }

    private fun fixtureControl(path: String): String {
        // This process is excluded from its own VPN and can control the isolated
        // emulator-host fixture even while the external UID's traffic is held.
        val connection = java.net.URL("http://10.0.2.2:18081/$path").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitHeldProbe() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { fixtureControl("hold-status") }.getOrDefault("") == "started") return
            SystemClock.sleep(100)
        }
        throw AssertionError("Replacement never reached the held HTTP fixture")
    }

    private fun shell(instrumentation: Instrumentation, command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
        }

    private fun directFixtureProbe(instrumentation: Instrumentation): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("sh /data/local/tmp/v2max-direct-probe.sh")
        val output = descriptor.use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }.trim()
        val uid = output.lineSequence().firstOrNull { it.startsWith("PROBE_UID=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()
        assertTrue("Direct probe must run outside the VPN app UID: $output", uid != null && uid != android.os.Process.myUid())
        assertTrue("Direct probe did not finish: $output", output.contains("NC_EXIT="))
        return output
    }

    private fun assertDirectFixtureReachable(instrumentation: Instrumentation) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var output: String
        do {
            output = directFixtureProbe(instrumentation)
            if (output.contains(" 200 ") && output.contains("v2max-tun-ok") && output.contains("NC_EXIT=0")) return
            if (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Direct fixture was not reachable outside the VPN: $output")
    }

    private fun assertDirectFixtureBlocked(instrumentation: Instrumentation) {
        val output = directFixtureProbe(instrumentation)
        assertTrue("Traffic escaped the retained VPN interface: $output",
            !output.contains(" 200 ") && !output.contains("v2max-tun-ok") && !output.contains("NC_EXIT=0"))
    }

    private fun assertTunTraffic(instrumentation: Instrumentation) {
        // UiAutomation runs outside the app UID. The target is unreachable directly;
        // the local VLESS fixture redirects it only after it traverses the VPN/TUN.
        val descriptor = instrumentation.uiAutomation.executeShellCommand("sh /data/local/tmp/v2max-tun-probe.sh")
        val output = descriptor.use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }.trim()
        val probeUid = output.lineSequence().firstOrNull { it.startsWith("PROBE_UID=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()
        assertTrue("Probe must run outside the VPN app UID: $output",
            probeUid != null && probeUid != android.os.Process.myUid())
        assertTrue("No traffic through Android TUN: $output\n${XrayCore.logs().joinToString("\n")}",
            output.contains(" 200 ") && output.contains("v2max-tun-ok") && output.contains("NC_EXIT=0"))
    }
}
