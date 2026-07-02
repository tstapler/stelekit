# Adversarial Review: hikari-pool-lessons plan

**Date**: 2026-07-01
**Verdict**: CONCERNS (1 blocker, 4 concerns, 4 NILs)

---

## Issues Found

### `setAutoCommit(true)` in `closeConnection()` is not guarded — BLOCKER

The Story 1 implementation sketch wraps `rollback()` in `runCatching` but leaves
`connection.autoCommit = true` unprotected:

```kotlin
runCatching { connection.rollback() }
    .onFailure { log.warning("rollback failed on dirty connection: ${it.message}") }
connection.autoCommit = true   // ← can throw; not in runCatching
// existing: pool.offer(connection) or connection.close()
```

If `connection.autoCommit = true` throws (sqlite-jdbc throws `SQLiteException` when the
underlying driver handle is broken — same condition that caused `rollback()` to fail),
the exception propagates out of `closeConnection()`, skipping both `pool.offer()` and
`connection.close()`. Result: permanent pool size decrease by 1 and a leaked JDBC file
descriptor, with no log entry for the loss. For a pool of 8 connections, 8 such events
mean total pool starvation — every subsequent `getConnection()` on a file-DB creates an
overflow connection for the rest of the process lifetime. The plan has no test covering
this path, so the bug would survive CI.

**Required fix**: wrap `connection.autoCommit = true` in its own try/catch. On any
exception: log a warning, call `runCatching { connection.close() }`, and return without
calling `pool.offer()`. Do NOT offer a connection whose state is unknown to the pool.
Additionally, since the plan explicitly defers "pool replacement when a connection is
discarded" to later work, the plan should state this explicitly (pool size silently
shrinks by 1 on broken-connection close) so the omission is deliberate rather than
overlooked.

---

### TOCTOU window in Story 4 not eliminated — only reduced — CONCERN

The plan says the guard is "positioned before `pool.poll()` to avoid a TOCTOU window
between the poll and the overflow creation." This framing is incorrect — the guard
reduces the window, not eliminates it:

```kotlin
if (closed.get()) error("PooledJdbcSqliteDriver is closed")  // Thread sees false
//                         ← close() sets closed=true HERE, drains pool
return pool.poll() ?: DriverManager.getConnection(url, properties)
//     poll() returns null → overflow created post-close → orphaned connection
```

The race requires `close()` to complete between the check and the poll — a narrow but
non-zero window. The plan's test `getConnection after close on file driver throws` tests
the sequential case only (close then getConnection). The concurrent case is untested.
The plan should acknowledge the residual window (acceptable in practice, but not
eliminated) and note that the test covers only the sequential case.

---

### Broken-connection discard permanently shrinks pool with no replacement — CONCERN

When Story 1's `closeConnection()` discards a broken connection (because `rollback()` or
`setAutoCommit()` failed), the plan does not recreate a replacement. The pool silently
shrinks from N to N−1. HikariCP closes and recreates in the same path. For a pool of 8,
losing connections to transient I/O errors (SQLITE_IOERR, SQLITE_FULL) means capacity
degrades over a long-running session with no diagnostic. The plan's "Adopt Later" section
defers pool replacement without explaining this consequence. The plan should document
this trade-off explicitly or add a `DriverManager.getConnection(url, properties)` +
`pool.offer(replacement)` path in `closeConnection()` for the broken-connection case.

---

### Leak detection fires false positives for legitimate long operations — CONCERN

With `leakThresholdMs = 5_000` (the plan's recommended production value), any
`withPinnedConnection` block that runs a migration or schema creation longer than 5
seconds emits a WARNING. After the block completes, `cancel(false)` is called, but if
the task has already fired, the warning has already been emitted — `cancel(false)` does
NOT suppress a task that already ran. The pool continues to function correctly (the
future is cancelled, no second warning fires), but operators will see spurious warnings
on first-run migrations and may start ignoring the signal.

The plan has no test that verifies "pool is healthy and future is cancelled after a
false-positive fires." The plan's rollout note should document the false-positive risk
and suggest `leakThresholdMs` values relative to expected migration durations, or note
that leak detection should be disabled during `MigrationRunner.applyAll()`.

---

### Overflow connection creation during normal operation is unobservable — CONCERN

Pitfalls research section 6 notes that each overflow connection consumes 32 MB page
cache (56 overflow × 32 MB = 1.8 GB under 64-thread IO burst). The plan defers
`overflowCount` metrics tracking to "Adopt Later" without acknowledging the specific
observability gap: there is no log line when an overflow connection is created during
normal operation. The research explicitly recommended WARN-level logging here. The plan
should either include a one-line `log.warning("Pool exhausted — creating overflow
connection")` in the file-DB `getConnection()` path, or document in "Explicitly
Excluded" that this is intentionally deferred with the specific rationale (no current
production cases, metrics endpoint not ready).

