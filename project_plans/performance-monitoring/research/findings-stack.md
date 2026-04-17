# Findings: Stack

## Summary

For SteleKit's performance monitoring on KMP (Android + JVM Desktop), **the recommended approach is a hybrid strategy**:

1. **Core instrumentation**: Use OpenTelemetry API (already in `build.gradle.kts`) with a lightweight custom SDK or the official `opentelemetry-kotlin` library if it gains multiplatform support by the time of implementation.
2. **Frame timing (Android)**: Leverage Jetpack's `JankStats` + `FrameMetricsAggregator` directly (no third-party required); fallback to `Choreographer.FrameCallback` for older Android versions.
3. **Frame timing (JVM/Desktop)**: Use `java.lang.management.OperatingSystemMXBean` for process-wide CPU timing; custom render loop instrumentation for Compose Desktop.
4. **Histogram storage**: Manual `System.nanoTime()` instrumentation funneled into SQLDelight histogram tables (simplest, most portable, zero SDK overhead).
5. **Export**: Build a minimal local exporter targeting JSON/OTLP HTTP for future backend integration; no remote backend in Phase 1.

**Key decision**: Avoid pulling in a heavy OTel SDK distribution for Android (binary bloat). Instead, instrument manually using OTel-compatible span/metric APIs, then decide on SDK runtime later once requirements crystallize.

---

## Options Surveyed

### Option 1: OpenTelemetry Kotlin SDK (`opentelemetry-kotlin`)

**What it is**: Official OpenTelemetry Kotlin SDK, supposedly designed for Kotlin-first development.

**Current status** [TRAINING_ONLY — verify in web search]:
- **KMP support**: Unknown / not yet documented in stable releases as of early 2025. The official `io.opentelemetry` distributions are primarily JVM-only.
- **Binary size**: Estimated 1–2 MB JAR + dependencies; unclear how much APK bloat when compiled to Android DEX.
- **API surface**: Full OTel spans, metrics, logs, context propagation, automatic instrumentation agents.
- **Maturity**: Core OpenTelemetry spec is stable (1.0+); Kotlin-specific tooling is younger.

**Integration approach**:
```kotlin
// If available in multiplatform form:
commonMain {
    implementation("io.opentelemetry:opentelemetry-sdk-kotlin:???")
    // Would provide: TracerProvider, MeterProvider, LoggerProvider
}
```

**Known challenges**:
- As of Feb 2025, the official `opentelemetry-kotlin` project does not appear to have a stable, published multiplatform (KMP) distribution. JVM distributions exist.
- No guarantee it works with Gradle's KMP plugin without extensive workarounds.
- Risk of being added as a dependency only to discover it does not compile for `commonMain`.

**Verdict**: **High risk, deferred**. Only viable if official KMP support is confirmed via web search. If pursuing, start with a proof-of-concept build in a separate branch.

---

### Option 2: OpenTelemetry Android Agent (`opentelemetry-android`)

**What it is**: Android-specific OpenTelemetry instrumentation library, designed to automatically capture ANRs, crashes, network traffic, and frame drops without manual instrumentation.

**Attributes**:
- **Scope**: Android-only; does not help with JVM Desktop.
- **Binary size**: 300–500 KB estimated; adds dependencies on OTel core + Android libraries.
- **API surface**: High-level automatic instrumentation (no manual span/metric setup needed); exporters for OTel backends.
- **Maintenance**: Active CNCF project; releases follow OTel core cadence.

**Integration approach**:
```kotlin
androidMain {
    implementation("io.opentelemetry.android:opentelemetry-android:0.x.y")
    // Automatically hooks into Android lifecycle, frame metrics, crash reporting
}
```

**Known challenges**:
- **Desktop incompatible**: Zero help for JVM instrumentation. Would need separate instrumentation strategy for Desktop.
- **Overhead**: Automatic instrumentation on Android adds background processing; unclear if compatible with SteleKit's existing coroutine scopes.
- **Export**: Agent ships with exporters for remote backends; Phase 1 requirement is local-only export.
- **Opinionated**: Captures metrics you may not need (network, crashes); hard to trim down.

**Verdict**: **Viable for Android only; breaks KMP unity**. If SteleKit is Android-first, could be considered, but violates the "single shared instrumentation" constraint. Not recommended for a KMP project.

---

### Option 3: Platform Tracing APIs — Android

#### Android `FrameMetricsAggregator` (AndroidX)

