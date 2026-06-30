# Graph Sections — Implementation Plan

**Status**: Draft  
**Date**: 2026-06-29  
**Requirements**: `project_plans/graph-sections/requirements.md`  
**Research**: `project_plans/graph-sections/research/`

> **v2 — 2026-06-29**: Updated for device profile model (FR-12), three-state subscriptions (FR-4),
> auto-assignment (FR-2), ambient context indicator (FR-13), and cross-section backlink policy (FR-14).
> Sensitivity field (FR-7) reserved in schema for v2 encryption enforcement.

---

## Architecture Summary

Graph Sections partitions a single SteleKit graph into named, independently-syncable
slices. Each section owns a directory prefix for pages (`pages/acme-work/`) and journals
(`journals/acme-work/`), declared in a `.stele-sections` TOML manifest at the graph root.
Each device holds a local subscription list in `PlatformSettings`; only subscribed sections
are materialized on that device.

The core data model addition is a `section_id TEXT` column on the `pages` table plus a
`(journal_date, section_id)` composite index that prevents the journal-date-collision
data-corruption bug. A new `SectionManifest` / `SectionDefinition` model (parsed by
`ktoml-core:0.7.1`) is the runtime representation of the manifest. A `SectionFilter` value
object, injected into `GraphLoader`, encapsulates the "should this file path be excluded?"
predicate; it is null when no `.stele-sections` file exists, preserving exact backward
compatibility (FR-11, G-7).

Desktop lazy content is already implemented via the existing `ParseMode.METADATA_ONLY` +
`is_content_loaded = 0` + `loadFullPage()` pipeline. The section feature adds only:
(a) a new `ParseMode.INDEX_ONLY` mode that skips `readFileDecrypted()` entirely,
deriving all stub metadata from the file path alone; and (b) call-site narrowing in
`loadGraphProgressive()` that passes section-specific prefix directories to
`loadDirectory()` and `loadJournalsImmediate()` instead of always scanning root `pages/`
and `journals/`. This is the fix for the non-recursive `FileSystem.listFiles()` blocker —
there is no need to make scanning recursive; instead the caller passes the correct
leaf directory.

WASM lazy content is the ADR-013 path: `WasmSectionSyncService` requests GitHub REST API
tree listings only for subscribed `pagePathPrefix` / `journalPathPrefix` directories,
creates `INDEX_ONLY` stub Page rows, and defers all blob fetches. `loadFullPage()` on WASM
hits a platform-specific `FileSystem.actual` that checks the OPFS cache first, then falls
back to a GitHub raw-content fetch. `indexRemainingPages()` is extended with a
`section_id IN (...)` filter to prevent draining non-subscribed stubs — the WASM
rate-limit-bomb fix.

The UI layer adds a section badge in the page header, a section picker, a "New work
journal entry" action (FR-10), and a Settings → Sections panel for managing sections and
device subscriptions. Cross-section backlinks (links to pages in Removed sections) render as plain text with a
subtle "?" badge and "Content not available on this device" on hover/tap — no section name
or path is leaked (FR-14). Ghost-link UI with section tooltip is explicitly descoped.

---

## Epic 1 — Data model + manifest parser

**Gate**: Must be complete before Epics 2–5 begin. No production behavior changes in this
epic — all code is additive (new class, new column, new queries).

### Story 1.1 — ktoml dependency + SectionManifest model

- [ ] Add `implementation("com.akuleshov7:ktoml-core:0.7.1")` to `commonMain.dependencies`
  in `kmp/build.gradle.kts`. Confirm `./gradlew :kmp:compileKotlinJvm` passes. (est: 1h)
- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sections/SectionDefinition.kt`
  with `@Serializable data class SectionDefinition(val id: String, val displayName: String,
  val color: String? = null, val pagePathPrefix: String, val journalPathPrefix: String,
  val sensitivity: String = "normal")`. Values: `"normal"` (default) or `"sensitive"`.
  Parsed and stored in v1; PIN/biometric enforcement deferred to v2 (per FR-7 and Appendix B).
  (est: 1h)
- [ ] Update `SectionManifestParser` test fixture (Story 1.2 / Story 6.1) to verify that
  `sensitivity = "sensitive"` in the TOML round-trips correctly and that omitting the field
  defaults to `"normal"`. (est: 0.5h)
- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sections/SectionManifest.kt`
  with `@Serializable data class SectionManifest(val version: Int = 1,
  @SerialName("section") val sections: List<SectionDefinition> = emptyList())`. Include a
  companion `FILENAME = ".stele-sections"` constant. (est: 1h)

### Story 1.2 — SectionManifestParser

- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sections/SectionManifestParser.kt`.
  Reads `.stele-sections` from graph root via `FileSystem.readFile()`, decodes with
  `Toml(TomlConfig(ignoreUnknownNames = true)).decodeFromString<SectionManifest>(content)`.
  Returns `null` when file is absent (no-sections backward compat). Wraps parse errors as
  `DomainError.ParseError`. (est: 2h)
- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sections/SectionManifestWriter.kt`.
  Serializes `SectionManifest` back to TOML via ktoml encode and writes to graph root via
  `FileSystem.writeFile()`. Used by Settings section management actions. (est: 2h)

### Story 1.3 — DB schema: section_id column + journal composite index

- [ ] **[B1]** Add `section_id TEXT NOT NULL DEFAULT ''` column to the `pages` table in
  `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`. Place after
  `backlink_count`. Use `''` (empty string) as the sentinel for global (no-section) pages —
  **do NOT use NULL**; `NULL != NULL` in SQLite unique checks, so two global journal rows
  for the same date would both be admitted instead of colliding. (est: 1h)
- [ ] **[B1]** In `SteleDatabase.sq` `CREATE TABLE pages`, remove the inline `UNIQUE` from
  `name TEXT NOT NULL UNIQUE COLLATE NOCASE` (change to `name TEXT NOT NULL COLLATE NOCASE`)
  and add a table-level constraint `UNIQUE(name, section_id) COLLATE NOCASE` before the
  closing `);`. This enforces uniqueness per `(name, section_id)` pair: two journal pages
  named `"2026-06-29"` for different sections (`''` vs `"acme-work"`) can coexist. (est: 1h)
- [ ] Add `CREATE INDEX idx_pages_journal_section ON pages(is_journal, journal_date,
  section_id)` in `SteleDatabase.sq` (replaces / supplements existing
  `idx_pages_journal`). (est: 0.5h)
- [ ] **[B1]** Add migration entries in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt` (in `all` list)
  for a new migration id — three steps in order:
  (1) `ALTER TABLE pages ADD COLUMN section_id TEXT NOT NULL DEFAULT ''`;
  (2) `DROP INDEX IF EXISTS sqlite_autoindex_pages_1` (SQLite's auto-named index for the
  inline `UNIQUE` on `name` — name may vary; verify with `.schema pages` at runtime or use
  the copy-alter pattern instead);
  (3) `CREATE UNIQUE INDEX idx_pages_name_section ON pages(name COLLATE NOCASE, section_id)`.
  If the auto-index name is not stable, use the full copy-alter pattern (create `pages_new`
  with the corrected DDL, `INSERT INTO pages_new SELECT ...`, drop old, rename). The
  `MigrationRunnerSchemaSyncTest` will enforce this migration is present. (est: 2h)
- [ ] **[B3]** Update `insertPage` SQL in `SteleDatabase.sq` to include `section_id`:
  change column list to `(..., is_content_loaded, section_id)` and add a `?` parameter.
  Current SQL omits `section_id`, so every insert silently writes `DEFAULT ''` regardless
  of what `Page.sectionId` contains — section assignment is permanently inoperative
  without this change. (est: 0.5h)
- [ ] **[B3]** Update `updatePage` SQL in `SteleDatabase.sq` to include `section_id = ?`
  in the `SET` clause. Same silent-loss risk as insertPage. (est: 0.5h)
- [ ] **[B3]** Update `RestrictedDatabaseQueries` forwarding stubs for `insertPage` and
  `updatePage` in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`
  to include the new `section_id` parameter (SQLDelight regenerates the function signature
  when the SQL changes; the stub must match). (est: 0.5h)
- [ ] Add `selectJournalPageByDateAndSection(journalDate: String, sectionId: String):` query
  to `SteleDatabase.sq` (note: `sectionId` is `String`, not `String?` — callers pass `""`
  for global journals):
  `SELECT * FROM pages WHERE is_journal = 1 AND journal_date = ? AND section_id = ? LIMIT 1;`
  (est: 1h)
- [ ] Add `selectPagesBySectionId(sectionId: String, limit: Long, offset: Long):` query
  to `SteleDatabase.sq`:
  `SELECT * FROM pages WHERE section_id = ? ORDER BY name ASC LIMIT ? OFFSET ?;` (est: 0.5h)
