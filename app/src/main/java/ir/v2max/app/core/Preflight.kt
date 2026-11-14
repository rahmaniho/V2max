package ir.v2max.app.core

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.PerAppMode
import ir.v2max.app.data.Proto
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only settings that affect pre-connect tests or the tested outbound. */
internal data class PreflightPolicy(
    val delayTestUrl: String,
    val multiPing: Boolean,
    val speedTest: Boolean,
    val speedTestCount: Int,
    val deepProbe: Boolean,
    val autoTuneFragment: Boolean,
    val fragment: Boolean,
    val fragmentProfile: FragmentProfile,
    val mux: Boolean,
    val muxConcurrency: Int,
    val balancer: Boolean,
    val balancerSize: Int
) {
    companion object {
        fun from(settings: AppSettings) = PreflightPolicy(
            delayTestUrl = settings.delayTestUrl.trim(),
            multiPing = settings.multiPing,
            speedTest = settings.speedTest,
            speedTestCount = if (settings.speedTest) settings.speedTestCount.coerceIn(1, 5) else 0,
            deepProbe = settings.deepProbe,
            autoTuneFragment = settings.autoTuneFragment,
            fragment = settings.fragment,
            fragmentProfile = settings.fragmentProfile,
            mux = settings.mux,
            muxConcurrency = if (settings.mux) settings.muxConcurrency else 0,
            balancer = settings.balancer,
            balancerSize = if (settings.balancer) settings.balancerSize else 0
        )
    }
}

/** Settings that determine a running VPN, excluding UI, refresh and test-only preferences. */
internal data class AppliedTunnelPolicy(
    val routingMode: RoutingMode,
    val mtu: Int,
    val ipv6: Boolean,
    val vpnDns: String,
    val remoteDns: String,
    val domesticDns: String,
    val geoAssets: Boolean,
    val customRoutingRules: List<String>,
    val perAppMode: PerAppMode,
    val perAppPackages: List<String>,
    val fragment: Boolean,
    val fragmentProfile: FragmentProfile?,
    val mux: Boolean,
    val muxConcurrency: Int,
    val balancer: Boolean,
    val balancerSize: Int,
    val balancerProbeUrl: String
) {
    companion object {
        fun from(server: ServerConfig, settings: AppSettings) = AppliedTunnelPolicy(
            routingMode = settings.routingMode,
            mtu = settings.mtu.coerceIn(1280, 9000),
            ipv6 = settings.ipv6,
            vpnDns = settings.vpnDns,
            remoteDns = settings.remoteDns,
            domesticDns = if (settings.routingMode == RoutingMode.SMART) settings.domesticDns else "",
            geoAssets = settings.routingMode == RoutingMode.SMART && settings.geoAssets,
            customRoutingRules = settings.customRoutingRules.toList(),
            perAppMode = settings.perAppMode,
            perAppPackages = if (settings.perAppMode == PerAppMode.OFF) emptyList()
                else settings.perAppPackages.distinct().sorted(),
            fragment = server.testedFragment,
            fragmentProfile = server.testedFragmentProfile.takeIf { server.testedFragment },
            mux = settings.mux && server.proto in listOf(Proto.VMESS, Proto.VLESS),
            muxConcurrency = if (settings.mux && server.proto in listOf(Proto.VMESS, Proto.VLESS)) {
                if (server.flow.isNotBlank()) -1 else settings.muxConcurrency.coerceIn(1, 128)
            } else 0,
            balancer = settings.balancer,
            balancerSize = if (settings.balancer) settings.balancerSize.coerceIn(1, 6) else 0,
            balancerProbeUrl = if (settings.balancer) settings.delayTestUrl.trim() else ""
        )
    }
}

/**
 * An in-process, short-lived receipt created only after the enabled tests finish.
 * It carries copies of the exact tested profiles; persisted ping fields are not
 * permission to start a tunnel. No credentials or receipts are written to intents.
 */
internal class VerifiedSelection private constructor(
    private val selected: ServerConfig,
    private val candidates: List<ServerConfig>,
    private val policy: PreflightPolicy,
    private val networkHandle: Long?,
    internal val completedNanos: Long
) {
    val id: String get() = selected.id
    fun server(): ServerConfig = selected.copy()
    fun pool(): List<ServerConfig> = candidates.map { it.copy() }

    fun validFor(
        current: ServerConfig?,
        settings: AppSettings,
        network: Long?,
        nowNanos: Long = System.nanoTime()
    ): Boolean = current != null &&
        current.id == selected.id && current.fingerprint() == selected.fingerprint() &&
        policy == PreflightPolicy.from(settings) && networkHandle != null && networkHandle == network &&
        nowNanos - completedNanos in 0..MAX_AGE_NANOS &&
        selected.realPing > 0 && selected.testedUrl.isNotBlank()

    companion object {
        internal val MAX_AGE_NANOS = TimeUnit.MINUTES.toNanos(2)

        fun create(
            selected: ServerConfig,
            candidates: List<ServerConfig>,
            settings: AppSettings,
            networkHandle: Long?,
            completedNanos: Long = System.nanoTime()
        ): VerifiedSelection = VerifiedSelection(
            selected.copy(), candidates.map { it.copy() }, PreflightPolicy.from(settings), networkHandle, completedNanos
        )
    }
}

/** One-shot hand-off to the service in the same process; never persisted. */
internal object PreflightTickets {
    private const val MAX_TICKETS = 32
    private val pending = LinkedHashMap<String, VerifiedSelection>()

    @Synchronized
    fun issue(selection: VerifiedSelection): String {
        val now = System.nanoTime()
        pending.entries.removeAll { now - it.value.completedNanos !in 0..VerifiedSelection.MAX_AGE_NANOS }
        while (pending.size >= MAX_TICKETS) pending.remove(pending.keys.first())
        val token = UUID.randomUUID().toString()
        pending[token] = selection
        return token
    }

    @Synchronized
    fun take(token: String?): VerifiedSelection? = if (token.isNullOrBlank()) null else pending.remove(token)

    @Synchronized
    fun revokeAll() = pending.clear()
}
