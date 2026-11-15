# Validation Plan: sync-lock-and-image-annotator

**Date**: 2026-11-14
**Inputs read**: `requirements.md` (4 Success Metrics, NFRs, constraints), `implementation/plan.md` (107 tasks, 31 stories, Repair Log through triad repair 2), `design/ux.md` (UX-01..UX-70), ADR-001..007, `implementation/adversarial-review.md` (re-review pass 2: 0 blockers, C1-C10 + minors), `implementation/architecture-review.md` (re-review: 0 blockers, R1-R6 + nits), repo `CLAUDE.md` (test placement rules).

Legend. Run column: **CI** = runs without a device (Bazel `//kmp:business_tests`, `//kmp:jvm_tests` via `scripts/jvm-display-check.sh`, Gradle `testDebugUnitTest`); **OWNER** = needs Tyler's phone or photos (named wall-clock blocker, cannot be closed by CI). Path aliases are the plan's (`CT`, `BT`, `JT`, `AU`, `CM`, `AM`, `JM`). IDs: `U-` unit, `I-` integration, `X-` UX acceptance, `D-` owner-device (counted separately, each is also integration-class). "NEW" in the Scenario column marks a test this validation adds because the plan's own test plan was thin or a review concern had no test.

## Happy Path Scenario

Given the v0.113.3 baseline (a slow sync can be running while Sync is pressed again, tools must be re-picked, measurements cannot be edited, one rear lens), when the owner starts a sync and presses Sync twice more, then draws one distance with one tool tap plus one drag, types 90 cm into that measurement's value editor, adds known distances and opens the camera to switch to the ultra-wide lens, then exactly one sync runs (one `decision=started`, two `decision=coalesced`, zero new `index.lock`, no cancel, no red state), the editor is back in Select with pan working, the measurement reads "90 cm" and is marked edited, the calibration pill shows "Calibrated from N distances", and the saved photo's sidecar records the ultra-wide lens id and focal length.

## Requirements

