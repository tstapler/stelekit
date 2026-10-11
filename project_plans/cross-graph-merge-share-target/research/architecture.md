# Architecture research: cross-graph-merge-share-target

Scope: how to (a) read a source graph and write a target graph in one operation, and (b) write a
shared item into a non-active graph from `CaptureActivity` / `CaptureController`. Requirements:
`../requirements.md`. Confidence labels: VERIFIED = code opened this session; INFERRED = reasoned, not run.

## 0. Facts established

- VERIFIED: `GraphManager` exposes one `_activeRepositorySet: MutableStateFlow<RepositorySet?>`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:99`). `switchGraph` (`:804`)
  cancels the previous graph's job, evicts its coordinator, and closes the captured factory on an IO
  coroutine before opening the next. `getActiveRepositorySet()` (`:1011`) is the only accessor used by
  capture callers.
- VERIFIED: `GraphMergeService` (`transfer/GraphMergeService.kt:25-81`) works around this with an
  in-memory `List<PageSnapshot(name, markdown)>`: `snapshot(source)` while source is active, user
  switches, `merge(target, fs)` while target is active. It skips same-named pages (`:62-65`), reads the
  whole source (`getAllPagesSnapshot` plus one `getBlocksForPage` per page, `:43-47`) and holds all
  markdown in a `var`. This violates the requirements' 8 000-page memory SLO.
- VERIFIED: the write path for a page is `QrImportService.import(markdown, PageName)` (parse and save
  via `GraphLoader` + `DatabaseWriteActor`) used by merge; capture instead uses
  `journalService.ensureTodayJournal()` then `writeActor.saveBlock` then `GraphWriter.savePage(page,
  blocks, graphPath)` (`androidApp/.../CaptureViewModel.kt:313-354`, also documented in
  `project_plans/desktop-quick-capture/research/architecture.md` section 1).
- VERIFIED: `CaptureController` (`kmp/src/jvmMain/.../capture/CaptureController.kt:35`) holds a single
  `graphManager` reference and owns its own scope; `CaptureViewModel` resolves
  `graphManager.getActiveRepositorySet()` and `getActiveGraphInfo()?.path` (`:313`, `:317`). Both are
  hard-wired to the active graph, so a graph chooser is a new parameter, not a tweak.
- VERIFIED: `GraphWriter` pre-write/post-write notifies the `GraphLoader` file watcher so its own writes
  are not reported as external changes (`GraphWriter.kt:545`, `:617`; `GraphLoader.externalFileChanges`
  `:479`). That suppression lives on the active graph's watcher instance.
- VERIFIED: `RelocationStagingDirectory` (`db/RelocationStagingDirectory.kt`) is a staging path plus
  `.marker` JSON (`graphId`, `startedAtEpochMs`) with a 7-day `sweep` that never deletes marker-less
  directories. Reusable convention, not reusable code (its prefix and API are relocation-specific).
- VERIFIED: Android SAF code is isolated in `kmp/src/androidMain/.../platform/PlatformFileSystem.kt`,
  `SafChangeDetector.kt`, `git/GitShadowWorktree.kt`. A SAF graph is accessed through `FileSystem` with
  `content://` tree paths; app-owned graphs are plain `filesDir` paths.
