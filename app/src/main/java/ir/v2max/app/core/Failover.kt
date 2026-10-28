package ir.v2max.app.core

import ir.v2max.app.data.Repo
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.net.HealthScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * موتور جایگزینی خودکار سرور.
 * اگر سرور فعلی از کار بیفتد، سریع‌ترین سرور سالم شناخته‌شده انتخاب می‌شود و
 * در صورت نبود گزینه، چند سرور به‌صورت موازی تست و بهترینشان برگردانده می‌شود.
 */
object Failover {

    private const val FRESH_MS = 30 * 60 * 1000L
    private const val PROBE_CONCURRENCY = 24

    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun reset() {
        failed.clear()
    }

    fun markFailed(id: String) {
        if (id.isNotBlank()) failed.add(id)
    }

    fun markWorking(id: String) {
        if (id.isNotBlank()) failed.remove(id)
    }

    fun failedCount(): Int = failed.size

    /** کاندید بعدی از بین سرورهای سالمِ شناخته‌شده */
    fun next(currentId: String? = null): ServerConfig? {
        val now = System.currentTimeMillis()
        val candidates = Repo.servers.value.filter { it.id != currentId && it.id !in failed }

        candidates
            .filter { it.bestPing() > 0 && now - it.testedAt <= FRESH_MS }
            .minByOrNull { it.bestPing() }
            ?.let { return it }

        return candidates.filter { it.bestPing() > 0 }.minByOrNull { it.bestPing() }
    }

    /**
     * تست سریع TCP روی مجموعه‌ای از سرورهای بدون نتیجه‌ی تازه.
     * @return بهترین سرور زنده یا null
     */
    suspend fun probeAsync(limit: Int = 80): ServerConfig? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val pool = Repo.servers.value
            .filter { it.id !in failed && (it.bestPing() <= 0 || now - it.testedAt > FRESH_MS) }
            .shuffled()
            .take(limit)
        if (pool.isEmpty()) return@withContext null

        val semaphore = Semaphore(PROBE_CONCURRENCY)
        val results = coroutineScope {
            pool.map { cfg ->
                async {
                    val ping = semaphore.withPermit { HealthScanner.tcpPing(cfg) }
                    cfg to ping
                }
            }.awaitAll()
        }

        val alive = results.filter { it.second > 0 }
        if (alive.isEmpty()) return@withContext null

        alive.forEach { (cfg, ping) -> Repo.setTestResult(cfg.id, ping, -1, persist = false) }
        Repo.persistNow()

        alive.minByOrNull { it.second }?.first
    }

    /** تلاش کامل: ابتدا گزینه‌ی شناخته‌شده، سپس کاوش سریع */
    suspend fun findReplacement(currentId: String?): ServerConfig? =
        next(currentId) ?: probeAsync()
}
