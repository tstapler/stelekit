# Adversarial Review: editor-subsystem-coverage

**Date**: 2026-10-14
**Verdict**: CONCERNS

Verified against source at `8f4818d6` (all paths under `kmp/`). 0 blockers, 6 concerns, 6 minors.

## Verified (not just asserted)

- File count: `find .../editor -type f | grep -v /commands/ | wc -l` = 26; `.kt` = 24; 2 READMEs (`persistence/`, `state/`); `commands/` = 6 files / 1387 lines; `git ls-files .../editor | wc -l` = 32. Deletion list in Task 1.2a is exact.
- No importer outside `editor/`: repo-wide grep of `stelekit\.editor\b` (kt/kts/bazel/yml/xml/sh) finds only `editor.commands`, the two keeper tests' own package lines, and their `AllBusinessTests` imports. The keepers import nothing dead and use none of the root-file symbols.
- No build file lists deleted paths: `kmp/src/commonMain/kotlin/BUILD.bazel` uses a `glob`; no `editor/` entries in any BUILD/kts/yml.
- Registration claim holds: `businessTest/kotlin/BUILD.bazel` uses `srcs = glob(["**/*.kt"])`, `test_class = AllBusinessTests`, so a new `editor/commands/` directory is compiled automatically but only runs once imported and listed in `@Suite.SuiteClasses`. `AllBusinessTestsCompletenessTest` fails otherwise. `kotlinx_coroutines_test_jvm` and `kotlin_test_junit` are already in `deps`.
- Expected values re-derived from `CommandTypes.kt`, `CommandSystem.kt`, `CommandManager.kt`, `CommandRegistry.kt`, `EssentialCommands.kt`: `isAvailable` truth table (incl. `selectionStart < selectionEnd`, reversed range false); unavailable command returns `Error("Command not available in current context")` and is still recorded in history; unknown id message; exception wrapped as `Unexpected error: <msg>`, history not appended; `CancellationException` rethrown; history cap 100 newest first; heading levels 1..6 for `plain`/`##`..`######`; `lineStart/lineEnd` `0,5` and `6,12`; `text.link` both arms; `block.*` `""` fallback; `toggle-todo` is `Nothing`; only `system.save` is `async`; palette set `{media.image}`; selection set (media.image plus 5 text.*); notification 3000/5000 and `Partial` unreachable via `EssentialCommands`; `newLevel > 0` else-arm unreachable (max level 5 gives 6, `% 7` never 0). All matched. No wrong expected value found.
- No hour/day estimates; only cost classes and `timeout` caps.
- `baseline.xml` stale IDs are lines 41, 46, 110, 111, 112, 121, 123, 131 (8); line 103 (`AnnotationEditorViewModel`) correctly kept. `hotspot-ranking.md:67` row exists.

## Blockers

None.

## Concerns

