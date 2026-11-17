# Abort and pivot checkpoints (Story 6.2.9)

Each entry records one checkpoint: the trigger (requirements.md "Abort and pivot criteria"), the measured input, and the outcome.
Outcomes are proceed, narrow or defer. No checkpoint removes a feature from scope.

## Task 6.2.9c - 500-element Canvas performance (Story 2.1.3)

Trigger: the 500-element p95 frame time of a target exceeds BOTH 1.5x the empty-canvas reference (Task 0.1.7b) AND the 24 ms ceiling.
Order when it fires: (1) confirm the static layer cache and viewport culling (built in Story 2.1.3), (2) lower
`SceneLimits.softElementThreshold` for that target only, (3) if still failing, record the supported element count in the release notes.
It never blocks the JVM/desktop release. The rule is `CanvasPerfCheckpoint` (`commonTest/.../sketch/v2/diagnostics/CanvasPerfCheckpoint.kt`,
`CanvasPerfCheckpointTest`); rerun it with each new figure.

| Target | Figure (p95, 500 elements) | Empty canvas p95 | Source | Outcome |
|---|---|---|---|---|
| JVM (software raster) | 14.23 ms, plain rounded rects | 0.24 ms | `CanvasReferenceBenchmark`, baselines.md Task 0.1.7b | PROCEED: over 1.5x but under 24 ms |
| JVM (software raster) | 1.46 to 1.82 ms, the real `ScenePainter` | 0.24 ms | `ScenePainterTest.pan500Elements_should_StayWithinTheFrameBudget`, three runs recorded in `sketch-engine-budgets.json` | PROCEED |
| wasmJs (headless Chrome, ChromeHeadless 146) | 2.70 ms, the real `ScenePainter` (one run, 120 frames after 60 warm-up) | 0.20 ms | `ScenePainterWasmBenchmark` (`:kmp:wasmJsBrowserTest`, 2026-11-16) | PROCEED: under 24 ms |
| Android Pixel_Fold | not measured | not measured | owner-run (OD-2), `owner-verification.md` | NOT MEASURED: no outcome is claimed |

Notes:
- The "1.5x the empty canvas" half of the trigger is always true here, because the empty canvas is a bare clear (46x to 59x on the JVM,
  baselines.md); only the 24 ms ceiling discriminates. The owner is asked in baselines.md to restate the relative rule; until then the
  pivot effectively fires on the ceiling alone.
- Software rasterisation on a 24-CPU desktop is not a device: the JVM and wasm figures are relative evidence, and the Pixel_Fold run decides
  the Android row.

## Task 6.2.9a - Sketchy on wasmJs and iOS (Story 2.2.3)

Trigger (requirements.md "Sketchy renderer on wasmJs / iOS"): the Sketchy render of architecture-15 differs from the JVM render by more than
the metric-4 tolerance (2% of pixels), OR the Sketchy draw-list build for 500 elements takes more than 2x the Clean build on the same host.
Switch when it fires: `SketchyAvailability.RECORDED` marks that target `Unavailable` (draws Clean, notice, stored `roughness` and `seed` kept) and ADR-004 records it.
It never blocks a release; no checkpoint removes a feature from scope.

| Target | Pixel diff vs JVM | Build ratio, 500 elements | Source | Outcome |
|---|---|---|---|---|
| Desktop (JVM) | reference mask | 1.28x (best of 15; 1.92x cold single run) | `LargeSceneDegradationTest` | PROCEED |
| Android | pixel-exact (Spike 0.1.3 Robolectric) | not measured | Spike 0.1.3e | PROCEED on pixels; build ratio is part of the owner-run device pass |
| wasmJs (headless Chrome 146) | 0.0 differing pixels | 2.17x, 2.26x, 2.29x, 2.18x (four runs) | `LargeSceneDegradationWasmTest`, `SketchyBuildRatioWasmTest`, 2026-11-16 | NARROW: over 2x, so Web draws Clean with the notice |
| iOS | not run | not run | BUG-001 blocks the iOS compile on this Linux host | UNVERIFIED: no outcome is claimed; a macOS or CI run must compile the target first |

