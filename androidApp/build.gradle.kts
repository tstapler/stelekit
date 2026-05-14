// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

val appVersionStr = (findProperty("appVersion") as? String ?: "0.1.0").removePrefix("v")
val versionParts = appVersionStr.split(".")
val vMajor = versionParts.getOrNull(0)?.toIntOrNull() ?: 0
val vMinor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
val vPatch = versionParts.getOrNull(2)?.toIntOrNull() ?: 0
// Encode semver as a monotonically increasing integer F-Droid uses to determine the latest APK.
// Formula: major*1_000_000 + minor*1_000 + patch (supports minor/patch up to 999).
// Coerced to ≥2 so all future APKs outrank every historical APK that was built with versionCode=1.
val computedVersionCode = (vMajor * 1_000_000 + vMinor * 1_000 + vPatch).coerceAtLeast(2)

android {
    namespace = "dev.stapler.stelekit.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.stapler.stelekit"
        minSdk = 26
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = appVersionStr
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = System.getenv("ANDROID_KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("ANDROID_STORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigningConfig = signingConfigs.getByName("release")
            // Only apply signing when keystore env vars are present (i.e. in CI release builds)
            if (releaseSigningConfig.storeFile != null) {
                signingConfig = releaseSigningConfig
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            // Non-debuggable so profiling reflects real-world performance.
            // proguardFiles keeps class names readable in Perfetto traces.
            isDebuggable = false
            proguardFiles("proguard/benchmark-rules.proguard")
            // Tell Gradle to use the :kmp release variant when resolving the benchmark
            // build type — :kmp has no benchmark variant of its own.
            matchingFallbacks += listOf("release")
        }
    }

    packaging {
        resources {
            // Both org.eclipse.jgit and org.eclipse.jgit.ssh.jsch include plugin.properties;
            // exclude it to prevent duplicate-resource merge failure.
            excludes += "plugin.properties"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        // JGit 5.13.x in :kmp uses java.time and other Java 8+ APIs — desugaring required here too
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(project(":kmp"))
    implementation("io.arrow-kt:arrow-core:2.2.1.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.6")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.6")

}
