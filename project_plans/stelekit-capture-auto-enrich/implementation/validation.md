# Validation Plan: stelekit-capture-auto-enrich

**Date**: 2026-08-27

## Happy Path Scenario

Given a user has SteleKit installed with an existing page named "Kotlin Multiplatform" in
their active graph, when they share an article snippet mentioning "Kotlin Multiplatform" via
the Android share sheet into `CaptureActivity`, then the background scan auto-links the
mention (shown as a read-only `[[Kotlin Multiplatform]]` preview line, `linkedText` never
rewriting the live `OutlinedTextField`) and surfaces at least one local-heuristic
`TopicSuggestion` chip in the tray, and when they tap Save the block is persisted to today's
journal with `linkedText` (not raw text) — zero additional taps beyond today's flow.

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| AC #1: auto-link before write | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt` | `scan_matcherReady_returnsSuccessWithLinkedText` | Unit | Happy path — matcher ready, existing page mention wrapped `[[…]]` in `ScanOutcome.Success.result.linkedText` |
| AC #1: auto-link before write | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt` | `scan_matcherNotBuilt_returnsMatcherNotReadyImmediately` | Unit | Error path — cold-start matcher `null`, no `withTimeoutOrNull` wait, fast-path `MatcherNotReady` |
| AC #1: auto-link before write | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `save_scanStateReadyMatchesCaptureText_persistsLinkedTextNotRawText` | Integration | `save()` persists `ScanState.Ready.result.linkedText`, not raw `captureText`, when text matches (Story 2.3.1) |
| AC #1: auto-link before write (formalizes plan's `onNewIntent` staleness test, PF-6) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `collectLatest_secondShareIntentSupersedesInFlightScan_onlyLatestResultReachesScanState` | Integration | A scan in flight for share-intent A's text is cancelled/superseded when intent B's text arrives before A resolves; A's result never lands in `_scanState` (Story 5.2.1) |
| AC #1: auto-link before write (formalizes plan's image-only-share test) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `scan_imageOnlyShareNoCaption_neverScansRawImagePathPrefix` | Integration | `captureText == "[image: <path>]"` (no trailing `\n` — `CaptureActivity`'s `.trim()` strips it when `shareContent.text` is empty, Story 2.1.3's fixed `IMAGE_PREFIX_REGEX`) → `splitImagePrefix` yields empty `freeText`; `coordinator.scan("")` short-circuits to `NotReady`, path string never reaches `AhoCorasickMatcher` (Story 5.2.2) |
| AC #2: chip tray, accept creates stub page (no silent creation) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_preSaveWithFakeGraphWriter_savesStubPageAndMarksAccepted` | Unit | Happy path — fake `writer`/`repoSet`, tap accept → `GraphWriter.savePage` called once, `markAccepted()` folds `[[link]]` into pending `_scanState` (Task 4.1.1a/4.1.1b) |
| AC #2: chip tray, accept creates stub page (no silent creation) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `dismissSuggestion_setsDismissedTrue_noWriteInvoked` | Unit | Error/negative path — `dismissSuggestion()` never calls `GraphWriter.savePage`; proves no code path silently creates a page without an explicit accept tap (Task 4.1.2a) |
| AC #2: chip tray, accept creates stub page (no silent creation) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_preSave_realGraphWriterPersistsStubPageFile` | Integration | Real `SteleKitApplication`/`GraphManager`/`GraphWriter` (Robolectric, per Phase 5's "do not override `Application`" note) — accepted stub page is actually written to the graph's markdown file, same path `ImportViewModel.confirmImport()` uses |
| AC #2: chip tray, accept creates stub page (no silent creation) (formalizes plan's save/accept race test, Blocker #2) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestionThenSave_synchronousFoldWinsRace_saveCompletesWithoutAwaitingSlowerStubWrite` | Integration | Chip accept (controllable-delay stub write) launched, then `save()` launched immediately after; `markAccepted()`'s synchronous fold (not a `saveOpMutex` — that design was superseded, see `plan.md`'s Pattern Decisions table) makes `save()`'s persisted `textToSave` include the accepted term's `[[link]]` without ever awaiting the slower stub write (Story 5.2.6) |
| AC #3: opt-in LLM enhancement tier | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt` | `resolveTopicEnricher_providerAvailableForCaptureEnrichment_returnsClaudeTopicEnricher` | Unit | Happy path — `LlmProviderRegistry.availableForFeature(CAPTURE_ENRICHMENT)` returns a provider (incl. on-device tier) → `ClaudeTopicEnricher` wraps its `formatter` (Story 1.1.2) |
| AC #3: opt-in LLM enhancement tier | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt` | `resolveTopicEnricher_noProviderConfigured_returnsNoOpTopicEnricher` | Unit | Error/default path — empty provider list → `NoOpTopicEnricher`, unchanged local-heuristic tier, not logged as degraded |
| AC #3: opt-in LLM enhancement tier | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `enhance_llmProviderConfigured_mergesEnrichedSuggestionsNonDestructivelyIntoScanState` | Integration | Fire-and-forget `enhance()` coroutine appends AI-sourced suggestions onto the existing local set (`mergeBySource`, never clear-and-replace) (Task 2.2.1a) |
| AC #3: opt-in LLM enhancement tier | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt` | `enhance_malformedProviderOutput_sanitizesBlankDuplicateAndOutOfRangeConfidence` | Unit | Error path — blank term dropped, confidence clamped `0f..1f`, case-insensitive duplicate deduped before reaching the chip tray/stub-page write (Task 1.1.1c, "no exception, bad data" failure mode) |
| AC #4: save never blocked by enrichment | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt` | `scan_withinBudget_returnsSuccessBeforeTimeout` | Unit | Happy path — scan completes inside `budgetMs`, returns `ScanOutcome.Success` |
| AC #4: save never blocked by enrichment | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `save_scanStateNotReady_savesRawCaptureTextImmediately` | Unit | Error path — `ScanState.NotReady` (matcher not built / budget exceeded) → `save()` uses `_captureText.value.trim()` immediately, no `await`/`join` on any scan or coordinator job |
| AC #4: save never blocked by enrichment | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `save_staleReadyStateTextMismatchesCaptureText_fallsBackToRawTrimmedText` | Integration | `ScanState.Ready(text = "old text", …)` but `captureText.value == "old text plus more"` → stale `Ready` ignored, raw current text saved (Story 2.3.1) |
| AC #4: save never blocked by enrichment (formalizes plan's scan-survives-`Throwable` test, Blocker #3) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `collectLatest_scanThrowsOnFirstAttempt_secondTextChangeStillProducesReadyState` | Integration | Fake coordinator throws `OutOfMemoryError` on first `scan()`, succeeds on second; first attempt degrades to `NotReady` without killing the collector, proving no partial/stalled save state under an enrichment-path `Throwable` (Story 5.2.7) |
| AC #5: no responsiveness regression | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `save_readyScanTextMatchesCaptureText_computesTextToSaveWithoutSuspending` | Unit | Happy path — `textToSave` computation reads `_scanState.value` synchronously, no suspension point in the common case |
| AC #5: no responsiveness regression | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt` | `scan_matcherNotReady_shortCircuitsWithoutInvokingWithTimeoutOrNull` | Unit | Error/edge path — matcher-null short-circuit costs zero timeout-wrapped work, keeping cold-start scans cheap |
| AC #5: no responsiveness regression (formalizes plan's dedicated AC#5 responsiveness test) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `save_scanInFlightAgainstStuckCoordinator_completesWithoutAwaitingScan` | Integration | Fake coordinator's `scan()` suspends indefinitely (unresolved `CompletableDeferred`); `save()` still reaches `Saved`/`Error` — falsifiable proof `save()` never awaits coordinator work (Story 5.2.8) |
| AC #6: visual/haptic distinction | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureActivityTest.kt` | `captureSuggestionChip_confidenceThresholds_produceExpectedContentDescriptionWord` | Unit | Happy path — 0.9/0.5/0.2 confidence values map to "high"/"medium"/"low" in the chip's semantics `contentDescription`, and to `primary`/`secondary`/`error` dot colors (0.7/0.4 thresholds) |
| AC #6: visual/haptic distinction | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureActivityTest.kt` | `autoLinkPreviewLine_rendersNoTapTargetOrChipAffordance` | Unit | Error/negative path — the read-only `[[link]]` preview line has no click modifier, no dismiss `×`, no semantics actions — only new-page suggestions render as interactive chips |
| AC #6: visual/haptic distinction (formalizes plan's consolidated AC#6 negative-case test) | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureActivityTest.kt` | `previewLineRenderAndChipDismiss_neverFireConfirmHaptic` | Integration | Spy `HapticFeedback` via `CompositionLocalProvider(LocalHapticFeedback provides spy)`: preview-line render fires no haptic at all; chip dismiss fires no `HapticFeedbackType.Confirm` (Story 5.2.9) |
| AC #7: per-suggestion failure isolation | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_stubPageSaveFailsForOneTerm_otherPendingSuggestionsUnaffected` | Unit | Happy path (isolation working) — one accepted term's `Either.Left` leaves every other pending chip's state untouched |
| AC #7: per-suggestion failure isolation | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_graphWriterReturnsLeft_logsErrorAndEmitsChipFailureMessage` | Unit | Error path — `Either.Left` is logged (`logger.error`) and surfaced via `_chipFailure`, never thrown/silently discarded (Task 4.1.1a, 4.1.3b) |
| AC #7: per-suggestion failure isolation | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_stubPageWriteFails_blockWriteAndMarkdownFlushUnaffected` | Integration | Real `SteleKitApplication` — a failing stub-page write never aborts `performSave()`'s block write or the Bug-8 markdown auto-flush |
| AC #8: race-safe coordinator construction | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerEnrichmentCoordinatorTest.kt` | `getOrCreateEnrichmentCoordinator_twoConcurrentCallsSameGraph_returnSameInstanceBuiltOnce` | Unit | Happy path — two concurrent callers for one `GraphId` receive `===`-equal `CaptureEnrichmentCoordinator`; exactly one `PageNameIndex`/`getPageNameEntries()` collector started (Story 1.2.2, formalizes plan's required `GraphManagerEnrichmentCoordinatorTest`) |
| AC #8: race-safe coordinator construction | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerEnrichmentCoordinatorTest.kt` | `getOrCreateEnrichmentCoordinator_constructionThrows_evictsFailedEntryForRetry` | Unit | Error path — a `Deferred` failing during construction is evicted from `coordinatorFor`, so the next call retries fresh instead of replaying the same failure forever (Task 1.2.1b) |
| AC #8: race-safe coordinator construction | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `rapidTextChanges_triggerConcurrentCoordinatorLookups_neverConstructTwoPageNameIndexInstances` | Integration | Real `SteleKitApplication`/`GraphManager` — rapid concurrent text-change events (debounced `collectLatest` overlapping with a direct concurrent call) never build two coordinator/`PageNameIndex` instances for one capture session |
| AC #8: race-safe coordinator construction | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerEnrichmentCoordinatorTest.kt` | `getOrCreateEnrichmentCoordinator_concurrentDifferentGraphs_g2NotBlockedByG1InFlightConstruction` | Unit | `coordinatorMutex` held only for cache read/insert — a slow `g1` construction never blocks a concurrent `g2` lookup (Task 1.2.1b) |
| AC #9: post-save chip acceptance | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_postSaveWithFakeSavedContext_reusesCapturedWriterAndWriteActor` | Unit | Happy path — fakes prove the exact `writer`/`writeActor` instances `performSave()` constructed are reused (`===`), exactly one additional `saveBlock`/`savePage` pair invoked (Story 5.2.3, formalizes plan's required AC#9 post-save write path test) |
| AC #9: post-save chip acceptance | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_postSaveGraphIdMismatch_neverTouchesRepositoryOrWriter` | Unit | Error path — `ctx.graphId != GraphManager.getActiveGraphId()` short-circuits before any repository/writer call; no state under `ctx` mutated (Story 5.2.5, formalizes plan's required graph-identity-mismatch test, Blocker #1) |
| AC #9: post-save chip acceptance | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_postSaveClosedSendChannelException_isCaughtWithDistinctMessage` | Integration | `writeActor.saveBlock()`'s channel closed (simulated graph-switch race) → `ClosedSendChannelException` caught, distinct non-"please retry" message logged/emitted, no crash (Story 5.2.4, PF-5) |
| AC #9: post-save chip acceptance | `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureViewModelTest.kt` | `acceptSuggestion_postSaveSuccess_realGraphWriterPersistsSecondWriteToMarkdown` | Integration | Real `SteleKitApplication`/`GraphWriter` — the second write actually lands: stub page created, block content updated with `[[link]]`, markdown flushed via the same `writer` instance |

