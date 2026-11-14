package ir.v2max.app

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Repo
import ir.v2max.app.ui.AppLock
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.ArrayDeque

/** Security checks use the shell UID, not a broadcast sent with the app's identity. */
@RunWith(AndroidJUnit4::class)
class AndroidExternalControlTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var oldSettings: AppSettings
    private var oldSelection = ""
    private val hosts = listOf(
        "auto-off.invalid", "auto-on.invalid", "auto-locked.invalid",
        "activity-off.invalid", "activity-on.invalid", "activity-locked.invalid",
        "share-first.invalid", "share-second.invalid"
    )

    @Before fun setUp() {
        Repo.init(context)
        oldSettings = Repo.settings.value.copy()
        oldSelection = Repo.selectedId.value
        Repo.updateSettings {
            it.copy(
                appLock = false, allowExternalAutomation = false,
                autoConnectOnOpen = false, autoRefreshOnOpen = false, autoUpdateSubs = false,
                autoUpdateCheck = false, useFreeSources = false, geoAssets = false
            )
        }
        removeFixtures()
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)
                .flatMap { monitor.getActivitiesInStage(it).toList() }
                .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        SmartConnect.cancelScan()
        removeFixtures()
        if (oldSelection.isBlank() || Repo.serverById(oldSelection) != null) Repo.setSelected(oldSelection)
        Repo.saveSettings(oldSettings)
    }

    private fun removeFixtures() {
        Repo.removeServers(Repo.servers.value.filter { it.address in hosts }.map { it.id })
    }

    private fun link(host: String) = "vless://11111111-2222-3333-4444-555555555555@$host:443"

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
        }

    private fun broadcastImport(host: String) {
        val receiver = "${context.packageName}/ir.v2max.app.service.AutoReceiver"
        val output = shell("am broadcast -n $receiver -a ${MainActivity.ACTION_IMPORT} --es text ${link(host)}")
        assertTrue("Broadcast was not delivered: $output", output.contains("Broadcast completed"))
    }

    private fun startImport(action: String, host: String, share: Boolean = false) {
        val activity = "${context.packageName}/ir.v2max.app.MainActivity"
        val arguments = if (share) "-t text/plain --es android.intent.extra.TEXT ${link(host)}"
            else if (action == MainActivity.ACTION_IMPORT) "--es text ${link(host)}"
            else "-d ${link(host)}"
        val output = shell("am start -W -n $activity -a $action $arguments")
        assertTrue("Activity request was not started: $output", output.contains("Starting:"))
    }

    private fun hasHost(host: String) = Repo.servers.value.any { it.address == host }

    private fun awaitHost(host: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline && !hasHost(host)) SystemClock.sleep(50)
        assertTrue("Expected import did not occur: $host", hasHost(host))
    }

    private fun accessibilityTree(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && nodes.size < 2_000) {
            val node = queue.removeFirst()
            nodes.add(node)
            for (index in 0 until node.childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(queue::addLast)
            }
        }
        return nodes
    }

    private fun awaitText(text: String): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var latest = emptyList<AccessibilityNodeInfo>()
        while (SystemClock.elapsedRealtime() < deadline) {
            // Compose's virtual node provider does not implement Android's
            // findAccessibilityNodeInfosByText. Traverse its actual children.
            latest = accessibilityTree()
            val matches = latest.filter {
                it.text?.toString()?.contains(text) == true || it.contentDescription?.toString()?.contains(text) == true
            }
            if (matches.isNotEmpty()) return matches
            SystemClock.sleep(100)
        }
        val visible = latest.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ").take(1500)
        throw AssertionError("Dialog text not visible: $text; active window: $visible")
    }

    private fun clickText(text: String) {
        val nodes = awaitText(text)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            repeat(6) {
                val candidate = current ?: return@repeat
                if (candidate.isClickable && candidate.isEnabled &&
                    candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                current = candidate.parent
            }
        }
        throw AssertionError("Dialog action was not clickable: $text")
    }

    @Test fun broadcastAutomationIsOptInAndAppLockRevokesConsent() {
        broadcastImport("auto-off.invalid")
        assertFalse("Disabled automation imported a profile", hasHost("auto-off.invalid"))

        Repo.updateSettings { it.copy(allowExternalAutomation = true) }
        val control = context.getSharedPreferences("v2max_control_consent", Context.MODE_PRIVATE)
        assertTrue("Consent was not persisted separately", control.getBoolean("allow_external_automation", false))
        val storedSettings = context.getSharedPreferences("v2max_store", Context.MODE_PRIVATE)
            .getString("settings_v1", "").orEmpty()
        assertFalse("Consent was serialized in portable settings", storedSettings.contains("allowExternalAutomation"))
        broadcastImport("auto-on.invalid")
        awaitHost("auto-on.invalid")

        Repo.updateSettings { it.copy(appLock = true, allowExternalAutomation = true) }
        assertFalse("App lock did not revoke consent", Repo.settings.value.allowExternalAutomation)
        assertFalse("Revoked consent is still stored", control.getBoolean("allow_external_automation", false))
        broadcastImport("auto-locked.invalid")
        assertFalse("Automation bypassed app lock", hasHost("auto-locked.invalid"))
        Repo.updateSettings { it.copy(appLock = false) }
        assertFalse("Unlock silently restored automation", Repo.settings.value.allowExternalAutomation)
    }

    @Test fun activityAutomationIsOptInAndAppLockDeniesIt() {
        startImport(MainActivity.ACTION_IMPORT, "activity-off.invalid")
        SystemClock.sleep(400)
        assertFalse("Disabled activity action imported a profile", hasHost("activity-off.invalid"))

        Repo.updateSettings { it.copy(allowExternalAutomation = true) }
        startImport(MainActivity.ACTION_IMPORT, "activity-on.invalid")
        awaitHost("activity-on.invalid")

        Repo.updateSettings { it.copy(appLock = true) }
        startImport(MainActivity.ACTION_IMPORT, "activity-locked.invalid")
        SystemClock.sleep(400)
        assertFalse("Activity action bypassed app lock", hasHost("activity-locked.invalid"))
    }

    @Test fun unavailableDeviceAuthenticationDoesNotUnlockAnExistingAppLock() {
        // The disposable CI emulator normally has no PIN, pattern or biometric.
        // Other devices must not be modified just to run this negative test.
        val available = AppLock.isAvailable(context)
        if (InstrumentationRegistry.getArguments().getString("expectNoDeviceCredential") == "true") {
            assertFalse("CI device unexpectedly has authentication configured", available)
        }
        assumeFalse(available)
        Repo.updateSettings { it.copy(appLock = true) }
        val activity = "${context.packageName}/ir.v2max.app.MainActivity"
        val output = shell("am start -W -n $activity -a android.intent.action.MAIN")
        assertTrue("Activity request was not started: $output", output.contains("Starting:"))
        awaitText("V2max قفل است")
        awaitText("روش احراز هویت در دسترس نیست")
        clickText("باز کردن با اثر انگشت / رمز")
        awaitText("روش احراز هویت در دسترس نیست")
        awaitText("V2max قفل است")
        awaitText("تنظیمات امنیت دستگاه")
    }

    @Test fun externalShareAndDeepLinkRequireConfirmationAndCannotReplacePendingLink() {
        startImport(android.content.Intent.ACTION_VIEW, "share-first.invalid")
        awaitText("افزودن لینک دریافتی؟")
        assertFalse("Deep link was imported before confirmation", hasHost("share-first.invalid"))

        // A second sender cannot replace the link being reviewed in the dialog.
        startImport(android.content.Intent.ACTION_SEND, "share-second.invalid", share = true)
        clickText("وارد کردن")
        awaitHost("share-first.invalid")
        assertFalse("A second sender replaced the pending link", hasHost("share-second.invalid"))

        startImport(android.content.Intent.ACTION_SEND, "share-second.invalid", share = true)
        awaitText("افزودن لینک دریافتی؟")
        clickText("انصراف")
        SystemClock.sleep(300)
        assertFalse("Canceled share was imported", hasHost("share-second.invalid"))
        startImport(android.content.Intent.ACTION_SEND, "share-second.invalid", share = true)
        awaitText("افزودن لینک دریافتی؟")
        clickText("وارد کردن")
        awaitHost("share-second.invalid")
    }
}