**What it is**: Jetpack library for low-level per-frame metrics (frame time, input latency, animation latency, layout time).

**Attributes**:
- **Binary size**: Part of `androidx.core:core-ktx` (already in `kmp/build.gradle.kts` via `androidMain`).
- **API surface**: Low-level:
  - `addMetricsListener()` callback receives per-frame breakdown (elapsed, input, anim, layout, draw).
  - Data available per frame; user calls polling methods to retrieve aggregates.
- **Accuracy**: 1–2 ms resolution; ties directly to the Android rendering pipeline.
- **Maturity**: Stable, part of Jetpack.

**Example usage**:
```kotlin
@PerformanceMetricsCollector
class AndroidFrameMetricsCollector {
    private val aggregator = FrameMetricsAggregator()
    
    fun start() {
        aggregator.addMetricsListener { data ->
            val totalTime = data.getMetric(FrameMetricsAggregator.TOTAL_INDEX)
            // Store to SQLDelight histogram
        }
    }
}
```

**Constraints**:
- API 21+ (generally acceptable for SteleKit).
- Must be called from the main thread.
- Does not give per-frame jank reasons automatically; need to interpret TOTAL_INDEX vs. INPUT_INDEX, ANIM_INDEX, etc.

#### Android `Choreographer.FrameCallback`

**What it is**: Lower-level frame-callback hook; fires once per frame (vsync).

**Attributes**:
- **Binary size**: Zero (part of Android framework).
- **API surface**: Single callback: `doFrame(frameTimeNanos: Long)`. Measure elapsed time yourself.
- **Accuracy**: ~1–5 ms; subject to Android scheduler overhead.
- **Maturity**: Available since API 16; rarely changes.

**Example usage**:
```kotlin
Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
        val now = System.nanoTime()
        val frameTime = (now - frameTimeNanos) / 1_000_000.0 // ms
        // Store to histogram
        Choreographer.getInstance().postFrameCallback(this) // re-post for next frame
    }
})
```

**Constraints**:
- Must post callback from main thread; re-posting incurs small overhead.
- Cannot measure frame time backward-looking; must capture before frame completes.
- Does not distinguish jank root cause (layout, draw, input).

#### Android `JankStats` (Jetpack, 2024+)

**What it is**: Newer Jetpack library (androidx.metrics.jankstats) for automatic jank detection and attribution.

**Attributes** [TRAINING_ONLY — verify]:
- **Binary size**: 50–100 KB estimated.
- **API surface**:
  - High-level: `JankStats.start()` -> receives `JankFrameData` with jank classification (input, animation, layout, draw, etc.).
  - Automatic detection of 60 Hz vs. 120 Hz displays; adjusts thresholds accordingly.
- **Accuracy**: Aligned with Android performance standards.
- **Maturity**: New (2024); may have stability issues in early versions.

**Example usage**:
```kotlin
val jankStats = JankStats.createAndTrack(activity) { jankData ->
    if (jankData.isJank) {
        logJankEvent(jankData.frameName, jankData.jankSeverityMillis)
    }
}
```

**Constraints**:
- Requires API 21+ (androidx.metrics:metrics-performance:1.0.0-alpha+ as of 2024).
- Limited historical data in first release; API may evolve.
- Tied to Activity lifecycle; need careful integration with Jetpack Compose.

**Verdict (Android APIs)**: **`JankStats` preferred if available; `FrameMetricsAggregator` as fallback**. Zero binary bloat, native integration with Compose. Requires web search to confirm API stability and version in `androidx.metrics:metrics-performance`.

---

### Option 4: JVM / Desktop Tracing

#### `java.lang.management` OperatingSystemMXBean

**What it is**: JVM built-in API for process metrics: CPU time, memory, file descriptors, etc.

**Attributes**:
- **Binary size**: Zero (JVM standard library).
- **API surface**:
  ```kotlin
  val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
  val processCpuTime = osBean.processCpuTime // nanoseconds
  val threadCpuTime = ThreadMXBean.getCurrentThreadUserTime() // per-thread
  ```
- **Accuracy**: Millisecond-level; coarse for per-frame measurements (typical frame = 16 ms).
- **Maturity**: Stable, part of JDK 8+.

**Constraints**:
- Captures **process-wide** metrics, not frame-specific.
- Does not measure Compose render time directly; would need custom instrumentation around `ComposeScene.render()` or equivalent.
- No automatic frame-time breakdown (unlike Android `FrameMetricsAggregator`).

