plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Display formatting only — no dependencies, so any module or the app can use it.
kotlin {
    jvmToolchain(17)
}
