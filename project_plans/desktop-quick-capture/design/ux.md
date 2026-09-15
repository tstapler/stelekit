# UX Design: Desktop Quick Capture (v1 — in-process hotkey popup)

Source docs: `../requirements.md`, `../research/ux.md`, `../implementation/plan.md` (Epics 1.1–1.5,
2.1–2.3). Scope matches ADR-002: in-process hotkey popup + pending-captures foundation only.
OS-native surfaces (macOS Services menu, Nautilus script, Windows registry handler) are Phase 2 and
are not designed here beyond the one file-format contract they'll eventually write into.

## Surfaces designed (4)

| # | Surface | Interactive? | Treatment |
|---|---------|--------------|-----------|
| 1 | Capture popup window (7 states) | Yes — the one surface the user types into | Full wireframe + flow |
| 2 | First-run / default-hotkey discovery notice | Minimal (dismiss only) | Condensed |
| 3 | Hotkey-conflict / registration-failure notification | Minimal (dismiss / open settings) | Condensed |
| 4 | Pending-captures directory file format | None — disk artifact, no UI | Condensed |

Surfaces 2 and 3 are now built by the plan as Stories 1.4.2 and 1.4.3
(`implementation/plan.md`) — added after an initial pass where Epic 1.3 only registered the
hotkey silently and failed open with no user-visible signal, and no first-run notice existed.
Nielsen's heuristic #1, *visibility of system status*, would otherwise be violated by silence in
both cases — a user who doesn't know the hotkey, or whose hotkey silently didn't register, would
have no way to discover or diagnose that. Both are designed here as minimum-viable additions
with acceptance criteria that Stories 1.4.2/1.4.3 and validation.md rows 13/14 implement against.

---

## Surface 1: Capture popup window

### Layout (baseline: empty/typing states)

```
┌─────────────────────────────────────────────────┐
│ ● SteleKit Quick Capture                         │  ← accessible dialog title
├───────────────────────────────────────────────────┤
│ ┌───────────────────────────────────────────────┐ │
│ │ Type a thought…                                │ │  ← autofocused, multi-line
│ │                                                 │ │
│ └───────────────────────────────────────────────┘ │
│                                                     │
│  Esc save & close  ·  Ctrl+Enter save now          │  ← persistent keyboard hint
└─────────────────────────────────────────────────────┘
```
~480×140px, undecorated, always-on-top, no taskbar entry — matches the Raycast/Quick
Note/Spotlight mental model research/ux.md §1–2 establishes as the transferable pattern.

### State: Typing
```
┌─────────────────────────────────────────────────┐
│ ● SteleKit Quick Capture                         │
├───────────────────────────────────────────────────┤
│ ┌───────────────────────────────────────────────┐ │
│ │ Buy milk on the way home█                      │ │
│ └───────────────────────────────────────────────┘ │
│  Esc save & close  ·  Ctrl+Enter save now          │
└─────────────────────────────────────────────────────┘
```
Plain `Enter` inserts a newline (multi-line thoughts are supported); only `Ctrl+Enter`
(`Cmd+Enter` on macOS) submits. This must be stated explicitly in the field's placeholder or a
first-open tooltip — a text field with a visible "save" hint but standard `Enter`-inserts-newline
behavior is a common source of "why didn't that save?" confusion (Krug: don't make the user think
about which key does what).

### State: Saving
```
┌─────────────────────────────────────────────────┐
│ ● SteleKit Quick Capture                         │
├───────────────────────────────────────────────────┤
│ ┌───────────────────────────────────────────────┐ │
│ │ Buy milk on the way home   (field disabled)    │ │
│ └───────────────────────────────────────────────┘ │
│  ⟳ Saving…                                         │
└─────────────────────────────────────────────────────┘
```
Field is disabled (not hidden) so the text stays visible and reassures the user nothing was lost
mid-write — Nielsen #1 (visibility of system status).

### State: Saved (auto-dismiss)
```
┌─────────────────────────────────────────────────┐
│ ✓ Saved to today's journal                       │
└─────────────────────────────────────────────────────┘
```
Shown ~600ms (per plan Task 1.4.1d) then the window closes itself; names the destination
("today's journal") per research/ux.md's destination-transparency precedent from
`VoiceCaptureActivity.DoneContent`. No action required — but see Accessibility below: a
timed auto-dismiss must not be the *only* way out for a screen-reader or motor-impaired user who
needs longer to perceive it.

