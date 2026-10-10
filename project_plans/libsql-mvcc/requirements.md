# Requirements: libsql-mvcc

## Problem Statement

SteleKit's database write path serializes all writes through a single `DatabaseWriteActor` coroutine
backed by SQLite WAL.  WAL allows concurrent reads but only one writer at a time, so high-priority
user edits and low-priority bulk import writes queue behind each other even when they touch completely
disjoint rows.  On large graphs (8 000+ pages) this causes perceptible write-actor latency spikes.

libsql — an open-source SQLite fork by Turso — adds `BEGIN CONCURRENT`, an MVCC extension that
allows multiple write transactions to proceed optimistically in parallel.  Transactions only conflict
if they touch the same underlying B-tree pages; on conflict libsql returns `SQLITE_BUSY_SNAPSHOT`.
For SteleKit's data model (each page's blocks occupy disjoint row ranges) actual conflicts should be
near zero.

## Goals

1. Ship a working `JvmLibsqlDriver` and `AndroidLibsqlDriver` that implement SQLDelight's `SqlDriver`
   interface backed by the libsql embedded database via a Rust JNI bridge.
2. Use Bazel (`rules_rust`) to build the native `cdylib` for host and cross-compilation targets.
3. Wire `JvmLibsqlDriver` into `DriverFactory.jvm.kt` so the application can use it end-to-end.
4. Demonstrate a measurable reduction in write-actor tail latency under concurrent synthetic load
   compared to `PooledJdbcSqliteDriver`.

## Non-Goals (this iteration)

- iOS driver (no public Kotlin/Native + libsql path yet).
- Cloud sync / Turso replication (local embedded only).
- `DatabaseWriteActor` MVCC retry logic (post-driver milestone).
- WASM/JS driver.
- Full cross-compilation CI (host-platform build is sufficient to prove the driver).

## Scope

### In scope

| Area | Deliverable |
|---|---|
| Rust JNI crate | `native/libsql/src/lib.rs` — complete JNI bridge with handle model, Tokio runtime, BEGIN CONCURRENT support |
| Bazel build | `native/libsql/BUILD.bazel`, `MODULE.bazel` — `rules_rust` setup; cross-compilation toolchains registered |
| Cargo.lock | Generated lockfile so `crates_repository` is deterministic |
| JVM driver | `jvmMain/db/libsql/JvmLibsqlDriver.kt` + `LibsqlJni.kt` — 8-connection pool, BEGIN CONCURRENT transactions, listener registry |
| Android driver | `androidMain/db/libsql/AndroidLibsqlDriver.kt` + `LibsqlJni.kt` — 4-connection pool, same JNI functions |
| DriverFactory integration | `DriverFactory.jvm.kt` gains `createLibsqlDriver(path)` that creates a `JvmLibsqlDriver` |
| Tests | `businessTest` or `jvmTest` — at minimum: open/write/read round-trip, concurrent writes without BUSY, transaction commit/rollback, nested savepoints |
| Benchmark | Extend existing benchmark or add a synthetic concurrent-write bench that compares throughput/tail latency between `PooledJdbcSqliteDriver` and `JvmLibsqlDriver` |

### Constraints

- The native library must be loadable at runtime on Linux x86-64 (CI target).
- No breaking changes to existing `DriverFactory` or `DatabaseWriteActor` interfaces.
- Must not regress existing `jvmTest` / `ciCheck` results.
- `SqlDriver` contract: pass the same queries that `PooledJdbcSqliteDriver` serves (schema creation, migrations, all repository reads and writes).

## Success Criteria

1. `bazel build //native/libsql:stelekit_libsql` succeeds on the host platform.
2. `JvmLibsqlDriver` passes the round-trip and transaction test suite.
3. A synthetic concurrent-write benchmark shows ≥ 10% reduction in P99 write latency or
   ≥ 10% improvement in write throughput versus `PooledJdbcSqliteDriver` under ≥ 4 concurrent
   writers on the same graph.
4. `./gradlew ciCheck` passes with the libsql driver wired into `DriverFactory`.

## Key Technical Decisions Already Made

- **JNI over JNA / Panama**: JNI works for both JVM and Android from the same native binary; no
  extra dependencies.
- **Handle model**: `Box<T>` cast to `jlong` — no global HashMap, no Send complexity.
- **Lazy prepare**: SQL stored in `StmtHandle`, prepared against the connection at execute/query
  time to avoid Rust lifetime conflicts between `Connection` and `Statement`.
- **Eager cursor collection**: All rows fetched into `Vec<Vec<Value>>` before returning the cursor
  handle so the async `Rows` iterator lifetime does not escape the JNI call boundary.
- **Global Tokio runtime**: 2 worker threads, shared across all JNI calls; `block_on` used in each
  JNI function since Java threads are never Tokio tasks.
- **Bazel version**: 9.1.1 (Bzlmod mandatory; core rules must be loaded explicitly per
  bazel-version-upgrades skill guidance).
