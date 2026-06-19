# Implementation Plan: llm-provider

**Feature**: Two-tier auto-tag suggestion for blocks, triggered from editing view, share widget, and voice input
**Date**: 2026-06-13
**Status**: Ready for implementation
**ADRs**:
- ADR-001: LlmTagProvider Delegates to LlmFormatterProvider
- ADR-002: WorkManager for Share Widget LLM Calls
- ADR-003: Introduce ProGuard Rules File for Release Builds

---

## Dependency Visualization

```
Phase 1: Core engine + models (no UI dependencies)
  1.1 TagSuggestion model + TagSuggestionEngine + LlmTagProvider
        ↓
Phase 2: Expose PageNameIndex; wire TagSuggestionViewModel
  2.1 Expose pageNameIndex on StelekitViewModel
  2.2 TagSuggestionViewModel (state machine, scope, close())
        ↓
Phase 3: Suggestion UI (depends on Phase 2 state types)
  3.1 SuggestionBottomSheet composable
  3.2 TagChipRow composable
        ↓
Phase 4: Entry points (depend on Phase 2 + 3)
  4.1 Block-scope entry point (BlockList + PageView wiring)
  4.2 Page-scope entry point (PageView overflow menu)
  4.3 Voice input integration (VoiceCaptureState + VoiceCaptureViewModel)
        ↓
Phase 5: Settings UI (parallel to Phase 4 — only depends on Phase 1 settings keys)
  5.1 TagSettings class + SettingsCategory enum extension
  5.2 LlmProviderSettings composable
  5.3 SettingsDialog wiring
        ↓
Phase 6: App.kt wiring + Android share widget
  6.1 App.kt: build TagSuggestionEngine + TagSuggestionViewModel (depends on 2, 5)
  6.2 Android share widget Activity + WorkManager worker (parallel, depends on Phase 1)
        ↓
Phase 7: ProGuard + tests
  7.1 proguard-rules.pro (depends on knowing all new serializable classes — do last)
  7.2 Unit tests for TagSuggestionEngine
  7.3 Unit tests for LlmTagProvider
```

---

## Phase 1: Core Engine and Domain Models

### Epic 1.1: Tag Suggestion Engine

**Goal**: Implement the two-tier tag suggestion engine (`tags/` package) with no UI or platform dependencies. This is the foundation all other phases build on.

#### Story 1.1.1: TagSuggestion model and TagSuggestionRequest

**As a** developer, **I want** a `TagSuggestion` model and `TagSuggestionRequest` value object in `tags/`, **so that** the engine and ViewModel share a common vocabulary without importing from `domain/`.

**Acceptance Criteria**:
- `TagSuggestion` wraps `term: String`, `confidence: Float`, `source: Source (LOCAL | LLM)`, `autoApplied: Boolean`
- `TagSuggestionRequest` holds `blockUuid: String`, `blockContent: String`, `pageVocabulary: List<String>`
- Both are `data class` in `commonMain` package `dev.stapler.stelekit.tags`

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestion.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionRequest.kt` (new)

##### Task 1.1.1a: Create TagSuggestion.kt (~3 min)

Create file at `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestion.kt`:

```kotlin
package dev.stapler.stelekit.tags

