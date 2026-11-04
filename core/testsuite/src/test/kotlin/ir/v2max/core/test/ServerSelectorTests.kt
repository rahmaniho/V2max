package ir.v2max.core.test

import ir.v2max.core.domain.NetworkKey
import ir.v2max.core.domain.SelectionAction
import ir.v2max.core.domain.ServerSelector
import ir.v2max.core.model.CarrierFamily
import ir.v2max.core.model.Endpoint
import ir.v2max.core.model.Measurement
import ir.v2max.core.model.Server

/**
 * Tests for the server-selection heuristics.
 *
 * These are the rules that decide whether a user gets a fast server, a dead
 * one, or a dropped connection — previously they were unreachable by tests
 * because they lived inside a suspend function that also owned a mutex and
 * started the tunnel.
 */
object ServerSelectorTests {

    private fun server(
        id: String,
        realPing: Int = -1,
        tcpPing: Int = -1,
        speedKbps: Int = 0,
        success: Int = 0,
        fail: Int = 0,
        favorite: Boolean = false,
    ) = Server(
        id = id,
        name = id,
        endpoint = Endpoint("$id.example.com", 443),
        favorite = favorite,
        measurement = Measurement(
            realPing = realPing, tcpPing = tcpPing, speedKbps = speedKbps,
            successCount = success, failCount = fail,
        ),
    )

