---
journey_id: format-text
platforms: [desktop, android, ios, web]
jtbd_tier: functional
step_count_target:
  with_selection: "exactly 1 keystroke (desktop) — design/ux.md criterion 4"
  no_selection: "no error; format applies to subsequently-typed text (toggle-on-type) — design/ux.md criterion 5"
current_step_count:
  desktop: "BOLD/ITALIC/STRIKETHROUGH/HIGHLIGHT/CODE meet the 1-keystroke-with-selection target via Ctrl/Cmd+B/I/S/H/E. LINK/QUOTE/NUMBERED_LIST/HEADING have NO keyboard shortcut at all today — target not applicable/not met for those 4 actions on desktop."
  android: "All 9 FormatAction cases reachable via the formatting overflow row (1 tap to expand + 1 tap per action = 2 taps, not 1 keystroke, but no hardware keyboard assumed)."
  ios: "Same as Android via toolbar; hardware-keyboard shortcuts (Ctrl+B etc.) untested on iOS per stack.md §6 — no iOS key-event test exists in this repo."
  web: "Same keyboard-shortcut coverage as desktop for BOLD/ITALIC/STRIKETHROUGH/HIGHLIGHT/CODE; LINK/QUOTE/NUMBERED_LIST/HEADING have no shortcut. Whether the canvas's key handler actually suppresses the browser's own reserved bindings for any of these is unverified (plan.md Unresolved Questions)."
heuristic_findings: |
  Consistency violation: Ctrl+K is hijacked for "open search with selected text" (BlockEditor.kt:307-313) instead of a link/FormatAction shortcut, breaking the app's own Ctrl+<letter>-to-FormatAction pattern; combined with zero on-screen shortcut legend, all 5 mapped shortcuts require pure recall (see Heuristic findings section).
test_ids: []
status: audited
last_verified: 2026-07-05
---

# Format text

## Trigger
User wants to apply BOLD/ITALIC/STRIKETHROUGH/HIGHLIGHT/CODE/LINK/QUOTE/NUMBERED_LIST/HEADING to selected or about-to-be-typed text.

## `FormatAction` enum (source of truth)
Defined at `JournalsViewModel.kt:26-37` (despite the filename): `BOLD("**","**")`, `ITALIC("*","*")`, `STRIKETHROUGH("~~","~~")`, `HIGHLIGHT("^^","^^")`, `CODE("` ` `","` ` `")`, `LINK("[[","]]")`, `QUOTE("> ","")`, `NUMBERED_LIST("1. ","")`, `HEADING("# ","")`. **No `CODE_BLOCK` case exists today** — confirmed gap, see `insert-code-block.md`.

## Current step sequence — hardware keyboard (desktop/web)

1. `handleKeyEvent` gates format shortcuts on `event.isCtrlPressed || event.isMetaPressed` (`BlockEditor.kt:304`) — the repo's established cross-platform Ctrl(Win/Linux)/Cmd(macOS) convention.
2. Mapped keys (`BlockEditor.kt:314-321`): `Key.B → BOLD`, `Key.I → ITALIC`, `Key.S → STRIKETHROUGH`, `Key.H → HIGHLIGHT`, `Key.E → CODE`. **`Key.K` is special-cased for "open search with selected text" (`BlockEditor.kt:307-313`), not a FormatAction.** No shortcut exists for LINK, QUOTE, NUMBERED_LIST, or HEADING — these 4 of 9 `FormatAction` cases are keyboard-unreachable on desktop/web today.
3. `applyFormatAction` (`BlockEditor.kt:491-558`) executes:
   - **Wrap actions** (BOLD/ITALIC/STRIKETHROUGH/HIGHLIGHT/CODE/LINK, lines 526-557): with a selection, checks whether it's already wrapped by the same markers just outside the selection bounds (lines 541-544) and unwraps (toggles) if so, else wraps — 1 keystroke, target met. With no selection (collapsed cursor), inserts `prefix+suffix` and places the cursor between them (lines 530-533) — the "toggle-on-type" behavior design/ux.md criterion 5 requires; confirmed present in code, not merely assumed.
   - **Line-prefix actions** (QUOTE/NUMBERED_LIST/HEADING, lines 503-524): mutually exclusive as a strip-group — if the line already starts with the action's prefix, toggles it off (lines 508-511); otherwise strips any *other* line-prefix action's matching prefix first (lines 513-516, via a `FormatAction.entries.filter{...}.fold`) before prepending the new one, so a block can never simultaneously carry two of QUOTE/NUMBERED_LIST/HEADING.
