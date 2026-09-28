# Graph Sections — Requirements

## Overview

Graph Sections partition a single logical knowledge graph into named, independently-syncable portions. A user can work with the full merged graph on their primary machine while pulling only a curated subset of sections to a work machine via the WASM build — keeping personal, professional, and domain-specific content compartmentalized without losing cross-section links.

> **Naming note**: The domain type is `GraphSection` (not `Namespace`) because SteleKit already uses `Page.namespace` / `NamespaceUtils` to represent Logseq's `/`-in-page-name hierarchy (e.g. "Tech/Rust/Lifetimes"). The two concepts are orthogonal and must not share a type name. User-facing label: "Sections."

---

## Problem Statement

SteleKit users maintain monolithic knowledge graphs that mix personal notes, work research, domain expertise, and ephemeral scratchpad content. Three pain points emerge:

1. **Work-machine leakage**: A user wants to access their "Technology" and "Work Projects" notes on a work machine but cannot do so without exposing all personal journal entries, financial notes, and private logs.

2. **Bandwidth and storage waste**: The WASM build must currently download the entire graph to access any page. For large graphs (8 000+ pages), this is prohibitively slow.

3. **No separation of concerns at the file level**: All `.md` files live in a flat directory tree. There is no way to declare "these 500 pages belong to 'technology' and may be shared; these other 200 pages are private."

---

## Goals

1. **Section declaration** — users define named sections (e.g., "personal", "work", "tech") by organizing pages into directory subtrees.
2. **Selective clone / sparse fetch** — when opening a graph via the WASM build or a new device, the user opts to sync only specific sections. Pages from other sections are absent (not just hidden).
3. **Local cache for WASM** — the browser persists synced section content in OPFS (Origin-Private File System) so the graph loads offline.
4. **Cross-section links degrade gracefully** — links from a synced section to a page in an un-synced section show a "not available in this section" placeholder rather than a broken link.
5. **Full-merge mode** — on a primary device, all sections are loaded and the graph is indistinguishable from the current single-graph experience.
6. **Git-native storage** — each section maps to a directory subtree within the existing git repo. No new file formats required. Section metadata lives in a single `.stele-sections` config file at the repo root.

## Non-Goals (v1)

