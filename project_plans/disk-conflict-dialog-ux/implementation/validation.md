# Validation Plan: disk-conflict-dialog-ux

**Date**: 2026-07-03

Naming convention used below matches the existing convention in
`DiskConflictResolutionTest.kt` (e.g. `keepLocalChanges_dismisses_conflict`,
`manualResolve_writes_conflict_markers`, `diskConflict_model_has_all_fields`): a leading
subject/method identifier in camelCase, followed by a lowercase, underscore-joined verb phrase
describing the asserted behavior and (optionally) its condition. This repo does **not** use
`methodName_should_ExpectedBehavior_When_Condition` — that convention is NOT applied here.

## Requirement → Test Mapping

### REQ-1: Preview granularity parity (Gap #1 — Phase 2, Epic 2.1/2.2)

| Requirement | Test File | Test Name | Type | Scenario | Source |
|---|---|---|---|---|---|
| REQ-1 | `DiskConflictBlockMatcherTest.kt` | `matchDiskBlockContent_returns_matching_sibling_content_at_stable_position` | Unit | Happy path — 3 root-level siblings, stable order, middle block matched | plan.md Task 6.1.3a |
| REQ-1 | `DiskConflictBlockMatcherTest.kt` | `matchDiskBlockContent_returns_null_when_block_deleted_above_target` | Unit | Error/edge — disk-side has one fewer block before target (structural change) | plan.md Task 6.1.3b |
| REQ-1 | `DiskConflictBlockMatcherTest.kt` | `matchDiskBlockContent_returns_nested_child_content_for_non_root_block` | Unit | Edge — nested (depth-2) block path | plan.md Task 6.1.3c |
| REQ-1 | `DiskConflictBlockMatcherTest.kt` | `matchDiskBlockContent_returns_null_when_contentHash_collides_with_different_sibling_after_reorder` | Unit | Error/edge — same-count sibling transposition, content-hash plausibility check | plan.md Task 6.1.3d |
| REQ-1 | `DiskConflictBlockMatcherTest.kt` | `matchDiskBlockContent_ignores_null_contentHash_siblings_without_false_positive` | Unit | Edge — never-saved siblings (`contentHash == null`) don't trigger false collision | plan.md Task 6.1.3d (2nd case) |
| REQ-1 | `DiskConflictResolutionTest.kt` | `observeExternalFileChanges_populates_diskBlockContent_when_disk_content_parses_successfully` | **Integration** (NEW — gap) | Full `StelekitViewModel` + real `GraphLoader.emitExternalFileChange()` + `MarkdownParser`: assert the resulting `DiskConflict.diskBlockContent` is non-null and equals the matched block's content, not just the pure matcher in isolation | **Not in plan.md — see Coverage Gaps** |
| REQ-1 | `DiskConflictResolutionTest.kt` | `observeExternalFileChanges_survives_malformed_disk_content_and_processes_next_change` | Integration | Malformed content → `diskBlockContent = null`, collector stays alive for next emission | plan.md Task 6.1.5a |

### REQ-2: Hard truncation / "view full" escape hatch (Gap #2 — Phase 3, Epic 3.1)

| Requirement | Test File | Test Name | Type | Scenario | Source |
|---|---|---|---|---|---|
| REQ-2 | `DiskConflictFullScreenStateTest.kt` | `computeDiskDiffState_returns_Identical_when_contents_match_exactly` | Unit | Happy path — identical local/disk content (mtime-race false-positive case) | plan.md Task 6.1.4a |
| REQ-2 | `DiskConflictFullScreenStateTest.kt` | `computeDiskDiffState_returns_NoLocalEdit_when_local_content_is_blank` | Unit | Edge — blank local content special case | plan.md Task 6.1.4a |
| REQ-2 | `DiskConflictFullScreenStateTest.kt` | `computeDiskDiffState_returns_Different_with_nonEmpty_patch_when_contents_diverge` | Unit | Happy path — genuine divergence produces a usable patch | plan.md Task 6.1.4b |
| REQ-2 | `DiskConflictResolutionTest.kt` | `showDiskConflictFullView_sets_visible_flag_without_clearing_diskConflict` | **Unit** (NEW — gap) | Happy path — opening "view full" keeps `diskConflict` state alive (reachability before resolving) | **Not in plan.md — see Coverage Gaps** |
| REQ-2 | `DiskConflictResolutionTest.kt` | `hideDiskConflictFullView_clears_visible_flag_and_returns_to_still_open_conflict` | **Unit** (NEW — gap) | Edge — closing full screen returns to the dialog with the same conflict state intact (ADR-001) | **Not in plan.md — see Coverage Gaps** |

