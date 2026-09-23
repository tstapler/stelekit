# Validation Plan: tag-suggestion-trigger

**Feature**: Add "Suggest tags" button to MobileBlockToolbar  
**Date**: 2026-06-22  
**Status**: Ready — write tests before writing code

---

## Happy Path (end-to-end)

A user taps a block on the Journals or Page screen, taps the "Suggest tags" label-icon button that appears in the `MobileBlockToolbar` primary actions row, and the `SuggestionBottomSheet` slides up showing tag chips derived from the editing block's content via `TagSuggestionViewModel.requestSuggestions`.

---

## Test Suite

### Part 1 — Unit Tests (businessTest)

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbarSuggestTagsTest.kt`  
Package: `dev.stapler.stelekit.ui.components`

These are pure logic tests — no Compose runtime, no screenshot. They verify the callback contract and the blank-content guard that lives inside `EditorToolbar`.

---

#### REQ-TOOLBAR-1: `onSuggestTags` parameter renders the button only when non-null

**Story 1.1.1 — button presence is controlled by nullable callback**

```
onSuggestTags_should_invokeCallback_when_tapped
```
- Setup: capture counter variable; build minimal MobileBlockToolbar with `editingBlockId = "abc"` and `onSuggestTags = { counter++ }`.
- Action: perform click on the node with `contentDescription = "Suggest tags"`.
- Assert: counter == 1.

```
onSuggestTags_should_notInvokeCallback_when_tappedTwice_and_firstInvocationPreventsSecond
```
- Error path: Tapping once increments counter to 1; a second tap also calls through (no debounce in the button itself). This verifies the button is not accidentally deduplicated at the toolbar level.
- Assert: counter == 2.

> Note: these two tests require the Compose test rule (ComposeTestRule) so they live in **jvmTest** (see Part 2, Screenshot & Compose tests). The pure logic counterpart below tests the `EditorToolbar` lambda-derivation without any Compose rendering.

---

#### REQ-WIRING-1: `EditorToolbar` derives null lambda when `editingBlockUuid` is null

**Story 1.1.2 — no-arg lambda derivation logic**

```
deriveOnSuggestTagsLambda_should_returnNull_when_editingBlockUuidIsNull
```
- Setup: Plain Kotlin function mirroring the `run { }` derivation block in `EditorToolbar` — `editingBlockUuid = null`, `onSuggestTags = { _, _ -> }`.
- Action: evaluate the derived lambda.
- Assert: derived lambda is `null`.

```
deriveOnSuggestTagsLambda_should_returnNull_when_onSuggestTagsCallbackIsNull
```
- Setup: `editingBlockUuid = BlockUuid("uuid-1")`, `onSuggestTags = null`.
- Action: evaluate the derived lambda.
- Assert: derived lambda is `null`.

```
deriveOnSuggestTagsLambda_should_callOnSuggestTags_with_correctUuidAndContent_when_blockFound
```
- Happy path: `editingBlockUuid = BlockUuid("uuid-1")`, block list contains block with `uuid = "uuid-1"` and `content = "meeting notes"`. Callback captures `(uuid, content)` pair.
- Action: invoke the derived `() -> Unit`.
- Assert: callback received `("uuid-1", "meeting notes")`.

```
deriveOnSuggestTagsLambda_should_callOnSuggestTags_with_emptyContent_when_blockNotFound
```
- Error path: `editingBlockUuid = BlockUuid("uuid-99")`, block list does not contain uuid-99.
- Action: invoke the derived `() -> Unit`.
- Assert: callback received `("uuid-99", "")` — falls back to empty string, does not throw.

```
deriveOnSuggestTagsLambda_should_notCallRequestSuggestions_when_contentIsBlank
```
- Blank-content guard: `content = "   "` (whitespace only). The implementation should guard against calling `requestSuggestions` for blank content. Add the guard to the lambda derivation: `if (content.isNotBlank()) suggestFn(uuid, content)`.
- Assert: callback is never invoked.

> This is the blank-content guard called out in the task brief. The unit test drives the design decision to add `isNotBlank()` before forwarding.

---

File: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelRequestTest.kt`  
Package: `dev.stapler.stelekit.tags`

Extends `TagSuggestionViewModelTest` coverage — tests live alongside existing VM tests.

