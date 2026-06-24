# Architecture Research: Graph Namespaces

## Q1: GraphLoader Filtering — Directory Enumeration vs. Page-Load Time

**Current flow:** `GraphLoader.loadGraphProgressive` hard-codes two directories: `$graphPath/pages` and `$graphPath/journals`. It calls `loadDirectory(pagesDir, ...)` and `loadJournalsImmediate(journalsDir, ...)`, then `loadRemainingJournals`. Inside `loadDirectory`, `fileRegistry.scanDirectory(path)` enumerates the directory, and each file is read and parsed by `parseAndSavePage`.

**Recommendation: filter at directory enumeration time (FR-2.1 + NFR-2).**

The namespace manifest maps names to directory path prefixes (e.g. `tech/`, `pages/Technology/`). Because namespaces can map to arbitrary subdirectory trees — not just the two hard-coded `pages/` and `journals/` roots — the correct hook is before `fileRegistry.scanDirectory` is called. The simplest design:

1. `NamespaceManifest.resolveActivePaths(graphRoot, activeNamespaceSet)` returns `List<String>` (absolute paths) that include all directories covered by the active namespaces.
2. `loadGraphProgressive` accepts an optional `activeNamespaceSet: Set<String>?` and replaces its hard-coded two-directory launch with a loop over the resolved paths.
3. `FileRegistry.scanDirectory` stays untouched; filtering happens above it at the call-site selection.

Filtering at parse time (inside `parseAndSavePage`) is the wrong place because: (a) it wastes the `readFileDecrypted` I/O for files that will be discarded, (b) it doesn't help the file watcher — `startWatching(graphPath)` watches the whole graph root and would still fire events for non-namespace files, and (c) NFR-2 (≤ 200 ms overhead on 8 000 pages) requires skipping the per-file scan entirely, not skipping the DB write after reading.

**File watcher implication:** `GraphFileWatcher.startWatching` must also receive the active path set so it only polls the namespace directories. A `Set<String>? activeRootPaths` parameter on `startWatching` gates the directory scan.

---

## Q2: Repository Layer Impact — Cross-Namespace Tombstone Resolution

**Current `getPageByName` signature:**

```kotlin
fun getPageByName(name: String): Flow<Either<DomainError, Page?>>
```

The `null` case currently means "page not in DB." With namespace filtering, `null` can now mean either "page not in DB" or "page exists but is in an inactive namespace." These two cases must be distinguishable at render time for tombstone UI (FR-3.1).

**Recommendation: do not add a new error variant to `PageRepository`.**

Instead, introduce a separate lookup in a `NamespaceResolver` service that is consulted only at render time:

```kotlin
// New in commonMain
class NamespaceResolver(
    private val manifest: NamespaceManifest,
    private val activeNamespaceSet: Set<String>?,
) {
    /**
     * Given a page name that resolved to null from PageRepository,
     * returns the namespace it would belong to (from manifest path rules)
     * if that namespace is inactive — or null if the page is genuinely absent.
     */
    fun resolveInactiveNamespace(pageName: String): String?
}
```

Why this is better than a new `Either` variant:

- `PageRepository` is a hot, high-frequency interface with many callsites. Adding `Either<NotInActiveNamespace, Page?>` propagates the variant to every caller including background indexing, journal resolution, and search — which do not need tombstone semantics.
- The `Page.filePath` column already stores the disk path. A present-but-inactive page is never in the DB (FR-2.2), so the disambiguation must come from the manifest rather than the repository.
- Tombstone resolution is a render-time concern; `NamespaceResolver` lives in the UI/ViewModel layer, not the repository layer.

**Cross-namespace tombstone write path:** When the active namespace set changes (namespace deactivated, FR-6.5), the `GraphLoader` must clear the deactivated pages from the DB (a new `pageRepository.deletePagesInPaths(paths)` batch delete), then re-index the remaining namespaces. Tombstone rendering is automatic because those page names will now return `null` from `getPageByName`, and `NamespaceResolver` will identify them as inactive-namespace pages.

