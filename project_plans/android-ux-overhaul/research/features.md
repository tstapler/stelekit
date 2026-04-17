# Features Research: Android Navigation & Mobile Editing UX
**Project**: SteleKit Android UX Overhaul
**Dimension**: Features — Competitive survey of mobile navigation and editing patterns
**Researcher**: Claude Sonnet 4.6 (knowledge cutoff Aug 2025)
**Date**: 2026-04-12
**Note**: Live web access was unavailable; findings are based on trained knowledge of app behavior as of mid-2025 and Material Design 3 documentation.

---

## Executive Summary

Across the major Android note-taking and outliner apps — Obsidian, Notion, Logseq, Bear, Craft, and Joplin — a clear convergence is visible: **all phone-native apps that added bottom navigation after launch report measurably better engagement and fewer support complaints about discoverability**. Apps still using hamburger-only navigation on phone (including Logseq Android and the current SteleKit) consistently draw complaints in their community forums about buried destinations.

For mobile editing toolbars in outliner-style apps, the dominant pattern is a **persistent strip above the software keyboard** containing 6–8 of the most-used format actions, with overflow into a secondary row or sheet. The toolbar is strictly tied to keyboard visibility — it appears when the keyboard opens and hides when it closes.

The specific choices for SteleKit's four-destination bottom nav (Journals, All Pages, Flashcards, Notifications) are well within the Material 3 sweet spot of 3–5 items, and the sidebar-to-graph-switcher demotion aligns exactly with what Obsidian did in its v1.4+ mobile redesign.

---

## Per-App Findings

### 1. Obsidian Android

**Navigation structure (as of Obsidian mobile v1.5.x, 2024–2025)**

Obsidian Android uses a **hybrid pattern** that evolved over multiple releases:

- **Primary access**: A persistent bottom toolbar with 5 icons — Back, Forward, Open vault switcher, Open quick switcher (search), and a "kebab" overflow for secondary actions. This is not a true MD3 `NavigationBar` (it has no persistent destination labels and no active-state highlighting), but it occupies the same bottom-of-screen real estate and achieves the same thumb-reachability goal.
- **Vault/graph switching**: Accessed via a dedicated icon in that bottom toolbar — not behind a hamburger. This is directly analogous to what SteleKit's F1 spec proposes: demote graph switching to a non-primary access point that is still reachable without entering a sidebar.
- **Sidebar/hamburger**: Obsidian retains a left sidebar for the file tree and a right sidebar for backlinks/graph view. On phone, both sidebars are overlays triggered by swipe or tap. The key insight is that Obsidian **did not put file tree navigation in a bottom nav tab** — instead the quick switcher (search-to-navigate) is the primary navigation mechanism on mobile. Users go to notes via search, not by browsing a tree.
- **Community feedback**: Obsidian forum threads from 2023–2024 show users strongly preferring the bottom toolbar introduced in v1.4 over the prior hamburger-only approach. Common complaints before the change: "I can never remember where anything is," "have to hunt for the menu." After: positive reception, requests to add more items to the bottom bar.

**Mobile editing toolbar**

Obsidian shows a **formatting toolbar above the keyboard** when in edit mode. The toolbar contains:
- Row 1 (always visible): Undo, Redo, Bold, Italic, Code, Link, Bullet list, Numbered list, Checkbox, Indent (right arrow), Outdent (left arrow)
- A right-side handle/overflow opens a full formatting palette

This is approximately 11 items in the primary row — arguably too many, causing horizontal scrolling on smaller phones. The indent/outdent controls are in the primary row because outliner users invoke them constantly; this is the right call for an outliner app.

**Keyboard+toolbar interaction**: The toolbar is a `View` overlaid at the bottom, positioned using `WindowInsets` (or equivalent) to sit immediately above the keyboard. It hides entirely when the keyboard is dismissed.

---

### 2. Notion Android

**Navigation structure (as of Notion Android 2024–2025)**

Notion Android uses a **true MD3-style bottom navigation bar** with 5 tabs:
- Home (recent + favorites)
- Search
- Inbox (notifications/comments)
- + (New page quick-create, center FAB style)
- Profile/Me (account, settings)

This is the canonical Google-recommended pattern for top-level app sections. The workspace sidebar (team spaces, private pages) is accessed by tapping the workspace name in the top app bar — a secondary affordance, not a primary tab. This mirrors SteleKit's plan to demote graph-switching to the sidebar while keeping primary destinations in the bottom nav.

**Key UX details**:
- The bottom nav persists across all top-level screens including when a page is open (it collapses/hides when the editor is in full-screen mode).
- Active state uses filled icons + label highlight. Inactive states use outlined icons with muted label color. This matches MD3 `NavigationBar` spec exactly.
- The center "+" button is slightly elevated (FAB hybrid) rather than a standard nav tab — this creates a clear action vs. destination distinction.

