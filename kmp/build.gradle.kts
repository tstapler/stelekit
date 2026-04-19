// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("app.cash.sqldelight")
    id("io.github.takahirom.roborazzi") version "1.59.0"
}

kotlin {
    jvmToolchain(21)
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Configure targets
    jvm()

    if (project.findProperty("enableJs") == "true") {
        wasmJs {
            browser()
            binaries.executable()
        }
    }

    androidTarget()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Configure source sets
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Kotlinx libraries
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
                implementation("org.jetbrains:markdown:0.7.3")

                // SQLDelight
                implementation("app.cash.sqldelight:runtime:2.3.2")
                implementation("app.cash.sqldelight:coroutines-extensions:2.3.2")

                // Compose Multiplatform
                implementation("org.jetbrains.compose.runtime:runtime:1.7.3")
                implementation("org.jetbrains.compose.foundation:foundation:1.7.3")
                implementation("org.jetbrains.compose.material3:material3:1.7.3")
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation("org.jetbrains.compose.components:components-resources:1.7.3")

                // Lifecycle
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

                // Coil 3 — image loading (KMP)
                implementation("io.coil-kt.coil3:coil-compose:3.2.0")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")

                // Ktor — HTTP client (required by coil-network-ktor3)
                implementation("io.ktor:ktor-client-core:3.1.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")

                // Ksoup — HTML parsing for URL import feature
                implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
            }
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                // OpenTelemetry API — JVM/Android only (not available for wasmJs)
                implementation("io.opentelemetry:opentelemetry-api:1.43.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            }
        }

        val jvmMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("app.cash.sqldelight:sqlite-driver:2.3.2")

                // Ktor engine for JVM (used by coil-network-ktor3)
                implementation("io.ktor:ktor-client-okhttp:3.1.3")

                // OpenTelemetry SDK (JVM/Desktop) — provides span processing and export
                implementation("io.opentelemetry:opentelemetry-sdk:1.43.0")
                implementation("io.opentelemetry:opentelemetry-exporter-logging:1.43.0")

                // Graph databases for performance evaluation
                // implementation("com.kuzudb:kuzu-jdbc:0.7.0")
                // implementation("org.neo4j.driver:neo4j-java-driver:5.21.0")
                // implementation("org.neo4j:neo4j:5.21.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:1.8.0")
                implementation("io.github.takahirom.roborazzi:roborazzi-compose-desktop:1.59.0") {
                    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4-desktop")
                }
                // Ktor MockEngine for unit-testing UrlFetcherJvm without real network calls
                implementation("io.ktor:ktor-client-mock:3.1.3")
            }
        }

        if (project.findProperty("enableJs") == "true") {
            val wasmJsMain by getting {
                dependencies {
                    // Phase B: add @sqlite.org/sqlite-wasm driver here
                }
            }

            val wasmJsTest by getting {
                dependencies {
                    implementation(kotlin("test-wasm-js"))
                }
            }
        }

        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.2")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
                implementation("app.cash.sqldelight:android-driver:2.3.2")
                implementation("com.github.requery:sqlite-android:3.49.0")

                // Ktor engine for Android (used by coil-network-ktor3)
                implementation("io.ktor:ktor-client-okhttp:3.1.3")

                // DocumentFile for SAF operations
                implementation("androidx.documentfile:documentfile:1.0.1")

                // Android Compose (Jetpack)
                implementation("androidx.compose.ui:ui:1.10.6")
                implementation("androidx.compose.ui:ui-graphics:1.10.6")
                implementation("androidx.compose.material3:material3:1.4.0")

                // OpenTelemetry SDK (Android)
                implementation("io.opentelemetry:opentelemetry-sdk:1.43.0")
                implementation("io.opentelemetry:opentelemetry-exporter-logging:1.43.0")

                // JankStats — zero-allocation frame jank classification
                implementation("androidx.metrics:metrics-performance:1.0.0-beta02")
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                implementation("org.robolectric:robolectric:4.13")
                implementation("androidx.test:core:1.6.1")
                implementation("androidx.test.ext:junit:1.2.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("androidx.arch.core:core-testing:2.2.0")
            }
        }

        val iosMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("app.cash.sqldelight:native-driver:2.3.2")

                // Ktor engine for iOS (used by coil-network-ktor3)
                implementation("io.ktor:ktor-client-darwin:3.1.3")
            }
        }

        val iosTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        // Separate test source set for business logic (no UI dependencies)
        val businessTest by creating {
            dependsOn(commonTest)
        }

        jvmTest.dependsOn(businessTest)
        
        // Link iOS source sets
        // Access targets defined earlier in the kotlin block
        // targets.filter { it.platformType.name == "native" }.forEach {
        //      it.compilations.getByName("main").defaultSourceSet.dependsOn(iosMain)
        //      it.compilations.getByName("test").defaultSourceSet.dependsOn(iosTest)
        // }
    }
}

