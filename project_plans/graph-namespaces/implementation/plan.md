# Graph Sections — Implementation Plan

**Feature:** Graph Sections (user-facing label: "Sections")
**Date:** 2026-06-24
**Status:** Revised (Round 2) — All Blockers Resolved
**ADRs:** ADR-011, ADR-012, ADR-013

---

## Step 0.5 — Creative Pass: Architectural Approaches

Three distinct high-level approaches were evaluated before committing to the architecture below.

| Approach | Strength | Weakness |
|----------|----------|----------|
| **A. Single DB, enumeration-time filter (chosen)** | Fits existing `GraphLoader` pipeline exactly; no cross-DB query complexity; `PageNameIndex` works unchanged; incremental rollout per epic. | Pages from inactive sections remain as DB rows if never deactivated cleanly — needs explicit `deletePagesInPaths` on toggle. |
| **B. Per-section SQLite database (one file per section)** | Perfect isolation; deactivating a section = closing a DB connection; OPFS eviction is per-section. | `ATTACH DATABASE` required for cross-section link resolution; `MigrationRunner`, `DatabaseWriteActor`, `RepositoryFactory` must be instantiated N times; `PageNameIndex` must merge N result sets. Complexity explosion for v1. |
| **C. Deferred filter (load all, hide in UI)** | Zero changes to `GraphLoader` or DB schema; tombstones trivially resolved from full DB. | Defeats NFR-1/NFR-2 (must still fetch all 8 000+ pages on WASM); no bandwidth saving; contradicts FR-2 entirely. Only viable for a "view mode" enhancement, not a sync feature. |

**Chosen:** Approach A. Rejected B and C are recorded in the Pattern Decisions table.

---

## Domain Glossary

| Term | Kotlin Type | Definition |
|------|-------------|------------|
| `GraphSection` | `data class GraphSection` | A named logical partition of a graph. Maps a human name (`"tech"`, `"personal"`) to a set of directory path prefixes within the graph root. The domain type for this feature — never called `Namespace` to avoid collision with `Page.namespace` (Logseq slash-hierarchy). |
| `SectionManifest` | `data class SectionManifest` | Deserialized contents of the `.stele-sections` TOML file at the graph root. Contains a list of `GraphSection` definitions and a format version. Parsed via `ktoml-core` + `@Serializable`. |
| `SectionConfig` | `@Serializable data class SectionConfig` | Per-device, per-graph active section selection. Stored as part of `GraphInfo` in `platformSettings` (never in the git repo). `null` means all sections active. |
| `ActiveSectionSet` | `Set<String>?` | The set of section names active on this device. `null` = all sections (primary device mode). An empty set = load nothing (degenerate; should warn). |
| `SectionFilter` | `class SectionFilter` | Constructed from a `SectionManifest` + `ActiveSectionSet`. Answers `fun includes(absolutePath: String): Boolean` via O(1) prefix hash-set lookup. Applied at directory-enumeration time in `GraphLoader`. |
| `SectionResolver` | `class SectionResolver` | UI/ViewModel-layer component. Given a page name that resolved to `null` from `PageRepository`, determines whether the page falls within an inactive section (→ tombstone) or is genuinely absent (→ broken link). Holds a `SectionManifest` + `ActiveSectionSet`. Lives in `ui/` or `ui/viewmodel/` — not in the repository layer. |
| `SectionSyncService` | `interface SectionSyncService` (expect/actual) | Platform-specific component that fetches section content from a git remote before `GraphLoader` starts. WASM actual: REST API fetch → OPFS write. Desktop/Android actual: no-op (files already on disk). |
| `Tombstone` | Render-time annotation only | A visual placeholder shown when a wikilink target is in an inactive section. Never a DB row, never a `Block` subtype. Produced by `SectionResolver` and consumed by the block renderer's annotated-string builder. Contains: page title, section name, section color. |
| `SectionSettingsScreen` | `@Composable fun SectionSettingsScreen` | UI screen for section CRUD and per-device active/inactive toggle. |
| `SectionManifestParser` | `object SectionManifestParser` | Parses/writes `.stele-sections` TOML. Uses `ktoml-core` on all platforms; `ktoml-file` on JVM/Android for file-path convenience. |
| `SparseCheckoutService` | `interface SparseCheckoutService` (expect/actual) | JVM only: shells out to system `git sparse-checkout set` via `ProcessBuilder`. No-op actual for WASM, iOS, Android. |
| `SectionOpfsSyncState` | `data class SectionOpfsSyncState` | Persisted in OPFS as `.stele-sections-sync-complete` JSON. Stores: last synced commit SHA, file count, timestamp. Absence = incomplete sync. |
| `SectionIndex` | `data class SectionIndex(val entries: Map<PageName, GraphSection>)` | In-memory snapshot of page-name → section mappings, built from the `section_name` DB column before pages are deleted on section deactivation. Used by `SectionResolver` to answer tombstone queries without file-path reconstruction. **Accumulated** across deactivations as `GraphManager.sectionIndices: MutableMap<SectionName, SectionIndex>` — one entry per currently-inactive section. A re-activation removes the entry; a full-graph load (`activeSectionSet = null`) clears the map entirely. |
| `RepoCredential` | `sealed class RepoCredential` | Credential for accessing a git remote from WASM. `Anonymous` = public repo, no token. `PersonalAccessToken(token: String)` = private repo PAT, stored in `sessionStorage` only (never OPFS, never persisted across sessions). |
| `BlockStateManager.flushAll` | `fun flushAll(sectionPaths: Set<Path>): Unit` | Synchronously drains all pending debounce-window edits for blocks whose owning page falls within the given section path prefixes. For each dirty block in scope: cancels the 500 ms debounce timer and immediately invokes `GraphWriter.saveBlock()`. Must complete before any DB deletion or sparse-checkout begins. |
| `SectionConfig.renameSection` | `suspend fun renameSection(oldName: SectionName, newName: SectionName): Either<SectionError, Unit>` | Atomically renames a section: updates `.stele-sections` (write-to-temp-then-rename), runs `UPDATE pages SET section_name = :newName WHERE section_name = :oldName` via `DatabaseWriteActor`, and re-keys the in-memory `sectionIndices` entry. Rolls back the manifest file if the DB update fails; emits `SectionError.InconsistentState` if the file rollback also fails. |

---

## Pattern Decisions

