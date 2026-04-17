# Validation Plan: SteleKit Import

**Phase**: 4 — Validation | **Date**: 2026-04-14
**Maps to**: `docs/tasks/stelekit-import.md`

---

## Test Strategy Summary

The import feature combines four sub-systems with distinct test strategies:

1. **`ImportService` (pure domain function)** — `commonTest` unit tests. No coroutines, no I/O, no mocks needed. Build a real `AhoCorasickMatcher` from a small fixture list. This forms the backbone of confidence because the logic is deterministic.
2. **`UrlFetcher` (JVM implementation)** — `jvmTest` unit tests using ktor-client `MockEngine`. All typed `FetchResult` branches are tested without network access.
3. **`ImportViewModel`** — `businessTest` tests using `InMemoryPageRepository`, a fake `GraphWriter`, and a fake `UrlFetcher`. Use `kotlinx.coroutines.test.runTest` + `TestScope` + `advanceTimeBy` for debounce timing. ViewModel is constructed with a `MutableStateFlow<AhoCorasickMatcher?>` so the null/non-null transition can be tested.
4. **`ImportScreen` (Compose UI)** — `jvmTest` Roborazzi screenshot tests. One screenshot per distinct UI state. Accessibility assertions on all interactive elements.
5. **Navigation wiring** — `jvmTest` integration tests on `StelekitViewModel` command registration and `Screen.Import` routing.

All `commonTest` tests run on JVM, Android, and JS targets. `businessTest` tests have no UI dependency and run fast. `jvmTest` tests run on JVM only and cover JVM-specific code (`UrlFetcherJvm`) or Compose UI.

---

## Coverage Map

| Requirement | Story | Test Classes |
|-------------|-------|--------------|
| Paste raw text → new page | Story 1, Story 3, Story 4 | `ImportServiceTest`, `ImportViewModelTest`, `ImportScreenTest` |
| URL import → fetch + strip → new page | Story 2, Story 3, Story 4 | `UrlFetcherJvmTest`, `ImportViewModelTest`, `ImportScreenTest` |
| Auto-tag existing topics via wiki links | Story 1, Story 3 | `ImportServiceTest`, `ImportViewModelTest` |
| User review before any link commit | Story 3, Story 4 | `ImportViewModelTest`, `ImportScreenTest` |
| Page name collision guard | Story 3 | `ImportViewModelTest` |
| Command palette entry navigates to import | Story 5 | `StelekitViewModelImportTest` |
| Back navigation from ImportScreen | Story 5 | `StelekitViewModelImportTest` |
| No-graph guard on import command | Story 5 | `StelekitViewModelImportTest` |
| CRLF normalization | Story 1 | `ImportServiceTest` |
| Unicode NFC normalization | Story 3 | `ImportViewModelTest` |
| Suggestion cap enforcement | Story 1, Story 3 | `ImportServiceTest`, `ImportViewModelTest` |
| Scan runs on background dispatcher | Story 3 | `ImportViewModelTest` |
| Matcher null at startup (graceful) | Story 3 | `ImportViewModelTest` |
| URL scheme validation (http/https only) | Story 2 | `UrlFetcherJvmTest` |
| Large paste no UI freeze | Story 3 | `ImportViewModelTest` (dispatcher check) |
| Block content size guard | Story 3 | `ImportViewModelTest` |

---

## Test Cases by Component

---

