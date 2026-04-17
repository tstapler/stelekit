# Findings: Architecture

## Summary

This research investigates patterns for instrumenting a Kotlin Multiplatform (KMP) note-taking app (SteleKit) with OpenTelemetry (OTel) observability without coupling domain code to telemetry infrastructure. The analysis covers four critical areas: span injection patterns at the repository/coroutine level, histogram storage in SQLDelight, debug menu implementation across KMP platforms, and local-first exporter configuration.

**Key Findings:**
1. **Decorator/wrapper pattern on repositories** is the most idiomatic approach for KMP, leveraging existing interface abstractions
2. **Coroutine context elements** can extend MDC-style propagation but are less invasive than direct instrumentation
3. **SQLDelight schema** can efficiently store histogram data with `metric_name`, `bucket_ms`, and `count` columns for approximate percentile queries
4. **KMP debug menus** can use expect/actual overlays with BuildConfig-style flags and persistent DataStore/SQLDelight toggles
5. **Local exporters** should target OTLP/stdout and in-memory span snapshots for bug reports (no gRPC/remote backend)

## Options Surveyed

### A. OTel Span Injection Patterns

#### Option 1: Decorator Pattern on Repository Interfaces

**Approach:** Wrap each repository interface with instrumented decorators.

```kotlin
class InstrumentedBlockRepository(
    private val delegate: IBlockRepository,
    private val tracer: Tracer
) : IBlockRepository {
    override suspend fun getBlock(id: Long): Result<Block?> {
        val span = tracer.spanBuilder("getBlock")
            .setAttribute("block.id", id.toString())
            .startSpan()
        return try {
            span.use { delegate.getBlock(id) }
        } finally {
            span.end()
        }
    }
}
```

**Pros:** Zero coupling of domain code to OTel; leverages existing interface-based architecture; composable; testable  
**Cons:** Decorator per repository method; memory overhead; limited to method boundaries

**Invasiveness:** Low (0 changes to domain code) | **Granularity:** Method-level only

---

#### Option 2: Coroutine Context Element (MDC Extension)

**Approach:** Store span context in coroutine context element, propagate via scope inheritance.

```kotlin
class SpanContextElement(val tracer: Tracer, val spanId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpanContextElement>
}

suspend inline fun <T> withTracing(tracer: Tracer, spanName: String, block: suspend () -> T): T {
    val span = tracer.spanBuilder(spanName).startSpan()
    return withContext(SpanContextElement(tracer, span.spanContext.spanId)) {
        try { block() } finally { span.end() }
    }
}
```

**Pros:** Automatic propagation through coroutine hierarchy; captures async boundaries naturally; idiomatic Kotlin  
**Cons:** All async functions must be suspend; implicit dependency; harder to test

**Invasiveness:** Very low | **Granularity:** Per-coroutine-scope + method boundaries

---

#### Option 3: Aspect-Oriented Programming (Annotations)

**Pros:** Minimal boilerplate; declarative  
**Cons:** KSP annotation processors don't work on JS/Native targets [TRAINING_ONLY — verify]; complex to debug

**Recommendation:** Not viable for full KMP due to platform limitations.

---

#### Option 4: Manual Span Calls at Call Sites

**Pros:** Maximum control; works everywhere  
**Cons:** Direct coupling of domain code to OTel API; boilerplate at every instrumentation point; violates clean architecture

**Recommendation:** Only use as fallback for non-critical paths.

---

### B. SQLDelight Histogram Storage

#### Option 1: Bucketed Histogram Table (Recommended)

```sql
CREATE TABLE metric_histograms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    metric_name TEXT NOT NULL,
    bucket_bound_ms INTEGER NOT NULL,  -- 0, 16, 33, 50, 100, 500, 1000, 5000+
    count INTEGER NOT NULL DEFAULT 0,
    recorded_at INTEGER NOT NULL,
    UNIQUE(metric_name, bucket_bound_ms, recorded_at)
);

CREATE INDEX idx_histogram_metric_time ON metric_histograms(metric_name, recorded_at DESC);
```

**Recommended bucket boundaries:** `0, 16, 33, 50, 100, 500, 1000, 5000, infinity`

Covers UI frame timing (16ms target), typical DB ops (33–500ms), and slow ops (5s+).

**Pros:** Efficient storage; multiplatform (pure SQL); quereable for approximate percentiles; TTL retention easy  
**Cons:** Approximate only; bucket boundaries must be chosen in advance

