# Agent 1: Existing Tests Research

## Summary

Analysis of test files covering `BlockStateManager`, block split/merge/write operations,
and concurrency utilities.

---

## Key Test Files

### Primary: `BlockStateManagerTest.kt`
**Path**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/state/BlockStateManagerTest.kt`

This is the main test suite — 1676 lines, ~40 test cases. Uses `kotlinx.coroutines.test`:
- `runTest` with `UnconfinedTestDispatcher(testScheduler)`
- `advanceTimeBy`, `advanceUntilIdle`
- All tests inject a `CoroutineScope(UnconfinedTestDispatcher(testScheduler))` explicitly

**Standard setup pattern** (repeated in every test):
```kotlin
val blockRepo = InMemoryBlockRepository()
val pageRepo = InMemoryPageRepository()
val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
val manager = BlockStateManager(blockRepo, graphLoader, scope)
```

### Concurrency/Race Condition Tests Already Written

**TC-04** (line 1478): `splitBlock_optimistically_updates_blocks_and_focus_before_db_write`
- Uses `DelayedBlockRepository` (500ms delay in `splitBlock`)
- Launches `manager.splitBlock("b1", 5)` without joining
- Advances 1ms, then checks `_blocks` already has 2 entries (optimistic)
- Confirms focus already set before DB returns
- **Does NOT test race between pending content write and structural op**

**TC-05** (line 1519): `addNewBlock_optimistically_inserts_empty_block_and_focus_before_db_write`
- Same pattern with `DelayedBlockRepository`
- Tests `addNewBlock` optimistic insertion
- **Does NOT test whether a pending `applyContentChange` corrupts the new block**

**TC-06** (line 1554): `splitBlock_rolls_back_blocks_and_focus_on_db_failure`
- Uses `FailingBlockRepository` (always returns `Left` from `splitBlock`)
- Verifies rollback restores single block and cursor position

**TC-07** (line 1590): `mergeBlock_rolls_back_focus_on_db_failure`
- Uses `FailingBlockRepository` (always returns `Left` from `mergeBlocks`)
- Verifies focus restored to exact pre-merge block and cursor

### Test Doubles (lines 1631–1675)

**`DelayedBlockRepository`** (line 1638–1652):
```kotlin
@OptIn(DirectRepositoryWrite::class)
private class DelayedBlockRepository(
    val delegate: InMemoryBlockRepository,
    private val splitDelayMs: Long = 500L,
) : BlockRepository by delegate {
    @DirectRepositoryWrite
    override suspend fun splitBlock(blockUuid: String, cursorPosition: Int, newBlockUuid: String?): Either<DomainError, Block> {
        delay(splitDelayMs)
        return delegate.splitBlock(blockUuid, cursorPosition, newBlockUuid)
    }
}
```

**`FailingBlockRepository`** (line 1659–1675):
```kotlin
@OptIn(DirectRepositoryWrite::class)
private class FailingBlockRepository(val delegate: InMemoryBlockRepository) : BlockRepository by delegate {
    override suspend fun splitBlock(...): Either<DomainError, Block> =
        DomainError.DatabaseError.WriteFailed("injected splitBlock failure").left()
    override suspend fun mergeBlocks(...): Either<DomainError, Unit> =
        DomainError.DatabaseError.WriteFailed("injected mergeBlocks failure").left()
}
```

Also defined locally in the test file:
- `FakeFileSystem` (line 34)
- `CountingFakeFileSystem` (line 50) — counts `readFile` calls
- `CountingBlockRepository` (line 73) — counts `getBlocksForPage` calls
- `TrackingFileSystem` (line 437) — records `writeFile` paths

### Structural Operations Already Tested (happy path only)

| Test | Line | Notes |
|------|------|-------|
| `splitBlock_creates_two_blocks_at_cursor_position` | 1046 | Uses vanilla `InMemoryBlockRepository` |
| `splitBlock_focuses_the_new_block` | 1067 | No concurrency |
| `splitBlock_records_undo_entry_and_undo_restores_single_block` | 1091 | |
| `splitBlock_at_cursor_zero_produces_empty_first_block_and_full_content_in_second` | 1115 | |
| `mergeBlock_combines_content_with_previous_sibling_and_removes_block` | 1425 | |
| `mergeBlock_moves_focus_to_previous_block` | 1448 | |
| `indentBlock_makes_block_child_of_previous_sibling` | 1139 | |
| `outdentBlock_moves_child_to_grandparent_level` | 1182 | |
| `moveBlockUp_swaps_positions_with_previous_sibling` | 1210 | |
| `moveBlockDown_swaps_positions_with_next_sibling` | 1232 | |

### `DatabaseWriteActorTest.kt`
**Path**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DatabaseWriteActorTest.kt`