// Skiko native/JVM version alignment: compose.desktop.currentOs pins the native runtime at
// 0.8.18 (Compose plugin 1.7.3), but Coil 3 transitively pulls in compose.foundation 1.8.0
// which upgrades the Skiko JVM jars to 0.9.4. Force the native platform runtime to match so
// `RenderNodeContext_nMake` and other 0.9.x native methods are available at test time.
configurations.all {
    resolutionStrategy.force(
        "org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.9.4",
        "org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:0.9.4",
        "org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.9.4",
        "org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.9.4",
        "org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.4"
    )
}

// Configure JVM test task for Compose Desktop UI tests
tasks.named<Test>("jvmTest") {
    jvmArgs("-Djava.awt.headless=false")
    // Enable software rendering for CI environments
    environment("LIBGL_ALWAYS_SOFTWARE", System.getenv("LIBGL_ALWAYS_SOFTWARE") ?: "")
    environment("GALLIUM_DRIVER", System.getenv("GALLIUM_DRIVER") ?: "")

    // Show per-test timing and results in the console
    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
        showCauses = true
        // Report how long each test takes
        showStandardStreams = false
    }
    // Print timing after each test using a listener
    afterTest(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        val ms = result.endTime - result.startTime
        if (ms > 1000) {
            println("  SLOW (${ms}ms) ${desc.className}#${desc.name}")
        }
    }))

    // Run non-Roborazzi tests in parallel (screenshot tests require AWT and must serialize)
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

// Fast task: unit + integration tests only, excludes screenshot rendering
tasks.register<Test>("jvmTestFast") {
    group = "verification"
    description = "Run all JVM tests except Roborazzi screenshot tests"
    classpath = tasks.named<Test>("jvmTest").get().classpath
    testClassesDirs = tasks.named<Test>("jvmTest").get().testClassesDirs

    jvmArgs("-Djava.awt.headless=false")
    environment("LIBGL_ALWAYS_SOFTWARE", System.getenv("LIBGL_ALWAYS_SOFTWARE") ?: "")
    environment("GALLIUM_DRIVER", System.getenv("GALLIUM_DRIVER") ?: "")

    exclude("**/*Screenshot*", "**/*Roborazzi*", "**/screenshots/**")
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
        showExceptions = true
    }
    afterTest(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        val ms = result.endTime - result.startTime
        if (ms > 500) println("  SLOW (${ms}ms) ${desc.className}#${desc.name}")
    }))
}

compose.desktop {
    application {
        mainClass = "dev.stapler.stelekit.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "stelekit"
            // Compose Desktop requires MAJOR > 0. Map 0.x.y → 1.x.y for package metadata;
            // the public version (tag, APK, release title) remains 0.x.y.
            val rawVersion = (findProperty("appVersion") as? String ?: "0.1.0")
            val parts = rawVersion.split(".")
            packageVersion = if ((parts.firstOrNull()?.toIntOrNull() ?: 1) == 0)
                "1.${parts.drop(1).joinToString(".")}" else rawVersion
            linux {
                iconFile.set(project.file("src/jvmMain/resources/icons/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/icons/icon.png"))
            }
        }
    }
}

// Alias runApp to desktopRun for convenience
tasks.register("runApp") {
    dependsOn("run")
}

sqldelight {
    databases {
        create("SteleDatabase") {
            packageName.set("dev.stapler.stelekit.db")
        }
    }
}

android {
    compileSdk = 36
    namespace = "dev.stapler.stelekit"

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}
