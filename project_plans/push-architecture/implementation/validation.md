# Validation: Push-Based Block State Architecture

## Test Suite — Requirement Traceability

| AC | Criterion (short) | Test name | Type | Story | Assertion |
|----|-------------------|-----------|------|-------|-----------|
| AC-1 | N-page fan-out — at most 1 read for written page, 0 for others | `fanOut_singlePageWrite_triggersOneReadForWrittenPageZeroForOthers` | Integration | 4.3 | `CountingBlockRepository` counter for page A = 1; counters for pages B–E = 0 after emitting `setOf(pageUuidA)` |
| AC-1 | N-page fan-out — wildcard emits exactly one read per observed page | `fanOut_wildcardInvalidation_triggersOneReadPerObservedPage` | Integration | 4.3 | All 5 page counters = 1 after emitting `setOf(WILDCARD_PAGE_UUID)` |
| AC-1 | N-page fan-out — multi-page set emits reads only for named pages | `fanOut_multiPageSetInvalidation_triggersReadsOnlyForNamedPages` | Integration | 4.3 | Counters for A and B = 1; counters for C/D/E = 0 after emitting `setOf(A, B)` |
| AC-2 | `blockObserveDebounceMs` parameter deleted from `BlockStateManager` | `blockObserveDebounceMs_doesNotExistInConstructor` | Unit | 2.5 | Compilation succeeds with no `blockObserveDebounceMs` parameter on `BlockStateManager`; grep confirms zero references across all source sets |
| AC-2 | `blockObserveDebounceMs` usage deleted from `App.kt` | `app_blockStateManager_construction_hasNoDebounceArg` | Unit | 2.3 | `App.kt` does not pass `blockObserveDebounceMs` at the `BlockStateManager` construction site |
| AC-3 | External file change reflects in UI within 2 seconds | `externalFileChange_reflectsInBlocks_withinTwoSeconds` | Integration | 4.4 | After writing a modified markdown file to disk and waiting up to 2 s, `BlockStateManager._blocks[pageUuid]` contains updated content from the new file |
| AC-3 | External file change uses Phase 1 invalidation path (not Phase 2 push) | `externalFileChange_via_saveBlocks_should_NOT_emit_BlocksWritten` | Integration | 3.1 | `blocksPushed` emits zero events when `saveBlocks` is called via `GraphLoader` external-change path; `blockInvalidations` emits `setOf(pageUuid)` |
| AC-4 | Converted BSM tests count does not decrease | `existingBsmTests_totalCountNotDecreased` | Unit | 2.4 | Test count in `BlockStateManagerTest` after conversion >= pre-migration count; no test file deleted |
| AC-4 | Converted BSM tests all pass with invalidation-based model | (all BSM tests in `BlockStateManagerTest`) | Unit | 2.4 | All pre-existing `BlockStateManager` tests pass after conversion from reactive-re-emission to invalidation-signal or optimistic-update path |
| AC-5 | In-app block edit triggers zero `getBlocksForPage` calls | `inApp_blockEdit_should_trigger_zero_getBlocksForPage_calls` | Integration | 3.2 | `CountingBlockRepository` records 0 calls to `getBlocksForPage` for the edited page after a `WriteBlockContent` write; state updated via `blocksPushed` only |
| AC-5 | Push source preserves dirty local edits | `pushSource_should_preserve_dirty_local_edits` | Unit | 3.2 | When user has an unsaved dirty edit in `_blocks` and a `BlocksWritten` push arrives, the dirty content is preserved in the merge output |
| AC-5 | Null push source falls back to Phase 1 invalidation path | `pushSource_null_should_fall_back_to_invalidation_path` | Unit | 3.2 | With `pushSource = null`, `BlockStateManager` still updates `_blocks` via `blockInvalidations` signal (Phase 1 behavior unchanged) |
| AC-6 | Background indexing re-render events measurably reduced | `backgroundIndexing_nonImportedPages_receiveZeroGetBlocksCalls_afterPhase2` | Integration | 3.3 / 4.3 | With 5 pages observed and a 500-block background import running, `CountingBlockRepository` records 0 `getBlocksForPage` calls for the 5 observed pages (actor emits `PagesInvalidated` for imported pages only; `blocksPushed` is not emitted for bulk import) |
| AC-6 | Benchmark baseline and post-Phase-2 data committed | `benchmark_history_files_exist_for_before_and_after` | Benchmark | 3.3 | Two JSON files exist in `benchmarks/history/` with matching naming convention; post-Phase-2 `getBlocksForPage` call count for non-imported pages is 0 |
| AC-7 | Graph switch clears pushed state; no stale blocks to new graph | `graphSwitch_oldActorInvalidation_notDeliveredToNewBSM` | Integration | 4.2 | After `switchGraph(B)`, an emission on the old graph A actor's `blockInvalidations` is not received by the new graph B `BlockStateManager`; `_blocks` for graph B shows only graph-B data |
| AC-7 | Old BSM observation jobs cancelled after `close()` | `graphSwitch_oldBSM_observationJobsCancelledAfterClose` | Integration | 4.2 | `BlockStateManager.observationJobs.isEmpty()` is true after `close()` is called on the old BSM |
| AC-7 | In-flight write during graph switch does not crash | `graphSwitch_inflight_write_on_old_actor_does_not_crash` | Integration | 4.2 | Old actor emits `blockInvalidations` after `switchGraph` starts; no exception thrown; the stale emission is silently dropped |

