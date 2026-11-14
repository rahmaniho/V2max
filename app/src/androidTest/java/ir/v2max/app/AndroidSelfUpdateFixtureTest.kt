package ir.v2max.app

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.data.UpdateInfo
import ir.v2max.app.net.UpdateInstaller
import ir.v2max.app.service.InstallConfirmation
import ir.v2max.app.service.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * A shell-driven CI fixture. The stage test intentionally leaves the system
 * session and its notification alive so a disposable emulator can confirm an
 * upgrade after this instrumentation process has exited.
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

    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Test fun stageForExternalConfirmation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stageSelfUpdateFixture") == "true")
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        assertTrue("This fixture requires the debug application", context.packageName.endsWith(".debug"))
        assertTrue("Install-source permission was not granted", UpdateInstaller.canRequestInstalls(context))

        val previous = installed()
        val apk = File(requireNotNull(context.getExternalFilesDir(null)), "v2max-ci-upgrade.apk")
        assertTrue("The debug upgrade fixture is missing", apk.isFile)
        @Suppress("DEPRECATION")
        val next = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0))
        assertEquals(context.packageName, next.packageName)
        assertTrue("The fixture must have a higher version code", versionCode(next) > versionCode(previous))
        assertTrue("The fixture must have a different version name", next.versionName != previous.versionName)
        assertTrue("The upgrade fixture is not signed by the installed debug key", UpdateInstaller.sameSignature(context, apk))

        Repo.init(context)
        Repo.updateSettings {
            it.copy(appLock = false, autoConnectOnOpen = false, autoRefreshOnOpen = false,
                autoUpdateSubs = false, autoUpdateCheck = false, useFreeSources = false, geoAssets = false,
                multiPing = true, speedTest = true)
        }
        Repo.addSubscription(Subscription(id = "ci-source", name = "CI source", url = "https://example.invalid/sub"))
        Repo.updateServer(ServerConfig(id = "ci-server", name = "CI server", address = "example.invalid",
            uuid = "11111111-2222-3333-4444-555555555555", subId = "ci-source", favorite = true,
            network = "xhttp", path = "/fixture", xhttpExtra = "{\"xPaddingBytes\":\"100-200\"}"))
        Repo.setSelected("ci-server")
        Repo.saveUpdateInfo(UpdateInfo(version = "99.0.0"))
        Repo.markUpdateChecked()
        // Commit after the repository's apply() writes so the process can safely
        // be killed immediately when instrumentation finishes.
        assertTrue("Could not flush repository data", context.getSharedPreferences("v2max_store", Context.MODE_PRIVATE)
            .edit().commit())
        assertTrue("Could not persist the disposable marker", prefs.edit()
            .putString("marker", "v2max-self-update-fixture")
            .putLong("previous_install_time", previous.lastUpdateTime)
            .putLong("previous_code", versionCode(previous))
            .putLong("next_code", versionCode(next))
            .putString("next_name", next.versionName).commit())

        val staged = try { UpdateInstaller.install(context, apk) } finally { apk.delete() }
        assertTrue("Staging the signed debug upgrade failed", staged)
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
        assertTrue(context.packageName.endsWith(".debug"))
        assertEquals("v2max-self-update-fixture", prefs.getString("marker", ""))
        val previousTime = prefs.getLong("previous_install_time", -1)
        val previousCode = prefs.getLong("previous_code", -1)
        val nextCode = prefs.getLong("next_code", -1)
        assertTrue("Missing previous installation metadata", previousTime > 0 && previousCode > 0)
        val current = installed()
        assertTrue("Package was not replaced", current.lastUpdateTime > previousTime)
        assertTrue("The installed package is not a newer version", versionCode(current) > previousCode)
        assertEquals("The wrong version code was installed", nextCode, versionCode(current))
        assertEquals("The wrong version name was installed", prefs.getString("next_name", ""), current.versionName)
        assertTrue("Installed APK lost the expected signature", UpdateInstaller.sameSignature(context, File(context.applicationInfo.sourceDir)))

        Repo.init(context)
        val server = Repo.serverById("ci-server")
        assertNotNull("The selected server did not survive the upgrade", server)
        assertEquals("ci-server", Repo.selectedId.value)
        assertEquals("ci-source", server!!.subId)
        assertTrue(server.favorite)
        assertEquals("xhttp", server.network)
        assertEquals("/fixture", server.path)
        assertEquals("{\"xPaddingBytes\":\"100-200\"}", server.xhttpExtra)
        assertEquals("https://example.invalid/sub", Repo.subById("ci-source")?.url)
        assertTrue(Repo.settings.value.multiPing)
        assertTrue(Repo.settings.value.speedTest)
        assertFalse("Pending update from the old version should be cleared", Repo.update.value.hasUpdate)
        assertEquals("Update check must run again after a version change", 0L, Repo.lastUpdateCheck())
        assertTrue("Could not clean up the disposable marker", prefs.edit().clear().commit())
    }
}
