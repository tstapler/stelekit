# Graph Sections — Pitfalls & Risk Research

**Date**: 2026-06-29
**Status**: Draft
**Scope**: Failure modes, performance traps, and migration risks for FR-1 through FR-11

---

## Critical: File Scanning Infrastructure Is Non-Recursive

**Severity**: Blocker — section pages cannot load at all without this fix.

`FileRegistry.scanDirectory(dirPath)` calls `fileSystem.listFilesWithModTimes(dirPath)`, which
delegates to `FileSystem.listFiles(path)` — a flat, one-level directory listing. As a
result:

- `loadDirectory("$graphPath/pages", …)` will never find `pages/acme-work/My Page.md`
- `fileWatcher.startWatching(graphPath)` only registers `pages/` and `journals/` — external
  writes to `pages/acme-work/` are invisible to the poll loop
- `sanitizeDirectory(pagesDir)` will not normalize filenames inside section subdirectories
- `resolvePageFilePath(pageName)` tries four hardcoded candidates:
  `pages/$name.md.stek`, `journals/$name.md.stek`, `pages/$name.md`, `journals/$name.md`.
  A file at `pages/acme-work/My Page.md` will never be found, causing `loadPageByName`
  and `loadFullPage` to return null for every section-scoped page.

**Required fixes** (must all land together):

1. `GraphLoader.loadGraph` / `loadGraphProgressive`: after reading `.stele-sections`, iterate
   subscribed `pagePathPrefix` and `journalPathPrefix` directories and call `loadDirectory` /
   `loadJournalsImmediate` for each in addition to the root `pages/` and `journals/` scans.
2. `resolvePageFilePath`: add a fallback that searches each subscribed section prefix, e.g.:
   ```kotlin
   sections.flatMap { s ->
       listOf("${graphPath}/${s.pagePathPrefix}/$pageName.md.stek",
              "${graphPath}/${s.pagePathPrefix}/$pageName.md")
   }
   ```
3. `fileWatcher.startWatching`: register section subdirectories explicitly, or make the watcher
   recursively track `graphPath/` (which has separate complexity trade-offs on Android SAF).

---

## Critical: Journal Date Collision in `journalDateResolver`

**Severity**: Data corruption — one journal silently overwrites the other in the DB.

`parseAndSavePage` classifies a file as a journal entry by checking
`filePath.contains("/journals/")`. It then extracts the date from the filename and calls
`journalDateResolver.getPageByJournalDate(date)` to find the existing DB row.

When `journals/2026-06-29.md` (global) and `journals/acme-work/2026-06-29.md` (section) both
exist, they share the same `LocalDate`. `getJournalPageByDate` returns a single page — the
first row that matches — so whichever journal is parsed second will be assigned the existing
page's UUID, overwriting the other journal's DB record. The two physical files will be
perpetually aliased to one DB row.

**Downstream effects**:
- `getJournalPages(limit, offset)` returns one entry for the date instead of two.
- The Journals view shows only the last-saved content for that date regardless of section filter.
- File watcher reloads for either file trigger saves under the wrong UUID.

**Required fix**: The `Page` model (and `journalDateResolver` interface) must carry a
`sectionId: String?` discriminator. Journal lookup should be keyed on `(journalDate, sectionId)`
not `(journalDate)` alone. The DB index on `journal_date` needs a composite index on
`(journal_date, section_id)` to remain efficient.

A simpler interim approach: check the `filePath` prefix against section directories to bypass
`journalDateResolver` for section-scoped journals — look up by `filePath` directly instead.

---

## High: `indexRemainingPages` Drains ALL Unloaded Stubs — WASM Rate-Limit Bomb

**Severity**: Functional failure on WASM — GitHub REST API rate limit exhausted.

`indexRemainingPages` queries `getUnloadedPages(INDEX_BATCH_SIZE, offset)` with no section
filter. If the startup index load creates `Page` stubs for all 8,000 pages (or for all
pages visible in the git tree) and only 300 are in the subscribed section, the background
drain loop will attempt `resolvePageFilePath` for all 7,700 non-subscribed pages.

On WASM, `resolvePageFilePath` returning null causes `indexRemainingPages` to skip the page
(stub stays unloaded → stays in the drain queue → infinite drain loop unless the `attempted`
set exclusion grows). But if WASM lazy-fetch is implemented as a fallback inside
`loadFullPage` / a `WasmSectionSyncService` triggered by stub resolution, each attempt could
fire a GitHub REST API request. GitHub authenticates 5,000 req/hr; 7,700 requests in one
session exhausts that limit in under an hour.

Even without a GitHub fallback, the drain loop termination relies on ALL non-subscribed pages
entering the `attempted` set. With 8,000 pages and `INDEX_BATCH_SIZE = 100`, that is 80
drain iterations (8,000 no-op offset advances), each doing a DB round-trip — slow but not
catastrophic on Desktop. On WASM with SQLite in OPFS this is fine, but it wastes cycles.

**Required fix**: Either:
- Filter stub creation at index time: only create `Page` stubs for subscribed section
  directories (i.e., do not populate stubs for pages from non-subscribed sections), **or**
- Add a `section_id IS NULL OR section_id IN (...)` predicate to `getUnloadedPages` so the
  drain loop only processes subscribed-section stubs.

The second option is safer because it handles subscription changes without requiring a full
re-index.

---

## High: Subscription Change at Runtime — No Watcher Re-registration Mechanism

**Severity**: Missing data on newly subscribed sections until app restart.

When the user adds a new section subscription in Settings (FR-4, phase 2+), three things
need to happen that have no current code path:

1. **Load the new section's files**: `loadDirectory` / `loadJournalsImmediate` must be called
   for the new `pagePathPrefix` / `journalPathPrefix`.
2. **Register with the file watcher**: `fileWatcher.startWatching(graphPath)` is idempotent
   (re-calling it is safe) but only watches the root directories it was given. New section
   subdirectories need explicit watcher registration.
3. **Cancel + restart `backgroundIndexJob`**: the in-flight drain loop will not automatically
   pick up newly subscribed pages unless it is restarted.

Currently, `cancelBackgroundWork()` cancels `backgroundIndexJob` but there is no
`addSectionSubscription()` API on `GraphLoader` that orchestrates the three steps. Without
it, adding a section subscription has no observable effect on a running graph.

**Required fix**: A `GraphLoader.addSectionSubscription(section: SectionDefinition)` method
that: (a) calls `loadDirectory(sectionPagePrefix, …)`, (b) registers the new prefix with
the watcher, (c) cancels and relaunches `backgroundIndexJob`.

---

## Medium: WASM Rate Limiting — Lazy Fetch Is Net Positive But Not Free

**Severity**: Operational concern — monitoring and backoff required.

FR-5 lazy fetch (page body on navigation) limits WASM GitHub REST API calls to pages the
user actually opens. For a 300-page section, the theoretical maximum is 300 requests
(one per page, if the user opens all of them and none are cached in OPFS). At one page
open per second, that is 300 requests in 5 minutes — well within the 5,000 req/hr budget.

**Risks**:

- **Cache miss storms**: If a user opens a page that contains many `[[backlink]]` references
  and the app pre-fetches those pages eagerly (e.g. for backlink panel display), a single
  navigation can trigger O(backlinks) requests. For a densely-linked "index" page this could
  be 50–100 requests in seconds.
- **Section switch**: Changing the subscribed section mid-session invalidates OPFS cache for
  the old section and triggers fresh fetches for the new one.
- **No rate-limit handling in current WASM stubs**: `PlatformFileSystem.kt` (WASM) currently
  contains stubs (per BUG-005 Phase 1). When the real GitHub REST API client is implemented
  (ADR-013), it must include exponential backoff on HTTP 429 and surfacing of rate-limit
  errors to the UI (NFR-2 says ≤ 3s; a rate-limit retry violates that SLA silently).

**Required addition**: WASM lazy-fetch client must: (a) check OPFS cache before each request,
(b) surface HTTP 429 as a `DomainError` with retry-after metadata, (c) avoid pre-fetching
backlink targets eagerly.

---

## Medium: Backlink Integrity During Page Migration (Moving Files to Section Prefix)

**Severity**: User-facing confusion — "page not found" after a move.

Backlinks use `[[Page Name]]` (title-based, not path-based), so renaming/moving a file does
not break link resolution as long as the page name stays the same. However:

1. **`resolvePageFilePath` misses the new location** (see Critical #1 above): after moving
   `pages/My Page.md` → `pages/acme-work/My Page.md`, `loadPageByName("My Page")` tries
   only the four hardcoded candidates and returns null until the section prefix is in the
   search list.
2. **UUID continuity**: The existing DB row for "My Page" has `filePath = pages/My Page.md`.
   After the move, the file watcher (if it watches `pages/`) will see a deletion event for
   the old path. Unless the reconcile run for `pages/acme-work/` recognises the same page
   name and reuses the old UUID, a new UUID is generated, orphaning any UUID-based sidecar
   references.
3. **Interim state**: Between the deletion event for the old path and the indexing of the new
   path, the page will transiently have zero blocks in the DB. Any UI still displaying it
   will show a blank page or "page not found".

**Required fix**: The section-move operation (triggered by the section badge picker in FR-9)
should be an atomic rename via `FileSystem.renameFile`, not a delete + create. After rename,
`GraphLoader.reloadFiles(listOf(newPath))` should be called directly rather than waiting for
the watcher poll cycle.

---

## Low: `IN`-Clause Scale for Path-Prefix Queries

**Severity**: Low — existing chunking prevents violations; attention needed for new queries.

Existing `getPagesByNames(chunkTitles)` and `getJournalPagesByDates(dates)` queries are
already chunked at ≤ 50–100 items, staying well below `SQLITE_MAX_VARIABLE_NUMBER = 999`.

If FR-8's path-prefix filtering is implemented as a SQL `LIKE` predicate
(`WHERE file_path LIKE 'pages/acme-work/%'`), there is no `IN`-list at all — no risk.

If a future query materialises section IDs as an `IN` list (e.g.,
`WHERE section_id IN ('acme-work', 'beta-consulting', …)`), this is bounded by the number
of sections (NFR-6 says ≤ 10) — far below the 999-item limit.

**No action required** for this specific constraint, but document the ≤ 999 rule in any new
query that accepts a collection parameter.

---

## Summary Matrix

| # | Pitfall | Severity | Phase Affected |
|---|---------|----------|----------------|
| 1 | File scanning non-recursive — section subdirs invisible | Blocker | Phase 2 |
| 2 | Journal date collision — same `journalDate` for two physical files | Blocker | Phase 1 |
| 3 | `indexRemainingPages` drains all stubs — WASM rate-limit bomb | High | Phase 3 |
| 4 | Subscription change at runtime — no watcher re-registration | High | Phase 2 |
| 5 | WASM lazy fetch — no backoff, potential pre-fetch storms | Medium | Phase 3 |
| 6 | Page migration — `resolvePageFilePath` + UUID continuity | Medium | Phase 2 |
| 7 | `IN`-clause size for path-prefix queries | Low | All |
