# ADR-004: Suggestion Chip Tray UX

**Date**: 2026-04-17
**Status**: Accepted

## Context

The review stage in `ImportScreen` currently shows two sections: a `LazyRow` of matched existing-page chips, and a scrollable text preview of the linked content. The new "Suggested new pages" section must integrate into this layout without disrupting the existing review flow or blocking the Confirm button.

Key UX constraints from research:
- 5–15 suggestions is the cognitive sweet spot; above 15 users disengage.
- Confidence indicators accelerate per-item decisions vs. ranked order alone.
- Low visual weight (chip dismiss, not modal) reduces dismissal friction (Tana's model).
- The suggestion list must never clear and re-render when Claude results arrive — it must merge incrementally.

## Decision

**Placement**: A new "Suggested new pages" section is inserted in `ReviewStage` between the matched-pages row and the text preview. The Confirm button remains in the existing `bottomBar` `Scaffold` slot — it is not moved.

**Chip anatomy**: Each chip renders as `[confidence dot] [term] [×]`. The confidence dot is color-coded: green (score >= 0.7), yellow (0.4–0.69), orange (0.2–0.39). Terms below 0.2 confidence are suppressed before reaching the UI. When a chip is accepted, it transitions to a "linked" visual state (checkmark icon, muted/outlined style) and remains in the tray as a record of accepted items.

**Cap**: Maximum 15 suggestions are shown. The top 8 are visible by default; a "Show N more" text button reveals the remainder. Items are sorted by confidence descending.

**Section header**: `"Suggested new pages (N)"` label on the left with an `"Accept All"` button on the right. The header also carries the Claude status badge: `"AI-enhanced"` (green tint) when enrichment has completed, `"AI enhancing..."` with a small indeterminate indicator while the Claude call is in flight, or `"AI unavailable"` (muted, no icon) if the Claude call failed.

**Accept All flow**: Tapping "Accept All" opens a confirmation dialog: `"Create N stub pages?"` with OK and Cancel buttons. This confirmation step is required because stub page creation is permanent (persisted to disk).

**Dismiss behavior**: Tapping `×` on a chip sets `dismissed = true` in state and removes the chip from the visible tray. Dismissed chips are never re-shown, even if the Claude enrichment arrives and assigns them high confidence. This is by design — user dismissal is authoritative.

**Confidence threshold**: 0.2 minimum before a suggestion is emitted to the UI. This threshold applies after scoring; items below it are silently discarded in `TopicExtractor` and are not passed to `ImportState`.

## Rationale

**Between matched-pages row and text preview** is the natural placement because: the user has just come from the input stage and will read top-to-bottom; matched pages provide the context for "what already exists," and new suggestions logically follow as "what else might deserve a page." Placing the tray above the text preview (rather than below) ensures it is visible without scrolling on typical screen sizes.

**15-item hard cap** reflects the research synthesis: a 10 KB technical article can produce 50+ raw heuristic candidates. A hard cap with a "Show more" affordance keeps the default view manageable while allowing power users to see lower-ranked candidates.

**Accepted chips remain visible** rather than disappearing because the user needs to see the full record of what will be created before clicking "Confirm import." Disappearing accepted chips would make it impossible to review the pending set without relying on memory.

**Incremental merge on Claude arrival** — new Claude-only suggestions are appended to the end of the visible list (not inserted by rank) and receive the `AI_ENHANCED` source badge. Confidence scores for items already shown may be promoted, causing them to re-sort. This is the minimum-surprise update: the user never sees items they are hovering over disappear or jump.

**No color for accepted state beyond muted/outlined** keeps the chip tray scannable. The green/yellow/orange dot already carries the confidence signal; adding more color to the accepted state would create visual noise.

## Consequences

- `ImportScreen` gains a `TopicSuggestionTray` composable (a private `@Composable` function in `ImportScreen.kt` or a separate file in `ui/components/`).
- `ImportState` must carry the fields needed to drive the tray: `topicSuggestions`, `claudeStatus`, and `isEnhancing`.
- `ImportViewModel` must expose `onSuggestionAccepted(term: String)` and `onSuggestionDismissed(term: String)` functions callable from the composable.
- `ImportViewModel` must expose `onAcceptAllSuggestions()` callable from the composable's confirmation dialog callback.
- The `ReviewStage` composable signature gains `onSuggestionAccepted`, `onSuggestionDismissed`, and `onAcceptAllSuggestions` parameters. These are passed down from `ImportScreen`.
- On mobile, the `LazyRow` in the tray wraps chips horizontally. On narrow screens the tray may be tall; this is acceptable given the vertical scroll container wrapping the entire review stage.

## Patterns Applied

- Progressive disclosure (8 expanded, N more collapsed)
- Progressive enhancement (local results first; Claude result arrives asynchronously and merges non-destructively)
- Tana-inspired low visual weight (per-chip dismiss via `×`, no modal interruption per item)
- Confirmed destructive action (Accept All requires a dialog; single-item Accept does not, since undo is available)
