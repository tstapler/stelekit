---
journey_id: insert-tag
platforms: [desktop, android, ios, web]
jtbd_tier: functional
step_count_target:
  typed_path: "≤(query length + 1) keystrokes — type `#` + query, select from autocomplete"
  button_path: "≤2 taps (flagship redesign target, design/ux.md criterion 1) — not yet met by current implementation"
current_step_count:
  desktop: "Typed `#` path only (no MobileBlockToolbar 'Suggest tags' interaction target on a keyboard-first surface, though the button renders — see note) — meets target (query length + 1 keystrokes)."
  android: "Typed path meets target. Button path: 4-5 discrete steps (tap 🏷 → wait for ModalBottomSheet slide animation → wait for LLM spinner → read chips → tap chip) — target NOT met."
  ios: "Same as Android — button path 4-5 discrete steps, target NOT met."
  web: "Typed `#` path only; button renders (no platform gating found around `EditorToolbar` call sites) but touch-oriented `SuggestionBottomSheet`/`ModalBottomSheet` on a non-touch surface is untested — flagged, not confirmed broken."
post_fix_step_count:
  android: "4 discrete steps (tap 🏷 → chips appear (local matches, no separate spinner frame) → read chips → tap chip), down from 4-5 — GAP-003 fix (Story D.1.1)."
  ios: "Same mechanism as Android — 4 steps, down from 4-5."
  mechanism: |
    Two changes to `TagSuggestionViewModel.requestSuggestions`/`SuggestionBottomSheet.kt` (both in
    `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`):
    1. `TagSuggestionViewModel.requestSuggestions` previously set `state = Loading` synchronously
       before dispatching its coroutine, forcing every tap through a distinct spinner frame even
       though Tier-1 local matches (`TagSuggestionEngine.directMatch`, an `AhoCorasickMatcher` exact
       lookup) need no network round-trip — only the Tier-2 LLM call genuinely needs to wait. The
       `Loading` emission was removed; the first state emitted is now `Ready` (with local
       suggestions already populated), computed off-main-thread on `Dispatchers.Default` for
       large-graph safety exactly as before. The LLM tier continues to enrich the same `Ready`
       state asynchronously via `llmPending`/`llmSuggestions`, unchanged.
    2. `SuggestionBottomSheet`'s `rememberModalBottomSheetState()` now passes
       `skipPartialExpanded = true` so the sheet reaches its final position in one continuous
       motion instead of settling at partial height first.
    This does not yet implement design/ux.md (d)'s longer-term "fully inline, no modal round-trip"
    redesign (2-step target) — that remains a larger follow-up; this pass removes the one
    concretely avoidable step (the artificial Loading frame) within this project's appetite.
heuristic_findings: |
  Discoverability violation: the faster typed `#` autocomplete path (BlockEditor.kt:28,129) has no in-app hint that `#` triggers anything, while the only visibly discoverable affordance (the 🏷 toolbar button, MobileBlockToolbar.kt:211) is the slower 4-5 step button path (see Heuristic findings section).
test_ids: []
status: audited
last_verified: 2026-07-06
---

# Insert tag

## Trigger
User wants to add a `#tag` to a block, either by typing the tag name directly or by asking the app to suggest one.

## Current step sequence — typed path (all platforms, unchanged, already fast)

1. User types `#` followed by query characters inside a block being edited.
2. `HASHTAG_AUTOCOMPLETE_REGEX = Regex("#([^\\s#\\[\\](),!?;.\"']*)$")` (`BlockEditor.kt:28`) matches on every `onValueChange`. The hashtag regex is only evaluated `if (wikiMatch == null)` (`BlockEditor.kt:129`) — i.e. `[[` always wins over `#` when both could match at the same cursor position.
3. Matching page/tag results stream into a dropdown; arrow keys navigate (`BlockEditor.kt:250-257`, wrap-around cycling via modulo).
4. Enter or tap applies the selection via `applyAutocompleteSelection` (`BlockEditor.kt:587-602`): replaces from `#` through the cursor with `"#[[$pageName]]"` if the page name contains a space, else `"#$pageName"` (line 594).
5. Total: query-length keystrokes + 1 selection action — already at the ≤(query length + 1) target across all 4 platforms. No desktop/mobile difference in this path (`detectSoftKeyboardBracketWrap` only special-cases `[[`, not `#`).

## Current step sequence — button path ("Suggest tags" 🏷, mobile toolbar)