### `ImportService` — Pure Domain Function

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/ImportServiceTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-IS-01 | `singleMatchReplacedWithWikiLink` | Text `"I use Kotlin for development"` with matcher containing `"Kotlin"` → `linkedText` contains `[[Kotlin]]`; `matchedPageNames == listOf("Kotlin")`. |
| U-IS-02 | `multipleNonOverlappingMatchesAllLinked` | Text `"Kotlin and KMP"` with matcher containing both `"Kotlin"` and `"KMP"` → `linkedText` contains `[[Kotlin]]` and `[[KMP]]`; `matchedPageNames` contains both names. |
| U-IS-03 | `multiWordTermMatchedAsUnit` | Text `"machine learning concepts"` with matcher containing `"machine learning"` → `linkedText` contains `[[machine learning]]`, not `[[machine]] [[learning]]`. |
| U-IS-04 | `longerMatchWinsOverShorterPrefix` | Text `"KMP SDK is great"` with matcher containing both `"KMP"` and `"KMP SDK"` → `linkedText` contains `[[KMP SDK]]` only (one link, no `[[KMP]]`). |
| U-IS-05 | `wordBoundaryPreventsSubstringMatch` | Text `"testing framework"` with matcher containing `"test"` → `matchedPageNames` is empty (substring inside `"testing"` must not fire). |
| U-IS-06 | `noMatchesReturnsOriginalTextUnchanged` | Text with no page name occurrences → `linkedText == rawText`; `matchedPageNames` is empty. |
| U-IS-07 | `emptyTextReturnsEmptyResult` | `rawText = ""` → `linkedText == ""`; `matchedPageNames` is empty. |
| U-IS-08 | `crlfInputNormalizedToLf` | Text `"line one\r\nline two"` with matcher containing `"line one"` and `"line two"` → `linkedText` contains `[[line one]]\n[[line two]]` (no `\r`); both page names matched. |
| U-IS-09 | `maxSuggestionsCapEnforced` | Text with 60 distinct page-name occurrences; `maxSuggestions = 50` → `linkedText` contains exactly 50 `[[` occurrences; `matchedPageNames.size == 50`. |
| U-IS-10 | `matchedPageNamesAreDeduplicated` | Page name `"Kotlin"` appears 3 times in text → `matchedPageNames == listOf("Kotlin")` (size 1, not 3). |
| U-IS-11 | `alreadyLinkedTextNotDoubleLinked` | Text `"See [[Kotlin]] for details"` with matcher containing `"Kotlin"` → `linkedText` contains `[[Kotlin]]` exactly once; word-boundary check prevents match inside the existing link syntax. |
| U-IS-12 | `scanOrderIsStartPositionOrder` | Text with two matches; spans cap applies to first N by start position, not arbitrary order. First 50 matches by position are kept when cap < total matches. |
| U-IS-13 | `unicodeNfcTitleMatchesNfcInput` | Matcher built with NFC-normalized page name `"Réunion"`; input text is also NFC `"Réunion"` → match found and `[[Réunion]]` inserted. |
| U-IS-14 | `noMatcherMutationOnScan` | Call `scan()` twice with the same `AhoCorasickMatcher` instance → both calls return identical results (matcher is not modified). |
| U-IS-15 | `largeInputFiftyKbReturnsWithinReasonableTime` | 50 KB text against 100 page names → `scan()` completes (no assertion on timing, but verifies no exception and `linkedText` is non-null). |

---

