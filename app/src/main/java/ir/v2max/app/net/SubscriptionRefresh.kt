package ir.v2max.app.net

import ir.v2max.app.data.Repo
import ir.v2max.app.data.Subscription
import ir.v2max.core.model.PublicSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Shared download/commit phase for startup, manual and periodic refreshes.
 * The caller holds SubscriptionSyncGate until the following health pass ends.
 */
internal object SubscriptionRefresh {
    data class Result(
        val sources: List<Repo.StartupSourceSnapshot> = emptyList(),
        val received: Int = 0,
        val firstError: String = ""
    ) {
        val updated: Int get() = sources.size
    }

    private data class SourceResult(
        val snapshot: Repo.StartupSourceSnapshot? = null,
        val received: Int = 0,
        val error: String = ""
    )

    suspend fun run(
        requested: List<Subscription>,
        epoch: Long,
        token: Long,
        permit: Repo.AutomaticRefreshPermit? = null,
        parallelism: Int = 6,
        fetch: suspend (Subscription) -> SubscriptionFetcher.Result = SubscriptionPolicy::fetch,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): Result {
        val targets = requested.filter { !it.builtIn || PublicSources.forUrl(it.url) != null }
        if (targets.isEmpty()) return Result()
        val semaphore = Semaphore(parallelism.coerceIn(1, 8))
        val progress = Mutex()
        var completed = 0

        fun current(): Boolean = Repo.currentDataEpoch() == epoch && Repo.startupHealthIsCurrent(token) &&
            (permit == null || Repo.automaticRefreshIsCurrent(permit))

        Repo.suspendPersist(true)
        try {
            val results = coroutineScope {
                targets.map { sub ->
                    async(Dispatchers.IO) {
                        val result = semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            if (!current()) return@async SourceResult()
                            try {
                                fetch(sub)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Never include a subscription URL or credentials in an error.
                                SubscriptionFetcher.Result(error = "دریافت اشتراک ناموفق بود")
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!current()) return@async SourceResult()
                        val configs = if (result.ok) SubscriptionPolicy.candidates(sub, result.configs) else emptyList()
                        val outcome = if (configs.isEmpty()) {
                            val error = result.error.ifBlank { "کانفیگ قابل استفاده‌ای دریافت نشد" }
                            Repo.recordSubscriptionRefreshError(
                                sub, error, expectedEpoch = epoch, automaticPermit = permit, startupToken = token
                            )
                            SourceResult(error = error)
                        } else {
                            val complete = SubscriptionPolicy.isComplete(result, configs)
                            val revision = Repo.commitSubscriptionRefreshSnapshot(
                                sub, configs, result.upload, result.download, result.total, result.expireAt,
                                expectedEpoch = epoch, automaticPermit = permit, deferBuiltInCap = true,
                                startupToken = token, preserveOmitted = !complete
                            )
                            if (revision == null) SourceResult() else SourceResult(
                                snapshot = Repo.StartupSourceSnapshot(
                                    revision, configs.mapTo(HashSet()) { it.fingerprint() }, complete
                                ),
                                received = configs.size
                            )
                        }
                        progress.withLock {
                            completed++
                            onProgress(completed, targets.size)
                        }
                        outcome
                    }
                }.awaitAll()
            }
            return Result(
                sources = results.mapNotNull { it.snapshot },
                received = results.sumOf { it.received },
                firstError = results.firstOrNull { it.error.isNotBlank() }?.error.orEmpty()
            )
        } finally {
            Repo.suspendPersist(false)
            // Preserve commits that completed before cancellation, without publishing
            // stale probe results or losing the caller's cancellation.
            withContext(NonCancellable + Dispatchers.IO) { Repo.persistNow() }
        }
    }
}
