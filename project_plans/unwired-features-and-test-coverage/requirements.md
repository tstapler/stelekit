# Requirements: unwired-features-and-test-coverage

**Date**: 2026-10-27
**Type**: feature addition + test-coverage planning (cross-cutting)
**Complexity**: 4 — high-stakes / cross-cutting (raw-SQL surface, background sync, media pipeline, four subsystems)

## Problem Statement
The owner (sole user) ships a Kotlin Multiplatform outliner. A scan of production types in `kmp/src` and `androidApp/src` for names referenced nowhere outside their own file (2026-10-27, name-based grep, noisy) found subsystems that exist but are not reachable from the running app, and coverage is planned feature by feature with no repo-wide method. Two problems:

1. **Unwired features.** Code that cannot run in production cannot be tested end to end, and rots. Verified unreferenced outside own file / tests: `DesktopSyncScheduler` (only `WorkManagerSyncScheduler` implements `BackgroundSyncScheduler` in use); `AssetPipelineService`, `AssetIndexService`, `AssetMoveService` (tests only); `OcrPlugin`, `CloudClaudePlugin`, `CloudVisionPlugin`, `PdfTextPlugin`, `OnDeviceLabelingPlugin`; `RTCManager`, `VectorSearch`/`InferenceWorker`, `DatalogEngine`/`VisualQueryBuilder`, `JsPluginBridge`. The dev console / SQL viewer (PR-C of `project_plans/dev-console-git-sync-fix/`, planned 2026-10-10) was never built: no `console/` source dir, no developer-mode toggle.
2. **No comprehensive coverage plan.** Existing per-area plans (`annotate-ui-test-coverage`, `asset-service-test-coverage`, `db-repository-test-coverage`, `editor-subsystem-coverage`, `doc-coverage`, `platform-perf-google-coverage`) and CI gates (jacoco, incremental coverage + mutation in `kmp/build.gradle.kts`) exist, but nothing states what "comprehensive" means across the repo, which gaps remain, or how each newly wired feature proves it works.

**Why it hurts now.** The owner cannot inspect the database on Android without `adb`, and `adb logcat` crashes the owner's Linux machine (repo `CLAUDE.md`, "Android debugging"). The 2026-10-27 OOM on the 9.5k-page graph (PR #501, `fix/android-oom-reconcile-leaks`) was therefore diagnosed offline from exported logs and a perf export, and that PR states its cure is unproven ("no heap dump"). Dormant code is the second cost: the asset pipeline that would index a 451 MB-class graph's files exists but never runs, so the Assets screen can only show an empty index. The problem is gone when (a) the owner can run a read-only query and fetch diagnostics on the phone and (b) the scan finds no unreferenced in-scope type apart from the explicit allowlist.

## Baseline
- Android on-device debugging relies on persisted logs and exports (Logs screen diagnostics, perf export, `DatabaseOpenDiagnostics`); the 2026-10-27 OOM analysis was done offline from those files. No in-app query tool.
- Desktop has no scheduled sync (VERIFIED 2026-10-27: `grep -rl DesktopSyncScheduler kmp/src androidApp/src` returns only its own file `jvmMain/.../git/DesktopSyncScheduler.kt`, and `GitSyncService.startPeriodicSync` has no production caller; the scheduler is superseded by the in-service timer, see plan Disposition Register row 1). `WorkManagerSyncScheduler.schedule` is reached only after a clone or storage move, so the Android periodic worker is not enqueued in normal use either.
- Asset pipeline Phases 0, 1, 3–7 of `project_plans/image-pipeline/implementation/plan.md` not started.
- Coverage measured per PR (diff coverage + mutation), not as a repo-wide map of untested behavior.

## Users / Consumers
The owner on Android (no adb), Desktop, and Web; future contributors.