- Section-level access control or authentication (that is Paranoid Mode's concern)
- Automatic section assignment / ML categorization
- Cross-repo sections (all sections are within a single git repo)
- Section-level conflict resolution (git merge handles it as usual)
- Mobile (Android/iOS) native sparse clone (desktop + WASM only in v1)

---

## Concepts and Terminology

| Term | Definition |
|------|-----------|
| **`GraphSection`** | A named logical partition of the graph. Each section is a set of pages + a directory path pattern within the graph root. Domain type name; never call it `Namespace`. |
| **Active section set** | The subset of `GraphSection`s active on a device in a session. May be "all" (primary device) or a selected list (work machine, WASM). |
| **Sparse checkout** | A git operation that materializes only specific directory paths from the repo on disk. Used to implement section-selective sync on desktop JVM. |
| **OPFS cache** | Browser Origin-Private File System storage for the WASM build. Stores synced section content for offline access. |
| **Tombstone** | A lightweight placeholder shown at render time when a page from an un-synced section is referenced via a cross-section wikilink. Contains the page title and section name; never written to the database. |
| **Section manifest** | `.stele-sections` TOML file at the graph root. Declares all section names, their directory patterns, and section metadata. |
| **`SectionResolver`** | A render-time component (UI/ViewModel layer) that distinguishes "page genuinely absent" from "page in an inactive section" by consulting the section manifest and the repository. Not a repository-layer concept. |
| **`SectionSyncService`** | Platform-specific (wasmJs expect/actual) component that fetches section content from the git remote via REST API before `GraphLoader` starts. |

---

## Functional Requirements

### FR-1 Section Manifest

**FR-1.1** The section manifest is a TOML file at `{graph_root}/.stele-sections`:

```toml
[[section]]
name = "personal"
description = "Journals and private notes"
paths = ["journals/", "private/"]
color = "#FF5733"

[[section]]
name = "tech"
description = "Technology and engineering research"
paths = ["tech/", "pages/Technology/", "pages/Engineering/"]
color = "#3498DB"

[[section]]
name = "work"
description = "Work projects and meeting notes"
paths = ["work/", "pages/Work/"]
color = "#2ECC71"

[[section]]
name = "default"
description = "All untagged pages"
paths = []          # empty = catch-all for pages not in any other section
```

**FR-1.2** A page belongs to the first section whose `paths` list contains a matching directory prefix for the page's path relative to the graph root. A page not matched by any named section belongs to the `default` section.

**FR-1.3** If `.stele-sections` is absent, SteleKit treats the entire graph as a single `default` section (backward compatible — no behavioral change from today).

**FR-1.4** Sections are created, edited, and deleted via a Section Settings screen in the app. Editing the manifest directly in a text editor is also supported.

**FR-1.5** TOML parsing uses `ktoml-core` (`com.akuleshov7:ktoml-core:0.7.1`) — the only KMP library with confirmed WASM/JS target. Deserialized via `@Serializable` data class with `ignoreUnknownNames = true` for forward compatibility.

---

### FR-2 Section-Selective Graph Loading

**FR-2.1** `GraphManager.addGraph(path, activeSectionSet: Set<String>? = null)` accepts an optional section filter. When non-null, `GraphLoader` only indexes pages whose paths fall within the active sections' directory patterns.

**FR-2.2** The section filter is applied at directory-enumeration time (before `fileRegistry.scanDirectory`), not at parse time. This satisfies NFR-2 (≤ 200 ms overhead) by avoiding I/O for inactive section directories entirely.

**FR-2.3** The file watcher (`ExternalFileChange` flow) is also scoped to the active section paths — changes in inactive sections do not trigger reload events.

**FR-2.4** Pages outside the active section set are not loaded, not stored in the database, and not returned by any repository query.

**FR-2.5** On the primary desktop device, `activeSectionSet` defaults to `null` (all sections), preserving existing behavior.

---

### FR-3 Cross-Section Link Tombstones

**FR-3.1** When a loaded page contains a `[[Page Name]]` wikilink that resolves to a page absent from the DB, the `SectionResolver` checks whether the page's expected path falls within any inactive section's directory patterns. If yes, a tombstone is shown:

- Visual indicator: dashed-underline link with section color (from manifest).
- Tooltip on hover: "Page in section '{section-name}' — not synced to this device."
- Click: opens a "Sync section?" dialog offering to add the section to the active set.

**FR-3.2** When all sections are loaded (primary device, `activeSectionSet = null`), no tombstones appear — links resolve normally.

**FR-3.3** Tombstones are render-time annotations only. They are never written to the database as rows, never indexed by `PageNameIndex`, never returned by `SearchRepository`. The "only loaded pages exist in the DB" invariant is preserved.

---

### FR-4 Sparse Checkout Support (Desktop JVM)

**FR-4.1** When a graph's directory is a git repository and the user selects a subset of sections, SteleKit invokes system git via `ProcessBuilder` (not JGit — JGit's sparse-checkout is broken per Eclipse Bug #383772):

```
git sparse-checkout init --cone
git sparse-checkout set <path1> <path2> ...
```

**FR-4.2** The full pattern set is recomputed on every section toggle (no incremental add/remove) because `git sparse-checkout` has no `remove` subcommand in Git < 2.37.

**FR-4.3** The `.stele-sections` file itself is always included in sparse-checkout regardless of section selection (required to read the manifest).

**FR-4.4** Path patterns from the manifest are translated from graph-root-relative to repo-root-relative using the `wikiSubdir` detected during git-smart-sync repo detection.

**FR-4.5** Sparse-checkout runs in a background coroutine on `Dispatchers.IO` with progress feedback.

**FR-4.6** If git is not installed or the repo is not a git repo, sparse-checkout is silently skipped. Section filtering still applies in-memory.

---

### FR-5 WASM Local Cache (OPFS)

**FR-5.1** The WASM build stores synced section content in the browser's OPFS. Cache key: `{git_remote_url}/{section_name}/`.

**FR-5.2** On first load, `SectionSyncService` (wasmJs actual) fetches pages for the selected sections via the git hosting REST API (GitHub `GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1` → parallel raw-file fetches) and writes them into OPFS.

**FR-5.3** The OPFS sync uses a commit-flag marker file (`.stele-sections-sync-complete`) as an atomic indicator. A tab crash mid-sync leaves the marker absent; the next load detects this and re-fetches the affected section.

**FR-5.4** Tab mutual exclusion uses `navigator.locks.request()` to prevent two tabs syncing the same section simultaneously.

**FR-5.5** On subsequent loads, `SectionSyncService` compares the cached commit SHA against the remote HEAD SHA. If equal, OPFS is used directly. If different, only files changed between the two SHAs are re-fetched (delta sync via `GET /repos/{owner}/{repo}/compare/{old}...{new}`).

**FR-5.6** On first WASM open, the user sees a section selector: a list of available sections (fetched from `.stele-sections` on the remote), each showing name, description, estimated page count, and estimated size. The user confirms before any content is fetched.

**FR-5.7** `navigator.storage.persist()` is called on first write to prevent browser eviction. A re-download path is provided if OPFS data is lost (iOS Safari eviction scenario).

**FR-5.8** Cache size per section is shown in the WASM settings screen. Users can clear individual sections or all cached data.

---

### FR-6 Section Settings UI

**FR-6.1** A "Sections" entry appears in the graph settings sidebar for graphs that have a `.stele-sections` file.

**FR-6.2** The Section Settings screen shows:
- List of all defined sections (name, description, path patterns, page count, color)
- Active/inactive toggle per section (for the current device)
- "Create section" button
- "Edit" and "Delete" buttons per section

**FR-6.3** Creating a section: name, description, one or more path pattern fields, color picker. On confirm: `.stele-sections` is updated and `GraphLoader` re-indexes with the new section mapping.

**FR-6.4** Deleting a section: confirmation dialog warns that pages in the deleted section will be assigned to `default`. Pages are not deleted from disk.

**FR-6.5** Toggling a section inactive: immediately removes its pages from the in-memory repositories. On desktop git repos, also updates sparse-checkout. Links to deactivated pages show tombstones.

---

### FR-7 Section Indicator on Pages

**FR-7.1** A page's section is computed from its path at load time. `Page.section: GraphSection?` is populated by `GraphLoader` using the manifest (a separate field from the existing `Page.namespace` which continues to hold Logseq's slash-hierarchy value).

