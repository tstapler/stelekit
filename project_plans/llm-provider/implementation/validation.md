# Validation Plan: llm-provider

**Date**: 2026-06-13

---

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| REQ-1 | `TagSuggestionEngineTest.kt` | `` `directMatch, should return confidence 1.0 autoApplied suggestions when block contains page names` `` | Unit | Happy path: AhoCorasick finds matching page names in block text |
| REQ-1 | `TagSuggestionEngineTest.kt` | `` `directMatch, should return empty list when matcher is null` `` | Unit | Edge: PageNameIndex not yet built (null matcher) returns empty without crash |
| REQ-1 | `TagSuggestionEngineIntegrationTest.kt` | `` `directMatch, should find page names from InMemoryPageRepository when graph is populated` `` | Integration | End-to-end: pages loaded into InMemoryPageRepository, engine scans block text offline |
| REQ-2 | `LlmTagProviderTest.kt` | `` `suggestTags, should return suggestions constrained to vocabulary when LLM returns valid names` `` | Unit | Happy path: LLM returns names all within the vocabulary list |
| REQ-2 | `LlmTagProviderTest.kt` | `` `suggestTags, should filter out LLM responses not in vocabulary when model hallucinates` `` | Unit | Edge: model returns page names not in the allowed vocabulary; all filtered out |
| REQ-2 | `LlmTagProviderIntegrationTest.kt` | `` `suggestTags, should call provider only with pre-filtered vocabulary when block text overlaps subset` `` | Integration | Vocabulary pre-filter: only token-overlapping names are sent to the fake LlmFormatterProvider |
| REQ-3 | `TagSuggestionEngineTest.kt` | `` `directMatch, should set autoApplied true for all local matches when AhoCorasick finds hits` `` | Unit | Happy path: all LOCAL source results have autoApplied=true and confidence=1.0 |
| REQ-3 | `TagSuggestionEngineTest.kt` | `` `directMatch, should never set autoApplied false for local hits even when they duplicate LLM results` `` | Unit | Edge: local match appears in both tiers; deduplication keeps LOCAL with autoApplied=true |
| REQ-3 | `TagSuggestionEngineIntegrationTest.kt` | `` `directMatch, should auto-apply all exact page name hits without threshold check when engine runs against real index` `` | Integration | Confirm that AUTO_APPLY_THRESHOLD does not gate local results in integrated flow |
| REQ-4 | `TagSuggestionViewModelTest.kt` | `` `requestSuggestions, should emit Ready with non-empty llmSuggestions when LLM provider returns results` `` | Unit | Happy path: state machine transitions to Ready with populated llmSuggestions list |
| REQ-4 | `TagChipRowTest.kt` | `` `TagChipRow, should render chip for each non-autoApplied suggestion when state is Ready` `` | UI | Happy path: each LLM suggestion renders a visible FilterChip |
| REQ-4 | `SuggestionBottomSheetTest.kt` | `` `SuggestionBottomSheet, should show bottom sheet with chip row when state is Ready with suggestions` `` | UI | Integration: ModalBottomSheet visible with chip contents in Ready state |
| REQ-5 | `TagChipRowTest.kt` | `` `TagChipRow, should invoke onAccept callback when chip is tapped` `` | UI | Happy path: tap gesture triggers acceptance callback with correct term |
| REQ-5 | `TagChipRowTest.kt` | `` `TagChipRow, should invoke onDismiss callback when chip is long-pressed` `` | UI | Edge: long-press triggers dismissal callback with correct term |
| REQ-5 | `TagSuggestionViewModelTest.kt` | `` `dismiss, should transition state to Idle when called while in Ready state` `` | Unit | Edge: user dismisses entire suggestion sheet, state returns to Idle |
| REQ-6 | `TagSuggestionEngineTest.kt` | `` `llmSuggest, should return empty right when llmTagProvider is null` `` | Unit | Happy path for offline: null LLM provider returns empty list without error |
| REQ-6 | `TagSuggestionViewModelTest.kt` | `` `requestSuggestions, should emit Ready with only localSuggestions when no LLM provider is configured` `` | Unit | Edge: engine has null provider; state reaches Ready without llmSuggestions |
| REQ-6 | `TagSuggestionEngineIntegrationTest.kt` | `` `llmSuggest, should degrade gracefully to empty right when LLM provider is configured but throws network error` `` | Integration | Offline degradation: fake provider throws NetworkError; ViewModel surfaces llmError string, local results retained |
| REQ-7 | `PageScopeTaggingTest.kt` | `` `suggestTagsForPage, should aggregate all untagged blocks and emit single LLM call when page-scope action triggered` `` | Integration | Happy path: overflow menu action results in one LLM call covering ≤20 blocks, ≤500 chars each |
| REQ-7 | `PageScopeTaggingTest.kt` | `` `suggestTagsForPage, should cap block aggregation at 20 blocks when page has more` `` | Unit | Edge: page has 30 untagged blocks; only first 20 sent to engine |
| REQ-7 | `PageScopeTaggingTest.kt` | `` `suggestTagsForPage, should apply autoApplied results and show chip row for low-confidence when page scope runs` `` | Integration | Integration: auto-applied tags written to blocks; chip row shown for LLM-only suggestions |
| REQ-8 | `BlockScopeTaggingTest.kt` | `` `onRequestTagSuggestions, should invoke TagSuggestionViewModel requestSuggestions with correct blockUuid when context menu action selected` `` | Unit | Happy path: BlockList callback fires ViewModel with the selected block's UUID |
| REQ-8 | `BlockScopeTaggingTest.kt` | `` `onRequestTagSuggestions, should show SuggestionBottomSheet when block-scope action triggered and state becomes Ready` `` | UI | Integration: Compose test verifies sheet is visible after block action |
| REQ-8 | `BlockScopeTaggingTest.kt` | `` `onRequestTagSuggestions, should cancel previous in-flight suggestion when different block selected` `` | Unit | Edge: second block selected before first LLM call resolves; previous job cancelled, state transitions to Loading |
| REQ-9 | `ShareTagActivityTest.kt` | `` `onCreate, should save note to today journal and trigger local scan when ACTION_SEND intent received` `` | Integration | Happy path: share intent saves note, local matcher returns suggestions |
| REQ-9 | `TagSuggestionWorkerTest.kt` | `` `doWork, should return Result success and post notification when LLM call succeeds after share capture` `` | Integration | Happy path: worker initialises DB with migration guard, calls LLM, posts notification |
| REQ-9 | `TagSuggestionWorkerTest.kt` | `` `doWork, should return Result success even when LLM call fails due to network error` `` | Unit | Edge: LLM fails; worker still returns success (retry suppression is intentional for MVP) |
| REQ-10 | `VoiceCaptureViewModelTest.kt` | `` `transcribe, should run TagSuggestionEngine after transcription completes when engine is injected` `` | Unit | Happy path: engine fires after transcription, Done state has non-empty suggestedTags |
| REQ-10 | `VoiceCaptureViewModelTest.kt` | `` `transcribe, should produce Done state with empty suggestedTags when no TagSuggestionEngine is injected` `` | Unit | Edge: null engine; Done state still emitted with suggestedTags=[] |
| REQ-10 | `VoiceCaptureButtonTest.kt` | `` `VoiceCaptureButton, should show TagChipRow when Done state has non-empty suggestedTags` `` | UI | Happy path: chip row rendered after voice transcription completes |
| REQ-11 | `LlmProviderSettingsTest.kt` | `` `LlmProviderSettings, should save provider type and API key when user fills form and taps save` `` | UI | Happy path: toggle + provider selector + API key field persist to TagSettings |
| REQ-11 | `TagSettingsTest.kt` | `` `TagSettings, should read back configured values from settings store when keys are written` `` | Unit | Happy path: written settings keys round-trip correctly |
| REQ-11 | `LlmProviderSettingsTest.kt` | `` `LlmProviderSettings, should show base URL field only when OpenAI-compatible provider is selected` `` | UI | Edge: provider selector controls conditional visibility of endpoint field |

