# Graph Namespaces — Pitfalls & Risks

Research date: 2026-06-24

---

## 1. Git Sparse-Checkout Footguns

### 1.1 Cone mode vs. non-cone mode ambiguity

FR-4.1 specifies `git sparse-checkout init --cone`, which uses the faster, path-prefix–only "cone" mode. Cone mode has a hard constraint: **only top-level directory prefixes are valid**; arbitrary glob patterns (e.g., `pages/Technology/`) are silently mis-handled in older git versions or require the `--no-cone` flag. A namespace defined as `paths = ["pages/Technology/", "pages/Engineering/"]` consists of directories nested two levels deep, which cone mode supports — but `pages/` itself would also be materialized as a root. This is intentional per the cone semantics (the parent directory is always included as metadata), but it means `pages/Work/` and `pages/Technology/` cannot be independently selected without materializing all of `pages/` as a directory stub. Stale worktree contents from previous cones can be left on disk if `git sparse-checkout set` is called while the worktree has local modifications — git refuses to update sparse paths with dirty files, so the deactivated namespace files may remain present and be re-indexed on next load.

**Mitigation:** Always run `git stash` or assert a clean worktree before calling `git sparse-checkout set`. Surface a "pending edits must be committed before changing namespace selection" warning in the UI (FR-6.5 already implies this but it must be explicit in the implementation).

### 1.2 `.stele-namespaces` must always be in the cone

The `.stele-namespaces` file lives at the repo root. In sparse-checkout cone mode, the repo root is always checked out (only subdirectories can be omitted), so `.stele-namespaces` is always present. This is safe. However, **any auxiliary config files that live within a namespace directory** (e.g., `tech/.stele-config`) would disappear when that namespace is deactivated. Do not place per-namespace config inside namespace directories; keep all config at the root.

### 1.3 File renames across namespace boundaries

When a page is moved (renamed) from `tech/` to `personal/` and then synced, git sees a rename. A client that has only the `tech` namespace active will see a delete (the file disappears from sparse-checkout) but not the corresponding create in `personal/`. The page will appear deleted from the database on the next sync, with no tombstone. This is a silent data-loss footgun — the page is still in the repo, just in an un-synced namespace. **Git does not deliver the renamed-to path to a sparse-checkout client that doesn't include the destination.**

**Mitigation:** Tombstone generation must be driven from the manifest, not from the filesystem. On sync, diff the page-name index against the manifest's expected paths; pages that disappear from a loaded namespace without a corresponding delete in the page's authoritative namespace should emit a tombstone, not a permanent delete.

### 1.4 `.gitattributes` and merge driver interaction

If a custom merge driver is specified for `.md` files via `.gitattributes`, it applies to all checked-out files regardless of namespace. If the merge driver is installed on the primary machine but not on the WASM/secondary client, sparse merges of the selected namespaces will fall back to the default three-way merge (or fail). The existing `JournalMergeService` (referenced in `GitSyncService`) is likely relevant here. Verify that the merge driver is namespace-agnostic and does not assume full-graph context.

### 1.5 Submodule interaction

The requirements do not mention submodules, but if a user has a git submodule inside a namespace directory (e.g., `tech/papers/` as a submodule), sparse-checkout does not recurse into submodules. The submodule pointer (`.gitmodules` entry + commit hash file) would be present in the cone, but the submodule contents would not. `GraphLoader.loadDirectory` would see the empty directory and silently produce no pages. This is correct behavior but should be documented.

---

## 2. OPFS Cache Invalidation

### 2.1 No atomic two-phase write

The current `OpfsInterop.kt` (`opfsWriteFile`) uses `createWritable()` → `write()` → `close()` with no transaction or journal. If the browser tab is closed between `write()` and `close()`, the OPFS file will be left in whatever partial state the browser decides — in practice, the underlying write is not atomic across the file boundary. A 500-page namespace sync writes ~500 individual file operations; a crash at file 250 leaves a partially-populated cache that the app cannot distinguish from a complete cache on next load.

