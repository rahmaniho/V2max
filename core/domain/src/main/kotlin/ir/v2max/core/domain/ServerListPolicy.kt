package ir.v2max.core.domain

import ir.v2max.core.model.Server

/** Result of a deduplication pass. */
data class DedupeResult(val servers: List<Server>, val removed: Int) {
    val changed: Boolean get() = removed > 0
}

/** Result of appending new servers to an existing list. */
data class AppendResult<T>(val servers: List<T>, val added: Int) {
    val changed: Boolean get() = added > 0
}

/**
 * Rules for maintaining the server list.
 *
 * Extracted from `Repo`, where both were private methods reading `MutableStateFlow`
 * fields directly, which made them impossible to exercise without an Android
 * `Context`. The invariants they protect are the ones users actually notice:
 * a personal config or a favourited server must never be silently deleted.
 */
object ServerListPolicy {

    /**
     * Ceiling on servers from the built-in public sources.
     *
     * Applies only to those; a user's own configs and subscriptions are never
     * subject to it.
     */
    const val MAX_BUILT_IN_SERVERS = 700

    /**
     * Trims built-in servers down to [limit], preserving order.
     *
     * Favourites, the selected server, and any other protected IDs (for example
     * a live tunnel) always survive even if that exceeds the limit. The
     * rest are ranked by [Server.score], so the worst performers go first.
     *
     * When [sourceReserve] is positive, a bounded share is also reserved for
     * each represented public source. This helps prevent a full older catalogue
     * from discarding every candidate of a newly refreshed source before the
     * user has a chance to test it. Protected records count toward the share.
     * A share may exceed the soft limit when protected records already fill it;
     * at most [limit] additional unprotected records are reserved in total.
     *
     * Returns the input unchanged when nothing needs trimming.
     */
    fun capBuiltIn(
        list: List<Server>,
        builtInSubscriptionIds: Set<String>,
        selectedId: String = "",
        limit: Int = MAX_BUILT_IN_SERVERS,
        protectedIds: Set<String> = emptySet(),
        sourceReserve: Int = 0,
    ): List<Server> = capBuiltIn(
        list = list,
        builtInSubscriptionIds = builtInSubscriptionIds,
        selectedId = selectedId,
        limit = limit,
        protectedIds = protectedIds,
        sourceReserve = sourceReserve,
        idOf = { it.id },
        subscriptionIdOf = { it.subscriptionId },
        isFavorite = { it.favorite },
        scoreOf = { it.score() },
    )

    /**
     * The same rule expressed over any element type.
     *
     * Callers that have not migrated to [Server] yet — the app's persistence
     * layer still holds its own `ServerConfig` — can apply the policy through
     * accessors instead of building an adapter for a list that may hold
     * hundreds of entries. The [Server] overload above delegates here, so both
     * paths run this single implementation.
     */
    fun <T> capBuiltIn(
        list: List<T>,
        builtInSubscriptionIds: Set<String>,
        selectedId: String = "",
        limit: Int = MAX_BUILT_IN_SERVERS,
        protectedIds: Set<String> = emptySet(),
        sourceReserve: Int = 0,
        idOf: (T) -> String,
        subscriptionIdOf: (T) -> String,
        isFavorite: (T) -> Boolean,
        scoreOf: (T) -> Int,
    ): List<T> {
        if (builtInSubscriptionIds.isEmpty()) return list

        val builtIn = list.filter { subscriptionIdOf(it) in builtInSubscriptionIds }
        val capacity = limit.coerceAtLeast(0)
        if (builtIn.size <= capacity) return list

        fun isProtected(s: T) = isFavorite(s) || idOf(s) == selectedId || idOf(s) in protectedIds

        val protected = builtIn.filter(::isProtected)
        val remaining = builtIn.filterNot(::isProtected).sortedBy { scoreOf(it) }
        val keep = protected.mapTo(HashSet()) { idOf(it) }

        if (sourceReserve > 0 && capacity > 0) {
            // Apportion at most the normal capacity across the represented
            // sources; a tiny custom capacity can only reserve a subset. Do not
            // spend slots for empty catalogue subscriptions.
            val sourceCount = builtIn.mapTo(HashSet()) { subscriptionIdOf(it) }.size
            val quota = minOf(sourceReserve, (capacity / sourceCount).coerceAtLeast(1))
            val protectedCounts = protected.groupingBy { subscriptionIdOf(it) }.eachCount()
            val groups = remaining.groupBy { subscriptionIdOf(it) }
            var reserved = 0
            for ((source, candidates) in groups) {
                val need = (quota - (protectedCounts[source] ?: 0)).coerceAtLeast(0)
                for (candidate in candidates.take(need)) {
                    if (reserved >= capacity) break
                    if (keep.add(idOf(candidate))) reserved++
                }
                if (reserved >= capacity) break
            }
        }

        // After protecting source shares, fill any remaining capacity by score.
        var slots = (capacity - keep.size).coerceAtLeast(0)
        for (candidate in remaining) {
            if (slots == 0) break
            if (keep.add(idOf(candidate))) slots--
        }

        // Personal servers are untouched regardless of the cap.
        return list.filter { subscriptionIdOf(it) !in builtInSubscriptionIds || idOf(it) in keep }
    }