---

## Test Stack

- **Unit**: `kotlin.test` + `kotlinx-coroutines-test` (`runTest`), fake/stub dependencies injected via constructor
- **Integration**: `kotlin.test` + `runTest` + `InMemoryPageRepository` + fake `LlmFormatterProvider`
- **UI/Compose**: Compose UI testing (`jvmTest`) with `ComposeTestRule` / `createComposeRule()`

---

## Coverage Targets

- Unit test coverage: ≥80% (line) for `tags/` package
- All public service methods (`TagSuggestionEngine`, `LlmTagProvider`, `TagSuggestionViewModel`): happy path + at least one error path each
- All external integrations (LLM provider, WorkManager worker, share Activity): unit-mocked + at least one integration test each

---

## Unit Tests (detailed list by component)

### TagSuggestionEngine (`businessTest`)

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngineTest.kt`

| # | Test Name | Assertion Focus |
|---|-----------|-----------------|
| 1 | `` `directMatch, should return confidence 1.0 autoApplied suggestions when block contains page names` `` | Results have `confidence=1.0f`, `source=LOCAL`, `autoApplied=true` |
| 2 | `` `directMatch, should return empty list when matcher is null` `` | Null matcher short-circuits; no exception |
| 3 | `` `directMatch, should set autoApplied true for all local matches when AhoCorasick finds hits` `` | All LOCAL results have `autoApplied=true`; no threshold gate applied |
| 4 | `` `directMatch, should never set autoApplied false for local hits even when they duplicate LLM results` `` | Deduplication keeps LOCAL entry with `autoApplied=true` |
| 5 | `` `directMatch, should be case-insensitive when matching block text against page names` `` | Lowercased block content matches mixed-case page names |
| 6 | `` `llmSuggest, should return empty right when llmTagProvider is null` `` | Returns `Either.Right(emptyList())` without calling provider |
| 7 | `` `llmSuggest, should exclude alreadyLinkedTerms from vocabulary before calling provider` `` | Vocabulary passed to provider does not include already-linked page names |
| 8 | `` `llmSuggest, should deduplicate results by lowercased term keeping first occurrence` `` | Duplicate term in LLM output appears once in result |
| 9 | `` `AUTO_APPLY_THRESHOLD, should be above the maximum LLM confidence of 0.85 so LLM suggestions never auto-apply` `` | `AUTO_APPLY_THRESHOLD (0.95f) > 0.85f` |

### LlmTagProvider (`jvmTest`)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/LlmTagProviderTest.kt`

