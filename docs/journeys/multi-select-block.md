---
journey_id: multi-select-block
platforms: [desktop, android, ios, web]
jtbd_tier: in-between
step_count_target: "no regression in existing step count for Copy/Cut/Delete once in selection mode; discoverability checklist: 'N selected' + Clear (✕) always present, no trapped mode — design/ux.md criterion 11"
current_step_count:
  desktop: "Selection mode entry mechanism confirmed as long-press only in the code found (see Notes) — a hardware-keyboard-only, non-pointer entry path was not confirmed. Copy/Cut/Delete/Clear all implemented and reachable once in selection mode."
  android: "Entry: 1 long-press. Toggle additional blocks: 1 tap each. Copy/Cut/Delete: 1 tap each from the selection-mode toolbar. No regression found vs. what the implemented mechanism supports."
  ios: "Same as Android — shared commonMain gesture code."
  web: "Same entry mechanism as Android if long-press maps to an equivalent pointer gesture on Web; not independently confirmed for a mouse-only Web session (see Notes)."
heuristic_findings: |
  Worst violation: discoverability. Entry into selection mode is long-press only
  (`BlockItem.kt:249-258`), with no explicit "Select" button or keyboard-only path found
  (this doc's own resolution of features.md §3's open question) — a user who cannot or
  does not know to long-press (mouse-only desktop, some assistive tech) may have no
  entry point into multi-select at all.
test_ids: []
status: audited
last_verified: 2026-07-06
post_fix_step_count:
  desktop: "RESOLVED (GAP-011, Story D.4.2): a non-gesture entry point now exists — the bottom-row 'Select blocks' button (`MobileBlockToolbar.kt`, ✓ CheckCircle icon) calls `blockStateManager.enterSelectionMode(editingBlockId)` for whichever block is currently being edited, giving a mouse-only Desktop session (or any assistive-tech user who cannot long-press) a discoverable path into selection mode. 1 tap (same cost as an equivalent Move-Up/Move-Down-style toolbar action)."
  android_ios_web: "Same new button available on touch platforms too, as an additive fallback alongside the unchanged long-press entry — not a replacement for it."
---

# Multi-select block

## Trigger
User wants to select multiple blocks to Copy/Cut/Delete them as a group, or to drag-move the group together (see `reorder-block.md`).

## CORRECTION — selection-mode machinery is substantially already implemented (same pattern as `reorder-block.md`'s correction)

`docs/tasks/multi-block-selection.md` is a pre-existing, more detailed plan (per Task A.1.3b's brief) — reading the actual `BlockStateManager.kt` code shows its core mechanisms already exist: `selectedBlockUuids: StateFlow<Set<String>>` and `isInSelectionMode: StateFlow<Boolean>` (lines 237-238), `clearSelection()` (245), `deleteSelectedBlocks()` (257), `copySelectedBlocks()`/`cutSelectedBlocks()` (304, 310, both via a shared `snapshotSelectedBlocks(ClipboardOperation)` helper), `pasteBlocks(afterBlockUuid)` (327), and `moveSelectedBlocks(newParentUuid, insertAfterUuid)` (437) with a `subtreeDedup` helper (line 246, referenced at 265/288/454) that filters out any UUID whose ancestor is also selected — this is exactly `multi-block-selection.md`'s own documented "Edge Case — Ancestor block in selected set causes double-move" mitigation, already present in code.

## Current step sequence — entry into selection mode

1. Long-press on a block (`BlockItem.kt:249-258`, `detectTapGestures(onLongPress = { ... haptic ... onEnterSelectionMode() })`, gated by `!isInSelectionMode`) is the only entry point found — bubbles through `BlockList.kt:214/77` → `PageView.kt:377` (`onEnterSelectionMode = { uuid -> blockStateManager.enterSelectionMode(BlockUuid(uuid)) }`) / `JournalsView.kt:147`.
2. **No explicit "Select" button or separate keyboard-only entry path was found** — features.md §3's own open question ("confirm during UX flow analysis whether entry is long-press or a separate explicit action") is resolved by this reading: **entry is long-press only**, a pointer/touch-dependent gesture with no confirmed keyboard-only or explicit-button alternative. This is worth flagging as a discoverability/accessibility gap in its own right (a user who cannot perform a long-press — e.g. some assistive-tech users, or a mouse-only desktop session where "long-press" maps awkwardly to "long mouse-down" — may have no path into selection mode at all).

### FIXED (GAP-011, Story D.4.2) — explicit non-gesture entry point added

`MobileBlockToolbar.kt`'s bottom (structural-actions) row now includes a "Select blocks"
`IconButton` (`Icons.Default.CheckCircle`, rendered alongside Move Up/Move Down/New Block,
whenever `editingBlockId != null`) that calls a new `onEnterSelectionMode: (String) -> Unit`
callback, wired in `EditorToolbar.kt` to `blockStateManager.enterSelectionMode(BlockUuid(blockUuid))`
for the currently-editing block — the exact same call the long-press path already makes. This is
**additive**: the long-press entry point (`BlockItem.kt:249-258`) is unchanged and still works
identically. The new button gives a mouse-only Desktop session, or any assistive-tech user who
cannot perform a long-press, a visible, discoverable path into multi-select for the first time.

## Current step sequence — acting on a selection (once in selection mode)

1. Tap toggles block membership in `selectedBlockUuids`.
2. Toolbar renders `"$selectedCount selected"` (`MobileBlockToolbar.kt:78`) plus Copy (`"Copy selected"`, line 83), Cut (`"Cut selected"`, 86), Delete (`"Delete selected"`, 89), Clear (`"Clear selection"`, 92) — all four always present together while in selection mode (`MobileBlockToolbar.kt:71-95`), matching design/ux.md criterion 11's "no trapped mode" requirement structurally (the Clear/✕ exit is rendered in the same row as the actions, not hidden behind another step).
3. Copy/Cut snapshot the selection via `snapshotSelectedBlocks` (`BlockStateManager.kt:304,310`); Delete calls `deleteSelectedBlocks()` (257); all three dispatch a single batched operation per tap, not per-block repetition.

## Known rough edges (from `docs/tasks/multi-block-selection.md`'s Known Issues — corroborated, not re-derived)

- **Concurrency Risk — Race condition in `moveSelectedBlocks` position calculation [High]**: moving N selected blocks in a loop can reorder siblings mid-loop if positions are precomputed; mitigated by using `startPosition + index` (loop counter) rather than a precomputed absolute position. Whether the current `moveSelectedBlocks` (`BlockStateManager.kt:437+`) implements this mitigation was not independently re-verified line-by-line in this pass — flagged for Phase A.2/heuristic review to confirm against the actual loop body if this journey is prioritized.
- **Data-Integrity Risk — `deleteBulk` does not repair chains for middle blocks [High]**: naive UUID-by-UUID deletion of a contiguous sibling span can orphan `left_uuid` linked-list pointers; the documented mitigation is a sequential loop re-reading the sibling chain after each deletion, in one transaction. Not independently re-verified against `deleteSelectedBlocks`'s current implementation in this pass.
- **UI Risk — `BlockList` Column recompose on every drag frame [Medium]**: `BlockList.kt` renders blocks via a plain `Column` (`blocks.forEach`, confirmed — no `LazyColumn` in this file), so any drag-state mutation (`offsetY`) recomposes the whole column — a real perf risk with 200+ blocks, corroborating this journey's own note that reorder and multi-select share files/state and should be read together (per plan.md's prior-art reuse note).
- **UX Risk — Drag gesture conflicts with scroll on mobile [Medium] — FIXED (Story D.3.1, GAP-010)**: the page's `LazyColumn` scroll and the block-level drag pointer input previously competed — same underlying concern as `reorder-block.md`'s Bug 002 (stale `blockBounds` during scroll). `BlockList`'s new `onDragStateChanged` callback now lets `JournalsView`/`PageView` set `userScrollEnabled = false` on their `LazyColumn` for the duration of any active drag, removing the competition at its root rather than reconciling positions after the fact.
- **Integration Risk — `BlockViewer` tap intercepted in selection mode [Low]**: `BlockViewer`'s `clickable { onStartEditing() }` would open the editor on tap instead of toggling selection, unless swapped for `clickable { onToggleSelect() }` when `isInSelectionMode` is true — not independently re-verified against current `BlockViewer.kt` in this pass.

