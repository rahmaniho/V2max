package ir.v2max.core.model

/**
 * Supported wire protocols.
 *
 * The old model stored the protocol *and* its transport/security as free-form
 * strings, so `network = "grcp"` or `security = "reality"` without a public key
 * compiled fine and only failed at runtime inside the Xray core. Transport and
 * security are sealed hierarchies here, so those states are unrepresentable.
 */
enum class Proto(val label: String, val defaultPort: Int) {
    VLESS("VLESS", 443),
    VMESS("VMess", 443),
    TROJAN("Trojan", 443),
    SS("Shadowsocks", 443),
    SOCKS("Socks", 1080),
    HTTP("HTTP", 80),
    HYSTERIA2("Hysteria2", 443);

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

/** How traffic is split between the tunnel and the local network. */
enum class RoutingMode {
    /** Iranian/domestic traffic goes direct, everything else through the tunnel. */
    SMART,

    /** Everything through the tunnel. */
    GLOBAL,
}

/** Per-application proxying policy. */
enum class PerAppMode { OFF, ONLY_SELECTED, EXCEPT_SELECTED }

/**
 * A network endpoint.
 *
 * Validated at construction time by [Endpoint.of]; the raw [HostPort.parse]
 * helper keeps the lenient behaviour needed while parsing untrusted links.
 */
data class Endpoint(val address: String, val port: Int) {

    /** True when the endpoint can carry real traffic (not loopback/unspecified). */
    val usable: Boolean
        get() {
            val a = address.trim().lowercase()
            if (a.isEmpty() || port !in 1..65535) return false
            if (a in LOCAL_LITERALS) return false
            if (a.startsWith("127.")) return false
            return true
        }

    /** `[addr]:port` form, bracketing IPv6 literals. */
    val display: String
        get() = if (address.contains(':')) "[$address]:$port" else "$address:$port"

    val hostPort: String get() = if (address.contains(':')) "[$address]" else "$address:$port"

    companion object {
        private val LOCAL_LITERALS = setOf("localhost", "0.0.0.0", "::", "::1", "[::1]")

        /** Parses `host:port`, `[ipv6]:port` and bare `host` (using [fallbackPort]). */
        fun parse(hostPort: String, fallbackPort: Int): Endpoint? {
            val s = hostPort.trim().substringBefore("/") // strip a trailing "/" path
            if (s.isEmpty()) return null
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                val host = s.substring(1, end)
                val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: fallbackPort
                return if (host.isEmpty()) null else Endpoint(host, port)
            }
            val idx = s.lastIndexOf(':')
            return if (idx < 0) {
                Endpoint(s, fallbackPort)
            } else {
                val host = s.substring(0, idx)
                if (host.isEmpty()) return null
                Endpoint(host, s.substring(idx + 1).toIntOrNull() ?: fallbackPort)
            }
        }
    }
}

/**
 * Protocol credentials.
 *
 * One sealed family instead of the old `uuid`/`password`/`method` string trio,
 * where nothing stopped a Trojan config from carrying a `uuid` and no password.
 */
sealed interface Credential {
    /** VLESS / VMess. [flow] carries XTLS flow (e.g. `xtls-rprx-vision`), required by REALITY. */
    data class Uuid(val uuid: String, val method: String = "", val flow: String = "") : Credential

    /** Trojan / Hysteria2. [flow] carries XTLS flow for Trojan. */
    data class Password(val password: String, val flow: String = "") : Credential

    /** Socks / HTTP basic auth; [user] may be blank for anonymous proxies. */
    data class UserPass(val user: String, val password: String) : Credential

    /** Shadowsocks */
    data class Cipher(val method: String, val password: String) : Credential
}

/**
 * Transport layer.
 *
 * Replaces the stringly-typed `network`/`headerType`/`host`/`path`/`serviceName`/
 * `mode`/`authority`/`kcp*` field cluster. Each variant only carries the
 * parameters that are meaningful for it.
 */
sealed interface Transport {
    /** Xray streamSettings `network` value. */
    val wire: String

    data class Tcp(val headerType: String = "none", val host: String = "", val path: String = "") : Transport {
        override val wire = "tcp"
    }

