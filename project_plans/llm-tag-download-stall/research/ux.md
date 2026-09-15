# UX Research: On-Device LLM Tag Suggestion — Download Stall

Agent 5 (UX Research), SDD Phase 2. Scope per requirements.md: `SuggestionBottomSheet.kt`
and `TagChipRow.kt`. Out of scope: visual redesign beyond new caption/retry states; iOS UX.

## Current-state baseline (read directly from code, not assumed)

- `SuggestionBottomSheet` (`ui/components/tags/SuggestionBottomSheet.kt`) has exactly two
  visible states: `Loading` (centered spinner) and `Ready`. There is no `Preparing`/`Stalled`
  branch — the sheet has no idea a download is in progress; it only sees the *result* of one
  attempt.
- `Ready` renders `TagChipRow` (local + LLM chips) and, if `state.llmError != null`, a single
  static `Text` in `MaterialTheme.colorScheme.error` below the row. No button, no icon, no
  retry affordance of any kind exists today — this is a plain read-only caption.
- `TagChipRow` already renders `llmError` a second time, subdued (`onSurfaceVariant` @ 60%
  alpha) inline next to the chips, distinct from the louder duplicate in the parent sheet —
  i.e. the error string is currently shown **twice**, once loud once subdued, for the same
  string. This gets more confusing once we introduce distinct stalled/unsupported states.
- `TagChipRow` already has a loading-chip precedent to extend: a 16dp inline
  `CircularProgressIndicator` shown next to chips when `isLlmLoading && displaySuggestions.isEmpty()`.
  This is the natural anchor point for an escalating caption, not a new UI region.
- **The bug's actual mechanism** (`LlmTagProvider.suggestTags` → `MlKitLlmFormatterProvider.format`):
  `format()` calls `model.checkStatus()` exactly **once** per suggestion request. If status is
  `DOWNLOADABLE`, it fires `generateContent()` to kick off the AICore download as a side effect,
  then immediately returns `OnDeviceUnavailable("Downloading on-device model — this may take a
  few minutes", retryable = true)`. If status is `DOWNLOADING`, it returns a *different* string:
  `"On-device model is downloading — try again in a moment"` (also `retryable = true`). **Both
  of these distinct, already-differentiated strings get collapsed** in
  `LlmTagProvider.suggestTags` into `DomainError.NetworkError.RequestFailed(result.reason)` —
  the `retryable` boolean is dropped entirely on the way to `TagSuggestionState.llmError: String?`.
  There is no re-poll: the ViewModel (`TagSuggestionViewModel.requestSuggestions`) runs the LLM
  call exactly once per `requestSuggestions()` invocation and sets `llmPending = false` on
  completion — success or failure alike. Nothing re-checks status afterward. This is why the
  sheet freezes on one caption forever: architecturally it is not "stuck," it correctly reported
  a one-time result and stopped, because nothing told it to look again.
- `TagSuggestionState.Ready.llmPending: Boolean` already distinguishes "still working" from
  "done" at the type level, but collapses `DOWNLOADABLE` vs `DOWNLOADING` vs any other pending
  reason into the same boolean — there's no room today to show *why* it's pending without
  reusing the error channel, which is semantically wrong (pending is not an error).
- `LlmProviderAvailability.Preparing(val detail: String? = null)` (`llm/LlmProviderAvailability.kt`)
  already exists as a sibling type with exactly the "why is it pending" slot this feature needs
  — but it's not currently plumbed into `TagSuggestionState` at all; `TagSuggestionState` only
  ever sees the flattened `llmError` string or `llmPending` boolean, never the richer
  `Preparing(detail)` case. This is the natural extension point: threading `Preparing(detail)`
  through instead of collapsing to `RequestFailed(reason)` gives the UI everything it needs
  (cold-start vs already-downloading vs stalled vs unsupported) without inventing new types.
