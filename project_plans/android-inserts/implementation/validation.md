# Validation Plan: Android Block Insert Performance

## Requirements Coverage

| Req ID | Test(s) | Type | Status |
|--------|---------|------|--------|
| FR-1 (Diagnose bottleneck) | TC-01 (dispatcher thread assertion) | Unit | Planned |
| FR-2 (Fix bottleneck) | TC-01, TC-02, TC-03, TC-04, TC-05, TC-06, TC-07, TC-08 | Unit + Regression | Planned |
| FR-3 (Benchmark — simulated SAF latency, CI-runnable) | TC-09, TC-10 | Benchmark | Planned |
| FR-4 (Benchmark — real Android, optional) | TC-13 (androidUnitTest, local-only) | Integration | Planned |
| FR-5 (Performance budget enforcement) | TC-09 (P99 ≤ 200ms assert), TC-10 (P99 ≤ 50ms assert) | Benchmark | Planned |
| NFR-1 (No JVM regression) | TC-10 | Benchmark | Planned |
| NFR-2 (Data integrity — no lost writes) | TC-11 | Unit | Planned |
| NFR-3 (ciCheck passes) | All jvmTest + androidUnitTest cases | All | Planned |

**Coverage: 8/8 requirements covered (6 FR + 2 NFR). NFR-3 is satisfied by the full suite running under `./gradlew ciCheck`.**

---

## Test Infrastructure: Shared Test Doubles

### `CountingFileSystem`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/CountingFileSystem.kt`
(Also usable from `commonTest` if declared in a shared fixtures module — JVM-only is sufficient for these tests.)

```kotlin
package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import java.util.concurrent.atomic.AtomicInteger

/**
 * FileSystem spy that delegates to a real implementation and counts calls to
 * writeFile, readFile, and fileExists. Used to enforce per-insert call budgets
 * so regressions that add spurious SAF calls are caught by CI.
 */
class CountingFileSystem(private val delegate: FileSystem) : FileSystem by delegate {
    val writeFileCount = AtomicInteger(0)
    val readFileCount  = AtomicInteger(0)
    val existsCount    = AtomicInteger(0)

    override fun writeFile(path: String, content: String): Boolean {
        writeFileCount.incrementAndGet()
        return delegate.writeFile(path, content)
    }
    override fun readFile(path: String): String? {
        readFileCount.incrementAndGet()
        return delegate.readFile(path)
    }
    override fun fileExists(path: String): Boolean {
        existsCount.incrementAndGet()
        return delegate.fileExists(path)
    }

    fun reset() { writeFileCount.set(0); readFileCount.set(0); existsCount.set(0) }

    /** Assert budgets for a single insert cycle (no debounce fired yet). */
    fun assertInsertBudget(label: String = "") {
        val tag = if (label.isBlank()) "" else "[$label] "
        assertEquals(0, writeFileCount.get(), "${tag}writeFile must not be called synchronously during insert (debounce must defer it)")
        assertEquals(0, readFileCount.get(),  "${tag}readFile must not be called during normal insert")
    }

    /** Assert exactly one writeFile fired after the debounce window. */
    fun assertDebounceFired(label: String = "") {
        val tag = if (label.isBlank()) "" else "[$label] "
        assertEquals(1, writeFileCount.get(), "${tag}exactly 1 writeFile expected after debounce, got ${writeFileCount.get()}")
    }
}
```

---

## Test Cases

### TC-01: GraphWriter IO dispatcher — savePageInternal executes file calls on PlatformDispatcher.IO

**Type**: Unit  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphWriterDispatcherTest.kt`  
**Setup**:
- Create a `TrackingFileSystem` that records the name of the thread that called `writeFile` (captures `Thread.currentThread().name`).
- Construct `GraphWriter(fileSystem = trackingFs)` using `PlatformFileSystem()` as the real delegate.
- Create a temp directory with a single markdown page on disk.
- Call `writer.savePage(page, blocks, graphPath)` and `join()` the resulting job.

**Assertion**:
```
assertTrue(capturedThreadName.startsWith("DefaultDispatcher-worker") ||
           capturedThreadName.contains("IO"),
    "writeFile must execute on IO dispatcher thread, was: $capturedThreadName")
