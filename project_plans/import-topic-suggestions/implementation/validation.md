# Validation Plan: Import Topic Suggestions

**Feature branch**: `stelekit-import`
**Plan date**: 2026-04-17
**Source artifacts**: `docs/tasks/import-topic-suggestions.md`, `project_plans/import-topic-suggestions/requirements.md`, `project_plans/import-topic-suggestions/research/pitfalls.md`

---

## Requirements Coverage Matrix

| # | MoSCoW Must Requirement | Test(s) That Verify It |
|---|---|---|
| M1 | Noun-phrase extraction: detect multi-word noun phrases (e.g. "machine learning") | `TopicExtractorTest.multiWordNounPhrase_detected` |
| M2 | Single-word concept detection: proper nouns and technical terms (e.g. "Kubernetes", "TensorFlow") | `TopicExtractorTest.singleWordTechnicalTerm_detected`, `TopicExtractorTest.camelCaseTerm_detected` |
| M3 | Confidence scoring: rank candidates by frequency, capitalization, length | `TopicExtractorTest.scoringFormula_highFrequencyTermRanksFirst`, `TopicExtractorTest.capAt15_returnedSortedByScore` |
| M4 | Deduplication against existing pages: suggestions exclude terms already in `PageNameIndex` | `TopicExtractorTest.termInExistingNames_excluded`, `ImportServiceTest.scanWithExistingNames_excludesCandidate` |
| M5 | One-click stub creation: accept → stub page via `GraphWriter` + `[[wiki link]]` inserted | `ImportViewModelTest.confirmImport_withAcceptedSuggestion_savesStubPage`, `ImportViewModelTest.confirmImport_finalTextContainsWikiLink` |
| M6 | Bulk accept: accept multiple suggestions before confirming | `ImportViewModelTest.onAcceptAllSuggestions_capsAtTen` |
| M7 | Local-first: heuristic detection with no API key | `ImportViewModelTest.runScan_withNoOpEnricher_claudeStatusStaysIdle`, `TopicExtractorTest` (all unit tests, zero I/O) |
| M8 | Claude API enhancement (opt-in): when key configured, Claude ranks/augments candidates | `ImportViewModelTest.runScan_withRealEnricher_claudeStatusTransitionsToLoading`, `ClaudeTopicEnricherTest.enhance_returnsSuggestions` |
| M9 | Local detection < 500ms on 10 KB input | `TopicExtractorTest.performance_10kbInput_under500ms` (benchmark note) |
| M10 | Zero API calls when key not configured | `ImportViewModelTest.runScan_withNoOpEnricher_claudeStatusStaysIdle` |

---

## Test Pyramid

### Unit Tests (`commonTest` / `businessTest`)

Tests for pure-function domain objects. No coroutines, no UI, no I/O.

Note: `businessTest` source set does not yet exist in this repo; these tests live in `commonTest` following the pattern of `AhoCorasickMatcherTest` and `PageNameIndexTest`. All tests below are placed in `kmp/src/commonTest/`.

---

#### `TopicExtractor.extract()` — `commonTest/domain/TopicExtractorTest.kt`

