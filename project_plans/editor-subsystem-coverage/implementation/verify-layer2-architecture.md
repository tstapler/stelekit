# Layer 2 Architecture Review: editor-subsystem-coverage

Diff: `git diff 826fdf7a..HEAD -- . ':!project_plans'`. Result: 0 BLOCKER, 1 CONCERN, 3 NITPICK.

## 1. Tech Debt Disposition vs diff

| Row | Planned | Diff evidence | Status |
|-----|---------|---------------|--------|
| 24 DeadFiles + 2 READMEs | delete | 26 files deleted under `editor/` (`git diff --stat`: 33 files changed = 26 deleted + 3 tests + AllBusinessTests + baseline + 2 docs). `find kmp/src -path '*stelekit/editor*'` lists only `commands/` in main | Done |
| detekt baseline (8 lines) | remove with files; keep `AnnotationEditorViewModel` | 10 `<ID>` lines removed, all for deleted classes (LargeClass EditorViewModel/PersistenceManager; TooManyFunctions BlockOperations, DefaultTextOperations, EditorState, EditorViewModel, OptimizedTextOperations, PersistenceErrorHandler, PersistenceManager, TextOperations). `AnnotationEditorViewModel` line retained | Done (10 vs planned 8: plan's line list undercounted; every removed ID targets a deleted class) |
| `docs/reference/hotspot-ranking.md:67` | edit row | row for `editor/viewmodel/EditorViewModel.kt` removed | Done |
| `docs/tasks/{3 files}` | leave | untouched; still cite deleted `BlockTreeOperations.kt`/`InputHandling.kt` (e.g. `docs/tasks/copy-cut-paste-blocks.md:168,394`) | Done as planned |
| CommandManager injection | extend as-is | no production change in `commands/` | Done |
| unreachable branches / heading quirk | accept/observe | no production change | Done |

No planned action skipped.

## 2. Dangling structure

- `ICommandSystem` (`ICommandSystem.kt:8`) sole implementer `CommandSystem.kt:15`; `ICommandRegistry` (`CommandRegistry.kt:17`) sole implementer `CommandRegistry.kt:57`. `git grep` of both names across `kmp/` finds no other use. `CommandSystem` and `CommandManager` depend on the concrete `CommandRegistry`. Plan.md:330 logs this; accurate. CONCERN (vestigial interfaces now look like seams; candidate follow-up).
- `kmp/README.md` updated to "Command palette commands (editor.commands)". Accurate.
- Test package `dev.stapler.stelekit.editor` (`LinkInsertionTest.kt:1`, `ImageAttachCallbackContractTest.kt:1`) has no main-source package of that name now. These tests are pre-existing and compile fine (Kotlin does not require a matching package). NITPICK.
- `editor.commands.EditorCommand` coexists with `model/EditorCommand.kt:6` and a top-level `command/` package; plan.md:330 already notes the confusion. NITPICK (pre-existing).

## 3. Residual references

`git grep -n -E "<deleted class names / editor sub-package paths>" -- . ':!project_plans' ':!docs/archive'` hits only:
- `docs/tasks/*.md` (planned leave).
- `compilation_output.txt`, `final_compile_result.txt` at repo root: tracked stale logs from `logseq-kmp` era (`com/logseq/kmp/...` paths, commit 9b08f169). Not live code. NITPICK (pre-existing clutter, out of scope).
- `benchmarks/history/*.json:86`: a historic frame for `editor/commands/EssentialCommands`, which still exists.
- `docs/tasks/templates.md:335,610` cites nonexistent `editor/commands/SlashCommandHandler.kt`: pre-existing, unrelated to this diff.

No hits in Gradle/Bazel/iOS/Swift, workflows, ProGuard, kover/jacoco configs, or other kmp source sets. The only live consumers of `editor.commands` are `ui/CommandManagerCoordinator.kt:3-6` and `ui/StelekitViewModel.kt:43-46`, which all resolve to files kept. No live reference to a deleted symbol.

## 4. Test architecture

- Three new tests live in `kmp/src/businessTest/.../editor/commands/` and are imported and listed in `AllBusinessTests.kt` (suite entries added next to `LinkInsertionTest::class`, diff lines +239-241). `ImageAttachCallbackContractTest` is registered at `AllBusinessTests.kt:347`. Consistent with the businessTest convention and `AllBusinessTestsCompletenessTest`; I did not execute the completeness test (UNVERIFIED by run; static inspection only).
- ADR-001 in this repo (`docs/adr/ADR-001-fileobserver-per-subdirectory.md`, `docs/architecture/decisions/ADR-001-graphcontent-decomposition.md`) is unrelated to test layout; no conflict.
- The new tests exercise pure commonMain logic from businessTest, matching the CLAUDE.md "no UI, fastest" tier. The CLAUDE.md preference for `commonTest` for pure functions is not violated: businessTest is where the repo's `AllBusinessTests` Bazel suite lives and the plan chose it deliberately for Bazel deps.

## 5. kibitzer architecture_assessment (scope `editor/**`)

8 advisory findings, all in `commands/` and all pre-existing, unchanged files: `CommandSystem.kt:21` long function (54 lines), `CommandSystem.kt:24` and `CommandTypes.kt:116` nested expressions, and repeated string-literal keys in `EssentialCommands.kt` ("formattedText", "selectionStart", "selectionEnd", "blockId", "action"). Verified by reading the cited lines' existence via the tool output only; none touches this diff and none is introduced by it. Magic-string map keys also mean the tests assert the same literals, which is an accepted coupling given the no-production-change constraint. No import-cycle or layering findings.

## Findings summary

- BLOCKER: 0
- CONCERN: 1 (vestigial `ICommandSystem`/`ICommandRegistry`; already logged in plan.md:330)
- NITPICK: 3 (test package without main package; stale root compile logs; `docs/tasks` stale citations incl. pre-existing `SlashCommandHandler.kt`)
