# ADR-015: Android WAL Connection Pool via FrameworkSQLiteOpenHelperFactory

**Status**: Proposed (requires audit before implementation)
**Date**: 2026-06-19
**Feature**: Block Operations Performance — Phase 2 (R6)

## Context

SteleKit's Android driver is built on `RequerySQLiteOpenHelperFactory` from `com.github.requery:sqlite-android:3.49.0`. This factory wraps Android's `SupportSQLiteOpenHelper` with Requery's bundled SQLite binary (which includes up-to-date SQLite without the Android vendor patches that affect older API levels).

`PRAGMA journal_mode=WAL` is set in `ANDROID_PRAGMAS` at database open time, enabling WAL (Write-Ahead Logging) journal mode. WAL's defining property is MVCC snapshot isolation: readers and writers do not block each other when they use separate database connections. However, activating WAL journal mode alone is not sufficient to gain concurrent read access on Android — the application must also enable Android's native multi-connection pool.

### The Gap: WAL Mode Without the Connection Pool

Android's `SQLiteOpenHelper.enableWriteAheadLogging()` is the API that enables the native multi-connection pool (multiple read connections + 1 write connection). `RequerySQLiteOpenHelperFactory` does not implement this API. Its `SupportSQLiteOpenHelper` wrapper does not expose `enableWriteAheadLogging()` at all — this is not a Requery bug but a structural limitation of the `SupportSQLiteOpenHelper` abstraction.

The consequence: even with `PRAGMA journal_mode=WAL` active, all reads and writes share a single underlying connection. A long write transaction in `DatabaseWriteActor` blocks all concurrent reads issued on the same connection. During Phase 3 background indexing (bulk INSERT of 50-row chunks, 80 chunks for a large page), every keystroke read that arrives during a chunk commit must wait behind the write — producing the 5-second `blocks:select` mean observed in v0.51.0.

Phase 1 and Phase 1.5 fixes reduced write duration per operation, and ADR-012 (reactive invalidation) decoupled the read fanout from write volume. After those changes, the residual bottleneck is reads queuing behind the writer on the single shared connection. R6 addresses this structural gap.

### Why a Two-Connection SqlDriver Wrapper Is Not Viable

Research evaluated wrapping two `SqlDriver` instances in a routing façade that directs SELECT queries to a read driver and DML to a write driver:

```kotlin
class ReadWriteSplitDriver(readDriver: SqlDriver, writeDriver: SqlDriver) : SqlDriver {
    override fun execute(...) = writeDriver.execute(...)     // INSERT/UPDATE/DELETE
    override fun <R> executeQuery(...) = readDriver.executeQuery(...)  // SELECT
    ...
}
```

This approach is blocked by a fundamental constraint in SQLDelight 2.x: **prepared statement identifier caching is per-driver**. The `identifier: Int?` parameter in `SqlDriver.execute()` and `executeQuery()` is a cache key for a compiled prepared statement inside a specific driver instance. Routing the same `identifier` to a different driver instance will either execute the wrong prepared statement or throw `IllegalStateException`. Since the identifier namespace is shared across all queries that flow through `SteleDatabase`, there is no safe way to split identifiers between two independent driver instances without forking SQLDelight's statement caching layer.

Additional complications: SQLDelight's transaction boundary logic (`newTransaction()`, `currentTransaction()`) assumes a single driver. Reads that occur inside a write transaction (e.g., SELECT in an actor lambda that also does INSERT) would go to the wrong driver under string-prefix routing, breaking transactional semantics.

### The Correct Path: FrameworkSQLiteOpenHelperFactory

The AndroidX default factory — `FrameworkSQLiteOpenHelperFactory` — is the standard `SupportSQLiteOpenHelper.Factory` that delegates to the Android framework's `SQLiteOpenHelper`. It supports `SQLiteOpenHelper.enableWriteAheadLogging()`, which activates Android's native connection pool. With that pool active, reads use independent read connections while writes use the dedicated write connection — exactly the concurrency model WAL is designed for.

Switching requires replacing the Requery factory with the AndroidX framework factory in `DriverFactory.android.kt`:

```kotlin
// Before
val factory = RequerySQLiteOpenHelperFactory()

// After
val factory = FrameworkSQLiteOpenHelperFactory()
// Then, on the resulting SQLiteOpenHelper, call:
// openHelper.setWriteAheadLoggingEnabled(true)  // or equivalent AndroidSqliteDriver API
```

The `enableWriteAheadLogging()` call may need to happen after driver construction depending on how `AndroidSqliteDriver` exposes it. Alternatively, `PRAGMA journal_mode=WAL` is already in `ANDROID_PRAGMAS` — if the framework factory respects WAL mode set via PRAGMA, the connection pool may activate automatically. This must be verified by inspection.

### Required Audit Before Implementation

Requery's factory provides behaviors that may not have direct equivalents in `FrameworkSQLiteOpenHelperFactory`. Before switching, each of the following must be evaluated:

1. **Bundled SQLite version**: Requery bundles SQLite 3.49.0 (current as of 2026). `FrameworkSQLiteOpenHelperFactory` uses the system SQLite. On Android API 21 (min SDK), system SQLite is ~3.7.x. If the codebase relies on SQLite 3.25+ features (`ROW_NUMBER()`, window functions), 3.35+ features (`UPDATE ... FROM`), or 3.45+ features (some JSON functions), switching to the system SQLite on low API levels will break those queries. **This is the highest-risk item.**

2. **PRAGMA delivery path**: `ANDROID_PRAGMAS` are delivered via `AndroidSqliteDriver`'s callback mechanism. Verify that `FrameworkSQLiteOpenHelperFactory` exposes the same callback for post-open PRAGMAs, or that PRAGMAs can be applied via `execSQL()` in an `onOpen` callback.

3. **`PRAGMA wal_autocheckpoint` and WAL file growth**: WAL checkpointing with a persistent read connection (SQLDelight `asFlow()` subscribers) can prevent WAL compaction indefinitely. With a native connection pool, the pool manager handles checkpoint scheduling. Verify that `enableWriteAheadLogging()` configures auto-checkpoint correctly and that the WAL file does not grow unbounded under the reactive flow usage pattern.

4. **`SQLITE_MAX_VARIABLE_NUMBER`**: Requery compiles its bundled SQLite with `SQLITE_MAX_VARIABLE_NUMBER = 999`, which is why all `IN ?` chunk sizes are capped at ≤500. The framework SQLite on modern Android has a higher limit (32 766 on API 30+). The existing ≤500 chunk cap is conservative and safe on the framework SQLite — no change needed, but verify behavior on API 21 (system SQLite 3.7.x limit is 999).

5. **Cursor behavior differences**: Requery's cursor implementation may differ from the framework cursor in edge cases (NULL handling, large BLOB reads, TEXT encoding). Run the full `jvmTest` and `androidUnitTest` suites, and the `QueryPlanAuditTest`, against the framework factory before committing the switch.

6. **`BEGIN IMMEDIATE` behavior**: The existing codebase uses `BEGIN IMMEDIATE` for all JVM write transactions (commit e3411409d0) to avoid `SQLITE_BUSY_SNAPSHOT`. Verify that `FrameworkSQLiteOpenHelperFactory` + `enableWriteAheadLogging()` preserves `BEGIN IMMEDIATE` semantics and does not introduce new lock contention patterns.

## Decision

Switch from `RequerySQLiteOpenHelperFactory` to `FrameworkSQLiteOpenHelperFactory` in `androidMain/kotlin/.../db/DriverFactory.android.kt` and call `enableWriteAheadLogging()` (or equivalent) to enable Android's native WAL connection pool.

This is conditioned on the audit above confirming:
- The target SQLite version meets all query feature requirements (window functions, `UPDATE ... FROM`, etc.) on the minimum supported API level, OR the Requery SQLite binary is retained separately for its version and the factory switch is accomplished another way.
- All existing tests pass with `FrameworkSQLiteOpenHelperFactory`.

If the system SQLite version audit fails (older Android APIs lack required SQLite features), the implementation is blocked and must either raise the min SDK or bundle a SQLite binary via an alternative mechanism (e.g., `androidx.sqlite:sqlite-bundled`, which ships a modern SQLite via relinker).

