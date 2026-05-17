# Requirements: Android Block Insert Performance

## Problem Statement

Block insert operations on Android take 1–2 seconds — new block creation, page reference insertion, indent/outdent, and clipboard paste all exhibit this lag. The same operations are near-instant on the JVM/Desktop target. The suspected root cause is Android filesystem (Storage Access Framework / SAF) latency in the write path. The goal is to diagnose the bottleneck, fix it, and add CI-runnable benchmarks that enforce performance over time.

## Scope

### In scope
- All block insert operations: new block (Enter/+), page reference insert (`[[`), indent/outdent, paste
- Android target (API 30+, physical device)
- Write path from `BlockStateManager` → `GraphWriter` → file system
- SQLDelight write path on Android
- JVM-side benchmark harness that simulates Android SAF latency for CI use
- Performance regression guardrails in CI

### Out of scope
- Read performance (page load, search)
- iOS / Web targets
- UI rendering / composition performance
- Block count > 10k per page (large-graph scenario is a separate benchmark)

## Functional Requirements

### FR-1: Diagnose bottleneck
Identify whether the 1–2 second lag comes from:
- (a) SQLite write via SQLDelight on Android
- (b) Markdown file write via Android SAF / `DocumentFile` API
- (c) The round-trip through `DatabaseWriteActor` serialization queue
- (d) UI-thread work (coroutine dispatch overhead, recomposition)

### FR-2: Fix the bottleneck
Implement the fix targeting the identified root cause without breaking JVM behavior:
- If SAF file write is the cause: batch or defer file writes; consider async write with in-memory state as source of truth
- If DB write is the cause: profile SQLDelight query plan; add indices if needed; reduce write surface
- If actor queue is the cause: reduce unnecessary serialization or add fast-path for simple inserts

### FR-3: Benchmark — simulated Android SAF latency (CI-runnable)
Create a JVM benchmark (in `jvmTest` or a dedicated `benchmarkTest` source set) that:
- Simulates the full block insert pipeline end-to-end
- Injects a configurable filesystem latency shim that mimics Android SAF (~10–50 ms per write, worst case 200 ms)
- Measures wall-clock latency from user action to "state committed" (DB write complete + file write dispatched)
- Can run in CI without an Android device or emulator
- Produces a JFR or structured output compatible with the existing benchmark infrastructure

### FR-4: Benchmark — real Android (optional, device-required)
Create an Android Instrumentation test or Macrobenchmark that measures the same insert operations on a real device. Not required for CI but must be runnable locally.

### FR-5: Performance budget enforcement
Define a performance budget for block insert operations:
- P50 latency ≤ 50 ms (UI thread to write dispatched)
- P99 latency ≤ 200 ms
- CI benchmark should FAIL the build if P99 exceeds budget
- Budget applies to the simulated-latency JVM benchmark (FR-3)

## Non-Functional Requirements

### NFR-1: No JVM regression
The fix must not degrade JVM/Desktop write latency. JVM benchmark P99 must remain ≤ 50 ms without the SAF latency shim.

### NFR-2: Data integrity
A deferred or async file write must never lose committed blocks. If the process is killed after a DB write but before the file write, the DB is the source of truth and the next launch must regenerate the markdown file from DB state.

### NFR-3: Existing tests must pass
`./gradlew ciCheck` must pass green after the fix.

## Acceptance Criteria

- [ ] Root cause identified and documented (ADR or inline comment)
- [ ] Block insert latency reduced to imperceptible (<100 ms) on Android physical device
- [ ] JVM benchmark (FR-3) exists and runs in `./gradlew jvmTest` or equivalent
- [ ] CI fails if P99 insert latency (simulated SAF) exceeds 200 ms
- [ ] `./gradlew ciCheck` passes
- [ ] No data loss under simulated process kill after DB write

## Constraints

- Kotlin Multiplatform — fixes must be expressed as `expect/actual` or platform-specific in `androidMain` without breaking `commonMain` contracts
- SQLDelight 2.3.2 — cannot upgrade as part of this fix
- Arrow `Either` error model must be preserved at all repository boundaries
- `DatabaseWriteActor` pattern must be preserved (do not bypass the actor)
