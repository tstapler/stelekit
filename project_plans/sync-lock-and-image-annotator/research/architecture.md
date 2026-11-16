# Research: Architecture (SDD Phase 2) — sync-lock-and-image-annotator

Labels: VERIFIED = code read this pass (path:line under `kmp/src/`); INFERRED = reasoned; UNVERIFIED = needs a device/log/experiment.
Builds on `project_plans/git-sync-resilience/` (research/pitfalls.md §0 L63-68, §2.1 L181-197, §3 L325-335; ADR-002 single retry owner), `android-git-saf-shadow-worktree/`, `measurement-accuracy-and-camera-profiles/` (ADR-001/002/004, research/existing-scaffolding.md), `annotation-lens-distortion/design.md`.

## 0. Headline findings

1. **Root cause of the owner's stale-lock error is a code path, not (only) a crash.** Every `sync()` runs "3b. Remove stale lock file" (`commonMain/.../git/GitSyncService.kt:338-342`) which calls `gitRepository.removeStaleLockFile`; that returns `Left(StaleLockFile)` whenever `.git/index.lock` exists and is <=60 s old, and DELETES it when >60 s old (`androidMain/.../git/AndroidGitRepository.kt:676-686`). So: a second sync starting while the first holds a live `index.lock` fails in ~0.36 s with exactly the logged `Stale git lock file` (VERIFIED code; matches the 21:57:51.78 -> :52.14 log, INFERRED consistent). If the first sync is slow (>60 s on an 8k-page graph; the log shows 7.5 s single ops) the second sync DELETES A LIVE LOCK. Age is the wrong liveness test (requirements Rabbit Hole confirmed).
2. **No single-flight exists anywhere.** `GitSyncService.sync` has no Mutex/guard (grep of the file: none; only `gitSyncBusyCounter.begin()` at L~291, a counter for quiesce, not exclusion). UI `triggerSync()` launches an unguarded coroutine per press (`ui/GitSyncCoordinator.kt:139-145`). `PeriodicSyncDriver` skips only if `isBusy()` (the counter) or its own `tickLock` (`git/PeriodicSyncDriver.kt:173`) — a TOCTOU window between counter check and `begin()`. `NamedLocks` exists but is a private top-level used ONLY by the cold worker path (`WorkManagerSyncScheduler.kt:321-325`, `ColdSyncRunner.kt:24-34,51`); the live service never takes it, so cold fetch and foreground sync can overlap.
3. **Coroutine cancellation is probably NOT the orphan source.** JGit calls run as blocking calls inside `withContext(IO)`; grep finds no `runInterruptible`; only clone wires `ProgressMonitor.isCancelled` (`AndroidGitRepository.kt:166-175`). A cancelled coroutine therefore keeps running the blocking JGit call to completion and JGit's own `finally` releases its lock (INFERRED from absence of interrupt plumbing; UNVERIFIED by test — Phase 4 should add the forced-cancel test). `SyncRunGuard.cancelled()` only resets UI state (`SyncRunGuard.kt:21`). The UI Cancel button does cancel the sync coroutine (`GitSyncCoordinator.kt:141`), which then lets a *new* sync start while the old blocking call is still in flight — i.e. cancel creates the overlap rather than the orphan.
4. **Real orphan sources** are process death mid-git-op (OOM / `EXCESSIVE_RESOURCE_USAGE` kill, OS kill of the worker, force-stop) and a retry's own `beforeRetry` cleanup — see §A.4.
5. **Multi-dimension calibration is already half-built**: `confirmMultiPointCalibration` (`AnnotationEditorViewModel.kt:657`), `LensCalibration(k, ticks, residualPercent, profileId)` (`model/LensCalibration.kt`), `GeometryCalibration(height, plane homography)` (`model/GeometryCalibration.kt`). What is missing is non-collinear *distance constraints* (ticks are positions along ONE ruler) and editing a measurement value. Extend, do not parallel-build.

---

## A. Single-flight sync coordinator

### A.1 Entry points (VERIFIED) and what each does today

| Entry point | Where | Guard today | Can overlap with |
|---|---|---|---|
| UI Sync button | `ui/GitSyncCoordinator.kt:139` -> `GitSyncService.sync(Manual)` L278 | none | everything |
| Mass-change confirm | `ui/SyncSafetyActions.kt:101` -> `sync(liftMassChangeGuard=true)` | none | everything |
| Fetch-only | `GitSyncCoordinator.triggerFetchOnly` -> `fetchOnly` L533 | none (cancels rate-limit retry L542) | sync |
| Scheduled tick (foreground) | `PeriodicSyncDriver` L173 -> `ScheduledSyncRunner.run` (L46) -> `runFull`/`fetch` | `isBusy()` counter + `tickLock.tryLock()` | a manual press racing the counter |
| WorkManager, app open | `GitSyncWorker` L231 -> `GitSyncTask` -> `GitSyncService.scheduled.run` | none beyond driver's | manual press |
| WorkManager, cold process | `WorkManagerSyncScheduler.kt:300-325` -> `ColdSyncRunner` under private `ColdSyncLocks` | cold-only `NamedLocks` | live service started after the cold worker began (process resurrects) |
| Rate-limit retry | `SyncRunGuard.publishFailure` -> `rateLimitRetry.schedule` -> re-calls sync | `sync()` entry does `rateLimitRetry.cancel()` L296 | manual |
| Conflict resolve / journal merge | `GitSyncService.resolveConflicts` L646, `applyJournalMerge` L708, `abortActiveMerge` L763 | none | a scheduled tick while a merge is pending |
| Commit-only | `commitLocalChanges` L600 | none | sync |

Cancel sources: UI job Cancel (`GitSyncCoordinator.kt:141` `run.cancel()`), `shutdown()` (`GitSyncService.kt:794`), `stopPeriodicSync`/driver restart, graph switch.

