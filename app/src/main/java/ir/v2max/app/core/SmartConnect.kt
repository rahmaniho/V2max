package ir.v2max.app.core

import android.content.Context
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.net.CarrierProbe
import ir.v2max.app.net.HealthScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * مغز متفکر «اتصال هوشمند»:
 * همه‌ی کانفیگ‌ها را می‌سنجد (گوگل/اینستاگرام/یوتیوب)، سالم‌ها را نگه می‌دارد،
 * سرورهای مرده را حذف می‌کند و سریع‌ترین گزینه را انتخاب و وصل می‌شود.
 */
object SmartConnect {

    private val scanMutex = Mutex()
    private val probeMutex = Mutex()

    /** بررسی عمیق شبکه (اپراتور، DNS، DPI، تأخیر/لرزش) و انتشار نتیجه برای رابط کاربری */
    suspend fun assessNetwork(context: Context, force: Boolean = false) {
        if (!Repo.settings.value.deepProbe) return
        probeMutex.withLock {
            if (!force && Repo.network.value.fresh) return@withLock
            Repo.scan.value = Repo.scan.value.copy(
                phase = "بررسی عمیق شبکه‌ی اپراتور",
                message = "سنجش DNS، DPI، تأخیر و لرزش شبکه…"
            )
            val result = runCatching { CarrierProbe.assess(context) }.getOrNull() ?: return@withLock
            Repo.network.value = result
        }
    }

    /** اسکن کامل + حذف سرورهای مرده + انتخاب بهترین + (اختیاری) اتصال */
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