**`getPagesInNamespace` already exists** on the `PageRepository` interface (line 32 of `PageRepository.kt`). This can be leveraged for deactivation: `getPagesInNamespace(namespace).first()` gives the UUIDs to delete. However, the current schema stores `namespace: String?` on `Page` as a nullable field that is always set to `null` in `parsePageWithoutSaving` (line 1328 of `GraphLoader.kt`). For namespace-aware filtering this field must be populated at parse time from the manifest path match.

---

## Q3: NamespaceConfig Persistence — Where to Store the Active Namespace Set Per Device

**Options evaluated:**

| Option | Pros | Cons |
|--------|------|------|
| Add `activeNamespaceSet: Set<String>?` to `GraphInfo` (JSON in `platformSettings`) | Already-serialized registry, one source of truth | `GraphInfo` is git-committed structure; active set is per-device, not per-repo |
| Local preferences file (`$appDataDir/namespace-prefs-{graphId}.json`) | Completely local, never git-synced | Requires a new read/write path |
| Device-specific sidecar in the graph dir (gitignored) | Co-located with graph | Requires gitignore enforcement; pollutes the repo |

**Recommendation: extend `GraphInfo` in `platformSettings`, not in the git repo.**

`GraphInfo` is already serialized to `platformSettings.getString("graph_registry", "")` — it is an app-local JSON blob, not a file in the git repository. Adding `activeNamespaceSet: Set<String>? = null` to `GraphInfo` means null = all namespaces (backward compatible, FR-2.3). This matches exactly how `isParanoidMode` and `gitDetectionDismissed` are stored: per-device, per-graph, in the app's platform settings store.

The `.stele-namespaces` manifest (namespace definitions) goes in the git repo root and is synced. The active namespace set (which namespaces this device uses) goes in `GraphInfo`. These are orthogonal concerns.

**Sparse-checkout state** (FR-4): git sparse-checkout configuration is stored inside `.git/info/sparse-checkout`, which is already per-clone by git's design. No extra storage is needed for that.

---

## Q4: WASM Integration Point — Where to Hook Namespace-Selective Fetching

**Current WASM boot sequence (from `DriverFactory.js.kt`):**
1. `createDriverAsync(graphId)` — creates OPFS SQLite database
2. `SteleDatabase.Schema.create(driver)` — creates schema
3. `MigrationRunner.applyAll(driver)` — runs migrations
4. `GraphManager.addGraph(path)` → `switchGraph(id)` → `createRepositorySet()`
5. `GraphLoader.loadGraphProgressive(graphPath, ...)`

The WASM build has no `FileSystem` backed by a real directory tree — it uses OPFS. The current `loadGraphProgressive` assumes files are already on the local filesystem when it calls `fileSystem.directoryExists(graphPath)`.

**Recommendation: namespace-selective fetching is a pre-`loadGraphProgressive` step — introduce a `NamespaceSyncService` for WASM.**

The hook should be placed **between step 4 and step 5**: after the SQLite database is open but before `GraphLoader` starts scanning. A new `NamespaceSyncService` (wasmJs-only expect/actual):

```
wasmJsMain/NamespaceSyncService.kt:
  suspend fun fetchNamespaces(remoteUrl: String): NamespaceManifest
  suspend fun syncNamespace(namespace: String, onProgress: (String) -> Unit)
```

This service:
1. Fetches `.stele-namespaces` from the remote (lightweight TOML).
2. Presents the namespace-selector UI (FR-5.4).
3. Downloads selected namespace directories into OPFS via git HTTP smart protocol.
4. Returns control to `GraphLoader`, which then scans the populated OPFS paths.

The non-WASM platforms use a no-op `actual` implementation — they have the files on disk already. The namespace filter passed to `loadGraphProgressive` is the same mechanism on all platforms; only the source of the files differs.

