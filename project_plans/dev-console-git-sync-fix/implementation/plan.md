# Implementation Plan: dev-console-git-sync-fix

**Feature**: Fix the silent git-sync no-op (master-vs-main), make sync a real full sync while the app is open (background work with the app closed is fetch-only plus a staleness indicator), repair journal visibility, and add an embedded developer console (sql/git/fs/settings/logs/graph/diag/sh) so on-device issues can be diagnosed without a rebuild.
**Date**: 2026-10-10 (Phase 3 repair, Phase 4 repair, Triad repair 1, Triad repair 2, Triad repair 3 and Triad repair 4 (minor) applied same day; see "Repair log", "Phase 4 repair log", "Triad repair 1", "Triad repair 2", "Triad repair 3" and "Triad repair 4" at the end)
**Status**: Phase 4 and triad (product/UX/engineering) findings repaired; ready for implementation
**Base branch**: `feat/cross-graph-phase2` (owner decision)
**ADRs**: [ADR-001 console architecture](../decisions/ADR-001-console-architecture.md), [ADR-002 SQL guard + write path](../decisions/ADR-002-sql-guard-and-write-path.md), [ADR-003 sync ref resolution + typed error](../decisions/ADR-003-sync-ref-resolution-and-typed-error.md), [ADR-004 scheduled sync: full while open, fetch-only in the background](../decisions/ADR-004-scheduled-full-sync.md), [ADR-005 shell](../decisions/ADR-005-shell-in-process-first.md), [ADR-006 lazy older journals](../decisions/ADR-006-lazy-older-journals.md)

Path legend: `...` = `kotlin/dev/stapler/stelekit`. All paths are under `kmp/src/` unless absolute. Test source sets follow the repo-root `CLAUDE.md` rules (there is no `kmp/CLAUDE.md`): pure logic in `commonTest`; git/JGit in `jvmTest`/`businessTest`; Compose behavior in `androidUnitTest` Robolectric, not `jvmTest`. Before any `jvmTest`/`jvm_tests` UI run use `scripts/jvm-display-check.sh`.

## Delivery: five separate units (owner decision; PR-A split per pre-mortem #8)

| Unit | Contents | Ships | Depends on |
|---|---|---|---|
| **PR-A1** | Stories 0.1 and 0.3 (spikes + evidence gate), Epic 1 (diagnostics commit + build stamp), Epic 2 Stories 2.1, 2.2, 2.3 and 2.5 (typed error + resolver + `doFetch` fix, branch detection/repair, push/invariant/mass-change guards, conditional remedies). **Includes the first-sync review sheet, the `git_first_sync_confirmed` consent flag and the manual `Sync now` (Task 2.2d0)**, because the repair flow ends in that review: an A1-only build must never show `Review first sync` without a sheet behind it | **First**; ships the primary success metric on its own. "Done" means Story 7.1 passed on the owner's device, not merged-green | none |
| **PR-A2** | Story 2.4 (scheduled full sync while the app is open, fetch-only background, staleness indicator, scheduled-policy enforcement of the first-sync consent, notification entry to the review; the review sheet and consent flag themselves are A1's Task 2.2d0) | **Build may start** once A1's code is merged and its interim evidence (Task 7.1c, labelled `INTERIM (not device)`) is green; **release is gated on Story 7.1** having passed on the owner's device (one rule everywhere: A2 may BUILD after A1 merge plus Task 7.1c, and may RELEASE only after Story 7.1 passes). Two owner wall-clock blockers sit on A2: (1) Story 7.1 (device pass; gates release) and (2) Task 7.1d (threshold calibration on the owner's graph; time-boxed, see the task). | A1 (typed errors, Task 2.2a2, Task 2.2d0 review sheet and consent flag, Task 2.3e/2.3f guards) |
| **PR-B** | Epic 3 (journals: `JournalLazyLoader`, diff/repair services, index status) | After A1 or in parallel (touches `GraphLoader`/`GraphLoaderPort`, which A1 does not except one WARN) | A1's WARN task 2.3d for the `GraphLoader.kt` serialization point |
| **PR-C** | Story 0.2 (SQLite spikes) and Epics 4-6 (console core, commands, UI) | Last; ships dark behind the developer-mode toggle | A1 (typed errors, `git set-branch` via Task 2.2a2), B (`journals-diff`/`index-status` verbs) |
| **Device verification** | Epic 7 (owner wall-clock) | After each unit's build is installed | A1 for 7.1; A2 for 7.1b; PR-C for 7.2 |

A console review problem cannot hold the sync fix: nothing in PR-A1 or PR-A2 imports `console/`. A2 cannot hold A1: A1's code lands before Story 2.4 starts building; A2's release (not its build) waits for Story 7.1. Task 0.3a is time-boxed: if the owner has not returned device evidence after one device cycle, Branch 1 (the safe default) is taken and Tasks 2.2c-e proceed. If the device pass (Story 7.1) is also delayed past that cycle, PR-A1 goes to review on the interim evidence of Task 7.1c, labelled `INTERIM (not device)`; M1/M4 stay open.

**Roadmap order** (requirements "Roadmap Fit"): release order is A1 -> A2 -> B -> C. B may be built in parallel with A1 but is not released before A2; C is last.

**Commit-order rule for the six diagnostics files.** The working tree today holds uncommitted edits to `GraphDiagnostics.kt`, `GitRepository.kt`, `AndroidGitRepository.kt`, `JvmGitRepository.kt`, `GraphContentCameraCapture.kt` and the untracked `GitRefDiagnostics.kt`, which Tasks 1.1d, 2.1c, 2.1f, 2.2a, 2.3x and others edit again. **Tasks 1.1a-c land first**, before any other task touches those files; every commit in this plan stages files **by name** (never `git add -A` / `git add .`), because the tree also holds unrelated untracked files (`benchmarks/history/2026-06-21_17h36m29s_295fd1afab.json`, the `project_plans/` docs) that must not ride along.

---

## Creative pass (Step 0.5): approaches considered

| # | Approach | Strength | Weakness |
|---|----------|----------|----------|
| A | Patch `doFetch` in both classes, keep diagnostics-per-question, no console | Smallest diff | Rejected by owner: every question needs a rebuild; duplicate fix sites remain |
| B | **Shared resolver + typed error + full-power in-process console (chosen)** | Fixes root cause once, shrinks duplication, console reuses the same ports so it can never disagree with sync | Largest scope; console is a security-sensitive surface |
| C | Console as debug-only socket inspector (Stetho/DevTools style) | Rich tooling for free | Network-exposed, needs adb/host; out of scope per requirements |

