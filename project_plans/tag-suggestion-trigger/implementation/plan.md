# Implementation Plan: tag-suggestion-trigger

**Feature**: Add "Suggest tags" button to MobileBlockToolbar to trigger per-block tag suggestions
**Date**: 2026-06-22
**Status**: Ready for implementation
**ADRs**: None

---

## Domain Glossary
| Term | Definition | Notes |
|------|-----------|-------|
| Tag suggestion | An automated or LLM-assisted recommendation for a `[[wiki-link]]` tag to insert into a block | Produced by `TagSuggestionEngine` via `TagSuggestionViewModel` |
| `onSuggestTags` callback | A lambda `() -> Unit` passed to `MobileBlockToolbar` that fires when the user taps "Suggest tags" | Reads block content at call time, not at composition time |
| `MobileBlockToolbar` | The floating bottom toolbar rendered while a block is being edited on mobile | Lives in `ui/components/MobileBlockToolbar.kt` |
| `EditorToolbar` | Wrapper composable that wires `MobileBlockToolbar` to `BlockStateManager` + feature callbacks | Single wiring site used by both `JournalsView` and `PageView` |
| `TagSuggestionViewModel` | Owns the suggestion state machine; exposes `requestSuggestions(blockUuid, content)` | Already present in both screens |
| `SuggestionBottomSheet` | Modal bottom sheet that shows local + LLM suggestions | Already rendered in both screens when `tagSuggestionViewModel != null` |
| Dead `DropdownMenu` | The `Box { DropdownMenu { ... } }` block in `BlockItem.kt` lines 499–516 that is never opened | To be deleted; superseded by the toolbar button |
| Primary actions row | The top row of `MobileBlockToolbar` containing Outdent / Indent / `[[]]` / AttachImage / CaptureImage | Where the new button is placed, guarded by `if (onSuggestTags != null)` |

---

## Pattern Decisions
| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Toolbar parameter type | `onSuggestTags: (() -> Unit)?` (nullable callback) | Existing `onLinkPicker`/`onAttachImage` pattern in `MobileBlockToolbar` | Non-null with no-op default | Nullable signals "feature unavailable"; absent button rather than disabled button, consistent with the rest of the toolbar |
| Content read timing | Read `blockStateManager.blocks.value` inside `onClick` lambda | Research pitfalls note | Capture content at composition time | Avoids stale content and unnecessary recompositions when block text changes |
| EditorToolbar wiring | Pass `onSuggestTags: ((blockUuid: String, content: String) -> Unit)?` param; derive `editingBlockUuid` + content inline | Exact link-picker wiring pattern | Pass `tagSuggestionViewModel` directly into `EditorToolbar` | Keeps `EditorToolbar` decoupled from `TagSuggestionViewModel` per requirements constraint |
| Icon choice | `Icons.Default.Label` | Verified grep: found in `AnnotationToolbar.kt:125` and `SettingsDialog.kt:253`; `Icons.AutoMirrored.Filled.Label` does NOT exist | `Icons.Default.AutoAwesome` | "Label" semantics match tagging; AutoAwesome implies generic AI, less discoverable |
| Dead code removal | Delete `BlockItem.kt` lines 499–516 outright | Requirements scope | Leave in place | Dead UI code with no anchor is unreachable; removing eliminates confusion and dead state variable |
| Button placement | Primary actions row (same row as `[[]]`, guarded by `if (onSuggestTags != null)`) | Research: matches `onAttachImage` guard pattern | Second row with undo/redo | Primary actions row is the editing-context row; tagging is an editing action |

---

## Observability Plan
- **Logs**: No additional logging required; `TagSuggestionViewModel.requestSuggestions` already logs internally.
- **Metrics**: None — this is a UI wiring change, not a new data path.
- **Alerts**: None.

## Risk Control
- **Feature flag**: None required — `tagSuggestionViewModel` being `null` already acts as a compile-time-safe off-switch. The button is invisible when the VM is absent.
- **Rollback procedure**: Revert the four file changes; no database or schema impact.
- **Staged rollout**: Not required for a UI wiring bug fix.

## Unresolved Questions
None.

