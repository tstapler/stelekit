# Architecture Research: dev-console-git-sync-fix

Builds on `git-sync-resilience/research/architecture.md` (shared `runGitTransportOp*` in `jvmCommonMain`, Extend-as-is disposition), `android-git-saf-shadow-worktree/research/architecture.md` (shadow tree, write-back before reload) and `git-smart-sync/research/architecture.md`. Paths below are under `kmp/src/` unless noted. Labels: VERIFIED = I opened the code (path:line given); INFERRED = reasoning, needs device evidence.

---

## 0. Headline findings (read these first)

1. **Sync hypothesis is mechanically sound, and there is a second, independent gap.** (VERIFIED)
   - `doFetch` returns `FetchResult(false, 0)` whenever `repo.resolve("origin/<remoteBranch>")` is null (`androidMain/.../AndroidGitRepository.kt:206-207`; JVM same at `jvmMain/.../JvmGitRepository.kt:193`). `GitSyncService.sync` then skips merge (`if (fetchResult.hasRemoteChanges)`, `GitSyncService.kt:~346`), pushes, and records `Success(…, remoteCommitsMerged = 0)`.
   - `doMerge` already treats the same unresolved ref as an error ("Remote ref not found", `AndroidGitRepository.kt:275-276`). Fetch and merge disagree about whether it is an error; the fetch side is the bug.
   - **`remoteBranch` has no detection anywhere.** Defaults: `GitConfig.remoteBranch = "main"` (`git/model/GitConfig.kt:15`), DB column default `'main'` (`SteleDatabase.sq:972`, `MigrationRunner.kt:376`), wizard text field initial `"main"` (`ui/screens/git/GitSetupScreen.kt:185`; field is a free-text `OutlinedTextField`, `GitSetupStep4Branch.kt:35`). A grep for `symref|defaultBranch|detectDefault` finds nothing outside code comments. `CloneCommand` is not given a branch, so it checks out the remote HEAD (`master`) while config says `main`.
   - **Push uses different branch semantics than fetch.** `doPush` sets only the remote name, no refspec (`AndroidGitRepository.kt:347-353`). It pushes by the local branch/JGit default, whereas fetch/merge use `config.remoteBranch`. With a stale `main` setting, local commits still push to `master`, which is why the app looks "healthy" while never pulling.
2. **Scheduled/background sync never pulls.** (VERIFIED) `GitSyncWorker` fast path calls `service.fetchOnly` (`WorkManagerSyncScheduler.kt:143`); slow path calls `gitRepository.fetch` only (`:213-217`); `DesktopSyncScheduler` only runs an injected `onTick`. `fetchOnly` ends in `SyncState.MergeAvailable` (a badge, `SyncStatusBadge.kt:151`). The only caller of full `sync()` is the sidebar button path `GitSyncCoordinator.triggerSync` (`GraphContentLeftSidebar.kt:169`). **Requirements metric 1 ("a scheduled sync pulls commits … within one sync interval") is not achievable by fixing the branch alone.** It needs a product decision: auto-merge on `MergeAvailable` (fast-forward-safe only?) or keep manual pull and reword the metric. Put this in the open questions.
3. **"1542 journals on disk, 35 in DB" is mostly by design, not a stuck indexer.** (VERIFIED) Warm and cold load call `loadJournalsImmediate` (10 newest) then `loadRemainingJournals(journalsDir, 10, 30 - 10)` (`GraphLoader.kt:1091-1100`, `FileRegistry.remainingJournals` at `FileRegistry.kt:76`). Only the newest ~30 journal files are ever registered. `indexRemainingPages` only upgrades rows that already exist as METADATA_ONLY (`getUnloadedPages`, `GraphLoader.kt:786`); it cannot discover files that have no DB row. Journals 31..1578 never enter the DB unless something calls `reloadFiles`/`loadFullPage` on them. The "9414 pages awaiting full index" is the separate `pages/` METADATA_ONLY set from `loadDirectory(pagesDir)`. Whether that drain completes is open (see §3).
   - The **recent** missing journals (2026-10-09/10) are explained by finding 1/2: they never reached disk (not a DB issue). After a merge, `reloadFiles(changedFiles)` parses them FULL (`GitSyncService.kt:359`, `GraphLoader.kt:943-947`).

---

## 1. Dev console architecture

### 1.1 What exists to build on (VERIFIED)

