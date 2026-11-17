package ir.v2max.app.core

import android.content.Context
import ir.v2max.app.data.Repo
import ir.v2max.app.net.CarrierProbe
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.StartupMaintenance
import ir.v2max.app.net.SubscriptionRefresh
import ir.v2max.app.net.SubscriptionSyncGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fail over only to a recently verified Xray configuration, never a TCP-only result. */
object Failover {
    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var lastRefreshAt = 0L

    fun reset() = failed.clear()
    fun markFailed(id: String) { if (id.isNotBlank()) failed.add(id) }
    fun markWorking(id: String) { if (id.isNotBlank()) failed.remove(id) }
    fun failedCount(): Int = failed.size

    /** Re-test promising replacements on the current network before switching. */
    internal suspend fun probeAsync(context: Context, limit: Int = 0, currentId: String? = null): VerifiedSelection? =
        withContext(Dispatchers.IO) {
            // A dying or user-switched tunnel takes priority over background
            // housekeeping. The barrier still waits for native calls to return.
            StartupMaintenance.cancelAll()
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
                fastestRecentFirst = true,
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
    private suspend fun refreshSubscriptions(context: Context): Boolean {
        StartupMaintenance.cancelAll()
        val token = Repo.startupHealthToken()
        return SubscriptionSyncGate.mutex.withLock {
            val now = System.currentTimeMillis()
            if (!Repo.startupHealthIsCurrent(token) || now - lastRefreshAt < REFRESH_INTERVAL_MS) return@withLock false
            val permit = Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.FAILOVER) ?: return@withLock false
            lastRefreshAt = now
            try {
                val sources = Repo.subs.value.filter { now - it.updatedAt > REFRESH_INTERVAL_MS }
                    .sortedBy { it.builtIn }.take(6)
                val fetched = SubscriptionRefresh.run(sources, permit.dataEpoch, token, permit, parallelism = 4)
                currentCoroutineContext().ensureActive()
                if (fetched.sources.isNotEmpty() && Repo.startupHealthIsCurrent(token) &&
                    Repo.automaticRefreshIsCurrent(permit)) {
                    // Cleanup is guarded and never interrupts the existing TUN.
                    // A separate preflight below still verifies all requested
                    // connection tests before any handover is attempted.
                    StartupMaintenance.finish(context, fetched.sources, permit.dataEpoch, permit, token)
                }
                fetched.sources.isNotEmpty() && Repo.automaticRefreshIsCurrent(permit)
            } finally {
                // A user who turns refresh back on can retry without waiting for
                // an obsolete attempt's ten-minute throttle.
                if (!Repo.automaticRefreshIsCurrent(permit) || !Repo.startupHealthIsCurrent(token)) lastRefreshAt = 0L
            }
        }
    }

    internal suspend fun findReplacement(context: Context, currentId: String?): VerifiedSelection? {
        probeAsync(context, currentId = currentId)?.let { return it }
        if (refreshSubscriptions(context)) return probeAsync(context, currentId = currentId)
        return null
    }
}
