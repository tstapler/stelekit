# Validation Plan: app-owned-storage-clone

**Date**: 2026-09-12

## Happy Path Scenario

Given the Baseline (an Android or Web user has no explicit "keep this graph inside the app" option
— app storage is invisible plumbing), when the user opens the new-graph dialog, selects the pinned
"App storage" row in `UnifiedLocationPicker`, and taps Create, then the graph is created with zero
SAF-intent / File-System-Access-API prompts, and `storage_locations` persists a
`StorageLocation.AppOwned` row for it via `GraphManager.onGraphLocationDetermined` — this is Success
Metric 1, it is the first user-visible slice to ship (Phase 2, per plan.md's explicit "usable slice
before the full move/link matrix" sequencing), and every other scenario below (git-clone into app
storage, relocate, link) is a variation that either changes the destination kind or adds a
copy/verify/repoint step on top of this same picker → `onGraphLocationDetermined` spine.

## Requirement → Test Mapping

Requirement IDs are derived from requirements.md's Success Metrics, Scope, Rabbit Holes, Feasibility
Risks, Observability Requirements, and Constraints sections (requirements.md has no pre-numbered
REQ-IDs). Test file/name/type columns cite plan.md's own task-level test callouts verbatim wherever
plan.md already named one — this table does not re-derive a parallel test list. Rows marked **(gap
in plan.md)** are ones this validation pass adds because the corresponding story's task list omitted
a test task; these are new test files, not renames of existing ones.

### REQ-1: Create a plain graph on Android using only app-owned storage, zero SAF grant (Success Metric 1)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-1 | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/model/StorageLocationTest.kt` | `storageLocation_should_ExposeFourSealedSubtypes_When_Enumerated` | Unit | Happy path — domain type foundation (Task 1.1.1c) |
| REQ-1 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/StorageLocationPersistenceTest.kt` | `selectStorageLocation_should_ReturnNull_When_NoRowExists` | Unit | Error path — absence must read as "not AppOwned," never inferred (Task 1.1.3e) |
| REQ-1 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphManagerOnGraphLocationDeterminedTest.kt` | `onGraphLocationDetermined_should_WriteExactlyOnce_When_AddGraphCalledWithLocation` | Integration | DB — the single seam every creation call site uses (Task 1.1.3g) |
| REQ-1 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/AddGraphAppOwnedTest.kt` | `addGraph_should_LaunchNoSafIntent_When_AppOwnedSelectedForPlainGraph` | Integration | Android — this is the Happy Path Scenario itself (Task 2.2.1f) |
| REQ-1 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPickerTest.kt` | `unifiedLocationPicker_should_KeepConfirmDisabled_When_NoRowSelected` | Unit | Error path — picker cannot be confirmed with nothing selected (Task 2.1.1e) |

### REQ-2: Clone a git repo into app-owned storage on Android and Web, zero SAF/File-System-Access prompt (Success Metric 2)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-2 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryAppOwnedCloneTest.kt` | `shadowWorktreeFor_should_SkipSafResolutionAndUseAppOwnedBranch_When_LocationIsAppOwned` | Integration | Android happy path (Task 2.2.2d) |
| REQ-2 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryAppOwnedCloneTest.kt` | `step2RepoPath_should_PreserveExistingSafCloneBehaviorByteForByte_When_BrowseSelected` | Integration | Error/regression path — Browse must stay indistinguishable from pre-feature behavior (Task 2.2.2, 2nd AC) |
| REQ-2 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/GitSetupScreenAppOwnedCloneTest.kt` | `step2RepoPath_should_SetOpfsRepoRootWithoutShowDirectoryPickerCall_When_AppStorageSelected` | Integration | Web happy path (Task 2.3.2c) |
| REQ-2 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/AddGraphDialogPickerTest.kt` | `unifiedLocationPicker_should_ShowOnlyAppStorageRow_When_BrowserLacksFileSystemAccessApi` | Unit | Error/edge path — Firefox/Safari get no dead "Browse…" row (Task 2.3.1d) |

### REQ-3: Relocate an existing graph between storage backends via a single guided, verified flow (Success Metric 3)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-3 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorTest.kt` | `relocate_should_EmitQuiescingCopyingVerifyingSummaryInOrder_When_HappyPath` | Integration | Happy path — full state-sequence orchestration (Task 3.1.5g) |
| REQ-3 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorFailureTest.kt` | `relocate_should_ReopenAndConfirmDriverBeforeEmittingFailed_When_VerificationOrCopyFails` | Integration | Error path — source never touched, graph provably editable again before `Failed` is shown (Task 3.1.5h) |
| REQ-3 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/db/StorageLocationResolverTest.kt` | `resolveOrBackfill_should_DeriveAndPersistSafFolder_When_OnRecordTreeUriExistsAndNoRow` | Integration | Pre-existing graphs need a real source location to relocate from (Task 1.1.4d) |
| REQ-3 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphRelocationEndToEndTest.kt` | `relocate_should_PreservePageBlockAndGitObjectParity_When_RelocatingSynthetic50PageGitClonedGraph` | Integration | End-to-end — closes the Feasibility Risk "no existing regression test exercises a live storage-location migration for a graph with real content" (Tasks 5.3.1a/b/c) |

### REQ-4: One shared picker component per platform, "App storage" selectable inside the picker itself (Success Metric 4)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-4 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPickerTest.kt` | `unifiedLocationPicker_should_RenderAppStorageAsPinnedFirstRow_When_FirstOpened` | Unit | Happy path (Task 2.1.1e) |
| REQ-4 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/UnifiedLocationPickerTest.kt` | `unifiedLocationPicker_should_ShowNoPreSelectedRow_When_FirstOpened` | Unit | Error/edge path — no default selection anywhere it's embedded (Task 2.1.1e) |
| REQ-4 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/AddGraphDialogPickerTest.kt` | `unifiedLocationPicker_should_RenderIdenticallyAcrossNewGraphAndStep2RepoPath_When_SameCapabilities` | Integration | Cross-surface consistency (AC-X5) — one implementation reused, not three (Task 2.3.1d, extended) |

