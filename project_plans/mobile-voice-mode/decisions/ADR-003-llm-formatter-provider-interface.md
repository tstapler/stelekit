# ADR-003: LLM Formatter Provider Interface

**Status**: Proposed
**Date**: 2026-04-18

## Context

After speech-to-text transcription, the raw transcript is formatted by an LLM into Logseq
outliner syntax: top-level `- ` bullets for main topics, 2-space-indented sub-bullets, and
`[[wikilinks]]` only for terms explicitly named in the transcript.

Multiple LLM backends are candidates:

- **Anthropic Claude** (cloud, user-supplied key): via `POST /v1/messages`; Ktor-native; mirrors
  the existing `ClaudeTopicEnricher` implementation pattern exactly.
- **OpenAI GPT** (cloud, user-supplied key): via `POST /v1/chat/completions`; compatible with
  any OpenAI-compatible endpoint.
- **Apple FoundationModels** (iOS, on-device): shipped at WWDC 2025; text-in/text-out API;
  offline, no cost; device gate: iPhone 15 Pro+, iOS 18.1+. Phase 2 scope.
- **No-op** (pass transcript verbatim): default for Story 1 — minimal end-to-end wire without
  LLM cost or API key requirement.

LLM hallucination of `[[wikilinks]]` to non-existent pages is a confirmed risk. The v1 system
prompt must constrain links to terms explicitly stated in the transcript. The raw transcript
must always be preserved as a collapsible block below the formatted output for user verification.

## Decision

Define `LlmFormatterProvider` as a `suspend fun interface` in `commonMain` with a sealed result
type following the same pattern as `TranscriptResult`:

```kotlin
// commonMain — voice/LlmFormatterProvider.kt
fun interface LlmFormatterProvider {
    suspend fun format(transcript: String, systemPrompt: String): LlmResult
}

sealed interface LlmResult {
    data class Success(val formattedText: String) : LlmResult
    sealed interface Failure : LlmResult {
        data object NetworkError : Failure
        data class ApiError(val code: Int, val message: String) : Failure
        data object Timeout : Failure
        data class MalformedResponse(val raw: String) : Failure
    }
}

/** Default: returns the transcript verbatim, no formatting. Used in Story 1 and tests. */
object NoOpLlmFormatterProvider : LlmFormatterProvider {
    override suspend fun format(transcript: String, systemPrompt: String) =
        LlmResult.Success(transcript)
}
```

**`ClaudeLlmFormatterProvider`** (Story 2): `commonMain`. Ktor POST to
`https://api.anthropic.com/v1/messages`. Mirrors `ClaudeTopicEnricher.kt` — same HTTP client
pattern, same error mapping. User-supplied API key from `VoiceSettings`.

**`OpenAiLlmFormatterProvider`** (Story 2): `commonMain`. Ktor POST to
`https://api.openai.com/v1/chat/completions`. Compatible with any OpenAI-compatible endpoint
(Groq, local Ollama, etc.) via configurable `baseUrl`.

**`NoOpLlmFormatterProvider`**: Story 1 default. Returns `LlmResult.Success(transcript)`. Allows
full end-to-end pipeline testing without an API key. The raw transcript IS the formatted output.

**`AppleIntelligenceLlmFormatterProvider`** (Phase 2, `iosMain`): `FoundationModels` framework.
On-device, offline, no cost. Device gate: iPhone 15 Pro+, iOS 18.1+. Added as an optional
built-in in a future story.

**System prompt**: The system prompt is a constructor parameter of `VoiceCaptureViewModel`, not
embedded in the interface. The v1 default prompt:

```
You are a personal note assistant. Convert the following voice transcript into Logseq outliner
markdown format:
- Use "- " (dash space) for all bullets
- Use exactly 2 spaces for each level of indentation
- Extract main topics as top-level bullets
- Add sub-points as indented bullets
- Add [[Page Name]] wiki links ONLY for proper nouns or topics explicitly named in the
  transcript — do NOT invent links for terms not spoken
- Do NOT add a preamble, title, or summary
- Output ONLY the bullet list

<transcript>
{{TRANSCRIPT}}
</transcript>
```