#### JVM Flight Recorder (JFR)

**What it is**: Built-in JVM profiler generating `.jfr` binary files; can be streamed or analyzed post-hoc.

**Attributes**:
- **Binary size**: Zero (JVM 11+).
- **API surface**:
  - Low-overhead async recording: `-XX:StartFlightRecording=...` flag or programmatic API.
  - Events: CPU, memory, GC, network I/O, file I/O, exception, lock contentions.
- **Accuracy**: Event-based, microsecond resolution available.
- **Maturity**: Stable, part of OpenJDK.

**Constraints**:
- **Overhead**: Even with JFR's low overhead (~1–2%), recording all events impacts Desktop performance.
- **Output format**: Binary `.jfr` files require special tools to parse (jfr CLI or Async Profiler).
- **Complexity**: Not trivial to ship + parse in a consumer app; better for development/profiling only.
- **Not realtime**: Data is available after recording stops, not suitable for in-app overlays.

**Verdict (JVM APIs)**: **`java.lang.management.OperatingSystemMXBean` for lightweight process-wide metrics; custom Compose instrumentation for frame-time**. JFR is overkill for Phase 1; reserve for development/profiling tools.

---

### Option 5: Manual Instrumentation (`System.nanoTime()` + SQLDelight)

**What it is**: Directly measure timings using `System.nanoTime()` and store histograms in SQLDelight (no external SDK).

**Attributes**:
- **Binary size**: Zero external dependencies.
- **API surface**:
  ```kotlin
  val start = System.nanoTime()
  // ... work ...
  val elapsed = (System.nanoTime() - start) / 1_000_000.0 // ms
  ```
- **KMP compatibility**: Works in `commonMain`, `androidMain`, `jvmMain` (all platforms).
- **Overhead**: < 1 microsecond per measurement; negligible.
- **Flexibility**: Instrument exactly what you care about; no bloat.

**Integration approach**:
```kotlin
// In commonMain/sqldelight/SteleDatabase.sq:
CREATE TABLE timing_histogram (
    id INTEGER PRIMARY KEY,
    operation_name TEXT NOT NULL,
    duration_ms REAL NOT NULL,
    timestamp INTEGER NOT NULL,
    tags TEXT -- JSON for context (screen, block_count, etc.)
);

// In commonMain Kotlin:
class PerformanceRecorder(private val database: SteleDatabase) {
    suspend fun recordOperation(name: String, block: suspend () -> Unit) {
        val start = System.nanoTime()
        block()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        database.insertTiming(name, elapsed, System.currentTimeMillis(), null)
    }
}
```

**Constraints**:
- Manual instrumentation at each measurement point (vs. automatic like OTel SDK).
- No built-in context propagation, baggage, or trace ID correlation.
- Histogram aggregation is manual (mean, percentiles, etc. computed in SQL or Kotlin).

**Verdict**: **Excellent foundation for Phase 1**. Low cost, high control, perfect for always-on lightweight histograms. Does NOT preclude adding an OTel SDK later for richer instrumentation.

---

## Trade-off Matrix

| Option | KMP Compat | Binary Size | API Surface | Maintenance | Integration Effort | Notes |
|--------|-----------|-------------|-------------|-------------|-------------------|-------|
| opentelemetry-kotlin | ⚠️ UNKNOWN — Not confirmed multiplatform | 1–2 MB + deps | Spans, metrics, logs, context | CNCF (active) | HIGH — Unproven on KMP | **Deferred**: Verify web search; high risk |
| opentelemetry-android | ✗ Android-only | 300–500 KB | Auto: ANRs, crashes, frame drops | CNCF (active) | MEDIUM — Breaks KMP unity | **Not recommended**: Violates shared instrumentation |
| FrameMetricsAggregator | ✓ Android only (AndroidX) | ~50 KB (in core-ktx) | Low-level per-frame breakdown | Google (stable) | MEDIUM — Android-only code path | **Good**: Zero extra binary; platform-specific is OK |
| Choreographer.FrameCallback | ✓ Android only | 0 (framework) | Manual frame-by-frame timing | Google (stable) | LOW — Simple callbacks | **Acceptable**: Older fallback for API <21 |
| JankStats | ✓ Android only | 50–100 KB | Auto jank detection + attribution | Google (new, 2024) | MEDIUM — New API, needs verification | **Preferred** (if stable): High-level, modern |
| java.lang.management.OperatingSystemMXBean | ✓ JVM only | 0 (JDK) | Process-wide CPU, memory | Oracle (stable) | LOW — Simple API | **Good**: Lightweight process metrics |
| JFR | ✓ JVM only | 0 (JDK 11+) | Async event recording, profiling | Oracle (stable) | HIGH — Complex parsing/shipping | **Not for Phase 1**: Overkill; for dev tools only |
| Manual System.nanoTime() + SQLDelight | ✓✓ BEST | 0 (stdlib) | Exactly what you measure | N/A (your code) | LOW — Simple instrumentation | **Recommended for Phase 1**: Foundation + future OTel |

