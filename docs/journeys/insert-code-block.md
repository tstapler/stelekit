---
journey_id: insert-code-block
platforms: [desktop, android, ios, web]
jtbd_tier: functional
step_count_target: "≤2 taps (mobile, overflow-row 'Code block' button) or 1 keystroke (desktop, once a shortcut is assigned) — design/ux.md criterion 8"
current_step_count:
  desktop: "CONFIRMED ABSENT — no trigger exists; user must hand-type triple-backtick fence syntax with no assistance. Target not met."
  android: "CONFIRMED ABSENT — no button/gesture exists in MobileBlockToolbar. Target not met."
  ios: "Same as Android — confirmed absent."
  web: "Same as desktop — confirmed absent."
post_fix_step_count:
  desktop: "1 step via command palette (Ctrl/Cmd+Shift+P → type 'Code Block' → Enter) — no hardware-keyboard shortcut assigned (confirmed via ShortcutTable.kt, see mechanism note). Overflow-row toolbar button ('{ } Code block') is also present but is a touch-first affordance, not the primary desktop path."
  android: "2 taps (tap ⋯ overflow toggle → tap '{ } Code block') — meets the ≤2-tap target. Command palette ('Format: Code Block') is also reachable but is a slower fallback path."
  ios: "Same as Android — 2 taps via the overflow-row '{ } Code block' button."
  web: "Same as desktop — 1 step via command palette; overflow-row button also present."
  mechanism: |
    `FormatAction.CODE_BLOCK` (Phase C.2.1, `GAP-006`) now exists and is wired through three paths, all
    in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`:
    1. **Mutation logic** — `applyFormatAction` (`ui/components/BlockEditor.kt:598-615`) special-cases
       `CODE_BLOCK` ahead of the generic wrap/line-prefix logic: wraps the entire block content in a
       ` ``` `-fenced pair (toggling off if already fenced), places the cursor just inside the opening
       fence, and commits via the standard `onContentChange`/version-increment path.
    2. **Mobile toolbar** — `ui/components/MobileBlockToolbar.kt:181-191` (`GAP-008` / Story D.2.1) adds
       a "{ } Code block" `TextButton` to the overflow-row (behind the "⋯" `formattingExpanded` toggle),
       calling `onFormat(FormatAction.CODE_BLOCK)`. Reaching it is 2 taps: tap the overflow toggle, then
       tap the button — meets the `step_count_target`.
    3. **Command palette** — `ui/StelekitViewModel.kt:2065` adds a `"Format: Code Block"` entry
       (id `format.code_block`) dispatching `bsm.requestFormat(FormatAction.CODE_BLOCK)` via the same
       `Command` list used by BOLD/ITALIC/etc.
    4. **No hardware-keyboard shortcut** — `ui/components/ShortcutTable.kt:19-22` explicitly documents
       that `CODE_BLOCK` (and `TABLE_INSERT`) "have no hardware-keyboard binding yet (mobile-toolbar /
       command-palette only)" — `bindings` (lines 36-45) has no entry for either action, and
       `ShortcutTable.forAction`/`actionForKeyEvent` therefore return `null`/never match for them. This
       was confirmed by reading the table directly rather than assumed; the desktop/web
       `step_count_target` of "1 keystroke (desktop, once a shortcut is assigned)" is therefore **not
       yet met** — desktop/web users reach this only via the command palette (itself opened with
       Ctrl/Cmd+Shift+P, `App.kt:1764`) or, if visible on that surface, the mobile-toolbar button.
    This closes the "Discoverability — VIOLATION" and "Consistency and standards — VIOLATION" findings
    below to the extent a toolbar button + palette entry now exist where previously nothing did; the
    "no hardware shortcut yet" gap remains open and is accurately reflected above rather than
    overstated as fully closed.
heuristic_findings: |
  Discoverability violation: CONFIRMED ABSENT trigger on all platforms — no `/code` or auto-expanding fence exists anywhere, unlike Logseq upstream/Notion/Obsidian, forcing users to hand-type the fence and language name from memory with zero editor assistance (see Heuristic findings section). NOTE: this section documents the PRE-fix (Phase A audit) state; see `post_fix_step_count.mechanism` above for the current, post-Phase-C/D/F state — the toolbar button and command-palette entry now provide a discoverable, memory-free trigger on all platforms; only the "no hardware shortcut" gap remains.
test_ids: []
status: audited
last_verified: 2026-07-06
---

# Insert code block

## Trigger
User wants to insert a fenced (triple-backtick) code block, distinct from `FormatAction.CODE`'s single-backtick inline wrap.

## Current step sequence — all platforms (CONFIRMED ABSENT)