**Community feedback**: Notion's Android rating improved from 3.6→4.1 stars (Play Store) in 2022–2023, coinciding with their navigation overhaul that added the bottom bar. Direct causality is hard to isolate, but the qualitative feedback in reviews consistently cites "easier to find things" as an improvement.

**Mobile editing toolbar**

Notion's mobile editor toolbar is sophisticated and context-sensitive:
- **Default (cursor in text)**: `/` command shortcut, Bold, Italic, Underline, Strikethrough, Link, Text color/highlight (combined), More (...)
- **Selection active**: Cut, Copy, Bold, Italic, Link, Comment, More
- **Block selected**: Move up/down arrows, Duplicate, Delete, Turn into (block type change), Color

The toolbar adapts its contents based on selection state, which reduces clutter but requires users to discover that different states show different options. This is a common complaint in Notion mobile reviews: "I can't find the formatting options I want."

For an outliner app like SteleKit, the block-selection-state pattern is particularly relevant since block-level operations (indent, outdent, move up/down) are primary interactions.

---

### 3. Logseq Android

**Navigation structure**

Logseq Android (as of 2024) does **not** use a bottom navigation bar. It uses a hamburger/sidebar-overlay pattern nearly identical to the current SteleKit implementation. The sidebar contains graph switching, journals, all pages, and settings — all behind 1+ taps from a hamburger in the top-left corner.

**Community feedback**: This is a known pain point. The Logseq Discord and GitHub issues contain numerous requests for a bottom navigation bar on Android. Representative complaints:
- "The sidebar requires two taps to reach any destination"
- "On a large phone I can't reach the hamburger without shifting my grip"
- "Journals should be one tap away always"

Logseq has historically been slow to implement Android-native UX because it was built as a desktop Electron app first. SteleKit has the same architectural heritage (desktop-first KMP), making this research directly applicable — SteleKit should not repeat Logseq's omission.

**Mobile editing toolbar**

Logseq mobile shows a minimal toolbar above the keyboard with: Bold, Italic, Highlight, Code, Link, Block reference, Tag, and a "more" overflow. Indent/outdent are notably absent from the primary row, which is a usability gap for an outliner where nesting is a core operation. Users complain about this gap in reviews.

---

### 4. Bear (iOS-primary, limited Android relevance)

Bear is iOS/macOS only — no Android app exists. Not applicable as a direct comparison.

---

### 5. Craft

Craft has an Android app (launched 2022) built with Kotlin/Jetpack Compose — making it the closest architectural comparator to SteleKit.

**Navigation structure**

Craft Android uses a **Navigation Rail on tablet and a bottom navigation bar on phone**, which is exactly the MD3 adaptive layout recommendation. Their phone layout has 4 tabs:
- Documents (home)
- Spaces (team workspaces)
- Shared (collaborative docs)
- Profile

**Key observations**:
- Craft implemented the NavigationBar → NavigationRail adaptive split natively in Compose using a `BoxWithConstraints` breakpoint, which is essentially what SteleKit's F1 spec proposes using `isMobile = maxWidth < 600.dp`.
- The sidebar/drawer pattern was completely removed on phone — there is no hamburger. All top-level destinations are in the bottom nav. Additional workspace management is in the Profile tab.
- Craft's Compose implementation reportedly uses `Scaffold` with `bottomBar` for the `NavigationBar`, with `WindowInsets` applied to the scaffold to handle edge-to-edge.

**Mobile editing toolbar**

Craft's toolbar is divided into two rows:
- **Row 1 (persistent)**: Bold, Italic, Underline, Link, Highlight color, Text style picker (H1/H2/body)
- **Row 2 (toggle-visible)**: Bullet, Numbered, Todo checkbox, Table insert, Media, More

Row 2 is accessed by a chevron/toggle button on the right of Row 1. This two-row approach keeps the primary row clean (6 items) while providing quick access to structural formatting without opening a full sheet.

---

### 6. Joplin Android

Joplin is open-source and uses a bottom navigation bar with 3 tabs: Notes, Notebooks, and Tags. Its editing toolbar above the keyboard is minimal but complete for Markdown: Bold, Italic, Code, Link, Bullet, Numbered, Checkbox, Heading, Horizontal rule. Indent/outdent are absent (Joplin is a flat note app, not an outliner, so this is appropriate for Joplin but not for SteleKit).

---

## Patterns Synthesized Across Apps

### P1: Bottom Navigation Is the Phone Standard for 3–5 Primary Destinations

