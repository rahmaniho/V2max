package ir.v2max.app.core

import android.content.Context
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fail over only to a recently verified Xray configuration, never a TCP-only result. */
object Failover {
    private const val FRESH_MS = 10 * 60 * 1000L
    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val refreshMutex = Mutex()
    @Volatile private var lastRefreshAt = 0L

    fun reset() = failed.clear()
    fun markFailed(id: String) { if (id.isNotBlank()) failed.add(id) }
    fun markWorking(id: String) { if (id.isNotBlank()) failed.remove(id) }
    fun failedCount(): Int = failed.size

    fun next(currentId: String? = null): ServerConfig? =
        Repo.healthyServers(FRESH_MS).firstOrNull { it.id != currentId && it.id !in failed }

    /** Complete the same real-protocol test before choosing an untested replacement. */
    suspend fun probeAsync(context: Context, limit: Int = 80): ServerConfig? = withContext(Dispatchers.IO) {
        val builtIns = Repo.subs.value.filter { it.builtIn }.map { it.id }.toSet()
        val pool = Repo.servers.value.filter { it.id !in failed && it.hasUsableEndpoint() }
            .shuffled()
            .sortedWith(compareByDescending<ServerConfig> { it.favorite }.thenBy { it.subId in builtIns })
            .take(limit)
        if (pool.isEmpty()) return@withContext null
        val outcome = HealthScanner.scan(
            context = context,
            servers = pool,
            settings = Repo.settings.value,
            onTcp = { cfg, ping -> Repo.setPretest(cfg.id, ping) },
            onReal = { cfg, result -> Repo.setVerifiedResult(cfg.id, result) },
            onMulti = { cfg -> Repo.setMultiPing(cfg.id, cfg.googlePing, cfg.instagramPing, cfg.youtubePing) },
            onSpeed = { cfg, kbps -> Repo.setSpeed(cfg.id, kbps, persist = false) }
        )
        Repo.persistNow()
        outcome.best?.let { Repo.serverById(it.id) }
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
            val result = SubscriptionFetcher.fetch(sub.url)
            currentCoroutineContext().ensureActive()
            if (!result.ok) continue
            result.configs.forEach { it.subId = sub.id }
            Repo.replaceSubscriptionServers(sub.id, result.configs)
            Repo.updateSubscription(sub.copy(
                updatedAt = System.currentTimeMillis(), count = result.configs.size,
                upload = result.upload, download = result.download, total = result.total,
                expireAt = result.expireAt, lastError = ""
            ))
            refreshed = true
        }
        refreshed
    }

    suspend fun findReplacement(context: Context, currentId: String?): ServerConfig? {
        next(currentId)?.let { return it }
        probeAsync(context)?.let { return it }
        if (refreshSubscriptions()) return probeAsync(context)
        return null
    }
}
