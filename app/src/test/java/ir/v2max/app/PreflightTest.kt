package ir.v2max.app

import ir.v2max.app.core.PreflightTickets
import ir.v2max.app.core.VerifiedSelection
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.FragmentProfile
import ir.v2max.app.data.ServerConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PreflightTest {
    private val tested = ServerConfig(
        id = "one", address = "example.org", uuid = "test", realPing = 42,
        testedUrl = "https://example.org/probe", testedFragment = true,
        testedFragmentProfile = FragmentProfile.MAHSA
    )
    private val options = AppSettings(deepProbe = false, multiPing = true, speedTest = true)

    @After fun cleanup() = PreflightTickets.revokeAll()

    @Test fun `receipt is bound to the exact profile settings network and a short lifetime`() {
        val now = System.nanoTime()
        val receipt = VerifiedSelection.create(tested, listOf(tested), options, 17L, now)
        assertTrue(receipt.validFor(tested, options, 17L, now))
        assertFalse(receipt.validFor(null, options, 17L, now))
        assertFalse(receipt.validFor(tested.copy(address = "changed.example.org"), options, 17L, now))
        assertFalse(receipt.validFor(tested, options.copy(speedTest = false), 17L, now))
        assertFalse(receipt.validFor(tested, options.copy(multiPing = false), 17L, now))
        assertFalse(receipt.validFor(tested, options, 18L, now))
        assertFalse(receipt.validFor(tested, options, null, now))
        assertFalse(receipt.validFor(tested, options, 17L, now - 1))
        assertFalse(receipt.validFor(tested, options, 17L, now + TimeUnit.MINUTES.toNanos(3)))
        assertFalse(VerifiedSelection.create(tested, listOf(tested), options, null, now)
            .validFor(tested, options, null, now))
    }

    @Test fun `ticket is one shot and cancellation revokes it`() {
        val receipt = VerifiedSelection.create(tested, listOf(tested), options, 17L)
        val ticket = PreflightTickets.issue(receipt)
        assertSame(receipt, PreflightTickets.take(ticket))
        assertNull(PreflightTickets.take(ticket))
        val cancelled = PreflightTickets.issue(receipt)
        PreflightTickets.revokeAll()
        assertNull(PreflightTickets.take(cancelled))
    }

    @Test fun `receipt owns its tested copies`() {
        val source = tested.copy()
        val receipt = VerifiedSelection.create(source, listOf(source), options, 17L)
        source.address = "edited.example.org"
        val returned = receipt.server()
        returned.address = "another.example.org"
        assertEquals("example.org", receipt.server().address)
        val pool = receipt.pool()
        pool.single().address = "pool-edited.example.org"
        assertEquals("example.org", receipt.pool().single().address)
        assertTrue(receipt.validFor(tested, options, 17L))
    }
}
