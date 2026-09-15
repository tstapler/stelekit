# Requirements: tag-suggestion-trigger

**Date**: 2026-06-22
**Type**: bug fix
**Complexity**: 1 — quick task

## Problem Statement

The tag auto-detection and suggestion feature (TagSuggestionEngine + TagSuggestionViewModel + SuggestionBottomSheet) is fully wired through the data and ViewModel layers but has no UI trigger when a user is typing into or has focus in a block on the Journals page. There is no button, gesture, or menu item that calls `onRequestTagSuggestions` from the editing context.

Root cause: `BlockItem.kt` has a dead `DropdownMenu` (lines 499–516) for "Suggest tags" whose `tagMenuExpanded` state variable starts `false` and is never set to `true` — no anchor button exists to open it.

## Baseline

Users cannot invoke tag suggestions at all while editing blocks on the Journals screen. On PageView, the only trigger is "Suggest tags for page" buried in the export overflow menu — not per-block, and not accessible during editing. The per-block `onRequestTagSuggestions` callback chain exists in code but has no surface.

## Users / Consumers

Note-takers using the Journals screen who want to detect and link wiki-page references while drafting or reviewing entries.

## Success Metrics

- A "Suggest tags" icon button is visible in the `MobileBlockToolbar` whenever a block is being edited and `tagSuggestionViewModel` is non-null.
- Tapping the button triggers `tagSuggestionViewModel.requestSuggestions()` for the editing block.
- The `SuggestionBottomSheet` appears with local + LLM suggestions.
- Works on both JournalsView and PageView screens (consistent placement).

## Appetite

Small (< 1 day)

## Constraints

- No new dependencies.
- Must not break existing toolbar behavior (indent, format, link picker, image attach).
- `EditorToolbar` must stay decoupled from `TagSuggestionViewModel` (use a callback instead).

## Non-functional Requirements

- **Performance SLO**: toolbar must not re-compose on every keystroke — the callback is stable
- **Scalability**: not applicable
- **Security classification**: internal
- **Data residency**: no special requirements

## Scope

### In Scope

- Add `onSuggestTags: (() -> Unit)?` parameter to `MobileBlockToolbar`
- Add `onSuggestTags: ((blockUuid: String, content: String) -> Unit)?` to `EditorToolbar`; derive block content from `blockStateManager` and forward to `MobileBlockToolbar`
- Wire callback in `JournalsView` and `PageView`
- Remove dead `DropdownMenu` code in `BlockItem.kt` (superseded by toolbar button)

### Out of Scope

- Changing the suggestion algorithm or LLM providers
- Auto-triggering suggestions without user action
- Adding suggestions to the multi-select toolbar
- Redesigning the `SuggestionBottomSheet` UI

## Rabbit Holes

- `blockStateManager.blocks` returns `Map<String, List<Block>>` keyed by pageUuid — finding the editing block requires a flatten+find O(n). Acceptable for the typical block count; do not optimize further.
- `EditorToolbar` must not hold a reference to `TagSuggestionViewModel` to avoid coupling.

## Alternatives Considered

1. **Fix `BlockItem` with an anchor button for the existing DropdownMenu** — simpler but the dropdown only has one item, which is awkward UX.
2. **Add to the right-click `SuggestionContextMenu`** — only visible when an existing link suggestion is detected; misses the "no existing suggestions" case.
3. **Toolbar button (chosen)** — consistent with existing editing tools, always visible while editing.

## Feasibility Risks

None — all plumbing exists, this is purely a UI wiring task.

## Open Questions

None.