    fun run() {
        T.group("ServerSelector")

        // ---- prioritize
        val servers = listOf(
            server("slow", realPing = 900),
            server("fast", realPing = 50),
            server("untested"),
            server("fav", realPing = 400, favorite = true),
            server("tcponly", tcpPing = 120),
        )

        T.eq("no limit returns all", 5, ServerSelector.prioritize(servers).size)
        T.eq("limit 0 returns all", 5, ServerSelector.prioritize(servers, 0).size)
        T.eq("limit above size returns all", 5, ServerSelector.prioritize(servers, 99).size)

        val top2 = ServerSelector.prioritize(servers, 2)
        T.eq("limit applied", 2, top2.size)
        T.eq("favourite ranks first", "fav", top2[0].id)
        T.eq("lowest latency next", "fast", top2[1].id)

        // An explicitly requested server outranks even a favourite.
        val preferred = ServerSelector.prioritize(servers, 1, preferredId = "slow")
        T.eq("preferred id wins over favourite", "slow", preferred[0].id)

        // Servers with a real test outrank TCP-only ones at equal limit.
        val top4 = ServerSelector.prioritize(servers, 4)
        T.ok("tcp-only ranks after real-tested", top4.map { it.id }.indexOf("tcponly") > top4.indexOf(top4.first { it.id == "fast" }))

        // Sorting only happens when the limit actually trims the list, matching
        // the original `if (limit > 0 && all.size > limit)` guard. With limit
        // equal to the size, the caller's order is preserved unchanged.
        val untrimmed = ServerSelector.prioritize(servers, 5)
        T.eq("untrimmed order preserved", "tcponly", untrimmed.last().id)

        // Full expected order: the favourite first, then real-tested servers by
        // ascending latency, then the TCP-only one. The never-tested server has
        // the weakest claim and is what gets dropped by the limit.
        val trimmed = ServerSelector.prioritize(servers, 4)
        T.eq(
            "trimmed order and eviction",
            listOf("fav", "fast", "slow", "tcponly"),
            trimmed.map { it.id },
        )

        // ---- resolve: remembered vs best
        // Note the tolerance is in *score points*, which are dominated by ping,
        // so a candidate must be within ~60ms of the best to qualify.
        val best = server("best", realPing = 100)
        val closeRemembered = server("remembered", realPing = 150)
        T.ok("best score is lower", best.score() < closeRemembered.score())
        T.ok(
            "close remembered is within tolerance",
            closeRemembered.score() <= best.score() + ServerSelector.REMEMBERED_TOLERANCE,
        )
        T.eq(
            "remembered wins within tolerance",
            "remembered",
            ServerSelector.resolve(best, closeRemembered)?.id,
        )

        // Beyond the tolerance the global best wins. Construct a remembered
        // server far worse than best.score() + 60.
        val farRemembered = server("far", realPing = 3000, success = 0, fail = 9)
        T.ok(
            "far remembered exceeds tolerance",
            farRemembered.score() > best.score() + ServerSelector.REMEMBERED_TOLERANCE,
        )
        T.eq("global best wins beyond tolerance", "best", ServerSelector.resolve(best, farRemembered)?.id)

        // A stale memory of a server that did not answer this scan must not win,
        // even if its old score looks good.
        val stale = server("stale", realPing = -1, tcpPing = 100)
        T.eq("stale memory ignored", "best", ServerSelector.resolve(best, stale)?.id)

        T.eq("null best yields null", null, ServerSelector.resolve(null, closeRemembered))
        T.eq("null remembered yields best", "best", ServerSelector.resolve(best, null)?.id)

        // ---- decide
        val chosen = server("chosen", realPing = 100)

        T.eq(
            "null chosen is NONE",
            SelectionAction.NONE,
            ServerSelector.decide(null, null, connected = false, autoConnect = true).action,
        )
        T.eq(
            "no current connection switches",
            SelectionAction.SWITCH,
            ServerSelector.decide(chosen, null, connected = false, autoConnect = true).action,
        )

        val same = server("chosen", realPing = 100)
        T.eq(
            "already connected to it is USE_SELECTION",
            SelectionAction.USE_SELECTION,
            ServerSelector.decide(chosen, same, connected = true, autoConnect = true).action,
        )

        // A current connection nearly as good is not interrupted.
        val currentGood = server("current", realPing = 110)
        T.eq(
            "comparable current connection is kept",
            SelectionAction.KEEP_CURRENT,
            ServerSelector.decide(chosen, currentGood, connected = true, autoConnect = true).action,
        )

        // A much worse current connection is replaced.
        val currentBad = server("current", realPing = 2000, success = 0, fail = 5)
        T.eq(
            "much worse current connection switches",
            SelectionAction.SWITCH,
            ServerSelector.decide(chosen, currentBad, connected = true, autoConnect = true).action,
        )

        // An explicit user choice overrides the keep-current heuristic.
        T.eq(
            "explicit preference always switches",
            SelectionAction.SWITCH,
            ServerSelector.decide(chosen, currentGood, connected = true, autoConnect = true, preferredId = "chosen").action,
        )

        // A current connection with no measurement at all has no claim.
        // (A TCP-only ping would still count, since bestPing() falls back to it.)
        val currentNoData = server("current")
        T.eq("bestPing is -1 with no data", -1, currentNoData.bestPing())
        T.eq(
            "unmeasured current connection switches",
            SelectionAction.SWITCH,
            ServerSelector.decide(chosen, currentNoData, connected = true, autoConnect = true).action,
        )

        // A TCP-only ping does count for keep-current, because bestPing() falls
        // back to tcpPing. That matches the original predicate; the README's
        // caveat that TCP alone never proves a working VPN applies to declaring
        // a server healthy, not to leaving an already-up tunnel alone.
        val currentTcpOnly = server("current", tcpPing = 100)
        T.eq("tcp-only bestPing falls back", 100, currentTcpOnly.bestPing())
        T.eq(
            "tcp-only current connection is kept",
            SelectionAction.KEEP_CURRENT,
            ServerSelector.decide(chosen, currentTcpOnly, connected = true, autoConnect = true).action,
        )

        // Not connected at all means nothing to keep.
        T.eq(
            "disconnected always switches",
            SelectionAction.SWITCH,
            ServerSelector.decide(chosen, currentGood, connected = false, autoConnect = true).action,
        )

        // keep-current requires autoConnect: with no intent to connect there is
        // nothing to protect, so the choice is still adopted. The caller only
        // actually reconnects when it asked to autoConnect.
        T.eq(
            "no autoConnect adopts selection",
            SelectionAction.SWITCH,
            ServerSelector.decide(chosen, currentGood, connected = true, autoConnect = false).action,
        )

        // The selection carries the server in every actionable case.
        T.eq(
            "selection carries server",
            "chosen",
            ServerSelector.decide(chosen, currentBad, connected = true, autoConnect = true).server?.id,
        )

        // ---- tolerance constants are the tuned originals
        T.eq("remembered tolerance is 60", 60, ServerSelector.REMEMBERED_TOLERANCE)
        T.eq("keep-current factor is 1.35", 1.35f, ServerSelector.KEEP_CURRENT_FACTOR)

        // ---- NetworkKey
        T.eq("unassessed yields blank", "", NetworkKey.of(CarrierFamily.MCI, "4G", assessed = false))
        T.eq("carrier and generation", "MCI|4G", NetworkKey.of(CarrierFamily.MCI, "4G", assessed = true))
        T.eq("blank generation falls back", "IRANCELL|net", NetworkKey.of(CarrierFamily.IRANCELL, "", assessed = true))
        T.eq("wifi keyed distinctly", "WIFI|net", NetworkKey.of(CarrierFamily.WIFI, "", assessed = true))

        // ---- carrier detection
        T.eq("wifi wins over mccMnc", CarrierFamily.WIFI, CarrierFamily.detect("43211", "MCI", wifi = true))
        T.eq("mccMnc 43211", CarrierFamily.MCI, CarrierFamily.detect("43211", "", wifi = false))
        T.eq("mccMnc 43235", CarrierFamily.IRANCELL, CarrierFamily.detect("43235", "", wifi = false))
        T.eq("mccMnc 43220", CarrierFamily.RIGHTEL, CarrierFamily.detect("43220", "", wifi = false))
        T.eq("mccMnc 43214", CarrierFamily.TCI, CarrierFamily.detect("43214", "", wifi = false))
        T.eq("name match mtn", CarrierFamily.IRANCELL, CarrierFamily.detect("", "MTN Irancell", wifi = false))
        T.eq("persian name match", CarrierFamily.MCI, CarrierFamily.detect("", "همراه اول", wifi = false))
        T.eq("unknown carrier", CarrierFamily.OTHER, CarrierFamily.detect("", "Something", wifi = false))
    }
}
