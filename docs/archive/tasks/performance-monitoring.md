# Performance Monitoring Implementation Plan

**Feature**: Performance Monitoring and Management
**Branch**: `stelekit-performance-monitoring-and-mangaement`
**Status**: Planning
**Last Updated**: 2026-04-13

---

## Context

SteleKit users experience stuttering on Android during scrolling, navigation, typing, startup, and block-reference search. Zero instrumentation exists today beyond the in-memory `PerformanceMonitor` singleton (which has no persistence or jank classification). This plan replaces that ad-hoc approach with a production-grade, always-on observability stack using OpenTelemetry Kotlin, JankStats, and a SQLDelight histogram store.

The existing `PerformanceMonitor` at `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/PerformanceMonitor.kt` will be **retained as-is** during Phase 1 and gradually replaced by OTel spans in Phase 2. This prevents a big-bang refactor risk.

### Existing Constraints

- `opentelemetry-api:1.43.0` is already declared in `commonMain` dependencies (see `kmp/build.gradle.kts` line 65). The KMP OTel SDK (`opentelemetry-kotlin`) will supersede this with its own API layer.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebounceManager.kt` already exists in the performance package — new files must not conflict with it.
- The `androidMain` source set depends on `jvmCommonMain` (not only `androidMain`); new Android files must not import `jvmMain`-only classes.
- No `android.*` imports are permitted in `commonMain`.
- SQLDelight `.sq` files live in `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/` — all new tables and queries must be added to `SteleDatabase.sq` or a co-located new `.sq` file.

---

## Epic

**PERF-0**: As a SteleKit developer and user, I need production observability into frame rendering, operation latency, and jank so that I can identify, reproduce, and fix performance regressions before they ship.

---

## Stories and Atomic Tasks

### Phase 1: Baseline Metrics (Always-On Histogram Store)

**PERF-1**: As a developer, I need always-on latency histograms stored in SQLDelight so that I can query P50/P95/P99 for DB load, navigation, search, and editor input latency without any UI impact.

#### PERF-1.1 — Add histogram schema to SteleDatabase.sq

**Source set**: `commonMain`

**Files to modify**:
- `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

**Files to create**:
- None (appended to existing schema file)

**What to add** to `SteleDatabase.sq`:

```sql
-- Performance histogram storage
-- Bucketed counters; bucket_ms is the upper bound of the bucket in milliseconds.
-- Boundaries: 0, 16, 33, 50, 100, 500, 1000, 5000 ms (plus an overflow ">5000" bucket represented as 9999).
CREATE TABLE IF NOT EXISTS perf_histogram_buckets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_name TEXT NOT NULL,   -- e.g. "graph_load", "navigation", "search", "editor_input"
    bucket_ms INTEGER NOT NULL,     -- upper bound: 0,16,33,50,100,500,1000,5000,9999
    count INTEGER NOT NULL DEFAULT 0,
    recorded_at INTEGER NOT NULL    -- epoch ms of last increment
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_perf_hist_op_bucket
    ON perf_histogram_buckets(operation_name, bucket_ms);

CREATE TABLE IF NOT EXISTS debug_flags (
    key TEXT NOT NULL PRIMARY KEY,
    value INTEGER NOT NULL DEFAULT 0,  -- 1=enabled, 0=disabled
    updated_at INTEGER NOT NULL
);

-- Histogram queries
incrementHistogramBucket:
INSERT INTO perf_histogram_buckets (operation_name, bucket_ms, count, recorded_at)
VALUES (?, ?, 1, ?)
ON CONFLICT(operation_name, bucket_ms) DO UPDATE SET count = count + 1, recorded_at = excluded.recorded_at;

selectHistogramForOperation:
SELECT bucket_ms, count FROM perf_histogram_buckets WHERE operation_name = ? ORDER BY bucket_ms;

selectAllHistogramOperations:
SELECT DISTINCT operation_name FROM perf_histogram_buckets ORDER BY operation_name;

deleteOldHistogramRows:
DELETE FROM perf_histogram_buckets WHERE recorded_at < ?;

-- Debug flag queries
upsertDebugFlag:
INSERT INTO debug_flags (key, value, updated_at)
VALUES (?, ?, ?)
ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at;

selectDebugFlag:
SELECT value FROM debug_flags WHERE key = ?;

selectAllDebugFlags:
SELECT key, value FROM debug_flags ORDER BY key;
```

