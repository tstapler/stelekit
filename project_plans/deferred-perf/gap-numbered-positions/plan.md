# Gap-Numbered Block Positions — Implementation Plan

## 1. Requirements

### What "done" looks like

- `splitBlock`, `outdentBlock`, `indentBlock`, `mergeBlocks`, and `moveBlock` no longer issue `O(siblings)` UPDATE statements in the common case.
- Inserting a block between two siblings with a gap ≥ 2 issues **one** position UPDATE (the new block's own INSERT with the midpoint position). Zero sibling shifts.
- All existing functional behaviour is preserved: blocks render in correct order, `left_uuid` chain is maintained, tests pass.
- A migration re-numbers all existing blocks from 0-based sequential integers to gap-spaced positions (multiples of 1000, ordered by the `left_uuid` chain, not raw `position`).
- The parser (`MarkdownPageParser`) assigns gap-spaced positions to newly parsed blocks.

### Success criteria

1. `splitBlock` on a page with 100 bullets fires exactly **1** INSERT and **0** `updateBlockPositionOnly` calls when the gap between predecessor and successor is ≥ 2.
2. `splitBlock` falls back to a full sibling re-number (at ×1000 spacing) only when the gap is exhausted (gap < 2).
3. `outdentBlock`, `indentBlock`, `mergeBlocks`, and `moveBlock` follow the same midpoint rule.
4. `MarkdownPageParser.processParsedBlocks` and `createStubBlocks` assign `position = (index + 1) * 1000` (1000, 2000, …).
5. The migration successfully re-numbers all existing blocks without corrupting the `left_uuid` ordering.
6. All existing tests pass after the change (some assertions on exact position values must be updated).

---

## 2. Design Decisions and Risks

### 2.1 Gap midpoint computation

Given a predecessor with position `pred` and a successor with position `succ`:

```
newPosition = pred + (succ - pred) / 2
```

Corner cases:
- **First block in parent** (no predecessor): predecessor is defined as `0`. New block gets `min(1000, succ / 2)` if succ > 1, else triggers re-number.
- **Last block in parent** (no successor): new block gets `pred + 1000`.
- **Gap exhausted** (`succ - pred < 2`): gap is 0 or 1 after integer division. This triggers a full sibling re-number at 1000× spacing.

### 2.2 Re-number algorithm on gap exhaustion

When `succ - pred < 2`, re-number **all siblings under the same parent** using the `left_uuid` chain as the canonical order (not the `position` column, which may be locally stale). Steps:

1. Fetch all siblings by parent using `selectBlocksByParentUuidOrdered` (ordered by `position` — which will be consistent with `left_uuid` order in all normal states).
2. Walk the list and assign `1000, 2000, 3000, …`.
3. Issue one `updateBlockPositionOnly` per sibling.
4. Then compute `newPosition` for the new block using the now-refreshed sibling positions.

Re-numbering is O(siblings) — the same complexity as the current code, but occurs only when the gap is exhausted. In practice, 1000× spacing means a page with 100 bullets can receive ~1000 interleaved inserts before any re-number is needed.

### 2.3 `left_uuid` remains the source of truth

`position` is a secondary index used solely for `ORDER BY position` queries. It must always be consistent with the `left_uuid` chain, but `left_uuid` wins in any ambiguity. The migration re-numbers by traversing the `left_uuid` chain (which MarkdownPageParser populates via `previousSiblingUuid`). If `left_uuid` is inconsistent (data corruption), the migration falls back to ordering by current `position`.

### 2.4 Initial position for the first block on a new page

The very first block under any parent gets `position = 1000`. Subsequent appended blocks get `lastChild.position + 1000`.

### 2.5 `Int` vs `Long` boundary

The `Block` domain model stores `position: Int`. With gap-numbered values up to ~(2^31 - 1) ≈ 2.1 billion, this fits comfortably in `Int` for typical use (billions of insertions would be needed to overflow). The SQL column is `INTEGER` (Long). No type change is required.

### 2.6 Interaction with `moveBlockUp` / `moveBlockDown`

`moveBlockUp` and `moveBlockDown` swap positions between two adjacent siblings. Under gap-numbering, this still works — they swap the `position` values of two existing blocks, which does not create a gap problem. No change needed for these two methods.

### 2.7 Risk: migration correctness on databases with missing `left_uuid`

Some early databases may have `left_uuid = NULL` for all blocks (left_uuid was added in migration). In that case, the migration must fall back to ordering by `position`. The migration should detect this per-parent and handle gracefully.

### 2.8 Risk: existing tests with exact position assertions

Several tests assert `position == 0`, `position == 1`, etc. (e.g., `BlockOperationsEdgeCaseTest`, `SqlDelightBlockRepositoryWarmPathTest`, inline fixtures). These will break and must be updated to use `position == 1000`, `position == 2000`, etc., or switch to relative ordering assertions (`blocks[0].position < blocks[1].position`).

---

## 3. Migration / Rollout Strategy

### Migration name: `gap_number_block_positions`

Add as the last entry in `MigrationRunner.all` in `MigrationRunner.kt`.

**What the migration does:**

For each distinct `(page_uuid, parent_uuid)` group of siblings:
1. Load all siblings in `left_uuid` chain order. Since the migration runs in SQL, it must use a pragmatic approach: load siblings ordered by `position` (which was set by the `left_uuid` chain during parsing) and re-assign positions as 1000, 2000, 3000, …
2. Issue `UPDATE blocks SET position = ? WHERE uuid = ?` for each block.

This is pure SQL within a migration statement and cannot use the `left_uuid` chain traversal directly in SQL without a recursive CTE. Instead:

```sql
-- Step 1: Collect all (page_uuid, parent_uuid) groups
-- Step 2: For each group, re-number blocks by current position order
```

Since migrations run arbitrary SQL via `driver.execute`, the implementation uses a Kotlin loop in a new helper function called from a `Migration` with a special marker — but the migration framework only supports SQL strings. The workaround is to add a **new dedicated migration helper** invoked from `DriverFactory` after `applyAll`, similar to how `UuidMigration` was handled historically.

**Better approach:** add a one-time `DataMigration` (a separate sealed type) to `MigrationRunner` that runs Kotlin code, identified by name in `schema_migrations` like regular SQL migrations. This is consistent with the existing pattern and avoids raw SQL window functions (which SQLite < 3.25 on Android API 26 doesn't support).

The data migration Kotlin pseudocode:

```kotlin
// For each unique (page_uuid, parent_uuid) pair:
//   SELECT uuid FROM blocks WHERE page_uuid = ? AND parent_uuid IS (?) ORDER BY position
//   Assign position = (index + 1) * 1000
//   UPDATE blocks SET position = ? WHERE uuid = ?
// Run in a single transaction per parent group to bound write-lock hold time.
```

This re-numbers all blocks exactly once on the first startup after upgrade.

---

## 4. Test Plan

All new tests go in **`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/GapNumberedPositionTest.kt`** unless noted.

### 4.1 Unit tests — `SqlDelightBlockRepository` gap midpoint

**`splitBlock_should_notUpdateSiblings_when_gapIsAvailable`**
- Setup: page with blocks at positions 1000, 2000, 3000. Split block at position 1000.
- Assert: new block position = 1500. Blocks at 2000 and 3000 are unchanged (no sibling shift).
- Assert: `selectBlocksByParentUuidOrdered` returns 4 blocks in correct order.

**`splitBlock_should_renumberSiblings_when_gapExhausted`**
- Setup: page with blocks at positions 1000, 1001 (gap = 1). Split block at position 1000.
- Assert: all siblings are re-numbered to multiples of 1000. New block inserted at midpoint of re-numbered positions.
- Assert: ordering is preserved.

**`outdentBlock_should_assignGapMidpoint_not_shiftSiblings`**
- Setup: parent block at position 1000, another sibling at position 2000. Child block outdented to become sibling between them.
- Assert: outdented block gets position 1500. Siblings at 1000 and 2000 are unchanged.
- Assert: no `updateBlockPositionOnly` calls to either sibling.

**`indentBlock_should_appendWithGapSpacing`**
- Setup: parent block with last child at position 3000. Indent a sibling into the parent.
- Assert: newly indented block gets position 4000 (lastChild.position + 1000).

**`gapNumberedPosition_should_produceStableOrder_after_100_inserts`**
- Setup: page with 2 root blocks at 1000, 2000.
- Action: insert 100 blocks between them (simulating Enter at line 1 repeatedly).
- Assert: at each step, blocks are in correct order by position.
- Assert: re-numbering occurs only when gap is exhausted (not on every insert).
- Assert: total `updateBlockPositionOnly` calls over 100 inserts << 100 × (total siblings).

**`mergeBlocks_should_appendChildrenWithGapSpacing`**
- Setup: block A with last child at position 5000. Block B has 3 children.
- Merge A←B. Assert B's children are reparented to A with positions 6000, 7000, 8000.

**`moveBlock_should_assignGapMidpoint`**
- Setup: siblings at 1000, 2000, 3000. Move block from position 3000 to between 1000 and 2000.
- Assert: moved block gets position 1500. Siblings at 1000 and 2000 are unchanged.

### 4.2 Migration test

**`migration_should_renumberExistingBlocksToGapPositions`**
Location: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/GapNumberedPositionMigrationTest.kt`

- Setup: create a minimal SQLite DB with blocks at positions 0, 1, 2, 3 (old sequential numbering) under a single parent.
- Run `MigrationRunner.applyAll`.
- Assert: blocks now have positions 1000, 2000, 3000, 4000.
- Assert: ordering by `position` matches the original ordering by `position` (i.e., the same block is first/last).

### 4.3 Property-based test (ordering invariant)

**`ordering_should_bePreserved_after_anySequenceOf_split_indent_outdent`**
- Use a fixed random seed to generate 50 random operations (split/indent/outdent/merge) on a page starting with 5 blocks.
- After each operation: assert that the `left_uuid` chain order and the `position`-sorted order are identical.
- This is the structural invariant: positions must never disagree with left_uuid links.

### 4.4 Tests that will break and need updating

The following tests contain exact position assertions that assume 0-based sequential positions and must be updated:

| File | Assertion to update |
|------|---------------------|
| `BlockOperationsEdgeCaseTest.kt` (commonTest) | `assertEquals(0, rootBlocks[0].position)`, `assertEquals(1, rootBlocks[1].position)` (lines 173–175, 199–201) — change to `assertEquals(1000, ...)`, `assertEquals(2000, ...)` or switch to ordering check |
| `SqlDelightBlockRepositoryWarmPathTest.kt` (jvmTest) | `position = 0` in `Block` fixture — change to `position = 1000` |
| `DatalogBlockRepositoryTest.kt` (commonTest) | Any exact position assertions |
| `OutlinerMonkeyTest.kt` (jvmTest) | Any exact position assertions — likely uses relative ordering, verify |
| `BlocksFtsTriggerTest.kt` (jvmTest) | Block construction with `position = 0` — update to `position = 1000` |

---

## 5. File-by-File Change List

### 5.1 `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

**No schema change required.** `position INTEGER NOT NULL` already accommodates gap-numbered values. Existing queries (`selectBlocksByParentUuidOrdered`, `selectRootBlocksByPageUuidOrdered`, `selectLastChild`, `updateBlockPositionOnly`) are unchanged.

### 5.2 `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

#### `splitBlock` (lines 957–1028)

Replace lines 979–992 (the sibling-shift loop and `newPosition` calculation):

```kotlin
// BEFORE (lines 979–992):
val newPosition = block.position + 1L
val siblings = if (block.parent_uuid == null) {
    queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
} else {
    queries.selectBlocksByParentUuidOrdered(block.parent_uuid).executeAsList()
}
siblings.forEach { sibling ->
    if (sibling.position >= newPosition) {
        queries.updateBlockPositionOnly(sibling.position + 1L, sibling.uuid)
    }
}

// AFTER:
val siblings = if (block.parent_uuid == null) {
    queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
} else {
    queries.selectBlocksByParentUuidOrdered(block.parent_uuid).executeAsList()
}
val blockIndex = siblings.indexOfFirst { it.uuid == block.uuid }
val predecessor = siblings.getOrNull(blockIndex)     // the block itself (will be predecessor after split)
val successor   = siblings.getOrNull(blockIndex + 1) // the block currently after it

val newPosition = computeGapPosition(
    predPosition = predecessor?.position ?: 0L,
    succPosition = successor?.position,
    siblings = siblings,
    excludeUuid = null,
)
```

Add the private helper `computeGapPosition` (see §5.7 below).

Remove the shifted-sibling loop entirely. The `newPosition` line at 1004 stays as-is (it now holds the midpoint value).

#### `outdentBlock` (lines 761–818)

Replace lines 783–796 (the `newPosition` calculation and `siblingsToShift` loop):

```kotlin
// BEFORE (lines 783–796):
val newLeftUuid = currentParent.uuid
val newPosition = currentParent.position + 1L
val newLevel = block.level - 1L

val siblingsToShift = if (grandParentUuid == null) {
    queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
} else {
    queries.selectBlocksByParentUuidOrdered(grandParentUuid).executeAsList()
}
siblingsToShift.forEach { sibling ->
    if (sibling.position >= newPosition) {
        queries.updateBlockPositionOnly(sibling.position + 1L, sibling.uuid)
    }
}

// AFTER:
val newLeftUuid = currentParent.uuid
val newLevel = block.level - 1L

// The block will be inserted immediately after currentParent in the grandparent's children.
val grandParentSiblings = if (grandParentUuid == null) {
    queries.selectRootBlocksByPageUuidOrdered(block.page_uuid).executeAsList()
} else {
    queries.selectBlocksByParentUuidOrdered(grandParentUuid).executeAsList()
}
val parentIndex = grandParentSiblings.indexOfFirst { it.uuid == currentParent.uuid }
val successor   = grandParentSiblings.getOrNull(parentIndex + 1)

val newPosition = computeGapPosition(
    predPosition = currentParent.position,
    succPosition = successor?.position,
    siblings = grandParentSiblings,
    excludeUuid = block.uuid,
)
```

Remove the `siblingsToShift.forEach` loop.

#### `indentBlock` (lines 720–759)

Replace line 743:

```kotlin
// BEFORE (line 743):
val newPosition = (lastChildOfNewParent?.position ?: -1L) + 1L

// AFTER:
val newPosition = (lastChildOfNewParent?.position ?: 0L) + 1000L
```

#### `mergeBlocks` (lines 904–955)

Replace lines 930–934 (the position calculation for reparented children):

```kotlin
// BEFORE (lines 930–934):
val lastChildOfA = queries.selectLastChild(blockA.uuid).executeAsOneOrNull()
val newPosition = (lastChildOfA?.position ?: -1L) + 1L
val newLeftUuid = lastChildOfA?.uuid ?: blockA.uuid

// AFTER:
val lastChildOfA = queries.selectLastChild(blockA.uuid).executeAsOneOrNull()
val newPosition = (lastChildOfA?.position ?: 0L) + 1000L
val newLeftUuid = lastChildOfA?.uuid ?: blockA.uuid
```

Note: `mergeBlocks` iterates over `childrenOfB` and appends each one in a loop, re-querying `selectLastChild` each iteration. After this change, each child appended gets `lastChild.position + 1000` (gap-spaced sequential appends). This is correct — no midpoint needed when appending at the end.

#### `moveBlock` (lines 654–718)

Replace line 699 (`updateBlockHierarchy` call with `newPosition.toLong()`). The method currently computes `newPosition` as an index-based integer. Replace the `newPosition` calculation:

```kotlin
// BEFORE (lines 684–705):
val newLeftUuid = if (newPosition <= 0 || otherSiblings.isEmpty()) {
    newParentUuidResolved ?: block.page_uuid
} else {
    val prevIdx = (newPosition - 1).coerceAtMost(otherSiblings.size - 1)
    otherSiblings[prevIdx].uuid
}

// 4. Repair NEW chain
val targetBlockAtPosition = otherSiblings.getOrNull(newPosition)
if (targetBlockAtPosition != null) {
    queries.updateBlockLeftUuid(block.uuid, targetBlockAtPosition.uuid)
}

// 5. Update block hierarchy
queries.updateBlockHierarchy(
    newParentUuidResolved,
    newLeftUuid,
    newPosition.toLong(),   // ← this line
    newLevel,
    block.uuid
)

// AFTER: compute gap position for the target slot
val prevSiblingAtTarget = if (newPosition <= 0 || otherSiblings.isEmpty()) null
                          else otherSiblings.getOrNull(newPosition - 1)
val succSiblingAtTarget  = otherSiblings.getOrNull(newPosition)

val gapPosition = computeGapPosition(
    predPosition = prevSiblingAtTarget?.position ?: 0L,
    succPosition = succSiblingAtTarget?.position,
    siblings = otherSiblings,
    excludeUuid = block.uuid,
)

// newLeftUuid computation remains identical
val newLeftUuid = prevSiblingAtTarget?.uuid ?: (newParentUuidResolved ?: block.page_uuid)

// Repair new chain (unchanged)
if (succSiblingAtTarget != null) {
    queries.updateBlockLeftUuid(block.uuid, succSiblingAtTarget.uuid)
}

// Update block hierarchy with gap position
queries.updateBlockHierarchy(
    newParentUuidResolved,
    newLeftUuid,
    gapPosition,
    newLevel,
    block.uuid
)
```

### 5.3 New private helper in `SqlDelightBlockRepository.kt`

Add after the `mergeBlocks` method (around line 956), before `splitBlock`:

```kotlin
/**
 * Computes the position for a new block inserted between [predPosition] and [succPosition].
 *
 * - If there is no successor, appends at [predPosition] + 1000.
 * - If the gap between pred and succ is >= 2, returns the midpoint.
 * - If the gap is exhausted (< 2), re-numbers all [siblings] at 1000× spacing and
 *   then returns the appropriate midpoint in the new numbering.
 *
 * [excludeUuid]: UUID of the block being moved/outdented that is already counted in
 * [siblings] but should be excluded from re-numbering if it will be removed first.
 * Pass null when the moving block is not yet in the sibling list.
 */
@OptIn(DirectSqlWrite::class)
private fun computeGapPosition(
    predPosition: Long,
    succPosition: Long?,
    siblings: List<dev.stapler.stelekit.db.Blocks>,
    excludeUuid: String?,
): Long {
    if (succPosition == null) return predPosition + 1000L

    val gap = succPosition - predPosition
    if (gap >= 2L) return predPosition + gap / 2L

    // Gap exhausted — re-number all siblings at 1000× spacing, then recurse once.
    val toRenumber = siblings.filter { it.uuid != excludeUuid }
    toRenumber.forEachIndexed { i, sib ->
        val newPos = (i + 1) * 1000L
        queries.updateBlockPositionOnly(newPos, sib.uuid)
    }
    // Find the re-numbered pred and succ positions
    val predIdx = toRenumber.indexOfFirst { it.position == predPosition }
    val newPred = if (predIdx >= 0) (predIdx + 1) * 1000L else predPosition
    val succIdx = toRenumber.indexOfFirst { it.position == succPosition }
    val newSucc = if (succIdx >= 0) (succIdx + 1) * 1000L else null
    return if (newSucc == null) newPred + 1000L else newPred + (newSucc - newPred) / 2L
}
```

Note: `queries` here refers to `this.queries` (the `SteleDatabaseQueries` field). `@OptIn(DirectSqlWrite::class)` is not needed for reads; `updateBlockPositionOnly` needs to be callable from the helper. Since this method is called inside a `queries.transaction { }` block, the DirectSqlWrite annotation must be added to the calling transaction lambda, not this helper. Adjust as needed to fit the `RestrictedDatabaseQueries` pattern — the helper should use `restricted.updateBlockPositionOnly(...)` if that is how position updates are gated.

### 5.4 `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MarkdownPageParser.kt`

**Lines 157 and 214**: Change `position = index` to `position = (index + 1) * 1000` in both `processParsedBlocks` and `createStubBlocks`.

```kotlin
// BEFORE (line 157):
position = index,

// AFTER:
position = (index + 1) * 1000,
```

```kotlin
// BEFORE (line 214):
position = index,

// AFTER:
position = (index + 1) * 1000,
```

### 5.5 `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`

Add a new entry at the end of `val all: List<Migration>` (after the `wikilink_references_table` migration at line 554):

```kotlin
Migration(
    name = "gap_number_block_positions",
    statements = listOf(
        // Re-number all blocks from sequential 0-based positions to gap-numbered
        // positions (multiples of 1000), ordered by current position within each
        // (page_uuid, parent_uuid) group. This makes the common-case splitBlock/
        // outdentBlock O(1) instead of O(siblings) by eliminating sibling shifts.
        //
        // Implementation note: SQLite does not support window functions on Android < API 30
        // (SQLite < 3.25). The re-numbering is implemented as a multi-step SQL sequence:
        // we rely on the fact that position values are already consistent within groups
        // (enforced by the left_uuid chain during parsing). A pure SQL approach using a
        // correlated subquery re-numbers each block based on its rank within its group.
        //
        // rank() equivalent via correlated COUNT(*):
        // new_position = (1 + COUNT of siblings with position < this.position) * 1000
        """
        UPDATE blocks
        SET position = (
            1 + (
                SELECT COUNT(*)
                FROM blocks AS b2
                WHERE b2.page_uuid = blocks.page_uuid
                  AND (b2.parent_uuid IS blocks.parent_uuid OR (b2.parent_uuid IS NULL AND blocks.parent_uuid IS NULL))
                  AND b2.position < blocks.position
            )
        ) * 1000
        """
    )
),
```

This single UPDATE statement re-numbers every block in one pass. The correlated subquery computes the 0-based rank of each block within its `(page_uuid, parent_uuid)` group and multiplies by 1000. `parent_uuid IS NULL` comparison is used instead of `=` because `NULL = NULL` is false in SQL.

**Performance note**: On a 50,000-block graph this runs as O(N²) due to the correlated subquery. Acceptable as a one-time migration. An index on `(page_uuid, parent_uuid, position)` (which `idx_blocks_parent_position` covers) makes each correlated scan an index range scan rather than a full table scan, keeping total wall time under a few seconds on typical graphs. Add an `ANALYZE blocks` after the UPDATE to refresh statistics:

```kotlin
statements = listOf(
    """
    UPDATE blocks
    SET position = (
        1 + (
            SELECT COUNT(*)
            FROM blocks AS b2
            WHERE b2.page_uuid = blocks.page_uuid
              AND (b2.parent_uuid IS blocks.parent_uuid OR (b2.parent_uuid IS NULL AND blocks.parent_uuid IS NULL))
              AND b2.position < blocks.position
        )
    ) * 1000
    """,
    "ANALYZE blocks"
)
```

### 5.6 `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt`

Update the in-memory `splitBlock` and `outdentBlock` implementations to match the gap-numbering contract. Tests use `DatalogBlockRepository`, not `InMemoryBlockRepository` directly, but the in-memory implementation should stay consistent.

- `splitBlock` (line 423): change `val newPosition = block.position + 1` to use midpoint or append logic.
- `outdentBlock` (line 313): change `val newPosition = (parentInGrandchildren?.position ?: -1) + 1` to midpoint.
- `indentBlock` (line 289): change `(prevSiblingChildren.maxOfOrNull { it.position } ?: -1) + 1` to `+ 1000`.
- `mergeBlocks` (line 383): change `(lastChildOfA?.position ?: -1) + 1` to `+ 1000`.

Note: The in-memory implementations may not need the full exhaustion/re-number logic since they are only used in tests. A simple implementation that always uses `pred + 1000` for append and midpoint for insertion is sufficient for test correctness.

### 5.7 New test file

**`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/repository/GapNumberedPositionTest.kt`**

Contains the 7 test cases described in §4.1 plus the property-based test (§4.3). Uses `SqlDelightBlockRepository` backed by an in-memory SQLite database (same pattern as `SqlDelightBlockRepositoryWarmPathTest`).

**`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/migration/GapNumberedPositionMigrationTest.kt`**

Contains the migration test described in §4.2. Uses `MigrationRunnerApplyAllTest`-style setup.

---

## 6. Hard Question Answers

### When gap is exhausted, how does re-number decide spacing?

Full sibling re-number at **1000× spacing** (1000, 2000, 3000, …). This is the same spacing used for fresh pages, restoring maximum runway. After re-numbering, the new block is inserted at the midpoint of the now-refreshed predecessor and successor positions.

### Does `left_uuid` remain the source of truth?

**Yes.** `left_uuid` is never modified by the gap-numbering change. Gap-numbered positions are a performance optimization over the previous sequential positions. The `left_uuid` chain determines the logical ordering; `position` must agree with it at all times. The migration re-numbers blocks in `position` order (which is already consistent with `left_uuid` from the parser). After migration, all insert/outdent/indent operations maintain `left_uuid` correctness first and set `position` to the gap midpoint as a secondary index.

### What position does the first block on a new page get?

**1000.** This is consistent with `position = (index + 1) * 1000` where `index = 0`.

### What existing tests might break?

See §4.4. The primary changes needed are:
1. `BlockOperationsEdgeCaseTest.kt`: `assertEquals(0, position)` → `assertEquals(1000, position)` (lines 173–175, 199–201). Alternatively, switch to `assertTrue(blocks[0].position < blocks[1].position)` which is position-scheme-agnostic.
2. Any test that constructs a `Block` with `position = 0` as a "first block" and later checks sibling positions by exact value.
3. The `OutlinerMonkeyTest` should be unaffected since it likely checks ordering invariants, not exact position values — verify before release.

---

## 7. Implementation Order

1. Add `computeGapPosition` helper to `SqlDelightBlockRepository`.
2. Update `MarkdownPageParser` (2 lines).
3. Update `splitBlock` in `SqlDelightBlockRepository`.
4. Update `outdentBlock` in `SqlDelightBlockRepository`.
5. Update `indentBlock` in `SqlDelightBlockRepository`.
6. Update `mergeBlocks` in `SqlDelightBlockRepository`.
7. Update `moveBlock` in `SqlDelightBlockRepository`.
8. Update `InMemoryRepositories` (append positions only — not full midpoint logic needed for tests).
9. Add `gap_number_block_positions` to `MigrationRunner.all`.
10. Write new test files (`GapNumberedPositionTest.kt`, `GapNumberedPositionMigrationTest.kt`).
11. Fix broken exact-position assertions in existing tests.
12. Run `./gradlew jvmTest` and verify all tests pass.
