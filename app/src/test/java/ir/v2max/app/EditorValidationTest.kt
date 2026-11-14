package ir.v2max.app

import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Proto
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.ui.EditorValidation
import org.junit.Assert.*
import org.junit.Test

class EditorValidationTest {
    private fun validate(address: String = "example.org", port: String = "443", network: String = "tcp", security: String = "tls", proto: Proto = Proto.VLESS) =
        EditorValidation.validate(address, port, network, security, proto)

    @Test fun `invalid addresses and ports are not silently replaced or clamped`() {
        assertTrue(validate(address = "  ") is EditorValidation.Result.Invalid)
        for (port in listOf("", "0", "65536", "-1", "1.5", "9999999999999999999")) {
            assertTrue(port, validate(port = port) is EditorValidation.Result.Invalid)
        }
        assertEquals(1, (validate(port = " 1 ") as EditorValidation.Result.Valid).fields.port)
        assertEquals(65535, (validate(port = "65535") as EditorValidation.Result.Valid).fields.port)
    }

    @Test fun `removed and unsupported transports and unknown security are not saved`() {
        for (network in listOf("quic", "h2", "h3", "http", "QUIC", "masque", "unknown")) {
            assertTrue(network, validate(network = network) is EditorValidation.Result.Invalid)
        }
        assertTrue(validate(security = "wrong") is EditorValidation.Result.Invalid)
        // HTTP proxy is a protocol, not the removed HTTP transport.
        assertTrue(validate(proto = Proto.HTTP, network = "tcp") is EditorValidation.Result.Valid)
    }

    @Test fun `supported aliases are normalized without touching ALPN`() {
        assertEquals("ws", (validate(network = " WebSocket ") as EditorValidation.Result.Valid).fields.network)
        for (network in listOf("tcp", "raw", "grpc", "httpupgrade", "xhttp", "splithttp", "kcp", "mkcp", "hysteria")) {
            assertEquals(network, (validate(network = network) as EditorValidation.Result.Valid).fields.network)
        }
        assertEquals("tls", (validate(security = " TLS ") as EditorValidation.Result.Valid).fields.security)
    }

    @Test fun `websocket alias reaches the actual builder and ALPN h3 is preserved`() {
        val fields = (validate(network = " WebSocket ") as EditorValidation.Result.Valid).fields
        val server = ServerConfig(address = "example.org", uuid = "id", network = fields.network,
            security = fields.security, path = "/ws", alpn = "h3")
        val stream = ConfigBuilder.outbound(server, AppSettings(fragment = false)).getAsJsonObject("streamSettings")
        assertEquals("ws", stream.get("network").asString)
        assertEquals("/ws", stream.getAsJsonObject("wsSettings").get("path").asString)
        assertEquals("h3", stream.getAsJsonObject("tlsSettings").getAsJsonArray("alpn")[0].asString)
    }

    @Test fun `hysteria2 uses its actual transport and TLS`() {
        val fields = (validate(network = "quic", security = "none", proto = Proto.HYSTERIA2) as EditorValidation.Result.Valid).fields
        assertEquals("hysteria", fields.network)
        assertEquals("tls", fields.security)
    }
}