```
On JVM, `PlatformDispatcher.IO` maps to `Dispatchers.IO`, whose threads are named `DefaultDispatcher-worker-N` or `kotlinx.coroutines.DefaultExecutor`. Verify the thread is NOT a `Default`-dispatcher thread that also handles `Dispatchers.Default` exclusively.

**Alternative assertion** (simpler, preferred): use `BlockHound` (already configured in `BlockHoundTestBase.kt`) — any `Thread.sleep` or blocking I/O on a non-IO thread triggers a `BlockingOperationError` and fails the test automatically. Extend `BlockHoundTestBase` to verify no blocking calls fire on `Dispatchers.Default` during a `savePage` call.

**Requirement**: FR-1, FR-2  
**Maps to epic**: Epic 1 (Task 1.1.1)

---

### TC-02: GraphWriter IO dispatcher — renamePage executes file calls on PlatformDispatcher.IO

**Type**: Unit  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphWriterDispatcherTest.kt` (same file as TC-01)  
**Setup**: Same `TrackingFileSystem` / `BlockHound` harness as TC-01.  
**Assertion**: Call `writer.renamePage(page, oldName, graphPath)`. Assert `writeFile` and `deleteFile` are invoked on an IO thread (same condition as TC-01).  
**Requirement**: FR-2  
**Maps to epic**: Epic 1 (Task 1.1.2)

---

### TC-03: GraphWriter IO dispatcher — deletePage executes file calls on PlatformDispatcher.IO

**Type**: Unit  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphWriterDispatcherTest.kt`  
**Setup**: Same harness as TC-01.  
**Assertion**: Call `writer.deletePage(page, graphPath)`. Assert `deleteFile` (or `fileExists` + `deleteFile`) fires on an IO thread.  
**Requirement**: FR-2  
**Maps to epic**: Epic 1 (Task 1.1.2)

---

### TC-04: BlockStateManager optimistic update — splitBlock moves _blocks BEFORE DB write completes

**Type**: Unit  
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerTest.kt` (extend existing file)  
**Setup**:
- Create a `DelayedBlockRepository` that wraps `InMemoryBlockRepository` and suspends for 500ms before returning from `splitBlock` (simulates the DB round-trip delay).
- Create a `BlockStateManager` with this repository.
- Load a page with 1 block (content = "HelloWorld", uuid = "block-1").
- Launch `bsm.splitBlock("block-1", 5)` without joining.
- Immediately after launch (before 500ms elapses), collect `bsm.blocks.value["test-page"]`.

**Assertion**:
```
val blocks = bsm.blocks.value[pageUuid]
assertEquals(2, blocks?.size, "_blocks must have 2 entries immediately after splitBlock launch")
assertEquals("Hello", blocks?.get(0)?.content)
assertEquals("World", blocks?.get(1)?.content)
```
Also assert `bsm.editRequests.value` (or equivalent focus signal) contains the new block UUID before the 500ms delay expires.

**Requirement**: FR-2  
**Maps to epic**: Epic 2 (Task 2.1.1)

---

### TC-05: BlockStateManager optimistic update — addNewBlock moves _blocks BEFORE DB write completes

**Type**: Unit  
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerTest.kt`  
**Setup**: Same `DelayedBlockRepository` as TC-04. Load a page with 1 block. Launch `bsm.addNewBlock("block-1")` without joining.  
**Assertion**: Immediately after launch, `bsm.blocks.value[pageUuid]` has 2 entries; second block has `content = ""`.  
**Requirement**: FR-2  
**Maps to epic**: Epic 2 (Task 2.1.2)

---

### TC-06: BlockStateManager rollback — splitBlock rolls back _blocks on DB failure

**Type**: Unit  
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerTest.kt`  
**Setup**:
- Create a `FailingBlockRepository` that always returns `Left(DomainError.DatabaseError.WriteFailed("injected"))` from `splitBlock`.
- Load a page with 1 block (content = "HelloWorld").
- Call `bsm.splitBlock("block-1", 5).join()`.

**Assertion**:
```
val blocks = bsm.blocks.value[pageUuid]
assertEquals(1, blocks?.size, "_blocks must be rolled back to 1 entry on DB failure")
assertEquals("HelloWorld", blocks?.get(0)?.content, "original content must be restored")
```
Also assert focus signal returns to `"block-1"` at position 5.

