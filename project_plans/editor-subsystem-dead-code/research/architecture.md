# Research: editor.* subsystem architecture and dead-code confirmation

**Date**: 2026-10-13

## What the editor/ package was

`editor/` was an alternative, full-fat editor subsystem introduced before PR #208. It modelled
a complete editing layer — state (`EditorState`), text operations (`TextOperations`,
`OptimizedTextOperations`), block tree manipulation (`BlockOperations`, `BlockTreeOperations`),
input handling (`InputHandling`), persistence (`PersistenceManager`, `PersistenceIntegration`,
`PersistenceOptimizations`, `ConflictResolution`), and a top-level orchestrator (`Editor.kt`).

## Why it is dead

PR #208 ("feat(editor): rich editing experience overhaul") made an explicit architectural
decision (ADR-001: "Extend live FormatAction system, not orphaned editor/ framework") to
continue building on `BlockStateManager` / `BlockEditor` / `FormatAction` instead of the
`editor/` infrastructure. The PR commit message explicitly says:

> "Extends the live FormatAction system (rather than the orphaned editor/ command framework)…
>  Deletes the confirmed-unreachable slice of the editor/ command \[framework\]"

The deletion was **incomplete**: the commit removed some dead code but left the six subpackages
identified in the backlog item intact.

## Dependency graph (confirmed by full-repo grep)

```
External callers (outside editor/)
  └─ editor.commands  ← StelekitViewModel.kt, CommandManagerCoordinator.kt
        (no imports from any other editor.* subpackage)

DEAD — no external callers, no transitive path from app entry points:
  editor.Editor (imports editor.commands.*, editor.text.*, editor.state.*, editor.blocks.*)
  editor.IEditor
  editor.EditorModels
  editor.format (FormatModels, IFormatProcessor — only referenced from IEditor.kt)
  editor.state (EditorState, EditorConfig, EditorMode — only referenced from Editor.kt)
  editor.text (ITextOperations, TextOperations, OptimizedTextOperations, DefaultTextOperations,
               TextModels — only referenced from Editor.kt and editor.state)
  editor.blocks (IBlockOperations, BlockOperations, BlockTreeOperations — only from Editor.kt)
  editor.input (InputHandling — internal to editor/)
  editor.persistence (PersistenceManager, PersistenceErrorHandler, PersistenceIntegration,
                      PersistenceOptimizations, ConflictResolution, IDataSynchronizer,
                      IPersistenceManager, PersistenceModels — internal to editor/)
  editor.viewmodel (EditorViewModel — internal to editor/)
```

Key observation: `editor.commands` does NOT import from any of the dead subpackages (confirmed
by reading its import statements). It is therefore a **self-contained island** that can be left
in place while everything else is deleted.

## Existing live replacements

| Dead class | Live equivalent |
|---|---|
| `EditorState` / `EditorConfig` | `BlockStateManager.kt` (ui/state/) |
| `TextOperations` / `OptimizedTextOperations` | `BlockEditor.kt` inline via `TextFieldValue` + Compose's own text APIs |
| `BlockOperations` / `BlockTreeOperations` | `BlockStateManager` methods (indent/unindent/merge/split) |
| `InputHandling` | `BlockEditor.kt`'s `handleKeyEvent` |
| `PersistenceManager` / `PersistenceIntegration` | `GraphWriter.kt` + `DatabaseWriteActor` |
| `ConflictResolution` | `GraphWriter.kt`'s `DiskConflict` + conflict-resolution UI |
| `EditorViewModel` | `StelekitViewModel` + `LogseqViewModel` |
| `Editor` (orchestrator) | `StelekitViewModel.executeCommand()` for the command bridge |

## businessTest/editor/ — two tests that ARE referenced

`businessTest/kotlin/dev/stapler/stelekit/editor/` contains two test files that are
referenced from `AllBusinessTests.kt`:
- `LinkInsertionTest.kt`
- `ImageAttachCallbackContractTest.kt`

These tests reside in the `editor` package but may test functionality that has been moved
to other packages. They should be checked individually: if they test live code (commands,
block-state manager), they stay; if they test deleted symbols, they are deleted.

## Coverage numbers (JaCoCo, jvmTest run, e337d58c1e)

| Package | Line covered/total | Branch covered/total |
|---|---|---|
| editor.persistence | 0 / ~800 | 0 / ~310 |
| editor.viewmodel | 0 / ~300 | 0 / ~100 |
| editor.text | 0 / ~400 | 0 / ~200 |
| editor.state | 0 / ~250 | 0 / ~90 |
| editor.blocks | 0 / ~200 | 0 / ~80 |
| editor.input | 0 / ~150 | 0 / ~60 |
| editor.commands | ~300 / 572 | 0 / ~200 |
| editor (root, Editor.kt etc.) | ~85 / ~250 | ~10 / ~100 |

Overall `editor.*`: 385/5672 lines = 6.8%, 10/2230 branches = 0.4%

## Risk: does deleting these packages break compilation?

- `editor.commands` has no imports from the dead packages → deletion of dead packages cannot
  break `editor.commands`.
- `businessTest/editor/LinkInsertionTest.kt` and `ImageAttachCallbackContractTest.kt` must be
  checked: if they reference deleted symbols, they need to be deleted or migrated.
- No other source set outside `editor/` imports the dead packages (confirmed).
