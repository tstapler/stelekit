# ADR-006: VoicePipelineConfig — `class` instead of `data class`

**Status**: Accepted
**Date**: 2026-04-19

## Context

`VoicePipelineConfig` was initially planned as a `data class` (see ADR-004). During implementation it was changed to a plain `class`.

## Decision

Use `class`, not `data class`.

## Reason

`data class` generates `equals()` and `hashCode()` that compare field values structurally. Two of the three fields in `VoicePipelineConfig` — `sttProvider` and `llmProvider` — are `suspend fun interface` instances (lambda or anonymous object). These types do not implement stable `equals()`, so structural equality is effectively identity equality. The generated `equals()` would be misleading: two configs built from the same API key would compare as unequal because each `withDefaults()` call produces a different object instance.

Since `VoicePipelineConfig` is used for injection (wired in `MainActivity` and compared nowhere), structural equality adds no value while the `data class` contract creates false expectations. A plain `class` removes the pretense.

## Consequences

- No `copy()` method — build a new config via the constructor or `buildVoicePipeline()`.
- `equals()` falls back to identity, which is correct for mutable pipeline configurations.
