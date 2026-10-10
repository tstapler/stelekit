---
journey_id: reorder-block
platforms: [desktop, android, ios, web]
jtbd_tier: in-between
step_count_target: "1 continuous gesture regardless of distance (design/ux.md criterion 10); discoverability checklist: Move Up/Down remain as an always-visible fallback, drag handle adds zero steady-state clutter, screen-reader-completable without the gesture."
current_step_count:
  desktop: "A working long-press/drag gesture ALREADY EXISTS (see Correction below) — 1 continuous gesture, target met in principle, but with confirmed rough edges (near-invisible handle, stale drop-target during scroll). Move-Up/Move-Down also available (N repeated taps/keystrokes for an N-position move)."
  android: "Same drag mechanism as desktop, using `detectDragGesturesAfterLongPress` per `useLongPressForDrag()`. 1 (select-via-long-press) + 1 continuous gesture. Move-Up/Move-Down also available as fallback (1 select + N repeated taps)."
  ios: "Same as Android — drag mechanism is shared commonMain code; not confirmed manually tested on-device (see Notes)."
  web: "Same shared commonMain drag code as desktop (`detectDragGestures`, no long-press requirement per `useLongPressForDrag()`'s presumed pointer-based branch) — not confirmed manually tested on Web/WASM specifically."
heuristic_findings: |
  Worst violation: discoverability. The drag handle icon renders at 0.15 alpha by default
  (`BlockGutter.kt:61`), independently corroborated by `docs/ux/journey-map.md`'s prior-art
  finding of the same "near-invisible" handle — an already-implemented, target-meeting
  drag-to-reorder mechanism is effectively hidden from new users.
test_ids: []
status: audited
last_verified: 2026-07-06
post_fix_step_count:
  desktop_android_ios_web: "Step count unchanged (1 continuous gesture already met the target) — Story D.3.1 fixed the three known rough edges instead of the step count: GAP-010 (Bug 002, stale blockBounds during scroll), GAP-012 (near-invisible 0.15-alpha handle), GAP-013 (ambiguous CHILD drop-zone affordance). Discoverability finding improves from 'must recall the handle exists' to 'visible at rest.'"
---

# Reorder block

## Trigger
User wants to move a block to a different position among its siblings, or reparent it as a child of another block.

## CORRECTION — a drag-to-reorder mechanism already exists (this journey's baseline is NOT "Move-Up/Move-Down only")

Task A.1.3a's brief directs checking `docs/tasks/drag-and-drop-reorder.md`'s Known Issues before treating any finding as new — that file's own header reads `**Status**: Planning`, which would suggest drag-reorder is unimplemented. **Reading the actual current code contradicts that status field**: `BlockGutter.kt:5-6,17,55-118` already implements a full long-press-then-drag gesture (`detectDragGesturesAfterLongPress`/`detectDragGestures`, gated by a `useLongPressForDrag()` platform helper), rendering a `Icons.Default.DragHandle` icon at 0.15 alpha (near-invisible) that brightens to full opacity on hover/drag (`BlockGutter.kt:61`, `contentDescription = "Drag to move"`). `BlockList.kt:36,138-165,255-306` implements the full drop-target computation: a `DropZone` enum (`ABOVE`/`CHILD`/`BELOW`, line 36) computed from vertical thirds of the target block's bounds (lines 158-161: `<0.33 → ABOVE`, `>0.67 → BELOW`, else `→ CHILD`), a same-subtree drop guard (`isOwnSubtree` check, `BlockList.kt:281-282`), and dispatch to `onMoveSelectedBlocks` per zone (lines 283-301). `BlockDragGhost` is rendered as a `zIndex(10f)`-layered overlay (`BlockList.kt:330-338`) — i.e. `drag-and-drop-reorder.md`'s own "Bug 001" (ghost rendered behind rows) already reads as **fixed** in this branch's code, not merely planned.

**This audit's finding, stated plainly**: `docs/tasks/drag-and-drop-reorder.md`'s `Status: Planning` field is stale relative to the actual code — the mechanism it plans for is substantially already implemented on this branch. Per this task's evidence-over-narrative directive, this is reported as a fact discovered by reading the code, not assumed from the plan doc's own status label.

