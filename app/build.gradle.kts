import java.util.Base64
import java.util.Properties

buildscript {
    repositories { mavenCentral() }
    // Packs the bundled databases as xz at build time (see packAssets below).
    dependencies { classpath("org.tukaani:xz:1.10") }
}

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

/**
 * The YouVersion Platform app key for online Bibles (BIB-12). Not committed (the repository is
 * public): it comes from youversion.properties in the project root (key=…), or the environment
 * variable YOUVERSION_KEY. Without it the app builds fine and asks for a key in Settings → Bibles.
 */
val youVersionKey: String = run {
    val f = rootProject.file("youversion.properties")
    val fromFile = if (f.exists()) Properties().apply { f.inputStream().use { load(it) } }.getProperty("key") else null
    (fromFile ?: System.getenv("YOUVERSION_KEY") ?: "").trim()
}

/** The NLT API key (Tyndale), the same way: `nlt=…` in youversion.properties, or NLT_KEY. */
val nltKey: String = run {
    val f = rootProject.file("youversion.properties")
    val fromFile = if (f.exists()) Properties().apply { f.inputStream().use { load(it) } }.getProperty("nlt") else null
    (fromFile ?: System.getenv("NLT_KEY") ?: "").trim()
}

/** The ESV API key (Crossway), the same way: `esv=…` in youversion.properties, or ESV_KEY. */
val esvKey: String = run {
    val f = rootProject.file("youversion.properties")
    val fromFile = if (f.exists()) Properties().apply { f.inputStream().use { load(it) } }.getProperty("esv") else null
    (fromFile ?: System.getenv("ESV_KEY") ?: "").trim()
}

android {
    namespace = "com.biblestudy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.biblestudy.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 51
        versionName = "2.2.0"
        buildConfigField("String", "YOUVERSION_KEY", "\"$youVersionKey\"")
        buildConfigField("String", "ESV_KEY", "\"$esvKey\"")
        buildConfigField("String", "NLT_KEY", "\"$nltKey\"")
    }

    // The commentaries are already packed tightly (xz); don't zip them again.
    androidResources {
        noCompress += "xz"
    }

    // The big databases ship packed as xz from packAssets (below), not as they are in src/main/assets.
    // (The first part of the pattern is Android's default list of files to leave out.)
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:" +
            "!kjv.db:!bsb.db:!web.db:!study.db:!original.db"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // The full suite keeps several Robolectric sandboxes, each with its own copies of the
        // bundled databases; the default 512 MB heap leaves it starved and timing out.
        unitTests.all { it.maxHeapSize = "2g" }
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
    // Front-buffered rendering for the lowest-latency pen ink (INK-4).
    implementation("androidx.graphics:graphics-core:1.0.2")
    // Unpacks the bundled commentaries (STD-17), stored as xz to keep the app small.
    implementation("org.tukaani:xz:1.10")
    // The Claude API, for the online AI chat (AI-1).
    implementation("com.anthropic:anthropic-java:2.68.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * The Bibles, study data and Hebrew/Greek text are kept as plain SQLite files in src/main/assets,
 * where the tools in tools/ build and read them. The app ships them packed as xz, about a third
 * smaller than the APK's own zip compression (roughly 21 MB instead of 33 MB), and unpacks each
 * once on first use, as it already does for the commentaries (data/PackedAssets.kt).
 */
val packedAssetPaths = listOf("bibles/kjv.db", "bibles/bsb.db", "bibles/web.db", "study/study.db", "study/original.db")

abstract class PackAssets : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Input
    abstract val paths: ListProperty<String>
    @get:Internal
    abstract val root: DirectoryProperty
    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val out = outDir.get().asFile
        out.deleteRecursively()
        val root = root.get().asFile
        for (rel in paths.get()) {
            val dest = File(out, "$rel.xz").apply { parentFile.mkdirs() }
            File(root, rel).inputStream().buffered(1 shl 16).use { input ->
                dest.outputStream().buffered(1 shl 16).use { raw ->
                    org.tukaani.xz.XZOutputStream(raw, org.tukaani.xz.LZMA2Options(9)).use { input.copyTo(it, 1 shl 16) }
                }
            }
        }
    }
}

val packAssets = tasks.register<PackAssets>("packAssets") {
    paths.set(packedAssetPaths)
    root.set(layout.projectDirectory.dir("src/main/assets"))
    sources.from(packedAssetPaths.map { "src/main/assets/$it" })
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(packAssets, PackAssets::outDir) }
}

// Print each test as it starts and finishes, so a stuck test is easy to spot.
tasks.withType<Test>().configureEach {
    testLogging {
        events("started", "passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
