# Build-vs-Buy: "Suggest Tags" Button in MobileBlockToolbar

## Task

Wire a "Suggest tags" button in `MobileBlockToolbar` so users can trigger block-scoped tag suggestions while editing on mobile.

---

## 1. Existing Toolbar Button Pattern (Prior Art)

### How link picker was added

The link picker and image attach are the canonical prior art examples. The pattern is identical for both and has three layers:

**Layer 1 — `MobileBlockToolbar.kt`**: accepts nullable lambda parameters (`onLinkPicker: (() -> Unit)?`, `onAttachImage: (() -> Unit)?`). When non-null, the button renders in the primary actions row inside the `editingBlockId != null` guard. `onAttachImage` uses a conditional `if (onAttachImage != null) { IconButton(...) }` block. The link picker button is always rendered but its click handler branches: `if (onLinkPicker != null) onLinkPicker() else onFormat(FormatAction.LINK)`.

**Layer 2 — `EditorToolbar.kt`**: the single wiring site for both `PageView` and `JournalsView`. It collects state from `BlockStateManager`, constructs the lambda (capturing cursor position, selection range, block UUID), and passes it to `MobileBlockToolbar`. `onAttachImage` is built from `capabilities.onAttachImage` plus the current `editingBlockUuid` — it's null-guarded at construction time so it can only fire when a block is focused.

**Layer 3 — `EditorCapabilities.kt`**: for platform-specific callbacks (attach image, file drop, paste image, camera capture), a data class bundles them. This ensures screens that host the editor receive the full capability set atomically — forgetting one is a compile error.

### Key constraint: toolbar must not steal focus

All toolbar buttons are inside a `Surface` with `Modifier.focusProperties { canFocus = false }`. This is architecturally significant — the toolbar fires a callback into a stateful parent rather than managing state itself.

---

## 2. Existing Tag Suggestion Infrastructure

The suggestion backend is already complete:

- `TagSuggestionViewModel` (`tags/TagSuggestionViewModel.kt`): owns a `CoroutineScope`, exposes `StateFlow<TagSuggestionState>`, handles `requestSuggestions(blockUuid, blockContent, alreadyLinkedTerms)` and `dismiss()`.
- `TagSuggestionState` (`tags/TagSuggestionState.kt`): `Idle | Loading | Ready | Error` sealed interface.
- `SuggestionBottomSheet`: already rendered in both `PageView` and `JournalsView`, wired to `tagSuggestionViewModel`.

The `onRequestTagSuggestions: ((blockUuid: String, content: String) -> Unit)?` callback is already threaded through `BlockList → BlockRenderer → BlockItem`. In `BlockItem.kt` (lines 499–516), when this callback is non-null, a `DropdownMenu` with a "Suggest tags" item is rendered — but its trigger (`tagMenuExpanded`) is never set to `true` from anywhere accessible on mobile. This is the gap.

### Gap: the dropdown trigger is never opened

The `DropdownMenu` in `BlockItem.kt` requires `tagMenuExpanded = true` to show. On desktop there is likely a context menu entry point; on mobile the toolbar button is the natural trigger. Currently `tagMenuExpanded` is local state inside `BlockItem` with no external entry point.

---

## 3. Should We Use a Library?

No. This is pure Compose UI wiring. The suggestion engine, bottom sheet, and state machine already exist. The only work is adding an `IconButton` to `MobileBlockToolbar` and wiring it through `EditorToolbar` to `TagSuggestionViewModel.requestSuggestions(...)`.

---

## 4. Where Should the Trigger Live?

Three options evaluated:

### Option A — MobileBlockToolbar (recommended)

**Fits the established pattern exactly.** Add `onSuggestTags: (() -> Unit)? = null` to `MobileBlockToolbar`, render an `IconButton` (e.g. `Icons.Default.Label` or `Icons.Default.Tag`) in the primary actions row alongside the link picker and attach-image buttons, guarded by `editingBlockId != null`. Wire it in `EditorToolbar.kt` the same way `onLinkPicker` is wired — capture `editingBlockUuid` and block content at click time, call `tagSuggestionViewModel.requestSuggestions(...)`.

Pros:
- Consistent with existing toolbar button model (link picker, image attach)
- Single wiring site (`EditorToolbar.kt`) covers both `PageView` and `JournalsView`
- Naturally scoped to the editing block (block UUID is captured at click time)
- Zero new abstractions; no new files needed

Cons:
- Adds one more button to an already-busy primary actions row (mitigated by hiding when `editingBlockId == null`)

### Option B — BlockItem/BlockEditor inline button

Would mean surfacing the existing `DropdownMenu` in `BlockItem` via some external trigger, or adding a button inside the block editing row itself.

Cons:
- Each block would need individual state; no single wiring point
- Conflicts with the established principle that editing state lives in `BlockStateManager`, not per-block composables
- The existing `tagMenuExpanded` in `BlockItem` is an orphaned dead-end for mobile
- Much more invasive prop-drilling through `BlockList → BlockRenderer → BlockItem`

### Option C — Floating Action Button

Pros: discoverable, no toolbar crowding.

Cons: Requires a new layout layer in both `PageView` and `JournalsView`; two wiring sites instead of one; clashes with the existing `SuggestionNavigatorPanel` and `SuggestionBottomSheet` positioning.

---

## 5. Recommendation: Build via Option A

**Pure build, no buy.** Add `onSuggestTags: (() -> Unit)? = null` to `MobileBlockToolbar`, wire it in `EditorToolbar` (the single wiring point for both screens), and call `tagSuggestionViewModel.requestSuggestions(blockUuid, blockContent, alreadyLinked)` with the current editing block's UUID and content captured at button-tap time. The `SuggestionBottomSheet` already handles the response display in both screens.

### Files to touch

| File | Change |
|------|--------|
| `ui/components/MobileBlockToolbar.kt` | Add `onSuggestTags: (() -> Unit)? = null` parameter; render `IconButton` in primary actions row when non-null |
| `ui/components/EditorToolbar.kt` | Build the `onSuggestTags` lambda from `editingBlockUuid` + block content, call `tagSuggestionViewModel.requestSuggestions(...)` |

No changes needed to `BlockItem.kt`, `BlockList.kt`, `BlockRenderer.kt`, `PageView.kt`, `JournalsView.kt`, `EditorCapabilities.kt`, or any tag engine files.
