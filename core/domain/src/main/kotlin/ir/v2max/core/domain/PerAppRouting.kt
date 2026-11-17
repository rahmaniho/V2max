package ir.v2max.core.domain

import ir.v2max.core.model.PerAppMode
import java.util.concurrent.CancellationException

/**
 * Apply one complete Android VPN allow- or disallow-list to a new, unestablished
 * builder. If any rule cannot be applied, the caller must discard that builder.
 * In particular, Android treats an empty allow-list as "allow every app", so it
 * must never be used as a fallback for ONLY_SELECTED.
 */
object PerAppRouting {
    enum class Failure { EMPTY_SELECTION, RULE_UNAVAILABLE }

    // Do not put package names or platform exception messages in errors that can
    // be shown in notifications or reported outside the settings screen.
    class InvalidRules(val reason: Failure) : IllegalStateException()

    fun apply(
        mode: PerAppMode,
        requestedPackages: List<String>,
        vpnPackage: String,
        allow: (String) -> Unit,
        disallow: (String) -> Unit,
    ) {
        val ownPackage = vpnPackage.trim()
        if (ownPackage.isEmpty()) throw InvalidRules(Failure.RULE_UNAVAILABLE)
        val selected = requestedPackages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != ownPackage }
            .distinct()
            .toList()

        fun applyRule(action: () -> Unit) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw InvalidRules(Failure.RULE_UNAVAILABLE)
            }
        }

        when (mode) {
            PerAppMode.OFF -> applyRule { disallow(ownPackage) }
            PerAppMode.ONLY_SELECTED -> {
                if (selected.isEmpty()) throw InvalidRules(Failure.EMPTY_SELECTION)
                selected.forEach { pkg -> applyRule { allow(pkg) } }
            }
            PerAppMode.EXCEPT_SELECTED -> {
                // Excluding the VPN application itself is required to keep its
                // outbound sockets from looping back into its own TUN.
                applyRule { disallow(ownPackage) }
                selected.forEach { pkg -> applyRule { disallow(pkg) } }
            }
        }
    }
}
