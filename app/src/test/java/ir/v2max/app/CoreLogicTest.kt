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

    @Test
    fun `balancer config adds observatory and pool outbounds`() {
        val settings = AppSettings(balancer = true, balancerSize = 3)
        val primary = ServerConfig(address = "a.example.com", port = 443, name = "A")
        val pool = listOf(
            primary,
            ServerConfig(address = "b.example.com", port = 443, name = "B"),
            ServerConfig(address = "c.example.com", port = 443, name = "C")
        )
        val json = com.google.gson.JsonParser.parseString(
            ConfigBuilder.vpnConfig(primary, settings, pool)
        ).asJsonObject
        assertNotNull(json.get("observatory"))
        val routing = json.getAsJsonObject("routing")
        val balancers = routing.getAsJsonArray("balancers")
        assertNotNull(balancers)
        assertEquals(1, balancers.size())
        // سه سرور + مستقیم + مسدود + DNS
        assertEquals(6, json.getAsJsonArray("outbounds").size())
    }

    @Test
    fun `balancer disabled keeps single proxy outbound`() {
        val settings = AppSettings(balancer = false)
        val primary = ServerConfig(address = "a.example.com", port = 443)
        val pool = listOf(primary, ServerConfig(address = "b.example.com", port = 443))
        val json = com.google.gson.JsonParser.parseString(
            ConfigBuilder.vpnConfig(primary, settings, pool)
        ).asJsonObject
        assertEquals(null, json.get("observatory"))
        assertEquals(null, json.getAsJsonObject("routing").get("balancers"))
    }

    // ------------------------------------------------------------ diagnostics

    @Test
    fun `diagnostics suggests aggressive profile when traffic does not pass`() {
        val findings = ir.v2max.app.net.Diagnostics.advise(
            connected = true,
            connecting = false,
            killSwitchBlocked = false,
            exitOk = false,
            dnsDohOk = false,
            dnsConsistent = false,
            totalServers = 20,
            aliveServers = 12,
            balancerEnabled = true,
            geoReady = true,
            fragmentProfile = "BALANCED"
        )
        assertTrue(findings.any { it.actionId == ir.v2max.app.net.Diagnostics.Action.AGGRESSIVE_FRAGMENT })
    }

    @Test
    fun `diagnostics reports healthy when everything is fine`() {
        val findings = ir.v2max.app.net.Diagnostics.advise(
            connected = true,
            connecting = false,
            killSwitchBlocked = false,
            exitOk = true,
            dnsDohOk = true,
            dnsConsistent = true,
            totalServers = 30,
            aliveServers = 25,
            balancerEnabled = true,
            geoReady = true,
            fragmentProfile = "BALANCED"
        )
        assertEquals(1, findings.size)
        assertEquals(ir.v2max.app.net.Diagnostics.Severity.OK, findings.first().severity)
    }

    @Test
    fun `kill switch blocked is critical`() {
        val findings = ir.v2max.app.net.Diagnostics.advise(
            connected = false,
            connecting = false,
            killSwitchBlocked = true,
            exitOk = false,
            dnsDohOk = false,
            dnsConsistent = false,
            totalServers = 10,
            aliveServers = 10,
            balancerEnabled = false,
            geoReady = true,
            fragmentProfile = "BALANCED"
        )
        assertTrue(findings.any {
            it.severity == ir.v2max.app.net.Diagnostics.Severity.CRITICAL &&
                it.actionId == ir.v2max.app.net.Diagnostics.Action.UNBLOCK
        })
    }

    // -------------------------------------------------- sing-box core (2.0)

    @Test
    fun `parses hysteria2 link with insecure and sni`() {
        val cfg = LinkParser.parseLine(
            "hysteria2://secret@hy.example.com:443/?insecure=1&sni=cdn.example.com#HY2"
        )
        assertNotNull(cfg)
        cfg!!
        assertEquals(Proto.HYSTERIA2, cfg.proto)
        assertEquals("secret", cfg.password)
        assertEquals("cdn.example.com", cfg.sni)
        assertTrue(cfg.allowInsecure)
        assertTrue(cfg.needsSingBox())
    }

    @Test
    fun `parses tuic link with encoded password`() {
        val cfg = LinkParser.parseLine(
            "tuic://3796d9ef-02ac-447c-a8bf-30789f5c58b3:qnJtB11d%2F552vPJu4EU4R7u6elt08%3D@13.140.91.117:22912" +
                "?allow_insecure=0&alpn=h3%2Cspdy%2F3.1&congestion_control=bbr&udp_relay_mode=native#TUIC"
        )
        assertNotNull(cfg)
        cfg!!
        assertEquals(Proto.TUIC, cfg.proto)
        assertEquals("3796d9ef-02ac-447c-a8bf-30789f5c58b3", cfg.uuid)
        assertEquals("qnJtB11d/552vPJu4EU4R7u6elt08=", cfg.password)
        assertEquals("h3,spdy/3.1", cfg.alpn)
        assertTrue(!cfg.allowInsecure)
    }

    @Test
    fun `parses anytls link`() {
        val cfg = LinkParser.parseLine("anytls://dongtaiwang.com@45.221.98.14:59901/?insecure=1&fp=chrome#AT")
        assertNotNull(cfg)
        cfg!!
        assertEquals(Proto.ANYTLS, cfg.proto)
        assertEquals("dongtaiwang.com", cfg.password)
        assertEquals("chrome", cfg.fingerprint)
        assertEquals(59901, cfg.port)
        assertTrue(cfg.needsSingBox())
    }

    @Test
    fun `parses wireguard link with reserved bytes`() {
        val cfg = LinkParser.parseLine(
            "wireguard://4N3vWR92y5QFhJm6RV1wlLHkmpwT6gmU%2B96u7kqEj1c%3D@162.159.192.6:500" +
                "?address=172.16.0.2%2F32&reserved=84%2C160%2C213&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D#WG"
        )
        assertNotNull(cfg)
        cfg!!
        assertEquals(Proto.WIREGUARD, cfg.proto)
        assertEquals("162.159.192.6", cfg.address)
        assertEquals(500, cfg.port)
        assertEquals("84,160,213", cfg.wgReserved)
        assertTrue(cfg.wgPrivateKey.endsWith("="))
        // کلید عمومی هم باید درصدگشایی شده باشد
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", cfg.wgPublicKey)
    }

    @Test
    fun `only sing-box protocols use the sing-box core`() {
        fun cfg(proto: Proto) = ServerConfig(proto = proto, name = "n", address = "a", port = 1)
        assertTrue(cfg(Proto.HYSTERIA2).needsSingBox())
        assertTrue(cfg(Proto.TUIC).needsSingBox())
        assertTrue(cfg(Proto.ANYTLS).needsSingBox())
        assertTrue(cfg(Proto.WIREGUARD).needsSingBox())
        assertTrue(!cfg(Proto.VLESS).needsSingBox())
        assertTrue(!cfg(Proto.VMESS).needsSingBox())
        assertTrue(!cfg(Proto.TROJAN).needsSingBox())
    }

    @Test
    fun `sing-box config contains tls dns resolver and route`() {
        val cfg = ServerConfig(
            proto = Proto.TUIC,
            name = "t",
            address = "t.example.com",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            password = "pw",
            sni = "t.example.com"
        )
        val json = ir.v2max.app.core.SingBoxConfig.socksTestConfig(cfg, AppSettings(), 10889)
        assertTrue(json.contains("\"type\": \"tuic\"") || json.contains("\"type\":\"tuic\""))
        assertTrue(json.contains("default_domain_resolver"))
        assertTrue(json.contains("\"congestion_control\""))
        assertTrue(json.contains("h3"))
    }

    @Test
    fun `wireguard uses endpoint not outbound`() {
        val cfg = ServerConfig(
            proto = Proto.WIREGUARD,
            name = "w",
            address = "162.159.192.6",
            port = 500,
            wgPrivateKey = "priv",
            wgPublicKey = "pub",
            wgAddress = "172.16.0.2/32",
            wgReserved = "84,160,213"
        )
        val json = ir.v2max.app.core.SingBoxConfig.socksTestConfig(cfg, AppSettings(), 10889)
        assertTrue(json.contains("\"endpoints\""))
        assertTrue(json.contains("\"peers\""))
        assertTrue(json.contains("public_key"))
        // در sing-box 1.11+ نوع wireguard نباید در outbounds باشد
        assertTrue(!json.contains("\"outbounds\":[{\"tag\":\"proxy\",\"type\":\"wireguard\""))
    }

    @Test
    fun `public sources cover every sing-box protocol`() {
        listOf(Proto.HYSTERIA2, Proto.TUIC, Proto.ANYTLS, Proto.WIREGUARD).forEach { proto ->
            assertTrue(
                "منبع کانفیگ برای $proto ثبت نشده است",
                ir.v2max.app.data.PublicSources.forProto(proto).isNotEmpty()
            )
        }
    }

    // ----------------------------------------------------------------- format

    @Test
    fun `byte formatting is readable`() {
        assertTrue(ir.v2max.app.util.Format.bytes(0).isNotBlank())
        assertTrue(ir.v2max.app.util.Format.bytes(1024 * 1024).contains("MB"))
        assertTrue(ir.v2max.app.util.Format.speed(2_000_000).isNotBlank())
    }
}
