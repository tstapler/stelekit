# Requirements: dev-console-git-sync-fix

**Date**: 2026-10-10
**Type**: feature addition + bug fix
**Complexity**: 3 — system design (new cross-platform subsystem touching DB, git, filesystem; security-sensitive because it permits raw SQL writes and shell)

## Problem Statement
Two linked problems, for the owner (sole user, personal Logseq wiki synced via git):

1. **Git sync silently misses remote commits.** On Android the active graph is a cloned repo; `GitSyncService` reports `Success(remoteCommitsMerged=0)` while the phone is missing the 2026-10-09/10-10 journals and several pages that exist on `origin/master` (diagnostics 2026-10-10T16:50Z vs local repo `3282e8010`). Leading hypothesis (INFERRED, not yet confirmed on device): `AndroidGitRepository.doFetch` (`:206-207`) treats an unresolvable `<remote>/<remoteBranch>` as "no changes", and `GitConfig.remoteBranch` defaults to `"main"` while the remote only has `master`. JvmGitRepository has the identical code.
2. **Diagnosing on-device issues needs a new app build per question.** Each investigation requires editing `GraphDiagnosticsCollector`, building, installing, exporting. There is no way to ask ad-hoc questions of a running instance.

## Roadmap Fit (why now)
- **Unblocks daily journaling sync.** The owner journals daily and the phone is currently missing the two newest journals (2026-10-09, 2026-10-10). Until sync is trustworthy, every journal written on one device is at risk of living on only that device; this is the highest-frequency user-visible failure in the app today.
- **Relation to cross-graph PR #397** (`cross-graph-merge-share-target`, implemented, ready, CI green per the project memory index entry `project_cross_graph_merge_share_target.md`). #397 adds writes into other graphs' journals (`TargetWriterRouter`, `JournalAppender`); those writes only help if the target graph then syncs. This work does not touch `TargetWriterRouter`; it shares the base branch `feat/cross-graph-phase2` and one file-level overlap (`RepositorySet` gains a nullable `sqlConsole` field in PR-C, plan Stories 4.6/5.4), so PR-C rebases after #397 merges.
- **Ordering (value first, each unit independently shippable)**: **PR-A1** (stop the silent no-op, owner metric 1/2, including the previewed first-sync review and its consent that the branch repair flows into) -> **PR-A2** (scheduled full sync while open, staleness, scheduled enforcement of the first-sync consent; may be built after A1 merges and Task 7.1c, released only after Story 7.1) -> **PR-B** (journal visibility: lazy older journals, diff/repair, index status) -> **PR-C** (dev console, ships dark behind the developer-mode toggle). PR-B may be built in parallel with A1 (plan Delivery table) but is released after A2; PR-C is last because it adds attack surface and is not needed to fix sync.

## Baseline
- Diagnostics export (Logs screen) is a fixed report; as of this session it includes a new "Git sync" section (config, status, refs, ls-remote, log) — **uncommitted**, in `GraphDiagnostics.kt` and `jvmCommonMain/.../GitRefDiagnostics.kt`, compiles on JVM+Android, `GraphManagerSwitchNotesPathTest` passes.
- Fetch with unresolved ref: returns `FetchResult(false, 0)`, UI shows green success. No log line.
- Off-graph/branch mismatch is only discoverable via adb or by reading code.

### Metric 1 numeric baseline (2026-10-10 diagnostics; sources checked 2026-10-10)
| Quantity | Phone baseline | Source / status | Target after one sync |
|---|---|---|---|
| Recent (last 14 days) journal dates missing on the phone | 2: 2026-10-09, 2026-10-10 | This file, Problem Statement item 1 (diagnostics 2026-10-10T16:50Z vs local repo `3282e8010`) | **0** |
| Other pages missing on the phone | "several" (a count of 3 was relayed in the request that triggered this repair; it appears in no project doc, so UNVERIFIED) | Problem Statement item 1 | 0 files in `git diff --name-only HEAD origin/<branch>` |
| Journal files on phone disk | 1,578 | `research/features.md:52`, Open Question 3 | equals the origin-tip count under the Task 0.3b counting rule |
| Journals in the phone DB | 35 (of which 3 are DB-only ghosts: 2026-10-07/09/10) | `research/features.md:52`, Open Question 4, plan Story 3.2 example | unchanged by sync (lazy load is by design, ADR-006); ghosts stay known exceptions |
| Pages awaiting full index | 9,414 | Open Question 3 | informational; must reach 0 or a recorded `lastError` (plan Story 3.3) |
| `remoteCommitsMerged` on tap | 0 (false green) | Problem Statement item 1 | > 0 on the first sync after repair |
| `behind` after sync | not reported (fetch silently no-ops) | plan Story 2.3 | 0 |