**What to test**:
- Unit test: verify `INSERT ... ON CONFLICT` increments counter correctly when called twice for the same operation+bucket.
- Unit test: verify `deleteOldHistogramRows` removes only rows with `recorded_at < threshold`.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/HistogramSchemaTest.kt`

**INVEST**: Independent (schema change only); Negotiable (bucket boundaries configurable); Valuable (unlocks all later tasks); Estimable; Small; Testable.

**Acceptance criteria**:
- Schema migrates cleanly on both Android (AndroidSqliteDriver) and Desktop (SQLite JDBC) without crashing.
- All new queries compile via SQLDelight code generation (`./gradlew generateCommonMainSteleDatabaseInterface`).

---

#### PERF-1.2 — HistogramWriter: async Channel-based writer

**Source set**: `commonMain`

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramWriter.kt`

**Purpose**: Accept latency samples from any coroutine context and batch-write to SQLDelight via `Dispatchers.IO`. Never blocks the main thread.

**Key design**:
- `Channel<HistogramSample>(capacity = Channel.BUFFERED)` — drop-oldest policy on overflow (prefer losing a metric over blocking the editor).
- Single consumer coroutine on `Dispatchers.IO`.
- `HistogramSample(operationName: String, durationMs: Long, recordedAt: Long)`.
- Bucket classification function: maps `durationMs` to the nearest upper-bound bucket from `[0, 16, 33, 50, 100, 500, 1000, 5000, 9999]`.

**Files to modify**:
- None (new file only)

**What to test**:
- Unit test: bucket classification maps edge values correctly (e.g., 0ms -> bucket 0, 16ms -> bucket 16, 17ms -> bucket 33, 5001ms -> bucket 9999).
- Unit test: concurrent calls from multiple coroutines do not deadlock.
- Integration test with in-memory SQLDelight driver: verify row count after 10 samples.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/HistogramWriterTest.kt`

**INVEST**: Independent; Valuable; Small; Testable.

**Acceptance criteria**:
- No main-thread DB writes (verified by coroutine dispatcher assertion in tests).
- Channel overflow drops samples silently, never throws.

---

#### PERF-1.3 — Instrument GraphLoader with timing spans

**Source set**: `commonMain`

**Files to modify**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

**What to add**: Wrap the graph load coroutine body with a `measureTimeMillis` block. Emit the result to `HistogramWriter` with `operationName = "graph_load"`. Log a warning if load exceeds 2000ms.

**Pattern**:
```kotlin
val durationMs = measureTimeMillis { /* existing load code */ }
histogramWriter.record("graph_load", durationMs)
```

**`HistogramWriter` is injected** via constructor parameter (nullable, defaulting to null for backward compatibility). Existing call sites in `GraphManager` and tests are unaffected.

**What to test**:
- Unit test: `GraphLoader` emits a histogram sample when load completes.
- Unit test: No sample emitted when `histogramWriter` is null (null-safety).
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphLoaderPerfTest.kt`

**Acceptance criteria**:
- `GraphLoader` constructor signature change is backward compatible (default null).
- Histogram sample recorded for both success and failure paths.

---

#### PERF-1.4 — Instrument StelekitViewModel navigation

**Source set**: `commonMain`

**Files to modify**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

**What to add**: Wrap `navigateTo()` with `measureTimeMillis`. Emit result to `HistogramWriter` with `operationName = "navigation"`. Block count at time of navigation recorded as span attribute (for later OTel enrichment).

**What to test**:
- Unit test: navigation emits histogram sample with `operationName = "navigation"`.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/StelekitViewModelPerfTest.kt`

**Acceptance criteria**:
- `HistogramWriter` is injected into `StelekitViewModel` (nullable default null).
- No existing ViewModel tests break.

---

#### PERF-1.5 — Instrument SearchRepository with timing

**Source set**: `commonMain`

**Files to modify**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt`

**What to add**: Wrap the FTS query in `measureTimeMillis`. Emit to `HistogramWriter` with `operationName = "search"`. The search repository interface (`SearchRepository`) is NOT modified — timing is added only to the SQLDelight implementation.

**What to test**:
- Unit test: search emits histogram sample.
- Test location: existing `businessTest` search tests extended.

**Acceptance criteria**:
- `HistogramWriter` injected via constructor (nullable default null).
- Instrument both `searchBlocksByContentFts` and `searchPagesByNameFts` paths.

---

#### PERF-1.6 — Histogram retention cleanup

**Source set**: `commonMain`

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramRetentionJob.kt`

**Purpose**: Coroutine job that runs `deleteOldHistogramRows` once at startup and daily thereafter. Retention window: 7 days (604800000 ms).

**What to test**:
- Unit test: cleanup deletes rows older than 7 days, retains newer rows.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/HistogramRetentionJobTest.kt`

**Acceptance criteria**:
- Retention job is launched from `GraphManager.switchGraph()` using the graph's `CoroutineScope`.
- Uses `Dispatchers.IO` exclusively.

---

### Phase 2: OpenTelemetry + JankStats