## Known rough edges (from `docs/tasks/drag-and-drop-reorder.md`'s Known Issues — these are corroborated against the actual current code, not just carried over)

- **Bug 001 — Ghost rendered behind block rows [Medium]**: as described above, appears **already fixed** (`BlockList.kt:338` has `zIndex(10f)` on the ghost overlay).
- **Bug 002 — `blockBounds` stale during scroll [Medium] — FIXED (Story D.3.1, GAP-010)**: `onGloballyPositioned` (`BlockList.kt`) does not re-fire relative to an ancestor scroll offset; `BlockList.kt`'s own block-rendering surface is a plain `Column` (confirmed — no `LazyColumn` in this file), but the *page* hosting it (`JournalsView.kt`/`PageView.kt`) scrolls via its own `LazyColumn`. **Fix**: `BlockList` now takes an `onDragStateChanged: (Boolean) -> Unit` callback, invoked whenever a drag starts/stops; `JournalsView`/`PageView` wire it to set their hosting `LazyColumn`'s `userScrollEnabled = false` for the duration of any active drag — a scroll can no longer happen mid-drag, so `blockBounds` can never desynchronize from the drop-target computation in the first place (no need to re-derive bounds on scroll at all).
- **Bug 003 — Multi-block drag order preservation [High]**: `BlockList.kt:255-259` — if the dragged block is not already in `selectedBlockUuids`, `onAutoSelectForDrag(uuid)` auto-selects only that one block; an already-selected ancestor is not swept in by dragging a specific descendant's handle. Matches the pre-existing plan's description; considered correct-by-design for the common case, flagged as an edge case, not re-litigated here (out of this pass's cut-not-grow scope — GAP-017, P3).
- **Bug 004 — Delta drift for fast drags [Low, per that plan: "Fixed by Story 1"]**: `BlockGutter.kt:90-93`/`110-113` consumes the drag delta directly per pointer event (`onDrag = { change, dragAmount -> change.consume(); onDrag(dragAmount.y) }`) rather than accumulating with a threshold — consistent with that plan's own claim this was already fixed.
- **GAP-012 — near-invisible 0.15-alpha drag handle [P2] — FIXED (Story D.3.1)**: idle alpha raised from 0.15 to 0.45 in `BlockGutter.kt` (still brightens to 1.0 on hover/drag) — the handle is now visible at rest instead of requiring the user to already know it exists.
- **GAP-013 — ambiguous CHILD drop-zone affordance [P2] — FIXED (Story D.3.1)**: `BlockItem.kt`'s `dropAsChild` case previously rendered identically to `dropBelow` (a divider at a different indent) — now the entire target row also gets a `dividerColor.copy(alpha = 0.12f)` background tint while `dropAsChild` is true, giving "this will reparent as a child" its own unambiguous, full-row affordance instead of relying on a subtle indent-shift on a thin line.

## Current step sequence — drag gesture (all platforms, shared commonMain code)

1. Long-press (mobile) or press-and-drag (desktop/pointer, depending on `useLongPressForDrag()`'s platform branch) on the block's drag-handle icon in `BlockGutter` reveals it at full opacity (transitions from 0.15 alpha).
2. Drag past a target block's vertical-third boundary; a `DropZone` (ABOVE/BELOW/CHILD) is computed live and reflected via `dropAbove`/`dropBelow`/`dropAsChild` flags passed down to the target row (`BlockList.kt:307-309`) — the visual affordance for this is a divider/indent-shift per the row's own rendering (not independently verified pixel-for-pixel in this pass).
3. Release: `onMoveSelectedBlocks` is dispatched with the resolved parent/insert-after target, unless the target falls inside the dragged block's own subtree (guarded, no-op).
4. Total: 1 continuous gesture regardless of distance — meets the numeric target in principle. Whether it *feels* discoverable (0.15-alpha handle) or robust (Bug 002's scroll-staleness) is exactly the heuristic-review's job, not restated as fact here.

## Current step sequence — Move-Up/Move-Down fallback (all platforms, unchanged, retained per design/ux.md's discoverability guard)

1. Tap/select the target block, then tap `onMoveUp`/`onMoveDown` (`MobileBlockToolbar.kt:260-266`, wired via `EditorToolbar.kt:68-69`) or press Alt+Up/Down on a hardware keyboard — repeat once per position moved.
2. N-position move = 1 (select) + N (repeated taps/keystrokes).

## Notes
- Prior-art cross-check (`docs/ux/journey-map.md`, commit `b3de1ec7dc`, branch `stelekit-editing`, not merged — see README changelog): independently diagrams a drag handle + ghost card + ABOVE/BELOW/CHILD drop-zone mechanism that matches what this audit found in the *current* code closely (down to flagging the same "near-invisible (18dp), low-alpha handle" and "CHILD drop-zone has no distinct visual affordance beyond a divider indent shift" concerns) — this is strong corroborating evidence the mechanism is real and has existed for some time, not a documentation artifact. Its UX findings (invisible handle, ambiguous CHILD zone, no haptic feedback on drop) are retained as heuristic-review input.
- iOS/Web manual verification of the drag gesture specifically (not just its existence in shared code) has not been performed in this audit pass — flagged for Phase A.2's benchmarking or Phase D's manual QA, consistent with validation.md's note that Android/iOS touch-drag gestures require instrumented/manual device testing, not JVM automation.
- This finding changes the shape of any downstream implementation work: since a drag mechanism already exists, Phase D.3/D.4's scope (if any P0/P1 backlog rows are filed for `reorder-block`) is about **fixing the known rough edges above** (discoverability of the low-alpha handle, Bug 002's scroll staleness), not building drag-to-reorder from scratch.

## Heuristic findings

1. **Visibility of system status — mixed.** While dragging, the `DropZone` (ABOVE/BELOW/CHILD) is computed live and pushed down as `dropAbove`/`dropBelow`/`dropAsChild` flags to the target row (`BlockList.kt:307-309`), so the system does give continuous feedback about where a drop will land — a good example of this heuristic done right. But that feedback becomes actively wrong during the exact condition most likely to occur on a long list: Bug 002 (`onGloballyPositioned` at `BlockList.kt:316-319` not re-firing on scroll, corroborated as "still live" against `PageView.kt`'s hosting `LazyColumn`) means the drop-zone indicator can point at a stale position while the page has scrolled underneath the drag — the visible status stops matching reality precisely when the user is mid-gesture and most needs to trust it.
2. **Consistency and standards — positive.** The drag handle uses the standard Material `Icons.Default.DragHandle` glyph with a conventional `contentDescription = "Drag to move"` (`BlockGutter.kt:61`), and the Move-Up/Move-Down fallback reuses the app's ordinary toolbar-button + Alt+Up/Down keyboard-shortcut convention (`MobileBlockToolbar.kt:260-266`, `EditorToolbar.kt:68-69`) rather than inventing a bespoke gesture vocabulary — new/expert users can rely on patterns already established elsewhere in the toolbar.
3. **Discoverability — the most severe finding in this journey.** The drag handle renders at 0.15 alpha by default, only brightening to full opacity on hover/drag (`BlockGutter.kt:61`). This is independently corroborated, not a one-off observation: `docs/ux/journey-map.md` (unmerged prior art, branch `stelekit-editing`) separately flagged the same "near-invisible (18dp), low-alpha handle" concern. A fully-implemented, gesture-target-meeting mechanism (1 continuous gesture, Bugs 001/004 already fixed) is effectively invisible to a user who hasn't been told it exists — the CHILD drop-zone's lack of a distinct affordance beyond a divider indent-shift (also noted in the prior-art cross-check) compounds this once a user does start dragging.
4. **Minimal memory load — mixed.** The Move-Up/Move-Down fallback is always visible in the block toolbar (`MobileBlockToolbar.kt:260-266`) with no hidden state to recall — a user recognizes the option rather than having to remember it exists, satisfying this heuristic for the fallback path. The drag path fails it: because the handle sits at 0.15 alpha in its resting state, a user must *recall* that a drag handle exists in that gutter position at all (rather than recognizing it on sight) to ever invoke the primary, single-gesture mechanism — the near-invisible default state pushes this from "recognition" to "recall."