    /**
     * Collapses duplicates by [Server.fingerprint], keeping the better record.
     *
     * On a collision a favourite always beats a non-favourite — a user's
     * explicit choice is not something a refresh should discard. Otherwise the
     * record with the better *real* latency wins, and an unmeasured record never
     * displaces a measured one.
     */
    fun dedupe(list: List<Server>): DedupeResult {
        if (list.size < 2) return DedupeResult(list, 0)

        val keep = LinkedHashMap<String, Server>()
        for (server in list) {
            val key = server.fingerprint()
            val current = keep[key]
            keep[key] = if (current == null) server else betterOf(current, server)
        }

        if (keep.size == list.size) return DedupeResult(list, 0)
        return DedupeResult(keep.values.toList(), list.size - keep.size)
    }

    private fun betterOf(current: Server, candidate: Server): Server = when {
        candidate.favorite != current.favorite -> if (candidate.favorite) candidate else current
        else -> {
            val a = candidate.bestPing()
            val b = current.bestPing()
            if (a > 0 && (b <= 0 || a < b)) candidate else current
        }
    }

    /**
     * Appends genuinely new servers to an existing list.
     *
     * This is the *add* rule, and it deliberately differs from [dedupe]: nothing
     * already present is ever replaced, however good the newcomer looks. A user
     * who imported a config yesterday keeps their copy — its identity, its
     * measured latency, its name — so re-pasting the same server is a no-op
     * rather than a silent reset of everything they learned about it.
     *
     * Order of operations matters. [prepare] runs *before* the fingerprint is
     * taken, so a caller that enriches a record cannot change which records
     * collide. The app resolves a country from the server's name here, and that
     * enrichment must not decide identity.
     *
     * @param isUsable rejects records that could never connect; they are dropped
     *   before anything else, so they neither get appended nor block a
     *   duplicate.
     * @return the combined list plus how many records were appended, letting the
     *   caller skip persisting when nothing changed.
     */
    fun <T> appendNew(
        existing: List<T>,
        incoming: List<T>,
        fingerprintOf: (T) -> String,
        isUsable: (T) -> Boolean = { true },
        prepare: (T) -> T = { it },
    ): AppendResult<T> {
        if (incoming.isEmpty()) return AppendResult(existing, 0)

        // Existing records are registered as-is: they were accepted when they
        // were added, and an entry that has since gone unusable still owns its
        // fingerprint.
        val seen = HashSet<String>(existing.size + incoming.size)
        for (item in existing) seen.add(fingerprintOf(item))

        val result = ArrayList<T>(existing.size + incoming.size)
        result.addAll(existing)

        for (item in incoming) {
            if (!isUsable(item)) continue
            val prepared = prepare(item)
            if (seen.add(fingerprintOf(prepared))) result.add(prepared)
        }

        return AppendResult(result, result.size - existing.size)
    }
}
