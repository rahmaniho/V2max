package ir.v2max.app.net

import ir.v2max.core.domain.ConnectionAdvice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * کاوشگرهای عیب‌یابی اتصال:
 *  • تشخیص IP خروجی و کشور (از طریق خود تانل)
 *  • بررسی پاسخ‌دهی DNS امن (DoH)
 *
 * این کلاس فقط داده‌ی خام را از شبکه می‌گیرد. قضاوت درباره‌ی این داده‌ها —
 * اینکه چه یافته‌ای با چه شدت و چه اقدامی ساخته شود — در
 * `ir.v2max.core.domain.ConnectionAdvice` است و آزمون واحد دارد.
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

    /** IP و کشور خروجی را از Cloudflare trace می‌خواند (از مسیر تانل) */
    suspend fun exitInfo(): ConnectionAdvice.ExitInfo = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://1.1.1.1/cdn-cgi/trace")
                .header("User-Agent", "V2max/1.5")
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful || body.isBlank()) {
                    return@withContext ConnectionAdvice.ExitInfo(ok = false, error = "پاسخ نامعتبر (${res.code})")
                }
                val map = body.lineSequence()
                    .mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
                    }
                    .toMap()
                ConnectionAdvice.ExitInfo(
                    ip = map["ip"].orEmpty(),
                    country = map["loc"].orEmpty(),
                    colo = map["colo"].orEmpty(),
                    ok = !map["ip"].isNullOrBlank()
                )
            }
        } catch (t: Throwable) {
            ConnectionAdvice.ExitInfo(ok = false, error = t.message ?: "خطای شبکه")
        }
    }

    /** پاسخ DoH و DNS سیستمی را مقایسه می‌کند */
    suspend fun dnsCheck(remoteDns: String, host: String = "www.gstatic.com"): ConnectionAdvice.DnsInfo =
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
            ConnectionAdvice.DnsInfo(
                dohAnswer = doh,
                systemAnswer = system,
                dohOk = dohOk,
                consistent = dohSet.isNotEmpty() && dohSet.intersect(sysSet).isNotEmpty()
            )
        }

    private val ipRegex = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

    private fun regexIPs(body: String): List<String> =
        ipRegex.findAll(body).map { it.value }.filter { it != "0.0.0.0" }.distinct().toList()
}