### `UrlFetcher` — JVM Implementation

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/UrlFetcherJvmTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-UF-01 | `http200WithHtmlBodyReturnsSuccess` | MockEngine returns 200 with HTML body `<html><body><p>Hello</p></body></html>` → `FetchResult.Success` with `text` containing `"Hello"`. |
| U-UF-02 | `pageTitleExtractedFromHtmlTitleTag` | MockEngine returns `<title>My Page</title>` in head → `FetchResult.Success.pageTitle == "My Page"`. |
| U-UF-03 | `http404ReturnsHttpError` | MockEngine returns 404 → `FetchResult.Failure.HttpError(404)`. |
| U-UF-04 | `http500ReturnsHttpError` | MockEngine returns 500 → `FetchResult.Failure.HttpError(500)`. |
| U-UF-05 | `connectionTimeoutReturnsTimeout` | MockEngine delays response past configured timeout → `FetchResult.Failure.Timeout`. |
| U-UF-06 | `networkUnavailableReturnsNetworkError` | MockEngine throws `UnresolvedAddressException` → `FetchResult.Failure.NetworkUnavailable`. |
| U-UF-07 | `responseLargerThan2MbReturnsTooLarge` | MockEngine returns 2.1 MB body → `FetchResult.Failure.TooLarge`. |
| U-UF-08 | `scriptTagContentExcludedFromOutput` | MockEngine returns HTML with `<script>var x=1;</script>Hello` → `text` does not contain `"var x=1"`. |
| U-UF-09 | `styleTagContentExcludedFromOutput` | MockEngine returns HTML with `<style>body{color:red}</style>Hello` → `text` does not contain `"body{color:red}"`. |
| U-UF-10 | `navTagContentExcludedFromOutput` | MockEngine returns HTML with `<nav>Menu items</nav><main>Content</main>` → `text` does not contain `"Menu items"` but does contain `"Content"`. |
| U-UF-11 | `utf8ContentDecodedCorrectly` | MockEngine returns `Content-Type: text/html; charset=utf-8` with UTF-8 body containing `"Réunion"` → `text` contains `"Réunion"` without garbling. |
| U-UF-12 | `emptyBodyHandledWithoutException` | MockEngine returns 200 with empty body → `FetchResult.Success` with empty or near-empty `text`; no exception thrown. |
| U-UF-13 | `fileSchemeRejectedAsHttpError` | `fetch("file:///etc/passwd")` → `FetchResult.Failure.HttpError(0)` (scheme validation blocks the request). |
| U-UF-14 | `jarSchemeRejectedAsHttpError` | `fetch("jar:file:///app.jar!/config.txt")` → `FetchResult.Failure.HttpError(0)`. |
| U-UF-15 | `shortHtmlBodyWarnsAboutJavaScript` | MockEngine returns 200 with body < 200 chars after stripping → `FetchResult.Success.text` contains a note indicating page may require JavaScript. |
| U-UF-16 | `httpRedirectFollowedTransparently` | MockEngine returns 301 redirect then 200 with content → `FetchResult.Success` with content from the final destination. |

---

### `ImportViewModel` — State + Debounce + Save