| ID | Requirement (source) |
|----|----------------------|
| R1 | SM1a: second Sync press (UI/tick/worker) creates zero new `index.lock`, never cancels or restarts the running sync; regression reproduces overlap and fails on today's code |
| R2 | SM1b: forced-cancel and forced-crash matrix leaves no lock the next sync cannot clear safely on its own |
| R3 | SM2a: fresh editor, one tool tap + one drag = one measurement, back in Select, pan/zoom works, no extra taps |
| R4 | SM2b: at the zoom floor the whole image is visible within at most 25% of the viewport width (at most 6.25% of its area) and a point is placeable on the outermost pixel row/column (stored coordinate exactly 0.0 or 1.0), at Fit and at the floor |
| R5 | SM3a: owner can change the value of any point-to-point measurement |
| R6 | SM3b: with >= N known distances in different places and directions, held-out mean absolute error lower than single-scale on the verification set (door-frame photo included) and within the PROPOSED absolute tolerance (at most 2% of the true length, 0.5 cm floor under 25 cm; owner confirms, OC-13); owner-study-gated |
| R7 | SM4a: capture screen lists every rear lens the device exposes and lets the owner switch |
| R8 | SM4b: saved photo records which lens/focal length took it |
| R9 | Status text/toast no longer overlaps the photo; no "estimation coming in a future update" text |
| R10 | Mode clarity: Keep-tool lock (opt-in), disarm paths, armed state visible |
| R11 | Calibration persisted with the annotation; old sidecars and DB rows keep loading; additive schema with version |
| R12 | Camera basic controls: flash, zoom ratio, lens label |
| R13 | NFR perf: pan/zoom/drag >= 60 fps-equivalent on 12 MP at the larger zoom-out range |
| R14 | NFR perf: lens switch within ~1 s |
| R15 | NFR scalability: sync/reload path stays bounded on the existing 8 000-page graph scale |
| R16 | NFR security: confidential, no new network egress |
| R17 | NFR data residency: none (verified only by R16's audit; no test beyond it) |
| R18 | OBS: every sync trigger logs source + decision |
| R19 | OBS: every lock create/clear logs owner id and reason |
| R20 | OBS: distinct state/log for "joined running sync" |
| R21 | OBS: calibration logs model, N, residual |
| R22 | Constraint: Arrow `Either` at repository/service boundaries |
| R23 | Constraint: DB writes via `DatabaseWriteActor`; new columns in `MigrationRunner`; SQLDelight sources regenerated |
| R24 | Constraint: no `rememberCoroutineScope` escaping into remembered classes |
| R25 | Constraint: new tests registered in `AllBusinessTests`/`AllJvmTests`/`AllAndroidUnitTests` + commonTest BUILD; wiring catalog; no `Thread.sleep`/`GlobalScope`/real-clock `delay` |
| R26 | Constraint: diagnosis via persisted logs only (`Logger`, not `android.util.Log`) |
| R27 | Constraint: PR #676 (unapplied-disk-content guard) and #662 (never stuck running) not weakened |
| R28 | Constraint: PRs open as drafts, no merge/release without owner (process) |
| R29 | Risk: sync fails closed when lock liveness is unsure |
| R30 | Risk: Settings toggles (`annotator_auto_return_to_select` default on, `annotator_sticky_tools` default off) |
| R31 | Risk: each epic independently revertable; downgrade to v0.113.3 reads new data |
| R32 | Rabbit hole: "stale" decided by ownership/liveness, never age or presence |
| R33 | Rabbit hole: gesture routing, hit-testing, undo/redo not regressed |
| R34 | Rabbit hole: existing sidecars load after any calibration schema change |
| R35 | Rabbit hole: OEM-hidden lenses degrade to zoom stops; lens availability OEM-dependent |
| R36 | Unresolved-question gates: probe results (4.1b), N and thresholds (3.8a), `HeightOffset` composition (3.2a before 3.3c) |

## Step 0 test-design rules applied

CLAUDE.md placement: pure logic in `commonTest`; Compose behaviour in `androidUnitTest` (Robolectric, headless); `jvmTest` only for pixels or desktop-only, each file carrying `// jvmTest-reason: <pixels|desktop-only>` when it imports `androidx.compose.ui.test`; real-JGit and multi-process tests live in `jvmTest` because `JvmGitRepository` is `jvmMain`-only (no compose test import, so no marker needed). Property tests use `kotest-property` `Arb`/`checkAll` inside `runTest`. No `Thread.sleep`, `GlobalScope`, or real-clock `delay`: virtual time (`TestScope`), `CountDownLatch`, `CompletableDeferred` only. Every class listed is registered by the wave's registration integrator (plan Registration rule) and each commonTest class gets a `kmp/src/commonTest/kotlin/BUILD.bazel` entry.

## Requirement -> Test Mapping

### E1 Sync: unit

| REQ | ID | Test File | Test Name | Type | Scenario | Run |
|-----|----|-----------|-----------|------|----------|-----|
| R2,R29,R32 | U-E1-01 | CT/git/LockRecoveryDecisionTest | decide_should_NeverDeleteUnlessOwnOrDeadAndAllowListed_When_AnyInputCombination | Unit (property) | Happy/Error: all {record, liveness, self/other, allow-listed} combos | CI |
| R2,R32 | U-E1-02 | CT/git/LockRecoveryDecisionTest | decide_should_ReturnClearedDeadOwner_When_PidDeadOrStartTicksDiffer | Unit | Happy path: dead pid, pid reuse | CI |
| R2,R32 | U-E1-03 | CT/git/LockRecoveryDecisionTest | decide_should_ReturnClearedDeadOwner_When_SameHostBootIdDiffers | Unit | Happy path: battery death | CI |
| R29 | U-E1-04 | CT/git/LockRecoveryDecisionTest | decide_should_ReturnOwnerUnknown_When_LivenessUnknownOrDifferentHostOrBootIdNull | Unit | Error path: fail closed | CI |
| R29 | U-E1-05 | CT/git/LockRecoveryDecisionTest | decide_should_ReturnNoRecordAndDeleteNothing_When_LockHasNoRecord | Unit | Error path | CI |
| R29 | U-E1-06 | CT/git/TornRecordIsUnknownTest | decide_should_ReturnOwnerUnknownAndDeleteNothing_When_RecordIsEmptyTornOrUnparseable | Unit | NEW (adv C4: torn record from `CREATE_NEW` then write) | CI |
| R32 | U-E1-07 | CT/git/ClockStepStartTimeTest | liveness_should_NotReportDead_When_StartInstantDiffersOnlyByWallClockStep | Unit (property over btime skew) | NEW (adv C3; renamed from ProcessLivenessSkewTest in the Phase 4 patch: JVM start time = btime + ticks; NTP step must not read a live owner as Dead; Linux/Android compare raw `/proc` ticks exactly, macOS/Windows `Instant` within 2 s); the real `/proc` path is exercised by I-E1-08 | CI |
| R2 | U-E1-08 | CT/git/ProcStatParserTest | parseProcStat_should_ReturnStartTicks_When_CommHasSpacesAndParentheses | Unit | Happy path; ENOENT=Dead, EACCES=Unknown error path | CI |
| R1,R29 | U-E1-09 | CT/git/RepoOpGateTest | tryAcquire_should_ReturnNull_When_HeldAndAwaitFreeShouldWake_When_Released | Unit | Happy + Error | CI |
| R1,R32 | U-E1-10 | CT/git/GateKeyAliasTest | keyFor_should_ReturnSameKey_When_TrailingSlashSymlinkOrRelativeAliasOfOneDirectory | Unit | NEW (adv C1: `lockNameFor` is a URL normaliser; two keys for one dir would make `ClearedOwn` delete a live lock); also `/a-b` vs `/a/b` must not collide (adv minor) | CI |
| R1 | U-E1-11 | CT/git/RepoOpGateReleaseStructureTest | releaseOrHandOff_should_FreeGate_When_EndOwnedOperationThrows | Unit | Error path; `releaseOrHandOff` is non-`suspend` | CI |
| R1,R20 | U-E1-12 | BT/git/SyncOriginTriggerMappingTest | toTrigger_should_MapFiveSyncCallingOrigins_AndLeaveOthersUnmapped | Unit | Happy path | CI |
| R29 | U-E1-13 | BT/git/ResolveGateKeyFailureTest | resolveGateKey_should_FailClosedAndMakeNoGitCall_When_ConfigUnreadableOrThrows | Unit | Error path, NEW case: transient `getConfig` exception (not only `Left`) must not run ungated under `graph:<id>` (arch R3) | CI |
| R1 | U-E1-14 | BT/git/GateKeyResolutionTest | quiesceKey_should_EqualSyncKey_When_SameGraph | Unit | NEW (arch R5: injected `(StorageMoveOperation)->String` lambda) | CI |
| R1,R20 | U-E1-15 | BT/git/SyncSingleFlightModelTest | rerunBit_should_YieldExactlyOneFollowUp_When_AnyInterleavingOfPressesAndRelease | Unit (property, model-based) | NEW: 500 seeded interleavings; no lost press, no duplicate follow-up | CI |
| R1,R27 | U-E1-16 | BT/git/GitSyncServiceSingleFlightTest | sync_should_ReturnSyncInProgressAndNotChangeSyncState_When_SecondTriggerArrivesInAnyPhase | Unit | Happy path over phases Fetching/Merging/Committing/Pushing (also offline flip + vault lock mid-run) | CI |
| R1 | U-E1-17 | BT/git/GitSyncServiceSingleFlightTest | liftMassChangeGuard_should_BeRefusedNotQueued_When_RunInFlight | Unit | Error path; the lift flag never rides a rerun | CI |
| R1 | U-E1-18 | BT/git/GitSyncServiceSingleFlightTest | confirmDialog_should_NotHoldGate_When_AwaitingUserConfirm | Unit | Edge | CI |
| R1,R27 | U-E1-19 | BT/git/GitSyncServiceConflictResolutionTest | tickDuringPendingMerge_should_BeSkipped_When_ConflictPending_AndGateReleased | Unit | Edge | CI |
| R1 | U-E1-20 | BT/git/GitSyncServiceSingleFlightTest | rateLimitRetry_should_SurviveAndNotOverlap_When_ManualPressDuringBackoff | Unit | Edge | CI |
| R27 | U-E1-21 | BT/git/GitSyncServiceSingleFlightTest | gate_should_ReleaseAndSyncStateNotStickRunning_When_PipelineThrowsAnyThrowable | Unit | Error path (#662 class, adv C2 of earlier pass) | CI |
| R2,R27 | U-E1-22 | BT/git/GitSyncCancelBoundaryTest | cancelDuringFetch_should_NotMerge_AndCancelBeforeCommitShouldNotCommit | Unit | Error path | CI |
| R2 | U-E1-23 | BT/git/GitSyncCancelBoundaryTest | cancelThenImmediateRepress_should_NotOverlap_When_BlockingCallStillRunning | Unit | Happy path; gate held until call returns | CI |
| R27 | U-E1-24 | BT/git/GitSyncCancelBoundaryTest | mergeUnit_should_NotFallThroughToPush_When_ConflictOrCommitFailureExitsInsideNonCancellable | Unit | NEW (adv C5: `return@withContext` rebinds to the inner lambda) | CI |
| R27,R15 | U-E1-25 | BT/git/GitSyncMergeUnitContainmentTest | reload_should_ChunkAtMost200AndSetReloadIncomplete_When_BeginGitMergeThrows | Unit | Error path; closed-DB containment | CI |
| R27 | U-E1-26 | BT/git/GitSyncServiceUnappliedContentGuardTest (existing #676 suite, re-run) | existingGuardSuite_should_StayGreen_When_GateAdded | Unit | Regression pin: list the existing #676 and #662 test classes in the PR and fail the wave if any is skipped or edited to weaker assertions | CI |
| R1,R20 | U-E1-27 | BT/ui/GitSyncCoordinatorCoalesceTest | secondPress_should_EmitOneAlreadySyncingEventAndNoStateChange_When_RunInFlight | Unit | Happy path (UX-02) | CI |
| R1 | U-E1-28 | BT/git/PeriodicSyncDriverBusyTest | tick_should_RecordSkipBusyAndNeverQueue_When_GateHeld | Unit | Happy path | CI |
| R1 | U-E1-29 | BT/git/ScheduledSyncRunnerBusyTest | runFull_should_ReturnSkipBusy_And_fetch_should_ReturnSkipBusy_When_GateHeld | Unit | NEW (arch nit: one test per path so a copy-paste of only one is caught) | CI |
| R1,R22 | U-E1-30 | CT/error/DomainErrorTest | toUiMessageAndToSyncErrorMessage_should_TreatSyncInProgressAsNonFailure | Unit | Error-path exhaustiveness; `TaskOutcomeMapping` maps it to Skipped, never Failure (arch R4) | CI |
| R1 | U-E1-31 | BT/git/ColdSyncRunnerTest | cold_should_ReturnSkippedBusy_When_LiveSyncHoldsGate_AndLiveShouldCoalesce_When_ColdHolds | Unit | Both directions | CI |
| R1 | U-E1-32 | BT/git/GateBeforeWorktreeOrderTest | quiesce_should_WaitForGateBeforeTakingWorktreeLock_AndNeverHoldBothWhileWaiting | Unit | Lock order; trigger during quiesce coalesces | CI |
| R1 | U-E1-33 | AU/db/QuiesceRealFlushTest | quiesce_should_NotSelfDeadlock_When_RealGitShadowFlushActorFlushRunsInDrain | Unit | NEW (arch R1: real flush re-takes the worktree lock; fake flush hid it) | CI |
| R1,R25 | U-E1-34 | BT/git/GitSyncServiceRegistryTest | register_should_StopReplacedDriver_When_SecondServiceForSameGraph | Unit | Edge (research H5) | CI |
| R18,R19,R20,R26 | U-E1-35 | BT/git/SyncObservabilityLogTest | everyTrigger_should_LogExactlyOneDecisionLine_AndCoalescedNeverAtErrorLevel | Unit | Happy; also one `lock verdict=` line per decision with owner+reason; `joined running sync` line distinct | CI |
| R1,R25 | U-E1-36 | BT/git/GitGateAuditTest | everyMutatingRepoCall_should_BeReachableOnlyThroughSingleFlightOrAllowListWithReason | Unit (source audit) | Error path: new bypass fails CI | CI |
| R16,R17,R26 | U-E1-37 | BT/audit/NewCodeAuditTest | newSyncCameraAnnotatorFiles_should_NotImportNetworkOrAndroidUtilLog | Unit (source audit) | NEW: no `java.net`/`okhttp`/`android.util.Log` in files added by this project (R16, R26) | CI |
| R2,R29 | U-E1-38 | CT/git/LockRecoveryClaimTest | claim_should_NotDeleteLockOrRecord_When_RecoveryLockContendedOrUnsupported | Unit | Error path | CI |
| R2,R32 | U-E1-39 | CT/git/ForeignLockNewerThanDeadRecordTest | claim_should_NotDeleteForeignLock_When_LockPostdatesDeadRecordHeartbeat | Unit | NEW (adv C2: a terminal `git` lock newer than a week-old dead record). Rule decided in the Phase 4 patch (ADR-001 decision 12 step 5): deletable only if `startedAtMs - 2 s <= lock.mtime <= record.mtime(heartbeat) + 10 s` and `lock.mtime <= now - 5 s`; otherwise nothing is deleted and the verdict is `OwnerUnknown(ForeignLockSuspected)` | CI |
| R2 | U-E1-40 | CT/git/ForceRemoveRacesLiveRunTest | forceRemoveLock_should_RecheckVerdictUnderRecoveryLock_AndRefuse_When_OwnerAlive | Unit | NEW (adv C4: `forceRemoveLock` goes through gate, recovery lock and re-decide) | CI |
| R1,R25 | U-E1-41 | AU/wiring/RepoGateSharedEntryPointTest | gateInstance_should_BeIdenticalAcrossServiceColdRunnerSchedulerAndQuiesce_OnAndroidAndDesktopRoots | Unit | Identity; no ctor default | CI |
| R1,R25 | U-E1-42 | BT/git/EntryPointGateTest | cloneSetupAndBranchRepair_should_TakeGateViaKeyFor | Unit | Edge entry points | CI |
| R29 | U-E1-43 | CT/git/PidNamespaceAbsentPidTest | decide_should_ReturnOwnerUnknown_When_PidAbsentButPidNamespaceDiffersOrUnreadable | Unit | NEW (Phase 4 patch, pre-mortem P1-2: sandboxed app vs host CLI see different pid spaces) | CI |
| R1,R2 | U-E1-44 | CT/git/ActiveRunNotClearedOwnTest | claim_should_ReturnSyncInProgressAndKeepLiveLock_When_OwnInstanceIdHasActiveRunOnCanonicalRecordPath | Unit | NEW (pre-mortem P1-1: `ClearedOwn` only for a corpse) | CI |
| R2 | U-E1-45 | CT/git/OwnerHeartbeatTest | heartbeat_should_AdvanceRecordMtimeEvery5s_AndStarvedHeartbeat_should_DegradeToOwnerUnknownNeverDelete | Unit (virtual time) | NEW (pre-mortem P1-3: the heartbeat is the upper bound for deleting a dead owner's locks) | CI |
| R27 | U-E1-46 | BT/audit/NonCancellableReturnAuditTest | gitSyncService_should_HaveNoReturnAtWithContextInsideNonCancellableBlock | Unit (source audit) | NEW (pre-mortem P1-4) | CI |
| R1,R2 | U-E1-47 | CT/git/GitRepositoryDefaultsFailClosedTest | defaultBeginOwnedOperation_should_ReturnOwnerUnknownNeverNoLock_AndForceRemoveLockShould_Refuse_ForAnImplementerWithoutOverrides | Unit | NEW (triad engineering gap 6: iOS, wasm and `StubGitRepository` defaults must be fail-closed) | CI |
| R2,R29 | U-E1-48 | CT/git/RecoveryLockInProcessMutexTest | twoClaimersInOneJvm_should_BeExcludedByInProcessMutex_AndOverlappingFileLockExceptionHandled | Unit | NEW (adv C6; id 47 is unused) | CI |

### E1 Sync: integration and owner device

| REQ | ID | Test File | Test Name | Type | Scenario | Run |
|-----|----|-----------|-----------|------|----------|-----|
| R1,R27 | I-E1-01 | BT/git/SyncSingleFlightRegressionTest | secondManualSync_should_ReturnSyncInProgress_AndCreateNoLock_When_FirstBlockedInCommit | Integration (fake repo, real lock file) | Happy path of SM1; must FAIL on `main` before the fix (run output pasted in PR); max concurrent repo calls = 1; no `Error` state | CI |
| R2,R32 | I-E1-02 | BT/git/SyncSingleFlightRegressionTest | liveLockOlderThan60s_should_NotBeDeleted_When_HeldByInFlightRun | Integration | Error path (the age-based deletion root cause) | CI |
| R2 | I-E1-03 | JT/git/JgitCancelLockTest | forcedCancelDuringRealCommit_should_ReleaseOrNextClaimShouldClearIndexLock | Integration (real JGit, 2,000 files) | Open Question 2; records whether a lock remains after cancel | CI |
| R2 | I-E1-04 | JT/git/JgitCancelSweepTest | cancelAtEveryProgressPoint_should_NeverLeaveUnclearableLock_AndGateHeldUntilCallReturns | Integration (real JGit) | NEW: cancel swept across add/commit/merge/fetch progress callbacks (>= 30 points, latch-driven); after each, `endOwnedOperation` and a fresh claim must return NoLock/ClearedOwn and `git status` must be healthy | CI |
| R2,R32 | I-E1-05 | JT/git/LockClaimIntegrationTest | twoAndThreeClaimers_should_YieldOneWinner_WithoutDeletingWinnersLiveLock_Over200Interleavings | Integration (real `JavaIoLockFiles`, threads) | Happy + race; ordering AC with real `beginOwnedOperation` | CI |
| R2,R29 | I-E1-06 | JT/git/TwoJvmRecoveryRaceTest | twoSpawnedJvms_should_YieldOneWinnerAndKeepSimulatedLiveLock_When_RacingOverPreexistingDeadRecord | Integration (`ProcessBuilder`, 2 JVMs x 40 rounds) | NEW (adv C6: in-JVM races lean on `FileLockTable`, not the kernel). Winner count == 1 each round, simulated live `index.lock` survives | CI |
| R2,R29 | I-E1-07 | JT/git/CrossProcessSyncOverDeadRecordTest | twoJvmsRunningGitSyncServiceSync_should_ClearDeadOwnerOnce_AndBothEndSyncedOrCoalesced | Integration (2 JVMs, real repo, dead record + `index.lock` seeded by a killed third process) | NEW: E1 "two-JVM race over a dead lock record" at service level, incl. `SyncMain` CLI vs desktop app | CI |
| R2 | I-E1-08 | JT/git/ProcessKillOrphanLockTest | sigkillDuringRealCommit_should_BeClearedAsDeadOwnerBySubsequentSync_WithoutUserAction | Integration (child JVM `destroyForcibly()` mid-commit, then parent syncs) | NEW: process-kill orphan lock; asserts verdict `ClearedDeadOwner`, sync succeeds, index not corrupt (`repositoryHealth` OK, no auto-reset); variant: killed while holding the recovery lock, next claimer acquires it | CI |
| R2,R29 | I-E1-09 | JT/git/ProcessKillOrphanLockTest | sigkillWithPidReuseSimulated_should_StillClear_When_StartTicksDiffer | Integration | Edge | CI |
| R1,R2 | I-E1-16 | BT/git/PausedRetryNowAndDurabilityTest | retryNow_should_LeaveTryNumberUnchangedNeverAddRetries_AndFailedSyncNowShould_RestartBudget_AndRebuiltServiceShould_ReClaimWithoutPersistedPausedState | Integration (virtual time) | Triad UX-51/UX-52; the timer is in-process (`RateLimitRetry.kt:22` pattern), durability is the WorkManager periodic tick | CI |
| R1 | I-E1-10 | JT/cli/SyncMainClaimTest | cliAndApp_should_CoalesceViaOwnerAlive_InBothDirections | Integration | CLI participation | CI |
| R1,R2 | I-E1-11 | AU/git/AndroidGitRepositoryShadowWorktreeTest | locksAndRecord_should_BeReadAndWrittenUnderResolveForJGit_When_SafGraph | Integration (Robolectric) | Happy path SAF | CI |
| R1 | I-E1-12 | AU/git/WorkerVsForegroundSyncTest | worker_should_ReturnSkippedAndStartNoSecondRun_When_ForegroundSyncHoldsGate | Integration (`TestListenableWorkerBuilder`) | Entry-point: WorkManager worker vs foreground, same process | CI |
| R15 | I-E1-13 | BT/git/LargeGraphMergeReloadTest | mergeUnit_should_ReloadInBoundedChunks_AndNeverMaterializeAllPages_When_8000FilesMerged | Integration | NFR scalability (extends `LargeGraphWarmStartCrashTest` pattern; chunk <= 200, DB reads <= 100 rows) | CI |
| R25,R1 | I-E1-14 | AU/wiring/GitSyncEntryPointTest | wiredApp_should_CoalesceSecondSyncAtRuntime | Integration (`WiredAppFixture`, `ObservableEffect.assert("sync-single-flight")`) | `wiring-catalog.txt` line added by integrator | CI |
| R2 | I-E1-15 | n/a (PR evidence) | mergedManifest_should_HaveNoAndroidProcess | Integration (build artifact grep) | Task 1.1c; re-run if a library manifest adds a process | CI |
| R2,R29,R36 | D-E1-01 | PR checklist 1.8c | forceStopMidSync_should_AutoClearOrShowAmberThenRedAfterTwoRetries_NeverUnexplainedRed | Owner device | Force-stop during sync, relaunch; read `/proc/<pid>` (ENOENT=Dead, EACCES=Unknown) and `boot_id` readability on the owner's Android; export logs, find `lock verdict=` lines | OWNER |
| R1 | D-E1-02 | PR checklist 1.8c | threeQuickSyncPresses_should_YieldOneRunAndOneFollowUp | Owner device | Slow-sync window like the 21:57 UTC log (batchDeleteBlocks 7.6 s) | OWNER |

### E2 Annotator: unit and integration

| REQ | ID | Test File | Test Name | Type | Scenario | Run |
|-----|----|-----------|-----------|------|----------|-----|
| R3,R10,R30 | U-E2-01 | CT/ui/annotate/ToolPolicyTest | nextToolAfterCommit_should_ReturnSelect_When_OneShotAndAutoReturnOn_AndKeepTool_When_Locked_OrSettingOff | Unit (property) | Happy + setting-off path | CI |
| R3,R33 | U-E2-02 | BT/ui/annotate/AnnotationEditorViewModelToolTest | freshViewModel_should_StartInSelect_AndAutoReturnAfterDistanceCommit | Unit | Happy path SM2a | CI |
| R10,R33 | U-E2-03 | BT/ui/annotate/AnnotationEditorViewModelToolTest | multiStepSessionsGridRefRulerAreaPlane_should_StayArmedUntilConfirm | Unit | Edge (ADR-005) | CI |
| R33 | U-E2-04 | BT/ui/annotate/AnnotationEditorViewModelToolTest | undoRedo_should_NotRearmTool_And_NotChangeStickiness | Unit | Error/edge | CI |
| R33,R22,R23 | U-E2-05 | BT/ui/annotate/AnnotationEditorViewModelToolTest | persistFailure_should_RollbackMeasurementAndRestoreToolOnlyIfUserHasNotChosenAnother | Unit | Error path (Either `Left` from repository) | CI |
| R3,R33 | U-E2-06 | CT/ui/annotate/PlacementClassifierTest | classifyPlacement_should_ReturnDragMeasure_When_ReleaseBeyond48dpAtFitOrCloser_ElseAimPlace | Unit (property) | Happy + error | CI |
| R3,R33 | U-E2-07 | CT/ui/annotate/PlacementClassifierTest | classifyPlacement_should_ReturnAimPlace_When_ViewScaleBelowFit_AndUseIsFitOrGreaterHelperNotRawFloat | Unit | Error path; adv minor (raw float compare) | CI |
| R4,R33 | U-E2-08 | CT/ui/annotate/AnnotationViewTransformTest | defaultTransform_should_BeFit_And_isFitSymmetric_And_Floor025Stops | Unit (property) | Happy; arch R2 | CI |
| R4 | U-E2-09 | CT/ui/annotate/AnnotationViewTransformTest | slowPinchOut_should_LeaveFit_When_200EventsOfFactor09995 | Unit | NEW (adv C10: 0.001 snap makes Fit sticky; accumulate unsnapped scale) | CI |
| R4 | U-E2-10 | CT/ui/annotate/AnnotationViewTransformTest | clamp_should_CentreAxisWhenContentFits_AndKeepFarEdgeSlackShortOfViewportEdge_When_ZoomedIn | Unit (property) | Edge | CI |
| R4 | U-E2-11 | CT/ui/annotate/AnnotationPointClampTest | placePoint_should_StoreExactly0Or1_When_FingerOutsideImageOnPasteboard | Unit (property) | Happy path edge reach | CI |
| R33 | U-E2-12 | CT/ui/annotate/AnnotationHitTestScaleTest | hitTest_should_UseSame24dpScreenRadius_At025And1_AndGrabEdgeHandle | Unit (property) | Regression pin (UX-24) | CI |
| R10 | U-E2-13 | BT/ui/annotate/StatusStripHintTest | firstThreeTimesHint_should_ShowThenStop_AndPersistCountInSettings | Unit | Edge | CI |
| R3,R33 | U-E2-14 | BT/ui/annotate/AnnotationEditorViewModelToolTest | tapTapIdenticalPoints_should_RejectSecondAndKeepFirst | Unit | Error path | CI |
| R30 | U-E2-15 | AU/ui/settings/AnnotatorSettingsRowsTest | settingsRows_should_ToggleAutoReturnDefaultOnAndStickyDefaultOff | Unit (Robolectric) | Happy | CI |
| R24 | U-E2-16 | BT/audit/ScopeEscapeAuditTest | rememberCoroutineScope_should_NotFlowIntoRememberedConstructors_InNewCode | Unit (source audit) | NEW (CLAUDE.md rule; audit grep over files added here) | CI |
| R33 | I-E2-01 | AU/ui/annotate/AnnotationGestureLayerTest | dragMeasure_should_CreateOneMeasurement_And_AimPlaceUnchanged_And_SecondFingerCancels_And_OffCanvasCancelsKeepingToolArmed_And_OtherToolsPlaceOnePoint | Integration (Robolectric `performTouchInput`) | Happy path SM2a + the ADR-007 matrix; `InProgressAnnotationCanvasGestureTest` stays green | CI |
| R3 | I-E2-02 | AU/ui/annotate/AnnotationGestureLayerTest | immediateRepressAfterAutoReturn_should_NotBeLost | Integration | Edge (pointer-input restart) | CI |
| R4,R33 | I-E2-03 | AU/ui/annotate/AnnotationGestureLayerTest | systemGestureInsetZeroOnDesktop_should_NotChangeCancelPath | Integration | Edge (adv minor: inset is zero on desktop and 3-button nav) | CI |
| R4 | I-E2-04 | AU/ui/annotate/PasteboardInsetTest | outermostPixelPlacement_should_LieInsideSystemGestureInset_AtFit | Integration (fake `WindowInsets.systemGestures` 28 dp) | Edge (adv C11 earlier) | CI |
| R9,R33 | I-E2-05 | BT/ui/annotate/AnnotationExporterPasteboardTest | export_should_BeIdenticalWithAndWithoutPasteboard | Integration | Pasteboard is view-only, never exported | CI |
| R13 | I-E2-06 | JT/ui/annotate/ZoomJank12MpTest | pinchFromFitToFloorAndBack_should_KeepP95FrameTimeWithinBudget_On12MpImage // jvmTest-reason: pixels | Integration (existing jank harness, `scripts/jvm-display-check.sh`) | NFR perf harness numbers (before/after Story 2.4) | CI |
| R13,R15 | I-E2-07 | AU/ui/annotate/ZoomedOutDecodeTest | decodeAtScale025_should_BeBoundedAndNotAllocateFullBitmapTwice_On12Mp | Integration | NEW (graceful degrade, memory ceiling under `-Xmx` of test JVM) | CI |
| R33 | I-E2-10 | AU/ui/annotate/PlacementCountersLogTest | placementGestures_should_LogDragMeasureAimPlaceAndUndoAfterDragMeasureOnce | Integration | Triad gap 2: the counters Task 2.7a reads to tune `DRAG_COMMIT_DP`; no coordinates or photo path in the lines | CI |
| R13 | D-E2-01 | PR checklist 2.7a | pinchZoomOutOn12MpPhoto_should_FeelSmoothAt25Percent | Owner device | Real-phone frame time (p95 at most 16.7 ms, jank under 5 percent, PROPOSED) vs the pre-change baseline; the 20 drag-measure and 20 aim-place pass criteria on `PlacementCounters` (Task 2.7a); real gesture-inset value | OWNER |
| R33 | I-E2-08 | JT/ui/annotate/AnnotationKeyboardShortcutsTest | keepToolShortcutAndToolKeys_should_ToggleAndRearm // jvmTest-reason: desktop-only | Integration | Desktop keyboard | CI |
| R9 | I-E2-09 | JT/ui/annotate/PasteboardFrameScreenshotTest | pasteboardFrameAndSelectLabel_should_MatchGolden // jvmTest-reason: pixels | Integration (Roborazzi) | Pixel assertion | CI |

### E3 Measurements: unit and integration

| REQ | ID | Test File | Test Name | Type | Scenario | Run |
|-----|----|-----------|-----------|------|----------|-----|
| R6 | U-E3-01 | CT/calibration/ConstraintCalibratorTest | fit_should_RecoverSyntheticTruthWithinTolerance_When_InModelTiltAndNoise | Unit (property, 500 scenes) | Happy path | CI |
| R6 | U-E3-02 | CT/calibration/OutOfModelRobustnessTest | defaultTierSelection_should_BeNoWorseThanSingleScaleHeldOut_When_SceneOutsideSolverModelClass | Unit (property, per family x 300 scenes) | NEW/strengthened (anti-circularity): families the solver does NOT model: (a) points at heights above the plane, (b) two planes (door + floor), (c) 1-2 px systematic tap bias, (d) higher-order radial distortion (k2, k3) beyond the single-k model, (e) non-square pixels, (f) cropped/digitally zoomed frame (principal point off-centre, adv C9), (g) rotation of up to 90 degrees (portrait/landscape EXIF). Pass criterion per family: MAE(default) <= MAE(single-scale) * 1.02 with 95% CI; failing family blocks the tier from shipping as default | CI |
| R6 | U-E3-03 | CT/calibration/ConstraintCalibratorTest | gradientTier_should_BeIneligibleAndReportNeedsMore_When_NMinusPLessThan2 | Unit | Error path N=4 | CI |
| R6 | U-E3-04 | CT/calibration/ConstraintCalibratorTest | fit_should_ReturnScaleWithAgreementPctAndNullLoo_When_ExactlyTwoConstraints | Unit | F2 rung | CI |
| R6 | U-E3-05 | CT/calibration/ConstraintCalibratorTest | fit_should_ReturnTypedIllConditionedOrCollinear_When_ClusteredCollinearOrZeroLength | Unit | Error path, no number on degenerate input | CI |
| R6 | U-E3-06 | CT/calibration/ConstraintCalibratorTest | gradientTier_should_BeatScaleHeldOut_When_OnlyExifFocalOrNoFocalPresent | Unit | focal-free claim | CI |
| R6 | U-E3-07 | CT/calibration/GradientNoiseFalsePositiveRateTest | gradientGate_should_AcceptAtMost1Percent_On500PureNoiseScenes_At_N5_And_N6 | Unit (Monte Carlo, seeded) | NEW (adv C8i: `3 SE` at df=2 passes ~9.5% of noise (0.0955, computed); the gate uses the Student-t quantile for N - P dof with an LOO veto; threshold tightened from 5% to 1% in the Phase 4 patch per the pre-mortem) | CI |
| R6 | U-E3-08 | CT/calibration/GradientSignificanceTest | gradientResult_should_ReportTiltTooLargeAndKeepScale_When_ImpliedTiltOver10DegViaExifBound | Unit | NEW (adv C8ii; EXIF only as an order-of-magnitude bound, not a correction) | CI |
| R6 | U-E3-09 | CT/calibration/CroppedFrameRefusalTest | fit_should_WarnOrKeepScale_When_CropOrDigitalZoomIndicatedByAspectOrExif | Unit | NEW (adv C9) | CI |
| R6 | U-E3-10 | CT/calibration/ConstraintCalibratorTest | selection_should_PreferSimplerTier_When_LeaveOneOutErrorRisesDespiteLowerTrainingResidual | Unit | Edge | CI |
| R6 | U-E3-11 | CT/calibration/ConstraintCalibratorTest | fit_should_NameWorstConstraintAndImpliedRatio_When_UnitTypo; _should_FlagMixedSurfaces_When_ResidualsSplit | Unit | Error path | CI |
| R11,R34 | U-E3-12 | CT/calibration/ConstraintCalibratorTest | fit_should_ReturnLegacyBasis_When_BasisVersion0 | Unit | Error path | CI |
| R6,R34 | U-E3-13 | CT/calibration/ConstraintFitApplierTest | apply_should_FollowPrecedenceTableRow_N (one test per ADR-003 row: SCALE clears plane; GRADIENT writes CONSTRAINT_FIT plane; K writes lens k; paper plane + 3 constraints; height offset set/cleared twice keeps constraints and fit; new line/ruler/paper calibration clears fit) | Unit | Happy path, 6+ rows | CI |
| R36 | U-E3-14 | CT/calibration/HeightOffsetPlaneCompositionTest | heightOffset_should_ComposeWithConstraintFitPlane_AsPinnedBy3_2a | Unit | Gate: 3.2a pinning test runs before 3.3c in wave order (arch R6); CI fails the 3.3c wave if the test is absent | CI |
| R6 | U-E3-31 | CT/calibration/GradientLooVetoTest | gradient_should_BeRejected_When_TTestPassesButLeaveOneOutDoesNotBeatScaleByMargin | Unit | NEW (Phase 4 patch, pre-mortem P1-5: LOO can veto) | CI |
| R6 | U-E3-32 | CT/calibration/GradientDefaultOffTest | tierSelection_should_ReturnScaleWithLoo_When_GradientDefaultOffAndSettingOff | Unit | NEW (pre-mortem P1-5: GRADIENT ships default-off until Story 3.8a) | CI |
| R11,R34 | U-E3-33 | BT/db/sidecar/GoldenSidecarMeasurementIdentityTest | existingMeasurementDistanceMeters_should_BeByteIdentical_After_LoadSaveAndHeightOffsetSetClearTwice | Integration (golden v0.113.3 sidecar) | NEW (pre-mortem P2-9: the `setGeometry` predicate change must not alter stored values) | CI |
| R6 | U-E3-15 | CT/calibration/MeasurementAdvisorTest | advisor_should_PrintScaleFromNKnownDistances_AndSkipCentreCheck_When_ConstraintFitPlane | Unit | Happy | CI |
| R5 | U-E3-16 | CT/ui/annotate/MeasurementValueEditTest | parse_should_ReturnMeters_For_5ft3in_AndRejectEmptyZeroNegativeNonNumeric | Unit (+ property round-trip parse/format) | Happy + error (UX-28) | CI |
| R5 | U-E3-17 | BT/ui/annotate/MeasurementValueEditTest | submitChangeLabelOnly_should_StoreOverriddenWithOriginal_AndLeaveGeometryUnchanged | Unit | Happy path SM3a | CI |
| R5,R33 | U-E3-18 | BT/ui/annotate/MeasurementValueEditTest | recalibration_should_SkipOverriddenMeasurements_AndRecomputeOthers | Unit | Edge | CI |
| R5 | U-E3-19 | BT/ui/annotate/MeasurementValueEditTest | dragEndpointOfOverride_should_ConfirmAndRevertPoints_When_Declined | Unit | Error path | CI |
| R5,R22 | U-E3-20 | BT/ui/annotate/MeasurementValueEditTest | submit_should_RollbackAndKeepInput_When_RepositoryReturnsLeft | Unit | Error path | CI |
| R21,R6 | U-E3-21 | BT/ui/annotate/AnnotationEditorViewModelCalibrationTest | confirmConstraintCalibration_should_RunApplierOnceStoreFitAndLogTierNResidual | Unit | Happy; log line `calibration fit tier=.. n=..` | CI |
| R6,R33 | U-E3-22 | BT/calibration/CalibrationHistoryTest | undo_should_RestoreConstraintsFitAndPriorBasis | Unit | Happy/Error | CI |
| R6 | U-E3-23 | BT/ui/annotate/AnnotationEditorViewModelCalibrationTest | collinearFailure_should_FallBackToScaleTier_AndNeverBlockMeasuring | Unit | Error path | CI |
| R6 | U-E3-24 | CT/calibration/CalibrationLadderPresenterTest | pill_should_ShowLadderTextAndIcon_For_0_1_2_3Plus_Legacy_AndBasis0 | Unit | Happy + legacy never "Not calibrated" (UX-31..33) | CI |
| R6 | U-E3-25 | CT/calibration/FocalSourceResolverTest | resolver_should_RankSourcesAndNeverEnableRefinement_When_ExifOnlyOrProfile | Unit | Optional refinement | CI |
| R6,R8 | U-E3-26 | CT/calibration/FocalSourceResolverTest | profileMatch_should_PickUltraWideProfile_When_LensRecordFocal2_2mm_AndEqualMakeModelOtherwise_When_NoRecord | Unit | Cross-epic E4->E3 | CI |
| R34 | I-E3-01 | BT/db/sidecar/SidecarCompatTest | oldV0113SidecarFixture_should_LoadWithEmptyConstraintsAndComputedValues_AndResaveIdentically | Integration | Backward compat | CI |
| R31,R34 | I-E3-02 | BT/db/sidecar/SidecarCompatTest | frozen0113Decoder_should_ReadNewSidecarAndDbJsonAsCalibrated_WithOverridesLostOnlyOnResave | Integration | Downgrade claim made precise; SCALE fit decodes as `MANUAL_REFERENCE` | CI |
| R34 | I-E3-03 | BT/db/sidecar/SidecarCompatTest | unknownKeyFutureThing_should_SurviveLoadAndSave | Integration | Tolerant reader | CI |
| R11 | I-E3-04 | BT/db/sidecar/SidecarVersionTest | schemaVersion_should_BeTwoOnlyWhenV2DataPresent | Integration | Edge | CI |
| R11,R23 | I-E3-05 | BT/db/MeasurementValueMigrationTest | migration_should_be_reversible | Migration | Run `MigrationRunner.applyAll()` on a fresh and on an upgraded DB: `value_source` (default `'COMPUTED'`) and `value_original_meters` exist, old rows read COMPUTED. "Down": plan has no column drop by design, so reversibility = the pre-change `insertMeasurementAnnotation`/select statements (frozen copy) still read and write the table with the extra columns and defaults apply (rollback leaves old rows valid) | CI |
| R23 | I-E3-06 | BT/db/MigrationRunnerSchemaSyncTest | (existing) schemaSync_should_PassUnchanged_When_NoNewTable | Integration | Regression pin: no new table, so unaffected | CI |
| R5,R23 | I-E3-07 | BT/repository/SqlDelightMeasurementAnnotationRepositoryTest | override_should_PersistAndSurviveRecalibration_AcrossReload | Integration (real SQLDelight, write via actor) | Happy; sealed `Computed`/`Overridden` mapping; illegal COMPUTED+original row coerces | CI |
| R23 | I-E3-08 | BT/db/GeneratedSqlDelightSyncTest | generatedSourcesCommitted_should_MatchRegenerated (CI job "SQLDelight generated sources") | Integration | Constraint check | CI |
| R23,R25 | I-E3-09 | BT/db/WriteGateAuditTest | newMeasurementInsertStub_should_BeAnnotatedDirectSqlWrite | Integration (existing audit) | Constraint | CI |
| R6,R34 | I-E3-10 | BT/ui/annotate/CalibrationEndToEndTest | addThirdKnownDistance_should_UpdateFitPillAndMeasurements_WithInMemoryRepos | Integration | Happy path E3 | CI |
| R6 | I-E3-11 | CT/calibration/AccuracyHarnessTest | harness_should_ReproducePublishedBaselineWithinTolerance_When_CorrectionsOff | Integration (golden scene) | Story 3.1 | CI |
| R6,R36 | I-E3-12 | JT/calibration/RealPhotoHoldOutStudyTest | holdOut_should_ShowShippedDefaultTierLowerMaeThanSingleScale_OnOwnerRealPhotos | Integration (reads `accuracy-fixtures/real/*.json`: pixel taps + tape-measured truth) | NEW: the only evidence for SM3. Design: (1) set A (door-frame + >= 2 photos) tunes N and thresholds; set B (>= 2 other photos, never seen by tuning) decides; (2) report MAE on withheld distance via leave-one-out per photo AND the A->B transfer; (3) pass = default-tier MAE < single-scale MAE on set B and no photo worse by > 10%. Fixtures are owner-supplied and not committed publicly; the test uses `Assume` only to keep CI green when absent but **`I-E3-13` makes absence fail the SM3 verdict** | OWNER |
| R6,R36 | I-E3-13 | BT/calibration/AccuracyStudyVerdictTest | accuracyStudyDoc_should_EndWithExactlyOneOfTheTwoVerdictsAndListAtLeast5RealPhotosWith6DistancesEach | Integration (doc audit) | NEW: Success Metric 3 cannot pass on synthetic data alone; verdict "SM3 met" is rejected if the evidence table has < 5 real photos, < 6 tape-measured distances each, the door-frame photo is missing, or set B is empty | CI |
| R6 | D-E3-01 | PR checklist 3.8a | ownerDoorFramePhoto_should_ShowLowerMaeThanSingleScale_AfterCalibratingN | Owner photos | Tyler supplies photos + tape ground truth (named wall-clock blocker); sets N and thresholds | OWNER |

### E4 Camera: unit and integration

| REQ | ID | Test File | Test Name | Type | Scenario | Run |
|-----|----|-----------|-----------|------|----------|-----|
| R7,R35 | U-E4-01 | CT/camera/LensClassifierTest | classify_should_ReturnUltraWideWideTeleOrderedByFocal_When_OwnerPhoneFixture | Unit (recorded `CameraCharacteristics` JSON) | Happy path SM4a | CI |
| R7,R35 | U-E4-02 | CT/camera/LensClassifierTest | classify_should_ListZoomStops_And_FlagDigitalAboveLongestFocal_When_OneLogicalCamera06To8 | Unit | OEM-hidden lenses | CI |
| R7,R35 | U-E4-03 | CT/camera/LensClassifierTest | classify_should_ExcludeFrontAndCollapseDuplicates | Unit | Error path | CI |
| R7,R35 | U-E4-04 | CT/camera/LensClassifierFixtureMatrixTest | classify_should_ProduceExpectedChips_For_EachOemFixtureShape | Unit | NEW (E4 CameraCharacteristics fixtures): hand-built fixtures for (a) Pixel-style logical multi-camera with physical ids 2,3,4 and zoom 0.5-8, (b) Samsung-style separate back ids exposed, (c) hidden-ultrawide logical camera with only a zoom range, (d) single lens no zoom range (no chips), (e) characteristics missing `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` or `SENSOR_INFO_PHYSICAL_SIZE` ("n/a", never throws), (f) two back cameras with identical focal (depth/macro duplicates), (g) 35 mm-equivalent computed from sensor size matches within 5% | CI |
| R7 | U-E4-05 | CT/camera/LensClassifierTest | classify_should_BeOrderIndependentAndNeverContainFront_ForAnyShuffle | Unit (property) | Property | CI |
| R7,R14 | U-E4-06 | AU/camera/CameraSessionTest | fiveTapsIn100ms_should_RunOnlyLastBindAfterInFlight_AndDisableShutterDuringRebind | Unit (fake backend, virtual time) | Happy path; also virtual-time bound: switch completes within the fake's configured 300 ms and the state machine adds no extra delay (the ~1 s target itself is `D-E4-03`) | CI |
| R7,R35 | U-E4-07 | AU/camera/CameraSessionTest | failedBind_should_KeepPreviousLensAndDisableChipWithReason_AndLogResultFail | Unit | Error path | CI |
| R35 | U-E4-08 | AU/camera/CameraSessionTest | physicalIdLens_should_AppearOnlyIfBindTested_ElseZoomStops | Unit | Error path | CI |
| R7 | U-E4-09 | AU/camera/CameraSessionTest | session_should_NeverCallUnbindAll_AndLeavePreviewBinderAndFrameSourceBound | Unit | Contract | CI |
| R8 | U-E4-10 | AU/camera/CaptureLensRecordTest | record_should_UseCaptureResultAtShutter_NotUiChip_When_PreviewResultAvailable_AndSelectedLensWhen_Not | Unit | Both record sources | CI |
| R8 | U-E4-11 | AU/camera/ExifOrientationFixerTest | fixOrientation_should_PreserveFocalLength_FocalLengthIn35mm_Make_Model_DateTime_LensModel_AfterReencode | Unit (Robolectric, real JPEG fixture) | Known defect fixed | CI |
| R8,R11 | U-E4-12 | BT/db/sidecar/SidecarLensRecordTest | lensRecord_should_RoundTrip_AndOldSidecarReadNull_AndCoexistWithCalibrationKeys | Unit | Happy/compat | CI |
| R12 | U-E4-13 | AU/camera/CameraControlsStateTest | flashCycle_should_StepOffAutoOnPerPress_AndPinchShouldHighlightNearestOpticalChipOnly | Unit | Happy | CI |
| R26 | U-E4-14 | CT/camera/CapabilityProbeFormatTest | probeLine_should_TolerateMissingCharacteristicsAndExportOnlyMakeModel | Unit | NEW (S9: no serial or location in exported fixture; per-field failure never aborts) | CI |
| R7 | I-E4-01 | AU/camera/CameraViewfinderChipsTest | dialog_should_ShowThreeChipsWith1xSelected_When_CatalogReturnsThreeLenses | Integration (Robolectric, fake catalog) | Happy path | CI |
| R7,R35 | I-E4-02 | AU/camera/CameraViewfinderChipsTest | dialog_should_ShowOnlyZoomStops_When_OneLens_AndNoChips_When_NoZoomRange | Integration | Fallback | CI |
| R7 | I-E4-03 | AU/camera/CameraViewfinderStatesTest | dialog_should_ShowRetryableDenialPermanentDenialAllLensesFailedAndCaptureFailedScreensWithExits | Integration | Error paths (F12) | CI |
| R7 | I-E4-04 | AU/camera/CameraViewfinderStatesTest | dialog_should_RestoreLensAndKeepShutterDisabled_When_ConfigChangeDuringRebind | Integration (Robolectric `recreate`) | Rotation/fold | CI |
| R8 | I-E4-05 | AU/camera/ImageImportServiceLensTest | import_should_SnapshotProfileIdIntoLensCalibration_When_RecordMatchesProfile | Integration | Used at import | CI |
| R7 | I-E4-06 | BT/audit/CameraBinderAuditTest | defaultBackCameraLiteral_should_AppearOnlyInCameraXBackend | Integration (source audit) | Story 4.6 | CI |
| R25 | I-E4-07 | AU/wiring/CameraLensEntryPointTest | wiredApp_should_ListFakeBackendLenses (`ObservableEffect.assert("camera-lens-list")`) | Integration | Wiring catalog | CI |
| R7,R36 | D-E4-01 | PR checklist 4.1b | probe_should_ListEveryRearIdWithPhysicalBindResultsAndCaptureKeyAvailability_OnOwnerPhone | Owner device | Opens R36 gate: lens ids, `setPhysicalCameraId` binds, `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` delivery, EXIF `FocalLength` per lens; sanitized fixture checked in | OWNER |
| R7,R8 | D-E4-02 | PR checklist 4.7b | onePhotoPerLens_should_ShowChipListMatchingProbe_AndSidecarRecordAndExifTagsMatchTheLens | Owner device | SM4a+SM4b acceptance | OWNER |
| R14 | D-E4-03 | PR checklist 4.7b | lensSwitch_should_CompleteWithinAbout1s_NoBlackFrame | Owner device | NFR; stopwatch from logs `camera bind ... ms=` | OWNER |
| R35 | D-E4-04 | PR checklist 4.7b | rotationMidBind_permissionRevokedWhileOpen_foldUnfold_should_NeverShowBlackOrUnderBars | Owner device | Fold/rotation | OWNER |
| R7 | D-E4-05 | Pixel_Fold AVD run of probe | avdProbe_should_RecordWhetherEmulatorExposesExtraLenses | Owner/dev machine | Open Question 6; informational | OWNER |

### Process and constraint checks

| REQ | ID | Check | Type | Run |
|-----|----|-------|------|-----|
| R25 | I-P-01 | `TestPlacementAuditTest`/`TestHygieneAuditTest` stay green: no new `Thread.sleep`, `GlobalScope`, uncancelled `Dispatchers.IO` scope, real-clock `delay`; every new jvmTest compose file has `// jvmTest-reason:`; baselines only shrink | Integration (existing audits) | CI |
| R25 | I-P-02 | `WiringCompletenessTest` passes with all new seam impls (`RepoOpGate`, `CameraSession`, `CameraXLensCatalog`, `ProcessLiveness`, `LockFiles`) constructed in a composition root or dated allow-list line; stale lines removed | Integration | CI |
| R25 | I-P-03 | Registration check: each new test class above appears in `AllBusinessTests`/`AllJvmTests`/`AllAndroidUnitTests` and each commonTest class has a BUILD.bazel entry (script diff in the integrator step) | Integration | CI |
| R28 | I-P-04 | PRs opened with `gh pr create --draft`; no merge or release run by the agent; one PR per epic (also R31) | Manual process | OWNER |
| R31 | I-P-05 | Revert-independence: for each epic, `git revert` of its commits on a scratch branch compiles (`bazel build //kmp:desktop_app`) | Manual/CI scratch | CI |

## UX Acceptance Tests

Tool column: **R** = Robolectric `androidUnitTest` (CI, headless); **P** = pure presenter/unit test (CI); **J** = `jvmTest` pixels or desktop-only (CI behind `scripts/jvm-display-check.sh`); **M** = manual checklist on the owner's phone (OWNER). Each criterion has exactly one primary row; where a criterion needs both a CI and a phone check the second is noted in Steps.

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| X-01 UX-01 running neutral, no retry control, no red | AU/ui/components/SyncStatusBadgeTest | runningRow_should_ShowSyncingNeutralWithNoRetryAndNoErrorColor_When_RunInFlight | R | Set `inFlight`; assert text "Syncing...", no node with error color, no retry node |
| X-02 UX-02 second press: no state change, announce "Already syncing" | AU/ui/components/SyncStatusBadgeTest | secondPress_should_AnnounceAlreadySyncingAndStartNoRun | R | Press twice; assert one polite live-region event, run count 1, state unchanged; also manual: TalkBack on phone (M) |
| X-03 UX-03 queued follow-up line | AU/ui/components/SyncStatusBadgeTest | followUpLine_should_ShowOnlyWhenManualPressArrivedMidRun_AndOneFollowUpRuns | R | Press mid-run; line visible; run ends; exactly one extra run; line gone when none queued |
| X-04 UX-04 amber text, >= 4.5:1, >= 48 dp, opens Details | AU/ui/components/SyncStatusBadgeTest | pausedRow_should_UseWarningTokenWith45ContrastLightAndDarkAnd48dpAndOpenDetails | R | Verdict OwnerUnknown; compute contrast of token pair in both themes; assert height; click opens sheet, no sync started |
| X-05 UX-05 after two failed retries: red + Sync now + Details | AU/ui/components/SyncStatusBadgeTest | failedRow_should_ShowCouldntSyncWithTwo48dpActions_AfterTwoFailedRetries | R | Drive `PausedRetryPolicy` with virtual time 30 s then 2 min; assert copy and actions |
| X-06 UX-06 Remove lock only in Details; confirm focus Cancel; nothing deleted before Remove | AU/ui/components/SyncStatusSheetTest | removeLock_should_BeInDetailsOnlyAndConfirmDefaultFocusCancelAndDeleteNothingBeforeRemove | R | NoRecord verdict; row has no Remove; dialog text includes "Only if no other sync or git app is running"; fake `LockFiles` shows 0 deletes until Remove |
| X-07 UX-07 disabled Sync button reason + stateDescription | AU/ui/components/SyncStatusSheetTest | disabledSyncButton_should_ShowReasonAndStateDescription_When_RunInFlight | R | Assert text "A sync is already running" and semantics |
| X-08 UX-08 no jargon on one-tap row | BT/git/SyncStatusSheetModelTest | rowText_should_NotContainIndexLockOrStale_ForAnyState | P | Property over all states |
| X-09 UX-09 every sync state has an exit | BT/git/SyncStatusSheetModelTest | everyState_should_HaveBackCloseOrVisibleAction | P | Walk the enum of states; assert exit action present |
| X-10 UX-10 one tap + one drag = measurement, Select active, pan works | AU/ui/annotate/OneShotToolFlowTest | tapDistanceThenDrag_should_CreateMeasurementReturnToSelectAndPanWithNoExtraTap | R | Fresh editor; tap Distance; drag A->B past 48 dp; assert measurement, Select selected, one-finger drag pans. Phone repeat (M) |
| X-11 UX-11 armed visible twice, announced, four disarm paths | AU/ui/annotate/ToolDisarmTest | armedState_should_ShowChipAndStripAndDisarmViaChipSelectBack_AndNotViaSecondFingerOrOffCanvasCancel | R | Arm; assert both; each disarm path returns to Select with no measurement |
| X-12 UX-12 tap-tap same as drag; discard first point | AU/ui/annotate/ToolDisarmTest | tapTap_should_CreateSameMeasurementAsDrag_AndDiscardFirstPointWithoutCreating | R | Tap A, strip "Point A placed. Tap point B", tap B; and variant discarding A |
| X-13 UX-13 second finger cancels in-flight, tool stays armed | AU/ui/annotate/AnnotationGestureLayerTest | secondFinger_should_CancelPlacementAndKeepToolArmed | R | Multi-pointer touch input |
| X-14 UX-14 Keep tool 48 dp toggle; long-press/double-tap; badge + stateDescription; reset on leaving | AU/ui/annotate/KeepToolToggleTest | keepTool_should_LockViaToggleLongPressAndDoubleTap_ShowBadgeAndResetOnExit | R | Assert `stateDescription` "locked", size >= 48 dp, reset after leaving editor |
| X-15 UX-15 Role.RadioButton + live region "Distance 90 cm added, returned to Select" | AU/ui/annotate/ToolbarSemanticsTest | chips_should_BeRadioButtonsInSelectableGroup_AndCommitAnnouncementShouldBeLiveRegion | R | Semantics tree assertions |
| X-16 UX-16 contrast >= 4.5:1 active/inactive; Reference "Calibrate first" | AU/ui/annotate/ToolbarContrastTest | selectLabel_should_HaveContrastAtLeast45AgainstToolbar_AndReferenceExposeCalibrateFirst | R | Compute from `TOOLBAR_BACKGROUND_COLOR`; disabled description; tap shows strip hint |
| X-17 UX-17 all targets >= 48 dp, no overlap with FAB/system bars | AU/ui/annotate/ToolbarLayoutBoundsTest | chipsAndLockToggle_should_Be48dpAndNotOverlapMicFabOrSystemBars | R | Bounds assertions; FAB absent in editor (also X covers 2.5c) |
| X-18 UX-18 floor 0.25, minus button disabled only there, Fit enabled off-Fit | AU/ui/annotate/ZoomControlsTest | zoomOut_should_StopAt25PercentWithMinusDisabledOnlyThere_AndFitEnabledOffFit | R | Repeated minus-button taps and pinch; at the floor the whole image is visible, centred, width at most 25% of the viewport (no "25% of the photo stays visible" invariant, ADR-006) |
| X-19 UX-19 outermost pixel by dragging into pasteboard, stored 0/1 | AU/ui/annotate/PasteboardPlacementTest | dragIntoPasteboard_should_StoreExactly0Or1 | R | Place with finger 40 dp outside image |
| X-20 UX-20 controls/status do not intersect photo bounds at Fit; none over top 56 dp | AU/ui/annotate/EditorLayoutBoundsTest | zoomControlsAndStatusText_should_NotIntersectPhotoBoundsAtFit | R | Bounds rect intersection on 360 dp and 600 dp widths |
| X-21 UX-21 no "coming in a future update" text | AU/ui/annotate/EditorLayoutBoundsTest | depthStatus_should_BeAbsent_When_OnEstimateNull | R | `onNodeWithText` absence |
| X-22 UX-22 zoom controls 48 dp, "Zoom 40 percent", frame 3:1 | AU/ui/annotate/ZoomControlsTest | zoomControls_should_Be48dpExposeValueAndFrameHave3to1Contrast | R | Semantics + color math |
| X-23 UX-23 nudge pad moves a point to within 1 px without dragging | AU/ui/annotate/NudgeMoveTest | handleTapThenNudge_should_MoveByOnePxWithoutDragAndAnnounceCoordinate | R | Tap handle in Select; 1 px arrows; arrows 48 dp; at edge "At image edge" |
| X-24 UX-24 hit radius >= 24 dp at 25% and 100% | CT/ui/annotate/AnnotationHitTestScaleTest | hitRadius_should_Be24dpOrMore_At025And100Percent | P | Same as U-E2-12 |
| X-25 UX-25 label tap opens editor in one tap; "Edit value" action | AU/ui/annotate/ValueEditorSheetTest | labelTap_should_OpenEditorInOneTap_AndExposeEditValueAction | R | Label hit area >= 48 dp |
| X-26 UX-26 both meanings with consequence; preselect rules | AU/ui/annotate/ValueEditorSheetTest | editor_should_PreselectKnownDistanceWhenUncalibrated_AndRequireChoiceOnFirstEditWhenCalibrated_AndPreselectLastChoiceOnLaterEdits | R | Apply disabled until choice when calibrated |
| X-27 UX-27 preview count + scale before/after; snackbar Undo restores all | AU/ui/annotate/ValueEditorSheetTest | apply_should_ShowPreviewThenSnackbarUndoRestoringEverything | R | "3 other measurements will change"; Undo |
| X-28 UX-28 invalid input disables Apply keeps input; 5'3" parses | AU/ui/annotate/ValueEditorSheetTest | invalidInput_should_DisableApplyKeepInputAndShowReason | R | Empty/0/negative/text; `5'3"` -> 160 cm |
| X-29 UX-29 edited marker pencil + "edited"; drag asks confirm | AU/ui/annotate/ValueEditorSheetTest | labelOnlyValue_should_ShowPencilAndEditedInAccessibleName_AndConfirmOnEndpointDrag | R | Semantics + dialog |
| X-30 UX-30 every editor error has message + exit | AU/ui/annotate/ValueEditorErrorStatesTest | everyEditorError_should_ShowMessageAndExit | R | Walk 6.3 table: empty, ratio, zero-length, solver failure, save fail, dismiss |
| X-31 UX-31 pill >= 48 dp opens sheet in one tap for 0/1/2/3+ | AU/ui/annotate/CalibrationPillTest | pill_should_Be48dpAndOpenKnownDistancesSheetInOneTap_ForEachCount | R | 4 counts |
| X-32 UX-32 pill ladder text + icon, not colour only | AU/ui/annotate/CalibrationPillTest | pillText_should_MatchLadderWithIconForEachCount | R | Compare strings to ux.md 7.1 |
| X-33 UX-33 legacy calibration never "Not calibrated" | CT/calibration/CalibrationLadderPresenterTest | legacyMethods_should_KeepMethodLabelNeverNotCalibrated | P | BLE, manual ref, ticks, paper, EXIF |
| X-34 UX-34 row detail; residual >= 3x mean and > 3% flagged; Use anyway | AU/ui/annotate/KnownDistancesSheetTest | worstRow_should_BeFlaggedWithUseAnyway | R | Fixture constraints |
| X-35 UX-35 third distance in <= 4 steps and row listed | AU/ui/annotate/KnownDistancesSheetTest | addThirdDistance_should_TakeAtMostFourStepsAndReturnToSheetWithRow | R | Draw, drag, type, Apply; assert `pendingKnownDistance` round trip; Cancel removes the line and leaves no undo entry |
| X-36 UX-36 delete shows count, Undo restores | AU/ui/annotate/KnownDistancesSheetTest | deleteRow_should_ShowAffectedCountAndUndoRestoreListAndFit | R | Snackbar Undo |
| X-37 UX-37 "lens distortion" never a headline | AU/ui/annotate/KnownDistancesSheetTest | strings_should_NotContainLensDistortionHeadline | R | Text scan of pill and sheet |
| X-38 UX-38 collinear shows hint, never blocks | AU/ui/annotate/KnownDistancesSheetTest | collinearOnly_should_ShowHintAndNotBlockMeasuring | R | Hint node; measuring still works |
| X-39 UX-39 chips only for exposed lenses; digital labelled | AU/camera/CameraViewfinderChipsTest | chips_should_ShowOnlyExposedLensesAndLabelDigitalStops | R | Fixture-driven; no invented chips; phone cross-check vs probe (M, D-E4-02) |
| X-40 UX-40 chip 48 dp, announces name/ratio/focal/selected, default 1x or last used | AU/camera/CameraViewfinderChipsTest | chip_should_Be48dpAndAnnounceNameRatioFocalSelected | R | Content description "0.5x ultra-wide, 13 millimetres equivalent" |
| X-41 UX-41 previous frame held, shutter disabled in rebind, five taps end on last | AU/camera/CameraSessionTest | switching_should_HoldPreviousFrameDisableShutterAndEndOnLastOfFiveTaps | R | Fake backend; black frame verified on phone (M, D-E4-04) |
| X-42 UX-42 failed lens inline message, previous preview kept, chip disabled with reason | AU/camera/CameraViewfinderStatesTest | failedLens_should_ShowInlineMessageKeepPreviewAndDisableChip | R | "<Lens> isn't available on this device right now" |
| X-43 UX-43 flash Off/Auto/On one step per press with text + announcement | AU/camera/CameraControlsStateTest | flashButton_should_CycleOneStepPerPressWithTextAndAnnouncement | R | 3 presses |
| X-44 UX-44 permission denied screens | AU/camera/CameraViewfinderStatesTest | permissionDenied_should_OfferAllowOrOpenSettingsPlusGalleryAndClose | R | Permanent vs retryable |
| X-45 UX-45 rotation/fold keeps lens, nothing under bars | AU/camera/CameraViewfinderStatesTest | rotation_should_KeepLensAndAvoidSystemBars | R | Robolectric `recreate`; real fold (M, D-E4-04) |
| X-46 UX-46 ultra-wide hint appears and is dismissible | AU/camera/CameraViewfinderChipsTest | ultraWideHint_should_AppearAndDismiss | R | Select ultra-wide |
| X-47 UX-47 every error/empty state has an exit or automatic recovery | BT/ux/ExitPathAuditTest | everyStateInUxTables_should_HaveExitOrAutoRecovery | P | Iterates every state value of the presentation models (`SyncStatusSheetModel`, camera dialog state, value-editor errors, known-distances presenter, tool strip) and asserts non-empty `exits` (Task 1.8a); remaining purely visual exits walked once on the phone (M) |
| X-48 UX-48 all new status/error text exposed to TalkBack, not toast-only | BT/audit/NewCodeAuditTest | newStatusAndErrorText_should_UseLiveRegionOrFocus_AndNeverToastOnly | P | Source audit for `Toast`/`Snackbar` without semantics + Robolectric live-region assertions in X-02/X-15/X-42; TalkBack pass on phone (M) |
| X-49 UX-49 contrast >= 4.5:1 text, 3:1 non-text, light and dark | AU/ui/ThemeContrastTest | newTokensAndElements_should_MeetContrastInLightAndDark | R | Computes from color tokens (warning pair, Select label, frame) |
| X-50 UX-50 animations respect remove-animations | AU/ui/annotate/ReducedMotionTest | crossfadeAutoReturnPulseAndZoomToFit_should_BeDisabled_When_AnimationsOff | R | Set `ANIMATOR_DURATION_SCALE` 0 |
| X-51 UX-51 Retry now enabled while retry pending, does not consume the budget, coalesces | AU/ui/components/SyncStatusSheetRetryNowTest | retryNow_should_RunExtraAttemptLeaveTryNumberUnchangedAndNeverReachFailed | R | Paused with pending retry; press five times; tries shown unchanged, state never Failed from presses; with a run holding the gate it coalesces |
| X-52 UX-52 paused state not persisted; overdue retry runs once | BT/git/PausedRetryNowAndDurabilityTest | rebuiltService_should_HaveNoPausedStateAndReClaim_And_OverdueRetryRunsOnceOnFirstCollection | P | Virtual time; simulated process death |
| X-53 UX-53 Pan pad and custom actions | AU/ui/annotate/AnnotatorLayoutTest | panPad_should_MoveViewBy25PercentDisableAtEdgeAndHideAtOrBelowFit | R | Scale 2.0; arrows 48 dp outside the photo; TalkBack custom actions "Pan left/right/up/down" |
| X-54 UX-54 TalkBack can place a point | AU/ui/annotate/AnnotationA11yActionsTest | crosshairCustomAction_should_PlacePointsAndAnnounceCoordinatesAndLength | R | Invoke "Place point with crosshair"; nudge; Place point twice; announcements asserted |
| X-55 UX-55 chip custom actions Keep/Release tool | AU/ui/annotate/AnnotationA11yActionsTest | toolChip_should_ExposeKeepToolAndReleaseToolCustomActions | R | Invoke action; `stickiness` toggles |
| X-56 UX-56 camera initial-bind loading state | AU/camera/CameraViewfinderStatesTest | firstBind_should_ShowStartingCameraWithShutterAndChipsDisabled_AndFailAfter5s | R | Fake backend with no frame |
| X-57 UX-57 flash unavailable disabled with reason | AU/camera/CameraControlsStateTest | noFlashUnit_should_ShowDisabledFlashWithReasonAndSkipCycle | R | Fixture without flash |
| X-58 UX-58 Known distances empty state | AU/ui/annotate/KnownDistancesSheetTest | zeroRows_should_ShowExplanationAndBothActionsNeverAnEmptyList | R | Zero constraints; legacy calibration heading variant |
| X-59 UX-59 pinch-to-chip hysteresis | AU/camera/CameraViewfinderChipsTest | pinchAtMidpoint_should_ChangeHighlightAtMostOnceAndNeverRebind | R | Five alternating micro-movements |
| X-60 UX-60 hint dismissal persists | AU/camera/CameraViewfinderChipsTest | dismissedUltraWideHint_should_NotReappearAfterReopenOrRestart | R | Settings flag |
| X-61 UX-61 Undo snackbar duration | AU/ui/annotate/ValueEditorSheetTest | undoSnackbar_should_BeIndefiniteUnderTouchExplorationAndLongOtherwise | R | Toggle accessibility service in Robolectric |
| X-62 UX-62 second row at 360 dp, 200% font, RTL | AU/ui/annotate/AnnotatorLayoutTest | secondRow_should_NotClipOverlapOrShrinkBelow48dp_At360dpFontScale2AndRtl | R | `w360dp-h640dp`, `fontScale=2.0`, `ldrtl` qualifiers; bounds assertions |
| X-04b UX-04 live region on entering Paused/Failed | AU/ui/components/SyncStatusBadgeTest | enteringPausedAndFailed_should_AnnounceOncePolitelyAndNotOnRetryTicks | R | Drive Paused then two retry ticks then Failed; assert exactly one announcement per entry |
| X-63 UX-63 strip priority; strip and pan row together | AU/ui/annotate/StatusStripTest | zoomedTapTap_should_ShowPromptWithCancelInStripAndPanRowAtTheSameTime_AndErrorOutranksPrompt | R | Scale 2.0, Pan on, point A placed; then inject an error; lower message returns after it clears (pure order in `EditorChromePolicyTest`: CT/ui/annotate) |
| X-64 UX-64 Back disarms first, then leaves; predictive back | AU/ui/annotate/StatusStripTest | back_should_DismissModalThenDisarmThenLeave_AndPredictiveGestureShouldCommitSameStepAndAbandonedGestureChangeNothing | R | Armed tool with point A: Back once leaves editor open in Select; two unfinished points ask discard; Select: existing leave check; use the Robolectric back dispatcher |
| X-65 UX-65 visible Cancel for pending point A | AU/ui/annotate/StatusStripTest | pendingPointA_should_ShowFortyEightDpCancelThatDiscardsAAndKeepsToolArmed | R | Place A; press Cancel; assert no point, tool armed, prompt restored |
| X-66 UX-66 editor scope | AU/ui/annotate/ValueEditorScopeTest | distanceOpensEditor_AreaAndAngleSelectWithMessageAndNoEditAction_LabelKeepsTextEdit | R | Tap each measurement kind; assert sheet vs strip message "Only distance values can be edited"; no "Edit value" action on Area/Angle |
| X-67 UX-67 unit chip is entry unit only | AU/ui/annotate/MeasurementValueEditorErrorStatesTest | editorUnitChip_should_SetEntryUnitOnlyAndNeverChangeDisplayUnitOrStoredMetres | R | Chip ft, display cm, type 2.5: stored 0.762 m, toolbar still cm; typed `5'3"` wins |
| X-68a UX-68 first toolbar row at 360 dp, 200%, RTL | AU/ui/annotate/AnnotatorLayoutTest | firstRowSevenChips_should_WrapWithoutClippingOrShrinkBelow48dp_At360dpFontScale2AndRtl | R | `w360dp`, `fontScale=2.0`, `ldrtl`; bounds assertions |
| X-68b UX-68 sync row and sheet | AU/ui/components/SyncStatusTextScaleTest | syncRowAndSheet_should_WrapAndKeepPrimaryActionReachable_At360dpFontScale2AndRtl | R | Paused and Failed states; no horizontal scroll |
| X-68c UX-68 value editor sheet | AU/ui/annotate/ValueEditorTextScaleTest | valueEditor_should_WrapAndPinCancelApply_At360dpFontScale2AndRtl | R | Sheet scrolls vertically; targets >= 48 dp |
| X-68d UX-68 Known distances rows | AU/ui/annotate/KnownDistancesSheetTest | rows_should_GrowAndWrapNames_At360dpFontScale2AndRtl | R | Long names; delete stays 48 dp |
| X-68e UX-68 camera chips and top bar | AU/ui/components/CameraDialogStatesTest | chipsAndTopBar_should_WrapAndKeepTargets_At360dpFontScale2AndRtl | R | Secondary focal line wraps or folds into content description |
| X-69 UX-69 one sync action vocabulary | BT/git/SyncStatusSheetModelTest | startLabelShouldBeSyncNowAndRetryNowOnlyInPausedAndTryAgainNever_ForEveryState | P | Property over all states; plus Failed "Sync now" restarts the budget (PausedRetryNowAndDurabilityTest) |
| X-70 UX-70 voice hint three times | AU/ui/annotate/VoiceHintTest | idleStrip_should_ShowVoiceHintFirstThreeOpensOnlyAndLoseToHigherPriorityMessages | R | Open editor four times; Settings counter |

All 70 UX criteria (UX-01..UX-50, UX-51..UX-62 added in triad repair 1, UX-63..UX-70 in triad repair 2) have at least one row above (UX-04 has two: X-04, X-04b; UX-68 has five: X-68a..e). The UNVERIFIED contrast and size claims of ux.md section 1 are measured by X-04, X-16, X-17, X-22, X-31 and X-49 (first run in Tasks 1.7b, 2.5b and 3.6b). Phone-only confirmations (not replaceable by CI): TalkBack announcement quality (X-02, X-48), real gesture feel (X-10), black frame and fold behavior (X-41, X-45), chip list vs probe (X-39). They are listed in the owner checklists (D- rows).

## Success Metric to test traceability

| Success Metric | Tests that prove it | Evidence class |
|---|---|---|
| SM1 (R1,R2) | I-E1-01 (fails on `main`, passes after), I-E1-02, I-E1-03/04 (real JGit forced cancel sweep), I-E1-05/06/07 (race over dead record, in-JVM and two real JVMs), I-E1-08 (SIGKILL orphan lock), U-E1-16..24, D-E1-01/02 | CI proves the overlap and the crash matrix on JVM; Android `/proc` behavior is owner-verified |
| SM2 (R3,R4) | U-E2-01/02/06/08..11, I-E2-01..04, X-10, X-18, X-19 | CI; feel and 12 MP smoothness owner-verified (D-E2-01) |
| SM3 (R5,R6; split into SM3a CI-closed and SM3b owner-study-gated) | U-E3-16..20 (edit); U-E3-01..15, I-E3-10, I-E3-11 (model correctness); **I-E3-12 + I-E3-13 + D-E3-01 (real-photo hold-out, the only evidence)** | Edit: CI. Accuracy: owner photos required; synthetic results can never close SM3 because I-E3-13 rejects the "SM3 met" verdict without the real evidence table |
| SM4 (R7,R8) | U-E4-01..10, I-E4-01..05, X-39..46, D-E4-01..04 | CI covers logic and UI states with fixtures; real lens enumeration and metadata delivery are owner-verified |

## Story to test traceability (every story's acceptance criteria)

| Story | Acceptance criteria covered by |
|-------|-------------------------------|
| 1.1 | I-E1-01, I-E1-02, I-E1-03, I-E1-15, U-E1-34, U-E1-16 |
| 1.2 | U-E1-01..08, U-E1-38..40, U-E1-47, I-E1-05..11, I-E1-04 (SAF I-E1-11, CLI I-E1-10) |
| 1.3 | U-E1-09..11, U-E1-14, U-E1-31..33, U-E1-41, U-E1-28 |
| 1.4 | U-E1-12..21, U-E1-30, U-E1-36, U-E1-42, I-E1-14 |
| 1.5 | U-E1-22..25, I-E1-03/04 |
| 1.6 | U-E1-27..29, U-E1-31, I-E1-12 |
| 1.7 | X-01..X-09, X-04b, X-51, X-52, X-68b, X-69, I-E1-16, U-E1-30 (PausedRetryPolicyTest and SyncStatusSheetModelTest are in X-05/X-08/X-09 rows) |
| 1.8 | U-E1-35..37, I-P-01..03, D-E1-01/02 |
| 2.1 | I-E2-05 and the audit PR output (no test; read-only task); failing-set list is the deliverable |
| 2.2 | U-E2-01..05, U-E2-15, X-14 |
| 2.3 | U-E2-06/07/14, I-E2-01..03, I-E2-10, X-11..X-13, X-23, X-54, X-63..X-65 |
| 2.4 | U-E2-08..12, I-E2-04, X-18, X-19, X-24 |
| 2.5 | X-16, X-17, X-20..X-22, X-53, X-62, X-68a, X-70, I-E2-09 |
| 2.6 | X-14..X-17, X-54, X-55, I-E2-08 |
| 2.7 | I-E2-06/07, D-E2-01 |
| 3.1 | I-E3-11, U-E3-02 |
| 3.2 | I-E3-01..09 |
| 3.3 | U-E3-01..15 |
| 3.4 | U-E3-16..20 |
| 3.5 | X-25..X-30, X-61, X-67, X-68c |
| 3.6 | X-31..X-38, X-58, X-66, X-68d, U-E3-24 |
| 3.7 | U-E3-21..23, U-E3-13 |
| 3.8 | I-E3-12, I-E3-13, D-E3-01 |
| 3.9 | U-E3-25, U-E3-26, U-E4-12 |
| 4.1 | U-E4-14, D-E4-01, D-E4-05 |
| 4.2 | U-E4-01..05 |
| 4.3 | U-E4-06..09, X-41 |
| 4.4 | I-E4-01..04, X-39..X-46, X-56, X-57, X-59, X-60, X-68e |
| 4.5 | U-E4-10..12, I-E4-05 |
| 4.6 | I-E4-06, U-E4-09 |
| 4.7 | I-E4-07, I-P-02, D-E4-02..04 |

## Review concerns to test mapping

| Concern (latest reviews) | Testable? | Test |
|---|---|---|
| Adv C1 two gate keys alias one directory, `ClearedOwn` deletes a live lock | Yes | U-E1-10 |
| Adv C2 mtime filter vs foreign lock newer than dead record | Yes (rule decided in the Phase 4 patch: heartbeat upper bound, ADR-001 decision 12 step 5) | U-E1-39 |
| Adv C3 JVM start time skew reads live owner as Dead | Yes | U-E1-07 |
| Adv C4 torn record; `forceRemoveLock` protocol | Yes | U-E1-06, U-E1-40 |
| Adv C5 `return@withContext` rebinding in `NonCancellable` unit | Yes | U-E1-24 |
| Adv C6 only in-JVM concurrency tested | Yes | I-E1-06, I-E1-07 |
| Adv C7 wave order, fake used before it exists | Process (not a runtime test) | Wave-order lint in the validation gate: `GateAssertingGitRepository` must exist at 1.3d/1.4a (I-P-03 checks the integrator order); applied in the Phase 4 patch (the fake is created in Task 1.3d, W3) |
| Adv C8 GRADIENT significance gate (df) and tilt validity | Yes | U-E3-07, U-E3-08 |
| Adv C9 principal point / crops | Yes | U-E3-02(f), U-E3-09 |
| Adv C10 Fit snap detent | Yes | U-E2-09 |
| Adv minors (desktop inset zero, `isFit-or-greater` helper, key collision, `heldBy` null for out-of-process) | Yes | I-E2-03, U-E2-07, U-E1-10, U-E1-38 (assert `SyncInProgress(heldBy=null)`) |
| Arch R1 flush re-entrancy | Yes | U-E1-33 |
| Arch R2 `isFit` below floor | Yes | U-E2-08 |
| Arch R3 fallback gate key splits one repo | Yes | U-E1-13 |
| Arch R4 `fetch().toTickOutcome()` and path | Yes | U-E1-30, U-E1-29 |
| Arch R5 quiesce key source | Yes | U-E1-14 |
| Arch R6 3.2a before 3.3c | Gate | U-E3-14 |
| Arch nits (ADR-005 text, `recoverStaleLock` name, runner Busy per path) | Partly | U-E1-29; the two naming nits are doc edits, no runtime test |

## Test Stack
- **Unit**: Kotlin `kotlin.test` + `kotest-assertions-core` + `kotest-property` (`Arb`/`checkAll` in `runTest`), `kotlinx-coroutines-test` virtual time; `commonTest` by default, `businessTest` for service logic.
- **Integration**: in-memory repositories, `FakeGitRepository` with a real temp-dir lock file (later `GateAssertingGitRepository`), real SQLDelight, real `JvmGitRepository`/`JavaIoLockFiles` on temp dirs, `ProcessBuilder` child JVMs for the cross-process and SIGKILL tests, `WiredAppFixture`/`ObservableEffect`, frozen old-reader decoders, `TestListenableWorkerBuilder`.
- **E2E / UX**: Robolectric Compose tests in `androidUnitTest` for gestures, semantics, layout bounds and contrast; Roborazzi only for pixels (`jvmTest`, `// jvmTest-reason: pixels`); manual owner checklists for TalkBack, real lens enumeration, jank on 12 MP, fold.
- Commands (all wrapped): `timeout 30m scripts/bazel-lowprio.sh test //kmp:business_tests`; `timeout 30m ./gradlew testDebugUnitTest`; `scripts/jvm-display-check.sh -- timeout 30m scripts/bazel-lowprio.sh test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`. A bare `jvm_tests` UI failure is unverified until the script confirms a real display.

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (`commonMain` pure logic: `LockRecovery`, `RepoOpGate`, `SyncSingleFlight`, `ToolPolicy`, `PlacementClassifier`, `AnnotationViewTransform`, `ConstraintCalibrator`, `ConstraintFitApplier`, `MeasurementValueEdit`, `LensClassifier`) | `./gradlew jacocoTestReport` then `kmp/build/reports/jacoco/` (or `bazel coverage //kmp:business_tests` if configured) | >= 90% line on these pure files, >= 80% line on the rest of changed files |
| Android UI (Robolectric) | `./gradlew testDebugUnitTest jacocoTestReport` | >= 80% line on new composables |

- All public service methods: happy path + error paths covered (every `LockVerdict`, every `CalibrationFailure`, every `GitError.SyncInProgress` consumer).
- All external integrations (JGit, filesystem locks, `/proc`, CameraX/Camera2): unit-tested via ports with fakes plus at least one integration or owner-device test (I-E1-03/08, D-E1-01, D-E4-01).
- UX acceptance: each of UX-01..UX-70 has a row in the UX table.
- Mutation guard for the regression test: `I-E1-01` is run against `main` before the fix and its failing output pasted in the PR (Success Metric 1 demands it).

## Items not fully closable by CI (named wall-clock blockers)

1. Owner phone (including the 20 + 20 gesture pass on `PlacementCounters` that tunes `DRAG_COMMIT_DP`, Task 2.7a): `/proc` and `boot_id` readability, force-stop verdicts, real lens enumeration and capture-metadata delivery, lens-switch time, 12 MP jank, TalkBack quality, fold (D-E1-01/02, D-E2-01, D-E4-01..05).
2. Owner photos with tape-measured ground truth: Success Metric 3's accuracy claim (I-E3-12, D-E3-01). CI can only enforce that the claim is not made without that evidence (I-E3-13).
3. CI wall time (~40 min per push) and release cadence if a device build is needed.

## Plan edits this validation asked for (all applied in the plan's Phase 4 patch; planning docs only)

1. APPLIED: adversarial C2 rule decided (heartbeat upper bound, ADR-001 decision 12 step 5); U-E1-39 asserts it.
2. APPLIED: `GateAssertingGitRepository` moved into Task 1.3d.
3. APPLIED (existing tasks extended; the liveness skew test is now `ClockStepStartTimeTest`; `CrossProcessSyncOverDeadRecordTest` sits in Task 1.8b because it needs the W5 gate): add Tasks (or extend existing ones) for: cross-process race and SIGKILL tests (Task 1.2f), `ProcessLivenessSkewTest` and torn-record handling (Tasks 1.2a/1.2b), Monte Carlo significance and crop tests (Task 3.3b), out-of-model families (e)(f)(g) and the real-photo hold-out with set A/B split (Tasks 3.1b/3.8a), `AccuracyStudyVerdictTest` (Task 3.8a), CameraCharacteristics OEM fixture matrix (Task 4.2b), `NewCodeAuditTest` and `ScopeEscapeAuditTest` (Task 1.8a), and the slow-pinch detent test (Task 2.4a).
