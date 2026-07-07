---
journey_id: insert-table
platforms: [desktop, android, ios, web]
jtbd_tier: functional
step_count_target: "≤2 taps (mobile, overflow-row 'Table' button), producing a usable 2×2 skeleton with cursor in the first cell — design/ux.md criterion 9"
current_step_count:
  desktop: "CONFIRMED ABSENT — no trigger exists, no markdown-table-insert affordance anywhere. Target not met."
  android: "CONFIRMED ABSENT — no button/gesture in MobileBlockToolbar. Target not met."
  ios: "Same as Android — confirmed absent."
  web: "Same as desktop — confirmed absent."
post_fix_step_count:
  desktop: "1 step via command palette (Ctrl/Cmd+Shift+P → type 'Table' → Enter) — no hardware-keyboard shortcut assigned (confirmed via ShortcutTable.kt, see mechanism note). Overflow-row toolbar button ('▦ Table') is also present but is a touch-first affordance, not the primary desktop path."
  android: "2 taps (tap ⋯ overflow toggle → tap '▦ Table') — meets the ≤2-tap target. Command palette ('Format: Table') is also reachable but is a slower fallback path."
  ios: "Same as Android — 2 taps via the overflow-row '▦ Table' button."
  web: "Same as desktop — 1 step via command palette; overflow-row button also present."
  mechanism: |
    `FormatAction.TABLE_INSERT` (Phase C.2.2, `GAP-007`) now exists and is wired through three paths, all
    in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`:
    1. **Mutation logic** — `applyFormatAction` (`ui/components/BlockEditor.kt:619-626`) special-cases
       `TABLE_INSERT` ahead of the generic wrap/line-prefix logic: replaces the block content with a
       fixed 2×2 markdown table skeleton (`"| | |\n| --- | --- |\n| | |"`), places the cursor in the
       first header cell (`TextRange(2)`), and commits via the standard `onContentChange`/
       version-increment path — matching plan.md's "no dialog needed for a fixed 2×2 skeleton" decision.
    2. **Mobile toolbar** — `ui/components/MobileBlockToolbar.kt:192-199` (`GAP-009` / Story D.2.1) adds
       a "▦ Table" `TextButton` to the overflow-row (behind the "⋯" `formattingExpanded` toggle),
       calling `onFormat(FormatAction.TABLE_INSERT)`. Reaching it is 2 taps: tap the overflow toggle,
       then tap the button — meets the `step_count_target` of "≤2 taps ... producing a usable 2×2
       skeleton with cursor in the first cell."
    3. **Command palette** — `ui/StelekitViewModel.kt:2066` adds a `"Format: Table"` entry
       (id `format.table_insert`) dispatching `bsm.requestFormat(FormatAction.TABLE_INSERT)` via the
       same `Command` list used by BOLD/ITALIC/etc.
    4. **No hardware-keyboard shortcut** — `ui/components/ShortcutTable.kt:19-22` explicitly documents
       that `TABLE_INSERT` (and `CODE_BLOCK`) "have no hardware-keyboard binding yet (mobile-toolbar /
       command-palette only)" — `bindings` (lines 36-45) has no entry for either action, and
       `ShortcutTable.forAction`/`actionForKeyEvent` therefore return `null`/never match for it. This
       was confirmed by reading the table directly rather than assumed. Desktop/web users reach this
       only via the command palette (itself opened with Ctrl/Cmd+Shift+P, `App.kt:1764`) or, if visible
       on that surface, the mobile-toolbar button.
    This closes the "Discoverability — VIOLATION" and "Consistency and standards — VIOLATION" findings
    below (and the "most severe minimal-memory-load violation of the 7 journeys" characterization no
    longer applies — the skeleton is now inserted, not hand-typed) to the extent a toolbar button +
    palette entry now exist where previously nothing did; no hardware shortcut has been added, which is
    accurately reflected above rather than overstated as fully closed.
heuristic_findings: |
  Discoverability violation: CONFIRMED ABSENT trigger on all platforms despite full rendering support (TableBlockScreenshotTest.kt) — no `/table` equivalent exists, forcing the most memory-intensive hand-typed syntax of any of the 7 journeys with zero editor assistance (see Heuristic findings section). NOTE: this section documents the PRE-fix (Phase A audit) state; see `post_fix_step_count.mechanism` above for the current, post-Phase-C/D/F state — the toolbar button and command-palette entry now insert the 2×2 skeleton directly, eliminating the hand-typed-syntax memory-load gap; only the "no hardware shortcut" gap remains.
test_ids: []
status: audited
last_verified: 2026-07-06
---

# Insert table

## Trigger
User wants to insert a markdown table skeleton without hand-typing pipe/dash syntax.

## Current step sequence — all platforms (CONFIRMED ABSENT)

1. No markdown-table-insert affordance exists anywhere (`BlockEditor.kt`, `MobileBlockToolbar.kt` — confirmed via targeted grep for "table", `|---|`, or similar; zero matches).
2. Note: `TableBlockScreenshotTest.kt` exists in the screenshot test suite (per pitfalls.md §3's Roborazzi inventory) — this indicates **table rendering** (view-mode display of an already-existing markdown table) is supported by `MarkdownEngine.kt`; it is specifically table *insertion from the editor* that is absent. Do not conflate the two — this is a triggering/insertion gap, not a rendering gap.
3. **Only working path**: user hand-types the full markdown table syntax (header row, `|---|---|` separator, data rows) as literal text, with zero editor assistance.

## Post-fix step sequence (Phase C.2.2 / D.2.1 / F.2.1 — GAP-007/GAP-009, current state)

### Mobile toolbar path (Android/iOS)
1. User is editing a block; taps the "⋯" overflow toggle (`MobileBlockToolbar.kt:206-219`) to reveal the secondary formatting row.
2. User taps "▦ Table" (`MobileBlockToolbar.kt:194-198`, `contentDescription = "Table"`).
3. `onFormat(FormatAction.TABLE_INSERT)` → `applyFormatAction` (`BlockEditor.kt:619-626`) replaces the block content with the 2×2 skeleton `"| | |\n| --- | --- |\n| | |"` and places the cursor in the first cell.
4. **Step count: 2 taps** — meets the `step_count_target` of "≤2 taps ... producing a usable 2×2 skeleton with cursor in the first cell".

### Command palette path (all platforms)
1. User opens the command palette (Ctrl/Cmd+Shift+P, `App.kt:1764`).
2. User types "Table" to filter to the `"Format: Table"` entry (`StelekitViewModel.kt:2066`).
3. User selects the entry (Enter or tap) → dispatches `bsm.requestFormat(FormatAction.TABLE_INSERT)`.
4. **Step count**: on desktop/web this is the fastest available trigger today (palette-open + type + select). No hardware-keyboard shortcut exists (`ShortcutTable.kt:19-22` — confirmed by reading the table, `TABLE_INSERT` has no `bindings` entry); a dedicated key binding has not been added.

## Notes
- Cross-product comparison (features.md §1): Logseq upstream (`/table`), Notion (`/table`) both offer a table-insert trigger; SteleKit has none.
- Per plan.md's Pattern Decisions table, this is modeled as a `FormatAction`-style pure text insertion (2×2 skeleton: header row + separator + one data row, cursor placed in the first cell) rather than a `StructuralAction`, consistent with the "only escalate to StructuralAction if a picker/dialog UI is required" rule — no dialog is needed for a fixed 2×2 starting skeleton.
- No prior-art conflict found in `docs/ux/journey-map.md` (not merged — see README changelog); table insertion is not separately diagrammed there either.

## Heuristic findings

1. **Visibility of system status — VIOLATION.** "Confirmed via targeted grep for 'table', `|---|`, or similar; zero matches" — there is no feedback loop at all during insertion (no live preview, no syntax assistance, no confirmation as the user hand-types the structure). The doc's own distinction is instructive here: table *rendering* does give eventual visible feedback once a correctly hand-typed table is complete (`TableBlockScreenshotTest.kt` confirms rendering works), but there is zero status feedback during the insertion attempt itself.
2. **Consistency and standards — VIOLATION.** Table *rendering* is fully supported by `MarkdownEngine.kt` (confirmed via `TableBlockScreenshotTest.kt`) but table *insertion* has no matching editor-side affordance — the renderer and the editor are inconsistent in what they support, an identical asymmetry pattern to `insert-code-block.md`'s CODE vs. CODE_BLOCK gap.
3. **Discoverability — VIOLATION.** "Logseq upstream (`/table`), Notion (`/table`) both offer a table-insert trigger; SteleKit has none" — explicitly one of "the two clearest 'must hand-type syntax with zero assistance' gaps identified by research."
4. **Minimal memory load — VIOLATION (most severe of the 7 journeys).** "The only working path: user hand-types the full markdown table syntax (header row, `|---|---|` separator, data rows) as literal text, with zero editor assistance" — this demands recalling a multi-row structural syntax from memory, more than any single-marker syntax (e.g. `TODO `, `` ``` ``), with the same total absence of in-editor recognition aid.
