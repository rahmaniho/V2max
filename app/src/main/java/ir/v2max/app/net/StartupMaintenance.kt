package ir.v2max.app.net

import android.content.Context
import ir.v2max.app.core.PreflightPolicy
import ir.v2max.app.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Refresh comes first; a protocol health check is not permission to connect. */
internal object StartupMaintenance {
    enum class Status { COMPLETE, NO_NETWORK, NO_PROFILES, INCOMPLETE, CHANGED }
    data class Result(
        val status: Status,
        val health: Repo.StartupHealthResult = Repo.StartupHealthResult(),
        val duplicatesRemoved: Int = 0
    )

    // Register every refresh + health phase, including time waiting for the
    // shared sync gate. Recovery must not wait behind thousands of old probes.
    // Handover still waits for any already-started native calls at the barrier.
    private val jobs = HashSet<Job>()

    suspend fun <T> track(token: Long, block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        // If cancelAll() races the initial registration, either the old token
        // is rejected here or the registered job is included in cancellation.
        // cancelAll releases Repo's lock before acquiring the jobs lock.
        synchronized(jobs) {
            if (!Repo.startupHealthIsCurrent(token)) throw CancellationException("startup superseded")
            if (job != null) jobs.add(job)
        }
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            if (job != null) synchronized(jobs) { jobs.remove(job) }
        }
    }

    fun cancelAll() {
        // Serialize invalidation with publication under Repo's lock before
        // requesting cancellation, so no obsolete cleanup can commit later.
        Repo.invalidateStartupHealth()
        synchronized(jobs) { jobs.toList().forEach { it.cancel() } }
    }

    /** Finish every successful refresh the same way, even when probing is unavailable. */
    suspend fun finish(
        context: Context,
        sources: List<Repo.StartupSourceSnapshot>,
        epoch: Long,
        permit: Repo.AutomaticRefreshPermit?,
        token: Long,
        onPhase: suspend (String, Int, Int) -> Unit = { _, _, _ -> }
    ): Result = finishPrepared(sources, epoch, permit, token) {
        check(context, sources, permit, token, onPhase)
    }

    /** Injectable boundary: tests can exercise finalization without Android networking. */
    internal suspend fun finishPrepared(
        sources: List<Repo.StartupSourceSnapshot>,
        epoch: Long,
        permit: Repo.AutomaticRefreshPermit?,
        token: Long,
        check: suspend () -> Result
    ): Result {
        if (sources.isEmpty()) return Result(Status.NO_PROFILES)
        val checked = try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A broken probe is indeterminate, never proof that a server is dead.
            Result(Status.INCOMPLETE)
        }
        currentCoroutineContext().ensureActive()
        val finalized = Repo.finalizeSubscriptionRefresh(sources, epoch, token, permit)
        return if (finalized.applied) checked.copy(duplicatesRemoved = finalized.duplicatesRemoved)
        else Result(Status.CHANGED)
    }

    private suspend fun check(
        context: Context,
        sources: List<Repo.StartupSourceSnapshot>,
        permit: Repo.AutomaticRefreshPermit?,
        token: Long,
        onPhase: suspend (String, Int, Int) -> Unit = { _, _, _ -> }
    ): Result {
        val network = CarrierProbe.networkHandle(context) ?: return Result(Status.NO_NETWORK)
        val validatedAtStart = CarrierProbe.internetValidated(context)
        val settings = Repo.settings.value.copy()
        val policy = PreflightPolicy.from(settings)
        val bySource = sources.associateBy { it.subscription.id }
        val snapshots = Repo.servers.value.map { it.copy() }
        // A preserved old/standalone duplicate is not proof about a newly
        // refreshed feed; only representatives belonging to that feed qualify.
        val candidates = snapshots.filter { cfg ->
            cfg.hasUsableEndpoint() && bySource[cfg.subId]?.fingerprints?.contains(cfg.fingerprint()) == true
        }.distinctBy { it.fingerprint() }
        if (candidates.isEmpty()) return Result(Status.NO_PROFILES)
        val job = currentCoroutineContext()[Job]
        val tcp = HashMap<String, Int>()
        val observations = ArrayList<Repo.StartupObservation>()
        // Optional multi-destination/download tests are preflight tests, not
        // health housekeeping. They still run in full before any connection.
        val outcome = HealthScanner.scan(
            context = context,
            servers = candidates,
            settings = settings.copy(multiPing = false, speedTest = false),
            exhaustive = true,
            onTcp = { cfg, ping -> tcp[cfg.id] = ping },
            onReal = { cfg, result -> observations.add(Repo.StartupObservation(cfg, tcp[cfg.id] ?: -1, result)) },
            onIndeterminate = { cfg ->
                observations.add(Repo.StartupObservation(cfg, tcp[cfg.id] ?: -1, null, conclusive = false))
            },
            onPhase = onPhase
        )
        currentCoroutineContext().ensureActive()
        if (outcome.testedCount != candidates.size || observations.size != candidates.size) {
            return Result(Status.INCOMPLETE)
        }
        val applied = Repo.applyStartupHealth(sources, snapshots, observations, permit, token, stillCurrent = {
            job?.isActive != false && CarrierProbe.networkHandle(context) == network &&
                PreflightPolicy.from(Repo.settings.value) == policy
        }, networkValidated = validatedAtStart && CarrierProbe.internetValidated(context))
        return Result(if (applied.applied) Status.COMPLETE else Status.CHANGED, applied)
    }
}
