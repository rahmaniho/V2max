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

            // The shell runs outside the VPN app's excluded UID, so this is a TUN datapath test.
            val command = "toybox timeout 20 toybox wget -q -O - http://198.51.100.1:18081/tun-check 2>&1"
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            val output = descriptor.use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }.trim()
            assertEquals("No traffic through Android TUN: $output\n${XrayCore.logs().joinToString("\n")}",
                "v2max-tun-ok", output)
        } finally {
            SmartConnect.cancelScan()
            TunnelController.stop(context)
        }
    }
}
