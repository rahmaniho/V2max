package ir.v2max.core.test

import ir.v2max.core.model.Credential
import ir.v2max.core.model.Endpoint
import ir.v2max.core.model.Measurement
import ir.v2max.core.model.Server
import ir.v2max.core.model.Transport

/** Endpoint parsing and the `usable` filter. */
object EndpointTests {
    fun run() {
        T.group("Endpoint")

        T.eq("host:port", Endpoint("example.com", 443), Endpoint.parse("example.com:443", 443))
        T.eq("bare host uses fallback", Endpoint("example.com", 1080), Endpoint.parse("example.com", 1080))
        T.eq("ipv6 literal", Endpoint("2001:db8::1", 8443), Endpoint.parse("[2001:db8::1]:8443", 443))
        T.eq("ipv6 without port", Endpoint("2001:db8::1", 443), Endpoint.parse("[2001:db8::1]", 443))
        T.eq("trailing slash path is stripped", Endpoint("h.com", 443), Endpoint.parse("h.com:443/", 443))
        T.eq("bad port falls back", Endpoint("h.com", 443), Endpoint.parse("h.com:notaport", 443))
        T.eq("unclosed bracket is rejected", null, Endpoint.parse("[2001:db8::1", 443))
        T.eq("empty is rejected", null, Endpoint.parse("", 443))
        T.eq("missing host before colon", null, Endpoint.parse(":443", 443))

        // The old hasUsableEndpoint() semantics, preserved exactly.
        T.ok("public host usable", Endpoint("1.2.3.4", 443).usable)
        T.ok("localhost rejected", !Endpoint("localhost", 443).usable)
        T.ok("0.0.0.0 rejected", !Endpoint("0.0.0.0", 443).usable)
        T.ok(":: rejected", !Endpoint("::", 443).usable)
        T.ok("::1 rejected", !Endpoint("::1", 443).usable)
        T.ok("[::1] rejected", !Endpoint("[::1]", 443).usable)
        T.ok("127.x rejected", !Endpoint("127.0.0.1", 443).usable)
        T.ok("127.5.x rejected", !Endpoint("127.5.5.5", 443).usable)
        T.ok("port 0 rejected", !Endpoint("1.2.3.4", 0).usable)
        T.ok("port 65536 rejected", !Endpoint("1.2.3.4", 65536).usable)
        T.ok("port 65535 accepted", Endpoint("1.2.3.4", 65535).usable)
        T.ok("blank address rejected", !Endpoint("", 443).usable)

        T.eq("display brackets ipv6", "[2001:db8::1]:443", Endpoint("2001:db8::1", 443).display)
        T.eq("display plain for ipv4", "1.2.3.4:443", Endpoint("1.2.3.4", 443).display)
    }
}

/** Scoring, stability and reach counting — must match the previous arithmetic. */
object ScoringTests {
    private fun server(m: Measurement) =
        Server(endpoint = Endpoint("1.2.3.4", 443), measurement = m)

    fun run() {
        T.group("Scoring")

        // stability(): no data -> 70
        T.eq("unmeasured stability is 70", 70, Measurement().stability())
        T.eq("all success is 100", 100, Measurement(successCount = 5, failCount = 0).stability())
        T.eq("all fail is 0", 0, Measurement(successCount = 0, failCount = 5).stability())
        T.eq("3 of 4 success is 75", 75, Measurement(successCount = 3, failCount = 1).stability())

        // bestPing(): min of positive real pings, else TCP, else -1
        T.eq("no measurement is -1", -1, Measurement().bestPing())
        T.eq("tcp only", 120, Measurement(tcpPing = 120).bestPing())
        T.eq(
            "min of positive reals wins over tcp",
            80,
            Measurement(tcpPing = 120, realPing = 90, googlePing = 80, youtubePing = 200).bestPing(),
        )
        T.eq("zero/negative pings ignored", 50, Measurement(realPing = 0, googlePing = -1, youtubePing = 50).bestPing())

        // reachCount()
        T.eq("reach 0", 0, Measurement().reachCount())
        T.eq("reach 3", 3, Measurement(googlePing = 1, instagramPing = 2, youtubePing = 3).reachCount())
        T.eq("reach ignores non-positive", 1, Measurement(googlePing = 5, instagramPing = 0, youtubePing = -1).reachCount())

        // score(): base 3500 when unmeasured, +150 no speed, +(100-70)*2
        T.eq("unmeasured score", 3500 + 150 + 60, server(Measurement()).score())

        // ping 100, no speed, unmeasured stability -> 100 + 150 + 60
        T.eq("measured ping, no speed", 310, server(Measurement(realPing = 100)).score())

        // speed bonus: 1000 kbps -> (1000/500)=2 steps -> -20.
        // Note the +150 no-speed penalty does NOT apply here: score() treats the
        // penalty and the bonus as mutually exclusive branches.
        T.eq("speed bonus applied", 100 - 20 + 60, server(Measurement(realPing = 100, speedKbps = 1000)).score())

        // speed bonus caps at 14 steps (140 points)
        T.eq("speed bonus caps at 140", 100 - 140 + 60, server(Measurement(realPing = 100, speedKbps = 999_999)).score())

        // stability penalty: 1 success 3 fails -> 25 -> (100-25)*2 = 150
        T.eq(
            "stability penalty applied",
            100 + 150 + 150,
            server(Measurement(realPing = 100, successCount = 1, failCount = 3)).score(),
        )

        // never below 1
        T.eq(
            "score floors at 1",
            1,
            server(Measurement(realPing = 1, speedKbps = 999_999, successCount = 100, failCount = 0)).score(),
        )
    }
}

