# Validation Plan: dev-console-git-sync-fix

**Date**: 2026-10-10

Path legend: `...` = `kmp/src/<sourceSet>/kotlin/dev/stapler/stelekit`. Type names follow the plan's Domain Glossary (`RemoteBranchNotFound`, `ScheduledSyncPolicy`, `ColdSyncOutcome`, `ConsoleSession`, `ConsoleRedactor`, `StatementClass`, ...). Test names use `method_should_Expected_When_Condition`.

## Happy Path Scenario
Given the owner's Android clone with stored `remote_branch = "main"` while `origin` only has `master` (and `origin/master` holds the 2026-10-09 and 2026-10-10 journals the phone lacks), when the owner taps Sync, then the badge turns red with "Branch 'main' not found on remote — tap to fix" (never green); after "Use 'master'" the stored value reads back as `master` and the previewed first-sync review opens (no sync yet); on "Sync now" a sync runs, `remoteCommitsMerged > 0`, the journals appear in the app, and the owner can run `git doctor` then `graph journals-diff` in the dev console and see `behind=0` and 0 missing recent dates. *(All error paths, scheduled-sync safety cases and console guards below are variations on this flow.)*

## Requirement Inventory (26 items)

| ID | Requirement (source) |
|----|----------------------|
| M1 | Metric 1 sync correctness: remote commits are pulled whenever the app is open within the interval or on tap; background fetches keep the behind-count fresh when the app is closed; WorkManager 15-min floor (Android interval `max(configured, 15 min)`); `remoteCommitsMerged > 0` when remote is ahead. Numeric baseline (requirements "Metric 1 numeric baseline"): missing recent dates 2 -> target 0, `remoteCommitsMerged` 0 -> > 0, `behind` -> 0; device-only until Story 7.1, with CI-level `INTERIM (not device)` proxies (Task 7.1c) |
| M2 | Metric 2 no silent no-op: unresolved remote ref yields `SyncState.Error` with actionable message; regression test fails on old code |
| M3 | Metric 3 console answers branch/refs/log/status and disk-not-in-DB journals in-app, no rebuild, exportable |
| M4 | Metric 4 last 14 days of journals on disk == DB == remote, stable across 3 scheduled syncs (ghost journals 2026-10-07/09/10 excluded as known exceptions) |
| S1 | Commit the diagnostics changes (Git section, journal samples) as six named files, no credentials; build stamp (git SHA + build time) in the diagnostics header and `diag` |
| S2 | `doFetch` (Android + JVM) errors on unresolved ref; WARN logs remote, branch, available list; typed `RemoteEmpty`/`InvalidRefName`; non-retryable |
| S3 | Detect remote default branch (`ls-remote --symref`), clone records real branch, confirmed repair of stale config, additive config migration |
| S4 | Verify push branch semantics (`doPush`), shallow-clone interaction, `wikiSubdir=logseq` filtering reaches `reloadFiles`, post-sync invariants; conflict-marker scan, repository-state guard and mass-change guard on every commit and push path; no force push |
| S5 | Journal indexing: explain/fix 1542 on-disk journals absent from DB; background index completes or recovers |
| S6 | Scheduled sync = full sync incl. push while the app is open (`autoCommit` honored, `EditLock`/`GitSyncBusyCounter`, conflict UI fallback); closed-app background work is fetch-only with a staleness indicator; first-sync confirmation; SAF graphs fetch-only; staleness chip/notification/banner |
| S7 | Journal cap kept (~30 eager); older journals load lazily on navigate/search/calendar jump (ADR-006) |
| S8 | Console REPL screen: history, scrollback, copy/export/share, cancel, reachable from Settings/Logs behind developer-mode toggle |
| S9 | `sql` family: bounded SELECT, writes via write actor behind confirm |
| S10 | `git` family: status, log, refs, remote, ls-remote, fetch, merge, set-branch, doctor (closed list; raw JGit passthrough cut) |
| S11 | `fs` family: ls/stat/cat/head/find within graph roots and app dir |
| S12 | `settings`, `logs`, `graph`, `diag` families; `graph reload`/`reindex`/`restore-backup` protect DB-only rows |
| S13 | `sh` command, platform-permitting (desktop only, gated) |
| S14 | Extensible command registry: new probe is a one-file addition |
| S15 | Platform support: Android + Desktop full; Web subset (no git/shell) |
| N1 | Performance SLO: streaming output, every command cancellable, 500-row cap with "truncated" marker, console never blocks UI thread |
| N2 | Scalability: 9.4k pages / 1.6k journals, bounded reads, `GIT_TRANSPORT_TIMEOUT_SECONDS` bounds ls-remote/fetch |
| N3 | Security: never print credentials; URL userinfo stripped; redaction on output, history, export, audit log |
| N4 | Export is local file / OS share sheet only; history in memory only |
| N5 | CLAUDE.md invariants: writes via `DatabaseWriteActor`/`@DirectSqlWrite`, `Either` at boundaries, `catch Throwable` guards, no `rememberCoroutineScope` escape, no `java.*` in commonMain, no schema/`MigrationRunner` change |
| N6 | Observability: console command INFO audit, SQL write WARN, fetch-unresolved WARN, scheduled-sync decision logs |
| N7 | Risk control: developer-mode flag default off, confirm tiers, pre-write backup + restore, DB-only-rows guard, shell second confirm, background fetch-only safety, first-sync confirmation, mass-change guard |

Requirements Metrics 5 (console and sync safety) and 6 (measurable outcomes of the added safety scope) are not new inventory IDs: they are measured through the existing IDs N3, N7, S4, S6, S9 and S12, as listed in "Safety metric mapping" below, so the inventory stays 26 items.

## Requirement → Test Mapping

