# Validation: Loupe Crosshair Alignment Fix

## Adversarial Review

### Claim: "Integer truncation causes the visible misalignment"

**Verdict: PLAUSIBLE but overstated as the sole cause.**

Truncation loses at most 0.999 physical pixels per axis. At zoomFactor=3 this
maps to ~0.33 unzoomed pixels — sub-pixel, barely perceptible. It's a real
precision improvement but unlikely to be what users notice at normal viewing distance.
The more likely perceptual cause is **micro-movement during finger lift**: between
the last move event and the UP event the finger moves 2–8 pixels, and the loupe
was showing the last-move position, not the UP position.

**Mitigation already in plan:** Task 3 reorders the loupe clear so the UP-event
position is reflected before the loupe disappears and the dot appears, bounding
the visible offset to exactly zero (they're in the same frame).

---

### Claim: "`onLoupeOffset(null)` must move AFTER `onTap`"

**Verdict: CONFIRMED safe, with a caveat.**

Compose snapshot state batches mutations that occur within the same event handler
(same coroutine continuation). Since both calls are in the same `awaitEachGesture`
block, `loupeOffset = null` and the ViewModel's state update from `addPoint` will
appear in the same composed frame. The user will see: loupe crosshair at UP
position + annotation dot rendered simultaneously, then the loupe disappears on
the next frame (since `loupeOffset` becomes null and that null triggers
recomposition). One extra frame of loupe is acceptable UX.

**Caveat:** The `isTap` guard (`(lastPos - downPos).getDistance() < tapThresholdPx`)
still applies. If the user drags further than the threshold, neither `onTap` nor
`onLoupeOffset(null)` is called — the loupe is correctly suppressed already.
Moving `onLoupeOffset(null)` after the `if (isTap)` block means the loupe stays
visible if the gesture is NOT a tap. This is benign: for a non-tap drag, the loupe
already cleared on the last move event (before the UP), so no regression.

Wait — actually in the current code, `onLoupeOffset(null)` fires unconditionally
after the loop, whether or not the gesture is a tap. If we move it after the
`if (isTap)` block, we must keep it outside the `if` so it fires for both taps
and non-taps.

**Revised plan task 3 ordering:**
```kotlin
if (isTap) {
    if (currentTool != AnnotationTool.SELECT) { onRipple?.invoke(lastPos) }
    onTap(lastPos)
}
onLoupeOffset?.invoke(null)   // ← still unconditional; just moved after onTap
```

This is safe and correct.

---

### Claim: "contentScale on main image is redundant change"

**Verdict: LOW RISK but defensively correct.**

Coil 3.x's `AsyncImage` defaults to `ContentScale.Fit`. Making this explicit:
- Protects against future Coil default changes
- Makes the offset math's assumption self-documenting
- Zero behavior change at runtime

**Accepted.** Worth the one-line change.

---

### Open Question: Why does the main image have no `contentScale`?

Looking at the layer comment (lines 406–410): "Zoom/pan is achieved via
`graphicsLayer` transform". But searching the file, there is no `graphicsLayer`
modifier on the `AsyncImage`. The comment appears to be aspirational or from a
prior design. The image currently renders statically with Fit. No zoom/pan
transform is in place. If zoom/pan via `graphicsLayer` is added in the future,
the `touchOffset` from pointer events (which are in the UN-transformed coordinate
space) will not match the displayed image position, and the loupe offset math will
break. This is an **out-of-scope risk** noted for future work.

---

### Claim: "Robolectric gesture test validates UP-event position fidelity"

**Verdict: IMPLEMENTABLE with care.**

Robolectric + Compose UI test can inject pointer events via:
```kotlin
onNode(...).performTouchInput { 
    down(Offset(100f, 100f))
    moveBy(Offset(5f, 5f))
    up()
}
```
The `InProgressAnnotationCanvas` composable is not directly accessible by
content description in the test (it uses `Annotation canvas — tap to place points`).
The test should expose a callback directly to the test rather than verifying
through the ViewModel — or use the ViewModel and assert that `addPoint` received
the final `up()` position.

**Risk:** If `awaitEachGesture`'s `awaitFirstDown(requireUnconsumed = false)` has
an ordering conflict with Robolectric's event dispatcher, the gesture might not
complete. Should be validated against `AnnotationEditorScreenRobolectricTest`'s
existing pattern before writing the new test.

---

## Go / No-go

| Item | Decision |
|------|----------|
| `roundToInt()` fix | ✅ Go |
| Explicit `contentScale` | ✅ Go |
| Reorder `onLoupeOffset(null)` after `onTap` | ✅ Go (see revised ordering above) |
| `LoupeInnerOffsetTest` unit tests | ✅ Go |
| Gesture UP fidelity Robolectric test | ✅ Go (validate Robolectric pointer injection first) |
| ViewModel coverage stubs | ✅ Go |
| Out-of-scope: zoom/pan via graphicsLayer | ⛔ Future item |
