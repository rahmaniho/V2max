package ir.v2max.core.domain

/** Historical information is only a scheduling hint, never proof that a server works now. */
data class ScanHint(
    val id: String,
    val sourceId: String,
    val favorite: Boolean,
    val verifiedAt: Long,
    val realPing: Int,
    val hasVerifiedUrl: Boolean,
)

/**
 * Put promising candidates first without letting a large subscription monopolize a scan.
 * Every returned item still needs a new protocol test. Even stale and untested items are
 * retained so callers can keep searching when the first group fails.
 */
object ScanOrder {
    const val RECENT_MS = 24L * 60 * 60 * 1000

    fun <T> prioritize(
        items: List<T>,
        hint: (T) -> ScanHint,
        nowMs: Long,
        preferredId: String? = null,
        rememberedId: String? = null,
    ): List<T> {
        data class Entry<T>(val item: T, val hint: ScanHint, val index: Int, val tier: Int)

        val entries = items.mapIndexed { index, item ->
            val h = hint(item)
            val recent = h.realPing > 0 && h.hasVerifiedUrl && h.verifiedAt > 0 &&
                nowMs - h.verifiedAt in 0..RECENT_MS
            val tier = when {
                preferredId != null && h.id == preferredId -> 0
                rememberedId != null && h.id == rememberedId -> 1
                h.favorite -> 2
                recent -> 3
                else -> 4
            }
            Entry(item, h, index, tier)
        }
        val ordered = ArrayList<T>(items.size)
        for (tier in 0..4) {
            // Within a source, use a recent real measurement rather than TCP-only data.
            // Unmeasured items preserve their original order.
            val members = entries.filter { it.tier == tier }.sortedWith(
                compareBy<Entry<T>> {
                    if (tier == 3) it.hint.realPing else Int.MAX_VALUE
                }.thenBy { it.index }
            )
            val bySource = linkedMapOf<String, java.util.ArrayDeque<T>>()
            for (entry in members) {
                bySource.getOrPut(entry.hint.sourceId) { java.util.ArrayDeque() }.addLast(entry.item)
            }
            val queues = java.util.ArrayDeque(bySource.values)
            while (queues.isNotEmpty()) {
                val queue = queues.removeFirst()
                ordered.add(queue.removeFirst())
                if (queue.isNotEmpty()) queues.addLast(queue)
            }
        }
        return ordered
    }
}
