// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

import java.io.File as IoFile
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("app.cash.sqldelight")
    id("io.github.takahirom.roborazzi") version "1.59.0"
    id("org.jetbrains.kotlinx.benchmark")
    id("io.gitlab.arturbosch.detekt")
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
                implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.13")

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

                // Encrypted SharedPreferences for API key storage
                implementation("androidx.security:security-crypto:1.1.0-alpha06")

                // On-device LLM via Gemini Nano (Pixel 9+ and AICore-enabled OEM flagships)
                implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")

                // Jetpack Glance — Compose-based home screen widget API
                // Use 1.1.1 (not 1.1.0) to pick up a protobuf security fix.
                implementation("androidx.glance:glance-appwidget:1.1.1")
                implementation("androidx.glance:glance-material3:1.1.1")
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
                implementation("androidx.glance:glance-appwidget-testing:1.1.1")
                // Roborazzi screenshot testing
                implementation("io.github.takahirom.roborazzi:roborazzi:1.59.0")
                implementation("io.github.takahirom.roborazzi:roborazzi-compose:1.59.0")
                implementation("androidx.compose.ui:ui-test-junit4:1.8.0")
                implementation("androidx.compose.ui:ui-test-manifest:1.8.0")
                implementation("androidx.activity:activity-compose:1.10.1")
            }
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("androidx.benchmark:benchmark-junit4:1.3.4")
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test:core:1.6.1")
                implementation("androidx.test.ext:junit:1.2.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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

// ── kotlinx-benchmark configuration ────────────────────────────────────────────
// Run with: ./gradlew jvmBenchmark
// Results land in: kmp/build/reports/benchmarks/
benchmark {
    targets {
        register("jvm")
    }
    configurations {
        named("main") {
            warmups = 3           // JVM warmup iterations (JIT stabilisation)
            iterations = 5        // measurement iterations
            iterationTime = 1     // seconds per iteration
            iterationTimeUnit = "s"
            // Output: ops/s (throughput) and ns/op (average time) per benchmark
        }
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

// ── graph load TTI profiling ────────────────────────────────────────────────
// Runs GraphLoadTimingTest with JFR recording enabled.
// Usage:  ./gradlew :kmp:jvmTestProfile -PgraphPath=/path/to/your/graph
// Output: kmp/build/reports/graph-load.jfr
//         kmp/build/reports/graph-load.collapsed  (CPU collapsed stacks — LLM-friendly, diffable)
//         kmp/build/reports/flamegraph.html        (interactive flamegraph)
tasks.register<Test>("jvmTestProfile") {
    group = "verification"
    description = "Profile graph load TTI with JFR. Usage: -PgraphPath=/your/graph"

    classpath = tasks.named<Test>("jvmTest").get().classpath
    testClassesDirs = tasks.named<Test>("jvmTest").get().testClassesDirs

    val graphPath = (project.findProperty("graphPath") as? String).orEmpty()
    systemProperty("STELEKIT_GRAPH_PATH", graphPath)
    systemProperty("benchmark.output.dir", layout.buildDirectory.dir("reports").get().asFile.absolutePath)

    filter {
        includeTestsMatching("dev.stapler.stelekit.benchmark.GraphLoadTimingTest")
    }

    val jfrFile = layout.buildDirectory.file("reports/graph-load.jfr").get().asFile
    jvmArgs(
        "-Djava.awt.headless=true",
        "-XX:+FlightRecorder",
        "-XX:StartFlightRecording=filename=${jfrFile.absolutePath},settings=profile,dumponexit=true",
    )

    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
        showStandardStreams = true
    }

    doLast {
        val collapsedFile = jfrFile.resolveSibling("graph-load.collapsed")
        val htmlFile = jfrFile.resolveSibling("flamegraph.html")
        println("\n── JFR written to: ${jfrFile.absolutePath}")

        // Auto-convert to collapsed stacks + HTML if jfrconv is available.
        val jfrconvPath = listOf("jfrconv", "/opt/homebrew/bin/jfrconv", "/usr/local/bin/jfrconv")
            .firstOrNull { cmd ->
                runCatching { ProcessBuilder("which", cmd).start().waitFor() == 0 }.getOrDefault(false)
            }

        // alloc profile: most useful for this IO-bound workload (JDBC/SQLite object churn dominates)
        val allocCollapsedFile = jfrFile.resolveSibling("graph-load-alloc.collapsed")
        if (jfrconvPath != null) {
            exec { commandLine(jfrconvPath, "--alloc", "-o", "collapsed", "$jfrFile", "$allocCollapsedFile"); isIgnoreExitValue = true }
            exec { commandLine(jfrconvPath, "--alloc", "$jfrFile", "$htmlFile"); isIgnoreExitValue = true }
            println("   Alloc stacks:    $allocCollapsedFile")
            println("   Flamegraph HTML: $htmlFile")
        } else {
            println("   (Install async-profiler for auto-conversion: brew install async-profiler)")
            println("   Manual: jfrconv --alloc -o collapsed $jfrFile $allocCollapsedFile")
        }

        // Generate timestamped benchmark summary JSON
        val reportsDir = layout.buildDirectory.dir("reports").get().asFile
        val scriptFile = IoFile(temporaryDir, "gen_benchmark_summary.py")
        scriptFile.writeText("""
import sys, json, os, subprocess, datetime, collections

root_dir = sys.argv[1]
reports_dir = sys.argv[2]

data = {}
for name in ["benchmark-load", "benchmark-jank"]:
    f = os.path.join(reports_dir, f"{name}.json")
    if os.path.exists(f):
        with open(f) as fh: data.update(json.load(fh))

# Allocation hotspots from alloc collapsed stacks.
# Leaf frame = rightmost semicolon-delimited token (the actual allocating site).
hotspots = []
collapsed_file = os.path.join(reports_dir, "graph-load-alloc.collapsed")
if os.path.exists(collapsed_file):
    frame_counts = collections.Counter()
    total = 0
    with open(collapsed_file) as f:
        for line in f:
            line = line.strip()
            if not line: continue
            parts = line.rsplit(" ", 1)
            if len(parts) == 2:
                stack, count_str = parts
                try: count = int(count_str)
                except ValueError: continue
                leaf = stack.split(";")[-1]
                frame_counts[leaf] += count
                total += count
    if total > 0:
        for frame, count in frame_counts.most_common(10):
            hotspots.append({"frame": frame, "samples": count, "pct": round(count * 100.0 / total, 1)})

try:
    sha = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=root_dir).decode().strip()
except Exception:
    sha = "unknown"
try:
    branch = subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=root_dir).decode().strip()
except Exception:
    branch = "unknown"

now = datetime.datetime.now(datetime.timezone.utc)
timestamp = now.strftime("%Y-%m-%d %H:%M:%S UTC")
file_slug = now.strftime("%Y-%m-%d_%Hh%Mm%Ss")

summary = {"timestamp": timestamp, "gitSha": sha, "branch": branch}
summary.update(data)
summary["allocHotspots"] = hotspots

history_dir = os.path.join(root_dir, "benchmarks", "history")
os.makedirs(history_dir, exist_ok=True)
out_file = os.path.join(history_dir, f"{file_slug}_{sha}.json")
with open(out_file, "w") as f:
    json.dump(summary, f, indent=2)
print(out_file)
""".trimIndent())
        exec {
            commandLine("python3", scriptFile.absolutePath, project.rootDir.absolutePath, reportsDir.absolutePath)
            isIgnoreExitValue = true
        }
        scriptFile.delete()
    }
}

compose.desktop {
    application {
        mainClass = "dev.stapler.stelekit.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm)
            packageName = "stelekit"
            // Compose Desktop requires MAJOR > 0. Map 0.x.y → 1.x.y for package metadata;
            // the public version (tag, APK, release title) remains 0.x.y.
            val rawVersion = (findProperty("appVersion") as? String ?: "0.1.0")
            val parts = rawVersion.split(".")
            packageVersion = if ((parts.firstOrNull()?.toIntOrNull() ?: 1) == 0)
                "1.${parts.drop(1).joinToString(".")}" else rawVersion
            modules("java.sql")
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

// ── Detekt static analysis ──────────────────────────────────────────────────
detekt {
    config.setFrom(file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    baseline = file("config/detekt/baseline.xml")
    // Analyse all KMP source sets; generated SQLDelight code is excluded via config
    source.setFrom(
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/androidMain/kotlin",
        "src/iosMain/kotlin",
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
        xml.required.set(false)
        md.required.set(false)
    }
}

// Load the custom rule set compiled from buildSrc, plus Compose-specific rules
dependencies {
    detektPlugins(files("${rootProject.projectDir}/buildSrc/build/libs/buildSrc.jar"))
    detektPlugins("io.nlopez.compose.rules:detekt:0.4.27")
}

// ── Local CI check ───────────────────────────────────────────────────────────
// Mirrors the four Gradle jobs in .github/workflows/ci.yml.
// README sync (scripts/generate-readme.sh) must be run separately.
// On headless Linux, wrap with: xvfb-run --auto-servernum ./gradlew ciCheck
tasks.register("ciCheck") {
    group = "verification"
    description = "Run all Gradle CI checks locally (detekt + jvmTest + Android unit tests + assembleDebug)"
    dependsOn(":kmp:detekt", ":kmp:jvmTest", ":kmp:testDebugUnitTest", ":androidApp:assembleDebug")
}

// ── always-on JFR profiling for desktop run ─────────────────────────────────
// Every `./gradlew :kmp:run` records a JFR session and converts it to collapsed
// stacks (CPU + alloc) via a finalizer task that runs even on Ctrl+C.
//
// Output: kmp/build/profiles/run-<timestamp>.jfr
//         kmp/build/profiles/run-<timestamp>-cpu.collapsed
//         kmp/build/profiles/run-<timestamp>-alloc.collapsed
//
// convertLastProfile can also be invoked manually:
//   ./gradlew :kmp:convertLastProfile
afterEvaluate {
    val profilesDir = layout.buildDirectory.dir("profiles").get().asFile
    // Pointer file written by the run task's doFirst so the finalizer can find
    // the JFR regardless of whether doLast ran.
    val latestJfrPointer = IoFile(profilesDir, "latest.jfr.path")

    val jfrconvPath = listOf("jfrconv", "/opt/homebrew/bin/jfrconv", "/usr/local/bin/jfrconv")
        .firstOrNull { cmd -> runCatching { ProcessBuilder("which", cmd).start().waitFor() == 0 }.getOrDefault(false) }

    val asyncProfilerLib = listOf(
        "/home/linuxbrew/.linuxbrew/lib/libasyncProfiler.so",
        "/usr/local/lib/libasyncProfiler.so",
        "/opt/homebrew/lib/libasyncProfiler.so",
        "/opt/homebrew/lib/libasyncProfiler.dylib",
        // IntelliJ IDEA bundled async-profiler (JetBrains Toolbox install)
        System.getProperty("user.home") + "/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate/lib/async-profiler/amd64/libasyncProfiler.so",
        System.getProperty("user.home") + "/.local/share/JetBrains/Toolbox/apps/intellij-idea-ce/lib/async-profiler/amd64/libasyncProfiler.so",
    ).firstOrNull { IoFile(it).exists() }

    // ── conversion finalizer (runs even on Ctrl+C) ───────────────────────────
    val convertLastProfile = tasks.register("convertLastProfile") {
        group = "profiling"
        description = "Convert the most recent JFR profile to collapsed stacks. Runs automatically after :run."
        doLast {
            val jfr = latestJfrPointer.takeIf { it.exists() }
                ?.readText()?.trim()?.let { IoFile(it) }
                ?: (profilesDir.listFiles { f: IoFile -> f.extension == "jfr" }
                    ?.maxByOrNull { it.name })
            if (jfr == null || !jfr.exists()) {
                println("── convertLastProfile: no JFR file found in $profilesDir")
                return@doLast
            }

            println("── JFR saved: ${jfr.absolutePath}  (${jfr.length() / 1024}KB)")

            if (jfrconvPath == null) {
                println("── (Install async-profiler for conversion: brew install async-profiler)")
                return@doLast
            }

            val stem = jfr.nameWithoutExtension
            val cpuCollapsed   = jfr.resolveSibling("$stem-cpu.collapsed")
            val allocCollapsed = jfr.resolveSibling("$stem-alloc.collapsed")

            // --cpu reads profiler.ExecutionSample (async-profiler agent events).
            // If the agent wasn't active the file will be empty — detect and warn.
            exec { commandLine(jfrconvPath, "--cpu",   "-o", "collapsed", "$jfr", "$cpuCollapsed");   isIgnoreExitValue = true }
            if (cpuCollapsed.length() == 0L) {
                cpuCollapsed.delete()
                println("── CPU stacks:   (empty — async-profiler agent was not active)")
            } else {
                println("── CPU stacks:   $cpuCollapsed")
            }

            exec { commandLine(jfrconvPath, "--alloc", "-o", "collapsed", "$jfr", "$allocCollapsed"); isIgnoreExitValue = true }
            if (allocCollapsed.exists() && allocCollapsed.length() > 0) println("── Alloc stacks: $allocCollapsed")

            // Prune: keep the 20 most recent .jfr files and their collapsed siblings.
            val allJfr = (profilesDir.listFiles { f: IoFile -> f.extension == "jfr" } ?: emptyArray())
                .sortedByDescending { it.name }
            allJfr.drop(20).forEach { old ->
                old.delete()
                (profilesDir.listFiles { f: IoFile -> f.name.startsWith(old.nameWithoutExtension) && f != old } ?: emptyArray())
                    .forEach { it.delete() }
            }
        }
    }

    tasks.named<JavaExec>("run") {
        // finalizedBy runs convertLastProfile even if run fails or is cancelled (Ctrl+C).
        finalizedBy(convertLastProfile)

        doFirst {
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())
            val jfr = IoFile(profilesDir, "run-$ts.jfr").also { it.parentFile.mkdirs() }

            // Write pointer file before the JVM starts so the finalizer can find
            // the JFR path even if the process is killed before doLast.
            latestJfrPointer.writeText(jfr.absolutePath)

            val jfrArgs = mutableListOf(
                "-XX:+FlightRecorder",
                // disk=true writes repository chunks continuously — data survives SIGKILL.
                "-XX:StartFlightRecording=filename=${jfr.absolutePath},settings=profile,disk=true,dumponexit=true",
            )
            if (asyncProfilerLib != null) {
                // wall-clock sampling captures all threads regardless of CPU state,
                // exposing DB waits, IO blocks, and lock contention that cpu-mode misses.
                // jfrsync injects profiler.ExecutionSample into the JFR stream so
                // jfrconv --cpu reads the wall samples alongside JFR built-in events.
                jfrArgs += "-agentpath:$asyncProfilerLib=start,event=wall,jfrsync=profile"
                println("── async-profiler wall-clock agent active")
            } else {
                println("── async-profiler not found; CPU profile will be empty (install: brew install async-profiler)")
            }
            jvmArgs(jfrArgs)
            println("── JFR profiling active → ${jfr.absolutePath}")
        }
    }
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
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}
