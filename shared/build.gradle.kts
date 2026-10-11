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
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("com.squareup.okio:okio:3.10.2")
            implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
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
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("org.tukaani:xz:1.10")
                // The Claude API, for the online AI chat (AI-1).
                implementation("com.anthropic:anthropic-java:2.68.0")
            }
            kotlin.srcDir("src/jvmSharedMain/kotlin")
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
