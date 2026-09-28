# UX Design: camera-qr-export

Phase 3 (Design) artifact. Turns plan.md's Epic 3.1/3.2 domain glossary and
acceptance criteria into concrete screens, flows, and testable UX acceptance
criteria. Does not contradict plan.md's sealed states
(`QrEncodeUiState`, `QrDecodeUiState`) or ADR-004's fps ceiling — every wireframe
below is annotated with the exact sealed-state case it renders. Where a needed
UI state or signal is **missing** from plan.md, it is called out explicitly in
**§13 Gaps**, not silently invented as a new sealed-type member.

Sources: `requirements.md`, `research/ux.md` (Phase 2), `implementation/plan.md`
(Epic 3.1/3.2, Domain Glossary), `decisions/ADR-004-fps-wcag-reconciliation.md`.

---

## Surface inventory

| # | Surface | Sealed state(s) rendered |
|---|---|---|
| S1 | "Send via QR" entry point (page menu) | n/a (flag-gated menu item) |
| S2 | Encoder screen — Idle / Serializing | `QrEncodeUiState.Idle`, `.Serializing` |
| S3 | Encoder screen — Displaying (main transfer view) | `QrEncodeUiState.Displaying` |
| S4 | Encoder screen — Paused / Complete / Cancelled | `QrEncodeUiState.Paused`, `.Complete`, `.Cancelled` |
| S5 | Encoder screen — accessible / reduce-motion variant | `QrEncodeUiState.Displaying` (with `reduceMotion=true`) |
| S6 | Camera permission rationale dialog (reused shape) | n/a (pre-`QrDecodeViewModel.start()` gate) |
| S7 | "Import via camera" entry point | n/a (flag-gated menu item) |
| S8 | Decoder screen — Idle / PreflightFailed | `QrDecodeUiState.Idle`, `.PreflightFailed(reason)` |
| S9 | Decoder screen — Scanning (camera + reticle + progress) | `QrDecodeUiState.Scanning(uniqueFragments, stalledSeconds)` |
| S10 | Decoder screen — Reassembling / Importing / Success / Failed / Cancelled | `.Reassembling`, `.Importing`, `.Success(pageName)`, `.Failed(QrTransferError)`, `.Cancelled` |
| S11 | Collision-resolution dialog | rendered from `.Importing` when `QrImportService` detects a name collision |

---

## S1 — "Send via QR" entry point

**Where**: page context/share menu (Story 3.1.4). Only visible when
`QrTransferSettings.enabled == true`.

```
┌ Page: "Meeting Notes"  ⋮ ─────────────┐
│                                        │
│  ⋮ Page menu                          │
│  ┌──────────────────────────────┐     │
│  │  Rename                       │     │
│  │  Export → Markdown            │     │
│  │  Delete                       │     │
│  │  ─────────────────────────    │     │
│  │  📷 Send via QR                │◄──  only when flag enabled
│  └──────────────────────────────┘     │
└────────────────────────────────────────┘
```

**Flow**: user opens page → opens menu → taps "Send via QR" → S2 (Idle→Serializing)
opens full-screen.

**Edge cases**:
- Flag disabled → menu item absent entirely (not disabled/greyed — absent, per
  Story 3.1.4 AC). No dead click, no explanation needed since the feature has
  zero surface area when off.
- Empty page (0 blocks) — plan.md doesn't gate this; recommend allowing it
  (serializes to a near-empty markdown string, transfers trivially) rather than
  blocking, since blocking would need a new validation path not in scope.

---

## S2 — Encoder screen: Idle → Serializing

```
┌────────────────────────────────────────┐
│ ←  Send via QR                         │
│                                        │
│                                        │
│            ⏳  Preparing…              │
│      Serializing "Meeting Notes"       │
│                                        │
│                                        │
│                                        │
│              [ Cancel ]                │
└────────────────────────────────────────┘
```

**Flow**: `QrEncodeViewModel.start(pageUuid)` fires → state `Idle` (instant,
not user-visible) → `Serializing` (page/block load + `LogseqPageSerializer` +
`FountainEncoder` construction) → on success, transitions straight to S3
`Displaying`. `Serializing` is expected to be sub-second for the ~2KB target
page; the spinner exists for larger pages so the screen never appears frozen.