**Source set**: `businessTest`
**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModelTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-VM-01 | `initialStateIsIdle` | Freshly constructed `ImportViewModel` → `state.value` has `isScanning = false`, `rawText = ""`, `matchedPageNames = emptyList()`. |
| U-VM-02 | `scanNotTriggeredDuringRapidTyping` | `onRawTextChanged` called 5 times within 200ms → `ImportService.scan` not called (debounce window not elapsed). |
| U-VM-03 | `scanTriggeredAfterDebounceWindow` | `onRawTextChanged` called once; `advanceTimeBy(300)` → `state.value.isScanning` transitions to `true` then back to `false`; `matchedPageNames` populated. |
| U-VM-04 | `stateScanningTrueWhileScanInProgress` | Fake `ImportService` that suspends for 100ms; `onRawTextChanged` + `advanceTimeBy(300)` → `state.value.isScanning == true` during scan; `false` after. |
| U-VM-05 | `staleResultDiscardedIfTextChangedBeforeScanCompletes` | Text changed during active scan (before result returned) → result from old scan is discarded; state reflects new scan's result only. |
| U-VM-06 | `matchedPageNamesPopulatedFromScanResult` | Text `"Kotlin and KMP"` with matcher containing both; after debounce → `state.value.matchedPageNames` contains both names. |
| U-VM-07 | `nullMatcherShowsLoadingState` | `matcherFlow` emits `null`; `onRawTextChanged("text")` → `state.value` indicates index loading (specific `pageNameError` or dedicated loading flag); `ImportService.scan` not called. |
| U-VM-08 | `scanRetriedWhenMatcherTransitionsFromNullToNonNull` | `matcherFlow` starts `null`; text already entered; matcher transitions to non-null → scan automatically triggered; `matchedPageNames` populated. |
| U-VM-09 | `confirmImportValidatesEmptyPageName` | `confirmImport()` with `state.pageName = ""` → `state.value.pageNameError` non-null; `graphWriter.savePage` not called. |
| U-VM-10 | `confirmImportBlocksOnPageNameCollision` | Pre-existing page with name `"My Notes"` in `pageRepository`; `state.pageName = "My Notes"` → `confirmImport()` sets `state.value.pageNameError` containing the name; save not called. |
| U-VM-11 | `confirmImportCallsGraphWriterWithLinkedText` | No collision; valid page name; `state.linkedText` set → `confirmImport()` → `graphWriter.savePage` called exactly once; blocks contain `linkedText` content. |
| U-VM-12 | `confirmImportCallsGraphWriterWithOriginalTextWhenNoMatches` | No matches found (empty `matchedPageNames`); valid page name → `confirmImport()` → `graphWriter.savePage` called with original `rawText` content. |
| U-VM-13 | `isSavingTrueWhileSaveInProgress` | Fake `GraphWriter` that suspends; `confirmImport()` → `state.value.isSaving == true` during save. |
| U-VM-14 | `isSavingReturnsFalseAfterSuccessfulSave` | `confirmImport()` succeeds → `state.value.isSaving == false` in final state. |
| U-VM-15 | `isSavingReturnsFalseAfterFailedSave` | `graphWriter.savePage` throws; `confirmImport()` → `state.value.isSaving == false` (finally block); error surfaced. |
| U-VM-16 | `cancelResetsStateWithoutSave` | State has `rawText`, `linkedText`, `matchedPageNames`; cancel action → state resets; `graphWriter.savePage` not called. |
| U-VM-17 | `urlFetchSuccessPopulatesRawText` | Fake `UrlFetcher` returns `Success(text = "fetched content", pageTitle = "My Page")` → `state.value.rawText == "fetched content"`; `state.value.pageName == "My Page"`. |
| U-VM-18 | `urlFetchNetworkErrorSurfacesErrorState` | Fake `UrlFetcher` returns `Failure.NetworkUnavailable` → `state.value.fetchError` is `Failure.NetworkUnavailable`; no crash. |
| U-VM-19 | `urlFetchTimeoutSurfacesTimeoutError` | Fake `UrlFetcher` returns `Failure.Timeout` → `state.value.fetchError` is `Failure.Timeout`. |
| U-VM-20 | `urlFetchHttpErrorSurfacesCode` | Fake `UrlFetcher` returns `Failure.HttpError(403)` → `state.value.fetchError` is `Failure.HttpError(403)`. |
| U-VM-21 | `pageNameNfcNormalizedBeforeCollisionCheck` | Page name input with NFD-form characters; collision check normalizes to NFC before `getPageByName`; no false-negative collision miss. |
| U-VM-22 | `blocksSplitOnDoubleNewline` | `state.linkedText = "Para one\n\nPara two\n\nPara three"` → `confirmImport()` → `graphWriter.savePage` called with list of 3 blocks. |
| U-VM-23 | `singleParagraphWithNoBlankLineBecomesSingleBlock` | `state.linkedText = "All one paragraph with no blank lines"` → 1 block in saved page. |
| U-VM-24 | `urlImportSavesSourcePropertyOnPage` | URL tab active; `state.urlInput = "https://example.com/article"` → `confirmImport()` → saved page has `"source"` property equal to the URL. |
| U-VM-25 | `pasteImportDoesNotAddSourceProperty` | Paste tab active (URL empty) → `confirmImport()` → saved page has no `"source"` property. |
| U-VM-26 | `scanDispatchedToDefaultDispatcher` | `ImportService.scan` is always called inside a `withContext(Dispatchers.Default)` block — verified by checking the calling coroutine context dispatcher is not `Main`. |
| U-VM-27 | `largeBlockContentSplitAtSentenceBoundary` | `state.linkedText` contains a single paragraph longer than 100 000 chars with sentence boundaries → saved blocks each < 100 000 chars. |
| U-VM-28 | `oversizedTotalContentRejectedWithError` | `state.rawText` total length exceeds 500 000 chars → `confirmImport()` sets `pageNameError` (or equivalent) with user-visible message; save not called. |
| U-VM-29 | `confirmImportDisabledWhileSaving` | `state.isSaving == true` → second `confirmImport()` call is a no-op (no duplicate save). |

---