#### REQ-VM-1: `requestSuggestions` is not called for blank content (guard enforced at call site)

```
requestSuggestions_should_remainIdle_when_contentIsBlank
```
- Setup: standard `TagSuggestionViewModel` with idle engine.
- Action: call `requestSuggestions("block-1", "   ")` (whitespace-only string).
- Assert: `state.value` is still `TagSuggestionState.Idle` — the guard at the EditorToolbar level prevents the VM from receiving blank calls; this test pins the contract.

> This is an integration-style unit test confirming the guard is enforced by whichever layer owns it. If the VM itself also needs a guard (defense-in-depth), this test catches any regression.

---

### Part 2 — Compose / UI Tests (jvmTest)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbarSuggestTagsComposeTest.kt`  
Package: `dev.stapler.stelekit.ui.components`  
Framework: `androidx.compose.ui.test.junit4.createComposeRule`, JUnit 4

These tests use `ComposeTestRule` to render and interact with `MobileBlockToolbar` in isolation.

---

#### REQ-TOOLBAR-1 (Compose): Button visibility controlled by `onSuggestTags` nullability

```
MobileBlockToolbar_should_showSuggestTagsButton_when_onSuggestTagsIsNonNull
```
- Setup: render `MobileBlockToolbar` with `editingBlockId = "block-1"`, `onSuggestTags = {}`.
- Assert: `onNodeWithContentDescription("Suggest tags").assertIsDisplayed()`.

```
MobileBlockToolbar_should_hideSuggestTagsButton_when_onSuggestTagsIsNull
```
- Error path: render with `editingBlockId = "block-1"`, `onSuggestTags = null` (default).
- Assert: `onNodeWithContentDescription("Suggest tags").assertDoesNotExist()`.

```
MobileBlockToolbar_should_hideSuggestTagsButton_when_editingBlockIdIsNull
```
- Error path: render with `editingBlockId = null`, `onSuggestTags = { }` (non-null but no editing block).
- Assert: `onNodeWithContentDescription("Suggest tags").assertDoesNotExist()` — primary actions row is entirely hidden when not editing.

#### REQ-TOOLBAR-2 (Compose): Tapping invokes callback exactly once

```
MobileBlockToolbar_should_invokeOnSuggestTagsExactlyOnce_when_buttonTapped
```
- Setup: counter = 0; render with non-null `onSuggestTags = { counter++ }`.
- Action: `onNodeWithContentDescription("Suggest tags").performClick()`.
- Assert: counter == 1.

#### REQ-TOOLBAR-3 (Compose): Existing toolbar items unaffected

```
MobileBlockToolbar_should_preserveExistingPrimaryActions_when_onSuggestTagsIsNull
```
- Setup: render with `onSuggestTags = null`, `editingBlockId = "block-1"`.
- Assert: `onNodeWithContentDescription("Outdent").assertIsDisplayed()`, `onNodeWithContentDescription("Indent").assertIsDisplayed()`, `onNodeWithContentDescription("Insert wiki link").assertIsDisplayed()`.

```
MobileBlockToolbar_should_preserveExistingPrimaryActions_when_onSuggestTagsIsNonNull
```
- Setup: render with `onSuggestTags = {}`, `editingBlockId = "block-1"`.
- Assert: same three existing buttons still displayed alongside the new button.

#### REQ-TOOLBAR-4 (Compose): Button absent in selection mode

```
MobileBlockToolbar_should_hideSuggestTagsButton_when_isInSelectionMode
```
- Setup: render with `editingBlockId = "block-1"`, `onSuggestTags = {}`, `isInSelectionMode = true`.
- Assert: `onNodeWithContentDescription("Suggest tags").assertDoesNotExist()` — selection toolbar replaces primary row entirely.

---

### Part 3 — Screenshot Tests (jvmTest / Roborazzi)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/MobileBlockToolbarScreenshotTest.kt`  
Package: `dev.stapler.stelekit.ui.screenshots`  
Framework: Roborazzi via `captureRoboImage`

These catch visual regressions in button placement, icon rendering, and left/right-hand layout.

---

#### Screenshot baseline: toolbar with Suggest Tags button visible

