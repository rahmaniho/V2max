package ir.v2max.app.core

import android.content.Context
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.SubscriptionFetcher
import ir.v2max.app.net.SubscriptionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fail over only to a recently verified Xray configuration, never a TCP-only result. */
object Failover {
    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val refreshMutex = Mutex()
    @Volatile private var lastRefreshAt = 0L

    fun reset() = failed.clear()
    fun markFailed(id: String) { if (id.isNotBlank()) failed.add(id) }
    fun markWorking(id: String) { if (id.isNotBlank()) failed.remove(id) }
    fun failedCount(): Int = failed.size

    /** Re-test promising replacements on the current network before switching. */
    suspend fun probeAsync(context: Context, limit: Int = 0, currentId: String? = null): ServerConfig? =
        withContext(Dispatchers.IO) {
        val pool = Repo.servers.value.filter {
            it.id != currentId && it.id !in failed && it.hasUsableEndpoint()
        }
        if (pool.isEmpty()) return@withContext null
        val startedAt = System.currentTimeMillis()
        val outcome = HealthScanner.scan(
            context = context,
            servers = pool,
            settings = Repo.settings.value,
            limit = limit,
            onTcp = { cfg, ping -> Repo.setPretest(cfg.id, ping, cfg.fingerprint()) },
            onReal = { cfg, result -> Repo.setVerifiedResult(cfg.id, result, expectedFingerprint = cfg.fingerprint()) },
            onMulti = { cfg -> Repo.setMultiPing(cfg.id, cfg.googlePing, cfg.instagramPing, cfg.youtubePing, expectedFingerprint = cfg.fingerprint()) },
            onSpeed = { cfg, kbps -> Repo.setSpeed(cfg.id, kbps, persist = false, expectedFingerprint = cfg.fingerprint()) }
        )
        currentCoroutineContext().ensureActive()
        val selected = outcome.qualified.firstNotNullOfOrNull { tested ->
            Repo.serverById(tested.id)?.takeIf { current ->
                current.fingerprint() == tested.fingerprint() && current.testedAt >= startedAt &&
                    current.realPing > 0 && current.testedUrl == tested.testedUrl &&
                    current.testedFragment == tested.testedFragment &&
                    (!current.testedFragment || current.testedFragmentProfile == tested.testedFragmentProfile)
            }
        }
        Repo.persistNow()
        selected
    }

    /** Refresh stale subscriptions once if no existing configuration succeeds. */
    private suspend fun refreshSubscriptions(): Boolean = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        if (!Repo.settings.value.autoUpdateSubs || now - lastRefreshAt < REFRESH_INTERVAL_MS) return@withLock false
        lastRefreshAt = now
        val sources = Repo.subs.value.filter { now - it.updatedAt > REFRESH_INTERVAL_MS }
            .sortedBy { it.builtIn }.take(6)
        var refreshed = false
        for (sub in sources) {
            currentCoroutineContext().ensureActive()
            val result = SubscriptionFetcher.fetch(sub.url, SubscriptionPolicy.limit(sub))
            currentCoroutineContext().ensureActive()
            val current = Repo.subById(sub.id) ?: continue
            if (!result.ok) continue
            val configs = SubscriptionPolicy.candidates(current, result.configs)
            if (configs.isEmpty()) continue
            configs.forEach { it.subId = current.id }
            Repo.replaceSubscriptionServers(current.id, configs)
            Repo.updateSubscription(current.copy(
                updatedAt = System.currentTimeMillis(), count = configs.size,
                upload = result.upload, download = result.download, total = result.total,
                expireAt = result.expireAt, lastError = ""
            ))
            refreshed = true
        }
        refreshed
    }

    suspend fun findReplacement(context: Context, currentId: String?): ServerConfig? {
        probeAsync(context, currentId = currentId)?.let { return it }
        if (refreshSubscriptions()) return probeAsync(context, currentId = currentId)
        return null
    }
}
