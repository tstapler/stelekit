# Implementation Plan: git-sync-resilience

**Feature**: Retry-with-backoff, connect/read timeouts, shallow-clone-by-default with
checkpointed resume, Android foreground-service survival, and retry/resume Step 5 UX for
SteleKit's JGit-backed git sync (Desktop + Android).
**Date**: 2026-09-23
**Status**: Ready for implementation
**ADRs**: ADR-001 (resume = shallow-clone checkpoint, not byte-level resume), ADR-002 (single
retry owner: `GitOperationSupport`'s internal retry loop, not WorkManager)

> **Phases 1-2 do NOT close the original bug report.** The reporter's literal repro — clone/sync
> silently stopping after the app is backgrounded / the screen is locked mid-transfer — is a
> Phase 3 fix (Android foreground-service survival), not a Phase 1-2 one. Phase 1-2 fix a real,
> independently-shippable bug (wrong error classification, no timeout, no shallow clone) but do
> not touch the backgrounding scenario at all. **Do not close the originating issue/backlog item
> on Phase 1-2 merging.** Close it only once Phase 3's real-device validation gate
> (`validation.md`'s Phase 3 manual release gate) has actually passed — see pre-mortem.md P1 #3.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `GitFailureClass` | Sealed type (`Transient`, `Permanent`, `Cancelled`) classifying a caught JGit/transport exception for retry gating. | Replaces the current 2-bucket `TransportException`/`Exception` split in `runGitTransportOp`, which misroutes a plain `SocketException` to `Transient` today it does not exist — this is the bug fix. |
| `classifyGitFailure(e: Exception)` | Pure function in `GitOperationSupport.kt` walking `e`'s cause chain to produce a `GitFailureClass`, per the taxonomy in `research/stack.md` §4. | Used by both the retry loop (gate on `Transient`) and `runGitTransportOp`'s existing `onAuthFailed` routing (gate on auth-shaped `Permanent`). |
| `runGitTransportOpWithRetry` | New function in `GitOperationSupport.kt` (`jvmCommonMain`), sibling to `runGitTransportOp`. Wraps only the transport call in a `Schedule`-driven retry loop gated by `classifyGitFailure`. | Single retry owner — see ADR-002. Never wraps `commit()`/`stageSubdir()`. |
| `RetryPolicies.gitTransportTransient` | Named `arrow.resilience.Schedule<Throwable, Duration>` constant in `RetryPolicies.kt`: exponential backoff from 1s, jittered, capped at ~16s/attempt (≈5 attempts). | Follows the existing `fileWatchReregistration` idiom exactly. |
| `GIT_TRANSPORT_TIMEOUT_SECONDS` | New constant in `GitOperationSupport.kt` (300s) applied via `TransportCommand.setTimeout()` to every `clone`/`fetch`/`push`. | Distinct from, and much larger than, the existing 15s `TEST_REMOTE_TIMEOUT_SECONDS`. |
| `CloneDepthState` | Sealed interface, not an enum: `sealed interface CloneDepthState { data object None : CloneDepthState; data class Shallow(val depth: Int) : CloneDepthState; data object FullHistory : CloneDepthState }`. Persisted as two SQL columns (`git_config.clone_depth_state`, `git_config.shallow_depth`) but parsed into one in-memory sealed value at the repository boundary. | Per architecture-review.md's Concern (adopted, not deferred): the old `enum class` + independent nullable `shallowDepth: Int?` made "shallow but depth unknown" and "full history but stale depth still set" both representable. Embedding depth only in `Shallow` makes those illegal states unconstructible in Kotlin, and forces exhaustive `when`-expression handling at every call site. `None` = pre-existing repos migrated with no shallow tracking; `Shallow(depth)` = shallow-cloned, deepen pending/available; `FullHistory` = full or successfully deepened (never carries a depth). |
| `DEFAULT_CLONE_DEPTH` | Constant `Int` (50) — the default `CloneCommand.setDepth()`/`FetchCommand.setDepth()` value for a new clone. | Becomes the `depth` argument to `CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH)` on a successful shallow clone; not a separately-tracked field once `CloneDepthState` is a sealed type. |
| `CloneDepthState` SQL parse rule | Fail-closed mapping from the two raw `git_config` columns to the sealed type, applied once at the repository boundary (`SqlDelightGitConfigRepository`'s row mapper / `WorkManagerSyncScheduler.toGitConfig()`). | `clone_depth_state='SHALLOW'` + non-null `shallow_depth` → `Shallow(depth)`; `clone_depth_state='FULL_HISTORY'` or `'NONE'` → `FullHistory`/`None` regardless of what `shallow_depth` contains (a stale non-null value here is logged as unexpected and ignored, never trusted); an unrecognized `clone_depth_state` string, or `'SHALLOW'` with a null `shallow_depth`, falls back to `None` rather than throwing or fabricating a depth. |
| `CloneProgress` | Data class (`phase: String`, `completed: Int`, `totalWork: Int`) — the widened replacement for `onProgress: (String) -> Unit`'s title-only signal. | Wires JGit's `ProgressMonitor.update(completed)`, currently a no-op, through to the UI. |
| `GitTransportRetryState` | Sealed UI-facing state (`Idle`, `Attempting(progress: CloneProgress, foregroundPromoted: Boolean = true)`, `Retrying(attempt, max, CloneProgress?)`, `ResumingDeepen(percent)`, `Exhausted(reason)`, `NonRetryableFailure(reason)`). | Mirrors the existing `SyncState`/`DeviceFlowPollState` sealed-state idiom; drives Step 5 and the foreground-service notification from one source of truth. `Attempting.foregroundPromoted = false` signals a `GitCloneWorker` run continuing after `setForeground()` was denied (Task 3.1.2e) — Step 5 must not imply a background-survival guarantee for that run. |
| `GitCloneWorker` | New Android `CoroutineWorker` that performs a user-initiated clone/fetch/push with `setForeground()` (`dataSync` type). | Enqueued sharing `WorkManagerSyncScheduler`'s unique-work name for free mutual exclusion (Story 5.1.2). |
| `GitCloneWorkerLauncher` | `commonMain` interface abstracting "run a clone/fetch/push as a survivable operation" — Android impl enqueues `GitCloneWorker`; JVM impl calls `GitRepository.clone()` directly (Desktop has no foreground-service equivalent, per requirements' explicit Out of Scope). | Keeps `GitSetupScreenSaveLogic.performCloneAndSave` platform-agnostic. |
| `DomainError.GitError.RetryExhausted` | New `GitError` case (`attempts: Int`, `lastError: GitError`) — distinguishes "gave up after N attempts" from a first-try failure for the UI. | Added per `research/stack.md` §4's suggestion. |
| `DomainError.GitError.ShallowHistoryInsufficient` | New `GitError` case — returned by `merge()` instead of letting JGit silently degrade when the local shallow boundary is newer than the true merge base with a diverged remote. | See Story 2.1.5 / `research/pitfalls.md` §2.3. |
| pre-retry cleanup hook (`beforeRetry`) | A `suspend () -> Unit` lambda parameter on `runGitTransportOpWithRetry`, run before every retry attempt after the first. | Clears a stale `.git/index.lock` (§2.1 of pitfalls) and, for `clone` specifically, deletes a partially-written target directory (Story 2.1.3) before the next attempt. |
| unshallow / deepen | The act of running `FetchCommand.setUnshallow(true)` to widen a shallow clone to full history, transitioning `CloneDepthState.Shallow(depth)` → `CloneDepthState.FullHistory`. | Backend capability only in this plan (Story 2.1.4) — no new settings-screen UI entry point, which requirements.md does not ask for. The `FullHistory` write has no `depth` field to null out — the sealed type makes the old "stale `shallow_depth` left lying around" bug unrepresentable rather than requiring a manual null-out step. |

---

## Pattern Decisions

**Step 0.5 creative pass** (three approaches considered for how retry + resume + foreground
execution fit together):

- **A — Unified single-owner retry in `jvmCommonMain`, checkpoint state as a `git_config` column,
  Android foreground execution via `CoroutineWorker.setForeground()` sharing WorkManager's
  existing unique-work infra.** Strength: reuses every existing seam
  (`GitOperationSupport`/`RetryPolicies`/`WorkManagerSyncScheduler.pauseFor`/`resumeFor`), one
  exception-classification site, independently testable without the foreground service.
  Weakness: requires an explicit, easy-to-miss coordination change (`GitSyncWorker.doWork()`
  must stop calling `Result.retry()`) or two retry layers silently compound.
- **B — `commonMain` `ResilientGitRepository` decorator wrapping `GitRepository`, handling retry
  and foreground orchestration uniformly across all platforms via the public `Either`-returning
  interface.** Strength: one implementation, uniform across present and future `GitRepository`
  implementations (including a hypothetical future iOS one). Weakness: by the time a decorator
  sees a `DomainError.GitError`, JGit's typed exception detail (which `SocketException` vs. which
  auth failure) is already erased — the decorator would have to re-classify from a coarser
  signal. `IosGitRepository` is a stub and out of scope, so the "uniform across platforms"
  argument is moot today and the JGit-specific retry logic would be dead weight there anyway.
  Also adds a new DI-wiring decision at every platform's construction site.
- **C — WorkManager owns all retry/backoff (`setBackoffCriteria`, capped), `GitOperationSupport`
  does zero internal retries.** Strength: single, simple owner by construction — no coordination
  bug possible. Weakness: WorkManager only exists on Android; Desktop would need an entirely
  separate retry-owning mechanism, splitting the one-shared-`jvmCommonMain`-fix architecture the
  research explicitly recommends. It also retries at the wrong granularity: WorkManager
  re-invokes the whole `doWork()`/`sync()`, which re-runs `stageSubdir()`/`commit()` on every
  retry — a real hazard for `EmptyCommitException`/duplicate commits (`research/pitfalls.md`
  §1.3), not just a push-idempotency question.

**Chosen: A**, with the coordination gap from A's own weakness closed explicitly as ADR-002
(single retry owner) rather than left implicit. B and C are the two rejected alternatives
recorded below.

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Retry/backoff orchestration | Strategy (GoF), via named `arrow.resilience.Schedule` constants | `RetryPolicies.kt` (existing idiom) | Hand-rolled deterministic backoff loop (the `WasmSectionSyncService.githubFetch` shape) | No jitter (thundering-herd risk per `research/pitfalls.md` §1.2), and diverges from this codebase's own established `Schedule` convention for no reason |
| Exception classification | Type-driven design: sealed `GitFailureClass` sum type | New in `GitOperationSupport.kt` | Inline cause-chain inspection duplicated at every call site | One classifier reused by both the retry gate and the UI's transient-vs-permanent messaging; duplicated inline checks drift out of sync |
| Retry-owning layer placement | Extend the existing `jvmCommonMain` choke point (`GitOperationSupport.runGitTransportOp` → sibling `runGitTransportOpWithRetry`) | `research/architecture.md` §1a/§3 | `commonMain` `ResilientGitRepository` decorator | See creative-pass Option B rejection above |
| Checkpoint/resume mechanism | Checkpoint-by-depth (Transaction Script–style: shallow clone, then an independent deepen step) | ADR-001 | Byte-level partial-pack resume | Confirmed infeasible — no JGit/native-git/library support exists; real corruption risk in the one prior attempt (`research/build-vs-buy.md` §2) |
| Checkpoint state persistence | Repository pattern (PoEAA) — extend the existing `GitConfigRepository`/`SqlDelightGitConfigRepository`/`GitConfig` | `git_config` table (existing) | New dedicated `clone_checkpoint` table | `git_config` already has the right one-row-per-graph lifecycle (created/deleted with the graph); a new table needs its own `MigrationRunner.all` entry and lifecycle wiring for no added benefit |
| Android foreground execution | `CoroutineWorker` + `setForeground()` on the existing `WorkManagerSyncScheduler` infrastructure | `research/build-vs-buy.md` §3, `research/stack.md` §7 | Hand-rolled bound `Service` (mirroring `AndroidMeasurementForegroundService`) | Duplicates WorkManager's retry/constraint machinery this project also needs; BLE's reason for being a bare `Service` (indefinite connection lifecycle) doesn't apply to a bounded transfer |
| Cross-platform code sharing | Extend `jvmCommonMain` (`GitOperationSupport.kt`) | Existing convention (Android + JVM bytecode share this source set) | Duplicate retry logic per platform | One fix covers both platforms; matches the existing `runGitTransportOp`/`configureTransportAuth` sharing pattern |
| Retry/resume UI state | State pattern: sealed `GitTransportRetryState`, surfaced through the existing `onProgress` channel | Mirrors `SyncState`/`GitHubDeviceFlowClient.DeviceFlowPollState` | Ad-hoc string parsing/formatting of `onProgress` messages | Type-safe, matches an established in-repo precedent, avoids brittle string matching in the UI layer |
| Mutual exclusion (foreground clone/fetch/push vs. periodic fetch) | Reuse WorkManager's own unique-work uniqueness guarantee (share `WorkManagerSyncScheduler`'s unique-work name) | `research/architecture.md` §4 | New per-`repoRoot` `Mutex`/lock primitive | WorkManager already provides this for free; a new mutex only helps the same-process case and duplicates a mechanism the codebase already depends on |
| Retry loop's exhaustion vs. WorkManager scheduling | Single retry owner: internal retry owns the budget; `GitSyncWorker`/`GitCloneWorker` return `Result.failure()` (not `Result.retry()`) on exhaustion | ADR-002 | Both layers retry independently (today's actual, accidental state) | Multiplicative retry-storm risk (`research/pitfalls.md` §1.1) — battery, notification-alive time, and time-to-user-visible-failure all blow up |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `AndroidGitRepository.kt` / `JvmGitRepository.kt` | None — already well-decomposed | **Extend as-is** | Already split into focused collaborators (`AndroidGitShadowSupport`, `AndroidGitAuthConfigurer`, `AndroidGitMergeSupport`, `JvmGitRepositoryAuth`, `JvmGitConflictSupport`); new logic is new call-site wiring plus new collaborators in existing seams (`GitOperationSupport.kt`), not a structural change (`research/architecture.md` §5). |
| `GitOperationSupport.runGitTransportOp`'s 2-bucket exception handling | Misroutes a plain `SocketException` (the reported bug's actual JDK exception) to `onAuthFailed` because it catches on `TransportException` type alone, not cause chain (`research/stack.md` §4) | **Refactor-first, sequenced as Phase 1's first task** — not folded silently into the retry story | This is an independently user-visible, pre-existing bug (today's "Clone failed: Software caused connection abort" report is actually being misrouted as an auth error), and correct classification is a hard prerequisite for the retry gate to work at all — it must land and be tested before any retry logic reads `classifyGitFailure`'s output, not as an incidental side effect of adding retry. |
| `GitSyncWorker.doWork()`'s unconditional `Result.retry()` | Second, uncoordinated retry layer stacking with the new internal retry (`research/pitfalls.md` §1.1) | **Refactor**, sequenced into Phase 1 (Story 1.2.3) immediately after retry lands, and extended in Phase 3 for the new `GitCloneWorker` | Must become `Result.failure()` once the internal budget is exhausted (ADR-002); bundling into the phase that introduces internal retry (rather than a separate, disconnected PR) keeps the single-owner invariant provably true from the moment retry ships. |
| `GitOperationSupport.kt` file size | ~196 lines today; will grow with the classifier, retry wrapper, and pre-retry cleanup hook | **Extend in place**; split into a sibling `GitTransportRetry.kt` (same `jvmCommonMain` source set) only if the file exceeds ~350 lines after Phase 1 | `research/architecture.md`'s own "watch for it, not a current problem" — a premature split adds indirection before there's a real size hotspot; re-evaluate with a line count at the end of Phase 1. |