**Requirement**: FR-2  
**Maps to epic**: Epic 2 (Task 2.1.4)

---

### TC-07: BlockStateManager rollback — mergeBlock rolls back on DB failure

**Type**: Unit  
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerTest.kt`  
**Setup**: `FailingBlockRepository` that fails `mergeBlocks`. Load a page with 2 blocks. Call `bsm.mergeBlock("block-2").join()`.  
**Assertion**: `_blocks` still has 2 entries after join; focus signal returns to `"block-2"` at position 0.  
**Requirement**: FR-2  
**Maps to epic**: Epic 2 (Task 2.1.3)

---

### TC-08: FileSystemCallCountTest — parameterized: each insert operation triggers 0 writeFile calls synchronously, 1 after debounce

**Type**: Regression / Unit  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/FileSystemCallCountTest.kt`  
**Setup**:
- Build a full test harness: `InMemoryBlockRepository`, `InMemoryPageRepository`, `CountingFileSystem` wrapping `FakeFileSystem`, `GraphWriter(countingFs)`, `BlockStateManager`.
- Load a page with 1 block.
- Parameterize over 5 operations: `{newBlock, splitBlock, indentBlock, outdentBlock, pasteBlock}`.

**For each operation**:
1. `countingFs.reset()`
2. Launch the operation and `.join()` it (DB write completes, debounce timer has NOT fired — use `TestCoroutineScheduler` or `advanceTimeBy(0)` to ensure debounce window is not advanced).
3. Call `countingFs.assertInsertBudget(operationName)` — asserts `writeFile == 0`, `readFile == 0`.
4. Advance virtual time past the debounce window (500ms): `advanceTimeBy(600)` or `advanceUntilIdle()`.
5. Call `countingFs.assertDebounceFired(operationName)` — asserts `writeFile == 1`.

**Assertion summary**:
- `writeFile` call count during insert (before debounce): exactly 0
- `readFile` call count during insert: exactly 0
- `writeFile` call count after debounce fires: exactly 1

**Requirement**: FR-2, FR-3, FR-5  
**Maps to epic**: Epic 1 + Epic 2 (primary regression guard for both)

---

### TC-09: BlockInsertBenchmarkTest — P99 ≤ 200ms with SAF latency shim

**Type**: Benchmark  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BlockInsertBenchmarkTest.kt`  
**Setup**:
- Create a temp directory and SQLite backend via `RepositoryFactoryImpl(DriverFactory(), ...)`.
- Construct `LatencyShimFileSystem` wrapping `PlatformFileSystem()` with `writeLatencyMs=50, existsLatencyMs=10, readLatencyMs=30`.
- Wire `DatabaseWriteActor`, `GraphWriter(fileSystem=shim)`, `BlockStateManager`.
- Create a page and insert N=100 blocks sequentially via `bsm.addBlockToPage(pageUuid).join()`.
- Record wall-clock time per insert (start = before `addBlockToPage` call, end = after `.join()` returns, meaning DB write is committed).

**Assertion**:
```kotlin
val sorted = dbLatencies.sorted()
val p50 = sorted[49]; val p99 = sorted[98]
assertTrue(p99 <= 200L, "P99 insert latency ${p99}ms exceeds 200ms budget")
assertTrue(p50 <= 50L,  "P50 insert latency ${p50}ms exceeds 50ms budget")
```
Write results to `kmp/build/reports/benchmark-insert.json` following the `writeJson()` pattern from `GraphLoadTimingTest`.

**Requirement**: FR-3, FR-5  
**Maps to epic**: Epic 3 (Tasks 3.2.1, 3.2.3)

---

### TC-10: BlockInsertBenchmarkTest — P99 ≤ 50ms without shim (JVM regression guard)

**Type**: Benchmark  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BlockInsertBenchmarkTest.kt` (second `@Test` method in same class)  
**Setup**: Same as TC-09 but with `PlatformFileSystem()` directly (no `LatencyShimFileSystem`). N=100 inserts.  
**Assertion**:
```kotlin
assertTrue(p99 <= 50L, "JVM P99 insert latency ${p99}ms exceeds 50ms budget (NFR-1 regression)")
```
**Requirement**: NFR-1  
**Maps to epic**: Epic 3 (Task 3.2.2)

