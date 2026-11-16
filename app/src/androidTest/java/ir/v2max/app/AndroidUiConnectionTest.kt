package ir.v2max.app

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.core.TunnelController
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.PerAppMode
import ir.v2max.app.data.Proto
import ir.v2max.app.data.Repo
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.service.V2maxVpnService
import ir.v2max.app.ui.AppViewModel
import ir.v2max.app.util.CrashLog
import libv2ray.Libv2ray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in only: a disposable emulator and the local Xray/HTTP fixture are required.
 * Unlike the lower-level smoke test, these tests use the actual Compose controls,
 * the system VPN consent dialog and a traffic probe from outside the app UID.
 * Camera permission and the document picker are also exercised because they
 * share the Activity Result infrastructure involved in the consent regression.
 */
@RunWith(AndroidJUnit4::class)
// Notification permission is revoked by the shell before instrumentation.
// Run this scenario before other tests grant it: revoking a runtime permission
// inside the target process can cause Android to kill the instrumentation.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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
                dropDeadServers = true, fragment = false, balancer = false, geoAssets = false, multiPing = false,
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
            when {
                activePackage().contains("vpndialog", ignoreCase = true) -> clickViewId("button2")
                activePackage().contains("permissioncontroller", ignoreCase = true) ||
                    activePackage().contains("documentsui", ignoreCase = true) -> shell("input keyevent 4")
            }
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
        Repo.updateSettings { it.copy(autoConnectOnOpen = false, autoRefreshOnOpen = false) }
        Repo.removeSubscription("startup-ui-source")
        Repo.removeSubscription("unhealthy-ui-source")
        shell("appops set ${context.packageName} ACTIVATE_VPN ignore")
    }

    @Test fun tappingServerRequestsConsentAndConnectsTheSelectedProfile() {
        // Use a held protocol request to prove that no new tunnel starts before
        // the enabled pre-connect test has actually finished.
        Repo.updateSettings { it.copy(delayTestUrl = "http://198.51.100.1:18081/hold-probe") }
        grantNotificationsIfNeeded()
        launchActivity()
        openServers()
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
        assertTrue("Denied consent was not kept visible", Repo.scan.value.finished &&
            Repo.scan.value.message.contains("اجازه‌ی ساخت VPN لازم است"))
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

        // A notification retest may be canceled while its native request is
        // still running. A new selection must wait for that actual completion,
        // not merely for the cancellation signal, without dropping the old VPN.
        assertEquals("waiting", fixtureControl("hold-reset"))
        Repo.updateSettings { it.copy(delayTestUrl = "http://198.51.100.1:18081/hold-probe") }
        context.startService(Intent(context, V2maxVpnService::class.java).apply {
            action = V2maxVpnService.ACTION_RETEST
        })
        awaitHeldProbe()
        val previousBetaTest = Repo.serverById(beta.id)?.testedAt
        Repo.updateSettings { it.copy(delayTestUrl = "http://198.51.100.1:18081/probe") }
        clickText("UI Beta")
        val scanDeadline = SystemClock.elapsedRealtime() + 10_000
        while (!Repo.scan.value.running && SystemClock.elapsedRealtime() < scanDeadline) SystemClock.sleep(50)
        assertTrue("The new selection did not begin waiting for preflight", Repo.scan.value.running)
        SystemClock.sleep(1_500)
        assertEquals("The old VPN changed before the canceled native retest returned", alpha.id, Repo.tunnel.value.serverId)
        assertEquals(TunnelStatus.CONNECTED, Repo.tunnel.value.status)
        assertEquals("A new test ran while the old native retest was held", previousBetaTest, Repo.serverById(beta.id)?.testedAt)
        assertTunTraffic()
        fixtureControl("hold-release")
        awaitConnected(beta.id)
        assertTunTraffic()

        // The home power control must disconnect and then reconnect the chosen
        // profile without asking for a permission that is already granted.
        clickText("خانه")
        clickDescription("قطع اتصال")
        awaitDisconnected()
        assertTrue("VPN permission was unexpectedly lost", VpnService.prepare(context) == null)
        clickDescription("اتصال")
        awaitConnected(beta.id)
        assertTunTraffic()
    }

    @Test fun failedManualChoiceIsReportedBeforeUsingVerifiedFallback() {
        // An invalid VLESS encryption mode makes the selected profile fail its
        // real Xray preflight; the other local profile remains usable.
        Repo.updateServer(alpha.copy(method = "invalid-fixture-encryption"))
        grantNotificationsIfNeeded()
        launchActivity()
        openServers()
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
            assertEquals("The shell must revoke notification permission before instrumentation starts",
                PackageManager.PERMISSION_DENIED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
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

    @Test fun cameraPermissionFromSubscriptionCanBeDeniedWithoutConnecting() {
        assertEquals("The shell must reset camera permission before instrumentation starts",
            PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        grantNotificationsIfNeeded()
        launchActivity()
        clickText("اشتراک")
        awaitText("منبع کانفیگ‌های شما")
        clickText("QR")
        awaitPackage("permissioncontroller")
        clickViewId("permission_deny_button")
        awaitText("برای اسکن QR به دوربین نیاز است")
        assertEquals(PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        assertNotNull("Opening QR must not authorize the VPN", VpnService.prepare(context))
        assertFalse("Opening QR unexpectedly started a tunnel", Repo.tunnel.value.isActive || V2maxVpnService.running)
        clickText("بازگشت")
        awaitText("منبع کانفیگ‌های شما")
    }

    @Test fun restoreOpensAndCancelsTheSystemDocumentPicker() {
        val settingsBefore = Repo.settings.value.copy()
        grantNotificationsIfNeeded()
        launchActivity()
        clickText("تنظیمات")
        awaitText("تنظیمات را با دقت تغییر دهید")
        clickScrollableText("بازیابی")
        awaitPackage("documentsui")
        shell("input keyevent 4")
        awaitPackage(context.packageName)
        assertEquals("Canceling restore must keep the existing servers", setOf(alpha.id, beta.id),
            Repo.servers.value.map { it.id }.toSet())
        assertEquals("Scrolling and canceling restore must not change settings", settingsBefore, Repo.settings.value)
        assertNotNull("Opening restore must not authorize the VPN", VpnService.prepare(context))
        assertFalse("Opening restore unexpectedly started a tunnel", Repo.tunnel.value.isActive || V2maxVpnService.running)
    }

    @Test fun manualSelectionCancelsStartupButWaitsForItsNativeProbe() {
        grantNotificationsIfNeeded()
        assertTrue(Repo.addSubscription(Subscription(
            id = "startup-ui-source", name = "Startup fixture",
            url = "http://10.0.2.2:18081/subscription", updatedAt = System.currentTimeMillis()
        )))
        assertTrue(Repo.updateSettings { it.copy(
            autoRefreshOnOpen = true, autoConnectOnOpen = true,
            delayTestUrl = "http://198.51.100.1:18081/hold-probe"
        ) })
        launchActivity()
        awaitHeldProbe()
        val fetched = requireNotNull(Repo.servers.value.firstOrNull { it.subId == "startup-ui-source" })
        assertTrue(Repo.sync.value.running)
        assertFalse(Repo.tunnel.value.isActive || V2maxVpnService.running)

        // An explicit choice supersedes startup, but the held native call is
        // still in flight and must really return before a new tunnel starts.
        Repo.updateSettings { it.copy(delayTestUrl = "http://198.51.100.1:18081/probe") }
        openServers()
        clickText("UI Alpha")
        awaitVpnDialog()
        clickViewId("button1")
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!Repo.scan.value.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Manual preflight did not start", Repo.scan.value.running)
        SystemClock.sleep(1_500)
        assertFalse("Cancellation started a tunnel before the old native test returned",
            Repo.tunnel.value.isActive || V2maxVpnService.running || XrayCore.isRunning)
        assertEquals("An incomplete startup scan published a result", 0L, Repo.serverById(fetched.id)?.testedAt)
        assertEquals("The new manual test ran before the held scan finished", 0L, Repo.serverById(alpha.id)?.testedAt)
        fixtureControl("hold-release")
        awaitConnected(alpha.id)
        assertNotNull("Canceling an incomplete startup test must keep its fetched profile", Repo.serverById(fetched.id))
        assertNotNull("Canceling startup must keep personal profiles", Repo.serverById(beta.id))
        assertTunTraffic()
    }

    @Test fun subscriptionRefreshAndHealthFinishBeforeAutomaticConsentAndPreflight() {
        grantNotificationsIfNeeded()
        Repo.removeServers(listOf(alpha.id, beta.id))
        val before = System.currentTimeMillis()
        assertTrue(Repo.addSubscription(Subscription(
            id = "startup-ui-source", name = "Startup fixture",
            url = "http://10.0.2.2:18081/subscription", updatedAt = before
        )))
        assertTrue(Repo.updateSettings { it.copy(
            autoRefreshOnOpen = true, autoConnectOnOpen = true,
            delayTestUrl = "http://198.51.100.1:18081/hold-probe"
        ) })
        launchActivity()
        awaitHeldProbe()
        val refreshed = Repo.subById("startup-ui-source")
        assertTrue("Even a recently updated source must be fetched on launch", (refreshed?.updatedAt ?: 0) > before)
        val fetched = Repo.servers.value.firstOrNull { it.subId == "startup-ui-source" }
        assertNotNull("The subscription was not applied before the health check", fetched)
        assertTrue("The subscription check was not shown as background preparation", Repo.sync.value.running)
        assertFalse("The connection preflight must follow the subscription check", Repo.scan.value.running)
        assertFalse("A tunnel started while health was being checked", Repo.tunnel.value.isActive || V2maxVpnService.running)
        assertNotNull("VPN permission was granted before health finished", VpnService.prepare(context))
        assertFalse("VPN consent was shown before health finished", activePackage().contains("vpndialog", ignoreCase = true))
        assertFalse("An unfinished health result was published", (fetched?.realPing ?: -1) > 0)

        fixtureControl("hold-release")
        awaitVpnDialog()
        val id = requireNotNull(fetched).id
        assertFalse("Subscription health was still in progress at consent", Repo.sync.value.running)
        assertTrue("A completed real health result was not published", (Repo.serverById(id)?.realPing ?: -1) > 0)
        assertEquals("waiting", fixtureControl("hold-reset"))
        clickViewId("button1")
        awaitHeldProbe()
        assertTrue("The independent connection preflight did not start", Repo.scan.value.running)
        assertFalse("Stored health bypassed the active preflight", Repo.tunnel.value.isActive || V2maxVpnService.running)
        fixtureControl("hold-release")
        awaitConnected(id)
        assertFalse("An accepted automatic receipt was falsely reported as canceled", Repo.scan.value.message.contains("لغو"))
        assertTunTraffic()
    }

    @Test fun failedStartupRefreshDoesNotStartAutomaticConnectionButManualChoiceWorks() {
        grantNotificationsIfNeeded()
        assertTrue(Repo.addSubscription(Subscription(
            id = "startup-ui-source", name = "Unavailable fixture", url = "http://10.0.2.2:18081/not-found"
        )))
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = true, autoConnectOnOpen = true) })
        launchActivity()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while ((Repo.subById("startup-ui-source")?.lastError.isNullOrBlank() || Repo.sync.value.running) &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertFalse("The failing refresh was not recorded", Repo.subById("startup-ui-source")?.lastError.isNullOrBlank())
        assertFalse("The failing refresh must not start an automatic scan", Repo.scan.value.running)
        assertFalse(Repo.tunnel.value.isActive || V2maxVpnService.running)
        assertNotNull("Automatic VPN consent must not appear on a failed refresh", VpnService.prepare(context))
        assertFalse(activePackage().contains("vpndialog", ignoreCase = true))
        assertNotNull(Repo.serverById(alpha.id))
        assertNotNull(Repo.serverById(beta.id))
        openServers()
        clickText("UI Alpha")
        awaitVpnDialog()
        clickViewId("button1")
        awaitConnected(alpha.id)
        assertTunTraffic()
    }

    @Test fun startupHealthPreservesNativeSetupErrorAndRemovesConfirmedNoResponse() {
        grantNotificationsIfNeeded()
        val before = System.currentTimeMillis()
        assertTrue(Repo.addSubscription(Subscription(
            id = "startup-ui-source", name = "Mixed fixture", updatedAt = before,
            url = "http://10.0.2.2:18081/subscription-mixed"
        )))
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = true, autoConnectOnOpen = false) })
        launchActivity()
        val deadline = SystemClock.elapsedRealtime() + 95_000
        while (((Repo.subById("startup-ui-source")?.updatedAt ?: 0) <= before || Repo.sync.value.running) &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("The source was not refreshed", (Repo.subById("startup-ui-source")?.updatedAt ?: 0) > before)
        assertFalse("Startup health did not finish", Repo.sync.value.running)
        assertEquals("The mixed fixture must contain all three profiles", 3, Repo.subById("startup-ui-source")?.count)
        val fromFeed = Repo.servers.value.filter { it.subId == "startup-ui-source" }
        val good = fromFeed.firstOrNull { it.port == 18080 && it.method == "none" }
        val unknown = fromFeed.firstOrNull { it.method == "invalid-fixture-encryption" }
        assertNotNull("The healthy fixture was lost", good)
        assertTrue("No native protocol success was published", (good?.realPing ?: -1) > 0)
        assertNotNull("An Xray setup error was mistaken for a dead server", unknown)
        assertEquals("An untested/unknown result must not be published as a success", 0L, unknown?.testedAt)
        val remaining = fromFeed.firstOrNull { it.port == 18082 }
        if (remaining != null) {
            // Only synthetic fixture URLs and credentials are used here. Capture
            // the native error channel on failure rather than guessing whether
            // this was a setup problem or an actual unanswered request.
            val config = ConfigBuilder.testConfig(remaining, Repo.settings.value.copy(fragment = false, balancer = false))
            val details = listOf("http://198.51.100.1:18081/probe", "https://www.cloudflare.com/cdn-cgi/trace")
                .joinToString { url ->
                    try {
                        "value=${Libv2ray.measureOutboundDelay(config, url)}"
                    } catch (error: Throwable) {
                        "${error.javaClass.simpleName}: ${error.message.orEmpty().take(500)}"
                    }
                }
            org.junit.Assert.fail(
                "No-response fixture was retained; cleanup=${Repo.settings.value.dropDeadServers}, " +
                    "selected=${remaining.id == Repo.selectedId.value}, favorite=${remaining.favorite}, " +
                    "ping=${remaining.realPing}, testedAt=${remaining.testedAt}; native=$details"
            )
        }
        assertEquals(0, Repo.subById("startup-ui-source")?.healthFailureChecks)
        assertFalse(Repo.tunnel.value.isActive || V2maxVpnService.running)
    }

    @Test fun partialStartupFeedRetainsAnOmittedPersonalProfile() {
        grantNotificationsIfNeeded()
        val before = System.currentTimeMillis()
        val source = Subscription(
            id = "startup-ui-source", name = "Partial fixture", updatedAt = before,
            url = "http://10.0.2.2:18081/subscription-partial"
        )
        val previous = fixture("old-personal-profile", "Previously healthy", "33333333-3333-4333-8333-333333333333")
            .copy(subId = source.id, realPing = 17, testedAt = before - 1_000,
                testedUrl = "http://198.51.100.1:18081/probe")
        assertTrue(Repo.addSubscription(source))
        Repo.updateServer(previous)
        assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = true, autoConnectOnOpen = false) })
        launchActivity()
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (((Repo.subById(source.id)?.updatedAt ?: 0) <= before || Repo.sync.value.running) &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("The partial source was not refreshed", (Repo.subById(source.id)?.updatedAt ?: 0) > before)
        assertFalse("Startup health did not finish", Repo.sync.value.running)
        assertEquals("A partial refresh discarded a previously healthy profile", 17, Repo.serverById(previous.id)?.realPing)
        val received = Repo.servers.value.firstOrNull { it.subId == source.id && it.id != previous.id }
        assertNotNull("The received profile was not added", received)
        assertTrue("The received profile did not pass the real protocol test", (received?.realPing ?: -1) > 0)
        assertFalse(Repo.tunnel.value.isActive || V2maxVpnService.running)
    }

    @Test fun unhealthySubscriptionRequiresExplicitConfirmationAndCanBeDeferred() {
        grantNotificationsIfNeeded()
        val now = System.currentTimeMillis()
        val source = Subscription(
            id = "unhealthy-ui-source", name = "اشتراک نمونه", url = "https://example.org/sub",
            updatedAt = 100L, count = 1,
            healthFirstFailureAt = now - 24L * 60 * 60 * 1000,
            healthLastFailureAt = now, healthFailureChecks = 2, healthCheckedRevision = 100L
        )
        val owned = alpha.copy(id = "unhealthy-ui-server", uuid = "unhealthy-fixture", subId = source.id)
        assertTrue(Repo.addSubscription(source))
        Repo.updateServer(owned)
        launchActivity()
        awaitText("بررسی حذف اشتراک")
        assertNotNull("A suggestion must not delete the subscription by itself", Repo.subById(source.id))
        assertNotNull(Repo.serverById(owned.id))
        clickText("بعداً")
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while ((Repo.subById(source.id)?.healthDismissedAt ?: 0L) == 0L && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
        val deferred = requireNotNull(Repo.subById(source.id))
        assertTrue("Deferring did not persist the user's choice", deferred.healthDismissedAt > 0)
        assertNotNull("Deferring unexpectedly deleted the server", Repo.serverById(owned.id))

        // Model the end of the reminder delay without changing the system clock.
        // The policy's actual seven-day and freshness rules are unit-tested.
        Repo.updateSubscription(deferred.copy(healthDismissedAt = 0))
        awaitText("بررسی حذف اشتراک")
        clickText("حذف اشتراک")
        val deletionDeadline = SystemClock.elapsedRealtime() + 5_000
        while (Repo.subById(source.id) != null && SystemClock.elapsedRealtime() < deletionDeadline) SystemClock.sleep(50)
        assertTrue("The confirmed subscription was not removed", Repo.subById(source.id) == null)
        assertTrue("The confirmed source's profile was not removed", Repo.serverById(owned.id) == null)
        assertNotNull("Deleting a subscription must not delete unrelated personal profiles", Repo.serverById(alpha.id))
        assertNotNull(Repo.serverById(beta.id))
        assertFalse("A cleanup prompt must not start a VPN", Repo.tunnel.value.isActive || V2maxVpnService.running)
    }

    @Test fun privateDiagnosticsRequirePreviewAndDoNotExportLegacySecrets() {
        grantNotificationsIfNeeded()
        val secret = "vless://fixture-secret@203.0.113.42:443?token=fixture-token#private"
        val crashFile = File(context.filesDir, "crash.log")
        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        val chooser = AtomicReference<Intent?>(null)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                // Intercept the chooser; no app actually receives the report.
                return Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            assertTrue(CrashLog.clear())
            CrashLog.record(IllegalArgumentException(secret), "viewModel")
            assertFalse("New local records must not contain raw exception text", crashFile.readText().contains(secret))
            crashFile.writeText("=== legacy raw error: $secret ===\n203.0.113.42\n")
            clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "unchanged"))
            launchActivity()
            clickText("تنظیمات")
            awaitText("تنظیمات را با دقت تغییر دهید")
            clickScrollableText("خلاصهٔ خصوصی خطا")
            awaitText("پیش‌نمایش گزارش خصوصی")
            val displayedCrashReport = awaitText("=== گزارش خصوصی V2max ===").first().text.toString()
            assertFalse(displayedCrashReport.contains(secret))
            assertFalse(displayedCrashReport.contains("203.0.113.42"))
            assertEquals("Opening the preview must not copy anything", "unchanged",
                clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            clickText("انصراف")
            assertEquals("Canceling must not copy anything", "unchanged",
                clipboard.primaryClip?.getItemAt(0)?.text?.toString())

            clickScrollableText("خلاصهٔ خصوصی خطا")
            val copiedPreview = awaitText("=== گزارش خصوصی V2max ===").first().text.toString()
            clickText("کپی خلاصه")
            assertEquals("Only the exact preview may be copied", copiedPreview,
                clipboard.primaryClip?.getItemAt(0)?.text?.toString())

            clickScrollableText("عیب‌یابی و گزارش")
            clickScrollableText("پیش‌نمایش و اشتراک‌گذاری")
            awaitText("پیش‌نمایش گزارش خصوصی")
            val sharedPreview = awaitText("=== گزارش خصوصی V2max ===").first().text.toString()
            assertTrue(sharedPreview.contains("IP خروجی VPN: نامشخص"))
            assertTrue(sharedPreview.contains("DNS و نشت DNS: نامشخص"))
            assertTrue(sharedPreview.contains("عبور ترافیک برنامه‌های دیگر از TUN: تأیید نشده"))
            assertFalse(sharedPreview.contains(secret))
            assertFalse(sharedPreview.contains("UI Alpha"))
            assertFalse(sharedPreview.contains("10.0.2.2"))
            assertNull("No share chooser should open before consent", chooser.get())
            clickText("اشتراک‌گذاری")
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (chooser.get() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            @Suppress("DEPRECATION")
            val send = chooser.get()?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull("The explicit share action did not launch a chooser", send)
            assertEquals(Intent.ACTION_SEND, send?.action)
            assertEquals("Only the exact preview may be shared", sharedPreview, send?.getStringExtra(Intent.EXTRA_TEXT))
            assertFalse("Diagnostics must not start a VPN", Repo.tunnel.value.isActive || V2maxVpnService.running)
            assertFalse("Diagnostics must not start a scan by itself", Repo.scan.value.running)

            // The optional button is a real Xray scan, never an implicit connection.
            clickScrollableText("تست همهٔ سرورها")
            val scanDeadline = SystemClock.elapsedRealtime() + 40_000
            while ((Repo.scan.value.running || !Repo.scan.value.finished || Repo.scan.value.diagnosticCounts == null) &&
                SystemClock.elapsedRealtime() < scanDeadline) SystemClock.sleep(50)
            val counts = Repo.scan.value.diagnosticCounts
            assertNotNull("A complete scan did not publish diagnostics: ${Repo.scan.value.message}", counts)
            assertEquals(2, counts?.responded)
            assertEquals(0, counts?.noResponse)
            assertEquals(0, counts?.unknown)
            assertFalse("Testing servers must not start a VPN", Repo.tunnel.value.isActive || V2maxVpnService.running)
            awaitText("بی‌پاسخ")
            clickScrollableText("پیش‌نمایش و اشتراک‌گذاری")
            val scannedPreview = awaitText("=== گزارش خصوصی V2max ===").first().text.toString()
            assertTrue("The report did not show the completed scan", scannedPreview.contains("پاسخ‌گو 2، بی‌پاسخ 0، نامشخص 0"))
            clickText("انصراف")
        } finally {
            instrumentation.removeMonitor(monitor)
            CrashLog.clear()
        }
    }

    private fun grantNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    private fun openServers() {
        clickText("سرورها")
        // The Home card also displays the selected server and only opens this
        // tab. Wait for the actual server list before clicking a server row.
        awaitText("برای اتصال، سرور موردنظر را لمس کنید")
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

    private fun permissionDiagnostics(): String {
        var vmState = "no tracked MainActivity"
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val activity = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it).toList() }
                .filterIsInstance<MainActivity>().firstOrNull()
            if (activity != null) {
                val vm = ViewModelProvider(activity)[AppViewModel::class.java]
                vmState = "vpn=${vm.vpnPermissionState.value}; notification=${vm.notificationPermissionInFlight.value}; " +
                    "launchError=${vm.lastVpnPermissionLaunchError}; message=${vm.message.value}"
            }
        }
        val needsPermission = runCatching { VpnService.prepare(context) != null }.getOrNull()
        return "$vmState; selected=${Repo.selectedId.value}; auto=${Repo.settings.value.autoConnectOnOpen}; " +
            "scan=${Repo.scan.value}; tunnel=${Repo.tunnel.value.status}; needsVpn=$needsPermission"
    }

    private fun awaitPackage(part: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (activePackage().contains(part, ignoreCase = true)) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected system window $part; active=${activePackage()}; ${visibleSummary()}; ${permissionDiagnostics()}")
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

    private fun clickScrollableText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var swipes = 0
        val seenMatches = linkedSetOf<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val tree = nodes()
            tree.filter { it.text?.toString()?.contains(text) == true }.forEach {
                seenMatches.add("${it.text}:${it.isClickable}:${it.isEnabled}")
                if (it.text?.toString() == text && clickNode(it)) return
            }
            val list = tree.firstOrNull { it.isScrollable && it.isEnabled }
            val bounds = Rect()
            list?.getBoundsInScreen(bounds)
            if (bounds.height() > 100 && bounds.width() > 100) {
                // Accessibility page-scroll can skip a short item at a viewport
                // boundary. Swipe a fraction of the visible list like a user.
                val down = (swipes / 20) % 2 == 0
                val high = bounds.top + bounds.height() * 3 / 4
                val low = bounds.top + bounds.height() * 2 / 5
                val from = if (down) high else low
                val to = if (down) low else high
                shell("input swipe ${bounds.centerX()} $from ${bounds.centerX()} $to 300")
                swipes++
            }
            SystemClock.sleep(150)
        }
        throw AssertionError("Scrollable action not visible/clickable: $text; seen=$seenMatches; active=${activePackage()}; ${visibleSummary()}")
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