data class TagSuggestion(
    val term: String,           // exact page name from graph vocabulary
    val confidence: Float,      // 0.0–1.0; >=0.95 triggers auto-apply
    val source: Source,
    val autoApplied: Boolean = false,
) {
    enum class Source { LOCAL, LLM }
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestion.kt`

##### Task 1.1.1b: Create TagSuggestionRequest.kt (~2 min)

Create file at `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionRequest.kt`:

```kotlin
package dev.stapler.stelekit.tags

data class TagSuggestionRequest(
    val blockUuid: String,
    val blockContent: String,
    val pageVocabulary: List<String>,   // token-filtered candidate page names
)
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionRequest.kt`

---

#### Story 1.1.2: LlmTagProvider — constrained-vocabulary LLM adapter

**As a** developer, **I want** `LlmTagProvider` to produce `List<TagSuggestion>` from an injected `LlmFormatterProvider`, **so that** both Anthropic and OpenAI-compatible providers work without new HTTP plumbing.

**Acceptance Criteria**:
- Delegates entirely to `LlmFormatterProvider.format(blockContent, systemPrompt)`
- System prompt includes vocabulary list in `<tags>` block; instructs model to return newline-delimited page names only
- Vocabulary pre-filtered to ≤200 names via token-overlap filter before sending
- Block content capped at 500 chars
- Returns `Either<DomainError, List<TagSuggestion>>` using Arrow
- Does NOT copy `ClaudeTopicEnricher`'s 429 retry loop — CircuitBreaker on the injected provider handles resilience
- Wraps `withTimeout(8.seconds)` around the `provider.format()` call

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/LlmTagProvider.kt` (new)

##### Task 1.1.2a: Create LlmTagProvider.kt (~5 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/LlmTagProvider.kt`:

```kotlin
package dev.stapler.stelekit.tags

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class LlmTagProvider(
    private val provider: LlmFormatterProvider,
    private val timeoutSeconds: Long = 8,
) {
    companion object {
        private const val MAX_BLOCK_CHARS = 500
        private const val MAX_VOCABULARY_SIZE = 200
        private const val MIN_TOKEN_OVERLAP = 1
    }

    suspend fun suggestTags(
        request: TagSuggestionRequest,
    ): Either<DomainError, List<TagSuggestion>> {
        val truncatedContent = request.blockContent.take(MAX_BLOCK_CHARS)
        val filtered = tokenOverlapFilter(truncatedContent, request.pageVocabulary)
            .take(MAX_VOCABULARY_SIZE)
        if (filtered.isEmpty()) return emptyList<TagSuggestion>().right()

        val systemPrompt = buildSystemPrompt(filtered)
        return try {
            withTimeout(timeoutSeconds.seconds) {
                when (val result = provider.format(truncatedContent, systemPrompt)) {
                    is LlmResult.Success -> parseResponse(result.formattedText, filtered).right()
                    is LlmResult.Failure.ApiError -> DomainError.NetworkError.HttpError(
                        result.code, result.message
                    ).left()
                    is LlmResult.Failure.NetworkError -> DomainError.NetworkError.RequestFailed(
                        "Network error"
                    ).left()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // DomainError.NetworkError.Timeout requires message: String
            DomainError.NetworkError.Timeout("LLM tag suggestion timed out after ${timeoutSeconds}s").left()
        } catch (e: Exception) {
            DomainError.NetworkError.RequestFailed(e.message ?: "LLM request failed").left()
        }
    }

    /** Keep only vocabulary names that share at least one token with the block text. */
    private fun tokenOverlapFilter(blockText: String, vocabulary: List<String>): List<String> {
        val blockTokens = blockText.lowercase().split(Regex("\\W+")).toSet()
        return vocabulary.filter { name ->
            name.lowercase().split(Regex("\\W+")).any { it in blockTokens }
        }
    }

    private fun buildSystemPrompt(vocabulary: List<String>): String {
        val tagList = vocabulary.joinToString("\n") { "- $it" }
        return """
You are a knowledge-graph tagging assistant.
Given a block of text and a list of existing page names, return ONLY the page names from the
list below that are genuinely relevant to the block content.
Output one page name per line. No explanation, no markdown, no extra text.
If nothing is relevant, output nothing.

<tags>
$tagList
</tags>
""".trimIndent()
    }

    private fun parseResponse(responseText: String, vocabulary: List<String>): List<TagSuggestion> {
        val vocabLower = vocabulary.associateBy { it.lowercase() }
        val lines = responseText.lines()
        val results = mutableListOf<TagSuggestion>()
        lines.forEachIndexed { index, line ->
            val cleaned = line.trim().removePrefix("- ").trim()
            if (cleaned.isBlank()) return@forEachIndexed
            val canonical = vocabLower[cleaned.lowercase()] ?: return@forEachIndexed
            // Positional confidence decay: 0.85 at position 0, decrement 0.02 per position
            val confidence = (0.85f - index * 0.02f).coerceIn(0.5f, 0.85f)
            results += TagSuggestion(
                term = canonical,
                confidence = confidence,
                source = TagSuggestion.Source.LLM,
            )
        }
        return results
    }
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/LlmTagProvider.kt`

---

#### Story 1.1.3: TagSuggestionEngine — two-tier orchestrator

**As a** developer, **I want** `TagSuggestionEngine` to run the local AhoCorasick scan first (always synchronous, offline), then optionally the LLM tier, **so that** the local tier always produces instant results regardless of network state.

**Acceptance Criteria**:
- `directMatch(blockContent)`: uses `AhoCorasickMatcher` from `PageNameIndex.matcher.value`; returns `List<TagSuggestion>` with `confidence = 1.0f, source = LOCAL`
- All AhoCorasick exact hits are `confidence = 1.0f` and `autoApplied = true`. The `AUTO_APPLY_THRESHOLD` constant governs only LLM suggestions (which range 0.50–0.85). A code comment in `TagSuggestionEngine.kt` must document this explicitly to prevent future confusion.
- `llmSuggest(blockContent, alreadyLinkedTerms)`: delegates to injected `LlmTagProvider?`; returns `Either<DomainError, List<TagSuggestion>>`; no-ops (returns empty right) when provider is null
- All LLM suggestions below `AUTO_APPLY_THRESHOLD` have `autoApplied = false`
- Deduplicates by lowercased term (local wins over LLM on collision)
- Constructor accepts `vocabularyProvider: () -> List<String>` lambda — engine calls this lambda to get the vocabulary for the LLM tier. Does NOT directly access `PageNameIndex._entries` (private field). In `App.kt`, wire as `vocabularyProvider = { stelekitViewModel.pageNameIndex.vocabularyNames() }`.

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt` (new)

##### Task 1.1.3a: Create TagSuggestionEngine.kt (~5 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt`:

```kotlin
package dev.stapler.stelekit.tags

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.domain.PageNameIndex
import dev.stapler.stelekit.error.DomainError

class TagSuggestionEngine(
    private val pageNameIndex: PageNameIndex,
    private val llmTagProvider: LlmTagProvider? = null,
    /**
     * Lambda that returns the current vocabulary for LLM pre-filtering.
     * Injected rather than reading PageNameIndex._entries directly (which is private).
     * In App.kt, wire as: vocabularyProvider = { pageNameIndex.vocabularyNames() }
     */
    private val vocabularyProvider: () -> List<String> = { pageNameIndex.vocabularyNames() },
) {
    companion object {
        /**
         * AUTO_APPLY_THRESHOLD governs ONLY LLM suggestions (which range 0.50–0.85 by positional decay).
         * AhoCorasick local exact hits always return confidence=1.0 and are always autoApplied=true —
         * they are never gated by this threshold. Do not add a threshold check to directMatch().
         */
        const val AUTO_APPLY_THRESHOLD = 0.95f
    }

    /**
     * Synchronous local scan. Uses the current AhoCorasickMatcher snapshot (null = no suggestions).
     * All results are confidence=1.0 and autoApplied=true — exact page name matches require no threshold.
     * Run this off the main thread (withContext(Dispatchers.Default)) for large graphs.
     */
    fun directMatch(blockContent: String): List<TagSuggestion> {
        val matcher = pageNameIndex.matcher.value ?: return emptyList()
        // AhoCorasickMatcher.findAll() is the correct method name — NOT .scan()
        return matcher.findAll(blockContent.lowercase())
            .map { span ->
                TagSuggestion(
                    term = span.canonicalName,
                    confidence = 1.0f,
                    source = TagSuggestion.Source.LOCAL,
                    autoApplied = true,  // exact hits always auto-apply
                )
            }
            .distinctByTerm()
    }

    /**
     * Async LLM scan. Returns empty list when no LLM provider is configured.
     * vocabularyProvider() supplies the constrained vocabulary to the provider.
     */
    suspend fun llmSuggest(
        blockContent: String,
        alreadyLinkedTerms: Set<String> = emptySet(),
    ): Either<DomainError, List<TagSuggestion>> {
        val provider = llmTagProvider ?: return emptyList<TagSuggestion>().right()
        val vocabulary = vocabularyProvider()
            .filter { it.lowercase() !in alreadyLinkedTerms.map(String::lowercase).toSet() }
        val request = TagSuggestionRequest(
            blockUuid = "",
            blockContent = blockContent,
            pageVocabulary = vocabulary,
        )
        return provider.suggestTags(request).map { suggestions ->
            suggestions
                .filter { it.term.lowercase() !in alreadyLinkedTerms.map(String::lowercase).toSet() }
                .distinctByTerm()
        }
    }

    private fun List<TagSuggestion>.distinctByTerm(): List<TagSuggestion> {
        val seen = mutableSetOf<String>()
        return filter { seen.add(it.term.lowercase()) }
    }
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt`

---

## Phase 2: PageNameIndex Exposure and TagSuggestionViewModel

### Epic 2.1: Expose PageNameIndex vocabulary and wire ViewModel

**Goal**: Make `PageNameIndex` accessible to `TagSuggestionEngine` outside of `StelekitViewModel`, and implement the `TagSuggestionViewModel` state machine.

#### Story 2.1.1: Add vocabularyNames() to PageNameIndex and expose pageNameIndex on StelekitViewModel

**As a** developer, **I want** `PageNameIndex.vocabularyNames()` to return the current list of page names and `StelekitViewModel.pageNameIndex` to be a public val, **so that** `TagSuggestionEngine` can access the full vocabulary without duplicating state.

**Acceptance Criteria**:
- `PageNameIndex.vocabularyNames(): List<String>` returns the canonical names from the current `_entries` snapshot
- `StelekitViewModel` adds `val pageNameIndex: PageNameIndex` as a public accessor (the private backing field already exists)

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/PageNameIndex.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 2.1.1a: Add vocabularyNames() to PageNameIndex (~3 min)

In `PageNameIndex.kt`, add a public method that reads from `_entries.value`:

```kotlin
/** Returns the canonical page names in the current matcher index snapshot. */
fun vocabularyNames(): List<String> = _entries.value.map { it.canonicalName }.distinct()
```

Add this after the `matcher` val declaration (around line 70).

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/PageNameIndex.kt`

##### Task 2.1.1b: Expose pageNameIndex on StelekitViewModel (~2 min)

In `StelekitViewModel.kt`, change line 332 from:
```kotlin
private val pageNameIndex = PageNameIndex(pageRepository, scope)
```
to:
```kotlin
val pageNameIndex = PageNameIndex(pageRepository, scope)
```

`suggestionMatcher` remains as-is (line 335).

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

---

#### Story 2.1.2: TagSuggestionViewModel state machine

**As a** developer, **I want** `TagSuggestionViewModel` to own a `CoroutineScope` with `CoroutineExceptionHandler` and expose `TagSuggestionState` as a `StateFlow`, **so that** the UI can observe two-phase updates (local instant, LLM deferred) without lifecycle leaks.

**Acceptance Criteria**:
- `TagSuggestionState` sealed interface: `Idle`, `Loading`, `Ready(localSuggestions, llmSuggestions, llmError?)`, `Error(message)`
- `requestSuggestions(blockUuid, blockContent)`: cancels any in-flight job; immediately emits `Ready(local=[directMatch results], llmSuggestions=[])`, then updates to `Ready(local, llm=[llmSuggest results])` when LLM completes
- `close()`: cancels scope
- Owns `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler)`
- Does NOT accept a `rememberCoroutineScope()`

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionState.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt` (new)

