# Validation Plan: background-task-scheduling

**Date**: 2026-11-10

Inputs: `../requirements.md`, `plan.md`, `../design/ux.md`, `pre-mortem.md`. Status after implementation: the mapping tables below were regenerated from the real test sources (the pre-implementation table listed proposed names, most of which were never created). REQ-1..REQ-15 each have at least one real automated test; REQ-16 is an explicit GAP (owner-run only). `requirements.md` has no REQ-n numbering, so REQ ids are assigned here from its Success Metrics, In Scope list, Constraints and NFRs. Android evidence is `./gradlew :kmp:testDebugUnitTest --tests '<fqcn>'`; several Compose test classes are Gradle-only and not registered in `AllAndroidUnitTests`.

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

Regenerated from the test sources on branch `stelekit-background-sync` (review range `825ed69d77..HEAD` plus the pre-existing regression gates named in plan.md). Every name below is a real `@Test` function; `GAP` marks a requirement (or part of one) with no automated test. Type: Unit = commonTest/businessTest; Compose (Robolectric) = androidUnitTest UI; Integration = Robolectric WorkManager/wired-app or jvmTest. Each test is listed once, under its primary requirement. Verification (script, run against the sources): 674 name rows listed in this document (610 distinct tests; UX rows repeat tests from this table), 674 found verbatim as `fun` declarations in the named file, 0 missing.