| Test name | Input | Expected outcome |
|---|---|---|
| `emptyInput_returnsEmpty` | `""`, `emptySet()` | empty list |
| `singleCharInput_returnsEmpty` | `"A"`, `emptySet()` | empty list (below min token length) |
| `allStopwords_returnsEmpty` | `"Introduction API URL HTTP"`, `emptySet()` | empty list (all filtered by `EXTRACTION_STOPWORDS`) |
| `structuralHeader_introduction_filtered` | `"Introduction\n\nMachine Learning is great"`, `emptySet()` | "Introduction" absent; "Machine Learning" present |
| `structuralHeader_conclusion_filtered` | `"Conclusion\n\nVector Database concepts"`, `emptySet()` | "Conclusion" absent |
| `acronym_api_filtered` | Text containing only "API REST HTTP", `emptySet()` | empty list |
| `multiWordNounPhrase_detected` | `"Machine learning and vector databases are key topics"`, `emptySet()` | "Machine Learning" or "Vector Database" in results |
| `twoWordPhrase_2tokens_detected` | `"Kubernetes Operator deployment"`, `emptySet()` | "Kubernetes Operator" in results |
| `fourWordPhrase_capped_at4tokens` | `"Kubernetes Operator Deployment Pattern best practices"`, `emptySet()` | phrase of at most 4 tokens; 5-word phrases not returned |
| `singleCapitalizedProperNoun_detected` | `"TensorFlow is a framework. We use TensorFlow daily."`, `emptySet()` | "TensorFlow" in results |
| `camelCaseTerm_detected` | `"SQLDelight generates type-safe SQL. SQLDelight is great."`, `emptySet()` | "SQLDelight" in results |
| `termInExistingNames_excluded` | `"TensorFlow is great"`, `setOf("tensorflow")` | empty list (exact case-insensitive match excluded) |
| `partialExistingNameMatch_notExcluded` | `"TensorFlow is great"`, `setOf("tensor")` | "TensorFlow" still returned (partial match does not exclude) |
| `confidenceThreshold_exactlyPoint2_included` | synthetic input producing normalized confidence of exactly 0.2 | term included |
| `confidenceThreshold_belowPoint2_excluded` | synthetic input producing score just below threshold | term excluded |
| `capAt15_returnedSortedByScore` | text with 20 distinct capitalized terms each appearing multiple times | exactly 15 results, sorted by score descending |
| `sentenceInitialCapital_notSurfacedUnlessAppearsElsewhere` | `"The algorithm is efficient."` (capital T only at sentence start) | "The Algorithm" NOT in results |
| `sentenceInitialCapital_surfacedWhenAppearsCapitalizedMidSentence` | `"The algorithm is efficient. We call it The Algorithm."` | "The Algorithm" in results (appears capitalized mid-sentence) |
| `deduplication_sameTermMultipleTimes_oneCandidate` | `"Machine Learning is key. Machine Learning is the future."`, `emptySet()` | exactly one "Machine Learning" candidate, score aggregated |
| `scoringFormula_highFrequencyTermRanksFirst` | text with term A appearing 5× and term B appearing 2×, both capitalized | term A has higher score than term B |
| `performance_10kbInput_under500ms` | 10 KB technical article string | completes in < 500ms on JVM (annotation note: mark as `@Ignore` for CI if flaky; verify manually or use `measureTimeMillis` assertion) |

---

#### `ImportService.insertWikiLinks()` — `commonTest/domain/ImportServiceTest.kt` (extend existing file)

| Test name | Input text | Terms | Expected output |
|---|---|---|---|
| `emptyTermsList_textUnchanged` | `"TensorFlow is great"` | `[]` | `"TensorFlow is great"` |
| `singleTermInMiddle_wrapped` | `"I use TensorFlow daily"` | `["TensorFlow"]` | `"I use [[TensorFlow]] daily"` |
| `termAtStart_wrapped` | `"TensorFlow is powerful"` | `["TensorFlow"]` | `"[[TensorFlow]] is powerful"` |
| `termAtEnd_wrapped` | `"I love TensorFlow"` | `["TensorFlow"]` | `"I love [[TensorFlow]]"` |
| `alreadyLinkedOccurrence_firstUnchanged_secondLinked` | `"[[TensorFlow]] and TensorFlow"` | `["TensorFlow"]` | `"[[TensorFlow]] and [[TensorFlow]]"` |
| `alreadyLinkedOccurrence_doubleLink_notCreated` | `"[[TensorFlow]]"` | `["TensorFlow"]` | `"[[TensorFlow]]"` (not `"[[ [[TensorFlow]] ]]"`) |
| `multiWordTerm_wrapped` | `"machine learning approaches"` | `["machine learning"]` | `"[[machine learning]] approaches"` |
| `caseInsensitiveMatch_displayFormUsed` | `"tensorflow is great"` | `["TensorFlow"]` | `"[[TensorFlow]] is great"` |
| `multipleTerms_allWrapped` | `"TensorFlow and Kubernetes are tools"` | `["TensorFlow", "Kubernetes"]` | `"[[TensorFlow]] and [[Kubernetes]] are tools"` |
| `overlappingTerms_longerMatchWins` | `"machine learning is a field"` | `["machine learning", "learning"]` | `"[[machine learning]] is a field"` (not `"machine [[learning]]"`) |
| `partialWordMatch_notWrapped` | `"TensorFlowX is not TensorFlow"` | `["TensorFlow"]` | only the standalone `TensorFlow` is wrapped (word-boundary respected) |