### REQ-5: Relocate and Link as two distinct, separately-selectable operations in any supported direction (Scope)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-5 | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/model/StorageLocationTest.kt` | `storageMoveOperation_should_MakeDeleteSourceAfterVerifyUnrepresentableOnLink_When_CompiledAsWhenExpression` | Unit | Happy path — invalid combination unrepresentable at compile time (Task 1.1.1c) |
| REQ-5 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/StorageMoveChoiceDialogTest.kt` **(gap in plan.md — no test task named for Story 3.4.1)** | `storageMoveChoiceDialog_should_HideLinkCard_When_PlainNonGitAndroidGraph` | Unit | Error/edge path — Link never a broken no-op for a plain Android graph (Story 4.2.1's AC, rendered here) |
| REQ-5 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/GraphRelocationLinkTest.kt` | `linkOperation_should_InvokeConnectHostDirectory_When_VerificationPasses` | Integration | Web Link reuses `connectHostDirectory`, not a parallel implementation (Task 4.1.1b) |
| REQ-5 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeLinkModeTest.kt` | `linkChoice_should_KeepShadowWorktreeInExistingCacheMode_When_GitClonedGraphLinked` | Integration | Android Link never repoints `storage_locations` — only Relocate does (Task 4.2.1b) |

### REQ-6: Safety net — copy-then-verify-then-optionally-delete-source, explicit confirmation, never destructive by default (Scope / Risk Control)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-6 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/BulkCopyVerifierTest.kt` | `copyAndVerify_should_ReturnVerificationFailed_When_DestinationFileByteCorrupted` | Unit | Error path — corruption detected before anything is marked verified (Task 3.1.1e) |
| REQ-6 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/RelocationStagingDirectoryTest.kt` | `startupSweep_should_LeaveDirectoryUntouched_When_NoMarkerFilePresent` | Unit | Error/edge path — ambiguous absence is never treated as staleness (Task 3.1.2c) |
| REQ-6 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/AtomicFileRelocationStepTest.kt` | `relocate_should_RollBackFirstFileRename_When_SecondFileRenameFails` | Integration | Regression — same rollback algorithm as `moveGraphFilesAndCredentials` today, byte-for-byte behavior-preserving (Task 3.1.4c) |
| REQ-6 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/db/SafGrantReleaseTest.kt` | `releaseSourceGrant_should_RemovePersistedUriPermission_When_CleanupConfirmed` | Integration | Source deletion only happens after explicit user confirmation, and only then releases the SAF grant (Task 5.2.1c) |
| REQ-6 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorTest.kt` | `relocate_should_LeaveSourceUntouchedAfterSummary_When_UserHasNotChosenDeleteOldCopy` | Integration | The Surface-9 "keep old copy is the default" guarantee (AC34) |

### REQ-7: `sweepOrphans()` must never delete an `AppOwned` graph's sole copy (Rabbit Hole / Feasibility Risk / ADR-002) — highest-severity finding in research/architecture.md §5.8

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-7 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeSweepStorageGateTest.kt` | `sweepOrphans_should_SkipDeletion_When_StorageLocationIsAppOwnedRegardlessOfMarkerAge` | Unit | Happy path — the entire point of Epic 1.2 (Task 1.2.1c) |
| REQ-7 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeSweepStorageGateTest.kt` | `sweepOrphans_should_DeleteAsBeforeFeature_When_NoStorageLocationRowExistsAndMarkerAged` | Unit | Regression path — zero behavior change for every pre-existing graph (Task 1.2.1c) |
| REQ-7 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeSweepStorageGateTest.kt` | `sweepOrphans_should_TreatLookupFailureAsDoNotDeleteAndContinueOtherDirectories_When_SelectStorageLocationThrows` | Integration | Error path — a DB error on one directory must not abort the sweep for the rest, and must fail safe (Task 1.2.1c) |

### REQ-8: Git-cloned graph relocation must quiesce in-flight JGit/write-back work and verify via real content hash, not count alone (Rabbit Hole)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-8 | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitSyncBusyCounterTest.kt` | `awaitIdle_should_SuspendUntilCounterReturnsToZero_When_SyncInFlight` | Unit | Happy path (Task 1.3.1c) |
| REQ-8 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/db/AndroidGraphMoveQuiesceStrategyTest.kt` | `quiesce_should_AwaitBusyCounterIdleAndDrainWriteBackQueue_When_GitWriteBackQueueHasInFlightEntry` | Integration | Android quiesce composes `GitWorktreeLocks` + `GitSyncBusyCounter` + `GitWriteBackQueue` drain (Task 3.1.3e) |
| REQ-8 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitObjectContentVerifierTest.kt` **(gap in plan.md — Task 3.1.1f names no test task)** | `copyAndVerify_should_ReturnVerificationFailed_When_GitPackFileByteCorrupted` | Unit | Error path — a torn-pack-file race must fail even though object/ref counts still match (`research/pitfalls.md` §3) |
| REQ-8 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/WorkManagerSyncSchedulerPauseTest.kt` | `pauseFor_should_CancelPeriodicJobBeforeCopyBegins_When_RelocateStarts` | Integration | A concurrent background fetch must never race a foreground relocate's `.git` copy (Task 3.2.1c) |
| REQ-8 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/RelocateLockNamespaceTest.kt` | `relocateLock_should_NotCrossBlockConcurrentGitPushLock_When_DifferentNamespaces` | Integration | Web equivalent — new lock namespace neither under- nor over-excludes (Task 3.3.1b) |