##### Task 2.1.2a: Create TagSuggestionState.kt (~2 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionState.kt`:

```kotlin
package dev.stapler.stelekit.tags

sealed interface TagSuggestionState {
    data object Idle : TagSuggestionState
    data object Loading : TagSuggestionState
    data class Ready(
        val blockUuid: String,
        val localSuggestions: List<TagSuggestion>,
        val llmSuggestions: List<TagSuggestion>,
        val llmError: String? = null,
    ) : TagSuggestionState
    data class Error(val message: String) : TagSuggestionState
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionState.kt`

##### Task 2.1.2b: Create TagSuggestionViewModel.kt (~5 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt`:

```kotlin
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class TagSuggestionViewModel(
    private val engine: TagSuggestionEngine,
) {
    private val logger = Logger("TagSuggestionViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                logger.error("Uncaught error: ${e::class.simpleName}: ${e.message}")
                _state.value = TagSuggestionState.Error(e.message ?: "Unknown error")
            }
        }
    )

    private val _state = MutableStateFlow<TagSuggestionState>(TagSuggestionState.Idle)
    val state: StateFlow<TagSuggestionState> = _state.asStateFlow()

    private var suggestionJob: Job? = null

    fun requestSuggestions(blockUuid: String, blockContent: String, alreadyLinkedTerms: Set<String> = emptySet()) {
        suggestionJob?.cancel()
        _state.value = TagSuggestionState.Loading

        suggestionJob = scope.launch {
            // Tier 1: local scan — synchronous, always available
            val localSuggestions = engine.directMatch(blockContent)
            _state.value = TagSuggestionState.Ready(
                blockUuid = blockUuid,
                localSuggestions = localSuggestions,
                llmSuggestions = emptyList(),
            )

            // Tier 2: LLM suggestions — async, may be empty if no provider
            engine.llmSuggest(blockContent, alreadyLinkedTerms).fold(
                ifLeft = { err ->
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmError = err.message)
                        } else current
                    }
                },
                ifRight = { llmSuggestions ->
                    _state.update { current ->
                        if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                            current.copy(llmSuggestions = llmSuggestions)
                        } else current
                    }
                }
            )
        }
    }

    fun dismiss() {
        suggestionJob?.cancel()
        _state.value = TagSuggestionState.Idle
    }

    fun close() {
        scope.cancel()
    }
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt`

---

## Phase 3: Suggestion UI Components

### Epic 3.1: Tag Suggestion UI

**Goal**: Implement the bottom sheet and chip row that present suggestions to the user, with accept/dismiss per chip and auto-apply of high-confidence matches.

#### Story 3.1.1: TagChipRow — inline chip strip for suggestions

**As a** user, **I want** suggested tags presented as dismissable chips, **so that** I can accept or ignore individual suggestions with a single tap.

**Acceptance Criteria**:
- Renders a `LazyRow` of `FilterChip` components, one per non-auto-applied suggestion
- Each chip shows the tag term; tap accepts (calls `onAccept(suggestion)`), long-press dismisses (calls `onDismiss(suggestion)`)
- Auto-applied suggestions (from Tier 1 local scan, `autoApplied = true`) are NOT shown — they were already written to the block
- Shows a "Loading..." indicator when `llmSuggestions` is still pending
- Returns `Unit` when suggestion list is empty

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/TagChipRow.kt` (new)

##### Task 3.1.1a: Create TagChipRow.kt (~5 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/TagChipRow.kt`:

Implement a `@Composable fun TagChipRow(suggestions: List<TagSuggestion>, isLlmLoading: Boolean, llmError: String?, onAccept: (TagSuggestion) -> Unit, onDismiss: (TagSuggestion) -> Unit, modifier: Modifier = Modifier)` using `LazyRow`. Show `FilterChip` per suggestion (exclude `autoApplied = true` items). Append a `CircularProgressIndicator` (small, 16dp) when `isLlmLoading && suggestions.isEmpty()`. Show a subdued "Could not reach LLM" `Text` when `llmError != null`.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/TagChipRow.kt`

---

#### Story 3.1.2: SuggestionBottomSheet — full-page suggestion sheet

**As a** user, **I want** a bottom sheet to appear when I request tag suggestions for a block, **so that** I can review and accept suggestions without navigating away.

**Acceptance Criteria**:
- Uses Compose Material3 `ModalBottomSheet`
- Header: "Suggested tags for this block" + dismiss X button
- Body: `TagChipRow` for the current `TagSuggestionState.Ready`
- Invisible when state is `Idle`; shows spinner when `Loading`; shows chips when `Ready`
- Accepts `onAcceptTag: (blockUuid: String, term: String) -> Unit` and `onDismiss: () -> Unit`

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/SuggestionBottomSheet.kt` (new)

##### Task 3.1.2a: Create SuggestionBottomSheet.kt (~5 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/SuggestionBottomSheet.kt`:

```kotlin
@Composable
fun SuggestionBottomSheet(
    state: TagSuggestionState,
    onAcceptTag: (blockUuid: String, term: String) -> Unit,
    onDismiss: () -> Unit,
)
```

