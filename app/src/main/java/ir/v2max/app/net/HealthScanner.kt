package ir.v2max.app.net

import android.content.Context
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.ConnectionTestBarrier
import ir.v2max.app.core.NativeDelay
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.PingTargets
import ir.v2max.app.data.ServerConfig
import ir.v2max.core.domain.ScanHint
import ir.v2max.core.domain.ScanOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/** TCP is only a scheduling hint; a server is healthy only after a real Xray request succeeds. */
object HealthScanner {
    private const val TCP_TIMEOUT_MS = 2200
    internal const val PRECHECK_WINDOW = 24
    internal const val REAL_BATCH_SIZE = 6
    private const val MULTI_CONCURRENCY = 4
    data class Outcome(
        val best: ServerConfig? = null,
        val aliveCount: Int = 0,
        /** Number of configurations subjected to real protocol tests. */
        val testedCount: Int = 0,
        /** Configurations prechecked, including UDP profiles for which TCP is not applicable. */
        val precheckedCount: Int = 0,
        /** Actual TCP handshakes; profiles with the same endpoint share a precheck. */
        val tcpConnections: Int = 0,
        val elapsedMs: Long = 0,
        val speedCompared: Boolean = false,
        /** IDs for which a download was actually attempted, including failures. */
        val speedTestedIds: Set<String> = emptySet(),
        val qualified: List<ServerConfig> = emptyList(),
        /** Failed setup or an unclassified native error is not a dead server. */
        val indeterminateIds: Set<String> = emptySet(),
        val message: String = ""
    )

    /** I/O boundary injectable in unit tests; the production path always uses Xray. */
    internal data class Operations(
        val tcp: suspend (ServerConfig) -> Int,
        val real: suspend (ServerConfig, AppSettings, String) -> Int,
        // Null means setup failed, not an attempted download with a zero result.
        val speed: suspend (ServerConfig, AppSettings) -> Int?,
        val problem: () -> String = { "" }
    )

    suspend fun scan(
        context: Context,
        servers: List<ServerConfig>,
        settings: AppSettings,
        preferredId: String? = null,
        rememberedId: String? = null,
        limit: Int = 0,
        exhaustive: Boolean = false,
        fastestRecentFirst: Boolean = false,
        onTcp: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onReal: suspend (ServerConfig, ProbeResult?) -> Unit = { _, _ -> },
        onIndeterminate: suspend (ServerConfig) -> Unit = { _ -> },
        onMulti: suspend (ServerConfig) -> Unit = { _ -> },
        onSpeed: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onPhase: suspend (String, Int, Int) -> Unit = { _, _, _ -> },
        onAlive: suspend (Int) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext Outcome(message = "کانفیگی برای تست وجود ندارد")
        ConnectionTestBarrier.tests {
            XrayCore.init(context)
            XrayCore.clearProbeProblem()
            scanPrepared(
                servers = servers,
                settings = settings,
                operations = Operations(
                    tcp = ::tcpPing,
                    real = ::realDelay,
                    speed = { cfg, variant -> SpeedTester.measure(cfg, variant) },
                    problem = XrayCore::probeProblem
                ),
                preferredId = preferredId,
                rememberedId = rememberedId,
                limit = limit,
                exhaustive = exhaustive,
                fastestRecentFirst = fastestRecentFirst,
                onTcp = onTcp,
                onReal = onReal,
                onIndeterminate = onIndeterminate,
                onMulti = onMulti,
                onSpeed = onSpeed,
                onPhase = onPhase,
                onAlive = onAlive
            )
        }
    }

