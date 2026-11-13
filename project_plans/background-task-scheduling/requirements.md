# Requirements: background-task-scheduling

**Date**: 2026-11-10
**Type**: feature addition (hardening + generalization of an existing cross-platform subsystem)
**Complexity**: 3 — system design (multiple epics, new port with two adapters, two consumers, per-graph settings UI)

## Problem Statement
Git sync is meant to keep running while the app is backgrounded or closed, especially on mobile, but
the scheduling surface is thin and git-specific:

- `BackgroundSyncScheduler` (`kmp/src/commonMain/.../git/BackgroundSyncScheduler.kt`) takes only an
  `intervalMinutes`; users cannot choose jitter or a network policy.
- `WorkManagerSyncScheduler` (Android) hard-codes `NetworkType.CONNECTED`
  (`kmp/src/androidMain/.../git/WorkManagerSyncScheduler.kt:51-53`), so a "sync on Wi-Fi only" rule is impossible and a
  sync can burn mobile data.
- Every fixed-interval device wakes at the same wall-clock cadence; there is no jitter, so many
  devices hit the remote together and the schedule is predictable.
- Desktop/JVM runs sync only in-process; nothing coordinates it with the same settings.
- The port, the settings (`ScheduledSyncPreferences` holds one global boolean) and the status UI are
  all named for git sync. Other deferred work (model download, LFS transfer, project digest) each
  invent their own WorkManager wiring (`GitCloneWorker`, `DepthModelDownloadWorker`) with no shared
  scheduling contract, constraints model, or per-task user policy.
- Reliability while backgrounded is unproven end to end: Doze, App Standby buckets and OEM battery
  managers (documented in `project_plans/git-integration/research/pitfalls.md` §1 and
  `project_plans/git-sync-resilience/`) can silently stop runs, and the user has no way to see that
  a sync is late or has stopped.

## Baseline
- Android: one global on/off switch (`scheduled_sync_enabled`), a periodic WorkManager job per graph,
  15-minute floor, any-network constraint, no jitter, no Wi-Fi/charging choice. `ScheduledSyncPolicy` (pure decision:
  Run / FetchOnly / Skip with reasons) and staleness presentation already exist.
- Desktop: in-process `PeriodicSyncDriver` timer only while the app is open.
- iOS: git is a `NotSupported` stub; Web has no git sync.
- Other background jobs (`JobKind.GitClone`, `ModelDownload`, `LfsTransfer`, `ProjectDigest`) are
  surfaced read-only through `BackgroundJobRegistry` but scheduled ad hoc per feature.

## Users / Consumers
- End users who sync a graph via git on Android (primary) and Desktop, who want to say "sync about
  every hour, only on Wi-Fi" per graph and see whether it is actually happening.
- Feature code that needs deferred or periodic work (git sync now; model download / LFS transfer
  proven in this project; later project digest and others) consuming one scheduling port.
- Maintainers: need a ports-and-adapters seam so platform schedulers are swappable and testable.

## Success Metrics
Measured against the baseline above:
- A user can set, per graph and per task kind, interval, jitter, and network policy (any / unmetered-only;
  "off" is `enabled = false`, not a third policy value, and `enabled` applies to periodic tasks only: an explicit
  one-shot such as a model download always runs) and, if the port supports it, charging-only; the chosen values reach the platform scheduler
  (verified by reading the enqueued WorkManager request back, not by "no error").
- With "Wi-Fi only" set, no scheduled run starts a network call on a metered connection (asserted by an
  instrumented/Robolectric test on the constraint and by the pure policy test on the live-connection check).
- Jitter: for a given configured interval, scheduled start offsets spread across the configured jitter window
  (property test: offsets stay within `[0, jitter]`, are never negative, and never push a run past
  the platform's minimum/maximum interval bounds).
- The generic port runs at least two consumers end to end — GitSync and a model-download/LFS-transfer
  task — with zero git-specific types in the port's contract (enforced by the dependency direction:
  port lives in a neutral package and does not import `git.*`).