| # | Test Name | Assertion Focus |
|---|-----------|-----------------|
| 1 | `` `suggestTags, should return suggestions constrained to vocabulary when LLM returns valid names` `` | Returned terms are all members of `pageVocabulary` |
| 2 | `` `suggestTags, should filter out LLM responses not in vocabulary when model hallucinates` `` | Non-vocabulary terms absent from result |
| 3 | `` `suggestTags, should apply positional confidence decay starting at 0.85 when response has multiple suggestions` `` | Position 0 → 0.85f, position 1 → 0.83f, position 10+ → 0.5f |
| 4 | `` `suggestTags, should truncate block content to 500 chars before sending to provider` `` | Fake provider receives content ≤500 chars |
| 5 | `` `suggestTags, should pre-filter vocabulary to max 200 names with token overlap before sending` `` | Fake provider receives ≤200 vocabulary items |
| 6 | `` `suggestTags, should return Timeout DomainError when provider takes longer than 8 seconds` `` | Returns `Either.Left(DomainError.NetworkError.Timeout)` with message |
| 7 | `` `suggestTags, should return HttpError DomainError when provider returns ApiError` `` | `DomainError.NetworkError.HttpError` with correct status code |
| 8 | `` `suggestTags, should return empty right when token-filtered vocabulary is empty` `` | No provider call made; returns empty list |
| 9 | `` `suggestTags, should rethrow CancellationException without wrapping` `` | `CancellationException` propagates (cooperative cancellation preserved) |

