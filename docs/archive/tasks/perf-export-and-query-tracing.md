# Perf Export + SQLDelight Query-Level Tracing — Implementation Plan

**Feature**: Perf Export + SQLDelight Query-Level Tracing  
**Epic IDs**: EXPORT-1, QTRACE-1  
**Status**: Planning  
**Last Updated**: 2026-04-21  
**Requirements**: `project_plans/perf-export-and-query-tracing/requirements.md`  
**ADRs**: `project_plans/perf-export-and-query-tracing/decisions/`

---

## Context

Two gaps block AI-assisted performance analysis of SteleKit sessions:

1. No export pathway exists for the span/histogram data stored in the per-graph SQLite DB. On Android the DB is app-private; on desktop there is no export action.
2. Spans are coarse: the waterfall shows `parseAndSavePage (43ms)` with 7 child spans, but none of them break down individual SQLDelight queries. The Datadog KMP SDK auto-wraps every SQL call; this epic adds an equivalent facility using only existing `SerializedSpan` infrastructure.

**Key architectural constraints carried forward from CLAUDE.md:**
- `commonMain` must not import `android.*` or `java.*`
- All DB writes go through `DatabaseWriteActor`; tracing must not bypass this
- Tracing spans are written to `RingBufferSpanExporter` (in-memory) only during a query, never to SQLite synchronously
- `rememberCoroutineScope()` must not escape Compose composition
- `PlatformDispatcher.DB` for all SQL; `PlatformDispatcher.IO` for file writes

---

## Epic EXPORT-1: Perf Report Export to JSON

**As a developer**, I need a one-click export of all recorded spans and histogram summaries so that I can share the file with an AI assistant for offline outlier analysis.

---

### Story EXPORT-1.1 — `PerfExportReport` data model and serializer

**Context files** (read before starting):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/RingBufferSpanExporter.kt` (contains `SerializedSpan`)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramWriter.kt` (contains `PercentileSummary`)

#### Task EXPORT-1.1.1 — Create `PerfExportReport.kt`

**Source set**: `commonMain`  
**File to create**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/PerfExportReport.kt`

Define:

```kotlin
package dev.stapler.stelekit.performance

import kotlinx.serialization.Serializable

@Serializable
data class PerfExportReport(
    val exportedAt: Long,
    val appVersion: String,
    val platform: String,        // "android" | "jvm" | "ios" | "wasm"
    val spans: List<SerializedSpan>,
    val histograms: Map<String, PercentileSummary>
)
```

`SerializedSpan` and `PercentileSummary` are already `@Serializable`. No new dependencies needed — `kotlinx-serialization-json` is already in the classpath. Verify in `kmp/build.gradle.kts` that `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:...")` is present in `commonMain`; add it explicitly if it is only transitive.

**Acceptance**: file compiles, `Json.encodeToString(PerfExportReport(...))` round-trips cleanly in a unit test.

---

#### Task EXPORT-1.1.2 — Add `KNOWN_OPERATIONS` constant

**File to modify**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramWriter.kt`

Add inside the `companion object`:

```kotlin
val KNOWN_OPERATIONS = listOf(
    "frame_duration", "graph_load", "navigation", "search",
    "editor_input", "sql.select", "sql.insert", "sql.update", "sql.delete"
)
```

This list is used by both the export logic and the `HistogramsTab` UI (future: replace hard-coded `operations` list there).

---

### Story EXPORT-1.2 — `PerfExporter` service (commonMain)

**Context files** (read before starting):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/SpanRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/HistogramWriter.kt` (after EXPORT-1.1.2)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`

#### Task EXPORT-1.2.1 — Add `getDownloadsPath(): String` to `FileSystem`

**File to modify**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`

Add default implementation:

```kotlin
/** Returns the platform-appropriate Downloads directory path for exporting user-facing files. */
fun getDownloadsPath(): String = expandTilde("~/Downloads")
```

JVM override in `DesktopFileSystem` is not needed (default resolves correctly via `expandTilde`). Android override is required in `androidMain` — see EXPORT-1.3.1.

---

