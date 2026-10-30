package ir.v2max.app.net

import java.util.Base64
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
                "vmess" -> if (line.substringAfter("://").substringBefore("?").contains('@')) {
                    parseStandard(line, Proto.VMESS)
                } else parseVmess(line)
                "vless" -> parseStandard(line, Proto.VLESS)
                "trojan" -> parseStandard(line, Proto.TROJAN)
                "ss" -> parseShadowsocks(line)
                "socks", "socks5" -> parseStandard(line, Proto.SOCKS)
                "http", "https" -> parseStandard(line, Proto.HTTP)
                "hysteria2", "hy2" -> parseStandard(line, Proto.HYSTERIA2)
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
        cfg.serviceName = str("serviceName").ifBlank { str("path").takeIf { cfg.network == "grpc" }.orEmpty() }
        cfg.authority = str("authority").ifBlank { str("host").takeIf { cfg.network == "grpc" }.orEmpty() }
        cfg.mode = str("mode").ifBlank { str("type").takeIf { cfg.network == "grpc" }.orEmpty() }
        cfg.kcpSeed = str("path").takeIf { cfg.network in listOf("kcp", "mkcp") }.orEmpty()
        cfg.security = str("tls").let { if (it == "tls" || it == "reality") it else "" }
        cfg.sni = str("sni")
        cfg.alpn = str("alpn")
        cfg.fingerprint = str("fp")
        cfg.publicKey = str("pbk")
        cfg.shortId = str("sid")
        cfg.spiderX = str("spx")
        cfg.allowInsecure = str("insecure", "allowInsecure").lowercase() in listOf("1", "true", "yes")
        cfg.verifyPeerCertByName = str("vcn")
        cfg.pinnedPeerCertSha256 = str("pcs")
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
        // بعضی لینک‌ها مسیر «/» بعد از پورت دارند (anytls://pass@host:port/?...) و باید حذف شود
        val hostPort = beforeQuery.substringAfterLast("@", beforeQuery).substringBefore("/")
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

        // ابتدا جدا کردن شناسه از رمز و سپس درصدگشایی تا رمزهای حاوی «:» خراب نشوند
        val rawUser = userInfo.substringBefore(":")
        val rawPass = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""
        val decodedUser = decodeComponent(userInfo)
        val decodedUserId = decodeComponent(rawUser)
        val decodedPassword = decodeComponent(rawPass)

        when (proto) {
            Proto.VMESS -> {
                cfg.uuid = decodedUser
                cfg.method = q["scy"].orEmpty().ifBlank { "auto" }
            }

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

            Proto.SOCKS, Proto.HTTP -> {
                cfg.uuid = decodedUserId
                cfg.password = decodedPassword
            }

            else -> Unit
        }

        cfg.network = q["type"].orEmpty().ifBlank { "tcp" }.lowercase()
        cfg.headerType = q["headerType"].orEmpty().ifBlank { "none" }
        cfg.host = q["host"].orEmpty()
        cfg.path = q["path"].orEmpty()
        cfg.serviceName = q["serviceName"].orEmpty().ifBlank { q["path"].orEmpty() }
        cfg.authority = q["authority"].orEmpty()
        cfg.mode = q["mode"].orEmpty()
        cfg.xhttpExtra = q["extra"].orEmpty()
        cfg.finalMask = q["fm"].orEmpty()
        cfg.kcpSeed = q["seed"].orEmpty()
        cfg.kcpMtu = q["mtu"]?.toIntOrNull() ?: 1350
        cfg.kcpTti = q["tti"]?.toIntOrNull() ?: 50
        cfg.security = when {
            proto == Proto.HYSTERIA2 -> "tls"
            q["security"] == "none" -> "none"
            q["security"] == "tls" || q["security"] == "reality" -> q["security"].orEmpty()
            proto == Proto.TROJAN -> "tls"
            else -> ""
        }
        cfg.sni = q["sni"].orEmpty().ifBlank { q["peer"].orEmpty() }
        cfg.alpn = q["alpn"].orEmpty()
        cfg.fingerprint = q["fp"].orEmpty()
        cfg.publicKey = q["pbk"].orEmpty()
        cfg.shortId = q["sid"].orEmpty()
        cfg.spiderX = q["spx"].orEmpty()
        cfg.echConfigList = q["ech"].orEmpty()
        cfg.verifyPeerCertByName = q["vcn"].orEmpty()
        cfg.pinnedPeerCertSha256 = q["pcs"].orEmpty().ifBlank { q["pinSHA256"].orEmpty() }
        cfg.mldsa65Verify = q["pqv"].orEmpty()
        cfg.allowInsecure = listOf("allowInsecure", "insecure", "allow_insecure", "insecure_skip_verify")
            .any { key -> q[key]?.lowercase() in listOf("1", "true", "yes") }
        cfg.name = decodeComponent(fragment).ifBlank { "${host}:$port" }
        normalize(cfg)
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
            val decodedInfo = decodeComponent(userInfo)
            val decoded = if (decodedInfo.contains(":")) decodedInfo else {
                decodeBase64(decodedInfo) ?: decodedInfo
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
        cfg.address = host.removeSurrounding("[", "]")
        cfg.port = port
        cfg.method = method.lowercase()
        cfg.password = password
        cfg.network = q["type"].orEmpty().ifBlank { "tcp" }
        cfg.host = q["host"].orEmpty()
        cfg.path = q["path"].orEmpty()
        cfg.security = if (q["security"] == "tls") "tls" else ""
        cfg.sni = q["sni"].orEmpty()
        // v2rayNG maps simple-obfs HTTP to the Xray TCP HTTP header.
        val plugin = q["plugin"].orEmpty()
        if (plugin.isNotBlank()) {
            val parts = plugin.split(';')
            val options = parts.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
            if (options["obfs"] != "http") return null // Do not silently discard unsupported plugins.
            cfg.network = "tcp"
            cfg.headerType = "http"
            cfg.host = options["obfs-host"].orEmpty()
            cfg.path = options["path"].orEmpty()
        }
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
        if (cfg.security.isNotBlank() && cfg.security != "none" && cfg.sni.isBlank()) {
            cfg.sni = cfg.effectiveSni()
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
                val bytes = Base64.getDecoder().decode(pad(variant))
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

    private fun encodeComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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
                    addProperty("type", if (cfg.network == "grpc") cfg.mode else cfg.headerType.ifBlank { "none" })
                    addProperty("host", if (cfg.network == "grpc") cfg.authority else cfg.host)
                    addProperty("path", when (cfg.network) {
                        "grpc" -> cfg.serviceName
                        "kcp", "mkcp" -> cfg.kcpSeed
                        else -> cfg.path
                    })
                    addProperty("tls", cfg.security)
                    addProperty("sni", cfg.sni)
                    addProperty("alpn", cfg.alpn)
                    addProperty("fp", cfg.fingerprint)
                    addProperty("insecure", if (cfg.allowInsecure) "1" else "0")
                    addProperty("vcn", cfg.verifyPeerCertByName)
                    addProperty("pcs", cfg.pinnedPeerCertSha256)
                }
                val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(obj.toString().toByteArray(Charsets.UTF_8))
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
                val q = mutableListOf<Pair<String, String>>()
                fun param(key: String, value: String) { if (value.isNotBlank()) q.add(key to value) }
                if (cfg.proto == Proto.VLESS) param("encryption", cfg.method.ifBlank { "none" })
                if (cfg.proto != Proto.HYSTERIA2) {
                    param("type", cfg.network)
                    param("security", cfg.security)
                    param("host", cfg.host)
                    param("path", cfg.path)
                    if (cfg.network == "grpc") param("serviceName", cfg.serviceName)
                    param("authority", cfg.authority)
                    param("mode", cfg.mode)
                    param("headerType", cfg.headerType.takeUnless { it == "none" }.orEmpty())
                    param("extra", cfg.xhttpExtra)
                    param("fm", cfg.finalMask)
                    if (cfg.network in listOf("kcp", "mkcp")) {
                        param("seed", cfg.kcpSeed)
                        param("mtu", cfg.kcpMtu.toString())
                        param("tti", cfg.kcpTti.toString())
                    }
                    param("fp", cfg.fingerprint)
                    param("pbk", cfg.publicKey)
                    param("sid", cfg.shortId)
                    param("spx", cfg.spiderX)
                    param("pqv", cfg.mldsa65Verify)
                    param("flow", cfg.flow)
                } else if (cfg.obfsPassword.isNotBlank()) {
                    param("obfs", "salamander")
                    param("obfs-password", cfg.obfsPassword)
                }
                param("sni", cfg.sni)
                param("alpn", cfg.alpn)
                param("ech", cfg.echConfigList)
                param("vcn", cfg.verifyPeerCertByName)
                param("pcs", cfg.pinnedPeerCertSha256)
                if (cfg.allowInsecure) param("insecure", "1")
                val query = if (q.isEmpty()) "" else "?" + q.joinToString("&") { (key, value) ->
                    "$key=${encodeComponent(value)}"
                }
                val hostPart = if (cfg.address.contains(":")) "[${cfg.address}]" else cfg.address
                val encodedUser = if (cfg.proto == Proto.SOCKS || cfg.proto == Proto.HTTP) {
                    if (cfg.uuid.isBlank()) "" else "${encodeComponent(cfg.uuid)}:${encodeComponent(cfg.password)}"
                } else encodeComponent(user)
                "$scheme://$encodedUser@$hostPart:${cfg.port}$query#${encodeComponent(cfg.name)}"
            }

            Proto.SS -> {
                val cred = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "${cfg.method}:${cfg.password}".toByteArray(Charsets.UTF_8)
                )
                val hostPart = if (cfg.address.contains(":")) "[${cfg.address}]" else cfg.address
                val plugin = if (cfg.headerType == "http") {
                    val value = "obfs-local;obfs=http;obfs-host=${cfg.host};path=${cfg.path}"
                    "?plugin=${encodeComponent(value)}"
                } else ""
                "ss://$cred@$hostPart:${cfg.port}$plugin#${encodeComponent(cfg.name)}"
            }
        }
    }
}
