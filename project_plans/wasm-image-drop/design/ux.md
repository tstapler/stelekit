# UX Design: wasm-image-drop

SDD Phase 3.5 (UX design, pre-implementation). Scope per `requirements.md` and
`implementation/plan.md`: drag-and-drop image attachment on the WASM/web build, end to end
(JS capture → OPFS byte write → markdown insertion), matching JVM's `PageDropTarget.kt`
behavior with no new hover-state UI.

**Confirmed against `implementation/plan.md` before drafting**: the plan does **not** add a
drag-over visual affordance — Pattern Decisions explicitly lists "Visual drag-over hover
affordance" under "Explicitly out of scope," matching `research/ux.md` §1's recommendation not
to scope-creep polish into this bug fix. This doc designs the shipped interaction (silent
drag-over, visible result-on-drop) rather than the more common cross-app pattern (highlighted
drop zone), because that is what's actually being built.

## Surface inventory

| # | Surface | Type | Treatment |
|---|---------|------|-----------|
| 1 | Page view drop zone (drag image from OS → open page) | Interactive | Full (wireframe + flow + error table) |
| 2 | Attachment-failure toast (reused `NotificationToast`, new call site) | Interactive | Full |
| 3 | Browser dev console / `graphContentLogger` output | Non-interactive | Condensed |
| — | Accessible alternative (keyboard/click picker) | Out of scope for this project | Flagged only, not designed |

No modal, loading spinner, or dedicated empty state exists for this feature — the plan's chosen
architecture (Approach A, body-level listener) has no intermediate "uploading…" UI; write latency
is expected to be sub-perceptible for typical screenshot/photo sizes, and no progress surface was
scoped.

---

## Surface 1: Page view drop zone

### Wireframe — states

```
┌─ Browser tab: SteleKit ──────────────────────────────────────────────┐
│ ┌──────────┐ ┌────────────────────────────────────────────────────┐ │
│ │ Sidebar  │ │  Page: "Trip Planning"                              │ │
│ │          │ │                                                      │ │
│ │ Journal  │ │  • Book flights                                     │ │
│ │ Pages    │ │  • Reserve hotel                                    │ │
│ │ Search   │ │  • ▍ (focused block, cursor here)                   │ │
│ │          │ │                                                      │ │
│ └──────────┘ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
STATE: idle — no visual difference from any other moment. No drop-zone
outline, no border, no tint change anywhere on the canvas (by design —
see confirmation above).
```

```
┌─ Browser tab: SteleKit ──────────────────────────────────────────────┐
│                                              🖱️➕ (native OS drag cursor)│
│ ┌──────────┐ ┌────────────────────────────────────────────────────┐ │
│ │ Sidebar  │ │  Page: "Trip Planning"        ░░░░░░░░░░░░░░░░░░░░  │ │
│ │          │ │                                ░ vacation.jpg  ░░░  │ │
│ │ Journal  │ │  • Book flights                ░ (OS drag ghost) ░  │ │
│ │ Pages    │ │  • Reserve hotel               ░░░░░░░░░░░░░░░░░░░  │ │
│ │ Search   │ │  • ▍                                                │ │
│ └──────────┘ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
STATE: drag-over (dragenter/dragover firing on document.body).
- App UI is unchanged — no border/tint. The "ghost" thumbnail and cursor
  glyph shown above are the OS/browser's own native drag feedback, not
  app UI. Whether the cursor shows a "copy"/"move" glyph depends on
  `dataTransfer.dropEffect`, which `DropZoneInterop.kt` (Task 4.1.1a)
  does not explicitly set — so the exact glyph is browser-default
  behavior, not a guaranteed app affordance. Confirm actual behavior in
  the target browser during Task 5.2.1a's manual check; do not assume a
  copy-cursor is guaranteed.
- The one guaranteed effect: the browser does NOT treat this as "open
  file in this tab" (see error table row "drag anywhere in window").
```

```
┌─ Browser tab: SteleKit ──────────────────────────────────────────────┐
│ ┌──────────┐ ┌────────────────────────────────────────────────────┐ │
│ │ Sidebar  │ │  Page: "Trip Planning"                              │ │
│ │          │ │                                                      │ │
│ │ Journal  │ │  • Book flights                                     │ │
│ │ Pages    │ │  • Reserve hotel                                    │ │
│ │ Search   │ │  • ![vacation.jpg](../assets/vacation.jpg)          │ │
│ │          │ │    [ thumbnail renders once markdown parses ]       │ │
│ └──────────┘ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
STATE: drop success — markdown inserted into the target block, same
`![name](../assets/name)` convention as JVM (requirements.md AC1).
```

