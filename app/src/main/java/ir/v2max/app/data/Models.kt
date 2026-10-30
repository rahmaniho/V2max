package ir.v2max.app.data

import java.util.UUID

/** پروتکل‌های پشتیبانی‌شده */
enum class Proto(val label: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SS("Shadowsocks"),
    SOCKS("Socks"),
    HTTP("HTTP"),
    HYSTERIA2("Hysteria2");

    companion object {
        fun fromScheme(scheme: String): Proto? = when (scheme.lowercase()) {
            "vless" -> VLESS
            "vmess" -> VMESS
            "trojan" -> TROJAN
            "ss" -> SS
            "socks", "socks5" -> SOCKS
            "http", "https" -> HTTP
            "hysteria2", "hy2" -> HYSTERIA2
            else -> null
        }
    }
}

/** حالت مسیریابی */
enum class RoutingMode {
    /** ترافیک داخلی (ایران) مستقیم، بقیه از تانل */
    SMART,

    /** همه ترافیک از تانل */
    GLOBAL
}

/** یک کانفیگ/سرور */
data class ServerConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var proto: Proto = Proto.VLESS,
    var address: String = "",
    var port: Int = 443,

    // اعتبارنامه‌ها
    var uuid: String = "",
    var password: String = "",
    var method: String = "",
    var flow: String = "",

    // انتقال
    var network: String = "tcp",
    var headerType: String = "",
    var host: String = "",
    var path: String = "",
    var serviceName: String = "",
    var mode: String = "",
    var authority: String = "",
    // پارامترهای لینک v2rayNG که باید بدون تغییر تا هسته حفظ شوند
    var xhttpExtra: String = "",
    var finalMask: String = "",
    var kcpSeed: String = "",
    var kcpMtu: Int = 1350,
    var kcpTti: Int = 50,

    // امنیت
    var security: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fingerprint: String = "",
    var publicKey: String = "",
    var shortId: String = "",
    var spiderX: String = "",
    var allowInsecure: Boolean = false,
    var echConfigList: String = "",
    var verifyPeerCertByName: String = "",
    var pinnedPeerCertSha256: String = "",
    var mldsa65Verify: String = "",

    // مخفی‌سازی اختیاری
    var obfsType: String = "",
    var obfsPassword: String = "",

    var subId: String = "",
    var favorite: Boolean = false,

    var tcpPing: Int = -1,
    var realPing: Int = -1,

    /** تأخیر واقعی نسبت به گوگل (ms) */
    var googlePing: Int = -1,

    /** تأخیر واقعی نسبت به اینستاگرام (ms) */
    var instagramPing: Int = -1,

    /** تأخیر واقعی نسبت به یوتیوب (ms) */
    var youtubePing: Int = -1,

    /** سرعت دانلود واقعی اندازه‌گیری‌شده (کیلوبیت بر ثانیه) — ۰ یعنی سنجیده نشده */
    var speedKbps: Int = 0,

    /** کد کشور (ISO-3166 alpha-2) و نام فارسی کشور */
    var countryCode: String = "",
    var countryName: String = "",

    /** آمار پایداری: تعداد اتصال‌های موفق و تلاش‌های ناموفق */
    var successCount: Int = 0,
    var failCount: Int = 0,

    var testedAt: Long = 0L,
    /** شرایط دقیق آخرین تست واقعی موفق؛ TCP به‌تنهایی به معنی سلامت نیست. */
    var testedFragment: Boolean = false,
    var testedFragmentProfile: FragmentProfile = FragmentProfile.BALANCED,
    var testedUrl: String = "",
    var addedAt: Long = System.currentTimeMillis()
) {
    val endpoint: String get() = "$address:$port"

    /** تعداد مقصدهایی که سرور به آن‌ها پاسخ داده است (۰ تا ۳) */
    fun reachCount(): Int {
        var n = 0
        if (googlePing > 0) n++
        if (instagramPing > 0) n++
        if (youtubePing > 0) n++
        return n
    }

    /** آیا آدرس قابل استفاده است (حذف آدرس‌های محلی/نامعتبر داخل کانفیگ‌ها) */
    fun hasUsableEndpoint(): Boolean {
        val a = address.trim().lowercase()
        if (a.isEmpty() || port !in 1..65535) return false
        if (a == "localhost" || a == "0.0.0.0" || a == "::" || a == "::1") return false
        if (a.startsWith("127.")) return false
        if (a == "[::1]") return false
        return true
    }

    /** درصد پایداری بین ۰ تا ۱۰۰ (۱۰۰ = بدون شکست) */
    fun stability(): Int {
        val total = successCount + failCount
        if (total <= 0) return 70
        return ((successCount * 100) / total).coerceIn(0, 100)
    }

    /** امتیاز ترکیبی؛ کمتر بهتر. تأخیر + جریمه‌ی سرعت پایین + جریمه‌ی ناپایداری */
    fun score(): Int {
        val base = bestPing().let { if (it > 0) it else 3500 }
        var score = base
        if (speedKbps <= 0) {
            score += 150
        } else {
            // هر ۵۰۰ کیلوبیت سرعت بیشتر، ۱۰ امتیاز کمتر (تا ۱۴۰ امتیاز اثر)
            score -= (speedKbps / 500).coerceAtMost(14) * 10
        }
        score += (100 - stability()) * 2
        return score.coerceAtLeast(1)
    }

    fun hasFlag(): Boolean = countryCode.isNotBlank()

    fun bestPing(): Int {
        val candidates = listOf(googlePing, instagramPing, youtubePing, realPing)
            .filter { it > 0 }
        if (candidates.isNotEmpty()) return candidates.min()
        return if (tcpPing > 0) tcpPing else -1
    }

    fun displayName(): String = name.ifBlank { endpoint }

    fun proxyHost(): String = host.ifBlank { address }

    fun effectiveSni(): String = sni.ifBlank {
        val transportHost = when (network) {
            "grpc" -> authority.ifBlank { host }
            else -> host.substringBefore(',').trim()
        }
        transportHost.ifBlank { address }
    }

    /** پارامترهای مؤثر در اتصال نباید هنگام حذف تکراری‌ها از بین بروند. */
    fun dedupeKey(): String = listOf(
        proto.name, address, port.toString(), uuid, password, method, flow, network,
        headerType, host, path, serviceName, mode, authority, xhttpExtra, finalMask,
        kcpSeed, kcpMtu.toString(), kcpTti.toString(), security, sni, alpn, fingerprint,
        publicKey, shortId, spiderX, allowInsecure.toString(), echConfigList,
        verifyPeerCertByName, pinnedPeerCertSha256, mldsa65Verify, obfsType, obfsPassword
    ).joinToString("|")

    /** شناسه پایداری که مستقل از id است (برای تطبیق با کانفیگ قبلی پس از به‌روزرسانی اشتراک) */
    fun fingerprint(): String = dedupeKey()
}

