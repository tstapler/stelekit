# Baselines (owner-run record)

## Task 0.1.7c — A1 pre-check
- Status: WAIVED by the owner on 2026-11-16 ("Skip the pre-check, run the spikes with defaults").
- No list of 5 diagrams, platforms, project kinds, frequency or web-intent was recorded. The 50% bar of metric 8 stays advisory and unmeasured; Option W's keep-or-drop demand rule (`webItems` / `web-intent`) is UNRECORDED, so Task 0.1.8c's recount has nothing to count against.
- Effect: the edge 0.1.7c -> W1 is treated as released. The A1 gate (Task 6.2.4e) is unchanged.

## Task 0.1.7b - canvas reference figures (JVM, VERIFIED; wasmJs and Pixel_Fold NOT run)
- Harness `JT/sketch/v2/baseline/CanvasReferenceBenchmark.kt` (software-raster ImageBitmap 1280x800, 120 frames, 60 warmup). Command: `./gradlew :kmp:jvmTest --tests '*CanvasReferenceBenchmark*'`, BUILD SUCCESSFUL.
- Two result lines in one run: empty p95 0.24 / 0.30 ms; 500 rounded rects p95 14.23 / 13.77 ms (p50 10.39 / 9.54). The ratio p95 is 46x to 59x: the empty canvas is a bare clear, so the plan's "500 elements <= 1.5x empty" rule cannot be met by this definition; the absolute 24 ms ceiling is met (about 14 ms) on this host. The rule needs restating by the owner (INFERRED).
- wasmJs harness `kmp/src/wasmJsTest/.../sketch/v2/baseline/CanvasReferenceWasmBenchmark.kt` written, NOT run: `:kmp:wasmJsBrowserTest` compile failed on another spike's `ui/sketch/v2/WebInputSpikeProbeTest.kt` (js() call not a single expression). Pixel_Fold run is owner-run.

## Task 0.1.7d - regression baseline
`scripts/sketch-baseline/record-sketch-baseline.sh` wrote `implementation/regression-baseline.txt` at baseline-sha db40c57e561f0c3f2d4bf079b303ef6ff0598309 (ancestor of origin/main): 67 test files, 9 golden fixtures. Re-run with that SHA is byte-identical (cmp); a non-ancestor SHA is refused (exit 1); shellcheck clean.