- INFERRED: each graph has its own SQLite file/driver (per-graph DB, per `GraphManager` KDoc: "per-graph
  RepositorySets"), so two open at once is physically possible. Not measured; the pool
  (`PooledJdbcSqliteDriver`, 8 connections each) and WAL checkpoint cost doubling is the real risk.

Prior research cited (not re-derived):
- `project_plans/desktop-quick-capture/research/architecture.md` section 1 (capture write chain;
  recommends factoring a shared `QuickCaptureService`; this feature should parameterize it by target).
- `project_plans/app-owned-storage-clone/research/architecture.md` section 1 (`StorageLocation` sealed
  interface modeled on `DomainError`; use it to describe a target graph's write surface, SAF vs filesDir vs OPFS).
- `project_plans/android-share-capture-whitespace/research/architecture.md` (`buildShareText` is a pure
  companion function; share normalization happens before save, so it is target-independent and needs no change).

## 1. Options for (a): read source and write target in one operation

### A1. Multi-open `GraphManager` (a second, non-active `RepositorySet`)
- Data flow: `openSecondary(targetId): RepositorySet` builds a factory/DB without touching
  `_activeRepositorySet`; merge reads source, writes target through the target's own `writeActor`; close when done.
- Pros: reuses DB-level merge (UUID lookups, idempotent via `getBlockByUuid`), no file parsing round-trip.
- Cons: highest blast radius. `GraphManager` is a 1 478-line class with 51 commits in 6 months (VERIFIED
  `git log`); `activeGraphJobs`, `currentFactory`, `_pendingMigration`, coordinator eviction, and
  `closeAndClearCryptoLayer` all assume one live graph. The target's `GraphWriter` and file watcher would
  be unwatched, so its writes would later be seen as external changes (see section 3). Android memory
  (two driver pools plus caches) is a concern on the OOM-prone path CLAUDE.md warns about.
- Verdict: reject for the general case.

### A2. Direct markdown write to the target directory plus lazy re-index
- Data flow: source (active or read-only) yields page markdown in bounded chunks; pure merge function
  produces merged markdown for each page; write to `<targetPath>/pages|journals/<file>.md` via `FileSystem`; the
  target graph indexes on next open via the normal `GraphLoader.loadDirectory` reconcile.
- Pros: no second DB; markdown is the source of truth so it is the format both graphs already understand;
  works for any platform with a `FileSystem`. Memory bounded by chunk size.
- Cons: must read the target's existing page from disk (parse markdown to blocks) since its DB is closed;
  needs the same filename and namespace encoding as `GraphWriter.getPageFilePath` (`:790`); no
  `DatabaseWriteActor` involvement (so none of the `@DirectSqlWrite` surface is touched, which is a plus);
  target indexing is deferred, so the "dry-run" must compute from disk.
- Verdict: strong candidate for writes into a non-active graph.

### A3. Queued inbox consumed on next switch
- Data flow: write a serialized `PendingImport` (target graph id, page name, blocks markdown) to
  app-private storage; when the target becomes active, `GraphManager`/app consumes it through the normal
  active-graph write path.
- Pros: uses the fully tested active-graph write path (`GraphWriter`, watcher suppression, DB actor).
- Cons: deferred, invisible effect ("share succeeded" but nothing in the target graph until the user
  switches); violates the success metric "lands in the chosen graph in one step". Requires crash-safe
  queue, ordering, and a failure surface. Only appropriate as the fallback when A2 cannot write (e.g. SAF
  grant revoked).
- Verdict: use only as the failure fallback for share.

### A4. Staging directory (cf. `RelocationStagingDirectory`)
- Data flow: spill the selection snapshot to `<appDataDir>/.stele-merge-staging-<mergeId>/` (one
  markdown file per page plus `.marker` with source/target ids and `startedAtEpochMs`); the merge phase
  reads files back, so memory stays bounded across the graph switch.
- Pros: directly fixes the 8 000-page in-memory snapshot; survives process death; sweepable using the
  same marker-or-never rule. Complements A1-A3: it is a transport between "source active" and "target
  active", not a write strategy.
- Cons: extra disk IO and cleanup; the staging prefix and API must be generalized or duplicated.
- Verdict: adopt for the copy/merge transport; keep RAM-only for tiny selections (e.g. under ~50 pages).

### Recommendation for (a)
Two-phase flow with a staging directory (A4) as transport, and the merge applied at write time against
the target's on-disk page (A2) when the target is not active, or against the active DB through the
existing `QrImportService`/`GraphWriter` path when it is. This leaves `GraphManager` single-open.
Read the source in bounded chunks (`getPages(limit, offset)`, `getPagesByNames`, <=100 rows per batch
like `LargeGraphWarmStartCrashTest`), never `getAllPagesSnapshot` held as a field.

## 2. Options for (b): share into a non-active graph (CaptureActivity / CaptureController)

A share is one small block appended to a journal page (or a new page), so the cost model differs from merge.

| Option | One step for user | Consistent with watcher | SAF-safe | Complexity |
|---|---|---|---|---|
| Multi-open GraphManager | yes | no (target unwatched) | yes | high |
| Direct markdown append to target journal file (A2) | yes | yes after reload (see s.3) | needs tree grant | medium |
| Inbox, consume on switch (A3) | no | yes | n/a | medium |
| Staging dir | no (transport only) | n/a | n/a | low |

Recommendation: direct markdown append (A2) when the target is not the active graph; the existing
active-graph chain (`ensureTodayJournal` then `saveBlock` then `GraphWriter.savePage`) when it is. If the
direct write fails (no grant, IO error), fall back to the inbox (A3) and show a visible "queued for
<graph>" state per the requirements' observability section (not silent loss).

Shape: extract a `commonMain` `CaptureTarget` resolution + `JournalAppendWriter`:
`appendToJournal(target: StorageLocation/GraphInfo, date, blockMarkdown): Either<DomainError, Unit>`
with two implementations (`ActiveGraphAppend` via repo set, `OffGraphMarkdownAppend` via `FileSystem`).
`CaptureViewModel` and `CaptureController` both call it; this also delivers the shared
`QuickCaptureService` that the desktop-quick-capture doc recommended. Deduping appended blocks reuses the
same pure merge function (append is a merge of a one-block page), which keeps share idempotent on
Android intent re-delivery (`onNewIntent`).

## 3. Consistency with the file watcher and `GraphWriter`

- Active target: always go through `GraphWriter` so `onPreWrite`/`onPostWrite` suppress the self-write
  (VERIFIED mechanism, `GraphWriter.kt:545,617`). Never write the file behind its back.
- Non-active target (A2): there is no watcher for it, so nothing fires at write time. On next
  `switchGraph`, `GraphLoader.loadDirectory` reconciles by file modification time/content hash and
  indexes the new content. INFERRED: no `DiskConflict` unless the user had unsaved edits to the same
  page in that graph's earlier session; verify with a test (see section 7).
- Race: the user switches to the target graph mid-merge. Mitigation: serialize via a per-graph-id
  `Mutex` held by the merge and by `switchGraph`'s init for that id (a small seam, not a rewrite), or
  refuse to start an off-graph write while `activeGraphJobs` contains the target id and route to the
  active path instead.
- Atomicity: write temp file plus rename where `FileSystem` supports it (A2 is additive-only, so a torn
  write loses only the new content; the requirement "never delete target content" must hold, so verify the
  rename path on SAF, where rename semantics differ).
- The merge function must be a no-op if the merged markdown is byte-identical to what is on disk, to
  avoid touching mtime and triggering needless re-index.

## 4. Android SAF

- VERIFIED location of SAF logic: `PlatformFileSystem.kt`, `SafChangeDetector.kt`, `GitShadowWorktree.kt`
  (androidMain). A non-active SAF graph needs a persisted tree URI permission
  (`takePersistableUriPermission`) to be written from `CaptureActivity`, which runs in the same app
  process and package so persisted grants apply. INFERRED: no extra permission prompt for graphs the user
  already added; revoked grants must map to a `DomainError.FileSystemError` and the inbox fallback.
- Slow SAF IO: the merge reads many small files; SAF per-file `ContentResolver` overhead is large.
  Prefer the app-owned/shadow-worktree representation where the graph has one (see
  `project_plans/app-owned-storage-clone/research/architecture.md`) and write to that; the existing git
  shadow flush actor pushes it back. Do not write to the SAF tree from the merge hot loop; batch via the
  staging directory (A4) under `filesDir`, then one write per page.
- `CaptureActivity` is a separate task/process-affine entry point: it must resolve the target graph from
  the registry (`GraphRegistry`/settings) without starting full `GraphManager` init for the other graph.
  Resolve `graphPath` from the persisted registry only; do not `switchGraph`.

## 5. Block-level merge algorithm placement

Pure function in `commonMain`, no IO, no repositories:

```
mergePage(existing: ParsedPage?, incoming: ParsedPage, policy: MergePolicy): MergeOutcome
MergeOutcome = Unchanged | Merged(page: ParsedPage, added: Int, conflicts: List<BlockConflict>) | New(page)
```

- Input is a lightweight block-tree model (uuid?, content, properties, children), parsed from markdown by
  the existing parser (no new parser). Output renders through `LogseqPageSerializer` (VERIFIED used by
  `GraphMergeService.snapshot`).
- Match order: UUID (`id::` property) then normalized-exact content among siblings of the matched parent;
  properties union (target wins on key conflict, conflict recorded); a same-UUID/different-content pair
  becomes a sibling block flagged as conflict (answers an open question with the additive-only rule;
  decision belongs to planning).
- Placement: unmatched incoming blocks keep their parent if the parent matched, else go under a matched
  ancestor, else append at the end of the page. Positions via the existing `FractionalIndexing`
  (referenced in `CaptureViewModel.performSave`).
- Idempotence property: `merge(merge(t, s), s) == merge(t, s)`; monotonicity: every target block survives.
  Test with kotest-property in `commonTest` (CLAUDE.md "Prefer property-based tests"), with a few named
  examples alongside.
- A `PageMergeService` (commonMain, suspend, Either) orchestrates chunking, staging, and the two write
  strategies; `GraphMergeService` becomes a thin deprecated wrapper or is replaced.

## 6. Hotspot disposition

- `GraphManager` (1 478 lines, 51 commits in 6 months, VERIFIED by `wc -l` and `git log`): **Isolate via
  seam**: add no multi-open state; introduce a small `GraphLocator` (resolve `GraphInfo`/path/`StorageLocation`
  by id without activating) and a per-graph write `Mutex`, and keep all new logic in new classes.
- `GraphMergeService`: **Refactor-first (small)**: replace the in-memory snapshot with the staging-directory
  transport and the pure merge function before layering selection UI on it; the class is small (82 lines) and
  the change is its core.
- `CaptureViewModel` / `CaptureController`: **Extend as-is** after extracting the shared
  append-to-journal service they both already duplicate.

## 7. Event-Command-Policy table

| Domain Event | Policy trigger | Command | Actor/System |
|---|---|---|---|
| PageSelectionConfirmed (source, target, page set) | When user confirms selection | PlanMerge (dry-run: classify new/merged/unchanged) | User / MergePlanner |
| MergePlanComputed | Always after plan | ShowDryRunSummary | MergePlanner / UI |
| MergeConfirmed | User accepts dry-run | StageSelection (spill pages to staging dir with marker) | User / PageMergeService |
| SelectionStaged | Target graph is active | ApplyMergeActive (merge via DB + `GraphWriter`) | PageMergeService |
| SelectionStaged | Target graph is not active | ApplyMergeOffGraph (merge on-disk markdown) | PageMergeService |
| PageMerged / PageCreated / PageUnchanged | Per page | RecordOutcome (counts, `Logger`) | PageMergeService |
| BlockConflictDetected | Same UUID, different content | KeepBothAsSibling and FlagConflict | MergeFunction |
| MergeCompleted | All pages processed | SweepStaging and ShowSnackbar(summary) | PageMergeService / UI |
| MergeFailed (IO, grant revoked) | Any page write error | KeepStaging and ShowFailureState (retry) | PageMergeService / UI |
| StaleStagingFound | App start, marker older than 7 days | SweepStaging (marker-or-never rule) | Startup sweeper |
| ContentShared (Android SEND / desktop hotkey) | Capture screen shown | ResolveCaptureTarget (last used or Settings default) | CaptureActivity / CaptureController |
| CaptureTargetChosen | User taps Save | AppendToJournal(target) | CaptureViewModel / QuickCaptureService |
| TargetIsActiveGraph | Target equals active | AppendViaActiveRepoSet (existing chain) | QuickCaptureService |
| TargetIsInactiveGraph | Target differs from active | AppendViaMarkdownFile | QuickCaptureService |
| AppendFailed (no SAF grant, IO) | Any write failure | EnqueueInbox and ShowQueuedState | QuickCaptureService |
| GraphActivated (switchGraph complete) | Inbox has items for graph id | DrainInbox (apply through active path) | GraphManager seam / InboxConsumer |
| InactiveGraphModified | Next load of that graph | ReconcileFromDisk (`GraphLoader.loadDirectory`) | GraphLoader |

## 8. Open items for planning

- Confirm with a spike (not yet run) that a markdown file written into a closed graph reconciles cleanly
  on `switchGraph` without a spurious `DiskConflict`.
- Decide whether tiny selections skip the staging directory.
- Verify rename/atomic-write semantics on SAF trees before choosing temp-plus-rename there.
- iOS and Web share targets remain out of scope here; the A2/A3 design needs only a `FileSystem` and works
  unchanged if an entry point is added later.
