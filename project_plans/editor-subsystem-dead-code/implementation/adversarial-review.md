# Adversarial Review: editor-subsystem-dead-code plan

**Date**: 2026-10-13
**Reviewer role**: Devil's advocate — challenge assumptions, find gaps, surface failure modes.

## Challenges to the deletion plan

### Challenge 1: "grep confirms no imports" is necessary but not sufficient

The grep only finds **direct** `import` statements. Kotlin's star-imports (`import
dev.stapler.stelekit.editor.*`) would match; the repo has none (verified: the only
star-import from `editor` is within `editor/` itself).

However, **reflection** or **string-based class loading**
(e.g., `Class.forName("dev.stapler.stelekit.editor.Editor")`) would not be caught by grep.
A quick `grep -r "stelekit.editor.Editor\|stelekit.editor.viewmodel\|stelekit.editor.persistence"` in `.kt` and `.xml` files would close this gap.

**Verdict**: low risk; this codebase does not use reflection-based DI. Mitigation: do the
extra grep during Task 1.1 before committing the deletion.

### Challenge 2: `businessTest/editor/` tests are in package `dev.stapler.stelekit.editor` — do they survive deletion?

`LinkInsertionTest.kt` and `ImageAttachCallbackContractTest.kt` are in the `editor` package
but only import from `model.*` and `ui.components.*`. They do NOT import any of the dead
subpackages. Deletion of the dead packages does not affect these tests.

**Verdict**: no risk; confirmed safe above.

### Challenge 3: The `editor.commands` coverage target (30%) may be harder than expected

`EssentialCommands.kt` is 893 lines with many `EditorCommand` object declarations. JaCoCo
counts Kotlin object initializers as branches. Writing tests that invoke `getAll()` and
filter on `isAvailable()` should hit many of these branches, but the 30% target could still
miss if the test runner only exercises one code path through `CommandConfig`.

**Mitigation**: write tests against `CommandConfig.isAvailable()` directly with multiple
inputs (requiresSelection=true/false, requiresBlock=true/false, hidden=true/false) rather
than end-to-end through `CommandManager.execute()` — direct unit tests on the config logic
will cover more branches cheaply.

### Challenge 4: Does `@DirectRepositoryWrite` usage in `Editor.kt` create a compile-time dependency?

`Editor.kt` has `@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)`.
This annotation is in the `repository` package, not `editor`. Deleting `Editor.kt` removes
the only source of this opt-in; that's fine — it's an annotation, not a runtime symbol.

**Verdict**: no risk.

### Challenge 5: Deletion order — `EditorViewModel` imports `text`/`state`/`blocks` — wrong order breaks nothing

The deletion order in the plan (viewmodel → input → persistence → text → blocks → state →
root → format) is safe because we are **deleting** files, not compiling intermediate states.
The Kotlin compiler only errors on missing symbols if the referencing file is present. Since
`Editor.kt` imports `editor.text.*` etc., deleting `editor.text/` first would cause `Editor.kt`
to fail to compile — but if we delete `Editor.kt` first, then `editor.text/` loses its last
external client and compiles cleanly.

**Correction to plan**: delete top-down, not bottom-up:
1. Root files first (`Editor.kt`, `IEditor.kt`, `EditorModels.kt`)
2. Then state (which Editor referenced)
3. Then text (which EditorState referenced)
4. Then blocks, input, viewmodel, persistence, format

Or simplest: delete all dead files in a single `git rm` batch — the compiler never sees
an intermediate state.

### Challenge 6: The `bazel test //kmp:business_tests` gate may not catch all issues

`//kmp:business_tests` does not compile Compose UI code. Dead symbols referenced only via
Compose `@Composable` functions in `jvmTest` or `androidUnitTest` would compile-fail there
but not here. Given the dead packages are in `commonMain` and are not imported anywhere in
`commonMain/ui/`, this is moot — but should be noted.

**Mitigation**: also run `bazel test //kmp:jvm_tests` (with display) or at minimum
`./gradlew testDebugUnitTest` as a secondary gate.

## Summary verdict

The plan is sound. Two low-cost mitigations should be added:
1. Add a reflection/string grep during Task 1.1 (5 min).
2. Correct deletion order to top-down or batch (addresses Challenge 5).
3. Add `testDebugUnitTest` to the verification gate (addresses Challenge 6).

No fundamental blocker found.