### `ImportScreen` — Compose UI

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/ImportScreenTest.kt`

All screenshot tests use Roborazzi. Fakes are used for `ImportViewModel` state.

| # | Test name | Assertion |
|---|-----------|-----------|
| S-UI-01 | `initialState_pasteTabActive_confirmDisabled` | Screenshot: paste tab selected, empty text field, "Next: Review links" button is disabled (greyed), no progress indicator visible. |
| S-UI-02 | `pasteTab_textEntered_proposedLinksHighlighted` | Screenshot: paste tab with text entered; review stage shown with `[[Kotlin]]` and `[[KMP]]` highlighted; match chips visible below preview. |
| S-UI-03 | `urlTab_emptyInput_fetchButtonVisible` | Screenshot: URL tab selected, single-line URL input field, "Fetch" button visible and enabled, no error message. |
| S-UI-04 | `urlTab_fetchInProgress_progressIndicatorShown` | Screenshot: URL tab; `isScanning = true` for URL fetch → `CircularProgressIndicator` visible; "Fetch" button disabled or replaced. |
| S-UI-05 | `urlTab_fetchErrorState_errorMessageAndRetry` | Screenshot: `fetchError = FetchResult.Failure.NetworkUnavailable` → inline error message shown; "Try again" action visible. |
| S-UI-06 | `pasteTab_scanning_linearProgressShown` | Screenshot: paste tab with text; `isScanning = true` → `LinearProgressIndicator` visible below text field. |
| S-UI-07 | `reviewStage_noMatchesFound_informationalMessageShown` | Screenshot: review stage with `matchedPageNames = emptyList()` → "No existing page topics were detected" message shown. |
| S-UI-08 | `pageNameCollisionErrorState` | Screenshot: `pageNameError = "A page named 'My Notes' already exists"` → page name field shows error text in supporting text region. |
| S-UI-09 | `reviewStage_importButtonDisabledWhileSaving` | Screenshot: `isSaving = true` → "Import page" button replaced with `CircularProgressIndicator`; no clickable confirm button. |
| S-UI-10 | `allInteractiveElementsHaveContentDescription` | Accessibility check: text fields, buttons, tabs, chips each have `contentDescription` set; verified via semantics tree inspection. |
| S-UI-11 | `tabSwitchUpdatesPasteAndUrlViews` | Interaction test: click "From URL" tab → URL input field becomes visible; paste text area hidden. |
| S-UI-12 | `backButtonFromReviewStageRetainsRawText` | Interaction test: reach review stage; click "Back" → paste stage shown; `rawText` field still populated with original text. |
| S-UI-13 | `charCountShownWhenTextExceeds10000` | State with `rawText` > 10 000 chars → char count label visible in paste tab. |

---

### Navigation + Command Palette

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/StelekitViewModelImportTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| N-01 | `importCommandPresentWhenGraphLoaded` | `StelekitViewModel` with active graph → `state.commands` contains an entry with label `"Import text as new page"`. |
| N-02 | `importCommandAbsentWhenNoGraphLoaded` | `StelekitViewModel` with no active graph → `state.commands` contains no entry with id `"import.paste-text"`. |
| N-03 | `importCommandNavigatesToScreenImport` | Invoke the import command action → `state.currentScreen` transitions to `Screen.Import`. |
| N-04 | `backNavigationFromImportScreenReturnsToPreviousScreen` | Navigate to `Screen.Import` from a page screen; trigger onDismiss → `state.currentScreen` is the previously active page screen. |
| N-05 | `successfulImportNavigatesToNewPage` | `ImportViewModel.confirmImport()` succeeds with page name `"New Page"` → `state.currentScreen` transitions to the new page's screen. |

---

## Property-Based Tests

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/ImportServicePropertyTest.kt`

These tests assert invariants that must hold for any input within the defined contract.

