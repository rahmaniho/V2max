package ir.v2max.app

import com.google.gson.Gson
import com.google.gson.JsonParser
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.BackupPayload
import ir.v2max.app.data.Migrations
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.PingTargets
import ir.v2max.app.data.Proto
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.net.LinkParser
import ir.v2max.app.net.ProbePlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CompatibilityTest {
    private val uuid = "11111111-2222-3333-4444-555555555555"

    @Test fun `standard vmess uri preserves grpc and tls settings`() {
        val cfg = LinkParser.parseLine(
            "vmess://$uuid@example.org:443?type=grpc&security=tls&serviceName=api%2Fv1" +
                "&authority=cdn.example.org&mode=multi&insecure=1#test"
        )!!
        assertEquals(Proto.VMESS, cfg.proto)
        assertEquals("api/v1", cfg.serviceName)
        assertEquals("cdn.example.org", cfg.authority)
        assertEquals("cdn.example.org", cfg.sni)
        assertTrue(cfg.allowInsecure)
        val stream = ConfigBuilder.outbound(cfg, AppSettings(fragment = false)).getAsJsonObject("streamSettings")
        assertTrue(stream.getAsJsonObject("grpcSettings").get("multiMode").asBoolean)
        assertEquals("api/v1", stream.getAsJsonObject("grpcSettings").get("serviceName").asString)
    }

    @Test fun `vmess qr grpc host and type mean authority and mode`() {
        val json = """{"v":"2","ps":"node","add":"example.org","port":"443","id":"$uuid","scy":"auto","net":"grpc","type":"multi","host":"cdn.example.org","path":"svc","tls":"tls","insecure":"1"}"""
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val cfg = LinkParser.parseLine(link)!!
        assertEquals("multi", cfg.mode)
        assertEquals("cdn.example.org", cfg.authority)
        assertEquals("svc", cfg.serviceName)
        assertTrue(cfg.allowInsecure)
    }

    @Test fun `old profiles and version two backups receive hysteria field defaults`() {
        val oldServer = """{"id":"old","address":"example.org","obfsPassword":"pw"}"""
        val healed = Migrations.healList("[$oldServer]", ServerConfig())!!.single()
        assertEquals("", healed.obfsMinPacketSize)
        assertEquals("", healed.obfsMaxPacketSize)
        assertEquals("", healed.hoppingPorts)
        val oldBackup = Gson().fromJson("""{"version":2,"servers":[$oldServer]}""", BackupPayload::class.java)
        val upgraded = Migrations.migrateBackup(oldBackup)
        assertEquals(3, upgraded.version)
        assertEquals("", upgraded.servers.single().hoppingPorts)
        assertEquals("pw", upgraded.servers.single().obfsPassword)
    }

    @Test fun `vmess qr preserves advanced transport fields through share round trip`() {
        val mask = "{\"tcp\":[]}"
        val extra = "{\"xPaddingBytes\":\"100-200\"}"
        val cfg = ServerConfig(
            proto = Proto.VMESS, address = "example.org", uuid = uuid, method = "auto",
            network = "xhttp", path = "/path", mode = "packet-up", security = "tls",
            finalMask = mask, xhttpExtra = extra, echConfigList = "ech-value"
        )
        val restored = LinkParser.parseLine(LinkParser.toLink(cfg))!!
        assertEquals(mask, restored.finalMask)
        assertEquals(extra, restored.xhttpExtra)
        assertEquals("packet-up", restored.mode)
        assertEquals("ech-value", restored.echConfigList)
        val stream = ConfigBuilder.outbound(restored, AppSettings(fragment = false)).getAsJsonObject("streamSettings")
        assertEquals(0, stream.getAsJsonObject("finalmask").getAsJsonArray("tcp").size())
        assertEquals("100-200", stream.getAsJsonObject("xhttpSettings").getAsJsonObject("extra").get("xPaddingBytes").asString)
    }

    @Test fun `hysteria gecko and port hopping survive import and sharing`() {
        val link = "hysteria2://secret@example.org:443?sni=example.org&obfs=gecko&obfs-password=pw" +
            "&minPacketSize=600&maxPacketSize=1300&mport=8443%2C9000-9002"
        val cfg = LinkParser.parseLine(link)!!
        val restored = LinkParser.parseLine(LinkParser.toLink(cfg))!!
        assertEquals("gecko", restored.obfsType)
        assertEquals("600", restored.obfsMinPacketSize)
        assertEquals("1300", restored.obfsMaxPacketSize)
        assertEquals("8443,9000-9002", restored.hoppingPorts)
        assertEquals(cfg.fingerprint(), restored.fingerprint())
        val masks = ConfigBuilder.outbound(restored, AppSettings(fragment = false))
            .getAsJsonObject("streamSettings").getAsJsonObject("finalmask").getAsJsonArray("udp")
        assertEquals("salamander", masks[0].asJsonObject.get("type").asString)
        assertEquals("600-1300", masks[0].asJsonObject.getAsJsonObject("settings").get("packetSize").asString)
        assertEquals("udphop", masks[1].asJsonObject.get("type").asString)
        assertEquals("8443,9000-9002", masks[1].asJsonObject.getAsJsonObject("settings").get("remotePorts").asString)
    }

    @Test fun `unknown hysteria obfuscation and invalid hopping fail explicitly`() {
        val unknown = LinkParser.parseLine("hysteria2://secret@example.org:443?obfs=other&obfs-password=pw")!!
        assertThrows(IllegalArgumentException::class.java) { ConfigBuilder.testConfig(unknown, AppSettings()) }
        val invalid = LinkParser.parseLine("hysteria2://secret@example.org:443?mport=9000-7000")!!
        assertThrows(IllegalArgumentException::class.java) { ConfigBuilder.testConfig(invalid, AppSettings()) }
        val invalidGecko = LinkParser.parseLine(
            "hysteria2://secret@example.org:443?obfs=gecko&obfs-password=pw&minPacketSize=1300&maxPacketSize=600"
        )!!
        assertThrows(IllegalArgumentException::class.java) { ConfigBuilder.testConfig(invalidGecko, AppSettings()) }
        val withoutPassword = LinkParser.parseLine("hysteria2://secret@example.org:443?obfs=gecko")!!
        assertThrows(IllegalArgumentException::class.java) { ConfigBuilder.testConfig(withoutPassword, AppSettings()) }
    }

    @Test fun `hysteria gecko defaults and hopping without obfuscation are encoded`() {
        val gecko = LinkParser.parseLine("hy2://secret@example.org:443?obfs=gecko&obfs-password=pw")!!
        val masks = ConfigBuilder.outbound(gecko, AppSettings()).getAsJsonObject("streamSettings")
            .getAsJsonObject("finalmask").getAsJsonArray("udp")
        assertEquals("512-1200", masks[0].asJsonObject.getAsJsonObject("settings").get("packetSize").asString)
        val hop = LinkParser.parseLine("hy2://secret@example.org:443?mport=1000-1003")!!
        val hops = ConfigBuilder.outbound(hop, AppSettings()).getAsJsonObject("streamSettings")
            .getAsJsonObject("finalmask").getAsJsonArray("udp")
        assertEquals(1, hops.size())
        assertEquals("udphop", hops[0].asJsonObject.get("type").asString)
    }

    @Test fun `hysteria explicit mask is retained when sharing`() {
        val cfg = ServerConfig(proto = Proto.HYSTERIA2, address = "example.org", password = "secret",
            finalMask = "{\"udp\":[]}")
        val restored = LinkParser.parseLine(LinkParser.toLink(cfg))!!
        assertEquals(cfg.finalMask, restored.finalMask)
        assertEquals(0, ConfigBuilder.outbound(restored, AppSettings()).getAsJsonObject("streamSettings")
            .getAsJsonObject("finalmask").getAsJsonArray("udp").size())
    }

    @Test fun `xhttp extra reality parameters and encoded path survive sharing`() {
        val extra = "{\"xPaddingBytes\":\"100-200\",\"xmux\":{\"maxConcurrency\":8}}"
        val cfg = ServerConfig(
            proto = Proto.VLESS, address = "example.org", uuid = uuid, method = "none",
            network = "xhttp", path = "/abc?ed=42&x=hello world", mode = "packet-up",
            host = "cdn.example.org", security = "reality", sni = "site.example.org",
            fingerprint = "chrome", publicKey = "key", shortId = "abcd", spiderX = "/hi?x=1",
            xhttpExtra = extra, finalMask = "{\"tcp\":[]}", mldsa65Verify = "pq"
        )
        val restored = LinkParser.parseLine(LinkParser.toLink(cfg))!!
        assertEquals(cfg.path, restored.path)
        assertEquals(cfg.xhttpExtra, restored.xhttpExtra)
        assertEquals(cfg.finalMask, restored.finalMask)
        assertEquals(cfg.spiderX, restored.spiderX)
        assertEquals(cfg.mldsa65Verify, restored.mldsa65Verify)
        val stream = ConfigBuilder.outbound(restored, AppSettings(fragment = true)).getAsJsonObject("streamSettings")
        assertEquals("100-200", stream.getAsJsonObject("xhttpSettings").getAsJsonObject("extra").get("xPaddingBytes").asString)
        assertEquals(0, stream.getAsJsonObject("finalmask").getAsJsonArray("tcp").size())
        assertEquals("pq", stream.getAsJsonObject("realitySettings").get("mldsa65Verify").asString)
    }

    @Test fun `kcp seed and header use modern xray legacy masks`() {
        val cfg = LinkParser.parseLine(
            "vless://$uuid@example.org:443?type=kcp&seed=secret&headerType=wechat-video&mtu=1300&tti=40"
        )!!
        val stream = ConfigBuilder.outbound(cfg, AppSettings()).getAsJsonObject("streamSettings")
        assertEquals(1300, stream.getAsJsonObject("kcpSettings").get("mtu").asInt)
        val masks = stream.getAsJsonObject("finalmask").getAsJsonArray("udp")
        assertEquals("secret", masks[0].asJsonObject.getAsJsonObject("settings").get("value").asString)
        assertEquals("wechat", masks[1].asJsonObject.getAsJsonObject("settings").get("header").asString)
    }

    @Test fun `shadowsocks ipv6 and http obfuscation are not discarded`() {
        val cfg = LinkParser.parseLine("ss://YWVzLTEyOC1nY206cGFzcw@[2001:db8::1]:443/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dcdn.example.org%3Bpath%3D%2Ftest")!!
        assertEquals("2001:db8::1", cfg.address)
        assertEquals("http", cfg.headerType)
        assertEquals("cdn.example.org", cfg.host)
        val roundTrip = LinkParser.parseLine(LinkParser.toLink(cfg))!!
        assertEquals(cfg.address, roundTrip.address)
        assertEquals(cfg.host, roundTrip.host)
        assertEquals(cfg.path, roundTrip.path)
        assertNull(LinkParser.parseLine("ss://YWVzLTEyOC1nY206cGFzcw@example.org:443/?plugin=unsupported%3Bobfs%3Dtls"))
    }

    @Test fun `explicit trojan no tls and encoded password survive sharing`() {
        val cfg = LinkParser.parseLine("trojan://a%3Ab%40c%2Bd@example.org:443?security=none#hello%20world")!!
        assertEquals("none", cfg.security)
        assertEquals("a:b@c+d", cfg.password)
        val restored = LinkParser.parseLine(LinkParser.toLink(cfg))!!
        assertEquals(cfg.security, restored.security)
        assertEquals(cfg.password, restored.password)
    }

    @Test fun `tls certificate pin takes precedence over deprecated insecure flag`() {
        val cfg = ServerConfig(address = "example.org", uuid = uuid, security = "tls", allowInsecure = true,
            pinnedPeerCertSha256 = "a".repeat(64), verifyPeerCertByName = "site.example.org", echConfigList = "ech")
        val tls = ConfigBuilder.outbound(cfg, AppSettings(fragment = false))
            .getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")
        assertFalse(tls.get("allowInsecure").asBoolean)
        assertEquals(cfg.pinnedPeerCertSha256, tls.get("pinnedPeerCertSha256").asString)
        assertEquals(cfg.verifyPeerCertByName, tls.get("verifyPeerCertByName").asString)
        assertEquals("ech", tls.get("echConfigList").asString)
    }

    @Test fun `dns hijack applies only to tun and pool has a catch all`() {
        val routing = ConfigBuilder.routingJson(AppSettings(geoAssets = false), useBalancer = true)
        val rules = routing.getAsJsonArray("rules")
        val dns = rules.first { it.asJsonObject.get("outboundTag")?.asString == "dns-out" }.asJsonObject
        assertEquals("tun", dns.getAsJsonArray("inboundTag")[0].asString)
        assertEquals("auto", rules.last().asJsonObject.get("balancerTag").asString)
    }

    @Test fun `udp transports never depend on a tcp handshake`() {
        assertTrue(ProbePlanner.usesUdp(ServerConfig(proto = Proto.HYSTERIA2)))
        assertTrue(ProbePlanner.usesUdp(ServerConfig(network = "kcp")))
        assertFalse(ProbePlanner.usesUdp(ServerConfig(network = "ws")))
    }

    @Test fun `probe tries plain and alternate destination before fragmentation`() = runBlocking {
        val server = ServerConfig(address = "example.org", uuid = uuid, security = "tls")
        val calls = mutableListOf<Pair<Boolean, String>>()
        val settings = AppSettings(fragment = true, fragmentProfile = FragmentProfile.MAHSA)
        val result = ProbePlanner.verify(server, settings) { _, variant, url ->
            calls.add(variant.fragment to url)
            if (variant.fragment && url == settings.delayTestUrl) 91 else -1
        }!!
        assertEquals(listOf(false to settings.delayTestUrl, false to ProbePlanner.FALLBACK_URL, true to settings.delayTestUrl), calls)
        assertTrue(result.fragment)
        assertEquals(FragmentProfile.MAHSA, result.profile)
        assertEquals(91, result.delayMs)
    }

    @Test fun `probe accepts fallback destination without changing fragmentation`() = runBlocking {
        val server = ServerConfig(address = "example.org", uuid = uuid, security = "tls")
        val calls = mutableListOf<String>()
        val result = ProbePlanner.verify(server, AppSettings(fragment = true)) { _, variant, url ->
            calls.add(url)
            if (url == ProbePlanner.FALLBACK_URL && !variant.fragment) 55 else -1
        }!!
        assertEquals(listOf(PingTargets.GOOGLE.url, ProbePlanner.FALLBACK_URL), calls)
        assertFalse(result.fragment)
        assertEquals(ProbePlanner.FALLBACK_URL, result.url)
    }

    @Test fun `tcp only and stale results are not chosen and manual choice is honored`() {
        val tcpOnly = ServerConfig(id = "tcp", tcpPing = 1, realPing = -1)
        val fast = ServerConfig(id = "fast", tcpPing = 200, realPing = 30, testedUrl = "https://example.org")
        val selected = ServerConfig(id = "selected", realPing = 80, testedUrl = "https://example.org")
        assertNull(ProbePlanner.choose(listOf(tcpOnly)))
        assertEquals("fast", ProbePlanner.choose(listOf(tcpOnly, fast, selected))!!.id)
        assertEquals("selected", ProbePlanner.choose(listOf(fast, selected), "selected")!!.id)
    }

}
