package ir.v2max.core.test

import ir.v2max.core.domain.SubscriptionCheck
import ir.v2max.core.domain.SubscriptionHealthEvidence
import ir.v2max.core.domain.SubscriptionHealthPolicy as P

object SubscriptionHealthPolicyTests {
    fun run() {
        T.group("Subscription health evidence")
        T.eq("an empty feed is unknown", SubscriptionCheck.UNKNOWN, P.classify(emptySet(), emptyMap(), true))
        T.eq("unmeasured entries are unknown", SubscriptionCheck.UNKNOWN, P.classify(setOf("a", "b"), mapOf("a" to false), true))
        T.eq("all failed without a proven network is unknown", SubscriptionCheck.UNKNOWN, P.classify(setOf("a"), mapOf("a" to false), false))
        T.eq("one current success proves a source is healthy even with incomplete coverage", SubscriptionCheck.HEALTHY,
            P.classify(setOf("a", "b"), mapOf("a" to true), true))
        T.eq("all received identities tested and another server works", SubscriptionCheck.NO_RESPONSE,
            P.classify(setOf("a", "b"), mapOf("a" to false, "b" to false, "other" to true), true))
        T.eq("shared fingerprints work across deduplicated sources", SubscriptionCheck.HEALTHY,
            P.classify(setOf("shared"), mapOf("shared" to true), true))

        val start = 10_000_000_000L
        val one = P.observe(SubscriptionHealthEvidence(), SubscriptionCheck.NO_RESPONSE, start, 11)
        T.eq("one complete negative check", 1, one.failureChecks)
        T.ok("one check cannot suggest deletion", !P.shouldSuggest(one, 11, start + P.MIN_INTERVAL_MS))
        val tooSoon = P.observe(one, SubscriptionCheck.NO_RESPONSE, start + P.MIN_INTERVAL_MS - 1, 12)
        T.eq("repeated launches cannot inflate evidence", 1, tooSoon.failureChecks)
        T.ok("recent revision alone does not qualify", !P.shouldSuggest(tooSoon, 12, start + P.MIN_INTERVAL_MS))
        val two = P.observe(tooSoon, SubscriptionCheck.NO_RESPONSE, start + P.MIN_INTERVAL_MS, 13)
        T.eq("two separated checks", 2, two.failureChecks)
        T.ok("two checks at least a day apart qualify", P.shouldSuggest(two, 13, start + P.MIN_INTERVAL_MS))
        T.ok("a new untested refresh invalidates a prompt", !P.shouldSuggest(two, 14, start + P.MIN_INTERVAL_MS))
        T.eq("uncertain results cannot add evidence", two, P.observe(two, SubscriptionCheck.UNKNOWN, start + 2 * P.MIN_INTERVAL_MS, 14))
        val healthy = P.observe(two, SubscriptionCheck.HEALTHY, start + 2 * P.MIN_INTERVAL_MS, 14)
        T.eq("a current success clears all failures", 0, healthy.failureChecks)
        T.ok("a current success clears the suggestion", !P.shouldSuggest(healthy, 14, start + 2 * P.MIN_INTERVAL_MS))
        val deferred = two.copy(dismissedAt = start + P.MIN_INTERVAL_MS)
        T.ok("later suppresses the prompt for one week", !P.shouldSuggest(deferred, 13, start + 2 * P.MIN_INTERVAL_MS))
        T.ok("old results cannot trigger a reminder", !P.shouldSuggest(deferred, 13, start + 8 * P.MIN_INTERVAL_MS))
        val freshlyFailed = P.observe(deferred, SubscriptionCheck.NO_RESPONSE, start + 8 * P.MIN_INTERVAL_MS, 15)
        T.ok("a deferred prompt can return after another complete current failure",
            P.shouldSuggest(freshlyFailed, 15, start + 8 * P.MIN_INTERVAL_MS))
        val clockMovedBack = P.observe(two, SubscriptionCheck.NO_RESPONSE, start - 1, 15)
        T.eq("a backwards clock starts over", 1, clockMovedBack.failureChecks)
        T.ok("a backwards clock cannot trigger a suggestion", !P.shouldSuggest(two, 13, start - 1))
    }
}