---

## Additional Tests (not AC-mapped but required by plan stories)

These tests are called out by the plan's "Tests required" sections and cover correctness of individual components. They are required by plan completeness but are not direct AC validators.

| Test name | Type | Story | Purpose |
|-----------|------|-------|---------|
| `actor_saveBlocks_should_emit_correct_page_uuids` | Unit | 1.2 | `SaveBlocks` for pages A and B emits `setOf(A, B)` |
| `actor_saveBlocksDiff_should_emit_correct_page_uuids` | Unit | 1.2 | Inserts for A, updates for B → `setOf(A, B)` |
| `actor_deleteBlocksForPage_should_emit_single_page_uuid` | Unit | 1.2 | Emits `setOf(pageUuidA)` |
| `actor_deleteBlocksForPages_should_emit_all_page_uuids` | Unit | 1.2 | Emits superset of `{A, B}` |
| `actor_execute_should_emit_wildcard` | Unit | 1.2 | `processExecute` emits `setOf(WILDCARD_PAGE_UUID)` before `deferred.complete` |
| `actor_execute_should_emit_wildcard_even_on_failure` | Unit | 4.1 | `Execute` lambda returns `Left(error)`; wildcard still emitted |
| `actor_savePage_should_not_emit_block_invalidation` | Unit | 1.2 | `SavePage` emits zero events on `blockInvalidations` |
| `actor_coalesced_saveBlocks_should_emit_union_of_page_uuids` | Unit | 4.1 | Two coalesced `SaveBlocks` batches emit union of both pages' UUIDs |
| `writeBlockContent_should_emit_pageUuid` | Unit | 2.1 | Typed `WriteBlockContent` request emits `setOf(pageUuid)`, not wildcard |
| `deleteBlock_typed_should_emit_pageUuid` | Unit | 2.1 | Typed `DeleteBlock` request emits `setOf(pageUuid)` |
| `splitBlock_typed_should_emit_pageUuid` | Unit | 2.1 | Typed `SplitBlock` request emits `setOf(pageUuid)` |
| `mergeBlocks_typed_should_emit_pageUuid` | Unit | 2.1 | Typed `MergeBlocks` request emits `setOf(pageUuid)` |
| `observePage_should_refetch_when_itsOwnPageIsInvalidated` | Unit | 2.2 | Emit `setOf(pageUuidA)`; assert `getBlocksForPage` called once for A |
| `observePage_should_notRefetch_when_differentPageIsInvalidated` | Unit | 2.2 | Emit `setOf(pageUuidB)`; assert zero calls for A after initial pull |
| `observePage_wildcard_should_refetch_all_active_pages` | Unit | 2.2 | Emit wildcard with A and B observed; assert both re-queried |
| `observePage_should_not_refetch_after_unobservePage_keepalive_expires` | Unit | 2.2 | Unobserve A, advance clock past keepalive, emit invalidation; assert zero calls |
| `observePage_collectLatest_should_cancel_inflight_query_on_rapid_invalidation` | Unit | 2.2 | Three rapid signals for page A; assert at most one completed query |
| `observePage_initialPull_should_not_require_invalidation_signal` | Unit | 2.2 | No signal emitted; assert `_blocks` populated from initial pull |
| `observePage_mergeBlocks_should_preserve_dirty_local_edits_on_invalidation` | Unit | 2.2 | Dirty block in `_blocks`, then invalidation signal; dirty content preserved |
| `writeBlockContent_should_emit_BlocksWritten_with_correct_blocks` | Unit | 3.1 | After `WriteBlockContent`, `blocksPushed` emits `BlocksWritten` for correct page with updated list |
| `saveBlocks_should_NOT_emit_BlocksWritten` | Unit | 3.1 | Bulk import path emits only `PagesInvalidated`; zero `BlocksWritten` events |

