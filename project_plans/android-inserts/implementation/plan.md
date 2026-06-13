# Implementation Plan: Android Block Insert Performance

## Summary

Block insert operations on Android exhibit 1–2 second perceived lag caused by two
compounding issues: (1) `GraphWriter.savePageInternal` calls blocking SAF Binder IPC
methods from `Dispatchers.Default` threads, starving other coroutines on the same
pool; and (2) structural operations (`splitBlock`, `indentBlock`, `outdentBlock`,
`mergeBlock`) await the DB round-trip before moving keyboard focus, forcing the user to
wait ~5–20 ms even without any SAF involvement. This plan fixes both in priority order,
adds a CI-runnable JVM latency-shim benchmark to enforce a 200 ms P99 budget, and
adds WAL verification hardening to catch silent PRAGMA failures.

---

## Epics

### Epic 1: Fix dispatcher misuse in GraphWriter (INDEPENDENT)

**Goal**: Eliminate SAF Binder IPC calls running on `Dispatchers.Default` threads by
wrapping all blocking I/O inside `GraphWriter.savePageInternal` with
`withContext(PlatformDispatcher.IO)`.

**Dependency**: INDEPENDENT — no other epic must land first.

**Files to change**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`

**Why this is the highest-impact change**: The `GraphWriter` runs on
`CoroutineScope(SupervisorJob() + Dispatchers.Default)`. `Dispatchers.Default` has only
CPU-count threads (4–8 on typical phones). Every SAF `writeFile` / `fileExists` /
`readFile` call blocks one of those threads for 50–500 ms, starving other Default-
dispatched coroutines — including `BlockStateManager` state updates and debounce timers.
Moving the blocking I/O to `Dispatchers.IO` (the elastic thread pool) eliminates this
starvation without any data-model changes.

#### Story 1.1: Wrap all blocking SAF calls in savePageInternal with IO dispatcher

**Task 1.1.1**: In `GraphWriter.kt`, locate `savePageInternal` at line 265. The method
body is already inside `saveMutex.withLock { }`. Add a `withContext(PlatformDispatcher.IO)`
wrapper around the entire body of the `saveMutex.withLock` lambda so all blocking
filesystem calls (lines 296–414) execute on an IO thread. The `saveMutex` itself is
unaffected — it remains the outermost lock. Import `dev.stapler.stelekit.coroutines.PlatformDispatcher`.

Concretely:
```kotlin
private suspend fun savePageInternal(page: Page, blocks: List<Block>, graphPath: String): Boolean =
    saveMutex.withLock {
        withContext(PlatformDispatcher.IO) {   // ← ADD THIS
            // ... existing body unchanged ...
        }                                      // ← CLOSE
    }
