package ir.v2max.core.model

import java.util.UUID

/**
 * One proxy server.
 *
 * Immutable replacement for the previous `ServerConfig`, which exposed ~40
 * mutable `var` fields and let any caller mutate a server mid-flight —
 * including while a test batch was scoring it. State changes now go through
 * [copy], and the volatile measurement fields live in a separate [Measurement]
 * value so a server's *identity* and its *last observed health* can't drift
 * apart.
 */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val proto: Proto = Proto.VLESS,
    val endpoint: Endpoint = Endpoint("", 443),
    val credential: Credential = Credential.Uuid(""),
    val transport: Transport = Transport.Tcp(),
    val security: Security = Security.None,
    val obfuscation: Obfuscation = Obfuscation(),
    /**
     * Explicit streamSettings `finalmask` supplied by the share link.
     *
     * Deliberately not part of [Transport]: it applies to any transport, and
     * when present it *suppresses* generated TLS fragmentation, because an
     * explicit mask from the link author takes precedence over a guessed one.
     */
    val finalMask: String = "",
    val subscriptionId: String = "",
    val favorite: Boolean = false,
    val addedAt: Long = 0L,
    val measurement: Measurement = Measurement(),
    val country: Country? = null,
) {

    /** Effective SNI: explicit value, else the transport host, else the address. */
    val effectiveSni: String
        get() {
            val explicit = when (val s = security) {
                is Security.Tls -> s.sni
                is Security.Reality -> s.sni
                Security.None -> ""
            }
            if (explicit.isNotBlank()) return explicit
            val fromTransport = when (val t = transport) {
                is Transport.Grpc -> t.authority.ifBlank { serviceNameOr(t.serviceName) }
                is Transport.Tcp -> t.host.substringBefore(',').trim()
                is Transport.WebSocket -> t.host.substringBefore(',').trim()
                is Transport.HttpUpgrade -> t.host.substringBefore(',').trim()
                is Transport.XHttp -> t.host.substringBefore(',').trim()
                is Transport.Kcp -> ""
                is Transport.Hysteria -> t.host
            }
            return fromTransport.ifBlank { endpoint.address }
        }

    private fun serviceNameOr(v: String): String = v

    /** Number of probe destinations (0..3) that answered. */
    fun reachCount(): Int = measurement.reachCount()

    /**
     * Combined ranking score; **lower is better**.
     *
     * Byte-for-byte the same arithmetic as the previous implementation so that
     * server ordering does not silently change for existing users.
     */
    fun score(): Int {
        val base = bestPing().let { if (it > 0) it else 3500 }
        var score = base
        if (measurement.speedKbps <= 0) {
            score += 150
        } else {
            // every 500 kbps of measured speed removes 10 points, capped at 140
            score -= (measurement.speedKbps / 500).coerceAtMost(14) * 10
        }
        score += (100 - measurement.stability()) * 2
        return score.coerceAtLeast(1)
    }

    /** Best observed real latency in ms, or -1 when never measured. */
    fun bestPing(): Int = measurement.bestPing()

    val displayName: String get() = name.ifBlank { endpoint.display }

    /** The host to present as SNI/host when none was supplied. */
    fun proxyHost(): String = when (val t = transport) {
        is Transport.Tcp -> t.host.ifBlank { endpoint.address }
        is Transport.WebSocket -> t.host.ifBlank { endpoint.address }
        is Transport.HttpUpgrade -> t.host.ifBlank { endpoint.address }
        is Transport.XHttp -> t.host.ifBlank { endpoint.address }
        is Transport.Grpc -> t.authority.ifBlank { t.serviceName.ifBlank { endpoint.address } }
        is Transport.Kcp -> endpoint.address
        is Transport.Hysteria -> t.host.ifBlank { endpoint.address }
    }

    /**
     * Every field that affects connectivity.
     *
     * Used to drop duplicates without losing a config that differs only in a
     * parameter the core actually cares about. Excludes volatile measurements,
     * the display name and the id, so a subscription refresh can re-match a
     * server it already knew about.
     */
    fun dedupeKey(): String {
        val t = transport
        val s = security
        return listOf(
            proto.name,
            endpoint.address, endpoint.port.toString(),
            credentialKey(),
            t::class.simpleName.orEmpty(),
            when (t) {
                is Transport.Tcp -> "${t.headerType}|${t.host}|${t.path}"
                is Transport.WebSocket -> "${t.host}|${t.path}"
                is Transport.Grpc -> "${t.serviceName}|${t.authority}|${t.mode}"
                is Transport.HttpUpgrade -> "${t.host}|${t.path}"
                is Transport.XHttp -> "${t.host}|${t.path}|${t.mode}|${t.extra}"
                is Transport.Kcp -> "${t.headerType}|${t.seed}|${t.mtu}|${t.tti}"
                is Transport.Hysteria -> t.host
            },
            // Stream-level: applies to every transport, so it lives here rather
            // than inside one transport variant.
            finalMask,
            when (s) {
                Security.None -> "none"
                is Security.Tls -> "tls|${s.sni}|${s.alpn}|${s.fingerprint}|${s.echConfigList}|" +
                    "${s.verifyPeerCertByName}|${s.pinnedPeerCertSha256}|${s.mldsa65Verify}"
                is Security.Reality -> "reality|${s.sni}|${s.fingerprint}|${s.publicKey}|" +
                    "${s.shortId}|${s.spiderX}"
            },
            "${obfuscation.type}|${obfuscation.password}",
        ).joinToString("|")
    }

    private fun credentialKey(): String = when (val c = credential) {
        // flow is included: two REALITY servers differing only by XTLS flow are
        // genuinely different endpoints, not duplicates.
        is Credential.Uuid -> "uuid|${c.uuid}|${c.method}|${c.flow}"
        is Credential.Password -> "pass|${c.password}|${c.flow}"
        is Credential.UserPass -> "userpass|${c.user}|${c.password}"
        is Credential.Cipher -> "cipher|${c.method}|${c.password}"
    }

    /** Stable identity across subscription refreshes (independent of [id]). */
    fun fingerprint(): String = dedupeKey()
}

