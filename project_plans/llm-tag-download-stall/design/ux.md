# UX Design: On-Device LLM Tag Suggestion — Download Stall

SDD Phase 3 design gate. Validates/refines `research/ux.md`'s design against the concrete
Epic 5 implementation in `implementation/plan.md` (Story 5.1 `TagChipRow` signature change,
Story 5.2 `SuggestionBottomSheet` caption/retry rendering). This is not a fresh design —
it is a check of the plan's actual Compose code against the research's requirements, plus
the deliverables requested for the review gate (wireframes, flows, testable UX AC).

## Step 1 — Surface inventory

**One surface, five states.** The research's premise is confirmed: everything lives in the
caption/retry region of `SuggestionBottomSheet`, beneath the `TagChipRow` chip row, driven
by a single `LlmSuggestionStatus` sealed value on `TagSuggestionState.Ready`
(`LlmSuggestionStatus.Pending(caption)` covers both row a and row b — same branch, only the
caption string differs — `Stalled(retryable)` is row c, `Failed(message, retryable)` covers
row d (`retryable = false`, e.g. a genuinely-unsupported device) **and** row e (`retryable =
true`, e.g. a `DomainError.NetworkError.Timeout` — see Step 2's Row (e), added by this
review pass to close a documentation gap: `Failed.retryable` is a real, reachable field as of
the Phase 3 repair pass, not the dead code an earlier draft of this document described — see
Step 4's updated Flag 1). No new screen, dialog, or sheet is introduced.

Two additional things surfaced during this review that are **not** new designable surfaces,
but are worth recording as scope boundaries so the review gate doesn't miss them:

1. **No transition/animation moment needs its own design.** `LlmSuggestionStatus.Pending`
   renders through one `Text` composable whose `text=` argument changes (a→b) or whose
   `when` branch changes (b→c is `Pending`→`Stalled`, a different Composable subtree
   entirely). Plan Task 5.2.2 doesn't wrap any of this in `AnimatedContent`/`Crossfade`,
   which is *correct* per research's explicit accessibility guidance ("do not wrap the
   caption in `AnimatedContent`... TalkBack will re-announce on every recomposition") —
   confirming the plan avoided a trap the research called out, not that it missed
   something.
2. **`TagSuggestionState.Error` is a distinct, pre-existing top-level state that this
   feature does not touch and that the review gate should know about.** Separately from
   `Ready.llmStatus`, `TagSuggestionViewModel` has a top-level `TagSuggestionState.Error`
   (set on an unrelated internal exception — `TagSuggestionViewModel.kt:48`).
   `SuggestionBottomSheet`'s `isVisible` check (`state is Ready || state is Loading`) means
   this state renders **no sheet at all** — a silent close, not a caption. Epic 5 does not
   modify this branch (the plan's `when (state)` still has `else -> Unit`), so it is
   unaffected by this feature and out of scope to redesign — but it is a genuine dead end
   (no message, no retry, sheet just doesn't appear) that predates this project and should
   not be conflated with the five rows below during review.

## Step 2 — Per-state wireframes and flows

All five rows share this ASCII frame; only the region below the divider line changes.
`TagChipRow`'s `displaySuggestions` = `state.localSuggestions + state.llmSuggestions`
filtered to non-auto-applied — local chips are present in every row per GAP-003 and are
never dimmed (confirmed in Step 4).

```
┌───────────────────────────────────────────────────┐
│ Suggested tags for this block                  [X]│  ← header, always present, X = onDismiss
├───────────────────────────────────────────────────┤
│ [Kotlin] [Q3-Planning] [Meeting]  ← ← ← scrollable │  ← TagChipRow (local chips, GAP-003)
│ ───────────────────────────────────────────────── │
│  <caption/retry region — varies by row below>      │
└───────────────────────────────────────────────────┘
```

### Row (a) — downloading, cold start (`Pending(caption)`, elapsed < 45s)

```
│ [Kotlin] [Q3-Planning]                        (no spinner — chips present)
│ Downloading on-device model — this may take a few minutes
```
(If zero local chips matched: `TagChipRow` shows a 16dp inline spinner next to the empty
row per the existing GAP-003 precedent — untouched by this plan.)

**Flow**: user types a tag trigger → sheet opens → local chips render synchronously →
`requestSuggestions()` fires the LLM path → `format()` returns `OnDeviceUnavailable`
(`DOWNLOADABLE`) → `runLlmSuggest` calls `onStatusUpdate(Pending("Downloading on-device
model…"))` → caption appears beneath the chips. User does nothing; system polls
`checkAvailability()` every 3–5s in the background (FR-0), invisibly to the UI (no visual
change per tick — only a value change triggers recomposition of the `Text`, and identical
strings across ticks don't even do that).

**System response / no action required from user.** No retry button (row a/b are
"auto-resolving," per research: showing Retry here would invite users to spam-retry a
download that's already progressing).

### Row (b) — downloading, escalated (`Pending(caption)`, elapsed ≥ 45s)

```
│ [Kotlin] [Q3-Planning]
│ Still downloading — this can take a few minutes the first time.
```

**Transition into this row**: purely a caption-string swap inside the *same*
`LlmSuggestionStatus.Pending` branch — `TagAvailabilityPoller` (Epic 3) updates
`onStatusUpdate` with the escalated string at the 45s mark while `checkAvailability()`
keeps returning `Preparing`. No layout change, no button appears/disappears — the only
observable change is the text content, which is exactly the "proof of life without alarm"
job this row does (research §1). `LiveRegionMode.Polite` (Task 5.2.1) announces the new
string to TalkBack at this boundary — the one and only announcement in this row's lifetime
besides the initial one from row (a).

### Row (c) — stalled / terminal (`Stalled(retryable = true)`, poll deadline reached)

```
│ [Kotlin] [Q3-Planning]
│ ┌─────────────────────────────────────────────┐
│ │ Taking longer than expected.                 │  ← tertiary color, LiveRegion.Polite
│ │ Tap Retry to check again, or keep typing the  │  ← onSurfaceVariant, secondary line
│ │ tag yourself.                                 │
│ │                                    [ Retry ]  │  ← TextButton, focusable, contentDescription
│ └─────────────────────────────────────────────┘  ← Column, mergeDescendants=true
```

**Copy fix (Phase 4 triad-review gap)**: the secondary line no longer reads "suggestions
will appear if the model finishes" — per ADR-001/plan.md, the poll loop actively STOPS
polling once it reaches this terminal `Stalled` state (it does not keep silently checking in
the background past its own `DEFAULT_POLL_DEADLINE_MS`), so the old copy implied ambient
auto-resolution that isn't accurate: nothing will make the suggestion appear from this point
without the user tapping Retry (which restarts a bounded poll attempt). The corrected line
names the two real options — retry, or type the tag manually — matching what the system
actually does.

**Transition into this row**: `TagAvailabilityPoller.pollUntilAvailable` reaches
`DEFAULT_POLL_DEADLINE_MS` (Epic 0's measured value) still seeing `Preparing` →
`runLlmSuggest` maps the resulting `Unavailable(retryable=true)` into
`DomainError.NetworkError.RequestFailed(reason, retryable=true)` →
`requestSuggestions()`'s `ifLeft` branch maps a `retryable=true` failure to
`Stalled(retryable=true)`. This is a distinct `when` branch (`Stalled`, not `Pending`) —
visually and semantically different per AC2's requirement (own `Column`, own color role,
own second line, own button), not just a third caption string in the same branch as
rows a/b.

**User does**: taps "Retry." **System responds**: `onRetry()` → `retryLastRequest()` →
re-invokes `requestSuggestions()` with the cached `LastRequest` args → cache-hit branch
sees `Stalled` is not terminal → cancels nothing (no job was running) → launches a fresh
`suggestionJob`.

**Updated for the pre-mortem P1 #1/#2 fix** (`downloadFirstObservedAtMs`, plan.md Epic 4
Task 4.1.1/4.1.2 — this text previously described retry as unconditionally "loops back to
row (a)... poll restarts from tick 0," which is no longer accurate): the new attempt's
caption depends on how much session-tracked time has elapsed since the model was *first*
observed downloading, not since this particular retry tap. If that elapsed time is already
past the 45s escalation threshold (the common case for a retry that follows a genuine
`Stalled`), the state goes straight to row (b)'s escalated caption, then — since a
`Stalled` retry's elapsed time is by definition already at or past the 120s deadline —
almost immediately back to row (c) itself, **not** a fresh cold-start row (a). Only a retry
that happens to land *before* the 45s threshold (unlikely in practice, since reaching row
(c) at all requires having already passed 120s) would show row (a) first. This is the
direct fix for pre-mortem P1 #2: repeated retries against a genuinely-still-downloading
model read as "still stalled," not as a misleadingly-reset "just started downloading again."
See plan.md's Task 4.2.4 and Story 4.6 for the concrete behavior and regression tests.

**Error/edge-case handling — exit path beyond Retry**: the header `[X]` Dismiss button is
always rendered (outside the `when` block, Line ~62 of `SuggestionBottomSheet.kt`), so a
user who doesn't want to wait or retry can dismiss and keep typing the tag manually — this
is the "social/workflow JTBD" escape hatch research §5 calls out, and it is *structurally*
guaranteed to exist for row (c) because it's part of the always-rendered header, not
something Story 5.2.2 needs to add per-row. **No dead end.**

### Row (d) — genuinely unsupported device (`Failed(message, retryable = false)`)

```
│ [Kotlin] [Q3-Planning]
│ On-device AI is not supported on this device
```
(Plain `onSurfaceVariant` text, no button, matching research's "must not look like an
error." **Corrected during the Phase 4 triad-review BLOCKER fix**: this row's message
`Text` DOES carry `LiveRegionMode.Polite` semantics as of Task 5.2.2 — an earlier draft of
this document under-described the `Failed` branch as having "no Column wrapper, no
LiveRegion," which was accurate only for the LiveRegion piece by omission (the code never
excluded it deliberately; nothing announced this row's transition, which was the BLOCKER),
not by design. The `Failed` branch's `Column`/`mergeDescendants` wrapper exists in the code
identically for rows (d) and (e); the ASCII wireframe above simply omits the box-drawing
used for row (c)/(e) to keep row (d)'s no-button case visually distinct. Now every
transition into `Failed` — retryable or not — is announced to TalkBack via LiveRegion on
the message text, matching `Pending`/`Stalled`'s existing treatment. See Step 4's
accessibility note below and plan.md's Task 5.2.2/5.2.5.)

**Transition into this row**: `format()`/`checkStatus()` returns a non-retryable
`Unavailable` on the *first* attempt → `runLlmSuggest`'s `failure.retryable == false` guard
returns immediately with **no poll loop started at all** (FR-4) → `Failed(message,
retryable=false)` is set directly, skipping rows (a)/(b)/(c) entirely for this device.

**Error/edge-case handling — exit path**: same always-present header `[X]` Dismiss button.
Per FR-4/research, no Retry button should ever appear here since retrying cannot change a
hardware capability — its *absence* is the correct, intentional design, not a gap, as long
as Dismiss remains available (confirmed it does). **No dead end**, but see Step 4 finding #2
below for a related gap in how `Failed` is used for *other* failure types.

### Row (e) — retryable hard failure (`Failed(message, retryable = true)`, e.g. `DomainError.NetworkError.Timeout`)

**New in this review pass** — this state exists in Epic 5's actual code (Task 4.2.1 /
5.2.2 / 5.2.5) but previously had zero wireframe/UX-criteria coverage in this document. See
Step 4's updated Flag 1 for why: `Failed.retryable` was dead code (always `false`) when this
document was first drafted, so this row could not occur; the Phase 3 repair pass
(adversarial-review Blocker C) made it reachable for `DomainError.NetworkError.Timeout`.

```
│ [Kotlin] [Q3-Planning]
│ ┌─────────────────────────────────────────────┐
│ │ LLM tag suggestion timed out after 90s        │  ← onSurfaceVariant, calm (not error),
│ │                                                │    LiveRegion.Polite (Phase 4 fix)
│ │                                    [ Retry ]  │  ← TextButton, focusable, contentDescription
│ └─────────────────────────────────────────────┘  ← Column, mergeDescendants=true
```

**Transition into this row**: a hard provider failure unrelated to on-device model
availability — e.g. a completed-but-slow network round-trip mapped to
`DomainError.NetworkError.Timeout` — is deliberately kept out of `Stalled` (plan.md's Pattern
Decisions row "Which retryable `DomainError`s become `Stalled` vs a retryable `Failed`?")
because `Stalled`'s "still downloading" framing would be misleading for a condition that has
nothing to do with a model download. `requestSuggestions()`'s `ifLeft` branch (Task 4.2.1)
maps `DomainError.NetworkError.Timeout` specifically to `Failed(message = err.message,
retryable = true)` — every other non-`RequestFailed.retryable` error still gets
`Failed(retryable = false)` (row d).

**User does**: taps "Retry." **System responds**: identical wiring to row (c)'s Retry
handling — `onRetry()` → `retryLastRequest()` → re-invokes `requestSuggestions()` with the
cached `LastRequest` args. (Unlike row (c), a `Failed` retry is a genuinely fresh attempt —
`downloadFirstObservedAtMs` tracking is specific to the on-device-availability/`Stalled`
path and is untouched by a `Timeout`-caused `Failed` state.)

**Error/edge-case handling — exit path**: same always-present header `[X]` Dismiss button,
plus the Retry `TextButton` described above — **two** exit paths, the same treatment as row
(c) (both retryable), not row (d)'s single-exit treatment (row (d) is not retryable). **No
dead end.**

## Step 3 — UX acceptance criteria (testable)

1. **Task completion, fast path**: when the on-device model is already `AVAILABLE`, a user
   sees LLM suggestions appear with **zero additional taps** beyond opening the tag-trigger
   sheet — no intermediate caption, no spinner beyond the pre-existing local-match render.
   (Validates AC4 fast path, Task 4.2.5.)
2. **Task completion, retry path**: from the stalled state (row c), a user can restart the
   download attempt in **exactly 1 tap** ("Retry"), with no confirmation dialog or extra
   step. (Validates AC3.)
3. **Proof-of-life without user action**: during a download that resolves within the poll
   deadline, a user who takes no action sees the caption text change **at least once**
   (cold → escalated, or cold/escalated → resolved chips) without touching the sheet.
   (Validates AC0/AC1/FR-2's "never one frozen string" requirement.)
4. **Error state shows a specific message and a specific action**: row (c) shows the literal
   string "Taking longer than expected." plus a secondary line naming the manual-typing
   alternative, and offers a real `TextButton` labeled "Retry" (`contentDescription =
   "Retry downloading tags"`). Row (e) shows the `DomainError.NetworkError.Timeout` message
   verbatim and offers the same "Retry" `TextButton`. Row (d) shows the SDK-sourced
   unsupported-device reason string and offers no button (its correct, intentional state).
5. **No dead ends** — every state has an exit path:
   - Row (a)/(b): implicit exit via the always-present header Dismiss `[X]`; no explicit
     affordance needed since the system is still auto-resolving (by design, per FR-1).
   - Row (c): **two** exit paths — Retry (resume the download attempt) and header Dismiss
     (abandon LLM tier, keep typing manually). Both present. **Confirmed, not a gap.**
   - Row (e): **two** exit paths, same treatment as row (c) — Retry (re-attempt the request)
     and header Dismiss. **Confirmed, not a gap.** This is the concrete implementation of
     AC3's "stalled/failed" wording covering an actual `Failed`-state retry path, not only
     `Stalled`.
   - Row (d): **one** exit path — header Dismiss (no Retry, correctly). Local chips remain
     tappable throughout, so the user is never blocked from finishing the tag with a local
     match even if the LLM tier offers nothing. **Confirmed, not a gap.**
   - Adjacent pre-existing state `TagSuggestionState.Error` (Step 1, item 2): **no exit
     path and no message** — the sheet simply fails to appear. This is out of this
     project's scope to fix (Epic 5 doesn't touch it), but it should be logged as a known
     gap for a future pass, not silently ignored, since "no dead ends" as a general
     principle is violated by it even though this feature isn't the one introducing it.
6. **Keyboard/switch-access navigable**: the Retry `TextButton` in row (c) is a real
   focusable `Button`-family composable (not a clickable `Text`/`Box`), reachable via
   TalkBack swipe, desktop `Tab`, and switch-access scanning — satisfied by Task 5.2.2's
   use of `TextButton`.
7. **Screen-reader labels present and grouped correctly**: row (c)'s heading, secondary
   line, and Retry button are wrapped in one `Modifier.semantics(mergeDescendants = true)`
   `Column` (Task 5.2.2), so TalkBack reads them as one coherent unit rather than three
   unrelated stops — satisfied as written. Rows (d)/(e) share the same
   `mergeDescendants = true` `Column` treatment for grouping.
   **Announce-on-transition, corrected during Phase 4 triad review**: every caption/message
   state in this design must announce itself to TalkBack when it first appears, not merely
   render silently and rely on the user finding it — `Pending` (rows a/b) and `Stalled` (row
   c) always had `LiveRegionMode.Polite` on their text (Task 5.2.1/5.2.2); `Failed` (rows
   d/e) did not, which was a real gap (a background transition into `Failed` — e.g. a
   `Timeout` firing while the sheet wasn't in focus, or a poll resolving to
   unsupported-device — went silently unannounced). Task 5.2.2 now applies
   `LiveRegionMode.Polite` to the `Failed` branch's message `Text` unconditionally (both rows
   d and e, independent of whether the Retry button also renders), so all five caption states
   are consistent on this principle. Task 5.2.5 carries the test assertion for both
   sub-cases.
8. **Retry is structurally absent, not disabled, when unsupported**: row (d) never renders
   a `TextButton` — enforced by the same `if (status.retryable)` gate that both `Stalled`'s
   branch (row c) and `Failed`'s branch (rows d/e) share; `Failed`'s branch renders the
   button when `retryable == true` (row e) and omits it entirely when `retryable == false`
   (row d) — it is not a case of `Failed` never rendering a button at all. Verified against
   the actual code (Task 5.2.2), not assumed.
9. **Color contrast ≥ 4.5:1 for all caption text** — see Step 4 findings below; two of the
   plan's three color choices need a concrete check before this criterion can be marked
   satisfied, not assumed satisfied because "Material3 role colors are theme-safe."

## Step 4 — Consistency check against `research/ux.md` and concrete findings

### Confirmed consistent

- **Local chips stay visually first-class, never dimmed.** Story 5.2.2's `Ready` branch
  code does not touch `TagChipRow`'s chip-rendering logic at all — it only changes what
  renders *beneath* the row (the `when (status)` block). `TagChipRow` itself (Story 5.1.1)
  only changes its early-return guard and drops the `llmError` text block; the
  `FilterChip`/`LazyRow` rendering of `displaySuggestions` is untouched. This is correct
  and consistent with research §5's requirement that chips never grey out or lose priority
  during a stall. **No redesign occurred here — confirmed as intended, not a gap.**
- **Row a/b share one branch, not two** — matches research's model of "caption escalates,
  state doesn't" (one `Pending` case, changing only its `caption` field).
- **Row d kept calm (`onSurfaceVariant`, not `error`)** — matches research's explicit
  instruction that the unsupported-device case "must not look like an error."
- **Retry button structurally gated (`if`, not `enabled=false`)** — matches research's
  accessibility requirement exactly.
- **No `AnimatedContent`/live-region churn per recomposition** — matches research's
  TalkBack guidance (see Step 1).

### Flags for the review gate

**Flag 1 (RESOLVED as of the Phase 3 repair pass — verify only, not a live finding) —
`LlmSuggestionStatus.Failed.retryable` was dead code when this document was first drafted;
it no longer is.** At the time this ux.md was originally written, tracing the wiring in
Epic 4 (`requestSuggestions()`'s `ifLeft` branch, Task 4.2.1) showed:
```kotlin
// (superseded — this was the wiring at the time this Flag was first written)
val retryable = (err as? DomainError.NetworkError.RequestFailed)?.retryable ?: false
val status = if (retryable) LlmSuggestionStatus.Stalled(retryable = true)
             else LlmSuggestionStatus.Failed(message = err.message, retryable = false)
```
i.e. every retryable failure became `Stalled`, and `Failed` was *always* constructed with
`retryable = false` — hardcoded, not read from `err` — making `LlmSuggestionStatus.Failed.retryable`
a field the UI never needed to read, because the ViewModel never sent `true` down that path,
and row (e) (below) did not exist.

**This gap has since been closed.** The Phase 3 repair pass (adversarial-review Blocker C —
see plan.md's Pattern Decisions row "Which retryable `DomainError`s become `Stalled` vs a
retryable `Failed`?" and the actual `when` block in Task 4.2.1) now maps
`DomainError.NetworkError.Timeout` specifically to `Failed(message = err.message, retryable =
true)` — a real, reachable, non-dead value — while every other non-`RequestFailed.retryable`
error still gets `Failed(retryable = false)`. `SuggestionBottomSheet`'s `Failed` branch (Task
5.2.2/5.2.5) now conditionally renders a retry button `if (status.retryable)`, exactly
mirroring the `Stalled` branch's treatment. This document's **Row (e)** (Step 2, new in this
review pass) documents that state's wireframe/UX-criteria coverage, which did not previously
exist here — closing the coverage gap this Flag originally warned about.

**One part of the original finding remains a genuine, still-open, still-out-of-scope gap —
not resolved by the repair pass and not newly introduced by it either**: hard provider
failures for non-on-device paths (cloud/custom OpenAI-compatible provider HTTP errors,
content rejection) still land in `Failed(retryable = false)` — only
`DomainError.NetworkError.Timeout` was upgraded to `retryable = true`, not every
retryable-in-principle cloud failure. A cloud-provider HTTP error still renders identically
to row (d): a plain message, no Retry button, no visual distinction from "this device can
never do this." This remains explicitly out of scope per `requirements.md` ("out of scope:
changing `LlmTagProvider`'s per-request 90s `withTimeout` semantics for providers other than
on-device") and is unchanged by this edit — recorded here as a still-open follow-up gap for
whenever that out-of-scope work is picked back up, not a blocker for this project.

**Flag 2 — `tertiary` color contrast is asserted by research to need verification, and this
review did that verification: it is not sourced from this app's custom theme at all.**
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt`'s `LightColorScheme`,
`DarkColorScheme`, and `StoneColorScheme` all call `lightColorScheme(...)` /
`darkColorScheme(...)` with `primary`, `onPrimary`, `background`, `onBackground`, `surface`,
`onSurface`, `surfaceVariant`, `onSurfaceVariant` explicitly overridden with the app's
custom stone/parchment palette (`Color.kt`) — but **`tertiary` is never set**, so it falls
back silently to Material3's unmodified baseline default (`#7D5260` light / `#EFB8C8` dark),
a color that was never designed against this app's custom surfaces. Computing WCAG relative
luminance by hand from the actual hex values in the repo:
  - Light: default tertiary `#7D5260` on `ParchmentBackground` (`#F5F0E8`) ≈ **5.7:1** — passes
    AA (≥4.5:1).
  - Dark/Stone: default tertiary `#EFB8C8` on `StoneBackground` (`#282828`) ≈ **8.7:1** —
    passes AA comfortably.
  These estimates are hand-computed from the exact hex constants in `Color.kt`/`Theme.kt`
  using the standard sRGB relative-luminance formula, not measured with a contrast tool —
  **the review gate should still run these three color pairs through an actual contrast
  checker before treating this as settled**, both because a hand computation can have
  arithmetic error and because Material3's actual resolved default tertiary hex should be
  confirmed against the installed Compose Material3 version rather than assumed from memory.
  Net: likely fine, but "likely fine, hand-verified once" is a materially different claim
  than research's original "Material3 role colors are theme-safe" assumption — flag it as
  checked-but-not-tool-verified, not as clear.

**Flag 3 — `onSurfaceVariant` (used for row (d)'s message, row (e)'s message, and row (c)'s
secondary line) is the one color in this plan that does *not* pass the 4.5:1 bar on the
app's own custom palette, by the same hand computation.** Research assumed "the existing
`onSurfaceVariant` treatment is fine" without checking; this review checked it:
  - Light: `AgedStone` (`#7C7369`) on `ParchmentBackground` (`#F5F0E8`) ≈ **4.10:1**.
  - Dark/Stone: `WornStone` (`#928374`) on `StoneBackground` (`#282828`) ≈ **4.02:1**.
  Both are **below** the 4.5:1 AA threshold for normal-size text, and `labelSmall` (used for
  all three) is well under the WCAG "large text" size exemption (≥18pt/24px, or ≥14pt bold)
  that would otherwise drop the bar to 3:1. This is a **pre-existing condition** — `onSurfaceVariant`
  is already used today in `TagChipRow`'s current `llmError` rendering (at `alpha = 0.6f`,
  which is *worse* than the full-alpha ~4.1:1 this plan moves to) — so Epic 5 does not
  introduce this problem and arguably improves it slightly by dropping the alpha modifier,
  but it also does not fix it, and this project is the first to newly rely on
  `onSurfaceVariant` for rows (d)/(e) *and* the row (c) secondary line simultaneously,
  widening its use. Recommend the review gate treat this as: acceptable to ship (visual
  redesign is explicitly out of scope per `requirements.md`), but log it as a known
  contrast gap for the app's color system generally, not specific to this feature, so it
  doesn't get lost.

## Summary

- **Surfaces designed**: 1 (the `SuggestionBottomSheet` caption/retry region), **5 states**
  (a/b/c/d + new row (e)) — confirmed complete against `research/ux.md`; one adjacent
  pre-existing surface (`TagSuggestionState.Error`'s silent sheet-close) identified as
  explicitly out of scope and flagged for future awareness, not redesigned here.
- **UX acceptance criteria written**: 9 (Step 3), each independently testable by a human
  against the running app or the Compose test tree.
- **Inconsistency with Epic 5's actual code found — updated after re-review**: this document
  originally reported three flags. (1) chip rendering is correctly untouched, confirmed
  *consistent*, not a flag. **(2) `Failed.retryable` — was reported as dead code; this is no
  longer true.** The Phase 3 repair pass made `Failed(retryable = true)` reachable for
  `DomainError.NetworkError.Timeout`; this document's Step 2 now has a Row (e) documenting
  that state's wireframe/UX-criteria coverage, which did not previously exist here. The
  narrower, still-open, still-out-of-scope gap is that non-timeout cloud/HTTP failures remain
  `Failed(retryable = false)` — see Flag 1's updated text. (3) `tertiary` is unset in the
  app's theme (falls back to Material3 default) — hand-verified as passing AA contrast but
  not tool-verified, so it should not be treated as "obviously theme-safe"; (4)
  `onSurfaceVariant` on this app's actual custom palette measures ~4.0–4.1:1, under the
  4.5:1 AA bar, in both light and dark/stone themes — a pre-existing gap this project widens
  the use of (now across rows (c)/(d)/(e)) rather than introduces.
