# Requirements: Android Save & Parse Performance

**Status**: Draft | **Phase**: 2 — Research complete, root cause confirmed by live logs
**Created**: 2026-04-23 | **Updated**: 2026-04-30

## Problem Statement

On Android, the app is unusably slow for interactive editing on a real (1000+ page, flagship device) graph. Creating bullet points, reordering blocks, and inserting page links from search are noticeably laggy. The root causes are now confirmed by live session logs:

1. **SAF file I/O is the dominant bottleneck.** All file reads/writes go through `saf://content://com.android.externalstorage.documents/...` (Storage Access Framework). Every `parseAndSavePage` call in Phase 3 background indexing reads a markdown file via SAF Binder IPC, which is orders of magnitude slower than direct filesystem access.
2. **Phase 3 background indexing starves interactive editing.** With 1000+ pages, Phase 3 blocks the `DatabaseWriteActor` for tens of seconds, causing HIGH-priority user saves to queue behind it. Observed: `parseAndSavePage took 48,497ms` (48 seconds) and `parseAndSavePage took 4,377ms` for individual pages.
3. **Race condition: Phase 3 replaces blocks for pages being edited.** Observed: `applyContentChange: block not found — content update dropped`. Phase 3 indexes a page (replacing its blocks in the DB with new UUIDs) while `BlockStateManager` holds stale UUIDs from before the re-index.

Users: Tyler Stapler on a flagship device with a 1000+ page personal wiki stored via SAF (external storage picker).

## Confirmed Evidence (2026-04-30 session logs)

```
Performance   06:23:17  Slow operation detected: parseAndSavePage took 4377ms
Performance   06:23:12  Slow operation detected: parseAndSavePage took 48497ms
BlockStateManager 06:23:00  applyContentChange: block not found — content update dropped
GraphWriter   06:22:52  Saved page: 2026_04_29
GraphWriter   06:22:52  Saved page to: saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki%2Flogseq/journals/2026_04_29.md
```

The `saf://` URI confirms SAF as the storage backend for all file operations.

## Current Benchmark Baseline

### JVM (CI, latest — 230 pages, xlarge config)

| Metric | Value |
|--------|-------|
| Phase 1 TTI | 9–11 ms |
| Phase 2 metadata | 3–6 ms |
| Phase 3 full indexing | 13–18 ms |
| Write p95 | 1–2 ms |

### Android (CI, latest — **25 pages only**)

| Metric | Value |
|--------|-------|
| Phase 1 | 67–79 ms |
| Phase 3 | 79–94 ms |
| Write p95 baseline | 2–4 ms |
| Jank factor | 0.5–1.33 |

**Critical gap**: The Android CI benchmark uses 25 pages; the user's real graph has 1000+. At scale, Phase 3 takes 48+ seconds per page via SAF. The benchmark masks the real-world severity.

## Success Criteria

- `parseAndSavePage` completes in < 500ms per page on a flagship device with a 1000+ page SAF-backed graph
- Phase 3 background indexing of 1000 pages does not block user edits by > 50ms
- `applyContentChange: block not found` error eliminated — Phase 3 must not replace blocks for pages currently being edited
- Android CI benchmark updated to test with MEDIUM (500 pages) graph, measuring write latency during Phase 3 concurrent indexing
- No regression on JVM numbers

## Scope

### Must Have (MoSCoW)
- **Fix SAF I/O bottleneck**: eliminate per-page SAF Binder IPC overhead for Phase 3 bulk reads (shadow-copy to `filesDir` on first load, or lazy on-demand parse only when page is navigated to)
- **Fix Phase 3 / BlockStateManager race condition**: Phase 3 must not replace blocks for pages that have an active `BlockStateManager` session
- **Update Android CI benchmark**: use MEDIUM graph (500 pages), measure interactive write p95 during Phase 3 contention
- **PRAGMA tuning**: `wal_autocheckpoint=4000`, `temp_store=MEMORY`, `cache_size=-8000` in `DriverFactory.android.kt` (3 lines, zero risk)

### Should Have
- Remove read-before-write safety check on the autosave path in `savePageInternal` (confirmed: the save path also uses SAF, doubling file I/O)
- Lazy Phase 3: only index a page when it is first navigated to, not eagerly at startup

### Out of Scope
- iOS performance
- JVM performance (already excellent)
- Search `AhoCorasickMatcher` performance (separate concern, not confirmed as bottleneck)
- UI frame rendering jank

## Constraints

- **Storage backend**: SAF (`saf://` URI scheme). Cannot be changed without prompting user to re-pick the folder via system picker. Shadow-copy must maintain SAF as write target for conflict-free external access.
- **Tech stack**: KMP, `AndroidSqliteDriver` + `RequerySQLiteOpenHelperFactory`, WAL mode, `DatabaseWriteActor` serializes all DB writes
- **Android DB dispatcher**: `Dispatchers.IO`
- **Current PRAGMA settings**: `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=10000` — missing autocheckpoint tuning
- **Timeline**: active; no hard deadline

## Architecture — Key Fix Points

### Fix 1: SAF Shadow-Copy for Phase 3 reads
Phase 3 calls `parseAndSavePage` for every page. Each call reads the markdown file via SAF (Binder IPC, potentially 1–48 seconds per page). **Fix**: on graph open, bulk-copy all markdown files from SAF to `context.filesDir/graphs/<id>/` using a single SAF traversal. Phase 3 then reads from `filesDir` (direct filesystem, ~1ms per file). Writes still go to both SAF (source of truth) and `filesDir` (shadow). Shadow is invalidated and re-synced on any external file change detected by the existing `GraphLoader.externalFileChanges` flow.

### Fix 2: Phase 3 / BlockStateManager race condition
When Phase 3 calls `parseAndSavePage` for a page, it must check if that page has an active edit session in `BlockStateManager`. If yes, skip re-indexing that page (it will be re-indexed after the edit session closes and the debounced save fires). **Fix**: expose a `Set<pageUuid>` of actively-edited pages from `BlockStateManager`; `GraphLoader.indexRemainingPages` skips any page in that set.

### Fix 3: PRAGMA tuning (DriverFactory.android.kt)
Add 3 lines after existing PRAGMAs:
```kotlin
try { driver.execute(null, "PRAGMA temp_store=MEMORY;", 0) } catch (_: Exception) { }
try { driver.execute(null, "PRAGMA cache_size=-8000;", 0) } catch (_: Exception) { }
try { driver.execute(null, "PRAGMA wal_autocheckpoint=4000;", 0) } catch (_: Exception) { }
```

## Research Status

Research complete (2026-04-23 to 2026-04-30). See `research/synthesis.md`.

- [x] Stack — Microbenchmark (not Macrobenchmark) confirmed as right tool; `BenchmarkRule` on standard AVDs
- [x] Features — WAL tuning options, SAF shadow-copy pattern, read-before-write removal
- [x] Architecture — SAF now confirmed dominant bottleneck; actor contention secondary
- [x] Pitfalls — SAF Binder IPC latency, emulator vs. device variance, block UUID race condition

## Stakeholders
- Tyler Stapler (developer + user)