**PERF-2**: As a developer, I need structured distributed traces via OpenTelemetry KMP so that I can correlate latency across async boundaries and export spans to stdout for local analysis.

#### PERF-2.1 — Add opentelemetry-kotlin dependency

**Source set**: `commonMain`, `androidMain`, `jvmMain`

**Files to modify**:
- `kmp/build.gradle.kts`

**What to add**:

```kotlin
// commonMain dependencies block
implementation("io.opentelemetry.kotlin:opentelemetry-kotlin-api:0.2.0")

// androidMain dependencies block
implementation("io.opentelemetry.kotlin:opentelemetry-kotlin-sdk-android:0.2.0")
// Remove old: io.opentelemetry:opentelemetry-api:1.43.0 from commonMain (replaced)

// jvmMain dependencies block
implementation("io.opentelemetry.kotlin:opentelemetry-kotlin-sdk-jvm:0.2.0")
```

**What to validate**:
- `./gradlew jvmTest` passes after dependency change.
- `./gradlew assembleDebug` (Android) passes.
- Release APK size delta measured in CI (gate: < 2 MB increase vs. baseline).
- R8/ProGuard keep rules added for OTel ServiceLoader SPI (see PERF-2.2).

**What to test**:
- Compilation smoke test: `OtelProvider` (PERF-2.3) compiles in both source sets.

---

#### PERF-2.2 — ProGuard/R8 keep rules for OTel SPI

**Source set**: `androidMain` (release build config)

**Files to create**:
- `android/proguard-rules.pro` (or modify if it exists)

**What to add**:
```
# OpenTelemetry Kotlin - ServiceLoader SPI
-keep class io.opentelemetry.** { *; }
-keepnames class io.opentelemetry.** { *; }
-adaptresourcefilenames
-keepattributes Signature,InnerClasses,EnclosingMethod
```

**What to test**:
- Release build compiles without missing class warnings for OTel.
- Integration test: `OtelProvider.getTracer()` returns a non-null tracer in release build.

**Acceptance criteria**:
- Zero `ClassNotFoundException` at runtime for OTel classes in release build.

---

#### PERF-2.3 — OtelProvider: expect/actual SDK initialization

**Source set**: `commonMain` (expect), `androidMain` (actual), `jvmMain` (actual)

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/OtelProvider.kt` — `expect object OtelProvider`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/performance/OtelProvider.android.kt` — `actual object OtelProvider` using `opentelemetry-kotlin-sdk-android`
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/performance/OtelProvider.jvm.kt` — `actual object OtelProvider` using `opentelemetry-kotlin-sdk-jvm`

**Interface** (commonMain expect):
```kotlin
expect object OtelProvider {
    fun initialize(exporterConfig: OtelExporterConfig)
    fun getTracer(instrumentationName: String): Tracer
    fun getMeter(instrumentationName: String): Meter
    fun shutdown()
}

data class OtelExporterConfig(
    val enableStdout: Boolean = false,
    val enableRingBuffer: Boolean = true,
    val ringBufferCapacity: Int = 1000
)
```

**Android actual**: initializes OTel SDK with `OtlpJsonLoggingSpanExporter` (stdout) + `InMemorySpanExporter` (ring buffer). Wired in `androidApp/` entry point (not `commonMain`).

**JVM actual**: same exporters via JVM SDK variant. Wired in `Main.kt`.

**What to test**:
- Unit test: `OtelProvider.getTracer("test")` returns non-null after `initialize()`.
- Unit test: calling `getTracer` before `initialize` throws `IllegalStateException` (fail-fast).
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/OtelProviderTest.kt`

---

#### PERF-2.4 — InstrumentedPageRepository decorator

**Source set**: `commonMain`

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/InstrumentedPageRepository.kt`

**Pattern**: Decorator wrapping `PageRepository`. Each method starts an OTel span, delegates to the wrapped implementation, and ends the span in a `finally` block. Span attributes include `db.operation`, `page.name` (where available).

```kotlin
class InstrumentedPageRepository(
    private val delegate: PageRepository,
    private val tracer: Tracer
) : PageRepository {
    override suspend fun getPage(uuid: String): Page? {
        val span = tracer.spanBuilder("page.get").startSpan()
        return try {
            delegate.getPage(uuid)
        } finally {
            span.end()
        }
    }
    // ... all interface methods
}
```

**Files to modify**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt` — `createPageRepository()` wraps result in `InstrumentedPageRepository` when OTel is initialized.

**What to test**:
- Unit test: span is started and ended for each method call.
- Unit test: exception propagates correctly (span ends even on throw).
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/InstrumentedPageRepositoryTest.kt`

**Acceptance criteria**:
- Zero coupling to domain model — `InstrumentedPageRepository` only imports from `repository` and `performance` packages.
- Decorator is wired only in `RepositoryFactoryImpl.createPageRepository()`, not in domain code.

---

#### PERF-2.5 — InstrumentedSearchRepository decorator

**Source set**: `commonMain`

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/InstrumentedSearchRepository.kt`