---

#### `ImportService.scan()` extended — `commonTest/domain/ImportServiceTest.kt` (extend existing file)

| Test name | Description |
|---|---|
| `scan_withExistingNames_excludesCandidate` | Scan a text containing "TensorFlow" (capitalized, mid-sentence multiple times); pass `existingNames = setOf("tensorflow")`; assert `topicSuggestions` does not contain "TensorFlow" |
| `scan_withEmptyExistingNames_candidatesEligible` | Same text; pass `existingNames = emptySet()`; assert "TensorFlow" appears in `topicSuggestions` |
| `scan_backwardCompat_twoArgForm` | Call `ImportService.scan(rawText, matcher)` (without `existingNames`); assert it compiles and returns a `ScanResult` with a non-null `topicSuggestions` field (may be empty or non-empty depending on text) |

---

### Integration Tests (`jvmTest`)

Tests involving `ImportViewModel`, coroutines, `StateFlow`, and seam interfaces.

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModelTest.kt` (extend existing file)

All new tests follow the existing pattern: `TestScope(UnconfinedTestDispatcher())`, `buildViewModel(...)`, `runTest { }`.

#### `ImportViewModel` — suggestion flow

| Test name | Setup | Assertion |
|---|---|---|
| `runScan_withNoOpEnricher_populatesTopicSuggestions` | text with clear technical terms, `NoOpTopicEnricher`, matcher that matches nothing | `state.topicSuggestions` is non-empty (from heuristic pass) |
| `runScan_withNoOpEnricher_claudeStatusStaysIdle` | same as above | `state.claudeStatus == ClaudeStatus.Idle`, `state.isEnhancing == false` |
| `runScan_withSlowEnricher_localSuggestionsAppearFirst` | `SlowEnricher(result, 500ms)`, `advanceTimeBy(50)` after scan | local suggestions present before enrichment delay expires |
| `runScan_withImmediateEnricher_claudeStatusTransitionsToDone` | `ImmediateEnricher(enrichedList)`, `advanceUntilIdle()` | `state.claudeStatus == ClaudeStatus.Done` |
| `runScan_withTimingOutEnricher_claudeStatusIsTimeout` | `TimingOutEnricher` (suspends forever), wait past 8s timeout | `state.claudeStatus == ClaudeStatus.Failed.Timeout` |
| `runScan_withFailingEnricher_claudeStatusIsError_localSuggestionsPreserved` | `FailingEnricher(RuntimeException("fail"))` | `state.claudeStatus` is a `ClaudeStatus.Failed` variant; `state.topicSuggestions` equals the local heuristic result |
| `runScan_whileEnrichmentInFlight_oldEnrichmentCancelled` | launch first scan (slow enricher), change text and launch second scan before first enrichment completes | only the second scan's enrichment result is applied; first scan's Claude result is discarded |
| `runScan_enrichmentArrivesAfterTextEdited_enrichmentDiscarded` | launch scan with slow enricher; before enrichment returns, call `onRawTextChanged` to change `rawText`; advance until enrichment would complete | enriched result is discarded; local suggestions from new text remain |

#### `ImportViewModel` — accept/dismiss/acceptAll/undo handlers

| Test name | Setup | Assertion |
|---|---|---|
| `onSuggestionAccepted_setsAcceptedTrue` | `topicSuggestions` pre-populated, call `onSuggestionAccepted("TensorFlow")` | `state.topicSuggestions.first { it.term == "TensorFlow" }.accepted == true` |
| `onSuggestionAccepted_updatesLinkedTextWithWikiLink` | same | `state.linkedText` contains `"[[TensorFlow]]"` |
| `onSuggestionDismissed_setsDismissedTrue` | `topicSuggestions` pre-populated, call `onSuggestionDismissed("TensorFlow")` | `state.topicSuggestions.first { it.term == "TensorFlow" }.dismissed == true` |
| `claudeEnrichment_doesNotReShowDismissedItems` | dismiss a suggestion, then deliver Claude result containing that term | dismissed suggestion remains dismissed after merge |
| `onAcceptAllSuggestions_capsAtTen` | `topicSuggestions` with 11 unaccepted items, call `onAcceptAllSuggestions()` | exactly 10 items have `accepted == true`; 11th item unchanged |
| `onAcceptAllSuggestions_skipsAlreadyDismissed` | 5 dismissed + 7 pending, call `onAcceptAllSuggestions()` | the 5 dismissed remain dismissed; 7 pending become accepted (all 7, under the 10 cap) |

---

#### `ImportViewModel.confirmImport()` — stub creation and undo

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModelTest.kt` (extend)

