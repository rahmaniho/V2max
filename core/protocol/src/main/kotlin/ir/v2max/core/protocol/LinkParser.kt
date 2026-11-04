package ir.v2max.core.protocol

import ir.v2max.core.model.Credential
import ir.v2max.core.model.Endpoint
import ir.v2max.core.model.Obfuscation
import ir.v2max.core.model.Proto
import ir.v2max.core.model.Security
import ir.v2max.core.model.Server
import ir.v2max.core.model.Transport
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Outcome of parsing a single line.
 *
 * The previous parser returned `null` for every failure, so a subscription of
 * 50 links where 5 were unsupported told the user nothing about *why*. Carrying
 * a reason here is what lets the UI say "3 links used an unsupported plugin"
 * instead of silently dropping them.
 */
sealed interface ParseResult {
    data class Ok(val server: Server) : ParseResult

    data class Skipped(val line: String, val reason: Reason) : ParseResult

    enum class Reason {
        /** Blank line, or a `#` / `//` comment. */
        COMMENT,

        /** No `scheme://` prefix, or an unrecognised scheme. */
        UNKNOWN_SCHEME,

        /** Payload could not be decoded (bad base64 or malformed JSON). */
        MALFORMED,

        /** Missing an address, port or credential the protocol requires. */
        INCOMPLETE,

        /**
         * A feature the core cannot honour — notably a Shadowsocks plugin other
         * than simple-obfs HTTP. Refusing loudly is deliberate: accepting it
         * would produce a server that looks fine and never connects.
         */
        UNSUPPORTED,

        /** Parser threw; kept as a distinct case so it is never mistaken for a skip. */
        ERROR,
    }
}

/**
 * Parses share links into [Server] values.
 *
 * Supports VLESS, VMess, Trojan, Shadowsocks, SOCKS/HTTP and Hysteria2, with
 * the TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP and KCP transports and the TLS /
 * REALITY parameter sets, matching the previous implementation's coverage.
 */
object LinkParser {

    /** Values a share link may use to mean "skip certificate verification". */
    private val INSECURE_TRUTHY = setOf("1", "true", "yes")

    /**
     * Every key seen in the wild for the insecure flag. Different clients spell
     * it differently, so all of them are honoured rather than only the canonical
     * one — an unrecognised spelling would silently upgrade the link to a
     * verified TLS connection that then fails.
     */
    private val INSECURE_KEYS = listOf("allowInsecure", "insecure", "allow_insecure", "insecure_skip_verify")

    /**
     * Transports removed from the current Xray core.
     *
     * Links using them are reported as unsupported rather than coerced onto TCP,
     * so the user sees "unsupported transport" instead of a server that connects
     * in the list and fails in practice.
     */
    private val REMOVED_TRANSPORTS = setOf("h2", "http", "quic")