**Same decorator pattern as PERF-2.4** applied to `SearchRepository`. Span attributes: `search.query_length`, `search.result_count`.

**What to test**:
- Unit test: search span includes `result_count` attribute.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/InstrumentedSearchRepositoryTest.kt`

---

#### PERF-2.6 — OTel coroutine context propagation

**Source set**: `commonMain`

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/OtelCoroutineContext.kt`

**Purpose**: Utility extension to install `OpenTelemetryContextElement` when launching child coroutines, ensuring span parent-child linkage across `withContext(Dispatchers.IO)` boundaries.

**Pattern**:
```kotlin
suspend fun <T> withSpan(span: Span, block: suspend () -> T): T {
    return withContext(span.asContextElement()) {
        try { block() } finally { span.end() }
    }
}
```

**What to test**:
- Unit test: child span created inside `withSpan` has correct parent span ID.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/OtelCoroutineContextTest.kt`

---

#### PERF-2.7 — JankStats integration (Android only)

**Source set**: `androidMain`

**Files to create**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/performance/JankStatsManager.kt`

**Purpose**: Initialize `JankStats` per `Activity` window. Register a `OnFrameListenerWithHistory` that classifies jank frames (duration > 16ms) and routes them to `HistogramWriter` with `operationName = "jank_frame"` and to an OTel span with attributes `frame.duration_ms`, `frame.is_jank`, `ui.screen`.

**Key implementation notes**:
- `JankStats` must be created on the main thread after `Activity.onWindowFocusChanged`.
- The frame listener callback must do minimal work: write to a `Channel`, never call DB directly.
- Screen context (`ui.screen`) is read from `AppState` via a `StateFlow` snapshot (no suspend call in callback).

**Files to modify**:
- `androidApp/src/main/kotlin/dev/stapler/stelekit/android/MainActivity.kt` (or equivalent Android entry point) — instantiate `JankStatsManager`.

**Dependency to add** in `androidMain` block of `kmp/build.gradle.kts`:
```kotlin
implementation("androidx.metrics:metrics-performance:1.0.0-beta02")
```

**What to test**:
- Unit test (Robolectric): `JankStatsManager` registers listener without crashing.
- Unit test: frame callback with duration 20ms records one sample in `HistogramWriter` with `operationName = "jank_frame"`.
- Test location: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/performance/JankStatsManagerTest.kt`

**Acceptance criteria**:
- `JankStats` listener callback allocates zero objects on the hot path (verified via Android Studio Profiler in a separate validation step).
- No `android.*` import leaks into `commonMain`.

---

#### PERF-2.8 — OTLP/stdout exporter wiring

**Source set**: `jvmMain` + `androidMain` entry points

**Files to modify**:
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` — add `OtelProvider.initialize(OtelExporterConfig(enableStdout = true, enableRingBuffer = true))` before `application { }` block; add shutdown hook call.
- Android entry point (`androidApp/`) — add `OtelProvider.initialize(...)` in `Application.onCreate()`.

**What to test**:
- Integration test: after `OtelProvider.initialize()`, a completed span appears in stdout (captured via log redirect in test).

---

### Phase 3: UX — Debug Menu, Ring Buffer, Bug Report Builder

**PERF-3**: As a developer, I need a toggleable debug overlay and one-tap bug report export so that I can inspect live metrics and attach reproduction context to bug reports.

#### PERF-3.1 — DebugMenuState (shared model)

**Source set**: `commonMain`

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugMenuState.kt`

**Contents**:
```kotlin
data class DebugMenuState(
    val isFrameOverlayEnabled: Boolean = false,
    val isOtelStdoutEnabled: Boolean = false,
    val isJankStatsEnabled: Boolean = false,
    val isDebugMenuVisible: Boolean = false
)
```

Persisted to `debug_flags` SQLDelight table (schema added in PERF-1.1). Loaded at startup via `HistogramWriter`'s database handle.

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugFlagRepository.kt` — CRUD over `debug_flags` table.

**What to test**:
- Unit test: `DebugFlagRepository.setFlag("frame_overlay", true)` persists and retrieves correctly.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/DebugFlagRepositoryTest.kt`

---

#### PERF-3.2 — DebugMenuOverlay: expect/actual composable

**Source set**: `commonMain` (expect), `androidMain` (actual), `jvmMain` (actual)

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.kt` — `@Composable expect fun DebugMenuOverlay(state: DebugMenuState, onStateChange: (DebugMenuState) -> Unit, onExportBugReport: () -> Unit)`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.android.kt` — Material3 `ModalBottomSheet` with toggle rows for each flag.
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.jvm.kt` — Compose desktop `Dialog` window.