### A.2 Where the lock lives — options evaluated

| Option | Covers | Fails to cover | Verdict |
|---|---|---|---|
| (a) Serialize inside `GitSyncService` only | UI, ticks, resolve, retry | Cold `ColdSyncRunner` (no service exists), JVM/other repository users, `removeStaleLockFile` callers | Necessary but not sufficient (requirements Alternatives already notes this) |
| (b) Per-repo `NamedLocks` in the git layer (decorate `GitRepository`) | EVERY JGit call incl. cold path | "Whole pipeline atomicity": sync = status/commit/fetch/merge/push as several separate repo calls; a decorator would interleave two syncs between calls | Needed for mutual exclusion of raw ops, wrong grain for single-flight |
| (c) Lock-file protocol (owner record) | Cross-process, crash recovery | In-process mutual exclusion cost (polling), pid reuse | Needed only for recovery/liveness, not as the mutex |
| **Recommended: layered (a)+(b-lite)+(c)** | | | below |

**Recommendation (Phase 3 to formalize as ADR):**
1. **One process-wide `RepoOpGate`** (commonMain `git/`, successor to `NamedLocks`; same key `GitWriteLockNaming.lockNameFor(repoRoot)` so cold and live paths collide by construction). Hoist the instance out of `WorkManagerSyncScheduler.kt:325` into the composition roots (Android Application, Desktop main) and inject into `GitSyncService` and `ColdSyncRunner`. Wiring must be reachable from `WiringCompletenessTest` roots.
2. **Single-flight at pipeline grain in `GitSyncService`**: `sync`, `fetchOnly`, `commitLocalChanges`, `resolveConflicts`, `applyJournalMerge`, `abortActiveMerge`, `deepen`, `repairBranch` each acquire the gate with `tryAcquire` (never `lock`/suspend-wait for UI/tick triggers). On failure: coalesce (A.3), return, do not touch state.
3. **Gate is the only thing that may call `removeStaleLockFile`/lock cleanup**, and only while holding it (A.5). This alone removes Finding 1: with the gate held no in-process op can own the lock.
4. Cold path: `ColdSyncRunner` keeps `withLock` semantics but on the shared gate (a cold fetch waits <= one fetch; a live sync trigger arriving mid-cold-fetch coalesces).

Lock order (extends CLAUDE.md "Cross-graph writes" rules): `RepoOpGate` is OUTERMOST for sync pipelines; never acquire `GraphWriteLock` (not used by git today — grep: no git file imports it) or `GitWorktreeLocks` before it; `GitWorktreeLocks.lockFor(shadowKey)` (`platform/GitWorktreeLocks.kt`, taken in `GitShadowWorktree.kt:198` and the write-behind flush `PlatformFileSystem.kt:1005`) nests INSIDE the gate and guards only SAF<->shadow copy, not JGit (pitfalls.md L63-68). Never `await` the `DatabaseWriteActor` idle while holding the gate if any actor job can itself request a sync (today `graphWriter.flush(); editLock.awaitIdle()` at `GitSyncService.kt:344-345` already runs inside the pipeline — keep it, but forbid any new actor->sync dependency). Tick path must not hold `tickLock` while waiting for the gate (use `tryAcquire`).

### A.3 "Coalesce, don't cancel" in `SyncState`

- A second trigger NEVER cancels, restarts or replaces the in-flight run; `rateLimitRetry.cancel()` (L296/L542) moves to *after* a successful gate acquire so a coalesced press does not cancel a scheduled retry.
- Do not add a new running variant to `SyncState` (sealed, consumed exhaustively by UI; `SyncRunGuard.isRunning()` enumerates 4 variants). Coalescing is reported out-of-band: `sync()` returns `Right(current/last Success)` or a typed `SyncJoin` result (Phase 3 choose; return type is `Either<GitError, SyncState.Success>` today at L283, so the least invasive is a new `GitError.SyncInProgress` that the UI maps to a toast "Sync already running", NOT to `SyncState.Error`) plus a log line `sync trigger=<manual|scheduled|worker|resume> decision=<started|coalesced|skipped>` (Observability requirement).
- `SyncState` stays the single truth of the running pipeline; add a tiny `inFlight: StateFlow<SyncRunInfo?>` (source, startedAt) beside it for the UI/tests, written only by the gate holder.
- **Manual press during run**: set a `rerunRequested` flag (one bit, not a queue). When the running pipeline finishes, if the flag is set AND the trigger was Manual (user wants their latest edits pushed), run exactly one follow-up after the gate is released. Scheduled/worker triggers are dropped (skipped, logged), never queued. This matches `PeriodicSyncDriver`'s existing "skipped, never queued" contract (L90) and `SkipReason.Busy`.
- The driver's `isBusy` lambda (`GitSyncService.kt:229`) becomes `gate.isHeld(repo)`; the counter stays for `GraphMoveQuiesceStrategy` only.
- The UI Cancel button: keep, but make cancel *cooperative and lock-safe*: cancellation sets a flag the pipeline checks at step boundaries (between status/commit/fetch/merge/push — `ensureActive()`), because blocking JGit calls are not interruptible (A.4). Do not release the gate until the blocking call actually returns (`try/finally` around the JGit call inside the gate, not `withContext` cancellation) — this closes "cancel then immediate re-press overlaps the still-running JGit call".

### A.4 Where JGit lock files can be orphaned (and by what)

