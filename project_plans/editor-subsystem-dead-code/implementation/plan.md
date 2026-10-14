# Implementation Plan: editor-subsystem-dead-code

**Date**: 2026-10-13
**Scope**: Delete confirmed-dead `editor.*` subpackages; add `commonTest` coverage for the live
`editor.commands` package.

## Confirmed deletion targets

All files in these directories have zero external callers (confirmed by full-repo grep) and
zero test coverage. `editor.commands` is NOT a deletion target — it is externally wired.

| Directory | Files | Approx lines |
|---|---|---|
| `editor/` root | `Editor.kt`, `IEditor.kt`, `EditorModels.kt` | ~760 |
| `editor/format/` | `FormatModels.kt`, `IFormatProcessor.kt` | ~small |
| `editor/state/` | `EditorState.kt` | ~912 |
| `editor/text/` | `ITextOperations.kt`, `TextOperations.kt`, `OptimizedTextOperations.kt`, `DefaultTextOperations.kt`, `TextModels.kt` | ~1 570 |
| `editor/blocks/` | `IBlockOperations.kt`, `BlockOperations.kt`, `BlockTreeOperations.kt` | ~1 264 |
| `editor/input/` | `InputHandling.kt` | ~640 |
| `editor/persistence/` | `PersistenceManager.kt`, `PersistenceErrorHandler.kt`, `PersistenceIntegration.kt`, `PersistenceOptimizations.kt`, `ConflictResolution.kt`, `IDataSynchronizer.kt`, `IPersistenceManager.kt`, `PersistenceModels.kt` | ~3 150 |
| `editor/viewmodel/` | `EditorViewModel.kt` | ~826 |
| **Total** | **~29 files** | **~9 100 lines** |

## Safe keepers

| Path | Reason to keep |
|---|---|
| `editor/commands/` (all files) | Externally wired from `StelekitViewModel`, `CommandManagerCoordinator`; has non-zero coverage |
| `businessTest/editor/LinkInsertionTest.kt` | Tests `applyFormatAction(LINK)` string logic with no dead-package imports |
| `businessTest/editor/ImageAttachCallbackContractTest.kt` | Tests `EditorCapabilities.onAttachImage` contract with no dead-package imports |

## Phase 1 — Confirm + delete dead packages (1–2h)

### Task 1.1 — Confirm deletion safety via compilation (30 min)
Run `bazel test //kmp:business_tests` on main (no changes) to establish a green baseline.
Then delete one file at a time from the smallest package (`editor/format/`, `editor/input/`)
and confirm `bazel test //kmp:business_tests` stays green after each group.

### Task 1.2 — Delete dead packages (1h)
Delete in this order (dependent-on-deleted first to fail fast):

1. `editor/viewmodel/EditorViewModel.kt` (imports text + state + blocks + input)
2. `editor/input/InputHandling.kt`
3. `editor/persistence/` (all 8 files — self-contained)
4. `editor/text/` (all 5 files)
5. `editor/blocks/` (all 3 files)
6. `editor/state/EditorState.kt`
7. `editor/Editor.kt`, `editor/IEditor.kt`, `editor/EditorModels.kt`
8. `editor/format/FormatModels.kt`, `editor/format/IFormatProcessor.kt`

Commit with message:
```
chore(editor): delete ~9100 lines of confirmed-dead editor.* subpackages

All six subpackages (persistence, viewmodel, text, state, blocks, input)
plus the root Editor/IEditor/EditorModels/format files had zero external
callers (full-repo grep confirms) and 0% JaCoCo branch coverage.
PR #208 (feat(editor): rich editing experience overhaul) established that
the live editing path uses BlockStateManager/BlockEditor/FormatAction.
editor.commands is retained — it is still wired in StelekitViewModel.

Closes: <issue>
```

### Task 1.3 — Verify build is clean (15 min)
Run `bazel test //kmp:business_tests` and confirm green.
Run `bazel test //kmp:jvm_tests` (with display, or skip UI tests if no display) to confirm
no regressions.

## Phase 2 — Add `editor.commands` commonTest coverage (2–3h)

### Task 2.1 — Scope: what needs testing (30 min)
Read `CommandManager.kt`, `EssentialCommands.kt`, `CommandRegistry.kt`, `CommandSystem.kt`,
`CommandTypes.kt`. Identify the most-impactful branch paths currently at 0%:
- `CommandManager.create()` + `execute()` happy/error paths
- `CommandConfig.isAvailable(context)` permutations (requiresBlock, requiresSelection, hidden)
- `EssentialCommands` getAll() / filtering

### Task 2.2 — Write `editor/commands/CommandManagerTest.kt` in `commonTest` (1.5h)
Minimum test class covering:
1. `CommandManager.execute()` calls registered command and returns `CommandResult.Success`
2. `CommandManager.execute()` returns `CommandResult.Error` when command ID unknown
3. `CommandConfig.isAvailable()` — hidden=true command excluded from available list
4. `CommandConfig.isAvailable()` — requiresBlock=true excluded when no block in context
5. `CommandConfig.isAvailable()` — requiresSelection=true excluded when selection is 0-length

Target: ≥5 test methods, enough to push `editor.commands` branch coverage above 30%.

### Task 2.3 — Run and verify coverage (30 min)
Run `bazel test //kmp:business_tests` to confirm new tests pass.
Optionally run `./gradlew :kmp:jacocoTestReport` and inspect XML for the `editor.commands`
branch counter — confirm ≥ 30%.

## Rollback

If the build breaks after deletion, `git restore <file>` the deleted files and re-check which
external import was missed. The grep command to re-run:
```
grep -r "import dev.stapler.stelekit.editor\." kmp/src --include="*.kt" | grep -v "/editor/"
```

## Estimated effort

| Task | Estimate |
|---|---|
| Confirm + delete dead packages (Tasks 1.1–1.3) | 2h |
| Write `CommandManagerTest.kt` (Tasks 2.1–2.3) | 2.5h |
| **Total** | **4.5h** |
