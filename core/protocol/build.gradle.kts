plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `api`: Server and friends appear in this module's public signatures.
    api(project(":core:model"))
}
