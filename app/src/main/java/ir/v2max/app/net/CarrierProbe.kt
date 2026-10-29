package ir.v2max.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import ir.v2max.app.data.CarrierFamily
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.NetworkAssessment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * بررسی عمیق شبکه‌ی دستگاه پیش از اتصال.
 *
 * چهار لایه سنجیده می‌شود:
 *  ۱) مشخصات اپراتور (MCC/MNC، نسل شبکه، سیگنال، IPv6)
 *  ۲) کیفیت مسیر: تأخیر، لرزش (jitter) و درصد افت بسته به چند لنگر مستقل
 *  ۳) سلامت DNS و دسترسی مستقیم به مقصدهای پرکاربرد
 *  ۴) رفتار DPI: تلاش برای دست‌دادن TLS با SNI هدف؛ اگر TCP وصل شود ولی TLS بسته شود،
 *     یعنی مسیر با DPI فیلتر می‌شود و باید با فرگمنت از آن عبور کرد.
 *
 * خروجی، پروفایل فرگمنت و MTU پیشنهادی برای «نفوذ» از همین شبکه است.
 */
object CarrierProbe {

    /** لنگرهای سنجش کیفیت (IP خام؛ مستقل از DNS) */
    private val ANCHORS = listOf(
        "8.8.8.8" to 53,
        "1.1.1.1" to 443,
        "9.9.9.9" to 443,
        "208.67.222.222" to 443
    )

    /** دامنه‌هایی که معمولاً با DPI مسدود می‌شوند */
    private val PROBE_HOSTS = listOf(
        "www.google.com",
        "www.instagram.com",
        "www.youtube.com",
        "cdn.jsdelivr.net"
    )

    private const val CONNECT_TIMEOUT = 2500

    suspend fun assess(context: Context): NetworkAssessment = withContext(Dispatchers.IO) {
        val info = carrierInfo(context)

        // ---------- ۱) کیفیت مسیر نسبت به لنگرها
        val samples = ArrayList<Pair<Int, Boolean>>() // (latency, ok)
        coroutineScope {
            ANCHORS.map { (host, port) ->
                async { tcpLatency(host, port) }
            }.awaitAll().forEach { samples.add(it) }
        }

        val ok = samples.filter { it.second }.map { it.first }
        val latency = if (ok.isEmpty()) -1 else ok.average().toInt()
        val jitter = if (ok.size < 2) -1 else (ok.maxOrNull()!! - ok.minOrNull()!!)
        val loss = ((samples.size - ok.size) * 100) / samples.size.coerceAtLeast(1)

        // ---------- ۲) DNS و دسترسی مستقیم
        var dnsBlocked = false
        val blocked = ArrayList<String>()
        var tlsBlocked = 0
        var reachableHosts = 0

        coroutineScope {
            PROBE_HOSTS.map { host ->
                async {
                    val resolved = runCatching { InetAddress.getByName(host) }.getOrNull()
                    if (resolved == null) {
                        dnsBlocked = true
                        return@async Pair(host, 0) // DNS fail
                    }
                    val ip = resolved.hostAddress ?: return@async Pair(host, 0)
                    val tcp = tcpLatency(ip, 443)
                    if (!tcp.second) return@async Pair(host, 1) // TCP blocked
                    // TCP وصل شد: حالا دست‌دادن TLS با همان SNI را امتحان می‌کنیم
                    val tlsOk = tlsHandshake(ip, 443, host)
                    Pair(host, if (tlsOk) 3 else 2)
                }
            }.awaitAll().forEach { (host, state) ->
                when (state) {
                    3 -> reachableHosts++
                    2 -> { tlsBlocked++; blocked.add(host) }
                    1 -> blocked.add(host)
                    0 -> { dnsBlocked = true; blocked.add(host) }
                }
            }
        }

        val dpi = tlsBlocked > 0 || blocked.isNotEmpty()
        val score = computeScore(latency, jitter, loss, reachableHosts, dpi, dnsBlocked)
        val profile = recommendProfile(info.family, dpi, tlsBlocked, loss, latency)
        val mtu = recommendMtu(info.family, info.ipv6)

        val message = buildString {
            append(info.family.label)
            if (info.netType.isNotBlank()) append(" · ${info.netType}")
            when {
                dpi && dnsBlocked -> append(" · فیلترینگ DPI و DNS شناسایی شد")
                dpi -> append(" · فیلترینگ DPI شناسایی شد")
                dnsBlocked -> append(" · DNS محدود است")
                else -> append(" · مسیر مستقیم سالم است")
            }
            if (latency > 0) append(" · تأخیر ${latency}ms")
        }

        NetworkAssessment(
            carrier = info.family,
            carrierName = info.name,
            mccMnc = info.mccMnc,
            netType = info.netType,
            signalDbm = info.signalDbm,
            ipv6 = info.ipv6,
            latencyMs = latency,
            jitterMs = jitter,
            lossPercent = loss,
            dnsBlocked = dnsBlocked,
            dpiDetected = dpi,
            blockedHosts = blocked,
            reachableAnchors = ok.size,
            totalAnchors = samples.size,
            recommendedFragment = profile,
            recommendedMtu = mtu,
            score = score,
            message = message,
            assessedAt = System.currentTimeMillis()
        )
    }

