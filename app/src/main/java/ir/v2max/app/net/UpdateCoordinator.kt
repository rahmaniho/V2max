package ir.v2max.app.net

import ir.v2max.app.data.Repo
import ir.v2max.app.data.UpdateInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialize foreground and periodic checks so an older response cannot overwrite a newer one. */
internal object UpdateCoordinator {
    private val mutex = Mutex()

    suspend fun check(
        automatic: Boolean,
        onApplied: (UpdateInfo) -> Unit = {}
    ): UpdateChecker.Result? = check(automatic, { UpdateChecker.checkForUpdate() }, onApplied)

    /** A null result means an automatic check was disabled before it could be applied. */
    internal suspend fun check(
        automatic: Boolean,
        fetch: suspend () -> UpdateChecker.Result,
        onApplied: (UpdateInfo) -> Unit = {}
    ): UpdateChecker.Result? = mutex.withLock {
        val epoch = if (automatic) Repo.beginAutomaticUpdateCheck() ?: return@withLock null else null
        val result = fetch()
        currentCoroutineContext().ensureActive()
        val info = when (result) {
            is UpdateChecker.Result.Available -> result.info
            UpdateChecker.Result.Current -> UpdateInfo()
            UpdateChecker.Result.Unavailable -> {
                return@withLock if (epoch != null && !Repo.automaticUpdateCheckIsCurrent(epoch)) null else result
            }
        }
        if (Repo.completeUpdateCheck(info, epoch, onApplied)) result else null
    }
}
