package ir.v2max.app

import android.app.Application
import android.content.Context
import android.net.VpnService
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ir.v2max.app.data.Repo
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.net.SubscriptionFetcher
import ir.v2max.app.service.SubSyncWorker
import ir.v2max.app.service.V2maxVpnService
import kotlinx.coroutines.runBlocking
import ir.v2max.app.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A previously scheduled worker must obey the current, not its old, setting. */
@RunWith(AndroidJUnit4::class)
class AndroidSubscriptionWorkerTest {
    @Test fun disabledAutoConnectDoesNotStartAnUnrequestedScan() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Repo.init(context)
        assertFalse("Fixture requires no active tunnel", Repo.tunnel.value.isActive)
        val oldSettings = Repo.settings.value.copy()
        val oldScan = Repo.scan.value
        val oldSelection = Repo.selectedId.value
        val server = ServerConfig(address = "10.0.2.2", port = 1, uuid = "startup-test")
        val store = ViewModelStore()
        try {
            assertTrue(Repo.updateSettings {
                it.copy(autoConnectOnOpen = false, autoRefreshOnOpen = false, autoUpdateCheck = false,
                    autoUpdateSubs = false, useFreeSources = false, deepProbe = false)
            })
            assertEquals(1, Repo.addServers(listOf(server)))
            Repo.setSelected(server.id)
            Repo.scan.value = ScanState(message = "no automatic scan")
            instrumentation.runOnMainSync {
                val app = context.applicationContext as Application
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1_000)
            assertEquals("no automatic scan", Repo.scan.value.message)
            assertFalse(Repo.scan.value.running)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            Repo.removeServer(server.id)
            Repo.setSelected(oldSelection)
            Repo.scan.value = oldScan
            Repo.saveSettings(oldSettings)
        }
    }

    @Test fun qrImportsPreservePendingVpnPermissionAndManualSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val needsPermission = VpnService.prepare(context) != null
        if (InstrumentationRegistry.getArguments().getString("expectUnpreparedVpn") == "true") {
            assertTrue("Security fixture unexpectedly has VPN permission", needsPermission)
        }
        // The full TUN suite grants permission first; this scenario runs in the
        // separate fresh security emulator rather than modifying a live VPN.
        assumeTrue("VPN permission already granted", needsPermission)
        Repo.init(context)
        assertFalse("Fixture requires no active tunnel", Repo.tunnel.value.isActive)
        val oldSettings = Repo.settings.value.copy()
        val oldSelection = Repo.selectedId.value
        val selected = ServerConfig(address = "pending-choice.example.invalid", port = 443, uuid = "chosen")
        val qrHost = "pending-qr.example.invalid"
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/pending"
        val responder = thread(isDaemon = true, name = "pending-subscription-fixture") {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 5_000
                    val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "vless://22222222-3333-4444-5555-666666666666@pending-sub.example.invalid:443"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    client.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                        output.flush()
                    }
                }
            } catch (_: Exception) {
                // Cleanup closes a fixture that was never contacted.
            }
        }
        val store = ViewModelStore()
        lateinit var vm: AppViewModel
        try {
            assertTrue(Repo.updateSettings {
                it.copy(autoConnectOnOpen = false, autoRefreshOnOpen = false, autoUpdateSubs = false,
                    autoUpdateCheck = false, useFreeSources = false, deepProbe = false)
            })
            assertEquals(1, Repo.addServers(listOf(selected)))
            Repo.setSelected(selected.id)
            val generation = V2maxVpnService.currentActionGeneration()
            instrumentation.runOnMainSync {
                val app = context.applicationContext as Application
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
                vm.requestConnect(selected.id)
                assertTrue("No VPN permission request was recorded", vm.pendingVpnRequest.value)
                vm.onQrScanned(url)
                vm.onQrScanned("vless://11111111-2222-3333-4444-555555555555@$qrHost:443?security=tls#QR")
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline &&
                (Repo.subs.value.none { sub -> sub.url == url && Repo.servers.value.any { it.subId == sub.id } } ||
                    Repo.servers.value.none { it.address == qrHost })) {
                assertTrue("Import discarded the pending permission request", vm.pendingVpnRequest.value)
                SystemClock.sleep(50)
            }
            assertTrue("Subscription import failed: ${vm.message.value}",
                Repo.subs.value.any { sub -> sub.url == url && Repo.servers.value.any { it.subId == sub.id } })
            assertTrue("Profile import failed: ${vm.message.value}", Repo.servers.value.any { it.address == qrHost })
            assertTrue("Import discarded the pending permission request", vm.pendingVpnRequest.value)
            assertEquals("Import changed the selected profile", selected.id, Repo.selectedId.value)
            assertEquals("Import started a connection", generation, V2maxVpnService.currentActionGeneration())
            instrumentation.runOnMainSync { vm.onVpnPermissionResult(false) }
            assertFalse(vm.pendingVpnRequest.value)
            assertFalse("A denied permission started a tunnel", Repo.tunnel.value.isActive)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            socket.close()
            responder.join(2_000)
            Repo.subs.value.filter { it.url == url }.forEach { Repo.removeSubscription(it.id) }
            Repo.removeServers(Repo.servers.value.filter { it.id == selected.id || it.address == qrHost }.map { it.id })
            Repo.setSelected(oldSelection)
            Repo.saveSettings(oldSettings)
        }
    }

    @Test fun disabledAutomaticRefreshDoesNotContactSubscriptions() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        Repo.init(context)
        val oldSettings = Repo.settings.value.copy()
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val hits = AtomicInteger()
        val responder = thread(isDaemon = true, name = "subscription-fixture") {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        client.soTimeout = 5_000
                        val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        hits.incrementAndGet()
                        val body = "vless://11111111-2222-3333-4444-555555555555@example.org:443"
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().use { output ->
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(bytes)
                            output.flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // Closing the socket after the worker finishes unblocks accept().
            }
        }
        val source = Subscription(url = "http://127.0.0.1:${socket.localPort}/private")
        try {
            assertTrue(Repo.updateSettings { it.copy(autoRefreshOnOpen = false) })
            assertTrue(Repo.addSubscription(source))
            // Exercise the same fetcher against the loopback fixture first; a
            // blocked socket or cleartext policy must not make the test pass.
            val positive = runBlocking { SubscriptionFetcher.fetch(source.url) }
            assertTrue("Subscription fixture is not reachable: ${positive.error}", positive.ok && positive.configs.isNotEmpty())
            assertEquals("Positive control did not hit the fixture", 1, hits.get())
            val request = OneTimeWorkRequestBuilder<SubSyncWorker>().build()
            val manager = WorkManager.getInstance(context)
            manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var info: WorkInfo? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                info = manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)
                if (info != null && info.state.isFinished) break
                SystemClock.sleep(100)
            }
            assertNotNull("Worker did not reach a terminal state", info)
            assertEquals("Disabled worker did not finish successfully", WorkInfo.State.SUCCEEDED, info?.state)
            assertEquals("An opted-out subscription was contacted", 1, hits.get())
            assertEquals("", Repo.subById(source.id)?.lastError)
        } finally {
            socket.close()
            responder.join(2_000)
            Repo.removeSubscription(source.id)
            Repo.saveSettings(oldSettings)
        }
    }
}
