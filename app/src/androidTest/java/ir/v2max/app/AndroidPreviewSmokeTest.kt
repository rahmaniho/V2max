package ir.v2max.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.app.core.SmartConnect
import ir.v2max.app.core.TunnelController
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.Proto
import ir.v2max.app.data.Repo
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.service.V2maxVpnService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** Runs only against the separately installed preview APK on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class AndroidPreviewSmokeTest {
    @Suppress("DEPRECATION")
    @Test fun previewCoexistsWithOfficialAppAndCarriesTraffic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Only the preview variant is the target of this test", context.packageName.endsWith(".preview"))
        assertEquals("ir.v2max.app.preview", context.packageName)
        assertTrue("The preview package must not be debuggable",
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        assertTrue("The preview version must be visibly distinct", BuildConfig.VERSION_NAME.endsWith("-preview"))

        // The official v2.3.1 APK is installed before the preview on this emulator.
        val pm = context.packageManager
        val official = pm.getPackageInfo("ir.v2max.app", PackageManager.GET_SIGNATURES)
        val preview = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val officialSigners = official.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val previewSigners = preview.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        assertTrue("Could not read both package signers", officialSigners.isNotEmpty() && previewSigners.isNotEmpty())
        assertNotEquals("Preview must not use the production signer", officialSigners, previewSigners)

        fun probe(): String = instrumentation.uiAutomation
            .executeShellCommand("sh /data/local/tmp/v2max-tun-probe.sh")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }

        Repo.init(context)
        Repo.updateSettings {
            it.copy(
                autoConnectOnOpen = false, autoRefreshOnOpen = false, autoUpdateSubs = false, autoUpdateCheck = false,
                appLock = false, allowExternalAutomation = false, useFreeSources = false, deepProbe = false,
                fragment = false, balancer = false, geoAssets = false, multiPing = false, speedTest = false,
                smartFailover = false, killSwitch = false, routingMode = RoutingMode.GLOBAL,
                delayTestUrl = "http://198.51.100.1:18081/probe"
            )
        }
        val server = ServerConfig(
            name = "Preview local Xray fixture", proto = Proto.VLESS, address = "10.0.2.2", port = 18080,
            uuid = "11111111-2222-3333-4444-555555555555", method = "none", network = "tcp", security = "none"
        )
        Repo.removeServers(Repo.servers.value.map { it.id })
        Repo.addServers(listOf(server))
        Repo.setSelected(server.id)
        assertNull("The emulator must grant preview VPN permission", VpnService.prepare(context))
        val before = probe()
        assertTrue("The TUN-only fixture was reachable before connecting", !before.contains("v2max-tun-ok"))

        try {
            assertTrue("Preview pre-connect test failed: ${Repo.scan.value.message}",
                runBlocking { SmartConnect.connectVerified(context, server.id) })
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < deadline &&
                Repo.tunnel.value.status != TunnelStatus.CONNECTED && Repo.tunnel.value.status != TunnelStatus.ERROR) {
                SystemClock.sleep(100)
            }
            assertEquals("Preview VPN did not connect: ${Repo.tunnel.value.message}",
                TunnelStatus.CONNECTED, Repo.tunnel.value.status)

            val trafficDeadline = SystemClock.elapsedRealtime() + 15_000
            var output: String
            do {
                output = probe()
                if (output.contains(" 200 ") && output.contains("v2max-tun-ok") && output.contains("NC_EXIT=0")) break
                if (SystemClock.elapsedRealtime() < trafficDeadline) SystemClock.sleep(250)
            } while (SystemClock.elapsedRealtime() < trafficDeadline)
            val uid = output.lineSequence().firstOrNull { it.startsWith("PROBE_UID=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
            assertTrue("The traffic probe did not run outside the VPN app", uid != null && uid != android.os.Process.myUid())
            assertTrue("Preview did not carry traffic through Android TUN: $output",
                output.contains(" 200 ") && output.contains("v2max-tun-ok") && output.contains("NC_EXIT=0"))
            assertTrue("Preview core stopped while carrying traffic", V2maxVpnService.running && XrayCore.isRunning)
        } finally {
            TunnelController.stop(context)
        }
    }
}
