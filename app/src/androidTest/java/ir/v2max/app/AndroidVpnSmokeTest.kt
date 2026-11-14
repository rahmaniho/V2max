package ir.v2max.app

import android.app.Instrumentation
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.app.core.PreflightTickets
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.core.TunnelController
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.Proto
import ir.v2max.app.data.Repo
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.ProbeResult
import ir.v2max.app.net.SpeedTester
import ir.v2max.app.service.V2maxVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

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
                autoConnectOnOpen = false, autoRefreshOnOpen = false, autoUpdateSubs = false,
                useFreeSources = false, deepProbe = false, fragment = false, balancer = false,
                geoAssets = false, multiPing = false, speedTest = false, smartFailover = false,
                routingMode = RoutingMode.GLOBAL,
                delayTestUrl = "http://198.51.100.1:18081/probe"
            )
        }
        val server = ServerConfig(
            name = "Local Xray fixture", proto = Proto.VLESS, address = "10.0.2.2", port = 18080,
            uuid = "11111111-2222-3333-4444-555555555555", method = "none", network = "tcp", security = "none"
        )
        Repo.addServers(listOf(server))
        Repo.setSelected(server.id)
        assertNull("CI must grant ACTIVATE_VPN app-op before this test", VpnService.prepare(context))

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
            TunnelController.cancelPreflight(context)
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
            assertTrue(runBlocking { SmartConnect.connectVerified(context, edited.id) })
            val editDeadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < editDeadline &&
                !(Repo.tunnel.value.status == TunnelStatus.CONNECTED && V2maxVpnService.activeProfileMatches(edited))) {
                SystemClock.sleep(100)
            }
            assertTrue("Edited profile was not applied: ${Repo.tunnel.value.message}", V2maxVpnService.activeProfileMatches(edited))
            assertTunTraffic(instrumentation)

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
        } finally {
            SmartConnect.cancelScan()
            TunnelController.stop(context)
        }
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
