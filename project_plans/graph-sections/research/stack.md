# Graph Sections — Technology Stack Research

**Date**: 2026-06-29  
**Scope**: FR-5 lazy content, ktoml status, Desktop sparse-checkout vs content-on-demand, OQ-3 index format

---

## 1. `loadGraphProgressive()` two-phase structure and FR-5 adaptation

### How the existing two-phase load works

`GraphLoader.loadGraphProgressive()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`) runs:

**Warm-start path** (DB already has journals):
1. Detects `warmStartJournals.isNotEmpty()` and fires `onPhase1Complete()` immediately (< 100 ms).
2. Launches background `backgroundIndexJob` that runs `loadJournalsImmediate` + `loadRemainingJournals` + `loadDirectory(pagesDir, ParseMode.METADATA_ONLY)`.

**Cold-start path** (empty DB):
1. **Phase 1 (blocking)**: `loadJournalsImmediate(journalsDir, immediateJournalCount)` — reads the N most-recent journal files with `ParseMode.FULL`, writes blocks to DB, calls `onPhase1Complete()`.
2. **Phase 2 (background coroutines)**: `loadRemainingJournals` + `loadDirectory(pagesDir, ParseMode.METADATA_ONLY)` — remaining pages get metadata-only stub blocks, `is_content_loaded = 0`.

The `is_content_loaded` column in the `pages` table (set to `0` in `METADATA_ONLY` mode, `1` in `FULL` mode) is the existing lazy-load flag. `loadFullPage(pageUuid)` is already the on-navigation content-fetch hook — it reads the file from disk and calls `parseAndSavePage(FULL)`.

`indexRemainingPages()` already implements a bounded drain loop (`getUnloadedPages(limit=100, offset)`) that processes `is_content_loaded = 0` rows in chunks — this is the exact pattern needed for background indexing after a section-filtered startup.

### FR-5 adaptation strategy

The infrastructure is already in place. The adaptation for Graph Sections is:

**Stage 1 (startup index)**: Instead of reading page bodies to build the index, populate `pages` rows with `is_content_loaded = 0` from a path-prefix scan (Desktop) or WASM tree listing (ADR-013). On Desktop this is already what `ParseMode.METADATA_ONLY` does in `loadDirectory()` — front-matter properties (including `stele-section::`) are parsed without reading full block content.

**Stage 2 (on navigation)**: `loadFullPage()` already handles this for Desktop. For WASM, `WasmSectionSyncService.fetchPageContent(path)` fetches the raw file from GitHub REST API, writes to OPFS via `opfsWriteFile`, then calls `parseAndSavePage(FULL)`.

**Section filtering hook**: `loadDirectory()` needs a path-prefix guard injected before the chunk loop. The subscribed section `paths` lists (from the parsed `.stele-sections` manifest) map directly to the file-path prefix check already done for `file:` protocol artifacts (line 1167 in `GraphLoader.kt`). A similar guard skips files outside subscribed prefixes.

### Key finding

`METADATA_ONLY` parse mode + `is_content_loaded = 0` + `loadFullPage()` on navigation is the correct, already-supported implementation path. No new lazy-fetch architecture is needed — only the section path-prefix filter in `loadDirectory()` and the WASM fetch-on-navigation path from ADR-013.

---

## 2. TOML parsing — ktoml status

### ADR decision (ADR-011)

ADR-011 (Accepted) selected **`ktoml-core:0.7.1`** for all TOML parsing in `commonMain`.

Rationale summary:
- Only KMP TOML library with confirmed `wasmJs` target support.
- Integrates with `kotlinx.serialization` (`@Serializable` data classes, `Toml.decodeFromString<T>()`).
- Supports `[[section]]` arrays-of-tables and `ignoreUnknownNames = true` for forward compatibility.
- MIT license (compatible with Elastic-2.0).
- v0.7.1 released August 2025, targets Kotlin 2.2.0.

### Current build status: NOT YET ADDED

Searching `kmp/build.gradle.kts` for `ktoml`, `toml`, and `akuleshov7` returns **zero matches**. The ADR records the intent but the dependency has not been added yet.

The current serialization stack in `build.gradle.kts` is:
```
kotlinx-serialization-json:1.10.0   (commonMain)
ktor-serialization-kotlinx-json:3.1.3  (commonMain)
```

### Required addition (Phase 1 work item)

```kotlin
// kmp/build.gradle.kts — commonMain.dependencies
implementation("com.akuleshov7:ktoml-core:0.7.1")

// jvmMain + androidMain (optional — for direct file write convenience)
implementation("com.akuleshov7:ktoml-file:0.7.1")
```

Parsing pattern per ADR-011:
```kotlin
val tomlConfig = TomlConfig(ignoreUnknownNames = true)
val manifest: SectionManifest = Toml(config = tomlConfig).decodeFromString(tomlString)
```

`ktoml-core` jar is ~150 KB. No native library, no JNI. `ktoml-file` brings okio as a transitive dependency, already present via SQLDelight.

---

## 3. Desktop lazy content: sparse-checkout vs content-on-demand

### Sparse-checkout assessment

`git sparse-checkout --cone` filters which paths are materialized in the working tree at **clone time** and subsequent `pull`/`checkout` operations. It is a git operation, not an in-process GraphLoader operation.

