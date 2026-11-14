package ir.v2max.app

import ir.v2max.core.model.PublicSources
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.net.LinkParser
import ir.v2max.app.net.SubscriptionBootstrap
import ir.v2max.app.net.SubscriptionBody
import ir.v2max.app.net.SubscriptionFetcher
import ir.v2max.app.net.SubscriptionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class SubscriptionBootstrapTest {
    private fun good() = SubscriptionFetcher.Result(configs = listOf(ServerConfig(address = "example.org")))

    @Test fun `first usable source wins and slow requests are cancelled`() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        var slowCancelled = false
        val sources = listOf(Subscription(id = "slow", url = "slow"), Subscription(id = "fast", url = "fast"))
        val first = withTimeout(5_000) {
            SubscriptionBootstrap.firstUsable(sources, fetch = { sub ->
                if (sub.url == "slow") {
                    try { slowStarted.complete(Unit); awaitCancellation() }
                    finally { slowCancelled = true }
                } else {
                    slowStarted.await()
                    good()
                }
            })
        }
        assertEquals("fast", first?.subscription?.id)
        assertTrue(slowCancelled)
    }

    @Test fun `invalid and failed subscriptions are skipped`() = runBlocking {
        val sources = listOf(
            Subscription(id = "bad", url = "bad"),
            Subscription(id = "local", url = "local"),
            Subscription(id = "good", url = "good")
        )
        val first = SubscriptionBootstrap.firstUsable(sources, parallelism = 1, fetch = { sub ->
            when (sub.url) {
                "bad" -> SubscriptionFetcher.Result(error = "failed")
                "local" -> SubscriptionFetcher.Result(configs = listOf(ServerConfig(address = "127.0.0.1")))
                else -> good()
            }
        })
        assertEquals("good", first?.subscription?.id)
    }

    @Test fun `caller cancellation propagates to source requests`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val task = async {
            SubscriptionBootstrap.firstUsable(listOf(Subscription(url = "wait")), fetch = {
                try { entered.complete(Unit); awaitCancellation() }
                finally { cancelled = true }
            })
        }
        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { task.cancelAndJoin() }
        assertTrue(cancelled)
    }

    @Test fun `built in caps do not truncate a personal subscription`() {
        val profiles = (0 until 550).map { ServerConfig(id = "$it", address = "host$it.example.org") }
        val public = PublicSources.DEFAULT.first()
        assertEquals(public.maxConfigs, SubscriptionPolicy.candidates(Subscription(builtIn = true, url = public.url), profiles).size)
        assertEquals(550, SubscriptionPolicy.candidates(Subscription(builtIn = false, url = public.url), profiles).size)
        val invalidFirst = listOf(ServerConfig(address = "127.0.0.1")) + profiles
        assertEquals(public.maxConfigs, SubscriptionPolicy.candidates(Subscription(builtIn = true, url = public.url), invalidFirst).size)
    }

    @Test fun `personal subscriptions and unknown URLs never go to third party mirrors`() {
        val known = PublicSources.DEFAULT.first().url
        val privateUrl = "https://raw.githubusercontent.com/me/private/main/secret-token/sub.txt?token=secret"
        val publicSub = Subscription(builtIn = true, url = known)
        val personalCopy = Subscription(builtIn = false, url = known)
        assertTrue(SubscriptionPolicy.allowPublicMirrors(publicSub))
        assertFalse(SubscriptionPolicy.allowPublicMirrors(personalCopy))
        assertFalse(SubscriptionPolicy.allowPublicMirrors(Subscription(builtIn = true, url = privateUrl)))
        assertFalse(SubscriptionPolicy.allowPublicMirrors(Subscription(builtIn = true, url = "$known?token=secret")))
        assertFalse(SubscriptionPolicy.allowPublicMirrors(Subscription(builtIn = true, url = "$known/")))
        assertEquals(listOf(known), SubscriptionFetcher.mirrorsFor(known))
        assertEquals(listOf(privateUrl), SubscriptionFetcher.mirrorsFor(privateUrl, allowPublicMirrors = true))
        assertEquals(listOf("$known?token=secret"), SubscriptionFetcher.mirrorsFor("$known?token=secret", true))
        assertEquals(listOf("$known/"), SubscriptionFetcher.mirrorsFor("$known/", true))
        val mirrors = SubscriptionFetcher.mirrorsFor(known, allowPublicMirrors = true)
        assertEquals(3, mirrors.size)
        assertEquals(known, mirrors.first())
        assertTrue(mirrors[1].startsWith("https://cdn.jsdelivr.net/"))
        assertTrue(mirrors[2].startsWith("https://ghproxy.net/"))
    }

    @Test fun `only a known built in can be canonicalized before fetching`() {
        val known = PublicSources.DEFAULT.first().url
        val original = Subscription(id = "built-in", builtIn = true, url = " $known/// ")
        val request = SubscriptionPolicy.requestSubscription(original)
        assertEquals(" $known/// ", original.url) // refresh identity is not mutated
        assertEquals("built-in", request.id)
        assertEquals(known, request.url)
        assertTrue(SubscriptionPolicy.allowPublicMirrors(request))
        assertEquals(known, SubscriptionFetcher.mirrorsFor(request.url, true).first())

        val personal = Subscription(builtIn = false, url = "$known/")
        val unknown = Subscription(builtIn = true, url = "$known?token=secret/")
        for (sub in listOf(personal, unknown)) {
            val unchanged = SubscriptionPolicy.requestSubscription(sub)
            assertEquals(sub.url, unchanged.url)
            assertFalse(SubscriptionPolicy.allowPublicMirrors(unchanged))
            assertEquals(listOf(sub.url), SubscriptionFetcher.mirrorsFor(unchanged.url, true))
        }
    }

    @Test fun `large public feeds stop parsing at the cap but personal feeds do not`() {
        val links = (0 until 550).map { "vless://u@host$it.example.org:443?security=tls" }
        val body = (listOf("vless://u@127.0.0.1:443") + links).joinToString("\n")
        assertEquals(150, LinkParser.parseAll(body, maxConfigs = 150).size)
        assertEquals("host0.example.org", LinkParser.parseAll(body, maxConfigs = 150).first().address)
        assertEquals(551, LinkParser.parseAll(body).size)
    }

    @Test fun `personal HTTP subscription preserves gzip charset profiles and metadata`() = runBlocking {
        val links = (0 until 550).joinToString("\n") { "vless://u@host$it.example.org:443?security=tls#سرور" }
        val bytes = ("\uFEFF" + links).toByteArray(Charsets.UTF_16LE)
        val compressed = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.GZIPOutputStream(output).use { it.write(bytes) }
        }.toByteArray()
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            server.use {
                it.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val headers = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n" +
                        "Content-Type: text/plain; charset=iso-8859-1\r\nContent-Length: ${compressed.size}\r\n" +
                        "subscription-userinfo: upload=12; download=34; total=100; expire=1893456000\r\n" +
                        "profile-title: My Subscription\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().write(compressed)
                    socket.getOutputStream().flush()
                }
            }
        }
        try {
            val result = withTimeout(5_000) {
                SubscriptionFetcher.fetch("http://127.0.0.1:${server.localPort}/private?token=secret")
            }
            assertTrue(result.error, result.ok)
            assertEquals(550, result.configs.size)
            assertEquals("سرور", result.configs.first().name)
            assertEquals("My Subscription", result.title)
            assertEquals(12L, result.upload)
            assertEquals(34L, result.download)
            assertEquals(100L, result.total)
            assertEquals(1893456000L, result.expireAt)
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test fun `oversized HTTP response is rejected before parsing`() = runBlocking {
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            server.use {
                it.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val reply = "HTTP/1.1 200 OK\r\nContent-Length: ${SubscriptionBody.MAX_BYTES + 1}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(reply.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
            }
        }
        try {
            val result = withTimeout(5_000) {
                SubscriptionFetcher.fetch("http://127.0.0.1:${server.localPort}/subscription")
            }
            assertFalse(result.ok)
            assertTrue(result.error.contains("۸ مگابایت"))
            assertTrue(result.configs.isEmpty())
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test fun `gzip response is limited after decompression`() = runBlocking {
        val compressed = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.GZIPOutputStream(output).use { gzip ->
                val block = ByteArray(8192) { 'x'.code.toByte() }
                repeat(SubscriptionBody.MAX_BYTES / block.size + 1) { gzip.write(block) }
            }
        }.toByteArray()
        assertTrue(compressed.size < SubscriptionBody.MAX_BYTES)
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            server.use {
                it.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val headers = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n" +
                        "Content-Length: ${compressed.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().write(compressed)
                    socket.getOutputStream().flush()
                }
            }
        }
        try {
            val result = withTimeout(10_000) {
                SubscriptionFetcher.fetch("http://127.0.0.1:${server.localPort}/compressed")
            }
            assertFalse(result.ok)
            assertTrue(result.error.contains("۸ مگابایت"))
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test fun `cancellation closes a response stalled during its body`() = runBlocking {
        val bodyStarted = CompletableDeferred<Unit>()
        val socketClosed = CompletableDeferred<Unit>()
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            server.use {
                try {
                    it.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val reader = input.bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\ntest\r\n"
                        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().flush()
                        bodyStarted.complete(Unit)
                        while (input.read() >= 0) { }
                        socketClosed.complete(Unit)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    socketClosed.completeExceptionally(e)
                } catch (_: java.io.IOException) {
                    // A reset is also a successful cancellation of the client socket.
                    socketClosed.complete(Unit)
                }
            }
        }
        try {
            val task = async { SubscriptionFetcher.fetch("http://127.0.0.1:${server.localPort}/subscription") }
            withTimeout(5_000) { bodyStarted.await() }
            kotlinx.coroutines.delay(100) // allow the client to enter the unfinished body read
            withTimeout(5_000) { task.cancelAndJoin() }
            withTimeout(2_000) { socketClosed.await() }
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test fun `cancelled real http fetch closes its socket promptly`() = runBlocking {
        val requestReceived = CompletableDeferred<Unit>()
        val socketClosed = CompletableDeferred<Unit>()
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            server.use {
                try {
                    it.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        // Wait until an HTTP request has actually arrived, then never reply.
                        if (input.read() >= 0) requestReceived.complete(Unit)
                        while (input.read() >= 0) { }
                        socketClosed.complete(Unit)
                    }
                } catch (t: Exception) {
                    socketClosed.completeExceptionally(t)
                }
            }
        }
        try {
            val task = async { SubscriptionFetcher.fetch("http://127.0.0.1:${server.localPort}/subscription") }
            withTimeout(5_000) { requestReceived.await() }
            withTimeout(5_000) { task.cancelAndJoin() }
            withTimeout(5_000) { socketClosed.await() }
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }
}
