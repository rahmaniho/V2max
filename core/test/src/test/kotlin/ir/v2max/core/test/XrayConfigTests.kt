package ir.v2max.core.test

import ir.v2max.core.config.ConfigException
import ir.v2max.core.config.XrayConfig
import ir.v2max.core.model.AppSettings
import ir.v2max.core.model.Credential
import ir.v2max.core.model.Endpoint
import ir.v2max.core.model.FragmentProfile
import ir.v2max.core.model.GeoAssets
import ir.v2max.core.model.Proto
import ir.v2max.core.model.RoutingMode
import ir.v2max.core.model.Security
import ir.v2max.core.model.Server
import ir.v2max.core.model.Transport
import ir.v2max.core.protocol.Json

/**
 * Tests for Xray config generation.
 *
 * Every assertion parses the generated JSON back and inspects it structurally,
 * rather than matching on a substring — a substring check would pass on a config
 * with the right keys in the wrong place, which is exactly the class of bug that
 * makes a tunnel fail at runtime.
 */
object XrayConfigTests {

    private val settings = AppSettings()

    private fun vlessReality(
        transport: Transport = Transport.Tcp(),
        security: Security = Security.Reality(publicKey = "PK", shortId = "SI", fingerprint = "chrome"),
    ) = Server(
        name = "Test",
        proto = Proto.VLESS,
        endpoint = Endpoint("r.example.com", 443),
        credential = Credential.Uuid("uuid-1", "none"),
        transport = transport,
        security = security,
    )

