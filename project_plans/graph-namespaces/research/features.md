# Graph Namespaces — Feature Research & Prior Art

## 1. Logseq Namespaces

### How It Works

Logseq uses `/` embedded in page names as a namespace separator — `[[Work/Project Alpha]]`
creates a single page titled `Work/Project Alpha`. This is **purely logical**: in the legacy
file-graph (Markdown mode), all pages live in a flat `pages/` directory and the slash is
percent-encoded (`Work%2FProject Alpha.md`). No subdirectory is created on disk.

### Key Difference from SteleKit's Directory Approach

| Dimension | Logseq (logical namespaces) | SteleKit (directory namespaces) |
|---|---|---|
| Disk layout | All pages flat in `pages/`; slash encoded | Pages live in actual subdirectories |
| External tools | See confusing encoded filenames | Normal directory tree; git-native |
| Sparse checkout | Cannot be used (no real dirs) | Native fit — directories map 1:1 |
| Rename cascade | Automated but fragile on large graphs | Rename a dir; git handles history |
| Hierarchy | Inferred from page name prefix | Inferred from actual file path |

### UX Patterns Worth Adopting

- **Hierarchy panel**: Below linked references on any namespaced page, Logseq shows
  parent/siblings/children — lateral navigation without needing a sidebar tree. Directly
  applicable to SteleKit's namespace color dot + filter concept.
- **`{{namespace ROOT}}` macro**: Auto-updating index of all pages under a namespace.
  SteleKit's namespace settings screen (FR-6) could generate a similar "namespace index"
  page.
- **Search autocomplete scoping**: Typing a namespace prefix in `[[]]` filters autocomplete
  to that namespace. SteleKit should support `[[tech/` as a namespace-scoped search hint.
- **DB-graph structural storage**: Logseq's new DB graph decouples page title from namespace
  identity — pages can be moved between namespaces without renaming. SteleKit's path-based
  approach provides a similar effect (move a file, its namespace changes).

### Pitfalls to Avoid

- **Premature taxonomy trap**: Experts warn against heavy namespace use early. SteleKit
  should make creating/restructuring namespaces cheap — avoid one-time migration pain by
  ensuring FR-1.4 (editing manifest) and FR-6.4 (delete with default fallback) are smooth.
- **No namespace filter in graph view**: Logseq has no first-class "show only this namespace"
  in its graph visualization. FR-7.3 (filter control in page list) addresses this gap.

**Sources:** Logseq Mastery blog on namespaces; Logseq DB version changes docs.

---

## 2. Obsidian Vaults

### Vault Model

Each Obsidian vault is a self-contained directory with its own config, plugins, hotkeys, and
theme. One Obsidian instance opens exactly one vault at a time — no native multi-vault view.

### Switching UX

Vault switching is a full context swap (app reloads, not a tab switch). Two clicks on
desktop, three on mobile — a known friction point with sustained forum requests for
improvement. A custom `[[VaultName::NoteTitle]]` link syntax via the Multi-Vault Navigator
community plugin provides read-only cross-vault navigation; no native solution exists.

### What SteleKit Does Better

Obsidian's strict vault isolation is **intentional but well-documented as friction**:

- **Lost serendipitous connections**: Backlinks and graph only work within one vault — a work
  project note never surfaces unexpected links to personal research. SteleKit's namespace
  model avoids this by keeping all namespaces in one graph (FR-8 full-merge on primary device).
- **Per-vault plugin overhead**: Every vault requires plugins installed, configured, and
  updated independently. SteleKit namespaces share one app instance.
- **Broken cross-vault links**: Moving a note between vaults breaks all inbound links with no
  repair. SteleKit's tombstone approach (FR-3) is a direct improvement over Obsidian's
  silent breakage.

### Within-Vault Context Filtering Patterns

Obsidian's **Workspaces** (core plugin) saves named layout snapshots (open files, split
panes, sidebar state). This is an analogue to SteleKit's "namespace view" toggle (FR-8.2) —
consider whether a namespace selection could also save/restore a named workspace layout.

