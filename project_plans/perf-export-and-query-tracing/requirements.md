# Requirements: Perf Export + SQLDelight Query-Level Tracing

**Feature ID**: PERF-EXPORT  
**Status**: Requirements  
**Last Updated**: 2026-04-21  
**Author**: Tyler Stapler

---

## Problem Statement

SteleKit has a production-grade span waterfall viewer (`PerformanceDashboard.kt`) that stores spans in SQLite and displays them in-app. Two gaps block effective AI-assisted performance analysis:

1. **No export pathway** — the span SQLite DB is app-private on Android and has no export UI on either platform. AI analysis of past sessions requires the raw data, which is currently inaccessible.

2. **Coarse instrumentation** — spans exist only at the `parseAndSavePage` call level (7 child spans). Individual SQLDelight queries have no spans, so the waterfall shows `db.getBlocks` taking 40ms with no breakdown of which SELECT or INSERT dominated.

---

## Stakeholders

| Role | Concern |
|---|---|
| Developer (Tyler) | AI-assisted outlier analysis of past sessions |
| AI assistant (Claude) | Needs structured JSON of spans to run analysis without direct DB access |

---

## Functional Requirements

### FR-1: Perf Report Export

**FR-1.1** — A "Export Perf Report" button must appear in the `PerformanceDashboard` toolbar (Spans tab toolbar row).

**FR-1.2** — On desktop (JVM), the export must write to `~/Downloads/stelekit-perf-YYYY-MM-DD-HHmm.json` using `DesktopFileSystem.writeFile`.

**FR-1.3** — On Android, the export must write to the shared Downloads directory. The file must be accessible from outside the app (readable by the user via Files app and transferable to a desktop for AI analysis). This requires writing to `Environment.DIRECTORY_DOWNLOADS` via `MediaStore` or direct `File` access (not app-private storage and not SAF graph tree).

**FR-1.4** — The exported JSON must conform to this schema:

```json
{
  "exported_at": 1714000000000,
  "app_version": "0.9.4",
  "platform": "android",
  "spans": [
    {
      "name": "parseAndSavePage",
      "traceId": "abc123",
      "spanId": "def456",
      "parentSpanId": "",
      "startEpochMs": 1714000001000,
      "endEpochMs": 1714000001043,
      "durationMs": 43,
      "attributes": { "page.name": "My Page" },
      "statusCode": "OK"
    }
  ],
  "histograms": {
    "graph_load": { "operationName": "graph_load", "p50Ms": 12, "p95Ms": 87, "p99Ms": 200, "sampleCount": 450 },
    "navigation": { "operationName": "navigation", "p50Ms": 5, "p95Ms": 30, "p99Ms": 60, "sampleCount": 1200 }
  }
}
```

**FR-1.5** — After a successful export, a snackbar (desktop) or toast (Android) must display the full file path written.

**FR-1.6** — The export must read all spans from `SpanRepository.getRecentSpans(limit = 10_000)` and all histogram summaries from `HistogramWriter.queryPercentiles(operationName)` for the standard operation set: `["frame_duration", "graph_load", "navigation", "search", "editor_input", "sql.select", "sql.insert", "sql.update", "sql.delete"]`.

**FR-1.7** — The export operation must be non-blocking from the UI thread; it runs in a coroutine on `Dispatchers.IO`.

**FR-1.8** — If the export fails (permission denied, disk full), the snackbar must show the error message rather than the file path.

### FR-2: SQLDelight Query-Level Span Instrumentation

