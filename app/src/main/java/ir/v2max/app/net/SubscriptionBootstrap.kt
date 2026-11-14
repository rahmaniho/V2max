package ir.v2max.app.net

import ir.v2max.app.data.PublicSources
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Limits only built-in public feeds, never a user's own subscription. */
object SubscriptionPolicy {
    fun limit(sub: Subscription): Int =
        if (sub.builtIn) PublicSources.forUrl(sub.url)?.maxConfigs ?: 150 else 0

    fun candidates(sub: Subscription, configs: List<ServerConfig>): List<ServerConfig> {
        val usable = configs.filter { it.hasUsableEndpoint() }
        val limit = limit(sub)
        return if (limit > 0) usable.take(limit) else usable
    }

    /** Only exact, predefined public feeds may be sent to a third-party mirror. */
    fun allowPublicMirrors(sub: Subscription): Boolean {
        val url = sub.url.trim()
        return sub.builtIn && PublicSources.forUrl(url)?.url == url
    }

    suspend fun fetch(sub: Subscription): SubscriptionFetcher.Result = SubscriptionFetcher.fetch(
        sub.url, maxConfigs = limit(sub), allowPublicMirrors = allowPublicMirrors(sub)
    )
}

/** Start with the first usable source rather than waiting for every slow public feed. */
object SubscriptionBootstrap {
    data class Found(val subscription: Subscription, val result: SubscriptionFetcher.Result)

    internal suspend fun firstUsable(
        sources: List<Subscription>,
        parallelism: Int = 6,
        fetch: suspend (Subscription) -> SubscriptionFetcher.Result = SubscriptionPolicy::fetch,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Found? = coroutineScope {
        if (sources.isEmpty()) return@coroutineScope null
        val semaphore = Semaphore(parallelism.coerceIn(1, 8))
        val completed = Channel<Found>(Channel.UNLIMITED)
        val jobs = sources.map { sub ->
            launch {
                val result = try {
                    semaphore.withPermit { fetch(sub) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SubscriptionFetcher.Result(error = "دریافت اشتراک ناموفق بود")
                }
                completed.send(Found(sub, result))
            }
        }
        try {
            repeat(sources.size) { index ->
                val found = completed.receive()
                onProgress(index + 1, sources.size)
                if (found.result.ok && SubscriptionPolicy.candidates(found.subscription, found.result.configs).isNotEmpty()) {
                    return@coroutineScope found
                }
            }
            null
        } finally {
            // SubscriptionFetcher cancels the underlying OkHttp call as well.
            jobs.forEach { it.cancel() }
        }
    }
}