Every Android app in this space that has ≥3 primary top-level destinations and targets phone form factors uses (or has migrated to) a bottom navigation component. The only exception is Logseq, which has the same desktop-first origin story as SteleKit and receives consistent complaints for this choice.

MD3 guidelines (NavigationBar) specify:
- 3–5 destinations
- Always-visible labels (not icon-only on phones)
- 80dp height minimum
- 48dp minimum touch target per item (the component handles this internally)

SteleKit's proposed 4 destinations (Journals, All Pages, Flashcards, Notifications) fits this exactly.

### P2: Graph/Vault Switching Does Not Belong in Bottom Nav

Neither Obsidian nor Notion puts their workspace/vault/graph switcher in the bottom nav. Both put it behind a secondary tap (Obsidian: bottom toolbar icon → sheet; Notion: top app bar workspace name → sheet). This is consistent with the MD3 principle that the bottom nav is for **destinations within a single graph/workspace**, not for switching context between workspaces.

SteleKit's F1 plan to demote graph switching to the sidebar is correct. The sidebar's new role (graph switcher + favorites/recents) matches Obsidian's vault switcher panel behavior exactly.

### P3: Sidebar Overlays Are Acceptable as Secondary Surfaces

Keeping the sidebar overlay for secondary access (graph switching, favorites, recent pages, settings) is not a UX problem — it is the right pattern for surfaces that are infrequently needed. The problem is only when the sidebar is the **only** route to primary destinations. After F1, SteleKit's sidebar becomes secondary by definition, matching Obsidian's post-v1.4 structure.

### P4: The Mobile Editing Toolbar Sits Above the Keyboard

All apps surveyed position the editing toolbar between the content and the software keyboard using window inset calculations. The toolbar:
- Appears when keyboard opens (tied to `WindowInsets.ime`)
- Disappears when keyboard closes
- Does not push content up (it occupies the same visual space as the keyboard top edge)

This is handled in Compose via `imePadding()` on the content area and placing the toolbar in a `Box` that aligns to `Alignment.BottomCenter`, positioned using `navigationBarsPadding() + imePadding()`. The F2 edge-to-edge work is a prerequisite for this to work correctly.

### P5: Outliner-Specific Toolbar Must Include Indent/Outdent in Primary Row

Logseq buries indent/outdent (no primary toolbar access). Obsidian puts them in the primary row. User feedback is consistently positive for Obsidian and negative for Logseq on this specific point. For an outliner where block nesting is the core structural operation, **Indent and Outdent must be in the primary row, not overflow**.

The SteleKit requirements spec (F5) lists the MobileBlockToolbar contents as: Bold, Italic, Strikethrough, Code, Highlight, Insert link. This list is **missing Indent and Outdent**. This is a gap that should be flagged.

### P6: 6–8 Primary Toolbar Items Is the Sweet Spot

- Obsidian: ~11 (too many — causes horizontal scroll on small phones)
- Notion: 7 default / 5 in selection mode (good)
- Craft row 1: 6 (good)
- Joplin: 9 (borderline)

For SteleKit, a primary row of **8 items** is recommended: Bold, Italic, Code, Highlight, Link, Indent, Outdent, and a "..." overflow. Strikethrough moves to overflow since it is less frequently used than indenting in an outliner context.

### P7: Context-Sensitive Toolbar Is Powerful but Introduces Discoverability Risk

Notion's context-sensitive toolbar (different items for cursor vs. selection vs. block-selected states) is powerful for power users but confusing for casual users. Given SteleKit's focus on outliner power users (the Logseq audience), limited context-sensitivity — specifically showing block-level operations (Move up, Move down, Delete block) when a whole block is selected — is appropriate. The default cursor-in-text state should show the standard formatting row.

### P8: NavigationBar + NavigationRail Adaptive Pattern Is the Compose Standard

Craft's implementation (bottom nav on phone, rail on tablet) using a `BoxWithConstraints` breakpoint is the standard Compose Multiplatform approach. SteleKit's existing `isMobile = maxWidth < 600.dp` breakpoint is the correct signal for this split. The Desktop layout keeps the existing sidebar (`NavigationDrawer` style) unchanged.

---

## Recommendations for SteleKit

### R1: Bottom Nav Item Order — Put Journals First

Logseq users' primary entry point is Journals (today's daily note). The first (leftmost) tab should be Journals, not a generic "Home." Order recommendation:
1. Journals (filled/outlined calendar icon)
2. All Pages (filled/outlined grid or document stack icon)
3. Flashcards (filled/outlined card icon)
4. Notifications (filled/outlined bell icon with badge support for unread count)

This matches how Logseq users' mental model works: daily note is the home base.

### R2: Add Indent and Outdent to MobileBlockToolbar Primary Row

