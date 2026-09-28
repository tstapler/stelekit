# Architecture Review: hikari-pool-lessons plan

**Date**: 2026-07-01
**Verdict**: CONCERNS

## Findings

### autoCommit reset design — CLEAN

Calling `connection.rollback()` before `setAutoCommit(true)` is correct belt-and-suspenders: sqlite-jdbc's `setAutoCommit(true)` already issues an implicit ROLLBACK for open transactions, but the explicit rollback ensures the failure is logged even if `setAutoCommit` swallows the exception. Overflow connections (destined for `connection.close()`) also pass through the autoCommit check unnecessarily, but this is a harmless extra JNI call; correctness is not affected. The `withPinnedConnection` case is correctly handled: a block that leaves autoCommit=false (e.g., uncaught exception mid-transaction) will have its transaction rolled back on return, which is the desired defensive behavior.

### Leak detection — in-memory path not covered — CONCERN

The implementation sketch for Story 2 shows leak task registration only in the non-memory branch of `getConnection()`. The acceptance criteria say "a connection held past that threshold" with no file-DB-only qualifier, so in-memory connections should also get leak detection. The in-memory `while(true)` loop returns via `return conn` inside the loop body; the task registration block must be inserted there (before `return conn`) or the feature silently does not apply to in-memory drivers. This is an omission in the sketch, not in the acceptance criteria — the implementer will likely catch it, but it should be explicit.

### Leak detection — lazy executor init in `close()` — CONCERN

`leakScheduler` is declared `by lazy`. The `close()` method guards with `if (leakThresholdMs > 0) leakScheduler.shutdown()`. If `leakThresholdMs > 0` but no connection was ever checked out (the lazy was never triggered), `close()` accessing `leakScheduler` creates a fresh `ScheduledThreadPoolExecutor` and immediately shuts it down — wasteful but harmless. A cleaner guard is `if (leakThresholdMs > 0 && (::leakScheduler as Lazy<*>).isInitialized()) leakScheduler.shutdown()`. The current approach creates no correctness bug but will surprise code reviewers and shows up as an unexpected thread creation in test teardown profiling.

### `computeIfAbsent` race fix — CLEAN

`ConcurrentHashMap.computeIfAbsent` is atomic: the factory function is called at most once per key and concurrent callers for the same new key block on the first insertion. The fix is correct and the 1-line change cannot regress existing sequential behavior. `getOrPut` compiles to `get` + conditional `put` with a gap, which the plan documents accurately.

### `closed.get()` guard position and stated guarantee — CONCERN

The acceptance criteria state "The check is positioned before `pool.poll()` to avoid a TOCTOU window between the poll and the overflow creation." This overstates what the guard accomplishes. The TOCTOU window between `closed.get()` returning false and the `pool.poll()` executing still exists: `close()` can drain the pool in that window, causing `poll()` to return null and `DriverManager.getConnection()` to create an overflow connection on a closing driver anyway. The guard eliminates only the case where `closed` is already true before the call, not the concurrent race. The implementation is still a meaningful improvement over no guard at all; the acceptance criteria should say "significantly reduces the window" rather than imply elimination.

### Implementation ordering risk (3→4→1→2) — CLEAN

All four stories are independently mergeable. The interaction in `closeConnection()` — leak-cancel (Story 2) runs before the autoCommit check (Story 1) which runs before `pool.offer()` — is the correct final ordering. Merging Story 1 first then Story 2 produces this ordering naturally: Story 2 inserts its `leakTasks.remove(connection)?.cancel(false)` at the top of `closeConnection()`, before Story 1's autoCommit block. No implicit sequencing dependency exists between any pair of stories.

### Thread safety of `withPinnedConnection` — CLEAN

Adding the autoCommit reset to `closeConnection()` does not break `withPinnedConnection`. The `finally` block unconditionally calls `closeConnection(conn)`, which will now roll back any open transaction if the pinned block exited without committing. This is correct defensive behavior: callers are expected to commit before the block exits, and the WARNING log is appropriate if they do not. Existing tests (`withPinnedConnection returns connection to pool even when block throws`) are unaffected because those tests don't alter autoCommit.

### Pool size constraint on `leakTasks` map — CLEAN

Maximum entries in `ConcurrentHashMap<Connection, ScheduledFuture<*>>` equals pool size (8) plus concurrent overflow connections. Overflow on file DBs is bounded by `Dispatchers.IO` thread count (Kotlin default: 64), giving a worst-case map size of ~72 entries. Each entry is two object references (~32 bytes). No bounding mechanism needed.

### `ScheduledThreadPoolExecutor` lifecycle — CLEAN (modulo lazy concern above)

`shutdown()` (orderly) is the correct choice over `shutdownNow()`: leak-detection tasks just emit log lines and should be allowed to complete if already running. Pending tasks that have not yet fired will be cancelled by `shutdown()`, which is correct — a connection returned after `close()` was called does not need its late-firing task suppressed separately (the `leakTasks.remove()` in `closeConnection()` handles that). `cancel(false)` in `closeConnection()` is correct: `false` means "don't interrupt if running," which avoids aborting a log operation in progress.

---

## Required Changes Before Implementation

None. No story is BLOCKED.

---

## Open Questions

1. **Story 2 in-memory path**: Should the implementation sketch be updated to explicitly show where leak task registration is inserted in the `while(true)` polling loop? Recommend yes — the omission is likely to be missed in review since the in-memory branch is structurally different.

2. **Story 4 acceptance criteria wording**: The claim "positioned before `pool.poll()` to avoid a TOCTOU window" should be weakened to "reduces" — should this be corrected in the plan before the PR is opened, or is a code comment sufficient?

3. **Lazy executor init**: Is `(::leakScheduler as Lazy<*>).isInitialized()` idiomatic enough to include in production code, or is a separate `AtomicBoolean leakSchedulerStarted` cleaner? Either resolves the wasteful-init concern.
