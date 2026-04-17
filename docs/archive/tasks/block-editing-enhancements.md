### Task 1.4: Block Editing Enhancements (3h)

**Scope**: Implement missing core editing interactions (Enter, Backspace, Navigation).

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/editor/Editor.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/editor/IEditor.kt`

**Context**:
- Current implementation handles text editing but lacks structural operations via keyboard.
- Users expect "Enter" to create a new sibling block.
- Users expect "Backspace" at the start of a block to merge it with the previous block.
- Users expect "Tab" to indent (already partially implemented but needs verification).

**Implementation**:
```kotlin
// In BlockRenderer.kt onKeyEvent
Key.Enter -> {
    if (event.isShiftPressed) {
        // Soft line break (insert \n)
        false // Let TextField handle it? Or insert manually.
    } else {
        onNewBlock() // Callback to Editor.createNewBlock()
        true
    }
}
Key.Backspace -> {
    if (cursorPosition == 0 && selection.collapsed) {
        onMergeWithPrevious()
        true
    } else {
        false
    }
}
```

**Success Criteria**:
- [ ] Pressing Enter creates a new block below the current one.
- [ ] Pressing Enter on a non-empty block splits it (optional for now, but standard behavior).
- [ ] Pressing Backspace at start of block merges content into previous block.
- [ ] Focus is correctly managed after structural changes.

**Testing**:
- Unit tests in `EditorViewModelTest` or `EditorTest`.
- Manual verification of focus transitions.

**Dependencies**:
- Task 1.1 (Persistence) - Completed.
- Task 1.2 (Auto-save) - Completed.

**Status**: ⏳ Pending