Types: Unit (incl. property tests, tagged `Property` in the Scenario column), Integration (real temp JGit repo, real SQLite driver, or service wiring), Migration.
Source sets: `commonTest` first; `businessTest` for service logic with fakes; `jvmTest` for JGit/SQLite/process; `androidUnitTest` (Robolectric) for Compose. Any `jvmTest`/`jvm_tests` run that touches UI is wrapped with `scripts/jvm-display-check.sh --`.

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| M1 | jvmTest/.../git/RemoteBranchResolutionTest.kt | fetchThenMerge_should_PullRemoteCommitAndFile_When_ConfiguredBranchIsMaster | Integration | Happy path: origin `master` c1+c2, `remoteBranch="master"`, merge commit exists, `logseq/journals/2026_10_10.md` on disk, `remoteCommitCount==1` |
| M1 | businessTest/.../git/GitSyncServiceInvariantTest.kt | sync_should_ReportRemoteCommitsMergedGreaterThanZero_When_RemoteAhead | Unit | Happy path: `Success.remoteCommitsMerged>0` via `StubGitRepository` |
| M1 | businessTest/.../git/GitSyncServiceInvariantTest.kt | sync_should_NotReportSuccess_When_RemoteTipNotMergedIntoHead | Unit | Error path: `Error(SyncInvariantViolated)` |
| M1 | jvmTest/.../git/InterimPhoneStateEndToEndTest.kt | endToEnd_should_RepairMainToMasterAndMergeBothJournals_When_PhoneStateReproduced | Integration | `INTERIM (not device)`: origin `master` only with 2026_10_09/10 journals, stored `main`, 3 ghost journals; sync -> `Error(RemoteBranchNotFound)` -> `BranchRepairService` -> sync -> `remoteCommitsMerged>0`, `behind==0`, `reloadFiles` gets both paths, 0 missing recent dates |
| M1 | manual (Story 7.1) | device_should_ShowZeroMissingRecentDatesAndMergedGreaterThanZero_When_OneSyncAfterRepair | Integration | Numeric baseline vs target on the phone: missing recent dates 2 -> 0, `remoteCommitsMerged` 0 -> >0, `behind` -> 0, disk-vs-origin journal count gap -> 0 under the Task 0.3b counting rule |
| M1 | androidUnitTest/.../git/WorkManagerSyncSchedulerIntervalTest.kt | periodicRequest_should_ClampToFifteenMinutes_When_SettingIsFiveMinutes | Unit | Happy path: `max(setting,15)` and setting text says so |
| M1 | androidUnitTest/.../git/WorkManagerSyncSchedulerIntervalTest.kt | periodicRequest_should_NotBeScheduled_When_IntervalSettingOff | Unit | Error path: "off" disables |
| M1 | androidUnitTest/.../git/WorkManagerSyncSchedulerRetryOwnerTest.kt | gitSyncWorker_should_CallRunScheduledSync_When_ServiceRegisteredInRegistry | Integration | Fast path uses real `GitSyncServiceRegistry` and fake service (guards dead-fast-path regression) |
| M1 | jvmTest/.../git/SchedulerFakeClockTest.kt | desktopTimer_should_MergeTwoCommitsAndReloadJournalPaths_When_RemoteTwoAhead | Integration | Desktop timer: temp origin 2 ahead, `reloadFiles` receives paths, `autoCommit` edits pushed |
| M2 | jvmTest/.../git/RemoteBranchResolutionTest.kt | fetch_should_ReturnRemoteBranchNotFound_When_OriginHasOnlyMaster | Integration | Happy path of the fix: `Left(RemoteBranchNotFound("origin","main",["master"]))`, message "Branch 'main' not found on remote — tap to fix"; committed red against old `doFetch` first (observed `Right(FetchResult(false,0))`) |
| M2 | commonTest/.../error/GitErrorMessageTest.kt | toSyncErrorMessage_should_ReturnExactUxStrings_When_AnyNewGitErrorOrRepairNeeded | Unit | Happy path: the five S1 strings verbatim plus `ConflictMarkersPresent`/`ScanIncomplete`/`MassChangeBlocked`/`RepairNeeded`; none retry-routed |
| M2 | businessTest/.../git/GitSyncServiceInvariantTest.kt | sync_should_ReturnErrorState_When_FetchReturnsRemoteBranchNotFound | Unit | False-green matrix (a): never `Success` |
| M2 | businessTest/.../git/GitSyncServiceInvariantTest.kt | sync_should_NotFallThroughToPush_When_FetchFailsWithUnresolvedRef | Unit | Error path: zero `push` calls on spy |
| M2 | jvmTest/.../git/GitSyncSpikeTest.kt | noConfigEarlyReturn_should_OnlyFireAtLine238_When_ConfigMissing | Unit | Characterization: real false-green path is `hasRemoteChanges==false` fall-through, not line 238 |
| M3 | businessTest/.../console/ConsoleAcceptanceTest.kt | consoleSession_should_AnswerBranchRefsLogStatus_When_GitDoctorThenGitLogRun | Integration | Happy path: fake `GitConsoleView` over temp repo; output includes configured ref, resolved, remote heads, suggestion |
| M3 | businessTest/.../console/ConsoleAcceptanceTest.kt | consoleSession_should_ListDiskOnlyJournals_When_JournalsDiffRun | Integration | Happy path: `graph journals-diff` over in-memory repos + `FakeFileSystem` |
| M3 | businessTest/.../console/ConsoleAcceptanceTest.kt | consoleExport_should_WriteRedactedTranscriptFile_When_ExportRun | Integration | Export produces `console-<ts>.txt`, grep for PAT finds nothing |
| M3 | businessTest/.../console/ConsoleAcceptanceTest.kt | consoleSession_should_AnswerNoRebuild_When_RegistryPopulatedAtRuntime | Unit | Baseline 0 of 3 questions without rebuild -> 3 of 3 through the registry alone |
| M3 | manual (Story 7.2) | device_should_AnswerBranchAndJournalQuestions_When_ConsoleRunOnPhone | Integration | On-device: the five named probes each < 10 s from Run tap to status chip on three consecutive runs (recorded per probe), plus `export` with no rebuild |
| M3 | businessTest/.../console/ConsoleAcceptanceTest.kt | consoleProbes_should_ReturnEachOfFiveNamedProbesUnderTenSecondsOfFakeClock_When_RunOnFixtures | Integration | Metric 3 threshold, CI proxy: `git refs`, `git ls-remote`, `git status`, `graph journals-diff`, `git doctor` over a temp repo and fake remote with a controllable clock; each reaches a final chip in < 10 s; a hung `ls-remote` ends as a TIMEOUT chip at the console probe limit (counted as a threshold failure, never a pass) |
| M4 | commonTest/.../journal/JournalDiffPropertyTest.kt | diff_should_PartitionDiskAndDb_When_AnyGeneratedDateSets | Unit | Property: `diskOnly`,`dbOnly`,`inBoth` disjoint, counts sum to inputs |
| M4 | businessTest/.../journal/JournalDiffServiceTest.kt | diff_should_SplitRecentFromOlder_When_GhostsAndOlderJournalsPresent | Unit | Happy path: `diskOnlyRecent=[10-08]`, `dbOnlyRecent=[10-07,10-09,10-10]`, `diskOnlyOlder` count 1500 sample <=50 |
| M4 | businessTest/.../journal/JournalDiffServiceTest.kt | diff_should_ExcludeKnownExceptions_When_GhostDatesMarkedUndecided | Unit | Error path: ghosts reported but do not fail equality check |
| M4 | jvmTest/.../git/InterimPhoneStateEndToEndTest.kt | diagnosticsExportDiff_should_ShowResolvedRefBehindZeroAndNewestJournals_When_BeforeAfterRepair | Integration | `INTERIM (not device)`: two `GraphDiagnosticsCollector` exports diffed; `resolve(origin/<branch>)` unresolved -> resolved, `behind` n -> 0, newest-journals listing gains 2026-10-09/10, no credential strings, no other Git-section field changes |
| M4 | jvmTest/.../git/ScheduledSoakTest.kt | threeScheduledRuns_should_LeaveBehindZero_When_CleanConflictFreeGraph | Integration | Fake clock >=15 min apart x3 over temp origin; `behind==0` each run |
| M4 | manual (Story 7.1b) | device_should_ShowZeroMissingRecentDates_When_ThreeSyncsFifteenMinutesApartAppOpen | Integration | On-device soak with the app open, `git doctor` + journals-diff; closed-app behind-count/notification checked in the same story |
| S1 | jvmTest/.../diagnostics/GraphDiagnosticsRedactionTest.kt | collect_should_OmitTokenAndUserinfo_When_RemoteUrlHasCredentials | Unit | `ghp_abc123` and `u:` absent from full output |
| S1 | jvmTest/.../diagnostics/GraphDiagnosticsRedactionTest.kt | collect_should_IncludeGitSectionAndJournalSamples_When_CloneConfigured | Integration | Git section shows config, refs, ls-remote, log; oldest+newest journals |
| S1 | commonTest/.../diagnostics/BuildStampFormatTest.kt | header_should_ShowBuildShaTimeVersionOrUnstamped_When_Collected | Unit | Pre-mortem #6; `diag` prints the same line; Task 0.3b rejects `unstamped` |
| S1 | jvmTest/.../git/GitRefDiagnosticsTest.kt | gitRefDiagnostics_should_NotThrow_When_RepoHasNoRemote | Unit | Error path: degrades to message |
| S1 | manual (Task 1.1c) | commit_should_ListExactlySixFiles_When_StagedByName | Integration | `git show --stat HEAD` lists six files; benchmark JSON and plan docs excluded |
| S2 | jvmTest/.../git/RemoteBranchResolutionTest.kt | fetch_should_ReturnRemoteEmpty_When_OriginHasNoBranches | Integration | Error path: "Remote is empty", not `RemoteBranchNotFound` |
| S2 | jvmTest/.../git/RemoteBranchResolutionTest.kt | fetch_should_ReturnInvalidRefName_When_BranchIsADotDotB | Unit | Error path: config corruption |
| S2 | jvmTest/.../git/RemoteBranchResolutionTest.kt | fetch_should_ReportNoChanges_When_LocalAheadOnly | Integration | Ahead-only is not "changes" |
| S2 | jvmTest/.../git/RemoteBranchResolutionTest.kt | fetch_should_ReturnRemoteBranchNotFound_When_RemoteBranchRenamedAfterClone | Integration | Stale config after remote rename |
| S2 | jvmTest/.../git/RemoteBranchResolutionTest.kt | fetch_should_ResolveViaShadowShapedRepo_When_GitDirSeparateFromWorkTree | Integration | SAF-shadow-shaped repo exercises the shadow code path |
| S2 | jvmTest/.../git/RemoteTrackingRefTest.kt | resolveRemoteTrackingRef_should_UseExactRef_When_LocalBranchNamedOriginMain | Unit | Ambiguity spike: `resolve` returns local OID, `exactRef` null |
| S2 | jvmTest/.../git/GitRetryClassificationTest.kt | runGitTransportOp_should_AttemptOnce_When_ErrorIsRemoteBranchNotFoundEmptyOrInvalid | Unit | Non-retryable, no `RetryExhausted` |
| S2 | jvmTest/.../git/RemoteBranchResolutionTest.kt | fetch_should_LogWarnWithRemoteBranchAndAvailable_When_RefUnresolved | Unit | Observability WARN content |
| S3 | jvmTest/.../git/RemoteDefaultBranchTest.kt | detectDefaultBranch_should_ReturnDetectedMaster_When_HeadSymrefPointsAtMaster | Integration | Happy path |
| S3 | jvmTest/.../git/RemoteDefaultBranchTest.kt | detectDefaultBranch_should_ReturnAmbiguous_When_MainAndMasterSameOidNoSymref | Integration | Error path: never auto-guess |
| S3 | jvmTest/.../git/RemoteDefaultBranchTest.kt | detectDefaultBranch_should_ReturnEmptyRemoteOrUnreachable_When_NoBranchesOrOffline | Integration | Offline never reported as "branch missing" |
| S3 | jvmTest/.../git/RemoteDefaultBranchTest.kt | detectDefaultBranch_should_NeverPickBranchOutsideHeadList_When_AnyGeneratedHeads | Unit | Property (kotest-property) |
| S3 | jvmTest/.../git/RemoteBranchResolutionTest.kt | clone_should_SaveCheckedOutBranch_When_FormSaysMainAndOriginHeadIsMaster | Integration | Clone records `remoteBranch=="master"` |
| S3 | businessTest/.../git/BranchRepairServiceTest.kt | apply_should_SaveConfigAndReadBack_When_UserConfirmsMaster | Unit | Happy path: read-back equals `master` |
| S3 | businessTest/.../git/BranchRepairServiceTest.kt | apply_should_ReturnError_When_ReadBackDiffersFromWritten | Unit | Error path (mutation read-back rule) |
| S3 | androidUnitTest/.../git/WorkerConfigMappingTest.kt | toGitConfig_should_ReturnMaster_When_RowRepaired | Unit | Background worker sees repair |
| S3 | businessTest/.../git/BranchRepairServiceTest.kt | migration_should_be_reversible | Migration | Data-only: repair `main -> master`, verify, `git set-branch main` restores prior value; no schema change (`git diff --stat -- '*.sq' '*MigrationRunner*'` empty) |
| S3 | jvmTest/.../git/RemoteDefaultBranchTest.kt | detectDefaultBranch_should_RespectFifteenSecondTimeout_When_RemoteHangs | Unit | N2 tie-in |
| S3 | businessTest/.../ui/GitSyncCoordinatorTest.kt | repair_should_WriteNothingAndRefresh_When_StoredBranchOrHeadsChangedSinceSheetOpened | Unit | UX-93: console `git set-branch` changes the row mid-sheet, or the target head vanishes; no `saveConfig`; also asserts the repair never calls `sync` by itself (opens the first-sync review, `Sync now` is the only trigger) |
| S3 | businessTest/.../ui/GitSyncCoordinatorTest.kt | repairActions_should_BeDisabledWhileLockHeldAndReEvaluateOnResume_When_SyncRunsOrProcessRecreated | Unit | UX-93: git write lock held or syncing disables Use/Sync now/Change back, re-enables on completion, tap racing the lock queues nothing, resume closes with `Already fixed.` when the branch became valid |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | push_should_UpdateOriginMasterOnly_When_RemoteBranchIsMaster | Integration | No `refs/heads/main` appears on origin |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | push_should_UseSlashedBranchName_When_BranchIsReleaseSlashX | Integration | Explicit refspec edge |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | sync_should_CountThreeMergedCommits_When_ShallowCloneBehindByThree | Integration | `MergedCommitCount` from HEAD before/after |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | sync_should_ReturnDetachedHeadError_When_HeadDetached | Integration | False-green matrix (e) |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | sync_should_RefuseBeforeStageCommitPush_When_RepositoryInMergingState | Integration | Matrix (g) |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | commit_should_RefuseAndLeaveOriginUnchanged_When_TrackedFileHasConflictMarkers | Integration | Staged marker scan, typed error names file |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | push_should_Refuse_When_PreexistingLocalCommitHasMarkersInNetDiff | Integration | Net tree diff scan |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | push_should_Allow_When_MarkerAddedThenRemovedAcrossTwoCommits | Integration | Net diff clean (no wedge) |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | commit_should_ReturnMassChangeBlocked_When_DeletionsExceedThresholdOrSubdirMissing | Integration | Pre-mortem P1-3: `max(20, 5%)` / 10 journals; missing `logseq/journals/` tree; origin unchanged |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | massChange_should_UseOverriddenThresholds_When_SettingsKeysSet | Integration | Task 2.3f configurable `MassChangeThresholds`: lowered `git_mass_change_min_files` blocks a 5-file deletion, raised value passes a 25-file one, read failure uses defaults |
| S4 | businessTest/.../git/GitSyncServiceInvariantTest.kt | massChange_should_BeLiftedOnceByForegroundConfirmAndNeverByScheduledRun_When_GuardHit | Unit | Plus one test per guarded entry point (`:268,:369,:497,:561,:604,:614`) |
| S4 | businessTest/.../git/NoForcePushAuditTest.kt | production_should_NeverUseForceRefspecOrSetForce_When_SourceAudited | Unit | Source audit |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | scan_should_IgnoreFencedBlockAndBlockOversizeFile_When_NotesDocumentGitSyntax | Unit | Fenced ignored; oversize fails closed |
| S4 | jvmTest/.../git/SyncInvariantTest.kt | scan_should_SkipAndPushBlockedByUnresolvedRef_When_NoRemoteTrackingRef | Integration | No base tree |
| S4 | businessTest/.../git/GitSyncServiceInvariantTest.kt | commitLocalChanges_resolveConflicts_applyJournalMerge_should_EachRefuseMarkers | Unit | One test per guarded entry point (`:497,:561,:604/:614`) |
| S4 | jvmTest/.../git/GitMergeDiffTest.kt | computeChangedGitRelativePaths_should_CharacterizeNoFfMergeCommit_When_TwoRemoteCommits | Integration | Characterization first; "diff range wrong" UNPROVEN |
| S4 | jvmTest/.../git/GitMergeDiffTest.kt | reloadSet_should_ContainOnlyLogseqPaths_When_WikiSubdirIsLogseq | Integration | `README.md` excluded, `/dev/null` deletions filtered |
| S4 | businessTest/.../db/GraphLoaderReloadWarnTest.kt | reloadFiles_should_LogWarnNamingPath_When_PathUnreadable | Unit | No silent `continue` |
| S4 | jvmTest/.../git/GitSyncSpikeTest.kt | lsRemoteSymref_and_shallowClone_should_RecordObservedValues_When_FileTransport | Integration | Spike 0.1a characterization |
| S5 | businessTest/.../db/BackgroundIndexSupervisorTest.kt | indexStatus_should_ReportUnloadedCountAndJobActive_When_9414PagesMidDrain | Unit | Happy path; `GraphLoader` gains no status state |
| S5 | businessTest/.../db/LargeGraphIndexRecoveryTest.kt | loadGraphProgressive_should_InvokeOnFullyLoadedAndOnDegradedAndStartIndexing_When_ReconcileThrows | Integration | Error path; extends `LargeGraphWarmStartCrashTest` pattern |
| S5 | businessTest/.../db/BackgroundIndexSupervisorTest.kt | supervisor_should_RestartDrain_When_AppResumesAfterTrimMemory | Unit | Fake lifecycle, `runTest` |
| S5 | businessTest/.../db/GraphLoaderIndexBatchingTest.kt | indexRemainingPages_should_TerminateWithPermanentlyFailingPages_When_DrainRuns | Integration | Existing test must stay green |
| S5 | businessTest/.../db/BackgroundIndexSupervisorTest.kt | indexStatus_should_ExposeLastError_When_DrainFails | Unit | Error path |
| S6 | commonTest/.../git/ScheduledSyncPolicyTest.kt | evaluate_should_ReturnRun_When_AllInputsClear | Unit | Happy path |
| S6 | commonTest/.../git/ScheduledSyncPolicyTest.kt | evaluate_should_ReturnSkipWithReason_When_EditLockBusyVaultLockedOrConflictPending | Unit | Table: `Skip(EditingInProgress/Busy/VaultLocked/ConflictPending)`, zero git calls |
| S6 | commonTest/.../git/ScheduledSyncPolicyTest.kt | evaluate_should_ReturnSkipRepoState_When_FreshServiceOverMergingRepo | Unit | Simulated process death: git's own state is the record; Property: never `Run` when any blocking input (repo state, `FirstSyncUnconfirmed`, non-app-owned path) is set |
| S6 | commonTest/.../git/ScheduledSyncPolicyTest.kt | evaluate_should_ReturnFirstSyncUnconfirmedOrFetchOnlySaf_When_NoConfirmationOrNotAppOwned | Unit | Error path: `Skip(FirstSyncUnconfirmed)`; `FetchOnly(Saf)`; zero merge/commit/push |
| S6 | businessTest/.../git/GitSyncServiceRepoStateTest.kt | sync_should_ReturnRepairNeededWithZeroStageCommitMergePushCalls_When_RepositoryMerging | Unit | Replaces the marker gate; fetch allowed; scheduled run `Skip(RepoState)` |
| S6 | businessTest/.../git/FirstSyncConfirmationTest.kt | confirmation_should_StoreOnlyOnManualSuccessAndInvalidateOnBranchChange_When_FirstSyncRuns | Unit | `Success` stores `<remote>/<branch>`; `Error`/`ConflictPending`/`RepairNeeded` store nothing; branch change re-arms |
| S6 | androidUnitTest/.../ui/screens/git/FirstSyncPreviewSheetTest.kt | preview_should_ShowCountsStrayBranchAndFirstTwentyLocalCommits_When_StrayOriginMainAnd25LocalCommits | Unit | Read-only; UX-75 |
| S6 | businessTest/.../ui/GitSyncCoordinatorTest.kt | syncWithoutPreview_should_BeOfferedOnlyAfterRemoteFailureAndRunOneSyncOnlyAfterTypedConfirm_When_RemoteCheckTimesOut | Unit | UX-104: absent while loading and after load; Cancel or mismatch runs zero syncs; typed `sync` (trim, case-insensitive) runs exactly one `sync`; confirmation stored only on `Success`; disabled while the lock is held; guards unchanged |
| S6 | businessTest/.../git/FirstSyncConfirmationTest.kt | pendingReviewKey_should_BeSetByRepairOrSetBranchAndClearedOnConfirmation_When_BranchRepaired | Unit | PR-A1 (Task 2.2d0): drives the `Review first sync` badge before any scheduler exists; branch change re-arms |
| S6 | businessTest/.../git/FirstSyncConfirmationTest.kt | confirmationHook_should_ConfirmAndClearPendingKey_When_AnyManualSyncEntryPointSucceedsAndNotOnAmberInvariantViolated | Unit | Triad repair 4 (G1, UX-105): table over entry points {S14 `Sync now`, badge sync, mass-change confirm run, invariant `Retry`} each returning `Success` stores `<remote>/<branch>` and clears the pending key; `SyncInvariantViolated`, `ConflictPending`, `RepairNeeded`, `Error` store nothing |
| S3 | businessTest/.../git/FirstSyncConfirmationTest.kt | previousBranchKey_should_SurviveCoordinatorRestartAndBeAbsentSafe_When_RepairThenProcessRecreated | Unit | Triad repair 4 (G2, UX-106): repair writes `git_first_sync_previous_branch_<graphId>`; a new coordinator reads it; unset key yields `unknown`; cleared with the pending key on confirmation |
| S6 | commonTest/.../git/SyncStatePrecedenceTest.kt | reviewFirstSyncRow_should_StayReachable_When_WorstErrorBadgeShownWhilePendingKeySet | Unit | Triad repair 4 (G1, UX-105): over {red error, amber `SyncInvariantViolated`, no error} x {pending, not pending}: badge text is the worst error, the opened sheet exposes the `Review first sync` row exactly when pending; row gone after a confirmed `Success` |
| S6 | businessTest/.../git/GitSyncServiceScheduledTest.kt | scheduledPolicy_should_RunWithoutMigration_When_ConfirmationWrittenByA1Flow | Unit | PR-A2 (Task 2.4h) reads the A1-written `git_first_sync_confirmed_<graphId>` unchanged; absent or mismatching => `Skip(FirstSyncUnconfirmed)` |
| S6 | jvmTest/.../git/ColdSyncRunnerTest.kt | cold_should_FetchOnceAndRecordBehindCount_When_RemoteAhead | Integration | `Fetched(behind, AppClosed)`; spy: merge/commit/push/stageSubdir/checkout/abortMerge all 0 |
| S6 | jvmTest/.../git/ColdSyncRunnerTest.kt | cold_should_LeaveEveryFileByteIdentical_When_TreeDirtyMergingOrCleanAnyGeneratedSet | Integration | Property over generated dirty sets; tree states clean, dirty, `MERGING` |
| S6 | jvmTest/.../git/ColdSyncRunnerTest.kt | cold_should_RecordTypedFetchErrorAsOutcome_When_BranchNotFound | Integration | Error path: never swallowed; chip shows it |
| S6 | androidUnitTest/.../git/AppOwnedPathPredicateTest.kt | isAppOwnedPath_should_BeFalse_When_SafResolverThrowsDirMissingOrSafUri | Unit | Table incl. `saf://` with fast-path resolver (false for scheduled work); true for plain `.git` dir |
| S6 | commonTest/.../git/StalenessChipStateTest.kt | chipState_should_MatchSpecForAllGeneratedInputs_When_AnyAgeBehindIntervalOutcomes | Unit | Property (Metric 6 chip accuracy, 100% of generated inputs): amber iff `behind>0` and age > 2x interval, or the last N fetches errored; fetch-only never green |
| S6 | commonTest/.../git/BannerPrecedenceTest.kt | precedence_should_ShowExactlyOneBannerInOrder_When_AnySubsetOfFourConditions | Unit | All 16 subsets of {mismatch, first-sync review, updates waiting, `Review first sync` badge}: at most one banner, mismatch > first-sync > waiting; the badge is never a banner, stays visible after `Not now` or a dismissed first-sync banner and reopens S14; chip unaffected |
| S6 | androidUnitTest/.../git/SyncNotifierTest.kt | notifier_should_RequestPermissionOnlyFromToggleAndDegradeToChip_When_DeniedOrPermanentlyDenied | Unit | API 33+ states (granted, not asked, denied once, permanent) and API < 33; sync unaffected; rationale before request |
| S6 | businessTest/.../git/FirstSyncPreviewLoaderTest.kt | previewLoader_should_EmitLocalFirstThenRemoteAndTimeOutAtFifteenSeconds_When_RemoteHangs | Unit | Local commits first, remote part cancellable, timeout state never claims a missing branch; fake clock |
| S6 | commonTest/.../git/SyncStalenessStoreTest.kt | history_should_KeepLastTenAndOrder_When_ElevenOutcomesRecorded | Unit | `lastMergedAt`, `lastFetchedAt`, `behindCount` persisted per graph |
| S6 | androidUnitTest/.../git/SyncNotifierTest.kt | notifier_should_PostOncePerBehindCountChangeAndClearAtZero_When_PermissionGrantedOrDenied | Unit | Denied => chip only |
| S6 | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | chip_should_TurnAmberAfterTwiceInterval_When_BehindPositive_And_ShowFetchOnlyReason | Unit | UX-76, never green when fetch-only |
| S6 | businessTest/.../git/GitSyncServiceAutoCommitTest.kt | sync_should_NotCommitOrMerge_When_AutoCommitFalseAndTreeDirty | Unit | Shows `MergeAvailable` "Uncommitted changes — commit or enable auto-commit" |
| S6 | businessTest/.../git/GitSyncServiceAutoCommitTest.kt | sync_should_CommitBeforeMerge_When_AutoCommitTrueAndTreeDirty | Unit | Happy path |
| S6 | jvmTest/.../git/AbortActiveMergeRecoveryTest.kt | abortActiveMerge_should_SnapshotAndRestoreNonConflictedEdits_When_UserAborts | Integration | Task 2.4i (b),(e) property |
| S6 | jvmTest/.../git/AbortActiveMergeRecoveryTest.kt | abortActiveMerge_should_NotReset_When_SnapshotFailsOrUserCancels | Integration | (c),(d): `abortMerge` count 0 |
| S6 | commonTest/.../git/GitSyncServiceRegistryTest.kt | registry_should_AlwaysFindRegisteredService_When_ConcurrentRegisterUnregister | Unit | Thread-safety stress |
| S6 | businessTest/.../git/GitSyncServiceScheduledTest.kt | runScheduledSync_should_CallSyncOnceAndLogDecision_When_PolicyRuns | Unit | Observability + happy path |
| S6 | commonTest/.../git/NotificationTapRouterTest.kt | route_should_OpenFirstSyncReviewOrStatusSheetAndNeverSync_When_NotificationTapped | Unit | UX-101 table: FirstSyncUnconfirmed vs confirmed, app closed/open/other graph/unknown graph, behind already 0, typed-error notification; no sync started |
| S7 | businessTest/.../db/LazyJournalLoadTest.kt | loadJournalsOlderThan_should_LoadNextThirtyWithChunkedIn_When_ScrolledToLastLoaded | Unit | 30 -> 60 journals, `IN` <=500 |
| S7 | businessTest/.../db/LazyJournalLoadTest.kt | ensureJournalLoaded_should_ParseFull_When_DateOnDiskNotInDb | Unit | Navigate / calendar jump |
| S7 | businessTest/.../db/LazyJournalLoadTest.kt | loadJournalsOlderThan_should_LoadZero_When_CalledTwice | Unit | Idempotence; failing file skipped |
| S7 | businessTest/.../db/LargeGraphLazyJournalTest.kt | lazyJournalPaths_should_NeverExceedHundredRowBatches_When_8030PageGraph | Integration | Bounded reads recording |
| S7 | businessTest/.../db/GraphLoaderEagerJournalTest.kt | warmStart_should_LoadExactlyTenThenTwentyJournals_When_LazyLoaderPresent | Unit | Eager behaviour unchanged |
| S7 | commonTest/.../ui/screens/JournalsViewModelTest.kt | endOfList_should_TriggerExactlyOneInFlightRequest_When_ScrolledToEnd | Unit | No request while one is running |
| S7 | businessTest/.../navigation/NavigateUnloadedJournalTest.kt | navigate_should_CallEnsureJournalLoaded_When_DateShapedQueryOrCalendarJump | Unit | Search copy states "loaded journals only" |
| S7 | businessTest/.../db/JournalRepairTest.kt | repair_should_WriteFileViaGraphWriterWithWatcherSuppression_When_ConfirmedGhostDate | Integration | `- met alex` file, re-run yields empty plan |
| S7 | businessTest/.../db/JournalRepairTest.kt | repair_should_SkipFileAppeared_When_FileExistsAtExecution | Unit | Error path, never overwrite |
| S7 | businessTest/.../db/JournalRepairTest.kt | repair_should_WriteNothing_When_NotConfirmed | Unit | Safe default for ghosts |
| S8 | commonTest/.../console/ConsoleSessionTest.kt | run_should_RecordHistoryAndBlock_When_CommandSucceeds | Unit | Happy path |
| S8 | commonTest/.../console/ConsoleSessionTest.kt | cancel_should_EndBlockCancelledKeepPartialOutput_When_CommandRunning | Unit | Cancel |
| S8 | commonTest/.../console/ConsoleSessionTest.kt | run_should_EmitErrBlockAndStayAlive_When_CommandThrowsOutOfMemoryError | Unit | `catch Throwable` + CEH; `CancellationException` rethrown |
| S8 | commonTest/.../console/ConsoleSessionTest.kt | history_should_BeEmpty_When_NewSessionAfterRestart | Unit | In-memory only |
| S8 | commonTest/.../console/ScrollbackBufferTest.kt | append_should_EvictOldestWithMarker_When_5001stLineAdded | Unit | `[... 1 earlier lines dropped ...]` |
| S8 | commonTest/.../console/ScrollbackBufferTest.kt | append_should_TruncateLongLine_When_5000CharLine | Unit | 2,000 chars + "…(+3000 chars)" |
| S8 | commonTest/.../console/ScrollbackBufferPropertyTest.kt | buffer_should_NeverExceedLineAndByteCaps_When_AnyAppendSequence | Unit | Property |
| S8 | commonTest/.../console/CommandLineTokenizerTest.kt | tokenize_should_KeepQuotedSqlAsOneToken_When_QuotesPresent | Unit | Happy path |
| S8 | commonTest/.../console/CommandLineTokenizerTest.kt | tokenize_should_ReturnUsageError_When_QuoteUnterminated | Unit | Error path |
| S8 | commonTest/.../console/CommandLineTokenizerTest.kt | tokenize_should_RoundTripRender_When_AnyGeneratedTokenList | Unit | Property |
| S8 | commonTest/.../console/DeveloperModeGateTest.kt | run_should_ReturnDeniedDeveloperModeOff_When_FlagAbsent | Unit | Dispatcher gate |
| S8 | commonTest/.../console/DeveloperModeGateTest.kt | disableFlag_should_CancelCommandDisarmShellClearScrollback_When_ConsoleOpen | Unit | UX-22 logic |
| S8 | businessTest/.../console/ConsoleCompositionTest.kt | commands_should_SeeNewGraph_When_GraphSwitchedBetweenRuns | Integration | Per-run view resolution |
| S8 | businessTest/.../console/ConsoleCompositionTest.kt | block_should_EndGraphClosedDuringCommand_When_GraphClosesMidRun | Integration | No crash |
| S8 | androidUnitTest/.../console/ConsoleScreenTest.kt | consoleScreen_should_RenderBlocksWithHeaderChipFooter_When_CommandsRun | Integration | Compose in Robolectric |
| S9 | commonTest/.../console/SqlStatementSplitterPropertyTest.kt | split_should_NotSplitOnSemicolonInQuotesOrComments_When_AnyGeneratedSql | Unit | Property |
| S9 | jvmTest/.../console/SqlReadCommandTest.kt | sqlRead_should_RejectMultipleStatements_When_SelectThenDrop | Unit | `Invalid("multiple statements")`, nothing runs |
| S9 | jvmTest/.../console/SqlReadCommandTest.kt | sqlRead_should_RejectCteDeleteByEngine_When_ReadOnlyConnection | Integration | Real driver; pooled app writes unaffected |
| S9 | jvmTest/.../console/SqlReadCommandTest.kt | sqlRead_should_ReturnUnsupported_When_ReadConnectionFactoryThrows | Unit | Fail closed, no classifier fallback |
| S9 | jvmTest/.../console/SqlReadCommandTest.kt | sqlRead_should_DenyCredentialTable_When_SelectFromGitConfig | Unit | `Denied("credential table")` |
| S9 | jvmTest/.../console/SqlReadCommandTest.kt | sqlRead_should_CapAt500RowsWithMarker_When_100kRows | Integration | Footer, 2 KB cell cap |
| S9 | jvmTest/.../db/SqlReadOnlySpikeTest.kt | readOnlyConnection_should_RejectUpdateAndCteDelete_When_OpenedReadOnlyJvm | Integration | Spike 0.2a |
| S9 | androidInstrumentedTest/.../db/DbSwapSpikeTest.kt | swap_should_PassIntegrityAndRowCount_When_ReaderClosedStaleWalDeleted | Integration | Pre-mortem #5; executed on device in Story 7.2 |
| S9 | jvmTest/.../console/SqlWriteCommandTest.kt | sqlWrite_should_RunInsideActorExecuteAndReportRowsAffected_When_Confirmed | Integration | "1 row affected -- DB-only: not written to markdown"; observers refresh |
| S9 | jvmTest/.../console/SqlWriteCommandTest.kt | sqlWrite_should_BeDeniedEvenAfterConfirm_When_DropAttachWritableSchemaFtsVacuumCredential | Unit | Deny table |
| S9 | jvmTest/.../console/SqlWriteCommandTest.kt | sqlWrite_should_ReturnWriteFailedNotRawException_When_ConstraintViolated | Unit | `DomainError.DatabaseError.WriteFailed` |
| S9 | jvmTest/.../console/SqlWriteCommandTest.kt | sqlWrite_should_RefuseWithNothingChangedAndNoBackup_When_NoReadConnection | Unit | Fail closed: dry-run count needs the read connection; actor not called |
| S9 | jvmTest/.../console/DbBackupTest.kt | backup_should_CreateValidSnapshotBeforeStatement_When_FirstWriteOfSession | Integration | `PRAGMA integrity_check` ok |
| S9 | jvmTest/.../console/DbBackupTest.kt | backup_should_SnapshotAgain_When_NewDestructiveClassOrNoWhere | Integration | Per-class snapshots |
| S9 | jvmTest/.../console/DbBackupTest.kt | backup_should_EvictOldest_When_SixSnapshots | Integration | Keep 5; path absent from `diag` |
| S9 | jvmTest/.../console/ConsoleWriteSafetyDrillTest.kt | drill_should_RestoreRowCountsAndChecksum_When_SeededWritesAcrossAllDestructiveClasses | Integration | Metric 5a: update, delete and no-WHERE writes, a valid snapshot before each class's first write, restore of the earliest returns counts and checksum equal to pre-write |
| S9 | businessTest/.../console/BackupsCommandTest.kt | backups_should_ListNewestFirstMarkUnreadableAndExplainEmpty_When_Run | Unit | Newest first, `integrity_check` failure listed not hidden, empty-state text (UX-85) |
| S9 | businessTest/.../console/BackupsCommandTest.kt | backupsRestore_should_DelegateToRestoreWithDbOnlyRowsGuard_When_Confirmed | Unit | Same path as `graph restore-backup`; list survives a new session (persisted folder) |
| S9 | jvmTest/.../console/DbRestoreTest.kt | restore_should_SwapAndReload_When_SnapshotValid | Integration | Success round trip |
| S9 | jvmTest/.../console/DbRestoreTest.kt | restore_should_ReplaceNothing_When_SnapshotFailsIntegrityCheck | Integration | Error path |
| S9 | jvmTest/.../console/DbRestoreTest.kt | restore_should_RestoreOriginal_When_CopyFailsMidSwap | Integration | Rollback of rename |
| S9 | jvmTest/.../console/RawWriteCascadeTest.kt | rawWrite_should_TriggerNoGraphWriterWriteAndTrackDirty_When_AppIdle | Integration | ADR-002 A4; `graph reload` disk wins after `DbOnlyRowsGuard`; also asserts trigger-maintained FTS tables (`blocks_fts`, `pages_fts`) and search results after a raw `blocks`/`pages` write |
| S9 | businessTest/.../db/DirectSqlWriteOptInAllowlistTest.kt | optInSites_should_MatchAllowlistWithFunctionLevelWriter_When_SourceAudited | Unit | Source audit; new site fails |
| S9 | commonTest/.../console/SqlWriteHintTest.kt | hint_should_ClassifyFirstKeywordAfterCommentsAndNeverSuggestSqlBangForDenyListed_When_Typed | Unit | UX-100: insert/update/delete/replace/create/alter/drop/vacuum/writing pragma; leading comments and whitespace; multi-line first statement only; deny-listed statements get the not-allowed hint and no switch chip |
| S10 | businessTest/.../console/GitCommandTest.kt | gitReadCommands_should_ReturnBoundedStrippedOutput_When_StatusLogRefsRemoteLsRemote | Integration | Log capped, userinfo stripped |
| S10 | businessTest/.../console/GitCommandTest.kt | gitFetch_should_RefuseSyncInProgress_When_GitWriteLockHeld | Unit | Not concurrent |
| S10 | businessTest/.../console/GitCommandTest.kt | gitFetchMerge_should_BalanceBusyCounter_When_SuccessOrFailure | Unit | `GitSyncBusyCounter` inc/dec |
| S10 | businessTest/.../console/GitCommandTest.kt | gitSetBranch_should_UseBranchRepairServiceAndPrintDiff_When_Confirmed | Unit | `remote_branch: main -> master` |
| S10 | businessTest/.../console/GitCommandTest.kt | gitCommand_should_ReturnUnsupported_When_GitViewNull | Unit | Wasm |
| S10 | jvmTest/.../console/GitDoctorTest.kt | gitDoctor_should_PrintFieldsAndSuggestSetBranchMaster_When_ConfiguredMainOriginMaster | Integration | All fields incl. path-mode, first-sync state, staleness history and build stamp |
| S10 | jvmTest/.../console/GitDoctorTest.kt | gitDoctor_should_ExplainUnresolvedFields_When_RemoteUnreachable | Integration | Error path |
| S11 | commonTest/.../console/PathPolicyTest.kt | resolve_should_NeverEscapeAllowedRoots_When_AnyGeneratedDotDotSequence | Unit | Property |
| S11 | jvmTest/.../console/FsSymlinkTest.kt | fsCat_should_DenyOutsideAllowedRoots_When_SymlinkEscapesGraphRoot | Integration | `Denied("outside allowed roots")` |
| S11 | commonTest/.../console/PathPolicyTest.kt | resolve_should_Deny_When_PathIsSharedPrefsSshKeystoreOrVault | Unit | Deny list |
| S11 | jvmTest/.../console/FsCommandTest.kt | fsCat_should_ReadAtMost64KiBViaReadPrefixBytes_When_50MbFile | Integration | Truncated marker; never `readFile` |
| S11 | businessTest/.../console/FsCommandTest.kt | fsLs_should_UseFileSystemNotJavaIoFile_When_SafContentRoot | Unit | SAF via `FileSystem` |
| S11 | commonTest/.../platform/FileSystemDefaultsTest.kt | canonicalPathOrNull_should_DenyNonLexicalPaths_When_PlatformReturnsNull | Unit | wasm/iOS disabled |
| S12 | businessTest/.../console/DiagCommandTest.kt | diagGit_should_EqualCollectorGitSection_When_CloneConfiguredMain | Integration | Shows `resolve(origin/main)` unresolved, heads `master` |
| S12 | businessTest/.../console/DiagCommandTest.kt | diag_should_EqualLogsScreenReportModuloTimestamp_When_NoArgs | Unit | Parity |
| S12 | businessTest/.../console/SettingsCommandTest.kt | settingsList_should_PrintOnlyAllowlistNonSecretValues_When_Run | Unit | No enumeration, secrets never printed |
| S12 | businessTest/.../console/SettingsCommandTest.kt | settingsSet_should_DenyAndNotWrite_When_KeyOutsideAllowlist | Unit | Error path |
| S12 | businessTest/.../console/LogsCommandTest.kt | logs_should_FilterByLevelAndGrepBounded_When_FlagsGiven | Unit | `-n 200 --level warn --grep` |
| S12 | businessTest/.../console/GraphCommandTest.kt | graphReindex_should_StopDraining_When_Cancelled | Integration | Cancellable job |
| S12 | businessTest/.../console/GraphCommandTest.kt | graphJournalsRepair_should_OnlyInvokeJournalRepairService_When_Confirmed | Unit | No raw SQL / fs |
| S12 | businessTest/.../console/GraphCommandTest.kt | graphRegistry_should_LabelSafShadowGraphs_When_PersonalWikiEntries | Unit | Registry labelling |
| S12 | businessTest/.../console/DbOnlyRowsGuardTest.kt | guard_should_ExportDbOnlyJournalsThenRequireTypedConfirm_When_ReloadReindexOrRestoreWithGhosts | Integration | Journals 2026-10-07/09/10 exported to `console-recovery/` first; silent when clean; each of the three commands calls it |
| S12 | businessTest/.../console/DbOnlyRowsGuardTest.kt | guard_should_RefuseCommand_When_RecoveryExportFails | Unit | Error path |
| S12 | businessTest/.../console/ConsoleExportTest.kt | export_should_ShowRedactionPreviewAndNoteContentWarning_When_ExportTapped | Unit | Pre-mortem #7; also marks `RUNNING (partial)` in a running-command export |
| S13 | jvmTest/.../console/ProcessRunnerTest.kt | sh_should_RunEchoAndStripSecretsFromEnv_When_DesktopConfirmed | Integration | Env excludes `GITHUB_TOKEN`, `SSH_AUTH_SOCK`, 1Password vars |
| S13 | jvmTest/.../console/ProcessRunnerTest.kt | sh_should_NotDeadlockAndKillProcessTree_When_1MbStderrThenCancel | Integration | `ProcessHandle.descendants()`, timeout |
| S13 | commonTest/.../console/ShCommandTest.kt | sh_should_ReturnUnsupportedDesktopOnly_When_AndroidIosOrWasm | Unit | Error path; help lists disabled |
| S13 | commonTest/.../console/ShCommandTest.kt | sh_should_RequireSecondConfirmOncePerSession_When_FirstRun | Unit | Armed state in session |
| S13 | commonTest/.../console/ShCommandTest.kt | shHelp_should_StateBypassAndPlatformLimits_When_HelpFlagGiven | Unit | ux S9 `sh --help` |
| S14 | commonTest/.../console/ConsoleRegistryTest.kt | resolve_should_RunEchoWithArgs_When_CommandAddedToInjectedList | Unit | One-file addition; `help` lists it |
| S14 | commonTest/.../console/ConsoleCommandContractTest.kt | registry_should_FailContract_When_CommandOmitsRisk | Unit | Every `Risk` maps to a `ConfirmTier` |
| S14 | commonTest/.../console/ConsoleRegistryTest.kt | resolve_should_SuggestUpToThree_When_TypoGti | Unit | Error path |
| S14 | businessTest/.../console/ConsoleSourceAuditTest.kt | consolePackage_should_ImportNoGraphManagerRepositorySetOrGraphLoader_When_UnderCommands | Unit | Source audit |
| S15 | commonTest/.../console/ConsoleRegistryTest.kt | help_should_ListGitFsShDisabledWithReason_When_GitViewNull | Unit | Web subset |
| S15 | commonTest/.../console/ConsoleRegistryTest.kt | webSubset_should_ExposeHelpSettingsLogsExportOnly_When_NoReadOnlySqlConnection | Unit | `sql` only with read-only conn |
| S15 | build gate (Task 4.1b) | wasmJsCompile_should_Succeed_When_ConsolePackageAdded | Integration | `./gradlew :kmp:compileKotlinWasmJs -PenableJs=true` |
| N1 | commonTest/.../console/ConsoleSessionTest.kt | publish_should_BatchToAboutThreeSnapshots_When_500LinesIn10ms | Unit | 60 ms batching |
| N1 | commonTest/.../console/ConsoleSessionTest.kt | run_should_NotBlockCallerDispatcher_When_LongCommand | Unit | Off-main dispatch (test scheduler) |
| N1 | jvmTest/.../console/ConsoleBoundedStreamingTest.kt | stream_should_StayBoundedEndToEndIncludingExport_When_100kRowsUnderSmallHeap | Integration | Pre-mortem P3; injected `OutOfMemoryError` becomes an ERR block via the console scope handler |
| N1 | jvmTest/.../console/SqlReadCommandTest.kt | sqlRead_should_Timeout_When_QueryExceedsThirtySeconds | Unit | Default timeout, cancel interrupts |
| N1 | androidUnitTest/.../console/ConsolePerfTest.kt | composition_should_CompleteAndEvictFirstLine_When_5001LinesAdded | Integration | Robolectric headless |
| N2 | businessTest/.../db/LargeGraphWarmStartCrashTest.kt | warmStart_should_StayWithinBatchBounds_When_ConsoleAndLazyLoaderWired | Integration | Existing test must stay green |
| N2 | jvmTest/.../git/GitTransportFaultInjectionTest.kt | lsRemoteAndFetch_should_FailWithinTimeout_When_RemoteHangs | Integration | `GIT_TRANSPORT_TIMEOUT_SECONDS` |
| N3 | commonTest/.../console/ConsoleRedactorPropertyTest.kt | sink_should_RemoveSecretShapedTokens_When_EmbeddedInArbitraryText | Unit | Property; documents hex-encoded limit as expected-limit case |
| N3 | commonTest/.../console/ConsoleRedactorTest.kt | emit_should_ShowRedactedUserinfoAndMarker_When_OutputHasTokenUrl | Unit | `https://[redacted]@github.com/o/r.git` |
| N3 | commonTest/.../console/ConsoleRedactorTest.kt | emit_should_ReplaceBearerGithubPatGlpatAkiaPem_When_Present | Unit | Pattern table |
| N3 | commonTest/.../console/ConsoleRedactorTest.kt | emit_should_ReplaceExactVaultSecret_When_NoKnownPrefix | Unit | Exact-secret match |
| N3 | jvmTest/.../console/ConsolePatLeakageAuditTest.kt | spillAndCsvFiles_should_ContainNoSeededSecret_When_WrittenToConsoleExports | Integration | Metric 5c: seeded PAT, userinfo and exact vault secret absent from spill and per-cell CSV on disk; zero matches across the whole redaction suite (hex/base64 limit reported separately) |
| N3 | jvmTest/.../console/ConsolePatLeakageAuditTest.kt | auditLogAndHistory_should_ContainRedacted_When_LsRemoteWithTokenUrl | Integration | Output, history, audit INFO line, export |
| N4 | businessTest/.../console/ConsoleExportTest.kt | export_should_WriteLocalFileAndNeverUseNetwork_When_Run | Unit | Local file / share provider only |
| N4 | businessTest/.../console/ConsoleExportTest.kt | exportFiles_should_LiveUnderConsoleExportsDeleteOnSessionEndAndCapAtTwentyMb_When_Run | Unit | Never under a graph folder (next sync stages nothing); removed-file block says so (UX-90) |
| N4 | commonTest/.../console/ConsoleSessionTest.kt | history_should_NotBePersisted_When_SessionDisposed | Unit | In-memory only |
| N5 | businessTest/.../console/ConsoleSourceAuditTest.kt | commonMainConsole_should_HaveNoJavaImports_When_Audited | Unit | Source audit (wasm safety) |
| N5 | businessTest/.../console/ConsoleSourceAuditTest.kt | consoleSession_should_OwnScopeAndNotAcceptRememberCoroutineScope_When_Audited | Unit | Scope ownership audit |
| N5 | businessTest/.../db/MigrationRunnerSchemaSyncTest.kt | migrationRunner_should_StayInSyncWithSq_When_NoSchemaChange | Unit | Existing; `git diff --stat -- '*.sq' '*MigrationRunner*'` empty |
| N5 | businessTest/.../git/GitSyncServiceInvariantTest.kt | gitErrors_should_BeEitherLeftNeverThrown_When_RepositoryFails | Unit | `Either` boundary |
| N6 | businessTest/.../console/ConsoleAuditLogTest.kt | run_should_LogInfoWithNameDurationCountsOutcome_When_CommandCompletes | Unit | INFO audit, args redacted |
| N6 | businessTest/.../console/ConsoleAuditLogTest.kt | sqlWrite_should_LogWarnWithStatementRowsAndBackupPath_When_Executed | Unit | WARN |
| N6 | businessTest/.../git/GitSyncServiceScheduledTest.kt | runScheduledSync_should_LogTriggerDecisionAndReason_When_SkippedOrFetchOnly | Unit | `Skip(RepoState/FirstSyncUnconfirmed)` and `FetchOnly(Saf)` logged; cold fetch logs `behindCount` |
| N7 | commonTest/.../console/ConfirmPolicyTest.kt | tierFor_should_MapRiskToTier_When_AllRisks | Unit | READ none, STATE_CHANGE inline, DB_WRITE modal, EXEC second confirm |
| N7 | commonTest/.../console/TypedConfirmMatcherTest.kt | matcher_should_TrimIgnoreCaseAndRequireDigitsOnlyForCounts_When_Typed | Unit | Table: `"  PAGES "` matches `pages`; `214 files` does not match `214`; mismatch hint text |
| N7 | commonTest/.../console/TypedConfirmMatcherTest.kt | massChangeAnswer_should_NeverAppearInPromptTextAndFailClosedOnCollision_When_AnyGeneratedDeletionSet | Unit | `Property` (UX-103): for generated deletion sets the derived expected string differs from every number token in the headline and question text; fallback order journals / non-journals / folders; all-collide withholds the confirm; the mismatch hint never contains the expected value |
| N7 | commonTest/.../console/ConfirmPolicyTest.kt | cancel_should_BeUnavailable_When_ActorWriteStarted | Unit | "Writing, can't cancel" |
| N7 | businessTest/.../console/SqlWriteCommandTest.kt | sqlWrite_should_NotRunAndSayNothingChanged_When_DryRunOrBackupFails | Unit | Error path |
| N7 | androidUnitTest/.../SettingsDeveloperCategoryTest.kt | developerCategory_should_ShowOnlyToggleAndWarning_When_FreshInstall | Integration | Default off hides console rows |
| N7 | commonTest/.../console/DeveloperModeGateTest.kt | flag_should_BeOff_When_SettingsReadThrows | Unit | ux S6: read failure defaults to off |
| N7 | androidUnitTest/.../console/ConsoleRetentionTest.kt | session_should_SurviveConfigChangeAndManifestShouldDeclareConfigChanges_When_OrientationApplied | Integration | Asserts `androidApp/src/main/AndroidManifest.xml:44` `configChanges` includes orientation, screenSize and uiMode; no Activity recreation |
| N7 | androidUnitTest/.../git/AutoBackupRulesTest.kt | backupRules_should_ExcludeConsoleBackups_When_ManifestParsed | Unit | `console-backups/` excluded |