### REQ-9: DB is always in `filesDir`; a move to/from `AppOwned` must never imply the DB moves (Rabbit Hole)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-9 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/AtomicFileRelocationStepTest.kt` | `atomicFileRelocationStep_should_OnlyEverBeCalledWithMarkdownFileMoves_When_InvokedFromCoordinator` | Unit | Confirms the coordinator's repoint step never includes the SQLite DB/WAL/SHM triplet in a relocate's file set |
| REQ-9 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/EditGraphStorageSummaryTest.kt` **(gap in plan.md — Surface 4's read-only summary line has no named test task)** | `editGraphDialog_should_DescribeOnlyMarkdownLocation_When_RenderingStorageSummaryLine` | Unit | UI-level version of the same guarantee — the "Storage: …" line never claims the DB moved (ux.md Surface 4) |

### REQ-10: Observability — log start/verify/completion/failure of every relocate/link operation (Observability Requirements)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-10 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorLoggingTest.kt` | `graphRelocationCoordinator_should_LogAllFourLifecyclePoints_When_HappyPathCompletes` | Integration | Happy path (Task 5.1.1b) |
| REQ-10 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/GraphRelocationCoordinatorLoggingTest.kt` | `graphRelocationCoordinator_should_LogMoveFailedWithStorageErrorSubtype_When_VerificationFails` | Integration | Error path — `grep`-able by failure class, and paired with a UI-visible `Failed` state per AC-X6 (Task 5.1.1b, extended) |

### REQ-11: Moves must not block the UI thread; large graphs (8,000+ pages) must not be fully loaded into memory (Non-functional Requirements)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-11 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/BulkCopyVerifierTest.kt` | `copyAndVerify_should_ProcessFilesInBoundedBatches_When_SourceHas8030Files` | Unit | Happy path — mirrors `INDEX_BATCH_SIZE = 100` from `GraphLoader.indexRemainingPages` (Task 3.1.1e) |
| REQ-11 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphRelocationEndToEndTest.kt` | `relocate_should_ReadDestinationInBoundedBatches_When_VerifyingSynthetic50PageGraph` | Integration | Consistent with `LargeGraphWarmStartCrashTest`'s ≤100-row-batch assertion style (Task 5.3.1) |

### REQ-12: Must not regress `GitShadowWorktree`/`FolderSyncSettings` behavior for users who don't opt in (Constraints)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-12 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeSweepStorageGateTest.kt` | `sweepOrphans_should_DeleteAsBeforeFeature_When_NoStorageLocationRowExistsAndMarkerAged` | Unit | Same test as REQ-7's regression row — one behavior, two requirements it satisfies |
| REQ-12 | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryAppOwnedCloneTest.kt` | `step2RepoPath_should_PreserveExistingSafCloneBehaviorByteForByte_When_BrowseSelected` | Integration | Same test as REQ-2's regression row |
| REQ-12 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/GraphRelocationLinkTest.kt` | `linkOperation_should_ReuseRunHostReconciliationUnchanged_When_Invoked` | Integration | `FolderSyncSettings`'s existing livesync path is extended, not replaced (Task 4.1.1b) |

## Storage-locations migration test

**Naming choice**: `migration_should_be_idempotent_and_forward_compatible`, **not**
`migration_should_be_reversible`. Verified against `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt`:
`MigrationRunner.all` is a flat `List<Migration>` applied forward-only via `applyAll()` — there is no
`down()`/rollback function anywhere in the file (the `DROP TABLE` statements present at lines
591-787 are *forward* migration steps in a create-copy-drop-rename column-change pattern, e.g. the
`pages`/`blocks`/`wikilink_references` table rebuilds — not a reverse-migration mechanism). This
matches plan.md's own Migration Plan section verbatim: "forward-only within this project (no
down-migration tooling exists elsewhere in this codebase to model one on); a rollback is 'stop
writing to the table,' not 'drop it.'" A test named `_should_be_reversible` would assert a capability
this codebase deliberately does not have.

