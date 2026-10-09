# Pitfalls and risks: cross-graph merge and share target

Confidence: VERIFIED = opened or grepped in this repo; GENERAL = well-known failure mode, not checked here.

## Codebase facts that shape the risks (VERIFIED)

- `GraphMergeService` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/GraphMergeService.kt`) snapshots the whole source graph as serialized markdown held in memory (`snapshot()` loops `getAllPagesSnapshot()` + `getBlocksForPage(...).first()`), then `merge()` skips pages whose lowercase name exists in the target and imports the rest through `QrImportService`. It also calls `getAllPagesSnapshot()` on the target. Both are O(graph) in heap.
- `GraphManager` (`db/GraphManager.kt:80`, `switchGraph` at :804) holds one `_activeRepositorySet`. `activeGraphJobs` holds one `CoroutineScope` per graph, and `switchGraph` cancels the previous one.
- `blocks.uuid` is `UNIQUE`, `parent_uuid` is `ON DELETE CASCADE`, and `left_uuid` is `ON DELETE SET NULL` (`SteleDatabase.sq:24-39`). Siblings form a left-linked list, not an index column.
- `GraphWriter` (`db/GraphWriter.kt`) pre-marks each write as an own-write in `FileRegistry` (Step 0, ~line 542) so the watcher ignores it, and notifies via `onFileWritten`.
- Android SAF: `PlatformFileSystem.kt:122` and `:848` handle persisted tree URIs. `GraphMoveQuiesceStrategy.android.kt:24` notes `MainActivity` grants read+write via `takePersistableUriPermission`. The `androidUnitTest` files `SafPermissionPersistenceTest`, `SafPermissionStateTransitionTest` and `UpgradePathTest` show grants are lost after reinstall.
- No `CaptureActivity` class or manifest SEND filter was found under `kmp/src` (grep hit only `strings.xml` and a `GraphManager` mention). The requirements doc describes it as existing, so confirm where it lives (possibly outside `kmp/src`) before planning. UNVERIFIED.

## 1. Outliner block merge

| Pitfall | Why it happens here | Mitigation |
|---|---|---|
| Duplicates on repeat merge | The current path re-imports via markdown, and the parser may assign fresh UUIDs unless the `id::` property round-trips. Content-only matching also fails when the user edits one side. | Match key order: UUID, then (parent-match, normalized content). Persist source UUID for imported blocks. Make idempotence a property test: `merge(merge(a,b),b) == merge(a,b)`. |
| UUID reuse with different content | Same UUID in both graphs happens after a graph copy or a split graph, and the two copies then diverge. `blocks.uuid` is globally `UNIQUE`, so inserting a "new" block with a colliding UUID throws or silently overwrites (INSERT OR REPLACE would trigger the `ON DELETE CASCADE` on children). | Never insert with a colliding UUID. Treat a same-UUID, different-content block as a conflict: mint a new UUID for the incoming copy and flag it. Check which upsert form the block insert uses before relying on this. |
| Lost children | Deleting or replacing a parent cascades. Reparenting an unmatched child under a matched parent whose UUID was remapped orphans it. | Merge top-down: resolve parent mapping (source UUID to target UUID) first, then children. Additive-only: no deletes, no `INSERT OR REPLACE` on blocks. Test with the invariant "every source block's content is reachable in the result". |
| Reorder / placement | Siblings are a `left_uuid` linked list, so inserting a block means fixing two links. Unmatched source blocks have no obvious position. | Anchor to the nearest preceding matched sibling, else append at the end of the parent. Never reorder existing target blocks. Use the existing block-insert path rather than hand-writing `left_uuid`. |
| Property conflicts | Same key, different values (`id::`, `collapsed::`, `tags::`, `alias::`). Page-level properties are in the first block. | Union keys. Multi-valued keys (tags, alias) union their values. For a scalar clash, keep the target value and report a conflict. Never merge `id::` as data. |
| Journal pages | Same date, different names across graphs (title format, case). Name matching is lowercase only. | Match journals by date, not name (`getJournalPagesByDates` exists). |
| Normalization | Trimmed-content equality misses trailing-whitespace and CRLF differences. Fuzzy matching wrongly merges short blocks such as "TODO" or "-". | Exact match on whitespace-normalized text only, and require parent context for short or empty blocks. Never fuzzy-match. |
| Page-level data | Name collisions differing only by namespace or case, aliases, and `is_journal` mismatch. | Key by lowercase name plus journal flag, and surface aliases as conflicts. |

## 2. Writing to a graph while its file watcher is live

- GENERAL: markdown is the source of truth and the DB is an index. Writing only to the DB creates drift the next reconcile may revert. Writing only to files leaves the DB stale until the watcher or a reload picks it up.
- VERIFIED: self-write suppression is per file through `FileRegistry` pending marks (`GraphWriter.kt` ~542-617). A merge writing many files via a different code path would not be suppressed. The result is `DiskConflict` prompts, double-parsing, or a merge that overwrites a concurrent user edit. Route all merge writes through `GraphWriter` or the same pre-mark API.
- Last-writer-wins: target pages open in the editor with unsaved debounced edits (500ms, per CLAUDE.md data flow) can be clobbered. Merge into a page being edited must go through `BlockStateManager` or be blocked.
- Non-active target (single-open constraint): opening a second `RepositorySet` means a second DB driver and writer actor on the same files. Two actors can race on one graph if the user later switches to it while the merge is in flight, so quiesce or lock per graph. `GraphMoveQuiesceStrategy` is the existing precedent for quiescing.
- Watcher bursts: writing N files fires N events. The watcher must not trigger a full reconcile per file (see the O(graph) rule below).
- A crash mid-merge leaves a partial result. Per-page atomic writes (temp file then rename) and idempotence make a re-run safe, which is another reason idempotence is a hard requirement.

## 3. Android SAF and URI permissions for a non-active graph

- VERIFIED: grants are persisted per tree URI and are lost on reinstall (`UpgradePathTest`, `SafPermissionStateTransitionTest`). A non-active graph's grant may have been revoked or never persisted without anyone noticing, since it has not been opened in a while. Check `contentResolver.persistedUriPermissions` for write access before offering the graph as a share destination. Show it disabled with a re-grant action if missing.
- GENERAL: the system caps persisted URI grants (128 on API 30+, 512 on newer), so many graphs plus attachments can evict old ones.
- GENERAL: SAF writes are slow (a Binder call per file, and `DocumentFile` listing is O(n)). A multi-hundred-page merge to SAF is much slower than to a local path. Batch and show progress, and do not hold the main thread.
- GENERAL: a `content://` URI delivered by a share intent carries only a temporary read grant that dies with the receiving task. Copy shared images and files into the graph's assets during the same activity instance, or into app-private storage first.
- `PlatformFileSystem.kt:122` says not to call `takePersistableUriPermission` on some URIs (app-private copy case). Follow that comment.
- Assets with the same filename in two graphs collide. Hash-name or suffix on conflict, and rewrite links in the merged markdown.

