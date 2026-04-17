# Implementation Plan: Import Topic Suggestions

**Feature branch**: `stelekit-import`
**Plan date**: 2026-04-17
**ADRs**: ADR-001 through ADR-005 in `project_plans/import-topic-suggestions/decisions/`

---

## Overview

Add a "Suggested new pages" chip tray to the import review stage. Local heuristic extraction runs synchronously inside `ImportService.scan()` and always provides suggestions. An optional `TopicEnricher` interface allows Claude API enrichment (or third-party plugins) to augment suggestions asynchronously. Accepted suggestions create stub pages and retroactively insert `[[wiki links]]` in the imported content.

---

## Architecture Summary

```
domain/
  TopicSuggestion.kt          — data class (term, confidence, source, accepted, dismissed)
  TopicExtractor.kt           — pure object; called from ImportService.scan()
  TopicEnricher.kt            — suspend fun interface (plugin API v1) + NoOpTopicEnricher
  PageDeleter.kt              — fun interface seam (mirrors PageSaver, enables undo tests)

ImportService.kt              — scan() gains existingNames param + topicSuggestions field
                              — insertWikiLinks() pure helper added

ImportViewModel.kt            — topicEnricher + pageDeleter constructor params
                              — ImportState gains suggestion + claude status fields
                              — runScan() launches two-coroutine pattern
                              — onSuggestionAccepted/Dismissed/AcceptAll/Undo handlers

ImportScreen.kt               — ReviewStage gains chip tray section + undo snackbar

commonMain/domain/
  ClaudeTopicEnricher.kt      — ktor-based TopicEnricher impl (API key at construction)
```

---

## Story 1 — Domain Layer

**Goal**: Define all domain types and pure functions. No I/O, no coroutines, no UI.

**Files touched** (max 4):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/TopicSuggestion.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/TopicExtractor.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/TopicEnricher.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt` (new `PageDeleter`)

**Test target**: `businessTest`

### Task 1.1 — `TopicSuggestion` model

Create `TopicSuggestion.kt` in `domain/`:

```kotlin
data class TopicSuggestion(
    val term: String,
    val confidence: Float,        // 0.0–1.0
    val source: Source,           // LOCAL | AI_ENHANCED
    val accepted: Boolean = false,
    val dismissed: Boolean = false,
) {
    enum class Source { LOCAL, AI_ENHANCED }
}
```

No tests required — pure data class with no logic.

### Task 1.2 — `TopicExtractor` object

Create `TopicExtractor.kt` in `domain/`. The `extract()` function must:

1. Apply a broad structural stop list (EXTRACTION_STOPWORDS) covering: academic/blog section headers (`introduction`, `conclusion`, `abstract`, `methodology`, `acknowledgements`, `references`, `appendix`), pervasive tech acronyms (`api`, `url`, `http`, `https`, `json`, `rest`, `html`, `css`, `sdk`, `cli`, `gui`, `ide`), and high-frequency generic nouns (`data`, `model`, `system`, `method`, `result`, `function`, `approach`, `paper`, `work`, `section`, `figure`, `table`). This list is broader than `PageNameIndex.DEFAULT_STOPWORDS` which was designed for link matching, not extraction.

2. Extract capitalized multi-word noun phrases (2–4 consecutive tokens where each token begins with an uppercase letter or is all-caps), using a `Regex` in `commonMain`. Handle camelCase tokens such as `TensorFlow`, `SQLDelight`, `GraphQL` via a secondary pattern matching mixed-case identifiers.

3. Score each candidate: `score = log1p(frequency) * capitalizationBonus * lengthBonus`, where `capitalizationBonus = 1.5` if all-caps or camelCase, `1.0` otherwise; `lengthBonus = min(1.0, termWordCount / 3.0)`.

4. Filter against `existingNames` (case-insensitive exact match on the lowercased term).

5. Apply minimum score threshold and minimum confidence of 0.2 after normalization.

6. Return at most 15 candidates sorted by score descending, each as a `TopicSuggestion` with `source = LOCAL` and `confidence` normalized to 0.0–1.0.

**Tests** in `businessTest/TopicExtractorTest.kt`:
- Single-word technical term detected and scored (e.g. `"TensorFlow"`)
- Multi-word noun phrase detected (e.g. `"machine learning"`)
- Stopword filtered out (e.g. `"Introduction"`, `"API"`)
- Sentence-initial single word not surfaced unless it appears capitalized mid-sentence elsewhere
- Term already in `existingNames` is excluded
- Results capped at 15 when input has many candidates
- Score below 0.2 suppressed

### Task 1.3 — `TopicEnricher` interface + `NoOpTopicEnricher`

Create `TopicEnricher.kt` in `domain/`:

```kotlin
fun interface TopicEnricher {
    suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>): List<TopicSuggestion>
}