1. User taps the 🏷 "Suggest tags" `IconButton` (`MobileBlockToolbar.kt:211`, `contentDescription = "Suggest tags"`), rendered conditionally in the toolbar's primary row (only when the `onSuggestTags` lambda is non-null).
2. `EditorToolbar.kt`'s `onSuggestTags` lambda (lines 81-94) resolves the currently-editing block by reading `blockStateManager.blocks.value.values.flatten().find { it.uuid == targetUuid }` **at click time** — this closure does NOT capture a `collectAsState()`-derived `allBlocks` from composable-body scope. An inline comment (`EditorToolbar.kt:86-88`) states this is a deliberate fix, and `PageView.kt:106` references it as already-applied "Task B.1.1b." **Finding: the Phase B `onSuggestTags` recomposition-closure bug this plan's Phase B was written to fix appears to already be fixed in the current codebase at both call sites (`onSuggestTags` and `onLinkPicker`)** — flagged for whoever starts Phase B to re-verify against `EditorToolbarRecompositionTest.kt` rather than assume the bug is still live.
3. `PageView.kt:489-498` calls `tagSuggestionViewModel.requestSuggestions(...)` when block content is non-blank; this opens `SuggestionBottomSheet` as a `ModalBottomSheet` (`SuggestionBottomSheet.kt:39`), visible while `TagSuggestionState` is `Loading` or `Ready` (line 34).
4. `Loading` state shows a `CircularProgressIndicator` (`SuggestionBottomSheet.kt:66-74`) — user waits for the LLM.
5. `Ready` state renders `TagChipRow` with combined local+LLM suggestions plus an `llmPending`/`llmError` sub-state (lines 76-89).
6. User taps a chip; sheet auto-dismisses, tag inserted.
7. **Step count**: tap toolbar button → wait for sheet slide-in animation (~250ms) → wait for LLM spinner → read chips → tap chip = 4-5 discrete user-perceptible steps, matching design/ux.md's "Before" baseline exactly. This is the flagship gap design/ux.md (d) is written to close (target: 2 steps via an inline anchored `TagChipRow`, no modal round-trip).

## Notes
- No platform gating was found wrapping the `EditorToolbar`/`MobileBlockToolbar` call sites in `JournalsView.kt:245` or `PageView.kt:484` — despite the "Mobile" name, the toolbar (and its "Suggest tags" button) appears not to be excluded from Desktop/Web composition. This should be confirmed empirically during Phase D rather than assumed either way.
- Prior-art cross-check: `docs/ux/journey-map.md` (commit `b3de1ec7dc`, branch `stelekit-editing`, not merged into this branch — see `docs/journeys/README.md` changelog) documents the same "Suggest tags" mechanism only in passing (its journey map predates the dedicated `tag-suggestion-trigger` project that built `SuggestionBottomSheet`) — no conflicting claims found.

## Heuristic findings

1. **Visibility of system status — PASS.** The button path keeps the user informed at every step: `SuggestionBottomSheet.kt:66-74` renders a `CircularProgressIndicator` while `TagSuggestionState.Loading`, and the `Ready` state's `llmPending`/`llmError` sub-states (`SuggestionBottomSheet.kt:76-89`) continue signaling in-progress/failed LLM enrichment rather than leaving the tap as a silent no-op.
2. **Consistency and standards — PASS.** The recomposition-closure fix at `EditorToolbar.kt:86-88` was applied "at both call sites" (`onSuggestTags` and `onLinkPicker`), and the toolbar button itself follows the same construction convention used elsewhere in `MobileBlockToolbar.kt` — an `IconButton` with a `contentDescription`, rendered conditionally only when its callback lambda is non-null (compare `MobileBlockToolbar.kt:211` "Suggest tags" to `MobileBlockToolbar.kt:201` "Insert wiki link" in insert-link.md).
3. **Discoverability — VIOLATION.** The typed `#` path (steps 1-5) surfaces no hint, tooltip, or onboarding cue that `#` triggers autocomplete — the documented mechanism is purely regex-driven (`HASHTAG_AUTOCOMPLETE_REGEX`, `BlockEditor.kt:28`) with nothing in the step sequence signaling its existence to a first-time user. The only visibly discoverable entry point is the 🏷 button (`MobileBlockToolbar.kt:211`), which is also the documented 4-5 step slow path — the fast mechanism is the undiscoverable one.
4. **Minimal memory load — PASS.** The typed `#` trigger requires no SteleKit-specific syntax to recall — it mirrors the cross-app hashtag convention (Twitter/Slack/etc.) users already carry into the app, meeting Nielsen's recognition-over-recall bar despite being a typed rather than pointed interaction.