## UX Acceptance Tests

Tool legend: **Robolectric** = `androidUnitTest` Compose test (headless, `./gradlew :kmp:testDebugUnitTest`; no display needed); **commonTest** = pure logic test; **jvmTest-UI** = desktop-only, run via `scripts/jvm-display-check.sh -- ...`; **Manual** = owner device pass (Story 7.1/7.2) because it needs TalkBack, a real IME or a real display. Playwright N/A (Compose; Web console subset has no UX criterion needing a browser).

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| UX-01 red badge exact text | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | badge_should_ShowBranchNotFoundTextRed_When_StateIsRemoteBranchNotFound | Robolectric | Stored `main`, error state; assert text and red, not green |
| UX-02 repaired in <=3 taps, no typing | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | repairFlow_should_CompleteInThreeTapsNoTypingAndNotSyncBeforeSyncNow_When_BadgeThenUseMasterThenSyncNow | Robolectric | Tap badge, tap "Use 'master'" (assert no `sync` call and the first-sync review is showing), tap "Sync now"; count interactions = 3; "Not now" leaves branch saved, zero syncs and the `Review first sync` badge reopens the review; wired with no scheduler present (A1-only build) |
| UX-03 sheet content | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | sheet_should_ShowConfiguredAvailableAndNothingPulled_When_Opened | Robolectric | Assert three strings |
| UX-04 exact rewrite shown | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | sheet_should_ShowRewriteLineBeforePrimaryPress_When_Opened | Robolectric | `remote_branch: main -> master` visible pre-click |
| UX-05 ambiguous: no preselect | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | applyButton_should_BeDisabledAndNoRadioSelected_When_Ambiguous | Robolectric | `Ambiguous(["main","master"])`; choose enables |
| UX-06 dismiss paths keep config | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | dismiss_should_KeepStoredBranchAndBadge_When_NotNowXBackEscape | Robolectric | Each of four dismissals; fake config unchanged |
| UX-07 read-back / save failure | businessTest/.../ui/GitSyncCoordinatorTest.kt | apply_should_ShowCouldntSaveWithRetryClose_When_ReadBackDiffers | commonTest | Fake config repo returns different value |
| UX-08 repair routes to review, Sync now syncs, result line | businessTest/.../ui/GitSyncCoordinatorTest.kt | apply_should_OpenFirstSyncReviewWithoutSyncingAndShowPulledNCommits_When_RepairSucceedsThenSyncNowTapped | commonTest | Repair success: zero sync calls; after Sync now persistent result line "Pulled N commits" / "Already up to date" green (state, not a toast; semantics in UX-87) |
| UX-09 amber when still ahead | businessTest/.../ui/GitSyncCoordinatorTest.kt | result_should_BeAmberWithDetails_When_BehindPositiveAfterSync | commonTest | `behind>0` after sync |
| UX-10 change back | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | sheet_should_StayOpenWithChangeBackOffer_When_PostRepairSyncFails | Robolectric | New error + "Change back to 'main'" |
| UX-11 unreachable variant | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | sheet_should_NeverClaimBranchMissingAndOfferRetry_When_Unreachable | Robolectric | `Unreachable` variant text |
| UX-12 empty remote variant | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | sheet_should_ShowRemoteIsEmptyNoUseButton_When_RemoteEmpty | Robolectric | Copy details + Close only |
| UX-13 copy details sanitized | commonTest/.../git/BranchRepairServiceTest.kt | copyDetails_should_ContainNoCredentialsOrUserinfo_When_UrlHasToken | commonTest | Assert absence |
| UX-14 banner once per failed sync | androidUnitTest/.../ui/GitDetectionBannerTest.kt | banner_should_ShowOncePerFailedSyncDismissKeepsBadge_When_Mismatch | Robolectric | Two renders, dismiss, badge persists |
| UX-15 Step 4 prefill | androidUnitTest/.../ui/screens/git/GitSetupBranchStepTest.kt | step4_should_PrefillMasterTaggedRemoteDefaultAndAllowOther_When_TestRemoteOk | Robolectric | Dropdown + typed override |
| UX-16 nonexistent branch blocks Save | androidUnitTest/.../ui/screens/git/GitSetupBranchStepTest.kt | save_should_BeBlockedWithAvailableList_When_NopeTyped | Robolectric | Message "Branch 'nope' not found on remote. Available: ..."; Back returns to Step 4 |
| UX-17 badge tap routing | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | tap_should_RouteRepairCredentialsOrRetry_When_BranchAuthOrOtherError | Robolectric | Three error kinds |
| UX-18 (A11y) TalkBack + focus | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | badge_should_HaveContentDescriptionAndFocusMoveIntoSheet_When_MissingBranch | Robolectric | Semantics "Sync error — tap to fix"; focus in/out. Real TalkBack pass: Manual (Story 7.2) |
| UX-19 (A11y) keyboard/focus/48dp | androidUnitTest/.../ui/screens/git/BranchRepairA11yTest.kt | controls_should_BeKeyboardOperableAtLeast48dpWithSelectionState_When_S2S3Open | Robolectric | Tab order, size assertions, radio selected state |
| UX-20 fresh install hides console | androidUnitTest/.../SettingsDeveloperCategoryTest.kt | developerCategory_should_ShowOnlyToggleAndWarningNoConsoleRows_When_FreshInstall | Robolectric | Warning text exact; no row in Settings or Logs overflow |
| UX-21 enable reveals without restart | androidUnitTest/.../SettingsDeveloperCategoryTest.kt | consoleRows_should_AppearInSettingsAndLogsOverflow_When_ToggleOn | Robolectric | Same composition, flip flag |
| UX-22 disable closes console | commonTest/.../console/DeveloperModeGateTest.kt | disable_should_CloseScreenCancelCommandDisarmClear_When_ConsoleOpen | commonTest | Plus Robolectric route-pop assertion in ConsoleScreenTest |
| UX-23 Open in dev console gated | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | openInConsole_should_PrefillGitDoctorNotRunAndOnlyShowWhenDevModeOn_When_Tapped | Robolectric | Visible only with flag; input prefilled, zero runs |
| UX-24 <=2 taps from Logs | androidUnitTest/.../console/ConsoleScreenTest.kt | logsOverflow_should_OpenConsoleInTwoTaps_When_DevModeOn | Robolectric | Overflow, Dev console |
| UX-25 git doctor in 2 taps | androidUnitTest/.../console/ConsoleScreenTest.kt | chipThenRun_should_ExecuteGitDoctor_When_TwoTapsNoTyping | Robolectric | Chip, Run |
| UX-26 journals-diff in 2 taps | androidUnitTest/.../console/ConsoleScreenTest.kt | chipThenRun_should_ExecuteJournalsDiff_When_TwoTapsNoTyping | Robolectric | Chip `diag journals-diff`, Run |
| UX-27 block header/chip vocabulary | androidUnitTest/.../console/ConsoleScreenTest.kt | block_should_ShowHeaderDurationAndTextStatusChipFromClosedVocabulary_When_CommandFinishesAwaitsConfirmRunsOrTimesOut | Robolectric | OK/ERR/CANCELLED/TRUNCATED/AWAITING CONFIRM/RUNNING/TIMEOUT: each state renders its text chip; a value outside the set fails the test |
| UX-28 cancel control | androidUnitTest/.../console/ConsoleScreenTest.kt | cancel_should_EndBlockCancelledKeepPartial_When_ButtonCaretCOrCtrlC | Robolectric | Three triggers |
| UX-29 auto-follow pause | androidUnitTest/.../console/ConsoleScreenTest.kt | autoFollow_should_PauseAndShowJumpToEnd_When_UserScrollsUp | Robolectric | Scroll up, tap, resumes |
| UX-30 multi-line paste not run | androidUnitTest/.../console/ConsoleInputTest.kt | paste_should_NotRunUntilRunOrCtrlEnter_When_MultiLine | Robolectric | Paste, assert zero runs |
| UX-31 extra keys row | androidUnitTest/.../console/ConsoleInputTest.kt | extraKeys_should_InsertOrActAsLabelled_When_Tapped | Robolectric | Each key; real IME overlap: Manual (7.2) |
| UX-32 chips insert, completion not covering | androidUnitTest/.../console/ConsoleInputTest.kt | chip_should_InsertTextCursorAtEnd_When_Tapped | Robolectric | `git ls-remote`; completion row bounds do not intersect output |
| UX-33 history nav | androidUnitTest/.../console/ConsoleInputTest.kt | history_should_StepAndSheetTapInsertLongPressRun_When_ThreePriorCommands | Robolectric | Up/Down + sheet gestures |
| UX-34 history empty after restart | commonTest/.../console/ConsoleSessionTest.kt | newSession_should_HaveEmptyHistoryAndScrollback_When_Restarted | commonTest | Fresh session |
| UX-35 rotation retains state | androidUnitTest/.../console/ConsoleRetentionTest.kt | rotation_should_KeepInputScrollbackRunningAndPendingConfirm_When_OrientationChangedWithoutRecreation | Robolectric | Apply orientation/size qualifiers (the manifest `configChanges` means no Activity recreation); state in the `remember {}`-hosted `ConsoleSession`; manifest assertion guards the mechanism |
| UX-36 typo suggestions | commonTest/.../console/ConsoleRegistryTest.kt | unknown_should_SuggestGitAsTappableChips_When_Gti | commonTest | Message exact, <=3 chips |
| UX-37 usage + help chip, input kept | androidUnitTest/.../console/ConsoleScreenTest.kt | wrongArgs_should_ShowUsageAndHelpChipKeepInput_When_BadArguments | Robolectric | Assertions on input text |
| UX-38 SQL error verbatim | jvmTest/.../console/SqlReadCommandTest.kt | sqlError_should_ShowSqliteMessageVerbatimAndKeepInput_When_SyntaxError | commonTest | Plus input retention in Robolectric screen test |
| UX-39 scrollback overflow marker | androidUnitTest/.../console/ConsolePerfTest.kt | top_should_ShowEarlierLinesDroppedMarker_When_Over5000Lines | Robolectric | UI responsive (completes) |
| UX-40 graph closed message | businessTest/.../console/ConsoleCompositionTest.kt | block_should_SayGraphClosedDuringCommand_When_GraphClosesMidRun | commonTest | No crash |
| UX-41 disabled with reason | commonTest/.../console/ConsoleRegistryTest.kt | helpAndRun_should_PrintSameReason_When_GitFsShDisabled | commonTest | Web and android `sh` |
| UX-42 (redaction) grep exported transcript | jvmTest/.../console/ConsolePatLeakageAuditTest.kt | exportedTranscript_should_NotContainPat_When_SessionRanTokenCommands | commonTest | Grep for PAT; documented hex limit |
| UX-43 sync in progress | businessTest/.../console/GitCommandTest.kt | gitFetch_should_ShowSyncInProgress_When_LockHeld | commonTest | Message exact |
| UX-44 (A11y) block focusable summary | androidUnitTest/.../console/ConsoleA11yTest.kt | block_should_BeSingleFocusableWithSummaryDescription_When_Streaming | Robolectric | "Command git status, succeeded in 120 ms, 14 lines"; no per-line focus steal |
| UX-45 (A11y) 48dp labelled controls | androidUnitTest/.../console/ConsoleA11yTest.kt | chipsKeysActions_should_Be48dpWithDescriptiveLabels_When_Rendered | Robolectric | "Control C, cancel running command" |
| UX-46 (A11y) keyboard shortcuts | androidUnitTest/.../console/ConsoleA11yTest.kt | shortcuts_should_Work_When_UpDownTabCtrlCCtrlLCtrlEnterEsc | Robolectric | Key events |
| UX-47 (A11y) contrast and text prefix | androidUnitTest/.../console/ConsoleA11yTest.kt | output_should_MeetFourPointFiveContrastAndPrefixErrWarn_When_LightAndDark | Robolectric | Theme-token contrast calc; real eyes pass: Manual (7.2, G15) |
| UX-48 read tier no confirm | commonTest/.../console/ConfirmPolicyTest.kt | readCommands_should_RunWithoutConfirm_When_StatusSelectDiag | commonTest | Tier NONE |
| UX-49 inline confirm | androidUnitTest/.../console/ConfirmDialogsTest.kt | setBranch_should_ShowInlineConfirmEchoAndReadBack_When_Run | Robolectric | Cancel makes no change; Confirm shows read-back |
| UX-50 Enter never confirms | commonTest/.../console/ConsoleSessionTest.kt | secondSubmit_should_CancelPendingConfirmSuperseded_When_EnterPressed | commonTest | "Cancelled: superseded" |
| UX-51 SQL write modal content | androidUnitTest/.../console/ConfirmDialogsTest.kt | modal_should_ShowStatementTablesDryRunCountAndBackupName_When_SqlWrite | Robolectric | Pre-execution |
| UX-52 verb+count, default Cancel | androidUnitTest/.../console/ConfirmDialogsTest.kt | modal_should_LabelUpdate12RowsDefaultFocusCancelEscapeCancels_When_Opened | Robolectric | Focus assertion |
| UX-53 type table name | androidUnitTest/.../console/ConfirmDialogsTest.kt | destructiveButton_should_EnableOnlyAfterExactTableName_When_NoWhere | Robolectric | Wrong text blocks |
| UX-54 denied never shows confirm | commonTest/.../console/SqlWriteClassifierTest.kt | classify_should_ReturnDeniedWithReasonAndNoConfirm_When_DropAttachPragmaFtsVacuumMulti | commonTest | "Denied: <reason>" |
| UX-55 nothing changed on failure | businessTest/.../console/SqlWriteCommandTest.kt | block_should_SayNothingWasChangedWithReason_When_DryRunOrBackupFails | commonTest | Zero writes |
| UX-56 result + persistent Restore | androidUnitTest/.../console/ConfirmDialogsTest.kt | result_should_ShowRowsDbOnlyBackupPathAndPersistentRestore_When_WriteDone | Robolectric | Not a timed snackbar (advance clock, still present) |
| UX-57 restore confirm/failure | androidUnitTest/.../console/ConfirmDialogsTest.kt | restore_should_ConfirmLaterChangesLostAndShowErrorWithPath_When_Fails | Robolectric | Failure injected |
| UX-58 first sh second confirm | commonTest/.../console/ShCommandTest.kt | firstSh_should_ConfirmBypassTextThenShowArmedChip_When_Desktop | commonTest | Chip stays; later runs no prompt; desktop display via jvmTest-UI |
| UX-59 unarm paths | commonTest/.../console/ConsoleSessionTest.kt | shell_should_BeUnarmed_When_DisarmLeaveDevModeOffOrClose | commonTest | Four triggers |
| UX-60 journals-repair behavior | businessTest/.../db/JournalRepairTest.kt | repair_should_ListFilesNeverOverwriteReportSkipped_When_FileAppeared | commonTest | Plus list echoed in confirm |
| UX-61 (A11y) modals focus trap | androidUnitTest/.../console/ConsoleA11yTest.kt | modals_should_TrapFocusAnnounceTitleAndHaveExit_When_Open | Robolectric | No auto-accept on timeout (advance clock) |
| UX-62 export file + Share/Save | businessTest/.../console/ConsoleExportTest.kt | export_should_NameFileTimestampedAndOfferShareAndSave_When_Run | commonTest | `console-<yyyyMMdd-HHmmss>.txt` |
| UX-63 export empty disabled | androidUnitTest/.../console/ConsoleScreenTest.kt | export_should_BeDisabledNothingToExportYet_When_TranscriptEmpty | Robolectric | Reason text |
| UX-64 export failure | androidUnitTest/.../console/ConsoleScreenTest.kt | export_should_ShowReasonRetryAndCopyFallback_When_ShareFails | Robolectric | Injected failure |
| UX-65 spill to file | businessTest/.../console/ConsoleExportTest.kt | output_should_SpillToFileWithShareAction_When_LargerThanScrollback | commonTest | Path printed |
| UX-66 SQL grid | androidUnitTest/.../console/SqlResultGridTest.kt | grid_should_ShowStickyColumnDimNullEllipsisAndExportCsv_When_12x4Result | Robolectric | Tap-to-expand; 2 MB cell shows 2 KB + "(+N chars)" |
| UX-67 row cap / zero rows | jvmTest/.../console/SqlReadCommandTest.kt | footer_should_SayTruncatedAtRowCapOrZeroRows_When_500Plus_Or_Empty | commonTest | Exact strings |
| UX-68 doctor 80 cols | jvmTest/.../console/GitDoctorTest.kt | gitDoctor_should_FitEightyColumnsStripUserinfoAndEndWithSuggestion_When_Run | commonTest | Line-length assertion |
| UX-69 journals-diff format | businessTest/.../console/GraphCommandTest.kt | journalsDiff_should_SeparateRecentFromOlderAndCapFiftyWithMore_When_Run | commonTest | "+N more" |
| UX-70 never green when unmerged | businessTest/.../ui/GitSyncCoordinatorTest.kt | syncResult_should_BeAmberNotGreen_When_RemoteCommitsUnmerged | commonTest | Badge color state |
| UX-71 fetch-only badge | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | badge_should_ShowUpdatesFetchedAmber_When_ColdOutcomeFetchedAndBehindPositive | Robolectric | Exact text for app-closed and SAF reasons |
| UX-72 launch banner | androidUnitTest/.../ui/GitDetectionBannerTest.kt | banner_should_ShowUpdatesWaitingWithReviewDismiss_When_BehindPositiveAtLaunch | Robolectric | Dismiss hides until the behind-count changes |
| UX-73 read-only sheet pre-repair | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | tap_should_OpenReadOnlySheetWithCopyDetailsNoUseButton_When_RepairUiNotShipped | Robolectric | Gate-open variant |
| UX-74 (A11y) restore a11y | androidUnitTest/.../console/ConsoleA11yTest.kt | restoreStates_should_BeKeyboardOperableAndAnnounceTitles_When_Opened | Robolectric | Confirm and failure states |
| UX-75 first-sync preview | androidUnitTest/.../ui/screens/git/FirstSyncPreviewSheetTest.kt | badge_should_ReviewFirstSyncAndPreviewBeReadOnly_When_NoConfirmation | Robolectric | Counts, strays tagged, 20 local commits, files-to-delete; only a successful manual sync unblocks |
| UX-76 staleness chip | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | chip_should_ShowLastMergedAndBehindAmberAfterTwiceInterval_When_Stale | Robolectric | Fetch-only reason text, never green; notification once per change (SyncNotifierTest) |
| UX-77 mass-change prompt | businessTest/.../ui/GitSyncCoordinatorTest.kt | massChange_should_OfferTypedCountConfirmOnlyInForeground_When_GuardHit | commonTest | Scheduled run never offers it; expected number derived from the list (UX-103) |
| UX-78 DB-only rows confirm | businessTest/.../console/DbOnlyRowsGuardTest.kt | confirm_should_ListGhostsStateExportAndRequireTypedText_When_ReloadWithDbOnlyRows | commonTest | Export failure refuses |
| UX-79 read-only variant sheets | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | sheets_should_BeReadOnlyWithCopyDetailsNoRepairButton_When_InvariantDetachedEmptyRepairMarkersMassChange | Robolectric | No credentials in Copy details; ships ungated |
| UX-80 lookup loading/timeout/cancel (S2, S5) | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | sheet_should_ShowCheckingWithSkipAndFallBackToAmbiguousAtFifteenSeconds_When_SeveralHeadsLookupHangs | Robolectric | Fake clock; single head needs no wait; no "branch missing" text while loading or after timeout; Step 4/5 twin in `GitSetupBranchStepTest` (`Skip`, free-text fallback) |
| UX-81 first-sync preview loading (S14) | androidUnitTest/.../ui/screens/git/FirstSyncPreviewSheetTest.kt | preview_should_ShowLocalCommitsAtOnceKeepSyncNowDisabledAndOfferRetry_When_RemoteHangs | Robolectric | Cancel stops the check; timeout text exact; closing cancels; `Sync without preview` appears only in the timeout state (UX-104) |
| UX-82 extra keys fit 360 dp | androidUnitTest/.../console/ConsoleInputTest.kt | extraKeys_should_FitRowAWithoutScrollAndScrollRowBWithFade_When_Width360dpAndFontScale1_5 | Robolectric | Qualifier `w360dp`; Row A all visible, Row B scrollable with fade, keys >= 48 x 48 dp, text keys become icons at >= 1.5x; real hardware: Manual (IME overlap pass) |
| UX-83 soft-keyboard vertical space | androidUnitTest/.../console/ConsoleInputTest.kt | layout_should_KeepMinOutputAndCollapseRowsInOrder_When_ImeHeightGrowsInLandscape | Robolectric | Simulated IME insets; order completion -> snippets -> Row B behind `Sym`; input line and Row A never hide; IME no-extract/no-fullscreen flags asserted where exposed; real IME: Manual |
| UX-84 IME flags per field | androidUnitTest/.../console/ConsoleInputTest.kt | fields_should_DisableAutocorrectCapsAndSuggestionsAndUseNumericKeypadForCounts_When_Rendered | Robolectric | `KeyboardOptions` asserted for console input, SQL input, typed-name field, typed-count field, Step 4 branch field |
| UX-85 durable backups list and restore | androidUnitTest/.../ui/screens/ConsoleBackupsScreenTest.kt | backupsScreen_should_ListNewestFirstMarkUnreadableAndRestoreThroughConfirm_When_DevModeOn | Robolectric | Visible only with developer mode; survives new session (persisted folder); empty state; keyboard operable, 48 dp rows; command twin in `BackupsCommandTest` |
| UX-86 banner precedence | commonTest/.../git/BannerPrecedenceTest.kt | precedence_should_ShowExactlyOneBannerInOrder_When_AnySubsetOfFourConditions | commonTest | Same 16-subset table as the S6 requirement row; Robolectric render check of the top banner in `GitDetectionBannerTest` |
| UX-87 persistent result line | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | resultLine_should_PersistAsPoliteLiveRegionUntilNextSyncOrDismiss_When_SyncFinishes | Robolectric | Advance the clock: line still present, not a timed toast; announced once; labelled Dismiss |
| UX-88 live regions and focus (A11y) | androidUnitTest/.../console/ConsoleA11yTest.kt | confirmBlock_should_AnnounceOnceFocusCancelAndReturnFocusToOpener_When_ConfirmsAndDialogsClose | Robolectric | AWAITING CONFIRM assertive once, focus on Cancel; start/finish chip announcements; focus return after SQL-write, no-WHERE, shell-arm and Restore dialogs; no per-line announcements |
| UX-89 notification permission flow | androidUnitTest/.../git/SyncNotifierTest.kt | permissionFlow_should_AskInContextOnlyAndShowOpenSettings_When_PermanentlyDeniedOnApi33 | Robolectric | Rationale before system request; Not now / denied / permanent states; below API 33 no request; sync unaffected; real device: Manual (Story 7.1b) |
| UX-90 spill/CSV hygiene | businessTest/.../console/ConsoleExportTest.kt | exportFiles_should_BeRedactedBeforeWriteAppPrivateSessionScopedAndCapped_When_SpillAndCsvWritten | commonTest | Seeded secrets absent on disk; `console-exports/` only; deleted on session end; removed-file block text; content warning shown |
| UX-91 typed-confirm rules and dry-run caveat | commonTest/.../console/TypedConfirmMatcherTest.kt | matcher_should_TrimIgnoreCaseAndRequireDigitsOnlyForCounts_When_Typed | commonTest | Plus `ConfirmDialogsTest` assertion of the "search-index rows are updated by triggers and are not counted" line in the SQL write modal |
| UX-92 plain-language copy | androidUnitTest/.../ui/UserFacingCopyAuditTest.kt | visibleText_should_ContainNoBannedJargon_When_EveryBadgeSheetBannerNotificationAndResultLineRenders | Robolectric | Banned words: symref, ls-remote, invariant, DB-only, tracking ref, refspec, detached HEAD, shallow, OID, SQLite, WAL; console output and Copy details exempt |
| UX-93 repair-sheet concurrency | androidUnitTest/.../ui/screens/git/BranchRepairSheetTest.kt | sheet_should_ShowChangedNoticeDisableActionsWhileSyncingAndCloseWhenFixed_When_StaleLockedOrResumed | Robolectric | Stale proposal notice and refreshed content; `A sync is running. Wait for it to finish.` with exactly the listed buttons disabled; re-enable on completion; resume closes with `Already fixed.`; double tap ignored; logic in the Unit rows of S3 |
| UX-94 badge/chip fit and icons at 360 dp | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | badgeAndChip_should_WrapStackKeep48dpTargetsAndUseDistinctIcons_When_Width360dpFontScale1And1_5 | Robolectric | `w360dp`: <= 2 lines (3 at 1.5), chip below when wrapped or < 400 dp, both targets >= 48 x 48 dp, ellipsized text keeps full description, amber triangle vs red octagon identity per variant |
| UX-95 (A11y) badge labels and announcements | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | badge_should_HaveVariantDescriptionWithWarningOrErrorPrefixAndAnnounceOncePerEntry_When_EachVariantRenders | Robolectric | Every variant row of the S1 table; button role; one polite announcement on entry, none for same-state retries; chip read as one item. Real TalkBack: Manual (Story 7.2) |
| UX-96 console and History empty states | androidUnitTest/.../console/ConsoleScreenTest.kt | emptyConsoleAndHistory_should_ShowHintChipsAndHistoryEmptyText_When_FirstOpenedAndAfterClear | Robolectric | Hint text exact, seeded chips present, hint returns after Clear, History sheet empty text and Close |
| UX-97 chip before any merge and preview empties | androidUnitTest/.../ui/components/SyncStatusBadgeTest.kt | chipAndPreview_should_ShowNoMergeYetNeutralAndEmptyPreviewTexts_When_NeverMergedAndNothingLocalOnly | Robolectric | `no merge yet · behind n` neutral, `checking…` while unknown, amber only after twice the interval; preview: `Nothing here that isn't on the remote yet.`, zero-files line hidden, `No other branches on the remote.` (`FirstSyncPreviewSheetTest` covers the preview half) |
| UX-98 journals-diff nothing missing | businessTest/.../console/JournalsDiffCommandTest.kt | journalsDiff_should_PrintAllPresentLineAndNoEmptyGroupHeaders_When_NothingMissing | commonTest | Exact `Recent (last 14 days): all 14 journals are in the database.` and the Older line |
| UX-99 symbols reachable | androidUnitTest/.../console/ConsoleInputTest.kt | extraKeysPage2_should_InsertBangPercentEqualsParensComma_When_MoreSymbolsKeyTapped | Robolectric | Pinned page key 48 dp with page description; each of `! % = ( ) ,` inserts its character; page choice persists; page key not inside the scrolling strip |
| UX-100 write hint under plain sql | businessTest/.../console/SqlWriteHintTest.kt | hint_should_OfferSwitchChipDenyRunAndDenyListedWording_When_WriteTypedUnderPlainSql | commonTest | Hint text, prefix-only rewrite keeps cursor, Run under `sql` gives the Denied text, deny-listed statement has no chip (classifier logic: the S9 Unit row; Robolectric render of the hint is covered by `ConsoleInputTest`) |
| UX-101 notification tap | androidUnitTest/.../git/SyncNotifierTest.kt | tap_should_OpenReviewOrStatusSheetAddOneBackStackEntryAndNotSync_When_AppClosedOrOpen | Robolectric | PendingIntent carries graph id; cold start and warm start destinations; `Up to date` when behind is 0; unknown graph wording; no second launch banner; routing table: `NotificationTapRouterTest` (S6 Unit row) |
| UX-102 (A11y) copy-range selection | androidUnitTest/.../console/ConsoleA11yTest.kt | block_should_StayOneNodeWithSelectTextAndCopySelectionActions_When_RangeSelectedAndCopied | Robolectric | One semantics node per block, custom actions `Select text` / `Copy selection`, `Copy selection` only while a range exists, one polite `<n> characters selected` per gesture, copied text is the redacted on-screen text, selection clears on Escape / new output / collapse |
| UX-103 mass-change confirm not copy-pasteable | androidUnitTest/.../console/ConfirmDialogsTest.kt | massChangeConfirm_should_AskDerivedCountRejectPasteAndNotRevealAnswer_When_Rendered | Robolectric | Prompt text contains no copy of the answer; paste (Ctrl+V and IME action) and drop rejected; mismatch hint `Doesn't match — count the list again`; collision case shows `Can't confirm here...` and keeps the run blocked (derivation: the N7 property Unit row) |
| UX-104 sync without preview when the remote check fails | androidUnitTest/.../ui/screens/git/FirstSyncPreviewSheetTest.kt | syncWithoutPreview_should_ConfirmUnknownAheadBehindAndRequireTypedSyncAndFocusCancel_When_RemoteTimedOut | Robolectric | Button absent while loading and when loaded; present on timeout; dialog text states ahead/behind and remote branches are unknown and that the delete guard still applies; focus on Cancel; confirm disabled until `sync` typed (` SYNC ` accepted); disabled with the running-sync line while locked; one sync on Confirm |
| UX-105 confirmation hook and re-entry under an error badge | androidUnitTest/.../ui/screens/git/FirstSyncPreviewSheetTest.kt | reviewRow_should_BeShownOnInvariantSheetAndClearedAfterBadgeSyncSuccess_When_AmberBadgeAndReviewPending | Robolectric | Pending review, amber `SyncInvariantViolated`: the invariant sheet shows `Review first sync` and it opens S14; `Retry` that succeeds clears the row and the pending key; a badge-triggered `Success` confirms without opening S14 |
| UX-106 `Change back` from the persisted previous branch | androidUnitTest/.../ui/screens/git/FirstSyncPreviewSheetTest.kt | changeBack_should_BeOfferedAfterReopenAndHiddenWhenUnknown_When_PostSyncErrorAndPreviousBranchPersistedOrAbsent | Robolectric | Repair `main -> master`, sync errors, recreate the activity and reopen S14 from the badge: `Change back to 'main'` shown; with the key absent no `Change back` and no empty button; `Copy details` and `Close` remain |
| UX-107 `Open details` from the S14 error state | androidUnitTest/.../ui/screens/git/FirstSyncPreviewSheetTest.kt | openDetails_should_RouteToMatchingSheetAndHostNoTypedConfirm_When_MassChangeBlockedConflictPendingOrRepairNeeded | Robolectric | Each of the three outcomes shows its line and `Open details`; the action opens the mass-change confirm, conflict sheet or repair sheet; no typed-count field inside S14; Back returns to S14 |
| Cross-cutting manual: TalkBack end to end | manual (Story 7.2) | talkback_should_ReadBadgeSheetAndConsoleBlocks_When_RunOnDevice | Manual | Covers UX-18/44/45/61/74/87/88/95/102 on hardware |
| Cross-cutting manual: contrast eyeball | manual (Story 7.2) | darkLightConsolePass_should_BeReadable_When_ThemeSwitched | Manual | Covers UX-47 (G15) |
| Cross-cutting manual: IME overlap | manual (Story 7.2) | extraKeysRow_should_SitAboveSoftKeyboard_When_Android | Manual | Covers UX-31, UX-82, UX-83, UX-84, UX-94, UX-99 on a 360 dp-class phone, portrait and landscape |
| Cross-cutting manual: notification permission | manual (Story 7.1b) | notification_should_PostOnceAndDegradeToChip_When_Api33PermissionGrantedThenDenied | Manual | Covers UX-89 on an Android 13+ device; Doze delivery noted as best effort |
| Cross-cutting desktop: jvmTest-UI smoke | jvmTest/.../console/ConsoleDesktopSmokeTest.kt | consoleScreen_should_RenderAndRunDiag_When_DesktopDisplayAvailable | jvmTest-UI | Run only via `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`; failures without a confirmed real display are unverified, not regressions |

