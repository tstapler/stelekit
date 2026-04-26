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
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(project(":kmp"))
    implementation("io.arrow-kt:arrow-core:2.2.1.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
}
