package ir.v2max.app

import com.google.gson.JsonParser
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.toCoreServer
import ir.v2max.app.data.toCoreSettings
import ir.v2max.app.net.LinkParser
import ir.v2max.core.config.XrayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Evidence, gathered *before* any call site is switched, that core's config
 * generator produces what the app's does.
 *
 * The app still generates configs with `ConfigBuilder`; core has a tested
 * `XrayConfig` written against its own `Server` type. Replacing one with the
 * other is the largest remaining step in the rewrite, and it changes what gets
 * handed to the VPN core — so it should happen on the strength of a comparison,
 * not on the assumption that two implementations written months apart agree.
 *
 * Both sides are compared as parsed JSON trees rather than strings, because key
 * order is not part of the contract and the two builders insert keys in
 * different orders.
 *
 * When a case here fails, the difference is the finding: either the mapping in
 * `ServerMapping.kt` is lossy, or one of the two generators is wrong. Both are
 * worth knowing before the switch, not after.
 */
class ConfigGeneratorTests {

    private val links = listOf(
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?type=ws&security=reality&sni=cdn.example.com&pbk=abc&sid=12&path=%2Fws#Test%20Node",
        "trojan://secret@host.example.org:8443?sni=host.example.org#T",
        "vless://u@tcp.example.com:443?type=tcp&headerType=http&host=h.example.com&path=%2F#Tcp",
    )

    @Test
    fun `test configs agree for parsed links`() {
        val settings = AppSettings()
        for (link in links) {
            val cfg = LinkParser.parseLine(link)
            assertNotNull("link did not parse: $link", cfg)
            cfg!!
            val app = ConfigBuilder.testConfig(cfg, settings)
            val core = XrayConfig.testConfig(cfg.toCoreServer(), settings.toCoreSettings())
            assertEquals(
                "test config differs for:\n$link\napp : $app\ncore: $core",
                JsonParser.parseString(app),
                JsonParser.parseString(core),
            )
        }
    }

    @Test
    fun `proxy outbounds agree for parsed links`() {
        val settings = AppSettings()
        for (link in links) {
            val cfg = LinkParser.parseLine(link)
            assertNotNull("link did not parse: $link", cfg)
            cfg!!
            val app = ConfigBuilder.outbound(cfg, settings).toString()
            val core = XrayConfig.outbound(cfg.toCoreServer(), settings.toCoreSettings()).toString()
            assertEquals(
                "outbound differs for:\n$link\napp : $app\ncore: $core",
                JsonParser.parseString(app),
                JsonParser.parseString(core),
            )
        }
    }
}