4. Total: exactly 1 keystroke when a selection exists and a shortcut is mapped (BOLD/ITALIC/STRIKETHROUGH/HIGHLIGHT/CODE) — target met for those 5. LINK/QUOTE/NUMBERED_LIST/HEADING require the toolbar (mobile) or are entirely keyboard-unreachable today on desktop/web.

## Current step sequence — mobile toolbar (formatting overflow row)

1. User taps the "…" `IconButton` (`MobileBlockToolbar.kt:172-184`, `contentDescription = "Toggle formatting"`) to expand `formattingExpanded` (state at line 60) if collapsed.
2. User taps one of 8 `TextButton`s in the expanded row: Bold "B" (110), Italic "I" (112-120), Strikethrough "S" (121-129), Code "`</>`" (130-138), Highlight "H" (139-144), Quote "`>_`" (145-151), Numbered-list "1." (152-158), Heading "H1" (159-164) — note **LINK has no dedicated overflow-row button** (the `[[ ]]` primary-row button, see `insert-link.md`, covers link insertion via a different mechanism).
3. Total: 2 taps from a collapsed toolbar (1 to expand + 1 to apply) for all 8 covered actions.

## Notes
- Prior-art cross-check (`docs/ux/journey-map.md`, not merged — see README changelog) independently flags: *"Selection-wrap linking & formatting shortcuts (Ctrl/Cmd+B/I/S/H/E/K) — powerful but entirely undiscoverable; no tooltip, legend, or menu surfaces any of it"* and lists "Zero shortcut discoverability" as cross-cutting gap #3 — this matches design/ux.md (b)'s finding verbatim and is reused, not rediscovered.
- The `EssentialCommands.kt`/`CommandManager` parallel command system defines `text.bold`/`text.italic`/etc. `EditorCommand`s with `requiresSelection = true`, but per features.md §2, the command palette's `CommandContext` never carries `selectionStart`/`selectionEnd`, so these are filtered out of `getAvailableCommands()`'s output entirely — they do not currently appear in the palette at all (not merely non-functional when invoked, as with `block.toggle-todo` — these specific commands are invisible).

## Heuristic findings

1. **Visibility of system status — PASS (wrap actions only).** The toggle-on-type behavior is confirmed present in code, not merely assumed: with no selection, `applyFormatAction` "inserts `prefix+suffix` and places the cursor between them (lines 530-533)" (`BlockEditor.kt:491-558`) — the user sees the markers appear immediately, visible confirmation the action registered, before they've typed the formatted text.
2. **Consistency and standards — VIOLATION.** `Key.K` is special-cased for "open search with selected text" (`BlockEditor.kt:307-313`) rather than a `FormatAction` — this breaks the app's own established `Ctrl+<letter> → FormatAction` pattern (B/I/S/H/E) and diverges from the common cross-editor convention (many editors bind Ctrl+K to "insert link"), so the one letter most likely to be guessed for LINK does something unrelated.
3. **Discoverability — VIOLATION.** Reused prior-art quote: *"powerful but entirely undiscoverable; no tooltip, legend, or menu surfaces any of it"* ("Zero shortcut discoverability," cross-cutting gap #3). This is compounded by a second, independent discoverability failure: the `text.bold`/`text.italic`/etc. palette commands are filtered out of `getAvailableCommands()` entirely because `CommandContext` never carries selection state (features.md §2) — they "do not currently appear in the palette at all," not even as a disabled/greyed entry a user could learn from.
4. **Minimal memory load — VIOLATION (desktop/web) / PASS (mobile).** The 5 mapped shortcuts (B/I/S/H/E) must be recalled from memory with no on-screen legend per the reused finding above — pure recall. The mobile toolbar overflow row is the mitigating counter-example: all 8 covered actions are labeled, visible buttons (`MobileBlockToolbar.kt:110-164`: "B", "I", "S", `` `</>` ``, "H", `>_`, "1.", "H1") — recognition rather than recall — though LINK/QUOTE/NUMBERED_LIST/HEADING remain keyboard-unreachable on desktop/web with no shortcut at all, forcing those 4 into hand-typed markdown recall regardless of platform.