    /**
     * Scan a small window at a time. Complete every started test in a batch and all
     * requested additional tests before returning. TCP failures are deferred, not
     * discarded; UDP and an explicitly selected profile are always tested for real.
     */
    internal suspend fun scanPrepared(
        servers: List<ServerConfig>,
        settings: AppSettings,
        operations: Operations,
        preferredId: String? = null,
        rememberedId: String? = null,
        limit: Int = 0,
        exhaustive: Boolean = false,
        fastestRecentFirst: Boolean = false,
        onTcp: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onReal: suspend (ServerConfig, ProbeResult?) -> Unit = { _, _ -> },
        onIndeterminate: suspend (ServerConfig) -> Unit = { _ -> },
        onMulti: suspend (ServerConfig) -> Unit = { _ -> },
        onSpeed: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onPhase: suspend (String, Int, Int) -> Unit = { _, _, _ -> },
        onAlive: suspend (Int) -> Unit = {}
    ): Outcome {
        val start = System.nanoTime()
        fun elapsed(): Long = (System.nanoTime() - start) / 1_000_000L
        // ServerConfig is mutable. Work with snapshots while a subscription or editor can update Repo.
        val usable = servers.map { it.copy() }.filter { it.hasUsableEndpoint() }
        if (usable.isEmpty()) return Outcome(message = "آدرس یا پورت کانفیگ‌ها معتبر نیست")
        val prioritized = ScanOrder.prioritize(
            items = usable,
            hint = {
                ScanHint(it.id, it.subId, it.favorite, it.testedAt, it.realPing, it.testedUrl.isNotBlank())
            },
            nowMs = System.currentTimeMillis(),
            preferredId = preferredId,
            rememberedId = rememberedId,
            fastestRecentFirst = fastestRecentFirst
        )
        val ordered = if (limit > 0) prioritized.take(limit) else prioritized
        val lock = Mutex()
        val tcpResults = HashMap<String, Int>()
        val endpointResults = HashMap<Pair<String, Int>, Int>()
        val deferred = ArrayList<ServerConfig>()
        val tested = ArrayList<ServerConfig>()
        val pendingReal = ArrayList<Pair<ServerConfig, ProbeResult?>>()
        val indeterminateIds = HashSet<String>()
        val pendingMulti = ArrayList<ServerConfig>()
        val pendingSpeed = ArrayList<Pair<ServerConfig, Int>>()
        var prechecked = 0
        var tcpConnections = 0
        var realDone = 0

        fun key(cfg: ServerConfig) = cfg.address.trim().lowercase() to cfg.port

        suspend fun precheck(window: List<ServerConfig>): List<ServerConfig> {
            onPhase("پیش‌بررسی مرحله‌ای آدرس‌ها", prechecked, ordered.size)
            val missing = linkedMapOf<Pair<String, Int>, ServerConfig>()
            for (cfg in window) {
                if (!ProbePlanner.usesUdp(cfg) && key(cfg) !in endpointResults) {
                    missing.putIfAbsent(key(cfg), cfg)
                }
            }
            val results = coroutineScope {
                missing.map { (endpoint, cfg) ->
                    async { endpoint to operations.tcp(cfg) }
                }.awaitAll()
            }
            results.forEach { (endpoint, ping) -> endpointResults[endpoint] = ping }
            tcpConnections += results.size
            val ready = ArrayList<ServerConfig>()
            for (cfg in window) {
                coroutineContext.ensureActive()
                val udp = ProbePlanner.usesUdp(cfg)
                val ping = if (udp) -1 else endpointResults[key(cfg)] ?: -1
                tcpResults[cfg.id] = ping
                onTcp(cfg, ping)
                prechecked++
                // An explicit selection must get its real test even when TCP fails.
                if (udp || ping > 0 || cfg.id == preferredId) ready.add(cfg) else deferred.add(cfg)
            }
            onPhase("پیش‌بررسی مرحله‌ای آدرس‌ها", prechecked, ordered.size)
            return ready
        }

        suspend fun verifyBatch(batch: List<ServerConfig>) {
            onPhase("تست واقعی اتصال با Xray", realDone, ordered.size)
            val batchResults = coroutineScope {
                batch.map { cfg ->
                    async {
                        val assessment = ProbePlanner.assess(cfg, settings, operations.real)
                        lock.withLock {
                            realDone++
                            onPhase("تست واقعی اتصال با Xray", realDone, ordered.size)
                        }
                        cfg to assessment
                    }
                }.awaitAll()
            }
            batchResults.forEach { (cfg, assessment) ->
                val result = assessment.result
                pendingReal.add(cfg to result)
                if (assessment.indeterminate) indeterminateIds.add(cfg.id)
                if (result != null) tested.add(
                    cfg.copy(
                        tcpPing = tcpResults[cfg.id] ?: -1,
                        realPing = result.delayMs,
                        googlePing = if (result.url == PingTargets.GOOGLE.url) result.delayMs else -1,
                        instagramPing = -1,
                        youtubePing = -1,
                        testedAt = System.currentTimeMillis(),
                        testedFragment = result.fragment,
                        testedFragmentProfile = result.profile,
                        testedUrl = result.url
                    )
                )
            }
            onAlive(tested.size)
        }

        // Publish verified results only after all tests requested for this scan finish.
        suspend fun publishResults() {
            coroutineContext.ensureActive()
            pendingReal.forEach { (cfg, result) ->
                if (cfg.id in indeterminateIds) onIndeterminate(cfg) else onReal(cfg, result)
            }
            pendingMulti.forEach { onMulti(it) }
            pendingSpeed.forEach { (cfg, kbps) -> onSpeed(cfg, kbps) }
        }

        var found = false
        for (window in ordered.chunked(PRECHECK_WINDOW)) {
            val ready = precheck(window)
            for (batch in ready.chunked(REAL_BATCH_SIZE)) {
                verifyBatch(batch)
                if (tested.isNotEmpty() && !exhaustive) {
                    found = true
                    break
                }
            }
            if (found) break
        }
        // A failed TCP check is not proof of failure. Reach these profiles if no
        // ready candidate succeeds, or always in an explicit full-list scan.
        if (exhaustive || tested.isEmpty()) {
            for (batch in deferred.chunked(REAL_BATCH_SIZE)) {
                verifyBatch(batch)
                if (tested.isNotEmpty() && !exhaustive) break
            }
        }
        coroutineContext.ensureActive()
        if (tested.isEmpty()) {
            publishResults()
            return Outcome(
                testedCount = realDone,
                precheckedCount = prechecked,
                tcpConnections = tcpConnections,
                elapsedMs = elapsed(),
                indeterminateIds = indeterminateIds.toSet(),
                message = buildString {
                    append("هیچ کانفیگی تست واقعی را نگذرانْد؛ کانفیگ‌ها حذف نشدند.")
                    val problem = operations.problem()
                    if (problem.isNotBlank()) append(" $problem")
                    else append(" شبکه، آدرس تست و تنظیمات سرور را بررسی کنید.")
                }
            )
        }

        val results = tested.toMutableList()
        if (settings.multiPing) {
            onPhase("بررسی مقصدهای بیشتر", 0, results.size)
            val multiSemaphore = Semaphore(MULTI_CONCURRENCY)
            var done = 0
            val multi = coroutineScope {
                results.map { cfg ->
                    async {
                        val variant = settings.copy(fragment = cfg.testedFragment, fragmentProfile = cfg.testedFragmentProfile)
                        val google = if (cfg.testedUrl == PingTargets.GOOGLE.url) cfg.realPing else
                            multiSemaphore.withPermit { operations.real(cfg, variant, PingTargets.GOOGLE.url) }
                        val ig = multiSemaphore.withPermit { operations.real(cfg, variant, PingTargets.INSTAGRAM.url) }
                        val yt = multiSemaphore.withPermit { operations.real(cfg, variant, PingTargets.YOUTUBE.url) }
                        val updated = cfg.copy(googlePing = google, instagramPing = ig, youtubePing = yt)
                        lock.withLock { done++; onPhase("بررسی مقصدهای بیشتر", done, results.size) }
                        updated
                    }
                }.awaitAll()
            }
            results.clear()
            results.addAll(multi)
            pendingMulti.addAll(multi)
        }

        val speeds = mutableMapOf<String, Int>()
        val speedTestedIds = mutableSetOf<String>()
        // An explicitly enabled test always runs, even for older settings where
        // dataSaver and speedTest were both true. Never silently bypass it.
        if (settings.speedTest) {
            val ranked = results.sortedWith(
                compareByDescending<ServerConfig> { it.id == preferredId }
                    .thenBy { ProbePlanner.latencyScore(it) }
            ).take(settings.speedTestCount.coerceIn(1, 5))
            onPhase("سنجش سرعت دانلود", 0, ranked.size)
            for ((index, cfg) in ranked.withIndex()) {
                coroutineContext.ensureActive()
                val variant = settings.copy(fragment = cfg.testedFragment, fragmentProfile = cfg.testedFragmentProfile)
                val kbps = operations.speed(cfg, variant)
                if (kbps == null) {
                    // A setup failure is not a completed download test. Preserve a
                    // running tunnel and do not make any candidate connectable.
                    return Outcome(
                        testedCount = realDone, precheckedCount = prechecked,
                        tcpConnections = tcpConnections, elapsedMs = elapsed(),
                        indeterminateIds = indeterminateIds.toSet(),
                        message = "آماده‌سازی سنجش سرعت ممکن نشد؛ اتصال جدید آغاز نشد. دوباره تلاش کنید."
                    )
                }
                speedTestedIds.add(cfg.id)
                if (kbps > 0) speeds[cfg.id] = kbps
                val position = results.indexOfFirst { it.id == cfg.id }
                if (position >= 0) results[position] = results[position].copy(speedKbps = kbps)
                pendingSpeed.add(cfg to kbps)
                onPhase("سنجش سرعت دانلود", index + 1, ranked.size)
            }
        }
        coroutineContext.ensureActive()
        publishResults()
        // When speed testing is enabled, only profiles whose download test was
        // actually attempted may be selected or added to the connection pool.
        val connectable = if (settings.speedTest) results.filter { it.id in speedTestedIds } else results
        val ranked = ProbePlanner.rank(connectable, speeds)
        // A low-latency switch uses freshly measured real delays, not TCP or a
        // persisted score. Respect an enabled download comparison and a manual choice.
        val qualified = if (fastestRecentFirst && !settings.speedTest) ranked.sortedBy { it.realPing } else ranked
        val best = qualified.firstOrNull { it.id == preferredId } ?: qualified.firstOrNull()
        val speedNote = when {
            settings.speedTest && speeds.isEmpty() -> "سنجش دانلود ناموفق بود؛ انتخاب بر اساس تأخیر انجام شد"
            speeds.isNotEmpty() -> "سرعت دانلود ${speeds.size} سرور سنجیده شد"
            else -> "انتخاب بر اساس تأخیر است، نه سرعت دانلود"
        }
        return Outcome(
            best = best,
            aliveCount = qualified.size,
            testedCount = realDone,
            precheckedCount = prechecked,
            tcpConnections = tcpConnections,
            elapsedMs = elapsed(),
            speedCompared = speeds.isNotEmpty(),
            speedTestedIds = speedTestedIds,
            qualified = qualified,
            indeterminateIds = indeterminateIds.toSet(),
            message = speedNote
        )
    }

    suspend fun tcpPing(cfg: ServerConfig): Int = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            Socket().use { it.connect(InetSocketAddress(cfg.address, cfg.port), TCP_TIMEOUT_MS) }
            ((System.nanoTime() - start) / 1_000_000L).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            -1
        }
    }

    suspend fun realDelay(cfg: ServerConfig, settings: AppSettings, url: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val config = ConfigBuilder.testConfig(cfg, settings)
                val delay = XrayCore.measureOutboundDelay(config, url)
                when (delay) {
                    in 1..60_000 -> delay.toInt()
                    NativeDelay.NO_RESPONSE -> NativeDelay.NO_RESPONSE.toInt()
                    else -> NativeDelay.INDETERMINATE.toInt()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                NativeDelay.INDETERMINATE.toInt()
            }
        }

    suspend fun realDelay(cfg: ServerConfig, settings: AppSettings): Int =
        realDelay(cfg, settings, settings.delayTestUrl)
}