### Community Consensus

Most experienced Obsidian users recommend one vault + folders/tags, not multiple vaults,
splitting only for concrete reasons (shared team vault, encrypted personal vault). This
validates SteleKit's approach of a single graph with namespace filtering rather than truly
separate graphs.

**Sources:** Obsidian Help docs; Obsidian Forum threads on vault switching and merging.

---

## 3. Notion Workspaces

### Compartmentalization Model

Notion has two layers: workspace (top-level, billing-scoped) and teamspaces (department-level).
Teamspaces have three visibility modes: Open, Closed, and Private (invisible to non-members).
The unit of selective visibility is the teamspace or individual page — not a database row.

### Key UX Pattern: Linked Database Views

The primary mechanism is the **linked database view**: a live projection of a master database
with its own filters, sorts, and layout — no data duplication. Common pattern: per-member
dashboard pages filtered to `Assigned to = [member]`.

**Analogy for SteleKit**: The namespace selector (FR-6) could offer "linked views" per
namespace — a namespace dashboard page auto-generated showing all pages in that namespace,
without duplicating content. The `.stele-namespaces` manifest (FR-1) already supports the
data model for this.

### Partial Access UX — A Notable Gap in Notion

Notion has **no native row-level permissions**. Sharing is all-or-nothing at the database
level. Workarounds are manual and fragile. SteleKit's tombstone approach (FR-3) is
architecturally superior — it provides a third state between "full access" and "no access":
you can see _that_ a page exists and which namespace it belongs to, even if you can't read it.

### Progressive Disclosure Patterns

- **"Load more" in databases**: Views show a fixed initial row count with a "Load X more"
  button — not infinite scroll. SteleKit's WASM namespace selector (FR-5.4) should follow
  this pattern: show namespace metadata and page count, reveal content only after selection.
- **Sidebar panels**: Bounded scroll depth with search — mirrors SteleKit's page list with
  namespace filter (FR-7.3).

**Sources:** Notion Help docs; Thomas Frank's permissions guide; Notion 2024-06-11 release notes.

---

## 4. Git Sparse Checkout vs. Worktrees

### Verdict: Sparse Checkout in Cone Mode Is the Right Choice

Sparse checkout (Git 2.25+, improved 2.41+) restricts which files appear in a working
directory via `CE_SKIP_WORKTREE` bits. All remote operations — pull, push, fetch, remote
tracking — are fully transparent. Every commit contains the complete tree; only the local
working copy is filtered.

**Cone mode** (what FR-4.1 specifies) uses full directory prefix patterns with O(1) hash-map
lookups, enabling a sparse-index optimization that collapses excluded directories to single
tree entries — dramatically faster on large note graphs. Non-cone mode (gitignore-style
wildcards) is O(N×M) and explicitly warned against for large trees.

### Why Not Worktrees

Git worktrees create additional working directories sharing one `.git/objects` database, each
on a potentially different branch. They do not add value for the namespace use case:

- Namespaces are not branches — they are directory-level filters on the same branch
- Worktrees add branch-level isolation that is not needed or desired here
- Sparse checkout and worktrees compose (you can apply sparse patterns inside a worktree),
  so worktrees can be added later if branch isolation is needed (e.g., for draft namespaces)

### Key Insight: Git Index Enables Tombstones Without Files on Disk

The git index always tracks all paths regardless of sparse-checkout configuration. An
application can query `git ls-files --sparse` or read the index directly to know that a file
exists but is not checked out. This means SteleKit can detect cross-namespace links and
generate tombstones (FR-3) by checking the index — without materializing the files.

### Known Footguns