| Need | Existing seam |
|---|---|
| Composition root for diagnostics | `GraphDiagnosticsCollector(graphManager, fileSystem, repos, settings, gitConfigRepository?, gitRepository?)` built in `remember{}` in `ui/GraphContentCameraCapture.kt:50`. This is already a "constructor-injected capabilities" object, not a god-object. Template for the console. |
| Existing in-app "command" notion | `editor/commands/CommandRegistry.kt` (editor slash/palette commands) and `command/Command.kt` (undoable `Command<T>`). Neither fits: they are editor-scoped, UI-bound and undo-centric. **Do not reuse the names**; use `console/` package + `ConsoleCommand` to avoid collision. |
| Screens | `Screen` sealed class in `ui/AppState.kt:36` (`Screen.Logs` at `:53`), routed by `ScreenRouter.kt:228`; Logs entry points in `TopBar.kt:206/294`, `Sidebar.kt:319`. Add `Screen.Console` the same way. |
| Settings gate | `platform/Settings.kt` (`getBoolean/putBoolean/containsKey`). No developer-mode flag exists (grep empty). Add `dev_console_enabled`, default false. |
| Log source | `LogManager.logs` StateFlow (used by `GraphDiagnostics.kt:216`). |
| FS | `platform/FileSystem.kt` (`readFile`, `listFiles`, `getFileSize`, `listFilesRecursiveWithModTimes`, `readFileBytes`, SAF-aware). `fs` commands must go through it, not `java.io.File`, so SAF graphs work. |
| Clipboard / share | `ui/PlatformClipboardProvider.kt` (`rememberClipboardProvider`), `ui/PlatformShareProvider.kt` (`rememberShareProvider`). Export = these two plus `FileSystem.pickSaveFileAsync`. |
| Git | `GitRepository` (all ops take `GitConfig`; has `describeRefs(config)` default, uncommitted work) and `GitConfigRepository`; `GraphManager.activeGitSyncService`. |

### 1.2 Proposed structure (no god-object)

```
commonMain/.../console/
  ConsoleCommand.kt        // interface + ConsoleContext + ConsoleOutput
  ConsoleRegistry.kt       // name/alias -> command; ConsoleCommand list is injected, not discovered
  ConsoleSession.kt        // owns scope, history, scrollback, running Job, confirm policy
  ConsoleRedactor.kt       // URL userinfo, token/key patterns, applied to ALL output + logs
  commands/{Sql,Git,Fs,Settings,Logs,Graph,Diag,Sh}Command.kt   // one file per family
  ui/ConsoleScreen.kt      // Compose; collects session StateFlows only
commonMain platform seams (expect/interface):
  ProcessRunner            // jvmMain + androidMain actual; wasm/ios = Unsupported
  DbMaintenance            // backup(), rawDriver access; see 1.4
```

**Command contract** (small, capability-scoped):

```kotlin
interface ConsoleCommand {
    val name: String; val summary: String
    val risk: Risk            // READ, WRITE, EXEC  -> drives confirm policy
    val requires: Set<Capability>   // GIT, SQL, FS, PROCESS ... -> hidden/disabled when absent
    suspend fun run(args: List<String>, ctx: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit>
}
```

**How commands get GraphManager/RepositorySet/GitRepository/FileSystem/Settings without a god-object:**
- `ConsoleContext` is a *narrow, immutable bundle of nullable capabilities*, mirroring how `GraphDiagnosticsCollector` already takes `gitRepository: GitRepository? = null`: `val fs: FileSystem`, `val settings: Settings`, `val graphs: ActiveGraphView?`, `val git: GitConsoleView?`, `val sql: SqlConsoleView?`, `val process: ProcessRunner?`, `val clock`, `val redactor`. Each `*View` is a 3-6 method interface defined next to its command family; the composition root adapts `GraphManager`/`RepositorySet`/`GitRepository` to them. Commands never see `GraphManager` itself. Consequences: unit-testable with fakes (precedent: `FakeFileSystem`), Wasm builds simply pass `null` for git/process, and a new probe is a one-file addition (register it in the list passed to `ConsoleRegistry`).
- The active graph is re-resolved **per command run** (`graphs.current()`), because `GraphManager` is single-open and `activeRepositorySet` changes on switch (CLAUDE.md "Cross-graph writes": read readiness from `readyGraph`, never `activeGraphId`).
- Git commands use `GitConfigRepository.getConfig(graphId)` per run (not a captured `GitConfig`), so `git set-branch` is observed immediately.

### 1.3 Raw SQL: reads and writes (VERIFIED seams, INFERRED design)

