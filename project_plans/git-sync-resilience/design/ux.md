# UX Design: git-sync-resilience — Step 5 + Foreground Notification

**Date**: 2026-09-23
**Phase**: SDD Phase 3 (Architecture/Design), building on `research/ux.md` and
`implementation/plan.md`'s Domain Glossary + Phase 4 (Wizard Step 5 UX) stories.
**Status**: Ready for validate/implement.

This document does not relitigate the two UX decisions requirements.md already locked in:
retry/resume status is shown (never silent), and the existing Step 5 screen
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupStep5TestAndSave.kt`)
is extended, not replaced. It also does not invent scope: `unshallow()`/deepen
(plan.md's `CloneDepthState`) is a **backend-only capability with no committed UI entry
point** (plan.md Domain Glossary, "unshallow / deepen" row: "no new settings-screen UI
entry point, which requirements.md does not ask for") — see Step 1(e) below for why that
surface is explicitly out of this design.

---

## Step 1: Surfaces touched

| # | Surface | Type | Treatment below |
|---|---|---|---|
| A | Step 5 in-progress clone/fetch/push (`Attempting`, `Retrying`) | Interactive (Cancel button, live region) | Full wireframe |
| B | Step 5 terminal failure: `Exhausted` vs. `NonRetryableFailure` | Interactive (distinct styling + actions) | Full wireframe |
| C | Cancel affordance for an in-progress operation | Interactive (currently absent) | Folded into A's wireframe + its own flow |
| D | Android foreground-service persistent notification | User-facing, tappable | Full wireframe (state-by-state mockup) |
| E | Shallow-clone indicator / "get full history" action | N/A | **Explicitly deferred — see below, no wireframe** |
| F | Structured retry/resume log lines | Non-interactive | Condensed entry only |

**On (E)**: requirements.md's Rabbit Holes flagged this as a *possible* unstated need, but
plan.md resolved it: Story 2.1.4 builds `unshallow()` as a `GitRepository` interface method
only, with no settings-screen UI, no Step 5 indicator, and no user-facing "get full history"
button anywhere in Phase 4's scope. `CloneDepthState` (`NONE`/`SHALLOW`/`FULL_HISTORY`) is
persisted to `git_config` purely so a *future* project can add that UI without a migration.
Designing an indicator or action here would be scope the plan didn't commit to — this design
intentionally builds nothing for (E) and flags it as a candidate for a follow-up project if a
real user need surfaces (e.g. someone who needs `git log` history beyond the shallow
boundary for an external tool).

---

## Step 2: Interactive surfaces

### A. Step 5 — in-progress status (`Attempting` / `Retrying`)

**Wireframe** (extends the existing `Step5TestAndSave` column; new/changed elements marked `»`):

```
┌────────────────────────────────────────────────────────┐
│ Test and save                                           │
│ Optionally test your connection before saving.          │
│                                                           │
│ [ Test connection ]                                      │
│                                                           │
│ » ┌──────────────────────────────────────────────────┐  │
│ » │ ⟳  Cloning your graph…                            │  │  ← primary line (unchanged copy
│ » │    Reconnecting… Attempt 2 of 4 — 45%              │  │     across Attempting/Retrying)
│ » │    (small, muted, only when Retrying; the "— 45%"  │  │  ← secondary line, caption style
│ » │    clause is omitted until progress is known)      │  │
│ » └──────────────────────────────────────────────────┘  │     (research/ux.md §1: attempt
│ »                                          [ Cancel ]     │     count subordinate, never the
│                                                           │     dominant text)
│ [ Back ]                              [ Save configuration ]  ← both disabled while busy
└────────────────────────────────────────────────────────┘
```

Row anatomy (`CloneProgressRow`, extended per plan.md Task 4.1.3a):
- Spinner (`CircularProgressIndicator`, unchanged) — stays visible across `Attempting` and
  `Retrying`; the spinner never stops just because a retry is in
  flight, so the operation never *looks* dead even before real percentages exist
  (research/ux.md §6 priority call: "don't look hung" beats exact percentage).
- **Primary line** (`MaterialTheme.typography.bodySmall`, full-emphasis color): the ongoing
  action, one of:
  - `Attempting`: "Cloning your graph…" (or "Fetching…" / "Pushing…" depending on which
    `GitTransportRetryState`-producing operation is running — Step 5 today is clone-only, so
    this design keeps "Cloning your graph…" as the copy Phase 4 ships; fetch/push reuse the
    same row shape if a future screen needs it).
  - `Retrying`: same primary line, unchanged — per plan.md AC ("shows primary text 'Cloning
    your graph…' and secondary … text"). This is also the state a resumed-and-progressing
    retry attempt stays in — `Retrying`'s `CloneProgress?` field carries real numbers once
    this attempt's transfer starts moving again; there is no separate "resuming" state name
    for that (see the Interaction flow below and ADR-001: a retried shallow-clone attempt
    still restarts its transfer from zero, so this is progress *within* the current retry
    attempt, never framed as picking up partial credit from a prior attempt).
- **Secondary line** (`labelSmall` or smaller, `colorScheme.onSurfaceVariant` — muted, never
  the error color): only rendered for `Retrying`. Renders "Reconnecting… Attempt {attempt} of
  {max} — {percent}%" (`percent = progress.completed * 100 / progress.totalWork`, rounded)
  once `Retrying.progress` is non-null with a known `totalWork > 0` — the same progress
  signal surface D's notification shows for this state, so the two never diverge. Falls back
  to the plain "Reconnecting… Attempt {attempt} of {max}" (no percentage clause) only while
  progress is genuinely unknown — `progress == null` or `totalWork == 0` — which in practice
  means the window right at the start of a retry attempt, before this attempt's transfer has
  moved any bytes. Absent entirely for `Attempting`/`Idle`.
- **Cancel button** (`TextButton`, new): visible and enabled whenever `cloneInProgress`,
  positioned at the row's trailing edge, mirroring `onCancelTestConnection`'s existing
  `TextButton` placement (`GitSetupStep5TestAndSave.kt:144`). Label is **"Cancel"** but its
  `contentDescription`/semantic label is **"Cancel clone"** (not bare "Cancel") — see
  Accessibility below for why.

**Interaction flow — transient failure → auto-retry → succeeds:**
1. User taps "Save configuration" (Step 2's existing action; this design adds no new
   trigger). `cloneInProgress = true`, state = `Attempting(CloneProgress("Receiving
   objects", 0, 0))`.
2. A transient failure occurs mid-transfer (`SocketException` etc., classified `Transient`
   by `classifyGitFailure`). State transitions to `Retrying(attempt = 1, max = 4, progress =
   null)`. Live region announces once (see Accessibility).
3. Backoff elapses; the retry attempt runs, and the shallow-clone transfer restarts from
   zero — per ADR-001, "an interruption mid-shallow-clone still re-transfers that shallow
   step's data from zero on retry... there is no partial credit within a single
   shallow-clone attempt." State stays `Retrying(attempt = 1, max = 4, progress = ...)`,
   with `progress` now populated as this attempt's transfer proceeds (never a fabricated
   percentage), then moves to a terminal success — Step 5's existing success path (outside
   this design's scope; unchanged) takes over: `Save configuration` completes, wizard
   advances.
4. At no point does the primary line disappear or the spinner stop.

**Interaction flow — transient failure → all retries exhausted:** see surface B.

**Interaction flow — non-retryable failure (bad credentials):** see surface B; no
`Retrying` state is ever entered — `classifyGitFailure` routes straight to
`NonRetryableFailure`, so the attempt counter is never shown, per plan.md's Story 4.1.3 AC.

**Interaction flow — user cancels mid-retry:**
1. User taps "Cancel" while state is `Attempting` or `Retrying`.
2. `onCancelClone()` fires → Android: `WorkManager.cancelUniqueWork(workName)`; Desktop:
   the coroutine `Job` backing the clone is cancelled (plan.md Tasks 4.1.4b/c).
3. `classifyGitFailure` sees the resulting `CanceledException` and reports
   `GitFailureClass.Cancelled` — the retry loop exits immediately, **no `beforeRetry`
   cleanup runs** (plan.md Task 4.1.4d), so the partially-cloned directory and any
   checkpoint/`CloneDepthState` row are left exactly as they were.
4. `cloneInProgress = false`. Row copy becomes: **"Cancelled — your progress is saved.
   Resume anytime from Step 5."** (`colorScheme.onSurfaceVariant`, no icon — this is a
   neutral, not an error, outcome).
