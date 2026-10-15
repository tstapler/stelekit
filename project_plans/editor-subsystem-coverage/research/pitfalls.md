# Pitfalls: deleting the 24 dead files under `commonMain/.../editor/` (all but `commands/`)

Date 2026-10-14. All results below come from grep/find run on branch
`backlog/stelekit-editor-subsystem-dead-code`. Scope: what an `import dev.stapler.stelekit.editor.` grep would miss.

## Verdict: no BLOCKING finding

Deleting the 24 `.kt` files (plus `persistence/README.md` and `state/README.md`) does not break compilation, tests,
Bazel, or the coverage gate. Non-blocking cleanups are listed at the end.

## Evidence by question

1. **Importers outside `editor/`.** `grep -rnE "import dev\.stapler\.stelekit\.editor" kmp --include=*.kt` outside
   `editor/` returns only: `commands.*` imports in `ui/StelekitViewModel.kt:43-46` and
   `ui/CommandManagerCoordinator.kt:3-6`, and two keeper test imports in
   `businessTest/.../AllBusinessTests.kt:38,140` (`editor.LinkInsertionTest`, `editor.ImageAttachCallbackContractTest`,
   both defined in `businessTest/.../editor/`). No wildcard `editor.*` / `editor.<pkg>.*` import exists anywhere.
2. **Fully-qualified references.** Only `jvmTest/.../CommandPaletteFocusRetentionTest.kt:451-452`
   (`dev.stapler.stelekit.editor.commands.EssentialCommands` / `CommandContext`), which is the live slice.
3. **Same-package unimported use.** The only files outside `editor/` that declare `package dev.stapler.stelekit.editor`
   are the two keeper tests. A word-match of all 166 type names declared in the dead files against both
   (`/tmp/dead_syms.txt` method: `class|interface|object|typealias` declarations) returns zero hits; their imports are
   `model.BlockUuid`, `ui.components.EditorCapabilities`, `kotlin.test.*`. They stay green.
4. **Same simple names as live code.** Dead files define `TextRange`, `TextFormat`, `EditorState`, `CursorState`,
   `BlockChange`, `ConflictResolver`, `SearchResult`, `ValidationResult`, etc.; live code has same-named types
   (`model/CursorState.kt`, `migration/BlockChange.kt`, `git/ConflictResolver.kt`, ...). None of those live files
   import any dead package or sit in a dead package, so Kotlin cannot resolve to the dead symbols; deletion cannot
   change resolution. Dead top-level funs/vals (`IBlockOperations.create()` at `blocks/BlockTreeOperations.kt:438`,
   `ImeCompositionIndicator`, `KeyboardShortcutsReference`) have no outside callers.
5. **`editor/commands/` depends on dead files? No.** `rg 'stelekit\.editor'` inside `commands/*.kt` hits only the six
   `package` lines and `EssentialCommands.kt:3` (`import ...editor.commands.*`, self-package). Other imports are
   `logging.Logger` and `model.NotificationType` (`CommandManager.kt:5`). Dead-package symbols are not referenced.
   (`EssentialCommands.TextFormatting` at `EssentialCommands.kt:33` is a nested object, not `editor.text.TextFormat`.)
6. **Test source sets.** Searched `androidUnitTest`, `androidInstrumentedTest`, `commonTest`, `businessTest`,
   `jvmTest`, `wasmJsTest`, `iosTest`, `androidApp/src/androidTest`, `benchmarks`: no reference to dead packages or to
   the unique dead symbols (`EditorViewModel`, `PersistenceManager`, `EditorStateManager`, `BlockTreeOperations`,
   `ImeCompositionManager`, `OptimizedTextOperations`, ...). `kmp/src/{androidMain,jvmMain,jsMain,wasmJsMain,iosMain}`
   also clean. Word-boundary matches that look like hits are `AnnotationEditorViewModel` substrings.
7. **Bazel.** `kmp/src/commonMain/kotlin/BUILD.bazel:32-47` uses `glob(["**/*.kt"], exclude=[stats, Mermaid*, cache/...])`;
   no per-file or per-package `editor` listing, so deletion is transparent. No BUILD file names the keeper tests.
   `tier_manifest.bzl:54` lists `"editor"` in `TIER_3_PACKAGES`; the package still exists via `commands/`, so no change.
8. **ProGuard/R8, jacoco excludes, kover, CODEOWNERS.** No `editor` match in any tracked `.pro`/`.proguard`, workflow,
   gradle script, or CODEOWNERS (`androidApp/proguard/benchmark-rules.proguard` has no editor entry).
   `@Serializable` types in `persistence/` and `text/` are never referenced by name from rules or reflection;
   no `Class.forName`/`ServiceLoader`/`::class.java` in dead files.
9. **Coverage gate (PR #402).** `kmp/build.gradle.kts:1555-1559` computes changed files with `--diff-filter=ACMR`, so
   deleted files never enter `changedMainClasses` and cannot be gated at the 80% `diffCoverage.threshold`
   (`:1545`). The new test file is `A` (added) and is selected as a changed test. Pure deletion + test additions leave
   the gate with nothing to fail on for the deleted files. Note: the gate only measures files in `commonMain|jvmMain`
   changed by the PR; the `commands/` files are unchanged, so adding tests there is not gated either.
10. **Detekt baseline.** `kmp/config/detekt/baseline.xml` lines 41, 46, 110-112, 121, 123, 131 name dead classes
    (`EditorViewModel`, `PersistenceManager`, `DefaultTextOperations`, `EditorStateManager`,
    `OptimizedTextOperations`, `TextOperations`). Stale baseline entries do not fail detekt (config at
    `kmp/build.gradle.kts:1222-1223`, `buildUponDefaultConfig = true`); remove them as optional cleanup.

## Non-blocking cleanups (stale text references, not compile inputs)

- `kmp/config/detekt/baseline.xml`: 8 stale `<ID>` lines above.
- `docs/reference/hotspot-ranking.md:67`: lists `editor/viewmodel/EditorViewModel.kt` as pending.
- `docs/tasks/copy-cut-paste-blocks.md:168,271,394,553,563,573`, `docs/tasks/drag-and-drop-reorder.md:501,703`,
  `docs/tasks/task-management-ui.md:363,471`: cite `BlockTreeOperations.kt` / `InputHandling.kt` as "reference" code.
  Those task docs will point at missing files after deletion (references only; recoverable from git history).
- `docs/tasks/templates.md:335,610` cite `commands/SlashCommandHandler.kt`, which does not exist (pre-existing, unrelated).
- `compilation_output.txt` and `final_compile_result.txt` (tracked at repo root) are stale logs of an old
  `com/logseq/kmp/editor/` layout; harmless.
- `benchmarks/history/2026-05-30_...json:86` mentions `EssentialCommands.getAll`, which is live.
- Dead `persistence/README.md` and `state/README.md` must be removed with the packages (they are in the 26-file
  directory listing; the "24" count is `.kt` only).

## Residual risk

- INFERRED (not run): full compile. Recommend `./gradlew :kmp:compileKotlinJvm :kmp:compileDebugKotlinAndroid` or
  `bazel build //kmp:...` after deletion, since static grep cannot prove no type-inferred use of a dead type.
  No such path is plausible given zero imports and zero same-package consumers.