**Integration into App.kt**: `DebugMenuOverlay` is shown when `AppState.isDebugMenuVisible = true`. Toggle is wired to a long-press gesture on the app version label in the sidebar (no dedicated button in release UI).

**Files to modify**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` — conditionally render `DebugMenuOverlay`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` — add `isDebugMenuVisible: Boolean = false`.

**What to test**:
- Screenshot test (Roborazzi on Desktop): `DebugMenuOverlay` renders all toggle rows without clipping.
- Unit test: toggling `isFrameOverlayEnabled` calls `onStateChange` with updated state.
- Test location: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlayTest.kt`

**Acceptance criteria**:
- Debug menu is not accessible in release builds (toggled behind `BuildConfig.DEBUG` equivalent on Android; a system property flag on Desktop).

---

#### PERF-3.3 — Frame time overlay (Android debug only)

**Source set**: `androidMain`

**Files to create**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/FrameTimeOverlay.kt`

**Purpose**: A Compose `Box` overlay in the top-right corner displaying current FPS and last frame duration in ms. Driven by a `StateFlow<FrameMetric>` populated by the `JankStats` listener (PERF-2.7). Only composed when `DebugMenuState.isFrameOverlayEnabled = true`.

**What to test**:
- Unit test: overlay displays "60 FPS / 16ms" when `FrameMetric(fps=60, lastFrameMs=16)` is emitted.

---

#### PERF-3.4 — InMemorySpanExporter ring buffer

**Source set**: `commonMain`

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/RingBufferSpanExporter.kt`

**Purpose**: Implements OTel `SpanExporter`. Fixed-capacity circular buffer (capacity = 1000). Thread-safe via `Mutex`. Exposed as `fun snapshot(): List<SpanData>` for bug report builder.

**What to test**:
- Unit test: inserting 1001 spans results in capacity of 1000 (oldest dropped).
- Unit test: `snapshot()` returns a stable copy not affected by concurrent inserts.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/RingBufferSpanExporterTest.kt`

---

#### PERF-3.5 — BugReportBuilder

**Source set**: `commonMain`

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/BugReportBuilder.kt`

**Purpose**: Assembles a JSON bug report from:
1. `RingBufferSpanExporter.snapshot()` — last 1000 OTel spans.
2. `HistogramWriter.queryPercentiles()` — P50/P95/P99 for each operation (computed in-memory from bucketed data).
3. Device info (platform, OS version, available RAM) — provided via `expect/actual DeviceInfo`.
4. User-supplied reproduction steps (plain text, max 2000 chars).

**Output format** (kotlinx.serialization `@Serializable`):
```kotlin
@Serializable
data class BugReport(
    val generatedAt: Long,
    val appVersion: String,
    val deviceInfo: DeviceInfo,
    val reproductionSteps: String,
    val histogramSummary: Map<String, PercentileSummary>,
    val recentSpans: List<SerializedSpan>
)
```

Export path: clipboard (primary) or file `<app_data>/bug_report_<timestamp>.json` (secondary, platform-specific).

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DeviceInfo.kt` — `expect data class DeviceInfo`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/performance/DeviceInfo.android.kt` — actual with `Build.MODEL`, `Build.VERSION.SDK_INT`, available RAM via `ActivityManager`.
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/performance/DeviceInfo.jvm.kt` — actual with `System.getProperty("os.name")`, JVM max heap.

**What to test**:
- Unit test: `BugReportBuilder.build()` returns valid JSON with all required top-level keys.
- Unit test: reproduction steps > 2000 chars are truncated.
- Unit test: `PercentileSummary` computed correctly from histogram buckets.
- Test location: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/BugReportBuilderTest.kt`

**Acceptance criteria**:
- Generated JSON is valid and parseable by `kotlinx.serialization.json.Json.decodeFromString<BugReport>()`.
- No PII in default output (no page content, no block text).

---

#### PERF-3.6 — Wire bug report export in DebugMenuOverlay

**Source set**: `commonMain` / `androidMain` / `jvmMain`

**Files to modify**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` — add `exportBugReport()` suspend function that calls `BugReportBuilder.build()` and invokes the platform share/clipboard mechanism.
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.android.kt` — wire "Export Bug Report" button to ViewModel.
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.jvm.kt` — same for Desktop.

**What to test**:
- Integration test: tapping "Export Bug Report" in debug menu calls `BugReportBuilder.build()` and writes result to clipboard.

---

### Phase 4: CI Performance Gates

**PERF-4**: As a developer, I need automated CI gates on frame time regression so that no PR silently worsens P95 frame time beyond baseline.

#### PERF-4.1 — Macrobenchmark baseline (Android)

