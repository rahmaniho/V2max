package ir.v2max.core.domain

/** Evidence from a completed Xray protocol probe, not from the TCP precheck. */
enum class ServerProbeEvidence { RESPONDED, NO_RESPONSE, UNKNOWN }

/** Snapshot of one scan, not proof of current server health or a connection ticket. */
data class ServerProbeCounts(val responded: Int, val noResponse: Int, val unknown: Int) {
    val total: Int get() = responded + noResponse + unknown
}

object ServerDiagnostics {
    /**
     * [evidence] contains at most one observation per candidate. Every candidate
     * not fully measured, including skipped and TCP-only candidates, is unknown.
     * Failed setup and unclassified native errors must be supplied as UNKNOWN.
     */
    fun summarize(total: Int, evidence: Collection<ServerProbeEvidence>): ServerProbeCounts {
        val size = total.coerceIn(0, 1_000_000)
        // Reject inconsistent data as unknown instead of inventing successes or failures.
        if (evidence.size > size) return ServerProbeCounts(0, 0, size)
        val responded = evidence.count { it == ServerProbeEvidence.RESPONDED }
        val noResponse = evidence.count { it == ServerProbeEvidence.NO_RESPONSE }
        return ServerProbeCounts(responded, noResponse, size - responded - noResponse)
    }
}
