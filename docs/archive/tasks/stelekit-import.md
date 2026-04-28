# Implementation Plan: SteleKit Import

## Epic Overview

Enable SteleKit desktop users to paste arbitrary text or provide a URL, then create a new page in the active graph with wiki-links automatically inserted for existing page names — after a mandatory user review step. The feature reuses the existing `AhoCorasickMatcher` + `PageNameIndex` pipeline and `GraphWriter.savePage` with no schema changes.

**Target platform**: JVM Desktop (Phase 1). Android/iOS follow.
**New dependencies**:
- `com.fleeksoft.ksoup:ksoup:0.2.6` — HTML-to-text extraction for URL import (commonMain)

---

## Architecture Decisions

| ADR | Decision | File |
|-----|----------|------|
| ADR-001 | Two-stage review dialog before any link commit (never silent auto-apply) | `project_plans/stelekit-import/decisions/ADR-001-import-review-ux.md` |
| ADR-002 | ksoup for HTML-to-text extraction in commonMain (over expect/actual jsoup) | `project_plans/stelekit-import/decisions/ADR-002-ksoup-html-extraction.md` |
| ADR-003 | ImportService as pure domain function reusing AhoCorasickMatcher | `project_plans/stelekit-import/decisions/ADR-003-import-service-design.md` |
| ADR-004 | UrlFetcher expect/actual interface for offline-first URL fetching | `project_plans/stelekit-import/decisions/ADR-004-url-fetcher-interface.md` |
| ADR-005 | Defer new-page suggestions to v2; v1 matches existing pages only | `project_plans/stelekit-import/decisions/ADR-005-new-page-suggestions-deferred.md` |

---

## Story Breakdown

---

### Story 1: ImportService — Pure Domain Function

**Goal**: A pure function that accepts raw text and an `AhoCorasickMatcher` and returns linked text with a list of matched page names. No UI, no coroutines, fully unit-testable in isolation.

---

#### Task 1.1 — `ImportResult` data class + `ImportService` skeleton

**Objective**: Create the `domain/` types and the `ImportService` entry point. The service is a pure function with no side effects.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ImportService.kt` (new)

**Implementation steps**:

1. Create `ImportService.kt` in the `domain/` package:
   ```kotlin
   data class ImportResult(
       val linkedText: String,
       val matchedPageNames: List<String>
   )

   object ImportService {
       fun scan(
           rawText: String,
           matcher: AhoCorasickMatcher,
           maxSuggestions: Int = 50
       ): ImportResult
   }
   ```

2. Implement `scan()`:
   - Normalize CRLF: `rawText.replace("\r\n", "\n")`
   - Call `matcher.findAll(normalizedText)` to get `List<MatchSpan>`
   - Resolve overlapping spans using `AhoCorasickMatcher`'s existing overlap resolution (already handles this)
   - Cap results: take at most `maxSuggestions` spans ordered by start position
   - Rewrite text by walking spans in reverse order (last → first) and substituting `[[canonicalName]]` around each matched region
   - Return `ImportResult(linkedText, matchedPageNames)` where `matchedPageNames` is the deduplicated list of canonical names found

3. Edge cases:
   - `matcher` called on empty string returns empty result immediately
   - No spans found: return `ImportResult(rawText, emptyList())`

**Validation criteria**:
- Given text "I use Kotlin and KMP for development" with a matcher containing pages ["Kotlin", "KMP"], `scan()` returns `linkedText` containing `[[Kotlin]]` and `[[KMP]]`
- CRLF normalization: text with `\r\n` produces the same linked output as text with `\n`
- Span cap: if 60 matches are found and `maxSuggestions = 50`, only 50 links appear in the output
- No mutation of the `matcher` object

---

#### Task 1.2 — `ImportService` unit tests

**Objective**: Cover all edge cases and the CRLF normalization requirement before any UI is written.

**Files** (max 5):
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/domain/ImportServiceTest.kt` (new)

**Implementation steps**:

1. Construct a real `AhoCorasickMatcher` from a small fixed page name list (no mocks needed — pure function).
2. Test cases:
   - Single match, multi-match, overlapping candidate spans
   - CRLF input normalized correctly
   - Empty text input
   - `maxSuggestions` cap applied
   - Returned `matchedPageNames` list is deduplicated
   - Text regions already wrapped in `[[...]]` are not double-linked (matcher's word-boundary check handles this — verify)
3. Run `./gradlew jvmTest --tests "*.ImportServiceTest"` to confirm green.

**Validation criteria**:
- All tests pass
- No I/O or coroutine dependencies in the test file

---

### Story 2: UrlFetcher — Offline-First URL Fetch + HTML Strip

**Goal**: An `expect/actual` interface that fetches a URL and returns plain text, with a typed error model and graceful failure. The production JVM implementation uses ktor-client + ksoup.

---

#### Task 2.1 — `UrlFetcher` interface + `FetchResult` sealed class

**Objective**: Define the common interface and result type in `commonMain`. No implementation yet.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/UrlFetcher.kt` (new)

**Implementation steps**:

1. Define result types:
   ```kotlin
   sealed class FetchResult {
       data class Success(val text: String, val pageTitle: String?) : FetchResult()
       sealed class Failure : FetchResult() {
           object Timeout : Failure()
           object NetworkUnavailable : Failure()
           data class HttpError(val code: Int) : Failure()
           object ParseError : Failure()
           object TooLarge : Failure()
       }
   }
   ```

2. Define the interface:
   ```kotlin
   interface UrlFetcher {
       suspend fun fetch(url: String): FetchResult
   }
   ```

3. Add a `NoOpUrlFetcher` in `commonMain` that returns `FetchResult.Failure.NetworkUnavailable` — used in tests and as a compile placeholder before platform implementations are wired.

**Validation criteria**:
- `UrlFetcher.kt` compiles in `commonMain` without any platform-specific imports
- `FetchResult` sealed class covers all documented failure modes from `pitfalls.md`

---

#### Task 2.2 — `UrlFetcherJvm` — ktor-client + ksoup implementation

**Objective**: JVM implementation of `UrlFetcher` using the existing ktor-client and new ksoup dependency.

**Files** (max 5):
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/domain/UrlFetcherJvm.kt` (new)
- `kmp/build.gradle.kts` (modify — add ksoup dependency)

**Implementation steps**:

1. Add ksoup to `build.gradle.kts` in the `commonMain` dependencies block:
   ```kotlin
   implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
   ```

2. Implement `UrlFetcherJvm`:
   - Create an `HttpClient` with `OkHttp` engine using existing engine declaration pattern
   - Set connect timeout 10s, request timeout 15s
   - Set `Accept: text/html` and a browser-like `User-Agent` header
   - Set max response body to 2 MB: read `bodyAsBytes()`, check size before converting to string
   - Wrap the entire fetch in `try/catch` mapping exceptions to typed `FetchResult.Failure`:
     - `HttpRequestTimeoutException` → `Timeout`
     - `UnresolvedAddressException`, `ConnectException` → `NetworkUnavailable`
     - `ResponseException` with status code → `HttpError(code)`
   - On success: call `Ksoup.parse(htmlString).body().text()` for plain text and `doc.title()` for the page title
   - If stripped text is < 200 chars, add a note in the `Success.text` that page may require JavaScript

3. The `HttpClient` instance should be created once and reused — inject via constructor (or companion factory) to allow testing with `MockEngine`.

**Validation criteria**:
- `UrlFetcherJvm` compiles with the ksoup dependency in place
- `FetchResult.Success` is returned for a valid HTTP 200 HTML response
- `FetchResult.Failure.TooLarge` is returned when response exceeds 2 MB
- `FetchResult.Failure.Timeout` is returned when connection exceeds configured timeout

---

#### Task 2.3 — `UrlFetcherJvm` unit tests

**Objective**: Verify all `FetchResult` branches using ktor's `MockEngine`.

**Files** (max 5):
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/UrlFetcherJvmTest.kt` (new)

**Implementation steps**:

1. Use `MockEngine` from `io.ktor:ktor-client-mock` (already a test dependency — verify, add if missing)
2. Test cases:
   - 200 OK with HTML body → `Success` with extracted plain text and title
   - 404 → `HttpError(404)`
   - 200 with body > 2 MB → `TooLarge`
   - Connection timeout (mock engine delays > timeout) → `Timeout`
   - HTML body with `<script>`, `<style>` tags stripped in output
   - `<title>` tag correctly extracted into `pageTitle`

**Validation criteria**:
- All cases pass with `./gradlew jvmTest --tests "*.UrlFetcherJvmTest"`

---

### Story 3: ImportViewModel + State

**Goal**: A ViewModel that wires `ImportService` + `GraphWriter`, holds `ImportState`, debounces the scan on text change, and exposes a confirm action that saves the page.

---

#### Task 3.1 — `ImportState` + `ImportViewModel` skeleton

**Objective**: Define the state shape and create the ViewModel with injected dependencies. No UI yet.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt` (new)

**Implementation steps**:

1. Define `ImportState`:
   ```kotlin
   data class ImportState(
       val rawText: String = "",
       val urlInput: String = "",
       val pageName: String = "",
       val linkedText: String = "",
       val matchedPageNames: List<String> = emptyList(),
       val isScanning: Boolean = false,
       val isSaving: Boolean = false,
       val fetchError: FetchResult.Failure? = null,
       val pageNameError: String? = null,
       val activeTab: ImportTab = ImportTab.PASTE
   )

   enum class ImportTab { PASTE, URL }
   ```

2. Create `ImportViewModel(coroutineScope, pageRepository, graphWriter, urlFetcher, matcherFlow)`:
   - `matcherFlow: StateFlow<AhoCorasickMatcher?>` — injected from `StelekitViewModel.suggestionMatcher`
   - `_state: MutableStateFlow<ImportState>`
   - `fun onRawTextChanged(text: String)` — updates state, triggers debounced scan
   - `fun onUrlChanged(url: String)` — updates URL input state
   - `suspend fun fetchUrl()` — calls `urlFetcher.fetch()`, updates state with result or error
   - `fun onPageNameChanged(name: String)` — updates page name, clears error
   - `suspend fun confirmImport()` — validates name, checks collision, saves page

3. Debounced scan pattern (mirror `DebounceManager` usage in `StelekitViewModel`):
   - On `onRawTextChanged`, launch a coroutine with 300ms delay
   - Cancel previous scan job if a new text change arrives within the delay window
   - Run `ImportService.scan(text, matcher)` on `Dispatchers.Default`
   - Update `state.isScanning` during the async window

**Validation criteria**:
- `ImportViewModel` compiles with a fake `AhoCorasickMatcher` and `NoOpUrlFetcher`
- `_state` is a `StateFlow` (not `MutableStateFlow`) in the public interface

---

#### Task 3.2 — `confirmImport()` — collision check + page save

**Objective**: Implement the save path with pre-save collision detection and block construction from linked text.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt` (modify)

**Implementation steps**:

1. In `confirmImport()`:
   - Validate page name using `Validation.validateName(name)` — show error in `pageNameError` if invalid
   - NFC-normalize the page name: `name.normalize(Form.NFC)` using `com.doist.x:normalize:1.2.0`
   - Call `pageRepository.getPageByName(normalizedName).first()` — if page exists, set `pageNameError = "A page named '$normalizedName' already exists"`
   - Split `state.linkedText` into blocks: split on `\n\n` for paragraph-level blocks; each paragraph becomes one top-level `Block`
   - For each block string, construct a `Block` with `level = 0`, `content = paragraphText`, new UUID
   - Construct `Page(name = normalizedName, ...)` with `source:: <url>` property if URL tab was used
   - Call `graphWriter.savePage(page, blocks, graphPath)` inside `state.isSaving = true` guard
   - On success: emit navigation event to open the new page

2. For URL imports, store the source URL as a page property:
   - Add `"source" to state.urlInput` in `page.properties` map before save

**Validation criteria**:
- Collision check blocks save and shows error message
- Page created with correct blocks split on `\n\n`
- URL-import pages include `source::` property
- `isSaving` returns to `false` regardless of success/failure (use `finally`)

---

#### Task 3.3 — `ImportViewModel` tests

**Objective**: Unit test the scan debounce, collision check, and save path using `InMemoryRepositories`.

**Files** (max 5):
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModelTest.kt` (new)

**Implementation steps**:

1. Test cases:
   - Scan triggered 300ms after text change; not triggered during rapid typing
   - `matchedPageNames` populated from `ImportService.scan()` result
   - `confirmImport()` blocked when `pageNameError` is set
   - Collision check: pre-existing page name shows error, does not call `graphWriter`
   - Successful save: `GraphWriter.savePage` called once with correct page and blocks
   - URL tab: `source::` property present in saved page

**Validation criteria**:
- All tests pass with `./gradlew jvmTest --tests "*.ImportViewModelTest"`
- No real network calls; `UrlFetcher` injected as a fake

---

### Story 4: ImportScreen — Two-Stage Compose UI

**Goal**: A full-screen Compose dialog with a paste tab and URL tab for source input, followed by a link-review stage showing proposed `[[links]]` highlighted and a confirm/cancel pair.

---

#### Task 4.1 — `ImportScreen` — source input stage

**Objective**: Build the first stage of the dialog: tab row (Paste / URL), text input area, and "Scan" trigger.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportScreen.kt` (new)

**Implementation steps**:

1. Create `ImportScreen(viewModel: ImportViewModel, onDismiss: () -> Unit)` composable.

2. First stage layout:
   - `TabRow` with two tabs: "Paste text" and "From URL"
   - **Paste tab**: `OutlinedTextField` (multiline, fills available height) bound to `state.rawText` via `onRawTextChanged`. Label: "Paste your text here". Show char count if > 10 000 chars.
   - **URL tab**: Single-line `OutlinedTextField` for URL input + "Fetch" button. While fetching: `CircularProgressIndicator`. On `fetchError`: show inline error message with a "Try again" action. On success: auto-switch to review stage.
   - Page name input field at bottom of stage 1: `OutlinedTextField` for `state.pageName`. Show `state.pageNameError` as supporting text.
   - "Next: Review links" button — disabled while `state.isScanning` or `state.rawText.isBlank()`

3. Scanning state: show `LinearProgressIndicator` below the text field while `state.isScanning` is true.

**Validation criteria**:
- Compose file compiles
- Tab switching updates `state.activeTab`
- "Next" button disabled while text is empty or scan is in progress

---

#### Task 4.2 — `ImportScreen` — link review stage

**Objective**: Build the second stage: annotated text preview showing proposed `[[links]]` highlighted, confirm/cancel buttons.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportScreen.kt` (modify)

**Implementation steps**:

1. Second stage (shown after user clicks "Next: Review links"):
   - Scrollable `Text` showing `state.linkedText` rendered via `parseMarkdownWithStyling` with the existing `suggestionMatcher` — this gives `[[links]]` the same visual highlight style as the rest of the app
   - Summary chip row: for each name in `state.matchedPageNames`, show a chip with the page name. Chips are informational (not toggleable) in v1.
   - Page name confirmation row: read-only display of `state.pageName` with an edit icon that returns to stage 1
   - "Import page" primary button (disabled while `state.isSaving`) and "Back" secondary button
   - While `state.isSaving`: replace "Import page" label with `CircularProgressIndicator`

2. If `state.matchedPageNames` is empty after scan, show an informational message: "No existing page topics were detected in this text."

**Validation criteria**:
- Preview renders without crashing on 50 KB text
- Confirm button calls `viewModel.confirmImport()`
- Back button returns to stage 1 without losing `rawText`

---

### Story 5: Navigation + Command Palette Wiring

**Goal**: `Screen.Import` added to the sealed class, `StelekitViewModel` navigates to it from a command palette entry, and `ImportViewModel` is constructed with the live `suggestionMatcher` flow.

---

#### Task 5.1 — `Screen.Import` sealed class entry

**Objective**: Add the new screen destination to `AppState.kt` and route it in `App.kt`.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (modify)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (modify)

**Implementation steps**:

1. In `AppState.kt`, add to the `Screen` sealed class:
   ```kotlin
   data object Import : Screen()
   ```

2. In `App.kt`, add a `when` branch for `Screen.Import`:
   - Construct `ImportViewModel` with the active graph's `pageRepository`, `graphWriter`, `UrlFetcherJvm()`, and `viewModel.suggestionMatcher`
   - Render `ImportScreen(importViewModel, onDismiss = { viewModel.navigateTo(previousScreen) })`

3. Handle the "after import" navigation: on successful save, `ImportViewModel` emits the new page name; `App.kt` calls `viewModel.navigateToPageByName(pageName)`.

**Validation criteria**:
- App compiles with `Screen.Import` added
- Navigating to `Screen.Import` and back does not crash

---

#### Task 5.2 — Command palette entry + `StelekitViewModel` wiring

**Objective**: Register the import command in `updateCommands()` so users can invoke import via the command palette.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (modify)

**Implementation steps**:

1. In `StelekitViewModel.updateCommands()`, append:
   ```kotlin
   Command(
       id = "import.paste-text",
       label = "Import text as new page",
       shortcut = null,
       action = { navigateTo(Screen.Import) }
   )
   ```

2. The command should only be active when a graph is loaded (`activeGraph != null`). Mirror the guard pattern used by the existing export commands.

**Validation criteria**:
- "Import text as new page" appears in the command palette when a graph is open
- Selecting it navigates to `Screen.Import`
- Command is absent (or disabled) when no graph is loaded

---

## Known Issues

### Potential Bugs Identified During Planning

#### Concurrency Risk: TOCTOU in Page Name Collision Check [SEVERITY: Medium]

**Description**: The collision check (`getPageByName` → `savePage`) is not atomic. If two simultaneous imports use the same page name, both pass the existence check and both attempt to write the file — the second write silently overwrites the first.

**Mitigation**:
- Disable the "Import page" button while `state.isSaving` is true to prevent concurrent invocations from the same session
- Accept the TOCTOU risk for solo-user v1; document as a known limitation
- `GraphWriter.saveMutex` serializes the actual file write, preventing file corruption

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt`

---

#### Data Integrity Risk: Block Content Exceeds Validation Limit [SEVERITY: Low]

**Description**: `Validation.validateContent` caps `Block.content` at 10 000 000 chars. A single pasted paragraph of that size is unlikely but not impossible. The current split-on-`\n\n` strategy distributes content across multiple blocks, but a single paragraph with no blank lines could produce one enormous block.

**Mitigation**:
- After splitting on `\n\n`, further split any paragraph > 100 000 chars at sentence boundaries (`. `, `? `, `! `) as a secondary split pass
- Add a validation step in `confirmImport()` that rejects content exceeding a configurable limit (default 500 000 chars total) with a user-visible error

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ImportService.kt`

---

#### Integration Risk: Matcher Null on First Import [SEVERITY: Medium]

**Description**: `PageNameIndex.matcher` starts as `null` until the first page list emission. If the user opens the import dialog immediately after app launch, `state.matchedPageNames` will always be empty and no scan progress is shown — the user sees no feedback and may assume the feature is broken.

**Mitigation**:
- In `ImportViewModel.onRawTextChanged()`, check `matcher == null` and set `state.fetchError`-equivalent state to "Loading page index..." with a `CircularProgressIndicator` in the UI
- Retry the scan automatically when the matcher transitions from null to non-null (collect the `matcherFlow` in the ViewModel scope)

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportScreen.kt`

---

#### Performance Risk: Large Paste UI Freeze [SEVERITY: Medium]

**Description**: If `ImportService.scan()` is accidentally called on the main thread (e.g. the `Dispatchers.Default` dispatch is omitted), a 50 KB paste on a slow device will block the UI thread for a noticeable period (estimated 100–500ms on low-end Android hardware).

**Mitigation**:
- Always dispatch `ImportService.scan()` via `withContext(Dispatchers.Default)` inside the ViewModel coroutine
- Add a `@VisibleForTesting` annotation and assertion in `ImportService.scan()` that verifies the calling dispatcher is not `Main` in debug builds

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ImportService.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt`

---

#### Security Risk: Unchecked URL Input [SEVERITY: Low]

**Description**: A user-supplied URL is passed directly to `ktor-client`. If the URL is a `file://` or `jar://` path, ktor-client on JVM may resolve it against the local filesystem or classpath, leaking file contents into the import text field.

**Mitigation**:
- Validate URL scheme before fetching: only `http://` and `https://` schemes are permitted
- Reject all other schemes with `FetchResult.Failure.HttpError(0)` and a user-visible message "Only http:// and https:// URLs are supported"

**Files likely affected**:
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/domain/UrlFetcherJvm.kt`

---

#### Edge Case: Stale Scan Results After Graph Change [SEVERITY: Low]

**Description**: If the user scans a paste, then creates or renames a page in another window before confirming import, the matched page names in the review stage are stale. A matched name may no longer exist, or a new page that would match may be missing.

**Mitigation**:
- This is the same stale-guard scenario documented in ADR-002 of the multi-word-term-highlighting feature
- Re-run the scan automatically when `matcherFlow` emits a new non-null value while the review stage is shown
- If the new scan produces different results, show a brief "Links updated" snackbar so the user is aware

**Files likely affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt`

---

## v2 Backlog (Out of Scope for v1)

- **New-page suggestions**: surface topics in the imported text that do not yet exist as pages (requires noun-phrase extraction or LLM step — undesigned for v1)
- **Stopword list**: add `stopwords: Set<String>` parameter to `PageNameIndex` to suppress common English function words from matching
- **URL deduplication**: check for existing pages with matching `source::` property before creating a duplicate
- **iOS/Android UrlFetcher implementations**: `UrlFetcherIos.kt` (ktor-client-darwin) and `UrlFetcherAndroid.kt` (ktor-client-okhttp already present)
- **HTML clipboard flavor**: read HTML directly from clipboard on paste (requires AWT `Toolkit` in `jvmMain`) for richer import with structure preservation
- **Block structure preservation**: convert HTML headings to Logseq heading blocks, lists to nested block trees
