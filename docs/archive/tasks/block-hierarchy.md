# Feature Plan: Block Hierarchy & Outliner

## 1. Epic Overview

### User Value
The core of Logseq is its outliner capability. Users need to structure their thoughts hierarchically using indentation. This feature enables the fundamental "thinking in blocks" workflow where ideas can be nested, reordered, and organized visually.

### Success Metrics
- **Interaction**: Tab indents a block, Shift+Tab outdents a block.
- **Visuals**: Nested blocks are rendered with correct indentation (e.g., 24dp per level).
- **Data Integrity**: Parent/Child relationships are correctly persisted to DB and File.
- **Performance**: Indent/Outdent operations feel instantaneous (< 50ms).

### Scope
- **In Scope**:
    - Indent (Tab) / Outdent (Shift+Tab).
    - Move Up (Alt+Up) / Move Down (Alt+Down).
    - Collapse / Expand (Clicking bullet).
    - Drag and Drop (Reordering).
- **Out of Scope**:
    - Advanced multi-select operations (for now).
    - Zooming into blocks (Hoisting).

## 2. Architecture Decisions

### ADR-001: Tree Operations in Domain Layer
- **Context**: Tree manipulations (indent/outdent) involve complex logic (finding new parent, reordering siblings, updating levels).
- **Decision**: Encapsulate this logic in `TreeOperations` (pure functions) and `BlockRepository` (state updates).
- **Rationale**: Keeps UI dumb and testable.
- **Status**: `TreeOperations` and `DatascriptBlockRepository` implementations exist.

### ADR-002: Key Handling in UI
- **Context**: `BasicTextField` consumes keys. We need to intercept Tab/Shift+Tab.
- **Decision**: Use `Modifier.onPreviewKeyEvent` on the `BasicTextField` in `BlockRenderer`.
- **Rationale**: Allows capturing keys before the text field handles them (e.g., inserting a tab character).

## 3. Story Breakdown

### Story 1: Basic Hierarchy Manipulation [1 week]
**User Value**: I can organize my blocks using keyboard shortcuts.
**Acceptance Criteria**:
- Tab indents the current block (if it has a preceding sibling).
- Shift+Tab outdents the current block (if it has a parent).
- Alt+Up/Down moves the block vertically.

### Story 2: Visual Feedback & Drag-and-Drop [1 week]
**User Value**: I can intuitively rearrange blocks with the mouse.
**Acceptance Criteria**:
- Drag handle appears on hover.
- Drop targets are highlighted.
- Collapse/Expand state is preserved.

## 4. Atomic Task Decomposition

### Story 1: Basic Hierarchy Manipulation

#### Task 1.1: ViewModel & Repository Integration [1h]
- **Objective**: Expose `indentBlock`, `outdentBlock`, `moveBlockUp`, `moveBlockDown` in `LogseqViewModel`.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/BlockRepository.kt`
- **Implementation**:
    - Add suspend functions to ViewModel that delegate to Repository.
    - Handle errors/logging.

#### Task 1.2: Key Binding Implementation [2h]
- **Objective**: Bind Tab, Shift+Tab, Alt+Up, Alt+Down in `BlockRenderer`.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/JournalsView.kt` (Pass callbacks)
- **Implementation**:
    - Add `onIndent`, `onOutdent`, `onMoveUp`, `onMoveDown` callbacks to `BlockRenderer`.
    - Use `Modifier.onPreviewKeyEvent` to detect keys.
    - Return `true` to consume the event.

#### Task 1.3: Mobile Block Toolbar [2h]
- **Objective**: Provide on-screen controls for indentation and reordering (Essential for Android).
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/MobileToolbar.kt` (New)
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/screens/JournalsView.kt`
- **Implementation**:
    - Create a row of icon buttons: [Indent] [Outdent] [Move Up] [Move Down].
    - Show this toolbar above the soft keyboard when a block is focused.
    - Connect to `LogseqViewModel` block operations.
    - *See [docs/tasks/android-readiness.md](docs/tasks/android-readiness.md) for more details.*

### Story 2: Visual Feedback

#### Task 2.1: Tree Visualization [2h]
- **Objective**: Ensure nested blocks render with correct indentation and connecting lines (optional).
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
- **Implementation**:
    - Verify `padding(start = (block.level * 24).dp)` logic.
    - Add visual guides (vertical lines) if needed.

#### Task 2.2: Drag and Drop Support [3h]
- **Objective**: Implement drag-and-drop reordering.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockList.kt`
- **Implementation**:
    - Use Compose DragAndDrop APIs.
    - Update `position` and `parentId` on drop.

## 5. Dependency Visualization

```
[Task 1.1: ViewModel] --> [Task 1.2: Key Bindings]
       ^
       |
(Existing Repository Logic)
```

## 6. Context Preparation Guide
- **Files**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`
- **Concepts**:
    - Compose Key Events (`onPreviewKeyEvent`).
    - Logseq Tree Structure (Parent/Child/Sibling).