Implementation steps (after audit passes):

1. Replace `RequerySQLiteOpenHelperFactory()` with `FrameworkSQLiteOpenHelperFactory()` in `DriverFactory.android.kt`.
2. Add a call to activate the WAL connection pool — either via `AndroidSqliteDriver`'s `onConfiguration` callback (`config.enableWriteAheadLogging = true`) or via `PRAGMA journal_mode=WAL` in the `onOpen` callback (which already exists in `ANDROID_PRAGMAS`).
3. Verify PRAGMAs (`mmap_size`, `wal_autocheckpoint`, `synchronous`, `analysis_limit`) are still applied correctly in the `onOpen` callback.
4. Remove the Requery dependency from `kmp/build.gradle.kts` if no other component references it.
5. Run `./gradlew ciCheck` and the Android integration tests under both low and high API level emulators.

## Rationale

- **Root cause fix**: the connection pool is the missing piece. WAL mode without a connection pool is WAL's journal format with none of its concurrency benefits. `enableWriteAheadLogging()` is the documented Android API for unlocking those benefits.
- **No SQLDelight change required**: the factory swap happens below the `SqlDriver` abstraction. `SteleDatabase`, `SteleDatabaseQueries`, `DatabaseWriteActor`, and all repository code are unaffected.
- **No two-driver complexity**: the native connection pool is managed by Android's `SQLiteDatabase` internally. There is no application-level routing logic, no statement identifier collision risk, and no transaction boundary confusion.
- **Simpler than alternatives**: the wrapper `SqlDriver` approach was investigated and rejected (see Context). The factory swap is a surgical two-line change with a well-understood risk surface.
- **Deferred until after other Phase 2 fixes**: Phase 1.5 algorithm fixes, ADR-013 (fractional positions eliminating position-shift writes), and ADR-012 (reactive invalidation reducing fanout reads) should be committed first. Re-profile after those changes. If `blocks:select` latency under concurrent writes is still unacceptable, proceed with this ADR. Implement only if profiling confirms read-stall-on-write is still the bottleneck.

## Consequences

**Positive**

- True N-read / 1-write concurrency on Android: `asFlow()` subscribers reading blocks from one page are not blocked by `DatabaseWriteActor` inserting blocks into another page.
- Background indexing writes no longer stall UI reads. `blocks:select` latency during Phase 3 indexing should fall to WAL reader latency (~sub-millisecond for the MVCC snapshot acquisition) rather than write transaction duration.
- `FrameworkSQLiteOpenHelperFactory` is the AndroidX-maintained default; it tracks upstream Android SQLite support without a third-party dependency.

**Negative / Trade-offs**

- **System SQLite version risk** (high): if the min SDK is low enough that the system SQLite lacks required features (window functions for `shiftRootBlockPositionsFrom` / data migrations, `UPDATE ... FROM` for position migration), the switch is not viable without raising the min SDK or bundling a modern SQLite binary via `androidx.sqlite:sqlite-bundled`.
- Removing Requery eliminates its bundled SQLite 3.49.0 binary; applications running on devices with old system SQLite may see different behavior for corner cases in JSON, FTS5, or PRAGMA handling.
- PRAGMA delivery mechanism may differ — requires verification that all `ANDROID_PRAGMAS` are applied correctly via the new factory's `onOpen` callback.
- WAL file growth from persistent readers must be monitored. The native connection pool's checkpoint behavior is different from Requery's single-connection behavior; WAL file size should be validated under sustained write load in integration tests.
- The audit is a prerequisite — this ADR cannot be implemented speculatively without the audit results. Status is `Proposed` until the audit completes and confirms viability.

## Related

- Requirements: `project_plans/block-ops-perf/requirements.md` § R6
- Research: `project_plans/block-ops-perf/research/stack.md` § R6, `research/architecture.md` § 3, `research/pitfalls.md` § 4
- Depends on: ADR-012 (reactive invalidation) and ADR-013 (fractional positions) should land first to establish the new performance baseline before evaluating whether R6 is still needed
- Prerequisite: min SDK audit and system SQLite version matrix verification
