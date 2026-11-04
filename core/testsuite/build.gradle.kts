plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(project(":core:model"))
    testImplementation(project(":core:protocol"))
    testImplementation(project(":core:config"))
    testImplementation(project(":core:domain"))
    testImplementation("junit:junit:4.13.2")
}

// The suite prints its own progress and failure detail, so surface stdout in
// the build log rather than only reporting a bare pass/fail count.
tasks.test {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
