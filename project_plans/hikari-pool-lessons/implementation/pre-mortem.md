# Pre-mortem: hikari-pool-lessons plan

**Date**: 2026-07-01

## Scenarios

### Scenario A — withPinnedConnection + autoCommit reset = silent DDL rollback
**What goes wrong**: `withPinnedConnection`'s KDoc says it is for callers who need "multi-statement sequences that must all run on the same JDBC connection — for example, wrapping DDL in an explicit `BEGIN`/`COMMIT`." A future caller writes a migration helper that sets `autoCommit=false`, executes DDL, commits, but forgets to reset `autoCommit=true` before returning from the block. The new `closeConnection()` logic detects `autoCommit=false`, calls `rollback()`, and resets — but the `rollback()` after an explicit `commit()` is a no-op in SQLite, so the DDL survives. However, a second caller that sets `autoCommit=false`, issues DDL, and exits the block via exception (without committing) will have their DDL silently rolled back — SQLite supports transactional DDL. The WARNING log appears once on a code path that rarely runs in production tests, and the schema change appears to succeed locally (Gradle runs `generateCommonMainSteleDatabase` at build time, regenerating sources) but fails at runtime on production databases where the table never existed.

**P(incident)**: Low

**Mitigation in plan**: The plan notes `withPinnedConnection` does NOT set `autoCommit=false` itself, and `Migration.init {}` prevents raw `BEGIN` statements. Current `MigrationRunner` uses `RestrictedDatabaseQueries` rather than raw JDBC, so no current caller sets `autoCommit=false` inside `withPinnedConnection`.

**Recommended addition**: Add a KDoc warning directly on `withPinnedConnection`: callers MUST NOT set `connection.autoCommit = false` on the connection argument. If explicit transaction control is needed, use SQLDelight's `transaction { }` block or `DatabaseWriteActor`. Add a `check(connection.autoCommit) { "..." }` assertion at the start of `withPinnedConnection` as a belt-and-suspenders guard (throws loudly in tests; catches violations before they reach production).

---

### Scenario B — Leak detector fires during legitimate long migrations
**What goes wrong**: `DriverFactory.jvm.kt` is configured with `leakThresholdMs = 5_000L` as the plan recommends. A large-graph migration (MigrationRunner step over 8 000 pages) or a `withPinnedConnection`-based DDL operation takes 12 seconds on a slow HDD under test. The leak detector fires mid-migration: it logs a WARNING containing the checkout stack trace pointing at `withPinnedConnection` → `MigrationRunner`. On-call engineers who receive WARN-level log alerts page into an incident and consider aborting the migration process. The WARN fires even though the connection is returned normally when the migration completes 7 seconds later. If monitoring is alert-on-WARN, this is a false-positive page every time a migration runs on a slow machine.

**P(incident)**: Medium

**Mitigation in plan**: None. The plan explicitly states "5 seconds is HikariCP's recommended minimum" without discussing what the longest legitimate `withPinnedConnection` operation takes or how to suppress false positives for known-long operations.

**Recommended addition**: (1) Before wiring `leakThresholdMs` in `DriverFactory.jvm.kt`, measure the p99 duration of every `withPinnedConnection` call site in production (or a representative benchmark run) and set `leakThresholdMs` to at least 2× that value. (2) Add a suppressible variant: `withPinnedConnectionNoLeakDetection` for use by `MigrationRunner`, or accept a `leakThresholdOverrideMs: Long? = null` parameter on `withPinnedConnection` so long-running operations can opt out of the default threshold. (3) Document in the `leakThresholdMs` KDoc that the configured value must exceed the worst-case legitimate operation duration.

---

### Scenario C — addListener / removeListener race after computeIfAbsent fix
**What goes wrong**: Nothing. The reasoning is sound. `ConcurrentHashMap.computeIfAbsent` is atomic — the factory lambda runs at most once per key, and concurrent calls for the same new key block until the first completes and then reuse the inserted `CopyOnWriteArrayList`. A concurrent `removeListener` that executes between `computeIfAbsent` (which returns the list) and the subsequent `.add(listener)` call will find an empty list and remove nothing; Thread A's `.add()` then succeeds, leaving the listener registered. Concurrent add/remove with no caller-level synchronization has inherently non-deterministic ordering — both outcomes (listener registered or not) are valid. COWAL operations are individually atomic, and no compound invariant is violated.

**P(incident)**: Low

**Mitigation in plan**: The `computeIfAbsent` fix directly addresses the only real race (concurrent first-put). The add/remove race described in this scenario is benign by design.

