# ADR-001: EnrichmentState as a Sibling StateFlow to SaveState

**Date**: 2026-08-10
**Status**: Accepted

## Context

`CaptureViewModel.SaveState` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:35-40`)
is a small sealed class — `Idle`/`Saving`/`Saved`/`Error` — consumed via both `==` comparisons
(`saveState == SaveState.Idle` at `CaptureActivity.kt:234,310`) and `is` matches
(`CaptureActivity.kt:218-219`). This feature adds a second, orthogonal concern: whether a
local-heuristic/LLM suggestion pass has produced chips the user should see before the sheet
closes. Both concerns need to reach `CaptureScreen`.

## Decision

Add a second, independent `StateFlow<EnrichmentState>` on `CaptureViewModel`
(`enrichmentState`, backed by `CaptureEnrichmentCoordinator.state`). Do not change the shape of
`SaveState` in any way — no new variant, no new field.

```kotlin
sealed interface EnrichmentState {
    data object Idle : EnrichmentState
    data object Scanning : EnrichmentState
    data class Ready(
        val linkedText: String,
        val matchedPageNames: List<String>,
        val topicSuggestions: List<TopicSuggestion> = emptyList(),
        val isEnhancing: Boolean = false,
        val sourceTextHash: Int,
    ) : EnrichmentState
    data object TimedOut : EnrichmentState
}
```

`CaptureScreen`'s existing `LaunchedEffect(saveState) { ... }` becomes
`LaunchedEffect(saveState, enrichmentState) { ... }`, gating the auto-`onSaved()` call on both
values instead of `saveState` alone.

## Rationale

This mirrors the codebase's own precedent: `ImportState`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt:59-81`) keeps
`isSaving`/`savedPageName` as plain fields alongside — not nested inside — the suggestion-tray
fields (`topicSuggestions`, `isEnhancing`, `claudeStatus`). Folding enrichment into `SaveState`
(e.g. `Saved(pendingSuggestions: Boolean)`) would work mechanically but:

1. Breaks the four existing `SaveState.Idle`/`Saving` `==` comparisons in `CaptureActivity.kt`,
   which assume those are singleton `data object`s.
2. Conflates two orthogonal questions — "did the write succeed" vs. "is there an optional
   follow-up review step" — into one type, making both harder to reason about independently.

## Consequences

- `CaptureViewModel` gains `val enrichmentState: StateFlow<EnrichmentState>`.
- `CaptureScreen`'s save-state `LaunchedEffect` gains a second key and a
  has-unresolved-suggestions check (see Story 3.2.2).
- No existing `SaveState`-comparison call site needs to change.
- Source: `project_plans/capture-auto-enrich/research/architecture.md` §3 (this ADR adopts that
  research doc's recommendation verbatim).
