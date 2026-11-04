package ir.v2max.core.domain

/**
 * Decides *how* a server gets probed, and which probe results may be believed.
 *
 * Extracted from the app's `ProbePlanner`. The orchestration stayed behind —
 * `verify` needs `kotlinx.coroutines` for `ensureActive`, and core has no
 * dependencies — but the rules are what needed pinning, because each one
 * silently changes which servers are reachable.
 *
 * Callers pass primitives and accessors rather than app types, so the app can
 * keep its own `ServerConfig`/`AppSettings` while these rules are tested.
 */
object ProbePlanner {

    /** Used when the user set no test URL, or set one that cannot work. */
    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

    /** Second attempt, for when the preferred URL never answers. */
    const val FALLBACK_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    /** Transports that run over UDP, where a failed TCP handshake proves nothing. */
    private val UDP_TRANSPORTS = setOf("hysteria", "kcp", "mkcp", "quic")

    /**
     * Whether the server is reached over UDP.
     *
     * A TCP connect failure says nothing about these, so callers must not let a
     * failed TCP ping mark them dead — that is why [choose] ignores TCP results
     * entirely and demands a protocol-level success.
     *
     * [protoIsHysteria2] is passed as a flag because Hysteria2 is UDP by
     * protocol, whatever its `network` field says.
     */
    fun usesUdp(protoIsHysteria2: Boolean, network: String): Boolean =
        protoIsHysteria2 || network.lowercase() in UDP_TRANSPORTS

    /**
     * The URLs to try, best first.
     *
     * A user-set URL is only trusted if it has a scheme; anything else — a bare
     * host, a typo — falls back to the default rather than producing a probe
     * that can never succeed. The fallback is always appended, and `distinct`
     * keeps it from appearing twice when the user chose the fallback themselves.
     */
    fun testUrls(delayTestUrl: String): List<String> {
        val preferred = delayTestUrl.trim().ifBlank { DEFAULT_TEST_URL }
        val valid = preferred.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: DEFAULT_TEST_URL
        return listOf(valid, FALLBACK_URL).distinct()
    }

    /**
     * Whether a fragmented retry is worth attempting.
     *
     * Fragmentation only helps TLS-bearing traffic, so the security check is the
     * gate: `tls`, `reality`, or a Trojan server that left security empty. A
     * server already behind `finalMask` is fragmented by something else, and UDP
     * transports are not fragmentable at all.
     *
     * The security comparison is case-sensitive on purpose — that is how the app
     * has always written these values, and quietly accepting `TLS` would let a
     * malformed config trigger a retry loop it cannot benefit from.
     */
    fun canFragment(
        fragmentEnabled: Boolean,
        finalMaskBlank: Boolean,
        isUdp: Boolean,
        security: String,
        protoIsTrojan: Boolean,
    ): Boolean = fragmentEnabled && finalMaskBlank && !isUdp &&
        (security == "tls" || security == "reality" || (protoIsTrojan && security.isBlank()))

    /**
     * Picks the server to connect to from a set of freshly probed candidates.
     *
     * Only *protocol-level* successes qualify: a positive real ping **and** a
     * recorded test URL. A TCP-only result is not enough, because it proves the
     * port is open, not that the proxy works. The preferred server wins when it
     * qualified — the user's last working choice is worth keeping — otherwise
     * the lowest score, same ranking the rest of the app uses.
     *
     * Returns null when nothing qualified, which callers must treat as "no
     * route" rather than falling back to an untested server.
     */
    fun <T> choose(
        candidates: Collection<T>,
        preferredId: String? = null,
        idOf: (T) -> String,
        realPingOf: (T) -> Int,
        testedUrlOf: (T) -> String,
        scoreOf: (T) -> Int,
    ): T? {
        val successful = candidates.filter { realPingOf(it) > 0 && testedUrlOf(it).isNotBlank() }
        return successful.firstOrNull { idOf(it) == preferredId }
            ?: successful.minByOrNull { scoreOf(it) }
    }
}
