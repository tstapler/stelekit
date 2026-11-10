# Validation Plan: background-task-scheduling

**Date**: 2026-11-10

Inputs: `../requirements.md`, `plan.md` (rev3 plus the Phase 4 repair and triad repair 1: 116 tasks, 27 stories, 9 epics), `../design/ux.md`, `pre-mortem.md`. The "Phase 4 repair additions" table below maps every AC added by the repair to a test; REQ coverage stays 16/16 and UX coverage is 39/39 (UX-30..UX-39 added by triad repair 1). `requirements.md` has no REQ-n numbering, so REQ ids below are assigned here from its Success Metrics, In Scope list, Constraints and NFRs. Test class and file names come from `plan.md`; every class named here is registered per Test Infrastructure Rule 2 (`AllBusinessTests` / `AllJvmTests` / `AllAndroidUnitTests`), and Android evidence is `./gradlew :kmp:testDebugUnitTest --tests '<fqcn>'` (Rule 1).

## Requirement IDs

| ID | Requirement (source in requirements.md) |
|----|---|
| REQ-1 | Per graph, per task kind: enabled, interval, jitter, network policy (and charging / battery-not-low) are persisted, and the values reach the platform scheduler, verified by read-back (Success Metric 1, In Scope: settings model) |
| REQ-2 | With "Unmetered" set, no scheduled run starts a network call on a metered or unknown connection; skips make no network or git call (Success Metric 2, Performance SLO) |
| REQ-3 | Jitter offsets stay in `[0, jitter]`, never negative, never breach platform min/max interval (Success Metric 3) |
| REQ-4 | Neutral `commonMain` port with zero git types, run end to end by two consumers: GitSync and model download (Success Metric 4, Constraints: ports-and-adapters) |
| REQ-5 | Observability: persisted per-run record readable in Logs, and a "Late" state with no false Late from foreground syncs (Success Metric 5, Observability Requirements) |
| REQ-6 | Existing behaviour preserved: `ScheduledSyncPolicy`, first-sync confirmation, edit lock, vault-locked and conflict skips, clone/sync exclusion, retry ownership (Success Metric 6) |
| REQ-7 | Android WorkManager adapter: stable unique names, tags, backoff, pause/resume around graph moves, orphan reconcile that never cancels live work, removal hook, startup self-heal (In Scope: Android adapter) |
| REQ-8 | Desktop in-process adapter behind the same port and settings; iOS/Web `Noop` adapter; every host names its scheduler (In Scope: Desktop, iOS) |
| REQ-9 | Migration from the global `scheduled_sync_enabled` flag to per-graph entries without silently flipping sync on or off; legacy keys retained for rollback (Risk Control, Rabbit Hole 5) |
| REQ-10 | Settings UI: interval, jitter, network policy, enabled; disabled-with-reason; effective schedule shown (In Scope: Settings UI) |
| REQ-11 | Restricted-background (Doze / battery-saver / standby) hint using `BackgroundRestrictionProbe`, with actionable fallback (In Scope: Observability) |
| REQ-12 | Fail-closed: a settings failure or decode error never starts background network activity; per-(graph, kind) switch defaults off (Constraints, Risk Control) |
| REQ-13 | NFR: bounded unique work names and bounded reads (tens of graphs x few kinds), no standing unbounded collectors, no DB fan-out at startup (Scalability, Constraints: graph-scale reads) |
| REQ-14 | Wiring and test-hygiene gates stay green (`WiringCompletenessTest`, `wiring-catalog.txt`, test registration, import-direction scan) (Constraints) |
| REQ-15 | LFS transfer shares the same `NetworkPolicy` semantics (Success Metric 4 second consumer scope, Rabbit Hole 2 "unmetered" wording) |
| REQ-16 | Backgrounded-app reliability on real devices (Doze, standby, OEM managers) is observable and honest (Feasibility Risks, Wall-clock blocker 1) |

## Happy Path Scenario
Given Android with the pre-change baseline (one global `scheduled_sync_enabled` switch, a 15-minute any-network periodic job per graph, no jitter), when the user opens Settings for graph "Work notes", turns on sync, picks "1 hour", "Unmetered networks only" and "A little" spread, then `WorkManagerTaskScheduler.apply` enqueues `stelekit_git_sync_<graphId>` and reading the `WorkInfo` back shows interval 60 min, flex 6 min and `NetworkType.UNMETERED`; when the worker later fires on a metered connection the run log records `Skipped:metered` with no git call, and once the last success is older than twice the interval the status line reads "Hasn't run in N h".

## Requirement → Test Mapping