---

## Test Case Counts

| Type | Count |
|------|-------|
| Unit | 28 |
| Integration | 11 |
| Benchmark | 1 |
| **Total** | **40** |

The 28 unit tests include: 7 from AC traceability table + 21 additional plan-required tests.
The 11 integration tests include: all AC-mapped integration tests (AC-1 ×3, AC-3 ×2, AC-5 ×1, AC-6 ×1, AC-7 ×3) and 1 additional integration test (AC-4 BSM conversion check, handled implicitly by running the full BSM test suite).
The 1 benchmark is the data-collection run in Story 3.3.

---

## Requirements Coverage

- AC-1: covered by `fanOut_singlePageWrite_*`, `fanOut_wildcardInvalidation_*`, `fanOut_multiPageSetInvalidation_*` (Story 4.3, integration)
- AC-2: covered by `blockObserveDebounceMs_doesNotExistInConstructor` (Story 2.5, unit) + `app_blockStateManager_construction_hasNoDebounceArg` (Story 2.3, unit)
- AC-3: covered by `externalFileChange_reflectsInBlocks_withinTwoSeconds` (Story 4.4, integration) + `externalFileChange_via_saveBlocks_should_NOT_emit_BlocksWritten` (Story 3.1, integration)
- AC-4: covered by BSM test-conversion completeness check in Story 2.4 (count gate) + full BSM test suite passing (all unit tests)
- AC-5: covered by `inApp_blockEdit_should_trigger_zero_getBlocksForPage_calls` (Story 3.2, integration) + `pushSource_should_preserve_dirty_local_edits` + `pushSource_null_should_fall_back_to_invalidation_path` (both Story 3.2, unit)
- AC-6: covered by `backgroundIndexing_nonImportedPages_receiveZeroGetBlocksCalls_afterPhase2` (Stories 3.3/4.3, integration) + benchmark data files in `benchmarks/history/`
- AC-7: covered by `graphSwitch_oldActorInvalidation_notDeliveredToNewBSM`, `graphSwitch_oldBSM_observationJobsCancelledAfterClose`, `graphSwitch_inflight_write_on_old_actor_does_not_crash` (all Story 4.2, integration)

**Coverage: 7/7 (100%)**

---

## Implementation Readiness Gate

### Criterion 1 — Requirements completeness

| AC | Testable? | Test count |
|----|-----------|------------|
| AC-1 | Yes | 3 |
| AC-2 | Yes | 2 |
| AC-3 | Yes | 2 |
| AC-4 | Yes | BSM suite + count gate |
| AC-5 | Yes | 3 |
| AC-6 | Yes — as call-count gate, not wall-clock (see note) | 2 |
| AC-7 | Yes | 3 |

**Note on AC-6**: The adversarial review (CONCERN-6) correctly identified that "measurably reduced" with no threshold is unmeasurable. The test suite resolves this by asserting `getBlocksForPage` call count = 0 for non-imported pages during bulk import (quantitative, not time-based). This satisfies the intent of AC-6 without relying on fragile wall-clock measurements in CI. The benchmark history files provide the narrative before/after; the call-count assertion provides the enforcement gate.

| Criterion | Result | Notes |
|-----------|--------|-------|
| Requirements completeness | **PASS** | All 7 ACs have at least one test; AC-6 converted from unmeasurable time threshold to measurable call-count gate per CONCERN-6 recommendation |

---