---

## Risk and Failure Modes

### 1. OpenTelemetry Kotlin SDK Does Not Support KMP
**Risk**: Pull in `opentelemetry-kotlin` only to discover it does not compile for `commonMain`.
**Mitigation**: 
- Run a proof-of-concept in a separate branch before committing to the dependency.
- If blocked, fallback to manual instrumentation + OTel API (in `build.gradle.kts` already) for future SDK swap.

### 2. Android Frame-Timing APIs Incompatible with Jetpack Compose
**Risk**: `FrameMetricsAggregator` and `JankStats` are designed for traditional Android Views; may not track Compose recomposition overhead accurately.
**Mitigation**:
- Implement a small test composable that triggers recomposition; verify frame times via `JankStats.start()` capture the expected delay.
- Consider custom Compose `.drawBehind { }` instrumentation as fallback.

### 3. SQLDelight Histogram Table Contention
**Risk**: Writing timing data to SQLite on the main thread or in a tight loop causes writes to block UI.
**Mitigation**:
- Use SQLDelight's `suspendingTransaction()` in a coroutine; batch writes every N seconds.
- Store in-memory ringbuffer (e.g., 10,000 recent measurements) before batch-flushing to disk.

### 4. JVM Desktop Render Loop Not Instrumented
**Risk**: Compose Desktop has no built-in frame-time API like Android; custom Compose instrumentation is non-obvious.
**Mitigation**:
- Hook into the Compose `ComposeScene.render()` call or use a custom `LayoutNode.measure()` instrumentation.
- Start with `System.currentTimeMillis()` around UI update blocks; refine to nanosecond precision if needed.

### 5. OTel Exporter Overhead in Always-On Mode
**Risk**: Exporting every trace/metric to a local file or HTTP endpoint adds latency, especially if the exporter batches synchronously.
**Mitigation**:
- Use only in-memory batch exporters for Phase 1; store to file/network in background thread.
- If using OTel SDK later, configure exporters to drop data if queue fills (vs. blocking).

### 6. Frame Overlay Performance Impact
**Risk**: Drawing the FPS / frame-time overlay itself consumes precious frame budget, causing paradoxical jank.
**Mitigation**:
- Only enable overlay in debug builds or with explicit feature flag.
- Use low-frequency updates (every 2–5 frames, not every frame).
- Render overlay off-screen, then compose into final frame.

---

## Migration and Adoption Cost

### Phase 1: Lightweight Manual Instrumentation (Recommended Start)

**Timeline**: 1–2 weeks
**Effort**: 
1. Add SQLDelight `timing_histogram` table.
2. Create `PerformanceRecorder` in `commonMain` wrapping `System.nanoTime()`.
3. Instrument key data-flow boundaries: `GraphLoader.load()`, `GraphWriter.save()`, search, block editor saves.
4. Add debug menu toggle to enable/disable recording (no-op when disabled).
5. Export histogram JSON to file on demand.

**Cost**: Minimal. Zero new dependencies. Existing code change: 2–5 lines per instrumentation point.

**Adoption**: Developers add `performanceRecorder.record("operation_name") { ... }` when adding new features.

### Phase 2: Android Frame-Timing (After Phase 1)

**Timeline**: 2–3 weeks
**Effort**:
1. Add `JankStats.start()` in Android Activity setup (or `FrameMetricsAggregator` as fallback).
2. Hook jank events into `PerformanceRecorder` for storage.
3. Add Compose overlay to render real-time FPS + frame time (debug-only).

**Cost**: 
- Binary: +50–100 KB APK size (for JankStats).
- Runtime: Negligible frame overhead when not rendering overlay.

**Adoption**: Automatic; no code changes needed for existing instrumentation points.