| Test name | Setup | Assertion |
|---|---|---|
| `confirmImport_withAcceptedSuggestion_savesStubBeforeImportPage` | 1 accepted suggestion, valid page name, `RecordingPageSaver` | `pageSaver.savedCalls[0].first.name == acceptedTerm`; import page saved in a subsequent call |
| `confirmImport_acceptedSuggestionWherePageAlreadyExists_skipsStubCreation` | page with name equal to suggestion already in `pageRepository`, suggestion marked accepted | `pageSaver` not called for that stub name; import page still saved |
| `confirmImport_finalTextContainsWikiLink` | 1 accepted suggestion "TensorFlow", raw text contains "TensorFlow" | the blocks saved to `pageSaver` contain `"[[TensorFlow]]"` |
| `confirmImport_withAcceptedSuggestions_showUndoSnackbarTrue` | 1 accepted suggestion, confirm completes | `state.showUndoSnackbar == true` |
| `confirmImport_withNoAcceptedSuggestions_showUndoSnackbarFalse` | no accepted suggestions | `state.showUndoSnackbar == false` |
| `onUndoStubCreation_callsPageDeleterForEachStub` | 2 accepted suggestions confirmed, then `onUndoStubCreation()` called | `RecordingPageDeleter.deletedPages` contains both stub pages |
| `onUndoStubCreation_revertsLinkedText` | accept suggestion, confirm, undo | `state.linkedText` reverts to the pre-acceptance value (no `[[TensorFlow]]`) |
| `onUndoStubCreation_clearsSuggestionsAcceptedState` | same | `state.topicSuggestions` all have `accepted == false` after undo |

---

### End-to-End / Scenario Tests (`jvmTest`)

Full review-stage flows tested at the `ImportViewModel` level. Each test covers a user-level scenario.

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModelTest.kt` (extend) or a separate `ImportTopicSuggestionsScenariosTest.kt` if the file becomes large.

| Scenario | Steps | Pass criteria |
|---|---|---|
| **Happy path — paste, accept 3, confirm** | paste technical article text; allow scan to complete; call `onSuggestionAccepted` on 3 terms; set page name; `confirmImport()` | `pageSaver.savedCalls` contains 4 saves (3 stubs + 1 import page); `state.linkedText` contains `[[term]]` for each accepted term; `state.showUndoSnackbar == true` |
| **Dismiss flow — dismissed terms not re-shown after Claude** | scan completes; dismiss 2 suggestions; deliver `ImmediateEnricher` result containing those 2 dismissed terms | after enrichment resolves, `state.topicSuggestions.none { it.dismissed && it.term in dismissedTerms }` is false — they remain dismissed |
| **No suggestions — short plain text** | set text to `"Hello world"` (no capitalized technical terms); scan completes | `state.topicSuggestions.isEmpty()` |
| **Accept All — 15 suggestions → accept, confirm** | scan produces 15 suggestions; call `onAcceptAllSuggestions()` (capped at 10); call `confirmImport()` | 10 stubs saved + 1 import page; `state.showUndoSnackbar == true` |
| **Undo flow** | accept 3 suggestions; `confirmImport()`; call `onUndoStubCreation()` | `RecordingPageDeleter.deletedPages.size == 3`; `state.showUndoSnackbar == false`; `state.undoBuffer.isEmpty()` |
| **Race condition — page created between scan and confirm** | accept suggestion "VectorDB"; before `confirmImport()`, add "VectorDB" page to `InMemoryPageRepository`; `confirmImport()` | stub save for "VectorDB" NOT called; import page still saved; no crash |

---

### Property-Based Tests (optional, `commonTest`)

These are optional extras if kotest-property is available in the test classpath. Check `kmp/build.gradle.kts` for kotest dependencies before implementing.

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/TopicExtractorPropertyTest.kt`

