# Stelekit UX Review

This document captures findings from a UX audit of the Stelekit KMP application. Findings span both mobile and desktop platforms and cover accessibility, usability, information architecture, and visual consistency. Use this as the working reference for triaging and prioritizing UX fixes.

**3 Critical · 5 High · 7 Medium · 6 Low**

### Severity Legend

| Level | Meaning |
|-------|---------|
| Critical | Broken experience, accessibility blocker, or silent no-op that violates user trust |
| High | Significant usability or accessibility gap with a clear fix path |
| Medium | Noticeable issue that degrades quality but has a workaround or low urgency |
| Low | Polish, icon semantics, or minor interaction timing issues |

---

## Critical

### CRIT-1 — MobileBlockToolbar obscures content (hardcoded 96dp bottom padding)

**Platform**: Mobile
**Category**: Usability, Interaction

The `LazyColumn` in `PageView.kt` and `JournalsView.kt` uses a hardcoded `PaddingValues(bottom = 96.dp)` but the two-row `MobileBlockToolbar` can reach 96–112dp. The last block hides behind the toolbar.

**Recommendation**: Measure toolbar height dynamically via `onSizeChanged` or use `Scaffold` with `bottomBar` so Compose handles insets automatically.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

---

### CRIT-2 — Mobile sidebar has no keyboard/focus trap (accessibility)

**Platform**: Mobile
**Category**: Accessibility (WCAG 2.1.2)

When the sidebar opens, focus is not moved into it. TalkBack and switch-access users can interact with content behind the scrim.

**Recommendation**: Use `FocusRequester` + `LaunchedEffect(sidebarExpanded)` to move focus into the sidebar on open and back to the hamburger button on close.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/MainLayout.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

---

### CRIT-3 — "Edit" and "Help" desktop menu items are non-interactive (silent no-ops)

**Platform**: Desktop
**Category**: Usability (Nielsen H1, H4)

Both are static `Text` composables with no click handler. Users who click them receive zero feedback.

**Recommendation**: Either wire up real `DropdownMenu`s or explicitly mark as disabled (`alpha = 0.38f`, non-clickable) until implemented.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (lines ~194–199, ~272–278)

---

## High

### HIGH-1 — Sidebar touch targets below Android 48dp minimum

**Platform**: Mobile
**Category**: Accessibility (WCAG 2.5.5, Material 3)

- `SidebarItem` favorite `IconButton`: `size(24.dp)` with 16dp icon, ~28–32dp row height
- `NavigationItem` row: ~30–34dp height
- `GraphItem` delete `IconButton`: `size(24.dp)` — destructive action with the smallest target

**Recommendation**: Apply `Modifier.defaultMinSize(minWidth=48.dp, minHeight=48.dp)` to all `IconButton`s; add `heightIn(min=48.dp)` to `SidebarItem` and `NavigationItem` `Surface`.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (lines 413–494, 287–339)

---

### HIGH-2 — Status bar is illegible and meaningless on mobile

**Platform**: Mobile
**Category**: Accessibility (WCAG 1.4.4, 1.4.3)

14dp icon, `labelSmall` (~11sp) text, 4dp vertical padding produces ~22dp total bar height. This is a desktop paradigm that does not translate to mobile.

**Recommendation**: Hide `StatusBarContent` on mobile (the `isMobile` flag is already threaded through `App.kt`). Surface the graph name in the TopBar center area instead.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (`StatusBarContent` composable, ~lines 481–527)

---

### HIGH-3 — Search dialog positioned at top 100dp — wrong for mobile thumb zones

**Platform**: Mobile
**Category**: Mobile UX (thumb reachability)

`SearchDialog.kt` uses `Modifier.padding(top = 100.dp)`. On mobile with the IME open, this creates split-attention between input at the top and results below.

