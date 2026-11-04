package ir.v2max.core.model

/**
 * The health and ranking arithmetic, in one place.
 *
 * These four functions decide which server the app connects to, so they are the
 * worst possible place for two implementations to drift. They had already
 * drifted in shape if not in value: [Measurement] and [Server] carried one copy,
 * and the app's persistence layer carried a second, written over flat fields
 * rather than a `Measurement`.
 *
 * They take plain numbers rather than a [Measurement] so a caller holding flat
 * fields can use them without constructing a `Measurement` — which would mean
 * supplying fields like `testedFragmentProfile` that none of this arithmetic
 * reads, and inventing values for them.
 *
 * The arithmetic is unchanged from both copies; see [score] for why each term is
 * there.
 */
object ServerHealth {

    /** How many of the three real destinations answered. 0..3. */
    fun reachCount(googlePing: Int, instagramPing: Int, youtubePing: Int): Int {
        var n = 0
        if (googlePing > 0) n++
        if (instagramPing > 0) n++
        if (youtubePing > 0) n++
        return n
    }

    /**
     * Reliability as a percentage, 0..100 (100 = no failures).
     *
     * A server never yet attempted returns 70 rather than 0 or 100: unknown is
     * deliberately middling, so an untried server is neither promoted above
     * known-good ones nor buried below known-bad ones.
     */
    fun stability(successCount: Int, failCount: Int): Int {
        val total = successCount + failCount
        if (total <= 0) return 70
        return ((successCount * 100) / total).coerceIn(0, 100)
    }

    /**
     * Best observed real latency in ms, falling back to a TCP handshake, or -1
     * when nothing has been measured.
     *
     * Real latency is preferred over TCP because a TCP connect only proves the
     * port is open, not that the proxy answers.
     */
    fun bestPing(
        tcpPing: Int,
        realPing: Int,
        googlePing: Int,
        instagramPing: Int,
        youtubePing: Int,
    ): Int {
        val candidates = listOf(googlePing, instagramPing, youtubePing, realPing).filter { it > 0 }
        if (candidates.isNotEmpty()) return candidates.min()
        return if (tcpPing > 0) tcpPing else -1
    }

    /**
     * Combined ranking score; **lower is better**.
     *
     *   latency  +  speed penalty  +  instability penalty
     *
     * An unmeasured server starts at 3500 so it sorts behind anything actually
     * timed. The speed term is exclusive with that baseline rather than added to
     * it: no measurement is a flat +150, while a measurement *earns back* up to
     * 140 — 10 points per 500 kbps. Instability costs twice its distance from
     * perfect. The floor of 1 keeps the score usable as a sort key and avoids a
     * meaningless 0 or negative.
     */
    fun score(bestPing: Int, speedKbps: Int, stability: Int): Int {
        val base = if (bestPing > 0) bestPing else 3500
        var score = base
        if (speedKbps <= 0) {
            score += 150
        } else {
            // every 500 kbps of measured speed removes 10 points, capped at 140
            score -= (speedKbps / 500).coerceAtMost(14) * 10
        }
        score += (100 - stability) * 2
        return score.coerceAtLeast(1)
    }
}
