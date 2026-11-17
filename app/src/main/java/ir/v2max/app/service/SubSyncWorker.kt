package ir.v2max.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.v2max.app.data.Repo
import ir.v2max.app.net.StartupMaintenance
import ir.v2max.app.net.SubscriptionRefresh
import ir.v2max.app.net.SubscriptionSyncGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
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
        // WorkManager may be executing a request scheduled under older settings.
        val permit = Repo.beginAutomaticRefresh(Repo.AutomaticRefresh.STARTUP_OR_PERIODIC)
            ?: return Result.success()
        val token = Repo.startupHealthToken()
        // User actions and VPN recovery cancel this phase, including when it is
        // waiting for the gate. A running native probe still drains at the barrier.
        return StartupMaintenance.track(token) {
            SubscriptionSyncGate.mutex.withLock {
                if (!Repo.startupHealthIsCurrent(token) || !Repo.automaticRefreshIsCurrent(permit)) {
                    return@withLock Result.success()
                }
                val sources = Repo.subs.value
                if (sources.isEmpty()) return@withLock Result.success()
                val fetched = SubscriptionRefresh.run(
                    sources, permit.dataEpoch, token, permit, parallelism = 4
                )
                currentCoroutineContext().ensureActive()
                if (!Repo.startupHealthIsCurrent(token) || !Repo.automaticRefreshIsCurrent(permit)) {
                    return@withLock Result.success()
                }
                if (fetched.sources.isEmpty()) return@withLock Result.retry()
                // An interrupted or unavailable health check retains unknown
                // profiles; it must never be recorded as a failed server.
                StartupMaintenance.finish(applicationContext, fetched.sources, permit.dataEpoch, permit, token)
                Result.success()
            }
        }
    }

    companion object {
        private const val NAME = "v2max-sub-sync"
        private const val INTERVAL_HOURS = 12L

        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<SubSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    // Opening the app already performs a full refresh. Avoid a
                    // duplicate immediate run competing with its health pass.
                    .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
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