Type column: Unit = pure/fake-backed; Integration = Robolectric WorkManager, real `Settings`, real `GraphManager`, wired-app fixtures. Migration test is listed separately below.

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| REQ-1: per-graph/kind settings reach scheduler | `TaskConfigCodecTest` | `codec_should_roundTripEntry_When_EncodedThenDecoded` | Unit | Happy path |
| REQ-1 | `TaskConfigCodecTest` | `codec_should_returnDisabledDefault_When_StoredStringIsGarbage` | Unit | Error path |
| REQ-1 | `TaskSettingsStoreMigrationTest` | `read_should_resolveDefaultsAndReportUsingDefault_When_EntryHasNoOverrides` | Unit | Happy path (default row is a per-kind record, never a scope) |
| REQ-1 | `TaskSettingsStoreMigrationTest` | `writeDefaults_should_notCollideWithAppWideScope_When_ScopeIsEmptyString` | Unit | Error path (key-family collision) |
| REQ-1 | `TaskKeyTest` | `settingsKey_should_beInjective_When_KindAndScopeContainSeparators` | Unit | Error path (key aliasing) |
| REQ-1 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_enqueuePeriodicWorkWithReadBackIntervalFlexAndUnmetered_When_ConfigApplied` | Integration | Real WorkManager read-back, not "no error" |
| REQ-1 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_mapChargingAndBatteryNotLowConstraints_When_Requested` | Integration | Constraint mapping |
| REQ-1 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_keepWorkIdAndName_When_IntervalChanges` | Integration | `UPDATE` in place |
| REQ-1 | `WorkManagerJitterSpikeTest` | `update_should_recordIntervalFlexAndWorkerClassBehaviour_When_ReappliedOnRunningWork` | Integration | Spike 0.1.1 regression (flex/initialDelay/UPDATE/worker class read-back) |
| REQ-2: unmetered gate, no network call | `NetworkGateTest` | `evaluate_should_proceedOnlyWhenOnlineAndNotMetered_When_PolicyUnmetered` | Unit | Happy path (12-row truth table) |
| REQ-2 | `NetworkGateTest` | `evaluate_should_skipUnknownNetwork_When_UnmeteredAndMeteredIsNull` | Unit | Error path (fail closed) |
| REQ-2 | `NetworkGateTest` | `evaluateMetered_should_equalEvaluateWithOnlineTrue_When_CalledByLfs` | Unit | Helper equivalence |
| REQ-2 | `NetworkGateTest` | `skip_should_mapToSkippedNeverFailed_When_NetworkSkipReasonMapped` | Unit | Skip is not failure |
| REQ-2 | `TaskWorkerTest` | `doWork_should_notCallBodyAndLogSkippedMetered_When_UnmeteredAndMetered` | Integration | Periodic skip -> `Result.success()` |
| REQ-2 | `TaskWorkerTest` | `doWork_should_skipUnknownNetwork_When_ProviderThrowsOrMeteredNull` | Integration | Cold-process signal fails closed |
| REQ-2 | `OneShotSkipTest` | `doWork_should_retryThenFailTerminallyWithKeyError_When_OneShotSkippedAtAttemptCap` | Integration | One-shot skip never reports success; shared 5-attempt budget |
| REQ-2 | `InProcessTaskSchedulerContractTest` | `tick_should_neverInvokeTask_When_UnmeteredAndMeteredUnknown` | Integration | Desktop defence in depth |
| REQ-3: jitter bounds | `JitterPolicyPropertyTest` | `offsetMinutes_should_stayWithinZeroAndJitterAndBeDeterministic_When_AnySeed` | Unit | Happy path (property) |
| REQ-3 | `JitterPolicyPropertyTest` | `clamp_should_neverGoNegativeOrBelowMinInterval_When_InputsNegativeOrOversized` | Unit | Error path (negative, jitter >= interval, interval < 15) |
| REQ-3 | `JitterPolicyPropertyTest` | `clamp_should_limitJitterToIntervalMinusMinFlexAndReportClamped_When_JitterTooLarge` | Unit | Platform bounds |
| REQ-3 | `JitterPolicyPropertyTest` | `offsets_should_spreadAcrossAllBuckets_When_200KeysAndJitter30` | Unit | Distribution sanity |
| REQ-3 | `PeriodicSyncDriverJitterTest` | `intervalMs_should_stayInIntervalPlusJitterAndEqualTodayAtZero_When_SeededRandom` | Unit | In-app loop seam |
| REQ-3 | `InProcessTaskSchedulerContractTest` | `ticks_should_gapWithinIntervalAndJitter_When_ThreeIntervalsOnVirtualTime` | Integration | Gaps of 15 to 20 min |
| REQ-3 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_setFlexFromJitterAndInitialDelayFromSeed_When_JitterConfigured` | Integration | Jitter reaches WorkManager |
| REQ-4: neutral port, two consumers | `TaskKeyTest` | `workName_should_keepLegacyGitNameAndHashOtherScopes_When_KeyBuilt` | Unit | Happy path |
| REQ-4 | `TaskKeyTest` | `taskKind_should_throw_When_IdHasUppercaseOrUnderscore` | Unit | Error path |
| REQ-4 | `TaskKeyTest` | `schedule_should_failOrCoerce_When_NegativeZeroOrJitterAboveInterval` | Unit | Illegal config unrepresentable |
| REQ-4 | `TaskKeyTest` | `register_should_beNoOpForEqualSpecAndThrowForConflict_When_RegisteredTwice` | Unit | Registry idempotence (B4) |
| REQ-4 | `JobsScheduleImportDirectionTest` | `scan_should_reportViolation_When_PlantedGitImportFixture` | Unit | Planted violation; real tree clean; non-zero scanned count |
| REQ-4 | `GitSyncTaskMappingTest` | `map_should_returnPartialSuccessSkippedOrFailed_When_EachTickOutcomeVariant` | Unit | GitSync consumer, exhaustive |
| REQ-4 | `GitSyncTaskMappingTest` | `map_should_neverReturnFailed_When_OutcomeIsSkip` | Unit | Skip is not failure |
| REQ-4 | `ModelDownloadTaskTest` | `runOnce_should_enqueueUnmeteredStorageNotLowRequestUnderUniqueName_When_ModelRequested` | Integration | Model-download consumer end to end |
| REQ-4 | `ModelDownloadTaskTest` | `runOnce_should_replaceWithConnected_When_DownloadNowAnywayTapped` | Integration | `OneShotConflict.Replace`; default `Keep` leaves request untouched |
| REQ-4 | `ModelDownloadTaskTest` | `worker_should_keepPartResumeAndFiveAttemptCap_When_RunThroughModelDownloadTask` | Integration | Existing worker behaviour preserved |
| REQ-4 | `ModelDownloadTaskTest` | `worker_should_requestForegroundOnlyWhenUserInitiated_When_RunOnceVsScheduled` | Integration | Foreground rule |
| REQ-4 | `TaskRegistryBothRootsTest` | `register_should_notThrowAndHoldExactlyTwoKinds_When_ApplicationThenActivityRootsRunTwice` | Integration | No double registration |
| REQ-5: run log, Late, no false Late | `TaskRunLogTest` | `append_should_keepNewestTwentyFirst_When_TwentyFiveRecordsAppended` | Unit | Happy path (cap) |
| REQ-5 | `TaskRunLogTest` | `recent_should_returnEmptyAndNotThrow_When_SettingsThrows` | Unit | Error path |
| REQ-5 | `TaskRunLogTest` | `lastSuccessAtMs_should_ignoreSkipsAndFailures_When_MixedAttempts` | Unit | Success separate from attempt |
| REQ-5 | `TaskRunLogTest` | `append_should_logLineWithResultAndConstraintState_When_SkippedMetered` | Unit | Persisted line and `Logger` line contain `result=Skipped:metered metered=true`, no credentials/URLs |
| REQ-5 | `ScheduleLatenessTest` | `of_should_returnLateAfterMultipleOfInterval_When_LastSuccess150MinAgo` | Unit | Happy path (Late at 150 min, OnTime at 90) |
| REQ-5 | `ScheduleLatenessTest` | `of_should_returnOnTime_When_LastSuccessInFutureOrForegroundSourceRecent` | Unit | Error path (clock back; foreground success) |
| REQ-5 | `ScheduleLatenessTest` | `of_should_attributeDeliberateSkip_When_LastRunsVaultLocked` | Unit | Cause attribution |
| REQ-5 | `ScheduleLatenessTest` | `of_should_returnNotApplicable_When_DisabledOrOneShot` | Unit | Not applicable |
| REQ-5 | `GitLastSuccessSourceTest` | `lastSuccessAtMs_should_takeMaxOfRunLogAndStaleness_When_ForegroundMergeNewer` | Unit | Arch-3 (no false Late) |
| REQ-5 | `GitLastSuccessSourceTest` | `lastSuccessAtMs_should_returnNullNotLate_When_ReadFails` | Unit | Error path |
| REQ-5 | `TaskRunLogRowsTest` | `logsScreen_should_showSkippedMeteredRowWithStartTime_When_RecordPersisted` | Integration | Persisted record to Logs screen (Robolectric, Gradle-only) |
| REQ-5 | `TaskSchedulerAndroidEntryPointTest` | `wiredApp_should_recordRunLogEntry_When_ScheduledWorkerFires` | Integration | `ObservableEffect` label `task-work-enqueued` plus log read-back |
| REQ-6: existing behaviour preserved | `GitSyncTaskMappingTest` | `run_should_returnSkippedVaultLockedAndMakeNoGitCall_When_PolicyVaultLocked` | Unit | Happy path (policy is the gate) |
| REQ-6 | `ScheduledSyncPolicyTest` (existing) | existing decision cases (Run / FetchOnly / Skip reasons) | Unit | Must stay green unchanged |
| REQ-6 | `PeriodicSyncDriverTest` / `ScheduledSyncStatusTest` (existing) | existing pause-after-3-failures, `immediateFirstTick`, busy/offline skips | Unit | Error path preserved; Desktop git keeps `PeriodicSyncDriver` |
| REQ-6 | `WorkManagerSyncSchedulerRetryOwnerTest` (existing) | non-retryable failure returns `Result.Failure` with empty registry | Integration | `GitSyncWorker` thin wrapper (N2) |
| REQ-6 | `WorkManagerSyncSchedulerUniqueWorkTest` (existing) | `launchClone` leaves periodic work ENQUEUED under shared name | Integration | Clone/sync exclusion |
| REQ-6 | `WorkManagerSyncSchedulerPauseTest` (existing) | `schedule(15)` then `pauseFor`/`resumeFor` with no store entry and no `SteleKitContext.init` | Integration | Snapshot-based resume (N1) |
| REQ-6 | `WorkManagerSyncSchedulerSlowPathYieldTest`, `WorkManagerSyncSchedulerWatchdogTest` (existing) | yield and watchdog assertions | Integration | Hooks call same functions |
| REQ-6 | `LegacyWorkUpgradeTest` | `apply_should_updateOldGitSyncWorkerJobInPlaceWithNewTags_When_PreUpgradeJobExists` | Integration | In-flight legacy row; stale input interval re-enqueued |
| REQ-6 | `GitSyncSchedulingEntryPointTest` | existing assertions (one periodic job, `closedAppChecks`, cancel on `setEnabled(false)`) | Integration | Updated fixture, assertions unchanged |
| REQ-6 | `GitSyncStackEntryPointTest`, `ScheduledSyncOriginE2ETest` (existing, jvmTest) | existing assertions on updated `DesktopWiredAppFixture` | Integration | Desktop stack unchanged |
| REQ-7: Android adapter lifecycle | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_restoreSnapshotInterval45_When_PausedAt45` | Integration | Happy path (not the 15-min floor) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_enqueueNothing_When_StoreEntryDisabledBeatsSnapshot` | Integration | Error path (never resurrect disabled work) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `pause_should_notClobberSnapshotOrCancelRunningClone_When_WorkNotPeriodicOrPausedTwice` | Integration | No-clobber, consume-on-resume, `GitSyncWorker` class kept |
| REQ-7 | `WorkManagerTaskSchedulerContractTest` | `contract_should_hold_When_RunAgainstWorkManagerAdapter` | Integration | Capability-parameterized contract suite |
| REQ-7 | `WorkManagerTaskSchedulerConstraintsTest` | `tags_should_excludeStelekitJobOnPeriodicAndIncludeOnOneShot_When_RequestsBuilt` | Integration | Jobs-list noise (C8) |
| REQ-7 | `ScheduledTaskReconcilerTest` | `reconcile_should_cancelOnlyGhostScope_When_KnownScopesContainsG1Only` | Integration | Happy path |
| REQ-7 | `ScheduledTaskReconcilerTest` | `reconcile_should_cancelNothingAndWriteNoTombstone_When_KnownScopesUnknown` | Integration | Error path (empty/corrupt/throwing registry) |
| REQ-7 | `TaskWorkerTest` | `doWork_should_cancelOwnWork_When_ScopeAbsentFromKnownRegistry` and `doWork_should_stillRun_When_KnownScopesUnknown` | Integration | Worker-side orphan guard |
| REQ-7 | `GraphManagerRemovalHookTest` | `removeGraph_should_invokeHookAfterSaveRegistry_When_RemovalSucceeds` | Integration | Happy path (cancelAll + tombstone) |
| REQ-7 | `GraphManagerRemovalHookTest` | `removeGraph_should_notInvokeHook_When_RefusedOrHookThrows` | Integration | Error path (refused guards; throwing callback keeps `true`) |
| REQ-7 | `GraphRegistryKnownScopesTest` | `read_should_returnUnknown_When_RegistryEmptyCorruptOrThrows` | Unit | Fail-closed provider |
| REQ-7 | `TaskWorkerTest` | `doWork_should_runHooksInOrderAndYield_When_GitCloneRunning` | Integration | preRun -> gate -> body -> postRun; yield returns `Skipped("clone-running")` |
| REQ-7 | `ScheduleStartupTest` | `reapplyAll_should_enqueueFromLedger_When_ForceStopWipedWorkManager` | Integration | Self-heal |
| REQ-8: Desktop in-process + Noop | `InProcessTaskSchedulerContractTest` | `contract_should_hold_When_RunAgainstInProcessAdapterOnVirtualTime` | Integration | Happy path (capabilities `minInterval=1`, `supportsUnmetered=false`) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `tick_should_logFailedAndContinue_When_TaskThrowsOutOfMemoryError` | Unit | Error path (`Throwable` caught, `CancellationException` rethrown) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `scheduler_should_ownScopeWithExceptionHandler_When_Constructed` | Unit | No caller scope; `stop()` cancels |
| REQ-8 | `NoopTaskSchedulerTest` | `apply_should_doNothingAndDescribeNull_When_EnabledConfig` | Unit | Happy path |
| REQ-8 | `NoopTaskSchedulerTest` | `capabilities_should_allBeFalse_When_NoopConstructed` | Unit | Honest capabilities |
| REQ-8 | `TaskSchedulerDesktopEntryPointTest` | `desktop_should_runOnePeriodicSyncDriverAndNoInProcessGitTask_When_GitSyncEnabled` | Integration | No double tick; `effect:in-process-task-tick` |
| REQ-9: legacy flag migration | `TaskSettingsStoreMigrationTest` | `read_should_migrateEnabledAndInterval30_When_LegacyFlagTrueAndGraphHasRemote` | Unit | Happy path (any platform, lazy) |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_writeNoMarkerAndStayDisabled_When_SeedUnavailableOrThrows` | Unit | Error path (cannot-read is not "no remote") |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_neverCallSeed_When_EntryOrMarkerExists` | Unit | Cold worker opens no DB |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_keepDisabled_When_UserDisabledAfterMigration` | Unit | Idempotent |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_migrateNeverComposedRemoteGraph_When_LegacyFlagTrue` | Unit | Documented semantics pinned |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `tombstone_should_blockSeedAndBeSupersededByWrite_When_GraphReAddedAtSamePath` | Unit | Re-add rule |
| REQ-9 | `LegacyGitSyncMigrationTest` | `seed_should_returnNullForOtherKindsAndThrowSeedUnavailable_When_SourceUnavailable` | Unit | Seed contract |
| REQ-9 | `ConfigRepositoryLegacyConfigSourceTest` | `read_should_returnFoundOrUnavailable_When_RepositoryAnswersOrThrows` | Unit | Desktop/active-graph source |
| REQ-9 | `AndroidGitLegacyConfigSourceTest` | `read_should_returnUnavailableNotNoRemote_When_DbPathUnopenable` | Integration | Real DB open on cold path |
| REQ-9 | `GitSyncScheduleControllerTest` | `ensureMigrated_should_enableAt30Minutes_When_DesktopGraphOpensWithLegacyFlag` | Unit | Controller glue; tombstone not migrated |
| REQ-9 | `TaskSchedulerDesktopEntryPointTest` | `desktop_should_startPeriodicSyncDriverAt30Min_When_OpenGraphHasLegacyFlagAndNoEntry` | Integration | Desktop never silently turns off |
| REQ-9 | `ScheduleStartupTest` | `run_should_migrateActiveGraphOnlyAndChangeNothingOnSecondStart_When_LegacyFlagTrue` | Integration | No DB fan-out |
| REQ-9 | `ScheduledSyncControlsApplyTest` | `setEnabled_should_persistFalseToStore_When_UserTogglesOff` | Integration | Per-graph entry behind `ScheduledSyncControls` |
| REQ-10: Settings UI | `ScheduledSyncPresentationTest` | `presetOf_should_roundTripMinutesFor_When_AnyPresetAndIntervalListed` | Unit | Happy path (property) |
| REQ-10 | `ScheduledSyncPresentationTest` | `intervalPresets_should_disableFiveMinutes_When_CapabilityMinIs15` | Unit | Error path (disabled-with-reason) |
| REQ-10 | `ScheduledSyncPresentationTest` | `jitterMinutes_should_recomputeFromPreset_When_IntervalChangesFrom60To120` | Unit | 6 -> 12 min, "Runs 120-132 min apart" |
| REQ-10 | `ScheduledSyncSettingsTest` | `section_should_showSwitchIntervalsNetworkAndAdvanced_When_RenderedForWorkNotes` | Integration | Compose/Robolectric, Gradle-only |
| REQ-10 | `ScheduledSyncSettingsTest` | `selectUnmetered_should_persistAndApplyOnce_When_UserPicksUnmetered` | Integration | Persist and apply |
| REQ-10 | `ScheduledSyncSettingsTest` | `control_should_revertAndShowCouldntSave_When_WriteOrApplyThrows` | Integration | Failed-save revert |
| REQ-10 | `ScheduledSyncRadioKeyboardTest` | `arrowKeys_should_moveSelectionSkippingDisabled_When_DesktopRadioGroupFocused` | Integration | `// jvmTest-reason: desktop-only` |
| REQ-11: restricted-background hint | `ScheduledSyncPresentationTest` | `hint_should_matchReason_When_BatterySaverStandbyAppRestrictedOrNoneWithLate` | Unit | Happy path |
| REQ-11 | `ScheduleLatenessTest` | `isRestricted_should_beTrueForStandbyAndFalseForNone_When_ProbeFake` | Unit | Probe derivation |
| REQ-11 | `ScheduledSyncPresentationTest` | `hint_should_beHidden_When_LateCauseIsDeliberateSkip` | Unit | Error path (no false blame) |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `openBatterySettings_should_showManualPathAndLogWarning_When_LauncherThrows` | Integration | Launcher failure fallback |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `actions_should_invokeExistingResumeUnlockAndConflictHandlersOnce_When_Tapped` | Integration | Reuse, not rebuild |
| REQ-12: fail closed, default off | `TaskConfigCodecTest` | `decode_should_returnDisabled_When_AnyRandomGarbageString` | Unit | Property over garbage |
| REQ-12 | `TaskSettingsStoreMigrationTest` | `read_should_returnDisabled_When_SettingsThrows` | Unit | Error path |
| REQ-12 | `TaskSettingsStoreMigrationTest` | `read_should_returnDisabledWithSpecDefaults_When_NoEntryAndNoLegacySource` | Unit | New graphs off |
| REQ-12 | `TaskWorkerTest` | `doWork_should_skipDisabledBeforeGateAndHooks_When_EntryDisabled` | Integration | Kill switch honoured in cold worker |
| REQ-12 | `ScheduleStartupTest` | `run_should_enqueueNothingAndNotCrash_When_KnownScopesUnknownOrSettingsThrow` | Integration | `Application.onCreate` safe |
| REQ-12 | `TaskWorkerTest` | `doWork_should_logFailedAndReturnFailure_When_TaskThrowsOutOfMemoryError` | Integration | `Throwable` guard, `CancellationException` rethrown |
| REQ-13: scale, bounded reads | `ScheduleStartupTest` | `run_should_openNoNonActiveGraphDbAndMakeNoNetworkCall_When_StartupRuns` | Integration | No fan-out |
| REQ-13 | `TaskRunLogTest` | `recent_should_readOnlyDerivedKeys_When_RegisteredKindsTimesGraphsQueried` | Unit | No key scan (25 graphs x kinds) |
| REQ-13 | `TaskKeyTest` | `workName_should_stayBoundedPerGraphKindPair_When_ManyGraphsGenerated` | Unit | Bounded unique names |
| REQ-13 | `TaskWorkerTest` | `doWork_should_notInvokeSeedOrOpenGraphDb_When_EntryExistsForNonActiveGraph` | Integration | Cold path DB discipline |
| REQ-13 | `TestHygieneAuditTest` / `TestPlacementAuditTest` (existing gates) | no new baseline lines | Integration | No `Thread.sleep`, `GlobalScope`, real-clock `delay` in new tests |
| REQ-14: wiring and registration gates | `WiringCompletenessTest` | `seams_should_haveEveryImplementationConstructedInRoot_When_NewSchedulersAdded` | Integration | Happy path (`WorkManagerTaskScheduler(`, `InProcessTaskScheduler(`, `NoopTaskScheduler(` in roots) |
| REQ-14 | `WiringCompletenessTest` | `seams_should_failOnStaleAllowlistOrMissingCatalog_When_Mismatch` | Integration | Error path (existing mechanism; catalog lines added) |
| REQ-14 | `JobsScheduleImportDirectionTest` | `scan_should_seeJobsScheduleSourcesInBothSourceSets_When_RunUnderBazel` | Unit | Non-zero scanned count per source set |
| REQ-14 | `AllBusinessTestsCompletenessTest` | `allBusinessTests_should_listEveryNewBusinessAndCommonTestClass_When_Run` | Integration | Registration audit |
| REQ-14 | `TaskSchedulerAndroidEntryPointTest` / `TaskSchedulerDesktopEntryPointTest` | `wiredApp_should_assertObservableEffectByReadBack_When_RootsConstructed` | Integration | Catalog labels `task-work-enqueued`, `in-process-task-tick` |
| REQ-15: LFS shares NetworkPolicy | `LfsNetworkGateTest` | `lfs_should_defer_When_DeferOnMeteredAndMeteredAndDetectionSupported` | Unit | Happy path |
| REQ-15 | `LfsNetworkGateTest` | `lfs_should_deferFailClosed_When_MeteredNullAndDetectionSupported` | Unit | Error path (Android null) |
| REQ-15 | `LfsNetworkGateTest` | `lfs_should_notDefer_When_MeteredDetectionUnsupportedOnDesktop` | Unit | Desktop unchanged |
| REQ-15 | `LfsTransferPolicyTest` / `AndroidGitRepositoryLfsGuardTest` (existing) | `git_lfs_defer_on_metered` key still read; guard unchanged | Integration | Settings key and Android metered read stay green |
| REQ-16: device reliability | manual only (Story 5.1.3) | no automated test; see UX/manual table row M-1..M-4 | n/a | Owner-run; not assertable in CI |

