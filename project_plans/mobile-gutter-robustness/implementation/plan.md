# Implementation Plan: mobile-gutter-robustness

**Feature**: Bring mobile gutter touch targets to spec and add a view-mode quick-action sheet
**Date**: 2026-11-23
**Status**: Ready for implementation
**ADRs**: ADR-001-quick-action-sheet-placement.md, ADR-002-gesture-conflict-resolution.md

---

## Domain Glossary

| Term | Definition |
|---|---|
| **BlockGutter** | The left-side strip of a block row (`BlockGutter.kt`): drag handle, collapse caret, bullet dot |
| **DragHandle** | The `=` icon — 48 dp hit area, long-press starts reorder drag on mobile |
| **CollapseCaret** | The `▶`/`▼` icon — rendered only when `hasChildren == true`; currently 18 dp on all platforms |
| **BulletDot** | The `•` circle — the entry point for lasso-select drag; currently 6 dp on all platforms |
| **QuickActionSheet** | The `ModalBottomSheet` opened by a tap on the drag handle in view mode on mobile |
| **ViewMode** | A block row where `isEditing == false`; the mode where the sheet should appear |
| **EditMode** | A block row where `isEditing == true`; the sheet must NOT appear |
| **GutterMenuHintSeen** | The `PlatformSettings` boolean flag `"gutter_menu_hint_shown"` — written `true` after the first-open tooltip fires once per install |
| **CanMoveUp** | Derived boolean: `leftUuid == null && index == 0` → `false` |
| **CanMoveDown** | Derived boolean: block is last sibling → `false` |
| **CanIndent** | Derived boolean: block is first sibling (index ≤ 0) → `false` |
| **CanOutdent** | Derived boolean: `parentUuid == null` → `false` |
| **BlockRef** | Logseq `((uuid))` syntax that embeds a block by UUID |
| **HapticOnDragStart** | `HapticFeedbackType.LongPress` fired at the moment a reorder drag begins |
| **HapticOnPositionChange** | `HapticFeedbackType.TextHandleMove` fired each time the drag drop-target changes row |
| **TouchTarget48dp** | The 48 × 48 dp minimum interactive region mandated by ux.md criterion 19 |
| **isMobile** | `LocalWindowSizeClass.current.isMobile` — true for `COMPACT` and `MEDIUM` |
| **UndoSnackbar** | 5-second undo Snackbar shown after a destructive delete so the user can undo |
| **BlockRefCopied** | "Copied" toast shown after copying `((uuid))` to the clipboard |

---

## Pattern Decisions

| Decision | Chosen | Rejected alternatives | Rationale |
|---|---|---|---|
| Quick-action sheet state location | Local `var showQuickActionSheet by remember { mutableStateOf(false) }` inside `BlockItem` | (A) Lift to `BlockList`; (B) Lift to screen ViewModel | Matches `suggestionState`/`contextMenuState` pattern already in `BlockItem`. Lifting to `BlockList` causes every row to recompose when any row opens/closes a sheet. ViewModel lift adds unnecessary state to a UI-only concern. |
| Gesture disambiguation | Mode-conditional modifier composition (view mode → `clickable`; drag mode → `dragGesture`) | (A) `combinedClickable` + drag stacking; (B) separate `pointerInput` with `awaitFirstDown` | Mode-conditional is simpler and avoids the documented risk of `combinedClickable` consuming the tap before the drag detector sees it. `combinedClickable` requires the tap to be declared before `dragGesture` in the chain and silently breaks if order is wrong. |
| Touch-target expansion | 48 dp outer `Box` with clickable on the outer Box; `isMobile` gate controls whether the extra 30 dp is applied | (A) `minimumInteractiveComponentSize`; (B) padding-only | `minimumInteractiveComponentSize` only enforces for Material components. Padding-only approach creates a larger layout footprint that affects row height. The drag handle already uses the outer-Box pattern — expanding the caret and bullet follows the same idiom. |
| CanX boundary booleans source | Computed inside `BlockList` from sibling list and passed to `BlockItem` / `BlockGutter` as plain `Boolean` params | (A) Computed in ViewModel as derived `StateFlow`; (B) computed inside `BlockGutter` with its own sibling query | ViewModel computation requires new coroutine machinery for every block row on every recompose. Inside `BlockGutter` requires passing the sibling list down three layers. `BlockList` already has `childrenByParent` and `blocksWithChildren`; it can derive `isFirstSibling`/`isLastSibling` per block at layout time cheaply. |
| New ViewModel functions (delete with undo, copyBlockRef) | `deleteBlockWithUndo` on ViewModel; `copyBlockRef` as a composable-level lambda (screen → `BlockList` → `BlockItem`) | (A) Both in ViewModel; (B) `BlockStateManager` additions | Delete requires undo Snackbar coordination — ViewModel responsibility. `copyBlockRef` uses `ClipboardManager` which is a Compose composition local; it cannot be injected into a ViewModel. All existing clipboard writes in this codebase are composable-level (`ScreenRouter.kt:379`, `BlockList.kt:304`) — same pattern here. |
| Discoverability tooltip | One-shot `PlainTooltip` keyed on `PlatformSettings` boolean `"gutter_menu_hint_shown"` | (A) `CoachMark` overlay; (B) no tooltip | Coach mark would require new infrastructure. No tooltip leaves the feature entirely undiscoverable on first use. Plain tooltip is the lightest one-shot pattern already seen in the codebase. |

