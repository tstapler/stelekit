# Build-vs-buy: dead editor.* packages vs live equivalents

Source: grep/ls/git log on branch `backlog/stelekit-editor-subsystem-dead-code` (paths under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`, abbreviated `K/`).

## History (why they were left)
- All dead packages except `persistence/`+`blocks/` originate in `9b08f169 feat: initial SteleKit release`; they were never wired to the UI. `persistence/`, `text/`, `blocks/`, `viewmodel/` were only touched afterwards by repo-wide refactors (UUID value classes `0b1d9dcf`, type-driven `18985fd0`, fractional positions `4d843be3`) that kept them compiling.
- `3b1a7aa4 chore(quality): remove dead code ... (#210)` only trimmed unused imports/members inside them; it did not delete the packages.
- PR #208 (`afc94fd3 feat(editor): rich editing experience overhaul`) message is SDD planning artifacts + the live-editor work; it does not mention these packages. The live editor grew in `ui/` instead, and the old stack was bypassed rather than replaced in one step. (INFERRED: no commit states an intent to delete.)

## Per package

| Dead package | Live equivalent | Behaviour with no live equivalent | Verdict |
|---|---|---|---|
| `persistence/` (8 files, PersistenceManager 903 lines) | `K/db/GraphWriter.kt` (+`GraphWriterPort`), `K/db/DatabaseWriteActor.kt`, `K/db/DiskConflictBlockMatcher.kt`, `K/db/GraphLoader.kt` externalFileChanges/DiskConflict flow, `K/ui/state/BlockStateManager.kt` 500ms debounce | Generic queue/retry/optimistic-sync/`ConflictResolution` strategies and `PersistenceOptimizations` batching. Live app resolves conflicts by UI DiskConflict flow, not pluggable strategies. | Delete (superseded) |
| `text/` (5 files) | `K/ui/components/BlockEditor.kt` (TextFieldValue + IME composition guard), `FormatAction` enum in `K/ui/screens/JournalsViewModel.kt:26` (applied via BlockRenderer/BlockItem/MobileBlockToolbar), `K/ui/state/BlockStateManager.kt` | Word-boundary navigation, `OptimizedTextOperations` cached ops, rope-like undo of text. Live uses Compose TextFieldValue. | Delete (superseded) |
| `state/EditorState.kt` | `AppState` (`K/ui/AppState.kt`), `BlockStateManager` StateFlows, `K/ui/theme/`, `ui/components/EditorCapabilities.kt` | EditorConfig themes (Gruvbox/Sepia), vim/emacs `KeyboardShortcuts`, double-tap/long-press behaviour config. Not exposed in live UI. | Delete (superseded; keybinding presets are informational) |
| `input/InputHandling.kt` | `onPreviewKeyEvent` handling in `BlockEditor.kt`, `BlockList.kt`, `AutocompleteMenu.kt`; IME composition guard in `BlockEditor.kt:66`; `ui/FirstRunHotkeyNotice.kt` | Stand-alone `ImeCompositionManager` state machine + `ImeCompositionIndicator` composable. Live app only guards composition inline. | Delete (superseded) |
| `blocks/BlockTreeOperations.kt` (also BlockOperations/IBlockOperations) | `BlockStateManager` indent/outdent/moveUp/moveDown (L1249-1285), `moveSelectedBlocks` (L464), `BlockStructureRepository`, `command/BlockCommands.kt`, `ui/state/BlockUndoManager.kt`, `db/UndoManager.kt` | `getSubtree`, `findCommonAncestor`, `getAncestorPath`, `duplicateSubtree`, `getSubtreeMaxDepth`, `BlockOperationValidator` (cycle/depth validation). No live callers of those names outside `editor/` (grep). | Delete (superseded; subtree duplicate/ancestor path are latent features) |
| `viewmodel/EditorViewModel.kt` | `K/ui/StelekitViewModel.kt` + `BlockStateManager`, `BlockUndoManager` (undo/redo L552-553) | In-editor find/replace (`findNext`, `replaceAll`, `showFindReplace`) and `getEditorStatistics()`. Live app has search, no in-block find/replace. | Delete (superseded) |

## Conclusion
No dead package is the sole owner of behaviour the app ships today; every job the dead code was built for is done in `ui/state/`, `ui/components/`, and `db/`. Keeping+testing would pin ~11k lines of un-integrated, un-reviewed design (the live code diverged: UUID value classes, DatabaseWriteActor serialization) and the tests would validate behaviour no user can reach.

## Concepts the live app lacks (informational only)
- In-block find/replace (EditorViewModel).
- Subtree duplicate / ancestor-path / common-ancestor helpers (BlockTreeOperations).
- User-selectable vim/emacs keyboard presets and editor themes (EditorState).
- Pluggable conflict-resolution strategies / retry queue for writes (persistence).
Recoverable from git history (`3b1a7aa4^`) if ever wanted.

## Gaps
- "No live caller" was established by name-grep, not by compiler/JaCoCo; a final `bazel build`/`compileKotlinJvm` after deletion is the proof.
