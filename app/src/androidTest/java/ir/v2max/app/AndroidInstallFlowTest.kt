package ir.v2max.app

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.net.UpdateInstaller
import ir.v2max.app.service.InstallConfirmation
import ir.v2max.app.service.InstallResultReceiver
import ir.v2max.app.service.Notifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.ArrayDeque

/** PackageInstaller tests use disposable emulator fixtures, never a public APK. */
@RunWith(AndroidJUnit4::class)
class AndroidInstallFlowTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var oldSettings: AppSettings
    private lateinit var oldTunnel: TunnelUiState
    private var oldMessage = ""
    private val fixtureIds = mutableSetOf<Int>()

    @Before fun setUp() {
        Repo.init(context)
        oldSettings = Repo.settings.value.copy()
        oldTunnel = Repo.tunnel.value
        oldMessage = Repo.installMessage.value
        Repo.updateSettings {
            it.copy(appLock = false, autoConnectOnOpen = false, autoRefreshOnOpen = false,
                autoUpdateSubs = false, autoUpdateCheck = false, useFreeSources = false, geoAssets = false)
        }
    }

    @After fun tearDown() {
        val pending = InstallConfirmation.pending.value
        if (pending != null) {
            fixtureIds.add(pending.sessionId)
            // An actual session is enabled only on a disposable CI emulator.
            if (InstrumentationRegistry.getArguments().getString("allowInstallSessionTest") == "true") {
                runCatching { context.packageManager.packageInstaller.abandonSession(pending.sessionId) }
            }
            InstallConfirmation.clear(pending.sessionId)
        }
        fixtureIds.forEach { Notifier.cancelInstallConfirmation(context, it) }
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)
                .flatMap { monitor.getActivitiesInStage(it).toList() }
                .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        Repo.tunnel.value = oldTunnel
        Repo.installMessage.value = oldMessage
        Repo.saveSettings(oldSettings)
    }

    private fun result(status: Int, sessionId: Int, confirmation: Intent? = null): Intent =
        Intent(context, InstallResultReceiver::class.java).apply {
            putExtra(PackageInstaller.EXTRA_STATUS, status)
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            if (confirmation != null) putExtra(Intent.EXTRA_INTENT, confirmation)
        }

    private fun hasNotification(id: Int): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.id == Notifier.INSTALL_NOTIFICATION_ID && it.tag == Notifier.installNotificationTag(id)
        }

    @Test fun missingConfirmationReportsFailureWithoutChangingVpnState() {
        val id = 727001
        fixtureIds.add(id)
        val original = oldTunnel.copy(message = "vpn-state-fixture")
        Repo.tunnel.value = original
        InstallResultReceiver().onReceive(context, result(PackageInstaller.STATUS_PENDING_USER_ACTION, id))
        assertEquals("صفحهٔ تأیید نصب دریافت نشد؛ دوباره تلاش کنید", Repo.installMessage.value)
        assertEquals(original, Repo.tunnel.value)
        assertNull(InstallConfirmation.pending.value)
        assertFalse(hasNotification(id))
    }

    @Test fun pendingConfirmationSurvivesLaunchFailureAndOldSessionResults() {
        val id = 727002
        val older = 727003
        fixtureIds.addAll(listOf(id, older))
        val receiver = InstallResultReceiver()
        val original = oldTunnel.copy(message = "vpn-state-fixture")
        Repo.tunnel.value = original
        receiver.onReceive(context, result(PackageInstaller.STATUS_PENDING_USER_ACTION, id,
            Intent(context, MainActivity::class.java)))
        val request = InstallConfirmation.pending.value
        assertNotNull(request)
        assertEquals(id, request?.sessionId)
        assertEquals(original, Repo.tunnel.value)
        val denied = InstrumentationRegistry.getArguments().getString("expectNotificationDenied") == "true"
        if (denied) {
            assertFalse("A notification was posted without permission", hasNotification(id))
            assertTrue(Repo.installMessage.value.contains("برنامه را باز"))
        } else {
            assertTrue("No recoverable installer notification", hasNotification(id))
        }

        val rejectingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("fixture") }
        }
        assertFalse(InstallConfirmation.open(rejectingContext, requireNotNull(request)))
        assertSame("A failed launch discarded the confirmation", request, InstallConfirmation.pending.value)
        receiver.onReceive(context, result(PackageInstaller.STATUS_FAILURE_ABORTED, older))
        assertSame("An older session cleared the current confirmation", request, InstallConfirmation.pending.value)
        receiver.onReceive(context, result(PackageInstaller.STATUS_FAILURE_ABORTED, id))
        assertNull(InstallConfirmation.pending.value)
        assertFalse(hasNotification(id))
        assertEquals("نصب لغو شد", Repo.installMessage.value)
        assertEquals(original, Repo.tunnel.value)
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
        }

    private fun tree(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && nodes.size < 2_000) {
            val node = queue.removeFirst()
            nodes.add(node)
            for (i in 0 until node.childCount) runCatching { node.getChild(i) }.getOrNull()?.let(queue::addLast)
        }
        return nodes
    }

    private fun awaitNode(description: String, timeout: Long = 30_000, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var latest = emptyList<AccessibilityNodeInfo>()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = tree()
            latest.firstOrNull(match)?.let { return it }
            SystemClock.sleep(100)
        }
        val visible = latest.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ").take(1600)
        val pkg = latest.firstOrNull()?.packageName
        throw AssertionError("$description not visible; package=$pkg; content=$visible")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(7) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.isEnabled &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        throw AssertionError("Node is not clickable: ${node.text}")
    }

    @Test fun realInstallerConfirmationCanBeOpenedAndCancelled() {
        // This test stages the emulator's own signed debug APK and cancels;
        // never run it implicitly on a user's device or perform an actual update.
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowInstallSessionTest") == "true")
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        assertTrue("Install-source permission was not granted", UpdateInstaller.canRequestInstalls(context))
        val activity = "${context.packageName}/ir.v2max.app.MainActivity"
        assertTrue(shell("am start -W -n $activity -a android.intent.action.MAIN").contains("Starting:"))
        awaitNode("app home") { it.text?.toString()?.contains("V2max") == true }

        val installedApk = File(context.applicationInfo.sourceDir)
        assertTrue("Installed debug APK unavailable", installedApk.isFile)
        assertTrue("Could not stage signed fixture", UpdateInstaller.install(context, installedApk))
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline && InstallConfirmation.pending.value == null) SystemClock.sleep(100)
        val request = requireNotNull(InstallConfirmation.pending.value) { "PackageInstaller did not request confirmation: ${Repo.installMessage.value}" }
        fixtureIds.add(request.sessionId)
        assertTrue("Missing PackageInstaller session id", request.sessionId >= 0)
        assertTrue("Installer notification was not posted", hasNotification(request.sessionId))
        awaitNode("in-app installer dialog") { it.text?.toString()?.contains("تأیید نصب به‌روزرسانی") == true }
        click(awaitNode("continue installation") { it.text?.toString() == "ادامه نصب" })

        val cancel = awaitNode("system installer cancel button", 45_000) {
            val pkg = it.packageName?.toString().orEmpty()
            val text = it.text?.toString()?.trim().orEmpty()
            (pkg.contains("packageinstaller") || pkg.contains("permissioncontroller")) &&
                (text.equals("cancel", ignoreCase = true) || text == "لغو" || text == "انصراف")
        }
        val visible = tree().mapNotNull { it.text?.toString() }.joinToString(" | ").lowercase()
        assertTrue("Unexpected system screen: $visible", "install" in visible || "update" in visible || "نصب" in visible)
        click(cancel)
        val cancelDeadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < cancelDeadline && InstallConfirmation.pending.value != null) SystemClock.sleep(100)
        assertNull("Canceled session did not deliver a terminal result", InstallConfirmation.pending.value)
        assertEquals("نصب لغو شد", Repo.installMessage.value)
        assertFalse("Canceled session left a confirmation notification", hasNotification(request.sessionId))
        assertTrue("The installed APK was deleted", installedApk.isFile)
    }
}
