package ir.v2max.app.core

import android.content.Context
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.net.HealthScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * مغز متفکر «اتصال هوشمند»:
 * همه‌ی کانفیگ‌ها را می‌سنجد، سالم‌ها را نگه می‌دارد، سریع‌ترین را انتخاب می‌کند و وصل می‌شود.
 */
object SmartConnect {

    private val scanMutex = Mutex()

    /** اسکن کامل + انتخاب بهترین + (اختیاری) اتصال */
    suspend fun scanAndConnect(
        context: Context,
        autoConnect: Boolean = true,
        subscriptionId: String? = null,
        limit: Int = 0
    ): ServerConfig? = scanMutex.withLock {
        val all = withContext(Dispatchers.Default) {
            Repo.servers.value.filter { subscriptionId == null || it.subId == subscriptionId }
        }
        // اولویت‌بندی: علاقه‌مندی‌ها، سپس سرورهایی که قبلاً سالم بوده‌اند
        val servers = if (limit > 0 && all.size > limit) {
            all.sortedWith(
                compareByDescending<ServerConfig> { it.favorite }
                    .thenByDescending { it.bestPing() > 0 }
                    .thenBy { if (it.bestPing() > 0) it.bestPing() else Int.MAX_VALUE }
            ).take(limit)
        } else all
        if (servers.isEmpty()) {
            Repo.scan.value = ScanState(
                running = false,
                finished = true,
                message = "هیچ کانفیگی وجود ندارد. ابتدا یک اشتراک اضافه کنید."
            )
            return@withLock null
        }

        Repo.scan.value = ScanState(
            running = true,
            total = servers.size,
            phase = "شروع اسکن هوشمند",
            message = "در حال بررسی ${servers.size} کانفیگ"
        )

        val settings = Repo.settings.value
        val outcome = try {
            HealthScanner.scan(
                context = context,
                servers = servers,
                settings = settings,
                onTcp = { cfg, ping ->
                    Repo.setTestResult(cfg.id, ping, -1, persist = false)
                },
                onReal = { cfg, delay ->
                    val current = Repo.serverById(cfg.id)
                    Repo.setTestResult(cfg.id, current?.tcpPing ?: -1, delay, persist = false)
                },
                onPhase = { phase, done, total ->
                    Repo.scan.value = Repo.scan.value.copy(phase = phase, done = done, total = total)
                },
                onAlive = { alive ->
                    Repo.scan.value = Repo.scan.value.copy(alive = alive)
                }
            )
        } catch (t: Throwable) {
            Repo.scan.value = Repo.scan.value.copy(
                running = false,
                finished = true,
                message = "اسکن متوقف شد: ${t.message ?: "خطا"}"
            )
            return@withLock null
        }

        Repo.persistNow()

        val best = outcome.best
        Repo.scan.value = Repo.scan.value.copy(
            running = false,
            finished = true,
            alive = outcome.aliveCount,
            done = outcome.testedCount,
            total = outcome.testedCount,
            bestName = best?.displayName().orEmpty(),
            phase = "پایان",
            finishedAt = System.currentTimeMillis(),
            message = when {
                best == null -> outcome.message
                else -> "بهترین سرور: ${best.displayName()} (${best.bestPing()}ms)"
            }
        )

        if (best != null) {
            val tunnel = Repo.tunnel.value
            val current = Repo.serverById(tunnel.serverId)
            val connected = tunnel.status == TunnelStatus.CONNECTED
            val sameAsRunning = connected && current?.id == best.id
            // اتصال فعلی تقریباً هم‌سطح بهترین است؛ برای جلوگیری از قطعی سوییچ نمی‌کنیم
            val keepCurrent = autoConnect && connected && current != null &&
                !sameAsRunning && current.bestPing() > 0 &&
                current.bestPing() <= best.bestPing() * 1.35f

            when {
                sameAsRunning -> Repo.setSelected(best.id)
                keepCurrent -> Unit
                else -> {
                    Repo.setSelected(best.id)
                    if (autoConnect) TunnelController.start(context, best.id)
                }
            }
        }
        return@withLock best
    }

    /** اتصال سریع: اگر سرور انتخاب‌شده سالم است وصل شو، وگرنه اسکن کن */
    suspend fun quickConnect(context: Context) {
        val selected = Repo.selectedServer()
        val fresh = selected != null &&
            selected.bestPing() > 0 &&
            System.currentTimeMillis() - selected.testedAt < 10 * 60 * 1000L

        if (selected != null && fresh) {
            TunnelController.start(context, selected.id)
            return
        }

        val candidate = if (selected != null) {
            val tcp = HealthScanner.tcpPing(selected)
            Repo.setTestResult(selected.id, tcp, -1)
            if (tcp > 0) selected else null
        } else null

        if (candidate != null) {
            TunnelController.start(context, candidate.id)
        } else {
            scanAndConnect(context, autoConnect = true)
        }
    }

    /**
     * شروع سریع: اگر سرور سالم و تازه‌ای داریم همین حالا وصل می‌شویم
     * تا کاربر منتظر اسکن کامل نماند.
     */
    fun fastStart(context: Context): Boolean {
        if (Repo.tunnel.value.isActive) return true
        val candidate = Repo.healthyServers(20 * 60 * 1000L).firstOrNull()
            ?: Repo.selectedServer()?.takeIf { it.bestPing() > 0 }
        candidate ?: return false
        Repo.setSelected(candidate.id)
        TunnelController.start(context, candidate.id)
        return true
    }

    /** بررسی دوره‌ای سلامت سرور انتخابی (برای اجرا هنگام باز شدن برنامه) */
    suspend fun refreshSelectedIfNeeded(context: Context) {
        val selected = Repo.selectedServer() ?: return
        if (System.currentTimeMillis() - selected.testedAt < 5 * 60 * 1000L) return
        val tcp = HealthScanner.tcpPing(selected)
        Repo.setTestResult(selected.id, tcp, selected.realPing)
    }
}