#### Task EXPORT-1.2.2 — Create `PerfExporter.kt`

**Source set**: `commonMain`  
**File to create**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/PerfExporter.kt`

```kotlin
package dev.stapler.stelekit.performance

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PerfExporter(
    private val spanRepository: SpanRepository,
    private val histogramWriter: HistogramWriter,
    private val fileSystem: FileSystem,
    private val appVersion: String,
    private val platform: String,
) {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    /**
     * Exports all recent spans and histogram summaries to a JSON file in the platform Downloads
     * directory. Returns the absolute file path on success or throws on failure.
     * Must be called from a coroutine; switches to [PlatformDispatcher.IO] internally.
     */
    suspend fun export(): String = withContext(PlatformDispatcher.IO) {
        val spans = spanRepository.getRecentSpans(limit = 10_000).first()
        val histograms = HistogramWriter.KNOWN_OPERATIONS
            .mapNotNull { op -> histogramWriter.queryPercentiles(op)?.let { op to it } }
            .toMap()
        val report = PerfExportReport(
            exportedAt = HistogramWriter.epochMs(),
            appVersion = appVersion,
            platform = platform,
            spans = spans,
            histograms = histograms
        )
        val jsonContent = json.encodeToString(report)
        val timestamp = formatTimestamp(report.exportedAt)
        val downloadsPath = fileSystem.getDownloadsPath()
        val fileName = "stelekit-perf-$timestamp.json"
        val fullPath = "$downloadsPath/$fileName"
        val success = fileSystem.writeFile(fullPath, jsonContent)
        if (!success) error("Failed to write perf report to $fullPath")
        fullPath
    }

    private fun formatTimestamp(epochMs: Long): String {
        // ISO-8601 compact: YYYY-MM-DD-HHmm
        // Use kotlinx-datetime if already in classpath, else manual arithmetic.
        // kotlinx-datetime is a transitive dep of sqldelight-runtime on KMP.
        val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
        val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        return "%04d-%02d-%02d-%02d%02d".format(
            local.year, local.monthNumber, local.dayOfMonth, local.hour, local.minute
        )
    }
}
```

Note: `kotlinx-datetime` must be present in `commonMain` dependencies in `kmp/build.gradle.kts`. Verify or add `implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")`.

**Acceptance**: unit test in `businessTest` creates a `PerfExporter` with an `InMemorySpanRepository` (or mock), calls `export()`, and verifies the returned path ends with `.json` and the written content parses to `PerfExportReport`.

---

### Story EXPORT-1.3 — Platform actuals for Downloads path

#### Task EXPORT-1.3.1 — Android: override `getDownloadsPath()` in `PlatformFileSystem`

**Source set**: `androidMain`  
**File to modify**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

Add override inside `actual class PlatformFileSystem`:

```kotlin
override fun getDownloadsPath(): String =
    android.os.Environment
        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        ?.absolutePath
        ?: "/storage/emulated/0/Download"
```

This uses scoped-storage public-directory exemption (no `WRITE_EXTERNAL_STORAGE` required on API 29+). On API 28 and below, `WRITE_EXTERNAL_STORAGE` is needed — add it to `androidApp/src/main/AndroidManifest.xml` behind `android:maxSdkVersion="28"`.

**Note**: `writeFile` in Android's `PlatformFileSystem` currently routes through SAF for SAF-rooted paths. A plain absolute path like `/storage/emulated/0/Download/...` must fall through to a direct `java.io.File` write. Verify that the existing `writeFile` implementation handles absolute non-SAF paths correctly. If it does not, add a branch:

```kotlin
actual override fun writeFile(path: String, content: String): Boolean {
    if (!path.startsWith("saf://")) {
        return try {
            java.io.File(path).also { it.parentFile?.mkdirs() }.writeText(content)
            true
        } catch (e: Exception) { false }
    }
    // ... existing SAF implementation
}
```

---

#### Task EXPORT-1.3.2 — Manifest permission for Android Downloads write

**File to modify**: `androidApp/src/main/AndroidManifest.xml`

Add (if not already present):