| Orphan scenario | Mechanism | Real? | Evidence |
|---|---|---|---|
| Coroutine cancel mid-`add`/`commit`/`merge`/`fetch` | blocking JGit call not interrupted; JGit `LockFile` closes in its own `finally` | Unlikely (INFERRED) | no `runInterruptible`; clone-only `isCancelled` at AndroidGitRepository.kt:175. **Phase 4: forced-cancel test** |
| Process death (OOM kill, `EXCESSIVE_RESOURCE_USAGE`, force-stop, worker killed after 10 min limit) while JGit holds `index.lock` / `shallow.lock` / `refs/**/*.lock` / `packed-refs.lock` / `HEAD.lock`/`config.lock` | `finally` never runs | **Yes** — prior exit log shows resource-usage kills; pitfalls.md L186-190 | Needs ownership record (A.5) |
| Second in-process sync's `removeStaleLockFile` deleting a live lock (age >60 s) | `AndroidGitRepository.kt:681` | **Yes** (new finding; slow op on big graph) | creates concurrent index writers, not an orphan |
| Retry cleanup `deleteLockFileIfStaleForRetry(lock, retryLoopStartMs)` deletes any lock with `lastModified < retryLoopStartMs` (`jvmCommonMain/.../GitOperationSupport.kt:287-291`, wired at `AndroidGitRepository.kt:217,241,424`) | A lock held by a *sibling* concurrent op that started before this loop is "stale" by that test | **Yes if overlap exists** — pitfalls.md L325-335 already warned | Becomes safe only when the gate guarantees no sibling; and should use the owner record, not mtime |
| SAF shadow: JGit runs against `filesDir/.../gitshadow` (`indexLockFile` uses `shadow.resolveForJGit`, `AndroidGitRepository.kt:670-674`), so the lock lives in app-private storage, not SAF | n/a — SAF cannot hold a lock; the shadow `.git` is single-owner app-private | Good: no foreign tool shares it (INFERRED) | A user-run desktop git on the *SAF* dir would not see the shadow's `.git` at all |
| Fetch/clone interrupted: `tmp_pack_*`, `*.keep`, partial `shallow.lock` | same as process death | Yes | clone handled by `deleteDirectoryContentsForRetry`; fetch not |

Same process? `GitSyncWorker` and the activity share the app process unless `android:process` is set (no hit in the manifests I grepped; UNVERIFIED for all manifests) -> the in-process gate covers app+worker; the only genuinely cross-"process" case is the previous process's corpse.

### A.5 Safe ownership / liveness protocol for recovering OWN stale locks

Goal: delete a lock only when it provably belongs to a dead holder. Fail closed otherwise (requirements Risk Control).

1. **Owner record**: before the first JGit call inside the gate, atomically write `.git/stelekit-sync.owner` (tmp+rename; in the shadow `.git` on Android) = `{instanceId (random UUID per process start), pid, processStartTicks (from /proc/self/stat field 22; JVM: ProcessHandle start Instant), op, trigger, startedAtMs}`. Delete it in `finally` after the gate's last git call. It is NOT a lock; the in-process gate is the mutex.
2. **Recovery decision** (only while holding the gate, replacing `deleteStaleLockFile`):
   - No lock files -> proceed.
   - Lock exists and owner record `instanceId == this process`: gate is held by us, so no in-process op owns it -> orphan from our own earlier op (e.g. cancelled/failed): safe to delete. Log `lock cleared owner=self reason=...`.
   - Lock exists, owner record from a different `instanceId`: owner alive iff `/proc/<pid>/stat` start-ticks match (JVM: `ProcessHandle.of(pid)` + `info().startInstant`). Alive -> `Left(SyncInProgress/AnotherProcess)`, do not delete. Dead (pid gone or start time differs = pid reuse) -> safe to delete; log `owner=<instanceId> dead`.
   - Lock exists, NO owner record (foreign tool, or crash between lock create and record write — impossible by ordering since record is written first): do NOT auto-delete; surface the existing "tap to retry" action that calls the user-initiated delete (mtime >60 s) so a human decides.
3. Scope the sweep to a fixed allow-list of JGit lock files (`index.lock`, `shallow.lock`, `HEAD.lock`, `packed-refs.lock`, `config.lock`, `refs/**/*.lock`), not just `index.lock`, and never `objects/`.
4. After deleting a lock from a dead owner, run `git status` equivalent (`gitRepository.status`) and `repositoryHealth` (`GitSyncService` guards, `safety.health`) before committing — index may be truncated/inconsistent; if JGit reports a corrupt index, refuse and route to the existing repair UX rather than rewriting.
5. `deleteLockFileIfStaleForRetry` is rewritten to ask the same recovery function (own-instance record) instead of comparing mtimes; it already is "best-effort silent" (L285).
6. Android `/proc/<pid>` readability for a same-uid dead app process: the pid simply is absent -> "dead"; for a live same-uid process it is readable (INFERRED; UNVERIFIED on API 33+ — Phase 4 device check; if unreadable, treat as alive = fail closed).

### A.6 Event–Command–Policy table (sync triggers; multi-actor domain)