- [ ] Add `selectUnloadedPagesBySection(sectionIds: Collection<String>, limit: Long, offset: Long):`
  query (uses `WHERE is_content_loaded = 0 AND section_id IN ?`) for the rate-limit-bomb
  fix. Callers include `""` in the collection for global stubs. Note: parameter collection
  bound at call site to ≤ 11 items (NFR-6 guarantees ≤ 10 sections + 1 for `""`). (est: 1h)
- [ ] Run `./gradlew :kmp:generateCommonMainSteleDatabase && rsync -a
  kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/ kmp/src/generated/sqldelight/`
  and confirm generated Kotlin compiles. (est: 1h)

### Story 1.4 — Page model: sectionId field

- [ ] Add `val sectionId: String` to the `Page` data class in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`. Default `""` (empty
  string = global, no section). Do NOT use `String?`; the DB column is `NOT NULL DEFAULT ''`
  and nullable adds unnecessary null-checks at every call site. (est: 1h)
- [ ] Update `toModel()` in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightPageRepository.kt`
  to read the new `section_id` column. Update all `insertPage` / `updatePage` call sites
  in the same file to pass `sectionId` (the SQL now requires it — see Story 1.3 B3 tasks).
  (est: 2h)
- [ ] Update `InMemoryRepositories.kt`'s in-memory `Page` storage to store/return `sectionId`.
  Update `InMemoryRepositories.getJournalPageByDate()` to add a section-aware overload
  `getJournalPageByDateAndSection(date, sectionId)` where `sectionId: String` (pass `""`
  for global journals). (est: 2h)
- [ ] Add `GraphWriter.movePageToSection(page: Page, newSection: SectionDefinition):
  Either<DomainError, Page>` in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`. When
  `Page.sectionId` changes (via section picker in Story 5.2 or auto-assignment in Story 5.8),
  this method moves the backing `.md` file from the old section's `pagePathPrefix` directory
  to the new section's `pagePathPrefix` directory using `FileSystem.renameFile()`, then
  updates the `section_id` column in the DB and returns the relocated `Page`. Old path is
  derived from the previous `sectionId` via `SectionManifest` lookup; falls back to the
  current `page.filePath` if the old section is not found in the manifest. (est: 3h)

### Story 1.5 — JournalDateResolver section-aware interface

- [ ] Add `suspend fun getJournalPageByDateAndSection(date: LocalDate, sectionId: String):
  Page?` to the `JournalDateResolver` functional interface in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt` (or
  wherever JournalDateResolver is defined). `sectionId = ""` for global journals. Provide
  a default implementation that falls back to the existing single-entry lookup for callers
  that haven't been updated. (est: 1h)
- [ ] Implement the query in `SqlDelightPageRepository`: wire to
  `selectJournalPageByDateAndSection`. (est: 1h)

### Story 1.6 — SectionFilter

- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sections/SectionFilter.kt`.
  Constructor: `SectionFilter(subscribedSections: List<SectionDefinition>,
  allSections: List<SectionDefinition>)`. Method `shouldExclude(filePath: String): Boolean`
  returns `true` when `filePath` starts with any `allSections` path prefix AND that
  prefix is not in `subscribedSections`. Method `sectionIdForPath(filePath: String):
  String?` returns the section id whose `pagePathPrefix` or `journalPathPrefix` matches
  the path prefix, or `null` for global files. (est: 2h)
- [ ] Update `GraphLoader` constructor in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` to accept
  `sectionFilter: SectionFilter? = null`. Store as a field. No behavior change when null
  (backward compat). (est: 1h)

### Story 1.7 — Device profile in PlatformSettings (three-state subscription)

- [ ] Define `enum class SectionState { ACTIVE, HIDDEN, REMOVED }` in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sections/SectionState.kt`. (est: 0.5h)
- [ ] Add `defaultSection: String` (empty string = global) and
  `sectionStates: Map<String, SectionState>` to the device profile storage in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt` and all
  platform-specific `actual` implementations (JVM, Android, WASM). Persist as a JSON
  string: `{"acme-work": "active", "personal": "removed", "health": "hidden"}`. Replace any
  existing `subscribedSectionIds: List<String>` key with this map. (est: 2h)
- [ ] Update all call sites that read the old subscription list:
  - "On-disk" sections (ACTIVE + HIDDEN — materialized locally):
    `sectionStates.filterValues { it != SectionState.REMOVED }.keys`
  - "Visible in UI" sections (ACTIVE only):
    `sectionStates.filterValues { it == SectionState.ACTIVE }.keys`
  Files: `GraphLoader.kt`, `StelekitViewModel.kt`, `GitSyncService.kt`,
  `WasmSectionSyncService.kt` (once created). (est: 1h)

---

## Epic 2 — Desktop sync filtering + sparse-checkout integration

**Prerequisite**: Epic 1 complete.  
**Can run in parallel with**: Epics 3, 4, 5.

### Story 2.1 — Section-aware loadGraphProgressive

- [ ] In `GraphLoader.loadGraphProgressive()` in `GraphLoader.kt`, after opening the graph
  path, call `SectionManifestParser.parse("$graphPath/${SectionManifest.FILENAME}")`.
  Load the device profile from `PlatformSettings` (`sectionStates` map). Build a
  `SectionFilter` from manifest + the on-disk section set
  (`sectionStates.filterValues { it != SectionState.REMOVED }`) and store it in the
  `sectionFilter` field. HIDDEN sections are included here — their files are on disk and
  must be indexed; they are filtered out at the UI query layer (Story 3.2), not here.
  When manifest is absent, leave `sectionFilter = null`. (est: 3h)
- [ ] Replace the single `loadDirectory("$graphPath/pages", ...)` call with: (a) a call
  scoped to the root `pages/` dir using `SectionFilter.shouldExclude()` predicate for
  global pages, plus (b) one `loadDirectory(section.pagePathPrefix, ...)` call per
  subscribed section. Pass the `sectionFilter` predicate into `loadDirectory()` as a new
  optional `pathFilter: ((String) -> Boolean)?` parameter; apply it in the inner loop
  before `readFileDecrypted()`. File:
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`. (est: 3h)
- [ ] Replace the single `loadJournalsImmediate("$graphPath/journals", ...)` and
  `loadRemainingJournals("$graphPath/journals", ...)` calls with paired calls: one for the
  root `journals/` dir (global journals only — files directly in the dir, not
  subdirectories) and one per subscribed section's `journalPathPrefix`. File:
  `GraphLoader.kt`. (est: 2h)
- [ ] **[B2]** Patch the `backgroundIndexJob` warm-start reconcile in
  `loadGraphProgressive()` (the second `loadDirectory(pagesDir, ...)` call, roughly ~line
  585 on the warm path) to use the same section-filtered path list as the cold-start path
  above — i.e., root `pages/` dir with global-only filter plus one
  `loadDirectory(section.pagePathPrefix, ...)` per subscribed section. Without this fix,
  every warm restart (the common case after the first session) ignores all section
  subdirectories and loads zero section pages. File: `GraphLoader.kt`. (est: 1h)

### Story 2.2 — resolvePageFilePath section fallback

- [ ] In `GraphLoader.resolvePageFilePath()`, add a fallback loop after the four existing
  hardcoded candidate paths: for each subscribed section in `sectionFilter`, try
  `"$graphPath/${section.pagePathPrefix}/$pageName.md.stek"` and
  `"$graphPath/${section.pagePathPrefix}/$pageName.md"`. Return the first path that exists
  on `fileSystem`. File: `GraphLoader.kt`. (est: 2h)

### Story 2.3 — File watcher section registration

- [ ] In `GraphLoader` (where `fileWatcher.startWatching` is called), register each
  subscribed section's `pagePathPrefix` and `journalPathPrefix` directories in addition
  to the existing root paths. File: `GraphLoader.kt` and
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt` (if watcher
  needs explicit multi-path registration). (est: 2h)
- [ ] Implement `GraphLoader.addSectionSubscription(section: SectionDefinition)`: (a) adds
  section to the active `SectionFilter`, (b) calls `loadDirectory(section.pagePathPrefix,
  ParseMode.METADATA_ONLY)` and `loadJournalsImmediate(section.journalPathPrefix, ...)`,
  (c) registers the new prefix dirs with `fileWatcher`, (d) cancels and relaunches
  `backgroundIndexJob`. File: `GraphLoader.kt`. (est: 3h)

### Story 2.4 — parseAndSavePage: resolve sectionId from path

- [ ] In `GraphLoader.parsePageWithoutSaving()`, after parsing frontmatter, call
  `sectionFilter?.sectionIdForPath(filePath)` to resolve `sectionId`. If
  `stele-section::` frontmatter property is present, prefer it; otherwise fall back to
  path-based inference. Assign `sectionId` to the `Page` model before saving. File:
  `GraphLoader.kt`. (est: 2h)