```xml
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

---

### Story EXPORT-1.4 — Wire `PerfExporter` into `RepositorySet` and `PerformanceDashboard`

**Context files** (read before starting):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PerformanceDashboard.kt`

#### Task EXPORT-1.4.1 — Add `PerfExporter?` to `RepositorySet`

`RepositorySet` is the data class holding all per-graph singletons. Locate its definition (likely inline in `RepositoryFactory.kt` or a nearby file).

Add field:

```kotlin
val perfExporter: PerfExporter? = null
```

In `RepositoryFactoryImpl.createRepositorySet`, after creating `histogramWriter` and `spanRepository`, construct:

```kotlin
val perfExporter = if (histogramWriter != null && spanRepository != null) {
    PerfExporter(
        spanRepository = spanRepository,
        histogramWriter = histogramWriter,
        fileSystem = PlatformFileSystem(),
        appVersion = BuildConfig.VERSION_NAME,   // or a constant
        platform = PlatformInfo.name             // expect/actual returning "android"/"jvm"/etc.
    )
} else null
```

Include `perfExporter` in the `RepositorySet(...)` constructor call.

Note: `BuildConfig.VERSION_NAME` requires platform-specific access. An `expect val appVersionName: String` in `commonMain` with `actual` implementations reading from platform build info is the correct approach. Alternatively, pass `appVersion` as a constructor parameter to `RepositoryFactoryImpl`.

---

#### Task EXPORT-1.4.2 — Add `PerfExporter?` parameter to `PerformanceDashboard`

**File to modify**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PerformanceDashboard.kt`

Change `@Composable fun PerformanceDashboard(...)` signature to include:

```kotlin
perfExporter: dev.stapler.stelekit.performance.PerfExporter? = null,
```

Pass it down to `SpansTab(spanRepository, ringBuffer, perfExporter)`.

---

#### Task EXPORT-1.4.3 — Add export button to `SpansTab` toolbar

**File to modify**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PerformanceDashboard.kt`

In `SpansTab`, add `perfExporter: PerfExporter?` parameter. In the first toolbar `Row` (controls row), add an export `IconButton` after the clear button:

```kotlin
if (perfExporter != null) {
    var exportState by remember { mutableStateOf<String?>(null) }  // null=idle, path=success, "ERROR: ...
    IconButton(onClick = {
        scope.launch {
            exportState = try { perfExporter.export() }
            catch (e: Exception) { "ERROR: ${e.message}" }
        }
    }) {
        Icon(Icons.Default.Download, contentDescription = "Export perf report")
    }
    // Snackbar feedback
    val state = exportState
    if (state != null) {
        LaunchedEffect(state) {
            // Show snackbar for 4s then clear
            kotlinx.coroutines.delay(4_000)
            exportState = null
        }
        // Simple overlay text — replace with SnackbarHost if a SnackbarHostState is available
        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    text = if (state.startsWith("ERROR")) state else "Exported: $state",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
```

**Acceptance**: button appears only when `perfExporter != null`; clicking it on desktop creates the file and shows the path in the overlay.

---

#### Task EXPORT-1.4.4 — Pass `perfExporter` from `StelekitViewModel` call site

Locate where `PerformanceDashboard(...)` is called in the UI (likely in `ui/screens/` or `ui/App.kt`). Pass `repositorySet?.perfExporter`.

**Files likely involved**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/ui/App.kt`

---

### Story EXPORT-1.5 — Tests for export

#### Task EXPORT-1.5.1 — Unit test `PerfExporter` in `businessTest`

**File to create**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/performance/PerfExporterTest.kt`

Test cases:
1. `export()` with an in-memory span list produces valid JSON that parses to `PerfExportReport` with correct `spans.size`
2. `export()` produces a file path ending in `.json` and containing a timestamp pattern `\d{4}-\d{2}-\d{2}-\d{4}`
3. `export()` propagates exceptions when `fileSystem.writeFile` returns `false`
4. Empty span list produces `"spans": []` (not null) in output JSON

Use a fake `SpanRepository` returning a `MutableStateFlow` and a fake `FileSystem` backed by a `MutableMap<String, String>`.