Notes:
- The wasm figure comes from a software Chrome on a 24-CPU desktop under load (load average 8 to 11 during the runs); the ratio is the same-host
  comparison the rule asks for, but a real browser on a device may differ. The owner can reverse the entry once a faster Sketchy build measures at or under 2x.
- `StrokeStyleProvider.forTarget` has no production caller yet, so this switch changes no runtime behaviour until the renderer is wired to a target.


## Task 6.2.9d - .excalidraw fidelity (Story 5.1.3)

Trigger (requirements.md "Abort and pivot criteria"): fewer than all six hand-authored fixtures round-trip with exact geometry for the supported subset.
Switch when it fires: the failing fixtures' element kinds leave the stated supported subset (importer placeholders per OD-6 apply); with four or fewer of six
passing, import ships labelled "beta" and export stays off by default until fixed; the owner, not the implementer, then amends metric 7.
The rule is `FidelityCheckpoint` (`commonTest/.../sketch/v2/diagnostics/FidelityCheckpoint.kt`, `FidelityCheckpointTest`); rerun it with each new set of results.

| Fixture set | Result | Source | Outcome |
|---|---|---|---|
| Six hand-authored fixtures (shapes-text, bound-arrows, frame-group, image, freedraw, legacy-binding), JVM | 6 of 6 import, export and re-import with the second scene equal to the first (kinds, geometry, text, bindings, z-order, `extras`) | `FixtureRoundTripTest`, 3 tests PASSED, 2026-11-16 | PROCEED on hand-authored fixtures |
| Real Excalidraw exports (Task 0.1.5d) | not supplied | owner-run | UNMEASURED: no outcome is claimed against real exports |

Notes:
- The fixtures were authored from Excalidraw v0.18.1 `types.ts`, so passing shows the exporter and importer agree with each other and with that reading of the format, not with files real Excalidraw writes. The pivot is re-evaluated when real exports exist.
- The six fixtures are asserted inside three tests, not one test per fixture, so a failure names the fixture in the assertion message rather than in the test name.

## Task 6.2.9b - Arrow binding and elbow routing (Story 3.3.2)

Trigger (requirements.md "Abort and pivot criteria"): `BindingInvariantPropertyTest` cannot hold the invariant (every `Binding.elementId` resolves, `reconcile` has nothing to repair) over rotate, group transform and paste.
Switch when it fires: v1 ships bind-on-drop, follow-on-move and unbind-on-delete only; rotate and group transform leave a bound arrow end at its last position, unbind it and show a notice. Elbow routing stays stored-only (OD-7) in every outcome. A dangling id after `reconcile` blocks release; a missing convenience does not.
The rule is `BindingCheckpoint` (`commonTest/.../sketch/v2/diagnostics/BindingCheckpoint.kt`, `BindingCheckpointTest`); rerun it with each new set of results.

| Operation | Invariant | Source | Outcome |
|---|---|---|---|
| Rotate (`RotateElement` of a bound shape) | held | `BindingInvariantPropertyTest`: random sequences of 10 command kinds, 40 committed steps per seed, `:kmp:jvmTest`, 1 test PASSED, 2026-11-16 | PROCEED |
| Group transform (`GroupElements`, `UngroupElements`, `MoveElements` over a selection) | held | same test; group and ungroup were added to its generator in this task | PROCEED |
| Paste and duplicate (`SceneClipboard.paste`, `DuplicateElements`) | held | same test | PROCEED |
| Undo of the whole sequence restores the start | held | same test | PROCEED |
| Elbow routing | stored only | OD-7 | not a binding outcome; unchanged |

Notes:
- The property test ran on the JVM only; wasmJs, Android and iOS were not run for this checkpoint, so the outcome is claimed for the shared `commonMain` logic as exercised on the JVM.
- There is no group-level rotate command (only `RotateElement` on one element), so "group transform" is move of grouped members plus group and ungroup; a group rotate would need a new command and a re-run.
- The figures above are the recorded inputs to `BindingCheckpoint.evaluate`; there is no automated link from the test result to the rule.
