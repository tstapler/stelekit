# Validation Plan: ui-screen-coverage
**Date**: 2026-10-13

## Happy Path Scenario

Given a user with two graphs open who wants to copy a selection of pages from their "Personal" graph to their "Work" graph: when they open the copy flow, pick pages, review the dry-run summary, confirm, and then review any conflicts that the copy produced — all screens in the copy/conflict/stats/import/git-setup flows render their primary content correctly, all button callbacks fire to the wired actions, and the overall `ui.screens` line coverage measured by `jacocoAndroidTestReport` meets or exceeds 55%.

---

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| AC-1: CopyPagesScreen ≥50% — page-list rendering | `CopyPagesScreenTest.kt` (extend) | `copyPages_should_renderPageNames_when_listIsLoaded` | Unit (Pattern A) | `CopyPagesContent` with 3 `PageRowState` rows; assert each name visible |
| AC-1: CopyPagesScreen ≥50% — selection toggle | `CopyPagesScreenTest.kt` (extend) | `copyPages_should_toggleSelectionOff_when_pickedRowIsClicked` | Unit (Pattern A) | Render with `picked = setOf(uuid(1))`; click row; assert selected count decrements |
| AC-1: CopyPagesScreen ≥50% — copy-trigger button | `CopyPagesScreenTest.kt` (extend) | `copyPages_should_enableReviewButton_when_destinationAndSelectionAreReady` | Unit (Pattern A) | `state(picked = setOf(uuid(1)), destinations = listOf(available)).copy(destinationId = GraphId("ok"))`; assert "Review copy" enabled |
| AC-1: CopyPagesScreen ≥50% — empty state | `CopyPagesScreenTest.kt` (extend) | `copyPages_should_showZeroCount_when_loadCompletesWithNoResults` | Unit (Pattern A) | `state(rows = emptyList(), listLoad = ListLoad.Idle, total = 0L)`; assert "0 results, 0 selected" |
| AC-1: CopyPagesScreen ≥50% — error/loading | `CopyPagesScreenTest.kt` (extend) | `copyPages_should_showLoadErrorBanner_when_loadErrorIsSetWithExistingRows` | Unit (Pattern A) | `state(listLoad = ListLoad.Failed).copy(loadError = "closed")` with rows; assert banner + Retry wires `onRetryLoad` |
| AC-1: CopyPagesScreen ≥50% — ListLoad.Updating | `CopyPagesScreenTest.kt` (extend) | `copyPages_should_showCountingLabel_when_listLoadIsUpdating` | Unit (Pattern A) | `state(listLoad = ListLoad.Updating, total = null)`; assert "Counting..." substring in semantics |
| AC-2: ConflictResolutionScreen ≥50% — hunk rendering | `ConflictResolutionScreenTest.kt` (extend) | `conflictResolution_should_showUseOneVersionAndResolveLineTabs_when_hunkIsUnresolved` | Unit (Pattern A) | `show()` without mode selection; assert "Use one version" and "Resolve line by line" both visible |
| AC-2: ConflictResolutionScreen ≥50% — LOCAL pick | `ConflictResolutionScreenTest.kt` (extend) | `conflictResolution_should_resolveToAcceptLocal_when_keepMineIsClicked` | Unit (Pattern A) | Click "Keep mine", Finish Merge; assert `HunkResolution.AcceptLocal` |
| AC-2: ConflictResolutionScreen ≥50% — multi-file gating | `ConflictResolutionScreenTest.kt` (extend) | `conflictResolution_should_keepFinishDisabled_when_onlyFirstOfTwoFilesIsResolved` | Unit (Pattern A) | Two `ConflictFile` objects; resolve first only; assert "Finish Merge" not enabled |
| AC-2: ConflictResolutionScreen ≥50% — dismiss | `ConflictResolutionScreenTest.kt` (extend) | `conflictResolution_should_callOnDismiss_when_dismissActionIsTriggered` | Unit (Pattern A) | `onDismiss` lambda captures call; trigger dismiss path; assert captured once |
| AC-3: ConflictReviewScreen ≥50% — conflict list display | `ConflictReviewTest.kt` (extend) | `conflictReview_should_showAllConflictRows_when_stateIsLoaded` | Unit (Pattern A) | `loaded(row(1), row(2, "Plan"))`; assert both row descriptions present |
| AC-3: ConflictReviewScreen ≥50% — remove-conflict action | `ConflictReviewTest.kt` (extend) | `conflictReview_should_callOnRequestRemove_when_removeThisBlockIsClicked` | Unit (Pattern A) | `loaded(row())`; click "Remove this block"; assert `calls.removeRequested` contains row key |
| AC-3: ConflictReviewScreen ≥50% — re-copy action | `ConflictReviewTest.kt` (extend) | `conflictReview_should_callOnResolve_when_markResolvedIsClickedThenLoadMoreTriggered` | Unit (Pattern A) | `loaded(row(1), row(2), hasMore = true)`; assert "Load more" present; click it; assert `onLoadMore` count increases |
| AC-3: ConflictReviewScreen ≥50% — hasMore pagination | `ConflictReviewTest.kt` (extend) | `conflictReview_should_showLoadMoreButton_when_hasMoreIsTrue` | Unit (Pattern A) | `loaded(row(), hasMore = true)`; assert a node containing "Load more" text is present |
| AC-4: AllPagesScreen ≥50% — page-list rendering | `AllPagesScreenTest.kt` (new) | `allPages_should_renderPageNames_when_repositoryHasPages` | Unit (Pattern B) | `makeVm(pages = listOf(alpha, beta, gamma))`; `waitUntil` 3 rows; assert each name visible |
| AC-4: AllPagesScreen ≥50% — search input | `AllPagesScreenTest.kt` (new) | `allPages_should_narrowList_when_filterTextIsTyped` | Unit (Pattern B) | Seed "Alpha", "Beta"; type "Beta" in filter field; assert only "Beta" row visible |
| AC-4: AllPagesScreen ≥50% — navigation callback | `AllPagesScreenTest.kt` (new) | `allPages_should_invokeOnPageClick_when_pageRowIsTapped` | Unit (Pattern B) | Click a row; assert captured `Page` matches seeded page |
| AC-4: AllPagesScreen ≥50% — empty state | `AllPagesScreenTest.kt` (new) | `allPages_should_showNoRows_when_repositoryIsEmpty` | Unit (Pattern B) | `makeVm()` with no pages; assert zero row nodes |
| AC-4: AllPagesScreen ≥50% — long-press selection | `AllPagesScreenTest.kt` (new) | `allPages_should_enterSelectionMode_when_rowIsLongPressed` | Unit (Pattern B) | Long-press first row; assert `vm.isInSelectionMode.value == true` |
| AC-4: AllPagesScreen ≥50% — delete dialog | `AllPagesScreenTest.kt` (new) | `allPages_should_showDeleteDialog_when_deleteActionIsTriggeredInSelectionMode` | Unit (Pattern B) | Long-press row, trigger delete; assert "Delete 1 selected page(s)? This cannot be undone." visible |
| AC-4: AllPagesScreen ≥50% — bulk delete error | `AllPagesScreenTest.kt` (new) | `allPages_should_callOnBulkDelete_when_deleteIsConfirmed` | Unit (Pattern B) | Select 1 page, confirm delete dialog; assert `onBulkDelete` lambda called with that UUID |
| AC-5: LibraryStatsScreen ≥40% — Loading state | `LibraryStatsScreenTest.kt` (new) | `libraryStats_should_showScanningLabel_when_stateIsLoading` | Unit (Pattern B) | Provider suspends (blocking `CompletableDeferred`); call `vm.load()`; assert "Scanning library…" visible |
| AC-5: LibraryStatsScreen ≥40% — Loaded state | `LibraryStatsScreenTest.kt` (new) | `libraryStats_should_showPageCount_when_providerReturnsReport` | Unit (Pattern B) | Provider returns `GraphStatsReport(pageCount = 42, ...)`; render; `waitUntil(Loaded)`; assert "42" in semantics |
| AC-5: LibraryStatsScreen ≥40% — Idle state | `LibraryStatsScreenTest.kt` (new) | `libraryStats_should_showTapRefreshHint_when_stateIsIdle` | Unit (Pattern B) | Construct VM without calling `load()`; assert "Tap refresh to scan your library." |
| AC-5: LibraryStatsScreen ≥40% — Error state | `LibraryStatsScreenTest.kt` (new) | `libraryStats_should_showErrorMessage_when_providerReturnsNull` | Unit (Pattern B) | `NoOpLibraryStatsProvider` (returns null); call `load()`; `waitUntil(Error)`; assert error message text visible |
| AC-5: LibraryStatsScreen ≥40% — Refresh | `LibraryStatsScreenTest.kt` (new) | `libraryStats_should_triggerLoad_when_refreshButtonIsClickedAfterLoaded` | Unit (Pattern B) | Load once to Loaded state; click Refresh icon; assert provider called a second time |
| AC-6: ImportScreen 30%→≥55% — Input rendering | `ImportScreenTest.kt` (new) | `importScreen_should_showPasteTabAndTitle_when_screenOpens` | Unit (Pattern B) | Render with empty VM; assert "Import text" top-bar title and "Paste text" tab label |
| AC-6: ImportScreen 30%→≥55% — text input | `ImportScreenTest.kt` (new) | `importScreen_should_updateRawText_when_userTypesInPasteField` | Unit (Pattern B) | Type "hello world"; `waitUntil { vm.state.value.rawText == "hello world" }` |
| AC-6: ImportScreen 30%→≥55% — button disabled | `ImportScreenTest.kt` (new) | `importScreen_should_disableNextButton_when_rawTextIsEmpty` | Unit (Pattern B) | Empty VM; assert Next/Import button not enabled |
| AC-6: ImportScreen 30%→≥55% — stage transition | `ImportScreenTest.kt` (new) | `importScreen_should_showReviewStage_when_nextIsTappedWithContent` | Unit (Pattern B) | Set rawText, tap Next; assert review-stage content visible |
| AC-6: ImportScreen 30%→≥55% — Back from review | `ImportScreenTest.kt` (new) | `importScreen_should_returnToInputStage_when_backIsTappedFromReview` | Unit (Pattern B) | Navigate to review; click Back; assert input-stage title visible |
| AC-6: ImportScreen 30%→≥55% — URL tab | `ImportScreenTest.kt` (new) | `importScreen_should_showUrlInputField_when_urlTabIsSelected` | Unit (Pattern B) | Click "From URL" tab; assert URL text field present |
| AC-7: GitSetupStep3Auth 16%→≥50% — four radio rows | `GitSetupStep3AuthTest.kt` (new) | `step3Auth_should_showFourAuthTypeRows_when_rendered` | Unit (Pattern A) | Render with `authType = NONE`; assert "No authentication", "SSH key", "HTTPS token", "GitHub (OAuth)" all visible |
| AC-7: GitSetupStep3Auth 16%→≥50% — SSH_KEY change | `GitSetupStep3AuthTest.kt` (new) | `step3Auth_should_callOnAuthTypeChange_when_sshKeyRadioIsClicked` | Unit (Pattern A) | Click "SSH key" row; assert `onAuthTypeChange` invoked with `GitAuthType.SSH_KEY` |
| AC-7: GitSetupStep3Auth 16%→≥50% — SSH section | `GitSetupStep3AuthTest.kt` (new) | `step3Auth_should_showSshFields_when_authTypeIsSshKey` | Unit (Pattern A) | `authType = SSH_KEY`; assert SSH key path field and passphrase field present |
| AC-7: GitSetupStep3Auth 16%→≥50% — HTTPS_TOKEN change | `GitSetupStep3AuthTest.kt` (new) | `step3Auth_should_callOnAuthTypeChange_when_httpsTokenRadioIsClicked` | Unit (Pattern A) | Click "HTTPS token" row; assert callback with `GitAuthType.HTTPS_TOKEN` |
| AC-7: GitSetupStep3Auth 16%→≥50% — token visibility | `GitSetupStep3AuthTest.kt` (new) | `step3Auth_should_callToggleTokenVisible_when_visibilityIconIsClicked` | Unit (Pattern A) | `authType = HTTPS_TOKEN`, `tokenVisible = false`; click Visibility icon; assert `onToggleTokenVisible` called |
| AC-7: GitSetupStep3Auth 16%→≥50% — OAuth section | `GitSetupStep3AuthTest.kt` (new) | `step3Auth_should_showOAuthSection_when_authTypeIsGithubOAuth` | Unit (Pattern A) | `authType = GITHUB_OAUTH`; assert OAuth-section label present |
| AC-7: GitSetupStep3Auth 16%→≥50% — invalid token | `GitSetupStep3AuthTest.kt` (new) | `step3Auth_should_showTokenField_when_authTypeIsHttpsToken` | Unit (Pattern A) | `authType = HTTPS_TOKEN`; assert "HTTPS token" field label present |
| AC-7: GitSetupStep3Auth 16%→≥50% — back/next callbacks | `GitSetupStep3AuthTest.kt` (new) | `step3Auth_should_wireBackAndNextCallbacks_when_navButtonsAreClicked` | Unit (Pattern A) | Click Back; assert `onBack` called; click Next; assert `onNext` called |
| AC-8: Overall ≥55% line coverage | Coverage report | (jacocoAndroidTestReport XML parse) | Integration | `./gradlew :kmp:testDebugUnitTest :kmp:jacocoAndroidTestReport`; `grep -A3 'ui/screens'` on report XML; assert line coverage ≥55% |
| AC-9: Tests pass testDebugUnitTest | Gradle run | `./gradlew testDebugUnitTest` output | Integration | Zero failures, no `NoSuchMethodError` from Robolectric; no `@Config` display errors |
| AC-10: No existing tests broken | Gradle run | `./gradlew testDebugUnitTest` — pre-existing test count | Integration | Test count ≥ baseline; no tests that previously passed are now failing |

