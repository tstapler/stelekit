# ADR-003: Shared remote-ref resolver, typed `RemoteBranchNotFound`, explicit push refspec

**Status**: Accepted | **Date**: 2026-10-10 | **Amended**: 2026-10-10 (Phase 3 repair; Phase 4 repair: variant list extended)

## Context
`doFetch` (Android `:206`, JVM `:193`, byte-identical) treats an unresolved `<remote>/<remoteBranch>` as "no changes" and `sync()` ends in green `Success(0)`. `doMerge` already errors. `doPush` uses no refspec, so push and fetch can target different branches. `GitConfig.remoteBranch` defaults to `main` in four places; clone follows the remote HEAD.

## Decision
- One resolver in `jvmCommonMain` (`RemoteTrackingRef.kt`): `exactRef("refs/remotes/<remote>/<branch>")` after `Repository.isValidRefName` (never `repo.resolve` in correctness paths), returning `Either<GitError.RemoteBranchNotFound(remote, branch, available), ObjectId>`. Used by `doFetch` and `doMerge` on both platforms; classes are not merged (auth/shadow differences are legitimate).
- Fetch sets `setRemoveDeletedRefs(true)` so a renamed remote branch cannot leave a stale local tracking ref that resolves.
- `hasRemoteChanges` = remote tip not an ancestor of HEAD (RevWalk), not OID inequality. `remoteCommitsMerged` = commits reachable from HEAD-after but not HEAD-before.
- `doPush` uses `refs/heads/<localBranch>:refs/heads/<remoteBranch>`; detached HEAD stays `DetachedHead`.
- Post-sync invariant in `GitSyncService.sync`: remote tip is an ancestor of HEAD and HEAD is on a branch, else `SyncState.Error(SyncInvariantViolated)`.
- `RemoteBranchNotFound` is non-retryable (deterministic; must not burn rate-limit budget).
- Empty remote and malformed ref names are typed too: `GitError.RemoteEmpty` (remote has no branches; "Remote is empty") and `GitError.InvalidRefName(name)` (config corruption, distinct from "branch absent on remote"). `fetch` threads `DefaultBranchDetection.EmptyRemote` through to `RemoteEmpty`; `toSyncErrorMessage` and `SyncStatusBadge` are exhaustive over all new variants (the guard variants are added by Tasks 2.1f/2.3e/2.3f).
- Retry classification lives in `GitOperationSupport.kt` (`runGitTransportOp*`); the single predicate there marks the new variants non-retryable (`GitTransportRetryState.kt` is only a state holder).
- Merge behavior is characterized, not assumed: `doMerge` uses `FastForwardMode.NO_FF` (`JvmGitRepository.kt:273`, `AndroidGitRepository.kt:287`), so every merge with remote changes creates a merge commit; the earlier claim that the merge diff range is wrong is UNPROVEN until Task 2.3d's characterization test runs.
- Config repair is confirmed in UI and written via `GitConfigRepository.saveConfig`, then read back. No schema change, no startup network call.

## Alternatives rejected
Fix only the Android copy (JVM twin and shallow-clone helper share the bug); detect at DB-open time (network at startup, per-device side effects); silent auto-correct (violates Risk Control).

## Consequences
Adds seven `GitError` variants (`RemoteBranchNotFound`, `RemoteEmpty`, `InvalidRefName`, `SyncInvariantViolated`, and from Tasks 2.3e/2.3f `ConflictMarkersPresent(files)`, `ScanIncomplete`, `MassChangeBlocked`) and one `SyncState` variant (`RepairNeeded`, returned for a non-SAFE repository state); the exhaustive `when` in `toSyncErrorMessage` and `SyncStatusBadge` forces handling. `SyncInvariantViolated` renders amber on the badge when the remote is still ahead after a sync and every other error renders red (one mapping, Task 2.1g). User-facing strings are those in `design/ux.md` S1 and are asserted verbatim by Task 2.1a/2.1f tests. `GitOperationSupport.kt` (521 lines) is not grown; new helpers go in sibling files.