| Risk | Mitigation |
|---|---|
| Unquoted glob in `git sparse-checkout set` → shell expansion → missing files | Always pass paths as string arrays from JVM; never invoke via shell interpolation |
| No `remove` subcommand — only `add` | Track the active namespace set explicitly; recompute full pattern list on every change rather than incrementally |
| Merge conflicts force full-index materialization (temporary perf hit) | Acceptable — conflicts are infrequent; document the behavior |
| Stale worktree metadata if dir deleted with `rm -rf` | Not applicable to our use case (no worktrees) |

### Prior Art

- **GitHub Actions `actions/checkout`**: Ships native `sparse-checkout` input — validates
  that sparse checkout is production-stable for CI monorepos.
- **Sparo** (TikTok OSS): Sparse-checkout wrapper that resolves transitive dependencies —
  the transitive dependency concept is analogous to namespace dependency resolution.
- **Microsoft VFS for Git**: Powers the 300 GB Windows OS repo — sparse checkout at extreme
  scale.

**Sources:** git-sparse-checkout official docs; GitHub Blog monorepo post; DeepWiki internals.

---

## 5. WASM Progressive Loading

### How Large WASM Apps Handle Big Content Sets

**Figma**: Cut load time from 29s to under 8s using `WebAssembly.instantiateStreaming`
(instantiates while bytes are still downloading), incremental document fetching, and
optimized C++/JS bindings. The key lesson: stream the WASM binary itself; do not wait for
full download before compilation.

**Autodesk APS Viewer** (most directly relevant): Three-layer caching:
1. Progressive rendering for fast first pixels
2. Chunked geometry streaming
3. OPFS geometry caching — 2–7x speedup on subsequent loads, default-on since v7.98

The Autodesk pattern maps directly to SteleKit's FR-5: OPFS as the local cache, delta-fetch
on subsequent loads, user-selected subset only.

### OPFS Is the Right Backend

OPFS (Origin-Private File System) provides:
- **Synchronous byte-level access on a Worker thread** via `createSyncAccessHandle` — no
  serialization overhead that hurts IndexedDB at scale
- **No permission prompt** — origin-sandboxed, automatically available
- **Browser support**: Chrome/Edge 86+, Firefox 111+, Safari 15.2+ — universally supported
  including iOS as of 2025
- **Storage quota**: Dynamic, from device free space (typically 300 MB to several GB).
  Queryable via `navigator.storage.estimate()`; persistent via `navigator.storage.persist()`

Lumafield (CT scan viewer) reported 3x faster project loads switching from IndexedDB to OPFS
for large binary data. RxDB explicitly documents severe Chrome IndexedDB performance
degradation at scale. **OPFS is unambiguously the right choice for FR-5.**

### Namespace-Selective Loading Pattern

The established pattern composes three primitives:
1. **Named OPFS directories per namespace** — keyed as `{remote_url}/{namespace_name}` (as
   in FR-5.1)
2. **Service Worker with named cache partitions** — intercepts reads; returns OPFS content
   offline; fetches delta from network when online
3. **Namespace selector before first fetch** (as in FR-5.4) — show metadata + page count,
   confirm selection, then fetch only selected namespaces

This mirrors the enterprise OneDrive/SharePoint model: hierarchy metadata syncs on join;
file bodies download on first access (or on explicit namespace selection).

### SQLite WASM Is Directly Applicable

The official SQLite WASM port uses OPFS as its persistence backend. Since SteleKit uses
SQLDelight (which can target SQLite), the path to an OPFS-backed SQLDelight driver on WASM
is well-trodden. TiddlyWiki already uses OPFS via File System Access API for in-browser
saving (`tiddlystow`).

**Sources:** Figma blog; Autodesk APS OPFS post; MDN OPFS; web.dev OPFS; RxDB benchmarks.

---

## 6. Cross-Namespace Links in Federated Wikis

### Summary Table