Relevance to FR-6 (first-time clone of a section slice): sparse-checkout is appropriate for the initial clone on Desktop — instead of cloning all 8 000 pages, clone only the subscribed section path prefixes. This satisfies G-2 (work device never receives personal files) at the git layer. The `GitSyncService` (JVM) would run `git clone --no-checkout` + `git sparse-checkout set <prefixes>` + `git checkout`.

However, `git sparse-checkout` does not provide content-on-demand for pages *within* the subscribed section. All files matching the subscribed paths are materialized fully on first checkout. FR-5 Stage 2 (content fetched only on navigation) requires a different mechanism.

### Content-on-demand for Desktop (Phase 2)

For Desktop, **content-on-demand is already free**:
- Files are on disk (within the subscribed path prefixes).
- `loadDirectory(pagesDir, ParseMode.METADATA_ONLY)` already populates `pages` rows with `is_content_loaded = 0`.
- `loadFullPage(pageUuid)` already reads the file from disk when the user navigates.

There is no network round-trip. "Loading…" spinner is not needed for Desktop — files are already materialized. The `ParseMode.METADATA_ONLY` → `loadFullPage()` flow is already the Desktop lazy-content path.

### Recommendation

**Phase 2 Desktop implementation**:
1. Add path-prefix filter in `loadDirectory()` — skip files outside subscribed section prefixes.
2. Continue using existing `ParseMode.METADATA_ONLY` for index; `loadFullPage()` for on-navigation full parse.
3. Optionally wire `git sparse-checkout` in `GitSyncService` for initial clone to prevent non-subscribed files from ever landing on disk (satisfies NFR-5 at the git layer rather than the reader layer).

Sparse-checkout is a defense-in-depth measure for NFR-5 compliance, not a required mechanism for lazy content. The `loadDirectory()` path-prefix filter is the primary enforcement mechanism.

---

## 4. Lightweight page index (OQ-3)

### Option analysis

OQ-3 asks: where is the lightweight page index stored? Options from the requirements:
1. `.stele-index.json` committed to git
2. OPFS-only cache built from tree listing
3. SQLite metadata-only rows

### Assessment

**Option A — `.stele-index.json`**: Requires a separate index file synchronized with page changes. Every page rename, section reassignment, or property edit requires updating the index file and committing it. Creates a merge-conflict source on every page write. Does not integrate with the existing `PageRepository` query layer. **Rejected.**

**Option B — OPFS-only cache from tree listing**: WASM-only. Does not serve Desktop. Requires a separate index format and loader. **Partial — suitable as WASM-specific fast startup cache**, not as the universal index. The ADR-013 commit-flag marker (`.stele-sections-sync-complete`) already records the tree state; the tree listing response from the GitHub REST API can seed the `pages` table directly.

**Option C — SQLite metadata-only rows (recommended)**: The `pages` table in `SteleDatabase.sq` already has:
- `is_content_loaded INTEGER NOT NULL DEFAULT 1` — distinguishes index-only rows from fully-loaded pages.
- `getUnloadedPages(limit, offset)` — paginated query for rows where `is_content_loaded = 0`.
- `countUnloadedPages()` — O(1) progress denominator for `indexRemainingPages()`.
- `file_path TEXT` — stores the path prefix needed for section filtering.
- `properties TEXT` — JSON blob that currently stores frontmatter; `stele-section::` property will be parsed here.

Adding a `section_id TEXT` column (with a migration entry in `MigrationRunner.all`, per CLAUDE.md rules) stores the resolved section assignment as a first-class DB column rather than parsing the JSON `properties` blob on every query. This enables `WHERE section_id = ?` filtering in the sidebar and journal queries without a JSON scan.

### Recommendation

Use **SQLite `pages` rows with `is_content_loaded = 0`** as the universal page index.

For WASM: populate from the GitHub REST tree listing (ADR-013 step 2) before content fetch. Each tree entry maps to a `pages` row: `(uuid=generated, name=filename, file_path=path, is_content_loaded=0, section_id=resolved_from_prefix)`. No body content is fetched or stored. `indexRemainingPages()` drains these rows on navigation.

For Desktop: `loadDirectory(ParseMode.METADATA_ONLY)` already populates these rows today. The section path-prefix filter gates which directories are scanned.

**Schema addition needed**:
```sql
-- Add to SteleDatabase.sq pages table (requires MigrationRunner entry)
section_id TEXT  -- nullable; NULL means global (no section assignment)
```

**Index query needed** (for sidebar section filter):
```sql
selectPagesBySectionId:
SELECT * FROM pages WHERE section_id = ? ORDER BY name ASC LIMIT ? OFFSET ?;
```

No `.stele-index.json` file is needed. The SQLite `pages` table is already the index; it just needs the `section_id` column and the section-aware query set.

---

## Summary table

| Research question | Finding |
|---|---|
| Two-phase load adaptation for FR-5 | `METADATA_ONLY` + `is_content_loaded=0` + `loadFullPage()` already provides the required infrastructure. Add section path-prefix filter to `loadDirectory()`. |
| ktoml in build.gradle.kts | **Not yet added.** ADR-011 specifies `ktoml-core:0.7.1` (MIT, wasmJs-confirmed). Must be added as Phase 1 work. |
| Desktop lazy content mechanism | Content-on-demand via `loadFullPage()` is already free on Desktop (files on disk). `git sparse-checkout` is optional defense-in-depth for NFR-5 (files never land on disk). |
| OQ-3 index format | SQLite `pages` rows (`is_content_loaded=0`) are the universal index. Add `section_id TEXT` column with MigrationRunner entry. No separate index file needed. |
