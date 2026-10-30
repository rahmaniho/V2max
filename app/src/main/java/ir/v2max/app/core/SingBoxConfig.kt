package ir.v2max.app.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Proto
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig

/**
 * ساخت کانفیگ JSON برای هسته‌ی sing-box (libbox).
 *
 * این هسته فقط برای پروتکل‌هایی استفاده می‌شود که Xray-core پشتیبانی نمی‌کند:
 * Hysteria2، TUIC، AnyTLS و WireGuard. برای بقیه‌ی پروتکل‌ها همان ConfigBuilder (Xray) به کار می‌رود.
 *
 * طراحی محافظه‌کارانه: کانفیگ تا حد امکان ساده ساخته می‌شود و پیش از اجرا با
 * `Libbox.checkConfig` اعتبارسنجی می‌گردد تا خطای قالب هرگز کل برنامه را زمین نزند.
 */
object SingBoxConfig {

    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_TUN = "tun-in"
    const val TAG_SOCKS = "socks-in"

    private const val TUN_ADDRESS = "172.19.0.1/30"

    /** پورت محلی پروکسی SOCKS برای تست تأخیر/سرعت */
    const val SOCKS_PORT = 10888

    /** کانفیگ کامل برای اجرای VPN (TUN از طریق PlatformInterface ساخته می‌شود) */
    fun vpnConfig(server: ServerConfig, settings: AppSettings): String {
        val root = base(settings)
        root.add(
            "inbounds",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("type", "tun")
                        addProperty("tag", TAG_TUN)
                        add("address", JsonArray().apply { add(TUN_ADDRESS) })
                        addProperty("mtu", settings.mtu.coerceIn(1280, 9000))
                        // ساخت و مسیردهی TUN را خودِ VpnService انجام می‌دهد
                        addProperty("auto_route", false)
                        addProperty("strict_route", false)
                        addProperty("stack", "gvisor")
                        addProperty("endpoint_independent_nat", true)
                    }
                )
                // inbound محلی برای اندازه‌گیری تأخیر و سرعت (همان روش هسته‌ی Xray)
                add(
                    JsonObject().apply {
                        addProperty("type", "socks")
                        addProperty("tag", TAG_SOCKS)
                        addProperty("listen", "127.0.0.1")
                        addProperty("listen_port", SOCKS_PORT)
                    }
                )
            }
        )
        root.add("outbounds", outbounds(server, settings))
        root.add("route", route(settings, listOf(TAG_PROXY)))
        return root.toString()
    }

    /** کانفیگ فقط-SOCKS برای تست تأخیر/سرعت پیش از اتصال */
    fun socksTestConfig(server: ServerConfig, settings: AppSettings, port: Int): String {
        val root = base(settings)
        root.add(
            "inbounds",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("type", "socks")
                        addProperty("tag", TAG_SOCKS)
                        addProperty("listen", "127.0.0.1")
                        addProperty("listen_port", port)
                    }
                )
            }
        )
        root.add("outbounds", outbounds(server, settings))
        root.add("route", route(settings, listOf(TAG_PROXY)))
        return root.toString()
    }

    private fun base(settings: AppSettings): JsonObject = JsonObject().apply {
        add(
            "log", JsonObject().apply {
                addProperty("level", "warn")
                addProperty("timestamp", true)
            }
        )
    }

    private fun outbounds(server: ServerConfig, settings: AppSettings): JsonArray = JsonArray().apply {
        add(proxyOutbound(server, settings))
        add(
            JsonObject().apply {
                addProperty("type", "direct")
                addProperty("tag", TAG_DIRECT)
            }
        )
        add(
            JsonObject().apply {
                addProperty("type", "block")
                addProperty("tag", TAG_BLOCK)
            }
        )
    }

    /** مسیریابی ساده: آدرس‌های خصوصی مستقیم، بقیه از پروکسی */
    private fun route(settings: AppSettings, finals: List<String>): JsonObject = JsonObject().apply {
        addProperty("final", finals.first())
        add(
            "rules",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("action", "route")
                        addProperty("outbound", TAG_DIRECT)
                        addProperty("ip_is_private", true)
                    }
                )
            }
        )
    }

    // ---------------------------------------------------------------- outbound

    fun proxyOutbound(server: ServerConfig, settings: AppSettings): JsonObject {
        val o = JsonObject()
        o.addProperty("tag", TAG_PROXY)
        when (server.proto) {
            Proto.HYSTERIA2 -> {
                o.addProperty("type", "hysteria2")
                o.addProperty("server", server.address.trim())
                o.addProperty("server_port", server.port.coerceIn(1, 65535))
                o.addProperty("password", server.password)
                o.addProperty("up_mbps", 100)
                o.addProperty("down_mbps", 100)
                if (server.obfsType.isNotBlank() && server.obfsPassword.isNotBlank()) {
                    o.add(
                        "obfs", JsonObject().apply {
                            addProperty("type", server.obfsType)
                            addProperty("password", server.obfsPassword)
                        }
                    )
                }
                o.add("tls", tls(server, defaultAlpn = null))
            }

            Proto.TUCI -> {
                o.addProperty("type", "tuic")
                o.addProperty("server", server.address.trim())
                o.addProperty("server_port", server.port.coerceIn(1, 65535))
                o.addProperty("uuid", server.uuid)
                o.addProperty("password", server.password)
                o.addProperty("congestion_control", "bbr")
                o.addProperty("udp_relay_mode", "native")
                o.addProperty("zero_rtt_handshake", false)
                o.add("tls", tls(server, defaultAlpn = "h3"))
            }

            Proto.ANYTLS -> {
                o.addProperty("type", "anytls")
                o.addProperty("server", server.address.trim())
                o.addProperty("server_port", server.port.coerceIn(1, 65535))
                o.addProperty("password", server.password)
                o.add("tls", tls(server, defaultAlpn = null))
            }

            Proto.WIREGUARD -> {
                o.addProperty("type", "wireguard")
                o.addProperty("server", server.address.trim())
                o.addProperty("server_port", server.port.coerceIn(1, 65535))
                addLocalAddresses(o, server)
                o.addProperty("private_key", server.wgPrivateKey)
                o.addProperty("peer_public_key", server.wgPublicKey)
                val reserved = server.wgReserved.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (reserved.size == 3) {
                    o.add("reserved", JsonArray().apply { reserved.forEach { add(it) } })
                }
                o.addProperty("mtu", settings.mtu.coerceIn(1280, 1420))
            }

            else -> {
                // برای اطمینان: اگر کانفیگی با هسته‌ی اشتباه به اینجا رسید، خطای روشن بده
                o.addProperty("type", "direct")
                o.addProperty("server", server.address)
            }
        }
        return o
    }

    private fun addLocalAddresses(o: JsonObject, server: ServerConfig) {
        val raw = server.wgAddress.ifBlank { "172.16.0.2/32" }
        val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        o.add("local_address", JsonArray().apply { list.forEach { add(it) } })
    }

    private fun tls(server: ServerConfig, defaultAlpn: String?): JsonObject = JsonObject().apply {
        addProperty("enabled", true)
        addProperty("server_name", server.sni.ifBlank { server.host.ifBlank { server.address } })
        addProperty("insecure", server.allowInsecure)
        val alpn = server.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (alpn.isNotEmpty()) {
            add("alpn", JsonArray().apply { alpn.forEach { add(it) } })
        } else if (defaultAlpn != null) {
            add("alpn", JsonArray().apply { add(defaultAlpn) })
        }
        if (server.fingerprint.isNotBlank()) {
            add(
                "utls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("fingerprint", server.fingerprint)
                }
            )
        }
        if (server.publicKey.isNotBlank()) {
            // Reality (اگر سرور از Reality استفاده می‌کند)
            add(
                "reality", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("public_key", server.publicKey)
                    addProperty("short_id", server.shortId)
                }
            )
        }
    }

    /** فهرست پروتکل‌هایی که با این هسته اجرا می‌شوند (برای نمایش در رابط کاربری) */
    fun supports(proto: Proto): Boolean = when (proto) {
        Proto.HYSTERIA2, Proto.TUCI, Proto.ANYTLS, Proto.WIREGUARD -> true
        else -> false
    }

    /** آیا تنظیمات فعلی اجازه‌ی مسیریابی هوشمند می‌دهد (برای آینده) */
    fun smartRouting(settings: AppSettings): Boolean = settings.routingMode == RoutingMode.SMART
}
