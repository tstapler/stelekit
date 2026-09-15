# Requirements: hikari-pool-lessons

**Date**: 2026-07-01
**Type**: feature addition / audit-and-harden
**Complexity**: 2 — focused feature (audit an existing component, produce a prioritized improvement backlog with concrete tasks)

## Problem Statement

SteleKit maintains a hand-rolled JDBC connection pool (`PooledJdbcSqliteDriver`) for SQLite on JVM/Android. The pool was written to solve one specific problem (avoid connection-per-thread burst on Dispatchers.IO) but was never audited against HikariCP — the reference implementation for Java connection pooling. A recent CI failure (pages_section_id migration deadlock) revealed that the pool silently exposes caller mistakes (raw BEGIN on a different connection per call) instead of preventing them. This raises the question: what other safety nets, observability hooks, or correctness guarantees does HikariCP provide that our pool is missing?

## Baseline

Current `PooledJdbcSqliteDriver` capabilities (as of 2026-07-01, before this work):
- Fixed-size `ArrayBlockingQueue` pool, pre-created connections at construction
- File DBs: non-blocking `poll()` with overflow connection creation
- In-memory DBs: blocking `poll(50ms)` loop (no overflow — schema isolation)
- Listener registry for SQLDelight reactive invalidation
- `drainPoolWaitStats()` / `PoolWaitSnapshot` for p99 wait-time telemetry
- `withPinnedConnection` (just added): hold one connection for a block
- No connection validation (stale/broken connections returned silently)
- No leak detection (connections checked out but never returned are invisible)
- No connection health check / eviction of dead connections
- No maximum lifetime / idle timeout (connections live forever)
- No thread-safety audit beyond what `ArrayBlockingQueue` provides
- No borrowing diagnostics (who holds a connection, for how long)

## Users / Consumers

- `DriverFactory.jvm.kt` — creates a `PooledJdbcSqliteDriver` per graph database on startup
- `MigrationRunner.applyAll()` — executes DDL via `driver.execute()` (each call may get a different connection)
- `DatabaseWriteActor` — serialized writes via SQLDelight's `newTransaction()` (ThreadLocal pins one connection)
- `SqlDelight*Repository` classes — reads via `mapToList(PlatformDispatcher.DB)` (concurrent, multi-connection)
- Android `DriverFactory.android.kt` — uses `AndroidSqliteDriver` (not this pool), but we should check for lessons applicable to Android too

## Success Metrics

1. A prioritized backlog of HikariCP patterns evaluated for applicability to our SQLite pool — each item classified as: **Adopt now** / **Adopt later** / **Not applicable (reason)**.
2. For every "Adopt now" item: a concrete implementation story in `plan.md` with acceptance criteria.
3. Zero false positives — patterns that genuinely don't apply to SQLite single-writer WAL semantics are explicitly excluded with a rationale so they don't resurface in future reviews.

## Appetite

Small (1–2 days for research + plan; implementation picked up in subsequent sessions per story)

## Constraints

- Must remain KMP-compatible: the pool is JVM-only (`jvmMain`), but behavior contracts must be observable from `commonMain` tests
- No new mandatory dependencies unless the benefit clearly outweighs the cost (HikariCP itself is ~165 KB — evaluate if worth pulling in vs. porting patterns)
- SQLite WAL semantics constrain some patterns: single writer, multiple readers, no row-level locking — HikariCP patterns designed for multi-writer RDBMS may not apply
- Android uses `AndroidSqliteDriver` (not this pool) — patterns that apply to both should be noted

## Non-functional Requirements

- **Performance SLO**: pool operations (checkout/return) must remain O(1) — no pattern should make the fast path slower
- **Scalability**: pool fixed at 8 connections for file DBs; patterns must work within that constraint, not assume a dynamic pool
- **Security classification**: internal / no PII in pool layer
- **Data residency**: no special requirements

## Scope

### In Scope

- Audit `PooledJdbcSqliteDriver` against HikariCP's feature set (connection validation, leak detection, eviction, health check, metrics, borrowing diagnostics, dead-connection detection, keep-alive)
- Evaluate applicability of each HikariCP feature to SQLite WAL + our fixed-pool design
- For applicable features: define concrete implementation stories
- Identify any HikariCP correctness guarantees (thread safety invariants, `autoCommit` state reset) that our pool doesn't enforce

### Out of Scope

- Replacing `PooledJdbcSqliteDriver` with HikariCP itself (we'd have to configure it for SQLite WAL mode and handle FTS5/JSON1; evaluate in build-vs-buy but default to keeping custom code)
- Android `AndroidSqliteDriver` refactor (different driver, different constraints)
- iOS / WASM connection pooling (native drivers handle their own concurrency)
- Real-time connection metrics dashboard / Prometheus export

## Rabbit Holes

- **HikariCP keep-alive**: uses `SELECT 1` to validate connections — SQLite doesn't need this (connections don't go stale over a local file), but the pattern may be wrongly adopted and add unnecessary latency
- **Connection max-lifetime eviction**: HikariCP evicts connections after 30 min to avoid network-socket rot — SQLite file connections don't have this problem, but eviction could be misapplied to "prevent WAL checkpoint pressure" (not a real use case for a pool of 8)
- **Dynamic pool sizing (min-idle / max-pool-size)**: HikariCP's most complex feature. Our fixed-size design is intentional (predictable memory + connection count). Don't get pulled into making the pool dynamic.

## Alternatives Considered

- **Pull in HikariCP directly**: would give us all features immediately but requires SQLite-specific JDBC URL configuration and may complicate Android builds. Evaluate in research.
- **Use SQLDelight's built-in driver**: `JdbcSqliteDriver` already handles some pooling via ThreadLocal — but it creates one connection per OS thread (up to 64 on Dispatchers.IO), which is why we wrote this pool.
- **Stay as-is**: acceptable if research finds no material gaps. This is explicitly on the table.

## Feasibility Risks

- HikariCP patterns assume multi-writer RDBMS semantics — false positives likely; need careful SQLite-specific evaluation
- Connection validation (`isValid()`) may not work correctly with all SQLite JDBC versions — needs verification against `sqlite-jdbc` 3.51.3.0 (our bundled version)
- Leak detection requires a background thread or weak-reference mechanism — adds complexity that may not be justified for a fixed 8-connection pool where leaks are obvious (pool drains completely)

## Open Questions

- Does HikariCP's `connectionInitSql` pattern (run SQL on every new connection checkout) give us a cleaner place to set per-connection PRAGMAs instead of wiring them in `Properties`?
- Is there a HikariCP pattern for detecting that `autoCommit` was left `false` after a connection is returned (stale transaction state)?
- Does `withPinnedConnection` need a timeout parameter to prevent indefinite hold on in-memory DBs?