- **Raw driver is not exposed today.** `RepositoryFactoryImpl` keeps `activeDriver`/`wrappedDriver` private (`RepositoryFactory.kt:117-119`); `RepositorySet` has no driver field; `RestrictedDatabaseQueries` takes an optional `driver` but does not expose it. Precedents for raw SQL exist (`QueryPlanRepository`, `MigrationRunner` via `driver.executeQuery/execute`). **Seam to add:** an `internal`/`@DirectSqlWrite`-gated `SqlConsoleView` implemented in the `db` package and put on `RepositorySet` (nullable, like `perfExporter`). Do not widen `RepositoryFactory`.
- **Reads** (`sql SELECT/PRAGMA/EXPLAIN`): classify with `SqliteStatementAnalyzer.leadingKeywords` (already handles comments and string literals, `db/SqliteStatementAnalyzer.kt`); run on `PlatformDispatcher.DB`; wrap with a hard row cap (500, `LIMIT` injection is unsafe for arbitrary SQL, so stream the cursor and stop at cap, emit "truncated"); apply a statement timeout via coroutine cancellation (`withTimeout`) because SQLite interrupt is driver-specific. Reject multi-statement input for READ mode.
- **Writes** must go through `DatabaseWriteActor.execute(priority) { ... }` (`DatabaseWriteActor.kt:761`). That gives serialization with `GraphLoader` writes and, importantly, `processExecute` emits `WILDCARD_PAGE_UUID` invalidation **after** `op()` completes (`DatabaseWriteActor.kt:~395`), which is the fix for the ordering bug in memory; a raw write inside `execute` inherits it. Wrap the SQL in `@OptIn(DirectSqlWrite::class)` in exactly one class (`SqlConsoleView` impl). Return the changed-row count; log at WARN with the statement.
- **Markdown-on-disk consistency.** The DB is a cache of the markdown files (`GraphLoader` parse → repositories; `GraphWriter` is the only DB→disk path, and `GraphLoader.reloadFiles` is the disk→DB path). A raw `UPDATE blocks …` therefore creates drift that the next file reload silently reverts, or that the watcher never reconciles because the file did not change. Policy for the console:
  - Tag every write result "DB-only: not written to markdown; will be overwritten on next reload of that page".
  - Offer a *safe* verb for the common repair, `graph reload <path|date|--missing-journals>`, which calls `graphLoader.reloadFiles` (disk wins). Treat "make DB match disk" as the supported direction; "make disk match DB" is not offered through raw SQL.
  - Hard-deny list in WRITE mode (confirm cannot override): DDL (`CREATE/DROP/ALTER`), `ATTACH`, `PRAGMA writable_schema`, `PRAGMA journal_mode/foreign_keys`, writes to `git_config` credentials columns, `schema_migrations`/`migrations` bookkeeping, and `VACUUM`. `MigrationRunnerSchemaSyncTest` relies on `MigrationRunner.all`; raw DDL would bypass it.
  - Writes to `git_config` are legitimate (`UPDATE git_config SET remote_branch='master'` is the on-device fix for finding 1), but give them a typed twin `git set-branch` that goes through `GitConfigRepository.saveConfig`, which is the audited write path (`SqlDelightGitConfigRepository.kt:62`).