**Error/edge cases**:
- `PayloadTooLarge` (Story 1.2.2 AC): `Serializing` fails before ever showing
  a QR frame. Screen shows: **"This page is too large to send via QR (◯ KB,
  max ◯ KB)."** with a single **[Back]** action — no partial/broken QR ever
  renders. This maps to `QrTransferError.PayloadTooLarge.toUiMessage()`
  (Story 1.1.2). Plan.md's `QrEncodeUiState` has no explicit
  `Failed`/`Error` case distinct from `Idle`/`Cancelled` — see **Gap G1**.
- Cancel tap during `Serializing` → `Cancelled` (S4), same as cancelling
  during `Displaying`.

---

## S3 — Encoder screen: Displaying (ADR-004 compliant)

This is the core sender view. Renders `QrEncodeUiState.Displaying(frameIndex,
totalCycled, chunkCount, estBytes)`.

```
┌────────────────────────────────────────┐
│ ←  Send via QR                    ⓘ    │
│                                        │
│  Meeting Notes · 5 blocks · ~2 KB      │  ← pre-flight summary (Story 3.1.3)
│  ~12 frames · ~30–45s                  │
│                                        │
│         ┌──────────────────┐          │
│         │ ▓▓░░▓▓░░▓▓░░▓▓░░ │          │  ← inset card, ≤60% viewport area
│         │ ░░▓▓░░▓▓░░▓▓░░▓▓ │          │    (ADR-004 area exemption),
│         │ ▓▓░░▓▓▓▓░░▓▓░░▓▓ │          │    static background around it,
│         │ ░░▓▓░░▓▓▓▓░░▓▓░░ │          │    ≤3fps / 2.5fps default advance
│         └──────────────────┘          │
│                                        │
│         frame 4 of ~12 (cycling)       │
│                                        │
│  🔒  No internet connection used       │  ← persistent air-gap assertion
│                                        │
│              [ Cancel ]                │
└────────────────────────────────────────┘
```

**Flow**: page/block loaded → serialized → chunked → the ViewModel advances
`frameIndex` on a delay loop (400ms/frame at default 2.5fps, ADR-004) and
loops indefinitely once `totalCycled` exceeds `chunkCount` — the fountain
encoder never "runs out," it keeps emitting redundant parts so a receiver
that joins late or drops frames still converges. The screen therefore has
**no automatic end state** — see **Gap G2** on how `Complete` is reached.

**Interaction model**:
- Tap outside / hardware back → confirmation is *not* required for Cancel per
  plan.md (no AC blocks it); Cancel is immediate and safe since nothing has
  been written to disk on the sender side (sender never mutates state).