1. `FormatAction` (`JournalsViewModel.kt:26-37`) has a `CODE` case (single backtick inline wrap) but **no `CODE_BLOCK` case** — confirmed by reading the full enum.
2. No triple-backtick (` ``` `) fence-pair insertion logic, and no "code block"/`CODE_BLOCK` string or enum reference, exists anywhere in `BlockEditor.kt` (confirmed via full-file read).
3. No auto-expand-on-type behavior exists either — features.md §2 confirms zero markdown-triggered auto-formatting on typing anywhere in the file (no `startsWith("\`\`\`")` post-keystroke handling).
4. **Only working path**: user hand-types the triple-backtick fence pair (and, for a language hint, the language name) directly as literal text, with zero editor assistance — no auto-pairing of the closing fence, no cursor-placement help, no language picker.

## Post-fix step sequence (Phase C.2.1 / D.2.1 / F.2.1 — GAP-006/GAP-008, current state)

### Mobile toolbar path (Android/iOS)
1. User is editing a block; taps the "⋯" overflow toggle (`MobileBlockToolbar.kt:206-219`) to reveal the secondary formatting row.
2. User taps "{ } Code block" (`MobileBlockToolbar.kt:183-190`, `contentDescription = "Code block"`).
3. `onFormat(FormatAction.CODE_BLOCK)` → `applyFormatAction` (`BlockEditor.kt:598-615`) wraps the full block content in a ` ``` ` fence pair and places the cursor just inside the opening fence.
4. **Step count: 2 taps** — meets the `step_count_target` of "≤2 taps (mobile, overflow-row 'Code block' button)".

### Command palette path (all platforms)
1. User opens the command palette (Ctrl/Cmd+Shift+P, `App.kt:1764`).
2. User types "Code Block" to filter to the `"Format: Code Block"` entry (`StelekitViewModel.kt:2065`).
3. User selects the entry (Enter or tap) → dispatches `bsm.requestFormat(FormatAction.CODE_BLOCK)`.
4. **Step count**: on desktop/web this is the fastest available trigger today (palette-open + type + select). No hardware-keyboard shortcut exists (`ShortcutTable.kt:19-22` — confirmed by reading the table, `CODE_BLOCK` has no `bindings` entry), so the `step_count_target`'s "1 keystroke (desktop, once a shortcut is assigned)" clause is **still not met** — that requires a future dedicated key binding, which has not been added.

## Notes
- Cross-product comparison (features.md §1): Logseq upstream, Notion, and Obsidian all offer a `/code` or auto-expanding `` ``` `` trigger; SteleKit has neither. This is one of the two clearest "must hand-type syntax with zero assistance" gaps identified by research (the other being `insert-table`).
- Per plan.md's Pattern Decisions table, this is modeled as a new `FormatAction.CODE_BLOCK` case (a pure text-mutation inserting a fence pair with cursor placed inside) rather than a `StructuralAction`, since no picker/dialog UI is required for the plain (no-language-picker) version — only escalate to `StructuralAction` if a language-picker dialog is later confirmed necessary.
- No prior-art conflict found in `docs/ux/journey-map.md` (not merged — see README changelog) — that document does not separately diagram code-block insertion; it is covered only implicitly under "Insert and manage rich content" in its Story Map Backbone table, with no dedicated journey.

## Heuristic findings

1. **Visibility of system status — VIOLATION.** Because "no auto-expand-on-type behavior exists either — features.md §2 confirms zero markdown-triggered auto-formatting on typing anywhere in the file," the user gets no acknowledgment at all as they type the fence syntax — no auto-closing of the fence, no confirmation that the app has recognized a code block is being started, unlike the inline `FormatAction.CODE` wrap which visibly applies markers immediately on keystroke.
2. **Consistency and standards — VIOLATION.** `FormatAction.CODE` (single-backtick inline wrap) is a first-class citizen with a keyboard shortcut (Ctrl/Cmd+E) and toolbar button, but the closely-related fenced code block — planned as a sibling `FormatAction.CODE_BLOCK` case per plan.md's Pattern Decisions — has no equivalent entry point anywhere today, an inconsistency where one "code" formatting flavor lives in the established FormatAction system and the other is entirely absent from it.
3. **Discoverability — VIOLATION.** Cross-product comparison confirms "Logseq upstream, Notion, and Obsidian all offer a `/code` or auto-expanding ``` trigger; SteleKit has neither" — explicitly called out as one of "the two clearest 'must hand-type syntax with zero assistance' gaps identified by research."
4. **Minimal memory load — VIOLATION.** "The only working path: user hand-types the triple-backtick fence pair (and, for a language hint, the language name) directly as literal text, with zero editor assistance — no auto-pairing of the closing fence, no cursor-placement help, no language picker" — the full fence syntax plus an arbitrary language name must be recalled from memory with no recognition aid whatsoever.