/** The hand-written JSON reader, since VMess links embed JSON. */
object JsonTests {
    fun run() {
        T.group("Json")
        val o = ir.v2max.core.protocol.Json.parse("""{"add":"h.com","port":"443","id":"u","v":"2"}""")
        val obj = T.notNull("object parses", o?.asObject())
        T.eq("string member", "h.com", obj?.get("add")?.asString())
        T.eq("numeric string member", "443", obj?.get("port")?.asString())
        T.eq("missing member is null", null, obj?.get("nope"))

        T.eq("number coerces to string", "443", ir.v2max.core.protocol.Json.parse("443")?.asString())
        T.eq("bool coerces", "true", ir.v2max.core.protocol.Json.parse("true")?.asString())
        T.eq("array parses", 3, ir.v2max.core.protocol.Json.parse("[1,2,3]")?.asArray()?.items?.size)
        T.eq("nested object", "x", ir.v2max.core.protocol.Json.parse("""{"a":{"b":"x"}}""")
            ?.asObject()?.get("a")?.asObject()?.get("b")?.asString())
        T.eq("escaped quote", """a"b""", ir.v2max.core.protocol.Json.parse(""""a\"b"""")?.asString())
        // Built as a plain string so the backslash is unambiguous: the JSON text
        // is "\u0041", which must decode to the single character 'A'.
        T.eq("unicode escape", "A", ir.v2max.core.protocol.Json.parse("\"\\u0041\"")?.asString())

        // Strictness: malformed input must not half-parse.
        T.eq("trailing garbage rejected", null, ir.v2max.core.protocol.Json.parse("""{"a":1} junk"""))
        T.eq("unterminated object rejected", null, ir.v2max.core.protocol.Json.parse("""{"a":1"""))
        T.eq("bare word rejected", null, ir.v2max.core.protocol.Json.parse("nope"))
        T.eq("empty string rejected", null, ir.v2max.core.protocol.Json.parse(""))

        // quoting round-trips through the parser
        val tricky = """he said "hi"\newline\ttab"""
        val quoted = ir.v2max.core.protocol.Json.quote(tricky)
        T.eq("quote round-trips", tricky, ir.v2max.core.protocol.Json.parse(quoted)?.asString())
    }
}

/** Link parsing across every supported protocol. */
object LinkParserTests {
    private val P = ir.v2max.core.protocol.LinkParser

    fun run() {
        T.group("LinkParser")

        // ---- comments and junk
        T.ok(
            "comment skipped",
            P.parseLine("# a comment") is ir.v2max.core.protocol.ParseResult.Skipped,
        )
        T.ok(
            "unknown scheme skipped",
            P.parseLine("foo://bar") is ir.v2max.core.protocol.ParseResult.Skipped,
        )
        T.ok("no scheme skipped", P.parseLine("just text") is ir.v2max.core.protocol.ParseResult.Skipped)

        // ---- VLESS + REALITY + ws
        val vless = P.parseLine(
            "vless://uuid-123@example.com:443?encryption=none&security=reality&type=ws" +
                "&host=cdn.example.com&path=%2Fws&sni=cdn.example.com&fp=chrome&pbk=PUBKEY&sid=SHORT&spx=%2F" +
                "#My%20Server",
        )
        val v = T.notNull("vless parsed", (vless as? ir.v2max.core.protocol.ParseResult.Ok)?.server)
        T.eq("vless name decoded", "My Server", v?.displayName)
        T.eq("vless proto", ir.v2max.core.model.Proto.VLESS, v?.proto)
        T.eq("vless address", "example.com", v?.endpoint?.address)
        T.eq("vless port", 443, v?.endpoint?.port)
        T.eq("vless uuid", "uuid-123", (v?.credential as? Credential.Uuid)?.uuid)
        T.eq("vless transport is ws", "ws", v?.transport?.wire)
        T.eq("vless ws path", "/ws", (v?.transport as? Transport.WebSocket)?.path)
        T.ok("vless security is reality", v?.security is ir.v2max.core.model.Security.Reality)
        T.eq("reality pbk", "PUBKEY", (v?.security as? ir.v2max.core.model.Security.Reality)?.publicKey)
        T.eq("reality sid", "SHORT", (v?.security as? ir.v2max.core.model.Security.Reality)?.shortId)
        T.eq("reality fp", "chrome", (v?.security as? ir.v2max.core.model.Security.Reality)?.fingerprint)

        // ---- Trojan defaults to TLS and inherits SNI
        val trojan = (P.parseLine("trojan://pass@t.example.com:443?type=tcp")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.ok("trojan defaults to tls", trojan?.security is ir.v2max.core.model.Security.Tls)
        T.eq("trojan password", "pass", (trojan?.credential as? Credential.Password)?.password)
        T.eq("trojan sni inherited from address", "t.example.com", trojan?.effectiveSni)

        // ---- VMess base64(JSON)
        // Kept on one line on purpose: an earlier version stripped whitespace to
        // allow pretty-printing, which also removed the space inside "VM Test".
        val vmessJson = """{"v":"2","ps":"VM Test","add":"vm.example.com","port":"8443","id":"vm-uuid","scy":"aes-128-gcm","net":"grpc","type":"multi","host":"","path":"svcname","tls":"tls","sni":"vm.example.com","fp":"safari"}"""
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(vmessJson.toByteArray())
        val vmess = (P.parseLine("vmess://$encoded")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("vmess name", "VM Test", vmess?.displayName)
        T.eq("vmess address", "vm.example.com", vmess?.endpoint?.address)
        T.eq("vmess port", 8443, vmess?.endpoint?.port)
        T.eq("vmess cipher", "aes-128-gcm", (vmess?.credential as? Credential.Uuid)?.method)
        T.eq("vmess transport grpc", "grpc", vmess?.transport?.wire)
        T.eq("vmess grpc serviceName from path", "svcname", (vmess?.transport as? Transport.Grpc)?.serviceName)
        T.eq("vmess grpc mode from type", "multi", (vmess?.transport as? Transport.Grpc)?.mode)

        // ---- VMess in standard user@host form
        val vmessStd = (P.parseLine("vmess://vm-id@vm2.example.com:443?type=ws&path=%2Fv&scy=auto#VM2")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("vmess standard uuid", "vm-id", (vmessStd?.credential as? Credential.Uuid)?.uuid)
        T.eq("vmess standard name", "VM2", vmessStd?.displayName)

        // ---- Shadowsocks, base64 credential form
        val ssCred = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:sspass".toByteArray())
        val ss = (P.parseLine("ss://$ssCred@ss.example.com:8388#SS%20Node")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("ss method lowercased", "aes-256-gcm", (ss?.credential as? Credential.Cipher)?.method)
        T.eq("ss password", "sspass", (ss?.credential as? Credential.Cipher)?.password)
        T.eq("ss port", 8388, ss?.endpoint?.port)

        // ---- Shadowsocks fully-base64 form
        val ssFull = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("chacha20-ietf-poly1305:pw@ss2.example.com:1234".toByteArray())
        val ss2 = (P.parseLine("ss://$ssFull") as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("ss full form host", "ss2.example.com", ss2?.endpoint?.address)
        T.eq("ss full form port", 1234, ss2?.endpoint?.port)
        T.eq("ss full form method", "chacha20-ietf-poly1305", (ss2?.credential as? Credential.Cipher)?.method)

        // ---- Shadowsocks simple-obfs HTTP maps to the TCP http header
        val ssObfs = (P.parseLine(
            "ss://$ssCred@ss3.example.com:8388?plugin=" +
                P.encodeComponent("obfs-local;obfs=http;obfs-host=h.example.com;path=/p"),
        ) as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("ss obfs -> tcp", "tcp", ssObfs?.transport?.wire)
        T.eq("ss obfs headerType", "http", (ssObfs?.transport as? Transport.Tcp)?.headerType)
        T.eq("ss obfs host", "h.example.com", (ssObfs?.transport as? Transport.Tcp)?.host)

        // ---- An unsupported plugin must be reported, never silently dropped
        val ssBad = P.parseLine(
            "ss://$ssCred@ss4.example.com:8388?plugin=" +
                P.encodeComponent("v2ray-plugin;path=/x"),
        )
        T.ok("unsupported ss plugin rejected", ssBad is ir.v2max.core.protocol.ParseResult.Skipped)
        T.eq(
            "unsupported ss plugin reason",
            ir.v2max.core.protocol.ParseResult.Reason.UNSUPPORTED,
            (ssBad as? ir.v2max.core.protocol.ParseResult.Skipped)?.reason,
        )

        // ---- SOCKS user:pass with a colon inside the password
        val socks = (P.parseLine("socks://user:p%3Ass%3Aword@s.example.com:1080#Socks")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("socks user", "user", (socks?.credential as? Credential.UserPass)?.user)
        T.eq("socks password keeps colons", "p:ss:word", (socks?.credential as? Credential.UserPass)?.password)

        // ---- default ports per protocol
        T.eq("socks default port 1080", 1080, socks?.endpoint?.port)
        T.eq(
            "http default port 80",
            80,
            (P.parseLine("http://u:p@h.example.com") as? ir.v2max.core.protocol.ParseResult.Ok)
                ?.server?.endpoint?.port,
        )

        // ---- Hysteria2 forces TLS over its own transport
        val hy2 = (P.parseLine("hysteria2://hypass@hy.example.com:443?obfs=salamander&obfs-password=obfspw#HY")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("hy2 transport", "hysteria", hy2?.transport?.wire)
        T.ok("hy2 forced tls", hy2?.security is ir.v2max.core.model.Security.Tls)
        T.eq("hy2 obfs type", "salamander", hy2?.obfuscation?.type)
        T.ok("hy2 obfs enabled", hy2?.obfuscation?.enabled == true)

        // ---- KCP parameters survive
        val kcp = (P.parseLine("vless://u@k.example.com:443?type=kcp&seed=SEED&mtu=1400&tti=40&headerType=srtp")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("kcp wire", "kcp", kcp?.transport?.wire)
        T.eq("kcp seed", "SEED", (kcp?.transport as? Transport.Kcp)?.seed)
        T.eq("kcp mtu", 1400, (kcp?.transport as? Transport.Kcp)?.mtu)
        T.eq("kcp tti", 40, (kcp?.transport as? Transport.Kcp)?.tti)

        // ---- XHTTP extra preserved; finalmask is stream-level, not XHTTP-only
        val xhttp = (P.parseLine("vless://u@x.example.com:443?type=xhttp&extra=EX&fm=FM&mode=auto")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("xhttp wire", "xhttp", xhttp?.transport?.wire)
        T.eq("xhttp extra", "EX", (xhttp?.transport as? Transport.XHttp)?.extra)
        T.eq("xhttp finalMask", "FM", xhttp?.finalMask)

        // The reason finalmask lives on Server rather than on XHttp: a share link
        // may carry `fm` alongside any transport, and it must survive.
        val fmOnWs = (P.parseLine("vless://u@f.example.com:443?type=ws&path=%2Ff&fm=WFM")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("finalmask on ws transport", "WFM", fmOnWs?.finalMask)
        T.eq("finalmask on ws transport wire", "ws", fmOnWs?.transport?.wire)

        // ---- IPv6 endpoint
        val v6 = (P.parseLine("vless://u@[2001:db8::9]:443?security=tls&sni=x.example.com")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("ipv6 address", "2001:db8::9", v6?.endpoint?.address)
        T.eq("ipv6 port", 443, v6?.endpoint?.port)

        // ---- multi-line subscription with base64 body
        val body = listOf(
            "vless://u1@a.example.com:443#One",
            "# comment line",
            "",
            "vless://u2@b.example.com:443#Two",
            "garbage without scheme",
        ).joinToString("\n")
        val b64 = java.util.Base64.getEncoder().encodeToString(body.toByteArray())
        T.eq("plain body yields 4 results", 4, P.parseAll(body).size)
        T.eq("plain body yields 2 servers", 2, P.parseServers(body).size)

        // The same body, base64-wrapped the way a subscription URL returns it.
        val fromB64 = P.parseServers(b64)
        T.eq("base64 subscription decoded", 2, fromB64.size)
        T.eq("first server name", "One", fromB64.firstOrNull()?.displayName)
    }
}

/** toLink() -> parseLine() must round-trip without losing connectivity fields. */
object RoundTripTests {
    private val P = ir.v2max.core.protocol.LinkParser

    fun run() {
        T.group("Round-trip")

        val samples = listOf(
            "vless://uuid-1@r.example.com:443?encryption=none&security=reality&type=tcp&sni=r.example.com&fp=chrome&pbk=PK&sid=SI&spx=%2Fspider#RT1",
            "vless://uuid-2@w.example.com:8443?encryption=none&security=tls&type=ws&host=cdn.example.com&path=%2Fwebsocket&alpn=h2%2Ch3#RT2",
            "trojan://tr%3Aojan@t.example.com:443?type=tcp&security=tls&sni=t.example.com#RT3",
            "vless://u@k.example.com:443?type=kcp&headerType=wechat&seed=KCPSEED&mtu=1400&tti=40#RT4",
            "vless://u@x.example.com:443?type=xhttp&mode=auto&extra=EXTRA&fm=MASK#RT5",
            "socks://user:pass@s.example.com:1080#RT6",
            "hysteria2://hp@h.example.com:443?obfs=salamander&obfs-password=opw#RT7",
        )

        samples.forEach { link ->
            val first = (P.parseLine(link) as? ir.v2max.core.protocol.ParseResult.Ok)?.server
            val s = T.notNull("parsed: ${link.take(40)}", first) ?: return@forEach
            val rebuilt = P.toLink(s)
            val second = (P.parseLine(rebuilt) as? ir.v2max.core.protocol.ParseResult.Ok)?.server
            T.eq(
                "dedupe key stable for ${s.displayName}",
                s.dedupeKey(),
                second?.dedupeKey(),
            )
        }

        // VMess base64 JSON round-trip
        val vmessJson = """{"v":"2","ps":"RTV","add":"vm.example.com","port":"8443","id":"vmid","scy":"auto","net":"tcp","type":"none","host":"","path":"","tls":"","sni":"","alpn":"","fp":""}"""
        val enc = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(vmessJson.toByteArray())
        val vm = (P.parseLine("vmess://$enc") as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        val vmLink = T.notNull("vmess parsed", vm)?.let { P.toLink(it) }.orEmpty()
        T.ok("vmess link starts with scheme", vmLink.startsWith("vmess://"))
        val vm2 = (P.parseLine(vmLink) as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("vmess round-trip address", "vm.example.com", vm2?.endpoint?.address)
        T.eq("vmess round-trip port", 8443, vm2?.endpoint?.port)
        T.eq("vmess round-trip uuid", "vmid", (vm2?.credential as? Credential.Uuid)?.uuid)
        T.eq("vmess round-trip name", "RTV", vm2?.displayName)

        // SS round-trip
        val ssCred = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:pw".toByteArray())
        val ss = (P.parseLine("ss://$ssCred@ss.example.com:8388#SSRT")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        val ssLink = T.notNull("ss parsed", ss)?.let { P.toLink(it) }.orEmpty()
        val ss2 = (P.parseLine(ssLink) as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("ss round-trip method", "aes-256-gcm", (ss2?.credential as? Credential.Cipher)?.method)
        T.eq("ss round-trip password", "pw", (ss2?.credential as? Credential.Cipher)?.password)
        T.eq("ss round-trip address", "ss.example.com", ss2?.endpoint?.address)

        // Names with spaces and non-ASCII survive
        val named = (P.parseLine("vless://u@n.example.com:443#${P.encodeComponent("سرور ایران 🇮🇷")}")
            as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("unicode name decoded", "سرور ایران 🇮🇷", named?.displayName)
        val reNamed = (P.parseLine(P.toLink(named!!)) as? ir.v2max.core.protocol.ParseResult.Ok)?.server
        T.eq("unicode name round-trips", "سرور ایران 🇮🇷", reNamed?.displayName)
    }
}
