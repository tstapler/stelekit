# Architecture research: Android git SAF shadow worktree

Research pass for `project_plans/android-git-saf-shadow-worktree/requirements.md`.
File:line references are exact as read this session; no line numbers are guessed.

## 1. Existing conventions this design must fit

From `CLAUDE.md` (repo root), already-enforced patterns the shadow-tree design must not violate:

- **Either/Arrow at repository boundaries.** `GitRepository`/`GitSyncService` already return
  `Either<DomainError.GitError, T>` everywhere (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`).
  Any new shadow-sync operation (SAF→shadow, shadow→SAF) that can fail must add a `DomainError`
  case, not throw or return null. `DomainError.GitError` is a `sealed interface` at
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt:69-104` — a shadow-sync
  failure needs a new case here (e.g. `ShadowSyncFailed`), following the existing
  `StaleLockFile`/`FileTooLarge` shape (data class carrying the failing path).
- **Dispatcher matrix.** All of `AndroidGitRepository`'s methods already run under
  `withContext(PlatformDispatcher.IO)` (e.g. `AndroidGitRepository.kt:56,72,104`). Shadow-tree
  I/O (mirroring files) is non-database IO, so it belongs on `PlatformDispatcher.IO` too — never
  `PlatformDispatcher.DB` (that's reserved for SQL). `ShadowFileCache.syncFromSaf` already does
  this correctly (`ShadowFileCache.kt:70`, `withContext(Dispatchers.IO)`).
- **Coroutine scope ownership.** `GitSyncService` and `GraphFileWatcher` both own private
  `CoroutineScope`s per the "never accept `rememberCoroutineScope()`" rule
  (`GitSyncService.kt:70`, `GraphFileWatcher.kt:62`). Any new shadow-sync orchestration
  (e.g. a `ShadowTreeManager`) must follow the same pattern if it needs to launch background work
  independent of a single suspend call — own its scope, guarded by a `CoroutineExceptionHandler`
  if long-lived, per the "Uncaught coroutine Throwables kill the process on Android" rule.
- **Bounded reads / no O(graph) reconciliation.** The existing SAF→shadow sync
  (`ShadowFileCache.syncFromSaf`) is already chunked per-directory using one batch SAF cursor
  (`fileModTimes` from `listFilesWithModTimes`), not per-file IPC. Any new shadow→SAF write-back
  after merge must reuse this batched-metadata pattern, not loop calling single-file SAF existence
  checks per changed file for a graph with thousands of pages.
- **`@DirectSqlWrite` / `DatabaseWriteActor`** — not directly relevant to shadow-tree file I/O
  (no new SQL tables proposed here), but the `MigrationRunner.all` sync rule would apply if the
  plan introduces a new `git_config` column (e.g. a `shadow_last_synced_at` timestamp) rather than
  in-memory/settings-only state.

No new architectural pattern is needed to satisfy these — the existing `ShadowFileCache` +
`PlatformDispatcher.IO` + `Either` conventions already anticipate exactly this kind of
mirror-and-sync work; they just need extending in the new direction.

## 2. `GraphManager` — per-graph lifecycle and where shadow-tree hooks in

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

- `GraphManager` owns one `coroutineScope` for itself (`GraphManager.kt:83`) and a fresh
  per-graph `CoroutineScope` in `activeGraphJobs[id]` created inside `switchGraph()`
  (`GraphManager.kt:620-621`), cancelled again in `switchGraph()`'s teardown of the *previous*
  graph (`GraphManager.kt:595-598`) and in `removeGraph()` (`GraphManager.kt:402`).
- `GitSyncService` is **not** owned by `GraphManager` — it's constructed in `App.kt` (Compose
  layer, see §3 below) and merely *registered* into `GraphManager` via `registerGitSyncService()`
  (`GraphManager.kt:905-907`), with teardown driven by `switchGraph()` (`GraphManager.kt:601-602`)
  and `shutdown()` (`GraphManager.kt:941-943`). This is because `GitSyncService` needs
  `GraphLoader`/`GraphWriter`, which are Compose-managed (`App.kt` comment at
  `GraphManager.kt:900-903`).
- **Implication for shadow-tree lifecycle**: a shadow working tree is graph-scoped (one shadow
  dir per `graphId`, matching `ShadowFileCache`'s existing `context.filesDir/graphs/$graphId/shadow`
  root, `ShadowFileCache.kt:23`). It should follow the *same* ownership split already established
  for git sync — not live inside `GraphManager` directly, but be **constructed alongside
  `AndroidGitRepository`/`GitSyncService` in the Compose layer** (`App.kt`, see §3), created lazily
  on first git setup for that graph and torn down:
  - on `switchGraph()` (implicitly — a new graph's `AndroidGitRepository`/shadow tree is
    reconstructed via `remember(gitRepository)` keying, mirroring how `gitConfigRepository` and
    `gitSyncService` already do in `App.kt:653-669`);
  - on `removeGraph()` (`GraphManager.kt:400-430`) — this is the one gap: `removeGraph()` already
    cleans up git credentials (`GraphManager.kt:420-427`) but has no shadow-directory deletion
    call. A shadow-tree design must add a `shadowRoot.deleteRecursively()`-equivalent here (or a
    callback hook `GraphManager` invokes), reusing `ShadowFileCache.deleteAll()`
    (`ShadowFileCache.kt:163-165`), which today is only wired to "SAF permission revoke"
    (per its doc comment) not graph removal.
  - `GraphId` is `sha256(canonicalPath)`-derived (`GraphManager.kt:315-316`); `updateGraphPath()`
    re-keys the id when a graph moves (`GraphManager.kt:454-504`, `moveGraphFilesAndCredentials`
    at 511-564). A shadow-tree design needs the same re-keying for the shadow directory (rename
    `graphs/$oldId/shadow` → `graphs/$newId/shadow`) or it will silently orphan/duplicate shadow
    state on graph path moves — `moveGraphFilesAndCredentials` is the natural extension point
    (`GraphManager.kt:511`).

## 3. `AndroidGitRepository` / `GitSyncService` — exact `repoRoot`/filesystem touch points

### Construction / DI (the actual wiring, not a factory)

There is **no `PlatformGitRepositoryFactory`** or DI abstraction today. `AndroidGitRepository` is
constructed at exactly two call sites, both hardcoded:

1. **`androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt:295-298`** — the live
   app path:
   ```kotlin
   val gitRepository = remember {
       val ctx = this@MainActivity.applicationContext
       AndroidGitRepository(pathResolver = { PlatformFileSystem.resolveSafToRealPath(it, ctx) })
   }
   ```
   `pathResolver` is wired to the `MANAGE_EXTERNAL_STORAGE`-only resolver
   (`PlatformFileSystem.kt:76-106`) — this is the exact site that must instead resolve to a
   **shadow path** when direct access isn't available (§6 below).
2. **`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt:115`** —
   `GitSyncWorker.doWork()`'s "slow path" (process was killed, `WorkManager` restarted it):
   `val gitRepository = AndroidGitRepository()` — **no `pathResolver` at all**. This path has no
   access to `PlatformFileSystem`/`ShadowFileCache` (it builds its own bare `DriverFactory` +
   `SteleDatabase` directly, `WorkManagerSyncScheduler.kt:104-116`) and today silently degrades to
   the raw `saf://` string (same failure mode as the bug this project fixes) whenever
   `MANAGE_EXTERNAL_STORAGE` isn't granted. **This is a second integration point the shadow-tree
   plan must explicitly address** — either give the worker a way to resolve/open the graph's
   existing shadow tree (read-only, since a stale/absent shadow there is a fetch-only op — see
   `GitSyncWorker.doWork()` calls `gitRepository.fetch(config)` only, never merge/push), or
   document that background WorkManager sync stays best-effort/degraded for SAF-only users
   until the app is foregrounded.

`GitSyncService` construction: `App.kt:656-669` (`gitConfigRepository` at 653-655 gates it on
`gitRepository != null`). This is where a shadow-tree manager would be threaded in alongside
`GitSyncService`'s other collaborators if pre/post-sync hooks are needed at that layer instead of
inside `AndroidGitRepository` itself (see §6 decision-logic discussion).

### `AndroidGitRepository.kt` — every method touching `config.repoRoot` / doing FS work

All of these call `resolveForJGit(path)` (`AndroidGitRepository.kt:415-429`) either directly or via
`openGit(repoRoot)` (`AndroidGitRepository.kt:431`, itself `Git.open(File(resolveForJGit(repoRoot)))`):

| Method | Line | FS touch |
|---|---|---|
| `isGitRepo` | 51-53 | `File(resolveForJGit(path), ".git").exists()` |
| `init` | 55-65 | `Git.init().setDirectory(File(resolveForJGit(repoRoot)))` |
| `clone` | 67-101 | `.setDirectory(File(resolveForJGit(localPath)))` |
| `fetch` | 103-139 | `openGit(config.repoRoot)` |
| `status` | 141-164 | `openGit(config.repoRoot)`, `addPath(config.wikiSubdir)` |
| `stageSubdir` | 166-180 | `openGit(config.repoRoot)`, `addFilepattern` |
| `commit` | 182-194 | `openGit(config.repoRoot)` |
| `merge` | 196-279 | `openGit(config.repoRoot)`; builds `ConflictFile.filePath = "${config.repoRoot}/$filePath"` (line 217) and `changedFiles` as `"${config.repoRoot}/${it.newPath}"` (line 246) |
| `push` | 281-298 | `openGit(config.repoRoot)` |
| `log` | 300-322 | `openGit(config.repoRoot)` |
| `abortMerge` | 324-338 | `openGit(config.repoRoot)` |
| `checkoutFile` | 340-362 | `openGit(config.repoRoot)`, path built by stripping `"${config.repoRoot}/"` prefix (line 353) |
| `markResolved` | 364-376 | same prefix-strip pattern (line 368) |
| `removeStaleLockFile` | 392-410 | `File(resolveForJGit(config.repoRoot), ".git/index.lock")` |

**Scope of change if `repoRoot` becomes the shadow dir**: every one of these methods is
*unchanged* at the JGit-call level — `resolveForJGit()` already centralizes path resolution
(single seam, `AndroidGitRepository.kt:414-429`), so redirecting `config.repoRoot` to a shadow
path (a real `java.io.File`-backed directory) makes `resolveForJGit()` a no-op passthrough
(`pathResolver` returns null, falls through to the already-real path at line 428). **The actual
scope of change is entirely in what `GitConfig.repoRoot` *is set to* at config-save time and in
what happens around each JGit call, not inside `AndroidGitRepository` itself.** The two places
that leak `config.repoRoot` as a *user-facing* path — `merge()`'s `ConflictFile.filePath`
construction (line 217) and `checkoutFile`/`markResolved`'s prefix-strip (lines 353, 368) — are
exactly the paths that need shadow→SAF remapping before they reach `GitSyncService`/the UI (§5).

### `GitSyncService.kt` — where shadow sync must be invoked

`GitSyncService` never touches paths directly except through `gitRepository`/`fileSystem`. The
methods that need a **pre-op SAF→shadow sync** call inserted (today: none — sync happens only via
`ShadowFileCache.syncFromSaf`, triggered by background indexing, not by git ops):

- `sync()` (`GitSyncService.kt:119-308`) — needs SAF→shadow before step 5 (commit, line 169) so
  JGit's working-tree diff sees current SAF content, and shadow→SAF after step 7's merge
  (`graphLoader.reloadFiles(...)` at line 269 already reloads into the DB, but as of today reads
  from `path.value` via `readFileDecrypted` — if `path.value` is shadow-absolute post-merge, this
  reload reads the *shadow* copy into the DB while SAF itself is still stale, an inconsistency
  that must be closed by writing shadow→SAF *before* `reloadFiles` runs, not after).
- `fetchOnly()` (`GitSyncService.kt:315-367`) — fetch alone doesn't touch the working tree, so no
  shadow sync needed (JGit fetch only updates refs/objects, no checkout).
- `commitLocalChanges()` (`GitSyncService.kt:373-397`) — needs SAF→shadow before `status()`/`stageSubdir()`/`commit()`.
- `resolveConflict()` / `resolveConflictBySide()` (`GitSyncService.kt:403-486`) — these already
  call `fileSystem.readFile`/`writeFile` (lines 416, 426) and `gitRepository.checkoutFile` — both
  the shadow tree (via JGit) and SAF (via `fileSystem`) must be written together here; today they
  write only to whatever `filePath` is (real path on Desktop). **Path-space collision risk**: if
  `filePath` is shadow-absolute (from `config.repoRoot`), `fileSystem.writeFile(filePath, ...)`
  falls through to `legacyWriteFile` (`PlatformFileSystem.kt:1002`), whose `validateLegacyPath`
  requires `canonicalPath.startsWith(homePath)` (`PlatformFileSystem.kt:1130-1139`) —
  `context.filesDir` (where the shadow tree lives, `ShadowFileCache.kt:23`) is **not** under
  `homeDir` (`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)`,
  `PlatformFileSystem.kt:21-24`). **This is a concrete blocker, not a hypothetical**: passing a
  shadow-absolute path straight into `fileSystem.readFile`/`writeFile` as currently implemented
  will fail `validateLegacyPath`'s containment check. The design must either (a) always remap
  shadow paths → SAF paths before calling `fileSystem.*`, or (b) special-case shadow-tree paths in
  `PlatformFileSystem` (extend the allowed-prefix check). Path remapping (a) is more consistent
  with keeping `PlatformFileSystem`'s SAF/legacy split unchanged and is what requirements Open
  Question 3 already flags for `ConflictResolutionScreen`.
- `applyJournalMerge()` (`GitSyncService.kt:493-535`) — same `fileSystem.writeFile` call
  (line 505), same collision risk.

## 4. `ShadowFileCache` — current lifecycle and scope of the new direction

`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFileCache.kt`

- **Documented invariant today** (doc comment, lines 12-21): *"SAF is the only write target;
  shadow is a read cache derived from SAF... callers never write to the shadow directory
  directly."* This is exactly the invariant the requirements doc's Open Question 1 asks about
  re-auditing.
- **What triggers `syncFromSaf()` today**: only `PlatformFileSystem.syncShadow(graphPath)`
  (`PlatformFileSystem.kt:848-857`), which is called from... nowhere found in `commonMain` under
  the name `syncShadow` other than the `FileSystem` interface declaration — it's a Phase-3
  background-indexing hook (per `ShadowFileCache`'s class doc, "Phase 3 background indexing").
  It is on-demand/triggered, not continuous. `invalidateStaleShadow()` (line 794) is the
  startup/first-access purge path, called once per graph session (`ShadowFileCache.isFirstAccess()`
  gate, `ShadowFileCache.kt:28-31`).
- **Two write paths into shadow already exist** beyond the read-cache role, despite the doc
  comment's "read-only" framing: `update()` (line 108, called from `PlatformFileSystem.updateShadow`
  after every successful direct-access or write-behind SAF write) and `ShadowFlushActor` writing
  shadow-derived content out to SAF (the *existing* shadow→SAF direction, but only for the
  write-behind queue's own pending pages — `ShadowFlushActor.kt:44-49`, reads `queue.getAll()` and
  flushes each path). **This means a shadow→SAF write-back mechanism already exists in the
  codebase (`ShadowFlushActor`), just scoped to the write-behind queue, not to git-merge output.**
  The new git-driven shadow→SAF direction should extend `ShadowFlushActor`'s pattern (batch,
  per-file: read shadow → `fileSystem.writeFile` to SAF → stamp shadow mtime) rather than invent a
  parallel mechanism, reusing the redaction and per-file try/catch already in
  `ShadowFlushActor.flushPage()` (`ShadowFlushActor.kt:51+`).
