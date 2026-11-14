package ir.v2max.app.net

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.PingTargets
import ir.v2max.app.data.Proto
import ir.v2max.app.data.ServerConfig
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A successful protocol-level HTTP test, including the exact settings that passed. */
data class ProbeResult(
    val delayMs: Int,
    val url: String,
    val fragment: Boolean,
    val profile: FragmentProfile
)

object ProbePlanner {
    const val FALLBACK_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    /** A failed TCP handshake cannot rule out protocols transported over UDP. */
    fun usesUdp(server: ServerConfig): Boolean =
        server.proto == Proto.HYSTERIA2 || server.network.lowercase() in setOf("hysteria", "kcp", "mkcp", "quic")

    fun testUrls(settings: AppSettings): List<String> {
        val preferred = settings.delayTestUrl.trim().ifBlank { PingTargets.GOOGLE.url }
        val validPreferred = preferred.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: PingTargets.GOOGLE.url
        return listOf(validPreferred, FALLBACK_URL).distinct()
    }

    /** Plain and, only when enabled and applicable, fragmented protocol variants. */
    fun variants(server: ServerConfig, settings: AppSettings): List<AppSettings> {
        val plain = settings.copy(fragment = false, balancer = false)
        val canFragment = settings.fragment && server.finalMask.isBlank() && !usesUdp(server) &&
            (server.security == "tls" || server.security == "reality" ||
                (server.proto == Proto.TROJAN && server.security.isBlank()))
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

    /**
     * Only newly successful protocol tests are candidates. When this scan also
     * measured download speed, prefer servers within 80% of its fastest sample,
     * breaking that group by latency and stability. Old/unmeasured speeds never
     * outrank a current measurement. These samples are not a guarantee of future speed.
     */
    fun rank(verified: Collection<ServerConfig>, speeds: Map<String, Int> = emptyMap()): List<ServerConfig> {
        val successful = verified.filter { it.realPing > 0 && it.testedUrl.isNotBlank() }
        val measured = successful.mapNotNull { speeds[it.id]?.takeIf { speed -> speed > 0 } }
        val fastest = measured.maxOrNull() ?: 0
        return successful.sortedWith(
            compareBy<ServerConfig> { cfg ->
                val speed = speeds[cfg.id] ?: 0
                when {
                    fastest <= 0 -> 0
                    speed > 0 && speed.toLong() * 10 >= fastest.toLong() * 8 -> 0
                    speed > 0 -> 1
                    else -> 2
                }
            }.thenBy { latencyScore(it) }
                .thenByDescending { speeds[it.id] ?: 0 }
        )
    }

    fun choose(
        verified: Collection<ServerConfig>,
        preferredId: String? = null,
        speeds: Map<String, Int> = emptyMap()
    ): ServerConfig? {
        val ranked = rank(verified, speeds)
        return ranked.firstOrNull { it.id == preferredId } ?: ranked.firstOrNull()
    }
}