```
mobileBlockToolbar_withSuggestTags_light
```
- Setup: render `MobileBlockToolbar` in a 400dp-wide `StelekitTheme(LIGHT)` box; `editingBlockId = "block-1"`, `onSuggestTags = {}`, all other optional callbacks `null`.
- Capture: `build/outputs/roborazzi/mobile_block_toolbar_suggest_tags_light.png`.

```
mobileBlockToolbar_withSuggestTags_dark
```
- Same setup, `StelekitTheme(DARK)`.
- Capture: `build/outputs/roborazzi/mobile_block_toolbar_suggest_tags_dark.png`.

```
mobileBlockToolbar_withSuggestTags_leftHanded
```
- Same light theme, `isLeftHanded = true`.
- Capture: `build/outputs/roborazzi/mobile_block_toolbar_suggest_tags_left_handed.png`.

#### Screenshot baseline: toolbar without Suggest Tags button (regression guard)

```
mobileBlockToolbar_withoutSuggestTags_unchanged
```
- Setup: `onSuggestTags = null` (default), `editingBlockId = "block-1"`.
- Capture: `build/outputs/roborazzi/mobile_block_toolbar_no_suggest_tags.png`.
- Purpose: verify existing layout is pixel-identical to pre-feature baseline when button is absent.

---

### Part 4 — Integration Tests (jvmTest)

