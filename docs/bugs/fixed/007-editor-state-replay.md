## 🐛 BUG-007: Editor Content Replay / Typing Wiped [SEVERITY: High]

**Status**: ✅ Fixed (Feb 24, 2026)
**Discovered**: Feb 22, 2026 during manual testing of Page Aliases
**Impact**: Typing in a block is occasionally wiped out or "replays" older content, leading to data loss during the editing session.

**Root Cause**:
A race condition exists between the local `textFieldValue` in `BlockRenderer` and the debounced repository updates in `LogseqViewModel`.
1. User types "abc". `onContentChange("abc")` is called.
2. `LogseqViewModel` starts a 300ms debounce timer for "abc".
3. While the database write is in progress, the user types "abcd". Local state is "abcd".
4. Database write for "abc" finishes and the repository emits the new state ("abc").
5. `BlockRenderer` receives the update where `block.content` is "abc".
6. `BlockRenderer`'s `LaunchedEffect` sees that the incoming content differs from local state and resets the local `textFieldValue`, wiping out the "d".

**Fix: Version-Based Synchronization (The "Higher Version Wins" Pattern)**:
Instead of comparing raw strings (which is prone to race conditions and expensive for large blocks), we implemented a versioning system:
1. **Model Update**: Added a `version: Long` field to both `Block` and `Page` models.
2. **Database Update**: Added a `version` column to the SQLDelight schema.
3. **UI Logic**: When the user types, the `BlockRenderer` increments its `localVersion` and sends the new content + version to the ViewModel.
4. **Sync Decision**: The UI only applies incoming repository updates if `block.version > localVersion`. This ensures that "stale" round-trips (where the DB is catching up to a previous keystroke) are ignored.

**Files Affected**:
- `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` (Schema update)
- `shared/src/commonMain/kotlin/logseq/model/Block.kt` (Model update)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt` (UI Model update)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt` (Sync logic update)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt` (Persistence update)

**Verification**:
1. Type quickly and ensure no characters are lost.
2. Verify that Undo/Redo (which increments version in the DB) still correctly updates the editor content.
3. Verify that changes from other components (like global search/replace) are correctly reflected in the editor.