**Recommendation**: On mobile, render search as a `ModalBottomSheet` so the input field is in the thumb-reachable zone and results scroll upward naturally.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SearchDialog.kt` (lines 68–75, 170)

---

### HIGH-4 — Mobile TopBar missing forward navigation button

**Platform**: Mobile
**Category**: Usability (Nielsen H3: User Control and Freedom)

The desktop branch has back and forward buttons; the mobile branch in `TopBar.kt` only has back. `appState.canGoForward` and `onGoForward` are wired but never rendered.

**Recommendation**: Add an `ArrowForward` `IconButton` after the back button in the mobile TopBar branch.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (mobile branch)

---

### HIGH-5 — Sidebar `onNavigate` uses raw strings instead of typed `Screen` sealed class

**Platform**: Both
**Category**: Usability, Consistency

`LeftSidebar.onNavigate: (String) -> Unit` uses literals like `"journals"` and `"flashcards"` while the rest of the app uses the `Screen` sealed class. A misspelled string fails silently.

**Recommendation**: Change the `onNavigate` signature to `(Screen) -> Unit` and pass sealed class instances directly.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (line 52)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

---

## Medium

### MED-1 — GraphSwitcher active state compares by displayName, not ID

**Platform**: Both
**Category**: Usability (Nielsen H5: Error Prevention)

`isActive = graph.displayName == currentGraphName` — two graphs with the same display name will both appear active.

**Recommendation**: Pass `activeGraphId: String?` to `GraphSwitcher` and compare `graph.id == activeGraphId`.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (line ~213)

---

### MED-2 — MobileBlockToolbar uses navigation arrows for indent/outdent

**Platform**: Mobile
**Category**: Usability (Nielsen H2, H6)

`ArrowBack` / `ArrowForward` icons are identical to TopBar navigation icons. The semantic meaning — navigate vs. restructure block hierarchy — is ambiguous.

**Recommendation**: Replace with `Icons.Default.FormatIndentDecrease` / `FormatIndentIncrease`.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt` (lines 107–118)

---

### MED-3 — Collapsed desktop sidebar has no visible toggle or icon rail

**Platform**: Desktop
**Category**: Information Architecture (Discoverability)

When the sidebar is collapsed via `Ctrl+B`, there is no visible affordance to re-open it. No hamburger button exists in the desktop TopBar.

**Recommendation**: Add a persistent 40dp icon-rail collapsed state, or add a hamburger/sidebar-toggle `IconButton` to the left side of the desktop TopBar.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (desktop branch)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

---

### MED-4 — Journal dates display as ISO format instead of human-readable

**Platform**: Both
**Category**: Usability (Nielsen H2: Match System and Real World)

`formatJournalDate` does a simple string replace of underscore to dash, producing technical ISO 8601 strings like `"2026-01-21"`.

