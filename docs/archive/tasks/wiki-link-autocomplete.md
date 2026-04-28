# Wiki Link Autocomplete Implementation Plan

## Objective
Implement an inline autocomplete popup that appears when the user types `[[` in the block editor. This popup should allow searching for and selecting pages, inserting `[[Page Name]]` into the text.

## Context
Logseq KMP currently supports `[[Wiki Links]]` for navigation in View Mode, but creating them in Edit Mode requires manual typing. The CLJS version provides an autocomplete dropdown.
We have `SearchViewModel` and `SearchRepository` which can be reused for querying pages.
The editor is built with `RichTextEditor` (wrapping `BasicTextField`).

## Scope (Atomic)
- **Trigger**: Detect `[[` sequence at cursor position.
- **UI**: Display a popup menu anchored to the cursor (or approximate position).
- **Filtering**: Filter pages based on text typed after `[[`.
- **Selection**: Insert `[[Selected Page]]` on click/enter.

## Prerequisites
- [x] Search infrastructure (`SearchRepository`)
- [x] Text editor infrastructure (`RichTextEditor`)

## Atomic Steps

### 1. Trigger Detection (1h)
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/editor/components/RichTextEditor.kt`
- **Task**: Implement logic in `onValueChange` to detect if the cursor is preceded by `[[` and capture the search query (text after `[[`).
- **State**: Expose `autocompleteState` (isVisible, query, cursorPosition) to parent or handle internally.

### 2. Autocomplete UI (2h)
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/editor/components/AutocompleteMenu.kt` (Create)
- **Task**: Create a `Popup` or `Dialog` that displays a list of pages.
- **Logic**: Use `SearchRepository` (via ViewModel or direct injection) to fetch results matching the query.
- **Styling**: Match existing `SearchDialog` style but compact.

### 3. Integration (1h)
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
- **Task**: Integrate `AutocompleteMenu` into the editor layout.
- **Wiring**: Connect the trigger detection from Step 1 to the UI from Step 2. Handle selection event to replace `[[query` with `[[Page Name]]`.

## Validation Strategy
1. Type `[[` -> Popup appears.
2. Type `[[Log` -> Popup filters to "Logseq", "Logic", etc.
3. Select "Logseq" -> Text becomes `[[Logseq]]` and popup closes.
4. Backspace to delete `[[` -> Popup closes.
