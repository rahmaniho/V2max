package ir.v2max.app

import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Proto
import ir.v2max.app.net.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** Uses the live app parser, not only the separate core parser. */
class LinkTransportCompatibilityTest {
    private fun vmess(network: String): String {
        val json = """{"add":"example.org","port":"443","id":"u","net":"$network","path":"/ws"}"""
        return "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
    }

    @Test fun removedTransportIsNotPresentedAsAWorkingProfile() {
        for (network in listOf("quic", "h2", "h3", "http", "QUIC")) {
            assertNull("VLESS $network", LinkParser.parseLine("vless://u@example.org:443?type=$network"))
            assertNull("VMess $network", LinkParser.parseLine(vmess(network)))
            assertNull("Shadowsocks $network", LinkParser.parseLine("ss://aes-256-gcm:pass@example.org:443?type=$network"))
        }
    }

    @Test fun supportedAliasesPreserveWebsocketSettings() {
        for (link in listOf(
            "vless://u@example.org:443?type=websocket&path=%2Fws",
            vmess("WEBSOCKET")
        )) {
            val server = requireNotNull(LinkParser.parseLine(link))
            assertEquals("ws", server.network)
            val stream = ConfigBuilder.outbound(server, AppSettings(fragment = false))
                .getAsJsonObject("streamSettings")
            assertEquals("ws", stream.get("network").asString)
            assertEquals("/ws", stream.getAsJsonObject("wsSettings").get("path").asString)
        }
        // HTTP proxy is a protocol; it must not be confused with removed HTTP transport.
        assertEquals(Proto.HTTP, LinkParser.parseLine("http://user:pass@example.org:8080")?.proto)
        assertNotNull(LinkParser.parseLine("vless://u@example.org:443?type=xhttp&path=%2Fapi"))
    }

    @Test fun hysteriaAndSimpleObfsKeepTheirActualTransport() {
        val hy = LinkParser.parseLine("hysteria2://pass@example.org:443?type=quic")
        assertEquals(Proto.HYSTERIA2, hy?.proto)
        assertEquals("hysteria", hy?.network)
        val ss = LinkParser.parseLine("ss://aes-256-gcm:pass@example.org:443?type=quic&plugin=" +
            "simple-obfs%3Bobfs%3Dhttp%3Bpath%3D%2Fok")
        assertEquals("tcp", ss?.network)
        assertEquals("http", ss?.headerType)
        assertEquals("/ok", ss?.path)
    }
}