- **Backup before writes.** First WRITE in a session (or per statement when `--backup` is on): `VACUUM INTO '<appdir>/console-backups/<graphId>-<ts>.db'` through the same driver *outside* a transaction, after a write-actor drain (`execute{}` ensures no interleaved write). Fall back to `PRAGMA wal_checkpoint(TRUNCATE)` + file copy via `FileSystem.readFileBytes/writeFileBytes` where `VACUUM INTO` is unsupported (INFERRED: Android's bundled SQLite version is not verified here; `LibsqlSupport.kt` exists, so check which engine each platform ships). Keep the last N=3, report the path, never include it in diagnostics export. `DriverFactory.getDatabaseDirectory()` (`db/DriverFactory.kt:28`) gives the location.

### 1.4 Output streaming and cancellation

- `ConsoleOutput` is a sink (`line(String)`, `table(...)`, `progress(...)`) that writes to a bounded ring buffer in `ConsoleSession` (cap by lines **and** bytes, e.g. 5k lines / 2 MB, drop-oldest with a visible marker) exposed as `StateFlow<List<Line>>` conflated/batched at ~60 ms. This addresses the "Compose scrollback performance" risk: render via `LazyColumn` with stable keys, never one giant `Text`.
- Each run is one `Job` in a session-owned scope: `CoroutineScope(SupervisorJob() + PlatformDispatcher.Default + CoroutineExceptionHandler{ ... })`. **This scope is created inside `ConsoleSession`, never from `rememberCoroutineScope()`** (CLAUDE.md scope-ownership rule) and carries a `CoroutineExceptionHandler` (CLAUDE.md: an uncaught OOM kills the process on Android). Commands must `catch (CancellationException) { throw }` and `catch (Throwable)` at the run boundary → `ConsoleError`.
- Cancel = `job.cancel()`. JGit is blocking and ignores coroutine cancellation: run it via `runInterruptible`/`ensureActive()` between steps and rely on `GIT_TRANSPORT_TIMEOUT_SECONDS` (300 s, `GitOperationSupport.kt`) as the backstop. Say so in the UI ("cancelling; waiting on network call to time out"). For `sh`, cancel = `Process.destroyForcibly()` in a `finally`.
- Audit: one INFO line per command (name, duration, rows/lines, outcome), arguments through `ConsoleRedactor`; writes at WARN with the statement (requirement Observability).

### 1.5 `sh` on Android (rabbit hole decision input)

- Recommendation: **in-process interpreter first, `Runtime.exec` as a gated opt-in.** The in-process families (`sql`, `git`, `fs`, `settings`, `logs`, `graph`, `diag`) cover every stated use case without a shell. `ProcessRunner` (`Runtime.exec`/`ProcessBuilder`, no `/bin/sh -c` assumption, argv list only, no string concatenation, working dir limited to app files) is an `actual` on JVM + Android and `Unsupported` on wasm/iOS. On Android it runs as the app uid with the system toybox PATH and cannot see the SAF tree; document that in `sh --help`. Second confirm per session, per requirements.
- `ConsoleRedactor` also scrubs env on exec (do not pass the app environment; pass a minimal one).

### 1.6 Security notes specific to the console

- Developer-mode gate hides the entry point; the *command layer* also checks the flag so a stale Screen route cannot run commands.
- Never print: `GitAuth` token/passphrase providers, `*_token_key` values resolved from the vault (`VaultCredentialStore`), OAuth tokens, remote URL userinfo. `describeRefs`/`GitRefDiagnostics` already strip userinfo; reuse that redactor instead of a second implementation. `PatLeakageAuditTest` (`jvmTest/.../git/PatLeakageAuditTest.kt`) is the existing precedent; add a console variant.
- History stays in memory (`ConsoleSession`), not `Settings`, per requirements.

---

## 2. Git-sync fix: trace and design

### 2.1 Call trace (VERIFIED)

```
Sidebar button -> StelekitViewModel.triggerSync -> GitSyncCoordinator.triggerSync (:118)
  -> GitSyncService.sync(graphId)                         [commonMain, GitSyncService.kt:202]
     offline? -> vault locked? -> getConfig -> hasDetachedHead -> removeStaleLockFile
     -> graphWriter.flush + editLock.awaitIdle -> status -> stageSubdir + commit (if local changes)
     -> gitRepository.fetch(config)  --> Android/JvmGitRepository.doFetch
          git fetch <remoteName>   (default refspec from .git/config)
          remoteRef = resolve("<remoteName>/<remoteBranch>")   <-- null => FetchResult(false,0)   *** BUG ***
     -> if hasRemoteChanges: merge(config) -> doMerge (resolve again; null => FetchFailed "Remote ref not found")
          merge NO_FF RECURSIVE; changedFiles = diff(HEAD^1..HEAD) filtered by wikiSubdir + "/" prefix
          Android only: SAF write-back via flushAndCheckConcurrentEdit before returning
     -> beginGitMerge(changedFiles); graphLoader.reloadFiles(changedFiles); endGitMerge
     -> push(config) -> doPush (no refspec; pushes current branch, independent of remoteBranch)
     -> Success(localCommitsMade, remoteCommitsMerged = fetchResult.remoteCommitCount)
```

`fetchOnly` (`GitSyncService.kt:410`) is the same minus commit/merge/push; used by WorkManager and desktop timers.

### 2.2 `remoteBranch` provenance

| Source | Value | Evidence |
|---|---|---|
| Model default | `"main"` | `GitConfig.kt:15` |
| DB column default | `'main'` | `SteleDatabase.sq:972`, `MigrationRunner.kt:376` |
| Wizard state | `existingConfig?.remoteBranch ?: "main"`, user-editable free text, not populated from the remote | `GitSetupScreen.kt:185`, `GitSetupStep4Branch.kt:35` |
| Persist | `buildConfig(... form.remoteBranch ...)` then `saveConfig` | `GitSetupScreenSaveLogic.kt:182-190`, `SqlDelightGitConfigRepository.kt:62` |
| Worker read | `Git_config.toGitConfig()` copies `remote_branch` verbatim | `WorkManagerSyncScheduler.kt:230` |
| Clone | `CloneCommand` with no `setBranch`; local branch = remote HEAD | `AndroidGitRepository.clone` (`:98-109` per prior research) |
| `init()` | `setInitialBranch("main")` (consistent with the default, only correct for non-cloned repos) | `AndroidGitRepository.kt:76` |

So a clone of a `master`-default repo with the wizard left at "main" produces exactly the observed state. `GitHostAdapter.kt:102` also consumes `config.remoteBranch` (Wasm/API push path), so a wrong value there is a separate failure.

### 2.3 Fix design (for the plan, not implemented)

1. **Make "unresolved ref after fetch" a typed error** in the shared layer, not twice: add `GitError.RemoteBranchMissing(remote, branch, available: List<String>)` and a helper in `jvmCommonMain` (`resolveRemoteTrackingRef(git, config)` returning `Either`), used by both `doFetch` and `doMerge` on Android and JVM. Include the available branches from the fetch result's tracking updates or `refs/remotes/<remote>/*`. This removes the Android/JVM `doFetch` copy-paste (they are byte-identical, `JvmGitRepository.kt:193` vs `AndroidGitRepository.kt:206`) at the same time. Log WARN with remote, configured branch, available list (requirements Observability).
2. **Detect the remote default branch** with `lsRemote()` + symref `HEAD` (JGit exposes `Ref` for `HEAD` whose `getTarget().getName()` is `refs/heads/<default>` when the server advertises symrefs; INFERRED, verify against GitHub in a test) in `GitOperationSupport.kt` next to `testRemoteViaLsRemote`. Use at: (a) wizard Step 1 `testRemote` success to prefill; (b) after clone, set `remoteBranch` from the *checked-out local branch* (authoritative, no network), (c) on `RemoteBranchMissing`.
3. **Config repair**: when the configured branch is missing and exactly one candidate is the remote default/current local branch, surface a confirm UI (requirement: auto-correct is confirmed). Provide the same through `git set-branch` in the console. Migration for existing rows is a data fix, not a schema change: do it lazily on first `RemoteBranchMissing` (no new `MigrationRunner` table; additive per requirements Risk Control).
4. **Push alignment**: give `doPush` an explicit refspec `refs/heads/<localBranch>:refs/heads/<remoteBranch>` or fail with the same typed error if the local branch differs from the configured one; today fetch/merge/push can silently use different branches (finding 1).
5. **Regression tests (real temp JGit repos, `jvmTest`)**: unresolved ref returns `Left(RemoteBranchMissing)` (must fail on current code: current code returns `Right(false,0)`); remote default `master`, config `main`; stale config after remote rename; clone sets branch from HEAD; push refspec alignment. Existing harness: `JvmGitRepositoryTest.kt` (844 lines), `GitSyncServiceJvmFixtures.kt`. Android parity comes from the shared helper (the Android class cannot be exercised in `jvmTest`; the prior research notes Robolectric lacks `fts5`, so test the helper, not the class).

### 2.4 Other paths the requirements asked me to verify

- **wikiSubdir filtering (VERIFIED)**: `wikiSubdirFilteredChangedPaths` keeps paths starting with `"<wikiSubdir>/"` (`AndroidGitRepository.kt:315-322`), then re-prefixes with `config.repoRoot` (`toUserFacingPaths`, `:324-331`). With `wikiSubdir = "logseq"` the files reload at `<repoRoot>/logseq/journals/…`, matching `GraphManager.updateEffectivePathFromGitConfig` (`GraphManager.kt:1274-1287`, `effectivePath = root/wikiSubdir`). Parsing keys off `"/journals/"` in the path (`GraphLoader.kt:1385`), so it classifies correctly. No bug found in this path.
- **`computeChangedGitRelativePaths` limits (VERIFIED, `GitMergeDiff.kt`)**: diffs `HEAD^1..HEAD`; correct for the NO_FF merge commit, but (a) for `ALREADY_UP_TO_DATE`/fast-forward results HEAD^1 is an unrelated earlier commit, so it reports a stale diff; (b) deleted files appear as `newPath = /dev/null`, which is neither under the subdir prefix nor deletable by `reloadFiles`. Not the cause of the reported symptom; list as a follow-up (remote-deleted journals/pages stay in the DB).
- **`reloadFiles` silently skips unreadable paths** (`GraphLoader.kt:944-946`, `?: continue`): a SAF shadow write-back race would drop a file with no log. Add a WARN (cheap, in scope for "no silent no-op").
- **SAF shadow worktree (VERIFIED structure, INFERRED relevance)**: `shadowWorktreeFor` returns null unless `repoRoot` starts with `saf://` and no direct path resolves (`AndroidGitRepositoryShadow.kt:41-50`). The owner's active graph is described as a *cloned* repo (app-owned storage, `app-owned-storage-clone` plan), so it takes the non-shadow path and none of the SAF write-back runs. The two `personal-wiki` SAF registry entries do use the shadow tree; they are out of scope per requirements, but the console `git` family must call `shadow.resolveForJGit` through `GitRepository` (not open `.git` directly) so it works for both.
- **Shallow clone interaction**: `doFetch` has no unshallow step; `hasRemoteDivergedSinceShallowClone` (`GitOperationSupport.kt:438-452`) also resolves `<remote>/<remoteBranch>` and then compares to `refs/heads/<remoteBranch>` from `ls-remote`. Both silently degrade to "diverged = true" on a wrong branch; they inherit the same root cause and need no separate fix. Open Question 2 is therefore largely answered: a shallow clone does not by itself explain `remoteCommitsMerged = 0`; a wrong branch does. Keep the device `git refs` probe to confirm.
- **`GitSyncService.sync` counts**: `remoteCommitsMerged = fetchResult.remoteCommitCount`, computed by a best-effort count (`countRemoteCommitsBestEffort`) that is `0` if `headBefore == null`; a shallow clone may undercount. UI must key off merge occurred, not the count (metric 1 uses `> 0`; acceptable, note the caveat).

---

## 3. Journal indexing

- **Cap on registered journals (VERIFIED)**: `loadJournalsImmediate(count=10)` + `loadRemainingJournals(skip=10, take=30-10)`. Everything older stays disk-only. `JournalsViewModel` pages `journalService.getJournalPages(totalVisibleCount, 0)` over the **DB** (`JournalsViewModel.kt:~103-105`), so a journal older than the newest ~30 cannot appear in the journal list, only via search-by-name/page navigation (which falls to `resolvePageFilePath`, `GraphLoader.kt:497-507`). This is the likely explanation for "35 DB journals vs 1578 on disk" (35 = ~30 + a few in-app created rows, INFERRED). Decision needed (Open Q): is the 30-cap an intended lazy-load, with older journals loaded on scroll, or a gap? The requirement "last 14 days of journals on disk == in DB" is satisfied by the existing cap *once the files are on disk*; the 1542-journal "diff" in the console should therefore be reported as `recent (14d) missing` vs `older (not loaded by design)`, not as one number.
- **Does `indexRemainingPages` complete? (INFERRED, evidence needed)** The drain loop is bounded and terminating (`GraphLoader.kt:777-841`). Reasons it may not *start* or may stall:
  1. It is launched only from `onFullyLoaded` (`StelekitViewModel.kt:~724`). On warm start, `onFullyLoaded` is called only at the end of the warm reconcile (`GraphLoader.kt:~662`); if the reconcile throws (caught as `Throwable`, logged, and `onFullyLoaded` **not** invoked, `:~672-681`) indexing never starts and `isFullyLoaded` stays false. Log signature: `Warm reconcile failed — …`.
  2. `backgroundIndexJob` is cancelled by `cancelBackgroundWork()` on `onTrimMemory`; nothing restarts it. Device log signature to grep: `Background indexing complete.` vs `Background indexing failed`.
  3. Pages skipped as "active edit session" or with no `filePath`/`resolvePageFilePath == null` are counted in `attempted` and never retried this run (`:804-826`), so they remain in `countUnloadedPages()` permanently, so "9414 awaiting" can include permanently-unindexable rows.
  4. Observed `batchDeleteBlocks 5.2 s` / `processChunk 9.5 s` are slow, not stuck; at chunks of 10 pages and ~9.4k pages that is ~940 chunks, so completion in minutes-to-hours on a phone is plausible without being a bug. Console probe `graph index-status` (countUnloadedPages, backgroundIndexJob active?, last-complete timestamp) answers this without a rebuild.
- **Console tie-in**: `graph reindex` = call `graphLoader.indexRemainingPages` under the console's own cancellable job (it already sets `backgroundIndexJob` to the caller's `Job`, so cancel works); `graph reload --missing-journals [--days N]` = list journal files via `FileSystem`, diff against `getJournalPagesByDates(chunk≤500)` (existing bounded API, `GraphLoader` uses it at `:1231-1253`), `reloadFiles` the missing ones. Both stay within the bounded-read rule.

