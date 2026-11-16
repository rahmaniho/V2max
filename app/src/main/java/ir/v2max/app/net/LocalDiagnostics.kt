package ir.v2max.app.net

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.core.domain.ConnectionAdvice
import ir.v2max.core.domain.PrivateDiagnosticReport
import ir.v2max.core.domain.PrivateErrorRecord

/** Local-only snapshot. It performs no DNS lookups or HTTP requests. */
internal object LocalDiagnostics {
    fun observation(
        tunnel: TunnelUiState,
        settings: AppSettings,
        servers: List<ServerConfig>,
        geoReady: Boolean?,
    ): ConnectionAdvice.Observation = ConnectionAdvice.Observation(
        state = when (tunnel.status) {
            TunnelStatus.DISCONNECTED -> ConnectionAdvice.State.DISCONNECTED
            TunnelStatus.CONNECTING -> ConnectionAdvice.State.CONNECTING
            TunnelStatus.CONNECTED -> ConnectionAdvice.State.CONNECTED
            TunnelStatus.DISCONNECTING -> ConnectionAdvice.State.DISCONNECTING
            TunnelStatus.ERROR -> ConnectionAdvice.State.ERROR
        },
        killSwitchRetained = tunnel.killSwitchRetained,
        recordedRealPings = servers.count { it.recordedRealPing() > 0 },
        totalServers = servers.size,
        geoRequired = settings.geoAssets && settings.routingMode == RoutingMode.SMART,
        geoReady = geoReady,
    )

    fun report(
        appVersion: String,
        coreVersion: String,
        androidApi: Int,
        tunnel: TunnelUiState,
        settings: AppSettings,
        servers: List<ServerConfig>,
        scan: ScanState,
        geoReady: Boolean?,
        localError: PrivateErrorRecord.Summary?,
    ): String = PrivateDiagnosticReport.build(
        PrivateDiagnosticReport.Snapshot(
            appVersion = appVersion,
            coreVersion = coreVersion,
            androidApi = androidApi,
            observation = observation(tunnel, settings, servers, geoReady),
            scan = if (scan.finished && !scan.running) scan.diagnosticCounts else null,
            localError = localError,
        )
    )
}
