# Architecture Review: StelekitViewModel.kt — 2026-07-04

**Target**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (2,574 lines, 139 functions)
**Depth**: standard, class-targeted (per the top finding of [`architecture-audit-2026-07-04.md`](./architecture-audit-2026-07-04.md)'s complexity×churn hotspot analysis)
**Method**: two independent parallel reviews (SOLID/Clean Code lens; Clean Architecture/DDD lens), synthesized below. Where both reviews independently reached the same conclusion, that's called out — it's the strongest evidence in this report.

## Executive summary

**This is a genuine God ViewModel spanning ~18 distinct feature/dialog bounded contexts**, not a large-but-cohesive class. Both independent reviews reached this conclusion from different angles (SOLID review: disjoint field usage across responsibility clusters; DDD review: the author's own `// ===== X =====` comment headers already informally marking separate use-case regions) and, critically, **both independently identified disk-conflict resolution as the single strongest extraction candidate** — a rare degree of agreement between two reviews that never saw each other's output.

No P0 correctness/crash defects were found — this is a structural review, not a bug hunt. The findings below are prioritized by (fix cost × blast radius), not urgency.

## The ~18 bounded contexts currently living in one class

The author's own region comments are themselves evidence the class already knows it's doing too much:

| Region | Lines | Approx size |
|---|---|---|
| Undo/Redo | 218-237 | small |
| Git Sync | 239-370 | 14 funcs |
| LLM suggestion review (Epic 7) | 372-441 | 5 funcs, ~70 lines |
| Recent/regular/journal page lists | 443-628 | 6 funcs, ~185 lines |
| Graph load lifecycle | 630-819 | includes the 150-line `loadGraph()` |
| Page refresh / favorites / clear | 821-870 | small |
| Block-tree structural editing | 872-1086 | 13 funcs, ~215 lines |
| Screen navigation & history | 1088-1288 | 9 funcs, ~200 lines |
| Bulk page delete / page creation | 1290-1359 | 3 funcs, ~75 lines |
| **Auto-save + disk-conflict resolution** | **1408-1707** | **19 funcs, ~330 lines — see below** |
| UI dialog visibility toggles | 1717-1741 | trivial setters |
| Share Dialog + Export + Google Docs/Drive | 1742-1966 | 14 funcs, ~330 lines |
| Theme/language/debug/status/snackbar | 1968-2037 | 12 funcs, ~70 lines |
| Command system + `updateCommands()` | 2039-2185 | 8 funcs, ~150 lines |
| Search autocomplete | 2192-2216 | 1 func |
| Vault flush/lock | 2224-2239 | 1 func |
| Export (clipboard/blocks) | 2241-2326 | overlaps Share Dialog region |
| Rename page + backlink rewrite | 2328-2384 | 3 funcs, ~55 lines |
| Midnight journal watcher | 2386-2421 | 2 funcs, ~35 lines |
| Section Management | 2423-2569 | 12 funcs, ~145 lines |

**Field/dependency smell**: ~50 fields, ~24 constructor-injected collaborators (`fileSystem, pageRepository, blockRepository, searchRepository, graphLoader, graphWriter, platformSettings, notificationManager, journalService, blockStateManager, writeActor, undoManager, exportService, histogramWriter, bugReportBuilder, debugFlagRepository, activeGitSyncService, activeGraphIdProvider, onDismissGitDetection, onSectionsLoaded, ringBuffer, llmSuggestionInbox, llmSuggestionWriter, scope`). Almost no function uses more than 2-3 of these 24 at once — each bounded-context cluster touches its own small, disjoint subset. The class exists because every UI feature needed *somewhere* to put its ViewModel-side logic, not because these responsibilities share state or invariants.

## Top extract-class candidates, ranked by (size × self-containment)

1. **DiskConflictController** — 19 funcs, ~380 lines (`observeExternalFileChanges`, `checkAndShowPendingConflict`, `keepLocalChanges`/`acceptDiskVersion`/`manualResolve`/`saveAsNewBlock`, `tryMatchDiskBlockContent`, `clearPendingConflict`). **Independently flagged by both reviews as the #1 candidate.** Near-zero leakage into other clusters — only needs `blockStateManager`, `graphLoader`/`graphWriter`, `DiskConflictBlockMatcher`, `ConflictMarkerDetector`. Corroborated by `DiskConflictResolutionTest.kt` already being the largest test file in the directory (635 lines) — forced to stand up the *entire* ViewModel fixture to test one bounded context.
2. **ExportController** (Share Dialog + clipboard export + Google Docs/Drive) — 14 funcs, ~330-580 lines depending on how the boundary is drawn. Cohesive around `exportService`/`notificationManager`, but more entangled with directly-UI-read `AppState` fields than #1.
3. **BlockEditingController** — 13 funcs, ~215 lines. Thin `blockRepository` delegators — the easiest mechanical extraction, though see the layering inconsistency noted below before extracting as-is.
4. **SectionsController** — 12 funcs, ~145 lines. Cohesive around `sectionManifestParser`/`Writer`; already has its own comment header.
5. **NavigationController** — 9 funcs, ~200 lines. Cohesive but needs callback hooks into recent-pages/commands/conflict clusters — extract after #1 and #4, not first.
6. **PageListController** (recent/regular/journal lists) — 6 funcs, ~185 lines, isolated mutex-guarded state.
   *(Runner-up: **LlmSuggestionController** — only 5 funcs/~70 lines, but the cleanest possible extraction with zero shared mutable state beyond one visibility flag — a good "prove the pattern works" first PR before tackling the bigger ones.)*

`updateCommands()` (2086-2185) is the hardest to extract cleanly — it's a hub reaching into nearly every other cluster (rename, export, import, sections) and should be extracted *last*, wired via callbacks once its dependents already exist as separate classes.

## Clean Architecture layer violations (file:line)

- **Widespread `@OptIn(DirectRepositoryWrite::class)` bypass of `DatabaseWriteActor`.** The annotation exists specifically to prevent `SQLITE_BUSY` by forcing writes through the actor (`repository/DirectRepositoryWrite.kt:8`, `RequiresOptIn(ERROR)` — meant to be a rare, deliberate escape hatch). `StelekitViewModel` opts out of it in **15+ methods**: `toggleFavorite` ×2 (848, 857), `clear` (865), `indentBlock`/`outdentBlock`/`moveBlockUp`/`moveBlockDown`/`moveBlock` (873, 880, 887, 894, 901), `addNewBlock` (912), `addBlockToPage` (952), `splitBlock` (987), `mergeBlock` (996), `handleBackspace` (1020), `navigateTo` (1089, via `addToRecent`), `bulkDeletePages` (1295), `createPage` (1321), `manualResolve` (1639), `saveAsNewBlock` (1679), `movePageToSection` (2443). This class is the single largest consumer of the opt-out in the codebase — the escape hatch has become the dominant pattern, not a narrow exception.
- **Direct file-system manipulation bypassing `GraphWriterPort`**: `bulkDeletePages` (1295-1314) calls `fileSystem.deleteFile(path)` directly at line 1304, instead of routing through `GraphWriterPort` like every other disk write in the class.
- **Domain policy embedded directly in ViewModel functions**, not a domain/use-case class:
  - `createPage` (1321-1359): journal-detection regex (`^\d{4}[-_]\d{2}[-_]\d{2}$`, line 1327) + default-section assignment policy — business rule logic, not view-state orchestration.
  - `addNewBlock` (912-946) / `addBlockToPage` (951-984) hand-roll sibling-shifting/position-calculation directly in the ViewModel — **inconsistent with sibling methods in the same class**: `splitBlock` (987-993) / `mergeBlock` (996-1017) correctly delegate the equivalent structural operation to `blockRepository`. Two different layering conventions coexist for the same category of operation, in the same file.
  - `manualResolve` (1638-1672) builds the git-style `<<<<<<< / ======= / >>>>>>>` conflict-marker text directly in the ViewModel (1649-1659) — this belongs beside the already-extracted `ConflictMarkerDetector`, which is currently only used to *detect* markers, not build them.
- **Settings-key string literals** scattered across ~10 unrelated features with no centralized keys abstraction (`"lastGraphPath"`, `"cached_graph_path"`, `"graph_registry"`, `"onboardingCompleted"`, `"isLeftHanded"`, `"db.libsql.enabled"`, `"defaultSection"`, `"deviceSetupComplete"`, etc.).

## Function quality

**Longest functions:**
- `loadGraph()` (669-819, ~150 lines) — mixes directory setup, 3-phase progressive-load callbacks, and 3 duplicated catch-clause error-string-building blocks. **P1.**
- `observeExternalFileChanges()` (1408-1505, ~97 lines) — bundles not-viewing-page bookkeeping, four-tier dirty-check, disk-block matching, and state update. **P1.**
- `updateCommands()` (2086-2185, ~99 lines) — builds ~10 unrelated command registrations inline. **P2.**

**High parameter-count functions (candidates for a request/parameter object):**
- `exportScopeToClipboard(...)` — 8 params (line 1809). **P1.**
- `resolveExportContent(...)` — 7 params (line 1777).
- `shareToGoogleDocs(...)` — 7 params (line 1909).
- `createSection(...)` — 5 params (line 2474). **P2.**

**Swallowed errors** (violates this repo's own "surface, don't vanish" Either convention):
- `retryIndexing()` (1575-1589): catches `Exception` and resets state with **zero logging or user feedback** — every sibling catch block in this file logs via `logger.error`; this one is silently inconsistent. **P1.**
- Block-editing cluster (`addNewBlock`, `splitBlock`, `mergeBlock`, `handleBackspace`, `focusPreviousBlock`, `focusNextBlock`, `addBlockToPage`) — ~7 call sites doing `blockRepository.getXxx(...).first().getOrNull() ?: return@launch`, silently no-op-ing a *user-initiated* action on DB-read failure with zero feedback. **P2** (pattern).
- `bulkDeletePages` — logs per-item failures but never surfaces an aggregate count; if 3 of 10 deletes fail, the UI reports success. **P2.**

## Interface segregation / DIP

`StelekitViewModel` implements **no interface at all** (line 113) — composables and tests depend on the full concrete 139-method surface regardless of how much they actually use. ISP is violated by omission: there's nothing to segregate because nothing was ever split out. Any future class extraction should ship paired with a narrow interface (e.g. `DiskConflictController` implementing a small `DiskConflictActions` interface the conflict-dialog composable depends on, instead of the whole ViewModel).

## What's already good (preserve on any refactor)

The class already delegates extensively to dedicated collaborators — `GraphLoaderPort`, `GraphWriterPort`, `BacklinkRenamer`, `ExportService`, `JournalService`, `CommandManager`, `PageNameIndex`, `UndoManager`, `DatabaseWriteActor`, `BlockStateManager`, `SectionManifestParser/Writer`, `DiskConflictBlockMatcher`, `ConflictMarkerDetector`, `LlmSuggestionInbox/Writer`. The team already understands and applies the coordinator/facade pattern for most concerns — the fix is extracting the *remaining* inline logic above into further collaborators, not introducing the pattern from scratch.

## Testability evidence

15 of 19 test files in `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/` directly construct the full `StelekitViewModel(...)` — including tests that only exercise one narrow slice (`RecentPagesTest.kt`, `SectionPickerTest.kt`, `DragDropReorderTest.kt`, `KeyboardShortcutTest.kt`), each standing up all ~24 injected collaborators just to test e.g. section-picker visibility. `DiskConflictResolutionTest.kt` (635 lines) is the largest test file in the directory — directly corroborating that disk-conflict resolution is both the densest bounded context *and* the best extraction target: a dedicated coordinator would let that test suite target a ~300-line class with a much smaller fixture.

## Severity-ranked findings

**P0** — God Object itself: 139 functions / ~24 unrelated dependencies / disjoint field usage / no interface segregation. Root cause of every finding below; matches the hotspot analysis's complexity×churn flag exactly.

**P1 (high):**
1. ~18 bounded contexts in one 2,574-line class.
2. Systemic `@OptIn(DirectRepositoryWrite::class)` bypass across 15+ call sites — the write-serialization guarantee's designated rare exception is this class's dominant pattern.
3. Disk-conflict resolution is a fully self-contained, un-extracted bounded context (confirmed by its outsized dedicated test file) — the single best first extraction.
4. Inconsistent structural-operation layering: `addNewBlock`/`addBlockToPage`/`handleBackspace` hand-roll domain logic the sibling `splitBlock`/`mergeBlock` correctly delegate to the repository.
5. `loadGraph()` — 150-line mixed-abstraction function.
6. `observeExternalFileChanges()` — 97-line function bundling 4 concerns.
7. `retryIndexing()` — silent exception swallow, zero user feedback.
8. `exportScopeToClipboard`/`resolveExportContent`/`shareToGoogleDocs` — 7-8 parameter functions.
9. No interface implemented by `StelekitViewModel` (DIP/ISP).

**P2 (medium, 7 findings):** `updateCommands()` mixing ~10 registrations; direct `fileSystem.deleteFile` bypassing `GraphWriterPort` in `bulkDeletePages`; `createPage`'s embedded journal-detection regex/policy; `manualResolve`'s inline marker-text construction (should live beside `ConflictMarkerDetector`); scattered settings-key string literals; `createSection`'s 5-param signature; `flushAndLockVault` shadowing injected fields with same-named parameters; the block-editing cluster's silent `getOrNull() ?: return@launch` pattern (~7 sites); `bulkDeletePages`'s unsurfaced aggregate failures.

**P3 (low, 4 findings):** ~17 trivial one-line `_uiState` setters inflating the function count without real complexity; magic destination strings in `navigateTo(String)` duplicating `Screen` routing; near-duplicate `focusPreviousBlock`/`focusNextBlock` traversal logic; duplicate-purpose overloads (`navigateTo(Screen)` vs `navigateTo(String)`, `toggleFavorite(Page)` vs `toggleFavorite(String)`).

## Recommended roadmap

**Short term (prove the pattern, lowest risk):**
1. Extract `LlmSuggestionController` first — smallest surface (5 funcs/~70 lines), zero shared mutable state beyond one flag. Validates the extraction pattern (thin delegation methods on `StelekitViewModel`, narrow interface, dedicated test fixture) before committing to the bigger ones.
2. Fix `retryIndexing()`'s silent exception swallow (P1, cheap, no extraction required).
3. Fix the newline-check-style small correctness issues in the parameter-heavy export functions by introducing an `ExportRequest` data class (also cheap, no extraction required).

**Medium term (the real payoff):**
4. Extract `DiskConflictController` — the highest-value target per both reviews and the test-file evidence. This directly shrinks the largest, most entangled test fixture (`DiskConflictResolutionTest.kt`).
5. Extract `SectionsController` and `BlockEditingController` — but resolve the `addNewBlock`/`addBlockToPage` vs. `splitBlock`/`mergeBlock` layering inconsistency *before* or *during* the `BlockEditingController` extraction, not after (extracting inconsistent logic just relocates the inconsistency).
6. Narrow the `@OptIn(DirectRepositoryWrite::class)` surface as each cluster is extracted — each new controller is an opportunity to route its writes through `DatabaseWriteActor` properly instead of carrying the opt-out forward.

**Longer term:**
7. Extract `ExportController`/`NavigationController`/`PageListController`.
8. Extract `updateCommands()` last, once its dependents (rename, export, sections, import) already exist as separate classes it can delegate to via injected callbacks/interfaces.
9. Revisit `StelekitViewModel`'s own name once it's reduced to a thin coordinator — at that point a more specific name may be warranted, or it may legitimately remain the coordinator/facade root.