Use `ModalBottomSheetLayout` (or `ModalBottomSheet` from Material3). Only show when `state is TagSuggestionState.Ready || state is TagSuggestionState.Loading`. Use `TagChipRow` in the body. Wire chip accept to `onAcceptTag(blockUuid, suggestion.term)`.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/SuggestionBottomSheet.kt`

Note on `llmError` in the UI: when `TagSuggestionState.Ready.llmError` is non-null, `SuggestionBottomSheet` must display a subtle error note (e.g., `Text("Could not reach LLM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)`) below the chip row. This is the only surface for the user to understand why AI suggestions are absent. Omitting this leaves users silently wondering why only local suggestions appear.

---

## Phase 3.5: WikiLinkExtractor utility (prerequisite for Phase 4)

##### Task 3.5a: Create WikiLinkExtractor.kt (~2 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/WikiLinkExtractor.kt`:

```kotlin
package dev.stapler.stelekit.tags

/** Extracts page names from Logseq wiki-link syntax `[[PageName]]` in block content. */
object WikiLinkExtractor {
    private val WIKI_LINK_REGEX = Regex("""\[\[(.+?)]]""")

    fun extractPageNames(content: String): Set<String> =
        WIKI_LINK_REGEX.findAll(content).map { it.groupValues[1] }.toSet()
}
```

Replace all inline `Regex("""\[\[(.+?)]]""")` usages in `PageView.kt` with `WikiLinkExtractor.extractPageNames(content)`.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/WikiLinkExtractor.kt`

---

## Phase 4: Entry Points

### Epic 4.1: Block-Scope Entry Point

**Goal**: Allow a user to request tag suggestions for a single selected block via the block context menu.

#### Story 4.1.1: Add onRequestTagSuggestions callback to BlockList

**As a** user, **I want** a "Suggest tags" option in the block context menu, **so that** I can trigger suggestions for the block I am currently editing.

**Acceptance Criteria**:
- `BlockList` gains an optional `onRequestTagSuggestions: ((blockUuid: String, content: String) -> Unit)?` parameter (default null)
- When non-null, shows a "Suggest tags" action in the block's long-press context menu or action toolbar (alongside existing indent/outdent/move actions)
- Callback is invoked with the block's UUID and current content string
- No changes to existing callbacks; backward compatible

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`

##### Task 4.1.1a: Add callback parameter to BlockList (~3 min)

In `BlockList.kt` function signature, add after line 92 (after `onOpenAnnotationEditor`):

```kotlin
onRequestTagSuggestions: ((blockUuid: String, content: String) -> Unit)? = null,
```

Locate the block long-press / context menu section in the block rendering loop. Add a "Suggest tags" `DropdownMenuItem` or toolbar icon that calls `onRequestTagSuggestions?.invoke(block.uuid.value, blockState.content)`. Show only when `onRequestTagSuggestions != null`.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt`

---

#### Story 4.1.2: Wire block-scope callback in PageView

**As a** developer, **I want** `PageView` to wire `onRequestTagSuggestions` and show `SuggestionBottomSheet`, **so that** block-level tag suggestions flow end-to-end.

**Acceptance Criteria**:
- `PageView` receives `tagSuggestionViewModel: TagSuggestionViewModel?` parameter (default null)
- `PageView` passes `onRequestTagSuggestions` lambda to `BlockList` that calls `tagSuggestionViewModel?.requestSuggestions(blockUuid, content, alreadyLinked)`
- `SuggestionBottomSheet` overlays `PageView`; `onAcceptTag` calls `blockStateManager.appendToBlock(blockUuid, " [[${term}]]")` — NOT `insertTextAtCursor`. Rationale: `insertTextAtCursor` is cursor-state-sensitive and inserts at `_editingCursorIndex` which is null or stale when the user is interacting with the bottom sheet (not typing in the block). `appendToBlock` always appends to `block.content.length`, the correct and safe insertion point for accepted suggestions. If `BlockStateManager.appendToBlock` does not yet exist, it must be added in a preparatory task before Phase 4 (see Task 4.0a below).
- Auto-applied tags (from local scan) are inserted using `blockStateManager.appendToBlock(blockUuid, " [[${term}]]")` without showing in the sheet
- `alreadyLinked` is derived from existing `[[...]]` links in the block content via a shared `WikiLinkExtractor.extractPageNames(content)` utility (see Phase 7 minor fix; create `tags/WikiLinkExtractor.kt`)

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` (add `appendToBlock` if missing)

##### Task 4.0a: Add appendToBlock to BlockStateManager if not present (~3 min)

`BlockStateManager.insertTextAtCursor(blockUuid, text, overrideCursorIndex)` already falls back to `block.content.length` when `overrideCursorIndex` is null and `_editingCursorIndex` is null. However, to make the intent explicit and avoid depending on that fallback, add a dedicated helper:

```kotlin
/**
 * Append [text] to the end of the block's current content.
 * Safe to call from a non-editing context (no active cursor required).
 * Internally delegates to insertTextAtCursor with overrideCursorIndex = block.content.length.
 */
fun appendToBlock(blockUuid: BlockUuid, text: String) {
    // Find the block across all pages in _blocks: Map<pageUuid, List<Block>>
    val block = _blocks.value.values.flatten().firstOrNull { it.uuid == blockUuid } ?: return
    insertTextAtCursor(blockUuid, text, overrideCursorIndex = block.content.length)
}
```

The implementation reads from `_blocks: StateFlow<Map<String, List<Block>>>` (the correct private field name) and delegates to the existing `insertTextAtCursor` with an explicit `overrideCursorIndex` to bypass cursor state.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

##### Task 4.1.2a: Add tagSuggestionViewModel param and wire SuggestionBottomSheet in PageView (~5 min)

Add `tagSuggestionViewModel: TagSuggestionViewModel? = null` to `PageView`'s parameter list.

Add a `val tagSuggestionState by tagSuggestionViewModel?.state?.collectAsState() ?: remember { mutableStateOf(TagSuggestionState.Idle) }`.

After the main `Box` content, overlay `SuggestionBottomSheet(state = tagSuggestionState, onAcceptTag = { uuid, term -> blockStateManager.appendToBlock(BlockUuid(uuid), " [[$term]]") }, onDismiss = { tagSuggestionViewModel?.dismiss() })`.

In `BlockList` call, add:
```kotlin
onRequestTagSuggestions = tagSuggestionViewModel?.let { vm ->
    { blockUuid, content ->
        val alreadyLinked = WikiLinkExtractor.extractPageNames(content)
        // Auto-apply high-confidence local matches by appending to block (cursor-safe)
        val localMatches = engine.directMatch(content).filter { it.autoApplied }
        localMatches.forEach { suggestion ->
            blockStateManager.appendToBlock(BlockUuid(blockUuid), " [[${suggestion.term}]]")
        }
        vm.requestSuggestions(blockUuid, content, alreadyLinked)
    }
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

---

### Epic 4.2: Page-Scope Entry Point

**Goal**: Add an overflow menu action to the page header that triggers tag suggestions for all untagged blocks on the page.

#### Story 4.2.1: "Suggest tags for page" action in EditorToolbar overflow menu

**As a** user, **I want** a menu item in the page editor overflow menu to suggest tags for all blocks on the page at once, **so that** I can tag an entire page without visiting each block individually.

**Acceptance Criteria**:
- Overflow menu in `PageView` gains "Suggest tags for page" item
- Tap iterates all blocks for the current page via `blockStateManager.blocks.value[page.uuid.value]`, calls `engine.directMatch()` per block, auto-applies `autoApplied = true` matches immediately via `blockStateManager.appendToBlock()` (NOT `insertTextAtCursor` — cursor state is not active during this operation)
- **LLM fan-out cap**: the page-scope action issues AT MOST ONE LLM API call total (not one per block). Specifically: after applying all local matches, the action aggregates the content of all blocks (up to 500 chars total after joining with newlines) into a single `llmSuggest` call. The `TagSuggestionViewModel.requestSuggestions` is called once with the concatenated content; the bottom sheet shows the aggregate LLM suggestions. If the page has >20 blocks, only the first 20 are included in the aggregate to bound the prompt size.
- Uses the existing overflow menu pattern in `PageView.kt` (the `DropdownMenu` already present in the page header area)
- Entry point is in `PageView.kt` — not a new file

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

##### Task 4.2.1a: Add overflow menu item and page-scope logic in PageView (~5 min)

Locate the existing overflow `DropdownMenu` in `PageView.kt`. Add:

```kotlin
private const val PAGE_SCOPE_MAX_BLOCKS = 20
private const val PAGE_SCOPE_MAX_CHARS = 500

DropdownMenuItem(
    text = { Text("Suggest tags for page") },
    onClick = {
        overflowExpanded = false
        tagSuggestionViewModel?.let { vm ->
            // blockStateManager.blocks is StateFlow<Map<pageUuid, List<Block>>>
            // Get blocks for this page by looking up with page.uuid.value
            val pageBlocks: List<Block> = blockStateManager.blocks.value[page.uuid.value] ?: emptyList()
            // Tier 1: Auto-apply local matches for all blocks immediately (no LLM)
            pageBlocks.forEach { block ->
                val content = block.content  // Block.content is the source of truth
                if (content.isBlank()) return@forEach
                engine.directMatch(content)
                    .filter { it.autoApplied }
                    .forEach { suggestion ->
                        blockStateManager.appendToBlock(block.uuid, " [[${suggestion.term}]]")
                    }
            }
            // Tier 2: ONE aggregate LLM call for the whole page (bounded to first 20 blocks, 500 chars total)
            val aggregateContent = pageBlocks
                .take(PAGE_SCOPE_MAX_BLOCKS)
                .filter { it.content.isNotBlank() }
                .joinToString("\n") { it.content }
                .take(PAGE_SCOPE_MAX_CHARS)
            if (aggregateContent.isNotBlank()) {
                val alreadyLinked = WikiLinkExtractor.extractPageNames(aggregateContent)
                // Use pageUuid as the "blockUuid" for the aggregate request — bottom sheet
                // will show suggestions without a specific block target; user accepts to last-edited block
                vm.requestSuggestions(page.uuid.value, aggregateContent, alreadyLinked)
            }
        }
    }
)
```

Note: `block.content` is the direct field on the `Block` model — do NOT call a non-existent `blockStateManager.getContent()` method.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

---

### Epic 4.3: Voice Input Integration

**Goal**: After voice transcription and LLM formatting complete, automatically run tag suggestions on the inserted block.

#### Story 4.3.1: Extend VoiceCaptureState.Done with suggestedTags

**As a** developer, **I want** `VoiceCaptureState.Done` to carry `suggestedTags`, **so that** the voice pipeline can pass suggestions to the UI without a separate channel.

**Acceptance Criteria**:
- `VoiceCaptureState.Done` gains `suggestedTags: List<TagSuggestion> = emptyList()`
- Existing callers that pattern-match on `Done` are unaffected (field has default)

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureState.kt`

##### Task 4.3.1a: Add suggestedTags to VoiceCaptureState.Done (~2 min)

In `VoiceCaptureState.kt`, update `Done`:

```kotlin
data class Done(
    val insertedText: String,
    val isLikelyTruncated: Boolean = false,
    val transcriptPageTitle: String? = null,
    val savedToPageName: String? = null,
    val suggestedTags: List<dev.stapler.stelekit.tags.TagSuggestion> = emptyList(),
) : VoiceCaptureState
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureState.kt`

---

#### Story 4.3.2: Run tag suggestion engine after voice transcription in VoiceCaptureViewModel

**As a** user, **I want** tag suggestions to appear automatically after I finish a voice note, **so that** voice-captured notes are tagged without extra steps.

**Acceptance Criteria**:
- `VoiceCaptureViewModel` gains an optional `tagSuggestionEngine: TagSuggestionEngine?` constructor parameter (default null)
- After inserting the formatted transcript and transitioning to `Done`, if the engine is non-null, runs `engine.directMatch(insertedText)` and `engine.llmSuggest(insertedText)`
- Result is merged into `Done.suggestedTags`
- Auto-applied tags (local exact matches) are passed back as `suggestedTags` with `autoApplied = true` — the UI layer handles actually inserting them
- If engine is null (voice-only mode), `suggestedTags` stays empty — no behavior change

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

##### Task 4.3.2a: Inject TagSuggestionEngine into VoiceCaptureViewModel (~4 min)

Add `private val tagSuggestionEngine: TagSuggestionEngine? = null` to `VoiceCaptureViewModel` constructor.

In the `processTranscript()` (or equivalent) suspend function, after the pipeline produces `VoiceCaptureState.Done` with `insertedText`, add:

```kotlin
val suggestions = if (tagSuggestionEngine != null) {
    val local = tagSuggestionEngine.directMatch(insertedText)
    val llm = tagSuggestionEngine.llmSuggest(insertedText)
        .getOrNull() ?: emptyList()
    (local + llm).distinctBy { it.term.lowercase() }
} else emptyList()
_state.value = VoiceCaptureState.Done(
    insertedText = insertedText,
    isLikelyTruncated = ...,
    transcriptPageTitle = ...,
    savedToPageName = ...,
    suggestedTags = suggestions,
)
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

---

#### Story 4.3.3: VoiceCaptureButton — show tag chip row on Done with suggestedTags

**As a** user, **I want** to see tag suggestions inline after a voice capture completes, **so that** I can accept relevant tags without additional navigation.

**Acceptance Criteria**:
- After voice capture completes (`VoiceCaptureState.Done`), if `suggestedTags` is non-empty, show a `TagChipRow` below the "Done" confirmation
- Each chip: tap = call `blockStateManager.appendToBlock(savedBlockUuid, " [[${suggestion.term}]]")`; auto-applied tags are not shown
- The chip row disappears when the user taps anywhere outside or accepts/dismisses all chips
- No UI change when `suggestedTags` is empty (backward compatible with non-LLM mode)

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureButton.kt` (existing UI component — modify to show TagChipRow when state is Done with non-empty suggestedTags)

##### Task 4.3.3a: Add TagChipRow to VoiceCaptureButton Done state (~4 min)

Find the `Done` state rendering in `VoiceCaptureButton.kt`. Add:

```kotlin
is VoiceCaptureState.Done -> {
    // Existing Done UI...
    if (state.suggestedTags.isNotEmpty()) {
        TagChipRow(
            suggestions = state.suggestedTags.filter { !it.autoApplied },
            isLlmLoading = false,
            llmError = null,
            onAccept = { suggestion ->
                state.savedToPageName?.let { pageName ->
                    // Append tag to the block that was just saved
                    voiceCaptureViewModel.appendTagToLastBlock(" [[${suggestion.term}]]")
                }
            },
            onDismiss = { /* dismiss silently */ },
        )
    }
}
```

Add `fun appendTagToLastBlock(text: String)` to `VoiceCaptureViewModel` that calls `journalService.appendToToday(text)`. Note: the correct method name on `JournalService` is `appendToToday()` — NOT `appendToTodayBlock()` (which does not exist).

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureButton.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt`

---

## Phase 5: Settings UI

### Epic 5.1: Tag Suggestion Settings

**Goal**: Allow users to configure whether LLM tag suggestions are enabled and which provider to use, following the `VoiceCaptureSettings` pattern.

#### Story 5.1.1: TagSettings class with persisted keys

**As a** developer, **I want** a `TagSettings` class wrapping `Settings`, **so that** all tag-suggestion configuration is in one place with typed accessors.

**Acceptance Criteria**:
- `TagSettings(platformSettings: Settings)` in `tags/`
- Keys: `tags.suggest_enabled` (Boolean, default false), `tags.suggest_provider` (String, default "anthropic"), `tags.openai_base_url` (String, default "https://api.openai.com")
- Typed getters/setters for each key
- No new SQL table — uses existing `Settings` key-value store

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSettings.kt` (new)

##### Task 5.1.1a: Create TagSettings.kt (~3 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSettings.kt`:

```kotlin
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.platform.Settings

class TagSettings(private val settings: Settings) {

    fun getSuggestEnabled(): Boolean = settings.getBoolean(KEY_SUGGEST_ENABLED, false)
    fun setSuggestEnabled(enabled: Boolean) = settings.putBoolean(KEY_SUGGEST_ENABLED, enabled)

    fun getSuggestProvider(): String = settings.getString(KEY_SUGGEST_PROVIDER, "anthropic")
    fun setSuggestProvider(provider: String) = settings.putString(KEY_SUGGEST_PROVIDER, provider)

    fun getOpenAiBaseUrl(): String = settings.getString(KEY_OPENAI_BASE_URL, "https://api.openai.com")
    fun setOpenAiBaseUrl(url: String) = settings.putString(KEY_OPENAI_BASE_URL, url)

    companion object {
        const val KEY_SUGGEST_ENABLED = "tags.suggest_enabled"
        const val KEY_SUGGEST_PROVIDER = "tags.suggest_provider"
        const val KEY_OPENAI_BASE_URL = "tags.openai_base_url"
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENAI = "openai"
    }
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSettings.kt`

---

#### Story 5.1.2: TAG_SUGGESTIONS SettingsCategory and LlmProviderSettings composable

**As a** user, **I want** a "Tag Suggestions" settings panel, **so that** I can enable LLM tagging and pick my provider without editing config files.

**Acceptance Criteria**:
- `SettingsCategory` enum gains `TAG_SUGGESTIONS("Tag Suggestions", Icons.Default.Label)`
- `SettingsDialog` gains `tagSettings: TagSettings? = null` and `tagSettingsContent: (@Composable () -> Unit)?` parameters (follows `audiobookNotesSettingsContent` pattern)
- When `tagSettings` is null, `TAG_SUGGESTIONS` is filtered out (follows `GOOGLE_ACCOUNT` pattern)
- `LlmProviderSettings` composable renders: an enable/disable toggle, a provider selector (Anthropic / OpenAI-compatible), API key field (password masked), and for OpenAI a base URL field
- On "Save", calls `tagSettings.setSuggestEnabled(...)`, `tagSettings.setSuggestProvider(...)`, `tagSettings.setOpenAiBaseUrl(...)`
- Shows disclaimer: "API key is stored in plaintext on desktop. Full encryption coming soon." (JVM only, but acceptable to show on all platforms for MVP)
- Calls optional `onRebuildTagEngine: (() -> Unit)?` after save so `App.kt` can reconstruct the engine

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LlmProviderSettings.kt` (new)

##### Task 5.1.2a: Add TAG_SUGGESTIONS to SettingsCategory enum (~2 min)

In `SettingsDialog.kt` line 235, after `VAULT`:

```kotlin
TAG_SUGGESTIONS("Tag Suggestions", Icons.Default.Label),
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`

##### Task 5.1.2b: Add tagSettings param to SettingsDialog (~3 min)

In `SettingsDialog` composable parameter list, add after `audiobookNotesSettingsContent`:
```kotlin
tagSettings: dev.stapler.stelekit.tags.TagSettings? = null,
onRebuildTagEngine: (() -> Unit)? = null,
```

In the category filter (line 92 area), add:
```kotlin
SettingsCategory.TAG_SUGGESTIONS -> tagSettings != null
```

In the category content switch, add:
```kotlin
SettingsCategory.TAG_SUGGESTIONS -> if (tagSettings != null) {
    LlmProviderSettings(tagSettings = tagSettings, onRebuildEngine = onRebuildTagEngine)
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`

##### Task 5.1.2c: Create LlmProviderSettings.kt (~5 min)

Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LlmProviderSettings.kt`:

```kotlin
@Composable
fun LlmProviderSettings(
    tagSettings: TagSettings,
    onRebuildEngine: (() -> Unit)? = null,
)
```

Body:
- `var suggestEnabled by remember { mutableStateOf(tagSettings.getSuggestEnabled()) }`
- `var selectedProvider by remember { mutableStateOf(tagSettings.getSuggestProvider()) }`
- `var openAiBaseUrl by remember { mutableStateOf(tagSettings.getOpenAiBaseUrl()) }`
- `var saved by remember { mutableStateOf(false) }`
- Toggle switch for enable/disable
- Segmented button or radio row: "Anthropic (Claude)" | "OpenAI-compatible"
- Password text field for API key (reads from `voiceSettings` — API keys are already stored under `VoiceSettings` keys; the provider picker determines which key is used; no new key storage needed)
- Base URL field shown only when OpenAI-compatible selected
- "Save" button calls setters, `onRebuildEngine?.invoke()`
- Plaintext disclaimer `Text` at bottom of section

Note: API keys for Anthropic and OpenAI are already stored and managed by `VoiceSettings`. `LlmProviderSettings` reads the key from the appropriate `VoiceSettings` getter based on `selectedProvider` (injected as an additional parameter: `voiceSettings: VoiceSettings`). This avoids duplicating key storage.

Updated composable signature:
```kotlin
fun LlmProviderSettings(
    tagSettings: TagSettings,
    voiceSettings: VoiceSettings,       // for reading existing API keys
    onRebuildEngine: (() -> Unit)? = null,
)
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LlmProviderSettings.kt`

##### Task 5.1.2d: Pass voiceSettings to LlmProviderSettings in SettingsDialog (~2 min)

Update the `TAG_SUGGESTIONS` branch in `SettingsDialog.kt` to pass `voiceSettings` (already a parameter of `SettingsDialog`) to `LlmProviderSettings`:

```kotlin
SettingsCategory.TAG_SUGGESTIONS -> if (tagSettings != null && voiceSettings != null) {
    LlmProviderSettings(tagSettings = tagSettings, voiceSettings = voiceSettings, onRebuildEngine = onRebuildTagEngine)
}
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`

---

## Phase 6: App.kt Wiring and Android Share Widget

### Epic 6.1: App.kt TagSuggestionEngine and ViewModel wiring

**Goal**: Wire `TagSuggestionEngine` and `TagSuggestionViewModel` into `GraphContent` in `App.kt`, following the `voiceCaptureViewModel` / `VoicePipelineFactory` pattern.

#### Story 6.1.1: Build TagSuggestionEngine in GraphContent and pass to PageView

**As a** developer, **I want** `App.kt` to construct `TagSuggestionEngine` and `TagSuggestionViewModel` in `GraphContent`, **so that** both entry points (block and page scope) are wired without threading through multiple composable layers.

**Acceptance Criteria**:
- `TagSettings` is constructed once in `StelekitApp` and passed down as a parameter
- `buildTagEngine(tagSettings, voiceSettings, viewModel.pageNameIndex)` helper constructs `LlmTagProvider?` (null when settings disabled or no API key) and `TagSuggestionEngine`
- `TagSuggestionViewModel` is built via `remember(tagSuggestionEngine) { TagSuggestionViewModel(tagSuggestionEngine) }`
- `DisposableEffect` calls `tagSuggestionViewModel.close()` on disposal
- `tagSuggestionViewModel` passed to `PageView` and `JournalsView`
- `SettingsDialog` call gains `tagSettings = tagSettings` and `onRebuildTagEngine = { /* trigger recompose that re-keys the engine */ }` — use a `var tagEngineKey by remember { mutableStateOf(0) }` incremented on rebuild

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 6.1.1a: Add TagSettings construction in StelekitApp and pass to GraphContent (~3 min)

In `App.kt`, near where `VoiceSettings` is constructed (around line 184 / where `voiceSettings` is set up), add:

```kotlin
val tagSettings = remember { TagSettings(platformSettings) }
```

Pass `tagSettings` to `GraphContent` composable call. Add `tagSettings: TagSettings` to `GraphContent` parameter list.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 6.1.1b: Build TagSuggestionEngine and TagSuggestionViewModel in GraphContent (~5 min)

Inside `GraphContent`, after the `voiceCaptureViewModel` block (around line 975), add:

```kotlin
var tagEngineKey by remember { mutableStateOf(0) }
val tagSuggestionEngine = remember(tagEngineKey, viewModel.pageNameIndex) {
    val llmProvider: LlmFormatterProvider? = if (tagSettings.getSuggestEnabled()) {
        when (tagSettings.getSuggestProvider()) {
            TagSettings.PROVIDER_ANTHROPIC ->
                voiceSettings?.getAnthropicKey()?.let { ClaudeLlmFormatterProvider.withDefaults(it) }
            TagSettings.PROVIDER_OPENAI ->
                voiceSettings?.getOpenAiKey()?.let {
                    OpenAiLlmFormatterProvider.withDefaults(it, tagSettings.getOpenAiBaseUrl())
                }
            else -> null
        }
    } else null
    TagSuggestionEngine(
        pageNameIndex = viewModel.pageNameIndex,
        llmTagProvider = llmProvider?.let { LlmTagProvider(it) },
    )
}
val tagSuggestionViewModel = remember(tagSuggestionEngine) {
    TagSuggestionViewModel(tagSuggestionEngine)
}
DisposableEffect(tagSuggestionViewModel) {
    onDispose { tagSuggestionViewModel.close() }
}
```

Pass `tagSuggestionViewModel` to `PageView` and to `JournalsView` composable calls.

Also update `SettingsDialog` call to include:
```kotlin
tagSettings = tagSettings,
onRebuildTagEngine = { tagEngineKey++ },
```

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 6.1.1c: Add tagSuggestionViewModel param to JournalsView composable (~3 min)

`JournalsView.kt` hosts `BlockList` — add `tagSuggestionViewModel: TagSuggestionViewModel? = null` parameter and wire it to the `BlockList` `onRequestTagSuggestions` callback using the same pattern as `PageView`.

Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt`

---

### Epic 6.2: Android Share Widget

**Goal**: Receive `Intent.ACTION_SEND` in an Android Activity, save the note immediately to the today journal page (offline, synchronous), run the local tag scan, then dispatch LLM tagging via WorkManager.

#### Story 6.2.1: ShareTagActivity — receives share intent, saves note, triggers local scan

**As a** user, **I want** to share text to SteleKit from any Android app and have it saved with immediate local tag suggestions, **so that** quick capture is instant with no required network call.

**Acceptance Criteria**:
- `ShareTagActivity` in `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/ShareTagActivity.kt`
- Handles `android.intent.action.SEND` with `text/plain` MIME type
- Reads shared text from `intent.getStringExtra(Intent.EXTRA_TEXT)`
- Opens the database via `DriverFactory` and `RepositoryFactory` (follows `AndroidApplication` pattern) using `applicationContext`
- Saves text as a new block on the today journal page via `JournalService`
- Runs `TagSuggestionEngine.directMatch()` (local, synchronous) on the saved block content; auto-applied matches are inserted immediately via `DatabaseWriteActor`
- Dispatches `TagSuggestionWorker` (see next story) as a `OneTimeWorkRequest` if `TagSettings.getSuggestEnabled()` and an API key is configured
- Shows a transient `Toast` "Note saved" and calls `finish()`
- Does NOT show any Compose UI for the LLM result — the WorkManager worker posts a notification on completion
- Declared in `AndroidManifest.xml` with intent filter

**Files**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/ShareTagActivity.kt` (new)
- `kmp/src/androidMain/AndroidManifest.xml`

##### Task 6.2.1a: Create ShareTagActivity.kt (~5 min)

Create `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/ShareTagActivity.kt`.

The Activity must:
1. Call `SteleKitApplication.instance.repositorySet` (or construct via `DriverFactory` + `RepositoryFactory`)
2. Get shared text from `intent`
3. Call `journalService.insertBlock(text)` (or equivalent `blockRepository.saveBlock`)
4. Construct `TagSuggestionEngine(pageNameIndex = PageNameIndex(pageRepository, lifecycleScope), llmTagProvider = null)` — LLM-only path uses WorkManager
5. Apply `directMatch()` results via `DatabaseWriteActor`
6. Enqueue `TagSuggestionWorker` if enabled
7. Show `Toast.makeText(..., "Note saved", Toast.LENGTH_SHORT).show(); finish()`

Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/ShareTagActivity.kt`

##### Task 6.2.1b: Register ShareTagActivity in AndroidManifest.xml (~2 min)

Add inside `<application>` in `kmp/src/androidMain/AndroidManifest.xml`:

```xml
<activity
    android:name="dev.stapler.stelekit.ui.ShareTagActivity"
    android:exported="true"
    android:theme="@style/Theme.TranslucentNoDisplay">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

Files: `kmp/src/androidMain/AndroidManifest.xml`

---

#### Story 6.2.2: TagSuggestionWorker — WorkManager worker for share widget LLM

**As a** developer, **I want** `TagSuggestionWorker` to perform the LLM tag call in the background after share capture, **so that** LLM suggestions survive process death.

**Acceptance Criteria**:
- `TagSuggestionWorker` is a `CoroutineWorker` in `kmp/src/androidMain`
- Input data: `blockUuid: String`, `blockContent: String`, `graphId: String` (the active graph identifier — required for `DriverFactory.getDatabaseUrl(graphId)`)
- `doWork()` initialization sequence (mandatory, mirrors `GitSyncWorker`):
  1. Call `DriverFactory.setContext(applicationContext)` before any DB access
  2. Resolve graph URL: `DriverFactory.getDatabaseUrl(graphId)` — fail fast if graphId not found
  3. Create driver via `DriverFactory(applicationContext).createDriver(dbUrl)` 
  4. Call `MigrationRunner.applyAll(driver)` **before** any queries — this is the mandatory migration guard per `CLAUDE.md`. Failure here is non-fatal for the LLM call but must be logged.
  5. Open `SteleDatabase(driver)` and `RestrictedDatabaseQueries`
- Reads `TagSettings` and `VoiceSettings` from `PlatformSettings` (requires `applicationContext`) to build `LlmTagProvider`
- Calls `LlmTagProvider.suggestTags()` (LLM only — no PageNameIndex in this context)
- On success with non-empty results: appends `" [[term]]"` per suggestion to the block's content via `DatabaseWriteActor.execute { queries.appendToBlock(blockUuid, text) }`, posts an Android notification "Tags added to your note"
- On failure or empty results: returns `Result.success()` with a Logcat warning log (intentional silent failure — LLM tag suggestion is non-critical; user note was already saved successfully)
- Constraints: `NetworkType.CONNECTED`

**Files**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionWorker.kt` (new)

##### Task 6.2.2a: Create TagSuggestionWorker.kt (~5 min)

Create `kmp/src/androidMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionWorker.kt` as a `class TagSuggestionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params)`.

In `doWork()`:
1. Extract `blockUuid`, `blockContent`, and `graphId` from `inputData`
2. Call `DriverFactory.setContext(applicationContext)` then `DriverFactory(applicationContext).createDriver(DriverFactory.getDatabaseUrl(graphId))` — if graphId is unknown, log warning and return `Result.success()`
3. Call `MigrationRunner.applyAll(driver)` before opening queries
4. Open `SteleDatabase(driver)` to get write access
5. Build `LlmTagProvider` from `PlatformSettings(applicationContext)` → `TagSettings` + `VoiceSettings`
6. Call `suggestTags(TagSuggestionRequest(blockUuid, blockContent, emptyList()))` — vocabulary is empty for share widget MVP (LLM-only, unconstrained)
7. On `Either.Right` with results: append `" [[term]]"` per accepted tag to block content in DB
8. Post notification via `NotificationManagerCompat` on success with results
9. On any failure: log to Logcat (`Log.w("TagSuggestionWorker", "LLM tagging failed: ${err.message}")`), return `Result.success()` (intentional — silent non-critical failure)

Update `ShareTagActivity.kt` (Task 6.2.1a) to include `graphId` in the `WorkManager` input data when enqueuing `TagSuggestionWorker`.

Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionWorker.kt`

---

## Phase 7: ProGuard Rules and Tests

### Epic 7.1: ProGuard Rules

**Goal**: Ensure release APKs do not strip Ktor engine, kotlinx.serialization, or Arrow classes introduced by this feature.

#### Story 7.1.1: Create proguard-rules.pro

**As a** developer, **I want** `kmp/src/androidMain/proguard-rules.pro` to exist with the required keep rules, **so that** release builds do not crash at Ktor engine discovery or serialization time.

**Acceptance Criteria**:
- Keep rules for Ktor OkHttp engine ServiceLoader
- Keep rules for `kotlinx.serialization` (companion objects + serializer methods)
- Keep rules for Arrow `CircuitBreaker`, `Either`
- Keep rules for `dev.stapler.stelekit.tags.**` (new package)
- File is referenced from `kmp/build.gradle.kts` in the `release` build type

**Files**:
- `kmp/src/androidMain/proguard-rules.pro` (new)
- `kmp/build.gradle.kts`

##### Task 7.1.1a: Create proguard-rules.pro (~4 min)

Create `kmp/src/androidMain/proguard-rules.pro`:

```
# Ktor OkHttp engine — discovered via ServiceLoader at runtime
-keep class io.ktor.client.engine.okhttp.** { *; }
-keepnames class io.ktor.** { *; }

# kotlinx.serialization — companion objects and KSerializer implementations
-keepclassmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * extends kotlinx.serialization.KSerializer { *; }

# Arrow resilience
-keep class arrow.resilience.** { *; }
-keep class arrow.core.** { *; }

# SteleKit tag suggestion models (serializable for JSON response parsing)
-keep class dev.stapler.stelekit.tags.** { *; }

# Kotlin reflect used by serialization
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**
```

Files: `kmp/src/androidMain/proguard-rules.pro`

##### Task 7.1.1b: Reference proguard-rules.pro in build.gradle.kts (~2 min)

In `kmp/build.gradle.kts`, inside `android { buildTypes { getByName("release") { ... } } }`, add:

```kotlin
proguardFiles(
    getDefaultProguardFile("proguard-android-optimize.txt"),
    "src/androidMain/proguard-rules.pro"
)
```

Files: `kmp/build.gradle.kts`

---

### Epic 7.2: Unit Tests

**Goal**: Verify the core engine logic — direct match, LLM parsing, deduplication, auto-apply threshold, vocabulary filter — before wiring UI.

#### Story 7.2.1: TagSuggestionEngine unit tests

**As a** developer, **I want** `TagSuggestionEngineTest` in `businessTest`, **so that** regressions in the two-tier pipeline are caught at CI time.

**Acceptance Criteria**:
- `directMatch`: exact page name in block text → `autoApplied = true`, confidence 1.0
- `directMatch`: no match → empty list
- `llmSuggest`: null provider → empty `Either.Right`
- Deduplication: same term from local and LLM → local wins (appears once)
- `AUTO_APPLY_THRESHOLD` constant is 0.95f

**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngineTest.kt` (new)

##### Task 7.2.1a: Create TagSuggestionEngineTest.kt (~5 min)

Create test class in `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngineTest.kt`.

Test cases:
1. `directMatch returns empty when matcher is null` — use `InMemoryPageRepository` with empty graph
2. `directMatch returns auto-applied suggestion for exact page name in block text` — add "Kotlin" page, block text "I love Kotlin", expect `TagSuggestion(term="Kotlin", confidence=1.0f, autoApplied=true)`
3. `llmSuggest returns empty Right when engine has no LLM provider`
4. `deduplication: local match wins when LLM returns same term`

Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngineTest.kt`

---

#### Story 7.2.2: LlmTagProvider unit tests

**As a** developer, **I want** `LlmTagProviderTest` in `jvmTest`, **so that** prompt construction, response parsing, and vocabulary filtering are verified.

**Acceptance Criteria**:
- `suggestTags`: LLM returns "Kotlin\nRust", both in vocabulary → parsed as `LLM` source with descending confidence
- `suggestTags`: LLM returns term not in vocabulary → filtered out (constrained vocabulary enforced)
- `suggestTags`: provider returns `LlmResult.Failure.NetworkError` → `Either.Left(DomainError.NetworkError.*)`
- `tokenOverlapFilter`: block "love Kotlin" with vocabulary ["Kotlin", "Haskell"] → keeps only "Kotlin"
- Uses `MockEngine` from Ktor for `ClaudeLlmFormatterProvider` integration path

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/LlmTagProviderTest.kt` (new)

##### Task 7.2.2a: Create LlmTagProviderTest.kt (~5 min)

Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/LlmTagProviderTest.kt`.

Use a fake `LlmFormatterProvider` lambda for most cases. For the `MockEngine` integration test, construct a `ClaudeLlmFormatterProvider(MockEngine { respond("...", ContentType.Application.Json) }, "test-key")` and test end-to-end parsing.

Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/tags/LlmTagProviderTest.kt`

---

#### Story 7.2.3: TagSuggestionViewModel unit tests

**As a** developer, **I want** `TagSuggestionViewModelTest` in `businessTest`, **so that** state machine transitions (Idle → Loading → Ready, cancellation races, LLM error path) are verified.

**Acceptance Criteria**:
- `requestSuggestions`: state transitions Idle → Loading → Ready(localSuggestions=[...]) → Ready(localSuggestions=[...], llmSuggestions=[...])
- `requestSuggestions` called twice: second call cancels in-flight first call
- LLM failure → Ready(localSuggestions=[...], llmError="...") — NOT Error state
- `dismiss()` → Idle
- CoroutineExceptionHandler catches uncaught exceptions → Error state
- Uses `TestCoroutineScope` or `runTest` per existing ViewModel test patterns

**Files**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt` (new)

##### Task 7.2.3a: Create TagSuggestionViewModelTest.kt (~5 min)

Create `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt`.

Use a fake `TagSuggestionEngine` that returns controlled results (stub `PageNameIndex` with `InMemoryPageRepository`). Follow `VoiceCaptureViewModelTest` pattern for `runTest` and `TestScope`.

Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt`

---

## Implementation Order Summary

Implement phases sequentially within each phase; tasks within a story can be parallelized when they touch different files.

| Phase | Stories | New Files | Modified Files |
|---|---|---|---|
| 1 | 1.1.1–1.1.3 | `tags/TagSuggestion.kt`, `tags/TagSuggestionRequest.kt`, `tags/LlmTagProvider.kt`, `tags/TagSuggestionEngine.kt` | — |
| 2 | 2.1.1–2.1.2 | `tags/TagSuggestionState.kt`, `tags/TagSuggestionViewModel.kt` | `domain/PageNameIndex.kt`, `ui/StelekitViewModel.kt` |
| 3 | 3.1.1–3.1.2 | `ui/components/tags/TagChipRow.kt`, `ui/components/tags/SuggestionBottomSheet.kt` | — |
| 4 | 4.1.1–4.3.2 | — | `ui/components/BlockList.kt`, `ui/screens/PageView.kt`, `voice/VoiceCaptureState.kt`, `voice/VoiceCaptureViewModel.kt`, `ui/screens/JournalsView.kt` |
| 5 | 5.1.1–5.1.2 | `tags/TagSettings.kt`, `ui/components/settings/LlmProviderSettings.kt` | `ui/components/settings/SettingsDialog.kt` |
| 6 | 6.1.1–6.2.2 | `androidMain/ui/ShareTagActivity.kt`, `androidMain/tags/TagSuggestionWorker.kt` | `ui/App.kt`, `androidMain/AndroidManifest.xml` |
| 7 | 7.1.1–7.2.2 | `proguard-rules.pro`, `businessTest/tags/TagSuggestionEngineTest.kt`, `jvmTest/tags/LlmTagProviderTest.kt` | `kmp/build.gradle.kts` |

**Total**: 3 phases with blocking dependencies, 7 epics, 15 stories, 28 tasks.

---

## Key Design Decisions Summary

1. **Tag write format**: `" [[PageName]]"` — wiki-link syntax, not `#hashtag`. Written via `blockStateManager.appendToBlock(blockUuid, " [[${term}]]")` (cursor-safe; appends at `block.content.length` via `insertTextAtCursor` with explicit `overrideCursorIndex`). NOT `insertTextAtCursor` with ambient cursor state.

2. **Page-scope trigger location**: Overflow `DropdownMenu` inside `PageView.kt`. No changes to `EditorToolbar.kt`.

3. **PageNameIndex exposure**: `private val pageNameIndex` changed to `val pageNameIndex` on `StelekitViewModel`. A `vocabularyNames()` method is added to `PageNameIndex` for vocabulary access.

4. **ProGuard**: `kmp/src/androidMain/proguard-rules.pro` is a hard requirement. Reference added to `kmp/build.gradle.kts` release build type.

5. **Auto-apply threshold**: `TagSuggestionEngine.AUTO_APPLY_THRESHOLD = 0.95f`. AhoCorasick exact hits always produce `confidence = 1.0f` and `autoApplied = true`. The `SuggestionBottomSheet` does not display auto-applied suggestions.

6. **OpenAI-compatible provider**: `LlmTagProvider` accepts any `LlmFormatterProvider`. `App.kt` selects `ClaudeLlmFormatterProvider.withDefaults(anthropicKey)` or `OpenAiLlmFormatterProvider.withDefaults(openAiKey, baseUrl)` based on `TagSettings.getSuggestProvider()`.

7. **Share widget LLM scope**: `ShareTagActivity` runs local scan synchronously; LLM call dispatched via `TagSuggestionWorker` (WorkManager). No `PageNameIndex` dependency in the worker.

8. **ClaudeTopicEnricher 429 anti-pattern**: NOT copied. `LlmTagProvider` inherits `CircuitBreaker` via the injected `LlmFormatterProvider`. No manual `delay(2000)` retry.