- Backgrounded-app reliability is observable: a run that has not happened within twice its interval (a fixed
  constant, not a setting) produces a visible "Late" state ("Hasn't run in N h"; "behind" is reserved for commits
  behind the remote; extends existing `SyncStalenessPresentation`), and the Logs screen records every scheduled
  run (background worker runs and foreground loop ticks) with its outcome/skip reason.
- Existing behaviour is preserved: `ScheduledSyncPolicy` decisions, first-sync confirmation, edit lock,
  vault-locked and conflict-pending skips are unchanged (existing tests stay green).

## Why Now / RICE-lite (triad repair 1)
Evidence labels: STATED = the owner's own request; REPO = visible in this repo's history/docs; INFERRED = reasoning, not measured.

- **Pain (REPO)**: `project_plans/git-integration/research/pitfalls.md` section 1 and `project_plans/git-sync-resilience/` already document Doze, App Standby buckets and OEM battery managers silently stopping background runs, and that the user cannot see a stalled sync. `WorkManagerSyncScheduler` hard-codes `NetworkType.CONNECTED` (`kmp/src/androidMain/.../git/WorkManagerSyncScheduler.kt:51-53`), so mobile-data use cannot be prevented (REPO). Two features (`GitCloneWorker`, `DepthModelDownloadWorker`) already wire WorkManager separately (REPO).
- **Request (STATED)**: the owner asked for a generic multi-task background scheduler with per-task user policy, not just a git-sync tweak. How many users want per-graph, per-kind tuning is INFERRED (no usage data; local-first app, no telemetry).
- **RICE-lite (INFERRED, ordinal not numeric)**: Reach = every Android git-sync user plus the model-download consumer; Impact = medium (stops metered-data use, makes stalls visible); Confidence = medium (Android timing is best-effort, jitter distribution proven only by Spike 0.1.1); Effort = Large band (see Size).
- **Rejected cheaper option**: extend `BackgroundSyncScheduler` in place with `jitter` and `networkPolicy` parameters. It delivers the metered-data fix and jitter for one consumer at a fraction of the cost. It is still not chosen because (1) the owner stated the multi-task architecture goal, (2) a second consumer is already proven in this scope (model download, one-shot, Wi-Fi-constrained), which is exactly what the in-place extension cannot carry without more git-named types, and (3) the extension would have to be redone when the second consumer arrives. It remains the documented fallback and the first migration step.

## Outcome Metrics (baseline to target; local counters only, no telemetry)
| Outcome | Baseline | Target | How observed |
|---|---|---|---|
| Scheduled runs executing within 2x the configured interval | Unmeasured (no run log) | Visible per (graph, kind): the run log plus the status line show whether each task ran within 2x its interval; OEM/field rate is reported by the owner after one release cycle, not asserted by CI | `TaskRunLog` (local), owner device checklist M-1..M-3 |
| Scheduled network calls on a metered connection under `Unmetered` policy | Possible (`CONNECTED` only) | 0 (a skip makes no network or git call) | Constraint read-back test, `NetworkGate` truth table, M-2 |
| "Late" visible when the worker is deferred | None (silent) | Status line shows "Hasn't run in N h" once last success exceeds 2x interval | `ScheduleLatenessTest`, M-1 |
| Settings adoption | 1 global boolean | Observable through local counters only: number of (graph, kind) entries with non-default overrides and run-log counts, readable on-device in Settings/Logs; nothing leaves the device | Local store reads only |

**"Late" threshold rationale**: 2x the interval is the smallest multiple that tolerates one missed or deferred period (WorkManager flex, Doze maintenance windows, the 15-minute floor) without crying wolf, yet flags a second miss. It is INFERRED, not measured, and is a fixed constant (`LATE_MULTIPLE = 2`), not a setting. It is revisited with field evidence: after one release cycle, the owner compares run-log gaps from real devices (M-1..M-3 results and Logs screen records) against the constant and changes the single constant if false "Late" or missed "Late" shows up.