**Repo-side comparison (UNVERIFIED as relayed):** the request that triggered this repair quoted phone 1,578 journals / 9,417 pages versus repo 1,587 / 9,430. The 1,578 matches the docs; 9,417 and 1,587 / 9,430 are not in any project document. Today's direct count of the local clone `~/Documents/personal-wiki` (`git ls-tree --name-only <rev> logseq/journals/ | grep -c '\.md$'`) is 1,585 journals and 9,409 pages at `3282e8010` (9,410 at HEAD `56cb5392e`), so the relayed repo figures do not reproduce under that counting rule. The authoritative gap is therefore captured once, by Task 0.3b, from the stamped export and the same `ls-tree` rule applied to origin's tip, and recorded in `device-evidence.md`; this table's "missing recent dates = 2" and "merged = 0" rows are the only baselines treated as established.

## Users / Consumers
The owner, on Android (primary, no adb), Desktop, and ideally Web. Secondary: future contributors debugging device-only failures.

## Success Metrics
1. **Sync correctness (primary):** after the fix, on the owner's device remote commits are pulled whenever the app is open within the interval or on tap (manual sync is immediate); background fetches keep the behind-count fresh when the app is closed; WorkManager's periodic floor is 15 minutes (Android effective interval = `max(configured, 15 min)`; desktop: the configured interval). Newly committed journals/pages appear in the app after such a sync; `remoteCommitsMerged > 0` when remote is ahead. With the app closed nothing is merged, committed or pushed in the background (owner decision 2026-10-10). Verified against the real device by the diagnostics Git section (`resolve(origin/<branch>)` resolved, `behind=0`) and disk-vs-DB journal diff showing 0 missing recent dates.
2. **No silent no-op:** unresolved remote ref → `SyncState.Error` with an actionable message (regression test fails on old code).
3. **Console:** the owner can answer "what branch/refs/log/status does this clone have?" and "which journals are on disk but not in DB?" from inside the running app, with zero rebuilds; results exportable to a file/share sheet. Baseline: 0 of these without a rebuild. **Threshold (measurable):** the console answers the five named probes `git refs`, `git ls-remote`, `git status`, `graph journals-diff` and `git doctor`, each returning its result block (final status chip OK) in **under 10 s** from the Run tap on the owner's device, on three consecutive runs, with the per-probe times recorded in the Story 7.2 runbook. A probe that ends in a TIMEOUT or ERR chip, including `git ls-remote` against an unreachable remote, counts as a miss for that run. The 10 s bar is INFERRED (below the 15 s detection bound); the CI proxy runs the same five probes under a fake clock (validation M3 row), and only the device run can mark the metric met.
4. **Journals up to date:** last 14 days of journals on disk == in DB == on remote, and stay so across 3 consecutive scheduled syncs with the app open (Android: at least 15 minutes apart); "on remote" is verified by `behind=0` after each sync, with no separate remote-side check. With the app closed only the behind-count is kept fresh; merges happen when the app is next open or on tap. If the DB-only journals 2026-10-07/09/10 are still undecided, they are excluded from the equality check and listed as known exceptions.
5. **Console and sync safety (no unrecovered data loss, no credential leak).** In the verification run: (a) every console DB write is covered by a valid snapshot (one per write session and per destructive statement class, ADR-002 A3) and a restore drill passes, meaning after the seeded writes `graph restore-backup` returns row counts and a table checksum equal to the pre-write state; (b) every `graph reload`/`reindex`/`restore-backup` with DB-only rows exports all of them first (3 of 3 for 2026-10-07/09/10) before disk wins; (c) **0 credential strings** (seeded PAT, URL userinfo, exact vault secret) appear in console output, history, audit log, exports, spill files or CSV in the redaction suite (the documented hex/base64 transformed-output limit is the only exception and is reported as such); (d) 0 working-tree files change in any closed-app run. Target: **0 unrecovered losses and 0 leaks**; any single failure blocks the PR that introduced it. Measured under existing requirement IDs N3, N7, S9, S12 (see validation "Safety metric mapping"); no new requirement ID.
6. **Measurable outcomes of the added safety scope** (the "Sync safety and visibility additions" list). Each is a pass/fail count in validation:
   - *Staleness chip accuracy*: the chip state (green / amber / fetch-only text) equals the specified function of (age, behind, interval, last-N outcomes, fetch-only reason) for **100%** of generated inputs (property test), and is never green when fetch-only; the notification posts at most once per behind-count change.
   - *Mass-change guard*: the seeded deletion test (> `max(20, 5%)` tracked files, or > 10 journals, or a missing `logseq/journals/` tree) is blocked at **6 of 6** guarded commit/push entry points, origin unchanged, and a scheduled run lifts the guard **0** times.
   - *Conflict-marker scan*: the seeded marker file results in **0** pushes; a fenced-code note documenting git syntax causes **0** false blocks.
   - *First-sync confirmation*: **0** scheduled merges/pushes before a successful manual, previewed sync for the current `(remote, branch)`.
   - *Abort recovery*: post-abort contents byte-identical to pre-abort for **100%** of generated non-conflicted edit sets.
   - *Thresholds are configurable* (Story 7.1 calibration task): the mass-change defaults are replaced by values calibrated on the owner's 1.6k-journal graph before PR-A2 ships.

