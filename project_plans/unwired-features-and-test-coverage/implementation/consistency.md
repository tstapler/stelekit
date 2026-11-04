# Cross-artifact consistency: unwired-features-and-test-coverage

**Date**: 2026-10-27 (counts re-derived by script in triad repair iteration 5; the iteration-3 run was written against 127 tasks and the iteration-1 run against 113 tasks / 27.5M CU)
**Checked**: `requirements.md`, `implementation/plan.md`, `design/ux.md`, `implementation/validation.md`, `decisions/ADR-001..008`, and the repo facts the artifacts cite. Method: `cross-artifact-consistency-prompt.md` (coverage gaps, scope drift, UX-plan misalignment, terminology drift, contradictions), run by script where a number is involved. Severity: contradictions and coverage gaps = BLOCKER, scope drift and terminology = CONCERN, UX alignment = NITPICK.

**Totals now: 0 blockers, 0 open concerns, 0 open nitpicks; 2 INFERRED and 2 UNVERIFIED items are carried (listed at the end).** The three original blockers (B1-B3) were closed in repair iteration 2 and re-verified here. Concerns C2, C8, C9, C11, C12, C13 and nitpicks N1-N6, which iteration 2 left open, are dispositioned below.

## Verified consistent (script output, 2026-10-27)

| Check | Result |
|---|---|
| Stories / tasks | `grep -c '^#### Story'` = **52**, `grep -c '^##### Task'` = **140** (recounted after triad round 3; earlier text says 127 or 135), no duplicate ids; the estimate table has 52 rows, its task column sums to **140**, and each row's task count equals the number of `##### Task` headings under that story |
| Per-row arithmetic | every row: class sum = raw tokens (XS 20k, S 55k, M 130k, L 240k), CU = raw x 1.48, CU incl. verification = raw x 1.48 x 1.75 (rounded); zero mismatches |
| Totals | raw **13,035k**, **19.3M** CU before verification, **33.8M** CU with the x1.75 multiplier (recounted by script from the table: 19,287k and 33,760k as summed per row); the plan's "Totals" paragraph, `validation.md` header and `pre-mortem.md` now state these; the only remaining mentions of 113 tasks, 121 tasks, 27.5M or 29.2M are the dated iteration history sentences |
| Disposition Register | **37** rows (matches "37 rows" in Requirements traceability and `requirements.md`) |
| Delivery units | **20** (U0..U19; U18 and U19 are the one-line post-pass flip PRs), matches "20 delivery units" in `validation.md` |
| UX criteria | `ux.md` has **106** criteria numbered 1-106 equal to `UW-01..UW-106` (recounted after triad round 3); `validation.md` has a row for each of the 106 (set equality, script) |
| Test ids | `validation.md`: **171** unit ids (UT-01..UT-171) and **103** integration ids (IT-01..IT-103, of which IT-61 is the migration test, so 102 integration plus 1 migration), contiguous, no gaps |
| Task and story references | every `Task N.Nx` and `Story N.N` cited in `plan.md` exists as a heading; the three non-matches (`0.4b`, `2.2d0`, `2.3e`) are the device check 0.4b and dev-plan tasks, which are not headings of this plan |
| ADR links | all eight `../decisions/ADR-00n-*.md` links in the plan resolve to files |
| Stale strings | none of `[A]`, "AI Providers", "Settings > Developer > Console", "Sync in progress", "Last synced" (outside the post-U16 note) remains in `ux.md` / `plan.md` / `validation.md` |
| Backup retention | Glossary `ConsoleBackup`, Story 3.3, ADR-006 and UW-11 all say default 2 newest, configurable to 5 |
| Repo facts re-checked by command | 14 files match `ghp_\|github_pat_\|[redacted]\|REDACTED`, of which 2 are production; `android:allowBackup="false"` at `AndroidManifest.xml:34`; `DesktopSyncScheduler` appears only in its own file; `startPeriodicSync` has no production caller; `NetworkMonitor` exposes only `isOnline` / `observeConnectivity()`; PR #397 merged; `docs/bugs/open/` holds BUG-001..BUG-004 |

## Closed in earlier iterations, re-verified

- **B1** (write verb): `sql!` is the only write verb everywhere; one denial string `Denied: plain sql is read-only. Use sql! for writes.` appears in the plan, `ux.md` and `validation.md` (UW-05, UT-14, UT-100).
- **B2** (credential denial): `ux.md` C3 and the plan agree: column-level authorizer only where Spike 0.3e = YES, otherwise aliases visible and the `Redactor` applies.
- **B3** (cloud text storage): `cloud_description` and `ml_tags_source=CLOUD_CLAUDE` are used; no new column. Note the original finding was wrong about the schema (`asset_index` already has those columns, `SteleDatabase.sq:1152,1161`).
- **C1, C3, C4, C5, C6, C7, C10**: Glossary retention fixed; Story 0.3 sits in U0; waves match unit dependencies (the engineering lens spot-checked lines 1158-1167); Task 5.5d removes only `PluginHost`/`Plugin`; Disposition vocabulary now includes the `Keep` and `Wire` qualifiers and the two conditional fallback deletions are on the OD-5 list; the iOS pair uses the non-expiring `out-of-scope-ios` class; UW-16 is DEFERRED(U16).

