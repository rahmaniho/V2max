package ir.v2max.app

import ir.v2max.app.data.PublicSources
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.net.LinkParser
import ir.v2max.app.net.SubscriptionBootstrap
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

    @Test fun `large public feeds stop parsing at the cap but personal feeds do not`() {
        val links = (0 until 550).map { "vless://u@host$it.example.org:443?security=tls" }
        val body = (listOf("vless://u@127.0.0.1:443") + links).joinToString("\n")
        assertEquals(150, LinkParser.parseAll(body, maxConfigs = 150).size)
        assertEquals("host0.example.org", LinkParser.parseAll(body, maxConfigs = 150).first().address)
        assertEquals(551, LinkParser.parseAll(body).size)
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