### TagSuggestionViewModel (`businessTest`)

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt`

| # | Test Name | Assertion Focus |
|---|-----------|-----------------|
| 1 | `` `requestSuggestions, should transition to Loading then Ready with localSuggestions when called` `` | State sequence: `Idle → Loading → Ready` |
| 2 | `` `requestSuggestions, should emit Ready with non-empty llmSuggestions when LLM provider returns results` `` | Second `Ready` update populates `llmSuggestions` |
| 3 | `` `requestSuggestions, should emit Ready with only localSuggestions when no LLM provider is configured` `` | `llmSuggestions` remains empty; no `llmError` |
| 4 | `` `requestSuggestions, should update llmError on Ready state when LLM tier returns DomainError` `` | `Ready.llmError` non-null; `localSuggestions` still present |
| 5 | `` `requestSuggestions, should cancel previous in-flight suggestion when different block selected` `` | Previous job cancelled; state transitions to `Loading` for new block |
| 6 | `` `requestSuggestions, should not update state for stale blockUuid when second call supersedes first` `` | State reflects second call's blockUuid, not first |
| 7 | `` `dismiss, should transition state to Idle when called while in Ready state` `` | `state.value` is `Idle` after `dismiss()` |
| 8 | `` `close, should cancel scope so subsequent requestSuggestions has no effect` `` | After `close()`, `requestSuggestions()` does not change state |
| 9 | `` `CoroutineExceptionHandler, should set Error state instead of crashing on uncaught throwable` `` | Uncaught `RuntimeException` in scope sets `Error` state |

### WikiLinkExtractor (`businessTest`)

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/WikiLinkExtractorTest.kt`

| # | Test Name | Assertion Focus |
|---|-----------|-----------------|
| 1 | `` `extract, should return all WikiLink page names when block contains multiple links` `` | All `[[PageName]]` patterns extracted |
| 2 | `` `extract, should return empty set when block has no WikiLinks` `` | Empty set; no crash |
| 3 | `` `extract, should handle nested brackets and aliases when block uses piped links` `` | `[[Display|Target]]` patterns handled correctly |

### TagSettings (`businessTest`)

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSettingsTest.kt`

| # | Test Name | Assertion Focus |
|---|-----------|-----------------|
| 1 | `` `TagSettings, should read back configured values from settings store when keys are written` `` | Round-trip: written keys match read values |
| 2 | `` `TagSettings, should return disabled and null provider when no settings have been configured` `` | Defaults: `suggest_enabled=false`, `suggest_provider=null` |
| 3 | `` `TagSettings, should store openai_base_url separately from API key` `` | Base URL and API key stored under distinct keys |

---

## Integration Tests (detailed list)

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngineIntegrationTest.kt`

