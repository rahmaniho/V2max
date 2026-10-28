package ir.v2max.app.net

import android.content.Context
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * اسکن سلامت کانفیگ‌ها در دو مرحله:
 *  ۱) تست سریع TCP (بدون هسته) برای حذف سرورهای خاموش
 *  ۲) تست تأخیر واقعی از طریق پروتکل (با هسته‌ی Xray) برای کانفیگ‌های سالم
 */
object HealthScanner {

    private const val TCP_TIMEOUT_MS = 2200
    private const val TCP_CONCURRENCY = 40
    private const val REAL_CONCURRENCY = 6
    private const val MAX_REAL_TESTS = 24

    data class Outcome(
        val best: ServerConfig? = null,
        val aliveCount: Int = 0,
        val testedCount: Int = 0,
        val message: String = ""
    )

    /**
     * @param onTcp تکمیل تست TCP یک سرور
     * @param onReal تکمیل تست واقعی یک سرور
     * @param onPhase تغییر مرحله (متن وضعیت)
     */
    suspend fun scan(
        context: Context,
        servers: List<ServerConfig>,
        settings: AppSettings,
        onTcp: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onReal: suspend (ServerConfig, Int) -> Unit = { _, _ -> },
        onPhase: suspend (String, Int, Int) -> Unit = { _, _, _ -> },
        onAlive: suspend (Int) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext Outcome(message = "کانفیگی برای تست وجود ندارد")

        runCatching { XrayCore.init(context) }

        // ---------- مرحله ۱: TCP
        val tcpResults = HashMap<String, Int>()
        val candidates = ArrayList<ServerConfig>()
        val mutex = kotlinx.coroutines.sync.Mutex()

        onPhase("بررسی سریع اتصال سرورها", 0, servers.size)

        val semaphore = Semaphore(TCP_CONCURRENCY)
        var doneCounter = 0

        coroutineScope {
            servers.map { cfg ->
                async {
                    coroutineContext.ensureActive()
                    val ping = semaphore.withPermit { tcpPing(cfg) }
                    mutex.lock()
                    try {
                        doneCounter++
                        tcpResults[cfg.id] = ping
                        if (ping > 0) candidates.add(cfg)
                        if (doneCounter % 4 == 0 || doneCounter == servers.size) {
                            onPhase("بررسی سریع اتصال سرورها", doneCounter, servers.size)
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

        if (candidates.isEmpty()) {
            return@withContext Outcome(
                testedCount = servers.size,
                message = "هیچ‌کدام از سرورها در دسترس نبودند. اتصال اینترنت یا وضعیت سرورها را بررسی کنید."
            )
        }

        // ---------- مرحله ۲: تأخیر واقعی
        val ordered = candidates.sortedBy { tcpResults[it.id] ?: Int.MAX_VALUE }
        val realTargets = ordered.take(MAX_REAL_TESTS)
        val realResults = HashMap<String, Int>()

        onPhase("اندازه‌گیری سرعت پاسخ واقعی", 0, realTargets.size)

        val realSemaphore = Semaphore(REAL_CONCURRENCY)
        var realDone = 0
        coroutineScope {
            realTargets.map { cfg ->
                async {
                    coroutineContext.ensureActive()
                    val delay = realSemaphore.withPermit { realDelay(cfg, settings) }
                    mutex.lock()
                    try {
                        realDone++
                        realResults[cfg.id] = delay
                        onPhase("اندازه‌گیری سرعت پاسخ واقعی", realDone, realTargets.size)
                    } finally {
                        mutex.unlock()
                    }
                    onReal(cfg, delay)
                    delay
                }
            }.awaitAll()
        }

        // ---------- انتخاب بهترین
        val best = ordered
            .filter { (realResults[it.id] ?: -1) > 0 }
            .minByOrNull { (realResults[it.id] ?: Int.MAX_VALUE).toLong() * 1000L + it.bestPing().coerceAtLeast(0) }
            ?: ordered.firstOrNull()

        val aliveTotal = candidates.size
        Outcome(
            best = best,
            aliveCount = aliveTotal,
            testedCount = servers.size,
            message = if (best == null) "سرور سالمی یافت نشد" else "بهترین سرور انتخاب شد"
        )
    }

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

    /** تست تأخیر واقعی از طریق هسته؛ خروجی میلی‌ثانیه یا -۱ */
    suspend fun realDelay(cfg: ServerConfig, settings: AppSettings): Int =
        withContext(Dispatchers.IO) {
            try {
                val config = ConfigBuilder.testConfig(cfg, settings)
                val delay = XrayCore.measureOutboundDelay(config, settings.delayTestUrl)
                if (delay in 1..60000) delay.toInt() else -1
            } catch (t: Throwable) {
                -1
            }
        }
}
