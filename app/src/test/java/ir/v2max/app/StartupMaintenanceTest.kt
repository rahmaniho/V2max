package ir.v2max.app

import ir.v2max.app.net.StartupMaintenance
import ir.v2max.app.net.SubscriptionSyncGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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
        val startup = launch(Dispatchers.Default) {
            StartupMaintenance.track {
                startupEntered.complete(Unit)
                SubscriptionSyncGate.mutex.withLock { acquired.set(true) }
            }
        }
        try {
            withTimeout(5_000) { startupEntered.await() }
            StartupMaintenance.cancelAll()
            withTimeout(5_000) { startup.cancelAndJoin() }
            assertTrue(startup.isCancelled)
            assertFalse("Canceled startup must not enter the refresh gate", acquired.get())
        } finally {
            releaseWorker.complete(Unit)
        }
        withTimeout(5_000) { worker.join() }
        // Canceling an old task must not poison later maintenance attempts.
        withTimeout(5_000) {
            StartupMaintenance.track { SubscriptionSyncGate.mutex.withLock { acquired.set(true) } }
        }
        assertTrue(acquired.get())
    }
}
