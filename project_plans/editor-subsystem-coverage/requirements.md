# Requirements: editor-subsystem-coverage

**Item**: c808071d-8bd2-40ac-a77a-3c0f6d8f3ccc — test: editor.* subsystem is ~7% covered and unreferenced outside editor/ (delete or test)
**Builds on**: `project_plans/editor-subsystem-dead-code/` (earlier triage of this same item; its deletion list is re-verified below, its test-API names are corrected).

## Problem

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/` measures 6.8% line / 0.4% branch coverage
(JaCoCo, JVM-only). Most of it is unreachable from the app; the one live slice, `editor.commands`,
has no direct tests.

## Verified facts (grep + file reads on this branch)

- **Live**: `editor.commands` (6 files, 1 387 lines) is imported by `ui/StelekitViewModel.kt` and
  `ui/CommandManagerCoordinator.kt`, and used by `jvmTest/.../CommandPaletteFocusRetentionTest.kt`.
  It depends only on `logging.Logger` and `model.NotificationType`.
- **Dead**: the other 24 files under `commonMain/.../editor/` have no importer outside `editor/`:
  `blocks/` (3), `format/` (2), `input/` (1), `persistence/` (8), `state/` (1), `text/` (5),
  `viewmodel/` (1), and root `Editor.kt`, `EditorModels.kt`, `IEditor.kt` (3).
- **Count correction**: the item says "~29 dead files"; the actual count is **24**. Deletion
  criteria are met by removing all 24, not by reaching 29.
- **Root files must go with the packages**: `EditorModels.kt` imports `editor.text.*`, so it cannot
  survive deleting `text/`; nothing outside `editor/` uses its symbols (`TextFormat`, `FormatState`, …).
- **Keepers in the `editor` test package**: `businessTest/.../editor/LinkInsertionTest.kt` and
  `ImageAttachCallbackContractTest.kt` import nothing from the dead packages and stay.
- **API correction**: `isAvailable(context)` is a method on `EditorCommand`, not `CommandConfig`
  (`CommandTypes.kt:115`). `CommandManager` exposes `executeCommand(id, context)` and
  `getAvailableCommands(context)`; there is no `execute()`.

## Acceptance criteria (from the backlog item, with the interpretation used here)

1. **AC-1** Grep for `stelekit.editor.<pkg>` outside `editor/` finds 0 matches for every deleted
   package (persistence, viewmodel, text, state, blocks, input, format). Reproducible command and
   output are recorded in the verification notes.
2. **AC-2** All 24 dead files are deleted; `bazel test //kmp:business_tests` has 0 failures.
3. **AC-3** New `CommandManagerTest` (placed in `businessTest`, see ADR-001; the item's
   "commonTest" wording is superseded because Bazel only runs `businessTest`) covers `EditorCommand.isAvailable()`
   permutations (`enabled`, `hidden`, `condition`, `requiresSelection`, `requiresBlock`,
   `requiresPage`) and `CommandManager.executeCommand()` success/error/unknown-id paths, taking
   `editor.commands` branch coverage from 0% to ≥ 30%. Measured with `jacocoTestReport`, or
   reported as not measurable with the reason if JaCoCo cannot be run here.
4. **AC-4** `bazel test //kmp:jvm_tests` (via `scripts/jvm-display-check.sh`) or
   `./gradlew testDebugUnitTest` shows zero new failures.
5. **AC-5** `editor/format/FormatModels.kt` and `IFormatProcessor.kt` are deleted (referenced only
   by the deleted `IEditor.kt`).

## Constraints

- Do not change `editor.commands` production behaviour; tests only.
- Test placement: `businessTest`, registered in `AllBusinessTests.kt` (ADR-001). `commonTest` is
  not globbed by Bazel's `business_tests`, so a test there would not run under the canonical build.
- Bazel builds are wrapped in `timeout`; run `bazel shutdown` before ending the session.
- Stage only touched files; no `git add -A`; any PR is a draft.

## Out of scope

Refactoring `editor.commands`, porting behaviour from dead code into `BlockStateManager`, UI changes.
