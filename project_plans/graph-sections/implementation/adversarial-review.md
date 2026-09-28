# Adversarial Review — Graph Sections Implementation Plan

**Reviewer**: Adversarial review agent  
**Date**: 2026-06-29  
**Plan reviewed**: `project_plans/graph-sections/implementation/plan.md`  
**Checklist items evaluated**: 10  

---

## Verdict: BLOCKED

Three blockers must be resolved before implementation can begin. Two of them corrupt data silently; neither will be caught by the test suite until it is too late.

---

## Blockers (must fix before implementing)

### B1 — `name UNIQUE` constraint makes split journals impossible

**The plan has no task for this. FR-3 (split journals) is unimplementable as written.**

The `pages` table has `name TEXT NOT NULL UNIQUE COLLATE NOCASE`. Both `journals/2026-06-29.md` and `journals/acme-work/2026-06-29.md` parse to `title = "2026-06-29"` via `substringAfterLast("/").stripPageExtension()`. The second `INSERT OR IGNORE` is silently dropped.

This is not the "journal date resolver" bug the pitfalls document describes (UUID aliasing). That bug cannot even be reached — the second journal's row never makes it into the database.

The required schema work that the plan entirely omits:

1. The `name UNIQUE COLLATE NOCASE` constraint must be relaxed. A naive `UNIQUE(name, section_id)` does not work either because `NULL != NULL` in SQLite unique checks, so two global (`section_id IS NULL`) journals with the same date are both allowed, which is also wrong. A correct fix requires either a partial unique index or a sentinel non-NULL value for "global."

2. `selectPageByName` returns `LIMIT 1` — it becomes ambiguous once the name uniqueness constraint is relaxed. It needs a section discriminator or must be deprecated for journal lookups.

3. `loadDirectory` builds `pagesByJournalDate` as:
   ```kotlin
   .associateBy { it.journalDate!! }
   ```
   If two journal pages share a date (after constraint relaxation), only the last one survives the `associateBy`. This is a separate silent-corruption site that the plan never addresses.

4. `pages_fts` is indexed by name. Duplicate names produce duplicate FTS entries and confusing search results. The plan has no task for this.

Story 1.3 adds a composite index `idx_pages_journal_section` and a section-discriminated query `selectJournalPageByDateAndSection`, but these only affect reads. They do not prevent the second journal row from being silently discarded on insert. The constraint is never touched.

---

### B2 — Warm-start reconcile path not covered by Story 2.1

Story 2.1 says: "Replace the single `loadDirectory("$graphPath/pages", ...)` call…" There is no single call. `loadGraphProgressive()` has **two** `loadDirectory(pagesDir, ...)` calls:

- Cold-start path (~line 641): `launch { loadDirectory(pagesDir, onProgress, ParseMode.METADATA_ONLY) }`
- Warm-start `backgroundIndexJob` reconcile (~line 585): `launch { loadDirectory(pagesDir, onProgress, ParseMode.METADATA_ONLY) }`

Story 2.1 lists replacement steps only for the cold-start path (it talks about what to do "after opening the graph path"). The warm-start reconcile path is not mentioned anywhere in the plan. On a warm restart (the common case after the first session), `loadGraphProgressive` takes the warm path immediately and calls the old unfiltered `loadDirectory(pagesDir, ...)`. Section subdirectories are flat-invisible from root `pages/`, so **no section pages load on warm restart**. This is a silent regression that would surface only after an app restart during testing.

Additionally, `loadGraph()` (non-progressive) has its own `loadDirectory(pagesDir, ...)` call. The plan only mentions `loadGraphProgressive`. If `loadGraph` is still reachable in production (it is called from tests and is a public method on `GraphLoaderPort`), it needs the same treatment.

---

### B3 — `insertPage` and `updatePage` SQL not tasked for update

Story 1.3 adds `section_id TEXT` to `CREATE TABLE pages` in `SteleDatabase.sq`. SQLDelight regenerates from the DDL, but the `insertPage` and `updatePage` queries use **explicit column lists**:

```sql
insertPage:
INSERT OR IGNORE INTO pages (uuid, name, namespace, file_path, created_at, updated_at,
    properties, version, is_favorite, is_journal, journal_date, is_content_loaded)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

updatePage:
UPDATE pages SET namespace = ?, file_path = ?, updated_at = ?, properties = ?, version = ?,
    is_favorite = ?, is_journal = ?, journal_date = ?, is_content_loaded = ?
WHERE uuid = ?;
```

SQLDelight does not automatically add `section_id` to these. Both queries need manual updates in `SteleDatabase.sq`, and the corresponding Kotlin call sites need the new parameter. Story 1.4 says "update all `insertPage` / `updatePage` call sites in the same file" but does not explicitly task updating the SQL statements themselves. If the SQL is not updated, every page save — including after section assignment — silently writes `section_id = NULL` to the DB regardless of what `Page.sectionId` contains. The section filter becomes permanently inoperative.

The plan's Story 1.3 does include "Run `./gradlew generateCommonMainSteleDatabase` and confirm generated Kotlin compiles" — this will surface compile errors at call sites if the generated function signatures change. But if an implementer only runs the rsync after generation without inspecting the diff, the queries will silently remain wrong.

**Explicit tasks needed**: "Update `insertPage` SQL to include `section_id`" and "Update `updatePage` SQL to include `section_id`."

---

## Concerns (should fix, not blocking)

### C1 — Cross-section ghost link detection is unimplementable as described

Story 5.5: "when a `[[Page Name]]` target has no DB row, check the `.stele-sections` manifest: if the target's path would fall under a known section prefix not in the subscription, render the link as grayed-out."

The problem: a page with no DB row has no known file path. The manifest maps section IDs to path prefixes (`pages/acme-work/`), not to page names. There is no way to reverse-map `[[My Work Note]]` to `pages/acme-work/My Work Note.md` without either (a) having a DB row for it, or (b) scanning the filesystem for all section directories — which defeats the point of not subscribing.

The story can only work if unsubscribed section stubs are kept in the DB (contradicting the rate-limit-bomb fix which says never create stubs for non-subscribed sections). The implementation will need a fundamentally different approach: either a name-to-section index derived from the manifest, or accepting that ghost links are not attributable.

### C2 — No backward compatibility regression test (FR-11)

FR-11 ("Graphs without `.stele-sections` load and behave identically to today") is the most important correctness guarantee and the one most likely to be broken accidentally by touching `loadGraphProgressive`. The test suite has zero coverage for it. Story 6.2's integration test only tests the positive filtering case. A regression here would ship silently.

**Needed**: An integration test that loads a graph without `.stele-sections`, confirms `sectionFilter == null`, and asserts that total page count and loaded content are identical to the pre-section baseline.

### C3 — Sparse-checkout subscription wiring has no owner

Story 2.5 adds `GitSyncService.addSparseCheckoutCone(sectionPagePath, sectionJournalPath)`. Story 5.4 (Settings subscription toggle) says on toggle-on: "call `GraphLoader.addSectionSubscription(section)`." Story 2.3 implements `GraphLoader.addSectionSubscription()`.

No task establishes the call from `addSectionSubscription()` → `GitSyncService.addSparseCheckoutCone()`. `GraphLoader` does not currently depend on `GitSyncService`. Who injects `GitSyncService` into the subscription path? `StelekitViewModel`? `GraphManager`? This coordination is unassigned. If it is left out, subscription changes load pages in memory but do not update git sparse-checkout on disk, so the next `git pull` re-fetches files outside the subscribed cone.

### C4 — No UI epic for first-time section clone (FR-6)

FR-6 ("first-time clone of a section slice in < 30s") is a stated goal and a key user-facing scenario. Story 2.5 adds `GitSyncService.cloneSectionSlice()` but no UI story triggers it. There is no onboarding screen, no "Set up new device" flow, no success/failure feedback. The method exists but has no caller in any epic.

### C5 — WASM write path absent with no stub or error

