# Requirements: editor-subsystem-dead-code

**Item**: test: editor.* subsystem is ~7% covered and unreferenced outside editor/ (delete or test)
**Date**: 2026-10-13
**Status**: pre-implementation triage

## Background

The `editor/` subpackage in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/` contains ~40 files
(~11 256 lines of Kotlin) and is measured at 6.8% line / 0.4% branch JaCoCo coverage as of the
branch `stelekit-merges @ e337d58c1e` (full `:kmp:jvmTest` run, 6234 passed, 0 failed).

The PR #208 "feat(editor): rich editing experience overhaul" explicitly stated it would "extend the
live `FormatAction` system rather than the orphaned `editor/` command framework" and "delete the
confirmed-unreachable slice of the `editor/` command" framework — but the deletion was incomplete.
The live `editor.commands` package is still wired into `StelekitViewModel.kt` and
`CommandManagerCoordinator.kt`. The remaining six subpackages (`editor.persistence`,
`editor.viewmodel`, `editor.text`, `editor.state`, `editor.blocks`, `editor.input`) have **zero**
external callers confirmed by full-repo grep.

### Confirmed reachable (from outside `editor/`)

| Package | External callers |
|---|---|
| `editor.commands` | `ui/StelekitViewModel.kt`, `ui/CommandManagerCoordinator.kt` |

### Confirmed unreachable (0 external imports, 0% coverage)

| Package | Approximate lines |
|---|---|
| `editor.persistence` | ~2 190 (PersistenceManager, PersistenceErrorHandler, PersistenceIntegration, PersistenceOptimizations, ConflictResolution, IDataSynchronizer, IPersistenceManager, PersistenceModels) |
| `editor.viewmodel` | ~826 (EditorViewModel) |
| `editor.text` | ~1 570 (TextOperations, OptimizedTextOperations, DefaultTextOperations, ITextOperations, TextModels) |
| `editor.state` | ~912 (EditorState) |
| `editor.blocks` | ~1 264 (BlockOperations, BlockTreeOperations, IBlockOperations) |
| `editor.input` | ~640 (InputHandling) |
| `editor/Editor.kt` + `EditorModels.kt` + `IEditor.kt` | ~760 |
| `editor/format/` | ~small (FormatModels, IFormatProcessor) |

**Total dead-code candidate**: ~8 000–9 000 lines / 29–30 files

Note: `editor.commands` (EssentialCommands, CommandManager, etc.) IS externally wired. Its
`EssentialCommands.kt` (893 lines, 52% line / 0% branch) should be covered by tests, not deleted.

## Goals

1. Eliminate confirmed dead code from `editor/` to reduce maintenance surface and eliminate
   misleading coverage gaps that misrepresent overall project health.
2. Add `commonTest` coverage to the `editor.commands` subpackage (the only externally-wired piece)
   so its branch coverage rises from 0%.
3. Leave no zombie classes that compile but are never called.

## Acceptance Criteria

### AC-1: Dead package audit confirmed
Each of the six 0%-covered, zero-external-import subpackages (`persistence`, `viewmodel`, `text`,
`state`, `blocks`, `input`) plus `Editor.kt`/`IEditor.kt`/`EditorModels.kt` and `editor/format/`
has been confirmed unreachable by a reproducible grep (no `import dev.stapler.stelekit.editor.<pkg>`
outside `editor/` itself) **and** by attempting compilation after deletion (build passes with
`bazel test //kmp:business_tests`).

### AC-2: Dead packages deleted
All confirmed-unreachable packages and their files are removed from the repository. The build
passes (`bazel test //kmp:business_tests` green) with no source referencing the deleted symbols.

### AC-3: `editor.commands` branch coverage ≥ 30%
At least one `businessTest` or `commonTest` test class exercises `CommandManager`, `EssentialCommands`,
and `CommandRegistry` such that JaCoCo branch coverage for `editor.commands` rises from 0% to ≥ 30%
(measured in the next `jacocoTestReport` run).

### AC-4: No regressions
The full `bazel test //kmp:jvm_tests` (with display, or `//kmp:business_tests` without) remains
green after the deletion. The `AllBusinessTests` suite still passes.

### AC-5: `editor/format/` resolved
`FormatModels.kt` and `IFormatProcessor.kt` are either deleted (if unreferenced) or moved to their
actual caller's package (if referenced), with the move recorded in a one-line commit note.

## Out of scope

- Rewriting `editor.commands` business logic (this is coverage-only work, not refactoring).
- Adding coverage to `editor.commands` beyond the 30% AC-3 threshold in this item.
- Migrating any behavior from the dead packages into `BlockStateManager` or `BlockEditor`.
- Changing any UI behaviour.
