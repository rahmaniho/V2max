package ir.v2max.app

import ir.v2max.app.core.TunnelResourceOwnership
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelResourceOwnershipTest {
    @Test fun `a newer service owns the lifecycle gate and the old one cannot clear it`() {
        val services = TunnelResourceOwnership()
        services.claim(1)
        assertTrue(services.isOwner(1))
        services.claim(2)
        assertFalse(services.isOwner(1))
        assertFalse(services.release(1))
        assertTrue(services.isOwner(2))
        assertTrue(services.release(2))
        assertFalse(services.isOwner(2))
    }

    @Test fun `late generation cleanup cannot release the replacement core`() {
        val resources = TunnelResourceOwnership()
        resources.claim(100)
        resources.claim(101)
        assertFalse(resources.release(100))
        assertTrue(resources.isOwner(101))
        assertTrue(resources.release(101))
        assertFalse(resources.release(101))
    }
}