| Property | Invariant |
|---|---|
| `extract_anyText_resultSizeAtMost15` | `TopicExtractor.extract(anyString, emptySet()).size <= 15` for all inputs |
| `extract_anyText_noResultInExistingNames` | `TopicExtractor.extract(anyText, existingNames).none { it.term.lowercase() in existingNames }` |
| `insertWikiLinks_anyText_containsWikiLinkForEachTerm` | for each accepted term `t`, `insertWikiLinks(text, listOf(t))` contains `"[[$t]]"` at least once if `t` appeared in `text` |
| `insertWikiLinks_anyText_neverDoubleBracketed` | `result` never contains `"[[ [["` or `"]] ]]"` (no double-wrapping) |

---

## Test Doubles Specification

All test doubles are created in the test source set. New fakes follow the `RecordingPageSaver` pattern already in `ImportViewModelTest.kt`.

### `TopicEnricher` stub implementations

Create in `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModelTest.kt` (inner classes, following existing `RecordingPageSaver` pattern):

```kotlin
/** Returns immediately with the provided result. */
private class ImmediateEnricher(
    private val result: List<TopicSuggestion>,
) : TopicEnricher {
    override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>) = result
}

/** Delays before returning, to test local-first presentation. */
private class SlowEnricher(
    private val result: List<TopicSuggestion>,
    private val delayMs: Long,
) : TopicEnricher {
    override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>): List<TopicSuggestion> {
        delay(delayMs)
        return result
    }
}

/** Always throws, to test error-path handling. */
private class FailingEnricher(private val exception: Exception) : TopicEnricher {
    override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>): List<TopicSuggestion> {
        throw exception
    }
}

/** Suspends forever — used to verify timeout (8s) triggers ClaudeStatus.Failed.Timeout. */
private class TimingOutEnricher : TopicEnricher {
    override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>): List<TopicSuggestion> {
        suspendCancellableCoroutine { /* never resumes */ }
    }
}
```

### `PageSaver` stub

Already exists as `RecordingPageSaver` in `ImportViewModelTest.kt`. No changes required for suggestion tests.

### `PageDeleter` stub

New — mirrors `RecordingPageSaver`. Create in `ImportViewModelTest.kt`:

```kotlin
private class RecordingPageDeleter : PageDeleter {
    val deletedPages = mutableListOf<Page>()

    override suspend fun delete(page: Page): Boolean {
        deletedPages.add(page)
        return true
    }
}
```

Pass to `buildViewModel(pageDeleter = RecordingPageDeleter())` in undo-related tests.

### `buildViewModel` extension

Update the existing `buildViewModel` helper to accept the two new parameters with no-op defaults, maintaining backward compatibility for all existing tests:

```kotlin
private fun buildViewModel(
    pageRepo: InMemoryPageRepository = InMemoryPageRepository(),
    pageSaver: RecordingPageSaver = RecordingPageSaver(),
    pageDeleter: PageDeleter = PageDeleter.NoOp,          // NEW
    topicEnricher: TopicEnricher = NoOpTopicEnricher(),   // NEW
    urlFetcher: UrlFetcher = FakeUrlFetcher(FetchResult.Failure.NetworkUnavailable),
    matcherFlow: StateFlow<AhoCorasickMatcher?> = MutableStateFlow(null),
    scope: TestScope = TestScope(UnconfinedTestDispatcher()),
    graphPath: String = "/tmp/graph",
): ImportViewModel { ... }
```

---

## Performance Budget

| Operation | Budget | Verification method |
|---|---|---|
| `TopicExtractor.extract()` on 10 KB input | < 500ms on JVM | `measureTimeMillis { TopicExtractor.extract(text10kb, emptySet()) }.let { assertTrue(it < 500) }` in `TopicExtractorTest.performance_10kbInput_under500ms`. Mark with `@Ignore("performance")` on slow CI agents; run locally before merging. |
| `ImportService.scan()` with topic extraction on 10 KB input | < 500ms total | Covered by the `TopicExtractor` budget above since extraction is the dominant cost. No separate benchmark required. |
| `ImportViewModel.runScan()` heuristic path (no enricher) | Local suggestions visible in `state` within 1 full test scheduler advance | Verified implicitly by `runScan_withNoOpEnricher_populatesTopicSuggestions` using `UnconfinedTestDispatcher` |

