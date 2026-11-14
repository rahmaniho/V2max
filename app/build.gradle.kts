import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// libv2ray (Xray-core) is a ~60 MB prebuilt AAR published by 2dust.
// It is not committed to this repository; the task below downloads it once.
// ---------------------------------------------------------------------------
val libV2rayVersion = "v26.10.21"
val libV2rayUrl =
    "https://github.com/2dust/AndroidLibXrayLite/releases/download/$libV2rayVersion/libv2ray.aar"
val libsDirProvider = layout.projectDirectory.dir("libs")
val libV2rayAar = libsDirProvider.file("libv2ray.aar")
val libV2raySha256 = "e56ec3b06485fe7b37af26948673951edf719c50d6bbc618b8539045952adde4"

fun matchesLibV2rayDigest(file: File): Boolean = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == libV2raySha256
}.getOrDefault(false)

val downloadLibV2ray by tasks.registering {
    description = "Downloads the Xray-core native library (libv2ray.aar) if it is missing"
    val target = libV2rayAar.asFile
    outputs.file(target)
    outputs.upToDateWhen { target.isFile && matchesLibV2rayDigest(target) }
    doLast {
        if (target.isFile && matchesLibV2rayDigest(target)) {
            logger.lifecycle("libv2ray.aar already verified (${target.length()} bytes)")
            return@doLast
        }
        target.delete()
        target.parentFile?.mkdirs()
        logger.lifecycle("Downloading $libV2rayUrl ...")
        val tmp = File(target.parentFile, "libv2ray.aar.part")
        URI(libV2rayUrl).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!matchesLibV2rayDigest(tmp)) {
            tmp.delete()
            throw GradleException("Downloaded libv2ray.aar SHA-256 did not match the pinned release")
        }
        if (!tmp.renameTo(target)) throw GradleException("Could not move verified libv2ray.aar into place")
        logger.lifecycle("libv2ray.aar verified (${target.length()} bytes)")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadLibV2ray)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
// Local developers may use an ignored keystore.properties file or environment
// variables. CI builds with a disposable debug key; approved releases are
// re-signed after tests, in a separate job that does not execute Gradle.
fun signingValue(environment: String, property: String): String? =
    System.getenv(environment)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(property)
val signingStoreFile = signingValue("V2MAX_RELEASE_KEYSTORE", "storeFile")
// CI creates this separate, throwaway key only for the isolated preview APK.
val previewStoreFile = System.getenv("V2MAX_PREVIEW_KEYSTORE")?.takeIf { it.isNotBlank() }

// Optional, debug-only version bump for the disposable emulator update test.
// It never changes release variants or the package installed on real users' devices.
val appVersionCode = 23
val emulatorUpgradeCode = providers.gradleProperty("v2maxEmulatorUpgradeCode").orNull?.let { raw ->
    raw.toIntOrNull()?.takeIf { it > appVersionCode && it <= 2_100_000_000 }
        ?: throw GradleException("v2maxEmulatorUpgradeCode must be greater than $appVersionCode")
}

android {
    namespace = "ir.v2max.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.v2max.app"
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = appVersionCode
        versionName = "2.4.0"
        resourceConfigurations += listOf("fa", "en")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(signingStoreFile)
                storePassword = signingValue("V2MAX_RELEASE_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("V2MAX_RELEASE_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("V2MAX_RELEASE_KEY_PASSWORD", "keyPassword")
            }
        }
        if (previewStoreFile != null) {
            create("preview") {
                storeFile = rootProject.file(previewStoreFile)
                storePassword = System.getenv("V2MAX_PREVIEW_STORE_PASSWORD")
                keyAlias = System.getenv("V2MAX_PREVIEW_KEY_ALIAS")
                keyPassword = System.getenv("V2MAX_PREVIEW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // کاهش حجم و بهینه‌سازی: R8 + حذف منابع بی‌استفاده
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            if (emulatorUpgradeCode != null) versionNameSuffix = "-emulator-upgrade"
        }
        create("preview") {
            // An isolated, non-debuggable app for voluntary device testing. It is
            // never an update of the official package and never uses the release key.
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.findByName("preview") ?: signingConfigs.getByName("debug")
            isDebuggable = false
            // Keep the instrumentation target unobfuscated. The production release
            // is still built and checked separately with R8/resource shrinking.
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }

    // Only the dedicated preview smoke invocation changes the instrumentation
    // target; all existing Android tests continue to target the debug variant.
    testBuildType = if (providers.gradleProperty("v2maxPreviewInstrumentation").orNull == "true") "preview" else "debug"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        emulatorUpgradeCode?.let { code -> variant.outputs.forEach { it.versionCode.set(code) } }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // Pure-JVM core modules. Migrated incrementally: each call site moves over
    // and its old duplicate in app/data, app/net or app/core is deleted, so the
    // two implementations never coexist for the same responsibility.
    implementation(project(":core:model"))
    implementation(project(":core:format"))
    implementation(project(":core:domain"))
    implementation(project(":core:geo"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("junit:junit:4.13.2")
    // Test-only on purpose. ConfigGeneratorTests compares core's XrayConfig
    // against the app's ConfigBuilder to prove they agree before any call site
    // moves. Adding it to `implementation` would put two config generators in
    // main, which the convention above forbids; when the app actually switches,
    // this becomes `implementation` and ConfigBuilder is deleted in the same
    // commit.
    testImplementation(project(":core:config"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // دوربین و اسکن QR برای افزودن اشتراک
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    // به‌روزرسانی پس‌زمینه‌ی اشتراک‌ها
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
