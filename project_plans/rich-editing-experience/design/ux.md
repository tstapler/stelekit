# UX Design: rich-editing-experience

**Date**: 2026-07-05
**Inputs**: `project_plans/rich-editing-experience/requirements.md`, `research/ux.md`, `research/features.md`, `implementation/plan.md`, `decisions/ADR-001-*.md`, `decisions/ADR-002-*.md`
**Status**: Pre-implementation design artifact. Written to give Phase D/E/F/G tasks a concrete mechanism to build, not another "illustrative placeholder."

This document designs the ten in-scope surfaces, gives each a before/after wireframe where a change is warranted, and closes with testable UX acceptance criteria. All proposals build only on existing SteleKit components (`MobileBlockToolbar`, `CommandPalette`, `SuggestionBottomSheet`, `TagChipRow`, `AnnotationToolbar`, `VoiceCaptureButton`, `FormatAction`, `Command`) and existing Compose Foundation APIs (`pointerInput`/`detectDragGestures`, `TooltipBox`) — no new dependency, consistent with ADR-002.

---

## Surface index

| # | Surface | Change proposed? |
|---|---|---|
| a | Mobile block-editor toolbar (`MobileBlockToolbar.kt`) — 3 rows + selection mode + left-handed | Yes — tag-insertion primary row, reorder bottom row, TODO overflow row |
| b | Desktop / hardware-keyboard experience (`BlockEditor.kt` `handleKeyEvent`) | Yes — add a discoverable shortcut-hint layer |
| c | Command palette (`CommandPalette.kt`) once formatting entries land (Phase F) | Yes — new entries, gated error state |
| d | Tag/link insertion flow (flagship example) | Yes — this is the flagship redesign |
| e | Block reordering | Yes — highest-value gap per research |
| f | TODO-toggle flow | Yes — fixes confirmed-broken state |
| g | Annotation editor toolbar (`AnnotationToolbar.kt`) | Yes — minor, scoped inside `ui/annotate/` only |
| h | Voice-mode capture (`VoiceCaptureButton.kt`) | No structural change — state model already sound; documented for completeness and error-state consistency |
| i | Error/empty/loading states across (a)–(h) | Cross-cutting — specified once, referenced per surface |
| j | Insert-image (mobile Attach-image/Capture-photo, `MobileBlockToolbar.kt`) | Minor — efficiency/discoverability polish, not a from-scratch redesign; existing 2-button flow is already close to optimal |

---

## (d) Tag/link insertion flow — FLAGSHIP REDESIGN

### Before (today)

Two disconnected mechanisms, and the explicit "Suggest tags" path requires a full modal round-trip:

```
Mobile — "Suggest tags" button path (today)
┌─────────────────────────────────────────┐
│  Block: "Meet with Sarah about budget"   │  ← user is mid-sentence, no tag yet
└─────────────────────────────────────────┘
   [Outdent][Indent][ [[ ] ][🏷 Suggest tags][📎][📷]   ← MobileBlockToolbar primary row
                              │ tap
                              ▼
        ┌───────────────────────────────────────┐
        │  (sheet slides up — animation, ~250ms) │  ← step: wait
        ├───────────────────────────────────────┤
        │ Suggested tags for this block      ✕  │  ← ModalBottomSheet chrome
        │ ● loading spinner (LLM pending) …      │  ← step: wait for LLM
        │ [#budget] [#meetings] [#finance-team]  │  ← step: read chips
        └───────────────────────────────────────┘
                              │ tap a chip
                              ▼
                 sheet auto-dismisses, tag inserted

Steps: tap toolbar button → wait for sheet animation → wait for LLM →
       read chips → tap chip  =  4-5 discrete user-perceptible steps
```

Meanwhile the typed `#` trigger already works and is fast — but it requires the user to know a tag name to type, which is exactly the case the "Suggest tags" button exists to help with (the user knows they *want* a tag, not *which* tag).

### After (proposed)

Collapse the two mechanisms into one inline surface anchored at the cursor, reusing the **same `TagChipRow`** composable that `SuggestionBottomSheet` and `VoiceCaptureButton`'s post-transcription state already use — no new component.

```
Mobile — proposed inline flow
┌─────────────────────────────────────────┐
│  Meet with Sarah about budget▍           │  ← cursor position
│  ┌───────────────────────────────────┐   │
│  │ #budget  #meetings  #finance-team │ ⟳ │  ← TagChipRow, anchored directly
│  └───────────────────────────────────┘   │     below the cursor line (same
└─────────────────────────────────────────┘     popup-flip logic as autocomplete —
   [Outdent][Indent][ [[ ] ][🏷][📎][📷]          see §(i) collision handling)
                        │ tap 🏷 (same icon,
                        │ same contentDescription
                        │ "Suggest tags")
                        ▼
        Row appears in place — no sheet, no dismiss chrome, no slide animation.
        Local-frequency suggestions render immediately (no LLM dependency);
        the trailing ⟳ spinner shows only next to LLM-sourced chips still
        pending, so the user can act on local suggestions before the LLM
        replies (never blocked waiting).

                        │ tap a chip
                        ▼
              tag inserted at cursor, row dismisses, focus stays in block

Steps: tap 🏷 → tap chip  =  2 discrete steps  (was 4-5)
```

