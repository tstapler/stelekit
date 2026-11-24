# Mobile Gutter Robustness — Architecture Research

## 1. Component hierarchy

```
ViewModel (StelekitViewModel / LogseqViewModel)
  └─ Screen (PageView / JournalsView)
       └─ BlockList            — renders all blocks; owns drag state, lasso state, drop-target logic
            └─ BlockRenderer   — thin public wrapper; passes all callbacks straight through to BlockItem
                 └─ BlockItem  — orchestrator for one block row: manages autocomplete, suggestion,
                 |               context-menu, and editor state; calls both BlockGutter and the content composable
                      └─ BlockGutter   — left gutter: drag handle, collapse caret, bullet, debug label
```

`BlockRenderer` exists solely to keep a stable public API for `BlockList`; it is a one-to-one delegation to `BlockItem` with no additional logic.

## 2. Touch target violations (confirmed in source)

| Element | Current size | Violation |
|---|---|---|
| Drag handle | 48dp outer Box wrapping 18dp Icon | Already correct — the fix landed earlier |
| Collapse caret | 18dp `Icon` with `.clickable {}` directly on it | 18dp < 48dp mobile minimum |
| Bullet dot | 6dp `Box` | 6dp < 24dp minimum; comment at line 176 explicitly says "deliberately reuses the existing 6dp hit area rather than enlarging it" (rationale: desktop mouse, Roborazzi golden risk) |

The collapse caret fix is unambiguous: replace the bare `.clickable` on the 18dp `Icon` with the same "small glyph, large hit area" `Box` pattern the drag handle already uses (see `BlockGutter.kt` lines 99–151).

The bullet requires a different approach: the existing comment argues against growing its hit box for desktop. The correct fix is a mobile-only expanded tap target (`Modifier.size(24.dp)` for the outer hit area) gated by `LocalWindowSizeClass.current.isMobile`, leaving the 6dp visual circle unchanged and the desktop layout untouched.

## 3. Quick-action callbacks — what is and is not plumbed

### Already in BlockGutter's signature
- `onMoveUp: () -> Unit` (line 61)
- `onMoveDown: () -> Unit` (line 62)

**Critical finding: both are dead params.** `BlockGutter`'s body never calls either one. They were accepted but never wired to any gesture or tap target. They exist in the signature but do nothing.

### Available in BlockItem, NOT forwarded to BlockGutter
- `onIndent: () -> Unit` — forwarded only to `BlockEditor` (edit mode)
- `onOutdent: () -> Unit` — forwarded only to `BlockEditor` (edit mode)

### Completely absent from the entire chain
- `onCopyBlockRef` — does not exist in `BlockGutter`, `BlockItem`, `BlockRenderer`, or `BlockList`
- `onDeleteBlock` — does not exist anywhere in this chain

