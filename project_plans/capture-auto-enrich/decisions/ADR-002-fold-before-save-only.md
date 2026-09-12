# ADR-002: Fold-Before-Save-Only Write Path (No Retroactive Post-Save Edit)

**Date**: 2026-08-10
**Status**: Accepted

## Context

A suggestion chip can be accepted either before or after the user taps Save. `requirements.md`
only specifies behavior for the "before" case: "accepting a suggestion chip creates a stub
page... and (if the accept happens before save) folds the new `[[wiki link]]` into the saved
block content." There is no requirement describing what happens if a chip is accepted, or a
background LLM suggestion arrives, after the block has already been written to disk.

`ImportViewModel.confirmImport()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportViewModel.kt:365-418`)
establishes the reference pattern: `onSuggestionAccepted` only mutates in-memory state; every
stub-page write and every content fold-in happens exactly once, inside `confirmImport()`.

## Decision

`CaptureViewModel`/`CaptureEnrichmentCoordinator` never write anything (no `pageSaver.save`, no
block persistence) at chip-accept time. `onSuggestionAccepted`/`onSuggestionDismissed` only
mutate `EnrichmentState.Ready` in memory (folding the `[[wiki link]]` into
`Ready.linkedText` immediately, so it's visible and already-applied). All persistence — the
journal block write and every accepted suggestion's stub-page creation — happens exactly once,
synchronously inside `CaptureViewModel.performSave()`, via
`CaptureEnrichmentCoordinator.resolveForSave()` and `createAcceptedStubPages()`.

No retroactive post-save edit path is built: if enrichment or an accept event resolves after
`SaveState.Saved` has already fired, it does not go back and rewrite the already-persisted
block.

## Rationale

- **Existing-page auto-linking** already has no "after save" case — it's computed synchronously
  before the block is constructed, same as `ImportViewModel`'s always-on matched-page linking.
- **A true retroactive edit is materially riskier for a v1 integration**: it would require
  re-reading the persisted block (a second DB round-trip after the file has already been
  flushed by Bug 8's `writer.savePage()`), re-applying `ImportService.insertWikiLinks` to
  content that may have already drifted (the user can reopen the main app and edit the journal
  immediately after a capture), then issuing a second `writeActor.saveBlock()` +
  `GraphWriter.savePage()` pair — reintroducing Bug 1's `ClosedSendChannelException` race a
  second time, for marginal benefit.
- **This ADR eliminates an entire race class for free.** Because no write is ever triggered from
  an accept event, "user accepts a chip then immediately taps Dismiss" cannot leave a stub page
  or partial write behind — there was never anything in flight to race. `viewModelScope`
  surviving `finish()` (research/pitfalls.md finding #2) stops being a correctness risk for
  stub-page creation specifically; it remains a (harmless) consideration only for the
  fire-and-forget LLM enrichment coroutine, which never has a side effect attached to it.
- **Requirements' own phrasing is the tie-breaker**: "if the accept happens before save" reads
  as the qualifying/expected case, not one of two symmetric branches requiring equal support.

## Consequences

- `CaptureEnrichmentCoordinator.createAcceptedStubPages()` is only ever called from
  `CaptureViewModel.performSave()` — never from `onSuggestionAccepted()`.
- A chip accepted after Save has already fired has no effect (the sheet is closing/closed by
  then per ADR-001's gate, so this is expected to be rare, not silently lossy).
- No undo affordance is required to make "oops, I didn't mean to accept that" safe pre-Save —
  simply not tapping Save is sufficient, since nothing was written. See the plan's
  "Out of Scope" section for the explicit undo scope call.
- Source: `project_plans/capture-auto-enrich/research/architecture.md` §5 (this ADR adopts that
  research doc's recommendation, and additionally documents the race-elimination consequence
  flagged separately in `research/pitfalls.md` finding #2).