Desktop stays unchanged and is already fast (type `#`, autocomplete filters live, Enter/click selects) — the redesign only touches the *button-triggered* path, matching research §2's finding that the typed-trigger mental model is already correct and shouldn't be disturbed.

**Mechanism notes for Phase D.1 implementation:**
- `EditorToolbar`'s `onSuggestTags` callback (already being fixed for the recomposition bug in Phase B) now renders `TagChipRow` inline in the block-editor layout instead of invoking a `ModalBottomSheet`. `TagSuggestionState.Loading` shows the row with only a spinner where LLM chips will appear; `TagSuggestionState.Ready` progressively fills local chips first, LLM chips as they arrive (`llmPending` already models this — see `SuggestionBottomSheet.kt:78`).
- `SuggestionBottomSheet.kt` and the `ModalBottomSheet` chrome can be retired for this call site once the inline row ships (kept only if another call site still needs a full-sheet layout — confirm at implementation time; not assumed here).
- The 🏷 icon, its `contentDescription = "Suggest tags"`, and its position in the primary row are unchanged — this preserves muscle memory and the existing screenshot-test anchor point, satisfying Phase D.1's "primary row change" without moving unrelated buttons.

---

## (j) Insert-image — mobile Attach-image/Capture-photo flow

Unlike every other surface above, `insert-image` has no existing modal-round-trip or hand-typed-syntax friction to fix — `MobileBlockToolbar`'s 📎 "Attach-image" (file picker) and 📷 "Capture-photo" (camera) primary-row buttons already exist and already insert the resulting asset directly into the block, no intermediate sheet. This is a materially smaller efficiency/discoverability polish than the tag-insertion flagship redesign in (d), not a from-scratch feature.

### Before (today)

```
Mobile — insert-image (today)
┌─────────────────────────────────────────┐
│  Block: "Photo of the whiteboard: "▍     │
└─────────────────────────────────────────┘
   [Outdent][Indent][ [[ ] ][🏷][📎 Attach][📷 Capture]
                              │ tap either
                              ▼
                 OS file-picker or camera opens → image inserted

Steps: tap 📎 or 📷 → complete the OS-level picker/camera flow
     = 1 toolbar tap (the OS picker/camera's own step count is outside this app's control)
```

### Finding — is a UI change even warranted?

Unlike tag-insertion, insert-image's in-app step count is already close to optimal: one tap reaches the OS picker or camera directly. Two plausible directions exist, and Phase A's audit — not this document — should decide between them, per Task A.1.2e's own note to check Desktop/Web's current mechanism or its absence:

1. **Combine 📎+📷 into one tap** with a lightweight chooser (a small inline popup: "Choose from library" / "Take photo"), IF Phase A's benchmarking/heuristic review finds users hesitate between the two buttons — a discoverability/decision-cost problem, not a step-count one (merging trades "1 tap + instant choice" for "1 tap + 1 chooser tap," i.e. no net step reduction, only a discoverability trade-off that should be confirmed empirically, not assumed).
2. **Leave the two buttons as-is** if the audit doesn't confirm real confusion — "attach an existing photo" and "capture a new one" are genuinely distinct intents, so collapsing them risks removing a meaningful distinction rather than simplifying one.

**Honest finding**: this document does not assert that a mobile UI change is required. The more concretely relevant, already-partially-confirmed gap is **platform parity**, not the mobile mechanism itself:

- Desktop/Web currently have no confirmed equivalent insert-image affordance — Task A.1.2e's own acceptance criteria already flags checking "the current Desktop/Web insertion mechanism or its confirmed absence"; this document treats "no mechanism exists" and "one exists but is undocumented" as equally plausible pending that audit.
- **Web-specific, already-tracked**: `docs/bugs/open/BUG-004-wasm-page-drop-target-noop.md` (open, Medium severity) documents that OS-level drag-and-drop image insertion is a confirmed no-op on the WASM/Web target. If Web's only current insert-image path is drag-and-drop, this is not a "gap to newly discover" during Phase A's audit — it is existing, already-diagnosed evidence the audit should cite directly (per pre-mortem.md's Failure #2 prevention: evidence-based backlog rows over pure agent narrative).

### Mechanism proposal (testable, conditional on audit confirmation)

- **If** Phase A confirms real discoverability confusion between 📎/📷: merge into one 🖼 "Insert image" button opening a 2-option inline chooser anchored at the button (reusing the same anchored-popup pattern as (d)'s `TagChipRow` — no new sheet component) — **≤ 2 taps** (button + choice), same worst-case step count as today, trading one fewer competing icon in the primary row for one extra decision tap.
- **If not**: no mobile UI change ships for insert-image. The confirmed backlog item instead becomes "Desktop/Web insert-image parity" (and, for Web specifically, tracking BUG-004's fix as the relevant unblock), filed under whichever phase Phase A's audit assigns it — not invented here.

---

## (a) Mobile block-editor toolbar — full surface

### Layout (after Phase D changes; unchanged rows omitted where no change is proposed)

```
┌──────────────────────────────────── MobileBlockToolbar ────────────────────────────────────┐
│ Row 0 (overflow, expandable via "…"):                                                       │
│   [B] [I] [S] [</>] [H] [Quote] [1.] [H1] [☑ TODO] [{ } Code block] [▦ Table]  ← +3 new     │
│                                                                                               │
│ Row 1 (primary, editing only):                                                              │
│   [⋮ overflow toggle] [Outdent] [Indent] [ [[ ] ] [🏷 tags → inline TagChipRow] [📎] [📷]    │
│                                                                                               │
│ Row 2 (bottom, always visible while editing):                                               │
│   [Undo] [Redo]      [⠿ drag-to-reorder handle]  [+ Add block] [Paste?]                     │
│                        ▲ NEW — replaces bare Move-Up/Move-Down icon pair as the PRIMARY      │
│                          reorder affordance; Move-Up/Move-Down remain reachable (see below)  │
└───────────────────────────────────────────────────────────────────────────────────────────┘

Selection-mode toolbar (multi-select) — unchanged:
┌─────────────────────────────────────────┐
│  "3 selected"      [Copy][Cut][Delete][✕]│
└─────────────────────────────────────────┘

Left-handed variant — unchanged pattern: every row's element order mirrors
(overflow toggle moves to trailing edge, primaryActions leads; structuralActions
leads over undoRedo) exactly as `isLeftHanded` already implements at
MobileBlockToolbar.kt:241-247 and :288-294. The new drag handle and TODO/Code
block/Table buttons follow the same mirroring rule — no new asymmetric case.
```

### Interaction flow — formatting overflow row (unchanged mechanism, 3 new entries)

1. User taps the "…" toggle (`contentDescription = "Toggle formatting"`) → row expands (existing `formattingExpanded` state, `MobileBlockToolbar.kt:60,173`).
2. User taps "☑ TODO" → `requestTodoToggle()` (Phase C.1's dedicated `TodoState`/`applyTodoToggle` plumbing — **not** a `FormatAction` case) → block content gets/loses its `TODO `/`DOING `/`DONE ` prefix per `TodoState.next()`, checkbox glyph reflects state immediately.
3. User taps "{ } Code block" → `onFormat(FormatAction.CODE_BLOCK)` → inserts triple-backtick fence pair around block content, cursor placed inside.
4. User taps "▦ Table" → inserts a 2×2 markdown table skeleton (`| | |` header + separator + one data row) with cursor in the first cell — this gives table insertion a concrete, testable mechanism rather than leaving it as a pure backlog placeholder.

### Interaction flow — reorder (bottom row)

See dedicated section (e) below for the full before/after.

---

## (b) Desktop / hardware-keyboard experience

### Before (today)

No visual affordance exists at all. A new user has no way to discover Ctrl+B/I/S/H/E/K short of reading documentation. This is itself a gap: research §2 states shortcuts should be "surfaced next to the toolbar action it accelerates... so novices graduate to expert usage," and today there is no toolbar-equivalent on desktop to surface it next to.

### After (proposed)

Do **not** add a persistent visual toolbar to the desktop editor (no requirement calls for one, and it would be new chrome with no corresponding research finding justifying it). Instead, make shortcuts discoverable through two already-planned, zero-new-chrome channels:

1. **Command palette** (Phase F): every `FormatAction`/structural action gets a `Command` entry whose `shortcut` field is populated from the new canonical `ShortcutTable` (Phase F.3). `CommandItem` already renders the shortcut badge (`CommandPalette.kt:208-223`) — this is pure data wiring, no new UI.

```
┌───────────────────────────────────────────┐
│ Search commands...                         │
├───────────────────────────────────────────┤
│ Format: Bold                       Ctrl+B  │  ← existing CommandItem layout,
│ Format: Italic                     Ctrl+I  │     shortcut badge now populated
│ Format: Toggle TODO                Ctrl+⏎  │     for every formatting action
│ Format: Code block              Ctrl+Shift+C│
└───────────────────────────────────────────┘
```

2. **A single new discoverability entry point**: "Show keyboard shortcuts" as its own `Command` (no shortcut of its own beyond the palette itself), opening a lightweight list dialog built from the same `ShortcutTable` — reusing `CommandPalette`'s `Dialog`/`LazyColumn` chrome in read-only form rather than inventing a new screen. This satisfies the discoverability gap without adding a persistent on-screen element (respects the "no new clutter" constraint).

This keeps desktop screen-space untouched — the fix is "make the shortcut discoverable when the user goes looking" (palette, help dialog), not "always show a bar of icons" (which nothing in the requirements asks for and which desktop users specifically don't need, since they already have direct key access).

---

## (c) Command palette formatting entries

### Flow

1. User opens palette (existing entry point, e.g. Ctrl+Shift+P) while a block is `isEditing == true`.
2. User types a fuzzy query, e.g. "bold" → existing 3-tier bucket-sort matcher (`CommandPalette.kt:37-59`) ranks "Format: Bold" to the top.
3. User presses Enter or taps → `command.action()` fires → `blockStateManager.requestFormat(FormatAction.BOLD)` (Phase F.2) → palette dismisses → block content updates exactly as Ctrl+B would.

### Error/edge case — gated on Phase F.1's focus/blur spike

Phase F.1 exists precisely because this path might silently fail (no replay cache on `_formatEvents` — architecture.md §3). The UX contract must be: **this feature does not ship in a state where it looks wired but silently does nothing** (research explicitly calls this "the single worst kind of missing feature" via the `EssentialCommands.toggleTodo` precedent).

- If Phase F.1's spike records **FAIL** (palette dialog blurs/tears down the block's collector scope): the "Format: …" entries must not ship until the fix (direct `blockStateManager` reference, or deferred-close dispatch) lands and re-passes the spike test. There is no partial-credit "ship it and see" path here — this is an explicit UX acceptance criterion below.
- If a formatting command is invoked with **no block currently in edit mode** (e.g. user opened the palette from the sidebar, no block focused): the entry should not appear in the filtered list at all, or if shown, should be visibly disabled with a `Command` action that's a no-op paired with a one-line explanatory state (avoid a "nothing happened" silent failure — same principle as the F.1 gate, applied to the ordinary empty-focus case too).

---

## (e) Block reordering — highest-value gap

**Prior-art note**: `docs/tasks/drag-and-drop-reorder.md` is a pre-existing, more detailed (`Status: Planning`, dated 2026-04-17, never started) plan targeting this exact same file surface (`BlockGutter.kt`, `BlockDragGhost.kt`, `BlockList.kt`). Its Story 4 ("Mobile Long-Press Drag Entry") independently specifies the identical mechanism this section proposes below — a long-press-then-drag gesture via Compose Foundation's `pointerInput`/`detectDragGestures` (specifically `detectDragGesturesAfterLongPress` on Android) — so this is a **confirmed-compatible design arrived at independently, not a coincidence or a duplicate invention**. This section's proposal is a deliberately **narrower slice** than that full pre-existing plan: mobile long-press reorder only — no desktop mouse-drag-and-drop as its own feature, and no center-zone "make child" drag-to-reparent (`docs/tasks/drag-and-drop-reorder.md`'s DD-05/DD-07). See plan.md's Epic D.3 for the explicit scope boundary between this project and that pre-existing plan.

### Before (today)

```
Android — reorder a block down 2 positions (today)
┌───────────────────────────┐
│ ○ Buy groceries           │
│ ○ Call dentist            │  ← target: move this block down 2 slots
│ ○ Finish report           │
│ ○ Email Sarah             │
└───────────────────────────┘
1. Tap "Call dentist" to enter edit mode on it.
2. Tap [▼ Move Down] in bottom row → block moves 1 slot.
3. Tap [▼ Move Down] again → block moves 1 more slot.
Steps for an N-position move = 1 (select) + N (repeated taps) = 3 taps for this example.
No drag gesture exists; this is a "select, then find the right button, repeat" flow —
the exact pattern research §1 flags as behind Notion/Roam/Craft's single continuous drag.
```

### After (proposed)

```
Android — reorder via drag handle (proposed)
┌───────────────────────────┐
│ ○ Buy groceries           │
│ ⠿ Call dentist            │  ← long-press on the block's bullet/gutter reveals
│ ○ Finish report           │     the drag handle (⠿) in place of the bullet
│ ○ Email Sarah             │     glyph for the duration of the press
└───────────────────────────┘
        │ press-and-hold, then drag down past 2 block boundaries
        ▼
┌───────────────────────────┐
│ ○ Buy groceries           │
│ ░░░░░░░░░░░░░░░░░░░░░░░░░ │  ← drop-target gap indicator (existing block
│ ○ Finish report           │     insertion-gap visual, reused from indent/
│ ○ Email Sarah             │     outdent drag-drop if one exists, else a
│ ⠿ Call dentist   ← drop   │     simple divider line — no new asset needed)
└───────────────────────────┘
        │ release
        ▼
              block reorders in one continuous gesture, haptic tick on drop

Steps for ANY-distance move = 1 continuous gesture (was N taps for N positions).
```

**Discoverability guard (research §2's rule: gesture is the least discoverable of the three input types and must never be the only path):**
- The bottom row's **Move Up / Move Down icon buttons are retained**, not removed. They remain the fully-discoverable, screen-reader-accessible fallback (TalkBack/VoiceOver cannot perform a drag gesture reliably) and the mechanism verified by existing tests. The drag handle is an *additional*, faster path for sighted/motor-able users — never a replacement.
- The drag handle only appears on long-press (not a permanently-visible ⠿ glyph replacing every bullet), so it adds **zero steady-state visual clutter** — satisfying the requirements.md constraint against increasing toolbar/screen clutter.
- Desktop equivalent: the same long-press-then-drag gesture works with a mouse (press-hold-drag), no new binding needed; keyboard users get the existing Move Up/Down affordance surfaced via the new command-palette entries from (c).

This is the concrete mechanism Phase D.3's "illustrative: reorder affordance change" should implement — a long-press drag handle built with Compose Foundation's `pointerInput { detectDragGestures { } }` (already available, no new dependency), coexisting with the existing `onMoveUp`/`onMoveDown` callbacks unchanged.

---

## (f) TODO-toggle flow

### Before (confirmed broken)

```
Desktop:  cursor in block "Call mom"  →  press Ctrl+Enter
          → falls through to plain-Enter block-split behavior (WRONG — splits
            the block instead of toggling TODO state)
          → only working path: user hand-types "TODO " as literal text at
            the start of the block content.
Mobile:   no TODO-toggle affordance exists anywhere in MobileBlockToolbar today.
```

### After (proposed — matches Phase C.1/C.1.2 already planned)

```
Desktop:
  cursor in block "Call mom", no autocomplete open  →  Ctrl+Enter
    →  handleKeyEvent checks autocompleteState first (existing "create new
       page" binding must keep priority when a [[ or # query is open)
    →  autocompleteState == null  →  requestTodoToggle() dispatches to
       applyTodoToggle(...) (dedicated TodoState-based mutation — NOT a
       FormatAction case, per plan.md's Pattern Decisions table)
    →  content becomes "TODO Call mom"; pressing again → "DONE Call mom" →
       pressing again → "TODO Call mom" (4-state cycle, not 3-state:
       NONE→TODO→DONE→TODO→DONE→..., matching TodoState.NONE/TODO/DOING/DONE
       from plan.md's Domain Glossary — DONE cycles back to TODO, not to
       no-prefix; DOING is a reachable state via other means, not via this
       Ctrl+Enter toggle path)

  cursor in block, autocomplete IS open (mid [[query)  →  Ctrl+Enter
    →  existing "create new page from query" behavior fires, unchanged
    →  requestTodoToggle() does NOT fire (no collision, no silent double-action)

Mobile:
  formatting overflow row gains a "☑ TODO" toggle button (see surface (a)) —
  tap → same requestTodoToggle() dispatch, same 4-state cycle.
```

### Error/edge case

- If the block is empty (no content yet): `requestTodoToggle()` still applies the `TODO ` prefix at cursor (`"TODO "` with trailing space, cursor after it) — same toggle-on-type principle as empty-selection formatting in (i), no error state, no no-op.
- Command-palette "Format: Toggle TODO" entry available for keyboard-first users unsure of Ctrl+Enter (see (c)).

---

## (g) Annotation editor toolbar

`AnnotationToolbar.kt` is already close to the bar the rest of the app should meet: every tool button uses `TooltipBox` with a shortcut hint baked into the tooltip text (`toolShortcut(tool)`, `AnnotationToolbar.kt:216-219,253-260`) — this is exactly the "shortcut surfaced next to the button that triggers it" pattern research §2 recommends, and it should be the model other surfaces copy, not the reverse.

### Gap found

`Undo`/`Redo` (`AnnotationToolbar.kt:161-174`) are the only two buttons in this toolbar **without** a `TooltipBox`/shortcut hint, and `AnnotationEditorScreen.kt`'s undo/redo stack (lines 160-163) has no confirmed hardware-keyboard binding today — an inconsistency inside the same toolbar.

### Proposed fix (scoped entirely inside `ui/annotate/`, per architecture.md §2's "no shared abstraction with the main editor")

```
┌─────────────────────────────────────────────┐
│ [Select] [Distance] [Area] [Angle] [Label]   │
│ [Reference] │ [Calibrate]                    │
│                                               │
│ [↶ Undo (Ctrl+Z)] [↷ Redo (Ctrl+Shift+Z)]    │  ← wrap in TooltipBox matching
│         ▲ NEW tooltip, matching ToolButton      ToolButton's existing pattern
│           style; hardware binding added to      exactly — zero new visual
│           AnnotationEditorScreen's key handler   language introduced
└─────────────────────────────────────────────┘
```

- Undo/Redo gain `TooltipBox` wrapping identical in style to `ToolButton`'s (`PlainTooltip { Text("Undo (Ctrl+Z)") }`), and `AnnotationEditorScreen` gains a Ctrl+Z / Ctrl+Shift+Z key handler dispatching to its own undo/redo stack — matching this project's Phase G.2.1 illustrative fix, now made concrete.
- `enabled = canUndo`/`canRedo` already exists (line 161-172) and already grays the icon (`Color.Gray` vs `Color.White`) when unavailable — this is correct and must not regress.

### Discoverability checklist (social-JTBD tier — no hard step-count number, per research §5)

- [ ] Every tool in the tool-selection row has a visible label (`showLabels`) or, at minimum, a tooltip on hover/long-press.
- [ ] Disabled (uncalibrated) tools communicate *why* they're disabled, not just that they are (currently: grayed with no tooltip explaining "calibrate first" — flagged as a finding, not fixed here; see acceptance criteria).
- [ ] Undo/Redo now match the rest of the toolbar's shortcut-hint convention (fix above).

---

## (h) Voice-mode capture flow

No structural change proposed — `VoiceCaptureButton.kt`'s state machine (`Idle → Recording → Transcribing/Formatting → Done | Error`) already has a visible, distinct affordance per state and already surfaces a tag-suggestion `TagChipRow` in the `Done` state when tags are available. Documented here for completeness per the task's surface list, and because its `Done`/`Error` states are the canonical model the other error states in (i) should match.

```
 Idle          Recording        Transcribing/       Done                    Error
 [🎤]  ──tap──▶ [⏹ pulsing] ──▶  Formatting     ──▶  [✓ / ⚠]           or   [⚠ message]
                (amplitude-       [spinner,           + optional             [tap to
                 driven scale)     disabled,           "Note may be           dismiss]
                                   labeled]            incomplete" chip
                                                      + TagChipRow if tags
                                                      (auto-dismisses after
                                                       5s, or tap to dismiss)
```

### Edge case already handled correctly (model to replicate elsewhere)

- `isLikelyTruncated` shows an explicit tertiary-container warning chip ("Note may be incomplete") rather than silently discarding a partial transcript — this is the "no silent failure" principle applied correctly; (c)'s Phase F.1 gate and (i)'s offline-tag-suggestion state should read the same way.
- `VoiceCaptureState.Error` always pairs its message with a dismiss action (`onDismissError`) — no dead end. This is the pattern (i) generalizes below.

---

## (i) Error, empty, and loading states — cross-cutting

| State | Surface(s) | User sees | Exit path |
|---|---|---|---|
| LLM tag suggestions unavailable/offline | Tag insertion (d), Voice capture (h) | Local-frequency chips render immediately and normally (no LLM dependency, per `TagSuggestionState.localSuggestions`); an inline `llmError` message renders below the chip row only if the LLM call itself failed (`SuggestionBottomSheet.kt:91-98`, ported to the new inline `TagChipRow` per (d)) | Local chips remain fully actionable; tapping any chip or the existing dismiss (✕) always works — LLM failure never blocks the working fallback |
| Disk-conflict pending resolution | Main editor Undo/Redo (mobile bottom row + any future desktop equivalent) | Undo/Redo icons render `enabled = false` (grayed, consistent with `AnnotationToolbar`'s existing `canUndo`/`canRedo` visual language) with a tooltip/label "Resolve file conflict to continue" instead of silently no-op'ing on tap | User resolves the existing `DiskConflict` UI (already in `AppState`) — once resolved, Undo/Redo re-enable automatically since they read live state, not a cached value |
| Empty/collapsed-selection formatting | Format-text (b), TODO-toggle (f) | Nothing errors — format/prefix applies at the cursor and is picked up by subsequently-typed text ("toggle-on-type"), matching research §4's explicit best practice | N/A — not an error state, this is the correct non-error behavior other surfaces should match |
| Autocomplete popup vs. soft-keyboard collision | `[[`/`#` autocomplete, new inline `TagChipRow` from (d) | Popup/row flips to render **above** the cursor line when insufficient space below (existing flip-positioning logic to be applied identically to the new inline tag row) | Tap-outside-to-dismiss-keyboard must not also dismiss the popup before a tap-to-select registers — hit-test the popup's bounds first |
| Command-palette formatting entry with no block focused | Command palette (c) | Entry either omitted from the filtered list, or shown disabled with a one-line reason — never a silent no-op | User focuses a block first, then reopens the palette |
| Phase F.1 focus/blur spike fails | Command palette (c) | Feature simply does not ship (blocked at PR review, not a runtime state) | N/A — release gate, not a user-facing state |

---

## UX acceptance criteria

Each is testable by a human tester. JTBD tier is annotated per requirements.md's rubric: **functional** journeys get a hard step-count ceiling; **in-between** journeys get both a step-count ceiling and a discoverability checklist; **social** journeys get a discoverability checklist only (per research §5, forcing a step-count number onto a social-job journey would optimize the wrong thing).

### Functional-job journeys (numeric ceiling)

1. **Insert tag (mobile, flagship)** — `jtbd_tier: functional`. From cursor placed in a block with no tag yet, tapping the 🏷 "Suggest tags" icon and then a suggested chip inserts the tag in **≤ 2 taps**. (Baseline today: 4-5 discrete steps via `SuggestionBottomSheet`.)
2. **Insert tag (mobile) via typed trigger** — typing `#` plus at least one character and selecting from the inline autocomplete list completes in **≤ (query length + 1) keystrokes** — i.e. the typed path must not regress when the button path is redesigned.
3. **Insert link (any platform)** — typing `[[` plus query characters and selecting a result completes in **≤ (query length + 1) keystrokes**; no modal, no page navigation away from the block.
4. **Format text with a visible selection (desktop)** — Ctrl+B/I/S/H/E/K applies the format in **exactly 1 keystroke** given an existing text selection.
5. **Format text with no selection (any platform)** — applying a format at an empty/collapsed cursor does **not** show an error; the format applies to subsequently-typed text ("toggle-on-type"), verified for Bold/Italic/Code at minimum.
6. **Toggle TODO (desktop)** — with no autocomplete open, Ctrl+Enter toggles the block through the real cycle NONE→TODO→DONE→TODO→DONE→... in **exactly 1 keystroke per state transition** (DONE does **not** cycle back to NONE — it cycles back to TODO, per `TodoState`/`EssentialCommands.kt:442-445`'s existing specified behavior, per plan.md's Domain Glossary); `DOING` is a reachable state via other means, not via this Ctrl+Enter toggle path specifically. With autocomplete open, Ctrl+Enter performs the existing "create new page" action and does **not** toggle TODO state (no collision).
7. **Toggle TODO (mobile)** — tapping the new "☑ TODO" overflow-row button toggles state in **exactly 1 tap** (plus the existing 1 tap to expand the overflow row if collapsed — **≤ 2 taps total** from a collapsed toolbar).
8. **Insert code block** — via the new overflow-row "{ } Code block" button (**≤ 2 taps**, accounting for overflow-row expansion) or, on desktop, a dedicated shortcut once assigned by Phase E (**1 keystroke**).
9. **Insert table** — via the new overflow-row "▦ Table" button, inserting a usable 2×2 markdown table skeleton with cursor in the first cell, in **≤ 2 taps** (accounting for overflow-row expansion).
9a. **Insert image (mobile)** — `jtbd_tier: functional`. Tapping either the 📎 Attach-image or 📷 Capture-photo primary-row button and completing the OS-level picker/camera flow inserts the image in **1 toolbar tap** (excluding the OS picker/camera's own step count, which is outside this app's control). No UI change is asserted as required by this criterion — it establishes the current-state baseline Phase A's audit must re-confirm (and, if warranted, improve) before any redesign ships, per (j) Insert-image's finding.
9b. **Insert image — Desktop/Web parity** — `jtbd_tier: functional` (discoverability/parity finding, not a hard step-count ceiling). The current Desktop and Web insert-image mechanism — or its confirmed absence, including the Web-specific known no-op documented in `docs/bugs/open/BUG-004-wasm-page-drop-target-noop.md` — is explicitly recorded in `insert-image.md` rather than left unverified.

### In-between journeys (numeric ceiling + discoverability checklist)

10. **Reorder a block, any distance** — dragging the long-press-revealed handle (⠿) from a block's bullet position to a new position completes the move in **1 continuous gesture regardless of distance** (was N taps for N positions under the old Move-Up/Down-only model).
    - Discoverability checklist: [x] Move Up/Move Down icon buttons remain visible and functional as an always-discoverable fallback; [x] **superseded during Phase D**: GAP-012 deliberately raised the drag handle's idle-state alpha from 0.15 to 0.45 (`BlockGutter.kt`) so the handle is faintly visible at rest rather than long-press-only — a discoverability fix trading this criterion's original "zero steady-state clutter" wording for "the handle is findable without already knowing it exists," per the real gap-backlog.md finding; this is a deliberate, documented trade-off, not an unmet criterion; [x] screen-reader users can complete the same reorder via Move Up/Down without ever needing the gesture.
11. **Multi-select and act on blocks** — entering selection mode and performing Copy/Cut/Delete completes in the existing step count (no regression); the "N selected" label and Clear-selection (✕) exit are both present at all times while in selection mode (no trapped mode, per research §5's emotional-job guidance).

### Social-job journeys (discoverability checklist, no hard number — per research §5)

12. **Annotation labeling** (`jtbd_tier: social`) — checklist: [ ] every tool button (including Undo/Redo after the fix in (g)) shows a tooltip naming its keyboard shortcut; [ ] disabled (uncalibrated) tools communicate why they're disabled, not merely that they are; [ ] the calibrate → measure → label sequence has no step that requires guessing which tool to use next.
13. **Voice-capture and review a transcript** (`jtbd_tier: social`) — checklist: [ ] the current pipeline stage (Recording/Transcribing/Formatting) is always visually distinguishable; [ ] a truncated/incomplete transcript is flagged explicitly rather than silently accepted; [ ] suggested tags from a voice note are discoverable (chip row) without a separate navigation step.

### Error-state and no-dead-end criteria

14. Every error state defined in the (i) table above shows a **specific, non-generic message** (not "Something went wrong") and offers a **specific action** (dismiss, retry-via-fallback, or resolve-conflict) — verified for: LLM-suggestion failure, disk-conflict-pending Undo/Redo, voice-capture `Error` state, voice-capture truncation warning.
15. **No dead ends**: every state in (i) and in the `VoiceCaptureButton` state machine has a reachable exit (dismiss, auto-timeout, or corrective action) back to a usable state — verified by walking each state transition in `VoiceCaptureButton.kt` and each row in the (i) table by hand.
16. Command-palette formatting entries (c) do not ship in a "looks wired, silently does nothing" state: Phase F.1's focus/blur spike must record **PASS** (or the corresponding fix must be implemented and re-verified) before Phase F.2 entries are exposed to users. This criterion blocks the feature outright if unmet — not a "ship and monitor" risk acceptance.

### Accessibility — must-not-regress plus new-affordance requirements

17. **Must not regress**: all 17 existing icon-only buttons in `MobileBlockToolbar` (both handedness variants and selection mode), every `AnnotationToolbar` tool/action button, and every `VoiceCaptureButton` state icon retain a non-null, accurate `contentDescription` exactly as audited in research §3.
18. **New affordances ship accessible from day one**: the drag-to-reorder handle, the "☑ TODO", "{ } Code block", and "▦ Table" overflow buttons, and every new `Command` palette entry each carry a non-null `contentDescription` (or, for palette entries, a non-empty `label`) before merge — no follow-up-PR exception.
19. **Touch targets**: the drag handle's *hit area* (not just its visual glyph) meets the existing IconButton default of ≥48dp — verified since a small ⠿ glyph rendered inline with block bullets risks a smaller visual footprint than a standard toolbar icon.
20. **Keyboard navigation**: `CommandPalette`'s existing DirectionUp/DirectionDown/Enter/Escape handling (`CommandPalette.kt:101-130`) continues to work unchanged once formatting entries are added — verified by keyboard-only palette navigation through at least one new "Format: …" entry to selection.
21. **Color contrast** ≥ 4.5:1 — flagged finding requiring verification during Phase D/G, not assumed compliant: `AnnotationToolbar`'s disabled-state icon tint `Color(0xFF555555)` against its near-black background `Color(0xEE1A1A1A)` (`AnnotationToolbar.kt:213,79`) should be measured; if it falls under 4.5:1, the disabled tint must be lightened as part of the Undo/Redo tooltip fix in (g) rather than left as a pre-existing-and-therefore-ignored gap.

---

## Summary of proposed mechanisms (for Phase D/E/F/G implementers)

- Tag insertion: inline `TagChipRow` anchored at cursor, replacing `SuggestionBottomSheet`'s modal round-trip for the button-triggered path only.
- Reorder: long-press drag handle (Compose `pointerInput`/`detectDragGestures`, no new dependency) additive to, never replacing, Move Up/Move Down.
- TODO toggle: `requestTodoToggle()`/`applyTodoToggle` (dedicated `TodoState` sum type — **not** a `FormatAction` case), autocomplete-gated Ctrl+Enter, plus a mobile overflow-row button.
- Code block / Table: two new overflow-row buttons with concrete insertion behavior (fence pair; 2×2 skeleton).
- Insert image: no confirmed mobile UI change (existing 2-button flow already close to optimal) unless Phase A's audit confirms discoverability confusion between Attach/Capture; the confirmed-relevant gap is Desktop/Web mechanism parity, including the already-tracked `BUG-004` Web drag-and-drop no-op.
- Desktop shortcut discoverability: populate existing `CommandItem` shortcut badges via a new `ShortcutTable`, plus one new "Show keyboard shortcuts" palette entry — no persistent new chrome.
- Annotation Undo/Redo: add `TooltipBox` + hardware binding, matching the toolbar's own existing `ToolButton` convention.
