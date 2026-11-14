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
     * Rank only fresh protocol successes. When current download samples are
     * supplied, candidates within 80% of the fastest sample form the first
     * group; latency/stability score breaks ties. Persisted speeds must not be
     * passed as current samples. With no samples, this is ordinary score order.
     */
    fun <T> rank(
        candidates: Collection<T>,
        realPingOf: (T) -> Int,
        testedUrlOf: (T) -> String,
        scoreOf: (T) -> Int,
        speedOf: (T) -> Int = { 0 },
    ): List<T> {
        val successful = candidates.filter { realPingOf(it) > 0 && testedUrlOf(it).isNotBlank() }
        val fastest = successful.mapNotNull { speedOf(it).takeIf { speed -> speed > 0 } }.maxOrNull() ?: 0
        return successful.sortedWith(
            compareBy<T> { candidate ->
                val speed = speedOf(candidate)
                when {
                    fastest <= 0 -> 0
                    speed > 0 && speed.toLong() * 10 >= fastest.toLong() * 8 -> 0
                    speed > 0 -> 1
                    else -> 2
                }
            }.thenBy(scoreOf).thenByDescending(speedOf)
        )
    }

    /**
     * The preferred candidate wins if it qualified; otherwise use the ranking.
     * A TCP-only result is not proof that a proxy works. Null means no route,
     * never permission to fall back to an untested profile.
     */
    fun <T> choose(
        candidates: Collection<T>,
        preferredId: String? = null,
        idOf: (T) -> String,
        realPingOf: (T) -> Int,
        testedUrlOf: (T) -> String,
        scoreOf: (T) -> Int,
        speedOf: (T) -> Int = { 0 },
    ): T? {
        val ranked = rank(candidates, realPingOf, testedUrlOf, scoreOf, speedOf)
        return ranked.firstOrNull { idOf(it) == preferredId } ?: ranked.firstOrNull()
    }
}