---

## Tech Debt Disposition

**`BlockItem.kt` (673 lines) — extend-as-is**

`BlockItem` is a recognized God Object. The architectural research explicitly recommends extend-as-is over refactor-first for this feature:

- The refactor risk outweighs the benefit here: `BlockItem` has a complex 7-`LaunchedEffect` chain, multiple render paths (edit/view/query/loading), and existing Roborazzi screenshot goldens that lock its visual contract. A structural decomposition in the same PR adds surface area, risks de-stabilizing those effects, and would make the PR harder to review.
- The new state (`showQuickActionSheet`, `showGutterMenuHint`) fits cleanly alongside existing block-local state (`suggestionState`, `contextMenuState`). The new `LaunchedEffect` guards (dismiss on selection-mode, dismiss on drag) are small additions.
- Tech debt is tracked: the God Object status of `BlockItem` is a pre-existing condition in the project backlog. It should be addressed in a dedicated refactor PR once this feature lands and its test coverage is in place.

---

## Migration Plan

N/A — no schema changes.

---

## Observability Plan

- **Logging**: The quick-action sheet open/close uses existing `Logger` calls at `DEBUG` level in `BlockItem`, matching the pattern for other UI state transitions.
- **Clipboard operations**: `copyBlockRef` logs a `DEBUG` event with the block UUID (no content).
- **Delete with undo**: `deleteBlockWithUndo` logs `INFO` level with UUID; undo fires `INFO` "Block delete undone".
- **No new telemetry events** are required: gutter interactions are UI-layer only and do not cross a repository boundary that would warrant a new telemetry counter.

---

## Risk Control

| Risk | Severity | Mitigation |
|---|---|---|
| `.clickable` placed *after* `dragGesture` in modifier chain — drag consumes the tap | HIGH | In `BlockGutter`, the tap `clickable` modifier is attached to the outer `Box` *before* the `dragGesture` modifier in the chain. `BlockGutterGestureTest` verifies both tap-to-menu and long-press-to-drag are distinguishable. |
| Stale-closure bug in new `pointerInput` keyed on wrong key | HIGH | Key the new view-mode `clickable` on `isEditing` and `isMobile` so it recomposes when mode changes. Use `rememberUpdatedState` for `showQuickActionSheet` setter inside any `pointerInput` block. |
| Roborazzi golden breaks on desktop from caret/bullet size change | HIGH | The 48 dp `Box` for caret and 24 dp `Box` for bullet are both gated by `isMobile`. Desktop renders at the original 18 dp/6 dp. The `Box` padding is `external` to the `size()` call (padding before size). `BlockGutterDesktopLayoutTest` (jvmTest) pins desktop behaviour. |
| Sheet open during drag — drag handler never fires `onDragEnd` | MEDIUM | `isDragging` is hoisted into `BlockItem` by wrapping the `onDragStart`/`onDragEnd` callbacks (Story 4.2.2). `LaunchedEffect(isDragging) { if (isDragging) showQuickActionSheet = false }` in `BlockItem` dismisses the sheet when a drag begins. |
| Selection-mode flip while sheet is open | MEDIUM | `LaunchedEffect(isInSelectionMode) { if (isInSelectionMode) showQuickActionSheet = false }` dismisses. |
| `ModalBottomSheet` desktop behaviour — partial expand state | LOW | `skipPartiallyExpanded = true` in `rememberModalBottomSheetState`. Feature is `isMobile`-gated so it won't appear on desktop in production, but the guard prevents any test-environment accident. |
| Read-only page exposes destructive Delete | MEDIUM | The sheet is only opened when `onDeleteBlock != null` (screens that pass a no-op lambda skip the Delete item). Until a formal `isPageReadOnly` flag exists, screen-level lambda injection controls availability. |
| `deleteBlock` with children silently drops children | MEDIUM | `deleteBlockWithUndo` calls `blockRepository.deleteBlock(blockUuid, deleteChildren = true)`. Undo restores via a snapshot saved before deletion. If Undo is invoked, snapshot is replayed to `blockRepository.saveBlocks(snapshot)`. |

---

## Unresolved Questions

1. **Formal `isPageReadOnly` flag**: There is no current `isPageReadOnly` boolean passed to `BlockList`. Read-only suppression for the quick-action sheet is handled by injecting a null `onDeleteBlock` lambda from the screen. If a formal read-only page concept is added in a later epic, the sheet suppression should be refactored to use that flag. Owner decision: defer to that later epic.
2. **iOS haptic API**: This plan adds `HapticFeedbackType.LongPress` and `TextHandleMove` via `LocalHapticFeedback`. On iOS, Compose's `HapticFeedbackType` maps to the system haptic engine but with different granularity than Android. The UX research noted this as out of scope; a follow-on can tune iOS patterns.
3. **Undo snapshot depth**: `deleteBlockWithUndo` captures the deleted block + its children as a snapshot before deletion and stores it in a local `remember`-keyed slot in the screen scope. This is a *single-level* undo, not integrated with any future undo history stack. Owner confirmed this is acceptable for the first version.