Note: the actual *mutual-exclusion rendering* (Task 3.1.1e — dialog suppressed while full screen
is visible) is Compose-layer and explicitly deferred to manual verification in plan.md itself
("Roborazzi screenshot tests remain Gradle-only... out of this project's test-tier scope"). The
two new tests above cover only the state-machine half (`AppState`/`StelekitViewModel` flags),
which is plain Kotlin and unit-testable without Compose — they were simply omitted from Phase 6.

### REQ-3: Manual-resolve marker literacy (Gap #3 — Phase 4, Epic 4.1)

| Requirement | Test File | Test Name | Type | Scenario | Source |
|---|---|---|---|---|---|
| REQ-3 | `DiskConflictResolutionTest.kt` | `manualResolve_writes_conflict_markers` | Unit | Happy path — markers written to block content | plan.md (existing test, pre-project) |
| REQ-3 | `DiskConflictResolutionTest.kt` | `manualResolve_clears_pendingConflicts_entry_on_main_branch` | Unit | Happy path — async clear after `requestEditBlock` | plan.md Task 6.1.1e |
| REQ-3 | `DiskConflictResolutionTest.kt` | `manualResolve_clears_pendingConflicts_entry_on_early_return_branch` | Unit | Edge — no `editingBlockUuid`, synchronous clear | plan.md Task 6.1.1e |
| REQ-3 | `DiskConflictResolutionTest.kt` | `manualResolve_sends_snackbar_naming_the_page_when_conflict_markers_inserted` | **Unit** (NEW — gap) | Happy path — post-write snackbar fires via `ConflictMarkerDetector.hasConflictMarkers()` gate, message includes page name | **Not in plan.md — see Coverage Gaps** |
| REQ-3 | `DiskConflictResolutionTest.kt` | `manualResolve_sends_no_snackbar_on_early_return_branch_since_no_markers_written` | **Unit** (NEW — gap) | Edge — early-return branch never writes markers, so the gate must not fire | **Not in plan.md — see Coverage Gaps** |
| REQ-3 | n/a | n/a | Integration | Not applicable — `manualResolve()`'s write path already runs against `FakeBlockRepository` in the above unit tests; no external system beyond the repository fake is involved | — |
| REQ-3 | — | — | — | Static caption text (Task 4.1.1a) — Compose chrome, no logic to unit test | Acceptable to defer, see Coverage Gaps |

### REQ-4: Persistent indicator for deferred conflicts (Gap #4 — Phase 1 + Phase 5)

| Requirement | Test File | Test Name | Type | Scenario | Source |
|---|---|---|---|---|---|
| REQ-4 | `DiskConflictResolutionTest.kt` | `checkAndShowPendingConflict_creates_entry_that_survives_navigation` | Unit | Happy path — entry created off-page, survives `navigateTo` (Task 1.1.1a fix) | plan.md Task 6.1.1a |
| REQ-4 | `DiskConflictResolutionTest.kt` | `keepLocalChanges_clears_pendingConflicts_entry` | Unit | Happy path — cleared exactly on resolution, not before | plan.md Task 6.1.1b |
| REQ-4 | `DiskConflictResolutionTest.kt` | `acceptDiskVersion_clears_pendingConflicts_entry` | Unit | Happy path — same, alternate resolver | plan.md Task 6.1.1c |
| REQ-4 | `DiskConflictResolutionTest.kt` | `saveAsNewBlock_clears_pendingConflicts_entry` | Unit | Happy path — same, alternate resolver | plan.md Task 6.1.1d |
| REQ-4 | `DiskConflictResolutionTest.kt` | `checkAndShowPendingConflict_uses_latest_diskContent_when_second_change_arrives_before_navigation` | Unit | Edge — stale-snapshot race (Task 1.1.1b) | plan.md Task 6.1.2a |
| REQ-4 | `DiskConflictResolutionTest.kt` | `pendingConflictFilePaths_includes_both_deferred_and_currently_open_conflict_paths` | **Unit** (NEW — gap) | Edge — the `App.kt` Task 5.1.3a set-union (`pendingConflicts.keys + diskConflict?.filePath`) is exercised against real `StelekitViewModel` state (one deferred conflict + one open dialog) | **Not in plan.md — see Coverage Gaps** |
| REQ-4 | — | — | — | `SidebarItem`/`LeftSidebar`/`PendingConflictsBanner` rendering (Tasks 5.1.1a, 5.1.4a/b) — Compose UI, no automated test tier in this repo (Roborazzi is Gradle-only) | Acceptable to defer, see Coverage Gaps |

