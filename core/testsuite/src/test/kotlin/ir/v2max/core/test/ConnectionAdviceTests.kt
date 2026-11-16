package ir.v2max.core.test

import ir.v2max.core.domain.ConnectionAdvice
import ir.v2max.core.domain.PrivateDiagnosticReport
import ir.v2max.core.domain.PrivateErrorRecord
import ir.v2max.core.domain.ServerDiagnostics
import ir.v2max.core.domain.ServerProbeCounts
import ir.v2max.core.domain.ServerProbeEvidence

object ConnectionAdviceTests {
    private fun observation(
        state: ConnectionAdvice.State = ConnectionAdvice.State.DISCONNECTED,
        retained: Boolean = false,
        recorded: Int = 0,
        total: Int = 0,
        geoRequired: Boolean = false,
        geoReady: Boolean? = false,
    ) = ConnectionAdvice.Observation(state, retained, recorded, total, geoRequired, geoReady)

    fun run() {
        T.group("Local diagnostics and private reports")
        val idle = ConnectionAdvice.advise(observation())
        T.ok("idle suggests a connection rather than claiming a failed probe",
            idle.any { it.actionId == ConnectionAdvice.Action.SMART_CONNECT } &&
                idle.none { it.severity == ConnectionAdvice.Severity.CRITICAL })
        T.ok("idle has no speculative DNS or exit diagnosis",
            idle.none { (it.title + it.detail).contains("DNS") || (it.title + it.detail).contains("قطعی تانل") })

        val connecting = ConnectionAdvice.advise(observation(state = ConnectionAdvice.State.CONNECTING))
        T.ok("connecting is not reported as disconnected",
            connecting.any { it.title.contains("آماده‌سازی") } &&
                connecting.none { it.actionId == ConnectionAdvice.Action.SMART_CONNECT })
        val connected = ConnectionAdvice.advise(observation(state = ConnectionAdvice.State.CONNECTED))
        T.ok("connected is attributed to app state", connected.any { it.title.contains("برنامه") })
        T.ok("an outbound response is not proof of Android TUN", connected.any { it.detail.contains("ثابت نمی‌کند") })
        T.ok("not running an exit test does not trigger a failure", connected.none { it.severity == ConnectionAdvice.Severity.CRITICAL })

        val retained = ConnectionAdvice.advise(observation(state = ConnectionAdvice.State.CONNECTING, retained = true))
        T.eq("retained interface is first and critical", ConnectionAdvice.Severity.CRITICAL, retained.first().severity)
        T.eq("retained interface offers user stop", ConnectionAdvice.Action.UNBLOCK, retained.first().actionId)
        T.ok("retention warns about excluded apps", retained.first().detail.contains("مستثنا"))
        T.ok("stopping is not described as total lockdown", retained.first().detail.contains("توقف سرویس"))

        val error = ConnectionAdvice.advise(observation(state = ConnectionAdvice.State.ERROR))
        T.ok("error is not silently called disconnected", error.any { it.title.contains("خطا") })
        val counts = ConnectionAdvice.advise(observation(total = 5, recorded = 20))
        T.ok("impossible historical counts are clamped", counts.any { it.detail.contains("5 از 5") })
        T.ok("missing pings are not automatically called dead", counts.any { it.detail.contains("الزاماً خراب نیستند") })
        T.ok("geo fallback not suggested when not required",
            ConnectionAdvice.advise(observation(geoRequired = false)).none { it.actionId == ConnectionAdvice.Action.SYNC_GEO })
        T.ok("incomplete required geo offers sync",
            ConnectionAdvice.advise(observation(geoRequired = true)).any { it.actionId == ConnectionAdvice.Action.SYNC_GEO })
        T.ok("ready geo does not offer sync",
            ConnectionAdvice.advise(observation(geoRequired = true, geoReady = true)).none { it.actionId == ConnectionAdvice.Action.SYNC_GEO })
        T.ok("uninspected geo is not treated as missing",
            ConnectionAdvice.advise(observation(geoRequired = true, geoReady = null)).none { it.actionId == ConnectionAdvice.Action.SYNC_GEO })

        T.eq("untested and TCP-only candidates are unknown", ServerProbeCounts(0, 0, 3),
            ServerDiagnostics.summarize(3, emptyList()))
        T.eq("only explicit real-probe outcomes are classified", ServerProbeCounts(1, 1, 3),
            ServerDiagnostics.summarize(5, listOf(ServerProbeEvidence.RESPONDED,
                ServerProbeEvidence.NO_RESPONSE, ServerProbeEvidence.UNKNOWN)))
        T.eq("malformed overcomplete evidence fails closed", ServerProbeCounts(0, 0, 1),
            ServerDiagnostics.summarize(1, listOf(ServerProbeEvidence.RESPONDED, ServerProbeEvidence.NO_RESPONSE)))
        T.eq("empty candidate list", ServerProbeCounts(0, 0, 0), ServerDiagnostics.summarize(0, emptyList()))
        T.eq("negative candidate count", ServerProbeCounts(0, 0, 0), ServerDiagnostics.summarize(-1, emptyList()))

        val secret = "vless://01234567-89ab-cdef-0123-456789abcdef@secret.example:443?token=abc#server"
        val errorWithSecrets = java.io.IOException("IP=203.0.113.42 $secret")
        errorWithSecrets.stackTrace = arrayOf(StackTraceElement("leaky.$secret", "call", "/private/$secret", 1))
        val safeError = PrivateErrorRecord.classify(errorWithSecrets, "vpnService")
        T.eq("classify known error", PrivateErrorRecord.Summary(PrivateErrorRecord.Location.VPN, PrivateErrorRecord.Category.IO), safeError)
        T.eq("safe record round trip", safeError, PrivateErrorRecord.decode(PrivateErrorRecord.encode(safeError)))
        T.ok("raw exception and stack are not formatted", secret !in PrivateErrorRecord.format(safeError) &&
            "203.0.113.42" !in PrivateErrorRecord.encode(safeError))
        T.eq("unknown thread tags are not serialized", PrivateErrorRecord.Location.OTHER,
            PrivateErrorRecord.classify(errorWithSecrets, secret).location)
        T.eq("legacy raw line cannot be decoded", null, PrivateErrorRecord.decode("=== $secret ==="))
        T.eq("unrecognized record values fail closed", null, PrivateErrorRecord.decode("V2MAX_SAFE_V1|VPN|$secret"))

        val snapshot = PrivateDiagnosticReport.Snapshot(
            appVersion = "2.4.0-preview", coreVersion = "v26.10.21", androidApi = 35,
            observation = observation(state = ConnectionAdvice.State.CONNECTED, recorded = 1, total = 3),
            scan = ServerProbeCounts(1, 1, 1), localError = safeError,
        )
        val report = PrivateDiagnosticReport.build(snapshot)
        T.ok("report has private header", report.startsWith("=== گزارش خصوصی V2max ==="))
        T.ok("app and core versions are allowlisted", "2.4.0-preview" in report && "v26.10.21" in report)
        T.ok("IP and DNS are explicitly unknown", "IP خروجی VPN: نامشخص" in report && "DNS و نشت DNS: نامشخص" in report)
        T.ok("report does not claim TUN was tested", "عبور ترافیک برنامه‌های دیگر از TUN: تأیید نشده" in report)
        T.ok("report contains categorized scan outcomes", "پاسخ‌گو 1، بی‌پاسخ 1، نامشخص 1" in report)
        T.ok("only a fixed error category is included", "ورودی/خروجی" in report && secret !in report && "203.0.113.42" !in report)
        T.ok("the report does not accept server or log text", "نام سرور، IP" in report && "متن خام خطا یا لاگ" in report)
        val unavailable = PrivateDiagnosticReport.build(snapshot.copy(
            appVersion = secret, coreVersion = "203.0.113.42\n$secret", androidApi = -1,
            scan = null, localError = PrivateErrorRecord.unknown,
        ))
        T.ok("untrusted version strings and newlines are rejected", secret !in unavailable && "203.0.113.42" !in unavailable)
        val versionIp = PrivateDiagnosticReport.build(snapshot.copy(appVersion = "203.0.113.42", coreVersion = "203.0.113.42"))
        T.ok("an IP-shaped value is not accepted as a version", "203.0.113.42" !in versionIp)
        T.ok("arbitrary version suffixes are not accepted", "fixtureSecret" !in
            PrivateDiagnosticReport.build(snapshot.copy(appVersion = "2.4.0-fixtureSecret")))
        T.ok("missing scan remains unknown", "آخرین اسکن انتخاب یا گروهی کامل: نامشخص" in unavailable)
        T.ok("invalid API remains unknown", "سطح API اندروید: نامشخص" in unavailable)
        val uninspectedGeo = PrivateDiagnosticReport.build(snapshot.copy(observation = observation(geoRequired = true, geoReady = null)))
        T.ok("uninspected geo stays unknown in the report", "فایل‌های جغرافیایی: نامشخص" in uninspectedGeo)
        val malformed = PrivateDiagnosticReport.build(snapshot.copy(scan = ServerProbeCounts(-1, 1, 0)))
        T.ok("negative scan counts remain unknown", "آخرین اسکن انتخاب یا گروهی کامل: نامشخص" in malformed)
        val overflow = PrivateDiagnosticReport.build(snapshot.copy(scan = ServerProbeCounts(Int.MAX_VALUE, 1, 1)))
        T.ok("overflow scan counts remain unknown", "آخرین اسکن انتخاب یا گروهی کامل: نامشخص" in overflow)
        T.ok("report is small enough for preview", report.length < 2_000)
    }
}