### Interaction flow

| Step | User action | System response |
|---|---|---|
| 1 | Has an image file available (Desktop, Finder/Explorer window, another browser tab's download) and a page open in SteleKit | Idle — no listener activity yet |
| 2 | Starts dragging the file over the browser window | `dragenter`/`dragover` fire on `document.body`; `preventDefault()` runs on both (plan Task 4.1.1a) — this is what stops the browser from later treating the drop as "navigate to file" |
| 3 | Continues hovering (moves cursor around, including outside the page content pane, e.g. over the sidebar) | `dragover` keeps firing/`preventDefault()`-ing; no app-visible change per the confirmed scope decision. Listener is body-level, so hovering over sidebar/toolbar does not "lose" the drag the way a canvas-only listener might |
| 4 | Releases the mouse button over the browser window | `drop` fires, `preventDefault()` runs, `dataTransfer.files` extracted, filtered to image extensions (`jpg/jpeg/png/gif/webp/heic/svg/bmp`), each accepted file's bytes read via `File.arrayBuffer()` |
| 5a | (success path) | Bytes forwarded to the active page's `onFileDrop` handler → `WasmMediaAttachmentService.attachBytes` writes to OPFS `assets/`, deduping the name if needed → `![name](../assets/name)` inserted into the focused/target block |
| 5b | (non-image file) | File is filtered out before any byte read; no callback invocation for that file — no visible or logged effect (matches JVM, requirements.md AC3) |
| 5c | (OPFS write failure) | `attachBytes` returns `Either.Left` → `graphContentLogger.warn(...)` (existing) **and** `notificationManager.show("Image attachment failed: ${err.toUserMessage()}", ERROR)` (new, Task 1.1.2b) → toast appears (Surface 2) |
| 5d | (no page view composed — e.g. graph-picker/welcome screen) | `preventDefault()` still runs (browser still doesn't navigate away), but no `PageView` has registered an active handler, so the drop silently no-ops — no console error (requirements.md AC4) |
| 6 | Drops multiple files, some duplicate-named | Processed sequentially (`App.kt`'s unchanged `files.forEach`); first gets the base name, a same-named second gets the `-1` suffix, matching JVM's per-drop dedup convention |

### Error and edge-case table

| Trigger | System response | What the user sees | Exit path |
|---|---|---|---|
| Drop a non-image file (`.txt`, `.pdf`, …) | Filtered client-side before any read; no service call | Nothing — no toast, no log-visible-to-user, no markdown inserted | N/A — no dead end because nothing changed; user can simply try a different file |
| Drop anywhere in the browser window while dragging (not just over blocks) | `preventDefault()` fires on `dragenter`/`dragover`/`drop` regardless of target element (body-level listener) | Browser does not navigate away or render the file full-page; if not over an active page view, nothing else happens either | N/A |
| Drop outside any composed page view (graph picker, settings, demo-fallback graph) | No active `onFileDrop` handler registered; silent no-op | Nothing visible; console shows no JS error | N/A |
| OPFS write fails (quota exceeded, permission denied, browser storage error) | `Either.Left` → toast + log | Toast: `"Image attachment failed: <reason from err.toUserMessage()>"`, `NotificationType.ERROR` styling (red/error container), auto-dismisses after 3s or on click | User can re-attempt the drop immediately; the failed attempt is not silently lost — see Surface 2's history note below |
| Drop a very large image (multi-MB) | Base64 bridge (ADR-002) handles bulk conversion; no explicit size cap identified in requirements/plan | No progress indicator exists — block insertion happens once the async write completes, with no "in progress" feedback in between | **Gap, not a dead end**: nothing blocks the user from continuing to type/navigate during the wait, but there is also no confirmation the drop is being processed until it either succeeds (markdown appears) or fails (toast). Flag for plan/implementation: if manual testing (Task 5.2.1a) surfaces a noticeable delay for realistic photo sizes (5–20 MB), a lightweight "attaching…" state should be considered as a fast-follow — not required to ship this project, since research/ux.md scoped hover/progress polish out |
| Two files dropped together, one image + one non-image | Non-image filtered before processing; image proceeds through the normal success/failure path independently | Only the image's markdown appears (or its own toast on failure); the non-image is silently ignored, same as a solo non-image drop | N/A |

---

## Surface 2: Attachment-failure toast

Reused component (`NotificationOverlay`/`NotificationToast`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/NotificationDisplay.kt`); this
project adds one new call site (`App.kt`'s wasm `onFileDrop` failure branch, Task 1.1.2b), not a
new visual component.

### Wireframe

```
┌─ Browser tab: SteleKit ──────────────────────────────────────────────┐
│ ┌──────────┐ ┌────────────────────────────────────────────────────┐ │
│ │ Sidebar  │ │  Page: "Trip Planning"                              │ │
│ │          │ │  • Book flights                                     │ │
│ │ Journal  │ │  • Reserve hotel                                    │ │
│ │ Pages    │ │  • ▍                                                │ │
│ │          │ │                                                      │ │
│ │          │ │                            ┌───────────────────────┐│ │
│ │          │ │                            │ ⨂  Image attachment   ││ │
│ │          │ │                            │    failed: Quota      ││ │
│ │          │ │                            │    exceeded        ✕  ││ │
│ └──────────┘ └────────────────────────────┴───────────────────────┘│ │
└────────────────────────────────────────────────────────────────────┘
Bottom-right toast, errorContainer/onErrorContainer Material3 colors,
error icon, message text, small close glyph. Entire surface is a click
target for dismiss (not just the ✕).
```

### Interaction flow

| Step | User action | System response |
|---|---|---|
| 1 | A drop's `attachBytes` call returns `Either.Left` | `notificationManager.show(message, ERROR)` is invoked with default `timeout = 3000`ms |
| 2 | (passive) | Toast slides in bottom-right, stacks above any other active notification (column layout, newest appended) |
| 3a | User does nothing | Toast auto-dismisses after 3s (`NotificationManager.show`'s default `timeout` — `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/NotificationManager.kt:29`) |
| 3b | User clicks anywhere on the toast | Immediately dismissed (`clickable { onDismiss() }` covers the whole `Surface`, not just the ✕ glyph) |
| 4 | (either path) | The notification is retained in `NotificationManager.history` (capped at 50) — visible later via `NotificationHistory`, if that view is reachable in the UI, giving the user a way to recover the error text after the 3s toast is gone |

### Error and edge-case table

| Trigger | System response | What the user sees | Exit path |
|---|---|---|---|
| User drops a second failing image while the first error toast is still showing | `NotificationManager.show` appends a new entry; both stack in the `Column` | Two toasts visible simultaneously (bottom-right, stacked) | Each dismisses independently, by click or its own 3s timer |
| Error message text is long (e.g. a verbose OPFS error string) | `Surface` width is bounded `200.dp`–`400.dp`; `Text` wraps within that | Toast grows taller, not wider, to fit the message | N/A |
| 3s default timeout vs. message severity | No override passed at the new call site (Task 1.1.2b uses the default) | An `ERROR`-severity message (something the user may need to act on, e.g. "storage full") gets the same 3s exposure as an `INFO` toast | **Design note, not a blocker**: recommend implementation consider a longer explicit `timeout` (e.g. 6000ms) for this specific `ERROR` call, or confirm the `NotificationHistory` panel is discoverable enough that a missed 3s window isn't a dead end. Not required to ship — `NotificationManager`'s default behavior is existing, unmodified infrastructure per `research/ux.md` §4's "reuse, don't invent" guidance |

---

## Surface 3: Browser dev console / log output (non-interactive)

No new logging surface — `graphContentLogger.warn(...)` at the existing `onFileDrop` failure
branch is unchanged in shape by this project (Task 1.1.2b adds the toast call alongside it, not
instead of it).

Representative output (Kotlin/Wasm console line, DevTools):

```
[WARN] Drag-and-drop attachment failed: Left(AttachmentError.CopyFailed(message=QuotaExceededError))
```

Acceptance criteria:
- A failed drop always produces exactly one `graphContentLogger.warn` line containing the
  underlying `DomainError`, regardless of platform (unchanged cross-platform pattern).
- A successful drop produces no warning-level log line.
- A non-image-file drop (filtered client-side) produces no log line at all — it's an expected
  no-op, not an error (matches AC3's "not an error" framing).
- A drop outside any composed page view produces no thrown/uncaught JS exception in the
  browser console (requirements.md AC4) — silence is the correct, tested state here, not an
  oversight.
- The log line is developer-facing only; it is never the user's sole feedback for a real
  failure — Surface 2's toast is what makes a real failure user-visible (this is the gap this
  project's Task 1.1.2b specifically closes for wasm).

---

## Out of scope, flagged for follow-up: accessible drag alternative

Per `research/ux.md` §3, shipping drag-only image attachment on web is acceptable for this
project (it restores a currently-dead path rather than regressing an existing accessible one —
WCAG 2.5.7 is not violated), but it does leave web without a keyboard/single-pointer alternative
that JVM also lacks today for `onAttachImage`. This project's `plan.md` explicitly scopes the
toolbar picker (`WasmMediaAttachmentService.pickAndAttach`) out, noting it becomes cheap once
this project's `attachBytes`/OPFS-write plumbing exists.

**Recommendation for near-term follow-up** (not designed here — out of scope for this project):
a "Browse…" button/hidden `<input type="file" accept="image/*">` surfaced near the block editor
toolbar, calling the same `attachBytes` path this project builds. This would close the
keyboard-accessibility gap the drag-only interaction leaves open, and should reuse Surface 2's
toast pattern for its own failure case. Flagging per the task brief's request to note whether
this should be called out — it should, as a separate, explicitly-scoped follow-up project, not
folded into this one.

---

## UX acceptance criteria

Testable by a human, covering the surfaces above.

**Task completion**
1. A user with an image file and an open page can attach it in a single physical action (one
   drag-and-release) — 0 additional clicks, no confirmation dialog.
2. The drop succeeds when released anywhere over the app window while a page view is composed
   (not just a narrow sub-region), since the capture listener is body-level.
3. On success, the inserted markdown (`![name](../assets/name)`) appears in the block the user
   was focused on (or the drop's target block) without requiring the user to take any further
   action (e.g., no "confirm attach" step).

**Silent/expected no-ops (must stay silent)**
4. Dropping a non-image file produces no toast, no dialog, no console error, and no markdown
   insertion — confirmed by manual test with a `.txt` file.
5. Dropping a file outside any open page view (e.g., on the graph picker) produces no browser
   navigation-away and no console error — confirmed by manual test per plan Task 5.2.1a.

**Error state**
6. A real attach failure (e.g., simulated OPFS quota error) shows a toast with the specific text
   `"Image attachment failed: <reason>"`, styled with error (not info/warning) coloring.
7. The error toast is dismissible immediately by a single click anywhere on it, in addition to
   auto-dismissing after its timeout.
8. No dead ends: every state in the Surface 1 and Surface 2 tables above has either (a) no
   visible change requiring recovery, or (b) a toast the user can dismiss and then simply
   retry the drop — there is no state that traps the user or requires a page reload.

**Consistency**
9. Behavior (filter list, dedup-suffix convention, markdown format) matches JVM's existing
   drag-and-drop behavior for the same inputs, per requirements.md AC1–AC3 and AC6 — verified by
   comparing wasm and JVM manual test runs side by side during Task 5.2.1a.

**Accessibility**
10. The error toast's message text is real text content (not an image or canvas-only paint), so
    it is exposed to screen readers via Compose's default semantics merging on the `Row`/`Surface`
    — verify with a screen reader (VoiceOver/NVDA) that dismissing/navigating past the toast
    doesn't require sight-based interaction alone (click target covers the full toast, not just
    the tiny ✕ glyph).
11. Toast color contrast meets ≥ 4.5:1 for text against its container — verify
    `errorContainer`/`onErrorContainer` (and the other three `NotificationType` pairs, since this
    project reuses the same component) against the app's active Material3 theme, light and dark.
12. **Known, explicitly out-of-scope gap** (do not mark this criterion "failed" for this
    project): the drag-and-drop interaction itself has no keyboard or single-pointer-click
    alternative on web. This is acceptable per `research/ux.md` §3's WCAG 2.5.7 analysis (no
    existing accessible path is being regressed) but should be tracked as a follow-up item, not
    silently dropped — see "Out of scope, flagged for follow-up" above.

---

## Summary

- **Surfaces designed**: 3 (2 full interactive treatments — page-view drop zone, attachment
  failure toast; 1 condensed non-interactive — console/log output), plus 1 explicitly-flagged
  out-of-scope follow-up (accessible alternative) noted but not designed.
- **UX acceptance criteria written**: 12.