**Mitigation:** Write a manifest file (e.g., `{namespace}/.sync-manifest.json`) as the **last** file after all page writes complete. On startup, verify the manifest before treating the cache as valid. If the manifest is absent or its page count doesn't match the actual file count, treat the cache as dirty and re-sync. This is essentially a write-ahead log at the namespace level.

### 2.2 Race between tab instances

OPFS has no cross-tab locking primitive in the standard API (unlike `SharedWorker` + `BroadcastChannel`). If the user opens SteleKit in two tabs simultaneously and both attempt to sync the same namespace, the second write to any given file will silently overwrite the first. Two tabs writing different deltas from different git fetches (possible if the remote advances during sync) will produce a corrupted cache.

**Mitigation:** Use `navigator.locks.request()` (Web Locks API) keyed by namespace name before entering any OPFS write session. Web Locks are supported in all modern browsers and provide exclusive or shared locking across same-origin contexts including tabs.

### 2.3 Origin isolation and SSL

OPFS is scoped to the origin (`scheme://host:port`). If SteleKit is served from different origins (e.g., a version-tagged subdomain, or HTTP vs. HTTPS), the OPFS cache is invisible from the new origin. Users who switch from `app.stelekit.dev` to `v2.stelekit.dev` will re-download all namespace data. Document the cache key (`{git_remote_url}/{namespace_name}` per FR-5.1) explicitly and ensure the app's origin is stable.

### 2.4 Delta sync correctness

FR-5.3 says: "if the remote has newer commits, it fetches only the delta (updated files)." This requires computing which files changed between the last-synced commit hash and `HEAD`. If the commit hash stored in the sync manifest is wrong (e.g., a partial sync was interrupted and the manifest was written with the old hash), the delta will be empty and stale data will persist. The sync manifest must record the commit hash **before** fetching, then the per-file writes, then update the commit hash **after** all writes succeed. Any deviation from this order risks stale-cache silences.

### 2.5 OPFS quota and eviction

