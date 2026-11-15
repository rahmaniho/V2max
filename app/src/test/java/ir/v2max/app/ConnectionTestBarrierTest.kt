package ir.v2max.app

import ir.v2max.app.core.ConnectionTestBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestBarrierTest {
    @Test fun `connection waits for a cancelled native style test to actually finish`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        val test = launch(Dispatchers.Default) {
            ConnectionTestBarrier.tests {
                entered.complete(Unit)
                // Models a blocking JNI call: cancellation cannot end it early.
                withContext(NonCancellable) { release.await() }
            }
        }
        withTimeout(5_000) { entered.await() }
        test.cancel()
        val connection = async(Dispatchers.Default) {
            ConnectionTestBarrier.connection { connected.set(true) }
        }
        try {
            delay(100)
            assertFalse("A connection started while a native test was still running", connected.get())
        } finally {
            release.complete(Unit)
        }
        withTimeout(5_000) { test.cancelAndJoin(); connection.await() }
        assertTrue(connected.get())
    }

    @Test fun `live monitoring can overlap housekeeping but handover waits for both`() = runBlocking {
        val testEntered = CompletableDeferred<Unit>()
        val monitorEntered = CompletableDeferred<Unit>()
        val releaseTest = CompletableDeferred<Unit>()
        val releaseMonitor = CompletableDeferred<Unit>()
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        val test = launch(Dispatchers.Default) {
            ConnectionTestBarrier.tests {
                testEntered.complete(Unit)
                releaseTest.await()
            }
        }
        val monitor = launch(Dispatchers.Default) {
            ConnectionTestBarrier.monitoring {
                monitorEntered.complete(Unit)
                // A native probe may keep running even after the monitor is canceled.
                withContext(NonCancellable) { releaseMonitor.await() }
            }
        }
        withTimeout(5_000) { testEntered.await(); monitorEntered.await() }
        monitor.cancel()
        val connection = async(Dispatchers.Default) {
            ConnectionTestBarrier.connection { connected.set(true) }
        }
        try {
            delay(100)
            assertFalse(connected.get())
            releaseTest.complete(Unit)
            withTimeout(5_000) { test.join() }
            delay(100)
            assertFalse("A canceled live probe must return before handover", connected.get())
        } finally {
            releaseTest.complete(Unit)
            releaseMonitor.complete(Unit)
        }
        withTimeout(5_000) { monitor.cancelAndJoin(); connection.await() }
        assertTrue(connected.get())
    }

    @Test fun `new tests wait while a handover owns both gates`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scanning = java.util.concurrent.atomic.AtomicBoolean(false)
        val monitoring = java.util.concurrent.atomic.AtomicBoolean(false)
        val connection = launch(Dispatchers.Default) {
            ConnectionTestBarrier.connection { entered.complete(Unit); release.await() }
        }
        withTimeout(5_000) { entered.await() }
        val test = launch(Dispatchers.Default) { ConnectionTestBarrier.tests { scanning.set(true) } }
        val monitor = launch(Dispatchers.Default) { ConnectionTestBarrier.monitoring { monitoring.set(true) } }
        try {
            delay(100)
            assertFalse(scanning.get())
            assertFalse(monitoring.get())
        } finally {
            release.complete(Unit)
        }
        withTimeout(5_000) { connection.join(); test.join(); monitor.join() }
        assertTrue(scanning.get())
        assertTrue(monitoring.get())
    }
}
