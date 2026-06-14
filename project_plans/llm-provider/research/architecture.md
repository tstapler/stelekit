# LLM Tag Suggestion Engine — Architecture Research

## Codebase Foundation

### Existing LLM Provider Pattern

The project already has a mature LLM provider abstraction in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/`:

```kotlin
// LlmFormatterProvider.kt
fun interface LlmFormatterProvider {
    suspend fun format(transcript: String, systemPrompt: String): LlmResult
}

sealed interface LlmResult {
    data class Success(val formattedText: String, val isLikelyTruncated: Boolean = false) : LlmResult
    sealed interface Failure : LlmResult {
        data class ApiError(val code: Int, val message: String) : Failure
        data object NetworkError : Failure
    }
}
```

Concrete implementations exist for:
- `ClaudeLlmFormatterProvider` — Ktor HTTP client + Arrow circuit breaker against `api.anthropic.com`
- `OpenAiLlmFormatterProvider` — same shape for OpenAI
- `MlKitLlmFormatterProvider` (androidMain) — on-device Gemini Nano via ML Kit Prompt API
- `NoOpLlmFormatterProvider` — passthrough for when LLM is disabled

Settings are persisted via the platform-agnostic `Settings` interface (key-value store) in `VoiceSettings`, which already holds keys for Anthropic, OpenAI, and Whisper API credentials.

### Tag Suggestion Parallel in the Codebase

`TagEditorPanel` (`ui/annotate/TagEditorPanel.kt`) shows the closest UI precedent: it accepts `existingTagSuggestions: List<String>` and renders an autocomplete dropdown from a filtered in-memory list. The list comes from the annotation repository — purely local, no LLM. This is the direct-match tier we need to extend.

---

## Recommended Architecture

### Layer Diagram

```
[Trigger Layer]               [Engine Layer]              [UI Layer]
BlockContextMenu  ──┐
ShareIntentActivity ├──► TagSuggestionEngine ──► TagSuggestionViewModel ──► SuggestionBottomSheet
VoiceCompletionHook ┘         │                                               InlineChipRow
                         ┌────┴─────────────────┐
                    DirectMatchTier         LlmSuggestionTier
                  (PageNameIndex)         (LlmFormatterProvider)
                                                │
                                         LlmProviderRegistry
                                         (pluggable config)
```

### New Packages

All new code lives in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`:

| Package | Contents |
|---|---|
| `tags/` | `TagSuggestionEngine`, `TagSuggestionRequest`, `TagSuggestion`, `LlmTagProvider` interface |
| `tags/providers/` | `LocalTagProvider`, `LlmBackedTagProvider`, `NoOpTagProvider` |
| `ui/components/tags/` | `SuggestionBottomSheet`, `TagChipRow`, `TagSuggestionViewModel` |
| `ui/components/settings/` | `LlmProviderSettings` composable (new tab in existing `SettingsDialog`) |

---

## LLM Provider Interface Design

The tag suggestion feature should **reuse `LlmFormatterProvider` directly** rather than defining a parallel interface. The existing interface is a Kotlin SAM (`fun interface`) that maps `(transcript, systemPrompt) -> LlmResult`. For tag suggestion, `transcript` carries the block content (or page context) and `systemPrompt` carries the tag extraction instruction.

Define a thin adapter layer:

```kotlin
// tags/LlmTagProvider.kt
package dev.stapler.stelekit.tags

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult

/**
 * Tag extraction prompt — instructs the LLM to return a newline-delimited tag list.
 * No preamble, no JSON — raw tags only.
 */
const val TAG_SUGGESTION_SYSTEM_PROMPT = """
You are a Logseq tagging assistant. Given the content of a note block and the existing tags in this
graph, return a newline-delimited list of #tag suggestions that are relevant to the content.

Rules:
- Suggest at most 5 tags
- Output one tag per line prefixed with '#' (e.g. "#meeting")
- Tags must be lower-case alphanumeric with hyphens (Logseq tag format)
- Prefer tags already in the graph (listed below) over new ones
- Do NOT invent unrelated topics
- Output only tags, no explanation

Existing graph tags:
{{EXISTING_TAGS}}

Block content:
{{BLOCK_CONTENT}}
"""

/**
 * Adapts [LlmFormatterProvider] for tag extraction. Returns an [Either] to integrate
 * with the Arrow error-handling conventions used throughout the repository layer.
 */
class LlmTagProvider(private val llm: LlmFormatterProvider) {
    suspend fun suggestTags(
        blockContent: String,
        existingTags: List<String>,
    ): Either<DomainError, List<String>> {
        val prompt = TAG_SUGGESTION_SYSTEM_PROMPT
            .replace("{{EXISTING_TAGS}}", existingTags.joinToString("\n") { "#$it" })
            .replace("{{BLOCK_CONTENT}}", blockContent.take(2000))
        return when (val result = llm.format(blockContent, prompt)) {
            is LlmResult.Success -> Either.Right(
                result.formattedText
                    .lines()
                    .map { it.trim().removePrefix("#") }
                    .filter { it.isNotBlank() && it.matches(Regex("[a-z0-9][a-z0-9-]*")) }
                    .take(5)
            )
            is LlmResult.Failure.ApiError ->
                Either.Left(DomainError.NetworkError.RequestFailed(result.message))
            is LlmResult.Failure.NetworkError ->
                Either.Left(DomainError.NetworkError.RequestFailed("LLM network error"))
        }
    }
}
```

---

## Two-Tier Engine

```kotlin
// tags/TagSuggestionEngine.kt
package dev.stapler.stelekit.tags

import arrow.core.Either
import dev.stapler.stelekit.domain.PageNameIndex
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

data class TagSuggestion(
    val tag: String,
    val source: SuggestionSource,
    val confidence: Float = 1f,
)

enum class SuggestionSource { LOCAL_MATCH, LLM }

data class TagSuggestionRequest(
    val blockContent: String,
    val pageContext: String = "",
    val existingTags: Set<String> = emptySet(),
)

class TagSuggestionEngine(
    private val pageNameIndex: PageNameIndex,
    private val llmTagProvider: LlmTagProvider?,  // null = LLM disabled
    private val maxLocalResults: Int = 10,
    private val llmTimeoutSeconds: Long = 8,
) {
    /**
     * Tier 1: Fast local direct-match against the PageNameIndex.
     * Extracts candidate words/phrases from [request.blockContent] and intersects
     * with all known page/tag names in the graph index. O(1) per word via Aho-Corasick.
     */
    fun directMatch(request: TagSuggestionRequest): List<TagSuggestion> {
        val words = tokenizeForTagging(request.blockContent)
        return words
            .flatMap { word ->
                pageNameIndex.findMatches(word)
                    .filter { it.startsWith("#") || it.all { c -> c.isLetterOrDigit() || c == '-' } }
                    .map { TagSuggestion(it.removePrefix("#"), SuggestionSource.LOCAL_MATCH) }
            }
            .distinctBy { it.tag }
            .filter { it.tag !in request.existingTags }
            .take(maxLocalResults)
    }

    /**
     * Tier 2: LLM-assisted suggestions. Returns [Either.Left] if LLM is disabled,
     * not configured, or times out (UI should gracefully degrade to Tier 1 only).
     */
    suspend fun llmSuggest(request: TagSuggestionRequest): Either<DomainError, List<TagSuggestion>> {
        val provider = llmTagProvider
            ?: return Either.Left(DomainError.NetworkError.RequestFailed("LLM not configured"))
        val allKnownTags = pageNameIndex.allTagNames().take(200)  // cap prompt size
        return try {
            withTimeout(llmTimeoutSeconds.seconds) {
                provider.suggestTags(
                    blockContent = request.blockContent,
                    existingTags = allKnownTags,
                ).map { tags ->
                    tags.map { TagSuggestion(it, SuggestionSource.LLM) }
                        .filter { it.tag !in request.existingTags }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Either.Left(DomainError.NetworkError.Timeout("LLM tag suggestion timed out"))
        }
    }

    private fun tokenizeForTagging(content: String): List<String> =
        content.split(Regex("\\s+|[#\\[\\](),!?;.\"']"))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
}
```