---

## UX Acceptance Tests

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| Copy flow — user can move from page selection to dry-run review to confirming the copy | `CopyFlowHostTest.kt` | `copyFlow_should_showDryRunDialog_when_stageIsIdleWithDryRunViewSet` | Robolectric + createComposeRule | 1. Render `CopyFlowContent(CopyFlowState(stage=CopyStage.Idle, dryRun=DryRunView(Ready(…))))`. 2. Assert "Copy N pages" button present. 3. Click it; assert `dryRunConfirm` recorded in calls. |
| Copy flow — stopped copy shows background notice instead of blocking dialog | `CopyFlowHostTest.kt` | `copyFlow_should_showBackgroundNotice_when_runningIsBackgrounded` | Robolectric + createComposeRule | 1. Render `CopyFlowState(stage=CopyStage.Running, backgrounded=true)`. 2. Assert "Copy to … continues in the background" visible. 3. Assert the in-progress dialog is NOT present. |
| Copy flow — interrupted copy offers Resume | `CopyFlowHostTest.kt` | `copyFlow_should_showResumeButton_when_stageIsInterrupted` | Robolectric + createComposeRule | 1. Render `CopyFlowState(stage=CopyStage.Interrupted, interrupted=InterruptedCopyNotice(…))`. 2. Assert "A copy to … was interrupted". 3. Click "Resume"; assert `resume` in calls. |
| Conflict resolution — user picks LOCAL side and resolves | `ConflictResolutionScreenTest.kt` | `conflictResolution_should_resolveToAcceptLocal_when_keepMineIsClicked` | Robolectric + createComposeRule | 1. Render screen with one hunk. 2. Click "Resolve line by line". 3. Click "Keep mine". 4. Click "Finish Merge". 5. Assert `HunkResolution.AcceptLocal` in resolved hunks. |
| Conflict resolution — hand-edited result is preserved through Finish Merge | `ConflictResolutionScreenTest.kt` | `conflictResolution_should_preserveHandEdit_when_finishMergeIsClicked` (existing) | Robolectric + createComposeRule | 1. Keep both → hand-edit result field. 2. Finish Merge. 3. Assert manual content matches the typed text. |
| Conflict review — user removes the last conflict and reaches empty state | `ConflictReviewTest.kt` | `conflictReview_should_showEmptyState_when_lastRowIsResolved` | Robolectric + createComposeRule | 1. Render `loaded(row())`. 2. Click "Mark resolved". 3. Re-render with `loaded()`. 4. Assert "No conflicts to review." |
| Page browsing — user finds a page by searching and taps it | `AllPagesScreenTest.kt` | `allPages_should_invokeOnPageClick_when_filteredRowIsTapped` | Robolectric + createComposeRule | 1. Seed "Alpha", "Beta". 2. Type "Beta" in filter. 3. Click "Beta" row. 4. Assert callback called with `page.name == "Beta"`. |
| Page browsing — bulk delete ends with empty list | `AllPagesScreenTest.kt` | `allPages_should_callOnBulkDelete_when_deleteIsConfirmed` | Robolectric + createComposeRule | 1. Seed 2 pages. 2. Long-press to select one. 3. Trigger delete action. 4. Confirm dialog. 5. Assert `onBulkDelete` called with the correct UUID list. |
| Import — user pastes text and imports successfully | `ImportScreenTest.kt` | `importScreen_should_showReviewStage_when_nextIsTappedWithContent` | Robolectric + createComposeRule | 1. Render `ImportScreen` with empty VM. 2. Type text in paste area. 3. Tap Next/Import. 4. Assert review-stage content (e.g. page name field or preview text) is visible. |
| Auth setup — user selects SSH auth and sees key path field | `GitSetupStep3AuthTest.kt` | `step3Auth_should_showSshFields_when_authTypeIsSshKey` | Robolectric + createComposeRule | 1. Render `Step3Auth(authType=SSH_KEY, …)`. 2. Assert SSH key path field present. 3. Assert passphrase field present. 4. Assert HTTPS token field NOT present. |
| Auth setup — NONE auth hides all credential fields | `GitSetupStep3AuthTest.kt` | `step3Auth_should_hideCredentialFields_when_authTypeIsNone` | Robolectric + createComposeRule | 1. Render with `authType = NONE`. 2. Assert no SSH key path field. 3. Assert no HTTPS token field. 4. Assert no OAuth section. |
| Library stats — idle state prompts user to refresh | `LibraryStatsScreenTest.kt` | `libraryStats_should_showTapRefreshHint_when_stateIsIdle` | Robolectric + createComposeRule | 1. Construct VM; do NOT call `load()`. 2. Render `LibraryStatsScreen`. 3. Assert "Tap refresh to scan your library." text visible. |
| Library stats — loaded state renders stat cards | `LibraryStatsScreenTest.kt` | `libraryStats_should_showPageCount_when_providerReturnsReport` | Robolectric + createComposeRule | 1. Provider immediately returns `GraphStatsReport(pageCount = 42, …)`. 2. Render and `waitUntil(Loaded)`. 3. Assert "42" visible in semantics. |