### Migration test (plan "Migration Plan": settings key-value, no SQL schema)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| REQ-9 | `TaskSettingsStoreMigrationTest` | `migration_should_be_reversible` | Migration | Run the lazy migration up (legacy `scheduled_sync_enabled=true` plus `git_config.poll_interval_minutes=30` becomes `task_entry_*` and `task_migrated_*`), assert the new keys; then "roll back" by reading with a legacy-only reader (`ScheduledSyncPreferences` and `git_config`) and assert both legacy keys are byte-identical to before and still read `true` / `30`; then drop the new keys and re-read: legacy flag re-derives the same result (marker absent), and with the marker present a downgrade-then-upgrade does not re-derive from a stale flag |

### Phase 4 repair additions (every new AC has a test; REQ column keeps the 16/16 mapping)

| Requirement | Plan AC | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|---|
| REQ-4 | P1-1: one-shot runs with no store entry | `TaskWorkerTest` | `doWork_should_runOneShotBody_When_NoStoreEntryExists` | Integration | Fresh install; `enabled` is periodic-only |
| REQ-4 | P1-1: explicit `enabled=false` does not stop a one-shot | `TaskWorkerTest` | `doWork_should_runOneShotBody_When_StoredEntryEnabledFalse` | Integration | Error path |
| REQ-4, REQ-2 | One-shot policy comes from `KEY_NETWORK_POLICY` | `TaskWorkerTest` | `doWork_should_honourInputPolicyAny_When_StoredDefaultIsUnmetered` | Integration | "Download now anyway" effective |
| REQ-4 | P1-1: end to end from empty `Settings` | `ModelDownloadEndToEndTest` | `downloadModel_should_writeFileAndReachReady_When_SettingsEmptyAndNetworkUnmetered` | Integration | Fresh install; also with a stored `enabled=false` entry |
| REQ-4, REQ-2 | B2: constraints resolved from store, default Unmetered | `ModelDownloadTaskTest` | `downloadModel_should_enqueueUnmeteredOrConnected_When_StoreEmptyOrAnyOrThrowing` | Integration | Read-back of the request (3 cases) |
| REQ-4 | `TaskOverrides.schedule` optional / `enabled` KDoc | `TaskKeyTest` | `taskOverrides_should_allowNullSchedule_When_OneShotKind` | Unit | Compile-level fixture; null schedule falls back to default for periodic |
| REQ-5, REQ-6 | P1-2b: one run-log record per `GitSyncWorker` run | `GitSyncWorkerGateAndRecordTest` | `doWork_should_appendExactlyOneRecord_When_SuccessSkipOrFailure` | Integration | Success, clone-yield skip, failure |
| REQ-5, REQ-12 | P1-2b: log failure never changes `Result` | `GitSyncWorkerGateAndRecordTest` | `doWork_should_keepResultUnchanged_When_RunLogWriteThrows` | Integration | Error path; `Logger` fallback |
| REQ-2, REQ-6 | P1-2a: thin worker gates on `KEY_NETWORK_POLICY` | `GitSyncWorkerGateAndRecordTest` | `doWork_should_skipMeteredWithoutGitCall_When_PolicyUnmeteredAndMetered` | Integration | `Skipped("metered")`, `Result.success()`; unknown/throwing provider -> `unknown-network`; absent key = `Any` proceeds |
| REQ-6 | P1-2: `RetryOwnerTest` still holds | `WorkManagerSyncSchedulerRetryOwnerTest` (existing) | non-retryable failure, empty registry, only `KEY_GRAPH_ID` | Integration | Gate must not turn this into a skip (Any + provider failure proceeds) |
| REQ-6, REQ-7 | Hook order in production worker | `GitSyncWorkerGateAndRecordTest` | `doWork_should_runPreRunGateYieldBodyPostRunInOrder_When_CloneRunning` | Integration | Moved from `TaskWorkerTest` |
| REQ-7 | P1-2: `apply` enqueues `GitSyncWorker` with `network_policy` input | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_enqueueGitSyncWorkerWithNetworkPolicyInput_When_GitSyncApplied` | Integration | Read-back of `workerClassName` and input data; `Replace` one-shot carries `any` |
| REQ-7 | Spike hard gate | `WorkManagerJitterSpikeTest` | `update_should_recordInputDataNextRunAndGapDistribution_When_ReappliedOnPeriodicWork` | Integration | Outcomes (5)-(7) in ADR-002; blocks 3.1.2c |
| REQ-1, REQ-9 | B1: git-sync default spread 6 min; migrated keeps 0 | `TaskSettingsStoreMigrationTest` | `read_should_resolveSixMinuteSpread_When_NewGraphAndZeroSpread_When_Migrated` | Unit | Two named tests |
| REQ-3 | `JitterPreset.ALittle.minutesFor(60) == 6`; `Schedule.coerce` ACs | `JitterPolicyPropertyTest` | `clamp_should_clampCoercedRawInts_When_IntervalBelowMinAndJitterTooLarge` | Unit | Replaces the unconstructible `Schedule(10,30)` example |
| REQ-12 | `readResult` distinguishes `ReadFailed` | `TaskSettingsStoreMigrationTest` | `readResult_should_returnReadFailedButReadStaysDisabled_When_SettingsThrows` | Unit | Error path |
| REQ-12, REQ-5 | Periodic `ReadFailed` -> skip + `SettingsReadHealth` | `TaskWorkerTest` | `doWork_should_logSettingsUnreadableAndSetHealth_When_ReadFailed` | Integration | No silent success; status shows "Can't read settings" |
| REQ-12, REQ-5 | Status line "Can't read settings" | `ScheduledSyncPresentationTest` | `statusLine_should_showCantReadSettingsAndNeverLate_When_HealthFailureNewerThanSuccess` | Unit | Precedence: after Disabled-with-reason, before Late |
| REQ-5 | B4: interleaved Background/Foreground writers | `TaskRunLogTest` | `recent_should_keepAllRecordsOfBothSources_When_WritersInterleaved` | Unit | No lost update; keys never collide (property) |
| REQ-5, REQ-6 | B4: driver tick recorder | `PeriodicSyncDriverRunRecordTest` | `tick_should_appendOneForegroundRecordPerOutcome_When_SuccessFetchedSkipFailure` | Unit | Throwing recorder leaves `TickOutcome`/pause logic unchanged; existing driver tests unchanged |
| REQ-5 | B4: Logs show both sources | `TaskRunLogRowsTest` | `logs_should_showForegroundAndBackgroundRows_When_BothPersisted` | Integration | Desktop in-app runs visible |
| REQ-7 | P2 #3: snapshot-write failure never blocks cancel | `WorkManagerTaskSchedulerPauseResumeTest` | `pause_should_stillCancelAndResumeFromMemoryCopy_When_SnapshotWriteThrows` | Integration | Failure ordering; also `WorkInfo` read throws -> ledger fallback |
| REQ-7 | P2 #3b: `apply` during pause updates snapshot | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_restoreUpdatedConfig_When_ApplyCalledWhilePaused` | Integration | Also `apply(enabled=false)` clears the snapshot; same invariant in the contract suite (`BackgroundTaskSchedulerContract`) |
| REQ-7, REQ-13 | `reapplyAll` reads ledger only | `ScheduleStartupTest` | `reapplyAll_should_neverCallSeedOrOpenGraphDb_When_LedgerHasEntries` | Integration | Counting seed |
| REQ-8, REQ-10 | Noop hides section; Desktop shows it | `BackgroundTasksSectionTest` | `section_should_beHiddenOnNoopAndShownOnDesktop_When_SchedulesWorkVaries` | Integration | `schedulesWork` capability |
| REQ-10, REQ-13 | S1 empty state; search at 8 not 7; lazy rows; no cap | `BackgroundTasksSectionTest` | `list_should_showEmptyStateAddGraphAndSearchOnlyFromEightGraphs_When_GraphCountVaries` | Integration | C11 |
| REQ-10 | One-shot row shows network only | `BackgroundTasksSectionTest` | `modelDownloadRow_should_showOnlyNetworkChoice_When_OneShotKind` | Integration | No switch, no interval, no `apply` |
| REQ-10 | Per-adapter spread copy and "Takes effect after the next run" | `ScheduledSyncPresentationTest` | `spreadSummary_should_deriveFromDescribeGapFields_When_DesktopOrWorkManagerAdapter` | Unit | Replaces the global "N-M" formula |
| REQ-10 | UX-11 clamp summary + Desktop helper; 15-min reason pinned | `ScheduledSyncSettingsTest` | `summary_should_showClampAndDesktopHelperAndPinnedReason_When_AndroidFiveMinOrDesktop` | Integration | `ANDROID_FIVE_MINUTE_REASON` equals the `ux.md` copy |
| REQ-4, REQ-15 | S6 presentation mapping | `ModelDownloadPresentationTest` | `present_should_mapEveryWorkStateToCopyAndAnExit_When_StateTableIterated` | Unit | Waiting/offline/running/retrying/failed-after-5 |
| REQ-15 | S8 shared strings | `ScheduledSyncPresentationTest` | `lfsLabelAndDeferralStatus_should_shareConstantsWithS2_When_Compared` | Unit | Folded into the UX-6 "Wi-Fi only" scan |