    /** Walks a parsed JSON object by dot-separated path. */
    private fun Json.at(path: String): Json? {
        var node: Json? = this
        for (part in path.split('.')) {
            node = when (node) {
                is Json.Obj -> node[part]
                is Json.Arr -> node.items.getOrNull(part.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return node
    }

    fun run() {
        T.group("XrayConfig")

        // ---- testConfig: no inbound, exactly one outbound
        val test = Json.parse(XrayConfig.testConfig(vlessReality(), settings))
        T.eq("testConfig has no inbounds", 0, test?.at("inbounds")?.asArray()?.items?.size)
        T.eq("testConfig has one outbound", 1, test?.at("outbounds")?.asArray()?.items?.size)
        T.eq("testConfig outbound tag", "proxy", test?.at("outbounds.0.tag")?.asString())
        T.eq("testConfig protocol", "vless", test?.at("outbounds.0.protocol")?.asString())

        // ---- protocol name mapping
        fun protoOf(proto: Proto, cred: Credential) = Json.parse(
            XrayConfig.outbound(
                Server(proto = proto, endpoint = Endpoint("h.example.com", 443), credential = cred),
                settings,
            ).render(),
        )?.at("protocol")?.asString()

        T.eq("vmess protocol name", "vmess", protoOf(Proto.VMESS, Credential.Uuid("u", "auto")))
        T.eq("trojan protocol name", "trojan", protoOf(Proto.TROJAN, Credential.Password("p")))
        T.eq("shadowsocks protocol name", "shadowsocks", protoOf(Proto.SS, Credential.Cipher("aes-256-gcm", "p")))
        T.eq("socks protocol name", "socks", protoOf(Proto.SOCKS, Credential.UserPass("u", "p")))
        T.eq("http protocol name", "http", protoOf(Proto.HTTP, Credential.UserPass("u", "p")))
        T.eq("hysteria protocol name", "hysteria", protoOf(Proto.HYSTERIA2, Credential.Password("p")))

        // ---- VLESS settings carry flow only when present
        val withFlow = Json.parse(
            XrayConfig.outbound(
                Server(
                    proto = Proto.VLESS,
                    endpoint = Endpoint("h.example.com", 443),
                    credential = Credential.Uuid("u", "none", "xtls-rprx-vision"),
                ),
                settings,
            ).render(),
        )
        T.eq("vless flow emitted", "xtls-rprx-vision", withFlow?.at("settings.flow")?.asString())
        val noFlow = Json.parse(
            XrayConfig.outbound(
                Server(
                    proto = Proto.VLESS,
                    endpoint = Endpoint("h.example.com", 443),
                    credential = Credential.Uuid("u", "none"),
                ),
                settings,
            ).render(),
        )
        T.eq("vless flow omitted when blank", null, noFlow?.at("settings.flow"))

        // ---- Socks emits credentials only for authenticated proxies
        val anon = Json.parse(
            XrayConfig.outbound(
                Server(proto = Proto.SOCKS, endpoint = Endpoint("h.example.com", 1080),
                    credential = Credential.UserPass("", "")),
                settings,
            ).render(),
        )
        T.eq("anonymous socks has no user", null, anon?.at("settings.user"))
        val authed = Json.parse(
            XrayConfig.outbound(
                Server(proto = Proto.SOCKS, endpoint = Endpoint("h.example.com", 1080),
                    credential = Credential.UserPass("bob", "pw")),
                settings,
            ).render(),
        )
        T.eq("authenticated socks user", "bob", authed?.at("settings.user")?.asString())
        T.eq("authenticated socks pass", "pw", authed?.at("settings.pass")?.asString())

        // ---- streamSettings per transport
        val ws = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.WebSocket("cdn.example.com", "/ws"), Security.None), settings,
        ).render())
        T.eq("ws network", "ws", ws?.at("network")?.asString())
        T.eq("ws path", "/ws", ws?.at("wsSettings.path")?.asString())
        T.eq("ws host", "cdn.example.com", ws?.at("wsSettings.host")?.asString())

        val grpc = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Grpc("svc", "auth.example.com", "multi"), Security.None), settings,
        ).render())
        T.eq("grpc serviceName", "svc", grpc?.at("grpcSettings.serviceName")?.asString())
        T.eq("grpc authority", "auth.example.com", grpc?.at("grpcSettings.authority")?.asString())
        T.eq("grpc multiMode", true, grpc?.at("grpcSettings.multiMode")?.asString()?.toBoolean())
        T.eq("grpc idle_timeout", "60", grpc?.at("grpcSettings.idle_timeout")?.asString())

        val tcpHttp = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Tcp("http", "a.example.com,b.example.com", "/p"), Security.None), settings,
        ).render())
        T.eq("tcp http header type", "http", tcpHttp?.at("tcpSettings.header.type")?.asString())
        T.eq("tcp http path", "/p", tcpHttp?.at("tcpSettings.header.request.path.0")?.asString())
        T.eq("tcp http host count", 2, tcpHttp?.at("tcpSettings.header.request.headers.Host")?.asArray()?.items?.size)

        // ---- TLS settings
        val tls = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Tcp(), Security.Tls(sni = "s.example.com", alpn = "h2,h3", fingerprint = "chrome")),
            settings,
        ).render())
        T.eq("tls serverName", "s.example.com", tls?.at("tlsSettings.serverName")?.asString())
        T.eq("tls alpn split", 2, tls?.at("tlsSettings.alpn")?.asArray()?.items?.size)
        T.eq("tls security", "tls", tls?.at("security")?.asString())

        val reality = Json.parse(XrayConfig.streamSettings(vlessReality(), settings).render())
        T.eq("reality security", "reality", reality?.at("security")?.asString())
        T.eq("reality publicKey", "PK", reality?.at("realitySettings.publicKey")?.asString())
        T.eq("reality shortId", "SI", reality?.at("realitySettings.shortId")?.asString())
        T.eq("reality default spiderX", "/", reality?.at("realitySettings.spiderX")?.asString())

        // allowInsecure is honoured, but a pin overrides it
        val insecure = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Tcp(), Security.Tls(sni = "s.example.com", allowInsecure = true)),
            settings,
        ).render())
        T.eq("allowInsecure emitted", "true", insecure?.at("tlsSettings.allowInsecure")?.asString())
        val pinned = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Tcp(),
                Security.Tls(sni = "s.example.com", allowInsecure = true, pinnedPeerCertSha256 = "PIN")),
            settings,
        ).render())
        T.eq("pin suppresses allowInsecure", "false", pinned?.at("tlsSettings.allowInsecure")?.asString())
        T.eq("pin emitted", "PIN", pinned?.at("tlsSettings.pinnedPeerCertSha256")?.asString())

        // ---- fragmentation
        T.ok("fragment emitted for reality", reality?.at("finalmask.tcp.0.settings") != null)
        T.eq("reality fragment packets", "1-3", reality?.at("finalmask.tcp.0.settings.packets")?.asString())

        val balanced = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Tcp(), Security.Tls(sni = "s.example.com")),
            settings.copy(fragmentProfile = FragmentProfile.AGGRESSIVE),
        ).render())
        // `packets` is the packet selector and is "tlshello" for every profile;
        // the value that differs between profiles is `length`.
        T.eq("packets selector", "tlshello", balanced?.at("finalmask.tcp.0.settings.packets")?.asString())
        T.eq("profile length honoured", "80-200", balanced?.at("finalmask.tcp.0.settings.length")?.asString())
        T.eq("profile delay honoured", "15-30", balanced?.at("finalmask.tcp.0.settings.delay")?.asString())
        T.eq("profile maxSplit honoured", "200", balanced?.at("finalmask.tcp.0.settings.maxSplit")?.asString())

        val noFrag = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Tcp(), Security.Tls(sni = "s.example.com")),
            settings.copy(fragment = false),
        ).render())
        T.eq("fragment off when disabled", null, noFrag?.at("finalmask"))

        // KCP and Hysteria are not fragmentable
        val kcp = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Kcp("none", "SEED", 1400, 40), Security.Tls(sni = "s.example.com")),
            settings,
        ).render())
        T.eq("kcp network", "kcp", kcp?.at("network")?.asString())
        T.eq("kcp mtu", "1400", kcp?.at("kcpSettings.mtu")?.asString())
        T.eq("kcp mask type", "mkcp-legacy", kcp?.at("finalmask.udp.0.type")?.asString())
        T.eq("kcp seed in mask", "SEED", kcp?.at("finalmask.udp.0.settings.value")?.asString())

        val hy = Server(
            proto = Proto.HYSTERIA2,
            endpoint = Endpoint("h.example.com", 443),
            credential = Credential.Password("hp"),
            transport = Transport.Hysteria("h.example.com"),
            security = Security.Tls(sni = "h.example.com"),
            obfuscation = ir.v2max.core.model.Obfuscation("salamander", "opw"),
        )
        val hyJson = Json.parse(XrayConfig.streamSettings(hy, settings).render())
        T.eq("hysteria network", "hysteria", hyJson?.at("network")?.asString())
        T.eq("hysteria version", "2", hyJson?.at("hysteriaSettings.version")?.asString())
        T.eq("hysteria auth", "hp", hyJson?.at("hysteriaSettings.auth")?.asString())
        T.eq("hysteria salamander mask", "salamander", hyJson?.at("finalmask.udp.0.type")?.asString())
        T.eq("hysteria forced tls", "tls", hyJson?.at("security")?.asString())

        // ---- explicit finalmask from a link overrides generated fragmentation
        val explicit = Json.parse(XrayConfig.streamSettings(
            vlessReality(Transport.Tcp(), Security.Tls(sni = "s.example.com"))
                .copy(finalMask = """{"tcp":[{"type":"custom"}]}"""),
            settings,
        ).render())
        T.eq("explicit mask wins", "custom", explicit?.at("finalmask.tcp.0.type")?.asString())

        // ---- mux
        val muxOn = Json.parse(XrayConfig.outbound(
            vlessReality(), settings.copy(mux = true, muxConcurrency = 16),
        ).render())
        T.eq("mux enabled", "true", muxOn?.at("mux.enabled")?.asString())
        T.eq("mux concurrency", "16", muxOn?.at("mux.concurrency")?.asString())
        val muxFlow = Json.parse(XrayConfig.outbound(
            Server(
                proto = Proto.VLESS, endpoint = Endpoint("h.example.com", 443),
                credential = Credential.Uuid("u", "none", "xtls-rprx-vision"),
                security = Security.Reality(publicKey = "PK"),
            ),
            settings.copy(mux = true, muxConcurrency = 16),
        ).render())
        T.eq("mux concurrency -1 under xtls flow", "-1", muxFlow?.at("mux.concurrency")?.asString())
        val muxOff = Json.parse(XrayConfig.outbound(
            Server(proto = Proto.TROJAN, endpoint = Endpoint("h.example.com", 443),
                credential = Credential.Password("p")),
            settings.copy(mux = true),
        ).render())
        T.eq("mux off for trojan", "false", muxOff?.at("mux.enabled")?.asString())

        // ---- full VPN config
        val vpn = Json.parse(XrayConfig.vpnConfig(vlessReality(), settings))
        T.eq("vpn tun inbound", "tun", vpn?.at("inbounds.0.tag")?.asString())
        T.eq("vpn mtu", "1500", vpn?.at("inbounds.0.settings.MTU")?.asString())
        T.ok("vpn has stats", vpn?.at("stats") != null)
        T.ok("vpn has policy", vpn?.at("policy.levels.0") != null)
        // proxy, direct, block, dns-out
        T.eq("vpn outbound count", 4, vpn?.at("outbounds")?.asArray()?.items?.size)
        T.eq("vpn dns module tag", "dns-module", vpn?.at("dns.tag")?.asString())
        T.eq("vpn routing strategy", "AsIs", vpn?.at("routing.domainStrategy")?.asString())
        T.ok("vpn has routing rules", (vpn?.at("routing.rules")?.asArray()?.items?.size ?: 0) > 0)

        // mtu is clamped into the range the TUN device accepts
        val clamped = Json.parse(XrayConfig.vpnConfig(vlessReality(), settings.copy(mtu = 99999)))
        T.eq("mtu clamped to 9000", "9000", clamped?.at("inbounds.0.settings.MTU")?.asString())

        // ---- global mode drops the domestic DNS server and .ir rules
        val global = Json.parse(XrayConfig.vpnConfig(vlessReality(), settings.copy(routingMode = RoutingMode.GLOBAL)))
        T.eq("global dns servers", 1, global?.at("dns.servers")?.asArray()?.items?.size)
        T.eq("global uses UseIPv4", "UseIPv4", global?.at("dns.queryStrategy")?.asString())
        val smart = Json.parse(XrayConfig.vpnConfig(vlessReality(), settings))
        T.eq("smart dns servers", 2, smart?.at("dns.servers")?.asArray()?.items?.size)
        T.eq("smart domestic tag", "dns-domestic", smart?.at("dns.servers.0.tag")?.asString())
        T.eq("ipv6 uses UseIP", "UseIP",
            Json.parse(XrayConfig.vpnConfig(vlessReality(), settings.copy(ipv6 = true)))
                ?.at("dns.queryStrategy")?.asString())

        // geo tags are injected, not looked up from disk
        val withGeo = Json.parse(XrayConfig.vpnConfig(
            vlessReality(), settings, geo = GeoAssets(geositeTag = "ir", geoipTag = "ir"),
        ))
        T.eq("geosite tag injected", "ext:geosite.dat:ir", withGeo?.at("routing.rules.4.domain.0")?.asString())

        // ---- balancer
        val pool = listOf(
            vlessReality(),
            Server(id = "b", proto = Proto.VLESS, endpoint = Endpoint("b.example.com", 443),
                credential = Credential.Uuid("u2"), security = Security.Reality(publicKey = "P2")),
        )
        val bal = Json.parse(XrayConfig.vpnConfig(pool[0], settings.copy(balancer = true), pool = pool))
        T.eq("balancer outbound count", 5, bal?.at("outbounds")?.asArray()?.items?.size)
        T.eq("second proxy tag", "proxy-1", bal?.at("outbounds.1.tag")?.asString())
        T.eq("balancer strategy", "leastPing", bal?.at("routing.balancers.0.strategy.type")?.asString())
        T.ok("observatory present", bal?.at("observatory") != null)
        T.eq("observatory probe interval", "60s", bal?.at("observatory.probeInterval")?.asString())

        val noBal = Json.parse(XrayConfig.vpnConfig(pool[0], settings.copy(balancer = false), pool = pool))
        T.eq("no pool when balancer off", 4, noBal?.at("outbounds")?.asArray()?.items?.size)
        T.eq("no observatory when balancer off", null, noBal?.at("observatory"))

        // ---- custom routing rules
        val rules = XrayConfig.buildCustomRules(
            settings.copy(
                customRoutingRules = listOf(
                    "direct|domain|example.com",
                    "proxy|ip|8.8.8.8/32",
                    "block|domain|ads.example.org",
                    "reject|port|25,587",
                    "# a comment",
                    "",
                    "nonsense",
                    "proxy|badtype|x",
                )
            )
        )
        T.eq("valid custom rules parsed", 4, rules.size)
        val parsed = rules.map { Json.parse(it.render())!! }
        T.eq("domain rule prefixed", "domain:example.com", parsed[0].at("domain.0")?.asString())
        T.eq("domain rule outbound", "direct", parsed[0].at("outboundTag")?.asString())
        T.eq("ip rule", "8.8.8.8/32", parsed[1].at("ip.0")?.asString())
        T.eq("block rule outbound", "block", parsed[2].at("outboundTag")?.asString())
        T.eq("port rule", "25,587", parsed[3].at("port")?.asString())
        T.eq("reject maps to block", "block", parsed[3].at("outboundTag")?.asString())

        // An existing prefix is not double-prefixed
        val prefixed = XrayConfig.buildCustomRules(
            settings.copy(customRoutingRules = listOf("direct|domain|full:exact.com,regexp:^a"))
        )
        val prefixedJson = Json.parse(prefixed[0].render())
        T.eq("full: kept", "full:exact.com", prefixedJson?.at("domain.0")?.asString())
        T.eq("regexp: kept", "regexp:^a", prefixedJson?.at("domain.1")?.asString())

        // proxy rules point at the balancer when pooled
        val proxyRule = XrayConfig.buildCustomRules(
            settings.copy(customRoutingRules = listOf("proxy|ip|1.1.1.1")), useBalancer = true,
        )
        T.eq("proxy rule uses balancer", "auto", Json.parse(proxyRule[0].render())?.at("balancerTag")?.asString())

        // ---- errors
        var threw = false
        try {
            XrayConfig.outbound(Server(endpoint = Endpoint("", 443)), settings)
        } catch (_: ConfigException) {
            threw = true
        }
        T.ok("blank address raises ConfigException", threw)

        threw = false
        try {
            XrayConfig.outbound(Server(endpoint = Endpoint("h.example.com", 70000)), settings)
        } catch (_: ConfigException) {
            threw = true
        }
        T.ok("out-of-range port raises ConfigException", threw)

        threw = false
        try {
            XrayConfig.streamSettings(
                vlessReality(Transport.XHttp(extra = "not json"), Security.None), settings,
            )
        } catch (_: ConfigException) {
            threw = true
        }
        T.ok("malformed xhttp extra raises ConfigException", threw)

        // Valid embedded JSON is spliced verbatim, not re-escaped
        val xh = Json.parse(XrayConfig.streamSettings(
            vlessReality(
                Transport.XHttp(host = "x.example.com", path = "/x", mode = "auto", extra = """{"a":1}"""),
                Security.None,
            ),
            settings,
        ).render())
        T.eq("xhttp extra is an object", "1", xh?.at("xhttpSettings.extra.a")?.asString())
    }
}