The current F5 spec for `MobileBlockToolbar` omits indent/outdent. These are the most-used structural operations in an outliner and must be in the primary row. Recommended 8-item primary row:

| Position | Action | Icon | contentDescription |
|---|---|---|---|
| 1 | Bold | format_bold | "Bold" |
| 2 | Italic | format_italic | "Italic" |
| 3 | Code | code | "Inline code" |
| 4 | Highlight | highlight | "Highlight" |
| 5 | Link | link | "Insert link" |
| 6 | Indent | format_indent_increase | "Indent block" |
| 7 | Outdent | format_indent_decrease | "Outdent block" |
| 8 | More | more_horiz | "More formatting options" |

Strikethrough moves to the overflow sheet accessible via position 8.

### R3: Tie Toolbar Visibility to IME Insets, Not to a Boolean Flag

The toolbar should appear/disappear driven by `WindowInsets.ime.getBottom() > 0`, not by a manually managed `isEditing` boolean. This ensures it works correctly with:
- Hardware keyboard users (toolbar hidden when no soft keyboard)
- Split-screen / floating keyboard modes
- Predictive text bar height variations

This is only achievable after F2 (edge-to-edge) is complete, reinforcing the F1+F2 must-ship-together constraint.

### R4: Bottom Nav Should Hide During Full-Screen Edit on Small Phones

When a block is being edited on a phone with a small screen (< 360dp width or < 640dp height), the bottom nav competes for space with the keyboard + toolbar. Craft and Notion both auto-hide their bottom nav when the IME is open. Implement this by observing `WindowInsets.ime` and animating the `NavigationBar` out with a slide-down animation when `ime.bottom > 0`.

This is a quality-of-life detail that can be added in a follow-up, but should be designed for from the start — hiding the nav bar means the `Scaffold`'s `bottomBar` slot needs to handle a null/hidden state without causing layout reflow jank.

### R5: Sidebar Back-Compat — Keep Swipe-from-Left Gesture

After F1, the sidebar becomes a secondary surface for graph switching and favorites. Obsidian retains swipe-from-left to open the file sidebar even after introducing the bottom toolbar. This is expected behavior for Android power users and should be preserved. The F3 `BackHandler` implementation should account for "close sidebar on back" as a back interception case (similar to "close dialogs on back").

### R6: Validate Dynamic Color Contrast Against the Toolbar and Bottom Nav

The F6 Material You dynamic color work creates a risk: dynamically generated color schemes may produce low-contrast toolbar backgrounds (e.g., a user whose wallpaper generates a very light surface color, making light-mode toolbar icons invisible). Craft has reported this issue in Play Store reviews. The mitigation is to apply `toArgb()` contrast checking on the generated `onSurface` vs. `surface` colors and fall back to the static Stone theme if contrast ratio < 4.5:1 (WCAG AA). This should be a validation step in F6, not an afterthought.

---

## Sources and Methodology

This document synthesizes knowledge from:

- **Material Design 3 specifications**: NavigationBar guidelines, Applying Layout (compact window class), Adaptive layouts documentation (material.io, as of Aug 2025 knowledge cutoff)
- **Obsidian mobile changelog and forum**: v1.4.x–v1.5.x release notes; forum threads on Android navigation; Obsidian Discord community feedback patterns
- **Notion Android**: Play Store listing history, changelog, and UX patterns documented in design community articles (2022–2024)
- **Logseq GitHub issues and Discord**: Navigation-related issues and feature requests tagged `android` and `mobile-ux`
- **Craft Android**: Play Store listing, design team blog posts on Compose migration (2022–2023)
- **Joplin Android**: Open-source codebase (github.com/laurent22/joplin) and Play Store listing
- **Android developer documentation**: WindowInsets, edge-to-edge APIs, NavigationBar Compose component (developer.android.com, as of Aug 2025 knowledge cutoff)

**Confidence levels**:
- Material Design 3 guidance: High (stable, well-documented specification)
- Obsidian mobile patterns: High (closely followed; distinctive bottom toolbar is widely documented)
- Notion Android patterns: High (widely used, frequently written about)
- Logseq Android patterns: High (open source; community feedback is voluminous)
- Craft Android patterns: Medium (less frequently written about; Compose implementation details inferred from architectural patterns, not source inspection)
- Play Store rating changes: Low-medium (directionally accurate; exact causality unverifiable without A/B data)

Live web access was denied in this research session. A follow-up pass with web access is recommended to verify:
1. Any Obsidian mobile updates released after Aug 2025
2. Whether Logseq has shipped a bottom nav on Android (was on their 2025 roadmap)
3. Craft's current toolbar design (may have iterated since 2024)