### Phase 3: OTel SDK Integration (Future, if justified)

**Timeline**: 4–6 weeks (contingent on KMP support confirmation)
**Effort**:
1. Evaluate `opentelemetry-kotlin` or lightweight custom OTel SDK for KMP.
2. Replace `System.nanoTime()` calls with OTel `Tracer.spanBuilder()` and `Meter.recordHistogram()`.
3. Implement local-file exporter (OTLP JSON or custom format).
4. Add exporters for remote backends (Jaeger, Tempo, Honeycomb) if needed post-Phase 1.

**Cost**:
- Binary: +1–2 MB if using full OTel SDK (larger on Android; manageable on Desktop).
- Runtime: Spans/metrics are lazy; no overhead if exporter is disabled.

**Adoption**: Requires update to all instrumentation points; possible with a compatibility shim (`PerformanceRecorder` delegates to OTel or manual mode).

---

## Operational Concerns

### 1. Always-On Overhead Impact
**Concern**: Lightweight instrumentation (even just `System.nanoTime()` calls) adds up in tight loops.
**Mitigation**:
- Profile the app with and without instrumentation; aim for <1% frame-time impact.
- Use sampling for high-frequency operations (e.g., record every 10th block edit, not every keystroke).
- Disable histogram writes in Release builds; only enable for Debug/Canary.

### 2. Histogram Data Growth & Retention
**Concern**: Storing millions of measurements in SQLDelight over days/weeks fills disk.
**Solution**:
- Implement histogram summarization: store raw data for recent 24 hours; aggregate to hourly/daily buckets older than that.
- Add SQLDelight cleanup job: delete measurements older than 7 days by default.
- Expose API to export/clear histograms manually.

### 3. Frame Overlay Impact on Photos, Exports, Screen Recording
**Concern**: Debug overlay visible in screenshots, bug reports, or user videos.
**Mitigation**:
- Overlay is only visible in Debug builds; impossible to include in Release screenshots.
- Add explicit "hide overlay" button for screen recording workflows.
- Screenshot/export APIs respect a `includeDebugOverlay` flag (defaults to false).

### 4. Jank Event Loss Due to High Volume
**Concern**: If a screen is pathologically janky (e.g., every frame jank), storing every event fills memory/disk.
**Solution**:
- Histogram is aggregate (count, sum, min, max); individual jank events are sampled.
- Store only first N unique jank patterns per session (e.g., "layout time > 50 ms in SearchResultsList").
- Rotate event log on size (max 10 MB per session in temp storage).

### 5. Privacy & Telemetry
**Concern**: Histograms may contain sensitive data (page names, search terms, block counts, file paths).
**Solution**:
- Phase 1: Local storage only; no remote export by default.
- Bug report export is opt-in and user-controlled (not automatic).
- Sanitize `tags` field in histogram table (strip PII before storing).

### 6. Testing & CI Validation
**Concern**: Performance metrics vary on different devices, OS versions, load; hard to set absolute thresholds for CI.
**Approach**:
- Phase 1: Metrics recorded and exported; no CI gates.
- Phase 2+: CI benchmarks on consistent hardware (e.g., GitHub Actions runner); flag regressions (>10% slower) for review.

---

## Prior Art and Lessons Learned

### 1. LeakCanary Frame Overlay Pattern
**Source**: LeakCanary (JakeWharton et al.)
**Lesson**: In-app debug overlays must be:
- Toggle-able via long-press or menu, not always-visible.
- Rendered with minimal overhead (custom Canvas drawing, not composable recomposition).
- Exempt from screenshot/export pipelines.

**Applicability**: Design SteleKit's frame overlay similarly.

### 2. Android Vitals & Core Web Vitals Metrics
**Source**: Google's Vitals program (ANR, frozen frames, excessive motion)
**Lesson**: 
- Focus on user-perceptible metrics (frames >34 ms on 60 Hz, >50 ms on 120 Hz) vs. individual frame timing.
- Classify jank by root cause (input, animation, layout, draw, rendering) for actionability.
- Use statistical aggregates (P50, P90, P99) not raw frame counts.

**Applicability**: Design histogram schema with percentile buckets; implement jank classification when adding `JankStats`.

### 3. Perfetto & Android Profiler Architecture
**Source**: Google's Perfetto (system profiler for Android/Linux)
**Lesson**:
- Trace file format (Perfetto proto) is a superset of Open Telemetry's OTLP.
- Local storage + export is standard; remote streaming is optional.
- Viewer tools can be web-based (Perfetto UI) for portability.