### State: Save error
```
┌─────────────────────────────────────────────────┐
│ ● SteleKit Quick Capture                         │
├───────────────────────────────────────────────────┤
│ ┌───────────────────────────────────────────────┐ │
│ │ Buy milk on the way home█                      │ │  ← text preserved, still editable
│ └───────────────────────────────────────────────┘ │
│  ⚠ Couldn't save — journal file is locked.        │
│  [ Retry ]              [ Copy text & close ]      │
└─────────────────────────────────────────────────────┘
```
Two explicit exits, not one — this is a deliberate change from a single "Esc dismisses" affordance
in every other state:
- **Retry** re-attempts the identical write chain (`CaptureWriter.writeCapture`).
- **Copy text & close** copies the captured text to the system clipboard, then closes — this is
  the exit path when the underlying failure isn't transient (e.g. disk full, permissions). Per
  research/ux.md §4c, silently discarding on a plain Escape here would be strictly worse than
  Android's always-open Snackbar, since Android never lets you lose the draft. `Escape` is bound to
  **Copy text & close**, not a bare discard, so the one failure mode this feature could regress
  from Android (losing the thought) has a designed exit that doesn't require successfully saving.

### State: No active graph
```
┌─────────────────────────────────────────────────┐
│ ● SteleKit Quick Capture                         │
├───────────────────────────────────────────────────┤
│  No graph is open.                                 │
│  Open SteleKit to choose or create one.            │
│                                                     │
│                  [ Open SteleKit ]                 │
└─────────────────────────────────────────────────────┘
```
No text field is shown — there's nowhere to save to, so offering one would invite a save attempt
that's guaranteed to fail (error prevention, Nielsen #5). `Escape` closes the popup outright (there
is no text to lose). `Enter`/click on the button opens the main SteleKit window.

### State: Graph locked (paranoid mode)
```
┌─────────────────────────────────────────────────┐
│ ● SteleKit Quick Capture                         │
├───────────────────────────────────────────────────┤
│  🔒 Vault is locked.                               │
│  Open SteleKit and unlock it to capture.           │
│                                                     │
│                  [ Open SteleKit ]                 │
└─────────────────────────────────────────────────────┘
```
Same shape as No active graph — one explanatory sentence, one action, `Escape` closes. Mirrors
`CaptureTileService`'s locked-vault precedent per the plan.

### Interaction flow

```
Hotkey press
    │
    ▼
Is a popup already Shown? ──yes──▶ Re-focus existing window
    │no                              (text untouched, no dupe window)
    ▼
resolveCaptureAvailability()
    │
    ├─ NoActiveGraph ──▶ [No active graph] state, autofocus "Open SteleKit"
    ├─ GraphLocked   ──▶ [Graph locked] state, autofocus "Open SteleKit"
    └─ available     ──▶ [Empty] state, autofocus text field
                              │
                              ▼ user types
                          [Typing] state
                              │
              ┌───────────────┼────────────────┐
              ▼               ▼                ▼
        Ctrl/Cmd+Enter      Escape        (window loses OS focus —
              │           text blank?      see Edge cases below)
              ▼             │    │
        [Saving] state    yes   no
              │             │    ▼
      ┌───────┴──────┐      │  auto-save runs (→ [Saving])
      ▼              ▼      │    │
   success        failure   │    ▼
      │              │      │  same success/failure fork as
      ▼              ▼      │  Ctrl+Enter, below
 [Saved] state  [Save error]│
  (auto-hide     state      ▼
   ~600ms,     (Retry /  [Hidden] —
   focus       Copy&close) focus restored
   restored)      │        to prior window
      │           ▼
      ▼      Retry → back to [Saving]
 [Hidden] —  Copy&close / Escape →
 focus        clipboard write,
 restored     → [Hidden]
```

### Edge cases and error handling (explicit)

- **No active graph / locked graph**: covered above — placeholder replaces the text field
  entirely; single next-step action; `Escape` always available since there's nothing to lose.
