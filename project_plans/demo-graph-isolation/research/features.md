# Feature Research: Demo Graph Isolation

**Date**: 2026-07-05
**Feature**: Demo Graph Isolation
**Scope**: JVM desktop + WASM demo graph handling

---

## 1. How Similar Apps Handle Bundled Demo/Example Vaults

### Logseq
- Ships a bundled "demo graph" loaded from classpath resources into an **in-memory database only** — no file-system writes, no path in the registry. The demo is explicitly flagged in ClojureScript state and excluded from the graph picker dropdown.
- Switching away from the demo discards it entirely; the user's real graphs are never contaminated.
- Isolation mechanism: a separate namespace (`logseq.db/demo?`) that gates write paths, prevents persistence, and removes the graph from the switcher list.

### Obsidian
- Ships a "Help" vault bundled inside the app bundle (`/Applications/Obsidian.app/Contents/Resources/help/`). It appears in the vault switcher under a distinct "Help" label but in a visually separate section.
- The help vault is read-only at the filesystem level; Obsidian opens it with no `.obsidian/` config writes.
- Isolation mechanism: filesystem read-only flag + structural separation in the vault list (not the same list as user vaults).

### Notion
- No local demo vault. "Getting started" workspaces are ordinary cloud workspaces pre-populated with template pages. No isolation is needed because cloud syncs overwrite any user content anyway.
- If the user deletes the template pages, they're gone permanently with no "reset" option from the app itself.

### Bear / Roam
- No concept of a demo vault; single-database (Bear) or cloud-only (Roam). Not applicable.

### Key pattern across apps that do it well (Logseq, Obsidian)
- The demo is **never written to the user's real data store**.
- The demo is **flagged or structurally separated** so it does not appear alongside real graphs.
- The demo is **transient by design** — exiting it returns the user to the normal state without a stale registry entry.

---

## 2. Edge Cases the Isolation Feature Must Handle

### Path resolution failures
The current `demoPath = "deps/graph-parser/test/resources/exporter-test-graph"` is a relative path. `graphIdFromPath` calls `fileSystem.expandTilde(path)` but does not make the path absolute. If the user launches the app from a different working directory, the `expandTilde` result changes, producing a different `GraphId` hash — creating orphan SQLite DB files per working directory.

**Required behavior**: The demo path must never produce a stable `GraphId` or a disk-backed SQLite file. Using `IN_MEMORY` backend severs this entirely.

### Registry contamination on restart
`GraphManager.init` → `loadRegistry()` restores persisted `GraphRegistry`. If a demo entry with `isDemo = true` is not filtered, `switchGraph` is called with its `GraphId` at startup. If the demo path no longer resolves (project moved, new install), the app hangs or loads an empty graph. The fix (`loadRegistry` strips `isDemo = true` entries) is already in scope.

### User has BOTH demo and real graph in registry
Without the isDemo flag, the sidebar `GraphSwitcher` shows both. The user can manually switch back to the demo from the switcher even after onboarding is complete. This is confusing because:
- Demo named "exporter-test-graph" looks like a legitimate user graph.
- Any edits to the demo survive in SQLite under a hashed path the user cannot find.
- "Remove graph" dialog says "graph files will not be deleted" — true but misleading for an in-memory demo.

**Required behavior**: Demo must not appear in the switcher once the user has a real graph. Filtering `isDemo = true` from `availableGraphs` in the sidebar is the simplest fix.

### WASM stale registry (`/demo` paths)
Already documented in `Main.kt:118-121`: a prior OPFS-unavailable session stores `/demo` in `graph_registry`. On next visit, OPFS is available and GraphManager tries to `switchGraph("/demo")`, which hangs because `/demo` does not exist in OPFS. The current workaround clears the registry if it contains `/demo`. The `isDemo` flag generalizes this: `loadRegistry` strips all `isDemo = true` entries, removing the workaround.

### User switches from demo to real graph mid-session
With the current code, both the demo (`GraphId` from the relative path) and the new real graph are in the registry. The active graph switches correctly. The demo entry lingers silently. With `isDemo` flag + registry stripping, the demo entry is never persisted in the first place, so there is nothing to linger.

### Demo edit persistence
Current behavior (SQLDELIGHT backend): the demo SQLite DB is created at `getDatabaseUrl(demoGraphId)`. Any edits the user makes to the demo survive session restarts. This creates a "dirty demo" problem — users who delete demo pages or corrupt content have no way to reset.

