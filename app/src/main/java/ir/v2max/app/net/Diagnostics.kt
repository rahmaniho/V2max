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