## Dependency Visualization

```
JournalsView / PageView
       |
       |  passes onSuggestTags lambda (captures tagSuggestionViewModel)
       v
  EditorToolbar
       |
       |  reads editingBlockUuid + blocks at click time; forwards () -> Unit
       v
  MobileBlockToolbar
       |
       |  renders IconButton (visible only when onSuggestTags != null)
       v
  [user taps button]
       |
       v
  TagSuggestionViewModel.requestSuggestions(blockUuid, content)
       |
       v
  SuggestionBottomSheet  (already rendered in both screens)
```

---

## Phase 1: Wire tag suggestion toolbar button

### Epic 1.1: Per-block "Suggest tags" button in MobileBlockToolbar
**Goal**: Surface the existing `TagSuggestionViewModel.requestSuggestions` path via a tappable icon button in the editing toolbar, consistent with the link-picker and attach-image patterns already present.

---

#### Story 1.1.1: Add `onSuggestTags` parameter and icon button to `MobileBlockToolbar`
**As a** user editing a block on mobile, **I want** a "Suggest tags" button in the block editing toolbar, **so that** I can invoke AI tag suggestions without hunting through overflow menus.

**Acceptance Criteria**:
- The button appears in the primary actions row when `onSuggestTags != null` and `editingBlockId != null`.
  - *Given* a `MobileBlockToolbar` with `onSuggestTags = { ... }` and `editingBlockId = "abc"`, *When* the composable renders, *Then* an `IconButton` with `contentDescription = "Suggest tags"` is present in the primary actions row.
- The button is absent when `onSuggestTags` is `null`.
  - *Given* `onSuggestTags = null`, *When* the composable renders, *Then* no "Suggest tags" icon button appears.
- Tapping the button invokes the lambda exactly once.
  - *Given* a callback that increments a counter, *When* the button is tapped, *Then* the counter equals 1.
- Existing toolbar items (Outdent, Indent, `[[]]`, AttachImage, CaptureImage) are unaffected.
  - *Given* all existing params unchanged, *When* `onSuggestTags = null`, *Then* the toolbar renders identically to baseline.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

##### Task 1.1.1a: Add `onSuggestTags: (() -> Unit)?` parameter and import (~3 min)
- Add `onSuggestTags: (() -> Unit)? = null` to the `MobileBlockToolbar` parameter list after `onCaptureImage`.
- Add import: `import androidx.compose.material.icons.filled.Label`
- Files: `MobileBlockToolbar.kt`

##### Task 1.1.1b: Render the icon button in the primary actions row (~3 min)
- Inside the `primaryActions` lambda, AFTER the `[[]]` / link-picker `TextButton` block and BEFORE the `if (onAttachImage != null)` block (BLOCKER C-01 fix: before attach-image, not after), add:
  ```kotlin
  if (onSuggestTags != null) {
      IconButton(
          onClick = onSuggestTags,
          modifier = Modifier.semantics { contentDescription = "Suggest tags" }
      ) {
          Icon(Icons.Default.Label, contentDescription = null)
      }
  }
  ```
- Files: `MobileBlockToolbar.kt`

---

#### Story 1.1.2: Add `onSuggestTags` wiring to `EditorToolbar`
**As a** developer, **I want** `EditorToolbar` to accept `onSuggestTags: ((blockUuid: String, content: String) -> Unit)?` and forward a no-arg lambda to `MobileBlockToolbar`, **so that** call sites only need to pass the VM callback without knowing toolbar internals.

**Acceptance Criteria**:
- `EditorToolbar` accepts `onSuggestTags: ((blockUuid: String, content: String) -> Unit)?` (default `null`).
  - *Given* a call site that omits the parameter, *When* `EditorToolbar` compiles, *Then* no compile error occurs.
- When `onSuggestTags` is non-null and `editingBlockUuid` is non-null, a no-arg lambda is derived that reads block content from `allBlocks` at invocation time and calls `onSuggestTags(uuid, content)`.
  - *Given* `editingBlockUuid = BlockUuid("uuid-1")` and `allBlocks` containing a block with uuid `"uuid-1"` and content `"hello"`, *When* the derived lambda is invoked, *Then* `onSuggestTags("uuid-1", "hello")` is called.