---

## 4. Tech Debt Disposition

Hotspot inputs (VERIFIED, `wc -l` and `git log --since='6 months ago'` just now; no CodeScene-style temporal coupling was run, so ranks are size × churn only):

| Area | Lines | Commits (6 mo) | Disposition | Reason |
|---|---|---|---|---|
| `git/GitSyncService.kt` (+ `SyncState`) | 687 | 16 | **Extend as-is** | The pipeline is a long but linear function and already has the right error edges. The fix is a new `GitError` that the existing `fetch` Left-branch already routes to `SyncState.Error`. Do not refactor now. If auto-merge-on-`MergeAvailable` is chosen, add it in `GitSyncCoordinator`, not inside `sync()`. |
| `AndroidGitRepository.kt` / `JvmGitRepository.kt` `doFetch`/`doMerge`/`doPush` | 570 / 471 | 27 / 13 | **Isolate via seam** (small refactor-first) | Highest churn in the area, and `doFetch` is a byte-level duplicate. Introduce the shared `resolveRemoteTrackingRef` + `RemoteBranchMissing` in `jvmCommonMain/GitOperationSupport.kt` (521 lines, 2 commits: stable, already the sanctioned sharing point per git-sync-resilience §1a) and make both platforms call it. Do not merge the two classes (auth/shadow differences are legitimate). |
| `GitOperationSupport.kt` | 521 | 2 | **Extend as-is** | Stable; add default-branch detection beside `testRemoteViaLsRemote`. Near the 500-line guideline: put new helpers in `GitRefDiagnostics.kt`/a sibling file, not here. |
| `ui/screens/git/GitSetup*` | n/a | n/a | **Extend as-is** | Add prefill from detection and a branch dropdown; no structural change. |
| `db/GraphLoader.kt` | 1970 | 63 | **Isolate via seam** (honest edit list) | Hotspot (size and churn). New journal lazy-loading lives in `db/JournalLazyLoader.kt`; `GraphLoader` gains only: two one-line delegating overrides of the port methods, one WARN in `reloadFiles`, a try/finally on the reconcile catch, and one defaulted `onDegraded` parameter on `loadGraphProgressive`. Indexing restart/status state lives in `BackgroundIndexSupervisor`. (Corrected in Phase 3 repair: an earlier version said "do not touch internals" while the stories added logic inside the class.) |
| `db/GraphManager.kt` | 1601 | 54 | **Isolate via seam** | Console reads it through an `ActiveGraphView` adapter written outside the class. No additions to the class. |
| `ui/StelekitViewModel.kt` | 2358 | 91 | **Isolate via seam** | Highest churn in repo. Console gets its own `ConsoleSession` and composition root; do not add console state to the ViewModel or `AppState` beyond a `Screen.Console` route. |
| `diagnostics/GraphDiagnostics.kt` | 252 | 3 | **Extend as-is**, then wrap | Becomes the `diag` command's implementation (`collect()` is already side-effect-free and bounded). Commit the pending Git section first (requirements In-Scope item 1). |
| `DatabaseWriteActor` / `RestrictedDatabaseQueries` | n/a | n/a | **Extend as-is** | `execute{}` is the sanctioned generic write path; add one gated forwarding class for the console, no new actor request type. |
| `WorkManagerSyncScheduler.kt` | n/a | n/a | **Extend as-is** + `ColdSyncRunner.kt` | Superseded by owner decision (scheduled sync = full sync, ADR-004): the fast path calls `runScheduledSync` through the existing `GitSyncServiceRegistry` (whose `register` has no production caller today and is wired in Task 2.4c); the slow path becomes a dirty-tree-safe, SAF-excluded `ColdSyncRunner`. |