---

## Dependency Visualization

```
Phase 1 (Foundation — no UI risk)
  Epic 1.1: ViewModel new actions
    Story 1.1.1: deleteBlockWithUndo       ← no UI deps
    Story 1.1.2: copyBlockRef             ← no UI deps

Phase 2 (BlockGutter touch target fixes — highest Roborazzi risk first)
  Epic 2.1: Collapse caret 48 dp
    Story 2.1.1: isMobile-gated caret Box
    Story 2.1.2: AC-1 Robolectric test
  Epic 2.2: Bullet 24 dp
    Story 2.2.1: isMobile-gated bullet Box
    Story 2.2.2: AC-2 Robolectric test
  Epic 2.3: Desktop regression test
    Story 2.3.1: BlockGutterDesktopLayoutTest (jvmTest)
  Epic 2.4: Roborazzi golden update
    Story 2.4.1: Record updated goldens (after 2.1 + 2.2 land)

Phase 3 (BlockList boundary booleans — feeds Phase 4)
  Epic 3.1: CanX booleans in BlockList
    Story 3.1.1: Compute canMoveUp/canMoveDown/canIndent/canOutdent per block
    Story 3.1.2: Thread canX through BlockItem → BlockGutter sig

Phase 4 (Quick-action sheet — depends on Phase 1 + 3)
  Epic 4.1: BlockGutterActionSheet composable
    Story 4.1.1: BlockGutterActionSheet.kt file
  Epic 4.2: Gesture wiring in BlockGutter
    Story 4.2.1: Tap-to-open in view mode
    Story 4.2.2: Dismiss guards (drag, selection mode)
  Epic 4.3: AC-3/AC-4/AC-5 tests
    Story 4.3.1: Robolectric AC-3 (mobile tap → sheet → action)
    Story 4.3.2: JVM AC-4 (desktop tap — no sheet)
    Story 4.3.3: BlockItemGestureTest AC-5 (long-press still drags)

Phase 5 (Haptic feedback — depends on Phase 2 gesture wiring existing)
  Epic 5.1: Haptic on drag start + position change
    Story 5.1.1: HapticFeedbackType.LongPress on drag start
    Story 5.1.2: HapticFeedbackType.TextHandleMove on drop-target change
    Story 5.1.3: AC-6 test

Phase 6 (Discoverability — independent)
  Epic 6.1: One-shot tooltip
    Story 6.1.1: GutterMenuHintStore (PlatformSettings wrapper)
    Story 6.1.2: Tooltip in BlockGutter
```

---

## Phase 1: ViewModel New Actions

### Epic 1.1: Extend StelekitViewModel for quick-action callbacks

Adds the two ViewModel functions that do not yet exist: `deleteBlockWithUndo` (block + children, with 5-second Snackbar undo) and `copyBlockRef` (clipboard write of `((uuid))`). These are pure ViewModel additions — no UI changes — and can land independently.

#### Story 1.1.1: `deleteBlockWithUndo` ViewModel function

**Cost class**: S  
**Files**: `StelekitViewModel.kt` (1 new function), screen wiring in `PageView.kt` + `JournalsView.kt`

**What to build**:
- Add `fun deleteBlockWithUndo(blockUuid: String)` to `StelekitViewModel`.
  - Fetches the block and its entire subtree snapshot before deletion (via `blockRepository.getBlocksForPage` + filter by uuid/parentUuid chain).
  - Calls `blockRepository.deleteBlock(BlockUuid(blockUuid), deleteChildren = true)`.
  - Emits a `UndoDeleteEvent(snapshot: List<Block>)` on a new `SharedFlow<UndoDeleteEvent>` exposed from the ViewModel.
  - Screen collects this flow and shows a `Snackbar` with "Block deleted" and "Undo" action; on "Undo" it calls `viewModel.undoDeleteBlock(snapshot)`.
- Add `fun undoDeleteBlock(snapshot: List<Block>)` that calls `blockRepository.saveBlocks(snapshot)`.
- In `PageView.kt` and `JournalsView.kt`: collect `undoDeleteEvents` in a `LaunchedEffect`, show `Snackbar` via `SnackbarHostState`, call `undoDeleteBlock` on action click.

**Given-When-Then**:
- Given: a block "Buy milk" (uuid `aaa`) with one child "2% only" (uuid `bbb`), neither being edited, `deleteBlockWithUndo("aaa")` is called.
- When: `undoDeleteBlock` is called with the snapshot.
- Then: `blockRepository.getBlockByUuid(BlockUuid("aaa"))` returns `Either.Left` (not found), and after undo it returns `Either.Right` with `content == "Buy milk"`.

#### Story 1.1.2: `copyBlockRef` composable-level callback