| Domain Event | Policy trigger | Command | Actor/System |
|---|---|---|---|
| User taps Sync | gate free -> start; gate held -> coalesce, set `rerunRequested` | `RunSync(Manual)` / `JoinSync` | UI (`GitSyncCoordinator`) |
| User taps Sync during a run | never cancel running; follow-up once if manual | `JoinSync`, then 0..1 `RunSync(Manual)` | `GitSyncService` gate |
| Scheduled tick fires | gate held -> skip `Busy`, log, do not queue | `RunTick` / `SkipTick(Busy)` | `PeriodicSyncDriver` |
| WorkManager worker starts (app open) | route to live tick; held -> skipped | `RunTick` via `GitSyncTask.liveTick` | `GitSyncWorker` |
| WorkManager worker starts (cold) | take shared gate; held -> `Skipped` | `ColdFetch` | `ColdSyncRunner` |
| Rate limit received | schedule one retry (single owner, ADR-002); a manual run cancels only the *pending timer*, after acquiring gate | `ScheduleRetry` / `CancelRetry` | `RateLimitRetry` |
| Conflict detected | hold gate until user resolves or aborts? **Decision for Phase 3**: gate released at `ConflictPending`; a "merge in progress" repo state (guards.repositoryState, L335) blocks new sync instead | `PauseForUser` | `GitSyncService` |
| User resolves / applies journal merge | re-acquire gate | `ResolveConflicts` / `ApplyJournalMerge` | UI -> service |
| User cancels job | cooperative stop at next step boundary; gate released only after in-flight JGit call returns | `RequestStop` | `JobTracker.onCancel` |
| Process starts / worker starts after death | owner record dead + lock present -> recover under gate | `ClearOwnStaleLock` | Gate / `AndroidGitRepository` |
| Lock present, owner unknown/alive | fail closed | `Refuse(SyncInProgress/StaleLockFile)` | Gate |
| Graph switch / shutdown | stop at boundary; release gate in `finally` | `Shutdown` | `GitSyncService.shutdown` L794 |

### A.7 Data-safety failure modes (git repository) — complexity-4 addition

