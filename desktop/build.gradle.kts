import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

/** The same version as the Android app (app/build.gradle.kts), so each release is one version everywhere. */
val appVersion: Pair<String, Int> = run {
    val text = rootProject.file("app/build.gradle.kts").readText()
    val name = Regex("versionName = \"([^\"]+)\"").find(text)!!.groupValues[1]
    val code = Regex("versionCode = (\\d+)").find(text)!!.groupValues[1].toInt()
    name to code
}

/** The online Bible keys (and Google's sign-in for computers), from youversion.properties as on Android. */
val keys: Properties = Properties().apply {
    val f = rootProject.file("youversion.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String, env: String) = (keys.getProperty(name) ?: System.getenv(env) ?: "").trim()

val generatedSrc = layout.buildDirectory.dir("generated/buildInfo")
val generateBuildInfo by tasks.registering {
    val out = generatedSrc
    val values = mapOf(
        "VERSION_NAME" to appVersion.first,
        "YOUVERSION_KEY" to key("key", "YOUVERSION_KEY"),
        "ESV_KEY" to key("esv", "ESV_KEY"),
        "NLT_KEY" to key("nlt", "NLT_KEY"),
        "GOOGLE_DESKTOP_ID" to key("google.desktop.id", "GOOGLE_DESKTOP_ID"),
        "GOOGLE_DESKTOP_SECRET" to key("google.desktop.secret", "GOOGLE_DESKTOP_SECRET"),
    )
    inputs.property("values", values)
    inputs.property("code", appVersion.second)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/biblestudy/app/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(buildString {
            appendLine("package com.biblestudy.app.desktop")
            appendLine()
            appendLine("/** Made by the build: the version and keys. */")
            appendLine("object BuildInfo {")
            appendLine("    const val VERSION_CODE = ${appVersion.second}")
            for ((k, v) in values) appendLine("    const val $k = \"${v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}\"")
            appendLine("}")
        })
    }
}

/** The app's bundled data (Bibles, study notes, help…), shared with Android, plus a list of it. */
val assetsOut = layout.buildDirectory.dir("generated/assets")
val copyAssets by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/assets")) { into("assets") }
    into(assetsOut)
    doLast {
        val root = assetsOut.get().dir("assets").asFile
        val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
        File(root, "index.txt").writeText(files.joinToString("\n"))
    }
}

sourceSets {
    main {
        kotlin.srcDir(generateBuildInfo)
        resources.srcDir(copyAssets)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.biblestudy.app.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Ink and Word"
            packageVersion = appVersion.first
            description = "Read, study and write in the Bible"
            vendor = "Ink and Word"
            // The Java parts the app uses (sound, the database driver, secure web requests, file dialogs).
            modules("java.desktop", "java.net.http", "java.sql", "jdk.unsupported", "java.naming", "jdk.crypto.ec", "jdk.charsets")
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Ink and Word"
                perUserInstall = true
                shortcut = true
                // Keeps the same id across versions so installing a new one replaces the old.
                upgradeUuid = "6f1f3c2e-6a8e-4b8f-9a8f-3e2b5c7d9a10"
            }
            macOS {
                iconFile.set(project.file("icons/icon.icns"))
                bundleID = "com.biblestudy.app"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                packageName = "ink-and-word"
            }
        }
    }
}
