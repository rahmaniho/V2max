package ir.v2max.core.test

import ir.v2max.core.domain.SubscriptionMerger

/**
 * Tests for reconciling a refreshed subscription against existing records.
 *
 * The failure mode these guard is the one users report as "the app forgot my
 * settings": a subscription refresh that drops a favourite flag, resets
 * measured latency, or disconnects the server currently in use.
 */
object SubscriptionMergerTests {

    /**
     * Stands in for the caller's server type.
     *
     * [gen] marks which side a record came from, so a test can tell whether a
     * survivor is the old record, the new one, or the result of a carry-over.
     */
    private data class Node(
        val id: String,
        val host: String,
        val favorite: Boolean = false,
        val ping: Int = -1,
        val gen: String = "new",
        val usable: Boolean = true,
    )

    /** Fingerprint on host alone: the same host means the same server. */
    private fun merge(
        existing: List<Node>,
        incoming: List<Node>,
        liveId: String? = null,
    ) = SubscriptionMerger.merge(
        existing = existing,
        incoming = incoming,
        liveId = liveId,
        fingerprintOf = { it.host },
        isUsable = { it.usable },
        idOf = { it.id },
        // The caller's real carry-over keeps the identity and the measured
        // history of the old record while taking connectivity from the new one.
        carryOver = { old, updated ->
            updated.copy(id = old.id, favorite = old.favorite, ping = old.ping, gen = "carried")
        },
    )

    fun run() {
        T.group("SubscriptionMerger")

        // ---- a record with no match is taken as-is
        val fresh = merge(emptyList(), listOf(Node("n1", "a.example.com")))
        T.eq("new record kept", 1, fresh.size)
        T.eq("new record untouched", "new", fresh.single().gen)

        // ---- a match carries the old identity and history forward
        val existing = listOf(Node("old-id", "a.example.com", favorite = true, ping = 42, gen = "old"))
        val incoming = listOf(Node("fresh-id", "a.example.com", gen = "new"))
        val carried = merge(existing, incoming)
        T.eq("match produces one record", 1, carried.size)
        T.eq("id preserved from old", "old-id", carried.single().id)
        T.eq("favourite preserved across refresh", true, carried.single().favorite)
        T.eq("measured latency preserved", 42, carried.single().ping)
        T.eq("carry-over actually ran", "carried", carried.single().gen)

        // ---- an unusable endpoint is dropped
        val withBad = merge(
            emptyList(),
            listOf(Node("b1", "localhost", usable = false), Node("b2", "ok.example.com")),
        )
        T.eq("unusable record dropped", listOf("b2"), withBad.map { it.id })

        // ---- duplicates inside one payload: first wins
        val dupes = merge(
            emptyList(),
            listOf(
                Node("first", "same.example.com", gen = "one"),
                Node("second", "same.example.com", gen = "two"),
            ),
        )
        T.eq("duplicate fingerprint collapsed", 1, dupes.size)
        T.eq("first duplicate wins", "first", dupes.single().id)

        // A duplicate must not win over a carry-over from the old record either.
        val dupesVsOld = merge(
            listOf(Node("old-id", "same.example.com", gen = "old")),
            listOf(Node("a", "same.example.com", gen = "one"), Node("b", "same.example.com", gen = "two")),
        )
        T.eq("old record still carried when duplicated", "old-id", dupesVsOld.single().id)

        // ---- records absent from the refresh are dropped
        // This is a replace, not a union: a server the subscription stopped
        // publishing must go away, or stale entries accumulate forever.
        val dropped = merge(
            listOf(Node("gone", "removed.example.com", gen = "old"), Node("kept", "still.example.com", gen = "old")),
            listOf(Node("k2", "still.example.com", gen = "new")),
        )
        T.eq("vanished server removed", listOf("kept"), dropped.map { it.id })

        // ---- the live server survives even when the refresh drops it
        val liveKept = merge(
            listOf(Node("live", "live.example.com", gen = "old"), Node("x", "x.example.com", gen = "old")),
            emptyList(),
            liveId = "live",
        )
        T.eq("live server preserved", listOf("live"), liveKept.map { it.id })
        T.eq("live server is the old record", "old", liveKept.single().gen)

        // ---- but it is not duplicated when the refresh still lists it
        val liveListed = merge(
            listOf(Node("live", "live.example.com", gen = "old")),
            listOf(Node("n", "live.example.com", gen = "new")),
            liveId = "live",
        )
        T.eq("live server not duplicated", 1, liveListed.size)
        T.eq("live server carried rather than appended", "carried", liveListed.single().gen)

        // ---- no live id means nothing extra is preserved
        val noLive = merge(
            listOf(Node("dead", "dead.example.com", gen = "old")),
            emptyList(),
            liveId = null,
        )
        T.eq("nothing preserved without a live id", 0, noLive.size)

        // ---- order follows the incoming payload
        val ordered = merge(
            emptyList(),
            listOf(Node("c", "c.example.com"), Node("a", "a.example.com"), Node("b", "b.example.com")),
        )
        T.eq("incoming order preserved", listOf("c", "a", "b"), ordered.map { it.id })

        // ---- a live id that matches nothing is a no-op, not a crash
        val phantom = merge(
            listOf(Node("x", "x.example.com", gen = "old")),
            listOf(Node("y", "y.example.com", gen = "new")),
            liveId = "not-here",
        )
        T.eq("unknown live id ignored", listOf("y"), phantom.map { it.id })

        // ---- empty inputs
        T.eq("both empty", 0, merge(emptyList(), emptyList()).size)
    }
}
