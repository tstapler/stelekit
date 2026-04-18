pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    plugins {
        kotlin("multiplatform") version "2.3.10"
        kotlin("android") version "2.3.10"
        kotlin("plugin.compose") version "2.3.10"
        kotlin("plugin.serialization") version "2.3.10"
        id("com.android.library") version "8.9.1"
        id("com.android.application") version "8.9.1"
        id("org.jetbrains.compose") version "1.7.3"
        id("app.cash.sqldelight") version "2.3.2"
        id("io.github.takahirom.roborazzi") version "1.59.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://repo.clojars.org/")
        // Required for Kotlin/JS: Node.js and Yarn binary downloads (PREFER_SETTINGS blocks
        // the project-level ivy repos that the Kotlin/JS plugin registers automatically)
        ivy("https://nodejs.org/dist/") {
            content { includeModule("org.nodejs", "node") }
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download/") {
            content { includeModule("com.yarnpkg", "yarn") }
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
        }
    }
}

rootProject.name = "stelekit"

include(":kmp")
include(":androidApp")
