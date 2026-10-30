package ir.v2max.app

import com.google.gson.JsonParser
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.GeoDataTags
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.PingTargets
import ir.v2max.app.data.Proto
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.net.LinkParser
import ir.v2max.app.net.ProbePlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
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

    @Test fun `geo tags match protobuf entry names not text inside domains`() {
        fun varint(value: Int): ByteArray {
            var v = value
            val out = ByteArrayOutputStream()
            while (v >= 128) { out.write((v and 127) or 128); v = v ushr 7 }
            out.write(v)
            return out.toByteArray()
        }
        fun field(number: Int, bytes: ByteArray): ByteArray =
            byteArrayOf(((number shl 3) or 2).toByte()) + varint(bytes.size) + bytes
        fun entry(code: String, text: String): ByteArray = field(1, field(1, code.toByteArray()) + field(2, text.toByteArray()))
        val file = File.createTempFile("geodata", ".dat")
        try {
            file.writeBytes(entry("US", "persia.ir"))
            assertNull(GeoDataTags.find(file, listOf("ir")))
            file.writeBytes(entry("US", "x") + entry("IR", "domain"))
            assertEquals("ir", GeoDataTags.find(file, listOf("ir")))
            file.appendBytes(byteArrayOf(10, 100)) // truncated entry invalidates the whole file
            assertNull(GeoDataTags.find(file, listOf("ir")))
        } finally { file.delete() }
    }
}