### Criterion 2 — Plan completeness

Each story's "Done when" and test list check:

| Story | "Done when" present? | Test list present? |
|-------|---------------------|-------------------|
| 1.1 | Yes ("File compiles on all KMP targets") | Yes (transitively covered; explicitly noted) |
| 1.2 | Yes ("All actor emission unit tests pass; CI green") | Yes (6 named tests) |
| 2.1 | Yes ("updateBlockContentOnly, saveBlock, … each enqueue typed requests") | Yes (4 named tests) |
| 2.2 | Yes ("observePage no longer holds standing asFlow subscription") | Yes (7 named tests) |
| 2.3 | Yes ("App.kt compiles without debounce argument; smoke-test runs are clean") | Yes (AC-7 via Story 4.2; noted explicitly) |
| 2.4 | Yes ("No BSM test relies on reactive SQLDelight re-emission; all tests pass") | Yes (conversion completeness check) |
| 2.5 | Yes ("blockObserveDebounceMs does not appear anywhere in codebase") | Yes (all existing BSM tests pass — pure deletion) |
| 3.1 | Yes ("Actor emits BlocksWritten for all five typed arms; bulk/external unchanged") | Yes (3 named tests) |
| 3.2 | Yes ("AC-5 passes; getBlocksForPage call count for hot-path edits = 0") | Yes (3 named tests) |
| 3.3 | Yes ("Before/after data exists; benchmark committed to history") | Yes (data-collection + call-count gate per CONCERN-6 resolution) |
| 4.1 | Yes ("All actor unit tests pass; file committed to commonTest") | Yes (2 named tests beyond Story 1.2 list) |
| 4.2 | Yes ("Both graph-switch scenarios pass; AC-7 green") | Yes (3 named tests) |
| 4.3 | Yes ("All three variants pass; AC-1 green") | Yes (3 named variants) |
| 4.4 | Yes ("AC-3 green; external file change path confirmed") | Yes (at least 1 integration test) |

| Criterion | Result | Notes |
|-----------|--------|-------|
| Plan completeness | **PASS** | All 14 stories have "Done when" criteria and test lists; the plan was updated after adversarial review to add Story 2.5 which was previously missing |

---

### Criterion 3 — No unresolved BLOCKERs

The adversarial review verdict was **BLOCKED** with 4 critical issues (CRIT-1 through CRIT-4). The plan's final section "Adversarial Review Patches Applied" records that all 4 patches were incorporated:

| Critical Issue | Patch Applied? | Where in plan |
|---------------|----------------|---------------|
| CRIT-1: `writeActor` absent from BSM App.kt construction | Yes | Story 2.3 updated: "add TWO parameters: `writeActor = repos.writeActor` and `invalidationSource = repos.writeActor?.blockInvalidations`" |
| CRIT-2: AC-2 deprecate-vs-delete contradiction | Yes | Story 2.5 added to Epic 2; AC-2 satisfaction deferred to Story 2.5 sequencing (2.2 → 2.3 → 2.5) |
| CRIT-3: 6 uncovered `getBlocksForPage` call sites in BSM | Yes | Story 2.1 expanded with explicit audit task; double-pull decision documented in Flagged Decisions |
| CRIT-4: `processExecute` emit location semantically wrong | Yes | Story 1.2 corrected: emit placed BEFORE `deferred.complete`, not anchored to absent `onWriteSuccess` |

The 6 CONCERN-level issues (CONCERN-1 through CONCERN-6) are non-blocking by the adversarial review's own classification. CONCERN-4 (AC-4 wording) was patched in requirements.md as recommended. CONCERN-6 (AC-6 unmeasurable) is resolved in this validation document by converting to a call-count gate.

CONCERN-2 (Phase 2 `blocksPushed` startup race) and CONCERN-3 (stale-read hazard in `mergeBlock`/`handleBackspace`) are pre-existing concerns documented as risks, with mitigations described but no new stories added. These are acceptable to carry as known risks into implementation with documented test coverage for the primary case.

| Criterion | Result | Notes |
|-----------|--------|-------|
| No unresolved BLOCKERs | **PASS** | All 4 CRIT-level issues patched in the plan; 6 CONCERN-level issues acknowledged; 2 observations informational only. Effective post-patch verdict: CONCERNS (all critical issues resolved) |

