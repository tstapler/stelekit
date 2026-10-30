# Plan: ui-components-test-coverage

## Summary

Add Compose UI tests for six zero-coverage or low-coverage components in
`ui.components`, prioritising user-data-path files over diagnostic dashboards.
All new test files are placed in `androidUnitTest` (Robolectric, headless) or
`jvmTest` (Compose Desktop) per the placement matrix in `research.md`. No
production source changes are required.

---

## Task Breakdown

### Task 1 — `VaultSettingsTest` (androidUnitTest) · 3h

**File:** `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/VaultSettingsTest.kt`

Cover the VaultSettings state machine:

1. `status_should_ShowEncryptionDisabled_WhenParanoidModeFalse` — set `isParanoidMode = false`, assert "Encryption disabled" text and "Files are stored as plain markdown." subtext are visible.
2. `status_should_ShowParanoidModeEnabled_WhenUnlocked` — set `isParanoidMode = true`, `isVaultUnlocked = true`, assert "Paranoid mode enabled" and "Graph is unlocked. Files are encrypted at rest." are visible.
3. `status_should_ShowGraphLocked_WhenLockedAndParanoid` — set `isParanoidMode = true`, `isVaultUnlocked = false`, assert "Graph is locked." is visible.
4. `createVaultSection_should_BeVisible_WhenNotEncryptedAndCallbackProvided` — `isParanoidMode = false`, `onCreateVault = { … }` non-null; assert a "Create vault" or "Enable encryption" button is visible.
5. `createVaultSection_should_BeHidden_WhenAlreadyEncrypted` — `isParanoidMode = true`, assert create-vault button absent.
6. `lockButton_should_CallOnLockVault_WhenClicked` — `isParanoidMode = true`, `isVaultUnlocked = true`, `onLockVault = { locked = true }`; assert click fires callback.

Pattern: `@RunWith(RobolectricTestRunner::class)`, `createComposeRule()`, no
ViewModel, wrap in `MaterialTheme`.

---

### Task 2 — `FolderSyncSettingsTest` (androidUnitTest) · 3h

**File:** `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/FolderSyncSettingsTest.kt`

Cover the host-access state gating and storage-move entry points:

1. `connectButton_should_BeVisible_WhenNotApplicableAndPickerSupported` — `hostAccessState = HostAccessState.NotApplicable`, `supportsNativeDirectoryPicker = true`; assert connect button/affordance visible.
2. `connectButton_should_BeAbsent_WhenAlreadyConnected` — `hostAccessState = HostAccessState.Connected(…)`; assert connect affordance absent.
3. `connectButton_should_BeAbsent_WhenPickerNotSupported` — `hostAccessState = HostAccessState.NotApplicable`, `supportsNativeDirectoryPicker = false`; assert connect affordance absent.
4. `moveStorage_should_BeAbsent_WhenOnMoveIsNull` — `onMoveStorageLocation = null`; assert move-storage entry absent.
5. `moveStorage_should_BePresent_WhenOnMoveIsProvided` — `onMoveStorageLocation = { … }` non-null; assert move-storage entry visible.
6. `onConnect_should_BeCalled_WhenButtonTapped` — capture invocations; assert callback called after button click.

---

### Task 3 — `SectionsSettingsTest` (androidUnitTest) · 2h

**File:** `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/SectionsSettingsTest.kt`

Cover the CRUD state machine:

1. `emptyState_should_ShowEmptyMessage` — `manifest = SectionManifest(sections = emptyList())`; assert "No sections" or equivalent empty-state text is visible.
2. `sections_should_BeListedByName` — manifest with two sections; assert both display names are visible.
3. `createDialog_should_OpenOnButtonTap` — tap "Add section" button; assert create dialog is shown.
4. `deleteConfirmation_should_CallCallback_WhenConfirmed` — tap delete icon on a section; assert confirm dialog shown; confirm; assert `onDeleteSection` was called with correct id.
5. `renameDialog_should_CallCallback_WhenSubmitted` — tap edit icon; rename dialog opens; type new name; confirm; assert `onRenameSection` called.

---

