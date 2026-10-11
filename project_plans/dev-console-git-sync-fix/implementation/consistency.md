# Cross-artifact consistency: dev-console-git-sync-fix

> **Superseded by plan Phase 4 repair log, Triad repair 1 and Triad repair 2.** This review is historical; its counts and line references are stale. Each item's resolving task or log row (all in `implementation/plan.md`, "Phase 4 repair log" unless noted):
> B1 -> Task 2.2a2; B2 -> owner decisions (a)/(b), Story 2.4 and Story 7.1b; C1 -> Delivery table; C2 -> moot (spike 0.1c deleted); C3 -> Task 2.1g; C4 -> Task 2.4e (`Fetched`); C5 -> Task 2.1f; C6 -> Task 2.1g; C7 -> Task 4.6a / Story 6.1 (manifest `configChanges`); C8 -> requirements "Sync safety and visibility additions"; C9 -> Task 4.5b naming rows; C10 -> requirements In Scope edits and Story 5.5 (`SqlWriteCommandTest`); C11 -> Tasks 2.1a / 2.1f. NITPICK 1-10 -> the "NITPICK 1-10" row of the same log (item 8, `DbRestoreView` in ADR-001, was part of that row; not re-verified in this repair). Later reviews raised new items that are tracked in the plan's own logs, not here.


**Date**: 2026-10-10 | **Prompt**: `/home/tstapler/dotfiles/.claude/skills/sdd/skills/4-validate/cross-artifact-consistency-prompt.md` (the `~/.claude/commands/sdd/` path does not exist)
**Inputs read in full**: `requirements.md`, `implementation/plan.md` (1029 lines), `design/ux.md`, `decisions/ADR-001..006`.
**Result**: 2 BLOCKER, 11 CONCERN, 10 NITPICK.

Severity here follows the prompt: contradictions and coverage gaps are BLOCKER, scope drift and terminology are CONCERN, UX alignment is NITPICK. I raised or lowered a few where the impact clearly differed, and each says so.

## BLOCKER

**B1. `BranchRepairService` is called "ungated" but its only creating task is gated.** (plan Story 5.2 AC vs Story 2.2 / Task 2.2d)
Story 5.2 says "If Task 2.2d is no-go, `BranchRepairService` still ships (it is ungated)". Story 2.2 gates Tasks 2.2c-e on Branch 1. Task 2.2d is the only task whose Files list creates `BranchRepairService.kt`, and ungated Tasks 2.2a/2.2b do not mention it. On Branches 2-5, nothing builds it and the console `git set-branch` AC cannot be met.
Resolution: add an ungated task (for example 2.2a2) that creates `BranchRepairService` with its read-back test, and let 2.2d only consume it.

