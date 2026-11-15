package ir.v2max.app

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
}