- When `editingBlockUuid` is `null`, the derived lambda passed to `MobileBlockToolbar` is `null`.
  - *Given* no block is being edited, *When* `EditorToolbar` computes the toolbar lambda, *Then* `onSuggestTags` passed to `MobileBlockToolbar` is `null` (button hidden).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorToolbar.kt`

##### Task 1.1.2a: Add `onSuggestTags` parameter to `EditorToolbar` (~2 min)
- Add `onSuggestTags: ((blockUuid: String, content: String) -> Unit)? = null` to `EditorToolbar`'s parameter list.
- Files: `EditorToolbar.kt`

##### Task 1.1.2b: Derive and forward the no-arg lambda to `MobileBlockToolbar` (~4 min)
- After the `onAttachImage` derivation block in `EditorToolbar`, add:
  ```kotlin
  onSuggestTags = run {
      val suggestFn = onSuggestTags
      val targetUuid = editingBlockUuid
      if (suggestFn != null && targetUuid != null) {
          {
              val block = allBlocks.values.flatten().find { it.uuid == targetUuid }
              val content = block?.content ?: ""
              suggestFn(targetUuid.value, content)
          }
      } else null
  },
  ```
- Pass `onSuggestTags = <derived>` in the `MobileBlockToolbar(...)` call.
- Files: `EditorToolbar.kt`

---

#### Story 1.1.3: Wire `onSuggestTags` in `JournalsView`
**As a** user on the Journals screen, **I want** tapping "Suggest tags" in the toolbar to trigger suggestions for the block I am currently editing, **so that** the `SuggestionBottomSheet` appears with relevant suggestions.

**Acceptance Criteria**:
- `EditorToolbar` in `JournalsView` receives `onSuggestTags` that calls `tagSuggestionViewModel.requestSuggestions(blockUuid, content)`.
  - *Given* `tagSuggestionViewModel` is non-null and block `"uuid-2"` is being edited with content `"#meeting"`, *When* the user taps "Suggest tags", *Then* `tagSuggestionViewModel.requestSuggestions("uuid-2", "#meeting")` is called and `SuggestionBottomSheet` becomes visible.
- When `tagSuggestionViewModel` is `null`, `onSuggestTags` is `null` (no button shown).
  - *Given* `tagSuggestionViewModel = null`, *When* `EditorToolbar` renders, *Then* `onSuggestTags = null` is passed and the button is invisible.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt`

##### Task 1.1.3a: Pass `onSuggestTags` to `EditorToolbar` in `JournalsView` (~3 min)
- Locate the `EditorToolbar(...)` call at line ~252 in `JournalsView.kt`.
- Add the parameter (with `isNotBlank` guard and 3-arg `requestSuggestions` to match architecture concern fixes):
  ```kotlin
  onSuggestTags = if (tagSuggestionViewModel != null) { blockUuid, content ->
      if (content.isNotBlank()) {
          val alreadyLinked = WikiLinkExtractor.extractPageNames(content)
          tagSuggestionViewModel.requestSuggestions(
              blockUuid = blockUuid,
              blockContent = content,
              alreadyLinkedTerms = alreadyLinked,
          )
      }
  } else null,
  ```
- Remove `onRequestTagSuggestions = if (tagSuggestionViewModel != null) { ... } else null` from the `BlockList` call inside `JournalEntry` (lines ~190–197).
- Files: `JournalsView.kt`

---

#### Story 1.1.4: Wire `onSuggestTags` in `PageView`
**As a** user on a Page view, **I want** the same "Suggest tags" toolbar button available while editing blocks, **so that** the experience is consistent with the Journals screen.

**Acceptance Criteria**:
- `EditorToolbar` in `PageView` receives `onSuggestTags` that calls `tagSuggestionViewModel.requestSuggestions(blockUuid, content)`.
  - *Given* `tagSuggestionViewModel` is non-null and block `"uuid-3"` is being edited with content `"project alpha"`, *When* the user taps "Suggest tags", *Then* `tagSuggestionViewModel.requestSuggestions("uuid-3", "project alpha")` is called.
