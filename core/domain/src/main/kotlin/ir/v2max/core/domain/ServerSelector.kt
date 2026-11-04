package ir.v2max.core.domain

import ir.v2max.core.model.CarrierFamily
import ir.v2max.core.model.Server

/**
 * What the app should do once a server has been chosen.
 *
 * Separated from *which* server was chosen because the two decisions have
 * different failure modes: picking the wrong server is a performance problem,
 * while switching away from a working connection is a visible outage.
 */
enum class SelectionAction {
    /** Nothing to connect to. */
    NONE,

    /** Record the choice without touching the tunnel (it is already this server). */
    USE_SELECTION,

    /**
     * Adopt the chosen server as the selection.
     *
     * The caller reconnects only if it requested autoConnect; this action does
     * not by itself promise that a tunnel will be established.
     */
    SWITCH,

    /** The current connection is good enough; do not interrupt it. */
    KEEP_CURRENT,
}

data class Selection(val server: Server?, val action: SelectionAction, val reason: String) {
    companion object {
        fun none(reason: String) = Selection(null, SelectionAction.NONE, reason)
    }
}

/**
 * Server selection heuristics.
 *
 * Extracted from the previous `SmartConnect`, where these rules were buried
 * inside a suspend function that also held a mutex, published UI state and
 * started the tunnel — which made them impossible to test. The tolerances here
 * are the same magic numbers as before, and are named because they are tuned
 * values rather than arbitrary ones.
 */
object ServerSelector {

    /**
     * A remembered server for this network is preferred over the global best
     * when it is within this many score points of it. Choosing a known-good
     * server avoids churn between two effectively equivalent options.
     */
    const val REMEMBERED_TOLERANCE = 60

    /**
     * An already-connected server is kept when its score is within this factor
     * of the new best. Switching tunnels drops packets, so a modest improvement
     * is not worth an interruption.
     */
    const val KEEP_CURRENT_FACTOR = 1.35f

    /**
     * Orders servers for testing, best candidates first.
     *
     * Priority: an explicitly requested server, then favourites, then servers
     * that have previously passed a real test, then lowest measured latency.
     * Previously-untested servers sort last rather than first, so a scan with a
     * limit spends its budget on servers with a track record.
     */
    fun prioritize(
        servers: List<Server>,
        limit: Int = 0,
        preferredId: String? = null,
    ): List<Server> {
        if (limit <= 0 || servers.size <= limit) return servers
        return servers.sortedWith(
            compareByDescending<Server> { it.id == preferredId }
                .thenByDescending { it.favorite }
                .thenByDescending { it.measurement.realPing > 0 }
                .thenBy { if (it.bestPing() > 0) it.bestPing() else Int.MAX_VALUE }
        ).take(limit)
    }

    /**
     * Picks between the global best and the server remembered for this network.
     *
     * A remembered server only wins if it actually answered in this scan
     * (`realPing > 0`); a stale memory of a dead server must not override a
     * working one.
     */
    fun resolve(best: Server?, remembered: Server?): Server? = when {
        best == null -> null
        remembered != null &&
            remembered.measurement.realPing > 0 &&
            remembered.score() <= best.score() + REMEMBERED_TOLERANCE -> remembered

        else -> best
    }

    /**
     * Decides whether to act on [chosen].
     *
     * @param current the server the tunnel is connected to right now, if any
     * @param connected whether the tunnel is actually up
     * @param autoConnect whether the caller intends to connect at all
     * @param preferredId set when the user explicitly picked a server; an
     *   explicit choice always wins over the keep-current heuristic, because
     *   the user is asking for that specific server.
     */
    fun decide(
        chosen: Server?,
        current: Server?,
        connected: Boolean,
        autoConnect: Boolean,
        preferredId: String? = null,
    ): Selection {
        if (chosen == null) return Selection.none("هیچ سرور پاسخ‌گویی پیدا نشد")

        val sameAsRunning = connected && current?.id == chosen.id
        if (sameAsRunning) {
            return Selection(chosen, SelectionAction.USE_SELECTION, "همان سرور فعلی")
        }

        val keepCurrent = preferredId == null &&
            autoConnect &&
            connected &&
            current != null &&
            current.bestPing() > 0 &&
            current.score() <= chosen.score() * KEEP_CURRENT_FACTOR

        return if (keepCurrent) {
            Selection(chosen, SelectionAction.KEEP_CURRENT, "اتصال فعلی تقریباً هم‌سطح بهترین است")
        } else {
            Selection(chosen, SelectionAction.SWITCH, "اتصال به سرور تأییدشده")
        }
    }
}

/**
 * Key for the "familiar networks" memory: one entry per carrier and network
 * generation, holding the server and settings that actually worked there.
 */
object NetworkKey {
    /** Blank when the network has never been assessed. */
    fun of(carrier: CarrierFamily, netType: String, assessed: Boolean): String {
        if (!assessed) return ""
        return carrier.name + "|" + netType.ifBlank { "net" }
    }
}
