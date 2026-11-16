package ir.v2max.core.domain

/**
 * An allowlisted report: there is deliberately no input for server labels,
 * endpoints, IP/DNS answers, exception messages, URLs, credentials or logs.
 * Build the text once and show that exact text before the user copies/shares it.
 */
object PrivateDiagnosticReport {
    data class Snapshot(
        val appVersion: String,
        val coreVersion: String,
        val androidApi: Int,
        val observation: ConnectionAdvice.Observation,
        val scan: ServerProbeCounts?,
        val localError: PrivateErrorRecord.Summary?,
    )

    // Do not accept arbitrary suffixes, URLs, IP addresses or native messages as versions.
    private val appVersion = Regex("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,2}(?:-(?:preview|emulator-upgrade))?")
    private val coreVersion = Regex("v?[0-9]{1,4}(?:\\.[0-9]{1,4}){1,2}")
    private fun safeVersion(value: String, pattern: Regex): String = value.takeIf { pattern.matches(it) } ?: "نامشخص"

    fun build(snapshot: Snapshot): String = buildString {
        val observation = snapshot.observation
        val total = observation.totalServers.coerceIn(0, 1_000_000)
        val recorded = observation.recordedRealPings.coerceIn(0, total)
        appendLine("=== گزارش خصوصی V2max ===")
        appendLine("نسخهٔ برنامه: ${safeVersion(snapshot.appVersion, appVersion)}")
        appendLine("نسخهٔ هسته: ${safeVersion(snapshot.coreVersion, coreVersion)}")
        appendLine("سطح API اندروید: ${snapshot.androidApi.takeIf { it in 1..100 }?.toString() ?: "نامشخص"}")
        appendLine("وضعیت برنامه: ${ConnectionAdvice.stateLabel(observation.state)}")
        appendLine("رابط VPN پس از توقف هسته: ${if (observation.killSwitchRetained) "حفظ شده" else "حفظ‌شده گزارش نشده"}")
        appendLine("عبور ترافیک برنامه‌های دیگر از TUN: تأیید نشده")
        appendLine("IP خروجی VPN: نامشخص؛ آزمون مسیر VPN انجام نشده")
        appendLine("DNS و نشت DNS: نامشخص؛ آزمون مسیر VPN انجام نشده")
        appendLine("پینگ واقعی ثبت‌شده: $recorded از $total سرور؛ نتیجه ممکن است قدیمی باشد")
        val scan = snapshot.scan
        if (scan == null || scan.responded < 0 || scan.noResponse < 0 || scan.unknown < 0 ||
            scan.responded.toLong() + scan.noResponse + scan.unknown > 1_000_000L) {
            appendLine("آخرین اسکن انتخاب یا گروهی کامل: نامشخص")
        } else {
            appendLine("آخرین اسکن انتخاب یا گروهی کامل: پاسخ‌گو ${scan.responded}، بی‌پاسخ ${scan.noResponse}، نامشخص ${scan.unknown}")
            appendLine("نتایج اسکن نمونهٔ گذشته‌اند؛ بی‌پاسخ به معنی خرابی دائمی نیست")
        }
        appendLine("فایل‌های جغرافیایی: ${when {
            !observation.geoRequired -> "برای مسیریابی فعلی لازم نیست"
            observation.geoReady == true -> "آماده"
            observation.geoReady == false -> "آماده نیست"
            else -> "نامشخص؛ فایل‌ها هنوز بررسی نشده‌اند"
        }}")
        appendLine("خطای محلی ثبت‌شده: ${snapshot.localError?.let(PrivateErrorRecord::format) ?: "گزارش نشده"}")
        append("نام سرور، IP، نشانی‌ها، جزئیات DNS، لینک‌ها و متن خام خطا یا لاگ در این گزارش نیستند.")
    }
}
