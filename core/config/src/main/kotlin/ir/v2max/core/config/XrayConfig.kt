package ir.v2max.core.config

import ir.v2max.core.model.AppSettings
import ir.v2max.core.model.Credential
import ir.v2max.core.model.FragmentProfile
import ir.v2max.core.model.GeoAssets
import ir.v2max.core.model.Proto
import ir.v2max.core.model.RoutingMode
import ir.v2max.core.model.Security
import ir.v2max.core.model.Server
import ir.v2max.core.model.Transport
import ir.v2max.core.protocol.Json

/** Raised when a server cannot be expressed as a valid Xray outbound. */
class ConfigException(message: String) : IllegalArgumentException(message)

/**
 * Generates Xray-core configuration JSON.
 *
 * The output goes straight to libv2ray, both for latency testing and for
 * running the tunnel, so a mistake here shows up as a connection that silently
 * fails rather than a readable error. That is why invalid input raises
 * [ConfigException] instead of emitting a config the core will reject.
 */
object XrayConfig {

    const val TAG_PROXY = "proxy"
    const val TAG_BALANCER = "auto"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_DNS_OUT = "dns-out"
    const val TAG_DNS_MODULE = "dns-module"
    const val TAG_DNS_DOMESTIC = "dns-domestic"

    private const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"