---

## `ClaudeTopicEnricher` Tests (`jvmTest`)

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricherTest.kt` (new file)

All tests that require a real API key are guarded at the top of each test function:

```kotlin
private val apiKey = System.getenv("ANTHROPIC_API_KEY") ?: return
```

| Test name | Requires key? | Description |
|---|---|---|
| `enhance_withRealText_returnsNonEmptySuggestions` | Yes | Send a short technical paragraph; assert result is non-empty and all items have `source == AI_ENHANCED` |
| `enhance_withEmptyLocalSuggestions_mayReturnClaudeSuggestions` | Yes | Pass `localSuggestions = emptyList()`; Claude may still return net-new items; assert no crash |
| `enhance_malformedJsonResponse_fallsBackToLocalSuggestions` | No (mock HTTP) | Configure a `MockEngine` returning HTTP 200 with body `"not json at all"`; assert `enhance()` returns `localSuggestions` unchanged |
| `enhance_timeoutConfiguredAt1ms_throwsTimeoutCancellationException` | No | Wrap call in `withTimeout(1) { enhancer.enhance(...) }`; assert `TimeoutCancellationException` is thrown |
| `enhance_http429_retriesOnceAndFallsBack` | No (mock HTTP) | `MockEngine` returns 429 on first call, 429 on second call; assert fallback to `localSuggestions` and no crash |
| `enhance_http500_fallsBackToLocalSuggestions` | No (mock HTTP) | `MockEngine` returns 500; assert fallback to `localSuggestions` |

---

## Requirements Traceability

Final mapping from `requirements.md` Must requirements to the specific test file and class that verifies each.

| Requirement | Test file | Test class | Test method(s) |
|---|---|---|---|
| **M1** Noun-phrase extraction | `commonTest/.../domain/TopicExtractorTest.kt` | `TopicExtractorTest` | `multiWordNounPhrase_detected`, `twoWordPhrase_2tokens_detected` |
| **M2** Single-word concept detection | `commonTest/.../domain/TopicExtractorTest.kt` | `TopicExtractorTest` | `singleCapitalizedProperNoun_detected`, `camelCaseTerm_detected` |
| **M3** Confidence scoring / ranking | `commonTest/.../domain/TopicExtractorTest.kt` | `TopicExtractorTest` | `scoringFormula_highFrequencyTermRanksFirst`, `capAt15_returnedSortedByScore` |
| **M4** Dedup against existing pages | `commonTest/.../domain/TopicExtractorTest.kt` + `ImportServiceTest.kt` | `TopicExtractorTest`, `ImportServiceTest` | `termInExistingNames_excluded`, `scanWithExistingNames_excludesCandidate` |
| **M5** One-click stub creation + wiki-link insertion | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `confirmImport_withAcceptedSuggestion_savesStubBeforeImportPage`, `confirmImport_finalTextContainsWikiLink` |
| **M6** Bulk accept | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `onAcceptAllSuggestions_capsAtTen`, `Accept All — 15 suggestions scenario` |
| **M7** Local-first (no API key required) | `commonTest/.../domain/TopicExtractorTest.kt` + `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `TopicExtractorTest`, `ImportViewModelTest` | all `TopicExtractorTest` tests (zero I/O); `runScan_withNoOpEnricher_claudeStatusStaysIdle` |
| **M8** Claude API enhancement opt-in | `jvmTest/.../domain/ClaudeTopicEnricherTest.kt` + `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ClaudeTopicEnricherTest`, `ImportViewModelTest` | `enhance_withRealText_returnsNonEmptySuggestions`, `runScan_withImmediateEnricher_claudeStatusTransitionsToDone` |
| **M9** < 500ms on 10 KB input | `commonTest/.../domain/TopicExtractorTest.kt` | `TopicExtractorTest` | `performance_10kbInput_under500ms` |
| **M10** Zero API calls without key | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `runScan_withNoOpEnricher_claudeStatusStaysIdle` (NoOpTopicEnricher never calls external API) |
| FM-1 False positives: stopword filter | `commonTest/.../domain/TopicExtractorTest.kt` | `TopicExtractorTest` | `allStopwords_returnsEmpty`, `structuralHeader_introduction_filtered`, `acronym_api_filtered` |
| FM-1 Sentence-initial false positive | `commonTest/.../domain/TopicExtractorTest.kt` | `TopicExtractorTest` | `sentenceInitialCapital_notSurfacedUnlessAppearsElsewhere` |
| FM-2 Claude latency: local-first presentation | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `runScan_withSlowEnricher_localSuggestionsAppearFirst` |
| FM-3 Suggestion list overload: cap at 15 | `commonTest/.../domain/TopicExtractorTest.kt` | `TopicExtractorTest` | `capAt15_returnedSortedByScore` |
| FM-4 Claude timeout → fallback | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `runScan_withTimingOutEnricher_claudeStatusIsTimeout` |
| FM-4 Claude exception → fallback | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `runScan_withFailingEnricher_claudeStatusIsError_localSuggestionsPreserved` |
| FM-4 Claude malformed JSON → fallback | `jvmTest/.../domain/ClaudeTopicEnricherTest.kt` | `ClaudeTopicEnricherTest` | `enhance_malformedJsonResponse_fallsBackToLocalSuggestions` |
| FM-5 Race condition: existing page | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `confirmImport_acceptedSuggestionWherePageAlreadyExists_skipsStubCreation`, `Race condition scenario` |
| FM-6 Stub pollution: undo snackbar | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `confirmImport_withAcceptedSuggestions_showUndoSnackbarTrue`, `onUndoStubCreation_callsPageDeleterForEachStub`, `Undo flow scenario` |
| FM-6 Stub pollution: bulk-accept cap | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `onAcceptAllSuggestions_capsAtTen` |
| Stale `rawText` hash: enrichment from old text discarded | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `runScan_enrichmentArrivesAfterTextEdited_enrichmentDiscarded` |
| Re-scan doesn't lose accepted suggestions | `jvmTest/.../ui/screens/ImportViewModelTest.kt` | `ImportViewModelTest` | `runScan_whileEnrichmentInFlight_oldEnrichmentCancelled` |