## Test Stack
- **Unit**: `kotlin.test` (`@Test`, `assertEquals`/`assertNull`/`assertNotNull`/`assertTrue`) — pure
  functions run in `businessTest` (`DiskConflictBlockMatcherTest.kt`, `DiskConflictFullScreenStateTest.kt`);
  `StelekitViewModel`-level state/lifecycle tests run in `jvmTest` (`DiskConflictResolutionTest.kt`) using
  `Dispatchers.Unconfined` so `scope.launch` bodies run eagerly, no `advanceUntilIdle()` needed.
- **Integration**: same `jvmTest` tier, driven through the real `GraphLoader.emitExternalFileChange()`
  test seam against `FakePageRepository`/`FakeBlockRepository`/`FakeFileSystem` — exercises
  `StelekitViewModel` + `GraphLoader` + `MarkdownParser` + `DiskConflictBlockMatcher` together, no
  mocking framework.
- **API/E2E**: not applicable — this project has no network/API surface; UI-tier verification
  (Compose rendering, Roborazzi screenshots) is explicitly out of this project's automated test
  scope per the repo's Gradle-only screenshot-test convention (see plan.md Task 3.1.1e), verified
  manually via `bazel run //kmp:desktop_app` instead.

## Coverage Targets
- Unit test coverage: ≥80% (line) on new/changed files (`DiskConflictBlockMatcher.kt`,
  `DiskConflictFullScreen.kt`'s pure state function, the changed sections of `StelekitViewModel.kt`).
- All public functions in `DiskConflictBlockMatcher.kt` and `computeDiskDiffState`: happy path +
  every documented no-match/edge branch.
- All four conflict resolvers (`keepLocalChanges`, `acceptDiskVersion`, `saveAsNewBlock`,
  `manualResolve`) + `checkAndShowPendingConflict`: `pendingConflicts` lifecycle asserted for each.
- All external integrations (file-change events, disk-content parsing): unit-mocked-equivalent
  (pure matcher tests) **and** at least one integration test through the real `GraphLoader` seam —
  REQ-1's new integration test closes the one place this was previously missing (see below).

## Coverage Gaps Found

Six requirement-relevant test scenarios have no corresponding task in plan.md's Phase 6. None of
these are structural flaws in the plan — Phase 6 is thorough on the pieces it targeted (block
matcher edge cases, `pendingConflicts` lifecycle, diff-state edge cases, malformed-content
resilience) — but each gap below is a real, specific hole against requirements.md's four gaps and
Success Metrics, not a rubber-stamp restatement.

1. **REQ-1 — no integration test proves `diskBlockContent` actually gets wired through the live
   `StelekitViewModel` on the happy path.** Phase 6 tests `DiskConflictBlockMatcher` in complete
   isolation (Story 6.1.3, pure unit tests) and tests the *failure* path through the real ViewModel
   (Story 6.1.5, malformed content → `null`). No test exercises the **success** path through
   `observeExternalFileChanges()`/`checkAndShowPendingConflict()` (Tasks 2.1.2b/2.1.2c) — i.e.
   asserting that a real `DiskConflict` built via `GraphLoader.emitExternalFileChange()` ends up
   with a *non-null*, correctly-matched `diskBlockContent`. This is exactly the kind of gap where
   the pure function is well-tested but its wiring into production code is not — a DI/plumbing
   regression (wrong `localBlocks` source, wrong `targetUuid`, wrong disk-content variable) would
   pass every existing Phase 6 test while shipping broken. **Recommend**: add this test task to
   plan.md's Story 6.1.2/6.1.3 area before implementation (low cost — same `makeViewModel()` /
   `emitExternalFileChange` fixture already used by Story 6.1.1/6.1.2).

