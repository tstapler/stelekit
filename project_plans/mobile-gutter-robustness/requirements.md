# Requirements: Mobile Gutter Robustness

**Project name**: `mobile-gutter-robustness`  
**Source**: Backlog item — "We should have more robust usage of the gutter on mobile"

---

## Problem Statement

The `BlockGutter` composable (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt`) renders identically on mobile and desktop. On mobile, two of the three interactive gutter elements violate the app's own 48 dp minimum touch-target rule (ux.md criterion 19):

| Element | Current touch target | Required on mobile |
|---|---|---|
| Drag handle | 48 dp (OK) | 48 dp ✓ |
| Collapse caret | 18 dp (`.size(18.dp).clickable`) | 48 dp ✗ |
| Bullet / lasso-select dot | 6 dp (`.size(6.dp)`) | ≥ 24 dp ✗ |

Additionally, structural block actions (indent, outdent, move up/down) are only reachable from the `MobileBlockToolbar`, which only appears when the block is in **edit mode**. A user browsing (view mode) has no path to these actions without first tapping to edit — the gutter is the natural, always-visible entry point but currently serves no quick-action purpose in view mode.

---

## Background

- The screenshot shows the mobile journal view: each block row has a `=` drag-handle on the far left and a `•` bullet dot. The collapse caret only renders when `hasChildren == true`.
- `useLongPressForDrag()` returns `true` on Android, so the drag handle is correctly shown at full alpha (1.0) and requires a long-press to begin drag — this behaviour is intentional and correct.
- `WindowSizeClass` infrastructure is already available via `LocalWindowSizeClass.current.isMobile`; `BlockGutter` does not currently consume it.
- The existing ux.md criterion 19 (rich-editing-experience project) only mandated the 48 dp rule for the drag handle. The caret and bullet were left at their desktop-optimised sizes.

---

## Goals

1. **Fix touch-target violations**: Bring collapse caret and bullet-select hit areas up to 48 dp on mobile without layout regressions on desktop.
2. **Quick-action gutter menu on mobile**: A single tap on the drag handle (when NOT currently dragging) in view mode (not editing) opens a compact bottom sheet or popover listing structural actions: **Indent**, **Outdent**, **Move Up**, **Move Down**, **Copy block ref**, **Delete**. This avoids requiring edit-mode entry for structural operations.
3. **Haptic feedback on gutter interactions**: Fire `HapticFeedbackType.LongPress` when long-press drag begins, and `HapticFeedbackType.TextHandleMove` on each reorder position change.
4. **Preserve desktop behaviour**: All changes are gated by `LocalWindowSizeClass.current.isMobile` or equivalent; desktop layout is unchanged.

---

## Acceptance Criteria

### AC-1: Collapse caret meets 48 dp touch target on mobile
On a device or emulator with `WindowSizeClass.COMPACT` or `WindowSizeClass.MEDIUM`, the collapse/expand caret's clickable region is ≥ 48 × 48 dp. Verified by a Robolectric `androidUnitTest` that asserts the bounds of the "Collapse" / "Expand" semantics node is `≥ 48dp × 48dp`.

### AC-2: Bullet lasso-select meets 24 dp touch target on mobile
On mobile, the bullet dot's draggable hit area is ≥ 24 × 24 dp (acceptable minimum for a secondary gesture-entry point, per Material Design). Verified by a Robolectric test asserting the "Select blocks" semantics node bounds.

### AC-3: Drag handle quick-action menu (view mode, mobile)
When a block is **not** in edit mode and the user taps (not long-presses) the drag handle on mobile, a modal bottom sheet appears with the actions: Indent, Outdent, Move Up, Move Down, Copy Block Ref, Delete. Each action fires the corresponding callback and closes the sheet. Verified by a Robolectric test: tap "Drag to move", assert sheet visible, tap "Move Up", assert `onMoveUp` called, assert sheet dismissed.

### AC-4: Drag handle quick-action menu does NOT appear on desktop
On desktop (`WindowSizeClass.EXPANDED`), tapping the drag handle does NOT open the quick-action sheet. Verified by a JVM Compose UI test.

### AC-5: Long-press drag still works after tap-to-menu addition
After the tap-to-menu is added, long-pressing the drag handle on mobile still enters the drag-to-reorder flow as before. The two gestures (tap vs. long-press) do not interfere. Verified by `BlockItemGestureTest`.

### AC-6: Haptic feedback on drag start
When the user begins a long-press drag on the gutter handle (Android), the system fires one haptic event of type `HapticFeedbackType.LongPress`. Verified in `BlockItemGestureTest` or a dedicated haptic test.

### AC-7: No Roborazzi screenshot regressions
Running `./gradlew :kmp:testDebugUnitTest` with Roborazzi goldens produces no unexpected diff for the existing mobile-layout screenshot tests. If gutter sizing changes alter pixels, the goldens must be updated and the new golden must be recorded in the same PR.

### AC-8: No touch-target size regression on desktop
On desktop, the collapse caret and bullet areas remain at their existing visual and hit sizes (18 dp and 6 dp respectively). The layout is pixel-identical to the baseline.

---

## Out of Scope

- Swipe-to-delete gesture on block rows (separate backlog item).
- Zoom-to-block / focus-mode navigation from the gutter.
- Changes to `MobileBlockToolbar` (the bottom editing toolbar); this feature is about view-mode gutter usability.
- iOS-specific haptic patterns (iOS uses a different `Haptics` API; a follow-on can add it).
