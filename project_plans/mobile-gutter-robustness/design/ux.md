# UX Design: Mobile Gutter Robustness

**Date**: 2026-11-23  
**Feature**: `mobile-gutter-robustness`  
**Author**: UX design pass — from requirements.md, research/ux.md, ADR-001, ADR-002, BlockGutter.kt

---

## Surfaces Covered

| # | Surface | Type |
|---|---------|------|
| S1 | BlockGutter — view mode (idle, pre-tap) | Persistent gutter UI |
| S2 | BlockGutter — discoverability tooltip | One-shot overlay |
| S3 | QuickActionSheet — full state | Modal bottom sheet |
| S4 | QuickActionSheet — boundary disabled states | Partial sheet state |
| S5 | QuickActionSheet — delete action confirmation | Post-action snackbar |
| S6 | "Copied" toast — copy block ref success | Ephemeral feedback |

---

## S1: BlockGutter — View Mode (Idle)

### Layout — mobile (isMobile = true)

```
┌─────────────────────────────────────────────────────────────┐
│  Row height: auto (content-driven)                          │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌────────┐  Block text...     │
│  │          │  │          │  │        │                     │
│  │  Drag    │  │ Collapse │  │ Bullet │  Lorem ipsum        │
│  │ Handle   │  │  Caret   │  │  Dot   │  dolor sit         │
│  │  (48dp)  │  │  (48dp)  │  │ (24dp) │  amet...           │
│  │          │  │          │  │        │                     │
│  └──────────┘  └──────────┘  └────────┘                     │
│    "Drag to      "Expand" /    "Select                       │
│     move"        "Collapse"    blocks"                       │
│    TalkBack      TalkBack      TalkBack                      │
│    label         label         label                         │
└─────────────────────────────────────────────────────────────┘

Touch target sizes (mobile):
  Drag handle:   48 × 48 dp  [visually: 18dp DragHandle icon, centered]
  Collapse caret: 48 × 48 dp  [visually: 18dp caret icon, centered]
  Bullet dot:    24 × 24 dp  [visually: 6dp circle, centered]

Indentation: each nesting level adds 16dp left padding before gutter
```

### Layout — desktop (isMobile = false, unchanged)

```
┌──────────────────────────────────────────────────────────────┐
│  ┌────────┐  ┌──────┐  ┌──────┐  Block text...              │
│  │ Drag   │  │Caret │  │ Dot  │  Lorem ipsum dolor sit...   │
│  │(18dp)  │  │(18dp)│  │(6dp) │                             │
│  └────────┘  └──────┘  └──────┘                             │
│  Hover → alpha 1.0; idle → alpha 0.45                        │
└──────────────────────────────────────────────────────────────┘
  Desktop layout is PIXEL-IDENTICAL to current baseline.
  No changes to touch target sizes or alpha behavior on desktop.
```

### Interaction flow — drag handle (mobile, view mode)

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Rests finger on drag handle | Nothing — pointer down not consumed yet |
| 2a | Short tap (< long-press threshold, ~400ms) | Opens QuickActionSheet (S3) |
| 2b | Long-press then drag | Haptic: `LongPress` fires. Drag-to-reorder activates. QuickActionSheet does NOT open. |
| 3 (drag) | Drag up/down | Haptic: `TextHandleMove` fires per reorder position change. Visual drag indicator moves. |
| 4 (drag) | Release | Block reordered. No sheet shown. |

### Interaction flow — bullet dot (mobile, view mode)

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Rests finger on bullet | Nothing |
| 2a | Short tap | Opens QuickActionSheet (same sheet as drag handle). Secondary path for Logseq-familiar users. |
| 2b | Drag (not tap) | Lasso-select activates. QuickActionSheet does NOT open. |

### Interaction flow — collapse caret (mobile, view mode)

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Tap 48dp caret region | Block's children collapse or expand. Caret icon switches direction. TalkBack: "Collapse" / "Expand". |

### Gutter states summary

