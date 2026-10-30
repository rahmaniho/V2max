package ir.v2max.app.core

import android.content.Context
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.net.CarrierProbe
import ir.v2max.app.net.HealthScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * مغز متفکر «اتصال هوشمند»:
 * تست واقعی با Xray را قبل از اتصال انجام می‌دهد و نتیجه‌ی موقتاً ناموفق را حذف نمی‌کند.
 */
object SmartConnect {

    private val scanMutex = Mutex()
    private val probeMutex = Mutex()
    @Volatile private var activeScanJob: Job? = null

    fun cancelScan() { activeScanJob?.cancel() }

    /** بررسی عمیق شبکه (اپراتور، DNS، DPI، تأخیر/لرزش) و انتشار نتیجه برای رابط کاربری */
    suspend fun assessNetwork(context: Context, force: Boolean = false) {
        if (!Repo.settings.value.deepProbe) return
        probeMutex.withLock {
            if (!force && Repo.network.value.fresh) return@withLock
            Repo.scan.value = Repo.scan.value.copy(
                phase = "بررسی عمیق شبکه‌ی اپراتور",
                message = "سنجش DNS، DPI، تأخیر و لرزش شبکه…"
            )
            val result = try {
                CarrierProbe.assess(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock
            }
            Repo.network.value = result
        }
    }

    /** اتمام تست‌ها، انتخاب یک سرور واقعاً پاسخ‌گو، سپس اتصال اختیاری. */
    suspend fun scanAndConnect(
        context: Context,
        autoConnect: Boolean = true,
        subscriptionId: String? = null,
        limit: Int = 0,
        preferredId: String? = null
    ): ServerConfig? = scanMutex.withLock {
        val job = currentCoroutineContext()[Job]
        activeScanJob = job
        try {
            val all = withContext(Dispatchers.Default) {
                Repo.servers.value.filter { subscriptionId == null || it.subId == subscriptionId }
            }
            // اولویت‌بندی: علاقه‌مندی‌ها، سپس سرورهایی که قبلاً سالم بوده‌اند
            val servers = if (limit > 0 && all.size > limit) {
                all.sortedWith(
                    compareByDescending<ServerConfig> { it.id == preferredId }
                        .thenByDescending { it.favorite }
                        .thenByDescending { it.realPing > 0 }
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
            val assessment = Repo.network.value
            val scanSettings = if (settings.autoTuneFragment && assessment.fresh) {
                settings.copy(fragmentProfile = assessment.recommendedFragment)
            } else settings
            val outcome = try {
                HealthScanner.scan(
                    context = context,
                    servers = servers,
                    settings = scanSettings,
                    preferredId = preferredId,
                    onTcp = { cfg, ping -> Repo.setPretest(cfg.id, ping) },
                    onReal = { cfg, result -> Repo.setVerifiedResult(cfg.id, result) },
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
            } catch (e: CancellationException) {
                Repo.scan.value = Repo.scan.value.copy(running = false, message = "تست لغو شد")
                throw e
            } catch (t: Exception) {
                Repo.scan.value = Repo.scan.value.copy(
                    running = false,
                    finished = true,
                    message = "اسکن متوقف شد: ${t.message ?: "خطا"}"
                )
                return@withLock null
            }

            // A temporary outage is not evidence that a user's configuration should be deleted.
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
                        if (preferredId != null && best.id != preferredId) append("سرور انتخابی پاسخ نداد؛ جایگزین: ")
                        else append("سرور تأییدشده: ")
                        append("${best.displayName()} (${best.realPing}ms)")
                        append(" · ${outcome.testedCount} تست واقعی")
                    }
                }
            )

            // حافظه‌ی شبکه‌های آشنا: اگر سرور ذخیره‌شده‌ی همین شبکه تقریباً هم‌سطح بهترین بود، همان انتخاب می‌شود
            val profileKey = profileKeyOf(assessment)
            val rememberedId = if (preferredId == null && settings.rememberNetworks && profileKey.isNotBlank()) {
                Repo.profileFor(profileKey)?.serverId
            } else null
            val remembered = outcome.qualified.firstOrNull { it.id == rememberedId }
                ?.let { Repo.serverById(it.id) }

            val chosen = when {
                best == null -> null
                remembered != null && remembered.realPing > 0 && remembered.score() <= best.score() + 60 -> remembered
                else -> best
            }

            if (chosen != null) {
                val tunnel = Repo.tunnel.value
                val current = Repo.serverById(tunnel.serverId)
                val connected = tunnel.status == TunnelStatus.CONNECTED
                val sameAsRunning = connected && current?.id == chosen.id
                // اتصال فعلی تقریباً هم‌سطح بهترین است؛ برای جلوگیری از قطعی سوییچ نمی‌کنیم
                val keepCurrent = preferredId == null && autoConnect && connected && current != null &&
                    !sameAsRunning && current.bestPing() > 0 &&
                    current.score() <= chosen.score() * 1.35f

                when {
                    sameAsRunning -> Repo.setSelected(chosen.id)
                    keepCurrent -> Unit
                    else -> {
                        Repo.setSelected(chosen.id)
                        if (autoConnect) TunnelController.start(context, chosen.id, verified = true)
                    }
                }
            }
            return@withLock chosen
        } finally {
            if (activeScanJob === job) activeScanJob = null
            if (job?.isCancelled == true) {
                Repo.scan.value = Repo.scan.value.copy(running = false, finished = true, message = "تست لغو شد")
            }
        }
    }

    /** Test the selected server first; try other verified candidates if it fails. */
    suspend fun quickConnect(context: Context) {
        val selected = Repo.selectedServer()
        if (selected != null) connectVerified(context, selected.id)
        else scanAndConnect(context, autoConnect = true)
    }

    /** An explicit selection is honored whenever that same server passes the real test. */
    suspend fun connectVerified(context: Context, serverId: String): Boolean {
        if (Repo.serverById(serverId) == null) return false
        // A manual choice should not wait for hundreds of unrelated public configs.
        if (scanAndConnect(context, autoConnect = true, limit = 1, preferredId = serverId) != null) return true
        currentCoroutineContext().ensureActive()
        Repo.scan.value = Repo.scan.value.copy(message = "سرور انتخابی پاسخ نداد؛ جستجوی جایگزین…")
        return scanAndConnect(context, autoConnect = true) != null
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

}
