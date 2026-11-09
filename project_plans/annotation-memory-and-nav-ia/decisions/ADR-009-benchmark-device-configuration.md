# ADR-009: Gates run on a pinned, recorded device configuration; the largeHeap conflict is resolved explicitly

**Status**: Proposed | **Date**: 2026-11-08

## Context
VERIFIED (stack.md section 1): shipped `androidApp` manifest sets `largeHeap="true"`, while `AssetPipelineHeapGateTest` asserts largeHeap OFF and `maxMemory <= 512 MB` on the `kmp` test APK. The research AVD ignored `-memory 2048` and reported 3 GB (pitfalls.md Part B VERIFIED). `android-benchmark.yml` sets no RAM; `android-smoke` pins `-memory 2048` on API 29.

## Decision
1. Resource scenarios run in the `kmp` test APK (largeHeap off, stricter than shipped) in `android-benchmark.yml` (API 30). Every record includes `memoryClass`, `largeMemoryClass`, `isLowRamDevice`, `heapgrowthlimit`, `MemTotal`, API level, GPU mode. The shipped-config difference is documented in the budgets file header and the PR comment legend.
2. AVD RAM is pinned through `ram-size`/`emulator-options` and verified at job start by reading `MemTotal`; if the value differs from the pin by more than 10%, the job marks all memory metrics `invalid-device` (report-only) instead of failing.
3. The crash-class pass/fail test (placement burst, no exit reason, no uncaught exception) additionally runs in `android-smoke` (API 29, `-memory 2048`), where the `junit-report` action already fails the job.
4. Graphics PSS under swiftshader is report-only always (stack.md section 6).

## Consequences
- Budgets are emulator-relative regression detectors, not claims about the owner's phone. A real low-RAM phone run is an unresolved wall-clock item.