The transcript is inserted via `{{TRANSCRIPT}}` substitution in `VoiceCaptureViewModel`, not
by the provider. The provider receives the fully assembled system prompt string.

## Rationale

**`suspend fun interface`**: Identical seam pattern to `TopicEnricher` and `SpeechToTextProvider`.
Single-method, no base class dependency, directly callable from coroutines.

**`NoOpLlmFormatterProvider` returning `Success(transcript)`**: Story 1 requires a working
end-to-end pipeline without LLM cost. Returning the transcript verbatim is safe and useful —
the user sees their spoken words appended to the journal, unformatted. The collapsible raw
transcript block below the formatted output satisfies the same purpose in Story 1.

**Caller-controlled system prompt**: The system prompt for Logseq outliner format will need
iteration. Keeping it as a `VoiceCaptureViewModel` parameter (defaulting to the v1 prompt
constant) allows prompt tuning without touching the `LlmFormatterProvider` interface. This
matches the principle from `ClaudeTopicEnricher` where the prompt is internal to the enricher
implementation but could be constructor-injected.

**`[[link]]` hallucination mitigation in v1**: The system prompt constraint ("ONLY for proper
nouns explicitly named in the transcript") is the minimum viable safeguard. A stronger v2
mitigation — passing the graph page index to the LLM — is deferred because it adds context cost
and complexity. The raw transcript preserved as a collapsible block lets users verify what was
captured vs what the LLM produced.

**No streaming interface**: Post-stop formatting latency is 1–3 seconds for typical voice notes.
Progressive display is not worth the implementation complexity for v1. The `VoiceCaptureState`
machine (`Formatting` state) communicates that work is in progress.

## Consequences

- `VoiceCaptureViewModel` constructor: `llmProvider: LlmFormatterProvider = NoOpLlmFormatterProvider`,
  `systemPrompt: String = DEFAULT_VOICE_SYSTEM_PROMPT`.
- `ClaudeLlmFormatterProvider` and `OpenAiLlmFormatterProvider` require user-supplied API keys
  from `VoiceSettings` (Story 2). If a key is absent, `format()` returns
  `LlmResult.Failure.ApiError(401, "API key not configured")`.
- Both Story 2 providers live in `commonMain` — no platform code needed for cloud LLM calls.
- `AppleIntelligenceLlmFormatterProvider` (Phase 2) will live in `iosMain` and requires a
  capability check at runtime (`FoundationModels.isAvailable()`).
- The `LlmResult` sealed hierarchy is a stable public API commitment once the plugin registry
  is added.
- Truncation detection: if `formattedText` does not end with `.`, `?`, `!`, or `]]`, surface a
  "Formatting may be incomplete" warning in the `VoiceCaptureState.Done` state.

## Alternatives Considered

**Batch string-out with `max_tokens` auto-calculation**: The LLM should be called with
`max_tokens = (transcriptWordCount * 1.5).toInt()` to avoid truncation. This is a
`VoiceCaptureViewModel` responsibility, not the provider's — providers receive the assembled
request body.

**`koog` (JetBrains KMP agent framework, v0.7.3)**: Evaluated and eliminated. Pre-1.0 stability,
designed for multi-step agent workflows — overkill for a single `format()` call. Raw Ktor is 50
lines and already present.

**`AppleIntelligenceLlmFormatterProvider` in v1**: Deferred. Adds cinterop complexity and a
capability-check UX for a device gate (iPhone 15 Pro+ only). Phase 2 is the appropriate slot.

**Structured JSON output mode**: LLM returns JSON `{"bullets": [...]}` which is parsed and
rendered to Logseq markdown client-side. Better format reliability but adds parsing complexity
and a JSON schema contract. Deferred to Phase 2 if zero-shot prompt compliance is insufficient.
