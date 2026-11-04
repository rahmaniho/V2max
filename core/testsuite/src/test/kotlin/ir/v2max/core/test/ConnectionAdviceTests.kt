package ir.v2max.core.test

import ir.v2max.core.domain.ConnectionAdvice

/**
 * Tests for the connection-diagnosis rules.
 *
 * Three of these were already covered in the app's JUnit suite and are ported
 * here; the rest pin the boundaries that suite left open — the exact ratios
 * behind "most servers are down", when the balancer warning does and does not
 * fire, and the order findings are emitted in, which is the order the UI
 * renders them.
 */
object ConnectionAdviceTests {

    private fun advise(
        connected: Boolean = false,
        connecting: Boolean = false,
        killSwitchBlocked: Boolean = false,
        exitOk: Boolean = false,
        dnsDohOk: Boolean = false,
        dnsConsistent: Boolean = false,
        totalServers: Int = 20,
        aliveServers: Int = 20,
        balancerEnabled: Boolean = false,
        geoReady: Boolean = true,
        fragmentProfile: String = "BALANCED",
    ) = ConnectionAdvice.advise(
        connected = connected,
        connecting = connecting,
        killSwitchBlocked = killSwitchBlocked,
        exitOk = exitOk,
        dnsDohOk = dnsDohOk,
        dnsConsistent = dnsConsistent,
        totalServers = totalServers,
        aliveServers = aliveServers,
        balancerEnabled = balancerEnabled,
        geoReady = geoReady,
        fragmentProfile = fragmentProfile,
    )

    private fun has(list: List<ConnectionAdvice.Finding>, actionId: String) =
        list.any { it.actionId == actionId }

