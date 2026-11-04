plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Country lookup tables only — no dependencies, so any module or the app can use it.
kotlin {
    jvmToolchain(17)
}