The `PageNameIndex` already exists at `domain/PageNameIndex.kt` and is backed by the Aho-Corasick matcher. It needs one new method `allTagNames(): List<String>` that returns names that are tag-shaped (no spaces, lower-case).

---

## ViewModel

```kotlin
// ui/components/tags/TagSuggestionViewModel.kt
package dev.stapler.stelekit.ui.components.tags

import arrow.core.Either
import dev.stapler.stelekit.tags.TagSuggestion
import dev.stapler.stelekit.tags.TagSuggestionEngine
import dev.stapler.stelekit.tags.TagSuggestionRequest
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TagSuggestionState {
    data object Idle : TagSuggestionState
    data object Loading : TagSuggestionState
    data class Ready(
        val localSuggestions: List<TagSuggestion>,
        val llmSuggestions: List<TagSuggestion>,
        val llmError: String? = null,
    ) : TagSuggestionState
    data class Error(val message: String) : TagSuggestionState
}

class TagSuggestionViewModel(
    private val engine: TagSuggestionEngine,
    // Must NOT be rememberCoroutineScope() — owns lifecycle per CLAUDE.md rules
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e ->
            if (e !is kotlinx.coroutines.CancellationException) {
                _state.update { TagSuggestionState.Error(e.message ?: "Unknown error") }
            }
        }
    ),
) {
    private val _state = MutableStateFlow<TagSuggestionState>(TagSuggestionState.Idle)
    val state: StateFlow<TagSuggestionState> = _state.asStateFlow()

    /**
     * Trigger suggestions for the given block content.
     * Tier 1 (local) fires immediately; Tier 2 (LLM) updates the state async.
     */
    fun requestSuggestions(request: TagSuggestionRequest) {
        _state.value = TagSuggestionState.Loading
        scope.launch {
            // Tier 1 — synchronous, always available
            val local = engine.directMatch(request)
            _state.value = TagSuggestionState.Ready(localSuggestions = local, llmSuggestions = emptyList())

            // Tier 2 — async, may fail
            val llmResult = engine.llmSuggest(request)
            _state.update { current ->
                val ready = current as? TagSuggestionState.Ready ?: return@update current
                when (llmResult) {
                    is Either.Right -> ready.copy(llmSuggestions = llmResult.value)
                    is Either.Left  -> ready.copy(llmError = llmResult.value.message)
                }
            }
        }
    }

    fun dismiss() {
        _state.value = TagSuggestionState.Idle
    }

    fun close() { scope.cancel() }
}
```

---

## State Flow: Trigger → Engine → UI

### Block Context Menu Entry Point

`BlockList.kt` already receives long-press / drag events per block. Add an `onRequestTagSuggestions: (blockUuid: String, content: String) -> Unit` callback parameter alongside the existing callbacks. The parent screen (`PageView.kt`) wires this to `tagSuggestionViewModel.requestSuggestions(...)` which is created in `GraphContent` alongside `voiceCaptureViewModel`.

Flow:
```
user long-presses block
→ BlockList.onRequestTagSuggestions(blockUuid, content)
→ PageView forwards to TagSuggestionViewModel.requestSuggestions()
→ state updates: Loading → Ready(local=[...]) → Ready(local=[...], llm=[...])
→ SuggestionBottomSheet observes state via collectAsState()
→ user taps chip → BlockStateManager.insertTextAtCursor(blockUuid, "#tag")
```

### Android Share Widget Entry Point