## Success Metrics
1. Every in-scope feature is reachable from the shipped app and exercised by at least one end-to-end test through its real entry point (not only a unit test of the class). Measured: zero in-scope types left in the unreferenced-outside-own-file/tests-only scan, **except an explicit allowlisted set recorded with a reason**: the iOS-only items (`CoreMlImageLabeler`, `IosPdfTextExtractor`; iOS is out of scope, so they use a non-expiring `out-of-scope-ios` allowlist class), verified false positives, and items awaiting an open owner decision (OD-1..OD-6). A feature counts as done only when its owner device pass is recorded and its flag-default decision is written down (on, or off with a dated reason), because every feature ships flag-off.
   **Lag fallback (renewal rule)**: allowlist entries other than `out-of-scope-ios` expire after 90 days. If an owner decision or owner device pass is still open at expiry, the entry may be renewed by editing its date, and only with a reason naming the open item (for example `awaiting OD-1` or `awaiting pass 12.2b`); a renewal without that name fails the test, so an entry cannot be silently extended. A device-pass lag never fails the gate: metric 1 is then reported as two numbers, "wired and tested" (code-complete, entry-point test green) and "done" (owner pass recorded plus flag default written down), and the final report prints how many entries were renewed and which items they wait on. Metric 1 is closed only when "done" equals the in-scope total or every shortfall is a named, renewed entry.
   **Owner-gated exception**: an entry owned by a unit that waits on an unanswered OD or an unscheduled owner pass may be converted once, at its first expiry, to `blocked-on-owner <OD-n or pass 12.n>`, which never expires, takes no renewals and is listed with its age in the nightly job and the final report (plan Glossary `WiringAllowlist`).
   **Renewal cap and escalation**: an entry may be renewed at most **2 times** (three 90-day windows, at most 270 days from first listing; the renewal count is a field on the allowlist line and the test fails an edit that lowers it). The third expiry is not renewable: the test fails with `renewal limit reached: <Class> awaiting <item>`, and the only ways to clear it are to wire the class, delete it (OwnerGate), or amend ADR-001 with a new owner decision recorded in the plan's Unresolved Questions. Entries seeded at U1 under reason `awaiting <unit>` (seam implementations that exist today but are constructed by no root; see plan Task 1.1b) count toward the same cap, and the wiring unit that constructs or deletes the class removes its line in the same PR (a class both constructed in a root and listed fails as a stale entry).
2. A written coverage map lists each module with current line/branch/mutation numbers (from jacoco/PIT, run not estimated), its risk tier, and a target; gaps ranked by risk × churn. **Tier thresholds are INFERRED placeholders until the Story 0.1 baseline run replaces them** (source: plan Epic 11 tier table and `decisions/ADR-003-coverage-program.md`): T1 85% line / 70% branch with nightly report-only PIT, T2 70% / 55%, T3 40% line plus a first-open smoke test per screen, T4 excluded. Metric 2 is met when every package has a measured number, a tier, a target and a rank in `docs/coverage/coverage-map.json`, ADR-003's addendum records the measured-informed targets, and no T1/T2 package is below its recorded ratchet floor.
3. Dev console: in any build (release included) the owner can turn on developer mode, run a read-only SQL query and fetch diagnostics on-device without a rebuild; plain `sql` can never write. Guarded writes (`sql!`, confirm plus automatic backup) and backup restore are available in **dev/internal builds only** (`ConsoleBuildFlags.WRITES_ENABLED=true`); they are compiled out of release builds until the owner records device pass 12.1 on a COPY of the real database, after which a separate one-line PR enables them for release (plan Risk Control, ADR-002/ADR-006). Metric 3 is therefore met for reads in release at U4 and for guarded writes in release only after pass 12.1.
4. Desktop sync: with a configured interval the app pulls remote commits while open; proven by a test and a manual run. **Dependency**: the pull step requires dev-plan (`dev-console-git-sync-fix`) PR-A1 guards and PR-A2 policy, which this project does not fund; this project delivers fetch-only staleness visibility and reports metric 4 as "partially met" until those ship.
   **Acceptance observation for the delivered half (staleness visibility; not a new metric number)**: with a configured interval and a remote that gains a commit, the app shows the behind-count within one interval plus one tick without the owner pressing Sync, and the "Last checked" line is no older than twice the interval while the app is open and the loop is not paused. Evidence: automated IT-16 / UT-123..UT-125 and the manual run 12.3 (after U2). Metric 4 is reported as "visibility met, pull pending" until U16.
