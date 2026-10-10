---
journey_id: toggle-todo
platforms: [desktop, android, ios, web]
jtbd_tier: functional
step_count_target: "exactly 1 keystroke per state transition (desktop, Ctrl+Enter); ≤2 taps from a collapsed mobile toolbar (design/ux.md criteria 6-7)"
current_step_count:
  desktop: "CONFIRMED BROKEN — no working keyboard trigger exists; target not met (infinite steps — user must hand-type 'TODO ' as literal text)."
  android: "CONFIRMED BROKEN — no TODO-toggle affordance exists anywhere in MobileBlockToolbar today; target not met."
  ios: "Same as Android — confirmed broken."
  web: "Same as desktop — confirmed broken."
heuristic_findings: |
  Visibility-of-system-status violation: Ctrl+Enter silently splits the block instead of toggling TODO state (BlockEditor.kt:407-419, no isCtrlPressed check), and the Command Palette's "Toggle Todo" entry computes the correct new content then discards it (StelekitViewModel.executeCommand() never writes newContent back) — both give zero feedback that the action failed (see Heuristic findings section).
test_ids: []
status: audited
last_verified: 2026-07-05
---

# Toggle TODO

## Trigger
User wants to mark a block as TODO/DOING/DONE (task-state cycling), by hardware keyboard (Ctrl+Enter) or a mobile toolbar affordance.

## Current step sequence — desktop/web (CONFIRMED BROKEN)

1. Cursor in a block, e.g. content `"Call mom"`, no autocomplete popup open.
2. User presses Ctrl+Enter.
3. **No Ctrl+Enter/TODO-toggle handling exists in `handleKeyEvent` outside the autocomplete-scoped branch.** The only Ctrl+Enter handling found is the autocomplete "create new page" binding (`BlockEditor.kt:258`), reachable only `if (autocompleteState != null && searchResults.isNotEmpty())` (line 247).
4. Plain `Key.Enter` handling (`BlockEditor.kt:407-419`) checks `event.isShiftPressed` (Shift+Enter → literal newline) but has **no `event.isCtrlPressed` check at this handler** — confirming Ctrl+Enter falls straight through to plain-Enter behavior: the block **splits** at the cursor instead of toggling TODO state. This is a genuine bug, not a missing-feature gap: the user's Ctrl held-down Enter still creates a new sibling block.
5. **No "TODO "/"DOING "/"DONE " string literal or prefix-detection logic exists anywhere in `BlockEditor.kt`** (confirmed via full-file read, zero matches) — there is no code path that even recognizes these markers today from the editor's side.
6. **Only working path**: the user hand-types the literal text `"TODO "` at the start of the block's content. This satisfies the markdown-rendering side (`MarkdownEngine.kt`'s `parseMarkdownWithStyling` renders task markers, per stack.md §4), but nothing in the editor helps the user get there faster than typing 5 characters by hand.
7. **Separately**, `EssentialCommands.kt`'s `toggleTodo` `EditorCommand` (lines 427-445) is fully implemented in isolation — the exact 4-state cycle (`content.startsWith("TODO ") → "DONE "`, `"DOING " → "DONE "`, `"DONE " → "TODO "`, else → `"TODO $content"`, lines 442-445) matches plan.md's `TodoState`/`applyTodoToggle` Domain Glossary spec precisely — but per features.md §2, `StelekitViewModel.executeCommand()` (line 1973) discards the `CommandResult` this produces; nothing writes `newContent` back to the block. So even where this command is reachable (the Command Palette, since `block.toggle-todo`'s `CommandConfig(requiresBlock = true)` has no selection requirement and likely passes the palette's filter), **selecting "Toggle Todo" from the palette today computes the right answer and then throws it away** — a wired-looking, silently non-functional entry, not a missing one.

## Current step sequence — mobile (CONFIRMED BROKEN / absent)

1. No TODO-toggle button, gesture, or affordance exists anywhere in `MobileBlockToolbar.kt` today (confirmed: the formatting overflow row's 8 buttons are Bold/Italic/Strikethrough/Code/Highlight/Quote/Numbered-list/Heading only — no TODO entry; the primary/bottom rows likewise have no TODO affordance).
2. Only working path (same as desktop): hand-type `"TODO "` as literal text.

## Notes
- Prior-art cross-check (`docs/ux/journey-map.md`, not merged — see README changelog) independently confirms this exact finding from an earlier pass: *"Dead settings... TODO/DOING/DONE toggling has a command definition but no reachable UI trigger."* This project's own research (features.md §1-§2) arrived at the identical conclusion independently — reused as corroborating evidence, not treated as new.
- This is the anchor bug Phase C.1 is scoped to fix (`TodoState`/`applyTodoToggle`/`requestTodoToggle`, per plan.md's Domain Glossary and Pattern Decisions — deliberately NOT a `FormatAction` case, to avoid colliding with the QUOTE/NUMBERED_LIST/HEADING mutually-exclusive strip-group at `BlockEditor.kt:511-514`).
- Ctrl+Enter overload is real and must be resolved by scoping: autocomplete-active Ctrl+Enter must keep its existing "create new page" priority; only when `autocompleteState == null` should Ctrl+Enter dispatch `requestTodoToggle()` (per validation.md's happy-path scenario).

## Heuristic findings

1. **Visibility of system status — VIOLATION (most severe in this project).** Ctrl+Enter falls through to plain-`Key.Enter` handling with no `event.isCtrlPressed` check (`BlockEditor.kt:407-419`) and **splits the block** instead of toggling TODO state — the user's held-down Ctrl produces a completely different, unexplained result with zero error or message. Worse still: selecting "Toggle Todo" from the Command Palette reaches `EssentialCommands.kt`'s fully-correct `toggleTodo` logic (lines 427-445), but `StelekitViewModel.executeCommand()` "discards the `CommandResult` this produces" — the palette action visibly executes (no error, no crash) and the user is told nothing that it silently did nothing. This is a "wired-looking, silently non-functional entry," the doc's own words for the worst kind of visibility failure — the UI actively looks like it worked.
2. **Consistency and standards — VIOLATION.** The same physical shortcut, Ctrl+Enter, already carries an established meaning elsewhere in the app (autocomplete "create new page," `BlockEditor.kt:258`, scoped to `autocompleteState != null`) but produces block-split behavior outside that scope instead of either doing nothing or toggling TODO — one key combo, context-dependent and inconsistent behavior with no visible signal of which mode is active. The doc itself flags this: "Ctrl+Enter overload is real and must be resolved by scoping."
3. **Discoverability — VIOLATION.** "No 'TODO '/'DOING '/'DONE ' string literal or prefix-detection logic exists anywhere in `BlockEditor.kt` (confirmed via full-file read, zero matches)," and "no TODO-toggle button, gesture, or affordance exists anywhere in `MobileBlockToolbar.kt`" on any platform — there is categorically zero in-app affordance for this capability; a new user can only learn it exists from external documentation of markdown TODO syntax, pure tribal knowledge.
4. **Minimal memory load — VIOLATION.** The only working path is hand-typing the literal 5-character string `"TODO "` from memory with no visible prompt anywhere in the UI. The correct 4-state cycle (`EssentialCommands.kt:442-445`: `TODO`→`DONE`, `DOING`→`DONE`, `DONE`→`TODO`, else→`TODO`) is fully implemented but never surfaced to the user — even someone who already knows the markdown convention must recall the exact cycle order from memory rather than recognize it via any UI control.