| State | Drag handle | Caret | Bullet |
|-------|-------------|-------|--------|
| View mode, mobile, block has children | Tap → sheet, long-press → drag | Tap → toggle | Tap → sheet, drag → lasso |
| View mode, mobile, no children | Tap → sheet, long-press → drag | Hidden (Spacer 48dp) | Tap → sheet, drag → lasso |
| Edit mode, mobile | Long-press → drag only. No tap-to-sheet. | Tap → toggle | Drag → lasso. No tap-to-sheet. |
| Selection mode, mobile | Checkbox replaces drag handle (existing behavior) | Tap → toggle | Drag → lasso |
| Desktop, any mode | Hover → alpha 1.0; immediate drag (no long-press). No tap-to-sheet. | Click → toggle | Drag → lasso |

---

## S2: Discoverability Tooltip (One-Shot)

Shown once per install, on the first page load that contains at least one block. Gated by
`DataStore` boolean flag `gutter_menu_hint_shown`. Written to `true` on first display.

### Layout

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌────────┐                    │
│  │  Drag    │  │ Collapse │  │ Bullet │  Block text...      │
│  │ Handle   │  │  Caret   │  │  Dot   │                     │
│  │  (48dp)  │  │  (48dp)  │  │ (24dp) │                     │
│  └────┬─────┘  └──────────┘  └────────┘                     │
│       │                                                     │
│  ┌────▼─────────────────────────────────────────────┐       │
│  │  💡 Tap for block actions                        │       │
│  └──────────────────────────────────────────────────┘       │
│                                                             │
└─────────────────────────────────────────────────────────────┘

Tooltip:
  Component: PlainTooltip (Material3) anchored below the drag handle
  Text: "Tap for block actions"
  Auto-dismiss: 3000ms, or on any tap anywhere
  Appear: 500ms delay after page load (avoids fighting scroll-in animation)
  Show once: flag gutter_menu_hint_shown written to DataStore before display
```

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | First page open (no flag set) | After 500ms, tooltip appears below drag handle of first block. `gutter_menu_hint_shown = true` written to DataStore immediately. |
| 2a | User taps anywhere | Tooltip dismisses. |
| 2b | Tooltip times out (3s) | Tooltip auto-dismisses. |
| 3 | Subsequent page opens | No tooltip shown (`gutter_menu_hint_shown = true`). |

### Error state

If DataStore read fails, treat as "not shown yet" (default false). The tooltip may appear
at most once more. Write the flag optimistically; a DataStore write failure means the tooltip
may appear again on next launch — this is acceptable; the alternative (never showing) is worse.

---

## S3: QuickActionSheet — Full State

Opened by: short tap on drag handle OR short tap on bullet dot. Mobile view mode only.

### Layout

```
  ┌──────────────────────────────────────────────────┐
  │  ════════════════════  ← drag handle (dismiss)   │  12dp top
  │                                                  │
  │  Block actions                                   │  16dp padding, body-large
  │  ─────────────────────────────────────────────   │  divider
  │                                                  │
  │  ⬆  Move Up                                      │  48dp row height
  │  ⬇  Move Down                                    │  48dp row height
  │  ←  Outdent                                      │  48dp row height
  │  →  Indent                                       │  48dp row height
  │  🔗  Copy Block Ref                               │  48dp row height
  │  🗑  Delete                                       │  48dp row height, error color
  │                                                  │
  │  ─────────────────────────────────────────────   │
  │  [Cancel]                                        │  48dp, center-aligned
  └──────────────────────────────────────────────────┘
  Navigation bar inset (WindowInsets.navigationBars)

Compose component: ModalBottomSheet
  - skipPartiallyExpanded = true  (shows full sheet, not peek)
  - windowInsets = WindowInsets.navigationBars
  - Modifier.semantics { paneTitle = "Block actions" }

Action rows: each is a full-width ListItem or TextButton
  - height: 48dp (meets minimum touch target)
  - padding: horizontal 16dp
  - icon: 24dp, leading
  - label: body-large
  - contentDescription: set explicitly on each row (see S3 action labels)

Cancel row:
  - height: 48dp
  - label: "Cancel" (body-large, secondary color)
  - onClick: dismiss sheet