---

## Migration Plan

- **Migration file**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt` —
  append one new `Migration` entry (Task 2.1.2b) using two `SchemaOp.AddColumn` operations
  (the DSL preferred by this file's own doc comment over a raw `ALTER TABLE` string, and the
  pattern already used by `content_hash`/`is_content_loaded`/`backlink_count`):
  ```kotlin
  Migration(
      name = "git_config_clone_depth_state",
      schemaOps = listOf(
          SchemaOp.AddColumn("git_config", "clone_depth_state", "TEXT NOT NULL DEFAULT 'NONE'"),
          SchemaOp.AddColumn("git_config", "shallow_depth", "INTEGER"),
      ),
  )
  ```
  Also update `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`'s
  `CREATE TABLE IF NOT EXISTS git_config (...)` definition (adds the two columns for a *fresh*
  install — the migration handles existing installs) and `selectGitConfig`/
  `insertOrReplaceGitConfig`'s column lists (Task 2.1.2a). These two raw columns remain the SQL
  storage shape even though `CloneDepthState` is a sealed type in-memory (Task 2.1.2d) — only the
  repository-boundary mapper (Task 2.1.2e) parses them into one `CloneDepthState` value, applying
  the fail-closed rule in the Domain Glossary's `CloneDepthState` SQL parse rule row.
- **Reversibility**: forward-only/irreversible, matching every other migration in this file —
  SQLite's `ALTER TABLE ADD COLUMN` has no companion `DROP COLUMN` support in the SQLite versions
  this app targets, and `MigrationRunner`'s own rules only support additive, idempotent
  operations. A reverted app binary simply ignores the two extra columns (the older-generated
  SQLDelight code never selects them) — no data loss on rollback.
- **Zero-downtime strategy**: purely additive column with a `DEFAULT`, safe for every existing
  row. Existing `git_config` rows read back as `clone_depth_state = 'NONE'` (their repos were
  cloned before this project shipped, so no shallow-clone tracking exists for them) until their
  next successful clone/fetch sets a real state.
- **Rollback procedure**: standard `git revert` of the PR (per Risk Control below); no data
  migration to unwind since the column addition is additive and harmlessly ignorable by an older
  binary.

## Observability Plan

- **Logs**: extend the existing `Logger("AndroidGitRepository")`/`Logger("JvmGitRepository")`
  instances (no new logger). Log, at `info`, every retry attempt (attempt number / max, the
  `GitFailureClass`, the backoff delay before the next attempt — message text run through the
  existing `redactUrlUserinfo` before logging, per `GitOperationSupport.kt`'s established
  convention) and every checkpoint transition (`CloneDepthState` `None`→`Shallow(depth)`,
  `Shallow(depth)`→`FullHistory`, and deepen failures). This directly serves requirements.md's
  stated Observability Requirement:
  "a user-reported sync failure can be diagnosed from local logs."
- **Metrics**: none — no telemetry infrastructure exists in this client app and this project
  does not introduce one, per requirements.md's explicit scope decision.
- **Alerts**: no new alerts required (client app, no oncall/alerting infrastructure).

## Risk Control

- **Feature flag**: not gated — no feature-flag infrastructure exists in this codebase
  (confirmed by grep in requirements.md) and this project does not introduce one.
- **Rollback procedure**: standard revert via PR close + revert commit, followed by an expedited
  patch release per `CLAUDE.md`'s Release Process, per requirements.md's Risk Control section.
- **Staged rollout**: full rollout on merge (release-please pipeline). Risk is mitigated primarily
  through the regression coverage in Phase 1/Phase 6 (the fast-fail-on-permanent-failure and
  common-case-unchanged tests), not staged rollout, which this app has no mechanism for.

## Unresolved Questions

- [ ] Exact `DEFAULT_CLONE_DEPTH` value (this plan defaults to 50) — balances "small, cheap retry
  unit" against "avoid tripping the shallow-history/merge-base interaction (Story 2.1.5) on
  frequently-diverging repos" — blocks Story 2.1.1 — owner: Tyler (product tuning call); default
  to 50 and revisit after Story 2.1.5's regression test if it proves too aggressive.
- [ ] Whether `GitSyncWorker`'s slow path (process-was-killed, standalone `AndroidGitRepository`)
  needs the full retry-taxonomy wiring in v1, or can rely solely on the next scheduled periodic
  run — blocks Story 1.2.2d — owner: implementer; default to yes (reuse `runGitTransportOpWithRetry`
  uniformly, since it's already shared via `jvmCommonMain` — no extra cost to include it).
- [ ] Whether to gate `GitCloneWorker`'s foreground-service promotion behind a duration heuristic
  (avoid a notification flash for an instant small-graph clone) or accept an always-on
  notification — blocks Story 3.1.2 — owner: Tyler (product/UX call, `research/architecture.md`
  §6.5); default to always-on for v1 (simpler, matches "don't look hung" priority from
  `research/ux.md` §6) unless Tyler prefers the heuristic.
- [ ] Whether `merge()` should auto-unshallow before merging when history has diverged past the
  shallow boundary, or fail closed with a distinct error — blocks Story 2.1.5 — owner:
  implementer; default to fail-closed (`DomainError.GitError.ShallowHistoryInsufficient`), the
  simpler and safer option, pending product input on whether an automatic-unshallow UX is wanted
  later.
- [ ] User-Initiated Data Transfer (API 34+) fast-follow — explicitly out of v1 scope per
  `research/build-vs-buy.md` §3 given `minSdk = 26` — confirm this stays deferred; owner: Tyler,
  default: stays out of this project.

## Dependency Visualization

```
Phase 1: Transport Resilience Foundation (independently shippable — fixes the reported bug)
  Epic 1.1 (exception taxonomy + timeout)
      │
      ▼
  Epic 1.2 (retry-with-backoff, single-owner coordination)
      │
      ▼
  Epic 1.3 (regression coverage: fast-fail on permanent failure)
      │
      ├─────────────────────────────┬───────────────────────────────┐
      ▼                             ▼                                ▼