---

## Epic QTRACE-1: SQLDelight Query-Level Span Instrumentation

**As a developer**, I need the span waterfall to show individual SQLDelight query durations so that I can identify which SELECT or INSERT inside a page load is slow.

---

### Story QTRACE-1.1 — Trace context propagation element

**Context files** (read before starting):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/RingBufferSpanExporter.kt`

#### Task QTRACE-1.1.1 — Create `ActiveSpanContext.kt`

**Source set**: `commonMain`  
**File to create**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/ActiveSpanContext.kt`

```kotlin
package dev.stapler.stelekit.performance

import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element carrying the active span's trace and span IDs.
 * Install with [kotlinx.coroutines.withContext] when starting a parent span.
 * [TracingQueryWrapper] reads this to set parentSpanId on query-level child spans.
 */
data class ActiveSpanContext(
    val traceId: String,
    val parentSpanId: String,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ActiveSpanContext>
    override val key: CoroutineContext.Key<*> = Key
}
```

This is a pure KMP type with no platform dependencies. It is safe in `commonMain`.

---

### Story QTRACE-1.2 — `TracingQueryWrapper` decorator

**Context files** (read before starting):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/RingBufferSpanExporter.kt` (for `record(SerializedSpan)`)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/ActiveSpanContext.kt` (after QTRACE-1.1.1)
- Generated `SteleDatabaseQueries` interface (in `kmp/build/` after `./gradlew generateCommonMainSteleDatabaseInterface`)

#### Task QTRACE-1.2.1 — Determine the full `SteleDatabaseQueries` method list

Before writing the decorator, run:

```
./gradlew generateCommonMainSteleDatabaseInterface
```

Then read the generated file at `kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/dev/stapler/stelekit/db/SteleDatabaseQueries.kt` to enumerate all method signatures. This list drives the decorator's method set.

---

#### Task QTRACE-1.2.2 — Create `TracingQueryWrapper.kt`

