# Validation Plan: llm-tag-download-stall

**Date**: 2026-07-29

## Happy Path Scenario

Given a user is editing a block and types a tag trigger while the on-device model
(`Gemini Nano`) is `DOWNLOADABLE`, when `requestSuggestions()` fires the LLM path and
`format()` returns a retryable `OnDeviceUnavailable`, then the sheet shows the
"Downloading…" caption, `TagAvailabilityPoller` polls `checkAvailability()` every 3–5s in
the background, and once the model transitions to `Available` the suggestion call
auto-re-runs and the sheet updates to real results with no manual re-trigger — never
freezing on the first caption forever.

## N/A — no schema changes

This project touches no `.sq` file and adds no table — Step 5 (migration tests) is skipped
per the SDD gate instruction. No `MigrationRunner` entries are required.

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| FR-0: background status polling (AC0) | `TagAvailabilityPollerTest.kt` (businessTest) | `pollUntilAvailable returns immediately once Available is observed` (Task 3.2.1) | Unit | Happy path — probe resolves on 3rd tick, ~8000ms virtual time |
| FR-0: background status polling (AC0), gap | `TagSuggestionViewModelTest.kt` (businessTest) | Task 4.1.3's GWT ("Meeting notes about Q3 planning" scenario) — **not yet named as a `@Test` in plan.md**, gap: add `` `runLlmSuggest polls checkAvailability in the background after the initial Downloading caption` `` | Integration (VM-level) | `runLlmSuggest` posts `Pending(sdk reason)` then polls without a manual re-trigger |
| FR-1: auto-resolve on `Available` (AC1) | `TagAvailabilityPollerTest.kt` | `pollUntilAvailable returns immediately once Available is observed` (Task 3.2.1) | Unit | Poller returns `Available`, caller re-runs |
| FR-1: auto-resolve on `Available` (AC1) | `TagSuggestionViewModelTest.kt` | Task 4.1.3's GWT — gap: add `` `requestSuggestions auto re-runs and resolves to real results once Available is observed, no manual retrigger` `` | Integration (VM-level) | `llmStatus` goes `Pending` → `Resolved` with real `llmSuggestions` populated, zero calls to `retryLastRequest()` |
| FR-2: bounded wait + terminal state + mid-wait escalation (AC2) | `TagAvailabilityPollerTest.kt` | `pollUntilAvailable returns retryable Unavailable when deadline is reached` (Task 3.2.2) | Unit | Deadline reached, `Unavailable("Taking longer than expected", retryable=true)` |
| FR-2: mid-wait caption escalation at ~45s (AC2) | `TagAvailabilityPollerTest.kt` | `pollUntilAvailable escalates the caption exactly once after 45s` (Task 3.2.4) | Unit | `onStatusUpdate` fires once with the escalated caption at the first tick ≥45s |
| FR-2: bounded wait, VM-level transition sequence (AC2) | `TagSuggestionViewModelTest.kt` | Task 4.2.3's GWT — gap: add `` `llmStatus transitions Pending(null) to Pending(reason) to Pending(escalated) to Stalled across a full poll deadline` `` | Integration (VM-level) | Full `Pending(null)`→`Pending(reason)`→`Pending(escalated)`→`Stalled(true)` sequence, cache mirrors state |
| FR-0/FR-2: elapsed-time math pinned to a caller-supplied origin, not invocation time (pre-mortem P1 fix, poller-level) | `TagAvailabilityPollerTest.kt` (businessTest) | `` `pollUntilAvailable measures elapsed time from startedAtOverride, not from invocation time` `` (Task 3.2.6) | Unit | `startedAtOverride = now - 90_000L`: the poller's remaining budget is `deadlineMs - alreadyElapsed` (30s, not a fresh 120s), and `escalated` starts `true` — the primitive-level guarantee that FR-0's "background polling... without requiring the user to manually retrigger" and FR-2's "bounded wait" depend on when a poll is resumed rather than started fresh |
| FR-0/FR-2: elapsed time survives a block-switch-and-return (pre-mortem P1 #1) | `TagSuggestionViewModelTest.kt` (businessTest) | `` `poll elapsed time survives a block-switch-and-return, escalating immediately and reaching Stalled early` `` (Task 4.6.1) | Integration (VM-level) | Block A polls 500ms (past the scaled 450ms escalation threshold), user switches to block B then back to A — block A's relaunch shows the ESCALATED caption immediately (never re-shows the cold-start caption, serving FR-0's "no manual retrigger" and FR-2's mid-wait escalation) and reaches `Stalled` after only the remaining ~700ms, not a fresh full deadline (serving FR-2's "bounded wait") |
| FR-0/FR-2: manual retry after Stalled reaches Stalled again quickly, not a fresh deadline (pre-mortem P1 #2) | `TagSuggestionViewModelTest.kt` (businessTest) | `` `retryLastRequest after Stalled reaches Stalled again immediately, not after a fresh deadline` `` (Task 4.6.2) | Integration (VM-level) | After first reaching `Stalled`, `retryLastRequest()` resolves back to `Stalled` with at most 1 additional `checkAvailability()` call (not a fresh ~12-tick cycle) — proves FR-2's "bounded wait" holds across repeated retries, not just the first attempt |
| FR-3: manual retry affordance (AC3) | `TagSuggestionViewModelTest.kt` | Task 4.2.4's GWT — gap: add `` `retryLastRequest re-invokes requestSuggestions with stored args and restarts from Pending` `` | Integration (VM-level) | `retryLastRequest()` fires a fresh `suggestionJob`, state resets to `Pending(null)` |
| FR-3: manual retry affordance, retryable `Failed` case (AC3) | `SuggestionBottomSheet` UX test (jvmTest) | Task 5.2.5's GWT — gap: add `` `Failed with retryable true renders a Retry button that fires onRetry` `` | UX/Compose | Timeout-mapped `Failed(retryable=true)` renders `TextButton` labeled Retry |
| FR-4: no regression, fast path (AC4) | `TagSuggestionViewModelTest.kt` | Task 4.2.5's GWT — gap: add `` `requestSuggestions resolves with zero poll calls when checkAvailability reports Available immediately` `` | Integration (VM-level) | Zero `pollUntilAvailable` calls, `Pending(null)`→`Resolved` directly |
| FR-4: no regression, genuinely-unsupported path (AC4) | `TagSuggestionViewModelTest.kt` | Task 4.2.6's GWT — gap: add `` `requestSuggestions does not start a poll loop when the first failure is non-retryable` `` | Integration (VM-level) | `Failed(message, retryable=false)`, no poll loop started |
| FR-5: clean coroutine lifecycle — stale-block leak (pitfall #1) | `TagSuggestionViewModelTest.kt` | `poll loop for a stale block does not write into a newly active block's cache` (Task 4.4.1) | Unit/Integration | Block switch cancels the nested poll job structurally |
| FR-5: clean coroutine lifecycle — `close()` termination | `TagSuggestionViewModelTest.kt` | Task 4.4.3's GWT — gap: add `` `close cancels the poll loop and no further checkAvailability calls occur` `` | Integration (VM-level) | Probe call counter frozen immediately after `close()`, even past virtual-time deadline |
| FR-5: clean coroutine lifecycle — own-deadline self-termination | `TagSuggestionViewModelTest.kt` | `suggestionJob becomes inactive on its own once the poll deadline elapses` (Task 4.4.4) | Unit/Integration | `isSuggestionJobActiveForTest` becomes `false` without `close()`/block switch, milliseconds of virtual time (NFR-3) |
| FR-6: evidence-based poll deadline (AC6) | N/A — documentation gate, not code | Task 0.1.1 (read-only confirmation of ADR-001) | Manual/process | `DEFAULT_POLL_DEADLINE_MS = 120_000L` sourced in `ADR-001-poll-deadline-estimate.md`; no automated test — enforced by `TagAvailabilityPoller.kt`'s inline comment pointing at the ADR (Task 3.1.1) |
| FR-7: bulk scan opts out of polling (AC7) | `TagSuggestionViewModelTest.kt` | Task 4.3.2's GWT — gap: add `` `scanEntries fails fast per entry without polling when allowPolling is false` `` | Integration (VM-level) | `runLlmSuggest(..., allowPolling=false, ...)` returns the first failure immediately, no `pollUntilAvailable` call |
| NFR-1: no leaked coroutines / structured concurrency | `TagSuggestionViewModelTest.kt` | `poll loop for a stale block does not write into a newly active block's cache` (Task 4.4.1) + `suggestionJob becomes inactive on its own once the poll deadline elapses` (Task 4.4.4) | Unit/Integration | Poll job is nested under `suggestionJob`, never a sibling `scope.launch`; risk-control item 1 |
| NFR-2: platform-agnostic (`LlmProviderAvailability` tri-state, not `FeatureStatus`) | `TagAvailabilityPollerTest.kt` | All 5 tests (Tasks 3.2.1–3.2.5) | Unit | Poller is written entirely against `LlmProviderAvailability`, never Android `FeatureStatus` — verified structurally by the collaborator's signature, not a runtime assertion |
| NFR-3: testability (virtual time, no real sleep) | `TagAvailabilityPollerTest.kt` + `TagSuggestionViewModelTest.kt` | All of Story 3.2 (Tasks 3.2.1–3.2.5) + Tasks 4.4.4/4.5.1 | Unit/Integration | `runTest` virtual time via injectable `dispatcher`/`pollDeadlineMs`/`pollIntervalMs`/`pollEscalationThresholdMs`; explicitly the NFR-3 BLOCKER fix from the adversarial review |
| Domain-layer: `retryable` threaded through `RequestFailed` | `DomainErrorTest.kt` (commonTest) | Task 1.1.2 — run existing tests unmodified to confirm the additive field doesn't break compilation/equality | Unit | Regression — existing tests still pass with the new default-`false` field |
| Domain-layer: `LlmTagProvider` stops dropping `retryable` | `TagSuggestionEngineTest.kt` or new `LlmTagProviderTest.kt` (businessTest) | Task 1.2.2 — gap: name it `` `suggestTags maps a retryable OnDeviceUnavailable to a retryable RequestFailed` `` | Unit | Direct regression test for the bug named in requirements.md's Root Cause |
| Poller resilience — thrown `checkAvailability()` tick | `TagAvailabilityPollerTest.kt` | `pollUntilAvailable treats a thrown checkAvailability as a transient tick and keeps polling` (Task 3.2.5) | Unit | Adversarial-review BLOCKER fix — `Throwable` (not just `Exception`) on one tick degrades to "log and continue," never propagates |
| Poller termination — non-retryable `Unavailable` stops immediately | `TagAvailabilityPollerTest.kt` | `pollUntilAvailable stops immediately on non-retryable Unavailable` (Task 3.2.3) | Unit | Overlaps FR-4's permanent-failure guard at the collaborator level |
| Pitfall #2 — `format()` not re-triggered per poll tick | `TagSuggestionViewModelTest.kt` | `format is called at most twice across a full poll cycle, never once per tick` (Task 4.5.1) | Integration (VM-level) | `formatCalls == 2`, `checkAvailabilityCalls == 5` — direct enforcement, NFR-3-fixed to run in milliseconds |
| UI ripple — `Ready` construction with old flat fields | `ErrorStateNoDeadEndTest.kt` (jvmTest) | `` `LLM-suggestion failure shows specific message and a reachable dismiss action` `` (Task 6.1.1, rewritten to construct `llmStatus = Failed(...)` instead of `llmError`) | UX/Compose | Confirms the sealed-type migration doesn't regress the pre-existing dead-end guard |
| Compile check — Epic 2 breaks exactly the expected 4 files | N/A — build step, not a named test | Task 2.1.2 (`./gradlew jvmTest`, compile-only expectation) | Manual/CI | Confirms no missed `.llmPending`/`.llmError` reference outside the planned ripple set |

**Coverage note on FR/NFR denominator**: 11 FR/NFR items (FR-0…FR-7, NFR-1…NFR-3), all 11
have at least one unit and/or integration test mapped above — **11/11 (100%)**. FR-6 is the
one item whose "test" is a documentation/process gate (ADR-001 confirmation), not an
automated assertion — flagged explicitly in the table rather than silently counted as a
normal code test. The denominator stays 11/11 after the Phase 4 triad-review gap fix below —
Task 3.2.6/Story 4.6 do not add a new FR/NFR item, they add evidence for FR-0/FR-2, which
this table already counted but had not yet mapped test rows for.

**Phase 4 fix — pre-mortem P1 mechanism (`downloadFirstObservedAtMs`/`startedAtOverride`)
now has explicit rows**: this table was originally written before the pre-mortem P1 fix
added Task 3.1.1's `startedAtOverride` parameter, Task 4.1.1's `downloadFirstObservedAtMs`
field, Task 3.2.6, and Story 4.6 (Tasks 4.6.1/4.6.2) to plan.md — the "11/11" claim above was
technically still accurate (FR-0/FR-2 already had other rows mapped), but it did not include
test rows for this specific mechanism, so the coverage table understated what the pre-mortem
fix itself was tested by. Three rows have been added above (poller-level Task 3.2.6, VM-level
Tasks 4.6.1/4.6.2), mapped to FR-0 ("background polling... without requiring the user to
manually retrigger") and FR-2 ("bounded wait" / mid-wait escalation) — the two requirements
this mechanism directly serves, since correct elapsed-time continuity across a block-switch
or a manual retry is precisely what keeps the poll loop's wait bounded and its escalation
timing accurate rather than silently resetting.

**Named-gap summary**: plan.md's own code blocks give concrete `@Test` names for 13 of the
above rows (Tasks 3.2.1–3.2.6, 4.4.1, 4.4.4, 4.5.1, 4.6.1, 4.6.2) plus the Epic 6/1.1.2/2.1.2
process tasks. The remaining rows (Tasks 4.1.3, 4.2.3, 4.2.4, 4.2.5, 4.2.6, 4.3.2, 4.4.3,
1.2.2, 5.2.3, 5.2.4, 5.2.5) are written in plan.md as prose Given-When-Then blocks without a
literal `@Test fun` — this is the gap this validation pass is required to surface. Suggested
concrete names are given in the table above, following plan.md's own backtick style and
using its Domain Glossary terms verbatim (no invented alternate names).

## UX Acceptance Tests

| UX Criterion (design/ux.md Step 3) | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Fast path, zero extra taps (validates AC4) | `jvmTest` new/extended UX test, e.g. `LlmSuggestionCaptionStatesUiTest.kt` | `` `Resolved status renders chips with no caption and no spinner beyond local-match render` `` | Compose (`createComposeRule`, `setContent { MaterialTheme { SuggestionBottomSheet(...) } }`) | Set `state.llmStatus = Resolved`, assert no `Text` node for any caption string exists, assert `TagChipRow`'s chips render immediately |
| 2. Retry path, exactly 1 tap (validates AC3) | same file | `` `Stalled state resumes the download in exactly one tap on Retry` `` | Compose | Set `llmStatus = Stalled(retryable=true)`, `onNodeWithText("Retry")` (or `onNodeWithContentDescription("Retry downloading tags")`), `performClick()`, assert `onRetry` callback fired exactly once, no dialog appeared |
| 3. Proof-of-life without user action (validates AC0/AC1/FR-2) | `TagAvailabilityPollerTest.kt` (businessTest, not Compose — this criterion is about caption *content* changing over time, which is a collaborator-level guarantee) | `pollUntilAvailable escalates the caption exactly once after 45s` (Task 3.2.4, reused — same assertion satisfies this UX criterion) | Unit (virtual time) | Confirms `onStatusUpdate` is called with a different caption string at least once during a resolving wait, with no user action between calls |
| 4. Specific message + specific action per row (c)/(d) | same Compose file as #1/#2 | `` `Stalled renders literal Taking longer than expected plus secondary line and labeled Retry button` `` and `` `Failed with retryable false renders the SDK reason with no button` `` | Compose | `onNodeWithText("Taking longer than expected.")`, `onNodeWithText("Tap Retry to check again, or keep typing the tag yourself.")`, `onNodeWithContentDescription("Retry downloading tags")` all present for row (c); row (d) asserts the reason text present and `onNodeWithText("Retry")` absent via `onNodeWithText("Retry").assertDoesNotExist()` (or equivalent) |
| 5. No dead ends — every state has an exit path | same Compose file, extends `ErrorStateNoDeadEndTest.kt`'s existing pattern | `` `Stalled state offers both Retry and header Dismiss as reachable exits` `` | Compose | Assert both `onNodeWithText("Retry")` and `onNodeWithContentDescription("Dismiss")` are present and enabled simultaneously for `Stalled`; click Dismiss, assert `onDismiss` fires (mirrors `ErrorStateNoDeadEndTest.kt`'s existing LLM-suggestion-failure test pattern exactly) |
| 6. Keyboard/switch-access navigable (Retry is a real `TextButton`) | same Compose file | `` `Retry affordance is a focusable TextButton, not a clickable Text` `` | Compose (`SemanticsMatcher`/`hasClickAction()` + role assertion, or simplest: `onNodeWithContentDescription("Retry downloading tags").assertHasClickAction()`) | Assert the Retry node has a click action and is part of the Button semantics role, confirming `TextButton` (Task 5.2.2) rather than a bare clickable `Text`/`Box` |
| 7. Screen-reader grouping (`mergeDescendants=true`) | same Compose file | `` `Stalled column merges heading secondary line and Retry into one semantics node` `` | Compose | Use `composeTestRule.onNode(hasText("Taking longer than expected.") and hasAnyDescendant(hasText("Retry")))` or equivalent merged-node query to assert the three pieces resolve as one semantics unit, not three separate stops |
| 8. Retry structurally absent (not disabled) when unsupported | same Compose file | `` `Retry button does not exist in the semantics tree when retryable is false` `` | Compose | For `Failed(retryable=false)`, use `onNodeWithText("Retry").assertDoesNotExist()` (not `assertIsNotEnabled()` — the distinction between "absent" and "disabled" is the actual criterion per Step 3 item 8 of ux.md) |
| 9. Color contrast ≥4.5:1 for caption text | N/A — not a Compose test; manual/tooling verification | N/A | Manual (contrast checker tool, e.g. WebAIM or a CLI WCAG contrast utility) against the exact hex pairs `ux.md` Step 4 hand-computed: `tertiary`-on-`ParchmentBackground`/`StoneBackground`, `onSurfaceVariant`-on-`ParchmentBackground`/`StoneBackground` | Run each of the 4 color pairs cited in `design/ux.md` Flags 2/3 through an actual contrast-ratio tool (not hand computation) — **`onSurfaceVariant` is flagged as likely failing (~4.0–4.1:1) in both themes; this is a pre-existing, out-of-scope gap this project widens the use of, not a new regression to block on** (ux.md Flag 3) |

**UX criterion 9 note**: this is explicitly not automatable via `ComposeTestRule` (contrast is
a rendering/color-math property, not a semantics-tree property) — it is a manual/tooling gate,
consistent with ux.md's own framing ("hand-verified once... should still run these... through
an actual contrast checker"). Recorded here as a checklist item, not a `@Test`.

**File placement note**: plan.md's Epic 5 does not name a new UI test file explicitly beyond
extending `ErrorStateNoDeadEndTest.kt` (Task 6.1.1) — the 8 automatable UX rows above are
proposed to live in one new file, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/LlmSuggestionCaptionStatesUiTest.kt`, following `ErrorStateNoDeadEndTest.kt`'s exact structure
(`createComposeRule`, one `@Test` per row/criterion, `MaterialTheme { SuggestionBottomSheet(...) }`).
This is a gap plan.md leaves open — Story 5.2's Tasks 5.2.3/5.2.4/5.2.5 are written as prose
GWT blocks, not `@Test` code, and do not name a target file for them.

## Test Stack

- **Unit**: `kotlin.test` + `kotlinx-coroutines-test` (`runTest`), `businessTest` source set —
  `TagAvailabilityPollerTest.kt` (new), `TagSuggestionViewModelTest.kt` (existing, extended),
  `TagSuggestionEngineTest.kt` / new `LlmTagProviderTest.kt` (existing/new), `DomainErrorTest.kt`
  (`commonTest`, existing, regression-only).
- **Integration**: `businessTest` source set, ViewModel-level through `TagSuggestionViewModel`'s
  public API (`requestSuggestions`, `retryLastRequest`, `scanEntries`) — per the task brief's
  framing, "external call" in this project means the on-device `checkAvailability()`/`format()`
  SDK boundary, which is already faked via `LlmFormatterProvider`/a lambda `checkAvailability`
  probe in every example test in plan.md. There is no true network/DB I/O integration surface
  in this feature's scope — no new SQLDelight table, no new file I/O.
- **UX/Compose UI**: `jvmTest` source set, JUnit4 `@Rule createComposeRule()` (Roborazzi is
  used elsewhere in this source set for screenshot tests but is not needed here — these are
  behavioral/semantics-tree assertions, not visual-diff tests). Pattern confirmed from
  `ErrorStateNoDeadEndTest.kt`: `composeTestRule.setContent { MaterialTheme { <composable> } }`,
  then `onNodeWithText(...)` / `onNodeWithContentDescription(...)` / `onAllNodesWithText(...).onFirst()`,
  `.assertIsEnabled()`, `.performClick()`, `composeTestRule.waitForIdle()`, and plain
  `kotlin.test` `assertTrue`/`assertFalse` on captured boolean flags set by callback lambdas
  (no mocking framework). Requires a display — `xvfb-run --auto-servernum ./gradlew ciCheck`
  in headless environments per this repo's CLAUDE.md.

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM | **Not configured.** No jacoco plugin exists anywhere in `kmp/build.gradle.kts` or any other Gradle file in this repo (`grep -rn jacoco` across the whole repo returns zero hits) — there is no `./gradlew jacocoTestReport` task available. Coverage is not machine-measured on this project; treat the requirement-mapping table above as the coverage proof instead. | N/A — no automated line-coverage target exists in this repo; do not assume one |

- All public service methods touched by this project (`TagAvailabilityPoller.pollUntilAvailable`,
  `TagSuggestionViewModel.requestSuggestions`/`retryLastRequest`/`scanEntries`,
  `LlmTagProvider.suggestTags`): happy path + error paths covered per the table above.
- The one external-SDK-boundary integration point (`checkAvailability()`/`format()`, faked via
  `LlmFormatterProvider`/lambda probes): unit-mocked in every test above, plus the VM-level
  tests (Tasks 4.1.3, 4.2.3–4.2.6, 4.4.1, 4.4.3, 4.4.4, 4.5.1) serve as the "at least one
  integration test" per external integration, per the task brief's guidance that most
  "integration" tests here are ViewModel-level rather than true I/O.
- UX acceptance criteria: all 9 from `design/ux.md` Step 3 have a corresponding test or
  manual step above — criterion 9 (contrast) is explicitly a manual/tooling step, not a
  `@Test`, and is recorded as such rather than silently omitted.
- Migration tests: **N/A — no schema changes** (Step 5, per the SDD gate instruction; plan.md
  has no Migration Plan section, no new `.sq` table, no `MigrationRunner.all` entry required).
