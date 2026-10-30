import java.io.File
import java.net.URI
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

val downloadLibV2ray by tasks.registering {
    description = "Downloads the Xray-core native library (libv2ray.aar) if it is missing"
    val target = libV2rayAar.asFile
    outputs.file(target)
    doLast {
        if (target.exists() && target.length() > 1_000_000L) {
            logger.lifecycle("libv2ray.aar already present (${target.length()} bytes)")
            return@doLast
        }
        target.parentFile?.mkdirs()
        logger.lifecycle("Downloading $libV2rayUrl ...")
        val tmp = File(target.parentFile, "libv2ray.aar.part")
        URI(libV2rayUrl).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (tmp.length() < 1_000_000L) {
            tmp.delete()
            throw GradleException("Download of libv2ray.aar failed (file too small)")
        }
        tmp.renameTo(target)
        logger.lifecycle("libv2ray.aar ready (${target.length()} bytes)")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadLibV2ray) }

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "ir.v2max.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.v2max.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.6.0"
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
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

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

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation("androidx.core:core-ktx:1.15.0")
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