Phase 2: Shallow clone +     Phase 3: Android foreground      Phase 6: Fault-injection
checkpoint/resume            execution (needs Epic 1.2's       harness (Story 6.1.1 can
  Epic 2.1                   retry wrapper + classifier to     start once Epic 1.1/1.2
      │                      call inside GitCloneWorker)       land; 6.1.2/6.1.4 need
      │                            │                           Phase 2/3's real call sites)
      ▼                            ▼
  (Story 2.1.1–2.1.3 must      Epic 3.1 (GitCloneWorker,
   land before Story 2.1.4/    manifest, launcher)
   2.1.5, which depend on           │
   real shallow-clone state)        ▼
      │                       (Story 3.1.5 depends on
      │                        Story 1.2.3's single-owner
      │                        pattern, applied to the
      │                        new worker)
      │                             │
      └──────────────┬──────────────┘
                      ▼
         Phase 4: Wizard Step 5 UX
           Epic 4.1 (needs Phase 2's CloneDepthState for
           "resume/deepen" copy, Phase 3's GitCloneWorker/
           notification for cancel + foreground wiring,
           and Phase 1's GitTransportRetryState inputs)
                      │
                      ▼
         Phase 5: GitWorktreeLocks / periodic-fetch race fix
           Epic 5.1 (needs Phase 3's GitCloneWorker to exist
           before it can be enqueued under the shared
           unique-work name)
                      │
                      ▼
         Phase 6 (continued): full regression suite
           (Stories 6.1.2–6.1.4 exercise the complete stack:
           classifier + retry + checkpoint + foreground +
           lock coordination all together)
```

---

## Phase 1: Transport Resilience Foundation

### Epic 1.1: Exception Taxonomy Fix + Connect/Read Timeout

**Goal**: Fix the pre-existing misclassification bug (plain `SocketException` routed to
`AuthFailed`) and give every transport operation an explicit timeout, so a stalled connection
fails fast enough to reach the new retry logic instead of hanging until the OS tears the socket
down. This alone fixes the reported bug's *symptom* (a wrong error message) even before retry
exists.

#### Story 1.1.1: Classify transport failures by cause chain, not exception type alone
**As a** SteleKit maintainer, **I want** `GitOperationSupport` to distinguish a transient network
failure from an auth failure by inspecting the exception's cause chain, **so that** a
`SocketException` is never reported to the user as an authentication problem, and the retry logic
(Story 1.2.2) has a reliable signal to gate on.

**Acceptance Criteria**:
- A JGit `TransportException` whose cause chain (recursively) contains `java.net.SocketException`,
  `java.net.SocketTimeoutException`, `java.net.UnknownHostException`, or `java.io.EOFException`
  classifies as `GitFailureClass.Transient`.
  - *Given* a `TransportException` constructed with cause `SocketException("Software caused
    connection abort")`, *When* `classifyGitFailure(e)` is called, *Then* it returns
    `GitFailureClass.Transient`.
- A `TransportException` whose cause chain bottoms out in `NoRemoteRepositoryException`, or whose
  message contains an HTTP `401`/`403` marker, classifies as `GitFailureClass.Permanent`.
  - *Given* a `TransportException` with cause `NoRemoteRepositoryException("repo-not-found")`,
    *When* `classifyGitFailure(e)` is called, *Then* it returns `GitFailureClass.Permanent`.
- `org.eclipse.jgit.api.errors.CanceledException` classifies as `GitFailureClass.Cancelled` and
  is never retried.
  - *Given* `ProgressMonitor.isCancelled()` returns `true` mid-clone and JGit throws
    `CanceledException`, *When* `classifyGitFailure(e)` is called, *Then* it returns
    `GitFailureClass.Cancelled`.
- `runGitTransportOp` routes only `Permanent` (auth-shaped) failures to `onAuthFailed`; a
  `Transient`-classified `TransportException` routes to `onFailed`, not `onAuthFailed`.
  - *Given* today's bug scenario (a `TransportException` wrapping a raw `SocketException` during
    `clone()`), *When* `runGitTransportOp` catches it, *Then* the resulting `DomainError.GitError`
    is `CloneFailed`, not `AuthFailed`.

**Files**: `kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/git/GitOperationSupport.kt`,
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitOperationSupportClassifierTest.kt` (new)

##### Task 1.1.1a: Define `GitFailureClass` sealed type (~3 min)
- Add `sealed interface GitFailureClass { data object Transient : GitFailureClass; data object
  Permanent : GitFailureClass; data object Cancelled : GitFailureClass }` to
  `GitOperationSupport.kt`, placed above `runGitTransportOp`.
- Files: `GitOperationSupport.kt`

##### Task 1.1.1b: Implement `classifyGitFailure` (~5 min)
- Implement `fun classifyGitFailure(e: Exception): GitFailureClass` walking `e`'s `cause` chain
  (bounded loop, max depth ~10 to avoid a cyclic-cause edge case) per the taxonomy table in
  `research/stack.md` §4: `SocketException`/`SocketTimeoutException`/`UnknownHostException`/
  `EOFException` anywhere in the chain → `Transient`; `NoRemoteRepositoryException` in the chain,
  or `e.message` containing `"401"`/`"403"`/`"not authorized"` (case-insensitive) → `Permanent`;
  `CanceledException` → `Cancelled`; everything else (including `InvalidRemoteException`,
  `RefNotAdvertisedException`) → `Permanent` (fail-closed: unknown shapes are treated as
  non-retryable, per `research/pitfalls.md` §5.1's "does a permanent failure still fail fast" as
  the sharpest regression risk).
- Files: `GitOperationSupport.kt`

##### Task 1.1.1c: Wire `classifyGitFailure` into `runGitTransportOp` (~4 min)
- Replace the current `catch (e: TransportException) { onAuthFailed(...) }` branch: catch
  `TransportException`, compute `classifyGitFailure(e)`, route to `onAuthFailed` only when
  `Permanent`; otherwise fall through to the same `onFailed(redactedTransportException(e))` path
  the generic `catch (e: Exception)` branch already uses. Log the classification at `info` via
  the existing `Logger` pattern (no new logger instance — pass a logger param or leave logging to
  callers for now if `GitOperationSupport.kt` doesn't already own one; verify against current
  file content before choosing).
- Files: `GitOperationSupport.kt`

##### Task 1.1.1d: Table-driven classifier unit tests (~5 min)
- New `businessTest` file exercising `classifyGitFailure` against constructed exception chains for
  every row of the taxonomy table in `research/stack.md` §4 (transient network causes, permanent
  auth/404 causes, `CanceledException`, and an unknown/unrecognized exception type to confirm the
  fail-closed default).
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitOperationSupportClassifierTest.kt`

#### Story 1.1.2: Add connect/read timeout to clone/fetch/push
**As a** SteleKit user on a degraded connection, **I want** clone/fetch/push to fail within a
bounded time instead of hanging until the OS kills the socket, **so that** the app can recover
via retry instead of appearing frozen.

**Acceptance Criteria**:
- Every `CloneCommand`/`FetchCommand`/`PushCommand` built by `AndroidGitRepository`/
  `JvmGitRepository` calls `.setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)` before `.call()`.
  - *Given* a clone against an unroutable host (e.g. a non-routable IP with no listener), *When*
    `AndroidGitRepository.clone()` runs, *Then* it fails with a `TransportException` within
    `GIT_TRANSPORT_TIMEOUT_SECONDS` (300s) rather than hanging indefinitely.
- `GIT_TRANSPORT_TIMEOUT_SECONDS` is defined once in `GitOperationSupport.kt`, not duplicated per
  platform.

**Files**: `GitOperationSupport.kt`, `AndroidGitRepository.kt`, `JvmGitRepository.kt`

##### Task 1.1.2a: Add the shared timeout constant (~2 min)
- Add `const val GIT_TRANSPORT_TIMEOUT_SECONDS = 300` to `GitOperationSupport.kt`, next to the
  existing `TEST_REMOTE_TIMEOUT_SECONDS`, with a doc comment cross-referencing why it's larger
  (a shallow clone still needs headroom on a slow-but-healthy connection — `research/architecture.md`
  §6.1).
- Files: `GitOperationSupport.kt`

##### Task 1.1.2b: Apply timeout in `AndroidGitRepository.clone()`/`doFetch()`/`doPush()` (~5 min)
- Add `.setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)` to the `Git.cloneRepository()` builder chain
  (`AndroidGitRepository.kt:98-109`), to `git.fetch()` in `doFetch()` (`:139-142`), and to
  `git.push()` in `doPush()` (`:276-279`).
- Files: `AndroidGitRepository.kt`

##### Task 1.1.2c: Apply timeout in `JvmGitRepository.clone()`/`doFetch()`/`doPush()` (~5 min)
- Same three call sites in `JvmGitRepository.kt` (`:87-97` clone, `:124-127` doFetch, `:237-240`
  doPush).
- Files: `JvmGitRepository.kt`

##### Task 1.1.2d: Regression test — bounded failure time against an unroutable host (~5 min)
- Add a `JvmGitRepositoryTest` case asserting `clone()` against a non-routable address (e.g.
  `10.255.255.1` or a documented TEST-NET address) completes (fails) within a generous bound well
  under what the *previous*, timeout-less behavior would have taken — proves the timeout is
  actually wired, not just present in source. Mark `@Tag` or similar if this repo has a slow-test
  convention (check existing test file for the pattern first).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/JvmGitRepositoryTest.kt`

---

### Epic 1.2: Retry-with-Backoff for Transport Operations

**Goal**: Wrap clone/fetch/push in a bounded, jittered exponential-backoff retry that only
retries `GitFailureClass.Transient` failures, established as the single retry owner (ADR-002).

#### Story 1.2.1: Add `RetryPolicies.gitTransportTransient`
**As a** SteleKit maintainer, **I want** a named, jittered exponential-backoff `Schedule` for git
transport retries, **so that** many graphs recovering from the same network blip don't retry in
lockstep (thundering herd).

**Acceptance Criteria**:
- `RetryPolicies.gitTransportTransient` is a `Schedule<Throwable, Duration>` using
  `Schedule.exponential(1.seconds).jittered().doUntil { _, d -> d > 16.seconds }` (≈5 attempts:
  ~1s, 2s, 4s, 8s, 16s, each jittered).
  - *Given* `RetryPolicies.gitTransportTransient` is evaluated via `Schedule.retry`, *When* every
    attempt fails, *Then* the schedule terminates after the 5th attempt (delay exceeds 16s) rather
    than retrying forever.
- A `testImmediate`-style zero-delay variant exists for unit tests, following the existing
  `RetryPolicies.testImmediate` precedent.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/resilience/RetryPolicies.kt`

##### Task 1.2.1a: Add `gitTransportTransient` (~3 min)
- Add the `Schedule` constant per the AC above, doc-commented with the jitter rationale (mirrors
  `fileWatchReregistration`'s style).
- Files: `RetryPolicies.kt`

##### Task 1.2.1b: Add `gitTransportTransientImmediate` test variant (~2 min)
- Add a zero-delay `Schedule.recurs<Throwable>(5)` constant for fast test execution, following
  `testImmediate`'s existing naming/placement convention.
- Files: `RetryPolicies.kt`

#### Story 1.2.2: Implement and wire `runGitTransportOpWithRetry`
**As a** SteleKit user on a flaky connection, **I want** clone/fetch/push to automatically retry
a transient failure with backoff, **so that** a single dropped packet doesn't fail the whole
operation.

**Acceptance Criteria**:
- Given a `clone()` call whose first 2 attempts throw a `Transient`-classified `SocketException`
  and the 3rd attempt succeeds, retrying via `RetryPolicies.gitTransportTransient` returns
  `Right(Unit)` after 3 total attempts, with credential resolution (`preResolvedToken`) happening
  exactly once, not once per attempt.
  - *Given* a fake transport op that throws `TransportException(cause=SocketException(...))`
    twice then succeeds, *When* `AndroidGitRepository.clone()` runs against it, *Then* the result
    is `Right(Unit)`, the op was invoked 3 times, and `preResolvedToken` was computed once before
    the loop began.
- A `Permanent`-classified failure is never retried — the operation fails on the first attempt.
  - *Given* a fake transport op that always throws `TransportException(cause=
    NoRemoteRepositoryException(...))`, *When* `clone()` runs, *Then* the result is
    `Left(CloneFailed)` (or `AuthFailed` if auth-shaped) after exactly 1 attempt.
- A `Cancelled`-classified failure propagates as cancellation and is never retried.
- After the retry budget is exhausted on an all-`Transient` sequence, the result is
  `Left(DomainError.GitError.RetryExhausted(attempts = 5, lastError = ...))`.
- Before every retry attempt after the first, the `beforeRetry` hook runs (Story 2.1.3 supplies
  clone's directory-cleanup implementation; Task 1.2.2b supplies the shared stale-lock cleanup).

**Files**: `GitOperationSupport.kt`, `DomainError.kt`,
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitTransportRetryTest.kt` (new)

##### Task 1.2.2a: Implement `runGitTransportOpWithRetry` (~5 min)
- New inline function in `GitOperationSupport.kt`:
  `runGitTransportOpWithRetry(schedule: Schedule<Throwable, Duration>, onAttempt: (attempt: Int,
  failure: GitFailureClass?) -> Unit, beforeRetry: suspend () -> Unit, onAuthFailed: (Exception) ->
  DomainError.GitError, onFailed: (Exception) -> DomainError.GitError, onExhausted: (attempts:
  Int, last: DomainError.GitError) -> DomainError.GitError, op: () -> Either<DomainError.GitError,
  T>)`. Internally: loop up to the schedule's attempt count, classify each failure via
  `classifyGitFailure`, retry only on `Transient` (calling `beforeRetry()` and `delay()` per the
  schedule between attempts), re-throw/return immediately on `Permanent`/`Cancelled`, and return
  `onExhausted(...)`'s result if all attempts are `Transient` but still fail.
- Files: `GitOperationSupport.kt`

##### Task 1.2.2b: Add `DomainError.GitError.RetryExhausted` (~2 min)
- Add `data class RetryExhausted(val attempts: Int, val lastError: GitError) : GitError` to
  `DomainError.kt`'s `GitError` sealed interface, plus entries in the two existing `when`
  exhaustive-message extension functions (`errorMessage`-style and `toSyncErrorMessage`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 1.2.2c: Pre-retry stale-lock cleanup hook (~5 min)
- Implement a shared `beforeRetry` default (a lambda calling the existing
  `deleteStaleLockFile`-equivalent logic) — but per `research/pitfalls.md` §2.1's warning, do
  **not** reuse the 60-second-age threshold unmodified for a lock this same retry loop's own
  just-failed attempt might still be releasing; instead, only attempt cleanup if the lock file's
  mtime predates the retry loop's own start time (passed in), not "60s old" in isolation. Wire
  this per-platform (Android/JVM each pass their own `deleteStaleLockFile` as the `beforeRetry`
  body).
- Files: `AndroidGitRepository.kt`, `JvmGitRepository.kt`

##### Task 1.2.2d: Wire `AndroidGitRepository.clone()`/`fetch()`/`push()` to the retry wrapper (~5 min)
- Replace `runGitTransportOp(...)` with `runGitTransportOpWithRetry(RetryPolicies.gitTransportTransient,
  ...)` at all three call sites, preserving the existing "resolve credentials once before entering
  JGit's synchronous territory" ordering (`preResolvedToken` computed outside the retried closure —
  `research/architecture.md` §6.6).
- Files: `AndroidGitRepository.kt`

##### Task 1.2.2e: Wire `JvmGitRepository.clone()`/`fetch()`/`push()` identically (~5 min)
- Same three call sites in `JvmGitRepository.kt`.
- Files: `JvmGitRepository.kt`

##### Task 1.2.2f: Unit tests for retry-then-succeed / retry-exhausted / permanent-fails-fast (~5 min)
- New `businessTest` using `RetryPolicies.gitTransportTransientImmediate` (zero-delay) and a fake
  `op` lambda that throws a configured sequence of exceptions, asserting the ACs in Story 1.2.2.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitTransportRetryTest.kt`

#### Story 1.2.3: Establish `GitSyncWorker` as a retry consumer, not a second retry owner
**As a** SteleKit maintainer, **I want** `GitSyncWorker.doWork()` to stop scheduling its own
WorkManager-level retry once `fetchOnly()`/`fetch()` already retried internally, **so that** a
transient failure doesn't compound into a multiplicative retry storm (ADR-002).

**Acceptance Criteria**:
- Given `GitSyncService.fetchOnly()` (fast path) returns a `Left` after Story 1.2.2's internal
  retry budget is exhausted, `GitSyncWorker.doWork()` returns `Result.failure()`, not
  `Result.retry()`.
  - *Given* a registered `GitSyncService` whose `fetchOnly("graph-1")` returns
    `Left(RetryExhausted(5, ...))`, *When* `GitSyncWorker.doWork()` runs for `graph-1`, *Then* it
    returns `Result.failure()`.
- The slow path (standalone `AndroidGitRepository.fetch()`, process-was-killed case) has the same
  behavior change, since it now benefits from Story 1.2.2e's internal retry too.

**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt`

##### Task 1.2.3a: Change fast-path catch to `Result.failure()` (~2 min)
- `GitSyncWorker.doWork()`'s fast-path `catch (_: Exception) { Result.retry() }`
  (`WorkManagerSyncScheduler.kt:131`) → `Result.failure()`, with a comment citing ADR-002.
- Files: `WorkManagerSyncScheduler.kt`

##### Task 1.2.3b: Change slow-path catch to `Result.failure()` (~2 min)
- Same change at the slow-path's `catch (_: Exception) { Result.retry() }` (`:166`).
- Files: `WorkManagerSyncScheduler.kt`

---

### Epic 1.3: Regression Coverage for the Common Case

**Goal**: Prove the sharpest regression risk this project introduces — a permanent failure must
still fail fast, not spend the whole retry budget — and that stable-network/small-repo behavior
is unaffected.

#### Story 1.3.1: Fast-fail regression for permanent failures
**As a** SteleKit maintainer, **I want** an automated test proving a bad-credentials or
repo-not-found clone fails on the first attempt, **so that** a future change to the classifier
can't silently regress into retrying unretryable errors.

**Acceptance Criteria**:
- A clone against a URL that JGit reports as `NoRemoteRepositoryException` fails after exactly one
  attempt (verified by counting invocations of the underlying op).
  - *Given* a fake `op` that always throws `TransportException(cause=NoRemoteRepositoryException("not
    found"))`, *When* `runGitTransportOpWithRetry` runs it with `RetryPolicies.gitTransportTransient`,
    *Then* the op is invoked exactly once and the result is `Left`.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitTransportRetryTest.kt`

##### Task 1.3.1a: Add the fast-fail assertion (~3 min)
- Extend the Story 1.2.2f test file with the permanent-failure invocation-count assertion.
- Files: `GitTransportRetryTest.kt`

#### Story 1.3.2: Common-case unchanged — no retry-loop overhead on success
**As a** SteleKit maintainer, **I want** a test proving a first-attempt-success clone makes
exactly one op invocation, **so that** the retry wrapper never adds latency/overhead to the
stable-network path.

**Acceptance Criteria**:
- *Given* a fake `op` that succeeds on the first call, *When* `runGitTransportOpWithRetry` runs it,
  *Then* the op is invoked exactly once and no `delay()`/backoff occurs.

**Files**: `GitTransportRetryTest.kt`

##### Task 1.3.2a: Add the single-invocation assertion (~2 min)
- Files: `GitTransportRetryTest.kt`

---

## Phase 2: Shallow Clone + Checkpoint/Resume

### Epic 2.1: Depth-Limited Clone as Default, with Checkpoint Persistence

**Goal**: Default new clones to shallow, persist that checkpoint durably, restructure `clone()`
so a retry can't hit JGit's non-empty-directory guard, and handle the downstream consequences of
shallow history (merge-base limitation, commit-count UI).

#### Story 2.1.1: Depth-limit new clones by default
**As a** SteleKit user cloning a large graph, **I want** the initial clone to be shallow by
default, **so that** the retryable unit (and initial transfer time) is bounded instead of scaling
with the repo's full history.

**Acceptance Criteria**:
- A new `clone()` call sets `CloneCommand.setDepth(DEFAULT_CLONE_DEPTH)` (50).
  - *Given* `AndroidGitRepository.clone("https://example.com/repo.git", "/local/path",
    GitAuth.None, {})` is called, *When* the clone succeeds, *Then*
    `git.repository.objectDatabase.shallowCommits` is non-empty (the local repo is shallow).

**Files**: `AndroidGitRepository.kt`, `JvmGitRepository.kt`, `GitOperationSupport.kt`

##### Task 2.1.1a: Add `DEFAULT_CLONE_DEPTH` constant (~2 min)
- `const val DEFAULT_CLONE_DEPTH = 50` in `GitOperationSupport.kt`, doc-commented per ADR-001's
  rationale (bounds retry cost, not a literal partial-pack resume).
- Files: `GitOperationSupport.kt`

##### Task 2.1.1b: Apply `.setDepth()` in `AndroidGitRepository.clone()` (~3 min)
- Add `.setDepth(DEFAULT_CLONE_DEPTH)` to the `Git.cloneRepository()` builder
  (`AndroidGitRepository.kt:98-109`).
- Files: `AndroidGitRepository.kt`

##### Task 2.1.1c: Apply `.setDepth()` in `JvmGitRepository.clone()` (~3 min)
- Same at `JvmGitRepository.kt:87-97`.
- Files: `JvmGitRepository.kt`

##### Task 2.1.1d: Test — fresh clone reports shallow commits (~4 min)
- `JvmGitRepositoryTest` case asserting `Repository.getObjectDatabase().getShallowCommits()` is
  non-empty after a successful `clone()` against a local test-fixture repo with >1 commit of
  history.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/JvmGitRepositoryTest.kt`

#### Story 2.1.2: Persist `CloneDepthState` on `git_config`
**As a** SteleKit maintainer, **I want** the shallow-vs-full state of a graph's clone persisted
durably, **so that** it survives process death and a resumed app session knows whether a deepen
is pending.

**Acceptance Criteria**:
- After a successful shallow clone, `git_config.clone_depth_state = 'SHALLOW'` and
  `shallow_depth = 50` for that `graph_id` at the SQL storage layer.
  - *Given* a successful `clone()` for `graphId = "graph-1"` with `DEFAULT_CLONE_DEPTH = 50`,
    *When* the post-clone config save runs, *Then* `SqlDelightGitConfigRepository.getConfig("graph-1")`
    returns a `GitConfig` with `cloneDepthState = CloneDepthState.Shallow(depth = 50)`.
- An existing pre-migration `git_config` row (cloned before this project shipped) reads back as
  `cloneDepthState = CloneDepthState.None` with no crash or data loss.
- A row where `clone_depth_state='SHALLOW'` but `shallow_depth` is null (a corrupt/hand-edited
  row — should never happen via this app's own writes, but the parser must not trust that), or
  where `clone_depth_state` is an unrecognized string, parses to `CloneDepthState.None` rather
  than throwing or fabricating a depth (the Domain Glossary's `CloneDepthState` SQL parse rule).

**Files**: `SteleDatabase.sq`, `MigrationRunner.kt`, `GitConfig.kt`, the `GitConfigRepository`
implementation(s), `WorkManagerSyncScheduler.kt` (its private `toGitConfig()` mapper)

##### Task 2.1.2a: Add columns to `SteleDatabase.sq`'s `git_config` (~4 min)
- Add `clone_depth_state TEXT NOT NULL DEFAULT 'NONE'` and `shallow_depth INTEGER` to the
  `CREATE TABLE IF NOT EXISTS git_config (...)` block (`SteleDatabase.sq:~904-918`), and add both
  columns to `selectGitConfig`'s implicit `SELECT *` (no change needed there) and to
  `insertOrReplaceGitConfig`'s explicit column/value lists.
- Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

##### Task 2.1.2b: Add the `MigrationRunner` migration (~3 min)
- Append the `git_config_clone_depth_state` `Migration` entry shown in the Migration Plan section
  above, to `MigrationRunner.all`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`

##### Task 2.1.2c: Regenerate SQLDelight sources (~3 min)
- Per `CLAUDE.md`'s mandatory step: run `./gradlew :kmp:generateCommonMainSteleDatabase`, `rsync`
  the output into `kmp/src/generated/sqldelight/`, and commit the regenerated files.
- Files: `kmp/src/generated/sqldelight/` (generated, committed per repo convention)

##### Task 2.1.2d: Add sealed `CloneDepthState` type and `GitConfig` field (~4 min)
- Add `sealed interface CloneDepthState { data object None : CloneDepthState; data class
  Shallow(val depth: Int) : CloneDepthState; data object FullHistory : CloneDepthState }` to
  `GitConfig.kt` (adopting architecture-review.md's Concern remediation — **not** an `enum class`),
  and add a single `cloneDepthState: CloneDepthState = CloneDepthState.None` field to the
  `GitConfig` data class. There is no separate `shallowDepth: Int?` field on `GitConfig` — depth
  lives only inside `CloneDepthState.Shallow`, so "shallow with unknown depth" and "full history
  with a stale depth" are both unconstructible.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitConfig.kt`

##### Task 2.1.2e: Update repository row↔model mapping with the fail-closed parse rule (~5 min)
- Update `SqlDelightGitConfigRepository`'s row-to-`GitConfig` mapper and `GitConfig`-to-row
  save/insert call to read/write the two raw `clone_depth_state`/`shallow_depth` columns, parsing
  them into one `CloneDepthState` per the Domain Glossary's `CloneDepthState` SQL parse rule row:
  `'SHALLOW'` + non-null `shallow_depth` → `Shallow(depth)`; `'FULL_HISTORY'`/`'NONE'` →
  `FullHistory`/`None` (ignoring any stale `shallow_depth` value present, never trusting it); any
  other combination (unrecognized string, or `'SHALLOW'` with a null depth) → `None`, logged as
  unexpected rather than thrown. On write, destructure `CloneDepthState` back into the two raw
  columns (`FullHistory`/`None` write `shallow_depth = null`, closing the architecture review's
  "explicitly null out `shallow_depth` on the `FullHistory` write" note by construction — the
  sealed type has no depth to carry for those variants in the first place). Also update that same
  private `toGitConfig()` extension in `WorkManagerSyncScheduler.kt` (used by `GitSyncWorker`'s
  slow path), which currently omits several `GitConfig` fields already and must not omit this one.
- Files: the `SqlDelightGitConfigRepository` implementation file (locate via `Glob` for
  `*SqlDelightGitConfigRepository*`), `WorkManagerSyncScheduler.kt`

##### Task 2.1.2f: Write `CloneDepthState` after a successful clone (~4 min)
- After `syncShadowAfterInitOrClone`/the JVM equivalent succeeds inside `clone()`, save
  `cloneDepthState = CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH)` via the config repository (the
  exact save call site depends on where `GitConfig` is first persisted for a new clone — trace
  `performCloneAndSave`'s `resolveAndSaveConfig` call and set the field there, since `GitConfig`
  doesn't exist yet at the moment `clone()` itself runs for a brand-new graph).
- Files: `GitSetupScreenSaveLogic.kt` (`resolveAndSaveConfig`/`buildConfig`)

##### Task 2.1.2g: Migration + mapping regression test (~5 min)
- `businessTest` asserting: (1) a fresh `git_config` row for a graph with no clone yet defaults to
  `CloneDepthState.None`; (2) after `MigrationRunner.applyAll()` runs against a pre-migration
  in-memory DB with an existing `git_config` row (no `clone_depth_state` column), the row is read
  back with `cloneDepthState = None` and no exception; (3) the fail-closed parse rule itself: a
  hand-constructed row with `clone_depth_state='SHALLOW'`, `shallow_depth=NULL` parses to `None`,
  not a crash or a fabricated depth, and a row with `clone_depth_state='FULL_HISTORY'`,
  `shallow_depth=50` (a stale leftover value) parses to `FullHistory` — the stale depth is
  ignored, not resurrected.
- Files: new or existing `MigrationRunner`-adjacent test file (check for
  `MigrationRunnerSchemaSyncTest` and add alongside it, or a new
  `GitConfigCloneDepthMigrationTest.kt`)

#### Story 2.1.3: Make clone retryable — directory cleanup before each retry attempt
**As a** SteleKit user retrying an interrupted clone, **I want** the retry to not fail
immediately with a "directory already exists" error, **so that** the retry-with-backoff added in
Phase 1 actually reaches the network again instead of failing deterministically on attempt 2.

**Acceptance Criteria**:
- Given the first clone attempt's fetch succeeds but the process is interrupted before
  `syncShadowAfterInitOrClone` completes (leaving a partially-populated, non-empty target
  directory), a retried `clone()` attempt does not throw JGit's "already exists and is not an
  empty directory" error.
  - *Given* a target directory containing a partial `.git/` from a simulated interrupted first
    attempt, *When* `runGitTransportOpWithRetry`'s `beforeRetry` hook runs ahead of the 2nd clone
    attempt, *Then* the target directory is empty (or removed) before `Git.cloneRepository().call()`
    is invoked again, and the 2nd attempt succeeds.
- Manual cancel (Story 4.1.4) does **not** trigger this cleanup — only an automatic retry does.

**Files**: `AndroidGitRepository.kt`, `JvmGitRepository.kt`

##### Task 2.1.3a: Implement clone-specific `beforeRetry` cleanup (JVM) (~5 min)
- In `JvmGitRepository.clone()`, pass a `beforeRetry` lambda to `runGitTransportOpWithRetry` that
  recursively deletes the contents of `File(localPath)` (not the directory itself, to preserve any
  externally-held file handles/permissions) if it exists and is non-empty, before the next
  attempt. Per ADR-001, this is intentionally "delete and re-transfer the bounded shallow pack,"
  not partial-pack splicing.
- Files: `JvmGitRepository.kt`

##### Task 2.1.3b: Implement clone-specific `beforeRetry` cleanup (Android, shadow-aware) (~5 min)
- Same in `AndroidGitRepository.clone()`, operating on `shadow.resolveForJGit(localPath)`.
- Files: `AndroidGitRepository.kt`

##### Task 2.1.3c: Test — retry after a simulated interrupted clone succeeds (~5 min)
- `JvmGitRepositoryTest`/businessTest: pre-populate a target directory with a partial `.git`
  (e.g. via a first `clone()` call whose `op` is faked to throw immediately after JGit would have
  written files — or more simply, seed the directory with dummy files before calling `clone()`
  with a retry-triggering fake), assert the retried clone succeeds rather than throwing the
  directory-exists error.
- Files: `JvmGitRepositoryTest.kt` or `GitTransportRetryTest.kt`

#### Story 2.1.4: Deepen (unshallow) capability
**As a** SteleKit maintainer, **I want** a backend capability to widen a shallow clone to full
history, **so that** a future UI entry point (out of scope for this plan's UI surface) has
something to call, and so shallow state is not a permanent, unrecoverable downgrade.

**Acceptance Criteria**:
- `GitRepository.unshallow(config: GitConfig): Either<DomainError.GitError, Unit>` runs
  `FetchCommand.setUnshallow(true)` and, on success, updates `git_config.clone_depth_state =
  'FULL_HISTORY'`, `shallow_depth = NULL` at the SQL layer — equivalently, persists
  `cloneDepthState = CloneDepthState.FullHistory` in-memory, which by construction carries no
  depth to leave stale.
  - *Given* a `GitConfig` with `cloneDepthState = CloneDepthState.Shallow(depth = 50)`, *When*
    `unshallow(config)` is called and the fetch succeeds, *Then* the persisted config's
    `cloneDepthState` becomes `CloneDepthState.FullHistory`.
- Before widening, the current remote ref's OID is compared against what was last observed; if it
  has diverged in a way that would make the shallow boundary invalid, `unshallow()` returns a
  distinct error rather than silently retrying forever (per `research/features.md` edge case #1).

**Files**: `GitRepository.kt` (interface), `AndroidGitRepository.kt`, `JvmGitRepository.kt`

##### Task 2.1.4a: Add `unshallow()` to the `GitRepository` interface (~3 min)
- Add the method signature to the shared `commonMain` `GitRepository` interface; add a
  `NotSupported("iOS")`-style stub if `IosGitRepository`/a wasm stub implements this interface and
  needs an override (check interface implementers first).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt`,
  `IosGitRepository.kt` (stub override if required)

##### Task 2.1.4b: Implement `unshallow()` on Android (~5 min)
- `openGitWithoutFreshnessCheck` (unshallow doesn't touch the working tree), run
  `git.fetch().setRemote(config.remoteName).setUnshallow(true)...call()` through
  `runGitTransportOpWithRetry` for consistency with clone/fetch/push's retry behavior.
- Files: `AndroidGitRepository.kt`

##### Task 2.1.4c: Implement `unshallow()` on JVM (~5 min)
- Same shape.
- Files: `JvmGitRepository.kt`

##### Task 2.1.4d: Ref-divergence guard before widening (~5 min)
- Before calling `setUnshallow(true)`, resolve `config.remoteName/config.remoteBranch` via a cheap
  `ls-remote` (reuse `testRemoteViaLsRemote`'s shape) and compare against the locally-known ref;
  if the comparison can't establish a safe widen (implementation detail — at minimum, don't crash;
  document the conservative choice made), return
  `DomainError.GitError.FetchFailed("Remote has diverged since the shallow clone — full history
  unavailable via automatic widen")` rather than attempting a widen JGit might mishandle.
- Files: `AndroidGitRepository.kt`, `JvmGitRepository.kt`, or a shared helper in
  `GitOperationSupport.kt` if the logic is identical on both platforms

##### Task 2.1.4e: Persist `CloneDepthState.FullHistory` on success (~3 min)
- After a successful `unshallow()`, save the updated `GitConfig` with `cloneDepthState =
  CloneDepthState.FullHistory` via the config repository. Task 2.1.2e's mapper writes `shallow_depth
  = NULL` for this variant automatically — there is no separate "null out the depth" step to
  remember here, closing architecture-review.md's Concern about a stale `shallow_depth` surviving
  a transition to full history.
- Files: `AndroidGitRepository.kt`, `JvmGitRepository.kt` (or the caller, if `GitRepository`
  implementations don't hold a `GitConfigRepository` reference — check and adjust call site
  accordingly, e.g. in `GitSyncService`)

##### Task 2.1.4f: Unit tests for `unshallow()` (~5 min)
- Success path (state transition), divergence-guard path (returns the distinct error, does not
  call `setUnshallow`).
- Files: platform test files alongside the implementation

#### Story 2.1.5: Fail closed on shallow-history/merge-base collision
**As a** SteleKit user with a shallow-cloned graph whose remote has diverged significantly, **I
want** a merge that would produce a degraded/wrong result to fail with a clear error instead of
silently merging incorrectly, **so that** my wiki's history is never silently corrupted by
`MergeStrategy.RECURSIVE`'s shallow-history limitation.

**Acceptance Criteria**:
- Given a shallow-cloned local repo whose shallow boundary is newer than the true merge base with
  the remote ref, `merge()` returns `DomainError.GitError.ShallowHistoryInsufficient` instead of
  calling JGit's `MergeCommand`. This guard covers **both** ways the merge base can be
  unreliable, per pre-mortem.md P1 #2 (JGit's own documented caveat that shallow-history
  merge-base computation "cannot be counted on to work as expected" — `RevWalk` can return a
  spurious ancestor rather than cleanly failing, not just fail to find one):
  1. **Absent** — no merge base is found at all within the shallow history.
  2. **Wrong-but-present** — `RevWalk` *does* return a merge-base commit, but that commit sits at
     or past the shallow boundary: its parents are not all present in the local object database
     (`repo.objectDatabase.has(parentId)` false for at least one parent that isn't itself a
     documented shallow-commit root). A merge base whose own ancestry is incomplete cannot be
     trusted as the true common ancestor — JGit's caveat means this shape is exactly where a
     spurious/wrong ancestor is returned instead of a clean "not found."
  - *Given* a `GitConfig` with `cloneDepthState = CloneDepthState.Shallow(depth)` and a synthetic
    test repo where the remote branch's history predates the local shallow boundary (no merge
    base at all is reachable), *When* `merge()` is called, *Then* the result is
    `Left(ShallowHistoryInsufficient)` and no merge commit is created.
  - *Given* a `GitConfig` with `cloneDepthState = CloneDepthState.Shallow(depth)` and a synthetic
    test repo constructed so `RevWalk`'s merge-base search returns a commit that sits at the
    shallow boundary (present locally, but at least one of its parents is not — i.e. a
    "wrong-but-present" merge base), *When* `merge()` is called, *Then* the result is also
    `Left(ShallowHistoryInsufficient)` and no merge commit is created — the guard does not treat a
    found-but-incomplete-ancestry commit as a safe merge base.
- A shallow repo whose shallow boundary safely covers the merge base (the common case — most
  merges happen against recent history, and the found merge-base commit's parents are all present
  locally) merges exactly as before, with no behavior change.

**Files**: `AndroidGitRepository.kt` (`doMerge`), `JvmGitRepository.kt` (`doMerge`),
`DomainError.kt`

##### Task 2.1.5a: Add `DomainError.GitError.ShallowHistoryInsufficient` (~2 min)
- Add the case plus entries in the two `when`-exhaustive message extension functions, with
  user-facing copy along the lines of "This graph's local history doesn't go back far enough to
  merge safely — contact support or re-clone with full history."
- Files: `DomainError.kt`

##### Task 2.1.5b: Detect both the absent and the wrong-but-present merge-base conditions (~7 min)
- Before calling `git.merge()` in `doMerge()`, if `repo.objectDatabase.shallowCommits` is
  non-empty, use JGit's `RevWalk` to compute the merge base between `HEAD` and `remoteRef`, and
  reject on **either** of two conditions (per pre-mortem.md P1 #2 — the guard must not stop at
  "no merge base found"):
  1. **Absent**: no merge base can be found within the shallow history (JGit's merge-base search
     terminates at the shallow boundary) → return `ShallowHistoryInsufficient` instead of calling
     `git.merge()`.
  2. **Wrong-but-present**: a merge-base commit *is* returned, but validate it against the local
     object database before trusting it — for each of the merge-base commit's parent OIDs, check
     `repo.objectDatabase.has(parentId)` (or resolve via `RevWalk.parseAny` and catch
     `MissingObjectException`); if any parent is missing and that parent is not itself one of
     `repo.objectDatabase.shallowCommits` (a legitimate shallow root, which is expected to have no
     locally-present parents), treat the returned merge base as unreliable and also return
     `ShallowHistoryInsufficient` instead of calling `git.merge()`. This is the case JGit's own
     documented caveat warns about — the merge-base search can return a spurious ancestor near the
     shallow boundary rather than cleanly failing, and condition 1 alone does not catch it.
- Files: `AndroidGitRepository.kt`

##### Task 2.1.5c: Same detection on JVM (~5 min)
- Files: `JvmGitRepository.kt`

##### Task 2.1.5d: Regression tests — absent AND wrong-but-present merge-base fixtures (~8 min)
- Build three test fixtures, per pre-mortem.md P1 #2's explicit instruction to add the
  wrong-but-present case, not just the absent one:
  1. **Absent**: a local repo with a shallow clone, then advance the "remote" past a point the
     shallow history doesn't cover so no merge base exists at all; assert `merge()` fails closed
     with `ShallowHistoryInsufficient` and no merge commit exists afterward.
  2. **Wrong-but-present** (new): construct a shallow clone where `RevWalk`'s merge-base search
     between `HEAD` and `remoteRef` returns a commit sitting exactly at the shallow boundary —
     i.e. a commit present locally but at least one of whose parents is not (and is not itself a
     shallow root) — and assert `merge()` still fails closed with `ShallowHistoryInsufficient`
     rather than proceeding to call `git.merge()` on the unreliable merge base. This is the
     regression fixture for Task 2.1.5b's condition 2; without it, only the "no merge base found"
     path is covered and JGit's documented spurious-ancestor caveat is unverified.
  3. A shallow clone with a recent, in-range merge base (all of the merge-base commit's parents
     present locally) merges successfully (regression guard for the common case).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/JvmGitRepositoryTest.kt` (or a new
  `ShallowMergeTest.kt`)

#### Story 2.1.6: Verify shallow history doesn't silently break existing commit-count/log behavior
**As a** SteleKit maintainer, **I want** confirmation that `countRemoteCommitsBestEffort` and
`log()` degrade gracefully (not crash or misreport) against a shallow repo, **so that** shipping
shallow-by-default doesn't regress every new clone's UI, not just large ones.

**Acceptance Criteria**:
- `countRemoteCommitsBestEffort` against a shallow repo returns a value ≤ the repo's actual
  shallow-visible commit count (capped at 100 as today) without throwing.
  - *Given* a shallow-cloned repo with depth 50, *When* `countRemoteCommitsBestEffort(git,
    headBefore, remoteRef)` is called after a fetch that advances `remoteRef` within the shallow
    window, *Then* it returns a non-negative count ≤ 100 and does not throw.
- `log(config, maxCount)` against a shallow repo returns up to `maxCount` commits (bounded by
  however much history the shallow clone actually has) without throwing.

**Files**: test-only — no production code change expected unless the verification below finds a
gap.

##### Task 2.1.6a: Add shallow-repo regression tests for `countRemoteCommitsBestEffort`/`log()` (~5 min)
- Two `JvmGitRepositoryTest`/businessTest cases per the ACs above, against a shallow test fixture.
  If either throws or misbehaves, this task also fixes the production code (expected to be a
  non-issue per `research/architecture.md` §6.4's own read — this task is verification-first).
- Files: `JvmGitRepositoryTest.kt`

---

## Phase 3: Android Foreground Execution

### Epic 3.1: `GitCloneWorker` — `CoroutineWorker` + `setForeground(dataSync)`

**Goal**: Move the initial clone (and, by extension, a user-initiated fetch/push) off the Step 5
composable's own coroutine scope onto a `CoroutineWorker` that promotes itself to a `dataSync`
foreground service, so it survives backgrounding/Doze — the Android-specific half of this
project's success metrics.

#### Story 3.1.1: Manifest permissions for `dataSync` foreground service
**As a** SteleKit maintainer, **I want** the correct Android 14 foreground-service-type
permission declared, **so that** `GitCloneWorker.setForeground()` doesn't throw
`ForegroundServiceStartNotAllowedException`/a manifest-validation failure at runtime.

**Acceptance Criteria**:
- `AndroidManifest.xml` declares `android.permission.FOREGROUND_SERVICE_DATA_SYNC` and
  `android.permission.POST_NOTIFICATIONS` (neither currently present — confirmed by
  `grep -n "POST_NOTIFICATIONS\|FOREGROUND_SERVICE" AndroidManifest.xml` returning only the
  existing `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` entries).
  - *Given* the manifest change, *When* `aapt2 dump permissions` (or an equivalent manifest-merge
    check) runs on the built APK, *Then* both new permissions are present.

**Files**: `kmp/src/androidMain/AndroidManifest.xml`

##### Task 3.1.1a: Add the two permission declarations (~3 min)
- Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` and
  `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` near the existing
  `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_CONNECTED_DEVICE` entries (`AndroidManifest.xml:40-41`).
- Files: `AndroidManifest.xml`

#### Story 3.1.2: `GitCloneWorker` implementation
**As a** SteleKit user on Android, **I want** an in-progress clone/fetch/push to survive the app
being backgrounded, **so that** locking my screen or switching apps doesn't kill the transfer.

**Acceptance Criteria**:
- `GitCloneWorker : CoroutineWorker` reads `url`/`localPath`/`graphId`/auth-reference input data,
  calls `setForeground()` with a `dataSync`-typed `ForegroundInfo` before starting the transfer,
  and delegates to `AndroidGitRepository.clone()` (retry/timeout/shallow already wired from
  Phases 1–2).
  - *Given* `GitCloneWorker` is enqueued with valid clone parameters, *When* `doWork()` starts,
    *Then* `setForeground()` is called before any JGit call, with a notification whose
    `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` type is set.
- The notification's progress reflects `GitTransportRetryState` (Phase 4 supplies the full sealed
  type; this story wires a minimal title/body update per JGit callback until Phase 4 lands).
- If the OS denies foreground promotion (`setForeground()` throws
  `ForegroundServiceStartNotAllowedException` — e.g. a race where WorkManager runs `doWork()` just
  as the app leaves the foreground-eligible window), `doWork()` does **not** fail the operation
  solely because promotion was denied — it logs the denial, continues the clone/fetch/push
  transfer without foreground promotion, and surfaces
  `GitTransportRetryState.Attempting(progress, foregroundPromoted = false)` so Step 5 doesn't
  imply a survivability guarantee that isn't actually in effect for this run.
  - *Given* `getForegroundInfo()` is built and `setForeground()` is called, *When*
    `setForeground()` throws `ForegroundServiceStartNotAllowedException`, *Then* `doWork()` logs
    the denial, proceeds to call `AndroidGitRepository.clone()` anyway with no foreground
    promotion in effect, and the observed `GitTransportRetryState` sequence includes an
    `Attempting(..., foregroundPromoted = false)` entry rather than the worker crashing or
    short-circuiting to `Result.failure()`.

**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitCloneWorker.kt` (new), new
Robolectric test file alongside it (Task 3.1.2f)

##### Task 3.1.2a: Scaffold `GitCloneWorker` class + input-data contract (~5 min)
- New file, `class GitCloneWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params)`, companion object with `KEY_URL`, `KEY_LOCAL_PATH`,
  `KEY_GRAPH_ID`, `KEY_AUTH_TYPE` input-data keys (auth secrets themselves are not passed as plain
  `Data` — resolve via the existing `CredentialStore`/`credentialAccess` keyed by `graphId`,
  matching how `GitSyncWorker`'s slow path already resolves credentials).
- Files: `GitCloneWorker.kt`

##### Task 3.1.2b: Implement `getForegroundInfo()` / notification builder (~5 min)
- Build a dedicated low-importance notification channel (create-if-absent, matching the
  `AndroidMeasurementForegroundService.kt` stub's already-correct TODO guidance) and a
  `NotificationCompat.Builder` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`; title/body
  content per `research/ux.md` §5 ("Syncing {graph name}" / state-dependent body), tap-target
  deep-linking to Step 5 (Task 4.1.5a covers the full content spec — this task wires the
  mechanism).
- Files: `GitCloneWorker.kt`

##### Task 3.1.2c: `doWork()` — `setForeground()` then delegate to `AndroidGitRepository.clone()` (~5 min)
- Call `setForeground(getForegroundInfo())` first, then construct/obtain an
  `AndroidGitRepository` (matching `GitSyncWorker`'s slow-path construction pattern) and call
  `clone(url, localPath, auth, onProgress = { progress -> /* update notification, Task 3.1.2d */ })`.
  Return `Result.success()`/`Result.failure()` per ADR-002 (never `Result.retry()` — Story 3.1.5
  makes this explicit).
- Files: `GitCloneWorker.kt`

##### Task 3.1.2d: Update the live notification from `onProgress` (~4 min)
- Wire `onProgress` to update the notification's body text via `NotificationManagerCompat.notify()`
  with the same notification ID, `setOnlyAlertOnce(true)` (per `research/ux.md` §5's no-repeat-alert
  guidance — full implementation detail in Task 4.1.5b, this task establishes the update path).
- Files: `GitCloneWorker.kt`

##### Task 3.1.2e: Catch `ForegroundServiceStartNotAllowedException` around `setForeground()` (~5 min)
- Wrap Task 3.1.2c's `setForeground(getForegroundInfo())` call in a
  `try { ... } catch (e: ForegroundServiceStartNotAllowedException) { ... }`. On catch: log the
  denial at `warn` via the existing `Logger` pattern (no new logger instance), emit
  `GitTransportRetryState.Attempting(progress = CloneProgress("Starting", 0, 0), foregroundPromoted
  = false)` via the `onStateChange` channel (Task 4.1.2b — if implementation order reaches this
  task before 4.1.2b lands, wire a placeholder no-op callback now and connect it once
  `onStateChange` exists), and fall through to the same `AndroidGitRepository.clone()` delegation
  Task 3.1.2c already runs when promotion succeeds. Do **not** return `Result.failure()` just
  because promotion was denied — the transfer itself did not fail, only its background-survival
  guarantee is absent for this run.
- Files: `GitCloneWorker.kt`

##### Task 3.1.2f: Regression test — `setForeground()` denial doesn't crash the worker (~5 min)
- Robolectric test (`androidUnitTest`, per `CLAUDE.md`'s Compose/worker-test convention) using a
  `TestListenableWorkerBuilder`-built `GitCloneWorker` whose `setForeground()` is mocked/shadowed
  to throw `ForegroundServiceStartNotAllowedException`, asserting `doWork()` still invokes the
  underlying clone delegate and returns a terminal `Result` (reflecting the clone's actual
  outcome, never an uncaught exception) rather than propagating the thrown exception or failing
  solely because promotion was denied.
- Files: new test file alongside `GitCloneWorker.kt`'s other test coverage

#### Story 3.1.3: `GitCloneWorkerLauncher` — platform-agnostic Step 5 call site
**As a** SteleKit maintainer, **I want** `GitSetupScreenSaveLogic.performCloneAndSave` to stay
platform-agnostic, **so that** Desktop continues to clone directly (no foreground-service concept
needed there) while Android routes through `GitCloneWorker`.

**Acceptance Criteria**:
- A `commonMain` `GitCloneWorkerLauncher` interface exposes `suspend fun launchClone(url: String,
  localPath: String, auth: GitAuth, onProgress: (CloneProgress) -> Unit): Either<DomainError.GitError,
  Unit>`. The Android implementation enqueues `GitCloneWorker` and observes its `WorkInfo` for
  completion/progress; the JVM implementation calls `GitRepository.clone()` directly, unchanged
  in spirit from today.
  - *Given* the Android `GitCloneWorkerLauncher` implementation, *When* `launchClone(...)` is
    called, *Then* it enqueues a `GitCloneWorker` one-off request and suspends until that work's
    `WorkInfo.state` reaches a terminal state, translating `SUCCEEDED`/`FAILED` into the `Either`
    result.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitCloneWorkerLauncher.kt` (new
interface), `kmp/src/androidMain/.../AndroidGitCloneWorkerLauncher.kt` (new),
`kmp/src/jvmMain/.../JvmGitCloneWorkerLauncher.kt` (new), `GitSetupScreenSaveLogic.kt`

##### Task 3.1.3a: Define the `commonMain` interface (~3 min)
- Files: `GitCloneWorkerLauncher.kt`

##### Task 3.1.3b: Android implementation — enqueue + observe `WorkInfo` (~5 min)
- Enqueue via `WorkManager.getInstance(context).enqueue(...)` (Story 5.1.2 changes this to the
  shared-unique-work-name variant — this task uses a plain one-off enqueue first, kept
  independently correct), observe via `WorkManager.getWorkInfoByIdFlow(...)`, map `WorkInfo.Data`
  progress fields back to `CloneProgress`/`Either`.
- Files: `AndroidGitCloneWorkerLauncher.kt`

##### Task 3.1.3c: JVM implementation — direct passthrough (~2 min)
- `launchClone(...)` calls `gitRepository.clone(...)` directly — no worker, no foreground concept,
  per requirements' explicit Desktop Out-of-Scope.
- Files: `JvmGitCloneWorkerLauncher.kt`

##### Task 3.1.3d: Wire `performCloneAndSave` to the launcher (~5 min)
- Replace the direct `onCloneAndAdd` suspend-call parameter's Android call site (wherever
  `MainActivity`/the DI wiring currently supplies `onCloneAndAdd`) to go through
  `GitCloneWorkerLauncher` instead of calling `AndroidGitRepository.clone()` inline on the
  composable's `rememberCoroutineScope()`. Desktop's wiring is unchanged (already direct).
- Files: `GitSetupScreenSaveLogic.kt`, the Android DI/wiring site (locate via `Glob`/`Grep` for
  `onCloneAndAdd =`)

#### Story 3.1.4: Bound the retry budget against battery/Android-14 `dataSync` limits
**As a** SteleKit user on a sustained outage (e.g. no signal in a basement), **I want** the
foreground service and its notification to stop once the retry budget is exhausted, **so that**
a permanent-looking outage doesn't hold a wakelock/notification alive indefinitely and drain the
battery.

**Acceptance Criteria**:
- The total wall-clock time `runGitTransportOpWithRetry` spends retrying is capped independently
  of the per-attempt backoff schedule (e.g. hard-stop at 10 minutes elapsed even if the schedule
  would allow more attempts), well under Android 14's ~6-hour `dataSync` aggregate cap.
  - *Given* a fake `op` that always throws a `Transient` failure and a retry loop whose schedule
    alone would run indefinitely, *When* `runGitTransportOpWithRetry` runs with a wall-clock
    deadline parameter, *Then* it stops and returns `RetryExhausted` once the deadline elapses,
    not just once the schedule's own attempt count is reached.
- `GitCloneWorker` stops (dismisses) its foreground promotion / notification the moment the retry
  budget is exhausted, rather than remaining foregrounded until WorkManager separately times it
  out.

**Files**: `GitOperationSupport.kt`, `GitCloneWorker.kt`

##### Task 3.1.4a: Add a wall-clock deadline parameter to `runGitTransportOpWithRetry` (~4 min)
- Extend the function (Task 1.2.2a) with an optional `maxElapsed: Duration = 10.minutes`
  parameter, checked before each retry attempt in addition to the schedule's own termination.
- Files: `GitOperationSupport.kt`

##### Task 3.1.4b: Stop the foreground service promptly on exhaustion (~3 min)
- In `GitCloneWorker.doWork()`, on a `RetryExhausted`/`Left` result, ensure the notification is
  updated to a terminal (dismissible) failure state immediately rather than lingering as an
  "in progress, non-dismissible" notification (ties into Task 4.1.5c's dismissal-state spec).
- Files: `GitCloneWorker.kt`

#### Story 3.1.5: Single retry owner applies to `GitCloneWorker` too
**As a** SteleKit maintainer, **I want** `GitCloneWorker.doWork()` to never return
`Result.retry()`, **so that** the same single-owner invariant established in Story 1.2.3 for
`GitSyncWorker` holds for the new worker (ADR-002).

**Acceptance Criteria**:
- `GitCloneWorker.doWork()`'s failure path always returns `Result.failure()`.
  - *Given* `clone()` returns `Left(RetryExhausted(...))`, *When* `doWork()` handles the result,
    *Then* it returns `Result.failure()`, never `Result.retry()`.

**Files**: `GitCloneWorker.kt`

##### Task 3.1.5a: Assert/enforce `Result.failure()` on the failure path (~2 min)
- Verify Task 3.1.2c's implementation already does this (it should, by construction); add an
  explicit comment citing ADR-002 so a future edit doesn't "helpfully" add `Result.retry()` back.
- Files: `GitCloneWorker.kt`

#### Story 3.1.6: Throwable-safety boundary for `GitCloneWorker.doWork()`
**As a** SteleKit user on Android, **I want** `GitCloneWorker.doWork()` to survive an uncaught
`Throwable` (not just an `Exception`) from a long-running foreground clone, **so that** an
`OutOfMemoryError` during JGit pack parsing (a real allocation hot spot, now running longer inside
a foreground service the user can't see, thanks to retry+backoff) fails the operation visibly
instead of silently killing the app process — the exact class of bug named in `CLAUDE.md`'s
"Uncaught coroutine Throwables kill the process on Android" section, previously fixed the same way
in `SteleKitApplication.onCreate`.

**Acceptance Criteria**:
- `GitCloneWorker.doWork()`'s outer body is wrapped in `catch (e: Throwable)`, not
  `catch (e: Exception)` — converting *any* throwable (including `Error` subclasses like
  `OutOfMemoryError`) to `Result.failure()` and tearing down the foreground notification, rather
  than letting it propagate uncaught.
  - *Given* a `GitCloneWorker.doWork()` call whose wrapped `op()` throws an `OutOfMemoryError`,
    *When* `doWork()` runs, *Then* it returns `Result.failure()` and the foreground notification is
    torn down, rather than the throwable propagating uncaught and killing the process.
- `doWork()`'s catch logic rethrows `CancellationException` *before* the generic
  `catch (e: Throwable)` runs, so a worker cancellation (e.g. Story 4.1.4's Cancel button, via
  `WorkManager.cancelUniqueWork` cancelling the coroutine `Job`) always propagates as cancellation
  and is never converted to `Result.failure()` — per Story 1.2.2's AC that a `Cancelled`-classified
  failure propagates as cancellation and is never retried.
  - *Given* `doWork()`'s wrapped operation throws `CancellationException` because the worker was
    cancelled, *When* `doWork()`'s catch block runs, *Then* the `CancellationException` is
    rethrown rather than converted to `Result.failure()`.
- `runGitTransportOpWithRetry`'s (Task 1.2.2a) `Exception`-typed signature stays unchanged — its
  job is classifying *retryable* transport failures, which by definition applies only to
  `Exception`s; a process-fatal `Error` like `OutOfMemoryError` is never a retry candidate, only a
  fail-fast one. `GitCloneWorker`'s outer `catch (Throwable)` is the correct boundary for this fix
  because it is the outermost frame of the whole foreground operation — the one place that must
  guarantee the notification is torn down and the worker returns a terminal `Result` no matter what
  escapes, retried transport failure or not. Widening `classifyGitFailure`/
  `runGitTransportOpWithRetry` to `Throwable` instead would incorrectly imply an `Error` could be a
  retry candidate.
- `GitCloneWorker` overrides `onStopped()` to explicitly tear down the foreground notification on
  the cancellation path that Task 3.1.6a's `catch (CancellationException) { throw e }` rethrow
  routes around the generic `catch (Throwable)` teardown — this is the explicit, testable owner of
  that teardown (Task 3.1.6c), rather than an unverified reliance on WorkManager's own
  foreground-service teardown behavior on cancellation.
  - *Given* a `GitCloneWorker` currently showing its foreground notification, *When* the worker is
    stopped via `WorkManager.cancelUniqueWork` (e.g. the user tapping Cancel per Story 4.1.4),
    *Then* `onStopped()` fires and tears down the notification.

**Files**: `GitCloneWorker.kt`, new test file (see Task 3.1.6b), new/adjacent test file (see Task 3.1.6c)

##### Task 3.1.6a: Wrap `doWork()`'s outer body in `catch (e: Throwable)` (~4 min)
- In `GitCloneWorker.doWork()` (Task 3.1.2c), wrap the whole body — from `setForeground()` through
  the `AndroidGitRepository.clone()` delegation — in a `try { ... } catch (e: Throwable) { ... }`,
  mirroring `SteleKitApplication.onCreate`'s established `catch (e: Throwable)` fix for this exact
  class of bug. On catch: tear down the foreground notification (reuse Task 3.1.4b's
  terminal-failure notification path) and return `Result.failure()`. Do not catch only
  `Exception` here — `OutOfMemoryError` and other `Error` subclasses must be caught too.
- **Ordering is mandatory and must be explicit in the code**: add
  `catch (e: CancellationException) { throw e }` *before* the generic `catch (e: Throwable)`
  block, so a coroutine cancellation (from `WorkManager.cancelUniqueWork`/Story 4.1.4's Cancel
  button) always rethrows and propagates as cancellation rather than being swallowed into
  `Result.failure()` by the generic catch. (`CancellationException` is an `Exception`/`Throwable`
  subtype, so without this earlier catch it would otherwise be silently caught by either.)
- Files: `GitCloneWorker.kt`

##### Task 3.1.6b: Regression test — a thrown `Error` subtype is caught, not propagated (~4 min)
- Unit test (`androidUnitTest`/Robolectric, per `CLAUDE.md`'s Compose/worker-test convention) using
  a test double whose `op()`/`AndroidGitRepository.clone()` call throws `OutOfMemoryError` (or a
  stand-in `Error` subtype if constructing a real `OutOfMemoryError` is impractical in-test),
  asserting `doWork()` returns `Result.failure()` rather than the exception propagating out of the
  test.
- **Companion test (mandatory, same task)**: a test double whose `op()` throws
  `CancellationException` instead, asserting the exception propagates *out of* `doWork()` (the
  test framework catches/asserts the throw, e.g. `assertFailsWith<CancellationException> { ... }`)
  rather than `doWork()` returning `Result.failure()`. This is the regression coverage for the
  Task 3.1.6a ordering requirement and for Story 4.1.4's Cancel button.
- Files: new test file alongside `GitCloneWorker.kt`'s other test coverage

##### Task 3.1.6c: Override `onStopped()` to tear down the foreground notification (~4 min)
- `GitCloneWorker` overrides `ListenableWorker`/`CoroutineWorker`'s `onStopped()` — the callback
  WorkManager invokes when the worker is cancelled/stopped, including via
  `WorkManager.cancelUniqueWork` (Task 4.1.4b, Story 4.1.4's Cancel button) — and calls the same
  notification-teardown call used by Task 3.1.4b's terminal-failure path (the one Task 3.1.6a's
  generic `catch (Throwable)` also reuses). Do not invent a second notification-teardown mechanism.
  This exists because Task 3.1.6a's mandatory `catch (CancellationException) { throw e }` rethrow
  (added ahead of the generic `catch (Throwable)`) means the cancellation path never reaches that
  catch block's teardown — `onStopped()` is the only remaining place in `GitCloneWorker` that runs
  on every cancellation, so it must own this explicitly rather than assuming WorkManager tears the
  foreground notification down on its own (unverified).
  - **AC**: *Given* a `GitCloneWorker` currently showing its foreground notification, *When* the
    worker is stopped via `WorkManager.cancelUniqueWork` (e.g. the user tapping Cancel per Story
    4.1.4), *Then* `onStopped()` fires and tears down the notification.
- **Companion test (mandatory, same task)**: unit test building `GitCloneWorker` with
  `TestListenableWorkerBuilder` (same convention as Task 3.1.2's `getForegroundInfo()` test),
  invoking `onStopped()` directly (or cancelling the worker's backing `Job`/work request so
  WorkManager's test driver invokes it), asserting the notification-teardown call fires exactly
  once.
- Files: `GitCloneWorker.kt`, new test file alongside Task 3.1.6b's

#### Story 3.1.7: Stuck-`WorkInfo` watchdog for the silent battery-optimization kill
**As a** SteleKit user whose OEM battery manager kills `GitCloneWorker`'s process outright (no
exception thrown, `onStopped()` never invoked — a distinct mechanism from Doze that a correctly
`setForeground()`'d service does not defeat, per pre-mortem.md P1 #1), **I want** the app to
notice a clone that has been stuck `RUNNING` far longer than any legitimate transfer would take,
and tell me battery optimization may be the cause, **so that** I'm not left staring at a stale
"syncing" notification/Step 5 state indefinitely with no error and no way to diagnose it — the one
failure mode in this project with no thrown exception for `classifyGitFailure` to classify at all.

**Acceptance Criteria**:
- A scheduled check (the existing periodic `GitSyncWorker` run, or an equivalent lightweight
  periodic work request) queries `WorkManager.getWorkInfosForUniqueWork(workNameFor(graphId))`
  for each graph with a recent `GitCloneWorker` enqueue, and if that `WorkInfo` has been in
  `RUNNING` state for longer than `STUCK_CLONE_WATCHDOG_THRESHOLD` (30 minutes — comfortably past
  `GIT_TRANSPORT_TIMEOUT_SECONDS` and Story 3.1.4's 10-minute retry wall-clock deadline, both of
  which bound how long a legitimately-running clone can take), it surfaces the user-facing
  message "Sync may have been stopped by battery optimization — check your device's battery
  settings for SteleKit."
  - *Given* a `GitCloneWorker` `WorkInfo` whose `state == RUNNING` and whose tracked start time is
    more than 30 minutes in the past (simulated via a fake clock in test — `WorkInfo` itself
    exposes no start-time field), *When* the watchdog check runs, *Then* it surfaces the
    battery-optimization message.
- The watchdog does not fire for a `WorkInfo` that is `RUNNING` but still within the threshold —
  no false positive on a slow-but-genuinely-alive transfer.
- This message is the only user-facing signal for this failure mode — there is no exception to
  route through `classifyGitFailure`/`GitTransportRetryState`, since the process was killed rather
  than throwing.

**Files**: `WorkManagerSyncScheduler.kt`, `GitCloneWorker.kt` (notification-builder reuse for
surfacing the message)

##### Task 3.1.7a: Track `GitCloneWorker` start time and detect a stuck `RUNNING` `WorkInfo` (~5 min)
- Record a start timestamp when `GitCloneWorker` is enqueued (`WorkInfo` doesn't expose one
  directly — persist it alongside the existing unique-work bookkeeping, e.g. a small
  `SharedPreferences`/DataStore entry keyed by `graphId`, or reuse `git_config` if a column is
  cheaper than new storage — implementer's call). Add
  `const val STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES = 30` and a check function that compares
  `WorkManager.getWorkInfosForUniqueWork(workNameFor(graphId))`'s `RUNNING` entries against that
  threshold.
- Files: `WorkManagerSyncScheduler.kt`

##### Task 3.1.7b: Run the check from the existing periodic path and surface the message (~4 min)
- Call Task 3.1.7a's check from `GitSyncWorker.doWork()`'s existing periodic invocation (or a new
  lightweight periodic work request, if coupling into `GitSyncWorker` is undesirable — document
  the choice). On detection, post a dismissible notification (reusing `GitCloneWorker`'s
  notification-builder plumbing) with the exact copy: "Sync may have been stopped by battery
  optimization — check your device's battery settings for SteleKit." Do not mark this
  `Result.retry()`/`Result.failure()` for the *periodic* worker itself — the watchdog is a
  side-effecting observation, not a retry decision (ADR-002 governs `GitCloneWorker`'s own retry
  ownership, not this unrelated check).
- Files: `WorkManagerSyncScheduler.kt`, `GitCloneWorker.kt`

##### Task 3.1.7c: Unit test — watchdog fires past threshold, not before (~5 min)
- Test asserting both ACs: a `RUNNING` `WorkInfo` older than 30 minutes triggers the message; one
  within 30 minutes does not.
- Files: new test file, e.g. `WorkManagerSyncSchedulerWatchdogTest.kt`

---

## Phase 4: Wizard Step 5 UX

### Epic 4.1: Retry/Resume Status, Cancel, and Notification Content

**Goal**: Replace raw exception text ("Clone failed: Software caused connection abort") with a
classified, reassuring status; add the missing cancel affordance; wire real progress data through
JGit's currently-no-op `ProgressMonitor.update(completed)`.

#### Story 4.1.1: Wire `ProgressMonitor.update(completed)` through
**As a** SteleKit maintainer, **I want** JGit's per-phase completed/total-work counts forwarded
to the UI, **so that** a real (if per-phase, not whole-operation) progress signal is available
instead of only a title string.

**Acceptance Criteria**:
- `GitRepository.clone()`'s `onProgress` callback signature widens from `(String) -> Unit` to
  `(CloneProgress) -> Unit`, and both platforms' `ProgressMonitor.update(completed: Int)`
  forwards `completed`/the last-seen `totalWork` instead of doing nothing.
  - *Given* JGit calls `beginTask("Receiving objects", 100)` then `update(42)`, *When* the
    widened callback fires, *Then* the caller receives `CloneProgress(phase = "Receiving
    objects", completed = 42, totalWork = 100)`.

**Files**: `GitRepository.kt`, `AndroidGitRepository.kt`, `JvmGitRepository.kt`,
`GitSetupScreenSaveLogic.kt`, `GitCloneWorker.kt`

##### Task 4.1.1a: Define `CloneProgress` and widen the `GitRepository.clone()` signature (~4 min)
- Add `data class CloneProgress(val phase: String, val completed: Int, val totalWork: Int)` to
  `commonMain` (e.g. `GitRepository.kt` or a small new file next to it); change `clone()`'s
  `onProgress` parameter type.
- Files: `GitRepository.kt`

##### Task 4.1.1b: Forward `completed`/`totalWork` in `AndroidGitRepository`'s `ProgressMonitor` (~4 min)
- Replace the no-op `override fun update(completed: Int) {}` (`AndroidGitRepository.kt:104`) to
  track the last `beginTask` title/totalWork in a local var and invoke
  `onProgress(CloneProgress(title, completed, totalWork))`.
- Files: `AndroidGitRepository.kt`

##### Task 4.1.1c: Same on JVM (~4 min)
- `JvmGitRepository.kt:90-97`.
- Files: `JvmGitRepository.kt`

##### Task 4.1.1d: Update call sites for the widened signature (~5 min)
- `GitSetupScreenSaveLogic.performCloneAndSave`'s `onCloneProgress: (String) -> Unit` parameter
  and `GitCloneWorker`'s `onProgress` lambda (Task 3.1.2c/d) both update to consume
  `CloneProgress` instead of a bare `String`.
- Files: `GitSetupScreenSaveLogic.kt`, `GitCloneWorker.kt`

#### Story 4.1.2: `GitTransportRetryState` — one state type driving Step 5 and the notification
**As a** SteleKit maintainer, **I want** a single sealed state type describing "what's happening
with this clone/fetch/push right now," **so that** Step 5's UI and the foreground-service
notification (Phase 3) never disagree and never show a raw exception string.

**Acceptance Criteria**:
- `GitTransportRetryState` has cases `Idle`, `Attempting(progress: CloneProgress,
  foregroundPromoted: Boolean = true)`, `Retrying(attempt: Int, max: Int, progress:
  CloneProgress?)`, `ResumingDeepen(percent: Int?)`, `Exhausted(reason: String)`,
  `NonRetryableFailure(reason: String)`. `foregroundPromoted = false` is the signal Task 3.1.2e
  surfaces when `setForeground()` was denied for the current run.
- `runGitTransportOpWithRetry`'s `onAttempt` callback (Task 1.2.2a) is threaded through to produce
  this state on each attempt/retry/terminal outcome.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitTransportRetryState.kt` (new)

##### Task 4.1.2a: Define the sealed class (~4 min)
- Files: `GitTransportRetryState.kt`

##### Task 4.1.2b: Thread it through `onAttempt`/a new parallel callback (~5 min)
- Add an `onStateChange: (GitTransportRetryState) -> Unit` parameter to
  `runGitTransportOpWithRetry` (or derive it from the existing `onAttempt` at the call site — pick
  whichever keeps `GitOperationSupport.kt`'s signature simplest; document the choice), invoked at
  each attempt/retry/terminal transition (per `research/ux.md` §3's "announce at transitions, not
  every tick" — this is also where that debouncing naturally lives, since attempts already are
  discrete transitions).
- Files: `GitOperationSupport.kt`

##### Task 4.1.2c: Wire clone/fetch/push call sites to surface `GitTransportRetryState` (~5 min)
- `AndroidGitRepository`/`JvmGitRepository`'s `clone()`/`fetch()`/`push()` accept and forward an
  `onStateChange` (or fold it into the existing `onProgress`/`CloneProgress` channel with a
  wrapping type — pick one consistent approach and apply to all three methods).
- Files: `AndroidGitRepository.kt`, `JvmGitRepository.kt`

#### Story 4.1.3: Step 5 UI — retry/resume status display
**As a** SteleKit user on a flaky connection, **I want** Step 5 to show "Reconnecting… (attempt 2
of 4)" instead of an unchanging spinner or raw exception text, **so that** I don't think the app
has frozen.

**Acceptance Criteria**:
- *Given* `cloneInProgress = true` and `GitTransportRetryState.Retrying(attempt = 2, max = 4,
  progress = null)`, *When* `Step5TestAndSave` renders, *Then* `CloneProgressRow` shows primary
  text "Cloning your graph…" and secondary (smaller, muted) text "Reconnecting… Attempt 2 of 4" —
  never the raw exception message.
- *Given* `GitTransportRetryState.Exhausted("...")`, *When* the row renders, *Then* it shows a
  warning-styled (not error-red) icon and copy: "Couldn't finish after 4 attempts. Check your
  connection and try again — your progress is saved," with a same-context "Try again" action.
- *Given* `GitTransportRetryState.NonRetryableFailure("...")` for an auth failure, *When* the row
  renders, *Then* no attempt counter is shown at all, and copy points at the fix (e.g.
  "Authentication failed — check your token/SSH key in Step 3").
- The container carries `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`, matching
  `FolderSyncReconciliationProgress.kt`/`StorageMoveProgressDialog.kt`'s existing convention, and
  the announced text updates at most once per retry attempt / every ~10% of resume progress, not
  on every JGit callback.

**Files**: `GitSetupStep5TestAndSave.kt`

##### Task 4.1.3a: Extend `CloneProgressRow` to accept `GitTransportRetryState` (~5 min)
- Change `CloneProgressRow(cloneProgress: String)` to accept
  `retryState: GitTransportRetryState`, rendering the primary/secondary text split per the AC.
- Files: `GitSetupStep5TestAndSave.kt`

##### Task 4.1.3b: Add the live-region semantics + debounce (~4 min)
- Wrap the row's container in `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`; debounce
  the announced text at the state-production site (Task 4.1.2b already only fires on discrete
  transitions, so this task mainly verifies no additional per-tick noise is introduced here).
- Files: `GitSetupStep5TestAndSave.kt`

##### Task 4.1.3c: Distinct terminal-state styling (Exhausted vs. NonRetryableFailure) (~5 min)
- Implement the two distinct visual treatments per the AC (warning icon + "Try again" for
  `Exhausted`; error icon + actionable copy, no attempt counter, for `NonRetryableFailure`),
  extending `TestResultRow`'s existing icon/color convention (`Icons.Default.Check`/`Error`,
  `Color(0xFF047857)`/`colorScheme.error`).
- Files: `GitSetupStep5TestAndSave.kt`

#### Story 4.1.4: Cancel affordance for an in-progress clone
**As a** SteleKit user, **I want** an explicit Cancel button during a clone, **so that** I'm not
stuck with Back and Save both disabled and no way out.

**Acceptance Criteria**:
- *Given* `cloneInProgress = true`, *When* Step 5 renders, *Then* a "Cancel" button is visible and
  enabled, mirroring `onCancelTestConnection`'s existing pattern.
- *Given* the user taps Cancel, *When* the cancellation propagates, *Then* the underlying
  `Job`/`WorkManager` unique work is cancelled (`WorkManager.cancelUniqueWork` on Android; the
  coroutine `Job` on Desktop), `classifyGitFailure` reports `Cancelled` (not retried), and the
  partially-cloned directory/checkpoint state is **not** deleted (Story 2.1.3's cleanup only runs
  ahead of an automatic retry, never on manual cancel).
- Step 5 shows "Cancelled — your progress is saved. Resume anytime from Step 5."

**Files**: `GitSetupStep5TestAndSave.kt`, `GitSetupScreenSaveLogic.kt`,
`AndroidGitCloneWorkerLauncher.kt`

##### Task 4.1.4a: Add the Cancel button to `Step5TestAndSave`/`BackAndSaveRow` (~4 min)
- New `onCancelClone: () -> Unit` parameter, rendered next to `CloneProgressRow` when
  `cloneInProgress`, mirroring `onCancelTestConnection`'s `TextButton` placement.
- Files: `GitSetupStep5TestAndSave.kt`

##### Task 4.1.4b: Android — cancel via `WorkManager.cancelUniqueWork` (~4 min)
- `AndroidGitCloneWorkerLauncher` exposes a `cancel()` method calling
  `WorkManager.getInstance(context).cancelUniqueWork(workName)`. Tearing down the foreground
  notification on this path is owned by `GitCloneWorker.onStopped()` (Task 3.1.6c), not this task.
- Files: `AndroidGitCloneWorkerLauncher.kt`

##### Task 4.1.4c: Desktop — cancel the coroutine `Job` (~3 min)
- `JvmGitCloneWorkerLauncher` exposes `cancel()` cancelling the `Job` its `launchClone` is running
  in (verify `ProgressMonitor.isCancelled()`'s existing `job?.isCancelled == true` wiring
  correctly surfaces this as `CanceledException` → `GitFailureClass.Cancelled`, not retried).
- Files: `JvmGitCloneWorkerLauncher.kt`

##### Task 4.1.4d: Confirm Story 2.1.3's cleanup never runs on manual cancel (~3 min)
- Add a regression test/assertion: a manually-cancelled clone leaves the partial target directory
  intact (not deleted) — `beforeRetry`'s cleanup only fires ahead of an *automatic* retry attempt,
  which a manual cancel never triggers (cancellation exits the retry loop entirely, per Task
  1.2.2a's `Cancelled` handling).
- Files: `GitTransportRetryTest.kt`

#### Story 4.1.5: Foreground-service notification content
**As a** SteleKit user, **I want** the sync notification to name my graph and show meaningful
state, **so that** I know it's safe to leave the app and what's currently happening.

**Acceptance Criteria**:
- Notification title is "Syncing {graph display name}" (never a raw URL/path); body mirrors
  Step 5's own `GitTransportRetryState` copy ("Cloning — 60%" / "Reconnecting… (attempt 2 of 4)" /
  "Resuming — 45%" / "Sync failed — tap to retry").
- Tapping the notification deep-links back to Step 5 specifically, not the app's generic
  home/last screen.
- The notification is non-dismissible while active, auto-dismisses on success, and converts to a
  normal (dismissible) notification on terminal failure.
- `setOnlyAlertOnce(true)` — only the first post/state-change alerts (no repeated
  sound/vibration per progress tick).

**Files**: `GitCloneWorker.kt`

##### Task 4.1.5a: Title/body content + deep-link `PendingIntent` (~5 min)
- Build the notification content per the AC, with a `PendingIntent` targeting the app's Step 5
  route (locate the existing navigation deep-link mechanism, if any, via `Glob`/`Grep`; if none
  exists, use a simple activity-launch intent with a route argument the app's navigation already
  reads on cold start — check `MainActivity`'s intent-handling for a precedent first).
- Files: `GitCloneWorker.kt`

##### Task 4.1.5b: `setOnlyAlertOnce(true)` + progress-bar mode (indeterminate until real %) (~3 min)
- Set `setOnlyAlertOnce(true)`; use an indeterminate progress bar until `CloneProgress.totalWork`
  is meaningfully known (per `research/ux.md` §5 — "don't fake a determinate bar"), determinate
  once available.
- Files: `GitCloneWorker.kt`

##### Task 4.1.5c: Dismissal-state handling (~4 min)
- `setOngoing(true)` while active; on success, cancel the notification; on terminal failure,
  rebuild it as dismissible (`setOngoing(false)`) with "tap to retry" content, per the AC. The
  cancellation path (user-initiated stop, not success/failure) is a third case owned by
  `GitCloneWorker.onStopped()` (Task 3.1.6c), not this task.
- Files: `GitCloneWorker.kt`

---

## Phase 5: `GitWorktreeLocks` / Periodic-Fetch Race Fix

### Epic 5.1: Mutual Exclusion Between Foreground Clone/Fetch/Push and Periodic Background Fetch

**Goal**: Close the race `research/pitfalls.md` §3.4 and `research/architecture.md` §4 identify —
`WorkManagerSyncScheduler`'s periodic fetch and a foreground-service-held clone/push are not
serialized against each other at the JGit level today, and this project's longer-held operations
(retries, foreground survival) widen that window substantially.

#### Story 5.1.1: Extend `GitSyncBusyCounter` to `fetchOnly()`
**As a** SteleKit maintainer, **I want** `GitSyncBusyCounter` to also track `fetchOnly()`, not
just `sync()`, **so that** same-process busy-detection (used by the relocate-quiesce sequence and
as defense-in-depth here) is accurate for every JGit-touching operation.

**Acceptance Criteria**:
- *Given* `GitSyncService.fetchOnly(graphId)` is in flight, *When* another component reads
  `gitSyncBusyCounter.isBusy.value`, *Then* it is `true` for the duration of the call, mirroring
  `sync()`'s existing `begin()`/`end()` bracketing.

**Files**: `GitSyncService.kt`

##### Task 5.1.1a: Wire `begin()`/`end()` around `fetchOnly()` (~4 min)
- Bracket `fetchOnly()`'s body the same way `sync()` already does (`GitSyncService.kt:186,381`),
  including the `try`/`finally`-equivalent guarantee that `end()` runs on every exit path.
- Files: `GitSyncService.kt`

##### Task 5.1.1b: Test — `isBusy` reflects `fetchOnly()` in flight (~3 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitSyncServiceTest.kt` (extend
  existing, or add alongside)

#### Story 5.1.2: Share the periodic job's unique-work name for the foreground clone/fetch/push worker
**As a** SteleKit maintainer, **I want** `GitCloneWorker` enqueued under the same unique-work name
as the periodic `GitSyncWorker`, **so that** WorkManager's own uniqueness guarantee serializes
them for free, without a second, only-same-process mutex.

**Acceptance Criteria**:
- *Given* a periodic `GitSyncWorker` job is scheduled for `graphId` under
  `stelekit_git_sync_$graphId` (`WorkManagerSyncScheduler.workNameFor`), *When* `GitCloneWorker`
  is enqueued for the same `graphId`, *Then* it uses `WorkManager.beginUniqueWork(workNameFor(graphId),
  ExistingWorkPolicy.APPEND_OR_REPLACE, ...)`, so WorkManager runs them sequentially rather than
  concurrently.
- The initial-clone case (no `GitConfig`/periodic job scheduled yet for this graph) still works —
  `beginUniqueWork` with no existing work for that name simply runs immediately, matching
  `research/architecture.md` §4's confirmation that no race is possible at this stage by
  construction.

**Files**: `AndroidGitCloneWorkerLauncher.kt`, `WorkManagerSyncScheduler.kt` (expose
`workNameFor` beyond `private`)

##### Task 5.1.2a: Make `WorkManagerSyncScheduler.workNameFor` accessible to `GitCloneWorker`'s enqueue site (~2 min)
- Change `private fun workNameFor(graphId: String)` to `internal fun` (or move to a small shared
  object both files reference) so `AndroidGitCloneWorkerLauncher` can compute the same name.
- Files: `WorkManagerSyncScheduler.kt`

##### Task 5.1.2b: Change `GitCloneWorker`'s enqueue to `beginUniqueWork` sharing the name (~4 min)
- Replace Task 3.1.3b's plain `enqueue(...)` with
  `WorkManager.getInstance(context).beginUniqueWork(workNameFor(graphId),
  ExistingWorkPolicy.APPEND_OR_REPLACE, oneTimeRequest).enqueue()`.
- Files: `AndroidGitCloneWorkerLauncher.kt`

##### Task 5.1.2c: Test — periodic and foreground jobs for the same graph serialize (~5 min)
- WorkManager instrumented/robolectric test (matching this repo's existing `WorkManager` test
  conventions — locate via `Glob` for existing `*WorkManager*Test*`) asserting two work requests
  enqueued under the same unique name for the same `graphId` do not run concurrently.
- Files: new test file alongside existing WorkManager tests

#### Story 5.1.3: `GitSyncWorker`'s slow path yields to an active foreground op (defense in depth)
**As a** SteleKit maintainer, **I want** the slow path (process was killed, standalone
`AndroidGitRepository`) to skip its fetch if a `GitCloneWorker` is already running for the same
graph in a freshly-restarted process, **so that** the two never race even outside WorkManager's
own unique-name serialization (belt-and-suspenders for the process-restart edge case
`research/architecture.md` §4 names).

**Acceptance Criteria**:
- *Given* `WorkManager.getWorkInfosForUniqueWork(workName)` reports a `RUNNING` `GitCloneWorker`
  for `graphId`, *When* `GitSyncWorker`'s slow path would otherwise run its standalone fetch,
  *Then* it returns `Result.success()` as a no-op instead.

**Files**: `WorkManagerSyncScheduler.kt`

##### Task 5.1.3a: Query `getWorkInfosForUniqueWork` before the slow-path fetch (~4 min)
- Add the check at the top of the slow-path branch in `GitSyncWorker.doWork()`
  (`WorkManagerSyncScheduler.kt:~135-168`).
- Files: `WorkManagerSyncScheduler.kt`

##### Task 5.1.3b: Test — slow path no-ops when a foreground op is active (~4 min)
- Files: new test alongside Story 5.1.2's WorkManager test

---

## Phase 6: Fault-Injection Test Infrastructure

### Epic 6.1: Deterministic Mid-Transfer Failure Harness

**Goal**: This codebase has no existing precedent for injecting a mid-transfer transport failure
(`research/pitfalls.md` §5.2). Build the minimum viable harness at two levels — a fast, fully
deterministic fake for retry/backoff/UI logic, and a small, real (if synthetic) JGit-transport
fault-injection for proving the classifier/retry wiring against an actual thrown exception.

#### Story 6.1.1: Fake-level fault injection for retry/backoff/UI logic
**As a** SteleKit maintainer, **I want** a configurable fake that fails N times then succeeds,
without a real socket, **so that** retry/backoff scheduling and UI-state-transition tests run
fast and deterministically.

**Acceptance Criteria**:
- `StubGitRepository` (existing, `kmp/src/commonTest/.../testsupport/StubGitRepository.kt`) gains
  a configurable failure-sequence mode for `clone`/`fetch`/`push` (e.g. "throw `SocketException`
  twice, then succeed" or "always throw `NoRemoteRepositoryException`").
  - *Given* `StubGitRepository` configured to fail twice with a transient cause then succeed,
    *When* a test drives `GitSyncService`/`GitCloneWorker`-level logic against it, *Then* the
    observed `GitTransportRetryState` sequence is `Attempting → Retrying(1,…) → Retrying(2,…) →
    Idle/Success`.

**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/testsupport/StubGitRepository.kt`

##### Task 6.1.1a: Add configurable failure-sequence support to `StubGitRepository` (~5 min)
- Files: `StubGitRepository.kt`

##### Task 6.1.1b: Retry/backoff scheduling tests using the fake (~5 min)
- Following `GitSyncServiceRateLimitRetryTest.kt`'s existing real-wall-clock test pattern
  (`PlatformDispatcher.IO` can't be pointed at a `TestDispatcher` on the JVM per
  `research/pitfalls.md` §5.2), assert retry-N-happens-after-the-right-delay,
  jitter/cap behavior, and cancellation-on-manual-retry.
- Files: new test file alongside `GitSyncServiceRateLimitRetryTest.kt`

#### Story 6.1.2: JGit-transport-level fault injection
**As a** SteleKit maintainer, **I want** at least one test proving `classifyGitFailure` +
`runGitTransportOpWithRetry` correctly retries a real (if synthetic) JGit-thrown transport
exception, **so that** the classifier/retry wiring isn't only validated against a hand-mocked
exception.

**Acceptance Criteria**:
- A test drives a real JGit `fetch()`/`clone()` against a local `file://` remote wrapped so it
  throws mid-transfer on a configured trigger (e.g. after N objects, or via a custom
  `TransportConfigCallback`/failing input stream), and asserts the resulting exception classifies
  as `Transient` and is retried by `runGitTransportOpWithRetry`.

**Files**: new test file, scope intentionally small per `research/pitfalls.md` §5.2's own
guidance ("a small number of slow/flaky-tolerant integration tests, not the primary coverage
mechanism").

##### Task 6.1.2a: Build the minimal failing-transport test fixture (~5 min)
- Implement the smallest viable local fault-injecting transport/remote (document the exact
  mechanism chosen, since JGit's pluggable-transport API has several viable seams — pick one and
  keep it small).
- Files: new test-support file

##### Task 6.1.2b: One integration test proving retry actually fires on a real thrown exception (~4 min)
- Files: new test file, referencing the fixture from 6.1.2a

##### Task 6.1.2c: Document the harness's scope/limits in a code comment (~2 min)
- One or two lines at the top of the new test file noting this proves the mechanism once, not a
  full fault matrix — per this plan's Proportionality guidance, not a design doc.
- Files: same file as 6.1.2b

#### Story 6.1.3: End-to-end fast-fail regression under the full stack
**As a** SteleKit maintainer, **I want** an end-to-end test (via `GitCloneWorker`/`GitSyncService`,
not just the raw `runGitTransportOpWithRetry` unit test from Story 1.3.1) proving a
bad-credentials clone fails within one attempt and releases the foreground service immediately,
**so that** the sharpest common-case regression (`research/pitfalls.md` §5.1) is covered at the
integration level, not just the unit level.

**Acceptance Criteria**:
- *Given* a `StubGitRepository` configured to always throw an auth-shaped failure, *When*
  `GitCloneWorker.doWork()` runs, *Then* it returns `Result.failure()` after exactly one attempt,
  and (on Android) the foreground notification is dismissed/converted to failure state
  immediately, not after any backoff delay.

**Files**: new test alongside `GitCloneWorker.kt`'s test coverage (androidUnitTest/Robolectric,
per `CLAUDE.md`'s "Compose-behavior/worker test belongs in androidUnitTest" guidance where
applicable, or businessTest if no Compose/Android-framework dependency is exercised).

##### Task 6.1.3a: Write the end-to-end fast-fail test (~5 min)
- Files: new test file

#### Story 6.1.4: Shadow-worktree freshness interaction regression
**As a** SteleKit maintainer, **I want** a test proving a retried clone/fetch never leaves
`ensureFresh()` treating a half-synced shadow tree as fresh, **so that** the Rabbit Hole named in
requirements.md ("retry/backoff interacting with the shadow-worktree freshness precondition") is
verified by a running test, not just `research/architecture.md`'s static code trace.

**Acceptance Criteria**:
- *Given* a clone that fails after `Git.cloneRepository().call()` succeeds but before
  `syncShadowAfterInitOrClone` runs (simulated interruption point), and is then retried to
  completion, *When* a subsequent `ensureFresh()` check runs, *Then* it does not report the
  shadow tree as fresh based on stale/absent manifest state from the failed first attempt — it
  correctly reflects the retried, now-complete clone.

**Files**: extend `AndroidGitRepositoryStorageGuardTest.kt`-style coverage (locate via `Glob` for
`*AndroidGitRepositoryStorageGuardTest*` / `*ShadowWorktree*Test*`) to the retry path.

##### Task 6.1.4a: Add the retry-vs-freshness regression test (~5 min)
- Files: existing `AndroidGitRepositoryStorageGuardTest.kt` (extend) or a new adjacent test file
