# Abort and pivot checkpoints (Story 6.2.9)

Each entry records one checkpoint: the trigger (requirements.md "Abort and pivot criteria"), the measured input, and the outcome.
Outcomes are proceed, narrow or defer. No checkpoint removes a feature from scope.

## Task 6.2.9c - 500-element Canvas performance (Story 2.1.3)

Trigger: the 500-element p95 frame time of a target exceeds BOTH 1.5x the empty-canvas reference (Task 0.1.7b) AND the 24 ms ceiling.
Order when it fires: (1) confirm the static layer cache and viewport culling (built in Story 2.1.3), (2) lower
`SceneLimits.softElementThreshold` for that target only, (3) if still failing, record the supported element count in the release notes.
It never blocks the JVM/desktop release. The rule is `CanvasPerfCheckpoint` (`sketch/v2/diagnostics/CanvasPerfCheckpoint.kt`,
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
