# ADR-004: UI Entry Points for Export

**Status**: Accepted
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

---

## Context

The export action needs to be discoverable and accessible. Four candidate entry points exist in the current UI:

1. **Command palette** (`AppState.commands`, surfaced via `CommandPalette.kt`) — already wired; `updateCommands()` in `StelekitViewModel` populates `AppState.commands` from `CommandManager`
2. **TopBar File menu** (`TopBar.kt`, desktop-only `DropdownMenu` around the "File" text button) — currently has only "Switch Graph"
3. **PageView header icon button** (`PageView.kt`, alongside existing Rename and Favorite `IconButton`s) — contextual, visible while reading
4. **Block-level context menu** — a new context menu on multi-block selection, leveraging `BlockStateManager.isInSelectionMode`

The question is which to implement in Phase 1 and which to defer.

---

## Decision

**Implement two entry points in Phase 1:**

1. **Command palette** (primary) — four `Command` entries added to `StelekitViewModel.updateCommands()`:
   - `export.page.markdown` / "Export page as Markdown" / shortcut `Ctrl+Shift+E`
   - `export.page.plain-text` / "Export page as Plain Text"
   - `export.page.html` / "Export page as HTML"
   - `export.page.json` / "Export page as JSON"
   All four are only visible when `AppState.currentPage != null`. All four copy to clipboard and display a `NotificationManager.show("Copied as Markdown")` success toast.

2. **TopBar File menu** (discoverable) — add an "Export page" submenu (or four flat items behind a `HorizontalDivider`) to the existing desktop `DropdownMenu` in `TopBar.kt`. Only rendered in the `!isMobile` branch. The TopBar currently receives `appState: AppState` which contains `currentPage`; items are disabled when `currentPage == null`.

**Defer to follow-on:**
- PageView header icon — low priority vs. TopBar; adds Compose layout complexity
- Block-selection context menu — the `BlockStateManager` selection state is ready, but the context menu UI is a separate story deferred to Story 4

---

## Rationale

1. **Command palette is zero-friction and zero-UI**: Adding four `Command` items to `updateCommands()` requires no new Compose components. It's available immediately to keyboard-centric users.

2. **TopBar File menu is the canonical desktop location**: Desktop productivity apps (VSCode, Notion Desktop, Obsidian) universally put export in the File menu. Users expect to find it there. The `TopBar.kt` `DropdownMenu` already handles the desktop branch and the existing pattern is straightforward to extend.

3. **Avoiding three simultaneous UI touch points for Phase 1**: Implementing the PageView header icon AND the TopBar menu AND the command palette simultaneously would require coordinating three Composable changes. The PageView icon is additive UI value but not the primary discovery path — users who don't know about the feature will find it in the File menu; power users will use the command palette.

4. **TopBar already has the right gate**: The `!isMobile` guard in `TopBar.kt` means desktop-only export items appear only where the feature is supported in Phase 1. No additional platform checks needed.

5. **Block-level export requires selection UI work**: The `BlockStateManager.isInSelectionMode` state exists, but wiring it to a context menu that surfaces an export action requires a separate UI surface. This is scoped to Story 4 to keep Phase 1 focused.

---

## Consequences

**Story 3 changes:**
- `StelekitViewModel.kt`: add `fun exportPage(formatId: String, clipboardProvider: ClipboardProvider)` and `fun exportSelectedBlocks(formatId: String, clipboardProvider: ClipboardProvider)`; add four export `Command` entries in `updateCommands()`
- `TopBar.kt`: add "Export page" submenu items in desktop `DropdownMenu`; pass `onExportPage: (formatId: String) -> Unit` callback through the composable parameter list
- `App.kt`: wire the new `onExportPage` callback to `viewModel.exportPage()`

**Story 4 (block-level, follow-on):**
- `PageView.kt`: add keyboard shortcut `Ctrl+Shift+E` when `isInSelectionMode` → calls `viewModel.exportSelectedBlocks()`
- New context menu item for multi-block selection visible when `isInSelectionMode == true`
