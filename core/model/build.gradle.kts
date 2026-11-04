plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Domain types only — no dependencies at all, so every other module can rely on
// it without pulling anything in.
kotlin {
    jvmToolchain(17)
}