---

### TC-11: Data integrity — no write loss when DB write succeeds but file write is deferred

**Type**: Unit  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphWriterIntegrityTest.kt`  
**Setup**:
- Use `CountingFileSystem` wrapping a real `PlatformFileSystem` on a temp directory.
- Create a `BlockStateManager` with a real `InMemoryBlockRepository` (or SQLite in-memory).
- Insert 3 blocks. Confirm DB write completes (`bsm.addBlockToPage(...).join()` for each).
- Simulate process kill by cancelling the `GraphWriter`'s coroutine scope BEFORE the debounce fires (advance time by 0ms).
- Verify `countingFs.writeFileCount == 0` (file write was deferred and never executed).
- Reconstruct a new `BlockStateManager` reading from the repository (which survived the "kill").
- Assert that all 3 blocks are present in the reloaded state.

**Assertion**: All 3 blocks survive a simulated pre-debounce process kill because the DB (source of truth) was already written.  
**Note**: This test validates the architecture documented in NFR-2 — the DB is the source of truth; file writes are a derived export.  
**Requirement**: NFR-2  
**Maps to epic**: Epic 1 (architectural contract) + Epic 2 (debounce design)

---

### TC-12: LatencyShimFileSystem — Thread.sleep blocks the calling thread (not suspend)

**Type**: Unit  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/LatencyShimFileSystemTest.kt`  
**Setup**: Construct `LatencyShimFileSystem(FakeFileSystem(), writeLatencyMs=20)`. Record wall-clock before and after a direct `writeFile("p", "c")` call (not from a coroutine).  
**Assertion**:
```kotlin
assertTrue(elapsed >= 20L, "LatencyShim must block the calling thread for at least 20ms, was ${elapsed}ms")
```
This validates ADR-001: `Thread.sleep` (not `delay`) is the correct simulation of Binder IPC blocking.  
**Requirement**: FR-3  
**Maps to epic**: Epic 3 (Task 3.1.1, ADR-001)

---

### TC-13: AndroidDriverWalTest — PRAGMA journal_mode reads back as "wal" after driver init

**Type**: Unit (Android)  
**File**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/db/AndroidDriverWalTest.kt`  
**Setup**:
- Use `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [30])` (same pattern as `PlatformFileSystemSafTest`).
- Construct a `DriverFactory` and call `createDriver(schema, context)` (or the equivalent factory method used in `androidMain`).
- Execute `PRAGMA journal_mode;` via `driver.executeQuery(...)`.

**Assertion**:
```kotlin
val cursor = driver.executeQuery(null, "PRAGMA journal_mode;", 0, null)
val mode = if (cursor.next().value) cursor.getString(0) else null
cursor.close()
assertEquals("wal", mode?.lowercase(), "SQLite journal_mode must be WAL after driver init")
```
**Requirement**: FR-2 (Epic 4 hardening), implicit NFR-3  
**Maps to epic**: Epic 4 (Task 4.1.1)

---

### TC-14: GraphWriter CountingFileSystem — renamePage triggers exactly 1 writeFile (new name) and 1 deleteFile (old name) on IO thread

**Type**: Unit  
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/FileSystemCallCountTest.kt` (same file as TC-08, separate test method)  
**Setup**: `CountingFileSystem` wrapping a real temp-directory `PlatformFileSystem`. Call `writer.renamePage(page, oldName, graphPath)` and join.  
**Assertion**:
```kotlin
assertEquals(1, countingFs.writeFileCount.get(),  "renamePage must write exactly 1 file (new name)")
assertEquals(0, countingFs.readFileCount.get(),   "renamePage must not read any files")
```
(deleteFile is not currently tracked by `CountingFileSystem` — extend it with a `deleteFileCount` if needed.)  
**Requirement**: FR-2  
**Maps to epic**: Epic 1 (Task 1.1.2)

---

## Detekt Rule: Future Hardening (Not Blocking This PR)