**Recommendation**: Use `kotlinx.datetime.LocalDate.parse()` and format as "Wednesday, January 21, 2026".

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt` (lines ~262–264)

---

### MED-5 — Flashcards screen is an unindicated stub; RightSidebar is placeholder-only

**Platform**: Both
**Category**: Usability, Visual Hierarchy

`FlashcardsScreen()` shows only a title and "Flashcards review session." with no empty state design. `RightSidebar` has hardcoded "No block selected" / "No linked references found." strings with no real state connection.

**Recommendation**: Design intentional empty/coming-soon states with illustrations and CTAs. Hide `RightSidebar` from the `Ctrl+Shift+B` shortcut until it is connected to real block selection.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (`FlashcardsScreen` composable)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (`RightSidebar`)

---

### MED-6 — Mobile overflow menu mixes developer tools with user preferences

**Platform**: Mobile
**Category**: Information Architecture (Feature Discoverability)

The `DropdownMenu` contains Performance Dashboard, Debug Mode toggle, Language picker, and Theme picker in a single flat list of 8–12 items.

**Recommendation**: Move Language and Theme exclusively to `SettingsDialog`. Gate Performance Dashboard and Debug Mode behind a developer options setting or debug build flag only.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (mobile overflow branch)

---

### MED-7 — AllPages infinite scroll fires `onLoadMore` multiple times before state updates

**Platform**: Both
**Category**: Interaction (Performance)

The `snapshotFlow` in `AllPagesScreen` fires on every scroll event; no loading guard prevents duplicate fetches.

**Recommendation**: Add `isLoadingMore: Boolean` to ViewModel state and check it before calling `onLoadMore`.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (`AllPagesScreen`, lines ~569–577)

---

## Low

### LOW-1 — Encryption icon uses `LockOpen` import but renders `Info` for decrypted state

**Platform**: Both
**Category**: Visual (Icon semantics)

`if (isEncrypted) Icons.Default.Lock else Icons.Default.Info` — `Info` does not communicate "not encrypted". `LockOpen` is already imported at `App.kt` line 16 but unused.

**Recommendation**: Change to `if (isEncrypted) Icons.Default.Lock else Icons.Default.LockOpen`.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (`StatusBarContent`, line ~497)

---

### LOW-2 — Desktop TopBar interactive targets are 32dp (below WCAG 2.5.5 for pointer inputs)

**Platform**: Desktop
**Category**: Accessibility

Back/forward and Add/Settings `IconButton`s use `Modifier.size(32.dp)`.

**Recommendation**: Increase to `Modifier.size(36.dp)`.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (desktop branch)

---

### LOW-3 — `GraphSwitcher` is a custom AnimatedVisibility panel, not a `DropdownMenu`

**Platform**: Both
**Category**: Accessibility, Consistency

Missing `DropdownMenu` semantics, dismiss-on-outside-click, and Z-elevation.

**Recommendation**: Refactor to use `ExposedDropdownMenuBox` from Material 3.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (`GraphSwitcher` composable)

---

### LOW-4 — Unfavorited star icon at `alpha = 0.3f` looks disabled

**Platform**: Both
**Category**: Visual Hierarchy (Perceived Affordance)

`0.3f` alpha is Material 3's disabled-state value, making a fully interactive star appear non-interactive.

**Recommendation**: Raise to `alpha = 0.5f` or `0.6f` for the unfavorited state.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (line ~454)

---

### LOW-5 — `JournalsView` infinite scroll bottom loading indicator is an empty Box

**Platform**: Both
**Category**: Usability (Nielsen H1: Visibility of System Status)

A `// Placeholder for loading spinner` comment with an empty `Box` provides no feedback that more content is loading.

**Recommendation**: Replace with a conditional `CircularProgressIndicator` when `uiState.isLoading` is true.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt` (lines ~131–136)

---

### LOW-6 — Scrim remains clickable during sidebar close animation

**Platform**: Mobile
**Category**: Interaction (Animation timing)

`sidebarExpanded` flips false immediately on tap but `AnimatedVisibility`'s shrink animation keeps the scrim rendered. Rapid taps can land on content behind a half-dismissed scrim.

**Recommendation**: Disable scrim `clickable` during the exit animation using `AnimatedVisibility` state tracking.

**Affected files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/MainLayout.kt`

---

## Cross-Cutting Observations

**1. Navigation mixes developer tools with user features.**
Logs and Performance appear at the same visual level as Journals and All Pages. Move them to a collapsible "Developer" section or hide them behind a debug flag.

**2. Crossfade duration 300ms is perceptible for frequent navigations.**
Consider `tween(150)` or no transition for back/forward button navigations; reserve animation for sidebar-triggered navigations where context shift is meaningful.

**3. Right sidebar is entirely placeholder.**
It occupies 300dp of desktop screen real estate with zero functional value. It should be hidden until connected to real block selection and backlink data.

**4. Typography audit is incomplete.**
11 of 15 Material 3 type styles use defaults. Not a problem today, but worth auditing if the brand identity expands beyond current scope.