5. Back and Save re-enable. Tapping "Save configuration" again re-enters `Attempting` and
   resumes from the preserved checkpoint (same mechanism as an automatic retry) — not from
   0%.

---

### B. Step 5 — terminal failure: `Exhausted` vs. `NonRetryableFailure`

**Wireframe — `Exhausted` (retryable, gave up after N attempts):**

```
┌────────────────────────────────────────────────────────┐
│ ⚠  Couldn't finish after 4 attempts.                     │  ← Icons.Default.Warning,
│    Check your connection and try again — your           │     tint = colorScheme.tertiary
│    progress is saved.                                    │     (NotificationDisplay.kt's
│                                                           │     existing WARNING convention —
│                          [ Try again ]                   │     NOT colorScheme.error)
│                                                           │
│ [ Back ]                              [ Save configuration ]  ← re-enabled
└────────────────────────────────────────────────────────┘
```

**Wireframe — `NonRetryableFailure` (auth/404, never retried):**

```
┌────────────────────────────────────────────────────────┐
│ ✕  Authentication failed — check your token/SSH key      │  ← Icons.Default.Error,
│    in Step 3.                                             │     tint = colorScheme.error
│                                                           │     (existing TestResultRow
│                                                           │     failure convention, reused
│                                                           │     as-is)
│ [ Back ]                              [ Save configuration ]  ← re-enabled; Back is the
└────────────────────────────────────────────────────────┘     exit path back to Step 3
```

