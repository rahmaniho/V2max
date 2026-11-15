package ir.v2max.app.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A connection must not start while an app-initiated test or a live-tunnel
 * probe is still running. Cancellation cannot interrupt a blocking native call;
 * its holder releases the gate only after the call actually returns.
 *
 * Live monitoring may overlap a long housekeeping scan so the existing VPN is
 * not left unmonitored. Handover takes both gates, always in the same order.
 * Never initiate a new scan or handover from inside either test block.
 */
internal object ConnectionTestBarrier {
    private val testsMutex = Mutex()
    private val monitoringMutex = Mutex()

    suspend fun <T> tests(block: suspend () -> T): T = testsMutex.withLock { block() }
    suspend fun <T> monitoring(block: suspend () -> T): T = monitoringMutex.withLock { block() }
    suspend fun <T> connection(block: suspend () -> T): T =
        testsMutex.withLock { monitoringMutex.withLock { block() } }
}
