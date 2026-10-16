# UX Review: ui-screen-coverage
**Date**: 2026-10-13

Sources consulted: `project_plans/ui-screen-coverage/implementation/plan.md`,
`project_plans/ui-screen-coverage/research/ux.md`,
`CopyFlowHost.kt` (lines 125–194 verified), `AllPagesScreen.kt` (lines 60–130 verified),
`GitSetupStep3Auth.kt` (lines 44–137 verified).

---

## Coverage Gaps

- [ ] **CopyFlowHost / Interrupted stage** — `CopyStage.Interrupted` renders
  `InterruptedCopyDialog` with Resume, Dismiss, and Open picker buttons (confirmed at
  `CopyFlowHost.kt:174-175`). No story in the plan covers this stage. If these buttons
  are broken, the user is trapped with a running copy and no exit.
  **Suggested criterion**: Story 1.1.1 should add a task — given
  `CopyFlowState(stage = CopyStage.Interrupted, interrupted = notice)`, assert the Resume
  button exists and `onResume` is invoked on click; assert Dismiss wires `dismissInterrupted`.

- [ ] **CopyFlowHost / switchConfirm dialog** — `CopyPullSwitchConfirmDialog` renders
  whenever `state.switchConfirm == true` (confirmed at `CopyFlowHost.kt:125-131`); it can
  appear over any stage. The plan mentions it in the glossary only; no story tests it.
  **Suggested criterion**: add a task to 1.1.1 — given `CopyFlowState(switchConfirm = true)`,
  assert the dialog is visible and `confirmPullSwitch` / `cancelPullSwitch` are wired.

- [ ] **CopyFlowHost / ConfirmUndo body text** — Story 1.1.1 task 7 asserts button
  callback wiring only. The body text "Removes the pages and blocks this copy added to
  \"$targetName\". Anything you edited since stays in place." (confirmed at
  `CopyFlowHost.kt:189`) is a user-visible warning that the UX research requires
  asserting (ux.md §6).
  **Suggested criterion**: add `onNodeWithText("Removes the pages and blocks").assertExists()`
  in the ConfirmUndo test.

- [ ] **AllPagesScreen / page type filter chips** — `PageTypeFilter.ALL`, `JOURNALS`,
  `PAGES` chips are rendered unconditionally (confirmed at `AllPagesScreen.kt:108-122`).
  Story 1.3.2's ten tasks (filter by name, sort, selection, delete) never exercise the
  type chips. The UX research explicitly lists "page type chips (All / Journals / Pages)
  work independently of the text filter" as a required assertion.
  **Suggested criterion**: add a task — seed one journal page and one regular page, click
  "Journals" chip, assert only the journal page is visible; click "Pages", assert only the
  regular page is visible.

- [ ] **AllPagesScreen / delete dialog Cancel path** — Story 1.3.2 task 8 tests
  confirming the delete. The Cancel button (`TextButton(onClick = { showDeleteDialog = false })`,
  confirmed at `AllPagesScreen.kt:77`) is untested; if it regresses,
  the user cannot dismiss the dialog without deleting.
  **Suggested criterion**: in the same test file, add — enter selection mode, open dialog,
  click Cancel, assert dialog is gone and `onBulkDelete` was never called.

- [ ] **GitSetupStep3Auth / deviceFlowEnabled=false gate** — `OAuthSection` receives
  `deviceFlowEnabled` (confirmed at `GitSetupStep3Auth.kt:110`; default is `false` at line 70).
  The UX research states "deviceFlowEnabled = false: OAuth Connect button is disabled — test
  this renders but is disabled." Plan task 7 only asserts the section renders; disabled-state
  is untested.
  **Suggested criterion**: add a task to 2.2.1 — render with `authType = GITHUB_OAUTH,
  deviceFlowEnabled = false`, assert the "Connect via browser" button has
  `isEnabled == false` in semantics.