**Rule name**: `DirectFileSystemCallOutsideIOContext`  
**Intent**: Detect any call to `fileSystem.writeFile`, `fileSystem.readFile`, or `fileSystem.fileExists` that is not lexically inside a `withContext(PlatformDispatcher.IO)` block.  
**Status**: Deferred. Add a `// TODO: Add DirectFileSystemCallOutsideIOContext detekt rule` comment at the top of `GraphWriter.kt` at the `savePageInternal` method to mark the IO boundary explicitly. File a follow-up task after this PR merges.  
**Rationale**: The `CountingFileSystem` integration tests (TC-08) provide immediate runtime regression protection. The detekt rule would provide compile-time protection for all callsites — valuable but complex to implement correctly for nested lambda scopes. Not blocking.

---

## IO Boundary Comment (Immediate — Part of Epic 1 Implementation)

Add the following comment in `GraphWriter.kt` at the `withContext(PlatformDispatcher.IO)` boundary in `savePageInternal`:

```kotlin
// IO BOUNDARY: All filesystem calls below this line run on PlatformDispatcher.IO.
// Adding any fileSystem.* call outside this withContext block will cause SAF Binder IPC
// to block a Default dispatcher thread, reintroducing the Android insert lag.
// See: project_plans/android-inserts/implementation/validation.md TC-08
// TODO: Add DirectFileSystemCallOutsideIOContext detekt rule to enforce this statically.
withContext(PlatformDispatcher.IO) {
```

---

## Test Case Summary by Type

| Type | Count | Test Cases |
|------|-------|-----------|
| Unit (JVM) | 8 | TC-01, TC-02, TC-03, TC-08, TC-11, TC-12, TC-14 + TC-04/05/06/07 (commonTest) |
| Unit (commonTest) | 4 | TC-04, TC-05, TC-06, TC-07 |
| Benchmark (JVM) | 2 | TC-09, TC-10 |
| Unit (Android / Robolectric) | 1 | TC-13 |
| **Total** | **15** | |

**Breakdown**:
- Unit tests: 12 (8 jvmTest + 4 commonTest)
- Benchmark tests: 2 (jvmTest, CI-enforced)
- Android unit tests: 1 (androidUnitTest, Robolectric)

---

## Requirements Coverage Summary

**8/8 requirements covered** (6 functional + 2 non-functional):

| Requirement | Covered by | # Test Cases |
|-------------|-----------|-------------|
| FR-1: Diagnose bottleneck | TC-01 | 1 |
| FR-2: Fix bottleneck | TC-01–08, TC-11, TC-13, TC-14 | 12 |
| FR-3: JVM SAF-latency benchmark | TC-09, TC-12 | 2 |
| FR-4: Real Android benchmark (local-only) | TC-13 (partial) | 1 |
| FR-5: Performance budget enforcement | TC-09, TC-10 | 2 |
| NFR-1: No JVM regression | TC-10 | 1 |
| NFR-2: Data integrity | TC-11 | 1 |
| NFR-3: ciCheck passes | All jvmTest + androidUnitTest | 15 |

---

## File Map

| Test File | Source Set | Test Cases |
|-----------|-----------|-----------|
| `kmp/src/jvmTest/.../db/CountingFileSystem.kt` | jvmTest | Shared test double (TC-08, TC-11, TC-14) |
| `kmp/src/jvmTest/.../db/GraphWriterDispatcherTest.kt` | jvmTest | TC-01, TC-02, TC-03 |
| `kmp/src/jvmTest/.../db/FileSystemCallCountTest.kt` | jvmTest | TC-08, TC-14 |
| `kmp/src/jvmTest/.../db/GraphWriterIntegrityTest.kt` | jvmTest | TC-11 |
| `kmp/src/commonTest/.../ui/state/BlockStateManagerTest.kt` | commonTest (extend existing) | TC-04, TC-05, TC-06, TC-07 |
| `kmp/src/jvmTest/.../benchmark/LatencyShimFileSystem.kt` | jvmTest | Infrastructure for TC-09, TC-10 |
| `kmp/src/jvmTest/.../benchmark/LatencyShimFileSystemTest.kt` | jvmTest | TC-12 |
| `kmp/src/jvmTest/.../benchmark/BlockInsertBenchmarkTest.kt` | jvmTest | TC-09, TC-10 |
| `kmp/src/androidUnitTest/.../db/AndroidDriverWalTest.kt` | androidUnitTest | TC-13 |