    /** Parses a multi-line subscription body. */
    fun parseAll(text: String): List<ParseResult> {
        val body = maybeBase64(text)
        return body.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseLine(it) }
    }

    /** The servers only — the common case for callers that don't surface skips. */
    fun parseServers(text: String): List<Server> =
        parseAll(text).mapNotNull { (it as? ParseResult.Ok)?.server }

    /** Decodes the payload if the whole body is base64, as subscription URLs return. */
    fun maybeBase64(text: String): String {
        val trimmed = text.trim()
        if (trimmed.contains("://")) return trimmed
        val decoded = decodeBase64(trimmed) ?: return trimmed
        return if (decoded.contains("://")) decoded else trimmed
    }

    fun parseLine(rawLine: String): ParseResult {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
            return ParseResult.Skipped(line, ParseResult.Reason.COMMENT)
        }
        val scheme = line.substringBefore("://", "")
        if (scheme.isEmpty() || !line.contains("://")) {
            return ParseResult.Skipped(line, ParseResult.Reason.UNKNOWN_SCHEME)
        }
        val proto = Proto.fromScheme(scheme)
            ?: return ParseResult.Skipped(line, ParseResult.Reason.UNKNOWN_SCHEME)

        return try {
            when (proto) {
                // A VMess link is either base64(JSON) or, in newer shares, a
                // standard `user@host:port?...` form. Disambiguate on the '@'.
                Proto.VMESS ->
                    if (line.substringAfter("://").substringBefore("?").contains('@')) {
                        parseStandard(line, Proto.VMESS)
                    } else {
                        parseVmessJson(line)
                    }

                Proto.VLESS, Proto.TROJAN, Proto.SOCKS, Proto.HTTP, Proto.HYSTERIA2 ->
                    parseStandard(line, proto)

                Proto.SS -> parseShadowsocks(line)
            }
        } catch (_: Throwable) {
            ParseResult.Skipped(line, ParseResult.Reason.ERROR)
        }
    }

    // ------------------------------------------------------------------ VMess

    private fun parseVmessJson(line: String): ParseResult {
        val payload = line.substringAfter("://").substringBefore("#")
        val json = decodeBase64(payload)
            ?: return ParseResult.Skipped(line, ParseResult.Reason.MALFORMED)
        val obj = Json.parse(json)?.asObject()
            ?: return ParseResult.Skipped(line, ParseResult.Reason.MALFORMED)

        fun str(vararg keys: String): String {
            for (k in keys) {
                val v = obj[k]?.asString()
                if (v != null) return v
            }
            return ""
        }

        val address = str("add", "address")
        val id = str("id")
        if (address.isBlank() || id.isBlank()) {
            return ParseResult.Skipped(line, ParseResult.Reason.INCOMPLETE)
        }
        val port = str("port").toIntOrNull() ?: Proto.VMESS.defaultPort
        val network = str("net", "network").ifBlank { "tcp" }.lowercase()

        // The same rule parseStandard applies. Without it a base64 VMess link
        // carrying net=quic fell through transportFrom's TCP fallback and came
        // out as a TCP server: listed, pingable, and unable to carry traffic.
        if (network in REMOVED_TRANSPORTS) {
            return ParseResult.Skipped(line, ParseResult.Reason.UNSUPPORTED)
        }

        val securityRaw = str("tls")

        val transport = transportFrom(
            network = network,
            headerType = str("type", "headerType").ifBlank { "none" },
            host = str("host"),
            path = str("path"),
            serviceName = str("serviceName").ifBlank { str("path").takeIf { network == "grpc" }.orEmpty() },
            authority = str("authority").ifBlank { str("host").takeIf { network == "grpc" }.orEmpty() },
            mode = str("mode").ifBlank { str("type").takeIf { network == "grpc" }.orEmpty() },
            extra = "",
            kcpSeed = str("path").takeIf { network == "kcp" || network == "mkcp" }.orEmpty(),
            kcpMtu = 1350,
            kcpTti = 50,
        )

        val security = securityFrom(
            raw = if (securityRaw == "tls" || securityRaw == "reality") securityRaw else "",
            sni = str("sni"),
            alpn = str("alpn"),
            fingerprint = str("fp"),
            publicKey = str("pbk"),
            shortId = str("sid"),
            spiderX = str("spx"),
            ech = "",
            vcn = str("vcn"),
            pcs = str("pcs"),
            pqv = "",
            allowInsecure = str("insecure", "allowInsecure").lowercase() in INSECURE_TRUTHY,
            address = address,
            transport = transport,
        )

        val server = Server(
            name = decodeComponent(str("ps", "remarks")).ifBlank { "$address:$port" },
            proto = Proto.VMESS,
            endpoint = Endpoint(address, port),
            credential = Credential.Uuid(id, str("scy", "security").ifBlank { "auto" }),
            transport = transport,
            security = security,
        )
        return ParseResult.Ok(normalize(server))
    }

    // --------------------------------------------- VLESS / Trojan / Socks / ...

    private fun parseStandard(line: String, proto: Proto): ParseResult {
        val withoutScheme = line.substringAfter("://")
        val fragment = withoutScheme.substringAfter("#", "")
        val main = withoutScheme.substringBefore("#")
        val queryStr = main.substringAfter("?", "")
        val beforeQuery = main.substringBefore("?")

        val userInfo = beforeQuery.substringBeforeLast("@", "")
        val hostPort = beforeQuery.substringAfterLast("@", beforeQuery).substringBefore("/")
        if (hostPort.isBlank()) return ParseResult.Skipped(line, ParseResult.Reason.INCOMPLETE)

        val endpoint = Endpoint.parse(hostPort, proto.defaultPort)
            ?: return ParseResult.Skipped(line, ParseResult.Reason.INCOMPLETE)
        if (endpoint.address.isBlank()) {
            return ParseResult.Skipped(line, ParseResult.Reason.INCOMPLETE)
        }

        val q = parseQuery(queryStr)

        // Split user:pass before percent-decoding so a password containing ':'
        // survives intact.
        val rawUser = userInfo.substringBefore(":")
        val rawPass = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""
        val decodedUser = decodeComponent(userInfo)
        val decodedId = decodeComponent(rawUser)
        val decodedPass = decodeComponent(rawPass)

        val flow = q["flow"].orEmpty()
        val credential: Credential = when (proto) {
            Proto.VMESS -> Credential.Uuid(decodedUser, q["scy"].orEmpty().ifBlank { "auto" })
            Proto.VLESS -> Credential.Uuid(decodedUser, q["encryption"].orEmpty().ifBlank { "none" }, flow)
            Proto.TROJAN -> Credential.Password(decodedUser, flow)
            Proto.HYSTERIA2 -> Credential.Password(decodedUser)
            Proto.SOCKS, Proto.HTTP -> Credential.UserPass(decodedId, decodedPass)
            Proto.SS -> Credential.Cipher("", "")
        }

        val network = q["type"].orEmpty().ifBlank { "tcp" }.lowercase()

        // Legacy HTTP/2 and QUIC were removed from the Xray core this app ships.
        // There is no Transport variant for them, so mapping them onto TCP would
        // produce a server that looks fine and never connects — the same silent
        // failure the Shadowsocks plugin check guards against. Report instead.
        if (network in REMOVED_TRANSPORTS) {
            return ParseResult.Skipped(line, ParseResult.Reason.UNSUPPORTED)
        }

        val transport = transportFrom(
            network = network,
            headerType = q["headerType"].orEmpty().ifBlank { "none" },
            host = q["host"].orEmpty(),
            path = q["path"].orEmpty(),
            serviceName = q["serviceName"].orEmpty().ifBlank { q["path"].orEmpty() },
            authority = q["authority"].orEmpty(),
            mode = q["mode"].orEmpty(),
            extra = q["extra"].orEmpty(),
            kcpSeed = q["seed"].orEmpty(),
            kcpMtu = q["mtu"]?.toIntOrNull() ?: 1350,
            kcpTti = q["tti"]?.toIntOrNull() ?: 50,
        )

        val securityRaw = when {
            proto == Proto.HYSTERIA2 -> "tls"
            q["security"] == "none" -> "none"
            q["security"] == "tls" || q["security"] == "reality" -> q["security"].orEmpty()
            proto == Proto.TROJAN -> "tls"
            else -> ""
        }

        val security = securityFrom(
            raw = securityRaw,
            sni = q["sni"].orEmpty().ifBlank { q["peer"].orEmpty() },
            alpn = q["alpn"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            ech = q["ech"].orEmpty(),
            vcn = q["vcn"].orEmpty(),
            pcs = q["pcs"].orEmpty().ifBlank { q["pinSHA256"].orEmpty() },
            pqv = q["pqv"].orEmpty(),
            allowInsecure = INSECURE_KEYS.any { key -> q[key]?.lowercase() in INSECURE_TRUTHY },
            address = endpoint.address,
            transport = transport,
        )

        val obfuscation = if (proto == Proto.HYSTERIA2) {
            Obfuscation(q["obfs"].orEmpty(), q["obfs-password"].orEmpty())
        } else {
            Obfuscation()
        }

        val server = Server(
            name = decodeComponent(fragment).ifBlank { endpoint.display },
            proto = proto,
            endpoint = endpoint,
            credential = credential,
            transport = transport,
            security = security,
            obfuscation = obfuscation,
            finalMask = q["fm"].orEmpty(),
        )
        return ParseResult.Ok(normalize(server))
    }

    // ------------------------------------------------------------ Shadowsocks

    private fun parseShadowsocks(line: String): ParseResult {
        var body = line.substringAfter("://")
        val fragment = body.substringAfter("#", "")
        body = body.substringBefore("#")
        val query = body.substringAfter("?", "")
        body = body.substringBefore("?")

        var method = ""
        var password = ""
        val address: String
        val port: Int

        if (body.contains("@")) {
            // ss://base64(method:password)@host:port  |  ss://method:password@host:port
            val userInfo = body.substringBeforeLast("@")
            val hostPort = body.substringAfterLast("@")
            val decodedInfo = decodeComponent(userInfo)
            val decoded = if (decodedInfo.contains(":")) decodedInfo else {
                decodeBase64(decodedInfo) ?: decodedInfo
            }
            method = decoded.substringBefore(":", "")
            password = decoded.substringAfter(":", "")
            val ep = Endpoint.parse(hostPort, Proto.SS.defaultPort)
                ?: return ParseResult.Skipped(line, ParseResult.Reason.INCOMPLETE)
            address = ep.address
            port = ep.port
        } else {
            // ss://base64(method:password@host:port)
            val decoded = decodeBase64(body)
                ?: return ParseResult.Skipped(line, ParseResult.Reason.MALFORMED)
            val at = decoded.lastIndexOf('@')
            if (at < 0) return ParseResult.Skipped(line, ParseResult.Reason.INCOMPLETE)
            method = decoded.substring(0, at).substringBefore(":", "")
            password = decoded.substring(0, at).substringAfter(":", "")
            val ep = Endpoint.parse(decoded.substring(at + 1), Proto.SS.defaultPort)
                ?: return ParseResult.Skipped(line, ParseResult.Reason.INCOMPLETE)
            address = ep.address
            port = ep.port
        }

        if (address.isBlank() || method.isBlank()) {
            return ParseResult.Skipped(line, ParseResult.Reason.INCOMPLETE)
        }

        val q = parseQuery(query)
        var transport: Transport = transportFrom(
            network = q["type"].orEmpty().ifBlank { "tcp" }.lowercase(),
            headerType = "none",
            host = q["host"].orEmpty(),
            path = q["path"].orEmpty(),
            serviceName = "", authority = "", mode = "",
            extra = "", kcpSeed = "", kcpMtu = 1350, kcpTti = 50,
        )

        // v2rayNG maps simple-obfs HTTP onto Xray's TCP HTTP header. Any other
        // plugin is reported rather than dropped silently.
        val plugin = q["plugin"].orEmpty()
        if (plugin.isNotBlank()) {
            val options = plugin.split(';').drop(1).associate {
                it.substringBefore('=') to it.substringAfter('=', "")
            }
            if (options["obfs"] != "http") {
                return ParseResult.Skipped(line, ParseResult.Reason.UNSUPPORTED)
            }
            transport = Transport.Tcp(
                headerType = "http",
                host = options["obfs-host"].orEmpty(),
                path = options["path"].orEmpty(),
            )
        }

        val security = if (q["security"] == "tls") {
            Security.Tls(sni = q["sni"].orEmpty())
        } else {
            Security.None
        }

        val server = Server(
            name = decodeComponent(fragment).ifBlank { "$address:$port" },
            proto = Proto.SS,
            endpoint = Endpoint(address, port),
            credential = Credential.Cipher(method.lowercase(), password),
            transport = transport,
            security = security,
        )
        return ParseResult.Ok(normalize(server))
    }

    // ---------------------------------------------------------------- helpers

    private fun transportFrom(
        network: String,
        headerType: String,
        host: String,
        path: String,
        serviceName: String,
        authority: String,
        mode: String,
        extra: String,
        kcpSeed: String,
        kcpMtu: Int,
        kcpTti: Int,
    ): Transport = when (network) {
        "ws" -> Transport.WebSocket(host, path.ifBlank { "/" })
        "grpc" -> Transport.Grpc(serviceName, authority, mode)
        "httpupgrade" -> Transport.HttpUpgrade(host, path)
        "xhttp", "splithttp" -> Transport.XHttp(host, path, mode, extra)
        "kcp", "mkcp" -> Transport.Kcp(headerType, kcpSeed, kcpMtu, kcpTti)
        "hysteria" -> Transport.Hysteria(host)
        else -> Transport.Tcp(headerType, host, path)
    }

    private fun securityFrom(
        raw: String,
        sni: String,
        alpn: String,
        fingerprint: String,
        publicKey: String,
        shortId: String,
        spiderX: String,
        ech: String,
        vcn: String,
        pcs: String,
        pqv: String,
        allowInsecure: Boolean,
        address: String,
        transport: Transport,
    ): Security = when (raw) {
        "reality" -> Security.Reality(
            sni = sni.ifBlank { defaultSni(address, transport) },
            fingerprint = fingerprint,
            publicKey = publicKey,
            shortId = shortId,
            spiderX = spiderX,
        )

        "tls" -> Security.Tls(
            sni = sni.ifBlank { defaultSni(address, transport) },
            alpn = alpn,
            fingerprint = fingerprint,
            echConfigList = ech,
            verifyPeerCertByName = vcn,
            pinnedPeerCertSha256 = pcs,
            mldsa65Verify = pqv,
            allowInsecure = allowInsecure,
        )

        else -> Security.None
    }

    private fun defaultSni(address: String, transport: Transport): String = when (transport) {
        is Transport.Grpc -> transport.authority.ifBlank { transport.serviceName.ifBlank { address } }
        is Transport.Tcp -> transport.host.substringBefore(',').trim().ifBlank { address }
        is Transport.WebSocket -> transport.host.substringBefore(',').trim().ifBlank { address }
        is Transport.HttpUpgrade -> transport.host.substringBefore(',').trim().ifBlank { address }
        is Transport.XHttp -> transport.host.substringBefore(',').trim().ifBlank { address }
        is Transport.Hysteria -> transport.host.ifBlank { address }
        is Transport.Kcp -> address
    }

    /**
     * Applies the protocol defaults the core expects.
     *
     * Same rules as before: Hysteria2 is always TLS over its own transport,
     * Trojan defaults to TLS, a secured link with no SNI inherits one, and gRPC
     * picks up its service name from `path`.
     */
    private fun normalize(server: Server): Server {
        var transport = server.transport
        var security = server.security

        if (server.proto == Proto.HYSTERIA2) {
            if (transport !is Transport.Hysteria) {
                transport = Transport.Hysteria(
                    (transport as? Transport.Tcp)?.host.orEmpty().ifBlank { server.endpoint.address }
                )
            } else if (transport.host.isBlank()) {
                transport = Transport.Hysteria(server.endpoint.address)
            }
            security = Security.Tls(sni = (security as? Security.Tls)?.sni?.ifBlank { server.endpoint.address }
                ?: server.endpoint.address)
        }

        if (security is Security.None && server.proto == Proto.TROJAN) {
            security = Security.Tls(sni = server.endpoint.address)
        }

        if (transport is Transport.Grpc && transport.serviceName.isBlank()) {
            transport = transport.copy(serviceName = transport.authority)
        }
        if (transport is Transport.WebSocket && transport.path.isBlank()) {
            transport = transport.copy(path = "/")
        }

        return server.copy(
            transport = transport,
            security = security,
            name = server.name.ifBlank { server.endpoint.display },
        )
    }

    fun parseQuery(query: String): Map<String, String> {
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
                // try the next variant
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
        } catch (_: Throwable) {
            value
        }
    }

    fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------- serialising

    /** Rebuilds a share link from a [Server], for the share sheet and QR code. */
    fun toLink(server: Server): String = when (server.proto) {
        Proto.VMESS -> vmessLink(server)
        Proto.SS -> ssLink(server)
        else -> standardLink(server)
    }

    private fun vmessLink(server: Server): String {
        val t = server.transport
        val members = LinkedHashMap<String, String>()
        members["v"] = "2"
        members["ps"] = server.displayName
        members["add"] = server.endpoint.address
        members["port"] = server.endpoint.port.toString()
        members["id"] = (server.credential as? Credential.Uuid)?.uuid.orEmpty()
        members["aid"] = "0"
        members["scy"] = (server.credential as? Credential.Uuid)?.method?.ifBlank { "auto" } ?: "auto"
        members["net"] = t.wire
        members["type"] = when (t) {
            is Transport.Grpc -> t.mode
            is Transport.Tcp -> t.headerType.ifBlank { "none" }
            is Transport.Kcp -> t.headerType.ifBlank { "none" }
            else -> "none"
        }
        members["host"] = when (t) {
            is Transport.Grpc -> t.authority
            is Transport.Tcp -> t.host
            is Transport.WebSocket -> t.host
            is Transport.HttpUpgrade -> t.host
            is Transport.XHttp -> t.host
            else -> ""
        }
        members["path"] = when (t) {
            is Transport.Grpc -> t.serviceName
            is Transport.Kcp -> t.seed
            is Transport.WebSocket -> t.path
            is Transport.HttpUpgrade -> t.path
            is Transport.XHttp -> t.path
            else -> ""
        }
        members["tls"] = when (server.security) {
            Security.None -> ""
            is Security.Tls -> "tls"
            is Security.Reality -> "reality"
        }
        members["sni"] = server.effectiveSni
        members["alpn"] = (server.security as? Security.Tls)?.alpn.orEmpty()
        members["fp"] = when (val s = server.security) {
            is Security.Tls -> s.fingerprint
            is Security.Reality -> s.fingerprint
            else -> ""
        }
        members["insecure"] = "0"
        members["vcn"] = (server.security as? Security.Tls)?.verifyPeerCertByName.orEmpty()
        members["pcs"] = (server.security as? Security.Tls)?.pinnedPeerCertSha256.orEmpty()

        val json = members.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${Json.quote(v)}"
        }
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        return "vmess://$encoded"
    }

    private fun ssLink(server: Server): String {
        val cipher = server.credential as? Credential.Cipher ?: Credential.Cipher("", "")
        val cred = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${cipher.method}:${cipher.password}".toByteArray(Charsets.UTF_8))
        val t = server.transport
        val plugin = if (t is Transport.Tcp && t.headerType == "http") {
            "?plugin=${encodeComponent("obfs-local;obfs=http;obfs-host=${t.host};path=${t.path}")}"
        } else {
            ""
        }
        return "ss://$cred@${server.endpoint.hostPort}$plugin#${encodeComponent(server.displayName)}"
    }

    private fun standardLink(server: Server): String {
        val scheme = when (server.proto) {
            Proto.VLESS -> "vless"
            Proto.TROJAN -> "trojan"
            Proto.HYSTERIA2 -> "hysteria2"
            Proto.SOCKS -> "socks"
            else -> "http"
        }

        val user = when (val c = server.credential) {
            is Credential.Uuid -> c.uuid
            is Credential.Password -> c.password
            is Credential.UserPass -> if (c.user.isBlank()) "" else "${c.user}:${c.password}"
            is Credential.Cipher -> ""
        }

        val params = LinkedHashMap<String, String>()
        fun param(key: String, value: String) {
            if (value.isNotBlank()) params[key] = value
        }

        val t = server.transport
        if (server.proto == Proto.VLESS) {
            param("encryption", (server.credential as? Credential.Uuid)?.method?.ifBlank { "none" } ?: "none")
        }

        if (server.proto != Proto.HYSTERIA2) {
            param("type", t.wire)
            param("security", when (server.security) {
                Security.None -> ""
                is Security.Tls -> "tls"
                is Security.Reality -> "reality"
            })
            when (t) {
                is Transport.Tcp -> {
                    param("host", t.host)
                    param("path", t.path)
                    param("headerType", t.headerType.takeUnless { it == "none" }.orEmpty())
                }
                is Transport.WebSocket -> { param("host", t.host); param("path", t.path) }
                is Transport.HttpUpgrade -> { param("host", t.host); param("path", t.path) }
                is Transport.XHttp -> {
                    param("host", t.host); param("path", t.path); param("mode", t.mode)
                    param("extra", t.extra)
                }
                is Transport.Grpc -> {
                    param("serviceName", t.serviceName); param("authority", t.authority); param("mode", t.mode)
                }
                is Transport.Kcp -> {
                    param("headerType", t.headerType.takeUnless { it == "none" }.orEmpty())
                    param("seed", t.seed); param("mtu", t.mtu.toString()); param("tti", t.tti.toString())
                }
                else -> Unit
            }
            // finalmask is stream-level, so it is emitted for every transport.
            param("fm", server.finalMask)
            when (val s = server.security) {
                is Security.Tls -> {
                    param("alpn", s.alpn); param("ech", s.echConfigList)
                    param("vcn", s.verifyPeerCertByName); param("pcs", s.pinnedPeerCertSha256)
                    param("pqv", s.mldsa65Verify); param("fp", s.fingerprint)
                }
                is Security.Reality -> {
                    param("fp", s.fingerprint); param("pbk", s.publicKey)
                    param("sid", s.shortId); param("spx", s.spiderX)
                }
                Security.None -> Unit
            }
            // XTLS flow lives on the credential and only applies to VLESS/Trojan.
            param("flow", when (val c = server.credential) {
                is Credential.Uuid -> c.flow
                is Credential.Password -> c.flow
                else -> ""
            })
        } else if (server.obfuscation.enabled) {
            param("obfs", "salamander")
            param("obfs-password", server.obfuscation.password)
        }

        // SNI is meaningless without a TLS layer; emitting it for a plain Socks
        // proxy would only add noise to the shared link.
        if (server.security !is Security.None) param("sni", server.effectiveSni)

        val query = if (params.isEmpty()) "" else {
            "?" + params.entries.joinToString("&") { (k, v) -> "$k=${encodeComponent(v)}" }
        }
        val encodedUser = if (server.credential is Credential.UserPass) {
            val c = server.credential as Credential.UserPass
            if (c.user.isBlank()) "" else "${encodeComponent(c.user)}:${encodeComponent(c.password)}"
        } else {
            encodeComponent(user)
        }
        return "$scheme://$encodedUser@${server.endpoint.hostPort}$query#${encodeComponent(server.displayName)}"
    }
}
