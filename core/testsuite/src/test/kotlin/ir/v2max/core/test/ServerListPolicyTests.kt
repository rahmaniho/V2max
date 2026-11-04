package ir.v2max.core.test

import ir.v2max.core.domain.ServerListPolicy
import ir.v2max.core.model.Endpoint
import ir.v2max.core.model.Measurement
import ir.v2max.core.model.Proto
import ir.v2max.core.model.Server

/**
 * Tests for server-list maintenance rules.
 *
 * The invariants here are the ones users notice directly: a personal config, a
 * favourite, or the currently connected server must never be deleted by a
 * subscription refresh or by hitting the public-source ceiling.
 */
object ServerListPolicyTests {

    private fun server(
        id: String,
        subId: String = "",
        favorite: Boolean = false,
        realPing: Int = -1,
        address: String? = null,
    ) = Server(
        id = id,
        name = id,
        proto = Proto.VLESS,
        // Distinct addresses make distinct fingerprints; a shared one makes two
        // servers collide, which is what the dedupe tests rely on.
        endpoint = Endpoint(address ?: "$id.example.com", 443),
        subscriptionId = subId,
        favorite = favorite,
        measurement = Measurement(realPing = realPing),
    )

    fun run() {
        T.group("ServerListPolicy")

        val builtIn = setOf("public")

        // ---- capBuiltIn: nothing to do
        val few = listOf(server("a", "public"), server("b", "public"))
        T.eq("under limit returns same list", 2, ServerListPolicy.capBuiltIn(few, builtIn).size)
        T.eq(
            "empty built-in set returns same list",
            2,
            ServerListPolicy.capBuiltIn(few, emptySet()).size,
        )

        // ---- capBuiltIn: personal servers are never capped
        val personalHeavy = (1..10).map { server("p$it", "mine") } +
            (1..5).map { server("b$it", "public") }
        val cappedPersonal = ServerListPolicy.capBuiltIn(personalHeavy, builtIn, limit = 3)
        // 10 personal untouched + 3 built-in surviving the cap.
        T.eq("total after cap", 13, cappedPersonal.size)
        T.eq("personal count preserved", 10, cappedPersonal.count { it.subscriptionId == "mine" })
        T.eq("built-in trimmed to limit", 3, cappedPersonal.count { it.subscriptionId == "public" })

        // ---- capBuiltIn: worst scores go first
        val ranked = listOf(
            server("fast", "public", realPing = 50),
            server("slow", "public", realPing = 900),
            server("mid", "public", realPing = 300),
        )
        val keptRanked = ServerListPolicy.capBuiltIn(ranked, builtIn, limit = 2)
        T.eq("keeps the two best", setOf("fast", "mid"), keptRanked.map { it.id }.toSet())

        // ---- capBuiltIn: favourites survive even when they are the worst
        val favWorst = listOf(
            server("fast", "public", realPing = 50),
            server("favSlow", "public", favorite = true, realPing = 9000),
            server("mid", "public", realPing = 300),
        )
        val keptFav = ServerListPolicy.capBuiltIn(favWorst, builtIn, limit = 2)
        T.ok("favourite survives", keptFav.any { it.id == "favSlow" })
        T.eq("limit still respected", 2, keptFav.size)

        // ---- capBuiltIn: the selected server survives
        val selWorst = listOf(
            server("fast", "public", realPing = 50),
            server("selected", "public", realPing = 9000),
            server("mid", "public", realPing = 300),
        )
        val keptSel = ServerListPolicy.capBuiltIn(selWorst, builtIn, selectedId = "selected", limit = 2)
        T.ok("selected survives", keptSel.any { it.id == "selected" })

        // ---- capBuiltIn: protecting more than the limit is allowed
        val allProtected = (1..5).map { server("f$it", "public", favorite = true, realPing = 9000) } +
            listOf(server("plain", "public", realPing = 50))
        val keptAll = ServerListPolicy.capBuiltIn(allProtected, builtIn, limit = 2)
        T.eq("protected servers can exceed limit", 5, keptAll.size)
        T.ok("unprotected dropped", keptAll.none { it.id == "plain" })

        // ---- capBuiltIn: input order is preserved
        val ordered = listOf(
            server("z", "public", realPing = 100),
            server("a", "public", realPing = 200),
            server("m", "public", realPing = 300),
        )
        T.eq(
            "original order preserved",
            listOf("z", "a"),
            ServerListPolicy.capBuiltIn(ordered, builtIn, limit = 2).map { it.id },
        )

        // ---- dedupe: distinct servers are untouched
        val distinct = listOf(server("a"), server("b"), server("c"))
        val dDistinct = ServerListPolicy.dedupe(distinct)
        T.eq("distinct: none removed", 0, dDistinct.removed)
        T.ok("distinct: unchanged", !dDistinct.changed)
        T.eq("distinct: all kept", 3, dDistinct.servers.size)

        // ---- dedupe: identical endpoints collide
        val dupes = listOf(
            server("a", address = "same.example.com"),
            server("b", address = "same.example.com"),
        )
        val dDupes = ServerListPolicy.dedupe(dupes)
        T.eq("dupes: one removed", 1, dDupes.removed)
        T.ok("dupes: changed", dDupes.changed)
        T.eq("dupes: first kept", "a", dDupes.servers.single().id)

        // ---- dedupe: a measured record beats an unmeasured one
        val measured = listOf(
            server("unmeasured", address = "same.example.com"),
            server("measured", address = "same.example.com", realPing = 200),
        )
        T.eq("measured wins", "measured", ServerListPolicy.dedupe(measured).servers.single().id)

        // ... and in the reverse input order too
        val measuredFirst = listOf(
            server("measured", address = "same.example.com", realPing = 200),
            server("unmeasured", address = "same.example.com"),
        )
        T.eq("measured wins regardless of order", "measured", ServerListPolicy.dedupe(measuredFirst).servers.single().id)

        // ---- dedupe: lower latency wins
        val pings = listOf(
            server("slow", address = "same.example.com", realPing = 500),
            server("fast", address = "same.example.com", realPing = 50),
        )
        T.eq("faster wins", "fast", ServerListPolicy.dedupe(pings).servers.single().id)

        // ---- dedupe: a favourite wins over a faster non-favourite
        val favDupes = listOf(
            server("fast", address = "same.example.com", realPing = 50),
            server("fav", address = "same.example.com", favorite = true, realPing = 9000),
        )
        T.eq("favourite beats faster", "fav", ServerListPolicy.dedupe(favDupes).servers.single().id)

        // ... and the reverse order, so it is not just first-wins
        val favFirst = listOf(
            server("fav", address = "same.example.com", favorite = true, realPing = 9000),
            server("fast", address = "same.example.com", realPing = 50),
        )
        T.eq("favourite wins regardless of order", "fav", ServerListPolicy.dedupe(favFirst).servers.single().id)

        // ---- dedupe: differing parameters must not collapse
        // Two servers on the same host but different ports are different servers.
        val differentPorts = listOf(
            Server(id = "a", endpoint = Endpoint("same.example.com", 443)),
            Server(id = "b", endpoint = Endpoint("same.example.com", 8443)),
        )
        T.eq("different ports are not duplicates", 0, ServerListPolicy.dedupe(differentPorts).removed)

        val differentProto = listOf(
            Server(id = "a", proto = Proto.VLESS, endpoint = Endpoint("same.example.com", 443)),
            Server(id = "b", proto = Proto.TROJAN, endpoint = Endpoint("same.example.com", 443)),
        )
        T.eq("different protocols are not duplicates", 0, ServerListPolicy.dedupe(differentProto).removed)

        // ---- dedupe: a short list is returned as-is
        T.eq("single element untouched", 1, ServerListPolicy.dedupe(listOf(server("a"))).servers.size)
        T.eq("empty list untouched", 0, ServerListPolicy.dedupe(emptyList()).servers.size)

        // ---- the generic overload, for callers not on core's Server type
        //
        // The app's persistence layer still holds its own ServerConfig, so the
        // policy is reachable through accessors. Row stands in for that type;
        // what matters is that the rule is identical.
        data class Row(val id: String, val subId: String, val favorite: Boolean, val score: Int)

        fun capRows(list: List<Row>, selectedId: String = "", limit: Int = 2) =
            ServerListPolicy.capBuiltIn(
                list = list,
                builtInSubscriptionIds = builtIn,
                selectedId = selectedId,
                limit = limit,
                idOf = { it.id },
                subscriptionIdOf = { it.subId },
                isFavorite = { it.favorite },
                scoreOf = { it.score },
            )

        val rows = listOf(
            Row("w", "public", false, 3000),
            Row("x", "public", false, 100),
            Row("y", "public", false, 200),
        )
        T.eq("generic: trims to limit", 2, capRows(rows).size)
        T.eq(
            "generic: worst score is dropped first",
            listOf("x", "y"),
            capRows(rows).map { it.id },
        )
        T.eq("generic: order preserved", listOf("x", "y"), capRows(rows).map { it.id })

        // A favourite survives even when it is the worst performer.
        T.eq(
            "generic: favourite protected despite worst score",
            listOf("w", "x"),
            capRows(rows.map { if (it.id == "w") it.copy(favorite = true) else it }).map { it.id },
        )

        // The selected server survives too, and the freed slot goes to the
        // lowest-scoring remaining entry (x=100), not the best one.
        T.eq(
            "generic: selected protected",
            listOf("w", "x"),
            capRows(rows, selectedId = "w").map { it.id },
        )

        // Protecting more entries than the limit leaves no room for anything
        // else, and every protected entry still survives rather than being
        // truncated down to the limit.
        val allFavorite = capRows(rows.map { it.copy(favorite = true) }, limit = 1)
        T.eq(
            "generic: protection can exceed the limit",
            listOf("w", "x", "y"),
            allFavorite.map { it.id },
        )
        T.ok("generic: nothing left for unprotected when all are protected", allFavorite.size == 3) {
            "kept ${allFavorite.map { it.id }} under limit 1"
        }

        // Personal servers are never touched by the cap.
        val withPersonal = rows + listOf(Row("p", "", false, 9999), Row("q", "mine", false, 9999))
        val personalKept = capRows(withPersonal).filter { it.subId != "public" }.map { it.id }
        T.eq("generic: personal servers untouched", listOf("p", "q"), personalKept)

        // Below the limit nothing happens at all.
        T.eq("generic: under limit is a no-op", 3, capRows(rows, limit = 3).size)
        T.eq("generic: no built-in subscriptions is a no-op", 3, capRows(rows.map { it.copy(subId = "") }).size)

        // ---- the generic path and the Server path must agree exactly.
        //
        // The Server overload delegates to the generic one, so building the same
        // list both ways and comparing the survivors catches any drift between
        // the two entry points.
        val mixed = listOf(
            server("s1", subId = "public", realPing = 400),
            server("s2", subId = "public", realPing = 50),
            server("s3", subId = "public", favorite = true, realPing = 900),
            server("s4", subId = "public", realPing = 120),
            server("s5", subId = "", realPing = 999),
        )
        val viaServer = ServerListPolicy.capBuiltIn(mixed, builtIn, selectedId = "s1", limit = 2).map { it.id }
        val viaGeneric = capRows(
            mixed.map { Row(it.id, it.subscriptionId, it.favorite, it.score()) },
            selectedId = "s1",
            limit = 2,
        ).map { it.id }
        T.eq(
            "Server and generic overloads agree",
            viaServer,
            viaGeneric,
        )
        T.ok("both paths actually trimmed", viaServer.size < mixed.size) {
            "kept=${viaServer.size} of ${mixed.size}"
        }

        // ---- the ceiling constant matches the original
        T.eq("ceiling is 700", 700, ServerListPolicy.MAX_BUILT_IN_SERVERS)

        // ================= appendNew =================
        //
        // The add rule. The point of these tests is that it is NOT dedupe: an
        // existing record always wins, so re-importing a server never resets the
        // latency history or the name the user gave it.
        data class Entry(val id: String, val key: String, val usable: Boolean = true)

        fun append(
            existing: List<Entry>,
            incoming: List<Entry>,
            prepare: (Entry) -> Entry = { it },
        ) = ServerListPolicy.appendNew(
            existing = existing,
            incoming = incoming,
            fingerprintOf = { it.key },
            isUsable = { it.usable },
            prepare = prepare,
        )

        // Nothing to do.
        val emptyAdd = append(listOf(Entry("a", "ka")), emptyList())
        T.eq("append: empty incoming adds nothing", 0, emptyAdd.added)
        T.eq("append: empty incoming keeps the list", listOf("a"), emptyAdd.servers.map { it.id })
        T.ok("append: empty incoming reports unchanged", !emptyAdd.changed)

        // Fresh records go on the end, in the order they arrived.
        val fresh = append(
            listOf(Entry("a", "ka"), Entry("b", "kb")),
            listOf(Entry("c", "kc"), Entry("d", "kd")),
        )
        T.eq("append: new records appended", 2, fresh.added)
        T.eq(
            "append: existing order kept, incoming after",
            listOf("a", "b", "c", "d"),
            fresh.servers.map { it.id },
        )

        // The core contract: a collision keeps the incumbent. Note this is the
        // opposite of dedupe, which would compare them and could promote the
        // newcomer.
        val collide = append(
            listOf(Entry("incumbent", "k")),
            listOf(Entry("newcomer", "k")),
        )
        T.eq("append: duplicate is not added", 0, collide.added)
        T.eq("append: incumbent wins", listOf("incumbent"), collide.servers.map { it.id })

        // Repeats inside a single batch collapse to the first.
        val batch = append(
            emptyList(),
            listOf(Entry("first", "k"), Entry("second", "k"), Entry("other", "k2")),
        )
        T.eq("append: first of a repeated batch wins", listOf("first", "other"), batch.servers.map { it.id })
        T.eq("append: repeat counted once", 2, batch.added)

        // Unusable records are filtered before anything else, so they are
        // neither appended nor able to block a usable record behind them.
        val filtered = append(
            emptyList(),
            listOf(Entry("bad", "k", usable = false), Entry("good", "k")),
        )
        T.eq("append: unusable dropped", listOf("good"), filtered.servers.map { it.id })
        T.eq("append: unusable does not claim the fingerprint", 1, filtered.added)

        // An incumbent owns its fingerprint even if it has since gone unusable;
        // the app fingerprints the existing list without re-checking it.
        val staleIncumbent = append(
            listOf(Entry("stale", "k", usable = false)),
            listOf(Entry("good", "k")),
        )
        T.eq("append: unusable incumbent still blocks", 0, staleIncumbent.added)
        T.eq("append: unusable incumbent retained", listOf("stale"), staleIncumbent.servers.map { it.id })

        // prepare runs before the fingerprint is taken. The app enriches each
        // incoming record with a country resolved from its name; that
        // enrichment must not decide identity, so it is applied first and the
        // fingerprint is read off the enriched record.
        val rewritten = append(
            listOf(Entry("incumbent", "k")),
            listOf(Entry("newcomer", "other")),
            prepare = { it.copy(key = "k") },
        )
        T.eq("append: prepare feeds the fingerprint", 0, rewritten.added)
        T.eq("append: prepared collision keeps incumbent", listOf("incumbent"), rewritten.servers.map { it.id })

        // ...and the appended records are the prepared ones, not the raw input.
        val enriched = append(emptyList(), listOf(Entry("raw", "k")), prepare = { it.copy(id = "prepared") })
        T.eq("append: stores the prepared record", listOf("prepared"), enriched.servers.map { it.id })

        // added always equals how much the list grew.
        T.ok("append: added matches list growth", fresh.servers.size == 2 + fresh.added) {
            "size=${fresh.servers.size} added=${fresh.added}"
        }
    }
}