**B2. Requirements promise a scheduled full sync with auto-commit, but ADR-004 gives the Android background worker neither in the common case.** (requirements Decisions + Metric 1 vs ADR-004 cold path steps 1, 3 and the plan's Unresolved Questions)
Requirements: "Scheduled sync = full sync incl. push (fetch -> merge -> push, auto-commit of local edits per `autoCommit`)" and Metric 1 "a scheduled sync pulls `origin/master` commits". ADR-004: with no live graph, a dirty tree is `Skipped(Dirty)` (fetch only, never commits), and a SAF-backed graph is `FetchedOnly(Saf)`. A phone with any uncommitted edit, or a SAF shadow graph, therefore pulls nothing on schedule. The plan only asks the owner about cold-worker *conflict* behaviour, not about this dirty-tree and SAF limitation. Story 2.4's soak is satisfiable only "on a clean, conflict-free graph", which requirements Metric 4 does not say. Whether the owner's active graph is SAF-shadow is UNVERIFIED (Branch 2 of the evidence gate).
Resolution: amend requirements Metric 1/4 and the Decisions bullet to state the live-process vs cold-worker split, and ask the owner explicitly whether "scheduled pulls only while the app process is alive or the tree is clean" is acceptable.

## CONCERN

**C1. Delivery table puts all of Epic 0 in PR-A, but Story 0.2 belongs to PR-C.** (plan "Delivery" table vs dependency graph and effort table)
The effort table tags 0.2 as PR C and the per-PR subtotal for A (38 tasks) excludes its 3 tasks; the Delivery table says "Epic 0 (spikes + evidence gate)" for A. Resolution: change the Delivery row to "Stories 0.1, 0.3".

**C2. ADR-004 cites a spike that does not exist.** (ADR-004 step 4 "spike 0.1d" vs plan Tasks 0.1a/0.1b/0.1c) The in-core merge preflight spike is Task 0.1c. Resolution: fix the cite.

**C3. Same condition gets two different outcomes: red Error vs amber.** (plan Story 2.3 "Error(SyncInvariantViolated), not Success" and ux S1 vs plan Story 2.2 / ux S3, N3, UX-09, UX-70 "amber ... never a bare success")
"Remote still ahead after a sync" is an `Error` in 2.3c but an amber non-error line in 2.2d. Resolution: state one rule, e.g. amber is the rendering of `SyncInvariantViolated` when `behind > 0` after a repair sync, and give `SyncStatusBadge` the same mapping in both places. Because 2.2d is gated, the amber/invariant rendering must also land in ungated PR-A work.

**C4. `Saf` appears in two `ColdSyncOutcome` branches.** (plan glossary and ADR-004 last line `Skipped(Dirty|Conflict|RepairNeeded|Saf)` and `FetchedOnly(reason)` vs Story 2.4 and Task 2.4g `FetchedOnly(Saf)`) Resolution: drop `Saf` from `Skipped`.

**C5. New state and error types are used but not defined.** (plan Story 2.4 / Task 2.4i, Task 2.3e vs glossary, ADR-003, `SyncState.kt`)
- Plan says live `sync()` "returns the `RepairNeeded`/`ConflictPending` state". `SyncState` has `ConflictPending(conflicts)` but no `RepairNeeded` (grep of `kmp/src` found none outside the plan), and a marker-only conflict has no `conflicts` list to supply. No task adds the variant or updates the exhaustive `when` in `SyncStatusBadge`.
- Task 2.3e introduces `ConflictMarkersPresent(files)` / `ScanIncomplete`, but ADR-003 says "four `GitError` variants", the glossary lists four, and UX has no message or sheet for it.
Resolution: add `SyncState.RepairNeeded` (or reuse an existing state) and `ConflictMarkersPresent`/`ScanIncomplete` to the glossary, ADR-003, Task 2.1a's message test and a UX line.

**C6. UX badge variants for invariant, detached HEAD and empty remote have no ungated task.** (ux S1 table, S2 variants vs plan Task 2.1a)
Task 2.1a routes only `RemoteBranchNotFound` to a read-only sheet (UX-73). The "invariant" and "detached HEAD" sheet variants (doctor-style summary, Retry sync) appear only inside gated Task 2.2d's S2 variants, yet the errors ship in PR-A (2.3c). Resolution: extend 2.1a/2.3c with the read-only variants.

**C7. UX-35 (rotation keeps scrollback, running command, pending confirm) conflicts with the planned session host.** (ux S7/UX-35, plan Story 4.4/4.6 vs `remember{}`-hosted `ConsoleSession`)
Story 4.6 creates the session with `rememberConsoleSession(...)` modelled on a `remember{}` site. `kmp/src/androidMain/AndroidManifest.xml` has no `configChanges`, so Activity recreation on rotation would drop `remember{}` state unless the host is saved elsewhere (UNVERIFIED how `MainActivity` retains state; only the manifest was checked). Resolution: decide the retention mechanism in Task 4.6a (activity-scoped holder) and add a rotation test.

**C8. Scope the plan adds that requirements.md never states.** (plan Tasks 2.3e, 2.4e-j, 4.1c, 5.5e, 5.5f, ADR-002 restore vs requirements In Scope)
Requirements ask for a "pre-write DB backup/checkpoint" and a regression-tested sync fix. The plan adds conflict-marker scanning, a cold-merge marker/repair protocol, user-abort file snapshots, `graph restore-backup`, Auto Backup exclusion, and a `SyncMarkerStore`. They come from the plan's reviews, not the owner. Resolution: add them to requirements In Scope (or Risk Control) so validation maps tests to requirements.

**C9. One flag, three names; one dialog, three words.** (requirements "developer-mode toggle/flag", ux "Developer mode" vs plan glossary `DevConsoleEnabled` / key `dev_console_enabled` / file `DeveloperMode.kt` / test `DeveloperModeGateTest`; ux `BranchRepairDialog` "bottom sheet" vs plan "dialog", "sheet", "repair sheet")
Resolution: pick "developer mode" for the user-facing term and one code name for the setting and gate class; call the surface "branch repair sheet" everywhere.

**C10. Requirements with thin or no story.** (requirements In Scope vs plan)
- "`git` ... raw JGit-backed subcommands": Story 5.2 lists status/log/refs/remote/ls-remote/doctor/fetch/merge/set-branch only. Either add it or strike it from requirements.
- ADR-002 says writes are refused when no read-only connection exists (needed for the dry-run count, UX-55). Story 5.5 has no AC for that refusal.
- Requirements "Persisting console history ... unless opted in": plan makes history in-memory only with no opt-in. Fine, but say so in requirements.

**C11. Message text differs between UX and plan.** (ux S1 `Remote is empty — push a commit first or check the URL`, `Sync finished but your phone is missing remote changes — tap for details` vs plan Story 2.1/ADR-003 "Remote is empty"; Task 2.1a test "asserts all four messages" without giving the invariant/detached text) Resolution: make Task 2.1a's test assert the UX strings.

## NITPICK

1. requirements Open Questions 5 (base branch) and 6 (Android shell) are answered in Decisions/ADR-005 but still listed as open.
2. requirements say ls-remote/fetch are "bounded by `GIT_TRANSPORT_TIMEOUT_SECONDS`" (const is 300 s, `GitOperationSupport.kt:465`); plan Task 2.2a uses a 15 s ls-remote timeout and Story 5.2 the same. Name the intended bound.
3. Plan Story 6.3 classification lists `graph *` and `git set-branch` but not `git merge` or `settings set` (ux S8 tier table and Story 5.6 do). Add to the table.
4. `ScheduledSyncPolicy` skip reasons (`EditingInProgress`, `Busy`, `VaultLocked`, `ConflictPending`, `MarkerPresent`, `RepoState`) are not in the glossary.
5. ux S9 `sh --help` text, S10 `RUNNING (partial)` marker, S12 "Back returns to the sync sheet's origin screen", S6 "read failure defaults to off", UX-68 80-column limit, and "respect reduce-motion/font scale" have no AC or task.
6. ux G8 "Proposed resolution" banner text ("hit a conflict and was undone") differs from the final UX-72 text; the Plan-resolution column is correct.
7. Story 5.2 says a git write already in progress "waits or refuses"; UX-43 requires "sync in progress" and no concurrency. Pick one.
8. ADR-001 lists `ConsoleContext` views without `DbRestoreView`, which ADR-002 and Story 4.6 add. Add it to ADR-001.
9. Requirements Metric 4 "on remote" has no separate verification step beyond `behind=0`; acceptable, but say so.
10. Requirements call the console shell an "in-process interpreter"; plan/ADR-005 deliver a tokenizer plus command dispatcher with no pipes or globbing. Reword to avoid implying shell semantics.

## Checks that passed

- **Coverage by metric**: Metric 2 (2.1, 2.3), Metric 3 (Epics 4-6, 7.2), commit-diagnostics (Epic 1), push/shallow/wikiSubdir verification (2.3, 2.5), journal indexing (3.1-3.3), observability (Observability Plan), and Risk Control (flag, confirm, backup, 2nd shell confirm) all map to a story. Plan "Requirements traceability" matches.
- **Counts recomputed**: `grep -c '^##### Task'` = 91 and `grep -c '^#### Story'` = 35, matching the repair log. Per-PR task totals (38 + 9 + 41 + 3) = 91 by my own tally.
- **Cites sampled and VERIFIED against current source** (`kmp/src`): `GitSyncService.kt` 687 lines, `:238` no-config `Success(0,0)`, `:268,369,497,561,604,614` commit/push sites, `:634` `abortActiveMerge`, `:652` `startPeriodicSync`; `AndroidGitRepository.kt:206-207` (`repo.resolve(...)`), `:287` NO_FF, `:375` `abortMerge`, `:538,545` shadow helpers; `JvmGitRepository.kt:193,273,358`; `GitRepository.kt:69`; `GitConfig.kt:22 autoCommit`; `WorkManagerSyncScheduler.kt:137,143,173,183,213,252`; `GraphContentGitSyncSetup.kt:43`; `DriverFactory.jvm.kt:143` and `ios.kt:17` return null, `android.kt:174` exists; `DatabaseWriteActor.kt:761`; `DomainError.kt:71,320`; `GitOperationSupport.kt:475` and 521 lines; `JvmGitRepositoryTest.kt:103,620` private; `AndroidGitRepositoryShadow.kt:41,92`; `SettingsDialog.kt:466`; `AppState.kt:46,52` `@HelpExempt`; file sizes of `GraphManager` 1601, `StelekitViewModel` 2358, `GraphLoader` 1970. `GitSyncServiceRegistry.register` has no production caller (only `WorkManagerSyncSchedulerRetryOwnerTest.kt:115`), as ADR-004 claims. Paths `ui/GitSyncCoordinator.kt`, `ui/screens/JournalsViewModel.kt`, `ui/components/LogDashboard.kt`, `commonTest/.../git/testsupport/StubGitRepository.kt`, `GitSetupStep4Branch.kt` exist.
- **Not verified**: `GraphLoader.kt:1091-1100` points at `loadRemainingJournals` (plausible for ADR-006) but the `skip=10, take=20` and `loadJournalsImmediate(10)` values were not opened; `project_plans/commonmain-bazel-target-split` exists but its content was not read.
- **ADR vs plan**: ADR-001..006 decisions match plan text apart from C2, C4, C5 and N8 above.