- `LlmProviderAvailability.Unavailable(reason, retryable: Boolean = false)` already models the
  FR-4 "genuinely unsupported device" case distinctly from `Preparing` — this is a *sibling
  sealed case*, not a string to pattern-match. If the sheet switches to consuming
  `LlmProviderAvailability` (or an equivalent typed pending-reason) instead of a flattened
  string, "no retry button for unsupported hardware" becomes a compile-time-adjacent
  `when` branch instead of a fragile string check.
- `TagSuggestionEngine.directMatch()` (referenced by the `GAP-003` comment in
  `TagSuggestionViewModel.kt:92`) already emits local chips synchronously, before the LLM call
  even starts — `requestSuggestions()` sets `localSuggestions` and pushes `Ready` state
  immediately, then updates `llmSuggestions`/`llmError`/`llmPending` asynchronously. So the sheet
  is **never actually empty** while the LLM tier is pending unless there happen to be zero local
  matches — the copy for the pending/stalled states must not imply "nothing found," only "more
  may be coming."
- Existing sibling precedent doc `project_plans/tag-suggestion-trigger/design/ux.md` (Surface 3,
  "Ready state with LLM still pending") already establishes the visual grammar for "local chips
  + inline spinner" and explicitly flags in Surface 4c that an empty-body Ready state is "a minor
  UX gap... logged here for awareness" — this project's stalled-state design should close that
  exact gap rather than leave it open a second time.
- No existing on-device-model-download UX precedent was found in `project_plans/mobile-voice-mode/`
  or `project_plans/voice/` (checked; neither directory has a ux.md addressing the AICore download
  wait specifically) — this is a first-of-its-kind caption/retry design in this codebase, not a
  copy of prior art.

## 1. Comparable UX patterns

The task is a **transient, sheet-scoped wait for a one-time background asset download with an
unknown-but-bounded duration** — narrower than a full app "optimizing" screen, closer to inline
progressive disclosure. Relevant patterns:

- **Progressive disclosure of wait-time** (the load-bearing pattern for FR-2): don't show one
  static caption for the whole wait. Stage the copy in at least three tiers tied to elapsed time,
  matching what the SDK already tells us:
  1. **0s (just started, cold)** — spinner + local chips already visible (existing behavior via
     GAP-003), caption: *"Downloading on-device model…"* (short, present-progressive, no ETA
     promised).
  2. **~45s (per FR-2, must change at least once)** — caption escalates to acknowledge elapsed
     time without alarming: *"Still downloading — this can take a few minutes the first time."*
     This is the same escalation IDEs use for "Indexing…" status bars (IntelliJ/VS Code shift from
     a bare spinner to "Indexing paused" / percentage text once the operation crosses a
     threshold) and what OS first-run "Optimizing apps" screens do by adding a
     count ("42 of 90") once available — the point isn't precision, it's *proof of life*: the copy
     itself changing is what tells the user the system hasn't hung, independent of whether a
     progress percentage is even obtainable (AICore's download API does not expose one here).
  3. **Past the FR-2 deadline (terminal "taking longer than expected")** — distinct visual
     treatment + the FR-3 retry button. Caption: *"Taking longer than expected."* with a
     secondary line and a `TextButton`/`OutlinedButton` "Retry" — mirrors "This is taking longer
     than usual" patterns from OS update screens and app-store "Retry download" affordances,
     which is the universally recognized escape hatch users look for once a spinner overstays.
- **ML Kit's own reference UX**: no first-party Compose reference UI was discoverable from
  in-repo prior research (checked `mobile-voice-mode` and `voice` project plans — neither
  addresses the AICore download wait). Google's own AICore/Gemini Nano sample surfaces
  (Android's system "Downloading AI features" notification-shade pattern, when the OS itself
  manages the download) use a determinate progress bar with byte count, but this app's
  `FeatureStatus.DOWNLOADING` value carries no percentage — so an indeterminate spinner +
  staged text remains the correct minimal-effort match to backend truth, not a fabricated
  progress bar.
- **Anchor point in existing code**: extend the existing inline 16dp `CircularProgressIndicator`
  in `TagChipRow` (next to the local chips) rather than introducing a second, separate spinner
  region in `SuggestionBottomSheet` — this keeps the "local results are the headline, LLM is a
  trailing enhancement" hierarchy that GAP-003 already established, and avoids a jarring layout
  where the sheet has two independent-looking loading indicators.

## 2. User mental model

- Users will not spontaneously know "this app ships its own on-device AI model that downloads on
  first use." The very first time this state is reached on a device, the copy must say **why**
  it's slow in terms the user already understands ("on-device AI model," "first time," "one-time
  download") — the existing string *"Downloading on-device model — this may take a few minutes"*
  already does this reasonably well and should be preserved as the cold-start (`DOWNLOADABLE`)
  caption rather than replaced.
- Distinguishing "never downloaded, starting now" from "already downloading from a previous
  session" is exactly what `FeatureStatus.DOWNLOADABLE` vs `FeatureStatus.DOWNLOADING` already
  encode at the SDK boundary (`MlKitLlmFormatterProvider.format()`), and what
  `LlmProviderAvailability.Preparing(detail: String?)` is built to carry through — the `detail`
  field is a free-text slot expressly for this kind of "which flavor of pending" distinction (see
  its doc comment: "Downloading or initializing"). Concretely: reuse the two SDK strings as the
  two `Preparing(detail=...)` values instead of collapsing both to one generic caption:
  - `DOWNLOADABLE` → "Downloading on-device model — this may take a few minutes" (implies: I'm
    the one who kicked this off, right now).
  - `DOWNLOADING` → "On-device model is downloading — try again in a moment" (implies: this was
    already in flight, e.g. from a previous suggestion request or app session; not started by
    this particular action).
  Reusing rather than inventing new copy here is a two-fold win — it's already been reviewed
  once (it's in production strings today) and it avoids a second engineer inventing a third,
  slightly-different phrasing for the same concept later.
- Once past the FR-2 threshold, the mental model shifts from "why is this slow" to "should I give
  up" — that's the job of the terminal "taking longer than expected" state (see §4), which is
  where the manual-typing escape hatch becomes the psychologically correct message, not more
  reassurance that it's still "almost done."

## 3. Accessibility

- **Escalating caption + TalkBack**: Do **not** wrap the caption text in an `AnimatedContent` or
  any construct that recomposes/re-renders on every parent recomposition of `Ready` state — if
  the caption `Text` node is torn down and rebuilt (or given a fresh `LiveRegion`/semantics node)
  on every recomposition rather than only when the *string value* actually changes, TalkBack will
  re-announce the same caption every time an unrelated poll/recomposition fires, which is worse
  than the current silent-freeze bug because now it interrupts the user repeatedly with a
  duplicate announcement. Guard: key the caption `Text` (or its state read) so recomposition is
  a no-op unless the caption string itself changed — e.g. drive it from a single `derivedStateOf`
  or a `remember(captionText) { }`-scoped composable so Compose's own equality check on `String`
  suppresses redundant semantics tree updates, and avoid `Modifier.semantics { liveRegion = ... }`
  churn tied to a ticking timer rather than to the text value.
  - Recommend `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` (not `Assertive`) on the
    caption `Text` specifically, scoped so it only fires when crossing a stage boundary (0s→45s,
    45s→terminal) — three total announcements over the whole wait, never a tick-by-tick timer
    readout. `Polite` avoids interrupting whatever the user is doing (they're expected to have
    moved focus back to typing the note) while still surfacing the state change if they swipe
    back to the sheet.
- **Retry button reachability**: the new FR-3 retry affordance must be a real focusable/clickable
  target (`IconButton`/`TextButton`/`Button`, not a clickable `Text` or `Box` with a raw
  `Modifier.clickable` lacking a `role`/`contentDescription`) so it's reachable via TalkBack swipe
  navigation, keyboard `Tab` (desktop/web targets), and switch access scanning. Give it an
  explicit `contentDescription`/label that names the action ("Retry downloading tags," not bare
  "Retry") since screen-reader users won't have the surrounding caption text read as a single
  unit with the button by default depending on grouping.
  - Group the terminal-state caption + retry button in a single semantics node
    (`Modifier.semantics(mergeDescendants = true)`) so TalkBack reads "Taking longer than
    expected, taking longer than expected, retry downloading tags button" as one coherent unit
    rather than two separately-focusable stops that don't obviously relate to each other.
- **Color contrast for the "taking longer than expected" treatment**: if a distinct warning color
  is introduced (e.g. `MaterialTheme.colorScheme.tertiary` or a custom amber), verify it against
  both the sheet's `surface` background *and* against `onSurface` at the standard WCAG AA
  4.5:1 text-contrast ratio — Material3's `error` color (already used for the current stalled
  text) is tuned for AA compliance by the theme, but a hand-picked "warning yellow" is not
  guaranteed to be; prefer reusing a Material3 role color (`error` continues to be defensible for
  "the wait has failed to resolve," or `tertiary` if the intent is explicitly "not an error, just
  slow") over a bespoke hex value, so contrast is inherited from the app's already-audited theme
  rather than needing a fresh manual check.
- FR-4 requires the retry button be **absent**, not merely disabled, for the genuinely-unsupported
  path (`Unavailable(reason, retryable=false)`). A disabled-but-visible button is worse for
  screen-reader users than an absent one — TalkBack would announce a target that can never be
  activated, which reads as broken rather than as "not applicable here." Gate the button's
  presence in the composable tree (an `if`, not `enabled = false`) on `retryable`.

## 4. Error/edge-case UX — target states for the sheet

Four situations, keyed off richer state than today's single `llmError: String?`:

| # | Condition (source of truth) | Local chips? | Caption | Retry button? |
|---|---|---|---|---|
| a | Within poll window, downloading (`Preparing(detail)`, elapsed < 45s) | Yes, if any (GAP-003) | SDK-sourced detail string ("Downloading on-device model…" / "…try again in a moment") | No — still auto-resolving, FR-1 says don't make the user do anything yet |
| b | Within poll window, elapsed ≥ 45s (`Preparing`, still pending) | Yes, if any | Escalated: "Still downloading — this can take a few minutes the first time." | No — still within bound, but see note below |
| c | Past deadline / stalled terminal state | Yes, if any | "Taking longer than expected." + secondary line, distinct (warning, not error) visual treatment | **Yes** (FR-3) |
| d | Genuinely unsupported device (`Unavailable(reason, retryable=false)`) | Yes, if any (still shown — this is not a failure of the local tier) | Reason string, plain/subdued (existing `onSurfaceVariant` treatment is fine — this is expected, not alarming) | **No** (FR-4 — must be visually and structurally absent, not disabled) |

Notes:
- Row (d) must **not** look like an error. Since it's expected/permanent for that device, treat it
  closer to the existing subdued inline treatment in `TagChipRow` than to the louder red `Text` in
  `SuggestionBottomSheet` today — the current code's louder duplicate red text is arguably already
  a mismatch for this case (it makes "device doesn't support this" look like a bug).
- Row (a)/(b) vs (c)/(d) is the retry-button hinge: FR-3 wants the button visible only once the
  system has stopped auto-resolving (stalled) or given up. Showing a retry button during (a)/(b)
  would contradict FR-1 (no user action needed while it's still plausibly working) and would
  invite users to spam retry during a download that's already progressing — the AICore download
  is a single job; re-triggering `generateContent()` mid-download does not speed it up and could
  reset it. So the retry button's whole reason to exist is exactly the boundary between "still
  trustworthy to wait" and "no longer trustworthy to wait" — which is the FR-2 deadline.
- Local suggestions remain tappable and rendered identically across all four rows — none of these
  states should touch `TagChipRow`'s chip-rendering logic, only the caption/affordance beneath it.
  Per GAP-003, copy must never claim "no suggestions yet" while local chips are present; word the
  captions around the *LLM tier specifically* ("on-device model," not "suggestions").

## 5. Job-to-be-done → visual priority of local chips

- **Functional** ("get the tag applied fast"): the local `directMatch()` chips are already the
  fast path — they resolve synchronously, before any LLM round-trip. During a download stall
  they are frequently the *only* usable result for potentially minutes. They should read as the
  primary, actionable content of the sheet; the download caption/retry affordance should read as
  a secondary status line beneath them, not compete for top billing. This is already roughly
  today's layout order (chips row, then caption below) — keep that order, just make the caption
  richer instead of moving it.
- **Emotional** ("don't want to feel like the app is broken/hung"): this is what FR-2's
  time-staged caption directly serves — a caption that visibly changes at 45s is proof-of-life
  even without a numeric progress bar. Pair this with *not* dimming/graying the existing chips
  during the wait (they're not stale, they're just as valid as when the sheet opened) — dimming
  the whole sheet body while the LLM tier is pending would read as "everything is broken," when
  in fact only the LLM tier is pending.
- **Social/workflow** ("don't want to lose their place mid-note waiting on a spinner"): the sheet
  is already dismissible while the LLM job continues in the background
  (`TagSuggestionViewModel.dismiss()` explicitly does not cancel `suggestionJob`, and results are
  cached per-block so reopening shows the cached/updated state instantly). The new stalled-state
  copy should make this *escape hatch discoverable*, not just structurally present — e.g. the
  terminal-state secondary line can explicitly suggest the manual path: "You can keep typing the
  tag, or wait for suggestions." This directly serves the workflow JTBD by naming the option the
  architecture already supports (dismiss-and-keep-typing) instead of leaving the user to infer it
  from an X button that looks like "give up on tags entirely" rather than "close this, I'll
  finish typing the tag myself."
- **Implication for chip prioritization**: local chips should NOT be visually deprioritized (no
  greying, no secondary color, no smaller type) relative to eventual LLM chips — they are already
  functionally first-class per GAP-003's intent ("skip waiting for LLM"), and a stalled LLM tier
  is exactly the scenario where local chips matter most. Any future visual differentiation between
  "local" and "LLM" chip *sources* (not in scope here) should wait for a dedicated design pass;
  for this fix, treat all rendered chips uniformly and put all the new visual weight into the
  caption/retry region instead.

## Summary of concrete UX recommendations for the plan phase

1. Thread `LlmProviderAvailability` (or an equivalent typed reason — `Preparing(detail)` /
   `Unavailable(reason, retryable)`) through `TagSuggestionState` instead of flattening to
   `llmError: String?`, so the sheet can `when`-branch on cold-start vs already-downloading vs
   stalled vs unsupported without string-matching.
2. Reuse the two existing SDK strings ("Downloading on-device model — this may take a few
   minutes" / "On-device model is downloading — try again in a moment") as the `Preparing(detail)`
   values for `DOWNLOADABLE`/`DOWNLOADING` respectively — don't invent new copy for the cold path.
3. Add a time-staged caption escalation at ~45s ("Still downloading — this can take a few
   minutes the first time.") driven by elapsed time since the request started, not by SDK status
   changes (the SDK gives no percentage).
4. Add a distinct terminal "taking longer than expected" state past the FR-2 deadline, with a
   real focusable retry button (visible only when the underlying `retryable` flag is true), a
   `mergeDescendants` semantics group, and a `LiveRegionMode.Polite` announcement fired only on
   caption-text change (not per recomposition).
5. Keep the unsupported-device row visually calm (subdued, not error-red) and structurally
   omit — not disable — the retry button when `retryable == false`.
6. Never imply the sheet is empty while `directMatch()` local chips are present; word all new
   captions around "on-device model" specifically, and keep local chips visually uniform/
   first-class throughout every state.