    fun run() {
        T.group("ConnectionAdvice")

        // ---- ported: traffic not passing suggests a stronger profile
        val noExit = advise(
            connected = true,
            exitOk = false,
            totalServers = 20,
            aliveServers = 12,
            balancerEnabled = true,
        )
        T.ok("no exit suggests aggressive fragment", has(noExit, ConnectionAdvice.Action.AGGRESSIVE_FRAGMENT)) {
            "actions=${noExit.map { it.actionId }}"
        }

        // ---- ported: a healthy connection produces exactly one OK finding
        val healthy = advise(
            connected = true,
            exitOk = true,
            dnsDohOk = true,
            dnsConsistent = true,
            totalServers = 30,
            aliveServers = 25,
            balancerEnabled = true,
        )
        T.eq("healthy yields one finding", 1, healthy.size)
        T.eq("healthy severity is OK", ConnectionAdvice.Severity.OK, healthy.first().severity)
        T.eq("healthy has no action", "", healthy.first().actionId)

        // ---- ported: kill switch is critical and offers unblock
        val blocked = advise(killSwitchBlocked = true, totalServers = 10, aliveServers = 10)
        T.ok("kill switch is critical with unblock", blocked.any {
            it.severity == ConnectionAdvice.Severity.CRITICAL &&
                it.actionId == ConnectionAdvice.Action.UNBLOCK
        }) { "findings=${blocked.map { it.severity to it.actionId }}" }

        // The list is never empty, so the screen is never blank.
        T.ok("never returns an empty list", blocked.isNotEmpty())

        // ---- idle: offer smart connect, but not while connecting
        val idle = advise()
        T.ok("idle offers smart connect", has(idle, ConnectionAdvice.Action.SMART_CONNECT)) {
            "actions=${idle.map { it.actionId }}"
        }
        T.ok(
            "connecting suppresses the idle finding",
            !has(advise(connecting = true), ConnectionAdvice.Action.SMART_CONNECT),
        )
        // Blocked already tells the user what to do; don't also tell them to connect.
        T.ok(
            "blocked suppresses the idle finding",
            !has(blocked, ConnectionAdvice.Action.SMART_CONNECT),
        )

        // ---- DNS branches are ordered: no answer at all, then a mismatch
        val noDoh = advise(connected = true, exitOk = true, dnsDohOk = false)
        T.ok("no DoH answer warns", noDoh.any {
            it.severity == ConnectionAdvice.Severity.WARN &&
                it.actionId == ConnectionAdvice.Action.AGGRESSIVE_FRAGMENT
        }) { "findings=${noDoh.map { it.title }}" }

        val mismatch = advise(connected = true, exitOk = true, dnsDohOk = true, dnsConsistent = false)
        T.ok("DNS mismatch warns", mismatch.any { it.severity == ConnectionAdvice.Severity.WARN }) {
            "findings=${mismatch.map { it.title }}"
        }
        // A mismatch has no fix to offer, so it carries no action button.
        T.ok("DNS mismatch has no action", mismatch.none { it.actionId.isNotEmpty() }) {
            "actions=${mismatch.map { it.actionId }}"
        }
        // The mismatch is only meaningful once DoH actually answered.
        T.ok(
            "no DoH answer does not also report a mismatch",
            noDoh.none { it.title == mismatch.first().title },
        )

        // ---- "most servers are down" is strictly below a quarter alive
        T.ok(
            "one in five alive warns",
            has(advise(connected = true, exitOk = true, dnsDohOk = true, dnsConsistent = true, totalServers = 20, aliveServers = 4), ConnectionAdvice.Action.REFRESH_SUBS),
        )
        // 5 of 20 is exactly a quarter: 5*4 == 20, not < 20, so no warning.
        T.ok(
            "exactly a quarter alive does not warn",
            !has(advise(connected = true, exitOk = true, dnsDohOk = true, dnsConsistent = true, totalServers = 20, aliveServers = 5), ConnectionAdvice.Action.REFRESH_SUBS),
        )
        // An empty list must not divide by zero into a warning.
        T.ok(
            "no servers at all does not warn",
            !has(advise(connected = true, exitOk = true, dnsDohOk = true, dnsConsistent = true, totalServers = 0, aliveServers = 0), ConnectionAdvice.Action.REFRESH_SUBS),
        )

        // ---- the balancer warning needs 1 or 2 alive, and the balancer on
        T.ok(
            "two alive with balancer warns",
            has(advise(connected = true, exitOk = true, dnsDohOk = true, dnsConsistent = true, balancerEnabled = true, aliveServers = 2), ConnectionAdvice.Action.DISABLE_BALANCER),
        )
        T.ok(
            "three alive is enough for the pool",
            !has(advise(connected = true, exitOk = true, dnsDohOk = true, dnsConsistent = true, balancerEnabled = true, aliveServers = 3), ConnectionAdvice.Action.DISABLE_BALANCER),
        )
        // Zero alive is a different problem entirely; the pool warning stays quiet.
        T.ok(
            "zero alive does not warn about the pool",
            !has(advise(connected = true, exitOk = true, dnsDohOk = true, dnsConsistent = true, balancerEnabled = true, aliveServers = 0), ConnectionAdvice.Action.DISABLE_BALANCER),
        )
        T.ok(
            "balancer off means no pool warning",
            !has(advise(connected = true, exitOk = true, dnsDohOk = true, dnsConsistent = true, balancerEnabled = false, aliveServers = 1), ConnectionAdvice.Action.DISABLE_BALANCER),
        )

        // ---- geo files
        T.ok("missing geo offers a download", has(advise(geoReady = false), ConnectionAdvice.Action.SYNC_GEO)) {
            "actions=${advise(geoReady = false).map { it.actionId }}"
        }
        T.ok("geo ready offers no download", !has(advise(geoReady = true), ConnectionAdvice.Action.SYNC_GEO))

        // ---- the LIGHT profile finding only applies to a connected tunnel
        val light = advise(connected = true, exitOk = false, fragmentProfile = "LIGHT")
        T.eq("LIGHT while connected adds two findings", 2, light.count {
            it.actionId == ConnectionAdvice.Action.AGGRESSIVE_FRAGMENT
        })
        // The general "traffic not passing" finding comes first, because it is
        // the more severe of the two and the UI renders in list order.
        T.eq(
            "the no-exit finding precedes the profile hint",
            ConnectionAdvice.Severity.CRITICAL,
            light.first { it.actionId == ConnectionAdvice.Action.AGGRESSIVE_FRAGMENT }.severity,
        )
        T.ok(
            "LIGHT while disconnected adds no profile hint",
            advise(connecting = false, fragmentProfile = "LIGHT").none {
                it.title == "پروفایل فرگمنت سبک انتخاب شده"
            },
        )
        T.ok(
            "a strong profile adds no hint",
            advise(connected = true, exitOk = false, fragmentProfile = "AGGRESSIVE").none {
                it.title == "پروفایل فرگمنت سبک انتخاب شده"
            },
        )

        // ---- several problems at once all surface, worst first
        val everything = advise(
            killSwitchBlocked = true,
            totalServers = 100,
            aliveServers = 1,
            balancerEnabled = true,
            geoReady = false,
        )
        T.ok("all conditions surface", everything.size >= 4) { "size=${everything.size}" }
        T.eq("kill switch is reported first", ConnectionAdvice.Severity.CRITICAL, everything.first().severity)

        // ---- the report is plain text, and blanks read as unknown
        val report = ConnectionAdvice.buildReport(
            appVersion = "1.5",
            coreVersion = "v26.10.21",
            deviceInfo = "Pixel 6",
            serverLabel = "FR-01",
            tunnelStatus = "connected",
            exit = ConnectionAdvice.ExitInfo(ip = "1.2.3.4", country = "FR", colo = "CDG"),
            dns = ConnectionAdvice.DnsInfo(dohAnswer = "9.9.9.9", systemAnswer = "9.9.9.9", dohOk = true, consistent = true),
            logs = listOf("line one"),
        )
        T.ok("report has a header", report.startsWith("=== گزارش عیب‌یابی V2max ===")) { report.take(40) }
        T.ok("report carries the exit IP", "1.2.3.4" in report)
        T.ok("report carries the colo", "CDG" in report)
        T.ok("report carries the log", "line one" in report)
        T.ok("consistent DNS reads as yes", "بله" in report)

        val sparse = ConnectionAdvice.buildReport(
            appVersion = "1.5",
            coreVersion = "c",
            deviceInfo = "d",
            serverLabel = "s",
            tunnelStatus = "off",
            exit = ConnectionAdvice.ExitInfo(),
            dns = ConnectionAdvice.DnsInfo(),
            logs = emptyList(),
        )
        T.ok("blank exit reads as unknown", "IP خروجی: نامشخص" in sparse) { sparse }
        T.ok("blank colo reads as a dash", "نود Cloudflare: -" in sparse)
        T.ok("no DoH answer says so", "بدون پاسخ" in sparse)
        T.ok("inconsistent DNS reads as no", "خیر/نامشخص" in sparse)
        T.ok("empty logs say so", "(خالی)" in sparse)

        // The report is pasted into chats, so it must not grow without bound.
        val many = (1..500).map { "log $it" }
        val trimmed = ConnectionAdvice.buildReport(
            appVersion = "v", coreVersion = "c", deviceInfo = "d", serverLabel = "s",
            tunnelStatus = "t", exit = ConnectionAdvice.ExitInfo(), dns = ConnectionAdvice.DnsInfo(),
            logs = many,
        )
        T.ok("report caps the log at 200 lines", "log 301\n" in trimmed && "log 300\n" !in trimmed) {
            "kept ${many.count { "log $it\n" in trimmed }} of ${many.size}"
        }
    }
}