| # | Invariant | Description |
|---|-----------|-------------|
| P-01 | `linkedTextLengthNeverShorterThanInput` | For any `rawText` and any matcher, `result.linkedText.length >= rawText.length`. (Links only add characters, never remove.) |
| P-02 | `matchedPageNamesIsSubsetOfMatcherPatterns` | Every name in `result.matchedPageNames` must be a canonical name that exists in the matcher's pattern map. |
| P-03 | `matchedPageNamesAreAlwaysDeduplicated` | `result.matchedPageNames.size == result.matchedPageNames.toSet().size` for any input. |
| P-04 | `wikiLinkCountNeverExceedsMaxSuggestions` | The number of `[[` occurrences in `result.linkedText` is always `<= maxSuggestions`. |
| P-05 | `emptyMatcherAlwaysReturnsInputUnchanged` | `AhoCorasickMatcher(emptyMap())` for any non-empty `rawText` → `result.linkedText == rawText.replace("\r\n", "\n")`. |
| P-06 | `crlfEquivalenceWithLf` | `scan(text.replace("\n", "\r\n"), matcher)` produces same `linkedText` (after CRLF normalize) as `scan(text, matcher)`. |
| P-07 | `scanIsIdempotentOnAlreadyLinkedText` | `scan(scan(text, matcher).linkedText, matcher)` produces no additional `[[` wrappers around already-linked terms — i.e., no double-wrapping. |

---

## Known Issues → Test Coverage

Each known issue from `docs/tasks/stelekit-import.md` maps to the test that catches (or validates mitigation of) the failure mode.

