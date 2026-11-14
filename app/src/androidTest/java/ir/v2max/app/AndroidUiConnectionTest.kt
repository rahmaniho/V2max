package ir.v2max.app

import android.app.Instrumentation
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.core.TunnelController
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.PerAppMode
import ir.v2max.app.data.Proto
import ir.v2max.app.data.Repo
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.service.V2maxVpnService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/**
 * Opt-in only: a disposable emulator and the local Xray/HTTP fixture are required.
 * Unlike the lower-level smoke test, these tests use the actual Compose controls,
 * the system VPN consent dialog and a traffic probe from outside the app UID.
 */
@RunWith(AndroidJUnit4::class)
class AndroidUiConnectionTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val alpha = fixture("ui-alpha", "UI Alpha", "11111111-2222-3333-4444-555555555555")
    private val beta = fixture("ui-beta", "UI Beta", "22222222-3333-4444-5555-666666666666")
    private var enabled = false

    private fun fixture(id: String, name: String, uuid: String) = ServerConfig(
        id = id, name = name, proto = Proto.VLESS, address = "10.0.2.2", port = 18080,
        uuid = uuid, method = "none", network = "tcp", security = "none"
    )

    @Before fun setUp() {
        assumeTrue("Explicit UI VPN fixture opt-in is required",
            InstrumentationRegistry.getArguments().getString("enableUiVpnTests") == "true")
        assertEquals("Never mutate a physical device", "1", shell("getprop ro.kernel.qemu"))
        enabled = true
        Repo.init(context)
        assertFalse("A previous test left a live VPN", V2maxVpnService.running || Repo.tunnel.value.isActive)
        shell("appops set ${context.packageName} ACTIVATE_VPN ignore")
        assertNotNull("The VPN permission must be ungranted before the UI test", VpnService.prepare(context))
        Repo.removeServers(Repo.servers.value.map { it.id })
        Repo.scan.value = ScanState()
        assertTrue(Repo.updateSettings {
            it.copy(
                autoConnectOnOpen = false, autoRefreshOnOpen = false, autoUpdateSubs = false,
                autoUpdateCheck = false, appLock = false, allowExternalAutomation = false,
                useFreeSources = false, deepProbe = false, autoTuneFragment = false,
                fragment = false, balancer = false, geoAssets = false, multiPing = false,
                speedTest = false, smartFailover = false, killSwitch = false,
                perAppMode = PerAppMode.OFF, routingMode = RoutingMode.GLOBAL,
                delayTestUrl = "http://198.51.100.1:18081/probe"
            )
        })
        assertEquals(2, Repo.addServers(listOf(alpha, beta)))
        Repo.setSelected(alpha.id)
        assertEquals("waiting", fixtureControl("hold-reset"))
    }

    @After fun tearDown() {
        if (!enabled) return
        runCatching { fixtureControl("hold-release") }
        // Close a system dialog before finishing its caller, including after an assertion failure.
        runCatching {
            if (activePackage().contains("vpndialog", ignoreCase = true)) clickViewId("button2")
        }
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)
                .flatMap { monitor.getActivitiesInStage(it).toList() }
                .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        SmartConnect.cancelScan()
        TunnelController.stop(context)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline &&
            (V2maxVpnService.running || Repo.tunnel.value.isActive || XrayCore.isRunning)) SystemClock.sleep(50)
        Repo.updateSettings { it.copy(autoConnectOnOpen = false) }
        shell("appops set ${context.packageName} ACTIVATE_VPN ignore")
    }

    @Test fun tappingServerRequestsConsentAndConnectsTheSelectedProfile() {
        // Use a held protocol request to prove that no new tunnel starts before
        // the enabled pre-connect test has actually finished.
        Repo.updateSettings { it.copy(delayTestUrl = "http://198.51.100.1:18081/hold-probe") }
        grantNotificationsIfNeeded()
        launchActivity()
        clickText("سرورها")
        clickText("UI Beta")
        awaitVpnDialog()
        assertEquals("The row did not select the requested profile", beta.id, Repo.selectedId.value)
        assertFalse("A tunnel started before consent", Repo.tunnel.value.isActive || V2maxVpnService.running)
        assertFalse("A scan started before consent", Repo.scan.value.running)
        clickViewId("button2")
        awaitText("برای اتصال، اجازه‌ی ساخت VPN لازم است")
        assertNotNull("Denied VPN consent unexpectedly granted permission", VpnService.prepare(context))
        assertFalse("Denial started a tunnel", Repo.tunnel.value.isActive || V2maxVpnService.running)
        assertFalse("Denial started a pre-connect scan", Repo.scan.value.running)
        assertEquals("Denial tested the selected profile", 0L, Repo.serverById(beta.id)?.testedAt)

        clickText("UI Beta")
        awaitVpnDialog()
        clickViewId("button1")
        awaitHeldProbe()
        assertTrue("The UI did not report an in-progress pre-connect scan", Repo.scan.value.running)
        assertFalse("A tunnel started while the selected profile's test was held",
            Repo.tunnel.value.isActive || V2maxVpnService.running || XrayCore.isRunning)
        assertEquals("An unfinished test was published as a verified result", 0L, Repo.serverById(beta.id)?.testedAt)
        fixtureControl("hold-release")
        awaitConnected(beta.id)
        assertTunTraffic()

        // Changing the chosen profile must retain the current VPN while its
        // replacement is tested and then connect the exact new choice.
        assertEquals("waiting", fixtureControl("hold-reset"))
        clickText("UI Alpha")
        awaitHeldProbe()
        assertEquals("The current VPN was dropped during replacement preflight", TunnelStatus.CONNECTED, Repo.tunnel.value.status)
        assertEquals("A replacement was applied before its test completed", beta.id, Repo.tunnel.value.serverId)
        assertTrue("The current core stopped during replacement preflight", V2maxVpnService.running && XrayCore.isRunning)
        assertTunTraffic()
        fixtureControl("hold-release")
        awaitConnected(alpha.id)
        assertTunTraffic()

        // The home power control must disconnect and then reconnect the chosen
        // profile without asking for a permission that is already granted.
        clickText("خانه")
        clickDescription("قطع اتصال")
        awaitDisconnected()
        assertTrue("VPN permission was unexpectedly lost", VpnService.prepare(context) == null)
        clickDescription("اتصال")
        awaitConnected(alpha.id)
        assertTunTraffic()
    }

    @Test fun failedManualChoiceIsReportedBeforeUsingVerifiedFallback() {
        // An invalid VLESS encryption mode makes the selected profile fail its
        // real Xray preflight; the other local profile remains usable.
        Repo.updateServer(alpha.copy(method = "invalid-fixture-encryption"))
        grantNotificationsIfNeeded()
        launchActivity()
        clickText("سرورها")
        clickText("UI Alpha")
        awaitVpnDialog()
        clickViewId("button1")
        awaitConnected(beta.id)
        assertTrue("A failed manual selection was silently replaced: ${Repo.scan.value.message}",
            Repo.scan.value.message.contains("سرور انتخابی تأیید نشد؛ جایگزین تأییدشده"))
        assertTrue("The replacement had no successful protocol test", (Repo.serverById(beta.id)?.realPing ?: -1) > 0)
        assertTunTraffic()
    }

    @Test fun emptyListExplainsWhyConnectionCannotStart() {
        Repo.removeServers(Repo.servers.value.map { it.id })
        grantNotificationsIfNeeded()
        launchActivity()
        clickDescription("اتصال")
        awaitText("سروری برای اتصال وجود ندارد")
        assertNotNull("An empty list must not silently authorize VPN", VpnService.prepare(context))
        assertFalse("An empty list started a tunnel", Repo.tunnel.value.isActive || V2maxVpnService.running)
        assertFalse("An empty list started a scan", Repo.scan.value.running)
    }

    @Test fun automaticConnectionWaitsForRuntimeNotificationAndVpnConsent() {
        Repo.removeServer(beta.id)
        Repo.updateSettings { it.copy(autoConnectOnOpen = true) }
        if (Build.VERSION.SDK_INT >= 33) {
            shell("pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS")
            shell("pm clear-permission-flags ${context.packageName} android.permission.POST_NOTIFICATIONS user-set user-fixed")
        }
        launchActivity()
        if (Build.VERSION.SDK_INT >= 33) {
            awaitPackage("permissioncontroller")
            assertNotNull("VPN consent was unexpectedly granted", VpnService.prepare(context))
            assertFalse("A VPN started while notification permission was pending", Repo.tunnel.value.isActive)
            clickViewId("permission_deny_button")
        }
        awaitVpnDialog()
        assertFalse("A VPN started before the user approved it", Repo.tunnel.value.isActive || V2maxVpnService.running)
        clickViewId("button1")
        awaitConnected(alpha.id)
        assertTunTraffic()
    }

    private fun grantNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    private fun launchActivity() {
        val component = "${context.packageName}/ir.v2max.app.MainActivity"
        val output = shell("am start -W -n $component -a android.intent.action.MAIN")
        assertTrue("Could not start the real activity: $output", output.contains("Starting:"))
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
    }

    private fun activePackage(): String =
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val result = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < 2_000) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(queue::addLast)
            }
        }
        return result
    }

    private fun visibleSummary(): String = nodes().mapNotNull {
        it.text ?: it.contentDescription ?: it.viewIdResourceName
    }.joinToString(" | ").take(1_500)

    private fun awaitPackage(part: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (activePackage().contains(part, ignoreCase = true)) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected system window $part; active=${activePackage()}; ${visibleSummary()}")
    }

    private fun awaitVpnDialog() = awaitPackage("vpndialog")

    private fun awaitText(text: String): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val matches = nodes().filter { it.text?.toString()?.contains(text) == true }
            if (matches.isNotEmpty()) return matches
            SystemClock.sleep(100)
        }
        throw AssertionError("Text not visible: $text; active=${activePackage()}; ${visibleSummary()}")
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.isEnabled &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = candidate.parent
        }
        return false
    }

    private fun clickText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastScroll = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val tree = nodes()
            tree.filter { it.text?.toString()?.contains(text) == true }
                .forEach { if (clickNode(it)) return }
            // Server rows are lazy and may be below the fold on small emulators.
            if (text.startsWith("UI ") && SystemClock.elapsedRealtime() - lastScroll > 700) {
                val list = tree.firstOrNull { it.isScrollable }
                if (list != null && !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                lastScroll = SystemClock.elapsedRealtime()
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Text not visible/clickable: $text; active=${activePackage()}; ${visibleSummary()}")
    }

    private fun clickDescription(description: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            nodes().filter { it.contentDescription?.toString() == description }
                .forEach { if (clickNode(it)) return }
            SystemClock.sleep(100)
        }
        throw AssertionError("Action not visible/clickable: $description; ${visibleSummary()}")
    }

    private fun clickViewId(suffix: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            nodes().filter { it.viewIdResourceName?.endsWith(":id/$suffix") == true }
                .forEach { if (clickNode(it)) return }
            SystemClock.sleep(100)
        }
        throw AssertionError("System action not clickable: $suffix; active=${activePackage()}; ${visibleSummary()}")
    }

    private fun fixtureControl(path: String): String {
        val connection = URL("http://10.0.2.2:18081/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        return try { connection.inputStream.bufferedReader().use { it.readText() } }
        finally { connection.disconnect() }
    }

    private fun awaitHeldProbe() {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { fixtureControl("hold-status") }.getOrDefault("") == "started") return
            SystemClock.sleep(100)
        }
        throw AssertionError("The UI did not reach the held protocol test: ${Repo.scan.value.message}")
    }

    private fun awaitConnected(id: String) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = Repo.tunnel.value
            if (state.status == TunnelStatus.CONNECTED && state.serverId == id) {
                assertEquals("Selected profile was replaced", id, Repo.selectedId.value)
                return
            }
            if (state.status == TunnelStatus.ERROR) break
            SystemClock.sleep(100)
        }
        throw AssertionError("UI VPN did not connect $id: tunnel=${Repo.tunnel.value}; scan=${Repo.scan.value}")
    }

    private fun awaitDisconnected() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!Repo.tunnel.value.isActive && !V2maxVpnService.running && !XrayCore.isRunning) return
            SystemClock.sleep(100)
        }
        throw AssertionError("The home control did not disconnect the VPN: ${Repo.tunnel.value}")
    }

    private fun assertTunTraffic() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var output: String
        do {
            output = shell("sh /data/local/tmp/v2max-tun-probe.sh")
            val uid = output.lineSequence().firstOrNull { it.startsWith("PROBE_UID=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
            assertTrue("TUN traffic probe did not run outside the app UID", uid != null && uid != android.os.Process.myUid())
            if (output.contains(" 200 ") && output.contains("v2max-tun-ok") && output.contains("NC_EXIT=0")) return
            if (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("No external-UID traffic through Android TUN: $output")
    }
}
