package ir.v2max.app.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Proto
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig

/**
 * سازنده‌ی کانفیگ JSON برای هسته‌ی Xray.
 *
 * خروجی این کلاس مستقیماً به libv2ray داده می‌شود (هم برای تست تأخیر و هم برای اجرای تانل).
 */
object ConfigBuilder {

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val TAG_BLOCK = "block"
    private const val TAG_DNS_OUT = "dns-out"
    private const val TAG_DNS_MODULE = "dns-module"
    private const val TAG_DNS_DOMESTIC = "dns-domestic"

    // ------------------------------------------------------------------ public

    /** کانفیگ سبک فقط برای تست تأخیر واقعی (بدون inbound) */
    fun testConfig(server: ServerConfig, settings: AppSettings): String {
        val root = JsonObject()
        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        root.add("inbounds", JsonArray())
        root.add("outbounds", JsonArray().apply { add(outbound(server, settings)) })
        return root.toString()
    }

    /** کانفیگ کامل برای اجرای VPN */
    fun vpnConfig(server: ServerConfig, settings: AppSettings): String {
        val root = JsonObject()

        // ---- log / stats / policy
        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        root.add("stats", JsonObject())
        root.add(
            "policy", JsonObject().apply {
                add(
                    "levels", JsonObject().apply {
                        add(
                            "0", JsonObject().apply {
                                addProperty("handshake", 4)
                                addProperty("connIdle", 300)
                                addProperty("uplinkOnly", 1)
                                addProperty("downlinkOnly", 1)
                            }
                        )
                    }
                )
                add(
                    "system", JsonObject().apply {
                        addProperty("statsOutboundUplink", true)
                        addProperty("statsOutboundDownlink", true)
                    }
                )
            }
        )

        // ---- inbound: TUN (مستقیماً روی fd سیستم‌عامل)
        val tun = JsonObject().apply {
            addProperty("tag", "tun")
            addProperty("protocol", "tun")
            add(
                "settings", JsonObject().apply {
                    addProperty("name", "xray0")
                    addProperty("MTU", settings.mtu.coerceIn(1280, 9000))
                    addProperty("userLevel", 0)
                }
            )
            add(
                "sniffing", JsonObject().apply {
                    addProperty("enabled", true)
                    add(
                        "destOverride", JsonArray().apply {
                            add("http"); add("tls"); add("quic")
                        }
                    )
                }
            )
        }
        root.add("inbounds", JsonArray().apply { add(tun) })

        // ---- outbounds
        val outbounds = JsonArray()
        outbounds.add(outbound(server, settings))
        outbounds.add(
            JsonObject().apply {
                addProperty("tag", TAG_DIRECT)
                addProperty("protocol", "freedom")
                add(
                    "streamSettings", JsonObject().apply {
                        add(
                            "sockopt", JsonObject().apply {
                                addProperty("domainStrategy", "UseIP")
                                add(
                                    "happyEyeballs", JsonObject().apply {
                                        addProperty("tryDelayMs", 250)
                                        addProperty("interleave", 2)
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
        outbounds.add(
            JsonObject().apply {
                addProperty("tag", TAG_BLOCK)
                addProperty("protocol", "blackhole")
                add(
                    "settings", JsonObject().apply {
                        add("response", JsonObject().apply { addProperty("type", "http") })
                    }
                )
            }
        )
        outbounds.add(
            JsonObject().apply {
                addProperty("tag", TAG_DNS_OUT)
                addProperty("protocol", "dns")
            }
        )
        root.add("outbounds", outbounds)

        // ---- dns
        root.add("dns", buildDns(settings))

        // ---- routing
        root.add("routing", buildRouting(settings))

        return root.toString()
    }

    // ------------------------------------------------------------------ dns

    private fun buildDns(settings: AppSettings): JsonObject {
        val dns = JsonObject()
        dns.addProperty("tag", TAG_DNS_MODULE)
        dns.addProperty("queryStrategy", if (settings.ipv6) "UseIP" else "UseIPv4")
        dns.addProperty("enableParallelQuery", true)

        val servers = JsonArray()

        // DNS داخلی (فقط برای دامنه‌های ایرانی) — مستقیم و بدون تانل
        if (settings.routingMode == RoutingMode.SMART && settings.domesticDns.isNotBlank()) {
            servers.add(
                JsonObject().apply {
                    addProperty("address", settings.domesticDns.trim())
                    addProperty("skipFallback", true)
                    addProperty("tag", TAG_DNS_DOMESTIC)
                    add("domains", JsonArray().apply { add("domain:ir") })
                }
            )
        }

        // DNS اصلی (از طریق تانل، DoH)
        servers.add(
            JsonObject().apply {
                addProperty("address", settings.remoteDns.ifBlank { "https://1.1.1.1/dns-query" }.trim())
            }
        )
        dns.add("servers", servers)

        // نگاشت دامنه‌های DNS شناخته‌شده تا برای خود DNS وابستگی ایجاد نشود
        dns.add(
            "hosts", JsonObject().apply {
                add("cloudflare-dns.com", JsonPrimitive("1.1.1.1"))
                add("dns.google", JsonPrimitive("8.8.8.8"))
                add("dns.quad9.net", JsonPrimitive("9.9.9.9"))
            }
        )
        return dns
    }

    // -------------------------------------------------------------- routing

    private fun buildRouting(settings: AppSettings): JsonObject {
        val rules = JsonArray()

        // 1) پاسخ‌های DNS داخلی از مسیر مستقیم
        if (settings.routingMode == RoutingMode.SMART) {
            rules.add(
                JsonObject().apply {
                    add("inboundTag", JsonArray().apply { add(TAG_DNS_DOMESTIC) })
                    addProperty("outboundTag", TAG_DIRECT)
                }
            )
        }

        // 2) درخواست‌های خود ماژول DNS از تانل عبور کنند
        rules.add(
            JsonObject().apply {
                add("inboundTag", JsonArray().apply { add(TAG_DNS_MODULE) })
                addProperty("outboundTag", TAG_PROXY)
            }
        )

        // 3) ربودن (hijack) همه‌ی درخواست‌های DNS برنامه‌ها
        rules.add(
            JsonObject().apply {
                addProperty("network", "tcp,udp")
                addProperty("port", "53")
                addProperty("outboundTag", TAG_DNS_OUT)
            }
        )

        // 4) آدرس‌های خصوصی و محلی مستقیم
        rules.add(
            JsonObject().apply {
                add("ip", JsonArray().apply { add("geoip:private") })
                addProperty("outboundTag", TAG_DIRECT)
            }
        )

        // 5) حالت هوشمند: دامنه‌های ایرانی مستقیم
        if (settings.routingMode == RoutingMode.SMART) {
            rules.add(
                JsonObject().apply {
                    add("domain", JsonArray().apply { add("domain:ir") })
                    addProperty("outboundTag", TAG_DIRECT)
                }
            )
        }

        val routing = JsonObject()
        routing.addProperty("domainStrategy", "AsIs")
        routing.add("rules", rules)
        return routing
    }

    // ------------------------------------------------------------- outbound

    /** تبدیل یک سرور به یک outbound کامل Xray */
    fun outbound(server: ServerConfig, settings: AppSettings): JsonObject {
        val o = JsonObject()
        o.addProperty("tag", TAG_PROXY)
        o.addProperty("protocol", protocolName(server))

        val address = server.address.trim()
        val port = server.port.coerceIn(1, 65535)

        when (server.proto) {
            Proto.VMESS -> {
                o.add(
                    "settings", JsonObject().apply {
                        addProperty("address", address)
                        addProperty("port", port)
                        addProperty("id", server.uuid)
                        addProperty("security", server.method.ifBlank { "auto" })
                        addProperty("level", 0)
                    }
                )
            }

            Proto.VLESS -> {
                o.add(
                    "settings", JsonObject().apply {
                        addProperty("address", address)
                        addProperty("port", port)
                        addProperty("id", server.uuid)
                        addProperty("encryption", server.method.ifBlank { "none" })
                        if (server.flow.isNotBlank()) addProperty("flow", server.flow)
                        addProperty("level", 0)
                    }
                )
            }

            Proto.TROJAN -> {
                o.add(
                    "settings", JsonObject().apply {
                        addProperty("address", address)
                        addProperty("port", port)
                        addProperty("password", server.password)
                        if (server.flow.isNotBlank()) addProperty("flow", server.flow)
                        addProperty("level", 0)
                    }
                )
            }

            Proto.SS -> {
                o.add(
                    "settings", JsonObject().apply {
                        addProperty("address", address)
                        addProperty("port", port)
                        addProperty("password", server.password)
                        addProperty("method", server.method.ifBlank { "aes-256-gcm" })
                        addProperty("level", 0)
                    }
                )
            }

            Proto.SOCKS -> {
                o.add(
                    "settings", JsonObject().apply {
                        addProperty("address", address)
                        addProperty("port", port)
                        if (server.uuid.isNotBlank()) {
                            addProperty("user", server.uuid)
                            addProperty("pass", server.password)
                        }
                        addProperty("level", 0)
                    }
                )
            }

            Proto.HTTP -> {
                o.add(
                    "settings", JsonObject().apply {
                        addProperty("address", address)
                        addProperty("port", port)
                        if (server.uuid.isNotBlank()) {
                            addProperty("user", server.uuid)
                            addProperty("pass", server.password)
                        }
                        addProperty("level", 0)
                    }
                )
            }

            Proto.HYSTERIA2 -> {
                o.add(
                    "settings", JsonObject().apply {
                        addProperty("address", address)
                        addProperty("port", port)
                        addProperty("version", 2)
                    }
                )
            }
        }

        o.add("streamSettings", streamSettings(server, settings))

        if (settings.mux && server.proto in listOf(Proto.VMESS, Proto.VLESS)) {
            o.add(
                "mux", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty(
                        "concurrency",
                        if (server.flow.isNotBlank()) -1 else settings.muxConcurrency.coerceIn(1, 128)
                    )
                    addProperty("xudpConcurrency", 8)
                    addProperty("xudpProxyUDP443", "reject")
                }
            )
        } else {
            o.add("mux", JsonObject().apply { addProperty("enabled", false) })
        }

        return o
    }

    private fun protocolName(server: ServerConfig): String = when (server.proto) {
        Proto.VMESS -> "vmess"
        Proto.VLESS -> "vless"
        Proto.TROJAN -> "trojan"
        Proto.SS -> "shadowsocks"
        Proto.SOCKS -> "socks"
        Proto.HTTP -> "http"
        Proto.HYSTERIA2 -> "hysteria"
    }

    // ------------------------------------------------------------ transport

    private fun streamSettings(server: ServerConfig, settings: AppSettings): JsonObject {
        val ss = JsonObject()
        var network = server.network.ifBlank { "tcp" }
        if (server.proto == Proto.HYSTERIA2) network = "hysteria"
        ss.addProperty("network", network)

        when (network) {
            "tcp", "raw" -> {
                ss.addProperty("network", "tcp")
                val headerType = server.headerType.ifBlank { "none" }
                ss.add(
                    "tcpSettings", JsonObject().apply {
                        add(
                            "header", JsonObject().apply {
                                addProperty("type", headerType)
                                if (headerType == "http") {
                                    add(
                                        "request", JsonObject().apply {
                                            add(
                                                "path", JsonArray().apply {
                                                    add(server.path.ifBlank { "/" })
                                                }
                                            )
                                            add(
                                                "headers", JsonObject().apply {
                                                    val hosts = server.host.split(",")
                                                        .map { it.trim() }
                                                        .filter { it.isNotEmpty() }
                                                    if (hosts.isNotEmpty()) {
                                                        add(
                                                            "Host", JsonArray().apply {
                                                                hosts.forEach { add(it) }
                                                            }
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        )
                    }
                )
            }

            "ws" -> {
                ss.add(
                    "wsSettings", JsonObject().apply {
                        addProperty("path", server.path.ifBlank { "/" })
                        if (server.host.isNotBlank()) {
                            add(
                                "headers", JsonObject().apply {
                                    addProperty("Host", server.host)
                                }
                            )
                        }
                    }
                )
            }

            "grpc" -> {
                ss.add(
                    "grpcSettings", JsonObject().apply {
                        addProperty("serviceName", server.serviceName)
                        if (server.authority.isNotBlank()) addProperty("authority", server.authority)
                        if (server.mode == "multi") addProperty("multiMode", true)
                        addProperty("idle_timeout", 60)
                        addProperty("health_check_timeout", 20)
                    }
                )
            }

            "h2", "http" -> {
                ss.addProperty("network", "h2")
                ss.add(
                    "httpSettings", JsonObject().apply {
                        addProperty("path", server.path.ifBlank { "/" })
                        val hosts = server.host.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        add(
                            "host", JsonArray().apply {
                                if (hosts.isEmpty()) add(server.address) else hosts.forEach { add(it) }
                            }
                        )
                    }
                )
            }

            "httpupgrade" -> {
                ss.add(
                    "httpupgradeSettings", JsonObject().apply {
                        addProperty("path", server.path.ifBlank { "/" })
                        if (server.host.isNotBlank()) addProperty("host", server.host)
                    }
                )
            }

            "xhttp", "splithttp" -> {
                ss.addProperty("network", "xhttp")
                ss.add(
                    "xhttpSettings", JsonObject().apply {
                        addProperty("path", server.path.ifBlank { "/" })
                        if (server.host.isNotBlank()) addProperty("host", server.host)
                        if (server.mode.isNotBlank()) addProperty("mode", server.mode)
                    }
                )
            }

            "kcp", "mkcp" -> {
                ss.add(
                    "kcpSettings", JsonObject().apply {
                        addProperty("mtu", 1350)
                        addProperty("tti", 50)
                        add(
                            "header", JsonObject().apply {
                                addProperty("type", server.headerType.ifBlank { "none" })
                            }
                        )
                    }
                )
            }

            "quic" -> {
                ss.add(
                    "quicSettings", JsonObject().apply {
                        addProperty("security", "none")
                        addProperty("key", "")
                        add(
                            "header", JsonObject().apply {
                                addProperty("type", server.headerType.ifBlank { "none" })
                            }
                        )
                    }
                )
            }

            "hysteria" -> {
                ss.add(
                    "hysteriaSettings", JsonObject().apply {
                        addProperty("version", 2)
                        addProperty("auth", server.password)
                    }
                )
                server.obfsPassword.takeIf { it.isNotBlank() }?.let { pass ->
                    ss.add(
                        "finalmask", JsonObject().apply {
                            add(
                                "udp", JsonArray().apply {
                                    add(
                                        JsonObject().apply {
                                            addProperty("type", "salamander")
                                            add(
                                                "settings", JsonObject().apply {
                                                    addProperty("password", pass)
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            }
        }

        // ---- security
        val security = when {
            server.proto == Proto.HYSTERIA2 -> "tls"
            server.security.isNotBlank() -> server.security
            else -> "none"
        }
        ss.addProperty("security", security)

        val sni = server.effectiveSni()
        val alpnList = server.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        when (security) {
            "tls" -> {
                ss.add(
                    "tlsSettings", JsonObject().apply {
                        addProperty("serverName", sni)
                        addProperty("allowInsecure", server.allowInsecure)
                        addProperty("fingerprint", server.fingerprint.ifBlank { "chrome" })
                        if (alpnList.isNotEmpty()) {
                            add("alpn", JsonArray().apply { alpnList.forEach { add(it) } })
                        }
                    }
                )
            }

            "reality" -> {
                ss.add(
                    "realitySettings", JsonObject().apply {
                        addProperty("serverName", sni)
                        addProperty("fingerprint", server.fingerprint.ifBlank { "chrome" })
                        addProperty("publicKey", server.publicKey)
                        addProperty("shortId", server.shortId)
                        addProperty("spiderX", server.spiderX.ifBlank { "/" })
                    }
                )
            }
        }

        // ---- fragment (عبور از DPI)
        if (settings.fragment && security in listOf("tls", "reality") && network != "hysteria") {
            val existing = ss.getAsJsonObject("finalmask")
            val mask = existing ?: JsonObject().also { ss.add("finalmask", it) }
            if (!mask.has("tcp")) {
                mask.add(
                    "tcp", JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("type", "fragment")
                                add(
                                    "settings", JsonObject().apply {
                                        addProperty(
                                            "packets",
                                            if (security == "reality") "1-3" else "tlshello"
                                        )
                                        addProperty("length", "50-100")
                                        addProperty("delay", "10-20")
                                        addProperty("maxSplit", "10")
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }

        return ss
    }
}
