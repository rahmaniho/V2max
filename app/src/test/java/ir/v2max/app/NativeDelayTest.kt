package ir.v2max.app

import ir.v2max.app.core.NativeDelay
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeDelayTest {
    @Test fun `a successful sub millisecond native probe is not treated as a failure`() {
        assertEquals(1L, NativeDelay.normalize(0L))
        assertEquals(1L, NativeDelay.normalize(1L))
        assertEquals(25L, NativeDelay.normalize(25L))
        assertEquals(-1L, NativeDelay.normalize(-1L))
        assertEquals(-1L, NativeDelay.normalize(-50L))
    }
}