**Required behavior**: `addDemoGraph()` uses `GraphBackend.IN_MEMORY`. Edits are discarded when the user switches away or closes the app. This matches user mental model ("it's just an example").

### Demo content source on JVM
The current onboarding hardcodes a relative file path (`deps/graph-parser/test/resources/exporter-test-graph`). The requirements specify seeding from `kmp/src/commonMain/resources/demo-graph/` classpath resources — the same source WASM already uses. The classpath source is already present with `pages/`, `journals/`, and `assets/` subdirectories. This removes the dependency on a local checkout path, making the demo work in packaged distributions where `deps/` does not exist.

---

## 3. Users' Unstated Needs

### Expectation: demo resets to pristine state on each launch
Users who explore the demo may delete pages, add blocks, or navigate around. They expect the demo to look like it did the first time if they "try it again." The IN_MEMORY backend satisfies this automatically — there is no persistence, so every cold start sees the original classpath content.

### Expectation: demo is clearly labeled, not a phantom graph
The current display name "exporter-test-graph" looks like a user's own accidentally-registered graph. Users expect clear labels like "Demo Graph" or "Example Vault" — with a visual indicator (badge, different icon, or section header) distinguishing it from real graphs. The `isSynced` badge on `GraphItem` shows the existing precedent for per-graph badges; an analogous `isDemo` badge (or using `Icons.Default.Info` instead of `Icons.Default.Folder`) would convey the distinction.

### Expectation: "Load Demo Graph" button in onboarding does not create a permanent entry
Users who click "Load Demo Graph" to explore the app expect to be able to open their own graph later without the demo cluttering the switcher. They do not expect to explain to a "Remove graph" dialog why they want to remove something they never added intentionally.

### Expectation: demo graph cannot be accidentally edited and synced to git
If a user has git configured and adds the demo to their registry, they might accidentally trigger a git sync on demo content. Isolating the demo to IN_MEMORY and preventing it from appearing in the git-sync flow avoids this.

### Expectation: "Load Demo Graph" works in packaged distributions
Users installing from a `.dmg` or `.deb` do not have a `deps/` directory. The current relative path silently produces a graph with no pages. Seeding from classpath resources fixes this.

---

## 4. Current Graph Switcher — UI Patterns for Special Graphs

### Existing differentiation patterns in `GraphItem` / `GraphSwitcher`
- **Active graph**: `secondaryContainer` background highlight.
- **Git-synced graph**: `Icons.Default.Sync` badge at end of row (via `isSynced: Boolean` prop).
- **Paranoid mode** (`isParanoidMode` on `GraphInfo`): no visual indicator currently — the feature exists at the data layer but the sidebar does not render it yet.
- **Remove button**: hidden when only one graph exists (`availableGraphs.size > 1`).

### What is missing for demo graphs
- No concept of a "system" or "demo" graph in `GraphItem` — the `graph: GraphInfo` parameter contains no `isDemo` field today.
- No separate section in the `DropdownMenu` for special graphs vs. user graphs.
- No visual indicator distinguishing demo from user-added graphs.

### Recommended UI approach (minimal, consistent with existing patterns)
1. Pass `isDemo: Boolean` derived from `GraphInfo.isDemo` into `GraphItem`.
2. Render `Icons.Default.Info` (or a "Demo" chip) instead of `Icons.Default.Folder` when `isDemo = true`.
3. Hide the remove button for demo graphs (no `onRemove` callback when `isDemo = true`).
4. Optionally: filter `availableGraphs` to exclude demo entries once a real graph is active, so the demo never appears in the switcher post-onboarding.

---

## Summary of Key Design Decisions

| Decision | Recommended | Rationale |
|---|---|---|
| Backend for demo graph | `IN_MEMORY` | Prevents all persistence, enables clean reset per session |
| Demo path source | Classpath resources (`demo-graph/`) | Works in packaged distributions; same as WASM |
| Registry persistence | Strip `isDemo = true` on `loadRegistry` | Prevents stale demo entries from hanging startup |
| Demo in graph switcher | Filter out once a real graph exists | Matches user expectation; avoids confusion |
| Demo display name | "Demo Graph" | Self-explanatory; no guessing from directory name |
| Demo graph ID stability | Not required | IN_MEMORY means no DB file to find by ID |
| WASM workaround (Main.kt:118) | Remove after isDemo flag | Flag generalizes the specific `/demo` string check |