| Decision | Chosen Pattern | Rejected Alternatives | Reason |
|----------|---------------|----------------------|--------|
| DB architecture | Single DB, enumeration-time filter | Per-section SQLite DB; post-load in-memory filter | Per-section DB multiplies `MigrationRunner`/`DatabaseWriteActor`/`RepositoryFactory` complexity; post-load filter violates NFR-2 and causes OOM on large graphs |
| TOML library | `ktoml-core:0.7.1` | `tomlkt`; hand-rolled parser | `ktoml-core` is the only KMP lib with confirmed `wasmJs` support; MIT license; `@Serializable` integration |
| JVM sparse-checkout | `ProcessBuilder` + system `git` | JGit `SparseCheckoutCommand` | JGit has no sparse-checkout API (Eclipse Bug #383772 open since 2012); JGit's `CheckoutCommand` actively disables sparse state on pull |
| WASM file fetch | GitHub/GitLab/Gitea REST API + OPFS | `isomorphic-git`; tarball download | `isomorphic-git` has no partial-clone support — downloads full packfiles; REST API allows filtering to section paths before fetching |
| Tombstone storage | Render-time annotation only | Sealed `Block` subtype; DB rows | DB tombstone rows pollute `PageNameIndex` and `SearchRepository`; sealed subtype requires re-annotating all blocks on every section toggle |
| Cross-section link disambiguation | `SectionResolver` in UI layer | New `Either` variant on `PageRepository` | `PageRepository` is high-frequency with many callsites; tombstone semantics are UI-only; resolver can be `LruCache`-backed for O(1) amortized cost |
| Active section persistence | `GraphInfo.sectionConfig: SectionConfig?` in `platformSettings` | Device-local sidecar file; git-tracked `.stele-device` file | `GraphInfo` is already per-device, per-graph, never git-synced; matches how `isParanoidMode` is stored |
| Stable section identity | `id: String` (UUID slug set at creation) | Name-only identity | Renames break any persisted reference keyed only on `name`; stable `id` separates human label from identity |
| Sparse-checkout path recompute | Full set recompute on every toggle | Incremental add/remove | `git sparse-checkout` has no `remove` subcommand on Git < 2.37; recompute is idempotent and safe |

---

## Migration Plan

### New Column: `section_name` on `pages`

**Why:** `Page.section: GraphSection?` stores which section a page belongs to (populated by `GraphLoader` from the manifest at parse time). This is separate from `Page.namespace: String?` (Logseq slash-hierarchy — untouched).

**SQL:**
```sql
ALTER TABLE pages ADD COLUMN section_name TEXT NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS idx_pages_section_name ON pages(section_name);
```

**`MigrationRunner.all` entry** (append to end of the list per CLAUDE.md rules; use the next available version number):
```kotlin
Migration(
    version = <next_version>,   // replace with highest existing version + 1
    name = "add_section_name_to_pages",
    statements = listOf(
        "ALTER TABLE pages ADD COLUMN section_name TEXT NOT NULL DEFAULT 'default'",
        "CREATE INDEX IF NOT EXISTS idx_pages_section_name ON pages(section_name)",
    ),
),
```

**Idempotency:** `MigrationRunner` skips any migration whose version number is already recorded in the `schema_migrations` table. **Do not rely on SQLite swallowing duplicate-column errors** — that behavior is undocumented in CLAUDE.md and does not exist reliably. The version-guard is the sole idempotency mechanism. The double-apply test in Task 3.1.4 asserts this explicitly.

**`SteleDatabase.sq` change:** Add `section_name TEXT NOT NULL DEFAULT 'default'` column to the `pages` CREATE TABLE. Must also appear in `MigrationRunner.all` per the mandatory migration rule in CLAUDE.md.

**`MigrationRunnerSchemaSyncTest`** will enforce this automatically and fail CI if the column is in `.sq` but missing from `MigrationRunner.all`.

---

## Epics, Stories, and Tasks

---

### Epic 1: `SectionManifest` — TOML Parsing, Data Model, Manifest CRUD

**Goal:** Parse and write `.stele-sections` TOML. Provide the domain model for sections.

**Stories:**

#### Story 1.1: Add `ktoml-core` dependency and define `@Serializable` manifest types

**Tasks:**

- **Task 1.1.1** — Add `ktoml-core:0.7.1` to `commonMain` dependencies in `kmp/build.gradle.kts`; add `ktoml-file:0.7.1` to `jvmMain` and `androidMain`. Run `bazel test //kmp:jvm_tests` to confirm no classpath conflicts.
  - Files: `kmp/build.gradle.kts`
  - Duration: 10 min

- **Task 1.1.2** — Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sections/GraphSection.kt` with:
  - `@Serializable data class GraphSection(val id: String, val name: String, val description: String = "", val paths: List<String> = emptyList(), val color: String = "#888888")`
  - `@Serializable data class SectionManifest(val version: Int = 1, val section: List<GraphSection> = emptyList())` (key must be `section` — matches `[[section]]` TOML array-of-tables)
  - `val SectionManifest.Companion.DEFAULT get() = SectionManifest(section = listOf(GraphSection(id = "default", name = "default", paths = emptyList())))`
  - Files: `sections/GraphSection.kt`
  - Duration: 15 min

- **Task 1.1.3** — Write unit test `SectionManifestParserTest` in `businessTest` that round-trips the example TOML from FR-1.1. Assert: `manifest.section.size == 4`, `manifest.section[0].name == "personal"`, `manifest.section[3].paths.isEmpty()`.
  - Given: TOML string with 4 sections. When: decoded via `ktoml-core`. Then: `SectionManifest` fields match expected values.
  - Files: `businessTest/.../sections/SectionManifestParserTest.kt`
  - Duration: 15 min

#### Story 1.2: `SectionManifestParser` — read/write `.stele-sections`

**Tasks:**

- **Task 1.2.1** — Create `SectionManifestParser` object in `commonMain/sections/`. Implement `fun parseString(toml: String): SectionManifest` using `Toml { ignoreUnknownNames = true }.decodeFromString<SectionManifest>(toml)`. Implement `fun encodeToString(manifest: SectionManifest): String` using `Toml.encodeToString(manifest)`.
  - Files: `sections/SectionManifestParser.kt`
  - Duration: 20 min

- **Task 1.2.2** — Create `expect fun readSectionManifest(graphPath: String, fileSystem: FileSystem): SectionManifest` in `commonMain/sections/`. On all platforms: read `$graphPath/.stele-sections` via `fileSystem.readFile(path)` → `SectionManifestParser.parseString(content)`. Return `SectionManifest.DEFAULT` if file absent (backward-compat per FR-1.3).
  - Files: `sections/SectionManifestReader.kt`
  - Duration: 20 min

- **Task 1.2.3** — Create `suspend fun writeSectionManifest(graphPath: String, manifest: SectionManifest, fileSystem: FileSystem)` that writes `SectionManifestParser.encodeToString(manifest)` to `$graphPath/.stele-sections`.
  - Files: `sections/SectionManifestReader.kt` (same file)
  - Duration: 10 min

#### Story 1.3: `SectionFilter` — O(1) path matching

**Tasks:**

- **Task 1.3.1** — Create `class SectionFilter(manifest: SectionManifest, activeSectionNames: Set<String>?, graphRoot: String)`. Pre-compute a `Set<String>` of absolute path prefixes for active sections. Implement `fun includes(absolutePath: String): Boolean` — returns `true` if `activeSectionNames == null` OR `absolutePath` starts with any prefix in the active set. Catch-all "default" section (empty `paths`) includes all files not matched by any other section.
  - Given: manifest with "tech" → `["tech/"]`, active = `{"tech"}`. When: `includes("/graph/tech/page.md")`. Then: returns `true`.
  - Given: same manifest/active. When: `includes("/graph/personal/diary.md")`. Then: returns `false`.
  - Files: `sections/SectionFilter.kt`, `businessTest/.../sections/SectionFilterTest.kt`
  - Duration: 25 min

---

### Epic 2: `GraphLoader` Section Filter

**Goal:** Apply `SectionFilter` at directory-enumeration time. Scope the file watcher to active sections.

**Stories:**

#### Story 2.1: Extend `GraphLoaderPort` and `GraphLoader` to accept `ActiveSectionSet`

**Tasks:**

- **Task 2.1.1** — Add `activeSectionSet: Set<String>? = null` parameter to `loadGraphProgressive` in `GraphLoaderPort` and `GraphLoader`. Add `sectionManifest: SectionManifest = SectionManifest.DEFAULT` parameter to `GraphLoader` constructor (injected by `GraphManager`).
  - Files: `db/GraphLoaderPort.kt`, `db/GraphLoader.kt`
  - Duration: 15 min

- **Task 2.1.2** — In `GraphLoader.loadGraphProgressive`, construct a `SectionFilter(sectionManifest, activeSectionSet, graphPath)` immediately. Replace the hard-coded `pages/` + `journals/` directory launch with a loop over `sectionManifest.resolveActivePaths(graphPath, activeSectionSet)` — a new helper that returns the set of absolute directory paths to scan. When `activeSectionSet == null`, resolves to all declared section paths (or `[pages/, journals/]` if manifest is DEFAULT for backward compat).
  - Given: manifest absent (DEFAULT), `activeSectionSet = null`. When: `loadGraphProgressive`. Then: scans `pages/` and `journals/` exactly as before (AC-5 regression guard).
  - Files: `db/GraphLoader.kt`, `sections/SectionManifest.kt` (add `resolveActivePaths`)
  - Duration: 30 min

- **Task 2.1.3** — In `GraphLoader.loadDirectory`, add `SectionFilter` gating before `fileRegistry.scanDirectory`. Files whose `absolutePath` returns `false` from `filter.includes()` are skipped entirely (no I/O, no DB stub). This is the NFR-2 guard.
  - Given: 8 000-page graph, `activeSectionSet = {"tech"}` covering 500 pages. When: `loadGraphProgressive`. Then: only 500 files are opened (no I/O for 7 500 inactive-section files).
  - Files: `db/GraphLoader.kt`
  - Duration: 20 min

- **Task 2.1.4** — Scope `GraphFileWatcher.startWatching` to active paths. Add `activeRootPaths: Set<String>? = null` to `startWatching`. When non-null, the watcher only emits events for paths that match the filter. Inactive-section file changes are silently ignored.
  - Files: `db/GraphFileWatcher.kt`
  - Duration: 20 min

#### Story 2.2: Populate `Page.section` at parse time

**Tasks:**

- **Task 2.2.1** — Add `section_name TEXT NOT NULL DEFAULT 'default'` column to `pages` table in `SteleDatabase.sq` (see Migration Plan above). Add `Migration("add_section_name_to_pages", ...)` to `MigrationRunner.all`.
  - Files: `commonMain/sqldelight/.../SteleDatabase.sq`, `db/MigrationRunner.kt`
  - Duration: 15 min

- **Task 2.2.2** — In `GraphLoader.parsePageWithoutSaving`, after path resolution, set `page.section` by calling `SectionFilter.resolveSection(absolutePath)` → returns the `GraphSection` whose paths match, or the `default` section. Store `page.section.name` as `section_name` in the DB row.
  - Files: `db/GraphLoader.kt`, `model/Models.kt` (add `section: GraphSection? = null` field to `Page`)
  - Duration: 25 min

- **Task 2.2.3** — Update `SqlDelightPageRepository` to read `section_name` from DB and populate `Page.section` using the injected `SectionManifest` lookup. Write `getPagesBySection(sectionName: String): Flow<...>` using `WHERE section_name = ?` (paginated, not unbounded per CLAUDE.md rules). Add corresponding query to `SteleDatabase.sq`.
  - Files: `repository/SqlDelightPageRepository.kt`, `repository/PageRepository.kt`, `commonMain/sqldelight/.../SteleDatabase.sq`
  - Duration: 30 min

#### Story 2.3: Section deactivation — remove pages from DB on toggle

**P1 Pre-Mortem Fix (Failure Mode 1 — data loss on deactivation):** A note being actively edited can be lost when sparse-checkout removes the directory before BlockStateManager's 500 ms debounce fires. `git status --porcelain` only sees on-disk changes; it cannot see in-memory edits held in BlockStateManager. The deactivation sequence must therefore flush all pending edits synchronously before any DB deletion or sparse-checkout begins.

**Mandatory atomic deactivation sequence:**
1. `BlockStateManager.flushAll(sectionPaths)` — drain editor edits to disk
2. Build `SectionIndex` from DB (projection-only query)
3. `deletePagesInPaths(sectionPaths)` — remove from DB
4. `git sparse-checkout set …` — remove from disk
5. On any failure in steps 3–4: rollback (re-insert from `SectionIndex`, re-add to sparse-checkout)

**Tasks:**

- **Task 2.3.0 (T_flush)** — Add `fun flushAll(sectionPaths: Set<Path>): Unit` to `BlockStateManager`. For every tracked dirty block whose owning page file path starts with any prefix in `sectionPaths`: cancel the debounce coroutine Job and immediately call `GraphWriter.saveBlock(block)` synchronously (blocking until the write completes or fails). Any `saveBlock` failure must be logged and surfaced as a `DomainError`; do not silently swallow it. `flushAll` must complete in full before returning — the caller (`GraphManager.toggleSection`) must not proceed to step 2 until this method returns.
  - Given: user is editing "personal/MyJournal.md" with 480 ms elapsed on the 500 ms debounce. When: `deactivateSection("personal")` is called. Then: `flushAll` fires, the edit is written to disk, and only then does `deletePagesInPaths` run.
  - Files: `db/BlockStateManager.kt`
  - Duration: 25 min

- **Task 2.3.1** — Add `suspend fun deletePagesInPaths(absolutePaths: List<String>): Either<DomainError, Int>` to `PageRepository` interface. Implement in `SqlDelightPageRepository` via `DELETE FROM pages WHERE file_path LIKE ?` for each path prefix (batched, not unbounded). Also cascade-delete `blocks` and `references` for those pages via existing FK relationships.
  - Files: `repository/PageRepository.kt`, `repository/SqlDelightPageRepository.kt`, `SteleDatabase.sq`
  - Duration: 30 min

- **Task 2.3.2** — In `GraphManager.toggleSection(graphId, sectionName, active: Boolean)`, when `active = false`: (1) call `blockStateManager.flushAll(sectionPaths)` first, (2) build `SectionIndex` via `pageRepository.getPagesBySectionSnapshot(sectionName)`, (3) call `pageRepository.deletePagesInPaths(paths)`, (4) call `sparseCheckoutService.setSectionPaths(...)`, (5) update `GraphInfo.sectionConfig`, (6) call `graphLoader.cancelBackgroundWork()` + `loadGraphProgressive` with updated active set. If step 3 or step 4 fails: rollback by re-inserting rows from the `SectionIndex` and re-adding paths to sparse-checkout; surface the error to the UI. When `active = true`: add section to active set, call `loadGraphProgressive` to re-index.
  - Files: `db/GraphManager.kt`
  - Duration: 35 min

- **Task 2.3.3 (T_flush_test)** — Write `SectionDeactivationFlushTest` in `businessTest`. Scenario: create a graph with a "personal" section containing one page, simulate an in-flight debounce edit on that page (advance the debounce timer to 480 ms without letting it fire), call `deactivateSection("personal")`, then assert: (a) the edit was written to disk before `deletePagesInPaths` was called (verify via file content on disk), (b) no `DomainError` is emitted, (c) the DB no longer contains the page row after deactivation completes.
  - Files: `businessTest/.../sections/SectionDeactivationFlushTest.kt`
  - Duration: 30 min

---

### Epic 3: DB Migration — `section_name` Column

**Goal:** Add `section_name` to `pages` table correctly so existing databases upgrade safely.

**Stories:**

#### Story 3.1: Schema migration

**Tasks:**

- **Task 3.1.1** — In `SteleDatabase.sq`, add `section_name TEXT NOT NULL DEFAULT 'default'` to the `pages` table definition (inside `CREATE TABLE IF NOT EXISTS pages`). This is the source of truth for schema generation.
  - Files: `commonMain/sqldelight/.../SteleDatabase.sq`
  - Duration: 10 min

- **Task 3.1.2** — Append to `MigrationRunner.all` a new versioned `Migration` entry for the `section_name` column. Use the standard `MigrationRunner` version-number pattern per CLAUDE.md: each `Migration` in `MigrationRunner.all` has a monotonically increasing version number, and `MigrationRunner.applyAll` skips any migration whose version number is already recorded in the `schema_migrations` table. **Do not rely on SQLite swallowing "duplicate column name" errors** — CLAUDE.md documents no such behavior and SQLite raises `SQLiteException("duplicate column name: section_name")` on a second `ALTER TABLE ... ADD COLUMN` call. The version-guard is the only correct idempotency mechanism. Entry to append:
  ```kotlin
  Migration(
      version = <next_version>,   // replace with next integer in MigrationRunner.all
      name = "add_section_name_to_pages",
      statements = listOf(
          "ALTER TABLE pages ADD COLUMN section_name TEXT NOT NULL DEFAULT 'default'",
          "CREATE INDEX IF NOT EXISTS idx_pages_section_name ON pages(section_name)",
      ),
  )
  ```
  First: open `db/MigrationRunner.kt`, read the current highest version number, and use `highest + 1`.
  - Files: `db/MigrationRunner.kt`
  - Duration: 15 min

- **Task 3.1.3** — Verify `MigrationRunnerSchemaSyncTest` passes: it auto-detects all `CREATE TABLE IF NOT EXISTS` in `.sq` and asserts each appears in `MigrationRunner.all`. Run `bazel test //kmp:business_tests`.
  - Files: test verification only — no code changes
  - Duration: 5 min

- **Task 3.1.4** — Write `SectionNameMigrationTest` in `businessTest` covering two scenarios:
  1. **Single apply**: open a DB without the column, call `MigrationRunner.applyAll`, assert `PRAGMA table_info(pages)` contains `section_name` with `DEFAULT 'default'`; assert existing rows get `section_name = 'default'`.
  2. **Double apply (idempotency)**: call `MigrationRunner.applyAll` a second time on the same DB; assert no exception is thrown and the column still exists exactly once.
  - Given: existing DB without `section_name`. When: `MigrationRunner.applyAll`. Then: column present, existing rows have default value.
  - Given: same DB (column already added). When: `MigrationRunner.applyAll` called again. Then: no exception; column present exactly once (version-skip guard works).
  - Files: `businessTest/.../sections/SectionNameMigrationTest.kt`
  - Duration: 25 min

---

### Epic 4: `SectionResolver` — Tombstone Resolution and UI Integration

**Goal:** Distinguish "page in inactive section" from "page does not exist." Render tombstones.

**Stories:**

#### Story 4.1: `SectionResolver` and `SectionIndex` implementation

**Design note (Blocker 2 fix):** The original plan attempted to resolve inactive-section membership by reconstructing a file path from the page name (slugifying). This is fundamentally broken: page names do not reliably map to file paths (arbitrary casing, spaces, OS differences). **The correct approach:** use the `section_name` DB column. Before pages are deleted on section deactivation, a `SectionIndex` is built from the live DB rows. `SectionResolver` queries this index by exact page name — no file path reconstruction.

**Tasks:**

- **Task 4.1.1** — Define `data class SectionIndex(val entries: Map<PageName, GraphSection>)` in `commonMain/sections/`. This is an in-memory snapshot of which page name belongs to which section, built while the section's DB rows still exist. `PageName` is the existing type alias for `String` from `model/Models.kt`. In `GraphManager`, declare `private val sectionIndices: MutableMap<SectionName, SectionIndex> = mutableMapOf()` (not a single `SectionIndex`). Each deactivation adds an entry under the deactivated section's name; each re-activation removes it; a primary-device full-graph load (`activeSectionSet = null`) calls `sectionIndices.clear()`. This ensures `SectionResolver` can resolve tombstones for all inactive sections concurrently, not just the most-recently-deactivated one.
  - Files: `sections/SectionIndex.kt`, `db/GraphManager.kt`
  - Duration: 15 min

- **Task 4.1.2** — In `GraphManager.toggleSection(graphId, sectionName, active = false)`, **before** calling `pageRepository.deletePagesInPaths(paths)`, build a `SectionIndex` by querying `pageRepository.getPagesBySectionSnapshot(sectionName)` (a new suspend method — **projection-only**, NOT a `List<Page>`). The projection returns only `(name: String, sectionName: String)` pairs via a dedicated SQL query (`SELECT name, section_name FROM pages WHERE section_name = ? ORDER BY name`) — no block content, no properties, no full `Page` object materialization. This avoids the O(section-size) heap allocation of full `Page` objects that would cause OOM on Android under heap pressure (per CLAUDE.md unbounded-read prohibition). Populate `SectionIndex.entries` as `Map<pageName, graphSection>` where `graphSection` is resolved from the manifest. **Add** the newly built index to `GraphManager.sectionIndices` (the accumulated `MutableMap<SectionName, SectionIndex>`) under key `sectionName` — do NOT replace the whole map. On re-activation (`active = true`): call `sectionIndices.remove(sectionName)`. On primary-device full-graph load (`activeSectionSet = null`): call `sectionIndices.clear()`.
  - Signature: `suspend fun getPagesBySectionSnapshot(sectionName: String): List<PageNameEntry>` where `data class PageNameEntry(val name: String, val sectionName: String)` — a minimal projection type defined in `repository/PageRepository.kt`.
  - Files: `db/GraphManager.kt`, `repository/PageRepository.kt`, `repository/SqlDelightPageRepository.kt`, `commonMain/sqldelight/.../SteleDatabase.sq` (add `SELECT name, section_name FROM pages WHERE section_name = :sectionName`)
  - Duration: 30 min

- **Task 4.1.3** — Create `class SectionResolver(private val manifest: SectionManifest, private val activeSectionSet: Set<String>?)` in `commonMain/sections/`. Implement `fun resolveTombstone(pageName: String): GraphSection?`:
  - If `activeSectionSet == null`: return `null` (all sections active — no tombstones possible).
  - Iterate over all entries in `sectionIndices: Map<SectionName, SectionIndex>` provided by `GraphManager` (injected as a snapshot reference). For each `SectionIndex`, check if `pageName` is a key in `entries`. Return the associated `GraphSection` on first hit. This correctly handles sequential deactivations: both "personal" and "finance" tombstones resolve even if both were deactivated in separate `toggleSection` calls.
  - If not found in any index: return `null` (genuinely absent page, not an inactive-section tombstone).
  - **No file path reconstruction of any kind.** No slugification. No heuristic matching.
  - Cache recent lookups with `LruCache<String, GraphSection?>(capacity = 512)`. Invalidate the cache on any `sectionIndices` map mutation (entry added or removed).
  - Given: `sectionIndices = {"personal" → SectionIndex("My Diary" → personal), "finance" → SectionIndex("Budget 2026" → finance)}`, active = `{"tech"}`. When: `resolveTombstone("My Diary")`. Then: returns `GraphSection("personal", ...)`.
  - Given: same indices, active = `null`. When: `resolveTombstone("My Diary")`. Then: returns `null` (all sections active).
  - Given: `sectionIndices` does NOT contain `"Unknown Page"` in any entry. When: `resolveTombstone("Unknown Page")`. Then: returns `null` (broken link, not tombstone).
  - Given: "personal" deactivated then "finance" deactivated (two sequential calls). When: `resolveTombstone("My Diary")`. Then: returns the "personal" tombstone (both indices remain in `sectionIndices` — the second deactivation did not erase the first).
  - Files: `sections/SectionResolver.kt`, `businessTest/.../sections/SectionResolverTest.kt`
  - Duration: 30 min

- **Task 4.1.4** — Inject `SectionResolver` into `StelekitViewModel` (or the block renderer composable). Update the wikilink rendering path to call `sectionResolver.resolveTombstone(wikilinkTarget)` when `pageRepository.getPageByName(target).first().getOrNull() == null`. Route to `renderTombstone(pageName, section)` if non-null, or `renderBrokenLink(pageName)` if null.
  - Files: `ui/StelekitViewModel.kt` or block renderer composable
  - Duration: 25 min

#### Story 4.2: Tombstone UI component

**Tasks:**

- **Task 4.2.1** — Create `@Composable fun TombstoneLink(pageName: String, section: GraphSection, onSyncRequest: (GraphSection) -> Unit)`. Renders: dashed-underline text with `section.color` tint, tooltip "Page in section '${section.name}' — not synced to this device", click calls `onSyncRequest(section)`.
  - Files: `ui/components/TombstoneLink.kt`
  - Duration: 25 min

- **Task 4.2.2** — Create `SyncSectionDialog` composable: "Add section '${section.name}' to active sections on this device?" with Confirm/Cancel. On confirm: calls `GraphManager.toggleSection(graphId, section.name, active = true)` and triggers re-index.
  - Given: user clicks tombstone. When: confirms sync. Then: `GraphManager.toggleSection` called with `active = true` for the section, re-index starts.
  - Files: `ui/components/SyncSectionDialog.kt`
  - Duration: 20 min

---

### Epic 5: Desktop JVM Sparse Checkout — `SparseCheckoutService`

**Goal:** Invoke `git sparse-checkout set` via `ProcessBuilder` when the active section set changes on a desktop git repo.

**Stories:**

#### Story 5.1: `SparseCheckoutService` interface and JVM implementation

**Tasks:**

- **Task 5.1.1** — Define `interface SparseCheckoutService` in `commonMain/sections/`:
  ```kotlin
  suspend fun setSectionPaths(repoRoot: String, wikiSubdir: String, absolutePaths: List<String>): Either<DomainError, Unit>
  suspend fun hasRemote(repoRoot: Path): Boolean
  ```
  Both methods are `suspend` — `hasRemote` shells out via `ProcessBuilder` on JVM and must not block the calling coroutine's dispatcher. Create `expect object SparseCheckoutServiceFactory { fun create(): SparseCheckoutService }`.
  - Files: `sections/SparseCheckoutService.kt`
  - Duration: 15 min

- **Task 5.1.2** — In `jvmMain`, implement `JvmSparseCheckoutService`. Logic:
  1. `findGitExecutable()` — checks `which git` via `ProcessBuilder`. Returns `null` if absent (FR-4.4: silently skip, return `Unit.right()`).
  2. Translate `absolutePaths` to repo-root-relative: strip `repoRoot/` prefix, prepend `wikiSubdir/` if non-empty, trim trailing `/`.
  3. Always include the repo root itself (so `.stele-sections` is never excluded — cone mode always includes root files, so this is a no-op constraint).
  4. Run `git -C $repoRoot sparse-checkout init --cone --no-sparse-index`.
  5. Run `git -C $repoRoot sparse-checkout set $repoPaths` (all paths in one invocation — never incremental). Full recompute on every toggle per FR-4.2.
  6. Run `withContext(PlatformDispatcher.IO)` wrapping **all** `ProcessBuilder` calls — both inside `setSectionPaths` and inside `hasRemote`. The dispatcher switch must live inside the method body, not at the call site.
  7. Implement `override suspend fun hasRemote(repoRoot: Path): Boolean = withContext(PlatformDispatcher.IO) { ProcessBuilder("git", "-C", repoRoot.toString(), "remote", "get-url", "origin").start().waitFor() == 0 }`. Non-JVM actuals return `false` unconditionally (no subprocess support on WASM/iOS/Android).
  - Files: `jvmMain/.../sections/JvmSparseCheckoutService.kt`
  - Duration: 40 min

- **Task 5.1.3** — Create no-op `actual` for WASM, iOS, Android returning `Unit.right()`.
  - Files: `wasmJsMain/.../sections/WasmSparseCheckoutService.kt`, `androidMain/.../sections/AndroidSparseCheckoutService.kt`, `iosMain/.../sections/IosSparseCheckoutService.kt`
  - Duration: 15 min

#### Story 5.2: Wire `SparseCheckoutService` into `GraphManager.toggleSection`

**Design note (Blocker 3 fix):** FR-4.6 originally guarded on "git installed AND is a git repo." A local-only git repo (no remote) will have `git sparse-checkout` applied, which excludes directories from the working tree — corrupting the user's pages on disk after any `git checkout` or `git reset`. **The guard must additionally require a configured remote.**

**Tasks:**

- **Task 5.2.1** — In `GraphManager.toggleSection`, after `deletePagesInPaths` (deactivate) or before `loadGraphProgressive` (activate): call `sparseCheckoutService.setSectionPaths(repoRoot, wikiSubdir, resolvedPaths)`. Gate on **both** `gitInfo != null` AND `gitInfo.hasRemote == true`. To determine `hasRemote`, add `suspend fun hasRemote(repoRoot: Path): Boolean` to `JvmSparseCheckoutService` (and as a no-op returning `false` in all non-JVM actuals): **the method must be declared `suspend` and must call `withContext(PlatformDispatcher.IO)` internally around the `ProcessBuilder` invocation** — never at the call site in `GraphManager`. This is required per CLAUDE.md's dispatcher matrix (non-database IO → `PlatformDispatcher.IO`). Running a subprocess without the dispatcher switch blocks whichever dispatcher `GraphManager` is running on (typically `Dispatchers.Default`) and will cause ANR-class hangs on Android and UI freezes on desktop. The implementation runs `git -C $repoRoot remote get-url origin` via `ProcessBuilder`; returns `true` if exit code is 0, `false` otherwise. Caller (`GraphManager`) simply `await`s the result — no `withContext` wrapping at the call site, no coroutine scope blocking. Populate `gitInfo.hasRemote` at graph-open time and store in `GraphInfo`.
  - If `hasRemote == false`: skip sparse-checkout entirely. Apply only the in-memory section filter (FR-2.4 still applies — `SectionFilter` still gates `GraphLoader`). Emit `SectionConfig.SparseCheckoutUnavailable` flag to `GraphInfo` and surface a non-blocking info banner in the UI: "Section filtering is active in memory only — connect a remote to enable disk-level filtering."
  - Given: desktop git repo WITH remote, user deactivates "personal". When: `toggleSection("personal", false)`. Then: `git sparse-checkout set` called with paths for all still-active sections; journals directory removed from disk on next pull.
  - Given: desktop git repo WITHOUT remote (local-only). When: `toggleSection("personal", false)`. Then: sparse-checkout NOT invoked; in-memory filter applied; info banner shown; user's files on disk untouched.
  - Files: `db/GraphManager.kt`, `jvmMain/.../sections/JvmSparseCheckoutService.kt`, `model/GraphInfo.kt`
  - Duration: 30 min

- **Task 5.2.2** — Guard for dirty worktree: before calling `setSectionPaths`, check `git -C $repoRoot status --porcelain` output. If non-empty, return `Either.Left(DomainError.GitError.UncommittedChanges(...))` and surface a "Please commit or stash changes before changing section selection" message in the UI.
  - Files: `jvmMain/.../sections/JvmSparseCheckoutService.kt`, `ui/StelekitViewModel.kt`
  - Duration: 20 min

---

### Epic 6: `SectionSyncService` WASM — REST API Fetch → OPFS

**Goal:** Before `GraphLoader` starts on WASM, fetch selected section content from git remote via REST API and write to OPFS.

**Stories:**

#### Story 6.1: WASM `SectionSyncService` — manifest fetch and section selector

**Tasks:**

- **Task 6.1.1** — Define `interface SectionSyncService` in `commonMain/sections/`:
  ```kotlin
  suspend fun fetchManifest(remoteUrl: String, branch: String, credential: RepoCredential = RepoCredential.Anonymous): SectionManifest
  suspend fun syncSection(remoteUrl: String, branch: String, section: GraphSection, credential: RepoCredential = RepoCredential.Anonymous, onProgress: (Int, Int) -> Unit): Either<DomainError, Unit>
  ```
  The `credential` parameter carries auth context. `RepoCredential` is defined in the Domain Glossary and `sections/RepoCredential.kt`. Create no-op `actual` for JVM/Android/iOS.
  - Files: `sections/SectionSyncService.kt`, `sections/RepoCredential.kt`
  - Duration: 20 min

- **Task 6.1.2** — In `wasmJsMain`, implement `WasmSectionSyncService.fetchManifest`: detect git host from `remoteUrl` (GitHub / GitLab / Gitea heuristic on host name), fetch raw `.stele-sections` file from the appropriate raw URL, parse via `SectionManifestParser.parseString`.
  - Files: `wasmJsMain/.../sections/WasmSectionSyncService.kt`
  - Duration: 25 min

- **Task 6.1.3** — Create `@Composable fun SectionSelectorScreen(manifest: SectionManifest, onConfirm: (Set<String>) -> Unit)` (WASM-only). Shows list of sections with name, description, estimated page count (from manifest metadata if present), color. Confirm button triggers sync for selected sections. Per FR-5.6.
  - Files: `wasmJsMain/.../ui/SectionSelectorScreen.kt`
  - Duration: 30 min

#### Story 6.2: WASM REST API tree fetch + parallel OPFS write

**Tasks:**

- **Task 6.2.1** — In `WasmSectionSyncService.syncSection`, implement the GitHub flow:
  1. Acquire `navigator.locks.request("stele-section-sync-${section.name}")` exclusive lock (FR-5.4 tab exclusion).
  2. Check for `.stele-sections-sync-complete` marker in OPFS. If present and `commitSha` matches remote HEAD → skip (FR-5.5 delta check).
  3. Fetch `GET /repos/{owner}/{repo}/git/trees/{headSha}?recursive=1`. Filter tree entries to section paths.
  4. Fetch raw file contents in parallel (10 concurrent via `async` + `awaitAll`). Write each file to OPFS at `sections/{urlSafeRemote}/{section.name}/{relativePath}` using existing `opfsWriteFile` infrastructure.
  5. Write `SectionOpfsSyncState(commitSha, fileCount, timestamp)` as `.stele-sections-sync-complete` last (atomic marker per FR-5.3).
  6. Call `navigator.storage.persist()` on first successful write (FR-5.7).
  - Files: `wasmJsMain/.../sections/WasmSectionSyncService.kt`
  - Duration: 45 min

- **Task 6.2.2** — Handle delta sync (FR-5.5): if marker present but `commitSha` differs from remote HEAD, call `GET /repos/{owner}/{repo}/compare/{cached}...{head}` to get changed files. Fetch only changed/added files; delete OPFS entries for removed files. Update marker with new SHA.
  - Files: `wasmJsMain/.../sections/WasmSectionSyncService.kt`
  - Duration: 35 min

- **Task 6.2.3** — Handle `QuotaExceededError` from OPFS writes: catch, surface `DomainError.StorageFull`, show user-facing error "Storage full — please free device storage and retry." (FR-5.7 requirement).
  - Files: `wasmJsMain/.../sections/WasmSectionSyncService.kt`, error handling in WASM boot sequence
  - Duration: 15 min

#### Story 6.3: Wire `SectionSyncService` into the WASM boot sequence

**Tasks:**

- **Task 6.3.1** — In the WASM boot sequence (between step 4 and step 5 per architecture research Q4), inject `WasmSectionSyncService`. After `GraphManager.addGraph` but before `GraphLoader.loadGraphProgressive`: call `sectionSyncService.fetchManifest(remoteUrl, branch)`, show `SectionSelectorScreen` if this is first load or manifest changed, await user selection, call `sectionSyncService.syncSection(...)` for each selected section.
  - Given: first WASM load with GitHub remote, user selects "tech". When: boot completes. Then: only `tech/` pages are in OPFS; `GraphLoader` scans only OPFS `sections/.../tech/` paths; other sections have no pages in DB.
  - Files: WASM boot composable / `wasmJsMain/` entry point
  - Duration: 30 min

- **Task 6.3.2** — Offline path: if `SectionSyncService.fetchManifest` throws network error, check for `SectionOpfsSyncState` marker. If present, proceed with cached OPFS content without network sync. If marker absent, show "No cached data and no network connection" error.
  - Given: OPFS populated, network offline. When: WASM loads. Then: graph opens from cache without network call (AC-6).
  - Files: WASM boot sequence
  - Duration: 15 min

#### Story 6.4: WASM credential management for private repo access

**Background (Blocker 4 fix):** Epic 6 describes REST API fetches but had no authentication story. Private repos (the primary privacy use case — keeping personal notes off a work machine) will fail immediately without a PAT or OAuth token. This story adds a credential flow scoped to the WASM platform only.

**Security model:** PATs are stored in `sessionStorage` only — never OPFS, never `localStorage`. They are cleared when the tab closes and are never sent to any server other than the declared git host. This is intentionally ephemeral: a work machine should not persist a private-repo credential across sessions.

**Tasks:**

- **Task 6.4.1** — Define `sealed class RepoCredential` in `commonMain/sections/RepoCredential.kt`:
  ```kotlin
  sealed class RepoCredential {
      object Anonymous : RepoCredential()
      data class PersonalAccessToken(val token: String) : RepoCredential()
  }
  ```
  - Files: `sections/RepoCredential.kt`
  - Duration: 10 min

- **Task 6.4.2** — In `WasmSectionSyncService`, accept `RepoCredential` on all API call sites. When `credential` is `PersonalAccessToken`, add `Authorization: Bearer <token>` header to all fetch requests (manifest fetch, tree fetch, file fetches). When `Anonymous`, omit the header. If the HTTP response is 401 or 403, do NOT retry — emit `DomainError.CredentialRequired` (add to `DomainError` sealed class) and return `Either.Left(DomainError.CredentialRequired)`.
  - Files: `wasmJsMain/.../sections/WasmSectionSyncService.kt`, `model/DomainError.kt`
  - Duration: 20 min

- **Task 6.4.3** — In the WASM ViewModel (or boot sequence), handle `DomainError.CredentialRequired`: transition to a `CredentialRequired` UI state. Show a PAT entry dialog: "This repository is private. Enter a Personal Access Token to continue." with a password field and a "Continue" button. On submit: store the PAT in `sessionStorage` (key: `stele-pat-<urlSafeRemote>`), construct `RepoCredential.PersonalAccessToken(token)`, and retry the failed operation.
  - Given: WASM session, private GitHub repo. When: anonymous fetch returns 401. Then: PAT dialog shown; user enters token; retry succeeds; OPFS populated.
  - Files: WASM boot composable, WASM ViewModel, `wasmJsMain/.../ui/PatEntryDialog.kt`
  - Duration: 30 min

- **Task 6.4.4** — In `SectionSelectorScreen` (FR-5.6), add a "Private repository" indicator. Before showing the section list, attempt a manifest fetch with `Anonymous`. If `CredentialRequired` is emitted, show the PAT dialog (Task 6.4.3) inline before rendering the section list.
  - Given: user opens WASM with a private repo URL. When: `SectionSelectorScreen` loads. Then: PAT prompt shown before section list; after PAT entry, section list is fetched and displayed.
  - Files: `wasmJsMain/.../ui/SectionSelectorScreen.kt`
  - Duration: 20 min

- **Task 6.4.5** — Test: `WasmSectionSyncService` with a mock HTTP client that returns 401 emits `DomainError.CredentialRequired`. After providing `RepoCredential.PersonalAccessToken("test-token")`, retry with the mock returning 200; assert sync succeeds and `Authorization` header was sent on the retry.
  - Files: `wasmJsTest/.../sections/WasmSectionSyncServiceCredentialTest.kt`
  - Duration: 20 min

---

### Epic 7: Section Settings UI

**Goal:** Section CRUD screen, page list color dots, section filter control.

**Stories:**

#### Story 7.1: `SectionSettingsScreen` — section list and toggle

**Tasks:**

- **Task 7.1.1** — Create `SectionSettingsViewModel` in `ui/viewmodel/sections/`. Exposes: `StateFlow<SectionManifest>`, `StateFlow<SectionConfig?>`, `fun toggleSection(sectionName: String, active: Boolean)`, `fun createSection(name, description, paths, color)`, `fun deleteSection(name)`. Wraps `GraphManager.toggleSection` and `writeSectionManifest`.
  - Files: `ui/viewmodel/sections/SectionSettingsViewModel.kt`
  - Duration: 30 min

- **Task 7.1.2** — Create `@Composable fun SectionSettingsScreen(viewModel: SectionSettingsViewModel)`. Shows list of sections: name, description, path patterns, page count (from `pageRepository.countPagesBySection(sectionName)`), color swatch, active/inactive toggle, Edit and Delete buttons. "Create Section" FAB.
  - Given: manifest with 3 sections, device has "tech" active. When: screen opens. Then: "tech" toggle is ON, others are OFF; page counts shown per section.
  - Files: `ui/sections/SectionSettingsScreen.kt`
  - Duration: 35 min

- **Task 7.1.3** — Create section form dialog for Create/Edit: name field, description field, add/remove path pattern fields (each a text input, e.g. "tech/" or "pages/Technology/"), color picker (`ColorPicker` or hex input), Confirm/Cancel. Validate: name non-empty, no duplicate names, paths non-overlapping with other sections (warn, not block).
  - Files: `ui/sections/SectionFormDialog.kt`
  - Duration: 30 min

- **Task 7.1.4** — Delete section confirmation dialog: "Delete section '${name}'? Pages in this section will be assigned to 'default'. Pages are not deleted from disk." Two-step confirmation (type section name to confirm).
  - Files: `ui/sections/DeleteSectionDialog.kt`
  - Duration: 15 min

#### Story 7.2: Page list section color dots and filter control

**Tasks:**

- **Task 7.2.1** — In the page list sidebar row composable, add a 6dp colored circle dot on the left of the page title when more than one section is active (or when `sectionConfig != null`). Color comes from `page.section?.color` looked up against `SectionManifest`. Show no dot when all sections are merged (primary device, single-section).
  - Given: page in "tech" section (color "#3498DB"), multiple sections active. When: page appears in sidebar. Then: blue dot shown left of title.
  - Files: `ui/components/PageListItem.kt` (or equivalent sidebar row composable)
  - Duration: 20 min

- **Task 7.2.2** — Add section filter dropdown to the page list header. Options: "All sections" + one entry per active section name. Selecting a section name applies `WHERE section_name = ?` to the page list query via `pageRepository.getPagesBySection(sectionName)`. "All sections" reverts to unfiltered query.
  - Given: user selects "tech" in filter. When: page list updates. Then: only tech pages shown; journal/personal pages hidden.
  - Files: `ui/PageListHeader.kt`, `ui/StelekitViewModel.kt`
  - Duration: 25 min

#### Story 7.4: Section rename migrates DB rows

**P1 Pre-Mortem Fix (Failure Mode 4 — invisible pages after section rename):** When a user renames a section (e.g., "tech" → "technology"), writing the new name to `.stele-sections` without updating the `pages` table leaves all existing DB rows with `section_name = 'tech'`. They become invisible to all queries for "technology". `GraphLoader`'s "already loaded" reconciliation pass skips unchanged files, so stale rows are never corrected by a re-index. An explicit `UPDATE pages SET section_name = :newName WHERE section_name = :oldName` migration must run atomically with the manifest write.

**Tasks:**

- **Task 7.4.1 (T_rename_1)** — Implement `SectionConfig.renameSection(oldName: SectionName, newName: SectionName): Either<SectionError, Unit>` (signature in Domain Glossary). Sequence: (a) write the updated manifest to a temp file then `rename()` atomically over `.stele-sections`; (b) execute `UPDATE pages SET section_name = :newName WHERE section_name = :oldName` via `DatabaseWriteActor`; (c) if the DB update fails, revert `.stele-sections` to the original by writing the old manifest back to the same temp-then-rename path; (d) if the file revert also fails, emit `SectionError.InconsistentState` and surface a user-visible error in the UI. Add `SectionError.InconsistentState` to the `DomainError` / `SectionError` sealed hierarchy.
  - Files: `sections/SectionConfig.kt`, `db/GraphManager.kt`, `model/DomainError.kt` (add `SectionError.InconsistentState`)
  - Duration: 35 min

- **Task 7.4.2 (T_rename_2)** — After the DB update succeeds, re-key the `sectionIndices` entry: if `GraphManager.sectionIndices` contains a `SectionIndex` under `oldName`, remove it and re-insert it under `newName`. This keeps tombstone resolution for the renamed section intact without requiring a full re-deactivation.
  - Files: `db/GraphManager.kt`
  - Duration: 10 min

- **Task 7.4.3 (T_rename_3)** — Update `SectionSettingsViewModel.renameSection(oldName, newName)` to call `SectionConfig.renameSection(oldName, newName)` and propagate any `SectionError` to the UI as an error snackbar. The `SectionFormDialog` Edit flow (Task 7.1.3) must call this new path instead of a direct `writeSectionManifest` call when the name field has changed.
  - Files: `ui/viewmodel/sections/SectionSettingsViewModel.kt`, `ui/sections/SectionFormDialog.kt`
  - Duration: 20 min

- **Task 7.4.4 (T_rename_4)** — Write `SectionRenameTest` in `businessTest`. Scenario: create a graph with 50 pages assigned `section_name = 'tech'`, call `SectionConfig.renameSection("tech", "technology")`, then assert: (a) all 50 `pages` rows now have `section_name = 'technology'`; (b) `getPagesBySection("technology")` returns all 50 pages; (c) `getPagesBySection("tech")` returns 0 pages; (d) no pages require a re-index to become visible. Also add a rollback scenario: if the DB UPDATE is made to fail (via mock), assert `.stele-sections` is reverted to the original content.
  - Files: `businessTest/.../sections/SectionRenameTest.kt`
  - Duration: 30 min

#### Story 7.3: Section indicator in graph settings sidebar

**Tasks:**

- **Task 7.3.1** — Add "Sections" entry to the graph settings sidebar. Visible only when `.stele-sections` file exists in the graph root (i.e., `GraphManager.currentSectionManifest` has more than the `default` section). Clicking navigates to `SectionSettingsScreen`.
  - Given: graph has `.stele-sections` with 3 sections. When: settings sidebar opens. Then: "Sections" entry visible. Given: no `.stele-sections`. Then: "Sections" entry absent (AC-5 backward compat).
  - Files: graph settings sidebar composable
  - Duration: 20 min

- **Task 7.3.2** — Add `GraphInfo.sectionConfig: SectionConfig? = null` field to `GraphInfo` (backward-compatible, `null` = all sections). Ensure `Json { ignoreUnknownKeys = true }` decoding handles old `GraphInfo` JSON without `sectionConfig` key.
  - Files: `model/GraphInfo.kt`
  - Duration: 10 min

---

## Acceptance Criteria — Given-When-Then

### AC-1: Section color dot in sidebar
- **Given** a `.stele-sections` file with a "tech" section (`color = "#3498DB"`, `paths = ["tech/", "pages/Technology/"]`) and multiple sections active.
- **When** `GraphLoader` indexes `tech/TypeSystems.md`.
- **Then** `Page.section.name == "tech"` and `Page.section.color == "#3498DB"`; the page list sidebar renders a blue dot left of "TypeSystems"; pages in `personal/` show a different color dot.

### AC-2: WASM section-selective fetch + tombstone
- **Given** a WASM session, GitHub remote with "tech" and "personal" sections declared.
- **When** user selects only "tech" on the section selector screen and confirms.
- **Then** only `tech/` files are fetched to OPFS; `personal/` files are absent from DB; a `[[My Diary]]` wikilink on a tech page renders as a dashed-underline tombstone with tooltip "Page in section 'personal' — not synced to this device."

### AC-3: Desktop sparse-checkout on deactivate
- **Given** a desktop git repo, active sections = `{"tech", "personal"}`, system git in PATH.
- **When** user deactivates "personal" in `SectionSettingsScreen`.
- **Then** `git sparse-checkout set` is called with only tech paths; `personal/` and `journals/` directories are removed from disk on next `git pull`; pages from personal are deleted from DB; their wikilinks show tombstones.

### AC-4: Desktop sparse-checkout on re-activate
- **Given** same repo, "personal" deactivated (step AC-3 result).
- **When** user re-activates "personal" (or clicks "Sync section?" dialog from a tombstone).
- **Then** `git sparse-checkout set` is called with personal paths added back; blobs are materialized from the local object store (no network if already cloned); `loadGraphProgressive` re-indexes personal pages; tombstones resolve to real links.

### AC-5: Backward compatibility — no `.stele-sections`
- **Given** a graph with no `.stele-sections` file.
- **When** `GraphManager.addGraph(path)` is called.
- **Then** `SectionManifest.DEFAULT` is used; `activeSectionSet = null`; `SectionFilter.includes()` returns `true` for all paths; graph loads identically to pre-feature behavior. "Sections" entry absent from settings sidebar.

### AC-6: WASM offline after OPFS populated
- **Given** WASM session, "tech" section previously synced to OPFS, `.stele-sections-sync-complete` marker present with correct SHA.
- **When** network is disconnected and WASM reloads.
- **Then** `SectionSyncService.syncSection` detects network error, falls back to OPFS cache; `GraphLoader` scans OPFS paths; tech pages load without any network call.

### AC-7: Tombstone "Sync section?" dialog activates section
- **Given** WASM session with only "tech" active, a tech page with `[[Private Note]]` wikilink where "Private Note" is in "personal".
- **When** user clicks the tombstone for "Private Note".
- **Then** `SyncSectionDialog` appears with "Add section 'personal'?"; on confirm, `sectionSyncService.syncSection("personal")` is called; OPFS is populated; page re-renders with a real link.

### AC-8: Type naming constraint
- **Given** the feature implementation is complete.
- **When** `grep -r "class Namespace\|data class Namespace\|sealed.*Namespace" kmp/src` is run.
- **Then** zero matches for this feature's types (existing `NamespaceUtils.kt` and `Page.namespace` field are untouched and still exist).

---

## Open Questions

1. **Stable `id` on `GraphSection`**: The manifest format in FR-1.1 does not include an `id` field. The pitfalls research (§4.2) recommends adding an immutable UUID slug. The plan adds `id: String` to `GraphSection`. On first write of an existing manifest (edited by user in text), a new `id` is generated automatically if absent via `id = name.slugify()`. This must be documented in the TOML schema.

2. **Page name collisions across sections**: Two pages named "Index" in `tech/` and `personal/` both parse as page name "Index". The current `PageRepository` lookup is name-keyed. Resolution: for v1, document that page names must be unique graph-wide (consistent with Logseq convention). Full namespace-qualified names (`tech/Index`) are a v2 scope item.

3. **Backlink count qualifier**: The UI should append "(in synced sections)" to backlink counts in partial-section mode. Deferred to a follow-up issue.

---

## Blockers Resolved

This section records the 4 BLOCKER-level issues identified by adversarial review (2026-06-24) and the patches applied in this revision.

### Blocker 1 — Migration idempotency claim unverified (RESOLVED)

**Original problem:** Task 3.1.2 claimed `MigrationRunner.applyAll` swallows "duplicate column name" SQLite errors, providing idempotency for the `ALTER TABLE ... ADD COLUMN` migration. CLAUDE.md documents no such behavior; relying on it would crash every existing user database on a second migration attempt.

**Fix applied:**
- Replaced the "swallows duplicate column" claim in Task 3.1.2 with the correct version-number guard: `MigrationRunner` skips migrations whose version is already recorded in `schema_migrations`. The migration entry now requires a `version` field (next integer in `MigrationRunner.all`).
- Updated the Migration Plan section at the top of the document to remove the false idempotency claim.
- Added a second test scenario to `SectionNameMigrationTest` (Task 3.1.4): apply migrations twice on the same DB, assert no exception and column present exactly once.
- Affected sections: Migration Plan, Epic 3 / Story 3.1 / Tasks 3.1.2 and 3.1.4.

### Blocker 2 — SectionResolver name-to-path heuristic is unimplementable (RESOLVED)

**Original problem:** Task 4.1.1 resolved inactive-section membership by slugifying page names to file paths (e.g., `"My Diary"` → `personal/My-Diary.md`). SteleKit allows arbitrary page-name casing and file-path conventions; this produces false positives and false negatives and is not correctly implementable.

**Fix applied:**
- Added `SectionIndex` data class to the Domain Glossary: `data class SectionIndex(val entries: Map<PageName, GraphSection>)` — an in-memory snapshot built from the `section_name` DB column before pages are deleted on section deactivation.
- Replaced Task 4.1.1 with four new tasks (4.1.1–4.1.4):
  - T4.1.1: Define `SectionIndex` data class.
  - T4.1.2: Build `SectionIndex` from DB in `GraphManager.toggleSection` **before** deleting pages (via new `pageRepository.getPagesBySectionSnapshot`).
  - T4.1.3: `SectionResolver.resolveTombstone(pageName)` checks `SectionIndex` by exact page-name key. No file path reconstruction, no slugification.
  - T4.1.4: Inject `SectionResolver` into `StelekitViewModel` (unchanged from original T4.1.2, renumbered).
- Affected sections: Domain Glossary, Epic 4 / Story 4.1.

### Blocker 3 — Desktop sparse-checkout on local-only repos (no remote) (RESOLVED)

**Original problem:** FR-4.6 guarded sparse-checkout on "git installed AND is a git repo" but not on "has a remote." A local-only git repo has sparse-checkout applied, excluding directories from the working tree — corrupting the user's pages on disk.

**Fix applied:**
- Updated Task 5.2.1 to gate sparse-checkout on `gitInfo != null AND gitInfo.hasRemote == true`. The `hasRemote` check runs `git remote get-url origin`; exit code non-zero = no remote.
- When `hasRemote == false`: sparse-checkout is skipped entirely. In-memory `SectionFilter` (FR-2.4) still applies. `SectionConfig.SparseCheckoutUnavailable` flag emitted. Non-blocking info banner shown: "Section filtering is active in memory only — connect a remote to enable disk-level filtering."
- `GitInfo.hasRemote` field populated at graph-open time and stored in `GraphInfo` / `model/GraphInfo.kt`.
- Affected sections: Epic 5 / Story 5.2 / Task 5.2.1.

### Blocker 4 — WASM REST API has zero authentication story (RESOLVED)

---

## Round 2 Blockers Resolved

This section records the 2 BLOCKER-level issues introduced by the Round 1 patches, identified by the second adversarial review pass (2026-06-24), and the fixes applied in this revision.

### Round 2 Blocker A — `hasRemote` subprocess dispatcher unspecified (RESOLVED)

**Original problem:** Task 5.2.1 introduced `fun hasRemote(repoRoot: String): Boolean` on `JvmSparseCheckoutService`, called from `GraphManager` at graph-open time. No dispatcher switch was specified at either the call site or the method body. If an implementer calls it without `withContext(PlatformDispatcher.IO)`, the `ProcessBuilder` subprocess blocks the calling coroutine's dispatcher — typically `Dispatchers.Default` or even the main thread if called from a composable launch — causing ANR-class hangs on Android and UI freezes on desktop.

**Fix applied:**
- Changed `hasRemote` signature from `fun` to `suspend fun hasRemote(repoRoot: Path): Boolean` in the `SparseCheckoutService` interface (Task 5.1.1) and JVM implementation (Task 5.1.2).
- Specified that `withContext(PlatformDispatcher.IO)` must live **inside the method body** — not at the call site in `GraphManager`. The caller (`GraphManager`) simply awaits the result; no coroutine scope blocking.
- Added explicit implementation sketch to Task 5.1.2: `override suspend fun hasRemote(repoRoot: Path): Boolean = withContext(PlatformDispatcher.IO) { ProcessBuilder(...).start().waitFor() == 0 }`.
- Non-JVM actuals (`WasmSparseCheckoutService`, `AndroidSparseCheckoutService`, `IosSparseCheckoutService`) implement `hasRemote` as `override suspend fun hasRemote(repoRoot: Path) = false`.
- Updated Task 5.2.1 to explicitly state the `suspend fun` + internal dispatcher requirement and the rationale (CLAUDE.md dispatcher matrix, ANR risk).
- Affected sections: Domain Glossary (`SparseCheckoutService`), Epic 5 / Story 5.1 / Task 5.1.1, Task 5.1.2, Story 5.2 / Task 5.2.1.

### Round 2 Blocker B — `SectionIndex` single-instance breaks multi-deactivation tombstones (RESOLVED)

**Original problem:** Task 4.1.2 stored the built `SectionIndex` as `GraphManager.sectionIndex: SectionIndex`, replacing any prior index on each deactivation. After two sequential deactivations ("personal" then "finance"), the "personal" index was discarded — `SectionResolver` could only resolve tombstones for the most-recently-deactivated section. `[[My Diary]]` on a tech page would return `null` (broken link) instead of the correct "personal" tombstone.

Additionally, `getPagesBySectionSnapshot` was specified to return `List<Page>` — a full object materialization that violates CLAUDE.md's unbounded-read prohibition and causes OOM risk on Android for large sections.

**Fix applied:**
- Changed `GraphManager.sectionIndex: SectionIndex` to `GraphManager.sectionIndices: MutableMap<SectionName, SectionIndex>` in the Domain Glossary and Task 4.1.1.
- On deactivation of section X: build `SectionIndex` for X, then `sectionIndices[X] = index` (accumulated, not replaced).
- On re-activation of section X: `sectionIndices.remove(X)`.
- On primary-device full-graph load (`activeSectionSet = null`): `sectionIndices.clear()`.
- Updated Task 4.1.2 to specify `getPagesBySectionSnapshot` as a **projection-only** query (`SELECT name, section_name FROM pages WHERE section_name = ?`) returning `List<PageNameEntry>` (a minimal `data class(val name: String, val sectionName: String)`) — no full `Page` object materialization, no block content, no properties. This eliminates the O(section-size) heap allocation per CLAUDE.md rules.
- Updated Task 4.1.3 to iterate `sectionIndices.values` instead of a single `sectionIndex`. Added a test scenario for sequential deactivation (two sections deactivated, both resolved).
- Affected sections: Domain Glossary (`SectionIndex`), Epic 4 / Story 4.1 / Tasks 4.1.1, 4.1.2, 4.1.3.

**Original problem:** Epic 6 described REST API fetches but had no story for PAT storage or transmission. Private repos (the primary privacy use case) fail immediately without authentication.

**Fix applied:**
- Added `RepoCredential` sealed class to the Domain Glossary and as Task 6.4.1: `Anonymous` (public repos) and `PersonalAccessToken(token: String)` (private repos, stored in `sessionStorage` only — never OPFS or `localStorage`).
- Updated `SectionSyncService` interface (Task 6.1.1) to accept `RepoCredential` on `fetchManifest` and `syncSection`.
- Added new Story 6.4 ("WASM credential management for private repo access") with 5 tasks:
  - T6.4.1: Define `RepoCredential` sealed class.
  - T6.4.2: `WasmSectionSyncService` sends `Authorization: Bearer` header when PAT provided; emits `DomainError.CredentialRequired` on HTTP 401/403.
  - T6.4.3: WASM ViewModel handles `CredentialRequired` → shows PAT dialog → stores PAT in `sessionStorage` → retries.
  - T6.4.4: `SectionSelectorScreen` shows PAT prompt before section list for private repos.
  - T6.4.5: Test — mock HTTP 401 emits `CredentialRequired`; after PAT provided, retry with mock 200 succeeds.
- Security model documented: PAT is session-scoped (`sessionStorage`), never persisted to OPFS, never sent to any server other than the declared git host.
- Affected sections: Domain Glossary, Epic 6 / Story 6.1 / Task 6.1.1, new Epic 6 / Story 6.4.

---

## P1 Pre-Mortem Items Resolved

This section records the 2 P1-priority failure modes identified by the pre-implementation pre-mortem (2026-06-24, `pre-mortem.md`) and the plan patches applied to address them.

### P1 Pre-Mortem Item 1 — Data Loss on Section Deactivation (BlockStateManager not flushed)

**Source:** `pre-mortem.md` Failure Mode 1 — "User Loses Pages After Section Deactivation" (Priority: P1)

**Problem:** The deactivation flow's dirty-worktree guard (`git status --porcelain`, Task 5.2.2) only catches uncommitted file changes already written to disk. It cannot detect edits still held in `BlockStateManager`'s 500 ms debounce window. A note being actively typed when the user triggers deactivation will be lost: `deletePagesInPaths` removes the DB row, `git sparse-checkout set` removes the directory from disk, and when the debounce finally fires, `GraphWriter.saveBlock()` writes to a path that no longer exists and silently fails. On re-activation, git materializes the last committed version of the file, discarding the in-progress edit.

**Patches applied:**

1. **Domain Glossary** — added `BlockStateManager.flushAll(sectionPaths: Set<Path>): Unit` with full specification: cancels debounce timers and synchronously invokes `GraphWriter.saveBlock()` for all dirty blocks in scope.

2. **Story 2.3 header** — added a "P1 Pre-Mortem Fix" callout block explaining the failure mode and the mandatory atomic deactivation sequence:
   1. `BlockStateManager.flushAll(sectionPaths)` — drain editor edits to disk
   2. Build `SectionIndex` from DB
   3. `deletePagesInPaths(sectionPaths)` — remove from DB
   4. `git sparse-checkout set …` — remove from disk
   5. On any failure in steps 3–4: rollback (re-insert, re-add to sparse-checkout)

3. **Task 2.3.0 (T_flush)** — new task added: implement `BlockStateManager.flushAll` with the cancel-debounce-then-write-synchronously logic; includes a Given-When-Then acceptance criterion covering the 480 ms debounce scenario.

4. **Task 2.3.2** — updated `GraphManager.toggleSection` orchestration to call `blockStateManager.flushAll(sectionPaths)` as step 1 (before `SectionIndex` build and before `deletePagesInPaths`).

5. **Task 2.3.3 (T_flush_test)** — new test task: `SectionDeactivationFlushTest` in `businessTest` asserts that an in-flight debounce edit is written to disk before `deletePagesInPaths` runs and that the DB row is removed after deactivation completes.

- Affected sections: Domain Glossary, Epic 2 / Story 2.3 (Tasks 2.3.0, 2.3.2, 2.3.3 added or updated).

---

### P1 Pre-Mortem Item 2 — Invisible Pages After Section Rename (no DB update story)

**Source:** `pre-mortem.md` Failure Mode 4 — "Section Rename Causes Invisible Pages" (Priority: P1)

**Problem:** The plan had a story for editing section metadata via `SectionFormDialog` (Task 7.1.3) and a `writeSectionManifest` call, but no story for updating the `section_name` column in the `pages` DB table when the section's name changes. After a rename from "tech" to "technology", all existing DB rows retain `section_name = 'tech'`. The `GraphLoader` reconciliation pass skips unchanged files (its "already loaded" check), so stale rows are never corrected by a re-index. From the UI, all 2,300+ unchanged tech pages become invisible — they exist in the DB but match no active section name. `MigrationRunnerSchemaSyncTest` does not catch this because it is a data mutation, not a schema change.

**Patches applied:**

1. **Domain Glossary** — added `SectionConfig.renameSection(oldName: SectionName, newName: SectionName): Either<SectionError, Unit>` with full atomicity specification: write manifest to temp-then-rename, then DB UPDATE, rollback manifest on DB failure, emit `SectionError.InconsistentState` on double failure.

2. **Story 7.4 ("Section rename migrates DB rows")** — new story added to Epic 7 with a "P1 Pre-Mortem Fix" callout and 4 tasks:
   - **Task 7.4.1 (T_rename_1):** Implement `SectionConfig.renameSection` with atomic manifest write + DB `UPDATE pages SET section_name` via `DatabaseWriteActor` + rollback on failure. Adds `SectionError.InconsistentState` to the error hierarchy.
   - **Task 7.4.2 (T_rename_2):** Re-key `GraphManager.sectionIndices` entry from `oldName` to `newName` so tombstone resolution remains correct after rename.
   - **Task 7.4.3 (T_rename_3):** Update `SectionSettingsViewModel.renameSection` and `SectionFormDialog` to call `SectionConfig.renameSection` (not a bare `writeSectionManifest`) when the name field changes.
   - **Task 7.4.4 (T_rename_4):** `SectionRenameTest` in `businessTest` — 50-page graph renamed "tech" → "technology"; asserts all 50 rows have the new `section_name`, `getPagesBySection("technology")` returns 50, no re-index required. Includes a rollback scenario (mock DB failure → manifest reverted).

- Affected sections: Domain Glossary, Epic 7 (new Story 7.4 with Tasks 7.4.1–7.4.4).