```

**Task 1.1.2**: Verify `renamePage` (line 141) and `deletePage` (line 224) — both also
call `fileSystem.*` methods under `saveMutex.withLock`. Apply the same
`withContext(PlatformDispatcher.IO)` wrapper inside each `saveMutex.withLock` lambda.

**Task 1.1.3**: Add import `import dev.stapler.stelekit.coroutines.PlatformDispatcher` at
the top of `GraphWriter.kt` (it is already present in repository files but check the
current imports — it may already be there from the dispatcher matrix rules in CLAUDE.md).

**Task 1.1.4**: Run `./gradlew jvmTest` to confirm no existing tests break. The JVM
actual for `PlatformDispatcher.IO` is `Dispatchers.IO`, which is always available in JVM
tests.

---

### Epic 2: Optimistic UI update for structural block operations (INDEPENDENT)

**Goal**: Move `requestEditBlock` (keyboard focus / cursor positioning) BEFORE the DB
`await()` call in `splitBlock`, `addNewBlock`, `mergeBlock`, and `handleBackspace` so the
user never waits for a DB round-trip to get focus on the new block.

**Dependency**: INDEPENDENT — can be developed in parallel with Epic 1.

**Files to change**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

**Background**: `addBlockToPage` (line 728) already implements the correct optimistic
pattern — it inserts the block into `_blocks`, calls `requestEditBlock`, and then
`await()`s the DB write. The structural operations `splitBlock` (line 714), `addNewBlock`
(line 699), `mergeBlock` (line 782), and `handleBackspace` (line 808) currently call
`blockRepository.splitBlock(...)` which `await()`s the DB internally, and only then call
`requestEditBlock`. This is the pattern that causes the 1–2 s perceived lag.

`indentBlock` and `outdentBlock` do NOT have a focus-move after the operation so they
don't need this treatment (they already call `requestEditBlock` in the undo/redo only).

#### Story 2.1: Optimistic focus in splitBlock

**Task 2.1.1**: In `BlockStateManager.kt` `splitBlock` function (lines 714–726), read
the current block content from `_blocks` in-memory state before calling
`blockRepository.splitBlock`. Compute the expected new block UUID using
`UuidGenerator.generateV7()` — store it as `expectedNewUuid`. Call
`requestEditBlock(expectedNewUuid)` immediately (before the repository call) so focus
moves without waiting for the DB.

After the repository call resolves with `onRight { newBlock -> ... }`, if
`newBlock.uuid != expectedNewUuid` (UUID mismatch — should not happen with deterministic
UUID generation, but guard for it), call `requestEditBlock(newBlock.uuid)` to correct the
focus. Also update `_blocks` optimistically before `blockRepository.splitBlock` returns:
split the source block's content in `_blocks` and insert a placeholder new block with
`expectedNewUuid`.

On `onLeft` (DB write failure): remove the placeholder from `_blocks`, log the error,
show no rollback UI (the block was not committed).

Full implementation template:
```kotlin
fun splitBlock(blockUuid: String, cursorPosition: Int): Job = scope.launch {
    val pageUuid = getPageUuidForBlock(blockUuid) ?: return@launch
    val before = takePageSnapshot(pageUuid)

    // Optimistic: split _blocks in-memory and move focus immediately
    val sourceBlock = _blocks.value[pageUuid]?.find { it.uuid == blockUuid } ?: return@launch
    val firstPart = sourceBlock.content.substring(0, cursorPosition).trim()
    val secondPart = sourceBlock.content.substring(cursorPosition).trim()
    val expectedNewUuid = UuidGenerator.generateV7()
    val now = kotlin.time.Clock.System.now()
    val optimisticNew = sourceBlock.copy(
        uuid = expectedNewUuid,
        content = secondPart,
        position = sourceBlock.position + 1,
        leftUuid = blockUuid,
        createdAt = now,
        updatedAt = now,
    )
    _blocks.update { state ->
        val pageBlocks = state[pageUuid]?.toMutableList() ?: return@update state
        val idx = pageBlocks.indexOfFirst { it.uuid == blockUuid }
        if (idx >= 0) {
            pageBlocks[idx] = pageBlocks[idx].copy(content = firstPart)
            pageBlocks.add(idx + 1, optimisticNew)
        }
        state + (pageUuid to pageBlocks)
    }
    pendingNewBlockUuids.update { it + expectedNewUuid }
    requestEditBlock(expectedNewUuid)   // ← FOCUS MOVES HERE, before DB

    blockRepository.splitBlock(blockUuid, cursorPosition).onRight { newBlock ->
        pendingNewBlockUuids.update { it - expectedNewUuid }
        // DB block may have canonical UUID — correct if needed
        if (newBlock.uuid != expectedNewUuid) requestEditBlock(newBlock.uuid)
        queueDiskSave(pageUuid)
        val after = takePageSnapshot(pageUuid)
        record(
            undo = { restorePageToSnapshot(pageUuid, before); requestEditBlock(blockUuid, cursorPosition) },
            redo = { restorePageToSnapshot(pageUuid, after); requestEditBlock(newBlock.uuid) }
        )
    }.onLeft { err ->
        logger.error("splitBlock: DB write failed for $blockUuid: $err")
        pendingNewBlockUuids.update { it - expectedNewUuid }
        // Roll back optimistic update
        _blocks.update { state ->
            val pageBlocks = state[pageUuid]?.toMutableList() ?: return@update state
            pageBlocks.removeIf { it.uuid == expectedNewUuid }
            val idx = pageBlocks.indexOfFirst { it.uuid == blockUuid }
            if (idx >= 0) pageBlocks[idx] = pageBlocks[idx].copy(content = sourceBlock.content)
            state + (pageUuid to pageBlocks)
        }
        requestEditBlock(blockUuid, cursorPosition)
    }
}
```

**Task 2.1.2**: Apply the same pattern to `addNewBlock` (lines 699–712). The current
implementation calls `blockRepository.splitBlock(currentBlockUuid, block.content.length)`
and only then `requestEditBlock(newBlock.uuid)`. Follow the same template as Task 2.1.1
with `cursorPosition = currentBlock.content.length` (i.e., split at end = append empty
block). The optimistic new block has `content = ""`.

**Task 2.1.3**: For `mergeBlock` (lines 782–806) and `handleBackspace` (lines 808–854):
apply focus before the repository call. In `mergeBlock`, `requestEditBlock(prevBlock.uuid,
prevBlock.content.length)` currently fires inside `onRight { }`. Move it before
`blockRepository.mergeBlocks(...)`. Apply a corresponding rollback in `onLeft` by
re-focusing `blockUuid`.

In `handleBackspace`, move `requestEditBlock(focusUuid, focusPos)` before each
`blockRepository.mergeBlocks` / `blockRepository.deleteBlock` call. Add `onLeft` rollback
that re-focuses `blockUuid` at position 0.

**Task 2.1.4**: Update `BlockStateManagerTest` (in
`kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerTest.kt`) to
cover the rollback path: verify that when the repository returns `Left`, the optimistic
`_blocks` change is reversed and focus returns to the original block.

---

### Epic 3: JVM benchmark with SAF latency shim — CI enforcement (INDEPENDENT)

**Goal**: Create a `BlockInsertBenchmarkTest` in `jvmTest` that injects configurable
filesystem write latency and asserts P99 ≤ 200 ms wall-clock from "insert triggered" to
"DB write dispatched + file write dispatched"; fails CI if the budget is exceeded.

**Dependency**: INDEPENDENT — can run on the existing codebase without Epics 1 or 2.
After Epics 1 and 2 land the benchmark values will improve; the assertions are written to
pass after Epics 1–2.

**Files to create** (new, minimal surface):
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/LatencyShimFileSystem.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BlockInsertBenchmarkTest.kt`

