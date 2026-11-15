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

    @Test fun `setup and unclassified errors never become deletion evidence`() {
        assertEquals(NativeDelay.INDETERMINATE, NativeDelay.outboundFailure("config load error: bad outbound"))
        assertEquals(NativeDelay.INDETERMINATE, NativeDelay.outboundFailure("instance creation failed: connection refused"))
        assertEquals(NativeDelay.INDETERMINATE, NativeDelay.outboundFailure("startup failed: i/o timeout"))
        assertEquals(NativeDelay.INDETERMINATE, NativeDelay.outboundFailure("failed to create HTTP request: bad URL"))
        assertEquals(NativeDelay.INDETERMINATE, NativeDelay.outboundFailure("Get \"https://example.org/\": unsupported protocol scheme"))
        assertEquals(NativeDelay.INDETERMINATE, NativeDelay.outboundFailure("invalid status: 403 Forbidden"))
        assertEquals(NativeDelay.INDETERMINATE, NativeDelay.outboundFailure(null))
        assertEquals(NativeDelay.NO_RESPONSE, NativeDelay.outboundFailure("Get \"https://example.org/\": context deadline exceeded"))
        assertEquals(NativeDelay.NO_RESPONSE, NativeDelay.outboundFailure("Get \"https://example.org/\": EOF"))
        assertEquals(NativeDelay.NO_RESPONSE, NativeDelay.outboundFailure("Get \"https://example.org/\": connection refused"))
        // A URL itself containing a marker must not turn an unknown failure into proof.
        assertEquals(NativeDelay.INDETERMINATE, NativeDelay.outboundFailure("Get \"https://example.org/connection%20refused\": unsupported protocol"))
    }
}