Uses `FakeBlockRepository` / `FakePageRepository` from `ui/fixtures/FakeRepositories.kt`.
Tests cover:
- Happy paths for `savePage`, `saveBlocks`, `deleteBlocksForPage`
- Failure propagation
- Batch coalescing with fallback retry
- Concurrent callers (20 simultaneous `saveBlocks`)
- Loop recovery after unexpected exception
- `executeBatch` semantics

**Key finding**: `DatabaseWriteActorTest` has no test for `execute {}` being called with a structural op (e.g., `splitBlock`) that must run after a pending `updateBlockContentOnly` for the same block.

### Other Related Tests

**`OutlinerRegressionTest.kt`**
**Path**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/OutlinerRegressionTest.kt`
Contains `BlockStateManager`-level regression tests but in the JVM UI test suite (Roborazzi).

**`BlockInsertBenchmarkTest.kt`**
**Path**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BlockInsertBenchmarkTest.kt`
Uses `DatabaseWriteActor` for load testing but not race conditions.

---

## What Is NOT Yet Tested

1. **The primary bug**: User types → `applyContentChange` calls `writeActor.updateBlockContentOnly()` (queued) → user presses Enter → `addNewBlock` / `splitBlock` calls `blockRepository.splitBlock()` directly (bypassing actor queue) → structural op executes before content write, reading stale content from DB.

2. **Sequence to reproduce**:
   - `manager.updateBlockContent("b1", "typed text", 1)` — queues content write
   - Before actor drains: `manager.splitBlock("b1", 10)` or `manager.addNewBlock("b1")` — directly calls repo, reads "original" content from DB
   - Result: split uses old content, new block appears but first block loses typed content

3. **No `DelayedContentWriteRepository`** exists that delays `updateBlockContentOnly` specifically (only `splitBlock` is delayed in `DelayedBlockRepository`).

---

## Test Utilities Available for New Tests

- `InMemoryBlockRepository` — can be used as delegate for custom doubles
- `InMemoryPageRepository`
- `FakeFileSystem`, `CountingFakeFileSystem`, `TrackingFileSystem`
- `UnconfinedTestDispatcher` + `testScheduler.advanceTimeBy()` for time control
- `CountingBlockRepository` pattern — wraps delegate with call counting
- `@OptIn(DirectRepositoryWrite::class)` needed on any class overriding repo methods

## Recommended New Test

```kotlin
@Test
fun splitBlock_after_pending_content_write_uses_latest_content() = runTest {
    // DelayedContentRepository delays updateBlockContentOnly by 500ms
    val delayedRepo = DelayedContentBlockRepository(InMemoryBlockRepository(), contentDelayMs = 500L)
    val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val actor = DatabaseWriteActor(delayedRepo, FakePageRepository())
    val manager = BlockStateManager(delayedRepo, graphLoader, scope, writeActor = actor)
    
    // User types
    manager.updateBlockContent("b1", "typed text", 1)
    // User immediately hits Enter (before actor drains)
    val splitJob = manager.splitBlock("b1", 5)
    advanceUntilIdle()
    splitJob.join()
    
    // With the fix: split sees "typed text", not "original"
    val blocks = manager.blocks.value[pageUuid]!!
    assertEquals("typed", blocks.find { it.uuid == "b1" }?.content)
}
```
