package ir.v2max.app

import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Proto
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.net.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون‌های واحد منطق خالص (بدون اندروید) که در CI اجرا می‌شوند.
 */
class CoreLogicTest {

    // ---------------------------------------------------------------- parser

    @Test
    fun `parses vless reality link`() {
        val link = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?type=ws&security=reality&sni=cdn.example.com&pbk=abc&sid=12&path=%2Fws#Test%20Node"
        val cfg = LinkParser.parseLine(link)
        assertNotNull(cfg)
        cfg!!
        assertEquals(Proto.VLESS, cfg.proto)
        assertEquals("example.com", cfg.address)
        assertEquals(443, cfg.port)
        assertEquals("ws", cfg.network)
        assertEquals("reality", cfg.security)
        assertEquals("cdn.example.com", cfg.sni)
        assertEquals("/ws", cfg.path)
        assertTrue(cfg.name.contains("Test"))
    }

    @Test
    fun `parses trojan link`() {
        val cfg = LinkParser.parseLine("trojan://secret@host.example.org:8443?sni=host.example.org#T")
        assertNotNull(cfg)
        assertEquals(Proto.TROJAN, cfg!!.proto)
        assertEquals("secret", cfg.password)
        assertEquals(8443, cfg.port)
    }

    @Test
    fun `rejects garbage`() {
        assertEquals(null, LinkParser.parseLine("hello world"))
        assertEquals(null, LinkParser.parseLine("vless://"))
    }

    @Test
    fun `parses multiple lines and ignores comments`() {
        val text = """
            #profile-title: base64:VGVzdA==
            vless://11111111-2222-3333-4444-555555555555@a.example.com:443?security=tls#A
            trojan://pw@b.example.com:443#B
        """.trimIndent()
        val list = LinkParser.parseAll(text)
        assertTrue("باید دو کانفیگ پارس شود، دریافتی: ${list.size}", list.size == 2)
    }

    // ---------------------------------------------------------------- scoring

    @Test
    fun `score prefers faster server`() {
        val slow = ServerConfig(googlePing = 100, speedKbps = 500)
        val fast = ServerConfig(googlePing = 120, speedKbps = 8000)
        assertTrue(fast.score() < slow.score())
    }

    @Test
    fun `stability is neutral without samples`() {
        assertEquals(70, ServerConfig().stability())
    }

    @Test
    fun `stability reflects outcomes`() {
        val s = ServerConfig(successCount = 9, failCount = 1)
        assertEquals(90, s.stability())
    }

    @Test
    fun `unusable endpoints are rejected`() {
        assertTrue(!ServerConfig(address = "127.0.0.1", port = 443).hasUsableEndpoint())
        assertTrue(!ServerConfig(address = "localhost", port = 443).hasUsableEndpoint())
        assertTrue(ServerConfig(address = "1.1.1.1", port = 443).hasUsableEndpoint())
    }

    // --------------------------------------------------------------- routing

    @Test
    fun `custom routing rules map to xray rules`() {
        val settings = AppSettings(
            customRoutingRules = listOf(
                "direct|domain|digikala.com,varzesh3.com",
                "proxy|ip|8.8.8.8/32",
                "block|domain|ads.example.org",
                "garbage line",
                "direct|unknown|foo"
            )
        )
        val rules = ConfigBuilder.buildCustomRules(settings)
        assertEquals(3, rules.size)
        assertEquals("direct", rules[0].get("outboundTag").asString)
        assertEquals(2, rules[0].getAsJsonArray("domain").size())
        assertEquals("proxy", rules[1].get("outboundTag").asString)
        assertEquals("block", rules[2].get("outboundTag").asString)
    }

    @Test
    fun `smart routing keeps domestic direct rule`() {
        val smart = AppSettings(routingMode = RoutingMode.SMART)
        val global = AppSettings(routingMode = RoutingMode.GLOBAL)
        val smartRules = ConfigBuilder.routingJson(smart).getAsJsonArray("rules").size()
        val globalRules = ConfigBuilder.routingJson(global).getAsJsonArray("rules").size()
        assertTrue(smartRules > globalRules)
    }

    // ----------------------------------------------------------------- format

    @Test
    fun `byte formatting is readable`() {
        assertTrue(ir.v2max.app.util.Format.bytes(0).isNotBlank())
        assertTrue(ir.v2max.app.util.Format.bytes(1024 * 1024).contains("MB"))
        assertTrue(ir.v2max.app.util.Format.speed(2_000_000).isNotBlank())
    }
}