- **Save fails**: text is never cleared or lost; `Retry` and `Copy text & close` are both always
  present (no silent auto-retry, no silent discard).
- **Hotkey pressed again while popup is open**: re-focuses the existing window (bring-to-front +
  restore text-field focus) rather than opening a second window or resetting typed text — matches
  the plan's idempotent `show()` (Epic 1.2 AC) and prevents the "where did my half-typed note go"
  failure mode a naive re-trigger would cause.
- **Window loses OS focus while typing** (user alt-tabs away without using Esc or Ctrl+Enter):
  now handled — Task 1.4.1g (`implementation/plan.md`) wires a `WindowFocusListener` that calls
  `controller.dismiss()` on `windowLostFocus()`, identical to `Escape` (auto-save if non-blank,
  else close). Always-on-top keeps the popup visually on top but does not itself prevent OS focus
  from moving to another app, so this listener — not the always-on-top flag — is what prevents an
  orphaned window with a stale draft sitting on screen after the user has moved on. Matches
  Android's scrim-tap-to-save precedent.
- **Hotkey never fires (OS-level conflict)**: see Surface 3 below, now built as Story 1.4.3; this
  is a distinct failure from anything renderable inside the popup, since the popup never gets a
  chance to open.

---

## Surface 2: First-run / default-hotkey discovery notice (condensed)