| Tool | Cross-Instance Links | Missing Link UX |
|---|---|---|
| TiddlyWiki | URL-only; no interwiki table | Blue link opens blank editor (no signal) |
| DokuWiki | Namespace-local; no external federation | Red link (identical to missing local page) |
| MediaWiki | Interwiki table; no remote existence check | Red = local missing; Blue = remote (no existence signal) |
| Federated Wiki (Ward Cunningham) | Fork-based; ghost pages from origin | Ghost stub with visual dimming — only genuine tombstone UX |
| Logseq / Roam / Athens | Graph-local only | "Block not found" inline or raw UUID |

### SteleKit's Tombstone Design Is Best-in-Class

No existing tool performs remote-existence validation before rendering a cross-instance link,
and no tool in the Logseq/Roam family supports cross-graph references at all. SteleKit's
proposed tombstone (FR-3) — dashed-underline link with namespace color, hover tooltip
identifying the namespace, click-to-sync — is more informative than any existing prior art:

- **MediaWiki red links**: Signal "missing" but not "in another namespace" — no namespace
  identity, no action path.
- **Federated Wiki ghost pages**: Closest analogue — visually dimmed content pulled from the
  origin fork. Good inspiration for the visual treatment. FedWiki is the only tool that
  renders actual content from the other instance; SteleKit intentionally does not (content
  is absent for privacy).
- **Obsidian unresolved links**: Muted/grayed with hover text "page not found" — no
  distinction between "never existed" and "not synced here."

### Design Recommendations from Prior Art

1. **Distinguish "not synced" from "does not exist"**: MediaWiki's red links collapse both
   states into one. SteleKit must maintain separate visual states: tombstone (exists in
   another namespace, not synced) vs. red link (page does not exist anywhere).
2. **Show namespace identity in the tombstone**: No existing tool does this. The namespace
   color dot + tooltip ("Page in namespace 'personal' — not synced") is a novel, high-value
   UX.
3. **Actionable click**: FedWiki's ghost page is navigable; MediaWiki red links go to an
   editor. SteleKit's "Sync namespace?" dialog is the most useful action — it converts the
   tombstone into a real link. This is the differentiating UX moment.
4. **Avoid orphan proliferation**: TiddlyWiki's blue-link-to-blank-editor creates zombie
   tiddlers. SteleKit's tombstone should never auto-create placeholder pages — only render
   the visual placeholder inline.

**Sources:** TiddlyWiki docs; DokuWiki syntax docs; MediaWiki interwiki manual; FedWiki design.

---

## Cross-Cutting Insights

### What the Field Gets Wrong That SteleKit Can Fix

1. **Hard binary access**: Every tool offers either full access or no access. SteleKit's
   tombstone is a third state: "acknowledged absence with identity." This enables serendipity
   (you know the referenced page exists and where) without leaking content.

2. **Vault/workspace proliferation**: Obsidian and Notion users repeatedly reach for
   separate vaults/workspaces and then regret the lost cross-context connections. SteleKit's
   single-graph model with namespace filtering directly addresses this.

3. **Flat file chaos at scale**: Logseq's percent-encoded flat file layout is the canonical
   failure mode for large logical namespaces. SteleKit's directory-based approach is
   strictly better for git, for external tools, and for sparse checkout.

4. **WASM note apps don't use OPFS yet**: No mainstream note-taking WASM app (Logseq
   included) has shipped OPFS-backed offline caching. This is a first-mover opportunity.

### Risks to Monitor

- **git sparse-checkout cone mode requires Git 2.26+**: Verify the minimum git version on
  target desktop platforms. macOS ships Git 2.39+ via Xcode tools; Linux distros vary.
- **OPFS storage eviction on iOS Safari**: Safari evicts OPFS under storage pressure even
  with `persist()` called on iOS (as of 2024). The UI should warn users and offer a
  re-download path rather than silently showing stale data.
- **Namespace rename cascade**: If a user renames a namespace from "tech" to "technology",
  the directory patterns in `.stele-namespaces` update, but git sparse-checkout must be
  reconfigured and pages must be re-indexed. Design this as an atomic operation with rollback.
