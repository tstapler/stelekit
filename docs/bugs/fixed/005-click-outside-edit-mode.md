## 🐛 BUG-005: Clicking outside edit box doesn't cancel edit mode

**Status**: ✅ Fixed
**Discovered**: Feb 1, 2026
**Fix Date**: Feb 1, 2026

**Description**:
Users reported that clicking outside the active text editor (block) did not exit edit mode. The cursor remained active in the text field.

**Root Cause**:
The application lacked a global touch/click handler to clear focus when tapping on empty space or non-interactive elements. In Compose, focus is not automatically cleared when clicking "nothing" unless explicitly handled.

**Fix**:
Added a `pointerInput` modifier with `detectTapGestures` to the root `Box` in `App.kt`. This handler calls `LocalFocusManager.current.clearFocus()` on any tap event that isn't consumed by a child component.

**Files Changed**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/App.kt`
