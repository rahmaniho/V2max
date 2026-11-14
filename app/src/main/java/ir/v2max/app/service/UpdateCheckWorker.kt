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
import ir.v2max.app.net.UpdateChecker
import ir.v2max.app.net.UpdateCoordinator
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Best-effort periodic release check; Android may defer it for battery/network policy. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            Repo.init(applicationContext)
            val result = UpdateCoordinator.check(automatic = true) { info ->
                if (info.version.isBlank()) {
                    Notifier.cancelUpdate(applicationContext)
                } else if (Repo.lastNotifiedUpdate() != info.version &&
                    Notifier.updateAvailable(applicationContext, info.version)) {
                    Repo.markUpdateNotified(info.version)
                }
            }
            if (result == UpdateChecker.Result.Unavailable) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "v2max-release-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            Notifier.cancelUpdate(context)
        }
    }
}
