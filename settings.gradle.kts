pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    plugins {
        kotlin("multiplatform") version "2.3.21"
        kotlin("jvm") version "2.3.21"
        kotlin("android") version "2.3.21"
        kotlin("plugin.compose") version "2.3.21"
        kotlin("plugin.serialization") version "2.3.21"
        id("com.android.library") version "8.13.2"
        id("com.android.application") version "8.13.2"
        id("com.android.test") version "8.13.2"
        id("org.jetbrains.compose") version "1.10.3"
        id("app.cash.sqldelight") version "2.3.2"
        id("io.github.takahirom.roborazzi") version "1.59.0"
        id("org.jetbrains.kotlinx.benchmark") version "0.4.13"
        id("io.gitlab.arturbosch.detekt") version "1.23.7"
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
        // Kotlin/WASM toolchain binaries — declared here so PREFER_SETTINGS mode resolves them
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn"
            setUrl("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy {
            name = "Binaryen"
            setUrl("https://github.com/WebAssembly/binaryen/releases/download")
            patternLayout {
                artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "stelekit"

include(":kmp")
include(":androidApp")
include(":macrobenchmark")
include(":tools:flamegraph")
