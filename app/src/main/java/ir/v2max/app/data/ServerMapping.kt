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
 * This adapter is currently for comparison tests, not the live VPN. The app's
 * `ConfigBuilder` supports newer Hysteria2/Gecko/hopping and REALITY options
 * that the older core generator cannot yet reproduce. Reject these and unknown
 * transports instead of silently dropping fields or coercing them onto TCP.
 * Comparison of a few configs is not sufficient to switch the live generator.
 */
fun ServerConfig.toCoreServer(): Server {
    val transportName = network.ifBlank { "tcp" }
    require(transportName in setOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp", "splithttp", "kcp", "mkcp")) {
        "Transport is not supported by the comparison adapter"
    }
    require(proto != Proto.HYSTERIA2 && obfsMinPacketSize.isBlank() &&
        obfsMaxPacketSize.isBlank() && hoppingPorts.isBlank()) {
        "Hysteria2 options require the live app generator"
    }
    require(security in setOf("", "none", "tls", "reality")) {
        "Security mode is not supported by the comparison adapter"
    }
    require(security != "reality" || mldsa65Verify.isBlank()) {
        "REALITY ML-DSA verification requires the live app generator"
    }
    return Server(
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
        transport = when (transportName) {
            "ws" -> Transport.WebSocket(host, path.ifBlank { "/" })
            "grpc" -> Transport.Grpc(serviceName, authority, mode)
            "httpupgrade" -> Transport.HttpUpgrade(host, path)
            "xhttp", "splithttp" -> Transport.XHttp(host, path, mode, xhttpExtra)
            "kcp", "mkcp" -> Transport.Kcp(headerType.ifBlank { "none" }, kcpSeed, kcpMtu, kcpTti)
            else -> Transport.Tcp(headerType.ifBlank { "none" }, host, path)
        },
        // Blank security maps to None for this restricted comparison adapter.
        security = when (security) {
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
}

/**
 * The settings bridge, for the same reason as [toCoreServer]: core's config
 * generator reads core's `AppSettings`.
 *
 * Config-relevant fields are copied and the three enums convert by name.
 * Device-local external-automation consent deliberately stays in the app; it
 * is not part of the core config model. New fields still need explicit review.
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