## Concerns that were open, now dispositioned

| # | Finding | Disposition |
|---|---|---|
| C2 | `graph` family "not built" yet restore is built; restore not in the requirements | **Fixed.** Epic 3 now has a "Command surface" paragraph: of the `graph` family only `graph restore-backup <name>` is built, plus the read-only `backups` list; `backups restore <name>` is an alias for the same handler. `requirements.md` In Scope records restore and the backups list as dev-plan-derived and in scope |
| C8 | Seam list drift (Glossary 5, Task 1.1a 7, `Plugin` and `CameraProvider`); Epic 7 goal row numbers | **Fixed.** One list of six seams in Glossary and Task 1.1a (`BackgroundSyncScheduler`, `AssetPipelinePlugin`, `ConsoleCommand`, `MeasurementDeviceFactory`, `WaybackMachineService`, `CameraProvider`); `Plugin` dropped (Task 5.5d deletes it); `CameraProvider` counts only for implementations remaining after Task 10.5a. Epic 7 goal now reads rows 24, 25, 29, 30, 31 plus 14 and 33-36 via Task 7.5a, row 37 via Story 2.2. New test UT-149 asserts the list equals the Glossary's |
| C9 | `PipelineProgress`, `PauseReason`, `ScheduledSyncStatus` too narrow for the UX states | **Fixed.** `PipelineProgress` gains `Cancelled`, `Finished`, `Stopped(GraphSwitched)`; `PauseReason` gains `DailyCap`; `ScheduledSyncStatus` gains the sealed `SyncPhase` (`NotScheduled`, `WaitingFirstTick`, `Idle`, `InProgress`, `Offline`, `NeedsAttention`, `Paused`) and `lastFailureCause`. Story 5.5 lists the Start again / Retry failed / Dismiss / Resume controls |
| C11 | Search query naming and snippets | **Fixed.** One query pair for both surfaces: `searchAssetsLight` (rows) + `selectAssetsSearchPreview(uuids)` (bounded excerpt and matched column), both created in Task 5.1f and used by Task 5.6a; Story 5.6 no longer names `searchAssets`. Row UT-148 |
| C12 | `CoverageRatchet` granularity | **Fixed.** Per package; the tier sets the target and whether a drop fails (T1/T2) or warns (T3). Glossary and Story 11.2 agree with Stories 11.3 and 11.4 |
| C13 | Terminology | **Fixed.** (a) the key screen is "Settings > LLM" everywhere (the "AI Providers" wording is gone); (b) the Compose file is `ScheduledSyncSection.kt`, `ScheduledSyncSettings` stays the keys; (c) `defaultAssetPlugins` is a function in `asset/pipeline/DefaultAssetPlugins.kt`, and the file is the root; (d) the row is "Dev console" under "Settings > Developer" in both documents |

## Nitpicks (UX-plan alignment), dispositioned

| # | Finding | Disposition |
|---|---|---|
| N1 | Console surfaces without a plan task | **Fixed.** Chips (`tables`, `sql: newest journals`, `diag`, `help`) in Task 3.1f; `EXPLAIN QUERY PLAN` chip in Task 3.2f; modal triggers line and persistent `Restore backup` action in Task 3.3d; `backups` command and "Console backups" list in Task 3.4a; failure copy unified to "Backup failed: <reason>. Nothing was changed." in the plan, `ux.md` and ADR-006 |
| N2 | Scheduled-sync details missing | **Fixed.** Story 2.3 now specifies Resume (always visible while paused, runs one check), "Stopping..." after 2 s, the Android closed-app helper line, the paused badge (Task 2.3b) |
| N3 | Cloud and plugin UX without plan support | **Fixed.** Session-limit picker 10/20/50 in Story 6.3; "Upload now anyway" **removed from the UX** (it contradicted ADR-005, Story 6.3 and UW-41); Forget-key confirm in Story 6.2; progress-row controls and the circuit-breaker row in Story 5.5; init-failure inline message in Story 5.5 |
| N4 | Assets wording | **Fixed.** Story 5.6 now calls it a dismissible card with persisted dismissal; "Show all in Assets", "Indexing files..." and index-failed states are in Task 5.6b |
| N5 | Plan stories with user-facing surfaces and no UX spec | **Fixed for Epics 7 and 8** (new `ux.md` section 10a, UW-82, acceptance lines in Stories 7.1, 7.2, 8.1, 8.2). **Story 4.2 (`AssetMoveService`) is recorded as having no interactive surface** (silent replay at open); that is a design decision, not a verified-on-device fact |
| N6 | First-tick cross-reference | **Fixed.** `ux.md` D1 attributes the immediate first tick to Story 2.0 / Task 2.0b, with Task 2.1b as the stack-level proof |

