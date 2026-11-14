package ir.v2max.app.core

import android.content.Context
import ir.v2max.app.data.Repo
import ir.v2max.app.net.CarrierProbe
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
    internal suspend fun probeAsync(context: Context, limit: Int = 0, currentId: String? = null): VerifiedSelection? =
        withContext(Dispatchers.IO) {
            val pool = Repo.servers.value.filter {
                it.id != currentId && it.id !in failed && it.hasUsableEndpoint()
            }
            if (pool.isEmpty()) return@withContext null
            val settings = Repo.settings.value.copy()
            val networkAtStart = CarrierProbe.networkHandle(context) ?: return@withContext null
            if (settings.deepProbe && !SmartConnect.assessNetwork(context, force = true)) return@withContext null
            val outcome = HealthScanner.scan(
                context = context,
                servers = pool,
                settings = SmartConnect.effectiveScanSettings(context, settings),
                limit = limit,
                onTcp = { cfg, ping -> Repo.setPretest(cfg.id, ping, cfg.fingerprint()) },
                onReal = { cfg, result -> Repo.setVerifiedResult(cfg.id, result, expectedFingerprint = cfg.fingerprint()) },
                onMulti = { cfg -> Repo.setMultiPing(cfg.id, cfg.googlePing, cfg.instagramPing, cfg.youtubePing, expectedFingerprint = cfg.fingerprint()) },
                onSpeed = { cfg, kbps -> Repo.setSpeed(cfg.id, kbps, persist = false, expectedFingerprint = cfg.fingerprint()) }
            )
            currentCoroutineContext().ensureActive()
            if (networkAtStart != CarrierProbe.networkHandle(context) ||
                PreflightPolicy.from(settings) != PreflightPolicy.from(Repo.settings.value)) return@withContext null
            val qualified = outcome.qualified.filter { tested ->
                Repo.serverById(tested.id)?.fingerprint() == tested.fingerprint()
            }
            Repo.persistNow()
            qualified.firstOrNull()?.let { VerifiedSelection.create(it, qualified, settings, networkAtStart) }
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
            if (!result.ok) continue
            val configs = SubscriptionPolicy.candidates(sub, result.configs)
            if (configs.isEmpty()) continue
            if (Repo.commitSubscriptionRefresh(
                    sub, configs, result.upload, result.download, result.total, result.expireAt
                )) refreshed = true
        }
        refreshed
    }

    internal suspend fun findReplacement(context: Context, currentId: String?): VerifiedSelection? {
        probeAsync(context, currentId = currentId)?.let { return it }
        if (refreshSubscriptions()) return probeAsync(context, currentId = currentId)
        return null
    }
}