## UX Criterion Coverage Index (UX-01..UX-107)

Each criterion maps to the row of the same number in the table above (107 of 107 rows present, one per criterion). Tool split: see counts in the Summary.

## Safety metric mapping (requirements Metrics 5 and 6; no new inventory IDs)

| Measure | Pass condition | Test (row above) | Inventory ID |
|---|---|---|---|
| 5a write recovery | every statement class has a valid snapshot before its first write; restore returns counts and checksum | `ConsoleWriteSafetyDrillTest`, `DbBackupTest`, `DbRestoreTest` | S9 |
| 5b DB-only rows | 3 of 3 ghost journals exported before reload/reindex/restore; refuses when export fails | `DbOnlyRowsGuardTest` | S12 |
| 5c credential strings | 0 seeded-secret matches in output, history, audit log, export, spill, CSV (hex/base64 limit listed separately) | `ConsolePatLeakageAuditTest` (incl. spill/CSV row), `ConsoleRedactorPropertyTest` | N3 |
| 5d closed-app tree safety | 0 working-tree changes, spy counts for merge/commit/push/stage/checkout/abortMerge all 0 | `ColdSyncRunnerTest` | S6 |
| 6 chip accuracy | 100% of generated inputs; never green when fetch-only | `StalenessChipStateTest`, `SyncStatusBadgeTest` | S6 |
| 6 mass-change guard | seeded deletion blocked at 6 of 6 entry points, scheduled lifts 0 | `GitSyncServiceInvariantTest`, `SyncInvariantTest` (incl. override row) | S4 |
| 6 marker scan / first sync / abort recovery | 0 marker pushes, 0 false blocks on fenced note; 0 scheduled merges before confirmation; 100% byte-identical post-abort | `SyncInvariantTest`, `FirstSyncConfirmationTest`, `AbortActiveMergeRecoveryTest` | S4, S6 |

