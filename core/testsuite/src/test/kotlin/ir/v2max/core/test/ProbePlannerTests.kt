package ir.v2max.core.test

import ir.v2max.core.domain.ProbePlanner
import java.util.Locale

/**
 * Tests for the probe rules.
 *
 * These decide which servers are reachable at all, so the interesting cases are
 * the ones that look equivalent but are not: a URL with no scheme, a security
 * value in the wrong case, a TCP-only success, and a transport name in
 * uppercase on a device whose locale lowercases "I" without a dot.
 */
object ProbePlannerTests {

    private data class Cand(val id: String, val realPing: Int, val testedUrl: String, val score: Int)

    private fun choose(list: List<Cand>, preferredId: String? = null) = ProbePlanner.choose(
        candidates = list,
        preferredId = preferredId,
        idOf = { it.id },
        realPingOf = { it.realPing },
        testedUrlOf = { it.testedUrl },
        scoreOf = { it.score },
    )

    fun run() {
        T.group("ProbePlanner")

        // ================= usesUdp =================
        T.ok("hysteria2 is UDP", ProbePlanner.usesUdp(protoIsHysteria2 = true, network = "ws"))
        for (net in listOf("hysteria", "kcp", "mkcp", "quic")) {
            T.ok("$net is UDP", ProbePlanner.usesUdp(false, net))
        }
        // Matching is case-insensitive, so an uppercase transport still counts.
        for (net in listOf("KCP", "MKCP", "QUIC", "Hysteria")) {
            T.ok("$net is UDP regardless of case", ProbePlanner.usesUdp(false, net))
        }
        for (net in listOf("ws", "tcp", "grpc", "")) {
            T.ok("$net is not UDP", !ProbePlanner.usesUdp(false, net)) { "matched for '$net'" }
        }
        // The protocol flag wins even when the transport field says otherwise.
        T.ok("hysteria2 flag overrides the transport", ProbePlanner.usesUdp(true, "ws"))

        // The transport check is case-insensitive and locale-independent:
        // Kotlin's lowercase() takes no locale, so a device set to Turkish —
        // where "I" would otherwise lower to a dotless "ı" — cannot turn "QUIC"
        // into something unmatched and get a working server marked dead.
        val savedLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr"))
            T.ok("QUIC matches under a Turkish locale", ProbePlanner.usesUdp(false, "QUIC"))
            T.ok("KCP matches under a Turkish locale", ProbePlanner.usesUdp(false, "KCP"))
        } finally {
            Locale.setDefault(savedLocale)
        }