## New findings in this run (all fixed in iteration 3)

1. **`NetworkMonitor` has no metered signal.** Plan Story 6.3 and ADR-005 said uploads skip when "offline or metered", but the expect class has only `isOnline` and `observeConnectivity()`. Fixed: Task 6.3c adds `isMetered: Boolean?`; desktop copy says "Only while online"; ADR-005 addendum. The same fact made the UX consent line ("Only on an unmetered network") false on desktop.
2. **Redactor task sizing rested on a miscount.** The plan said 12 files held secret scrubbers; the grep returns 14 files, 2 of them production (`CloudEnrichmentConfig.kt`, `GitOperationSupport.kt`) and 12 tests or fixtures. Task 3.1b is split into 3.1b (core), 3.1g, 3.1h, 3.1i, each 2-5 files.
3. **Android Auto Backup is already off** (`allowBackup="false"`, no rules XML). Task 3.3c's rules are defense in depth and the plan now says so; new state (consent, run marker, egress counter, console backups) is covered (UT-143).
4. **BUG-003 looks stale.** `docs/bugs/open/BUG-003` says wasm `encodeToJpeg` returns empty bytes, but `ImageEncoder.wasmJs.kt` contains a Canvas-2D encoder from commit `cb7edec2e6` (#350, 2026-09-23). The plan does not depend on the wasm path either way (Task 6.1b, Task 8.2a notes); closing the bug is the owner's call and no file outside `project_plans/` was edited. BUG-002 (benchmark jobs skip on push) is noted at Epic 11: the coverage workflow does not use those jobs and a CI assertion (IT-92) guards against copying their draft-PR condition.
5. **UX/plan label contradictions in D1.** "Sync automatically while SteleKit is open" was false on Android (WorkManager fetches while closed) and the interim tick is fetch-only, yet the N1 sample logged `decision=full`. Fixed: label "Check for updates automatically", "checked" verbs until U16, `decision=fetch` only before U16 (UW-65, UW-69, UT-147).
6. **UW-61 and UW-62 were not crisp.** UW-61 depended on a banned-word list in another document; the 11 words are now quoted in the criterion. UW-62 ("every screen above") now enumerates twelve screens. UW-12 separates the Robolectric assertions (pass/fail) from the owner TalkBack pass (Epic 12 item 12.1).
7. **Stale count in `pre-mortem.md`** (113 tasks, 27.5M CU) corrected to 127 tasks, 30.3M CU.

## Findings in this run (triad round 3, fixed in iteration 5)

1. **Staged rollout was not implementable (engineering B1).** `defaultAssetPlugins` was wired with the real on-device plugins in U8 under the single master flag, while the per-plugin keys arrived only in U10 (Task 5.5c). Fixed: the key read, default false and inert-when-unset tests moved to Task 5.2a (U8); Task 5.5c is switches only; Risk Control, U8-U10 and passes 12.2a/12.2b restated; UT-164, IT-102.
2. **U3 catalog line named a class created in U6 (G1).** Task 3.1e now creates a minimal `ConsoleEntryPointTest`; Task 3.5b extends it (IT-101).
3. **Dry-run mechanism undefined (G2, adversarial C3).** Specified in Task 3.3b and ADR-006 addendum 4 (rolled-back execution, escalate-or-refuse rule, in-transaction re-check).
4. **Interim row's "N of M indexed" read `countUnprocessedAssets`**, which only the plugin drain decrements, so it would never move while no plugin key is set. Story 4.1 now reads the index walk's progress.
5. **Orphan flip PRs (G3)** now have Tasks 3.5c and 5.2e and units U18 and U19.
6. **Stale counts (G10)** in this file and `pre-mortem.md` replaced by the recount above.

## Carried, not resolved (named so they are not smoothed over)

- **INFERRED**: tier targets (replaced by the Story 0.1 baseline), the Android heap budget (first emulator run), the 1,000-row / 50% typed-confirm thresholds (design defaults the owner may change).
- **UNVERIFIED**: what `AssetEntry.importedAtMs` holds for files first registered by the `AssetIndexService` backfill (decides the `NewOnly` filter; Task 6.2c checks it); the `usb-serial-for-android` upstream licence text at the pinned version (the plan cites the image-meter plan's "LGPL 2.1"; Task 7.3b re-reads it).
- **Outside this plan's control**: metric 4's pull half (dev-plan PR-A1/PR-A2), the owner device passes (Epic 12), OD-1..OD-6.
- **Not edited (outside `project_plans/`)**: `docs/bugs/open/BUG-003-wasm-image-encoder-empty-bytes.md` and any Android backup-rules or manifest file; these are source/doc changes for implementation time.