**Source set**: New Gradle module `benchmarks/`

**Files to create**:
- `benchmarks/build.gradle.kts` — Android Macrobenchmark module targeting `androidApp`.
- `benchmarks/src/main/kotlin/dev/stapler/stelekit/benchmark/NavigationBenchmark.kt` — `@MacrobenchmarkRule` test that navigates between 5 pages and captures `FrameTimingMetric`.
- `benchmarks/src/main/kotlin/dev/stapler/stelekit/benchmark/ScrollBenchmark.kt` — scrolls `LazyColumn` block list, captures `FrameTimingMetric`.

**Files to modify**:
- `build.gradle.kts` (root) — include `:benchmarks` module.

**What to test**: Benchmark runs and outputs `frameTime50thPercentileMs` and `frameTime95thPercentileMs` JSON to `benchmarks/outputs/`.

**Acceptance criteria**:
- Benchmark P95 frame time < 33ms on the reference device (Pixel 6, Android 14).
- CI fails if P95 regresses by > 20% vs. stored baseline JSON.

---

#### PERF-4.2 — CI step: APK size gate

**Source set**: `.github/workflows/`

**Files to modify**:
- `.github/workflows/` (existing CI workflow file)

**What to add**: Step after `assembleRelease` that:
1. Reads APK size in bytes.
2. Compares against `CI_APK_SIZE_BASELINE_BYTES` environment variable (set manually after first OTel release).
3. Fails the build if APK grows > 2,000,000 bytes (2 MB) over baseline.

**Acceptance criteria**:
- CI step runs on every PR targeting `main`.
- First run establishes baseline (baseline-setting mode toggled by `CI_APK_BASELINE_MODE=true`).

---

#### PERF-4.3 — CI step: histogram regression detection

**Source set**: `businessTest`

**Files to create**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/HistogramRegressionTest.kt`

**Purpose**: Synthetic benchmark using in-memory SQLDelight driver. Simulates 100 navigation operations with known durations. Asserts that P95 bucket <= 50ms. Fails the build if histogram writes to a wrong bucket.

**Acceptance criteria**:
- Test runs in < 5 seconds (no real I/O).
- Included in `./gradlew allTests` CI step.

---

## Dependency Graph

```
PERF-1.1 (schema)
  └── PERF-1.2 (HistogramWriter)
        ├── PERF-1.3 (GraphLoader timing)
        ├── PERF-1.4 (ViewModel timing)
        ├── PERF-1.5 (Search timing)
        ├── PERF-1.6 (Retention job)
        └── PERF-2.7 (JankStats → HistogramWriter)

PERF-2.1 (OTel dependency)
  └── PERF-2.2 (ProGuard rules)
        └── PERF-2.3 (OtelProvider expect/actual)
              ├── PERF-2.4 (InstrumentedPageRepository)
              ├── PERF-2.5 (InstrumentedSearchRepository)
              ├── PERF-2.6 (Coroutine context propagation)
              ├── PERF-2.7 (JankStats)
              └── PERF-2.8 (Exporter wiring)
                    └── PERF-3.4 (Ring buffer)

PERF-3.1 (DebugMenuState)
  └── PERF-3.2 (DebugMenuOverlay)
        ├── PERF-3.3 (Frame overlay)
        ├── PERF-3.4 (Ring buffer) ← also depends on PERF-2.3
        └── PERF-3.5 (BugReportBuilder)
              └── PERF-3.6 (Wire export)

PERF-1.2 + PERF-3.5 → PERF-4.3 (Histogram regression test)
PERF-2.1 → PERF-4.2 (APK size gate)
PERF-4.1 (Macrobenchmark) — independent; requires physical device or emulator in CI
```

**Critical path**: PERF-1.1 → PERF-1.2 → PERF-2.1 → PERF-2.3 → PERF-3.4 → PERF-3.5

---

## Integration Checkpoints

### Checkpoint A (end of Phase 1)
- `./gradlew jvmTest` passes with histogram writer tests green.
- `./gradlew assembleDebug` (Android) succeeds with new schema migration.
- SQLDelight code generation produces `IncrementHistogramBucket` and `SelectHistogramForOperation` queries.
- Manual verification: navigate 5 pages on Desktop, query `perf_histogram_buckets` in SQLite browser; rows appear.

### Checkpoint B (end of Phase 2)
- OTel spans visible in stdout during Desktop app startup.
- JankStats listener fires on Android when deliberately delaying the main thread (test with `Thread.sleep(50)` in a scroll callback).
- `./gradlew assembleRelease` passes ProGuard without OTel class stripping errors.
- Release APK size delta documented in PR description.

### Checkpoint C (end of Phase 3)
- Debug menu opens via long-press on version label; toggles persist across app restart.
- "Export Bug Report" generates valid JSON containing histogram data and at least one span.
- Span count in ring buffer is <= 1000 after sustained operation.

### Checkpoint D (end of Phase 4)
- Macrobenchmark runs on reference device; P95 frame time recorded.
- CI fails intentionally when a 50ms `Thread.sleep` is injected into scroll path (regression test).
- APK size gate blocks a PR that adds a 3 MB uncompressed resource.

---

## Known Issues

### Bug Risk: Race condition in HistogramWriter channel overflow [SEVERITY: Medium]

**Description**: The `Channel.BUFFERED` default capacity is 64. Under heavy load (e.g., 100ms of jank events), the channel may fill up faster than the consumer can drain it. The drop-oldest policy silently loses samples, which skews P95 calculations during the worst-case scenarios we most need to capture.

**Mitigation**:
- Set explicit channel capacity to 512 (empirically sized for max 30fps * 17ms of jank bursts).
- Add a dropped-sample counter (atomic long) that is included in bug reports.
- Unit test: verify dropped counter increments when channel is full.

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramWriter.kt`