No attempt counter, no "Try again" button here — the fix is elsewhere in the wizard (Step 2
for a bad URL/404, Step 3 for bad credentials), and re-running the same operation cannot
succeed. `Back` (already present, now re-enabled since `busy = false`) is the exit path.

**Why visually distinct** (per research/ux.md §4, the requirement this satisfies): a user
who sees ⚠ amber "try again, it's saved" versus ✕ red "go fix Step 3" should never confuse
"my connection is flaky" with "I typed the wrong token." Both treatments reuse
*already-established* codebase tokens — no new color is introduced. `NonRetryableFailure`
copies `TestResultRow`'s existing inline failure pattern verbatim: bare `colorScheme.error`
tint/text with `Icons.Default.Error`, no background container
(`GitSetupStep5TestAndSave.kt:164,170`). `Exhausted` adapts the codebase's other existing
warning pairing to that same inline, no-background shape: `NotificationDisplay.kt`'s
`NotificationType.WARNING` uses `Icons.Default.Warning` with the tertiary color family
(`tertiaryContainer`/`onTertiaryContainer`, since that component renders a colored toast
surface) — this design uses the bare `colorScheme.tertiary` token instead, the inline analog
of "tertiary family" in the same way `colorScheme.error` (not `errorContainer`) is the inline
analog `TestResultRow` already uses for `NonRetryableFailure`, since Step 5's row has no
background surface to pair a "Container" token against.

**Interaction flow — `Exhausted` → manual retry:**
1. All N automatic attempts (`RetryPolicies.gitTransportTransient`) hit `Transient`
   failures. State becomes `Exhausted(reason)`. `cloneInProgress = false`.
2. User taps "Try again". This calls the same entry point as the original "Save
   configuration" action (`performCloneAndSave`), **not** a special-cased retry function —
   it starts a fresh `Attempting` → retry cycle from the last checkpoint (per ADR-001), not
   from 0%.
3. If it fails again, the cycle repeats (back to `Retrying`/`Exhausted`); if it succeeds,
   the wizard advances as normal.

**Interaction flow — non-retryable, immediate:**
1. First transport attempt throws an exception `classifyGitFailure` recognizes as
   `Permanent` and auth/lookup-shaped (401/403/404). `runGitTransportOpWithRetry` never
   enters its backoff loop — state goes directly `Attempting` → `NonRetryableFailure`, no
   `Retrying` state is ever observed.
