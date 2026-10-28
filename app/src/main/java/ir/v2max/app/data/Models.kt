package ir.v2max.app.data

import java.util.UUID

/** پروتکل‌های پشتیبانی‌شده */
enum class Proto(val label: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SS("Shadowsocks"),
    SOCKS("Socks"),
    HTTP("HTTP"),
    HYSTERIA2("Hysteria2");

    companion object {
        fun fromScheme(scheme: String): Proto? = when (scheme.lowercase()) {
            "vless" -> VLESS
            "vmess" -> VMESS
            "trojan" -> TROJAN
            "ss" -> SS
            "socks", "socks5" -> SOCKS
            "http", "https" -> HTTP
            "hysteria2", "hy2" -> HYSTERIA2
            else -> null
        }
    }
}

/** حالت مسیریابی */
enum class RoutingMode {
    /** ترافیک داخلی (ایران) مستقیم، بقیه از تانل */
    SMART,

    /** همه ترافیک از تانل */
    GLOBAL
}

/** یک کانفیگ/سرور */
data class ServerConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var proto: Proto = Proto.VLESS,
    var address: String = "",
    var port: Int = 443,

    // اعتبارنامه‌ها
    var uuid: String = "",
    var password: String = "",
    var method: String = "",
    var flow: String = "",

    // انتقال
    var network: String = "tcp",
    var headerType: String = "",
    var host: String = "",
    var path: String = "",
    var serviceName: String = "",
    var mode: String = "",
    var authority: String = "",

    // امنیت
    var security: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fingerprint: String = "",
    var publicKey: String = "",
    var shortId: String = "",
    var spiderX: String = "",
    var allowInsecure: Boolean = false,

    // مخفی‌سازی اختیاری
    var obfsType: String = "",
    var obfsPassword: String = "",

    var subId: String = "",
    var favorite: Boolean = false,

    var tcpPing: Int = -1,
    var realPing: Int = -1,
    var testedAt: Long = 0L,
    var addedAt: Long = System.currentTimeMillis()
) {
    val endpoint: String get() = "$address:$port"

    fun bestPing(): Int = when {
        realPing > 0 -> realPing
        tcpPing > 0 -> tcpPing
        else -> -1
    }

    fun displayName(): String = name.ifBlank { endpoint }

    fun proxyHost(): String = host.ifBlank { address }

    fun effectiveSni(): String = sni.ifBlank {
        when (network) {
            "ws", "grpc", "h2", "http", "httpupgrade", "xhttp", "hysteria" -> host.ifBlank { address }
            else -> address
        }
    }

    /** کلید یکتاسازی برای حذف کانفیگ‌های تکراری */
    fun dedupeKey(): String = listOf(
        proto.name, address, port.toString(), uuid, password, method, network,
        host, path, serviceName, security, sni, publicKey, shortId, flow, obfsPassword
    ).joinToString("|")

    /** شناسه پایداری که مستقل از id است (برای تطبیق با کانفیگ قبلی پس از به‌روزرسانی اشتراک) */
    fun fingerprint(): String = dedupeKey()
}

/** اشتراک */
data class Subscription(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    var updatedAt: Long = 0L,
    var count: Int = 0,
    var upload: Long = 0L,
    var download: Long = 0L,
    var total: Long = 0L,
    var expireAt: Long = 0L,
    var lastError: String = ""
) {
    val usedBytes: Long get() = upload + download

    fun usedPercent(): Float = if (total <= 0L) 0f else (usedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** حالت پروکسی برنامه‌به‌برنامه */
enum class PerAppMode {
    /** همه‌ی برنامه‌ها از تانل عبور می‌کنند */
    OFF,

    /** فقط برنامه‌های انتخاب‌شده از تانل عبور می‌کنند */
    ONLY_SELECTED,

    /** همه به‌جز برنامه‌های انتخاب‌شده از تانل عبور می‌کنند */
    EXCEPT_SELECTED
}

/** بسته‌ی پشتیبان */
data class BackupPayload(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val servers: List<ServerConfig> = emptyList(),
    val subs: List<Subscription> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val selectedId: String = ""
)

/** تنظیمات برنامه */
data class AppSettings(
    var routingMode: RoutingMode = RoutingMode.SMART,
    var autoConnectOnOpen: Boolean = false,
    var autoConnectOnBoot: Boolean = false,
    var smartFailover: Boolean = true,
    var autoUpdateSubs: Boolean = true,

    /** تکه‌تکه‌سازی TLS برای عبور از فیلترینگ DPI */
    var fragment: Boolean = true,
    var mux: Boolean = false,
    var muxConcurrency: Int = 8,
    var mtu: Int = 1500,
    var ipv6: Boolean = false,

    var remoteDns: String = "https://1.1.1.1/dns-query",
    var domesticDns: String = "178.22.122.100",
    var vpnDns: String = "1.1.1.1",
    var delayTestUrl: String = "https://www.gstatic.com/generate_204",

    var showSpeedInNotification: Boolean = true,
    var dynamicColor: Boolean = false,

    // پروکسی برنامه‌به‌برنامه
    var perAppMode: PerAppMode = PerAppMode.OFF,
    var perAppPackages: List<String> = emptyList()
)

enum class TunnelStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

/** وضعیت زنده تانل برای رابط کاربری */
data class TunnelUiState(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val serverId: String = "",
    val serverName: String = "",
    val serverProto: String = "",
    val ping: Int = -1,
    val connectedAt: Long = 0L,
    val downSpeed: Long = 0L,
    val upSpeed: Long = 0L,
    val totalDown: Long = 0L,
    val totalUp: Long = 0L,
    val message: String = ""
) {
    val isActive: Boolean get() = status == TunnelStatus.CONNECTED || status == TunnelStatus.CONNECTING
}

/** وضعیت اسکن هوشمند */
data class ScanState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val alive: Int = 0,
    val phase: String = "",
    val bestName: String = "",
    val message: String = "",
    val finished: Boolean = false
) {
    val progress: Float get() = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