File: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/EditorToolbarSuggestTagsIntegrationTest.kt`  
Package: `dev.stapler.stelekit.ui.components`  
Framework: `createComposeRule`, `BlockStateManager`, `InMemoryBlockRepository`

These tests exercise `EditorToolbar` with a real `BlockStateManager` to verify the end-to-end wiring from toolbar tap to callback invocation with the correct `(blockUuid, content)` pair.

---

#### REQ-WIRING-2: `EditorToolbar` forwards correct block identity and content to callback

```
EditorToolbar_should_passEditingBlockUuidAndContent_when_suggestTagsButtonTapped
```
- Happy path: construct `BlockStateManager` with `InMemoryBlockRepository` seeded with a block (`uuid = "uuid-a"`, `content = "project alpha"`). Set `editingBlockUuid` to `BlockUuid("uuid-a")`. Wire `onSuggestTags = { uuid, content -> captured = Pair(uuid, content) }`.
- Action: tap `onNodeWithContentDescription("Suggest tags")`.
- Assert: `captured == Pair("uuid-a", "project alpha")`.

```
EditorToolbar_should_passEmptyContent_when_editingBlockHasNoContent
```
- Error path: block exists with empty `content = ""`. Wire and tap.
- Assert: callback receives `("uuid-a", "")` — does not crash; blank guard may suppress the call (assert callback not invoked if blank guard is active, or receives empty string if guard only checks whitespace).

```
EditorToolbar_should_notShowSuggestTagsButton_when_onSuggestTagsIsNull
```
- Setup: `onSuggestTags = null` (default EditorToolbar parameter).
- Assert: `onNodeWithContentDescription("Suggest tags").assertDoesNotExist()`.

---

## Part 5 — UX Acceptance Tests

These map 1:1 to the 14 UX acceptance criteria. They are Compose UI behavioral tests using `ComposeTestRule`, living in:

`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbarUxAcceptanceTest.kt`

| AC | Test Name | Mechanism |
|----|-----------|-----------|
| **AC-01** | `ac01_should_showSuggestTagsButton_when_blockEditingAndVmNonNull` | Render `EditorToolbar` with non-null `onSuggestTags`; assert button exists. |
| **AC-02** | `ac02_should_hideSuggestTagsButton_when_isInSelectionMode` | `isInSelectionMode = true`; assert button does not exist. |
| **AC-03** | `ac03_should_hideSuggestTagsButton_when_noBlockBeingEdited` | `editingBlockId = null`; assert button does not exist. |
| **AC-04** | `ac04_should_notStealFocus_when_suggestTagsButtonTapped` | Focus is owned by the `Surface`'s `focusProperties { canFocus = false }` constraint; assert the composable renders without error when tapped and that `focusProperties` modifier is present on the `Surface`. (Structural check — full cursor-retention test requires a real TextField integration test.) |
| **AC-05** | `ac05_should_showSuggestTagsInLeftHandedGroup_when_isLeftHanded` | Render `MobileBlockToolbar` with `isLeftHanded = true`; assert "Suggest tags" button exists and is in the same row as "Outdent" (left group). Checked via semantic tree ordering. |
| **AC-06** | `ac06_should_invokeCallbackImmediately_when_suggestTagsButtonTapped` | Tap the button; assert the callback lambda was invoked synchronously within the same frame (variable captured before `waitForIdle()`). |
| **AC-07** | `ac07_should_haveCorrectTitle_when_suggestionBottomSheetShown` | Render `SuggestionBottomSheet` in `TagSuggestionState.Ready`; assert node with text `"Suggested tags for this block"` is displayed. |
| **AC-08** | `ac08_should_showCircularProgressIndicator_when_loadingStateAndNoLocalResults` | Render `SuggestionBottomSheet` in `TagSuggestionState.Loading`; assert `CircularProgressIndicator` semantic node exists. |
| **AC-09** | `ac09_should_showChipsAndInsertTag_when_suggestionsAvailableAndChipTapped` | Render sheet in `Ready` state with mock suggestions; tap first chip; assert `onAcceptTag` called with correct `(blockUuid, term)`. |
| **AC-10** | `ac10_should_showInlineSpinner_when_llmPendingAndLocalSuggestionsPresent` | Render sheet with `llmPending = true` and non-empty `displaySuggestions`; assert inline 16dp spinner is visible. |
| **AC-11** | `ac11_should_dismissSheetWithoutApplyingTags_when_closeTapped` | Render sheet; tap [✕]; assert `onDismiss` called once and no `onAcceptTag` calls. |
| **AC-12** | `ac12_should_keepSuggestTagsButtonVisible_when_sheetDismissed` | After `onDismiss` fires (state resets to `Idle`), assert "Suggest tags" button is still present in toolbar. |
| **AC-13** | `ac13_should_showLocalChipsAndLlmErrorLabel_when_llmFailedButLocalSuggestionsPresent` | Render sheet in `Ready` with `llmError = "Could not reach LLM"` and non-empty chips; assert error text and chips are both visible. |
| **AC-14** | `ac14_should_notOpenBottomSheet_when_fullSuggestionRequestFails` | Render screen with ViewModel in `Error` state (or `Idle`); assert bottom sheet node does not exist; assert "Suggest tags" button is still tappable (perform click; no crash). |

---

## Coverage Matrix

| Requirement / Story | Unit tests | Compose UI tests | Screenshot tests | Integration tests | UX AC tests |
|---------------------|-----------|-----------------|-----------------|------------------|------------|
| Story 1.1.1 — `onSuggestTags` param + icon button in `MobileBlockToolbar` | 5 | 6 | 4 | — | AC-01–05 |
| Story 1.1.2 — `EditorToolbar` lambda derivation | 5 | — | — | 3 | AC-06 |
| Story 1.1.3 — `JournalsView` wiring | (AC tests cover it end-to-end) | — | — | — | AC-06, AC-12 |
| Story 1.1.4 — `PageView` wiring | (same AC tests; topology identical) | — | — | — | AC-06, AC-12 |
| Story 1.1.5 — Dead `DropdownMenu` removal | compile-time (no `tagMenuExpanded` reference must remain) | assertDoesNotExist on any DropdownMenu for "Suggest tags" in `BlockItem` rendering | — | — | — |
| Blank-content guard | 1 (VM stays Idle), 1 (lambda returns null content) | — | — | 1 (empty content path) | — |
| SuggestionBottomSheet states | — | — | — | — | AC-07–14 |

---

## Summary

| Category | Count |
|----------|-------|
| Pure logic unit tests (businessTest) | 6 |
| Compose UI component tests (jvmTest) | 8 |
| Screenshot regression tests (jvmTest / Roborazzi) | 4 |
| Integration tests — EditorToolbar wiring (jvmTest) | 3 |
| UX acceptance tests (jvmTest) | 14 |
| **Total** | **35** |

**Requirements coverage**: 5 / 5 stories covered (100%). All in-scope items from `requirements.md` have at least one happy-path test and one error/edge-path test. The blank-content guard is pinned by both a unit test and an integration test.

**UX acceptance criteria**: 14 / 14 covered (100%).

**No migration tests** — this is a UI-only change with no schema or data migrations.