**Applicability**: Design local export in OTLP JSON format; use Perfetto UI as free viewer if uploading traces.

### 4. Compose Compiler Metrics Plugin
**Source**: Google's Jetpack Compose compiler metrics (compose-compiler-metrics Gradle plugin)
**Lesson**:
- Recomposition counts are a useful proxy for performance; can be logged per-screen.
- Metrics are compiler-generated; no runtime overhead if disabled.
- Export as HTML report for analysis.

**Applicability**: Consider adding Compose compiler metrics to Phase 1 if recomposition is a suspected bottleneck (e.g., SearchResultsList).

### 5. Firebase Performance Monitoring (APM)
**Source**: Firebase Performance Monitoring SDK
**Lesson**:
- Automatic instrumentation is convenient but inflexible; manual spans are more controlled.
- Always-on sampling (e.g., 1% of sessions) reduces overhead vs. 100% capture.
- Client-side SDK queues data; backend eventually wins on retention/aggregation.

**Applicability**: Implement manual span instrumentation in Phase 1; sampling can be added later if volume is high.

### 6. Sentry Performance Monitoring Integration
**Source**: Sentry (error & perf monitoring)
**Lesson**:
- Baggage context (user, release, environment) is crucial for correlating traces with errors.
- Sampling decision should be made early; can be overridden per-trace.
- Exporter library should be pluggable (in-memory, file, HTTP) for flexibility.

**Applicability**: Store sampling decision + baggage (device, OS, app version) in histogram `tags` field from day 1.

---

## Open Questions

1. **Does `opentelemetry-kotlin` have stable KMP support as of April 2026?**
   - Training knowledge: Unlikely as of Feb 2025. Requires confirmation.
   - Action: Web search `opentelemetry-kotlin multiplatform KMP support 2026`.

2. **Is `androidx.metrics.jankstats` stable and production-ready in 2026?**
   - Training knowledge: New library (2024); may have breaking changes.
   - Action: Web search `androidx.metrics.jankstats stable release 2026` + check Maven Central.

3. **What is the actual APK size impact of `opentelemetry-android` vs. manual instrumentation?**
   - Training knowledge: Estimated 300–500 KB; needs real-world test.
   - Action: Web search `opentelemetry-android APK size impact DEX` + build test.

4. **Can `JankStats` track Jetpack Compose recomposition latency accurately?**
   - Training knowledge: Unclear; `JankStats` may only measure Android choreographer frames.
   - Action: Web search `JankStats Jetpack Compose recomposition latency` + local test.

5. **What is the best pattern for Compose Desktop frame-time instrumentation?**
   - Training knowledge: No standard API; custom `ComposeScene.render()` hook likely needed.
   - Action: Web search `Compose Desktop frame time profiling ComposeScene` + check Jetpack Compose source.

6. **Should Phase 1 use OTel API (already in `build.gradle.kts`) or wait for SDK confirmation?**
   - Training knowledge: OTel API is cheap; using it now is low-risk even if SDK swap is deferred.
   - Action: Recommend using API in Phase 1 for future SDK compatibility.

7. **What SQLDelight schema design best supports histogram aggregation (percentiles, bucketing)?**
   - Training knowledge: Two-table design (raw measurements + summary buckets) is standard.
   - Action: Web search `SQLite histogram percentile aggregation query patterns`.

8. **How do we handle sampling decisions for high-frequency operations (block editing, scrolling)?**
   - Training knowledge: Reservoir sampling or deterministic sampler common in APM SDKs.
   - Action: Web search `adaptive sampling algorithm high-frequency metrics OpenTelemetry`.

---

## Recommendation

### Recommended Approach: Phased Hybrid Strategy

**Phase 1 (Next Sprint): Lightweight Manual Instrumentation + Platform APIs**
- Core: Manual `System.nanoTime()` instrumentation in `commonMain` + SQLDelight histogram storage.
- Android: Integrate `JankStats` (if stable) or `FrameMetricsAggregator` (fallback) + `Choreographer.FrameCallback` for API <21.
- JVM Desktop: Process metrics via `java.lang.management.OperatingSystemMXBean` + custom Compose render loop instrumentation.
- No remote backend; local JSON export only.
- Debug menu toggle to enable/disable recording.

