package ir.v2max.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Proto
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.net.LinkParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Uses the same pinned upstream Xray release as the Android library; no public server required. */
class XrayIntegrationTest {
    private val uuid = "11111111-2222-3333-4444-555555555555"
    private val binary = System.getenv("TEST_XRAY_BIN").orEmpty()
    private val settings = AppSettings(fragment = false, balancer = false, geoAssets = false, routingMode = RoutingMode.GLOBAL)

    private fun requireBinary() {
        assumeTrue("TEST_XRAY_BIN is set by CI", binary.isNotBlank() && File(binary).canExecute())
    }

    private fun configFile(content: String): File = File.createTempFile("v2max-xray-", ".json").apply {
        writeText(content)
        deleteOnExit()
    }

    private fun validate(config: String, label: String = "fixture") {
        val file = configFile(config)
        val process = ProcessBuilder(binary, "run", "-test", "-config", file.absolutePath)
            .redirectErrorStream(true).start()
        assertTrue("Xray config validation timed out", process.waitFor(20, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("Xray rejected $label: $output", 0, process.exitValue())
    }

    @Test fun `pinned xray accepts configs generated from common v2rayng links`() {
        requireBinary()
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val links = listOf(
            "vless://$uuid@127.0.0.1:443?type=tcp&security=none",
            "vless://$uuid@example.org:443?type=ws&security=tls&host=cdn.example.org&path=%2Fws%3Fed%3D2048&sni=cdn.example.org",
            "vless://$uuid@example.org:443?type=grpc&security=tls&serviceName=example&authority=cdn.example.org&mode=multi",
            "vless://$uuid@example.org:443?type=tcp&security=reality&sni=example.org&fp=chrome&pbk=$key&sid=abcd&flow=xtls-rprx-vision",
            "vless://$uuid@example.org:443?type=xhttp&security=tls&host=cdn.example.org&path=%2Fx&mode=packet-up&extra=%7B%22xPaddingBytes%22%3A%22100-200%22%7D",
            "vless://$uuid@10.0.0.2:443?type=kcp&seed=seed&headerType=wechat-video",
            "vmess://$uuid@example.org:443?type=grpc&security=tls&serviceName=svc&authority=cdn.example.org",
            "trojan://password@example.org:443?type=ws&security=tls&host=cdn.example.org&path=%2Fws",
            "ss://YWVzLTEyOC1nY206cGFzcw@example.org:443",
            "hysteria2://password@example.org:443?sni=example.org&obfs=salamander&obfs-password=secret"
        )
        links.forEach { link ->
            val cfg = LinkParser.parseLine(link)
            assertNotNull("Could not import ${link.substringBefore("://")}", cfg)
            validate(ConfigBuilder.testConfig(cfg!!, settings), "${cfg.proto}/${cfg.network} test")
            validate(ConfigBuilder.vpnConfig(cfg, settings), "${cfg.proto}/${cfg.network} VPN")
        }
        val primary = LinkParser.parseLine(links.first())!!
        val other = primary.copy(id = "second", address = "10.0.0.2")
        validate(ConfigBuilder.vpnConfig(primary, settings.copy(balancer = true), listOf(primary, other)))
    }

    @Test fun `imported profiles actually transfer traffic via local xray servers`() {
        requireBinary()
        exerciseTunnel("vless://$uuid@127.0.0.1:{port}?type=tcp&security=none", Proto.VLESS, "tcp")
        exerciseTunnel("vless://$uuid@127.0.0.1:{port}?type=ws&security=none&host=cdn.example.org&path=%2Fws", Proto.VLESS, "ws")
        exerciseTunnel("vmess://$uuid@127.0.0.1:{port}?type=tcp&security=none", Proto.VMESS, "tcp")
        exerciseTunnel("trojan://secret@127.0.0.1:{port}?type=tcp&security=none", Proto.TROJAN, "tcp")
        exerciseTunnel("ss://YWVzLTEyOC1nY206cGFzcw@127.0.0.1:{port}", Proto.SS, "tcp")
    }

    private fun exerciseTunnel(linkTemplate: String, proto: Proto, network: String) {
        val proxyPort = freePort()
        val socksPort = freePort()
        val target = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        target.soTimeout = 15_000
        val serverConfig = JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "error") })
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("listen", "127.0.0.1")
                    addProperty("port", proxyPort)
                    addProperty("protocol", when (proto) {
                        Proto.VLESS -> "vless"
                        Proto.VMESS -> "vmess"
                        Proto.TROJAN -> "trojan"
                        Proto.SS -> "shadowsocks"
                        else -> error("unsupported test protocol")
                    })
                    add("settings", JsonObject().apply {
                        when (proto) {
                            Proto.VLESS, Proto.VMESS -> {
                                add("clients", JsonArray().apply { add(JsonObject().apply { addProperty("id", uuid) }) })
                                if (proto == Proto.VLESS) addProperty("decryption", "none")
                            }
                            Proto.TROJAN -> add("clients", JsonArray().apply {
                                add(JsonObject().apply { addProperty("password", "secret") })
                            })
                            Proto.SS -> { addProperty("method", "aes-128-gcm"); addProperty("password", "pass") }
                            else -> Unit
                        }
                    })
                    add("streamSettings", JsonObject().apply {
                        addProperty("network", network)
                        if (network == "ws") add("wsSettings", JsonObject().apply {
                            addProperty("host", "cdn.example.org")
                            addProperty("path", "/ws")
                        })
                    })
                })
            })
            add("outbounds", JsonArray().apply { add(JsonObject().apply {
                addProperty("protocol", "freedom")
                // Xray blocks private targets from remote inbound protocols by default.
                // Permit only this disposable loopback fixture.
                add("settings", JsonObject().apply {
                    add("finalRules", JsonArray().apply { add(JsonObject().apply {
                        addProperty("action", "allow")
                        add("ip", JsonArray().apply { add("127.0.0.1/32") })
                    }) })
                })
            }) })
        }.toString()
        val serverLog = File.createTempFile("v2max-server-", ".log").apply { deleteOnExit() }
        val clientLog = File.createTempFile("v2max-client-", ".log").apply { deleteOnExit() }
        val server = ProcessBuilder(binary, "run", "-config", configFile(serverConfig).absolutePath)
            .redirectErrorStream(true).redirectOutput(serverLog).start()
        var client: Process? = null
        val responder = thread(isDaemon = true) {
            try {
                target.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nv2max".toByteArray())
                }
            } catch (_: Exception) { }
        }
        try {
            waitForPort(proxyPort, server, serverLog)
            val cfg = LinkParser.parseLine(linkTemplate.replace("{port}", proxyPort.toString()))!!
            val clientConfig = ConfigBuilder.socksTestConfig(cfg, settings, socksPort)
            client = ProcessBuilder(binary, "run", "-config", configFile(clientConfig).absolutePath)
                .redirectErrorStream(true).redirectOutput(clientLog).start()
            waitForPort(socksPort, client, clientLog)
            Socket("127.0.0.1", socksPort).use { socket ->
                socket.soTimeout = 10_000
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                assertArrayEquals(byteArrayOf(5, 0), input.readNBytes(2))
                val port = target.localPort
                output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
                output.flush()
                val response = input.readNBytes(10)
                assertTrue("SOCKS connect failed ($proto/$network): ${response.toList()}\n${serverLog.readText()}\n${clientLog.readText()}",
                    response.size == 10 && response[1] == 0.toByte())
                // Xray sniffing routes by the HTTP Host. Keep it identical to the local target.
                output.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                // Read until the known response body arrives; a proxy need not close immediately.
                val responseBytes = java.io.ByteArrayOutputStream()
                try {
                    while (responseBytes.size() < 8192 && !responseBytes.toString("UTF-8").contains("v2max")) {
                        val byte = input.read()
                        if (byte < 0) break
                        responseBytes.write(byte)
                    }
                } catch (_: java.net.SocketTimeoutException) { }
                val body = responseBytes.toString("UTF-8")
                assertTrue("Traffic failed ($proto/$network): $body\n${serverLog.readText()}\n${clientLog.readText()}", body.contains("v2max"))
            }
        } finally {
            target.close()
            responder.join(1_000)
            client?.destroyForcibly()
            server.destroyForcibly()
            client?.waitFor(3, TimeUnit.SECONDS)
            server.waitFor(3, TimeUnit.SECONDS)
        }
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun waitForPort(port: Int, process: Process, log: File) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            if (!process.isAlive) fail("Xray exited early: ${log.readText()}")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }
                return
            } catch (_: Exception) { Thread.sleep(50) }
        }
        fail("Xray did not open port $port: ${log.readText()}")
    }
}
