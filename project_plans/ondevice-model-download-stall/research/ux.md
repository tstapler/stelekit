# UX Research: On-Device Model Download Status Display

## 0. Scope

Covers the fix surface named in requirements.md: `LlmProviderListScreen.kt`
(`LlmProviderRow` / `ProviderStatusIndicator`), the `LlmProviderAvailability` sealed
interface (`llm/LlmProviderAvailability.kt`), and the copy produced by
`mapMlKitFeatureStatus()` (`voice/MlKitAvailabilityMapping.kt`). Findings below are grounded
in this codebase's own established patterns (`FolderSyncStatusBadge`, `SyncStatusBadge`,
`FolderSyncReconciliationProgress`) so the fix reads as "this app's house style," not a
one-off.

## 1. Comparable UX patterns in this codebase

Three existing status-indicator components define the vocabulary this fix should match:

- **`FolderSyncStatusBadge.kt`** is the closest analog: an async, non-deterministic,
  multi-state background-process indicator. Its `folderSyncBadgeContent()` function is a
  *pure* state→copy mapper, unit-testable without a Compose harness — the same shape
  `mapMlKitFeatureStatus()` already has. It draws a hard line between **actionable** states
  (clickable, `Role.Button`, colored) and **informational** states (plain text, not
  clickable) — e.g. "N changes syncing to `<dirName>`" (informational, mid-flight, no user
  action possible) vs. "Folder not found — Reconnect" (actionable). This maps directly onto
  the fix: `Preparing` before `generateContent()` fires is informational-but-inert today;
  after the fix, the not-yet-started sub-state should stay informational (nothing to click,
  it self-triggers on first use) while a stuck/stalled sub-state should become actionable
  (offer a manual re-check or fallback).
- **`SyncStatusBadge.kt`** demonstrates the "auto-resolving state is never phrased as
  actionable" rule explicitly in its own doc comment for `SyncState.RateLimited`: *"never
  clickable, never phrased as 'tap to retry'."* This is the precedent against overloading
  the `Preparing` row with a retry button that implies the user caused the wait or must act
  — the manual refresh AC3 calls for should read as "check now," not "retry," to avoid
  implying something failed.
- **`FolderSyncReconciliationProgress.kt`**'s `ConnectingState` is the pattern for an
  indeterminate wait with a *bounded, known-short* duration: spinner + one-line label +
  one-line explanatory subtext ("Comparing your browser edits with the files on disk").
  This is the template for splitting the on-device row's single sentence into a primary
  status line + a secondary explanatory line, rather than one long run-on string.

