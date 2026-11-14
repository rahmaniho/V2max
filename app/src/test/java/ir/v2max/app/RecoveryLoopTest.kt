package ir.v2max.app

import ir.v2max.app.core.RecoveryLoop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLoopTest {
    @Test fun `recovery backs off and returns only a fresh verified result`() = runBlocking {
        val waits = mutableListOf<Long>()
        val nextAttempts = mutableListOf<Int>()
        var probes = 0
        val result = RecoveryLoop.awaitVerified(
            isCurrent = { true },
            wait = { waits.add(it) },
            onAttempt = { nextAttempts.add(it) },
            findVerified = { if (++probes == 3) "fresh" else null }
        )
        assertEquals("fresh", result)
        assertEquals(listOf(10_000L, 20_000L, 40_000L), waits)
        assertEquals(listOf(1, 2, 3), nextAttempts)
        assertEquals(3, probes)
        // If starting the verified result fails, the next recovery cycle must
        // continue its backoff instead of returning to ten-second retries.
        val nextWaits = mutableListOf<Long>()
        assertEquals("fresh-again", RecoveryLoop.awaitVerified(
            isCurrent = { true }, initialAttempt = nextAttempts.last(),
            wait = { nextWaits.add(it) }, findVerified = { "fresh-again" }
        ))
        assertEquals(listOf(80_000L), nextWaits)
    }

    @Test fun `backoff is bounded even after repeated failures`() {
        assertEquals(10_000L, RecoveryLoop.delayFor(-1))
        assertEquals(80_000L, RecoveryLoop.delayFor(3))
        assertEquals(120_000L, RecoveryLoop.delayFor(4))
        assertEquals(120_000L, RecoveryLoop.delayFor(Int.MAX_VALUE))
    }

    @Test fun `a newer action while waiting prevents probing`() = runBlocking {
        var current = true
        var probed = false
        val result = RecoveryLoop.awaitVerified(
            isCurrent = { current },
            wait = { current = false },
            findVerified = { probed = true; "stale" }
        )
        assertNull(result)
        assertTrue(!probed)
    }

    @Test fun `a newer action during a probe discards its result`() = runBlocking {
        var current = true
        val result = RecoveryLoop.awaitVerified(
            isCurrent = { current },
            wait = {},
            findVerified = { current = false; "stale" }
        )
        assertNull(result)
    }

    @Test fun `cancellation while waiting never launches a probe`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var probed = false
        val job = async {
            RecoveryLoop.awaitVerified(
                isCurrent = { true },
                wait = { entered.complete(Unit); release.await() },
                findVerified = { probed = true; "stale" }
            )
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(!probed)
    }

    @Test fun `late noncancellable probe cannot return a result after cancellation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = async {
            RecoveryLoop.awaitVerified(
                isCurrent = { true },
                wait = {},
                findVerified = {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                    "stale"
                }
            )
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
    }
}
