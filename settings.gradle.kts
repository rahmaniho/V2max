pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "V2max"
include(":app")

// Platform-independent core modules. These are plain JVM modules, so they build
// and test without the Android SDK.
include(
    ":core:model",
    ":core:protocol",
    ":core:config",
    ":core:domain",
    ":core:format",
    ":core:geo",
    ":core:testsuite",
)
