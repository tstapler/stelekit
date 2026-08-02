# ADR-001: Interim `DEFAULT_POLL_DEADLINE_MS` Estimate (Desk Research, Not Real-Hardware Measured)

**Status**: Accepted (interim — see Follow-up)
**Date**: 2026-07-29

## Context

FR-6/AC6 requires `DEFAULT_POLL_DEADLINE_MS` — the wall-clock bound after which the
tag-suggestion poll loop (FR-0/FR-2) gives up on `checkAvailability()` returning
`Available` and surfaces the "taking longer than expected" terminal state — to be derived
from a real on-device AICore first-download timing measurement on physical hardware, not
an unvalidated guess.

**This planning session has no physical AICore-capable Android device available**
(Pixel 9+ or equivalent OEM flagship with AICore). A literal hardware measurement per the
methodology `research/build-vs-buy.md` recommends (app-side `Logger` transitions +
`adb logcat -s AiCoreService:* GenerativeAIService:*` on real hardware) cannot be
performed.

**Attribution correction**: `requirements.md`'s FR-6/AC6 text itself contains no fallback
clause — it states the measurement "must happen... on physical hardware" with no exception.
An earlier draft of this ADR (and of plan.md's Epic 0) incorrectly described the desk-research
substitution below as done "per the requirements' explicit fallback instructions." That is
false: the fallback instructions were given directly to the planning subagent as special
session context by the coordinator during Phase 3 dispatch — that context is not part of
`requirements.md` and was never authorized by it. The honest framing is: this planning
session had no physical AICore-capable device available, so a desk-research-based interim
estimate was substituted as a pragmatic necessity, not because `requirements.md` authorized a
fallback. Read strictly, `requirements.md`'s FR-6/AC6 therefore remain only **partially**
satisfied by this ADR — a sourced, documented interim value exists; a real physical-hardware
measurement does not yet exist. This ADR's "Follow-up (mandatory)" section below is the
acknowledgment of that gap, not an optional nice-to-have.

With that gap acknowledged, this ADR substitutes desk research (WebSearch/WebFetch against
Google's own documentation and secondary developer sources) and records a reasoned interim
value.

### Sources consulted

1. **`developer.android.com/ai/gemini-nano`** (official Android AI docs) — architectural
   only; states AICore "manages the distribution of Gemini Nano... you don't need to
   worry about downloading" but gives no size/duration numbers.
2. **`developers.google.com/ml-kit/genai/prompt/android/get-started`** (official ML Kit
   Prompt API docs) — defines `AVAILABLE`/`DOWNLOADABLE`/`DOWNLOADING`/`UNAVAILABLE`
   status semantics. Its only timing figure ("usually takes a few minutes to a few hours
   to update... restarting the device can speed up the update") is scoped to AICore's own
   *configuration* refresh, not the Gemini Nano *model* download — noted here to avoid
   misattributing it, but not used as the basis for the estimate below.
3. **`developers.google.com/ml-kit/genai/aicore-dev-preview`** (official AICore Developer
   Preview docs) — the most directly relevant official source: "models are relatively
   large and must be downloaded over Wi-Fi," "downloading models can take a few minutes,"
   and separately "the very first inference might take about a minute" for in-memory model
   load after download completes.
4. **Secondary developer sources** (Local AI Master's Gemini Nano Android guide; a Medium
   walkthrough by Yassine Beldi) — report Gemini Nano model sizes in the ~1 GB
   (Nano 1, older/lower-tier devices) to ~4.2 GB (Nano 3, newer flagship-tier base model,
   fetched over Wi-Fi) range, and cite **15–30 minutes on Wi-Fi** for a first-time
   download of the larger variant. These are not Google-authored and are treated as
   corroborating, not authoritative.
5. **Existing in-repo signal**: `MlKitAvailabilityMapping.kt` (already shipped, written by
   an earlier project on this same feature) already surfaces the `Preparing` detail string
   *"On-device model is downloading — this can take 15–30 minutes on first use"* to users
   today. This is independent internal corroboration of the secondary-source 15–30 minute
   figure — a previous contributor arrived at the same range from presumably similar
   research, and that copy is explicitly kept unchanged by this project (`research/ux.md`
   row (a) — reuse existing strings, don't invent new copy for cold start).

### Reasoning

Two different quantities are in play and must not be conflated:

- **Total model download time** (device-storage-to-model-ready): the copy already shown
  to users says 15–30 minutes, corroborated by secondary sources. This is *not* what
  `DEFAULT_POLL_DEADLINE_MS` should equal — it is a background, unattended process (the
  user is not expected to sit staring at a bottom sheet for half an hour), and FR-5/ADR-002
  already establish that the poll loop is allowed to keep running in the background after
  the user dismisses the sheet.
- **Interactive poll-loop deadline** (`DEFAULT_POLL_DEADLINE_MS`): how long the *sheet's
  own active polling* should keep re-checking before giving up and handing control back to
  the user via the FR-3 manual-retry affordance. This should be long enough to catch the
  common fast-resolving case within one sitting (official docs' "a few minutes" framing for
  the download step itself, plus the documented ~1-minute first-inference/model-load cost
  once the download completes) without leaving the sheet in an actively-polling state for
  the full 15–30 minute worst case, which would be pointless (no user stays on one journal
  entry that long) and wastes battery/CPU on a foreground poll loop.

## Decision

Set `DEFAULT_POLL_DEADLINE_MS = 120_000L` (2 minutes) as the **interim** value.

Rationale for the specific number: 2 minutes covers the official "a few minutes" download
guidance for the common case plus the ~1-minute post-download initialization cost, at
roughly 30 poll ticks at the chosen `DEFAULT_POLL_INTERVAL_MS = 4_000L` (midpoint of the
3–5s FR-0 range). It is short enough that a user who keeps the sheet open sees a
resolution or a clear "taking longer than expected, tap Retry" outcome within a plausible
attention span, and — per ADR-002 — does not lose progress on the (much longer) actual
background download, since the loop simply stops actively re-checking, it does not cancel
whatever AICore is doing.

`CAPTION_ESCALATION_THRESHOLD_MS = 45_000L` (fixed by requirements, not re-derived here)
sits comfortably inside this window, giving the mid-wait caption change room to be seen
before the terminal state at 120s.

## Consequences

**Positive**: FR-6/AC6 has a documented, sourced, non-arbitrary number instead of a bare
guess; the reasoning explicitly separates "total download time" from "interactive poll
window," which future maintainers can reuse if either changes independently.

**Negative/Risks**: This value is **not validated against real AICore hardware**. If
actual first-download time on a Pixel 9+/AICore-capable device is materially shorter (the
2-minute window is unnecessarily short, causing users to hit the terminal "taking longer
than expected" state during downloads that would have finished in under 2 minutes,
generating avoidable manual retries) or materially longer in its early, fast-resolving
phase, the constant will need adjustment. Separately: because this value comes from desk
research and not requirements.md-authorized fallback (see the Attribution correction above),
FR-6/AC6 should be tracked as only partially satisfied until the mandatory real-hardware
re-validation below actually happens — this ADR's existence should not be read as closing
FR-6/AC6 outright.

## Follow-up (mandatory)

**Re-validate against real Pixel 9+/AICore hardware before or shortly after shipping.**
Capture actual first-download timing via the methodology `research/build-vs-buy.md`
recommends (app-side `Logger` transitions bracketing the `DOWNLOADABLE` → `AVAILABLE`
transition, cross-referenced with `adb logcat -s AiCoreService:* GenerativeAIService:*`).
Adjust `DEFAULT_POLL_DEADLINE_MS` in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt`'s
companion object if the measured value differs materially (e.g. by more than 2x in either
direction) from the 120s interim estimate. Log a backlog item for this validation pass if
none exists at ship time.