| # | Test Name | Components Under Test | Assertion Focus |
|---|-----------|----------------------|-----------------|
| 1 | `` `directMatch, should find page names from InMemoryPageRepository when graph is populated` `` | `TagSuggestionEngine` + `PageNameIndex` + `InMemoryPageRepository` | Pages loaded into repo are discoverable by local scan without network |
| 2 | `` `directMatch, should auto-apply all exact page name hits without threshold check when engine runs against real index` `` | `TagSuggestionEngine` + `PageNameIndex` | `AUTO_APPLY_THRESHOLD` does not gate LOCAL results; all hits returned |
| 3 | `` `llmSuggest, should call provider only with pre-filtered vocabulary when block text overlaps subset` `` | `TagSuggestionEngine` + fake `LlmTagProvider` | Only token-overlapping names from vocab passed to provider |
| 4 | `` `llmSuggest, should degrade gracefully to empty right when LLM provider is configured but throws network error` `` | `TagSuggestionEngine` + `TagSuggestionViewModel` + failing fake provider | `Ready.llmError` populated; `localSuggestions` still present and correct |
| 5 | `` `suggestTagsForPage, should aggregate all untagged blocks and emit single LLM call when page-scope action triggered` `` | `TagSuggestionEngine` + `blockStateManager` stub + fake `LlmTagProvider` | Exactly one LLM call; covers ≤20 blocks, ≤500 chars each |
| 6 | `` `suggestTagsForPage, should cap block aggregation at 20 blocks when page has more` `` | Page-scope aggregation logic | `blockStateManager.blocks.value[pageUuid]` capped at 20 entries |
| 7 | `` `suggestTagsForPage, should apply autoApplied results and show chip row for low-confidence when page scope runs` `` | `TagSuggestionEngine` + fake `LlmTagProvider` + `appendToBlock` stub | Auto-applied tags appended to blocks; remaining suggestions placed in chip row |
| 8 | `` `onRequestTagSuggestions, should cancel previous in-flight suggestion when different block selected` `` | `TagSuggestionViewModel` + fake engine | Second block cancels first in-flight job |
| 9 | `` `doWork, should return Result success and post notification when LLM call succeeds after share capture` `` | `TagSuggestionWorker` + fake DB + fake `LlmTagProvider` | `Result.success()` returned; notification posted |
| 10 | `` `doWork, should return Result success even when LLM call fails due to network error` `` | `TagSuggestionWorker` + failing fake provider | `Result.success()` (intentional retry suppression for MVP); no crash |
| 11 | `` `transcribe, should run TagSuggestionEngine after transcription completes when engine is injected` `` | `VoiceCaptureViewModel` + fake `TagSuggestionEngine` | `Done.suggestedTags` non-empty after transcription |
| 12 | `` `transcribe, should produce Done state with empty suggestedTags when no TagSuggestionEngine is injected` `` | `VoiceCaptureViewModel` (engine=null) | `Done.suggestedTags` is empty; no crash |

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/LlmTagProviderIntegrationTest.kt`

| # | Test Name | Components Under Test | Assertion Focus |
|---|-----------|----------------------|-----------------|
| 13 | `` `suggestTags, should call provider only with pre-filtered vocabulary when block text overlaps subset` `` | `LlmTagProvider` + `FakeLlmFormatterProvider` | Captured system prompt contains only token-overlapping names |
| 14 | `` `suggestTags, should return empty right without calling provider when vocabulary is empty after filter` `` | `LlmTagProvider` + `FakeLlmFormatterProvider` | Provider never invoked; returns `Right(emptyList())` |

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/ShareTagActivityTest.kt`

| # | Test Name | Components Under Test | Assertion Focus |
|---|-----------|----------------------|-----------------|
| 15 | `` `onCreate, should save note to today journal and trigger local scan when ACTION_SEND intent received` `` | `ShareTagActivity` + fake `JournalService` + fake `TagSuggestionEngine` | `journalService.appendToToday(content)` called (not `appendToTodayBlock`) |

---

## UI / Compose Tests (detailed list)

Source set: `jvmTest`

### TagChipRow

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/ui/TagChipRowTest.kt`

| # | Test Name | Interaction | Assertion |
|---|-----------|-------------|-----------|
| 1 | `` `TagChipRow, should render chip for each non-autoApplied suggestion when state is Ready` `` | No interaction | N chips visible matching non-autoApplied suggestion list |
| 2 | `` `TagChipRow, should invoke onAccept callback when chip is tapped` `` | Single tap on chip | `onAccept` invoked with matching `TagSuggestion.term` |
| 3 | `` `TagChipRow, should invoke onDismiss callback when chip is long-pressed` `` | Long press on chip | `onDismiss` invoked with matching `TagSuggestion.term` |
| 4 | `` `TagChipRow, should show loading indicator when state is Loading` `` | No interaction | `CircularProgressIndicator` (or equivalent) is displayed |
| 5 | `` `TagChipRow, should hide autoApplied suggestions from chip row` `` | No interaction | Chips with `autoApplied=true` are not rendered in the row |

### SuggestionBottomSheet

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/ui/SuggestionBottomSheetTest.kt`

| # | Test Name | Interaction | Assertion |
|---|-----------|-------------|-----------|
| 6 | `` `SuggestionBottomSheet, should show bottom sheet with chip row when state is Ready with suggestions` `` | No interaction | `ModalBottomSheet` visible; `TagChipRow` content present |
| 7 | `` `SuggestionBottomSheet, should display LLM error message when Ready state has llmError set` `` | No interaction | Error text from `llmError` is visible in sheet |
| 8 | `` `SuggestionBottomSheet, should hide when state transitions to Idle` `` | No interaction | Sheet not visible after state set to `Idle` |