**Cost class**: S  
**Files**: `PageView.kt`, `JournalsView.kt`, `BlockList.kt`, `BlockItem.kt`

**Clipboard access is composable-level only.** `ClipboardManager` is a Compose composition local (`LocalClipboardManager.current`); it cannot be injected into a ViewModel without DI surgery. All existing clipboard writes in this codebase (`ScreenRouter.kt:379`, `BlockList.kt:304`, `ShareInboxPanel.kt:151`) are composable-level. This story follows the same pattern.

**What to build**:
- In `PageView.kt` and `JournalsView.kt`, declare:
  ```kotlin
  val clipboardManager = LocalClipboardManager.current
  val onCopyBlockRef = { blockUuid: String ->
      // 1. id:: property check: if the block has no id:: property, write it now.
      //    Call viewModel.ensureBlockIdProperty(blockUuid) which saves id:: = blockUuid
      //    to blockRepository before returning. (see features.md §2 — id:: is required for
      //    ((uuid)) refs to survive file round-trip in existing imported graphs.)
      viewModel.ensureBlockIdProperty(blockUuid) // suspend, runs in VM coroutine scope
      // 2. Copy the ref.
      clipboardManager.setText(AnnotatedString("(($blockUuid))"))
      // 3. Emit a toast event so the screen shows "Copied".
      viewModel.onBlockRefCopied()
  }
  ```
- Add `fun ensureBlockIdProperty(blockUuid: String)` to `StelekitViewModel` — fetches the block, writes `id::` to `block.properties["id"]` if absent, saves via `blockRepository.saveBlock(updated)`.
- Add `fun onBlockRefCopied()` to `StelekitViewModel` that emits a `Unit` on `SharedFlow<Unit> val blockRefCopiedEvents` — screen shows "Block ref copied" toast for ~2 seconds.
- `onCopyBlockRef` lambda is threaded from the screen → `BlockList` → `BlockItem`.

**Given-When-Then**:
- Given: a block with uuid `"aaaa-bbbb-cccc-dddd"` and no `id::` property.
- When: `onCopyBlockRef("aaaa-bbbb-cccc-dddd")` is invoked from the sheet.
- Then: the clipboard contains `"((aaaa-bbbb-cccc-dddd))"` AND `blockRepository.getBlockByUuid(...)?.properties?.get("id") == "aaaa-bbbb-cccc-dddd"`.

---

## Phase 2: BlockGutter Touch Target Fixes

### Epic 2.1: Collapse caret 48 dp hit area on mobile

#### Story 2.1.1: isMobile-gated caret Box

**Cost class**: S  
**Files**: `BlockGutter.kt`

**What to build**:

Replace the bare `Icon` caret at lines 156–164 with a conditional `Box`:
- Read `val isMobile = LocalWindowSizeClass.current.isMobile`.
- When `isMobile`: wrap the 18 dp `Icon` in a `Box(Modifier.size(48.dp).clickable { onToggleCollapse() })`. The `Icon` is centered inside with `contentAlignment = Alignment.Center` and carries no gesture. The `semantics { contentDescription = … }` moves to the outer `Box`.
- When `!isMobile`: keep the existing `Icon.size(18.dp).clickable` unchanged.
- The `Spacer` in the `else` branch (no children) remains as-is on desktop. On mobile, replace the `Spacer(18.dp) + Spacer(4.dp)` (22dp total) with `Spacer(Modifier.size(48.dp).padding(end = 4.dp))` (52dp total) so the total footprint matches the mobile caret Box's `Modifier.size(48.dp).padding(end = 4.dp)` — a bare `Spacer(48.dp)` would leave leaf-block rows 4dp narrower than parent rows, causing visible left-edge misalignment.

**Sizing rationale**: The `size(48.dp)` follows the drag handle pattern precisely — `padding(end = 4.dp).size(48.dp)` already in the gutter. The caret already has `padding(end = 4.dp)` in its modifier; on mobile this padding moves outside the `size(48.dp)` call.

**Given-When-Then**:
- Given: a block with two children, rendered under `WindowSizeClass.COMPACT`.
- When: the semantics tree is queried for the node with `contentDescription == "Collapse"`.
- Then: its `BoundsInRoot` width and height are both ≥ 48 dp × density.

#### Story 2.1.2: AC-1 Robolectric test

**Cost class**: S  
**Files**: New file `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/BlockGutterCaretTouchTargetTest.kt`

**What to build**:
- `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [29])`, `@GraphicsMode(NATIVE)`.
- `composeTestRule.setContent { CompositionLocalProvider(LocalWindowSizeClass provides WindowSizeClass.COMPACT) { BlockGutter(hasChildren = true, ...) } }`
- Assert `onNodeWithContentDescription("Collapse").getBoundsInRoot().width >= 48.dp` and `height >= 48.dp`.
- A second test provides `WindowSizeClass.EXPANDED` and asserts bounds `== 18.dp` (desktop unchanged).
- Register in `AllAndroidUnitTests.kt`.

---

### Epic 2.2: Bullet dot 24 dp hit area on mobile

