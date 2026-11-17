package ir.v2max.app

import ir.v2max.app.net.StartupMaintenance
import ir.v2max.app.net.SubscriptionSyncGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class StartupMaintenanceTest {
    @Test fun `recovery cancels startup even while it is waiting for a periodic refresh`() = runBlocking {
        val workerEntered = CompletableDeferred<Unit>()
        val releaseWorker = CompletableDeferred<Unit>()
        val startupEntered = CompletableDeferred<Unit>()
        val acquired = AtomicBoolean(false)
        val worker = launch(Dispatchers.Default) {
            SubscriptionSyncGate.mutex.withLock {
                workerEntered.complete(Unit)
                releaseWorker.await()
            }
        }
        withTimeout(5_000) { workerEntered.await() }
        val token = ir.v2max.app.data.Repo.startupHealthToken()
        val startup = launch(Dispatchers.Default) {
            StartupMaintenance.track(token) {
                startupEntered.complete(Unit)
                SubscriptionSyncGate.mutex.withLock { acquired.set(true) }
            }
        }
        try {
            withTimeout(5_000) { startupEntered.await() }
            StartupMaintenance.cancelAll()
            // join(), not cancelAndJoin(): only cancelAll() may cancel this job.
            // A broken cancellation must time out while the worker holds the gate.
            withTimeout(5_000) { startup.join() }
            assertTrue("cancelAll must cancel the registered startup", startup.isCancelled)
            assertFalse("Canceled startup must not enter the refresh gate", acquired.get())
            releaseWorker.complete(Unit)
            withTimeout(5_000) { worker.join() }
            // Cancellation must not poison the gate for subsequent maintenance.
            withTimeout(5_000) {
                StartupMaintenance.track(ir.v2max.app.data.Repo.startupHealthToken()) {
                    SubscriptionSyncGate.mutex.withLock { acquired.set(true) }
                }
            }
            assertTrue(acquired.get())
        } finally {
            // Also clean up on timeout, without letting the waiting startup enter.
            startup.cancel()
            releaseWorker.complete(Unit)
            worker.cancel()
        }
    }

    @Test fun `a service action before registration rejects the stale startup`() = runBlocking {
        val staleToken = ir.v2max.app.data.Repo.startupHealthToken()
        StartupMaintenance.cancelAll()
        val entered = AtomicBoolean(false)
        val old = launch(Dispatchers.Default) {
            StartupMaintenance.track(staleToken) { entered.set(true) }
        }
        withTimeout(5_000) { old.join() }
        assertTrue("An already superseded startup must be canceled", old.isCancelled)
        assertFalse("A stale startup must not begin after a newer action", entered.get())
        StartupMaintenance.track(ir.v2max.app.data.Repo.startupHealthToken()) { entered.set(true) }
        assertTrue("A fresh startup can still register", entered.get())
    }
}