---

## Test Stack

- Unit: Robolectric + Compose UI Test (`createComposeRule`) + `kotlin.test` assertions
- Integration: `./gradlew testDebugUnitTest` (zero failures) + `./gradlew :kmp:jacocoAndroidTestReport` XML parsing for per-file and package line coverage
- E2E/UX: Manual checklist (Robolectric covers all behavioral assertions listed above; no browser E2E)

---

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/Android (Robolectric) | `./gradlew :kmp:testDebugUnitTest :kmp:jacocoAndroidTestReport` | ≥55% line (ui.screens package) |
| Per-file — CopyPagesScreen.kt | Same report, filter by class name | ≥50% line |
| Per-file — ConflictResolutionScreen.kt | Same report, filter by class name | ≥50% line |
| Per-file — ConflictReviewScreen.kt | Same report, filter by class name | ≥50% line |
| Per-file — AllPagesScreen.kt | Same report, filter by class name | ≥50% line |
| Per-file — LibraryStatsScreen.kt | Same report, filter by class name | ≥40% line |
| Per-file — ImportScreen.kt | Same report, filter by class name | ≥55% line |
| Per-file — GitSetupStep3Auth.kt | Same report, filter by class name | ≥50% line |
| Regression check | `./gradlew testDebugUnitTest` | All pre-existing tests pass |
| Bazel regression check | `bazel test //kmp:android_unit_tests` | All suite tests pass (new Compose tests excluded by design) |

**Extracting per-package numbers from the JaCoCo XML:**
```bash
grep -A3 'ui/screens' kmp/build/reports/jacoco/jacoco-android.xml | grep 'LINE'
```

**Key constraint reminders for implementers:**
- `@RunWith(RobolectricTestRunner::class)` on every new class.
- `MaterialTheme { … }` wrapper always required.
- Inject `Dispatchers.Unconfined` (not `TestScope`) into ViewModels — eager coroutine execution; `waitUntil(5_000)` for async state.
- Never add new Compose test classes to `AllAndroidUnitTests.kt`.
- All new files go in `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/` (or the matching git subpackage).
