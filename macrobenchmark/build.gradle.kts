// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

plugins {
    id("com.android.test")
    kotlin("android")
}

android {
    namespace = "dev.stapler.stelekit.macrobenchmark"
    compileSdk = 36

    // Instrumentation target — the app APK built by :androidApp
    targetProjectPath = ":androidApp"

    // Self-instrumentation: runs the benchmark in the macrobenchmark process instead of the
    // target app's process. Required because MacrobenchmarkRule unconditionally calls
    // `am force-stop dev.stapler.stelekit` even with CompilationMode.None(); in
    // NOT-SELF-INSTRUMENTING mode the runner shares dev.stapler.stelekit's process and the
    // force-stop kills itself ("Instrumentation run failed due to Process crashed").
    //
    // With self-instrumentation, AGP sets <instrumentation android:targetPackage> to the
    // macrobenchmark APK's own package and includes all dependencies (including kotlin-stdlib)
    // in the test APK — unlike the default com.android.test behavior which strips shared deps.
    // See: benchmark-macro-junit4 NOT-SELF-INSTRUMENTING error message for official guidance.
    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 26
        // targetSdk must match compileSdk (not default to minSdk) to avoid the
        // "built for an older version of Android" compatibility dialog that Android
        // shows for apps with targetSdkVersion < threshold. Without this, the dialog
        // appears when IsolationActivity resumes between benchmark iterations and blocks
        // UIAutomator from interacting with the device.
        targetSdk = 36
        // AndroidBenchmarkRunner disables system animations and CPU frequency scaling
        // so measurements are stable and comparable across runs.
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // Suppress EMULATOR error so benchmarks run on emulators (results less stable than
        // real hardware but useful for catching regressions in CI).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            // matchingFallbacks tells Gradle to use the :androidApp "release" variant
            // when no matching "benchmark" variant exists in a dependency.
            matchingFallbacks += listOf("release")
        }
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
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    // benchmark-junit4 provides AndroidBenchmarkRunner which must be present in the
    // macrobenchmark APK's DEX — it is not bundled inside benchmark-macro-junit4.
    implementation("androidx.benchmark:benchmark-junit4:1.4.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    // Force lifecycle to >= 2.7 so benchmark-junit4 1.4.x can access ReportFragment.Companion.
    // Without this, uiautomator/test-runner pull in lifecycle 2.3.1 which lacks the field.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