## 4. Large-graph memory (8 000 pages)

- VERIFIED violation: `GraphMergeService.snapshot()` and `merge()` both materialize the whole graph (`getAllPagesSnapshot()` plus a serialized markdown string per page). `getAllPagesSnapshot()` is explicitly for "whole-graph one-shots" in CLAUDE.md, but holding all serialized pages plus all blocks is the heap-spike pattern it warns about, and it risks OOM on Android. CLAUDE.md also forbids pinning full-table snapshots in fields, and `snapshot` is exactly that field.
- Required patterns per CLAUDE.md: select by page UUID list or chunk. Use `getPagesByNames(chunk)` and `getJournalPagesByDates(chunk)` with `IN` lists of 500 or fewer. Use `getPages(limit, offset)` for the picker (paginated and searchable, never a full list). Stream per page: read, merge, write, release.
- Do not add an unbounded `getAllPages()` to any repository. This is compile-time enforced by absence.
- Link or asset closure: transitive `[[links]]` can pull in the whole graph. Cap depth (default 1, opt-in), dedupe with a visited set, and show the count in the dry-run before commit.
- Dry-run must not need the full merge result in memory. Compute counts (new, merged, unchanged) per chunk and keep only counters plus a bounded list of conflict details.
- Every DB write invalidates standing queries on the table. A merge writing thousands of blocks while sidebar or `PageNameIndex` collectors run re-materializes results per burst. Use existing projections and debounce, and batch writes in transactions through the actor.
- Android: guard the merge scope with a `CoroutineExceptionHandler` (CLAUDE.md "Uncaught coroutine Throwables"). An OOM in a merge coroutine kills the process on Android but only prints on desktop.
- Regression tests to mirror: `LargeGraphWarmStartCrashTest` (batch size under 100), `QueryPlanAuditTest` for any new query.

## 5. Android share intents

