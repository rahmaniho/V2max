package ir.v2max.app.net

import android.content.Context
import ir.v2max.app.core.PreflightPolicy
import ir.v2max.app.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Refresh comes first; this is a protocol health check, not permission to connect. */
internal object StartupMaintenance {
    enum class Status { COMPLETE, NO_NETWORK, NO_PROFILES, INCOMPLETE, CHANGED }
    data class Result(val status: Status, val health: Repo.StartupHealthResult = Repo.StartupHealthResult())

    // Register the full refresh + health phase, including time waiting for the
    // shared sync gate. Recovery must not wait behind thousands of old probes.
    // Handover still waits for any already-started native calls at the barrier.
    private val jobs = HashSet<Job>()

    suspend fun <T> track(block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        if (job != null) synchronized(jobs) { jobs.add(job) }
        try {
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

    suspend fun check(
        context: Context,
        sources: List<Repo.StartupSourceSnapshot>,
        permit: Repo.AutomaticRefreshPermit,
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
        try {
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
        } catch (e: CancellationException) {
            throw e
        }
    }
}