class NoOpTopicEnricher : TopicEnricher {
    override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>) = localSuggestions
}
```

No tests required for `NoOpTopicEnricher` — it is a one-liner identity function.

### Task 1.4 — `PageDeleter` seam

Add `PageDeleter` to `ImportViewModel.kt` (or a nearby file if preferred for separation):

```kotlin
fun interface PageDeleter {
    suspend fun delete(page: Page): Boolean
    companion object {
        fun from(writer: GraphWriter): PageDeleter = PageDeleter { page -> writer.deletePage(page) }
        val NoOp: PageDeleter = PageDeleter { _ -> false }
    }
}
```

This task is small; add it to `ImportViewModel.kt` just below the existing `PageSaver` definition.

---

## Story 2 — ImportService Integration

**Goal**: Extend `scan()` to call `TopicExtractor`, pass `existingNames`, and return suggestions in `ScanResult`. Add `insertWikiLinks()` helper.

**Files touched** (max 2):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ImportService.kt`
- `kmp/src/commonTest/kotlin/dev/stapler/stelekit/domain/ImportServiceTest.kt` (existing test file to extend)

**Test target**: `businessTest` / `commonTest` (follow existing test file location)

### Task 2.1 — Extend `ScanResult` and `scan()`

Extend `ScanResult`:

```kotlin
data class ScanResult(
    val linkedText: String,
    val matchedPageNames: List<String>,
    val topicSuggestions: List<TopicSuggestion> = emptyList(),  // NEW
)
```

Extend `scan()` signature:

```kotlin
fun scan(
    rawText: String,
    matcher: AhoCorasickMatcher,
    existingNames: Set<String> = emptySet(),  // NEW — default preserves backward compat
): ScanResult
```

Inside `scan()`, after building `ScanResult`, call:

```kotlin
val suggestions = TopicExtractor.extract(rawText, existingNames)
return ScanResult(linkedText = sb.toString(), matchedPageNames = ..., topicSuggestions = suggestions)
```

Note: `TopicExtractor.extract()` receives `rawText`, not `linkedText`, because raw text has clean capitalization before wiki-link brackets are inserted.

**Tests** — extend existing `ImportServiceTest`:
- `scan()` with no `existingNames` returns `topicSuggestions` (may be empty or non-empty depending on text)
- `scan()` with `existingNames` containing a candidate excludes that candidate from suggestions
- `scan()` backward-compat: call with two-argument form still compiles and runs

### Task 2.2 — `insertWikiLinks()` helper

Add to `ImportService`:

```kotlin
/**
 * Wraps each plain-text occurrence of a term in [terms] with `[[term]]` syntax.
 * Occurrences already inside `[[…]]` are skipped.
 * Matching is case-insensitive; the display form from [terms] is used as the link target.
 */
fun insertWikiLinks(text: String, terms: List<String>): String
```

Implementation notes:
- Process terms longest-first to avoid partial matches inside longer phrases.
- Use a regex that matches word boundaries and excludes already-bracketed occurrences (negative lookbehind for `[[` and negative lookahead for `]]`).
- The regex must handle multi-word terms correctly (spaces between words, not crossing sentence boundaries).