- When `tagSuggestionViewModel` is `null`, no button is shown.
  - *Given* `tagSuggestionViewModel = null`, *When* `EditorToolbar` renders in `PageView`, *Then* the "Suggest tags" button is absent.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

##### Task 1.1.4a: Pass `onSuggestTags` to `EditorToolbar` in `PageView` (~3 min)
- Locate the `EditorToolbar(...)` call at line ~428 in `PageView.kt`.
- Add the parameter (same shape as Story 1.1.3, with `isNotBlank` guard and 3-arg `requestSuggestions`):
  ```kotlin
  onSuggestTags = if (tagSuggestionViewModel != null) { blockUuid, content ->
      if (content.isNotBlank()) {
          val alreadyLinked = WikiLinkExtractor.extractPageNames(content)
          tagSuggestionViewModel.requestSuggestions(
              blockUuid = blockUuid,
              blockContent = content,
              alreadyLinkedTerms = alreadyLinked,
          )
      }
  } else null,
  ```
- Remove `onRequestTagSuggestions = if (tagSuggestionViewModel != null) { ... } else null` from the `BlockList` call (lines ~361–368).
- Files: `PageView.kt`

---

#### Story 1.1.5: Remove dead `onRequestTagSuggestions` parameter chain (FM-1 P1 scope fix)
**As a** developer, **I want** the dead `onRequestTagSuggestions` parameter chain fully removed from 5 files, **so that** the codebase has a single, discoverable trigger surface with no dead pass-through state.

**Acceptance Criteria**:
- Dead `DropdownMenu` block (lines 499–516 in `BlockItem.kt`) is deleted.
- `onRequestTagSuggestions` parameter is removed from `BlockItem`, `BlockRenderer`, `BlockList`, `JournalEntry` (private composable in `JournalsView.kt`), `JournalsView` (from the `BlockList` call), and `PageView` (from the `BlockList` call).
- Verified: right-click context menu path (`SuggestionContextMenu` triggered by `contextMenuState` around lines 469–497 in `BlockItem.kt`) is INDEPENDENT — it does NOT use `onRequestTagSuggestions`. Removing the chain is safe.

**Files**: `BlockItem.kt`, `BlockRenderer.kt`, `BlockList.kt`, `JournalsView.kt`, `PageView.kt`

##### Task 1.1.5a: Delete dead DropdownMenu block in `BlockItem.kt` and remove its parameter (~5 min)
- Delete the block at lines 499–516:
  ```kotlin
  // Render "Suggest tags" dropdown (shown when onRequestTagSuggestions is wired)
  if (onRequestTagSuggestions != null) {
      var tagMenuExpanded by remember { mutableStateOf(false) }
      Box {
          DropdownMenu(
              expanded = tagMenuExpanded,
              onDismissRequest = { tagMenuExpanded = false },
          ) {
              DropdownMenuItem(
                  text = { Text("Suggest tags") },
                  onClick = {
                      tagMenuExpanded = false
                      onRequestTagSuggestions(block.uuid.value, block.content)
                  },
              )
          }
      }
  }
  ```
- Remove the `onRequestTagSuggestions: ((blockUuid: String, content: String) -> Unit)? = null` parameter from `BlockItem` (line ~92).
- Files: `BlockItem.kt`

##### Task 1.1.5b: Remove `onRequestTagSuggestions` from `BlockRenderer.kt` (~2 min)
- Remove the `onRequestTagSuggestions: ((blockUuid: String, content: String) -> Unit)? = null` parameter (line ~78).
- Remove `onRequestTagSuggestions = onRequestTagSuggestions` from the `BlockItem(...)` call (line ~125).
- Files: `BlockRenderer.kt`

##### Task 1.1.5c: Remove `onRequestTagSuggestions` from `BlockList.kt` (~2 min)
- Remove the `onRequestTagSuggestions: ((blockUuid: String, content: String) -> Unit)? = null` parameter and its doc comment (lines ~92–94).
- Remove `onRequestTagSuggestions = onRequestTagSuggestions` from the `BlockRenderer(...)` call (line ~249).
- Files: `BlockList.kt`
