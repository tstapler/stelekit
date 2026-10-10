---
journey_id: insert-link
platforms: [desktop, android, ios, web]
jtbd_tier: functional
step_count_target: "≤(query length + 1) keystrokes; no modal, no page navigation away from the block (design/ux.md criterion 3)"
current_step_count:
  desktop: "Meets target — typed `[[` path, no modal."
  android: "Meets target for typed path. Mobile toolbar also has a direct '[[ ]] Insert wiki link' button (1 tap) that pre-fills `[[` and opens the same autocomplete — effectively 1 tap + query-length keystrokes + 1 selection."
  ios: "Same as Android."
  web: "Meets target — typed `[[` path, no modal."
heuristic_findings: |
  Discoverability violation: neither the typed `[[`/`#` triggers nor the selection-wrap `[` shortcut (BlockEditor.kt:332-346) have any visible hint or affordance — reused prior-art finding calls this "pure tribal knowledge" (see Heuristic findings section).
test_ids: []
status: audited
last_verified: 2026-07-05
---

# Insert link

## Trigger
User wants to link to another page, either by typing `[[` directly, by tapping the mobile toolbar's wiki-link button, or by selecting existing text and wrapping it.

## Current step sequence — typed `[[` path (all platforms)

1. User types `[[` followed by query characters. `WIKI_LINK_AUTOCOMPLETE_REGEX = Regex("\\[\\[([^\\]]*)$")` (`BlockEditor.kt:27`) matches on `onValueChange`, checked *before* the hashtag regex (`BlockEditor.kt:126-135`) — `[[` always takes priority if both could match.
2. Search runs against existing pages; results stream into a dropdown positioned at the live cursor rect.
3. Arrow keys navigate (`BlockEditor.kt:250-257`); Tab enters a filter-refinement mode via `onEnterFilterMode()` (`BlockEditor.kt:289-292`, delegates out — no in-file refine logic); Escape closes the popup via `onAutocompleteStateChange(null)` (`BlockEditor.kt:293-296`) with nothing applied.
4. Enter applies the item at `selectedIndex` (`BlockEditor.kt:276-288`) via `applyAutocompleteSelection` (lines 603-619): replaces the query with `"[[$pageName]]"`; if text immediately after the cursor already starts with `]]`, those two characters are stripped first (line 609) to avoid doubled brackets.
5. **Ctrl/Cmd+Enter — "create new page"**: filters `searchResults` for `SearchResultItem.CreatePageItem` and applies that specific index (`BlockEditor.kt:258-275`), only reachable `if (autocompleteState != null && searchResults.isNotEmpty())` (line 247) — i.e. this binding is scoped to an open autocomplete popup, not a global shortcut. (This scoping is exactly what Phase C.1's TODO-toggle collision-avoidance logic must not disturb.)
6. Total: query-length keystrokes + 1 selection = at or under the ≤(query length + 1) target. No modal appears at any point in this path; the block never navigates away.

## Current step sequence — mobile toolbar "[[ ]]" button

1. User taps the `[[ ]] ` `IconButton` (`MobileBlockToolbar.kt:201`, `contentDescription = "Insert wiki link"`) in the toolbar's primary row.
2. Inserts `[[` at the cursor, which immediately re-triggers the same typed-`[[` autocomplete flow above (steps 2-4).
3. Total: 1 tap + query-length keystrokes + 1 selection — a small discoverable head-start over hand-typing `[[`, no separate mechanism or modal.

## Current step sequence — selection-wrap linking

1. User selects existing text, then presses `[` on a hardware keyboard.
2. `BlockEditor.kt:332-346` wraps the selection in `[[`/`]]` and moves the cursor by +2 (past the opening brackets), landing the cursor ready to type/select the target page name inline.
3. This is a hardware-keyboard-only path today — no equivalent mobile-toolbar gesture was found for "select text, then wrap in `[[ ]]`" as a single action (the `[[ ]]` toolbar button inserts fresh brackets at the cursor; it does not wrap an existing selection).

## Notes
- Prior-art cross-check (`docs/ux/journey-map.md`, commit `b3de1ec7dc`, not merged — see README changelog): its "Wiki-link and hashtag autocomplete" journey independently documents the same `getCursorRect` popup-positioning mechanism and flags a gap this audit reconfirms structurally: *"`getCursorRect` is wrapped in try/catch that silently no-ops the whole popup on any exception — fails with zero explanation"* and *"no visible hint that `[[` or `#` trigger anything — pure tribal knowledge."* These are reused as heuristic-review input, not rediscovered from scratch.
- No `((` block-reference autocomplete trigger exists (confirmed absent per features.md §2/§4) — per plan.md's Unresolved Questions, this is filed as a `needs-data-model-answer`-tagged backlog row (see `gap-backlog.md`) rather than silently dropped, since it blocks nothing in this plan directly but should not be lost either.

## Heuristic findings

1. **Visibility of system status — VIOLATION.** The reused prior-art finding quoted in Notes is concrete: *"`getCursorRect` is wrapped in try/catch that silently no-ops the whole popup on any exception — fails with zero explanation"* — the user's typed `[[` registers no visible autocomplete and no error, a textbook silent-failure violation. By contrast, Escape closing the popup via `onAutocompleteStateChange(null)` (`BlockEditor.kt:293-296`) with nothing applied at least gives visible feedback (the popup disappears) that the escape action registered.
2. **Consistency and standards — PASS.** The `[[ ]]` toolbar button follows the same `IconButton` + `contentDescription` + conditional-lambda construction convention used across the toolbar (`MobileBlockToolbar.kt:201`, compare `MobileBlockToolbar.kt:211` "Suggest tags" in insert-tag.md and `MobileBlockToolbar.kt:216-231` in insert-image.md) — consistent internal pattern for all toolbar-inserted mechanisms.
3. **Discoverability — VIOLATION.** Reused prior-art quote: *"no visible hint that `[[` or `#` trigger anything — pure tribal knowledge."* The selection-wrap `[` shortcut compounds this: it is "a hardware-keyboard-only path today — no equivalent mobile-toolbar gesture was found," so on touch platforms the capability has zero surfaced affordance at all.
4. **Minimal memory load — VIOLATION.** The selection-wrap-then-`[` mechanism (`BlockEditor.kt:332-346`) requires the user to recall an arbitrary, unlabeled key binding with no on-screen indication anywhere in the documented UI — pure recall, not recognition — whereas the toolbar button path (1 tap, `MobileBlockToolbar.kt:201`) meets the bar since the option is visible when needed.
