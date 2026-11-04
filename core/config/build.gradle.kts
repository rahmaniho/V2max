plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))

    // Only the JSON reader is used, and only to validate embedded fragments
    // before splicing them in; it does not leak into this module's API.
    implementation(project(":core:protocol"))
}
