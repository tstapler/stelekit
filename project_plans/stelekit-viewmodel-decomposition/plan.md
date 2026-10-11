# Decomposition Plan: StelekitViewModel.kt

**Target**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (2,773 lines)
**Date**: 2026-09-30
**Status**: Diagnosis + phased plan only — no code changed in this pass
**Source ranking**: `docs/reference/hotspot-ranking.md` (#1, complexity × 6-month churn), `docs/architecture-audit-2026-07-04.md` (independent line-count audit)
**Precedent**: PR #367 (merged) — `App.kt`/`GraphContentActiveShell.kt` extraction, same pattern applied here

Methodology note: kibitzer's `list_architecture_symbols`/`get_architecture_node` returned zero
matches for this package (no Kotlin architecture index present for this repo at the time of this
pass — `possibly_pruned: true`), so grouping below is derived from `Grep` line-numbered function
listing + targeted `Read` of each cluster, per the fallback the task allows.

---

## Diagnosis

**Single Responsibility Principle violation**: `StelekitViewModel` is the single Facade for ~13
largely-independent bounded contexts (sections, git sync, LLM suggestions, disk-conflict
resolution, sharing/export, block editing, navigation, graph lifecycle, command palette, rename,
debug/settings) that have no behavioral dependency on each other except through two shared
choke points — the monolithic `_uiState: MutableStateFlow<AppState>` and the single `scope`.
Churn confirms this is not just a line-count artifact: unrelated features (git sync, LLM
suggestions, sections) have each independently driven edits to this one file over the last 6
months, which is exactly Fowler's Shotgun-Surgery/Divergent-Change smell pair, not a false
positive from one big feature landing once.

---

## Function Groups

Line ranges are for the current file on `chore/hotspot-ranking-ledger`. "AppState fields" lists
only fields this group both reads and is the sole writer of, unless noted.

| # | Group | Functions (line) | Count / LOC | AppState fields owned | Cross-group coupling |
|---|-------|-------------------|-------------|------------------------|----------------------|
| 1 | **Section Management** | `loadSectionManifest`(2623), `movePageToSection`(2641), `createSection`(2673), `renameSection`(2698), `deleteSection`(2712), `setDefaultSection`(2728), `setSectionState`(2733), `setSectionStates`(2739), `completeDeviceSetup`(2744), `showSectionPicker`(2758), `dismissSectionPicker`(2762), `setSectionQuickToggleVisible`(2766), `newSectionJournalForToday`(2223, non-contiguous) | 13 fn / ~160 loc | `currentManifest`, `currentSectionStates`, `defaultSection`, `deviceSetupComplete`, `deviceSetupWizardVisible`, `sectionPickerVisible`, `sectionPickerPage`, `sectionQuickToggleVisible` | calls `sendSnackbar` (Notifications), `graphWriter.movePageToSection`/`pageRepository.savePage` via `writeActor` (shared deps, not VM groups), `graphLoader.updateSectionFilter`, `onSectionsLoaded` ctor callback |
| 2 | **LLM Suggestion Workflow** | `llmSuggestions`(438), `observeLlmSuggestions`(448), `proposeLlmSuggestion`(462), `dismissLlmSuggestionReview`(467), `rejectLlmSuggestion`(476), `acceptLlmSuggestion`(486), `openLlmProviderSettings`(352), `dismissLlmProviderSettings`(357) | 8 fn / ~90 loc | `llmSuggestionReviewVisible`, `llmProviderSettingsVisible` | calls `sendSnackbar`, reads `activeGraphIdProvider()`/`currentGraphPath`/`currentGraphId`; delegates almost entirely to already-extracted `LlmSuggestionInbox`/`LlmSuggestionWriter` |
| 3 | **Git Sync & Setup/Conflict Dialogs** | `observeSyncState`(300), `triggerSync`(316), `triggerFetchOnly`(324), `setGitConfig`(332), `openGitSetup`(337), `dismissGitSetup`(342), `openGitSetupForCredentials`(347), `openGitSetupForClone`(362), `dismissConflictResolution`(367), `showDiskConflictFullView`(372), `hideDiskConflictFullView`(377), `dismissJournalMergeReview`(382), `abortJournalMerge`(390), `acceptJournalMerge`(407), `dismissGitDetection`(422), `dismissBrowserOnlySyncBanner`(429) | 16 fn / ~170 loc | `gitConfig`, `gitSetupVisible`, `gitSetupInitialStep`, `gitSetupOpenForClone`, `conflictResolutionVisible`, `diskConflictViewFullVisible`, `journalMergeReviewVisible` | derived `StateFlow`s `syncState`/`gitLastSyncAt`/`gitLocalStatusCountFlow` built from ctor-injected `activeGitSyncService`/`localChangesCountFlow`; calls ctor callbacks `onDismissGitDetection`/`onDismissBrowserOnlySyncBanner` |
| 4 | **Share & Export** | `showShareDialog`(1887), `hideShareDialog`(1892), `setShareFormat`(1897), `setShareScope`(1902), `resolveExportContent`(1919), `exportScopeToClipboard`(1951), `launchGoogleAuth`(2014), `refreshShareGoogleAuthState`(2035), `setShareJournalDateRange`(2047), `shareToGoogleDocs`(2051), `setClipboardProvider`(2441), `exportPage`(2449), `exportSelectedBlocks`(2481), `formatDisplayName`(2514) | 14 fn / ~230 loc (two blocks: 1884–2108, 2435–2520) | `shareDialogVisible`, `shareFormat`, `shareScope`, `shareIsGoogleAuthenticated`, `shareGoogleEmail`, `shareJournalFromDate`/`shareJournalToDate`, `isExportingToDrive`, `isExporting` | reads `currentPage`/`blockStateManager.blocksForPage`/`.selectedBlockUuids`; calls `notificationManager` directly (not `sendSnackbar`) |
| 5 | **Disk Conflict Resolution** | `tryMatchDiskBlockContent`(184), `clearPendingConflict`(195), `reconcilePendingConflicts`(212), `observeExternalFileChanges`(1486), `checkAndShowPendingConflict`(1661), `clearFatalError`(1709), `keepLocalChanges`(1724), `acceptDiskVersion`(1748), `manualResolve`(1776), `saveAsNewBlock`(1816) | 10 fn / ~400 loc (non-contiguous: 184–223, 1486–1849) | `pendingConflicts`, `diskConflict`, `fatalError` | **two-way coupling**: `navigateTo`/`goBack`/`goForward` (Navigation group) call `checkAndShowPendingConflict` directly; `bulkDeletePages`/`renamePage` (Navigation/Rename groups) call `clearPendingConflict` directly; reads `blockStateManager` extensively |
| 6 | **Block Editing Primitives** | `indentBlock`(949), `outdentBlock`(956), `moveBlockUp`(963), `moveBlockDown`(970), `moveBlock`(977), `requestEditBlock`(983), `addNewBlock`(988), `addBlockToPage`(1028), `splitBlock`(1063), `mergeBlock`(1072), `handleBackspace`(1096), `focusPreviousBlock`(1128), `focusNextBlock`(1146) | 13 fn / ~215 loc (948–1163) | `editingBlockId`, `editingCursorIndex` (via `requestEditBlock` only) | thin pass-throughs to `blockRepository`; `updateCommands`/UI call these directly by name (`viewModel.indentBlock(...)` etc. from Compose call sites) |
| 7 | **Navigation & Page Lifecycle** | `navigateTo(Screen,...)`(1165), `goBack`(1244), `goForward`(1266), `navigateTo(String)`(1285), `navigateToPageByName`(1301), `navigateToPageByUuid`(1327), `navigateToAnnotationEditor`(1344), `navigateToGallery`(1349), `navigateToBlock`(1353), `bulkDeletePages`(1371), `createPage`(1401) | 11 fn / ~240 loc | `currentScreen`, `navigationHistory`, `historyIndex`, `currentPage` | calls `checkAndShowPendingConflict`/`clearPendingConflict` (Disk Conflict group), `addToRecent`/`refreshCurrentPage` (Graph Lifecycle group), `updateCommands` (Commands group) |
| 8 | **Graph Lifecycle & Recent Pages** | `observeSpecialPages`(594), `refreshRecentPages`(629), `trimRecentPagesCache`(644), `loadMoreRegularPages`(648), `loadMoreJournalPages`(671), `addToRecent`(682), `triggerReindex`(700), `setGraphPath`(719), `loadGraph`(737), `refreshCurrentPage`(897), `reloadCurrentPageFromDisk`(908), `toggleFavorite(Page)`(924), `toggleFavorite(String)`(933), `clear`(941), `startAutoSave`(1459), `stopAutoSave`(1854) | 16 fn / ~330 loc | `isLoading`, `isFullyLoaded`, `currentGraphPath`, `statusMessage`, `recentPages`, `regularPages*`, `journalPages`, `favoritePages`, `_indexingProgress` | central — `loadGraph`'s callbacks call into Section Mgmt (`loadSectionManifest`), Midnight Watcher (`startMidnightBoundaryWatcher`), and `journalService` directly |
| 9 | **Command Palette** | `updateCommands`(2231), `buildFormatCommands`(2355), `executeCommand`(2202), `getAvailableCommands`(2215), `searchPages`(2389) | 5 fn / ~230 loc | `commands` (inside `uiState`) | **fans out to nearly every other group** — `updateCommands` builds its command list by calling `showRenameDialog`, `exportPage`×4, `navigateTo`, `newSectionJournalForToday`; cannot move without becoming a dependency hub either way |
| 10 | **Rename Page** | `showRenameDialog`(2524), `dismissRenameDialog`(2528), `renamePage`(2532) | 3 fn / ~60 loc | `renameDialogPage`, `renameDialogBusy`, `renameDialogError` | delegates to already-extracted `BacklinkRenamer`; calls `clearPendingConflict` (Disk Conflict), `loadMoreRegularPages` (Graph Lifecycle) |
| 11 | **Midnight/Journal-Boundary Watcher** | `millisUntilNextMidnight`(2587), `startMidnightBoundaryWatcher`(2595) | 2 fn / ~35 loc | none (internal `Job`/`LocalDate` fields) | called from `loadGraph`'s `onPhase1Complete` |
| 12 | **Undo/Redo** | `canUndo`, `canRedo`, `undo`(251), `redo`(257) | 4 fn / ~20 loc | none (delegates to `UndoManager`) | none — already nearly a pure facade |
| 13 | **UI Toggles / Settings (misc)** | `toggleSidebar`, `toggleRightSidebar`, `setSettingsVisible`, `setCommandPaletteVisible`, `setSearchDialogVisible`, `setThemeMode`, `setLanguage`, `setOnboardingCompleted`, `setStatusMessage`, `sendSnackbar`, `toggleDebugMode`, `setLeftHanded`, `setLibsqlDriverEnabled`, `showDebugMenu`, `dismissDebugMenu`, `onDebugMenuStateChange`, `exportBugReport` | 17 fn / ~140 loc | 15+ disjoint single-field `uiState` flags | `sendSnackbar` is called by nearly every other group — cannot move in isolation |
| 14 | **Core infra / construction** | ctor + fields (114–173), `sanitizeErrorMessage`(219), `registerAttachImageCallback`(236), `generateUuid`(1443), `getBlockContent`(1450), `onMemoryPressure`(1470), `close`(1474), `savePendingChanges`(2412), `flushAndLockVault`(2423) | ~9 fn / ~120 loc | n/a | owns `scope`, `_uiState`, all injected deps |

Totals above (~141 fn-equivalents counting property accessors) are consistent with the task's
reported 139-function count; the small delta is accessor-vs-function counting, not a missed group.

---

## Extraction Candidates vs. Must-Stay

**Safe to extract as a standalone collaborator** (own constructor-injected `CoroutineScope` —
reuse the ViewModel's existing `scope`, which already carries the `CoroutineExceptionHandler`
guard and is cancelled in `close()`; never a `rememberCoroutineScope()`, per this repo's ownership
rule):

| Group | Verdict | Why |
|---|---|---|
| 1. Section Management | Extract | Fully contiguous, touches only 8 disjoint `AppState` fields, dedicated test coverage |
| 2. LLM Suggestion Workflow | Extract | Nearly a pure facade over already-extracted `LlmSuggestionInbox`/`LlmSuggestionWriter` |
| 3. Git Sync & Dialogs | Extract | Contiguous, derived `StateFlow`s are self-contained, dedicated test coverage |
| 4. Share & Export | Extract | Clear bounded context, but **no existing direct test coverage** — see Test Coverage section |
| 5. Disk Conflict Resolution | Extract, but last | Two-way coupling with Navigation/Rename groups must become explicit public-method calls on the new collaborator held by the ViewModel |

**Must stay on `StelekitViewModel`** (public API surface other code depends on directly, or
central to the `uiState`/navigation state machine):

| Group | Why it stays |
|---|---|
| 6. Block Editing Primitives | Compose call sites invoke `viewModel.indentBlock(...)` etc. by name; extracting without a facade breaks ~13 call sites for near-zero complexity reduction (each is a 1–3 line repository pass-through) |
| 7. Navigation & Page Lifecycle | Owns `currentScreen`/`navigationHistory`/`historyIndex` — the core state machine `uiState` is built around |
| 8. Graph Lifecycle & Recent Pages | `loadGraph` is the central orchestrator calling into almost every other group's init hooks; extracting it first would just move the God-Object problem, not fix it |
| 9. Command Palette | `updateCommands` is a dependency hub by construction (builds a list of closures over other groups' methods) — it can only move *after* those other groups have stable public APIs to close over |
| 13. UI Toggles / Settings | Each function is a single `_uiState.update` call with no business logic. Per Ousterhout/Fowler, grouping trivial setters into a class for its own sake doesn't reduce complexity — it just relocates line count. **Recommend leaving these in place.** |
| 14. Core infra | Owns `scope`/`_uiState`/dependency injection by definition |

12 (Undo/Redo) and 10 (Rename Page) are small enough to move opportunistically alongside an
adjacent phase but are not independently worth a PR.

---

## Phased Extraction Plan

Each phase is one independently shippable PR, verified with `./gradlew :kmp:compileKotlinJvm` and
`./gradlew jvmTest`/`businessTest` (per this repo's established pattern from PR #367), before the
next phase starts.

| Phase | Extract | Fn / LOC moved | Risk | Why this order |
|---|---|---|---|---|
| **1** | Section Management → `SectionManagementCoordinator` | 13 fn / ~160 loc | Low | Smallest fully-contiguous group with the least cross-group coupling (only shared ctor deps + `sendSnackbar` callback) and the best existing test coverage (3 dedicated businessTest files). Mirrors `GraphContentActiveShell`'s role as the "most mechanically separable piece." |
| **2** | LLM Suggestion Workflow → `LlmSuggestionCoordinator` | 8 fn / ~90 loc | Low | Already delegates almost entirely to two pre-extracted collaborators; this phase mostly relocates orchestration, not logic. Dedicated test coverage exists. |
| **3** | Git Sync & Setup/Conflict Dialogs → `GitSyncCoordinator` | 16 fn / ~170 loc | Low–Medium | Contiguous, dedicated test coverage (`StelekitViewModelSyncStateTest`/`...IntegrationTest`), but the three derived `StateFlow`s (`syncState`, `gitLastSyncAt`, `gitLocalStatusCountFlow`) must move as a unit with their consumers. |
| **4** | Share & Export → `ShareExportCoordinator` | 14 fn / ~230 loc | Medium | No existing direct test coverage — **write regression tests for `exportScopeToClipboard`/`shareToGoogleDocs` success+failure paths as part of this phase, before extracting**, not after. |
| **5** | Disk Conflict Resolution → `DiskConflictCoordinator` | 10 fn / ~400 loc | Medium–High | Good existing test coverage mitigates the main risk, which is structural: `navigateTo`/`goBack`/`goForward`/`bulkDeletePages`/`renamePage` all call into this group directly and must be rewired to call the new collaborator's public methods instead of private ViewModel functions. Do this last so Navigation's call sites are the only remaining unknowns. |

**Not phased (future, re-rank after Phase 5)**: Block Editing Primitives, Graph Lifecycle, Navigation,
and Command Palette remain on `StelekitViewModel` after Phase 5. Re-run the hotspot ranking once
Phases 1–5 land — at an estimated ~1,050 lines / 61 functions removed, the file drops from 2,773
to roughly ~1,700 lines / ~78 functions. Whether further extraction of Graph Lifecycle or
Navigation is worth the risk should be a fresh decision made against the post-Phase-5 churn/
complexity numbers, not assumed now.

---

## Test Coverage Findings

Grepped `StelekitViewModel` usage across `jvmTest`, `commonTest`, `androidUnitTest`, `businessTest`.

| Group | Existing coverage | Verdict |
|---|---|---|
| Section Management | `sections/NewPageAutoAssignmentTest.kt`, `sections/ThreeStateSubscriptionTest.kt`, `sections/DeviceProfileTest.kt` (businessTest) | Adequate — proceed |
| LLM Suggestion Workflow | `ui/StelekitViewModelLlmSettingsTest.kt`, `llm/StelekitViewModelLlmSuggestionTest.kt` (businessTest) | Adequate — proceed |
| Git Sync & Dialogs | `ui/StelekitViewModelSyncStateTest.kt`, `ui/StelekitViewModelSyncStateIntegrationTest.kt` | Adequate — proceed |
| **Share & Export** | **None found** (`grep -rl "shareToGoogleDocs\|exportScopeToClipboard\|resolveExportContent"` → no hits in any test source set) | **Gap — add tests before/during Phase 4** |
| Disk Conflict Resolution | `ui/DiskConflictResolutionTest.kt`, `ui/ExternalFileChangeErrorHandlingTest.kt`, `db/DiskConflictBlockMatcherTest.kt` (businessTest) | Adequate — proceed |
| Block Editing Primitives | No test exercises the ViewModel wrapper methods directly; underlying `BlockRepository` ops are covered (`BlockOperationsEdgeCaseTest`, `DatalogBlockRepositoryTest`) | Low risk even uncovered — thin pass-throughs |
| Navigation | No dedicated navigation test; incidentally touched by `RecentPagesTest`/`DiskConflictResolutionTest` | Gap, but group is not slated for extraction yet |
| Rename Page | No direct `renamePage` test, but `db/BacklinkRenamerTest.kt` covers the collaborator it delegates to | Acceptable |
| Midnight Watcher | `millisUntilNextMidnight`/`startMidnightBoundaryWatcher` are `internal` for testability but no test file directly targets them found by content grep; `db/GraphLoaderProgressiveTest.kt` references the symbols | Light — not slated for extraction yet |

---

## Open Questions / Follow-ups

- Kibitzer has no architecture index for this Kotlin repo yet (`list_architecture_symbols`
  returned 0 matches, `possibly_pruned: true`) — worth checking `.claude/inspect.json` config
  separately; this plan did not depend on it being fixed.
- Phase 4 (Share & Export) should not proceed to implementation until its regression-test gap is
  closed — this is a prerequisite task, not a nice-to-have.
- Phase 5's rewiring of `navigateTo`/`bulkDeletePages`/`renamePage` call sites should be scoped as
  its own sub-task with an explicit list of call-site diffs in that phase's implementation plan,
  not discovered ad hoc during the PR.
