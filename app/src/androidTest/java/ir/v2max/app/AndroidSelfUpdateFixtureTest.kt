package ir.v2max.app

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.app.data.Repo
import ir.v2max.app.net.UpdateInstaller
import ir.v2max.app.service.InstallConfirmation
import ir.v2max.app.service.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * A shell-driven CI fixture. The stage test intentionally leaves the system
 * session and its notification alive so a disposable emulator can confirm an
 * update after this instrumentation process has exited.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSelfUpdateFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val prefs by lazy { context.getSharedPreferences("self_update_test_fixture", Context.MODE_PRIVATE) }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
        }

    private fun installTime(): Long = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    @Test fun stageForExternalConfirmation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stageSelfUpdateFixture") == "true")
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        assertTrue("This fixture requires the debug application", context.packageName.endsWith(".debug"))
        assertTrue("Install-source permission was not granted", UpdateInstaller.canRequestInstalls(context))
        Repo.init(context)
        Repo.updateSettings {
            it.copy(appLock = false, autoConnectOnOpen = false, autoRefreshOnOpen = false,
                autoUpdateSubs = false, autoUpdateCheck = false, useFreeSources = false, geoAssets = false)
        }
        assertTrue("Could not persist the disposable marker", prefs.edit()
            .putString("marker", "v2max-self-update-fixture")
            .putLong("previous_install_time", installTime()).commit())
        val apk = File(context.applicationInfo.sourceDir)
        assertTrue("The installed debug APK is missing", apk.isFile)
        assertTrue("Staging the signed debug APK failed", UpdateInstaller.install(context, apk))
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline && InstallConfirmation.pending.value == null) SystemClock.sleep(100)
        val request = requireNotNull(InstallConfirmation.pending.value) {
            "Android did not request confirmation: ${Repo.installMessage.value}"
        }
        assertTrue("Missing session id", request.sessionId >= 0)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notificationDeadline = SystemClock.elapsedRealtime() + 5_000
        fun posted() = manager.activeNotifications.any {
            it.id == Notifier.INSTALL_NOTIFICATION_ID && it.tag == Notifier.installNotificationTag(request.sessionId)
        }
        while (SystemClock.elapsedRealtime() < notificationDeadline && !posted()) SystemClock.sleep(50)
        assertTrue("The installer notification was not posted", posted())
        assertTrue(prefs.edit().putInt("session_id", request.sessionId).commit())
    }

    @Test fun verifyAfterReplacement() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySelfUpdateFixture") == "true")
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        assertEquals("v2max-self-update-fixture", prefs.getString("marker", ""))
        val previous = prefs.getLong("previous_install_time", -1)
        assertTrue("Missing previous installation timestamp", previous > 0)
        assertTrue("Package was not replaced", installTime() > previous)
        assertTrue("Installed APK lost the expected signature", UpdateInstaller.sameSignature(context, File(context.applicationInfo.sourceDir)))
        assertTrue("Could not clean up the disposable marker", prefs.edit().clear().commit())
    }
}