No **Refactor-first** item is blocking: the only refactor that must land *before* the fix is the shared `resolveRemoteTrackingRef` helper, and it is the fix's own seam.

---

## 5. Event-Command-Policy table: sync flow

Events are facts observed; Commands are what the system issues; Policies are "whenever EVENT then COMMAND". Rows marked **NEW** are what this work adds; all others are VERIFIED current behavior.

| # | Event | Command / Reaction | Policy (current -> proposed) |
|---|---|---|---|
| 1 | User taps Sync (`triggerSync`) | `GitSyncService.sync(graphId)` | Unchanged. The only trigger of a full pipeline. |
| 2 | Timer / WorkManager tick | `fetchOnly(graphId)` | Current: fetch + `MergeAvailable` badge only, never merge/push (finding 2). **Proposed (needs owner decision):** on `MergeAvailable` with a clean tree, run `sync()` automatically, or keep manual and amend metric 1. |
| 3 | `NetworkMonitor` offline | `SyncState.Error(Offline)`, abort | Unchanged. |
| 4 | Vault locked | `CredentialVaultLocked`, abort | Unchanged. |
| 5 | HEAD detached | `SyncState.Error(DetachedHead)` | Unchanged. |
| 6 | Local changes present | `stageSubdir` -> `commit` | Unchanged. Commit failure RateLimited -> schedule retry; CredentialExpired -> state. |
| 7 | `fetch` returns Right with `<remote>/<branch>` unresolved | **Current: none (Success, 0 merged, silent).** | **NEW:** `fetch` returns `Left(RemoteBranchMissing(remote, branch, available))`; `SyncState.Error` with message "origin/main not found; remote has: master"; WARN log; surfaced action "Switch to master". |
| 8 | `RemoteBranchMissing` and remote default is unambiguous | **NEW:** show confirm UI; on accept `GitConfigRepository.saveConfig(remoteBranch=default)` then re-run `sync` | Policy: never rewrite stored config without confirm (Risk Control). Console twin: `git set-branch`. |
| 9 | Clone completes | **NEW:** set `remoteBranch` from the checked-out branch, not the wizard text | Replaces default `"main"` for any clone (root cause fix, prevents recurrence). |
| 10 | Wizard `testRemote` succeeds | **NEW:** `lsRemote` symref default branch -> prefill Step 4 | Free text stays editable. |
| 11 | Fetch Right, `hasRemoteChanges` | `merge(config)` | Unchanged. `doMerge` also uses the shared resolver (**NEW**: same error type, not a differently worded `FetchFailed`). |
| 12 | Merge conflicts, single journal file | `JournalMergeReady` else `ConflictPending` | Unchanged. |
| 13 | Merge OK | `beginGitMerge(files)` -> `reloadFiles(files)` -> `endGitMerge` | Unchanged; **NEW:** `reloadFiles` WARNs on unreadable path; deletions handled as follow-up. |
| 14 | Remote ahead and local branch != configured branch at push | **NEW:** `doPush` uses explicit refspec or fails with `RemoteBranchMissing`/mismatch error | Today push goes to a different branch than fetch silently. |
| 15 | Push OK | `refreshLocalStatus`, `Success(local, remoteMerged)`, `recordLastSyncAt` | Unchanged. |
| 16 | Push ConflictError (GitHub 409/422) | `ConflictPending` | Unchanged. |
| 17 | Console `git fetch/merge/set-branch` invoked | Same `GitRepository`/`GitConfigRepository` calls; **must hold the same per-graph git write lock** (`GitWriteLockNaming`) and increment `GitSyncBusyCounter` | **NEW** policy: console never runs git concurrently with `sync()` (shared lock; else wait/refuse with message). |
| 18 | Console `sql` WRITE confirmed | `DatabaseWriteActor.execute{}` after `DbMaintenance.backup()` | **NEW:** backup precedes write; WARN audit line; result tagged DB-only. |
| 19 | Console `graph reload` | `GraphLoader.reloadFiles(paths)` inside `beginGitMerge/endGitMerge`-style suppression | **NEW:** reuse watcher suppression so self-writes do not echo. |