    // ------------------------------------------------------------ carrier info

    private data class CarrierInfo(
        val family: CarrierFamily,
        val name: String,
        val mccMnc: String,
        val netType: String,
        val signalDbm: Int,
        val ipv6: Boolean
    )

    private fun carrierInfo(context: Context): CarrierInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

        var operatorName = ""
        var mccMnc = ""
        var netType = ""
        var signal = 0
        runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java)
            if (tm != null) {
                operatorName = tm.networkOperatorName.orEmpty()
                mccMnc = tm.networkOperator.orEmpty()
                netType = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps != null -> {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                caps.linkDownstreamBandwidthKbps > 100_000
                            ) "5G" else "4G/3G"
                        } else if (wifi) "Wi-Fi" else "نامشخص"
                    }

                    else -> "نامشخص"
                }
            }
        }
        if (mccMnc.isBlank()) {
            runCatching {
                val tm = context.getSystemService(TelephonyManager::class.java)
                mccMnc = tm?.simOperator.orEmpty()
                if (operatorName.isBlank()) operatorName = tm?.simOperatorName.orEmpty()
            }
        }
        if (netType.isBlank()) {
            netType = when {
                wifi -> "Wi-Fi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "همراه"
                else -> "نامشخص"
            }
        }
        val ipv6 = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == true &&
            hasIpv6()

        val family = CarrierFamily.detect(mccMnc, operatorName, wifi)
        return CarrierInfo(family, operatorName.ifBlank { family.label }, mccMnc, netType, signal, ipv6)
    }

    private fun hasIpv6(): Boolean = runCatching {
        val addrs = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
        addrs.any { !it.isLoopbackAddress && it.hostAddress?.contains(':') == true }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- measurements

    /** تأخیر برقراری TCP؛ خروجی (میلی‌ثانیه, موفق؟) */
    private fun tcpLatency(host: String, port: Int): Pair<Int, Boolean> {
        val start = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
                val ms = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                ms to true
            }
        } catch (t: Throwable) {
            -1 to false
        }
    }

    /** آیا دست‌دادن TLS با این SNI موفق است؟ (شکست = نشانه‌ی DPI) */
    private fun tlsHandshake(ip: String, port: Int, sni: String): Boolean = try {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, null, null)
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        socket.use { s ->
            s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT)
            s.soTimeout = 4000
            val params = s.sslParameters
            params.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
            params.endpointIdentificationAlgorithm = null
            s.sslParameters = params
            s.startHandshake()
        }
        true
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------ heuristics

    private fun computeScore(
        latency: Int,
        jitter: Int,
        loss: Int,
        reachableHosts: Int,
        dpi: Boolean,
        dnsBlocked: Boolean
    ): Int {
        var score = 100
        if (latency > 0) {
            score -= (latency / 4).coerceAtMost(30)
        } else {
            score -= 40
        }
        if (jitter > 0) score -= (jitter / 10).coerceAtMost(15)
        score -= loss / 4
        score -= (4 - reachableHosts).coerceAtLeast(0) * 6
        if (dpi) score -= 8
        if (dnsBlocked) score -= 6
        return score.coerceIn(0, 100)
    }

    /**
     * انتخاب پروفایل فرگمنت بر اساس تجربه‌ی میدانی روی اپراتورهای ایران
     * (هم‌راستا با توصیه‌های پروژه‌ی MahsaNG: همراه اول تکه‌های بیشتر، ایرانسل کمتر).
     */
    private fun recommendProfile(
        family: CarrierFamily,
        dpi: Boolean,
        tlsBlocked: Int,
        loss: Int,
        latency: Int
    ): FragmentProfile = when (family) {
        CarrierFamily.MCI -> if (dpi || loss > 0) FragmentProfile.AGGRESSIVE else FragmentProfile.BALANCED
        CarrierFamily.TCI -> FragmentProfile.AGGRESSIVE
        CarrierFamily.IRANCELL -> if (tlsBlocked >= 2) FragmentProfile.BALANCED else FragmentProfile.LIGHT
        CarrierFamily.RIGHTEL -> FragmentProfile.BALANCED
        CarrierFamily.WIFI -> if (dpi) FragmentProfile.BALANCED else FragmentProfile.LIGHT
        CarrierFamily.OTHER -> when {
            dpi && tlsBlocked >= 2 -> FragmentProfile.AGGRESSIVE
            dpi -> FragmentProfile.BALANCED
            loss > 0 || latency > 400 -> FragmentProfile.BALANCED
            else -> FragmentProfile.LIGHT
        }
    }

    private fun recommendMtu(family: CarrierFamily, ipv6: Boolean): Int = when (family) {
        CarrierFamily.MCI -> 1380
        CarrierFamily.IRANCELL -> 1400
        CarrierFamily.RIGHTEL -> 1400
        CarrierFamily.TCI -> 1380
        CarrierFamily.WIFI -> 1450
        CarrierFamily.OTHER -> if (ipv6) 1400 else 1450
    }
}