/** اشتراک */
data class Subscription(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    var updatedAt: Long = 0L,
    var count: Int = 0,
    var upload: Long = 0L,
    var download: Long = 0L,
    var total: Long = 0L,
    var expireAt: Long = 0L,
    var lastError: String = "",
    /** اشتراک داخلی برنامه (منبع رایگان عمومی) */
    var builtIn: Boolean = false
) {
    val usedBytes: Long get() = upload + download

    fun usedPercent(): Float = if (total <= 0L) 0f else (usedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** حالت پروکسی برنامه‌به‌برنامه */
enum class PerAppMode {
    /** همه‌ی برنامه‌ها از تانل عبور می‌کنند */
    OFF,

    /** فقط برنامه‌های انتخاب‌شده از تانل عبور می‌کنند */
    ONLY_SELECTED,

    /** همه به‌جز برنامه‌های انتخاب‌شده از تانل عبور می‌کنند */
    EXCEPT_SELECTED
}

/** بسته‌ی پشتیبان */
data class BackupPayload(
    val version: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val servers: List<ServerConfig> = emptyList(),
    val subs: List<Subscription> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val selectedId: String = ""
)

/** تنظیمات برنامه */
data class AppSettings(
    var routingMode: RoutingMode = RoutingMode.SMART,
    var autoConnectOnOpen: Boolean = true,
    var autoConnectOnBoot: Boolean = false,
    var smartFailover: Boolean = true,
    var autoUpdateSubs: Boolean = true,

    /** منابع رایگان عمومی به‌صورت پیش‌فرض به لیست اشتراک‌ها اضافه شوند */
    var useFreeSources: Boolean = true,

    /** در هر بار باز شدن برنامه، همه‌ی اشتراک‌ها به‌روزرسانی و بهترین سرور انتخاب شود */
    var autoRefreshOnOpen: Boolean = true,

    /** تکه‌تکه‌سازی TLS برای عبور از فیلترینگ DPI */
    var fragment: Boolean = true,
    var mux: Boolean = false,
    var muxConcurrency: Int = 8,
    var mtu: Int = 1500,
    var ipv6: Boolean = false,

    /** بررسی همزمان پینگ گوگل، اینستاگرام و یوتیوب پیش از اتصال (کندتر؛ پیش‌فرض خاموش) */
    var multiPing: Boolean = false,

    /** بررسی عمیق شبکه‌ی اپراتور (DNS/DPI/تأخیر/لرزش) پیش از هر تست */
    var deepProbe: Boolean = true,

    /** هیچ اتصالی پیش از پایان کامل همه‌ی تست‌ها برقرار نمی‌شود */
    var requireFullTest: Boolean = true,

    /** تنظیم خودکار شدت فرگمنت بر اساس شبکه‌ی اپراتور */
    var autoTuneFragment: Boolean = true,

    /** بررسی خودکار نسخه‌ی جدید برنامه */
    var autoUpdateCheck: Boolean = true,

    /** سنجش سرعت دانلود واقعی برای چند سرور برتر (زمان‌بر؛ پیش‌فرض خاموش) */
    var speedTest: Boolean = false,

    /** چند سرور برتر سرعت‌شان سنجیده شود */
    var speedTestCount: Int = 3,

    /** استفاده از حافظه‌ی شبکه‌های آشنا برای انتخاب خودکار */
    var rememberNetworks: Boolean = true,

    /** حالت کم‌مصرف: تست‌های سنگین‌تر و دانلود تست سرعت انجام نمی‌شود */
    var dataSaver: Boolean = false,

    /** اتصال خودکار فقط روی وای‌فای */
    var wifiOnly: Boolean = false,

    /**
     * استخر سرورها (Balancer): چند سرور برتر همزمان در کانفیگ قرار می‌گیرند و
     * هسته با استراتژی leastPing خودش سالم‌ترین را انتخاب می‌کند؛ نتیجه: قطعی کمتر و سرعت بیشتر.
     */
    var balancer: Boolean = false,

    /** حداکثر تعداد سرور در استخر (۲ تا ۶) */
    var balancerSize: Int = 3,

    /**
     * کاهش فعالیت پس‌زمینه با خاموش بودن صفحه (صرفه‌جویی باتری و حجم).
     * پیش‌فرض روشن است؛ در حالت روشن پروب از ۲ ثانیه به ۱۵ ثانیه می‌رسد.
     */
    var batterySaver: Boolean = true,

    /** به‌روزرسانی خودکار فایل‌های geoip/geosite (مسیریابی دقیق‌تر دامنه‌های ایرانی) */
    var geoAssets: Boolean = true,

    /** سقف مصرف روزانه (مگابایت) — صفر یعنی بی‌نهایت */
    var dailyLimitMb: Int = 0,

    /**
     * Kill Switch: اگر تانل به‌طور غیرمنتظره قطع شود، اینترنت دستگاه مسدود می‌ماند
     * تا داده‌ای بیرون از تانل نرود. با دکمه‌ی «رفع مسدودی» یا قطع دستی، اینترنت برمی‌گردد.
     */
    var killSwitch: Boolean = false,

    /** قفل برنامه با اثر انگشت/رمز دستگاه */
    var appLock: Boolean = false,

    /** پنهان‌کردن جزئیات اتصال در اعلان (نام سرور و سرعت) */
    var hideNotificationContent: Boolean = false,

    /** نمایش پرچم کشور در فهرست سرورها */
    var showFlags: Boolean = true,

    /** حذف سرورهایی که به هیچ‌کدام از مقصدها پاسخ نمی‌دهند */
    var dropDeadServers: Boolean = true,

    /** شدت فرگمنت TLS (الهام‌گرفته از MahsaNG / gfw_resist_tls_proxy) */
    var fragmentProfile: FragmentProfile = FragmentProfile.BALANCED,

    var remoteDns: String = "https://1.1.1.1/dns-query",
    var domesticDns: String = "178.22.122.100",
    var vpnDns: String = "1.1.1.1",
    var delayTestUrl: String = "https://www.gstatic.com/generate_204",

    var showSpeedInNotification: Boolean = true,
    var dynamicColor: Boolean = false,

    /**
     * قواعد مسیریابی سفارشی کاربر.
     * هر قاعده یک خط با قالب «عمل|نوع|مقدار» است، مثلاً:
     *   direct|domain|example.com
     *   proxy|ip|8.8.8.8/32
     *   block|domain|ads.example.org
     * مقدارها می‌توانند با کاما از هم جدا شوند.
     */
    var customRoutingRules: List<String> = emptyList(),

    // پروکسی برنامه‌به‌برنامه
    var perAppMode: PerAppMode = PerAppMode.OFF,
    var perAppPackages: List<String> = emptyList()
)

/**
 * پروفایل تکه‌تکه‌سازی TLS.
 * طبق تجربه‌های پروژه‌ی MahsaNG/gfw_resist_tls_proxy، تعداد تکه‌ها و فاصله‌ی زمانی
 * بین آن‌ها روی هر اپراتور متفاوت است؛ چند پروفایل آماده در دسترس کاربر قرار می‌گیرد.
 */
enum class FragmentProfile(val label: String, val packets: String, val length: String, val delay: String, val maxSplit: String) {
    LIGHT("سبک (ایرانسل)", "tlshello", "20-60", "2-6", "40"),
    BALANCED("متعادل", "tlshello", "50-100", "10-20", "80"),
    AGGRESSIVE("قوی (همراه اول)", "tlshello", "80-200", "15-30", "200"),

    /** تنظیمات پیش‌فرض MahsaNG — برای شبکه‌های بسیار سخت‌گیر */
    MAHSA("مهسا (MahsaNG)", "tlshello", "100-200", "10-20", "200");

    companion object {
        fun fromName(name: String?): FragmentProfile =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

/** یک مقصد سنجش کیفیت اتصال */
data class PingTarget(val name: String, val url: String)

enum class TunnelStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

/** وضعیت زنده تانل برای رابط کاربری */
data class TunnelUiState(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val serverId: String = "",
    val serverName: String = "",
    val serverProto: String = "",
    val ping: Int = -1,
    val connectedAt: Long = 0L,
    val downSpeed: Long = 0L,
    val upSpeed: Long = 0L,
    val totalDown: Long = 0L,
    val totalUp: Long = 0L,
    val message: String = ""
) {
    val isActive: Boolean get() = status == TunnelStatus.CONNECTED || status == TunnelStatus.CONNECTING
}

/** خانواده‌ی اپراتور/شبکه‌ی دستگاه */
enum class CarrierFamily(val label: String) {
    MCI("همراه اول"),
    IRANCELL("ایرانسل"),
    RIGHTEL("رایتل"),
    TCI("مخابرات"),
    WIFI("وای‌فای"),
    OTHER("نامشخص");

    companion object {
        fun detect(mccMnc: String, operatorName: String, wifi: Boolean): CarrierFamily {
            if (wifi) return WIFI
            val name = operatorName.lowercase()
            return when {
                mccMnc == "43211" || name.contains("mci") || operatorName.contains("همراه") -> MCI
                mccMnc == "43235" || name.contains("irancell") || name.contains("mtn") -> IRANCELL
                mccMnc == "43220" || name.contains("rightel") || name.contains("رایتل") -> RIGHTEL
                mccMnc == "43214" || name.contains("tci") || operatorName.contains("مخابرات") -> TCI
                else -> OTHER
            }
        }
    }
}

/**
 * نتیجه‌ی بررسی عمیق شبکه‌ی دستگاه پیش از اتصال.
 * برنامه با این اطلاعات می‌فهمد شبکه‌ی فعلی چقدر سالم است، کدام مقصدها مسدود شده‌اند
 * و برای عبور از DPI کدام پروفایل فرگمنت مناسب‌تر است.
 */
data class NetworkAssessment(
    val carrier: CarrierFamily = CarrierFamily.OTHER,
    val carrierName: String = "",
    val mccMnc: String = "",
    val netType: String = "",
    val signalDbm: Int = 0,
    val ipv6: Boolean = false,
    val latencyMs: Int = -1,
    val jitterMs: Int = -1,
    val lossPercent: Int = 0,
    val dnsBlocked: Boolean = false,
    val dpiDetected: Boolean = false,
    val blockedHosts: List<String> = emptyList(),
    val reachableAnchors: Int = 0,
    val totalAnchors: Int = 0,
    val recommendedFragment: FragmentProfile = FragmentProfile.BALANCED,
    val recommendedMtu: Int = 1500,
    val score: Int = 0,
    val message: String = "",
    val assessedAt: Long = 0L
) {
    val fresh: Boolean
        get() = assessedAt > 0 && System.currentTimeMillis() - assessedAt < 10 * 60 * 1000L
}

/**
 * حافظه‌ی «شبکه‌های آشنا»: برای هر ترکیب اپراتور/نسل شبکه، بهترین سرور و
 * تنظیمات فرگمنت/MTU که واقعاً جواب داده ذخیره می‌شود.
 */
data class NetworkProfile(
    val key: String = "",                 // مثلاً "MCI|4G"
    val carrierLabel: String = "",
    val serverId: String = "",
    val serverName: String = "",
    val fragmentProfile: FragmentProfile = FragmentProfile.BALANCED,
    val mtu: Int = 1500,
    val bestPing: Int = -1,
    val score: Int = 0,
    val uses: Int = 0,
    val updatedAt: Long = 0L
)

/** اطلاعات نسخه‌ی جدید منتشرشده */
data class UpdateInfo(
    val version: String = "",
    val notes: String = "",
    val pageUrl: String = "",
    val apkUrl: String = "",
    val publishedAt: String = ""
) {
    val hasUpdate: Boolean get() = version.isNotBlank()
}

/** مقصدهای پیش‌فرض سنجش پینگ */
object PingTargets {
    val GOOGLE = PingTarget("گوگل", "https://www.gstatic.com/generate_204")
    val INSTAGRAM = PingTarget("اینستاگرام", "https://www.instagram.com/")
    val YOUTUBE = PingTarget("یوتیوب", "https://www.youtube.com/generate_204")

    val ALL = listOf(GOOGLE, INSTAGRAM, YOUTUBE)
}

/** وضعیت همگام‌سازی اشتراک‌ها (به‌روزرسانی خودکار) */
data class SyncState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = ""
) {
    val progress: Float get() = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** وضعیت اسکن هوشمند */
data class ScanState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val alive: Int = 0,
    val phase: String = "",
    val bestName: String = "",
    val message: String = "",
    val finished: Boolean = false,
    val finishedAt: Long = 0L
) {
    val progress: Float get() = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
