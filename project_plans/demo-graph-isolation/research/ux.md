# UX Research: Demo Graph Isolation

**Date**: 2026-07-05
**Feature**: Demo Graph Isolation (ephemeral in-memory demo during onboarding)

---

## 1. Comparable App Precedents

### Logseq
Logseq's browser-based demo is a **session-only** graph; it does not appear in the graph switcher at all. In the desktop app the graph switcher lists only real local directories. When a user wants to explore without a vault they use a dedicated "Open a local graph > Pick a demo" flow that is separate from the switcher. The net effect: users never see "Demo Graph" as a persistent entry — it simply goes away when they close the app or open a real graph. This is the closest precedent to what SteleKit is implementing.

### Obsidian
Obsidian ships a **"Help"** vault that does appear in the vault switcher. It is a real on-disk vault created in the app support directory and labeled "Help" (not the folder's literal directory name, which is `Obsidian Help`). The vault-switcher entry is visually identical to user vaults — no badge, no special styling — but its name is unique enough that users recognize it. Obsidian also has a "Sandbox" vault mode (web version only) that is in-memory and explicitly excluded from the vault list. The pattern: **on-disk bundled content appears in the switcher; in-memory ephemeral content does not.**

### Notion
Templates are never workspaces in the Notion switcher. Template content is copied into a real workspace on import. Not applicable as a direct comparison.

### Summary Principle
The industry norm is: **ephemeral / in-memory demos are omitted from the persistent switcher; on-disk demos appear in the switcher but may carry a distinct label.** Because SteleKit's demo will be `IN_MEMORY` and cleared on restart, the Logseq / Obsidian-web pattern — omit from switcher after session ends — is the right precedent.

---

## 2. SteleKit Graph Switcher — Current UI Analysis

Source: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

The `GraphSwitcher` composable renders:
1. A collapsed **current-graph pill** (`primaryContainer` background, folder icon + `displayName` + expand chevron).
2. On tap: a `DropdownMenu` listing each `GraphInfo` via `GraphItem` (folder icon + `displayName` + optional sync icon + optional delete `IconButton`).
3. A divider, then "Open local folder…" and "Clone from URL…" action items.

**"(Demo)" badge approach** — how it would look:
- Add a small `Badge` or `SuggestionChip` composable immediately to the right of the `displayName` text inside `GraphItem`, styled with `MaterialTheme.colorScheme.tertiaryContainer` / `onTertiaryContainer` to differentiate from the active-graph `secondaryContainer` highlight.
- The current-graph pill would read "Demo Graph  [Demo]" (badge inline).
- The delete `IconButton` should be suppressed for `isDemo = true` graphs (replacing the delete action with a "Close demo" gesture or omitting it entirely, since removal is automatic on restart).
- Accessibility: the existing `contentDescription` string on the pill reads `"Graph: $currentGraphName, expanded"` — this must be extended to `"Graph: Demo Graph (demo), expanded"` to convey the ephemeral nature to screen-reader users without relying on the badge color alone.

**"Omit from switcher" approach** — how it would look:
- Filter `graphRegistry.graphs` in `LeftSidebar` before passing to `GraphSwitcher`: `availableGraphs = graphRegistry.graphs.filter { !it.isDemo }`.
- The switcher never shows the demo entry; the current-graph pill shows "Demo Graph" while the demo is active, but the dropdown does not list it (preventing re-selection or confusion about persistence).
- A separate "You are viewing the demo" banner or `InfoCard` inside the sidebar body (above the nav items) communicates the ephemeral state to the user without polluting the switcher list.

**Recommendation**: The **omit-from-dropdown + in-session sidebar notice** approach is cleaner. It avoids a half-entry in the switcher that disappears on restart, and it sets honest expectations. The current-graph pill still displays "Demo Graph" so the user always knows what they are looking at.

---

## 3. User Mental Model for Ephemeral Demo Graphs

### Will users expect the demo to persist in the switcher?
Probably not on first launch, but if they switch away to a real graph and want to return to the demo within the same session, they will look in the switcher. If the demo is omitted from the dropdown, they have no path back. Two mitigations:
1. **Within-session**: keep the demo entry visible in the dropdown only while the demo `RepositorySet` is alive (i.e., during the current run). Filter it out on next startup when `isDemo = true` entries are stripped.
2. **Inform the user**: a dismissible banner ("Exploring the demo — your changes won't be saved") inside the main content area prevents confusion.

### Will disappearance on restart confuse users?
Yes, if they expect the graph to re-appear. The onboarding copy ("Load Demo Graph") must set expectations: e.g., "Explore sample content — this graph is temporary and won't be saved." A single sentence is sufficient.

### Preferred label
- **"Demo Graph"** — matches the onboarding button label exactly, minimal cognitive overhead.
- "SteleKit Demo" adds branding but is longer and inconsistent with the button text.
- "Example Graph" is generic and mismatches the button.
- Decision: use **"Demo Graph"** for the `displayName`.

---

## 4. Error States

If classpath resources fail to load (bad build, missing jar entries):

- **Do not show an error screen as the first thing the user sees.** The user clicked "Load Demo Graph" from onboarding and expects to enter the app.
- Preferred: `addDemoGraph()` returns `Either<DomainError, GraphId>`. On `Left`, the `App.kt` call site emits a snackbar: `"Demo content failed to load — starting with an empty graph."` and falls back to creating the demo as an empty in-memory graph (no content, but the app is still usable).
- Do **not** fall back to the onboarding screen — that would confuse users who just left it.
- If the in-memory graph itself cannot be created (extreme failure), fall back to standard onboarding (with an error snackbar explaining what happened).

---

## 5. Accessibility

A "(Demo)" badge is WCAG 2.1 AA compliant provided:

1. **Not color-only**: the badge must contain visible text ("Demo"), not just a color dot. The current `SyncStatusBadge` implementation in `Sidebar.kt` uses an icon without text — that pattern would need text or `contentDescription` to convey "demo" to screen readers.
2. **`contentDescription` on the pill**: the `GraphSwitcher` Surface already sets `contentDescription = "Graph: $currentGraphName, tap to switch graph"`. For demo graphs this must include the demo designation: `"Graph: Demo Graph (demo), tap to switch graph"`.
3. **Contrast**: `tertiaryContainer` / `onTertiaryContainer` from Material3 dynamic color is guaranteed ≥ 3:1 contrast for non-text UI components and ≥ 4.5:1 for the badge text, so the color pairing is safe.
4. **Focus order**: the badge is decorative (inert, no click target), so it does not need to be a separate focusable node — embedding it inside the existing `GraphItem` `Row` is correct.

No unique WCAG concerns beyond the above. The existing pattern in `GraphItem` (role, contentDescription via the parent `Surface`) is sufficient if extended to mention "(demo)" in the semantic description.

---

## Decision Summary

| Question | Recommendation |
|---|---|
| Show demo in graph-switcher dropdown? | No — omit from dropdown; show in current-graph pill only |
| Display name | "Demo Graph" |
| How to communicate ephemeral state | Dismissible in-app banner + onboarding copy update |
| Error on classpath failure | Snackbar + empty in-memory graph fallback (do not return to onboarding) |
| Accessibility | Extend `contentDescription` to include "(demo)"; badge text must be visible not color-only |
