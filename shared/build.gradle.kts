import java.util.Properties

/**
 * The app itself, shared by every platform (PLAT-1): Android (phones and tablets), desktops
 * (Windows, Mac, Linux) and iOS (iPad, iPhone). Platform parts live in androidMain, desktopMain
 * and iosMain; the Android app (:app) and desktop app (:desktop) are thin shells around it.
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

/** The version (from app/build.gradle.kts) and online Bible keys (youversion.properties), for iOS. */
val generateIosBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/iosBuildInfo")
    val appText = rootProject.file("app/build.gradle.kts").readText()
    val keys = Properties().apply {
        rootProject.file("youversion.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun key(name: String, env: String) = (keys.getProperty(name) ?: System.getenv(env) ?: "").trim()
    val values = mapOf(
        "VERSION_NAME" to Regex("versionName = \"([^\"]+)\"").find(appText)!!.groupValues[1],
        "YOUVERSION_KEY" to key("key", "YOUVERSION_KEY"),
        "ESV_KEY" to key("esv", "ESV_KEY"),
        "NLT_KEY" to key("nlt", "NLT_KEY"),
        "GOOGLE_IOS_ID" to key("google.ios.id", "GOOGLE_IOS_ID"),
    )
    val code = Regex("versionCode = (\\d+)").find(appText)!!.groupValues[1]
    inputs.property("values", values)
    inputs.property("code", code)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/biblestudy/app/IosBuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(buildString {
            appendLine("package com.biblestudy.app")
            appendLine()
            appendLine("/** Made by the build: the version and keys. */")
            appendLine("internal object IosBuildInfo {")
            appendLine("    const val VERSION_CODE = $code")
            for ((k, v) in values) appendLine("    const val $k = \"${v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}\"")
            appendLine("}")
        })
    }
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    // Desktop and iOS share a "bundled" source set (SQLite bundled with the app).
    applyDefaultHierarchyTemplate {
        common {
            group("bundled") {
                withJvm()
                group("ios") { withIos() }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("com.squareup.okio:okio:3.10.2")
            implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }
        // Android and desktop both run on the JVM and share code that needs it (the Claude Java SDK).
        androidMain.get().kotlin.srcDir("src/jvmSharedMain/kotlin")
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.15.0")
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            // Front-buffered rendering for the lowest-latency pen ink (INK-4).
            implementation("androidx.graphics:graphics-core:1.0.2")
            // Unpacks the bundled data, stored as xz to keep the app small.
            implementation("org.tukaani:xz:1.10")
            // Google sign-in for syncing through the user's Google Drive (SYNC-1).
            implementation("com.google.android.gms:play-services-auth:21.2.0")
            // The Claude API, for the online AI chat (AI-1).
            implementation("com.anthropic:anthropic-java:2.68.0")
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
            implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
        }
        // Desktop and iOS share SQLite bundled with the app (Android uses its own).
        getByName("bundledMain").dependencies { implementation("androidx.sqlite:sqlite-bundled:2.5.1") }
        // iOS gets the version and keys from a file made by the build (Android has BuildConfig).
        iosMain.get().kotlin.srcDir(generateIosBuildInfo)
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("org.tukaani:xz:1.10")
                // The Claude API, for the online AI chat (AI-1).
                implementation("com.anthropic:anthropic-java:2.68.0")
            }
            kotlin.srcDir("src/jvmSharedMain/kotlin")
            // The Bible text's typeface, from the Android resources (font/...).
            resources.srcDir("src/androidMain/res")
        }
    }
}

android {
    namespace = "com.biblestudy.app.shared"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
