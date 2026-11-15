package ir.v2max.app

import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.Proto
import ir.v2max.app.data.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.lang.reflect.Modifier

class ServerFingerprintTest {
    @Test fun `a displayed real ping requires a recorded successful protocol test`() {
        val server = ServerConfig(realPing = 42, tcpPing = 1)
        assertEquals(-1, server.recordedRealPing())
        assertEquals(-1, server.copy(testedAt = 1).recordedRealPing())
        assertEquals(-1, server.copy(testedUrl = "https://example.org/probe").recordedRealPing())
        assertEquals(-1, server.copy(realPing = -1, testedAt = 1, testedUrl = "https://example.org/probe").recordedRealPing())
        assertEquals(42, server.copy(testedAt = 1, testedUrl = "https://example.org/probe").recordedRealPing())
    }

    @Test fun `every outbound input changes identity and metadata does not`() {
        // These are the ServerConfig properties consumed by ConfigBuilder's
        // outbound, including all TLS, transport, authentication and obfs inputs.
        // A newly added property must be classified here before CI can pass.
        val outbound = setOf(
            "proto", "address", "port", "uuid", "password", "method", "flow", "network",
            "headerType", "host", "path", "serviceName", "mode", "authority", "xhttpExtra",
            "finalMask", "kcpSeed", "kcpMtu", "kcpTti", "security", "sni", "alpn", "fingerprint",
            "publicKey", "shortId", "spiderX", "allowInsecure", "echConfigList", "verifyPeerCertByName",
            "pinnedPeerCertSha256", "mldsa65Verify", "obfsType", "obfsPassword", "obfsMinPacketSize",
            "obfsMaxPacketSize", "hoppingPorts"
        )
        val metadata = setOf(
            "id", "name", "subId", "favorite", "tcpPing", "realPing", "googlePing", "instagramPing",
            "youtubePing", "speedKbps", "countryCode", "countryName", "successCount", "failCount",
            "testedAt", "testedFragment", "testedFragmentProfile", "testedUrl", "addedAt"
        )
        val fields = ServerConfig::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        assertEquals("Classify new ServerConfig fields before treating two profiles as equivalent",
            outbound + metadata, fields.map { it.name }.toSet())
        val base = ServerConfig(id = "fixture", address = "server.example.org", uuid = "uuid")
        for (field in fields) {
            val changed = base.copy()
            field.isAccessible = true
            when (field.type) {
                String::class.java -> field.set(changed, "changed-${field.name}")
                Int::class.javaPrimitiveType -> field.setInt(changed, field.getInt(changed) + 1)
                Long::class.javaPrimitiveType -> field.setLong(changed, field.getLong(changed) + 1)
                Boolean::class.javaPrimitiveType -> field.setBoolean(changed, !field.getBoolean(changed))
                Proto::class.java -> field.set(changed, Proto.VMESS)
                FragmentProfile::class.java -> field.set(changed, FragmentProfile.AGGRESSIVE)
                else -> error("Unclassified field type: ${field.name}")
            }
            if (field.name in outbound) {
                assertNotEquals("Outbound field ${field.name} must be part of the fingerprint", base.fingerprint(), changed.fingerprint())
            } else {
                assertEquals("Metadata field ${field.name} must not change connection identity", base.fingerprint(), changed.fingerprint())
            }
        }
    }
}
