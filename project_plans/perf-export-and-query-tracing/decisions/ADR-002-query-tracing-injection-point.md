# ADR-002: Query Tracing Injection Point — Decorator vs AOP vs Manual Wrapping

**Status**: Accepted  
**Date**: 2026-04-21  
**Deciders**: Tyler Stapler  

---

## Context

`SteleDatabaseQueries` is a SQLDelight-generated interface. We need to intercept every query execution to record timing spans. Three approaches were considered.

---

## Options

### Option A: Decorator (hand-written `TracingQueryWrapper`)

A hand-written class that wraps `SteleDatabaseQueries` and delegates every method, adding timing around the delegate call. It is injected at the `RestrictedDatabaseQueries` construction site in `RepositoryFactoryImpl`.

The wrapper must cover:
- All `executeAsList()`, `executeAsOne()`, `executeAsOneOrNull()` paths (synchronous, called inside `withContext(PlatformDispatcher.DB)`)
- All write methods forwarded by `RestrictedDatabaseQueries` (which already delegates to `SteleDatabaseQueries`)

**Injection point**: `RepositoryFactoryImpl.createRepositorySet` creates a `SteleDatabase(driver)`. The decorator wraps `database.steleDatabaseQueries` before passing it to `RestrictedDatabaseQueries`:

```kotlin
val rawQueries = database.steleDatabaseQueries
val queries = if (debugFlagRepo?.getFlag("query_tracing") == true) {
    TracingQueryWrapper(rawQueries, ringBuffer)
} else {
    rawQueries
}
val restricted = RestrictedDatabaseQueries(queries)
```

Because `RestrictedDatabaseQueries` currently takes `SteleDatabaseQueries` directly in its constructor, this requires changing `RestrictedDatabaseQueries(private val queries: SteleDatabaseQueries)` to accept the concrete type — which it already does. The wrapper implements `SteleDatabaseQueries` (generated sealed interface).

Pros:
- No new dependencies
- Works on all KMP targets (pure Kotlin)
- Completely debuggable; timing logic is visible
- Satisfies NFR-3 (commonMain purity) and NFR-6
- Zero overhead when disabled — wrapper is never instantiated
- Fits existing `wrapWithOtelIfAvailable` pattern already used for `PageRepository` and `SearchRepository`

Cons:
- `SteleDatabaseQueries` has ~60+ methods. The decorator is verbose (mechanical delegation).
- Must be kept in sync when new queries are added to `SteleDatabase.sq`. This is the same discipline already required for `RestrictedDatabaseQueries`.

### Option B: Kotlin Symbol Processing (KSP) / Code Generation

Generate the decorator at compile time from the `SteleDatabaseQueries` interface using a KSP plugin.

Pros: Zero boilerplate maintenance.

Cons:
- KSP for KMP targets (iOS, WASM) is not production-stable for all platforms as of April 2026.
- Adds a new build plugin dependency.
- Significant implementation effort for a one-time boilerplate cost.
- Out of proportion to the problem size (~60 methods, all following the same pattern).

### Option C: Aspect-Oriented Programming (AspectJ / Byte-Buddy)

Intercept SQLDelight query calls at the JVM bytecode level.

Pros: No boilerplate.

Cons:
- Only works on JVM; Android uses a different bytecode pipeline. Fails NFR-6 (commonMain purity).
- AspectJ weaving at build time is not supported in KMP Gradle configurations.
- Entirely non-viable for this codebase's multi-target requirement.

### Option D: Manual instrumentation at call sites

Add `val start = epochMs(); ...; record(start)` at every repository call site in `SqlDelightBlockRepository`, `SqlDelightPageRepository`, etc.

Pros: Maximum control; no wrapper needed.

Cons:
- Approximately 40+ call sites across 4 repository files.
- Coupling performance concerns into business logic violates Single Responsibility.
- Missing call sites silently produce gaps in traces.

---

## Decision

**Option A: Decorator (`TracingQueryWrapper`).**

The decorator is implemented in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/TracingQueryWrapper.kt`. It implements `SteleDatabaseQueries` (the SQLDelight-generated interface) and wraps every method in timing logic that records a `SerializedSpan` to the `RingBufferSpanExporter`.

**Injection point**: `RepositoryFactoryImpl.createRepositorySet` reads the `query_tracing` flag from `DebugFlagRepository` immediately after creating `database`. If the flag is on, it constructs `TracingQueryWrapper(database.steleDatabaseQueries, ringBuffer)` and passes that to `RestrictedDatabaseQueries` instead of the raw queries.

**Span recording target**: `RingBufferSpanExporter` (in-memory), not `SqlDelightSpanRepository`. Writing spans synchronously to SQLite while inside a traced SQLite call would be self-referential and could produce infinite span chains. The background drain loop (5s interval in `createRepositorySet`) persists ring buffer spans to SQLite asynchronously.

**Trace context propagation**: For Phase 1, parent span ID is obtained from a `CoroutineContext` element `ActiveSpanContext` (a simple `CoroutineContext.Element` holding `traceId: String` and `spanId: String`). When no element is present, both are empty strings. This matches the existing pattern — `GraphLoader.parseAndSavePage` sets a trace context when it starts a span.

---

## Consequences

- `RestrictedDatabaseQueries` does not need to change its constructor signature. The `TracingQueryWrapper` replaces the raw `SteleDatabaseQueries` reference before `RestrictedDatabaseQueries` is constructed, so `RestrictedDatabaseQueries` is unaware of tracing.
- When new queries are added to `SteleDatabase.sq`, both `RestrictedDatabaseQueries` (for write stubs) and `TracingQueryWrapper` (for timing) must be updated. This is documented in the existing `@DirectSqlWrite` enforcement comment in `RestrictedDatabaseQueries.kt`.
- `TracingQueryWrapper` generates one `SerializedSpan` per synchronous query call. Flow-based queries (`asFlow()`) are not wrapped in Phase 1 — they appear as duration-less spans if ever added. This is a known limitation.
- The `ActiveSpanContext` coroutine element is defined in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/ActiveSpanContext.kt`.