**File**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/StorageLocationsMigrationTest.kt` (new)

| Test Name | Scenario |
|---|---|
| `migration_should_be_idempotent_and_forward_compatible_When_AppliedToFreshDatabase` | Run `SteleDatabase.Schema.create(driver)` then `MigrationRunner.applyAll(driver)`; assert `storage_locations` exists with columns `graph_id, kind, tree_uri, real_path, display_name, updated_at_epoch_ms` and `graph_id` as primary key (schema-state verification). |
| `migration_should_be_idempotent_and_forward_compatible_When_AppliedTwiceInSequence` | Run `applyAll(driver)` twice against the same driver (simulating a second app launch on an already-migrated DB); assert no exception is thrown and `storage_locations` still exists exactly once — proves the `CREATE TABLE IF NOT EXISTS` is safe to re-run, which is this repo's only substitute for a down-migration. |
| `migration_should_be_idempotent_and_forward_compatible_When_PreExistingTableDataSurvives` | Seed a row into an existing table (e.g. `pages`) before running `applyAll(driver)` a second time; assert that row is unchanged afterward — the `storage_locations` migration is purely additive and never touches other tables' data, satisfying the Migration Plan's "no data is destroyed by a rollback since `storage_locations` only stores location metadata" claim. |
| `migration_should_be_idempotent_and_forward_compatible_When_NoRowExistsForGraph` | After migration, `selectStorageLocation(graphId)` for a graph with no row returns `null` — re-confirms at the schema-integration level (not just the unit level covered by `StorageLocationPersistenceTest.kt`, Task 1.1.3e) that a pre-migration graph's absence is never inferred as `AppOwned`, which is the one behavior `sweepOrphans()`'s safety gate (REQ-7) depends on. |

This test complements, rather than duplicates, `MigrationRunnerSchemaSyncTest` (existing, unchanged
per Task 1.1.3c's note — it auto-discovers new `IF NOT EXISTS` table names and asserts each appears
in `MigrationRunner.all`) and `StorageLocationPersistenceTest.kt` (Task 1.1.3e, which round-trips a
single upsert/read but does not exercise `MigrationRunner.applyAll()` against a pre-existing schema).

## UX Acceptance Tests

Surface numbers and AC numbers match `design/ux.md` exactly. Test files already named in ux.md/plan.md
are cited verbatim; rows marked **(gap)** add a test plan.md's UX surface described but never assigned
a task-level test file for.

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| AC1: select App storage + confirm in 2 taps, any of 3 call sites | `UnifiedLocationPickerTest.kt` (jvmTest, gap — add case) | `unifiedLocationPicker_should_ConfirmInTwoTaps_When_AppStorageSelected` | Compose UI test (jvmTest) | Open picker from each of the 3 embedding contexts; tap "App storage" row, tap "Next"; assert picker closes with `AppOwned` result after exactly 2 interactions. |
| AC2: no row selected on first render | `UnifiedLocationPickerTest.kt` (Task 2.1.1e) | `unifiedLocationPicker_should_RenderNoRowSelected_When_FirstOpened` | Compose UI test (jvmTest) | Render picker; assert semantics tree shows neither row with a selected/checked state. |
| AC3: Next/Confirm disabled until exactly one row selected | `UnifiedLocationPickerTest.kt` | `unifiedLocationPicker_should_KeepConfirmDisabled_When_NoRowSelected` | Compose UI test (jvmTest) | Render picker with no interaction; assert Next button's `enabled` semantics is false. |
| AC4: every row keyboard-navigable, Enter/Space activates | `UnifiedLocationPickerTest.kt` (gap — add case) | `unifiedLocationPicker_should_ActivateRow_When_EnterPressedAfterTabFocus` | Compose UI test (jvmTest) | Tab through rows in visual order; press Enter on focused row; assert selection state changes. |
| AC5: screen reader announces label+subtitle as one unit | `UnifiedLocationPickerTest.kt` (gap — add case) | `unifiedLocationPicker_should_MergeLabelAndSubtitleIntoOneSemanticsNode_When_Rendered` | Compose UI test (jvmTest, semantics tree assertion) | Assert each row's `contentDescription`/merged semantics text includes both label and subtitle strings, never a bare icon name. |
| AC6: WCAG AA contrast (≥4.5:1), both themes | Manual | Row/subtitle contrast check | Manual | Render picker in light and dark theme; measure text/background contrast with a contrast-checker tool; confirm ≥4.5:1 for both. |
| AC7: no dead end — Cancel/system-back always returns caller unchanged | `UnifiedLocationPickerTest.kt` (gap — add case) | `unifiedLocationPicker_should_ReturnCallerUnchanged_When_CancelledBeforeNextTapped` | Compose UI test (jvmTest) | Select a row, then tap Cancel/press system-back; assert caller's prior dialog/wizard state is unmodified. |
| AC8: create plain app-storage graph in ≤4 steps on Chromium/Android | `AddGraphAppOwnedTest.kt` (Task 2.2.1f, extended) | `addGraphDialog_should_CompleteInFourSteps_When_AppStorageChosenForPlainGraph` | Integration (androidUnitTest) | name → App storage → Create → acknowledge warning; assert no SAF/File-System-Access prompt appears and count taps == 4. |
| AC9: warning copy matches ADR-003 verbatim per platform | `AddGraphAppOwnedTest.kt` (Task 2.2.1f) | `warningDialog_should_ShowExactAndroidCopy_When_PlainGraphAppOwnedSelected` | Integration (androidUnitTest) — string-equality assertion | Trigger the warning; assert body text equals ADR-003's Android copy exactly. |
| AC10: warning not dismissable by outside-tap/Escape without explicit choice | `PlainGraphAppOwnedWarningDialogTest.kt` (jvmTest, new — **gap**, no task named one) | `warningDialog_should_RequireExplicitChoice_When_OutsideTapOrEscapeAttempted` | Compose UI test (jvmTest) | Attempt `onDismissRequest` via outside-tap and Escape; assert dialog remains open and no graph is created. |
| AC11: no dead end — Go back returns to editable New-graph dialog | `PlainGraphAppOwnedWarningDialogTest.kt` (new — **gap**) | `warningDialog_should_ReturnToEditableNewGraphDialog_When_GoBackTapped` | Compose UI test (jvmTest) | Tap "Go back"; assert New-graph dialog re-renders with name and picker selection intact, no graph created. |
| AC12: clone into app storage in ≤3 steps from Step 2 | `GitSetupScreenAppOwnedCloneTest.kt` (Task 2.3.2c, extended) | `step2RepoPath_should_CompleteInThreeSteps_When_AppStorageChosenForClone` | Integration (wasmJsTest) | App storage row → Next → confirm clone; assert zero SAF/File-System-Access prompts and 3 interactions. |
| AC13: Browse… produces UI/clone behavior indistinguishable from pre-feature wizard | `AndroidGitRepositoryAppOwnedCloneTest.kt` / wasmJs equivalent (Tasks 2.2.2d, 2.3.2c) | `step2RepoPath_should_ExerciseNoDivergentCodePath_When_BrowseSelected` | Integration | Select Browse…, pick a SAF/real folder; assert the exact pre-feature `GitShadowWorktree` cache-mode write-back path is exercised, no new branch. |
| AC14: wiki-subdirectory browse continues to work against resolved root, no a11y regression | `AndroidGitRepositoryAppOwnedCloneTest.kt` (gap — add case) | `wikiSubdirBrowser_should_OperateAgainstResolvedRoot_When_AppOwnedOrSafFolderChosen` | Integration | Resolve each root kind, then invoke wiki-subdir browse; assert it lists correctly and icon `contentDescription`s are unchanged. |
| AC15: current storage location legible at a glance before committing | `EditGraphStorageSummaryTest.kt` (new — **gap**) | `editGraphDialog_should_ShowOneLineStorageSummary_When_Opened` | Compose UI test (jvmTest) | Open Edit Graph for a graph of each `StorageLocation` kind; assert a single readable line ("App storage" / folder display name) renders before any button is tapped. |
| AC16: picker never offers current location as a destination | `EditGraphStorageSummaryTest.kt` (new — **gap**) | `moveStoragePicker_should_ExcludeCurrentLocation_When_Opened` | Compose UI test (jvmTest) | Open the move-storage picker for a graph on `SafFolder`; assert no row resolves back to that same `SafFolder`. |
| AC17: no dead end — cancelling any guided-flow step returns to editable Edit Graph dialog | `EditGraphStorageSummaryTest.kt` (new — **gap**) | `editGraphDialog_should_RemainEditable_When_GuidedMoveFlowCancelledAtAnyStep` | Compose UI test (jvmTest) | Cancel from picker, choice dialog, and confirmation dialog in turn; assert Edit Graph dialog is unaffected each time. |
| AC18: Relocate/Link cards render with equal visual weight | `StorageMoveChoiceDialogTest.kt` (jvmTest, new — **gap**, Story 3.4.1 named no test task) | `storageMoveChoiceDialog_should_RenderBothCardsWithEqualVisualWeight_When_Rendered` | Screenshot/jvmTest diff | Render dialog; assert neither card uses a filled/primary `Button` style over the other. |
| AC19: each card's consequence text present, non-truncated at 400px | `StorageMoveChoiceDialogTest.kt` (new — **gap**) | `storageMoveChoiceDialog_should_ShowFullConsequenceText_When_RenderedAt400pxWidth` | Compose UI test (jvmTest) | Render at 400px width; assert no ellipsis/truncation on either card's consequence line. |
| AC20: screen reader announces each card as one unit | `StorageMoveChoiceDialogTest.kt` (new — **gap**) | `storageMoveChoiceDialog_should_MergeLabelAndConsequenceIntoOneSemanticsNode_When_Rendered` | Compose UI test (jvmTest, semantics) | Assert merged semantics text per card includes label + consequence text. |
| AC21: no dead end — Cancel/Escape returns to Surface 4, no move started | `StorageMoveChoiceDialogTest.kt` (new — **gap**) | `storageMoveChoiceDialog_should_StartNoOperation_When_CancelledOrEscaped` | Compose UI test (jvmTest) | Dismiss dialog both ways; assert no `StorageMoveOperation` was constructed/passed downstream. |
| AC22: dialog names exact source/destination in human-readable form | `StorageMoveConfirmDialogTest.kt` (Task 3.4.2b) | `confirmDialog_should_NameExactSourceAndDestination_When_Rendered` | Compose UI test (jvmTest) — string-content assertion | Render for `SafFolder → AppOwned`; assert body text contains graph name + human-readable source/destination, never a raw `content://` URI or OPFS path. |
| AC23: default focus on Cancel | `StorageMoveConfirmDialogTest.kt` (Task 3.4.2b) | `confirmDialog_should_DefaultFocusToCancel_When_Rendered` | Compose UI test (jvmTest) — focus-state assertion | Render dialog; assert `Cancel` button has initial focus, not `Move`. |
| AC24: Escape dismisses without starting a move | `StorageMoveConfirmDialogTest.kt` (Task 3.4.2b) | `confirmDialog_should_DismissWithoutStartingMove_When_EscapePressed` | Compose UI test (jvmTest) | Press Escape; assert `onDismissRequest` fires and no coordinator/move is started. |
| AC25: reassurance sentence present verbatim, doubles as accessible description | `StorageMoveConfirmDialogTest.kt` (Task 3.4.2b, extended) | `confirmDialog_should_IncludeVerbatimReassuranceSentence_When_Rendered` | Compose UI test (jvmTest) — string assertion + semantics description check | Assert the exact sentence "Your files stay where they are until the copy is verified." is present as both visible text and the dialog's accessible description. |
| AC26: no dead end — Cancel/Escape returns to Surface 5/4 unchanged | `StorageMoveConfirmDialogTest.kt` (Task 3.4.2b, extended) | `confirmDialog_should_ReturnToPriorSurfaceUnchanged_When_CancelledOrEscaped` | Compose UI test (jvmTest) | Dismiss both ways; assert prior surface state (choice dialog or Edit Graph) is unaffected. |
| AC27: Copying always shows determinate "N of M" | `StorageMoveProgressDialogTest.kt` (Task 3.4.3b) | `progressDialog_should_ShowDeterminateCount_When_CopyingStateEmitted` | Compose UI test (jvmTest) | Emit `Copying(4200, 8030)`; assert rendered text/progress reflects "4200 of 8030," not an indeterminate spinner. |
| AC28: Quiescing/Verifying may spin but must show descriptive text | `StorageMoveProgressDialogTest.kt` (Task 3.4.3b, extended) | `progressDialog_should_ShowDescriptiveLabel_When_QuiescingOrVerifyingStateEmitted` | Compose UI test (jvmTest) | Emit each state; assert a non-blank label naming the current step accompanies any spinner. |
| AC29: Cancel during Quiescing/Copying always returns to pre-move state, no data loss | `GraphRelocationCoordinatorTest.kt` (cited directly in ux.md Surface 7) | `relocate_should_ReturnToPreMoveStateWithNoDataLoss_When_CancelledDuringQuiescingOrCopying` | Integration (businessTest) | Cancel mid-`Quiescing`/`Copying`; assert source untouched and staging directory discarded. |
| AC30: dialog not dismissable by outside-tap/system-back mid-move | `StorageMoveProgressDialogTest.kt` (Task 3.4.3b, extended) | `progressDialog_should_IgnoreOutsideTapAndSystemBack_When_MoveInProgress` | Compose UI test (jvmTest) | Attempt outside-tap and back-press during `Copying`; assert dialog remains open, only the explicit Cancel button dismisses. |
| AC31: only Retry/Cancel present on Failed | `StorageMoveProgressDialogTest.kt` (Task 3.4.3b) | `progressDialog_should_ShowOnlyRetryAndCancel_When_FailedStateEmitted` | Compose UI test (jvmTest) — enumerate buttons | Emit `Failed`; assert exactly two interactive buttons exist, no "delete anyway"/"use it anyway." |
| AC32: reassurance line present verbatim on every Failed render | `StorageMoveProgressDialogTest.kt` (Task 3.4.3b, extended) | `progressDialog_should_ShowUntouchedReassurance_When_AnyStorageErrorSubtypeFailed` | Compose UI test (jvmTest) | Emit `Failed` with each `DomainError.StorageError` subtype; assert "Your original files were not touched or deleted." renders every time. |
| AC33: no dead end — both Retry/Cancel lead to a recoverable state | `StorageMoveProgressDialogTest.kt` (Task 3.4.3b, extended) | `progressDialog_should_LeadToRecoverableState_When_RetryOrCancelTapped` | Compose UI test (jvmTest) | Tap Retry → assert coordinator restarts from the top; tap Cancel → assert return to Surface 4 with nothing changed. |
| AC34: no time-boxed/silent auto-deletion of source under any circumstance | `GraphRelocationCoordinatorTest.kt` (cited directly in ux.md Surface 9) | `relocate_should_LeaveSourceUntouchedAfterSummary_When_UserHasNotChosenDeleteOldCopy` | Integration (businessTest) | Reach `Summary`; wait/advance virtual time; assert source location's files are unchanged absent an explicit "Delete old copy" call. |
| AC35: both cleanup choices equally reachable with one tap | `EditGraphStorageSummaryTest.kt` (new — **gap**) | `postMoveSummary_should_OfferBothCleanupChoicesAtEqualDepth_When_Rendered` | Compose UI test (jvmTest) | Render `Summary`; assert "Keep old copy" and "Delete old copy" are both one tap away, no nested confirm-of-confirm on Keep. |
| AC36: failed cleanup-delete never implies rollback of the completed move | `EditGraphStorageSummaryTest.kt` (new — **gap**) | `postMoveSummary_should_ReportMoveAsSuccessful_When_CleanupDeleteFails` | Integration | Simulate source-deletion failure after a successful relocate; assert the summary still reports the move itself as successful. |
| AC37: no dead end — dismissing without a choice equals "Keep old copy" | `EditGraphStorageSummaryTest.kt` (new — **gap**) | `postMoveSummary_should_DefaultToKeepOldCopy_When_DismissedWithoutChoice` | Compose UI test (jvmTest) | Dismiss `Summary` without tapping either button; assert source remains intact (equivalent to Keep). |
| AC38: Link reuses `FolderSyncStatusBadge` unchanged, no divergent rendering path | `FolderSyncStatusBadgeLinkReuseTest.kt` (Task 4.1.3a) | `folderSyncStatusBadge_should_RenderIdenticalCopy_When_LinkEstablishedViaCoordinatorVsFolderSyncSettings` | Integration (wasmJsTest) | Establish a Link via each entry point; assert badge state/copy is byte-identical. |
| AC39: reconnect/grant-access tap target keyboard-reachable and announced, no regression | `FolderSyncStatusBadgeLinkReuseTest.kt` (Task 4.1.3a, extended) | `folderSyncStatusBadge_should_RemainKeyboardReachableAndAnnounced_When_LinkEstablishedViaNewFlow` | Integration (wasmJsTest) | Tab to badge established via the new flow; assert focus reachability and announcement text are unchanged from today. |
| AC40: no dead end — every clickable badge state leads to a native re-prompt | `FolderSyncStatusBadgeLinkReuseTest.kt` (Task 4.1.3a, extended) | `folderSyncStatusBadge_should_TriggerNativeRepromptFromEveryClickableState_When_Clicked` | Integration (wasmJsTest) | Click badge in `Disconnected`/`Denied` states; assert the platform's native permission flow launches, never a stuck state. |
| AC41: warning copy matches ADR-003 verbatim per platform | `AddGraphAppOwnedTest.kt` (Android, Task 2.2.1f) / `PlainGraphAppOwnedWarningDialogTest.kt` (Web, new — **gap**) | `warningDialog_should_ShowExactCopy_When_RenderedPerPlatform` | Integration / Compose UI test | String-equality assertion against ADR-003's Android and Web copy respectively. |
| AC42: "Export as .zip" only on Android, never Web | `PlainGraphAppOwnedWarningDialogTest.kt` (new — **gap**) | `warningDialog_should_ShowExportButtonOnlyOnAndroid_When_Rendered` | Compose UI test (jvmTest, platform-conditional) | Render on both platform configurations; assert export button presence differs exactly as specified. |
| AC43: warning never silently bypassed, no "don't show again" | `PlainGraphAppOwnedWarningDialogTest.kt` (new — **gap**) | `warningDialog_should_HaveNoSuppressionCheckbox_When_Rendered` | Compose UI test (jvmTest) | Assert no checkbox/toggle exists that could suppress the warning on a future plain-graph creation. |
| AC44: no dead end — Go back always returns to editable New-graph dialog | Same as AC11 | `warningDialog_should_ReturnToEditableNewGraphDialog_When_GoBackTapped` | Compose UI test (jvmTest) | (See AC11 — same test, same guarantee restated for Surface 11.) |
| AC-X1: task efficiency (≤4/≤3/≤4 taps) | Composite of AC8, AC12, AC15-17 tests above | (see individual rows) | Integration | Audit checklist — no new test, aggregates the per-surface tap-count assertions already listed. |
| AC-X2: no dead ends across all documented error/edge states | Composite of AC7, AC11, AC17, AC21, AC26, AC33, AC37, AC40, AC44 | (see individual rows) | Manual audit | Reviewer walks the enumerated table in ux.md's "Cross-cutting" section and confirms each per-surface test above passing implies this checklist item is satisfied. |
| AC-X3: never destructive by default — only "Delete old copy" ever deletes source | `GraphRelocationCoordinatorTest.kt` + `EditGraphStorageSummaryTest.kt` (gap) | `entireMoveFlow_should_HaveExactlyOneDestructiveAction_When_AuditedEndToEnd` | Integration (businessTest, new assertion) | Exercise every surface's buttons/timers across a full relocate+cleanup run; assert source files change only immediately following an explicit "Delete old copy" tap. |
| AC-X4: accessibility baseline (Tab-reachable, Enter/Space, real accessible name, ≥4.5:1 contrast) | Composite of AC4-6, AC20, AC39 | (see individual rows) | Compose UI test (jvmTest) + Manual (contrast) | Aggregates per-surface a11y assertions already listed; contrast remains a manual check (no in-repo contrast-checker tool). |
| AC-X5: consistency — one picker impl, one move-wizard impl per platform, identical everywhere | `UnifiedLocationPickerTest.kt` + `StorageMoveChoiceDialogTest.kt`/`StorageMoveConfirmDialogTest.kt` (gap for cross-entry-point identity assertion) | `storageMoveWizard_should_RenderIdenticallyRegardlessOfEntryPoint_When_LaunchedFromAndroidEditGraphOrWebFolderSyncSettings` | Compose UI test (jvmTest) | Launch the choice/confirm dialogs from both entry points; assert identical composable tree/copy. |
| AC-X6: no silent failure — every terminal failure paired with a log entry | `GraphRelocationCoordinatorLoggingTest.kt` (Task 5.1.1b) | `graphRelocationCoordinator_should_LogMoveFailedWithStorageErrorSubtype_When_VerificationFails` | Integration (businessTest) | Same test as REQ-10's error-path row — the UI-visibility half of the guarantee is covered by AC31/AC32's `Failed`-rendering tests. |

