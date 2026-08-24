# Validation Plan: web-local-folder-livesync

**Date**: 2026-07-17

## Happy Path Scenario
Given a web user who has already picked a local host directory for a graph (Baseline: files
imported once into OPFS via `pickDirectoryAsync()`, handle discarded, no write-through), when the
user retains live sync (handle persisted across reload) and edits a page in the browser, then the
corresponding `.md` file in the host directory is updated on disk within roughly the existing
~500ms autosave latency budget, with no user action beyond the original one-time directory pick.

---

## Requirement → Test Mapping

Requirement IDs reference `requirements.md`'s Scope/Success Metrics bullets and `plan.md`'s Epics.
Domain Glossary terms (`HostDirectorySync`, `HostAccessState`, `ReconciliationOutcome`,
`HostWritePayload`, `HostDirectorySync.CacheAccess`, etc.) are used verbatim per plan.md.

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Retain `FileSystemDirectoryHandle` across session (req. Scope #1; Epic 2.1) | `HostDirectorySyncHandleRetentionTest.kt` | `attachFreshHandle_should_SetHostDirHandleAndOpfsPath_When_PickDirectoryAsyncSucceeds` | Unit | Happy path — pick resolves, `hostDirHandle`/`hostGraphOpfsPath` set |
| Retain handle — error path (Epic 2.1) | `HostDirectorySyncHandleRetentionTest.kt` | `attachFreshHandle_should_LeaveHostDirHandleNull_When_PersistHostHandleThrows` | Unit | IndexedDB `put` throws — pick itself must not fail, handle stays attached in-memory but persistence failure is logged, not propagated |
| Handle persisted to IndexedDB (Epic 2.1) | `HostDirectorySyncHandleRetentionTest.kt` | `persistHostHandle_should_StoreHostHandleEnvelopeKeyedByGraphId_When_AttachFreshHandleCompletes` | Integration | Real IndexedDB (wasmJs browser target) — `idbGetHandle` round-trips a `HostHandleEnvelope` |
| `HostAccessState` exhaustiveness (Epic 1.3) | `HostAccessStateTest.kt` | `hostAccessState_should_ExposeExactlyFiveVariants_When_ExhaustiveWhenIsCompiled` | Unit | Compile-time exhaustiveness guard (a `when` without `else` over all 5 variants) |
| `FileSystem.hostDirectoryAccessState` default (Story 1.3.2) | `FileSystemDefaultsTest.kt` (commonTest) | `hostDirectoryAccessState_should_ReturnNotApplicable_When_NoOverrideExists` | Unit | JVM `FileSystem` impl, no wasmJs override — zero I/O |
| `classifyReconciliation` four-way table (Epic 1.4) | `HostReconciliationTest.kt` | `classifyReconciliation_should_ReturnIdentical_When_HostAndCacheContentMatch` | Unit | Happy path — byte-identical strings |
| `classifyReconciliation` — error/edge path (Epic 1.4) | `HostReconciliationTest.kt` | `classifyReconciliation_should_ReturnIdentical_When_BothSidesAreEmptyStringNotNull` | Unit | Off-by-one guard: empty string vs. absent file |
| `classifyReconciliation` — conflict (Epic 1.4) | `HostReconciliationTest.kt` | `classifyReconciliation_should_ReturnHostChangedConflict_When_BothSidesNonNullAndDiffer` | Unit | Happy path variant |
| `classifyReconciliation` — host-only (Epic 1.4) | `HostReconciliationTest.kt` | `classifyReconciliation_should_ReturnHostOnlyNew_When_CacheContentIsNull` | Unit | — |
| `classifyReconciliation` — browser-only (Epic 1.4) | `HostReconciliationTest.kt` | `classifyReconciliation_should_ReturnBrowserOnlyNeedsPush_When_HostContentIsNull` | Unit | — |
| `classifyReconciliationBytes` paranoid-mode (Story 1.4.1e, Blocker 4) | `HostReconciliationTest.kt` | `classifyReconciliationBytes_should_UseContentEqualsNotReferenceEquality_When_ByteArraysAreEqualButDifferentInstances` | Unit | Happy path — guards against reference-equality bug |
| `classifyReconciliationBytes` — error path | `HostReconciliationTest.kt` | `classifyReconciliationBytes_should_ReturnHostChangedConflict_When_ByteArraysDifferAndBothNonNull` | Unit | Ensures bytes path never decodes as UTF-8 |
| `HostWritePayload` exhaustive dispatch (Task 1.4.1d) | `HostWritePayloadTest.kt` | `hostWritePayload_should_ExposeExactlyThreeVariants_When_FlushHostWriteDispatchesExhaustively` | Unit | Compile-time guard |
| IndexedDB open/put/get interop (Story 1.5.1) | `HostDirectoryInteropTest.kt` | `idbOpenHandleDb_should_CreateDatabaseAndObjectStore_When_NoStelekitHostHandlesDbExists` | Unit (mocked) | Happy path against a fresh profile |
| IndexedDB interop — error path | `HostDirectoryInteropTest.kt` | `idbGetHandle_should_ReturnNull_When_KeyNotFound` | Unit | Read path degrades gracefully |
| IndexedDB interop — integration | `PlatformFileSystemDirtyTrackingIntegrationTest.kt`-style new file `HostDirectoryInteropIndexedDbLiveTest.kt` | `idbPutHandle_then_idbGetHandle_should_RoundTripHostHandleEnvelope_When_RunAgainstRealBrowserIndexedDb` | Integration | Real IndexedDB, wasmJs browser test target |
| Permission query/request interop (Story 1.5.3) | `HostDirectoryInteropTest.kt` | `queryHandlePermission_should_ReturnPrompt_When_HandleFreshlyRehydratedFromIndexedDb` | Unit (mocked) | Happy path per `research/pitfalls.md` §1.1 |
| Permission interop — error path | `HostDirectoryInteropTest.kt` | `requestHandlePermission_should_ReturnDenied_When_UnderlyingCallThrows` | Unit | Fail-closed, not open |
| `FileSystemObserver` construction (Story 1.5.4) | `HostDirectoryInteropTest.kt` | `fileSystemObserverSupported_should_ReturnTrue_When_RunningOnChrome133OrNewer` | Unit (mocked) | Feature-detect happy path |
| `FileSystemObserver` — unsupported browser | `HostDirectoryInteropTest.kt` | `fileSystemObserverSupported_should_ReturnFalse_When_ConstructorNotPresentOnSelf` | Unit | Error/fallback path |
| `navigator.storage.persist()` interop (Story 1.5.6) | `HostDirectoryInteropTest.kt` | `requestStoragePersistence_should_ReturnGrantResult_When_StorageApiSupported` | Unit (mocked) | Happy path |
| `navigator.storage.persist()` — error path | `HostDirectoryInteropTest.kt` | `requestStoragePersistence_should_ReturnFalse_When_NavigatorStoragePersistNotAFunction` | Unit | Never throws on unsupported browsers |
| `HostDirectorySync` SRP extraction (Epic 1.6) | `HostDirectorySyncConstructionTest.kt` | `hostDirectorySync_should_ConstructAndOperateStandalone_When_GivenOnlyAFakeCacheAccessAndNoPlatformFileSystem` | Unit | Regression guard for Blocker 1 (independence) |
| `FolderSyncLockNaming` determinism (Story 1.2.1) | `FolderSyncLockNamingTest.kt` | `pollLockNameFor_should_ReturnIdenticalStringOnRepeatedCalls_When_GivenSameGraphId` | Unit | Happy path |
| `FolderSyncLockNaming` distinctness | `FolderSyncLockNamingTest.kt` | `writeLockNameFor_should_ReturnDistinctNames_When_GivenDifferentRepoRelativePaths` | Unit | Error/collision-avoidance path |
| `FolderSyncLockNaming` — no collision with `GitWriteLockNaming` | `FolderSyncLockNamingTest.kt` | `pollLockNameFor_and_writeLockNameFor_should_NeverSharePrefixWithGitWriteLockNaming_When_ComparedForAnyGraphId` | Unit | Cross-feature isolation guard |
| `WebLock.withLock` basic semantics (Story 1.1.1) | `WebLockTest.kt` | `withLock_should_NotBlockEachOther_When_TwoCallsUseDistinctLockNames` | Integration | Real browser Web Locks API |
| `WebLock.withLock` — contention path | `WebLockTest.kt` | `withLock_should_SerializeExecution_When_TwoCallsUseTheSameLockNameConcurrently` | Integration | Real browser Web Locks API |
| `reconnectHostDirectory` silent resume, always reconciling (Story 2.2.1, Blocker 3) | `HostDirectorySyncReconciliationTest.kt` | `reconnectHostDirectory_should_RunHostReconciliationAndSetGranted_When_HandleFoundAndPermissionGranted` | Integration | Happy path — session resume with divergence, no UI block |
| `reconnectHostDirectory` — no handle found | `HostDirectorySyncSessionResumeTest.kt` | `reconnectHostDirectory_should_ResolveNotApplicable_When_NoHandlePersistedInIndexedDb` | Unit | Error/absence path |
| `reconnectHostDirectory` — prompt/denied branches | `HostDirectorySyncSessionResumeTest.kt` | `reconnectHostDirectory_should_ResolvePromptNeeded_When_QueryHandlePermissionReturnsPrompt` | Unit | Error path — no reconciliation call, no handle set |
| `requestHostDirectoryAccess` one-click resume (Story 2.2.2) | `HostDirectorySyncSessionResumeTest.kt` | `requestHostDirectoryAccess_should_SetGrantedAndStartSyncLoops_When_UserAllowsNativePrompt` | Unit | Happy path |
| `requestHostDirectoryAccess` — decline path | `HostDirectorySyncSessionResumeTest.kt` | `requestHostDirectoryAccess_should_SetDeniedWithoutRetryLoop_When_UserDeclinesNativePrompt` | Unit | Error path — no auto-retry |
| `hostDirectoryAccessState` FileSystem override (Task 2.2.2b) | `PlatformFileSystemHostSyncDelegationTest.kt` | `hostDirectoryAccessState_should_DelegateToHostDirectorySyncFlowValue_When_Called` | Unit | Happy path — one-line delegate |
| `FolderSyncStatusBadge` renders `HostAccessState` (Story 2.3.1) | `FolderSyncStatusBadgeTest.kt` | `folderSyncStatusBadge_should_RenderReconnectFolderText_When_StateIsPromptNeeded` | Unit (Compose UI test) | Happy path |
| `FolderSyncStatusBadge` — `NotApplicable` renders nothing | `FolderSyncStatusBadgeTest.kt` | `folderSyncStatusBadge_should_RenderNothing_When_StateIsNotApplicable` | Unit | Error/no-broken-affordance path |
| `FolderSyncStatusBadge` — `Disconnected` vs `PromptNeeded` distinct copy | `FolderSyncStatusBadgeTest.kt` | `folderSyncStatusBadge_should_RenderDistinctTextFromPromptNeeded_When_StateIsDisconnected` | Unit | Reconnect-vs-conflict distinction guard |
| `storage.persist()` on connect (Story 2.4.1) | `HostDirectorySyncSessionResumeTest.kt` | `connectHostDirectory_should_CallRequestStoragePersistenceExactlyOnce_When_ConnectSucceeds` | Unit | Happy path — fire-and-forget, logged not blocking |
| `storage.persist()` — never blocks connect flow | `HostDirectorySyncSessionResumeTest.kt` | `connectHostDirectory_should_ResolveGrantedWithoutWaitingOnStoragePersist_When_StoragePersistIsSlowOrDenied` | Unit | Error/degraded path — persist failure never surfaces to user |
| `connectHostDirectory` reconciles, never imports (Story 3.1.1, Critical Finding) | `HostDirectorySyncReconciliationTest.kt` | `connectHostDirectory_should_PreserveBrowserOnlyEditInCache_When_EnablingLiveSyncOnAlreadyPopulatedGraph` | Integration | Happy path — the Critical Finding's core regression guard |
| `connectHostDirectory` — error path | `HostDirectorySyncReconciliationTest.kt` | `connectHostDirectory_should_LeaveHostDirHandleNullAndStateNotApplicable_When_ShowDirectoryPickerOrReconciliationFails` | Integration | Reconciliation failure mid-walk — no partial state treated as complete |
| `runHostReconciliation` four-way classification (Story 3.2.1) | `HostDirectorySyncReconciliationTest.kt` | `runHostReconciliation_should_ProduceIdenticalConflictHostOnlyAndBrowserOnlyOutcomes_When_WalkingAFourPathMixedDirectory` | Integration | Happy path — combined scenario, see also Migration test below |
| `runHostReconciliation` — `.md.stek` bytes path (Blocker 4) | `HostDirectorySyncReconciliationTest.kt` | `runHostReconciliation_should_UseClassifyReconciliationBytes_When_PathEndsWithMdStekSuffix` | Integration | Error-avoidance path — never decodes ciphertext as UTF-8 |
| `runHostReconciliation` browser-only-path coverage (Task 3.2.1b) | `HostDirectorySyncReconciliationTest.kt` | `runHostReconciliation_should_ClassifyPathsNotVisitedByHostWalkAsBrowserOnlyNeedsPush_When_CacheHasPathsAbsentFromHost` | Integration | — |
| `HostChangedConflict` dispatch (Task 3.2.2a) | `HostDirectorySyncReconciliationTest.kt` | `runHostReconciliation_should_InvokeOnHostConflictExactlyOnce_When_PathClassifiesAsHostChangedConflict` | Integration | — |
| `HostOnlyNew` dispatch, bytes-aware (Task 3.2.2b) | `HostDirectorySyncReconciliationTest.kt` | `runHostReconciliation_should_ImportViaSetBytesAndWriteOpfsMirrorBytes_When_HostOnlyNewPathIsMdStekSuffixed` | Integration | Happy path — paranoid mode import |
| `BrowserOnlyNeedsPush` dispatch (Task 3.2.2c) | `HostDirectorySyncReconciliationTest.kt` | `runHostReconciliation_should_EnqueueHostWritePendingEntry_When_PathClassifiesAsBrowserOnlyNeedsPush` | Integration | — |
| Fresh-empty-graph regression (Task 3.3.1d) | `HostDirectorySyncReconciliationTest.kt` | `pickDirectoryAsync_should_ProduceByteForByteIdenticalCacheToPreProjectBehavior_When_GraphIsFreshAndEmpty` | Integration | Regression guard — old path untouched |
| `reconnectHostDirectory`/`connectHostDirectory` parity (Blocker 3, Task 3.3.1f) | `HostDirectorySyncReconciliationTest.kt` | `reconnectHostDirectory_should_InvokeOnHostConflictIdenticallyToConnectHostDirectory_When_SilentResumeEncountersDivergence` | Integration | Happy path — proves both entry points share data-loss protection |
| `hostWritePending` crash recovery — resolved half (Blocker 2, Task 3.3.1g) | `HostDirectorySyncReconciliationTest.kt` | `reconnectHostDirectory_should_ReenqueueHostWritePending_When_CacheHoldsBrowserOnlyEditButInMemoryQueueWasLostToCrash` | Integration | Recovery path — durable edit is rediscovered |
| `hostWritePending` crash recovery — fix verification (Blocker 2, closed by Epic 1.7 scope expansion, Task 3.3.1g) | `HostDirectorySyncReconciliationTest.kt` | `scheduleHostWriteThrough_should_EnqueuePathOnceDelayedOpfsWriteResolves_When_WriteFileWasCalledWithASlowOpfsWriteFileDouble` | Integration | **Asserts correct, safe behavior — no longer a known-limitation-documentation test.** See "Migration & Known-Limitation Coverage" below. |
| `hostWritePending` crash recovery — await-mechanism regression test (second test kept per this fix pass, Task 3.3.1g) | `HostDirectorySyncReconciliationTest.kt` | `scheduleHostWriteThrough_should_NotContainPathUntilOpfsWriteDeferredResolves_When_GivenTheSameSlowOpfsWriteFileDouble` | Integration | Mechanism-level regression guard — proves the await itself, distinct from the outcome-level test above |
| `scheduleHostWriteThrough` coalescing (Story 4.1.1) | `HostDirectorySyncWriteThroughTest.kt` | `scheduleHostWriteThrough_should_CollapseToOneWriteOfLatestContent_When_CalledTwiceForSamePathBeforeFirstFlushCompletes` | Unit | Happy path |
| `scheduleHostWriteThrough` — single write | `HostDirectorySyncWriteThroughTest.kt` | `scheduleHostWriteThrough_should_FlushExactlyOnce_When_CalledOnceForAPathWithHostDirHandleSet` | Unit | — |
| `flushHostWrite` freshness check (Story 4.2.1) | `HostDirectorySyncWriteThroughTest.kt` | `flushHostWrite_should_RouteThroughOnHostConflictInsteadOfOverwriting_When_HostHashMismatchesLastKnownHash` | Unit | Error path — conflict, not silent overwrite |
| `flushHostWrite` — happy path | `HostDirectorySyncWriteThroughTest.kt` | `flushHostWrite_should_WritePendingContentAndDequeue_When_HostHashMatchesLastKnownHash` | Unit | Happy path |
| `flushHostWrite` paranoid-mode bytes (Story 4.2.2) | `HostDirectorySyncWriteThroughTest.kt` | `flushHostWrite_should_SkipHashGuardAndUseWritableWriteBuffer_When_PayloadIsBytesForMdStekPath` | Unit | Happy path variant |
| `writeFile`/`writeFileBytes`/`deleteFile` wire write-through (Story 4.3.1) | `PlatformFileSystemHostSyncDelegationTest.kt` | `writeFile_should_ProduceFourIndependentEffects_When_HostDirHandleIsSet` | Integration | Happy path — cache, dirtySet, OPFS mirror, hostWritePending |
| `writeFile` — no-handle regression (Story 4.3.1) | `PlatformFileSystemHostSyncDelegationTest.kt` | `writeFile_should_LeaveHostWritePendingUntouched_When_HostDirHandleIsNull` | Unit | Regression guard |
| `deleteFile` host-side removal | `PlatformFileSystemHostSyncDelegationTest.kt` | `deleteFile_should_RemoveHostEntry_When_HostDirHandleIsSetAndFileExistsOnHost` | Integration | Happy path |
| `applyRemoteContent` never write-throughs (Task 4.3.1d) | `PlatformFileSystemHostSyncDelegationTest.kt` | `applyRemoteContent_should_NeverCallScheduleHostWriteThrough_When_MergingRemoteGitContent` | Unit | Guard against unintended host writes |
| Write failure surfacing (Story 4.4.1) | `HostDirectorySyncWriteThroughTest.kt` | `flushHostWrite_should_KeepPathQueuedAndSetDisconnected_When_ThrowsNotFoundError` | Unit | Error path — happy-path counterpart is `flushHostWrite_should_WritePendingContentAndDequeue_...` above |
| Write failure — permission-loss reclassification (adversarial Concern) | `HostDirectorySyncWriteThroughTest.kt` | `flushHostWrite_should_TransitionToPromptNeededOrDenied_When_ThrowsNotAllowedErrorAndPermissionRequeryConfirmsLoss` | Unit | Error path |
| `pollHostDirectoryOnce` walk + pre-filter (Story 5.1.1) | `HostDirectorySyncExternalChangeTest.kt` | `pollHostDirectoryOnce_should_UpdateHostModTimesAndCache_When_FileLastModifiedAndContentDiffer` | Unit | Happy path |
| `pollHostDirectoryOnce` — pre-filter short-circuit | `HostDirectorySyncExternalChangeTest.kt` | `pollHostDirectoryOnce_should_SkipContentRead_When_FileLastModifiedAndSizeAreUnchanged` | Unit | Error-avoidance / cost-control path |
| `pollHostDirectoryOnce` `.md.stek` branch (Blocker 4) | `HostDirectorySyncExternalChangeTest.kt` | `pollHostDirectoryOnce_should_ReadArrayBufferAndUseSetBytes_When_ChangedPathIsMdStekSuffixed` | Unit | — |
| `pollHostDirectoryOnce` own-write suppression (Task 5.1.1c) | `HostDirectorySyncExternalChangeTest.kt` | `pollHostDirectoryOnce_should_SkipPath_When_PathIsCurrentlyInHostWriteInFlight` | Unit | — |
| `HostDirectoryPoller` timer loop (Story 5.1.2) | `HostDirectorySyncExternalChangeTest.kt` | `hostDirectoryPoller_should_CallPollHostDirectoryOnceAtLeastOnce_When_TenSecondsElapseWithHostDirHandleSet` | Integration | Happy path — timer-driven |
| `getLastModifiedTime`/`listFilesWithModTimes` delegation (Story 5.2.1) | `HostDirectorySyncExternalChangeTest.kt` | `getLastModifiedTime_should_ReturnHostModTimesValue_When_HostDirHandleIsSetForPath` | Unit | Happy path |
| `getLastModifiedTime` — regression path | `HostDirectorySyncExternalChangeTest.kt` | `getLastModifiedTime_should_ReturnNull_When_HostDirHandleIsNull` | Unit | Regression guard |
| End-to-end mtime-bump to `ExternalFileChange` (Story 5.4.1) | `HostDirectorySyncExternalChangeTest.kt` | `fileRegistryDetectChanges_should_EmitExternalFileChange_When_HostDirectorySyncPollUpdatesHostModTimes` | Integration | Happy path — full pipeline through `GraphFileWatcher` |
| Own-write suppression end-to-end | `HostDirectorySyncExternalChangeTest.kt` | `fileRegistryDetectChanges_should_NotEmitExternalFileChange_When_PollObservesTheAppsOwnJustWrittenContent` | Integration | Error-avoidance path |
| `HostChangeObserver` fast path (Story 5.2.2) | `HostDirectorySyncExternalChangeTest.kt` | `handleObserverRecords_should_TriggerImmediatePollHostDirectoryOnce_When_ModifiedChangeRecordReceived` | Unit | Happy path |
| `HostChangeObserver` — errored record fallback | `HostDirectorySyncExternalChangeTest.kt` | `handleObserverRecords_should_TriggerFullTreePollAndContinueOperating_When_ErroredRecordReceived` | Unit | Error path |
| Visibility-triggered recheck (Story 5.3.1) | `HostDirectorySyncExternalChangeTest.kt` | `visibilityVisibleLoop_should_TriggerPollHostDirectoryOnceImmediately_When_TabRegainsFocusAfterBeingBackgrounded` | Integration | Happy path |
| Large-graph poller-cost benchmark, steady-state (Story 5.5.1, Blocker 6) | `HostDirectoryPollerBenchmarkTest.kt` | `pollHostDirectoryOnce_should_CompleteWithinDocumentedBoundAndPerformZeroContentReads_When_8030FilesAreUnchanged` | Integration (benchmark) | Happy path — steady-state gate |
| Large-graph poller-cost benchmark, burst-change | `HostDirectoryPollerBenchmarkTest.kt` | `pollHostDirectoryOnce_should_PerformExactly100ContentReads_When_100Of8030FilesHaveChangedLastModified` | Integration (benchmark) | Worst-case gate |
| Per-write lock (Story 6.1.1) | `HostDirectorySyncCrossTabTest.kt` | `flushHostWrite_should_SerializeAcrossTwoHostDirectorySyncInstances_When_BothScheduleWriteThroughForSamePathConcurrently` | Integration | Happy path — real Web Locks |
| `WebLock.tryWithLock` non-blocking (Story 6.2.1) | `WebLockTest.kt` | `tryWithLock_should_ReturnNull_When_AnotherWithLockCallAlreadyHoldsSameLockName` | Integration | Error/contention path |
| `tryWithLock` — happy path | `WebLockTest.kt` | `tryWithLock_should_ReturnBlockResult_When_LockIsFree` | Integration | — |
| Per-poll-tick skip (Story 6.2.1) | `HostDirectorySyncCrossTabTest.kt` | `pollHostDirectoryOnce_should_HaveExactlyOneWinningTabPerTick_When_TwoTabsAreBothDueForAPollAtTheSameInstant` | Integration | Happy path |
| `renameFile` write-new-verify-delete-old (Story 7.1.1) | `HostDirectorySyncRenameTest.kt` | `renameHostFile_should_WriteNewContentThenDeleteOldPath_When_VerificationOfNewFileSucceeds` | Unit | Happy path |
| `renameFile` — verify-before-delete failure path | `HostDirectorySyncRenameTest.kt` | `renameHostFile_should_LeaveOldPathInPlace_When_NewFileVerificationFailsAfterWrite` | Unit | Error path — no data loss on failed verify |
| Interrupted-rename non-destructive duplicate (Story 7.1.2, Blocker 5) | `HostDirectorySyncRenameTest.kt` | `runHostReconciliation_should_ImportBothPathsAndLogOnly_When_HostOnlyNewPathContentHashMatchesAnotherCachePath` | Integration | Happy path — proves no destructive auto-delete |
| Interrupted-rename — coincidental match, unrelated pages | `HostDirectorySyncRenameTest.kt` | `runHostReconciliation_should_NeverDeleteEitherPath_When_TwoUnrelatedHostOnlyNewPagesShareIdenticalContent` | Integration | Error-avoidance path — false positive has zero destructive effect |
| `FolderSyncStatusBadge` precedence dispatch (Story 8.1.1) | `FolderSyncStatusBadgeTest.kt` | `folderSyncStatusBadge_should_ShowPendingWriteCount_When_StateIsGrantedAndPendingCountIsNonZero` | Unit | Happy path |
| `FolderSyncStatusBadge` — idle state | `FolderSyncStatusBadgeTest.kt` | `folderSyncStatusBadge_should_ShowSyncedToDirName_When_StateIsGrantedAndPendingCountIsZero` | Unit | — |
| Unsupported-browser fallback is fully inert (Story 8.2.1) | `HostDirectorySyncFallbackRegressionTest.kt` | `reconnectHostDirectory_should_ResolveNotApplicableWithoutQueryingPermission_When_ShowDirectoryPickerUnsupported` | Integration | Happy path — regression guard |
| `web-git-writeback` dirtySet independence (Story 8.2.2) | `PlatformFileSystemDirtySetIndependenceTest.kt` | `writeFile_should_ProduceByteIdenticalDirtySetAndMarkerFile_When_ComparedWithAndWithoutHostDirHandleSet` | Integration | Regression guard — cross-feature isolation |

---

## UX Acceptance Tests

Per `design/ux.md` §15 (UX Acceptance Criteria 1–24). Tool column: **Playwright** = automatable in
`e2e/tests/*.spec.ts` following the model of `e2e/tests/benchmark.spec.ts`/`demo.spec.ts`.
**Manual** = requires a real File System Access API user-gesture/permission dialog, which Playwright
cannot script (per Chromium's transient-activation + native-OS-dialog requirement — these dialogs
are outside the DOM and outside CDP's normal control surface).

| # | UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|---|
| AC1 | First-time pick, 1 click (Surface 1) | — | *Directory pick 1-click flow* | Manual | Click "Select Graph Directory" → native OS picker → browser permission prompt → Allow. Confirm graph loads with zero additional clicks. |
| AC2 | Silent resume, 0 clicks (Surface 4) | `e2e/tests/folder-sync-resume.spec.ts` | `silentResume: badge shows Synced with zero user interaction when grant still active` | Playwright | Seed IndexedDB with a handle + stub `queryPermission` to resolve `"granted"` via `page.addInitScript`; reload; assert badge text `"Synced to"` appears with no click. (Handle/permission mocking is scriptable even though the *initial* grant is not — see Manual note below.) |
| AC3 | One-click resume (Surface 4) | — | *Reconnect folder, exactly 1 click* | Manual | With a stale/`"prompt"` grant, click "Reconnect folder" → native Allow → badge updates. Count clicks = 1 (excluding the native dialog's own Allow click, per the AC's own wording "1 click" = the in-app click). |
| AC4 | Enable-on-existing-graph, ≤3 clicks (Surface 8) | — | *Enable live folder sync end-to-end* | Manual | Settings → "Enable live folder sync" → native picker/Allow → reconciliation summary → "Done". Confirm reassurance copy visible before native picker fires. |
| AC5 | Error-state recovery, ≤2 clicks | — | *Recovery from Denied/Disconnected/reconciliation-failure/write-failure* | Manual | From each error state, count clicks to working state (1 in-app click + ≤1 native prompt confirm). |
| AC6 | "Folder access declined — Grant access" exact text (Surface 6) | `e2e/tests/folder-sync-badge-states.spec.ts` | `deniedState: badge shows exact text 'Folder access declined — Grant access' and click retries requestHostDirectoryAccess` | Playwright | Drive `hostAccessStateFlow` to `Denied` via a test-only JS hook (see Playwright harness note below) or by stubbing `queryPermission`/`requestPermission` to resolve `"denied"`; assert exact badge text; click; assert `requestHostDirectoryAccess` was invoked (via a page-exposed call counter). |
| AC7 | "Folder not found — Reconnect" exact text (Surface 7) | `e2e/tests/folder-sync-badge-states.spec.ts` | `disconnectedState: badge shows exact text 'Folder not found — Reconnect' and click re-runs directory picker not requestPermission` | Playwright | Stub a host write to throw `NotFoundError`-shaped failure; assert exact badge text; assert click path calls `showDirectoryPicker`-shaped entry point, not `requestPermission`. |
| AC8 | Reconciliation failure copy (Surface 9) | `e2e/tests/folder-sync-reconciliation.spec.ts` | `reconciliationFailure: summary shows exact text and Nothing was changed reassurance with Try again` | Playwright | Stub `runHostReconciliation` to throw mid-walk; assert both exact strings render; assert `hostDirHandle` stays unset (state stays `NotApplicable`) via a debug flow snapshot. |
| AC9 | Host write failure banner names the page (Surface 12) | `e2e/tests/folder-sync-write-failure.spec.ts` | `writeFailureBanner: dismissable banner names the failed page and offers retry` | Playwright | Stub `flushHostWrite` to throw; assert banner text includes the page name; assert dismiss + retry affordances present. |
| AC10 | Content conflict always routes through `DiskConflictDialog` | `e2e/tests/folder-sync-reconciliation.spec.ts` | `hostChangedConflict: navigating to a conflicted page opens DiskConflictDialog with four-choice structure` | Playwright | Seed a `HostChangedConflict` outcome; navigate to that page; assert `DiskConflictDialog` renders with all four choices + escape hatch. |
| AC11 | No dead ends, ACs 6–10 | (covered by AC6–AC10 tests above — each asserts an exit affordance is present) | — | Playwright | Folded into AC6–AC10; no separate test file. |
| AC12 | Decline does not retry-loop (Surface 6) | `e2e/tests/folder-sync-badge-states.spec.ts` | `declinePrompt: state stays Denied after decline with no automatic repeated native prompt` | Playwright | Stub `requestPermission` to resolve `"denied"` once; assert no second automatic call occurs within a bounded wait window; assert badge affordance persists. |
| AC13 | `NotApplicable` renders zero new UI (Story 8.2.1) | `e2e/tests/folder-sync-fallback.spec.ts` | `unsupportedBrowser: zero new folder-sync UI renders anywhere when showDirectoryPicker unsupported` | Playwright | Stub `showDirectoryPickerSupported()` to `false` via init script; assert no `FolderSyncStatusBadge`/`FolderSyncSettings` DOM nodes exist anywhere in the app. |
| AC14 | Cancel native picker leaves no partial state (Surfaces 1/6/7/8) | — | *Cancel picker at each entry point* | Manual | Cancel the native OS picker at each of the 4 entry points; confirm no half-connected badge state, no orphaned IndexedDB entry (verifiable via devtools Application tab, not automatable). |
| AC15 | Persistent idle "Synced to `<dirName>`" (Surface 3) | `e2e/tests/folder-sync-badge-states.spec.ts` | `idleConnected: badge shows persistent Synced to dirName text at rest, never blank while connected` | Playwright | With `Granted` + 0 pending, assert badge visible immediately and remains visible across a wait period (no flicker to blank). |
| AC16 | Pending count updates within one flush cycle (Surface 3) | `e2e/tests/folder-sync-badge-states.spec.ts` | `pendingCount: badge count updates within one flush cycle of rapid edits` | Playwright | Trigger several rapid `writeFile` calls; assert badge's numeric count reflects the queue size within a bounded poll window. |
| AC17 | Reconciliation summary names all 4 categories when non-zero (Surface 9) | `e2e/tests/folder-sync-reconciliation.spec.ts` | `reconciliationSummary: all four non-zero outcome categories are named explicitly including BrowserOnlyNeedsPush` | Playwright | Seed a mixed-state fixture producing all 4 outcomes; assert summary text names each count, especially `BrowserOnlyNeedsPush`'s count is never folded into another line. |
| AC18 | Reconnect affordance Tab-reachable + Enter/Space (a11y) | `e2e/tests/folder-sync-a11y.spec.ts` | `keyboardAccess: reconnect affordance is Tab-reachable and Enter-activatable` | Playwright | `page.keyboard.press('Tab')` repeatedly; assert focus lands on the reconnect button; press Enter; assert `requestHostDirectoryAccess` invoked. |
| AC19 | `aria-live="polite"` on status text (a11y) | `e2e/tests/folder-sync-a11y.spec.ts` | `liveRegion: badge status text uses aria-live polite not assertive` | Playwright | Query DOM for the badge's live-region attribute; assert value is `"polite"`. |
| AC20 | Focus returns to badge after native prompt resolves (a11y) | — | *Focus restoration after native permission prompt* | Manual | Native permission dialogs steal OS-level focus outside CDP's reliable control — focus-return timing after a real dialog closes cannot be deterministically scripted. |
| AC21 | Settings/reconciliation buttons are real `Button` composables (a11y) | `e2e/tests/folder-sync-a11y.spec.ts` | `realButtons: Enable live folder sync and reconciliation summary buttons are Tab/Enter operable` | Playwright | Same Tab/Enter pattern as AC18, applied to `FolderSyncSettings`'s and the reconciliation summary's buttons. |
| AC22 | No color-only signaling (a11y) | `e2e/tests/folder-sync-a11y.spec.ts` | `textNotColorOnly: each warning-tinted badge state pairs its tint with distinct wording` | Playwright | Snapshot each state's rendered text; assert distinct non-empty strings per state (automatable text-presence check; true color-blind simulation is Manual). |
| AC22b | Color-blind simulation cross-check | — | *Color-blind mode visual spot-check* | Manual | Chrome DevTools "Emulate vision deficiencies"; confirm all states remain distinguishable by text alone. |
| AC23 | 4.5:1 contrast, light + dark theme | — | *Contrast audit against Material3 tokens* | Manual | Run an axe-core/Lighthouse contrast check (or manual token inspection) against `errorContainer`/`onErrorContainer` in both themes — flagged Manual because it needs a real rendered-color audit tool run, not scripted DOM assertions alone; promote to Playwright + `@axe-core/playwright` if that dependency is added to `e2e/package.json`. |
| AC24 | "Connecting to folder…" announced on entry + completion (a11y) | `e2e/tests/folder-sync-a11y.spec.ts` | `reconciliationProgressAnnouncement: progress state and completion summary are both announced via live region` | Playwright | Assert both the progress spinner's live-region text and the summary's live-region text are present and distinct in sequence. |

**Playwright harness note**: Since real File System Access API grants require user-gesture-gated
native OS dialogs that Playwright/CDP cannot reliably script, all Playwright specs above drive
`HostDirectorySync`'s state through **test-only stubs** injected via `page.addInitScript` /
`page.exposeFunction` (mirroring `e2e/tests/benchmark.spec.ts`'s existing pattern of controlling app
internals from the test harness) — e.g. stubbing `queryHandlePermission`/`requestHandlePermission`/
`showDirectoryPicker` return values, or driving `hostAccessStateFlow`/`hostWritePendingCountFlow`
directly if a test-only debug hook is exposed. This is consistent with how `stelekit-web-opfs`'s
`e2e/tests/*.spec.ts` already operate without a real user present. Genuine first-grant and
focus-after-native-dialog behavior (AC1, AC3 (native-Allow leg), AC4 (native-picker leg), AC14,
AC20, AC23) remain Manual because they exercise the actual OS-level dialog outside any scriptable
surface.

---

## Migration & Known-Limitation Coverage

### Migration test — 4-way reconciliation classification, realistic mixed-state fixture

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Migration Plan (plan.md, upgrade-boundary reconciliation; Critical Finding) | `HostDirectorySyncMigrationReconciliationTest.kt` | `runHostReconciliation_should_ClassifyAllFourOutcomesCorrectly_When_ReconcilingARealisticMixedStateGraphAtTheUpgradeBoundary` | Migration | See below |

**Scenario**: Simulates the exact upgrade-boundary scenario the Migration Plan section describes —
a non-empty OPFS graph populated under pre-upgrade one-time-import behavior, with independent
in-browser edits layered on top, reconciled against a divergent host directory in one
`connectHostDirectory`/`runHostReconciliation` call:

- `pages/Stable.md` — identical content on host and in `cache` → asserts `Identical`, no OPFS write,
  no `onHostConflict` call, no `hostWritePending` entry.
- `pages/EditedBoth.md` — host content differs from `cache` content (external edit + browser edit
  both landed since last sync) → asserts `HostChangedConflict`, `onHostConflict` invoked exactly
  once with the host content, `cache` **left untouched** (not overwritten — the core Critical Finding
  assertion).
- `pages/NewOnDisk.md` — present only on host (e.g. added via `git pull` before the user opted in)
  → asserts `HostOnlyNew`, imported into `cache` via `CacheAccess.set` + `writeOpfsMirror`.
- `pages/BrowserDraft.md` — present only in `cache` (created in-browser after the original one-time
  import, never written to host) → asserts `BrowserOnlyNeedsPush`, enqueued into `hostWritePending`,
  **not** lost.
- `pages/Secret.md.stek` — paranoid-mode encrypted file present on both sides with differing bytes →
  asserts `HostChangedConflict` via `classifyReconciliationBytes` specifically (bytes methods
  exercised, not string methods) — folds Blocker 4's requirement into the same fixture rather than a
  separate test, since the Migration Plan's "no silent data loss for every divergence this migration
  step can see" claim explicitly covers paranoid-mode content too.
- **Assertion on the whole run**: exactly one classified outcome per path (5 total), the observability
  log line (`"[SteleKit] reconciliation: N identical, M conflict, K host-only, J browser-only"`)
  reflects the correct per-category counts, and no path is silently dropped or double-classified.
- **Rollback leg**: a second, independent assertion in the same test file (or a paired test) that
  `pickDirectoryAsync()` on a *fresh, empty* graph is unaffected — i.e. the "if `connectHostDirectory`
  is never invoked, nothing changes" rollback claim from the Migration Plan's Rollback bullet holds.
  (This duplicates Task 3.3.1d's regression test by design — the Migration Plan's own Rollback claim
  deserves its own explicit assertion in the migration-scoped file, not just a cross-reference.)

### Crash-recovery regression tests — Task 3.3.1g (asserts fixed, safe behavior — scope expansion, Epic 1.7)

Already represented in the Requirement → Test Mapping table above as three tests in
`HostDirectorySyncReconciliationTest.kt` (previously two — a third was added alongside the fix):

1. `reconnectHostDirectory_should_ReenqueueHostWritePending_When_CacheHoldsBrowserOnlyEditButInMemoryQueueWasLostToCrash`
   — the **resolved half**, unchanged: proves a crash that lost only the in-memory `hostWritePending`
   map (but whose OPFS write had already landed) self-heals via reconciliation. This is a genuine
   regression guard — it asserts correct, desired behavior.

2. `scheduleHostWriteThrough_should_EnqueuePathOnceDelayedOpfsWriteResolves_When_WriteFileWasCalledWithASlowOpfsWriteFileDouble`
   — **replaces the former "residual gap" test.** Per plan.md's Epic 1.7 (the OPFS-write-durability
   fix — scope explicitly expanded by the user, superseding this plan's original Option-B "accept and
   document" decision), this test is built with a **slow-but-eventually-resolving** `opfsWriteFile`
   test double, not a never-resolving one — a never-resolving double cannot meaningfully assert "data
   is not lost" since nothing can be awaited to a testable completion. It calls
   `writeFile("pages/Draft.md", "unsaved edit")` followed by the normal `scheduleHostWriteThrough`
   delegation and asserts that once the delayed OPFS write resolves, `hostWritePending` **does**
   contain the path — the edit is not silently dropped during the wait. **This test now asserts
   correct/safe behavior, not documented accepted loss.** A future PR must not weaken this test back
   into a fixture that pre-seeds the edit as already durable or reintroduces a never-resolving
   double framed as "documenting" loss — that would be a regression against this fix, not a
   legitimate simplification.

3. `scheduleHostWriteThrough_should_NotContainPathUntilOpfsWriteDeferredResolves_When_GivenTheSameSlowOpfsWriteFileDouble`
   — the second, mechanism-level regression test kept per this fix pass's explicit instruction: using
   the same slow-but-eventually-resolving double, asserts `hostWritePending` does **not** yet contain
   the path immediately after `scheduleHostWriteThrough`'s call returns control (before the delay
   elapses) and **does** after the delay resolves — proving the await mechanism itself (Task
   1.7.1a/1.7.1b), not just its eventual outcome. Complements
   `PlatformFileSystemOpfsWriteDurabilityTest.kt`'s Task 1.7.3a test of the same mechanism from the
   write side.

A true, un-awaitable hard crash (OOM kill, force-quit) remains outside what any client-side JS fix
can close — this was never claimed as fully closeable. What Epic 1.7 closes, and what the tests above
now assert, is the previously-real race where the write-through queue could enqueue (or silently miss
tracking) an edit that *was* going to complete, just not yet — the actual bug the Critical Finding and
adversarial-review.md's sole remaining Blocker identified.

---

## Test Stack
- **Unit**: `kotlin.test` via `kmp/src/commonTest` (pure/platform-agnostic logic — `HostAccessState`,
  `ReconciliationOutcome`, `HostWritePayload`, `FolderSyncLockNaming`, `classifyReconciliation`/
  `classifyReconciliationBytes`) and `kmp/src/wasmJsTest` (`HostDirectorySync` behavior against fake
  `CacheAccess`/mocked `dirHandle`/mocked interop functions — no real browser API calls). Compose UI
  assertions for `FolderSyncStatusBadge` use this codebase's existing Compose-for-Web test harness
  pattern (consistent with other `wasmJsTest` UI coverage).
- **Integration**: `kmp/src/wasmJsTest` tests that either (a) exercise real browser APIs (IndexedDB,
  Web Locks — `WebLockTest.kt`, `HostDirectoryInteropIndexedDbLiveTest.kt`) run against a real
  browser test target (mirrors `WasmGitWriteServiceLiveTest.kt`'s existing "Live" naming convention
  for real-API tests vs. `...MockedIntegrationTest.kt` for mocked ones), or (b) wire multiple real,
  unmodified collaborators together (`FileRegistry`/`GraphFileWatcher`/`GraphLoader` in
  `HostDirectorySyncExternalChangeTest.kt`) against a `HostDirectorySync` under test, per this
  codebase's existing `IT-*`-prefixed integration-test convention (see
  `PlatformFileSystemDirtyTrackingIntegrationTest.kt`).
