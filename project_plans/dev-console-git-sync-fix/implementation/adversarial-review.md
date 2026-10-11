# Adversarial Review: dev-console-git-sync-fix

**Date**: 2026-10-10
**Verdict**: BLOCKED (initial); see "Re-review 2" at the end for the current verdict (CONCERNS, no blockers)

Reviewed `implementation/plan.md` against `requirements.md` and ADR-001..006. Code citations were checked against the working tree on `feat/cross-graph-phase2`. Items marked VERIFIED were opened or grepped; items marked UNVERIFIED are inferences.

## Blockers

- [ ] **B1. Task 2.4d / ADR-004 cold-worker abort is specified against code that does not match the plan, and it can lose edits or desync SAF graphs.**
  - The plan and glossary treat `GitRepository.abortMerge` as new, with a default `NotSupported` and a `MergeAbortResult` type. In fact `abortMerge(config): Either<GitError, Unit>` already exists as an abstract method (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt:69`), implemented in Jvm (`JvmGitRepository.kt:358`), Android (`AndroidGitRepository.kt:378`) and iOS. VERIFIED.
  - Both implementations run `reset --hard` (Jvm `doAbortMerge`, Android `doAbortMerge` at `:396`). That discards every uncommitted working-tree change. "Merge only if clean" appears in ADR-004 but no task enforces it (no pre-merge `status` assertion).
  - `sync()` step 5 (`GitSyncService.kt` ~249-270) commits when `hasLocalChanges`. It does not honor `GitConfig.autoCommit`: a grep for `.autoCommit` finds only the config repository and the diagnostics line. VERIFIED. If a cold worker commits and the user (or the app starting mid-run) writes a file between that commit and a later abort, the hard reset deletes the edit. The per-graph git lock covers only git operations, not file writes by a foreground process started mid-run. ADR-004 acknowledges the mid-run start and calls the lock "mitigation", which does not cover file writes.
  - The existing slow path says it deliberately uses a throwaway uninitialized `PlatformFileSystem()` because `fetch()` never touches the working tree (`WorkManagerSyncScheduler.kt` ~176-186, "the user's next foreground merge() call is what surfaces fetched changes into SAF"). The cold pipeline adds merge, abort and push. The plan has no SAF-shadow branch for any of them. For the two SAF graphs (and any SAF-backed active graph), the cold worker would merge and push in the shadow tree with no write-back, so the visible SAF tree and the pushed history diverge.
  - Recommendation: before 2.4d starts:
    - Rewrite 2.4d against the existing `abortMerge`.
    - Add a hard precondition: the tree must be clean (status check) or the run aborts to `Skipped`/`FetchedOnly` without a reset.
    - Add an explicit "SAF/shadow graph => cold worker stays fetch-only" rule, or a shadow write-back task.
    - Replace `abortMerge` with `merge --abort` semantics that keep uncommitted files.
    - Test the case where the tree is dirty at abort time.

- [ ] **B2. Unattended auto-commit + push can push conflict markers or half-merged state; the plan's gates do not cover repo state, only in-memory `SyncState`.**
  - `ScheduledSyncPolicy` blocks on "pending conflict" (Glossary/Story 2.4), but its inputs are `SyncState`, `EditLock`, `GitSyncBusyCounter` and a Settings marker (`git_pending_conflict_<graphId>`). The marker is not listed as a policy input (Glossary row `ScheduledSyncPolicy`). `SyncState` is lost on process death.
  - If a cold `abortMerge` returns `Left` (lock file, I/O error), or the process is killed between `merge` and `abortMerge`, the repo stays in `MERGING` with conflict-marker files in the tree. The next scheduled run calls `sync()`, step 5 sees `hasLocalChanges`, stages with `stageSubdir`, commits the marker-laden files and pushes them.
  - `sync()` has no `RepositoryState` guard before step 5. The existing manual path has the same latent hole, but scheduled sync turns it into an unattended push to the owner's only copy.
  - Recommendation:
    - Add a `repositoryState == SAFE` precondition (a refusal in `sync()` step 3 or in the policy) and make the marker an explicit policy input.
    - Add a pre-push scan refusing files that contain `<<<<<<<`/`>>>>>>>` markers.
    - Honor `autoCommit` as the requirements say ("auto-commit of local edits per `autoCommit`"), or reword the requirement. Today it is silently ignored.
    - Add a failure-matrix row for "abort failed".

## Concerns

- [ ] **C1. The sync fix hypothesis is still unverified and the plan does not gate expensive work on evidence.**
  - Unresolved Questions and "Plan branches" correctly say the evidence gates only "the decision to claim root cause". But Wave 3 starts Story 2.2 (default-branch detection, wizard rework, repair dialog; about 1.0M CU) and 2.3/2.4 regardless of what the device shows.
  - The cheapest decisive step is Story 1.1 (commit) plus one build plus the owner pasting the Git section. It is a wall-clock blocker, yet it is not on the critical path as a gate.
  - Branch 2 (configured=`master`, resolves, merged=0) has no remedy tasks. It says "prioritize 2.3b/2.3d, 3.3, then SAF shadow review via `git doctor`", but no task fixes a SAF shadow-tree, `wikiSubdir` filter or shallow-state cause. If the cause lands there, metric 1 stays unmet and the plan has nothing to run.
  - Branch 3 cites "fixed by 2.1d". 2.1d only changes ahead/behind in `doFetch`. Whether `hasRemoteChanges` gating is the cause is itself unverified.
  - Recommendation: add an explicit evidence gate between Epic 1 and Wave 3. Add a task slot (even a placeholder with acceptance criteria) for branch 2/3 remedies. Note that Epic 7.1 is the first point at which the "expected" branch is confirmed, and by then 2.2 is built.

- [ ] **C2. SQL read guard has no defined fallback on the primary dev platform.**
  - ADR-002 depends on a console-owned `query_only` connection. `DriverFactory.jvm.kt:143` has `createReadDriver` returning `null`, so the JVM desktop has no engine-enforced read connection today. VERIFIED.
  - The spike (0.2a) may conclude "dedicated connection required" without saying what ships. ADR-002 says classification is "the router, not the security boundary where an engine guard exists", which implies the classifier is the only boundary where none exists.
  - CTE writes (`with x as (...) delete ...`) are the plan's own test case, and its first keyword is `WITH`.
  - Recommendation: state fail-closed behavior. If no engine guard exists on a platform, the console `sql` read path is disabled there, or the statement must be re-parsed as SELECT-only with a rejected-on-doubt policy. Add a test on the real JVM driver.

- [ ] **C3. A console DB write can cascade into disk and then be pushed by scheduled sync.**
  - ADR-002 tags raw writes "DB-only, not written to markdown". But other writers (autosave, reconcile, `GraphWriter` observers) may serialize DB state back to markdown, and the new scheduled sync then commits and pushes it. The `delete from pages` and `update blocks` footguns therefore become repo-wide data loss on the remote, not just a local DB problem.
  - UNVERIFIED: whether invalidation after `DatabaseWriteActor.execute` triggers any file writer.
  - Recommendation: add an explicit test and ADR note proving that raw-write invalidation does not trigger `GraphWriter`. Also consider blocking scheduled sync (policy input) for N minutes after a console DB write, or until `graph reload` is run.

- [ ] **C4. The backup design leaves gaps.**
  - Backup occurs only "before the first write per session". A second, destructive write in the same session has no new snapshot, and only 3 are kept.
  - "Restore backup" appears in Story 6.3 UI but has no Epic 5 task. Restoring over a live pooled DB is nontrivial: close drivers, swap the file, and reconcile with markdown.
  - Backups are full copies of the notes DB plus whatever credentials tables hold. They are unencrypted in the app directory. UNVERIFIED: whether Android Auto Backup rules exclude `console-backups/`. No manifest `allowBackup`/`dataExtractionRules` match turned up in a quick grep of `kmp/src/androidMain/AndroidManifest.xml`; check the real manifest.
  - Recommendation: snapshot before every confirmed write (or a debounce), add a restore task, and exclude the folder from backup in the manifest and rules.

- [ ] **C5. The `sh` command defeats the `fs` deny-list and the redactor.**
  - The `fs` deny-list (`shared_prefs`, `.ssh`, keystore, vault files) is moot once `sh cat` can read the app-private directory as the same uid.
  - `ConsoleRedactor` is pattern- and exact-vault-secret-based. `substr()`, `hex()`, `base64`, line-wrapped output, or `sql select` over a "redacted-on-read" credential table with expression columns can all evade it.
  - The plan does not state that these are accepted limits, and "no credential output anywhere" in Risk Control is overstated.
  - Recommendation: document that the redactor is best-effort; keep the credential tables off the read allowlist rather than redacting columns; state that `sh` is outside the `fs` policy; keep `sh` Desktop-only unless the owner explicitly wants Android (ADR-005 already marks it optional).

- [ ] **C6. The plan duplicates an existing registry and mis-describes the worker's current state.**
  - `GitSyncServiceRegistry` (`WorkManagerSyncScheduler.kt:252`) already exists and the worker already has the "live service" fast path (`:137`). VERIFIED.
  - The plan introduces `ActiveGitSyncRegistry` in commonMain, with its own Story 2.4c/Glossary entry. Story 2.4c is really "swap `fetchOnly` for `runScheduledSync` at `:143`" plus a move to commonMain for the desktop timer.
  - This is a task-sizing error and a naming-collision risk. Rewrite 2.4c to extend or promote the existing registry.

- [ ] **C7. Several test-plan cites do not hold.**
  - `kmp/CLAUDE.md` is cited in the plan header; no such file exists. Root `CLAUDE.md` is the one.
  - Story 2.1b says "reuse `JvmGitRepositoryTest` helpers (`createBareOriginWithCommits`, `setIdentity`)". `createBareOriginWithCommits` is `private` at `JvmGitRepositoryTest.kt:620`. It must be extracted to a shared fixture; add that to the task's file list.
  - Story 2.3 "fast-forward merge of 2 commits" cannot happen through `merge()`. `doMerge` uses `FastForwardMode.NO_FF` (`JvmGitRepository.kt:273`), so every merge produces a merge commit. The `computeChangedGitRelativePaths` first-parent diff is already correct for merge commits. The plan's claim that "diff range is wrong" (2.3d) is unverified. A test should characterize current behavior first.
  - `GitSyncService.kt:238` (Story 0.1b) is the "no config" early return. It is not part of the unresolved-ref false-green path (that path is `hasRemoteChanges == false` falling through to push and `Success`). Spike 0.1b is aimed at the wrong line.

- [ ] **C8. Metric 4 and the interval claim are partly unsatisfiable as written.**
  - "Within one sync interval" cannot hold on Android for intervals under 15 minutes (WorkManager periodic minimum). The plan says scheduled sync "respects the existing auto-sync interval setting" without stating the floor.
  - Metric 4 requires disk == DB == remote for 14 days. The DB-only ghosts (2026-10-07/09/10) block it until the owner decides their fate. The plan lists this as an owner question but the soak in Story 7.1 does not say what to do when the ghosts are still present.
  - Add the 15-minute floor to the acceptance criteria and a branch for "ghosts undecided".

- [ ] **C9. Scope and phasing.**
  - The plan ships a 68-task console (completion engine, extra-keys row, history sheet, SQL grid, CSV export, TalkBack suite) behind the same release train as the sync fix. It does say "Epics 1-3 can ship before 4-6".
  - Not an issue of scale (full scope is the default) but of risk: Epic 3.3 edits a `StelekitViewModel` call site (the highest-churn hotspot) and Epic 3.1 touches `GraphLoader` (63 commits).
  - Recommendation: make the sync/journal PR and the console PR separate PRs by design, not just "can ship", so a console review problem cannot hold the sync fix.

## Minors

- Requirements metric 3 says results are exportable; Story 5.8 covers `export`, but "Web gets whichever subset is feasible" has no story. Fine to defer; say so.
- The 16-day `JournalDiff` window is configurable (default 14) but metric 4 is fixed at 14; align the defaults.
- `git set-branch` read-back is specified for the console path (5.2) and the dialog path (2.2d). Share one implementation to avoid two divergent writes.
- Cached figures: the plan's total of "68 tasks" matches `grep -c '^##### Task'` (68). CU totals were not recomputed here (UNVERIFIED).
- `BranchRepairDialog.kt` is cited as a file to create; it does not exist yet (expected). `RemoteDefaultBranch.kt`/`RemoteTrackingRef.kt` also new; no naming conflicts found.
- Verified cites that do hold: `doFetch` unresolved-ref logic at `JvmGitRepository.kt:~193` and `AndroidGitRepository.kt:~206`; `startPeriodicSync` at `GitSyncService.kt:652` (plan says `~649`); `WorkManagerSyncScheduler.kt:143,213`; `GraphLoader.kt` journal cap near `:1091-1100`; `GitMergeDiff.kt`, `GitWriteLockNaming.kt`, `GitSyncBusyCounter.kt`, `EditLock.kt`, `StubGitRepository.kt`, `GitTransportFaultInjectionTest.kt` all exist.


## Re-review (B1, B2 only; against repaired plan.md + amended ADR-004)

**Verdict: BLOCKED** (one remaining blocker, R1; B2 reduced to concerns). Scope: only B1/B2 re-checked. Symbols below were grepped or opened in the working tree on 2026-10-10.

### B1 status: mostly resolved, one data-loss path remains
Resolved (VERIFIED against plan Story 2.4, Tasks 2.4d-g, ADR-004):
- `abortMerge` reuse is correct: `GitRepository.kt:69` exists; Jvm `doAbortMerge` is `ResetType.HARD` (`JvmGitRepository.kt:376-380`). `MergeAbortResult` is gone.
- Clean-tree-only rule, in-core preflight (spike 0.1c), pre-merge marker, no automatic abort in the repair routine, SAF fetch-only (Task 2.4g), `autoCommit` task (2.4d; `sync()` step 5 at `GitSyncService.kt:262` still commits unconditionally today, so the task is needed and correctly scoped), registry reuse (`WorkManagerSyncScheduler.kt:252`, `register` unwired) all hold.
- The slow path still builds `AndroidGitRepository(context, PlatformFileSystem())` (`WorkManagerSyncScheduler.kt:173-183`); Task 2.4g correctly cites it.

**R1 (BLOCKER). ADR-004 step 5 still lets the cold worker hard-reset after a clean-at-start check, which can destroy edits made after the check.**
- Step 5 calls `abortMerge` on merge `Left` "because `cleanAtStart` held and the lock is still held". `cleanAtStart` is sampled at the start of the run; the git lock does not cover foreground/other-app file writes (ADR-004 line 27 says so itself). Between the `status` check and a failing `merge` (I/O error, checkout conflict, lock file) a user or Syncthing-style writer can modify a file. The most likely cause of a post-preflight `Left` is exactly such an overlapping write (JGit refuses to overwrite a dirty path), so the reset would delete the very edit that made the merge fail.
- This contradicts the plan's own invariant ("never rely on `abortMerge` to discard user edits", Risk Control) and the Story AC only tests "dirty at start", not "dirty at abort time".
- Fix (any one): (a) on merge `Left`, re-run `status`; if dirty, skip abort, keep the marker, `Skipped(RepairNeeded)`; (b) skip `abortMerge` entirely for a non-conflicting preflight-clean merge (a failed JGit merge without conflicts leaves no `MERGE_HEAD`; just clear/keep marker); (c) use a `ResetType.MERGE`/`KEEP` path instead of `HARD` for the cold runner. Add a test: edit file after status, force merge `Left`, assert the edit is byte-identical.

Residual B1 concerns (not blocking):
- **R1b.** SAF detection is a "path-mode predicate shared with `git doctor`" (Task 2.4g/5.2c) that is not defined anywhere in the plan. If it misclassifies a shadow graph as non-SAF, the cold worker merges in the shadow tree with no write-back, and the later foreground merge's changed-file diff will not include those files (the SAF tree stays stale permanently). Define the predicate from the same source `AndroidGitRepository` uses to pick shadow mode, and add a fail-closed default (unknown => fetch-only).
- **R1c.** Kill mid-checkout: the process dies after JGit rewrote some working-tree files but before the merge commit. Repository state is `SAFE` (no `MERGE_HEAD`), HEAD equals `preMergeSha`, tree looks dirty with remote content. The repair routine "SAFE => clear marker" then lets the next live `sync()` auto-commit that half-merged tree as user edits. No edit is lost and no markers are pushed, but the routine should compare HEAD to `preMergeSha` and flag `RepairNeeded` instead of silently clearing.

### B2 status: substantially resolved, concerns remain
Resolved: `ScheduledSyncPolicy` now takes persisted markers and repository state (glossary row, Task 2.4a, AC "simulated process death"); Task 2.3e adds a repository-state guard and marker scan before commit and push; matrix row (g) added; Task 2.4d honors `autoCommit`; failed-abort leaves the marker and blocks the next run (Story AC, Task 2.4f). `sync()` still has no such guard today (VERIFIED: steps at `GitSyncService.kt:262` and `:369` push/commit unguarded), so 2.3e is the real fix and is correctly sequenced before 2.4 in the dependency graph.

Remaining concerns:
- **R2. Marker scan scope is under-specified for push.** Task 2.3e says "a bounded scan of staged `.md` paths". At push time nothing is staged; a marker-laden commit that already exists (made earlier, or by another path) is not seen. Specify the scan as the added lines in `origin/<branch>..HEAD` (and staged paths at commit time), over all text files, not only `.md`. Also state the match rule (line-start `<<<<<<< `/`>>>>>>> ` pair, ignoring fenced code blocks) so a note that merely documents git syntax cannot wedge sync forever, and say what "bounded" means (size cap must fail closed, not skip the file).
- **R3. Other commit/push entry points are not covered by 2.3e.** `applyJournalMerge` pushes directly (`GitSyncService.kt:614`) and `resolveConflicts` commits (`:561`); neither is named. `commitLocalChanges` (`:479`) stages and commits with no guard; its only non-test caller is the desktop CLI (`jvmMain/.../cli/SyncMain.kt:191` has its own private function of the same name), so the exposure is small. List these in 2.3e: guard or document why exempt (they legitimately run in `MERGING`, so a marker scan, not the state guard, is the right check there).
- **R4. `git_pending_conflict_<graphId>` has no clear path.** The plan sets it (Task 2.4e) and reads it (policy, banner 2.4h) but no task clears it after the user resolves or aborts via the live UI. Result: scheduled sync can stay disabled indefinitely (liveness, not safety). Add clearing in `resolveConflicts` / `abortActiveMerge` success and on a clean `sync()`.
- **R5. User-initiated `abortActiveMerge` (`GitSyncService.kt:634`) is also `HARD`.** Pre-existing and explicit in the UI, so not part of this plan's regression, but it discards edits made while resolving. Worth a confirm-dialog note; out of scope for B1/B2.

### Answers to the adversarial questions
- Can user edits still be lost? Yes, via R1 only (abort after an unverified-clean window). Dirty-at-start, preflight-conflict, and failed-abort-then-next-run cases are covered.
- Can conflict markers still be pushed? Not via `sync()` once 2.3e lands, with the caveat that the scan is staged-only (R2) and `applyJournalMerge` is unguarded (R3).
- Can SAF graphs desync? Only if the SAF predicate is wrong (R1b).

### Required to reach CONCERNS/CLEAN
Amend ADR-004 step 5 and Story 2.4 to remove the unconditional hard reset (R1) with the stated test. R1b-R4 are plan edits of a sentence or task line each.


## Re-review 2 (R1, R1b, R1c, R2, R3, R4, R5; against repaired plan.md and ADR-004)

**Verdict: CONCERNS** (no remaining blockers). Symbols and lines were grepped or opened on 2026-10-10.

### Cited symbols (VERIFIED)
- `abortMerge` at `GitRepository.kt:69`; `abortActiveMerge` at `GitSyncService.kt:634` calls it (HARD reset, no dirty check).
- `shadowWorktreeFor` / `resolveForJGit` at `AndroidGitRepositoryShadow.kt:41,92`, with `internal` wrappers at `AndroidGitRepository.kt:538,545`. `shadowWorktreeFor` returns null for any non-`saf://` path, and `resolveForJGit` falls through to the raw `saf://` string when unresolvable. The Task 2.4g predicate (non-saf, null shadow, existing dir containing `.git`, exception => fetch-only) is therefore coherent and fails closed.
- Slow path still builds `AndroidGitRepository(applicationContext, PlatformFileSystem())` (`WorkManagerSyncScheduler.kt:173-183`).
- R3 entry-point list matches `grep gitRepository.(commit|push)(` in production sources exactly: `GitSyncService.kt:268,369,497,561,604,614`, plus `SyncMain.kt:155,207,271` (exempt) and only a doc mention in `WasmGitWriteService.kt:106`.

### Per item
- **R1 (was BLOCKER): RESOLVED.** ADR-004 step 5 no longer calls `abortMerge` anywhere in the cold runner. On merge `Left` it re-checks state and status and keeps the marker (`Skipped(RepairNeeded)`) if dirty or unsure. The only working-tree write left is the merge itself, and JGit refuses to overwrite a dirty path (INFERRED from JGit behavior, not run here; racy-git timestamp edge cases are the residual, and the late-edit test in Task 2.4f is the guard). The late-edit test (edit after status, forced `Left`, byte-identical, `abortMerge` count 0) is specified. The rejected-options list is sound.
- **R1b: RESOLVED.** See predicate above.
- **R1c: RESOLVED.** Step 6 compares HEAD with `preMergeSha`; SAFE + HEAD == `preMergeSha` + dirty => `RepairNeeded`, marker kept, no auto-commit. Case (c) (`MERGING` after `Left`) and (d) are handled without a reset.
- **R2: RESOLVED with one ambiguity (N2).**
- **R3: RESOLVED** for `GitSyncService`; see N1 for the cold path.
- **R4: RESOLVED.** Task 2.4i names three clear paths plus a launch-time revalidation, and tests Dismiss, failed resolve, and policy returning `Run`.

### Remaining concerns (non-blocking, one plan sentence each)
- **N1. The cold runner's push is not covered by Task 2.3e.** `ColdSyncRunner` calls `GitRepository.merge/push` directly, not `GitSyncService`, so the guards at `GitSyncService.kt:369` do not apply. A pre-existing local-ahead commit containing markers (producible by the exempt `SyncMain.kt` CLI path) could be pushed cold. Add to Task 2.4e: before the cold push call `repositoryStateSafe` and `findConflictMarkers`; on hit => `Skipped(RepairNeeded)`, no push.
- **N2. Scan range semantics.** Task 2.3e says "added lines of `origin/<branch>..HEAD`". State it as the net tree diff (`origin/<branch>` tree vs `HEAD` tree), not per-commit patches; per-commit patches would keep flagging a marker added in one commit and removed in the next, wedging sync forever. Also state behavior with no remote-tracking ref (new branch): scan all of HEAD's diff against the empty tree or fail closed.
- **N3.** Say whether a live `sync()` with `git_merge_in_progress` set (RepairNeeded) may still merge or push; today the text only forbids auto-commit.

### R5 (user `abortActiveMerge` is HARD): needs a task
Yes, add a small Task 2.4j (S). It is pre-existing, but this plan makes it reachable in a new way: ADR-004 case 5(c) and repair-routine `MERGING` hand a state to the existing conflict UI whose only abort is `abortActiveMerge` (`GitSyncCoordinator.kt:191`, `GraphDialogLayer.kt:323`), and the user may have edited files in the meantime. The cold runner itself now cannot discard edits, so this is not a blocker, but the guarantee "nothing the user typed is lost" ends at that button. Suggested scope: before `abortMerge` in `abortActiveMerge`, run `status` and, if any non-conflicted path is modified, require confirmation listing those files (or snapshot them to a backup ref/stash first); test that the snapshot restores byte-identical content. Without the task, record the exposure as an accepted risk in Risk Control.

### Final answers
- Can the cold runner discard user edits? No, by design (no reset, no checkout, merge-only writes).
- Can the cold runner push conflict markers? Only via N1 (a gap, narrow).
- Can the user lose edits after the repair? Only via R5.
