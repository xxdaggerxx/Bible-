pluginManagement {
    repositories {
        // Google's copy of Maven Central first: Maven Central itself often refuses busy build
        // machines (429 Too Many Requests).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
    }
}
rootProject.name = "BibleStudy"
include(":app", ":shared")
