# Research: ui-components-test-coverage

## 1. Current Coverage Baseline

Numbers from `:kmp:jvmTest` + `:kmp:jacocoTestReport` on `stelekit-merges @ e337d58c1e`.  
**Caveat:** JVM-only — `androidUnitTest` (Robolectric) and Bazel runs are not merged, so
Robolectric-tested composables appear at 0% here.

### Zero-coverage composables (JVM)

| File | Line count | Notes |
|---|---|---|
| `PerformanceDashboard.kt` | 804 | Pure diagnostic rendering; no user-data state machine |
| `ShareDialog.kt` | 454 (actual) | 305 lines in backlog item likely refers to executable LOC; **zero tests** confirmed |
| `VaultSettings.kt` | 377 (actual) | Security-sensitive state machine; **zero tests** |
| `LogDashboard.kt` | 243 | Diagnostic rendering |
| `SectionsSettings.kt` | 350 (actual) | CRUD state machine; **zero tests** |
| `ShareInboxPanel.kt` | 381 (actual) | **Already covered** in `androidUnitTest/ShareInboxPanelTest.kt` (Robolectric); 0% is a measurement artefact |
| `FolderSyncSettings.kt` | 375 (actual) | Host-access state machine; **zero tests** |
| `AutocompleteMenu.kt` | 169 | Keyboard navigation; **zero tests** |

### Partial-coverage composables

| File | Line % | Branch % | Existing test |
|---|---|---|---|
| `Sidebar.kt` | 67% | 39% | `SidebarLoadingStateTest.kt` (jvmTest) |
| `SearchDialog.kt` | 51% | — | `SearchDialogTest.kt` (jvmTest) — happy path only |
| `BlockItem.kt` | 63% | 63% | `BlockItemGestureTest.kt`, `BlockItemCodeFenceDispatchTest.kt` (jvmTest) |
| `ReferencesPanel.kt` | 26% | — | **No tests found** |

## 2. Test Infrastructure

### Test harnesses available

**`androidUnitTest` (Robolectric) — preferred for behavior tests**
- `createComposeRule()` from `androidx.compose.ui.test.junit4`
- Headless — no X11 display needed
- Pattern: `ShareInboxPanelTest`, `NewGraphFlowTest`, `AddGraphAppOwnedTest`
- Runs via `./gradlew testDebugUnitTest` or Bazel `//kmp/src/androidUnitTest/kotlin:android_unit_tests`
- Coverage via JaCoCo is NOT merged into the main `:kmp:jacocoTestReport` report — this is why ShareInboxPanel appears at 0% even with thorough tests

**`jvmTest` (Compose Desktop) — for desktop-specific or screenshot tests**
- `ComposeUITestBase` provides pre-wired fakes (`pageRepo`, `blockRepo`, `viewModel`, etc.)
- Requires X11 display (check with `scripts/jvm-display-check.sh`)
- Pattern: `SearchDialogTest`, `SyncStatusBadgeTest`, `StorageMoveChoiceDialogTest`
- Coverage IS merged into the main JaCoCo report

### Fake/in-memory dependencies

The test infrastructure provides:
- `InMemorySearchRepository` — for search-dependent composables
- `PopulatedFakePageRepository`, `PopulatedFakeBlockRepository` — pre-seeded repos
- `FakeFileSystem` — for file-system interactions
- `InMemorySettings` — for `PlatformSettings`
- `ComposeUITestBase` wires all of these into a `StelekitViewModel`

### Composable public signatures (confirmed zero tests)

**`ShareContent` (in `ShareDialog.kt`)**
```kotlin
@Composable fun ShareContent(
    appState: AppState,
    viewModel: StelekitViewModel,
    page: Page?,
    blocks: List<Block>,
    selectedBlockUuids: Set<String>,
    shareProvider: ShareProvider,
    googleAuthManager: GoogleAuthManager?,
    driveClient: DriveUploader?,
    isMobile: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```
Dependencies: `AppState` (has `shareScope`, `shareIsGoogleAuthenticated`, `shareGoogleEmail`),
`StelekitViewModel` (needs fake wiring or can use `ComposeUITestBase`). `googleAuthManager` and
`driveClient` can be null to disable those tiles.

**`VaultSettings` (in `VaultSettings.kt`)**
```kotlin
@Composable fun VaultSettings(
    isParanoidMode: Boolean,
    isVaultUnlocked: Boolean,
    modifier: Modifier = Modifier,
    onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)? = null,
    onLockVault: (() -> Unit)? = null,
    onListActiveSlots: (suspend () -> List<Int>)? = null,
)
```
Pure state-in/callback-out composable — no shared infrastructure needed. All parameters are
nullable or primitive, making it trivially testable with lambda fakes.

