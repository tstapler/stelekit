# Build-vs-Buy: BlockGutter Mobile Robustness

**Date:** 2026-11-23  
**Stack:** Compose Multiplatform 1.10.3 · Kotlin 2.4.10 · Material3 (CMP) · Android M3 1.4.0

---

## 1. Touch target expansion (caret 18dp → 48dp, bullet 6dp → 24dp)

### Options evaluated

**A. `Box(size = 48.dp) { Icon(size = 18.dp) }` — explicit outer hit box**  
The existing `BlockGutter` drag handle already uses this pattern exactly (lines 99–151 of `BlockGutter.kt`): a 48dp `Box` with `pointerInput` attached, containing an 18dp `Icon`. The gesture detector and semantics live on the outer box, so the full 48dp is the real hit area, not cosmetic padding.

Pros: Already proven in this codebase; zero new dependencies; portable across all CMP targets; no layout side-effects on the visual glyph size; Compose Lint / detekt rules won't flag it.  
Cons: Slightly more boilerplate than a one-modifier solution; requires wrapping `clickable` with the outer box when replacing a bare `Icon.clickable`.

**B. `Modifier.minimumInteractiveComponentSize()` (Material3 experimental)**  
M3 exposes this modifier (`@ExperimentalMaterial3Api`) which expands the touch/semantic target to at least 48dp × 48dp without changing visual bounds. It is what `IconButton` uses internally. Available in CMP 1.10.3 / M3 1.4.0.

Pros: One-liner; aligns with M3's internal implementation; works on all CMP targets.  
Cons: Annotated `@ExperimentalMaterial3Api` — requires opt-in; API surface may change in minor releases; doesn't apply to gesture detectors attached via `pointerInput` (only to click/interaction sources), so the caret's existing bare `.clickable {}` would benefit, but a future drag-based lasso on the bullet would still need a manual box. Using two different patterns for the same gutter column adds cognitive overhead.

**C. Accompanist `Modifier.minimumTouchTargetSize()`**  
Accompanist's older touch-target modifier is deprecated in favour of M3's built-in; Accompanist is not in this project's dependencies.

Verdict: **A — Recommended.** The 48dp Box pattern is already established for the drag handle. Apply it uniformly to the caret (wrap the bare `Icon.clickable` in a 48dp Box with the same `Alignment.Center` pattern) and to the bullet for the mobile quick-action tap target. No new dependency.

---

## 2. Quick-action bottom sheet (view-mode tap on drag handle)

### Options evaluated

**A. `ModalBottomSheet` from CMP Material3 — RECOMMENDED**  
Already in use in 10+ commonMain files: `SyncStatusSheet.kt`, `MoreSheet.kt`, `SuggestionBottomSheet.kt`, `MeasurementValueEditorSheet.kt`, `CalibrationSheet.kt`, `BleDevicePanel.kt`, `LibraryFilterBar.kt`, `AssetDetailScreen.kt`, `AnnotationToolbar.kt`, `KnownDistancesSheet.kt`. CMP ships `ModalBottomSheet` for Android, desktop JVM, and wasmJs. The API lives in `commonMain` under `import androidx.compose.material3.ModalBottomSheet`.

KMP compatibility: Confirmed working across all three targets by the existing usages. On desktop JVM it renders as a panel overlay; on Android it renders as a true bottom sheet with scrim.

Pros: Zero new dependency; consistent with the rest of the codebase; handles scrim, back-press, drag-to-dismiss, and animation automatically.  
Cons: `@ExperimentalMaterial3Api` opt-in required (same as every existing usage); desktop rendering is not a native sheet — matches the rest of the app's convention.

**B. `Popup` / `Dialog`**  
Lower-level. Would need manual positioning, dismissal logic, and animation. All existing block-action surfaces use ModalBottomSheet or AlertDialog, not bare Popup.

Verdict: Not recommended — more work for worse UX.

