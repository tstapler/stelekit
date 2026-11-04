# Research: Loupe Crosshair Alignment Fix

## Code Locations

### Primary composable — AnnotationEditorScreen.kt
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`
- `LoupeOverlay` composable: lines 1530–1601
- `InProgressAnnotationCanvas` gesture loop: lines 944–1017
- Main `AsyncImage` without explicit `contentScale`: lines 415–422
- LoupeOverlay invocation: lines 447–454

### Inner-offset math (lines 1557–1558)
```kotlin
val innerOffsetXPx = loupeSizePx / 2f - touchOffset.x * zoomFactor
val innerOffsetYPx = loupeSizePx / 2f - touchOffset.y * zoomFactor
```
Correctly derived: letterbox offsets cancel in the Fit-scaled inner image.
Integer truncation via `IntOffset(innerOffsetXPx.toInt(), …)` discards the
fractional part (up to 0.999 px per axis). At 3× zoom this represents a
visible ~3-pixel misalignment in the loupe window.

### Timing issue — gesture loop (lines 978–984)
```kotlin
if (!change.pressed) break  // UP event
// ...
onLoupeOffset?.invoke(null)  // ← loupe cleared
if (isTap) {
    onRipple?.invoke(lastPos)
    onTap(lastPos)            // ← tap registered
}
```
Both `onLoupeOffset` and `onTap` fire synchronously on the main thread. Compose
batches the state mutations: `loupeOffset = null` and the ViewModel's
`addPoint` both happen before recomposition, so the loupe never re-renders with
the UP-event position. The user sees the loupe disappear and the dot appear
at the last-moved position, which may not be the same as where the finger
ultimately lifted (micro-movement during release).

### ContentScale inconsistency risk (line 415 vs 1570)
Main image uses Coil default (`ContentScale.Fit`; not explicit in source).
Loupe inner image explicitly declares `ContentScale.Fit`. If Coil's default
ever changes or diverges by platform, the loupe offset math silently breaks.
Making the main image explicit eliminates the dependency on Coil's default.

### loupeDecodeSize (ImageSampling.kt lines 46–48)
Caps the longest edge at 2048 px. The displayed inner image is always
`canvasSize × zoomFactor` dp regardless of decode size. `ContentScale.Fit`
upscales the capped decode, which is correct—the letterbox math is unaffected
by decode resolution because Fit preserves aspect ratio.

## Existing Test Coverage

| Test file | Covers |
|-----------|--------|
| `AnnotationCoordinateTransformTest` | `toNormalized` / `toScreen` round-trips |
| `AnnotationEditorViewModelTest` | Tool selection, addPoint, undo/redo, calibration |
| `AnnotationEditorViewModelBranchTest` | Edge cases (e.g., max points, zero calibration) |
| `AnnotationEditorScreenRobolectricTest` | Smoke render of full screen, nudge banner |
| `AnnotateEditorPartsTest` | CalibrationStatusBar, GPS row, device chip |
| `CalibrationSheetTest` | CalibrationSheet unit dialog behavior |
| `AnnotationGeometryTest` | Distance/area/angle geometry formulas |

### Gaps
- **No test** for `LoupeOverlay` offset math (pure function, fully unit-testable)
- **No test** for touch-to-tap position fidelity in `InProgressAnnotationCanvas`
- **No test** for `AnnotationEditorViewModel.updateLabelText` / `confirmLabel` path
- **No test** for `AnnotationEditorViewModel.setActiveDevice` / `deviceConnectionState`
- **No test** for `AnnotationEditorViewModel.injectMeasurementFromDevice`
- CalibrationSheet and BleDevicePanel have basic render tests; ViewModel integration not tested

## Compose Coordinate System Verification

Pointer event `change.position` in `pointerInput` returns layout px relative to
the composable that owns the `pointerInput` modifier (the `Canvas` with
`fillMaxSize()`). The `LoupeOverlay` is a sibling inside the same `Box`, so its
`.offset { IntOffset(x, y) }` uses the same layout-pixel coordinate space.
No coordinate space mismatch exists.

## ContentScale.Fit Offset Algebra (reference)

Let:
- `s` = Fit scale factor applied to main image in canvas of size `W × H`
- `lbX = (W - imgW·s) / 2` = horizontal letterbox offset in main view
- `zf` = zoom factor (3)
- `tx` = touch x in canvas space (physical pixels)

In the loupe's inner space (size `W·zf × H·zf`):
- Inner image content start: `lbX·zf`
- Scale factor applied by Fit: `s·zf`
- Position of `tx` in inner space: `lbX·zf + (tx − lbX)·zf = tx·zf`

For the crosshair center (at `loupePx/2`) to show `tx`:
  `innerOffsetX + tx·zf = loupePx/2`
  `innerOffsetX = loupePx/2 − tx·zf` ✓ (matches current code)

Conclusion: the algebra is correct for both letterboxed and non-letterboxed images.
The two fixable issues are (1) integer truncation in `IntOffset` and
(2) explicit `contentScale` on the main image.

## Recommendation for `onLoupeOffset` Timing

Move `onLoupeOffset?.invoke(null)` to AFTER `onTap(lastPos)` so the UP event
position is briefly reflected in the loupe's last state. Because Compose batches
both mutations into the same recomposition frame, the loupe will show the
UP-event position in the same frame that the annotation dot appears—giving the
user a brief visual confirmation that the crosshair center was the placed point.
Alternatively, the loupe state could be suppressed only for `SELECT` tool and
explicitly NOT cleared on UP when in a measurement mode (let it persist for 100 ms
via animation, like the ripple effect already does).