**`FolderSyncSettings` (in `FolderSyncSettings.kt`)**
```kotlin
@Composable fun FolderSyncSettings(
    hostAccessState: HostAccessState,
    supportsNativeDirectoryPicker: Boolean,
    onConnect: suspend () -> ReconciliationUiState,
    modifier: Modifier = Modifier,
    onMoveStorageLocation: (suspend () -> StorageLocation)? = null,
    graphName: String = "this graph",
    onStorageLocationChoose: (operation: StorageMoveOperation) -> Unit = {},
    onBrowseRequestForMove: (suspend () -> StorageLocation?)? = null,
)
```
Pure state-in/callback-out; `HostAccessState` is a sealed class with `NotApplicable`,
`Connected`, and `Pending` variants.

**`SectionsSettings` (in `SectionsSettings.kt`)**
```kotlin
@Composable fun SectionsSettings(
    manifest: SectionManifest,
    onCreateSection: (id: String, displayName: String, color: String?, pagePathPrefix: String, journalPathPrefix: String) -> Unit,
    onRenameSection: (id: String, newDisplayName: String) -> Unit,
    onDeleteSection: (id: String) -> Unit,
    modifier: Modifier = Modifier,
)
```
Pure state-in/callback-out; `SectionManifest` and `SectionDefinition` are data classes —
trivially constructable without mocks.

**`AutocompleteMenu` (in `AutocompleteMenu.kt`)**
```kotlin
@Composable fun AutocompleteMenu(
    items: List<SearchResultItem>,
    selectedIndex: Int,
    onItemSelected: (SearchResultItem) -> Unit,
    onDismiss: () -> Unit,
    cursorRect: Rect?,
    filterText: String = "",
    isFilterActive: Boolean = false,
    onFilterTextChange: (String) -> Unit = {},
    onFilterFocusChange: (Boolean) -> Unit = {},
    onDeactivateFilter: () -> Unit = {},
    modifier: Modifier = Modifier,
)
```
Uses `Popup` — requires Compose Desktop (jvmTest), not Robolectric. Returns early if `items.isEmpty() || cursorRect == null` — empty guard is the simplest test.

## 3. Existing Test Patterns to Follow

### Robolectric (androidUnitTest) pattern
`ShareInboxPanelTest` is the gold standard:
- `@RunWith(RobolectricTestRunner::class)` + `@get:Rule val composeTestRule = createComposeRule()`
- Composable receives only primitive/data-class state and lambda callbacks
- Fake actions implemented as inner classes or lambdas with captured `mutableListOf`
- Assertions via `onNodeWithText`, `onNodeWithContentDescription`, `assertIsDisplayed()`, `performClick()`
- `composeTestRule.waitForIdle()` after interactions

### jvmTest pattern  
`SearchDialogTest` for self-contained composables, `StorageMoveChoiceDialogTest` for dialogs with
confirm/cancel flow, `ComposeUITestBase` for ViewModel-wired composables.

## 4. Key Risks and Constraints

**R1: `ShareContent` requires `StelekitViewModel`** — heaviest dependency among target files.
Mitigation: Use `ComposeUITestBase` as the base class, which already wires a fake ViewModel.
Place the test in `jvmTest` to have access to `ComposeUITestBase`.

**R2: `AutocompleteMenu` uses `Popup`** — `Popup` in Robolectric has historically been
problematic; Compose Desktop's `Popup` is known to work in `jvmTest`. Place `AutocompleteMenuTest`
in `jvmTest`.

**R3: `ReferencesPanel` depends on `BlockRepository` and `PageRepository` directly**
(not via ViewModel) and calls `flow.first()` internally — requires coroutine-aware test setup.
The in-memory repositories from `businessTest`/`commonMain` can be used with `runTest` or by
populating the repos before `setContent`. Test must be in `jvmTest` (Compose Desktop).

**R4: JaCoCo coverage report gap** — new `androidUnitTest` (Robolectric) tests will NOT appear
in the current JaCoCo aggregate. The fix would require a separate Gradle task to merge Robolectric
coverage with JVM coverage (a known gap described in PR #402). Confirm with team whether merged
reporting is a prerequisite — if not, accept the Robolectric tests improve real quality even if
the dashboard number doesn't reflect them until the merge task is done.

**R5: Coverage gate from PR #402** — the gate checks aggregate JVM coverage. New `androidUnitTest`
tests won't move the gate number. New `jvmTest` tests for `ShareContent`, `ReferencesPanel`, and
`AutocompleteMenu` will move it. Priority accordingly.

## 5. Test Placement Decision Matrix

| File | Test target | Reason |
|---|---|---|
| `ShareDialog.kt` / `ShareContent` | `jvmTest` | Requires `StelekitViewModel`; `ComposeUITestBase` is in jvmTest |
| `VaultSettings.kt` | `androidUnitTest` | Pure state-in/callback-out; headless Robolectric is fastest |
| `FolderSyncSettings.kt` | `androidUnitTest` | Same — pure callbacks, no ViewModel dependency |
| `SectionsSettings.kt` | `androidUnitTest` | Same |
| `AutocompleteMenu.kt` | `jvmTest` | Uses `Popup`; Robolectric support is unreliable |
| `ReferencesPanel.kt` | `jvmTest` | `BlockRepository`/`PageRepository` coroutine flows; Compose Desktop test |
