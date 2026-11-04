# Requirements: Loupe Crosshair Alignment Fix

## Item Summary

The annotation editor's loupe (magnifying glass) overlay does not reliably place
the touch pointer at the center of its crosshair when the user releases their press.
The placed annotation point should land exactly where the loupe crosshair indicates.
Additionally, the broader annotation module lacks test coverage for several wired
features, so this item also hardens coverage across calibration, measurement tools,
and the ViewModel.

## Problem Statement

When a user presses and drags to place a measurement endpoint, the loupe magnifies
the region under their finger and shows a crosshair at its center. At release, the
annotation dot should appear exactly where the crosshair was. Users report the dot
lands at a visually different position than the crosshair indicated—making precise
placement unreliable.

## Key Files

| File | Role |
|------|------|
| `kmp/src/commonMain/.../ui/annotate/AnnotationEditorScreen.kt` | `LoupeOverlay` composable (lines 1530–1601), `InProgressAnnotationCanvas` gesture loop (lines 944–1017) |
| `kmp/src/commonMain/.../platform/image/ImageSampling.kt` | `loupeDecodeSize` / `capLongestEdge` helpers |
| `kmp/src/commonTest/.../annotate/AnnotationCoordinateTransformTest.kt` | Existing coordinate round-trip tests |
| `kmp/src/androidUnitTest/.../ui/annotate/AnnotationEditorScreenRobolectricTest.kt` | Existing Robolectric smoke tests |
| `kmp/src/commonTest/.../annotate/AnnotationEditorViewModelTest.kt` | ViewModel unit tests |
| `kmp/src/androidUnitTest/.../ui/annotate/AnnotateEditorPartsTest.kt` | Parts-level Robolectric tests |

## Identified Potential Root Causes

1. **Timing: loupe cleared before point is placed.** In `InProgressAnnotationCanvas`,
   `onLoupeOffset?.invoke(null)` (line 981) fires synchronously before `onTap(lastPos)`
   (line 984). Because Compose batches state reads before recomposition, the loupe
   may visually disappear slightly before the UP-event position is used as the placed
   point. The user thus sees the loupe crosshair at one frame, then it vanishes, and
   the dot appears at what looks like a different spot due to finger lift micro-movement.

2. **ContentScale inconsistency.** The main `AsyncImage` (line 415) has no explicit
   `contentScale`, falling back to Coil 3.x's default (`ContentScale.Fit`). The loupe's
   inner `AsyncImage` explicitly sets `ContentScale.Fit`. If the defaults ever diverge
   between Coil versions or platform implementations, the inner-image offset math
   (`innerOffsetXPx = loupeSizePx/2 - touchOffset.x * zoomFactor`) produces a
   misaligned view. Making the contentScale explicit on the main image guards against
   this.

3. **Integer truncation in `IntOffset`.** The inner image offset is cast via
   `IntOffset(innerOffsetXPx.toInt(), innerOffsetYPx.toInt())` (line 1574). Truncating
   rather than rounding loses up to 1 physical pixel per axis, which at 3× zoom
   corresponds to a 3-pixel misalignment visible in the zoomed loupe.

4. **Missing `AnnotationEditorScreenKeyboardTest` loupe tests.** No test verifies that
   after a tap gesture on `InProgressAnnotationCanvas`, the placed point appears at the
   same canvas coordinate as the last loupe-updated position. The gap means the
   alignment regression could reappear silently.

## Acceptance Criteria

1. After the user releases a press on the annotation canvas, the annotation dot appears
   at the same canvas coordinate that the loupe crosshair center was showing immediately
   before the loupe disappeared.

2. The main `AsyncImage` explicitly declares `contentScale = ContentScale.Fit` so it
   can never silently diverge from the loupe's rendering assumptions.

3. The inner-image offset in `LoupeOverlay` uses `roundToInt()` (not `toInt()`) for
   the `IntOffset` arguments so sub-pixel drift is eliminated.

4. A unit test (`LoupeInnerOffsetTest` in `commonTest`) verifies that for arbitrary
   canvas sizes, touch positions, and zoom factors, the mathematical expression
   `loupeSizePx/2 - touchOffset * zoomFactor` (rounded) produces an offset such that
   image position `touchOffset` falls exactly at the loupe center.

5. A Robolectric test verifies that after a simulated `down → move → up` gesture on
   `InProgressAnnotationCanvas`, the ViewModel receives `addPoint` with the UP-event
   position (not an earlier drag position).

6. Existing tests in `AnnotationEditorScreenRobolectricTest`, `AnnotateEditorPartsTest`,
   and `AnnotationEditorViewModelTest` continue to pass without modification.

7. Coverage audit: a review comment identifies any features wired in
   `AnnotationEditorViewModel` (calibration, undo/redo, label confirm, BLE device,
   drive export) that have zero unit-test coverage, and at minimum stubs are added for
   each.
