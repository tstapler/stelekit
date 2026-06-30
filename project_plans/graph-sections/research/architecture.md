# Graph Sections — Architecture Research

**Date**: 2026-06-29
**Scope**: Lazy content fetch, section-filtered sync, journal split, cross-section backlinks

---

## Research Task 1: Section filter in GraphLoader — earliest path-prefix filter point

### Current flow

`loadGraphProgressive()` calls:
- `loadJournalsImmediate("$graphPath/journals", ...)` — reads files immediately
- `loadDirectory("$graphPath/pages", ...)` / `loadDirectory("$graphPath/journals", ...)` — background

Inside `loadDirectory()`, the sequence is:
1. `fileRegistry.scanDirectory(path)` — lists files, builds `FileRegistry` entries (no content IO)
2. `fileRegistry.pageFiles(path)` / `fileRegistry.recentJournals(path, n)` — filters cached scan results
3. Chunk iteration: `val filePath = entry.filePath` then `readFileDecrypted(filePath)` (first content IO)

### Earliest filter point (no content IO)

**Option A — call-site path narrowing (preferred for section directories):**

For sections whose pages live under a dedicated prefix (`pages/acme-work/`), the cleanest filter is at the `loadGraphProgressive` call site. Instead of always calling `loadDirectory("$graphPath/pages", ...)`, call `loadDirectory("$graphPath/pages/acme-work", ...)` for each subscribed section, plus `loadDirectory("$graphPath/pages", ...)` scoped to only files that do NOT start with any known section prefix.

This requires zero changes inside `loadDirectory()` and zero content reads for out-of-scope files. `fileSystem.listFiles(path)` is still called, but only on the subscribed directories, so even file enumeration is bounded.

**Option B — in-loop path predicate (for global page filtering):**

For global pages (no section prefix in path), a predicate is needed inside `loadDirectory()` after `val filePath = entry.filePath` but before `readFileDecrypted(filePath)`. The predicate checks: "does this file's path start with ANY section prefix not in my subscription?" If yes, `return@count false`. This is pure string matching, zero IO.

Both options can be combined: Option A handles section directories; Option B handles the global `pages/` directory where global pages (no prefix) and section pages (with prefix) coexist.

### Concrete injection point

```kotlin
// In loadDirectory() inner loop, before readFileDecrypted:
val fileName = entry.fileName
val filePath = entry.filePath
if (sectionFilter != null && sectionFilter.shouldExclude(filePath)) return@count false
fileSystem.invalidateShadow(filePath)
val content = readFileDecrypted(filePath) ?: return@count false
```

`sectionFilter: SectionFilter?` injected into `GraphLoader` constructor — null when no `.stele-sections` file exists (FR-11 backward compat).

---

## Research Task 2: Lazy content architecture

### Current two-stage mechanism already exists

The codebase has a complete stub/loaded state machine built around `ParseMode`:

| Stage | ParseMode | `isContentLoaded` | When |
|---|---|---|---|
| Startup — pages | `METADATA_ONLY` | `false` | `loadGraphProgressive` Phase 2 |
| Startup — recent journals | `FULL` | `true` | `loadJournalsImmediate` Phase 1 |
| Background | `FULL` | `true` | `indexRemainingPages()` (Phase 3 drain) |
| Navigation | `FULL` | `true` | `loadFullPage(pageUuid, force)` |

`navigateTo()` → `loadFullPage()` is **already the lazy-fetch trigger**. `loadFullPage()` checks `isContentLoaded && allBlocksLoaded` and returns immediately if already loaded, otherwise reads the file and calls `parseAndSavePage(FULL)`.

### State transitions needed for section-scoped lazy fetch

For the new feature, Stage 1 (startup) needs a **new index-only mode** that writes stub page rows without reading file content at all — just deriving metadata from the file path:

| Stage | ParseMode (new) | `isContentLoaded` | How |
|---|---|---|---|
| Index load | `INDEX_ONLY` | `false` | Write stub Page row from file path alone (name from filename, filePath from path, isJournal from path prefix, journalDate from filename) |
| Navigation | `FULL` | `true` | `loadFullPage()` — no change needed |

The `INDEX_ONLY` mode would not call `readFileDecrypted()` at all. It inserts a minimal stub: `Page(uuid=generated, name=fromFilename, filePath=path, isJournal, journalDate, isContentLoaded=false)`. This satisfies FR-5 "< 2s for 8000 pages" because there is no file content IO — only a directory listing + INSERTs.

For Desktop (JVM/Android), `indexRemainingPages()` already handles the drain from `isContentLoaded=false` → full load. No architectural change needed there.