- [ ] **GitSetupStep3Auth / Next button gating per auth type** — The UX research documents
  that Next is enabled only when `sshKeyPath` is non-empty (SSH_KEY) or `httpsToken` is
  non-empty (HTTPS_TOKEN) or a connection is selected/`oauthConnectedAs != null`
  (GITHUB_OAUTH). Story 2.2.1 tests that Next wires its callback but not the
  enabled/disabled gate for any auth type.
  **Suggested criterion**: for SSH_KEY, assert Next button is disabled when `sshKeyPath = ""`
  and enabled when `sshKeyPath = "/path/to/key"`. Mirror for HTTPS_TOKEN.

---

## Accessibility Gaps

- [ ] **CopyPagesScreen — liveRegion on countLine** — The UX research is explicit:
  "tests must assert this node exists so TalkBack reads filter-result updates automatically"
  (`semantics { liveRegion = LiveRegionMode.Polite }` on `state.countLine`). Story 1.1.2
  adds three new test tasks; none asserts the liveRegion semantic.
  **Suggested criterion**: add to one of Story 1.1.2's tests —
  `onNode(hasText(state.countLine)).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))`.

- [ ] **GitSetupStep3Auth — RadioButton role** — `AuthTypeRadioRow` sets
  `role = Role.RadioButton` in `selectable(...)` (confirmed at `GitSetupStep3Auth.kt:128`).
  The UX research states "tests should assert the RadioButton semantics role is present so
  accessibility services announce it correctly." Story 2.2.1 task 1 asserts label text;
  no task asserts the role.
  **Suggested criterion**: in the NONE state test, assert
  `onNodeWithText("No authentication (public repo)").assert(hasRole(Role.RadioButton))`.

- [ ] **GitSetupStep3Auth — token visibility toggle contentDescription change** — The UX
  research states "contentDescription must change when toggled." Plan task 6 asserts that
  `onToggleTokenVisible` is called; it does not assert the icon's `contentDescription`
  changes from "Show password" to "Hide password" (or equivalent).
  **Suggested criterion**: in the HTTPS_TOKEN test, assert the initial contentDescription
  of the visibility icon, click it, re-assert the updated description.

---

## Well-covered flows

- **ConflictReviewScreen loading / empty / error states**: covered by existing tests
  (loading skeleton, empty, load-failure, live region) plus Story 1.2.2 (hasMore,
  Undo snackbar, last-row-resolves transition).
- **CopyFlowHost main stage transitions (DryRun, Running, Finished, RunFailed, Conflicts,
  ConfirmUndo)**: covered by Story 1.1.1 tasks 2–7 with Given-When-Then specified.
- **AllPagesScreen filter by name, click, long-press, delete confirmation**: covered by
  Story 1.3.2 tasks 3–8.
- **ImportScreen Input stage (empty gate, enabled gate, Next transition, URL tab)** and
  **Review stage (Back, Confirm, Undo snackbar)**: covered by Stories 2.1.1 and 2.1.2.
- **LibraryStatsScreen all four ViewModel states (Idle / Loading / Loaded / Error)** plus
  auto-load LaunchedEffect and Refresh button: covered by Story 1.3.1 tasks 3–8.
- **ConflictResolutionScreen keep-mine / multi-file gate**: covered by Story 1.2.1 tasks 2–3.
- **GitSetupStep3Auth four auth types (radio selection, field visibility)**: covered by
  Story 2.2.1 tasks 1–8 for the happy path.

---

## Verdict

**CONCERNS**

No flow is entirely untested from end to end, but two gaps come close:

1. `CopyStage.Interrupted` has three exit-path buttons (Resume, Dismiss, Open picker) with
   zero planned test coverage. A regression there leaves users unable to recover from an
   interrupted copy without force-quitting.
2. The `switchConfirm` overlay can appear at any stage and is completely absent from the plan;
   a bug in its callbacks could strand users mid-copy with no visible way to proceed.

Additionally, the CopyPagesScreen `liveRegion` and GitSetupStep3Auth `Role.RadioButton`
accessibility gaps are the highest-priority accessibility signals identified by the UX
research and are both missing from the plan. These are not blocking but should be resolved
before the branch ships to prevent accessibility regressions.

The remaining gaps (type filter chips, Cancel path, Next gating, token contentDescription)
are medium-priority but have clear, low-effort remedies as noted above.