```

### Action labels and content descriptions

| Label | Content description | Icon |
|-------|--------------------|----|
| Move Up | "Move block up" | ArrowUpward |
| Move Down | "Move block down" | ArrowDownward |
| Outdent | "Outdent block" | FormatIndentDecrease |
| Indent | "Indent block" | FormatIndentIncrease |
| Copy Block Ref | "Copy block reference" | Link |
| Delete | "Delete block" | Delete |

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Tap drag handle (view mode) | Sheet animates up from bottom. TalkBack: "Block actions pane". Focus moves to first action (Move Up). |
| 2 | Tap Move Up | `onMoveUp()` callback fires. Sheet dismisses. Focus returns to drag handle. |
| 3 | Tap Move Down | `onMoveDown()` callback fires. Sheet dismisses. Focus returns to drag handle. |
| 4 | Tap Outdent | `onOutdent()` callback fires. Sheet dismisses. Focus returns to drag handle. |
| 5 | Tap Indent | `onIndent()` callback fires. Sheet dismisses. Focus returns to drag handle. |
| 6 | Tap Copy Block Ref | `onCopyBlockRef()` callback fires. Sheet dismisses. "Copied" toast shown (S6). Focus returns to drag handle. |
| 7 | Tap Delete | `onDelete()` callback fires. Sheet dismisses. Undo snackbar shown (S5). Focus moves to snackbar action. |
| 8 | Tap Cancel | Sheet dismisses. No action. Focus returns to element that opened the sheet. |
| 9 | Swipe down on sheet | Sheet dismisses. No action. Focus returns to element that opened the sheet. |
| 10 | Tap outside sheet scrim | Sheet dismisses. No action. Focus returns to element that opened the sheet. |

### Auto-dismiss guards

The sheet must also auto-dismiss (without user action) in these cases:

| Trigger | Reason |
|---------|--------|
| `isDragging` becomes `true` (long-press entered) | Drag started while sheet was animating open — close to prevent overlap |
| `isInSelectionMode` becomes `true` | Selection mode activated externally (e.g. another block drag started) |
| Block is deleted by an external event | The block no longer exists; the sheet would operate on a stale target |

---

## S4: QuickActionSheet — Boundary Disabled States

When an action is not available for the current block (boundary conditions), the row renders
as `enabled = false`. It stays visible — it is NOT hidden. TalkBack announces "dimmed" after
the content description.

### Visual treatment

```
  Enabled row:
  │  ⬆  Move Up                                      │  full opacity, tappable

  Disabled row:
  │  ⬆  Move Up                                      │  38% opacity (Material3 disabled alpha)
                                                       not tappable, role = Button, state = disabled
```

### Disabled conditions

| Action | Disabled when |
|--------|---------------|
| Move Up | Block is the first sibling at its level (`canMoveUp = false`) |
| Move Down | Block is the last sibling at its level (`canMoveDown = false`) |
| Outdent | Block is at root level (indent level 0) (`canOutdent = false`) |
| Indent | No previous sibling exists to become parent (`canIndent = false`) |
| Copy Block Ref | Never disabled — always enabled |
| Delete | Never disabled — always enabled |

### Example: first block at root level

```
  ┌──────────────────────────────────────────────────┐
  │  ════════════════════                            │
  │                                                  │
  │  Block actions                                   │
  │  ─────────────────────────────────────────────   │
  │                                                  │
  │  ⬆  Move Up          ← DISABLED (38% opacity)   │
  │  ⬇  Move Down                                   │
  │  ←  Outdent          ← DISABLED (38% opacity)   │
  │  →  Indent           ← DISABLED (38% opacity)   │
  │  🔗  Copy Block Ref                               │
  │  🗑  Delete                                       │
  │                                                  │
  │  [Cancel]                                        │
  └──────────────────────────────────────────────────┘

  Tapping a disabled row: no action, no feedback (the row is not clickable).
  TalkBack on disabled Move Up: "Move block up, dimmed"
```

---

## S5: Undo Snackbar (Post-Delete)

Shown immediately after the user taps Delete in the QuickActionSheet.

### Layout

```
  ┌────────────────────────────────────────────────────────┐
  │   Block deleted.                             [Undo]    │
  └────────────────────────────────────────────────────────┘
  Anchored above navigation bar (SnackbarHost, bottom of scaffold)
  Auto-dismiss after 4000ms if Undo not tapped.
