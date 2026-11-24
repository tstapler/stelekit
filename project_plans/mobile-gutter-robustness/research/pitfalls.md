# Mobile Gutter Robustness — Known Pitfalls and Risks

Sources examined:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockGutter.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/BlockItemGestureTest.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ModifierExtensions.kt` and platform actuals
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/WindowSizeClass.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/nav/MoreSheet.kt`

---

## 1. Gesture conflict: tap on the drag handle vs. `detectDragGesturesAfterLongPress`

**Risk level: HIGH**

On mobile, `useLongPressForDrag() = true` (Android), so the drag handle Box applies
`detectDragGesturesAfterLongPress` (BlockGutter.kt:40). Adding a `.clickable` to open the
quick-action sheet on the same node competes with this recognizer.

Compose dispatches pointer events to all `pointerInput` modifiers in the chain simultaneously.
The resolution depends on modifier ordering:

- `.clickable { showSheet = true }` placed **before** (outside) `.dragGesture(useLongPress=true)`:
  short tap → click fires on PointerUp before the long-press timeout → sheet opens. Hold → `detectDragGesturesAfterLongPress` fires after timeout → `.clickable` is cancelled before it resolves.
  **This ordering is correct.**
- `.clickable { ... }` placed **after** (inside) `.dragGesture(...)`:
  the long-press detector sees and consumes the gesture first; click never fires.
  **This ordering silently eats every tap.**

The critical risk is the stale-pointer-input-closure bug documented in
`BlockItemGestureTest.longPressThenTap_onSameRow_togglesSelection_notEditMode` (line 207): when
`isInSelectionMode` flips true mid-gesture (via `onAutoSelectForDrag`), the gutter re-keyed
its `pointerInput` scope. If the new tap gesture's `pointerInput` block captures
`showQuickActionSheet` without listing it as a key, the closure sees the stale `false` value
and the sheet never opens after the first drag cycle. Key the second `pointerInput` on
`showQuickActionSheet` or use `rememberUpdatedState`.

There is **no existing test** for the drag handle's tap-vs-long-press disambiguation. A new
`BlockGutterGestureTest` is needed: short-tap → sheet opens, long-press → drag starts (no
sheet), drag started while sheet open → sheet is dismissed.

---

## 2. Roborazzi screenshot golden breakage from touch-target enlargement

**Risk level: HIGH — mandatory Roborazzi update pass required**

The codebase already flags this risk explicitly. The bullet-point comment at BlockGutter.kt
lines 173–176 reads:

> Deliberately reuses the existing 6dp hit area rather than enlarging it — the drag handle
> above already owns an expanded 48dp touch target for reorder, and growing the bullet's own
> box would shift row height/layout and risk the Roborazzi screenshot goldens for no gain on
> desktop.

The same applies to the collapse caret. Its current modifier chain (BlockGutter.kt line 160):

```kotlin
Modifier
    .size(18.dp)         // outermost layout: 18dp × 18dp
    .clickable { ... }   // touch target is the 18dp × 18dp region, not 22dp
    .padding(end = 4.dp) // layout width = 22dp, but clickable area is still 18dp
```

Wrapping it in a 48dp outer Box (the pattern used by the drag handle at lines 99–107) changes
the gutter column width on every block row that has children, breaking all Roborazzi goldens
for expanded block lists. `ComponentGalleryScreenshotTest.kt` and
`BottomNavScreenshotTest.kt` will both fail.

**Recommended mitigation**: apply the 48dp Box only when `isMobile` (via
`LocalWindowSizeClass.current.isMobile`), and use `requiredSize(48.dp)` with
`wrapContentSize()` so the outer layout reports 48dp for touch-event dispatch but the parent
row continues to allocate the original 22dp via `requiredSize` overriding the layout
measurement. If instead you change the Box unconditionally, record new Roborazzi goldens
immediately:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :kmp:testDebugUnitTest -Proborazzi.test.record=true \
  --tests "dev.stapler.stelekit.ui.*"