The share widget (no existing code — new `androidMain` Activity) receives `Intent.ACTION_SEND` text, extracts content, and opens a minimal compose UI showing tag suggestions. It calls `TagSuggestionEngine` directly (no `StelekitViewModel` dependency — share widget is lightweight). It uses the same `VoiceSettings`-backed `LlmFormatterProvider` construction that `VoicePipelineFactory.kt` already demonstrates.

Flow:
```
user selects text in external app → shares to SteleKit
→ ShareTagActivity (new, androidMain) receives Intent.ACTION_SEND
→ extracts text → calls TagSuggestionEngine (no full graph needed)
→ displays SuggestionBottomSheet with chips
→ user taps chip → intent back to JournalService.appendToToday("- #tag [[shared content]]")
```

### Voice Input Completion Entry Point

`VoiceCaptureViewModel.processTranscript()` already calls `pipeline.llmProvider.format(rawTranscript, prompt)`. After that call succeeds, add a second async call to `TagSuggestionEngine.llmSuggest(TagSuggestionRequest(formattedText))`. The result is stored in `VoiceCaptureState.Done` (extend the data class):

```kotlin
data class Done(
    val insertedText: String,
    val isLikelyTruncated: Boolean,
    val transcriptPageTitle: String?,
    val savedToPageName: String?,
    val suggestedTags: List<TagSuggestion> = emptyList(),  // new field
) : VoiceCaptureState
```

The `VoiceCaptureButton` composable shows a persistent chip row when state is `Done` with non-empty `suggestedTags`. Tapping a chip appends the tag to the voice note block via `JournalService`.

---

## Settings / Configuration

### Provider Config Repository

Extend `VoiceSettings` (which already persists Anthropic/OpenAI keys via the platform `Settings` interface) with tag-suggestion-specific keys:

```kotlin
// In VoiceSettings.kt (extend existing class)
fun getTagSuggestionEnabled(): Boolean =
    platformSettings.getBoolean(KEY_TAG_SUGGEST_ENABLED, false)

fun setTagSuggestionEnabled(enabled: Boolean) =
    platformSettings.putBoolean(KEY_TAG_SUGGEST_ENABLED, enabled)

fun getTagSuggestionProvider(): String =
    platformSettings.getString(KEY_TAG_SUGGEST_PROVIDER, PROVIDER_LOCAL_ONLY)

fun setTagSuggestionProvider(provider: String) =
    platformSettings.putString(KEY_TAG_SUGGEST_PROVIDER, provider)

companion object {
    const val PROVIDER_LOCAL_ONLY = "local"
    const val PROVIDER_ANTHROPIC  = "anthropic"
    const val PROVIDER_OPENAI     = "openai"
    const val PROVIDER_DEVICE_LLM = "device"
    private const val KEY_TAG_SUGGEST_ENABLED  = "tags.suggest_enabled"
    private const val KEY_TAG_SUGGEST_PROVIDER = "tags.suggest_provider"
}
```

No new SQLite table or migration is needed. The `Settings` interface is already key-value; there is no repository boundary here. The API key is shared with voice settings (Anthropic key is already persisted at `voice.anthropic_key`).

### Settings UI

Add a new `LlmProviderSettings` composable at `ui/components/settings/LlmProviderSettings.kt`, following the exact pattern of `VoiceCaptureSettings.kt`:
- `SettingsSection("Tag Suggestions")` — toggle on/off
- Radio group for provider selection (Local only / Anthropic / OpenAI / On-device)
- API key field shows only when the matching provider is selected (keys reuse the existing `VoiceSettings` fields — no duplication)
- "Test connection" button launches a `scope.launch` that calls a no-content `LlmTagProvider.suggestTags("test", emptyList())` and shows a snackbar

Wire into `SettingsDialog.kt`: add `SettingsCategory.LLM_PROVIDER` to the enum and render `LlmProviderSettings` in the content pane. Thread `voiceSettings` down (it already flows in through `GraphDialogLayer → SettingsDialog`).

---

## Where Tag Suggestion Engine Fits in ViewModel / Repository Architecture

### Construction in GraphContent