/** ISO-3166 country of a server, resolved for the flag column. */
data class Country(val code: String, val nameFa: String) {
    val hasFlag: Boolean get() = code.isNotBlank()
}

/**
 * Volatile health measurements for a [Server].
 *
 * Split out of the server identity so re-measuring never produces a different
 * dedupe key, and so a "same server, new numbers" update is a plain copy.
 */
data class Measurement(
    val tcpPing: Int = -1,
    val realPing: Int = -1,
    val googlePing: Int = -1,
    val instagramPing: Int = -1,
    val youtubePing: Int = -1,
    /** Measured download speed in kbps; 0 means not measured. */
    val speedKbps: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val testedAt: Long = 0L,
    /** Exact conditions of the last *successful* real test; TCP alone is not health. */
    val testedFragment: Boolean = false,
    val testedFragmentProfile: FragmentProfile = FragmentProfile.BALANCED,
    val testedUrl: String = "",
) {
    fun reachCount(): Int {
        var n = 0
        if (googlePing > 0) n++
        if (instagramPing > 0) n++
        if (youtubePing > 0) n++
        return n
    }

    /** Reliability as a percentage, 0..100 (100 = no failures). Unmeasured = 70. */
    fun stability(): Int {
        val total = successCount + failCount
        if (total <= 0) return 70
        return ((successCount * 100) / total).coerceIn(0, 100)
    }

    /** Best real latency, falling back to TCP, or -1 when nothing was measured. */
    fun bestPing(): Int {
        val candidates = listOf(googlePing, instagramPing, youtubePing, realPing).filter { it > 0 }
        if (candidates.isNotEmpty()) return candidates.min()
        return if (tcpPing > 0) tcpPing else -1
    }
}
