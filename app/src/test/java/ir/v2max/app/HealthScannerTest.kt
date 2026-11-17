package ir.v2max.app

import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.NetworkAssessment
import ir.v2max.app.data.PingTargets
import ir.v2max.app.data.Proto
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.net.HealthScanner
import ir.v2max.app.net.ProbePlanner
import ir.v2max.app.net.ScanDiagnostics
import ir.v2max.core.domain.ServerProbeCounts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HealthScannerTest {
    private val settings = AppSettings(fragment = false, multiPing = false, speedTest = false, deepProbe = false)

    private fun node(i: Int, source: String = "source-${i % 3}") = ServerConfig(
        id = "s$i", address = "node$i.example.org", port = 443, subId = source, security = "none"
    )

    private fun ops(
        tcp: suspend (ServerConfig) -> Int = { 20 },
        real: suspend (ServerConfig, AppSettings, String) -> Int = { _, _, _ -> 50 },
        speed: suspend (ServerConfig, AppSettings) -> Int? = { _, _ -> 0 }
    ) = HealthScanner.Operations(tcp, real, speed)

    @Test fun `fast path examines only a small window and completes the full real batch`() = runBlocking {
        val profiles = (0 until 450).map { node(it) }
        val realCalls = mutableListOf<String>()
        val published = mutableListOf<String>()
        val outcome = HealthScanner.scanPrepared(
            profiles, settings,
            ops(real = { cfg, _, _ -> realCalls.add(cfg.id); 50 }),
            onReal = { cfg, result -> if (result != null) published.add(cfg.id) }
        )
        assertEquals(24, outcome.precheckedCount)
        assertEquals(24, outcome.tcpConnections)
        assertEquals(6, outcome.testedCount)
        assertEquals(6, realCalls.size)
        assertEquals(realCalls.toSet(), published.toSet())
        assertEquals(6, outcome.qualified.size)
        assertNotNull(outcome.best)
    }

    @Test fun `fast switch uses recent real ping only as ordering and repeats every chosen protocol test`() = runBlocking {
        val now = System.currentTimeMillis()
        val fast = node(0, "A").copy(realPing = 12, testedAt = now, testedUrl = PingTargets.GOOGLE.url)
        val other = node(1, "B").copy(realPing = 30, testedAt = now, testedUrl = PingTargets.GOOGLE.url)
        val slow = node(2, "A").copy(realPing = 300, testedAt = now, testedUrl = PingTargets.GOOGLE.url)
        val favorite = node(3, "B").copy(favorite = true)
        val calls = mutableListOf<String>()
        val result = HealthScanner.scanPrepared(
            listOf(favorite, slow, other, fast), settings,
            ops(real = { cfg, _, _ -> calls.add(cfg.id); if (cfg.id == fast.id) 90 else 20 }),
            fastestRecentFirst = true
        )
        assertEquals(listOf(fast.id, other.id, slow.id, favorite.id), calls)
        assertEquals(4, result.testedCount)
        assertNotEquals("An old 12ms measurement must not be reused as proof", fast.id, result.best?.id)
        assertEquals(20, result.best?.realPing)
    }

    @Test fun `a tcp failure is deferred not discarded and all windows remain searchable`() = runBlocking {
        val profiles = (0 until 30).map { node(it) }
        val results = mutableMapOf<String, Boolean>()
        val outcome = HealthScanner.scanPrepared(
            profiles, settings,
            ops(tcp = { -1 }, real = { cfg, _, _ -> if (cfg.id == "s29") 80 else -1 }),
            onReal = { cfg, result -> results[cfg.id] = result != null }
        )
        assertEquals(30, outcome.precheckedCount)
        assertEquals(30, outcome.testedCount)
        assertEquals("s29", outcome.best?.id)
        assertEquals(30, results.size)
        assertEquals(true, results["s29"])
    }

    @Test fun `explicit selection gets protocol test even if tcp fails`() = runBlocking {
        val profiles = (0 until 25).map { node(it) }
        val outcome = HealthScanner.scanPrepared(
            profiles, settings, ops(tcp = { -1 }), preferredId = "s24", limit = 1
        )
        assertEquals("s24", outcome.best?.id)
        assertEquals(1, outcome.precheckedCount)
        assertEquals(1, outcome.testedCount)
    }

    @Test fun `udp candidates do not depend on tcp and are actually tested`() = runBlocking {
        val udp = node(1).copy(proto = Proto.HYSTERIA2, network = "hysteria")
        var tcpCalls = 0
        val outcome = HealthScanner.scanPrepared(listOf(udp), settings, ops(tcp = { tcpCalls++; -1 }))
        assertEquals(0, tcpCalls)
        assertEquals(1, outcome.testedCount)
        assertEquals(udp.id, outcome.best?.id)
    }

    @Test fun `same endpoint shares tcp handshake but every candidate gets its own real test`() = runBlocking {
        val profiles = (0 until 24).map { node(it).copy(address = "shared.example.org", subId = "one") }
        var tcpCalls = 0
        val outcome = HealthScanner.scanPrepared(profiles, settings, ops(tcp = { tcpCalls++; 30 }))
        assertEquals(1, tcpCalls)
        assertEquals(1, outcome.tcpConnections)
        assertEquals(6, outcome.testedCount)
        assertEquals(6, outcome.qualified.map { it.id }.toSet().size)
    }

    @Test fun `full list scan tests successes and tcp failures without stopping early`() = runBlocking {
        val profiles = (0 until 30).map { node(it) }
        val results = mutableSetOf<String>()
        val outcome = HealthScanner.scanPrepared(
            profiles, settings, ops(tcp = { if (it.id.drop(1).toInt() % 2 == 0) 10 else -1 }),
            exhaustive = true, onReal = { cfg, _ -> results.add(cfg.id) }
        )
        assertEquals(30, outcome.precheckedCount)
        assertEquals(30, outcome.testedCount)
        assertEquals(30, outcome.qualified.size)
        assertEquals(30, results.size)
    }

    @Test fun `cached success is never enough when the current protocol tests fail`() = runBlocking {
        val staleSuccess = node(0).copy(realPing = 10, testedUrl = PingTargets.GOOGLE.url, testedAt = System.currentTimeMillis())
        val outcome = HealthScanner.scanPrepared(listOf(staleSuccess), settings, ops(real = { _, _, _ -> -1 }))
        assertNull(outcome.best)
        assertTrue(outcome.qualified.isEmpty())
        assertEquals(1, outcome.testedCount)
    }

    @Test fun `setup failures remain indeterminate unless a later variant succeeds`() = runBlocking {
        val calls = mutableMapOf<String, Int>()
        val published = mutableSetOf<String>()
        val unknown = mutableSetOf<String>()
        val outcome = HealthScanner.scanPrepared(
            (0..3).map { node(it) }, settings,
            ops(real = { cfg, _, _ ->
                val attempt = (calls[cfg.id] ?: 0) + 1
                calls[cfg.id] = attempt
                when (cfg.id) {
                    "s0" -> 20
                    "s1" -> -2
                    "s2" -> if (attempt == 1) -1 else -2
                    else -> if (attempt == 1) -2 else 30
                }
            }), exhaustive = true,
            onReal = { cfg, _ -> published.add(cfg.id) },
            onIndeterminate = { cfg -> unknown.add(cfg.id) }
        )
        assertEquals(4, outcome.testedCount)
        assertEquals(setOf("s1", "s2"), outcome.indeterminateIds)
        assertEquals(outcome.indeterminateIds, unknown)
        assertEquals(setOf("s0", "s3"), published)
        assertEquals(setOf("s0", "s3"), outcome.qualified.map { it.id }.toSet())
        assertEquals(2, calls["s3"])
    }

    @Test fun `diagnostics distinguish protocol responses no response and indeterminate from tcp only`() = runBlocking {
        val diagnostics = ScanDiagnostics(4)
        val outcome = HealthScanner.scanPrepared(
            (0..3).map { node(it) }, settings,
            ops(tcp = { if (it.id == "s3") -1 else 10 }, real = { cfg, _, _ ->
                when (cfg.id) { "s0" -> 30; "s1" -> -1; else -> -2 }
            }),
            onReal = { cfg, result -> diagnostics.onReal(cfg.id, result) },
            onIndeterminate = { cfg -> diagnostics.onIndeterminate(cfg.id) }
        )
        assertEquals(3, outcome.testedCount)
        assertEquals(ServerProbeCounts(responded = 1, noResponse = 1, unknown = 2),
            diagnostics.complete(outcome.testedCount))
    }

    @Test fun `a speed setup failure publishes no completed diagnostic summary`() = runBlocking {
        val diagnostics = ScanDiagnostics(2)
        val outcome = HealthScanner.scanPrepared(
            (0..1).map { node(it) }, settings.copy(speedTest = true),
            ops(speed = { _, _ -> null }),
            onReal = { cfg, result -> diagnostics.onReal(cfg.id, result) },
            onIndeterminate = { cfg -> diagnostics.onIndeterminate(cfg.id) }
        )
        assertEquals(2, outcome.testedCount)
        assertNull(diagnostics.complete(outcome.testedCount))
    }

    @Test fun `all enabled extra tests finish before any results are published`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var speedDone = 0
        val published = mutableListOf<String>()
        val detailed = settings.copy(multiPing = true, speedTest = true, speedTestCount = 2)
        val task = async {
            HealthScanner.scanPrepared(
                (0 until 3).map { node(it) }, detailed,
                ops(
                    real = { cfg, _, url ->
                        if (cfg.id == "s2" && url == PingTargets.YOUTUBE.url) {
                            entered.complete(Unit)
                            release.await()
                        }
                        50
                    },
                    speed = { _, _ -> speedDone++; 3000 }
                ),
                onReal = { cfg, _ ->
                    assertEquals("speed tests must finish first", 2, speedDone)
                    published.add(cfg.id)
                }
            )
        }
        withTimeout(5_000) { entered.await() }
        assertFalse(task.isCompleted)
        assertTrue(published.isEmpty())
        release.complete(Unit)
        val outcome = withTimeout(5_000) { task.await() }
        assertEquals(3, published.size)
        assertEquals(2, speedDone)
        assertTrue(outcome.speedCompared)
    }

    @Test fun `enabled speed test runs even with legacy data saver setting`() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = HealthScanner.scanPrepared(
            listOf(node(0), node(1)), settings.copy(speedTest = true, dataSaver = true, speedTestCount = 1),
            ops(speed = { cfg, _ -> calls.add(cfg.id); 0 })
        )
        assertEquals(1, calls.size)
        assertEquals(calls.toSet(), outcome.speedTestedIds)
        // Even a failed download was attempted; an untested profile cannot be selected.
        assertEquals(calls.single(), outcome.best?.id)
        assertEquals(calls.toSet(), outcome.qualified.map { it.id }.toSet())
    }

    @Test fun `speed setup failure leaves no connectable candidate or partial result`() = runBlocking {
        var published = 0
        val outcome = HealthScanner.scanPrepared(
            listOf(node(0)), settings.copy(speedTest = true),
            ops(speed = { _, _ -> null }), onReal = { _, _ -> published++ }
        )
        assertNull(outcome.best)
        assertTrue(outcome.qualified.isEmpty())
        assertEquals(0, published)
        assertTrue(outcome.message.contains("سنجش سرعت"))
    }

    @Test fun `an explicit choice gets a speed attempt before selection`() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = HealthScanner.scanPrepared(
            listOf(node(0), node(1)), settings.copy(speedTest = true, speedTestCount = 1),
            ops(
                real = { cfg, _, _ -> if (cfg.id == "s1") 900 else 50 },
                speed = { cfg, _ -> calls.add(cfg.id); 100 }
            ), preferredId = "s1"
        )
        assertEquals(listOf("s1"), calls)
        assertEquals("s1", outcome.best?.id)
    }

    @Test fun `cancellation does not publish partially tested successes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val task = async {
            HealthScanner.scanPrepared(
                (0 until 3).map { node(it) }, settings,
                ops(real = { cfg, _, _ ->
                    if (cfg.id == "s2") { entered.complete(Unit); release.await() }
                    50
                }),
                onReal = { cfg, _ -> published.add(cfg.id) }
            )
        }
        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { task.cancelAndJoin() }
        assertTrue(published.isEmpty())
    }

    @Test fun `multi destination option really tests google when the fallback succeeded`() = runBlocking {
        val urls = mutableListOf<String>()
        var multi: ServerConfig? = null
        val outcome = HealthScanner.scanPrepared(
            listOf(node(0)), settings.copy(multiPing = true),
            ops(real = { _, _, url -> urls.add(url); if (url == PingTargets.GOOGLE.url) -1 else 70 }),
            onMulti = { multi = it }
        )
        assertEquals(2, urls.count { it == PingTargets.GOOGLE.url })
        assertTrue(PingTargets.INSTAGRAM.url in urls)
        assertTrue(PingTargets.YOUTUBE.url in urls)
        assertEquals(-1, multi?.googlePing)
        assertEquals(70, multi?.instagramPing)
        assertEquals(ProbePlanner.FALLBACK_URL, outcome.best?.testedUrl)
    }

    @Test fun `latency selection ignores stale bandwidth and speed mode uses current samples`() {
        val lowLatency = node(0).copy(realPing = 50, speedKbps = 0, testedUrl = PingTargets.GOOGLE.url)
        val oldSpeed = node(1).copy(realPing = 130, speedKbps = 100_000, testedUrl = PingTargets.GOOGLE.url)
        val fastest = node(2).copy(realPing = 160, speedKbps = 1, testedUrl = PingTargets.GOOGLE.url)
        assertEquals("s0", ProbePlanner.choose(listOf(lowLatency, oldSpeed, fastest))?.id)
        assertEquals("s2", ProbePlanner.choose(listOf(lowLatency, oldSpeed, fastest), speeds = mapOf("s0" to 500, "s2" to 10_000))?.id)
        assertEquals("s0", ProbePlanner.choose(listOf(lowLatency, fastest), preferredId = "s0", speeds = mapOf("s2" to 10_000))?.id)
        // A near-tie in download speed is decided by latency rather than noise.
        assertEquals("s0", ProbePlanner.choose(listOf(lowLatency, fastest), speeds = mapOf("s0" to 8_500, "s2" to 10_000))?.id)
    }

    @Test fun `future network assessment is not treated as fresh`() {
        assertFalse(NetworkAssessment(assessedAt = System.currentTimeMillis() + 60_000).fresh)
        assertTrue(NetworkAssessment(assessedAt = System.currentTimeMillis()).fresh)
    }

    @Test fun `recent working variant is tried first but must succeed again`() = runBlocking {
        val server = node(0).copy(
            security = "tls", realPing = 90, testedAt = System.currentTimeMillis(),
            testedUrl = ProbePlanner.FALLBACK_URL, testedFragment = true,
            testedFragmentProfile = FragmentProfile.MAHSA
        )
        val tried = mutableListOf<Pair<Boolean, String>>()
        val result = ProbePlanner.verify(server, settings.copy(fragment = true, fragmentProfile = FragmentProfile.MAHSA)) { _, variant, url ->
            tried.add(variant.fragment to url)
            if (variant.fragment && url == ProbePlanner.FALLBACK_URL) 80 else -1
        }
        assertEquals(listOf(true to ProbePlanner.FALLBACK_URL), tried)
        assertEquals(80, result?.delayMs)

        tried.clear()
        val disabled = ProbePlanner.verify(server, settings.copy(fragment = false)) { _, variant, url ->
            tried.add(variant.fragment to url); 40
        }
        assertFalse(disabled!!.fragment)
        assertTrue(tried.all { !it.first })
    }
}