2. `cloneInProgress = false` immediately (no multi-second wait pretending to retry).
3. User's only path forward is Back (to fix Step 2/3) — no "Try again" is offered because
   retrying without fixing the underlying config cannot succeed.

---

### C. Cancel affordance (flow only — wireframe is folded into A)

Already covered end-to-end in surface A's "user cancels mid-retry" flow. Restated as its own
acceptance-testable unit for Step 3 below because it's the one surface `research/ux.md`
flags as **currently entirely absent** (today, Back is simply disabled with no repurposing —
`GitSetupStep5TestAndSave.kt:82`'s `busy = saving || cloneInProgress` gates both Back and
Save with no other exit).

---

### D. Android foreground-service notification

**Wireframe — state-by-state notification content** (system notification shade, collapsed
+ expanded views share the same title/body per plan.md Story 4.1.5):

```
Cloning (Attempting):
┌───────────────────────────────────────────┐
│ 🔄  SteleKit                                │
│     Syncing My Notes                        │  ← title: "Syncing {graph display name}"
│     Cloning — 60%                           │  ← body mirrors Step 5's copy exactly
│     [██████████████░░░░░░░░]  60%           │  ← determinate once CloneProgress.totalWork
│                                              │     is known; indeterminate bar until then
└───────────────────────────────────────────┘     (never a fake determinate %)

Retrying (backoff wait, no transfer yet):
┌───────────────────────────────────────────┐
│ 🔄  SteleKit                                │
│     Syncing My Notes                        │
│     Reconnecting… (attempt 2 of 4)          │
│     [░░░░░░░░░░░░░░░░░░░░░░░░]  indeterminate│
└───────────────────────────────────────────┘

Retrying (this attempt's transfer is under way — same state, not a distinct
"resuming" state; per ADR-001 this attempt still restarts from zero, it is not
picking up partial credit from the prior failed attempt):
┌───────────────────────────────────────────┐
│ 🔄  SteleKit                                │
│     Syncing My Notes                        │
│     Reconnecting… (attempt 2 of 4) — 45%    │
│     [█████████░░░░░░░░░░░░░]  45%           │
└───────────────────────────────────────────┘

Terminal failure (tap to retry) — becomes DISMISSIBLE here, unlike the two states above:
┌───────────────────────────────────────────┐
│ ⚠  SteleKit                                 │
│     Syncing My Notes                        │
│     Sync failed — tap to retry              │
└───────────────────────────────────────────┘   [swipe-to-dismiss now available]

Success: notification auto-dismisses, no terminal "success" notification lingers.
```

- **Non-dismissible while active** (`setOngoing(true)`) for `Attempting`/`Retrying` —
  Android foreground-service requirement.
- **`setOnlyAlertOnce(true)`** globally — only the very first post (or the first
  state-*class* change, e.g. cloning → retrying) triggers sound/vibration; no repeated
  alert per progress tick or percentage update (research/ux.md §3: this is the
  notification-channel equivalent of live-region over-announcing, and is doubly disruptive
  under TalkBack, which speaks the update *and* would play a tone each time).