### Interim verification when the device run is delayed
Metrics 1 and 4 are only "met" on the owner's device (Story 7.1/7.1b). If the device run has not happened after one device cycle (the same time-box as Task 0.3a), PR-A1 may proceed to review on **interim evidence**, labelled `INTERIM (not device)` and never reported as the metric being met:
1. **JVM end-to-end test** (plan Task 7.1c): a temp bare origin whose only branch is `master` and which holds `logseq/journals/2026_10_09.md` and `2026_10_10.md`, a clone whose stored `remote_branch` is `main`, and an in-memory DB seeded with the 3 ghost journals. Sequence: sync -> typed error (no green) -> `BranchRepairService` repair + read-back -> sync -> `remoteCommitsMerged > 0`, `behind == 0`, `reloadFiles` receives both journal paths, `JournalDiffService` reports 0 missing recent dates and the ghosts as known exceptions.
2. **Diagnostics export diff**: run `GraphDiagnosticsCollector` before and after the same sequence against the fixture, and diff the two exports. Required differences: `resolve(origin/<branch>)` unresolved -> resolved, `behind` n -> 0, the newest-journals listing gains 2026-10-09/10, and nothing else in the Git section changes unexpectedly. The same diff is repeated on the phone when the device run happens, which is what turns interim into measured.
Interim evidence satisfies "regression-proof in CI", not "fixed on the owner's phone"; M1/M4 stay open in the validation summary until Story 7.1/7.1b pass.

## Size
Large (3–15M CU) — INFERRED band; informational only. Full scope is the default.

**Wall-clock blockers:** (a) owner runs the new diagnostics build on the phone and shares the Git section (confirms/refutes the hypothesis) — owner; (b) device verification of sync fix and console on Android — owner device; (c) PR #397 (cross-graph) / branch `feat/cross-graph-phase2` merge state — decides the base branch; (d) iOS has no Bazel/JGit path (stub) — out of device-test scope.

## Constraints
- Respect CLAUDE.md invariants: writes only through `DatabaseWriteActor` / `@DirectSqlWrite`; reads bounded (no O(graph)); Arrow `Either` at boundaries; `catch Throwable` guards on long-lived scopes (an uncaught OOM in a console coroutine kills the process on Android); no `rememberCoroutineScope` escaping into `remember{}` objects; new SQL tables mirrored in `MigrationRunner`.
- Never print credentials (tokens, passphrases, OAuth) in console output, exports, or logs; remote URLs have userinfo stripped.
- Android: no adb, no real shell guarantee — the "shell" is an in-process command dispatcher (tokenizer plus registered commands; no pipes, globbing or script semantics), plus process exec on desktop only, never an assumption of `/bin/sh`.
- Owner decision (this session): console is **full power — raw SQL writes and shell included**, not read-only.

## Non-functional Requirements
- **Performance SLO**: console commands stream output; any command must be cancellable; query results are row-capped (default 500) with explicit "truncated" marker; console must not block UI thread.
- **Scalability**: graph ≈ 9.4k pages / 1.6k journals; default-branch detection and console `ls-remote` are bounded at 15 s; `fetch` is bounded by `GIT_TRANSPORT_TIMEOUT_SECONDS` (300 s).
- **Security classification**: confidential (personal notes; can mutate DB and run commands).
- **Data residency**: no special requirements; export is local file / OS share sheet only, nothing uploaded.