## Test Stack
- **Unit**: `kotlin.test` + `kotest-assertions-core` + `kotest-property` (`Arb`/`checkAll` inside `runTest`), `commonTest` first; fakes: `StubGitRepository`, in-memory repositories, `FakeFileSystem`, in-memory settings store, injected clock.
- **Integration**: `jvmTest`/`businessTest` with real temp bare origins via `BareOriginFixtures` (JGit), real SQLite JDBC driver for read-only/backup/restore tests, `DatabaseWriteActor` with the real repository. Bazel `business_tests` class count must increase by exactly the number of new businessTest classes (recount from the real run).
- **E2E / UX**: Robolectric `androidUnitTest` Compose tests (headless, give signal on any machine); desktop UI smoke only through `scripts/jvm-display-check.sh`; manual owner device passes for Stories 7.1/7.2 (TalkBack, IME, contrast, Auto Backup, soak, `VACUUM INTO`).
- **Spikes kept as regression tests**: `GitSyncSpikeTest`, `MergePreflightSpikeTest`, `SqlReadOnlySpikeTest`, `VacuumIntoSpikeTest` (JVM; Android instrumented compiled by `ciCheck`, executed on device), `RawWriteCascadeSpikeTest`.
- **Failing-first rule**: `RemoteBranchResolutionTest` master-vs-main case is committed and shown red against the pre-fix `doFetch` before the fix; a mutation check (revert `RemoteTrackingRef`) re-proves it once in Task 2.3c.

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (Gradle) | `./gradlew jacocoTestReport` -> `kmp/build/reports/jacoco/` (or the project's equivalent task if absent; recorded as UNVERIFIED until run) | >=80% line on new `console/`, `git/ScheduledSyncPolicy`, `RemoteTrackingRef`, `RemoteDefaultBranch`, `JournalLazyLoader`, `JournalDiffService`, `BackgroundIndexSupervisor` |
| Bazel | `timeout 30m bazel test //kmp:business_tests`; `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY` | all green; class-count delta equals new classes |
| Android | `./gradlew :kmp:testDebugUnitTest` (Robolectric) | all UX Robolectric tests green |
| Wasm | `./gradlew :kmp:compileKotlinWasmJs -PenableJs=true` | compiles with console package |
| Lint/gates | detekt, `git diff --stat -- '*.sq' '*MigrationRunner*'` empty, `MigrationRunnerSchemaSyncTest`, `actionlint` if workflows touched | clean |

- All public service methods: happy path + error paths covered (sync service, `BranchRepairService`, `JournalDiffService`, `JournalRepairService`, `SqlConsoleWriter`, `DbBackup`/`DbRestore`, `ConsoleSession`, `ProcessRunner`).
- All external integrations (JGit remote, SQLite, process exec, WorkManager): unit with fakes plus at least one integration test.
- UX acceptance criteria: each of UX-01..UX-107 has a test row; criteria needing hardware have an automated proxy plus a Manual step.

## Gaps and Residual Risks

1. **Android-hardware-only behaviors** (TalkBack, real IME overlap, `VACUUM INTO` on requery SQLite 3.49, Auto Backup dry run, metric 1/3/4 on the real device): automated proxies exist but the pass/fail is owner wall-clock (Epic 7). Metrics are not "met" until Story 7.1/7.2 complete.
2. **`AndroidGitRepository` is not constructible without a `Context`** (Task 0.1b decision pending): Android `doFetch` parity is proven via the shared helper and a shadow-shaped JVM repo, not by exercising the Android class directly. Re-evaluate after Task 0.1b.
3. **Evidence-gated tests**: Story 2.2c-e UI tests (UX-05..UX-16 Step 4/repair variants) and Story 2.5 remedy tests are conditional on Task 0.3b selecting Branch 1 (resp. 2-5). Characterization tests for all alternative causes exist regardless; if the gate says no-go for 2.2c-e, UX-02..UX-16 collapse to UX-01/UX-73/UX-79 plus the console twin (`git set-branch`, which uses the ungated `BranchRepairService`, Task 2.2a2). UX-80 (branch-lookup loading in the repair sheet and wizard) is gated the same way; UX-81 (first-sync preview loading) and UX-92 (plain-language audit) are ungated.
4. **Redaction limit**: transformed output (`substr`, hex, base64, `sh`) can evade the redactor; the property test records hex as an expected-limit case, so "no credentials anywhere" is verified for known shapes and exact vault secrets only.
5. **Android read-only SQLite connection**: if spike 0.2a shows no engine-enforced read connection, `sql` reads are disabled there (fail closed) and the Android `sql` read/UX-38/UX-66/UX-67 tests apply on JVM only; wasm/iOS similarly.
6. **Background-path safety** is an invariant, not a race test: the closed-app runner only fetches, so the dirty-tree, late-edit and kill-mid-merge hazards of the withdrawn cold-merge design no longer exist; the spy asserts zero merge/commit/push/stage/checkout/abortMerge calls in every tree state. A live-process kill mid-merge is covered by the repository-state guard (`RepairNeeded`).
7. **`SyncMain.kt` desktop CLI and `WasmGitWriteService.kt`** commit/push paths are explicitly exempt from the conflict-marker/state/mass-change guards (plan Task 2.3e); no test guards them.
8. **Long-press snippet edit/pin (G11)** deferred: no test.
9. **Remote-deleted files stay in DB** after merge (deferred follow-up): only the reload-set deletion filter is tested.
10. **Coverage percentage commands** for Kotlin (Jacoco) are not confirmed present in this repo's Gradle/Bazel setup (UNVERIFIED); fall back to the per-class "happy + error" checklist above if absent.
11. **Counts of tests per row are design counts**, not executed results; nothing here has been run.
12. **Mass-change thresholds** (`max(20, 5%)`, 10 journals) are INFERRED defaults, now configurable (three Settings keys, one override test); the tests pin the defaults and the override mechanism, not their suitability for the owner's 1.6k-journal graph, which Task 7.1d calibrates from real history (time-boxed to one device cycle; PR-A2 proceeds on the labelled compiled-in defaults if the numbers have not arrived).
13. **Notification behavior** (permission denial, channel reuse) is Robolectric-proxied; real delivery, the Android 13+ permission dialog and Doze throttling are verified only in Story 7.1b (cross-cutting manual row).
14. **Interim evidence is not device evidence.** `InterimPhoneStateEndToEndTest` reproduces the phone's state on a temp bare origin and diffs two diagnostics exports; it proves the fix and guards regression in CI but cannot prove the owner's phone changed. M1/M4 stay "open" in the Summary until Story 7.1/7.1b record the numeric baseline-versus-target comparison on the device.
15. **Repo-side baseline figures are unverified.** The phone-versus-repo counts relayed with this repair (1,578 / 9,417 versus 1,587 / 9,430) do not reproduce: today's direct `ls-tree` count of the local clone at `3282e8010` is 1,585 journals and 9,409 pages. Only "2 recent dates missing" and "`remoteCommitsMerged` 0" are treated as established baselines; Task 0.3b measures the rest from the stamped export.
16. **Real-keyboard and real-hardware behavior** for UX-82/83/84 (key fit, IME no-extract, autocorrect) is Robolectric-proxied only; the IME overlap manual pass covers it, and the Compose hook for the no-extract/no-fullscreen flags is an UNVERIFIED implementation detail (Task 6.2a).
17. **Metric 3 threshold is owner-device wall clock.** The CI row `consoleProbes_should_ReturnEachOfFiveNamedProbesUnderTenSecondsOfFakeClock_When_RunOnFixtures` proves the logic and the TIMEOUT handling under a fake clock, not the phone's real latency; the pass/fail on `ls-remote` over the owner's network is recorded only in Story 7.2. The < 10 s bar is INFERRED from the 15 s detection bound (the console probe limit is set below it).
18. **Non-copyable mass-change confirm** defeats copy-paste from the prompt and reflexive tap-through, not a determined user who counts the list; the typed phrases of the other confirms (table name, `erase N DB-only journals`) remain echoed by design.

## Summary (counted by script from the tables above)

- Requirement-mapped tests: 223 (Unit 140 incl. property tests, Integration 82, Migration 1). Requirements covered: 26/26 (M1-M4, S1-S15, N1-N7). (Phase 4 repair: 12 cold-merge/marker/preflight rows deleted, 23 rows added. Triad repair 1: +14 rows, 197 -> 211: Unit +8, Integration +6. Triad repair 2: +6 rows, 211 -> 217: Unit +5 (two repair-concurrency, notification-tap router, `SqlWriteHint`, mass-change answer property), Integration +1 (Metric 3 five-probe threshold proxy). Triad repair 3: +3 Unit rows, 217 -> 220 (`Sync without preview` coordinator test, A1 pending-review key test, A2 scheduled-policy-reads-A1-confirmation test); the `BannerPrecedenceTest` row was reworded to 16 subsets, not added. Triad repair 4 (minor): +3 Unit rows, 220 -> 223 (confirmation hook across manual-sync entry points, persisted previous branch, `Review first sync` row under an error badge). All recounted from the table by script, `python3 -I counts.py` over the "Requirement -> Test Mapping" table.)
- UX acceptance tests: 107 criterion rows (Robolectric 75, commonTest-level 32) plus 5 cross-cutting (4 Manual device passes, 1 jvmTest-UI desktop smoke). UX criteria covered: 107/107 (UX-105..UX-107 added in Triad repair 4; UX-104 added in Triad repair 3; UX-75..UX-79 added in Phase 4, UX-80..UX-92 in Triad repair 1, UX-93..UX-103 in Triad repair 2; UX-02, UX-08, UX-27, UX-75, UX-77, UX-81 and UX-86 rows reworded, not added).
- Plan task count behind this validation: 100 tasks (`grep -c '^##### Task'`; Triad repair 3 added Task 2.2d0 by splitting Task 2.4h, the review sheet and consent flag now being PR-A1 and the scheduled enforcement PR-A2), 36 stories (`grep -c '^#### Story'`, unchanged).
- Metrics 5 and 6 (console and sync safety, safety-scope outcomes) are covered through existing IDs; see "Safety metric mapping". M1 and M4 remain device-only: interim CI proxies exist (Task 7.1c) but the metrics are open until Story 7.1/7.1b.
- Migration test: `migration_should_be_reversible` (data-only config repair; no schema migration exists).