**Files to read for infrastructure patterns**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/GraphLoadTimingTest.kt`

#### Story 3.1: Implement LatencyShimFileSystem

**Task 3.1.1**: Create
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/LatencyShimFileSystem.kt`.

The class wraps any `FileSystem` delegate and injects `delay()` (suspending, not
`Thread.sleep` — `savePageInternal` is a suspend fun) before `writeFile`,
`writeFileBytes`, `fileExists`, and `readFile` to simulate SAF Binder IPC latency.

```kotlin
package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.delay

/**
 * FileSystem wrapper that injects configurable latency before each write/exists/read
 * call, simulating Android SAF Binder IPC overhead for CI benchmarks.
 *
 * Use writeLatencyMs=50, existsLatencyMs=10, readLatencyMs=30 for the "typical mid-range
 * device" profile (P50 real-device measurements). These values are intentionally
 * conservative — the budget assertion uses P99=200ms.
 */
class LatencyShimFileSystem(
    private val delegate: FileSystem,
    private val writeLatencyMs: Long = 50L,
    private val existsLatencyMs: Long = 10L,
    private val readLatencyMs: Long = 30L,
) : FileSystem by delegate {

    override fun writeFile(path: String, content: String): Boolean {
        // Simulate Binder IPC: blocking call (suspend context handled by GraphWriter's withContext)
        Thread.sleep(writeLatencyMs)
        return delegate.writeFile(path, content)
    }

    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        Thread.sleep(writeLatencyMs)
        return delegate.writeFileBytes(path, data)
    }

    override fun fileExists(path: String): Boolean {
        Thread.sleep(existsLatencyMs)
        return delegate.fileExists(path)
    }

    override fun readFile(path: String): String? {
        Thread.sleep(readLatencyMs)
        return delegate.readFile(path)
    }
}
```