### VoiceCaptureButton

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/ui/VoiceCaptureButtonTest.kt`

| # | Test Name | Interaction | Assertion |
|---|-----------|-------------|-----------|
| 9 | `` `VoiceCaptureButton, should show TagChipRow when Done state has non-empty suggestedTags` `` | No interaction | Chip row visible with correct suggestion terms |
| 10 | `` `VoiceCaptureButton, should not show TagChipRow when Done state has empty suggestedTags` `` | No interaction | Chip row absent from composition |

### LlmProviderSettings

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/ui/LlmProviderSettingsTest.kt`

| # | Test Name | Interaction | Assertion |
|---|-----------|-------------|-----------|
| 11 | `` `LlmProviderSettings, should save provider type and API key when user fills form and taps save` `` | Fill text fields + tap save | `TagSettings` store contains typed values |
| 12 | `` `LlmProviderSettings, should show base URL field only when OpenAI-compatible provider is selected` `` | Select provider from dropdown | Base URL field visible for OpenAI; hidden for other providers |
| 13 | `` `LlmProviderSettings, should disable suggestion toggle by default when first opened` `` | No interaction | Toggle is unchecked in initial state |

---

## Implementation Readiness Notes

### Adversarial Review Concerns (2 remaining)

**CONCERN 1 — `appendTagToLastBlock` calls non-existent `journalService.appendToTodayBlock(text)`**

The correct method name is `journalService.appendToToday(text)` (no "Block" suffix). This is covered by the integration test `` `onCreate, should save note to today journal and trigger local scan when ACTION_SEND intent received` `` in `ShareTagActivityTest.kt`, which asserts the call is made against `appendToToday()`. If the implementation uses `appendToTodayBlock()` the test will fail at compile time because the method does not exist on `JournalService`.

**CONCERN 2 — Story 4.2.1 acceptance criteria says `insertTextAtCursor` but task code says `appendToBlock`**

Covered by the integration test `` `suggestTagsForPage, should apply autoApplied results and show chip row for low-confidence when page scope runs` ``. The test uses a `blockStateManager` stub that tracks calls to `appendToBlock()`; any invocation of `insertTextAtCursor()` instead will result in no call recorded and the test assertion will fail. This provides compile-and-runtime verification that the correct method is used.

### Minor Notes (2 retained from adversarial review)

**MINOR 1 — Key Design Decisions Summary still reads `insertTextAtCursor`**

This is a documentation inconsistency only (plan line in Key Design Decisions Summary). Covered by the same test as Concern 2. No code change required; the test enforces the correct method regardless of what the doc summary says.

**MINOR 2 — `TagSuggestionEngine.AUTO_APPLY_THRESHOLD = 0.95f` is dead code for LLM suggestions**

Covered by test `` `AUTO_APPLY_THRESHOLD, should be above the maximum LLM confidence of 0.85 so LLM suggestions never auto-apply` `` in `TagSuggestionEngineTest.kt`. The test asserts `TagSuggestionEngine.AUTO_APPLY_THRESHOLD > 0.85f` and that no LLM suggestion in the range 0.5–0.85 has `autoApplied=true`, documenting the intentional ceiling via a compile-time-verified test rather than a comment alone.

### Additional Model Invariant Notes

- `TagSuggestion.confidence: Float` has no `coerceIn` guard in the model itself (clamping enforced only in `LlmTagProvider.parseResponse`). The test `` `suggestTags, should apply positional confidence decay starting at 0.85 when response has multiple suggestions` `` verifies the clamping via the provider boundary. A class-level comment on `TagSuggestion` should document the expected range (0.0–1.0).
- `TagSuggestionWorker` returns `Result.success()` on LLM failure intentionally. The test `` `doWork, should return Result success even when LLM call fails due to network error` `` documents this behaviour as a deliberate MVP trade-off; a TODO comment in the worker should note that `Result.retry()` should be used for `DomainError.NetworkError.*` if retries are added later.