---

### Bug Risk: OTel context lost across Dispatchers.IO boundary [SEVERITY: High]

**Description**: When a span is started on the main dispatcher and the work crosses into `Dispatchers.IO` (e.g., `GraphLoader` reading files), the OTel context element is not automatically propagated. Child spans will have no parent, breaking trace trees.

**Mitigation**:
- Always use `withSpan()` utility (PERF-2.6) instead of raw `withContext(Dispatchers.IO)` in instrumented paths.
- Add explicit integration test: assert child span `parentSpanId` equals the outer span's `spanId` after a `Dispatchers.IO` switch.

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt`

---

### Bug Risk: SQLDelight UPSERT syntax compatibility [SEVERITY: Medium]

**Description**: `INSERT ... ON CONFLICT DO UPDATE SET` (standard UPSERT) requires SQLite 3.24+. The Android SQLite version varies by API level. The `RequerySQLiteOpenHelperFactory` bundled as `com.github.requery:sqlite-android:3.49.0` ships SQLite 3.49 and is already used by the project — this eliminates the risk on Android. Desktop uses the JVM JDBC driver which also bundles a modern SQLite. However, the schema migration path (running `ALTER TABLE` in `DriverFactory.createDriver()`) must also add the new tables for existing databases.

**Mitigation**:
- Use `CREATE TABLE IF NOT EXISTS` for new tables in `SteleDatabase.sq`.
- Add incremental migration in `DriverFactory.android.kt` and `DriverFactory.jvm.kt` alongside the existing `ALTER TABLE` block pattern.
- Integration test: open a pre-existing database (fixture) with the new schema; verify no crash and both old and new tables exist.

**Files likely affected**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt`
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/DriverFactory.jvm.kt` (if exists)

---

### Bug Risk: JankStats Activity reference leak [SEVERITY: High]

**Description**: `JankStats` holds a reference to the `Window`. If `JankStatsManager` is stored as a singleton or in a ViewModel that outlives the `Activity`, the `Window` (and thus `Activity`) will leak.

**Mitigation**:
- Scope `JankStatsManager` to the `Activity` lifecycle. Create in `onStart()`, destroy in `onStop()`.
- Never store `JankStatsManager` in `StelekitViewModel` (which may outlive an Activity rotation).
- Leak canary (already standard in debug builds) will catch this in QA.

**Files likely affected**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/performance/JankStatsManager.kt`
- Android `MainActivity.kt`

---

### Bug Risk: DebugMenuOverlay visible in release builds [SEVERITY: High]

**Description**: If the long-press gesture trigger for the debug menu is not gated on a compile-time or runtime debug flag, users could access internal metrics and toggle OTel stdout in production.

