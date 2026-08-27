# Requirements: On-Device LLM Tag Suggestion — Download Stall

## Problem Statement

When the on-device LLM (Gemini Nano via ML Kit/AICore on Android) is not yet
`AVAILABLE`, tag suggestion checks its status exactly once and stops. In
`MlKitLlmFormatterProvider.format()`, a `DOWNLOADABLE`/`DOWNLOADING`
`FeatureStatus` immediately returns
`LlmResult.Failure.OnDeviceUnavailable(reason, retryable = true)` without
waiting or re-checking. `LlmTagProvider.suggestTags()` collapses that into
`DomainError.NetworkError.RequestFailed(reason)`, discarding `retryable`
entirely. `TagSuggestionViewModel.requestSuggestions()` sets `llmError` to
the static reason string ("Downloading on-device model — this may take a
few minutes") and never re-runs. The suggestion sheet is left showing that
one frozen caption forever — the model may finish downloading seconds or
minutes later, but the UI never notices and the user has no way to retry.
This is the bug in the attached screenshot: the sheet is stuck on the
download caption with no progress and no way out.

## Root Cause

One-shot status check with no polling, no caption escalation over time, and
a `retryable` signal that is computed (`OnDeviceUnavailable.retryable`) but
dropped before it reaches the UI (`TagSuggestionState.Ready` has no
retry-related field, `SuggestionBottomSheet` renders no retry affordance).

## Success Criteria

This is a bug fix, not a feature with growth/engagement targets, so success is defined
narrowly: (1) the reported symptom — the frozen "Downloading…" caption from the attached
screenshot, where the sheet never updates and offers no way out — no longer reproduces on
any tested path (fast/AVAILABLE, escalating/still-downloading, stalled-at-deadline,
genuinely-unsupported, and retryable-hard-failure); and (2) AC0–AC5 and AC7 (7 of the 8
acceptance criteria) are verifiably true via the automated test suite mapped in
`implementation/validation.md`. AC6 (real-hardware `DEFAULT_POLL_DEADLINE_MS` measurement)
is *not* automated-test-verified by this project — no physical AICore-capable device was
available this session, so ADR-001's desk-researched `120_000L` estimate substitutes for it,
explicitly tracked as only partially satisfying AC6 pending the mandatory real-hardware
re-validation follow-up (see ADR-001 and plan.md's Risk Control section). No engagement,
retention, or adoption metrics apply here.

## Scope

### In scope
- `dev.stapler.stelekit.tags` package: `TagSuggestionViewModel`,
  `TagSuggestionState`, `TagSuggestionEngine`, `LlmTagProvider`
- `dev.stapler.stelekit.ui.components.tags.SuggestionBottomSheet` (and
  `TagChipRow` if the retry affordance/caption lives there instead)
- The `checkStatus()`/`FeatureStatus` polling path as reached through the
  existing `LlmProvider.checkAvailability()` / `LlmProviderAvailability`
  abstraction (commonMain) — implementation is exercised primarily via
  `AndroidOnDeviceLlmProvider` / `MlKitLlmFormatterProvider`, but the retry
  loop itself belongs in the commonMain tag-suggestion flow, not
  Android-specific code, since `LlmProviderAvailability.Preparing` already
  models this state platform-agnostically.
- `TagSuggestionViewModel.scanEntries()` — must opt out of the new poll
  loop via an `allowPolling` parameter (default preserves current
  behavior for the single-suggestion path; scan explicitly passes `false`).
- A real-hardware measurement of AICore first-download duration to set
  `DEFAULT_POLL_DEADLINE_MS`.

### Out of scope
- iOS on-device (Apple Foundation Models) download/preparation UX — no
  evidence in the codebase that iOS has an analogous multi-minute download
  step; do not add polling infrastructure there speculatively. (Note: because
  the poll loop is written against the platform-agnostic
  `LlmProviderAvailability` abstraction per NFR-2, it will technically
  activate on iOS once/if an iOS on-device provider is registered — this is
  an accepted architectural consequence of NFR-2's design, not iOS-specific
  work being added by this project; see `implementation/plan.md`'s Pattern
  Decisions table and Risk Control section for the full reasoning.)
- Changing `LlmTagProvider`'s per-request 90s `withTimeout` semantics for
  providers other than on-device (cloud/custom OpenAI-compatible providers
  are unaffected).
- Redesigning `SuggestionBottomSheet` visually beyond what's needed for the
  new caption states and retry affordance.

## Functional Requirements

### FR-0 — Background status polling while a suggestion is pending (AC0)
After the initial "Downloading…" state is shown for a block's LLM
suggestion, the system re-checks on-device model status at a bounded
interval (3–5s) without the user re-triggering the request.

### FR-1 — Auto-resolve when the model becomes available (AC1)
If the model transitions to `AVAILABLE` while the poll loop is active and
the suggestion sheet is still showing that block, the system automatically
re-runs the LLM suggestion call and updates `TagSuggestionState.Ready` with
real results (or an explicit empty-results state) — no manual re-trigger.

### FR-2 — Bounded wait with a distinct terminal state and mid-wait caption change (AC2)
If the model is still `DOWNLOADABLE`/`DOWNLOADING` when a wall-clock poll
deadline (`DEFAULT_POLL_DEADLINE_MS`) is reached, the system stops polling
and surfaces a distinct "taking longer than expected" state (visually and
semantically different from the initial "Downloading…" state). Before that
terminal state, the caption text changes at least once at ~45s so the
sheet never reads as a single unchanging string for the entire wait.

### FR-3 — Manual retry affordance (AC3)
From the stalled/failed terminal state, the user can trigger a manual
retry via a visible affordance (e.g. a "Retry" button/chip) in
`SuggestionBottomSheet`/`TagChipRow`. This affordance is wired to the
`retryable` signal that already exists on
`LlmResult.Failure.OnDeviceUnavailable` but is currently discarded in
`LlmTagProvider.suggestTags()` — that signal must be threaded through
`DomainError`/`TagSuggestionState` to the UI instead of being dropped.

### FR-4 — No regression on the fast/terminal paths (AC4)
When `checkStatus()` reports `AVAILABLE` immediately, behavior is
unchanged (no polling overhead, no extra latency). When the model is
genuinely unsupported (`FeatureStatus` maps to a non-retryable
`Unavailable`), behavior is unchanged — no poll loop is started.

### FR-5 — Clean coroutine lifecycle (AC5)
The poll loop must terminate without leaking a coroutine when: the user
switches to a different block, the `TagSuggestionViewModel` is closed
(`close()` → `scope.cancel()`), or the loop's own wall-clock deadline is
reached. Per ADR-002 (to be written in the plan phase), sheet dismiss
(`dismiss()`) deliberately does NOT hard-cancel the loop — matching the
existing "let the LLM finish in the background, cache the result" pattern
— but the loop must still self-terminate via its deadline or block-switch
cancellation, i.e. it is bounded, not indefinite, even when left running
in the background.

### FR-6 — Evidence-based poll deadline (AC6)
`DEFAULT_POLL_DEADLINE_MS` must be derived from a real on-device AICore
first-download timing measurement (physical hardware), not an unvalidated
guess. This measurement must happen before Phase 1 implementation starts
and the resulting number (with source/methodology) must be recorded in the
plan.

### FR-7 — Bulk scan opts out of polling (AC7)
`TagSuggestionViewModel.scanEntries()` must continue to fail fast per
entry rather than inheriting up-to-`DEFAULT_POLL_DEADLINE_MS` waits per
entry. Implement via a new `allowPolling: Boolean` parameter on the
suggestion-request path (default `true` for the single-block/manual
suggestion flow used by `requestSuggestions()`; `scanEntries()` passes
`false` explicitly).

## Non-Functional Requirements

### NFR-1: No leaked coroutines / structured concurrency
All polling must run within `TagSuggestionViewModel`'s existing
`SupervisorJob` + `CoroutineExceptionHandler` scope; no new unscoped
`GlobalScope` or ad hoc scopes.

### NFR-2: Platform-agnostic where possible
The retry/poll loop should be written against the existing
`LlmProviderAvailability` tri-state (`Available` / `Preparing` /
`Unavailable(retryable)`) abstraction in commonMain rather than against
Android-specific `FeatureStatus`, so it does not have to be duplicated if
another platform later needs the same treatment.

### NFR-3: Testability
The poll loop's timing must be controllable in tests (e.g. injectable
clock/dispatcher or a virtual-time coroutine test scheduler) — no test
should require sleeping through the real `DEFAULT_POLL_DEADLINE_MS`.

## Acceptance Criteria (verbatim, from backlog item `505fb733-9621-4621-b7fc-27712e36d084`)

0. After the initial "Downloading…" message is shown, the app automatically
   re-checks `checkStatus()` at a reasonable interval (3-5s) without
   requiring the user to manually retrigger a suggestion request.
1. When the on-device model transitions to AVAILABLE while a
   tag-suggestion sheet is open, the UI automatically retries the LLM
   suggestion call and replaces the caption with real results (or a clean
   empty-results state) — no manual re-trigger required.
2. If the model remains DOWNLOADABLE/DOWNLOADING past a bounded poll
   deadline, the UI shows a distinct "taking longer than expected" state,
   and — per mid-poll message escalation added during review — the
   caption itself changes at least once (~45s) before that terminal
   state, so it never reads as one frozen string for the whole wait.
3. A user can manually retry from the stalled/failed state via a visible
   retry affordance wired to the existing (currently dead) `retryable`
   flag.
4. No behavior change when the on-device model is already AVAILABLE (fast
   path) or genuinely UNAVAILABLE (unsupported device).
5. Polling terminates with no leaked coroutine on block switch, ViewModel
   close, or its own wall-clock deadline; sheet dismiss does not
   hard-cancel it (documented deviation from literal AC wording, ADR-002)
   but the loop still self-terminates.
6. Before Phase 1 implementation starts, real on-device AICore
   first-download timing is measured on physical hardware and
   `DEFAULT_POLL_DEADLINE_MS` is set from that data rather than an
   unvalidated 90s guess (added by pre-mortem gate).
7. The bulk "scan entries for tag suggestions" path (`scanEntries()`) opts
   out of the poll loop via a new `allowPolling=false` parameter,
   preserving today's fail-fast-per-entry timing instead of inheriting
   up-to-90s waits per entry (added by pre-mortem gate).