## UX Acceptance Tests

Source: `../design/ux.md` Step 3 (UX-1 to UX-29). Tools: **RobolectricCompose** = `androidUnitTest` Compose test run with `./gradlew :kmp:testDebugUnitTest --tests ...` (Gradle-only, headless); **JvmCompose** = `jvmTest` Compose Desktop test run via `scripts/jvm-display-check.sh -- ./gradlew :kmp:jvmTest --tests ...` (needs a real display); **Unit** = commonTest presentation function; **Manual** = owner checklist on a device (TalkBack, OEM, Esc focus return). The plan names `ScheduledSyncSettingsTest`, `ScheduledSyncStatusActionsTest`, `TaskRunLogRowsTest`, `ScheduledSyncRadioKeyboardTest`; `BackgroundTasksSectionTest` and `ModelDownloadUiTest` below are new Robolectric Compose files (Gradle-only, not registered in `AllAndroidUnitTests`); the Phase 4 repair added them to the plan (Tasks 4.1.1a and 3.2.1a/3.2.1e).

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| UX-1: turn sync on in <=3 steps | `BackgroundTasksSectionTest` | `enableSync_should_takeAtMostThreeTaps_When_StartingFromSettings` | RobolectricCompose | Open Settings > Background tasks > Git sync > graph row, toggle switch; count clicks; assert `apply` called once with defaults |
| UX-2: change interval and network in <=4 taps | `ScheduledSyncSettingsTest` | `changeIntervalAndNetwork_should_takeAtMostFourTapsWithoutLeavingScreen_When_FromSettings` | RobolectricCompose | From Settings, drill to graph, pick "2 hours", pick "Unmetered networks only"; assert both persisted |
| UX-3: jitter only under Advanced, preset labels | `ScheduledSyncSettingsTest` | `jitter_should_appearOnlyUnderAdvancedWithOffALittleALotLabels_When_SectionRendered` | RobolectricCompose | Assert preset labels absent until Advanced expanded; assert no numeric text field node exists |
| UX-4: off is one tap, works with broken precondition | `ScheduledSyncSettingsTest` | `switch_should_turnOffInOneTap_When_RemoteLostAfterEnable` | RobolectricCompose | Enable, remove remote, assert switch enabled, tap once, assert `apply(enabled=false)` |
| UX-5: 25 graphs, one summary per kind | `BackgroundTasksSectionTest` | `list_should_showOneSummaryLinePerKindAndUsingDefaultOrCustomRows_When_25Graphs` | RobolectricCompose | Seed 25 graphs, mixed overrides; assert no expanded cards, rows read "Using default" / "Custom: ...", search field shown at >=8 |
| UX-6: "Any network" / "Unmetered networks only", never "Wi-Fi only" | `ScheduledSyncPresentationTest` | `networkLabels_should_beExactAndNeverContainWifiOnly_When_AllLocalizedStringsScanned` | Unit | Assert exact label strings; scan string constants (settings, LFS section) for "Wi-Fi only" |
| UX-7: "Hasn't run in N h", never "behind" | `ScheduledSyncPresentationTest` | `lateLine_should_sayHasntRunInNhAndNeverBehind_When_LastSuccess3hAgo` | Unit | Render line at 3 h; assert text; assert "behind" absent from Late copy |
| UX-8: one status line, precedence | `ScheduledSyncPresentationTest` | `statusLine_should_followPrecedencePausedDisabledUnreadableLateInProgressChecked_When_StatesCombined` | Unit | Property over state combinations; assert exactly one line and the precedence order (Paused > Disabled-with-reason > Can't-read-settings > Late > CheckInProgress > Checked) |
| UX-9: deliberate skip names reason, hides hint | `ScheduledSyncStatusActionsTest` | `status_should_sayLockedAndHideRestrictedHint_When_LastRunsVaultLocked` | RobolectricCompose | Seed `Skipped("vault-locked")` x3, last success 5 h; assert "Locked. Sync resumes after you unlock." and no hint row |
| UX-10: no clock time of next run | `ScheduledSyncPresentationTest` | `statusLine_should_useAboutAndNeverClockTime_When_NextRunRendered` | Unit | Regex assert no `\d{1,2}:\d{2}` in any status copy |
| UX-11: effective schedule shown when it differs | `ScheduledSyncSettingsTest` | `summary_should_showClampAndSpreadWindow_When_AndroidFiveMinOrJitterSet` | RobolectricCompose | Assert "Runs every 15 min on this device" when clamped; the spread line from the adapter's `describe` gap fields ("Runs 60-66 min apart" on the Desktop adapter at 60 + "A little"; the spike-derived flex wording on the WorkManager adapter, plus "Takes effect after the next run" when `appliesAfterNextRun`); Desktop shows the existing "Syncs with the remote while SteleKit is open." helper |
| UX-12: Desktop unmetered disabled with reason | `ScheduledSyncSettingsTest` | `unmetered_should_beDisabledWithDesktopReason_When_SupportsUnmeteredFalse` | RobolectricCompose | Capabilities `supportsUnmetered=false`; assert disabled node and exact reason text |
| UX-13: Android 5 min not listed, helper line (triad repair 1) | `ScheduledSyncSettingsTest` | `fiveMinute_should_beAbsentWithAndroid15MinHelperLine_When_MinIntervalIs15` | RobolectricCompose | Assert no 5 min radio on Android (present and enabled on Desktop) and the helper "Android runs at most every 15 minutes." |
| UX-14: every disabled control has reason and fix action | `ScheduledSyncSettingsTest` | `disabledControls_should_showOneLineReasonAndFixAction_When_NoRemoteOrFirstSyncUnreviewed` | RobolectricCompose | For each disabled control assert a reason node; assert "set up remote" / "review first sync" buttons invoke existing handlers |
| UX-15: unreadable settings shows Retry, nothing runs | `ScheduledSyncSettingsTest` | `section_should_showCouldntReadSettingsWithRetryAndNeverApply_When_ReadFailsClosed` | RobolectricCompose | Throwing `Settings`; assert message, Retry re-reads, fake scheduler records zero `apply` calls |
| UX-16: failed save reverts and shows message | `ScheduledSyncSettingsTest` | `control_should_revertAndShowCouldntSave_When_WriteThrows` | RobolectricCompose | Throwing write; pick network option; assert control reverts, message shown, no `apply` |
| UX-17: Open battery settings launches app page or falls back | `ScheduledSyncStatusActionsTest` | `openBatterySettings_should_launchAppDetailsOrShowManualPath_When_LauncherSucceedsOrThrows` | RobolectricCompose | Fake launcher: assert `ACTION_APPLICATION_DETAILS_SETTINGS` intent; on throw assert "Open Android Settings > Apps > SteleKit > Battery." and a `Logger` warning |
| UX-18: hint button per reason | `ScheduledSyncStatusActionsTest` | `hint_should_showButtonOnlyForStandbyAppRestrictedAndOemLate_When_ReasonVaries` | RobolectricCompose | BatterySaver: no button; Standby/AppRestricted/Late+None: "Open battery settings" button |
| UX-19: metered model download offers override, per request only | `ModelDownloadUiTest` | `download_should_showWaitingWithDownloadNowAnywayAndCancel_When_NetworkMetered` | RobolectricCompose | Queue unmetered request on metered network; assert row text and both buttons; tap override; assert `Replace` call and stored default still `Unmetered` |
| UX-20: every failure state has an exit | `ScheduledSyncSettingsTest` | `errorStates_should_eachOfferRetryCancelFixOrBack_When_S1ToS6FailuresInjected` | RobolectricCompose | Inject each S1-S6 failure (settings read, save, launcher, run-log read, download failure); assert at least one actionable node per state; plus manual dead-end sweep M-5 |
| UX-21: skipped run in Logs | `TaskRunLogRowsTest` | `logs_should_showKindScopeSkippedReasonAndStartTime_When_SkippedRunPersisted` | RobolectricCompose | Persist `Skipped("metered")`; open Logs; assert "Git sync / Work notes / skipped: metered" (display names, UX-31) and time |
| UX-22: empty/unreadable run log safe | `TaskRunLogRowsTest` | `logs_should_hideSectionOrShowNoRunsAndRetryOnUnreadable_When_EmptyOrThrowing` | RobolectricCompose | Empty: no crash; throwing `Settings`: "Couldn't read run history" + Retry, rest of Logs renders |
| UX-23: persisted log line content, no secrets | `TaskRunLogTest` | `logLine_should_containKindScopeTimesResultAndConstraintsAndNoCredentialsOrUrls_When_Appended` | Unit | Append record with a remote URL in context; assert required fields present and URL/token absent |
| UX-24: keyboard navigation | `ScheduledSyncRadioKeyboardTest` | `keyboard_should_tabToEveryRowSpaceToggleAndArrowsMoveRadios_When_DesktopFocused` | JvmCompose | Tab through rows, Space on switch/radio, arrows in radio group, assert focus indicator node |
| UX-25: merged node announcement and labels | `ScheduledSyncSettingsTest` | `graphRow_should_exposeOneMergedNodeWithExactDescription_When_SemanticsRead` | RobolectricCompose | Assert "Git sync, Work notes, on, about every hour, unmetered networks only"; every control has a content description |
| UX-26: 48 dp targets, one focus stop per row | `ScheduledSyncSettingsTest` | `rows_should_be48dpAndSingleFocusStop_When_RadiosAndSwitchesRendered` | RobolectricCompose | Assert node sizes >=48 dp; inner control `onClick = null`; one focusable per row |
| UX-27: polite live region, no re-announce on unchanged refresh | `ScheduledSyncStatusActionsTest` | `statusLine_should_notReannounce_When_ThirtySecondRefreshUnchanged` | RobolectricCompose | Advance virtual clock 30 s with same state; assert live-region text node unchanged and region mode polite |
| UX-28: state not by colour alone, contrast >=4.5:1 | `ScheduledSyncStatusActionsTest` | `lateAndPausedAndHint_should_pairTextWithIconAndMeetContrast_When_LightAndDark` | RobolectricCompose | Assert icon + text nodes; compute contrast of theme colours for both themes >=4.5:1 |
| UX-29: Esc closes dialog and returns focus | manual | M-6 `escape_should_closeSettingsDialogAndRestoreFocus` | Manual | On Desktop open the settings dialog from a control, press Esc, confirm dialog closes and focus returns to the opener (not yet verifiable by Compose Desktop run per ux.md note) |
| UX-30: Reset to default on Custom rows | `ScheduledSyncSettingsTest` | `resetToDefault_should_clearOverridesKeepEnabledAndApplyOnce_When_CustomRowTapped` | RobolectricCompose | Seed overrides; assert button only on Custom rows; tap; assert row "Using default", `enabled` unchanged, one `apply`; throwing write reverts |
| UX-31: Logs display names and date+time | `TaskRunLogRowsTest` | `logs_should_showTaskLabelGraphDisplayNameAndTodayVsDateTime_When_RecordsPersisted` | RobolectricCompose | Assert "Git sync / Work notes / ..." (no raw ids), time-only today, relative within the hour, "Nov 9, 21:15" earlier |
| UX-32: S2 loading state | `ScheduledSyncSettingsTest` | `section_should_showSkeletonDisabledControlsAndLoadingReason_When_ReadPending` | RobolectricCompose | Delay `readResult`; assert placeholders, disabled controls with "Loading settings...", one polite announcement, no default values shown |
| UX-33: focus/title on drill-in and back | `BackgroundTasksSectionTest` | `drillIn_should_moveFocusToTitleAndBackRestoreOpener_When_S1ToKindToGraph` | RobolectricCompose | Drill and back; assert focused node is the title, then the opener; TalkBack on device is M-9 |
| UX-34: labelled search, No matches | `BackgroundTasksSectionTest` | `search_should_haveLabelAndShowNoMatchesWithClear_When_QueryMatchesNothing` | RobolectricCompose | 8 graphs; assert label "Search graphs", accessible name, "No matches", "Clear search" works |
| UX-35: disabled reason in semantics | `ScheduledSyncSettingsTest` | `disabledControl_should_exposeReasonInSemantics_When_RemoteMissingOrUnmeteredOnDesktop` | RobolectricCompose | Assert node state description includes disabled plus the reason text; device wording is M-7 |
| UX-36: large font, RTL, Custom format, days | `ScheduledSyncPresentationTest` / `ScheduledSyncSettingsTest` | `customSummary_should_listOnlyDifferingValues_When_OneTwoOrThreeDiffer`; `lateLine_should_useDaysAfter48Hours_When_LastSuccess3DaysAgo`; `rows_should_wrapAt200PercentFontAndMirrorUnderRtl_When_Rendered` | Unit + RobolectricCompose | Exact "Custom: every 6 h" / "Custom: 3 changes"; "Hasn't run in 3 days"; no clipped text, 48 dp kept |
| UX-37: success feedback after enabling | `ScheduledSyncSettingsTest` | `enable_should_announceFirstCheckInAboutNMin_When_SwitchTurnedOn` | RobolectricCompose | Assert "First check in about N min" with the clamped interval and no clock time |
| UX-38: S6 in-progress, cancelled, notification denied | `ModelDownloadUiTest` / `ModelDownloadPresentationTest` | `download_should_showProgressCancelledAndNotificationsOffExplanationWithExit_When_StateVaries` | RobolectricCompose + Unit | Assert each state's copy and an exit; denied permission does not block the download |
| UX-39: busy/editing copy | `ScheduledSyncPresentationTest` | `statusLine_should_useExplicitEditingOrBusyCopyThenRevert_When_LastRunSkippedForEditingOrBusy` | Unit | "Sync paused while you edit. It continues when you stop." / "Another sync is running."; reverts to Checked |

### Manual / owner-run checklist (no automated equivalent)

| ID | Covers | Scenario | Tool | Steps |
|---|---|---|---|---|
| M-1 | REQ-16 | AVD standby / Doze | Manual (owner, `Pixel_Fold`) | Enable git sync at 15 min, Unmetered; `adb shell cmd jobscheduler run`; set Rare bucket via `adb shell am set-standby-bucket`; reopen app; read persisted logs (`filesDir/logs/current.log`, Logs screen), not logcat; confirm `TaskRun` records show scheduled-vs-actual start and Late appears after 2x interval |
| M-2 | REQ-2, REQ-16 | Unmetered holds on mobile data | Manual (owner, physical device) | Policy Unmetered on mobile data; trigger worker; confirm `Skipped:metered` and no request in the remote access log |
| M-3 | REQ-16 | OEM battery manager | Manual (owner, Xiaomi/Samsung class) | Leave app sleeping 24 h; record run count and whether Late appeared; paste into PR |
| M-4 | REQ-12, REQ-5 | Keystore-backed settings on cold worker | Manual (owner, real device) | Kill process; let scheduled `TaskWorker` run cold; reopen; confirm stored config read, run-log record present, note write latency |
| M-5 | UX-20 | Dead-end sweep | Manual | Walk S1 to S6 failure states by fault injection; confirm each has Retry, Cancel, fix action or Back |
| M-6 | UX-29 | Esc / focus return | Manual (Desktop) | As described in UX-29 row |
| M-7 | UX-25, UX-35 | TalkBack merged-node and disabled-reason wording on a device | Manual (owner, device) | Read a graph row and a disabled control with TalkBack; confirm one announcement "Git sync, Work notes, on, about every hour, unmetered networks only" and "<control>, disabled, <reason>" |
| M-8 | UX-28 | Contrast and icon-plus-text pairing on a device | Manual (owner, device) | In light and dark themes view Late, Paused and the restricted hint; confirm each pairs an icon with text and reads clearly (>= 4.5:1); paste result into the PR |
| M-9 | UX-33, UX-34 | TalkBack focus and announcement on drill-in/back and search | Manual (owner, device) | With TalkBack drill S1 -> kind -> graph and back; confirm focus lands on the title and returns to the opener; search with no match announces "No matches" |

## Test Stack
- **Unit**: Kotlin `kotlin.test` with `kotest-assertions-core` and `kotest-property` (`Arb`/`checkAll` inside `runTest`) in `commonTest` and `businessTest`; `FakeBackgroundTaskScheduler` plus abstract `BackgroundTaskSchedulerContract` (commonTest, no JVM-only APIs, Rule 5); in-memory and throwing `Settings` fakes; virtual time only (`TestHygieneAuditTest`). `androidUnitTest` uses plain `kotlin.test`, no `Arb` (Rule 6).
- **Integration**: Robolectric with `WorkManagerTestInitHelper` and `TestListenableWorkerBuilder` (androidUnitTest, run via Gradle); real `GraphManager` with in-memory `Settings` (businessTest); `WiredAppFixture` / `DesktopWiredAppFixture` with `ObservableEffect.assert` (wiring gates); contract suite subclassed per adapter (fake, `InProcessTaskScheduler` in jvmTest, `WorkManagerTaskScheduler` in androidUnitTest).
- **E2E / UX**: Compose UI tests in Robolectric (headless, Gradle-only) for S1-S6; one jvmTest (`ScheduledSyncRadioKeyboardTest`, needs a real display, run through `scripts/jvm-display-check.sh`); owner device checklist M-1..M-7. Playwright does not apply (no browser surface for these screens; wasm has no git sync).

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM commonMain + jvm (pure core, desktop adapter) | `./gradlew :kmp:jvmTest` then the project's coverage task if configured (UNVERIFIED that jacoco/kover is wired; check `kmp/build.gradle.kts` before relying on a number) | >=80% line on `jobs/schedule/**`, `git/GitSyncTask*`, `git/GitSyncScheduleController`, `git/GitLastSuccessSource` |
| Kotlin/Android (androidMain adapter) | `./gradlew :kmp:testDebugUnitTest` (Robolectric) | >=80% line on `androidMain/.../jobs/schedule/**` |
| Bazel registration proof | `timeout 30m bazel test //kmp:business_tests`; `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`; `timeout 30m bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` | all green; registration of every new class greppable in `AllBusinessTests` / `AllJvmTests` / `AllAndroidUnitTests` |

- All public port methods (`apply`, `runOnce`, `cancel`, `cancelAll`, `pause`, `resume`, `describe`) have happy and error coverage through the contract suite, run against the fake, `InProcessTaskScheduler` and `WorkManagerTaskScheduler`.
- All external integrations (WorkManager, `ConnectivityManager`, `GraphManager.removeGraph`, per-graph SQLite `git_config`, `PlatformSettings`) have a unit fake plus at least one integration test (Robolectric WorkManager, real `GraphManager`, `AndroidGitLegacyConfigSourceTest`; Keystore path is owner-verified, M-4, because Robolectric has no Keystore).
- UX acceptance criteria: UX-1 to UX-39 each map to one automated test or a named manual step (UX-29 is manual-only M-6; UX-20 and UX-25 also carry a manual step).

## Gaps and notes
- REQ-16 has no automated test by design: Doze, standby buckets and OEM managers cannot be reproduced in CI (requirements.md "Wall-clock blockers"). Covered by manual M-1..M-4 only.
- UX-29 has no automated test (Compose Desktop focus return is not verifiable by a run, per `ux.md` gaps note); manual M-6 only.
- RESOLVED (Phase 4 repair): `BackgroundTasksSectionTest` (UX-1, UX-5, S1 empty state, search at 8, Noop hiding) is now named in Task 4.1.1a and `ModelDownloadUiTest` (UX-19, UX-20 S6 states) in Tasks 3.2.1a/3.2.1e, both Compose/Gradle-only and excluded from `AllAndroidUnitTests`.
- Unresolved by design: the Android spread copy and `describe` gap fields depend on Spike 0.1.1's observed distribution; the `spreadSummary_*` and UX-11 tests pin the derivation rule, and their expected Android strings are filled in when the spike lands.
- Rows labelled "(existing)" are regression gates believed to be in the repo; their names were taken from plan.md, not re-opened here (INFERRED). **Verify each exists before relying on it** (`ScheduledSyncPolicyTest`, `PeriodicSyncDriverTest`, `ScheduledSyncStatusTest`, `WorkManagerSyncSchedulerRetryOwnerTest`, `WorkManagerSyncSchedulerUniqueWorkTest`, `WorkManagerSyncSchedulerPauseTest`, `WorkManagerSyncSchedulerSlowPathYieldTest`, `WorkManagerSyncSchedulerWatchdogTest`, `GitSyncSchedulingEntryPointTest`, `GitSyncStackEntryPointTest`, `ScheduledSyncOriginE2ETest`, `LfsTransferPolicyTest`, `AndroidGitRepositoryLfsGuardTest`): a missing class means the regression gate must be written, not assumed (Task 3.1.2b characterization run is the checkpoint).
- Engineering note (triad repair 1): story-level waves in `plan.md` understate serial depth; the root-file chain is about 10 serial tasks. PR-stack check: `gh pr list` found no open git-sync PR at triad time; Tasks 0.1.2b and 3.1.2a re-check.
- Triad repair 1 UX additions: UX-30..UX-39 map to the rows above (UX coverage 39/39); UX-28, UX-29, UX-25/35, UX-33/34 also carry owner device items M-6..M-9 (plan Story 5.1.3). The count in the Inputs line (115 tasks) is now 116 tasks (new Task 4.1.1g).
- Test method names are proposals in the required `method_should_X_When_Y` form; implementers may adjust spelling but not the scenario.