**Mitigation**:
- Gate the long-press handler in `App.kt` behind `BuildConfig.DEBUG` on Android.
- On Desktop, gate behind `-Ddev.stapler.debug=true` JVM system property.
- CI release build smoke test: verify `DebugMenuOverlay` composable is not reachable via UI automation in release variant.

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.android.kt`

---

### Bug Risk: Existing PerformanceMonitor singleton conflicts with OTel [SEVERITY: Low]

**Description**: `PerformanceMonitor` (in-memory only) and the new OTel tracer will both capture `graph_load` events in Phase 2. Duplicate instrumentation wastes CPU and may confuse log analysis.

**Mitigation**:
- In Phase 2, deprecate `PerformanceMonitor.startTrace()` / `endTrace()` call sites one by one as OTel decorator spans replace them.
- Do not delete `PerformanceMonitor.kt` until Phase 2 is complete and all call sites migrated (use `@Deprecated` annotation).
- Track migration completeness via a `// TODO(PERF-2): migrate to OTel` comment at each call site.

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/PerformanceMonitor.kt`
- All files calling `PerformanceMonitor.startTrace()`

---

## File Index

### New Files to Create

| File | Source Set | Phase |
|------|-----------|-------|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramWriter.kt` | commonMain | 1 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramRetentionJob.kt` | commonMain | 1 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/OtelProvider.kt` (expect) | commonMain | 2 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/OtelCoroutineContext.kt` | commonMain | 2 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/InstrumentedPageRepository.kt` | commonMain | 2 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/InstrumentedSearchRepository.kt` | commonMain | 2 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/RingBufferSpanExporter.kt` | commonMain | 3 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/BugReportBuilder.kt` | commonMain | 3 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugMenuState.kt` | commonMain | 3 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugFlagRepository.kt` | commonMain | 3 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DeviceInfo.kt` (expect) | commonMain | 3 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.kt` (expect) | commonMain | 3 |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/performance/OtelProvider.android.kt` | androidMain | 2 |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/performance/JankStatsManager.kt` | androidMain | 2 |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/performance/DeviceInfo.android.kt` | androidMain | 3 |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.android.kt` | androidMain | 3 |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/components/FrameTimeOverlay.kt` | androidMain | 3 |
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/performance/OtelProvider.jvm.kt` | jvmMain | 2 |
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/performance/DeviceInfo.jvm.kt` | jvmMain | 3 |
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.jvm.kt` | jvmMain | 3 |
| `benchmarks/build.gradle.kts` | new module | 4 |
| `benchmarks/src/main/kotlin/dev/stapler/stelekit/benchmark/NavigationBenchmark.kt` | new module | 4 |
| `benchmarks/src/main/kotlin/dev/stapler/stelekit/benchmark/ScrollBenchmark.kt` | new module | 4 |

### Existing Files to Modify

| File | Change | Phase |
|------|--------|-------|
| `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` | Add histogram + debug_flags tables and queries | 1 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` | Inject `HistogramWriter`, wrap load with `measureTimeMillis` | 1 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` | Inject `HistogramWriter`, time `navigateTo()` | 1 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt` | Inject `HistogramWriter`, time FTS queries | 1 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` | Launch `HistogramRetentionJob` in `switchGraph()` | 1 |
| `kmp/build.gradle.kts` | Add OTel KMP deps; add JankStats dep for androidMain | 2 |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt` | Add incremental migration for new perf tables | 1 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt` | Wrap repositories in instrumented decorators | 2 |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` | Add `DebugMenuOverlay` conditional render | 3 |
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` | Add `OtelProvider.initialize()` call | 2 |
| `.github/workflows/` (CI file) | Add APK size gate step | 4 |
| `build.gradle.kts` (root) | Include `:benchmarks` module | 4 |

---

## Test Locations Summary

| Test File | What It Tests |
|-----------|--------------|
| `kmp/src/businessTest/.../performance/HistogramSchemaTest.kt` | SQLDelight UPSERT + delete retention |
| `kmp/src/businessTest/.../performance/HistogramWriterTest.kt` | Channel writes, bucket classification, concurrency |
| `kmp/src/businessTest/.../performance/HistogramRetentionJobTest.kt` | 7-day cleanup |
| `kmp/src/businessTest/.../performance/OtelProviderTest.kt` | SDK init, tracer access, fail-fast |
| `kmp/src/businessTest/.../performance/InstrumentedPageRepositoryTest.kt` | Span start/end, exception propagation |
| `kmp/src/businessTest/.../performance/InstrumentedSearchRepositoryTest.kt` | Search span attributes |
| `kmp/src/businessTest/.../performance/OtelCoroutineContextTest.kt` | Parent-child span across dispatcher boundary |
| `kmp/src/businessTest/.../performance/RingBufferSpanExporterTest.kt` | Capacity cap, snapshot thread-safety |
| `kmp/src/businessTest/.../performance/BugReportBuilderTest.kt` | JSON structure, truncation, no-PII |
| `kmp/src/businessTest/.../performance/DebugFlagRepositoryTest.kt` | Persist + retrieve debug flags |
| `kmp/src/businessTest/.../performance/HistogramRegressionTest.kt` | P95 synthetic benchmark gate |
| `kmp/src/androidUnitTest/.../performance/JankStatsManagerTest.kt` | Listener registration, frame classification |
| `kmp/src/jvmTest/.../ui/components/DebugMenuOverlayTest.kt` | Roborazzi screenshot, toggle behavior |
| `kmp/src/businessTest/.../db/GraphLoaderPerfTest.kt` | GraphLoader emits histogram sample |
| `kmp/src/businessTest/.../ui/StelekitViewModelPerfTest.kt` | Navigation emits histogram sample |
