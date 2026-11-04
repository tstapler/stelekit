# Plan: Loupe Crosshair Alignment Fix

## Scope

Fix three layered issues that together cause the annotation dot to appear at a
visually different location than the loupe crosshair:

1. Integer truncation in `LoupeOverlay` offset → round instead of truncate.
2. Implicit `contentScale` on the main `AsyncImage` → make it explicit.
3. Loupe cleared before tap registered → reorder or animate.

Also add unit and Robolectric tests to cover the loupe math and the
gesture-to-ViewModel pipeline. Audit uncovered ViewModel paths and add stubs.

## Architecture Decision

**No new abstractions needed.** All three fixes are localized edits within
`AnnotationEditorScreen.kt` (< 20 lines of change total). Tests live in
existing test files or new files in `commonTest` / `androidUnitTest`.

## Task List

### 1. Fix: Use `roundToInt()` for loupe inner offset (AnnotationEditorScreen.kt)

Lines 1573–1574:
```kotlin
// Before
.offset { IntOffset(innerOffsetXPx.toInt(), innerOffsetYPx.toInt()) }
// After
.offset { IntOffset(innerOffsetXPx.roundToInt(), innerOffsetYPx.roundToInt()) }
```
Add `import kotlin.math.roundToInt` at top. This eliminates up to 1px sub-pixel
drift per axis (3px equivalent in the zoomed loupe view).

### 2. Fix: Explicit `contentScale` on main AsyncImage (AnnotationEditorScreen.kt)

Line 415–422: add `contentScale = ContentScale.Fit` to the main image so the
offset math is resilient to Coil default changes.

### 3. Fix: Reorder loupe clear so UP event position is visible (AnnotationEditorScreen.kt)

Move `onLoupeOffset?.invoke(null)` from before `onTap` to after. Because Compose
batches these state mutations into one recomposition frame, the loupe's UP-event
position and the placed dot will appear in the same frame, giving visual
confirmation that the crosshair center == the placed point.

```kotlin
// Before (lines 981–984)
onLoupeOffset?.invoke(null)
if (isTap) {
    if (currentTool != AnnotationTool.SELECT) { onRipple?.invoke(lastPos) }
    onTap(lastPos)
}

// After
if (isTap) {
    if (currentTool != AnnotationTool.SELECT) { onRipple?.invoke(lastPos) }
    onTap(lastPos)
}
onLoupeOffset?.invoke(null)
```

### 4. Test: LoupeInnerOffsetTest (new file in commonTest)

File: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/annotate/LoupeInnerOffsetTest.kt`

Test that `loupeSizePx/2 - touchOffset * zoomFactor` (rounded) positions
image content `touchOffset` exactly at the loupe center for:
- Touch at canvas center
- Touch at canvas edges
- Touch at a point inside a letterboxed (non-square) canvas
- Touch beyond canvas bounds (clamped)
- Multiple zoom factors (1.5f, 2f, 3f, 4f)

This is a pure math test with no Compose runtime dependency.

### 5. Test: LoupeInnerOffsetTest — decode-size cap invariance

Verify that `loupeDecodeSize` returning a capped size doesn't affect the
offset algebra: since the displayed inner image size is always
`canvasSize × zoomFactor` dp regardless of decode size, the offsets are
decode-size-independent. Assert that for any `MAX_LOUPE_EDGE_PX`-capped
decode size, the offset math remains identical to the uncapped case.

### 6. Test: Gesture UP position fidelity (AnnotateEditorPartsTest or new Robolectric test)

Using `createComposeRule()` + Robolectric, simulate a `down → move → up`
gesture on `InProgressAnnotationCanvas` and verify that `onTap` receives
the UP-event position (not any intermediate drag position). This confirms
that the placed annotation dot matches the loupe's last-shown position.

The test should be placed in `AnnotateEditorPartsTest` or a new
`InProgressAnnotationCanvasGestureTest.kt` in `androidUnitTest`.

### 7. ViewModel coverage audit

Read `AnnotationEditorViewModel.kt` and produce a table of public methods
vs test coverage. For each uncovered method, add at minimum a "smoke"
test in `AnnotationEditorViewModelTest.kt` or `AnnotationEditorViewModelBranchTest.kt`.

Known uncovered paths from research:
- `updateLabelText` / `confirmLabel` / `cancelLabel`
- `setActiveDevice` / `deviceConnectionState`
- `injectMeasurementFromDevice`
- `clearCalibrationMessage`

### 8. Register new tests in AllTests collectors

Add the new `LoupeInnerOffsetTest` class to:
- `kmp/src/commonTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt` (if present)

Add any new Robolectric test to:
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt`

Confirm both Bazel and Gradle can discover the new tests.

## File Changeset Summary

| File | Change type |
|------|-------------|
| `kmp/src/commonMain/.../ui/annotate/AnnotationEditorScreen.kt` | Edit (3 fixes) |
| `kmp/src/commonTest/.../annotate/LoupeInnerOffsetTest.kt` | New |
| `kmp/src/androidUnitTest/.../ui/annotate/AnnotateEditorPartsTest.kt` or new file | Edit / New |
| `kmp/src/commonTest/.../annotate/AnnotationEditorViewModelTest.kt` | Edit (coverage stubs) |
| AllTests collector files | Edit (register new test classes) |

## Risk Assessment

**Low risk.** All three code changes are isolated edits within a single
composable function — no new abstractions, no DB changes, no migration needed.
`roundToInt` is a one-liner with no logical change to behavior, only sub-pixel
rounding. Explicitly setting `contentScale` is a no-op at runtime (Coil's
default matches). Reordering `onLoupeOffset(null)` after `onTap` is safe because
Compose's snapshot state batches both mutations before the next frame.

**Test risk.** The Robolectric gesture test must properly inject pointer events
via `performTouchInput { down(...); moveTo(...); up() }`. Robolectric's pointer
simulation occasionally has quirks with `awaitEachGesture`; the test should
confirm it actually receives an `up` event and not just the `down`.
