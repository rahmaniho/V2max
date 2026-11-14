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
import ir.v2max.app.data.UpdateInfo
import ir.v2max.app.net.UpdateChecker
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Best-effort periodic release check; Android may defer it for battery/network policy. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            Repo.init(applicationContext)
            if (!Repo.settings.value.autoUpdateCheck) return Result.success()
            val result = UpdateChecker.checkForUpdate()
            // The user may have turned checks off while the request was in flight.
            if (!Repo.settings.value.autoUpdateCheck) return Result.success()
            when (result) {
                is UpdateChecker.Result.Available -> {
                    Repo.saveUpdateInfo(result.info)
                    Repo.markUpdateChecked()
                    if (Repo.lastNotifiedUpdate() != result.info.version &&
                        Notifier.updateAvailable(applicationContext, result.info.version)) {
                        Repo.markUpdateNotified(result.info.version)
                    }
                    Result.success()
                }
                UpdateChecker.Result.Current -> {
                    Repo.saveUpdateInfo(UpdateInfo())
                    Repo.markUpdateChecked()
                    Notifier.cancelUpdate(applicationContext)
                    Result.success()
                }
                UpdateChecker.Result.Unavailable -> Result.retry()
            }
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
