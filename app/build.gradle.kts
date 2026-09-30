import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing key. Every APK must be signed with the same key, or Android refuses to
 * install it over the previous version. The key is never committed; it comes from either
 *  - keystore.properties in the project root (storeFile, storePassword, keyAlias, keyPassword), or
 *  - environment variables BIBLESTUDY_KEYSTORE_BASE64, BIBLESTUDY_KEYSTORE_PASSWORD and
 *    optionally BIBLESTUDY_KEY_ALIAS (default "biblestudy"), for cloud builds.
 * With neither, release builds fall back to the debug key (fine for a one-off test only).
 */
data class ReleaseKey(val file: File, val storePassword: String, val alias: String, val keyPassword: String)

val releaseKey: ReleaseKey? = run {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val p = Properties().apply { propsFile.inputStream().use { load(it) } }
        return@run ReleaseKey(
            rootProject.file(p.getProperty("storeFile")),
            p.getProperty("storePassword"),
            p.getProperty("keyAlias"),
            p.getProperty("keyPassword"),
        )
    }
    val encoded = System.getenv("BIBLESTUDY_KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }
    val password = System.getenv("BIBLESTUDY_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    if (encoded != null && password != null) {
        val file = layout.buildDirectory.file("signing/release.jks").get().asFile
        file.parentFile.mkdirs()
        file.writeBytes(Base64.getMimeDecoder().decode(encoded))
        return@run ReleaseKey(file, password, System.getenv("BIBLESTUDY_KEY_ALIAS") ?: "biblestudy", password)
    }
    null
}

android {
    namespace = "com.biblestudy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.biblestudy.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.0"
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey.file
                storePassword = releaseKey.storePassword
                keyAlias = releaseKey.alias
                keyPassword = releaseKey.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 strips unused code (mostly the extended icon set), shrinking the APK
            // from ~47 MB to about a third of that.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (releaseKey != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("No release signing key found; signing release with the debug key. See README.")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Print each test as it starts and finishes, so a stuck test is easy to spot.
tasks.withType<Test>().configureEach {
    testLogging {
        events("started", "passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
