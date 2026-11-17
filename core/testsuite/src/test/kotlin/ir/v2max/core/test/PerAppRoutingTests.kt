package ir.v2max.core.test

import ir.v2max.core.domain.PerAppRouting
import ir.v2max.core.model.PerAppMode
import java.util.concurrent.CancellationException

object PerAppRoutingTests {
    private const val SELF = "ir.example.vpn"

    fun run() {
        T.group("Per-app VPN routing policy")
        val allowed = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        fun apply(mode: PerAppMode, packages: List<String>) = PerAppRouting.apply(
            mode, packages, SELF, allowed::add, excluded::add
        )
        fun reset() { allowed.clear(); excluded.clear() }
        fun failure(action: () -> Unit): PerAppRouting.InvalidRules? = try {
            action()
            null
        } catch (e: PerAppRouting.InvalidRules) {
            e
        }

        apply(PerAppMode.OFF, listOf("other.app"))
        T.eq("all mode does not allow-list apps", emptyList<String>(), allowed)
        T.eq("all mode always excludes the VPN app", listOf(SELF), excluded)

        reset()
        apply(PerAppMode.ONLY_SELECTED, listOf("  one.app ", "two.app", "one.app", SELF, " "))
        T.eq("only mode allows exactly the distinct selected packages", listOf("one.app", "two.app"), allowed)
        T.eq("only mode never mixes allow and disallow rules", emptyList<String>(), excluded)

        reset()
        apply(PerAppMode.EXCEPT_SELECTED, listOf("one.app", SELF, "one.app", "two.app"))
        T.eq("except mode has no allow rules", emptyList<String>(), allowed)
        T.eq("except mode excludes self and each selected package", listOf(SELF, "one.app", "two.app"), excluded)

        for (packages in listOf(emptyList(), listOf(" "), listOf(SELF), listOf(" ", SELF))) {
            reset()
            val rejected = failure { apply(PerAppMode.ONLY_SELECTED, packages) }
            T.eq("empty only-selected list fails closed", PerAppRouting.Failure.EMPTY_SELECTION, rejected?.reason)
            T.ok("empty only-selected list never touches the builder", allowed.isEmpty() && excluded.isEmpty())
        }

        reset()
        val partial = failure {
            PerAppRouting.apply(PerAppMode.ONLY_SELECTED, listOf("one.app", "missing.app", "three.app"), SELF,
                allow = { pkg -> if (pkg == "missing.app") error("sensitive package info") else allowed.add(pkg) },
                disallow = { excluded.add(it) })
        }
        T.eq("an unavailable allowed app rejects the entire builder", PerAppRouting.Failure.RULE_UNAVAILABLE, partial?.reason)
        T.eq("no later rule is applied after a failure", listOf("one.app"), allowed)
        T.ok("failed allow-list never falls back to all apps", excluded.isEmpty())
        T.ok("the failure does not contain platform details", partial?.message.isNullOrEmpty() && partial?.cause == null)

        reset()
        val missingExcluded = failure {
            PerAppRouting.apply(PerAppMode.EXCEPT_SELECTED, listOf("missing.app"), SELF,
                allow = { allowed.add(it) },
                disallow = { pkg -> if (pkg == "missing.app") error("missing") else excluded.add(pkg) })
        }
        T.eq("an unavailable excluded app rejects the whole builder", PerAppRouting.Failure.RULE_UNAVAILABLE, missingExcluded?.reason)
        T.eq("self was excluded before the failed rule", listOf(SELF), excluded)
        T.ok("a failed exclusion never switches to an allow-list", allowed.isEmpty())

        for (mode in listOf(PerAppMode.OFF, PerAppMode.EXCEPT_SELECTED)) {
            val missingSelf = failure {
                PerAppRouting.apply(mode, listOf("one.app"), SELF, {}, { error("self exclusion failed") })
            }
            T.eq("cannot start if the VPN app cannot be excluded", PerAppRouting.Failure.RULE_UNAVAILABLE, missingSelf?.reason)
        }
        val missingIdentity = failure { PerAppRouting.apply(PerAppMode.OFF, emptyList(), " ", {}, {}) }
        T.eq("blank VPN package fails closed", PerAppRouting.Failure.RULE_UNAVAILABLE, missingIdentity?.reason)

        var cancelled = false
        try {
            PerAppRouting.apply(PerAppMode.ONLY_SELECTED, listOf("one.app"), SELF,
                { throw CancellationException("cancel") }, {})
        } catch (_: CancellationException) {
            cancelled = true
        }
        T.ok("cancellation is not converted into an invalid package error", cancelled)
    }
}
