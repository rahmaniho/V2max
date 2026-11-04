package ir.v2max.app.net

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.Proto
import ir.v2max.app.data.ServerConfig
import ir.v2max.core.domain.ProbePlanner as CoreProbePlanner
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A successful protocol-level HTTP test, including the exact settings that passed. */
data class ProbeResult(
    val delayMs: Int,
    val url: String,
    val fragment: Boolean,
    val profile: FragmentProfile
)

/**
 * Plans and runs the probes for a server.
 *
 * The rules — which transports are UDP, which URLs to try, when fragmentation
 * is worth a retry, which results may be believed — live in
 * `ir.v2max.core.domain.ProbePlanner` and are tested there. What stays here is
 * the parts that need app types or coroutines: translating `ServerConfig` and
 * `AppSettings` into the primitives those rules take, and driving `measure`.
 */
object ProbePlanner {
    const val FALLBACK_URL = CoreProbePlanner.FALLBACK_URL

    /** A failed TCP handshake cannot rule out protocols transported over UDP. */
    fun usesUdp(server: ServerConfig): Boolean = CoreProbePlanner.usesUdp(
        protoIsHysteria2 = server.proto == Proto.HYSTERIA2,
        network = server.network,
    )

    fun testUrls(settings: AppSettings): List<String> = CoreProbePlanner.testUrls(settings.delayTestUrl)

    /** Match v2rayNG first; try fragmentation only when enabled and the plain test failed. */
    fun variants(server: ServerConfig, settings: AppSettings): List<AppSettings> {
        val plain = settings.copy(fragment = false, balancer = false)
        val canFragment = CoreProbePlanner.canFragment(
            fragmentEnabled = settings.fragment,
            finalMaskBlank = server.finalMask.isBlank(),
            isUdp = usesUdp(server),
            security = server.security,
            protoIsTrojan = server.proto == Proto.TROJAN,
        )
        return if (canFragment) listOf(plain, plain.copy(fragment = true)) else listOf(plain)
    }

    suspend fun verify(
        server: ServerConfig,
        settings: AppSettings,
        measure: suspend (ServerConfig, AppSettings, String) -> Int
    ): ProbeResult? {
        for (variant in variants(server, settings)) {
            for (url in testUrls(settings)) {
                coroutineContext.ensureActive()
                val delay = measure(server, variant, url)
                coroutineContext.ensureActive()
                if (delay > 0) return ProbeResult(delay, url, variant.fragment, variant.fragmentProfile)
            }
        }
        return null
    }

    /** Only fresh protocol successes can be candidates, never TCP-only or stale results. */
    fun choose(verified: Collection<ServerConfig>, preferredId: String? = null): ServerConfig? =
        CoreProbePlanner.choose(
            candidates = verified,
            preferredId = preferredId,
            idOf = { it.id },
            realPingOf = { it.realPing },
            testedUrlOf = { it.testedUrl },
            scoreOf = { it.score() },
        )
}