### What needs to be added
To wire the quick-action sheet:
1. Add `onIndent`, `onOutdent`, `onCopyBlockRef`, `onDeleteBlock` to `BlockGutter`'s signature.
2. Forward `onIndent` and `onOutdent` from `BlockItem` → `BlockGutter` (they're already plumbed into `BlockItem`).
3. Add `onCopyBlockRef` and `onDeleteBlock` to `BlockItem` → `BlockRenderer` → `BlockList` → screen → ViewModel. These are new callbacks all the way up the stack.
4. Wire `onMoveUp` and `onMoveDown` in `BlockGutter`'s body (currently dead).

## 4. Data flow from ViewModel to BlockGutter

```
StelekitViewModel.onMoveUp(blockUuid)
  └─ PageView / JournalsView  { onMoveUp = { uuid -> vm.onMoveUp(uuid) } }
       └─ BlockList(onMoveUp = ...)
            └─ BlockRenderer(onMoveUp = { onMoveUp(block.uuid.value) })   [line 454 in BlockList.kt]
                 └─ BlockItem(onMoveUp = ...)
                      └─ BlockGutter(onMoveUp = onMoveUp)  — but BlockGutter never calls it
```

The pattern is consistent: all structural callbacks (`onIndent`, `onOutdent`, `onMoveUp`, `onMoveDown`) are lambda-threaded from `BlockList` through `BlockRenderer` through `BlockItem`. Adding new ones follows the same chain: add the param at each layer, thread it down.

`onCopyBlockRef` and `onDeleteBlock` will need ViewModel methods as well. `onCopyBlockRef` likely just writes the block UUID to the clipboard (no ViewModel state change). `onDeleteBlock` already exists in the ViewModel (there is `onDeleteBlock` / structural edit logic) — needs confirmation but is likely present.

## 5. Quick-action sheet state placement

**Recommendation: local to `BlockItem`.**

Rationale:
- `BlockItem` already owns all other ephemeral per-block popup state: `suggestionState`, `contextMenuState` (see lines 181–183). The quick-action sheet is the same kind of state — it opens for one block at a time, lives only while the row is composed, and has no cross-block meaning.
- Lifting to `BlockList` would require a new `var quickActionBlockUuid: String?` there, which forces `BlockList` to recompose when any block's menu opens/closes — that's unnecessary overhead and couples an ephemeral UI detail into a component that already has complex drag/selection state.
- Lifting to the ViewModel adds server-side lifecycle to an ephemeral UI concern.
- **Implementation pattern**: add `var quickActionMenuOpen by remember { mutableStateOf(false) }` inside `BlockItem`, controlled by a new tap handler in `BlockGutter` (mobile-only, view mode only). Render the bottom sheet or dropdown inside `BlockItem`'s outer `Box` (same layer as the existing `SuggestionContextMenu` and `AutocompleteMenu`).

## 6. Haptic feedback

`BlockItem` already imports and uses `LocalHapticFeedback` (line 263) for `HapticFeedbackType.LongPress` on selection-mode entry. The drag-start haptic should be added in the `onDragStart` lambda inside `BlockItem` (line 478 of `BlockList.kt` wires this — but the callback crosses from `BlockList` → `BlockItem` → `BlockGutter` → back up via the lambda). The cleanest point is inside `BlockItem`, in the `onDragStart` override it passes down to `BlockGutter`: wrap it with `haptic.performHapticFeedback(HapticFeedbackType.LongPress)` before forwarding. No new API needed.

## 7. Tech debt disposition

`BlockGutter.kt` (207 lines) is clean and well-separated — single responsibility, no leaked state. The dead `onMoveUp`/`onMoveDown` params are minor cruft.

`BlockItem.kt` (673 lines) is a moderate God Object: it owns autocomplete fetch, suggestion popup, context menu, haptic feedback, focus management, format event subscription, and block-ref resolution all in one composable. It is not clean, but it is stable and well-understood. A refactor-first approach would risk destabilizing the Roborazzi goldens and the complex `LaunchedEffect` chain.

**Recommendation: extend-as-is for this feature.** The quick-action sheet state fits the existing `BlockItem` popup pattern exactly. The touch-target fixes are surgical Modifier changes. No architectural seam extraction is needed to deliver the feature safely.

## Files to modify

| File | Change |
|---|---|
| `BlockGutter.kt` | Add `onIndent`, `onOutdent`, `onCopyBlockRef`, `onDeleteBlock` params; wire `onMoveUp`/`onMoveDown` (currently dead); fix collapse caret hit area; fix bullet mobile hit area; add mobile tap handler on drag handle for quick-action menu; add haptic on drag start |
| `BlockItem.kt` | Add `onCopyBlockRef`, `onDeleteBlock` params; add `quickActionMenuOpen` state; render quick-action sheet; pass new callbacks to `BlockGutter`; add haptic in drag-start wrapper |
| `BlockRenderer.kt` | Thread `onCopyBlockRef`, `onDeleteBlock` through (pure delegation) |
| `BlockList.kt` | Thread `onCopyBlockRef`, `onDeleteBlock` through |
| Screen(s) (PageView, JournalsView) | Wire `onCopyBlockRef`, `onDeleteBlock` to ViewModel |
| ViewModel | Add `onCopyBlockRef(blockUuid)`, `onDeleteBlock(blockUuid)` if not present |