```

---

## 3. `ModalBottomSheet` on KMP: works on all targets, one known desktop behaviour gap

**Risk level: LOW — API available; minor UX delta on JVM/wasmJs**

`ModalBottomSheet` from `androidx.compose.material3` is already used in `commonMain` on all
four targets: `MoreSheet.kt`, `SuggestionBottomSheet.kt`, `SyncStatusSheet.kt`,
`CalibrationSheet.kt`, `KnownDistancesSheet.kt`, `MeasurementValueEditorSheet.kt`. Annotation
`@OptIn(ExperimentalMaterial3Api::class)` is required (see `MoreSheet.kt:29`).

Behaviour difference: on desktop JVM and wasmJs the sheet renders as a bottom-anchored overlay
(no native OS chrome), and **swipe-to-dismiss is a no-op** because there is no touch screen.
Scrim-click dismiss works everywhere. `skipPartiallyExpanded = true` (the pattern used in
`MoreSheet.kt:43`) is required — desktop has no "half-open" concept and leaving it false causes
the sheet to open at an intermediate height with no way to fully expand.

One cross-target inconsistency: `useLongPressForDrag() = false` on JVM and wasmJs, so desktop
drag starts immediately without a long-press. The quick-action sheet is gated by `isMobile`
(`WindowSizeClass.COMPACT` or `MEDIUM`), but `WindowSizeClass` is a **layout-width check**, not
a platform check. On a narrow JVM window (< 840dp, possible in windowed mode), `isMobile =
true` and `useLongPressForDrag() = false` simultaneously. Result: on a narrow JVM window, a
tap on the drag handle opens the quick-action sheet (correct), but the user can also start
dragging with a simple press with no long-press (the drag handle does both). No crash, but the
interaction model is mixed. Acceptable if the sheet is dismissed on drag start (see §4).

---

## 4. State management: sheet open while drag starts

**Risk level: MEDIUM**

`ModalBottomSheet` creates a Compose overlay that captures all pointer events within its
visible area. If `showQuickActionSheet = true` while the user attempts a long-press drag on the
gutter, the gutter's `pointerInput(detectDragGesturesAfterLongPress)` is behind the sheet
overlay and receives no pointer events. The drag silently does nothing.

If `showQuickActionSheet` is managed as `var showQuickActionSheet by remember { mutableStateOf(false) }`
local to `BlockGutter`, the sheet and drag handle share the same composition scope. Set
`showQuickActionSheet = false` at the top of `onDragStart` to dismiss the sheet before the
drag motion begins. Failing to do so means: tap → sheet opens; user immediately long-presses
again → drag is blocked by the still-visible sheet overlay.

There is also the dual-state risk: `isDragging = true` and `showQuickActionSheet = true`
concurrently, which could happen if a very fast sequence of tap + long-press triggers both
state updates before recomposition. Guard with `if (isDragging) showQuickActionSheet = false`
in `LaunchedEffect(isDragging)`.

---

## 5. Selection mode + drag handle tap: new state machine edge

**Risk level: MEDIUM**

The existing comment at BlockGutter.kt lines 79–84 documents stelekit#238: when a drag on the
gutter causes `onAutoSelectForDrag` to fire, `isInSelectionMode` can flip from `false` to
`true` while the gutter's `pointerInput` is still active. The codebase guards this by keeping
the drag handle visible for the duration of `isDragging = true` regardless of `isInSelectionMode`
(line 85: `if (isInSelectionMode && !isDragging)`).

Adding a tap gesture introduces a **new edge**: the user taps the drag handle (setting
`showQuickActionSheet = true`), then the parent logic enters selection mode (via some other
trigger — another block's long-press, say). The sheet should be dismissed when
`isInSelectionMode` flips to true, because the quick-action menu's "Indent / Outdent / Move"
actions are incompatible with multi-select mode. Without an explicit dismissal, the sheet stays
open on top of a selection-mode view, which is confusing.

Guard with:
```kotlin
LaunchedEffect(isInSelectionMode) {
    if (isInSelectionMode) showQuickActionSheet = false
}
```

The inverse edge — tapping the sheet's "Delete" action while `isInSelectionMode` has just
become true for another block — is handled by simply not calling the action if the ViewModel
state has moved on; that is a ViewModel-side concern, not a Compose state concern.

---

## Summary

| # | Risk | Severity | Mitigation |
|---|------|----------|-----------|
| 1 | Tap + `detectDragGesturesAfterLongPress` ordering and stale closure | HIGH | Put `.clickable` before `.dragGesture()`; key on `showQuickActionSheet`; add `BlockGutterGestureTest` |
| 2 | Roborazzi goldens break if caret/bullet touch targets change layout | HIGH | Gate 48dp Box behind `isMobile`; record new goldens unconditionally if layout changes |
| 3 | `ModalBottomSheet` desktop behaviour gap | LOW | Use `skipPartiallyExpanded = true`; sheet is `isMobile`-gated so it won't appear on expanded layouts |
| 4 | Sheet open blocks drag; dual-state race | MEDIUM | Set `showQuickActionSheet = false` in `onDragStart`; guard with `LaunchedEffect(isDragging)` |
| 5 | Selection mode flip while sheet is open | MEDIUM | Dismiss sheet in `LaunchedEffect(isInSelectionMode)` when `isInSelectionMode` becomes true |
