# ADR-001: One hand-rolled `ResourceProbe` harness, one budgets file, one enforcement script

**Status**: Proposed (Phase 3) | **Date**: 2026-11-08 | **Project**: annotation-memory-and-nav-ia

## Context
The owner reported the kill as "excessive CPU usage" (requirements.md Amendment). Benchmarks must therefore measure CPU and memory from one harness in the same scenarios. Existing precedent is the hand-rolled `AssetPipelineHeapGateTest` plus `android-benchmark.yml` -> `scripts/benchdiff-export.js` -> `tstapler/benchdiff` (research/stack.md section 1, build-vs-buy.md section 1). Memory-only Java-heap gates miss native and graphics memory (stack.md section 3) and CPU numbers inflate 5-7x under `performTouchInput` (cpu.md section 3).

## Options
1. **Hand-rolled `ResourceProbe`** (androidInstrumentedTest, no new deps) + JSON records + budgets file. Strength: runs on the existing API 30 emulator job; we choose metrics. Weakness: we own noise handling and must prove the gate can fail.
2. **Macrobenchmark `MemoryUsageMetric`/`FrameTimingMetric`**. Strength: official, out-of-process. Weakness: separate non-debuggable APK, documented emulator discouragement, UiAutomator-driven drags over a Compose canvas (build-vs-buy.md section 1, VERIFIED URLs there).
3. **Memory-only gate extended from `AssetPipelineHeapGateTest`**. Strength: smallest. Weakness: cannot see the reported failure class (CPU) or graphics memory.

## Decision
Option 1. Probe records per scenario: CPU ms per placement, per-thread CPU share, FrameMetrics jank, sustained CPU %, post-GC Java heap, native heap, PSS (incl. `summary.graphics`), `VmHWM`, Coil decode count/max bytes/cache size, undo depth, ApplicationExitInfo check. Macrobenchmark stays an optional later device-only trace.

**Budgets file**: `benchmarks/budgets/annotation-resource-budgets.json`, one entry per `scenario.metric` with `{budget, mode: "gate"|"report-only", calibratedFromRuns}`. Instrumented tests only EMIT records; `scripts/bench-budget-check.js` is the single evaluator (CI and local), exits non-zero for breached `gate` entries. Promotion or rollback = edit one `mode` value.

**Initial modes** (from stack.md section 7, cpu.md section 4): deterministic metrics (decode count per press, cache size <= maxSize, undo depth, loader-construction count, process survived, no bad exit reason) are `gate` entries from day one. Amended 2026-11-08 (Repair 2): max single decode bytes is NOT in this list (`report-only` until the A.5a spike chooses the loupe ceiling of 2 MB and the cell size is measured for the 4 MB library ceiling). Every `gate` entry is still evaluated as report-only while the file-level `globalMode` is `report-only` (see Repair 2 amendment below). Amended 2026-11-08 (repair pass): post-gesture idle CPU % < 10 starts `report-only` (cold CI emulator spikes; promote on median of 3 windows over >=10 runs), as does sustained CPU % (60%). Report-only until >=10 CI runs: CPU ms/placement, per-thread CPU, jank, retained Java heap, native heap, PSS, VmHWM, idle and sustained CPU %.

## Consequences
- Every gate needs a red proof per the plan's pre-fix proof procedure (Story B.5): probe-only PR on unfixed behaviour, fix-only revert, or a test-only mutation flag; gates with none achievable stay `report-only`. The loader-construction counter lives in the probe-only PR (B.2d), not in the fix. Original wording: (a) a "fails on the pre-fix commit" proof and (b) a mutation check (script fixtures + a deliberate allocation/CPU burn) before its mode is `gate`.
- `benchdiff-export.js` SPECS and `benchdiff-export.test.js` change; the inline Python summarizer in `android-benchmark.yml` is extracted to `scripts/` (isolate-via-seam).
- Rollback: set affected entries to `report-only`; revert the workflow line.
- **Repair 2 amendments (2026-11-08)**:
  - Terminology: JSON values `gate` / `report-only`; Kotlin enum `Gate` / `ReportOnly`; prose uses "gate" and "report-only" (not "hard gate").
  - `BudgetEntry` gains `mutation` (the name of the test-only mutation flag that proves the gate can fail; `bench-budget-check.js --validate` errors on a `gate` entry without one). The mutation list is the full proof-matrix set: `loaderPerPress`, `perDrawMeasure`, `busyWaitMove`, `backgroundBusyLoop`, `leakStrongRef`, `exitOnBackground`, `loupeFullDecode`, `cacheNoLimit`, `undoUnbounded`, `oomInjection`.
  - The file has a top-level `globalMode` (`"report-only"` | `"enforce"`). While `report-only`, no entry can fail the job, whatever its own `mode`. It starts `report-only` and flips to `enforce` in the B.7b promotion PR after the calibration soak (>= 10 runs). Demotion after a flaky gate is the same one-field edit.
  - Bad exit reasons are an explicit allowlist: `CRASH`, `CRASH_NATIVE`, `ANR`, `EXCESSIVE_RESOURCE_USAGE`, `LOW_MEMORY`, `SIGNALED`, filtered to entries whose `pid` is the test process and `timestamp >=` the scenario start. `REASON_OTHER`, `USER_REQUESTED`, `USER_STOPPED` and reinstall/teardown entries are ignored.
  - Coil bumps (Renovate) must rerun the probe: the Coil version is recorded in `ProbeRecord.device`; `CoilSeamTest` asserts the listener fired at least once (so a `<=` gate cannot pass vacuously on zero decodes).

