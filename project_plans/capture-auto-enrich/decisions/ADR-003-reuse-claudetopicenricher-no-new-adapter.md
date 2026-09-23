# ADR-003: Reuse ClaudeTopicEnricher Directly Instead of a New LLM Adapter Class

**Date**: 2026-08-10
**Status**: Accepted

## Context

`project_plans/capture-auto-enrich/research/architecture.md` (§"Current-state findings" and §2)
recommends writing a **new**, small `TopicEnricher` adapter class over `LlmProviderRegistry`,
"structurally identical to `tags/LlmTagProvider`," reasoning that `ClaudeTopicEnricher` is
Claude-specific and therefore unsuitable to wire directly to a registry-resolved, possibly
non-Claude `LlmProvider`.

Direct reading of `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricher.kt`
(lines 20-22) during planning shows this premise is out of date:

```kotlin
class ClaudeTopicEnricher(
    private val claudeProvider: LlmFormatterProvider,
) : TopicEnricher {
```

As of the landed `llm-service` Epic 8 Story 8.3 migration (already noted in
`research/build-vs-buy.md`), `ClaudeTopicEnricher`'s constructor takes the generic
`dev.stapler.stelekit.voice.LlmFormatterProvider` interface — the same interface every
`LlmProvider.formatter` in the registry already exposes. Nothing inside the class is
Claude-specific except its name and its `withDefaults(apiKey)` convenience factory (which
this feature does not use). `tags/LlmTagProvider` — the class architecture.md points to as the
pattern to mirror — has the exact same shape: `class LlmTagProvider(private val provider:
LlmFormatterProvider, ...)`.

## Decision

Do **not** write a new `TopicEnricher` adapter class. Construct `ClaudeTopicEnricher` directly
with the registry-resolved provider's formatter:

```kotlin
val enricher: TopicEnricher =
    resolvedProvider?.let { ClaudeTopicEnricher(it.formatter) } ?: NoOpTopicEnricher()
```

where `resolvedProvider` comes from `LlmProviderRegistry`/`LlmSettings` resolution
(`LlmSettings.getSelectedProviderId(LlmFeature.TOPIC_ENRICHMENT)` →
`DISABLED_SENTINEL`/`null`("Auto")/explicit id), mirroring `App.kt:1107-1116`'s tag-suggestion
resolution pattern exactly.

## Rationale

A second class implementing the identical `TopicEnricher` fun interface
(`suspend fun enhance(rawText, localSuggestions): List<TopicSuggestion>`) over the identical
`LlmFormatterProvider` dependency, with no different prompt or parsing logic, would be a
line-for-line duplicate of `ClaudeTopicEnricher`. Writing it anyway to avoid an arguably
misleading class name would directly contradict this feature's own constraint ("reuse whichever
LLM provider is configured via the llm-service abstraction... no new provider code",
`requirements.md` Must-Have) and `research/build-vs-buy.md`'s explicit guard against
reinventing shipped logic.

The `ClaudeTopicEnricher` name is acknowledged as misleading now that the class is
provider-agnostic — this is flagged as a separate, out-of-scope cleanup (see plan's Unresolved
Questions), not resolved here, because renaming it touches `ImportViewModel`/`ScreenRouter.kt`
call sites outside this feature's stated file list.

## Consequences

- Zero new files/classes for LLM-tier wiring beyond the `LlmFeature.TOPIC_ENRICHMENT` enum case
  and the resolution logic inside `CaptureViewModel`.
- `LlmBackedTopicEnricher`/similar — the name architecture.md's summary table proposed — is
  **not** created. Any future reader of this plan's task list should not expect that file to
  exist.
- This ADR explicitly disagrees with `research/architecture.md`'s Summary-table row
  ("`domain/LlmBackedTopicEnricher` (new, small) adapter") and its §2 recommendation, on the
  basis of a direct code read performed during planning (`domain/ClaudeTopicEnricher.kt:20-22`)
  that the research pass's own citations (`research/build-vs-buy.md` §"Confirming the reuse
  premise") already contained but didn't carry through to the architecture recommendation.