---

#### Option 2: Raw Value Storage (Not Recommended)

One row per span — unbounded growth; query overhead for percentiles; not viable for production.

---

#### Option 3: Precomputed Percentiles Table

Pre-computed P50/P95/P99 per time window. Requires external computation; loses detail; double maintenance.

---

### C. Debug Menu in KMP Compose

#### Option 1: Expect/Actual Platform Overlays (Recommended for UI)

```kotlin
// commonMain
expect class DebugMenuOverlay {
    fun show()
    fun hide()
}

// androidMain
actual class DebugMenuOverlay : DebugMenuOverlay {
    actual fun show() { /* Android Dialog + shake detector */ }
}

// jvmMain
actual class DebugMenuOverlay : DebugMenuOverlay {
    actual fun show() { /* Desktop composable window */ }
}
```

**Pros:** Platform-specific UI; clean KMP separation; idiomatic  
**Cons:** Duplicate implementation per platform

---

#### Option 2: BuildConfig-Style Feature Flags

Compile-time flags to completely strip debug code from release builds.

**Pros:** Zero runtime overhead in release; dead code eliminated  
**Cons:** No native BuildConfig equivalent on JVM/JS/Native; requires rebuild to toggle

---

#### Option 3: Persistent DataStore Toggle (Recommended for Runtime)

```kotlin
interface DebugPreferences {
    val isInstrumentationEnabled: Flow<Boolean>
    suspend fun setInstrumentation(enabled: Boolean)
}
```

**Pros:** Toggle at runtime without rebuild; multiplatform via expect/actual; persistent; Flow-based

---

#### Option 4: SQLDelight Debug Flags Table

Co-locate toggles with metrics storage. Reuses existing infrastructure; queryable alongside metrics.

---

**Combined Recommendation:** Expect/Actual for platform-specific UI overlay + DataStore-backed Flow for persistent toggles + compile-time BuildConfig flags to strip debug code from release.

---

### D. OTel Exporters (Local-Only)

#### Option 1: OTLP/stdout Exporter

Zero setup; standard OTLP format; works everywhere; can pipe to file.

```kotlin
class OtlpStdoutExporter : SpanExporter {
    override suspend fun export(spans: List<ReadableSpan>): ExportResult {
        println(Json.encodeToString(spans.toProtos()))
        return ExportResult.Success
    }
}
```

---

#### Option 2: File-Based OTLP Exporter

Persistent; queryable; bounded storage (rotate files); works everywhere.

```kotlin
class OtlpFileExporter(private val tracesDir: File, private val batchSize: Int = 100) : SpanExporter {
    private val spans = mutableListOf<ReadableSpan>()
    override suspend fun export(spans: List<ReadableSpan>): ExportResult {
        this.spans.addAll(spans)
        if (this.spans.size >= batchSize) {
            val file = tracesDir.resolve("traces-${System.currentTimeMillis()}.json")
            file.writeText(Json.encodeToString(this.spans.toProtos()))
            this.spans.clear()
        }
        return ExportResult.Success
    }
}
```

---

#### Option 3: In-Memory Span Ring Buffer (Recommended for Bug Reports)

```kotlin
class InMemorySpanBuffer(maxSpans: Int = 1000) : SpanExporter {
    private val spans = ConcurrentLinkedDeque<ReadableSpan>()
    override suspend fun export(spans: List<ReadableSpan>): ExportResult {
        spans.forEach { span ->
            this.spans.addLast(span)
            if (this.spans.size > maxSpans) this.spans.removeFirst()
        }
        return ExportResult.Success
    }
    fun snapshot(): List<ReadableSpan> = spans.toList()
}
```

Zero overhead; perfect for bug reports; lost on crash without companion file exporter.

---

**Combined Recommendation:** OTLP/stdout for development + In-memory ring buffer for production + optional file exporter for long sessions. No remote exporters (gRPC, Jaeger) in Phase 1.

## Trade-off Matrix