### Task 4 — `ShareContentTest` (jvmTest) · 4h

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/ShareContentTest.kt`

`ShareContent` requires `StelekitViewModel`; extend `ComposeUITestBase`.

1. `scopeSelector_should_RenderAllThreeOptions` — set `appState.shareScope = ShareScope.Page`; render ShareContent; assert "Page", "Selected Blocks", and "Journal Range" scope options visible.
2. `journalRange_should_ShowDateFields_WhenScopeIsJournalRange` — set scope to `JournalRange`; assert date fields appear.
3. `journalRange_should_ShowError_WhenInvalidDateFormat` — fill from/to with non-date string; trigger export; assert "Enter dates as YYYY-MM-DD" error appears.
4. `journalRange_should_ShowError_WhenFromAfterTo` — fill valid dates where from > to; assert "Start date must be on or before end date".
5. `googleAuth_should_ShowEmail_WhenAuthenticated` — `appState.shareIsGoogleAuthenticated = true`, `shareGoogleEmail = "test@example.com"`; assert email visible.
6. `googleAuth_should_ShowSignInPrompt_WhenNotAuthenticated` — `appState.shareIsGoogleAuthenticated = false`; assert Google Drive tile shows sign-in affordance (or auth placeholder).

Note: `GoogleAuthManager` and `DriveUploader` passed as null to disable those tiles in basic tests; one additional test passes a fake `GoogleAuthManager` that returns `true` for auth status.

---

### Task 5 — `AutocompleteMenuTest` (jvmTest) · 2h

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/AutocompleteMenuTest.kt`

Uses `Popup` — must be in jvmTest (Compose Desktop).

1. `menu_should_BeAbsent_WhenItemsIsEmpty` — `items = emptyList()`, `cursorRect = Rect(…)`; assert no menu nodes.
2. `menu_should_BeAbsent_WhenCursorRectIsNull` — non-empty items, `cursorRect = null`; assert no menu nodes.
3. `menu_should_RenderItemText` — two items with distinct titles; assert both texts are visible.
4. `itemClick_should_FireOnItemSelected` — click an item; assert `onItemSelected` invoked with correct item.
5. `selectedIndex_should_HighlightCorrectItem` — pass `selectedIndex = 1`; assert second item is in selected/highlighted state (via content description or test tag).

---