**Outside-app mental model (general knowledge, no fetch needed — this is a well-established
pattern class):** app-store "Installing…" chips and browser-extension update rows both avoid
a fake/misleading progress bar when no real progress signal exists, and instead lean on
three techniques this fix should borrow: (a) a distinct **queued vs. active** verb ("Waiting
to install" vs. "Installing…" — never the same word for both), (b) a **static elapsed-time
or expected-window caption** ("Usually takes 15–30 minutes") stated as an expectation rather
than a promise, and (c) an **on-demand refresh/check-again affordance** rather than silent
auto-polling only, because indeterminate spinners with no user control are the single
biggest driver of "is this frozen?" support requests. All three translate directly to ACs
1–3.

## 2. User mental models

A user who opens Settings, sees "On-device model is downloading — this can take 15–30
minutes on first use," and later reopens the same screen to see the *identical, unchanged*
sentence will conclude one of two things, in roughly this order of likelihood:

1. **"This is broken / stuck."** Static text with no timestamp, no elapsed counter, and no
   change across multiple checks is indistinguishable from a hung process — the copy makes a
   time-bounded promise ("15–30 minutes") and then falsifies it by never resolving or
   changing, which is worse than not promising a bound at all.
2. **"Nothing is actually happening"** (the *correct* read in the `DOWNLOADABLE` case per
   root cause #1) — but the user has no way to distinguish this from case 1, because the copy
   is identical for "not started" and "genuinely downloading."

What resolves the uncertainty, in priority order (maps to ACs 1, 3, 4):

- **A way to check current status on demand** (AC3) — even without byte progress, a manual
  "Check again" tap that re-runs `checkAvailability()` and either confirms "still preparing"
  (with a fresh implicit timestamp via re-render) or reveals a state change gives the user an
  active verification loop instead of passive waiting.
- **Distinct copy for distinct machine states** (AC1) — "will start automatically the first
  time you use it" (DOWNLOADABLE, nothing running yet) vs. "downloading now" (DOWNLOADING,
  something is actually in flight) turns a guess into a fact.
- **An escalation path once "unreasonably long" is crossed** (AC4) — the single most
  reassuring thing this screen can say after ~30+ minutes is not new copy alone but a
  concrete next step: *"still preparing — you can keep using \<remote provider name\>
  meanwhile"* if a remote provider is configured, or *"this is taking longer than usual;
  tap Check again, or try again later"* if not. A fallback pointer is more actionable than a
  bare warning because it directly serves the job-to-be-done (see §5) without requiring the
  user to leave Settings.

## 3. Accessibility

`ProviderStatusIndicator` currently has **no** `liveRegion`/semantics annotation anywhere in
`LlmProviderListScreen.kt` (confirmed via `grep -rln liveRegion` — only
`FolderSyncStatusBadge.kt` and `FolderSyncReconciliationProgress.kt` use it in
`commonMain`). That is a real gap relative to this codebase's own precedent:

- `FolderSyncStatusBadge.kt:227` puts `Modifier.semantics { liveRegion =
  LiveRegionMode.Polite }` directly on the status `Text`, with the doc comment explicitly
  framing it as "state transitions announced without interrupting typing."
- `FolderSyncReconciliationProgress.kt` applies the same `LiveRegionMode.Polite` at the
  `Column` level of both `ConnectingState` (announced on entry) and `SummaryState`
  (announced again on completion) — i.e. the *pattern in this codebase* is: apply
  `liveRegion = Polite` at the smallest node that wraps the changing text, and apply it again
  independently at each terminal state.

For this fix: `ProviderStatusIndicator`'s status `Text`/`Row` (or `StatusDotLabel`'s `Text`)
should carry `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` so a TalkBack user
gets an announcement when the state transitions (e.g. `Preparing("not started")` →
`Preparing("downloading")` → `Available`, or when the "taking unusually long" copy appears)
without needing to re-focus the row manually. `Polite` (not `Assertive`) matches every
existing precedent in this codebase — never interrupt the user mid-task for a background
status change. The manual "Check again" `IconButton` (AC3) should get an explicit
`contentDescription` following the existing `Icons.Default.Refresh` retry button's pattern
at `LlmProviderListScreen.kt:189-191` (`contentDescription = "Retry"`) — reuse or closely
mirror that string so both refresh affordances on this screen are announced consistently
(e.g. "Check status now").

## 4. Error/edge-case UX — differentiated copy per machine state

Per AC1 and AC4, the four states that currently collapse into one or two messages need
distinct copy. Proposed mapping (final strings are a UX-content decision for
implementation, not this research doc, but the *state boundaries* below should be treated as
requirements):