- **Migration**: `HostDirectorySyncMigrationReconciliationTest.kt` (`wasmJsTest`) — a dedicated,
  larger-fixture integration test scoped specifically to the Migration Plan's upgrade-boundary
  contract, distinct from Epic 3.3's per-branch unit tests (which test each `ReconciliationOutcome`
  in isolation) and from Story 3.2.1's 4-path combined test (which is a smaller, implementation-level
  fixture) — this one is fixture-realistic (named after actual upgrade-boundary scenarios:
  `Stable`/`EditedBoth`/`NewOnDisk`/`BrowserDraft`/`Secret`) and is the artifact a reviewer should
  read to verify the Critical Finding is closed end-to-end.
- **Benchmark**: `HostDirectoryPollerBenchmarkTest.kt` (`wasmJsTest`) — wall-clock-bounded regression
  gate at 8,000+-file scale, matching this codebase's existing `LargeGraphWarmStartCrashTest`/
  `QueryPlanAuditTest` precedent cited in `CLAUDE.md`.
- **E2E / UX**: Playwright, `e2e/tests/folder-sync-*.spec.ts`, following `e2e/tests/benchmark.spec.ts`/
  `demo.spec.ts`'s existing structure (single `test('description', async ({ page }) => {...})` blocks,
  no nested `describe`). Real File System Access API user-gesture/permission-dialog flows are marked
  **Manual** throughout (see UX Acceptance Tests table) since Playwright/CDP cannot reliably script
  native OS-level directory pickers or permission prompts.