- [ ] **Wasm claim is wrong; the check is available locally.** Task 1.2b says wasm "needs `-PenableJs=true`" and is not compiled locally. `gradle.properties:24` sets `enableJs=true` by default, so wasmJs is enabled in this checkout. Add a wasmJs compile (look up the exact task name; likely `:kmp:compileKotlinWasmJs`) to Task 1.2b, or rewrite the claim. Cheap and closes a target the plan currently leaves to CI.
- [ ] **AC-1 grep does not cover same-package use of the three root files.** `Editor.kt`, `EditorModels.kt`, `IEditor.kt` live in package `dev.stapler.stelekit.editor` (symbols: `Editor`, `IEditor`, `TextFormat`, `FormatState`, `CursorState`, `SearchState`, `SearchResult`, `TextAffinity`, `ValidationError`, `ValidationSeverity`, `IFormatProcessor`). A file in that package needs no import, so the seven-package grep cannot see it. I verified none of the two same-package keeper tests use them, so the deletion is safe, but add a symbol-name grep to AC-1 evidence (or state that the compiler is the proof for the root files). The census regex only catches imports.
- [ ] **AC-3 baseline is not a baseline for `editor.commands`.** Task 1.1c measures with `--tests LinkInsertionTest`, which never touches `commands`; the plan's "0%" is inferred from the earlier 0.4% whole-`editor/` figure, not measured for the package. Record the branch counters from a run that includes `CommandPaletteFocusRetentionTest` (jvmTest, needs display) or say "baseline for the package = 0 by construction of the filter". Otherwise the "from 0% to 30%" statement is asserted, not shown.
- [ ] **Denominator risk and the data-class fallback.** The 30% bar depends on whether JaCoCo 0.8.12 counts data-class `equals`/`hashCode` branches (UNVERIFIED either way; the plan correctly says so). The fallback step 2 (a value-semantics test added to move the number) sits close to the plan's own "no padding" rule. It is labelled, which is honest, but it is a test written to hit a metric. Either keep it and say in the PR it exists for the metric, or drop it and report "measured, not met". Decide before implementation so the implementer is not choosing under pressure.
- [ ] **Cold Bazel `timeout 30m` may produce no baseline.** Tasks 1.1b, 2.4a, 3.1a wrap a cold `bazel test //kmp:business_tests` (full Kotlin compile of commonMain, jvmMain, businessTest, plus a possible Bazel server start) in 30m. A timeout looks like a failure and an AC-4 baseline cannot be formed. Run the first one in the background with a larger cap (60m), or define "timeout is not a baseline" explicitly.
- [ ] **Wave 1 contradicts the "heavy runs never overlap" rule.** Task 1.3a verifies with `./gradlew :kmp:detekt` inside Wave 1, parallel with test authoring, while T1.2b is described as "Wave 2 position if Wave 1 edits are still landing". Make T1.3a's detekt run a Wave 2 step, serialised after T1.2b, so the diagram and the rule agree.

## Minors

- Requirements still say "`commonTest`" in AC-3 and Constraints (lines 37, 49); the plan and ADR-001 use `businessTest`. Update `requirements.md` or cite ADR-001 there so the two do not disagree.
- Heading cursor row (2.3a #8) says "cursor at 0 and at 8"; the expected `6,12` needs `selectionStart = selectionEnd = 8`. State both fields.
- Task 2.1b case 8 (`CommandManager.create(...)` without callback) also needs `advanceUntilIdle()` before executing; the setup note only shows it for the callback variant.
- Task 1.3a lists "EditorViewModel" once but two entries (lines 41 and 112) are `EditorViewModel`; the count of 8 is right, the enumeration reads as 7.
- "~11k lines" for dead code: the 24 `.kt` files total 10,226 lines. Use "~10k".
- `docs/archive/tasks/{block-editing-enhancements,kmp-build-failure-resolution}.md` also name deleted files; the plan's Tech Debt table only lists `docs/tasks/*`. Same disposition (leave), but list them so the count is complete.
- Note for the notes file: per the header of `AllBusinessTests.kt`, Bazel PR CI runs only `//kmp:jvm_tests`, so these new tests are exercised by Gradle `ciCheck`/`jvmTest` and release gate, not by Bazel PR CI. Worth one line so nobody assumes Bazel CI guards them.

## AC feasibility

| AC | Satisfiable as written? | Note |
|----|--------------------------|------|
| AC-1 | Yes, with the root-symbol caveat above | Wording "0 matches for every deleted package" is met; root files closed by compiler |
| AC-2 | Yes | Counts verified (26 deletions, 6 remain) |
| AC-3 | Probably; fallback honest | Reachable hand-written branches (isAvailable 16, system/manager/registry, heading/link/`?:` arms) plausibly exceed 30% even if data-class branches count, but unmeasured. Fallback wording ("measured, not met" / "not measurable") is honest |
| AC-4 | Yes via fallback | Requirement text allows `testDebugUnitTest`; plan uses it when no display. Display availability remains the only owner-dependent item |
| AC-5 | Yes | Both files are in the 24 |
