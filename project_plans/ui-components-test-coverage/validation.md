# Validation: ui-components-test-coverage

## Adversarial Review

### Challenge 1: Will new Robolectric tests actually move the coverage gate?

**Finding:** No. The coverage gate in PR #402 measures `:kmp:jacocoTestReport` which aggregates only
`jvmTest` runs. `androidUnitTest` (Robolectric) coverage is not merged. Tasks 1–3 (VaultSettings,
FolderSyncSettings, SectionsSettings in Robolectric) will NOT numerically advance the JaCoCo
report or the CI gate.

**Impact:** Medium. The tests are still valuable — they guard real behaviour on the Android target —
but the stated backlog goal ("raise coverage") is only partially met unless Tasks 4–6 (jvmTest)
are prioritised.

**Mitigation in plan:** Tasks 4–6 are jvmTest and WILL appear in JaCoCo. Tasks 1–3 are retained
for real quality, with an explicit note in the plan that they do not move the gate. If the
owner needs the gate number to rise, Tasks 4–6 should be done first or the Robolectric coverage
merge should be tracked as a follow-up.

---

### Challenge 2: `ReferencesPanel` internal coroutine structure may make tests brittle

**Finding:** `ReferencesPanel.kt` launches coroutines internally to collect flows from
`BlockRepository` and `PageRepository`. Pre-populating in-memory repos before `setContent` may
cause timing issues if the composable's `LaunchedEffect` observes initial state differently
between test/production. The `composeTestRule.waitForIdle()` pattern used in other tests may not
be sufficient if the composable dispatches on a non-test dispatcher.

**Mitigation:** Use `InMemoryBlockRepository`/`InMemoryPageRepository` backed by `StateFlow` so
pre-populated data is immediately available. If needed, add a `composeTestRule.waitUntil { … }`
block guarding on a known text node. This is the established pattern in `DiskConflictResolutionTest`
and `JournalWarmStartRaceHealingTest`.

---

### Challenge 3: `ShareContent` composable requires many dependencies — test may become fragile

**Finding:** `ShareContent` accepts `AppState`, `StelekitViewModel`, `Page?`, `List<Block>`,
`Set<String>` (selected UUIDs), `ShareProvider`, `GoogleAuthManager?`, and `DriveUploader?`.
The `StelekitViewModel` dependency is the most complex — `ComposeUITestBase` wires it with fakes,
but `AppState` is normally owned by the ViewModel's `uiState.value.appState`. Tests must either
reach into `viewModel.uiState.value.appState` or construct an `AppState` directly.

**Mitigation:** `AppState` is a plain data class — construct it directly for test. Pass
`googleAuthManager = null` and `driveClient = null` to disable Google tiles, reducing dependencies
to zero external services. The date-range and scope-selector tests only need the local `AppState`
and `viewModel`. This keeps the test surface manageable.

---

### Challenge 4: `AutocompleteMenu` uses `Popup` which behaves differently in Compose Desktop test

**Finding:** `Popup` in Compose Desktop jvmTest is rendered in a separate window. `onNodeWithText`
in `composeTestRule` may not find nodes inside a `Popup` without additional configuration (e.g.
`useUnmergedTree = true` or asserting the popup's own window).

**Investigation needed at implementation time:** Check whether `composeTestRule.onAllNodes()` with
`useUnmergedTree = true` finds popup nodes, or whether `onNode(hasText(…))` works across popup
windows in the Compose Desktop test runner. Reference: `SuggestionContextMenuTest.kt` in jvmTest
which also tests a popup-like suggestion menu.

**Mitigation:** If popup nodes are not reachable, test the early-return guard (empty/null) and the
item rendering by toggling `isFilterActive` (which renders filter text inline, not in a popup).
Keyboard navigation tests may need to interact via `performKeyInput` on the host text field rather
than directly on popup nodes.

---

### Challenge 5: `SectionsSettings` rename/delete dialogs — animation timing

**Finding:** `SectionsSettings` uses `AnimatedVisibility` and `remember { mutableStateOf }` for
dialog visibility. In Robolectric, animations may not complete synchronously.

**Mitigation:** Use `composeTestRule.mainClock.advanceTimeByFrame()` or
`composeTestRule.waitForIdle()` after interactions. Robolectric + Compose test clock is
`TestCoroutineScheduler`-backed; advancing the clock or waiting for idle resolves animation state
in the existing test suite (see `ShareInboxPanelTest.discard_should_ConfirmWithFirst80Chars_OfferCopy_ThenRemoveAndFocusNextItem`
for the established pattern).

---

## Acceptance Criteria Validation (vs. Requirements AC-1..AC-8)

| AC | Status | Notes |
|---|---|---|
| AC-1: ShareDialogTest | FEASIBLE | jvmTest via `ComposeUITestBase`; date range and auth state are testable without real Google SDK |
| AC-2: VaultSettingsTest | FEASIBLE | Pure state-in/callback-out; 6 cases listed in plan |
| AC-3: FolderSyncSettingsTest | FEASIBLE | Pure state-in/callback-out; HostAccessState is a sealed class, easily constructed |
| AC-4: SectionsSettingsTest | FEASIBLE | Slightly at risk from animation timing (Challenge 5) — mitigated |
| AC-5: AutocompleteMenuTest | FEASIBLE WITH CAVEAT | Popup node reachability must be verified at implementation time (Challenge 4) |
| AC-6: ReferencesPanelTest (line % > 50%) | FEASIBLE | 4 cases cover the major branches; coroutine timing mitigated (Challenge 2) |
| AC-7: Tests pass in CI | FEASIBLE | Robolectric tests are headless; jvmTest tests need X11 (CI provides it) |
| AC-8: No source modifications | CONFIRMED | All interactions are via already-public signatures |

## Risks Accepted

- **Robolectric coverage not merged into gate:** Accepted. Document gap; track Robolectric/JaCoCo
  merge as a separate chore item.
- **AutocompleteMenu Popup testability:** Accepted with the fallback strategy (test guard cases and
  filter inline rendering; skip popup keyboard nav if unreachable in test runner).
- **PerformanceDashboard / LogDashboard remain at 0%:** Explicitly accepted. High line count,
  pure diagnostic rendering, no user-data state machines.

## Re-validation against the 10 backlog criteria

| Backlog AC | Plan task | Status |
|---|---|---|
| 1 ShareContent ≥ 60% | Task 4 (revised, Robolectric) | FEASIBLE; needs viewModel-free harness check |
| 2 VaultSettings ≥ 60% | Task 1 | Tests written, 13 green; coverage % not yet measured |
| 3 SectionsSettings ≥ 50% | Task 3 | Tests written, not yet run (gradle build-cache lock contention) |
| 4 FolderSyncSettings ≥ 50% | Task 2 | Planned |
| 5 AutocompleteMenu ≥ 60% jvmTest | Task 5 | Planned; Popup caveat (Challenge 4) |
| 6 Dashboards ≥ 30% | Task 8 | Planned (added) |
| 7 ShareInboxPanel +5% branch | Task 9 | Planned (added) |
| 8 Package ≥ 60% merged | Task 10 | AT RISK: needs measurement; fail-with-figure if short |
| 9 Headless, RobolectricTestRunner | all | FEASIBLE |
| 10 No broken/ignored tests | all | FEASIBLE (new files only) |

**Readiness gate: PASS (with AC8 flagged at risk).** Every criterion maps to a task; no blockers.

See `plan.md` "Final status" for what was built and the measured coverage.