**C. Third-party: `compose-bottom-sheet` (Tlaster/PlatformSheet)**  
Not in the dependency graph. Adds an external dependency for behaviour CMP M3 already ships. PlatformSheet targets native sheet on iOS (not relevant here — iOS uses SwiftUI/Compose interop in this project). KMP coverage is narrower than CMP M3.

Verdict: Not recommended.

**Overall verdict: A — ModalBottomSheet, the established pattern.**

---

## 3. Gesture conflict: tap vs. long-press on the same drag-handle target

### Context

The drag handle currently attaches either `detectDragGestures` (immediate) or `detectDragGesturesAfterLongPress` (mobile, via `useLongPressForDrag()`). The requirement adds a *tap* in view mode to open the quick-action sheet.

### Options evaluated

**A. Mode-conditional gesture — RECOMMENDED**  
View mode and edit/drag mode are mutually exclusive states already tracked in the ViewModel. In view mode, the drag handle can skip the `dragGesture` modifier entirely and attach only a `clickable` (or `combinedClickable`) for the sheet. In edit mode (or when `useLongPress = true`), the drag gesture is attached as today.

```kotlin
if (isMobile && isViewMode) {
    Modifier.clickable { onShowQuickActions() }
} else {
    Modifier.dragGesture(useLongPress = useLongPress, ...)
}
```

Pros: No gesture conflict — the two paths are mutually exclusive by state; no library; matches the existing `isInSelectionMode && !isDragging` branching already in `BlockGutter.kt`.

**B. `combinedClickable` + drag — competing gesture streams**  
Attaching both a `combinedClickable` and a `detectDragGestures` to the same node causes them to race. `combinedClickable` requires a 300ms hold to distinguish tap from long-press, which conflicts with immediate drag start. This is the classic "swipe vs tap" problem in Compose, and the solution is always to separate the modes, not to pile on modifiers.

Verdict: Not recommended.

**C. Third-party gesture library**  
No library resolves the fundamental conflict better than mode-conditional branching. Libraries like Accompanist's `detectTransformGestures` solve multi-finger transforms, not tap/drag disambiguation on the same target.

Verdict: Not recommended.

**Overall verdict: A — mode-conditional modifier composition.**

---

## 4. Haptic feedback on drag start

### Options evaluated

**A. `LocalHapticFeedback.current` — RECOMMENDED**  
`androidx.compose.ui.platform.LocalHapticFeedback` and `androidx.compose.ui.hapticfeedback.HapticFeedbackType` are already used in this codebase in `AnnotationEditorScreen.kt` (lines 79–80, 291) for drag snap feedback. The pattern is:

```kotlin
val haptic = LocalHapticFeedback.current
// inside onDragStart:
haptic.performHapticFeedback(HapticFeedbackType.LongPress)
```

KMP behaviour by platform:
- **Android**: Calls `View.performHapticFeedback(LONG_PRESS)` — real vibration on physical devices. No-op if the device or user setting has haptics disabled.
- **Desktop JVM**: No-op stub. Correct behaviour — desktop has no convention for drag haptics.
- **wasmJs**: No-op stub. Browsers require a user gesture and the Vibration API is unavailable in this context.

Pros: Zero new dependency; already in the project; KMP no-op stubs are the right behaviour on non-mobile targets; consistent with AnnotationEditorScreen precedent.  
Cons: None for this use case.

**B. Third-party haptic library**  
No library adds meaningful value over the standard API for this use case.

Verdict: Not recommended.

**Overall verdict: A — `LocalHapticFeedback.current`, the established pattern.**

---

## Summary table

| Sub-component | Verdict | Approach | New dependency? |
|---|---|---|---|
| Touch target expansion | **Build** | 48dp Box (existing drag-handle pattern) | No |
| Quick-action bottom sheet | **Build** | `ModalBottomSheet` (10+ existing usages) | No |
| Gesture conflict resolution | **Build** | Mode-conditional modifier composition | No |
| Haptic feedback | **Build** | `LocalHapticFeedback.current` (existing pattern) | No |

No external library additions are required for any sub-component. All four solutions use APIs already present in the codebase.