Note: `Thread.sleep` (not `delay`) is correct here because `GraphWriter.savePageInternal`
calls these methods synchronously inside `withContext(PlatformDispatcher.IO)`. The IO
dispatcher thread is what we want to block to simulate SAF latency. Using `delay` would
yield the coroutine instead of holding the thread, which would not accurately simulate the
Binder IPC blocking behavior.

**Task 3.1.2**: After Epic 1 lands, verify that `LatencyShimFileSystem` is injected into
`GraphWriter` in the benchmark and that the `withContext(PlatformDispatcher.IO)` wrapper
in `savePageInternal` moves the `Thread.sleep` calls off the `Default` pool. The benchmark
assertion will fail before Epic 1 (SAF calls on Default threads) and pass after it (SAF
calls on IO threads don't starve Default).

#### Story 3.2: Implement BlockInsertBenchmarkTest

**Task 3.2.1**: Create
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BlockInsertBenchmarkTest.kt`
following the `GraphLoadTimingTest` infrastructure pattern.

The test must:
1. Create a temp directory and SQLite backend using `RepositoryFactoryImpl` +
   `DriverFactory()` (same as `GraphLoadTimingTest`).
2. Create a `LatencyShimFileSystem` wrapping `PlatformFileSystem()` with default
   50ms/10ms/30ms latency profile.
3. Wire up: `DatabaseWriteActor`, `GraphWriter(fileSystem=shim, writeActor=actor)`,
   `BlockStateManager(blockRepository=..., graphWriter=writer, writeActor=actor)`.
4. Insert N=100 blocks sequentially via `BlockStateManager.addBlockToPage(pageUuid)` —
   the same path the user triggers with the Enter key.
5. For each insert, measure wall-clock time from the `addBlockToPage` call start to the
   point where `writeActor.saveBlock()` completes (DB committed). This is the
   user-perceived latency — keyboard focus appears, DB write is done.
6. Separately, measure wall-clock from `addBlockToPage` call start to when
   `GraphWriter.queueSave` has dispatched the file write (i.e., the debounced job is
   launched). This captures the file-write-dispatched latency.
7. Collect latencies, compute P50/P95/P99 using sorted-list percentile (same as
   `GraphLoadTimingTest`).
8. Assert `p99DbLatencyMs <= 200L` — fail the test if exceeded.
9. Write results to `kmp/build/reports/benchmark-insert.json` following the existing
   benchmark JSON convention.

Structure outline:
```kotlin
@Test
fun blockInsertLatency_syntheticGraph_shimmedSafFileSystem() = runBlocking {
    val tempDir = Files.createTempDirectory("block-insert-bench").toFile()
    // ... setup repos, actor, writer, BSM ...
    val dbLatencies = mutableListOf<Long>()
    repeat(100) { i ->
        val start = System.currentTimeMillis()
        blockStateManager.addBlockToPage(page.uuid).join()
        val elapsed = System.currentTimeMillis() - start
        dbLatencies.add(elapsed)
    }
    val sorted = dbLatencies.sorted()
    val p50 = sorted[49]; val p95 = sorted[94]; val p99 = sorted[98]
    println("BlockInsert P50=${p50}ms P95=${p95}ms P99=${p99}ms")
    writeBenchmarkJson("benchmark-insert", mapOf("p50" to p50, "p95" to p95, "p99" to p99))
    assertTrue(p99 <= 200L, "P99 insert latency ${p99}ms exceeds 200ms budget")
}
```

**Task 3.2.2**: Add a second test method `blockInsertLatency_noShim` that runs the same
benchmark WITHOUT the `LatencyShimFileSystem` (raw JVM `PlatformFileSystem`) and asserts
P99 ≤ 50ms (NFR-1: no JVM regression). This test must also pass before and after the fix.

**Task 3.2.3**: Wire the benchmark into `./gradlew jvmTest` — this is automatic since it
lives in `jvmTest`. Confirm it is included in `./gradlew ciCheck` (which calls `jvmTest`
per CLAUDE.md). Do NOT add a separate Gradle task; reuse the existing test infrastructure.

**Task 3.2.4**: Add the JSON output to CI artifact collection. Check
`.github/workflows/*.yml` for the step that reads `benchmark-*.json` files and add
`benchmark-insert.json` to the glob if it is not already covered by a wildcard.

---

### Epic 4: WAL verification and Android driver hardening (DEPENDS-ON-EPIC-1)

**Goal**: Read back the `journal_mode` PRAGMA after `createDriver` to verify WAL was
actually applied; log a diagnostic warning (not a crash) if WAL is not active; add to
Android startup diagnostics.

**Dependency**: DEPENDS-ON-EPIC-1 — Epic 1 is the primary performance fix. Epic 4 is
defensive hardening that makes silent PRAGMA failures visible. Implement after Epic 1 is
merged and green.

**Files to change**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt`

**Background**: `DriverFactory.android.kt` lines 50–59 apply PRAGMAs using
`try { driver.execute(...) } catch (_: Exception) { }`. If the PRAGMA fails silently (the
empty catch swallows the exception), the DB runs in DELETE journal mode for the entire
session — 10–100x slower for write-heavy workloads because every transaction commit
requires a full fsync. Research (pitfalls.md §6) confirms this risk with
`RequerySQLiteOpenHelperFactory` which applies PRAGMAs post-`schema.create`, not in
`onConfigure`. Verification cost is one extra `executeQuery` call at startup (negligible).

#### Story 4.1: Read back and verify WAL PRAGMA after driver init

**Task 4.1.1**: In `DriverFactory.android.kt`, after the PRAGMA-application block (after
line 59), add a WAL verification read-back:

```kotlin
// WAL verification — read back journal_mode to confirm the PRAGMA applied.
// RequerySQLiteOpenHelperFactory applies PRAGMAs post-schema-create; if schema.create
// is slow or throws, the PRAGMA block (lines 50–59) may have run in DELETE mode.
try {
    val cursor = driver.executeQuery(null, "PRAGMA journal_mode;", 0, null)
    val journalMode = if (cursor.next().value) cursor.getString(0) else null
    cursor.close()
    if (journalMode?.lowercase() != "wal") {
        android.util.Log.w("DriverFactory", "WAL not active — journal_mode=$journalMode. " +
            "SQLite writes will be slower. Check RequerySQLiteOpenHelperFactory onConfigure.")
    } else {
        android.util.Log.d("DriverFactory", "WAL confirmed active.")
    }
} catch (_: Exception) {
    android.util.Log.w("DriverFactory", "Could not verify journal_mode PRAGMA.")
}
```

**Task 4.1.2**: Move the PRAGMA applications from post-`AndroidSqliteDriver` construction
(lines 50–59) into a `RequerySQLiteOpenHelperFactory` custom `Callback` that overrides
`onConfigure(db: SupportSQLiteDatabase)`. The `onConfigure` hook fires before
`schema.create` and is the correct place for per-connection PRAGMAs.

Create a private inner class `WalConfiguredCallback` inside `DriverFactory.android.kt`:

```kotlin
private class WalConfiguredCallback(
    private val schema: SqlSchema<*>,
) : SupportSQLiteOpenHelper.Callback(schema.version.toInt()) {
    override fun onCreate(db: SupportSQLiteDatabase) {
        // Schema creation handled by AndroidSqliteDriver
    }
    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations handled by AndroidSqliteDriver
    }
    override fun onConfigure(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA journal_mode=WAL;")
        db.execSQL("PRAGMA synchronous=NORMAL;")
        db.execSQL("PRAGMA busy_timeout=10000;")
        db.execSQL("PRAGMA wal_autocheckpoint=4000;")
        db.execSQL("PRAGMA temp_store=MEMORY;")
        db.execSQL("PRAGMA cache_size=-8000;")
    }
}
```

Then replace the `RequerySQLiteOpenHelperFactory()` argument in the `AndroidSqliteDriver`
constructor call with a factory that injects the callback. Check the
`RequerySQLiteOpenHelperFactory` API — it accepts a `SupportSQLiteOpenHelper.Factory`
callback; the exact injection point depends on which constructor overload is used. If the
factory does not expose `onConfigure`, apply PRAGMAs in the post-construction block as
now but add the read-back verification from Task 4.1.1 regardless.

**Task 4.1.3**: Add a `DomainError.DatabaseError.WalNotActive` variant to
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`. This is a
diagnostic-only error (no user-visible impact); it exists so the startup diagnostic can
be surfaced in developer builds via the existing `RingBufferSpanExporter` rather than only
a logcat line. Add a `@Suppress("UnusedPrivateClass")` if detekt requires it.

**Task 4.1.4**: Run `./gradlew ciCheck` to confirm the new code compiles and all existing
Android unit tests pass. The verification code runs at driver init time (app startup),
which is mocked in unit tests; ensure it doesn't break `DriverFactory`-dependent tests.

---

## ADR Flags

The following technology decisions require Architecture Decision Records before
implementation begins (create in `project_plans/android-inserts/decisions/`):

| ADR | Decision | Epic | Trigger condition |
|-----|----------|------|-------------------|
| ADR-001 | Use `Thread.sleep` in `LatencyShimFileSystem` vs. `delay()` | Epic 3 | `Thread.sleep` simulates Binder blocking accurately but pins an IO thread; `delay()` would measure async latency not Binder IPC latency. Decision is `Thread.sleep` as justified above. |
| ADR-002 | Optimistic UUID pre-generation in `splitBlock` | Epic 2 | `SqlDelightBlockRepository.splitBlock` generates the new block UUID internally via `UuidGenerator.generateV7()`. If the BSM pre-generates the same UUID optimistically, the two must agree. Options: (a) BSM pre-generates, passes to repository; (b) repository generates, BSM uses a placeholder UUID. Both require an interface change. ADR must capture the chosen approach. |
| ADR-003 | `onConfigure` callback support in `RequerySQLiteOpenHelperFactory` | Epic 4 | If `RequerySQLiteOpenHelperFactory` does not expose `onConfigure`, Task 4.1.2 falls back to post-construction PRAGMAs. ADR must document the discovery and chosen fallback. |

---

## Task Count Summary

- **Epics**: 4
- **Stories**: 7 (1.1, 2.1, 3.1, 3.2, 4.1 + two sub-stories in Epic 2)
- **Tasks**: 18
  - Epic 1: 4 tasks
  - Epic 2: 4 tasks
  - Epic 3: 4 tasks
  - Epic 4: 4 tasks

## Parallelization Guide

Epics 1, 2, and 3 are fully independent and can be developed in three parallel branches.
Epic 4 should follow Epic 1 (share the same branch or a follow-up branch from Epic 1's
green CI). Recommended merge order: Epic 2 → Epic 3 → Epic 1 → Epic 4, but any order
that keeps CI green is acceptable since each epic is independently testable.