Follow the `voiceCaptureViewModel` / `VoicePipelineFactory` pattern:

```kotlin
// In GraphContent (App.kt)
val tagSuggestionEngine = remember(voiceSettings) {
    val llmProvider: LlmFormatterProvider? = when {
        voiceSettings?.getTagSuggestionEnabled() != true -> null
        voiceSettings.getTagSuggestionProvider() == VoiceSettings.PROVIDER_ANTHROPIC ->
            voiceSettings.getAnthropicKey()?.let { ClaudeLlmFormatterProvider.withDefaults(it) }
        voiceSettings.getTagSuggestionProvider() == VoiceSettings.PROVIDER_OPENAI ->
            voiceSettings.getOpenAiKey()?.let { OpenAiLlmFormatterProvider.withDefaults(it) }
        voiceSettings.getTagSuggestionProvider() == VoiceSettings.PROVIDER_DEVICE_LLM ->
            if (deviceLlmAvailable) MlKitLlmFormatterProvider.create() else null
        else -> null
    }
    TagSuggestionEngine(
        pageNameIndex = /* expose from StelekitViewModel or pass from repos */,
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

The `PageNameIndex` is owned by `StelekitViewModel`. Expose it via a read-only getter or pass it as a constructor parameter. It does not need a new repository — it is already populated from `PageRepository.getPageNameEntries()`.

### Dependency on Existing Repositories

The engine has no direct SQL dependency. It reads from `PageNameIndex` (in-memory, maintained by `StelekitViewModel`) for Tier 1 and calls the LLM HTTP client for Tier 2. Writes (inserting the chosen tag into a block) go through the existing `BlockStateManager.insertTextAtCursor()` — no new write path.

---

## Coroutine Patterns to Follow

Per CLAUDE.md conventions:

1. **ViewModel scope ownership**: `TagSuggestionViewModel` owns `CoroutineScope(SupervisorJob() + Dispatchers.Default)` internally. Never pass `rememberCoroutineScope()` from a composable.

2. **Uncaught Throwable guard**: Attach a `CoroutineExceptionHandler` to the scope (shown above) that updates `_state` to `Error`. This prevents `OutOfMemoryError` from crashing Android.

3. **LLM call dispatching**: The LLM call in `LlmTagProvider.suggestTags()` uses Ktor's IO-bounded dispatcher internally (per `ClaudeLlmFormatterProvider`). No explicit `withContext(PlatformDispatcher.IO)` wrapper needed; Ktor handles threading. Do not use `PlatformDispatcher.DB` for HTTP calls.

4. **Timeout**: Wrap the LLM tier in `withTimeout(8.seconds)` as shown above. Map `TimeoutCancellationException` to `DomainError.NetworkError.Timeout` before returning the `Either`.

5. **Cancellation**: Always rethrow `CancellationException` in catch blocks (matches the pattern in `ClaudeLlmFormatterProvider`, `VoiceCaptureViewModel`).

6. **Two-phase update pattern**: Tier 1 emits a partial `Ready` state immediately (zero latency for the user); Tier 2 updates the same `Ready` state when complete. This keeps the UI responsive while LLM is pending.

---

## Arrow Either Error Handling

Follow conventions from `CLAUDE.md` and existing code:

- `LlmTagProvider.suggestTags()` returns `Either<DomainError, List<String>>` — not `Result`, not nullable.
- `TagSuggestionEngine.llmSuggest()` returns `Either<DomainError, List<TagSuggestion>>` — callers use `.fold` or `.onLeft`.
- The ViewModel collapses `Either.Left` into a non-blocking UI annotation (`llmError: String?`) on `TagSuggestionState.Ready` — the error is surfaced as a tooltip/snackbar, not a hard failure.
- The UI never crashes from an LLM error — it falls back to local suggestions only.

```kotlin
// Caller pattern inside ViewModel
val llmResult = engine.llmSuggest(request)
_state.update { current ->
    val ready = current as? TagSuggestionState.Ready ?: return@update current
    llmResult.fold(
        ifLeft  = { err -> ready.copy(llmError = err.message) },
        ifRight = { tags -> ready.copy(llmSuggestions = tags) },
    )
}
```

---

## UI Components

### SuggestionBottomSheet

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/SuggestionBottomSheet.kt`