### Task 6 — `ReferencesPanelTest` (jvmTest) · 2h

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/ReferencesPanelTest.kt`

`ReferencesPanel` takes `BlockRepository` and `PageRepository` directly. Use
`InMemoryBlockRepository` + `InMemoryPageRepository` from `commonMain`; pre-populate
before calling `setContent`.

1. `emptyState_should_ShowNoReferencesMessage` — page with no linked blocks; assert empty-state text visible on the "Linked" tab.
2. `linkedReferences_should_ListBlockContent` — insert blocks that link to target page; assert block content snippets appear.
3. `linkClick_should_FireOnLinkClick` — click a linked block entry; assert `onLinkClick` called with the correct page name.
4. `unlinkedTab_should_ShowUnlinkedMentions` — insert a block whose text mentions the page name without `[[…]]`; switch to "Unlinked" tab; assert block content visible.

---

### Task 7 — Coverage gate verification · 0.5h

After the new jvmTest tests (Tasks 4, 5, 6) are written and passing:

1. Run `./gradlew :kmp:jvmTest :kmp:jacocoTestReport` locally (or with `scripts/jvm-display-check.sh` wrapper).
2. Confirm aggregate `ui.components` line coverage has increased from 47.3%.
3. Confirm the coverage gate in CI (PR #402) passes.

Note: Tasks 1–3 (Robolectric) will not move the JaCoCo gate but will improve real quality. Document this gap in a comment on PR #402 or this plan and track the Robolectric coverage merge as a follow-up.

---

## Execution Order

Tasks are independent — all six test files can be written in parallel. Recommended order for a single developer:

1. Tasks 1, 2, 3 first — fastest ROI, headless, no display needed.
2. Task 6 (ReferencesPanel) — covers the largest partial-coverage gap.
3. Task 5 (AutocompleteMenu) — straightforward guard/render tests.
4. Task 4 (ShareContent) — most complex, requires ViewModel wiring.
5. Task 7 — gate verification after tasks 4–6.

---

## Architecture Notes

- All new tests interact only with already-public composable signatures — no source changes.
- Do not use `rememberCoroutineScope()` in test helpers; use `CoroutineScope(Dispatchers.Unconfined)` for any scope passed to helpers.
- For `VaultSettings`, `FolderSyncSettings`, and `SectionsSettings`, the nullable-callback pattern means the composable self-gates: null callback → UI element absent. Tests for the hidden state are cheap and cover branch logic.
- `ShareContent` date-range validation lives in a local `parseDateRange()` function inside the composable — test it via UI interaction (fill fields + trigger export button) rather than calling it directly.

---

## Amendment: alignment with the 10 backlog criteria

Supersedes the "out of scope" note and Task 4 placement above.

- **Task 4 (revised):** `ShareContentTest` moves to `androidUnitTest` (Robolectric) per backlog AC1; cover scope selector, format toggle, invalid-date error, dismiss callback.
- **Task 8 (new):** `PerformanceDashboardTest` and `LogDashboardTest` smoke tests (render, assert ≥ 1 text node) targeting ≥ 30% lines each (AC6).
- **Task 9 (new):** extend `ShareInboxPanelTest` with a NeedsNewerApp status row and a quarantine-confirm dialog (AC7).
- **Task 10 (revised gate):** run `jacocoTestReport` plus the Android unit-test report, merge, and report real `ui.components` line coverage vs the 60% target (AC8). If the merge isn't achievable or the number falls short, mark AC8 `/backlog/fail-7` with the measured figure rather than claiming it.
- **Test-writing note:** Robolectric's default 320x470 px screen clips unscrollable columns (verified: VaultSettings "Cancel" had zero height, so submit never fired). Use `@Config(qualifiers = "w480dp-h1600dp")`.
- **Order:** Task 4, Task 9, Task 1 (done, 13 tests green), Tasks 2, 3, 5, then 8, 6, 10.

---

## Final status (supersedes the task list above where they differ)

Coverage = union of `jacoco.xml` (jvmTest) and `jacoco-android.xml` (testDebugUnitTest) after a full
run of both suites; lines covered/total.

| Backlog AC | Test file(s) | Source set | Measured |
|---|---|---|---|
| 1 ShareContent ≥60% | `ShareContentTest` (16) | androidUnitTest | `ShareContent` fn 66.9% (ShareDialog.kt file: 59.7%) |
| 2 VaultSettings ≥60% | `VaultSettingsTest` (13) | androidUnitTest | 96.2% |
| 3 SectionsSettings ≥50% | `SectionsSettingsTest` (5) + `SectionsSettingsDialogsTest` (6) | androidUnitTest + jvmTest | 98.3% |
| 4 FolderSyncSettings ≥50% | `FolderSyncSettingsTest` (10) | androidUnitTest | 66.0% |
| 5 AutocompleteMenu ≥60% | `AutocompleteMenuTest` (10) | jvmTest | 92.9% |
| 6 Dashboards ≥30% | `PerformanceDashboardTest` (2), `LogDashboardTest` (3) | jvmTest | 35.1% / 60.5% |
| 7 ShareInboxPanel branches | `ShareInboxPanelTest` 15 → 29 | androidUnitTest | 89.9% lines, 36.5% branches (no pre-change branch baseline recorded) |
| 8 package ≥60% | — | — | `ui.components` 66.6% lines (JVM-only 63.9%) |

Deviations: dialogs containing an `OutlinedTextField` hang Robolectric (infinite layout loop, then heap
OOM that fails every later test in the JVM), so the create/rename-section dialog tests run on Compose
Desktop and need a display (`scripts/jvm-display-check.sh`). `ReferencesPanelTest` (Task 6) was not built:
it is not among the backlog criteria. The Escape-key filter test for `AutocompleteMenu` was dropped
because key injection does not reach the `Popup` in the test harness.