**FR-7.2** The page list sidebar shows a section color dot next to each page title when multiple sections are active.

**FR-7.3** A filter control in the page list allows showing pages from a specific section only.

---

### FR-8 Full-Merge View (Primary Device)

**FR-8.1** On the primary desktop device with no active section set configured (all sections loaded), the graph loads exactly as today — no behavioral change.

**FR-8.2** A "Section view" toggle in the toolbar switches between merged view and section-filtered view (showing only pages from one selected section at a time).

---

## Non-Functional Requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-1 | Performance | Loading a single-section WASM session (≤ 500 pages) completes in ≤ 10 s on a 10 Mbps connection (first load). |
| NFR-2 | Performance | Section filtering at graph-load time adds ≤ 200 ms overhead for an 8 000-page graph on desktop. |
| NFR-3 | Storage | WASM OPFS cache for a 500-page section uses ≤ 50 MB. |
| NFR-4 | Compatibility | The `.stele-sections` file is valid TOML and human-editable. Format version field enables forward compatibility. |
| NFR-5 | Backward Compatibility | A graph without `.stele-sections` loads identically to today — no user-visible change. |
| NFR-6 | Offline | WASM build operates fully offline once OPFS cache is populated (and not evicted). |
| NFR-7 | Git interop | Sparse-checkout configuration does not corrupt the git index. A full `git pull` from another client on the same repo still works. |
| NFR-8 | Type safety | No code uses `Namespace` as a type name for this feature. All types use `GraphSection` / `SectionManifest` / `SectionResolver`. |

---

## Baseline

- SteleKit already supports multiple simultaneous graphs via `GraphManager`.
- The WASM build (`bazel build //kmp:web_app`) already compiles and runs a functional KMP app in the browser.
- OPFS infrastructure exists (`OpfsInterop.kt`, `WasmOpfsSqlDriver.kt` — from the `stelekit-web-opfs` plan).
- Git remote detection and pull/push exist via `git-smart-sync`.
- `Page.namespace` (Logseq slash-hierarchy) and `NamespaceUtils.kt` are existing; this feature adds `Page.section: GraphSection?` as a separate field.
- Paranoid Mode (disk encryption) is planned separately; sections are orthogonal to encryption.

---

## Acceptance Criteria

| ID | Criterion |
|----|-----------|
| AC-1 | A user creates a "tech" section mapped to `tech/` and `pages/Technology/`. Pages in those directories show a "tech" color dot in the sidebar. |
| AC-2 | A user on the WASM build selects only the "tech" section on first open. Only "tech" pages are fetched and cached. A link from a tech page to a personal-section page shows a tombstone. |
| AC-3 | On a desktop git repo, deactivating the "personal" section triggers sparse-checkout update. `journals/` is removed from disk. |
| AC-4 | Re-activating "personal" triggers sparse-checkout expansion. Journals are restored from the remote. |
| AC-5 | A graph without `.stele-sections` loads identically to the current behavior (no regression). `Page.namespace` (slash-hierarchy) continues to work unchanged. |
| AC-6 | On the WASM build, after OPFS cache is populated, the graph loads without a network connection. |
| AC-7 | A paranoid-mode graph with sections: only the selected section's encrypted files are synced to the WASM build; decryption happens in-browser after section selection and vault unlock. |
| AC-8 | `grep -r "GraphSection\|SectionManifest\|SectionResolver" kmp/src` produces matches; `grep -r "class Namespace\|data class Namespace\|sealed.*Namespace" kmp/src` produces zero matches for this feature's types. |
