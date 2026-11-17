package ir.v2max.app.net

import ir.v2max.core.domain.ServerDiagnostics
import ir.v2max.core.domain.ServerProbeCounts
import ir.v2max.core.domain.ServerProbeEvidence

/** Transient aggregate for one group/selection scan; never used for connection selection. */
internal class ScanDiagnostics(private val candidates: Int) {
    private val evidence = HashMap<String, ServerProbeEvidence>()

    /** Called only after the scanner has completed all requested tests. */
    fun onReal(id: String, result: ProbeResult?) {
        evidence[id] = when {
            result == null -> ServerProbeEvidence.NO_RESPONSE
            result.delayMs > 0 && result.url.isNotBlank() -> ServerProbeEvidence.RESPONDED
            else -> ServerProbeEvidence.UNKNOWN
        }
    }

    fun onIndeterminate(id: String) {
        evidence[id] = ServerProbeEvidence.UNKNOWN
    }

    /** An interrupted/unpublished scan must never be presented as completed. */
    fun complete(tested: Int): ServerProbeCounts? =
        if (tested > 0 && tested <= candidates && evidence.size == tested)
            ServerDiagnostics.summarize(candidates, evidence.values)
        else null
}