5. Dormant search/RTC/plugin code: each item has a recorded wire-or-delete decision; wired ones satisfy metric 1, deleted ones are removed with their tests.

**Outcome observations (acceptance observations recorded at the owner passes; they are not numbered metrics, so metrics 1-5 keep their meaning)**:
- O1 (at pass 12.1): the owner uses the on-device console to diagnose one real Android issue from the live or copied database (for example the heap or table-size question behind PR #501) and records the issue, the query or command used and what it showed. Not met if the console was only demonstrated on synthetic data.
- O2 (at pass 12.2a/12.2b): the owner finds at least one real asset through Assets search or the global-search Assets section by text that was extracted from it, and records the query. Not met if search only ever matched by filename.
- O3 (at pass 12.2a): the owner records whether the Assets screen is non-empty after indexing (Task 0.4b compares with the pre-U7 state).

**Riskiest assumption (named; falsified early, with a fallback)**: *the asset pipeline (index backfill, then on-device ML, then cloud) can run over the owner's 9.5k-page / 9.5k-asset graph on a 512 MB Android heap without an `OutOfMemoryError` or process death, using dormant code that was written against an older architecture.* It is the assumption most likely to be wrong because the existing OOM (PR #501) is unproven-cured and the plugins decode images. **Falsifying test**: Task 5.2c, `AssetPipelineHeapGateTest` on the `Pixel_Fold` AVD (blocking gate for U8; U7's indexing-only backfill stays flag-off until a recorded pass). The console-side counterpart is Spike 0.3 (0.3d cancel on the `requery` driver, 0.3e authorizer), which gates U4-U6. **Fallback if 5.2c fails**: U8 does not merge and `asset_pipeline_enabled` stays off; the owner-visible fallback ladder is (1) halve the batch and the decode budget and rerun, (2) ship indexing only (U7) with plugins unconstructed, (3) re-plan the pipeline behind a separate process-safe worker. If Spike 0.3d fails, Android ships the console without `sql!`. Neither fallback removes a story from scope; they change the order and the default flags.

## Size
XL (> 15M CU) — INFERRED, informational. Full scope is the default.

**Wall-clock blockers**: owner device runs for console/asset pipeline on Android (owner); CI runs; PR #501 and other branches awaiting merge; #397 is already MERGED (`gh pr view 397`), so the `RepositorySet.sqlConsole` field needs only a rebase/conflict check at Task 3.2a, not a wait; owner decisions on dormant code (wire vs delete) where research cannot decide.