- Observe `TagSuggestionState` via `collectAsState()`
- Show `CircularProgressIndicator` while `Loading` or while `Ready` with empty LLM list and no error
- Show local chips immediately when `Ready.localSuggestions` is non-empty
- Show LLM chips in a second row (or merged, deduped) as they arrive
- Each chip: `SuggestionAssistChip(label = "#${suggestion.tag}", onClick = { onAccept(suggestion.tag) })`
- "Source" indicator: small icon distinguishing LOCAL vs LLM (a database icon vs. a stars/sparkles icon)
- Non-modal: use `ModalBottomSheet` on mobile, an inline panel on desktop

### Inline Chip Row

`TagChipRow` — a simpler version of the above without a bottom sheet, shown inline below the active block in `PageView`. Suitable for surfaces with limited vertical space.

### Acceptance Action

On chip tap, the parent screen calls:
```kotlin
blockStateManager.insertTextAtCursor(blockUuid, " #${tag}")
```
This follows the existing `onPasteImage` / `insertTextAtCursor` pattern in `App.kt`.

---

## Key Files to Create or Modify

| File | Action |
|---|---|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt` | Create |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/LlmTagProvider.kt` | Create |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/TagSuggestionViewModel.kt` | Create |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/SuggestionBottomSheet.kt` | Create |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LlmProviderSettings.kt` | Create |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt` | Extend (add 2 keys) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureState.kt` | Extend `Done` with `suggestedTags` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceCaptureViewModel.kt` | Call engine after `processTranscript` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` | Wire `tagSuggestionEngine` + `tagSuggestionViewModel` in `GraphContent` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` | Pass `onRequestTagSuggestions` to `BlockList` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` | Add `onRequestTagSuggestions` callback |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt` | Add `LLM_PROVIDER` settings category |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/ShareTagActivity.kt` | Create (Android-only share widget) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/PageNameIndex.kt` | Add `allTagNames(): List<String>` |

---

## What Does Not Require Changes

- **SQLDelight schema** — no new table; all settings are key-value via `Settings` interface
- **`MigrationRunner`** — no new tables to register
- **`RepositorySet`** — tag suggestions are a service layer concern, not a repository concern
- **`DatabaseWriteActor`** — tag insertion reuses `BlockStateManager.insertTextAtCursor()` which already routes through the actor
- **`DomainError`** — existing `NetworkError` subtypes cover all LLM failure cases
- **LLM provider implementations** — `ClaudeLlmFormatterProvider`, `OpenAiLlmFormatterProvider`, `MlKitLlmFormatterProvider` are reused without modification

---

## Risk Notes

1. **PageNameIndex exposure**: `StelekitViewModel` currently owns `PageNameIndex` privately. Either add a `val pageNameIndex` property or pass it to `GraphContent` as a parameter. The former is simpler.

2. **Android share widget graph access**: The share widget cannot assume the main graph is loaded. It should use a minimal `TagSuggestionEngine` with `llmTagProvider` only (skip Tier 1 or accept an empty index), or load a separate lightweight read-only `RepositorySet` from the persisted graph path via `GraphManager`. The latter is more work — start with LLM-only for the share widget.

3. **On-device LLM token cap**: `MlKitLlmFormatterProvider` hard-caps output at 256 tokens. A 5-tag response fits well within this limit. No changes needed.

4. **`VoiceSettings` shared with tag settings**: Both voice and tag suggestion features share the Anthropic/OpenAI keys. This is correct — there is one API key per provider per user. The tag suggestion provider selection (`KEY_TAG_SUGGEST_PROVIDER`) is separate from the voice LLM selection (`KEY_USE_DEVICE_LLM`).