**FR-2.1** — A `TracingQueryWrapper` class in `commonMain` must intercept all calls to `SteleDatabaseQueries` and wrap each method execution in a child `SerializedSpan` with:
  - `name`: `sql.<methodName>` (e.g., `sql.selectPageByUuid`, `sql.insertBlock`)
  - `attributes["sql.table"]`: derived from method name (e.g., `"pages"` for `selectPageByUuid`)
  - `attributes["sql.operation"]`: one of `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `UPSERT`, `TRANSACTION`
  - `attributes["row.count"]`: number of rows returned, for SELECT queries only (set after collection)
  - `startEpochMs` / `endEpochMs` / `durationMs`: measured around the actual query execution
  - `traceId` / `parentSpanId`: propagated from a coroutine context element if present, otherwise empty string

**FR-2.2** — The `TracingQueryWrapper` must record completed spans directly to `RingBufferSpanExporter` (not through `DatabaseWriteActor` to avoid self-referential DB writes during tracing).

**FR-2.3** — Query tracing must be gated behind a `DebugFlagRepository` flag with key `"query_tracing"`. When the flag is disabled (default), `TracingQueryWrapper` must add zero overhead — the wrapper must not be instantiated in the call path at all when the flag is off.

**FR-2.4** — `DebugMenuState` must expose `isQueryTracingEnabled: Boolean`. `DebugFlagRepository.loadDebugMenuState()` and `saveDebugMenuState()` must include this flag.

**FR-2.5** — The debug menu UI must include a toggle for "SQL query tracing" that persists the `query_tracing` flag.

**FR-2.6** — Query tracing must not bypass `DatabaseWriteActor`. The wrapper intercepts at the `SteleDatabaseQueries` layer, which is already downstream of the actor. Write queries already serialized through the actor will still be serialized — the tracing wrapper only observes, it does not reorder.

**FR-2.7** — `RepositoryFactoryImpl.createRepositorySet` must conditionally inject `TracingQueryWrapper` when `query_tracing` flag is enabled. The flag must be read once at graph-open time; hot-reload is not required in Phase 1.

---

## Non-Functional Requirements

**NFR-1 (Performance)** — When `query_tracing` is disabled, the overhead added by this feature to any query path must be zero (no wrapper instance, no flag check per query). When enabled, per-query overhead must not exceed 0.5ms additional latency.

**NFR-2 (Storage)** — Exported JSON for 10,000 spans must be under 20MB. `SerializedSpan.attributes` is already bounded; no additional size constraints are needed.

**NFR-3 (Platform safety)** — No `android.*` imports in `commonMain`. The export path for Android must use a platform-specific actual, consistent with the existing `expect/actual` pattern in `PlatformFileSystem`.

**NFR-4 (Write safety)** — Tracing spans are written to the `RingBufferSpanExporter` in-memory buffer only, never synchronously to SQLite during a traced query. The background drain loop in `RepositoryFactoryImpl.createRepositorySet` handles eventual persistence.

**NFR-5 (Coroutine scope)** — No `rememberCoroutineScope()` value may be injected into `TracingQueryWrapper` or the export logic. Both own their execution context via `withContext` or the existing composable scope.

**NFR-6 (commonMain purity)** — `TracingQueryWrapper` must compile cleanly on all targets (JVM, Android, iOS, WASM). No `java.lang.ThreadLocal` or `android.*` may be used in `commonMain`. Coroutine context elements (`CoroutineContext.Element`) are the correct mechanism for trace context propagation.

---

## Constraints

- The `FileSystem.writeFile(path, content)` method in `commonMain` already exists and works for both JVM and Android SAF paths. However, SAF paths (graph tree) are not accessible from outside the app. Android export must use a separate mechanism writing to shared Downloads — this is a platform-specific concern handled in `androidMain`.
- `kotlin.coroutines.coroutineContext` access is only valid inside `suspend` functions. The `SteleDatabaseQueries` API returns `Query<T>` objects whose `.executeAsList()` / `.executeAsOne()` / `.executeAsOneOrNull()` calls are synchronous on JVM/Android. Timing must be measured around the synchronous call, not asynchronously.
- `DatabaseWriteActor` already serializes all writes; the tracing wrapper adds no serialization concern for writes. For reads (SELECT flows returning `Query<T>`), wrapping is technically feasible but adds complexity for `Flow`-based queries. Phase 1 scope: instrument synchronous execute calls only (`executeAsList`, `executeAsOne`, `executeAsOneOrNull`), not `asFlow()` chains.

---

## Out of Scope

- Continuous export / live streaming of spans to an external endpoint
- Import of exported JSON back into the app
- Tracing of `asFlow()` reactive queries (deferred to Phase 2)
- Platform actuals for iOS and WASM (stubs returning `false`/no-op are sufficient for Phase 1)
- Automated export scheduling or rotation

---

## Acceptance Criteria

| ID | Criterion |
|---|---|
| AC-1 | Pressing "Export Perf Report" on desktop produces a readable `.json` file in `~/Downloads/` within 3 seconds for up to 10,000 spans |
| AC-2 | The same file, when parsed, validates against the JSON schema in FR-1.4 |
| AC-3 | On Android, the exported file appears in the system Downloads folder and is openable by the Files app |
| AC-4 | A snackbar/toast appears after export with the file path or an error message |
| AC-5 | With `query_tracing` enabled and a page load triggered, the Spans tab shows child spans named `sql.selectPageByUuid`, `sql.selectBlocksForPage`, etc. under the `parseAndSavePage` root |
| AC-6 | With `query_tracing` disabled (default), a page load produces the same span count as before this feature |
| AC-7 | `./gradlew ciCheck` passes with no new lint/detekt warnings |