## Scope
### In Scope
- **Commit the diagnostics changes** already made (Git section, newest-journals listing, oldest+newest samples).
- **Sync bug fix:** `doFetch` (Android + JVM) errors when `<remote>/<remoteBranch>` is unresolved after fetch; surface the message in `SyncState.Error`/UI; detect the remote's actual default branch (`ls-remote --symref HEAD`) at clone/setup and when the configured branch is missing, and offer/auto-apply correction; migration for existing configs with a non-existent `remote_branch`; regression tests (unresolved ref, master-vs-main, stale config) against a real temp JGit repo.
- Verify push path uses the same branch semantics (`doPush`), shallow-clone interactions, and the `wikiSubdir=logseq` filtering of fetched/merged changes, so merged files in `logseq/journals` and `logseq/pages` reach the loader (`reloadFiles`).
- Verify journal indexing: why 1542 on-disk journals are absent from the DB ("pages awaiting full index 9414") and whether background indexing completes; fix if it does not.
- **Embedded dev console** ("Chrome inspector" analogue): a screen/panel, reachable from Settings/Logs (gated by a developer-mode toggle), with a REPL: command history, output scrollback, copy/export-to-file/share, cancel running command. Command families: `sql` (SELECT bounded; writes via the write actor, behind explicit confirm), `git` (status, log, refs, remote, ls-remote, fetch, merge, set-branch, doctor; the list is closed, no raw JGit passthrough), `fs` (ls/stat/cat/head/find within graph roots and app dir), `settings`, `logs`, `graph` (registry, active graph, reload/reindex), `diag` (existing report), plus a `sh` command (desktop only, gated) for process exec. Extensible command registry so a new probe is a one-file addition.
- Console works on Android and Desktop; Web gets whichever subset is feasible (no git/shell).
- **Sync safety and visibility additions** (derived from the Phase 3/4 reviews, confirmed by the owner's decisions; not in the original ask):
  - conflict-marker scan and repository-state guard before every commit and push; mass-change (mass-deletion) guard on commit and push, never liftable by a scheduled run; no force push;
  - first-sync preview and confirmation for each `(remote, branch)` (review and consent in PR-A1, since a branch repair routes into it; with a `Sync without preview` typed-confirm path when the remote check fails) before scheduled sync (PR-A2) may run;
  - staleness indicator (last merged / behind count), launch banner and a low-importance notification for background fetch results;
  - preservation of uncommitted edits before a user-initiated merge abort (snapshot, confirm, restore);
  - console DB safety: pre-write snapshot backup, `graph restore-backup`, Android Auto Backup exclusion of backups/recovery folders, and `DbOnlyRowsGuard` (DB-only journal rows are exported to a recovery file before `graph reload`/`reindex`/`restore-backup`);
  - build stamp (git SHA + build time) in diagnostics so device evidence is tied to a build;
  - mass-change thresholds are **configurable** (defaults `max(20, 5%)` files and 10 journals, INFERRED) and calibrated on the owner's 1.6k-journal graph in a Story 7.1 task;
  - a durable backups list and restore (`backups` command plus a Settings entry) so recovery does not depend on a scrollback block, and plain-language user-facing copy outside the console (jargon allowed only inside it).

### Out of Scope
- Remote/network-exposed console (no listening sockets, no remote debugging server).
- A general-purpose terminal emulator (pty, ANSI full-screen apps).
- Rewriting the git layer to something other than JGit.
- iOS device verification.
- Syncing other graphs (the two SAF `personal-wiki` registry entries) — except documenting how they differ from the cloned graph.

## Rabbit Holes
- "Shell" on Android: `Runtime.exec` runs as the app uid with a toybox PATH; scoped storage hides the SAF tree. Decide in-process interpreter vs process exec early.
- Raw SQL writes bypass `RestrictedDatabaseQueries`/actor invariants and query-invalidation ordering (cf. the DatabaseWriteActor ordering bug in memory); must route through the actor or the DB can desync from markdown files on disk.
- SAF shadow-worktree (`AndroidGitShadowSupport`): fetch/merge operate on a shadow tree; ref resolution and file reload paths differ for SAF-backed graphs.
- Wasm/JS build: new commonMain code must not pull JVM-only APIs.
- Persisting console history may leak queries/secrets; history is in-memory only and there is no opt-in persistence (exports are explicit and previewed).
- Fixing the symptom without confirming the hypothesis — device evidence required first.

## Alternatives Considered
- Keep adding diagnostics sections per question (status quo) — rejected by owner.
- Embed an existing REPL/Termux/adb-over-wifi — external dependency, not self-contained; Termux not guaranteed installed.
- Debug-only remote inspector (Chrome DevTools protocol over a socket) — rejected as network-exposed.
- Android Studio Database Inspector / `adb shell run-as` — requires adb/debuggable build; the stated gap.

## Feasibility Risks
- Hypothesis may be wrong (the cause could be SAF shadow tree, depth/shallow state, or the wikiSubdir path filter); the plan must branch on the Git-section evidence. **Pivot criterion** (full table in plan "Plan branches driven by device evidence"): the main-vs-master hypothesis is accepted only when the stamped export shows stored branch `main`, `resolve(origin/main)` unresolved and ls-remote heads without `main`; any resolved tracking ref moves the plan to the remedy branch named by the OID/path-mode evidence (SAF shadow, `wikiSubdir` filter, shallow/refspec, or `hasRemoteChanges` gating). The time-box is **one device cycle** (install stamped build -> export -> paste); with no evidence returned, Branch 1 is taken, and a failed Story 7.1 after the Branch 1 repair triggers exactly one more cycle whose export selects the remedy branch.
- Full-power SQL/shell on a notes app is a footgun; mitigation = developer-mode gate, confirm on writes, automatic pre-write DB backup/checkpoint, restore and DB-only-rows guard, audit trail in the log.
- Compose text-input/scrollback performance on large outputs.

## Observability Requirements
- Every console command logged (command name, duration, row/line counts, outcome) at INFO; arguments logged with secrets redacted; SQL writes logged at WARN with the statement.
- Fetch with unresolved ref logs WARN with remote name, configured branch, and the list of available remote branches.
- No new alerts (single-user app).

## Risk Control
- Developer mode (Settings toggle, key `developer_mode_enabled`), default off; console entry point hidden when off; a read failure counts as off.
- Write commands require explicit confirm and take a pre-write DB backup; shell/process exec requires a second confirm per session.
- Sync fix: behind no flag (it turns a silent no-op into an error); branch auto-correct is confirmed via UI before it rewrites stored config. Rollback = revert commit; config migration is additive.

## Decisions (owner, 2026-10-10, after Phase 2 research)
- **Scheduled sync = full sync incl. push while the app is open** (fetch → merge → push, auto-commit of local edits per `autoCommit`), falling back to the conflict UI on conflicts and respecting `EditLock`/`GitSyncBusyCounter`. **With the app closed, background work is fetch-only plus a badge/behind-count** (no merge, commit or push; WorkManager 15-minute floor). Amended 2026-10-10 (final): the earlier "full sync in a cold worker" decision is withdrawn.
- **Active graph:** the owner's active graph is the cloned app-private (non-`saf://`) graph; SAF graphs stay fetch-only for scheduled work.
- **Journal cap kept** (~30 eager); older journals load lazily on navigate/search/calendar jump.
- **Base branch:** `feat/cross-graph-phase2`.
- Console power: full (raw SQL writes + shell), per interview.
- Shell: in-process interpreter primary; `sh` optional, gated, desktop-first (research: Android `exec` is uid-limited, no SAF, no git).

## Open Questions
1. What does the device's stored `remote_branch` actually contain? (Git section of next diagnostics export decides hypothesis vs. alternatives.)
2. Is `remoteCommitsMerged=0` also possible because the clone is shallow and `hasRemoteDivergedSinceShallowClone`/unshallow logic interferes?
3. Why are 35 DB journals vs 1578 on disk — is the lazy index drain (`indexRemainingPages`) completing or stuck (log shows `batchDeleteBlocks` 5.2s, `processChunk` 9.5s)?
4. Should the stale `2026-10-07/09/10` DB-only journal rows (created in-app, never on disk) be written out and pushed, or are they ghosts from the other graph?
5. ~~Base branch~~ answered: `feat/cross-graph-phase2` (Decisions).
6. ~~Android shell~~ answered: in-process dispatcher only; `sh` is desktop-only (ADR-005).
