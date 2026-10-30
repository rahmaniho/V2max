import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

// ---------------------------------------------------------------------------
// libbox (sing-box, gomobile) — هسته‌ی دوم برای پروتکل‌هایی که Xray پشتیبانی نمی‌کند:
// Hysteria2 / TUIC / WireGuard / AnyTLS
// ---------------------------------------------------------------------------
val libBoxVersion = "1.14.4"
val libBoxUrl =
    "https://github.com/singbox-android/libbox/releases/download/$libBoxVersion/libbox.aar"
val libBoxAar = libsDirProvider.file("libbox.aar")

val downloadLibBox by tasks.registering {
    description = "Downloads the sing-box native library (libbox.aar) if it is missing"
    val target = libBoxAar.asFile
    outputs.file(target)
    doLast {
        if (target.exists() && target.length() > 10_000_000L) {
            logger.lifecycle("libbox.aar already present (${target.length()} bytes)")
            return@doLast
        }
        target.parentFile?.mkdirs()
        logger.lifecycle("Downloading $libBoxUrl ...")
        val tmp = File(target.parentFile, "libbox.aar.part")
        URI(libBoxUrl).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (tmp.length() < 10_000_000L) {
            tmp.delete()
            throw GradleException("Download of libbox.aar failed (file too small)")
        }
        tmp.renameTo(target)
        logger.lifecycle("libbox.aar ready (${target.length()} bytes)")
    }
}

// ---------------------------------------------------------------------------
// libbox و libv2ray هر دو با gomobile ساخته شده‌اند و کلاس‌های مشترک go.Seq را
// داخل classes.jar خود دارند؛ Android Gradle Plugin اجازه‌ی وجود کلاس تکراری
// نمی‌دهد. جدیدترین نسخه‌ی go.Seq (از libv2ray) نگه داشته می‌شود و نسخه‌ی
// قدیمی‌تر از libbox حذف می‌شود (gomobile در این بخش فقط افزودنی است).
// ---------------------------------------------------------------------------
fun stripGoClassesFromClassesJar(bytes: ByteArray, removed: IntArray): ByteArray {
    val output = ByteArrayOutputStream(bytes.size)
    ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
        ZipOutputStream(output).use { out ->
            var entry: ZipEntry? = input.nextEntry
            val buffer = ByteArray(64 * 1024)
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && (name == "go/" || name.startsWith("go/"))) {
                    removed[0]++
                } else {
                    out.putNextEntry(ZipEntry(name))
                    if (!entry.isDirectory) {
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                    out.closeEntry()
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }
    return output.toByteArray()
}

fun stripDuplicateGoClasses(aar: File): Int {
    if (!aar.exists() || aar.length() < 1_000_000L) return 0
    val temp = File(aar.parentFile, aar.name + ".tmp")
    val removed = IntArray(1)
    ZipFile(aar).use { zip ->
        ZipOutputStream(temp.outputStream().buffered()).use { out ->
            val entries = zip.entries()
            var wrote = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val raw = zip.getInputStream(entry).use { it.readBytes() }
                val data = if (entry.name == "classes.jar") {
                    stripGoClassesFromClassesJar(raw, removed)
                } else {
                    raw
                }
                out.putNextEntry(ZipEntry(entry.name))
                out.write(data)
                out.closeEntry()
                wrote++
            }
            if (wrote == 0) return 0
        }
    }
    if (removed[0] > 0) {
        if (!temp.renameTo(aar)) {
            aar.delete()
            temp.renameTo(aar)
        }
    } else {
        temp.delete()
    }
    return removed[0]
}

val stripLibBoxDuplicates by tasks.registering {
    description = "Removes gomobile go.* classes from libbox.aar that libv2ray.aar already provides"
    dependsOn(downloadLibBox)
    dependsOn(downloadLibV2ray)
    doLast {
        val removed = stripDuplicateGoClasses(libBoxAar.asFile)
        if (removed > 0) {
            logger.lifecycle("libbox.aar: removed $removed duplicate gomobile classes")
        } else {
            logger.lifecycle("libbox.aar: no duplicate gomobile classes left to strip")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(stripLibBoxDuplicates)
}

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
        versionCode = 16
        versionName = "2.0.0"
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
            // کاهش حجم و بهینه‌سازی: R8 + حذف منابع بی‌استفاده
            isMinifyEnabled = true
            isShrinkResources = true
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
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("junit:junit:4.13.2")
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