## Constraints
- Bazel is canonical; SQLDelight generated sources must be regenerated when `.sq` changes; `@DirectSqlWrite` and `MigrationRunner` rules apply.
- Raw SQL surface is security-sensitive (ADR-002 of dev-console plan): plain `sql` is read-only and cannot be switched to write; writes exist only through `sql!` behind an explicit confirm and a backup (see Owner Decisions, below).
- Android: uncaught Throwable kills the process; long-lived scopes need exception handlers; no logcat.
- Heap is capped at 512 MB on the target phone; new background work must be bounded (see PR #501).
- Drafts PRs; no attribution lines.

## Non-functional Requirements
- **Performance SLO**: new background work must not increase startup heap or block the UI; pipeline runs bounded and cancellable.
- **Scalability**: 9.5k-page graph, 451 MB DB on device.
- **Security classification**: confidential (personal notes; cloud OCR/labeling plugins send images to third parties — opt-in only).
- **Data residency**: no special requirements.

## Scope
### In Scope
- Coverage strategy and map for the whole repo (tiers, metrics, gap ranking, gating policy) building on existing plans and CI gates.
- Wiring + tests for: dev console / SQL viewer; desktop scheduled sync; image/asset pipeline (services and plugins); wire-or-delete resolution for RTCManager, VectorSearch/InferenceWorker, DatalogEngine/VisualQueryBuilder, JsPluginBridge, and the other unreferenced items found by a rerun of the scan with false positives filtered.
- The small wirings named in the plan's Epics 7 and 8, each independently shippable (owner pain: none stated; these are hygiene wirings of already-written code, approved 2026-10-27 under "wire if it adds functionality", with no outcome observation attached; desktop scheduled sync's pain is likewise UNVERIFIED by the owner and stated as hygiene: a desktop session that never checks the remote shows a stale working copy until `Sync` is pressed) (register rows in parentheses): Wayback Machine archive action via `KtorWaybackMachineService` and the `BlockItem.onArchiveUrl` hook (row 29, Story 7.1); desktop rich-text paste via `HtmlClipboardReader` (row 25, Story 7.2); measurement device factories (rows 30-31, Story 7.3); `CommandManagerCoordinator` adoption (row 24, Story 7.4); Google Photos picker and Drive export/browser (rows 27-28, Stories 8.1-8.3).

- **Owner-approved additions (amendment 2026-10-27, from the "wire; build out stubs if needed" decision; the plan builds these and they are not cut)**:
  - Real OCR: new `TextRecognizer` port with an ML Kit implementation, rewriting `OcrPlugin` (it currently joins image labels, which is not OCR).
  - Cloud Claude plugin build-out: Anthropic image request on a shared `AnthropicMessagesClient` extracted from `ClaudeLlmFormatterProvider`, per-graph consent gate, caps, EXIF strip, revocation (no second HTTP client or key store).
  - USB-serial measurement support: `AndroidUsbSerialFactory` implementation with the LGPL `usb-serial-for-android` dependency (Gradle and `MODULE.bazel`), and `KeyboardEmulationDeviceFactory` registration.
  - Google Photos picker and Drive export/browser surfaces (Epic 8).
  - Global-search "Assets" section (Task 5.6a) and the Settings > Plugins surface with a single enabled-state registry.
  - Conditional semantic search (Epic 9), only if the owner confirms OD-1 and the latency spike passes.
  - Console backup restore and backups list (`graph restore-backup`, `backups`; Stories 3.4): dev-plan-derived (dev ADR-002, ADR-006) and recorded here as in scope, because guarded writes with an automatic backup are not safe to ship without a way back.
  - Refactors required to wire safely: `PeriodicSyncDriver` extraction from `GitSyncService`, `WiringCompletenessTest` resource bundling, `Redactor` consolidation.

### Out of Scope
- New product features beyond wiring existing code, except the console and the owner-approved additions listed under In Scope.
- iOS-specific work (no Bazel/KMP path yet).
- Rewriting existing passing tests.

**Explicit "not doing" list** (so a reader does not assume these ride along):
- `CloudVisionPlugin` and any second cloud provider (owner decision 2026-10-27: one cloud provider; the class is deleted by Task 6.4a).
- Desktop OCR (Tesseract/tess4j) unless the owner chooses it at OD-3; the default is `NoOpTextRecognizer` and the Plugins row says "not available on desktop".
- Real webcam capture on desktop or web (`WebcamCameraProvider`, `WebCameraProvider` are deleted, not built out).
- A JavaScript plugin engine (`JsPluginBridge` is deleted unless the owner chooses otherwise at OD-2) and real-time collaboration (`RTCManager` is deleted).
- A Datalog engine (folded into `{{query}}`), and semantic search unless OD-1 = build and Spike 9.1 passes.
- The desktop/Android sync pull step and the first-sync review gate (dev-plan PR-A1/PR-A2, Task 2.2d0); this project ships fetch-only visibility and a cutover task (U16) that waits on them (see OD-6).
- Notifications or a foreground service for background backfill and scheduled sync while the app is closed (decided in `design/ux.md`, GAP-10).
- Fixing open bugs BUG-001..BUG-004 (`docs/bugs/open/`): none intersects planned work (plan, Risk Control).
- Deleting cloud-derived descriptions in bulk beyond the single "Delete AI descriptions" action, and recalling images already sent to a provider (not technically possible).

