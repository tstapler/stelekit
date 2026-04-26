# Findings: Stack — Android On-Device Benchmark Infrastructure

## Summary

For measuring save/parse latency on Android and gating it in CI, the right choice is **`androidx.benchmark:benchmark-junit4` (Microbenchmark) running as an Android instrumentation test** — not Macrobenchmark. Microbenchmark directly invokes `commonMain`/`androidMain` Kotlin code without indirection, provides statistically sound sub-millisecond timing via `BenchmarkRule` + `measureRepeated()`, runs on the `ubuntu-latest` GitHub Actions emulator with `reactivecircus/android-emulator-runner`, and can be added to the existing `kmp` library module without creating a new `:benchmark` app module.

Macrobenchmark is designed for end-to-end app startup from a separate test process; it cannot call `GraphWriter` or `GraphLoader` directly and is overkill for sub-component latency. Plain `System.nanoTime()` instrumentation tests are workable but lack warm-up control, outlier suppression, and JSON output. Android Profiler/Systrace is manual-only with no CI story.

**Bridge step**: Start with a plain `@RunWith(AndroidJUnit4::class)` instrumented test (0.5 days) to confirm the problem reproduces, then upgrade to `BenchmarkRule` for the regression gate.

## Options Surveyed

1. `androidx.benchmark:benchmark-macro` (Macrobenchmark) — measures startup/UI from a separate process
2. `androidx.benchmark:benchmark-junit4` (Microbenchmark) — in-process timing with `BenchmarkRule`
3. Plain `@Test` instrumentation tests with `System.nanoTime()`
4. Android Profiler / Systrace (manual)

## Trade-off Matrix

| Axis | Macrobenchmark | Microbenchmark | Plain nanoTime() | Profiler/Systrace |
|------|----------------|----------------|------------------|-------------------|
| Setup complexity (KMP) | High — new module, profileable variant, IPC wrappers | Low-Medium — new source set in `:kmp`, 1–2 dependencies | Low — port existing JVM test | N/A (manual) |
| CI integration | Hard — Play image required; separate APK | Medium — `android-emulator-runner` standard AVD | Medium — same runner | Not CI-gateable |
| On-device measurement accuracy | Highest (full process) | High — warm-up controlled, GC suppressed | Medium — JIT noise, GC unchecked | Highest but offline |
| Sub-500ms accuracy | Poor — measures app launch, not sub-component | Good — `measureRepeated` handles JIT + GC | Fair — sufficient with large N | Good but offline |
| KMP compatibility (calls `commonMain` directly) | No — cross-process, needs app-side shim | Yes — direct call in test process | Yes | Partial |
| JSON CI output | Yes (standard benchmark JSON) | Yes (standard benchmark JSON) | Only with manual serialization | No |
| Allocation profiling | Yes | Yes (built-in to `measureRepeated`) | No | Yes |
| New Gradle module required | Yes (`:macrobenchmark`) | No — lives in `:kmp` `androidInstrumentedTest` | No | N/A |
| Regression gate | Yes | Yes | Yes with manual parsing | No |

## Risk and Failure Modes

### Microbenchmark (recommended)

1. **Emulator clock instability** — `BenchmarkRule` marks results "unstable" in JSON if the host is under CPU contention. Mitigation: set `android:debuggable="false"` in the benchmark build type or accept wider thresholds. [TRAINING_ONLY — verify non-debuggable requirement applies to emulator runs]
2. **`DriverFactory` context injection** — `DriverFactory.setContext(context)` must be called in `@Before` using `ApplicationProvider.getApplicationContext()`.
3. **SAF paths unavailable in test process** — `PlatformFileSystem` routes SAF URIs through `ContentResolver`. The SAF ContentProvider is not registered in the test process. Mitigation: use direct paths with `filesDir` for benchmark tests (same approach as `PlatformFileSystemSafTest.kt`).
4. **`SyntheticGraphGenerator` source set access** — currently in `jvmTest`, uses `java.io.File`. Must move to `businessTest` or be duplicated to reach from `androidInstrumentedTest`.
5. **`DatabaseWriteActor` scope leaks** — call `scope.cancel()` in `@After` between `measureRepeated` iterations.

### Macrobenchmark (not recommended)

1. Cannot call `GraphWriter` directly — cross-process. Requires app-side BroadcastReceiver/ContentProvider shim.
2. Google Play system image may be required for profiling hooks on emulator. [TRAINING_ONLY — verify]
3. Adds `profileable` build type complexity to `:androidApp`.

### Plain `nanoTime()` Tests

1. JIT dominates first iterations without controlled warm-up — first 10–50 samples can be 5–50x slower.
2. No GC suppression — 10–50ms outliers inflate p99 for allocation-heavy paths.
3. No standard JSON output format — requires manual serialization.

## Migration and Adoption Cost