#### Story 2.2.1: isMobile-gated bullet Box

**Cost class**: S  
**Files**: `BlockGutter.kt`

**What to build**:

The bullet `Box` at lines 178–197 is currently `Modifier.size(6.dp)`. On mobile, expand to 24 dp using the outer-Box pattern:
- When `isMobile`: the outer `Box` is `Modifier.size(24.dp)`. Inside, the 6 dp circle background `Box` remains centered at 6 dp via `Alignment.Center`. The `dragGesture` modifier moves to the outer `Box` so the draggable region is 24 dp. The `.semantics { contentDescription = "Select blocks" }` moves to the outer `Box`.
- When `!isMobile`: no change from current.
- Note: the outer Box's `padding(end = 12.dp, top = 10.dp)` remains in place (it is external spacing, not part of the hit area).

**Given-When-Then**:
- Given: a block rendered under `WindowSizeClass.COMPACT`.
- When: semantics queried for `contentDescription == "Select blocks"`.
- Then: bounds width and height both ≥ 24 dp × density.

#### Story 2.2.2: AC-2 Robolectric test

**Cost class**: S  
**Files**: New file `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/BlockGutterBulletTouchTargetTest.kt`

**What to build**: Mirror of 2.1.2 for bullet. Tests `>= 24.dp` on COMPACT, `== 6.dp` on EXPANDED.
Register in `AllAndroidUnitTests.kt`.

---

### Epic 2.3: Desktop regression test (AC-8)

#### Story 2.3.1: `BlockGutterDesktopLayoutTest` (jvmTest)

**Cost class**: S  
**Files**: New file `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/BlockGutterDesktopLayoutTest.kt`

**What to build**:
- Uses `createComposeRule()` (JVM).
- `CompositionLocalProvider(LocalWindowSizeClass provides WindowSizeClass.EXPANDED)`.
- Asserts caret bounds `== 18.dp` on EXPANDED.
- Asserts bullet bounds `== 6.dp` on EXPANDED.
- Register in `AllJvmTests.kt`.

---

### Epic 2.4: Roborazzi golden update

#### Story 2.4.1: Re-record Roborazzi goldens

**Cost class**: XS  
**Files**: Any `.png` files under `kmp/src/androidUnitTest/` that show `BlockGutter` on mobile

**What to build**:
Run `./gradlew :kmp:testDebugUnitTest -Proborazzi.test.record=true` for affected tests. Review the generated PNGs (using `Read` on each). Commit the new golden files. This story is a dependency of the PR being mergeable; it gates on stories 2.1.1 and 2.2.1 completing.

---

## Phase 3: CanX Boundary Booleans

### Epic 3.1: Thread boundary booleans through BlockList → BlockItem → BlockGutter

#### Story 3.1.1: Compute canX in BlockList

**Cost class**: S  
**Files**: `BlockList.kt`

**What to build**:

Inside `BlockList`, in the `items(blocks)` block where `BlockItem` is called, compute per-block:

```kotlin
val siblings = childrenByParent[block.parentUuid?.value] ?: blocks.filter { it.parentUuid == null }
val sortedSiblings = siblings.sortedBy { it.position }
val idx = sortedSiblings.indexOfFirst { it.uuid == block.uuid }
val canMoveUp = idx > 0
val canMoveDown = idx < sortedSiblings.size - 1
val canIndent = idx > 0
val canOutdent = block.parentUuid != null
```

Pass `canMoveUp`, `canMoveDown`, `canIndent`, `canOutdent` to `BlockItem`.

**Note**: `childrenByParent` is already computed at lines 217–220 of `BlockList.kt`. This reuses it. `sortedBy { it.position }` uses the fractional-index string ordering already relied on by drag-to-reorder.

#### Story 3.1.2: Plumb canX through BlockItem signature (BlockGutter is excluded)

**Cost class**: S  
**Files**: `BlockItem.kt`

**What to build**:
- Add four `Boolean` params to `BlockItem`: `canMoveUp: Boolean = true`, `canMoveDown: Boolean = true`, `canIndent: Boolean = true`, `canOutdent: Boolean = true`.
- **Do NOT add them to `BlockGutter`.** `BlockGutterActionSheet` is rendered inside `BlockItem`'s scope (Phase 4), not inside `BlockGutter`. Passing them through `BlockGutter` would create dead params — an ISP violation — since `BlockGutter` has no use for them (architecture review concern, 2026-11-23).
- In Phase 4 (Story 4.2.1), `BlockItem` passes them directly to `BlockGutterActionSheet`.

The existing dead `onMoveUp`/`onMoveDown` params already on `BlockGutter` are cleaned up in Story 4.2.1 (they are replaced by the new callback wiring there).

---

## Phase 4: Quick-Action Sheet

### Epic 4.1: BlockGutterActionSheet composable

#### Story 4.1.1: New `BlockGutterActionSheet.kt` file

**Cost class**: M  
**Files**: New file `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutterActionSheet.kt`

