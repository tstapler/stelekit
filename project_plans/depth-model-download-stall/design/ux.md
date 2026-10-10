# UX Design: Depth Model Download Stall

SDD Phase 3 (design) artifact for backlog item `505fb733-9621-4621-b7fc-27712e36d084`. Grounds
`implementation/plan.md`'s UI tasks (Phase 3 Cancel button, Phase 4 stall copy, Phase 6
`liveRegion` semantics) in concrete wireframes, flows, and testable acceptance criteria. Built
directly on `research/ux.md`'s findings — see that document for the underlying rationale; this
document is the spec those findings and the plan's tasks implement against.

This is a single small floating panel (`DepthEstimationPanel`, `AnnotationEditorScreen.kt:1319-1440`)
with five renderable branches driven by two independent bits of state (`modelState:
DepthModelUiState`, `isInferenceRunning: Boolean`), plus an inline error string. It is **not** a
modal, dialog, or full-screen gate — it floats over the photo canvas alongside other annotation
controls and never blocks them.

---

## Surface inventory

| # | Surface | Trigger | Plan phase that implements it |
|---|---------|---------|-------------------------------|
| 1 | Entry / editor context | User opens `AnnotationEditorScreen` | Phase 1 (wiring) |
| 2 | `Absent` — download prompt | Model not yet downloaded | Existing (unchanged) |
| 3 | `Downloading` — determinate | Total size known | Phase 2 (AC1) |
| 4 | `Downloading` — indeterminate | Total size unknown (`progress = -1`) | Phase 2 (AC2) |
| 5 | `Downloading` — Cancel action | User taps Cancel | Phase 3 (AC3) |
| 6 | `Failed` — stall-triggered | No byte movement for 30s | Phase 4 (AC4) |
| 7 | `Failed` — generic | `STATUS_FAILED` from `DownloadManager` | Existing (unchanged) |
| 8 | `Ready` — estimate prompt | Download complete | Existing (unchanged) |
| 9 | Inference running / inference error | User taps "Estimate depth (AI)" | Existing (unchanged) |
| 10 | Navigate-away / return mid-download | User leaves and re-enters the screen | Phase 2 + 5 (AC5) |
| 11 | Screen-reader (TalkBack) flow | Any of the above, non-visually | Phase 6 |

11 surfaces below; **25 UX acceptance criteria** at the end.

---

## 1. Entry / editor context

The panel is a floating `Surface` (dark, `0xDD1A1A1A`, rounded small corners) positioned over the
photo canvas — not centered, not modal. Other annotation controls (manual measurement tools,
drawing) remain visible and tappable behind/beside it at all times, in every state below. This is
already true structurally today; the copy changes in this design make that fact legible to the
user instead of just true in the layout.

```
┌──────────────────────────────────────────────────────┐
│  AnnotationEditorScreen                                │
│  ┌────────────────────────────────────────────────┐   │
│  │                                                  │   │
│  │              [ photo canvas ]                    │   │
│  │                                                  │   │
│  │                                    ┌───────────┐ │   │
│  │                                    │ Depth      │ │   │
│  │                                    │ panel here │◄┼───┼── DepthEstimationPanel
│  │                                    └───────────┘ │   │      (floating, non-blocking)
│  │                                                  │   │
│  └────────────────────────────────────────────────┘   │
│  [ manual measurement tools remain usable throughout ] │
└──────────────────────────────────────────────────────┘
```

**Flow**: User opens the annotation editor → `ScreenRouter`'s `LaunchedEffect` (Task 1.2.1a)
subscribes to `modelState` → panel renders whatever branch matches the current
`DepthModelUiState` on first composition. No loading flash before this — `Absent` is a valid
first-paint state, not an interim one.

---

## 2. `Absent` — download prompt

```
┌───────────────────────────────────┐
│  [ Download depth model (~100MB) ] │   ← OutlinedButton, white text
└───────────────────────────────────┘
```

**Interaction flow**:
1. User taps the button.
2. System calls `onDownloadDepthModel` → `downloader.downloadModel()` → enqueues via
   `DownloadManager`.
3. Panel transitions to `Downloading` (Surface 3 or 4) on the next `modelState` emission.

**Edge cases**: None specific to this state — it is the resting/idle state.

---

## 3. `Downloading` — determinate (AC1)

```
┌───────────────────────────────────────────┐
│  ⟳  Downloading model… 47%        [Cancel] │
└───────────────────────────────────────────┘
```

