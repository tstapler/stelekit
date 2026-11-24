# ADR-001: Quick-Action Sheet State Lives in BlockItem (Not BlockList or ViewModel)

**Date**: 2026-11-23  
**Status**: Accepted  
**Deciders**: Implementation planning pass

---

## Context

The quick-action sheet (opened by tapping the drag handle in view mode on mobile) needs to track an `isOpen: Boolean` state. There are three possible homes:

1. **Local to `BlockItem`** — a `var showQuickActionSheet by remember { mutableStateOf(false) }` declared inside `BlockItem`'s body.
2. **Lifted to `BlockList`** — a `Map<String, Boolean>` or single `String?` tracking which block UUID has its sheet open, stored in `BlockList`.
3. **Lifted to ViewModel** — a `StateFlow<String?>` on `StelekitViewModel` tracking the UUID of the block whose sheet is open.

## Decision

State is **local to `BlockItem`** (Option 1).

## Rationale

**Against Option 2 (BlockList)**: `BlockList` renders every block in a `LazyColumn`. If a single `String?` state lives in `BlockList`, it is a shared value at the `BlockList` scope level, which means any state change (`showSheet = true` or `= false`) triggers recomposition of the entire `BlockList` composable body, not just the affected block row. For large pages (hundreds of blocks), this is unnecessary work. The existing `suggestionState` and `contextMenuState` in `BlockItem` demonstrate the codebase's established pattern for block-local overlay state.

**Against Option 3 (ViewModel)**: The sheet is entirely a UI concern — it has no interaction with the domain layer, no persistence, and no cross-block coordination. Lifting it to the ViewModel adds unnecessary state to a class that is already large (the existing God Object problem in the ViewModel). It would also require UI-layer round trips through `StateFlow` for a purely local toggle, adding latency for no benefit.

**For Option 1 (BlockItem)**: Matches the existing `suggestionState` / `contextMenuState` pattern exactly. The sheet open/close is local to the block row; when the user scrolls the row out of view, the `remember` scope is cleared automatically. The two dismiss guards (`LaunchedEffect(isDragging)` and `LaunchedEffect(isInSelectionMode)`) are already in `BlockItem`'s scope and can observe the needed state directly.

## Consequences

- Each `BlockItem` independently tracks whether its sheet is open.
- At most one sheet can be open at a time in practice (a tap opens one sheet; navigating or scrolling away dismisses it via `remember` scope teardown).
- If a future requirement demands "close all open sheets when X happens" at the `BlockList` level, the state location would need to be revisited. That is not a current requirement.