    data class WebSocket(val host: String = "", val path: String = "/") : Transport {
        override val wire = "ws"
    }

    data class Grpc(val serviceName: String = "", val authority: String = "", val mode: String = "") : Transport {
        override val wire = "grpc"
    }

    data class HttpUpgrade(val host: String = "", val path: String = "") : Transport {
        override val wire = "httpupgrade"
    }

    data class XHttp(
        val host: String = "",
        val path: String = "",
        val mode: String = "",
        /** Embedded JSON object with extra XHTTP settings, verbatim from the link. */
        val extra: String = "",
    ) : Transport {
        override val wire = "xhttp"
    }

    data class Kcp(
        val headerType: String = "none",
        val seed: String = "",
        val mtu: Int = 1350,
        val tti: Int = 50,
    ) : Transport {
        override val wire = "kcp"
    }

    data class Hysteria(val host: String = "") : Transport {
        override val wire = "hysteria"
    }
}

/** TLS layer, including REALITY. */
sealed interface Security {
    data object None : Security

    data class Tls(
        val sni: String = "",
        val alpn: String = "",
        val fingerprint: String = "",
        val echConfigList: String = "",
        val verifyPeerCertByName: String = "",
        val pinnedPeerCertSha256: String = "",
        val mldsa65Verify: String = "",
        /**
         * Legacy "skip certificate verification". Current Xray cores reject it,
         * but it is still parsed out of share links and must be carried through
         * so the generated config matches what the user's link asked for. It is
         * suppressed when a certificate pin is present, which is stricter and
         * therefore the safer of the two.
         */
        val allowInsecure: Boolean = false,
    ) : Security

    data class Reality(
        val sni: String = "",
        val fingerprint: String = "",
        val publicKey: String = "",
        val shortId: String = "",
        val spiderX: String = "",
    ) : Security
}

/** Hysteria2 obfuscation (Salamander). */
data class Obfuscation(val type: String = "", val password: String = "") {
    val enabled: Boolean get() = type.isNotBlank() && password.isNotBlank()
}

/**
 * Iranian carrier / network family.
 *
 * Used to key the "familiar networks" memory and to pick a fragment profile,
 * since DPI behaviour differs sharply between operators. Detection matches on
 * the MCC-MNC pair first because it is unambiguous, and falls back to the
 * operator name, which carriers are free to localise.
 */
enum class CarrierFamily(val label: String) {
    MCI("همراه اول"),
    IRANCELL("ایرانسل"),
    RIGHTEL("رایتل"),
    TCI("مخابرات"),
    WIFI("وای‌فای"),
    OTHER("نامشخص");

    companion object {
        fun detect(mccMnc: String, operatorName: String, wifi: Boolean): CarrierFamily {
            if (wifi) return WIFI
            val name = operatorName.lowercase()
            return when {
                mccMnc == "43211" || name.contains("mci") || operatorName.contains("همراه") -> MCI
                mccMnc == "43235" || name.contains("irancell") || name.contains("mtn") -> IRANCELL
                mccMnc == "43220" || name.contains("rightel") || name.contains("رایتل") -> RIGHTEL
                mccMnc == "43214" || name.contains("tci") || operatorName.contains("مخابرات") -> TCI
                else -> OTHER
            }
        }
    }
}

/**
 * TLS fragmentation profile for evading DPI.
 *
 * Values mirror the upstream gfw_resist_tls_proxy tuning that the previous
 * implementation hard-coded, so operator-specific presets behave identically.
 */
enum class FragmentProfile(
    val label: String,
    val packets: String,
    val length: String,
    val delay: String,
    val maxSplit: String,
) {
    LIGHT("سبک (ایرانسل)", "tlshello", "20-60", "2-6", "40"),
    BALANCED("متعادل", "tlshello", "50-100", "10-20", "80"),
    AGGRESSIVE("قوی (همراه اول)", "tlshello", "80-200", "15-30", "200"),
    MAHSA("مهسا (MahsaNG)", "tlshello", "100-200", "10-20", "200");

    companion object {
        fun fromName(name: String?): FragmentProfile =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