When a user edits a page on WASM, what happens? `PlatformFileSystem.writeFile()` on WASM is currently a stub (per BUG-005 Phase 1). Story 4.3 only implements `readFile()`. There is no task for:
- Making the edit path show a read-only indicator on WASM until OQ-5 resolves
- Stubbing `writeFile()` to return a clear error
- Documenting what happens to in-memory edits that cannot be persisted

Without explicit handling, users on WASM can edit content that silently vanishes on refresh.

### C6 — Story 4.2 WasmSectionSyncService estimated at 4h — too low

`WasmSectionSyncService` requires: GitHub REST client setup (authentication headers, base URL config from `PlatformSettings`), GitHub Trees API response parsing (recursive tree with path/sha/type fields), subscribed-prefix filtering over potentially thousands of entries, stub row insertion per filtered entry with `sectionId` assignment, and error handling. This is a non-trivial integration that touches 4+ new files on a platform with no existing GitHub REST client. 8–12h is more realistic.

### C7 — `sanitizeDirectory` not section-aware

`sanitizeDirectory(pagesDir)` uses `fileSystem.listFiles(path)` — the same flat listing that is the subject of the non-recursive blocker fix. It will not sanitize filenames inside `pages/acme-work/`. Section page files with URL-encoded or OS-illegal characters will fail to load correctly. Story 2.1 does not include a step to call `sanitizeDirectory(section.pagePathPrefix)` for each subscribed section alongside the root scan.

### C8 — `loadDirectory` journal chunk lookup not section-aware (cascades from B1)

Even after B1 is fixed (relaxing the `name UNIQUE` constraint so two journal rows with the same date can coexist), the `loadDirectory` method's per-chunk journal lookup:
```kotlin
pageRepository.getJournalPagesByDates(dates)
    .associateBy { it.journalDate!! }
```
uses `associateBy` which keeps only the last match per date. A chunk containing both `2026-06-29.md` (global) and `journals/acme-work/2026-06-29.md` would have only one survive lookup. The plan has Story 1.5 adding `getJournalPageByDateAndSection` but `loadDirectory`'s own journal-lookup path has no corresponding update. This is a dependency on B1 that needs its own task.

---

## Checklist Results

| # | Check | Result |
|---|-------|--------|
| 1 | Flat `listFiles()` blocker addressed | Present (Story 2.1) — but warm-start reconcile path not covered (B2) |
| 2 | MigrationRunner rule called out for `section_id` | Present (Story 1.3 explicit) |
| 3 | `indexRemainingPages` rate-limit bomb filtered on WASM | Present (Story 4.2 + `getUnloadedPagesBySection` query in Story 1.3) |
| 4 | Journal date uniqueness via `(journal_date, section_id)` constraint | Partial — query added, composite index added, `name UNIQUE` blocker unaddressed (B1) |
| 5 | Backward compat test (no `.stele-sections` = identical behavior) | Absent (C2) |
| 6 | Sparse-checkout wiring from subscription UI to `git sparse-checkout` | Partial — methods exist but wiring has no owner (C3) |
| 7 | WASM write-back credential path stubbed or blocked | Deferred via OQ-5 (documented) — but no error path for in-memory edits (C5) |
| 8 | Cross-section backlinks handled | Mechanically unimplementable as described (C1) |
| 9 | Estimate sanity | Story 4.2 (`WasmSectionSyncService`, 4h) and Story 4.3 (WASM lazy fetch, 4h) both 2-3x too low (C6) |
| 10 | All requirements have corresponding epic/story | FR-6 onboarding UX absent (C4); `sanitizeDirectory` gap (C7); WASM write path absent (C5) |

---

## Priority Order for Resolution

1. **B1** — Schema redesign for `name UNIQUE` relaxation (blocks FR-3 entirely; architectural decision required before Story 1.3)
2. **B2** — Add warm-start reconcile path to Story 2.1 (one-line addition to Epic 2 task list, high risk if missed)
3. **B3** — Explicitly task `insertPage` / `updatePage` SQL updates in Story 1.3
4. **C3** — Assign owner for sparse-checkout wiring
5. **C2** — Add FR-11 backward compat integration test to Story 6.2
6. **C1** — Redesign or descope Story 5.5 ghost link mechanism