**Source set**: `commonMain`  
**File to create**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/TracingQueryWrapper.kt`

The wrapper implements `SteleDatabaseQueries` (the generated interface). For every method:

1. Record `startMs = HistogramWriter.epochMs()`
2. Delegate to the wrapped `SteleDatabaseQueries`
3. Record `endMs = HistogramWriter.epochMs()`
4. Build a `SerializedSpan` with the timing and attributes
5. Call `ringBuffer.record(span)`
6. Return the delegate result

For SELECT queries that return `Query<T>`, wrap the returned `Query` to intercept `executeAsList()` / `executeAsOne()` / `executeAsOneOrNull()` — this is where the actual SQL execution occurs, not at the method call boundary. Use an inline wrapper:

```kotlin
private fun <T : Any> Query<T>.timed(spanName: String, table: String): Query<T> {
    val wrapper = this
    return object : Query<T>(wrapper.mapper) {
        override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
            val start = HistogramWriter.epochMs()
            val result = wrapper.execute(mapper)
            val end = HistogramWriter.epochMs()
            recordSpan(spanName, table, "SELECT", end - start, rowCount = null)
            return result
        }
        override fun addListener(listener: Query.Listener) = wrapper.addListener(listener)
        override fun removeListener(listener: Query.Listener) = wrapper.removeListener(listener)
    }
}
```

For write methods (INSERT/UPDATE/DELETE/UPSERT), timing wraps the `QueryResult<Long>` call directly since these are not lazy:

```kotlin
// Pattern for write methods:
fun insertBlock(uuid: String, ...) : QueryResult<Long> {
    val start = HistogramWriter.epochMs()
    val result = delegate.insertBlock(uuid, ...)
    recordSpan("sql.insertBlock", "blocks", "INSERT", HistogramWriter.epochMs() - start)
    return result
}
```

`recordSpan` is a private helper:

```kotlin
private fun recordSpan(
    name: String,
    table: String,
    operation: String,
    durationMs: Long,
    rowCount: Int? = null,
) {
    val spanId = dev.stapler.stelekit.util.UuidGenerator.generateV7()
    val nowMs = HistogramWriter.epochMs()
    val attrs = buildMap<String, String> {
        put("sql.table", table)
        put("sql.operation", operation)
        rowCount?.let { put("row.count", it.toString()) }
    }
    ringBuffer.record(
        SerializedSpan(
            name = name,
            startEpochMs = nowMs - durationMs,
            endEpochMs = nowMs,
            durationMs = durationMs,
            attributes = attrs,
            statusCode = "OK",
            traceId = activeTraceId,
            spanId = spanId,
            parentSpanId = activeParentSpanId,
        )
    )
}
```

`activeTraceId` and `activeParentSpanId` are read from a property backed by a platform-safe mechanism. Because `TracingQueryWrapper` methods are called synchronously inside `withContext(PlatformDispatcher.DB)`, they run on a specific coroutine dispatcher thread. Direct `coroutineContext` access is not available in non-`suspend` context. Use a `ThreadLocal` on JVM/Android actuals or a nullable stored reference.

**Platform-safe trace context strategy**: define an `expect` in `commonMain`:

```kotlin
// kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/CurrentSpanContext.kt
expect object CurrentSpanContext {
    fun set(ctx: ActiveSpanContext?)
    fun get(): ActiveSpanContext?
}
```

JVM/Android actual: `ThreadLocal<ActiveSpanContext?>`. iOS/WASM actual: a simple `var` (single-threaded).

The pattern for usage: in `GraphLoader.parseAndSavePage` (or wherever the root span is opened), call `CurrentSpanContext.set(ActiveSpanContext(traceId, spanId))` before any DB calls and `CurrentSpanContext.set(null)` in a `finally` block after.

**File inventory for this task**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/TracingQueryWrapper.kt` (create)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/CurrentSpanContext.kt` (create, expect declaration)
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/performance/CurrentSpanContext.jvm.kt` (create, ThreadLocal actual)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/performance/CurrentSpanContext.android.kt` (create, ThreadLocal actual)

---

### Story QTRACE-1.3 — `DebugFlagRepository` and `DebugMenuState` changes

**Context files** (read before starting):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugFlagRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugMenuState.kt`

#### Task QTRACE-1.3.1 — Add `isQueryTracingEnabled` to `DebugMenuState`

**File to modify**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugMenuState.kt`

```kotlin
data class DebugMenuState(
    val isFrameOverlayEnabled: Boolean = false,
    val isOtelStdoutEnabled: Boolean = false,
    val isJankStatsEnabled: Boolean = false,
    val isQueryTracingEnabled: Boolean = false,   // add this
    val isDebugMenuVisible: Boolean = false
)
```

---

#### Task QTRACE-1.3.2 — Persist `isQueryTracingEnabled` in `DebugFlagRepository`

**File to modify**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/DebugFlagRepository.kt`

Update `loadDebugMenuState()`:

```kotlin
fun loadDebugMenuState(): DebugMenuState = DebugMenuState(
    isFrameOverlayEnabled = getFlag("frame_overlay"),
    isOtelStdoutEnabled = getFlag("otel_stdout"),
    isJankStatsEnabled = getFlag("jank_stats"),
    isQueryTracingEnabled = getFlag("query_tracing"),   // add
)
```

Update `saveDebugMenuState()`:

```kotlin
fun saveDebugMenuState(state: DebugMenuState) {
    setFlag("frame_overlay", state.isFrameOverlayEnabled)
    setFlag("otel_stdout", state.isOtelStdoutEnabled)
    setFlag("jank_stats", state.isJankStatsEnabled)
    setFlag("query_tracing", state.isQueryTracingEnabled)   // add
}
```

---

#### Task QTRACE-1.3.3 — Add SQL query tracing toggle to debug menu UI

Locate the debug menu composable. It is likely in:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DebugMenuOverlay.kt` or a platform-specific actual

Add a `Switch` row for `isQueryTracingEnabled`, following the same pattern as the existing `isJankStatsEnabled` row. Label: "SQL query tracing".

Note: changing this flag takes effect only on the next graph open (not hot-reloaded). Add a subtitle: "Restart graph to apply".

---

### Story QTRACE-1.4 — Inject `TracingQueryWrapper` in `RepositoryFactoryImpl`

**Context files** (read before starting):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

#### Task QTRACE-1.4.1 — Conditional decorator injection

**File to modify**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`