        // ================= testUrls =================
        T.eq(
            "blank falls back to the default",
            listOf(ProbePlanner.DEFAULT_TEST_URL, ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls(""),
        )
        T.eq(
            "whitespace falls back to the default",
            listOf(ProbePlanner.DEFAULT_TEST_URL, ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls("   "),
        )
        T.eq(
            "an https URL is used as given",
            listOf("https://example.com/x", ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls("https://example.com/x"),
        )
        T.eq(
            "an http URL is accepted too",
            listOf("http://example.com/x", ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls("http://example.com/x"),
        )
        T.eq(
            "surrounding whitespace is trimmed",
            listOf("https://example.com/x", ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls("  https://example.com/x  "),
        )
        // No scheme means the probe could never work, so it is not trusted.
        T.eq(
            "a bare host falls back to the default",
            listOf(ProbePlanner.DEFAULT_TEST_URL, ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls("example.com/generate_204"),
        )
        T.eq(
            "an unusable scheme falls back to the default",
            listOf(ProbePlanner.DEFAULT_TEST_URL, ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls("ftp://example.com"),
        )
        // The scheme check is case-sensitive; this pins current behaviour rather
        // than endorsing it, so a change here is a deliberate one.
        T.eq(
            "an uppercase scheme falls back to the default",
            listOf(ProbePlanner.DEFAULT_TEST_URL, ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls("HTTPS://example.com"),
        )
        // Choosing the fallback yourself must not produce it twice.
        T.eq(
            "choosing the fallback yields one URL",
            listOf(ProbePlanner.FALLBACK_URL),
            ProbePlanner.testUrls(ProbePlanner.FALLBACK_URL),
        )
        T.ok("the fallback is always last", ProbePlanner.testUrls("https://a.test").last() == ProbePlanner.FALLBACK_URL)

        // ================= canFragment =================
        fun can(
            fragmentEnabled: Boolean = true,
            finalMaskBlank: Boolean = true,
            isUdp: Boolean = false,
            security: String = "tls",
            protoIsTrojan: Boolean = false,
        ) = ProbePlanner.canFragment(fragmentEnabled, finalMaskBlank, isUdp, security, protoIsTrojan)

        T.ok("tls can fragment", can(security = "tls"))
        T.ok("reality can fragment", can(security = "reality"))
        T.ok("trojan with no security can fragment", can(security = "", protoIsTrojan = true))
        T.ok("trojan over tls can fragment", can(security = "tls", protoIsTrojan = true))
        T.ok("fragmentation off means no retry", !can(fragmentEnabled = false))
        T.ok("an existing final mask blocks fragmentation", !can(finalMaskBlank = false))
        T.ok("UDP cannot be fragmented", !can(isUdp = true))
        // A non-Trojan server with empty security has nothing to fragment.
        T.ok("empty security without trojan cannot fragment", !can(security = ""))
        T.ok("plaintext cannot fragment", !can(security = "none"))
        T.ok("ws security cannot fragment", !can(security = "ws"))
        // Case-sensitive on purpose: a config shouting "TLS" is malformed, and
        // accepting it would start a retry that cannot help.
        T.ok("uppercase TLS is not accepted", !can(security = "TLS")) { "accepted 'TLS'" }
        T.ok("uppercase REALITY is not accepted", !can(security = "REALITY"))

        // ================= choose =================
        T.eq("no candidates yields nothing", null, choose(emptyList())?.id)

        // A failed protocol test never qualifies, however good the TCP ping.
        val tcpOnly = Cand("tcp", realPing = -1, testedUrl = "", score = 10)
        T.eq("a TCP-only result is not a candidate", null, choose(listOf(tcpOnly))?.id)

        // Both halves of the qualification are required.
        T.eq(
            "a positive ping with no recorded URL is not a candidate",
            null,
            choose(listOf(Cand("x", realPing = 120, testedUrl = "", score = 10)))?.id,
        )
        T.eq(
            "a recorded URL with no positive ping is not a candidate",
            null,
            choose(listOf(Cand("x", realPing = -1, testedUrl = "https://a", score = 10)))?.id,
        )
        // The ping must be strictly positive; zero means "not measured".
        T.eq(
            "a zero ping is not a candidate",
            null,
            choose(listOf(Cand("x", realPing = 0, testedUrl = "https://a", score = 10)))?.id,
        )

        val fast = Cand("fast", realPing = 40, testedUrl = "https://a", score = 100)
        val slow = Cand("slow", realPing = 400, testedUrl = "https://a", score = 900)
        T.eq("the only qualified server wins", "fast", choose(listOf(fast))?.id)
        T.eq("the lowest score wins", "fast", choose(listOf(slow, fast))?.id)
        T.eq("an unqualified server is skipped", "fast", choose(listOf(tcpOnly, fast))?.id)

        // The user's preferred server wins when it qualified, even if slower.
        T.eq("a qualified preferred server wins", "slow", choose(listOf(fast, slow), "slow")?.id)
        // ...but not when it did not.
        T.eq(
            "an unqualified preferred server is ignored",
            "fast",
            choose(listOf(tcpOnly.copy(id = "slow"), fast), "slow")?.id,
        )
        T.eq("an unknown preferred id is ignored", "fast", choose(listOf(fast, slow), "ghost")?.id)
        T.eq("no preferred id falls back to score", "fast", choose(listOf(slow, fast), null)?.id)
        T.eq("nothing qualified yields nothing", null, choose(listOf(tcpOnly, tcpOnly.copy(id = "b")))?.id)
    }
}
