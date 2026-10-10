# Validation Plan: capture-auto-enrich

**Date**: 2026-08-10

## Happy Path Scenario

Given a `CaptureViewModel` whose active graph already contains a page named `"Kubernetes"`
and whose `CaptureEnrichmentCoordinator` matcher has resolved within the local-scan budget,
when the user shares text mentioning "Kubernetes" via the Android share sheet and taps Save,
then the persisted journal `Block.content` contains `"[[Kubernetes]]"` instead of the raw
unlinked text — matching in-app Import's auto-link behavior — with Save completing inside the
sheet's existing responsiveness budget and no perceptible delay.

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| REQ-1: Run captured text through `ImportService.scan()` before save; write `linkedText` instead of raw `text` | `CaptureEnrichmentCoordinatorTest.kt` | `resolveForSave_should_ReturnLinkedText_When_MatcherResolvesWithinBudget` | Unit | Happy path — Story 1.1.2 AC1 |
| REQ-1 | `CaptureEnrichmentCoordinatorTest.kt` | `resolveForSave_should_ReturnRawText_When_MatcherNotReadyWithinBudget` | Unit | Error/fallback path — Story 1.1.2 AC2 |
| REQ-1 | `CaptureViewModelTest.kt` | `performSave_should_PersistLinkedBlockContent_When_MatcherResolvesInTime` | Integration | Robolectric + `IN_MEMORY` `RepositorySet`, real `GraphWriter` write path — Story 2.1.2 AC1 |
| REQ-2: Surface `topicSuggestions` from the local heuristic tier as dismissible chips in `CaptureScreen` | `CaptureActivity` compose test (new file, e.g. `CaptureSuggestionTrayTest.kt`) | `CaptureSuggestionTray_should_RenderChipRow_When_UndismissedSuggestionsExist` | Unit | Happy path — Story 3.2.1 AC1 |
| REQ-2 | same | `CaptureSuggestionTray_should_RenderNothing_When_NoUndismissedSuggestionsExist` | Unit | Error/empty path — Story 3.2.1 AC2 |
| REQ-2 | `CaptureViewModelTest.kt` | `onSuggestionAccepted_should_ForwardToCoordinator_When_ChipTapped` | Integration | Chip tap → `CaptureViewModel` → `CaptureEnrichmentCoordinator` wiring through a live ViewModel instance — Story 3.2.1 Task 3.2.1c |
| REQ-3: Accepting a chip creates a stub page via `GraphWriter.savePage` and folds the `[[wiki link]]` into saved block content if accepted before save | `CaptureEnrichmentCoordinatorTest.kt` | `onSuggestionAccepted_should_FoldWikiLinkIntoLinkedText_When_TermMatches` | Unit | Happy path — Story 1.3.1 AC1 |
| REQ-3 | `CaptureEnrichmentCoordinatorTest.kt` | `createAcceptedStubPages_should_SkipCreate_When_LiveDbReadFindsExistingPage` | Unit | Error/dedup-guard path — Story 1.3.2 AC2 (two coordinators, shared repo) |
| REQ-3 | `CaptureViewModelTest.kt` | `performSave_should_CreateStubPageAndFoldLink_When_SuggestionAcceptedBeforeSave` | Integration | Robolectric + `IN_MEMORY` backend, real `GraphWriter.savePage` — Story 2.1.2 AC3 |
| REQ-4: Reuse whichever LLM provider is configured via `llm-service` (incl. on-device) for the opt-in enhancement pass — no new provider code | `CaptureViewModelTest.kt` (or a pure-function test targeting `resolveEnricherProvider`) | `resolveEnricherProvider_should_ConstructClaudeTopicEnricher_When_AutoResolvesToAvailableProvider` | Unit | Happy path — Story 1.2.2 AC1, Task 1.2.2d |
| REQ-4 | same | `resolveEnricherProvider_should_ReturnNoOpTopicEnricher_When_NoProviderConfigured` | Unit | Error/no-provider path — Story 1.2.2 AC2 |
| REQ-4 | `CaptureEnrichmentCoordinatorTest.kt` | `launchLlmEnrichment_should_MergeAiEnhancedSuggestion_When_EnricherCompletesBeforeTextChanges` | Integration | External LLM call via fake `TopicEnricher` + `TestScope` virtual time — Story 1.2.1 AC1 |
| REQ-5: Time-box enrichment so it never delays save past budget; timeout/failure falls back to raw-text save | `CaptureEnrichmentCoordinatorTest.kt` | `resolveForSave_should_TriggerFreshScan_When_ReadyStateSourceTextHashIsStale` | Unit | Happy path (stale-hash rescan, not a false-positive reuse) — Story 1.1.2, Task 1.1.2d bullet 2 |
| REQ-5 | `CaptureEnrichmentCoordinatorTest.kt` | `resolveForSave_should_ReturnPromptly_When_LlmEnrichmentCoroutineNeverCompletes` | Unit | Error/hang path (`awaitCancellation` fake enricher never gates save) — Story 1.2.1 AC3 |
| REQ-5 | `CaptureEnrichmentCoordinatorTest.kt` | `resolveForSave_should_CoverFiveDegradeBranches_When_TimeoutFallbackAndProviderStatesVary` | Integration | Consolidated 5-branch matrix (matcher timeout / in-budget / no-provider / provider-in-time / provider-timeout-or-throw) — Story 4.1.1 |
| REQ-5 | `CaptureEnrichmentCoordinatorTest.kt` | `coordinatorSurvivesLargeGraphMatcherBuild_should_notCrashProcess` | Integration (JVM-thread level) | Crash-safety regression — mirrors `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/LargeGraphWarmStartCrashTest.kt`'s `warm_start_with_8030_pages_completes_without_uncaught_throwables` (`UncaughtRecorder` / `Thread.setDefaultUncaughtExceptionHandler` pattern): construct a coordinator against an 8 030-page `PageRepository` fake so matcher-build / `ImportService.scan()` runs at the same OOM-risk scale `PageNameIndex.kt:52-56` documents; assert zero `Throwable`s reach the process-level uncaught handler, closing adversarial-review.md's Blocker #2 and exercising the mandatory `Throwable` guards Tasks 1.1.2a / 1.2.1a / 2.2.3a add |
| REQ-6 (Should Have): visual/haptic distinction between auto-applied existing-page links and new-page suggestion chips | See **UX Acceptance Tests** rows 5, 6, 13 below | — | UX (manual) | No dedicated unit test — confidence-tier rendering is reused unmodified from Import (Story 3.1.1 is a zero-behavior-change promotion); the haptic signal is optional Should-Have polish per the plan's Pattern Decisions table and is verified on-device only |