| Known Issue | Severity | Mitigating Tests |
|-------------|----------|-----------------|
| **TOCTOU in collision check** — check and save are not atomic; concurrent imports could both pass the existence check | Medium | U-VM-29 (isSaving guard prevents re-entry); N-05 (navigation after successful save); manual testing note: TOCTOU accepted for v1 solo use |
| **Block content exceeds validation limit** — single paragraph with no blank lines could produce one enormous block | Low | U-VM-27 (sentence-boundary secondary split); U-VM-28 (total content size cap rejects oversized input) |
| **Matcher null on first import** — `PageNameIndex.matcher` starts null; user sees no feedback | Medium | U-VM-07 (null matcher shows loading state); U-VM-08 (scan retried after matcher transitions to non-null); S-UI-01 (initial state screenshot confirms disabled button, no scan shown) |
| **Large paste UI freeze** — `ImportService.scan()` called on main thread blocks UI | Medium | U-VM-26 (dispatcher assertion: scan must not run on Main); U-IS-15 (large input completes without exception) |
| **Unchecked URL input (file:// / jar://)** — ktor-client resolves local paths on JVM | Low | U-UF-13 (file:// scheme rejected); U-UF-14 (jar:// scheme rejected) |
| **Stale scan results after graph change** — matched names may be stale if graph changes before confirm | Low | U-VM-05 (stale result from concurrent text change discarded); U-VM-08 (re-scan on matcher change); no separate test for graph-change stale guard in v1 (accepted limitation) |
| **FM-1: False-positive wiki-link matching** (pitfalls.md) — short page names fire everywhere | High | U-IS-05 (word-boundary prevents substring match); U-VM-06 (matched names populated correctly); P-07 (idempotency prevents runaway linking); covered architecturally by the user-review gate |
| **FM-2: Large-paste performance** (pitfalls.md) | Medium | U-IS-15 (50 KB no exception); U-VM-26 (background dispatcher); U-VM-04 (isScanning UI feedback during async scan) |
| **FM-3: URL fetch failure modes** (pitfalls.md) — timeout, 4xx/5xx, TLS, JS-only, encoding | Medium | U-UF-03 through U-UF-15 (all failure branches); U-VM-18 through U-VM-20 (ViewModel error surface) |
| **FM-4: Encoding edge cases** (pitfalls.md) — NFC vs NFD, CRLF, charset | Medium | U-IS-08 (CRLF normalization); U-IS-13 (NFC match); U-VM-21 (NFC normalize before collision check); U-UF-11 (UTF-8 decode) |
| **FM-5: Duplicate page creation** (pitfalls.md) — re-import same URL creates duplicate | Medium | U-VM-10 (name collision check); U-VM-24 (source:: property stored for URL imports); URL-dedup check deferred to v2 |
| **FM-6: Title collision from auto-generated title** (pitfalls.md) | Medium | U-VM-09 (empty name validation); U-VM-10 (collision error surfaces); S-UI-08 (collision error screenshot) |

---

## Test Infrastructure Notes

### Test Doubles Required

| Double | Type | Used By | Notes |
|--------|------|---------|-------|
| `FakeUrlFetcher` | Interface implementation (`UrlFetcher`) | `ImportViewModelTest` | Returns configurable `FetchResult`; no network calls. Constructor takes a `FetchResult` to return. |
| `FakeGraphWriter` | Class capturing calls | `ImportViewModelTest` | Stores the `Page` and `List<Block>` passed to `savePage`; optionally throws. Mirrors the `MockFileSystem` pattern in `OutlinerMonkeyTest`. |
| `InMemoryPageRepository` | Existing class | `ImportViewModelTest`, `StelekitViewModelImportTest` | Already exists in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt`. |
| `MutableStateFlow<AhoCorasickMatcher?>` | Kotlin stdlib | `ImportViewModelTest` | Inject directly as `matcherFlow`; flip `null` ↔ non-null to test startup and rebuild scenarios. |
| `MockEngine` (ktor) | ktor-client-mock | `UrlFetcherJvmTest` | Already present in test dependencies per `docs/tasks/stelekit-import.md` Task 2.3 (verify, add if missing). |
| `TestScope` + `advanceTimeBy` | `kotlinx.coroutines.test` | `ImportViewModelTest` | Controls virtual time for debounce tests (300ms window). Pass `TestDispatcher` to `ImportViewModel` constructor. |

### Helper / Fixture Classes Needed

| Helper | Location | Purpose |
|--------|----------|---------|
| `ImportTestFixtures` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/ImportTestFixtures.kt` | Factory methods for `AhoCorasickMatcher` from small fixed page lists; large-text generators for performance tests. |
| `FakeUrlFetcher` | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/domain/FakeUrlFetcher.kt` | Configurable `UrlFetcher` implementation for ViewModel tests. |
| `FakeGraphWriter` | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/FakeGraphWriter.kt` | Capture-based `GraphWriter` fake for save-path assertions. |

### ViewModel Constructor Wiring for Tests

`ImportViewModel` must accept a `CoroutineScope` parameter (mirroring the pattern in `JournalsViewModel`) so tests can pass a `TestScope`. The `Dispatchers.Default` dispatch inside the scan must be replaceable via an injected `CoroutineDispatcher` or `withContext` override using the test dispatcher — follow the `DebounceManager` pattern already in `StelekitViewModel`.

---

## Definition of Done

The feature is ready to ship when **all** of the following pass:

- [ ] `./gradlew allTests` is green with no new failures
- [ ] All 15 `ImportServiceTest` tests pass (`commonTest`)
- [ ] All 16 `UrlFetcherJvmTest` tests pass (`jvmTest`)
- [ ] All 29 `ImportViewModelTest` tests pass (`businessTest`)
- [ ] All 13 `ImportScreenTest` screenshot tests pass with approved golden images (`jvmTest`)
- [ ] All 5 `StelekitViewModelImportTest` tests pass (`jvmTest`)
- [ ] All 7 `ImportServicePropertyTest` invariants hold (`commonTest`)
- [ ] No known issue has severity Medium or higher without a mitigating test case (see Known Issues table above)
- [ ] `FakeUrlFetcher`, `FakeGraphWriter`, and `ImportTestFixtures` exist and are re-usable (not inlined in individual tests)
- [ ] Roborazzi golden images for all 13 `ImportScreen` states committed to the repository
- [ ] `./gradlew jvmTest --tests "*.ImportServiceTest"` passes in isolation (no I/O or coroutine dependencies in the file)
- [ ] `./gradlew jvmTest --tests "*.ImportViewModelTest"` passes with no real network calls (verified by the `FakeUrlFetcher` injection)
- [ ] Accessibility check (S-UI-10) passes: all interactive elements have `contentDescription`
- [ ] The import command (`"import.paste-text"`) does not appear in commands when no graph is loaded (N-02)
- [ ] A 50 KB paste does not cause the scan to be dispatched on the main thread (U-VM-26)
