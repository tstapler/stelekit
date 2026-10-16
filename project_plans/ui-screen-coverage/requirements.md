# Requirements: ui-screen-coverage

## Overview

Raise Compose UI-screen test coverage for the `ui.screens` package from the measured
37.5% line / 16.1% branch baseline to a meaningful threshold. The largest gaps are
concentrated in the copy-pages flow, conflict-review/resolution screens, all-pages
list, library stats, LLM suggestion review, import screen, and the git-setup wizard.
A new incremental coverage gate (PR #402) flags 0%-covered diffs, making further
regressions visible at CI time — this item closes the existing gap before that gate
raises the bar.

## Problem Statement

The `ui.screens` package has 67 files, 7 939 lines, and only 37.5% line coverage
(JaCoCo, JVM-only, branch `stelekit-merges @ e337d58c1e`). Branch coverage is worse
at 16.1%. The heaviest uncovered files:

| File | Lines | Coverage |
|---|---|---|
| CopyPagesScreen.kt | 447 | 0% |
| ConflictResolutionScreen.kt | 327 | 0% |
| AllPagesScreen.kt | 251 | 0% |
| LibraryStatsScreen.kt | 192 | 0% |
| ConflictReviewScreen.kt | 187 | 0% |
| CopyFlowHost.kt | 167 | ~1% |
| LlmSuggestionReviewScreen.kt | 136 | 0% |
| ImportScreen.kt | 436 | 30% |
| GitSetupStep3Auth.kt | 239 | 16% |
| PageView.kt | 465 | 57% |
| GitSetupScreen.kt | 404 | 51% |
| JournalsView.kt | 393 | 51% |

## Goals

1. Write Robolectric Compose tests (`androidUnitTest`) for the copy-pages and
   conflict flows, covering the most impactful interaction paths (page selection,
   copy trigger, conflict hunk resolution).
2. Write pure state-reducer / ViewModel tests in `commonTest` or `businessTest`
   where the screen has no Compose dependency (AllPagesViewModel, LibraryStatsViewModel,
   ImportViewModel).
3. Bring the lowest-covered 0%-covered screens to ≥ 50% line coverage each.
4. Bring overall `ui.screens` line coverage from 37.5% to ≥ 55%.
5. Do not regress any existing tests; new tests must be green in both Robolectric
   (`./gradlew testDebugUnitTest`) and Bazel (`bazel test //kmp:android_unit_tests`).

## Out of Scope

- Screenshot (Roborazzi) pixel-level tests — those stay in `jvmTest` and are not
  part of this item.
- Instrumented on-device tests (androidInstrumentedTest).
- Full coverage of every branch — the goal is meaningful behavioural coverage, not
  synthetic 100% line coverage.
- LlmSuggestionReviewScreen: defer until the LLM feature is more stable.

## Acceptance Criteria

1. `CopyPagesScreen` has ≥ 50% line coverage via Robolectric tests that exercise:
   page-list rendering, page selection toggle, copy-trigger button, and at least one
   error/empty state.
2. `ConflictResolutionScreen` has ≥ 50% line coverage via tests that exercise: hunk
   picker rendering, LOCAL/REMOTE pick interaction, and the Resolve button.
3. `ConflictReviewScreen` has ≥ 50% line coverage via tests that exercise: conflict
   list display, remove-conflict action, and open-page action.
4. `AllPagesScreen` has ≥ 50% line coverage via tests (Robolectric or ViewModel-only)
   that cover: page-list rendering, search input, and navigation callback.
5. `LibraryStatsScreen` has ≥ 40% line coverage via ViewModel or Compose tests
   covering at least the Loading / Loaded / Error states.
6. `ImportScreen` line coverage improves from 30% to ≥ 55%.
7. `GitSetupStep3Auth` line coverage improves from 16% to ≥ 50%.
8. Overall `ui.screens` line coverage ≥ 55% (up from 37.5%) as reported by
   `./gradlew :kmp:testDebugUnitTest :kmp:jacocoAndroidTestReport`.
9. All new tests pass `./gradlew testDebugUnitTest` without requiring a display
   (Robolectric is headless).
10. No existing tests are broken or skipped.

## Complexity

3 — multi-screen Compose test authoring with ViewModel fakes; touches copy/conflict
flows that have real state machine depth.

## Open Questions

- Should `CopyFlowHost` be tested as a Compose host (white-box orchestration test)
  or via integration through `CopyPagesScreen`? *(unresolved — see pitfalls.md)*
- Is `LlmSuggestionReviewScreen` stable enough for tests, or still actively changing?
- What is the target branch for this work? (`stelekit-merges` or a new branch from `main`)