**Why**: 
- Zero external dependencies; full control over instrumentation.
- Platform-specific APIs are battle-tested and lightweight.
- Foundation is compatible with future OTel SDK swap.
- Unblocks Phase 2 (frame overlay, bug report export).

**Phase 2 (Subsequent Sprint): Frame Overlay + Bug Report Export**
- Add debug-only Compose overlay for FPS / frame time / jank count.
- Implement one-tap bug report export (metrics + device info + reproduction steps → JSON file).
- Refine histogram aggregation; add percentile buckets (P50, P90, P99).

**Phase 3 (Deferred, Decision Point): OTel SDK Integration**
- Contingent on:
  1. Confirmation that `opentelemetry-kotlin` or an equivalent KMP SDK is production-ready.
  2. Measured overhead of full SDK vs. manual instrumentation is acceptable on Android.
  3. Remote backend need is clear (bug reports, analytics, incident response).
- If yes: Migrate instrumentation points to `Tracer` / `Meter` APIs; implement OTLP exporter.
- If no: Continue with manual instrumentation + platform APIs; review in 6 months.

---

### Decision Checklist for Implementation

Before starting Phase 1, confirm:

- [ ] Add SQLDelight `timing_histogram` table with schema: `id`, `operation_name`, `duration_ms`, `timestamp`, `tags` (JSON).
- [ ] Create `PerformanceRecorder` in `commonMain` wrapping `System.nanoTime()`.
- [ ] Identify instrumentation points in `GraphLoader`, `GraphWriter`, search, block editor (apply 80/20 rule; ~5–10 points).
- [ ] Implement histogram export to JSON file (for analysis & bug reports).
- [ ] Add debug menu UI to toggle recording on/off (no-op when disabled).
- [ ] Test on Android (min API 21) and JVM Desktop; measure <1% frame-time impact.
- [ ] For Android: Run POC with `JankStats` (or `FrameMetricsAggregator` fallback); confirm it captures expected jank.
- [ ] For JVM: Measure CPU time from `OperatingSystemMXBean`; add custom timing around Compose render loop.

---

## Pending Web Searches

Execute in order; results will inform Phase 2–3 decisions:

1. **`opentelemetry-kotlin multiplatform KMP support 2026 Maven Central`**
   - Goal: Confirm if official KMP distribution exists; check maven.org artifact listings.
   - If yes: Evaluate `opentelemetry-kotlin` as alternative to manual instrumentation.
   - If no: Recommend deferring OTel SDK to Phase 3+; proceed with Phase 1 as-is.

2. **`androidx.metrics.jankstats stable release API 21 2026`**
   - Goal: Confirm stability, API maturity, version available on Maven Central.
   - If stable: Recommend `JankStats` as primary Android jank solution.
   - If beta/unstable: Recommend fallback to `FrameMetricsAggregator` + `Choreographer`.

3. **`Compose Desktop frame time profiling ComposeScene.render instrumentation`**
   - Goal: Discover standard patterns for JVM Desktop frame-time measurement.
   - Result: May find custom instrumentation or profiler tools; inform Phase 1 design.

4. **`opentelemetry-android APK size impact DEX binary bloat`**
   - Goal: Real-world APK size comparison with/without `opentelemetry-android`.
   - Result: Informs decision whether to use Android-specific agent vs. manual instrumentation.

5. **`SQLite histogram percentile P50 P90 P99 aggregation query`**
   - Goal: Standard SQL patterns for computing percentiles from raw timing data.
   - Result: Refine SQLDelight schema design; may use PERCENT_CONT window function (SQLite 3.47+).

6. **`adaptive reservoir sampling algorithm OpenTelemetry metrics high-frequency`**
   - Goal: Best practice for sampling high-frequency operations (block edits, scrolls) without losing anomalies.
   - Result: Informs histogram sampling strategy for Phase 1–2.

7. **`Perfetto trace OTLP JSON export format compatibility 2026`**
   - Goal: Confirm if OTLP JSON is compatible with Perfetto UI viewer; check if local export format is standardized.
   - Result: Informs Phase 2 export format design (may use Perfetto proto instead of custom JSON).

8. **`Jetpack Compose recomposition metrics Compose Compiler plugin integration`**
   - Goal: Determine if Compose compiler metrics can be captured at runtime or only at build time.
   - Result: Informs whether Phase 1 should include recomposition tracking or defer to Phase 2.