**Migration**: N/A — plan.md's Migration Plan section confirms no SQLDelight schema or data
migration; `LlmFeature.TOPIC_ENRICHMENT` is a new enum case consumed by an existing
namespaced-string-key settings read that already defaults correctly to `null` for every
installed user. No migration test row is included.

## UX Acceptance Tests

Each row corresponds to one numbered criterion in `design/ux.md`'s "UX Acceptance Criteria"
section.

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Zero-chip capture saves in the same 2 steps as today | Manual checklist (Task 4.2.2a) | `manual_ZeroChipCapture_SavesInTwoSteps` | Manual — on-device | Share text with no matcher hits; confirm type → Save → sheet closed, no added step |
| 2. No perceptible first-frame delay attributable to enrichment | Manual checklist | `manual_SheetOpen_NoPerceptibleDelayToFirstFrame` | Manual — on-device, repeated launches | Launch via share sheet several times; confirm keyboard focus lands with no visible stall vs. a pre-feature build |
| 3. Save never waits on network/LLM latency | `CaptureEnrichmentCoordinatorTest.kt` (automated cross-ref) + manual | `resolveForSave_should_ReturnPromptly_When_LlmEnrichmentCoroutineNeverCompletes` + `manual_SlowOrOfflineLlm_SaveStillCompletesImmediately` | Unit (automated) + Manual — airplane mode | Automated: `awaitCancellation()` fake enricher (same test as REQ-5). Manual: configure a provider, enable airplane mode, confirm Save still completes without delay |
| 4. 1-tap accept / 1-tap dismiss, no confirmation dialog | Manual + Robolectric UI test (Task 3.2.2d) | `manual_ChipAcceptDismiss_SingleTapNoDialog` | Manual — on-device | Tap a chip body (accept) and a chip's ⓧ (dismiss) on separate chips; confirm no dialog appears either time |
| 5. Accept produces visible feedback within one frame | Manual | `manual_ChipAccept_VisibleFeedbackWithinOneFrame` | Manual — screen recording, frame-step | Record screen, tap a chip, frame-step the recording to confirm the checkmark/muted state appears within one frame with no spinner |
| 6. Dismiss is permanent; a later LLM-tier suggestion for the same term is never re-shown | Manual | `manual_DismissedTerm_NeverReshownByLlmTier` | Manual — on-device with LLM provider configured | Dismiss a local-tier chip, wait for the LLM-tier enhancement pass to complete, confirm the term does not reappear |
| 7. Zero suggestions produces zero visible UI | `CaptureSuggestionTrayTest.kt` (Story 3.2.1 AC2, automated) | `CaptureSuggestionTray_should_RenderNothing_When_NoUndismissedSuggestionsExist` | Robolectric compose test | Compose `CaptureScreen` with `Idle`/`Scanning`/empty-`Ready` state; assert no tray semantics node is emitted |
| 8. Timeout, matcher failure, and no-provider-configured are indistinguishable from a normal no-chip save | Manual (screenshot diff) | `manual_ThreeDegradePaths_ByteForByteIdenticalToBaselineSheet` | Manual — on-device, 3 forced scenarios | Force matcher-timeout, scan-throw, and no-provider paths independently; screenshot each and compare against the §1 baseline sheet |
| 9. Stub-page-creation failure shows a scoped retry message, reverts the chip, and does not roll back the persisted block | **GAP — not covered by `implementation/plan.md` as written.** `design/ux.md` §9 flags that `createAcceptedStubPages` (Task 1.3.2a) returns only successes, with no per-term failure channel for the UI to react to. A new story is required under Epic 2.2 (or a Story 2.1.2 amendment) before this criterion is testable. | — | — (blocked) | Once implemented: simulate a disk-full/permission error during stub-page save; confirm the snackbar text (`Couldn't create page "{term}" — tap to try again`), the chip reverting to its pre-accept state, and the journal block remaining intact |
| 10. Every surface (§1–§9) has an always-available exit action | Manual checklist | `manual_EverySurface_HasWorkingExitAction` | Manual — on-device, walk all 9 surfaces | For each of §1–§9, exercise Dismiss/dim-layer-tap/Back/Done at that state; confirm the sheet closes every time |
| 11. Accept-then-close-without-Save leaves zero writes on disk | `CaptureEnrichmentCoordinatorTest.kt` / `CaptureViewModelTest.kt` (Story 2.2.2, automated) | `onSuggestionAccepted_then_NoSave_should_RecordZeroWrites_When_SheetClosedWithoutSaving` | Unit (JUnit, businessTest) | Accept a chip, never call `save()`/`performSave()`; assert the fake `PageSaver`/`PageRepository` record zero calls |
| 12. Every interactive control has a touch target ≥44×44dp | Manual (Accessibility Scanner) | `manual_ChipAndButtonTargets_MeetFortyFourDp` | Android Accessibility Scanner | Run the scanner over the tray plus the Done/Save/Dismiss row; confirm zero "touch target too small" flags |
| 13. Confidence tier is legible in grayscale and announced in words by TalkBack | Manual (screenshot desaturation + TalkBack) | `manual_ConfidenceTier_LegibleGrayscaleAndAnnounced` | Manual — screenshot + TalkBack | Desaturate a tray screenshot, confirm the three tier shapes remain distinguishable; focus each chip with TalkBack, confirm the tier is spoken in words |
| 14. Async chip arrival announces politely without stealing focus | Manual (TalkBack) | `manual_AsyncChipArrival_PoliteAnnouncementNoFocusSteal` | Manual — on-device TalkBack | Focus the Save button via TalkBack, type text that triggers a background scan mid-typing, confirm the announcement is polite and neither focus nor an in-progress announcement is interrupted |
| 15. Full keyboard/switch-access navigation reaches every control in focus order | Manual (switch access / external keyboard) | `manual_FullNavigation_ReachesEveryControlInFocusOrder` | Manual — switch-access device / Bluetooth keyboard | Tab/switch through text field → chips (accept, dismiss) → Dismiss/Done/Save → snackbar retry action; confirm no touch-only dead end |