**Coverage**: 9/9 acceptance criteria mapped, each with ≥1 unit-happy + ≥1 unit-error + ≥1
integration test; every Phase-5-sketched regression test (`GraphManagerEnrichmentCoordinatorTest`,
`onNewIntent` staleness, image-only-share, AC#9 post-save write path, graph-identity mismatch
(Story 5.2.5), `ClosedSendChannelException` race (Story 5.2.4), save/accept race (Story 5.2.6),
scan-survives-`Throwable` (Story 5.2.7), AC#5 responsiveness (Story 5.2.8), AC#6 negative cases
(Story 5.2.9)) is formalized above rather than duplicated.

**Known limitation tested — bare-URL `EXTRA_TEXT`** (pre-mortem.md failure #5, P2): many share
sheets (browser "Share" on an article) send only the bare URL as `EXTRA_TEXT`, not article
prose. Against a bare URL, `PageNameIndex` matching has nothing to match and
`TopicExtractor`'s noun-phrase heuristic has no prose to extract from, so the flagship "share an
article" scenario can legitimately produce zero auto-links and zero/minimal suggestion chips.
This is the honest expected behavior, not an undefined case — add a test row asserting it
explicitly rather than leaving it implicit: `scan_bareUrlExtraText_producesNoAutoLinksAndNoOrMinimalSuggestions`
(`kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinatorTest.kt`,
Unit) — *Given* `captureText == "https://example.com/some-article"` and no existing page name
appears verbatim in the URL string, *When* `coordinator.scan(...)` runs, *Then*
`result.linkedText == captureText` (no auto-links) and `result.topicSuggestions` is empty or
near-empty, documenting the expected (if unhelpful) v1 behavior rather than pretending it's
untested.

## UX Acceptance Tests

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Zero-friction save (0–1 tap) | `CaptureActivityTest.kt` | `save_zeroSuggestions_singleTapSavesAndFinishes` | Compose UI test (Robolectric) | Render `CaptureScreen` with no matcher hits; type text; tap Save once; assert `finish()`-equivalent callback fires with no intermediate composable |
| 2. Save never gated on scan progress | `CaptureActivityTest.kt` | `saveButton_alwaysEnabledRegardlessOfScanState_tapProducesImmediateSave` | Compose UI test | Render at `NotReady` and both `Ready` shapes (`CaptureViewModel.ScanState` has no distinct in-flight variant); assert Save stays enabled in every one and a tap reaches the button's `onClick` without throwing. (Asserting `save()`'s async `viewModelScope` completion itself is out of scope here — this Compose test harness's virtual-time `TestDispatcher` does not drive coroutines launched outside composition; that path is covered at the unit level by `CaptureViewModelTest.kt`'s `save_*` tests instead.) |
| 3. Chip accept is 1 tap, no dialog | `CaptureActivityTest.kt` | `captureSuggestionChip_accept_firesConfirmHapticSynchronously` (renamed from `chipAccept_singleTap_noConfirmationDialogShown` — `CaptureActivity.kt` has no `Dialog`/`AlertDialog` composable anywhere in the file, so a single `performClick()` firing `onAccept` synchronously, which this test already asserts, is the whole of "no dialog, one tap") | Compose UI test | Render one pending chip; tap its accept region; assert `onAccept` fires from the single tap with no dialog interposed |
| 4. Chip dismiss is 1 tap, no write | `CaptureActivityTest.kt` / `CaptureViewModelTest.kt` | `captureSuggestionChip_dismiss_neverFiresConfirmHaptic` (chip-level: single tap fires `onDismiss`) + `dismissSuggestion_setsDismissedTrue_noWriteInvoked` (view-model level: dismiss never calls `GraphWriter.savePage`) — renamed/split from `chipDismiss_singleTapOnX_removesChipNoWrite`; production's tray `filterNot { it.dismissed }` (`CaptureActivity.kt`) then removes the chip on next recomposition | Compose UI test + Unit test | Tap `×`; assert `onDismiss` fires and no write is invoked |
| 5. No visible latency gating Save | `CaptureActivityTest.kt` | `scanningState_noSpinnerComposableExistsInTree` (renamed from `saveButton_neverShowsLoadingOrDisabledStateDuringScan` — merged into the row-6 test below since both sweep the same scan states for the same absence) | Compose UI test | Sweep scan states; assert Save never renders a spinner, skeleton, or disabled modifier |
| 6. Scanning state invisible (~200ms) | `CaptureActivityTest.kt` | `scanningState_noSpinnerComposableExistsInTree` | Compose UI test | Assert the composable tree contains no spinner/progress indicator node under any `scanState`, structurally enforcing "never a spinner" |
| 7. No-suggestions renders nothing | `CaptureActivityTest.kt` | `pendingSuggestions_empty_rendersNoEmptyStateLabel` | Compose UI test | `scanState == NotReady` or `Ready` with zero suggestions; assert no empty-state `Text`/placeholder node exists |
| 8. Tray never displaces text field | `CaptureActivityTest.kt` | `suggestionTrayAppearing_textFieldPositionAndFocusUnchanged` | Compose UI test | Capture text field's semantics bounds/focus before and after tray appears; assert unchanged, only the button row shifts |
| 9. Chip cap 3–4, no disclosure control | `CaptureActivityTest.kt` | `pendingSuggestions_sevenCandidates_onlyTop4RenderedNoShowMoreButton` | Compose UI test | Feed 7 `TopicSuggestion`s; assert exactly the top 4 by confidence render and no "Show more" node exists anywhere |
| 10. `[[bracket]]` read-only preview, field untouched | `CaptureActivityTest.kt` | `autoLinkPreview_rendersBracketSyntax_textFieldValueUnchanged` | Compose UI test | Assert preview `Text` shows `linkedText`; assert `OutlinedTextField.value` still equals raw `captureText` |
| 11. Auto-links carry no chip affordance | `CaptureActivityTest.kt` | `autoLinkPreviewLine_notTappableOrDismissible_onlyNewPageSuggestionsAreChips` | Compose UI test | Assert the preview line has no click/dismiss semantics; only tray chips do |
| 12. Zero pending chips → immediate finish | `CaptureActivityTest.kt` | `postSave_zeroPendingChips_finishesImmediatelyNoAddedFrame` | Compose UI test | `saveState == Saved`, `pendingSuggestions.isEmpty()`; assert `onSaved()` fires with no `delay()` |
| 13. ≥1 pending chip → "✓ Saved", ~2.75s resettable auto-finish | `CaptureActivityTest.kt` | `postSave_pendingChips_showsSavedStateAndAutoFinishesAfter2750ms` | Compose UI test (`TestDispatcher`/virtual time) | Assert "✓ Saved" label renders instead of Dismiss/Save row; advance virtual time 2750ms with no interaction; assert `onSaved()` fires; repeat with a chip tap mid-window and assert the timer restarts from full duration |
| 14. No dead ends — both exits present | `CaptureActivityTest.kt` | `postSaveDoneWindow_scrimTapFinishesImmediately_bypassingAutoFinishTimer` | Compose UI test | In the "Done" window, tap scrim before the 2750ms timer elapses; assert immediate finish, paired with criterion 13's timer-only exit to confirm both paths exist |
| 15. Done window field doesn't silently discard input | `CaptureActivityTest.kt` | `postSaveDoneWindow_textFieldDisabled_noSilentKeystrokeDiscard` | Compose UI test | Assert `OutlinedTextField.enabled == false` once `isDone == true` |
| 16. Failed accept surfaced via snackbar naming the term | `CaptureActivityTest.kt` | `chipFailure_emittedMessage_showsSnackbarNamingFailedTerm` (renamed from `acceptSuggestion_writeFails_showsSnackbarNamingFailedTerm`) | Compose UI test | Emit on `viewModel.chipFailure`; assert `snackbarHostState` shows `"Couldn't create page for \"<term>\""` |
| 17. Failed accept does not offer retry | `CaptureViewModelTest.kt` | `acceptSuggestion_preSaveStubWriteFails_isolatedFailureEmitsChipFailureOtherChipsUnaffected` / `acceptSuggestion_stubPageWriteFails_blockWriteAndMarkdownFlushUnaffected` | Integration test (Robolectric) | **Corrected 2026-08-28**: this row previously read "Failed chip remains tappable (retry)" / `acceptSuggestion_writeFails_chipReturnsToPendingStateForRetry`, describing a retry affordance that was never implemented and contradicts `plan.md` Story 4.1.1's actual, later-decided design (`plan.md` line 942: "The chip itself is unaffected by this addition: it is left exactly as it already was pre-tap ... not `accepted`, not removed" is about the *tap-time* fold, and `markAccepted()`/`createStubPage()` in `CaptureViewModel.kt` confirm the fold happens synchronously and is never reverted on a later async write failure). Actual behavior: `markAccepted(term)` folds the chip to `accepted = true` synchronously at tap time, before the async stub-page write even starts; `CaptureActivity.kt`'s tray filter (`filterNot { it.dismissed \|\| it.accepted }`) then removes the chip from the tray on the next recomposition regardless of whether the subsequent write succeeds or fails. A failed write only logs the error and emits `chipFailure` (row 16) — it never reverts `accepted`, never re-adds the chip to the tray, and there is no retry affordance in v1. Assert: after a failed stub-page write, the term stays `accepted` and folded into `linkedText`/the block content, and `chipFailure` is the only visible signal. |
| 18. Chip failure never blocks/delays other work | `CaptureViewModelTest.kt` | `acceptSuggestion_preSaveStubWriteFails_isolatedFailureEmitsChipFailureOtherChipsUnaffected` (pre-save isolation) + `acceptSuggestion_stubPageWriteFails_blockWriteAndMarkdownFlushUnaffected` (post-save: proves the original capture's block write and Bug-8 markdown flush complete even when a chip's stub-page write fails — renamed from `acceptSuggestion_oneChipFails_blockWriteAndOtherChipsUnaffected`) | Integration test (Robolectric) | One chip's write fails; assert the block write and Bug-8 markdown flush complete, and a second pending chip's accept/dismiss still works |
| 19. Confirm haptic not walked back on failure | `CaptureActivityTest.kt` | `captureSuggestionChip_accept_firesConfirmHapticSynchronously` (renamed from `acceptSuggestion_writeFails_confirmHapticStillFiredAtTapTime` — the chip composable fires the haptic synchronously at tap time with no knowledge of the async write's eventual outcome, so "the write later fails" cannot structurally walk it back; this test already proves the haptic fires at tap time) | Compose UI test (spy `HapticFeedback`) | Tap accept; assert `HapticFeedbackType.Confirm` recorded once, at tap time — the chip has no code path that could revert it on a later write failure |
| 20. Touch targets ≥48×48dp (accept + dismiss) | `CaptureActivityTest.kt` | `captureSuggestionChip_acceptAndDismissRegions_meetMinimum48dpTouchTarget` | Compose UI test | Measure both `IconButton`s' semantics bounds; assert ≥48×48dp independently |
| 21. Exact chip `contentDescription` format | `CaptureActivityTest.kt` | `captureSuggestionChip_newPage_contentDescriptionMatchesExactFormat` (renamed from `captureSuggestionChip_contentDescription_matchesExactSpelledOutConfidenceFormat`; paired with `captureSuggestionChip_confidenceThresholds_produceExpectedContentDescriptionWord` for the 0.9/0.5/0.2 → high/medium/low mapping and `captureSuggestionChip_existingLink_contentDescriptionHasNoConfidenceClause` for the confirm-first variant) | Compose UI test | Assert `contentDescription == "Suggested page, <term>, confidence <high|medium|low>. Double-tap to accept."` at 0.9/0.5/0.2 confidence |
| 22. Screen-reader dismiss via `customActions` | `CaptureActivityTest.kt` | `captureSuggestionChip_talkBackCustomActions_includesDismissSuggestionAction` | Compose UI test | Assert semantics `customActions` contains a "Dismiss suggestion" action that invokes `onDismiss()` |
| 23. Polite (non-interrupting) live region | `CaptureActivityTest.kt` | `suggestionTray_liveRegion_isPoliteNeverAssertive` | Compose UI test | Assert tray container's `liveRegion == LiveRegionMode.Polite` |
| 24. Auto-finish timer paused (not extended) during accessibility focus | `CaptureActivityTest.kt` | `postSaveDoneWindow_accessibilityFocusPresent_autoFinishTimerNeverStarts` | Compose UI test | Set `hasAccessibilityFocus = true` before entering "Done"; assert the `delay(2_750)` `LaunchedEffect` never fires `onSaved()`; clear focus and assert it then starts fresh |
| 25. Confidence-dot contrast ≥3:1 (WCAG SC 1.4.11) | Manual QA | `manual_confidenceDotContrast_meetsSC1411AcrossThemeModes` | Contrast-checker (e.g. browser devtools / axe) against the rendered app | Render high/medium/low chips in Light, Dark, and Stone theme modes; measure dot-vs-background contrast; confirm ≥3:1 for all three, flagging unthemed `secondary`/`error` defaults in Dark/Stone if they fail |
| 26. Reduced motion respected | Manual QA | `manual_reducedMotion_fadeDegradesToInstantAppearDisappear` | Manual device QA (system "remove animations" setting) | Enable Android's reduced-motion setting; trigger the tray/preview fade-in; confirm it degrades to instant appear/disappear rather than being skipped or left animated |

**Coverage**: 26/26 UX acceptance criteria from `design/ux.md` mapped — 23 as Compose
UI/Robolectric tests (in the new `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureActivityTest.kt`),
1 as an integration `CaptureViewModelTest` case, and 2 (contrast, reduced-motion) as manual QA
steps per `design/ux.md`'s own statement that these "cannot be confirmed from source alone."

## Test Stack

- **Unit**: `kotlin.test` + `kotest-assertions-core`/`kotest-property` (assertions/generators
  only, not the Kotest runner — see root `CLAUDE.md`). `kmp/src/commonTest` for
  `CaptureEnrichmentCoordinatorTest` (pure logic, fake `PageRepository`/`TopicEnricher`, no
  real DB or file I/O). `kmp/src/businessTest` for `GraphManagerEnrichmentCoordinatorTest`
  (real `GraphManager` against an `IN_MEMORY` repository backend — fast, no file system).
  Isolated `CaptureViewModelTest` cases that use only fakes/spies for `GraphManager`/
  `GraphWriter`/`DatabaseWriteActor` (no real `SteleKitApplication`) also count as unit-level.
- **Integration**: `androidApp/src/test` Robolectric tests running against a real
  `SteleKitApplication`/`GraphManager`/`GraphWriter`/coroutine scope (per Phase 5's note: do
  **not** apply `@Config(application = Application::class)` for these — that override defeats
  the point). Covers `CaptureViewModelTest`'s save/accept/race paths and the new
  `CaptureActivityTest` Compose UI tests exercising real composition, semantics, and
  (`TestDispatcher`-controlled) virtual time.
- **E2E / UX**: Compose UI tests (Robolectric `createComposeRule`) for the 24 automatable UX
  criteria in `CaptureActivityTest.kt`; 2 manual QA steps (confidence-dot contrast across theme
  modes, reduced-motion) that `design/ux.md` itself flags as unverifiable from source.

## Coverage Targets and How to Measure

**Chip-tray adoption is not measurable post-ship, and this is an accepted v1 tradeoff, not an
oversight.** Pre-mortem.md failure #1 (P2) flags that the chip tray — this feature's only
value-add beyond auto-linking — could go largely unused, and asks at minimum for an explicit
note here on whether that's acceptable for a v1 ship gate. It is: `plan.md`'s Observability Plan
and Risk Control sections both already establish "no metrics, log-only" as the deliberate scope
for this feature (matching the existing `llm-service`/`TagSuggestionEngine` precedent, and the
plan's own "no new abstractions beyond what the task requires" constraint) — adding a
chip-tap-rate counter would mean adding new instrumentation specifically to measure this
feature, which the plan already declined to do for good reason. Consequence: whether the chip
tray gets used at all cannot be answered from telemetry after ship; it can only be inferred
indirectly (bug reports, anecdote) — accepted here as the tradeoff for staying within the
existing observability scope, not a gap left unaddressed.

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (`kmp` module) | `bazel test //kmp:business_tests` / `bazel test //kmp:jvm_tests` (or `./gradlew jvmTest`) | All new `CaptureEnrichmentCoordinator`/`GraphManagerEnrichmentCoordinatorTest` cases pass |
| Android (`androidApp` module) | `./gradlew testDebugUnitTest` (Robolectric) | All new/extended `CaptureViewModelTest`/`CaptureActivityTest` cases pass |
| Full CI gate | `./gradlew ciCheck` / `bazel test //... --config=ci` | Green, no regression in existing `CaptureViewModelTest`/`CaptureShareTextTest`/`ImportServiceTest`/`PageNameIndexTest` |

- All public `CaptureEnrichmentCoordinator`/`GraphManager.getOrCreateEnrichmentCoordinator()`/
  `CaptureViewModel` methods touched by this feature: happy path + error path covered per the
  Requirement → Test Mapping table above.
- All external integrations (real `GraphWriter`/`DatabaseWriteActor`/`LlmProviderRegistry`
  wiring): unit-tested with fakes, plus at least one integration test against the real
  `SteleKitApplication`/`GraphManager` stack (see AC #1, #2, #7, #9 integration rows).
- UX acceptance criteria: all 26 from `design/ux.md` have a corresponding automated test or an
  explicitly-named manual QA step — none silently dropped.
- Migration test: **N/A** — `plan.md`'s Migration Plan states "no schema or data changes"; no
  `MigrationRunner` entries added, so no `migration_should_be_reversible` test is required.