## Risky Assumptions
1. Users want per-graph, per-kind tuning rather than good defaults. UNVERIFIED. Mitigation: sensible defaults (1 h, "Any network" for git, unmetered for downloads, "A little" spread for new git graphs), per-graph rows read "Using default" until changed, and jitter sits under a collapsed "Advanced".
2. Thundering-herd jitter rationale is weak for a single-owner app: there is no fleet to synchronise. Jitter is kept because the owner requested it and it is cheap (a deterministic offset plus a WorkManager flex window); it is not justified by a measured load problem.
3. Android timing is best-effort: Doze, standby buckets and OEM managers can defer or drop runs regardless of correct WorkManager use. The product answer is honest visibility (Late state, run log, restricted-background hint), not a guarantee.

## Owner-confirm Items (bundled behaviour changes, listed once)
These ship bundled and each is its own commit, so any can be dropped at PR review. They mirror plan Unresolved Questions (a)-(h). If the owner gives no answer, the stated default stands and implementation proceeds.

| Item | Change | Default if no answer |
|---|---|---|
| (a) | Model-download default network `CONNECTED` to unmetered (`TaskSpec.defaultConfig`) | Ship the change |
| (b) | Per-request "Download now anyway" action | Ship |
| (c) | LFS defers on an unknown network, Android only (Desktop unchanged) | Ship |
| (d) | `GraphManager.onGraphRemoved` callback plus orphan reconcile and startup self-heal | Ship |
| (e) | Migration `enabled = legacy flag && graph has a remote` (a never-opened remote-bearing graph turns on, reproducing old behaviour) | Ship |
| (f) | Desktop git stays on `PeriodicSyncDriver` this release | Ship (follow-up gated on a parity test) |
| (g) | Default spread "A little" (6 min at 1 h) for new git-sync graphs; migrated graphs keep 0 | Ship |
| (h) | Desktop radio-group arrow-key navigation | Ship |

## Size
Large (3–15M CU) — informational, INFERRED band per ESTIMATION.md; includes review/verify. Full scope is
the default; not a gate.

**Wall-clock blockers**: (1) device/emulator verification of Doze/standby behaviour on the `Pixel_Fold` AVD and
(owner) a physical device for OEM battery-manager behaviour — owner runs; the owner cannot use `adb logcat`, so
verification reads persisted logs; (2) Android scheduler timing runs are slow by nature (15-minute floor) and need the
test WorkManager driver (`WorkManagerTestInitHelper`) for CI; (3) the open PR stack on `main` touching git sync
(check `gh pr list`) may need to merge first.

## Constraints
- Ports-and-adapters: the scheduling port and its value types live in `commonMain` in a neutral package (not `git`);
  Android WorkManager and Desktop in-process timer are adapters. Consumers depend on the port only.
- Android platform limits are facts the design must respect, not fight: 15-minute minimum periodic interval, Doze /
  App Standby / OEM restrictions, WorkManager flex windows; no guarantee of exact time.
- Existing rules in CLAUDE.md apply: no `rememberCoroutineScope` escaping composition; long-lived scopes carry a
  `CoroutineExceptionHandler`; Android `Application.onCreate` catches `Throwable`; settings reads fail closed
  (a settings failure must never start background network activity); new tables go in `MigrationRunner.all` and
  `.sq` change requires regenerating SQLDelight sources; wiring gates (`WiringCompletenessTest`, `wiring-catalog.txt`,
  new screens in `first-open-screens.txt`) must stay green; log through `Logger`, not `android.util.Log`.
- Bazel is the canonical build; Gradle only for what CLAUDE.md lists.

