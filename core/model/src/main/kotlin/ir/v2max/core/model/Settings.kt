package ir.v2max.core.model

/**
 * Application settings.
 *
 * Immutable replacement for the previous mutable `AppSettings`. Every field had
 * a `var`, so any layer could flip a setting while a connection was being
 * established and the config would be built from a half-updated snapshot.
 */
data class AppSettings(
    val routingMode: RoutingMode = RoutingMode.SMART,
    val autoConnectOnOpen: Boolean = true,
    val autoConnectOnBoot: Boolean = false,
    val smartFailover: Boolean = true,
    val autoUpdateSubs: Boolean = true,

    /** Add the built-in free public sources to the subscription list by default. */
    val useFreeSources: Boolean = true,

    /** On each launch, refresh subscriptions and pick the best server. */
    val autoRefreshOnOpen: Boolean = true,

    /** TLS fragmentation to get past DPI filtering. */
    val fragment: Boolean = true,
    val mux: Boolean = false,
    val muxConcurrency: Int = 8,
    val mtu: Int = 1500,
    val ipv6: Boolean = false,

    /** Probe Google, Instagram and YouTube concurrently before connecting. */
    val multiPing: Boolean = false,

    /** Deep carrier probe (DNS/DPI/latency/jitter) before every test. */
    val deepProbe: Boolean = true,

    /** Never connect before every test has finished. */
    val requireFullTest: Boolean = true,

    /** Auto-tune fragment strength from the detected carrier. */
    val autoTuneFragment: Boolean = true,

    val autoUpdateCheck: Boolean = true,

    /** Real download speed test for the top few servers. */
    val speedTest: Boolean = false,
    val speedTestCount: Int = 3,

    /** Remember which server worked on which carrier/network generation. */
    val rememberNetworks: Boolean = true,

    /** Skip the expensive tests and the speed test. */
    val dataSaver: Boolean = false,

    val wifiOnly: Boolean = false,

    /**
     * Server pool (balancer): several top servers go into the config at once and
     * the core picks the healthiest via leastPing, failing over without a
     * visible drop.
     */
    val balancer: Boolean = false,
    val balancerSize: Int = 3,

    /** Reduce background work while the screen is off. */
    val batterySaver: Boolean = true,

    /** Keep geoip/geosite up to date for accurate domestic routing. */
    val geoAssets: Boolean = true,

    /** Daily data cap in MB; 0 means unlimited. */
    val dailyLimitMb: Int = 0,

    /** Keep the device offline if the tunnel drops unexpectedly. */
    val killSwitch: Boolean = false,

    val appLock: Boolean = false,
    val hideNotificationContent: Boolean = false,
    val showFlags: Boolean = true,
    val dropDeadServers: Boolean = true,

    val fragmentProfile: FragmentProfile = FragmentProfile.BALANCED,

    val remoteDns: String = "https://1.1.1.1/dns-query",
    val domesticDns: String = "178.22.122.100",
    val vpnDns: String = "1.1.1.1",
    val delayTestUrl: String = "https://www.gstatic.com/generate_204",

    val showSpeedInNotification: Boolean = true,
    val dynamicColor: Boolean = false,

    /**
     * User-defined routing rules, one per line as `action|type|value`:
     *   direct|domain|example.com
     *   proxy|ip|8.8.8.8/32
     *   block|domain|ads.example.org
     * Values may be comma-separated.
     */
    val customRoutingRules: List<String> = emptyList(),

    val perAppMode: PerAppMode = PerAppMode.OFF,
    val perAppPackages: List<String> = emptyList(),
)

/**
 * Resolved geo-asset tags.
 *
 * The previous implementation asked a filesystem-backed `GeoAssets` singleton
 * which `.dat` files were present, making config generation untestable without
 * touching disk. The platform layer resolves availability and injects the tags;
 * a null tag means "not available, emit the built-in fallback rules only".
 */
data class GeoAssets(val geositeTag: String? = null, val geoipTag: String? = null) {
    companion object {
        val NONE = GeoAssets()
    }
}