For WASM (ADR-013 path), `loadFullPage()` needs a WASM-specific implementation that calls `WasmSectionSyncService.fetchPageContent(filePath)` instead of `fileSystem.readFile(filePath)`. The `FileSystem` abstraction already has platform-specific `actual` implementations — a WASM actual that fetches from the GitHub REST API and writes to OPFS is the right extension point.

### Key insight: `loadFullPage()` is already the correct hook

No new "on-demand fetch" trigger is needed. The existing `navigateTo()` → `loadFullPage()` → `parseAndSavePage(FULL)` path is the lazy fetch. Adding `INDEX_ONLY` as a new startup mode completes the two-stage design.

---

## Research Task 3: Page index for lazy fetch — is `is_loaded = false` sufficient?

### Existing schema

```sql
CREATE TABLE pages (
    uuid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
    namespace TEXT,
    file_path TEXT,
    ...
    is_content_loaded INTEGER NOT NULL DEFAULT 1,
    backlink_count INTEGER NOT NULL DEFAULT 0
);
```

`is_content_loaded` (`Page.isContentLoaded`) already represents stub state when `false`. The background drain in `indexRemainingPages()` and the `getUnloadedPages(limit, offset)` query are already wired to this flag.

### Is `is_content_loaded = false` sufficient for index-only load?

**Yes, with one addition**: for the index-only startup (FR-5), the stub row must include:
- `name` — derivable from filename without reading content (e.g. `TypeSystems` from `pages/acme-work/TypeSystems.md`)
- `file_path` — the absolute path, stored as-is
- `is_journal` — derivable from path prefix (`/journals/` anywhere in path)
- `journal_date` — derivable from filename if journal (e.g. `2026-06-29` from `2026-06-29.md`)
- `is_content_loaded = false`

**NOT needed at stub time** (all come from file content):
- `properties` (includes `stele-section::` frontmatter)
- `backlink_count`
- block records

### Section assignment at index time

Since `stele-section::` is in the file content (frontmatter), it cannot be known at index-only time without reading the file. Two options:

**Option A — path-based section inference:** If `pagePathPrefix = "pages/acme-work"`, any file under that prefix belongs to `acme-work`. No frontmatter parse needed. Section assignment is reconstructed from `file_path` at read time. The DB does not need a `section_id` column for files whose path already encodes the section.

**Option B — add `section_id TEXT` to pages table:** Populated at first full content load, persisted for subsequent lookups. This requires a schema migration but enables fast "get all pages for section X" queries without path-string scanning.

**Recommendation:** Option A for v1 (no schema change, no content IO), Option B as an optimization migration once the feature is stable.

### What metadata is needed for the index-only load?

For the "browsable namespace" model (FR-5, analogous to CitC/Piper), the index needs to provide:
1. Page title (from filename) — for search, sidebar, backlink display
2. `file_path` — for `loadFullPage()` to find the file on demand
3. `is_journal` + `journal_date` — for journal calendar view
4. `isContentLoaded = false` — to signal "stub, fetch on demand"

All four are derivable from path alone. No file reads needed for the index.

---

## Research Task 4: Journal split architecture

### Current journal detection

`parsePageWithoutSaving()` in `GraphLoader`:
```kotlin
val journalDate = if (filePath.contains("/journals/")) JournalUtils.parseJournalDate(title) else null
val isJournal = journalDate != null
```

`JournalUtils.parseJournalDate(title)` parses the date from the **filename** (e.g. `2026-06-29.md` → `LocalDate(2026, 6, 29)`), not from the directory path.

### How `journals/acme-work/2026-06-29.md` routes

`filePath.contains("/journals/")` → `true` (still contains `/journals/`) ✓
`JournalUtils.parseJournalDate("2026-06-29")` → `LocalDate(2026, 6, 29)` ✓
`isJournal = true` ✓

**No change needed to the journal detection logic.** The date-based detection already works for nested paths.

### Section routing for section-scoped journals

`loadJournalsImmediate()` and `loadRemainingJournals()` both accept a `journalsDir: String` argument. Currently `loadGraphProgressive()` passes `"$graphPath/journals"`.

For section-scoped loading, the call site changes to pass section-specific paths:
```kotlin
// Global journals
loadJournalsImmediate("$graphPath/journals", count, ...)  // only top-level .md files
// Section-scoped journals (one call per subscribed section)
loadJournalsImmediate("$graphPath/journals/acme-work", count, ...)
```

`fileRegistry.recentJournals(journalsDir, n)` returns files sorted by date descending. Calling it on `journals/acme-work/` returns only Acme work journals. Global journals remain at `journals/` (top-level only; subdirectories are section-specific).

### File-naming convention

