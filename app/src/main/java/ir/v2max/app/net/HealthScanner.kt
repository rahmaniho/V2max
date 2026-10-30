package ir.v2max.app.net

import android.content.Context
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.PingTargets
import ir.v2max.app.data.ServerConfig
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

/** TCP is only a preliminary check; a server is healthy only after a real Xray request succeeds. */
object HealthScanner {
    private const val TCP_TIMEOUT_MS = 2200
    private const val TCP_CONCURRENCY = 24
    private const val REAL_CONCURRENCY = 6
    private const val REAL_BATCH_SIZE = 24
    private const val MULTI_CONCURRENCY = 4

    data class Outcome(
        val best: ServerConfig? = null,
        val aliveCount: Int = 0,
        /** Number of actual protocol tests, not just TCP checks. */
        val testedCount: Int = 0,
        val precheckedCount: Int = 0,
        val qualified: List<ServerConfig> = emptyList(),
        val message: String = ""
    )

    suspend fun scan(
        context: Context,
        servers: List<ServerConfig>,
        settings: AppSettings,
        preferredId: String? = null,
        onTcp: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onReal: suspend (ServerConfig, ProbeResult?) -> Unit = { _, _ -> },
        onMulti: suspend (ServerConfig) -> Unit = { _ -> },
        onSpeed: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onPhase: suspend (String, Int, Int) -> Unit = { _, _, _ -> },
        onAlive: suspend (Int) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext Outcome(message = "کانفیگی برای تست وجود ندارد")
        XrayCore.init(context)
        XrayCore.clearProbeProblem()
        val usable = servers.filter { it.hasUsableEndpoint() }
        if (usable.isEmpty()) return@withContext Outcome(message = "آدرس یا پورت کانفیگ‌ها معتبر نیست")

        val lock = Mutex()
        val tcpResults = HashMap<String, Int>()
        val tcpSemaphore = Semaphore(TCP_CONCURRENCY)
        var prechecked = 0
        onPhase("پیش‌بررسی آدرس‌ها (TCP و UDP)", 0, usable.size)
        coroutineScope {
            usable.map { cfg ->
                async {
                    coroutineContext.ensureActive()
                    // UDP protocols cannot be declared dead from a TCP failure.
                    val ping = if (ProbePlanner.usesUdp(cfg)) -1 else tcpSemaphore.withPermit { tcpPing(cfg) }
                    onTcp(cfg, ping)
                    lock.withLock {
                        tcpResults[cfg.id] = ping
                        prechecked++
                        if (prechecked % 4 == 0 || prechecked == usable.size) {
                            onPhase("پیش‌بررسی آدرس‌ها (TCP و UDP)", prechecked, usable.size)
                        }
                    }
                }
            }.awaitAll()
        }

        // TCP errors are lower-priority, not proof of death. Explicit choices are tested first.
        val ordered = usable.sortedWith(
            compareByDescending<ServerConfig> { it.id == preferredId }
                .thenByDescending { it.favorite }
                .thenBy { if (ProbePlanner.usesUdp(it) || (tcpResults[it.id] ?: -1) > 0) 0 else 1 }
                .thenBy { tcpResults[it.id]?.takeIf { ping -> ping > 0 } ?: Int.MAX_VALUE }
        )
        val tested = ArrayList<ServerConfig>()
        val realSemaphore = Semaphore(REAL_CONCURRENCY)
        var realDone = 0
        onPhase("تست واقعی اتصال با Xray", 0, ordered.size)

        // Work in bounded batches. If one batch yields no real success, try the next;
        // never discard a whole subscription because its first 24 nodes were down.
        for (batch in ordered.chunked(REAL_BATCH_SIZE)) {
            val results = coroutineScope {
                batch.map { cfg ->
                    async {
                        val result = realSemaphore.withPermit {
                            ProbePlanner.verify(cfg, settings, ::realDelay)
                        }
                        onReal(cfg, result)
                        lock.withLock {
                            realDone++
                            onPhase("تست واقعی اتصال با Xray", realDone, ordered.size)
                        }
                        result?.let {
                            cfg.copy(
                                tcpPing = tcpResults[cfg.id] ?: -1,
                                realPing = it.delayMs,
                                googlePing = if (it.url == PingTargets.GOOGLE.url) it.delayMs else -1,
                                instagramPing = -1,
                                youtubePing = -1,
                                testedAt = System.currentTimeMillis(),
                                testedFragment = it.fragment,
                                testedFragmentProfile = it.profile,
                                testedUrl = it.url
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            tested.addAll(results)
            onAlive(tested.size)
            if (tested.isNotEmpty()) break
        }
        if (tested.isEmpty()) return@withContext Outcome(
            testedCount = realDone,
            precheckedCount = prechecked,
            message = buildString {
                append("هیچ کانفیگی تست واقعی را نگذرانْد؛ کانفیگ‌ها حذف نشدند.")
                val problem = XrayCore.probeProblem()
                if (problem.isNotBlank()) append(" $problem")
                else append(" شبکه، آدرس تست و تنظیمات سرور را بررسی کنید.")
            }
        )

        val results = tested.toMutableList()
        if (settings.multiPing) {
            onPhase("بررسی مقصدهای بیشتر", 0, results.size)
            val multiSemaphore = Semaphore(MULTI_CONCURRENCY)
            var done = 0
            val multi = coroutineScope {
                results.map { cfg ->
                    async {
                        val variant = settings.copy(fragment = cfg.testedFragment, fragmentProfile = cfg.testedFragmentProfile)
                        val ig = multiSemaphore.withPermit { realDelay(cfg, variant, PingTargets.INSTAGRAM.url) }
                        val yt = multiSemaphore.withPermit { realDelay(cfg, variant, PingTargets.YOUTUBE.url) }
                        val updated = cfg.copy(instagramPing = ig, youtubePing = yt)
                        onMulti(updated)
                        lock.withLock { done++; onPhase("بررسی مقصدهای بیشتر", done, results.size) }
                        updated
                    }
                }.awaitAll()
            }
            results.clear()
            results.addAll(multi)
        }

        if (settings.speedTest && !settings.dataSaver && !XrayCore.isRunning) {
            val ranked = results.sortedBy { it.score() }.take(settings.speedTestCount.coerceIn(1, 5))
            onPhase("سنجش سرعت دانلود", 0, ranked.size)
            ranked.forEachIndexed { index, cfg ->
                coroutineContext.ensureActive()
                val variant = settings.copy(fragment = cfg.testedFragment, fragmentProfile = cfg.testedFragmentProfile)
                val kbps = SpeedTester.measure(cfg, variant)
                val position = results.indexOfFirst { it.id == cfg.id }
                if (position >= 0) results[position] = results[position].copy(speedKbps = kbps)
                onSpeed(cfg, kbps)
                onPhase("سنجش سرعت دانلود", index + 1, ranked.size)
            }
        }

        val qualified = results.sortedBy { it.score() }
        val best = ProbePlanner.choose(qualified, preferredId)
        Outcome(
            best = best,
            aliveCount = qualified.size,
            testedCount = realDone,
            precheckedCount = prechecked,
            qualified = qualified,
            message = "${qualified.size} کانفیگ تست واقعی را گذراند"
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
                if (delay in 1..60_000) delay.toInt() else -1
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                -1
            }
        }

    suspend fun realDelay(cfg: ServerConfig, settings: AppSettings): Int =
        realDelay(cfg, settings, settings.delayTestUrl)
}
