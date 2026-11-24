# UX Research: Mobile Gutter Robustness

**Date**: 2026-11-23
**Inputs**: `requirements.md`, `BlockGutter.kt`, `MobileBlockToolbar.kt`, prior-art analysis

---

## 1. Comparable UX patterns

### Notion mobile (closest prior art)

Notion's block handle (⠿ six-dot icon) in view mode is the canonical reference for the proposed design.
Tapping it — not long-pressing — opens a contextual bottom sheet listing structural operations:
Turn into, Move to, Delete, Duplicate, Copy link to block, and Color.
The handle renders at full opacity at all times on mobile; there is no hover state.
This establishes the interaction contract the proposed design adopts: **tap drag handle → structural
action sheet**.

Key observation: Notion gates the full action set on the same tap as the entry affordance — no
mode-switch (edit/view) required. This is the gap the quick-action menu fills for SteleKit in
view mode.

### Logseq mobile

On desktop Logseq, clicking the bullet opens a block context menu (Open in sidebar, Copy block ref,
Focus on block here). On the mobile builds, the bullet is not reliably tappable — it is functionally
inaccessible. This is a known friction point for users migrating from desktop Logseq to mobile. The
SteleKit design should do better: a visible drag handle that doubles as a consistent quick-action
entry point addresses the exact complaint users have about Logseq mobile.

### Obsidian mobile

Obsidian does not have block-level structure natively; list items in Markdown view have no gutter.
The outline plugin adds a sidebar panel rather than inline gutter controls. Not directly comparable.

### Bear and Craft

Bear has no outline/block gutter at all. Craft does have block-level structure and renders a
"handle" on long-press, but its primary block operations (cut, copy, duplicate, delete) live in
the native iOS text selection menu that appears after long-press, rather than a persistent gutter
icon. The pattern does not transfer directly to Android/KMP.

### Summary

The Notion pattern — **visible gutter icon in view mode, single tap opens structural action sheet** —
is the established standard for outline-based editors on mobile. It is the right model to adopt.
The proposed SteleKit design aligns with it.

---

## 2. User mental model

Desktop Logseq users expect the bullet (the `•` dot) to open a block context menu on click.
They bring this expectation to mobile and find the bullet unresponsive.

The proposed feature's split of responsibilities — bullet for lasso-selection, drag handle for
quick-action — may cause brief confusion for users who try tapping the bullet expecting the menu.
Two mitigations are worth considering:

**Option A (recommended):** Make the bullet also a secondary entry point for the same quick-action
sheet on mobile. The lasso-select drag is initiated by *dragging*, not tapping; a bare tap on the
bullet can safely open the same action sheet as the drag handle without conflicting with the drag
gesture. This matches the Logseq mental model exactly and removes the need to discover the drag
handle as an action entry point.

**Option B:** Redirect to the drag handle via a first-use hint (see §5 below).

Option A is lower cognitive friction and requires no one-time hint. The implementation cost is
small: add the same `onClick` handler to the bullet Box that the drag handle will gain.

Regardless of the option chosen, the drag handle remains the primary tap target for the menu
(48 dp, always visible) and the bullet is the secondary path (≥ 24 dp on mobile).

---

## 3. Accessibility

### TalkBack navigation

TalkBack users rely on content descriptions and node bounds for navigation. The current gutter already
sets `contentDescription = "Drag to move"` on the drag handle and `contentDescription = "Select blocks"`
on the bullet. For the quick-action menu:

- The bottom sheet must carry `Modifier.semantics { paneTitle = "Block actions" }` so TalkBack
  announces "Block actions pane" on open.
- Each action button (Indent, Outdent, Move Up, Move Down, Copy Block Ref, Delete) needs a unique
  `contentDescription` without redundant "button" suffix — TalkBack adds that itself.
- The sheet must trap focus on open: the first focusable element should receive focus. Compose
  `ModalBottomSheet` does this by default via `Modifier.focusRequester`.
- On dismiss (action taken or swipe-down), focus must return to the drag handle or bullet that
  opened it, not reset to the top of the page. This requires the caller to restore focus
  explicitly after dismissal.

### Disabled states (see also §4)

Disabled actions (Move Up on first block, Outdent on root-level block) must remain visible in the
sheet and render as `enabled = false` rather than being hidden. TalkBack announces disabled items
with their content description plus "dimmed" — users learn what actions exist even when unavailable,
which builds the mental model faster than invisible items.