**Tests** in `businessTest`:
- Single-word insertion: `"TensorFlow is great"` + `["TensorFlow"]` → `"[[TensorFlow]] is great"`
- Multi-word insertion: `"machine learning approaches"` + `["machine learning"]` → `"[[machine learning]] approaches"`
- Already-linked occurrence skipped: `"[[TensorFlow]] and TensorFlow"` + `["TensorFlow"]` → `"[[TensorFlow]] and [[TensorFlow]]"` (second occurrence linked, first unchanged)
- Case-insensitive match uses display form: `"tensorflow"` in text + `["TensorFlow"]` → `"[[TensorFlow]]"`
- Multiple terms inserted correctly without double-linking
- Empty `terms` list returns `text` unchanged

---

## Story 3 — ImportViewModel Suggestion State

**Goal**: Extend `ImportState` with suggestion fields and Claude status. Wire `topicEnricher` and `pageDeleter` constructor params. Add two-coroutine pattern in `runScan()`. Add `onSuggestionAccepted/Dismissed/AcceptAll/Undo` handlers.

**Files touched** (max 3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModelTest.kt` (existing, extend)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/PageNameIndex.kt` (read `_canonicalNames` — may need accessor)

**Test target**: `jvmTest` (follow existing `ImportViewModelTest` pattern)

### Task 3.1 — Extend `ImportState`

Add to `ImportState`:

```kotlin
// Suggestion tray state
val topicSuggestions: List<TopicSuggestion> = emptyList(),
val isEnhancing: Boolean = false,            // Claude call in flight
val claudeStatus: ClaudeStatus = ClaudeStatus.Idle,

// Undo buffer (cleared after confirm or navigation away)
val undoBuffer: List<Page> = emptyList(),
val showUndoSnackbar: Boolean = false,
val undoLinkedText: String = "",             // linkedText snapshot before acceptance
```

Define `ClaudeStatus`:

```kotlin
sealed interface ClaudeStatus {
    data object Idle : ClaudeStatus
    data object Loading : ClaudeStatus
    data object Done : ClaudeStatus
    sealed interface Failed : ClaudeStatus {
        data object Timeout : Failed
        data object RateLimited : Failed
        data object NetworkError : Failed
        data class ApiError(val code: Int) : Failed
        data object MalformedResponse : Failed
    }
}
```

### Task 3.2 — Constructor params and two-coroutine `runScan()`

Extend `ImportViewModel` primary constructor:

```kotlin
class ImportViewModel(
    private val coroutineScope: CoroutineScope,
    private val pageRepository: PageRepository,
    private val pageSaver: PageSaver,
    private val graphPath: String,
    private val urlFetcher: UrlFetcher,
    private val matcherFlow: StateFlow<AhoCorasickMatcher?>,
    private val topicEnricher: TopicEnricher = NoOpTopicEnricher(),  // NEW
    private val pageDeleter: PageDeleter = PageDeleter.NoOp,          // NEW
    private val scanDispatcher: CoroutineDispatcher = Dispatchers.Default,
)
```

Update the secondary `GraphWriter`-accepting constructor to also accept `topicEnricher` and `pageDeleter` defaulting to their no-op values.

Extend `runScan()` to the two-coroutine pattern:

```kotlin
private suspend fun runScan(text: String, matcher: AhoCorasickMatcher) {
    // Coroutine 1: synchronous heuristic scan (fast, <500ms)
    val existingNames = /* read from PageNameIndex._canonicalNames.value.keys */
    val result = withContext(scanDispatcher) {
        ImportService.scan(text, matcher, existingNames)
    }
    _state.update {
        it.copy(
            linkedText = result.linkedText,
            matchedPageNames = result.matchedPageNames,
            topicSuggestions = result.topicSuggestions,
            isScanning = false,
            isEnhancing = topicEnricher !is NoOpTopicEnricher,
            claudeStatus = if (topicEnricher !is NoOpTopicEnricher) ClaudeStatus.Loading else ClaudeStatus.Idle,
        )
    }

    // Coroutine 2: async Claude enrichment (fire-and-forget, never blocks review UI)
    if (topicEnricher !is NoOpTopicEnricher) {
        coroutineScope.launch {
            try {
                withTimeout(8_000) {
                    val enriched = topicEnricher.enhance(text, _state.value.topicSuggestions)
                    val currentSuggestions = _state.value.topicSuggestions
                    val merged = mergeEnrichedSuggestions(currentSuggestions, enriched)
                    _state.update {
                        it.copy(topicSuggestions = merged, isEnhancing = false, claudeStatus = ClaudeStatus.Done)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _state.update { it.copy(isEnhancing = false, claudeStatus = ClaudeStatus.Failed.Timeout) }
            } catch (e: Exception) {
                _state.update { it.copy(isEnhancing = false, claudeStatus = ClaudeStatus.Failed.NetworkError) }
            }
        }
    }
}
```

`mergeEnrichedSuggestions(current, enriched)` logic:
- Never re-show dismissed items (filter `enriched` against dismissed terms in `current`).
- For items in both lists, use the Claude confidence score.
- Append net-new Claude items after existing items (do not re-sort by confidence — avoid jarring reorder during merge).
- Apply 15-item cap after merge.

### Task 3.3 — Accept/Dismiss/AcceptAll/Undo handlers

Add to `ImportViewModel`:

```kotlin
fun onSuggestionAccepted(term: String) {
    val snapshot = _state.value.linkedText
    _state.update { state ->
        val updated = state.topicSuggestions.map {
            if (it.term == term) it.copy(accepted = true) else it
        }
        val acceptedTerms = updated.filter { it.accepted }.map { it.term }
        state.copy(
            topicSuggestions = updated,
            linkedText = ImportService.insertWikiLinks(state.linkedText, listOf(term)),
            undoLinkedText = if (state.undoBuffer.isEmpty()) snapshot else state.undoLinkedText,
        )
    }
}

fun onSuggestionDismissed(term: String) {
    _state.update { state ->
        state.copy(topicSuggestions = state.topicSuggestions.map {
            if (it.term == term) it.copy(dismissed = true) else it
        })
    }
}

fun onAcceptAllSuggestions() {
    // Caps at 10 per bulk-accept gesture
    val toAccept = _state.value.topicSuggestions
        .filter { !it.accepted && !it.dismissed }
        .take(10)
    toAccept.forEach { onSuggestionAccepted(it.term) }
}

fun onUndoStubCreation() {
    // Called from snackbar; only available pre-confirm
    coroutineScope.launch {
        _state.value.undoBuffer.forEach { page -> pageDeleter.delete(page) }
        _state.update { state ->
            state.copy(
                topicSuggestions = state.topicSuggestions.map { it.copy(accepted = false) },
                linkedText = state.undoLinkedText,
                undoBuffer = emptyList(),
                showUndoSnackbar = false,
                undoLinkedText = "",
            )
        }
    }
}
```

Note: Stub pages are not created at `onSuggestionAccepted()` time — only at `confirmImport()`. The undo snackbar is shown after `confirmImport()` completes (see Story 5).

**Tests** — extend `ImportViewModelTest`:
- `onSuggestionAccepted` sets `accepted = true` and updates `linkedText` with `[[term]]`
- `onSuggestionDismissed` sets `dismissed = true`; item not shown in visible suggestions
- `onAcceptAllSuggestions` caps at 10 items
- Claude enrichment with `FakeTopicEnricher` updates suggestions without re-showing dismissed items
- `runScan()` with `NoOpTopicEnricher` does not set `isEnhancing = true`
- `runScan()` with non-NoOp enricher sets `isEnhancing = true` then resolves to `ClaudeStatus.Done`
- Timeout in enricher sets `claudeStatus = ClaudeStatus.Failed.Timeout`

---

## Story 4 — ImportScreen Chip Tray

**Goal**: Add the chip tray section to `ReviewStage` in `ImportScreen.kt`. Wire callbacks from ViewModel.

**Files touched** (max 2):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportScreen.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/ImportScreenTest.kt` (extend if exists, otherwise screenshot tests in `jvmTest`)

**Test target**: `jvmTest` (screenshot tests via Roborazzi or compose test)

### Task 4.1 — Wire ViewModel callbacks through `ReviewStage`

Update `ImportScreen` to pass callbacks to `ReviewStage`:

```kotlin
Stage.REVIEW -> ReviewStage(
    state = state,
    onConfirmImport = { coroutineScope.launch { viewModel.confirmImport() } },
    onBack = { stage = Stage.INPUT },
    onSuggestionAccepted = viewModel::onSuggestionAccepted,
    onSuggestionDismissed = viewModel::onSuggestionDismissed,
    onAcceptAllSuggestions = viewModel::onAcceptAllSuggestions,
    onUndoStubCreation = viewModel::onUndoStubCreation,
)
```

Update `ReviewStage` signature to include these four parameters.

### Task 4.2 — `TopicSuggestionTray` composable

Add a private `@Composable` function `TopicSuggestionTray` in `ImportScreen.kt` (or extracted to a new file in `ui/components/` if it grows large). Place it in `ReviewStage`'s column between the matched-pages section and the `Spacer`/text preview.

Tray structure:
- Hidden entirely when `state.topicSuggestions.filter { !it.dismissed }.isEmpty()`.
- Section header row: label `"Suggested new pages (N)"` and `TextButton("Accept All")`.
- Claude status badge: shown in the header when `claudeStatus != Idle`. `"AI-enhanced"` when `Done`, a small `CircularProgressIndicator` inline when `Loading`, `"AI unavailable"` (muted) for any `Failed` variant.
- `FlowRow` (or `LazyRow`) of `SuggestionChip` items for the top 8 visible suggestions. A `TextButton("Show N more")` appears when there are more than 8 non-dismissed suggestions.
- Each chip: confidence dot (green/yellow/orange `Box` of 8.dp), term label, either `×` dismiss `IconButton` (if not yet accepted) or a checkmark (if accepted). Accepted chips use `OutlinedButton` or a muted chip style.
- Confidence dot colors: green = `MaterialTheme.colorScheme.primary` for score >= 0.7; yellow = a warm secondary tone for 0.4–0.69; orange = `MaterialTheme.colorScheme.error` for 0.2–0.39.

Accept All confirmation: use a local `var showAcceptAllDialog by remember { mutableStateOf(false) }`. When `showAcceptAllDialog` is true, render an `AlertDialog` with the message and OK/Cancel.

Undo snackbar: in `ReviewStage`, add a `Scaffold`-level `snackbarHostState` and a `LaunchedEffect(state.showUndoSnackbar)` that shows the snackbar when the flag is true and calls `onUndoStubCreation` if the user presses the action.

---

## Story 5 — `confirmImport()` Stub Creation

**Goal**: Extend `confirmImport()` to create stub pages for accepted suggestions, apply `insertWikiLinks` to the final text, and trigger the undo snackbar.

**Files touched** (max 2):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModelTest.kt`

**Test target**: `jvmTest`

### Task 5.1 — Stub creation in `confirmImport()`

Insert the following block in `confirmImport()` after the existing collision check (after line ~214) and before the block-building logic:

```kotlin
// Create stub pages for accepted suggestions (pre-accept existence check)
val acceptedSuggestions = currentState.topicSuggestions.filter { it.accepted }
val createdStubs = mutableListOf<Page>()
for (suggestion in acceptedSuggestions) {
    val existingStub = pageRepository.getPageByName(suggestion.term).first().getOrNull()
    if (existingStub == null) {
        val stubPage = Page(
            uuid = UuidGenerator.generateV7(),
            name = suggestion.term,
            createdAt = now,
            updatedAt = now,
        )
        pageSaver.save(stubPage, emptyList(), graphPath)
        createdStubs.add(stubPage)
    }
    // If page exists already, link will resolve — skip creation silently
}
```

After stub creation, compute the final text with all accepted wiki links applied:

```kotlin
val acceptedTerms = acceptedSuggestions.map { it.term }
val contentSource = if (currentState.linkedText.isNotBlank()) currentState.linkedText else currentState.rawText
val finalText = if (acceptedTerms.isEmpty()) contentSource
                else ImportService.insertWikiLinks(contentSource, acceptedTerms)
```

Use `finalText` instead of `currentState.linkedText` as the content source for the block-building step.

After `pageSaver.save(page, blocks, graphPath)` succeeds, trigger the undo snackbar if any stubs were created:

```kotlin
if (createdStubs.isNotEmpty()) {
    _state.update { state ->
        state.copy(
            undoBuffer = createdStubs,
            showUndoSnackbar = true,
            undoLinkedText = contentSource,  // pre-insertWikiLinks text for revert
        )
    }
}
_state.update { it.copy(savedPageName = page.name) }
```

Note: `savedPageName` being set triggers `ImportScreen`'s `LaunchedEffect` to call `onDismiss()`. The snackbar must be shown before dismissal — ensure the `showUndoSnackbar` update fires before `savedPageName` is set, or restructure the `LaunchedEffect` to delay dismissal until after the undo window. Preferred: set `savedPageName` inside the `finally` block, and set `showUndoSnackbar` before it.

### Task 5.2 — Prerequisite check: `deletePage` availability

`GraphWriter.deletePage(page: Page): Boolean` is confirmed to exist (line 156 of `GraphWriter.kt`). The `PageDeleter.from(writer)` seam wraps it directly. No additional work required.

**Tests** — extend `ImportViewModelTest`:
- `confirmImport()` with accepted suggestion calls `pageSaver.save` for the stub before the import page
- Accepted suggestion where page already exists does not call `pageSaver.save` for that stub (collision check)
- `finalText` passed to block builder contains `[[AcceptedTerm]]` for each accepted suggestion
- `showUndoSnackbar` is true after `confirmImport()` when stubs were created
- `onUndoStubCreation()` calls `pageDeleter.delete` for each stub and reverts `linkedText`
- No stubs created → `showUndoSnackbar` remains false

---

## Story 6 — `ClaudeTopicEnricher`

**Goal**: Implement the ktor-based `TopicEnricher` for the Claude API.

**Files touched** (max 3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricher.kt` (new)
- `kmp/build.gradle.kts` (add ktor content-negotiation deps if not already transitive)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricherTest.kt` (new, integration test guarded by API key env var)

**Test target**: `jvmTest` (integration only, requires `ANTHROPIC_API_KEY` env var to run)

### Task 6.1 — Dependencies

In `kmp/build.gradle.kts`, verify whether `ktor-client-content-negotiation` and `ktor-serialization-kotlinx-json` are already transitively available from `coil-network-ktor3`. If not, add:

```kotlin
commonMain.dependencies {
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
}
```

### Task 6.2 — `ClaudeTopicEnricher` implementation

```kotlin
class ClaudeTopicEnricher(
    private val apiKey: String,
    private val httpClient: HttpClient,
) : TopicEnricher {

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
        private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        private const val MAX_INPUT_TOKENS = 15_000
    }

    override suspend fun enhance(
        rawText: String,
        localSuggestions: List<TopicSuggestion>,
    ): List<TopicSuggestion> {
        val truncatedText = rawText.take(MAX_INPUT_TOKENS * 4) // ~4 chars/token approximation
        val candidateJson = localSuggestions.joinToString { "\"${it.term}\"" }

        val prompt = buildString {
            append("You are a knowledge graph assistant. ")
            append("Given the document below and a list of candidate page names, ")
            append("return a JSON array of objects with 'term' (string) and 'confidence' (float 0-1). ")
            append("Re-rank the candidates by page-worthiness. You may add up to 5 net-new concepts. ")
            append("Return ONLY the JSON array, no markdown, no explanation.\n\n")
            append("Candidates: [$candidateJson]\n\n")
            append("<document>\n$truncatedText\n</document>")
        }

        val response = httpClient.post(MESSAGES_URL) {
            headers {
                append("x-api-key", apiKey)
                append("anthropic-version", ANTHROPIC_VERSION)
            }
            contentType(ContentType.Application.Json)
            setBody(MessagesRequest(
                model = CLAUDE_MODEL,
                maxTokens = 256,
                messages = listOf(Message(role = "user", content = prompt)),
            ))
        }

        // Parse response; wrap in runCatching; return localSuggestions on any parse failure
        val body = response.body<MessagesResponse>()
        val rawJson = body.content.firstOrNull()?.text ?: return localSuggestions

        return runCatching {
            val parsed = Json.decodeFromString<List<ClaudeCandidate>>(rawJson)
            parsed.map { candidate ->
                TopicSuggestion(
                    term = candidate.term,
                    confidence = candidate.confidence.coerceIn(0f, 1f),
                    source = TopicSuggestion.Source.AI_ENHANCED,
                )
            }
        }.getOrElse {
            localSuggestions // fallback on JSON parse failure
        }
    }
}

@Serializable private data class ClaudeCandidate(val term: String, val confidence: Float)
@Serializable private data class MessagesRequest(val model: String, @SerialName("max_tokens") val maxTokens: Int, val messages: List<Message>)
@Serializable private data class Message(val role: String, val content: String)
@Serializable private data class MessagesResponse(val content: List<ContentBlock>)
@Serializable private data class ContentBlock(val type: String, val text: String)
```

HTTP status error handling: wrap the `httpClient.post` call in a `try/catch`. On HTTP 429, retry once after 2 seconds. On any other error, throw and let the ViewModel's `catch` block map it to `ClaudeStatus.Failed.NetworkError` (or the appropriate variant).

### Task 6.3 — Integration test (opt-in)

Create `ClaudeTopicEnricherTest.kt` in `jvmTest`. Guard every test with:

```kotlin
private val apiKey = System.getenv("ANTHROPIC_API_KEY") ?: return
```

Tests (run only when key is configured):
- `enhance()` with a real technical text returns non-empty suggestions
- `enhance()` with an empty `localSuggestions` list may still return Claude-identified concepts
- Timeout behavior: configure a 1ms timeout and verify `TimeoutCancellationException` is thrown (does not require a real API key)
- Malformed JSON response (mock HTTP client returning invalid JSON) falls back to `localSuggestions`

---

## Known Issues

### Potential Bug: `insertWikiLinks` Double-Linking on Re-Scan

**Description**: If the user edits text after accepting a suggestion (triggering a re-scan), and the re-scan re-runs `onRawTextChanged`, `runScan()` will produce a fresh `ScanResult` without the accepted suggestions. The `linkedText` in state will be overwritten, losing the accepted `[[wiki links]]`. The `topicSuggestions` will also be regenerated, potentially re-surfacing dismissed items.

**Mitigation**: When `runScan()` completes, re-apply accepted suggestions from the current state before updating `linkedText`. Specifically, after `_state.update { it.copy(linkedText = result.linkedText, ...) }`, check `_state.value.topicSuggestions.filter { it.accepted }` and re-insert their wiki links. Dismissed suggestions must also be re-applied to the newly generated suggestion list by merging `dismissed = true` from the old state for matching terms.

**Files Likely Affected**:
- `ImportViewModel.kt` — `runScan()` method

**Prevention Strategy**: Add a `reapplyAcceptedState(newSuggestions, oldSuggestions)` helper that merges `accepted` and `dismissed` flags from `oldSuggestions` onto `newSuggestions` by `term`. Call from `runScan()` after computing the new result.

---

### Potential Bug: Undo Snackbar Dismissed by `savedPageName` LaunchedEffect

**Description**: `ImportScreen` currently calls `onDismiss()` inside a `LaunchedEffect(state.savedPageName)` when `savedPageName != null`. If `showUndoSnackbar` is set at the same time as `savedPageName`, the screen is dismissed before the user sees the snackbar.

**Mitigation**: Set `savedPageName` after a 10-second delay OR change the dismiss logic to respect `showUndoSnackbar`. Recommended: change `LaunchedEffect` to only dismiss when `savedPageName != null && !state.showUndoSnackbar`. The snackbar's own timeout (`onUndoStubCreation` or a 10-second delay) then clears `showUndoSnackbar`, triggering dismissal.

**Files Likely Affected**:
- `ImportScreen.kt` — `LaunchedEffect(state.savedPageName)` block

---

### Potential Bug: Claude Enrichment Based on Stale `rawText`

**Description**: If the user edits the text in the input stage after the Claude enrichment coroutine has been launched (possible if they navigate back), the Claude response will be based on a different text version than what is currently displayed. The merge will apply suggestions from the stale text to the current suggestion list.

**Mitigation**: Hash `rawText` at enrichment launch time and store it in a local variable inside the coroutine. Before applying the enriched result, compare the hash to `_state.value.rawText.hashCode()`. If they differ, discard the enriched result silently. (This pattern mirrors the `capturedContent` version-stamp from ADR-002 of multi-word-term-highlighting.)

**Files Likely Affected**:
- `ImportViewModel.kt` — the Claude enrichment `launch` block inside `runScan()`

---

### Potential Bug: `PageDeleter` Not Exposed in Production `ImportViewModel` Constructor

**Description**: The secondary `GraphWriter`-accepting constructor currently creates `PageSaver.from(writer)`. If `PageDeleter.from(writer)` is not added to the same constructor, the undo path will silently use `PageDeleter.NoOp` in production and stubs will not be deleted on undo.

**Mitigation**: Ensure the secondary constructor is updated to include `pageDeleter = PageDeleter.from(graphWriter)` alongside the `pageSaver` line. Add a test that verifies `onUndoStubCreation` calls the deleter — this will catch the regression if the secondary constructor is missed.

**Files Likely Affected**:
- `ImportViewModel.kt` — secondary constructor

---

## Implementation Order

Stories are sequentially dependent in this order: 1 → 2 → 3 → 4 → 5 → 6. Story 6 (ClaudeTopicEnricher) can be parallelized with Story 4 (ImportScreen chip tray) since they share no direct dependencies after Story 3 is complete.

```
Story 1 (domain types)
  → Story 2 (ImportService integration)
    → Story 3 (ImportViewModel state + two-coroutine pattern)
      → Story 4 (ImportScreen chip tray)       ←┐
      → Story 5 (confirmImport stubs)            │ can run in parallel
      → Story 6 (ClaudeTopicEnricher)          ←┘
```

---

## Test Coverage Targets

| Layer | Test file | Target |
|---|---|---|
| `TopicExtractor` | `businessTest/TopicExtractorTest.kt` | 7 test cases covering extraction, scoring, filtering, caps |
| `ImportService.insertWikiLinks` | `businessTest/ImportServiceTest.kt` | 6 test cases covering edge cases (multi-word, already-linked, case) |
| `ImportService.scan()` with suggestions | existing `ImportServiceTest.kt` | 3 new test cases |
| `ImportViewModel` suggestion handlers | `jvmTest/ImportViewModelTest.kt` | 8 new test cases |
| `ImportViewModel.confirmImport()` stubs | `jvmTest/ImportViewModelTest.kt` | 6 new test cases |
| `ClaudeTopicEnricher` | `jvmTest/ClaudeTopicEnricherTest.kt` | 4 test cases (3 guarded by API key) |
