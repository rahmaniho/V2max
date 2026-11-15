package ir.v2max.core.domain

/** A missing result, a failed download, or an interrupted scan is not a failed server. */
enum class SubscriptionCheck { HEALTHY, NO_RESPONSE, UNKNOWN }

data class SubscriptionHealthEvidence(
    val firstFailureAt: Long = 0,
    val lastFailureAt: Long = 0,
    val failureChecks: Int = 0,
    val checkedRevision: Long = 0,
    val dismissedAt: Long = 0,
)

/** Conservative, reversible suggestions; this policy never deletes a subscription. */
object SubscriptionHealthPolicy {
    const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val REMINDER_INTERVAL_MS = 7L * MIN_INTERVAL_MS
    const val MAX_LAST_CHECK_AGE_MS = 2L * MIN_INTERVAL_MS

    /**
     * [expected] is the set actually received in one successful refresh, not
     * the number of records left after global deduplication or a built-in cap.
     * A positive result for an identical fingerprint may be shared by sources.
     * An absent fingerprint is unknown, even when the visible list is empty.
     */
    fun classify(expected: Set<String>, results: Map<String, Boolean>, networkProven: Boolean): SubscriptionCheck {
        if (expected.isEmpty()) return SubscriptionCheck.UNKNOWN
        if (expected.any { results[it] == true }) return SubscriptionCheck.HEALTHY
        if (!networkProven || expected.any { it !in results }) return SubscriptionCheck.UNKNOWN
        return SubscriptionCheck.NO_RESPONSE
    }

    /** Count independent complete checks, separated by at least a day. */
    fun observe(
        previous: SubscriptionHealthEvidence,
        result: SubscriptionCheck,
        now: Long,
        revision: Long,
    ): SubscriptionHealthEvidence {
        if (result == SubscriptionCheck.UNKNOWN || now <= 0 || revision <= 0) return previous
        if (result == SubscriptionCheck.HEALTHY) return SubscriptionHealthEvidence(checkedRevision = revision)
        val valid = previous.failureChecks > 0 && previous.firstFailureAt > 0 &&
            previous.lastFailureAt >= previous.firstFailureAt && previous.lastFailureAt <= now
        if (!valid) return SubscriptionHealthEvidence(now, now, 1, revision)
        val separated = now - previous.lastFailureAt >= MIN_INTERVAL_MS
        return previous.copy(
            lastFailureAt = if (separated) now else previous.lastFailureAt,
            failureChecks = if (separated) previous.failureChecks.coerceAtMost(999) + 1 else previous.failureChecks,
            checkedRevision = revision,
        )
    }

    fun shouldSuggest(evidence: SubscriptionHealthEvidence, currentRevision: Long, now: Long): Boolean =
        currentRevision > 0 && evidence.checkedRevision == currentRevision &&
            evidence.failureChecks >= 2 && evidence.firstFailureAt > 0 &&
            evidence.lastFailureAt >= evidence.firstFailureAt && evidence.lastFailureAt <= now &&
            now - evidence.lastFailureAt <= MAX_LAST_CHECK_AGE_MS &&
            evidence.lastFailureAt - evidence.firstFailureAt >= MIN_INTERVAL_MS &&
            (evidence.dismissedAt <= 0 || now - evidence.dismissedAt >= REMINDER_INTERVAL_MS)
}
