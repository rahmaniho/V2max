package ir.v2max.app.net

import ir.v2max.app.core.ConfigBuilder
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * سنجش سرعت دانلود واقعی از طریق یک کانفیگ، پیش از اتصال تانل.
 *
 * A separate no-TUN Xray controller exposes a temporary local SOCKS inbound.
 * It is never the VPN controller, so stopping a measurement cannot stop a live tunnel.
 */
object SpeedTester {

    /** منبع تست سبک و پرسرعت (Cloudflare) */
    private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=1200000"

    private const val MAX_BYTES = 1_500_000L
    private val speedMutex = Mutex()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Kbit/s; zero means the download was attempted and failed. Null means the
     * test could not be set up and a connection must not be started on that basis.
     * The URL parameter also permits a local, deterministic instrumentation fixture.
     */
    suspend fun measure(server: ServerConfig, settings: AppSettings, testUrl: String = TEST_URL): Int? =
        speedMutex.withLock {
            withContext(Dispatchers.IO) {
                var lease: Long? = null
                try {
                    // Avoid a fixed port, which may already belong to another app.
                    val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                    val config = ConfigBuilder.socksTestConfig(server, settings, port)
                    lease = XrayCore.startSpeedTest(config) ?: return@withContext null
                    if (!awaitSocksReady(port)) return@withContext null

                    val request = Request.Builder()
                        .url(testUrl)
                        .header("User-Agent", "V2max-SpeedTest")
                        .header("Connection", "close")
                        .get()
                        .build()
                    val proxyClient = client.newBuilder()
                        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                        .build()
                    downloadKbps(proxyClient, request)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    lease?.let { runCatching { XrayCore.stopSpeedTest(it) } }
                }
            }
        }

    /** Cancellation closes the HTTP stream rather than waiting for the read timeout. */
    private suspend fun downloadKbps(client: OkHttpClient, request: Request): Int = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        val start = System.nanoTime()
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(0)
            }

            override fun onResponse(call: Call, response: Response) {
                val kbps = try {
                    response.use {
                        val body = it.body
                        if (!it.isSuccessful || body == null) 0 else {
                            var totalBytes = 0L
                            val stream = body.byteStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read <= 0) break
                                totalBytes += read
                                if (totalBytes >= MAX_BYTES ||
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > 12_000L) break
                            }
                            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                            if (totalBytes < 20_000) 0 else ((totalBytes * 8L) / elapsed).coerceIn(0, 999_999).toInt()
                        }
                    }
                } catch (_: Exception) {
                    0
                }
                if (continuation.isActive) continuation.resume(kbps)
            }
        })
    }

    private suspend fun awaitSocksReady(port: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val ready = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                true
            } catch (_: Exception) {
                false
            }
            if (ready) return true
            delay(25)
        }
        return false
    }
}
