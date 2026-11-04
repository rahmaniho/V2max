package ir.v2max.core.test

import ir.v2max.core.model.Endpoint
import ir.v2max.core.model.Measurement
import ir.v2max.core.model.Proto
import ir.v2max.core.model.Server
import ir.v2max.core.model.ServerHealth

/**
 * Tests for the health and ranking arithmetic.
 *
 * These numbers decide which server gets connected, so the interesting cases are
 * the boundaries: an unmeasured server, a speed that exceeds the penalty cap, a
 * score that would go negative, and the requirement that [Server] and the bare
 * functions cannot disagree.
 */
object ServerHealthTests {

    private fun server(
        tcpPing: Int = -1,
        realPing: Int = -1,
        googlePing: Int = -1,
        instagramPing: Int = -1,
        youtubePing: Int = -1,
        speedKbps: Int = 0,
        successCount: Int = 0,
        failCount: Int = 0,
    ) = Server(
        id = "s",
        name = "s",
        proto = Proto.VLESS,
        endpoint = Endpoint("s.example.com", 443),
        measurement = Measurement(
            tcpPing = tcpPing,
            realPing = realPing,
            googlePing = googlePing,
            instagramPing = instagramPing,
            youtubePing = youtubePing,
            speedKbps = speedKbps,
            successCount = successCount,
            failCount = failCount,
        ),
    )

    fun run() {
        T.group("ServerHealth")

        // ---- reachCount counts only positive answers
        T.eq("nothing answered", 0, ServerHealth.reachCount(-1, -1, -1))
        T.eq("all three answered", 3, ServerHealth.reachCount(10, 20, 30))
        T.eq("one answered", 1, ServerHealth.reachCount(-1, 20, -1))
        // Zero and negative both mean "not measured", not "instant".
        T.eq("a zero ping does not count", 0, ServerHealth.reachCount(0, 0, 0))
        T.eq("a negative ping does not count", 0, ServerHealth.reachCount(-5, -1, -99))

        // ---- stability
        T.eq("never attempted is middling", 70, ServerHealth.stability(0, 0))
        T.eq("no failures is perfect", 100, ServerHealth.stability(5, 0))
        T.eq("all failures is zero", 0, ServerHealth.stability(0, 5))
        T.eq("half and half", 50, ServerHealth.stability(5, 5))
        T.eq("integer division truncates", 66, ServerHealth.stability(2, 1))
        T.eq("clamped at 100", 100, ServerHealth.stability(1000, 0))

        // ---- bestPing prefers a real measurement over TCP
        T.eq("nothing measured", -1, ServerHealth.bestPing(-1, -1, -1, -1, -1))
        T.eq("tcp only", 80, ServerHealth.bestPing(80, -1, -1, -1, -1))
        T.eq("a real ping beats tcp", 40, ServerHealth.bestPing(80, 40, -1, -1, -1))
        T.eq("the fastest real target wins", 30, ServerHealth.bestPing(80, 40, 55, 30, 90))
        T.eq("a zero tcp ping is not a result", -1, ServerHealth.bestPing(0, -1, -1, -1, -1))
        T.eq("zero among real pings is skipped", 30, ServerHealth.bestPing(80, 0, 30, -1, -1))

        // ---- score
        // Unmeasured: 3500 baseline + 150 for no speed + (100-70)*2 for unknown
        // stability = 3710. This is the value that sorts a new server last.
        T.eq("an unmeasured server sorts last", 3710, ServerHealth.score(-1, 0, 70))
        // A measured server with no speed: 100 + 150 + 0.
        T.eq("measured without speed", 250, ServerHealth.score(100, 0, 100))
        // Speed earns points back: 500 kbps is exactly one 10-point step.
        T.eq("500 kbps earns back 10", 90, ServerHealth.score(100, 500, 100))
        T.eq("499 kbps earns back nothing", 100, ServerHealth.score(100, 499, 100))
        T.eq("the speed credit caps at 140", 10, ServerHealth.score(150, 7000, 100))
        T.eq("the cap holds far beyond it", 10, ServerHealth.score(150, 100_000, 100))
        // Instability costs twice its distance from perfect, on top of the
        // no-speed penalty: 100 + 150 + (100-0)*2.
        T.eq("instability is doubled", 450, ServerHealth.score(100, 0, 0))
        T.eq("half stability costs 100", 350, ServerHealth.score(100, 0, 50))
        // The floor keeps the score a usable positive sort key.
        T.eq("the score never goes below 1", 1, ServerHealth.score(1, 100_000, 100))

        // ---- Server and the bare functions must agree.
        //
        // The app's persistence layer calls ServerHealth directly over flat
        // fields, while core goes through Server/Measurement. If these ever
        // disagree, the app and the core would rank the same server differently.
        val cases = listOf(
            server(),
            server(tcpPing = 80),
            server(realPing = 40, speedKbps = 1200),
            server(googlePing = 55, instagramPing = 30, youtubePing = 90, speedKbps = 7000),
            server(realPing = 1, speedKbps = 100_000, successCount = 3, failCount = 9),
            server(successCount = 7, failCount = 1, speedKbps = 500),
        )
        for (s in cases) {
            val m = s.measurement
            T.ok("Server.bestPing matches ServerHealth", s.bestPing() == ServerHealth.bestPing(
                m.tcpPing, m.realPing, m.googlePing, m.instagramPing, m.youtubePing,
            )) { "server=${s.bestPing()}" }
            T.ok("Measurement.stability matches ServerHealth", m.stability() == ServerHealth.stability(
                m.successCount, m.failCount,
            )) { "measurement=${m.stability()}" }
            T.ok("Measurement.reachCount matches ServerHealth", m.reachCount() == ServerHealth.reachCount(
                m.googlePing, m.instagramPing, m.youtubePing,
            )) { "measurement=${m.reachCount()}" }
            T.ok("Server.score matches ServerHealth", s.score() == ServerHealth.score(
                bestPing = s.bestPing(),
                speedKbps = m.speedKbps,
                stability = m.stability(),
            )) { "server=${s.score()}" }
        }
    }
}