## Test Stack

- **Unit**: `kotlin.test` `@Test`-annotated functions in `commonTest`/`businessTest`, `kotest-assertions-core` for
  assertions, `kotest-property` (`Arb`/`checkAll` inside `runTest { }`) for the sealed-interface
  exhaustiveness and byte-exactness contract tests where a structured input space exists (e.g.
  `ContentHasherRawBytesTest`'s whitespace-variant byte arrays). No Kotest Spec runner — matches
  project CLAUDE.md's stated convention.
- **Integration**: `businessTest` (business logic, no UI — `GraphRelocationCoordinator`, `BulkCopyVerifier`,
  `StorageLocationResolver`, migration tests) plus `androidUnitTest` (Robolectric, for
  `sweepOrphans()`/`GitShadowWorktree`/`WorkManager` interactions) and `wasmJsTest` (real headless
  Chrome via Karma, for `HostDirectorySync`/Web Lock/OPFS interactions). Test doubles:
  `FakeGraphMoveQuiesceStrategy` (Task 3.1.3d) is the load-bearing fake enabling the coordinator's
  timeout/cancellation paths to be tested on the JVM target without real platform code.
- **E2E / UX**: Compose UI tests in `jvmTest` (semantics-tree assertions, screenshot/Roborazzi diffs
  for visual-weight checks) for every interactive surface; a short manual checklist for contrast and
  actual screen-reader verification, since this repo has no in-house contrast-checker or
  screen-reader-automation tool.

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (`commonTest`/`businessTest`/`jvmTest`) | `bazel test //kmp:business_tests` and `bazel test //kmp:jvm_tests` (or `./gradlew jacocoTestReport` → `kmp/build/reports/jacoco/`) | ≥80% line coverage on every new file listed above; 100% branch coverage on `GraphRelocationCoordinator`'s closed-driver region (steps 3-8) and `sweepOrphans()`'s new guard, given both are named as the highest-risk code in this project (Feasibility Risks, ADR-002) |
| Android (`androidUnitTest`) | `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` | All Android-only branches (`AndroidGraphMoveQuiesceStrategy`, `AndroidGitRepository.shadowWorktreeFor()`, `StorageLocationResolver.android`, `GitShadowWorktree`'s sweep gate) covered by both a happy-path and a fail-safe/error-path test |
| Web (`wasmJsTest`) | `./gradlew :kmp:wasmJsBrowserTest` | All Web-only branches (`HostDirectorySync` link/unlink/pause, `WasmJsGraphMoveQuiesceStrategy`, Web Lock namespace) covered; Firefox/Safari no-Browse-row path explicitly exercised, not just Chromium |
| UI (`jvmTest` Compose tests) | `bazel test //kmp:jvm_tests` (`xvfb-run --auto-servernum` if no display, per project CLAUDE.md) | Every UX acceptance criterion (AC1-AC44, AC-X1-X6) above has a passing automated test or an explicit Manual row in this document — none silently unaddressed |

- All public service methods introduced by this project (`GraphRelocationCoordinator`,
  `BulkCopyVerifier`, `StorageLocationResolver`, `AtomicFileRelocationStep`, `HostDirectorySync`'s new
  methods): happy path + every named `DomainError.StorageError`/edge-case path covered.
- All external integrations (SAF `ContentResolver`, `WorkManager`, File System Access API, Web Locks):
  unit/fake-strategy mocked in `businessTest`, plus at least one `androidUnitTest`/`wasmJsTest`
  integration test per platform.
- UX acceptance criteria: every criterion in `design/ux.md` has a corresponding automated test row
  above, or an explicit Manual row where no in-repo tool exists (contrast, live screen-reader
  verification) — none silently unaddressed.
- Gaps this validation pass added beyond plan.md's own task list (marked **(gap in plan.md)** above):
  `StorageMoveChoiceDialogTest.kt` (Story 3.4.1), `PlainGraphAppOwnedWarningDialogTest.kt` (Stories
  2.2.1/2.3.3's shared dialog shell), `EditGraphStorageSummaryTest.kt` (Surface 4's read-only summary
  line and Surface 9's cleanup-choice UI), `GitObjectContentVerifierTest.kt` (Task 3.1.1f), and the
  Web-side "Move storage location" wiring tests for Stories 3.2.2/3.3.3. None of these change any
  acceptance criterion already stated in plan.md/ux.md — they only give an AC that plan.md described
  but left untested a concrete test file to land in.