2. **REQ-2 — the "view full" state-machine (Story 3.1.1's acceptance criteria) has zero test
   coverage.** Phase 6 covers `computeDiskDiffState` (the diff *rendering* logic, Story 6.1.4) but
   never asserts the actual reachability contract from Success Metrics — "reachable before the
   user must pick a resolution" — which lives in `showDiskConflictFullView()`/
   `hideDiskConflictFullView()` toggling `AppState.diskConflictViewFullVisible` while
   `appState.diskConflict` survives untouched. This is plain `StateFlow` state, not Compose, and is
   exactly as unit-testable as the existing `editingBlockId_is_tracked_through_requestEditBlock`
   test already in `DiskConflictResolutionTest.kt` — it was simply never scheduled. **Recommend**:
   add this test task to plan.md's Story 6.1.1 area (mirrors the existing show/hide-toggle test
   pattern already in the file) before implementation.

3. **REQ-3 — the post-write snackbar's conditional gate (Task 4.1.2a) is untested.** Requirements.md's
   Success Metrics bullet list ("Regression/unit tests cover: preview-granularity parity,
   full-content reachability, and indicator persistence...") notably does **not** name the
   manual-resolve explanation as a metric requiring a test — so plan.md's silence here is
   consistent with requirements.md, not a plan.md oversight. However, Task 4.1.2a is not just
   static chrome (which would genuinely be Compose-only and fair to defer) — it has real
   conditional logic (`if (ConflictMarkerDetector.hasConflictMarkers(...))` gating `sendSnackbar`)
   that could silently regress (e.g. gate inverted, wrong message, fires on the early-return branch
   that never wrote markers). This is unit-testable via the same `snackbarEvents` `Flow` other
   snackbar call sites already use. **Recommend**: add this test task to plan.md's Story 6.1.1
   area — cheap, and it's the one piece of Gap #3 with actual branching logic, even though
   requirements.md itself doesn't mandate it.

4. **REQ-4 — the sidebar's derived `pendingConflictFilePaths` set (Task 5.1.3a,
   `appState.pendingConflicts.keys + listOfNotNull(appState.diskConflict?.filePath)`) is untested.**
   Story 6.1.1 proves `pendingConflicts` and `diskConflict` are each individually correct, but no
   test proves the *union* used to drive the sidebar indicator is computed correctly when both a
   deferred conflict (different page) and a currently-open conflict (the page just navigated to)
   coexist — the exact scenario the persistent-indicator requirement cares about. **Recommend**:
   add a lightweight test task to plan.md's Story 6.1.1 area; low cost since it reads existing
   `vm.uiState.value` fields with no new fixture needed.

5. **REQ-2/REQ-4 — Compose-layer assertions (dialog/full-screen mutual exclusion, `SidebarItem`
   icon, `PendingConflictsBanner`) have no automated coverage anywhere in Phase 6.** This is
   **acceptable to defer** — it is not a plan.md oversight but a consistent, explicit, pre-existing
   repo convention: Roborazzi screenshot tests are Gradle-only (`CLAUDE.md`, `kmp/TESTING_README.md`)
   and plan.md's own Task 3.1.1e explicitly calls this out and defers to manual verification via
   `bazel run //kmp:desktop_app`. No action needed beyond what plan.md already documents, but it's
   worth naming explicitly here since two of the four requirements.md gaps (mismatched preview
   *rendering* and the persistent *indicator's visual presence*) ultimately cash out as Compose
   assertions that will never be exercised by CI — only by the manual pre-ship verification pass
   plan.md already calls for.

6. **REQ-1 — Task 2.1.3a's dialog fallback copy ("Could not find a matching section on disk...")
   has no test anywhere**, automated or otherwise scheduled. It is Compose `Text`, so — consistent
   with Gap 5 above — **acceptable to defer** to the same manual verification pass, not a distinct
   action item.

**Net recommendation**: gaps 1–4 are cheap (all reuse the existing `DiskConflictResolutionTest.kt`
fixture, no new test infrastructure) and target real conditional logic outside Compose — add them
to plan.md's Phase 6 (Story 6.1.1/6.1.2 area) before implementation starts. Gaps 5–6 are correctly
and explicitly deferred already by plan.md's own stated conventions; no plan change needed.