### Story 2.5 — Git sparse-checkout for initial clone (defense-in-depth)

- [ ] In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`, add a
  `cloneSectionSlice(remote: String, targetDir: String, sections: List<SectionDefinition>)`
  method that runs `git clone --filter=blob:none --sparse <remote> <targetDir>` then
  `git -C <targetDir> sparse-checkout set <cone paths>` where cone paths are the
  `pagePathPrefix` and `journalPathPrefix` values for **ACTIVE and HIDDEN** sections only.
  REMOVED sections must never appear in the sparse-checkout cone — their directories must
  not exist on this device. (est: 3h)
- [ ] Add `GitSyncService.addSparseCheckoutCone(sectionPagePath: String,
  sectionJournalPath: String)` that runs `git sparse-checkout add <paths>` when a section
  transitions to ACTIVE or HIDDEN (i.e., newly on-disk). File: `GitSyncService.kt`. (est: 2h)

### Story 2.6 — Transition ACTIVE/HIDDEN → REMOVED: local file deletion

- [ ] In `StelekitViewModel.kt`, implement `removeSectionFromDevice(section: SectionDefinition)`:
  (a) show confirmation dialog listing the section and file count to be deleted; (b) on
  confirm, call `GitSyncService.removeSparseCheckoutCone(section.pagePathPrefix,
  section.journalPathPrefix)` which runs `git sparse-checkout remove <prefix>`;
  (c) delete the local section directories via `FileSystem.deleteDirectory()`; (d) update
  `sectionStates[section.id] = SectionState.REMOVED` in `PlatformSettings`; (e) reload
  `GraphLoader` to evict DB rows for the removed section. No network needed — this is a
  local-only operation. (est: 2h)
- [ ] Add `GitSyncService.removeSparseCheckoutCone(sectionPagePath: String,
  sectionJournalPath: String)` that runs `git -C <graphPath> sparse-checkout remove <paths>`.
  File: `GitSyncService.kt`. (est: 0.5h)

### Story 2.7 — Transition REMOVED → ACTIVE: download and register section

- [ ] In `StelekitViewModel.kt`, implement `activateSectionOnDevice(section: SectionDefinition)`:
  (a) show confirmation dialog: "This will download [Section Name] content to this device.
  Are you sure?"; (b) on confirm, call `GitSyncService.addSparseCheckoutCone(...)`;
  (c) git lazily materializes blobs on first file read — no explicit blob fetch needed;
  (d) update `sectionStates[section.id] = SectionState.ACTIVE` in `PlatformSettings`;
  (e) call `GraphLoader.addSectionSubscription(section)` to register path prefixes with
  the file watcher and trigger index loading. Show a progress indicator while
  `backgroundIndexJob` runs. (est: 2h)

---

## Epic 3 — Split journals

**Prerequisite**: Epic 1 complete.  
**Can run in parallel with**: Epics 2, 4, 5.

### Story 3.1 — Journal date collision fix

- [ ] Update `GraphLoader`'s `journalDateResolver` lambda (currently set to
  `pageRepository.getJournalPageByDate(date).first().getOrNull()`) to instead call
  `pageRepository.getJournalPageByDateAndSection(date, sectionId)`. The `sectionId` is
  derived from the `filePath` being parsed via `sectionFilter?.sectionIdForPath(filePath)`.
  File: `GraphLoader.kt`. (est: 2h)
- [ ] Update `parseAndSavePage()` in `GraphLoader.kt` to pass the resolved `sectionId`
  through to the `JournalDateResolver` lookup so the discriminator is always correct even
  when `sectionFilter` is null (sectionId stays null = global journal). (est: 1h)
- [ ] Update `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/JournalService.kt`
  (or the journals query in `SqlDelightPageRepository.kt`) so that `getJournalPages()`
  and related flow queries are section-aware: only return journals for subscribed sections
  + global journals on the current device. (est: 2h)

### Story 3.2 — Journal view routing

- [ ] Update `JournalsViewModel.kt` in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/`
  to pass only **ACTIVE** section IDs into the journal query (not HIDDEN, not REMOVED).
  `JournalsView` must show only ACTIVE-section journals + global journals; HIDDEN section
  journal entries must be invisible even though their files are on disk. Read
  `sectionStates.filterValues { it == SectionState.ACTIVE }.keys` from `PlatformSettings`
  and pass as `activeSectionIds: List<String>` to the repository query. (est: 2h)
- [ ] Update `JournalsView.kt` in the same directory to show a section label or color dot
  alongside journal entries that belong to a section (uses `Page.sectionId` +
  `SectionManifest` for display name). (est: 2h)

### Story 3.3 — New work journal entry action (FR-10)

- [ ] Implement `GraphLoader.createSectionJournalPage(sectionId: String, date: LocalDate):
  Either<DomainError, Page>` in `GraphLoader.kt`. Creates
  `journals/<sectionId>/<date>.md` if absent, parses it into a Page row with
  `isJournal=true`, `sectionId`, `journalDate=date`, returns the Page. (est: 2h)
- [ ] Add "New work journal entry for today" command to the command palette in
  `StelekitViewModel.kt`. Show section picker if multiple sections are subscribed. Wire to
  `createSectionJournalPage()` then `navigateTo()`. (est: 2h)
- [ ] Add a "New work journal entry" shortcut button to the sidebar in `App.kt`
  (visible only when at least one non-global section is subscribed). (est: 1h)

---

## Epic 4 — WASM section fetch filtering + lazy page content

**Prerequisite**: Epic 1 complete.  
**Can run in parallel with**: Epics 2, 3, 5.  
**Note**: OQ-5 (WASM write-back credential storage) must be resolved before the write path
can be implemented. The read path (fetch + OPFS cache) can proceed independently.

### Story 4.1 — INDEX_ONLY ParseMode

- [ ] Add `INDEX_ONLY` variant to the `ParseMode` enum in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ParseMode.kt` (or wherever
  `ParseMode` is defined — research found `GraphLoader.kt` and `MarkdownPageParser.kt`
  reference it). (est: 0.5h)
- [ ] Implement the `INDEX_ONLY` code path in `GraphLoader.loadDirectory()` inner loop:
  when `parseMode == INDEX_ONLY`, skip `readFileDecrypted()` entirely; instead derive
  `name` from filename (strip `.md` / `.md.stek` extension), `filePath` from path,
  `isJournal` from `"/journals/"` in path, `journalDate` from filename if journal, and
  insert a stub `Page` row with `isContentLoaded = false`. The `sectionId` is resolved
  via `sectionFilter?.sectionIdForPath(filePath)`. File: `GraphLoader.kt`. (est: 3h)

### Story 4.2 — WasmSectionSyncService

- [ ] Create
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncService.kt`.
  Takes `subscribedSections: List<SectionDefinition>` and GitHub repo config from
  `PlatformSettings`. For each subscribed section, calls the GitHub REST API
  `GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1` filtered to
  `pagePathPrefix` and `journalPathPrefix`. Maps each tree entry to an `INDEX_ONLY`
  Page stub insert via `GraphLoader.loadDirectory(IndexOnlyTreeSource(...))` or a
  direct `pageRepository` insert. (est: 4h)
- [ ] Filter `indexRemainingPages()` in `GraphLoader.kt` to use
  `selectUnloadedPagesBySection(subscribedSectionIds + listOf(""))` instead of the
  current `selectUnloadedPagesPaginated()` call, so the background drain never attempts
  to resolve non-subscribed stubs. Pass `""` (empty string) to include global stubs.
  When `sectionFilter == null` (no sections), fall back to the existing unbounded drain.
  (est: 2h)
- [ ] **[GraphQL upgrade]** Replace the REST tree listing in `WasmSectionSyncService` with
  the GitHub GraphQL API: `POST /graphql` with a `repository.object.entries` recursive tree
  query filtered to the subscribed `pagePathPrefix` / `journalPathPrefix`. A single GraphQL
  request returns the full recursive tree for a path prefix vs. paginated REST calls
  (`GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1` is depth-limited and paginates
  via `truncated: true`). This cuts "new device first sync" latency for large sections.
  File: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncService.kt`.
  (est: 2h, add to 4h base for a revised 6h total for Story 4.2)

### Story 4.3 — WASM lazy content fetch via OPFS + GitHub REST

- [ ] In
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`,
  implement `readFile(filePath)` to: (a) check OPFS for a cached copy at the path,
  return cached content if present; (b) otherwise call GitHub raw-content REST endpoint
  `GET /repos/{owner}/{repo}/contents/{filePath}`, write the response body to OPFS, and
  return content. This is the WASM `actual` of the `FileSystem` `expect` that
  `loadFullPage()` already calls. (est: 4h)
