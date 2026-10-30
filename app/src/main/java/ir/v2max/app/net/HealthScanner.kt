package ir.v2max.app.net

import android.content.Context
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.CountryFlags
import ir.v2max.app.data.PingTargets
import ir.v2max.app.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * اسکن سلامت کانفیگ‌ها:
 *  ۱) تست سریع TCP (بدون هسته) برای حذف سرورهای خاموش
 *  ۲) تست تأخیر واقعی از طریق پروتکل با هسته‌ی Xray
 *  ۳) تست چندمقصدی: گوگل، اینستاگرام و یوتیوب — سروری که به هیچ‌کدام پاسخ ندهد کنار گذاشته می‌شود
 */
object HealthScanner {

    private const val TCP_TIMEOUT_MS = 2200
    private const val TCP_CONCURRENCY = 24
    private const val REAL_CONCURRENCY = 6
    private const val MAX_REAL_TESTS = 24

    /** چند سرور برتر برای تست اینستاگرام/یوتیوب بررسی می‌شوند */
    private const val MAX_MULTI_TESTS = 24
    private const val MULTI_CONCURRENCY = 4

    data class Outcome(
        val best: ServerConfig? = null,
        val aliveCount: Int = 0,
        val testedCount: Int = 0,
        val deadIds: List<String> = emptyList(),
        val qualified: List<ServerConfig> = emptyList(),
        val message: String = ""
    )

    /**
     * @param onTcp تکمیل تست TCP یک سرور
     * @param onReal تکمیل تأخیر واقعی (گوگل) یک سرور
     * @param onMulti تکمیل تست چندمقصدی یک سرور
     * @param onPhase تغییر مرحله (متن وضعیت)
     */
    suspend fun scan(
        context: Context,
        servers: List<ServerConfig>,
        settings: AppSettings,
        onTcp: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onReal: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onMulti: suspend (ServerConfig) -> Unit = { _ -> },
        onSpeed: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onPhase: suspend (String, Int, Int) -> Unit = { _, _, _ -> },
        onAlive: suspend (Int) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext Outcome(message = "کانفیگی برای تست وجود ندارد")

        runCatching { XrayCore.init(context) }

        val usable = servers.filter { it.hasUsableEndpoint() }
        val invalid = servers.filterNot { it.hasUsableEndpoint() }.map { it.id }
        if (usable.isEmpty()) {
            return@withContext Outcome(
                testedCount = servers.size,
                deadIds = invalid,
                message = "هیچ کانفیگ معتبری برای تست وجود ندارد"
            )
        }

        // ---------- مرحله ۱: TCP
        val tcpResults = HashMap<String, Int>()
        val candidates = ArrayList<ServerConfig>()
        val mutex = Mutex()

        onPhase("بررسی سریع اتصال سرورها", 0, usable.size)

        val semaphore = Semaphore(TCP_CONCURRENCY)
        var doneCounter = 0

        coroutineScope {
            usable.map { cfg ->
                async {
                    coroutineContext.ensureActive()
                    val ping = semaphore.withPermit { tcpPing(cfg) }
                    mutex.lock()
                    try {
                        doneCounter++
                        tcpResults[cfg.id] = ping
                        if (ping > 0) candidates.add(cfg)
                        if (doneCounter % 4 == 0 || doneCounter == usable.size) {
                            onPhase("بررسی سریع اتصال سرورها", doneCounter, usable.size)
                            onAlive(candidates.size)
                        }
                    } finally {
                        mutex.unlock()
                    }
                    onTcp(cfg, ping)
                    ping
                }
            }.awaitAll()
        }

        // سرورهای خاموش TCP
        val deadIds = ArrayList<String>(invalid)
        usable.filter { (tcpResults[it.id] ?: -1) <= 0 }.forEach { deadIds.add(it.id) }

        if (candidates.isEmpty()) {
            return@withContext Outcome(
                testedCount = servers.size,
                deadIds = deadIds,
                message = "هیچ‌کدام از سرورها در دسترس نبودند. اتصال اینترنت یا وضعیت سرورها را بررسی کنید."
            )
        }

        // ---------- مرحله ۲: تأخیر واقعی (گوگل)
        val ordered = candidates.sortedBy { tcpResults[it.id] ?: Int.MAX_VALUE }
        val realTargets = ordered.take(MAX_REAL_TESTS)
        val realResults = HashMap<String, Int>()

        onPhase("اندازه‌گیری پینگ واقعی (گوگل)", 0, realTargets.size)

        val realSemaphore = Semaphore(REAL_CONCURRENCY)
        var realDone = 0
        coroutineScope {
            realTargets.map { cfg ->
                async {
                    coroutineContext.ensureActive()
                    val delay = realSemaphore.withPermit { realDelay(cfg, settings, PingTargets.GOOGLE.url) }
                    mutex.lock()
                    try {
                        realDone++
                        realResults[cfg.id] = delay
                        onPhase("اندازه‌گیری پینگ واقعی (گوگل)", realDone, realTargets.size)
                    } finally {
                        mutex.unlock()
                    }
                    onReal(cfg, delay)
                    delay
                }
            }.awaitAll()
        }

        // ---------- مرحله ۳: چندمقصدی (اینستاگرام + یوتیوب)
        val multiQualified = LinkedHashMap<String, ServerConfig>()

        if (settings.multiPing) {
            val reachable = ordered.filter { (realResults[it.id] ?: -1) > 0 }.take(MAX_MULTI_TESTS)
            if (reachable.isNotEmpty()) {
                onPhase("تست اینستاگرام و یوتیوب", 0, reachable.size)
                val multiSemaphore = Semaphore(MULTI_CONCURRENCY)
                var multiDone = 0
                coroutineScope {
                    reachable.map { cfg ->
                        async {
                            coroutineContext.ensureActive()
                            val ig = multiSemaphore.withPermit {
                                realDelay(cfg, settings, PingTargets.INSTAGRAM.url)
                            }
                            val yt = multiSemaphore.withPermit {
                                realDelay(cfg, settings, PingTargets.YOUTUBE.url)
                            }
                            mutex.lock()
                            try {
                                multiDone++
                                onPhase("تست اینستاگرام و یوتیوب", multiDone, reachable.size)
                            } finally {
                                mutex.unlock()
                            }
                            onMulti(cfg.apply { instagramPing = ig; youtubePing = yt })
                            ig to yt
                        }
                    }.awaitAll()
                }

                // سروری که گوگل جواب داده اما هیچ‌کدام از دو مقصد دیگر هم جواب نداده، معیوب است
                ordered.forEach { cfg ->
                    val g = realResults[cfg.id] ?: -1
                    if (g > 0 && cfg.reachCount() >= 2) multiQualified[cfg.id] = cfg
                }
            }
        }

        // ---------- مرحله ۴: سنجش سرعت دانلود واقعی برای چند سرور برتر
        if (settings.speedTest && !settings.dataSaver) {
            val ranked = ordered
                .filter { (realResults[it.id] ?: -1) > 0 }
                .sortedBy { scoreOf(it) }
                .take(settings.speedTestCount.coerceIn(1, 5))
            if (ranked.isNotEmpty()) {
                onPhase("سنجش سرعت دانلود واقعی", 0, ranked.size)
                var speedDone = 0
                ranked.forEach { cfg ->
                    coroutineContext.ensureActive()
                    val kbps = SpeedTester.measure(cfg, settings.copy(fragment = settings.fragment))
                    mutex.lock()
                    try {
                        speedDone++
                        onPhase("سنجش سرعت دانلود واقعی", speedDone, ranked.size)
                    } finally {
                        mutex.unlock()
                    }
                    cfg.speedKbps = kbps
                    onSpeed(cfg, kbps)
                }
            }
        }

        // ---------- انتخاب بهترین
        val scored = ordered.filter { (realResults[it.id] ?: -1) > 0 }
        val best = when {
            multiQualified.isNotEmpty() -> multiQualified.values.minByOrNull { scoreOf(it) }
            scored.isNotEmpty() -> scored.minByOrNull { scoreOf(it) }
            else -> ordered.firstOrNull()
        }

        // سرورهایی که تستشان انجام شد ولی به هیچ مقصدی پاسخ ندادند → حذف می‌شوند
        val failedReal = realTargets
            .filter { (realResults[it.id] ?: -1) <= 0 }
            .map { it.id }
        deadIds.addAll(failedReal)

        val qualifiedList = (if (multiQualified.isNotEmpty()) multiQualified.values.toList() else scored)
            .sortedBy { scoreOf(it) }

        Outcome(
            best = best,
            aliveCount = candidates.size,
            testedCount = servers.size,
            deadIds = deadIds,
            qualified = qualifiedList,
            message = when {
                best == null -> "سرور سالمی یافت نشد"
                else -> "بهترین سرور انتخاب شد"
            }
        )
    }