---

### autoCommit rollback regression — NIL

The concern is whether the new `closeConnection()` guard could roll back a live
transaction. It cannot. JdbcDriver 2.3.2 calls `closeConnection()` only after
`endTransaction()` or `rollbackTransaction()`, both of which restore `autoCommit=true`
before the call. When `closeConnection()` is reached, the transaction is already
committed or rolled back, and `connection.autoCommit` is already true. The guard is a
dead letter for normal SQLDelight paths. The only way the guard fires is for connections
returned dirty via error paths that bypass `rollbackTransaction()` — exactly the
intended case. No regression.

---

### ConcurrentHashMap key identity for leak tracking — NIL

The concern is whether sqlite-jdbc re-wraps `Connection` objects between `getConnection()`
and `closeConnection()`, breaking `ConcurrentHashMap` identity lookups. It does not. The
pool stores raw JDBC `Connection` objects from `DriverManager.getConnection()`. JdbcDriver
passes the same object reference from `getConnection()` through to `closeConnection()`.
The `leakTasks` map entries are inserted and removed using the same object reference.
Identity semantics are stable.

---

### ScheduledThreadPoolExecutor daemon thread — NIL

The implementation uses a thread factory that sets `it.isDaemon = true`. The `by lazy`
initialization ensures the executor is only created when `leakThresholdMs > 0`. If a
test using leak detection doesn't call `driver.close()`, the daemon thread is still
running at JVM shutdown but will not prevent it. Logger output during JVM teardown is
harmless. The plan's claim "daemon thread — does not prevent JVM shutdown" is correct.

---

### In-memory path `closed.get()` asymmetry — NIL

Story 4 adds `closed.get()` before `pool.poll()` for file DBs only. The in-memory path
already checks `closed.get()` at the top of each spin-loop iteration. Both paths
correctly check the flag; the placement differs because file-DB needs to prevent overflow
creation while in-memory needs to periodically re-check during blocking. The asymmetry
is intentional and correct.

---

## Required Plan Changes

1. **Story 1 implementation sketch**: Add explicit error handling for
   `connection.autoCommit = true`. The pattern must be:
   - `rollback()` in try/catch (currently done via `runCatching`)
   - `autoCommit = true` in its own try/catch; on failure, close the connection and
     return WITHOUT calling `pool.offer()`
   - Document (in plan or inline KDoc) that broken-connection discards permanently
     shrink the pool until the driver is closed and recreated.

2. **Story 4 framing**: Replace "positioned before `pool.poll()` to avoid a TOCTOU
   window" with "reduces the TOCTOU window to near-zero." Note that the test covers only
   the sequential case, not the concurrent race.

---

## Test Gaps

**TC-BROKEN-CONN**: `closeConnection with setAutoCommit throwing — connection is closed
not leaked`. Use a mock or spy that throws on `setAutoCommit(true)`. Verify: (a) the
connection is closed via `connection.close()`, (b) `pool.offer()` is NOT called, (c)
the pool's next `getConnection()` creates a fresh overflow (pool is smaller). Currently
untested — would fail if the implementation follows the sketch literally.

**TC-TOCTOU-RACE**: `getConnection and close racing concurrently on file driver — no
orphaned connection`. Run 100 iterations of: start thread A calling `getConnection()`
while thread B calls `close()` simultaneously. Verify either (a) `getConnection()` throws
or (b) the returned connection is eventually closed. Currently the plan tests only the
sequential case.

**TC-FALSE-POSITIVE-POOL-HEALTH**: `pool still healthy after leak warning fires and
connection eventually returned`. Create driver with `leakThresholdMs=50ms`; hold a
connection for 100ms (warning fires); return it; verify `cancel(false)` was called and
no second warning fires; verify the returned connection is reusable (pool size
unchanged). Currently not in the test plan.

**TC-OVERFLOW-LOGGING**: No test verifies that overflow creation during normal operation
is either logged or silently suppressed. If observability logging is added per the
concern above, this test should assert the log line fires exactly once per overflow
creation.
