# Implementation Plan: app-owned-storage-clone

**Feature**: Unified storage-location picker (app-owned storage as a first-class destination) plus
guided relocate/link operations between storage backends, on Android and Web.
**Date**: 2026-09-12
**Status**: Ready for implementation
**ADRs**: ADR-001 (`StorageLocation` domain model), ADR-002 (`sweepOrphans()` storage-mode gate,
blocking), ADR-003 (plain-graph `AppOwned` backup mitigation + Android `Link` scope)

---

## Step 0.5 — Alternatives considered

Three high-level approaches were weighed before committing:

**A. Unified `StorageLocation` domain model + new seam coordinators, sequenced horizontally by
capability (picker → relocate → link) across both platforms per phase.** Strength: matches
`research/architecture.md`'s explicit recommendation to add new logic *around* the two hotspot files
(`GraphManager.kt`, highest churn; `GitSetupScreen.kt`, largest God-function) rather than into them,
and lets a usable slice (the picker) ship on both platforms before relocate/link complexity begins,
per requirements.md's appetite note. Weakness: higher upfront design cost — two new sealed
interfaces, a new coordinator class, and a new DB table must all land before the first user-visible
row appears.

**B. Extend existing per-platform code directly** (add an `AppOwned` branch straight into
`AddGraphDialog`/`GitSetupScreen`/`Sidebar`'s edit-graph dialog with a string/boolean flag, no new
domain type). Strength: fastest path to a visible picker row — no new types, no migration. Weakness:
perpetuates stringly-typed location handling with nothing for `sweepOrphans()`'s safety gate or a
future relocate/link operation to key off; directly contradicts `research/architecture.md` §4's
finding that `GraphManager.kt` and `GitSetupScreen.kt` are hotspots where new logic should be added
around, not into.

**C. Full vertical slices per platform** (ship Android's entire picker+relocate+link stack first,
then start Web from scratch). Strength: each platform is fully done before context-switching to the
other. Weakness: directly violates requirements.md's explicit sequencing instruction ("sequence work
so a usable slice — e.g. creation-time destination picker — ships... before the full move/link
matrix"), since a full Android relocate+link stack is itself most of the Large appetite budget before
Web users see anything.