The plan hard-codes a default combo (exact keys TBD — plan's own Unresolved Questions) with no UI
surface telling the user what it is. Recommend a single one-time in-app notice, shown the first
time the main SteleKit window opens after this feature ships:

```
┌───────────────────────────────────────────────────┐
│ ℹ New: Quick Capture                               │
│ Press Ctrl+Shift+Space anywhere to capture a note  │
│ into today's journal.                    [ Got it ]│
└───────────────────────────────────────────────────┘
```

Acceptance criteria:
- Shown exactly once per install (persisted flag), not on every launch.
- The hotkey combo shown here is generated from the same constant the registration code uses
  (`GlobalHotkeyListener`'s bound combo), never hand-typed separately — so the two can't drift.
- Reachable again on demand afterward (e.g. an "About" or "Keyboard Shortcuts" row in Settings) —
  a one-time toast alone fails Nielsen #10 (help and documentation should be revisitable).
- Dismissible via `Enter`/click on "Got it" or `Escape`; auto-dismiss is not required since this
  is non-urgent and low-frequency.

---

## Surface 3: Hotkey-conflict / registration-failure notification (condensed)

Per plan Task 1.3.1c, registration failure (Wayland, or another app already bound to the same
combo) is caught and logged but produces **no user-visible signal at all** — the app behaves
identically whether the hotkey works or silently doesn't, which a user can only discover by trying
it and getting nothing. Recommend a minimal notification surface:

```
┌───────────────────────────────────────────────────┐
│ ⚠ Quick Capture hotkey unavailable                 │
│ Ctrl+Shift+Space is already in use, or this        │
│ desktop session doesn't support global hotkeys.    │
│                                    [ Dismiss ]      │
└───────────────────────────────────────────────────┘
```

Acceptance criteria:
- Shown once per app session (not repeated on every failed hotkey attempt — there are none, since
  registration either succeeds or fails once at startup).
- States *why* in plain language when known (in-use vs. unsupported session), not a generic
  "error occurred" — Nielsen #9.
- Does not block app startup or steal focus from the main window on launch.
- The rest of the app (main window, in-app capture via any future menu item) remains fully
  functional — this notification is informational only, never a hard failure.

---

## Surface 4: Pending-captures directory file format (condensed, non-interactive)

Representative sample (`~/.stelekit/pending-captures/01930000-abcd-7xyz-....json`):

```json
{"captureId":"01930000-abcd-7xyz-....","text":"Call dentist about appointment","capturedAt":"2026-09-05T10:00:00Z"}
```

Acceptance criteria:
- File is atomically visible-or-absent (temp file + `ATOMIC_MOVE`) — a poller or future
  OS-integration script never observes a partially-written file.
- Filename uses a UUIDv7 (time-ordered) so a directory listing sorted by name is chronological
  without parsing file contents.
- `captureId` is a UUID string, identical to the filename stem, and is present in every file —
  it is the field a poller replay uses to make a duplicate delivery idempotent (an `INSERT OR
  REPLACE` on the same `Block.uuid` rather than a second block), so it is load-bearing, not
  informational.
- `text` is the verbatim captured string with no truncation or re-encoding; `capturedAt` is
  ISO-8601 UTC.
- A failed replay (e.g. no active graph yet) leaves the file in place for the next poll cycle —
  the format itself carries no retry-count or backoff state, so retries are driven entirely by
  the poller's fixed interval, not by anything in the file.
- Forward-compatible: this schema has no `source` or `title` field yet; Phase 2 OS-integration
  surfaces that need to distinguish "which script wrote this" will require an additive field, not
  a breaking format change.

---

## UX Acceptance Criteria

**Efficiency**
1. From hotkey press to journal-saved, the fastest path is exactly 1 non-text keystroke (hotkey)
   → typing → 1 non-text keystroke (`Ctrl+Enter`) — 2 keystrokes total, 0 mouse clicks required.
2. The popup is visibly on-screen and the text field has keyboard focus within 150ms of the
   hotkey firing — a human should be able to start typing immediately with no perceptible lag.
3. Pressing the hotkey a second time while the popup is already open re-focuses it without
   clearing existing typed text and without opening a second window (human-testable: type
   partial text, press hotkey again, confirm text is unchanged and only one window exists).

**Error handling and dead ends**
4. Save-error state shows the specific message "Couldn't save — journal file is locked." (or the
   equivalent real failure reason) and offers both `Retry` and `Copy text & close` — never a bare
   "Error" with no next step.
5. No state in this feature is a dead end: every state (Saving excluded, since it's transient)
   has at least one human-operable exit — `Escape`, a labeled button, or auto-dismiss.
6. Typed text is never silently lost: a save failure, a focus-loss, or an explicit "Copy text &
   close" all preserve or externalize the text (kept in the field, or copied to clipboard) —
   never discarded without the user's text being retrievable.
7. `No active graph` and `Graph locked` states never show an editable text field (error
   prevention) — a human tester confirms no keystroke in these states appears to "type into"
   anything.

**Accessibility**
8. Every action reachable via mouse (Retry, Copy text & close, Open SteleKit, Got it, Dismiss) is
   also reachable via keyboard alone (`Tab`/`Shift+Tab` to move focus, `Enter`/`Space` to
   activate, `Escape` for the default close/save action) — tested by unplugging the mouse and
   completing a full capture end to end.
9. On open, the popup announces itself to assistive technology as a dialog with an accessible
   name ("SteleKit Quick Capture") — verified with a screen reader (Orca on Linux, NVDA on
   Windows, VoiceOver on macOS) reading the title immediately, without requiring the user to
   explore the window first.
10. Focus is trapped within the popup while it's open (`Tab` cycles only through its own
    controls) and is restored to whatever window had focus before the hotkey fired, on every exit
    path (save, cancel, error-close) — a human tester confirms that after any capture, the
    previously focused app can immediately receive keystrokes without an extra click.
11. Text and icon contrast in every state (including the dimmed/disabled "Saving" field and the
    `⚠`/`✓`/`🔒` status glyphs) meets WCAG AA — 4.5:1 for normal text, 3:1 for large text/icons —
    verified with a contrast-checker against the actual rendered colors in both light and dark
    desktop themes.
12. The `Saved` state's auto-dismiss does not remove the user's only way to confirm success for
    someone who needs more than 600ms to perceive it: the same "Saved to today's journal" text is
    also written to the app's log/toast history (or an equivalent persistent trace) so a screen
    reader or slow-reading user isn't left uncertain whether the capture landed if the window
    closes before they finish reading it.

**Discoverability**
13. A user who has never used this feature learns the hotkey at least once (Surface 2) without
    reading external documentation, and can look it up again later without re-triggering the
    first-run notice.
14. A user whose hotkey silently fails to register (Surface 3) receives a plain-language
    notification distinguishing "already in use" from "unsupported session," rather than
    inferring failure only by trying the hotkey and getting nothing.
