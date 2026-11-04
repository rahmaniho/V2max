package ir.v2max.core.domain

/**
 * Reconciles a refreshed subscription against what the user already has.
 *
 * Extracted from `Repo.replaceSubscriptionServers`. That method mixed this
 * algorithm with `MutableStateFlow` writes, persistence, and the list of fields
 * that survive a refresh — none of which could be exercised without an Android
 * `Context`. The algorithm is the part with subtle invariants, so it is the part
 * under test here; the field list is specific to the caller's own server type
 * and stays there, supplied as [carryOver].
 */
object SubscriptionMerger {

    /**
     * Applies [incoming] over [existing], matching records by fingerprint.
     *
     * Rules, in the order the original applied them:
     *
     *  - an incoming record with an unusable endpoint is dropped;
     *  - the *first* incoming record for a fingerprint wins, later duplicates
     *    in the same payload are ignored;
     *  - when an incoming record matches an existing one, [carryOver] decides
     *    which parts of the old record survive — this is what keeps a user's
     *    favourite flag and measured latency across a refresh;
     *  - a record with no match is taken as-is;
     *  - [liveId], when set, is preserved even if the refreshed subscription no
     *    longer lists it, so the active connection is never cut by a sync.
     *
     * [existing] must already be narrowed to the subscription being refreshed;
     * merging across subscriptions would match records that should stay apart.
     */
    fun <T> merge(
        existing: List<T>,
        incoming: List<T>,
        liveId: String? = null,
        fingerprintOf: (T) -> String,
        isUsable: (T) -> Boolean,
        idOf: (T) -> String,
        carryOver: (old: T, updated: T) -> T,
    ): List<T> {
        val byKey = existing.associateBy(fingerprintOf)
        val merged = mutableListOf<T>()
        val seen = HashSet<String>()

        for (candidate in incoming) {
            if (!isUsable(candidate)) continue
            val key = fingerprintOf(candidate)
            // First occurrence wins; a repeated fingerprint inside one payload
            // is a duplicate, not an update.
            if (!seen.add(key)) continue
            val old = byKey[key]
            merged.add(if (old != null) carryOver(old, candidate) else candidate)
        }

        // Keep the live server until disconnect even if an updated subscription
        // no longer lists it.
        if (liveId != null && merged.none { idOf(it) == liveId }) {
            existing.firstOrNull { idOf(it) == liveId }?.let { merged.add(it) }
        }

        return merged
    }
}