| Requirement | Test File | Test Name | Type |
|---|---|---|---|
| REQ-1: per-graph/kind settings persisted and reaching the scheduler | `BackgroundTaskSchedulerContract` | `apply_should_beIdempotentAndFloorInterval_When_CalledTwice` | Unit |
| REQ-1 | `BackgroundTaskSchedulerContract` | `apply_should_removeWork_When_Disabled` | Unit |
| REQ-1 | `BackgroundTaskSchedulerContract` | `apply_should_reportClamp_When_IntervalBelowMinimum` | Unit |
| REQ-1 | `BackgroundTaskSchedulerContract` | `apply_should_updateSnapshotNotEnqueue_When_AppliedWhilePaused` | Unit |
| REQ-1 | `BackgroundTaskSchedulerContract` | `cancel_should_leaveOtherKeys_When_OneKeyCancelled` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `concurrentEdits_should_loseNoUpdate_When_SettingsPanelAndGitSyncControllersShareAStore` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `concurrentEdits_should_loseNoUpdate_When_TwoGitSyncControllerEditsRace` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `concurrentEdits_should_loseNoUpdate_When_TwoPanelEditsRace` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `controller_should_NeverCallApply_When_SchedulerDoesNotRunWhenClosed` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `failedApply_should_restoreSchedulerToPreviousConfig_When_PanelChangeCannotBeApplied` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `reconcile_should_ApplyDisabled_When_StoredDisabled` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `reconcile_should_ApplyStoredConfigOnce_When_SchedulerRunsWhenClosed` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `reconcile_should_ReturnNullAndApplyNothing_When_LegacyConfigUnavailable` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `setEnabledFalse_should_PersistAndApplyDisabled_When_PreviouslyEnabled` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `setEnabled_should_UseDefaultSchedule_When_EnablingEntryWithNoSchedule` | Unit |
| REQ-1 | `GitSyncScheduleControllerTest` | `setSchedule_should_KeepEnabledAndConstraints` | Unit |
| REQ-1 | `TaskConfigCodecTest` | `codec_should_roundTripAnyEntry_When_Generated` | Unit |
| REQ-1 | `TaskConfigCodecTest` | `decodeDefaults_should_roundTrip_When_DefaultsStored` | Unit |
| REQ-1 | `TaskConfigCodecTest` | `decodeEntry_should_returnNull_When_StoredStringIsGarbage` | Unit |
| REQ-1 | `TaskConfigCodecTest` | `decodeEntry_should_roundTripNullScheduleOverride_When_OneShotKind` | Unit |
| REQ-1 | `TaskConfigCodecTest` | `decodeEntry_should_roundTrip_When_EntryHasOverrides` | Unit |
| REQ-1 | `TaskConfigCodecTest` | `settingsKeys_should_beDisjointFamilies_When_SameKey` | Unit |
| REQ-1 | `TaskKeyTest` | `oneShotOverridesAndNullScheduleFallbackAreRepresentable` | Unit |
| REQ-1 | `TaskSettingsStoreMigrationTest` | `read_should_giveNewGitSyncGraphSixMinuteJitter_When_NoEntry` | Unit |
| REQ-1 | `TaskSettingsStoreMigrationTest` | `read_should_notCollideWithDefaults_When_ScopeIsAppWide` | Unit |
| REQ-1 | `TaskSettingsStoreMigrationTest` | `read_should_resolveDefaultsAndReportUsingDefault_When_EntryHasNoOverrides` | Unit |
| REQ-1 | `TaskSettingsStoreMigrationTest` | `read_should_returnSpecConstraintsIgnoringEnabled_When_OneShotKindHasNoEntry` | Unit |
| REQ-1 | `TaskSettingsStoreMigrationTest` | `read_should_useOverrides_When_EntryIsCustom` | Unit |
| REQ-1 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_enqueueGitSyncWorkerWithNetworkPolicyInput_When_GitSyncApplied` | Integration (Robolectric) |
| REQ-1 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_enqueuePeriodicWorkWithReadBackIntervalFlexAndUnmetered_When_ConfigApplied` | Integration (Robolectric) |
| REQ-1 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_mapChargingAndBatteryNotLowConstraints_When_Requested` | Integration (Robolectric) |
| REQ-2: unmetered/unknown network never starts a run | `FakeBackgroundTaskSchedulerContractTest` | `fireNow_should_skipWithoutRunningBody_When_UnmeteredPolicyOnMeteredNetwork` | Unit |
| REQ-2 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_proceed_When_PolicyAnyAndNetworkMetered` | Integration (Robolectric) |
| REQ-2 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_proceed_When_PolicyAnyAndProviderThrows` | Integration (Robolectric) |
| REQ-2 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_proceed_When_PolicyKeyAbsentAsInAPreUpgradeJob` | Integration (Robolectric) |
| REQ-2 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_skipMeteredAndLogOneRecord_When_UnmeteredPolicyOnMeteredNetwork` | Integration (Robolectric) |
| REQ-2 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_skipOffline_When_ProviderExplicitlyReportsOffline` | Integration (Robolectric) |
| REQ-2 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_skipPostRun_When_CloneYieldSkips` | Integration (Robolectric) |
| REQ-2 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_skipUnknownNetwork_When_UnmeteredPolicyAndMeteringUnknown` | Integration (Robolectric) |
| REQ-2 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_skipUnknownNetwork_When_UnmeteredPolicyAndProviderThrows` | Integration (Robolectric) |
| REQ-2 | `InProcessTaskSchedulerContractTest` | `tick_should_skipWithoutRunningBody_When_UnmeteredAndMeteringUnknown` | Integration (jvm) |
| REQ-2 | `NetworkGateTest` | `evaluateMetered_should_equalEvaluateWithOnlineTrue_When_CalledByLfs` | Unit |
| REQ-2 | `NetworkGateTest` | `evaluate_should_neverProceedOffline_When_AnyInputs` | Unit |
| REQ-2 | `NetworkGateTest` | `evaluate_should_proceedOnlyWhenOnlineAndNotMetered_When_PolicyUnmetered` | Unit |
| REQ-2 | `NetworkGateTest` | `evaluate_should_skipUnknownNetwork_When_UnmeteredAndMeteredIsNull` | Unit |
| REQ-2 | `NetworkGateTest` | `skip_should_mapToSkippedNeverFailed_When_NetworkSkipReasonMapped` | Unit |
| REQ-2 | `OneShotSkipTest` | `downloadModel_should_resolveFailed_When_meteredStateIsUnknown` | Integration (Robolectric) |
| REQ-2 | `OneShotSkipTest` | `downloadModel_should_resolveFailed_When_networkIsMetered` | Integration (Robolectric) |
| REQ-2 | `OneShotSkipTest` | `worker_should_retryThenFailTerminalWithError_When_meteredSkipsShareTheFiveAttemptBudget` | Integration (Robolectric) |
| REQ-2 | `TaskWorkerTest` | `doWork_should_failClosedToUnmetered_When_oneShotInputPolicyAbsent` | Integration (Robolectric) |
| REQ-2 | `TaskWorkerTest` | `doWork_should_failClosed_When_oneShotMeteredUnknownEvenIfConstraintSatisfied` | Integration (Robolectric) |
| REQ-2 | `TaskWorkerTest` | `doWork_should_retryBelowCapThenFailTerminal_When_oneShotSkippedForMetered` | Integration (Robolectric) |
| REQ-2 | `TaskWorkerTest` | `doWork_should_shareOneBudget_When_skipsThenTransientFailuresMix` | Integration (Robolectric) |
| REQ-2 | `TaskWorkerTest` | `doWork_should_skipMeteredAndSucceed_When_periodicUnmeteredPolicyOnMeteredNetwork` | Integration (Robolectric) |
| REQ-2 | `TaskWorkerTest` | `doWork_should_skipUnknownNetwork_When_isMeteredNull` | Integration (Robolectric) |
| REQ-2 | `TaskWorkerTest` | `doWork_should_skipUnknownNetwork_When_providerThrows` | Integration (Robolectric) |
| REQ-3: jitter bounds and platform clamps | `JitterPolicyPropertyTest` | `clamp_should_clampCoercedRawInts_When_IntervalBelowMinAndJitterTooLarge` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `clamp_should_limitJitterToIntervalMinusMinFlexAndReportClamped_When_JitterTooLarge` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `clamp_should_neverGoNegativeOrBelowMinInterval_When_InputsNegativeOrOversized` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `clamp_should_notReportClamped_When_ScheduleWithinCapabilities` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `clamp_should_reproduceTodaysRequest_When_ZeroJitter` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `clamp_should_zeroJitter_When_AdapterDoesNotSupportJitter` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `flexMinutes_should_beNullForZeroOrNegativeJitter` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `nextDelayMs_should_beInIntervalPlusJitterRange_When_AnyInputs` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `offsetMinutes_should_beEqualAndInRange_When_SeedFromStringTwice` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `offsetMinutes_should_stayWithinZeroAndJitterAndBeDeterministic_When_AnySeed` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `offsets_should_spreadAcrossAllBuckets_When_200KeysAndJitter30` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `presetOf_should_roundTripPresetMinutes` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `preset_should_matchPlanValues` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `preset_should_neverExceedInterval_When_AnyInterval` | Unit |
| REQ-3 | `JitterPolicyPropertyTest` | `seedOfKey_should_equalSeedOfKindColonScope` | Unit |
| REQ-3 | `PeriodicSyncDriverJitterTest` | `delay_should_EqualIntervalExactly_When_JitterIsZero` | Unit |
| REQ-3 | `PeriodicSyncDriverJitterTest` | `delay_should_StayWithinIntervalPlusJitter_Across50SeededCycles` | Unit |
| REQ-3 | `TaskKeyTest` | `scheduleCoerceAlwaysValid` | Unit |
| REQ-3 | `TaskKeyTest` | `scheduleHasValueEquality` | Unit |
| REQ-3 | `TaskKeyTest` | `scheduleRejectsInvalidValues` | Unit |
| REQ-3 | `WorkManagerJitterSpikeTest` | `nextScheduleTimeOverride_and_periodicityInfo_should_BeProbed` | Integration (Robolectric) |
| REQ-3 | `WorkManagerJitterSpikeTest` | `observedGap_should_BeRecordedOverSeveralPeriods` | Integration (Robolectric) |
| REQ-3 | `WorkManagerJitterSpikeTest` | `readBack_should_ExposeIntervalFlexInitialDelayAndConstraints` | Integration (Robolectric) |
| REQ-3 | `WorkManagerJitterSpikeTest` | `update_should_RecordBehaviour_When_PeriodicWorkIsRunning` | Integration (Robolectric) |
| REQ-3 | `WorkManagerJitterSpikeTest` | `update_should_RecordWhetherInputDataIsReplaced` | Integration (Robolectric) |
| REQ-3 | `WorkManagerJitterSpikeTest` | `update_should_RecordWhichFieldsChangeAndWhetherIdSurvives` | Integration (Robolectric) |
| REQ-3 | `WorkManagerJitterSpikeTest` | `update_should_RecordWorkerClassNameAndRunHistory_When_ClassChanges` | Integration (Robolectric) |
| REQ-3 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_setFlexFromJitterAndInitialDelayFromSeed_When_JitterConfigured` | Integration (Robolectric) |
| REQ-3 | `WorkManagerTaskSchedulerConstraintsTest` | `describe_should_reportFlexWindowAndUpdateImmediately_When_ApplyHasJitter` | Integration (Robolectric) |
| REQ-4: neutral port, two consumers (git sync, model download) | `BackgroundTaskSchedulerContract` | `runOnce_should_recordUnmeteredOneShotWithoutPeriodicEntry_When_Supported` | Unit |
| REQ-4 | `BackgroundTaskSchedulerContract` | `runOnce_should_replaceOrKeepPendingRequest_By_OneShotConflict` | Unit |
| REQ-4 | `DepthModelDownloaderTest` | `a fresh instance after a restart sees the job that is still queued` | Integration (Robolectric) |
| REQ-4 | `DepthModelDownloaderTest` | `a ready model short-circuits without enqueueing` | Integration (Robolectric) |
| REQ-4 | `DepthModelDownloaderTest` | `a second caller re-attaches to the queued job instead of enqueueing another` | Integration (Robolectric) |
| REQ-4 | `DepthModelDownloaderTest` | `cancel resolves a waiting caller and surfaces Cancelled` | Integration (Robolectric) |
| REQ-4 | `DepthModelDownloaderTest` | `cancel with nothing in flight is a no-op` | Integration (Robolectric) |
| REQ-4 | `GitSyncTaskMappingTest` | `map_should_neverReturnFailed_When_OutcomeIsSkip` | Unit |
| REQ-4 | `GitSyncTaskMappingTest` | `map_should_returnPartialSuccessSkippedOrFailed_When_EachTickOutcomeVariant` | Unit |
| REQ-4 | `GitSyncTaskMappingTest` | `register_should_beNoOp_When_TwoGitSyncTaskInstancesShareTheSpec` | Unit |
| REQ-4 | `GitSyncTaskMappingTest` | `run_should_preferLiveTickAndNotTouchColdPath_When_ServiceRegistered` | Unit |
| REQ-4 | `GitSyncTaskMappingTest` | `run_should_rethrowCancellation_When_TickIsCancelled` | Unit |
| REQ-4 | `GitSyncTaskMappingTest` | `run_should_returnNonRetryableFailed_When_TickThrows` | Unit |
| REQ-4 | `GitSyncTaskMappingTest` | `run_should_runColdPathWithGraphId_When_NoLiveService` | Unit |
| REQ-4 | `GitSyncTaskMappingTest` | `run_should_skipDeliberately_When_NoLiveServiceAndNoColdRunner` | Unit |
| REQ-4 | `GitSyncTaskMappingTest` | `spec_should_useALittleJitterAndBeDisabledByDefault_When_Inspected` | Unit |
| REQ-4 | `GitSyncTaskRegistrationTest` | `register_should_exposeGitSyncSpecOnceAndBeIdempotent_When_calledTwice` | Integration (Robolectric) |
| REQ-4 | `JobsScheduleImportDirectionTest` | `allowedImportsAreNotReported` | Unit |
| REQ-4 | `JobsScheduleImportDirectionTest` | `plantedViolationIsReported` | Unit |
| REQ-4 | `JobsScheduleImportDirectionTest` | `realSourceTreeHasNoViolations` | Unit |
| REQ-4 | `JobsScheduleImportDirectionTest` | `scannerSeesCommonMainSourcesAndSourceSetsAreBundled` | Unit |
| REQ-4 | `ModelDownloadEndToEndTest` | `downloadModel_should_endReady_When_settingsAreEmpty` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadEndToEndTest` | `downloadModel_should_endReady_When_storedEntryHasEnabledFalse` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `cancelled_should_offerDownloadAgain` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `everyNonFinishedState_should_haveAnExit` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `failed_should_offerTryAgainAfterFiveTries` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `failureReason_should_rewriteSkipReasonsOnly` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `notificationsOff_should_addNoticeWithoutChangingTheActions` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `queued_should_notOfferAnyway_When_requestAlreadyAcceptsMetered` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `queued_should_offerDownloadAnywayAndCancel_When_meteredOrUnknownAndUnmeteredRequired` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `queued_should_offerOnlyCancel_When_offline` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `queued_should_sayItRetriesAutomatically_When_aRealAttemptAlreadyFailed` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadPresentationTest` | `running_should_showProgressOrIndeterminateWithCancel` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `downloadModel_should_defaultToUnmetered_When_storeIsEmptyOrUnreadable` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `downloadModel_should_tagRequestWithKindAndTitle_AndRequireStorageNotLow` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `downloadModel_should_useStoredConstraints_NotEnabled` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `register_should_beIdempotentAndSupplyDataSyncForeground_When_calledTwice` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `runOnce_should_keepOrReplace_ByConflictPolicy` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `run_should_beRetryableFailure_When_serverErrors` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `run_should_failTerminally_When_downloadedFileIsTooSmall` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `run_should_putReadyFileInPlaceAndReportProgress_When_serverServesModel` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadTaskTest` | `spec_should_beOneShotWithSharedAttemptBudget_AndLegacyWorkName` | Integration (Robolectric) |
| REQ-4 | `ModelDownloadUiTest` | `everyState_should_keepAnExit` | Compose (Robolectric) |
| REQ-4 | `ModelDownloadUiTest` | `waitingRow_should_offerDownloadNowAnywayAndReplaceWithAnyNetwork_When_networkIsMetered` | Compose (Robolectric) |
| REQ-4 | `TaskKeyTest` | `failedResultIsNotRetryableByDefault` | Unit |
| REQ-4 | `TaskKeyTest` | `portAcceptsRunOnceWithAndWithoutConflictMode` | Unit |
| REQ-4 | `TaskKeyTest` | `registryIsIdempotentForEqualSpecWithDifferentTaskInstance` | Unit |
| REQ-4 | `TaskKeyTest` | `registryLookupOfUnknownKindIsNull` | Unit |
| REQ-4 | `TaskKeyTest` | `registryRejectsConflictingSpec` | Unit |
| REQ-4 | `TaskRegistryBothRootsTest` | `register_should_beIdempotent_When_equalSpecRegisteredFromTwoProcessStarts` | Integration (Robolectric) |
| REQ-4 | `TaskRegistryBothRootsTest` | `registration_should_notThrowAndKeepOneRegistry_When_applicationThenActivityRootsRunTwice` | Integration (Robolectric) |
| REQ-4 | `TaskWorkerTest` | `doWork_should_neverPromoteToForeground_When_periodicRuns` | Integration (Robolectric) |
| REQ-4 | `TaskWorkerTest` | `doWork_should_retry_When_oneShotFailedRetryable` | Integration (Robolectric) |
| REQ-4 | `TaskWorkerTest` | `doWork_should_runBody_When_oneShotEntryExplicitlyDisabled` | Integration (Robolectric) |
| REQ-4 | `TaskWorkerTest` | `doWork_should_runBody_When_oneShotHasNoStoreEntry` | Integration (Robolectric) |
| REQ-4 | `TaskWorkerTest` | `doWork_should_useInputPolicyNotStore_When_oneShotInputAnyOverStoredUnmetered` | Integration (Robolectric) |
| REQ-4 | `TaskWorkerTest` | `runTaskWork_should_promoteToForeground_When_oneShotRuns` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `a failed clone surfaces its error message` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `a failed job carries the error its kind reports` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `a queued depth model download is listed with its kind and title` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `a running job carries the progress its kind reports` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `an enabled periodic graph adds no job row but a one-shot does` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `cancelling a listed job marks it cancelled` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `clearFinished drops finished jobs from the list` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `clone progress is shown as a percentage once the total is known` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `clone progress stays indeterminate before the total is known` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `depth model download reports its percent and error` | Integration (Robolectric) |
| REQ-4 | `WorkManagerJobRegistryTest` | `work without a job kind tag is ignored` | Integration (Robolectric) |
| REQ-4 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_enqueueTaskWorkerWithKindScopeAndPolicy_When_NonGitKindApplied` | Integration (Robolectric) |
| REQ-4 | `WorkManagerTaskSchedulerConstraintsTest` | `runOnce_should_keepOrReplacePendingRequest_By_ConflictPolicy` | Integration (Robolectric) |
| REQ-4 | `WorkManagerTaskSchedulerConstraintsTest` | `runOnce_should_setBackoffOnlyWhenSpecAllowsRetries` | Integration (Robolectric) |
| REQ-5: run log and Late state | `GitLastSuccessSourceTest` | `lastSuccessAtMs_should_countFetchedAndIgnoreErrors_When_StalenessHasBoth` | Unit |
| REQ-5 | `GitLastSuccessSourceTest` | `lastSuccessAtMs_should_returnNullNotLate_When_ReadFails` | Unit |
| REQ-5 | `GitLastSuccessSourceTest` | `lastSuccessAtMs_should_returnNull_When_NothingRecorded` | Unit |
| REQ-5 | `GitLastSuccessSourceTest` | `lastSuccessAtMs_should_takeMaxOfRunLogAndStaleness_When_ForegroundMergeNewer` | Unit |
| REQ-5 | `GitLastSuccessSourceTest` | `lastSuccessAtMs_should_useStaleness_When_RunLogThrows` | Unit |
| REQ-5 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_appendExactlyOneRecord_When_SuccessSkipOrFailure` | Integration (Robolectric) |
| REQ-5 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_keepResultUnchanged_When_RunLogCannotBeBuilt` | Integration (Robolectric) |
| REQ-5 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_keepResultUnchanged_When_RunLogWriteThrows` | Integration (Robolectric) |
| REQ-5 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_logFailedAndReturnFailure_When_FetchFails` | Integration (Robolectric) |
| REQ-5 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_recordConstraintStateReadAtRunStart_When_Run` | Integration (Robolectric) |
| REQ-5 | `LogManagerConcurrencyTest` | `addLog_should_KeepEveryEntry_When_ManyThreadsLogConcurrently` | Integration (jvm) |
| REQ-5 | `LogManagerConcurrencyTest` | `addLog_should_NotThrow_When_SinkRegisteredWhileOthersLog` | Integration (jvm) |
| REQ-5 | `PeriodicSyncDriverRunRecordTest` | `driver_should_BehaveIdentically_When_RecorderThrows` | Unit |
| REQ-5 | `PeriodicSyncDriverRunRecordTest` | `recorder_should_NotBeCalled_When_TickIsSkippedAsBusy` | Unit |
| REQ-5 | `PeriodicSyncDriverRunRecordTest` | `recorder_should_ReceiveOneMappedResultPerTick_ForEachOutcome` | Unit |
| REQ-5 | `PeriodicSyncDriverRunRecordTest` | `runLog_should_HoldForegroundRecordsForTheGitKey_When_UsingTheProductionRecorder` | Unit |
| REQ-5 | `ScheduleHealthTest` | `health_should_attributeDeliberateSkip_When_NewestRunsAreVaultLocked` | Unit |
| REQ-5 | `ScheduleHealthTest` | `health_should_beLate_When_Last3hAgoAndAndroid` | Unit |
| REQ-5 | `ScheduleHealthTest` | `health_should_beOnTime_When_ForegroundMergeIs10MinAgoAndRunLogHasOnlyOldSkips` | Unit |
| REQ-5 | `ScheduleHealthTest` | `health_should_failClosed_When_RunLogOrHealthSourceThrows` | Unit |
| REQ-5 | `ScheduleHealthTest` | `health_should_flagUnreadableSettings_When_FailureNewerThanSuccessAndLastRunIsUnreadableOrAbsent` | Unit |
| REQ-5 | `ScheduleHealthTest` | `health_should_neverBeLate_When_DesktopOrDisabled` | Unit |
| REQ-5 | `ScheduleLatenessTest` | `isRestricted_should_beTrueForStandbyAndFalseForNone_When_ProbeFake` | Unit |
| REQ-5 | `ScheduleLatenessTest` | `lateness_should_returnLate_When_SourceReportsOldSuccess` | Unit |
| REQ-5 | `ScheduleLatenessTest` | `of_should_attributeDeliberateSkip_When_LastRunsVaultLocked` | Unit |
| REQ-5 | `ScheduleLatenessTest` | `of_should_beMonotoneInNowAndNeverNegativeAge_When_AnyInputs` | Unit |
| REQ-5 | `ScheduleLatenessTest` | `of_should_returnLateAfterMultipleOfInterval_When_LastSuccess150MinAgo` | Unit |
| REQ-5 | `ScheduleLatenessTest` | `of_should_returnNotApplicable_When_DisabledOrOneShot` | Unit |
| REQ-5 | `ScheduleLatenessTest` | `of_should_returnOnTime_When_LastSuccessInFutureOrForegroundSourceRecent` | Unit |
| REQ-5 | `ScheduleLatenessTest` | `of_should_returnOnTime_When_NoSuccessEverOrSourceThrows` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `lateStatus_should_neverBeAllowed_When_DesktopCapabilities` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `lateText_should_useMinutesHoursThenDays_When_DurationCrossesThresholds` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `settingsUnreadable_should_holdOnlyWhenFailureIsNewerThanSuccessAndLastRunIsUnreadableOrAbsent` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `skipCopy_should_matchTheSpec_When_RenderedForEachReason` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `statusCopy_should_neverStateAClockTime_When_AnyLineRenders` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `statusLine_should_followPrecedence_PausedThenDisabledThenUnreadableThenLateThenInProgressThenChecked` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `statusLine_should_nameDeliberateSkip_When_LateCauseIsVaultLockedConflictEditingOrBusy` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `statusLine_should_neverShowLate_When_DesktopCapabilitiesOrNoCapabilities` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `statusLine_should_sayAnotherSyncRunning_When_LastSkipBusyAndNoTickInFlight` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `statusLine_should_sayHasntRunInHours_When_LastSuccess3hAgoAndIntervalOneHour` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `statusLine_should_showCheckedNotLate_When_ForegroundSuccessIsRecentAndRunLogHasOnlyOldSkips` | Unit |
| REQ-5 | `ScheduledSyncPresentationTest` | `statusLine_should_showEditingAndConflictSkipsFromTheInAppLoop_When_MostRecentTickWasSkipped` | Unit |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `lateAndHintRows_should_pairTextWithIcon_NotColourAlone` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_callConflictReviewHandlerOnce_When_ConflictPendingSkip` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_callResumeHandlerOnce_When_Paused` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_neverShowLateCopy_When_DesktopCapabilities` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_showBusyCopy_When_LateCauseIsBusySkip` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_showCantReadSettingsWithRetryAndNeverLate_When_UnreadableAndLate` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_showCheckedNotLate_When_ForegroundSuccessIsRecent` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_showEditingAndBusyCopyWithoutAction_When_ThoseSkipsAreTheCause` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_showHasntRunInHours_When_LateOnAndroid` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `section_should_showLockedWithUnlockActionAndNoRestrictedHint_When_LateCauseIsVaultLocked` | Compose (Robolectric) |
| REQ-5 | `ScheduledSyncStatusActionsTest` | `statusLine_should_notReannounce_When_30sRefreshLeavesStateUnchanged` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `logsScreen_should_listRunsAndNotCrash_When_DashboardHasTaskRuns` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `recentResult_should_beReadFailedWhileRecentStaysEmpty_When_SettingsReadThrows` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `row_should_readTaskGraphAndResultWithStartTime_When_RecordIsSkippedMetered` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `rows_should_beCappedAndSaySo_When_MoreRunsThanTheCap` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `rows_should_filterByQuery_When_SearchTextGiven` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `rows_should_neverShowRawIds_When_GraphHasDisplayName` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `rows_should_omitGraphSegment_When_TaskIsAppWide` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `rows_should_showBothSourcesNewestFirst_When_ForegroundAndBackgroundRecordsExist` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `screenReaderText_should_carryTheSameTimeAndLabels_When_RowIsMerged` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `section_should_offerRetryThatRereads_When_RunLogThrows` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `section_should_showNothing_When_RunLogIsEmpty` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `section_should_showUnreadableAndRetry_When_RealSettingsReadThrows` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `time_should_beRelativeTodayOrDated_When_RecordAgeVaries` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogRowsTest` | `time_should_followLocale_When_FormattingClockAndDate` | Compose (Robolectric) |
| REQ-5 | `TaskRunLogTest` | `append_should_loseNoRecord_When_AppendsRace` | Unit |
| REQ-5 | `TaskRunLogTest` | `append_should_neverPersistOrLogCredentials_When_FailureTextCarriesUrlUserinfoAndToken` | Unit |
| REQ-5 | `TaskRunLogTest` | `append_should_persistAndLogResultAndMeteredState_When_Skipped` | Unit |
| REQ-5 | `TaskRunLogTest` | `append_should_swallowNonExceptionThrowable_When_SettingsThrowError` | Unit |
| REQ-5 | `TaskRunLogTest` | `codec_should_roundTrip_When_FreeTextHasSeparators` | Unit |
| REQ-5 | `TaskRunLogTest` | `lastSuccessAtMs_should_ignoreLaterSkipsAndFailures_When_OlderSuccessExists` | Unit |
| REQ-5 | `TaskRunLogTest` | `recent_should_capMergedListAt20_When_BothSourcesFull` | Unit |
| REQ-5 | `TaskRunLogTest` | `recent_should_mergeBothSources_When_InterleavedWriters` | Unit |
| REQ-5 | `TaskRunLogTest` | `recent_should_returnEmptyAndAppendNotThrow_When_SettingsThrow` | Unit |
| REQ-5 | `TaskRunLogTest` | `taskApplyLine_should_carryEffectiveScheduleAndNetwork_When_Enabled` | Unit |
| REQ-5 | `TaskSchedulerAndroidEntryPointTest` | `wiredApp_should_recordRunLogEntry_When_ScheduledWorkerFires` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_failWithoutRetry_When_failedNotRetryable` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_neverRetry_When_periodicFailedEvenIfRetryable` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_recordCancelledRun_When_bodyThrowsCancellationException` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_recordFailedAndReturnFailure_When_bodyThrowsOutOfMemoryError` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_redactCredentialsFromOutputAndRecord_When_bodyThrowsWithSecretInMessage` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_rethrow_When_bodyThrowsCancellationException` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_skipCloneRunning_When_preRunYields` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_skipSettingsUnreadableAndSetHealth_When_periodicReadFails` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_succeedWithNotRegisteredRecord_When_registryEmpty` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `doWork_should_truncateErrorToWorkManagerDataLimit_When_failureMessageIsHuge` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `recordRun_should_leaveResultUnchangedAndLog_When_runLogThrowsThrowable` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `recordRun_should_leaveResultUnchanged_When_realSettingsWriteThrows` | Integration (Robolectric) |
| REQ-5 | `TaskWorkerTest` | `recordRun_should_swallowThrowableButRethrowCancellation` | Integration (Robolectric) |
| REQ-6: existing git-sync behaviour preserved | `GitSyncSchedulingEntryPointTest` | `androidStack_should_EnqueueNothing_When_FlagUnset` | Integration (Robolectric) |
| REQ-6 | `GitSyncSchedulingEntryPointTest` | `androidStack_should_EnqueueOneUniquePeriodic15MinWork_When_FlagOnInterval5_AndCancelWhenOff` | Integration (Robolectric) |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a SAF graph, or one whose predicate throws, gets exactly one fetch` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a busy counter, a locked vault and being offline each skip with no repository call` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a confirmation stored by an earlier build is read unchanged and the next tick runs` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a failed sync backs off the next ticks and the delay doubles` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a fetch error while skipping for repo state is reported as a failure` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a fresh service over a MERGING repository skips with only a fetch` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a held EditLock skips the tick with no repository call` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a merged sync records lastMerged and zero behind while a fetch-only tick records only the fetch` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a missing git config falls back to a harmless fetch tick` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a pending first-sync review blocks even a matching confirmation` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a review that becomes pending between the policy check and the sync is a first-sync skip` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a scheduled run can never lift the mass-change guard` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `a vault provider that throws counts as available, like sync` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `all clear runs sync once and merges the remote commits` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `an app-closed marker left by the worker is cleared when the service starts` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `an in-memory conflict skips further ticks without touching the repository` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `an unconfirmed first sync fetches only and never merges commits or pushes` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `autoCommit off with a clean tree still syncs` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `autoCommit off with a dirty tree fetches only and commits nothing` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `changing the branch re-arms the first sync gate` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `going offline between the policy check and the sync is a skip, not a failure` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `the default path predicate treats saf and content uris as not app-owned` | Unit |
| REQ-6 | `GitSyncServiceScheduledSyncTest` | `the review badge covers every unconfirmed config, not only a pending repair` | Unit |
| REQ-6 | `GitSyncStackEntryPointTest` | `gitSyncStack_should_ExposeServiceWithScheduledStatus_When_Composed` | Integration (jvm) |
| REQ-6 | `GitSyncStackEntryPointTest` | `gitSyncStack_should_LaunchNothing_When_IntervalZero` | Integration (jvm) |
| REQ-6 | `GitSyncStackEntryPointTest` | `gitSyncStack_should_LogRedactedWarnAndRunNextTick_When_FetchThrows` | Integration (jvm) |
| REQ-6 | `GitSyncStackEntryPointTest` | `gitSyncStack_should_RecordImmediateFetchThenThreeIntervalFetchesAndSyncTickLines_When_Flag15MinAnd45MinutesPass` | Integration (jvm) |
| REQ-6 | `GitSyncStackEntryPointTest` | `gitSyncStack_should_RestartOnceAtNewInterval_When_PollIntervalMinutesEdited` | Integration (jvm) |
| REQ-6 | `GitSyncStackEntryPointTest` | `gitSyncStack_should_SkipTickWithBusyAndMakeNoGitCall_When_ManualSyncRunning` | Integration (jvm) |
| REQ-6 | `GitSyncStackEntryPointTest` | `gitSyncStack_should_StartNoLoop_When_FlagUnset` | Integration (jvm) |
| REQ-6 | `GitSyncStackEntryPointTest` | `gitSyncStack_should_StopTicking_When_CompositionDisposed` | Integration (jvm) |
| REQ-6 | `GitSyncTaskRunnerPolicyTest` | `run_should_redactCredentials_When_BodyThrowsWithSecretInMessage` | Unit |
| REQ-6 | `GitSyncTaskRunnerPolicyTest` | `run_should_returnSkippedVaultLockedAndMakeNoGitCall_When_PolicyVaultLocked` | Unit |
| REQ-6 | `GitSyncTaskRunnerPolicyTest` | `toTaskRunResult_should_redactCredentials_When_FailureMessageEmbedsRemoteUrlAndToken` | Unit |
| REQ-6 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_runPreRunGateYieldBodyPostRunInOrder_When_CloneRunning` | Integration (Robolectric) |
| REQ-6 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_runWatchdogButSkipPostRun_When_NetworkGateSkips` | Integration (Robolectric) |
| REQ-6 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_runWatchdogThenBodyThenPostRun_When_FetchSucceeds` | Integration (Robolectric) |
| REQ-6 | `GitSyncWorkerGateAndRecordTest` | `doWork_should_stayRecordedSuccessAndSucceed_When_PostRunThrowsAfterGoodFetch` | Integration (Robolectric) |
| REQ-6 | `PeriodicSyncDriverTest` | `a fetch-only tick records lastFetchedAt and never moves lastSuccessAt` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `driver should log a redacted warning and run the next tick when a tick throws` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `driver should log one SyncTick line per tick with graph decision outcome and last success` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `driver should pause and expose paused status when three consecutive ticks fail` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `driver should propagate cancellation and stop ticking when a tick is cancelled` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `driver should report wall-clock last success and next time when a tick succeeds` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `driver should run an immediate first tick then every interval when started with immediateFirstTick` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `driver should skip with Busy without calling the tick when a manual sync is running` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `driver should wait a full interval for the first tick when not immediate` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `outcomes should be sealed types and status should be a StateFlow of ScheduledSyncStatus` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `resume should clear the pause and restart the loop when its one tick succeeds` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `resume should stay paused with the new cause when its one tick fails` | Unit |
| REQ-6 | `PeriodicSyncDriverTest` | `stop should end the loop and leave no further ticks` | Unit |
| REQ-6 | `ScheduledSyncOriginE2ETest` | `scheduledTick_should_OnlyFetch_When_FirstSyncNotConfirmed` | Integration (jvm) |
| REQ-6 | `ScheduledSyncOriginE2ETest` | `scheduledTick_should_PullTwoRemoteCommitsIntoTheWorkingTree_When_FirstSyncConfirmedAndEditorIdle` | Integration (jvm) |
| REQ-6 | `ScheduledSyncPolicyTest` | `a graph that is not app-owned is fetch-only` | Unit |
| REQ-6 | `ScheduledSyncPolicyTest` | `all clear runs a full sync` | Unit |
| REQ-6 | `ScheduledSyncPolicyTest` | `backoff doubles from one minute and is capped at thirty` | Unit |
| REQ-6 | `ScheduledSyncPolicyTest` | `each blocking input maps to its skip reason` | Unit |
| REQ-6 | `ScheduledSyncPolicyTest` | `never runs while any input blocks, across every combination` | Unit |
| REQ-6 | `ScheduledSyncPolicyTest` | `no-git skips never come back from an input set that is only blocked by repo state or consent` | Unit |
| REQ-6 | `ScheduledSyncPolicyTest` | `only repo state and first sync confirmation still fetch` | Unit |
| REQ-6 | `ScheduledSyncPolicyTest` | `the closed-app runner is fetch-only` | Unit |
| REQ-6 | `ScheduledSyncStackE2ETest` | `a tick with the remote two commits ahead, an idle editor and a confirmed first sync merges both` | Unit |
| REQ-6 | `ScheduledSyncStackE2ETest` | `an editor that is mid-edit makes the tick skip with no repository call` | Unit |
| REQ-6 | `ScheduledSyncStackE2ETest` | `an unconfirmed first sync keeps ticks fetch-only until a manual sync confirms it` | Unit |
| REQ-6 | `ScheduledSyncStackE2ETest` | `with autoCommit on a dirty tree is committed before the merge` | Unit |
| REQ-6 | `ScheduledSyncStatusTest` | `effective interval should be at least 15 and disable 5 when Android` | Unit |
| REQ-6 | `ScheduledSyncStatusTest` | `every option should be selectable and nothing clamped when desktop` | Unit |
| REQ-6 | `ScheduledSyncStatusTest` | `line should be not scheduled when off and stopping only after the delay while a tick runs` | Unit |
| REQ-6 | `ScheduledSyncStatusTest` | `line should read last synced 3 min ago and next in 12 when a tick succeeded 3 minutes ago at 15` | Unit |
| REQ-6 | `ScheduledSyncStatusTest` | `line should report busy offline paused and never synced` | Unit |
| REQ-6 | `ScheduledSyncStatusTest` | `line should say checked, never synced, when only fetches have run` | Unit |
| REQ-6 | `ScheduledSyncStatusTest` | `loopInterval_staysOff_when_configuredIsNotPositive_and_isRaisedToTheFloorOtherwise` | Unit |
| REQ-6 | `ScheduledSyncStatusTest` | `scheduled sync flag key should stay stable` | Unit |
| REQ-6 | `TaskWorkerTest` | `doWork_should_runHooksInOrder_When_periodicKindHasHooks` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerPauseTest` | `pauseFor_should_CancelPeriodicJobBeforeCopyBegins_When_RelocateStarts` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerPauseTest` | `pauseFor_should_NotAffectAnotherGraphsScheduledJob_When_OnlyOneGraphIsRelocating` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerPauseTest` | `resumeFor_should_RescheduleThePeriodicJob_When_SwitchGraphCompletes` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerRetryOwnerTest` | `GitSyncWorker doWork() fast path returns Result failure(), not Result retry(), when fetchOnly() returns Left(RetryExhausted)` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerRetryOwnerTest` | `a GitSyncWorker built via TestListenableWorkerBuilder against a retry-exhausting StubGitRepository returns Result failure() and is not re-enqueued by WorkManager's own retry mechanism` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerRetryOwnerTest` | `fast path runs the policy-gated scheduled tick, not a bare fetchOnly` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerRetryOwnerTest` | `slow path hands the single fetch to the cold runner and records the behind count` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerRetryOwnerTest` | `slow path records a typed fetch error as the outcome and fails` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerRetryOwnerTest` | `slow path returns Result failure(), not retry(), when fetch() returns Left(RetryExhausted)` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerRetryOwnerTest` | `slow path returns success when fetch() succeeds, and success when no config row exists` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerSlowPathYieldTest` | `slow path proceeds with its standalone fetch when no GitCloneWorker is RUNNING for graphId` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerSlowPathYieldTest` | `slow path yields when a GitCloneWorker WorkInfo is RUNNING for graphId` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerUniqueWorkTest` | `AndroidGitCloneWorkerLauncher computes the same workNameFor(graphId) as WorkManagerSyncScheduler's periodic job` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerUniqueWorkTest` | `GitCloneWorker appended under the same workNameFor(graphId) as a periodic GitSyncWorker via APPEND_OR_REPLACE is still chained BLOCKED - the hazard the launcher avoids` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerUniqueWorkTest` | `GitCloneWorker enqueued via beginUniqueWork(APPEND_OR_REPLACE) for a graph with no existing periodic job still runs immediately` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerUniqueWorkTest` | `launchClone over an existing periodic job reaches SUCCEEDED instead of being left BLOCKED, and restores the periodic job` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerWatchdogTest` | `isGitCloneWorkerStuck does not fire for a RUNNING WorkInfo still within the threshold` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerWatchdogTest` | `isGitCloneWorkerStuck does not fire for a non-RUNNING state regardless of elapsed time` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerWatchdogTest` | `isGitCloneWorkerStuck does not fire when no start time was tracked` | Integration (Robolectric) |
| REQ-6 | `WorkManagerSyncSchedulerWatchdogTest` | `isGitCloneWorkerStuck fires for a RUNNING WorkInfo whose start time is more than the threshold before now` | Integration (Robolectric) |
| REQ-7: Android adapter: names, pause/resume, reconcile, removal, startup | `BackgroundTaskSchedulerContract` | `pauseResume_should_restoreAppliedInterval_When_NoStoreEntryExists` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `pauseResume_should_restoreInProcess_When_SettingsSilentlyNoOp` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `pauseResume_should_restoreSeededExternalWork_When_NoLedgerOrStoreEntry` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `pause_should_stillCancelAndResumeFromMemoryCopy_When_SnapshotWriteThrows` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `resume_should_enqueueNothingAndNotThrow_When_NothingWasPausedOrStored` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `resume_should_enqueueNothing_When_DisabledWhilePaused` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `resume_should_fallBackToLastApplied_When_SnapshotWriteThrowsAndMemoryCopyAbsent` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `resume_should_notResurrect_When_StoreEntryExplicitlyDisabled` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `resume_should_notResurrect_When_StoreHoldsTombstone` | Unit |
| REQ-7 | `BackgroundTaskSchedulerContract` | `resume_should_preferSnapshot_When_StoreHoldsEnabledEntry` | Unit |
| REQ-7 | `GraphManagerRemovalHookTest` | `removeGraph_should_InvokeHookAfterRegistrySaved_When_RemovalSucceeds` | Unit |
| REQ-7 | `GraphManagerRemovalHookTest` | `removeGraph_should_NotInvokeHook_When_ActiveGraphIsNotTheLastRealGraph` | Unit |
| REQ-7 | `GraphManagerRemovalHookTest` | `removeGraph_should_NotInvokeHook_When_GraphIsDemo` | Unit |
| REQ-7 | `GraphManagerRemovalHookTest` | `removeGraph_should_NotInvokeHook_When_GraphNotFound` | Unit |
| REQ-7 | `GraphManagerRemovalHookTest` | `removeGraph_should_StillReturnTrueAndKeepRegistrySaved_When_HookThrows` | Unit |
| REQ-7 | `GraphRegistryKnownScopesTest` | `read_should_excludeDemoGraph_When_mixedWithRealGraph` | Unit |
| REQ-7 | `GraphRegistryKnownScopesTest` | `read_should_returnKnownIds_When_registryHasRealGraphs` | Unit |
| REQ-7 | `GraphRegistryKnownScopesTest` | `read_should_returnUnknownWithoutThrowing_When_settingsReadFails` | Unit |
| REQ-7 | `GraphRegistryKnownScopesTest` | `read_should_returnUnknown_When_registryBlankEmptyDemoOnlyOrCorrupt` | Unit |
| REQ-7 | `GraphRemovalTasksTest` | `cleanup_should_runWork_When_openAndDoNothing_When_closed` | Unit |
| REQ-7 | `GraphRemovalTasksTest` | `onRemoved_should_cancelAllThenTombstonePeriodicKindsOnly_When_GraphRemoved` | Unit |
| REQ-7 | `GraphRemovalTasksTest` | `onRemoved_should_stillTombstone_When_CancelAllThrows` | Unit |
| REQ-7 | `GraphRemovalTasksTest` | `onRemoved_should_tombstoneRemainingKinds_When_OneTombstoneThrows` | Unit |
| REQ-7 | `LegacyWorkUpgradeTest` | `apply_should_leaveJobEnqueued_When_ConvertingPreUpgradeJob` | Integration (Robolectric) |
| REQ-7 | `LegacyWorkUpgradeTest` | `apply_should_replaceStaleInputInterval_When_StoredIntervalDiffers` | Integration (Robolectric) |
| REQ-7 | `LegacyWorkUpgradeTest` | `apply_should_updateInPlaceKeepingIdPeriodicAndWorkerClass_When_PreUpgradeJobExists` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `reapplyAll_should_enqueueFromLedger_When_ForceStopWipedWorkManager` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `reapplyAll_should_leaveLiveWorkUntouched_When_AlreadyScheduled` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `reapplyAll_should_neverCallSeedOrOpenGraphDb_When_LedgerHasEntries` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `run_should_cancelOrphansViaReconciler_When_KnownScopesKnown` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `run_should_doNothing_When_WorkManagerNotReady` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `run_should_enqueueNothingAndNotCrash_When_KnownScopesUnknownOrSettingsThrow` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `run_should_resumeStalePausedSnapshot_When_NoCloneWorkIsLive` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `run_should_runReconcileFailClosed_When_ReconcileThrows` | Integration (Robolectric) |
| REQ-7 | `ScheduleStartupTest` | `run_should_stayPaused_When_CloneWorkIsLive` | Integration (Robolectric) |
| REQ-7 | `ScheduledTaskReconcilerTest` | `reconcile_should_cancelNothingAndWriteNoTombstone_When_Unknown` | Integration (Robolectric) |
| REQ-7 | `ScheduledTaskReconcilerTest` | `reconcile_should_cancelNothing_When_KnownIsEmptyOrSourceThrows` | Integration (Robolectric) |
| REQ-7 | `ScheduledTaskReconcilerTest` | `reconcile_should_cancelOnlyAbsentScopes_When_KnownNonEmpty` | Integration (Robolectric) |
| REQ-7 | `ScheduledTaskReconcilerTest` | `reconcile_should_neverCancelAppWideScope_When_ScopeEmpty` | Integration (Robolectric) |
| REQ-7 | `TaskKeyTest` | `fnvHashMatchesKnownVectors` | Unit |
| REQ-7 | `TaskKeyTest` | `gitSyncKeepsLegacyWorkName` | Unit |
| REQ-7 | `TaskKeyTest` | `hashedWorkNamesUseSafeCharsetAndSettingsKeysAreInjectiveForAnyScope` | Unit |
| REQ-7 | `TaskKeyTest` | `hexLookingScopeNeverCollidesWithHashedScope` | Unit |
| REQ-7 | `TaskKeyTest` | `keysAreInjectiveAcrossKindScopeSplit` | Unit |
| REQ-7 | `TaskKeyTest` | `kindCharsetIsRestricted` | Unit |
| REQ-7 | `TaskKeyTest` | `modelDownloadKeepsLegacyWorkName` | Unit |
| REQ-7 | `TaskKeyTest` | `otherKindsUseHashedScope` | Unit |
| REQ-7 | `TaskKeyTest` | `settingsKeyUsesLengthPrefix` | Unit |
| REQ-7 | `TaskKeyTest` | `specialScopesStayDistinct` | Unit |
| REQ-7 | `TaskWorkerTest` | `doWork_should_cancelOwnWorkAndSucceed_When_scopeAbsentFromKnownRegistry` | Integration (Robolectric) |
| REQ-7 | `TaskWorkerTest` | `doWork_should_neverCancelAppWideScope_When_knownScopesKnown` | Integration (Robolectric) |
| REQ-7 | `TaskWorkerTest` | `doWork_should_runTask_When_knownScopesUnknownOrThrowing` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_keepWorkIdAndName_When_IntervalChanges` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_removeWorkAndClearLedger_When_Disabled` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerConstraintsTest` | `apply_should_skipWithoutCancelling_When_NonPeriodicWorkHoldsTheName` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerConstraintsTest` | `tags_should_excludeStelekitJobOnPeriodicAndIncludeOnOneShot_When_RequestsBuilt` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerConstraintsTest` | `workName_should_matchLegacyNames_When_GitSyncAndModelDownload` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `apply_should_clearSnapshot_When_DisabledDuringPause` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `apply_should_updateSnapshotAndEnqueueNothing_When_PausedThenResumeRestoresNewConfig` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `pause_should_cancelAndResumeFromMemory_When_SnapshotWriteThrows` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `pause_should_cancelOnlyNamedGraph` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `pause_should_notOverwriteSnapshot_When_SecondPauseFindsNonPeriodicWork` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_consumeSnapshot_When_Restored` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_enqueueFromStore_When_NoSnapshotAndNoLedger` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_enqueueNothing_When_StoreSaysDisabledEvenWithSnapshot` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_keepRemainingInitialDelayAndConstraints_When_JitteredWorkWasPaused` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_reenqueueFromLiveWorkInfo_When_NoLedgerOrStoreEntry_PauseTestScenario` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_restoreAppliedInterval_When_PausedAfterApply` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_restoreDirectlyEnqueuedGitSyncWorkAsEnqueuedGitSyncWorker_UniqueWorkScenario` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_restorePreUpgradeJobWithOriginalIntervalAndConstraints_When_UnmigratedAndUnrecorded` | Integration (Robolectric) |
| REQ-7 | `WorkManagerTaskSchedulerPauseResumeTest` | `resume_should_restoreSnapshot_When_StoreEntryEnabled` | Integration (Robolectric) |
| REQ-8: Desktop in-process and Noop adapters | `DesktopTaskRootWiringTest` | `desktopRoot_should_ListGitSyncInBackgroundTasksWithDefaultJitter_When_RegistryBuiltByRootFactory` | Integration (jvm) |
| REQ-8 | `DesktopTaskRootWiringTest` | `desktopRoot_should_MakeSchedulerRunLogVisibleThroughTheUiSettingsInstance_When_OneInstanceIsShared` | Integration (jvm) |
| REQ-8 | `DesktopTaskRootWiringTest` | `settingsEdit_should_ChangeRunningLoopScheduleAndEnabledState_When_PanelControllerSaves` | Integration (jvm) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `apply_should_logTaskApplyLineWithEffectiveSchedule_When_Enabled` | Integration (jvm) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `runOnce_should_runTaskAndClearPending_When_Executed` | Integration (jvm) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `stop_should_endTicking_When_Called` | Integration (jvm) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `tick_should_keepLooping_When_JobTrackerThrows` | Integration (jvm) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `tick_should_keepLooping_When_RunLogAppendThrows` | Integration (jvm) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `tick_should_logFailureAndKeepLooping_When_TaskThrowsOutOfMemoryOnce` | Integration (jvm) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `tick_should_runWithJitteredGapsInWindow_When_ThreeIntervalsElapse` | Integration (jvm) |
| REQ-8 | `InProcessTaskSchedulerContractTest` | `tick_should_stopLoop_When_TaskThrowsCancellation` | Integration (jvm) |
| REQ-8 | `NoopTaskSchedulerTest` | `apply_should_scheduleNothing_When_EnabledConfigApplied` | Unit |
| REQ-8 | `NoopTaskSchedulerTest` | `capabilities_should_beAllFalse_When_Constructed` | Unit |
| REQ-8 | `TaskSchedulerAndroidEntryPointTest` | `androidRoot_should_EnqueueUniquePeriodicWorkWithStoredConstraints_When_GitSyncStackComposes` | Integration (Robolectric) |
| REQ-8 | `TaskSchedulerDesktopEntryPointTest` | `desktopOpen_should_MigrateLegacyFlagAndStartLoopAtLegacyInterval_When_NoEntryExists` | Integration (jvm) |
| REQ-8 | `TaskSchedulerDesktopEntryPointTest` | `desktopRoot_should_NeverCallApplyOrRegisterGitSync_When_GitSyncEnabled` | Integration (jvm) |
| REQ-8 | `TaskSchedulerDesktopEntryPointTest` | `desktopRoot_should_RecordForegroundRunInTheSharedRunLog_When_InProcessLoopTicks` | Integration (jvm) |
| REQ-8 | `TaskSchedulerDesktopEntryPointTest` | `inProcessScheduler_should_HaveNoGitSyncWork_When_DesktopLoopRuns` | Integration (jvm) |
| REQ-9: legacy-flag migration | `AndroidGitLegacyConfigSourceTest` | `read_should_beUnavailable_When_DatabaseFileDoesNotExist` | Integration (Robolectric) |
| REQ-9 | `AndroidGitLegacyConfigSourceTest` | `read_should_beUnavailable_When_TheReadThrows` | Integration (Robolectric) |
| REQ-9 | `AndroidGitLegacyConfigSourceTest` | `read_should_beUnavailable_When_TheRealDatabasePathIsUnopenable` | Integration (Robolectric) |
| REQ-9 | `AndroidGitLegacyConfigSourceTest` | `read_should_findNoRemote_When_TheDatabaseHasNoGitConfigRow` | Integration (Robolectric) |
| REQ-9 | `AndroidGitLegacyConfigSourceTest` | `read_should_findRemoteAndInterval_When_TheDatabaseHasAGitConfigRow` | Integration (Robolectric) |
| REQ-9 | `ConfigRepositoryLegacyConfigSourceTest` | `read_should_ReturnFoundWithRemoteAndInterval_When_ConfigExists` | Unit |
| REQ-9 | `ConfigRepositoryLegacyConfigSourceTest` | `read_should_ReturnFoundWithoutRemote_When_RepositoryHasNoConfig` | Unit |
| REQ-9 | `ConfigRepositoryLegacyConfigSourceTest` | `read_should_ReturnUnavailableNotNoRemote_When_RepositoryReturnsError` | Unit |
| REQ-9 | `ConfigRepositoryLegacyConfigSourceTest` | `read_should_ReturnUnavailable_When_RepositoryThrows` | Unit |
| REQ-9 | `GitSyncScheduleControllerTest` | `ensureMigrated_should_NotSeed_When_GraphWasTombstoned` | Unit |
| REQ-9 | `GitSyncScheduleControllerTest` | `ensureMigrated_should_SeedFromLegacyFlagRemoteAndInterval_When_NoEntryExists` | Unit |
| REQ-9 | `LegacyGitSyncMigrationTest` | `seed_should_returnEnabledLegacyInterval_When_FlagOnAndRemotePresent` | Unit |
| REQ-9 | `LegacyGitSyncMigrationTest` | `seed_should_returnNull_When_NoRemoteOrFlagOffOrOtherKind` | Unit |
| REQ-9 | `LegacyGitSyncMigrationTest` | `seed_should_throwSeedUnavailable_When_SourceCannotRead` | Unit |
| REQ-9 | `PlatformSettingsColdPathTest` | `putString_should_RoundTripAcrossInstances_When_OnlyContextInitHasRun_FallbackPathOnly` | Integration (Robolectric) |
| REQ-9 | `ScheduleStartupTest` | `run_should_migrateActiveGraphOnlyAndChangeNothingOnSecondStart_When_LegacyFlagTrue` | Integration (Robolectric) |
| REQ-9 | `ScheduledSyncControlsApplyTest` | `setEnabledFalse_should_PersistDisabledToTheGraphStoreEntry_AndLeaveLegacyFlagAlone` | Compose (Robolectric) |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_beDisabledAndNeverSeed_When_KeyTombstoned` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_beDisabledAndWriteMarker_When_GraphHasNoRemote` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_beDisabled_When_LegacyFlagUnset` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_keepUserChoice_When_MigratedThenDisabledByUser` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_keepZeroJitterAndLegacyInterval_When_MigratedFromLegacyFlag` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_leaveLegacyKeysUnchanged_When_MigrationRuns` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_migrateToEnabled_When_RemoteBearingGraphWasNeverComposed` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_neverCallSeed_When_EntryOrMarkerExists` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_notWriteMarker_When_LegacyFlagReadThrows` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `read_should_notWriteMarker_When_SourceUnavailableOrThrowing` | Unit |
| REQ-9 | `TaskSettingsStoreMigrationTest` | `write_should_supersedeTombstone_When_GraphReAddedAtSamePath` | Unit |
| REQ-10: Settings UI | `BackgroundTasksSectionTest` | `defaultRow_should_showAppliesToNGraphsAndWriteDefaultsNotAScope_When_Edited` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `drillIn_should_moveFocusToTitleAndBackRestoreOpener_When_S1ToKindToGraph` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `enableSync_should_takeAtMostThreeTaps_When_StartingFromSettings` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `graphRow_should_exposeOneMergedNodeWithExactDescription_When_SemanticsRead` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `list_should_showEmptyStateAddGraphAndSearchOnlyFromEightGraphs_When_GraphCountVaries` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `modelDownloadRow_should_showOnlyNetworkChoice_When_OneShotKind` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `search_should_beAbsentAtSevenAndPresentAtEightGraphs_When_KindOpened` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `search_should_haveLabelAndShowNoMatchesWithClear_When_QueryMatchesNothing` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `section_should_beHiddenOnNoopAndShownOnDesktop_When_SchedulesWorkVaries_Desktop` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksSectionTest` | `section_should_beHiddenOnNoopAndShownOnDesktop_When_SchedulesWorkVaries_Noop` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksUxGapsTest` | `graphPanel_should_omitFetchOnlyLine_When_GraphIsNotSafBacked` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksUxGapsTest` | `graphPanel_should_sayOpenGraphToSeeStatus_When_GraphIsNotActive` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksUxGapsTest` | `graphPanel_should_showFetchOnlyLine_When_GraphIsSafBacked` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksUxGapsTest` | `graphPanel_should_showGateReasonNotOpenGraphCopy_When_ActiveGraphHasGate` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksUxGapsTest` | `kindSummary_should_showCouldntReadSettingsAndRetry_When_ReadFails` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksUxGapsTest` | `legacyIntervalPicker_should_moveSelectionWithArrowKeys_When_RadioFocused` | Compose (Robolectric) |
| REQ-10 | `BackgroundTasksUxGapsTest` | `navigation_should_useAutoMirroredIconsNotLiteralArrows_When_ListAndDrillInShown` | Compose (Robolectric) |
| REQ-10 | `DepthEstimationPanelLiveRegionTest` | `downloadStates_should_beInPoliteLiveRegion_When_StateChanges` | Compose (Robolectric) |
| REQ-10 | `DepthEstimationPanelLiveRegionTest` | `idleModel_should_notAnnounce_When_NoDownloadInFlight` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncPresentationTest` | `clampSummary_should_sayRunsEvery15_When_RequestedBelowFloor` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `customSummary_should_countExactlyTheDifferingDimensions_When_AllCombinationsTried` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `customSummary_should_listOnlyDifferingValues_When_OneTwoOrThreeDiffer` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `firstCheck_should_useAboutAndNeverClockTime_When_Rendered` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `graphRowDescription_should_carryFullUntruncatedText_When_OnOrOff` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `graphRowSummary_should_sayUsingDefaultOnlyWithoutOverrides_When_RowsBuilt` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `intervalPhrases_should_readNaturally_When_MinutesHoursAndDays` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `intervalPresets_should_disableFiveMinutes_When_CapabilityMinIs15` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `intervalPresets_should_includeFiveMinutes_When_DesktopCapabilities` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `jitterMinutes_should_recomputeFromPreset_When_IntervalChangesFrom60To120` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `kindSummary_should_beOneLine_When_ManyGraphs` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `networkLabels_should_beExactAndNeverContainWifiOnly_When_AllLocalizedStringsScanned` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `presetOf_should_roundTripMinutesFor_When_AnyPresetAndIntervalListed` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `scheduleForSpread_should_mapPresetsToPercentOfInterval_When_IntervalIs60` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `spreadSummary_should_deriveFromDescribeGapFields_When_DesktopOrWorkManagerAdapter` | Unit |
| REQ-10 | `ScheduledSyncPresentationTest` | `unmetered_should_beSelectableOnlyWhereSupported_When_CapabilitiesVary` | Unit |
| REQ-10 | `ScheduledSyncRadioKeyboardTest` | `arrowKeys_should_moveSelectionSkippingDisabled_When_DesktopRadioGroupFocused` | Integration (jvm) |
| REQ-10 | `ScheduledSyncSettingsTest` | `changeIntervalAndNetwork_should_takeAtMostFourTapsWithoutLeavingScreen_When_FromSettings` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `control_should_revertAndShowCouldntSave_When_WriteOrApplyThrows` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `control_should_revertStoredEntryAndShowCouldntSave_When_ApplyThrows` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `defaultsPanel_should_writeKindDefaultsNeverAScope_When_DefaultRowChanged` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `disabledControl_should_exposeReasonInSemantics_When_UnmeteredOnDesktop` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `enable_should_announceFirstCheckInAboutNMin_When_SwitchTurnedOn` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `fiveMinute_should_beAbsentWithAndroid15MinHelperLine_When_MinIntervalIs15` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `jitter_should_appearOnlyUnderAdvancedWithOffALittleALotLabels_When_SectionRendered` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `labelAndStatus_should_SaySyncedNowThatScheduledTicksPull_And_NeverSayChecked_UW17` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `pausedLine_should_ShowDecorativePauseIconBesideText_When_StatusPaused` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `picker_should_NotListFiveAndShowHelperAndRunsEvery15_When_AndroidAndStoredBelow15` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `picker_should_ShowExactly15_When_Android15` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `picker_should_ShowExactly30_When_Android30` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `picker_should_ShowExactly60_When_Android60` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `resetToDefault_should_clearOverridesKeepEnabledAndApplyOnce_When_CustomRowTapped` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `resetToDefault_should_keepOverridesAndShowCouldntSave_When_WriteThrows` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `resume_should_BeVisibleWhilePausedAndRunOneCheck_When_Tapped` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `row_should_ShowPausedAfter3FailuresRouteToFixAndShowResume_When_StatusPaused` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `rows_should_be48dpAndSingleFocusStop_When_RadiosAndSwitchesRendered` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `rows_should_wrapAt200PercentFontAndMirrorUnderRtl_When_Rendered` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `scheduledSyncSection_should_DisableSwitchWithReasonAndReviewButton_When_FirstSyncNotReviewed` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `scheduledSyncSection_should_DisableSwitchWithReasonAndWizardButton_When_NoRemote` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `scheduledSyncSection_should_KeepAnAlreadyOnSwitchUsable_When_FirstSyncNotReviewed` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `scheduledSyncSection_should_RenderNoReviewFirstSyncRow_When_U2InterimFetchOnly` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `scheduledSyncSection_should_RenderNoReviewRow_When_FirstSyncConfirmed` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `scheduledSyncSection_should_ShowSwitchOffAndRadioGroup5_15_30_60_When_RemoteConfigured` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `scheduledSync_should_EnableAndPickIntervalInThreeTapsWithNoTextInput_When_RemoteConfigured` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `section_should_ExposeOneToggleNodeRadioGroupAnd48dpTargets_And_BeKeyboardOperable` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `section_should_ShowAndroidHelper_When_ClosedAppChecksAvailable` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `section_should_ShowPlatformHelperText_When_DesktopOrAndroid` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `section_should_showCouldntReadSettingsWithRetryAndNeverApply_When_ReadFailsClosed` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `section_should_showSkeletonDisabledControlsAndLoadingReason_When_ReadPending` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `section_should_showSwitchIntervalsNetworkAndAdvanced_When_RenderedForWorkNotes` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `selectUnmetered_should_persistAndApplyOnce_When_UserPicksUnmetered` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `status_should_ClearRestrictedLine_When_RestrictionEnds` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `status_should_NeverShowRestrictedLine_When_Desktop` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `status_should_ReadLastChecked3MinNextIn12Min_And_NotTakeFocus_When_TickSucceeded` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `status_should_ReadOfflineWillResume_When_Offline` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `status_should_ShowBackgroundRestrictedLineWithoutErrorBadge_When_ProbeReportsOnAndroid_And_NeverOnDesktop` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `stopping_should_ShowOnlyAfterTwoSeconds_When_SwitchOffWhileTickRunning` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `summary_should_showClampAndSpreadWindow_When_AndroidFiveMinOrJitterSet` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `summary_should_useAdapterGapFieldsAndClamp_When_WorkManagerLikeAdapter` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `switchOff_should_ReadNotScheduled_When_Toggled` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `switch_should_ShowOn_When_EnabledState` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `switch_should_turnOffInOneTap_When_AlreadyOn` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `tick_should_BeSkippedShowAnotherSyncRunningAndNeverQueued_When_PreviousTickRunning` | Compose (Robolectric) |
| REQ-10 | `ScheduledSyncSettingsTest` | `unmetered_should_beDisabledWithDesktopReason_When_SupportsUnmeteredFalse` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `errorStates_should_alwaysOfferAnExit_When_AnyS1ToS4FailureRenders` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `errorStates_should_leaveControlsOperableAndBackAvailable_When_S2SaveFails` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `errorStates_should_offerAddGraph_When_NoGraphsYet` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `errorStates_should_offerBack_When_S2IsLoading` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `errorStates_should_offerRetryAndBack_When_S2ReadFails` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `keyboard_should_tabToEveryInteractiveRowInOrder_When_PanelRendered` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `panel_should_disableSwitchWithReasonAndReviewAction_When_FirstSyncUnreviewed` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `panel_should_disableSwitchWithReasonAndSetUpAction_When_NoRemote` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `panel_should_keepAnAlreadyOnSwitchOperable_When_GateAppearsAfterEnabling` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `panel_should_showNoReasonAndKeepSwitchEnabled_When_NoGateKnown` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `rows_should_haveExactlyOneRoleNodePerOption_AndNoFocusableInnerControl` | Compose (Robolectric) |
| REQ-10 | `TaskSettingsAccessibilityAndErrorsTest` | `section_should_passGateOnlyToTheGraphItIsKnownFor_When_DrillingIntoGraphs` | Compose (Robolectric) |
| REQ-11: restricted-background hint | `AndroidBatterySettingsLauncherTest` | `open_should_launchAppDetailsSettingsForThisPackage_When_HandlerExists` | Integration (Robolectric) |
| REQ-11 | `AndroidBatterySettingsLauncherTest` | `open_should_reportUnavailable_When_LaunchIsRefused` | Integration (Robolectric) |
| REQ-11 | `AndroidBatterySettingsLauncherTest` | `open_should_reportUnavailable_When_NoActivityHandlesIt` | Integration (Robolectric) |
| REQ-11 | `ScheduledSyncPresentationTest` | `hint_should_beHidden_When_DesktopDisabledOrCauseIsDeliberateSkip` | Unit |
| REQ-11 | `ScheduledSyncPresentationTest` | `hint_should_followRestrictionReason_When_AndroidAndEnabled` | Unit |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `hint_should_becomeManualPathAndNotClickable_When_LauncherReportsUnavailable` | Compose (Robolectric) |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `hint_should_becomeManualPathWithoutCrashing_When_LauncherThrows` | Compose (Robolectric) |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `hint_should_clearWhenCauseClears_AndBeHiddenWhenDisabled` | Compose (Robolectric) |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `hint_should_launchSettingsOnce_When_AppRestrictedButtonTapped` | Compose (Robolectric) |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `hint_should_launchSettingsOnce_When_StandbyRestrictedButtonTapped` | Compose (Robolectric) |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `hint_should_showManualPath_When_NoLauncherOnThisPlatform` | Compose (Robolectric) |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `hint_should_showSofterOemWordingWithButton_When_LateAndProbeNone` | Compose (Robolectric) |
| REQ-11 | `ScheduledSyncStatusActionsTest` | `hint_should_showTextWithoutButton_When_BatterySaver` | Compose (Robolectric) |
| REQ-12: fail-closed settings | `TaskConfigCodecTest` | `decodeEntry_should_neverThrow_When_RandomGarbage` | Unit |
| REQ-12 | `TaskSettingsStoreMigrationTest` | `readResult_should_beReadFailed_When_SeedUnavailable` | Unit |
| REQ-12 | `TaskSettingsStoreMigrationTest` | `readResult_should_distinguishReadFailedFromDisabled_When_SettingsThrow` | Unit |
| REQ-12 | `TaskSettingsStoreMigrationTest` | `read_should_returnDisabledSpecDefault_When_NoEntryAndNoLegacySource` | Unit |
| REQ-12 | `TaskSettingsStoreMigrationTest` | `write_should_returnFalse_When_SettingsWriteFails` | Unit |
| REQ-12 | `TaskWorkerTest` | `doWork_should_callSeedOnce_When_entryAndMarkerBothAbsent` | Integration (Robolectric) |
| REQ-12 | `TaskWorkerTest` | `doWork_should_clearHealth_When_nextPeriodicReadSucceeds` | Integration (Robolectric) |
| REQ-12 | `TaskWorkerTest` | `doWork_should_neverCallSeedForExistingEntry_When_periodicRuns` | Integration (Robolectric) |
| REQ-12 | `TaskWorkerTest` | `doWork_should_shortCircuitDisabledWithoutSeed_When_periodicEntryExists` | Integration (Robolectric) |
| REQ-12 | `TaskWorkerTest` | `doWork_should_skipColdPathNotAllowed_When_specDisallowsIt` | Integration (Robolectric) |
| REQ-13: bounded names and reads | `BackgroundTasksSectionTest` | `list_should_showOneSummaryLinePerKindAndUsingDefaultOrCustomRows_When_25Graphs` | Compose (Robolectric) |
| REQ-13 | `ScheduleStartupTest` | `run_should_openNoNonActiveGraphDbAndMakeNoNetworkCall_When_StartupRuns` | Integration (Robolectric) |
| REQ-13 | `TaskRunLogTest` | `recent_should_keepNewest20NewestFirst_When_25Appended` | Unit |
| REQ-13 | `TaskRunLogTest` | `runLogKey_should_neverCollide_When_AnyKindScopeAndSource` | Unit |
| REQ-14: wiring and test-hygiene gates | `WiringCompletenessTest` | `businessTestData_should_BeVisibleOnClasspathUnderBazel_When_AllowlistCatalogEgressBaselineHygieneAndCredentialFilesLoaded` | Unit |
| REQ-14 | `WiringCompletenessTest` | `plantedOrphan_should_FailUnderBazelSandbox_When_RunViaBazelTestBusinessTests` | Unit |
| REQ-14 | `WiringCompletenessTest` | `realCatalog_should_PassAgainstRealTestSources` | Unit |
| REQ-14 | `WiringCompletenessTest` | `seamList_should_EqualGlossaryList_When_Parsed` | Unit |
| REQ-14 | `WiringCompletenessTest` | `seededAllowlist_should_PassAtHeadRoots_And_FailNamingClass_When_AnyAwaitingUnitLineRemoved` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringAllowlist_should_FailNamingEntry_When_AllowlistEntryOlderThan90Days` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringAllowlist_should_NeverExpire_When_ReasonClassOutOfScopeIosAndDateIsNever` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringCatalog_should_Fail_When_EntryPointTestMissingOrDoesNotCallWiredAppFixture` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringCompleteness_should_FailNamingClassAndRootsSearched_When_ImplementationNeverConstructed` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringCompleteness_should_FailRootNotFound_When_AnyRootAbsentFromClasspath` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringCompleteness_should_FailWhenGraphContentSetupFilesAbsent` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringCompleteness_should_FindTransitiveImplementations_And_SkipAbstractTypes` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringCompleteness_should_NotCountACommentOrStringMention_When_RootOnlyMentionsTheClass` | Unit |
| REQ-14 | `WiringCompletenessTest` | `wiringCompleteness_should_Pass_When_EverySeamImplementationIsConstructedInARoot` | Unit |
| REQ-15: LFS shares NetworkPolicy | `AndroidGitRepositoryLfsGuardTest` | `commit_should_RefuseAndUnstage_When_ARawBlobWasStagedWithoutTheGuard` | Integration (Robolectric) |
| REQ-15 | `AndroidGitRepositoryLfsGuardTest` | `stageSubdir_should_CommitAPointerNormally` | Integration (Robolectric) |
| REQ-15 | `AndroidGitRepositoryLfsGuardTest` | `stageSubdir_should_HoldTheRawBinaryBackAndCommitTheRest_When_PathIsLfsTracked` | Integration (Robolectric) |
| REQ-15 | `AndroidGitRepositoryLfsGuardTest` | `stagedLfsViolations_should_ReportAndUnstage_When_ARawBlobIsInTheIndex` | Integration (Robolectric) |
| REQ-15 | `LfsNetworkGateTest` | `deferralStatus_should_ShareTheNetworkPolicyCopy` | Unit |
| REQ-15 | `LfsNetworkGateTest` | `defers_should_BeFalse_When_DetectionIsUnsupported_ForEveryReading` | Unit |
| REQ-15 | `LfsNetworkGateTest` | `defers_should_BeFalse_When_PolicyDefersAndNetworkIsUnmetered` | Unit |
| REQ-15 | `LfsNetworkGateTest` | `defers_should_BeFalse_When_PolicyDoesNotDefer_ForEveryReading` | Unit |
| REQ-15 | `LfsNetworkGateTest` | `defers_should_BeTrue_When_PolicyDefersAndMeteringIsUnknownOnADetectingPlatform` | Unit |
| REQ-15 | `LfsNetworkGateTest` | `defers_should_BeTrue_When_PolicyDefersAndNetworkIsMetered` | Unit |
| REQ-15 | `LfsTransferPolicyTest` | `download_should_BeHeldWithoutAJob_When_MeteredAndPolicyDefers` | Integration (jvm) |
| REQ-15 | `LfsTransferPolicyTest` | `download_should_BeHeld_When_MeteringIsUnknownAndDetectionIsSupported` | Integration (jvm) |
| REQ-15 | `LfsTransferPolicyTest` | `download_should_Proceed_When_DetectionIsUnsupported_EvenIfReportedMeteredOrUnknown` | Integration (jvm) |
| REQ-15 | `LfsTransferPolicyTest` | `download_should_Proceed_When_MeteredButPolicyDoesNotDefer` | Integration (jvm) |
| REQ-16: device reliability | GAP | no automated test (owner-run only: M-1..M-4 in the manual checklist) | n/a |

REQ-16 note: `WorkManagerJitterSpikeTest` (listed under REQ-3) records OS behaviour on Robolectric only; nothing asserts real Doze, standby-bucket or OEM behaviour.

### Requirement-level gaps (no real test found)

| Requirement | Gap |
|---|---|
| REQ-16 | GAP: no automated test by design (Doze, standby, OEM managers); owner checklist M-1..M-4 |
| REQ-12 (Keystore cold-worker read) | GAP: Robolectric has no Keystore; `PlatformSettingsColdPathTest` proves only the plain-prefs fallback; owner step M-4 |
| REQ-9 (reversibility) | GAP: no test named for "legacy keys byte-identical after rollback"; `read_should_leaveLegacyKeysUnchanged_When_MigrationRuns` covers only the keys staying unchanged after migration |
| REQ-13 (no DB fan-out at startup / no standing unbounded collectors) | PARTIAL: `run_should_openNoNonActiveGraphDbAndMakeNoNetworkCall_When_StartupRuns`, `doWork_should_neverCallSeedForExistingEntry_When_periodicRuns` and the 20-record cap exist; there is no test that scans for unbounded collectors |
| REQ-14 (`AllBusinessTests` registration) | GAP: no test named for registration completeness of new classes; registration is checked only by the Bazel CI target |
| REQ-2 (Desktop unknown network) | PARTIAL: `tick_should_skipWithoutRunningBody_When_UnmeteredAndMeteringUnknown` covers the in-process adapter; Desktop never offers Unmetered in the UI |

## UX Acceptance Tests

Source: `../design/ux.md` Step 3 (UX-1 to UX-39). Tool: Compose (Robolectric) = `./gradlew :kmp:testDebugUnitTest --tests ...` (headless, Gradle-only); Unit = commonTest; JvmCompose = `scripts/jvm-display-check.sh -- ./gradlew :kmp:jvmTest --tests ...`. Names are real `@Test` functions.

| UX Criterion | Test File | Test Name | Tool |
|---|---|---|---|
| UX-1 | `BackgroundTasksSectionTest` | `enableSync_should_takeAtMostThreeTaps_When_StartingFromSettings` | Compose (Robolectric) |
| UX-2 | `ScheduledSyncSettingsTest` | `changeIntervalAndNetwork_should_takeAtMostFourTapsWithoutLeavingScreen_When_FromSettings` | Compose (Robolectric) |
| UX-3 | `ScheduledSyncSettingsTest` | `jitter_should_appearOnlyUnderAdvancedWithOffALittleALotLabels_When_SectionRendered` | Compose (Robolectric) |
| UX-4 | `TaskSettingsAccessibilityAndErrorsTest` | `panel_should_keepAnAlreadyOnSwitchOperable_When_GateAppearsAfterEnabling` | Compose (Robolectric) |
| UX-4 | `ScheduledSyncSettingsTest` | `switch_should_turnOffInOneTap_When_AlreadyOn` | Compose (Robolectric) |
| UX-5 | `BackgroundTasksSectionTest` | `list_should_showOneSummaryLinePerKindAndUsingDefaultOrCustomRows_When_25Graphs` | Compose (Robolectric) |
| UX-6 | `ScheduledSyncPresentationTest` | `networkLabels_should_beExactAndNeverContainWifiOnly_When_AllLocalizedStringsScanned` | Unit |
| UX-7 | `ScheduledSyncPresentationTest` | `statusLine_should_sayHasntRunInHours_When_LastSuccess3hAgoAndIntervalOneHour` | Unit |
| UX-8 | `ScheduledSyncPresentationTest` | `statusLine_should_followPrecedence_PausedThenDisabledThenUnreadableThenLateThenInProgressThenChecked` | Unit |
| UX-9 | `ScheduledSyncStatusActionsTest` | `section_should_showLockedWithUnlockActionAndNoRestrictedHint_When_LateCauseIsVaultLocked` | Compose (Robolectric) |
| UX-9 | `ScheduledSyncPresentationTest` | `statusLine_should_nameDeliberateSkip_When_LateCauseIsVaultLockedConflictEditingOrBusy` | Unit |
| UX-10 | `ScheduledSyncPresentationTest` | `statusCopy_should_neverStateAClockTime_When_AnyLineRenders` | Unit |
| UX-10 | `ScheduledSyncPresentationTest` | `firstCheck_should_useAboutAndNeverClockTime_When_Rendered` | Unit |
| UX-11 | `ScheduledSyncSettingsTest` | `summary_should_showClampAndSpreadWindow_When_AndroidFiveMinOrJitterSet` | Compose (Robolectric) |
| UX-11 | `ScheduledSyncPresentationTest` | `spreadSummary_should_deriveFromDescribeGapFields_When_DesktopOrWorkManagerAdapter` | Unit |
| UX-12 | `ScheduledSyncSettingsTest` | `unmetered_should_beDisabledWithDesktopReason_When_SupportsUnmeteredFalse` | Compose (Robolectric) |
| UX-13 | `ScheduledSyncSettingsTest` | `fiveMinute_should_beAbsentWithAndroid15MinHelperLine_When_MinIntervalIs15` | Compose (Robolectric) |
| UX-14 | `TaskSettingsAccessibilityAndErrorsTest` | `panel_should_disableSwitchWithReasonAndSetUpAction_When_NoRemote` | Compose (Robolectric) |
| UX-14 | `TaskSettingsAccessibilityAndErrorsTest` | `panel_should_disableSwitchWithReasonAndReviewAction_When_FirstSyncUnreviewed` | Compose (Robolectric) |
| UX-14 | `BackgroundTasksUxGapsTest` | `graphPanel_should_sayOpenGraphToSeeStatus_When_GraphIsNotActive` | Compose (Robolectric) |
| UX-15 | `ScheduledSyncSettingsTest` | `section_should_showCouldntReadSettingsWithRetryAndNeverApply_When_ReadFailsClosed` | Compose (Robolectric) |
| UX-15 | `BackgroundTasksUxGapsTest` | `kindSummary_should_showCouldntReadSettingsAndRetry_When_ReadFails` | Compose (Robolectric) |
| UX-16 | `ScheduledSyncSettingsTest` | `control_should_revertAndShowCouldntSave_When_WriteOrApplyThrows` | Compose (Robolectric) |
| UX-17 | `ScheduledSyncStatusActionsTest` | `hint_should_becomeManualPathWithoutCrashing_When_LauncherThrows` | Compose (Robolectric) |
| UX-17 | `ScheduledSyncStatusActionsTest` | `hint_should_becomeManualPathAndNotClickable_When_LauncherReportsUnavailable` | Compose (Robolectric) |
| UX-17 | `AndroidBatterySettingsLauncherTest` | `open_should_launchAppDetailsSettingsForThisPackage_When_HandlerExists` | Compose (Robolectric) |
| UX-18 | `ScheduledSyncStatusActionsTest` | `hint_should_showTextWithoutButton_When_BatterySaver` | Compose (Robolectric) |
| UX-18 | `ScheduledSyncStatusActionsTest` | `hint_should_launchSettingsOnce_When_StandbyRestrictedButtonTapped` | Compose (Robolectric) |
| UX-18 | `ScheduledSyncStatusActionsTest` | `hint_should_showSofterOemWordingWithButton_When_LateAndProbeNone` | Compose (Robolectric) |
| UX-19 | `ModelDownloadUiTest` | `waitingRow_should_offerDownloadNowAnywayAndReplaceWithAnyNetwork_When_networkIsMetered` | Compose (Robolectric) |
| UX-20 | `TaskSettingsAccessibilityAndErrorsTest` | `errorStates_should_alwaysOfferAnExit_When_AnyS1ToS4FailureRenders` | Compose (Robolectric) |
| UX-20 | `ModelDownloadUiTest` | `everyState_should_keepAnExit` | Compose (Robolectric) |
| UX-20 | `ModelDownloadPresentationTest` | `everyNonFinishedState_should_haveAnExit` | Compose (Robolectric) |
| UX-21 | `TaskRunLogRowsTest` | `row_should_readTaskGraphAndResultWithStartTime_When_RecordIsSkippedMetered` | Compose (Robolectric) |
| UX-22 | `TaskRunLogRowsTest` | `section_should_showNothing_When_RunLogIsEmpty` | Compose (Robolectric) |
| UX-22 | `TaskRunLogRowsTest` | `section_should_offerRetryThatRereads_When_RunLogThrows` | Compose (Robolectric) |
| UX-23 | `TaskRunLogTest` | `append_should_persistAndLogResultAndMeteredState_When_Skipped` | Unit |
| UX-23 | `TaskRunLogTest` | `append_should_neverPersistOrLogCredentials_When_FailureTextCarriesUrlUserinfoAndToken` | Unit |
| UX-24 | `TaskSettingsAccessibilityAndErrorsTest` | `keyboard_should_tabToEveryInteractiveRowInOrder_When_PanelRendered` | Compose (Robolectric) |
| UX-24 | `ScheduledSyncRadioKeyboardTest` | `arrowKeys_should_moveSelectionSkippingDisabled_When_DesktopRadioGroupFocused` | JvmCompose |
| UX-24 | `BackgroundTasksUxGapsTest` | `legacyIntervalPicker_should_moveSelectionWithArrowKeys_When_RadioFocused` | Compose (Robolectric) |
| UX-25 | `BackgroundTasksSectionTest` | `graphRow_should_exposeOneMergedNodeWithExactDescription_When_SemanticsRead` | Compose (Robolectric) |
| UX-26 | `ScheduledSyncSettingsTest` | `rows_should_be48dpAndSingleFocusStop_When_RadiosAndSwitchesRendered` | Compose (Robolectric) |
| UX-26 | `TaskSettingsAccessibilityAndErrorsTest` | `rows_should_haveExactlyOneRoleNodePerOption_AndNoFocusableInnerControl` | Compose (Robolectric) |
| UX-27 | `ScheduledSyncStatusActionsTest` | `statusLine_should_notReannounce_When_30sRefreshLeavesStateUnchanged` | Compose (Robolectric) |
| UX-28 | `ScheduledSyncStatusActionsTest` | `lateAndHintRows_should_pairTextWithIcon_NotColourAlone` | Compose (Robolectric) |
| UX-30 | `ScheduledSyncSettingsTest` | `resetToDefault_should_clearOverridesKeepEnabledAndApplyOnce_When_CustomRowTapped` | Compose (Robolectric) |
| UX-31 | `TaskRunLogRowsTest` | `rows_should_neverShowRawIds_When_GraphHasDisplayName` | Compose (Robolectric) |
| UX-31 | `TaskRunLogRowsTest` | `time_should_beRelativeTodayOrDated_When_RecordAgeVaries` | Compose (Robolectric) |
| UX-32 | `ScheduledSyncSettingsTest` | `section_should_showSkeletonDisabledControlsAndLoadingReason_When_ReadPending` | Compose (Robolectric) |
| UX-33 | `BackgroundTasksSectionTest` | `drillIn_should_moveFocusToTitleAndBackRestoreOpener_When_S1ToKindToGraph` | Compose (Robolectric) |
| UX-34 | `BackgroundTasksSectionTest` | `search_should_haveLabelAndShowNoMatchesWithClear_When_QueryMatchesNothing` | Compose (Robolectric) |
| UX-35 | `ScheduledSyncSettingsTest` | `disabledControl_should_exposeReasonInSemantics_When_UnmeteredOnDesktop` | Compose (Robolectric) |
| UX-36 | `ScheduledSyncSettingsTest` | `rows_should_wrapAt200PercentFontAndMirrorUnderRtl_When_Rendered` | Compose (Robolectric) |
| UX-36 | `ScheduledSyncPresentationTest` | `customSummary_should_listOnlyDifferingValues_When_OneTwoOrThreeDiffer` | Unit |
| UX-36 | `ScheduledSyncPresentationTest` | `lateText_should_useMinutesHoursThenDays_When_DurationCrossesThresholds` | Unit |
| UX-36 | `BackgroundTasksUxGapsTest` | `navigation_should_useAutoMirroredIconsNotLiteralArrows_When_ListAndDrillInShown` | Compose (Robolectric) |
| UX-37 | `ScheduledSyncSettingsTest` | `enable_should_announceFirstCheckInAboutNMin_When_SwitchTurnedOn` | Compose (Robolectric) |
| UX-38 | `ModelDownloadPresentationTest` | `running_should_showProgressOrIndeterminateWithCancel` | Compose (Robolectric) |
| UX-38 | `ModelDownloadPresentationTest` | `cancelled_should_offerDownloadAgain` | Compose (Robolectric) |
| UX-38 | `ModelDownloadPresentationTest` | `notificationsOff_should_addNoticeWithoutChangingTheActions` | Compose (Robolectric) |
| UX-38 | `DepthEstimationPanelLiveRegionTest` | `downloadStates_should_beInPoliteLiveRegion_When_StateChanges` | Compose (Robolectric) |
| UX-39 | `ScheduledSyncStatusActionsTest` | `section_should_showEditingAndBusyCopyWithoutAction_When_ThoseSkipsAreTheCause` | Compose (Robolectric) |
| UX-39 | `ScheduledSyncPresentationTest` | `statusLine_should_sayAnotherSyncRunning_When_LastSkipBusyAndNoTickInFlight` | Unit |
| UX-28 (contrast >= 4.5:1) | GAP | no automated contrast test for the feature screens; owner M-8 | Manual |
| UX-29 (Esc closes dialog, focus returns) | GAP | no automated test; owner M-6 | Manual |

UX coverage: 38 of 39 criteria have at least one automated test; UX-28 (contrast part) and UX-29 are GAP/manual. Criteria with no automated row at all: UX-29.

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
- Pre-existing regression gates are now listed from their real sources (VERIFIED exist): `ScheduledSyncPolicyTest`, `PeriodicSyncDriverTest`, `WorkManagerSyncSchedulerUniqueWorkTest`, `WorkManagerSyncSchedulerPauseTest`, `WorkManagerSyncSchedulerSlowPathYieldTest`, `WorkManagerSyncSchedulerWatchdogTest`, `ScheduledSyncOriginE2ETest`, `ScheduledSyncStackE2ETest`, `GitSyncServiceScheduledSyncTest`, `AndroidGitRepositoryLfsGuardTest`. Original note, kept for context: **Verify each exists before relying on it** (`ScheduledSyncPolicyTest`, `PeriodicSyncDriverTest`, `ScheduledSyncStatusTest`, `WorkManagerSyncSchedulerRetryOwnerTest`, `WorkManagerSyncSchedulerUniqueWorkTest`, `WorkManagerSyncSchedulerPauseTest`, `WorkManagerSyncSchedulerSlowPathYieldTest`, `WorkManagerSyncSchedulerWatchdogTest`, `GitSyncSchedulingEntryPointTest`, `GitSyncStackEntryPointTest`, `ScheduledSyncOriginE2ETest`, `LfsTransferPolicyTest`, `AndroidGitRepositoryLfsGuardTest`): a missing class means the regression gate must be written, not assumed (Task 3.1.2b characterization run is the checkpoint).
- Engineering note (triad repair 1): story-level waves in `plan.md` understate serial depth; the root-file chain is about 10 serial tasks. PR-stack check: `gh pr list` found no open git-sync PR at triad time; Tasks 0.1.2b and 3.1.2a re-check.
- Triad repair 1 UX additions: UX-30..UX-39 map to the rows above (UX coverage 39/39); UX-28, UX-29, UX-25/35, UX-33/34 also carry owner device items M-6..M-9 (plan Story 5.1.3). The count in the Inputs line (115 tasks) is now 116 tasks (new Task 4.1.1g).
- Traceability regenerated after implementation: the earlier table listed proposed names, and most were never created (105 of 161 did not exist). Every name now listed was extracted from the source and verified verbatim; see the count line at the top of the mapping.
