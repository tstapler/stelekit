# ADR-002: libsql local embedded mode for MVCC

**Status**: Accepted
**Date**: 2026-06-17

## Context

SteleKit's write path serializes all writes through a single `DatabaseWriteActor` backed by SQLite WAL.
WAL permits concurrent readers but only one writer; on an 8 000+ page graph, bulk import writes and
high-priority user edits queue behind each other even when they touch disjoint row ranges, causing
perceptible P99 latency spikes.

libsql is an open-source SQLite fork by Turso that adds MVCC via `BEGIN CONCURRENT`. Two deployment
modes exist:

- **sqld server mode**: A separate process (`sqld`) exposes an HTTP/WebSocket API. `BEGIN CONCURRENT`
  semantics are fully available. Requires a running server; adds network overhead and an infrastructure
  dependency that conflicts with SteleKit's offline-first design.
- **Local embedded mode**: The libsql C library is linked directly into the process. The pitfalls research
  confirmed that `BEGIN CONCURRENT` is available in local embedded mode with `PRAGMA journal_mode = 'mvcc'`
  (or `PRAGMA journal_mode = WAL` followed by the MVCC pragma on newer libsql builds). Conflict detection
  is **row-level** in the local embedded MVCC implementation, which is finer-grained than the page-level
  conflict detection in the upstream SQLite `BEGIN CONCURRENT` patchset.

A key behavioral difference confirmed by research: `SQLITE_BUSY_SNAPSHOT` (error code 517) fires at
**COMMIT time**, not at `BEGIN CONCURRENT`. A transaction that proceeds through many reads and writes
can be rejected only when it attempts to commit, requiring full `ROLLBACK` and replay — a simple
`sqlite3_step` retry loop is insufficient.

## Decision

Use **libsql local embedded mode**: open the database file with `Database::open(path)` (no server URL),
set the MVCC journal mode via PRAGMA, and issue `BEGIN CONCURRENT` for top-level write transactions in
`JvmLibsqlDriver` and `AndroidLibsqlDriver`.

Do not depend on `sqld` or any Turso cloud infrastructure in this iteration.

## Rationale

- **Offline-first**: SteleKit stores the user's graph locally; adding a server process would break the
  single-binary deployment model and require network availability for writes.
- **Row-level MVCC is strictly better for SteleKit's data model**: Each page's blocks occupy disjoint row
  ranges. Row-level conflict detection means concurrent page-level writers will never conflict with each
  other in practice, whereas page-level detection would produce spurious conflicts under high write
  concurrency.
- **No replication overhead**: Local mode does not replicate frames to a remote endpoint, avoiding the
  latency floor that cloud sync introduces.
- **Simpler operational model**: No sqld process to start, monitor, or version-pin. The Rust `cdylib`
  contains everything needed.
- **Proven feasibility**: The features research explicitly confirmed `PRAGMA journal_mode = 'mvcc'` in the
  local embedded libsql binary; this is not an undocumented or unsupported configuration.

## Consequences

- The compiled `cdylib` embeds the full libsql library, adding several MB to the APK/JAR. This is
  acceptable given the latency improvement goal; APK size can be mitigated by ABI splits in the Android
  build.
- `SQLITE_BUSY_SNAPSHOT` at commit time means `DatabaseWriteActor` must issue a full `ROLLBACK` before
  retrying the entire transaction. Retry logic is deferred to a post-driver milestone (non-goal for this
  iteration), but the driver must surface error code 517 as a distinct, retriable condition so the actor
  can act on it.
- WAL/MVCC checkpoint behavior must be monitored; the `PRAGMA optimize` call site in `GraphManager` must
  be verified to work correctly with the libsql MVCC journal mode.
- iOS is not addressed: there is no public Kotlin/Native + libsql embedded path. iOS remains a non-goal
  for this iteration.
- The existing `PooledJdbcSqliteDriver` remains the default; `JvmLibsqlDriver` is opt-in via
  `DriverFactory.createLibsqlDriver(path)`. No breaking change to existing paths.