- **Tap target**: every state's tap action is a `PendingIntent` deep-linking to Step 5
  specifically (not the app's generic last/home screen) — the user's context was mid-wizard.
- **Dismissal on terminal failure**: `setOngoing(false)` and content becomes "tap to retry",
  converting it from a stuck non-dismissible notification (which itself reads as "broken")
  into a normal, swipeable one.
- **Content language**: "Syncing My Notes" — the graph's display name, never a raw
  `https://github.com/.../repo.git` URL or local path (research/ux.md §2/§5's jargon-avoidance
  rule applies identically here and on Step 5, since the two surfaces must never disagree).

**Interaction flow — background survival:**
1. User starts a clone on Step 5, then backgrounds the app or locks the screen.
2. `GitCloneWorker` (Android `CoroutineWorker` with `setForeground(dataSync)`) keeps the
   transfer alive; the notification above is the only visible indicator while backgrounded.
3. Any retry/resume/failure transition updates the *same* notification in place (not a new
   one per event) — `NotificationManager.notify(id, updatedNotification)` with a stable ID.
4. Tapping the notification at any point returns the user to Step 5, which re-renders
   whatever `GitTransportRetryState` is current — the notification and Step 5 are two views
   of the same state, never divergent copy.

---

### F. Structured retry/resume log lines (non-interactive — condensed entry)

Per plan.md's Observability Plan: `info`-level, existing `Logger("AndroidGitRepository")` /
`Logger("JvmGitRepository")`, no new logger or telemetry.

**Representative sample:**
```
INFO AndroidGitRepository: git retry attempt=2/4 op=clone class=Transient
    cause=SocketException backoffMs=2000 url=https://***@github.com/***/notes.git
INFO AndroidGitRepository: git clone checkpoint NONE->SHALLOW depth=50 graphId=abc123
```

**Acceptance criteria:**
- Every retry attempt logs attempt number, max attempts, the `GitFailureClass`, the
  triggering exception type, and the backoff delay before the next attempt — sufficient to
  reconstruct the full retry timeline from logs alone (requirements.md's Observability
  Requirement: "diagnosed from local logs without new telemetry infrastructure").
- Every `CloneDepthState` transition (`NONE→SHALLOW`, `SHALLOW→FULL_HISTORY`, and deepen
  failures) is logged with the graph ID and depth value.
- Remote URLs are always passed through the existing `redactUrlUserinfo` before logging
  (`GitOperationSupport.kt`'s established convention) — no embedded credentials in
  userinfo (`https://user:token@host/...`) ever reach a log line in plaintext.
- Cancellation is logged distinctly from exhaustion (`class=Cancelled` vs. a final
  `class=Transient` after the last attempt) so a diagnosing developer can tell "user gave up"
  from "the network gave up" without cross-referencing UI state.
- Terminal `NonRetryableFailure` logs the classified reason (auth/404/etc.), not just a
  generic "clone failed," so a support conversation can identify which wizard step to send
  the user back to without reproducing the failure.

---

## Step 3: UX acceptance criteria

**Task completion:**
1. A user can cancel an in-progress clone in **1 click** (the always-visible "Cancel"
   button — no confirmation dialog; cancel is non-destructive per (C)'s partial-state
   preservation, so a confirm step would only add friction for no safety benefit).
2. A user can manually retry after `Exhausted` in **1 click** ("Try again").
3. A user recovering from `NonRetryableFailure` reaches the field they need to fix in **2
   clicks** (Back → the specific wizard step named in the error copy, e.g. Step 3 for
   credentials) — the error copy itself must name the step, satisfying this without the user
   guessing.
4. Resuming a cancelled clone takes **1 click** ("Save configuration" again) — no separate
   "Resume" button/screen is introduced; resume is just "try the same action again," which
   is also what a user intuitively does after cancelling.

**Error-state specificity** (each testable by triggering the state and reading the screen):
5. `Retrying` shows primary text **"Cloning your graph…"** and secondary text
   **"Reconnecting… Attempt {N} of {max}"**, extended to **"Reconnecting… Attempt {N} of
   {max} — {percent}%"** once `Retrying.progress` has a known `totalWork > 0` (same
   progress signal as the notification, surface D) — never a raw exception class name or
   message (`SocketException`, `Software caused connection abort`, etc.) anywhere in the row.
6. `Exhausted` shows **"Couldn't finish after 4 attempts. Check your connection and try
   again — your progress is saved."** with a warning (not error-red) icon, and offers
   **"Try again."**
7. `NonRetryableFailure` for an auth failure shows **"Authentication failed — check your
   token/SSH key in Step 3"**; for a repo-not-found/404 failure it shows **"Repository not
   found — check the URL on the previous step"** (pointing back at Step 2's repo-path entry,
   matching the auth string's tone: state the problem, name the step to fix it). Both show
   an error icon and **no attempt counter**.
8. Cancelling shows **"Cancelled — your progress is saved. Resume anytime from Step 5."**
9. No raw JGit/transport exception text (`TransportException`, `SocketException`, HTTP status
   codes as bare numbers) is ever rendered in Step 5 or the notification — every user-facing
   string in `GitTransportRetryState` is authored copy, not `e.message`.

**No dead ends** (every state has an exit path — testable per state):
10. `Attempting`/`Retrying`: exit path = Cancel.
11. `Exhausted`: exit paths = Try again (retry) or Back (abandon, config not yet saved).
12. `NonRetryableFailure`: exit path = Back (go fix the named step); Save remains available
    if the user wants to re-attempt after fixing something out-of-wizard (e.g. rotated a
    token in their git host's UI without revisiting Step 3).
13. Cancelled: exit paths = Save (resume) or Back (abandon, partial clone left on disk —
    out of scope for this design whether an abandoned partial clone is later garbage
    collected; that's a lifecycle question for the architecture/implementation phases, not
    UX).

**Accessibility:**
14. The row/column containing `CloneProgressRow`'s text carries
    `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`, applied at the same
    container level as `FolderSyncReconciliationProgress.kt:90` and
    `StorageMoveProgressDialog.kt:116`'s existing convention — not `assertive` (this is a
    background operation, not a blocking alert).
15. The live region's announced text — the same parameterized "Reconnecting… Attempt {N} of
    {max}" secondary line specified above (Row anatomy, AC5), optionally suffixed with
    " — {percent}%" once progress is known, not a fixed string — updates **at most once per
    retry-attempt transition**, and, within a single retry attempt once `progress.totalWork`
    is known, **at most every ~10 percentage points** of that attempt's `percent` value —
    never on every raw `ProgressMonitor.update()` callback. (Enforced at the state-production
    site, plan.md Task 4.1.2b — this AC is what a human tester confirms by listening with
    TalkBack/VoiceOver/NVDA through a simulated flaky clone and counting announcements
    against attempt-count plus 10-point progress buckets, not against every tick.)
16. The Cancel button exposed during an in-progress clone has an accessible label
    **"Cancel clone"**, distinct from the pre-existing "Cancel" `TextButton` on the Test
    Connection row (`GitSetupStep5TestAndSave.kt:144`) — both buttons render the visible
    text "Cancel," so without a distinguishing `contentDescription`/semantic label a screen
    reader presents two indistinguishable "Cancel" controls in the same screen's traversal
    order.
17. The terminal-state icons carry `contentDescription` values distinct from the existing
    `TestResultRow` pair ("Success"/"Error") — `Exhausted`'s warning icon uses
    `contentDescription = "Warning"`, `NonRetryableFailure` reuses `contentDescription =
    "Error"` (same meaning as today's failure icon, consistent semantics).
18. Every interactive element introduced here (Cancel, Try again) is a standard
    `TextButton`/`Button` — reachable via Tab/Shift+Tab on Desktop and via TalkBack/
    VoiceOver swipe navigation on Android, with no custom touch-target-only affordance
    (e.g. no bare `Icon` with a `clickable` modifier and no semantic role).
19. Color contrast: `colorScheme.tertiary` (warning) and `colorScheme.error` (non-retryable)
    against their container backgrounds meet **≥ 4.5:1**, verified against this app's
    Material3 theme tokens (both are pre-existing tokens already used elsewhere in the app —
    `NotificationDisplay.kt`'s `WARNING`/`ERROR` treatment and `TestResultRow`'s failure
    treatment — so this AC is satisfied by inheriting those tokens rather than picking new
    ad hoc colors; a human tester spot-checks with a contrast checker against the actual
    rendered theme, since Compose doesn't statically guarantee this).
20. Foreground-service notification title/body text avoids jargon (graph display name, not
    raw URL/path; plain-language state, not exception class names) so TalkBack's automatic
    notification announcement is meaningful without any extra ARIA-equivalent work (Android
    notifications have no live-region concept — this AC is about copy quality, not markup).

---

## Summary of dependencies this design assumes (already surfaced in research/ux.md, restated for traceability)

- `CloneProgress`/`GitTransportRetryState` (plan.md Stories 4.1.1–4.1.2) must exist before
  this UI can be literal rather than aspirational — this design specifies the *rendering* of
  those states, not their production.
- The retry-classification taxonomy (`classifyGitFailure`, Phase 1) must correctly separate
  `Transient` from `Permanent`/auth-shaped failures — the UI in surface B cannot distinguish
  `Exhausted` from `NonRetryableFailure` if the underlying classifier doesn't.
