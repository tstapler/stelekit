# Cross-Artifact Consistency Check: hikari-pool-lessons

**Date**: 2026-07-01
**Verdict**: INCONSISTENCIES FOUND (4)

## Checks

| Check | Status | Notes |
|---|---|---|
| build-vs-buy → plan | PARTIAL MISMATCH | See details below |
| requirements → plan | MATCH | All 3 success metrics covered; no constraint violated |
| adversarial BLOCKER addressed | YES | Both `rollback()` and `autoCommit = true` are inside a single `try` block in plan.md's Story 1 sketch; broken connection is discarded, not offered back to pool |
| architecture concerns addressed | PARTIAL | In-memory path (Concern 1) addressed; TOCTOU wording (Concern 2) and lazy executor lifecycle (Concern 3) not addressed |
| LoC estimates consistent | MINOR VARIANCE | build-vs-buy.md counted ~120 for 2 patterns; plan.md adds Stories 3+4 (3 more lines) from pitfalls.md, giving ~103–123 total — within range |
| excluded patterns consistent | MATCH | isValid(), max-lifetime, keepalive, dynamic sizing all appear in plan.md Section 4 with matching rationale |
| pitfalls coverage | FULL | All 4 pitfall gaps appear in plan.md; isValid()-on-return vs. on-checkout distinction noted below |
| code baseline accurate | YES | `getOrPut` confirmed in `addListener` (line 132); `closeConnection` confirmed missing autoCommit check (lines 97–101); file-DB `getConnection` confirmed missing `closed` check (line 76) |

---

## Inconsistencies Found

### 1. TOCTOU wording not updated in plan.md Story 4 (REQUIRED change per adversarial review)

**Filed as "Required Plan Change #2"** in `adversarial-review.md`:
> Replace "positioned before `pool.poll()` to avoid a TOCTOU window" with "reduces the TOCTOU window to near-zero."

`architecture-review.md` (same concern):
> "The acceptance criteria should say 'significantly reduces the window' rather than imply elimination."

**Current plan.md Story 4 acceptance criteria** (line 227):
> "The check is positioned before `pool.poll()` to avoid a TOCTOU window between the poll and the overflow creation."

The language still implies elimination ("to avoid"). Neither review's required wording change was applied. The adversarial review explicitly marked this as a required change before the PR is opened.

---

### 2. Lazy executor lifecycle concern not addressed in plan.md Story 2

`architecture-review.md` (Concern: "Lazy executor init in `close()`"):
> If `leakThresholdMs > 0` but no connection was ever checked out, `close()` accessing `leakScheduler` creates a fresh `ScheduledThreadPoolExecutor` and immediately shuts it down — wasteful. Recommended fix: `if (leakThresholdMs > 0 && (::leakScheduler as Lazy<*>).isInitialized()) leakScheduler.shutdown()`.

**Current plan.md Story 2 sketch** (line 154):
> `if (leakThresholdMs > 0) leakScheduler.shutdown()`

The architecture review raised this as a concern; the plan sketch was not updated to use the `isInitialized()` guard or an `AtomicBoolean` alternative. The architecture review's open question #3 asked whether to use `isInitialized()` or a separate flag — neither was resolved in the plan.

---

### 3. HikariCP version discrepancy between plan.md and build-vs-buy.md

`build-vs-buy.md` (line 32): identifies the evaluated version as **HikariCP 7.0.2** (released 2025-08-19).

`plan.md` Decision Summary (line 17): refers to **HikariCP 7.1.0**.

The version in plan.md is not the version that was evaluated in the build-vs-buy research. If any HikariCP behavior cited in plan.md's exclusion rationale differs between 7.0.2 and 7.1.0, the rationale could be subtly wrong. Minor in practice (no known 7.0.x → 7.1.x API changes affect the cited issues), but the artifact trail is inconsistent.

---

### 4. plan.md adds 2 "Adopt Now" stories not in build-vs-buy.md's explicit recommendation

`build-vs-buy.md` Prioritized Backlog lists exactly 2 "Adopt now" items:
1. autoCommit reset (~20 lines)
2. Leak detection (~80–100 lines)

`plan.md` Section 1 "Final Adopt Now List" has 4 items — Stories 3 (`computeIfAbsent`) and 4 (`closed.get()` guard) are sourced from `pitfalls.md` Recommended Fixes #1 and #2, not from build-vs-buy.md. The additions are correct and supported by pitfalls research, but build-vs-buy.md's prioritized backlog does not enumerate them, creating a gap if someone reads only build-vs-buy.md to understand what was decided.

This is an additive inconsistency rather than a contradiction.

---

## Notes (Non-blocking)

**isValid() on return vs. on checkout**: `pitfalls.md` (section 1) recommends `isValid()` in `closeConnection()` (validating before re-enqueue). `plan.md` Section 4 excludes `Connection.isValid()` under the label "on checkout" with rationale about checkout latency. The plan does not explicitly address the return-path variant recommended by pitfalls.md. The exclusion rationale could be read to apply only to checkout, leaving the return-path use case unaddressed rather than explicitly deferred. A one-line note in the exclusion entry would close this ambiguity.

**isValid() implementation disagreement across research files**: `features.md` (section 3.2) states xerial's `isValid()` is "a purely in-process JNI handle check — does NOT send any bytes to a remote server." `pitfalls.md` (section 1) and `build-vs-buy.md` state it "executes `SELECT 1`" / "adds one SELECT per checkout." `plan.md` adopts the build-vs-buy.md framing ("executes `SELECT 1` — real SQL, not a handle check"). The discrepancy is in the research layer; plan.md is internally consistent with its chosen source, but the research files contradict each other on this fact.
