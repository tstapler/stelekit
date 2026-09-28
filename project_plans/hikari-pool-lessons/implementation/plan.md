# Implementation Plan: HikariCP Pool Lessons for PooledJdbcSqliteDriver

**Date**: 2026-07-01
**Feature**: hikari-pool-lessons
**File**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/PooledJdbcSqliteDriver.kt`

---

## 1. Decision Summary

### Build vs Buy Verdict

**Reject HikariCP as a dependency. Port 2 patterns manually.**

HikariCP 7.1.0 is structurally incompatible with our use case on 3 independent axes:

1. **WAL PRAGMA delivery**: Passing `journal_mode=WAL` via `addDataSourceProperty` triggers error `"batch entry 0: query returns results"` (HikariCP issue #493, open since 2015). We set 10 PRAGMAs at connection creation; `connectionInitSql` accepts only one statement and has no workaround.
2. **In-memory database lifecycle**: HikariCP's HouseKeeper evicts and recreates connections after `maxLifetime` (default 30 min). A recreated `:memory:` connection returns a fresh schema-less database, causing "no such table" errors. Disabling this requires per-database HikariCP configuration with no compile-time enforcement.
3. **10 inapplicable features** that would require active suppression: `maxLifetime`, `keepaliveTime`, `idleTimeout`, `HouseKeeper` background thread, dynamic pool sizing, `Connection.isValid()` checkout latency, `networkTimeout`, `beginRequest`/`endRequest`, `HikariCredentialsProvider`, JMX MBeans.

The two HikariCP patterns that genuinely matter — autoCommit state reset and leak detection — are each under 100 lines and carry zero transitive dependencies. Porting them is strictly less work than fighting HikariCP's assumptions.

### Final "Adopt Now" List

| # | Pattern | Why it matters |
|---|---|---|
| 1 | `autoCommit` reset + rollback in `closeConnection()` | Correctness: stale transactions corrupt the next borrower |
| 2 | Leak detection (checkout stack trace + ScheduledFuture cancellation) | Observability: silent pool drain has no diagnostic |
| 3 | `computeIfAbsent` fix in `addListener` | Correctness: non-atomic `getOrPut` drops listeners under concurrent first-use |
| 4 | `closed.get()` guard before overflow creation in file-DB `getConnection()` | Correctness: post-close calls create orphaned connections that are never cleaned up |

---

## 2. Implementation Stories

---

### Story 1: autoCommit Reset on `closeConnection()`

**Why it matters**

When a coroutine is cancelled mid-transaction, or when code bypasses SQLDelight's `transaction { }` block, `JdbcDriver.endTransaction()` may not run. The connection goes back to the pool with `autoCommit=false`. The next borrower that calls `JdbcDriver.beginTransaction()` hits `IllegalStateException("Expected autoCommit to be true by default")` — the error is attributed to the innocent next caller, not the leaker. Until the pool is closed and recreated, that connection slot throws on every write attempt.

From `JdbcDriver.beginTransaction()` bytecode (verified 2.3.2): it checks `if (autoCommit == false)` and throws immediately. HikariCP's proxy makes this structurally impossible; our pool has no equivalent guard.

**Acceptance Criteria**

- A connection returned to the pool via `closeConnection()` always has `autoCommit=true` before it re-enters the queue.
- A connection returned mid-transaction has its open transaction rolled back before re-entry.
- A WARNING is logged with the connection identity when a dirty connection is detected.
- The next `getConnection()` after a dirty return succeeds and returns a clean connection (verified by `newTransaction()` not throwing).
- No regression: connections returned in clean state (autoCommit=true) pass through without any extra JDBC call.

**Implementation Sketch**

In `closeConnection()`, before `pool.offer(connection)`:

```
if (!connection.autoCommit) {
    log.warning("Connection returned with autoCommit=false — rolling back open transaction and resetting.")
    try {
        connection.rollback()
        connection.autoCommit = true
    } catch (e: Exception) {
        // Broken connection: both rollback and autoCommit reset may throw if the connection
        // is in a terminal state (e.g., post-driver-close). Discard rather than returning a
        // corrupt connection to the pool — pool shrinks by 1 slot, but this is the correct
        // outcome (no replacement logic planned; size recovers on driver restart).
        log.warning("closeConnection: broken connection discarded during autoCommit reset: ${e.message}")
        runCatching { connection.close() }
        return
    }
}
// existing: pool.offer(connection) or connection.close()
```

`setAutoCommit(true)` on a sqlite-jdbc connection that has an open transaction issues an implicit ROLLBACK. The explicit `rollback()` call before `setAutoCommit(true)` is belt-and-suspenders: it ensures the rollback is logged even if the `setAutoCommit` path silently swallows it. The `try/catch` is critical: if `setAutoCommit(true)` throws (broken connection), skipping `pool.offer()` is correct — a corrupt connection must not re-enter the pool even at the cost of a permanently smaller pool.

**Test Strategy**

Extend `PooledJdbcSqliteDriverTest`:

- `autoCommit reset on dirty return — next checkout is clean`: use a single-connection pool; call `getConnection()`, set `autoCommit=false`, call `closeConnection()`; verify next `getConnection()` returns a connection with `autoCommit=true`.
- `dirty return issues rollback — previous DML is not visible`: same setup; execute `INSERT` with `autoCommit=false`, return connection dirty; verify the INSERT is not visible after the next checkout (WAL MVCC snapshot confirms rollback fired).
- `clean return has no extra JDBC call`: instrument with a wrapper that counts `setAutoCommit` calls; verify zero calls when connection is returned with `autoCommit=true`.

**Estimated lines**: ~20 implementation, ~30 test.

---

### Story 2: Connection Leak Detection

**Why it matters**

With a fixed 8-connection pool, one leaked connection permanently removes 12.5% of pool capacity with no diagnostic signal. For file-DB pools the impact is subtle — an overflow connection is silently created. For in-memory pools the pool blocks indefinitely. Neither case surfaces _who_ leaked the connection or _when_. A scheduled task that fires after a configurable threshold converts a mystery hang into an actionable log line with the exact checkout stack trace.

This is the pattern from `ProxyLeakTask` in HikariCP's source, ported as a lightweight implementation without the proxy layer.

**Acceptance Criteria**

- When `leakThresholdMs > 0` and a connection is held past that threshold without `closeConnection()` being called, a WARNING is logged containing: the checkout thread name, the checkout stack trace (trimmed to application frames), and the connection identity.
- When the connection is returned (even after the threshold fires), the scheduled task is cancelled and no second log is emitted.
- When `leakThresholdMs = 0` (default), no `ScheduledFuture` is created and no background thread is started. Checkout performance is unaffected.
- The scheduler thread is a daemon thread — it does not prevent JVM shutdown.
- Leak detection is independent per connection: 8 simultaneous outstanding connections each get their own task.

**Implementation Sketch**

Add `leakThresholdMs: Long = 0` to the `PooledJdbcSqliteDriver` constructor (default 0 = disabled).

Add a lazily initialized `ScheduledThreadPoolExecutor` (1 daemon thread):

```
private val leakScheduler: ScheduledExecutorService by lazy {
    Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "PooledJdbcSqliteDriver-leak-detector").also { it.isDaemon = true }
    }
}
```

Add a `ConcurrentHashMap<Connection, ScheduledFuture<*>>` to track outstanding tasks:

```
private val leakTasks = ConcurrentHashMap<Connection, ScheduledFuture<*>>()
```

In `getConnection()`, after obtaining the connection (both file-DB and in-memory paths), when `leakThresholdMs > 0`:

```
val checkoutException = Exception("Connection checkout stack trace")
val threadName = Thread.currentThread().name
val connectionId = System.identityHashCode(conn)
val future = leakScheduler.schedule({
    log.warning(
        "Connection leak detected: connection #$connectionId was checked out on thread " +
        "'$threadName' and not returned within ${leakThresholdMs}ms.\n" +
        checkoutException.stackTraceToString()
    )
}, leakThresholdMs, TimeUnit.MILLISECONDS)
leakTasks[conn] = future
```

**Note**: Leak detection must cover BOTH the file-DB path (`pool.poll() ?: overflow`) and the in-memory polling loop. Both paths end with `return conn` — register the task after the `return conn` refactor (extract a shared `registerLeakTask(conn)` helper called from both branches).

In `closeConnection()`, cancel the task before the autoCommit check and pool offer:

```
leakTasks.remove(connection)?.cancel(false)
```

In `close()`, shut down the scheduler after draining the pool. Use `isInitialized()` rather than accessing the lazy property to avoid triggering initialization during `close()` when `getConnection()` was never called with leak detection enabled:

```
if (leakThresholdMs > 0 && ::leakScheduler.isInitialized()) leakScheduler.shutdownNow()
// shutdownNow() (not shutdown()) cancels pending tasks immediately — prevents pending
// leak-detection tasks from firing after close() and producing false WARNINGs during
// normal graph switching (where drivers are shut down and replaced).
```

**Test Strategy**

Extend `PooledJdbcSqliteDriverTest`:

- `leak detector fires after threshold when connection held too long`: create driver with `leakThresholdMs=100`; check out a connection; sleep 200 ms without returning it; verify the WARNING log was emitted (capture via a custom `java.util.logging.Handler`).
- `no leak log when connection returned before threshold`: check out a connection; return it within 50 ms of a 200 ms threshold; sleep past threshold; verify no WARNING was emitted.
- `ScheduledFuture is cancelled on normal closeConnection`: check out a connection with `leakThresholdMs=500`; return it immediately; sleep 600 ms; verify no WARNING was emitted.
- `leak detector disabled by default`: create default driver (no `leakThresholdMs`); hold a connection for 10s (simulated with mocking or fast-clock); verify no WARNING and no background thread is created.

**Estimated lines**: ~80-100 implementation, ~40 test.

---

### Story 3: `computeIfAbsent` Fix for `addListener` Race

**Why it matters**

Kotlin's `getOrPut` on `ConcurrentHashMap` is not atomic. It compiles to a `get` then a conditional `put`, with a gap between them. Two coroutines concurrently calling `addListener` for the same new key for the first time can each create a `CopyOnWriteArrayList`, with the second write overwriting the first. The first listener is added to a list that is no longer referenced by the map. `notifyListeners` only iterates the map's current lists, so the first listener is silently dropped.

Symptom: a SQLDelight `Flow` collector that started concurrently with another collector on the same query key occasionally misses invalidation signals, causing the UI to show stale data. Rare under normal use (first-use race), but possible during cold start when multiple screens launch simultaneously.

**Acceptance Criteria**

- `addListener` called concurrently for the same new key from N threads results in all N listeners being stored in the same list under that key.
- `notifyListeners` fires all N listeners.
- No change in behavior for the existing sequential case.

**Implementation Sketch**

In `addListener`, replace:

```
listeners.getOrPut(key) { CopyOnWriteArrayList() }.add(listener)
```

with:

```
listeners.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(listener)
```

`ConcurrentHashMap.computeIfAbsent` is atomic: the default-value function is called at most once per key, and only if the key is absent. Concurrent calls for the same key block until the first call completes and then reuse the inserted list.

**Test Strategy**

New test in `PooledJdbcSqliteDriverTest`:

- `concurrent addListener for same new key — all listeners fire`: launch 20 threads simultaneously, each calling `addListener("same_key", listener = ...)` with a distinct listener; call `notifyListeners("same_key")`; verify all 20 listeners received exactly one notification. Run the scenario at least 50 times in a loop to expose the race reliably.

**Estimated lines**: 1 implementation, ~20 test.

---

### Story 4: `closed` Guard Before Overflow Creation in File-DB `getConnection()`

**Why it matters**

`close()` sets `closed = true`, clears listeners, and drains the `ArrayBlockingQueue` to close all pooled connections. But the file-DB branch of `getConnection()` does not check `closed`. After `close()` drains the pool:

1. A concurrent call to `getConnection()` sees `pool.poll()` return null (pool is empty).
2. It falls through to `DriverManager.getConnection(url, properties)` and creates a new connection to the database being torn down.
3. When the caller later calls `closeConnection()`, `pool.offer()` succeeds (queue has space), returning the overflow connection to the pool.
4. `close()` has already completed — it never drains this slot. The connection is orphaned: held in the queue but never closed.

This is a one-connection leak per race instance, plus potential use of a connection to a database whose schema is being torn down by `GraphManager.shutdown()`.

**Acceptance Criteria**

- Calling `getConnection()` on a file-DB driver after `close()` throws `IllegalStateException("PooledJdbcSqliteDriver is closed")`.
- No new overflow connection is created after `close()` is called.
- The check is positioned before `pool.poll()` to reduce (not eliminate) the TOCTOU window between the poll and the overflow creation. A concurrent `close()` completing between `closed.get()` and `pool.poll()` can still result in an overflow connection, but this window is much narrower than no check at all.
- In-memory path already has this guard (in the polling loop); no change needed there.

**Implementation Sketch**

In `getConnection()`, file-DB branch, add before `pool.poll()`:

```
if (!isMemory) {
    if (closed.get()) error("PooledJdbcSqliteDriver is closed")
    return pool.poll() ?: DriverManager.getConnection(url, properties)
}
```

**Test Strategy**

New test in `PooledJdbcSqliteDriverTest`:

- `getConnection after close on file driver throws`: call `fileDriver.close()`; call `fileDriver.getConnection()`; verify it throws `IllegalStateException` with message containing "closed".
- `no overflow connection created after close`: verify no `DriverManager.getConnection` is invoked post-close (instrument by subclassing or wrapping; or verify indirectly by asserting pool size stays 0 and no connection object is returned).

**Estimated lines**: 2 implementation, ~10 test.

---

## 3. Implementation Order

Stories are ordered from least to most complexity. Each is independently mergeable — no story depends on another being merged first.

| Order | Story | Rationale |
|---|---|---|
| **1** | Story 3: `computeIfAbsent` | 1-line change; zero risk; fix it first |
| **2** | Story 4: `closed` guard | 2-line guard; trivially safe; no behavioral change for non-race callers |
| **3** | Story 1: `autoCommit` reset | ~20 lines; requires test for rollback behavior; small but has a behavioral effect on the return path |
| **4** | Story 2: Leak detection | ~100 lines; introduces new infrastructure (scheduler, map); implement last so earlier reviews are uncluttered |

All four can land in a single PR or as four stacked PRs (one per story). The stacked approach is preferred — each story has a self-contained diff and a clear acceptance test.

---

## 4. Patterns Explicitly Excluded

Document here so they are not re-proposed in future reviews.

| Pattern | Verdict | Rationale |
|---|---|---|
| `Connection.isValid()` on checkout | Not applicable | sqlite-jdbc's `isValid()` executes `SELECT 1` — real SQL, not a handle check. Adds latency on every checkout for zero benefit: local SQLite file connections never go stale (no network, same process, file always accessible). The only relevant case is post-`driver.close()`, where `conn.isClosed()` is the correct check (no SQL needed). Future work if shutdown-race protection is prioritized. |
| Max-lifetime eviction | Not applicable | Designed to prevent network-socket rot (TCP keepalive timers, DB server session timeouts). SQLite file connections have no network layer and do not rot. Applying this would cause periodic unnecessary reconnects with zero benefit, plus JDBC.connect latency on each eviction. |
| `keepaliveTime` / `SELECT 1` heartbeat | Not applicable | Same root cause as max-lifetime. SQLite connections do not idle-out. A periodic `SELECT 1` adds latency for zero safety benefit. |
| Dynamic pool sizing (`minIdle` / `maxPoolSize`) | Not applicable | Fixed-8 design is intentional: predictable memory footprint (8 × 32 MB page cache = 256 MB), bounded connection count, no surge behavior. See requirements Rabbit Holes. |
| `ConcurrentBag` replacing `ArrayBlockingQueue` | Not applicable | `ConcurrentBag`'s thread-local affinity benefit materializes at >10k checkouts/sec with many threads competing. Our workload: `DatabaseWriteActor` serializes writes (single-connection path); reads are concurrent but pool is pre-sized to match the concurrency budget. `ABQ.poll()` is adequate. Reassess only if `drainPoolWaitStats()` shows p99 > 5 ms. |
| Full HikariCP dependency | Rejected | WAL PRAGMA delivery incompatible (issue #493); in-memory database lifecycle incompatible (HouseKeeper eviction destroys schema); 10 inapplicable features require active suppression; adds `slf4j-api` transitive dependency; HouseKeeper background thread adds noise. The two valuable patterns are portable in ~120 lines. |
| Open-statement tracking + force-close on return | Adopt later | Leaked SQLite cursors can delay WAL checkpointing (hold a read mark, prevent WAL truncation). Worth adding, but lower priority than correctness fixes. Requires a `FastList<Statement>`-equivalent per connection slot. |
| Pool-shutdown `abort()` for in-use connections | Adopt later | `close()` currently only drains the pool queue. Connections currently checked out continue until their callers return them. `Connection.abort()` would forcibly close them. Low priority — callers get a `SQLException` on next use of a closed connection, which is acceptable. |
| `PoolWaitSnapshot` enhancements (p99, overflow count) | Adopt later | The UX research identified `maxWaitMs`, `overflowCount`, and checkout duration tracking as valuable additions. No dependency or correctness risk. Deferred until a telemetry or health endpoint is added. |

---

## 5. Files Changed

| File | Stories |
|---|---|
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/db/PooledJdbcSqliteDriver.kt` | All 4 stories — implementation changes |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/PooledJdbcSqliteDriverTest.kt` | All 4 stories — new test cases |

**KDoc update required (Story 1)**: `withPinnedConnection`'s current KDoc says "wrapping DDL in an explicit `BEGIN`/`COMMIT`" — this is now misleading. Story 1 makes `closeConnection()` roll back any open transaction, so a caller using `autoCommit=false` inside the block would have their work silently rolled back. The KDoc must be updated to: (a) remove the `BEGIN`/`COMMIT` example, and (b) add a note that callers must not leave `autoCommit=false` at the end of the block. Transactional DDL should go through SQLDelight's `newTransaction()` instead.

No other files are modified. No `commonMain` interface changes. No Bazel BUILD file changes (both files are already in the Bazel build graph).

---

## 6. Rollout Notes

**Scope**: All changes are in `jvmMain` only. Android uses `AndroidSqliteDriver` (not this pool) — no impact. iOS and WASM use native drivers — no impact.

**`leakThresholdMs` opt-in**: The parameter defaults to `0` (disabled). No existing callers are affected by adding it. To enable in production, `DriverFactory.jvm.kt` can pass `leakThresholdMs = 5_000L` (5 seconds is HikariCP's recommended minimum; 2 seconds is their hard floor). For tests, the default of 0 is correct — test teardown happens too quickly for leak detection to fire usefully.

**`autoCommit` reset behavioral note**: The change adds one `conn.getAutoCommit()` call on every `closeConnection()` path. This is a JNI call to check SQLite's internal autocommit flag — effectively free. Only when `autoCommit == false` (the dirty case) does it add a `rollback()` + `setAutoCommit()` call. This is deliberately not a no-op: it is the guard against a class of bugs that HikariCP's proxy prevents structurally. Accept the minimal overhead.

**CI**: All four stories pass `bazel test //kmp:jvm_tests`. No new Gradle tasks required. No screenshot tests are affected. Story 2 (leak detection) requires a test that sleeps 100–200 ms, which is acceptable for a JVM unit test; the test is time-bounded and will not flake.

**Pre-existing test coverage**: `PooledJdbcSqliteDriverTest` already covers connection reuse, overflow, in-memory semantics, listener lifecycle, close behavior, concurrent reads, and `withPinnedConnection`. The new tests for stories 1–4 extend this class; no new test files are needed.
