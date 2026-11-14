package ir.v2max.core.test

import ir.v2max.core.domain.ScanHint
import ir.v2max.core.domain.ScanOrder

object ScanOrderTests {
    fun run() {
        T.group("Scan order")
        val now = 100_000_000L
        fun candidate(id: String, source: String, favorite: Boolean = false, age: Long? = null,
                      ping: Int = -1, url: Boolean = false) =
            ScanHint(id, source, favorite, age?.let { now - it } ?: 0L, ping, url)
        val list = listOf(
            candidate("a1", "A"), candidate("a2", "A"), candidate("a3", "A"),
            candidate("b1", "B"), candidate("b2", "B"),
            candidate("c1", "C"),
            candidate("recent", "A", age = 1_000, ping = 90, url = true),
            candidate("stale", "B", age = ScanOrder.RECENT_MS + 1, ping = 1, url = true),
            candidate("future", "B", age = -1, ping = 1, url = true),
            candidate("tcp-only", "C", age = 1_000, ping = 1, url = false),
            candidate("favorite", "C", favorite = true),
            candidate("remembered", "B"), candidate("preferred", "A"),
        )
        val ordered = ScanOrder.prioritize(list, { it }, now, preferredId = "preferred", rememberedId = "remembered")
        T.eq("explicit selection first", "preferred", ordered[0].id)
        T.eq("network hint second", "remembered", ordered[1].id)
        T.eq("favorite before historical candidates", "favorite", ordered[2].id)
        T.eq("recent real result before untested", "recent", ordered[3].id)
        T.eq("all candidates retained", list.size, ordered.size)
        T.eq("no candidate duplicated", list.map { it.id }.toSet(), ordered.map { it.id }.toSet())
        T.eq("different sources interleaved", listOf("a1", "b1", "c1", "a2", "b2", "tcp-only"),
            ordered.drop(4).take(6).map { it.id })
        T.ok("stale result is not a recent success", ordered.indexOfFirst { it.id == "stale" } > 3)
        T.ok("future timestamp is not a recent success", ordered.indexOfFirst { it.id == "future" } > 3)

        val history = listOf(
            candidate("slow", "A", age = 10, ping = 600, url = true),
            candidate("fast", "A", age = 10, ping = 40, url = true),
            candidate("other", "B", age = 10, ping = 100, url = true),
        )
        T.eq("best real ping within a source", listOf("fast", "other", "slow"),
            ScanOrder.prioritize(history, { it }, now).map { it.id })
        T.eq("empty list", emptyList<ScanHint>(), ScanOrder.prioritize(emptyList<ScanHint>(), { it }, now))
    }
}