In `RepositoryFactoryImpl.createRepositorySet`, after creating `debugFlagRepo` and the `ringBuffer`, conditionally wrap queries:

```kotlin
val rawQueries = database.steleDatabaseQueries
val tracedQueries: SteleDatabaseQueries = if (
    debugFlagRepo?.getFlag("query_tracing") == true && ringBuffer != null
) {
    TracingQueryWrapper(rawQueries, ringBuffer)
} else {
    rawQueries
}
```

Then, wherever `RestrictedDatabaseQueries(database.steleDatabaseQueries)` is constructed inside the `RepositoryFactoryImpl` (currently it happens inside each `SqlDelight*Repository` constructor), the repositories need to receive `tracedQueries` instead of `database.steleDatabaseQueries` directly.

This requires a small change: each `SqlDelight*Repository(database: SteleDatabase)` constructor currently creates its own `RestrictedDatabaseQueries(database.steleDatabaseQueries)` internally. To allow injection of `tracedQueries`, the repositories must accept `SteleDatabaseQueries` (or `SteleDatabase`) and use the provided queries reference rather than going to `database.steleDatabaseQueries` directly.

**Preferred minimal approach**: add a secondary constructor or parameter to `SqlDelightBlockRepository`, `SqlDelightPageRepository`, `SqlDelightPropertyRepository`, `SqlDelightReferenceRepository`, `SqlDelightSpanRepository`, and `SqlDelightSearchRepository`:

```kotlin
class SqlDelightBlockRepository(
    private val database: SteleDatabase,
    queries: SteleDatabaseQueries = database.steleDatabaseQueries  // default: unwrapped
) {
    private val restricted = RestrictedDatabaseQueries(queries)
    // ...
}
```

In `createRepositorySet`, pass `tracedQueries` explicitly:
```kotlin
SqlDelightBlockRepository(database, tracedQueries)
```

This is a 1-line change per repository class plus a parameter addition to the factory calls.

**Files to modify**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightPageRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightPropertyRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightReferenceRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt`

Do NOT change `HistogramWriter`, `DebugFlagRepository`, or `SqlDelightSpanRepository` — these must keep using `database.steleDatabaseQueries` directly to avoid self-referential tracing.

---

### Story QTRACE-1.5 — Set trace context in `GraphLoader.parseAndSavePage`

**Context files** (read before starting):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/CurrentSpanContext.kt` (after QTRACE-1.2.2)

#### Task QTRACE-1.5.1 — Install `CurrentSpanContext` around parse-and-save call

**File to modify**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

Find `parseAndSavePage` (or the equivalent method that creates the root OTel span). Around the block that executes DB writes, add:

```kotlin
val traceId = span.spanContext().traceId   // already available from OTel span
val spanId  = span.spanContext().spanId
CurrentSpanContext.set(ActiveSpanContext(traceId, spanId))
try {
    // ... existing DB save logic ...
} finally {
    CurrentSpanContext.set(null)
}
```

This ensures all `TracingQueryWrapper` calls during this block will have `parentSpanId` set to the `parseAndSavePage` span, producing the correct parent-child hierarchy in the waterfall.

If `parseAndSavePage` does not currently expose the OTel span object, generate a UUID for `traceId` and `spanId` using `UuidGenerator.generateV7()` instead and pass them through the call chain. The span in `RingBufferSpanExporter` will have matching IDs.

---

### Story QTRACE-1.6 — Tests for query tracing

#### Task QTRACE-1.6.1 — Unit test `TracingQueryWrapper` span emission