**What to build**:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlockGutterActionSheet(
    onDismiss: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canIndent: Boolean,
    canOutdent: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onCopyBlockRef: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { paneTitle = "Block actions" },
    ) {
        Column { ... }
        // Items: Indent (canIndent), Outdent (canOutdent), Move Up (canMoveUp),
        //        Move Down (canMoveDown), Copy Block Ref (always enabled),
        //        Delete (always enabled, red tint / destructive style)
        // Each item is a TextButton with enabled = canX, calls onX() then onDismiss()
    }
}
```

Accessibility requirements:
- `paneTitle = "Block actions"` on the sheet's root semantics.
- Each action button has a descriptive `contentDescription`.
- Focus restores to the trigger element on close (Compose `ModalBottomSheet` does this by default via `FocusRequester` integration if the trigger has a focusable semantics node).

**Given-When-Then for disabled state**:
- Given: a block at `idx == 0` (first sibling), so `canMoveUp = false`.
- When: `BlockGutterActionSheet` is rendered with `canMoveUp = false`.
- Then: the "Move Up" button is `enabled = false` (not clickable, visually muted).

---

### Epic 4.2: Gesture wiring in BlockGutter and BlockItem

#### Story 4.2.1: Tap-to-open in view mode (mobile only)

**Cost class**: M  
**Files**: `BlockGutter.kt`, `BlockItem.kt`

**What to build**:

In `BlockGutter`, add a callback param `onTapDragHandle: (() -> Unit)? = null`. On the drag-handle `Box`:
- When `onTapDragHandle != null` (passed only in view mode on mobile), add a `clickable { onTapDragHandle() }` modifier to the outer `Box` *before* `dragGesture` in the chain.
- The existing drag-gesture modifier chain is unchanged.

In `BlockItem`, declare `var showQuickActionSheet by remember { mutableStateOf(false) }`. Pass to `BlockGutter`:
```kotlin
onTapDragHandle = if (isMobile && !isEditing && !isInSelectionMode) {
    { showQuickActionSheet = true }
} else null
```

Render the sheet conditionally:
```kotlin
if (showQuickActionSheet) {
    BlockGutterActionSheet(
        onDismiss = { showQuickActionSheet = false },
        canMoveUp = canMoveUp, canMoveDown = canMoveDown,
        canIndent = canIndent, canOutdent = canOutdent,
        onMoveUp = { onMoveUp(); showQuickActionSheet = false },
        onMoveDown = { onMoveDown(); showQuickActionSheet = false },
        onIndent = { onIndent(); showQuickActionSheet = false },
        onOutdent = { onOutdent(); showQuickActionSheet = false },
        onCopyBlockRef = { onCopyBlockRef(); showQuickActionSheet = false },
        onDelete = { onDeleteBlock(); showQuickActionSheet = false },
    )
}
```

New params added to `BlockItem` in this story:
- `onCopyBlockRef: () -> Unit = {}` — wired by screen to `viewModel::copyBlockRef`.
- `onDeleteBlock: () -> Unit = {}` — wired by screen to `{ viewModel.deleteBlockWithUndo(block.uuid.value) }`.

New params added to `BlockList`:
- `onCopyBlockRef: (String) -> Unit = {}`, `onDeleteBlock: (String) -> Unit = {}`.

Plumbing in `PageView.kt` and `JournalsView.kt`:
- Pass `onCopyBlockRef = { uuid -> viewModel.copyBlockRef(uuid) }`.
- Pass `onDeleteBlock = { uuid -> viewModel.deleteBlockWithUndo(uuid) }`.
- Collect `viewModel.undoDeleteEvents` to show Snackbar (from Story 1.1.1).
- Collect `viewModel.blockRefCopiedEvents` to show "Copied" toast (from Story 1.1.2).

<!-- Bullet secondary tap was considered (Notion/Logseq convention) but is out of scope: requirements specify drag-handle only (AC-3), bullet tap has no AC and an untested gesture-conflict with the bullet's existing `detectDragGesturesAfterLongPress`. Promote to a named requirement with its own AC if desired in a future sprint. -->

#### Story 4.2.2: Dismiss guards

**Cost class**: S  
**Files**: `BlockItem.kt`

**What to build**:

`isDragging` is **not** passed to `BlockItem`; it is a local `var` inside `BlockGutter`. To hoist the dragging state into `BlockItem` without restructuring `BlockGutter`, wrap the existing `onDragStart`/`onDragEnd` callbacks at the `BlockItem` call site:

```kotlin
// Declared in BlockItem, alongside showQuickActionSheet:
var isDragging by remember { mutableStateOf(false) }

