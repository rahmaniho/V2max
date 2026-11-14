package ir.v2max.app

import ir.v2max.app.ui.VpnPermissionQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPermissionQueueTest {
    @Test fun dialogCanOnlyBeLaunchedOnceUntilItsResultArrives() {
        val queue = VpnPermissionQueue<String>()
        queue.enqueue("first")
        assertTrue(queue.claim())
        val launched = queue.snapshot()
        assertEquals(launched.pendingId, launched.inFlightId)
        assertFalse("Recreated activities must not launch a second dialog", queue.claim())
        assertEquals("first", queue.complete())
        assertNull(queue.snapshot().pendingId)
        assertNull(queue.snapshot().inFlightId)
        assertNull("A duplicate result must not repeat the action", queue.complete())
    }

    @Test fun canceledDialogCannotStartAConnection() {
        val queue = VpnPermissionQueue<String>()
        queue.enqueue("first")
        assertTrue(queue.claim())
        queue.cancel()
        assertNull(queue.snapshot().pendingId)
        assertTrue(queue.snapshot().inFlightId != null)
        assertNull(queue.complete())
        assertNull(queue.snapshot().inFlightId)
    }

    @Test fun lateResultDoesNotConsumeOrStartTheNewerAction() {
        val queue = VpnPermissionQueue<String>()
        queue.enqueue("old")
        assertTrue(queue.claim())
        val oldId = queue.snapshot().inFlightId
        queue.cancel()
        queue.enqueue("new")
        val newId = queue.snapshot().pendingId
        assertNotEquals(oldId, newId)
        assertFalse("Wait for the old dialog result before launching another", queue.claim())
        assertNull("An old permission result started the new connection", queue.complete())
        assertEquals(newId, queue.snapshot().pendingId)
        assertNull(queue.snapshot().inFlightId)
        assertTrue(queue.claim())
        assertEquals("new", queue.complete())
        assertNull(queue.snapshot().pendingId)
    }

    @Test fun replacingAnUnlaunchedActionKeepsOnlyTheLatest() {
        val queue = VpnPermissionQueue<String>()
        queue.enqueue("old")
        queue.enqueue("new")
        assertTrue(queue.claim())
        assertEquals("new", queue.complete())
    }
}