- [ ] Add HTTP 429 exponential backoff in the WASM GitHub REST client: on 429, parse
  `Retry-After` header, delay, retry up to 3 times; after 3 failures emit
  `DomainError.NetworkError.RateLimited(retryAfterSeconds)`. Surface this error in
  `StelekitViewModel` as a transient toast / banner. File:
  `PlatformFileSystem.kt` + `StelekitViewModel.kt`. (est: 2h)
- [ ] Ensure WASM `loadFullPage()` integration: `navigateTo()` → `loadFullPage(pageUuid)`
  → `fileSystem.readFile(filePath)` already calls the WASM `actual`. Confirm no code
  path bypasses the platform `readFile()`. Add a "Loading…" `isLoading` state to
  `StelekitViewModel` displayed while the WASM fetch is in-flight. File:
  `StelekitViewModel.kt`. (est: 2h)

---

## Epic 5 — Section UI (badge, picker, settings)

**Prerequisite**: Epic 1 complete.  
**Can run in parallel with**: Epics 2, 3, 4.

### Story 5.1 — Section badge composable

- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SectionBadge.kt`.
  Composable `SectionBadge(section: SectionDefinition, onClick: () -> Unit)`: color dot
  (from `section.color`) + `section.displayName` text, pill shape, tappable. No-op when
  `section.color` is null (use neutral color). (est: 2h)
- [ ] Integrate `SectionBadge` into the page header area in `PageView.kt` (in
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`). Show badge
  only when `page.sectionId != null` and a matching `SectionDefinition` exists in the
  loaded manifest. (est: 1h)
- [ ] Add a section label to journal entry headers in `JournalsView.kt`: show "Work
  journal" / "Personal journal" based on `page.sectionId` + manifest lookup. (est: 1h)