**Source set**: `jvmTest`  
**File to create**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/performance/TracingQueryWrapperTest.kt`

Test cases:
1. A SELECT method call with `query_tracing` active records a `SerializedSpan` with `name = "sql.select<MethodName>"`, `attributes["sql.operation"] = "SELECT"`, `durationMs >= 0`
2. A write method call records a span with `attributes["sql.operation"] = "INSERT"` (or the appropriate operation type)
3. When `CurrentSpanContext.get()` returns a non-null context, the recorded span's `traceId` and `parentSpanId` match
4. When `CurrentSpanContext.get()` returns null, `parentSpanId` is empty string (not null, not "null")

Use a real in-memory SQLDelight driver (same pattern as existing `*RepositoryTest` classes that create `SteleDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))`).

---

#### Task QTRACE-1.6.2 — Integration test: query spans appear in waterfall for page load

**Source set**: `jvmTest`  
**File to create**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/performance/QueryTracingIntegrationTest.kt`

Test steps:
1. Create a `RepositorySet` with `GraphBackend.SQLDELIGHT` and `query_tracing = true`
2. Load a test page through `GraphLoader`
3. Drain the `RingBufferSpanExporter`
4. Assert at least one span with `name` starting with `"sql."` is present
5. Assert at least one span has `traceId` matching the root `parseAndSavePage` trace ID

---

## Known Issues

### Potential Bug: `RingBufferSpanExporter` thread-safety during tracing

`RingBufferSpanExporter.record()` is documented "not thread-safe — all calls must be from a single thread or coroutine context". `TracingQueryWrapper` calls `ringBuffer.record()` from `PlatformDispatcher.DB` (a thread pool on JVM). If multiple queries execute concurrently on different DB pool threads, `record()` may be called concurrently.

**Mitigation**: Synchronize `RingBufferSpanExporter.record()` with a `synchronized(buffer)` block on JVM/Android, or switch its internal buffer to `ConcurrentLinkedDeque`. Alternatively, route `TracingQueryWrapper` span writes through a dedicated single-threaded channel (like `HistogramWriter` does). The simplest fix is `@Synchronized` annotation on `record()` for JVM/Android actuals.

**Files affected**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/RingBufferSpanExporter.kt`

---

### Potential Bug: `SteleDatabaseQueries` generated interface changes on schema modification

When a new query is added to `SteleDatabase.sq`, the generated `SteleDatabaseQueries` interface gains a new method. `TracingQueryWrapper` will fail to compile (missing override) at that point. This is the same discipline already required for `RestrictedDatabaseQueries`.

**Mitigation**: Add a comment to `SteleDatabase.sq` and to `TracingQueryWrapper.kt` cross-referencing each other. CI will catch the compile failure immediately on any `.sq` change. Document in `CLAUDE.md`'s "Write enforcement" section.

---

### Potential Bug: Android `writeFile` for non-SAF paths

`PlatformFileSystem.writeFile` on Android currently routes through SAF for all paths. The Downloads path (`/storage/emulated/0/Download/...`) is an absolute path, not a `saf://` path. If the existing implementation passes all paths to the SAF resolver, it will fail for the Downloads path.

**Mitigation**: Task EXPORT-1.3.1 includes a pre-flight check and a non-SAF fallback branch. Add a test in `androidUnitTest` that verifies `writeFile` succeeds for an absolute path to a temp directory.

**Files affected**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

---

### Potential Bug: Self-referential tracing loop for span persistence writes

The background drain loop in `RepositoryFactoryImpl.createRepositorySet` calls `spanRepository.insertSpan(span)` for every drained span. `SqlDelightSpanRepository` uses `database.steleDatabaseQueries` directly (not the traced queries). If it were accidentally wired to `tracedQueries`, each span insertion would generate a new span, which would be drained on the next tick, generating another span, ad infinitum.

**Mitigation**: Task QTRACE-1.4.1 explicitly excludes `SqlDelightSpanRepository` from the traced queries injection. Add a comment in `RepositoryFactoryImpl` to document this constraint. Add a test that verifies span count does not grow unboundedly after a single page load.

---

### Potential Bug: `kotlinx-datetime` absent from `commonMain` dependencies

`PerfExporter.formatTimestamp` uses `kotlinx.datetime`. This library may be present only as a transitive dependency and not explicitly declared. Transitive dependencies can be dropped without warning during version upgrades.