## Notes
- Prior-art cross-check (`docs/ux/journey-map.md`, not merged — see README changelog): its "Multi-block selection and drag reorder" journey independently confirms long-press/Shift+Click as the entry trigger and documents the same "3 selected" + Copy/Cut/Delete/Clear toolbar shape, plus notes Shift+Up/Down extends selection on desktop (a desktop-specific extension mechanism not independently re-confirmed against current code in this pass — flagged as a follow-up check, not asserted as fact here).
- Given both this journey and `reorder-block.md` share the exact same underlying files (`BlockGutter.kt`, `BlockList.kt`, `BlockStateManager.kt`) and the exact same "stale during scroll" root-cause candidate, any Phase D/backlog work item touching one should check whether it also resolves (or needs to explicitly not touch) the other.

## Heuristic findings

1. **Visibility of system status — positive, with one hedged risk.** Once in selection mode, the toolbar renders `"$selectedCount selected"` (`MobileBlockToolbar.kt:78`) live as blocks are toggled, giving immediate, always-visible confirmation of exactly what's selected — a clean example of this heuristic satisfied. The one open risk against it: the Integration Risk that `BlockViewer`'s `clickable { onStartEditing() }` may not be swapped for a selection-toggle handler when `isInSelectionMode` is true (not independently re-verified against current `BlockViewer.kt` in this pass) — if unfixed, a tap in selection mode would silently open the editor instead of toggling selection, giving the user no feedback that their intended action (toggle) didn't happen.
2. **Consistency and standards — positive.** Copy/Cut/Delete/Clear all appear together in the same toolbar row with conventional verb labels (`"Copy selected"`, `"Cut selected"`, `"Delete selected"`, `"Clear selection"` — `MobileBlockToolbar.kt:83,86,89,92`), matching ordinary selection-toolbar conventions and design/ux.md criterion 11's "no trapped mode" requirement structurally, since Clear/✕ sits in the same row as the destructive actions rather than being buried behind another step.
3. **Discoverability — the most severe finding in this journey — FIXED (GAP-011, Story D.4.2).** Entry into selection mode was long-press only (`BlockItem.kt:249-258`), with **no explicit "Select" button or keyboard-only entry path** — worse than `reorder-block.md`'s low-alpha-but-present handle, this journey had no visible entry-point cue at all. A "Select blocks" button in `MobileBlockToolbar`'s bottom row now provides that visible cue on every platform, additively alongside the unchanged long-press path.
4. **Minimal memory load — mixed.** Once inside selection mode, all four actions are shown simultaneously (`MobileBlockToolbar.kt:71-95`) so the user recognizes rather than recalls what's available — good. But getting *into* that state requires the user to recall, from prior experience or tribal knowledge, that long-press is the trigger, since (per finding 3) there is no visible cue prompting it — the same undiscoverable gesture that fails heuristic 3 also forces recall rather than recognition at the entry step.
