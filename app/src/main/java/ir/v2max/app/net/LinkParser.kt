package ir.v2max.app.net

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ir.v2max.app.data.Proto
import ir.v2max.app.data.ServerConfig
import java.net.URLDecoder

/**
 * تبدیل لینک‌های اشتراک (vmess/vless/trojan/ss/socks/http/hysteria2) به [ServerConfig].
 */
object LinkParser {

    /** پارس یک متن چندخطی (محتویات اشتراک) */
    fun parseAll(text: String): List<ServerConfig> {
        val result = ArrayList<ServerConfig>()
        val body = maybeBase64(text)
        body.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                parseLine(line)?.let { result.add(it) }
            }
        return result
    }

    /** اگر متن base64 باشد آن را رمزگشایی می‌کند */
    fun maybeBase64(text: String): String {
        val trimmed = text.trim()
        if (trimmed.contains("://")) return trimmed
        val decoded = decodeBase64(trimmed) ?: return trimmed
        return if (decoded.contains("://")) decoded else trimmed
    }

    fun parseLine(rawLine: String): ServerConfig? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return null
        val scheme = line.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return null
        return try {
            when (scheme) {
                "vmess" -> parseVmess(line)
                "vless" -> parseStandard(line, Proto.VLESS)
                "trojan" -> parseStandard(line, Proto.TROJAN)
                "ss" -> parseShadowsocks(line)
                "socks", "socks5" -> parseStandard(line, Proto.SOCKS)
                "http", "https" -> parseStandard(line, Proto.HTTP)
                "hysteria2", "hy2" -> parseStandard(line, Proto.HYSTERIA2)
                "tuic" -> parseStandard(line, Proto.TUIC)
                "anytls" -> parseStandard(line, Proto.ANYTLS)
                "wireguard", "wg" -> parseWireGuard(line)
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ vmess

    private fun parseVmess(line: String): ServerConfig? {
        val payload = line.substringAfter("://").substringBefore("#")
        val json = decodeBase64(payload) ?: return null
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null

        fun str(vararg keys: String): String {
            for (k in keys) {
                val v = obj.get(k)?.takeIf { !it.isJsonNull }
                if (v != null && v.isJsonPrimitive) return v.asString
            }
            return ""
        }

        val host = str("add", "address")
        val port = str("port").toIntOrNull() ?: 443
        val id = str("id")
        if (host.isBlank() || id.isBlank()) return null

        val cfg = ServerConfig()
        cfg.proto = Proto.VMESS
        cfg.address = host
        cfg.port = port
        cfg.uuid = id
        cfg.method = str("scy", "security").ifBlank { "auto" }
        cfg.network = str("net", "network").ifBlank { "tcp" }
        cfg.headerType = str("type", "headerType").ifBlank { "none" }
        cfg.host = str("host")
        cfg.path = str("path")
        cfg.serviceName = str("path").takeIf { cfg.network == "grpc" }.orEmpty()
        cfg.authority = str("authority")
        cfg.mode = str("mode")
        cfg.security = str("tls").let { if (it == "tls" || it == "reality") it else "" }
        cfg.sni = str("sni")
        cfg.alpn = str("alpn")
        cfg.fingerprint = str("fp")
        cfg.publicKey = str("pbk")
        cfg.shortId = str("sid")
        cfg.spiderX = str("spx")
        cfg.name = decodeComponent(str("ps", "remarks")).ifBlank { "${host}:$port" }
        normalize(cfg)
        return cfg
    }

    // ------------------------------------------------- vless / trojan / others

    private fun parseStandard(line: String, proto: Proto): ServerConfig? {
        val withoutScheme = line.substringAfter("://")
        val fragment = withoutScheme.substringAfter("#", "")
        val main = withoutScheme.substringBefore("#")
        val queryStr = main.substringAfter("?", "")
        val beforeQuery = main.substringBefore("?")

        val userInfo = beforeQuery.substringBeforeLast("@", "")
        val hostPort = beforeQuery.substringAfterLast("@", beforeQuery)
        if (hostPort.isBlank()) return null

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort(proto)
        } else {
            val idx = hostPort.lastIndexOf(':')
            if (idx < 0) {
                host = hostPort
                port = defaultPort(proto)
            } else {
                host = hostPort.substring(0, idx)
                port = hostPort.substring(idx + 1).toIntOrNull() ?: defaultPort(proto)
            }
        }
        if (host.isBlank()) return null

        val q = parseQuery(queryStr)
        val cfg = ServerConfig()
        cfg.proto = proto
        cfg.address = host
        cfg.port = port

        val decodedUser = decodeComponent(userInfo)

        when (proto) {
            Proto.VLESS -> {
                cfg.uuid = decodedUser
                cfg.method = q["encryption"].orEmpty().ifBlank { "none" }
                cfg.flow = q["flow"].orEmpty()
            }

            Proto.TROJAN -> {
                cfg.password = decodedUser
                cfg.flow = q["flow"].orEmpty()
            }

            Proto.HYSTERIA2 -> {
                cfg.password = decodedUser
                cfg.obfsType = q["obfs"].orEmpty()
                cfg.obfsPassword = q["obfs-password"].orEmpty()
            }

            Proto.TUIC -> {
                // tuic://uuid:password@host:port
                cfg.uuid = decodedUser.substringBefore(":")
                cfg.password = if (decodedUser.contains(":")) decodedUser.substringAfter(":") else ""
            }

            Proto.ANYTLS -> {
                cfg.password = decodedUser
            }

            Proto.SOCKS, Proto.HTTP -> {
                cfg.uuid = decodedUser.substringBefore(":")
                cfg.password = if (decodedUser.contains(":")) decodedUser.substringAfter(":") else ""
            }

            else -> Unit
        }

        cfg.network = q["type"].orEmpty().ifBlank { "tcp" }
        cfg.headerType = q["headerType"].orEmpty().ifBlank { "none" }
        cfg.host = q["host"].orEmpty().ifBlank { q["sni"].orEmpty() }
        cfg.path = q["path"].orEmpty()
        cfg.serviceName = q["serviceName"].orEmpty().ifBlank { q["path"].orEmpty() }
        cfg.authority = q["authority"].orEmpty()
        cfg.mode = q["mode"].orEmpty()
        cfg.security = q["security"].orEmpty().let {
            if (proto == Proto.HYSTERIA2 || proto == Proto.TUIC || proto == Proto.ANYTLS) "tls"
            else if (it == "tls" || it == "reality") it else ""
        }
        cfg.sni = q["sni"].orEmpty().ifBlank { q["peer"].orEmpty() }
        cfg.alpn = q["alpn"].orEmpty()
        cfg.fingerprint = q["fp"].orEmpty()
        cfg.publicKey = q["pbk"].orEmpty()
        cfg.shortId = q["sid"].orEmpty()
        cfg.spiderX = q["spx"].orEmpty()
        cfg.allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1" || q["allow_insecure"] == "1"
        cfg.name = decodeComponent(fragment).ifBlank { "${host}:$port" }
        normalize(cfg)
        return cfg
    }

    // ---------------------------------------------------------------- wireguard

    /**
     * پشتیبانی از لینک‌های WireGuard:
     *   wireguard://<privateKey>@<host>:<port>/?publickey=<peer>&address=172.16.0.2/32&reserved=0,0,0&mtu=1420#name
     * و قالب ساده‌ی `wg://<privateKey>@<host>:<port>?publickey=...`
     */
    private fun parseWireGuard(line: String): ServerConfig? {
        val withoutScheme = line.substringAfter("://")
        val fragment = withoutScheme.substringAfter("#", "")
        val main = withoutScheme.substringBefore("#")
        val queryStr = main.substringAfter("?", "")
        val beforeQuery = main.substringBefore("?")

        val userInfo = beforeQuery.substringBeforeLast("@", "")
        val hostPort = beforeQuery.substringAfterLast("@", "")
        if (hostPort.isBlank() || userInfo.isBlank()) return null

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull() ?: 51820
        } else {
            val idx = hostPort.lastIndexOf(':')
            host = if (idx < 0) hostPort else hostPort.substring(0, idx)
            port = if (idx < 0) 51820 else (hostPort.substring(idx + 1).toIntOrNull() ?: 51820)
        }
        if (host.isBlank()) return null

        val q = parseQuery(queryStr)
        val cfg = ServerConfig()
        cfg.proto = Proto.WIREGUARD
        cfg.address = host
        cfg.port = port
        cfg.wgPrivateKey = decodeComponent(userInfo)
        cfg.wgPublicKey = (q["publickey"] ?: q["public_key"] ?: q["peer"]).orEmpty()
        cfg.wgAddress = (q["address"] ?: q["ip"]).orEmpty().ifBlank { "172.16.0.2/32" }
        cfg.wgReserved = (q["reserved"] ?: "").orEmpty()
        cfg.name = decodeComponent(fragment).ifBlank { "WG $host:$port" }
        return cfg
    }

    // ------------------------------------------------------------ shadowsocks

    private fun parseShadowsocks(line: String): ServerConfig? {
        var body = line.substringAfter("://")
        val fragment = body.substringAfter("#", "")
        body = body.substringBefore("#")
        val query = body.substringAfter("?", "")
        body = body.substringBefore("?")

        var method = ""
        var password = ""
        var host: String
        var port: Int

        if (body.contains("@")) {
            // ss://base64(method:password)@host:port  یا  ss://method:password@host:port
            val userInfo = body.substringBeforeLast("@")
            val hostPort = body.substringAfterLast("@")
            val decoded = if (userInfo.contains(":")) decodeComponent(userInfo) else {
                decodeBase64(userInfo) ?: decodeComponent(userInfo)
            }
            method = decoded.substringBefore(":", "")
            password = decoded.substringAfter(":", "")
            val idx = hostPort.lastIndexOf(':')
            if (idx < 0) return null
            host = hostPort.substring(0, idx)
            port = hostPort.substring(idx + 1).substringBefore("/").toIntOrNull() ?: 443
        } else {
            // ss://base64(method:password@host:port)
            val decoded = decodeBase64(body) ?: return null
            val at = decoded.lastIndexOf('@')
            if (at < 0) return null
            val cred = decoded.substring(0, at)
            val hostPort = decoded.substring(at + 1)
            method = cred.substringBefore(":", "")
            password = cred.substringAfter(":", "")
            val idx = hostPort.lastIndexOf(':')
            if (idx < 0) return null
            host = hostPort.substring(0, idx)
            port = hostPort.substring(idx + 1).toIntOrNull() ?: 443
        }

        if (host.isBlank() || method.isBlank()) return null
        val q = parseQuery(query)
        val cfg = ServerConfig()
        cfg.proto = Proto.SS
        cfg.address = host
        cfg.port = port
        cfg.method = method
        cfg.password = password
        cfg.network = q["type"].orEmpty().ifBlank { "tcp" }
        cfg.host = q["host"].orEmpty()
        cfg.path = q["path"].orEmpty()
        cfg.security = if (q["security"] == "tls") "tls" else ""
        cfg.sni = q["sni"].orEmpty()
        cfg.name = decodeComponent(fragment).ifBlank { "${host}:$port" }
        normalize(cfg)
        return cfg
    }

    // ------------------------------------------------------------------ utils

    private fun defaultPort(proto: Proto): Int = when (proto) {
        Proto.HTTP -> 80
        Proto.SOCKS -> 1080
        else -> 443
    }

    private fun normalize(cfg: ServerConfig) {
        if (cfg.proto == Proto.HYSTERIA2) {
            cfg.network = "hysteria"
            cfg.security = "tls"
            if (cfg.host.isBlank()) cfg.host = cfg.address
        }
        if (cfg.security.isBlank() && cfg.proto == Proto.TROJAN) cfg.security = "tls"
        if (cfg.security.isNotBlank() && cfg.sni.isBlank()) {
            cfg.sni = cfg.host.ifBlank { cfg.address }
        }
        if (cfg.network == "grpc" && cfg.serviceName.isBlank()) cfg.serviceName = cfg.path
        if (cfg.network == "ws" && cfg.path.isBlank()) cfg.path = "/"
        if (cfg.name.isBlank()) cfg.name = "${cfg.address}:${cfg.port}"
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val k = pair.substringBefore('=')
            val v = if (pair.contains('=')) pair.substringAfter('=') else ""
            map[decodeComponent(k)] = decodeComponent(v)
        }
        return map
    }

    fun decodeBase64(input: String): String? {
        val cleaned = input.trim().replace("\n", "").replace("\r", "")
        if (cleaned.isEmpty()) return null
        val variants = listOf(cleaned, cleaned.replace('-', '+').replace('_', '/'))
        for (variant in variants) {
            try {
                val bytes = Base64.decode(pad(variant), Base64.NO_WRAP)
                if (bytes.isEmpty()) continue
                val text = String(bytes, Charsets.UTF_8)
                if (text.isNotBlank() && !text.contains('\uFFFD')) return text
            } catch (_: Throwable) {
                // variant بعدی
            }
        }
        return null
    }

    private fun pad(s: String): String {
        val remainder = s.length % 4
        return if (remainder == 0) s else s + "=".repeat(4 - remainder)
    }

    fun decodeComponent(value: String): String {
        if (value.isEmpty()) return value
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (t: Throwable) {
            value
        }
    }

    /** ساخت لینک اشتراک از یک کانفیگ (برای اشتراک‌گذاری) */
    fun toLink(cfg: ServerConfig): String {
        return when (cfg.proto) {
            Proto.VMESS -> {
                val obj = JsonObject().apply {
                    addProperty("v", "2")
                    addProperty("ps", cfg.name)
                    addProperty("add", cfg.address)
                    addProperty("port", cfg.port.toString())
                    addProperty("id", cfg.uuid)
                    addProperty("aid", "0")
                    addProperty("scy", cfg.method.ifBlank { "auto" })
                    addProperty("net", cfg.network)
                    addProperty("type", cfg.headerType.ifBlank { "none" })
                    addProperty("host", cfg.host)
                    addProperty("path", cfg.path)
                    addProperty("tls", cfg.security)
                    addProperty("sni", cfg.sni)
                    addProperty("alpn", cfg.alpn)
                    addProperty("fp", cfg.fingerprint)
                }
                val encoded = Base64.encodeToString(obj.toString().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
                "vmess://$encoded"
            }

            Proto.VLESS, Proto.TROJAN, Proto.HYSTERIA2, Proto.SOCKS, Proto.HTTP -> {
                val scheme = when (cfg.proto) {
                    Proto.VLESS -> "vless"
                    Proto.TROJAN -> "trojan"
                    Proto.HYSTERIA2 -> "hysteria2"
                    Proto.SOCKS -> "socks"
                    else -> "http"
                }
                val user = when (cfg.proto) {
                    Proto.VLESS -> cfg.uuid
                    Proto.TROJAN, Proto.HYSTERIA2 -> cfg.password
                    Proto.SOCKS, Proto.HTTP -> if (cfg.uuid.isBlank()) "" else "${cfg.uuid}:${cfg.password}"
                    else -> ""
                }
                val q = mutableListOf<String>()
                if (cfg.proto == Proto.VLESS) q.add("encryption=${cfg.method.ifBlank { "none" }}")
                if (cfg.proto != Proto.HYSTERIA2) {
                    q.add("type=${cfg.network}")
                    if (cfg.security.isNotBlank()) q.add("security=${cfg.security}")
                    if (cfg.sni.isNotBlank()) q.add("sni=${cfg.sni}")
                    if (cfg.host.isNotBlank()) q.add("host=${cfg.host}")
                    if (cfg.path.isNotBlank()) q.add("path=${cfg.path}")
                    if (cfg.serviceName.isNotBlank() && cfg.network == "grpc") q.add("serviceName=${cfg.serviceName}")
                    if (cfg.fingerprint.isNotBlank()) q.add("fp=${cfg.fingerprint}")
                    if (cfg.publicKey.isNotBlank()) q.add("pbk=${cfg.publicKey}")
                    if (cfg.shortId.isNotBlank()) q.add("sid=${cfg.shortId}")
                    if (cfg.flow.isNotBlank()) q.add("flow=${cfg.flow}")
                    if (cfg.alpn.isNotBlank()) q.add("alpn=${cfg.alpn}")
                } else {
                    if (cfg.sni.isNotBlank()) q.add("sni=${cfg.sni}")
                    if (cfg.obfsPassword.isNotBlank()) {
                        q.add("obfs=salamander")
                        q.add("obfs-password=${cfg.obfsPassword}")
                    }
                }
                val query = if (q.isEmpty()) "" else "?" + q.joinToString("&") { it.replace(" ", "%20") }
                val hostPart = if (cfg.address.contains(":")) "[${cfg.address}]" else cfg.address
                "$scheme://$user@$hostPart:${cfg.port}$query#${cfg.name.replace(" ", "%20")}"
            }

            Proto.SS -> {
                val cred = Base64.encodeToString(
                    "${cfg.method}:${cfg.password}".toByteArray(),
                    Base64.NO_WRAP or Base64.URL_SAFE
                )
                "ss://$cred@${cfg.address}:${cfg.port}#${cfg.name.replace(" ", "%20")}"
            }
        }
    }
}