## Rabbit Holes
- The name-based scan is noisy (Android manifest components, serialization DTOs, same-file helpers); a precise reachability analysis may need compiler/IDE tooling.
- Cloud plugins need credentials, consent UX, and cost controls.
- Datalog/vector search may overlap SQL FTS already shipped; wiring could duplicate features.
- Mutation testing on the full repo may be too slow for CI; needs tiering.

## Alternatives Considered
- Delete all dormant code (cheap, loses planned features) vs wire (cost, value uncertain) — decided per item in research.
- Per-feature coverage plans only (status quo) vs one repo-wide map.

## Feasibility Risks
- Some dormant code may be incompatible with the current architecture (repository/actor rules), making wiring a rewrite.
- Console on Web has no read-only connection (dev-console plan open question).

## Observability Requirements
Console and pipeline actions log through `Logger` to the persisted sink; pipeline emits spans via `PerformanceMonitor`; sync scheduler logs each tick and last-success time.

## Risk Control
Console ships dark behind a developer-mode toggle. Plain `sql` is read-only; guarded writes (`sql!`, explicit confirm plus automatic backup) are the only write path, per the owner-decision amendment below, and are compiled out of release builds until device pass 12.1. Pipeline and desktop sync behind settings flags, default off until verified on device. Each unit independently revertible.

## Open Questions
- ~~Which dormant items have value worth wiring~~ ANSWERED: the plan's Disposition Register (37 rows, ADR-001) gives each item one disposition; remaining owner calls are OD-1..OD-6 (plan Unresolved Questions).
- What coverage numbers exist today per module: OPEN until the Story 0.1 baseline run (only stale change-scoped figures exist, dated 2026-10-16).
- ~~Is `DesktopSyncScheduler` truly unwired?~~ ANSWERED: yes; it is superseded by `startPeriodicSync` inside `GitSyncService` and is deleted by Task 2.1c after owner confirmation (OD-5).
- ~~PR #397 blocker~~ ANSWERED: merged; rebase check only.
- OPEN: the pull half of scheduled sync (metric 4) waits on dev-plan PR-A1/PR-A2, which this project does not fund; the owner schedules them. **Listed as owner decision OD-6 (plan Unresolved Questions, ADR-008)**: decision requested by the time U2 is released: either name who schedules dev PR-A1/PR-A2 and by when, or record "not scheduled" (then metric 4 stays "visibility met, pull pending" and U16 stays unfunded). The final report prints the recorded answer.

## Owner Decisions (2026-10-27, after Phase 2 research)
- **Dormant code**: do NOT default to deleting. If an item supports functionality that adds to the project, wire it (build out stubs if needed). Delete only items that are superseded or duplicate (e.g. `DesktopSyncScheduler` vs ADR-004's in-service timer, the `command/` package vs `editor/commands`). The plan must classify every dormant item as wire / delete-superseded with the functionality it would add; owner confirms any deletion at implementation time.
- **Dev console**: first slice is read-only SQL plus guarded writes (ADR-002: writes behind confirm with automatic backup). Supersedes "read-only by default" in Risk Control above: read-only is the default mode, writes require explicit confirm.
- **Cloud plugins**: wire `CloudClaudePlugin` with a per-provider consent dialog, session cap, EXIF stripping, and revocation; drop `CloudVisionPlugin` unless the plan finds a reason to keep it.