**Do not put namespace fetching inside `GraphLoader`.** `GraphLoader` is a file→DB importer; it has no network knowledge. Mixing fetch and import would violate the current clean separation and make the existing file-watcher logic incorrect on WASM (there is nothing to watch during a fetch).

---

## Q5: Tombstone as a Domain Concept — Sealed Subtype vs. Render-Time Check

**Current `Block` model** is a plain `data class`. There is no sealed hierarchy. Block content is raw markdown stored in `Block.content`.

**Recommendation: resolve tombstones purely at render time — do not add a sealed `Block` subtype.**

Reasons:

1. **Disk format purity.** A tombstone is not a different kind of block — it is a rendering decision made when a wikilink target is absent. The `.md` file on disk contains `[[Page Name]]`; nothing in the file changes based on whether that page is in the active namespace set. Making `Block` sealed would require re-parsing or re-annotating every block whenever the active namespace set changes, which is expensive.

2. **Active namespace set can change at runtime** (FR-6.5 toggle). A sealed tombstone block written to the DB becomes stale the moment the user re-activates a namespace. A render-time check via `NamespaceResolver` is always current.

3. **Existing wikilink rendering is already a render-time pass.** The `HtmlExporter.kt`, `ExportService`, and block renderers already post-process `[[...]]` syntax. Tombstone rendering is one more case in that pass.

4. **DB size.** Tombstone data is: page title + namespace name. This is known from the manifest at render time for zero cost; materializing it as DB rows would create a write dependency on namespace set changes.

**Concrete render-time design:**

```kotlin
// In the block renderer (Compose annotated string builder)
val targetPage = pageRepository.getPageByName(wikilinkTarget).first().getOrNull()
val tombstoneNamespace = if (targetPage == null) {
    namespaceResolver.resolveInactiveNamespace(wikilinkTarget)
} else null

when {
    targetPage != null -> renderActiveLink(targetPage)
    tombstoneNamespace != null -> renderTombstone(wikilinkTarget, tombstoneNamespace, manifest.colorFor(tombstoneNamespace))
    else -> renderBrokenLink(wikilinkTarget)
}
```

The `NamespaceResolver` does a single path-prefix scan of the manifest and can be cached per `pageName` lookup in a `LruCache<String, String?>` to avoid per-render manifest scans.

---

## Summary of Key Architectural Decisions

| Area | Decision |
|------|----------|
| Filtering | At directory enumeration time (before `scanDirectory`), not at page-load/parse time |
| Repository contract | No new `Either` variant on `PageRepository`; use a separate `NamespaceResolver` at render time |
| Config persistence | `GraphInfo.activeNamespaceSet: Set<String>?` in `platformSettings` (never in git) |
| WASM fetch | Pre-`loadGraphProgressive` `NamespaceSyncService` (wasmJs expect/actual); `GraphLoader` stays file-only |
| Tombstones | Render-time check via `NamespaceResolver`; no sealed `Block` subtype; no DB writes for tombstones |

---

## Existing Code That Needs to Change

| File | Change |
|------|--------|
| `db/GraphLoader.kt` | Accept `activeNamespaceSet: Set<String>?`; replace hard-coded `pages/` + `journals/` launch with manifest-resolved paths |
| `db/GraphLoaderPort.kt` | Add `activeNamespaceSet` to `loadGraphProgressive` signature |
| `model/GraphInfo.kt` | Add `activeNamespaceSet: Set<String>? = null` (serializable, backward-compatible) |
| `model/Models.kt` | Populate `Page.namespace` from manifest at parse time in `parsePageWithoutSaving` |
| `repository/PageRepository.kt` | `getPagesInNamespace` is already declared; `SqlDelightPageRepository` must implement it efficiently |
| `db/GraphManager.kt` | Pass `activeNamespaceSet` from `GraphInfo` to `GraphLoader` on `switchGraph`/`openGraph` |
| `wasmJsMain/DriverFactory.js.kt` | No change; new `NamespaceSyncService` is a separate wasmJs expect/actual |
