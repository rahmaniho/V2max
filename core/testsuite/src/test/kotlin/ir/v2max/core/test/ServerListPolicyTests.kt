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

        // ---- the ceiling constant matches the original
        T.eq("ceiling is 700", 700, ServerListPolicy.MAX_BUILT_IN_SERVERS)
    }
}
