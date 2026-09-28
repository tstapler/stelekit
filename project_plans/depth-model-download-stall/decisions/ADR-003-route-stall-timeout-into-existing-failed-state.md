# ADR-003: Route the AC4 stall timeout into the existing `Failed` state, not a new `Stalled` state

## Status
Accepted

## Context

AC4 requires that a user "can distinguish 'actively downloading' from 'stalled/no progress for N
seconds'" — at minimum, a stalled transfer must not look identical to a healthy one indefinitely.
The AC's own suggested mechanism is explicit: *"surface a 'taking longer than usual' affordance
or timeout-driven transition to `Failed` with retry."*

Two designs were considered:
1. Add a third sealed-interface variant, e.g. `ModelState.Stalled` / `DepthModelUiState.Stalled`,
   distinct from `Failed`, so the UI can render a visually different "still trying, but slow"
   state versus a hard failure.
2. Reuse the existing `Failed` state for a stall timeout, adding an optional `reason: String?`
   field so the UI copy can differ ("This is taking longer than expected." vs. the generic
   "Download failed") without a new state.

research/ux.md explicitly recommends option 2: *"Route the AC4 stall timeout into the existing
`Failed` UI branch/copy rather than adding a new state"* — reusing the `Failed` branch's existing
retry-button treatment, which is already the correct UX per the AC's own literal wording
("transition to `Failed` with retry").

## Decision

Stall detection (`hasStalled()` in the polling loop, `DepthModelDownloader.kt`) transitions
`ModelState.Downloading` directly to `ModelState.Failed(reason = "This is taking longer than
expected.")`. No new sealed-interface variant is added to either `ModelState` or
`DepthModelUiState`. The existing `Failed` branch in `DepthEstimationPanel`
(`AnnotationEditorScreen.kt:1400-1415`) is updated to render `reason` when present, falling back
to the existing generic "Download failed — tap to retry" copy when `reason == null` (e.g. a
genuine `DownloadManager.STATUS_FAILED`, not a stall).

## Consequences

- No changes needed to the `toUiState()` mapper's exhaustiveness beyond the one new field — a
  `Stalled` variant would have required touching both sealed hierarchies, the `DepthEstimationPanel`
  `when` block, and the mapper, for a distinction the AC doesn't require.
- The existing "Download failed — tap to retry" `TextButton` is reused as-is for the retry
  action — no new UI affordance to design, test, or make accessible.
- Trade-off accepted: a user cannot tell, from UI state alone, "waiting because paused for a
  legitimate reason (e.g. `PAUSED_QUEUED_FOR_WIFI`) but not yet past the stall timeout" apart
  from the percentage simply not moving for up to `STALL_TIMEOUT_MS` (30s) before flipping to
  `Failed`. This was judged acceptable: `STALL_TIMEOUT_MS` is generous enough (matching
  `SafChangeDetector`'s existing 30s "reasonable slow network tolerance" constant elsewhere in
  this codebase) that the AC's core goal — "must not look identical to a healthy one
  indefinitely" — is met without a third state.
- If product later wants a distinct "waiting for Wi-Fi" vs. "stalled" vs. "generic failure" UI
  treatment, `Failed.reason` can grow from a free-form `String?` into a closed `enum
  FailureReason` without another ADR — the state-count/mapper decision made here is unaffected by
  that future refinement (tracked in plan.md's Unresolved Questions).
