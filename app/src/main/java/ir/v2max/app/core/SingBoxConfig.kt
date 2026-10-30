package ir.v2max.app.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Proto
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig

/**
 * ساخت کانفیگ JSON برای هسته‌ی sing-box (libbox 1.14).
 *
 * این هسته فقط برای پروتکل‌هایی استفاده می‌شود که Xray-core پشتیبانی نمی‌کند:
 * Hysteria2، TUIC، AnyTLS و WireGuard. برای بقیه‌ی پروتکل‌ها همان ConfigBuilder (Xray) به کار می‌رود.
 *
 * نکات مهم قالب 1.14 که در این فایل رعایت شده‌اند:
 *  - از نسخه‌ی 1.11، outbound نوع «wireguard» حذف شده و باید به‌صورت endpoint نوشته شود.
 *  - از نسخه‌ی 1.14، اگر آدرس سرور دامنه باشد، وجود `domain_resolver` (یا
 *    `route.default_domain_resolver`) الزامی است؛ بنابراین همیشه یک DNS محلی تعریف می‌کنیم.
 *
 * طراحی محافظه‌کارانه: کانفیگ تا حد امکان ساده ساخته می‌شود و پیش از اجرا با
 * `Libbox.checkConfig` اعتبارسنجی می‌گردد تا خطای قالب هرگز کل برنامه را زمین نزند.
 */
object SingBoxConfig {

    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_TUN = "tun-in"
    const val TAG_SOCKS = "socks-in"

    private const val TAG_DNS = "dns-local"

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
        addProxyEntries(root, server, settings)
        root.add("route", route())
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
        addProxyEntries(root, server, settings)
        root.add("route", route())
        return root.toString()
    }

    /**
     * افزودن مسیر پروکسی به کانفیگ: برای WireGuard از `endpoints` و برای بقیه از `outbounds` استفاده می‌شود.
     */
    private fun addProxyEntries(root: JsonObject, server: ServerConfig, settings: AppSettings) {
        val outbounds = JsonArray().apply {
            if (server.proto != Proto.WIREGUARD) add(proxyOutbound(server, settings))
            add(
                JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", TAG_DIRECT)
                }
            )
        }
        root.add("outbounds", outbounds)
        if (server.proto == Proto.WIREGUARD) {
            root.add("endpoints", JsonArray().apply { add(wireGuardEndpoint(server, settings)) })
        }
    }

    private fun base(settings: AppSettings): JsonObject = JsonObject().apply {
        add(
            "log", JsonObject().apply {
                addProperty("level", "warn")
                addProperty("timestamp", true)
            }
        )
        // resolver محلی برای تبدیل دامنه‌ی سرورها؛ بدون این بخش، sing-box 1.14
        // کانفیگ‌هایی که آدرس سرورشان دامنه است را رد می‌کند.
        add(
            "dns", JsonObject().apply {
                add(
                    "servers",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("type", "local")
                                addProperty("tag", TAG_DNS)
                            }
                        )
                    }
                )
            }
        )
    }

    /** مسیریابی ساده: آدرس‌های خصوصی مستقیم، بقیه از پروکسی */
    private fun route(): JsonObject = JsonObject().apply {
        addProperty("final", TAG_PROXY)
        addProperty("default_domain_resolver", TAG_DNS)
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
                if (server.obfsType.isNotBlank() && server.obfsPassword.isNotBlank()) {
                    o.add(
                        "obfs", JsonObject().apply {
                            addProperty("type", server.obfsType.ifBlank { "salamander" })
                            addProperty("password", server.obfsPassword)
                        }
                    )
                }
                o.add("tls", tls(server, defaultAlpn = null))
            }

            Proto.TUIC -> {
                o.addProperty("type", "tuic")
                o.addProperty("server", server.address.trim())
                o.addProperty("server_port", server.port.coerceIn(1, 65535))
                o.addProperty("uuid", server.uuid.trim())
                o.addProperty("password", server.password.ifBlank { server.uuid.trim() })
                o.addProperty("congestion_control", "bbr")
                o.addProperty("udp_relay_mode", "native")
                o.addProperty("zero_rtt_handshake", false)
                o.add("tls", tls(server, defaultAlpn = "h3"))
            }

            Proto.ANYTLS -> {
                o.addProperty("type", "anytls")
                o.addProperty("server", server.address.trim())
                o.addProperty("server_port", server.port.coerceIn(1, 65535))
                o.addProperty("password", server.password.ifBlank { server.uuid.trim() })
                o.addProperty("idle_session_check_interval", "30s")
                o.addProperty("idle_session_timeout", "30s")
                o.add("tls", tls(server, defaultAlpn = null))
            }

            else -> {
                // برای اطمینان: اگر کانفیگی با هسته‌ی اشتباه به اینجا رسید، خطای روشن بده
                o.addProperty("type", "direct")
                o.addProperty("server", server.address)
            }
        }
        return o
    }

    /** WireGuard در sing-box 1.11+ به‌صورت endpoint تعریف می‌شود (outbound آن حذف شده است) */
    fun wireGuardEndpoint(server: ServerConfig, settings: AppSettings): JsonObject = JsonObject().apply {
        addProperty("type", "wireguard")
        addProperty("tag", TAG_PROXY)
        addProperty("mtu", settings.mtu.coerceIn(1280, 1420))
        add("address", JsonArray().apply { localAddresses(server).forEach { add(it) } })
        addProperty("private_key", server.wgPrivateKey.trim())
        add(
            "peers",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("address", server.address.trim())
                        addProperty("port", server.port.coerceIn(1, 65535))
                        addProperty("public_key", server.wgPublicKey.trim())
                        if (server.wgPreSharedKey.isNotBlank()) {
                            addProperty("pre_shared_key", server.wgPreSharedKey.trim())
                        }
                        add(
                            "allowed_ips",
                            JsonArray().apply {
                                add("0.0.0.0/0")
                                add("::/0")
                            }
                        )
                        addProperty("persistent_keepalive_interval", 25)
                        val reserved = server.wgReserved.split(",").mapNotNull { it.trim().toIntOrNull() }
                        if (reserved.size == 3) {
                            add("reserved", JsonArray().apply { reserved.forEach { add(it) } })
                        }
                    }
                )
            }
        )
    }

    private fun localAddresses(server: ServerConfig): List<String> =
        server.wgAddress.ifBlank { "172.16.0.2/32" }
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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
        Proto.HYSTERIA2, Proto.TUIC, Proto.ANYTLS, Proto.WIREGUARD -> true
        else -> false
    }

    /** آیا تنظیمات فعلی اجازه‌ی مسیریابی هوشمند می‌دهد (برای آینده) */
    fun smartRouting(settings: AppSettings): Boolean = settings.routingMode == RoutingMode.SMART
}