- **First-use explainer is now a binding plan.md AC (Story 3.1.3, triad review
  fix)** — not optional additive UX. The first time a user opens S3, a
  one-time, dismissible banner shows the expectation-setting copy from
  research/ux.md §2 ("this is a continuous scan, not a photo — keep both
  screens visible for about 30–60 seconds"), reusing a "seen this before"
  flag persisted via `platform.Settings` so it does not reappear on
  subsequent sends. Rendered as a dismissible banner over/above the QR card,
  not a persistent info icon — dismissing it never pauses or cancels the
  in-progress transfer.

**Error/edge cases**:
- Backgrounding mid-send (app switched away, screen locks): plan.md's
  `Paused` state is presumed to represent exactly this (lifecycle-driven
  pause of the frame-advance loop). See S4 and **Gap G3** — plan.md does not
  specify the trigger/exit conditions for `Paused` the way it specifies every
  other transition.

---

## S4 — Encoder screen: Paused / Complete / Cancelled

```
Paused (assumed: app backgrounded / screen off mid-send)
┌────────────────────────────────────────┐
│ ←  Send via QR                         │
│                                        │
│         ┌──────────────────┐          │
│         │                    │          │  ← frame frozen on last index,
│         │   ⏸  Paused        │          │    not flashing (no WCAG risk
│         │                    │          │    while paused)
│         └──────────────────┘          │
│  Reopen this screen to resume sending  │
│              [ Cancel ]                │
└────────────────────────────────────────┘

Complete (assumed: user-initiated "Done" — see Gap G2)
┌────────────────────────────────────────┐
│ ←  Send via QR                         │
│                                        │
│              ✅  Sent                  │
│      Meeting Notes was displayed       │
│         for the other device            │
│                                        │
│              [ Done ]                  │
└────────────────────────────────────────┘

Cancelled
┌────────────────────────────────────────┐
│ ←  Send via QR                         │
│                                        │
│           Transfer cancelled            │
│                                        │
│              [ Close ]                 │
└────────────────────────────────────────┘
```

**Exit paths**: every terminal/interrupted state has exactly one primary
action that returns the user to the page they started from — `[Cancel]` →
`Cancelled` → `[Close]` → page view; `[Done]` → page view. No state strands
the user without a way back.

---

## S5 — Encoder screen: accessible / reduce-motion variant

Renders `QrEncodeUiState.Displaying` with `QrTransferSettings.reduceMotion =
true` (Story 3.1.3 AC: "drops to 1–2 fps + tap-to-advance").

```
┌────────────────────────────────────────┐
│ ←  Send via QR              [Reduce ✓] │  ← toggle, persists via settings
│                                        │
│  Meeting Notes · 5 blocks · ~2 KB      │
│  ~12 frames · manual advance           │
│                                        │
│         ┌──────────────────┐          │
│         │ ▓▓░░▓▓░░▓▓░░▓▓░░ │          │  ← frame is STATIC until tapped
│         │ ░░▓▓░░▓▓░░▓▓░░▓▓ │          │    or "Next" pressed
│         │ ▓▓░░▓▓▓▓░░▓▓░░▓▓ │          │
│         └──────────────────┘          │
│                                        │
│         frame 4 of ~12                 │
│                                        │
│         [ ◀ Prev ]   [ Next ▶ ]        │  ← tap-to-advance, never auto
│                                        │    faster than 2fps if user
│  🔒  No internet connection used       │    holds Next
│              [ Cancel ]                │
└────────────────────────────────────────┘
```

**Flow**: identical screen structure to S3, but the frame-advance loop is
disabled; `Next`/`Prev` step `frameIndex` directly. This mode is reachable
both as an explicit user toggle *and* should be the default when the OS
"reduce motion" accessibility setting is on (recommended; plan.md ties
`reduceMotion` to `QrTransferSettings`, a user-facing toggle, but doesn't
specify OS-level auto-detection — flagged as **Gap G4**, a nice-to-have, not
blocking).

**Accessibility notes**: this mode serves both photosensitivity (never
exceeds 2fps even if held) and motor/switch-access users (fully self-paced,
no timing pressure) per research/ux.md §3.

---

## S6 — Camera permission rationale dialog (reused shape)

Reuses `CameraPermissionRationaleDialog` (`ui/annotate/PermissionRationaleDialog.kt`)
verbatim in shape; only the rationale copy is feature-specific.

```
┌──────────────────────────────────────┐
│         📷                            │
│                                        │
│   SteleKit needs camera access to     │
│   scan the transfer code from the     │
│   other device.                       │
│                                        │
│   You can still receive pages using   │
│   the existing git sync or file       │
│   import instead.                     │
│                                        │
│      [ Not now ]      [ Continue ]    │
└──────────────────────────────────────┘

Permanently denied variant:
│      [ Not now ]   [ Open Settings ]  │
```

**Flow**: shown before `QrDecodeViewModel.start()` is allowed to proceed.
"Continue" → OS permission prompt → granted → S8/S9; denied → dialog
re-shows or, if permanently denied, swaps the confirm button to "Open
Settings" (existing pattern, `isPermanentlyDenied` param).

**Exit path**: "Not now" always dismisses back to the page/import entry
point — never a dead end, and explicitly offers the non-camera alternative
(git sync / file import) per the existing dialog's non-punitive-escape-hatch
convention (research/ux.md §0).

---

## S7 — "Import via camera" entry point

```
┌ Import ▾ ──────────────────────────────┐
│  Import from file                      │
│  Import from folder                    │
│  ─────────────────────────────────     │
│  📷 Import via camera                  │◄── flag-gated (Story 3.2.4b)
└─────────────────────────────────────────┘
```

Same flag-gating rule as S1: absent (not disabled) when
`QrTransferSettings.enabled == false`.

---

## S8 — Decoder screen: Idle / PreflightFailed

```
Idle (brief, pre-permission-check)
┌────────────────────────────────────────┐
│ ←  Import via camera                   │
│              Starting…                 │
└────────────────────────────────────────┘

PreflightFailed(HardwareUnavailable) — e.g. JVM desktop, no camera wired
┌────────────────────────────────────────┐
│ ←  Import via camera                   │
│                                        │
│         📷🚫  Camera unavailable        │
│                                        │
│  This device can't scan QR transfer    │
│  codes yet. Try importing from a       │
│  file instead, or use a device with    │
│  a camera.                             │
│                                        │
│         [ Import from file ]           │
│              [ Back ]                  │
└────────────────────────────────────────┘
```

**Flow**: `QrDecodeViewModel.start()` checks `cameraFrameSource.isAvailable`
before requesting permission or entering `Scanning` (Story 3.2.2 AC) — this
is a genuine pre-flight gate, not a runtime failure, so the user never sees
a spinner that goes nowhere.

**Exit path**: `[Import from file]` routes to the existing non-QR import
path; `[Back]` returns to wherever the entry point was invoked from. No
dead end on unsupported hardware (this is exactly the Desktop-receive
Epic 4.3 deferral surfacing gracefully in v1).

---

## S9 — Decoder screen: Scanning

Renders `QrDecodeUiState.Scanning(uniqueFragments, stalledSeconds)`.

```
┌────────────────────────────────────────┐
│ ←  Import via camera                   │
│ ┌────────────────────────────────────┐ │
│ │                                    │ │
│ │        ┌──────────────┐           │ │  ← camera preview
│ │        │              │           │ │
│ │        │   ┌──────┐   │           │ │  ← reticle overlay,
│ │        │   │      │   │           │ │    contentDescription =
│ │        │   └──────┘   │           │ │    "Point camera at the
│ │        │              │           │ │    SteleKit transfer code"
│ │        └──────────────┘           │ │
│ │                                    │ │
│ └────────────────────────────────────┘ │
│                                        │
│   Receiving… (7 fragments)             │  ← count, NOT linear %
│   ▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░ (non-linear, │
│                            stalls visibly)
│                                        │
│              [ Cancel ]                │
└────────────────────────────────────────┘

Stalled (stalledSeconds ≥ 8–10s with zero new fragments)
│   Receiving… (7 fragments)             │
│   ⚠ Not receiving new data —           │
│     try moving closer or adjusting     │
│     the angle.                         │
```

**Flow**: camera preview streams; each newly-decoded, previously-unseen
fragment increments `uniqueFragments`, fires one haptic tick + optional audio
tick (Story 3.2.3 AC), and nudges the (non-linear, fountain-aware) progress
indicator. Reaching sufficient coverage transitions to S10 `Reassembling`.
**Haptic/audio ticks are an enhancement, never the sole feedback channel
(binding plan.md AC, Story 3.2.3, triad review fix)**: the "Receiving… (N
fragments)" text line is always present and always sufficient on its own —
haptics-disabled devices, hardware with no vibration motor, and muted audio
all still convey full progress via text alone.