| Option | Invasiveness | Observability Granularity | Storage Overhead | Debug Ergonomics | Notes |
|--------|--------------|--------------------------|------------------|------------------|-------|
| Decorator Repository Pattern | Low (0 domain changes) | Method-level spans | External processor | Good | **Recommended** for repositories |
| Coroutine Context Element | Very low (context lookups) | Per-coroutine scope | External processor | Excellent | **Recommended** for hierarchies |
| Aspect-Oriented (KSP) | Low (annotations) | Method-level | External processor | Poor on JS/Native | Not viable for full KMP |
| Manual Span Calls | Very high (OTel in domain) | Arbitrary | External processor | Poor | Last resort only |
| Bucketed Histogram | N/A | ~10 rows/metric/hour | Low | Good (simple queries) | **Recommended** for histograms |
| Raw Value Storage | N/A | Per-span row | Unbounded | Poor | Not viable |
| Expect/Actual Overlays | Low (expect only) | Per-platform UI | Low | Excellent | **Recommended** for UI |
| DataStore Toggles | Low (Flow-based) | Runtime control | Low | Good | Pair with Expect/Actual |
| OTLP/stdout Exporter | Very low (no setup) | Full trace | Unbounded | Excellent | **Recommended** for development |
| In-Memory Ring Buffer | Very low (no setup) | Last N spans | Fixed | Excellent | **Recommended** for production |

## Risk and Failure Modes

### A. Span Injection Risks

1. **Decorator Chaining Complexity** — Multiple decorators create nested wrappers; hard to debug. Mitigation: document decorator order; use named spans with layer ID.

2. **Tracer Context Not Serializable** — If coroutine serialized, tracer serialization fails. Mitigation: strip context element before serialization, re-attach after.

3. **Span Context Loss Across Platform Boundaries** — OTel context lost when crossing FFI. Mitigation: string serialization for span IDs; document FFI boundary contracts.

4. **Baggage Propagation Not Automatic** — Developers forget to pass context to spawned coroutines. Mitigation: use structured concurrency (scope inheritance).

### B. Histogram Storage Risks

1. **Bucket Boundaries Mismatch** — Wrong boundaries for operation characteristics. Mitigation: start conservative (10ms, 100ms, 1s, 10s), adjust after 1 week of data.

2. **Timezone/DST Issues** — DST can cause duplicate timestamps. Mitigation: use Unix timestamps (always UTC).

3. **SQLite Recursion Limit** — CTE depth >1000 rows exceeds default limit. Mitigation: use iterative aggregation; test with large histogram tables.

4. **Concurrent Insert Conflicts** — Multiple threads hit UNIQUE constraint. Mitigation: `INSERT OR IGNORE` with pre-aggregation; batch histogram updates.

### C. Debug Menu Risks

1. **Expect/Actual Drift** — Desktop overlay works, mobile has bugs. Mitigation: define test interface contract.

2. **Debug Flag Not Stripped** — Instrumentation left in production builds. Mitigation: aggressive R8 dead code elimination; test in release.

3. **DataStore on Main Thread** — Blocks rendering. Mitigation: use `collectAsState`, not blocking reads.

4. **Debug Files Leak Sensitive Data** — Traces contain block IDs, timestamps. Mitigation: sanitize attributes before export.

### D. Exporter Risks

1. **stdout Spams Logs** — Every span = unbuffered stdout write. Mitigation: buffer to 100-span batches.

2. **File Exporter Fills Mobile Storage** — Long sessions write many files. Mitigation: TTL (delete files > 1 hour old); warn if cache > 500 MB.

3. **Ring Buffer Lost on Crash** — No post-mortem traces. Mitigation: pair with file exporter; write snapshot periodically.

4. **Tracer Init Fails Silently** — OTel setup failure drops spans with no error. Mitigation: return `Result<SpanExporter>` from factory; handle in logging setup.

## Migration and Adoption Cost

### Phase 1: Foundation (2–3 days)
- Decorator pattern + coroutine context element
- In-memory span exporter
- **Result:** Trace method calls, see durations in logs

### Phase 2: Persistence (3–5 days)
- SQLDelight histogram table + recording
- File OTLP exporter
- **Result:** Query historical durations, analyze trends

### Phase 3: UX (3–5 days)
- Debug menu overlay (expect/actual)
- DataStore toggles
- Ring buffer for crash reports
- **Result:** Users can enable/disable instrumentation, submit traces with bugs

**Total:** 8–13 days (1.5–2.5 weeks) for full implementation with one developer

## Operational Concerns

### Memory Overhead
- Span objects: ~300 bytes each; at 10 spans/second: ~3 MB/second buffered
- Ring buffer (1000 spans): ~300 KB steady state — acceptable on modern devices

