package ir.v2max.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.v2max.core.model.PublicSources
import ir.v2max.app.data.Repo
import ir.v2max.app.net.SubscriptionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

/**
 * به‌روزرسانی دوره‌ای اشتراک‌ها در پس‌زمینه، حتی وقتی برنامه باز نیست.
 * کانفیگ‌ها تازه می‌شوند تا هنگام باز کردن برنامه، فهرست آماده و تست‌شده باشد.
 */
class SubSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        run()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        ir.v2max.app.util.CrashLog.record(t, "SubSyncWorker")
        Result.success()
    }

    private suspend fun run(): Result {
        Repo.init(applicationContext)
        val subs = Repo.subs.value
        if (subs.isEmpty()) return Result.success()

        val semaphore = Semaphore(4)
        val updated = java.util.concurrent.atomic.AtomicInteger(0)

        Repo.suspendPersist(true)
        try {
            coroutineScope {
                subs.map { sub ->
                    async(Dispatchers.IO) {
                        // The catalogue accepts trailing slashes; SubscriptionPolicy
                        // canonicalizes known built-ins before allowing mirrors.
                        if (sub.builtIn && PublicSources.forUrl(sub.url) == null) return@async
                        val result = semaphore.withPermit { SubscriptionPolicy.fetch(sub) }
                        val trimmed = if (result.ok) SubscriptionPolicy.candidates(sub, result.configs) else emptyList()
                        if (trimmed.isNotEmpty() && Repo.commitSubscriptionRefresh(
                                sub, trimmed, result.upload, result.download, result.total, result.expireAt
                            )) {
                            updated.incrementAndGet()
                        } else if (trimmed.isEmpty()) {
                            Repo.recordSubscriptionRefreshError(sub, result.error.ifBlank { "کانفیگ قابل استفاده‌ای دریافت نشد" })
                        }
                        // کمی فاصله تا منابع همزمان فشار نیاورند
                        delay(120)
                    }
                }.awaitAll()
            }
        } finally {
            Repo.suspendPersist(false)
            Repo.persistNow()
        }

        Repo.dedupeAllServers()
        Repo.trimBuiltInServers()
        Repo.persistNow()

        return if (updated.get() == 0) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "v2max-sub-sync"
        private const val INTERVAL_HOURS = 12L

        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<SubSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(NAME)
            }
        }
    }
}
