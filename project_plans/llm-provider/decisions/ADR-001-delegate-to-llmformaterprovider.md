# ADR-001: LlmTagProvider Delegates to LlmFormatterProvider

**Date**: 2026-06-13
**Status**: Accepted

## Context

The tag suggestion engine needs LLM-assisted tag suggestions for blocks. Two concrete LLM
clients already exist in `voice/`: `ClaudeLlmFormatterProvider` and `OpenAiLlmFormatterProvider`.
Both implement `LlmFormatterProvider`, carry `CircuitBreaker` resilience, and use the project's
standard Ktor + kotlinx.serialization stack.

An alternative would be to build a third standalone client in `tags/` (following
`ClaudeTopicEnricher`'s pattern of a self-contained `HttpClient` and manual retry).

## Decision

`LlmTagProvider` in `tags/` delegates to an injected `LlmFormatterProvider` rather than
owning its own `HttpClient`.

`LlmTagProvider` constructs a structured system prompt (vocabulary list in `<tags>` XML block
+ constrained output instructions) and calls `provider.format(blockText, systemPrompt)`.
Response parsing handles a newline-delimited list of page names returned by the model.

Both Anthropic and OpenAI-compatible providers are supported via this single interface — the
caller selects which concrete `LlmFormatterProvider` to inject (wired in `App.kt` following the
`buildVoicePipeline` pattern).

## Consequences

Positive:
- CircuitBreaker resilience is inherited for free — no copy of `ClaudeTopicEnricher`'s manual
  429 retry loop.
- No new `HttpClient` instances; no new serialization boilerplate.
- OpenAI-compatible endpoints (Groq, Ollama, Azure) work without additional code by injecting
  `OpenAiLlmFormatterProvider.withDefaults(key, baseUrl)`.
- `LlmFormatterProvider` is already covered by `MockEngine` tests in jvmTest.

Negative / Trade-offs:
- `LlmFormatterProvider.format(transcript, systemPrompt)` was designed for voice transcripts.
  For tag suggestions the `transcript` parameter carries the block text and `systemPrompt` carries
  the vocabulary prompt — a mild repurposing of parameter names.
- Token budget estimation (`LlmProviderSupport.estimateMaxTokens`) is calibrated for voice
  transcripts. `LlmTagProvider` will override `maxTokens` in the system prompt body directly
  and accept whatever the provider returns; the vocabulary list is bounded to 200 names so
  response tokens stay well under 256.

## Alternatives Rejected

- **Standalone HttpClient in `tags/`** (ClaudeTopicEnricher pattern): rejected because it
  duplicates CircuitBreaker setup, adds a second connection pool, and repeats the 429 blind-retry
  anti-pattern the requirements explicitly forbid copying.
- **Extending TopicEnricher interface**: rejected because `TopicEnricher.enhance()` takes
  `localSuggestions: List<TopicSuggestion>` and is designed for re-ranking, not the constrained-
  vocabulary tag-from-scratch use case here.