**Recommended addition**: None needed.

---

### Scenario D — Pool silently shrinks after broken-connection discard; no metric surfaces it
**What goes wrong**: A transient filesystem I/O error (full disk, NFS blip, TMPFS exhaustion on CI) causes `connection.setAutoCommit(true)` to throw `SQLException` inside the new `closeConnection()` guard. The connection is correctly discarded. Pool capacity is now 7/8 permanently — the plan explicitly accepts this: "pool shrinks by 1 slot, recovers on driver restart." The WARNING log is emitted once. If the same I/O condition triggers on subsequent connection returns (e.g., 5 connections are checked out during a bulk import that hits disk-full), the pool drains to 3/8. With 3 pooled connections and Dispatchers.IO (up to 64 threads), overflow connections are now created on any burst, each adding 32 MB page cache. GC pressure rises. Throughput degrades. The engineer investigating sees CPU/memory symptoms, not a connection pool warning from 20 minutes earlier. Diagnosis takes 30+ minutes and requires log archaeology.

`drainPoolWaitStats()` returns `PoolWaitSnapshot(totalMs, count)` — it does not expose current pool size vs. configured pool size. There is no metric an operator can chart to detect "pool is at 5/8."

**P(incident)**: Medium

**Mitigation in plan**: The WARNING log is the only signal. The plan does not mention exposing pool size as a metric.

**Recommended addition**: Add `currentPoolSize: Int` to `PoolWaitSnapshot` (or return it separately via a new `poolHealthSnapshot()` method). Populate it with `pool.size` at drain time. This gives `QueryStatsReporter` or any telemetry consumer a chartable signal. Separately: after discarding a broken connection in `closeConnection()`, attempt to replace it with a fresh connection via `DriverManager.getConnection(url, properties)` and re-enqueue it — this keeps the pool at configured size without requiring a restart. The `try/catch` around the replacement creation prevents cascading failures if the disk is genuinely full.

---

### Scenario E — leakScheduler shutdown does not cancel pending tasks; false WARNINGs after close
**What goes wrong**: `close()` is called while a leak detection task is already scheduled (a connection was checked out 4 seconds ago; `leakThresholdMs = 5_000`). `leakScheduler.shutdown()` initiates an orderly shutdown but does NOT cancel already-submitted tasks — `ScheduledThreadPoolExecutor.shutdown()` has `setExecuteExistingDelayedTasksAfterShutdownPolicy(true)` by default. One second after `close()` returns, the leak task fires and logs a WARNING attributing the "leak" to the stack trace from a connection checkout that was part of normal graph teardown. Engineers investigating a clean `GraphManager.switchGraph()` see a spurious leak warning in the logs.

The lazy initialization scenario specifically mentioned in the prompt is NOT a problem: when `leakThresholdMs > 0`, `close()` accesses `leakScheduler` via `if (leakThresholdMs > 0) leakScheduler.shutdown()`, which triggers lazy init (creating the executor) and immediately shuts it down — no resource leak.

**P(incident)**: Low

**Mitigation in plan**: None (the issue with pending tasks surviving `shutdown()` is not mentioned).

**Recommended addition**: Replace `leakScheduler.shutdown()` in `close()` with `leakScheduler.shutdownNow()`. This cancels pending (not yet started) tasks and interrupts running ones. Since the leak task body is a single `log.warning(...)` call (not a blocking loop), interruption is safe. Alternatively, call `leakScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)` at construction time so `shutdown()` already cancels pending tasks.

---

## P1 Items (must fix before implementation)

None. No scenario has both High P(incident) and no existing mitigation.

---

## P2 Items (fix in follow-up PR)

**Scenario B — Leak detector false positives during long migrations**
Measure worst-case `withPinnedConnection` duration before wiring `leakThresholdMs` in production. Add per-call-site threshold override or a `withPinnedConnectionNoLeakDetection` escape hatch for `MigrationRunner`. Document the constraint in the `leakThresholdMs` KDoc.

**Scenario D — No metric for degraded pool size**
Add `currentPoolSize: Int` to `PoolWaitSnapshot`. Consider adding post-discard connection replacement in `closeConnection()` to keep the pool at configured capacity without requiring a driver restart.

**Scenario E — Pending leak tasks survive `close()`**
Replace `leakScheduler.shutdown()` with `leakScheduler.shutdownNow()` in `close()` to prevent false-positive WARNINGs firing after driver teardown.