- [ ] Ensure that when the section badge triggers a section change (via Story 5.2's picker),
  the handler calls `GraphWriter.movePageToSection()` (Story 1.4) to move the backing file —
  not just a DB update. The badge picker must be disabled for journal pages (journal
  section is fixed by the file's `journalPathPrefix` directory). (est: 1h)

### Story 5.2 — Section picker dialog

- [ ] Create
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SectionPickerDialog.kt`.
  Shows all sections from the manifest plus "(Global)" as options; current selection
  highlighted. Confirms with "Move to section" button. (est: 2h)
- [ ] Wire badge tap → `SectionPickerDialog`. On selection: (a) update `stele-section::`
  frontmatter in the page file via `GraphWriter`, (b) if the page's directory does not
  match the new section's `pagePathPrefix`, offer an atomic rename via
  `FileSystem.renameFile(oldPath, newPath)` + `GraphLoader.reloadFiles(listOf(newPath))`.
  File: `StelekitViewModel.kt`. (est: 3h)

### Story 5.3 — Settings: section management

- [ ] Add a "Sections" panel to the existing Settings screen (in `App.kt` or
  `ScreenRouter.kt`). Lists sections from `SectionManifest` with display name, color
  dot, and page/journal path prefixes. (est: 2h)
- [ ] "Create section" action: show dialog for `displayName`, `id` (auto-slugified from
  displayName), `color`, `pagePathPrefix`, `journalPathPrefix`. On confirm, update
  `SectionManifest` via `SectionManifestWriter`, create the two directories, write
  `.stele-sections`. (est: 2h)
- [ ] "Rename section" action: update `displayName` in `SectionManifest` and rewrite
  `.stele-sections`. (ID and path prefixes are immutable per spec.) (est: 1h)
- [ ] "Delete section" action: remove section from `SectionManifest`. Pages lose their
  `stele-section::` frontmatter property and revert to global (sectionId = null). Show
  confirmation dialog listing affected page count. File: `StelekitViewModel.kt`,
  `SectionManifestWriter.kt`. (est: 2h)

### Story 5.4 — Settings: device subscriptions

- [ ] Add a "Device subscriptions" sub-panel within the Sections settings. Lists all
  sections with a toggle per section. Currently-subscribed sections are toggled on. (est: 2h)
- [ ] On toggle-on: save to `PlatformSettings`, call
  `GraphLoader.addSectionSubscription(section)`. On toggle-off: remove from
  `PlatformSettings`; show restart-required banner (live unsubscription is deferred to v2).
  File: `StelekitViewModel.kt` + `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt`
  + platform-specific `actual` files. (est: 2h)

### Story 5.5 — Cross-section backlink rendering (FR-14)

- [ ] In the wikilink renderer (`WikilinkParser.kt` and the Markdown render path in
  `App.kt`), when a `[[Page Name]]` target UUID is **not present in the local DB**
  (i.e., it belongs to a Removed section or was never synced), render the reference as
  plain text (`PageName`) with a subtle "?" badge alongside it — **no section name, no
  path, no tooltip revealing which section the target belongs to**. On hover/tap show only
  "Content not available on this device." Do NOT consult the manifest for section
  attribution — that would leak which section the missing page belongs to (privacy
  violation per FR-14). (est: 2h)
- [ ] Confirm that if a linked page IS in a Hidden section (files on disk, DB row exists
  but not shown in UI), the link still navigates normally — Hidden pages are accessible
  via direct link even if not surfaced in lists. Only Removed-section pages (no DB row)
  get the plain-text treatment. (est: 0.5h)

### Story 5.6 — First-launch wizard (FR-12)

- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/onboarding/DeviceSetupWizard.kt`.
  Shown once after the remote is configured and `.stele-sections` is detected. Three-option
  flow:
  - **Work device**: prompts user to pick their primary work section; sets
    `defaultSection = <picked section id>` and all other sections to REMOVED in
    `PlatformSettings`.
  - **Personal device**: sets all sections to ACTIVE; `defaultSection = ""` (global).
  - **Custom**: lists all sections with a three-state toggle (Active / Hidden / Removed)
    and a `defaultSection` picker.
  Writes `defaultSection` and `sectionStates` to `PlatformSettings` in one atomic step.
  Triggers `GraphLoader` to apply the profile immediately. (est: 4h)
- [ ] Persist a `deviceSetupComplete: Boolean` flag in `PlatformSettings` so the wizard
  is never shown again after first completion. (est: 0.5h)

### Story 5.7 — Ambient context indicator (FR-13)

- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SectionContextIndicator.kt`.
  Persistent composable placed in the sidebar header (in `App.kt`). Shows the
  `defaultSection`'s color dot and display name (e.g., "● Acme Work"). When
  `defaultSection = ""`, shows "All sections" or is hidden (per UX decision). (est: 2h)
- [ ] Tapping `SectionContextIndicator` opens a `SectionQuickTogglePanel` bottom sheet /
  popup listing all sections with their current state (Active / Hidden). Toggle between
  Active ↔ Hidden is instant (no network, no confirmation). A "Manage sections" link
  navigates to Settings → Sections for Removed/download actions. The panel must NOT expose
  the Removed state directly — only Settings does (avoids accidental re-downloads). (est: 2h)

### Story 5.8 — New-page auto-assignment (FR-2)

- [ ] In `StelekitViewModel.createNewPage()` (or wherever new pages are created), read
  `defaultSection` from `PlatformSettings`. If non-empty, create the new page file under
  `pages/<defaultSection>/` (the section's `pagePathPrefix`) instead of the root `pages/`
  directory. Set `Page.sectionId = defaultSection`. The section badge (Story 5.1) shows the
  auto-assigned section and allows the user to change it via the picker (which triggers
  `GraphWriter.movePageToSection()` per Story 5.2). If `defaultSection = ""`, create in
  root `pages/` as today. (est: 3h)

### Story 5.9 — "Today" journal routing (FR-3)

- [ ] Update the "Today" journal action in `StelekitViewModel.kt` (and the sidebar "Today"
  button in `App.kt`) to route based on `defaultSection` from `PlatformSettings`:
  - If `defaultSection != ""`: open `journals/<defaultSection>/<date>.md` (the section's
    `journalPathPrefix/<date>.md`), creating it via `GraphLoader.createSectionJournalPage()`
    (Story 3.3) if absent.
  - If `defaultSection = ""`: open the global `journals/<date>.md` as today.
  Add a "Switch journal context" command-palette entry that allows explicitly opening the
  global journal or any ACTIVE section's journal regardless of `defaultSection`. (est: 2h)

---

## Epic 6 — Tests

Tests are written per-epic and run after the corresponding epic passes review.

### Story 6.1 — Epic 1: data model tests

- [ ] Unit test `SectionManifestParser` in
  `kmp/src/commonTest/kotlin/dev/stapler/stelekit/sections/SectionManifestParserTest.kt`:
  (a) valid two-section TOML parses correctly; (b) missing file returns null;
  (c) unknown TOML fields are silently ignored (`ignoreUnknownNames`). (est: 2h)
- [ ] Unit test `SectionFilter.shouldExclude()` in
  `kmp/src/commonTest/kotlin/dev/stapler/stelekit/sections/SectionFilterTest.kt`:
  global page passes, subscribed section page passes, non-subscribed section page is
  excluded, path that matches no section prefix passes. (est: 2h)
- [ ] Unit test `SectionFilter.sectionIdForPath()`: file under `pages/acme-work/` returns
  `"acme-work"`, file under `pages/` root returns null, file under `journals/acme-work/`
  returns `"acme-work"`. (est: 1h)
- [ ] Confirm `MigrationRunnerSchemaSyncTest` in
  `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/MigrationRunnerSchemaSyncTest.kt`
  passes after the `section_id` column and migration entry are added (this test auto-fails
  if migration is missing). (est: 0.5h — verify, not write)

### Story 6.2 — Epic 2: desktop filtering tests

- [ ] Integration test in
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderSectionFilterTest.kt`:
  given a fake graph with `pages/`, `pages/acme-work/`, `pages/personal/` dirs and a
  device subscribed to `acme-work`, `loadGraphProgressive()` loads only pages under
  `pages/acme-work/` and root `pages/` (no section prefix); personal pages absent. (est: 3h)
- [ ] Integration test: `resolvePageFilePath()` finds `pages/acme-work/My Page.md` when
  `acme-work` is in the subscription. (est: 1h)
- [ ] Integration test: `GraphLoader.addSectionSubscription(personalSection)` causes
  `personal`-section pages to appear in the DB without restarting the graph. (est: 2h)

### Story 6.3 — Epic 3: split journal tests

- [ ] Unit test in
  `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/SplitJournalTest.kt`:
  loading `journals/2026-06-29.md` (global) and `journals/acme-work/2026-06-29.md`
  (section) in the same graph produces two separate `Page` rows with different UUIDs
  and correct `sectionId` values. (est: 2h)
- [ ] Unit test: `getJournalPageByDateAndSection(2026-06-29, null)` returns the global
  journal; `getJournalPageByDateAndSection(2026-06-29, "acme-work")` returns the section
  journal. (est: 1h)
- [ ] Unit test: `createSectionJournalPage("acme-work", today)` creates
  `journals/acme-work/<today>.md` and inserts a Page row with the correct fields. (est: 1h)

### Story 6.4 — Epic 4: WASM fetch filtering tests

- [ ] Unit test `ParseMode.INDEX_ONLY` in
  `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/IndexOnlyParseTest.kt`: processing
  a directory of 100 file entries in INDEX_ONLY mode makes zero `readFile()` calls and
  inserts 100 stub Page rows with `isContentLoaded = false`. (est: 2h)
- [ ] Unit test: `indexRemainingPages()` with a filter for `["acme-work"]` only drains
  stubs where `section_id = "acme-work"` or `section_id IS NULL`; stubs for other
  sections are not drained. (est: 2h)
- [ ] Unit test `WasmSectionSyncService` with a mocked GitHub REST response: correct Page
  stubs created for subscribed prefixes; paths outside subscribed prefixes generate no
  stubs. (est: 2h)
- [ ] Unit test: WASM `readFile()` with a mocked HTTP 429 response triggers exponential
  backoff and emits `DomainError.NetworkError.RateLimited` after 3 failures. (est: 2h)

### Story 6.5 — Epic 5: UI tests

- [ ] Screenshot test `SectionBadgeScreenshotTest.kt` in `jvmTest` (Roborazzi): renders
  `SectionBadge` with a hex color and display name; verifies color dot and label. (est: 2h)
- [ ] UI test `SectionPickerTest.kt` in `jvmTest`: tapping a section badge, selecting a
  new section, and confirming calls `GraphWriter.movePageToSection()` and moves the file to
  the new section's `pagePathPrefix` directory. (est: 2h)
- [ ] Unit test: settings subscription toggle persists `sectionStates` map to
  `PlatformSettings` and is read back correctly after a simulated restart. (est: 1h)

### Story 6.6 — DeviceProfileTest (FR-12)

- [ ] Unit test in
  `kmp/src/businessTest/kotlin/dev/stapler/stelekit/sections/DeviceProfileTest.kt`:
  - Work-device wizard path sets `defaultSection = "acme-work"` and all other sections to
    REMOVED in `PlatformSettings`.
  - Personal-device wizard path sets all sections to ACTIVE and `defaultSection = ""`.
  - Custom wizard path stores exactly the user-selected states.
  - `deviceSetupComplete` flag is set to `true` after any wizard completion; wizard is not
    shown on subsequent launches. (est: 2h)

### Story 6.7 — ThreeStateSubscriptionTest (FR-4)

- [ ] Unit/integration test in
  `kmp/src/businessTest/kotlin/dev/stapler/stelekit/sections/ThreeStateSubscriptionTest.kt`:
  - ACTIVE → HIDDEN: sectionStates updated; no files deleted; section pages absent from
    journal view (Story 3.2 query returns empty for HIDDEN section).
  - ACTIVE → REMOVED: confirmation dialog shown; after confirm, local section directories
    deleted; `git sparse-checkout remove` invoked; DB rows evicted.
  - REMOVED → ACTIVE: confirmation dialog shown; `git sparse-checkout add` invoked;
    `GraphLoader.addSectionSubscription()` called; section pages appear in DB. (est: 3h)

### Story 6.8 — NewPageAutoAssignmentTest (FR-2)

- [ ] Unit test in
  `kmp/src/businessTest/kotlin/dev/stapler/stelekit/sections/NewPageAutoAssignmentTest.kt`:
  - With `defaultSection = "acme-work"`, `createNewPage("Meeting Notes")` creates the
    backing file at `pages/acme-work/Meeting Notes.md` and sets `Page.sectionId = "acme-work"`.
  - With `defaultSection = ""`, `createNewPage("Idea")` creates at `pages/Idea.md` with
    `Page.sectionId = ""`. (est: 2h)

### Story 6.9 — CrossSectionBacklinkRenderTest (FR-14)

- [ ] Unit test in
  `kmp/src/businessTest/kotlin/dev/stapler/stelekit/sections/CrossSectionBacklinkRenderTest.kt`:
  - A wikilink `[[PersonalNote]]` whose target UUID is absent from the local DB (Removed
    section) renders as plain text `PersonalNote` with a "?" badge.
  - The rendered output contains no section name, no path, and no tooltip other than
    "Content not available on this device."
  - A wikilink whose target IS in the DB (Hidden section) renders as a normal navigable link.
  (est: 2h)

---

## Dependency Graph

```
Epic 1 (Data model)
    |
    +──────────────────┬──────────────────┬────────────────────+
    v                  v                  v                    v
Epic 2 (Desktop)  Epic 3 (Journals)  Epic 4 (WASM)       Epic 5 (UI)
    |                  |                  |                    |
    +──────────────────+──────────────────+────────────────────+
                       v
                Epic 6 (Tests — stories run after each parallel epic)
```

Epics 2, 3, 4, and 5 can be assigned to different engineers and run in parallel once
Epic 1 is merged. Epic 6 stories run in lockstep with the epic they test.

---

## Open Questions to Resolve Before Implementation

| # | Question | Blocks | Decision needed from |
|---|----------|--------|---------------------|
| OQ-1 | ~~**Journal view: show both entries or only subscribed-section entry for a date?**~~ **RESOLVED**: Work device journal view shows ONLY the subscribed section's entry for a date. `getJournalPageByDateAndSection(date, sectionId)` replaces `getJournalPageByDate(date)` for section-subscribing devices. Global personal journal entries are hidden on work-subscribed devices (privacy-first). | Epic 3 (Story 3.2) — query signature now fixed | Closed |
| OQ-2 | **Multi-section pages**: Spec says no — confirmed. No action needed. | — | Closed: single-section only |
| OQ-3 | **Index format**: Resolved — SQLite `pages` rows with `is_content_loaded = 0` are the universal page index. No `.stele-index.json` file. | — | Closed |
| OQ-4 | ~~**Work-journal page discovery on work device**~~ **RESOLVED**: WASM calendar shows dates from the GitHub tree listing (seeded as `INDEX_ONLY` stubs by `WasmSectionSyncService`). Content loads on navigation via `loadFullPage()`. `WasmSectionSyncService` must enumerate all journal path entries into stub rows. | Epic 4 (Story 4.2) — scope confirmed | Closed |
| OQ-5 | ~~**WASM write-back credential storage**~~ **RESOLVED**: WASM write-back uses in-memory PAT only (no persistence) for v1. `CredentialStore.retrieve()` returns null on WASM; the write-back flow prompts the user for a token if credential is absent each session. This is acceptable for v1. Write path can proceed; credential persistence deferred to when CredentialStore ADR lands. | Epic 4 (Story 4.3) — write path unblocked | Closed |

All open questions are now resolved. Epics 2–5 can begin in parallel immediately after
Epic 1 is merged.

---

## Epic 7 — SyncTransport interface + GitManager rename

**Gate**: Can run in parallel with Epics 2–5. No behavioral change — mechanical rename only.
**Rationale**: ADR-016. `GitManager` leaks git semantics; every future transport has to fake
`commit`/`status`/`isDirty`. Transport-neutral interface unblocks Epics 8 and 9.

### Story 7.1 — SyncTransport interface + value types

- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sync/SyncTransport.kt` with the
  interface and value types from ADR-016: `SyncTransport`, `TransportCapabilities`,
  `SyncEndpoint` (sealed: `GitRemote`, `LanPeer`, `WebRtcPeer`, `BastionServer`),
  `SyncVersion` (`@JvmInline value class`), `SyncCredentials` (sealed: `Token`,
  `PreSharedKey`, `None`), `RemoteFileEntry`, `SyncedFile`, `LocalFile`. (est: 2h)
- [ ] Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sync/SectionAwareSyncService.kt`
  skeleton: constructor takes `SyncTransport + DeviceProfile + SectionManifest`; single
  `suspend fun sync(): Either<DomainError, SyncResult>` that resolves active section prefixes
  (including `""` for global) and delegates to `transport.listRemote(activePrefixes)`.
  Stub the rest with `TODO()` — wired fully in Epic 8. (est: 2h)

### Story 7.2 — GitSyncTransport (JVM rename)

- [ ] Rename `GitManager` → `SyncTransport` at the interface level (the interface in
  `commonMain`). Rename `GitManagerFactory` → `SyncTransportFactory`. (est: 0.5h)
- [ ] Rename `kmp/src/jvmMain/.../platform/GitManager.kt` to `JvmGitSyncTransport.kt`;
  rename the class `JvmGitManager` → `JvmGitSyncTransport`. Map existing git operations to
  the new interface methods: `pushFiles` → `git commit + push`, `pullFiles` → `git pull`,
  `listRemote` → `git ls-remote --heads` (stub returning empty for now — remote listing
  is not used by `GitSyncService` today). Return `SyncVersion(commitSha)` from `pushFiles`.
  (est: 3h)
- [ ] Rename `GitSyncService` → `SyncCoordinator`. Update all callers
  (`StelekitViewModel`, `GraphManager`, sync status UI state). No logic changes — callers
  use `SyncTransport` interface methods only. (est: 2h)

### Story 7.3 — GitHubRestTransport (WASM rename)

- [ ] Rename `kmp/src/wasmJsMain/.../platform/GitManager.kt` → `GitHubRestTransport.kt`;
  rename `JsGitManager` → `GitHubRestTransport`. Methods continue to return
  `DomainError.NetworkError.HttpError(501, NOT_SUPPORTED)` until ADR-013/ADR-015
  implementation lands (BUG-005 Phase 1 status quo preserved). (est: 1h)

### Story 7.4 — SyncTransport tests

- [ ] Unit test `SectionAwareSyncService` prefix resolution: with `defaultSection = "acme-work"`
  active and `"personal"` removed, `activePrefixes` contains `"pages/acme-work"`,
  `"journals/acme-work"`, and `""` (global), but NOT `"pages/personal"`. Fake
  `SyncTransport` in test. (est: 2h)

---

## Epic 8 — LAN-HTTP transport (pure Kotlin)

**Gate**: Epic 7 complete (needs `SyncTransport` interface).
**Goal**: Two SteleKit devices on the same WiFi network sync directly — no GitHub, no cloud.
Desktop↔Desktop and Desktop↔Android covered. ~500 LOC, no Rust, no new binary blobs.
**Dependency**: `io.ktor:ktor-server-netty` (JVM only — already on classpath if Ktor client
is present; verify and add if not). `javax.jmdns:jmdns:3.5.9` (250KB pure Java, JVM +
Android only — no iOS/WASM; those platforms skip mDNS discovery and use QR code pairing).

### Story 8.1 — JmDNS service advertisement + discovery

- [ ] Create `kmp/src/jvmMain/.../sync/lan/MdnsAdvertiser.kt`. Registers a
  `_stelekit._tcp.local.` mDNS service via `JmDNS.create()` on graph open; deregisters on
  graph close. Service TXT record: `{"v":"1","graphId":"<uuid>","port":"<port>"}`.
  `graphId` is the stable graph UUID from `PlatformSettings` (generated once at first open).
  (est: 3h)
- [ ] Create `kmp/src/jvmMain/.../sync/lan/MdnsDiscovery.kt`. Listens for
  `_stelekit._tcp.local.` services; emits `Flow<LanPeer>` of discovered peers. Filters out
  self (matches `graphId`). (est: 2h)
- [ ] Add `jmdns:3.5.9` to `jvmMain.dependencies` in `kmp/build.gradle.kts`. Confirm
  `bazel build //kmp:desktop_app` passes. (est: 0.5h)

### Story 8.2 — LAN-HTTP server (file-serving side)

- [ ] Create `kmp/src/jvmMain/.../sync/lan/LanSyncServer.kt`. Ktor embedded server
  (Netty) on a random port. Routes:
  - `GET /.stele-manifest` — returns JSON: `{"version":"<SyncVersion.opaque>","files":[{"path":"…","sha256":"…","size":N}]}`.
    Version token = SHA-256 of sorted `(path, sha256)` pairs. Filtered to caller's
    `?prefixes=pages/acme-work,journals/acme-work` query param.
  - `GET /files/{path}` — returns raw file bytes. Checks auth header before serving.
  - `PUT /files/{path}` — writes file to local graph via `FileSystem.writeFile()`. Returns
    `204`. Triggers `GraphLoader` reload of affected page.
  - Auth: `Authorization: Bearer <peerToken>` on every request. `peerToken` is a 32-byte
    random hex string generated at graph open, shared via QR code or NFC at pairing time.
  (est: 6h)
- [ ] Start server on graph open in `GraphManager`; stop on shutdown. Store `port` in
  `PlatformSettings` (changes each run — that's fine, pairing re-discovers via mDNS).
  (est: 1h)

### Story 8.3 — LanHttpTransport (client side)

- [ ] Create `kmp/src/jvmMain/.../sync/lan/LanHttpTransport.kt` implementing `SyncTransport`.
  - `connect(LanPeer, Token)`: store host/port/token; verify with `GET /.stele-manifest`.
  - `listRemote(sectionPrefixes)`: `GET /.stele-manifest?prefixes=…`; parse JSON into
    `List<RemoteFileEntry>`. `SyncVersion = manifest.version` field.
  - `pullFiles(paths)`: parallel `GET /files/{path}` up to `capabilities.maxConcurrentPulls`
    (= 8). Write each to local `FileSystem`.
  - `pushFiles(files, message)`: sequential `PUT /files/{path}` for each dirty file. Returns
    new `SyncVersion` from re-fetched manifest after all PUTs. (`message` is stored as a
    `.stele-sync-log` entry — no git commit on LAN sync.)
  - `hasRemoteChanges(knownVersion)`: `GET /.stele-manifest` → compare `version` field.
  - `capabilities`: `supportsOfflineRead=false, requiresServer=false, maxConcurrentPulls=8,
    isBootstrapOnly=false`.
  (est: 6h)

### Story 8.4 — QR code pairing

- [ ] Generate pairing QR code payload: `{"endpoint":"http://<localIp>:<port>","token":"<peerToken>","graphId":"<uuid>"}`.
  Display as a scannable QR code in Settings → Sync → Pair Device using a KMP-compatible
  QR library (`io.github.alexzhirkevich:qrose:1.0.1` — Compose Multiplatform). (est: 4h)
- [ ] On the receiving device: scan QR (system camera intent / `AVFoundation` on iOS — use
  `expect/actual` wrapper). Parse payload. Store `LanPeer` endpoint + token in
  `PlatformSettings` as a paired device entry. Initiate first sync. (est: 4h)

### Story 8.5 — NFC bootstrap (Android only)

- [ ] Create `kmp/src/androidMain/.../sync/lan/NfcPairingTransport.kt` implementing
  `SyncTransport` with `capabilities.isBootstrapOnly = true`. Uses `android.nfc.NfcAdapter`
  NDEF push to transfer the same pairing payload as the QR code (Story 8.4). On successful
  read, calls `SyncCoordinator.adoptEndpoint(LanPeer)` which hands off to
  `LanHttpTransport`. Returns `DomainError.NotSupported` on devices without NFC. (est: 4h)
- [ ] iOS: NFC bootstrap is QR code only (CoreNFC reader mode, no HCE). `expect/actual`
  stub for `NfcPairingTransport` on iOS returns `isBootstrapOnly=true` and delegates
  immediately to QR flow. (est: 1h)

### Story 8.5b — Wi-Fi Direct bootstrap (Android)

Wi-Fi Direct creates a direct device-to-device WiFi link with no router — one device
becomes a soft AP, the other connects, and normal TCP/IP runs on top. The group owner
always gets IP `192.168.49.1`; the client gets a DHCP-assigned address. Once the link
exists, `LanHttpTransport` handles sync identically to the router case. This is
`isBootstrapOnly = true`: Wi-Fi Direct negotiation yields a `LanPeer`, then hands off.

- [ ] Create `kmp/src/androidMain/.../sync/lan/WifiDirectBootstrapTransport.kt` implementing
  `SyncTransport` with `capabilities.isBootstrapOnly = true`. Uses `WifiP2pManager`
  (`android.net.wifi.p2p`):
  - Discover peers via `discoverPeers()` + `WIFI_P2P_PEERS_CHANGED_ACTION` broadcast.
  - Emit discovered peers as `Flow<WifiP2pDevice>` — display in Settings → Sync → Pair Device
    as a "Nearby Devices" list (no QR code required).
  - Connect via `connect(WifiP2pConfig)`. On `WIFI_P2P_CONNECTION_CHANGED_ACTION`:
    extract group owner IP (`WifiP2pInfo.groupOwnerAddress`) and the local role (owner vs.
    client).
  - Exchange pairing payload (same JSON as QR code — `{token, graphId}`) over a short-lived
    TCP socket on port 42101 immediately after the P2P link is established. Owner listens;
    client connects. This bootstraps `peerToken` without a QR scan.
  - Call `SyncCoordinator.adoptEndpoint(LanPeer(groupOwnerIp, serverPort, peerToken))`.
    `LanSyncServer` (Story 8.2) is already bound to `0.0.0.0` — it answers on the Wi-Fi
    Direct interface automatically.
  - On disconnect or `DomainError`: call `WifiP2pManager.removeGroup()` to tear down the
    soft AP and release the channel.
  (est: 6h)
- [ ] Register `WifiDirectBootstrapTransport` as a connection option in the Pair Device UI
  alongside QR code and NFC. Show as "Connect nearby Android device". (est: 1h)
- [ ] `expect/actual` stub for non-Android targets returns
  `DomainError.NotSupported("Wi-Fi Direct not available on this platform")`. (est: 0.5h)

### Story 8.5c — MultipeerConnectivity bootstrap (iOS + macOS)

Apple's MultipeerConnectivity (MCSession) abstracts over Bluetooth, AWDL (Apple's
Wi-Fi Direct variant used by AirDrop), and infrastructure WiFi. It handles discovery and
connection automatically — no mDNS, no manual IP resolution needed. On Apple platforms
this replaces both mDNS discovery and the Wi-Fi Direct negotiation with a single API.

- [ ] Create `kmp/src/iosMain/.../sync/lan/MultipeerBootstrapTransport.kt` (`expect/actual`
  shell) with the iOS actual delegating to a Swift helper via Kotlin/Native `@ObjCClass`
  interop:
  - `MCNearbyServiceAdvertiser` — advertises `serviceType = "stelekit-sync"` with
    discovery info `{"graphId": "<uuid>"}`. Accepts incoming invitations automatically
    (the peerToken exchange happens inside the session, not in the invitation).
  - `MCNearbyServiceBrowser` — discovers peers advertising `stelekit-sync`. Emits
    `Flow<MCPeerID>` of nearby SteleKit instances.
  - On session connected: exchange pairing payload (token + port) as the first MCSession
    data message. Both sides then switch to `LanHttpTransport` over the MCSession's
    underlying IP address (obtainable via `MCSession` connected peer info + `getifaddrs`).
  - `capabilities.isBootstrapOnly = true`. Hands off to `LanHttpTransport` after pairing.
  (est: 8h — Swift interop layer ~3h, Kotlin wrapper ~2h, pairing handshake ~3h)
- [ ] Display nearby Apple devices in Settings → Sync → Pair Device under "Nearby Apple
  Devices" section. No QR code required for Apple↔Apple pairing. (est: 1h)
- [ ] `expect/actual` stub for non-iOS/macOS targets returns `DomainError.NotSupported`. (est: 0.5h)
- [ ] `macosMain` actual: same MCSession API is available on macOS — reuse the iOS actual
  via a `commonApple` source set (`iosMain` + `macosMain`). (est: 1h)

### Story 8.5d — WASM LAN fetch transport (browser → desktop, same network)

The WASM side needs its own `LanHttpTransport` that uses `fetch()` instead of Ktor
`HttpClient`. Discovery is via QR code (Story 8.4 — browser scans desktop's QR code to
get IP + port + token). This covers the common case: both devices on the same WiFi and
SteleKit WASM served over HTTP, or installed as a PWA (home screen install removes the
mixed-content restriction on many browsers).

- [ ] Add `Access-Control-Allow-Private-Network: true` and appropriate `Access-Control-Allow-Origin`
  CORS headers to all `LanSyncServer` (Story 8.2) route handlers. Required for Chrome 104+
  to permit browser→local-IP requests from any origin. (est: 0.5h)
- [ ] Create `kmp/src/wasmJsMain/.../sync/lan/WasmLanHttpTransport.kt` implementing
  `SyncTransport`. Uses `kotlinx.browser.window.fetch()` (already available in WASM stdlib)
  with `Authorization: Bearer <peerToken>` header:
  - `listRemote(sectionPrefixes)`: `fetch("http://$host:$port/.stele-manifest?prefixes=…")` →
    parse JSON `RemoteFileEntry` list.
  - `pullFiles(paths)`: parallel `fetch` calls (Promise.all via Kotlin coroutine bridge, max
    8 concurrent). Write results to OPFS via `PlatformFileSystem`.
  - `pushFiles(files, message)`: sequential `fetch` PUT for each dirty OPFS file.
  - `hasRemoteChanges(knownVersion)`: `fetch` manifest → compare version field.
  - `capabilities`: `supportsOfflineRead=true` (OPFS cache), `requiresServer=false,
    maxConcurrentPulls=8, isBootstrapOnly=false`.
  (est: 4h)
- [ ] Wire `WasmLanHttpTransport` into the WASM `SyncTransportFactory` actual. When a
  `LanPeer` endpoint is configured in `PlatformSettings` (stored after QR scan), use
  `WasmLanHttpTransport`; otherwise fall back to `GitHubRestTransport`. (est: 1h)
- [ ] Document the HTTPS limitation in a `ponytail:` comment on the transport: mixed content
  blocks `http://` fetch from `https://` pages. Workarounds in priority order: (1) install
  as PWA, (2) use Epic 9 relay (already HTTPS), (3) Story 8.5e WebRTC. (est: 0h — comment
  only)

### Story 8.5e — WebRTC data channels (WASM ↔ Desktop, HTTPS-safe)

WebRTC data channels bypass the HTTPS/mixed-content restriction entirely — `RTCPeerConnection`
uses DTLS cert pinning via SDP fingerprints, not the browser's cert trust store. This makes
it the correct path for a WASM app served from HTTPS that needs to connect to a desktop on
the local network.

WASM side: native `RTCPeerConnection` (no library, built into every modern browser).
Desktop side (JVM): DTLS via Bouncy Castle (JVM built-in) + SCTP for data channel framing.
SCTP is the hard part — use `jitsi-sctp` (`org.jitsi:jitsi-sctp:1.0`) which wraps the
battle-tested `usrsctp` C library via JNI. This is the one JNI dependency in the otherwise
pure-Kotlin transport stack. If `jitsi-sctp` becomes a maintenance liability, the interface
allows swapping to a pure-Kotlin SCTP implementation without touching callers.

Signaling (SDP exchange) is done via QR code for LAN discovery: desktop generates an SDP
offer as a QR code, browser scans it and sends back the SDP answer (displayed as a second
QR or pasted as text). For subsequent connections the SDP can be stored in `PlatformSettings`
and the ICE host candidates re-negotiated automatically (ICE restart). For cross-network
connections the relay WebSocket from Epic 9 Story 9.3 carries the SDP instead of QR codes.

- [ ] Add `org.jitsi:jitsi-sctp:1.0` to `jvmMain.dependencies` in `kmp/build.gradle.kts`.
  Confirm `bazel build //kmp:desktop_app` passes (JNI libs are bundled in the JAR). (est: 1h)
- [ ] Create `kmp/src/jvmMain/.../sync/webrtc/DesktopWebRtcTransport.kt`. Implements
  `SyncTransport`. On `connect()`:
  - Generate X25519 DTLS keypair (Bouncy Castle). Compute certificate fingerprint (SHA-256).
  - Build SDP offer: ICE ufrag/pwd (random), host candidate (local IP + port from JmDNS
    listener), DTLS fingerprint, `m=application` data channel line.
  - Display SDP offer as QR code in Settings → Sync → Pair Device (reuse `qrose`).
  - Wait for browser's SDP answer (scanned QR or relay). Parse ICE candidates + remote
    fingerprint. Perform DTLS handshake over UDP. Open SCTP association via `jitsi-sctp`.
  Sync protocol on top of the SCTP data channel: same framing as `LanHttpTransport` —
  JSON manifest exchange, then chunked binary file transfer. Reuse `LanSyncServer` request
  handlers by wrapping the SCTP data channel in a `ByteReadChannel`/`ByteWriteChannel` pair.
  `capabilities`: `supportsOfflineRead=false, requiresServer=false, maxConcurrentPulls=4,
  isBootstrapOnly=false`. (est: 12h)
- [ ] Create `kmp/src/wasmJsMain/.../sync/webrtc/WasmWebRtcTransport.kt`. On `connect()`:
  - Receive SDP offer (from QR scan or relay).
  - Create `RTCPeerConnection` via `external fun` JS interop. Set remote description.
  - Generate SDP answer. Display as QR or send via relay.
  - On `ondatachannel`: wrap the `RTCDataChannel` in coroutine-friendly send/receive
    wrappers. Run the same manifest+file-transfer framing as `WasmLanHttpTransport`.
  `capabilities`: same as `DesktopWebRtcTransport`. (est: 8h)
- [ ] Unit test `DesktopWebRtcTransport` SDP generation: offer contains host candidate,
  DTLS fingerprint, correct `m=application` line. (est: 2h)
- [ ] Integration test (JVM only, loopback): two `DesktopWebRtcTransport` instances, one
  as offerer one as answerer, DTLS handshake on loopback UDP, SCTP data channel opens,
  manifest JSON round-trips. (est: 4h)

### Story 8.6 — LAN transport tests

- [ ] Integration test `LanSyncServerTest`: start server on loopback, `GET /.stele-manifest`
  with valid token returns file list; with no token returns 401; `GET /files/{path}` returns
  bytes; `PUT /files/{path}` writes to in-memory `FileSystem`. (est: 3h)
- [ ] Unit test `LanHttpTransport`: mock server via `MockEngine` (Ktor test util);
  `listRemote` parses manifest JSON; `pullFiles` parallelizes up to 8. (est: 2h)
- [ ] Unit test `MdnsDiscovery`: mock JmDNS event; flow emits `LanPeer` with correct
  host/port; self-discovery (same `graphId`) is filtered out. (est: 2h)
- [ ] Unit test `WifiDirectBootstrapTransport`: mock `WifiP2pManager` callbacks; peer
  discovery emits `Flow<WifiP2pDevice>`; connection yields correct `LanPeer` with group
  owner IP `192.168.49.1`. (est: 2h)

---

## Epic 9 — Custom P2P sync protocol (WAN + relay)

**Gate**: Epic 8 complete. Only needed for syncing across NAT (different networks — home to
coffee shop, etc.). LAN-HTTP (Epic 8) covers same-network cases without any of this.
**Goal**: Two devices with no shared network sync directly or via a lightweight relay — no
GitHub account required. Pure Kotlin/KMP: no Rust, no FFI, no binary blobs.

### Story 9.1 — NOISE_XX handshake (encrypted authenticated channels)

- [ ] Add `dev.whyoleg.cryptography:cryptography-core:0.3.1` to `commonMain.dependencies`
  (KMP-compatible crypto; JVM backend uses JCA, WASM uses WebCrypto). (est: 0.5h)
- [ ] Create `kmp/src/commonMain/.../sync/noise/NoiseHandshake.kt`. Implement
  `NOISE_XX` pattern (mutual authentication, both parties present static key):
  - Key generation: `X25519` keypair via `cryptography-core`.
  - Handshake messages: `e`, `e, ee, s, es`, `s, se` — three round trips over any
    `ByteChannel`.
  - Output: `CipherState` pair (send/receive AES-256-GCM) for the session.
  Static key pairs are generated once per device and stored in `PlatformSettings`.
  (est: 8h)
- [ ] Unit test `NoiseHandshake` on loopback: two coroutines, `ByteChannel` pair, complete
  handshake, exchange encrypted messages, decrypt correctly. Test that a tampered message
  fails decryption. (est: 3h)

### Story 9.2 — STUN client (NAT address discovery)

- [ ] Create `kmp/src/commonMain/.../sync/stun/StunClient.kt`. Minimal STUN binding request
  (RFC 5389): send `Binding Request` to a STUN server (default: `stun.l.google.com:19302`
  or a self-hosted alternative); parse `Binding Response` to extract `XOR-MAPPED-ADDRESS`
  (public IP + port). Uses Ktor `UdpSocket` (Ktor 3 multiplatform sockets). (est: 4h)
- [ ] Unit test `StunClient` with a mock STUN server (loopback UDP). (est: 2h)

### Story 9.3 — Relay server

- [ ] Create a minimal Ktor relay server (separate Gradle/Bazel module:
  `relay/`). Exposes two endpoints:
  - `POST /relay/register` — device posts `{"peerId":"<pubkey-hex>","addr":"<stunAddr>"}`;
    server stores for 60s TTL. Returns `200`.
  - `GET /relay/peer/{peerId}` — returns the registered peer's STUN address if present,
    or `404`. Used for signaling (peer A learns peer B's public IP before attempting
    direct connection).
  - `CONNECT /relay/tunnel/{peerId}` — WebSocket tunnel fallback: relay forwards bytes
    between two connected peers when direct UDP hole-punch fails.
  No auth on register/lookup (peerId is a public key — spoofing it gains nothing without
  the corresponding private key). TLS terminated by a reverse proxy. (est: 6h)
- [ ] Dockerize relay (`relay/Dockerfile`). Intended to run on Fly.io free tier or any VPS.
  One relay can serve multiple unrelated SteleKit users (peerIds are globally unique public
  keys). (est: 2h)

### Story 9.4 — UDP hole-punching + direct connection

- [ ] Create `kmp/src/commonMain/.../sync/p2p/HolePuncher.kt`. Algorithm:
  1. Both devices register with relay server (`/relay/register`).
  2. Device A fetches device B's STUN address from relay (`/relay/peer/{peerId}`).
  3. Simultaneous UDP packets to each other's public address (hole-punch).
  4. If packets arrive within 5s: direct UDP channel established. Proceed to NOISE
     handshake (Story 9.1) over the direct channel.
  5. If no arrival in 5s: fall back to relay WebSocket tunnel.
  Implements `Either<DomainError, ByteChannel>`. (est: 8h)
- [ ] Integration test on loopback (both sides same machine, STUN skipped, direct UDP):
  hole-punch completes, NOISE handshake succeeds, 1KB message round-trips encrypted.
  (est: 3h)

### Story 9.5 — P2pTransport

- [ ] Create `kmp/src/commonMain/.../sync/p2p/P2pTransport.kt` implementing `SyncTransport`.
  Delegates to `HolePuncher` for connection establishment, then `LanHttpTransport` logic
  (same file-listing + push/pull protocol) over the encrypted `ByteChannel`.
  `capabilities`: `supportsOfflineRead=false, requiresServer=false, maxConcurrentPulls=4,
  isBootstrapOnly=false`. (est: 4h)

### Story 9.6 — P2P transport tests

- [ ] End-to-end test `P2pTransportTest`: two in-process `P2pTransport` instances (loopback
  UDP, no real STUN/relay), share public keys out-of-band, hole-punch, sync three files,
  verify content on both sides. (est: 4h)
- [ ] Test relay fallback: block direct UDP packets (mock `HolePuncher` to return timeout);
  confirm relay WebSocket tunnel is used and files transfer correctly. (est: 2h)

---

## Updated Dependency Graph

```
Epic 1 (Data model)
    |
    +────────────────────┬──────────────────┬────────────────────+
    v                    v                  v                    v
Epic 2 (Desktop)    Epic 3 (Journals)  Epic 4 (WASM)       Epic 5 (UI)
    |                    |                  |                    |
    +────────────────────+──────────────────+────────────────────+
                         v
                  Epic 6 (Tests — stories run after each parallel epic)
                         |
                  Epic 7 (SyncTransport interface — can also run parallel with Epics 2–5)
                         |
                  Epic 8 (LAN-HTTP transport — same network sync, no cloud)
                         |
                  Epic 9 (Custom P2P + relay — cross-network, no cloud)
```

Epics 7–9 are independent of the Graph Sections feature (Epics 1–6) and can ship as
separate PRs. Epic 7 is a rename-only refactor with zero behavior change — safe to merge
at any time after Epic 1. Epic 8 is self-contained; Epic 9 depends only on Epic 8's
`LanHttpTransport` sync protocol (reused over the encrypted P2P channel).

All three transport epics are pure Kotlin/KMP. If a specific component (NAT traversal,
NOISE crypto) proves fragile in the Kotlin implementation, it can be replaced by a Rust
crate exposed via UniFFI without touching the `SyncTransport` interface or any caller.
