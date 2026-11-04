package ir.v2max.app.data

import ir.v2max.core.model.Country
import ir.v2max.core.model.Credential
import ir.v2max.core.model.Endpoint
import ir.v2max.core.model.FragmentProfile as CoreFragmentProfile
import ir.v2max.core.model.Measurement
import ir.v2max.core.model.Obfuscation
import ir.v2max.core.model.Proto as CoreProto
import ir.v2max.core.model.Security
import ir.v2max.core.model.Server
import ir.v2max.core.model.Transport

/**
 * Bridges the app's persistence type onto core's domain type.
 *
 * The app still stores [ServerConfig] — flat, mutable, Gson-serialised — while
 * the tested logic lives against core's [Server]. This is the seam between them,
 * and it exists so the app can start using core's config generation without
 * rewriting persistence and every screen in the same step.
 *
 * The mapping is deliberately faithful rather than clever: each branch mirrors
 * what `ConfigBuilder` already reads out of these same fields, so a server that
 * connected before produces the same streamSettings after.
 *
 * **One thing cannot be carried.** `network` is free text in the config editor,
 * so a user can type `quic`, `h2` or `http`. Core has no [Transport] variant for
 * them because the Xray core this app ships removed them, and its link parser
 * reports such links as `UNSUPPORTED` rather than coercing them onto TCP — a
 * coerced server appears in the list, passes a TCP ping, and never carries
 * traffic. Mapping one here falls through to [Transport.Tcp] and would recreate
 * exactly that. Callers must reject those values before mapping, the same way
 * the parser does. See `ConfigGeneratorTests`.
 */
fun ServerConfig.toCoreServer(): Server = Server(
    id = id,
    name = name,
    proto = CoreProto.valueOf(proto.name),
    endpoint = Endpoint(address, port),
    // Which credential fields are meaningful depends on the protocol; these are
    // the same fields ConfigBuilder reads when it writes the outbound.
    credential = when (proto) {
        Proto.VLESS, Proto.VMESS -> Credential.Uuid(uuid, method, flow)
        Proto.TROJAN, Proto.HYSTERIA2 -> Credential.Password(password, flow)
        Proto.SS -> Credential.Cipher(method, password)
        // Socks and HTTP keep the username in `uuid`; see ConfigBuilder, which
        // writes it as "user" alongside password as "pass".
        Proto.SOCKS, Proto.HTTP -> Credential.UserPass(uuid, password)
    },
    transport = when (network.ifBlank { "tcp" }.lowercase()) {
        "ws" -> Transport.WebSocket(host, path.ifBlank { "/" })
        "grpc" -> Transport.Grpc(serviceName, authority, mode)
        "httpupgrade" -> Transport.HttpUpgrade(host, path)
        "xhttp", "splithttp" -> Transport.XHttp(host, path, mode, xhttpExtra)
        "kcp", "mkcp" -> Transport.Kcp(headerType.ifBlank { "none" }, kcpSeed, kcpMtu, kcpTti)
        "hysteria" -> Transport.Hysteria(host)
        else -> Transport.Tcp(headerType.ifBlank { "none" }, host, path)
    },
    // Blank security maps to None; XrayConfig then applies the same defaults
    // ConfigBuilder does (Hysteria2 is always TLS).
    security = when (security.lowercase()) {
        "reality" -> Security.Reality(
            sni = sni,
            fingerprint = fingerprint,
            publicKey = publicKey,
            shortId = shortId,
            spiderX = spiderX,
        )
        "tls" -> Security.Tls(
            sni = sni,
            alpn = alpn,
            fingerprint = fingerprint,
            echConfigList = echConfigList,
            verifyPeerCertByName = verifyPeerCertByName,
            pinnedPeerCertSha256 = pinnedPeerCertSha256,
            mldsa65Verify = mldsa65Verify,
            allowInsecure = allowInsecure,
        )
        else -> Security.None
    },
    obfuscation = Obfuscation(obfsType, obfsPassword),
    finalMask = finalMask,
    subscriptionId = subId,
    favorite = favorite,
    addedAt = addedAt,
    measurement = Measurement(
        tcpPing = tcpPing,
        realPing = realPing,
        googlePing = googlePing,
        instagramPing = instagramPing,
        youtubePing = youtubePing,
        speedKbps = speedKbps,
        successCount = successCount,
        failCount = failCount,
        testedAt = testedAt,
        testedFragment = testedFragment,
        testedFragmentProfile = CoreFragmentProfile.valueOf(testedFragmentProfile.name),
        testedUrl = testedUrl,
    ),
    country = countryCode.takeIf { it.isNotBlank() }?.let { Country(it, countryName) },
)

/**
 * The settings bridge, for the same reason as [toCoreServer]: core's config
 * generator reads core's `AppSettings`.
 *
 * Both classes carry the same 42 fields with the same types, so this is a
 * straight copy — the three enum fields convert by name, which is safe because
 * every constant exists on both sides. If a field is ever added to one and not
 * the other, this stops compiling rather than silently defaulting.
 */
fun AppSettings.toCoreSettings(): ir.v2max.core.model.AppSettings =
    ir.v2max.core.model.AppSettings(
        routingMode = ir.v2max.core.model.RoutingMode.valueOf(routingMode.name),
        autoConnectOnOpen = autoConnectOnOpen,
        autoConnectOnBoot = autoConnectOnBoot,
        smartFailover = smartFailover,
        autoUpdateSubs = autoUpdateSubs,
        useFreeSources = useFreeSources,
        autoRefreshOnOpen = autoRefreshOnOpen,
        fragment = fragment,
        mux = mux,
        muxConcurrency = muxConcurrency,
        mtu = mtu,
        ipv6 = ipv6,
        multiPing = multiPing,
        deepProbe = deepProbe,
        requireFullTest = requireFullTest,
        autoTuneFragment = autoTuneFragment,
        autoUpdateCheck = autoUpdateCheck,
        speedTest = speedTest,
        speedTestCount = speedTestCount,
        rememberNetworks = rememberNetworks,
        dataSaver = dataSaver,
        wifiOnly = wifiOnly,
        balancer = balancer,
        balancerSize = balancerSize,
        batterySaver = batterySaver,
        geoAssets = geoAssets,
        dailyLimitMb = dailyLimitMb,
        killSwitch = killSwitch,
        appLock = appLock,
        hideNotificationContent = hideNotificationContent,
        showFlags = showFlags,
        dropDeadServers = dropDeadServers,
        fragmentProfile = CoreFragmentProfile.valueOf(fragmentProfile.name),
        remoteDns = remoteDns,
        domesticDns = domesticDns,
        vpnDns = vpnDns,
        delayTestUrl = delayTestUrl,
        showSpeedInNotification = showSpeedInNotification,
        dynamicColor = dynamicColor,
        customRoutingRules = customRoutingRules,
        perAppMode = ir.v2max.core.model.PerAppMode.valueOf(perAppMode.name),
        perAppPackages = perAppPackages,
    )
