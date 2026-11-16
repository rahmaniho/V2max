package ir.v2max.core.domain

/**
 * Describe only the state the app actually knows. A successful Xray outbound
 * probe is useful, but cannot prove that another application's traffic crossed
 * Android's TUN. A missing or historical measurement is not a failed server.
 */
object ConnectionAdvice {
    enum class Severity { INFO, WARN, CRITICAL }
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    data class Finding(
        val title: String,
        val detail: String,
        val severity: Severity,
        val actionId: String = "",
        val actionLabel: String = "",
    )

    object Action {
        const val SMART_CONNECT = "smart_connect"
        const val TEST_SERVERS = "test_servers"
        const val SYNC_GEO = "sync_geo"
        const val UNBLOCK = "unblock"
    }

    data class Observation(
        val state: State,
        val killSwitchRetained: Boolean,
        /** Historical successful protocol measurements, not currently healthy servers. */
        val recordedRealPings: Int,
        val totalServers: Int,
        val geoRequired: Boolean,
        /** Null means the local files have not yet been inspected. */
        val geoReady: Boolean?,
    )

    fun stateLabel(state: State): String = when (state) {
        State.DISCONNECTED -> "قطع"
        State.CONNECTING -> "در حال اتصال"
        State.CONNECTED -> "متصل طبق وضعیت برنامه"
        State.DISCONNECTING -> "در حال قطع"
        State.ERROR -> "خطای اتصال"
    }

    fun advise(observation: Observation): List<Finding> {
        val out = ArrayList<Finding>()
        if (observation.killSwitchRetained) {
            out += Finding(
                title = "رابط VPN حفظ شده است",
                detail = "هسته متوقف شده است؛ برنامه‌های مستثنا ممکن است مستقیم وصل شوند و حفاظت با توقف سرویس پایان می‌یابد.",
                severity = Severity.CRITICAL,
                actionId = Action.UNBLOCK,
                actionLabel = "قطع VPN",
            )
        }

        when (observation.state) {
            State.CONNECTED -> out += Finding(
                title = "برنامه وضعیت اتصال را متصل گزارش می‌کند",
                detail = "پاسخ هسته به‌تنهایی عبور ترافیک برنامه‌های دیگر از VPN را ثابت نمی‌کند.",
                severity = Severity.INFO,
            )
            State.CONNECTING -> out += Finding(
                title = "اتصال در حال آماده‌سازی است",
                detail = "تا پایان تست‌های فعال و راه‌اندازی سرویس صبر کنید.",
                severity = Severity.INFO,
            )
            State.DISCONNECTING -> out += Finding(
                title = "اتصال در حال قطع‌شدن است",
                detail = "تا پایان توقف سرویس صبر کنید.",
                severity = Severity.INFO,
            )
            State.DISCONNECTED, State.ERROR -> if (!observation.killSwitchRetained) {
                out += Finding(
                    title = if (observation.state == State.ERROR) "اتصال با خطا متوقف شد" else "اتصال برقرار نیست",
                    detail = "اتصال هوشمند پس از تکمیل تست‌های فعال یک سرور پاسخ‌گو را انتخاب می‌کند.",
                    severity = Severity.INFO,
                    actionId = Action.SMART_CONNECT,
                    actionLabel = "اتصال هوشمند",
                )
            }
        }

        val total = observation.totalServers.coerceIn(0, 1_000_000)
        val recorded = observation.recordedRealPings.coerceIn(0, total)
        if (total > 0) {
            out += Finding(
                title = "نتایج ثبت‌شدهٔ سرورها",
                detail = "$recorded از $total سرور پینگ واقعی ثبت‌شده دارند؛ نتیجه ممکن است قدیمی باشد و بقیه الزاماً خراب نیستند.",
                severity = Severity.INFO,
            )
        }

        if (observation.geoRequired && observation.geoReady == false) {
            out += Finding(
                title = "فایل‌های مسیریابی جغرافیایی آماده نیستند",
                detail = "برای قوانین جغرافیایی فعال، فایل‌های geoip و geosite لازم‌اند.",
                severity = Severity.INFO,
                actionId = Action.SYNC_GEO,
                actionLabel = "دریافت فایل‌ها",
            )
        }
        return out
    }
}
