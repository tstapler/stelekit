# ADR-003: Claude API Opt-In Architecture

**Date**: 2026-04-17
**Status**: Accepted

## Context

The requirements mandate that Claude API calls are strictly opt-in — zero API calls, zero network traffic, zero cost when no key is configured. The `TopicEnricher` interface (ADR-002) provides the seam. This ADR decides how `ClaudeTopicEnricher` is wired, how the API key is provided, how errors degrade, and which model and API version to target.

Key verified facts from research (web searches, 2026-04-17):
- Anthropic Messages API endpoint: `POST https://api.anthropic.com/v1/messages`
- Required header: `anthropic-version: 2023-06-01`
- Recommended model for fast, cheap extraction: `claude-haiku-4-5-20251001`
- Retired models that return errors (must not be used): `claude-3-7-sonnet-20250219`, `claude-3-5-haiku-20241022`
- No official Anthropic KMP SDK exists; raw ktor HTTP is the correct approach.

## Decision

**Implementation**: `ClaudeTopicEnricher` lives in `commonMain` and calls the Anthropic Messages API via `ktor-client-core` (already in `commonMain`) plus `ktor-client-content-negotiation:3.1.3` and `ktor-serialization-kotlinx-json:3.1.3` (to be added if not already transitive via coil).

**API key injection**: The API key is passed to `ClaudeTopicEnricher` as a constructor `String`. Key resolution (reading from secure storage or user preferences) is the responsibility of the call site that constructs the ViewModel, not of `ClaudeTopicEnricher` itself. This keeps the enricher free of platform dependencies.

**Zero-calls-when-no-key guarantee**: When the API key is blank or absent, `NoOpTopicEnricher` is used — the `ClaudeTopicEnricher` is never constructed. The wiring site checks for a non-blank key before constructing `ClaudeTopicEnricher`.

**Timeout**: 8 seconds via `withTimeout(8_000)` wrapping the ktor call. On timeout, `TopicEnricher` is considered to have returned the `localSuggestions` unchanged (same as NoOp), and `claudeStatus` is set to `Failed.Timeout`.

**Error handling — all errors silent by default**: All exceptions from the Claude call (network error, HTTP 4xx/5xx, JSON parse failure, timeout) must not propagate to the ViewModel as uncaught exceptions. A sealed `ClaudeEnrichmentResult` type communicates outcome:

```kotlin
sealed interface ClaudeEnrichmentResult {
    data class Success(val suggestions: List<TopicSuggestion>) : ClaudeEnrichmentResult
    sealed interface Failure : ClaudeEnrichmentResult {
        data object Timeout : Failure
        data object RateLimited : Failure
        data object NetworkError : Failure
        data class ApiError(val code: Int) : Failure
        data object MalformedResponse : Failure
    }
}
```

All `Failure` variants result in `claudeStatus = ClaudeStatus.Failed(reason)` in `ImportState`; the local suggestions are preserved untouched.

**Retry**: A single retry with 2-second delay on HTTP 429 (rate limit). No retry for other failures.

**Input truncation**: Import text is truncated to 15,000 tokens (approximately 60 KB) before sending to prevent context-window errors.

**Prompt strategy**: Send local candidates with the raw text and ask Claude to re-rank, filter, and add up to 5 net-new concepts. Responses must be a JSON array of objects `{term: string, confidence: float}`. The user content is wrapped in `<document>…</document>` tags in the prompt to provide a clear injection boundary.

**Model**: `claude-haiku-4-5-20251001`. This must be stored as a named constant, not a string literal, to make future model upgrades visible in code review.

## Rationale

**commonMain placement** allows `ClaudeTopicEnricher` to work on all KMP targets (Desktop, Android, iOS) using the already-wired platform HTTP engines (OkHttp for JVM/Android, Darwin for iOS). Restricting to `jvmMain` would exclude mobile users who have configured an API key.

**Constructor injection of API key** keeps `ClaudeTopicEnricher` free of `expect/actual` and platform-specific keychain APIs. The platform-appropriate key retrieval is handled at the DI/construction layer, which already has platform awareness.

**Re-rank local candidates rather than open-ended generation** is the preferred prompt strategy because: it is cheaper (fewer input tokens when the candidate list is short), produces more predictable structured output, and ensures Claude only evaluates terms that actually appear in the text. Claude may still add up to 5 net-new concepts if it identifies important terms the heuristic missed.

**Sealed result type over exceptions** keeps the ViewModel's `catch` blocks minimal and makes all failure modes explicit. This is especially important since the Claude enrichment runs in a fire-and-forget coroutine — unhandled exceptions in that context would be silently swallowed by the coroutine scope.

## Consequences

- Two new `commonMain` dependencies required: `io.ktor:ktor-client-content-negotiation:3.1.3` and `io.ktor:ktor-serialization-kotlinx-json:3.1.3`. Verify whether `coil-network-ktor3` already makes these transitive before adding explicit declarations.
- The `CLAUDE_MODEL` and `ANTHROPIC_VERSION` constants must be defined as named constants in `ClaudeTopicEnricher` (or a companion object) and referenced via those names throughout.
- `ImportState` must carry `claudeStatus: ClaudeStatus` (an enum or sealed class) so `ImportScreen` can render the "AI-enhanced" badge or "AI unavailable" notice without polling.
- Prompt injection risk is mitigated by the `<document>` delimiter and a system-prompt instruction that the model must only return a JSON array regardless of document content. The model is not asked to interpret or act on document instructions.
- iOS App Store submission may require privacy disclosure that content is sent to a third-party API. This is a compliance concern for the app release, not a code concern for this feature.

## Patterns Applied

- Null Object pattern (`NoOpTopicEnricher` — zero-cost default that satisfies the interface)
- Circuit Breaker lite (timeout + fallback to local results)
- Sealed result type for explicit failure mode enumeration
- Dependency Inversion (ViewModel depends on `TopicEnricher` interface; `ClaudeTopicEnricher` is a detail)
