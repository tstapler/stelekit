# Feature Research: mobile-gutter-robustness

## 1. Industry patterns (Notion, Obsidian, Bear, Craft)

### Notion (Android/iOS)
- **Gutter affordance**: drag handle is hidden at rest; tap-and-hold reveals a bottom sheet with block actions. Bullet/icon tap does nothing (decoration only). The touch target for the drag handle is a narrow strip of ~40 dp left of the block; on mobile, the whole row is long-pressable.
- **Quick-action sheet** (from drag handle tap in view mode): Move Up, Move Down, Turn Into (type change), Delete, Copy Block Link, Comment. Duplicate. No "Indent/Outdent" in the sheet — those are keyboard/desktop-only in Notion's model.
- **Copy Block Link**: shows the URL immediately in a toast (no preview of the syntax). Mobile users are expected to paste into another context, not read the raw `((uuid))` syntax.
- **Delete**: no confirmation dialog. "Undo" via the global undo button is the safety net.
- **Touch targets**: consistent 44–48 dp hit area; the drag handle glyph itself is about 20 dp but the hitbox is padded to 48 dp.

### Obsidian (mobile)
- **No explicit gutter** in mobile reading mode; entering edit mode reveals a drag handle per line.
- **Context/action sheet**: long-press opens a sheet with Cut, Copy, Paste, Delete Line, Toggle Checkbox, Toggle Heading, Indent, Unindent.
- **Indent/Outdent**: present and enabled/disabled based on context (Outdent disabled when already at root level). The disabled items are still shown but grayed out — not hidden — to teach the user the feature exists.
- **Copy Block ID** (equivalent to block ref): copies `^uuid` Obsidian syntax, showing a brief "Copied" toast. The menu item label says "Copy block ID" not the syntax itself.

### Bear (iOS)
- No block-level gutter; paragraph-level swipe gestures instead. Not relevant for bullet-outliner model.

### Craft (iOS/iPadOS)
- **Gutter dot** is 24 dp diameter touch target, tap opens an action sheet.
- **Action sheet**: Move Up, Move Down, Duplicate, Delete, Copy Permalink, Outdent, Indent. Items that cannot apply (Indent when no preceding sibling; Outdent when at root) are **disabled (grayed)**, not hidden.
- **Copy Permalink**: copies `craftdocs://...` URL and shows the full URL in a "Copied" toast. The user can see what they copied.
- **Delete**: no confirmation; undo banner appears at the bottom for ~5 seconds.
- **Haptic feedback**: short tick on drag start (`.selectionChanged`), stronger buzz on drop (`.impactOccurred(.medium)`). Obsidian also does this.

### Synthesis
All four apps share these conventions:
- Disabled actions stay visible (grayed) rather than hidden — this is the universal pattern for action sheets.
- Delete is immediate (no dialog); a dismissible undo affordance is the safety net.
- Copy-block-ref/link shows a brief "Copied" toast; the raw syntax does not appear in the menu label on mobile (though showing it, as `SuggestionContextMenu` does for `[[page]]`, would be acceptable and more transparent).
- Haptic on drag start is present in Craft and Obsidian; absent in Notion. It is the standard iOS interaction pattern (`UIImpactFeedbackGenerator`).

---

## 2. Edge cases the quick-action sheet must handle

### Indent
- Disabled when the block is the **first sibling** at its level (no preceding sibling to become its new parent). `TreeOperations.indent()` already returns `null` in this case (`if (index <= 0) return null`).
- Disabled (or shown at level 0 as the only enabled case) when the block is the only child.
- Not applicable to the page root/title block (level -1 convention, if any). Verify that the ViewModel exposes a `canIndent` derived value rather than letting the sheet try and silently fail.

### Outdent
- Disabled when `Block.parentUuid == null` (already at root/level-0). `TreeOperations.outdent()` returns `null` in this case.
- When outdented, children of the block stay as children (they move with it). If the block carries children, the action still works — no special disable needed — but the visual tree shift can surprise users. Consider a brief "Block and its children moved" toast.

### Move Up
- Disabled when the block is **first** among its siblings (`leftUuid == null` and `index == 0`). `TreeOperations.moveUp()` returns `null` for `index <= 0`.
- Note: "first sibling" is determined within the parent scope. A block that is first among children of a parent is still moveable within that parent's list, even if it appears at the top of the visible viewport. The ViewModel must supply `isFirstSibling`, not just `isFirstVisible`.

### Move Down
- Disabled when the block is **last** among its siblings. `TreeOperations.moveDown()` returns `null` for `index >= siblings.size - 1`.

### Delete
- Delete with children: the existing `deleteBlock(blockUuid, deleteChildren: Boolean)` in `DatalogBlockRepository` must be called with `deleteChildren = true` from the quick-action sheet (to match the Tab-then-Delete keyboard pattern). Do not silently orphan children.
- Do **not** add a confirmation dialog (see §1 prior art). An undo affordance (Snackbar for ~5s) is the right safety net.
- After delete the ViewModel should re-focus the previous block or the parent, not leave focus on a deleted uuid.