**Known coverage gaps carried from `design/ux.md`'s "Open items for the planning/validation
phase"** (surfaced here, not resolved by this document):
- **§8 caveat** — accepting a chip *after* Save has already completed has no story in
  `implementation/plan.md` (recommended resolution: option (a), call `createAcceptedStubPages`
  directly, skip the now-moot content fold). No test can be written until a story exists.
- **§9 gap** — `createAcceptedStubPages`'s signature (Task 1.3.2a) returns only successes; a
  per-term failure channel is required before UX criterion 9 above is testable at all, automated
  or manual.

## Test Stack
- **Unit**: Kotlin/JUnit + kotlinx-coroutines-test (`kmp/src/businessTest` source set) — all
  `CaptureEnrichmentCoordinator` logic (pure, Android-free, per ADR-004)
- **Integration**: Robolectric (`androidApp/src/test`) — `CaptureViewModel`/`CaptureActivity`
  wired against an `IN_MEMORY` `RepositorySet` backend (`repository/RepositoryFactory.kt`)
- **E2E / UX**: manual checklist (no Playwright — this is an Android app, not a web app); see
  UX Acceptance Tests table above for the full manual pass list

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM | `./gradlew jacocoTestReport` → check `build/reports/jacoco/` | ≥80% line |

- All public `CaptureEnrichmentCoordinator` methods (`onTextChanged`, `resolveForSave`,
  `onSuggestionAccepted`/`Dismissed`, `createAcceptedStubPages`, `close`): happy path + error
  path covered per the mapping table above.
- The one external integration this feature adds (LLM enhancement call via `TopicEnricher`):
  unit-mocked (fake `TopicEnricher`) in `CaptureEnrichmentCoordinatorTest.kt`, plus at least one
  Robolectric integration path through `CaptureViewModel`'s provider-resolution wiring.
- UX acceptance criteria: all 15 numbered criteria in `design/ux.md` have a corresponding test
  or manual step above; criterion 9 is explicitly blocked pending a plan amendment (see Known
  coverage gaps).
