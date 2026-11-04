package ir.v2max.core.test

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bridges the hand-rolled suite into Gradle's `test` task.
 *
 * The checks themselves live in [runAllSuites] so that `tools/jvm.sh` and
 * `gradle test` exercise exactly the same code — there is no second, possibly
 * drifting, copy of the assertions. Failures are printed to stdout by the
 * runner before this assertion trips, so the report shows which check broke.
 */
class CoreSuiteTest {

    @Test
    fun allCoreChecksPass() {
        val failures = runAllSuites()
        assertEquals("core suite reported failing checks (see stdout above)", 0, failures)
    }
}