Optional (Phase 6, non-blocking enhancement per `research/ux.md` §1):

```
┌────────────────────────────────────────────────────────┐
│  ⟳  Downloading model… 47% (47.0 MB / 100.0 MB) [Cancel]│
└────────────────────────────────────────────────────────┘
```

**Interaction flow**:
1. Polling loop (`startPolling`, 300ms cadence) queries `DownloadManager.Query()`.
2. Every tick where `bytesDownloaded` differs from the prior tick, `modelState` emits
   `Downloading(progress = computeProgressPercent(bytes, total))` — percentage visibly advances,
   never sticks at a single value for the whole transfer.
3. User may tap Cancel at any point (see Surface 5) or simply keep annotating — nothing else on
   screen is blocked.
4. On completion, `BroadcastReceiver` fires, `pollingJob` is cancelled, `modelState` transitions
   to `Ready` (Surface 8).

**Edge case — percentage appears to stall due to rounding** (e.g. 44% for several seconds because
bytes moved from 44.1% to 44.6%): the byte-count string, if implemented in Phase 6, keeps
advancing even when the rounded percentage doesn't, which is the specific mitigation for this.
Not a blocking requirement — the underlying 300ms real polling already satisfies AC1 without it.

---

## 4. `Downloading` — indeterminate (AC2)

```
┌───────────────────────────────────┐
│  ⟳  Downloading model…    [Cancel] │
└───────────────────────────────────┘
```

