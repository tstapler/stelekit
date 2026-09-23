# Validation: hikari-pool-lessons plan

**Date**: 2026-07-01
**Verdict**: READY (with 3 minor gaps — none are blockers)

---

## Coverage Matrix

| Story | AC Complete? | Tests Complete? | Error Path Covered? |
|---|---|---|---|
| Story 1: autoCommit reset | Y | Y — except broken-connection path | N (see Gap 1) |
| Story 2: Leak detection | Y | Y — except in-memory path and multi-connection AC | Partial (see Gap 2) |
| Story 3: computeIfAbsent | Y | Y | N/A — correctness fix, no error path |
| Story 4: closed guard | Y | Y | Y |

---

## Requirements Traceability

| Success Metric | Satisfied by |
|---|---|
| 1. Prioritized backlog (Adopt now / Adopt later / Not applicable + reason) | Section 1 "Final Adopt Now List" (4 items) + Section 4 "Patterns Explicitly Excluded" (7 items with rationale, including "Adopt later" and "Not applicable" distinctions). ✓ |
| 2. Adopt-now stories with AC | Stories 1–4 each have a numbered Acceptance Criteria block. All 4 "Adopt now" items are covered. ✓ |
| 3. Zero false positives (excluded with rationale) | Section 4 documents 7 excluded patterns. Each has a single-sentence rationale tied to SQLite WAL / fixed-pool design. ✓ |

---

## Gaps Found

### Gap 1 (Story 1): Broken-connection test case missing

The implementation sketch explicitly adds a `try/catch` that discards a connection when `rollback()` or `setAutoCommit(true)` throws. The rationale is sound: a broken connection must not re-enter the pool. However, the test strategy lists three scenarios and none of them covers this path:

- "autoCommit reset on dirty return — next checkout is clean" ← happy dirty path
- "dirty return issues rollback — previous DML is not visible" ← rollback behavior
- "clean return has no extra JDBC call" ← clean path

**Missing test**: "broken connection on setAutoCommit reset is discarded — pool shrinks by 1 slot, no IllegalStateException on next checkout from remaining slots."

Without this test, the `catch` branch is dead code from a coverage standpoint. If the `return` is accidentally dropped or the `catch` is widened to re-offer the connection, the bug the guard was designed to prevent will re-appear silently.

**Recommendation**: Add one test — mock or wrap the connection to throw on `setAutoCommit(true)`, call `closeConnection()`, verify the connection is not re-offered (pool count drops), and verify the next `getConnection()` from a different slot succeeds.

---

### Gap 2 (Story 2): Leak detection test strategy is incomplete on two sub-points

**2a — In-memory path not explicitly tested.** The implementation note says leak detection must cover BOTH the file-DB and in-memory paths (`getConnection()` has two branches, both ending in `return conn`). The test strategy names four scenarios but all are written against a generic "driver with leakThresholdMs=100" — it's not specified whether the test driver uses a file URL or an in-memory URL. If the `registerLeakTask` helper call is added to only one branch, the in-memory path will silently go undetected.

**Recommendation**: One of the four test scenarios (e.g. "leak detector fires after threshold") should be duplicated with an in-memory driver, or the test should use an in-memory driver by default and add a file-driver variant for the "no leak on return" scenario.

**2b — AC5 (8 simultaneous connections, independent tasks) has no test.** AC5 states: "Leak detection is independent per connection: 8 simultaneous outstanding connections each get their own task." No test exercises this invariant. A `ConcurrentHashMap` keyed by `Connection` identity should handle this correctly, but a regression test prevents a future refactor from collapsing tasks by connection index or thread name.

**Recommendation**: Add one test — check out all 8 connections simultaneously (or use a 2-connection pool for simplicity); sleep past threshold; verify the WARNING log fires once per connection (2 or 8 warnings), each with a distinct connection identity hash.

---

### Gap 3 (Minor): `schedule()` O(1) claim is technically O(log n) — bounded and acceptable

The Performance SLO in requirements.md requires pool operations to remain O(1). `ConcurrentHashMap.put` is O(1) amortized. However, `ScheduledThreadPoolExecutor.schedule()` inserts into a binary heap — O(log n) where n is the number of outstanding scheduled tasks. With at most 8 connections in the pool, n ≤ 8, so this is O(log 8) = 3 comparisons — effectively O(1) in practice.

This does not violate the spirit of the SLO, but the plan does not acknowledge it. If someone later increases `poolSize` to 64, the claim remains accurate. No change is required — document-only note.

---

## Implementation Risks

Risks already in plan.md (not repeated here): WAL PRAGMA delivery incompatibility ruling out HikariCP; in-memory lifetime incompatibility; autoCommit behavioral note (one JNI call per closeConnection); leakThresholdMs=0 default; Story 2 sleep-based test flake risk acknowledged.

**New risks not in plan.md:**

1. **Stack trace capture cost (Story 2).** `new Exception("...")` to capture a checkout stack trace triggers JVM stack walking — typically 100–500 µs per checkout when `leakThresholdMs > 0`. This is inside the `if (leakThresholdMs > 0)` branch so it is zero-cost when disabled (the default). In production use with `leakThresholdMs = 5_000L` enabled, the cost per checkout is real but small relative to SQLite round-trip latency. Not a blocker, but the rollout notes in Section 6 should mention it.

2. **Connection identity as map key (Story 2).** `leakTasks` uses `Connection` object identity as the key. This is correct for the current implementation (no proxy wrapping). If a future change wraps connections in a proxy (e.g., for open-statement tracking from the "Adopt later" list), the `remove(connection)` in `closeConnection()` will fail to find the original key — causing the scheduled task to never be cancelled and a false-positive WARNING after the threshold. The "Adopt later" open-statement tracking story should be flagged to revisit this.

3. **Pool-size shrink is permanent (Story 1).** When a broken connection is discarded in the `catch` block, the plan states "pool shrinks by 1 slot, but this is the correct outcome (no replacement logic planned; size recovers on driver restart)." This is correct and acceptable, but the WARNING log message should include "pool size permanently reduced to N" rather than the generic discard message, so operators can detect sustained shrinkage. Minor UX improvement, not a functional gap.
