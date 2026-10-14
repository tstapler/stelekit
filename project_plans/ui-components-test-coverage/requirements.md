# Requirements: ui-components-test-coverage

## Context

**Source:** Backlog item `404b362c-e5b0-4909-b332-482d2458885b`
**Branch:** `stelekit-merges @ e337d58c1e`
**Coverage baseline (JVM-only, `:kmp:jvmTest` + `:kmp:jacocoTestReport`):**
- `ui.components` package: lines 5447/11508 = **47.3%**, branches 5754/18410 = **31.3%**
- Overall project: lines 50.5% (36338/71945), branches 31.7%
**Reference:** https://github.com/tstapler/stelekit/pull/402 (coverage gate introduction)

## Problem Statement

Eight composable files in `ui.components` have **0% JVM line coverage**:

| File | Reported lines | Risk tier |
|---|---|---|
| `PerformanceDashboard.kt` | 804 | Diagnostic (low user-data risk) |
| `ShareDialog.kt` | 305 | **User-data path** |
| `VaultSettings.kt` | 264 | **User-data / security-sensitive** |
| `LogDashboard.kt` | 243 | Diagnostic (low risk) |
| `SectionsSettings.kt` | 242 | **User-data path** |
| `ShareInboxPanel.kt` | 208 | Already covered by Robolectric (JVM not merged) |
| `FolderSyncSettings.kt` | 188 | **User-data path** |
| `AutocompleteMenu.kt` | 169 | Editor interaction |

Partially-covered files with the largest coverage gaps:
- `ReferencesPanel.kt` — 26% line coverage (highest gap)
- `SearchDialog.kt` — 51% line coverage
- `BlockItem.kt` — 63% line / 63% branch

**Important caveat:** Coverage numbers are JVM-only — `androidUnitTest` (Robolectric) runs are not merged. `ShareInboxPanel` already has thorough `ShareInboxPanelTest.kt` in `androidUnitTest`; it is 0% in JaCoCo only because coverage from Robolectric runs is not aggregated. This item does NOT require new ShareInboxPanel tests.

## Goals

1. Add tests for the highest-risk zero-coverage files: `ShareDialog`, `VaultSettings`, `FolderSyncSettings`, `SectionsSettings`.
2. Improve branch coverage on `ReferencesPanel` (currently 26% line coverage).
3. Add basic coverage for `AutocompleteMenu` (editor interaction, keyboard navigation).
4. Leave `PerformanceDashboard` and `LogDashboard` as explicit out-of-scope (diagnostic code, no user-data risk, highest complexity-to-test ratio).
5. Each new test must pass in CI: Robolectric tests go in `androidUnitTest`, Compose-Desktop-specific tests in `jvmTest`.

## Non-Goals

- Do not modify any production source code.
- Do not write screenshot (Roborazzi) tests for new files — those belong in a separate effort.
- Do not target 100% coverage — test behavior that could regress silently, not every rendering branch.
- `ShareInboxPanel.kt` already has thorough tests in `androidUnitTest/ShareInboxPanelTest.kt` — no new tests needed.

## Acceptance Criteria

AC-1: `ShareDialogTest` exists in `androidUnitTest` (Robolectric) covering:
  - scope selector options rendered (Page, Selected Blocks, Journal Range)
  - date range validation rejects bad input and accepts valid YYYY-MM-DD range
  - "Start date must be on or before end date" error shown when from > to
  - Google auth status text reflects `isGoogleAuthenticated` state

AC-2: `VaultSettingsTest` exists in `androidUnitTest` covering:
  - "Encryption disabled" status shown when `isParanoidMode = false`
  - "Paranoid mode enabled" status shown when `isParanoidMode = true` and vault unlocked
  - "Graph is locked." status shown when `isParanoidMode = true` and vault locked
  - Create-vault section visible only when `isParanoidMode = false` and `onCreateVault != null`
  - Create-vault section hidden when `isParanoidMode = true`

AC-3: `FolderSyncSettingsTest` exists in `androidUnitTest` covering:
  - Connect button shown only when `hostAccessState = NotApplicable` and `supportsNativeDirectoryPicker = true`
  - Connect button absent when `hostAccessState` is already connected
  - "Move storage location" entry absent when `onMoveStorageLocation = null`
  - "Move storage location" entry present when `onMoveStorageLocation` is non-null

AC-4: `SectionsSettingsTest` exists in `androidUnitTest` covering:
  - Empty state message shown when `manifest.sections` is empty
  - Section names listed when sections exist
  - Create-section button visible; clicking it opens the create dialog
  - Delete confirmation dialog shown when delete icon tapped; section removed on confirm
  - Rename dialog shown when edit icon tapped

AC-5: `AutocompleteMenuTest` exists in `jvmTest` (Compose Desktop) covering:
  - Menu absent when `items` is empty or `cursorRect` is null
  - Items rendered and keyboard Up/Down navigation updates `selectedIndex`
  - Click on an item calls `onItemSelected`

AC-6: `ReferencesPanelTest` exists (or is extended) in `jvmTest` to raise line coverage above 50% (from current 26%), covering:
  - Empty-references state (no links shown)
  - Populated-references state (linked page names rendered)
  - Click on a reference calls the navigation callback

AC-7: All new tests pass `bazel test //kmp:jvm_tests` (jvmTest targets) or `./gradlew testDebugUnitTest` (androidUnitTest targets) without a display (Robolectric tests are headless).

AC-8: No source-code modifications — tests only interact with already-public composable signatures and fake/in-memory dependencies.
