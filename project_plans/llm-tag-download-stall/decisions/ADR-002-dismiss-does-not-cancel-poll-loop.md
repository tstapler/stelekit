# ADR-002: Sheet Dismiss Does Not Hard-Cancel the Poll Loop

**Status**: Accepted
**Date**: 2026-07-29

## Context

FR-5/AC5 (verbatim from the backlog item) says polling must terminate with no leaked
coroutine "on block switch, ViewModel close, or its own wall-clock deadline." A literal
reading could be extended to also cancel on sheet dismiss (`SuggestionBottomSheet`'s
`onDismiss` → `TagSuggestionViewModel.dismiss()`), since dismiss is a natural "user is
done with this" signal.

`TagSuggestionViewModel.dismiss()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt:177-181`)
already deliberately does **not** cancel `suggestionJob` — the existing comment reads:

> // Do NOT cancel suggestionJob — let the LLM finish in the background and cache the
> result. The next requestSuggestions() for the same block will serve from cache
> immediately.

This is a pre-existing, intentional pattern (predates this project) for the plain LLM
suggestion call: closing the sheet does not abort an in-flight `engine.llmSuggest()` call,
because the network/on-device round trip has real cost (a 90s timeout budget,
`generateContent()` inference cost, on-device battery/compute) and the result is still
useful if the user reopens the same block shortly after. `research/features.md` confirms
reopening the same block mid-download already works correctly today via `cache`, exactly
because `dismiss()` doesn't cancel.

The new poll loop (FR-0) is layered on top of the same `suggestionJob`. Three options were
considered for how dismiss should interact with it:

| Option | Description | Rejected reason |
|---|---|---|
| **A. Cancel on dismiss** | Literal AC5 reading — dismiss cancels `suggestionJob` (and the poll loop nested in it) immediately | Breaks the existing, intentional "let it finish in background" pattern for the non-polling path too, since the poll loop lives inside the same job. Would regress today's working "reopen same block, see cached/in-progress result" behavior for the *new* download-wait case specifically — the case this project exists to fix. Also wastes the AICore download-kickoff side effect already fired by `format()` (Task references pitfalls #2/#5) — cancelling mid-download doesn't stop AICore's OS-level download, it just stops the app from ever finding out it finished. |
| **B. Cancel poll, keep suggestion result** | Dismiss cancels only the poll sub-loop, letting any in-flight `format()`/`suggestTags()` call finish, but stops re-checking `checkAvailability()` | Adds a second cancellation surface (poll job vs. suggestion job) that must be kept in sync with pitfall #1's requirement that the poll job be nested under `suggestionJob`, not a sibling — more moving parts for no clear user benefit, since the poll loop's own bounded deadline (ADR-001) already caps its lifetime regardless. |
| **C. No hard-cancel on dismiss (status quo, extended to polling)** | Dismiss only resets UI state (`_state.value = Idle`); `suggestionJob` (poll loop included) keeps running until its own deadline, a block switch, or `close()` | **Chosen.** Consistent with the existing pattern this codebase already ships; the loop is still bounded (not indefinite) via ADR-001's `DEFAULT_POLL_DEADLINE_MS`, so "no hard cancel on dismiss" does not mean "no cancellation ever" — it means cancellation is driven by the loop's own termination conditions, matching FR-5's actual set (block switch / close / deadline) rather than adding dismiss as a fourth. |

## Decision

`TagSuggestionViewModel.dismiss()` continues to only reset `_state.value` to
`TagSuggestionState.Idle` and does **not** cancel `suggestionJob` (or the poll loop nested
inside it, once implemented for FR-0). The poll loop still self-terminates via:

1. Its own wall-clock deadline (`DEFAULT_POLL_DEADLINE_MS`, ADR-001) — bounded, not
   indefinite.
2. A block switch — `requestSuggestions()` for a *different* `blockUuid` still calls
   `suggestionJob?.cancel()` before starting a new job (existing behavior,
   `TagSuggestionViewModel.kt:88`), which cancels the previous block's poll loop too since
   it is nested, not a sibling (pitfall #1's structural requirement).
3. `close()` → `scope.cancel()` (existing behavior, `TagSuggestionViewModel.kt:183-185`).

If the model becomes `AVAILABLE` while the sheet is dismissed and the loop is still
running in the background for that block, the result is written to `cache[blockUuid]` as
today; reopening the same block via `requestSuggestions()` serves the resolved result
(or, if still pending, the in-progress `Ready` state) immediately per the existing
cache-hit branch at `TagSuggestionViewModel.kt:76-85`.

This is a deliberate, documented deviation from the literal wording of AC5 ("polling
terminates ... on block switch, ViewModel close, or its own wall-clock deadline; sheet
dismiss does not hard-cancel it") — which the backlog item itself already anticipates and
names this ADR to justify.

## Consequences

**Positive**:
- Preserves the existing, working "close sheet, reopen same block, see result without
  re-waiting" UX for the download-wait case, not just the fast-resolving case.
- No new cancellation surface to keep in sync with `suggestionJob`'s existing lifecycle —
  the poll loop is just "more work `suggestionJob` does," governed by the same three
  termination conditions that already exist.
- Matches the single-job, single-active-block design `research/features.md` documents
  (only one block's poll loop can ever be running at a time app-wide), so "runs in the
  background after dismiss" never means "multiple concurrent background polls."

**Negative/Risks**:
- A user who dismisses the sheet and never reopens that block leaves the poll loop running
  for up to `DEFAULT_POLL_DEADLINE_MS` (120s per ADR-001) doing periodic
  `checkAvailability()` calls nobody will see the result of, until the deadline or a block
  switch reclaims it. This is bounded (not a leak — NFR-1 still holds) but is not zero-cost;
  accepted because 120s of a lightweight `checkAvailability()` poll at a 4s interval
  (~30 calls) is negligible compared to the alternative regression described in Option A.
- If the user switches away from the graph/app entirely (not just dismissing the sheet)
  without navigating to a different block, the loop is not reclaimed until its own
  deadline — `research/features.md`'s open question about `ProcessLifecycleOwner`-based
  pause-on-background is not resolved by this ADR and remains a noted, deferred risk (see
  plan.md Risk Control).