**Interaction flow**: Identical to Surface 3, except `total <= 0` (Android's documented "unknown
size" sentinel — e.g. HF redirect not yet resolved to a `Content-Length`). `progress = -1` is
passed through unchanged; the panel's existing `if (pct >= 0)` branch (line 1392) already renders
this correctly — **no fabricated "0%"** is shown, satisfying AC2's explicit "no misleading fixed
0% label" requirement.

**Edge case**: If the total size resolves mid-download (common — redirects often lack
`Content-Length` on the first response but the final URL has it), the panel transitions from
Surface 4 to Surface 3 seamlessly on the next tick — no special transition animation needed, this
is just a normal state re-render.

---

## 5. `Downloading` — Cancel action (AC3)

```
User taps [Cancel] ──▶ onCancelDownload() ──▶ downloader.cancelDownload()
                                                    │
                                                    ├─ pollingJob.cancel()
                                                    ├─ downloadManager.remove(activeDownloadId)
                                                    ├─ modelFile.delete() (partial file cleanup)
                                                    ├─ activeDownloadId = -1L
                                                    └─ modelState = Absent
                                                              │
                                                              ▼
                                              Panel re-renders as Surface 2 (Absent)
```

**Interaction flow**:
1. User taps `Cancel` `TextButton` (styled to match the existing `Failed`-branch retry button:
   `labelSmall` typography, inline row placement next to the spinner/percentage).
2. Tap is immediate and irreversible — no confirmation dialog. (Rationale: this is a
   low-stakes, easily-repeatable action — re-downloading costs the user one more tap on "Download
   depth model," not data loss. A confirmation dialog would add friction to the exact action this
   fix exists to make easy. If real usage shows accidental cancels are common, revisit.)
3. Panel returns to the `Absent` branch (Surface 2) — same visual state as if the user had never
   started, by design (`research/ux.md` §4: no new "cancelled" terminal state needed).
4. The download can be restarted immediately by tapping "Download depth model" again — nothing
   about a prior cancel blocks a fresh attempt.

**Edge case — tapping Cancel right as the download completes**: a benign race between the
Cancel tap and the `BroadcastReceiver`'s completion callback. Whichever wins, the user ends up in
either `Ready` (download had already finished — cancel is a no-op past that point) or `Absent`
(cancel won) — never a stuck or contradictory state, since both paths fully reset
`activeDownloadId`/`pollingJob`.

---

## 6. `Failed` — stall-triggered (AC4)

```
┌───────────────────────────────────────────────────┐
│  ⚠  This is taking longer than expected. Tap to    │
│      retry.                                         │
└───────────────────────────────────────────────────┘
```

**Interaction flow**:
1. Polling loop observes no change in `bytesDownloaded` for `STALL_TIMEOUT_MS` (30s).
2. Loop cancels the underlying `DownloadManager` request, deletes the partial file, and sets
   `modelState = Failed(reason = "This is taking longer than expected.")`.
3. Panel renders the `Failed` branch with the stall-specific copy (distinguished via
   `modelState.reason`).
4. User taps the `Failed` row (the whole row is the retry `TextButton`, matching the existing
   pattern) → `onDownload()` → `downloadModel()` is called fresh (no `activeDownloadId` in flight,
   since the stall handler already cleared it) → transitions back to Surface 3/4.

**Why 30s and not sooner/later**: matches `SafChangeDetector`'s existing 30s poll-tolerance
constant elsewhere in this codebase (research/plan precedent) — long enough that normal network
jitter or a brief `PAUSED_WAITING_FOR_NETWORK` blip doesn't false-positive, short enough that a
user actively watching the panel doesn't wait more than half a minute before getting an
actionable signal.

**Edge case — legitimate `STATUS_PAUSED` (e.g. waiting for Wi-Fi)**: per plan Task 4.1.2a, this is
*not* exempted from the stall timer — if there's genuinely no byte movement for 30s, the user sees
the same "taking longer than expected" message regardless of the underlying `DownloadManager`
reason code. From the user's seat, "paused waiting for network for 30+ seconds" and "actually
stuck" are indistinguishable and should produce the same actionable UI.

---

## 7. `Failed` — generic (unchanged, regression-checked)

```
┌───────────────────────────────────┐
│  ⚠  Download failed — tap to retry │
└───────────────────────────────────┘
```

**Interaction flow**: Identical retry mechanics to Surface 6, but for a genuine
`DownloadManager.STATUS_FAILED` (`Failed(reason = null)`) rather than a stall. This is the
pre-existing behavior — Phase 4 must not regress it (plan's Task 4.1.1a explicitly falls back to
this exact copy when `reason == null`).

---

## 8. `Ready` — estimate prompt (unchanged)

```
┌─────────────────────────────────────┐
│  [ Estimate depth (AI) ]              │
│  ⚠ Low confidence — verify with       │
│    reference object                   │
└─────────────────────────────────────┘
```

Unaffected by this fix — included for completeness since it's the state the whole flow is working
toward. No change proposed.

---

## 9. Inference running / inference error (unchanged)

```
┌───────────────────────────────────┐        ┌───────────────────────────────────┐
│  ⟳  Estimating depth…               │        │  [ Estimate depth (AI) ]           │
└───────────────────────────────────┘        │  ⚠ <depthEstimationError text>     │
                                                └───────────────────────────────────┘
```

Unaffected by this fix. `isInferenceRunning` short-circuits the `when` before `modelState` is
even consulted (line 1338), and `depthEstimationError` renders below the main branch regardless of
which branch is active. Included to confirm the fix doesn't touch this logic and the two concerns
stay orthogonal.

---

## 10. Navigate-away / return mid-download (AC5)

```
User on Downloading (47%)
        │
        │  navigates back / leaves AnnotationEditorScreen
        ▼
DepthModelDownloader.scope (instance-owned, process-lifetime)
  keeps pollingJob + DownloadManager transfer alive
  (NOT tied to the screen's coroutine — this is the fix's core mechanism)
        │
        │  user returns to AnnotationEditorScreen later
        ▼
ScreenRouter's LaunchedEffect re-subscribes to modelState
        │
        ▼
Panel renders whatever the download's CURRENT state is
  (e.g. Downloading(83%) if still running, Ready if it finished
   while the user was away, Failed if it stalled/failed while away)
```

**Interaction flow**:
1. User taps "Download depth model," sees progress start, then navigates away (back button, or to
   another screen) before it finishes.
2. Unlike the pre-fix behavior (coroutine cancellation silently reset state to `Absent`,
   discarding the in-flight download), the download **continues** because `pollingJob` lives on
   `DepthModelDownloader`'s own scope, not the screen's.
3. If the user reopens the annotation editor before the download finishes, they see the current
   live progress — not a fresh `Absent` prompt requiring them to restart.
4. If a second `downloadModel()` call happens to fire during this window (e.g. from a freshly
   recomposed `AnnotationEditorViewModel`), the reattachment guard (`activeDownloadId != -1L`)
   prevents a duplicate `DownloadManager` enqueue — the caller instead awaits the existing
   transfer's terminal state.

**Edge case — user cancels, then immediately navigates away**: no conflict; `cancelDownload()`
already fully tears down `activeDownloadId`/`pollingJob`/`modelFile` before the navigation even
starts processing, so there's no dangling background transfer to reattach to.

**What this deliberately does NOT do**: navigating away never itself cancels the download. Only
the explicit Cancel button (Surface 5) does. This is the AC3/AC5 split the plan's ADR-001 exists
to enforce — "user explicitly cancelled" and "user briefly left the screen" must produce different
outcomes, and did not before this fix.

---

## 11. Screen-reader (TalkBack) flow

```
Absent ──(announce: "Download depth model, button")──▶ [user activates]
   │
   ▼
Downloading ──(announce ONCE on entry: "Downloading model")──▶ ... (no per-tick spam) ...
   │                                                                       │
   │ [Cancel button reachable via swipe, announces:                       │
   │  "Cancel model download, button"]                                   │
   │                                                                       │
   ├──(announce on exit → Ready)────────────────────────────▶ Ready
   ├──(announce on exit → Failed)───────────────────────────▶ Failed
   └──(announce on exit → Absent, via Cancel)────────────────▶ Absent
```

**Interaction flow**: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the panel's
outer `Column` (matching `FolderSyncReconciliationProgress.kt`'s exact precedent) causes TalkBack
to announce the *container's* content once per state-boundary crossing (`Absent → Downloading`,
`Downloading → Ready`, `Downloading → Failed`, `Downloading → Absent`) — not once per 300ms
percentage tick. A TalkBack user hears "Downloading model" once, then is free to move focus
elsewhere (e.g. to continue manual annotation), and hears the next state change whenever it
happens, without being interrupted every third of a second.

**Edge case — TalkBack user wants to check current percentage on demand**: swiping focus onto the
progress text still reads its current literal string (e.g. "Downloading model, 47 percent") at
whatever value it holds at that moment — the live-region *throttling* only affects unprompted
interruption-style announcements, not on-demand reading via explicit focus navigation.

---

## UX Acceptance Criteria

Each is independently testable by a human tester (manual QA pass, `plan.md` Story 5.1.3) or by an
automated Compose/Robolectric test where noted.

### Task completion

1. **Download initiation**: user can go from `Absent` to a visibly-advancing `Downloading` state
   in **1 tap** (the "Download depth model (~100MB)" button).
2. **Cancel**: user can cancel an in-progress download in **1 tap** (the Cancel `TextButton`),
   with no confirmation dialog interrupting the action.
3. **Retry after failure**: user can restart a download from either `Failed` variant in **1 tap**
   (tapping the failed row itself, same target as the existing retry pattern).
4. **No dead ends**: every terminal/blocking-looking state (`Downloading`, `Failed` generic,
   `Failed` stalled) has at least one visible, tappable exit path (Cancel, or Retry) — verified by
   inspecting each of Surfaces 3-7 above; none renders text-only with no actionable control.

### Progress legibility (AC1, AC2, AC4)

5. While downloading with a known total size, the displayed percentage changes at least once
   within any 5-second window of active network transfer — a tester watching the panel for 5
   seconds during a real download must see the number move at least once.
6. The percentage is never observed frozen at a nonzero value for more than `STALL_TIMEOUT_MS`
   (30s) without either continuing to advance or transitioning to a `Failed` state — this is the
   literal fix for the bug's title ("stuck... never improves").
7. When total size is unknown, the panel shows "Downloading model…" with **no percentage digit
   and no "0%"** — a tester must not see a static "0%" under any circumstance, matching AC2's
   explicit requirement.
8. A stalled download (no byte movement for 30s) transitions to a `Failed` state showing the exact
   copy `"This is taking longer than expected. Tap to retry."` — distinguishable by a tester from
   the generic `"Download failed — tap to retry"` shown for a genuine `DownloadManager` failure.

### Error and edge-case handling

9. Generic download failure (`Failed(reason = null)`) shows `"Download failed — tap to retry"` and
   offers a retry action in the same row — no separate navigation required.
10. Stall-triggered failure shows the distinct "taking longer than expected" copy (criterion 8)
    and offers the identical retry action/target as generic failure — a tester should not need to
    learn two different recovery gestures for the two `Failed` variants.
11. Cancelling a download returns the user to the exact same `Absent` state/copy they'd see on
    first ever opening the panel — no lingering "cancelled" indicator, no different button label.
12. Navigating away from the editor mid-download and returning later shows the download's *live*
    current state (still downloading at its current percentage, or `Ready`, or `Failed`) — never
    resets to `Absent` while a transfer is genuinely still in flight in the background.
13. No user-facing copy anywhere in the panel mentions implementation terms — `"DownloadManager"`,
    `"request ID"`, `"HTTP"`, `"ONNX"`, `"Content-Length"` must not appear in any rendered string; a
    tester grep of the rendered strings against these terms should return nothing.

### Non-blocking / no false gating

14. While the panel shows any state other than `Absent`/`Ready` (i.e., `Downloading` or `Failed`),
    a tester can still interact with at least one other annotation-editor control (manual
    measurement tool, drawing tool) without the panel intercepting the tap or otherwise blocking
    input — confirms the "floating, non-blocking overlay" model holds, not just at the layout
    level but at the interaction level.
15. No modal scrim, dialog, or full-screen overlay ever appears as part of this download flow —
    a tester should be able to see the rest of the photo canvas at all times the panel is visible.

### Accessibility (keyboard/TalkBack/contrast)

16. Every actionable control in the panel (Download button, Cancel button, Retry row, Estimate
    button) is reachable via TalkBack linear swipe navigation and via any connected keyboard's Tab
    order — no control is a bare `Row`/`Column` with an `onClick` that skips semantics.
17. The Cancel button has a `contentDescription` of exactly `"Cancel model download"` — verified by
    inspecting the Compose semantics tree or via TalkBack announcement.
18. State transitions (`Absent → Downloading`, `Downloading → Ready`, `Downloading → Failed`,
    `Downloading → Absent`) are announced by TalkBack within one interaction cycle of the
    transition — verified by enabling TalkBack and observing/recording the announcement at each
    transition during a manual pass.
19. Intermediate percentage ticks (e.g. `Downloading(12)` → `Downloading(13)` → ... →
    `Downloading(47)`) are **not** individually announced by TalkBack — a tester with TalkBack
    enabled should not hear more than one announcement per state *boundary*, regardless of how
    many ticks occur within that state.
20. All text in the panel meets WCAG AA contrast (≥ 4.5:1) against the panel's `0xDD1A1A1A`
    background — white body text (`Color.White`) and the existing amber/red accent colors
    (`0xFFFFA000` warning, `0xFFEF5350` error) must be checked against this specific background,
    not assumed from generic Material defaults, since the panel uses a custom dark surface color
    rather than the app's default `Surface` color.
21. Touch targets for the Cancel button and the Retry row meet the minimum 48x48dp touch target
    size (Android convention) even though the panel itself is visually compact — verified via
    Compose semantics bounds inspection or on-device tap-target testing, not just visual sizing.

### Regression (AC6)

22. The existing fast path (`isModelReady()` short-circuit) still transitions directly to `Ready`
    with no `Downloading` flash and no `DownloadManager` enqueue when the model file already
    exists on disk — a tester who already has the model downloaded should see the `Ready` state
    immediately on opening the panel.
23. The existing `Failed` → retry flow (pre-dating this fix) continues to work identically to
    before — no new step, dialog, or copy change beyond the reason-string branching in criterion 9/10.

### Copy tone (per `research/ux.md` and `FolderSyncReconciliationProgress.kt` precedent)

24. All copy is written in plain, non-technical language a non-developer user would understand on
    first read — verified by having someone unfamiliar with the codebase read each panel string
    (Surfaces 2-7's text) and confirm they understand what's happening and what action, if any, is
    available, without needing anything explained.
25. Copy does not imply the rest of the editor is blocked or that the user must wait — verified by
    confirming no panel string uses words like "please wait," "do not leave this screen," or
    similar block-implying language, consistent with the "keep annotating" framing `research/ux.md`
    recommends (contingent on Task 1.2.1c's verification that no other control actually gates on
    `Ready` — if that verification finds a gating control, this criterion's wording should be
    revisited to scope the framing accurately).

---

## Open items carried from research (not blocking this design, flagged for implementer)

- Criterion 25 depends on plan Task 1.2.1c's grep confirming no other `AnnotationEditorScreen`
  control gates on `depthModelUiState == Ready`. If that grep finds a gating control, revise the
  `Absent`/`Downloading` copy to avoid implying "keep annotating" applies to that gated control
  specifically.
- Byte-progress string (Surface 3's second wireframe, Phase 6 Story 6.1.2) is a "nice to have" per
  `research/ux.md` — not required for criteria 5-6 to pass, since the 300ms real polling already
  satisfies them without it. Treat its absence as acceptable if Phase 6 is deprioritized.
- No AskUserQuestion-worthy ambiguity found — the plan's task-level detail (exact copy strings,
  file/line targets) was specific enough that this design could be produced directly from
  `requirements.md` + `research/ux.md` + `implementation/plan.md` without needing a product
  decision.