## Non-functional Requirements
- **Performance SLO**: a scheduled tick that is skipped by policy makes no network or git call; policy evaluation is
  pure and cheap. No standing unbounded DB collectors (graph-scale read rules).
- **Scalability**: tens of graphs × a handful of task kinds; per-(graph, task-kind) unique work names stay bounded.
- **Security classification**: internal. No new credential surface; credentials stay in the existing vault (a locked
  vault blocks a run, as today).
- **Data residency**: no special requirements.

## Scope
### In Scope
- A neutral, platform-agnostic background-task port in `commonMain`: task kind, schedule (interval + jitter),
  constraints (network policy any/unmetered; "off" is `enabled = false`; optionally charging / battery-not-low), run result with a skip-or-failure reason,
  and a registration surface so features declare a task without touching platform code.
- Android WorkManager adapter implementing the port with constraints mapped to `NetworkType`, jittered scheduling,
  stable unique work names per (graph, task kind), WorkManager backoff for one-shot work only (periodic work never
  returns `retry()`: the next period is the retry, per `project_plans/git-sync-resilience/` ADR-002), and pause/resume
  around graph moves (existing behaviour preserved; the port's `pause`/`resume` is an internal quiesce, distinct from the user-visible "Paused" error state).
- Desktop in-process timer adapter behind the same port with the same settings (runs while the app is open).
  Owner decision recorded (Phase 4 repair): in this release Desktop git sync STAYS on `PeriodicSyncDriver` (settings,
  jitter and run log feed it); `InProcessTaskScheduler` ships constructed and is proven by the shared contract suite
  and a fake/second periodic consumer. Moving Desktop git onto it is a follow-up gated on a status-line and
  failure-pause parity test.
- Items beyond the original brief, included deliberately and flagged for owner confirmation at PR review (each is its own commit and can be dropped; none blocks implementation): orphan reconcile of scheduled work for removed graphs with a `GraphManager.onGraphRemoved` callback and a startup self-heal that re-applies from the applied-config ledger (fail closed on an unknown or corrupt graph registry); model-download default network `CONNECTED` -> unmetered plus a per-request "Download now anyway" action; LFS defers on an unknown network on Android only (Desktop unchanged); a "default for new graphs" row per kind (git-sync default spread "A little"); Desktop radio-group arrow-key navigation; migration `enabled = legacy flag && graph has a remote` (a never-opened remote-bearing graph turns on, which reproduces what the old composition-driven scheduling did when such a graph was opened).
- iOS: port compiles with a `None`/stub adapter (a BGTaskScheduler adapter is deferred until iOS git is real).
- Migrate GitSync onto the port; keep `ScheduledSyncPolicy` as the gate inside the git task.
- Prove the port with a second consumer: model download / LFS transfer scheduled and constrained (Wi-Fi-only) through
  the same port.
- Per-graph, per-task-kind settings model, persisted (replacing the single global boolean without losing a user's
  existing "enabled" choice), plus Settings UI: interval, jitter, network policy, enabled.
- Observability: persisted per-run outcome log, "Late" schedule state (not "behind"), restricted-background (Doze/battery-saver)
  hint using the existing `BackgroundRestrictionProbe`.
- Tests: pure-policy and jitter property tests (commonTest), fake-adapter port contract suite run against both
  adapters, Robolectric WorkManager tests, wiring-gate entries.

### Out of Scope
- A BGTaskScheduler (iOS) or Web/Service Worker adapter.
- Exact-time scheduling (`AlarmManager`), foreground services, push-triggered sync (see `project_plans/push-architecture/`).
- Changing git merge/conflict/first-sync semantics or the resilience work in `project_plans/git-sync-resilience/`.
- A general cron/calendar scheduler (fixed interval + jitter only).
- Migrating `ProjectDigest` and other tasks (the port must make them possible; they migrate in follow-ups).

## Rabbit Holes
- WorkManager periodic work and jitter: `PeriodicWorkRequest` has only a flex window, no native random jitter; jitter may
  need a per-run randomised initial delay or re-enqueue as chained one-time work. Decide in planning, and check it against the
  existing `WorkManagerSyncSchedulerRetryOwnerTest` / `UniqueWorkTest` invariants.
- "Wi-Fi only" semantics: `NetworkType.UNMETERED` is not "Wi-Fi" (metered Wi-Fi hotspots, unmetered cellular plans); the
  policy and its copy must say "unmetered". The live check inside the worker and `AndroidNetworkMonitor` must agree
  with the constraint.
- Desktop has no metered-network signal on JVM; the unmetered option may need to degrade or be disabled with a reason.
- Migrating the existing `scheduled_sync_enabled` global flag to per-graph keys without silently turning sync on or off.
- Per-graph × per-kind unique work names vs. the existing `workNameFor(graphId)` and the graph-move pause/resume hooks.
- OEM battery managers can kill background work regardless of correct WorkManager use; the product answer is visibility
  (behind-state, hint), not a fix.

## Alternatives Considered
- Extend `BackgroundSyncScheduler` in place with `jitter`/`networkPolicy` params: smallest change but keeps git naming
  and one consumer; rejected as the end state, though it may be the first migration step.
- Per-feature WorkManager workers with their own constraints (status quo): duplicated constraint logic, no shared settings UI.
- Third-party scheduling library: none targets KMP + WorkManager + in-process timers with this policy model; not researched deeply
  yet — a research-phase question.
- Push-triggered sync instead of polling: separate effort (`push-architecture`).

## Feasibility Risks
- Android may defer or drop runs on restricted devices; success is "observable and honest", not "guaranteed on schedule".
- Per-graph settings multiply WorkManager unique-work entries and UI rows; bounded (graphs x kinds). Decision (Phase 4 repair): no hard cap; the Settings list is virtualized, shows one summary line per kind, and adds a search field at 8 or more graphs.
- Network-policy semantics differ across Android API levels and OEMs.
- Open stacked PRs on git sync may conflict with the migration.

## Observability Requirements
- Persisted per-run record (task kind, graph, scheduled vs. actual start, outcome or skip reason, constraint state) readable
  from the in-app Logs screen via `Logger` so it survives without `adb logcat`.
- Staleness signal reused from `SyncStalenessPresentation`: last successful run vs. interval; "Late" when over a fixed multiple of 2 (constant, not configurable; "behind" stays reserved for commits behind the remote).
- No remote alerting (local-first app); the user-facing status line is the alert.

## Risk Control
- Feature flag / kill switch: per-(graph, task kind) enabled switch defaulting to off; global fail-closed read of settings
  (existing rule). Existing users' current "enabled" state is migrated, not reset.
- Rollback: revert the PR; the old `scheduled_sync_enabled` key is retained read-only for one release so rollback loses
  nothing. Android keeps the same unique-work name for git sync and `UPDATE`s the running job in place (no cancel-then-enqueue window).
- Staged rollout via the existing release-please pipeline; no server component.

## Open Questions
- Jitter implementation on WorkManager (initial-delay randomisation vs. chained one-time work vs. flex window) and its
  effect on the 15-minute floor.
- Does charging-only / battery-not-low belong in v1 constraints, or only network + interval + jitter? (Default: include,
  cheap to map; settings UI exposes network policy first.)
- Is the generic port a `commonMain` package `dev.stapler.stelekit.jobs.schedule` alongside `BackgroundJobRegistry`, and how does
  it relate to the read-only `BackgroundJobRegistry` (same kinds, separate responsibilities)?
- Where do per-graph per-kind settings persist (Settings key-value vs. a DB table needing `MigrationRunner` entries)?
- Which Desktop signal, if any, can approximate "unmetered"?
- Which model-download/LFS-transfer entry point is the concrete second consumer (`DepthModelDownloadWorker`,
  `LfsTransferPolicy`)? Research to confirm.
