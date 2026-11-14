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

    /** Plain and, only when enabled and applicable, fragmented protocol variants. */
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
        val variants = variants(server, settings)
        val urls = testUrls(settings)
        // A recent success only changes the order of NEW tests. It is never reused as
        // proof. Respect current fragment settings and never use an old arbitrary URL.
        val recent = server.testedAt > 0 &&
            System.currentTimeMillis() - server.testedAt in 0..(10 * 60 * 1000L) &&
            server.realPing > 0 && server.testedUrl in urls
        val previousVariant = if (recent) variants.firstOrNull {
            it.fragment == server.testedFragment &&
                (!it.fragment || it.fragmentProfile == server.testedFragmentProfile)
        } else null
        val orderedVariants = if (previousVariant != null) {
            listOf(previousVariant) + variants.filter { it != previousVariant }
        } else variants
        val orderedUrls = if (recent) listOf(server.testedUrl) + urls.filter { it != server.testedUrl } else urls
        for (variant in orderedVariants) {
            for (url in orderedUrls) {
                coroutineContext.ensureActive()
                val delay = measure(server, variant, url)
                coroutineContext.ensureActive()
                if (delay > 0) return ProbeResult(delay, url, variant.fragment, variant.fragmentProfile)
            }
        }
        return null
    }

    /** Ignore persisted download measurements when ranking a NEW scan by latency. */
    fun latencyScore(server: ServerConfig): Int = server.copy(speedKbps = 0).score()

    /** Use only current speed samples, never the persisted speed in ServerConfig. */
    fun rank(verified: Collection<ServerConfig>, speeds: Map<String, Int> = emptyMap()): List<ServerConfig> =
        CoreProbePlanner.rank(
            candidates = verified,
            realPingOf = { it.realPing },
            testedUrlOf = { it.testedUrl },
            scoreOf = ::latencyScore,
            speedOf = { speeds[it.id] ?: 0 },
        )

    fun choose(
        verified: Collection<ServerConfig>,
        preferredId: String? = null,
        speeds: Map<String, Int> = emptyMap()
    ): ServerConfig? = CoreProbePlanner.choose(
        candidates = verified,
        preferredId = preferredId,
        idOf = { it.id },
        realPingOf = { it.realPing },
        testedUrlOf = { it.testedUrl },
        scoreOf = ::latencyScore,
        speedOf = { speeds[it.id] ?: 0 },
    )
}