    /** امتیاز کمتر = بهتر؛ ترکیب تأخیر، تعداد مقصدهای پاسخ‌دهنده، سرعت و پایداری */
    private fun scoreOf(cfg: ServerConfig): Long = cfg.score().toLong()

    /** تست TCP؛ خروجی میلی‌ثانیه یا -۱ */
    suspend fun tcpPing(cfg: ServerConfig): Int = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            val address = InetSocketAddress(cfg.address, cfg.port)
            socket = Socket()
            socket.connect(address, TCP_TIMEOUT_MS)
            val elapsed = (System.currentTimeMillis() - start).toInt()
            if (elapsed <= 0) 1 else elapsed
        } catch (t: Throwable) {
            -1
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** تست تأخیر واقعی از طریق هسته برای یک URL مشخص؛ خروجی میلی‌ثانیه یا -۱ */
    suspend fun realDelay(cfg: ServerConfig, settings: AppSettings, url: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val config = ConfigBuilder.testConfig(cfg, settings)
                val delay = XrayCore.measureOutboundDelay(config, url)
                if (delay in 1..60000) delay.toInt() else -1
            } catch (t: Throwable) {
                -1
            }
        }

    /** نسخه‌ی سازگار با کد قبلی: تأخیر واقعی نسبت به مقصد پیش‌فرض */
    suspend fun realDelay(cfg: ServerConfig, settings: AppSettings): Int =
        realDelay(cfg, settings, settings.delayTestUrl)
}