---

## Definition of Done

- [ ] All `commonTest` unit tests pass: `./gradlew jvmTest --tests "*.TopicExtractorTest"` and `"*.ImportServiceTest"`
- [ ] All `jvmTest` integration tests pass: `./gradlew jvmTest --tests "*.ImportViewModelTest"` and `"*.ClaudeTopicEnricherTest"`
- [ ] All pre-existing `commonTest` and `jvmTest` tests pass without modification: `./gradlew allTests`
- [ ] `TopicExtractor.extract()` returns `<= 15` items on all inputs (property enforced by `capAt15_returnedSortedByScore` and optional property test)
- [ ] `TopicExtractor.extract()` never returns a term whose lowercase form is in `existingNames` (verified by `termInExistingNames_excluded`)
- [ ] `InsertWikiLinks` never double-brackets already-linked terms (verified by `alreadyLinkedOccurrence_doubleLink_notCreated`)
- [ ] `confirmImport()` creates stubs before the import page (`confirmImport_withAcceptedSuggestion_savesStubBeforeImportPage`)
- [ ] `confirmImport()` skips stub creation when page already exists (`confirmImport_acceptedSuggestionWherePageAlreadyExists_skipsStubCreation`)
- [ ] Undo path calls `PageDeleter` for each stub and reverts `linkedText` (verified by `onUndoStubCreation_callsPageDeleterForEachStub` and `onUndoStubCreation_revertsLinkedText`)
- [ ] Claude enrichment discarded when `rawText` has changed (verified by `runScan_enrichmentArrivesAfterTextEdited_enrichmentDiscarded`)
- [ ] `NoOpTopicEnricher` path never sets `isEnhancing = true` (verified by `runScan_withNoOpEnricher_claudeStatusStaysIdle`)
- [ ] Performance budget note added as comment in `TopicExtractorTest.performance_10kbInput_under500ms` or verified locally with `measureTimeMillis`
- [ ] `ClaudeTopicEnricherTest` mock-engine tests pass without `ANTHROPIC_API_KEY` (all non-key-guarded tests pass in CI)