## Epic B implementation amendments (2026-11-09)

- **Coil 3.2.0 spike (B.2a)**, `javap` on `coil-core-jvm-3.2.0.jar`: `coil3.EventListener` is an open class with `onSuccess(ImageRequest, SuccessResult)` and a nested `EventListener.Factory`; `ImageLoader.Builder.eventListenerFactory(EventListener.Factory)` exists; `coil3.Image` exposes `long getSize()` (bytes), `getWidth()`, `getHeight()`; `coil3.memory.MemoryCache` exposes `long getSize()`, `long getMaxSize()`, `clear()`. These are exactly the members `CoilDecodeStats`, `CoilSeamTest` and `ProbeRun` use. A bump that changes them fails `CoilSeamTest` at compile time.
- **Record format**: a `ProbeRecord` is `{scenario, pid, scenarioStartMs, device, metrics{bare names}, exitReasons[]}`, logged as `ANDROID_RESOURCE: <json>`. `scripts/android-benchmark-summarize.py` flattens every record of a run into one summary with `Scenario.metric` keys, which `bench-budget-check.js`, `bench-budget-calibrate.js` and `benchdiff-export.js --kind android-resource` all consume.
- **Budget entry** adds `comparator` (`lte`, `gte`, `eq`) and `memory` (true: skipped, never failing, when the record's `device.invalidDevice` is true). `Process.badExitReasons` is derived by the checker from `exitReasons` (allowlist, pid, timestamp filter); the probe never emits it.
- **Probe catalog**: `ProbeCatalog.kt` lists every key the probe may emit; `--validate` fails a key without an entry, and `ProbeRun.finish` throws on an uncataloged metric.
- **Mutation list** is checked for drift between `Mutations.kt` and the checker by a node test; `scripts/check-no-mutations-in-main.sh` (run in `android-benchmark.yml`) fails if a flag name reaches production sources, replacing the planned dex-grep.
- **Output channel (B.4a)**: logcat `ANDROID_RESOURCE` for CI, plus `probe/<scenario>.json` in the app's external files dir for a local `adb pull`. Host-side retrieval through Gradle's connected-test output was not exercised (no device run in this change).

- **Mutation channel**: `-Pandroid.testInstrumentationRunnerArguments.<custom key>=...` delivered only `class` to the device on the Pixel_Fold run (the test saw `testTimeoutSeconds, class, additionalTestOutputDir`), so a Gradle-passed mutation was a silent no-op. `scripts/ci/run-android-resource-benchmark.sh` therefore installs with `installDebugAndroidTest` and starts `am instrument -e annotateMutation <name> -e expectedRamMb <n>`, which was verified to arrive. `Mutations.active` rejects an unknown name.

### Proof matrix: where each gate's red proof stands

Device runs on the `Pixel_Fold` AVD (API 34, 3.05 GB guest, so tagged `invalidDevice` by the 2 GB pin, as predicted in pitfalls.md), 2026-11-09:

| Gate / entry | Normal run | Mutation run | Evaluator result (enforce) |
|---|---|---|---|
| `LibraryScroll.cacheOverflowBytes` (gate) | 0 | `cacheNoLimit`: 60,154,645 | exit 1 |
| `LibraryScroll.maxDecodeBytes` (report-only, shown promoted) | 1,045,480 | `loupeFullDecode`: 48,000,000 | exit 1 once `mode=gate` |
| `SwitchChurn.editorReferenceCleared` (report-only) | 1 | `leakStrongRef`: 0 (0 of 30 editors cleared) | warn |

Evaluator-only proofs (no device scenario in this epic; the RapidPlacement and BackgroundIdle scenarios are Epic A.3):

| Gate | Evidence | Instrumented mutation |
|---|---|---|
| `RapidPlacement.loaderConstructions` | `ImageLoaderConstructionCountTest` counts 100 builds as 100; the checker exits 1 on 100 | `loaderPerPress`, with the A.3 scenario |
| `RapidPlacement.decodeCountPerPress` | the checker exits 1 above 1 | `loupeFullDecode`, A.3 |
| `RapidPlacement.cacheOverflowBytes` | the checker exits 1 on a positive overflow | `cacheNoLimit`, A.3 |
| `RapidPlacement.undoDepth` | `UndoDepthTest` pins the cap at 50; the checker exits 1 on 51 | `undoUnbounded`, A.3 |
| `Process.survived`, `Process.badExitReasons` | the checker exits 1 on `survived=0` and on an allowlisted exit reason for this pid | `exitOnBackground` / `oomInjection`, A.3 |

Every `gate` in the budgets file stays evaluated as report-only while `globalMode` is `report-only`; the B.7b promotion PR flips it after the calibration soak. The `cacheNoLimit` and `loupeFullDecode` mutations are deliberately bounded (4x the production cache ceiling; a full-size decode every 400th cell): the first attempt used a truly unbounded cache and full decodes of every cell, which killed the process before the record was written.