| Machine state | Today | Proposed distinct treatment |
|---|---|---|
| `FeatureStatus.UNAVAILABLE` (device will never support it) | "On-device AI is not supported on this device" (`Unavailable`, not retryable) | Already correctly distinct — no change needed. Keep as the one state with **no** refresh affordance (nothing to re-check; matches `Unavailable(retryable = false)` already suppressing the retry button at `LlmProviderListScreen.kt:188`). |
| `FeatureStatus.DOWNLOADABLE` (not yet started) | Identical "is downloading" copy — **false claim**, root cause #1 | Either (a) trigger the real download on first row composition so this state becomes true, or (b) distinct copy: "Not downloaded yet — starts automatically the first time you use voice formatting or tags" with **no** spinner (nothing is in flight) and no false urgency. |
| `FeatureStatus.DOWNLOADING`, within expected window | "is downloading — 15-30 minutes" | Keep substantially this copy but pair with a manual "Check again" affordance (AC3) and, ideally, an implicit "as of just now" freshness cue from the periodic re-check (AC2) so repeated views don't read as frozen. |
| `FeatureStatus.DOWNLOADING`, past a "long time" threshold | Same static copy forever | Escalated copy once elapsed time since first observed `DOWNLOADING` exceeds a threshold well past 30 min (e.g. "This is taking longer than usual") plus, if any remote provider is configured and reachable, a pointer to it ("You can keep using \<Provider\> in the meantime") — directly serves AC4 and the job-to-be-done in §5. Requires tracking a first-seen timestamp for the `Preparing` state somewhere above the purely-derived `mapMlKitFeatureStatus()` function (that function is stateless by design per its own doc comment, so the elapsed-time clock likely belongs in the row's state holder / a wrapping provider, not inside the pure mapper). |
| `checkStatus()` threw / unrecognized code | "Not yet available — check back in a few minutes" (`Unavailable`, retryable) | Already distinct and already has the existing retry button — no change needed, just confirm it isn't accidentally merged with the `Preparing` copy work. |

Design note: `LlmProviderAvailability.Preparing(val detail: String? = null)` already carries a
free-text `detail` field the row already renders (`availability.detail ?: "Preparing…"` at
`LlmProviderListScreen.kt:184`) — the "not started" vs. "downloading" vs. "taking too long"
distinction can very likely be expressed entirely through different `detail` strings on the
same `Preparing` case, with no new sealed-interface variant required, unless the elapsed-time
escalation needs a machine-readable flag (e.g. a `stalled: Boolean` field) for
`ProviderStatusIndicator` to conditionally render the fallback-provider pointer/refresh
emphasis rather than just swapping text. That's an implementation-phase call, not a UX one,
but the row-level composable will need to know "should I show the escalation affordance,"
which argues mildly for a typed signal over string-sniffing `detail`.

## 5. Job-to-be-done

The user opening Settings → AI Providers is not browsing idly — `LlmProviderListScreen.kt`'s
own subtitle states the job directly: *"Configure the LLM providers used for tag
suggestions, voice formatting, and other AI features."* The realistic trigger is one of:

- **Pre-flight confirmation**: "I'm about to rely on voice/tags — is on-device AI actually
  going to work, or do I need a remote API key instead?" (the dominant case, per requirements
  background: users who haven't yet used voice/tags land here with `DOWNLOADABLE`.)
- **Post-hoc troubleshooting**: "Voice formatting / tag suggestions didn't work / were slow —
  is the on-device model the reason?"

In both cases the job is **decide whether to trust on-device AI right now, or fall back to a
remote provider** — not "watch a download progress bar" (correctly out of scope per
Non-Goals). The current static, possibly-false "downloading" message actively defeats this
job: it tells the user to wait rather than letting them make the trust/fallback decision
immediately. The fix's value is entirely in serving this decision faster and more honestly:

- Accurate "not started yet" copy (AC1) lets a pre-flight user immediately understand
  on-device won't be ready for this session and switch to configuring a remote provider now,
  instead of waiting on a phantom download.
- The manual refresh (AC3) and periodic update (AC2) let a mid-download user re-decide
  without leaving the app or guessing.
- The "taking unusually long" escalation with a fallback pointer (AC4) directly closes the
  loop on the job — it converts "still stuck, now what?" into an explicit next action instead
  of silence, which is the single highest-leverage change relative to the current screen.

## Summary of concrete recommendations

1. Split `Preparing` copy into "not started" vs. "downloading" vs. "stalled" sub-states,
   likely via distinct `detail` strings (and possibly a typed "stalled" signal) on the
   existing `Preparing` case rather than new sealed variants — mirrors
   `folderSyncBadgeContent()`'s precedent of state→copy as a pure, testable function.
2. Add a "Check again" `IconButton` on the `Preparing` branch of `ProviderStatusIndicator`
   (currently only wired for `Unavailable`), phrased as a check, not a retry, per
   `SyncState.RateLimited`'s "never phrased as tap to retry" precedent.
3. Add `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` to the status text so
   TalkBack announces state transitions, matching `FolderSyncStatusBadge`/
   `FolderSyncReconciliationProgress`'s existing pattern — currently absent from this screen
   entirely.
4. On "stalled" (elapsed time past the stated 15–30 min window), surface a pointer to a
   configured remote provider if one exists, not just escalated warning copy — this is what
   actually serves the trust/fallback job-to-be-done in §5.
5. Leave `Available`/`Unavailable(retryable=false)` treatments unchanged — both already have
   correct, distinct, non-misleading copy and no regression risk per AC5.