Requirements already specify (FR-3, FR-7):
- Global journal: `journals/2026-06-29.md`
- Section journal: `journals/<section-id>/2026-06-29.md`

`<section-id>` is the stable slug (e.g. `acme-work`), matching the `journalPathPrefix` field in `.stele-sections`.

### Disambiguation for journal lookups

Currently `getJournalPageByDate(date)` returns a single `Page?`. With split journals, the same date can have multiple pages (global + section-specific). The `PageRepository` query:
```sql
SELECT * FROM pages WHERE is_journal = 1 AND journal_date = ? LIMIT 1;
```
...needs to either:
- Return all pages for a date (breaking change to the interface), or
- Be replaced by `getJournalPageByDateAndSection(date, sectionId)`.

The safest v1 approach: keep `getJournalPageByDate()` returning the global journal page, add `getJournalPageByDateAndSection(date, sectionId?)` for the split case. The journal calendar view queries both.

---

## Research Task 5: Cross-section backlinks

### Scenario

Personal page `Reflections.md` contains `[[Acme Project Kickoff]]`. Device subscribes only to `personal`. `Acme Project Kickoff` belongs to section `acme-work` and is not on this device.

### What the DB has

If the device never subscribed to `acme-work`, there is no stub row for `Acme Project Kickoff` in the `pages` table. The backlink resolver (`BlockRepository` query for `[[Acme Project Kickoff]]`) returns nothing.

### Options

**Option A — Unresolved reference (ghost link):** The `[[Acme Project Kickoff]]` wikilink renders as a grayed-out non-navigable link (similar to how Logseq shows links to pages that don't exist). Clicking it shows a tooltip: "This page belongs to a section not synced to this device." No stub row needed.

This requires the wikilink renderer to distinguish:
- "page exists but content not loaded" (stub row, `isContentLoaded=false`)
- "page does not exist on this device" (no row at all)

**Option B — Phantom stub rows:** During section subscription setup, enumerate backlinks from subscribed-section pages that point to non-subscribed pages and insert minimal stubs (name only, no file_path, `isContentLoaded=false`, `section_id=acme-work`). Navigation to these stubs shows "Content in section 'Work – Acme Corp' — not synced to this device."

**Option C — No special handling:** Unresolved wikilinks already render as "new page" links in the current codebase. Document this behavior; tell the user to subscribe to both sections if they want cross-section links to resolve.

### Recommendation

**Option A** for v1. It requires:
1. No DB change — ghost links come from wikilink parsing, not DB lookup.
2. The wikilink renderer already handles "page not found" — just add a section-context tooltip when the link target matches a known section prefix from `.stele-sections`.
3. `SectionFilter` (loaded from `.stele-sections`) can be consulted at link-render time: "is this link target under a section path prefix I don't subscribe to?"

Option B (phantom stubs) is the right long-term design for offline access to titles without full content, but it introduces complexity in maintaining the phantom rows across subscription changes. Defer to v2.

---

## Summary Table — Key Decisions

| Question | Answer |
|---|---|
| Earliest filter point | Before `readFileDecrypted()` in `loadDirectory()` loop; or at call site by passing section-specific directories |
| Lazy fetch trigger | `navigateTo()` → `loadFullPage()` — already exists; add `INDEX_ONLY` ParseMode for startup |
| `is_content_loaded = false` sufficient? | Yes; name + filePath + isJournal + journalDate all derivable from path |
| Section at index time | Infer from `file_path` prefix matching `pagePathPrefix` in `.stele-sections` (no DB column needed v1) |
| Journal split detection | Works automatically — `filePath.contains("/journals/")` true for nested paths |
| Journal routing | Pass `$graphPath/journals/<section-id>` as `journalsDir` to `loadJournalsImmediate` |
| Cross-section backlinks | Render as ghost (grayed, non-navigable) links; consult `.stele-sections` for section attribution tooltip |

---

## Open Issues Surfaced by Research

| # | Issue | Impact |
|---|---|---|
| OQ-3a | `getJournalPageByDate()` returns one page; split journals produce multiple pages per date | FR-3, repository interface change |
| OQ-6 | `sanitizeDirectory()` currently only scans files directly in the given dir, not recursively — section subdirs in `journals/` are not sanitized | NFR-3, correctness |
| OQ-7 | `loadJournalsImmediate` uses `fileRegistry.recentJournals(journalsDir, count)` — does FileRegistry handle subdirectory paths? Needs verification | FR-3, Phase 1 latency |
| OQ-8 | `fileRegistry.scanDirectory` is not recursive; section journal directories (`journals/acme-work/`) must be explicitly passed for scan | Implementation detail |
