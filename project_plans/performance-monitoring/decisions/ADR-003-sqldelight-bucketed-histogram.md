# ADR-003: SQLDelight Bucketed Histogram Storage for Always-On Latency Metrics

**Date**: 2026-04-13
**Status**: Accepted
**Deciders**: Tyler Stapler

---

## Context

Always-on latency collection must meet two constraints that are in tension:

1. **Zero UI impact**: writes must never stall the main thread.
2. **Persistent across app restarts**: in-memory counters reset on crash, losing exactly the data needed for bug analysis.

Four storage strategies were evaluated:

| Strategy | Persistence | Write Cost | Query Cost | Implementation Effort |
|----------|------------|-----------|------------|----------------------|
| In-memory `AtomicLong` array (existing `PerformanceMonitor`) | None | Zero | Zero | Already exists |
| SQLDelight raw event log (one row per event) | Full | One write per event (risky on main thread) | Table scan for percentiles | Low |
| SQLDelight bucketed histogram (one row per operation+bucket) | Full | UPSERT increments counter (amortized constant) | Direct read of bounded row set | Medium |
| External time-series DB (InfluxDB, Prometheus) | Full | Network I/O (not viable — Phase 1 has no backend) | Fast | High |

---

## Decision

Use **SQLDelight bucketed histogram tables** with the following design:

**Bucket boundaries** (upper bounds in ms): `0, 16, 33, 50, 100, 500, 1000, 5000, 9999`

- Boundary 16ms = one frame at 60fps.
- Boundary 33ms = one frame at 30fps (minimum acceptable).
- Boundary 9999 = overflow ("worse than 5 seconds").

**Schema**: `perf_histogram_buckets(operation_name TEXT, bucket_ms INTEGER, count INTEGER, recorded_at INTEGER)` with a UNIQUE constraint on `(operation_name, bucket_ms)`.

**Write path**: `Channel<HistogramSample>(capacity = 512)` → single consumer coroutine on `Dispatchers.IO` → SQLDelight `INSERT ... ON CONFLICT DO UPDATE SET count = count + 1`.

**Retention**: 7-day rolling window enforced by `HistogramRetentionJob`.

**Co-location**: Histogram tables reside in the same per-graph SQLDelight database as `pages` and `blocks`. A separate database was considered but rejected (adds driver lifecycle complexity with no benefit in Phase 1).

---

## Rationale

1. **Bounded write cost**: With 9 buckets and N operation types, the table never grows beyond `9 * N` rows. A UPSERT increments one integer — constant time regardless of total sample count. In contrast, one-row-per-event would produce millions of rows in a day of heavy use.

2. **Persistence across restarts**: Unlike `PerformanceMonitor` (in-memory), histogram data survives app crashes. The last-N-days' worth of performance history is available in the bug report even if the user cannot reproduce the issue on demand.

3. **Async write path eliminates main-thread risk**: The `Channel` decouples the call site (any dispatcher) from the DB write (always `Dispatchers.IO`). The channel drop-oldest policy ensures a full channel never blocks the caller — a lost sample is preferable to a dropped frame.

4. **Percentile estimation from buckets**: P50/P95/P99 can be estimated from bucketed counts using interpolation. The accuracy is sufficient for identifying whether operations are in the 0-16ms, 16-50ms, or >50ms range — which is all the precision needed for actionable decisions.

5. **SQLDelight type safety**: Queries are generated at compile time. The `incrementHistogramBucket` named query is a `suspend fun` in the generated code, ensuring callers cannot call it synchronously.

---

## Consequences

**Positive**:
- Histogram data available in bug reports without any app-side aggregation service.
- Bounded storage growth: histogram table stays small regardless of app usage duration.
- P95 regression detection possible in CI using in-memory SQLDelight driver (PERF-4.3).

**Negative**:
- Bucket boundaries are fixed at schema definition time. Changing them requires a schema migration and resets historical data. Boundaries were chosen conservatively (16ms/33ms = vsync thresholds) to be stable.
- Percentile estimation from buckets is approximate. For operations that cluster in a narrow range within one bucket (e.g., all navigation takes 45-49ms, bucket is 0-50ms), P50 and P95 are indistinguishable. This is accepted — fine-grained OTel spans (Phase 2) provide exact timings for individual operations.
- The `debug_flags` table co-located in the same `.sq` file serves a different purpose (debug menu toggles). This is acceptable for Phase 1 simplicity; a future refactor may split it into a separate `DebugDatabase.sq`.

---

## Migration Path for Existing Databases

The `DriverFactory.android.kt` and `DriverFactory.jvm.kt` already use a pattern of wrapping `ALTER TABLE` calls in `try { } catch (_: Exception) { }` to handle incremental schema migration for existing databases. The new histogram tables will follow the same pattern:

```kotlin
try {
    driver.execute(null, """
        CREATE TABLE IF NOT EXISTS perf_histogram_buckets (...)
    """, 0)
} catch (_: Exception) { }

try {
    driver.execute(null, """
        CREATE TABLE IF NOT EXISTS debug_flags (...)
    """, 0)
} catch (_: Exception) { }
```

This ensures existing user databases are upgraded on first launch without data loss.