Browsers apply storage quotas to OPFS (same bucket as the site's storage). On a storage-constrained device, the browser may evict OPFS storage without notice (though in practice this is rare for OPFS vs. Cache API). FR-5.5 (cache size display + clear) is important for the user, but the app must also handle `QuotaExceededError` gracefully during writes rather than crashing.

---

## 3. Cross-Namespace Links and Search

### 3.1 PageNameIndex includes un-synced page names — tombstone must not create a link

`PageNameIndex` (`domain/PageNameIndex.kt`) is rebuilt from `PageRepository.getPageNameEntries()`. In a namespace-filtered session, only loaded namespaces contribute pages to the repository, so the matcher will not contain names from un-synced namespaces. This means the auto-link suggestion feature will not offer cross-namespace links — **correct behavior** and a useful side effect of the existing architecture.

However, if a tombstone row is written to the database for a cross-namespace page (to preserve the placeholder), its name will appear in `getPageNameEntries()` and be added to the matcher. This causes the auto-linker to suggest creating links to a tombstone page. **Do not store tombstones as Page rows.** Tombstones must be a pure render-time annotation, resolved from the page reference table + the namespace manifest, with no database row.

### 3.2 Search returns tombstone content

FR-2.2 states: "Pages outside the active namespace set are not loaded, not stored in the database, and not returned by any repository query." If this invariant holds, search is automatically correct. The risk is in any code path that populates the database with tombstone stubs — see §3.1. Verify that `SearchRepository` has no special tombstone-bypass path.

### 3.3 Backlink counts are wrong by design

If namespace B is not loaded, pages in B that contain wikilinks to pages in namespace A are not indexed. Therefore A's backlink count from B's pages is zero on the partial-load client. This is **unavoidable** given the design (un-synced pages are not present). The requirements say cross-namespace links degrade gracefully (FR-3.1), but they do not say backlinks degrade gracefully. The backlink count displayed in the UI for a page in a loaded namespace should carry a qualifier: "N backlinks in synced namespaces." Displaying a bare count without qualification implies completeness and is misleading.

### 3.4 Link resolution uses page name, not path

`BacklinkRenamer.kt` and the wikilink resolution logic use page **names** (not file paths) as the join key. A page's name is set at parse time from its filename. If two pages in different namespaces share the same name (e.g., `tech/index.md` → page "Index" and `personal/index.md` → page "Index"), only one can win the name-keyed lookup. The requirements do not address namespace-qualified page names (e.g., `tech::Index` vs `personal::Index`). This is a naming collision risk, especially for common page names like "TODO", "README", "Index", "Templates". Consider requiring namespace-qualified names for cross-namespace disambiguation, or enforcing uniqueness within a graph across all namespaces.

### 3.5 Cross-namespace link resolve-time must not do full graph scan

When rendering a wikilink to detect whether its target is in an unloaded namespace, the renderer must not call `getPagesByNames()` against the full DB — this is O(1) per-page under the current design since un-loaded pages have no DB row. The check is: "does this wikilink resolve to a known page?" If no → check the namespace manifest to see if the target's expected namespace is declared but not active → render tombstone. The manifest check must be O(1) (hash map of known cross-namespace page titles is impractical at scale; instead, tombstone detection should be deferred to a manifest-driven fallback after a failed page lookup, not a pre-scan).

---

## 4. Namespace Rename and Restructure

### 4.1 Page ownership flip on manifest edit

FR-1.2 states: "A page belongs to the first namespace whose paths list contains a matching directory prefix." If the user edits `.stele-namespaces` and changes path patterns, pages that were in namespace A are now in namespace B — **instantaneously, on the next re-index.** On a partial-load client that only has B active, these pages will suddenly appear. On a client that only has A active, those same pages will disappear (because they are no longer in A's path patterns). Neither client has taken a git action; the manifest change is the only trigger. This is correct behavior but is user-surprising and can feel like data loss.

**Mitigation:** When the manifest is modified, diff the old vs. new namespace assignment for each loaded page and emit a "N pages moved from namespace A to namespace B" notification before re-indexing.

### 4.2 Tombstones become stale after namespace rename

If namespace "tech" is renamed to "technology", all tombstones that reference namespace "tech" become stale — they now point to a non-existent namespace name. Tombstone data (if stored) must be keyed to the canonical namespace name, and any rename must sweep tombstone references. Since tombstones should be a render-time concern (see §3.1), this is less critical, but the namespace manifest itself must carry a stable `id` field distinct from the human-readable `name`, so manifest-driven lookups are ID-stable.

**Risk for v1:** The requirements as written do not include a stable `id` field in the manifest (FR-1.1 only has `name`, `description`, `paths`, `color`). Renames will break any persisted reference to a namespace by name. Add an immutable `id` field (UUID or slug set at namespace creation).

### 4.3 Sparse-checkout state after namespace rename

If the user renames namespace "tech" to "technology" but the namespace's `paths` are unchanged (still `["tech/"]`), the sparse-checkout configuration is still valid. But if the namespace name was used to label the sparse-checkout cone, there may be a stale cone label. `git sparse-checkout set` is idempotent on paths, so re-running it with the same path list is safe. The problem is if the UI triggers a sparse-checkout update for the renamed namespace without re-computing the correct path list from the manifest.

### 4.4 Data loss risk on namespace delete

FR-6.4 says: "pages in the deleted namespace will be assigned to default." This requires re-writing the manifest and re-indexing. However, on a partial-load client that did not have the deleted namespace active, the pages from the deleted namespace are still absent (not loaded). After the manifest delete, those pages fall into "default" — but the client still doesn't have them on disk (they were in the sparse-checkout cone for the deleted namespace, which is no longer configured). The pages are invisible but their data is still in git. No data loss at the git level, but the user's partial-load client will not see them until they activate "default" and trigger a new sparse-checkout expansion. This must be documented clearly in the UI delete flow.

---

## 5. Paranoid Mode + Namespaces Interaction

### 5.1 Namespace existence is a metadata leak

In an encrypted graph where only specific namespaces are synced, the `.stele-namespaces` manifest file at the repo root must be synced to all clients (it is always in the sparse-checkout root). The manifest reveals **all namespace names, descriptions, path patterns, and colors** — even for namespaces the client did not select. On a "work machine" that syncs only the "tech" namespace, the manifest reveals that "personal" and "journals" namespaces exist, along with their path patterns.

AC-7 in the requirements shows the intended flow: "only the selected namespace's encrypted files are synced to the WASM build; decryption happens in-browser after namespace selection." This implies the manifest is fetched unencrypted as a prerequisite to namespace selection — which leaks namespace metadata to anyone who can fetch the repo root.

**Mitigations (graded):**
- **Minimal (v1):** Accept the leak; document that namespace names and paths are metadata not protected by paranoid mode.
- **Partial:** Encrypt the manifest with a separate "manifest key" that is shared with all authorized clients, and store only an encrypted blob at `.stele-namespaces`. Namespace selection UI must decrypt the manifest before display.
- **Full:** Per-namespace encryption keys; the manifest lists only namespace IDs (not names), and each client must have the corresponding key to decrypt the namespace name and content. High implementation complexity; out of scope for v1.

### 5.2 Timing side-channel via sync size

A network observer watching a sparse-checkout fetch can infer the size of the selected namespace from the transferred bytes, even if content is encrypted. Namespace size correlates with namespace content volume. For high-sensitivity deployments, this is an inherent limitation of git sparse-checkout over cleartext transport (HTTPS). Mitigation requires padding or bundling at the transport layer, which is out of scope.

### 5.3 Encrypted file filter is separate from namespace filter

The current encrypted file handling in `loadDirectory` (lines 1108–1120 of `GraphLoader.kt`) filters `.md.stek` vs. `.md` at file scan time. This filter is path-based. Namespace filtering (if added to `loadDirectory`) must be applied **before** the encryption filter, or a `.md.stek` file in an active namespace with a missing `.md` counterpart may be incorrectly skipped by the namespace filter if the filter only matches `.md` names. Ensure the namespace path-prefix check is applied to the canonical path (stripping `.stek` extension) not the raw filename.

### 5.4 Namespace selector must run before decryption

AC-7 implies the user selects namespaces, then decryption occurs. This ordering is important: if decryption runs first (to check what namespaces exist), encrypted content from all namespaces must be decrypted, which defeats the bandwidth savings. The WASM flow must: (1) fetch unencrypted manifest, (2) show namespace selector, (3) fetch only selected namespace's encrypted files, (4) decrypt in-browser. Any implementation that reverses steps 2 and 3 will download all encrypted content regardless of selection.

---

## 6. Graph Loader O(N) Regression Risk

### 6.1 Namespace filter must run at file-scan time, not post-DB

The safe pattern for namespace filtering in `loadDirectory` is:

```kotlin
// After fileRegistry.pageFiles(path) and before chunked processing:
val namespacedEntries = fileEntries.filter { entry ->
    activeNamespaces.any { ns -> entry.filePath.startsWith(graphRoot + "/" + ns.paths.first()) }
    // or full path-prefix matching per namespace paths list
}
```

This filter is O(files in directory) which is already the cost of the directory scan. It runs before any DB interaction and does not add a new DB query.

The **dangerous anti-pattern** would be post-hoc filtering: loading all pages into DB, then deleting or filtering ones outside the active namespace. This would be O(graph) in DB operations and would re-introduce the OOM risk that the chunked architecture (introduced in the refactor documented in `GraphLoader.kt` lines 1134–1138) was specifically designed to avoid.

### 6.2 `getPageNameEntries()` projection must stay namespace-aware

`PageNameIndex` subscribes to `pageRepository.getPageNameEntries()` — a projection query that returns `(name, isJournal)` pairs. If namespace filtering is done at load time (pages from unloaded namespaces never enter the DB), this query is automatically correct and cheap. If namespace filtering is done in-memory after a full-DB load, this query would return all pages including unloaded-namespace pages, causing the `AhoCorasickMatcher` to be built over the full graph (with all associated memory pressure on 8 000+ page graphs). Enforce the invariant: only loaded pages exist in the DB.

### 6.3 `indexRemainingPages` drain loop must skip unloaded-namespace pages

`GraphLoader.indexRemainingPages` uses a paginated drain loop (`getUnloadedPages(limit, offset)`) to background-index pages not yet fully parsed. If namespace filtering is at load time, unloaded-namespace pages never enter the DB as stubs, so they never appear in `getUnloadedPages` — correct. But if the implementation creates stub rows for all pages at scan time and then filters at read time, unloaded-namespace page stubs will be drained through `indexRemainingPages` and queued for full parse — wasting IO and potentially triggering O(graph) work even in a selective-namespace session.

### 6.4 `getPagesInNamespace` in `DatalogPageRepository` is unbounded

`DatalogPageRepository.getPagesInNamespace` (line 48, `repository/DatalogPageRepository.kt`) returns `Flow<Either<DomainError, List<Page>>>` with no pagination. This is currently fine because the Datalog backend is only used in tests/demos. If the SQLDelight backend ever adds a `getPagesInNamespace` method (which the new feature might tempt), it must be paginated. Do not add an unbounded `getPagesInNamespace` to `SqlDelightPageRepository`.

### 6.5 Namespace-prefix path matching must be O(1) per file

Matching a file path against a namespace's `paths` list is a string prefix check. For a graph with M files and N namespace paths, a naïve O(M × N) scan is fine for realistic values (M ≤ 10 000, N ≤ 20), but building a prefix trie or sorted-prefix binary search at manifest load time is a cleaner architecture and makes the cost explicit.

---

## 7. Naming Collision: "Namespace" Term Already Exists

**This is the highest-priority semantic risk in the entire feature.**

The codebase already uses the term "namespace" in two incompatible senses:

1. **Logseq page namespaces** (`NamespaceUtils.kt`, `Page.namespace` field in `Models.kt`): hierarchical page-title namespaces using `/` as separator — e.g., page "Tech/Rust/Lifetimes" has namespace "Tech/Rust". This is stored in the `namespace` column in the database.

2. **Graph partitions** (the new feature): named partitions of the graph for selective sync — "tech", "personal", "work".

These are entirely different concepts. Naming the new feature "namespace" (as the requirements currently do throughout) will:

- Cause permanent confusion when reading code that uses `Page.namespace` (Logseq slash-hierarchy) alongside `GraphPartition.name` (selective sync).
- Require refactoring all search hits for "namespace" in the codebase to determine which meaning applies.
- Risk bugs where a developer sends Logseq's `Page.namespace` (e.g., "Tech/Rust") through a code path expecting a graph-partition name (e.g., "tech").

**Recommendation:** Rename the new concept throughout the requirements and implementation to **"partition"** (or "graph partition", "sync scope", "vault", or "zone"). Reserve "namespace" exclusively for the existing Logseq `/`-hierarchy meaning. The manifest file can remain `.stele-namespaces` for user-facing familiarity, but the internal Kotlin type should be `GraphPartition`, `NamespacePartition`, or similar — not `Namespace`.

---

## Summary Table

| Risk | Severity | Phase |
|------|----------|-------|
| "namespace" term collision with existing `Page.namespace` field | Critical | Architecture |
| Tombstones stored as DB rows pollute PageNameIndex + search | High | Implementation |
| Tab-close mid-sync corrupts OPFS cache (no atomic write) | High | WASM |
| Multi-tab OPFS race (no Web Locks) | High | WASM |
| File rename across namespace boundary = silent page disappearance | High | Git |
| Namespace manifest leaks all partition names in paranoid mode | High | Security |
| Namespace filter applied post-DB = O(graph) regression | High | Performance |
| Backlink counts are silently incomplete without qualifier | Medium | UX |
| Page name collision across namespaces (e.g., two "Index" pages) | Medium | Data model |
| Manifest has no stable `id` field — renames break references | Medium | Data model |
| `getPagesInNamespace` on SQLDelight must be paginated | Medium | Performance |
| Cone-mode sparse checkout leaves stale files on dirty worktree | Medium | Git |
| `.stele-namespaces` exposes path patterns even when encrypted | Medium | Security |
| OPFS delta sync writes stale-hash manifest on interrupted sync | Medium | WASM |
| Encrypted file filter must check canonical path, not raw filename | Low | Implementation |
| Timing side-channel via sync transfer size | Low | Security |
