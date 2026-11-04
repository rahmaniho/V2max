package ir.v2max.core.test

/**
 * A tiny assertion runner.
 *
 * JUnit is published on Maven Central, which is not reachable from this
 * environment, and the core modules are meant to stay dependency-free anyway.
 * This keeps the suite runnable with nothing but kotlinc and a JRE.
 *
 * Every check records a pass or a failure and the process exits non-zero if any
 * check failed, so CI can treat this like a normal test task.
 */
object T {
    private var passed = 0
    private val failures = mutableListOf<String>()
    private var group = ""

    fun group(name: String) {
        group = name
        println("\n$name")
    }

    fun eq(name: String, expected: Any?, actual: Any?) {
        if (expected == actual) {
            passed++
        } else {
            failures.add("$group / $name\n     expected: $expected\n     actual:   $actual")
            println("  FAIL $name")
        }
    }

    fun ok(name: String, condition: Boolean, detail: () -> String = { "" }) {
        if (condition) {
            passed++
        } else {
            failures.add("$group / $name\n     ${detail()}")
            println("  FAIL $name")
        }
    }

    fun <T> notNull(name: String, value: T?): T? {
        if (value != null) {
            passed++
        } else {
            failures.add("$group / $name\n     was null")
            println("  FAIL $name (null)")
        }
        return value
    }

    fun report(): Int {
        println("\n" + "=".repeat(62))
        if (failures.isEmpty()) {
            println("PASSED — $passed check(s), 0 failed")
            return 0
        }
        println("FAILED — $passed passed, ${failures.size} failed\n")
        failures.forEachIndexed { i, f -> println("  ${i + 1}. $f") }
        return 1
    }
}

/**
 * Runs every suite and returns the number of failed checks (0 means all passed).
 *
 * Shared by two entry points: [main], used by `tools/jvm.sh` for a fast local
 * loop with no build system, and [CoreSuiteTest], which lets Gradle run the same
 * suite under `gradle test` without duplicating the checks.
 */
fun runAllSuites(): Int {
    println("V2max core — JVM test suite")
    println("=".repeat(62))

    EndpointTests.run()
    JsonTests.run()
    ScoringTests.run()
    LinkParserTests.run()
    RoundTripTests.run()
    XrayConfigTests.run()
    ServerSelectorTests.run()
    ServerListPolicyTests.run()
    SubscriptionMergerTests.run()
    SubscriptionUsageTests.run()
    FormatTests.run()
    CountryFlagsTests.run()
    GeoDataTagsTests.run()
    ConnectionAdviceTests.run()

    return T.report()
}

fun main() {
    kotlin.system.exitProcess(runAllSuites())
}
