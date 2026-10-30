package ir.v2max.app

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
import kotlinx.coroutines.runBlocking
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

            // UiAutomation runs the command outside the app UID. It uses Runtime.exec,
            // not a shell, so the CI runner installs a script for the piped HTTP request.
            val descriptor = instrumentation.uiAutomation.executeShellCommand("sh /data/local/tmp/v2max-tun-probe.sh")
            val output = descriptor.use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }.trim()
            val probeUid = output.lineSequence().firstOrNull { it.startsWith("PROBE_UID=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
            assertTrue("Probe must run outside the VPN app UID: $output",
                probeUid != null && probeUid != android.os.Process.myUid())
            assertTrue("No traffic through Android TUN: $output\n${XrayCore.logs().joinToString("\n")}",
                output.contains(" 200 ") && output.contains("v2max-tun-ok") && output.contains("NC_EXIT=0"))
        } finally {
            SmartConnect.cancelScan()
            TunnelController.stop(context)
        }
    }
}