// Wrapped callbacks passed to BlockGutter:
BlockGutter(
    ...
    onDragStart = { blockUuid, y ->
        isDragging = true
        externalOnDragStart(blockUuid, y) // the existing BlockItem onDragStart param
    },
    onDragEnd = { blockUuid, offset ->
        isDragging = false
        externalOnDragEnd(blockUuid, offset)
    },
)
```

Then add three `LaunchedEffect`s to `BlockItem` after `showQuickActionSheet` is declared:
```kotlin
// Sheet must close if drag starts (isDragging tracked via onDragStart/onDragEnd wrappers above)
LaunchedEffect(isDragging) { if (isDragging) showQuickActionSheet = false }
// Sheet must close if selection mode is entered while sheet is open
LaunchedEffect(isInSelectionMode) { if (isInSelectionMode) showQuickActionSheet = false }
// Sheet must close if editing starts (programmatic or keyboard-driven on narrow JVM layouts)
LaunchedEffect(isEditing) { if (isEditing) showQuickActionSheet = false }
```

---

### Epic 4.3: Acceptance Criteria tests for the sheet

#### Story 4.3.1: Robolectric AC-3 test

**Cost class**: M  
**Files**: New file `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/components/BlockGutterQuickActionSheetTest.kt`

**What to build**:
- `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [29], qualifiers = "w360dp-h780dp-xxhdpi")`, `@GraphicsMode(NATIVE)`.
- Provide `WindowSizeClass.COMPACT`.
- Render `BlockItem` with `isEditing = false`, `isInSelectionMode = false`, real callbacks recorded via `Recorder`.
- `onNodeWithContentDescription("Drag to move").performClick()` → assert sheet node with `paneTitle = "Block actions"` is visible.
- `onNodeWithText("Move Up").performClick()` → assert `recorder.moveUpCalls == 1` and sheet is gone.
- Separate test: provide `WindowSizeClass.EXPANDED` → assert no sheet appears on click.
- Register in `AllAndroidUnitTests.kt`.

#### Story 4.3.2: JVM AC-4 test

**Cost class**: S  
**Files**: `BlockItemGestureTest.kt` (extend existing) or new jvmTest file

**What to build**:
- Add a test to `BlockItemGestureTest.kt` or a new `BlockGutterDesktopSheetTest.kt` (jvmTest).
- Provide `WindowSizeClass.EXPANDED`, tap "Drag to move", assert no node with `paneTitle = "Block actions"` is found.
- Register in `AllJvmTests.kt` if new file.

#### Story 4.3.3: AC-5 long-press still drags

**Cost class**: S  
**Files**: `BlockItemGestureTest.kt` (extend existing)

**What to build**:
- Add a test: `WindowSizeClass.COMPACT`, long-press "Drag to move", assert `onDragStart` was called (not `showQuickActionSheet`).
- This pins the gesture disambiguation.

---

## Phase 5: Haptic Feedback

### Epic 5.1: Haptic on drag start and position change

#### Story 5.1.1: HapticFeedbackType.LongPress on drag start

**Cost class**: S  
**Files**: `BlockGutter.kt`

**What to build**:

In `BlockGutter`'s drag handle `Box`, the `onDragStart` lambda currently calls `isDragging = true; isHovered = false; onDragStart(blockUuid, startOffset.y)`. Add haptic before those:
```kotlin
onDragStart = { startOffset ->
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    isDragging = true
    isHovered = false
    onDragStart(blockUuid, startOffset.y)
},
```
`haptic` is obtained via `val haptic = LocalHapticFeedback.current` at the top of `BlockGutter`. `BlockItem` already imports `LocalHapticFeedback` and uses it at line 263 for selection-mode; the same import is added to `BlockGutter.kt`.

#### Story 5.1.2: HapticFeedbackType.TextHandleMove on drop-target change

**Cost class**: S  
**Files**: `BlockList.kt`

**What to build**:

`BlockList` tracks the drag drop-target via its internal `BlockDragState`. When the drop-target row changes (new `dropAbove`/`dropBelow`/`dropAsChild` row is selected), fire `HapticFeedbackType.TextHandleMove`.

Inside `refreshDropTarget`:
- Track `var lastDropTargetUuid by remember { mutableStateOf<String?>(null) }`.
- When `newDropTarget.uuid != lastDropTargetUuid`: `haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastDropTargetUuid = newDropTarget.uuid`.

#### Story 5.1.3: AC-6 test

**Cost class**: S  
**Files**: `BlockItemGestureTest.kt` (extend) or new `BlockGutterHapticTest.kt`

**What to build**:

Using the existing test infrastructure, mock `LocalHapticFeedback`:
```kotlin
val hapticRecorder = object : HapticFeedback {
    var longPressCalls = 0
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (hapticFeedbackType == HapticFeedbackType.LongPress) longPressCalls++
    }
}
CompositionLocalProvider(LocalHapticFeedback provides hapticRecorder) { ... }
```
Long-press on "Drag to move" (or simulate drag-start). Assert `hapticRecorder.longPressCalls == 1`.

---

## Phase 6: Discoverability Tooltip

### Epic 6.1: One-shot gutter-menu hint

#### Story 6.1.1: `GutterMenuHintStore`

**Cost class**: XS  
**Files**: New file `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/GutterMenuHintStore.kt`

**What to build**:

A thin wrapper around `PlatformSettings`:
```kotlin
class GutterMenuHintStore(private val settings: PlatformSettings) {
    fun hasShown(): Boolean = settings.getBoolean("gutter_menu_hint_shown", false)
    fun markShown() = settings.putBoolean("gutter_menu_hint_shown", true)
}
```

**Injection path decision (architecture review 2026-11-23):** Inject via `StelekitViewModel` constructor. The ViewModel path keeps hint state testable via a fake `GutterMenuHintStore`, allows `WiringCompletenessTest` to enforce the wiring, and is consistent with how `TaskSettingsStore` and other Settings wrappers are injected today. The CompositionLocal path is rejected: it makes the hint untestable at the ViewModel unit-test level and would require a new `CompositionLocalProvider` in every test. Add `GutterMenuHintStore` to the ViewModel's constructor and to the composition root; add a `WiringCompletenessTest` entry.

#### Story 6.1.2: One-shot tooltip in BlockGutter

**Cost class**: S  
**Files**: `BlockGutter.kt`, `BlockItem.kt`

**What to build**:

Add a param to `BlockGutter`: `showGutterMenuHint: Boolean = false`, `onGutterMenuHintShown: () -> Unit = {}`.

In view mode, mobile only, when `showGutterMenuHint == true`:
- Render a `PlainTooltip` ("Tap for block actions") anchored above the drag handle.
- After a 2-second `delay` in a `LaunchedEffect(showGutterMenuHint)`, call `onGutterMenuHintShown()`.

In `BlockItem`:
- Accept `showGutterMenuHint: Boolean = false` and `onGutterMenuHintShown: () -> Unit = {}`, pass to `BlockGutter`.

In `BlockList`:
- Accept `showGutterMenuHintForBlockUuid: String? = null` and `onGutterMenuHintShown: () -> Unit = {}`.
- Pass `showGutterMenuHint = (block.uuid.value == showGutterMenuHintForBlockUuid)` per block.

In `PageView` / `JournalsView`:
- Collect from `viewModel.gutterMenuHintBlockUuid: StateFlow<String?>` (the UUID of the first visible block on the page when the page opens for the first time, `null` after hint shown).
- `viewModel.onGutterMenuHintShown()` calls `hintStore.markShown()` and clears the state.

**Given-When-Then**:
- Given: `hintStore.hasShown() == false`, page opens, first block UUID is `"xyz"`.
- When: the page renders for 2 seconds.
- Then: `hintStore.hasShown() == true` and `viewModel.gutterMenuHintBlockUuid.value == null`.

---

## Effort Estimate

| Agent wave | Stories | Wall-clock blockers |
|---|---|---|
| Wave 1 (parallel) | 1.1.1, 1.1.2, 2.1.1, 2.3.1 | None — pure additions, no shared files |
| Wave 2 | 2.1.2, 2.2.1 | Waits on 2.1.1 (BlockGutter.kt) |
| Wave 3 | 2.2.2, 3.1.1, 3.1.2 | Waits on 2.2.1 (BlockGutter.kt); 3.1.1 reads BlockList.kt (parallel with 2.2.2) |
| Wave 4 | 4.1.1 | Waits on 3.1.2 (canX signature settled) |
| Wave 5 | 4.2.1, 4.2.2, 5.1.1, 5.1.2 | Waits on 4.1.1 (sheet composable) and 1.1.1 (delete/copyRef) |
| Wave 6 | 4.3.1, 4.3.2, 4.3.3, 5.1.3 | Waits on Wave 5 to complete |
| Wave 7 | 2.4.1 (Roborazzi golden re-record) | Waits on 2.1.1 + 2.2.1; device/emulator run |
| Wave 8 | 6.1.1, 6.1.2 | Waits on Wave 5 (BlockItem param chain settled) |

**Wall-clock blockers** (owner decisions / device runs):
- Roborazzi golden review (Wave 7): owner must open the generated PNGs and approve before merge.
- Android emulator run for Stories 4.3.1 (Robolectric) + 5.1.3: standard CI, no manual step needed.
- Owner decision on UndoSnackbar UX: confirm 5-second timeout and "Undo" label before Wave 5.

**Cost class key**:
- XS: single function/small file addition, minimal output tokens
- S: one focused composable or test file, moderate output
- M: multi-callback wiring or new composable with accessibility + guard logic
- L: (none — all stories are ≤ M; no stories required splitting)

---

## First-open screens registration

No new screens. The quick-action sheet is not a navigation destination and does not need an entry in `first-open-screens.txt`.

## Wiring completeness

`BlockGutterActionSheet` is an internal composable with no seam interface — no `WiringCompletenessTest` entry required. `GutterMenuHintStore` is a plain class, not a seam interface.

## BUILD.bazel

After adding new files:
- `kmp/src/androidUnitTest/kotlin/BUILD.bazel`: add `BlockGutterCaretTouchTargetTest`, `BlockGutterBulletTouchTargetTest`, `BlockGutterQuickActionSheetTest`.
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/BUILD.bazel` (or parent): add `BlockGutterDesktopLayoutTest` and any new `BlockGutterHapticTest`.
- `kmp/modules_manifest.bzl` / `scripts/kmp-modules-sync.py`: run after adding `BlockGutterActionSheet.kt` and `GutterMenuHintStore.kt` to assign them to their owning module.