---

### Criterion 4 — Sequencing safety

| Story | Safe to land independently? | Dependency |
|-------|-----------------------------|------------|
| 1.1 — `BlockUpdateEvent` sealed class | Yes — purely additive; no subscribers yet | None |
| 1.2 — Actor emits `blockInvalidations` | Yes — additive; `BlockStateManager` not yet subscribed; no behavior change | 1.1 |
| 2.1 — Typed `WriteRequest` subclasses | Yes — actor behavior change only; BSM still uses `Execute` path; `processExecute` wildcard still fires; observable behavior unchanged | 1.2 |
| 2.2 — `BlockStateManager` subscribes to `invalidationSource` | **Conditional**: safe only if `invalidationSource = null` is the default and tests construct BSM without the parameter. The constructor default is `null`, which means tests using `InMemoryBlockRepository` (no actor) continue working. App.kt still passes `null` until Story 2.3 lands. Safe as an intermediate state. | 2.1 |
| 2.3 — Wire `invalidationSource` in `App.kt` | **Conditional**: must land after 2.2. This is the first production behavior change — standing reactive subscription is replaced by invalidation-driven pull in live users' sessions. Requires desktop and Android smoke-test sign-off before merge. | 2.2 |
| 2.4 — Convert false-negative BSM tests | Yes — test-only change; no production behavior change | 2.2 (needs invalidation model active for test correctness) |
| 2.5 — Delete `blockObserveDebounceMs` | Yes — pure deletion; parameter was already no-op after 2.2 | 2.3 (call site removed) |
| 3.1 — Actor emits `BlocksWritten` | Yes — additive; `blocksPushed` has no subscribers until 3.2; `blockInvalidations` still fires for all arms | 2.1 |
| 3.2 — BSM subscribes to `blocksPushed` | **Conditional**: must land after 3.1 (publisher must exist) and after 2.3 (wiring in App.kt). Without 3.1, `pushSource` is null and behavior falls back to Phase 1 (safe). | 3.1, 2.3 |
| 3.3 — Benchmark | Yes — data collection; no production change | 3.2 (post-Phase-2 data point) |
| 4.1 — Actor unit tests | Yes — test additions only | 1.2 |
| 4.2 — Graph switch integration tests | Yes — test additions only | 2.3, 3.2 (tests both Phase 1 and Phase 2 paths) |
| 4.3 — N-page fan-out integration test | Yes — test additions only | 2.2 (needs invalidation model) |
| 4.4 — External file change regression | Yes — test additions only | 2.3 |

**Risk flag**: Story 2.3 is the highest-risk deployment step. It changes live app behavior (standing reactive subscription replaced, `writeActor` wired into BSM). This story must not land without the converted BSM tests from Story 2.4 already passing. The plan orders them (2.3 before 2.4), but CI should gate 2.3's merge on a pre-run of 2.4's converted test suite. Recommended sequence: 2.4 conversion work should begin in parallel with 2.2 and complete before 2.3 merges.

| Criterion | Result | Notes |
|-----------|--------|-------|
| Sequencing safety | **PASS** | Each story is independently deployable or has an explicit documented dependency; the one deployment risk (Story 2.3 behavior change) is flagged with a mitigation (2.4 must pass before 2.3 merges); the Epic 1 foundation stories are purely additive and safe to ship at any time |

---

## Implementation Readiness Gate — Summary

| Criterion | Result | Notes |
|-----------|--------|-------|
| Requirements completeness | PASS | 7/7 ACs testable; AC-6 threshold defined as call-count gate |
| Plan completeness | PASS | 14/14 stories have "Done when" + test lists |
| No unresolved BLOCKERs | PASS | 4 CRIT issues patched; post-patch adversarial verdict is CONCERNS |
| Sequencing safety | PASS | All stories independently deployable or dependency documented; one deployment risk flagged with mitigation |

**Overall verdict: PASS**

The feature is ready to proceed to Phase 5 (implementation). One pre-implementation action is recommended before Story 2.3 merges: complete Story 2.4 (BSM test conversion) first and confirm the full BSM test suite passes with the invalidation model active, then gate Story 2.3's merge on those results.