Chosen B. Within B, sub-alternatives are recorded in the Pattern Decisions table (cold-worker sync, SQL guard, shell).

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `RemoteBranchNotFound` | `DomainError.GitError` variant: configured `<remote>/<branch>` has no tracking ref after fetch; carries `remote`, `branch`, `available: List<String>` | New; non-retryable; exhaustive `when` in `toSyncErrorMessage` |
| `RemoteEmpty` | `GitError` variant: remote has no branches ("Remote is empty — push a commit first or check the URL") | New; replaces a stringly-typed `FetchFailed` |
| `InvalidRefName` | `GitError` variant: configured remote/branch fails `Repository.isValidRefName` (config corruption) | New; distinct from "branch absent on remote" |
| `SyncInvariantViolated` | `GitError` variant: post-sync check failed (remote tip not ancestor of HEAD, or HEAD detached) | New; turns false-green into `SyncState.Error`. **Rendering rule (one mapping, Task 2.1g)**: when the cause is "remote still ahead after a sync" the badge renders amber ("incomplete"), every other error renders red; amber is the rendering of this error, not a second state |
| `ConflictMarkersPresent` / `ScanIncomplete` / `MassChangeBlocked` | `GitError` variants from the push/commit guards: markers found in named files; the marker scan hit its size cap (fails closed); deletions exceed the mass-change threshold | New (Tasks 2.1f, 2.3e, 2.3f); non-retryable; exhaustive `when` in `toSyncErrorMessage` and `SyncStatusBadge` |
| `SyncState.RepairNeeded` | New `SyncState` variant returned by `sync()` when the repository state is not SAFE (`MERGING`, unmerged paths) before any stage/commit/merge/push | Task 2.1f; distinct from `ConflictPending(conflicts)` because an interrupted merge has no `conflicts` list; detail sheet routes to the existing conflict/abort UI |
| `RemoteTrackingRef` | Resolved `refs/remotes/<remote>/<branch>` object id, produced only by `resolveRemoteTrackingRef` | jvmCommonMain helper; never `repo.resolve` |
| `DefaultBranchDetection` | Sealed: `Detected(name)` / `Ambiguous(candidates)` / `EmptyRemote` / `Unreachable(cause)` | Result of ls-remote symref detection; ambiguity is never auto-guessed |
| `BranchRepairProposal` | UI model: `from`, `to`, `available`; user confirms before `saveConfig` | Console twin: `git set-branch`; both call one shared `BranchRepairService` (Task 2.2a2, ungated); the UI surface is called the "branch repair sheet" everywhere (`BranchRepairSheet`) |
| `ScheduledSyncPolicy` | Pure decision for a scheduler tick: `Run` / `FetchOnly(reason)` / `Skip(reason)` | Inputs: online, vault, `EditLock`, `GitSyncBusyCounter`, backoff, in-memory `SyncState`, repository state (git's own `MERGING` state survives process death, so no private markers are needed), graph path mode (`isAppOwnedPath`), and the persisted first-sync confirmation. `Skip` reasons: `EditingInProgress`, `Busy`, `VaultLocked`, `ConflictPending`, `RepoState`, `Backoff`, `Offline`, `FirstSyncUnconfirmed`. `FetchOnly` reasons: `Saf`, `AppClosed` |
| `FirstSyncUnconfirmed` | Policy state: scheduled sync is blocked until one manual, previewed sync has succeeded for the current `(remote, branch)` | Persisted as `git_first_sync_confirmed_<graphId>` = `<remote>/<branch>`; changes with the branch, so a repair re-arms it (flag and review: Task 2.2d0 in A1; scheduled enforcement: Task 2.4h in A2); the repair therefore never syncs by itself, it opens the previewed first-sync review and the user's `Sync now` tap is the consent (Tasks 2.2d0, 2.2d) |
| `SyncStaleness` | Persisted per graph: `lastMergedAt`, `lastFetchedAt`, `behindCount`, last-10 background outcome history | Settings key `git_sync_staleness_<graphId>`; drives the staleness chip, notification, `diag` and `git doctor` (Task 2.4f) |
| `BuildStamp` | `shortSha builtAt appVersion` header line in diagnostics and `diag` | Task 1.1d; `unstamped` when unavailable, and Task 0.3b refuses to select a branch on an unstamped export |
| `DbOnlyRowsGuard` | Precondition of `graph reload`, `graph reindex` and `graph restore-backup`: runs `JournalDiffService`, lists DB-only journals and `ConsoleDbDirty` tables, exports their content to `console-recovery/` first, then requires a typed confirm | Task 5.6d |
| `GitSyncServiceRegistry` | **Existing** process-wide lookup of the live `GitSyncService` per graph (`WorkManagerSyncScheduler.kt:252`); promoted to commonMain unchanged; `register` gets its first production caller | Replaces the invented `ActiveGitSyncRegistry` (deleted from the plan) |
| `ColdSyncOutcome` | Sealed result of a worker run with no live graph: `Fetched(behindCount, reason)` / `Error(cause)`; `reason` is `AppClosed` or `Saf`, the single place `Saf` appears | ADR-004; the cold runner only fetches, it never merges, commits, pushes or resets |
| `MergedCommitCount` | Commits reachable from HEAD-after but not HEAD-before | Replaces `countRemoteCommitsBestEffort` as source of `remoteCommitsMerged` |
| `JournalLazyLoader` | New `db/JournalLazyLoader.kt` seam implementing `loadJournalsOlderThan` / `ensureJournalLoaded`; `GraphLoader` delegates | Constructor: `FileRegistry`, page lookup repository, parse-and-save function reference |
| `JournalDiff` | Data: counts plus a **bounded sample (<=50 dates per group)** of `diskOnlyRecent`, `dbOnlyRecent`, `diskOnlyOlder`, `dbOnlyOlder`, `inBoth` for a date window | `recent` = last 14 days; `older` = "not loaded by design"; the service never holds an O(graph) list in a result |
| `JournalRepairPlan` | List of DB-only journal dates to write to disk via `GraphWriter` | Requires owner confirm |
| `IndexStatus` | `unloadedCount` (`countUnloadedPages`), `indexJobActive`, `lastCompleteAt`, `lastError` | Owned by `BackgroundIndexSupervisor`, not `GraphLoader` |
| `ConsoleCommand` | Interface: `name`, `summary`, `risk`, `requires`, `run(args, ctx, out)` | `console/` package; not the editor `Command`; a contract test fails if a command omits `risk` |
| `Risk` | Enum `READ` / `NETWORK` / `STATE_CHANGE` / `DB_WRITE` / `EXEC` | Drives confirm tier |
| `Capability` | Enum `GIT` / `SQL` / `FS` / `PROCESS` / `SETTINGS` / `GRAPH` | Absent => command listed as disabled with reason |
| `ConsoleContext` | Immutable bundle of nullable capability views | Never holds `GraphManager` |
| `ActiveGraphView`, `GitConsoleView`, `SqlConsoleView`, `DbRestoreView` | 3-6 method interfaces adapting `GraphManager`/`GitRepository`/driver | Re-resolved per run |
| `ConsoleOutput` | Sink: `line`, `table`, `progress`, `truncated`; the only path to the scrollback | Redaction applied here |
| `ConsoleRedactor` | Single-sink **best-effort** scrubber: URL userinfo, token patterns, PEM blocks, exact vault secrets | Applied to output, history, export, audit log; not a guarantee against transformed output (`substr`, `hex`, base64) |
| `ConsoleSession` | Owns scope, history, ring buffer, running `Job`, pending inline confirm, shell-armed state | In-memory only |
| `ConsoleError` | Sealed: `Usage`, `Denied`, `Unsupported`, `Cancelled`, `Timeout`, `Failed(cause)` | Never leaks a raw exception |
| `ScrollbackBuffer` | Ring buffer capped at 5,000 lines and 2 MB, drop-oldest with visible marker | Pure, `commonTest`-tested |
| `StatementClass` | Sealed: `Read` / `Write` / `Denied(reason)` / `Invalid(reason)` for one SQL statement | Router only; never the security boundary |
| `SqlConsoleReader` | Console-owned read-only connection per platform; absent => `sql` reads disabled (fail closed) | ADR-002 A1 |
| `SqlConsoleWriter` | Injects `RestrictedDatabaseQueries`; one private raw-exec function carries function-level `@OptIn(DirectSqlWrite::class)` inside `actor.execute` | ADR-002 A2 |
| `DbBackup` | Snapshot (`VACUUM INTO`, fallback checkpoint+copy) per write session and per destructive statement class; last 5 kept | `console-backups/` in app dir; unencrypted; excluded from Android backup |
| `ConsoleDbDirty` | Session list of tables/pages touched by console DB writes | Surfaced by `diag`; cleared by `graph reload` |
| `DeveloperModeEnabled` | Settings key `developer_mode_enabled` (Boolean, default false; user-facing term "developer mode") | Checked in dispatcher, not only nav; a read failure counts as off |
| `ProcessRunner` | Platform seam to run an argv; JVM desktop only, `Unsupported` elsewhere | ADR-005 amendment |
| `ConfirmTier` | Mapping `Risk` -> UI confirmation (none / inline / modal+dry-run / second-per-session) | Epic 6.3 |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Remote ref lookup | Shared helper returning `Either` (Gateway-style) in jvmCommonMain | PoEAA | Per-platform copy-paste fix | Byte-identical `doFetch` is why the bug exists twice (ADR-003) |
| `RemoteBranchNotFound`, `RemoteEmpty`, `InvalidRefName`, `SyncInvariantViolated`, `ConsoleError`, `StatementClass`, `DefaultBranchDetection`, `ColdSyncOutcome` | Sealed types, exhaustive handling | type-driven-design | String errors / booleans | Compiler forces every UI/`when` site to handle new states |
| Branch name, remote name | Validated once in the resolver; invalid => `InvalidRefName` | type-driven-design | Raw `String` interpolated into ref names | `release/x`, empty, or injected names must fail at parse, as a different fault from "absent on remote" |
| Scheduled sync gate | Strategy-like pure `ScheduledSyncPolicy` reading repository state and the persisted first-sync confirmation | GoF | Inline `if`s in worker/timer; in-memory state only | One unit-tested rule for both platforms that survives process death |
| Cold worker vs live service | Existing `GitSyncServiceRegistry` lookup; live process => `runScheduledSync` (full), no live process => fetch-only `ColdSyncRunner` that records `SyncStaleness` | GoF/PoEAA Registry | A cold merge/commit/push path (preflight, markers, repair routine) | Owner decision (a): a background job never touches the working tree, so the dirty-tree, stale-sample and kill-mid-merge hazards cannot occur there (ADR-004); one registry only |
| Console commands | Command + Registry (injected list) | GoF | Reuse editor `CommandRegistry` | Palette leakage; not argv-shaped (ADR-001) |
| Console capabilities | Ports/Adapters, narrow views | Hexagonal | Pass `GraphManager` | Avoid god-object; wasm passes `null` |
| Console state | Session object + `StateFlow` snapshots | PoEAA Service Layer | State in `StelekitViewModel`/`AppState` | ViewModel is the highest-churn hotspot |
| Output | Single sink with Decorator-style best-effort redaction | GoF | Per-command redaction | One place to prove what is and is not scrubbed |
| SQL read guard | Engine-enforced read-only connection, fail closed | stack/build-vs-buy | Regex/keyword guard or classifier fallback; parser lib | The classifier is never the boundary (ADR-002 A1) |
| SQL write | Unit of Work through `DatabaseWriteActor.execute`, function-level opt-in | PoEAA | Side connection; class-level opt-in | Serialization + invalidation; CLAUDE.md-approved pattern |
| Shell | In-process interpreter + desktop-only gated `ProcessRunner` | ADR-005 | Bundled busybox; Android `sh` | `sh` bypasses the fs deny-list |
| Journal lazy-load | `JournalLazyLoader` seam with `GraphLoader` delegating | Isolate via seam | Logic inside `GraphLoader` | 1970-line, 63-commit hotspot |
| Journal repair | Service over `GraphWriter` (existing DB->disk path) | PoEAA | Raw `FileSystem` write | Watcher self-write suppression |
| Background index restart + status | `BackgroundIndexSupervisor` (new small class in `db/`) | Isolate via seam | Edit `StelekitViewModel`/`GraphLoader` internals | Both are hotspots |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `AndroidGitRepository.kt` / `JvmGitRepository.kt` (`doFetch`/`doMerge`/`doPush`) | Highest churn in area (27/13 commits); byte-identical `doFetch`; fetch and merge disagree on unresolved ref | **Isolate via seam** (small refactor-first) | Story 2.1 extracts `RemoteTrackingRef.kt` before behavior changes; both classes call it; classes not merged |
| `git/GitSyncService.kt` (687 lines) | Long linear pipeline; false-green `Success`; `autoCommit` never read; no repository-state guard before commit/push | **Extend as-is** | Typed errors route through the existing Left edge; scheduling goes in `runScheduledSync`/policy, never inside `sync()` branches; three small guards added (Tasks 2.3e, 2.3f, 2.4d) |
| `jvmCommonMain/.../GitOperationSupport.kt` (521 lines) | Near 500-line guideline | **Extend as-is**: one predicate line (non-retryable variants); new helpers in sibling files | `RemoteTrackingRef.kt`, `RemoteDefaultBranch.kt` |
| `db/GraphLoader.kt` (1970 lines, 63 commits) | Hotspot; journal cap; reconcile failure skips `onFullyLoaded` | **Isolate via seam**; the complete edit list is: (1) Task 3.1a **adds** `loadJournalsOlderThan` to `GraphLoaderPort` and `GraphLoader` as a new one-line method delegating to `JournalLazyLoader` (neither method exists today, so these are additions, not overrides of existing behavior); (2) Task 3.1a likewise **adds** `ensureJournalLoaded`; (3) one WARN in `reloadFiles` at the `?: continue` (Task 2.3d, PR-A1); (4) try/finally on the reconcile catch that still invokes `onFullyLoaded()` and starts indexing (Task 3.3b); (5) one new defaulted parameter `onDegraded: (Throwable) -> Unit = {}` on `loadGraphProgressive` (`GraphLoader.kt:~595`, `GraphLoaderPort.kt:69,80,81`). No other edits | `onFullyLoaded: () -> Unit` keeps its signature; the contract change is item 5 only, and every implementer/fake is listed in Task 3.3b (`GraphLoaderPort`, `GraphLoader`, and test fakes in `jvmTest/.../ui/StelekitViewModelLoadingTest.kt`, `ExternalFileChangeErrorHandlingTest.kt`, `businessTest/.../llm/StelekitViewModelLlmSuggestionTest.kt`, `sections/*Test.kt` per grep at implementation time) |
| `db/GraphManager.kt` (1601 lines) | Hotspot | **Isolate via seam** | Console reads through `ActiveGraphView`/`DbRestoreView` adapters outside the class using existing public `addGraph`/`removeGraph`; zero additions |
| `ui/StelekitViewModel.kt` (2358 lines, 91 commits) | Highest churn | **Isolate via seam** | No console state here; single call-site swap to `BackgroundIndexSupervisor` (Task 3.3c); only a `Screen.Console` route in `AppState.kt` |
| `diagnostics/GraphDiagnostics.kt` | `collect()` monolith | **Extend as-is**, then split into probes (5.1) | Commit pending Git section first (Epic 1) |
| `ui/GraphContentCameraCapture.kt` | Unrelated camera file; Epic 1 commits one diagnostics line there | **Extend as-is** for Epic 1 only | Console wiring goes in new `ui/GraphContentConsole.kt` (not here) |
| `WorkManagerSyncScheduler.kt` | Fast path calls `fetchOnly`; `GitSyncServiceRegistry.register` has no production caller (dead fast path); slow path fetches only (kept: owner decision a) | **Extend as-is** + `ColdSyncRunner.kt` | Registry promoted to commonMain and registered in `GraphContentGitSyncSetup.kt`; fast path -> `runScheduledSync`; slow path -> fetch-only `ColdSyncRunner` |
| `DatabaseWriteActor` / `RestrictedDatabaseQueries` | Gated write surface | **Extend as-is** | One raw-exec `@DirectSqlWrite` stub; function-level opt-in; no new actor request type |
| `ui/components/settings/SettingsDialog.kt` | `DeveloperSettings` hidden unless libsql callback set | **Extend as-is** | Change visibility predicate only |
| `platform/FileSystem.kt` | No canonical-path or ranged read | **Extend as-is** (defaulted members) | Task 5.3a0 adds `readPrefixBytes` and `canonicalPathOrNull` with defaults |

---

## Migration Plan
- **Schema migration**: none. `MigrationRunner.all` and `SteleDatabase.sq` are untouched, so `MigrationRunnerSchemaSyncTest` is unaffected and the SQLDelight regeneration step is not triggered. Verify with `git diff --stat -- '*.sq' '*MigrationRunner*'` empty before each ship.
- **Data repair** (gated, Story 2.2c-e): stored `git_config.remote_branch` rows that name a missing branch are repaired lazily on first `RemoteBranchNotFound`, only after UI confirmation, via `GitConfigRepository.saveConfig`, then read back. No startup network call.
- **New persisted state**: Settings keys `developer_mode_enabled` (default absent = false), `git_first_sync_confirmed_<graphId>`, `git_first_sync_previous_branch_<graphId>` (the branch a repair replaced, for `Change back`; cleared with the pending key), `git_first_sync_review_pending_<graphId>` (A1: set by a repair or `git set-branch`, cleared on confirmation; drives the `Review first sync` badge before A2 widens it to every unconfirmed config), `git_sync_staleness_<graphId>`, the notification toggle `git_sync_notify_enabled` (Task 2.4f), and the mass-change threshold keys `git_mass_change_min_files` (default 20), `git_mass_change_pct` (default 5) and `git_mass_change_max_journals` (default 10) (Task 2.3f; absent = default; also in the `settings` allowlist); app-dir folders `console-backups/` (created on first write, last 5 retained), `console-recovery/` (DB-only journal exports, Task 5.6d), `console-exports/` (spill and CSV files, session-scoped, 20 MB cap, Task 5.8a/6.4a) and `git-abort-recovery/` (Task 2.4i), all excluded from Android Auto Backup; `*.pre-restore` files during a restore. No cold-worker markers exist (ADR-004).
- **Reversibility**: all additive; revert commit restores prior behavior. A repaired `remote_branch` is a data value, reversible with `git set-branch`.
- **Rollback procedure**: revert the PR(s); delete `console-backups/` manually if desired; stored branch value stays valid; a stale `git_first_sync_confirmed_<graphId>` or `git_sync_staleness_<graphId>` value is inert once the policy code is reverted.

## Observability Plan
- **Logs** (via `LogManager`, existing Logs screen):
  - INFO per console command: name, duration, rows/lines, outcome; arguments pass `ConsoleRedactor`.
  - WARN per console SQL write with the (redacted) statement, affected rows, backup path.
  - WARN on `RemoteBranchNotFound`: remote, configured branch, available list.
  - WARN in `GraphLoader.reloadFiles` for each unreadable path (no more silent `continue`).
  - INFO per scheduled sync: trigger (worker-live / worker-cold / timer), policy decision and reason when skipped or fetch-only (including `Skip(RepoState)`, `Skip(FirstSyncUnconfirmed)`, `FetchOnly(Saf)`), `MergedCommitCount`, outcome.
  - INFO per cold fetch: `behindCount`, `lastMergedAt` age; WARN when a cold fetch ends in a typed error (e.g. `RemoteBranchNotFound`) or the staleness exceeds 2x the effective interval; each outcome is appended to the persisted last-10 history that `diag` and `git doctor` print.
  - WARN on `MassChangeBlocked` and `ConflictMarkersPresent` with the file count and first 50 paths (paths only, never content).
  - INFO build stamp in the diagnostics header and at console start.
  - INFO/WARN for indexer: `Background indexing complete` / `failed` / `restarted after trim` (existing signatures retained).
- **Metrics** (existing perf/telemetry facility, no new backend): `console.command.duration_ms{name}`; `git.sync.scheduled.duration_ms`; `git.sync.merged_commits`. Operations over 100 ms that are new: console SQL, `VACUUM INTO` backup, ls-remote default-branch detection, scheduled sync, in-core merge preflight.
- **Alerts**: no paging alerts (single-user app). The only user-facing alerts are the staleness chip, the launch banner and one low-importance Android notification when updates are waiting (Task 2.4f).
- **Diagnostic export**: keeps the Git section; `diag` reuses the same probes.

## Risk Control
- **Feature flags**: `developer_mode_enabled` default off (console entry hidden AND dispatcher refuses commands). Sync fix not gated (turns a silent no-op into an error). Scheduled full sync respects the existing auto-sync interval setting (Android floor 15 minutes); setting "off" disables it. It is also blocked by `Skip(FirstSyncUnconfirmed)` until one previewed manual sync has succeeded for the current `(remote, branch)`.
- **Background safety invariants** (ADR-004): the closed-app runner performs exactly one fetch and never merges, commits, pushes, stages, checks out or calls `abortMerge` (spy-asserted); SAF-backed graphs are fetch-only for all scheduled work; repository-state precheck before every commit and push; conflict-marker scan and mass-change guard before every commit and push; scheduled runs can never lift the mass-change guard; no force push anywhere (source-audit test).
- **Data-loss guards**: `graph reload`, `graph reindex` and `graph restore-backup` first run `DbOnlyRowsGuard` (Task 5.6d): DB-only journal rows (the 2026-10-07/09/10 journals today) are exported to `console-recovery/` before disk wins, and a typed confirm is required.
- **Confirm tiers**: state change = inline confirm echoing effect; DB write = modal with verbatim statement, dry-run row count, backup name, verb-labelled button, default focus Cancel, extra typed-table-name confirm for no-WHERE writes; shell = desktop-only, second confirm once per session plus "armed" chip.
- **Rollback procedure**: standard revert via PR close + revert commit. DB write rollback = `graph restore-backup <name>` (Task 5.5e).
- **Staged rollout**: PR-A1 first (done = Story 7.1 passed on the device); PR-A2 next; PR-B; PR-C ships dark behind the developer toggle; device verification (Epic 7) before marking the owner metrics met.
- **Standing invariants honored** (binding from repo-root `CLAUDE.md`): writes through actor/`@DirectSqlWrite`; reads bounded (<=500 rows, `IN` lists <=500); Arrow `Either` at boundaries; `catch Throwable` + `CoroutineExceptionHandler` on every long-lived scope; no `rememberCoroutineScope` into `remember{}` objects; no `java.*` in commonMain (enforced by a source-audit test, Task 4.1c). Credential handling: the redactor is best-effort plus exact-secret matching, credential tables are off the SQL allowlist, and `fs` deny-lists secrets; "no credential output anywhere" is a goal verified by tests, not a guarantee against transformed output.

## Unresolved Questions
Safe defaults apply until the owner answers; none blocks implementation.
- [ ] Device `git_config.remote_branch` and `resolve(origin/<branch>)` value (Git section of the Epic 1 diagnostics build). **Gates Story 2.2c-e (default-branch repair UI/migration) and selects Story 2.5 remedies** (Task 0.3b). Evidence-independent work (typed errors, no silent no-op, tests, Stories 2.3-2.4) proceeds immediately. Owner device wall-clock blocker (Task 0.3a).
- [x] Cold-worker behavior: **decided by the owner 2026-10-10 (a)**: closed-app background work is fetch-only plus badge/behind-count; full commit+merge+push runs only while the app is open or on manual sync. No cold conflict handling exists to ask about.
- [x] Which graph is active: **decided (b)**: the cloned app-private (non-`saf://`) graph; SAF graphs stay fetch-only for scheduled work. Interpretation applied (UNVERIFIED with the owner): a *manual* Sync on a SAF graph still runs the full sync as today. Task 7.1a verifies the owner's active graph is classified app-owned on the device.
- [ ] Mass-change thresholds (Task 2.3f: deleted files > `max(20, 5% of previous tree)` or > 10 journals). **Safe default as written**; INFERRED values, **configurable** (three Settings keys, defaults compiled in) and calibrated on the owner's 1.6k-journal graph in Task 7.1d before PR-A2 ships.
- [ ] Search over never-loaded older journals. **Safe default: no** (ADR-006; search copy says full-text covers loaded journals only); owner may later ask for METADATA_ONLY registration.
- [ ] Fate of the DB-only journals 2026-10-07/09/10. **Safe default: do not write or push without explicit confirm**; Task 3.2a lists them, `journals-repair` ships but never runs unconfirmed. Metric 4 treats them as known exceptions until decided. Additionally (Task 5.6d) any `graph reload`/`reindex`/`restore-backup` exports them to `console-recovery/` first.
- [ ] Remote-deleted files stay in the DB after merge (`computeChangedGitRelativePaths` reports `/dev/null`). **Safe default: deferred, tracked as a follow-up** (Task 2.3d filters deletions from the reload set only).
- [ ] Web console subset (UX G13): `help`, `settings`, `logs`, `export`, `sql` only if a read-only connection exists. Default applies; owner confirmation requested.
- [ ] Long-press edit/pin of snippet chips (UX G11): deferred, not in this plan.
- [ ] PR #397 / `RepositorySet` field conflict (ADR-002 adds nullable `sqlConsole`): rebase before Story 5.4 — owner merge state.
- [ ] Bazel target placement for new `commonMain/.../console/` sources (see `project_plans/commonmain-bazel-target-split`): resolved inside Task 4.1b with an explicit class-count acceptance line — implementer.

## Plan branches driven by device evidence (Task 0.3b; requirements Open Q1, features 3.3)
Each branch names the tasks that run. Tests for every alternative cause are written regardless (as characterization), so a wrong guess costs a remedy task, not a missing safety net.
- **Branch 1** (expected): configured=`main`, remote only `master`. Story 2.2c-e runs (repair UI/migration); 2.5 skipped.
- **Branch 2**: configured=`master`, resolves, `remoteCommitsMerged=0`, path-mode SAF shadow. Run Task 2.5a (SAF shadow remedy); skip 2.2c-e. Low prior after owner decision (b) (the active graph is app-private); kept as characterization.
- **Branch 3**: configured branch resolves, `wikiSubdir=logseq` and changed files do not reach `reloadFiles`. Run Task 2.5b.
- **Branch 4**: shallow clone / narrowed refspec (`remote.origin.fetch` single-branch, `cloneDepthState`) leaves the tracking ref stale. Run Task 2.5c.
- **Branch 5**: resolves but `behind>0` after a sync (merge not running). Task 2.3c surfaces it; run Task 2.5d (`hasRemoteChanges` gating, fixed by 2.1d).
Epics 4-6 do not depend on the branch taken.

### Pivot criterion: which Git-section evidence moves the plan to which branch (Task 0.3b rule)
Inputs come only from a stamped export (Task 1.1d): stored `remote_branch` (B), `resolve(origin/B)` result and OID (R), ls-remote heads and their OIDs (H), local HEAD branch and OID, shallow flag, `remote.origin.fetch` refspec, path-mode, `wikiSubdir`, `behind`/`ahead`. Evaluate top to bottom; the **first** row that matches is taken (rows are exclusive by construction), and the matching row also decides which Task 2.5 remedy is go.

| # | Evidence (all conditions) | Branch taken | Tasks go |
|---|---|---|---|
| 1 | B is not in H (for example `main` vs heads `[master]`) **and** R is unresolved **and** ls-remote succeeded (H non-empty) | **Branch 1** (hypothesis confirmed) | 2.2c-e; 2.5 no-go |
| 1' | As row 1 but H is empty (remote empty) or ls-remote failed | not a branch mismatch: `RemoteEmpty` / unreachable path; no repair UI | 2.2c-e no-go; Task 2.1 messages only |
| 2 | B is in H, R resolves, R's OID equals H(B), `behind == 0`, path-mode is SAF shadow | **Branch 2** | 2.5a |
| 3 | B is in H, R resolves, R's OID equals H(B), local HEAD contains R, `behind == 0`, files exist in the git tree at HEAD but not on disk/DB under the wiki root, `wikiSubdir` set | **Branch 3** (path filter) | 2.5b |
| 4 | B is in H, R is unresolved or R's OID differs from H(B), and the clone is shallow or `remote.origin.fetch` does not cover B | **Branch 4** | 2.5c |
| 5 | B is in H, R resolves, R's OID equals H(B), `behind > 0` after a sync | **Branch 5** | 2.5d |
| 6 | Journals are present on disk after sync but missing from the DB (`diskOnly` large, recent dates present on disk) | not a sync defect: PR-B scope (lazy load / indexer); Story 2.5 no-go | PR-B Stories 3.1-3.3 |

Rules: (i) row 1 is checked first because it is the cheapest, evidence-backed explanation of the observed `remoteCommitsMerged=0`; any resolved R sends the plan to rows 2-5 by the OID/path-mode evidence, never to row 1. (ii) Evidence that matches no row, or contradicts a row (for example R resolves and B in H but `behind` is unknown), makes the export **inconclusive**: record it, request a second export once, and keep Branch 1's ungated tasks (typed error, invariants) shipping since they are correct on every row. (iii) **Time-box = one device cycle** (owner installs the stamped build, opens Logs > diagnostics, pastes the Git section). No return after one cycle => Branch 1 is taken as the safe default. (iv) **Second gate**: if Story 7.1 fails on the device after the Branch 1 remedy (still `behind > 0` or journals still absent), a fresh stamped export is taken once and the table is re-evaluated on that export; that selects the 2.5 remedy for one further device cycle. Anything still unexplained after the second cycle is escalated to the owner as an open question instead of adding another remedy blind.

## Dependency Visualization

```
PR-A1
Wave1:  [0.1 JGit spikes]  [1.1a-c commit diag]  [2.1b0 extract origin fixture]
          |                   |
Wave2:  [2.1 typed err + resolver (needs 0.1)]  [1.1d build stamp]  [2.2a2 BranchRepairService]
          |                                          |
Wave3:  [2.3 push/invariants/state guard/mass-change]  [2.2a/b detect + clone]   [0.3a owner shares stamped export (wall-clock, time-boxed)]
          (2.2d0 first-sync review sheet + consent flag + manual Sync now: needs 2.1d ahead/behind helper and 2.3f counter; may start in W3)
          |                                    |
Wave4:  [0.3b decision] -> [2.2d0 review sheet (before 2.2d)] -> [2.2c-e gated repair UI; 2.2d needs 2.2d0] / [2.5 conditional remedies] -> [7.1 device pass: A1 done]

PR-A2 (may BUILD after A1 merge + 7.1c; may RELEASE only after 7.1)
Wave1:  [2.4a policy + runScheduledSync (needs 2.1, 2.3e/f)]  [2.4c registry]  [2.4d autoCommit]  [2.4g path-mode predicate]
          |
Wave2:  [2.4b desktop timer]  [2.4e fetch-only cold runner]  [2.4f staleness]  [2.4h policy enforcement + notification entry (needs 2.2d0)]  [2.4i abort recovery]
          |
Wave3:  [7.1b device soak + staleness pass]

PR-B (independent of A except GraphLoader serialization)
Wave1:  [3.2 journal diff/repair]  [3.1a0 JournalLazyLoader] -> [3.1 lazy journals] [3.3 index status/supervisor]

PR-C
Wave1:  [0.2 SQL spikes] [4.1 skeleton] [4.2 tokenizer] [4.3 redactor]
Wave2:  [4.4 buffer/session] [4.5 dev-mode gate] [4.6 composition root (needs 4.1)]
Wave3:  [5.1 diag] [5.2 git (needs 2.1,2.2a2)] [5.3 fs (5.3a0 first)] [5.4 sql read (needs 0.2a)] [5.6 graph (needs 3.2,3.3; 5.6d guard)] [5.7 sh] [5.8 meta]
Wave4:  [5.5 sql write/backup/restore (needs 5.4, 0.2b, 0.2c; 5.5e also needs 5.6d)] -> [5.5g backups list + Settings entry (needs 5.5b, 5.5e)]
Wave5:  [6.1 screen] -> [6.2 input] [6.3 confirm tiers (needs 5.5)] [6.4 SQL grid] [6.5 entry points] -> [6.6 a11y/tests]

Device:
Wave:   [7.1c interim JVM end-to-end + export diff (after A1 W3 and Task 2.2d0; runs if 7.1 is delayed)] [7.1d threshold calibration (owner data; before A2 opens)] [7.1 sync device pass (A1, owner)] [7.1b scheduled/staleness pass (A2, owner)] [7.2 console device pass (PR-C, owner)] -> [7.3 closeout]
```

Serialization points (shared files; **Tasks 1.1a-c land first on the six uncommitted diagnostics files, staged by name**, see Delivery): `AndroidGitRepository.kt`/`JvmGitRepository.kt` (2.1c -> 2.3a -> 2.3d -> 2.5x), `GitSyncService.kt` (2.3e -> 2.3f -> 2.4a -> 2.4d -> 2.4f -> 2.4i), `WorkManagerSyncScheduler.kt` (2.4c -> 2.4e), `GraphLoader.kt`/`GraphLoaderPort.kt` (2.3d WARN in PR-A1 -> 3.1a -> 3.3b), `SettingsDialog.kt` (4.5 -> 6.5), `ConsoleSession.kt` (4.4 -> 6.x), `GraphContentCameraCapture.kt` (Epic 1.1c only), `GraphContentGitSyncSetup.kt` (2.4c), `GraphDiagnostics.kt` (1.1c -> 1.1d), `SyncStatusBadge.kt` (2.1a -> 2.1f -> 2.1g -> 2.4f), `DomainError.kt` (2.1a -> 2.1f -> 2.3e).

---


# PR-A1 and PR-A2: Sync correctness (Epics 0-2; Story 2.4 is PR-A2, Story 0.2 is PR-C, everything else here is PR-A1)

### Epic 0: Spikes and evidence gate (gate dependent stories)
**Goal**: Retire the UNVERIFIED items from research and gate the expensive, evidence-dependent work.

#### Story 0.1: JGit behavior spikes
**As the** implementer, **I want** measured JGit behavior, **so that** Epic 2 relies on facts rather than inference.
**Acceptance Criteria**:
- Characterization tests record the actual behavior and are kept as regression tests.
  - *Given* a temp bare origin created with `setInitialBranch("master")` and one commit, *When* `Git.lsRemoteRepository().setRemote(path).callAsMap()["HEAD"]` is read over the file transport and a shallow `CloneCommand.setDepth(1)` clone is made, *Then* the test prints HEAD `isSymbolic`/`target.name`, the clone's `remote.origin.fetch` string and `branchList`, and asserts the observed values (documenting whether file transport advertises symref).
- `FetchResult.getAdvertisedRefs()` accessibility and `exactRef` vs `resolve` ambiguity are confirmed.
  - *Given* a repo with a local branch `origin/main` and a remote-tracking ref `refs/remotes/origin/master`, *When* `repo.resolve("origin/main")` and `repo.exactRef("refs/remotes/origin/main")` are called, *Then* `resolve` returns the local-branch OID (ambiguity) and `exactRef` returns `null`.
- The real false-green path and Android constructibility are answered.
  - *Given* `GitSyncService.kt:238` is the **no-config early return** (`Success(0,0)` when `getConfig` yields null, verified by reading the file), *When* a stub-repository test drives a configured service whose `fetch` returns `FetchResult(false, 0)` for an unresolvable ref, *Then* the test documents that the false-green path is `hasRemoteChanges == false` falling through to push and `Success` (not line 238), and a second test proves line 238 only fires when there is no config.
**Files**: `jvmTest/.../git/GitSyncSpikeTest.kt` (new), `commonTest`/`businessTest` stub test, notes appended to this plan's Task results.

##### Task 0.1a: JGit symref / depth-clone / exactRef / advertised-refs spike (S)
- Files: `jvmTest/.../git/GitSyncSpikeTest.kt`. Test: the characterization tests above, green. Done when: findings recorded; if file transport lacks symref, ls-remote symref assertions move to the real-remote console probe (Task 7.1a) and the objectId-match fallback is the unit-tested path.

##### Task 0.1b: False-green path characterization + Android constructibility (XS)
- Files: `commonMain/.../git/GitSyncService.kt` (read-only), `commonTest/.../git/testsupport/StubGitRepository.kt` (existing), `androidUnitTest/.../git/` (probe). Test: stub-driven `GitSyncService` test whose name states the no-config early return at `:238` and a second test for the real false-green path. Done when: decision recorded for Task 2.1c: "test the shared helper, not the Android class" if `AndroidGitRepository` cannot be built without a real `Context`.

#### Story 0.2: SQLite capability spikes (feeds PR-C)
**As the** implementer, **I want** per-driver facts on read-only enforcement, `VACUUM INTO` and write cascade, **so that** ADR-002 is concrete and fails closed.
**Acceptance Criteria**:
- Read-only enforcement per driver is known, and a console-owned connection path is chosen per platform.
  - *Given* the JVM driver where `DriverFactory.jvm.kt:143` `createReadDriver` returns `null`, *When* a dedicated read-only `JdbcSqliteDriver`/`java.sql.Connection` (SQLite read-only open flags) on the same file executes `UPDATE pages SET name=name` and `WITH x AS (SELECT 1) DELETE FROM pages`, *Then* both are rejected with a "readonly" error, and a pooled app write issued afterwards still succeeds (no leak), while concurrent reads during a WAL write succeed.
  - *Given* Android (`createReadDriver` at `DriverFactory.android.kt:174`), iOS (`:17` returns null) and wasm, *When* the same write is attempted, *Then* the result is recorded per platform and any platform with no engine-enforced read connection is marked "sql reads disabled".
  - *Given* a spike failure on any platform, *Then* ADR-002 A1 stands: reads disabled there, no classifier-only fallback.
- The live-DB swap is proven (pre-mortem #5).
  - *Given* an emulator/device with the console read connection open, *When* the spike closes it, drains the actor, renames `*.db`/`-wal`/`-shm` to `*.pre-restore`, copies a snapshot in, deletes any stale `-wal`/`-shm` and reopens, *Then* `PRAGMA integrity_check` is `ok` and a row-count comparison against the snapshot matches; the checkpoint+copy fallback is only used on a quiesced actor.
- `VACUUM INTO` support is known.
  - *Given* the Android requery SQLite (3.49.0 per build file) on a device/instrumented test, *When* `VACUUM INTO '<cache>/t.db'` runs outside a transaction, *Then* success or the exact error is recorded and the fallback path is chosen accordingly.
- Write cascade is answered (Task 0.2c).
  - *Given* a test graph with an on-disk page and `DatabaseWriteActor.execute { rawUpdate on blocks }`, *When* invalidation fires and the app is otherwise idle for a debounce window, *Then* the test asserts whether any `GraphWriter` write or file change occurred, and records the answer in ADR-002 A4.
**Files**: `jvmTest/.../db/SqlReadOnlySpikeTest.kt`, `jvmTest/.../db/RawWriteCascadeSpikeTest.kt`, `androidInstrumentedTest/.../db/VacuumIntoSpikeTest.kt` (new).

##### Task 0.2a: Read-only enforcement spike per driver (M)
- Files: `jvmTest/.../db/SqlReadOnlySpikeTest.kt`; reads `commonMain/.../db/DriverFactory.kt`, `jvmMain/.../db/DriverFactory.jvm.kt`, `ReadWriteRouterDriver.kt`. Test: JVM green; Android/wasm findings recorded in plan notes (Android via Robolectric if the driver loads, else instrumented). Done when: ADR-002 "read path" section lists the per-platform connection or "disabled".

##### Task 0.2b: `VACUUM INTO` and live-DB swap spike (M)
- Files: `jvmTest/.../db/VacuumIntoSpikeTest.kt`, `androidInstrumentedTest/.../db/VacuumIntoSpikeTest.kt`, `androidInstrumentedTest/.../db/DbSwapSpikeTest.kt` (swap sequence above). Test: JVM green (sqlite-jdbc 3.51.3); Android compiled in CI (`ciCheck` compiles androidTest), executed on the owner's device in Story 7.2. Done when: backup design uses runtime detection regardless; spike tunes the fallback; the swap order (close reader, drain, rename, delete stale WAL/SHM, verify) is recorded in ADR-002 A3 and Task 5.5e depends on it.

##### Task 0.2c: Raw-write cascade spike (S)
- Files: `jvmTest/.../db/RawWriteCascadeSpikeTest.kt`. Done when: ADR-002 A4 states the verified answer (does actor invalidation alone ever trigger `GraphWriter`?) and Task 5.5d is sized accordingly.

#### Story 0.3: Device-evidence decision gate
**As the** owner, **I want** the expensive default-branch repair UI built only if the device evidence says it is the cause, **so that** no UI ships for the wrong problem.
**Acceptance Criteria**:
- Gate is explicit.
  - *Given* Epic 1 is committed and a build is installed on the phone, *When* the owner shares the Git section of the diagnostics export, *Then* `remote_branch`, `resolve(origin/<branch>)`, ls-remote heads, local HEAD branch, shallow flag, path-mode and `wikiSubdir` are recorded in `project_plans/dev-console-git-sync-fix/` notes and exactly one plan branch (see "Plan branches") is marked taken.
- Evidence is trustworthy (pre-mortem #6, #1).
  - *Given* an export whose header lacks a `Build:` stamp (Task 1.1d) or shows `unstamped`, *Then* Task 0.3b refuses to select a branch from it. *Given* the export, *Then* the record also includes `ls-remote` heads showing whether a stray `main` exists on the remote (it affects the first-sync preview, Task 2.4h).
- Evidence-independent work is not blocked, and the gate is time-boxed.
  - *Given* the gate is still open, *Then* Stories 2.1, 2.3 and PR-B/PR-C proceed (Story 2.4 waits for A1 per Delivery); only Tasks 2.2c, 2.2d, 2.2e and 2.5a-d wait. *Given* one device cycle passes with no evidence, *Then* Branch 1 (the safe default) is taken.
##### Task 0.3a: Owner runs the diagnostics build and shares the Git section (XS, owner wall-clock blocker)
- Steps: build from the Task 1.1d stamp commit, install, open Logs > diagnostics, confirm the `Build:` line matches the commit, share the Git section. No code.
##### Task 0.3b: Record the evidence and select the plan branch (XS)
- Files: `project_plans/dev-console-git-sync-fix/implementation/device-evidence.md` (notes written by the implementer from the owner's paste). Done when: one branch marked taken by applying the "Pivot criterion" table (first matching row, or "inconclusive") and Tasks 2.2c-e / 2.5a-d marked go or no-go; the record includes the build stamp, the stray-`main` `ls-remote` result, and the **M1 baseline** measured once from the same export: missing recent dates, on-disk journal count and the origin-tip count under the same `git ls-tree` counting rule (requirements "Metric 1 numeric baseline"; the relayed repo-side figures 1,587 / 9,430 are replaced by the measured ones).

### Epic 1: Commit the diagnostics work already done
**Goal**: Land the uncommitted Git diagnostics section with exactly its own files, as the first commit of PR-A1, then add a build stamp in a second small commit so device evidence can be tied to a build.

#### Story 1.1: Commit diagnostics changes
**As the** owner, **I want** the Git diagnostics section committed, **so that** the next device build can confirm or refute the hypothesis.
**Acceptance Criteria**:
- Build and the cited test pass before commit.
  - *Given* the working tree on `feat/cross-graph-phase2`, *When* `./gradlew :kmp:compileKotlinJvm :kmp:compileDebugKotlinAndroid` and `GraphManagerSwitchNotesPathTest` run, *Then* all succeed.
- No credentials appear in the export.
  - *Given* a `GitConfig.remoteUrl = "https://u:ghp_abc123@h/r.git"`, *When* `GraphDiagnosticsCollector.collect()` runs, *Then* the full output contains neither `ghp_abc123` nor `u:`.
- Only the six intended files are committed.
  - *Given* `git status` showing five modified files, the untracked `jvmCommonMain/.../git/GitRefDiagnostics.kt`, plus untracked `benchmarks/history/2026-06-21_17h36m29s_295fd1afab.json` and `project_plans/dev-console-git-sync-fix/`, *When* the commit is made by naming files, *Then* `git show --stat HEAD` lists exactly the six and the benchmark JSON and plan docs stay out of this commit.
- The build stamp ties evidence to a build (pre-mortem #6).
  - *Given* the stamp commit, *When* diagnostics are exported, *Then* the header reads `Build: <shortSha> <builtAt> <appVersion>` (or `unstamped`); `diag` prints the same line.
**Files**: `commonMain/.../diagnostics/GraphDiagnostics.kt`, `jvmCommonMain/.../git/GitRefDiagnostics.kt`, `commonMain/.../git/GitRepository.kt`, `androidMain/.../git/AndroidGitRepository.kt`, `jvmMain/.../git/JvmGitRepository.kt`, `commonMain/.../ui/GraphContentCameraCapture.kt` (the only console-unrelated edit this plan makes to that file); the stamp commit has its own file list (Task 1.1d).

##### Task 1.1a: Verify the working diff is diagnostics-only and builds (XS)
- Steps: `git diff` on the five modified files; confirm no unrelated edits; run compiles and `GraphManagerSwitchNotesPathTest` (wrap with `scripts/jvm-display-check.sh` if a UI-test class is touched; this one is headless). Done when: output shown green.

##### Task 1.1b: Redaction regression test for the diagnostics export (XS)
- Files: `jvmTest/.../diagnostics/GraphDiagnosticsRedactionTest.kt` (new). Test: the Given/When/Then above; uses `GitRefDiagnostics` userinfo stripping. Done when: green.

##### Task 1.1c: Stage by name and commit (XS)
- Steps: `git add` the six paths explicitly (never `-A`/`.`); commit `feat(diagnostics): add git sync section and journal samples to graph diagnostics`; do not push (push is Phase 7 ship). Done when: `git show --stat HEAD` pasted.

##### Task 1.1d: Build stamp in the diagnostics header and `diag` (S)
- Files: `commonMain/.../diagnostics/GraphDiagnostics.kt` (header line), a build-info constant generated like `generateWasmVersionInfo` / `resolveAppVersion()` in `kmp/build.gradle.kts` (git short SHA + build time), and the Bazel equivalent via workspace-status stamping if available; if the Bazel build cannot stamp, the line reads `unstamped` and Task 0.3b rejects that export. Test: `commonTest/.../diagnostics/BuildStampFormatTest.kt` (format, `unstamped` fallback). Done when: a second commit lists only its own files and Task 0.3a builds from it.

### Epic 2: Git sync fix

#### Story 2.1: Typed error and shared remote-ref resolver (evidence-independent)
**As the** owner, **I want** an unresolved remote branch to be an error with an actionable message, **so that** sync can never look healthy while pulling nothing.
**Acceptance Criteria**:
- Unresolved ref fails loudly (metric 2).
  - *Given* a temp bare origin created with `setInitialBranch("master")` holding commit `c1`, cloned through `JvmGitRepository`, and `GitConfig(remoteName="origin", remoteBranch="main")`, *When* `fetch(config)` runs, *Then* it returns `Left(RemoteBranchNotFound(remote="origin", branch="main", available=["master"]))` and the user message reads "Branch 'main' not found on remote — tap to fix" (the UX S1 strings are the single source; Task 2.1a asserts them).
- The test is red on old code first.
  - *Given* the failing-first test committed before the fix, *When* run against the pre-fix `doFetch`, *Then* it fails with `Right(FetchResult(false, 0))` observed.
- Correct config pulls (metric 1).
  - *Given* the same origin with a second commit `c2` adding `logseq/journals/2026_10_10.md` and `remoteBranch="master"`, *When* `fetch` then `merge` run, *Then* `fetch.hasRemoteChanges == true`, `remoteCommitCount == 1`, a merge commit exists (NO_FF), and the file exists under `<repoRoot>/logseq/journals/`.
- Ahead-only is not "changes".
  - *Given* local `master` ahead of `origin/master` by 1 and no remote commits, *When* `fetch` runs, *Then* `hasRemoteChanges == false`.
- Typed empty remote and invalid name.
  - *Given* an origin with no branches, *When* `fetch` runs, *Then* `Left(RemoteEmpty)` with message "Remote is empty — push a commit first or check the URL" and no `RemoteBranchNotFound`; *Given* `remoteBranch = "a..b"`, *Then* `Left(InvalidRefName("a..b"))`.
- Error is not retried.
  - *Given* any of the three new fetch errors, *When* `runGitTransportOp*` classifies it (predicate in `GitOperationSupport.kt`), *Then* exactly one attempt is made and no `RetryExhausted` wrapper appears.
- Unrepaired state is still usable.
  - *Given* the repair UI has not shipped (gate open), *When* the owner taps the red badge, *Then* a read-only detail sheet shows configured branch, available branches, "Nothing was pulled" and Copy details (UX-73); there is no "Use" button. The invariant, detached-HEAD, empty-remote, `RepairNeeded`, marker-scan and mass-change variants are read-only too and ship ungated (Task 2.1g, UX-79).
**Files**: `commonMain/.../error/DomainError.kt`, `jvmCommonMain/.../git/RemoteTrackingRef.kt` (new), `jvmMain/.../git/JvmGitRepository.kt`, `androidMain/.../git/AndroidGitRepository.kt`, `jvmCommonMain/.../git/GitOperationSupport.kt` (one predicate line), `jvmTest/.../git/RemoteBranchResolutionTest.kt` (new), `jvmTest/.../git/testsupport/BareOriginFixtures.kt` (new), `commonMain/.../git/SyncState.kt` (Task 2.1f), `commonMain/.../ui/screens/git/SyncDetailSheet.kt` (Task 2.1g).

##### Task 2.1a: Add `GitError.RemoteBranchNotFound`, `RemoteEmpty`, `InvalidRefName`, `SyncInvariantViolated` + messages (S)
- Files: `commonMain/.../error/DomainError.kt` (`GitError` at `:71`, + `toSyncErrorMessage` at `:320`), `commonMain/.../ui/components/SyncStatusBadge.kt` (route: `RemoteBranchNotFound` tap opens the read-only detail sheet now and the repair sheet when Story 2.2d ships, not retry; TalkBack text "Sync error — tap to fix"), any other exhaustive `when` (compiler lists them).
- Exact strings (from ux.md S1, asserted verbatim): `RemoteBranchNotFound` "Branch 'main' not found on remote — tap to fix" (with the actual names); `RemoteEmpty` "Remote is empty — push a commit first or check the URL"; `SyncInvariantViolated` "Sync finished but your phone is missing remote changes — tap for details"; detached HEAD "Your notes folder isn't on a branch — tap for details" (plain language, UX-92; changed from the earlier "Repository is on a detached HEAD" wording); `InvalidRefName` "Branch name '<x>' is not valid — tap for details".
- Test: `commonTest/.../error/GitErrorMessageTest.kt` asserts the five strings above and that none is retry-routed. Done when: all targets compile.

##### Task 2.1b0: Extract `createBareOriginWithCommits` and `setIdentity` into shared test support (XS)
- Both are `private` in `jvmTest/.../git/JvmGitRepositoryTest.kt` (`createBareOriginWithCommits` at `:620`, `setIdentity` at `:103`). Move to `jvmTest/.../git/testsupport/BareOriginFixtures.kt` (`internal`), add a `setInitialBranch` parameter, and make `JvmGitRepositoryTest` use it. Done when: `JvmGitRepositoryTest` is green unchanged in behavior.

##### Task 2.1b: Failing-first regression tests (S)
- Files: `jvmTest/.../git/RemoteBranchResolutionTest.kt`, using `BareOriginFixtures`. Cases: master-only origin + `main` config; correct config pulls with `logseq/` file; ahead-only; stale config after remote rename; empty remote; invalid ref name; detached HEAD. Commit against old code and show red, then proceed. Also confirm Bazel `business_tests` picks up any new businessTest class (class count increases by exactly the number added; recount from the real run).

##### Task 2.1c: Shared `resolveRemoteTrackingRef` and wire both platforms (M)
- Files: `jvmCommonMain/.../git/RemoteTrackingRef.kt`, `jvmMain/.../git/JvmGitRepository.kt`, `androidMain/.../git/AndroidGitRepository.kt`, `androidMain/.../git/AndroidGitRepositoryMerge.kt`. `doFetch` captures `FetchResult.advertisedRefs`/tracking updates for `available`, sets `setRemoveDeletedRefs(true)`; `doMerge` uses the same helper (Android message now names the ref); `hasRemoteDivergedSinceShallowClone` uses `exactRef`. WARN log per Observability. Resolver validates names once (`isValidRefName`) and returns `InvalidRefName`.
- Test: 2.1b green on JVM; helper unit-tested; Android parity by helper test (per 0.1b). The resolver is also exercised against a repo shaped like the SAF shadow worktree (separate git dir / work tree) in a jvmTest to cover the shadow code path that Android would use. Done when: grep shows no remaining `repo.resolve("<remote>/<branch>")` in correctness paths.

##### Task 2.1d: Ahead/behind via RevWalk (S)
- Files: `jvmCommonMain/.../git/RemoteTrackingRef.kt` (add `isRemoteAhead(repo, remoteTip)`), both `doFetch`. Test: ahead-only, behind-only, diverged cases in `RemoteBranchResolutionTest`.

##### Task 2.1e: Mark the new fetch errors non-retryable (XS)
- Files: `jvmCommonMain/.../git/GitOperationSupport.kt` (single predicate line near the `isTransient`-style helpers around `:100-135`; `GitTransportRetryState.kt` is only a state holder and is not changed). Test: fault-injection style assertion of one attempt, next to `GitTransportFaultInjectionTest`.

##### Task 2.1f: `SyncState.RepairNeeded` and the guard/scan error variants (S)
- Files: `commonMain/.../git/SyncState.kt` (new variant `RepairNeeded(reason)`; `ConflictPending(conflicts)` unchanged; update the exhaustive `when` in `SyncStatusBadge` and any site the compiler lists), `commonMain/.../error/DomainError.kt` (`GitError.ConflictMarkersPresent(files)`, `GitError.ScanIncomplete(reason)`, `GitError.MassChangeBlocked(deleted, threshold, sample)`) and `toSyncErrorMessage`. Strings: "Conflict markers found in N file(s) — nothing was pushed, tap for details"; "Couldn't finish checking your files for conflict markers — nothing was pushed, tap to retry"; "Sync paused: N files would be deleted — tap to review"; `RepairNeeded` "Repository needs repair (a merge was interrupted) — tap for details".
- `RepairNeeded` is what `sync()` returns for a non-SAFE repository state before any `stageSubdir`/`commit`/`merge`/`push`; an interrupted merge has no `conflicts` list, so it cannot reuse `ConflictPending`. Test: `GitErrorMessageTest` extended (every variant has a message, none retry-routed); the badge `when` stays exhaustive. Done when: all targets compile; ADR-003 lists the seven variants.

##### Task 2.1g: Ungated read-only detail sheet variants and the amber/red rule (S)
- Badge fit and accessibility (UX-94, UX-95): at 360 dp the badge wraps to at most two lines (three at font scale >= 1.5) before ellipsizing with the full text in the description; the chip moves below the badge when the badge wraps or width < 400 dp; badge and chip are separate >= 48 x 48 dp targets; amber uses the warning-triangle icon and red the octagon-with-exclamation (distinct silhouettes); each variant has its own contentDescription (`Warning: ...` / `Error: ...`), button role and a polite live-region announcement once on entering the state. Tests: `SyncStatusBadgeTest` at `w360dp` x font scale 1 and 1.5 (line limits, targets, icon identity per variant, description per variant, one announcement per entry, none for same-state retries).
- Files: `commonMain/.../ui/components/SyncStatusBadge.kt`, `commonMain/.../ui/screens/git/SyncDetailSheet.kt` (new, read-only; Task 2.2d adds the repair button on top for `RemoteBranchNotFound`). Variants (ux S1/S2, UX-79): invariant, detached HEAD, empty remote, `RepairNeeded` (routes to the existing conflict/abort UI), `ConflictMarkersPresent`/`ScanIncomplete`, `MassChangeBlocked` (foreground confirm, Task 2.3f), `FirstSyncUnconfirmed` (opens the preview, Task 2.4h). Each shows a doctor-style summary and Copy details; Retry sync where meaningful; no repair button.
- **One mapping (resolves the red-versus-amber conflict)**: `SyncInvariantViolated` is a `SyncState.Error` that the badge renders amber ("incomplete") when the remote is still ahead after a sync, and red for every other error. Badge text is the ux S1 string; the N3 result line ("Remote is ahead by K but nothing was merged — see details") is the same state shown inline. Test: `androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt` table (state -> text, color, tap target) and `businessTest/.../ui/GitSyncCoordinatorTest.kt` for the amber mapping. Lands in A1, not behind the 2.2d gate. **Plain-language audit (UX-92)**: `androidUnitTest/.../ui/UserFacingCopyAuditTest.kt` renders every badge state, detail-sheet variant, banner and result line (and, once they exist, the notification text and wizard strings) and fails if any visible text contains a banned word (`symref`, `ls-remote`, `invariant`, `DB-only`, `tracking ref`, `refspec`, `detached HEAD`, `shallow`, `OID`, `SQLite`, `WAL`); Copy details text and console output are exempt by design. Tasks 2.2c/2.2d/2.4f/2.4h add their own surfaces to the same test as they land.

#### Story 2.2: Default-branch detection (ungated) and confirmed config repair (gated)
**As the** owner, **I want** the app to detect the remote's default branch and offer to correct a wrong stored branch, **so that** setup and existing broken configs end up right without silent rewrites.
**Depends on**: Story 2.1 (typed error, Task 2.1d ahead/behind helper) and Task 2.3f (files-to-delete counter) for the review's data; Task 2.2d0 (first-sync review, A1) before Task 2.2d, because the repair routes into it.
**Gate**: Tasks 2.2a, 2.2a2, 2.2b and 2.2d0 are evidence-independent (they prevent new misconfigured clones, feed the console twin `git set-branch`, and own the first-sync consent that scheduled sync in PR-A2 also needs). Tasks 2.2c, 2.2d and 2.2e (wizard rework, branch repair sheet, data repair) are **go only if Task 0.3b selects Branch 1**.
**Acceptance Criteria**:
- Detection uses the server symref, falling back to OID match.
  - *Given* a temp origin whose HEAD points at `refs/heads/master`, *When* `detectDefaultBranch(url)` runs, *Then* it returns `Detected("master")`; and *Given* origin with heads `main` and `master` at the same OID and no symref, *When* run, *Then* it returns `Ambiguous(["main","master"])`; and *Given* an origin with no branches, *Then* `EmptyRemote`.
- Clone records the real branch (ungated).
  - *Given* cloning an origin whose HEAD is `master` while the wizard form says `main`, *When* clone completes, *Then* the saved `GitConfig.remoteBranch == "master"`.
- Wizard prefills and validates (gated).
  - *Given* a successful `testRemote` against that origin, *When* Step 4 renders, *Then* the field shows `master` with "(remote default)" and Save is blocked with "Branch 'nope' not found on remote. Available: ..." if the user types `nope` (UX-15, UX-16).
- Existing broken config is repaired only after confirm (gated).
  - *Given* a stored `remote_branch='main'` and origin has only `master`, *When* sync errors with `RemoteBranchNotFound` and the user taps "Use 'master'", *Then* the dialog shows "remote_branch: main -> master", `saveConfig` runs, the row is read back as `master`, and **no sync runs yet**: the sheet is replaced by the previewed first-sync review (Task 2.2d0, same PR, heading "Branch set to master. Review before syncing."), and only the user's "Sync now" tap there (the consent) runs the sync, which then reports "Pulled N commits" (green), "Already up to date" (green), or, when `behind > 0` after the sync, the amber rendering of `Error(SyncInvariantViolated)` ("Remote is ahead by K but nothing was merged — see details"; one mapping, Task 2.1g) (UX-02 = three taps: badge, "Use 'master'", "Sync now"; UX-08, UX-09, UX-70, UX-87, resolves G2; the result is a persistent line, not a toast). *Given* "Not now" on the review, *Then* the branch stays saved, no sync runs and the badge reads "Review first sync"; tapping that badge reopens the review (an A1-only build has no scheduled sync, so the badge is the only re-entry and must always have its sheet, Task 2.2d0).
  - *Given* the user dismisses (Not now, X, Back, Escape), *Then* the stored value stays `main` and the red badge persists (UX-06).
  - *Given* the sheet content, *Then* it offers: "Choose another branch" radio list (no preselection and a disabled apply button when ambiguous, UX-05); "Copy details" with no credentials or URL userinfo (UX-13); an "unreachable" variant that never claims the branch is missing (UX-11); an "empty remote" variant with no Use button (UX-12); "Change back to 'main'" when the follow-up sync fails (UX-10); and "Couldn't save the new branch" with Retry/Close when read-back differs (UX-07) (resolves G7).
- Background worker sees the repair (gated).
  - *Given* the repaired row, *When* `WorkManagerSyncScheduler` builds its `GitConfig` (`Git_config.toGitConfig()`), *Then* `remoteBranch == "master"`.
- Loading, timeout and cancel states (gated; UX-80).
  - *Given* a remote with several heads, *When* the branch repair sheet opens, *Then* it is usable at once (data from the error), the primary button is disabled with `Checking which branch is the default…` and a progress bar while `detectDefaultBranch` runs, a `Skip check` control ends it, and at 15 s it falls back to the ambiguous layout with `Couldn't check the remote's default (timed out). Pick one.`; *Given* exactly one head, *Then* no wait occurs. The same lookup state with `Skip` and the 15 s fallback applies to wizard Step 4/5; leaving the screen cancels the lookup; no state claims the branch is missing while a lookup runs or after it timed out.
- Repair-sheet concurrency (gated; UX-93).
  - *Given* the sheet opened for stored `main`, *When* the stored branch changes elsewhere (console `git set-branch`, another window) before the tap, *Then* `BranchRepairService` re-reads the row, writes nothing and the sheet shows `This changed while the sheet was open. Review the new details.` with refreshed content; *Given* a chosen target no longer in the freshly fetched head list, *Then* the same outcome.
  - *Given* the git write lock held or `SyncState` syncing, *Then* `Use <branch>`, `Use selected branch`, `Sync now` and `Change back` are disabled with `A sync is running. Wait for it to finish.` and re-enable when the sync ends; Copy details, Choose another branch, Not now stay enabled; a tap that races the lock gets the same message and queues nothing.
  - *Given* the app is backgrounded or the process recreated with the sheet open, *When* it resumes, *Then* the error is re-evaluated: branch now valid closes the sheet with `Already fixed.`; error type changed switches variant; unchanged shows no visible change; a second tap during `Saving…` is ignored. State lives in `GitSyncCoordinator`, so rotation keeps it.
- Result line is persistent (gated; UX-87).
  - *Given* a repair-triggered sync finishes, *Then* the result ("Pulled N commits", "Already up to date" or the amber still-ahead line) is a persistent line, a polite live region announced once, kept until the next sync starts or the user dismisses it; it is not a timed toast.
- First-sync review, consent and manual sync (ungated; pre-mortem P1-1, UX-75, UX-81, UX-104; moved here from Story 2.4 so an A1-only build is complete).
  - *Given* a config whose current `(remote, branch)` does not match `git_first_sync_confirmed_<graphId>` and a pending review (set by a repair or `git set-branch`, key `git_first_sync_review_pending_<graphId>`), *Then* the badge shows "Review first sync" and tapping it opens the review; opening it never syncs.
  - *Given* the review is open, *Then* it shows ahead/behind counts, every remote branch with strays (for example `origin/main`) flagged "not the sync branch", the first 20 local-only commits (subject, date) and the count of files the sync would delete (Task 2.3f); it is read-only. Tapping "Sync now" runs one manual `GitSyncService.sync`; only a `Success` stores the confirmation for that `(remote, branch)` and clears the pending key; an `Error`, `ConflictPending` or `RepairNeeded` result stores nothing and the review stays reachable.
  - *Given* the confirmation hook, *Then* it lives in the common manual-sync `Success` path of `GitSyncService`/`FirstSyncConfirmation`, not in the sheet's button handler: any manual sync of the current `(remote, branch)` that returns `Success` (S14 `Sync now`, the badge sync action, the mass-change typed-count confirm run, the invariant sheet's `Retry`) stores `git_first_sync_confirmed_<graphId>` and clears `git_first_sync_review_pending_<graphId>` and `git_first_sync_previous_branch_<graphId>`; amber `Error(SyncInvariantViolated)`, `ConflictPending`, `RepairNeeded` and `Error` store nothing and keep the pending key (UX-105).
  - *Given* an error badge (red or amber, including `SyncInvariantViolated`) and the pending key set, *Then* the badge keeps showing the worst error, and the sheet it opens carries a `Review first sync` row that opens the review, so the re-entry is never hidden permanently; when the error clears the badge reads `Review first sync` again (state precedence, UX-105).
  - *Given* a repair, *Then* `BranchRepairService` persists the replaced branch as `git_first_sync_previous_branch_<graphId>` beside the pending key (a `git set-branch` records its `from` too); *When* the review reopens from the badge or after process death with a post-sync error, *Then* `Change back to '<previous>'` is offered from that key, and when the key is absent `Change back` is hidden with no placeholder (UX-106).
  - *Given* the sync started from the review ends in `MassChangeBlocked`, `ConflictPending` or `RepairNeeded`, *Then* the review stays open with that outcome's line and an `Open details` action that opens the mass-change confirm (typed count lives there, UX-103), the conflict sheet or the repair sheet; the review hosts none of those confirms (UX-107).
  - *Given* the branch changes again, *Then* the stored confirmation no longer matches (the review is required again; in PR-A2 scheduled sync also stays blocked until a new confirmed manual sync).
  - *Given* the review opens, *Then* the local-only commits render at once, the remote part shows `Checking the remote…` with Cancel and a 15 s bound, `Sync now` stays disabled until the remote numbers load, and a timeout shows `Couldn't reach the remote (timed out after 15 s). Nothing was changed.` with Retry, Copy details and `Sync without preview`; closing the sheet cancels the check.
  - *Given* the remote numbers failed or timed out (never while loading or after they loaded), *When* the user taps `Sync without preview`, *Then* a confirm dialog states `SteleKit couldn't check the remote, so how many commits are ahead or behind, and which branches exist there, is unknown. Syncing can still pull commits and delete files on this device to match the remote. The delete guard still applies.`, focus lands on Cancel, and the confirm button stays disabled until the user types `sync` (trimmed, case-insensitive, UX-91 rules; paste allowed); on Confirm one manual `sync` runs exactly as `Sync now` does, the mass-change and invariant guards unchanged, and a `Success` stores the same confirmation. Cancel or a mismatch changes nothing; the button is also disabled with `A sync is running. Wait for it to finish.` while the lock is held (UX-93).
  - *Given* zero local-only commits, zero files to delete, or a remote with only the sync branch, *Then* the empty-state texts of UX-97 apply (`Nothing here that isn't on the remote yet.`, the `0 files would be deleted` line hidden, `No other branches on the remote.`).
- User-facing copy is plain language (ungated; UX-92).
  - *Given* any badge, sheet, banner, notification, wizard or result-line string, *Then* it contains none of `symref`, `ls-remote`, `invariant`, `DB-only`, `tracking ref`, `refspec`, `detached HEAD`, `shallow`, `OID`, `SQLite`, `WAL` (console output and Copy details excepted); the detached-HEAD badge reads "Your notes folder isn't on a branch — tap for details".
**Files**: `jvmCommonMain/.../git/RemoteDefaultBranch.kt` (new), `commonMain/.../git/GitRepository.kt`, `commonMain/.../ui/screens/git/GitSetupScreen.kt`, `GitSetupStep4Branch.kt`, `GitSetupScreenSaveLogic.kt`, `commonMain/.../ui/screens/git/BranchRepairSheet.kt` (new), `commonMain/.../git/BranchRepairService.kt` (new in Task 2.2a2, ungated; shared with console `git set-branch`), `commonMain/.../ui/GitSyncCoordinator.kt` (existing at this path, **not** `git/`); for Task 2.2d0: `commonMain/.../git/FirstSyncConfirmation.kt` and `commonMain/.../ui/screens/git/FirstSyncPreviewSheet.kt` (both new).

##### Task 2.2a: `detectDefaultBranch` + port (M, ungated)
- Files: `jvmCommonMain/.../git/RemoteDefaultBranch.kt`, `commonMain/.../git/GitRepository.kt` (default method returning `Unreachable`/`NotSupported`), `jvmMain` + `androidMain` overrides using `testRemoteViaLsRemote` (`GitOperationSupport.kt:475`) shape, 15 s timeout, token pre-resolved in suspend context, `configureTransport` auth. Test: `jvmTest/.../git/RemoteDefaultBranchTest.kt` (symref, OID-fallback ambiguity, empty, unreachable -> `Unreachable`, offline never reported as "branch missing"). Property test over head sets (kotest-property): result never picks a branch not in the head list.

##### Task 2.2a2: `BranchRepairService` with read-back (S, ungated)
- Files: `commonMain/.../git/BranchRepairService.kt` (new). Applies a confirmed `BranchRepairProposal` through `GitConfigRepository.saveConfig`, reads the row back and returns an error if the read-back differs (mutation read-back rule), and builds sanitized copy-details text (no URL userinfo). Consumed by Task 2.2d (sheet) and Task 5.2b (`git set-branch`). Test: `businessTest/.../git/BranchRepairServiceTest.kt` (read-back equal; read-back mismatch is an error; copy details sanitized; reversible `main -> master -> main`). Done when: the service exists on every plan branch, including when Tasks 2.2c-e are no-go (this resolves the earlier contradiction where the only creating task was gated).

##### Task 2.2b: Clone sets `remoteBranch` from the checked-out branch (S, ungated)
- Files: `commonMain/.../ui/screens/git/GitSetupScreenSaveLogic.kt`, `jvmMain/.../git/JvmGitRepository.kt`, `androidMain/.../git/AndroidGitRepository.kt` (return local branch from clone), `androidMain/.../git/GitCloneWorker.kt`. Test: `RemoteBranchResolutionTest` clone case + save-logic unit test.

##### Task 2.2c: Wizard prefill, branch chooser, validation (M, gated)
- Files: `GitSetupScreen.kt`, `GitSetupStep4Branch.kt`, `GitSetupStep5*` (test connection checks ref exists). Replace bare text with prefilled field + dropdown of remote heads; ambiguity forces explicit choice; offline state does not claim "branch missing"; lookup shows `Checking the remote…` with `Skip` and a 15 s fallback to free text (UX-80); free-text branch field has autocorrect and auto-capitalization off (UX-84). Test: `androidUnitTest/.../GitSetupBranchStepTest.kt` (Robolectric) for prefill, ambiguity, validation block.

##### Task 2.2d0: First-sync review sheet, consent flag and manual `Sync now` (M, ungated, PR-A1; split out of Task 2.4h)
- Files: `commonMain/.../git/FirstSyncConfirmation.kt` (`git_first_sync_confirmed_<graphId>` = `<remote>/<branch>`, set only after a manual `sync` returns `Success`; plus `git_first_sync_review_pending_<graphId>`, written by `BranchRepairService` on a repair or `git set-branch` and cleared on confirmation, plus `git_first_sync_previous_branch_<graphId>` holding the replaced branch for `Change back`, cleared with it; the confirmation write is a hook in the common manual-sync `Success` path so badge sync, mass-change confirm and invariant `Retry` also confirm; the minimum policy input that Task 2.4h later wires into `ScheduledSyncPolicy`), `commonMain/.../ui/screens/git/FirstSyncPreviewSheet.kt` (read-only: ahead/behind, all remote heads with strays flagged, first 20 local-only commits, files-to-delete count; `Sync now`; progressive load with Cancel and a 15 s bound per ux S14 / UX-81; timeout state with Retry, Copy details and `Sync without preview` behind the typed `sync` confirm, UX-104), `GitSyncService.kt` (preview data via existing ports `ls-remote`, RevWalk, Task 2.1d helper and Task 2.3f counter; no change to `sync()` itself), `SyncStatusBadge.kt` (A1 badge `Review first sync` driven by the pending key; A2 widens it to every unconfirmed config), `ui/GitSyncCoordinator.kt` (entry from the repair flow with the heading `Branch set to master. Review before syncing.`; its `Sync now` is the only sync trigger of the repair; lock-disabled actions per UX-93). Empty states per UX-97. Not in A1: `ScheduledSyncPolicy` enforcement, the notification entry and the launch banner (Task 2.4h, PR-A2).
- Test: `businessTest/.../git/FirstSyncConfirmationTest.kt` (stored only on `Success`; `Error`/`ConflictPending`/`RepairNeeded` store nothing; branch change invalidates; pending key set by repair and cleared by confirmation; hook fires on every manual-sync `Success` entry point and not on amber `SyncInvariantViolated`; previous branch persisted, survives a coordinator restart, absent value hides `Change back`); `businessTest/.../ui/GitSyncCoordinatorTest.kt` also covers the `Open details` routing for `MassChangeBlocked`/`ConflictPending`/`RepairNeeded`; `commonTest/.../git/SyncStatePrecedenceTest.kt` (error badge with pending key exposes the `Review first sync` row); `businessTest/.../git/FirstSyncPreviewLoaderTest.kt (local first, remote cancellable, 15 s timeout never claims a missing branch; fake clock); `businessTest/.../ui/GitSyncCoordinatorTest.kt` (`Sync without preview` offered only after a failure or timeout, runs `sync` once only after the typed confirm, stores the confirmation only on `Success`, disabled while the lock is held); Robolectric `FirstSyncPreviewSheetTest` (UX-75 with a stray `origin/main` and 25 local-only commits shows 20; UX-81 loading and timeout; UX-104 dialog text, default focus on Cancel, mismatch keeps the button disabled; UX-97 empties; the badge opens the sheet in an A1-only wiring with no scheduler present).

##### Task 2.2d: Confirmed repair flow incl. S2 variants and result line (M, gated; repair button only, the read-only variants are Task 2.1g; depends on Task 2.2d0, whose review it routes into)
- Files: `BranchRepairSheet.kt` (consumes `BranchRepairService` from Task 2.2a2), `SyncStatusBadge.kt` routing, `ui/GitSyncCoordinator.kt` (apply -> `saveConfig` -> read-back -> route to the first-sync review of Task 2.2d0, same PR; the review's `Sync now` runs `sync`; stale-proposal check, lock-disabled actions and resume re-evaluation per UX-93), banner via existing `GitDetectionBanner` pattern once per failed sync (UX-14). All S2 variants and result-line states in the Story ACs are covered, including the loading/timeout/cancel states (UX-80) and the persistent result line (UX-87; no timed toast, `liveRegion = Polite`). Test: coordinator unit test with `StubGitRepository`/fake config repo (read-back mismatch is an error, per mutation-read-back rule; repair never calls `sync` by itself; stale proposal writes nothing; actions disabled while the lock is held); Robolectric sheet tests echoing the exact effect for each variant.

##### Task 2.2e: Background path reads the repaired row (S, gated)
- Files: `androidMain/.../git/WorkManagerSyncScheduler.kt` (no logic change expected), test `androidUnitTest/.../git/WorkerConfigMappingTest.kt` mapping row -> `GitConfig` after repair.

#### Story 2.3: Push alignment, post-sync invariants and repository-state guard (evidence-independent)
**As the** owner, **I want** push, fetch and merge to agree on the branch, a post-sync truth check, and a guarantee that conflict-marker files are never pushed, **so that** "Success" always means my phone has the remote tip and my remote is not corrupted.
**Acceptance Criteria**:
- Push targets the configured branch explicitly.
  - *Given* local branch `master`, `remoteBranch="master"`, one local commit, *When* `push` runs, *Then* origin's `refs/heads/master` equals local HEAD and no `refs/heads/main` appears on origin.
- Merged count is derived from HEAD before/after.
  - *Given* a shallow clone behind by 3 remote commits, *When* `sync` completes a merge, *Then* `Success.remoteCommitsMerged == 3` even if `countRemoteCommitsBestEffort` would return 0.
- Post-sync invariant.
  - *Given* a sync whose merge is a no-op while `origin/master` tip is not an ancestor of HEAD, *When* `sync` finishes, *Then* the state is `Error(SyncInvariantViolated)` (rendered amber, Task 2.1g), not `Success`.
  - *Given* HEAD detached, *Then* `Error(DetachedHead)`.
- False-green matrix: (a) unresolved ref, (b) local ahead only, (c) diverged, (d) empty remote, (e) detached HEAD, (f) success with tip not merged, **(g) abort failed / interrupted merge (repository left in `MERGING`)** each yield the specified non-green or correct state.
  - *Given* case (d) empty remote, *When* `sync` runs, *Then* `Error(RemoteEmpty)` with message "Remote is empty — push a commit first or check the URL" and no `RemoteBranchNotFound`.
  - *Given* case (g) the repo is in `MERGING` with unmerged paths (including after process death mid-merge), *When* `sync` or a scheduled run starts, *Then* `sync` returns `SyncState.RepairNeeded` and the scheduled run `Skip(RepoState)`, both before any `stageSubdir`/`commit`/`merge`/`push` (zero such calls on the repository spy); only `fetch` may run. Git's own repository state is the persistent record, so no private marker exists.
- Conflict-marker files can never be pushed.
  - *Given* a working tree where a tracked text file (any extension) contains a line-start `<<<<<<< ` and a later line-start `>>>>>>> ` pair outside a fenced code block, *When* `sync` reaches commit, *Then* it refuses with a typed error naming the file, nothing is committed or pushed, and origin is unchanged.
  - *Given* a marker-laden commit that already exists locally (made earlier or by another path) with nothing staged, *When* `sync` reaches push, *Then* the scan of the net tree diff `origin/<branch>` vs `HEAD` finds it and push is refused.
  - *Given* a marker added in local commit 1 and removed in local commit 2 (net diff clean), *Then* push is allowed (a per-commit scan would wedge sync forever). *Given* no `refs/remotes/<remote>/<branch>` ref exists, *Then* the net-diff scan is skipped, and push still fails closed because the unresolved-ref error (`RemoteBranchNotFound`) already blocks it.
  - *Given* a note that documents git syntax inside a fenced code block, or a marker file larger than the scan cap, *Then* the fenced note does not block sync, and the oversize file blocks it (fail closed, never skipped).
  - *Given* `applyJournalMerge` or `resolveConflicts` produces a commit containing markers, *Then* the same scan refuses the commit/push.
- Mass-change guard (pre-mortem #3, P1).
  - *Given* the net tree diff `origin/<branch>` vs `HEAD` (at push) or the staged diff vs the `HEAD` tree (at commit) deletes more than `max(20, 5% of the files tracked in the previous tree)` files, or more than 10 files under `journals/`, *When* `sync` reaches commit or push, *Then* it returns `Error(MassChangeBlocked(deleted, threshold, sample))` naming the first 50 deleted paths; nothing is committed or pushed.
  - *Given* a foreground manual sync hits the guard, *Then* the detail sheet offers a typed-count confirm ("delete N files") that retries once with the guard lifted for that run only; *Given* a scheduled/timer/worker run hits it, *Then* the guard is never lifted: the run logs `Skip(MassChange)`, the push does not happen and the badge shows the amber `MassChangeBlocked` text.
  - *Given* a working tree whose `logseq/journals/` subdirectory is missing (SAF shadow desync, partial reload, restore swap, wikiSubdir mismatch), *When* `stageSubdir` + commit would delete it, *Then* the guard refuses and origin is unchanged.
  - *Given* any production source, *Then* no push uses a force refspec or `setForce(true)` (source-audit test `NoForcePushAuditTest`).
  - Thresholds are initial values (INFERRED) held in one `MassChangeThresholds` value (compiled-in defaults, overridable through the three Settings keys `git_mass_change_min_files`, `git_mass_change_pct`, `git_mass_change_max_journals`; read at guard time, a read failure uses the defaults). They are calibrated on the owner's real 1.6k-journal graph in Task 7.1d; the foreground typed-count confirm remains the escape hatch for a legitimate large cleanup.
- Merge diff behavior is characterized, not assumed; wikiSubdir is covered.
  - *Given* `doMerge` uses `FastForwardMode.NO_FF` (`JvmGitRepository.kt:273`, `AndroidGitRepository.kt:287`) so every merge with remote changes is a merge commit, *When* changed files are computed for a merge of 2 remote commits touching `logseq/journals/a.md` and `README.md`, *Then* the characterization test records what `computeChangedGitRelativePaths` returns; the earlier "diff range is wrong" claim is **UNPROVEN** and a fix is made only if the test shows a defect.
  - *Given* `wikiSubdir="logseq"`, *Then* only paths under `logseq/` reach `reloadFiles`, `README.md` does not, and `/dev/null` deletions are filtered from the reload set.
  - *Given* an unreadable path in `reloadFiles`, *Then* a WARN naming the path is emitted.
**Files**: both `*GitRepository.kt`, `jvmCommonMain/.../git/GitMergeDiff.kt`, `commonMain/.../git/GitSyncService.kt`, `commonMain/.../git/GitRepository.kt`, `commonMain/.../db/GraphLoader.kt` (WARN only), `jvmTest/.../git/SyncInvariantTest.kt`, `jvmTest/.../git/GitMergeDiffTest.kt`, `businessTest/.../git/GitSyncServiceInvariantTest.kt`, `businessTest/.../git/NoForcePushAuditTest.kt`.

##### Task 2.3a: Explicit push refspec (S)
- Files: `JvmGitRepository.kt`, `AndroidGitRepository.kt` (`doPush`). Test: `SyncInvariantTest` push-alignment case incl. branch name with `/`.

##### Task 2.3b: `MergedCommitCount` from HEAD before/after (S)
- Files: `jvmCommonMain/.../git/RemoteTrackingRef.kt`, `GitSyncService.kt` (use count from the repository result), `commonMain/.../git/model/` `MergeResult` (list every construction site: both repositories, `StubGitRepository`, and the `GitSyncService` consumer at implementation time via `sg`). Test: shallow-clone case.

##### Task 2.3c: Post-sync ancestor/branch invariant + false-green matrix (M)
- Files: `GitSyncService.kt`, `GitRepository.kt` (`isRemoteTipMerged(config)` default `NotSupported` + JGit impl in shared helper), `businessTest/.../git/GitSyncServiceInvariantTest.kt` using `buildGitSyncTestService` (`GitSyncServiceTestFixtures.kt`) and `StubGitRepository`. Done when: all matrix cases (a)-(g) are tests, and the 2.1 old-code failure still reproduces if `RemoteTrackingRef` is reverted (mutation check shown once).

##### Task 2.3d: Merge-diff characterization, wikiSubdir test, deletion filter, `reloadFiles` WARN (S)
- Files: `jvmCommonMain/.../git/GitMergeDiff.kt`, `jvmTest/.../git/GitMergeDiffTest.kt`, `commonMain/.../db/GraphLoader.kt` (one WARN at the `?: continue`, edit (3) in the Tech Debt table). Write the characterization test first against the NO_FF merge commit; fix only if it shows a defect. Filter `/dev/null` deletions from the reload set (propagation of remote deletions is the recorded follow-up).

##### Task 2.3e: Repository-state guard and conflict-marker scan on every commit and push path (M)
- Files: `commonMain/.../git/GitRepository.kt` (new `repositoryStateSafe(config)`/`findConflictMarkers(config)` members with defaults returning the safe answer, implemented in the shared jvmCommon helper using `Repository.getRepositoryState()`), `GitSyncService.kt`, both `*GitRepository.kt`, `commonMain/.../error/DomainError.kt` (reuse `SyncInvariantViolated` or add `ConflictMarkersPresent(files)` in 2.1a's exhaustive set).
- **Scan definition**: the added lines (`+` lines) of (i) the **net tree diff** of the `origin/<branch>` tree against the `HEAD` tree (a single tree-to-tree diff, equivalent to `git diff origin/<branch>...HEAD`; **not** a per-commit patch walk, which would keep flagging a marker added in one commit and removed in the next and wedge sync forever) and (ii) the staged diff at commit time, over **all text files** (binary detected by JGit and skipped, not `.md`-only). A marker is a line starting `<<<<<<< ` with a later line starting `>>>>>>> ` in the same file; lines inside fenced code blocks (``` or ~~~) are ignored so a note documenting git syntax cannot wedge sync. The size cap (per file and total bytes scanned) **fails closed**: an oversize file or an exceeded total returns `ConflictMarkersPresent`/`ScanIncomplete`, never "skipped".
- **No remote-tracking ref** (new branch, empty remote, unresolved ref): scan (i) is skipped because there is no base tree, and push stays fail-closed because the unresolved-ref error (`RemoteBranchNotFound`/`RemoteEmpty`, Story 2.1) already blocks the push before it is reached; scan (ii) still runs at commit. Tested.
- **Entry points guarded** (list produced by `grep -nE "gitRepository\.(commit|push)\(" kmp/src` over production sources, 2026-10-10): `GitSyncService.kt:268` (`sync()` commit), `:369` (`sync()` push), `:497` (`commitLocalChanges`), `:561` (`resolveConflicts` commit), `:604` (`applyJournalMerge` commit), `:614` (`applyJournalMerge` push). `resolveConflicts` and `applyJournalMerge` legitimately run in `MERGING`, so they get the marker scan only (staged diff and `origin..HEAD`), not the state guard. `commitLocalChanges` gets both. Out of scope but recorded (the closed-app runner no longer commits or pushes, so it needs no guard): `jvmMain/.../cli/SyncMain.kt:155,207,271` (desktop CLI, own private commit/push; named in the PR description, or routed through the same helper if cheap) and `wasmJsMain/.../git/WasmGitWriteService.kt` (separate write path, documented as exempt). Re-run the grep at implementation start; any new hit is guarded or documented as exempt in this task's PR.
- Test: `SyncInvariantTest` + `GitSyncServiceInvariantTest` for `MERGING`, markers (staged, pre-existing local commit, added-then-removed across two commits => allowed, no remote-tracking ref => scan skipped and push blocked by the unresolved-ref error, fenced block, oversize), detached HEAD, and one test per guarded entry point (`commitLocalChanges`, `resolveConflicts`, `applyJournalMerge`). Applies to manual and scheduled sync alike. On `MERGING` at entry `sync()` returns `SyncState.RepairNeeded` (Task 2.1f) whose sheet routes to the existing conflict/abort UI.

##### Task 2.3f: Mass-change guard on every commit and push path (M)
- Files: `commonMain/.../git/GitRepository.kt` (`findMassDeletions(config)`, default returns the safe answer), the shared jvmCommon tree-diff helper from Task 2.3e, `GitSyncService.kt` (same six entry points as Task 2.3e: `:268`, `:369`, `:497`, `:561`, `:604`, `:614`), `commonMain/.../error/DomainError.kt` (variant from Task 2.1f), `businessTest/.../git/NoForcePushAuditTest.kt`. Foreground confirm lifts the guard once per run; scheduled runs never lift it. **Configurable thresholds**: `MassChangeThresholds` (defaults `max(20, 5%)` files, 10 journals) read from the three Settings keys; add them to the `settings` allowlist (Task 5.6a) so they can be tuned from the console without a rebuild.
- Test: `SyncInvariantTest` + `GitSyncServiceInvariantTest`: below threshold passes; overridden thresholds change the verdict (a lowered `git_mass_change_min_files` blocks a 5-file deletion, a raised one passes a 25-file deletion); above blocks; a tree with a missing subdir blocks; foreground confirm retries once; scheduled run cannot lift it; one test per guarded entry point; `NoForcePushAuditTest` source audit. Applies to manual and scheduled sync alike.

#### Story 2.4: Scheduled full sync while the app is open; background is fetch-only (PR-A2; owner decision, ADR-004)
**As the** owner, **I want** remote commits pulled whenever the app is open (within the interval) or when I tap Sync, and a background fetch that keeps a behind-count fresh when the app is closed, **so that** I always know how stale my phone is and no background job ever touches my working tree.
**Owner decisions (2026-10-10, final)**: (a) with the app closed, background work is fetch-only plus a badge/behind-count; the full commit + merge + push runs only while the app is open (timer or worker fast path) or on manual sync. (b) The owner's active graph is the cloned app-private (non-`saf://`) graph; SAF-backed graphs stay fetch-only for scheduled work.
**Acceptance Criteria**:
- Policy gates.
  - *Given* `EditLock` held, *When* a tick fires, *Then* `ScheduledSyncPolicy` returns `Skip(EditingInProgress)` and no git call is made; `GitSyncBusyCounter == 1` => `Skip(Busy)`; vault locked => `Skip(VaultLocked)`; in-memory conflict => `Skip(ConflictPending)`.
  - *Given* a fresh service instance (simulated process death) over a repository in `MERGING`, *Then* `Skip(RepoState)` (git's own state is the persistent record).
- Scheduled sync honors the first-sync consent (pre-mortem P1-1; the review sheet, the consent flag and the manual `Sync now` are PR-A1's Task 2.2d0 and are already shipped when this story builds).
  - *Given* a config whose current `(remote, branch)` has no `git_first_sync_confirmed_<graphId>` entry (first run after upgrade, or the branch was repaired by Task 2.2d or `git set-branch`), *When* a timer tick or the worker fires, *Then* the policy returns `Skip(FirstSyncUnconfirmed)`, only a fetch runs, and the badge shows "Review first sync" for every unconfirmed config (UX-75; in A1 only after a repair), opening Task 2.2d0's review.
  - *Given* a confirmation stored by Task 2.2d0 before A2 was installed, *Then* the policy reads it unchanged (no migration) and returns `Run` for that `(remote, branch)`.
  - *Given* the branch changes again, *Then* the stored value no longer matches and scheduled sync stays blocked until a new confirmed manual sync.
- `autoCommit` is honored.
  - *Given* `GitConfig.autoCommit=false` and a dirty tree, *When* a scheduled or manual sync runs, *Then* nothing is committed, the merge is not attempted, fetch results are shown as `MergeAvailable` with \"Uncommitted changes — commit or enable auto-commit\"; *Given* `autoCommit=true` and a dirty tree in the live process, *Then* local edits are committed (current behavior, after the Task 2.3e/2.3f guards) before merge.
- Desktop timer runs full sync.
  - *Given* a remote 2 commits ahead, an idle editor and a confirmed first sync, *When* the desktop timer ticks, *Then* `remoteCommitsMerged == 2`, `reloadFiles` received the changed journal paths, local `autoCommit` edits were committed and pushed.
- Android live-process delegation through the existing registry.
  - *Given* the app process with an active graph registered in `GitSyncServiceRegistry` (registration now exists in production), *When* `GitSyncWorker` runs, *Then* it calls `runScheduledSync` (not `fetchOnly`), producing the same state as a manual sync.
  - *Given* a configured interval of 5 minutes on Android, *Then* the periodic request uses 15 minutes and the setting text says so.
- SAF graphs are fetch-only for scheduled work (owner decision b).
  - *Given* a graph for which `isAppOwnedPath(repoRoot)` is false or throws (SAF-backed, unresolvable `saf://`), *When* the timer or a live worker run fires, *Then* the policy returns `FetchOnly(Saf)`: one fetch, no merge/commit/push, amber badge \"Updates fetched; open the app to merge\" (UX-71). A manual Sync is unchanged.
- The closed-app runner is fetch-only and cannot touch the tree.
  - *Given* no live graph, *When* the worker runs, *Then* `ColdSyncRunner` takes the `GitWriteLockNaming` lock, performs exactly one fetch, persists `behindCount`/`lastFetchedAt`/outcome and returns `Fetched(behindCount, AppClosed)` or `Error(cause)`; on a repository spy the counts of `merge`, `commit`, `push`, `stageSubdir`, `checkout` and `abortMerge` are all 0.
  - *Given* a dirty tree, a tree in `MERGING`, or a clean tree, *Then* every working-tree file is byte-identical after the run (property test over generated dirty sets).
  - *Given* a typed fetch error (`RemoteBranchNotFound`, `RemoteEmpty`), *Then* it is recorded as the outcome and shown by the chip; it is never swallowed.
- Staleness is always visible and cannot look healthy while pulling nothing (pre-mortem P1-2).
  - *Given* any graph with git sync, *Then* the sync badge shows a permanent chip \"last merged <age> · behind <n>\" from `SyncStaleness`; it turns amber when `lastMergedAt` is older than 2x the effective interval and `behindCount > 0`, or when the last N fetch outcomes were errors (UX-76).
  - *Given* the last 10 background outcomes, *Then* `diag` and `git doctor` print them (time, outcome, behind count).
  - *Given* a graph classified fetch-only (Saf, or the predicate threw), *Then* the chip says \"fetch-only (<reason>)\" and is never green, so a mis-classified graph is visible.
  - *Given* a background fetch finds `behindCount > 0` older than 2x the interval, or a typed fetch error on first occurrence, *Then* one low-importance Android notification \"N remote commits are waiting — open SteleKit to merge\" is posted (a Settings toggle, default on; denial of the notification permission degrades to the chip only) and is cleared when `behindCount == 0`. At the next launch with `behindCount > 0` the banner \"Updates are waiting — N remote commits not merged yet\" with Review and Dismiss appears (UX-72, resolves G8); Dismiss only hides the banner.
- Notification tap and chip before any merge (UX-101, UX-97).
  - *Given* the updates-waiting notification, *When* tapped, *Then* the named graph's first-sync review (Task 2.2d0) opens if `FirstSyncUnconfirmed`, otherwise the sync status sheet; no sync starts; with the app already open it adds one back-stack entry (Back returns to the previous screen), focuses an already-open sheet, shows `Up to date` if `behind` is already 0, and on cold start lands on the same destination after graph load without a second launch banner. The pending intent carries the graph id; a graph not in the registry opens the current graph's sheet with `Updates are waiting for <graph name>`. Pure routing function `NotificationTapRouter` in `commonMain` (table test) plus a Robolectric `SyncNotifierTest` intent check.
  - *Given* a graph with no `lastMergedAt`, *Then* the chip reads `no merge yet · behind <n>` (neutral; `checking…` while behind is unknown) and turns amber only after twice the effective interval since the graph was first seen; never `last merged 0 min ago`.
- Banner precedence (UX-86).
  - *Given* any subset of four conditions {mismatch, first-sync review pending (launch banner), `behind > 0` (`Updates are waiting`), `Review first sync` badge after "Not now"}, *Then* at most one banner shows, the highest present of mismatch, first-sync review, `Updates are waiting`; the `Review first sync` badge is never a banner, it is the re-entry after "Not now" or a dismissed first-sync banner and stays visible (a dismissed first-sync review is replaced by the badge, not lost); the badge and chip stay visible; dismissing or resolving the top one reveals the next; each banner keeps its own dismiss rule.
- Notification permission flow (UX-89).
  - *Given* Android 13+ and the toggle `Notify me when updates are waiting` turned on, *Then* a rationale precedes the `POST_NOTIFICATIONS` request, which is never requested at first launch; Not now, denied and permanently denied leave sync and the chip/banner untouched, and permanent denial shows the toggle disabled with `Open system settings`. Below API 33 no permission is requested.
- User abort does not discard unrelated edits (R5).
  - *Given* the repo is in `MERGING` and the user has also edited a non-conflicted tracked file, *When* the user taps Abort, *Then* the file is snapshotted to the app-private recovery dir, the user confirms a list of those files, and after `abortMerge` the file is byte-identical to its pre-abort content (Task 2.4i).
- Metric 4 soak is satisfiable with the app open: three consecutive scheduled syncs (>=15 minutes apart on Android, app process alive) leave `behind == 0` on a clean, conflict-free graph. With the app closed only the behind-count is kept fresh; merges happen when the app is next open or on tap.
**Files**: `commonMain/.../git/ScheduledSyncPolicy.kt` (new), `commonMain/.../git/SyncStalenessStore.kt` (new, Settings-backed), `commonMain/.../git/FirstSyncConfirmation.kt` (Task 2.2d0, A1; read by the policy here), `commonMain/.../git/GitSyncServiceRegistry.kt` (moved from `androidMain/.../git/WorkManagerSyncScheduler.kt:252`, API unchanged), `commonMain/.../ui/GraphContentGitSyncSetup.kt` (register/unregister beside `registerGitSyncService` at `:43-46`), `commonMain/.../git/GitSyncService.kt` (`runScheduledSync`, `startPeriodicSync` at `:652` calls it, `autoCommit` read), `commonMain/.../git/BackgroundSyncScheduler.kt`, `androidMain/.../git/WorkManagerSyncScheduler.kt`, `androidMain/.../git/ColdSyncRunner.kt` (new, fetch-only), `androidMain/.../git/SyncNotifier.kt` (new), `jvmMain/.../git/DesktopSyncScheduler.kt`. `abortMerge` (`GitRepository.kt:69`) is **reused unchanged**; no `MergeAbortResult`; no cold-worker markers exist.

##### Task 2.4a: `ScheduledSyncPolicy` + `GitSyncService.runScheduledSync` (M)
- Policy returns `Run` / `FetchOnly(reason)` / `Skip(reason)` (reasons in the glossary); `runScheduledSync` = policy + `sync()` or `fetchOnly`. Test: `commonTest/.../git/ScheduledSyncPolicyTest.kt` (table + property: never `Run` when any blocking input, including repository state, `FirstSyncUnconfirmed` and non-app-owned path, is set); `businessTest` service test that `runScheduledSync` calls `sync()` once and logs the decision.

##### Task 2.4b: Desktop timer and `startPeriodicSync` use `runScheduledSync` (S)
- Files: `GitSyncService.kt:652`, `jvmMain/.../git/DesktopSyncScheduler.kt` wiring site. Test: fake-clock `jvmTest` with temp origin; interval setting \"off\" disables.

##### Task 2.4c: Registry promotion, production registration, worker fast path, 15-minute floor (S)
- Files: move `GitSyncServiceRegistry` to `commonMain/.../git/GitSyncServiceRegistry.kt` (keeping `register/unregister/getService`; `androidUnitTest/.../WorkManagerSyncSchedulerRetryOwnerTest.kt` still compiles), call `register(graphId, service)`/`unregister` in `GraphContentGitSyncSetup.kt` `DisposableEffect`, change `WorkManagerSyncScheduler.kt:137-143` from `service.fetchOnly(graphId)` to `service.runScheduledSync(graphId)`, route the slow path (`:213`) to `ColdSyncRunner`, clamp the periodic interval to `max(setting, 15)` minutes. **Make the registry thread-safe** (written from the UI thread, read from a WorkManager thread): back it with `ConcurrentHashMap` on JVM/Android, or a `kotlinx.atomicfu`/`Mutex`-guarded map if kept in pure commonMain; keep the API. Test (extra): concurrent register/unregister/getService stress shows no exception and a registered service is always found. Test: `androidUnitTest` worker test with the real registry and a fake service; a registered service is found in the fast path (guards the dead-fast-path regression).

##### Task 2.4d: Honor `GitConfig.autoCommit` in `sync()` (S)
- Files: `GitSyncService.kt` step 5 (commit only when `config.autoCommit`), `businessTest` tests for both values. Done when: grep shows `.autoCommit` is read in the sync pipeline (today only the config repository and diagnostics line read it).

##### Task 2.4e: `ColdSyncRunner` is fetch-only (S)
- Files: `androidMain/.../git/ColdSyncRunner.kt`, `WorkManagerSyncScheduler.kt` slow path (`runSlowPathFetch` at `:213`). Holds the `GitWriteLockNaming` lock for the single fetch; computes `behindCount` with the Task 2.1d ahead/behind helper; writes `SyncStaleness`; returns `ColdSyncOutcome`. The existing slow path already builds `AndroidGitRepository(context, PlatformFileSystem())` with an uninitialized `PlatformFileSystem` because fetch never touches the working tree (`WorkManagerSyncScheduler.kt:173-183`); that premise now stays true. Test: `jvmTest/.../git/ColdSyncRunnerTest.kt` against temp repos: spy counts all 0 for merge/commit/push/stageSubdir/checkout/abortMerge (clean, dirty, `MERGING`); dirty files byte-identical (property); typed fetch error recorded as the outcome; worker unit test for the happy path. (The earlier preflight, marker, kill-point and late-edit machinery is deleted: nothing in the closed-app path can write the tree.)

##### Task 2.4f: Staleness store, chip, notification and launch banner (M)
- Files: `SyncStalenessStore.kt` (`lastMergedAt` written by `GitSyncService` on `Success`, `lastFetchedAt`/`behindCount` after every fetch, last-10 outcome history), `commonMain/.../ui/components/SyncStatusBadge.kt` (chip, amber after 2x interval, fetch-only reason text), launch banner reusing the `GitDetectionBanner` pattern, `androidMain/.../git/SyncNotifier.kt` (reuse an existing notification channel if the implementer finds one by grep; otherwise one low-importance channel; handles the `POST_NOTIFICATIONS` runtime permission in context: rationale, then request, only from the Settings toggle (key `git_sync_notify_enabled`), states per ux S15 / UX-89), a small `BannerPrecedence` pure function ordering the mismatch banner, first-sync review and `Updates are waiting`, and keeping the `Review first sync` badge as the post-"Not now" re-entry (UX-86), `diag`/`git doctor` history lines, Settings toggle. Test: `commonTest/.../git/BannerPrecedenceTest.kt` (table of all 16 subsets of the four conditions {mismatch, first-sync review, updates waiting, `Review first sync` badge}; the badge never counts as a banner and survives a dismissed first-sync banner), `commonTest/.../git/StalenessChipStateTest.kt` (property: chip state equals the specified function of age, behind, interval, last-N outcomes and fetch-only reason for every generated input; never green when fetch-only), and the notifier permission-state table (granted, not asked, denied once, permanently denied, API < 33); `commonTest` store tests (history capped at 10, ordering); Robolectric chip/banner tests (UX-76, UX-72, UX-71); `androidUnitTest` notifier test with a fake `NotificationManager` (posted once per behind-count change, cleared at 0, permission denied => no post).

##### Task 2.4g: `isAppOwnedPath(repoRoot)` path-mode predicate (S)
- Files: `androidMain/.../git/` predicate also used by `git doctor` (Task 5.2c), the policy and Task 7.1a. **Predicate source**: the one `AndroidGitRepository` uses to choose shadow mode, `AndroidGitShadowSupport.shadowWorktreeFor`/`resolveForJGit` (`androidMain/.../git/AndroidGitRepositoryShadow.kt:41,92`; exposed as `AndroidGitRepository.shadowWorktreeFor`/`resolveForJGit` at `AndroidGitRepository.kt:538,545`, both `internal`). True only if `!repoRoot.startsWith(\"saf://\")`, `shadowWorktreeFor(repoRoot) == null`, and `resolveForJGit(repoRoot)` is an existing directory, not a `saf://` string, containing `.git`. **Default is false (fetch-only)** on any other result or any exception. Test: `androidUnitTest` table (plain path => true; `saf://` with no resolver => false; `saf://` with a fast-path resolver => false for scheduled work, conservative; resolver throws => false; non-existent directory => false) and a policy test that false yields `FetchOnly(Saf)` with zero `merge`/`push` calls. Desktop/JVM: always true.

##### Task 2.4h: Scheduled-policy enforcement, notification entry and badge widening for the first-sync consent (S, PR-A2; the review sheet, consent flag and manual `Sync now` moved to Task 2.2d0 in PR-A1)
- Files: `ScheduledSyncPolicy.kt` input (reads `FirstSyncConfirmation` from Task 2.2d0 and returns `Skip(FirstSyncUnconfirmed)`), `SyncStatusBadge.kt` (widen `Review first sync` from "pending after a repair" to every unconfirmed config, e.g. first run after upgrade), launch banner and `NotificationTapRouter` destination (Task 2.4f consumes it), staleness integration (`Skip(FirstSyncUnconfirmed)` fetch still updates `behindCount`). No new sheet or consent state is created here. Test: `commonTest` policy table row `Skip(FirstSyncUnconfirmed)` until a confirmation exists and `Run` after (no repository calls beyond fetch); `businessTest` an A1-written confirmation unblocks the scheduler unchanged; Robolectric badge test for the widened condition.

##### Task 2.4i: Preserve uncommitted edits before the user-initiated `abortActiveMerge` hard reset (S)
- Files: `GitSyncService.kt` (`abortActiveMerge` at `:634`), `commonMain/.../git/GitRepository.kt` only if a snapshot helper is needed (`abortMerge` at `:69` itself is unchanged), `GraphDialogLayer.kt:323` / `GitSyncCoordinator.kt:191` (confirm dialog), app-private recovery dir `git-abort-recovery/<graphId>/<timestamp>/`. Pre-existing exposure in the live path (the conflict UI's only abort is a HARD reset and the user may have edited files meanwhile); kept because a `RepairNeeded` sheet now routes to it.
- Behavior: before calling `abortMerge`, run `status`; compute the modified paths that are **not** conflicted (`status.modified`/`added`/`removed` minus unmerged paths). If none, proceed as today. If any: (1) snapshot each file's bytes to the app-private recovery dir (fail closed: if the snapshot cannot be fully written, do not reset, return an error), (2) show a confirmation listing those files and the recovery location, (3) call `abortMerge`, (4) restore the snapshotted files over the reset tree and re-run `status` to confirm they are byte-identical to the snapshot. The recovery copy is kept (not deleted) until the next successful `sync()`. Cancel => no reset, nothing changed.
- Tests: `jvmTest` against a temp repo in `MERGING` with (a) conflicted paths only => abort proceeds, no snapshot dir; (b) a modified non-conflicted tracked file => snapshot written, file byte-identical after abort, confirmation required; (c) snapshot write failure injected => `abortMerge` call count 0, tree untouched; (d) user cancels the confirm => `abortMerge` call count 0; (e) property test: for any generated set of modified non-conflicted files, post-abort contents equal pre-abort contents.

#### Story 2.5: Conditional remedies for alternative causes (taken only if Task 0.3b selects the matching branch)
**As the** owner, **I want** a ready fix path for each alternative cause, **so that** metric 1 does not stall if the master-vs-main hypothesis is wrong.
**Acceptance Criteria** (each remedy is test-first; the characterization tests already exist in 2.1c/2.3d/0.1a, so a go decision only adds the fix):
- SAF shadow (Branch 2, Task 2.5a).
  - *Given* a SAF shadow worktree whose tracking ref resolves but whose merged files are not written back to the SAF tree, *When* a sync completes, *Then* the changed files exist under the SAF-facing path and `reloadFiles` receives user-facing (not shadow-absolute) paths.
- `wikiSubdir` (Branch 3, Task 2.5b).
  - *Given* a merge whose files live outside `wikiSubdir`, *Then* the loader still receives every path under the wiki root and the diagnostics line states the filter.
- Shallow / refspec (Branch 4, Task 2.5c).
  - *Given* a `setDepth(1)` single-branch clone whose `remote.origin.fetch` omits the configured branch, *When* `fetch` runs, *Then* the tracking ref is created (refspec widened to `+refs/heads/<branch>:refs/remotes/<remote>/<branch>`) and `hasRemoteDivergedSinceShallowClone` does not misreport.
- `hasRemoteChanges` gating (Branch 5, Task 2.5d).
  - *Given* `behind > 0` after a sync, *Then* the invariant error names whether gating, merge no-op or write-back caused it, with a regression test reproducing the cause found on the device.
##### Task 2.5a: SAF shadow remedy (S, conditional)
- Files: `androidMain/.../git/AndroidGitRepositoryShadow.kt`, `GitShadowWorktree.kt`; test `androidUnitTest/.../git/` shadow merge test.
##### Task 2.5b: `wikiSubdir` filter remedy (S, conditional)
- Files: `jvmCommonMain/.../git/GitMergeDiff.kt`; test `GitMergeDiffTest` (extends 2.3d).
##### Task 2.5c: Shallow / refspec remedy (S, conditional)
- Files: `RemoteTrackingRef.kt`, both `doFetch`; test in `RemoteBranchResolutionTest` (shallow single-branch clone).
##### Task 2.5d: `hasRemoteChanges` gating remedy (S, conditional)
- Files: both `doFetch`, `GitSyncService.kt`; test in `SyncInvariantTest`.

---


# PR-B: Journals (Epic 3)

### Epic 3: Journal visibility and indexing

#### Story 3.1: Lazy loading of older journals (ADR-006)
**As the** owner, **I want** journals older than the eager ~30 to appear when I scroll, navigate or jump, **so that** the cap never hides my history.
**Acceptance Criteria**:
- Seam, not hotspot growth.
  - *Given* the change set, *Then* `GraphLoader.kt` gains only the two **added** one-line delegating methods (`loadJournalsOlderThan`, `ensureJournalLoaded`) to `JournalLazyLoader` (Tech Debt table edits 1-2); all logic and tests live in `db/JournalLazyLoader.kt`.
- Pagination.
  - *Given* 1,578 journal files on disk and the DB holding the newest 30, *When* the journals view scrolls to its last loaded date `2026-09-10`, *Then* the next 30 older files load via `loadJournalsOlderThan(2026-09-10, 30)` using <=500-element `IN` lookups and the view shows 60 journals.
- Navigate/calendar jump.
  - *Given* `2024_03_01.md` on disk but not in DB, *When* the user navigates to `2024-03-01` or taps it in the calendar, *Then* `ensureJournalLoaded(2024-03-01)` parses it FULL and the page renders.
- Bounded reads.
  - *Given* the lazy paths running on an 8,030-page graph, *When* `LargeGraphWarmStartCrashTest`-style recording runs, *Then* no query returns more than 100 rows per batch outside the 500-element chunking and no standing unbounded collector exists.
- Eager behaviour unchanged.
  - *Given* warm start, *Then* exactly the existing 10 + 20 journals load first (existing tests pass).
**Files**: `commonMain/.../db/JournalLazyLoader.kt` (new), `commonMain/.../db/GraphLoaderPort.kt`, `commonMain/.../db/GraphLoader.kt` (two added delegating methods), `commonMain/.../db/FileRegistry.kt`, `commonMain/.../ui/screens/JournalsViewModel.kt` (existing at this path, **not** `ui/`), journal service, calendar jump handler; tests in `businessTest/.../db/LazyJournalLoadTest.kt`.

##### Task 3.1a0: Extract `JournalLazyLoader` seam (M)
- Files: `commonMain/.../db/JournalLazyLoader.kt` (constructor takes `FileRegistry`, the page lookup repository, and a parse-and-save function reference supplied by `GraphLoader`). Implements `loadJournalsOlderThan(date, count)` and `ensureJournalLoaded(date)` (file resolved with existing `resolvePageFilePath`). Test: `LazyJournalLoadTest` with in-memory repos + `FakeFileSystem`; idempotence (second call loads 0), failing-file skip.

##### Task 3.1a: Port methods and `GraphLoader` delegations (S)
- Files: `GraphLoaderPort.kt` (add the two methods with defaults so existing fakes compile; list every implementer found by `sg`), `GraphLoader.kt` (two **added** one-line delegating methods; these members do not exist on the port or the loader today), `FileRegistry.kt` if a lookup is needed. Test: delegation test plus the existing eager-load tests.

##### Task 3.1b: Journals view requests next page at end of list (S)
- Files: `ui/screens/JournalsViewModel.kt` (+ screen scroll trigger). Test: extend `commonTest/.../ui/screens/JournalsViewModelTest.kt`: end-of-list triggers exactly one in-flight request; no request while one is running.

##### Task 3.1c: Navigate-by-name and calendar jump call `ensureJournalLoaded` (S)
- Files: navigation path that currently falls to `resolvePageFilePath`, calendar handler. Test: navigation test for an unloaded date; date-shaped search query resolves. Search copy states that full-text covers loaded journals only (safe default for the open question).

#### Story 3.2: Journal disk-vs-DB diff and DB-only repair
**As the** owner, **I want** to see which journals are on disk but not in the DB (and vice versa) and repair DB-only ones, **so that** I can decide the fate of the 2026-10-07/09/10 ghosts.
**Acceptance Criteria**:
- Diff splits recent from older and is bounded.
  - *Given* disk journals `2026_10_08` and 1,500 older ones, DB journals `2026_10_07`, `2026_10_09`, `2026_10_10`, and today `2026-10-10`, *When* `JournalDiffService.diff(days=14)` runs, *Then* `diskOnlyRecent=[2026-10-08]`, `dbOnlyRecent=[2026-10-07, 2026-10-09, 2026-10-10]`, and `diskOnlyOlder` is reported as count 1500 with a sample of at most 50 dates, labelled "not loaded by design"; the service result never holds more than 50 dates per group plus counts.
- Property: diff is symmetric and partitions.
  - *Given* any generated sets of disk and DB dates, *Then* `diskOnly`, `dbOnly`, `inBoth` are disjoint and their counts sum to the inputs.
- Repair writes through `GraphWriter`.
  - *Given* a `JournalRepairPlan([2026-10-09])` whose DB page has blocks `["met alex"]` and no file, *When* confirmed and executed, *Then* `journals/2026_10_09.md` exists containing `- met alex`, watcher suppression is active (no self-reload echo), and re-running yields an empty plan.
  - *Given* a plan date whose file now exists on disk, *Then* the file is not overwritten and the date is reported "skipped: file appeared".
  - *Given* the owner has not confirmed, *Then* no file is written and nothing is committed or pushed (safe default for the ghosts).
**Files**: `commonMain/.../journal/JournalDiffService.kt` (new package `journal/`, sibling of `domain/`/`service/` by design: it depends only on ports), `commonMain/.../journal/JournalRepairService.kt` (new), tests `commonTest/.../journal/JournalDiffPropertyTest.kt`, `businessTest/.../journal/JournalRepairTest.kt`. No dependency on Epic 1.

##### Task 3.2a: `JournalDiffService` (M)
- Bounded: lists via `FileSystem`, DB lookups via `getJournalPagesByDates(chunk<=500)`; window parameter default 14 days (aligned with metric 4); returns counts plus <=50-date samples.

##### Task 3.2b: `JournalRepairService` over `GraphWriter` (M)
- Uses the existing DB->disk path with watcher suppression; no raw file writes; never overwrites; confirm is the caller's responsibility (console tier STATE_CHANGE, lists the dates).

#### Story 3.3: Indexer progress probe and failure paths
**As the** owner, **I want** to know whether background indexing finished and have it recover from failure, **so that** "9,414 pages awaiting index" is explainable and does not stick.
**Acceptance Criteria**:
- Probe lives in the supervisor.
  - *Given* a graph with 9,414 METADATA_ONLY pages mid-drain, *When* `BackgroundIndexSupervisor.indexStatus()` is read, *Then* `unloadedCount` (O(1) `countUnloadedPages`), `indexJobActive == true`, `lastCompleteAt == null`, `lastError` are returned; `GraphLoader` gains no status state.
- Reconcile failure no longer strands indexing.
  - *Given* warm reconcile throwing `IllegalStateException`, *When* startup proceeds, *Then* the failure is logged, `onFullyLoaded()` is still invoked (signature unchanged), the new defaulted `onDegraded(Throwable)` is invoked, and `indexRemainingPages` starts.
- Trim-memory restart.
  - *Given* `cancelBackgroundWork()` after `onTrimMemory`, *When* the app returns to foreground, *Then* `BackgroundIndexSupervisor` restarts the drain from `getUnloadedPages(limit=100, offset)` and `Background indexing complete.` is eventually logged; drain still terminates with permanently failing pages (existing `GraphLoaderIndexBatchingTest` passes).
**Files**: `commonMain/.../db/BackgroundIndexSupervisor.kt` (new), `commonMain/.../db/GraphLoaderPort.kt` (`onDegraded` default param, lines `:69,80,81`), `commonMain/.../db/GraphLoader.kt` (try/finally + `onDegraded`, edits 4-5), `commonMain/.../ui/StelekitViewModel.kt` (single call-site swap at `:712` area), tests `businessTest/.../db/BackgroundIndexSupervisorTest.kt`.

##### Task 3.3a: `IndexStatus` on `BackgroundIndexSupervisor` (S)
##### Task 3.3b: Reconcile failure path invokes `onFullyLoaded()` + `onDegraded` and starts indexing (M)
- **First step**: run `sg --pattern 'loadGraphProgressive($$$)' --lang kotlin kmp/src` and `grep -rln 'GraphLoaderPort' kmp/src`, record every implementer and fake in the PR description before editing (2026-10-10 grep of `GraphLoaderPort` found: `GraphLoaderPort.kt`, `GraphLoader.kt`, `SectionManagementCoordinator.kt`, `StelekitViewModelDependencies.kt`, `StelekitViewModel.kt`, and test users `StelekitViewModelLoadingTest.kt`, `ExternalFileChangeErrorHandlingTest.kt`, `ThreeStateSubscriptionTest.kt`, `DeviceProfileTest.kt`, `StelekitViewModelLlmSuggestionTest.kt`, `NewPageAutoAssignmentTest.kt`). Files above. Because `loadGraphProgressive` gains a defaulted parameter, list every implementer/fake found by `sg` (known: `GraphLoaderPort`, `GraphLoader`; test users include `jvmTest/.../ui/StelekitViewModelLoadingTest.kt`, `ExternalFileChangeErrorHandlingTest.kt`, `businessTest/.../llm/StelekitViewModelLlmSuggestionTest.kt`, `businessTest/.../sections/*Test.kt`); regression test extends the `LargeGraphWarmStartCrashTest` pattern with a throwing reconcile.
##### Task 3.3c: `BackgroundIndexSupervisor` restart on resume/trim (S)
- Test with fake lifecycle events and `runTest`.

---

# PR-C: Developer console (Epics 4-6)

### Epic 4: Console core

#### Story 4.1: Skeleton, contracts, registry
**As a** contributor, **I want** a small command contract and registry, **so that** a new probe is a one-file addition.
**Acceptance Criteria**:
- Registration.
  - *Given* a test command `echo` added to the injected list, *When* the registry resolves `echo hi`, *Then* it runs with `args=["hi"]` and `help` lists it.
- Capability filtering and Web subset.
  - *Given* a `ConsoleContext` with `git = null` (wasm), *When* `help` runs, *Then* `git`, `fs` and `sh` are listed as "disabled: ..." with a reason and `git status` returns `ConsoleError.Unsupported`; the Web console exposes `help`, `settings`, `logs`, `export`, and `sql` only if a read-only connection exists (UX G13).
- Typo help.
  - *Given* input `gti status`, *Then* the error is "unknown command 'gti'. Did you mean 'git'?" with up to 3 suggestions.
- Contract test.
  - *Given* the injected command list, *Then* a test fails if any command omits `risk`, and maps each `Risk` to a `ConfirmTier`.
**Files**: `commonMain/.../console/ConsoleCommand.kt`, `ConsoleRegistry.kt`, `ConsoleError.kt`, `commonTest/.../console/ConsoleRegistryTest.kt`, `ConsoleCommandContractTest.kt`.

##### Task 4.1a: Contracts and registry (S)
##### Task 4.1b: Bazel target placement and wasm compile check (S)
- Files: `kmp/BUILD.bazel` (and sub-package BUILD files per `project_plans/commonmain-bazel-target-split`), `kmp/src/commonMain/.../console/`. Acceptance: `timeout 30m bazel build //kmp:jvm_main`-equivalent target and `./gradlew :kmp:compileKotlinWasmJs -PenableJs=true` compile with the console package; **`bazel test //kmp:business_tests` class count increases by exactly the number of new businessTest classes** (recount from the real run; a silently excluded test cannot pass).
##### Task 4.1c: `java.*` and write-gate source audits (S)
- Files: `businessTest/.../console/ConsoleSourceAuditTest.kt` (modelled on `CaptureWriterSourceAuditTest`): asserts no `java.*`/`javax.*` imports under `commonMain/.../console/` (replaces the one-time grep) and no `GraphManager`/`RepositorySet`/`GraphLoader` imports under `console/commands/**`.

#### Story 4.2: Command-line tokenizer
**Acceptance Criteria**:
- Quotes and escapes.
  - *Given* `sql "select name from pages where name = 'a b'"`, *When* tokenized, *Then* tokens are `["sql", "select name from pages where name = 'a b'"]`.
- Errors.
  - *Given* an unterminated quote `git log "abc`, *Then* `ConsoleError.Usage("unterminated quote")` and no crash.
- Property: for any generated token list, `tokenize(render(tokens)) == tokens`.
**Files**: `commonMain/.../console/CommandLineTokenizer.kt`, `commonTest/.../console/CommandLineTokenizerTest.kt`.

##### Task 4.2a: Tokenizer + property tests (S)

#### Story 4.3: Best-effort redaction at the single sink
**As the** owner, **I want** known credential shapes and the exact vault secrets scrubbed from output, history, export and logs, **so that** sharing a transcript is safe in the normal case.
**Limits (stated, not hidden)**: the redactor is **best effort plus exact-secret matching**. Transformed output (`substr()`, `hex()`, base64, line wrapping, `sh`) can evade it; mitigation is structural: credential tables are not readable through `sql`, `fs` denies secret paths, `settings` uses an allowlist, `sh` is desktop-only behind its own confirm.
**Acceptance Criteria**:
- Patterns.
  - *Given* output `origin https://u:ghp_x1y2z3@github.com/o/r.git`, *When* emitted through `ConsoleOutput`, *Then* the scrollback shows `https://[redacted]@github.com/o/r.git` and a "redacted" marker is present.
  - *Given* `Authorization: Bearer abc.def`, `github_pat_...`, `glpat-...`, `AKIA...`, and a PEM block, *Then* each is replaced.
- Exact vault secrets.
  - *Given* the credential store holds token `s3cr3t-token-value`, *When* any line contains it (even without a known prefix), *Then* it is replaced.
- Property: for generated secret-shaped tokens embedded in arbitrary text, none survive the sink. The test documents (as an expected-limit case) that a hex-encoded secret is not caught.
- Audit logs and history also pass the redactor.
  - *Given* the command `git ls-remote https://u:ghp_abc123@h/r.git` is run, *Then* the INFO audit line and the history entry both contain `[redacted]` and not `ghp_abc123`.
**Files**: `commonMain/.../console/ConsoleRedactor.kt`, `ConsoleOutput.kt`, `commonTest/.../console/ConsoleRedactorPropertyTest.kt`, `jvmTest/.../console/ConsolePatLeakageAuditTest.kt` (modelled on `PatLeakageAuditTest`).

##### Task 4.3a: Redactor + sink wiring + property and leak-audit tests (M)

#### Story 4.4: Scrollback buffer and session
**As the** owner, **I want** streaming, cancellable, bounded output, **so that** a big query or `git log` cannot freeze or crash the app.
**Acceptance Criteria**:
- Ring buffer.
  - *Given* a `ScrollbackBuffer` capped at 5,000 lines, *When* the 5,001st line is appended, *Then* line 1 is evicted and a visible marker `[... 1 earlier lines dropped ...]` leads the snapshot; a single 5,000-char line is truncated to 2,000 chars with "…(+3000 chars)".
- Batched publication.
  - *Given* a command emitting 500 lines in 10 ms, *When* the UI collects `StateFlow<ScrollbackSnapshot>`, *Then* at most ~3 snapshots are published in that window (60 ms batching).
- Cancellation and survival.
  - *Given* a running command, *When* `cancel()` is called, *Then* the block ends `CANCELLED` and partial output is kept; *Given* a command that throws `OutOfMemoryError`, *Then* the session emits an ERR block with a preformatted message and stays alive (`CoroutineExceptionHandler` + `catch Throwable`; `CancellationException` rethrown).
- Scope ownership.
  - *Given* a `ConsoleSession` created inside `remember {}`, *Then* it uses its own `CoroutineScope(SupervisorJob()+...)` and a grep audit finds no `rememberCoroutineScope` value passed in.
- History in memory only: *Given* the app restarts, *Then* history is empty.
- Bounded end to end (pre-mortem #9, P3).
  - *Given* a command streaming 100,000 rows or lines (including CSV export and cell materialization) under a small test heap, *Then* command-side buffering is bounded at every stage (row cap, 2 KB cell cap, spill-to-file for large output) and the process does not hit `OutOfMemoryError`; *Given* an injected `OutOfMemoryError` in the console scope, *Then* the `CoroutineExceptionHandler` turns it into an ERR block and the session survives.
  - *Given* an export while a command is running, *Then* the partial output is included and the running block is marked `RUNNING (partial)` (ux S10).
- Pending confirm and shell state live in the session (resolves G6, G10).
  - *Given* a pending inline confirm and a configuration change (rotation; see Story 4.6 for the retention mechanism), *Then* the confirm is still pending; *Given* a second command is submitted, *Then* the pending confirm is cancelled with "Cancelled: superseded" (UX-35, UX-50).
  - *Given* the shell is armed, *When* the console screen is left, developer mode is turned off, or the app closes, *Then* the session is unarmed.
**Files**: `commonMain/.../console/ScrollbackBuffer.kt`, `ConsoleSession.kt`, `commonTest/.../console/ScrollbackBufferTest.kt`, `ConsoleSessionTest.kt`, `jvmTest/.../console/ConsoleBoundedStreamingTest.kt`.

##### Task 4.4a: `ScrollbackBuffer` + `ConsoleOutput` implementation (M)
##### Task 4.4b: `ConsoleSession` (run/cancel/history/pending confirm/shell-arm lifetime/audit/CEH) (M)

#### Story 4.5: Developer-mode gate
**Acceptance Criteria**:
- Default off hides everything.
  - *Given* a fresh install (`developer_mode_enabled` absent), *When* Settings opens, *Then* the Developer category shows only the toggle with warning "Lets you run SQL writes and shell commands. Your notes can be changed or lost." and no console row exists in Settings or the Logs overflow.
- Read failure counts as off (ux S6).
  - *Given* `Settings.getBoolean("developer_mode_enabled")` throws, *Then* the flag is treated as off and nothing is shown.
- Dispatcher enforcement.
  - *Given* the flag off and a restored `Screen.Console` route, *When* `ConsoleSession.run("diag")` is called, *Then* it returns `ConsoleError.Denied("developer mode off")` and nothing executes.
- Turning the flag off while the console is open (UX-22, resolves G9).
  - *Given* the console is open with a running command and an armed shell, *When* developer mode is turned off, *Then* the screen closes, the command is cancelled, the shell is disarmed and scrollback is cleared.
**Files**: `commonMain/.../console/DeveloperMode.kt`, `commonMain/.../ui/components/settings/SettingsDialog.kt` (visibility predicate), `commonMain/.../platform/Settings.kt` key constant (no interface change), tests `commonTest/.../console/DeveloperModeGateTest.kt`, `androidUnitTest/.../SettingsDeveloperCategoryTest.kt`.

##### Task 4.5a: Setting + dispatcher gate (S)
##### Task 4.5b: Settings > Developer visibility, toggle UI, and close-on-disable behavior (S)

#### Story 4.6: Composition root and views
**Acceptance Criteria**:
- Commands never see `GraphManager`.
  - *Given* the source audit of Task 4.1c, *Then* no import of `GraphManager`, `RepositorySet` or `GraphLoader` implementation classes exists under `console/commands/**`.
- Session retention is settled (resolves the rotation question; VERIFIED by reading the manifest).
  - *Given* `androidApp/src/main/AndroidManifest.xml:44`, which declares `android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode|navigation"` on `MainActivity` (the `kmp/src/androidMain` manifest declares no activity, which is why the earlier check there found nothing), *Then* rotation, resize and dark-mode changes do not recreate the Activity and a `remember {}`-hosted `ConsoleSession` survives them; the earlier suspicion that `remember {}` would drop state on rotation does not apply. No `ViewModel`, `rememberSaveable` or retained-state mechanism exists in `androidMain` (grep found none), and none is added.
  - *Given* process death or the Activity being destroyed for any other reason, *Then* the session (history, scrollback, running command, pending confirm, armed shell) is lost by design (UX-34).
  - *Given* a test that applies orientation/size qualifiers without recreating the Activity (`RuntimeEnvironment.setQualifiers`), *Then* input, scrollback, running command and pending confirm are retained (UX-35); a manifest test asserts the `configChanges` set still contains `orientation|screenSize|uiMode` so removing it fails CI.
- Console wiring is not in the camera file.
  - *Given* `GraphContentCameraCapture.kt`, *Then* its diff versus Epic 1 is empty; the session is created by `rememberConsoleSession(...)` in `ui/ConsoleComposition.kt` and hosted by `ui/GraphContentConsole.kt`, modelled on the `GraphDiagnosticsCollector` `remember{}` site (reusing that collector for `diag`); no scope is passed in.
- Per-run resolution.
  - *Given* a graph switch between two runs, *When* `graphs.current()` is called each time, *Then* the second run sees the new graph; given the graph closes mid-command, *Then* the block ends "graph closed during command" with no crash.
- Git config per run: *Given* `git set-branch master` then `git doctor`, *Then* doctor reads the new value.
**Files**: `commonMain/.../ui/ConsoleComposition.kt` (new), `commonMain/.../ui/GraphContentConsole.kt` (new, `GraphContent*` family), `commonMain/.../console/views/ActiveGraphView.kt`, `GitConsoleView.kt`, `SqlConsoleView.kt`, `DbRestoreView.kt`, tests `businessTest/.../console/ConsoleCompositionTest.kt`, `androidUnitTest/.../console/ConsoleRetentionTest.kt` (config-change retention + manifest `configChanges` assertion against `androidApp/src/main/AndroidManifest.xml`).

##### Task 4.6a: Views + composition root + host file (M)
- Hosting decision: `rememberConsoleSession(...)` (a `remember {}` site) is sufficient because of the manifest `configChanges` above; the session owns its scope and is disposed when developer mode turns off or the console route is left.

### Epic 5: Console commands

#### Story 5.1: `diag`
**Acceptance Criteria**:
- Reuses probes.
  - *Given* `diag git`, *When* run on a cloned graph with `remoteBranch=main`, *Then* output equals the Git section of `GraphDiagnosticsCollector` and shows `resolve(origin/main)` unresolved plus ls-remote heads `master`.
- `diag` with no args equals the Logs-screen diagnostics report text (modulo timestamp).
**Files**: `commonMain/.../diagnostics/GraphDiagnostics.kt` (split `append*` into independently callable probes), `commonMain/.../console/commands/DiagCommand.kt`, tests.
##### Task 5.1a: Probe split + `DiagCommand` (M)

#### Story 5.2: `git` family
**Acceptance Criteria**:
- Read commands.
  - *Given* a clone, *When* `git status|log -n 10|refs|remote|ls-remote` run, *Then* each returns bounded output (log capped at `maxCount`, ls-remote with a 15 s timeout; fetch is bounded by `GIT_TRANSPORT_TIMEOUT_SECONDS`) through `GitRepository` (shadow-aware), URL userinfo stripped.
- `git doctor`.
  - *Given* configured `main`, origin only `master`, *When* `git doctor` runs, *Then* it prints configured ref, resolved yes/no, remote heads, symref HEAD, ahead/behind, shallow flag, path-mode (app-owned / direct / SAF shadow, from `isAppOwnedPath`), detached flag, repository state, first-sync confirmation state, `SyncStaleness` (last merged, behind count, last-10 background outcomes), the build stamp, and the suggestion "git set-branch master".
- Mutating commands are serialized with sync.
  - *Given* a scheduled sync holding the per-graph git write lock, *When* `git fetch` runs, *Then* it refuses with "sync in progress" (never waits, never concurrent), and increments/decrements `GitSyncBusyCounter` on success and failure.
- `git set-branch`.
  - *Given* `git set-branch master` and confirm, *Then* the shared `BranchRepairService` (same code as the dialog, Task 2.2d) runs `saveConfig` with read-back, output shows `remote_branch: main -> master`. `BranchRepairService` is created by the ungated Task 2.2a2, so the console twin is never blocked by the Task 2.2d gate. The raw "JGit-backed subcommands" item in the original requirements is cut (requirements updated): the fixed subcommand list is the contract.
**Files**: `commonMain/.../console/commands/GitCommand.kt`, `GitDoctor.kt`, `commonMain/.../console/views/GitConsoleView.kt`, adapters in `ConsoleComposition.kt`, tests `businessTest/.../console/GitCommandTest.kt`, `jvmTest/.../console/GitDoctorTest.kt`.
##### Task 5.2a: Read-only subcommands (status/log/refs/remote/ls-remote) (M)
##### Task 5.2b: Mutating subcommands (fetch/merge/set-branch) with lock + busy counter (M)
##### Task 5.2c: `git doctor` incl. shared path-mode predicate (S)
- Uses `isAppOwnedPath(repoRoot)` from Task 2.4g (same `shadowWorktreeFor`/`resolveForJGit` source); prints path mode, the predicate verdict, staleness history, first-sync state and the build stamp.

#### Story 5.3: `fs` family
**Acceptance Criteria**:
- Capabilities exist first (Task 5.3a0).
  - *Given* `FileSystem` gains `readPrefixBytes(path, maxBytes): ByteArray?` and `canonicalPathOrNull(path): String?` (defaulted: `null` = unsupported), *Then* JVM and Android actuals implement them, and a platform returning `null` from `canonicalPathOrNull` makes `fs` deny every path that is not lexically inside an allowed root **and** not provably symlink-free (wasm/iOS: `fs` listed disabled).
- Roots enforced.
  - *Given* `fs cat ../../etc/passwd` or a symlink escaping the graph root (JVM temp dir test), *Then* `ConsoleError.Denied("outside allowed roots")`.
- Deny-list.
  - *Given* `fs cat <appdir>/shared_prefs/...`, `.ssh/`, `*.keystore`, vault files, *Then* denied.
- Bounded reads.
  - *Given* a 50 MB file, *When* `fs cat`, *Then* at most 64 KiB is read through `readPrefixBytes` (never `readFile`) with "truncated" marker and `fs head -n 20` reads a bounded prefix; a size check via `getFileSize` refuses over the cap where `readPrefixBytes` is unavailable.
- SAF graph: *Given* a `content://` graph root, *When* `fs ls`, *Then* it works through `FileSystem` (not `java.io.File`).
**Files**: `commonMain/.../platform/FileSystem.kt` (+ JVM and Android actuals and every test fake that implements the interface, listed by `sg` at implementation time), `commonMain/.../console/commands/FsCommand.kt`, `commonMain/.../console/PathPolicy.kt`, tests `commonTest/.../console/PathPolicyTest.kt` (property: no `..` sequence escapes), `jvmTest/.../console/FsSymlinkTest.kt`.
##### Task 5.3a0: Extend `FileSystem` with `readPrefixBytes` and `canonicalPathOrNull` (M)
##### Task 5.3a: `PathPolicy` + `fs ls/stat/cat/head/find` (M)

#### Story 5.4: `sql` read (fail closed)
**Acceptance Criteria**:
- Single statement.
  - *Given* `select 1; drop table blocks`, *Then* `Invalid("multiple statements")` and nothing runs.
- Read-only by engine, on a console-owned connection (ADR-002 A1).
  - *Given* `with x as (select 1) delete from pages`, *When* run, *Then* the engine rejects it on the console-owned read-only connection and the pooled app connections are unaffected afterwards (JVM real-driver test).
  - *Given* a platform where the read-only connection cannot be opened (or spike 0.2a failed), *Then* `sql` reads return `Unsupported("no read-only connection on this platform")`; nothing falls back to classifier-only execution (test with a connection factory that throws).
- Credential tables are not readable.
  - *Given* `select * from git_config` (credential-bearing columns), *Then* `Denied("credential table")`.
- Caps.
  - *Given* `select * from blocks` on 100k rows, *Then* 500 rows shown, footer "500 rows shown, more available -- truncated at row cap", each cell <= 2 KB, and cancel interrupts within the driver capability (timeout default 30 s).
- Discovery chips: `tables`, `schema pages` work without typing SQL.
**Files**: `commonMain/.../console/sql/SqlStatementSplitter.kt`, `commonMain/.../console/commands/SqlCommand.kt`, `commonMain/.../db/SqlConsoleReader.kt` (+ `RepositorySet.sqlConsole` nullable field), per-platform read-connection factories (`jvmMain/.../db/`, `androidMain/.../db/`), `jvmTest/.../console/SqlReadCommandTest.kt`, `commonTest/.../console/SqlStatementSplitterPropertyTest.kt`.
##### Task 5.4a: Statement splitter/classifier (router only; property tests: no `;` inside quotes/comments splits) (M)
##### Task 5.4b: `SqlConsoleReader` seam + `sql` read command, caps, timeout (M)
##### Task 5.4c: Console-owned read-only connection per platform, fail closed (M)
- Implements the per-platform result of spike 0.2a (JVM dedicated read-only connection; Android `createReadDriver`/`OPEN_READONLY`; iOS/wasm disabled unless the spike shows otherwise); credential-table allowlist; failure-closed test.

#### Story 5.5: `sql` write, backup, restore
**Acceptance Criteria**:
- Actor path.
  - *Given* `sql! update pages set is_favorite=1 where name='Inbox'` confirmed, *When* executed, *Then* it runs inside `DatabaseWriteActor.execute`, result "1 row affected -- DB-only: not written to markdown" (row count obtained through the captured holder set inside the lambda, since `execute` returns `Either<DomainError, Unit>`), and open UI observers refresh (invalidation after op).
- Opt-in discipline.
  - *Given* the enforcement test `DirectSqlWriteOptInAllowlistTest` (new, businessTest source audit), *Then* every `@OptIn(DirectSqlWrite::class)` site is in its allowlist, `SqlConsoleWriter` appears only as a function-level opt-in on its one private raw-exec function (no class-level opt-in), and adding a new site fails the test. (There is no existing enforcement test; this story creates it.)
- Writes need a read connection (fail closed).
  - *Given* a platform or run where no console read-only connection exists (spike 0.2a failed, or the connection factory throws), *When* any `sql!` write is submitted, *Then* it is refused with "Nothing was changed: writes need a read connection for the dry-run count" (UX-55); no backup is taken and the actor is not called.
- Deny list.
  - *Given* `drop table blocks`, `attach database`, `pragma writable_schema=1`, `insert into blocks_fts ...`, `vacuum`, any write to a credential table, *Then* each is `Denied(reason)` even after confirm.
- Backups (ADR-002 A3).
  - *Given* the first write of a write session, *Then* `console-backups/<graph>-<ts>.db` exists and is a valid SQLite file (`PRAGMA integrity_check` ok) before the statement runs.
  - *Given* a second write in the same session of a **new destructive class** (e.g. `update` then `delete`), or any write without `WHERE`, *Then* an additional snapshot is taken before it.
  - *Given* 6 snapshots over time, *Then* the oldest is evicted (keep 5); the backup path is absent from the `diag` export.
- No-WHERE.
  - *Given* `delete from pages`, *Then* the UI requires typing the table name; wrong text blocks.
- Cascade policy (ADR-002 A4).
  - *Given* a raw console write to `blocks`, *When* the app is otherwise idle, *Then* no `GraphWriter` write, file change or commit occurs; the touched tables appear in `ConsoleDbDirty`; and `graph reload` re-imports disk over the console change (after the `DbOnlyRowsGuard`, Task 5.6d).
  - *Given* a raw write to `blocks` or `pages`, *Then* the trigger-maintained FTS tables (`blocks_fts`, `pages_fts`) are exercised by the cascade test and the post-write search results are checked, not only the written table (pre-mortem #4).
- Durable backups list and restore (UX-85).
  - *Given* three retained snapshots, *When* `backups` runs, *Then* it lists them newest first with time, size and what preceded them, marks one that fails `integrity_check` as unreadable (listed, `Restore` disabled with the reason), and prints `Restore with: backups restore <n>`; *Given* an empty folder, *Then* it says a backup is taken before the first write; *Given* Settings > Developer > `Console backups` with developer mode on, *Then* the same list is shown after an app restart and a new session, each row with `Restore…` (the S8d confirm and the `DbOnlyRowsGuard`) and `Copy path`.
- Safety drill (requirements Metric 5a).
  - *Given* a seeded sequence of writes covering `update`, `delete` and a no-WHERE statement, *Then* a valid snapshot exists before each statement class's first write, and restoring the earliest snapshot returns row counts and a table checksum equal to the pre-write state.
- Failure surfaces as `DomainError.DatabaseError.WriteFailed`, never a raw exception.
**Files**: `commonMain/.../console/commands/BackupsCommand.kt`, `commonMain/.../db/SqlConsoleWriter.kt`, `commonMain/.../db/RestrictedDatabaseQueries.kt` (one `@DirectSqlWrite` raw-exec stub), `commonMain/.../db/DbBackup.kt`, `commonMain/.../db/DbRestore.kt`, `commonMain/.../console/commands/SqlWriteCommand.kt`, `androidMain/AndroidManifest.xml` + `androidMain/res/xml/` rules, `businessTest/.../db/DirectSqlWriteOptInAllowlistTest.kt`, `jvmTest/.../console/SqlWriteCommandTest.kt`, `jvmTest/.../console/RawWriteCascadeTest.kt`, `jvmTest/.../console/DbRestoreTest.kt`, `businessTest/.../console/BackupsCommandTest.kt`, `jvmTest/.../console/ConsoleWriteSafetyDrillTest.kt`, `androidUnitTest/.../ui/screens/ConsoleBackupsScreenTest.kt`.
##### Task 5.5a: Writer (function-level opt-in) + stub + deny list + actor routing + allowlist enforcement test (M)
##### Task 5.5b: `DbBackup` with runtime `VACUUM INTO` detection, per-session and per-class snapshots, retention 5 (M)
##### Task 5.5c: Dry-run row count and confirm payload via the read connection (S)
##### Task 5.5d: Cascade regression test (including FTS triggers) and `ConsoleDbDirty` tracking (S)
##### Task 5.5e: `graph restore-backup` (Restore backup story) (M)
- Depends on the swap result of Task 0.2b and on Task 5.6d (`DbOnlyRowsGuard`). Mechanism per ADR-002 A3: run `DbOnlyRowsGuard` (DB-only journals exported to `console-recovery/` first); validate snapshot on a temp copy; **close the console read connection**, drain actor; `DbRestoreView` closes/re-adds the graph through public `GraphManager.removeGraph`/`addGraph`; live DB (+ `-wal`/`-shm`) renamed to `*.pre-restore`, stale `-wal`/`-shm` deleted, snapshot copied in, `integrity_check` + row-count comparison before reopening, then `graph reload` (disk wins). The checkpoint+copy fallback runs only on a quiesced actor; `*.pre-restore` is kept until the next clean launch. Failure rolls the rename back and prints the backup path with Copy details. Tests: success, snapshot fails `integrity_check` (nothing replaced), copy failure mid-swap (original restored).
##### Task 5.5f: Exclude `console-backups/` from Android Auto Backup (S)
- The manifest sets no backup attributes (verified: no `allowBackup`/`dataExtractionRules`/`fullBackupContent` in `androidMain/AndroidManifest.xml`), so the default includes app files. Add `android:dataExtractionRules` and `android:fullBackupContent` XML under `androidMain/res/xml/` excluding `console-backups/`, `console-recovery/`, `console-exports/` and `git-abort-recovery/`; document that snapshots are unencrypted copies in the app directory. Test: Robolectric/manifest lint assertion that the rules file excludes the path.

##### Task 5.5g: `backups` command and Settings > Developer > Console backups (S)
- Files: `commonMain/.../console/commands/BackupsCommand.kt` (READ `backups`; `backups restore <n>` delegates to the Task 5.5e restore path, STATE_CHANGE confirm), `commonMain/.../ui/screens/ConsoleBackupsScreen.kt` (list rows, `Restore…`, `Copy path`, empty and unreadable states), `ui/components/settings/SettingsDialog.kt` (row visible only with developer mode on, beside `Dev console`), `DbBackup` listing API (name, time, size, preceding statement class, integrity flag). Depends on Task 5.5b and 5.5e. Test: `businessTest/.../console/BackupsCommandTest.kt` (list order, unreadable marked not hidden, empty state, restore delegates and runs the `DbOnlyRowsGuard`), `androidUnitTest/.../ui/screens/ConsoleBackupsScreenTest.kt` (UX-85; keyboard operable, 48 dp rows), `jvmTest/.../console/ConsoleWriteSafetyDrillTest.kt` (the Metric 5a drill above). No schema change.

#### Story 5.6: `settings`, `logs`, `graph`
**Acceptance Criteria**:
- `settings` uses an allowlist (no enumeration; `Settings` has only `getBoolean/putBoolean/getString/putString/containsKey`).
  - *Given* `settings list`, *Then* it prints the console-owned allowlist of known keys with `containsKey` presence and values only for non-secret keys; secret-bearing keys are never printed.
  - *Given* `settings get developer_mode_enabled`, *Then* `true`; `settings set` is STATE_CHANGE with inline confirm echoing the change; a key outside the allowlist is `Denied`. The allowlist includes the three mass-change threshold keys (`git_mass_change_min_files`, `git_mass_change_pct`, `git_mass_change_max_journals`, Task 2.3f) so thresholds can be tuned from the console.
- `logs [-n 200] [--level warn] [--grep text]` reads `LogManager.logs` bounded.
- `graph registry|active|index-status|reindex|reload <path|date>|journals-diff|journals-repair|restore-backup`.
  - *Given* `graph journals-diff`, *Then* output matches the Story 3.2 example, with registry entries labelled (the two SAF `personal-wiki` graphs marked "SAF shadow") and each list capped at 50 with "+N more"; with nothing missing it prints `Recent (last 14 days): all 14 journals are in the database.` and the `Older: not loaded by design (N on disk).` line, never an empty group header (UX-98).
  - *Given* `graph reindex`, *Then* it runs `indexRemainingPages` under the console's cancellable job; cancel stops it.
  - *Given* `graph journals-repair` and confirm, *Then* only `JournalRepairService` runs (no raw SQL, no raw fs).
- DB-only rows are never silently erased (pre-mortem #4; default for the 2026-10-07/09/10 journals).
  - *Given* DB-only journal pages (or a non-empty `ConsoleDbDirty`), *When* `graph reload`, `graph reindex` or `graph restore-backup` is run, *Then* `DbOnlyRowsGuard` first lists them ("3 journals exist only in this database: 2026-10-07, 2026-10-09, 2026-10-10; reload will erase them"), exports their block text to `console-recovery/db-only-journals-<ts>.md` (app-private, excluded from Auto Backup, not pushed, no `GraphWriter`), and only then asks for a typed confirm ("erase 3 DB-only journals"); if the export fails the command refuses to proceed.
  - *Given* no DB-only rows, *Then* the guard is silent and adds no confirm.
**Files**: `commonMain/.../console/commands/SettingsCommand.kt`, `LogsCommand.kt`, `GraphCommand.kt`, `commonMain/.../console/SettingsAllowlist.kt`, `commonMain/.../console/DbOnlyRowsGuard.kt`, tests `businessTest/.../console/GraphCommandTest.kt`, `DbOnlyRowsGuardTest.kt`.
##### Task 5.6a: `settings` (allowlist) and `logs` commands (S)
##### Task 5.6b: `graph` commands incl. journals-diff/repair and index-status (M)
##### Task 5.6c: Registry labelling of SAF vs app-owned graphs (S)
##### Task 5.6d: `DbOnlyRowsGuard` for `graph reload`, `reindex` and `restore-backup` (M)
- Files: `commonMain/.../console/DbOnlyRowsGuard.kt`, uses `JournalDiffService` (Story 3.2) and `ConsoleDbDirty`; recovery file via the `FileSystem` port. Test: `businessTest/.../console/DbOnlyRowsGuardTest.kt` (export file content, export failure refuses, typed confirm, silent when clean, each of the three commands invokes it).

#### Story 5.7: `sh` (desktop-only)
**Acceptance Criteria**:
- Platform decision.
  - *Given* Android, iOS or wasm, *When* `sh echo hi` runs, *Then* `ConsoleError.Unsupported("shell bypasses the file deny-list; desktop only")` and `help` lists it disabled with the same reason.
- Gated (desktop).
  - *Given* a session where `sh` was never confirmed, *When* `sh echo hi` runs, *Then* a second confirm appears once (stating the deny-list bypass and best-effort redaction); subsequent runs show the "shell armed" chip.
- Help text (ux S9).
  - *Given* `sh --help`, *Then* it states the deny-list bypass, best-effort redaction and the platform limits.
- Safe execution.
  - *Given* a command emitting 1 MB to stderr, *Then* no deadlock; on cancel the process tree is destroyed (`ProcessHandle.descendants()`); a default timeout applies; environment excludes `GITHUB_TOKEN`, `SSH_AUTH_SOCK`, 1Password variables.
**Files**: `commonMain/.../console/ProcessRunner.kt`, `jvmMain/.../console/JvmProcessRunner.kt`, `androidMain/...`, `wasmJsMain/.../console/UnsupportedProcessRunner.kt`, `iosMain/...` (Unsupported stubs), `commonMain/.../console/commands/ShCommand.kt`, `jvmTest/.../console/ProcessRunnerTest.kt`.
##### Task 5.7a: `ProcessRunner` actuals (JVM real, others Unsupported) + `sh` command (M)

#### Story 5.8: Meta commands
**Acceptance Criteria**:
- `help`, `help <cmd>`, `history`, `clear`, `export` exist; `help` is generated from the registry.
  - *Given* `export`, *Then* the redacted transcript is written as `console-<yyyyMMdd-HHmmss>.txt` via `rememberShareProvider().saveToFile(...)`/`shareText` with Share and Save to Downloads (UX-62).
  - *Given* export is tapped, *Then* a preview shows the transcript with matched redactions highlighted and a "may contain note content" warning before Share/Save (pre-mortem #7).
  - *Given* an empty transcript, *Then* Export is disabled with "Nothing to export yet" (UX-63); *Given* an export failure, *Then* the reason, Retry and a copy-to-clipboard fallback appear (UX-64) (resolves G12).
- Output spill: *Given* a result larger than the scrollback cap, *Then* it spills to a file and prints "saved to ..., tap to share" (UX-65).
- Spill and export file hygiene (UX-90).
  - *Given* a spill or CSV export containing a seeded PAT, URL userinfo and exact vault secret, *Then* the file on disk contains none of them (redaction happens before the write, per cell for CSV; no unredacted temp copy), it lives under app-private `console-exports/`, never under a graph folder (the next sync stages nothing from it), and carries the `May contain note content.` warning in the export preview.
  - *Given* the session ends (developer mode off or the next app launch), *Then* files in `console-exports/` are deleted and the folder never exceeds 20 MB (oldest evicted first); files the owner saved to Downloads are untouched; *Given* a block that points at a removed file, *Then* it says `File removed when the session ended` with no Share button.
**Files**: `commonMain/.../console/commands/MetaCommands.kt`, `commonMain/.../console/ExportStore.kt` (the `console-exports/` lifecycle), tests (`businessTest/.../console/ConsoleExportTest.kt`).
##### Task 5.8a: Meta commands + spill + export states (S)

### Epic 6: Console UI

#### Story 6.1: Screen and blocks
**Acceptance Criteria**:
- Route and layout.
  - *Given* developer mode on, *When* the user opens Settings > Developer > "Dev console", *Then* a full `Screen.Console` opens (survives rotation; not a dialog) with a `LazyColumn` of blocks (stable monotonic keys), each: header `$ cmd`, duration, chip from the closed vocabulary OK/ERR/CANCELLED/TRUNCATED/AWAITING CONFIRM/RUNNING/TIMEOUT (UX-27), body, footer; block actions Copy/Share/Collapse (plus Select text / Copy selection, Story 6.6). *Given* a first open with no blocks, *Then* the output area shows the hint `Type a command, or tap a chip below. Nothing here is saved after you close the app.` with the seeded chips, and an empty History sheet reads `No commands yet this session. History isn't saved after you close the app.` (UX-96).
- `Screen.Console` follows its siblings.
  - *Given* `AppState.kt:46-52` (siblings carry `@HelpExempt(reason = ...)`), *Then* `Screen.Console` carries `@HelpExempt(reason = "Developer tooling; reachable only with developer mode on")`, and every exhaustive `when (screen)` flagged by the compiler (known: `ScreenRouter.kt`) is updated and listed in the PR.
- Auto-follow pauses.
  - *Given* output streaming, *When* the user scrolls up, *Then* auto-follow pauses and a "jump to bottom" control appears.
- Errors.
  - *Given* a SQL error, *Then* the SQLite message is shown verbatim and the input text is kept.
**Files**: `commonMain/.../ui/AppState.kt` (`Screen.Console`), `commonMain/.../ui/ScreenRouter.kt`, `commonMain/.../console/ui/ConsoleScreen.kt`, `ConsoleBlock.kt`, tests `androidUnitTest/.../console/ConsoleScreenTest.kt`.
##### Task 6.1a: Route (`@HelpExempt`) + `ConsoleScreen` skeleton wired to `ConsoleSession` flows (M)
##### Task 6.1b: Block rendering, chips, per-block actions, wrap toggle, level colors with text prefix (M)

#### Story 6.2: Phone input ergonomics
**Acceptance Criteria**:
- Extra-keys row above the IME.
  - *Given* the soft keyboard open, *Then* the keys `Tab ^C Hist-up Hist-down / - _ ' " * ; | Paste` are offered, each >= 48 dp with descriptive labels; `^C` cancels the running command.
  - *Given* a 360 dp-wide screen (UX-82), *Then* Row A (`Tab ^C Up Dn Paste`, 240 dp) shows without scrolling and Row B (page 1 `/ - _ ' " * ; |`, page 2 `! % = ( ) , < >`, 384 dp plus a pinned 48 dp `More symbols` page key) is a horizontally scrollable strip with a visible trailing fade below 440 dp, so `! % = ( ) ,` are reachable without the IME symbol layer (UX-99); no key is under 48 x 48 dp or wraps; label glyphs are capped at 1.3x font scale and at >= 1.5x the text keys become icons with identical descriptions.
- Soft-keyboard vertical space (UX-83).
  - *Given* the keyboard open in portrait, landscape or split screen, *Then* the output area keeps at least `max(96 dp, 4 lines)`; the completion row hides first (Tab accepts the first completion shown as ghost text), then the snippet row folds into the `Snippets` menu, then Row B folds behind a `Sym` toggle; the input line and Row A never hide; in landscape the input sets `IME_FLAG_NO_EXTRACT_UI | IME_FLAG_NO_FULLSCREEN` through the implementer's chosen hook (UNVERIFIED which Compose API exposes it; `androidMain` `EditorInfo.imeOptions` wrapper is the fallback); the manifest `windowSoftInputMode`/`imePadding` interplay is checked in this task.
- Write statement under plain `sql` (UX-100).
  - *Given* the input `update pages set is_favorite = 1` under `sql`, *Then* a non-blocking hint (not a live region while typing) offers a `Switch to sql!` chip that rewrites only the prefix; Run under plain `sql` yields ERR `Denied: plain sql is read-only. Use sql! for writes.`; a deny-listed statement (`drop table pages`) shows `This statement isn't allowed in the console.` with no switch chip. Pure classifier `SqlWriteHint` in `commonMain` (first keyword after whitespace and comments; first statement only) with a table test.
- Input IME flags (UX-84).
  - *Given* the console input, SQL input and typed-confirm name fields, *Then* autocorrect, auto-capitalization and suggestions are off with a Run IME action (multi-line input keeps newline); *Given* a typed-count confirm field, *Then* it uses a numeric keypad with a Done action; asserted per field through `KeyboardOptions` in a Robolectric test.
- Chips (snippets) insert text and place the cursor.
  - *Given* tapping chip `git ls-remote`, *Then* the input contains `git ls-remote`; seeded chips: `git status`, `git refs`, `git doctor`, `git log -n 10`, `diag journals-diff`, `tables`. On Desktop the chips also appear as a collapsed "Snippets" menu (resolves G11; long-press edit/pin deferred).
- History.
  - *Given* 3 prior commands, *When* Up is pressed (hardware) or Hist-up tapped, *Then* the previous command fills the input; history sheet supports tap-to-insert and hold-to-run.
- Autocomplete as a chip row (first token from registry, second from subcommands, table/column names for `sql`), never a popup covering output.
- Multi-line paste does not auto-run; `sql` requires explicit Run.
**Files**: `commonMain/.../console/ui/ConsoleInput.kt`, `ExtraKeysRow.kt`, `SnippetChips.kt`, `HistorySheet.kt`, `commonMain/.../console/Completion.kt`, tests (`commonTest` for completion; Robolectric for UI).
##### Task 6.2a: Input, extra-keys row, history, chips, Desktop Snippets menu (M)
##### Task 6.2b: Completion engine + chip row (S)

#### Story 6.3: Confirm tiers
**Acceptance Criteria**:
- Classification table.
  - *Given* the `Risk` mapping, *Then* `graph journals-repair`, `graph reindex`, `graph reload`, `graph restore-backup`, `git merge` and `settings set` are STATE_CHANGE with inline confirm (journals-repair echo lists the dates; restore states "later changes are lost"; reload/reindex/restore add the typed `DbOnlyRowsGuard` confirm when DB-only rows exist) (resolves G3).
- Inline confirm for STATE_CHANGE.
  - *Given* `git set-branch master`, *Then* an inline `Run? [Confirm] [Cancel]` chip echoes `remote_branch: main -> master`.
- Modal for DB writes: shows statement verbatim, tables affected, dry-run row count (e.g. "12 rows"), backup name; button reads "Update 12 rows"; default focus Cancel; Escape cancels; no-WHERE requires typing the table name; the cascade note ("later edits to these pages can write the change to markdown; `graph reload` discards it") is shown.
- Cancel rule (resolves G5).
  - *Given* a confirmed write, *Then* Cancel is available only during dry run and backup; once the actor write starts, Cancel is hidden and the label reads "Writing, can't cancel".
- Typed-confirm matching and dry-run caveat (UX-91).
  - *Given* any typed confirm (mass-change count, no-WHERE table name, `erase N DB-only journals`), *Then* one matcher trims whitespace and ignores case, a count accepts digits only, and a mismatch shows `Doesn't match — type <expected>`; *Given* the SQL write modal for `blocks` or `pages`, *Then* it shows the dry-run count for the statement's own table plus `Search-index rows are updated by triggers and are not counted.`; table-driven matcher test in `commonTest/.../console/TypedConfirmMatcherTest.kt` (`"  PAGES "` matches `pages`, `214 files` does not match `214`).
- Mass-change confirm is not copy-pasteable (UX-103).
  - *Given* a deletion set, *Then* the prompt asks `Of the N files listed, how many are journals? Type the number.`; the expected answer is derived from the listed paths (journals, else non-journals, else distinct folders; first candidate whose digits differ from every number in the headline and question text) and appears nowhere in the prompt; the field takes typed digits only (paste and drop rejected); a mismatch shows `Doesn't match — count the list again` (never the expected value); if all three candidates collide the confirm is withheld and the run stays blocked. Tests in `TypedConfirmMatcherTest`: a `Property` test over generated deletion sets asserting the expected string never equals any number token in the prompt text and the collision fallback blocks; Robolectric `ConfirmDialogsTest` asserts paste is rejected.
- Live region and focus (UX-88).
  - *Given* a block entering AWAITING CONFIRM, *Then* it announces `Awaiting confirmation: <effect>` once and focus moves to Cancel; on command start and finish the chip announces once each; closing the SQL-write modal, no-WHERE confirm, shell-arm dialog or Restore confirm returns focus to the opener or the command input.
- Second confirm for `sh` once per session (desktop); Disarm chip.
- After a write: result, backup location and a persistent "Restore backup" action (not a timed snackbar) that opens a confirm stating later changes are lost, reloads the graph on success, and shows an error with the backup path on failure (UX-56, UX-57, resolves G4).
**Files**: `commonMain/.../console/ui/ConfirmDialogs.kt`, `commonMain/.../console/ConfirmPolicy.kt`, tests (Robolectric dialogs; policy in `commonTest`).
##### Task 6.3a: Policy mapping `Risk` -> `ConfirmTier`, dialogs, cancel rule, restore action (M)

#### Story 6.4: SQL result grid and export
**Acceptance Criteria**:
- Grid: sticky first column, NULL dim, ellipsized ~80 chars with tap to expand, footer with truncation marker and "Export CSV".
  - *Given* 12 rows x 4 columns, *Then* all render; *Given* a 2 MB cell, *Then* it shows the 2 KB-capped value with "(+N chars)".
- Column widths computed off the UI thread on capped rows.
- CSV export states (resolves G12): empty result disables Export CSV with the reason; a failure shows the reason, Retry and copy fallback. CSV goes through the same redaction, `console-exports/` location and cleanup rules as spill files (Story 5.8, UX-90).
**Files**: `commonMain/.../console/ui/SqlResultGrid.kt`, tests.
##### Task 6.4a: Grid + CSV export via `rememberShareProvider` incl. failure states (M)

#### Story 6.5: Entry points and sync cross-link
**Acceptance Criteria**:
- Entry points exist only with developer mode on: Settings > Developer row, Logs overflow item.
  - *Given* developer mode off, *Then* neither exists (verified in 4.5 test and here).
- Back navigation (ux S12).
  - *Given* the console was opened from the sync detail sheet, *When* Back is pressed, *Then* the user returns to the sheet's origin screen, not a deep stack.
- Sync error cross-link.
  - *Given* a `RemoteBranchNotFound` detail sheet and developer mode on, *When* "Open in dev console" is tapped, *Then* the console opens with `git doctor` pre-filled, not executed.
**Files**: `commonMain/.../ui/components/LogDashboard.kt` (existing at this path; the Logs overflow already hosts a `diagnostics` callback at `:36-52,173`, so locate the overflow by that parameter; there is no "Export diagnostics" string in the codebase), `ui/components/settings/SettingsDialog.kt`, `ui/screens/git/BranchRepairSheet.kt` (if Task 2.2d shipped; otherwise the read-only `SyncDetailSheet` from Task 2.1g).
##### Task 6.5a: Entry points + cross-link (S)

#### Story 6.6: Accessibility and UI test suite
**Acceptance Criteria**:
- Each block is one focusable item with a summary description ("Command git status, succeeded in 120 ms, 14 lines"); status chip is a polite live region; chips/keys have `Role.Button` and labels; keyboard: Up/Down, Tab accepts completion, Ctrl+C cancel, Ctrl+L clear, Ctrl+Enter run, Esc closes dialogs; contrast >= 4.5:1 using theme tokens (not the raw amber). Modals trap focus and announce titles, including Restore backup (UX-74).
- *Given* system font scale 2.0 or reduce-motion on, *Then* the console and sheets remain usable and animations are suppressed (ux Accessibility); indeterminate progress bars are replaced by a static `Working…` label.
- Live regions: the sync result line is a polite live region announced once (UX-87); AWAITING CONFIRM, command start/finish and dialog focus return follow Story 6.3 (UX-88); a test collects semantics `liveRegion` and focus targets.
- Partial selection fits the single-node model (UX-102).
  - *Given* a block with output, *When* the user long-presses in its body (or uses TalkBack's `Select text` custom action), *Then* a range can be selected within that block only, `Copy selection` appears while a range exists, the semantics tree still holds one node per block with custom actions `Select text` and `Copy selection` (no focusable lines), `<n> characters selected` is announced politely once per gesture, the copy is the redacted on-screen text, and the selection clears on Escape, new output for that block or collapse. Tests in `ConsoleA11yTest`.
- Badge announcements (UX-95): covered by Task 2.1g tests; `ConsoleA11yTest` does not duplicate them.
- Plain-language audit (UX-92): the `UserFacingCopyAuditTest` of Task 2.1g covers the console-adjacent surfaces added here (Settings > Developer rows, `Console backups`, notification toggle text).
- Compose perf: *Given* 5,001 lines added, *Then* composition completes headless in Robolectric and the first line is evicted.
**Files**: `androidUnitTest/.../console/ConsoleA11yTest.kt`, `ConsolePerfTest.kt`.
##### Task 6.6a: A11y semantics, keyboard map, Robolectric suite (M)

---


# Phase 3: Verification

### Epic 7: Device verification (owner wall-clock blockers)

#### Story 7.1: Sync fix on the owner's device (after PR-A1; A1 is "done" only when this passes)
**Acceptance Criteria**:
- Hypothesis confirmed or refuted from evidence (already recorded by Task 0.3b; restated against the Epic 2 build).
  - *Given* the stamped Epic 1 build installed on the phone, *When* the owner shares the Git section, *Then* the `Build:` line matches the commit under test, and `remote_branch`, `resolve(origin/<branch>)`, ls-remote heads (including any stray `main`), local HEAD branch are recorded and the matching plan branch is marked taken.
- Metric 1/2.
  - *Given* the A1 build, *When* the owner taps Sync with stored `main`, *Then* the red "Branch 'main' not found on remote — tap to fix" appears; after "Use 'master'" (Branch 1) the previewed first-sync review opens (shipped in A1, Task 2.2d0, so this works on an A1-only build) and the owner's "Sync now" tap runs the manual sync (Branches 2-5: the selected remedy, then the same review and tap), `remoteCommitsMerged > 0` and the 2026-10-09/10 journals appear.
- The owner's active graph is the app-private graph (decision b).
  - *Given* the diagnostics Git section, *Then* path-mode reads app-owned (non-`saf://`) for the active graph; a forced check shows `isAppOwnedPath` true for it and false for the two SAF `personal-wiki` registry entries (not a unit table only).
- Numeric result against the baseline (requirements "Metric 1 numeric baseline").
  - *Given* the device pass completes, *Then* the runbook records, before and after one sync: missing recent dates (baseline 2, target 0), `remoteCommitsMerged` (baseline 0, target > 0), `behind` (target 0), on-disk journal count versus the origin-tip count under the same `ls-tree` rule (gap target 0), and the two diagnostics exports diffed (see Task 7.1c).
##### Task 7.1a: Prepare device-pass runbook in the PR description and capture results (XS)
- Owner steps (blockers): install the stamped build, share Git section, tap Sync, confirm path-mode and the stray-branch listing.

##### Task 7.1c: Interim verification when the device run is delayed (S)
- Also the build-start evidence for PR-A2: when this test and the diagnostics diff are green, PR-A2 may start building before Story 7.1; A2's release still waits for Story 7.1. Files: `jvmTest/.../git/InterimPhoneStateEndToEndTest.kt` (new), `jvmTest/.../git/testsupport/BareOriginFixtures.kt` (extended from Task 2.1b0), `project_plans/dev-console-git-sync-fix/implementation/interim-verification.md` (notes written from the run). Implements requirements "Interim verification": temp bare origin with only `master` holding `logseq/journals/2026_10_09.md` and `2026_10_10.md`; a clone whose stored `remote_branch` is `main`; an in-memory repository seeded with the 3 ghost journals. Asserts, in order: sync returns `Error(RemoteBranchNotFound)` and never `Success`; `BranchRepairService` applies `master` with read-back and sets the pending-review key; the review's `Sync now` (no scheduler present) gives `remoteCommitsMerged > 0` and `behind == 0`; `reloadFiles` receives both journal paths; `JournalDiffService` reports 0 missing recent dates and the three ghosts as known exceptions. Then runs `GraphDiagnosticsCollector` before and after and diffs the two exports: `resolve(origin/<branch>)` unresolved -> resolved, `behind` n -> 0, newest-journals listing gains 2026-10-09/10, no credential strings, and no other Git-section field changes unexpectedly. Output is labelled `INTERIM (not device)`; it never marks M1/M4 met. Wrap with `scripts/jvm-display-check.sh --` only if a UI-touching class is added (this one is headless).

##### Task 7.1d: Calibrate the mass-change thresholds on the real graph (XS, owner data)
- Steps: on the owner's 1.6k-journal clone, list the largest legitimate per-commit deletions in history (`git log --diff-filter=D --name-only --pretty=format:%H` grouped per commit, recorded in the PR notes); choose `git_mass_change_min_files`, `git_mass_change_pct` and `git_mass_change_max_journals` so the largest legitimate deletion passes and the seeded deletion test still blocks; set defaults in code only if the measured values differ from `max(20, 5%)` / 10, and record the numbers. Done when: the chosen values and the measured maximum are written in the PR description before PR-A2 is opened for review. **Time-box: one device cycle (the same box as Task 0.3a) after A1 is installed; if the owner has not returned the history numbers by then, PR-A2 proceeds with the compiled-in defaults (`max(20, 5%)` / 10 journals), labelled `UNCALIBRATED DEFAULTS` in the PR description, and Task 7.1d stays open as a follow-up; the thresholds are configurable, so calibration never blocks release.** No code beyond the defaults.

#### Story 7.1b: Scheduled sync and staleness on the owner's device (after PR-A2)
**Acceptance Criteria**:
- Metric 4 soak (wording matches the WorkManager floor and decision a).
  - *Given* three consecutive scheduled syncs at least 15 minutes apart with the app open, *Then* `git doctor` (or the diagnostics Git section before PR-C) shows `behind=0` each time and the journals diff shows 0 missing recent dates.
  - *Given* the DB-only journals 2026-10-07/09/10 are still undecided, *Then* they are listed as known exceptions and do not fail the soak; nothing is written or pushed for them without explicit confirm.
- Closed-app behavior.
  - *Given* the app is closed and the remote gains a commit, *When* the next worker run completes, *Then* the staleness chip, the notification and the launch banner show the behind-count, and no working-tree file changed (compare `git status` before and after).
- First-sync preview shown once, and a deliberately injected mass deletion (test repo) is blocked in a scheduled run.
##### Task 7.1b: Device runbook for scheduled sync, staleness and first-sync enforcement (XS)
- Owner steps (blockers): leave the app open for three intervals; close the app and push a remote commit; observe chip, notification, banner.

#### Story 7.2: Console on the device (after PR-C)
**Acceptance Criteria**:
- Metric 3 (threshold, requirements Metric 3): from the phone with no rebuild, the five named probes `git refs`, `git ls-remote`, `git status`, `graph journals-diff`, `git doctor` each return their result block in under 10 s from the Run tap to the status chip, on three consecutive runs, recorded per probe in the runbook (an `ls-remote` that ends in a TIMEOUT chip on an unreachable remote is a recorded failure of the threshold for that run, not a pass); `git refs`, `git doctor` and `graph journals-diff` answer the two questions and `export` produces a shareable file with no secrets (grep for the PAT).
- `VACUUM INTO` spike result (Task 0.2b) and `sql!` backup succeed on the device; a restore round-trips; the `console-backups/` folder is absent from an Auto Backup dry run (`adb shell bmgr`-equivalent on a dev machine, or recorded as not testable); `sh` shows disabled with the desktop-only reason; TalkBack pass once (cannot be tested headless).
- Contrast: a manual dark/light pass of the console against theme tokens is recorded (resolves G15).
##### Task 7.2a: Runbook (incl. manual contrast pass) + record results (XS)

#### Story 7.3: Closeout
**Acceptance Criteria**:
- Doc and memory updates.
  - *Given* all gates green, *Then* repo-root `CLAUDE.md` gets a short "Dev console" section (package, one-file-addition rule, function-level write-gate opt-in and its allowlist test, redaction best-effort rule, sync-marker rule), the Bazel test target count is recomputed from the real run, and the memory index gets a one-line entry.
- Final gates per PR: `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests ...`, `bazel test //kmp:business_tests`, `./gradlew :kmp:testDebugUnitTest`, detekt, wasm compile, `git diff --stat -- '*.sq' '*MigrationRunner*'` empty; each PR opened as draft.
##### Task 7.3a: Docs, counts, final gate run, draft PRs (S)

---

## Effort estimate

Bands and weights INFERRED (ESTIMATION.md); recalibrate after the first implementer run. Task class tokens: XS 20k, S 55k, M 130k, L 240k (no L tasks planned; all split). Assumed mix per implementation run: 12% output, 38% cache read, 10% cache write, 40% fresh input => 1.16 CU per raw token. Verification multiplier x1.75 (spec + quality review per story, 6-verify layers, CI reruns). The table, totals and per-PR subtotals below were recomputed by script from the `##### Task` lines of this document after the Phase 4 repair (class token = last `(XS|S|M|L)` on each task line; Story 7.1b is Task 7.1b); the x1.75 column is the sum of the unrounded per-story values, shown rounded.

| Story | Tasks | Classes | Raw tokens | Output share | Est. CU (raw) | CU x1.75 | Wall-clock blocker | PR |
|---|---|---|---|---|---|---|---|---|
| 0.1 JGit behavior spikes | 2 | S+XS | 75k | 12% | 87k | 0.15M | none | A1 |
| 0.2 SQLite capability spikes | 3 | M+M+S | 315k | 12% | 365k | 0.64M | Android `VACUUM INTO` run completes on device (7.2) | C |
| 0.3 Device-evidence decision gate | 2 | XS+XS | 40k | 8% | 46k | 0.08M | Owner: install diagnostics build, share Git section | A1 |
| 1.1 Commit diagnostics changes | 4 | XS+XS+XS+S | 115k | 8% | 133k | 0.23M | none | A1 |
| 2.1 Typed error and shared remote-ref resolver | 8 | S+XS+S+M+S+XS+S+S | 445k | 12% | 516k | 0.90M | none | A1 |
| 2.2 Default-branch detection and first-sync review | 7 | M+S+S+M+M+M+S | 685k | 12% | 795k | 1.39M | Gated on 0.3b evidence (Tasks 2.2a2, 2.2b and 2.2d0 are ungated) | A1 |
| 2.3 Push alignment, post-sync invariants and repository-state guard | 6 | S+S+M+S+M+M | 555k | 12% | 644k | 1.13M | none | A1 |
| 2.4 Scheduled sync while open; fetch-only background | 9 | M+S+S+S+S+M+S+S+S | 645k | 12% | 748k | 1.31M | none (staleness and first-sync need no owner input) | A2 |
| 2.5 Conditional remedies for alternative causes | 4 | S+S+S+S | 220k | 12% | 255k | 0.45M | Gated on 0.3b evidence | A1 |
| 3.1 Lazy loading of older journals | 4 | M+S+S+S | 295k | 12% | 342k | 0.60M | none | B |
| 3.2 Journal disk-vs-DB diff and DB-only repair | 2 | M+M | 260k | 12% | 302k | 0.53M | owner decision to run repair | B |
| 3.3 Indexer progress probe and failure paths | 3 | S+M+S | 240k | 12% | 278k | 0.49M | device log confirms signature | B |
| 4.1 Skeleton, contracts, registry | 3 | S+S+S | 165k | 12% | 191k | 0.33M | none | C |
| 4.2 Command-line tokenizer | 1 | S | 55k | 12% | 64k | 0.11M | none | C |
| 4.3 Best-effort redaction at the single sink | 1 | M | 130k | 12% | 151k | 0.26M | none | C |
| 4.4 Scrollback buffer and session | 2 | M+M | 260k | 12% | 302k | 0.53M | none | C |
| 4.5 Developer-mode gate | 2 | S+S | 110k | 12% | 128k | 0.22M | none | C |
| 4.6 Composition root and views | 1 | M | 130k | 12% | 151k | 0.26M | PR #397 `RepositorySet` merge state | C |
| 5.1 `diag` | 1 | M | 130k | 12% | 151k | 0.26M | none | C |
| 5.2 `git` family | 3 | M+M+S | 315k | 12% | 365k | 0.64M | none | C |
| 5.3 `fs` family | 2 | M+M | 260k | 12% | 302k | 0.53M | none | C |
| 5.4 `sql` read | 3 | M+M+M | 390k | 12% | 452k | 0.79M | PR #397 `RepositorySet` merge state | C |
| 5.5 `sql` write, backup, restore | 7 | M+M+S+S+M+S+S | 610k | 12% | 708k | 1.24M | none | C |
| 5.6 `settings`, `logs`, `graph` | 4 | S+M+S+M | 370k | 12% | 429k | 0.75M | none | C |
| 5.7 `sh` | 1 | M | 130k | 12% | 151k | 0.26M | none | C |
| 5.8 Meta commands | 1 | S | 55k | 12% | 64k | 0.11M | none | C |
| 6.1 Screen and blocks | 2 | M+M | 260k | 15% | 302k | 0.53M | none | C |
| 6.2 Phone input ergonomics | 2 | M+S | 185k | 15% | 215k | 0.38M | none | C |
| 6.3 Confirm tiers | 1 | M | 130k | 15% | 151k | 0.26M | none | C |
| 6.4 SQL result grid and export | 1 | M | 130k | 15% | 151k | 0.26M | none | C |
| 6.5 Entry points and sync cross-link | 1 | S | 55k | 12% | 64k | 0.11M | none | C |
| 6.6 Accessibility and UI test suite | 1 | M | 130k | 15% | 151k | 0.26M | TalkBack pass on device (7.2) | C |
| 7.1 Sync fix on the owner's device | 3 | XS+S+XS | 95k | 8% | 110k | 0.19M | Owner: install stamped build, share Git section; threshold calibration data (7.1d) | Dev |
| 7.1b Scheduled sync and staleness on the owner's device | 1 | XS | 20k | 8% | 23k | 0.04M | Owner: app open for 3 intervals (>=15 min), then closed-app check | Dev |
| 7.2 Console on the device | 1 | XS | 20k | 8% | 23k | 0.04M | Owner: device session, TalkBack, contrast | Dev |
| 7.3 Closeout | 1 | S | 55k | 12% | 64k | 0.11M | CI queue | Dev |
| **Total** | **100** | | **8,080k** | | **9.37M** | **16.40M** | | |

Per-PR subtotals (tasks / raw tokens / CU x1.75): **A1**: 33 / 2,135k / 4.33M; **A2**: 9 / 645k / 1.31M; **B**: 9 / 795k / 1.61M; **C**: 43 / 4,315k / 8.76M; **Dev**: 6 / 190k / 0.39M. (Triad repair 1 added three tasks, 5.5g, 7.1c and 7.1d, +130k raw / +0.26M at x1.75; the UX acceptance criteria UX-80..92 were absorbed into existing task sizes, so those tasks carry more acceptance criteria at the same class, an INFERRED trade-off the overrun checkpoint will test.)

Size band (informational): **Large** (3-15M CU), now just above the top of the band at 16.40M x1.75 (9.37M raw-token CU before the verification multiplier). The Phase 4 repair deleted the cold-merge machinery (Tasks 0.1c, the old marker, cold-merge-safety, marker-lifecycle and banner-for-skipped-merge tasks) and added the build stamp, `BranchRepairService`, error/state variants, read-only sheet variants, mass-change guard, staleness, first-sync preview, `DbOnlyRowsGuard`, a larger swap spike and one device story. Planning/review overhead already spent is not included. Per ESTIMATION.md this is a tracking figure, not a scope gate.

**Critical path (agent waves)**: PR-A1: W1 spikes + Epic 1 + fixture extraction -> W2 typed error/resolver (2.1), build stamp (1.1d), `BranchRepairService` (2.2a2) and owner evidence request (0.3a, wall-clock, time-boxed to one device cycle) -> W3 push/invariants/state guard/mass-change guard (2.3) -> W3/W4 first-sync review sheet and consent flag (2.2d0, ungated; needs 2.1d and 2.3f) -> W4 decision (0.3b) then gated 2.2c-e (2.2d after 2.2d0) or conditional 2.5 -> Story 7.1. PR-A2 (Story 2.4: policy, registry, fetch-only cold runner, staleness, scheduled enforcement of the first-sync consent) may BUILD (after A1 merge) building on interim evidence (Task 7.1c green) while Story 7.1 is pending; it may RELEASE only after Story 7.1; its release waits for Story 7.1, and Task 7.1d must be done before it is opened for review. PR-B and PR-C start in parallel with PR-A1 W2: PR-C W1 spikes + leaf units -> W2 session/gate/composition -> W3 command families fan out -> W4 sql write/restore -> W5 console UI -> device passes -> closeout. Serial segments on shared files are listed under Serialization points.

**Overrun checkpoint**: if cumulative spend on completed stories exceeds the table value by more than 25% (for PR-A1, its subtotal x1.25), pause and report the delta and its cause (wrong estimate vs real surprise). Informational, not a cut invitation.

**Wall-clock blockers (owners)**:
1. Owner builds/installs the stamped Epic 1 diagnostics build on the phone and shares the Git section (Task 0.3a) — gates Stories 2.2c-e and 2.5; time-boxed to one device cycle, after which Branch 1 is taken.
2. Owner device verification of sync fix (Story 7.1, defines A1 done and **gates the release of A2**; A2 may be built on interim evidence meanwhile), scheduled soak and staleness with the app open and closed (Story 7.1b, three intervals of >=15 minutes), and console incl. TalkBack (Story 7.2) — owner device.
2b. **PR-A2 owner blockers (two)**: Story 7.1 (device pass; release gate) and Task 7.1d (mass-change threshold calibration from the owner's 1.6k-journal history; time-boxed to one device cycle, after which A2 proceeds on the labelled defaults).
3. Owner answers (safe defaults in Unresolved Questions): mass-change thresholds, older-journal search scope, DB-only journal fate, deletion-propagation deferral, Web subset.
4. PR #397 / `feat/cross-graph-phase2` merge state vs the `RepositorySet` field (Stories 4.6, 5.4) — owner.
5. Bazel/CI queue time for `bazel test //kmp:jvm_tests` and Gradle wasm/Android compiles — CI.
6. Android `VACUUM INTO` instrumented run — owner device.

## Requirements traceability

| Requirement / metric | Where satisfied |
|---|---|
| Metric 1 sync correctness (remote commits are pulled whenever the app is open within the interval or on tap; background fetches keep the behind-count fresh; WorkManager 15-min floor) | Stories 2.1-2.5 (2.4 = open-app full sync + fetch-only background), 7.1, 7.1b |
| Metric 2 no silent no-op | 2.1 (failing-first test), 2.3 matrix |
| Metric 3 console answers without rebuild | Epics 4-6, 7.2 |
| Metric 4 journals up to date (ghosts as known exceptions until decided; soak with the app open) | 3.1-3.3, 2.4 soak, 7.1b |
| Commit diagnostics | Epic 1 |
| Verify push, shallow, wikiSubdir paths | 2.3a-e (incl. `wikiSubdir` test), 2.1c shadow-shaped test, 2.5a-c remedies, `git doctor` path-mode line, 0.1 spikes |
| Auto-commit per `autoCommit` | Task 2.4d |
| Sync truth and data-loss guards (conflict-marker scan, mass-change guard, abort snapshot 2.4i, first-sync confirmation, DB-only rows guard, staleness indicator, build stamp) | 2.3e, 2.3f, 2.2d0, 2.4h, 2.4i, 2.4f, 5.6d, 1.1d |
| Console families, registry, gating, export | Epics 4-6 |
| Security (best-effort redaction, deny-lists, confirm, backup, restore, Auto Backup exclusion) | 4.3, 5.3, 5.4, 5.5, 5.6d, 6.3, ADR-002 |
| Delivery split (PR-A1 / PR-A2 / PR-B / PR-C / device); roadmap fit and release order | "Delivery" section; requirements "Roadmap Fit" |
| Pivot criterion and time-box for the main-vs-master hypothesis | "Pivot criterion" table under "Plan branches", Tasks 0.3b, 7.1 |
| Interim verification for M1/M4 when the device run is delayed | Task 7.1c, requirements "Interim verification" |
| Metric 1 numeric baseline and targets | requirements "Metric 1 numeric baseline", Task 0.3b (measure), Story 7.1 (compare) |
| Metric 5 (console/sync safety) and Metric 6 (safety-scope outcomes) | Tasks 5.5g (drill), 5.6d, 4.3a, 2.3e, 2.3f, 2.2d0, 2.4f, 2.4h, 2.4i; validation "Safety metric mapping" |
| UX-93..UX-103 (repair-sheet concurrency, badge/chip fit and icons, badge announcements, empty states, symbol keys, write hint, notification tap, copy-range selection, non-copyable mass-change confirm) | Stories 2.2, 2.4, 5.6, 6.1, 6.2, 6.3, 6.6; Tasks 2.1g, 2.2d0, 2.4f, 2.4h |
| UX-80..UX-92 (loading, input fit, backups, precedence, live regions, notification permission, file hygiene, confirm matching, plain language) | Stories 2.2, 2.4, 5.5 (5.5g), 5.8, 6.2, 6.3, 6.4, 6.6; Task 2.1g |

## Repair log (Phase 3 repair, 2026-10-10)

Mapping of every finding from `architecture-review.md` (AR), `adversarial-review.md` (ADV) and the `design/ux.md` "Plan gaps" (G) to the change made. "Verified" means the cited path or symbol was opened or grepped before it was written into this plan.

| Finding | Resolution |
|---|---|
| AR Blocker 1 (duplicate registry) | `ActiveGitSyncRegistry` deleted from glossary, pattern table, tech-debt, dependency graph. Reuse existing `GitSyncServiceRegistry` (`WorkManagerSyncScheduler.kt:252`) promoted to commonMain; Task 2.4c resized to registry promotion + production registration + `fetchOnly` -> `runScheduledSync`. New finding while verifying: `register` has no production caller (dead fast path) so Task 2.4c wires it in `GraphContentGitSyncSetup.kt`. ADR-004 amended. |
| ADV C6 | Same as AR Blocker 1 (Task 2.4c rewritten). |
| AR Blocker 2 (`abortMerge`/dirty tree) and ADV B1 | `abortMerge` reused unchanged (`GitRepository.kt:69`); `MergeAbortResult` removed from glossary. Cold path rewritten (Story 2.4, ADR-004): clean-tree-only, in-core merge preflight (spike 0.1c), pre-merge marker, no automatic `abortMerge` in repair, SAF fetch-only (Task 2.4g), tests for dirty tree and failed abort (Tasks 2.4e, 2.4f). `autoCommit` honored (Task 2.4d). Owner question reworded with default "skip + marker". |
| ADV B2 (conflict markers pushed; policy ignores persisted state) | Task 2.3e (repository-state guard + conflict-marker scan before commit and push, matrix row (g)); `ScheduledSyncPolicy` reads persisted markers and repo state (Task 2.4a); `SyncMarkerStore`. |
| AR Blocker 3 (GraphLoader disposition) | `JournalLazyLoader` seam (Task 3.1a0); tech-debt table lists all five `GraphLoader` edits; `IndexStatus` moved to `BackgroundIndexSupervisor`; `onFullyLoaded` signature unchanged, one defaulted `onDegraded` param, implementers listed (Task 3.3b); research/architecture.md row corrected. |
| AR Concern: `fs` symlink/ranged read | Task 5.3a0 adds `readPrefixBytes` and `canonicalPathOrNull` (costed M); Story 5.3 ACs rewritten. |
| AR Concern: `settings list` | Story 5.6: allowlist held in console package; no enumeration added to `Settings`. |
| AR Concern: write gate (a)(b)(c) and ADV C3 | ADR-002 A2/A4: function-level opt-in (CLAUDE.md pattern); `DirectSqlWriteOptInAllowlistTest` created (no prior test); rows-affected holder; spike 0.2c, Task 5.5d, cascade policy and `graph reload` disk-wins. |
| AR Concern: empty remote typed | Task 2.1a adds `RemoteEmpty` (and `InvalidRefName`); ADR-003 amended. |
| AR Concern: cold worker on SAF | Task 2.4g + ADR-004 step 1. |
| AR Concern: `Screen.Console` | Task 6.1a: `@HelpExempt` (sibling pattern verified at `AppState.kt:46-52`) and exhaustive `when` list. |
| AR Concern: composition root location | Task 4.6a: `ui/ConsoleComposition.kt` + `ui/GraphContentConsole.kt`; `GraphContentCameraCapture.kt` untouched beyond Epic 1; serialization points updated; ADR-001 amended. |
| AR Concern: value types | `InvalidRefName` outcome; resolver validates once (Tasks 2.1a, 2.1c). |
| AR Concern: wikiSubdir / SAF shadow coverage | Task 2.3d `wikiSubdir` test; Task 2.1c shadow-shaped resolver test; Story 2.5 remedies. |
| AR Concern: Story 3.2 dependency | Edge to 1.1 dropped (dependency graph); package `journal/` rationale stated. |
| AR Concern: Bazel placement | Task 4.1b acceptance: business_tests class-count delta. |
| AR Nitpicks | x1.75 column recomputed by script; retry predicate site named (Task 2.1e, `GitOperationSupport.kt`), "or" dropped; `MergeResult` construction sites listed (Task 2.3b); `JournalDiff` bounded to counts + 50-date samples; `Risk` contract test (Story 4.1); `java.*` audit as a test (Task 4.1c). |
| AR wrong-path table | Fixed: `ui/GitSyncCoordinator.kt`, `ui/screens/JournalsViewModel.kt`, `ui/components/LogDashboard.kt`, `commonTest/.../git/testsupport/StubGitRepository.kt`, and Story 6.5 no longer greps "Export diagnostics" (locate by the `diagnostics` callback). |
| ADV C1 (evidence gate, remedy branches) | Story 0.3 (Tasks 0.3a/0.3b); Story 2.2 split into ungated (2.2a/b) and gated (2.2c-e); Story 2.5 conditional remedies with tests; "Plan branches" rewritten (5 branches). |
| ADV C2 (SQL guard fallback) | ADR-002 A1 fail closed; Task 5.4c per-platform console-owned read-only connection; Story 5.4 ACs (`DriverFactory.jvm.kt:143` verified returns null; real-driver CTE test). No classifier fallback. |
| ADV C4 (backups) | ADR-002 A3: snapshot per write session and per destructive class, keep 5; Task 5.5e Restore backup with live-DB swap, confirm and failure states; Task 5.5f Android backup exclusion (manifest verified to set no backup attributes); unencrypted-in-app-dir noted. |
| ADV C5 (`sh` vs deny-list/redactor) | `sh` desktop-only (ADR-005 amendment, Story 5.7, UX S9); redactor wording softened (Story 4.3, Risk Control); credential tables off the read allowlist (Story 5.4). |
| ADV C7 (cites) | Repo-root `CLAUDE.md` replaces `kmp/CLAUDE.md`; Task 2.1b0 extracts private `createBareOriginWithCommits`/`setIdentity`; Story 2.3 NO_FF characterization, "diff range wrong" marked UNPROVEN (Task 2.3d, ADR-003); Task 0.1b corrected to the no-config early return at `GitSyncService.kt:238`. |
| ADV C8 (15-minute floor, ghosts) | Task 2.4c clamps to `max(setting, 15)`; requirements.md metrics 1 and 4 reworded; Story 7.1 ghost-exception branch. |
| ADV C9 (phasing) | "Delivery" section: PR-A / PR-B / PR-C / device verification, with dependencies. |
| ADV Minors | Web subset (Task 4.1a, Unresolved Questions); 14-day window aligned (Task 3.2a); `git set-branch` and dialog share `BranchRepairService`; counts recomputed below. |
| Owner decision 3 (research cross-refs) | research/architecture.md GraphLoader and WorkManager rows corrected. |
| Owner decision 4 (device-evidence gate) | Story 0.3, Story 2.2 gate note, Story 2.5. |
| Owner decision 9 (pending questions) | Kept in Unresolved Questions, each with its safe default. |
| UX G1, G14 | Unchanged, as the UX design resolved them. |
| UX G2 | Task 2.2d ACs (result line states) |
| UX G3 | Story 6.3 classification table |
| UX G4 | Task 5.5e + Task 6.3a ACs + ADR-002 A3 |
| UX G5 | Task 6.3a cancel rule |
| UX G6 | Task 4.4b shell-arm lifetime |
| UX G7 | Task 2.2d S2 variants |
| UX G8 | Task 2.4h + Story 2.4 AC (UX-72) |
| UX G9 | Task 4.5b |
| UX G10 | Task 4.4b |
| UX G11 | Task 6.2a (Desktop Snippets menu; long-press deferred in Unresolved Questions) |
| UX G12 | Tasks 5.8a, 6.4a |
| UX G13 | Task 4.1a Web subset; owner confirmation listed |
| UX G15 | Task 7.2a manual contrast pass |

### Re-review repair (R1-R4, 2026-10-10)

Source: `adversarial-review.md` "Re-review" and `architecture-review.md` "Re-review" non-blocking notes. Docs only; no code changed.

| Finding | Resolution |
|---|---|
| R1 (BLOCKER, stale `cleanAtStart` hard reset) | ADR-004 step 5 rewritten: the cold runner **never calls `abortMerge`**; on merge `Left` it re-runs `repositoryState` + `status` and keeps the marker / `Skipped(RepairNeeded)` if dirty or unsure. `ResetType.MERGE`/`KEEP`/per-file checkout recorded as rejected for the cold path (cannot prove paths unmodified without racing the foreground). Story 2.4 ACs, Task 2.4e, Task 2.4f (late-edit test: edit after the status check, forced `Left`, file byte-identical, `abortMerge` count 0), Risk Control invariant and glossary updated. |
| R1b (SAF predicate undefined) | Task 2.4g defines `isColdMergeSafe(repoRoot)` from `AndroidGitShadowSupport.shadowWorktreeFor`/`resolveForJGit` (`AndroidGitRepositoryShadow.kt:41,92`, VERIFIED by grep/open); default fetch-only on unknown/exception; table test; Task 5.2c reuses it. |
| R1c (kill mid-checkout) | ADR-004 step 6 and Story 2.4 AC: compare HEAD to `preMergeSha`; SAFE + HEAD == `preMergeSha` + dirty => `RepairNeeded`, marker kept, live `sync()` does not auto-commit; test in Task 2.4e. |
| R2 (scan scope) | Task 2.3e: added lines of `origin/<branch>..HEAD` plus staged diff, all text files, line-start `<<<<<<< `/`>>>>>>> ` pair, fenced blocks ignored, size cap fails closed; Story 2.3 ACs added. |
| R3 (other commit/push paths) | Task 2.3e lists `GitSyncService.kt:268,369,497,561,604,614` (grep of `gitRepository.(commit\|push)(`, production sources); `resolveConflicts`/`applyJournalMerge` get the marker scan only (legitimately `MERGING`); `SyncMain.kt:155,207,271` and `WasmGitWriteService.kt` recorded as out of scope/exempt. Cross-checked with `sg --pattern 'gitRepository.push($$$)' --lang kotlin kmp/src` (same hits); Task 2.3e re-runs it at implementation start. |
| R4 (`git_pending_conflict` clear path) | New Task 2.4i + ADR-004 step 7: cleared on `resolveConflicts` success, `abortActiveMerge` success, and a clean live `sync()` `Success`; Dismiss does not clear; test that `ScheduledSyncPolicy` returns `Run` afterward. |
| R5 (user `abortActiveMerge` is HARD) | Superseded by Re-review 2 repair below: Task 2.4j. |
| AR note: registry thread safety | Task 2.4c: concurrent-safe map and stress test. |
| AR note: Task 3.3b fakes | Task 3.3b now starts with the `sg`/grep step and lists the 2026-10-10 grep result. |
| Counts | `grep -c '^##### Task'` = 90 (was 89; +Task 2.4i); stories unchanged. Effort table: Story 2.4 -> 9 tasks, 645k raw; total 7,300k raw, 8.47M CU, 14.82M at x1.75; PR-A 37 tasks / 2,355k / 4.78M. |

### Re-review 2 repair (N1, N2, N3, R5, 2026-10-10)

Source: `adversarial-review.md` "Re-review 2". Docs only; no code changed.

| Finding | Resolution |
|---|---|
| N1 (cold push not covered by 2.3e) | Task 2.4e: before every cold `push`, `ColdSyncRunner` calls `repositoryStateSafe` + `findConflictMarkers`; hit => `Skipped(RepairNeeded)`, no push. Extra test with a pre-existing marker-laden local-ahead commit (zero `push` calls). ADR-004 new step 4b. |
| N2 (scan range semantics) | Task 2.3e and Story 2.3 AC: scan is the **net tree diff** `origin/<branch>` vs `HEAD` (`git diff origin/<branch>...HEAD`), not per-commit patches; added-then-removed marker across two commits is allowed (test). No remote-tracking ref: net-diff scan skipped, push fail-closed via the unresolved-ref error that already blocks it (test). ADR-004 live-process bullet amended. |
| N3 (live `sync()` while marker set) | Story 2.4 AC + Task 2.4i + ADR-004 step 6: with `git_merge_in_progress_<graphId>` set, `sync()` may not auto-commit, merge, or push; returns `RepairNeeded`/`ConflictPending` at entry (fetch only allowed); spy test asserts zero `stageSubdir/commit/merge/push` calls. |
| R5 (user `abortActiveMerge` is HARD) | New Task 2.4j (S): `status` before `abortMerge`; non-conflicted modified files snapshotted to an app-private recovery dir (fail closed), user confirms the list, files restored after reset; tests (a)-(e) incl. snapshot-failure and cancel => `abortMerge` count 0 and a byte-identity property test. Story 2.4 AC added; serialization point updated; ADR-004 new bullet. |
| Counts | `grep -c '^##### Task'` = 91 (was 90); `grep -c '^#### Story'` = 35 (unchanged). Effort table by the same script method (raw x1.16 = CU, x1.75): Story 2.4 -> 10 tasks, 700k raw, 812k CU, 1.42M; total 7,355k raw, 8.53M CU, 14.93M at x1.75; PR-A 38 tasks / 2,410k / 4.89M; PR-B 9 / 795k / 1.61M; PR-C 41 / 4,055k / 8.23M; Dev 3 / 95k / 0.19M. |

## Phase 4 repair log (2026-10-10)

Sources: `consistency.md` (2 BLOCKER, 11 CONCERN, 10 NITPICK) and `pre-mortem.md` (P1-1..3, P2 #4-#8, P3 #9). Docs only; no code changed. Owner decisions applied (final): (a) closed-app background sync is fetch-only plus badge/behind-count, full commit+merge+push runs while the app is open or on manual sync; (b) the active graph is the cloned app-private graph, SAF graphs stay fetch-only. This log supersedes the cold-path entries (R1, R1b, R1c, N1, N3, R4, "Cold path rewritten") in the two earlier repair logs above, which describe a design that no longer exists.

| Finding | Resolution |
|---|---|
| Decision (a) | Cold full-sync path deleted. `ColdSyncRunner` is fetch-only and records `SyncStaleness` (Task 2.4e). Deleted with it: spike Task 0.1c, `SyncMarkerStore`, `git_merge_in_progress_`/`git_pending_conflict_` markers, `MergeInProgressMarker`, kill-point repair routine, cold push guard, live-`sync()` marker gate, `ColdSyncSafetyTest` and the cold merge/marker tests, ADR-004 steps 1-7 (ADR-004 rewritten). A live-process kill mid-merge is covered by the repository-state guard returning `SyncState.RepairNeeded`. Kept: `abortActiveMerge` snapshot (Task 2.4i, live path). Requirements Metrics 1 and 4 reworded as instructed. |
| Decision (b) | Policy returns `FetchOnly(Saf)` for non-app-owned graphs (Task 2.4a/2.4g, `isAppOwnedPath`); Story 7.1 verifies the owner's real active graph is classified app-owned. Interpretation flagged in Unresolved Questions: a manual Sync on a SAF graph is unchanged. |
| BLOCKER 1 | New ungated Task 2.2a2 creates `BranchRepairService`; Story 2.2 file list, Task 2.2d (now only consumes it) and Story 5.2 `git set-branch` AC updated. |
| BLOCKER 2 | Resolved by (a): requirements Decisions, Metrics 1 and 4 and plan Story 2.4/7.1b state the live-versus-closed split; the owner no longer needs to be asked about cold dirty-tree/SAF behavior. |
| P1-1 | `FirstSyncUnconfirmed` policy state, `git_first_sync_confirmed_<graphId>`, read-only preview (ahead/behind, all remote branches with strays flagged, first 20 local-only commits, files-to-delete) and "Sync now" (Task 2.4h, UX-75, ux S14); Story 0.3 AC and Task 0.3b record a stray remote `main`. |
| P1-2 | `SyncStaleness` store, always-visible chip, amber after 2x interval, last-10 outcome history in `diag`/`git doctor`, notification and launch banner (Task 2.4f, UX-71/72/76); "fetch-only (<reason>)" instead of green for a mis-classified graph; Story 7.1 forced classification check on the real graph (not only a unit table). |
| P1-3 | Task 2.3f mass-change guard (deleted > `max(20, 5%)` or > 10 journals), foreground typed-count confirm, never liftable by scheduled runs, missing-subdir test, `NoForcePushAuditTest`; new `MassChangeBlocked` variant (Task 2.1f), UX-77. Thresholds are INFERRED. |
| P2 #4 | `DbOnlyRowsGuard` (Task 5.6d) for `graph reload`/`reindex`/`restore-backup`: exports DB-only journals (2026-10-07/09/10) to `console-recovery/` first, typed confirm, refuses if export fails (UX-78); cascade test extended to trigger-maintained FTS tables (Task 5.5d); ADR-002 A3/A4. |
| P2 #5 | Task 0.2b enlarged to a device swap spike (`DbSwapSpikeTest`); Task 5.5e depends on it, closes the console reader first, deletes stale WAL/SHM, verifies `integrity_check` + row counts, keeps `*.pre-restore` until next clean launch. |
| P2 #6 | Task 1.1d build stamp in diagnostics header and `diag`; Task 0.3b rejects unstamped exports; A1 "done" = Story 7.1 passed. The Android emulator `file://` clone test suggested in the pre-mortem was NOT added (see unresolved). |
| P2 #7 | Export preview with highlighted redactions and a note-content warning (Task 5.8a). The "SQL credential allowlist instead of deny-list" suggestion was NOT adopted beyond what ADR-002 already says (credential tables are off the read allowlist); see unresolved. |
| P2 #8 | PR-A split: A1 (Stories 0.1, 0.3, Epic 1, 2.1, 2.2, 2.3, 2.5) and A2 (Story 2.4); Delivery table, dependency graph, effort table PR column and per-PR subtotals updated; Task 0.3a time-boxed (Branch 1 default after one device cycle). |
| P3 #9 | Story 4.4 AC: bounded buffering end to end (100k rows under a small heap, incl. export) and injected-OOM test on the console scope; `ConsoleBoundedStreamingTest`. |
| C1 | Delivery table row for A now reads Stories 0.1 and 0.3; Story 0.2 is listed under PR-C. |
| C2 | Moot: the cold preflight and its spike (0.1c) are deleted; no `0.1d` cite remains. |
| C3 | One rule: `SyncInvariantViolated` is a `SyncState.Error` rendered amber when the remote is still ahead after a sync, red otherwise (glossary, Task 2.1g, ux S1/N3, ADR-003). The ungated Task 2.1g owns it, not the gated 2.2d. |
| C4 | `ColdSyncOutcome` is `Fetched(behindCount, reason)` with reason AppClosed or Saf / `Error`; `Saf` appears once. |
| C5 | New Task 2.1f adds `SyncState.RepairNeeded` and `GitError.ConflictMarkersPresent`/`ScanIncomplete`/`MassChangeBlocked` with strings; glossary, ADR-003 (seven variants + `RepairNeeded`), UX S1 rows and UX-79 added. |
| C6 | New ungated Task 2.1g ships the read-only invariant / detached / empty / repair-needed / marker / mass-change sheets. |
| C7 | Grep found no ViewModel/`rememberSaveable`/retained-state mechanism in `androidMain`; the real manifest is `androidApp/src/main/AndroidManifest.xml:44`, whose `MainActivity` declares `configChanges` covering orientation, keyboardHidden, keyboard, screenSize, locale, smallestScreenSize, screenLayout, uiMode and navigation, so no Activity recreation on rotation and a `remember {}`-hosted session survives it. Story 4.6 AC, Task 4.6a, new `ConsoleRetentionTest` (config-change retention + manifest assertion), UX-35 rewritten (process death still loses state, UX-34), ADR-001. The earlier check of `kmp/src/androidMain` found nothing because that manifest declares no activity. |
| C8 | Requirements In Scope gained a "Sync safety and visibility additions" list (marker scan, mass-change guard, first-sync confirmation, staleness, abort snapshot, restore-backup, Auto Backup exclusion, DB-only guard, build stamp). |
| C9 | Setting: UI term "developer mode", key `developer_mode_enabled`, glossary `DeveloperModeEnabled`, file `DeveloperMode.kt`, test `DeveloperModeGateTest`. Surface: "branch repair sheet", class `BranchRepairSheet` (validation test names updated). |
| C10 | "raw JGit-backed subcommands" cut from requirements (closed list, `doctor` added); AC added for "no read connection => writes refused" (Story 5.5, `SqlWriteCommandTest`); requirements say console history is in-memory only with no opt-in. |
| C11 | Task 2.1a/2.1f assert the exact ux.md S1 strings; Story 2.1/2.3 ACs use the full "Remote is empty — push a commit first or check the URL". |
| NITPICK 1-10 | 1 Open Questions 5/6 marked answered; 2 requirements name the bounds (15 s detection/ls-remote, 300 s fetch); 3 Story 6.3 table gained `git merge` and `settings set`; 4 skip reasons in the `ScheduledSyncPolicy` glossary row; 5 ACs added for `sh --help` (5.7), `RUNNING (partial)` (4.4/5.8), Back from the console (6.5), read-failure-off (4.5), reduce-motion/font scale (6.6), UX-68 already had a test; 6 G8 row rewritten; 7 Story 5.2 "refuses with sync in progress"; 8 ADR-001 lists `DbRestoreView`; 9 requirements say remote equality is verified by `behind=0`; 10 "interpreter" reworded to command dispatcher. |

**Counts (recomputed with `grep -c`)**: `grep -c '^##### Task'` = 96 (was 91: -Task 0.1c, -1 net in Story 2.4 (10 -> 9), +1.1d, +2.1f, +2.1g, +2.2a2, +2.3f, +5.6d, +7.1b); `grep -c '^#### Story'` = 36 (was 35: +Story 7.1b). Effort table regenerated by script (class tokens XS 20k, S 55k, M 130k, L 240k; x1.16 = CU; x1.75 verification): total 7,895k raw, 9.16M CU, 16.03M at x1.75; A1 32 / 2,005k / 4.07M; A2 9 / 720k / 1.46M; B 9 / 795k / 1.61M; C 42 / 4,260k / 8.65M; Dev 4 / 115k / 0.23M. Validation: requirement-mapped tests 197 (Unit 121, Integration 75, Migration 1), requirements 26/26; UX criteria 79/79 (UX-75..79 added), Robolectric 52 and commonTest-level 27 plus 4 cross-cutting.

## Triad repair 1 (2026-10-10)

Source: product, UX and engineering triad review of `project_plans/dev-console-git-sync-fix/`. Docs only; no code changed. Files touched: `requirements.md`, `design/ux.md`, `implementation/plan.md`, `implementation/validation.md`. Recounts use the same method as the effort table (class token = last `(XS|S|M|L)` on each `##### Task` line; x1.16 CU per raw token; x1.75 verification multiplier); the script and `grep` agree.

| Finding | Resolution |
|---|---|
| PRODUCT 1 (roadmap fit) | requirements "Roadmap Fit (why now)": unblocks daily journaling sync, relation to PR #397 (shares into journals need working sync; shared base branch; `RepositorySet` overlap only in PR-C), release order A1 -> A2 -> B -> C; Delivery section restates the order and the interim-evidence rule. |
| PRODUCT 2 (pivot criterion) | "Pivot criterion" table under "Plan branches" (first matching row selects Branch 1-5 or "not a sync defect"; inconclusive rule; one-device-cycle time-box; second gate after a failed Story 7.1); Task 0.3b applies it; requirements Feasibility Risks summarises it. |
| PRODUCT 3 (interim verification) | requirements "Interim verification when the device run is delayed"; Task 7.1c (JVM end-to-end against a temp bare origin plus before/after diagnostics export diff), labelled `INTERIM (not device)`; M1/M4 stay open. |
| PRODUCT 4 (numeric M1 baseline) | requirements "Metric 1 numeric baseline" table with sources. The relayed repo-vs-phone figures (1,587 / 9,430 vs 1,578 / 9,417) and the "3 pages" count are NOT in any project doc and do not reproduce (local clone at `3282e8010`: 1,585 journals, 9,409 pages by `git ls-tree`); only 2 missing recent dates, `remoteCommitsMerged` 0, 1,578 on-disk, 35 in DB and 9,414 awaiting index are recorded as established. Task 0.3b measures the authoritative gap; Story 7.1 compares. |
| PRODUCT 5 (safety metrics) | requirements Metrics 5 (zero unrecovered loss, zero credential strings) and 6 (chip accuracy, mass-change 6/6, marker, first-sync, abort recovery); validation "Safety metric mapping"; no new inventory ID (26/26 holds). |
| UX loading states (S2/S5/S14) | ux S2/S5/S14 loading sections, cross-cutting "Loading states" rule, UX-80, UX-81; plan Story 2.2/2.4 ACs and Tasks 2.2c, 2.2d, 2.4h. |
| UX extra keys, vertical space, IME flags | ux S7 paragraphs; UX-82, UX-83, UX-84; plan Story 6.2 ACs. The Compose hook for no-extract/no-fullscreen is UNVERIFIED (Task 6.2a decides). |
| UX durable backups | ux S17; UX-85; new Task 5.5g (`backups` command, Settings > Developer > Console backups) and `ConsoleWriteSafetyDrillTest`. |
| UX banner precedence | ux S1 precedence rule; UX-86; `BannerPrecedence` in Task 2.4f. |
| UX persistent result line, live regions, focus | ux N3/S3 changed from toast to a persistent polite line; ux S8a/S8d and cross-cutting rule; UX-87, UX-88; plan Story 2.2, Story 6.3, Story 6.6. |
| UX Android 13+ notification flow | ux S15 permission table; UX-89; plan Story 2.4 AC and Task 2.4f (key `git_sync_notify_enabled`). |
| UX spill/CSV hygiene | ux S10; UX-90; `console-exports/` (session-scoped, 20 MB cap, redacted before write, excluded from backup) added to Migration Plan, Task 5.5f, Stories 5.8 and 6.4. |
| UX typed-confirm rules, dry-run caveat | ux S16/S8b/S8c; UX-91; `TypedConfirmMatcherTest`; Story 6.3 AC. |
| UX plain-language audit | ux cross-cutting rule with banned-word list; detached-HEAD badge copy changed to "Your notes folder isn't on a branch — tap for details" (ux S1, plan Task 2.1a); UX-92; `UserFacingCopyAuditTest` in Task 2.1g. |
| ENGINEERING GraphLoader wording | Tech Debt table, Story 3.1 AC/Files and Task 3.1a now say Task 3.1a **adds** `loadJournalsOlderThan` and `ensureJournalLoaded` (new port members and one-line delegating methods), not overrides of existing ones. |
| ENGINEERING Tasks 1.1a-c first, stage by name | Delivery "Commit-order rule" and the Serialization points header. |
| ENGINEERING configurable mass-change thresholds | Task 2.3f `MassChangeThresholds` with three Settings keys (allowlisted in `settings`, Story 5.6); new Task 7.1d calibrates on the 1.6k-journal graph before PR-A2; Unresolved Questions and Migration Plan updated; one override test added. |
| Counts | `grep -c '^##### Task'` = 99 (was 96; +5.5g, +7.1c, +7.1d); `grep -c '^#### Story'` = 36 (unchanged). Effort table regenerated by script: total 8,025k raw, 9.31M CU, 16.29M at x1.75; A1 32 / 2,005k / 4.07M; A2 9 / 720k / 1.46M; B 9 / 795k / 1.61M; C 43 / 4,315k / 8.76M; Dev 6 / 190k / 0.39M. Validation: requirement-mapped tests 211 (Unit 129, Integration 81, Migration 1), requirements 26/26; UX criteria 92/92, Robolectric 62 and commonTest-level 30 plus 5 cross-cutting (4 Manual, 1 jvmTest-UI). |

Unresolved after this repair: the repo-side baseline gap and the "3 pages" figure (UNVERIFIED until Task 0.3b); the Compose API for IME no-extract/no-fullscreen flags; whether an existing Android notification channel can be reused (implementer greps, Task 2.4f); the `console-exports/` 20 MB cap and the three threshold defaults are INFERRED values; PR #397 merge state still gates Stories 4.6/5.4.

## Triad repair 2 (2026-10-10)

Source: product, UX and engineering triad review (second pass). Docs only; no code changed. Files touched: `requirements.md`, `design/ux.md`, `implementation/plan.md`, `implementation/validation.md`, `implementation/consistency.md`, `implementation/pre-mortem.md`. No new tasks or stories: new work is acceptance criteria inside existing tasks.

| Finding | Resolution |
|---|---|
| UX 1 (repair vs first-sync contradiction) | One flow: "Use 'master'" saves and read-back verifies, then opens the previewed first-sync review (S14 entry (c)); the user's "Sync now" tap is the consent and the only sync trigger; "Not now" leaves the branch saved with the badge "Review first sync". Changed: ux S3 flow, S14, UX-02 (now 3 taps: badge, Use, Sync now), UX-08/09/10; plan glossary `FirstSyncUnconfirmed` row (`:55`), Story 2.2 repair AC, Task 2.2d, Task 2.4h AC (`:477`), Story 7.1 (`:973`); validation UX-02/UX-08 rows and happy path. |
| UX 2 (repair-sheet concurrency) | ux S3 concurrency table; UX-93; Story 2.2 AC; two S3 Unit rows. |
| UX 3 (badge/chip 360 dp, targets, labels, icons) | ux S1 fit rules and variant table (warning triangle vs error octagon); UX-94, UX-95; Task 2.1g. |
| UX 4 (empty and first-use states) | ux S7, S14, S15, N1; UX-96, UX-97, UX-98; Story 5.6/6.1 ACs, Tasks 2.4f/2.4h. |
| UX 5 (SQL typing) | Row B page 2 `! % = ( ) , < >` via a pinned `More symbols` key (strip threshold 400 -> 440 dp); hint plus `Switch to sql!` chip for writes under plain `sql`; UX-99, UX-100; Story 6.2; `SqlWriteHintTest`. |
| UX 6 (notification tap) | ux S15 paragraph; UX-101; Task 2.4f AC; `NotificationTapRouterTest`. |
| UX 7 (partial selection) | ux S7 item 11, a11y rule; UX-102; Story 6.6 AC; selection is a mode of the one block node with custom actions. |
| UX 8 (chip vocabulary) | UX-27, ux S7 item 1, Story 6.1 AC: OK/ERR/CANCELLED/TRUNCATED/AWAITING CONFIRM/RUNNING/TIMEOUT. |
| UX 9 (copy-pasteable mass-change confirm) | ux S16: answer is a count derived from the listed paths (journals, else non-journals, else distinct folders; first candidate not equal to any number printed in the prompt), paste/drop rejected, hint never reveals the answer, fail closed on total collision; UX-103; Story 6.3 AC; `TypedConfirmMatcherTest` property row and Robolectric `ConfirmDialogsTest` row. |
| PRODUCT (Metric 3 threshold) | requirements Metric 3: five named probes each < 10 s from Run tap on the owner's device, three consecutive runs; Story 7.2 AC; validation M3 CI proxy row and updated manual row; residual risk 17. |
| ENGINEERING (consistency/pre-mortem staleness) | "Superseded" headers with per-item resolving task ids on `consistency.md` and `pre-mortem.md`. |
| ENGINEERING (A2 start, 7.1d time-box) | PR-A2 may build on interim evidence (Task 7.1c green), release gated on Story 7.1; Task 7.1d time-boxed to one device cycle, then A2 proceeds on labelled `UNCALIBRATED DEFAULTS`; the two A2 owner wall-clock blockers (Story 7.1, Task 7.1d) recorded in the Delivery table and "Wall-clock blockers" item 2b. |
| Counts | Plan: `grep -c '^##### Task'` = 99, `grep -c '^#### Story'` = 36 (unchanged); effort table unchanged (16.29M at x1.75; same class tokens). Validation, by `python3 -I counts.py` over the tables: requirement-mapped tests 217 (Unit 134, Integration 82, Migration 1), requirements 26/26; UX rows 103 (Robolectric 71, commonTest-level 32), UX-01..UX-103 contiguous, plus 5 cross-cutting. |

Unresolved after this repair: the < 10 s probe bar and the 440 dp strip threshold are INFERRED; the Compose hook for blocking paste in the typed-count field (Task 6.3a) and for custom accessibility actions on a selectable block (Task 6.6) are UNVERIFIED implementation details; the notification-tap behavior for a graph not currently in the registry is specified but untested on a device (Story 7.1b); the DB-only-rows typed confirm (`erase N DB-only journals`) intentionally remains copyable.

## Triad repair 3 (2026-10-10)

Sources: triad re-review: one BLOCKER (repair flow sequencing across PR-A1 and PR-A2) and three gaps. Docs only; no code changed.

| Finding | Resolution |
|---|---|
| BLOCKER (A1-only build leaves `Review first sync` with no sheet) | Task 2.4h split. New ungated **Task 2.2d0** (M, PR-A1, in Story 2.2, before Task 2.2d): `FirstSyncConfirmation` (`git_first_sync_confirmed_<graphId>`), the `FirstSyncPreviewSheet`, the manual `Sync now` running the existing `GitSyncService.sync`, and the A1 badge driven by a new `git_first_sync_review_pending_<graphId>` key written by a repair or `git set-branch`. **Task 2.4h** is now S, PR-A2: `ScheduledSyncPolicy` enforcement, badge widening to every unconfirmed config, notification and launch-banner entry, staleness integration. Updated: Delivery table (A1 and A2 rows), dependency graph, Story 2.2 (Depends on, Gate, ACs moved from Story 2.4), Story 2.4 ACs and Files, Task 2.2d, glossary `FirstSyncUnconfirmed`, persisted-state list, critical path, traceability rows, Story 7.1 and Task 7.1c, effort table and per-PR subtotals; ux S3/S14, UX-02, UX-08; validation rows. |
| (a) Precedence misses the badge re-entry | Rule: the `Review first sync` badge is never a banner; it is the re-entry after "Not now" or a dismissed first-sync banner. `BannerPrecedenceTest` now covers all 16 subsets of {mismatch, first-sync review, updates waiting, `Review first sync` badge}; ux S1 and UX-86 reworded. |
| (b) S14 dead end when remote numbers fail | `Sync without preview` on the timeout or failure state only, behind an explicit confirm that states ahead/behind and remote branches are unknown, typed `sync` (UX-91 matching), guards unchanged, confirmation stored only on `Success`. New UX-104; ux S14; tests in `GitSyncCoordinatorTest`, `FirstSyncPreviewSheetTest`. |
| (c) Delivery vs dependency chart | One rule everywhere: A2 may BUILD after A1 merge plus Task 7.1c, and RELEASE only after Story 7.1 (Delivery table, graph heading, critical path, blockers). |
| Counts | `grep -c '^##### Task'` = 100 (was 99; +2.2d0), `grep -c '^#### Story'` = 36. Effort: total 8,080k raw, 9.37M CU, 16.40M at x1.75 (was 8,025k, 9.31M, 16.29M); A1 33 / 2,135k / 4.33M, A2 9 / 645k / 1.31M (Task 2.4h M -> S). Validation and UX counts in the validation summary. |

## Triad repair 4 (minor, 2026-10-10)

Sources: triad re-review: three minor gaps in the first-sync review. Docs only; no code changed. No tasks added (`grep -c '^##### Task'` unchanged), effort unchanged: the ACs are absorbed into Task 2.2d0 (M).

| Finding | Resolution |
|---|---|
| G1 (confirmation only on the S14 button; amber invariant badge could hide the re-entry) | Task 2.2d0 ACs: the confirmation hook sits in the common manual-sync `Success` path (badge sync, S14 `Sync now`, mass-change confirm run, invariant `Retry`) and clears the pending key; amber `SyncInvariantViolated` stores nothing; the worst-error badge's sheet carries a `Review first sync` row (state precedence). ux S14, S1, UX-105. |
| G2 (`Change back` lost on reopen or process death) | New key `git_first_sync_previous_branch_<graphId>` written by the repair and `git set-branch`, cleared with the pending key; `Change back` hidden if unknown. Task 2.2d0 Files, persisted-state AC. ux S14, E2, UX-106. |
| G3 (no route from the S14 error state to the confirm) | `Open details` action for `MassChangeBlocked`, `ConflictPending`, `RepairNeeded`; the typed-count confirm stays in its own sheet. ux S14, UX-107. |