| # | Failure | How it happens today | Mitigation / Phase 4 test |
|---|---|---|---|
| F1 | **Lock-deletion race**: live lock removed, two writers on `index` | `removeStaleLockFile` >60 s age (`AndroidGitRepository.kt:681`) and `deleteLockFileIfStaleForRetry` mtime test | Gate + owner record; test: hold fake slow op >60 s, second trigger must not delete (fails on today's code) |
| F2 | **Concurrent index writers**: two syncs each `add`/`commit` | no mutual exclusion | Gate; test: two parallel `sync()` on a fake `GitRepository` that records overlapping calls — assert max concurrency 1 |
| F3 | **Partial merge**: `fetch` ok, `merge` interrupted by kill -> `MERGE_HEAD` present, working tree partially updated, index lock orphaned | pipeline is not atomic | existing `guards.repositoryState` (L335) already refuses sync while mid-merge; keep. Recovery after kill: do not auto-`reset --hard`; route to existing abort/repair (`abortActiveMerge`, `guardedAbort`) with its preservation prompt (PR #676 unapplied-disk-content guard must stay intact) |
| F4 | **Truncated/corrupt `index`** after kill during write | JGit writes index via lock-file + rename (atomic) so the committed file is normally intact; the orphan is the `.lock` | after clearing, run `status`+health; corrupt -> refuse |
| F5 | **Shadow vs SAF divergence** after killed run: shadow tree has staged/committed state SAF lacks (or inverse) | shadow is mirror (`GitShadowWorktree.ensureFresh`) | SAF is source of truth for uncommitted work (`AndroidGitRepository.kt:509-512` comment); recovery path must re-run `ensureFresh(force)` before commit; never write shadow back to SAF without existing guards |
| F6 | **Rate-limit retry stacking** with a manual run | retry cancelled by `sync()` entry before guard | order: acquire gate, then cancel timer |
| F7 | **Resolve/applyJournalMerge racing a tick** | unguarded | gate on all mutating entry points |
| F8 | **Cancel then immediate re-press** while old blocking JGit call still running | cancellation does not interrupt the call | gate released in `finally` of the JGit call, not on coroutine cancel |
| F9 | **Cold worker resurrects process while foreground sync starts** | private `ColdSyncLocks` | shared gate |

Test matrix for Phase 4: overlap (UI x UI, UI x tick, UI x cold, tick x cold), forced cancel at each step boundary, forced "crash" = simulated by leaving a lock + dead owner record, lock + live foreign owner, lock + no record, lock + own-instance record, pid-reuse (same pid different start ticks), >60 s slow op, cancel-then-repress. Existing seams: `GitSyncServiceStuckStateTest`, `PeriodicSyncDriverTest`, `ColdSyncRunnerTest`, `GitOperationSupportHelpersTest`, `AndroidGitRepositoryShadowWorktreeTest` (add a gate-aware fake repository in `GitSyncServiceTestFixtures.kt`).

### A.8 Disposition (sync)

- **`GitSyncService` (806 lines, `sync()` alone ~L278-520 = ~240 lines, mixes network check, creds, config, guards, commit, fetch, merge, push, state publish)**: **Isolate via seam.** Introduce `RepoOpGate` + a thin `SyncSingleFlight` wrapper around the existing public methods; do NOT refactor the pipeline body in this change (it already delegated to `guards`, `scheduled`, `runGuard`, `periodicDriver`; the extraction is under way per ADR-004). Refactor-first would risk PR #676/#662 guard regressions.
- **`removeStaleLockFile` / `deleteLockFileIfStaleForRetry`**: **Refactor-first** (small): replace age/mtime tests with one `LockRecovery` collaborator (commonMain port, JVM/Android impl) that both call; it duplicates today in `JvmGitRepository.kt:568-585` and `AndroidGitRepository.kt:663-686`.
- **`NamedLocks`**: **Extend as-is** (rename/move, add `tryWithLock`, `isHeld`).

---

## B. Annotator interaction architecture

### B.1 Current mode state machine (VERIFIED)

- `AnnotationTool {SELECT, DISTANCE, AREA, ANGLE, LABEL, GRID_REF}` (`ui/annotate/AnnotationEditorViewModel.kt:74`), `AnnotationEditorState.currentTool` default **`DISTANCE`** (L108) — so a fresh editor starts in a placing tool, not Select (relevant to the "fresh editor" success metric).
- `selectTool(tool)` L445 is the only transition: clears `inProgressPoints`, preserves GRID_REF sessions only for GRID_REF, resets `placement` for SELECT.
- Placement flow: `AnnotationGestureLayer` routes by tool: `if (tool == SELECT) selectGesture else placeGesture` (`AnnotationGestureLayer.kt:137-140`). `placeGesture` (L186-209): one finger aims with loupe, release calls `onTap(canvasPoint)` -> `addPoint` (VM L519) -> at `requiredPointCount` -> `commitAnnotation` (L814) -> optimistic append + `persistence.submit` (L868-890). A second finger hands off to `viewGesture` (pinch/pan) — so pinch works in tool modes, one-finger pan does not (that is the owner's complaint). Two-step "tap to place, drag/nudge" also exists via `AnnotationNonDragMode` + `AnnotationPlacementSession` (`selectTool` resets it for SELECT; `AnnotationNonDragMode.kt` 146 lines, `AnnotationToolbar.kt` has `NudgePad`, `AnnotationPlacementControls`).
- `GestureOwner {TOOL, VIEW}` + `gestureOwner(previous, pressedPointers)` (`AnnotationViewTransform.kt:144-151`) is already the pure arbitration function; extend it, do not special-case in the layer.

### B.2 Where auto-return and tool lock belong

- **Policy in the ViewModel, not the gesture layer or toolbar.** `commitAnnotation` is the single success point for DISTANCE/AREA/ANGLE/LABEL (incl. LABEL via `confirmLabel` L591 and AREA via `closePolygon` L571). Add an immutable `ToolStickiness { ONE_SHOT, LOCKED }` field in `AnnotationEditorState` and a pure function `nextToolAfterCommit(tool, stickiness, settings) : AnnotationTool` (commonMain, unit-testable like `AnnotationNonDragMode`'s pure helpers). After the optimistic update succeeds (not after persistence — rollback on failure would otherwise leave the user in Select with nothing placed; return to Select on submit, re-enter on failure is NOT desired; keep tool unchanged on rollback), call `selectTool(next)`.
- Exceptions: GRID_REF/ruler/plane sessions (multi-step, `rulerSession`, `planeSession`) stay until their own confirm; `confirmGridRefCalibration`/`confirmRulerSession`/`confirmPlaneSession` return to Select (they currently end in the tool they started).
- **Lock gesture**: long-press on a toolbar tool button (or double-tap) toggles `LOCKED`; toolbar shows a lock badge. `ToolStickiness` resets to ONE_SHOT on leaving the editor; the user preference (Settings toggle default ON for auto-return; lock opt-in) read through the existing `Settings`/`PlatformSettings` pattern (requirements Risk Control).
- Default tool on fresh open: SELECT (1-line change at L108, but `AnnotationEditorViewModelBranchTest`/`AnnotationEditorViewModelTest` assert DISTANCE-default likely -> UNVERIFIED, grep in Phase 4).
- Regression risks (requirements Rabbit Hole): undo/redo `pushUndo(before)` (L414) must snapshot tool-independent state; auto-return must not push an undo entry; hit-testing unchanged because `selectGesture` is already the SELECT path.

### B.3 Larger-than-image canvas and wider zoom-out

Current: `MIN_VIEW_SCALE = 1f` (fit), `MAX_VIEW_SCALE = 8f` (`AnnotationViewTransform.kt:13,16`); `clamped()` returns identity `Fit` at scale <= 1 (L109-112); `clampAxis` centres content smaller than the viewport and otherwise allows pan only until the far edge is `slack` short (L124-131); `slack` (default 0) is the existing "extra pan room past the edge" knob in `ViewGeometry` (L37-46, built at `AnnotationEditorScreen.kt:210`).

Architecture options:
1. **Lower `MIN_VIEW_SCALE` (e.g. 0.25) and delete the `Fit` short-circuit**; `clampAxis`'s "content <= viewport -> centre" branch already handles a small image. Annotation points stay normalized `[0,1]`, which is the invariant (comment L53-54: view concern only, never persisted). Point placement beyond the image bounds must be clamped at `addPoint`/`updateAnnotationPoints` (normalized coords outside [0,1] would be persisted — decide: clamp to [0,1], which satisfies "point on the outermost pixel row/column"). The *gutter* is purely visual so edge points are reachable without the finger covering the border.
2. **Give `ViewGeometry.slack` a non-zero value** instead of lowering min scale (cheaper, but only gives pan room when zoomed in; does not give zoom-out). Combine: slack for edge reach at scale>=1 plus min scale <1 for overview.
- `isFit` (L61) is used for the double-tap `toggledAt` and Fit button: redefine `Fit` as the *fitted-to-viewport* identity, not the scale floor; `FIT_EPSILON` logic then needs the floor separate from "fit".
- Hit-testing (`AnnotationHitTest.kt:27 hitTestAnnotations`) and loupe (`loupeZoomFactor(viewScale)` L166) take canvas/normalized coords and scale; tolerances are in px so they should divide by scale — verify that hit radius is screen-px (UNVERIFIED; Phase 4 test at scale 0.25).
- **Export**: annotations export from normalized coords against the native bitmap (sidecar `normalizedPoints`), independent of view transform (comment L53-54) — a larger canvas therefore changes NO export math. If an "exported image with annotations" renderer exists it draws on the image bounds, not the canvas (UNVERIFIED: grep in Phase 3 for the export composable; do not extend the canvas into the export).
- Perf: a smaller scale draws the same bitmap smaller (cheaper); gutter is background fill. Risk is 12 MP decode — unchanged.
- Toast overlap (requirements note): layout bug in the editor screen overlay, not architecture; fix by hosting the toast in a `Scaffold` snackbar/overlay anchored above the canvas.

### B.4 Disposition (annotator)

- **`AnnotationEditorViewModel` (1510 lines)**: handles tools, placement, 3 calibration session kinds (grid-ref, ruler, plane), persistence, undo/redo, reliability advice, selection, label dialog. Known God-class. **Isolate via seam**: put the new `nextToolAfterCommit`/`ToolStickiness` and the measurement-value-edit logic (C.3) in new pure functions/collaborators (like existing `AnnotationPlacement.kt`, `MultiPointCalibration`, `RulerTickSession`) and add only thin delegations to the VM. Do not Refactor-first: it would double the diff against a heavily tested class (`AnnotationEditorViewModelBranchTest` 680 lines + `AnnotationEditorViewModelTest`). Phase 3 may list "extract calibration sessions" as a separate follow-up.
- **`AnnotationViewTransform`/`ViewGeometry`**: **Extend as-is** (small pure file, 171 lines, has `AnnotationViewTransformTest` 167 lines) — change the scale floor and Fit semantics with test-first.
- **`AnnotationGestureLayer`**: **Extend as-is**; no tool-specific policy goes in it.

---

## C. Calibration model architecture

### C.1 What exists (VERIFIED)

`Calibration` (`model/ImageAnnotation.kt:50-66`): `method`, `pixelsPerMeter` (single scale), `confidencePercent`, `basisVersion` (0 legacy / 1 native / 2 native+lens, `CalibrationBasis` L72-82), `lens: LensCalibration?`, `geometry: GeometryCalibration?`. `LensCalibration`: division-model `k`, `ticks: List<CalibrationTick(x,y,positionMeters)>`, `residualPercent`, `kEstimated`, `profileId/profileLabel` (snapshot, ADR-001). `GeometryCalibration`: `heightOffsetMeters`, `cameraDistanceMeters`, `plane: PlaneCalibration(homography 9 doubles, referenceName, width/height, checkResidualMm)`. Persistence: JSON columns `calibration_lens_json`, `calibration_geometry_json` (`db/MigrationRunner.kt:943-953`) via `LensCalibrationCodec`/`GeometryCalibrationCodec` (`ignoreUnknownKeys = true`, unparseable -> null = "degrades to a plain scale"); sidecar `SidecarImageAnnotation.calibrationLens/Geometry` + `calibrationBasisVersion` (`db/sidecar/ImageSidecarSchema.kt:41-45`), `SidecarFile.schemaVersion = 1` (L20). `SqlDelightImageAnnotationRepository` maps columns (focal_length_mm at L92/L163). Pipeline order is fixed by `measurement-accuracy-and-camera-profiles` ADR-002 (normalize -> undistort(k) -> plane H or scale -> measure -> height factor -> uncertainty). `updateCalibration` (VM L903) re-derives ALL committed `valueMeters` (L897-902 doc) — the recompute-on-calibration-change mechanism already exists.

Reconcile with prior art (requirements demand it): measurement-accuracy research ranks lens distortion SMALLEST of four error terms (depth/plane mismatch, short reference, tilt dominate; ADR-002 v1 order puts guidance, height, plane homography BEFORE profiles; ADR-004 defers `LENS_DISTORTION`). Therefore "multi-dimension calibration from several known distances in different directions" should be framed architecturally as a **constraint set that feeds the existing `k` and plane fits**, not a new distortion product: (i) with >=2 non-collinear known distances you can fit per-axis scale (anisotropy) and `k` jointly with residuals; (ii) >=4 coplanar points already goes through `PlaneCalibration`. Whether (i) beats today's single scale is an EMPIRICAL question for the accuracy-research agent; this document only fixes where it plugs in.

### C.2 Where multi-reference calibration fits — recommended seam

- Generalize `CalibrationTick` (collinear, 1-D positions) with a sibling **`CalibrationConstraint(p1, p2, distanceMeters, label?)`** (two normalized points + true length), stored as an additive list `constraints: List<CalibrationConstraint> = emptyList()` on `LensCalibration` (or a new `fit: FitSummary` object — decide in Phase 3). The existing multi-tick path becomes a special case (k collinear constraints). Pure solver in `commonMain/calibration/` next to `MultiPointCalibration` (`calibrate(ticks, w, h, mode, profile)`), returning `Calibration` + residual; VM gets `confirmConstraintCalibration(constraints)` modeled on `confirmMultiPointCalibration` (L657) and calling `updateCalibration(keepingGeometry(...))`.
- **No new table** -> the CLAUDE.md `MigrationRunner.all` rule is not triggered; additive JSON fields only (`ignoreUnknownKeys = true` gives forward/back compatibility for old app versions reading new JSON, they drop the unknown list). If any new column is added: `SchemaOp.AddColumn(... "TEXT NOT NULL DEFAULT ''")` like L943-953, update `.sq`, regenerate `kmp/src/generated/sqldelight*` and commit, add `RestrictedDatabaseQueries` stub (writes), and update `SqlDelightImageAnnotationRepository`. Prefer NO new column.
- Sidecar: add `calibrationConstraints` (or nest inside `calibrationLens`) with default empty; keep `schemaVersion` at 1 for pure additions (ignore-unknown readers) or bump to 2 only if semantics of existing fields change. Requirements Risk Control says "additive with a version field" — the existing version fields are `schemaVersion` (file) and `calibrationBasisVersion` (data); use `basisVersion` only if stored geometry changes meaning (ADR-002 Consequences already says: additive, no bump).

### C.3 Editing a measurement value feeds calibration

A user-edited value on measurement M means "segment M (points p1,p2) truly has length v". Two distinct intents; the architecture must separate them:
- **Edit as annotation override**: `MeasurementAnnotation.valueMeters`/`valueDisplay` replaced, flagged `userSupplied=true` so `updateCalibration`'s recompute loop (VM L897-902 doc) does NOT overwrite it. Needs a field on `MeasurementAnnotation` (282 lines, `model/MeasurementAnnotation.kt`) and the sidecar `SidecarMeasurement` (`valueMeters`, `valueDisplay` exist at L65-66; add `valueSource: "COMPUTED"|"USER"` default COMPUTED) + `measurement` columns (check `SqlDelightMeasurementAnnotationRepository`; a new column needs MigrationRunner `AddColumn`). Backward compatible: missing -> COMPUTED.
- **Edit as calibration reference**: offered as an explicit action "use as calibration reference" -> creates a `CalibrationConstraint(p1,p2,v)` and re-fits. Do NOT silently turn every value edit into calibration (it would let one typo re-scale the whole photo; requirements' own prior-art ranks short/noisy references as dominant error). Show the residual/uncertainty (ADR-002 step 6) before committing; the calibration undo (`undoCalibration` L954) already exists for rollback.
- Existing `GRID_REF` tool is effectively "a measurement used as calibration" (reference-length dialog, `confirmGridRefCalibration` L623) — editing should reuse its dialog (`isGridRefDialogVisible`, `updateGridRefLengthText`) rather than a second text-entry UI.

### C.4 Persisted-annotation migration failure modes (complexity-4 addition)

| # | Failure | Mitigation / test |
|---|---|---|
| M1 | Old sidecar (no new fields) loads | defaults + `ignoreUnknownKeys`; test: golden v1 sidecar JSON round-trips (`ImageAnnotationOfflineTest`, `ImageAnnotationModelTest` extend) |
| M2 | New sidecar read by an OLD build (downgrade/rollback to 0.113.3) | unknown keys ignored; calibration silently degrades to scale-only (codecs return null on parse error) — acceptable but must not *rewrite* the sidecar dropping the new data on a no-op save. Old build does rewrite -> data loss of constraints on downgrade (known, accept + document) |
| M3 | `.sq` column added but not in `MigrationRunner.all` | `MigrationRunnerSchemaSyncTest` catches new tables only, not columns -> manual review; prefer JSON, no column |
| M4 | Generated SQLDelight sources not regenerated | CI job "SQLDelight generated sources" |
| M5 | Recompute loop overwrites user-edited values | `valueSource` flag + test in `AnnotationEditorViewModelTest` |
| M6 | Basis-version confusion: re-fit under basis 0 (legacy decode-relative) image | `Calibration.isLegacyBasis` (L66) — never rescale legacy (ADR-002 step 1); constraint calibration must refuse or upgrade only through the existing legacy path |
| M7 | `valueMeters` stale after unit/profile change | existing recompute path; assert `userSupplied` kept |
| M8 | Two writers racing sidecar vs DB | writes already via `DatabaseWriteActor`; sidecar writer is the git-synced file — the sync gate (A) means sidecar rewrite during a sync can produce merge noise; sidecar is a normal graph file so existing guards apply |
| M9 | Persistence rollback after optimistic update (VM L877-889) | edit path must use the same optimistic+rollback pattern and keep undo stack consistent |

### C.5 Disposition (calibration)

- **Extend as-is** for `Calibration`/`LensCalibration`/`GeometryCalibration`/codecs/sidecar (additive); **Isolate via seam** for the solver (new pure object in `commonMain/calibration/`, `MultiPointCalibration` precedent) so the VM stays a thin caller. `MeasurementAnnotation.kt` (282 lines, mixes model + helpers) **Extend as-is** (add one field).

---

## D. Camera capture architecture

### D.1 Current structure (VERIFIED)

- Commonmain contract: `interface CameraProvider { isAvailable; suspend capturePhoto(): Either<SensorError, PlatformImageFile> }` (`platform/sensor/CameraProvider.kt`); `expect fun CameraViewfinderDialog(onCapture, onDismiss, onError)` (`ui/components/CameraViewfinderDialog.kt`, 11 lines) — **the expect signature carries no lens/camera parameter and no result metadata beyond `PlatformImageFile`**.
- Two independent Android binding paths: (1) the dialog: `ProcessCameraProvider`, `obtained.unbindAll(); bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)` (`androidMain/.../ui/components/CameraViewfinderDialog.android.kt:~91-111`), photo via `takePhotoAndProcess` (L208) with `SensorModule.motionSensorProvider.snapshotSensorData()` (L228); (2) `AndroidCameraProvider.capturePhoto()` binds `CameraSelector.DEFAULT_BACK_CAMERA` itself (`AndroidCameraProvider.kt:116`) and `AndroidCameraPreviewBinder` binds ONLY a `Preview` to `ProcessLifecycleOwner` with `DEFAULT_BACK_CAMERA` and deliberately never `unbindAll()` (`AndroidCameraPreviewBinder.kt:54-59`) so it composes with another bound use case on the singleton camera. A lens switch must change the selector in every binder of the same session consistently or CameraX throws (use cases of one camera must share a Camera); the two-path design makes "which binder owns the session" the key decision.
- Metadata path: `PlatformImageFile{focalLengthMm, focalLength35mmEq, cameraMake, cameraModel, sensorData}` (`platform/sensor/PlatformImageFile.kt:23-31`) -> `ImageImportService.kt:139` (`tempFile.focalLengthMm ?: capturedSensorData?.focalLengthMm`) -> `ImageAnnotation.sensorData` (`ImageSensorData` incl. focal length, make/model, `model/ImageAnnotation.kt:92-101`) -> DB `focal_length_mm` / sidecar `focalLengthMm` -> `CalibrationService`/`MultiPointCalibration` + `CameraProfile` matching (ADR-001: profile snapshot in lens JSON). Focal data is read from EXIF after the fact, which for a physical-lens capture reflects that lens (INFERRED; UNVERIFIED on the owner phone — some OEMs write the logical camera's values).

### D.2 Where lens selection and lens metadata live

- **Lens enumeration + selection is Android-only and belongs in `androidMain`** behind a small commonMain port, e.g. `CameraLensCatalog { val lenses: List<CameraLens>; }` with `CameraLens(id, facing, focalLengthMm?, focal35mmEq?, zoomRatio?, isLogicalMulti)` (commonMain data class so UI/tests/sidecar share it); the `expect fun CameraViewfinderDialog` gains an optional `lensChoice`/result type or (preferred, to avoid expect/actual churn on every platform) the dialog keeps its signature and `PlatformImageFile` carries the chosen `CameraLens`, with desktop/iOS/wasm actuals ignoring it. Implementation: CameraX `ProcessCameraProvider.availableCameraInfos` + `Camera2CameraInfo.from(info).cameraId` / `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` (+ `CameraSelector.Builder().addCameraFilter { ... }` to pick by physical id / `ZOOM_RATIO` for OEMs that hide ultra-wide/tele behind one logical camera; physical camera ids are an OEM-dependent rabbit hole per requirements). All four are UNVERIFIED on the owner phone; build the catalog to degrade to "one logical camera + zoom slider" (CameraX `CameraControl.setZoomRatio`) which covers the hidden-lens case.
- **Single source of binding truth**: one `CameraSession` object (androidMain) owning `ProcessCameraProvider`, current `CameraSelector`, `Preview`, `ImageCapture`, `Camera`, with `bind(lens)`, `switchLens(lens)`, `setZoom`, `setFlash`, `unbind()`. The dialog and `AndroidCameraProvider`/`AndroidCameraPreviewBinder` become thin clients — this removes the duplicated `DEFAULT_BACK_CAMERA` literals (3 places). **Disposition: Isolate via seam** (do not rewrite `AndroidCameraFrameSource`/CameraXBinder used by other features; the binder's no-`unbindAll` composability constraint at `AndroidCameraPreviewBinder.kt:18-24,52` must be preserved).
- **Lens metadata recording**: extend `PlatformImageFile`/`ImageSensorData` additively with `lensId: String?`, `lensLabel: String?`, `lensFocalLengthMm`, `lensZoomRatio` (nullable defaults); persist via the sidecar `SidecarImageAnnotation` (add nullable fields next to `focalLengthMm`, L53-56) — DB column optional (store inside existing JSON? `sensorData` has discrete columns, so a new column + MigrationRunner `AddColumn` is the established pattern for `focal_length_mm`; alternatively keep in sidecar only and treat DB as derived — Phase 3 decision, prefer sidecar-only unless a query needs it). EXIF stays authoritative for focal length; the recorded lens id is a cross-check that disambiguates `CameraProfile` matching (ADR-001 matching key currently make/model/focal — a lens id improves it).
- Capture-time lens metadata reaches calibration: `ImageImportService` (L139) already merges file EXIF with captured sensor snapshot; add the lens fields to the same merge; `CalibrationService` (L209-223) consumes `focalLengthMm`/`focal35mm` — pass `lensId` into profile lookup, no new calibration code path.
- Lifecycle caution (CLAUDE.md): the dialog must not pass `rememberCoroutineScope` into the session object; the session owns its own scope or is a `remember`-ed holder with executor-based callbacks (current code uses `ListenableFuture` listeners on the main executor — keep). Catch `Throwable` around bind (already done at `AndroidCameraPreviewBinder.kt:31`; the dialog catches `Throwable` at its bind too).
- Testing: AVD multi-camera is UNVERIFIED (open question 6). Architecture mitigation: keep lens selection logic (filtering/labeling a `List<CameraLensInfo>` into UI choices, zoom clamp, "no extra lenses" fallback) in a pure commonMain/androidUnitTest function fed by a fake catalog; the CameraX glue is the only device-only part (owner device run).

### D.3 Disposition (camera)

`AndroidCameraProvider` (224 lines) and the dialog: **Isolate via seam** (`CameraSession` + `CameraLensCatalog` port); `expect CameraViewfinderDialog`: **Extend as-is** (no signature change if possible; results ride on `PlatformImageFile`).

---

## E. Summary of dispositions Phase 3 must formalize

| Area | Disposition | One-line reason |
|---|---|---|
| `GitSyncService` (806 L, `sync()` ~240 L) | Isolate via seam | Add `RepoOpGate` + single-flight wrapper at public-method grain; no pipeline refactor (guards #676/#662) |
| Lock recovery (`removeStaleLockFile` x2 impls, `deleteLockFileIfStaleForRetry`) | Refactor-first (small) | age/mtime liveness is the root cause; replace with one owner-record `LockRecovery` before adding the gate |
| `NamedLocks`/`ColdSyncRunner` | Extend as-is | hoist to shared composition-root instance, add `tryLock`/`isHeld` |
| `AnnotationEditorViewModel` (1510 L) | Isolate via seam | new pure collaborators (tool-stickiness policy, value-edit, constraint solver); thin VM delegations |
| `AnnotationViewTransform`/`GestureLayer` | Extend as-is | lower scale floor, redefine Fit; policy stays out of the layer |
| Calibration model + sidecar | Extend as-is (additive JSON) | no new table; `ignoreUnknownKeys`; version only if meaning changes |
| Camera (`AndroidCameraProvider`, dialog, binder) | Isolate via seam | `CameraSession` + `CameraLensCatalog`; single binding owner |

## F. Open items for other Phase 2 agents / Phase 4
- UNVERIFIED: forced-cancel leaves/does-not-leave a lock (write the test); `/proc/<pid>` readability on the owner's Android version; any manifest `android:process`; whether UI tests assume default tool DISTANCE; whether hit radius scales with view scale; exporter ignores canvas gutter; whether owner phone exposes physical camera ids/focal lengths via Camera2 and whether EXIF `FocalLength` reflects the selected lens.
- The accuracy question (does constraint calibration beat single scale given prior art's error ranking?) belongs to the measurement-accuracy research agent; this doc only fixes the integration seams.