- GENERAL: process death. A share can arrive while the app process is dead, and a task backgrounded during the destination picker can be killed. Persist the pending payload (text or URI plus chosen graph) before any UI, for example in `savedInstanceState` or a small durable store, and write it exactly once. Do not hold the payload only in a ViewModel.
- GENERAL: `onNewIntent`. With `singleTop` or `singleTask`, a second share arrives in `onNewIntent`, so handle both `onCreate` and `onNewIntent` or the second share is dropped.
- GENERAL: config change and rotation. Do not re-process the intent on recreation (duplicate journal entries). Use a handled flag in `savedInstanceState`, or `intent.removeExtra`, and be idempotent on content hash plus timestamp.
- GENERAL: temporary URI permission. Reading the stream URI after the activity finishes can throw `SecurityException`. Read it synchronously first, or use `ClipData` plus `FLAG_GRANT_READ_URI_PERMISSION` hand-off to a foreground service or WorkManager job.
- GENERAL: never block `onCreate`. A share to a non-active graph that needs to open a DB and do SAF writes should run in a coroutine owned by something that outlives the activity (an application-scope or WorkManager unit) so that finishing the overlay does not cancel the write. This ties to CLAUDE.md's rule that `rememberCoroutineScope` is for transient UI work only: do not give it a writer or any long-lived object, since the scope is cancelled when the composable leaves.
- Failure visibility: the requirements demand a visible failure state. A fire-and-forget write that fails after the overlay closes is silent loss, so post a notification or persist a failed-share inbox.
- Remembered last graph can be stale (graph removed). Validate at use time and fall back to the default.
- Desktop `CaptureController` (`jvmMain/.../capture/CaptureController.kt:35`) takes a `PlatformFileSystem`, so it already writes through files. A non-active target there is the same watcher and DB-sync issue as section 2.
- No `CaptureActivity` or SEND filter was found in `kmp/src` (see top). Locate it first.

## 6. SteleKit CLAUDE.md rules that constrain the design

| Rule | Consequence |
|---|---|
| Arrow `Either<DomainError, T>` at repository and service boundaries; no `Result`, nullable, or thrown domain errors; wrap SQLite exceptions in `DomainError.DatabaseError.WriteFailed` | The new merge function and service return `Either`. `GraphMergeResult` currently uses string lists, so add a typed conflict and failure model. |
| `@DirectSqlWrite` / `RestrictedDatabaseQueries`: never call `insert*/update*/delete*/upsert*/transaction` on `SteleDatabaseQueries` directly | Route merge writes through `DatabaseWriteActor` (`actor.execute { }` or typed `saveBlock`/`savePage`). Any new write query needs a forwarding stub on `RestrictedDatabaseQueries`. |
| New table needs `MigrationRunner.all` entry (enforced by `MigrationRunnerSchemaSyncTest`); regenerate SQLDelight sources and commit `kmp/src/generated/sqldelight/` | If a merge-provenance, source-UUID map, or share-inbox table is added, both steps are mandatory. Prefer a block property over a new table to avoid this. |
| `rememberCoroutineScope` must not escape composition | Merge and share services own an internal `CoroutineScope(SupervisorJob() + Dispatchers.Default)` plus a `CoroutineExceptionHandler`, and expose `StateFlow`. The current `GraphMergeService` has no scope, which is fine. |
| Graph-scale reads bounded: no unbounded `getAllPages()`, chunk `IN` lists to 500 or fewer, no pinned snapshots | See section 4. The current snapshot design must change. |
| `PlatformDispatcher.DB` for SQL, `IO` for files and network | Markdown and SAF writes use `IO`. DB work uses `withContext(DB)`. |
| Reads use `asDbFlowList` / `asDbFlowOrNull` with `catchDbError()`; a closed DB must not crash | Critical if a second `RepositorySet` is opened and closed around a merge: collectors against a closed driver must degrade to `Left`. See `UpgradeResilienceTest`. |
| Tests: pure logic in `commonMain`/`commonTest`, property-based with kotest-property, Compose-behavior tests in `androidUnitTest`, check display via `scripts/jvm-display-check.sh` before trusting `jvmTest` UI failures | Make the block-merge function pure (inputs: two block trees, output: merged tree plus conflicts) and property-test idempotence, no-loss and no-reorder of target blocks. |
| Root-cause before loosening tests | Applies to any flaky merge test. |

## 7. Design implications (for the planning phase)

1. Write-path candidates, ranked by risk: (a) markdown-file merge into the target through `GraphWriter`-style pre-marked writes, with the DB reindexed lazily on next open, is the lowest risk for a non-active graph. It is also resilient to the single-open constraint, and SAF writes can go through `PlatformFileSystem`. (b) Opening a second `RepositorySet` is a larger refactor of `GraphManager` and has the writer-actor race above. (c) A queued inbox consumed on the next switch is simplest for share but breaks the "one step" metric.
2. The block-merge function should be pure and operate per page, so memory is bounded by one page at a time.
3. Mark a merged block as imported with a block property carrying the source UUID, so repeat merges are idempotent without a new table.
4. Gaps I could not close in this pass: how the block insert handles UUID collisions (INSERT vs REPLACE), whether `id::` round-trips through `LogseqPageSerializer`/parser, and where Android capture lives.