### Copy Block Ref
- Format is `((uuid))` (confirmed by `PageExporter.kt`: "resolvedRefs maps block UUIDs referenced via `((uuid))` syntax").
- The block must have an `id::` property in its markdown for the ref to survive a file round-trip. If `block.properties["id"]` is absent (older block), the copy should write the uuid to `id::` before returning the ref string — or display a "Block needs an ID property first" toast and trigger the save.
- Show a brief "Copied `((uuid))`" toast so the user knows what was placed on the clipboard.

### Read-only page
- The existing codebase does not expose an `isReadOnly` flag at the `BlockGutter` or `BlockItem` level. All five quick actions mutate state, so in read-only mode the entire quick-action sheet should either not be shown (preferred — no affordance for actions that can't fire) or all items should be disabled. Gate on whatever the ViewModel uses to prevent edit operations on the page.

### Selection mode
- When `isInSelectionMode` is true, the drag handle becomes a Checkbox (see `BlockGutter.kt` lines 85–152). The quick-action sheet tap-trigger (a tap on the drag handle) must be suppressed in selection mode — otherwise tapping a checkbox with a slight delay could open the sheet. Gate: only show the sheet tap handler when `!isInSelectionMode`.

---

## 3. Unstated user needs beyond the explicit requirements

### Touch target on caret and bullet
- **Caret**: currently 18 dp + `.clickable {}` with no padding expansion (line 157–162 of `BlockGutter.kt`). The `.clickable` modifier alone does not expand the hit area; it matches the visual 18 dp size. On mobile this fails the 48 dp requirement. The fix mirrors the drag handle: wrap in a `Box(Modifier.size(48.dp))` with the gesture attached to the Box.
- **Bullet**: The comment at line 174–177 explicitly says "Deliberately reuses the existing 6 dp hit area rather than enlarging it … this gesture is primarily mouse-driven." On mobile, lasso-select from the bullet is not the primary use case — a 24 dp tap target (minimum for touch) is needed for the quick-action sheet trigger. The comment's desktop-only rationale does not apply to mobile, so the `isMobile` branch should use a 24–48 dp tap target while desktop keeps 6 dp.

### Drag on mobile with long-press already claimed
- `useLongPressForDrag()` is already mobile-aware (the flag is true on Android). The quick-action trigger must therefore be a **plain tap** (not long-press) on the drag handle in view mode. In edit mode the drag stays as long-press.

### Undo for quick-action operations
- None of the five actions have undo today. For delete this is the highest risk. The pattern from Craft/Notion is a Snackbar with an "Undo" action that re-inserts the block. The ViewModel's existing `onMoveUp`/`onMoveDown` callbacks already exist on `BlockGutter` — the Snackbar undo for delete is the net-new piece.

### Copy Block Ref — show the syntax
- On desktop, `SuggestionContextMenu` shows the full syntax `Link as [[$canonicalName]]`. Applying the same transparency on mobile (menu item shows `Copy ((uuid:short…))` or a toast showing the copied value) would remove confusion about what a "block ref" is for new users.

---

## 4. Existing codebase patterns the implementation should reuse

### Bottom sheet: `ModalBottomSheet` with `skipPartiallyExpanded = true`
Used in `MoreSheet`, `MeasurementValueEditorSheet`, `BleDevicePanel`, `CalibrationSheet`, `SyncStatusSheet`, `SuggestionBottomSheet`, `LfsHeldFilesSheet`. All share the same pattern:
```kotlin
ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) { Column(Modifier.navigationBarsPadding()) { ... } }
```
The quick-action sheet should follow this pattern exactly.

### Haptic feedback on selection-mode entry
`BlockItem.kt` lines 263–279 wires `LocalHapticFeedback.current` with `HapticFeedbackType.LongPress` on selection mode entry. For drag-start haptic, the same call site in the drag handle's `onDragStart` callback in `BlockGutter.kt` is the right place — `LocalHapticFeedback` is a composition local, so pass the haptic reference into the gutter or lift the callback.

### `isMobile` gating
`LocalWindowSizeClass.current.isMobile` is the established boolean used in `App.kt`, `GraphContentActiveShell.kt`, `GraphContentLeftSidebar.kt`, and `MainLayout.kt`. All mobile-only gutter changes should read this value at the `BlockGutter` call site and pass a `isMobile: Boolean` parameter down, keeping the composable itself platform-agnostic.

### Tree operations
`TreeOperations.indent()`, `outdent()`, `moveUp()`, `moveDown()` all return `null` when the operation is not applicable — this null return is the natural disable signal for the sheet's button states. The ViewModel should expose `canIndent`, `canOutdent`, `canMoveUp`, `canMoveDown` as derived booleans by dry-running the operations or tracking sibling position.

### Block ref syntax
`((uuid))` — confirmed in `PageExporter.kt`. Copy to clipboard via the platform clipboard API (already used in the clipboard module; see `ClipboardModels.kt`).
