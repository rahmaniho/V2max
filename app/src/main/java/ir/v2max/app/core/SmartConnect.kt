package ir.v2max.app.core

import android.content.Context
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.net.CarrierProbe
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.ProbePlanner
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
    private val scanJobs = java.util.Collections.synchronizedSet(HashSet<Job>())
    @Volatile private var activeScanJob: Job? = null
    @Volatile private var assessmentNetwork: Long? = null

    // Cancel queued scans too, so an automatic scan cannot reconnect after a user cancels.
    fun cancelScan() {
        synchronized(scanJobs) {
            scanJobs.toList().forEach { it.cancel() }
            PreflightTickets.revokeAll()
        }
    }

    /** بررسی عمیق شبکه (اپراتور، DNS، DPI، تأخیر/لرزش) و انتشار نتیجه برای رابط کاربری */
    suspend fun assessNetwork(context: Context, force: Boolean = false): Boolean {
        return probeMutex.withLock {
            val before = CarrierProbe.networkHandle(context) ?: return@withLock false
            if (!force && before == assessmentNetwork && Repo.network.value.fresh) return@withLock true
            Repo.scan.value = Repo.scan.value.copy(
                phase = "بررسی عمیق شبکه‌ی اپراتور",
                message = "سنجش DNS، DPI، تأخیر و لرزش شبکه…"
            )
            val result = try {
                CarrierProbe.assess(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock false
            }
            val after = CarrierProbe.networkHandle(context)
            if (before != after) {
                assessmentNetwork = null
                Repo.network.value = ir.v2max.app.data.NetworkAssessment()
                return@withLock false
            }
            assessmentNetwork = after
            Repo.network.value = result
            true
        }
    }

    /** Only use a deep-probe recommendation for the network on which it was measured. */
    internal fun effectiveScanSettings(context: Context, settings: ir.v2max.app.data.AppSettings): ir.v2max.app.data.AppSettings {
        val assessment = Repo.network.value
        val valid = settings.deepProbe && assessment.fresh && assessmentNetwork != null &&
            assessmentNetwork == CarrierProbe.networkHandle(context)
        return if (settings.autoTuneFragment && valid) settings.copy(fragmentProfile = assessment.recommendedFragment) else settings
    }

    /** اتمام تست‌ها، انتخاب یک سرور واقعاً پاسخ‌گو، سپس اتصال اختیاری. */
    internal suspend fun scanAndConnect(
        context: Context,
        autoConnect: Boolean = true,
        subscriptionId: String? = null,
        limit: Int = 0,
        preferredId: String? = null,
        excludedIds: Set<String> = emptySet(),
        exhaustive: Boolean = false
    ): VerifiedSelection? {
        val job = currentCoroutineContext()[Job]
        if (job != null) scanJobs.add(job)
        try {
            return scanMutex.withLock {
                activeScanJob = job
                try {
                    val servers = withContext(Dispatchers.Default) {
                        Repo.servers.value.filter {
                            (subscriptionId == null || it.subId == subscriptionId) && it.id !in excludedIds
                        }
                    }
                    if (servers.isEmpty()) {
                        Repo.scan.value = ScanState(
                            running = false,
                            finished = true,
                            message = "هیچ کانفیگی برای بررسی وجود ندارد. ابتدا یک اشتراک اضافه کنید."
                        )
                        return@withLock null
                    }

                    Repo.scan.value = ScanState(
                        running = true,
                        total = if (limit > 0) minOf(limit, servers.size) else servers.size,
                        phase = "شروع اسکن هوشمند",
                        message = "در حال بررسی مرحله‌ای کانفیگ‌ها"
                    )

                    val settings = Repo.settings.value.copy()
                    val networkAtStart = CarrierProbe.networkHandle(context)
                    if (networkAtStart == null) {
                        Repo.scan.value = Repo.scan.value.copy(
                            running = false, finished = true,
                            message = "شبکه‌ی فعالی پیدا نشد؛ اتصال جدید آغاز نشد"
                        )
                        return@withLock null
                    }
                    // This assessment, when enabled, is complete before any connection.
                    if (settings.deepProbe && !assessNetwork(context, force = true)) {
                        Repo.scan.value = Repo.scan.value.copy(
                            running = false, finished = true,
                            message = "بررسی شبکه کامل نشد؛ اتصال جدید آغاز نشد"
                        )
                        return@withLock null
                    }
                    val assessment = Repo.network.value
                    val assessmentValid = settings.deepProbe && assessment.fresh && assessmentNetwork != null &&
                        assessmentNetwork == networkAtStart
                    val scanSettings = effectiveScanSettings(context, settings)
                    val profileKey = if (assessmentValid) profileKeyOf(assessment) else ""
                    val rememberedId = if (preferredId == null && settings.rememberNetworks && profileKey.isNotBlank()) {
                        Repo.profileFor(profileKey)?.serverId
                    } else null
                    val outcome = try {
                        HealthScanner.scan(
                            context = context,
                            servers = servers,
                            settings = scanSettings,
                            preferredId = preferredId,
                            rememberedId = rememberedId,
                            limit = limit,
                            exhaustive = exhaustive,
                            onTcp = { cfg, ping -> Repo.setPretest(cfg.id, ping, cfg.fingerprint()) },
                            onReal = { cfg, result ->
                                Repo.setVerifiedResult(cfg.id, result, expectedFingerprint = cfg.fingerprint())
                            },
                            onSpeed = { cfg, kbps ->
                                Repo.setSpeed(cfg.id, kbps, persist = false, expectedFingerprint = cfg.fingerprint())
                            },
                            onMulti = { cfg ->
                                Repo.setMultiPing(
                                    cfg.id,
                                    google = cfg.googlePing,
                                    instagram = cfg.instagramPing,
                                    youtube = cfg.youtubePing,
                                    persist = false,
                                    expectedFingerprint = cfg.fingerprint()
                                )
                            },
                            onPhase = { phase, done, total ->
                                Repo.scan.value = Repo.scan.value.copy(phase = phase, done = done, total = total)
                            },
                            onAlive = { alive -> Repo.scan.value = Repo.scan.value.copy(alive = alive) }
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
                    currentCoroutineContext().ensureActive()

                    // Do not authorize a connection if the network or any test option
                    // changed mid-scan. An old ping in Repo is not proof of this scan.
                    if (networkAtStart != CarrierProbe.networkHandle(context) ||
                        PreflightPolicy.from(settings) != PreflightPolicy.from(Repo.settings.value)) {
                        Repo.scan.value = Repo.scan.value.copy(
                            running = false, finished = true,
                            message = "شبکه یا تنظیمات هنگام تست تغییر کرد؛ دوباره تلاش کنید"
                        )
                        withContext(Dispatchers.IO) { Repo.persistNow() }
                        return@withLock null
                    }
                    // Use the actual tested snapshots. A refresh/editor may have
                    // replaced a profile with another configuration bearing its ID.
                    val qualified = outcome.qualified.filter { tested ->
                        Repo.serverById(tested.id)?.fingerprint() == tested.fingerprint()
                    }
                    val best = qualified.firstOrNull { it.id == preferredId } ?: qualified.firstOrNull()
                    val remembered = qualified.firstOrNull { it.id == rememberedId }
                    val chosen = when {
                        best == null -> null
                        // Do not override a fresh download comparison with old network memory.
                        !outcome.speedCompared && remembered != null &&
                            ProbePlanner.latencyScore(remembered) <= ProbePlanner.latencyScore(best) + 60 -> remembered
                        else -> best
                    }

                    Repo.scan.value = Repo.scan.value.copy(
                        running = false,
                        finished = true,
                        finishedAt = System.currentTimeMillis(),
                        alive = qualified.size,
                        done = outcome.testedCount,
                        total = outcome.testedCount,
                        bestName = chosen?.displayName().orEmpty(),
                        phase = "پایان",
                        message = when {
                            chosen == null && outcome.best != null -> "فهرست هنگام تست تغییر کرد؛ دوباره تلاش کنید"
                            chosen == null -> outcome.message
                            else -> buildString {
                                if (preferredId != null && chosen.id != preferredId) append("سرور انتخابی پاسخ نداد؛ جایگزین: ")
                                else append("سرور تأییدشده: ")
                                append("${chosen.displayName()} (${chosen.realPing}ms)")
                                append(" · ${outcome.testedCount} تست واقعی · اسکن ${outcome.elapsedMs}ms")
                                append(" · ${outcome.message}")
                            }
                        }
                    )

                    currentCoroutineContext().ensureActive()
                    val selection = chosen?.let { VerifiedSelection.create(it, qualified, settings, networkAtStart) }
                    if (selection != null && autoConnect) {
                        val tunnel = Repo.tunnel.value
                        val current = Repo.serverById(tunnel.serverId)
                        val connected = tunnel.status == TunnelStatus.CONNECTED
                        val sameAsRunning = connected && current?.id == selection.id &&
                            TunnelController.matchesActiveProfile(selection.server(), settings)
                        // Avoid interrupting a working, comparable connection.
                        val keepCurrent = preferredId == null && connected && current != null &&
                            !sameAsRunning && current.bestPing() > 0 &&
                            ProbePlanner.latencyScore(current) <= ProbePlanner.latencyScore(chosen) * 1.35f
                        when {
                            sameAsRunning -> Repo.setSelected(selection.id)
                            keepCurrent -> Unit
                            else -> synchronized(scanJobs) {
                                // Cancellation and receipt hand-off must not cross.
                                if (job?.isActive != false) TunnelController.startVerified(context, selection)
                            }
                        }
                    }
                    // Serialization of a large list must not block the UI or delay issuing the start command.
                    withContext(Dispatchers.IO) { Repo.persistNow() }
                    selection
                } finally {
                    if (activeScanJob === job) activeScanJob = null
                    if (job?.isCancelled == true) {
                        Repo.scan.value = Repo.scan.value.copy(running = false, finished = true, message = "تست لغو شد")
                    }
                }
            }
        } finally {
            if (job != null) scanJobs.remove(job)
        }
    }

    /** Test the selected server first; try other verified candidates if it fails. */
    suspend fun quickConnect(context: Context): Boolean {
        val selected = Repo.selectedServer()
        return if (selected != null) connectVerified(context, selected.id)
        else scanAndConnect(context, autoConnect = true) != null
    }

    /** An explicit selection is honored whenever that same server passes the real test. */
    suspend fun connectVerified(context: Context, serverId: String): Boolean {
        if (Repo.serverById(serverId) == null) return false
        // A manual choice should not wait for hundreds of unrelated public configs.
        if (scanAndConnect(context, autoConnect = true, limit = 1, preferredId = serverId) != null) return true
        currentCoroutineContext().ensureActive()
        if (Repo.servers.value.none { it.id != serverId }) {
            Repo.scan.value = Repo.scan.value.copy(message = "سرور انتخابی پاسخ نداد و جایگزینی وجود ندارد")
            return false
        }
        Repo.scan.value = Repo.scan.value.copy(message = "سرور انتخابی پاسخ نداد؛ جستجوی جایگزین…")
        return scanAndConnect(context, autoConnect = true, excludedIds = setOf(serverId)) != null
    }

    /**
     * «ری‌استارت جستجو»: نتایج قبلی پاک می‌شوند و کل چرخه از نو اجرا می‌شود.
     * اتصال فعلی تا پیدا شدن گزینه‌ی بهتر برقرار می‌ماند (بدون قطعی برای کاربر).
     */
    internal suspend fun restart(context: Context, autoConnect: Boolean = true): VerifiedSelection? {
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
