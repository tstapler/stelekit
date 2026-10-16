# Validation: annotate-ui-test-coverage

## Adversarial Review

### Concern 1: Coverage targets are optimistic for `BleDevicePanel`

The plan concedes `BleDevicePanel` will only reach ~20% coverage because `formatMeters` is private and the `LaunchedEffect` scanning logic depends on `MeasurementDeviceRegistry` global state. The 75% overall line target may be missed if `BleDevicePanel`'s 209 lines remain mostly uncovered.

**Resolution:** The overall package target (75%) is achievable without covering `BleDevicePanel` fully — it contributes only 209/2648 lines (8%). Covering the other gaps fully brings the package to ~73-78%. The `formatMeters` promotion to `internal` (1-line change) is low risk and covers the most testable logic. The `LaunchedEffect` / `MeasurementDeviceRegistry` dependency is a genuine integration concern; testing it requires either mocking the global or testing in an instrumented environment. **Accept this gap — log it as a follow-up to extract `BleDevicePanel`'s scanning logic into a injectable collaborator.**

### Concern 2: Robolectric test location (`androidUnitTest`) may not run on all CI jobs

Per `CLAUDE.md`, `bazel test //kmp:jvm_tests` is the CI gate, but `androidUnitTest` runs via `./gradlew testDebugUnitTest` separately. The PR #402 coverage gate uses JaCoCo from `:kmp:jvmTest` run only — not merged with Robolectric data. New Robolectric tests will improve developer coverage locally but not be reflected in the JaCoCo gate numbers from `jvmTest`.

**Resolution:** The primary coverage gains targeted in Streams 1–3 are in `commonTest` (which runs under `jvmTest`) and will appear in the JaCoCo gate. The Robolectric tests in Stream 5 add real coverage of the composables and satisfy ACs 6-8, even if they don't move the JaCoCo gate number immediately. Document this split in the PR description. **Accept: the plan's 75% overall target uses the JaCoCo-measured numbers, and Streams 1–3 alone bring the package to ~68%; Streams 4–5 close to ~75%.**

### Concern 3: `initialize` TOCTOU test is complex and may be flaky

Testing the `hasBeenMutated` guard requires racing `initialize`'s repository coroutine against `commitAnnotation`. Using `UnconfinedTestDispatcher` with a fake repository that suspends mid-flight is non-trivial.

**Resolution:** Use a `DelayedMeasurementRepository` fake that suspends for 1ms before returning. With `runTest { ... }` and `advanceUntilIdle()`, the coroutine ordering is deterministic. The test scenario: (1) call `initialize`; (2) before `advanceUntilIdle`, commit an annotation; (3) then advance — the repository results must not overwrite the committed annotation. This is a well-defined invariant and is deterministic in a test scheduler. **Accept with the delayed-fake pattern.**

### Concern 4: `DepthEstimationCoordinator.close()` cancel test

Verifying that `close()` cancels the scope is hard to assert directly since Kotlin coroutines don't expose a public `isActive` from outside the scope. The test would need to verify that `runDepthEstimation` called after `close()` does not invoke the estimator.

**Resolution:** Use a `FakeMonocularDepthEstimator` that records invocations. After `close()`, call `runDepthEstimation(mockBitmap)`, `advanceUntilIdle()`, and assert `invocationCount == 0`. This works because the scope is cancelled so the `scope.launch { ... }` inside `runDepthEstimation` is a no-op. **Accept.**

### Concern 5: Stream 4 requires a production source change (`internal` visibility)

Making `formatMeters` `internal` changes the file, which could affect downstream tooling or API surface. For a `private` fun in a Compose file, this is extremely low risk — `internal` means KMP-module-internal, not public API. The alternative (testing `formatMeters` through a full Robolectric render of `BleDevicePanel.ReadingReceived` state) is technically possible but would hit the `MeasurementDeviceRegistry` dependency.

**Resolution:** The 1-line change to `internal fun formatMeters` is acceptable. Add a `// @VisibleForTesting` comment above it. **Accept.**

## Validation Verdict

The plan is sound. The approach correctly:
- Prioritizes ViewModel and coordinator tests (highest line count, compile-time-safe, platform-independent)
- Uses Robolectric appropriately for Compose UI assertions
- Acknowledges the BLE scanning global dependency without papering over it
- Proposes a minimal production change (1 line)
- Provides deterministic test patterns for async ViewModel methods

**Estimated total new test count: ~55 tests across 6 new files (and extensions to 1 existing file)**

**PR size:** 6 new test files + 1 line change in `BleDevicePanel.kt` = low risk.