```

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Tap Delete in sheet | Sheet dismisses. `onDelete()` fires. Snackbar appears: "Block deleted. Undo?" |
| 2a | Tap "Undo" in snackbar | `onUndoDelete()` fires. Block is restored. Snackbar dismisses. |
| 2b | Snackbar times out (4s) | Snackbar dismisses. Delete is committed. No further undo path. |
| 2c | User navigates away (page change) | Snackbar dismisses. Delete is committed. |

### Content descriptions (accessibility)

- Snackbar message: announced by TalkBack as "Block deleted."
- "Undo" action button: `contentDescription = "Undo delete"`

### No undo after timeout — empty state handling

If the deleted block was the only block on the page and the undo timer expires:
- The page renders in its "empty page" state (existing behavior — not new).
- No dead end: the user can type to add a new block.

---

## S6: "Copied" Toast (Post-Copy Block Ref)

Shown immediately after the user taps Copy Block Ref in the QuickActionSheet. Ephemeral
confirmation that the block reference was placed on the clipboard.

### Layout

```
  ┌────────────────────────────────────────────────────┐
  │   Block reference copied.                          │
  └────────────────────────────────────────────────────┘
  Anchored above navigation bar (SnackbarHost).
  Auto-dismiss after 2000ms. No action button.
```

### Interaction flow

| Step | User action | System response |
|------|-------------|-----------------|
| 1 | Tap Copy Block Ref in sheet | Sheet dismisses. Clipboard set. Toast appears: "Block reference copied." |
| 2 | Toast auto-dismisses (2s) | Nothing further. User may paste the block ref anywhere. |

---

## Acceptance Criteria

### Touch Targets

**UX-AC-01** On a device or emulator with compact or medium window width, the drag handle's
tappable region is exactly 48 × 48 dp. Verified by asserting that the semantics node with
`contentDescription = "Drag to move"` has bounds ≥ 48 × 48 dp in a Robolectric test.

**UX-AC-02** On mobile, the collapse caret's tappable region is 48 × 48 dp. Verified by
asserting the "Expand" / "Collapse" semantics node bounds ≥ 48 × 48 dp in a Robolectric test.

**UX-AC-03** On mobile, the bullet dot's draggable region is 24 × 24 dp. Verified by asserting
the "Select blocks" semantics node bounds ≥ 24 × 24 dp in a Robolectric test.

**UX-AC-04** On desktop, the collapse caret and bullet areas retain their existing visual and
hit sizes (18 dp and 6 dp respectively). No pixel diff in Roborazzi desktop screenshot goldens.

### QuickActionSheet — Opening

**UX-AC-05** A single tap on the drag handle (view mode, mobile) opens the QuickActionSheet in
≤ 1 step. The sheet is visible within the same interaction (no additional confirm required).

**UX-AC-06** A single tap on the bullet dot (view mode, mobile) opens the same QuickActionSheet
in ≤ 1 step (secondary path).

**UX-AC-07** On desktop, tapping the drag handle does NOT open the QuickActionSheet. The
sheet component is never composed when `isMobile = false`.

**UX-AC-08** In edit mode on mobile, tapping the drag handle does NOT open the QuickActionSheet.
The tap-to-sheet modifier is absent when the block is in edit mode.

### QuickActionSheet — Actions

**UX-AC-09** The QuickActionSheet contains exactly 6 action rows in this order: Move Up,
Move Down, Outdent, Indent, Copy Block Ref, Delete. Plus a Cancel row. Verified by asserting all
7 semantics nodes are present.

**UX-AC-10** Tapping Move Up calls `onMoveUp()`, closes the sheet, and returns focus to the
drag handle — all in ≤ 1 user action from the sheet being open.

**UX-AC-11** Tapping Move Down calls `onMoveDown()` and closes the sheet in ≤ 1 step.

**UX-AC-12** Tapping Outdent calls `onOutdent()` and closes the sheet in ≤ 1 step.

**UX-AC-13** Tapping Indent calls `onIndent()` and closes the sheet in ≤ 1 step.

**UX-AC-14** Tapping Copy Block Ref calls `onCopyBlockRef()`, closes the sheet, and shows the
"Block reference copied." toast — all within ≤ 1 user action from the sheet being open.

**UX-AC-15** Tapping Delete calls `onDelete()`, closes the sheet, and shows the undo snackbar —
all within ≤ 1 user action from the sheet being open.

**UX-AC-16** Tapping Cancel closes the sheet. No action is called. No snackbar or toast appears.

### QuickActionSheet — Disabled States

**UX-AC-17** When `canMoveUp = false`, Move Up renders as a disabled (38% opacity) non-tappable
row. The row is visible. The row's content description is present ("Move block up"). Tapping it
does not call `onMoveUp()`.

**UX-AC-18** When `canMoveDown = false`, Move Down renders as disabled. Same rules as UX-AC-17.

**UX-AC-19** When `canOutdent = false`, Outdent renders as disabled. Same rules as UX-AC-17.

**UX-AC-20** When `canIndent = false`, Indent renders as disabled. Same rules as UX-AC-17.

**UX-AC-21** Delete and Copy Block Ref are never disabled. They are always tappable.

### QuickActionSheet — No Dead Ends

**UX-AC-22** Every path that opens the QuickActionSheet has at least one exit (Cancel, swipe-down,
tap-scrim). No state exists where the sheet is open and cannot be dismissed.

**UX-AC-23** If a drag is initiated while the sheet is animating open, the sheet auto-dismisses.
The drag-to-reorder flow completes normally.

### Undo Snackbar

**UX-AC-24** After Delete, the undo snackbar appears with message "Block deleted." and action
button "Undo". Auto-dismisses after 4000ms.

**UX-AC-25** Tapping "Undo" within the 4s window calls `onUndoDelete()` and dismisses the
snackbar. The block is restored to its original position.

**UX-AC-26** The undo snackbar is dismissed if the user navigates to a different page before the
4s window expires. The delete is committed on navigation.

### Copy Block Ref Toast

**UX-AC-27** After Copy Block Ref, the toast "Block reference copied." appears and auto-dismisses
after 2000ms. No user action required to dismiss it.

### Discoverability Tooltip

**UX-AC-28** On first page load containing at least one block, the tooltip "Tap for block actions"
appears anchored below the drag handle of the first block. It appears once per install.

**UX-AC-29** On subsequent page loads, the tooltip does not appear (flag `gutter_menu_hint_shown`
is set).

**UX-AC-30** Tapping anywhere dismisses the tooltip immediately.

### Haptic Feedback

**UX-AC-31** When a long-press drag begins on the drag handle on Android, exactly one haptic
event of type `HapticFeedbackType.LongPress` fires before the first drag delta.

**UX-AC-32** For each reorder position change during a drag, one haptic event of type
`HapticFeedbackType.TextHandleMove` fires.

### Accessibility

**UX-AC-33** The QuickActionSheet has `paneTitle = "Block actions"` in its semantics. TalkBack
announces "Block actions pane" on open.

**UX-AC-34** On sheet open, TalkBack focus moves to the first action row (Move Up) automatically.

**UX-AC-35** On sheet dismiss (any path), TalkBack focus returns to the gutter element (drag
handle or bullet) that opened the sheet.

**UX-AC-36** Each action row has a unique, descriptive content description (see S3 action labels
table). No row's content description is null or duplicated.

**UX-AC-37** Disabled action rows remain in the accessibility tree and are announced by TalkBack
as `<label>, dimmed` (e.g. "Move block up, dimmed").

**UX-AC-38** The drag handle, collapse caret, and bullet each have a non-null content description
in both enabled and disabled states (existing descriptions must not be removed).

**UX-AC-39** Color contrast for all action labels in the sheet meets ≥ 4.5:1 against the sheet
background, in both light and dark Material3 themes (checked via Compose SemanticsMatcher or
Accessibility Scanner).

**UX-AC-40** Disabled action rows meet ≥ 3:1 contrast (WCAG AA for large text / UI components
in a disabled state).

### Interaction Preservation

**UX-AC-41** Long-press drag-to-reorder continues to work after the tap-to-sheet addition.
A long-press followed by a drag on the handle does not open the sheet, activates reorder mode,
and fires `onDragStart`. Verified by `BlockItemGestureTest`.

**UX-AC-42** Lasso-select via bullet drag continues to work after the bullet tap-to-sheet
addition. A drag (not tap) on the bullet activates lasso-select, not the sheet.

---

## Open Questions

None — all decisions resolved by ADR-001 (sheet state location), ADR-002 (gesture conflict
resolution), and research/ux.md (Notion pattern, disabled states, discoverability, accessibility).