    /** Private/local ranges that must always go direct. */
    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "224.0.0.0/4",
        "240.0.0.0/4",
        "255.255.255.255/32",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
        "ff00::/8",
    )

    // ------------------------------------------------------------------ public

    /** Minimal config with no inbound — used for real latency tests. */
    fun testConfig(server: Server, settings: AppSettings): String = JObj()
        .obj("log") { put("loglevel", "warning") }
        .put("inbounds", JArr())
        .arr("outbounds") { obj { outboundInto(this, server, settings, TAG_PROXY) } }
        .render()

    /**
     * Config with a local SOCKS inbound for measuring real download speed.
     *
     * Runs with tunFd = 0 so traffic only flows through the local port.
     */
    fun socksTestConfig(server: Server, settings: AppSettings, port: Int): String = JObj()
        .obj("log") { put("loglevel", "warning") }
        .arr("inbounds") {
            obj {
                put("tag", "socks-in")
                put("protocol", "socks")
                put("listen", "127.0.0.1")
                put("port", port)
                obj("settings") {
                    put("auth", "noauth")
                    put("udp", false)
                }
                obj("sniffing") {
                    put("enabled", true)
                    arr("destOverride") { add("http"); add("tls") }
                }
            }
        }
        .arr("outbounds") {
            obj { outboundInto(this, server, settings, TAG_PROXY) }
            obj { put("protocol", "freedom"); put("tag", TAG_DIRECT) }
            obj { put("protocol", "blackhole"); put("tag", TAG_BLOCK) }
        }
        .render()

    /**
     * Full VPN config.
     *
     * @param pool additional top servers for the automatic pool. When more than
     *   one is usable and the balancer setting is on, the core picks the
     *   healthiest via leastPing and fails over without dropping the connection.
     * @param geo resolved geo-asset tags, injected by the platform layer.
     */
    fun vpnConfig(
        server: Server,
        settings: AppSettings,
        pool: List<Server> = emptyList(),
        geo: GeoAssets = GeoAssets.NONE,
    ): String {
        val extra = if (settings.balancer) {
            pool.filter { it.id != server.id && it.endpoint.usable }
                .take((settings.balancerSize - 1).coerceIn(0, 5))
        } else {
            emptyList()
        }
        val useBalancer = extra.isNotEmpty()

        val root = JObj()
        root.obj("log") { put("loglevel", "warning") }
        root.put("stats", JObj())
        root.obj("policy") {
            obj("levels") {
                obj("0") {
                    put("handshake", 4)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                }
            }
            obj("system") {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
        }

        // TUN inbound bound directly to the OS file descriptor.
        root.arr("inbounds") {
            obj {
                put("tag", "tun")
                put("protocol", "tun")
                obj("settings") {
                    put("name", "xray0")
                    put("MTU", settings.mtu.coerceIn(1280, 9000))
                    put("userLevel", 0)
                }
                obj("sniffing") {
                    put("enabled", true)
                    arr("destOverride") { add("http"); add("tls"); add("quic") }
                }
            }
        }

        root.arr("outbounds") {
            obj { outboundInto(this, server, settings, TAG_PROXY) }
            extra.forEachIndexed { index, cfg ->
                obj { outboundInto(this, cfg, settings, "proxy-${index + 1}") }
            }
            obj {
                put("tag", TAG_DIRECT)
                put("protocol", "freedom")
                obj("streamSettings") {
                    obj("sockopt") {
                        put("domainStrategy", "UseIP")
                        obj("happyEyeballs") {
                            put("tryDelayMs", 250)
                            put("interleave", 2)
                        }
                    }
                }
            }
            obj {
                put("tag", TAG_BLOCK)
                put("protocol", "blackhole")
                obj("settings") { obj("response") { put("type", "http") } }
            }
            obj {
                put("tag", TAG_DNS_OUT)
                put("protocol", "dns")
            }
        }

        root.put("dns", buildDns(settings))

        // Observatory continuously measures pool members; leastPing needs it.
        if (useBalancer) {
            root.obj("observatory") {
                arr("subjectSelector") { add(TAG_PROXY) }
                put("probeUrl", settings.delayTestUrl.ifBlank { DEFAULT_PROBE_URL })
                put("probeInterval", "60s")
                put("enableConcurrency", true)
            }
        }

        root.put("routing", buildRouting(settings, useBalancer, geo))
        return root.render()
    }

    // ------------------------------------------------------------------ dns

    fun buildDns(settings: AppSettings): JObj {
        val dns = JObj()
        dns.put("tag", TAG_DNS_MODULE)
        dns.put("queryStrategy", if (settings.ipv6) "UseIP" else "UseIPv4")
        dns.put("enableParallelQuery", true)

        dns.arr("servers") {
            // Domestic resolver, used only for .ir and reached directly.
            if (settings.routingMode == RoutingMode.SMART && settings.domesticDns.isNotBlank()) {
                obj {
                    put("address", settings.domesticDns.trim())
                    put("skipFallback", true)
                    put("tag", TAG_DNS_DOMESTIC)
                    arr("domains") { add("domain:ir") }
                }
            }
            // Primary resolver, reached through the tunnel (DoH).
            obj {
                put("address", settings.remoteDns.ifBlank { "https://1.1.1.1/dns-query" }.trim())
            }
        }

        // Pin the well-known DoH endpoints so resolving them can't depend on DNS.
        dns.obj("hosts") {
            put("cloudflare-dns.com", "1.1.1.1")
            put("dns.google", "8.8.8.8")
            put("dns.quad9.net", "9.9.9.9")
        }
        return dns
    }

    // -------------------------------------------------------------- routing

    /** Routing section only — used by the routing preview screen. */
    fun routingJson(settings: AppSettings, useBalancer: Boolean = false, geo: GeoAssets = GeoAssets.NONE): JObj =
        buildRouting(settings, useBalancer, geo)

    private fun buildRouting(settings: AppSettings, useBalancer: Boolean, geo: GeoAssets): JObj {
        val routing = JObj()
        routing.put("domainStrategy", "AsIs")

        routing.arr("rules") {
            // 1) Domestic DNS answers go direct.
            if (settings.routingMode == RoutingMode.SMART) {
                obj {
                    arr("inboundTag") { add(TAG_DNS_DOMESTIC) }
                    put("outboundTag", TAG_DIRECT)
                }
            }

            // 2) The DNS module's own queries must go through the tunnel.
            obj {
                arr("inboundTag") { add(TAG_DNS_MODULE) }
                proxyTarget(this, useBalancer)
            }

            // 3) Only hijack DNS arriving on the TUN; the core's own lookups
            //    must not be re-hijacked.
            obj {
                arr("inboundTag") { add("tun") }
                put("network", "tcp,udp")
                put("port", "53")
                put("outboundTag", TAG_DNS_OUT)
            }

            // 4) Private and local addresses go direct without needing geoip.
            obj {
                arr("ip") { addAll(PRIVATE_CIDRS) }
                put("outboundTag", TAG_DIRECT)
            }

            // 5) Smart mode: Iranian domains and IPs go direct.
            if (settings.routingMode == RoutingMode.SMART) {
                obj {
                    arr("domain") {
                        if (settings.geoAssets) geo.geositeTag?.let { add("ext:geosite.dat:$it") }
                        add("domain:ir")
                    }
                    put("outboundTag", TAG_DIRECT)
                }
                if (settings.geoAssets) {
                    geo.geoipTag?.let { tag ->
                        obj {
                            arr("ip") { add("ext:geoip.dat:$tag") }
                            put("outboundTag", TAG_DIRECT)
                        }
                    }
                }
            }

            // 6) User-defined rules.
            buildCustomRules(settings, useBalancer).forEach { add(it) }

            // 7) Catch-all, otherwise the core falls back to the first outbound
            //    and bypasses the balancer entirely.
            if (useBalancer) {
                obj {
                    put("network", "tcp,udp")
                    proxyTarget(this, true)
                }
            }
        }

        if (useBalancer) {
            routing.arr("balancers") {
                obj {
                    put("tag", TAG_BALANCER)
                    arr("selector") { add(TAG_PROXY) }
                    obj("strategy") { put("type", "leastPing") }
                    put("fallbackTag", TAG_PROXY)
                }
            }
        }
        return routing
    }

    /** A rule's proxy target: the balancer when pooled, otherwise the outbound. */
    private fun proxyTarget(rule: JObj, useBalancer: Boolean) {
        if (useBalancer) rule.put("balancerTag", TAG_BALANCER) else rule.put("outboundTag", TAG_PROXY)
    }

    /**
     * Converts user routing rules into routing entries.
     *
     * Malformed lines are skipped rather than fatal: one typo in the routing
     * screen must not take down the whole connection.
     */
    fun buildCustomRules(settings: AppSettings, useBalancer: Boolean = false): List<JObj> {
        val out = ArrayList<JObj>()
        for (raw in settings.customRoutingRules) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("|").map { it.trim() }
            if (parts.size < 3) continue

            val outbound = when (parts[0].lowercase()) {
                "direct" -> TAG_DIRECT
                "proxy" -> if (useBalancer) TAG_BALANCER else TAG_PROXY
                "block", "reject" -> TAG_BLOCK
                else -> continue
            }
            val values = parts.drop(2).joinToString(",").split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (values.isEmpty()) continue

            val rule = JObj()
            when (parts[1].lowercase()) {
                "domain" -> rule.arr("domain") {
                    values.forEach {
                        add(
                            if (it.startsWith("domain:") || it.startsWith("full:") ||
                                it.startsWith("regexp:") || it.startsWith("keyword:")
                            ) it else "domain:$it"
                        )
                    }
                }

                "ip" -> rule.arr("ip") { addAll(values) }
                "port" -> rule.put("port", values.joinToString(","))
                else -> continue
            }
            if (outbound == TAG_BALANCER) rule.put("balancerTag", outbound) else rule.put("outboundTag", outbound)
            out.add(rule)
        }
        return out
    }

    // ------------------------------------------------------------- outbound

    /** Renders one server as a complete Xray outbound. */
    fun outbound(server: Server, settings: AppSettings, tag: String = TAG_PROXY): JObj =
        JObj().also { outboundInto(it, server, settings, tag) }

    private fun outboundInto(o: JObj, server: Server, settings: AppSettings, tag: String) {
        val address = server.endpoint.address.trim()
        val port = server.endpoint.port
        if (address.isBlank() || port !in 1..65535) {
            throw ConfigException("Server address or port is not valid")
        }

        o.put("tag", tag)
        o.put("protocol", protocolName(server.proto))

        o.obj("settings") {
            put("address", address)
            put("port", port)
            when (val c = server.credential) {
                is Credential.Uuid -> when (server.proto) {
                    Proto.VMESS -> {
                        put("id", c.uuid)
                        put("security", c.method.ifBlank { "auto" })
                    }

                    Proto.VLESS -> {
                        put("id", c.uuid)
                        put("encryption", c.method.ifBlank { "none" })
                        putIf("flow", c.flow.isNotBlank(), c.flow)
                    }

                    else -> put("id", c.uuid)
                }

                is Credential.Password -> {
                    put("password", c.password)
                    putIf("flow", c.flow.isNotBlank(), c.flow)
                }

                is Credential.Cipher -> {
                    put("password", c.password)
                    put("method", c.method.ifBlank { "aes-256-gcm" })
                }

                is Credential.UserPass -> if (c.user.isNotBlank()) {
                    put("user", c.user)
                    put("pass", c.password)
                }
            }
            // Hysteria2 needs an explicit protocol version in settings.
            putIf("version", server.proto == Proto.HYSTERIA2, 2)
            put("level", 0)
        }

        o.put("streamSettings", streamSettings(server, settings))

        // Mux only helps VMess/VLESS, and must be off entirely under XTLS flow.
        val flow = when (val c = server.credential) {
            is Credential.Uuid -> c.flow
            is Credential.Password -> c.flow
            else -> ""
        }
        if (settings.mux && server.proto in MUX_CAPABLE) {
            o.obj("mux") {
                put("enabled", true)
                put("concurrency", if (flow.isNotBlank()) -1 else settings.muxConcurrency.coerceIn(1, 128))
                put("xudpConcurrency", 8)
                put("xudpProxyUDP443", "reject")
            }
        } else {
            o.obj("mux") { put("enabled", false) }
        }
    }

    private val MUX_CAPABLE = setOf(Proto.VMESS, Proto.VLESS)

    private fun protocolName(proto: Proto): String = when (proto) {
        Proto.VMESS -> "vmess"
        Proto.VLESS -> "vless"
        Proto.TROJAN -> "trojan"
        Proto.SS -> "shadowsocks"
        Proto.SOCKS -> "socks"
        Proto.HTTP -> "http"
        Proto.HYSTERIA2 -> "hysteria"
    }

    // ------------------------------------------------------------ transport

    fun streamSettings(server: Server, settings: AppSettings): JObj {
        val ss = JObj()
        val t = server.transport
        val network = if (server.proto == Proto.HYSTERIA2) "hysteria" else t.wire
        ss.put("network", network)

        when (t) {
            is Transport.Tcp -> ss.obj("tcpSettings") {
                obj("header") {
                    put("type", t.headerType.ifBlank { "none" })
                    if (t.headerType == "http") {
                        obj("request") {
                            arr("path") { add(t.path.ifBlank { "/" }) }
                            val hosts = t.host.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (hosts.isNotEmpty()) {
                                obj("headers") { arr("Host") { addAll(hosts) } }
                            }
                        }
                    }
                }
            }

            is Transport.WebSocket -> ss.obj("wsSettings") {
                put("path", t.path.ifBlank { "/" })
                putNonBlank("host", t.host)
            }

            is Transport.Grpc -> ss.obj("grpcSettings") {
                put("serviceName", t.serviceName)
                putNonBlank("authority", t.authority)
                putIf("multiMode", t.mode == "multi", true)
                put("idle_timeout", 60)
                put("health_check_timeout", 20)
            }

            is Transport.HttpUpgrade -> ss.obj("httpupgradeSettings") {
                put("path", t.path.ifBlank { "/" })
                putNonBlank("host", t.host)
            }

            is Transport.XHttp -> ss.obj("xhttpSettings") {
                put("path", t.path.ifBlank { "/" })
                putNonBlank("host", t.host)
                putNonBlank("mode", t.mode)
                if (t.extra.isNotBlank()) {
                    // Validated here, then spliced in verbatim: re-encoding it
                    // as a string would produce a value the core cannot read.
                    Json.parse(t.extra)?.asObject()
                        ?: throw ConfigException("XHTTP extra settings must be a JSON object")
                    put("extra", RawJson(t.extra))
                }
            }

            is Transport.Kcp -> {
                ss.obj("kcpSettings") {
                    put("mtu", t.mtu)
                    put("tti", t.tti)
                }
                // Current Xray no longer reads the legacy seed/header inside
                // kcpSettings, so they are expressed as mkcp-legacy masks, the
                // same way v2rayNG does it.
                ss.obj("finalmask") {
                    arr("udp") {
                        obj {
                            put("type", "mkcp-legacy")
                            obj("settings") { putNonBlank("value", t.seed) }
                        }
                        if (t.headerType.isNotBlank() && t.headerType != "none") {
                            obj {
                                put("type", "mkcp-legacy")
                                obj("settings") {
                                    put(
                                        "header",
                                        if (t.headerType == "wechat-video") "wechat" else t.headerType,
                                    )
                                    if (t.headerType == "dns" && server.proxyHost().isNotBlank()) {
                                        put("value", server.proxyHost())
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is Transport.Hysteria -> {
                ss.obj("hysteriaSettings") {
                    put("version", 2)
                    put("auth", (server.credential as? Credential.Password)?.password.orEmpty())
                }
                if (server.obfuscation.password.isNotBlank()) {
                    ss.obj("finalmask") {
                        arr("udp") {
                            obj {
                                put("type", "salamander")
                                obj("settings") { put("password", server.obfuscation.password) }
                            }
                        }
                    }
                }
            }
        }

        // ---- security
        val security = server.security
        ss.put("security", when {
            server.proto == Proto.HYSTERIA2 -> "tls"
            security is Security.Tls -> "tls"
            security is Security.Reality -> "reality"
            else -> "none"
        })

        val sni = server.effectiveSni
        val alpnList = when (val s = security) {
            is Security.Tls -> s.alpn
            else -> ""
        }.ifBlank { if (server.proto == Proto.HYSTERIA2) "h3" else "" }
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        when (security) {
            is Security.Tls -> ss.obj("tlsSettings") {
                put("serverName", sni)
                // A pin is stricter than skipping verification, so it wins.
                put("allowInsecure", security.allowInsecure && security.pinnedPeerCertSha256.isBlank())
                putNonBlank("echConfigList", security.echConfigList)
                putNonBlank("verifyPeerCertByName", security.verifyPeerCertByName)
                putNonBlank("pinnedPeerCertSha256", security.pinnedPeerCertSha256)
                putNonBlank("fingerprint", security.fingerprint)
                if (alpnList.isNotEmpty()) arr("alpn") { addAll(alpnList) }
            }

            is Security.Reality -> ss.obj("realitySettings") {
                put("serverName", sni)
                put("fingerprint", security.fingerprint.ifBlank { "chrome" })
                put("publicKey", security.publicKey)
                put("shortId", security.shortId)
                put("spiderX", security.spiderX.ifBlank { "/" })
            }

            Security.None -> Unit
        }

        // An explicit mask from the share link overrides generated fragmentation.
        if (server.finalMask.isNotBlank()) {
            Json.parse(server.finalMask)?.asObject()
                ?: throw ConfigException("finalmask settings must be a JSON object")
            ss.put("finalmask", RawJson(server.finalMask))
        }

        // ---- TLS fragmentation to get past DPI
        val secured = security is Security.Tls || security is Security.Reality
        val fragmentable = network != "hysteria" && network != "kcp"
        if (server.finalMask.isBlank() && settings.fragment && secured && fragmentable && !ss.has("finalmask")) {
            val profile: FragmentProfile = settings.fragmentProfile
            ss.obj("finalmask") {
                arr("tcp") {
                    obj {
                        put("type", "fragment")
                        obj("settings") {
                            // REALITY needs a much smaller split than plain TLS.
                            put("packets", if (security is Security.Reality) "1-3" else profile.packets)
                            put("length", profile.length)
                            put("delay", profile.delay)
                            put("maxSplit", profile.maxSplit)
                        }
                    }
                }
            }
        }

        return ss
    }
}
