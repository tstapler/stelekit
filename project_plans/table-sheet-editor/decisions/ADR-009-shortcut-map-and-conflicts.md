# ADR-009: Keyboard map and conflicts with existing SteleKit shortcuts

**Status**: Proposed (confirmed by Task 5.3c and 5.5b tests)
**Date**: 2026-11-24

## Context
research/ux.md section 5 proposes a starting set and asks the planner to grep for conflicts. Grep over `kmp/src/commonMain` for `Key.*` found these existing bindings.

| Existing binding | Where | Applies |
|---|---|---|
| Ctrl/Cmd+Shift+P command palette, +K search, +B sidebar, +Shift+B right sidebar, +, settings, +Z undo, +Shift+Z / +Y redo, +LeftBracket / +RightBracket / Alt+arrows back/forward, +Shift+D debug | `ui/App.kt:720-731` | App window; not reachable from a Dialog window (verified in Task 5.1a) |
| Ctrl+Shift+E export page or selection | `ui/screens/PageView.kt:273` | Page; **conflicts with the proposed "edit as markdown"** |
| Ctrl+A select all blocks, Ctrl+C/X/V block copy/cut/paste, Delete/Backspace, Esc in selection mode | `PageView.kt:242-269`, `BlockList.kt:329-356` | Page, block selection mode |
| Ctrl+Enter, Tab, Esc, Ctrl+K (with selection) inside a block editor | `BlockEditor.kt:272,303,307,321` | Raw block editor, not the grid |
| Ctrl+B/I/E (format) | `ShortcutTable.kt` | Raw block editor |
| Plain D/A/R/K tool keys | `ui/annotate/AnnotationToolbar.kt:472-479` | Annotation screen only |
| Ctrl+Alt, Ctrl+Shift+M/R | none in repo | Reserved or AltGr-sensitive in browsers (Chrome Ctrl+Shift+M profile, Ctrl+Shift+R reload) and on European layouts (AltGr = Ctrl+Alt) |

## Decision
Navigation mode (grid focused, not editing) and edit mode follow research/ux.md section 5 unchanged (arrows, Tab/Shift+Tab, Enter, Shift+Enter, F2, Esc, printable-key replace, Delete/Backspace clear, Home/End, Ctrl+Home/End, PgUp/PgDn, Ctrl+Arrow, Shift+Arrows, Ctrl+Space/Shift+Space, Ctrl+A/C/X/V, Ctrl+Z/Y, Ctrl+Shift+=/-, Alt+Shift+Arrows, Shift+F10/Menu, Alt+Enter for `<br>` in edit mode) with these changes:

| Action | Binding | Reason |
|---|---|---|
| Maximize sheet | `Ctrl/Cmd+Shift+Enter` (inline, navigation mode; in the open sheet the same chord closes it, iteration 7) | `Ctrl+Alt+M` is AltGr-sensitive and browser-adjacent; no repo binding uses Shift+Enter with Ctrl |
| Edit as markdown | `Shift+F2` | `Ctrl+Shift+E` is export; Shift+F2 has no existing binding and no browser reservation |
| Fill down/right (Ctrl+D/R) | not bound | Out of scope; Ctrl+D is a browser bookmark |

- The grid's `onPreviewKeyEvent` consumes Ctrl+A/C/X/V and Esc in navigation mode so `PageView` and `BlockList` handlers do not also fire; in edit mode it consumes nothing except Enter/Tab/Esc, and never while `composition != null`.
- Wasm: Tab traversal is already prevented in `wasmJsMain/.../browser/Main.kt:125`; no further browser-key override is added.
- Every shortcut also has a menu, strip or TalkBack custom action (ADR-005, Epic 6), so no command is keyboard-only or drag-only.
- `ShortcutTable.kt` stays for the block editor; sheet shortcuts are listed in the sheet's overflow "Keyboard shortcuts" panel, built from one pure `TableShortcutMap` so docs and tests share a source.

## Touch and assistive equivalents (iteration 6)

Every chord that a phone user lacks has a control, so no command needs a hardware key (ux.md design rules 3 and 14).

| Chord | Touch and assistive equivalent |
|---|---|
| Enter (commit, move down) | Soft keyboard action key (`ImeAction.Next`, Enter semantics; on the last body row it commits and stays) |
| Tab (commit, move right) | Strip and phone edit bar **Next column** |
| Alt+Enter (line break `<br>`) | Strip and phone edit bar **Insert line break** |
| Esc in `Edit` (cancel) | Strip **Cancel edit** (always present in `Edit`; posts an Undo notice only when the draft differs); **Close** is the same fixed slot in `Navigate` |
| Esc in sheet `Navigate` (single cell) | Not handled: Esc never closes the sheet. Close with the (x) "Close table" button, Android Back, or Ctrl/Cmd+Shift+Enter (iteration 7) |
| Enter on the last body row | Commits and stays on the same cell; no row is appended, Insert row is explicit |
| Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y | **Undo** and **Redo** buttons: sheet top bar, sheet context strip, range strip, inline strip; table-scoped in `Navigate` and field-scoped in `Edit` (ux.md rule 16) |
| Back (Android) | The single ladder of ux.md 3.3, shared with Esc |

The grid's key handler, the strip and the edit bar all dispatch the same `GridCommand` values, so a test over `TableShortcutMap` also covers the touch controls (Task 6.1b iterates the map and the strip).

## Consequences
No collision with a currently shipped binding. Cmd on macOS maps to the same actions as Ctrl, as `onGraphKeyEvent` does with `isMod`.

## Browser check (iteration 4)
`Ctrl+Shift+=` and `Ctrl+-` (insert/delete) are zoom commands in Chrome (INFERRED, not tested). Task 8.3a checks that the page receives them; if the browser swallows them, Web uses the menu alternatives only and the shortcuts panel marks them "not on Web".