**Chosen: A**, sequenced horizontally (Phase 1 foundation → Phase 2 picker on both platforms → Phase
3 relocate on both platforms → Phase 4 link on both platforms → Phase 5 hardening), so if appetite
runs out after Phase 2 or Phase 3, both platforms already have a shipped, valuable increment rather
than one platform being complete and the other untouched. Rejected alternatives B and C are also
recorded per-component in the Pattern Decisions table below.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `StorageLocation` | Sealed interface identifying where a graph's markdown content lives: `AppOwned`, `SafFolder`, `DirectAccessFolder`, or `HostFolder`. | Newtype-style sum type, not a flat enum — each case carries different fields. Never inferred; always the field read from `storage_locations`. |
| `StorageLocation.AppOwned` | The graph's markdown lives entirely inside the app's own storage (Android: `GitShadowWorktree`'s directory promoted to primary, or plain `filesDir` markdown; Web: OPFS). The only kind requiring zero user-granted permission. | |
| `StorageLocation.SafFolder` | Android only. Backed by a `content://` tree URI from `ACTION_OPEN_DOCUMENT_TREE`; write access can be revoked by the OS at any time. | Carries `treeUri: String`. |
| `StorageLocation.DirectAccessFolder` | Android only, requires `MANAGE_EXTERNAL_STORAGE`. Same real filesystem as `SafFolder` conceptually, but accessed via `java.io.File` for lower Binder-IPC latency. | Carries `realPath: String`. Internal-only — never its own row in the picker UI (ADR-001). |
| `StorageLocation.HostFolder` | Web only. Backed by a retained `FileSystemDirectoryHandle` via `HostDirectorySync`; permission can be lost across browser sessions. | Carries `displayName: String`. |
| `StorageMoveOperation` | Sealed interface for a user-chosen move: `Relocate` or `Link`. Never a merged/boolean-flagged single type. | |
| `StorageMoveOperation.Relocate` | One-time copy + verify + repoint + optional source cleanup. Source is only deleted after explicit user confirmation post-verification. | Carries `deleteSourceAfterVerify: Boolean`. |
| `StorageMoveOperation.Link` | Continuous mirror between source and destination; neither side is ever auto-deleted. | |
| `DomainError.StorageError` | New nested `sealed interface` family under the existing `DomainError`, for move-operation failures. | Leaves: `VerificationFailed`, `SourceInFlight`, `DestinationNotWritable`, `PartialCopyDetected`, `InsufficientSpace`, `QuiesceTimedOut`, `ReopenFailed` (driver failed to reopen after a relocate closed it — worse than the other leaves because the graph may be left uneditable; see Story 3.1.5's step 6/7 handling). |
| `GraphRelocationCoordinator` | New commonMain-orchestrated class that runs the quiesce → copy → verify → repoint → cleanup sequence for a `StorageMoveOperation`, delegating platform-specific quiesce/release work to the injected `GraphMoveQuiesceStrategy` port and persisting the outcome through `GraphManager.onGraphLocationDetermined` — never referencing an `androidMain`-only type directly, never growing `moveGraphFilesAndCredentials`/`updateGraphPath` in place. | One instance per in-flight move; Service Layer pattern (PoEAA). |
| `GraphMoveQuiesceStrategy` | New `commonMain` `interface GraphMoveQuiesceStrategy { suspend fun quiesce(op: StorageMoveOperation): Either<DomainError.StorageError, Unit>; suspend fun release(op: StorageMoveOperation); suspend fun releaseSourceGrant(source: StorageLocation) }` — the seam `GraphRelocationCoordinator` orchestrates against instead of calling `GitWorktreeLocks`/`GitSyncBusyCounter`/`GitWriteBackQueue`/`ContentResolver` (all `androidMain`-only) directly, which cannot compile in `commonMain` (KMP's platform-depends-on-common, never the reverse). | Strategy (GoF). `androidMain` impl wraps `GitWorktreeLocks`/`GitSyncBusyCounter`/`GitWriteBackQueue`/`WorkManagerSyncScheduler`/SAF grant release; `wasmJsMain` impl wraps `HostDirectorySync`'s poll-pause. A `businessTest` fake/no-op impl (with a configurable never-completes mode) lets the coordinator be tested on the JVM target, including the quiesce-timeout path. |
| `AtomicFileRelocationStep` | New standalone collaborator extracted from `moveGraphFilesAndCredentials`'s existing DB/WAL/SHM rename-with-rollback logic, reused by both that unchanged call site and `GraphRelocationCoordinator`'s markdown-repoint step. | Extract Method (Fowler); keeps `GraphManager.kt`'s function bodies from growing, per the Tech Debt Disposition's "Isolate via seam" choice for this file. |
| `GraphManager.onGraphLocationDetermined` | New narrow public seam, `fun onGraphLocationDetermined(graphId: String, location: StorageLocation)`, invoked exactly once near `addGraph()`'s existing registry-save point and once by `GraphRelocationCoordinator` post-relocate — the single place `storage_locations` is written, replacing four independent inline edits to `addGraph()`'s body plus an ordering-sensitive edit to `updateGraphPath()`. | Not a new type — a method seam. Referenced here because every creation/relocate task below calls into it rather than editing `GraphManager.kt` independently. |
| `StorageLocationResolver` | New `commonMain` port, `suspend fun resolveOrBackfill(graphId: String): StorageLocation`, called lazily the first time a pre-existing graph (one with no `storage_locations` row) needs a real source location — Android derives from an on-record SAF tree URI / `MANAGE_EXTERNAL_STORAGE` resolution / else `AppOwned`; Web derives from a connected `HostDirectorySync` handle / else `AppOwned`. Persists the derived row via `onGraphLocationDetermined` so later reads are never re-inferred. | Runs at "Move storage location" invocation time, not app-upgrade migration time — see Story 1.1.4. |
| `BulkCopyVerifier` | New primitive: chunked recursive tree copy from one `StorageLocation` to another, plus a real per-file content-hash verify step (using `ContentHasher.sha256(ByteArray)`, never the mtime/size shortcuts used by steady-state sync). | Does not exist today on either platform (confirmed absent, `research/features.md` §4). |
| `MoveInProgressFlag` | New coarse per-graph flag/state, checked as an early-exit guard by editing, background indexing, `GraphFileWatcher` polling, and git sync — set for the duration of any `GraphRelocationCoordinator` run. | Replaces the need to compose every existing platform-specific lock from outside (`research/architecture.md` §5.9). |
| `GitSyncBusyCounter` | New busy-counter primitive modeled on `EditLock` (`git/EditLock.kt`), incremented around `GitSyncService.sync()`'s body, so a relocate can detect "JGit commit/merge/push in flight" — a gap `GitWorktreeLocks` alone does not cover. | Android only. |
| `RelocationStagingDirectory` | A distinct staging path (never the final destination path) that a relocate's copy step writes into, with a marker file recording "in progress since T" — modeled on `GitShadowWorktree.sweepOrphans()`'s "ambiguous absence is never staleness" rule. | Swept at startup by a new sweep routine, analogous to `sweepOrphans()` but for interrupted relocates. |
| `StorageMoveUiState` | Sealed UI state for the relocate/link wizard's async operation, shaped like `FolderSyncSettings.ReconciliationUiState` (`Connecting`/`Quiescing`/`Copying`/`Verifying` → `Summary` \| `Failed`). | `Failed` never offers a "delete anyway" affordance. |
| `UnifiedLocationPicker` | New shared `commonMain` composable, generalized from `WikiSubdirBrowserDialog`'s shape, used by all three surfaces (new-graph dialog, `Step2RepoPath`, "move storage" action). Renders "App storage" as a pinned first row, "Browse…" second, no row pre-selected. | One implementation per platform's UI tree, not three. |
| `unlinkHostDirectory` | New command on `HostDirectorySync` — a user-invoked "detach from this folder, stay on OPFS" state transition. Does not resurrect the retired `disconnectForGraphSwitch()` field-by-field pattern. | Net-new; confirmed absent today. |
| `graphStorageMoveLog` | The existing app log/telemetry span mechanism, extended with structured entries for `MoveStarted`/`MoveVerified`/`MoveCompleted`/`MoveFailed`, keyed by `graphId`, `source`, `destination`. | Satisfies requirements.md's Observability Requirements. |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| `StorageLocation` | Sum type / sealed interface | type-driven-design | Flat enum (`GraphBackend`-style) | Needs per-case data (`treeUri`, `realPath`, `displayName`); a flat enum forces an external side-table for the same information (ADR-001). |
| `StorageMoveOperation` | Sum type / sealed interface | type-driven-design | Single data class with an enum `mode` field | `Relocate`'s `deleteSourceAfterVerify` is meaningless for `Link`; a sealed interface makes that invalid combination unrepresentable rather than defensively checked at runtime. |
| `DomainError.StorageError` | Sum type extending existing convention | type-driven-design / this repo's Either convention | Throwing exceptions or a bare `Boolean` return | Matches the `Either<DomainError, T>` boundary convention already enforced repo-wide (project `CLAUDE.md`). |
| `GraphRelocationCoordinator` | Service Layer (PoEAA) | Fowler | Growing `moveGraphFilesAndCredentials`/`updateGraphPath` in place inside `GraphManager.kt` | `GraphManager.kt` is the highest-churn file surveyed (40 commits) — isolate via seam per `research/architecture.md` §4, keep its own diff small and independently testable. |
| Picker UI (new-graph, `Step2RepoPath`, move-storage) | Generalize existing `WikiSubdirBrowserDialog` shape into one shared composable | This repo's own precedent | Three separate per-surface pickers (Approach B, rejected in Step 0.5) | requirements.md explicitly mandates one shared picker component per platform; three implementations would drift. |
| `GitShadowWorktree` promotion to primary storage | Strategy (GoF) — new branch at `AndroidGitRepository.shadowWorktreeFor()` | GoF | Rewriting `GitShadowWorktree`'s constructor to make `safRoot` optional throughout the class | `research/architecture.md` §4 rates `GitShadowWorktree.kt` "Extend as-is" (newest, cleanest, lowest-churn file surveyed) — a narrow additive branch keeps blast radius small; the constructor requirement is a real API constraint, not a SOLID violation. |
| JGit-operation-in-flight detection | New busy-counter (Observer-adjacent), modeled on `EditLock` | This repo's own precedent | Reusing `GitWorktreeLocks` alone | `GitWorktreeLocks` guards shadow-content sync/flush only, not JGit's own commit/merge/push execution (`research/architecture.md` §2.1) — a real gap, not redundant coverage. |
| Cross-cutting move exclusion | Single coarse per-graph `MoveInProgressFlag` (Guard/State) | This repo's own precedent (additive capability checks over lock composition) | Acquiring every existing lock (`GitWorktreeLocks` + `EditLock`-style counter + `HostDirectorySync` locks) from outside, in sequence | Composing independently-evolved lock sets across two platforms risks ordering/deadlock bugs (`research/architecture.md` §5.9); one flag checked by each existing mechanism is cheaper to reason about. |
| Bulk copy + verify | New hand-rolled `BulkCopyVerifier` | This repo's build-vs-buy precedent (`ContentHasher`) | Adopt `SimpleStorage` (Android), `browser-fs-access` (Web), or Apache Commons IO / Okio-as-shared-copy | None reach across both Android and wasmJs targets or fit the `Either` convention (`research/build-vs-buy.md` §1); each would need a full wrapping facade anyway, erasing the claimed savings. |
| Verify-before-delete integrity check | Reuse `ContentHasher.sha256(ByteArray)` per file | This repo's existing hashing primitive | The existing steady-state "cheap" comparisons (SAF batch-mtime cursors; `runHostReconciliation`'s `matchesBaseline` mtime/size check) | Those exist for polling performance, not integrity, and would under-detect exactly the class of encoding bug already shipped once (`e087959cb1`) — `research/architecture.md` §5.7. |
| Partial-copy cleanup | Staging directory + marker file + startup sweep (State/Memento-adjacent) | This repo's own precedent (`GitShadowWorktree.sweepOrphans()`) | Copy directly into the final destination path with no staging area | An interrupted direct-copy leaves an ambiguous partial state indistinguishable from a real graph on next launch — `research/pitfalls.md` §5. |
| Web `Link` mechanism | Extend `HostDirectorySync.connectHostDirectory` (existing Adapter over the File System Access API) | GoF (Adapter) / this repo's shipped code | Build a parallel `Link` implementation | `connectHostDirectory` is already a reconfigurable, mid-lifetime entry point (`research/architecture.md` §2.2) — a parallel implementation would duplicate working, tested machinery. |
| Web `Unlink` | New explicit state-transition command (`unlinkHostDirectory`) | This repo's own precedent, deliberately not reused | Resurrect the retired `disconnectForGraphSwitch()` field-by-field pattern | That pattern was deliberately retired (PR #293 round 2) to fix shared-mutable-state bugs; it solves a different problem (teardown for graph switch, not user-invoked detach) — `research/architecture.md` §2.2. |
| `sweepOrphans()` safety | Guard clause consulting persisted `StorageLocation` | This repo's own precedent, extended | Leave `sweepOrphans()` unchanged and document the risk only in a warning dialog | A UI warning does not stop a silent 60-day-idle deletion; the mechanism itself must be gated (ADR-002) — this is the single highest-severity finding in `research/architecture.md` §5.8. |
| Plain-graph `AppOwned` backup risk | Mandatory warning copy (both platforms) + a required export affordance on **both** platforms (Android: `java.util.zip.ZipOutputStream`; Web: hand-rolled stored-only ZIP writer, no new dependency) | Joplin's fail-safe precedent (`research/features.md` §1) | Defer plain-graph `AppOwned` entirely, build a full cross-device backup engine, or defer Web's export specifically (original ADR-003 text, reversed by its Amendment) | Deferring contradicts Success Metric 1; a full backup engine is explicitly out of scope per requirements.md; deferring Web's export alone was found by the Phase 4 pre-mortem to leave Web users with zero escape hatch if appetite runs out after Phase 2 — a proportionate local mitigation, shipped on both platforms before `AppOwned` is selectable for a plain graph, is the middle path (ADR-003 + Amendment). |
| `GraphRelocationCoordinator`'s quiesce/cleanup steps | Strategy (GoF) — `GraphMoveQuiesceStrategy` port, injected per platform | GoF / this repo's expect/actual-constructor precedent (`DriverFactory`) | `GraphRelocationCoordinator` in `commonMain` calling `GitWorktreeLocks`/`GitSyncBusyCounter`/`GitWriteBackQueue` directly | Those three types are `androidMain`-only; a `commonMain` class cannot reference them (KMP's platform-depends-on-common, one-way dependency) — this would not compile, and would make the story's own `businessTest` plan impossible to run against real platform code. |
| `moveGraphFilesAndCredentials` / `updateGraphPath` growth (relocate's file-move + repoint) | Extract Method (Fowler) — `AtomicFileRelocationStep` collaborator + `onGraphLocationDetermined` seam | Fowler / this repo's own Tech Debt Disposition for `GraphManager.kt` | Extending `moveGraphFilesAndCredentials` in place to also move markdown, and inverting `updateGraphPath()`'s driver-close ordering in place | Directly compounds the exact violation the Tech Debt Disposition table chose "Isolate via seam" to avoid for this 40-commit-churn file; extracting the rollback algorithm and adding one narrow seam reuses correctness-critical logic without growing either function. |
| `storage_locations` backfill for pre-existing graphs | Lazy derive-and-persist on first use (`StorageLocationResolver`), not an eager app-upgrade migration scan | This repo's own "graph-scale reads must be paginated... never O(graph)" precedent (project `CLAUDE.md`) | Backfill every existing graph's row at migration time | An eager scan is an O(graph-count) SAF-IPC/host-handle probe run unconditionally at every startup; the sweep gate (ADR-002) already treats a missing row as safely "not `AppOwned`," so nothing correctness-critical depends on backfilling before it's actually needed. |
| Git-cloned graph integrity verification (Task 3.1.1f) | Per-object/pack-file content hash via the existing `ContentHasher.sha256(ByteArray)` | This repo's own build-vs-buy precedent (reuse, no new dependency) | Object/ref-count comparison only | An object/ref-count match can pass across a torn-pack-file race (`research/pitfalls.md` §3) while history is corrupted; Story 3.1.1's own stated bar is real content-hash verification, and the same verifier already used for markdown needs no new primitive to reuse here. |
| Quiesce/drain bounding (Task in Story 3.1.5) | `withTimeoutOrNull` around the quiesce sequence, producing `DomainError.StorageError.QuiesceTimedOut` | This repo's own precedent (documented "silent indefinite hang" bug class, `stelekit_pages_backlink_count_migration_crash`) | Unbounded `awaitIdle()`/drain loop | `QuiesceTimedOut` already exists as a domain type with no task wiring it to an actual bound — an unbounded await repeats a documented hang-class bug rather than surfacing a visible `Failed` state. |
| Reopen-after-relocate confirmation (Task 3.1.5e) | Suspend on `GraphManager.awaitPendingMigration()` immediately after every `switchGraph(graphId, forceReinit = true)` call, treating the pair as one inseparable step | This repo's own precedent — `awaitPendingMigration()` is the existing, documented mechanism `openGraph()` already uses for exactly this ("you cannot access the repository before it is ready"); `createGitConfigRepository()` (`GraphManager.kt:1010-1012`) is a second existing precedent defensively null-checking for the same reason | Call `switchGraph(forceReinit = true)` alone and assume the reopen is synchronous | `switchGraph()` is fire-and-forget — it schedules the actual driver/repository-set creation on `graphScope.launch(PlatformDispatcher.IO) { ... }` and returns immediately (`GraphManager.kt:670-810`). Without awaiting, the coordinator's `onGraphLocationDetermined` write (success path) or its "graph is editable again" claim (failure path) can race a reopen that hasn't finished — a silent no-op write or a write against a not-yet-open connection, and a flaky regression test for Task 3.1.5h. |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt` | Outer `GitSetupScreen` composable (line 102) spans 521 lines, nests 10 levels deep, takes 17 parameters (flag-argument smell on 5+ booleans). | **Isolate via seam.** | Pre-existing God-function, not this project's to fix within a Large-but-bounded appetite. The new `UnifiedLocationPicker` is invoked *from* `Step2RepoPath`'s existing browse-button callback (`GitSetupScreen.kt:785-794`) without touching the outer function. Flagged as a separate future refactor candidate. |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` | Highest churn of all files surveyed (40 commits); six functions over 40 lines, one at 141 lines (`switchGraph`'s region); `addGraph()` already 53 lines. | **Isolate via seam.** | Highest-churn file is the riskiest place to add new inline logic. `moveGraphFilesAndCredentials`'s DB/WAL/SHM rename-with-rollback logic is extracted into a standalone `AtomicFileRelocationStep` collaborator (Story 3.1.4), reused by both the unchanged existing call site and `GraphRelocationCoordinator`'s markdown-repoint step — neither function grows. `updateGraphPath()` is left untouched; the coordinator performs its own close-driver-then-move sequence and repoints via the new `onGraphLocationDetermined(graphId, location)` seam (Story 1.1.3), which is also the single call site every creation flow (Phase 2) uses instead of editing `addGraph()`'s body at four independent call sites. |
| `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` | 1871 lines; two functions at 193/127 lines, 7-level nesting; comment-quality advisories. | **Extend as-is.** | Responsibilities are separated by cohesive suspend-function boundaries with no tangled cross-concern coupling (`research/architecture.md` §4) — new relocate/unlink methods slot in next to the existing connect/reconnect/request-access trio using the same idiom. A future file-split is flagged as separate tech debt, not a blocker. |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` | 1170 lines; a 6-line block duplicated 5× (lines 1043, 1092, 1105, 1121, 1135). | **Extend as-is.** | The existing `isDirectAccess()`/`resolveToRealPath()` capability-check-plus-resolver pattern is the established idiom for the kind of location-branching this feature needs. The duplicate block is adjacent tech debt, not a blocker for adding a `StorageLocation`-aware branch. |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt` | `safRoot`-required constructor is a real API constraint; otherwise clean (7 commits, lowest churn surveyed). | **Extend as-is.** | Newest, smallest, cleanest, lowest-churn file surveyed — no hotspot signal. A new construction path is additive, not a refactor. |

---

## Migration Plan

- **Migration file**: new `CREATE TABLE IF NOT EXISTS storage_locations (graph_id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, tree_uri TEXT, real_path TEXT, display_name TEXT, updated_at_epoch_ms INTEGER NOT NULL)` added to `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`, with a matching entry added to `MigrationRunner.all` in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt` (per this repo's mandatory migration rule — `MigrationRunnerSchemaSyncTest` enforces this at CI time).
- **Reversibility**: forward-only within this project (no down-migration tooling exists elsewhere in this codebase to model one on); a rollback is "stop writing to the table," not "drop it" — a dropped table on a rolled-back app version would strand any `AppOwned`-primary graph with an unreadable location on next upgrade. State this explicitly in the migration's own comment.
- **Zero-downtime strategy**: N/A in the distributed-systems sense (single-user local SQLite) — the operative constraint is that a graph created *before* this migration has no row, and its **absence must be read as "not `AppOwned`"** (preserves today's cache-only `sweepOrphans()` behavior for every pre-existing graph). Never infer `AppOwned` by omission.
- **Backfill for pre-existing graphs**: `sweepOrphans()` treating a missing row as "not `AppOwned`" is sufficient for the sweep gate alone, but the relocate/link flows (Phase 3) need a *real* source location for a graph that predates this feature, and ADR-001 forbids ephemeral inference — the value must come from the table. `StorageLocationResolver` (Story 1.1.4) closes this gap by deriving the row from the graph's actual current state (SAF tree URI / `MANAGE_EXTERNAL_STORAGE` / connected host handle, else `AppOwned`) and persisting it via `onGraphLocationDetermined` the first time "Move storage location" is invoked for that graph — so the row still always exists in the table by the time anything reads it as a *source*, only its creation is deferred to first use rather than done eagerly at migration time.
- **Rollback procedure**: revert to the previous app version; the table is simply unread by old code. No data is destroyed by a rollback since `storage_locations` only stores location metadata, never graph content.

## Observability Plan

- **Logs**: structured log lines (existing app log/telemetry span mechanism) at `MoveStarted` (graphId, source kind, destination kind, operation type), `MoveVerified` (pass/fail, file count, hash-mismatch count if any), `MoveCompleted`, `MoveFailed` (reason, `DomainError.StorageError` subtype) — per requirements.md's Observability Requirements. Every entry must be UI-visible on failure too (per `research/pitfalls.md` §6's backlink-count-migration-crash precedent: a silently-logged failure that hangs the UI repeats a known bug class).
- **Metrics**: `storage_move_duration_ms` (wall-clock time from `MoveStarted` to terminal state) — the one new operation plausibly over 100ms for an 8,000-page graph. No new dashboard/alerting infra (personal-scale app, no analytics pipeline).
- **Alerts**: no new alerting infra — not applicable to a personal-scale app (requirements.md's Observability Requirements section).

## Risk Control

- **Feature flag**: not gated — no feature-flag infrastructure exists in this codebase (requirements.md's Risk Control section); the copy-verify-confirm-before-delete design is itself the safety net.
- **Rollback procedure**: standard revert via PR close + revert commit per story/task; no data migration to unwind beyond the additive `storage_locations` table (see Migration Plan).
- **Staged rollout**: full rollout on merge, per story, matching this single-developer app's existing release process. Phase boundaries (1→5) are the de facto staging: each phase ships as its own mergeable increment, so Phase 2 (picker) can reach users before Phase 3/4 (relocate/link) is complete if appetite runs out, per requirements.md's explicit sequencing instruction.

## Unresolved Questions

None. All five Open Questions from requirements.md were resolved by Phase 2 research and are
incorporated directly into this plan:
- Integrity-check strength → content hash per file (`ContentHasher.sha256(ByteArray)`) for markdown,
  and the same primitive applied per git object/pack file for git-cloned graphs (Phase 3, Epic 3.1,
  Story 3.1.1 Task 3.1.1f) — a torn-pack-file race (`research/pitfalls.md` §3) could pass a
  count-only comparison, so both verification paths use the same real content-hash bar.
- `MANAGE_EXTERNAL_STORAGE` picker entry → modeled internally as `DirectAccessFolder`, never a
  separate picker row (ADR-001, Phase 1 Epic 1.1).
- `GitShadowFlushActor` quiesce mechanism → `GitWriteBackQueue.isEmpty()` + new `GitSyncBusyCounter`
  (Phase 1 Epic 1.3), consumed via the `GraphMoveQuiesceStrategy` port's Android implementation
  (Phase 3 Epic 3.1, Story 3.1.3) rather than referenced directly by the `commonMain` coordinator.
- `HostDirectorySync` link generalizability → already reconfigurable via `connectHostDirectory`; only
  the unlink command and picker-UI wiring are net-new (Phase 4 Epic 4.1).
- Undo-window UX → resolved by `ux.md`: copy-verify-confirm before any deletion, with an explicit
  "keep old copy" vs. "delete old copy" choice after verification succeeds (never a silent
  time-boxed auto-delete) — see Phase 3 Epic 3.4.
- Backfill for pre-existing graphs → `StorageLocationResolver`, lazily deriving and persisting an
  initial `storage_locations` row at first "Move storage location" invocation (Phase 1, Story 1.1.4;
  see Migration Plan above for the rationale on invocation-time vs. migration-time).

## Dependency Visualization

```
Phase 1 (Foundation, BLOCKING)
  1.1 StorageLocation/StorageMoveOperation/StorageError domain model + storage_locations table
      + onGraphLocationDetermined seam on GraphManager (Story 1.1.3)
    |
  1.1.4 StorageLocationResolver lazy backfill (needs 1.1.3's seam) ---- MUST land before 3.2.2/3.3.3 ship
    |
  1.2 sweepOrphans() storage-mode gate (ADR-002) ---- MUST land before 2.2 ships
    |
  1.3 GitSyncBusyCounter + MoveInProgressFlag + ContentHasher raw-bytes verify path
    |
    +-------------------------+-------------------------+
    |                                                     |
Phase 2 (Usable slice: creation-time picker, both platforms)
  2.1 UnifiedLocationPicker composable (commonMain)
    |         \
  2.2 Android integration (needs 1.2, 1.1's seam)      2.3 Web integration (needs 1.1's seam)
    | (2.2.1e zip-export MUST land before               | (2.3.3c/d zip-export MUST land before
    |  AppOwned is offered for plain graphs)            |  AppOwned is offered for plain graphs)
    |                                        |
    +-------------------+--------------------+
                         |
Phase 3 (Relocate, both platforms — needs 1.1, 1.3, 2.1)
  3.1 BulkCopyVerifier + RelocationStagingDirectory
    |
  3.1.3 GraphMoveQuiesceStrategy port + Android/Web strategy impls (needs 1.3's GitSyncBusyCounter)
    |
  3.1.4 AtomicFileRelocationStep extraction (needs 3.1's copy step)
    |
  3.1.5 GraphRelocationCoordinator (needs 3.1.3's port, 3.1.4's step, 1.1's onGraphLocationDetermined
        seam, and a new GraphManager.switchGraph(forceReinit) parameter (Task 3.1.5b) so a same-
        graphId relocate actually reopens the driver instead of hitting switchGraph's idempotency
        guard; every reopen also suspends on GraphManager's pre-existing awaitPendingMigration() —
        switchGraph() alone only schedules the reopen, it doesn't wait for it (Task 3.1.5e) — and
        treats a null (genuine reopen failure) as the new DomainError.StorageError.ReopenFailed leaf,
        surfaced as StorageMoveUiState.ReopenFailed (Task 3.1.5m, Story 1.1.2), distinct from the
        ordinary Retry/Cancel Failed flow; wires QUIESCE_TIMEOUT_MS -> DomainError.StorageError.QuiesceTimedOut)
    |            \
  3.2 Android relocate wiring       3.3 Web relocate wiring
    (3.2.1 extends 3.1.3's Android      (3.3.2 extends 3.1.3's Web
     strategy impl; 3.2.2 needs 1.1.4)   strategy impl; 3.3.3 needs 1.1.4)
    |                                |
    +---------------+----------------+
                     |
  3.4 Relocate confirmation & progress UI (needs 3.2, 3.3)
                     |
Phase 4 (Link, both platforms — needs 3.1.5's coordinator shape)
  4.1 Web link generalization (connectHostDirectory + unlinkHostDirectory)
  4.2 Android link mode (git-cloned graphs only, per ADR-003)
                     |
Phase 5 (Hardening)
  5.1 Observability wiring
  5.2 SAF grant lifecycle hygiene (release old grants on confirmed relocate)
  5.3 Live storage-migration regression test infrastructure
```

---

## Phase 1: Foundation and blocking safety gate

### Epic 1.1: `StorageLocation` domain model
**Goal**: Give every later story a queryable, persisted domain type for "where does this graph's
content live," per ADR-001.

#### Story 1.1.1: Add `StorageLocation` and `StorageMoveOperation` sealed interfaces
**As a** developer building the relocate/link/picker features, **I want** a typed domain model for
storage locations and move operations, **so that** later code cannot represent an invalid
combination (e.g. a `Link` with `deleteSourceAfterVerify` set).
**Acceptance Criteria**:
- `StorageLocation` is a `sealed interface` with `AppOwned`, `SafFolder`, `DirectAccessFolder`,
  `HostFolder` data classes, each carrying `graphId: String` plus its kind-specific field.
  - *Given* a `StorageLocation.SafFolder(graphId = "g1", treeUri = "content://com.android...")`,
    *When* code pattern-matches on `StorageLocation` with a `when` expression missing the
    `DirectAccessFolder` branch, *Then* the Kotlin compiler reports a non-exhaustive `when` error
    (sealed interface, no `else` branch used in production code).
- `StorageMoveOperation` is a `sealed interface` with `Relocate` and `Link` data classes, each
  carrying `graphId`, `source: StorageLocation`, `destination: StorageLocation`; only `Relocate`
  carries `deleteSourceAfterVerify: Boolean`.
  - *Given* `StorageMoveOperation.Link(graphId = "g1", source = AppOwned("g1"), destination =
    HostFolder("g1", "Documents"))`, *When* code attempts `operation.deleteSourceAfterVerify`,
    *Then* it is a compile error (the field does not exist on `Link`).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/StorageLocation.kt` (new),
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/StorageMoveOperation.kt` (new)

##### Task 1.1.1a: Create `StorageLocation.kt` (~4 min)
- Add the sealed interface and four data-class leaves, each with a KDoc line matching the Domain
  Glossary's one-sentence definition (per this repo's "docstrings on entry points only" comment
  convention).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/StorageLocation.kt`

##### Task 1.1.1b: Create `StorageMoveOperation.kt` (~3 min)
- Add the sealed interface and two data-class leaves.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/StorageMoveOperation.kt`

##### Task 1.1.1c: `commonTest` exhaustiveness + equality tests (~4 min)
- One test asserting a `when` over `StorageLocation` compiles exhaustively (a compile-time check
  disguised as a test — add a new leaf in a throwaway local sealed copy is not needed; instead
  assert `StorageLocation::class.sealedSubclasses.size == 4` so a future added leaf without a test
  update is caught) and one asserting `StorageMoveOperation::class.sealedSubclasses.size == 2`.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/model/StorageLocationTest.kt` (new)

#### Story 1.1.2: Add `DomainError.StorageError` family
**As a** developer implementing relocate/link operations, **I want** typed error returns for every
move-failure mode, **so that** callers use `Either.fold`/`onLeft` instead of catching generic
exceptions.
**Acceptance Criteria**:
- `DomainError.StorageError` is a nested `sealed interface` under `DomainError` with leaves
  `VerificationFailed(path: String, reason: String)`, `SourceInFlight(reason: String)`,
  `DestinationNotWritable(path: String)`, `PartialCopyDetected(path: String)`,
  `InsufficientSpace(requiredBytes: Long, availableBytes: Long)`, `QuiesceTimedOut(waitedMs: Long)`,
  `ReopenFailed(graphId: String)`.
  - *Given* a destination write probe fails with a permission error on a `SafFolder` destination,
    *When* `GraphRelocationCoordinator` catches it, *Then* it returns
    `DomainError.StorageError.DestinationNotWritable(path = "content://...").left()`, not a thrown
    exception.
  - *Given* `GraphManager.awaitPendingMigration()` returns `null` after `GraphRelocationCoordinator`
    calls `switchGraph(graphId, forceReinit = true)` to reopen the driver post-relocate, *When* the
    coordinator handles it, *Then* it treats this as
    `DomainError.StorageError.ReopenFailed(graphId.value)` — a distinct, worse-than-`Failed` outcome
    (Story 3.1.5), since unlike every other leaf here, the
    driver may now be stuck closed rather than safely back at its pre-move state.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 1.1.2a: Add the `StorageError` nested sealed interface (~4 min)
- Insert alongside the existing `DatabaseError`/`FileSystemError`/`GitError`/`ConflictError`
  families, following their exact `data class`/`val message: String` shape.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 1.1.2b: `commonTest` for message formatting (~3 min)
- One test per leaf asserting `.message` is non-blank and includes the relevant field (e.g.
  `InsufficientSpace`'s message mentions both byte counts).
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/DomainErrorStorageErrorTest.kt` (new)

#### Story 1.1.3: Persist `StorageLocation` in a new `storage_locations` table
**As a** developer, **I want** each graph's `StorageLocation` durably persisted, **so that**
`sweepOrphans()` (Epic 1.2) and the picker (Phase 2) can read it back after an app restart.
**Acceptance Criteria**:
- `SteleDatabase.sq` gains `CREATE TABLE IF NOT EXISTS storage_locations (graph_id TEXT NOT NULL
  PRIMARY KEY, kind TEXT NOT NULL, tree_uri TEXT, real_path TEXT, display_name TEXT,
  updated_at_epoch_ms INTEGER NOT NULL)` plus `selectStorageLocation(graphId)`,
  `upsertStorageLocation(...)` queries.
  - *Given* a fresh app install with no `storage_locations` row for `graphId = "g1"`, *When*
    `selectStorageLocation("g1")` runs, *Then* it returns `null` (no row), and calling code
    interprets that as "not `AppOwned`."
- `MigrationRunner.all` includes an entry creating `storage_locations`, verified by
  `MigrationRunnerSchemaSyncTest` (existing test, no changes needed — it auto-discovers new
  `IF NOT EXISTS` tables).
  - *Given* an existing on-disk database from before this migration, *When* `MigrationRunner.applyAll()`
    runs, *Then* `storage_locations` is created without touching any existing table's data.
- `upsertStorageLocation` is a `@DirectSqlWrite`-gated write, routed through
  `DatabaseWriteActor`/`RestrictedDatabaseQueries` per this repo's write-enforcement convention —
  never called directly on `SteleDatabaseQueries`.
- `GraphManager` gains one new narrow public seam, `fun onGraphLocationDetermined(graphId: String,
  location: StorageLocation)`, invoked exactly once near `addGraph()`'s existing registry-save point
  (line ~235) when `addGraph()` is called with a non-null `location`, and callable independently by
  `GraphRelocationCoordinator` (Phase 3) after a successful relocate. This is the **only** place
  `storage_locations` is written from a creation or relocate flow — every later Phase 2/3 task calls
  into it rather than editing `addGraph()`'s body or `updateGraphPath()`'s ordering independently
  (Tech Debt Disposition's "Isolate via seam" for `GraphManager.kt`, the highest-churn file
  surveyed).
  - *Given* `addGraph(..., location = StorageLocation.AppOwned("g1"))` is called, *When* the graph is
    registered, *Then* `onGraphLocationDetermined` runs exactly once and `storage_locations` has a
    `kind = "AppOwned"` row for `"g1"` — with no separate inline `upsertStorageLocation` call added
    at the four Phase 2 creation call sites.
**Files**: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

##### Task 1.1.3a: Add table + queries to `SteleDatabase.sq` (~5 min)
- Add the `CREATE TABLE IF NOT EXISTS`, `selectStorageLocation`, `upsertStorageLocation` (as an
  `INSERT OR REPLACE`), `deleteStorageLocation` query blocks.
- Files: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`

##### Task 1.1.3b: Regenerate SQLDelight sources + commit generated output (~3 min)
- Run `./gradlew :kmp:generateCommonMainSteleDatabase` and `rsync` into
  `kmp/src/generated/sqldelight/` per this repo's mandatory Bazel-codegen-sync step (project
  `CLAUDE.md`'s Bazel Build Commands section).
- Files: `kmp/src/generated/sqldelight/` (generated, committed)

##### Task 1.1.3c: Add `MigrationRunner.all` entry (~3 min)
- Add the new table's `CREATE TABLE IF NOT EXISTS` statement to `MigrationRunner.all`'s list,
  matching `SteleDatabase.sq` verbatim (per this repo's mandatory-migration rule).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`

##### Task 1.1.3d: Add `@DirectSqlWrite` forwarding stub (~3 min)
- Add `upsertStorageLocation`/`deleteStorageLocation` forwarding methods to
  `RestrictedDatabaseQueries`, annotated `@DirectSqlWrite`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt`

##### Task 1.1.3e: `businessTest` for persistence round-trip (~4 min)
- Test: upsert an `AppOwned` location, read it back via `selectStorageLocation`, assert kind and
  `graphId` match; test that a graph with no row returns `null`.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/StorageLocationPersistenceTest.kt` (new)

##### Task 1.1.3f: Add `onGraphLocationDetermined` seam + `location` parameter to `addGraph()` (~4 min)
- Add `onGraphLocationDetermined(graphId, location)` (calls `upsertStorageLocation` via the actor),
  and an optional `location: StorageLocation? = null` parameter to `addGraph()` that invokes it once,
  near the existing registry-save point, when non-null. This is the single seam every Phase 2
  creation call site (Tasks 2.2.1c, 2.2.2c, 2.3.1c, 2.3.2b) and Phase 3's `GraphRelocationCoordinator`
  (Story 3.1.5) call — no other `GraphManager.kt` edits are needed for `storage_locations` writes.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`

##### Task 1.1.3g: `businessTest` — `onGraphLocationDetermined` writes exactly once per `addGraph()` call (~3 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerOnGraphLocationDeterminedTest.kt` (new)

#### Story 1.1.4: Lazily backfill `storage_locations` for pre-existing graphs
**As a** developer implementing the relocate/link "Move storage location" flow, **I want** a graph
that predates this feature to get a real, persisted `StorageLocation` the first time it's needed,
**so that** `GraphRelocationCoordinator` and the confirmation dialog (Story 3.4.2) have an actual
`source: StorageLocation` to relocate from and display, not an inference done ad hoc at the call
site (which ADR-001 forbids).
**Decision — when it runs**: at first "Move storage location" invocation (Stories 3.2.2/3.3.3), not
at app-upgrade migration time. Rationale: an eager migration-time scan would probe every existing
graph's SAF tree URI / host handle unconditionally at startup — an O(graph-count) SAF-IPC/host-handle
walk this repo's own "graph-scale reads must be paginated... never O(graph)" rule (project
`CLAUDE.md`) exists specifically to avoid; the sweep gate (Epic 1.2, ADR-002) already treats a
missing row as safely "not `AppOwned`," so nothing correctness-critical needs the row before the
relocate/link flow is the one asking for it.
**Acceptance Criteria**:
- `StorageLocationResolver.resolveOrBackfill(graphId: String): StorageLocation` returns the existing
  persisted row if one exists (no re-derivation); otherwise derives one and persists it via
  `onGraphLocationDetermined` before returning.
  - *Given* `graphId = "g6"` already has a `storage_locations` row of kind `SafFolder`, *When*
    `resolveOrBackfill("g6")` runs, *Then* it returns that row unchanged and performs no SAF/host
    probe.
- Android derivation: an on-record SAF tree URI for the graph → `SafFolder`; else a
  `MANAGE_EXTERNAL_STORAGE` direct-access resolution to a real path → `DirectAccessFolder`; else →
  `AppOwned`.
  - *Given* `graphId = "g7"` has no `storage_locations` row but has an on-record SAF tree URI
    `"content://.../Notes"`, *When* `resolveOrBackfill("g7")` runs, *Then* it persists and returns
    `StorageLocation.SafFolder("g7", "content://.../Notes")`.
- Web derivation: a connected `HostDirectorySync` handle for the graph → `HostFolder`; else →
  `AppOwned`.
  - *Given* `graphId = "g8"` has no `storage_locations` row and no connected host handle, *When*
    `resolveOrBackfill("g8")` runs, *Then* it persists and returns `StorageLocation.AppOwned("g8")`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/StorageLocationResolver.kt` (new, `expect` port),
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/StorageLocationResolver.android.kt` (new),
`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/StorageLocationResolver.wasmJs.kt` (new)

##### Task 1.1.4a: Define the `StorageLocationResolver` port + the persisted-row short-circuit (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/StorageLocationResolver.kt`

##### Task 1.1.4b: Android derivation (SAF tree URI record, `MANAGE_EXTERNAL_STORAGE`, else `AppOwned`) (~5 min)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/StorageLocationResolver.android.kt`

##### Task 1.1.4c: Web derivation (connected `HostDirectorySync` handle, else `AppOwned`) (~4 min)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/StorageLocationResolver.wasmJs.kt`

##### Task 1.1.4d: Android unit test + wasmJs test — each derivation branch persists exactly once and short-circuits on re-call (~5 min)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/db/StorageLocationResolverTest.kt` (new),
  `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/db/StorageLocationResolverTest.kt` (new)

---

### Epic 1.2: `sweepOrphans()` storage-mode gate (BLOCKING — must land before Phase 2's Android stories)
**Goal**: Prevent `GitShadowWorktree.sweepOrphans()` from silently deleting a user's sole data copy
once `AppOwned` becomes a selectable primary destination, per ADR-002.

#### Story 1.2.1: Gate `sweepOrphans()` on persisted `StorageLocation`
**As a** SteleKit user who has chosen app-owned storage for a graph, **I want** the shadow-worktree
orphan sweep to never delete my only copy, **so that** a 60-day gap in opening the app does not
cause total data loss.
**Acceptance Criteria**:
- `sweepOrphans()` looks up `StorageLocation` for each shadow directory's `graphId` before deleting
  it on age grounds; a directory whose location is `AppOwned` is skipped unconditionally, regardless
  of `.last-used` marker age.
  - *Given* a shadow directory for `graphId = "g2"` with a `storage_locations` row of kind
    `AppOwned` and a `.last-used` marker dated 90 days ago (older than `DEFAULT_MAX_AGE_MILLIS`),
    *When* `sweepOrphans()` runs, *Then* the directory for `g2` is **not** deleted.
- A shadow directory with **no** `storage_locations` row (every graph predating this migration)
  retains today's exact behavior — swept if older than `maxAgeMillis`.
  - *Given* a shadow directory for `graphId = "g3"` with no `storage_locations` row and a
    `.last-used` marker dated 90 days ago, *When* `sweepOrphans()` runs, *Then* the directory for
    `g3` **is** deleted, identical to pre-feature behavior.
- The `StorageLocation` lookup failing (e.g. a DB error) for one directory does not abort the sweep
  for other directories — wrapped per-directory, not around the whole loop.
  - *Given* `selectStorageLocation("g4")` throws during the sweep loop, *When* `sweepOrphans()`
    processes `g4` and then `g5` (a normal, sweepable directory), *Then* `g4` is left untouched
    (treated as "unknown, do not delete" per fail-safe default) and `g5` is still correctly swept.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.2.1a: Add `StorageLocation`-aware guard to `sweepOrphans()` (~5 min)
- At `GitShadowWorktree.kt:405-423`, before the age-based delete, query
  `selectStorageLocation(graphId)` (via the existing DB access path already available to this
  class) and skip deletion when the result is `AppOwned` or the lookup throws.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.2.1b: Verify `.last-used` marker update triggers (~4 min)
- Per ADR-002's explicit caution ("verify the marker's actual update triggers, don't assume 'app
  opened' is a reliable proxy"), read every call site that touches `LAST_USED_FILE_NAME` and confirm
  it is updated on every graph *open*, not just app launch. Document the finding as a one-line
  comment at the marker-write call site if it differs from "on graph open."
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.2.1c: Android instrumented/unit test — `AppOwned` graph survives an aged sweep (~5 min)
- Construct a shadow directory with an artificially aged marker and an `AppOwned` row; assert
  `sweepOrphans()` does not delete it. Construct a second directory with no row and an aged marker;
  assert it is deleted (regression guard for the "no change to existing behavior" criterion).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeSweepStorageGateTest.kt` (new)

---

### Epic 1.3: New coordination primitives
**Goal**: Build the busy-counter and move-in-progress primitives that Phase 3's
`GraphRelocationCoordinator` will consume, and confirm the correct `ContentHasher` entry point, so
Phase 3 does not have to invent these mid-flight.

#### Story 1.3.1: `GitSyncBusyCounter` (Android)
**As a** developer building the Android relocate quiesce sequence, **I want** to detect "a JGit
commit/merge/push is currently running," **so that** a relocate never copies `.git` mid-operation.
**Acceptance Criteria**:
- A new `MutableStateFlow<Int>`-backed busy-counter, incremented at the start and decremented at the
  end of `GitSyncService.sync()`'s body, with an `awaitIdle()` suspend function mirroring
  `EditLock.awaitIdle()`'s shape.
  - *Given* `GitSyncService.sync()` is mid-execution (counter value `1`), *When* a caller invokes
    `GitSyncBusyCounter.awaitIdle()`, *Then* the suspend call does not return until `sync()`
    completes and the counter returns to `0`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncBusyCounter.kt` (new),
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 1.3.1a: Create `GitSyncBusyCounter.kt` modeled on `EditLock` (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncBusyCounter.kt`

##### Task 1.3.1b: Wire increment/decrement around `GitSyncService.sync()` (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 1.3.1c: `commonTest` for counter increment/decrement/`awaitIdle` (~4 min)
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitSyncBusyCounterTest.kt` (new)

#### Story 1.3.2: `MoveInProgressFlag`
**As a** developer, **I want** one coarse per-graph flag that editing, indexing, file-watching, and
git sync all check as an early-exit condition, **so that** a relocate/link operation doesn't need to
acquire every existing platform-specific lock individually.
**Acceptance Criteria**:
- A new `commonMain` object/class exposing `isMoveInProgress(graphId): Boolean` and
  `setMoveInProgress(graphId, inProgress: Boolean)`, backed by an in-memory
  `MutableStateFlow<Set<String>>` (no persistence needed — a crash mid-move already leaves a
  detectable staging marker, per Epic 3.1's `RelocationStagingDirectory`).
  - *Given* `setMoveInProgress("g1", true)` has been called, *When* `GraphFileWatcher` polls for
    `graphId = "g1"`, *Then* it checks `isMoveInProgress("g1")` and skips its poll cycle for that
    graph.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MoveInProgressFlag.kt` (new)

##### Task 1.3.2a: Create `MoveInProgressFlag.kt` (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MoveInProgressFlag.kt`

##### Task 1.3.2b: Add early-exit checks to `GraphFileWatcher`'s poll loop (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt` (exact watcher file per platform — apply to the `commonMain` polling entry point if shared, else both `androidMain`/`wasmJsMain` actuals)

##### Task 1.3.2c: `commonTest` for flag set/clear/query (~3 min)
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/MoveInProgressFlagTest.kt` (new)

#### Story 1.3.3: Confirm and lock in the `ContentHasher` raw-bytes verify entry point
**As a** developer implementing `BulkCopyVerifier` in Phase 3, **I want** a test asserting
`ContentHasher.sha256(ByteArray)` is byte-exact (no whitespace normalization), **so that** a future
edit to `ContentHasher` cannot silently regress relocate's integrity check into using the
block-dedup-oriented `sha256ForContent` overload instead.
**Acceptance Criteria**:
- A `commonTest` asserts `ContentHasher.sha256(byteArrayOf(...))` produces different digests for two
  byte arrays that differ only in trailing whitespace, proving no normalization occurs on this
  overload (contrasted with `sha256ForContent`, which does normalize).
  - *Given* `bytesA = "hello".encodeToByteArray()` and `bytesB = "hello ".encodeToByteArray()`,
    *When* both are passed to `ContentHasher.sha256(ByteArray)`, *Then* the two returned digests are
    different.
**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/util/ContentHasherRawBytesTest.kt` (new)

##### Task 1.3.3a: Add the byte-exactness regression test (~3 min)
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/util/ContentHasherRawBytesTest.kt`

---

## Phase 2: Usable slice — creation-time unified picker (Android + Web)

**Phase-2 exit gate**: per ADR-003's Amendment (2026-09-12 pre-mortem repair), `AppOwned` must not
ship as a selectable destination for a **plain** graph on either platform until that platform's
export affordance (Story 2.2.1, Task 2.2.1e on Android; Story 2.3.3, Tasks 2.3.3c/d on Web) is
also shipped. This phase's "usable slice" is not just the picker — it is the picker plus an escape
hatch for the one storage choice this project cannot otherwise undo if appetite runs out before
Phase 3/4.

### Epic 2.1: `UnifiedLocationPicker` composable
**Goal**: One shared `commonMain` composable, used by every surface in Phase 2 and Phase 3, per
requirements.md's explicit "no more than one picker implementation per platform" constraint.

#### Story 2.1.1: Build `UnifiedLocationPicker`
**As a** SteleKit user creating a graph or choosing where to store one, **I want** a single picker
showing "App storage" as a pinned first row above a folder-browse option, **so that** I don't have to
find a separate toggle before reaching the picker.
**Acceptance Criteria**:
- The composable renders: a pinned "App storage" row (with the platform-specific subtitle from
  `ux.md` §2) as the first, always-visible entry; a "Browse…" row second; no row pre-selected by
  default (per `ux.md` §5's explicit no-default-selection recommendation).
  - *Given* the picker is opened from `Step2RepoPath` on Android, *When* it first renders, *Then*
    neither "App storage" nor "Browse…" shows a selected/checked state, and the caller's "Next"/
    confirm action is disabled until one row is tapped.
- Selecting "App storage" returns `StorageLocation.AppOwned(graphId)` to the caller; selecting
  "Browse…" launches the platform-native picker (`pickDirectoryAsync()` on Android,
  `showDirectoryPicker()` on Web) and returns the resulting `SafFolder`/`HostFolder`.
  - *Given* a user taps "Browse…" on Web, *When* `showDirectoryPicker()` resolves with a handle
    named `"Documents"`, *Then* the picker returns `StorageLocation.HostFolder(graphId, "Documents")`.
- Accessibility: the "App storage" and "Browse…" rows use `Modifier.semantics { role = Role.Button
  }.clickable(...)`, matching this repo's existing row-selection convention
  (`GitSetupScreen.kt`'s `Step1CloneMode`); any lone icon carries a real `contentDescription`.
  - *Given* a screen-reader user focuses the "App storage" row, *When* the reader announces it,
    *Then* it announces the row's label and subtitle text, not a bare icon name.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPicker.kt` (new)

##### Task 2.1.1a: Scaffold the composable from `WikiSubdirBrowserDialog`'s shape (~5 min)
- Copy the breadcrumb-dialog structure (`segments`, `FileSystem.listDirectories`, `LaunchedEffect`)
  from `GitSetupScreen.kt:862-935` into the new shared file, generalized to accept a
  `platformCapabilities` parameter (whether native folder browse is available — always true on
  Android, conditional on Web per `supportsNativeDirectoryPicker`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPicker.kt`

##### Task 2.1.1b: Add the pinned "App storage" row with platform subtitle copy (~4 min)
- Use `ux.md` §2's exact subtitle shape: Android — "Kept inside SteleKit only — not visible in your
  device's file manager, and removed if you uninstall the app."; Web — "Kept in this browser only —
  not backed up automatically, and lost if you clear site data."
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPicker.kt`

##### Task 2.1.1c: Wire no-default-selection + confirm-button gating (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPicker.kt`

##### Task 2.1.1d: Accessibility pass — semantics roles and content descriptions (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPicker.kt`

##### Task 2.1.1e: `jvmTest`/screenshot test for the picker's default (no-selection) state (~4 min)
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPickerTest.kt` (new)

---

### Epic 2.2: Android creation-time integration
**Goal**: Wire `UnifiedLocationPicker` into the Android new-graph dialog and `Step2RepoPath`,
depends on Epic 1.2 (sweep gate) already having shipped.

#### Story 2.2.1: `AppOwned` option in the Android new-graph dialog
**As an** Android user creating a plain (non-git) graph, **I want** to choose app storage with zero
SAF grant required, **so that** I don't have to navigate a folder picker for a graph I want fully
local.
**Acceptance Criteria**:
- The Android new-graph creation flow (equivalent of `AddGraphDialog` on Web) renders
  `UnifiedLocationPicker`; choosing "App storage" creates the graph rooted at `filesDir`-backed
  storage with zero `ACTION_OPEN_DOCUMENT_TREE` launch.
  - *Given* a user on Android taps "New graph," selects "App storage," and confirms, *When* graph
    creation completes, *Then* no SAF tree-picker `Intent` was ever launched, and
    `storage_locations` has a row for the new `graphId` with `kind = "AppOwned"`.
- The mandatory plain-graph warning (ADR-003) is shown before creation completes, with the
  Android-only "Export as .zip" affordance inline.
  - *Given* the user has selected "App storage" for a plain graph, *When* the warning dialog
    renders, *Then* it shows the exact copy "...permanently deleted if you uninstall the app. There
    is no automatic backup." and an "Export as .zip" button.
**Files**: Android new-graph creation composable/screen (equivalent of `App.kt`'s `AddGraphDialog`
for Android — locate via `Glob`/`sg` for the Android-specific new-graph entry point before editing;
if Android shares `AddGraphDialog` with Web, edit `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` directly)

##### Task 2.2.1a: Replace the folder-only new-graph destination control with `UnifiedLocationPicker` (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (`AddGraphDialog`, ~line 2084)

##### Task 2.2.1b: Wire `AppOwned` selection to skip `pickDirectoryAsync()`/SAF entirely (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 2.2.1c: Pass the selected `StorageLocation` into the existing `addGraph(..., location = ...)` call (~3 min)
- No `GraphManager.kt` edit here — Task 1.1.3f's `onGraphLocationDetermined` seam handles
  persistence once `addGraph()` is called with a non-null `location`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (`AddGraphDialog`'s
  `addGraph(...)` call site)

##### Task 2.2.1d: Add plain-graph warning dialog + zip-export button (~5 min)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/PlainGraphAppOwnedWarningDialog.android.kt` (new, or shared composable with an Android-only export slot)

##### Task 2.2.1e: Implement Android zip-export using `java.util.zip.ZipOutputStream` (~5 min)
- Recursively read the graph's markdown files via `FileSystem`, write a zip to a user-chosen
  location via the existing share/save-file mechanism (`PlatformShareProvider.kt`).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/export/GraphZipExporter.android.kt` (new)

##### Task 2.2.1f: Android unit test — creating a graph with `AppOwned` launches no SAF intent (~4 min)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/AddGraphAppOwnedTest.kt` (new)

#### Story 2.2.2: `AppOwned` option in Android's `GitSetupScreen.Step2RepoPath`
**As an** Android user cloning a git repository, **I want** to choose app storage as the clone
destination with zero SAF grant required, **so that** git-clone works exactly as smoothly as a plain
graph.
**Acceptance Criteria**:
- `Step2RepoPath`'s browse control is replaced with `UnifiedLocationPicker`; selecting "App storage"
  clones into the promoted `GitShadowWorktree` directory as primary storage (not cache).
  - *Given* a user on `Step2RepoPath` selects "App storage" and confirms a clone of
    `https://github.com/example/notes.git`, *When* cloning completes, *Then* `AndroidGitRepository`
    used the new `AppOwned` branch at `shadowWorktreeFor()` (no `pathResolver(repoRoot)` SAF
    resolution ever ran), and `storage_locations` records `kind = "AppOwned"` for the new graph.
- Existing SAF-folder clone behavior is unchanged for users who select "Browse…" instead.
  - *Given* a user selects "Browse…" and picks a SAF folder, *When* cloning completes, *Then*
    behavior is byte-for-byte identical to pre-feature `Step2RepoPath` (same `GitShadowWorktree`
    cache-mode write-back).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt` (Step2RepoPath, lines 742-853),
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt` (`shadowWorktreeFor()`, lines 68-76)

##### Task 2.2.2a: Add the new `AppOwned` branch at `AndroidGitRepository.shadowWorktreeFor()` (~5 min)
- Add a third branch: when `StorageLocation` for this clone is `AppOwned`, construct the shadow
  worktree with no SAF resolution and mark it primary (skip `syncFromSafRoot()`/write-back queue
  entirely).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 2.2.2b: Replace `Step2RepoPath`'s browse button with `UnifiedLocationPicker` (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`

##### Task 2.2.2c: Pass the selected `StorageLocation` into the existing `addGraph(..., location = ...)` call for the cloned graph (~3 min)
- No `GraphManager.kt` edit here — reuses Task 1.1.3f's `onGraphLocationDetermined` seam.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`

##### Task 2.2.2d: Android instrumented test — clone into `AppOwned` never touches SAF (~5 min)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryAppOwnedCloneTest.kt` (new)

---

### Epic 2.3: Web creation-time integration
**Goal**: Promote OPFS from a fallback-only default to a peer, always-visible picker row on Web,
and add the missing `Step2RepoPath` app-storage option there.

#### Story 2.3.1: Promote OPFS to a peer entry in `AddGraphDialog`
**As a** Web user with a Chromium browser, **I want** "App storage" (OPFS) offered as an equal
choice alongside "pick a real folder," **so that** I'm not limited to OPFS only when the File System
Access API happens to be unsupported.
**Acceptance Criteria**:
- `AddGraphDialog` renders `UnifiedLocationPicker` instead of its current fallback-only OPFS
  framing; on Firefox/Safari (no `showDirectoryPicker`), only "App storage" is shown (no dead
  "Browse…" row), per `stack.md`'s confirmed two-tier browser support finding.
  - *Given* a user on Firefox opens the new-graph dialog, *When* `UnifiedLocationPicker` renders,
    *Then* it shows only the "App storage" row (no "Browse…" row at all, not a disabled one).
  - *Given* a user on Chrome opens the new-graph dialog, *When* `UnifiedLocationPicker` renders,
    *Then* both "App storage" and "Browse…" are shown as equal, unselected rows.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (`AddGraphDialog`, ~line 2084-2116)

##### Task 2.3.1a: Replace `AddGraphDialog`'s OPFS-fallback branch with `UnifiedLocationPicker` (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 2.3.1b: Conditionally hide "Browse…" using `supportsNativeDirectoryPicker` (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPicker.kt`

##### Task 2.3.1c: Pass the selected `StorageLocation` (`AppOwned`/`HostFolder`) into the existing `addGraph(..., location = ...)` call on Web (~3 min)
- No `GraphManager.kt` edit here — reuses Task 1.1.3f's `onGraphLocationDetermined` seam.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (`AddGraphDialog`'s
  `addGraph(...)` call site)

##### Task 2.3.1d: wasmJs test — Firefox/Safari path shows no "Browse…" row (~4 min)
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/AddGraphDialogPickerTest.kt` (new)

#### Story 2.3.2: `AppOwned` option in Web's `GitSetupScreen.Step2RepoPath`
**As a** Web user cloning a git repository, **I want** to clone directly into OPFS, **so that** I
never need to grant File System Access API permission for a repo I want kept in-browser.
**Acceptance Criteria**:
- `Step2RepoPath` (Web target) renders `UnifiedLocationPicker`; selecting "App storage" sets
  `repoRoot` to an OPFS path (`/stelekit/<graphId>/...`, per `stack.md`'s confirmed existing
  `getDefaultGraphPath()` convention) with zero `showDirectoryPicker()` call.
  - *Given* a Web user selects "App storage" on `Step2RepoPath` and clones
    `https://github.com/example/notes.git`, *When* cloning completes, *Then* no
    `showDirectoryPicker()` call was ever made, and the repo's `repoRoot` is an OPFS path.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt` (Step2RepoPath, lines 742-853)

##### Task 2.3.2a: Replace `Step2RepoPath`'s `onBrowseRepoRoot` wiring with `UnifiedLocationPicker` (~5 min)
- Must preserve the existing synchronous-user-activation constraint for the "Browse…" path
  (`fileSystem.requestDirectoryPickerNow()` called directly from the click handler, no `await` gap
  in between — per `stack.md` §3's documented Chrome requirement).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`

##### Task 2.3.2b: Pass the selected `StorageLocation.AppOwned` into the existing `addGraph(..., location = ...)` call for the cloned graph (Web) (~3 min)
- No `GraphManager.kt` edit here — reuses Task 1.1.3f's `onGraphLocationDetermined` seam.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`

##### Task 2.3.2c: wasmJs test — clone into OPFS never calls `showDirectoryPicker` (~4 min)
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/GitSetupScreenAppOwnedCloneTest.kt` (new)

#### Story 2.3.3: Plain-graph `AppOwned` warning on Web, with a required export affordance
**As a** Web user choosing app storage for a plain graph, **I want** to understand that clearing
site data deletes it, and to have a way to get my files out before that happens, **so that** I'm not
surprised later and I'm not left with zero escape hatch if I never get to use a future relocate/link
flow.
**BLOCKING**: per ADR-003's Amendment (2026-09-12, Phase 4 pre-mortem finding), `AppOwned` must not
be offered as a selectable option for a plain graph on Web until this story's export task
(2.3.3c) ships — this is a Phase-2 prerequisite, not a deferrable Phase 3/4 nice-to-have. Without
it, a Web user who picks `AppOwned` for a plain graph and never gets to a shipped relocate/link
phase has no way to get their files out at all — strictly worse than the pre-project baseline where
OPFS was only an invisible auto-fallback.
**Acceptance Criteria**:
- Selecting "App storage" for a plain (non-git) graph on Web shows the mandatory warning with the
  exact copy from ADR-003 ("...lost if you clear site data.") **and** an "Export as .zip" button,
  mirroring Android's Story 2.2.1e affordance (per ADR-003's Amendment reversing the original
  Web-gets-no-export decision).
  - *Given* a Web user selects "App storage" for a plain graph, *When* the warning dialog renders,
    *Then* it shows the Web-specific copy and an "Export as .zip" button.
  - *Given* a Web user taps "Export as .zip", *When* the export completes, *Then* a browser download
    of a valid `.zip` archive (openable by a standard OS zip tool) containing the graph's markdown
    files is triggered, with no `showDirectoryPicker()`/File System Access prompt involved.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PlainGraphAppOwnedWarningDialog.kt` (new, shared shell — both platforms now supply an export slot),
`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/export/GraphZipExporter.wasmJs.kt` (new),
`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.js.kt`

##### Task 2.3.3a: Build the shared warning dialog shell (platform copy + export slot on both platforms) (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PlainGraphAppOwnedWarningDialog.kt`

##### Task 2.3.3b: Wire the Web new-graph and `Step2RepoPath` flows to show it (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `GitSetupScreen.kt`

##### Task 2.3.3c: Implement a hand-rolled stored-only (uncompressed) ZIP writer for wasmJs (~8 min)
- Per ADR-003's Amendment: write ZIP local-file-header + central-directory-record binary structures
  directly, with every entry using method `0` (`STORED`, no compression) — avoids needing
  `java.util.zip` (JVM-only) or a new Kotlin/Wasm compression dependency
  (`research/build-vs-buy.md`'s "no new dependency justified" finding still holds). Needs a small
  CRC-32 implementation (table-driven, no library) since the ZIP format requires a CRC per entry
  regardless of compression method. Recursively read the graph's markdown files via the existing
  `FileSystem` interface, same enumeration approach as Android's Task 2.2.1e.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/export/GraphZipExporter.wasmJs.kt` (new),
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/export/Crc32.wasmJs.kt` (new)

##### Task 2.3.3d: Add a binary-`ByteArray` download path to `WasmJsShareProvider` (~4 min)
- `PlatformShareProvider.js.kt`'s existing `triggerBlobDownload` only accepts a `String` (used for
  `text/plain` content); add a sibling that accepts a `ByteArray`, builds a `Uint8Array`-backed
  `Blob` with `type: "application/zip"`, and reuses the same `<a download>` object-URL trigger —
  no new download mechanism, only a binary-content variant of the existing one.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.js.kt`

##### Task 2.3.3e: wasmJs test — exported archive round-trips (write via `GraphZipExporter`, read back via a ZIP-reading assertion) and contains every markdown file with byte-identical content (~5 min)
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/export/GraphZipExporterWasmJsTest.kt` (new)

---

## Phase 3: Relocate operation

### Epic 3.1: `BulkCopyVerifier` + staging + quiesce port + `GraphRelocationCoordinator`
**Goal**: Build the net-new copy+verify primitive, the `GraphMoveQuiesceStrategy` port (so the
`commonMain` coordinator never references an `androidMain`-only type), the extracted
`AtomicFileRelocationStep` (so `GraphManager.kt` doesn't grow), and the orchestration coordinator
itself — all of which both platforms' relocate wiring (Epics 3.2, 3.3) extend or consume, resolving
the platform split here rather than discovering it ad hoc downstream.

#### Story 3.1.1: `BulkCopyVerifier` primitive
**As a** developer implementing relocate, **I want** a chunked recursive copy with real content-hash
verification, **so that** the encoding/path bugs already shipped once in this codebase
(`e087959cb1`) are not repeated.
**Acceptance Criteria**:
- `BulkCopyVerifier.copyAndVerify(source: StorageLocation, destination: StorageLocation, graphId:
  String): Either<DomainError.StorageError, CopyReport>` walks the source tree in bounded chunks
  (never a single unbounded directory listing for an 8,000+ page graph, per this repo's existing
  "graph-scale reads must be paginated" rule), copies each file, then re-reads the destination file
  and compares `ContentHasher.sha256(ByteArray)` against the source's hash.
  - *Given* a source directory with 8,030 markdown files (matching this repo's largest benchmarked
    graph size), *When* `copyAndVerify` runs, *Then* it processes files in bounded batches (assert
    no single call reads more than `COPY_BATCH_SIZE` files' bytes into memory at once — mirror
    `INDEX_BATCH_SIZE = 100` from `GraphLoader.indexRemainingPages`).
  - *Given* one destination file's bytes were corrupted during copy (simulated by flipping a byte
    post-write in a test), *When* verification runs, *Then* `copyAndVerify` returns
    `DomainError.StorageError.VerificationFailed(path, reason).left()`, and no further files are
    marked verified past that point without being independently checked.
- Page identity for verification is derived using `FileUtils.sanitizeFileName`/`decodeFileName`,
  never a bespoke filename transform (per the `e087959cb1` precedent named in requirements.md).
  - *Given* a source file named via `sanitizeFileName("Q&A_notes")`, *When* `copyAndVerify` compares
    source and destination identity, *Then* it calls `decodeFileName` on both sides rather than a
    raw string comparison or ad-hoc `.replace("_", " ")`.
- For git-cloned graphs, `.git` object/pack files are verified with the same real content-hash bar as
  markdown (`ContentHasher.sha256(ByteArray)` per file), not an object/ref-count comparison alone — a
  count-only check can pass across a torn-pack-file race (`research/pitfalls.md` §3) while history is
  silently corrupted, which contradicts this story's own stated bar.
  - *Given* a `.git/objects/pack/pack-*.pack` file is corrupted by a single flipped byte during copy
    (same simulated-corruption technique as the markdown case above), *When* `copyAndVerify` verifies
    a git-cloned graph's destination, *Then* it returns
    `DomainError.StorageError.VerificationFailed(path, reason).left()` rather than passing on a
    matching object/ref count alone.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BulkCopyVerifier.kt` (new)

##### Task 3.1.1a: Define `CopyReport` and the `copyAndVerify` signature (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BulkCopyVerifier.kt`

##### Task 3.1.1b: Implement chunked tree walk + copy (commonMain, delegating per-file I/O to `FileSystem`) (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BulkCopyVerifier.kt`

##### Task 3.1.1c: Implement per-file hash verify using `ContentHasher.sha256(ByteArray)` (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BulkCopyVerifier.kt`

##### Task 3.1.1d: Insufficient-space pre-flight check (Android `StatFs`, Web `navigator.storage.estimate()`) (~5 min)
- Model Android's check on `MIN_SHADOW_FREE_BYTES`/`StatFs` (`AndroidGitRepository.kt:112,799`);
  Web's on a new `estimate()` call (none exists today per `research/features.md` §4).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/BulkCopyVerifier.android.kt` (new),
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/BulkCopyVerifier.wasmJs.kt` (new)

##### Task 3.1.1e: `businessTest` — 8,000-file bounded-batch + corruption-detection tests (~5 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/BulkCopyVerifierTest.kt` (new)

##### Task 3.1.1f: For git-cloned graphs, content-hash each `.git` object/pack file (~5 min)
- Walk `.git/objects/` (loose objects + `pack/*.pack`/`*.idx`) in the same bounded-batch style as
  Task 3.1.1b, and verify each file with `ContentHasher.sha256(ByteArray)` — same primitive, same
  verify-before-cleanup rule as markdown. Chosen over an object/ref-count-only comparison (cheaper
  but weaker) because a torn-pack-file race (`research/pitfalls.md` §3) can produce a same-count,
  corrupted result; a real fsck was also considered but rejected as disproportionate for a
  single-developer app with no dedicated QA (requirements.md's Constraints) when content-hashing the
  same bytes the file-count check already touches costs little more.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitObjectContentVerifier.android.kt` (new)

#### Story 3.1.2: `RelocationStagingDirectory` + startup sweep
**As a** developer, **I want** relocate's copy step to write into a distinct staging path with a
marker file, **so that** an interrupted relocate is detectable and cleanable on next launch instead
of leaving an ambiguous partial state.
**Acceptance Criteria**:
- The copy step writes to `<destination-parent>/.stele-relocate-staging-<graphId>/` (or the
  platform-appropriate equivalent), with a marker file recording `startedAtEpochMs` and `graphId`.
  - *Given* a relocate begins for `graphId = "g1"`, *When* the copy step starts, *Then* files are
    written under the staging path, not directly to the final destination path, until verification
    passes.
- A startup sweep scans for staging directories whose marker is older than a grace period (mirror
  `GitShadowWorktree.sweepOrphans()`'s pattern) and deletes only those, never a marker-less directory
  (ambiguous absence is never treated as staleness, per the same precedent).
  - *Given* a staging directory with a marker dated 8 days ago (past a 7-day grace period), *When*
    the startup sweep runs, *Then* the staging directory is deleted.
  - *Given* a staging directory with no marker file at all, *When* the startup sweep runs, *Then*
    it is left untouched (ambiguous, not swept).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RelocationStagingDirectory.kt` (new)

##### Task 3.1.2a: Implement staging-path construction + marker read/write (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RelocationStagingDirectory.kt`

##### Task 3.1.2b: Implement the startup sweep, called alongside the existing `sweepOrphans()` startup hook (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RelocationStagingDirectory.kt`

##### Task 3.1.2c: `businessTest` — aged-marker sweep vs. marker-less non-sweep (~4 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/RelocationStagingDirectoryTest.kt` (new)

#### Story 3.1.3: `GraphMoveQuiesceStrategy` port + platform implementations
**As a** developer, **I want** the quiesce/release steps behind a `commonMain` interface instead of
`GraphRelocationCoordinator` calling `GitWorktreeLocks`/`GitSyncBusyCounter`/`GitWriteBackQueue`
directly, **so that** the coordinator (Story 3.1.5) compiles in `commonMain` at all — those three
types are `androidMain`-only, and KMP's dependency direction is one-way (platform depends on common,
never the reverse), so a `commonMain` class cannot reference them.
**Acceptance Criteria**:
- `interface GraphMoveQuiesceStrategy { suspend fun quiesce(op: StorageMoveOperation):
  Either<DomainError.StorageError, Unit>; suspend fun release(op: StorageMoveOperation); suspend fun
  releaseSourceGrant(source: StorageLocation) }` is defined in `commonMain`. `releaseSourceGrant` is
  a separate, narrow addition to the same port (not a new interface) for the one other
  `androidMain`-only call `GraphRelocationCoordinator` needs post-cleanup
  (`ContentResolver.releasePersistableUriPermission`, Epic 5.2) — grouped here because it is the same
  "commonMain cannot call an androidMain-only API" problem this story exists to solve, just
  discovered at cleanup time instead of quiesce time.
  - *Given* `GraphRelocationCoordinator` is compiled as a `commonMain` source file with a constructor
    parameter of type `GraphMoveQuiesceStrategy`, *When* the `commonMain` compilation target builds,
    *Then* it succeeds with no reference to any `androidMain`-only type.
- `AndroidGraphMoveQuiesceStrategy` (`androidMain actual`) implements `quiesce()` by acquiring
  `GitWorktreeLocks.lockFor(shadowKey)` (if the operation touches a git shadow worktree), awaiting
  `GitSyncBusyCounter.awaitIdle()`, then draining `GitWriteBackQueue` via `flushActor.flush()` until
  `isEmpty()`; `release()` releases the lock.
  - *Given* a `Relocate` operation touching a git shadow worktree with an in-flight
    `GitWriteBackQueue` entry, *When* `AndroidGraphMoveQuiesceStrategy.quiesce()` is invoked, *Then*
    it does not return until the busy counter is idle and the write-back queue is empty.
- `WasmJsGraphMoveQuiesceStrategy` (`wasmJsMain actual`) implements `quiesce()`/`release()` as a no-op
  initially (host-poll pause is wired in by Story 3.3.2, which extends this same class rather than
  creating a new one).
  - *Given* a Web `Relocate` operation not yet touching a linked host folder, *When*
    `WasmJsGraphMoveQuiesceStrategy.quiesce()` is invoked, *Then* it completes immediately with
    `Unit.right()`.
- A `FakeGraphMoveQuiesceStrategy` test double exists in a shared test-fixtures location, with a
  configurable "never completes" mode (a `suspend fun quiesce()` that suspends forever via
  `awaitCancellation()` or an uncompleted `Deferred`), for Story 3.1.5's timeout regression test and
  for any `businessTest` exercising the coordinator without a real platform.
  - *Given* `FakeGraphMoveQuiesceStrategy(neverCompletes = true)` is injected into
    `GraphRelocationCoordinator`, *When* `relocate()` is invoked, *Then* `quiesce()` never returns on
    its own (the coordinator's own timeout, Story 3.1.5, is what must eventually terminate it).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.kt` (new),
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.android.kt` (new),
`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.wasmJs.kt` (new),
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/FakeGraphMoveQuiesceStrategy.kt` (new)

##### Task 3.1.3a: Define the `GraphMoveQuiesceStrategy` interface in `commonMain` (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.kt`

##### Task 3.1.3b: Implement `AndroidGraphMoveQuiesceStrategy` wrapping `GitWorktreeLocks`/`GitSyncBusyCounter`/`GitWriteBackQueue` (~5 min)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.android.kt`

##### Task 3.1.3c: Implement `WasmJsGraphMoveQuiesceStrategy` as an initial no-op (extended by Story 3.3.2) (~3 min)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.wasmJs.kt`

##### Task 3.1.3d: Build `FakeGraphMoveQuiesceStrategy` with a configurable never-completes mode (~3 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/FakeGraphMoveQuiesceStrategy.kt`

##### Task 3.1.3e: `androidUnitTest` — `AndroidGraphMoveQuiesceStrategy` awaits busy-counter idle and drains the write-back queue (~4 min)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/db/AndroidGraphMoveQuiesceStrategyTest.kt` (new)

##### Task 3.1.3f: `wasmJsTest` — `WasmJsGraphMoveQuiesceStrategy` completes immediately pre-3.3.2 (~3 min)
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/db/WasmJsGraphMoveQuiesceStrategyTest.kt` (new)

##### Task 3.1.3g: Implement `releaseSourceGrant` (Android: no-op stub, wired in Epic 5.2; Web: no-op) (~3 min)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.android.kt`,
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.wasmJs.kt`

#### Story 3.1.4: Extract `AtomicFileRelocationStep` from `moveGraphFilesAndCredentials`
**As a** developer, **I want** the DB/WAL/SHM rename-with-rollback logic available as a standalone
collaborator, **so that** `GraphRelocationCoordinator`'s markdown-repoint step (Story 3.1.5) can reuse
the same correctness-critical rollback algorithm without growing
`moveGraphFilesAndCredentials`/`GraphManager.kt` in place — the exact violation the Tech Debt
Disposition table chose "Isolate via seam" to avoid for this 40-commit-churn file.
**Acceptance Criteria**:
- `AtomicFileRelocationStep.relocate(files: List<FileMove>): Either<DomainError.StorageError, Unit>`
  contains the rename-with-rollback-on-partial-failure algorithm currently inline in
  `moveGraphFilesAndCredentials` (`GraphManager.kt` lines 600-653), byte-for-byte behavior-preserving.
  - *Given* the existing DB/WAL/SHM move (three files) that `moveGraphFilesAndCredentials` performs
    today, *When* it is re-expressed as a call to `AtomicFileRelocationStep.relocate(...)`, *Then*
    `moveGraphFilesAndCredentials`'s own body is equal or smaller in line count than before this
    task, not larger.
  - *Given* a simulated failure renaming the second of three files, *When*
    `AtomicFileRelocationStep.relocate(...)` handles it, *Then* the first file's rename is rolled
    back, matching `moveGraphFilesAndCredentials`'s existing rollback behavior exactly (regression
    test, not new behavior).
- `GraphRelocationCoordinator`'s markdown-repoint step (Story 3.1.5) calls
  `AtomicFileRelocationStep.relocate(...)` directly for its own final staging-to-destination rename,
  never through `moveGraphFilesAndCredentials` (which remains scoped to its existing DB/WAL/SHM
  callers) and never by editing `updateGraphPath()`'s internal ordering.
  - *Given* a relocate's copy+verify step has completed into the staging directory, *When* the
    coordinator repoints to the final destination, *Then* it calls `AtomicFileRelocationStep`
    directly and `updateGraphPath()`'s own source is unmodified by this project.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/AtomicFileRelocationStep.kt` (new),
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (`moveGraphFilesAndCredentials`,
lines 600-653 — delegates to the extracted step, does not grow)

##### Task 3.1.4a: Extract the rename-with-rollback algorithm into `AtomicFileRelocationStep.kt` (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/AtomicFileRelocationStep.kt`

##### Task 3.1.4b: Update `moveGraphFilesAndCredentials` to delegate to the extracted step (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (lines 600-653)

##### Task 3.1.4c: `businessTest` — rollback-on-partial-failure regression (behavior-preserving) (~4 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/AtomicFileRelocationStepTest.kt` (new)

#### Story 3.1.5: `GraphRelocationCoordinator`
**As a** developer, **I want** one class orchestrating quiesce → copy → verify → repoint → cleanup,
**so that** `GraphManager.kt` doesn't grow the sequencing logic in place, and a stuck quiesce — or a
copy/verify failure — cannot hang the UI or leave the graph uneditable.
**Acceptance Criteria**:
- `GraphRelocationCoordinator(quiesceStrategy: GraphMoveQuiesceStrategy).relocate(operation:
  StorageMoveOperation.Relocate): Flow<StorageMoveUiState>` runs, in order: (1) set
  `MoveInProgressFlag`, (2) call `quiesceStrategy.quiesce(operation)`, bounded by
  `withTimeoutOrNull(QUIESCE_TIMEOUT_MS)` — if this step fails or times out, the driver has never been
  closed, so cleanup is only "clear the flag and emit `Failed`," no reopen needed; (3) close the DB
  driver via the `tearDownActiveGraphResources()` mechanism `GraphManager.switchGraph`/`removeGraph`
  already use (Task 3.1.5c widens it to `internal` so the coordinator can call it directly, then
  awaits the returned factory's `close()` synchronously — unlike `switchGraph`/`removeGraph`, which
  defer that close to a background coroutine, the coordinator must not start copying files until the
  handle is actually released). From here through step 7, the coordinator is in a **closed-driver
  region**: every exit path — success or failure — reopens the driver (step 6) before the operation
  ends, no exceptions; (4) `BulkCopyVerifier.copyAndVerify` into the staging directory — the untouched
  source is only ever read here, never written; (5) on copy+verify success, move staging → final
  destination via `AtomicFileRelocationStep` (Story 3.1.4); (6) reopen the driver by calling
  `GraphManager.switchGraph(graphId, forceReinit = true)` (Task 3.1.5b — a relocate keeps the same
  `graphId`, so the existing idempotency guard at the top of `switchGraph()` would otherwise treat
  this as a no-op "already active" switch and never reopen the connection) **and then suspending on
  `GraphManager.awaitPendingMigration()` before proceeding** — `switchGraph()` itself only schedules
  driver/repository-set creation on `graphScope.launch(PlatformDispatcher.IO) { ... }` and returns
  immediately (`GraphManager.kt:670-810`); `awaitPendingMigration()` is this codebase's own documented
  mechanism for waiting on that scheduled work to actually finish (`GraphManager.kt:812-819`,
  `openGraph()`'s doc comment: "you cannot access the repository before it is ready") — this **pair of
  calls is made on every path out of the closed-driver region** (happy path, copy failure,
  verification failure, or cancellation mid-copy/move): neither call varies by outcome, because
  `onGraphLocationDetermined` — not `switchGraph`/`awaitPendingMigration` — is what ever records a new
  location, and on every non-happy path it simply never runs, so the reopened graph still reads as
  being at its original, untouched `StorageLocation`. `awaitPendingMigration()` returns the ready
  `RepositorySet`, or `null` if reopening itself failed (mirroring `openGraph()`'s own null-check,
  and the same defensive pattern `createGitConfigRepository()` already applies for exactly this
  reason, `GraphManager.kt:1010-1012`) — see the dedicated reopen-failure bullet below for that case;
  (7) only on the happy path, **and only once step 6's `awaitPendingMigration()` returned a non-null
  `RepositorySet`**, call `GraphManager.onGraphLocationDetermined(graphId, destination)` (Story
  1.1.3's seam — never `updateGraphPath()`) now that step 6 has reopened and confirmed-ready the very
  connection `storage_locations` lives in — every other path (copy failure, verification failure,
  cancellation, or a reopen that itself failed) skips this call entirely; (8) call
  `quiesceStrategy.release(operation)` and clear `MoveInProgressFlag` — on every path, immediately
  after step 6's reopen-and-await (whether it succeeded or returned `null`), or immediately after the
  quiesce failure/timeout, when steps 3-7 never ran at all; (9) on the happy path, emit `Summary` with
  a cleanup prompt; on an ordinary failure path (quiesce timeout, copy failure, verification failure),
  emit `Failed(reason)` only once the reopen-and-await (step 6) and cleanup (step 8) that precede it
  have both completed and step 6 confirmed the driver is actually usable again, so a `Failed` state is
  never observed while the graph is still uneditable; on the reopen-genuinely-failed path (step 6's
  `awaitPendingMigration()` returned `null`), emit `StorageMoveUiState.ReopenFailed` instead of
  `Failed` — see the dedicated bullet below for why this is a distinct terminal state rather than a
  `Failed` subtype.
  `quiesceStrategy` is instantiated the same way `GraphManager` receives its platform collaborators —
  a uniform no-arg constructor call from `commonMain` (mirroring `DriverFactory()`'s
  expect/actual-constructor convention), not a per-platform coordinator subclass.
  - *Given* a `Relocate` operation from `SafFolder` to `AppOwned` for a graph with an in-flight
    `GitWriteBackQueue` entry, *When* `relocate()` is invoked, *Then* the emitted `StorageMoveUiState`
    sequence includes a `Quiescing` state before any `Copying` state, the DB driver is closed before
    any file at the destination path is written, and the driver is reopened AND confirmed ready via
    `awaitPendingMigration()` (step 6) before `onGraphLocationDetermined` (step 7) writes to
    `storage_locations` — never the reverse, which would write through a closed or still-initializing
    connection.
  - *Given* a relocate targets the graph that is currently active (the common case — relocating the
    graph a user has open), *When* step 6 calls `switchGraph(graphId, forceReinit = true)` followed by
    `awaitPendingMigration()`, *Then* the driver is actually reopened despite `graphId` being unchanged
    and already "active" — `switchGraph`'s pre-existing idempotency guard (`GraphManager.kt`, skips
    re-init when `currentGraphId == id` and a repository set/init job already exists) is bypassed only
    via the explicit `forceReinit` parameter, and ordinary same-graph switch calls (e.g.
    `StelekitApp`'s `LaunchedEffect`) keep passing `forceReinit = false` and are unaffected; and the
    coordinator's subsequent `onGraphLocationDetermined` call does not race the still-launching
    `graphScope.launch(...)` init coroutine, because `awaitPendingMigration()` has already suspended
    until that coroutine's `finally { deferred.complete(Unit) }` ran.
  - *Given* `switchGraph(graphId, forceReinit = true)`'s scheduled reopen itself fails (its `launch`
    block's `catch (e: Exception)` branch runs — e.g. the destination is unreadable immediately after
    the move, or `RepositoryFactoryImpl` throws during driver creation), *When* `awaitPendingMigration()`
    returns `null`, *Then* the coordinator does not fold this into the ordinary `Failed`/Retry-Cancel
    flow — it emits `StorageMoveUiState.ReopenFailed(graphId, DomainError.StorageError.ReopenFailed(graphId.value))`,
    a distinct terminal state, because unlike a copy or verification failure (where the source is
    provably untouched and the driver is provably reopened before `Failed` is ever shown, per Surface
    8's "Your original files were not touched or deleted" guarantee), a reopen failure means the app
    cannot currently prove the graph is editable at all — offering a plain "Retry" here would restate
    a guarantee ("your graph is fine, just retry") the coordinator cannot back up. `quiesceStrategy.release`
    and `MoveInProgressFlag` clearing (step 8) still run on this path — the flag must not be left stuck
    set — but no automatic re-open retry loop is attempted here (out of scope for this iteration; see
    Task 3.1.5m).
- `QUIESCE_TIMEOUT_MS = 30_000L` (30s — long enough for a large `GitWriteBackQueue` drain under
  normal load, short enough that a stuck flush surfaces as a visible failure within one user-patience
  window rather than an indefinite spinner). If `quiesceStrategy.quiesce()` does not complete within
  `QUIESCE_TIMEOUT_MS`, the coordinator clears `MoveInProgressFlag` and emits
  `StorageMoveUiState.Failed` carrying `DomainError.StorageError.QuiesceTimedOut(waitedMs =
  QUIESCE_TIMEOUT_MS)` — never an indefinite "Quiescing" spinner (this codebase's own documented
  "silent indefinite hang" bug class), and never a graph left silently uneditable because the flag
  was never cleared. No reopen call is made on this path: the driver was never closed.
  - *Given* `FakeGraphMoveQuiesceStrategy(neverCompletes = true)` (Story 3.1.3) is injected, *When*
    `relocate()` is invoked, *Then* the coordinator emits `StorageMoveUiState.Failed` containing
    `DomainError.StorageError.QuiesceTimedOut` within `QUIESCE_TIMEOUT_MS` plus test-scheduler
    tolerance, `MoveInProgressFlag` is cleared, `switchGraph` is never called (nothing to reopen),
    rather than suspending forever with the graph stuck uneditable.
- On copy failure or verification failure (both occur inside the closed-driver region, after step 3
  has already closed the driver), the source is never touched, the coordinator reopens the driver AND
  suspends on `awaitPendingMigration()` (step 6) BEFORE emitting `Failed` so the graph is *confirmed*
  editable again — not just "a reopen was requested" — emits `Failed` with only "Retry"/"Cancel"
  affordances (no "delete anyway"), and `MoveInProgressFlag` is cleared. If `awaitPendingMigration()`
  itself returns `null` on this path (reopen failed on top of a copy/verification failure), the
  coordinator emits `ReopenFailed` instead of `Failed`, per the dedicated reopen-failure bullet above
  — the underlying copy/verification reason is not lost (carry it as `ReopenFailed`'s `cause`, since a
  user retrying a reopen-failed state needs to know what triggered the reopen in the first place, not
  just that reopening failed). Both failure kinds are handled by the same shared reopen-then-cleanup
  code path (Task 3.1.5e/f) — neither duplicates it.
  - *Given* `BulkCopyVerifier` returns `VerificationFailed`, *When* the coordinator handles it,
    *Then* it calls `switchGraph(graphId, forceReinit = true)` followed by `awaitPendingMigration()`
    to reopen and confirm the driver BEFORE emitting `StorageMoveUiState.Failed(reason)`,
    `onGraphLocationDetermined` is never called, the source `StorageLocation`'s files are
    byte-for-byte unchanged, and `MoveInProgressFlag` is cleared — and a subsequent read/write through
    `GraphManager` for this `graphId` succeeds (the graph remains editable at its original, untouched
    location — not merely "the flag is clear," and not merely "switchGraph was called" without proof
    the scheduled reopen actually finished).
  - *Given* `BulkCopyVerifier.copyAndVerify` fails during the copy itself (an I/O error before
    verification even runs), *When* the coordinator handles it, *Then* it follows the identical
    reopen-await-before-`Failed` sequence as the verification-failure case above.
- `MoveInProgressFlag` is cleared on **every** exit path from `relocate()` — success (step 8), quiesce
  timeout, copy failure, verification failure, and coroutine cancellation at any point in the
  sequence — never only the happy-path branch. The clearing, and the reopen it depends on once the
  driver has been closed, live in one shared place in the coordinator: a `finally`-equivalent wrapping
  the closed-driver region (steps 3-8), executed with `NonCancellable` so it still runs when the
  coordinator's own coroutine has itself been cancelled — not duplicated per failure branch, so a
  later-added failure branch can't forget either the reopen or the flag clear.
  - *Given* any of `quiesce()` timing out, `BulkCopyVerifier` failing, `AtomicFileRelocationStep`
    failing mid-copy, or the coordinator's coroutine being cancelled mid-sequence, *When* the
    operation ends, *Then* `MoveInProgressFlag`'s state for that `graphId` is cleared, so subsequent
    edits/indexing/watcher activity for that graph are not blocked by a stale flag.
  - *Given* the coordinator's coroutine is cancelled while inside the closed-driver region — after
    step 3's close, during `BulkCopyVerifier.copyAndVerify` or `AtomicFileRelocationStep.relocate` —
    *When* cancellation propagates, *Then* the `finally` block, running with `NonCancellable`, still
    calls `switchGraph(graphId, forceReinit = true)` **and suspends on `awaitPendingMigration()`**
    (both calls are ordinary suspend functions and run fine inside a `NonCancellable` context) to
    reopen and confirm the driver, then clears `MoveInProgressFlag` regardless of whether that reopen
    succeeded or `awaitPendingMigration()` returned `null` — even though the now-cancelled `Flow` can
    no longer successfully emit a terminal `Failed`/`Summary`/`ReopenFailed` state to a collector, the
    flag must not be left stuck set purely because nothing was listening.
  - *Given* cancellation is requested while the coordinator is inside `BulkCopyVerifier.copyAndVerify`
    **after the copy sub-phase has finished and the hash-verification sub-phase is what's running**
    (i.e. the state the UI was last shown was `Verifying`, per Story 3.4.3/Surface 7's
    Cancel-enabled-during-`Verifying` requirement), *When* cancellation propagates, *Then* it is
    handled by the exact same `NonCancellable` closed-driver-region `finally` as any other
    cancellation inside `copyAndVerify` — there is no separate "cancel during verify" branch: the
    destination copy (staging, or the final path if step 5's atomic move already ran) is abandoned,
    never repointed via `onGraphLocationDetermined`, the driver is reopened and confirmed at the
    original location, and `MoveInProgressFlag` is cleared. This is the case Task 3.1.5l's test
    coverage extends to assert explicitly, not just cancellation during the copy sub-phase.
  - *Given* the coordinator's coroutine is cancelled before step 3 (during quiesce, steps 1-2), *When*
    cancellation propagates, *Then* only `MoveInProgressFlag` is cleared via the same shared `finally`
    — no reopen call is made, because the driver was never closed on this path.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt` (new),
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (existing — add `forceReinit`
parameter to `switchGraph()`; widen `tearDownActiveGraphResources()` from `private` to `internal` so
the coordinator can reuse the same close mechanism `switchGraph`/`removeGraph` already share;
`awaitPendingMigration()` itself is pre-existing and unchanged — the coordinator is simply a new
caller of it), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt` (existing — add
the `ReopenFailed` leaf to `StorageError`, Story 1.1.2)

##### Task 3.1.5a: Define `StorageMoveUiState` sealed states, including a `ReopenFailed(graphId: GraphId, cause: DomainError.StorageError.ReopenFailed)` state distinct from `Failed(reason: DomainError.StorageError)` (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/StorageMoveUiState.kt` (new)
- `ReopenFailed` is a sibling of `Failed`, not one of its cases — the two are structurally
  distinguishable so a `when` over `StorageMoveUiState` in the UI layer is forced to handle them
  separately (see Task 3.4.3's UI wiring, which must not render `ReopenFailed` with the ordinary
  "Retry"/"Cancel" affordances Surface 8 defines for `Failed`, since a plain "Retry" implies a
  guarantee — that the graph is otherwise fine — the coordinator cannot back up on this path).

##### Task 3.1.5b: Add a `forceReinit: Boolean = false` parameter to `GraphManager.switchGraph()`, bypassing the idempotency guard only when `true` (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (`switchGraph()`'s guard,
  currently `if (currentGraphId == id && (_activeRepositorySet.value != null ||
  activeGraphJobs.containsKey(id))) return`)
- Change the guard to `if (currentGraphId == id && !forceReinit && (...)) return`.
  `GraphRelocationCoordinator` (Task 3.1.5e below) is the only caller that passes
  `forceReinit = true`, and it does so on every exit from the closed-driver region — success and
  failure alike; every other call site (`StelekitApp`'s startup `LaunchedEffect`, ordinary
  graph-switch UI actions) keeps the default `false`, so the guard's documented racing-
  `LaunchedEffect` protection (see the guard's own comment in `GraphManager.kt`) is unchanged for
  normal switches. Done before the coordinator tasks below because they call this parameter.

##### Task 3.1.5c: Widen `GraphManager.tearDownActiveGraphResources()` from `private` to `internal`, giving the coordinator a named seam for "close the driver" that reuses the exact mechanism `switchGraph`/`removeGraph` already share, instead of inventing a new one (~2 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` (`tearDownActiveGraphResources()`,
  currently `private fun`)
- No behavior change for existing callers: `switchGraph`/`removeGraph` keep closing the returned
  factory asynchronously (deferred to an IO coroutine, per the existing comment about
  `wal_checkpoint(TRUNCATE)` blocking the UI thread if run synchronously). The coordinator is the one
  new caller that awaits `factoryToClose?.close()` itself, synchronously, before proceeding to step 4
  — it already runs off the UI dispatcher, and it cannot start `BulkCopyVerifier.copyAndVerify` until
  the file handle is actually released.

##### Task 3.1.5d: Implement the quiesce sequence (steps 1-2), wrapped in `withTimeoutOrNull(QUIESCE_TIMEOUT_MS)` producing `QuiesceTimedOut` on expiry; this path never reaches step 3, so its cleanup is flag-clear + `Failed` only, no reopen call (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt`

##### Task 3.1.5e: Implement the closed-driver region (steps 3-7) as one `try`/`finally`-guarded block: close the driver (Task 3.1.5c's seam), then copy+verify+move (steps 4-5), then — in the `finally`, run with `NonCancellable` — always reopen via `switchGraph(graphId, forceReinit = true)` followed by `awaitPendingMigration()` (step 6, Task 3.1.5b); only when that reopen succeeds (non-null) AND the outcome was the happy path does step 7's `onGraphLocationDetermined` run before that shared reopen-and-cleanup completes (~10 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt`
- The reopen-before-write ordering is load-bearing: `storage_locations` lives in the same per-graph
  SQLite file closed at step 3, so calling `onGraphLocationDetermined` before step 6 reopens the
  driver would write through a closed connection.
- `switchGraph(graphId, forceReinit = true)` is fire-and-forget — it schedules driver/repository-set
  creation on `graphScope.launch(PlatformDispatcher.IO) { ... }` and returns before that work finishes
  (`GraphManager.kt:670-810`). Calling it alone is not sufficient here: the coordinator must suspend on
  `GraphManager.awaitPendingMigration()` immediately afterward (`GraphManager.kt:812-819`) to actually
  wait for the reopen, exactly as `openGraph()` does internally. Treat `switchGraph(...)` +
  `awaitPendingMigration()` as one inseparable "reopen-and-confirm" step — never call the former
  without immediately suspending on the latter.
- The reopen-and-confirm pair is identical on every path out of this region (success, copy failure,
  verification failure, cancellation) — only whether `onGraphLocationDetermined` subsequently runs
  differs, and only when `awaitPendingMigration()` returned non-null. Do not fork the reopen call per
  failure type; a single shared `finally` is the point of this task.
- If `awaitPendingMigration()` returns `null`, branch to emitting `ReopenFailed` (Task 3.1.5a) instead
  of `Failed`/`Summary`; `onGraphLocationDetermined` must not be called in this case even on an
  otherwise-happy path, since there is no confirmed-open connection to write `storage_locations`
  through.

##### Task 3.1.5f: Implement the shared cleanup step (step 8: `quiesceStrategy.release(operation)` + clear `MoveInProgressFlag`) as one function called from both the quiesce-failure branch (Task 3.1.5d) and the closed-driver-region `finally` (Task 3.1.5e), rather than inlined separately in each (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt`

##### Task 3.1.5g: `businessTest` — full happy-path relocate state sequence, using `FakeGraphMoveQuiesceStrategy` (~5 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorTest.kt` (new)

##### Task 3.1.5h: `businessTest` — verification-failure and copy-failure both reopen the driver via `switchGraph(forceReinit = true)` AND await `awaitPendingMigration()` BEFORE emitting `Failed`, leave the source untouched, never call `onGraphLocationDetermined`, and clear `MoveInProgressFlag` (~5 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorFailureTest.kt` (new)
- Asserts the graph is actually editable after `Failed` is observed — e.g. a subsequent read/write
  through `GraphManager` succeeds — not just that the flag is clear; this is the regression test for
  the reopen-on-failure gap this iteration fixes. Drive this test with a fake/slow `RepositoryFactory`
  whose `createRepositorySet` completes on a delayed dispatcher tick, so the test would fail if the
  coordinator asserted "editable" merely because `switchGraph` was *called* rather than because
  `awaitPendingMigration()` actually returned — this is what makes the test a genuine regression test
  for the race this iteration fixes, not just a same-thread happy case where the race can't manifest.

##### Task 3.1.5i: `businessTest` — quiesce timeout surfaces `Failed(QuiesceTimedOut)` instead of hanging, clears `MoveInProgressFlag`, and never invokes `switchGraph` (the driver was never closed on this path) (~4 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorQuiesceTimeoutTest.kt` (new)

##### Task 3.1.5j: `businessTest` — relocating the currently-active graph reopens the driver despite unchanged `graphId`, and `awaitPendingMigration()` observably returns only after the reopened `RepositorySet` is ready (regression for both the `switchGraph` idempotency-guard gap and the fire-and-forget reopen gap) (~5 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorSameGraphSwitchTest.kt` (new)

##### Task 3.1.5k: `businessTest` — `MoveInProgressFlag` is cleared on quiesce-timeout, copy-failure, verification-failure, AND cancellation exit paths, not just success (~5 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorFlagClearingTest.kt` (new)

##### Task 3.1.5l: `businessTest` — cancelling the coordinator's coroutine mid-copy, and separately mid-verify (i.e., after the copy sub-phase of `BulkCopyVerifier.copyAndVerify` has finished but before verification has returned — the point at which the UI would be showing `Verifying`), both still reopen the driver, suspend on `awaitPendingMigration()` under `NonCancellable`, and clear `MoveInProgressFlag` via the `NonCancellable` cleanup path; cancelling during quiesce (before the driver is closed) clears the flag with NO reopen call (~7 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorCancellationTest.kt` (new)
- This is the dedicated regression test for cancellation-triggered flag-clearing/reopening — previously
  only an acceptance-criterion claim with no task or test implementing it.
- The mid-verify case drives cancellation via a fake/instrumented `BulkCopyVerifier` (or an injectable
  hook it exposes) that suspends at a checkpoint reached only after all files are copied and hashing
  has begun, so the test can assert cancellation at that point takes the identical reopen-and-discard
  path as mid-copy cancellation — same assertions (driver reopened and confirmed, destination
  discarded, `onGraphLocationDetermined` never called, `MoveInProgressFlag` cleared) — rather than
  assuming the mid-copy assertions also cover the verify sub-phase without a dedicated test proving it.
  This is the repair this iteration adds per the Phase 4 adversarial-review UX-lens finding that
  Surface 7 disabled Cancel during `Verifying` on an unstated "a few seconds at most" assumption
  requirements.md's own Rabbit Holes section (8,000+-page graph scale) and Task 3.1.1f's per-object
  content-hash verification both contradict.

##### Task 3.1.5m: `businessTest` — a reopen that genuinely fails after a relocate (`awaitPendingMigration()` returns `null`) emits `StorageMoveUiState.ReopenFailed` carrying `DomainError.StorageError.ReopenFailed`, never a plain `Failed`, and still clears `MoveInProgressFlag` and calls `quiesceStrategy.release` (~5 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorReopenFailureTest.kt` (new)
- Drive the reopen failure with a fake `RepositoryFactory`/driver that throws during
  `createRepositorySet` on the *second* `switchGraph` call only (the reopen), so the first open (at
  app/graph startup, before `relocate()` runs) still succeeds — isolates "reopen after relocate
  failed" from "graph never opened at all."
- Asserts `onGraphLocationDetermined` is never called on this path even when the underlying failure
  originated from an otherwise-successful copy+verify (i.e., reopen fails after a happy-path
  copy/verify) — the happy-path write must not happen through a connection the coordinator cannot
  prove is open.
- This is the dedicated regression test for the reopen-failure/`ReopenFailed` gap this repair
  iteration adds — previously the plan had no path for `switchGraph`'s own scheduled reopen failing,
  only for `switchGraph` never being awaited at all.

---

### Epic 3.2: Android relocate wiring
**Goal**: Wire platform-specific quiesce points (WorkManager, git-in-flight) into
`GraphRelocationCoordinator` for Android.

#### Story 3.2.1: Pause `WorkManagerSyncScheduler`'s periodic job during relocate
**As a** developer, **I want** a relocate to pause any scheduled background git fetch, **so that** a
concurrent WorkManager fetch never races a foreground relocate's copy of `.git`.
**Acceptance Criteria**:
- `AndroidGraphMoveQuiesceStrategy` (Story 3.1.3's Android implementation) is extended to also call a
  new `WorkManagerSyncScheduler.pauseFor(graphId)` at the start of `quiesce()`, and
  `resumeFor(graphId)` at the start of `release()`.
  - *Given* a graph has a scheduled periodic `WorkManager` sync job, *When* a relocate begins for
    that graph, *Then* the job is cancelled/paused before the copy step starts and re-scheduled after
    `switchGraph()` completes.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.android.kt`

##### Task 3.2.1a: Add `pauseFor(graphId)`/`resumeFor(graphId)` to `WorkManagerSyncScheduler` (~4 min)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt`

##### Task 3.2.1b: Call `pauseFor`/`resumeFor` from `AndroidGraphMoveQuiesceStrategy.quiesce()`/`release()` (Story 3.1.3) (~3 min)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.android.kt`

##### Task 3.2.1c: Android unit test — relocate pauses and resumes the periodic job (~4 min)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/WorkManagerSyncSchedulerPauseTest.kt` (new)

#### Story 3.2.2: "Move storage location" action replaces the bare `updateGraphPath` text field
**As an** Android user, **I want** a guided relocate flow from the graph's Edit dialog, **so that**
I'm not editing a raw path string with no verification.
**Depends on**: Story 1.1.4 (`StorageLocationResolver`) — a graph opened via this action may predate
`storage_locations` entirely, and the confirmation dialog (Story 3.4.2) needs a real source location
to name.
**Acceptance Criteria**:
- `Sidebar.kt`'s "Edit Graph" dialog's path `OutlinedTextField` is replaced with a "Move storage
  location…" button that opens `UnifiedLocationPicker` followed by the Relocate/Link choice dialog
  (Epic 3.4).
  - *Given* a user opens "Edit Graph" for an existing graph, *When* the dialog renders, *Then* there
    is no longer a freely-editable path text field — only the new guided action button.
- Tapping the button first calls `StorageLocationResolver.resolveOrBackfill(graphId)` (Story 1.1.4)
  before opening the picker, so a graph that predates this feature still has a real source location
  to display and relocate from.
  - *Given* a graph with no `storage_locations` row (created before this feature shipped) and an
    on-record SAF tree URI, *When* the user taps "Move storage location…", *Then*
    `resolveOrBackfill` runs first and the subsequent confirmation dialog (Story 3.4.2) names the
    derived `SafFolder` as the source, not a blank/unknown value.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (~line 577)

##### Task 3.2.2a: Replace the path `OutlinedTextField` with the "Move storage location…" button (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

##### Task 3.2.2b: Call `StorageLocationResolver.resolveOrBackfill(graphId)`, then wire the button to open `UnifiedLocationPicker` + Relocate/Link choice (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

---

### Epic 3.3: Web relocate wiring
**Goal**: Add the new Web Lock namespace and host-poll-pause quiesce steps.

#### Story 3.3.1: New Web Lock namespace for relocate
**As a** developer, **I want** relocate to use its own Web Lock name, **so that** it neither fails
to exclude a concurrent link operation (wrong namespace) nor over-blocks unrelated git pushes
(borrowed namespace).
**Acceptance Criteria**:
- A new lock name (e.g. `"stele-relocate-<graphId>"`), distinct from `GitWriteLock`'s remote-URL-derived
  name and `HostDirectorySync`'s per-write/per-poll-tick lock names, is acquired for the duration of
  a Web relocate.
  - *Given* a relocate is in progress for `graphId = "g1"` holding `"stele-relocate-g1"`, *When* a
    concurrent `git push` attempts to acquire its own remote-URL-derived lock, *Then* it succeeds
    (unaffected) — proving the namespaces are independent, not accidentally shared.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.3.1a: Define the new lock-name constant and acquire/release helper (~3 min)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.3.1b: wasmJs test — relocate lock and git-push lock don't cross-block (~4 min)
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/RelocateLockNamespaceTest.kt` (new)

#### Story 3.3.2: Pause the host poll loop during a Web relocate
**As a** developer, **I want** `HostDirectorySync`'s steady-state poll loop paused during a relocate
touching a linked host folder, **so that** the reconciliation pass doesn't race the relocate's copy.
**Acceptance Criteria**:
- Reuses the existing `visibilitychange`-driven poll-suspension idiom already present in
  `HostDirectorySync.kt`, extended with an explicit `pausePolling()`/`resumePolling()` pair called
  from `WasmJsGraphMoveQuiesceStrategy` (Story 3.1.3's initially-no-op Web implementation).
  - *Given* a graph is linked to a host folder and a relocate begins, *When* the copy step starts,
    *Then* the poll loop is suspended until `switchGraph()` completes.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`,
`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.wasmJs.kt`

##### Task 3.3.2a: Extract explicit `pausePolling()`/`resumePolling()` from the existing suspension idiom (~4 min)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.3.2b: Call `pausePolling`/`resumePolling` from `WasmJsGraphMoveQuiesceStrategy.quiesce()`/`release()` (Story 3.1.3), replacing its initial no-op (~3 min)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.wasmJs.kt`

#### Story 3.3.3: "Move storage location" action wired on Web
**As a** Web user, **I want** the same guided relocate action available from the graph's settings,
**so that** Web and Android have parity for this feature.
**Depends on**: Story 1.1.4 (`StorageLocationResolver`) — same rationale as Android's Story 3.2.2.
**Acceptance Criteria**:
- The Web equivalent of the graph edit/settings surface gets the same "Move storage location…"
  button and flow as Android's Story 3.2.2.
  - *Given* a Web user opens the graph's storage settings, *When* they tap "Move storage location…",
    *Then* the same `UnifiedLocationPicker` + Relocate/Link choice dialog opens as on Android.
- Tapping the button first calls `StorageLocationResolver.resolveOrBackfill(graphId)`, identical in
  intent to Android's Task 3.2.2b.
  - *Given* a graph with no `storage_locations` row and a connected `HostDirectorySync` handle,
    *When* the user taps "Move storage location…", *Then* `resolveOrBackfill` derives and persists
    `HostFolder` before the confirmation dialog renders.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncSettings.kt`

##### Task 3.3.3a: Call `StorageLocationResolver.resolveOrBackfill(graphId)`, then add the "Move storage location…" entry to `FolderSyncSettings` (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncSettings.kt`

---

### Epic 3.4: Relocate confirmation & progress UI
**Goal**: Build the confirmation, choice, and progress dialogs shared by both platforms.

#### Story 3.4.1: Relocate-vs-Link choice dialog
**As a** user invoking "move storage location," **I want** Relocate and Link presented as distinct,
equal-weight choices, **so that** I understand these are different operations, not one action with a
buried mode toggle.
**Acceptance Criteria**:
- A new dialog, shaped like `DiskConflictDialog.kt`'s stacked-button pattern, offers "Relocate (move
  and stop using the old location)" and "Link (keep both, stay in sync)" as separate buttons, each
  with one line of consequence text.
  - *Given* the dialog is open for a move from `SafFolder` to `AppOwned`, *When* it renders, *Then*
    both options are shown with equal visual weight (matching `DiskConflictDialog`'s stacked-button
    sizing), neither pre-selected.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveChoiceDialog.kt` (new)

##### Task 3.4.1a: Build the dialog from `DiskConflictDialog.kt`'s shape (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveChoiceDialog.kt`

#### Story 3.4.2: Confirmation dialog naming exact source/destination
**As a** user about to start a relocate, **I want** to see exactly what will move where before
confirming, **so that** I'm never surprised by which folder is affected.
**Acceptance Criteria**:
- A confirmation `AlertDialog`, modeled on `Sidebar.kt`'s graph-removal dialog shape, states the
  exact source and destination (e.g. `"Move \"My Notes\" from a folder on your device to App
  storage?"`), reassures what does not happen yet ("Your files stay where they are until the copy is
  verified."), and does **not** auto-focus the destructive/confirm action (default focus lands on
  "Cancel").
  - *Given* a relocate from `SafFolder(treeUri = "content://.../Documents")` to `AppOwned` for a
    graph named `"My Notes"`, *When* the confirmation dialog renders, *Then* its title/body text
    contains both "My Notes" and a human-readable reference to the source and destination kinds, and
    keyboard focus is on the "Cancel" button, not "Move."
  - *Given* the user presses Escape, *When* the dialog is dismissed, *Then* no copy operation starts
    (matches this repo's existing `onDismissRequest` convention).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveConfirmDialog.kt` (new)

##### Task 3.4.2a: Build the confirmation dialog, non-auto-focused destructive action (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveConfirmDialog.kt`

##### Task 3.4.2b: jvmTest — default focus lands on Cancel, Escape dismisses without starting a move (~4 min)
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/StorageMoveConfirmDialogTest.kt` (new)

#### Story 3.4.3: Progress/verify/failed UI, shaped like `ReconciliationUiState`
**As a** user waiting for a relocate to finish, **I want** to see quiescing/copying/verifying
progress with a file/page count, **so that** a multi-minute operation on a large graph doesn't look
like a bare, indefinite spinner.
**Acceptance Criteria**:
- A composable renders `StorageMoveUiState` (`Quiescing → Copying(count, total) → Verifying →
  Summary | Failed | ReopenFailed`), matching `FolderSyncSettings`'s `ReconciliationUiState`-driven
  `FolderSyncReconciliationProgress` shape; `Failed` offers only "Retry"/"Cancel," never "Delete
  anyway."
  - *Given* `StorageMoveUiState.Copying(count = 4200, total = 8030)` is emitted, *When* the
    composable renders, *Then* it shows a progress indicator reflecting "4200 of 8030" rather than
    an indeterminate spinner.
  - *Given* `StorageMoveUiState.Failed(reason = "hash mismatch on pages/foo.md")` is emitted, *When*
    the composable renders, *Then* the only actionable buttons are "Retry" and "Cancel."
- `Cancel` is enabled — not disabled or omitted — while `StorageMoveUiState.Verifying` is rendered,
  with the identical enabled treatment and click behavior it has during `Quiescing`/`Copying`. This
  repairs a Phase 4 adversarial-review UX-lens finding: an earlier design disabled `Cancel` during
  `Verifying` on the unstated assumption that verification is "a few seconds at most," but Task
  3.1.1f content-hashes every markdown file **and** every `.git` object/pack file, which
  `requirements.md`'s Rabbit Holes section already flags as a genuine multi-minute concern at
  8,000+-page graph scale — a non-cancelable dialog spanning that phase is a dead end (cross-cutting
  AC-X2), not a simplicity choice. Cancelling during `Verifying` requires no new coordinator behavior:
  `Verifying` is rendered from inside the same `BulkCopyVerifier.copyAndVerify` call `Copying` is, so
  it is already covered by Story 3.1.5's closed-driver-region cancellation handling (Task 3.1.5l) —
  this UI change only removes the composable-level restriction that kept that existing, safe
  cancellation path unreachable from `Verifying`.
  - *Given* `StorageMoveUiState.Verifying` is emitted, *When* the composable renders, *Then* the
    "Cancel" button is present and enabled (clickable), and tapping it invokes the same
    `GraphRelocationCoordinator` cancellation path as tapping "Cancel" during `Copying` does — the
    destination copy is discarded, the driver is reopened at the original location, and the dialog
    returns to Surface 4 with the source untouched.
- `StorageMoveUiState.ReopenFailed` (Story 3.1.5) renders a visually and textually distinct screen
  from `Failed` — no "Retry" button (retrying `relocate()` from the top presumes the driver can be
  closed again, which is not established here) and no reassurance that the graph is untouched (the
  whole point of this state is that the coordinator cannot confirm that). Offer only an
  acknowledgment action (e.g. "OK") that returns to the graph list, mirroring how the rest of this
  app already surfaces a graph as unavailable rather than inventing a new severity tier.
  - *Given* `StorageMoveUiState.ReopenFailed` is emitted, *When* the composable renders, *Then* no
    "Retry" button is present (distinguishing it from every `Failed` rendering, per AC31/Surface 8's
    "only Retry and Cancel" rule, which this state deliberately does not follow) and the copy does not
    include the "your original files were not touched" reassurance line Surface 8 always shows for
    `Failed`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveProgressDialog.kt` (new)

##### Task 3.4.3a: Build the progress composable from `FolderSyncReconciliationProgress`'s shape (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveProgressDialog.kt`

##### Task 3.4.3b: jvmTest — Failed state renders only Retry/Cancel (~3 min)
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/StorageMoveProgressDialogTest.kt` (new)

##### Task 3.4.3c: Render `ReopenFailed` as a distinct screen (no Retry, no "untouched" reassurance) + jvmTest asserting it (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveProgressDialog.kt`,
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/StorageMoveProgressDialogTest.kt`
- This is a plan gap this repair iteration closes: introducing `StorageMoveUiState.ReopenFailed`
  (Story 3.1.5) without a rendering task would leave the UI layer with an unhandled `when` branch (or
  worse, silently falling through to the `Failed` rendering it was deliberately made distinct from).

##### Task 3.4.3d: Enable (rather than disable) the `Cancel` button when rendering `StorageMoveUiState.Verifying`, wired to the same cancel callback `Copying`/`Quiescing` already use, + jvmTest asserting it is present and enabled (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveProgressDialog.kt`,
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/StorageMoveProgressDialogTest.kt`
- This is a plan gap this repair iteration closes (Phase 4 adversarial-review UX-lens finding): the
  composable's `when (state)` branch for `Verifying` previously rendered `Cancel` disabled (or omitted
  it), on an unstated "a few seconds at most" assumption `requirements.md`'s Rabbit Holes section and
  Task 3.1.1f's per-object content-hash verification both contradict at 8,000+-page graph scale. No
  new coordinator wiring is needed — `GraphRelocationCoordinator`'s cancellation handling already
  covers this point in `copyAndVerify` (Task 3.1.5l) — this task only removes the UI-level restriction
  and points the button at the same `onCancel` callback the `Copying`/`Quiescing` branches already
  pass.

---

## Phase 4: Link operation

### Epic 4.1: Web link generalization
**Goal**: Wire the already-reconfigurable `connectHostDirectory` into the unified picker's Link
choice, and add the missing unlink command.

#### Story 4.1.1: Wire `connectHostDirectory` into the Link choice
**As a** Web user, **I want** to establish a continuous mirror between OPFS and a real folder from
the unified move-storage flow, **so that** Link isn't buried in `FolderSyncSettings`'s single connect
button.
**Acceptance Criteria**:
- Choosing "Link" in `StorageMoveChoiceDialog` (Epic 3.4) for a Web graph calls
  `HostDirectorySync.connectHostDirectory(existingOpfsPath)`, reusing `runHostReconciliation`
  unchanged.
  - *Given* a Web user picks "Link" with destination `HostFolder("g1", "Documents")`, *When* the
    operation proceeds past `VerificationPassed`, *Then* `connectHostDirectory` is invoked (not a new
    parallel connect implementation).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt` (Link branch),
`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.1.1a: Add the `Link` branch to `GraphRelocationCoordinator`, calling `connectHostDirectory` after `VerificationPassed` (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt`

##### Task 4.1.1b: wasmJs test — Link path invokes `connectHostDirectory`, not a duplicate connect flow (~4 min)
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/GraphRelocationLinkTest.kt` (new)

#### Story 4.1.2: New `unlinkHostDirectory` command
**As a** Web user, **I want** to detach a linked folder while staying on OPFS, **so that** I can
stop mirroring without losing my in-browser copy.
**Acceptance Criteria**:
- A new `HostDirectorySync.unlinkHostDirectory(): Either<DomainError.StorageError, Unit>` clears
  `hostDirHandle` and its IndexedDB entry, stops the poll loop, and transitions
  `hostAccessStateFlow` to a new explicit "unlinked" state (not `Disconnected`, which implies an
  error) — implemented as its own state transition, not a resurrection of the retired
  `disconnectForGraphSwitch()` field-by-field pattern.
  - *Given* a graph is linked (`hostAccessStateFlow` = `Granted`), *When* `unlinkHostDirectory()` is
    called, *Then* `hostAccessStateFlow` transitions to the new unlinked state, the IndexedDB handle
    entry is removed, and the graph's `storage_locations` row updates to `kind = "AppOwned"`.
  - *Given* the graph is subsequently reopened, *When* `HostDirectorySync` initializes, *Then* it
    does not attempt to reconnect to the previously-linked folder (no stale-state resurrection, per
    `research/architecture.md` §2.2's caution).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.1.2a: Implement `unlinkHostDirectory()` as a new explicit state transition (~5 min)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.1.2b: Add an "unlinked" `HostAccessState` (distinct from `Disconnected`/`Denied`) (~3 min)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.1.2c: Wire an "Unlink" action into `FolderSyncSettings`/`FolderSyncStatusBadge` (~4 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncSettings.kt`

##### Task 4.1.2d: wasmJs test — unlink clears handle, stops polling, updates `storage_locations` (~5 min)
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncUnlinkTest.kt` (new)

#### Story 4.1.3: Reuse `FolderSyncStatusBadge` for broken-link surfacing
**As a** user whose link broke (permission revoked), **I want** the existing sidebar status badge to
surface it, **so that** there isn't a second, inconsistent indicator for the same failure class.
**Acceptance Criteria**:
- `FolderSyncStatusBadge`'s existing `Disconnected`/`Denied` states are reused unchanged for a
  broken Link established via this feature (no new badge component).
  - *Given* a Link's `HostAccessState` transitions to `Disconnected` because the browser revoked the
    directory handle, *When* the sidebar renders, *Then* it shows the existing "Folder not found —
    Reconnect" badge copy, identical to today's `FolderSyncSettings`-established Link breaking.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt` (no change expected — acceptance criterion is "verify reuse, add no new component")

##### Task 4.1.3a: Verify `FolderSyncStatusBadge` renders correctly for a Link established via `GraphRelocationCoordinator` (not just via `FolderSyncSettings`'s original connect flow) (~4 min)
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/FolderSyncStatusBadgeLinkReuseTest.kt` (new)

---

### Epic 4.2: Android Link mode (git-cloned graphs only, per ADR-003)
**Goal**: Promote the existing git-shadow-worktree write-back mechanism from an invisible cache
detail to a user-visible, explicitly-invoked continuous mirror, for git-cloned graphs only.
Plain-graph Android Link is explicitly out of scope for this project (ADR-003) — no story below
attempts it.

#### Story 4.2.1: Wire Link choice to the existing shadow-worktree write-back mechanism
**As an** Android user with a git-cloned graph, **I want** to explicitly choose "Link" between a SAF
folder and app storage, **so that** I get the same continuous mirror permission-constrained users
already get invisibly, but as a deliberate choice.
**Acceptance Criteria**:
- Choosing "Link" for a git-cloned graph's move keeps `GitShadowWorktree` in its existing cache/
  write-back mode (rather than promoting it to `AppOwned`-primary), and surfaces its sync status via
  the existing `SyncStatusBadge`.
  - *Given* an Android user with a git-cloned graph currently on `SafFolder` chooses "Link" with
    destination `AppOwned`, *When* the operation completes, *Then* `GitShadowWorktree` continues
    operating in its existing write-back-to-SAF cache mode (no change to `AndroidGitRepository`'s
    resolution logic), and the graph's `storage_locations` row remains `SafFolder` (Link never
    repoints the location of record — only Relocate does).
- Attempting "Link" for a **plain** (non-git) Android graph shows a disabled/absent Link option with
  a one-line explanation, never a broken/no-op action.
  - *Given* an Android user opens the move-storage flow for a plain markdown graph, *When*
    `StorageMoveChoiceDialog` renders, *Then* the "Link" button is either hidden or disabled with
    copy explaining continuous mirroring isn't available for this graph type yet (pointing to
    ADR-003's scope decision, not a raw error).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveChoiceDialog.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 4.2.1a: Disable/hide "Link" in `StorageMoveChoiceDialog` for plain Android graphs (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/StorageMoveChoiceDialog.kt`

##### Task 4.2.1b: Confirm "Link" for a git-cloned Android graph is a no-op on `GitShadowWorktree`'s existing mode (add a regression test, no production code change expected beyond UI wiring) (~4 min)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeLinkModeTest.kt` (new)

---

## Phase 5: Hardening

### Epic 5.1: Observability wiring
**Goal**: Satisfy requirements.md's Observability Requirements with concrete log points.

#### Story 5.1.1: Log every relocate/link operation's lifecycle
**As a** developer debugging a user-reported failed move, **I want** start/verify-result/completion
log entries, **so that** I have a debugging trail without a telemetry pipeline.
**Acceptance Criteria**:
- `GraphRelocationCoordinator` emits structured log entries (existing app log/telemetry span
  mechanism) at `MoveStarted`, `MoveVerified`, `MoveCompleted`, `MoveFailed`, each including
  `graphId`, source kind, destination kind, and operation type.
  - *Given* a relocate from `AppOwned` to `HostFolder` for `graphId = "g5"` fails verification,
    *When* the operation terminates, *Then* a `MoveFailed` log entry exists containing `"g5"`,
    `"AppOwned"`, `"HostFolder"`, and the `DomainError.StorageError` subtype name.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt`

##### Task 5.1.1a: Add the four log call sites using the existing span mechanism (~5 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt`

##### Task 5.1.1b: `businessTest` asserting all four log points fire in the happy path (~4 min)
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorLoggingTest.kt` (new)

---

### Epic 5.2: SAF grant lifecycle hygiene
**Goal**: Release stale SAF grants after a confirmed relocate away from a SAF folder, per
`research/pitfalls.md` §1.1's grant-cap finding.

#### Story 5.2.1: Release the old SAF persistable URI permission on confirmed cleanup
**As an** Android user who has relocated multiple graphs away from SAF folders over time, **I want**
old grants released, **so that** I never hit the 512-grant cap and start silently failing new picks.
**Acceptance Criteria**:
- When a relocate's source cleanup is confirmed (source was `SafFolder`), `GraphRelocationCoordinator`
  (`commonMain`) calls `quiesceStrategy.releaseSourceGrant(source)` (Story 3.1.3's port) rather than
  calling `ContentResolver.releasePersistableUriPermission` itself — the latter is an `androidMain`-
  only API, the same one-way-dependency constraint Story 3.1.3 exists to satisfy.
  `AndroidGraphMoveQuiesceStrategy.releaseSourceGrant()`'s stub (Task 3.1.3g) is filled in here.
  - *Given* a relocate from `SafFolder(treeUri = "content://.../old")` to `AppOwned` completes and
    the user confirms cleanup, *When* cleanup runs, *Then*
    `ContentResolver.persistedUriPermissions` no longer includes `"content://.../old"`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.android.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt`

##### Task 5.2.1a: Implement `releasePersistableUriPermission` inside `AndroidGraphMoveQuiesceStrategy.releaseSourceGrant()` (~4 min)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/GraphMoveQuiesceStrategy.android.kt`

##### Task 5.2.1b: Call `quiesceStrategy.releaseSourceGrant(source)` from the coordinator's confirmed-cleanup path (~3 min)
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinator.kt`

##### Task 5.2.1c: Android unit test — grant released after confirmed cleanup (~4 min)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/db/SafGrantReleaseTest.kt` (new)

---

### Epic 5.3: Live storage-migration regression test infrastructure
**Goal**: Close requirements.md's Feasibility Risk that "no existing regression test exercises a live
storage-location migration for a graph with real content."

#### Story 5.3.1: End-to-end relocate test with real git history + blocks + pages
**As a** developer, **I want** a test that relocates a realistic graph (git history, multiple pages,
blocks) and asserts full content parity post-move, **so that** this feature has the same evidentiary
bar as the rest of this codebase's crash-prevention suite (e.g. `LargeGraphWarmStartCrashTest`).
**Acceptance Criteria**:
- A `businessTest`/`jvmTest` builds a synthetic graph (git-cloned, ~50 pages with cross-references),
  runs `GraphRelocationCoordinator.relocate()` from `SafFolder`-equivalent test fixture to `AppOwned`,
  and asserts: page count, block content, and git ref/object counts are identical before and after.
  - *Given* a synthetic 50-page git-cloned graph, *When* relocate completes, *Then*
    `getAllPagesSnapshot()` (existing bounded-batch one-shot method) returns identical page content
    before and after, and `git rev-list --all --count`-equivalent object counts match.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphRelocationEndToEndTest.kt` (new)

##### Task 5.3.1a: Build the synthetic git-cloned graph test fixture (~5 min)
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphRelocationEndToEndTest.kt`

##### Task 5.3.1b: Run relocate and assert page/block content parity (~4 min)
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphRelocationEndToEndTest.kt`

##### Task 5.3.1c: Assert git object/ref-count parity post-relocate, as an end-to-end sanity check layered on top of Task 3.1.1f's per-object content-hash verification (not a substitute for it) (~4 min)
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphRelocationEndToEndTest.kt`