**Gradle changes** (`kmp/build.gradle.kts`):
- Add `androidInstrumentedTest` source set
- Add `benchmark-junit4:1.3.4` and `androidx.test:runner` dependencies

**New source file**: `kmp/src/androidInstrumentedTest/kotlin/.../benchmark/AndroidGraphBenchmark.kt`
- Mirrors `GraphLoadTimingTest` using `BenchmarkRule.measureRepeated { }`
- Sets up `DriverFactory.setContext(...)` in `@Before`
- Uses temp dir from `filesDir` (not SAF)

**CI changes** (`android-benchmark.yml`):
- Job using `reactivecircus/android-emulator-runner@v2` (API 30, x86_64)
- Run `./gradlew :kmp:connectedBenchmarkAndroidTest`
- Upload JSON artifacts; post PR comment

**Estimated effort**:
- Bridge step (plain instrumented test): 0.5 days
- Full Microbenchmark + CI: 1–2 days
- Main friction: `SyntheticGraphGenerator` source-set access

## Operational Concerns

- Emulator benchmarks are for **regression detection** (is PR X slower than main?), not absolute targets. Absolute latency targets (< 500ms save, < 3s TTI) must be validated on a real device.
- Lock AVD to a specific API level (e.g., API 30) and ABI (`x86_64`) in the workflow YAML for comparable history.
- Microbenchmark JSON schema differs from the existing JVM custom schema — a separate CI parsing step is needed.
- `BenchmarkRule` uses `SystemClock.elapsedRealtimeNanos()` (not `System.nanoTime()`); results in nanoseconds; threshold check must convert.
- Keep `warmupCount` at 5–10 and `measurementCount` at 10–20 for CI (a 2-second operation × 50 iterations = 100s watchdog risk).

## Prior Art and Lessons Learned

- **Jetpack Room** ships a `:room-benchmark` module using `BenchmarkRule` to measure `INSERT`, `SELECT`, and `@Transaction` latency — directly transferable pattern. [TRAINING_ONLY — verify Room benchmark module structure in AOSP]
- **Nowinandroid**: uses Macrobenchmark for app startup, not Microbenchmark for library-level timing — confirms the right tool split. [TRAINING_ONLY — verify]
- **Existing SteleKit precedent**: `PlatformFileSystemSafTest.kt` demonstrates the `@Before` context setup; `GraphLoadTimingTest.kt` has the right phase-separated timing structure. Porting to Microbenchmark is mostly mechanical.
- **OTel integration opportunity**: the `TimingDriverWrapper` from the observability branch can be initialized in the test process, providing span data alongside `BenchmarkRule` measurements for richer diagnosis.

## Open Questions

- [ ] Is `SyntheticGraphGenerator` reachable from `androidInstrumentedTest`? — determines whether a source-set move is needed before writing the benchmark
- [ ] Does the "several seconds" latency reproduce on an emulator? — if caused by physical eMMC/SAF latency, emulator may not show it; a real device test step would be required for absolute validation
- [ ] Non-debuggable APK requirement on emulator — must test empirically; community reports suggest it may be relaxed for emulators
- [ ] Can `TimingDriverWrapper` / OTel SDK initialize correctly in a test process without a full `Application` lifecycle?
- [ ] KVM performance on `ubuntu-latest` for SQLite-heavy workloads — affects threshold strategy

## Recommendation

**Primary**: `androidx.benchmark:benchmark-junit4` (Microbenchmark) in `:kmp` `androidInstrumentedTest`. Direct `commonMain` access, standard JSON output, CI-compatible, no new Gradle module.

**Bridge step first**: Write a plain `@RunWith(AndroidJUnit4::class)` instrumented test with `System.nanoTime()` (0.5 days) to confirm the problem reproduces in a CI-runnable environment. Upgrade to `BenchmarkRule` once scaffolding is validated.

**Reject**:
- Macrobenchmark: wrong abstraction level (app startup, not sub-component), cannot call library code directly
- Android Profiler: not CI-gateable

**Threshold strategy**: Collect 5–10 emulator baseline runs before setting a hard threshold; start at `baseline_p50 * 1.5` to tolerate emulator variance without false positives.

## Pending Web Searches

1. `"androidx.benchmark" "benchmark-junit4" latest version site:maven.google.com` — confirm current stable version (claimed 1.3.4)
2. `"reactivecircus/android-emulator-runner" KVM "ubuntu-latest" github actions 2024 2025` — confirm nested KVM availability on current runners
3. `"androidx.benchmark" microbenchmark "non-debuggable" emulator site:developer.android.com` — confirm whether non-debuggable APK is required for emulator runs
4. `"benchmark-macro" "x86_64" emulator "google play" requirement site:developer.android.com` — confirm Macrobenchmark emulator image restriction
5. `sqldelight "androidInstrumentedTest" OR "connectedAndroidTest" benchmark site:github.com` — find SQLDelight-backed Android benchmark examples
