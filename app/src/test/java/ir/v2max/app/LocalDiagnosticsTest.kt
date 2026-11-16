package ir.v2max.app

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.net.LocalDiagnostics
import ir.v2max.app.net.ProbeResult
import ir.v2max.app.net.ScanDiagnostics
import ir.v2max.core.domain.ConnectionAdvice
import ir.v2max.core.domain.PrivateErrorRecord
import ir.v2max.core.domain.ServerProbeCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiagnosticsTest {
    private val settings = AppSettings(geoAssets = true, routingMode = RoutingMode.SMART)
    private val secret = "vless://uuid@203.0.113.42:443?token=do-not-share#name"
    private val servers = listOf(
        ServerConfig(name = secret, address = "203.0.113.42", uuid = "uuid", tcpPing = 1),
        ServerConfig(name = secret, realPing = 20, testedAt = 42, testedUrl = "https://probe.example/secret"),
        ServerConfig(realPing = 10, testedAt = 0, testedUrl = "https://probe.example/secret"),
    )

    @Test fun `historical counts require real protocol evidence not tcp`() {
        val observed = LocalDiagnostics.observation(TunnelUiState(), settings, servers, geoReady = false)
        assertEquals(ConnectionAdvice.State.DISCONNECTED, observed.state)
        assertEquals(1, observed.recordedRealPings)
        assertEquals(3, observed.totalServers)
        assertTrue(observed.geoRequired)
        assertEquals(false, observed.geoReady)
        assertFalse(LocalDiagnostics.observation(TunnelUiState(), settings.copy(routingMode = RoutingMode.GLOBAL), servers, false).geoRequired)
    }

    @Test fun `every tunnel status maps to a distinct explicit state`() {
        val expected = mapOf(
            TunnelStatus.DISCONNECTED to ConnectionAdvice.State.DISCONNECTED,
            TunnelStatus.CONNECTING to ConnectionAdvice.State.CONNECTING,
            TunnelStatus.CONNECTED to ConnectionAdvice.State.CONNECTED,
            TunnelStatus.DISCONNECTING to ConnectionAdvice.State.DISCONNECTING,
            TunnelStatus.ERROR to ConnectionAdvice.State.ERROR,
        )
        for ((status, mapped) in expected) {
            assertEquals(mapped, LocalDiagnostics.observation(TunnelUiState(status = status), settings, servers, false).state)
        }
    }

    @Test fun `report never includes profile or raw error and does not treat running scan as complete`() {
        val report = LocalDiagnostics.report(
            appVersion = "2.4.0", coreVersion = "v26.10.21", androidApi = 35,
            tunnel = TunnelUiState(status = TunnelStatus.CONNECTED, serverName = secret, message = secret),
            settings = settings, servers = servers,
            scan = ScanState(running = true, finished = true, diagnosticCounts = ServerProbeCounts(1, 1, 1)),
            geoReady = false,
            localError = PrivateErrorRecord.classify(java.io.IOException(secret), secret),
        )
        assertFalse(report.contains(secret))
        assertFalse(report.contains("203.0.113.42"))
        assertFalse(report.contains("probe.example"))
        assertTrue(report.contains("آخرین اسکن انتخاب یا گروهی کامل: نامشخص"))
        assertTrue(report.contains("IP خروجی VPN: نامشخص"))
        assertTrue(report.contains("عبور ترافیک برنامه‌های دیگر از TUN: تأیید نشده"))
    }

    @Test fun `scan evidence rejects incomplete duplicate and malformed samples`() {
        val scan = ScanDiagnostics(4)
        scan.onReal("success", ProbeResult(30, "https://example.org/probe", false, FragmentProfile.BALANCED))
        scan.onReal("no-response", null)
        scan.onIndeterminate("unknown")
        assertNull(scan.complete(4)) // one candidate never published a result
        assertEquals(ServerProbeCounts(1, 1, 2), scan.complete(3))
        scan.onReal("success", ProbeResult(30, "", false, FragmentProfile.BALANCED))
        assertEquals(ServerProbeCounts(0, 1, 3), scan.complete(3))
        assertNull(scan.complete(5)) // inconsistent count, not a completed scan
    }
}
