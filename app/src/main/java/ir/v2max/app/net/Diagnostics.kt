package ir.v2max.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ابزارهای عیب‌یابی اتصال:
 *  • تشخیص IP خروجی و کشور (از طریق خود تانل)
 *  • بررسی پاسخ‌دهی DNS امن (DoH)
 *  • نمونه‌گیری از مسیر خروج برای گزارش
 *
 * توجه: تشخیص «نشت DNS» به‌صورت قطعی از درون یک اپلیکیشن اندروید ممکن نیست،
 * چون سرورهای DNS سیستم قابل مشاهده نیستند. ما بررسی می‌کنیم که درخواست‌های DNS
 * از تانل عبور می‌کنند (پاسخ DoH معتبر می‌آید) و آدرس IP خروجی همان سرور است.
 */
object Diagnostics {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    data class ExitInfo(
        val ip: String = "",
        val country: String = "",
        val colo: String = "",
        val ok: Boolean = false,
        val error: String = ""
    )

    data class DnsInfo(
        val dohAnswer: String = "",
        val systemAnswer: String = "",
        val dohOk: Boolean = false,
        val consistent: Boolean = false
    )

    /** IP و کشور خروجی را از Cloudflare trace می‌خواند (از مسیر تانل) */
    suspend fun exitInfo(): ExitInfo = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://1.1.1.1/cdn-cgi/trace")
                .header("User-Agent", "V2max/1.5")
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful || body.isBlank()) {
                    return@withContext ExitInfo(ok = false, error = "پاسخ نامعتبر (${res.code})")
                }
                val map = body.lineSequence()
                    .mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
                    }
                    .toMap()
                ExitInfo(
                    ip = map["ip"].orEmpty(),
                    country = map["loc"].orEmpty(),
                    colo = map["colo"].orEmpty(),
                    ok = !map["ip"].isNullOrBlank()
                )
            }
        } catch (t: Throwable) {
            ExitInfo(ok = false, error = t.message ?: "خطای شبکه")
        }
    }

    /** پاسخ DoH و DNS سیستمی را مقایسه می‌کند */
    suspend fun dnsCheck(remoteDns: String, host: String = "www.gstatic.com"): DnsInfo =
        withContext(Dispatchers.IO) {
            var doh = ""
            var dohOk = false
            var system = ""

            // ۱) پاسخ از DNS راه دور (DoH) — همان مسیری که هسته استفاده می‌کند
            runCatching {
                val url = if (remoteDns.startsWith("http")) {
                    remoteDns.trimEnd('/') + "?name=$host&type=A"
                } else {
                    "https://1.1.1.1/dns-query?name=$host&type=A"
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build()
                client.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (res.isSuccessful) {
                        val answers = regexIPs(body)
                        if (answers.isNotEmpty()) {
                            doh = answers.take(2).joinToString(", ")
                            dohOk = true
                        }
                    }
                }
            }

            // ۲) پاسخ DNS سیستمی برای مقایسه
            runCatching {
                val addrs = java.net.InetAddress.getAllByName(host)
                system = addrs.mapNotNull { it.hostAddress }
                    .filter { it.count { c -> c == '.' } == 3 }
                    .take(2)
                    .joinToString(", ")
            }

            val dohSet = doh.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val sysSet = system.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            DnsInfo(
                dohAnswer = doh,
                systemAnswer = system,
                dohOk = dohOk,
                consistent = dohSet.isNotEmpty() && dohSet.intersect(sysSet).isNotEmpty()
            )
        }

    private val ipRegex = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

    private fun regexIPs(body: String): List<String> =
        ipRegex.findAll(body).map { it.value }.filter { it != "0.0.0.0" }.distinct().toList()

    enum class Severity { OK, INFO, WARN, CRITICAL }

    /** یک یافته‌ی تشخیصی با اقدام پیشنهادی */
    data class Finding(
        val title: String,
        val detail: String,
        val severity: Severity,
        val actionId: String = "",
        val actionLabel: String = ""
    )

    /** شناسه‌ی اقدام‌ها که رابط کاربری به عملیات واقعی نگاشت می‌کند */
    object Action {
        const val SMART_CONNECT = "smart_connect"
        const val AGGRESSIVE_FRAGMENT = "aggressive_fragment"
        const val REFRESH_SUBS = "refresh_subs"
        const val SYNC_GEO = "sync_geo"
        const val UNBLOCK = "unblock"
        const val DISABLE_BALANCER = "disable_balancer"
        const val CHECK_UPDATE = "check_update"
    }

    /**
     * تشخیص خودکار وضعیت: از داده‌های واقعی (وضعیت تانل، کشور خروجی، DNS، تعداد سرور سالم)
     * یافته‌های قابل اقدام می‌سازد. منطق خالص است تا در آزمون واحد قابل بررسی باشد.
     */
    fun advise(
        connected: Boolean,
        connecting: Boolean,
        killSwitchBlocked: Boolean,
        exitOk: Boolean,
        dnsDohOk: Boolean,
        dnsConsistent: Boolean,
        totalServers: Int,
        aliveServers: Int,
        balancerEnabled: Boolean,
        geoReady: Boolean,
        fragmentProfile: String
    ): List<Finding> {
        val out = ArrayList<Finding>()

        if (killSwitchBlocked) {
            out += Finding(
                title = "ترافیک مسدود است (Kill Switch)",
                detail = "تانل قطع شده و برای محافظت از حریم خصوصی، اینترنت مسدود مانده است.",
                severity = Severity.CRITICAL,
                actionId = Action.UNBLOCK,
                actionLabel = "رفع مسدودی"
            )
        }

        if (!connected && !connecting && !killSwitchBlocked) {
            out += Finding(
                title = "اتصال برقرار نیست",
                detail = "با یک ضربه، تست کامل انجام و بهترین سرور انتخاب می‌شود.",
                severity = Severity.INFO,
                actionId = Action.SMART_CONNECT,
                actionLabel = "اتصال هوشمند"
            )
        }

        if (connected && !exitOk) {
            out += Finding(
                title = "ترافیک از تانل عبور نمی‌کند",
                detail = "هسته بالا آمده ولی درخواست خروجی پاسخ نگرفت. اغلب با پروفایل فرگمنت قوی‌تر حل می‌شود.",
                severity = Severity.CRITICAL,
                actionId = Action.AGGRESSIVE_FRAGMENT,
                actionLabel = "اعمال پروفایل قوی"
            )
        }

        if (connected && exitOk && !dnsDohOk) {
            out += Finding(
                title = "DNS امن پاسخ نمی‌دهد",
                detail = "پاسخ DoH از مسیر تانل دریافت نشد؛ ممکن است برخی سایت‌ها باز نشوند.",
                severity = Severity.WARN,
                actionId = Action.AGGRESSIVE_FRAGMENT,
                actionLabel = "تغییر پروفایل فرگمنت"
            )
        }

        if (connected && dnsDohOk && !dnsConsistent) {
            out += Finding(
                title = "نتیجه‌ی DNS یکسان نیست",
                detail = "پاسخ DNS سیستم و DNS امن متفاوت است؛ احتمال دخالت DNS اپراتور وجود دارد.",
                severity = Severity.WARN
            )
        }

        if (totalServers > 0 && aliveServers * 4 < totalServers) {
            out += Finding(
                title = "بیشتر سرورها از کار افتاده‌اند",
                detail = "${FormatInt(aliveServers)} از ${FormatInt(totalServers)} سرور سالم است؛ منابع را دوباره دریافت کنید.",
                severity = Severity.WARN,
                actionId = Action.REFRESH_SUBS,
                actionLabel = "به‌روزرسانی اشتراک‌ها"
            )
        }

        if (balancerEnabled && aliveServers in 1..2) {
            out += Finding(
                title = "سرور سالم برای استخر کم است",
                detail = "برای پایداری بیشتر، تعداد سرور در استخر را کم کنید یا منابع بیشتری اضافه کنید.",
                severity = Severity.INFO,
                actionId = Action.DISABLE_BALANCER,
                actionLabel = "خاموش‌کردن استخر"
            )
        }

        if (!geoReady) {
            out += Finding(
                title = "فایل‌های مسیریابی ایران نصب نشده",
                detail = "با دانلود geoip/geosite، سایت‌های ایرانی مستقیم و سریع‌تر باز می‌شوند.",
                severity = Severity.INFO,
                actionId = Action.SYNC_GEO,
                actionLabel = "دانلود فایل‌ها"
            )
        }

        if (fragmentProfile == "LIGHT" && connected && !exitOk) {
            out += Finding(
                title = "پروفایل فرگمنت سبک انتخاب شده",
                detail = "برای شبکه‌های دارای DPI، پروفایل قوی‌تر احتمال موفقیت بیشتری دارد.",
                severity = Severity.WARN,
                actionId = Action.AGGRESSIVE_FRAGMENT,
                actionLabel = "اعمال پروفایل قوی"
            )
        }

        if (out.isEmpty()) {
            out += Finding(
                title = "همه‌چیز سالم است",
                detail = "خروجی، DNS و سرورها وضعیت مناسبی دارند.",
                severity = Severity.OK
            )
        }
        return out
    }

    private fun FormatInt(value: Int): String = value.toString()

    /** گزارش متنی برای اشتراک‌گذاری/ارسال به پشتیبانی */
    fun buildReport(
        appVersion: String,
        coreVersion: String,
        deviceInfo: String,
        serverLabel: String,
        tunnelStatus: String,
        exit: ExitInfo,
        dns: DnsInfo,
        logs: List<String>
    ): String = buildString {
        appendLine("=== گزارش عیب‌یابی V2max ===")
        appendLine("برنامه: $appVersion")
        appendLine("هسته: $coreVersion")
        appendLine("دستگاه: $deviceInfo")
        appendLine("سرور: $serverLabel")
        appendLine("وضعیت تانل: $tunnelStatus")
        appendLine("IP خروجی: ${exit.ip.ifBlank { "نامشخص" }}")
        appendLine("کشور خروجی: ${exit.country.ifBlank { "نامشخص" }}")
        appendLine("نود Cloudflare: ${exit.colo.ifBlank { "-" }}")
        appendLine("DNS امن (DoH): ${if (dns.dohOk) "پاسخ گرفت (${dns.dohAnswer})" else "بدون پاسخ"}")
        appendLine("DNS سیستم: ${dns.systemAnswer.ifBlank { "نامشخص" }}")
        appendLine("هم‌خوانی DNS: ${if (dns.consistent) "بله" else "خیر/نامشخص"}")
        appendLine("---- لاگ هسته ----")
        if (logs.isEmpty()) appendLine("(خالی)") else logs.takeLast(200).forEach { appendLine(it) }
    }
}
