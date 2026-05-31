# Architecture Research: Span/Tracing Pipeline

## Key files read
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/RingBufferSpanExporter.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/SpanEmitter.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/QueryStatsCollector.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/OtelRepositoryWrappers.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModelDependencies.kt`

---

## 1. RingBufferSpanExporter — the span store

```kotlin
class RingBufferSpanExporter(val capacity: Int = 1000) {
    @kotlin.concurrent.Volatile var enabled: Boolean = false
    fun record(span: SerializedSpan)      // no-op when enabled=false
    fun snapshot(): List<SerializedSpan>  // non-destructive copy
    fun drain(): List<SerializedSpan>     // returns all + clears buffer
    fun clear()
}
```

**Key facts:**
- `enabled = false` by default — must set `enabled = true` before any spans are recorded
- Not thread-safe; all calls must come from a single thread/coroutine context
- Fixed capacity (default 1000); oldest span dropped when full — for a full session benchmark use `capacity = 10000` or larger
- `drain()` is the correct end-of-session call to extract all spans atomically

**For the benchmark:** instantiate once with large capacity, enable before the session starts, drain after:
```kotlin
val ringBuffer = RingBufferSpanExporter(capacity = 10_000).also { it.enabled = true }
```

## 2. SpanEmitter — the write-side facade

```kotlin
class SpanEmitter(private val ringBuffer: RingBufferSpanExporter?) {
    fun emit(name: String, startMs: Long, endMs: Long = epochMs(), traceId: String, ...)
    fun trace(name: String, startMs: Long, attrs: Map<String, String> = emptyMap())
    fun error(name: String, startMs: Long, errorMsg: String, ...)
    val isEnabled: Boolean get() = ringBuffer != null
}
```

- Each `emit()` call creates one `SerializedSpan` and calls `ringBuffer?.record(span)`.
- Auto-attaches `"session.id" to AppSession.id` to every span's attributes.
- Null-safe — if `ringBuffer` is null, all calls are no-ops.

## 3. Span names emitted by the ViewModel

From `StelekitViewModel.navigateTo()`:
```kotlin
spanEmitter.emit(
    name = "navigation",
    startMs = navStart,
    endMs = navEnd,
    attrs = mapOf(
        "screen" to screen::class.simpleName.orEmpty(),
        "duration.ms" to navDurationMs.toString(),
    )
)
```

From `SqlDelightSearchRepository.searchBlocksByContent()`:
- `name = "search.blocks"`, attrs: `query.terms`, `result.count`, `duration.ms`

From `SqlDelightSearchRepository.searchPagesByTitle()`:
- `name = "search.pages"`, attrs: `result.count`, `duration.ms`

From `SqlDelightSearchRepository.searchWithFilters()`:
- Emits spans via the `SpanEmitter` initialized in the repository constructor

**Note:** `StelekitViewModel` constructs its `spanEmitter` from `deps.ringBuffer`:
```kotlin
private val spanEmitter = dev.stapler.stelekit.performance.SpanEmitter(deps.ringBuffer)
```
So the same `RingBufferSpanExporter` instance passed into `StelekitViewModelDependencies.ringBuffer` receives both ViewModel navigation spans and repository-level search spans.

## 4. Enabling span recording — full wiring pattern

```kotlin
// 1. Create the ring buffer with spans enabled
val ringBuffer = RingBufferSpanExporter(capacity = 10_000).also { it.enabled = true }

// 2. Pass it into the search repository (if constructing manually)
val searchRepo = SqlDelightSearchRepository(
    database = ...,
    histogramWriter = repoSet.histogramWriter,
    ringBuffer = ringBuffer,
    writeActor = repoSet.writeActor,
)

// 3. Pass it into StelekitViewModelDependencies
val deps = StelekitViewModelDependencies(
    ...
    ringBuffer = ringBuffer,
)

// 4. Construct the ViewModel — it creates SpanEmitter(deps.ringBuffer) internally
val viewModel = StelekitViewModel(deps)

// --- run benchmark session ---

// 5. Drain at end
val spans: List<SerializedSpan> = ringBuffer.drain()
```

## 5. OtelRepositoryWrappers — platform-specific (JVM only)

`OtelRepositoryWrappers.kt` declares:
```kotlin
expect fun wrapWithOtelIfAvailable(repo: PageRepository, tracerName: String): PageRepository
expect fun wrapWithOtelIfAvailable(repo: SearchRepository, tracerName: String): SearchRepository
```

The `actual` JVM implementation uses the OpenTelemetry SDK (available in `jvmMain` dependencies). For the benchmark test (in `jvmTest`), the OTel wrapper is **optional** — `RingBufferSpanExporter` collects all spans internally without needing OTel. Do not use the OTel wrappers in the benchmark; they add cross-process OTLP export overhead.

## 6. QueryStatsCollector — separate from spans

`QueryStatsCollector` is for SQL-level query statistics (calls/latency per table:operation), stored separately from spans. It is NOT part of the span pipeline.

For the benchmark:
- Call `repoSet.queryStatsCollector?.drainNow()` after the session to flush accumulated stats
- Read with `repoSet.queryStatsRepository?.getTopByTotalMs("unknown", 20)` (same pattern as existing test 2)
- Write to `benchmark-session-query-stats.json` if desired (separate from span output)

## 7. SerializedSpan schema — for JSON output

```kotlin
@Serializable
data class SerializedSpan(
    val name: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val durationMs: Long,
    val attributes: Map<String, String> = emptyMap(),
    val statusCode: String = "OK",
    val traceId: String = "",
    val spanId: String = "",
    val parentSpanId: String = "",
)
```

`kotlinx.serialization` is on the classpath (`commonMain` deps). The span list can be serialized directly:
```kotlin
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

val json = Json { prettyPrint = true }
File(outputDir, "benchmark-session-spans.json").writeText(
    json.encodeToString(spans)
)
```

---

## Summary

- Enable spans with `RingBufferSpanExporter(capacity=10_000).also { it.enabled = true }` before constructing the ViewModel — the ViewModel's `SpanEmitter` is wired at construction time and only captures if the buffer is present.
- Known span names: `"navigation"` (ViewModel), `"search.blocks"`, `"search.pages"` (SqlDelightSearchRepository).
- Drain with `ringBuffer.drain()` at the end of the session; serialize with `kotlinx.serialization.json.Json.encodeToString(spans)`.
- `QueryStatsCollector` and `RingBufferSpanExporter` are independent pipelines — both should be drained and written to separate output files.