---

## 6. Open questions / items needing the owner or the device

1. (Device) Git section of the next diagnostics export: `remoteBranch=`, `resolve(origin/<branch>)`, `ls-remote` heads, local HEAD branch. Confirms or refutes finding 1; the plan should keep a branch for "ref resolved but still 0" (then look at shallow/`countRemoteCommitsBestEffort`).
2. (Owner) Background sync: auto-merge or badge-only (finding 2). Changes metric 1's wording and the scheduler tests.
3. (Owner) 30-journal cap: intentional lazy load or gap (finding 3)? Determines whether Success Metric 4 needs a `GraphLoader` change or only console reporting.
4. (Owner) Open Q4 stale DB-only journals for 2026-10-07/09/10: console `graph journals --diff` should list them before any write-out/push decision; I have no evidence on their origin.
5. (Owner) Base branch: the git/diagnostics edits are uncommitted on `feat/cross-graph-phase2`; the console touches `RepositorySet`/`GraphManager` neighborhood that PR #397 also changes. INFERRED conflict risk is low if the console only adds new files plus a `Screen.Console` route, higher if `RepositorySet` gains a field. Prefer branching from `main` after #397 merges for the console; the sync fix can land independently.
6. (Verify before building) Android SQLite/libsql support for `VACUUM INTO`; JGit symref advertisement via `lsRemote` on GitHub; whether `JvmGitRepositoryTest` fixtures can create a `master`-default bare remote cheaply (expected yes).
7. (Bazel) New `console/` files in `commonMain` must land in the right Bazel target (see `project_plans/commonmain-bazel-target-split`); not investigated here.