### Touch target compliance

The 48 dp requirement for the drag handle (already met) and the ≥ 24 dp requirement for the bullet
dot satisfy Google's Material Design accessibility guidelines for touch targets. The collapse caret
at 48 dp on mobile exceeds the minimum and aligns with the same standard.

---

## 4. Error states

### Move Up on the first block / Move Down on the last block

The action must render `enabled = false` in the sheet when the block is first or last in its
sibling list. It must not be hidden — see §3 (disabled states). No toast or snackbar is needed:
the disabled visual (greyed icon + text) is sufficient feedback for a tappable affordance that was
never activated.

The view model or the callback site already knows sibling position; `BlockGutter` should receive
`canMoveUp: Boolean` and `canMoveDown: Boolean` props so the sheet can render correctly without
querying the repository.

### Outdent on a root-level block

A root-level block (indent level 0) cannot be outdented further. Same pattern: `canOutdent: Boolean`
prop, rendered as `enabled = false` in the sheet. No destructive feedback.

### Indent when already at max nesting

If the app enforces a maximum nesting depth, `canIndent: Boolean` follows the same pattern.
Currently no max is enforced in SteleKit; the prop defaults to `true`.

### Delete with confirmation

Delete is destructive. The sheet should not fire `onDelete` immediately on tap. Instead, tap on
Delete should either:
(a) show a secondary confirmation row inside the sheet ("Delete this block? Confirm / Cancel"), or
(b) dismiss the sheet and show a `SnackbarWithUndo` ("Block deleted. Undo?").

Option (b) is consistent with the existing multi-select delete flow in `MobileBlockToolbar` (which
does not confirm inline), and lighter to implement. Recommended unless the requirements doc
specifies otherwise.

---

## 5. Discoverability

### Is the drag handle a discoverable entry point?

On mobile, the drag handle (`=` icon) renders at full alpha (`1.0`) when `useLongPressForDrag()`
is `true` (Android). It is persistently visible. However, its visual affordance communicates
"drag to reorder" rather than "tap to open menu" — the icon does not signal secondary-action
access.

Three options in increasing friction order:

**Option 1 (recommended): Dual-label the handle on first use.**
On the first time the user opens a page containing blocks (tracked in `DataStore` preferences,
key `gutter_menu_hint_shown`), render a brief tooltip or one-shot educational popover anchored
to the drag handle: "Tap for block actions". Dismiss on tap anywhere. Write the flag immediately
so it shows once per install. This is the same pattern used by Notion on new users' first note.

**Option 2: Animate the handle on first open.**
Briefly pulse the handle (a scale animation from 1.0 → 1.2 → 1.0 over 400 ms) on first page
load. Communicates interactivity without blocking content.

**Option 3: Rely on discovery through accidental taps.**
Users who attempt to drag will occasionally release quickly and trigger a tap. The sheet appears
and they learn the interaction exists. Low implementation cost but slower discovery rate — not
recommended for a primary path.

**Recommendation:** Option 1, implemented as a `Snackbar`-height one-shot tooltip that appears
at the bottom of the first block row. The existing `TooltipBox` Compose component handles
positioning and dismiss behaviour.

---

## Summary of key UX decisions

1. **Interaction model**: Tap drag handle (and optionally bullet) → modal bottom sheet with Indent,
   Outdent, Move Up, Move Down, Copy Block Ref, Delete. Long-press on handle remains drag-to-reorder.
   This follows the Notion pattern, the established standard for outline editors on mobile.

2. **Mental model alignment**: Adding bullet-tap as a secondary path to the same sheet (Option A
   in §2) removes friction for users who expect the bullet to be the action entry point, consistent
   with desktop Logseq conventions.

3. **Error states**: Disabled actions (Move Up / Move Down / Outdent at boundary) stay visible in
   the sheet with `enabled = false`. Delete requires a Undo Snackbar rather than inline confirmation.
   Requires `canMoveUp`, `canMoveDown`, `canIndent`, `canOutdent` props on `BlockGutter`.

4. **Accessibility**: Sheet requires `paneTitle = "Block actions"` semantics, focus trap on open,
   focus restore on close, and descriptive content descriptions on each action button (no hidden
   disabled items).

5. **Discoverability**: One-shot tooltip on first page open ("Tap for block actions") anchored to
   the drag handle. Write a `gutter_menu_hint_shown` DataStore flag on first display; show once
   per install.