### CPU Overhead
- Span creation: ~10–50 μs per span; at 10 spans/second: <1% CPU

### Disk I/O
- File OTLP: ~100 KB per 100 spans; at 1000 spans/hour: ~1 MB/hour — on par with app logging

### Data Retention and Privacy
- Keep 30 days; prune via `DELETE FROM metric_histograms WHERE recorded_at < now() - interval('30 days')`
- Filter sensitive attributes at exporter level (allowlist span names; redact block content, user IDs)
- Debug toggle: "Enable performance data collection" — disabled by default (opt-in)

### Accessing Traces in Production
- Crash reports: include ring buffer snapshot in feedback
- Long sessions: ship file OTLP with bug report email
- Live debugging: send debug-menu-enabled build to user; reproduce; ship logs

## Prior Art and Lessons Learned

### OpenTelemetry in Similar Projects
- **Logseq (Source):** No structured traces; DataScript coupling made observability difficult
- **Obsidian:** Extensive performance profiling in debug builds, local file export
- **Notion:** Decorators on service layers (not repository) work well; spans need semantic names

### Common Pitfalls to Avoid
1. **Tracer as Singleton in Global State** — Can't replace in tests; couples to OTel global state. Inject via DI, use interface abstraction.
2. **Span Attributes Without Cardinality Limits** — Block IDs / UUIDs as attributes cause memory explosion. Use only low-cardinality attributes.
3. **Histogram Buckets Too Granular** — Every millisecond = SQLite UNIQUE constraint failure. Use exponential/logarithmic buckets.
4. **Forgetting to Flush Spans Before App Termination** — Crashes lose trace context. Register shutdown hook; flush synchronously in crash handler.

### From Kotlin/JVM Ecosystem
- **Micrometer:** Uses decorators with `Timer.record { }` syntax — adapt for KMP with `suspend` functions
- **Kotlin Coroutines MDC:** ThreadLocal replaced with CoroutineContext — right place for cross-cutting concerns
- **Arrow-kt:** `Result<T>` monad — use for exporter return types

## Open Questions

- [ ] Does `io.opentelemetry:opentelemetry-api` expose identical API across JVM/JS/Native? [TRAINING_ONLY — verify]
- [ ] What's acceptable error margin for P95/P99 approximation from bucketed data?
- [ ] Should debug menu be disabled if signed release key detected (Play Store compliance)?
- [ ] Does OTel Kotlin SDK work identically on JS/Native, or are there platform-specific variants?
- [ ] Can OTel span context be safely serialized/deserialized for IPC or background work?

## Recommendation

### Architecture Pattern

```
Repository Operation
  → Decorator Span (method boundary)
  → Coroutine Context Element (nested scope, if needed)
  → Histogram Recording (duration bucketing → SQLDelight)
  → Span Exporter (stdout dev / file+memory prod)

Domain code: Zero OTel imports
Instrumentation: Isolated in decorator + exporter layers
```

### Recommended Tech Stack

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Span injection | Decorator pattern | Idiomatic KMP, zero coupling |
| Hierarchy tracing | Coroutine context element | Automatic propagation |
| Histogram storage | SQLDelight bucketed table | Multiplatform, efficient |
| Debug menu | Expect/actual overlays | Native UX per platform |
| Preferences | DataStore (commonMain abstraction) | Persistent, Flow-based |
| Development exporter | OTLP/stdout | Simple, standard format |
| Production exporter | In-memory ring buffer + file backup | Low overhead, crash-safe |

## Pending Web Searches

1. `"OpenTelemetry Kotlin Multiplatform JS Native support 2025"` — SDK feature parity across JVM/JS/Native
2. `"SQLite recursive CTE depth limit performance impact"` — Histogram percentile query safety
3. `"Kotlin Coroutine Context Element serialization across platforms"` — IPC/FFI boundary crossing
4. `"OpenTelemetry OTLP JSON format specification"` — Exact protobuf message structure for local export
5. `"DataStore vs SQLDelight for app preferences Kotlin Multiplatform"` — API differences, platform coverage
6. `"Performance overhead OpenTelemetry span allocation Kotlin"` — Actual CPU/memory cost at 10+ spans/sec
7. `"Histogram bucket boundaries for UI frame timing database operations"` — Exponential vs fixed bucketing
8. `"kotlin expect/actual pattern code generation Gradle KSP"` — Reducing duplicate code across platforms
