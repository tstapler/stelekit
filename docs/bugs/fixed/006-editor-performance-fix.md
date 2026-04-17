## 🐛 BUG-006: Editor Typing Performance / Cursor Jumping

**Status**: ✅ Fixed
**Discovered**: Feb 1, 2026
**Fix Date**: Feb 1, 2026

**Description**:
Users reported sluggish rendering when typing fast, and the cursor jumping to the beginning of the text field.

**Root Cause**:
1. **Performance**: Every keystroke triggered a database write (`blockRepository.saveBlock`), which emitted a new state, causing the entire `PageView` and all `BlockRenderer`s to recompose.
2. **Cursor Jump**: The `BasicTextField` state was being re-initialized on every external update because `block.content` was used as a `remember` key. When the round-trip update came back from the DB, it reset the text field, losing the cursor position if it differed slightly in timing.

**Fix**:
1. **Debouncing**: Implemented `DebounceManager` in `LogseqViewModel`. Text updates are now debounced (300ms) before writing to the repository. This keeps the UI responsive as it relies on local state while typing.
2. **State Management**: Updated `BlockRenderer` to remove `block.content` from `remember` keys. Added a `LaunchedEffect` to only update local state from external `block.content` if the content *actually* differs, preserving cursor position during normal typing.

**Files Changed**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/performance/DebounceManager.kt` (New)