---

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM & commonMain/wasmJs | `./gradlew jvmTest` (commonTest runs on the JVM target too) + `./gradlew wasmJsBrowserTest` → inspect `kmp/build/reports/tests/` | All new files in the Requirement → Test Mapping table pass; no coverage percentage gate exists in this repo today (no `jacocoTestReport` task configured for wasmJs) — the gate is the mapping table's completeness, not a numeric threshold |
| Bazel (canonical, JVM/commonMain subset only — wasmJs stays Gradle-only per `CLAUDE.md`) | `bazel test //kmp:jvm_tests` | All `commonTest`-scoped new files (`HostAccessStateTest`, `HostReconciliationTest`, `HostWritePayloadTest`, `FolderSyncLockNamingTest`) pass under Bazel too — wasmJs-scoped tests are Gradle-only until `wasmJsBrowserTest` gets Bazel coverage |
| E2E | `cd e2e && npx playwright test folder-sync` | All Playwright specs in the UX Acceptance Tests table pass in CI's Chromium target; Manual items are checked off by a human tester per release, tracked outside this repo's automated gate |

- **All public service methods** (`HostDirectorySync`'s public/`internal` suspend functions,
  `PlatformFileSystem`'s 7 delegation touch points): happy path + error path covered per the
  Requirement → Test Mapping table above — every row pair (`_should_X_When_HappyCondition` /
  `_should_Y_When_ErrorCondition`) is present.
- **All external integrations** (IndexedDB, Web Locks, `FileSystemObserver`, `navigator.storage`):
  unit-mocked in `HostDirectoryInteropTest.kt` + at least one real-browser integration test each
  (`WebLockTest.kt`, `HostDirectoryInteropIndexedDbLiveTest.kt`; `FileSystemObserver`'s real-browser
  leg is covered indirectly by `HostDirectorySyncExternalChangeTest.kt`'s fast-path tests since a
  fully mocked observer is sufficient to prove dispatch logic — the interop call itself is a 3-line
  `js()` wrapper with no independent branching to integration-test beyond what
  `fileSystemObserverSupported_should_ReturnTrue_When_RunningOnChrome133OrNewer` already covers).
- **UX acceptance criteria**: all 24 criteria in `design/ux.md` §15 have a corresponding entry in the
  UX Acceptance Tests table above — 17 automated via Playwright, 7 marked Manual with an explicit
  reason (native OS dialog / real user gesture required).
- **Migration contract**: `HostDirectorySyncMigrationReconciliationTest.kt` is the single
  reviewer-readable artifact proving the Critical Finding is closed — see "Migration & Known-Limitation
  Coverage" above.
- **Formerly a known limitation, now closed (scope expansion, Epic 1.7)**: the `writeFile`/
  `writeFileBytes` OPFS-write-durability gap that Task 3.3.1g's tests previously only *documented*
  is now fixed at the root, and those tests assert correct/safe behavior accordingly — see "Migration
  & Known-Limitation Coverage" above. There is no longer a test in this validation plan whose passing
  status does *not* mean "this works correctly."
