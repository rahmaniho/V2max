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

    /** Match v2rayNG first; try fragmentation only when enabled and the plain test failed. */
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
    fun choose(verified: Collection<ServerConfig>, preferredId: String? = null): ServerConfig? {
        val successful = verified.filter { it.realPing > 0 && it.testedUrl.isNotBlank() }
        return successful.firstOrNull { it.id == preferredId } ?: successful.minByOrNull { it.score() }
    }
}
