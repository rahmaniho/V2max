package ir.v2max.core.domain

/**
 * Turns the real state of a connection into findings a user can act on.
 *
 * Extracted from the app's `Diagnostics`, which mixed this pure decision logic
 * with the OkHttp calls that gather the inputs. The probes stayed behind —
 * they need a network — but the rules are what needed tests: eleven inputs,
 * nine independent conditions, and an ordering the UI renders top to bottom.
 *
 * Note what these rules cannot do. Detecting a DNS *leak* conclusively from
 * inside an Android app is not possible, because the system resolvers are not
 * observable. What is checked instead is that DNS requests do travel through
 * the tunnel (a valid DoH answer comes back) and that the exit IP is the
 * server's.
 */
object ConnectionAdvice {

    enum class Severity { OK, INFO, WARN, CRITICAL }

    /** A single diagnostic finding, with the action the UI should offer. */
    data class Finding(
        val title: String,
        val detail: String,
        val severity: Severity,
        val actionId: String = "",
        val actionLabel: String = "",
    )

    /** Action identifiers; the UI maps these onto real operations. */
    object Action {
        const val SMART_CONNECT = "smart_connect"
        const val AGGRESSIVE_FRAGMENT = "aggressive_fragment"
        const val REFRESH_SUBS = "refresh_subs"
        const val SYNC_GEO = "sync_geo"
        const val UNBLOCK = "unblock"
        const val DISABLE_BALANCER = "disable_balancer"
        const val CHECK_UPDATE = "check_update"
    }

    /** Where the tunnel actually exits, as read from the exit probe. */
    data class ExitInfo(
        val ip: String = "",
        val country: String = "",
        val colo: String = "",
        val ok: Boolean = false,
        val error: String = "",
    )

    /** Secure-DNS answer compared against the system resolver. */
    data class DnsInfo(
        val dohAnswer: String = "",
        val systemAnswer: String = "",
        val dohOk: Boolean = false,
        val consistent: Boolean = false,
    )

    /**
     * Builds the findings for the current state.
     *
     * Conditions are independent, not a cascade: several can fire at once, and
     * the order they are added in is the order the UI shows them, worst first.
     * When nothing fires the caller gets a single OK finding rather than an
     * empty list, so the screen is never blank.
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
        fragmentProfile: String,
    ): List<Finding> {
        val out = ArrayList<Finding>()

        if (killSwitchBlocked) {
            out += Finding(
                title = "ترافیک مسدود است (Kill Switch)",
                detail = "تانل قطع شده و برای محافظت از حریم خصوصی، اینترنت مسدود مانده است.",
                severity = Severity.CRITICAL,
                actionId = Action.UNBLOCK,
                actionLabel = "رفع مسدودی",
            )
        }

        if (!connected && !connecting && !killSwitchBlocked) {
            out += Finding(
                title = "اتصال برقرار نیست",
                detail = "با یک ضربه، تست کامل انجام و بهترین سرور انتخاب می‌شود.",
                severity = Severity.INFO,
                actionId = Action.SMART_CONNECT,
                actionLabel = "اتصال هوشمند",
            )
        }

        if (connected && !exitOk) {
            out += Finding(
                title = "ترافیک از تانل عبور نمی‌کند",
                detail = "هسته بالا آمده ولی درخواست خروجی پاسخ نگرفت. اغلب با پروفایل فرگمنت قوی‌تر حل می‌شود.",
                severity = Severity.CRITICAL,
                actionId = Action.AGGRESSIVE_FRAGMENT,
                actionLabel = "اعمال پروفایل قوی",
            )
        }

        if (connected && exitOk && !dnsDohOk) {
            out += Finding(
                title = "DNS امن پاسخ نمی‌دهد",
                detail = "پاسخ DoH از مسیر تانل دریافت نشد؛ ممکن است برخی سایت‌ها باز نشوند.",
                severity = Severity.WARN,
                actionId = Action.AGGRESSIVE_FRAGMENT,
                actionLabel = "تغییر پروفایل فرگمنت",
            )
        }

        if (connected && dnsDohOk && !dnsConsistent) {
            out += Finding(
                title = "نتیجه‌ی DNS یکسان نیست",
                detail = "پاسخ DNS سیستم و DNS امن متفاوت است؛ احتمال دخالت DNS اپراتور وجود دارد.",
                severity = Severity.WARN,
            )
        }

        if (totalServers > 0 && aliveServers * 4 < totalServers) {
            out += Finding(
                title = "بیشتر سرورها از کار افتاده‌اند",
                detail = "$aliveServers از $totalServers سرور سالم است؛ منابع را دوباره دریافت کنید.",
                severity = Severity.WARN,
                actionId = Action.REFRESH_SUBS,
                actionLabel = "به‌روزرسانی اشتراک‌ها",
            )
        }

        if (balancerEnabled && aliveServers in 1..2) {
            out += Finding(
                title = "سرور سالم برای استخر کم است",
                detail = "برای پایداری بیشتر، تعداد سرور در استخر را کم کنید یا منابع بیشتری اضافه کنید.",
                severity = Severity.INFO,
                actionId = Action.DISABLE_BALANCER,
                actionLabel = "خاموش‌کردن استخر",
            )
        }

        if (!geoReady) {
            out += Finding(
                title = "فایل‌های مسیریابی ایران نصب نشده",
                detail = "با دانلود geoip/geosite، سایت‌های ایرانی مستقیم و سریع‌تر باز می‌شوند.",
                severity = Severity.INFO,
                actionId = Action.SYNC_GEO,
                actionLabel = "دانلود فایل‌ها",
            )
        }

        if (fragmentProfile == "LIGHT" && connected && !exitOk) {
            out += Finding(
                title = "پروفایل فرگمنت سبک انتخاب شده",
                detail = "برای شبکه‌های دارای DPI، پروفایل قوی‌تر احتمال موفقیت بیشتری دارد.",
                severity = Severity.WARN,
                actionId = Action.AGGRESSIVE_FRAGMENT,
                actionLabel = "اعمال پروفایل قوی",
            )
        }

        if (out.isEmpty()) {
            out += Finding(
                title = "همه‌چیز سالم است",
                detail = "خروجی، DNS و سرورها وضعیت مناسبی دارند.",
                severity = Severity.OK,
            )
        }
        return out
    }

    /** A shareable text report for support. Pure string building. */
    fun buildReport(
        appVersion: String,
        coreVersion: String,
        deviceInfo: String,
        serverLabel: String,
        tunnelStatus: String,
        exit: ExitInfo,
        dns: DnsInfo,
        logs: List<String>,
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