        // پیش از هر تستی، شبکه‌ی اپراتور بررسی می‌شود تا فرگمنت/MTU درست انتخاب شود
        assessNetwork(context)

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
                    Repo.setTestResult(
                        cfg.id,
                        current?.tcpPing ?: -1,
                        delay,
                        persist = false
                    )
                    Repo.setMultiPing(
                        cfg.id,
                        google = delay,
                        instagram = Repo.serverById(cfg.id)?.instagramPing ?: -1,
                        youtube = Repo.serverById(cfg.id)?.youtubePing ?: -1,
                        persist = false
                    )
                },
                onSpeed = { cfg, kbps ->
                    Repo.setSpeed(cfg.id, kbps, persist = false)
                },
                onMulti = { cfg ->
                    Repo.setMultiPing(
                        cfg.id,
                        google = cfg.googlePing,
                        instagram = cfg.instagramPing,
                        youtube = cfg.youtubePing,
                        persist = false
                    )
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

        // حذف سرورهایی که به هیچ مقصدی پاسخ ندادند
        // محافظ: اگر هیچ سروری سالم نبود یعنی مشکل از شبکه‌ی کاربر است، پس چیزی حذف نمی‌شود
        var removed = 0
        val safeToPrune = outcome.qualified.isNotEmpty() || outcome.best != null
        if (settings.dropDeadServers && safeToPrune && outcome.deadIds.isNotEmpty()) {
            val protected = Repo.servers.value
                .filter { it.id in outcome.deadIds && it.favorite }
                .map { it.id }
            val toRemove = outcome.deadIds.filterNot { it in protected }
            if (toRemove.isNotEmpty()) {
                Repo.removeServers(toRemove)
                removed = toRemove.size
            }
        }

        Repo.persistNow()

        val best = outcome.best?.let { Repo.serverById(it.id) } ?: outcome.best
        Repo.scan.value = Repo.scan.value.copy(
            running = false,
            finished = true,
            finishedAt = System.currentTimeMillis(),
            alive = outcome.qualified.size,
            done = outcome.testedCount,
            total = outcome.testedCount,
            bestName = best?.displayName().orEmpty(),
            phase = "پایان",
            message = when {
                best == null -> outcome.message
                else -> buildString {
                    append("بهترین سرور: ${best.displayName()} (${best.bestPing()}ms)")
                    if (removed > 0) append(" · ${removed} سرور مرده حذف شد")
                }
            }
        )

        // حافظه‌ی شبکه‌های آشنا: اگر سرور ذخیره‌شده‌ی همین شبکه تقریباً هم‌سطح بهترین بود، همان انتخاب می‌شود
        val assessment = Repo.network.value
        val profileKey = profileKeyOf(assessment)
        val remembered = if (settings.rememberNetworks && profileKey.isNotBlank()) {
            Repo.profileFor(profileKey)?.serverId?.let { Repo.serverById(it) }
        } else null

        val chosen = when {
            best == null -> null
            remembered != null && remembered.bestPing() > 0 &&
                remembered.score() <= best.score() + 60 -> remembered
            else -> best
        }

        if (chosen != null) {
            val tunnel = Repo.tunnel.value
            val current = Repo.serverById(tunnel.serverId)
            val connected = tunnel.status == TunnelStatus.CONNECTED
            val sameAsRunning = connected && current?.id == chosen.id
            // اتصال فعلی تقریباً هم‌سطح بهترین است؛ برای جلوگیری از قطعی سوییچ نمی‌کنیم
            val keepCurrent = autoConnect && connected && current != null &&
                !sameAsRunning && current.bestPing() > 0 &&
                current.score() <= chosen.score() * 1.35f

            when {
                sameAsRunning -> Repo.setSelected(chosen.id)
                keepCurrent -> Unit
                else -> {
                    Repo.setSelected(chosen.id)
                    if (autoConnect) TunnelController.start(context, chosen.id)
                }
            }
        }
        return@withLock chosen
    }

    /**
     * اتصال: اگر «تست کامل پیش از اتصال» فعال باشد، هیچ اتصالی پیش از پایان همه‌ی تست‌ها
     * (بررسی شبکه + TCP + سه پینگ) انجام نمی‌شود.
     */
    suspend fun quickConnect(context: Context) {
        val settings = Repo.settings.value
        if (settings.requireFullTest) {
            scanAndConnect(context, autoConnect = true)
            return
        }

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
     * اتصال به یک سرور مشخص با تست پیش از اتصال:
     * اگر همان سرور سالم بود وصل می‌شود، وگرنه چرخه‌ی کامل اجرا و بهترین انتخاب می‌شود.
     */
    suspend fun connectVerified(context: Context, serverId: String): Boolean {
        val server = Repo.serverById(serverId) ?: return false
        return if (Repo.settings.value.requireFullTest) {
            scanAndConnect(context, autoConnect = true) != null
        } else {
            val tcp = HealthScanner.tcpPing(server)
            Repo.setTestResult(server.id, tcp, -1)
            if (tcp > 0) {
                TunnelController.start(context, serverId)
                true
            } else {
                scanAndConnect(context, autoConnect = true) != null
            }
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

    /**
     * «ری‌استارت جستجو»: نتایج قبلی پاک می‌شوند و کل چرخه از نو اجرا می‌شود.
     * اتصال فعلی تا پیدا شدن گزینه‌ی بهتر برقرار می‌ماند (بدون قطعی برای کاربر).
     */
    suspend fun restart(context: Context, autoConnect: Boolean = true): ServerConfig? {
        Failover.reset()
        Repo.clearTestResults()
        Repo.scan.value = ScanState()
        return scanAndConnect(context, autoConnect = autoConnect)
    }

    /** کلید حافظه‌ی شبکه از نتیجه‌ی بررسی عمیق */
    fun profileKeyOf(assessment: ir.v2max.app.data.NetworkAssessment): String {
        if (assessment.assessedAt == 0L) return ""
        return assessment.carrier.name + "|" + assessment.netType.ifBlank { "net" }
    }

    /** ذخیره‌ی بهترین سرور و تنظیمات کاری همین شبکه پس از اتصال موفق */
    fun rememberWorkingNetwork(server: ir.v2max.app.data.ServerConfig) {
        if (!Repo.settings.value.rememberNetworks) return
        val assessment = Repo.network.value
        val key = profileKeyOf(assessment)
        if (key.isBlank()) return
        val settings = Repo.settings.value
        val previous = Repo.profileFor(key)
        Repo.saveProfile(
            ir.v2max.app.data.NetworkProfile(
                key = key,
                carrierLabel = assessment.carrier.label,
                serverId = server.id,
                serverName = server.displayName(),
                fragmentProfile = settings.fragmentProfile,
                mtu = settings.mtu,
                bestPing = server.bestPing(),
                score = server.score(),
                uses = (previous?.uses ?: 0) + 1,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** بررسی دوره‌ای سلامت سرور انتخابی */
    suspend fun refreshSelectedIfNeeded(context: Context) {
        val selected = Repo.selectedServer() ?: return
        if (System.currentTimeMillis() - selected.testedAt < 5 * 60 * 1000L) return
        val tcp = HealthScanner.tcpPing(selected)
        Repo.setTestResult(selected.id, tcp, selected.realPing)
    }
}