**Mitigation**: Task EXPORT-1.2.2 includes a verification step. Add `implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")` explicitly to `commonMain` dependencies in `kmp/build.gradle.kts`.

---

## Implementation Sequence

Tasks should be executed in this order to avoid blocked dependencies:

```
Phase 1 (Foundation — no UI changes):
  EXPORT-1.1.1  PerfExportReport data class
  EXPORT-1.1.2  KNOWN_OPERATIONS constant
  QTRACE-1.1.1  ActiveSpanContext coroutine element
  QTRACE-1.3.1  DebugMenuState.isQueryTracingEnabled

Phase 2 (Logic — no wiring yet):
  EXPORT-1.2.1  FileSystem.getDownloadsPath()
  EXPORT-1.2.2  PerfExporter service
  EXPORT-1.3.1  Android getDownloadsPath() actual
  EXPORT-1.3.2  Manifest permission
  QTRACE-1.2.1  Enumerate SteleDatabaseQueries methods
  QTRACE-1.2.2  TracingQueryWrapper + CurrentSpanContext expect/actual
  QTRACE-1.3.2  DebugFlagRepository persistence

Phase 3 (Wiring):
  QTRACE-1.4.1  Repository secondary constructors + factory injection
  QTRACE-1.5.1  GraphLoader CurrentSpanContext installation
  EXPORT-1.4.1  PerfExporter into RepositorySet

Phase 4 (UI):
  EXPORT-1.4.2  PerformanceDashboard parameter
  EXPORT-1.4.3  Export button in SpansTab toolbar
  EXPORT-1.4.4  Pass perfExporter from ViewModel call site
  QTRACE-1.3.3  SQL query tracing toggle in debug menu

Phase 5 (Tests):
  EXPORT-1.5.1  PerfExporter unit tests
  QTRACE-1.6.1  TracingQueryWrapper unit tests
  QTRACE-1.6.2  Query tracing integration test
```

---

## Checklist

### EXPORT-1 (Perf Export)
- [ ] EXPORT-1.1.1 `PerfExportReport.kt` created
- [ ] EXPORT-1.1.2 `KNOWN_OPERATIONS` added to `HistogramWriter`
- [ ] EXPORT-1.2.1 `getDownloadsPath()` in `FileSystem`
- [ ] EXPORT-1.2.2 `PerfExporter.kt` created
- [ ] EXPORT-1.3.1 Android `getDownloadsPath()` override
- [ ] EXPORT-1.3.2 Android manifest `WRITE_EXTERNAL_STORAGE` (API ≤28)
- [ ] EXPORT-1.4.1 `PerfExporter?` in `RepositorySet` and factory wiring
- [ ] EXPORT-1.4.2 `PerformanceDashboard` parameter added
- [ ] EXPORT-1.4.3 Export button in `SpansTab` toolbar
- [ ] EXPORT-1.4.4 Call site passes `perfExporter`
- [ ] EXPORT-1.5.1 Unit tests pass

### QTRACE-1 (Query Tracing)
- [ ] QTRACE-1.1.1 `ActiveSpanContext.kt` created
- [ ] QTRACE-1.2.1 Generated interface methods enumerated
- [ ] QTRACE-1.2.2 `TracingQueryWrapper.kt` + `CurrentSpanContext` expect/actual created
- [ ] QTRACE-1.3.1 `DebugMenuState.isQueryTracingEnabled` added
- [ ] QTRACE-1.3.2 `DebugFlagRepository` loads/saves `query_tracing` flag
- [ ] QTRACE-1.3.3 Debug menu toggle added
- [ ] QTRACE-1.4.1 Repository constructors accept `SteleDatabaseQueries`; factory injects `tracedQueries`
- [ ] QTRACE-1.5.1 `GraphLoader` installs `CurrentSpanContext`
- [ ] QTRACE-1.6.1 `TracingQueryWrapper` unit tests pass
- [ ] QTRACE-1.6.2 Integration test verifies sql.* spans in waterfall
- [ ] `./gradlew ciCheck` green