**Error/edge cases** (mapped to existing signals, gaps flagged where the
signal doesn't exist yet):
- **Stalled scan**: `stalledSeconds` field already exists on `Scanning` —
  drives the "move closer / adjust angle" copy verbatim from research/ux.md
  §4 (mirrors the Sparrow/BC-UR precedent). Bar never animates without
  genuine new-fragment progress (explicit AC, Story 3.2.3).
- **Wrong/unrelated QR code**: research/ux.md §4 and Story 3.2.3's AC both
  call for **"That's not a SteleKit transfer code."** as distinct copy from
  the generic stall message. Plan.md's `QrScanner.decode(frame): QrChunk?`
  (Task 3.2.2a) returns `null` for *both* "no QR in the frame at all" and "a
  QR was found but failed the magic-byte/version check" — the `Scanning`
  state's two fields (`uniqueFragments`, `stalledSeconds`) can't distinguish
  these to choose which copy to show. **Flagged as Gap G5** — do not invent a
  new field here; call it out for the review loop instead.
- **Ambient light too low**: `CameraFrame.luminanceBytes` is already
  available (Y-plane), so a mean-luminance heuristic can be computed
  client-side in `QrDecodeViewModel` without any new type — this is *not* a
  gap, it's an achievable enhancement within existing types. However, like
  the wrong-QR case, `Scanning`'s fields have no slot to carry a
  low-light-specific hint separately from the generic stall message — see
  **Gap G5** (same underlying limitation: `Scanning` conflates all "why
  isn't this working" reasons into one stall timer).
- **Concurrent second sender** (Story 3.3.4, **now binding, triad review
  fix**): a frame from a different `TransferId` is ignored and logged, and
  the UI **MUST** show a transient, visible message — "Another transfer
  started — ignoring it" (or materially equivalent copy) — not a silent drop
  with no user-visible signal. This is a required AC, not the previous "can
  warn" soft language. Does not change `uniqueFragments` or `stalledSeconds`,
  and does not interrupt reception of the active session.
- **Handheld-fatigue mitigation tip** (Story 3.2.3, **now binding, triad
  review fix — pre-mortem item #5**): once `Scanning` has been continuously
  active for more than 15 seconds, a one-time, dismissible tip appears —
  "Tip: try propping your phone against something stable for a steadier
  scan." Shown at most once per scan session (not repeated on every stall).
  This surfaces the handheld-fatigue mitigation that previously only existed
  in Story 3.3.5's hardware-in-the-loop test matrix (which tests the
  handheld condition) but never reached the actual product-facing UX.
- **First-use explainer** (Story 3.2.3, **new, triad review fix**): the first
  time a user opens this scanning surface, a one-time, dismissible explainer
  states what's about to happen (e.g. "Point your camera at the other
  device's screen — this may take 30–60 seconds"), mirroring S3's
  sender-side explainer and using the same local "seen this before" flag
  pattern (`platform.Settings`-backed). Previously the receiver side had no
  equivalent onboarding at all, unlike the sender side's (recommended, now
  binding) expectation-setting banner.
- **Backgrounding mid-scan** (Story 3.3.2, UQ-3): fragments accumulated
  survive within the same VM lifetime; on return to foreground, `Scanning`
  resumes with its prior `uniqueFragments` count intact (not reset to 0).
  Copy on the way out (if the OS shows a paused/backgrounded affordance)
  should say "Paused — reopen camera to continue" per UQ-3's resolution.

**Exit path**: `[Cancel]` → `Cancelled` (S10), always available, always
tears down the scope (Story 3.2.2 AC on `close()`).

---

## S10 — Decoder screen: Reassembling / Importing / Success / Failed / Cancelled

```
Reassembling (brief — proof-gate check, Story 1.2.3)
┌────────────────────────────────────────┐
│              Checking…                 │
│      Verifying the received data       │
└────────────────────────────────────────┘

Importing (collision-free path)
┌────────────────────────────────────────┐
│         ⏳  Importing…                 │
│      Adding "Meeting Notes" to         │
│           your graph                   │
└────────────────────────────────────────┘

Success(pageName)
┌────────────────────────────────────────┐
│              ✅  Imported!             │
│                                        │
│      "Meeting Notes" was added to      │
│           your graph.                  │
│                                        │
│           [ Open page ]                │
│              [ Done ]                  │
└────────────────────────────────────────┘

Failed(QrTransferError)
┌────────────────────────────────────────┐
│              ⚠  Import failed          │
│                                        │
│   <QrTransferError.toUiMessage()>      │
│   e.g. "This transfer couldn't be      │
│   verified — some data may have been   │
│   corrupted."                          │
│                                        │
│           [ Try again ]                │
│              [ Close ]                 │
└────────────────────────────────────────┘

Cancelled
┌────────────────────────────────────────┐
│           Import cancelled              │
│              [ Close ]                 │
└────────────────────────────────────────┘
```

**Flow**: `Reassembling` = `ChunkBuffer.reassemble()` proof gate (necessary +
sufficient check, Story 1.2.3) → `Right` → `Importing` (`QrImportService`
pipeline, collision check may open S11 mid-step) → `Success`. `Left` at any
point → `Failed(QrTransferError)` with the specific `toUiMessage()` for that
error (`ChunkDecodeFailed`, `IncompleteTransfer`, `IntegrityCheckFailed`,
`PayloadTooLarge`, `TransferCancelled`, `MarkdownParseFailed`) — never a
generic "something went wrong."

**Exit path**: every terminal state has one primary action back to a known
place (`Open page`/`Done` → the imported page or graph home; `Try again` →
back to S8/S9; `Close` → import entry point). No dead ends.

---

## S11 — Collision-resolution dialog

Reuses the `CapturePreviewDialog` Save/Discard-with-spinner shape (button
disables + spinner replaces label while work is in flight; dialog stays up).

```
┌──────────────────────────────────────────┐
│  A page named "Meeting Notes" already     │
│  exists.                                  │
│                                            │
│  [ Keep both ]  [ Overwrite ]  [ Cancel ] │
└──────────────────────────────────────────┘

While a choice is being written:
│  [ Keep both ]  [ ⏳ Overwriting… ]  [ Cancel ] │  ← chosen button shows
│                                                    spinner, others disabled,
│                                                    onDismissRequest blocked
```

**Flow**: surfaces mid-`Importing` when `QrImportService` detects an
existing page of the reassembled name. `Keep both` writes under a
disambiguated name (e.g. "Meeting Notes (2)"); `Overwrite` replaces the
existing page; `Cancel` aborts the import entirely (→ `Cancelled`, not
`Failed` — this is a user choice, not an error).

**Exit path**: three explicit choices, none of which strands the user;
`Cancel` is always available and not blocked even mid-write (only the two
write-triggering buttons disable during their own in-flight write, matching
`CapturePreviewDialog`'s existing `onDismissRequest = { if (!isImporting)
onDiscard() }` guard pattern — recommend the same asymmetry: block accidental
dismiss-by-tap-outside during write, but keep the explicit `Cancel` button
always tappable).

---

## 12. UX Acceptance Criteria (human-testable)

### Task completion
1. A user can start sending a page via QR in **≤ 2 taps** from an open page:
   page menu → "Send via QR" (S1 → S2/S3).
2. A user can start importing via camera in **≤ 2 taps** from the import
   entry point: Import menu → "Import via camera" (S7 → S6/S8).
3. A user can cancel an in-progress send or receive in **exactly 1 tap**
   (`[Cancel]` visible on every non-terminal encoder/decoder screen — S3, S4
   Paused, S9).
4. A user can resolve a page-name collision in **1 tap** (S11: Keep
   both/Overwrite/Cancel, no further sub-dialogs).

### No dead ends
5. Every error state (`PreflightFailed`, `Failed(QrTransferError)`,
   `Cancelled` on both sides, permission-denied) offers at least one action
   that returns the user to a known, functional screen — verified surface by
   surface in S4, S6, S8, S10 above. No error state is a terminal blank
   screen with only a system back-gesture as the way out.
6. Permission denial (S6) never fully blocks the feature area: "Not now"
   always exits to a working alternative (existing git sync / file import),
   never leaves the user stuck on the rationale dialog.

### Error-state specificity
7. `PayloadTooLarge` shows the actual size and max size in the message (not
   a generic "too big" string) and offers `[Back]` — S2.
8. Stalled scan (`stalledSeconds ≥ 8`) shows the exact copy "Not receiving
   new data — try moving closer or adjusting the angle" (or materially
   equivalent actionable phrasing) — S9. Never shows a smoothly-animating
   progress indicator with zero new fragments behind it (this is
   independently testable: freeze `uniqueFragments`, confirm the bar's
   *visual* state does not advance).
9. Every `QrTransferError` variant (`ChunkDecodeFailed`, `IncompleteTransfer`,
   `IntegrityCheckFailed`, `PayloadTooLarge`, `TransferCancelled`,
   `MarkdownParseFailed`) renders a distinct, plain-language message via
   `toUiMessage()` — no two variants may share identical UI copy (verifies
   Story 1.1.2's exhaustive `when`).
10. Page-name collision never silently overwrites or silently
    duplicates — S11's modal is mandatory and blocking before any write
    (verified by: attempting to dismiss via tap-outside during an active
    write does nothing, per the `onDismissRequest` guard).
11. Camera-permission-denied and hardware-unavailable are visually and
    textually distinct states (S6 vs S8 `PreflightFailed`) — a user should
    never see "camera unavailable" copy when the real cause was a
    permission denial, or vice versa.

### Accessibility
12. Every interactive control in S1–S11 (menu items, dialog buttons, Cancel,
    Next/Prev in S5, reticle region) is reachable via standard focus order
    (Tab / D-pad / switch scan) and activatable without a pointer — no
    custom chrome bypasses Compose's default `AlertDialog`/`Button` focus
    traversal.
13. The QR display canvas (S3) and the camera viewfinder (S9) each carry a
    `Modifier.semantics { contentDescription = "..." }` label with
    *live* status text (e.g. "Sending, frame 4 of about 12" / "Point camera
    at the SteleKit transfer code, 7 fragments received"), not a static
    label — following the `AnnotationEditorScreen.kt` idiom exactly, and
    verifiable with TalkBack/VoiceOver announcing updated text as the
    transfer progresses.
14. Color is never the sole signal for scanning state ("locked on" vs.
    "searching" in S9 reticle) — shape/icon + text must also convey it.
    Testable by simulating protanopia/deuteranopia and confirming the state
    is still distinguishable.
15. Text/background contrast on all persistent status lines (pre-flight
    summary, "No internet connection used," fragment count, error messages)
    is **≥ 4.5:1** against their card/background in both light and dark
    theme — measurable with a contrast checker against the actual rendered
    colors.
16. **WCAG 2.3.1 flash-safety, explicitly verified here (not just in plan.md
    implementation)**:
    - The default animated QR frame rate **never exceeds 3 fps** (default
      2.5 fps / 400 ms per frame) — measurable by timing frame changes on
      S3 with a stopwatch or frame-capture over ≥10 seconds; any measured
      rate > 3.33 fps (300ms/frame) fails this criterion outright regardless
      of `QrTransferSettings` configuration, since construction is supposed
      to reject/clamp >3fps.
    - The QR is rendered inside a bordered inset card occupying **≤ 60% of
      viewport area** on a static (non-flashing) background — measurable by
      screenshot + area calculation on S3.
    - A **reduce-motion / accessible transfer mode** (S5) exists as a
      first-class, discoverable toggle (not buried in a settings submenu
      three levels deep) and, once enabled, the QR frame **only changes on
      an explicit tap** ("Next"/"Prev") — measurable by holding the "Next"
      button down/rapid-tapping it and confirming the advance rate never
      exceeds 2 fps even under rapid tapping (i.e., the app itself
      rate-limits, not just relies on human tap speed).
    - Both the ≤3fps default ceiling and the reduce-motion alternative are
      independently verifiable by a human tester with a stopwatch and the
      running app — neither requires reading source code to confirm.

---

## 13. Gaps found versus plan.md's sealed states

These are called out for the review loop rather than silently patched with
invented states, per this task's instructions.

- **G1 — No `Failed`/`Error` case on `QrEncodeUiState`.** The sealed type is
  `Idle, Serializing, Displaying, Paused, Complete, Cancelled` (plan.md
  Domain Glossary). But Story 1.2.2's `FountainEncoder` construction can
  return `Left(QrTransferError.PayloadTooLarge(...))`, and `Serializing`
  itself (page/block load) can fail for other reasons (e.g. page deleted
  concurrently). There is no sealed case to represent "serialization/encoder
  construction failed" distinctly from `Cancelled` (which implies user
  intent) or `Idle` (which implies not-yet-started). S2 above renders this
  as a `PayloadTooLarge`-specific message, but there's no home for it in the
  state machine as specified. **Recommend to Phase 3 review**: add
  `QrEncodeUiState.Failed(QrTransferError)` mirroring the decoder side's
  `Failed` case, rather than overloading `Cancelled`.

- **G2 — No specified trigger for `QrEncodeUiState.Complete`.** The fountain
  encoder is designed to loop indefinitely (plan.md: "Encode side should keep
  cycling frames indefinitely... until explicitly stopped"), and the sender
  has no back-channel to know the receiver actually finished (QR/camera is
  one-directional per the requirements). None of Story 3.1.2's or 3.1.3's
  acceptance criteria describe an automatic or user-initiated path from
  `Displaying` to `Complete` — only `cancel()` → `Cancelled` is specified.
  S4 above assumes `Complete` is reached via a user-initiated affirmative
  "Done sending" action (distinct from Cancel, since Cancel implies
  abandonment and Complete implies success), but this button and its wiring
  do not appear in any Epic 3.1 story/task. **Recommend to Phase 3 review**:
  either add an explicit "Done" action + AC to Story 3.1.3, or clarify that
  `Complete` is unreachable in v1 and should be removed from the sealed type
  (dead code the exhaustive `when` would otherwise have to handle with no
  test coverage).

- **G3 — Unspecified trigger/exit for `QrEncodeUiState.Paused`.** Unlike the
  receiver side (`TransferSession`, Story 3.3.2, has an explicit AC for
  backgrounding behavior), no Epic 3.1 story specifies what causes
  `Displaying → Paused` or `Paused → Displaying` (e.g., screen lock, app
  backgrounded, explicit user pause button?) or whether the frame-advance
  loop's timer state is preserved or reset. S4 above assumes lifecycle-driven
  pause-on-background as the most plausible reading, consistent with the
  receiver-side precedent, but this is inference, not a specified AC.
  **Recommend to Phase 3 review**: add a story/AC for `Paused` symmetric to
  Story 3.3.2's receiver-side backgrounding resolution.

- **G4 — No specified OS-level "reduce motion" auto-detection.** Research
  recommends the accessible mode serve "photosensitivity and motor/switch
  access users" (research/ux.md §3), and plan.md's `reduceMotion` is a
  `QrTransferSettings` field, but no story specifies whether it auto-enables
  from the platform's OS-level reduce-motion accessibility setting or is
  purely an explicit manual toggle. Non-blocking (manual toggle alone
  satisfies WCAG 2.3.1 since it's discoverable and available), but worth a
  product decision.

- **G5 — `QrDecodeUiState.Scanning`'s two fields cannot carry the distinct
  hint reasons the plan's own acceptance criteria promise.** `Scanning(
  uniqueFragments, stalledSeconds)` (Domain Glossary) can only drive one
  generic "stalled" message. But:
  - Story 3.2.3's AC explicitly requires a **different** message for a wrong
    QR ("That's not a SteleKit transfer code") than for a stall ("move
    closer / adjust angle").
  - `QrScanner.decode(frame): QrChunk?` (Task 3.2.2a) collapses "no QR
    detected in frame," "QR detected but wrong magic/version," and "correct
    magic but past-`TransferId`" all down to `null` (or a silent drop) —
    there's no signal left by the time it reaches `Scanning`'s two fields to
    distinguish which message to show.
  - Research also recommends a distinct "too dark to scan" hint
    (research/ux.md §4), which — like the wrong-QR case — has no field to
    live in on `Scanning` either, even though the raw signal (mean luminance
    of `CameraFrame.luminanceBytes`) is already available upstream.

  **Recommend to Phase 3 review**: either (a) give `QrScanner.decode` a
  richer return type (e.g. `sealed interface ScanResult { NoQr, WrongFormat,
  Chunk(QrChunk) }`) so the ViewModel can distinguish causes, and add a
  `hint: ScanHint` (enum: `None, Stalled, WrongCode, LowLight`) field to
  `Scanning`, or (b) explicitly descope the wrong-QR/low-light differentiated
  messaging to "generic stall message only" for v1 and update Story 3.2.3's
  AC to match. Do not add this field unilaterally in implementation without
  a plan.md update, since it changes a documented sealed-type signature.

---

## Summary

- **11 surfaces** designed (S1–S11), each with a wireframe, interaction flow,
  and error/edge-case table, every one mapped to an actual
  `QrEncodeUiState`/`QrDecodeUiState` case (or explicitly flagged where no
  case exists).
- **16 UX acceptance criteria** written (§12), covering task-completion step
  counts, no-dead-ends, error-state specificity, and accessibility —
  including two acceptance criteria (16) that make the ≤3fps WCAG 2.3.1
  ceiling and the reduce-motion/tap-to-advance alternative independently
  human-testable rather than only implementation-level assertions.
- **5 gaps (G1–G5)** found versus plan.md's existing state definitions,
  flagged for the review loop rather than silently patched:
  - G1: no `Failed` case on `QrEncodeUiState`.
  - G2: no specified trigger for reaching `Complete` (one-directional
    transport with no back-channel).
  - G3: no specified trigger/exit for `Paused`.
  - G4: no spec for OS-level reduce-motion auto-detection (non-blocking).
  - G5: `Scanning`'s two fields can't carry the distinct wrong-QR /
    low-light / stalled hints the plan's own acceptance criteria promise.