- **Scope of change needed**:
  1. A **git-merge-driven shadow→SAF flush** — same shape as `ShadowFlushActor` but iterating
     `mergeResult.changedFiles` (shadow-relative, from `AndroidGitRepository.merge()`, §3) instead
     of `WriteBehindQueue.getAll()`.
  2. `ShadowFileCache.deleteAll()` wired into `GraphManager.removeGraph()` (§2) and
     `moveGraphFilesAndCredentials()`-equivalent renaming for `updateGraphPath()` (§2).
  3. Concurrency between the *existing* `syncFromSaf`/write-behind-flush direction and the *new*
     merge-driven write-back direction needs a lock — both mutate the same shadow directory; JGit
     also reads/writes it directly as a working tree during `stageSubdir`/`commit`/`merge`
     checkout. `GitSyncService.sync()` already serializes against concurrent edits via
     `editLock.awaitIdle()` (`GitSyncService.kt:166`) — that existing lock is the natural point to
     also exclude `ShadowFlushActor`/write-behind flush from running concurrently with a git
     operation on the same graph's shadow tree, but today `editLock` and `WriteBehindQueue` are
     independent; this needs an explicit ordering decision in the plan phase (not just "reuse
     editLock" — write-behind flush is triggered from app-lifecycle hooks, not just editing).

## 5. Data-flow sketches

### (a) Pull (fetch + merge), user tap to SAF/DB update

```
User taps "Sync" (or periodic/WorkManager trigger)
  -> GitSyncService.sync(graphId)                                  [GitSyncService.kt:119]
     1. networkMonitor / vault checks
     2. configRepository.getConfig(graphId)  -> GitConfig{repoRoot=shadowPath, wikiSubdir}
     3. gitRepository.hasDetachedHead / removeStaleLockFile         (JGit, operates on shadow dir)
     4. graphWriter.flush(); editLock.awaitIdle()                   (drain pending SAF writes)
   [NEW] 4.5. shadow <- SAF sync (extend ShadowFileCache.syncFromSaf,
              scoped to config.wikiSubdir under repoRoot) so JGit's
              working-tree diff reflects the user's latest SAF edits
     5. commit local changes (JGit writes into shadow .git + working tree)
     6. gitRepository.fetch(config)           -> updates shadow/.git objects+refs only
     7. gitRepository.merge(config)           -> JGit checks out merged content into the
                                                  shadow working tree; conflicts reported as
                                                  ConflictFile{filePath = "$shadowRepoRoot/$path"}
   [NEW] 7.5. shadow -> SAF write-back for mergeResult.changedFiles
              (extend ShadowFlushActor's per-file read-shadow/write-SAF loop),
              BEFORE the reload below, so SAF and DB never observe a state where
              the DB has merged content but SAF is still pre-merge.
     8. graphLoader.beginGitMerge(changedFiles) -> reloadFiles(...)  [suppresses the
        5s GraphFileWatcher poll (GraphFileWatcher.kt:166) for these paths so the
        write-back in 7.5 isn't double-detected as an "external SAF change" —
        REQUIRES changedFiles to be SAF paths, not shadow paths, for this suppression
        set (gitMergeSuppressedFiles, keyed by whatever string GraphFileWatcher
        compares against fileRegistry entries, which are SAF paths on Android) to
        actually match. Today mergeResult.changedFiles are repoRoot-absolute
        (AndroidGitRepository.kt:246) — shadow-tree design MUST remap these to SAF
        paths before calling beginGitMerge/reloadFiles, or suppression silently
        no-ops and the watcher double-processes the merge's own write-back as an
        "external" conflict.]
     9. gitRepository.push(config)
  -> SyncState.Success                                              [UI observes syncState]
GraphFileWatcher's 5s poll (or SafChangeDetector-hinted rescan) independently scans
the SAF pages/journals dirs (GraphFileWatcher.kt:126-127) — with correct SAF-path
suppression in step 8, it sees the write-back as already-known-good (own-write via
FileRegistry.markWrittenByUs semantics) and does not re-emit externalFileChanges/
DiskConflict for it.
```

### (b) Commit + push, starting from a normal SAF-side edit

```
User edits a block -> BlockStateManager -> GraphWriter.saveBlock()
  -> fileSystem.writeFile(safPath, content)     [normal SAF write, PlatformFileSystem.kt:435
     or markDirty() write-behind path, line 773]
  -> graphLoader.markFileWrittenByUs(safPath)   [suppresses watcher self-detection]
     (independently: updateShadow(safPath, content) already keeps ShadowFileCache's
      READ-cache in sync per-file on every direct-access/write-behind write —
      PlatformFileSystem.kt:442, 705 — so for direct-access users the shadow is
      already fresh; for write-behind (no MANAGE_EXTERNAL_STORAGE) users the SAF
      write may not have landed yet — see WriteBehindQueue/ShadowFlushActor.)

User taps "Sync" -> GitSyncService.sync(graphId)
   [NEW] pre-op: shadow <- SAF sync for config.wikiSubdir (ensures shadow sees the
         edit above even if it arrived via write-behind and hasn't flushed to SAF
         yet — OR: sync() must first call fileSystem.flushPendingWrites() (already
         exists, PlatformFileSystem.kt:782) BEFORE the shadow<-SAF sync, so the
         write-behind queue's shadow-authoritative content is what gets diffed,
         not stale SAF content the write-behind flush hasn't caught up to yet.
         graphWriter.flush() at GitSyncService.kt:165 already flushes the DB-side
         write-behind queue — worth confirming it's the same queue as
         fileSystem.flushPendingWrites(), or both need calling.]
   status()/stageSubdir()/commit() -> JGit commits the shadow tree's current state
   fetch()/merge() (as in flow (a), possibly no-op if remote unchanged)
   push() -> commit lands on the remote
```

### Integration points at risk of double-detection / double-processing

1. **`GraphFileWatcher`'s 5s poll vs. shadow→SAF write-back** (flow (a) step 7.5/8) — resolved by
   reusing `beginGitMerge`/`endGitMerge` sticky suppression, contingent on remapping shadow paths
   to SAF paths first (see above — this is the single highest-risk correctness gap identified).
2. **`SafChangeDetector`'s native hint** (`SafChangeDetector.kt`, `FileObserver`/`ContentObserver`)
   — fires `onExternalChange()` as a bare signal with no path info
   (`GraphFileWatcher.kt:135-137`, `newScheduler.hint()`). A shadow→SAF write-back necessarily
   touches real SAF files (direct-access path) or SAF documents (write-behind path), which will
   trigger `FileObserver`/`ContentObserver` the same as any other SAF write. This is naturally
   absorbed by the same `beginGitMerge` suppression as (1) since the hint only causes a rescan, and
   the rescan is what's suppressed — no separate fix needed if (1) is done correctly.
3. **`WriteBehindQueue`/`ShadowFlushActor` running concurrently with a git merge** — flagged in §4;
   both mutate the same shadow directory tree. Needs explicit mutual exclusion in the plan (e.g. a
   per-graph `Mutex` shared between `GitSyncService.sync()` and
   `PlatformFileSystem.flushPendingWrites()`/write-behind's background trigger), not just
   `editLock` (which only guards active block editing, not the write-behind flush timer).
4. **`ConflictResolutionScreen`/`resolveConflict()`/`resolveConflictBySide()`** — per §3, these
   write to `fileSystem.writeFile(filePath, ...)` using shadow-repoRoot-absolute paths, which is
   both (a) semantically wrong (user is editing what they believe is their SAF file) and (b) will
   hit `validateLegacyPath`'s containment check and fail outright on Android once `repoRoot` is a
   shadow path. This needs the shadow→SAF path remap applied consistently across
   `resolveConflict`, `resolveConflictBySide`, and `applyJournalMerge` — not just the merge/pull
   flow.

## 6. Where the "which git path" decision logic belongs

**No existing factory/strategy abstraction for `GitRepository` selection exists today** — confirmed
by the two hardcoded construction sites in §3 (`MainActivity.kt:295-298`,
`WorkManagerSyncScheduler.kt:115`). The closest existing precedent for *this kind of* runtime
capability branching is **`PlatformFileSystem.isDirectAccess()`**
(`PlatformFileSystem.kt:279-282`, a private `Boolean` check on `Environment.isExternalStorageManager()`)
used inline at the top of nearly every `FileSystem` method (`readFile`, `writeFile`, `listFiles`,
etc. — e.g. lines 342, 399, 438) to decide direct-`java.io.File` vs. SAF-document-API code paths,
with `resolveToRealPath()` (line 294) as the actual resolver. That is exactly the shape of
decision requirements Open Question 6 asks about, already established in this codebase — it is a
**private capability check + resolver pair inlined at each call site**, not a separate
strategy/factory class.

Two viable placements, in order of fit with existing precedent:

1. **Extend the existing `pathResolver: (String) -> String?` seam on `AndroidGitRepository`**
   (`AndroidGitRepository.kt:41`, already used exactly this way for the
   `MANAGE_EXTERNAL_STORAGE` fast path). The resolver closure built in `MainActivity.kt:295-298`
   becomes: try `PlatformFileSystem.resolveSafToRealPath()` first (direct access, zero
   shadow-mirror overhead — preserves the "strictly better when available" requirement from the
   rejected-options list), and only if that returns null, fall back to
   resolving/creating the graph's shadow directory path. This keeps `AndroidGitRepository` itself
   completely unaware of *why* a path resolved the way it did — consistent with
   `resolveForJGit()`'s current single-responsibility ("resolve for JGit's File API, don't ask
   why"), and requires zero changes to `AndroidGitRepository`'s internals beyond what §3 already
   found (none, since `resolveForJGit` already no-ops through any resolver).
2. **A capability check function analogous to `isDirectAccess()`**, e.g.
   `PlatformFileSystem.gitAccessMode(graphPath): { DIRECT, SHADOW_MIRROR }`, called once at
   `GitConfig` construction/save time (when the user sets up Git Sync) to decide what `repoRoot`
   value gets persisted into the `git_config` DB row in the first place — rather than
   re-decided on every JGit call. This is likely the *more correct* placement because `repoRoot`
   is persisted (`SqlDelightGitConfigRepository`, referenced at `GraphManager.kt:927`) and
   re-read on every sync (`configRepository.getConfig(graphId)`,
   `GitSyncService.kt:139,332,375,407,461,498,543`) — deciding the mode once at setup time (with
   a migration/re-detection path if `MANAGE_EXTERNAL_STORAGE` is granted *after* Git Sync was
   already configured in shadow-mirror mode) avoids re-evaluating `isDirectAccess()` inside the
   hot JGit-call path and makes the mode explicit/inspectable (e.g. surfaced in Git Sync settings
   UI) rather than implicit in a resolver closure's fallback order.

Desktop's `JvmGitRepository` (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/git/JvmGitRepository.kt:33-47`)
has no `pathResolver`/shadow concept at all and must stay untouched — `repoRoot` is always a real
path there, matching the requirement that Desktop see zero behavior change.

**Recommendation for the plan phase**: combine both — persist the decided mode/shadow-root in
`GitConfig` (or derive it deterministically from `graphId` + a `hasDirectAccess()` check so it
needs no new persisted field), but implement the actual resolution via the existing
`pathResolver` seam so `AndroidGitRepository` needs no code changes. This satisfies Open Question
6 without inventing a new abstraction layer the codebase doesn't already have a precedent for.

## Summary of concrete integration points (file:line)

- `MainActivity.kt:295-298` — sole live construction site of `AndroidGitRepository`; the
  `pathResolver` closure is where shadow-fallback resolution plugs in.
- `WorkManagerSyncScheduler.kt:115` — second, currently-unresolved construction site (background
  fetch after process death); needs its own resolution story or documented degraded behavior.
- `AndroidGitRepository.kt:217,246,353,368` — the four places `config.repoRoot` leaks into a
  path string handed back to callers (`ConflictFile.filePath`, `changedFiles`, checkout/resolve
  path stripping) — all need shadow→SAF remap before reaching `GitSyncService`/UI.
- `GitSyncService.kt:165-166` (`graphWriter.flush()` + `editLock.awaitIdle()`) — natural insertion
  point for a pre-op SAF→shadow sync call.
- `GitSyncService.kt:266-272` (`beginGitMerge`/`reloadFiles`) — insertion point for shadow→SAF
  write-back, which must happen *before* this block, using SAF-remapped paths for the
  suppression set to actually match `GraphFileWatcher`'s comparison keys.
- `GitSyncService.kt:416,426,505` (`fileSystem.readFile`/`writeFile` in conflict-resolution paths)
  — will hit `PlatformFileSystem.validateLegacyPath`'s `homeDir`-containment check
  (`PlatformFileSystem.kt:1130-1139`) and fail if handed a shadow-absolute path unremapped.
- `GraphManager.kt:400-430` (`removeGraph`) and `:511-564` (`moveGraphFilesAndCredentials`) — need
  shadow-directory delete/rename hooks analogous to the existing DB-file and credential handling.
- `ShadowFlushActor.kt` — existing shadow→SAF write-back mechanism (currently scoped to the
  write-behind queue only) that the new git-merge-driven write-back should extend rather than
  duplicate.
