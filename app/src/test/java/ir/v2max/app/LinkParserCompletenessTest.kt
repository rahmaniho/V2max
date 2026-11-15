package ir.v2max.app

import ir.v2max.app.data.Proto
import ir.v2max.app.net.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserCompletenessTest {
    @Test fun `parser distinguishes exactly full from truncated and unsupported feeds`() {
        val one = "vless://u@one.example.org:443?security=tls"
        val two = "vless://u@two.example.org:443?security=tls"
        val exact = LinkParser.parseAllDetailed("# comment\n$one\n$two", maxConfigs = 2)
        assertTrue(exact.complete)
        assertEquals(2, exact.configs.size)
        val capped = LinkParser.parseAllDetailed("$one\n$two\n$one", maxConfigs = 2)
        assertFalse(capped.complete)
        assertEquals(2, capped.configs.size)
        assertTrue(LinkParser.parseAllDetailed("$one\n$two").complete)
        assertFalse(LinkParser.parseAllDetailed("$one\nfuture-protocol://host").complete)
        assertFalse(LinkParser.parseAllDetailed("$one\nopaque unsupported profile").complete)
        assertTrue(LinkParser.parseAllDetailed("$one\n# comment\n// comment\n\n").complete)
        assertFalse(LinkParser.parseAllDetailed("$one\nvless://u@127.0.0.1:443").complete)
        assertFalse(LinkParser.parseAllDetailed("$one\nvless://u@host.example.org:443?type=quic").complete)
    }

    @Test fun `mixed plaintext and base64 shadowsocks subscription shapes are supported`() {
        fun encode(value: String) = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        val credentials = encode("chacha20-ietf-poly1305:example-password")
        val ss1 = "ss://$credentials@one.example.org:443/?type=tcp#one"
        val ss2 = "ss://$credentials@two.example.org:8443/?type=tcp#two"
        val trojan = "trojan://example-password@three.example.org:443?security=tls"
        val vless = "vless://example-id@four.example.org:443?security=tls&type=ws"
        val vmess = "vmess://" + encode(
            """{"v":"2","ps":"five","add":"five.example.org","port":"443","id":"example-id","net":"ws","tls":"tls"}"""
        )
        val mixed = LinkParser.parseAllDetailed(listOf(ss1, trojan, vless, vmess).joinToString("\n"))
        assertTrue(mixed.complete)
        assertEquals(listOf(Proto.SS, Proto.TROJAN, Proto.VLESS, Proto.VMESS), mixed.configs.map { it.proto })
        val capped = LinkParser.parseAllDetailed(listOf(ss1, trojan, vless, vmess).joinToString("\n"), maxConfigs = 2)
        assertEquals(2, capped.configs.size)
        assertFalse(capped.complete)

        val encodedFeed = encode("$ss1\n$ss2")
        val decoded = LinkParser.parseAllDetailed(encodedFeed, maxConfigs = 150)
        assertTrue(decoded.complete)
        assertEquals(listOf(Proto.SS, Proto.SS), decoded.configs.map { it.proto })
        assertEquals(listOf("one.example.org", "two.example.org"), decoded.configs.map { it.address })
        assertEquals(listOf("chacha20-ietf-poly1305", "chacha20-ietf-poly1305"), decoded.configs.map { it.method })
    }
}
