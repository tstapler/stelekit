# Implementation Plan: editor-subsystem-coverage

**Feature**: Delete the 24 unreachable `editor.*` files (all but `editor/commands/`) and add direct tests that take `editor.commands` branch coverage from 0% to at least 30%.
**Date**: 2026-10-14
**Status**: Ready for implementation
**ADRs**: [ADR-001](../decisions/ADR-001-test-placement-businesstest.md) (tests live in `businessTest`, not `commonTest`)
**Complexity**: 2 (full template; Migration, Observability and Risk Control are kept short because the change is deletion plus test-only code)

Builds on `project_plans/editor-subsystem-dead-code/` (its deletion list is re-verified here; its API names, file count and test placement were wrong and are corrected below).

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `EditorCommand` | Data class with `id`, `type`, `label`, `config`, `condition`, `execute`. Owns `isAvailable(context)` and `executeWithContext(context)` (`CommandTypes.kt:101-134`) | `isAvailable` is on `EditorCommand`, not `CommandConfig` |
| `CommandConfig` | Data class of availability flags: `enabled`, `hidden`, `requiresSelection`, `requiresBlock`, `requiresPage`, `async`, and others (`CommandTypes.kt:80`) | Pure data; no behaviour |
| `CommandContext` | Input to availability and execution: `currentText`, `selectionStart/End`, `currentBlockId`, `currentPageId`, `modifiers`, `additionalData` (`CommandTypes.kt:52`) | Selection present means `selectionStart < selectionEnd` |
| `CommandResult` | Sealed class: `Success(message, data)`, `Error(message, exception, code)`, `Partial(message, completed, total)`, `Nothing` | `Nothing` is an `object` shadowing `kotlin.Nothing` inside the package; reference it as `CommandResult.Nothing` |
| `CommandRegistry` | In-memory `MutableStateFlow<Map<String, EditorCommand>>` keyed by id (`CommandRegistry.kt:57`) | Public constructor, so tests can register custom commands |
| `CommandSystem` | Executes by id through a `CommandRegistry`, keeps a capped (100) newest-first history (`CommandSystem.kt:13`) | Public constructor takes a `CommandRegistry` |
| `CommandManager` | Facade: owns its own `CommandRegistry` + `CommandSystem`, registers `EssentialCommands.getAll()` in `init`, maps `CommandResult` to notification callbacks (`CommandManager.kt:19`) | Registry is private and pre-seeded: no custom command injection |
| `EssentialCommands` | Object holding the 24 built-in `EditorCommand`s and `getAll()` (`EssentialCommands.kt:28,856`) | 893 lines, mostly object initialisers |
| `PaletteContext` | The `CommandContext` `StelekitViewModel.updateCommands()` builds: `selectionStart = 0`, `selectionEnd = 0`, `currentBlockId` = page uuid (non-null) | Test fixture name only |
| `LiveSlice` | `editor/commands/` (6 files, 1 387 lines): imported by `StelekitViewModel.kt`, `CommandManagerCoordinator.kt`, `CommandPaletteFocusRetentionTest.kt` | Stays untouched |
| `DeadFile` | One of the 24 `.kt` files under `editor/` outside `commands/` (blocks 3, format 2, input 1, persistence 8, state 1, text 5, viewmodel 1, root 3) | Plus 2 `README.md` files deleted with them |
| `KeeperTest` | `businessTest/.../editor/LinkInsertionTest.kt` and `ImageAttachCallbackContractTest.kt`: in package `dev.stapler.stelekit.editor`, import nothing dead | Must stay green and registered |
| `BranchCoverage` | JaCoCo `BRANCH` counter (`covered / (covered + missed)`) on `<package name="dev/stapler/stelekit/editor/commands">` in `kmp/build/reports/jacoco/jacoco.xml` | Includes lambda classes (`EssentialCommands$...$1`) |
| `AllBusinessTests` | JUnit `@Suite` that is the only runner for `//kmp:business_tests`; new classes must be imported and listed (`AllBusinessTests.kt`) | `AllBusinessTestsCompletenessTest` fails if one is missing |

---

## Pattern Decisions

**CREATIVE pass (approaches considered for the test half)**

| Approach | Strength | Weakness | Verdict |
|----------|----------|----------|---------|
| A. Black-box only through `CommandManager` | Tests the one public facade the app uses | Registry is private and pre-seeded, so `Partial`, thrown-exception and `async` branches are unreachable; coverage stalls | Rejected as the only layer |
| B. Add an injection seam to `CommandManager` and test everything through it | Reaches every branch | Changes production code, which the requirements forbid | Rejected |
| C. Layered: truth-table on `EditorCommand.isAvailable`, `CommandSystem`+`CommandRegistry` with custom commands, `CommandManager` and `EssentialCommands` through their real public surface | Every public branch is reachable without touching production code; each layer's failures localise | Three test files instead of one | Chosen |

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| `isAvailable` tests | Exhaustive truth table (2^9 = 512 combinations of `enabled`, `hidden`, `condition`, three `requires*`, three "fact present" flags) plus named examples | Test design | `kotest-property` `Arb` | Finite space; exhaustive beats random and has no generator setup in Bazel |
| Custom commands in tests | Test Data Builder (`fun command(id, config, execute)`) | Meszaros xUnit Test Patterns | One literal per test | 20+ cases would repeat the nine-field constructor |
| Notification assertions | Recording lambda (`MutableList<Triple<String, NotificationType, Long?>>`) | Test Spy | Mocking library | The callback is a plain function type; a list is enough and works in commonMain-style code |
| Coroutine determinism | `runTest { val m = CommandManager(backgroundScope, ...); advanceUntilIdle() }` | kotlinx-coroutines-test | `Dispatchers.setMain` | `init` launches on the passed scope; `backgroundScope` cannot leak a collector |
| Characterization of `EssentialCommands` | Assert observable `CommandResult` / `data` values and availability sets | Feathers, characterization tests | Reading source and asserting nothing | The Phase H "only `media.image` is palette-visible" invariant is currently guarded by comments only |
| Deletion | One atomic `git rm` batch | PoEAA-neutral | Package-by-package | Packages import each other (`Editor.kt`, `EditorModels.kt` import `text.*`); partial deletion does not compile |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| 24 `DeadFile`s + 2 READMEs under `editor/` | Un-integrated parallel editor stack (~11k lines), 6.8% line / 0.4% branch coverage, no importer outside `editor/` | Refactor-first (delete) | Superseded by `BlockStateManager`/`GraphWriter`/`BlockEditor` per `research/build-vs-buy.md`; keeping and testing would pin unreachable behaviour |
| `kmp/config/detekt/baseline.xml` lines 41, 46, 110, 111, 112, 121, 123, 131 | Eight suppressions for deleted classes | Refactor-first (remove with the files) | Baseline count may shrink; CI only rejects growth (`ci.yml:162-166`). Keep line 103 (`AnnotationEditorViewModel`, live) |
| `docs/reference/hotspot-ranking.md:67` | Row for deleted `editor/viewmodel/EditorViewModel.kt` marked `pending` | Refactor-first (edit the row) | Live reference doc; would mislead the next hotspot pass |
| `docs/tasks/{copy-cut-paste-blocks,drag-and-drop-reorder,task-management-ui}.md` | Cite deleted `BlockTreeOperations.kt` / `InputHandling.kt` as reference code | Extend as-is (leave) | Historic task specs; content recoverable from git history (`3b1a7aa4^`). Owner may override |
| `CommandManager` | Registry and system are private and constructed internally, so custom commands cannot be injected | Extend as-is | Constraint: no production change; layered tests reach the same branches through `CommandSystem`/`CommandRegistry`. Not a new instance of the violation |
| `EssentialCommands` heading `newLevel > 0` else-branch and `CommandManager.executeCommand` outer `catch (Exception)` | Unreachable: `(h+1)%7` with `h <= 5` is never 0; `CommandSystem` already catches every `Exception` | Extend as-is (accepted gap) | Counted in the denominator; not worth a production edit. Reported in Story 2.4 |
| `EssentialCommands.TextFormatting.heading` | Prefix is prepended without stripping an existing one (`"## a"` becomes `"## ## a"`) | Extend as-is (observation only) | Out of scope (tests only); tests assert the reported level, not the quirky `newLine`, so they do not enshrine it. Surface in the final report |

---

## Migration Plan

Not applicable: no schema or data change. Deletion is reversible through `git revert`.

## Observability Plan

- **Logs**: none added. Test output and JaCoCo XML are the evidence.
- **Metrics**: `editor.commands` `BranchCoverage` recorded once in the verification notes (before and after).
- **Alerts**: no new alerts. The PR #402 diff-coverage gate does not fire (`kmp/build.gradle.kts:1555-1559`: deleted files are `--diff-filter=ACMR` excluded; `commands/` files are unchanged).

## Risk Control

- **Feature flag**: not gated.
- **Rollback procedure**: standard revert via PR close or revert commit. Deletion and tests are separate commits so either can be reverted alone.
- **Staged rollout**: full rollout on merge. Any PR opened is a draft (`gh pr create --draft`).

## Unresolved Questions

- [ ] Does `./gradlew :kmp:jvmTest --tests '<filter>' :kmp:jacocoTestReport` run headless on this machine and emit `jacoco.xml`? UNVERIFIED (`research/stack.md` section 3). Blocks Task 2.4b. Resolved by Task 1.1c. Owner: implementer agent.
- [ ] Does JaCoCo 0.8.12 count Kotlin data-class `equals`/`hashCode`/`copy` of `CommandContext`, `CommandConfig`, `EditorCommand`, `CommandResult.*`, `CommandHistoryEntry` in the `BRANCH` denominator? INFERRED yes (about 100 of the roughly 200 branches). Determines how much of the 30% the hand-written branches must carry. Resolved by Task 1.1c per-class counters. Owner: implementer agent.
- [ ] Display availability for `bazel test //kmp:jvm_tests`: `scripts/jvm-display-check.sh` decides; installing Xvfb needs `sudo pacman -S xorg-server-xvfb`. Blocks the jvm_tests half of AC-4 only. Owner: Tyler.

---

## Dependency Visualization

```
Wave 0 (serial, one agent)        Wave 1 (4 agents in parallel, disjoint files)        Wave 2 (serial)                     Wave 3
T1.1a grep baseline  ─┐           ┌─ S1.2  T1.2a git rm ─> T1.2b compile ─> T1.2c      T2.4a register 3 classes in
T1.1b baseline tests ─┼─ gate ───>├─ S2.1  T2.1a, T2.1b  CommandManagerTest.kt   ───>  AllBusinessTests.kt ─> run   ──> S3.1 T3.1a full AC-4 run
T1.1c JaCoCo spike   ─┘           ├─ S2.2  T2.2a         CommandSystemRegistryTest.kt  business_tests               T3.1b hygiene + commit
                                  ├─ S2.3  T2.3a         EssentialCommandsTest.kt       T2.4b JaCoCo measure ─> T2.4c (only if <30%)
                                  └─ S1.3  T1.3a, T1.3b  baseline.xml + hotspot doc
```

Heavy runs (Bazel, Gradle) never overlap: Bazel and Gradle each take a workspace lock, and a concurrent compile would blur which edit broke what. Wave 1 agents edit files; one agent runs the build afterwards (T1.2b in Wave 2 position if Wave 1 edits are still landing). T1.3a's detekt run counts as a heavy run: it executes in Wave 2 after T1.2b, not in Wave 1. A cold Bazel run can exceed `timeout 30m`; if T1.1b times out, retry once with `timeout 60m` and record the cold-cache cause, so AC-4 keeps a baseline.

---

## Phase 1: Delete the dead subsystem

### Epic 1: Remove 24 files and prove nothing referenced them
**Goal**: AC-1, AC-2, AC-5.

#### Story 1.1: Baseline before touching anything
**As a** maintainer, **I want** the pre-change grep, test result and JaCoCo numbers recorded, **so that** "zero new failures" and "0% to 30%" are comparisons, not assertions.
**Acceptance Criteria**:
- Pre-change state is captured in `project_plans/editor-subsystem-coverage/implementation/verification-notes.md` (an artifact the plan owner asked for under AC-1).
  - *Given* the branch at `8f4818d6` with 24 `DeadFile`s present, *When* the AC-1 grep (below) runs, *Then* it prints matches only inside `editor/` and the notes record `files=24 kt + 2 md, commands=6`.
**Files**: `project_plans/editor-subsystem-coverage/implementation/verification-notes.md`

##### Task 1.1a: Record file inventory and AC-1 baseline (XS)
- `find kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor -type f | sort` into notes. Expected: 26 files outside `commands/` (24 `.kt` + 2 `README.md`) and 6 under `commands/`, 32 total. Recount with `| wc -l`; do not trust this plan's numbers.
- Run the AC-1 grep (see AC table) and the outside-`editor/` import census; record output.
- Files: `verification-notes.md`

##### Task 1.1b: Baseline test result (XS agent work; wall-clock blocker: cold Bazel)
- `timeout 30m bazel test //kmp:business_tests`; record pass/fail and any failing targets.
- `scripts/jvm-display-check.sh` (report-only); record verdict. If a display or Xvfb exists, `timeout 60m scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`; record failing targets. If not, record "jvm_tests not runnable here: <reason>" and plan on `./gradlew testDebugUnitTest` for the AC-4 alternative.
- Files: `verification-notes.md`

##### Task 1.1c: JaCoCo feasibility spike and denominator (S) — riskiest step, runs first
- `timeout 40m ./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.editor.LinkInsertionTest' :kmp:jacocoTestReport` (filter on the same invocation; `jacocoTestReport` depends on `jvmTest`). This is a feasibility spike only: it proves JaCoCo runs and emits `jacoco.xml`. The existing `CommandPaletteFocusRetentionTest` exercises `editor.commands`, so the true pre-change `editor.commands` baseline is whatever the report shows for that package; to get it, also run with `--tests '*CommandPaletteFocusRetentionTest'` only if a display is available, otherwise record the item's measured 0% as the baseline and say it was not re-measured.
- If it needs a display, retry as `scripts/jvm-display-check.sh -- ./gradlew ...`.
- Extract the package and per-class `BRANCH` counters with the script in "Coverage extraction" below; record total branches, covered, and the per-class breakdown (which classes hold data-class generated branches).
- Outcome decides the Unresolved Questions above. If JaCoCo cannot run at all, record the exact error and go to the "JaCoCo cannot run" fallback; do not block the other waves.
- Files: `verification-notes.md`

#### Story 1.2: Delete in one batch and prove compilation
**As a** maintainer, **I want** the dead files gone together and the build to prove it, **so that** grep's blind spot (type-inferred use) is closed by the compiler.
**Acceptance Criteria**:
- AC-2: all 24 `DeadFile`s (and 2 READMEs) are deleted; `//kmp:business_tests` has 0 failures.
  - *Given* `EditorViewModel.kt` (`viewmodel/`), `PersistenceManager.kt` (`persistence/`) and `EditorModels.kt` (root) exist, *When* `git rm` removes all 26 paths and `bazel test //kmp:business_tests` runs, *Then* `git ls-files .../editor | wc -l` prints `6` and the test target reports `PASSED`.
- AC-5: `format/FormatModels.kt` and `format/IFormatProcessor.kt` are deleted.
  - *Given* both files exist only for `IEditor.kt`, *When* `IEditor.kt` and the two format files are removed in the same batch, *Then* `test ! -e` succeeds for both and `format/` no longer exists.
- AC-1: no reference to a deleted package remains outside `editor/`.
  - *Given* the post-deletion tree, *When* the AC-1 grep runs for `persistence|viewmodel|text|state|blocks|input|format`, *Then* all seven print zero lines.
**Files**: the 26 paths listed in Task 1.2a.

##### Task 1.2a: `git rm` the 24 `.kt` files and 2 `README.md` (XS)
- Under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/`: `blocks/{BlockOperations,BlockTreeOperations,IBlockOperations}.kt`, `format/{FormatModels,IFormatProcessor}.kt`, `input/InputHandling.kt`, `persistence/{ConflictResolution,IDataSynchronizer,IPersistenceManager,PersistenceErrorHandler,PersistenceIntegration,PersistenceManager,PersistenceModels,PersistenceOptimizations}.kt` + `persistence/README.md`, `state/EditorState.kt` + `state/README.md`, `text/{DefaultTextOperations,ITextOperations,OptimizedTextOperations,TextModels,TextOperations}.kt`, `viewmodel/EditorViewModel.kt`, and root `Editor.kt`, `EditorModels.kt`, `IEditor.kt`.
- Do not touch `commands/` or the two `KeeperTest`s.
- Use explicit paths (`git rm <path>...`), never `git add -A`.
- Files: the 26 paths above.

##### Task 1.2b: Compile proof for JVM and Android (XS agent work; wall-clock blocker: compile)
- `timeout 40m ./gradlew :kmp:compileKotlinJvm :kmp:compileDebugKotlinAndroid` with `export ANDROID_HOME=$HOME/Android/Sdk` (worktrees have no `local.properties`).
- `timeout 30m bazel build //kmp/src/jvmMain/kotlin:jvm_main_lib`.
- Pass: both exit 0. A failure naming a deleted symbol means a type-inferred consumer exists: stop, restore that file, and report (do not paper over it).
- wasmJs: `gradle.properties` sets `enableJs=true`, so also run `timeout 40m ./gradlew :kmp:compileKotlinWasmJs` (confirm the task name with `./gradlew :kmp:tasks --all | grep -i wasmJs`). If it can't run here, record why. iOS needs macOS and is left to CI (ci-ios.yml); say so in the notes.
- AC-1 grep gap: the import grep cannot see same-package use of the root files (`Editor`, `TextFormat`, `FormatState`, ...), which need no import. The compile above is what closes it.

##### Task 1.2c: Post-deletion AC-1 and AC-5 checks (XS)
- Re-run the AC-1 grep and the census; run the `test ! -e` checks; record in notes.

#### Story 1.3: Clean references to deleted files
**As a** maintainer, **I want** stale suppressions and doc rows removed, **so that** the baseline and ranking doc do not describe code that no longer exists.
**Acceptance Criteria**:
- `baseline.xml` has no `<ID>` naming a deleted class and still has line 103.
  - *Given* `baseline.xml` lists `LargeClass:PersistenceManager.kt$PersistenceManager : IPersistenceManager`, *When* the 8 stale entries are removed, *Then* `grep -c '<ID>'` drops by exactly 8 and `./gradlew :kmp:detekt` exits 0.
- `hotspot-ranking.md` no longer lists `editor/viewmodel/EditorViewModel.kt`.
  - *Given* line 67 `| editor/viewmodel/EditorViewModel.kt | 11 | 8 | 88 | pending |`, *When* the row is removed, *Then* the table renders with the neighbouring rows intact.
**Files**: `kmp/config/detekt/baseline.xml`, `docs/reference/hotspot-ranking.md`

##### Task 1.3a: Remove 10 stale detekt baseline entries (XS)
- Verified count is **10**, not 8 (pre-mortem P1, re-checked): lines 41, 46, 104, 110, 111, 112, 121, 122, 123, 131 of `kmp/config/detekt/baseline.xml` — `EditorViewModel` (x2), `PersistenceManager` (x2), `BlockOperations`, `DefaultTextOperations`, `EditorStateManager`, `OptimizedTextOperations`, `PersistenceErrorHandler`, `TextOperations`. Keep `AnnotationEditorViewModel` (live). Line numbers drift: locate by content, not number.
- Verify: no `<ID>` in the baseline names a `.kt` file absent from `git ls-files`, and `timeout 30m ./gradlew :kmp:detekt` exits 0.

##### Task 1.3b: Remove the hotspot-ranking row (XS)
- Edit `docs/reference/hotspot-ranking.md`; leave the three `docs/tasks/*.md` files alone (see Tech Debt Disposition).

---

## Phase 2: Direct tests for `editor.commands`

### Epic 2: Cover `EditorCommand`, `CommandSystem`, `CommandRegistry`, `CommandManager`, `EssentialCommands`
**Goal**: AC-3 (`BranchCoverage` of `dev/stapler/stelekit/editor/commands` at least 30%) and AC-4.

All three files: package `dev.stapler.stelekit.editor.commands`, path `kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands/`, `kotlin.test` assertions only (`kotest-assertions-core` is not on Bazel's `business_tests` deps), `kotlinx.coroutines.test.runTest`. Prefer `assertEquals(CommandResult.Error(...), result)` over field-by-field checks where the whole value is the contract; it exercises data-class `equals` honestly.

#### Story 2.1: `CommandManagerTest` — availability permutations and manager paths
**As a** maintainer, **I want** `EditorCommand.isAvailable` and `CommandManager.executeCommand`/`getAvailableCommands` pinned, **so that** palette filtering and notification mapping cannot regress silently.
**Acceptance Criteria**:
- `isAvailable` honours `enabled`, `hidden`, `condition`, `requiresSelection`, `requiresBlock`, `requiresPage`.
  - *Given* an `EditorCommand` with `CommandConfig(requiresSelection = true)` and `CommandContext(currentText = "hello", selectionStart = 0, selectionEnd = 0)`, *When* `isAvailable(context)` is called, *Then* it returns `false`; with `selectionEnd = 5` it returns `true`.
- `executeCommand` returns `Success`, `Error`, and unknown-id `Error`, and notifies accordingly.
  - *Given* a `CommandManager` whose `notificationManager` records calls, *When* `executeCommand("media.image")` runs, *Then* the result is `CommandResult.Success("Image attachment requested", mapOf("action" to "attachImage"))` and one `("Image attachment requested", NotificationType.SUCCESS, 3000L)` is recorded.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands/CommandManagerTest.kt`

##### Task 2.1a: Availability tests (S)
Helper: `private fun command(id: String = "t", config: CommandConfig = CommandConfig(), condition: (CommandContext) -> Boolean = { true }, execute: suspend (CommandContext) -> CommandResult = { CommandResult.Success() }) = EditorCommand(id, CommandType.CUSTOM, id, config = config, condition = condition, execute = execute)`.
Named cases (each asserts one flag in isolation, then its pass counterpart):

| # | Config / context | Expected `isAvailable` |
|---|------------------|------------------------|
| 1 | defaults, `CommandContext()` | `true` |
| 2 | `enabled = false` | `false` |
| 3 | `hidden = true` | `false` |
| 4 | `condition = { false }` | `false` |
| 5 | `condition = { it.currentText == "x" }`, text `"x"` then `"y"` | `true` then `false` |
| 6 | `requiresSelection`, `(0,0)` / `(5,3)` reversed / `(0,5)` | `false` / `false` / `true` |
| 7 | `requiresBlock`, `currentBlockId = null` / `"b1"` | `false` / `true` |
| 8 | `requiresPage`, `currentPageId = null` / `"p1"` | `false` / `true` |
| 9 | all three `requires*` with exactly one missing (3 variants) | `false` each |
| 10 | all three `requires*` all present | `true` |

Truth table: loop over all 512 combinations of `(enabled, hidden, conditionResult, reqSel, reqBlock, reqPage, hasSel, hasBlock, hasPage)`; build the command and a context (`selectionEnd = 5` iff `hasSel`, `currentBlockId = "b1"` iff `hasBlock`, `currentPageId = "p1"` iff `hasPage`); expected is stated as "no unmet precondition": `enabled && !hidden && conditionResult && (!reqSel || hasSel) && (!reqBlock || hasBlock) && (!reqPage || hasPage)`. Assert with a failure message carrying the combination.
`executeWithContext`: available runs `execute` (counter becomes 1, returns its result); unavailable returns `CommandResult.Error("Command not available in current context")` and counter stays 0.

##### Task 2.1b: `CommandManager` tests (S)
Setup per test: `runTest { val events = mutableListOf<Triple<String, NotificationType, Long?>>(); val m = CommandManager(backgroundScope) { msg, t, d -> events += Triple(msg, t, d) }; advanceUntilIdle() ... }`. All commands below are real `EssentialCommands`.

| # | Call | Expected result | Expected notification |
|---|------|-----------------|-----------------------|
| 1 | `executeCommand("media.image")` | `Success("Image attachment requested", mapOf("action" to "attachImage"))` | `(msg, SUCCESS, 3000)` |
| 2 | `executeCommand("does.not.exist")` | `Error("Command not found: does.not.exist")` | `(msg, ERROR, 5000)` |
| 3 | `executeCommand("text.bold")`, default context (hidden by `requiresSelection`) | `Error("Command not available in current context")` | `(msg, ERROR, 5000)` |
| 4 | `executeCommand("text.bold", CommandContext(currentText = "hello world", selectionStart = 0, selectionEnd = 5))` | `Success` with `data["formattedText"] == "**hello**"` | `("Text formatted as bold", SUCCESS, 3000)` |
| 5 | `executeCommand("text.bold", CommandContext(currentText = "hi", selectionStart = 0, selectionEnd = 99))` (available, `substring` throws) | `Error` whose `message` starts with `"Failed to format text as bold:"` and `exception != null` | `(msg, ERROR, 5000)` |
| 6 | `executeCommand("block.toggle-todo")` with `CommandContext(currentBlockId = "b1")`, but command is `hidden = true` | `Error("Command not available in current context")` | ERROR |
| 7 | `executeCommand("system.help")` hidden: same availability error | `Error` | ERROR |
| 8 | `CommandManager.create(backgroundScope)` with no callback, `executeCommand("media.image")` | `Success` | no callback, no throw (covers `notificationManager == null`) |
| 9 | `getAvailableCommands()` default context | ids `== setOf("media.image")` | n/a |
| 10 | `getAvailableCommands(CommandContext(currentText = "hello", selectionStart = 0, selectionEnd = 5))` | ids `== setOf("media.image","text.bold","text.italic","text.code","text.strikethrough","text.highlight")` | n/a |

Note case 6/7 reflect real behaviour: `hidden` commands are not executable through `executeWithContext`. If an assertion disagrees with observed behaviour, record the observed value and report; do not change production code.
Accepted gaps (documented, not faked): `CommandManager.handleResult` `Partial` branch and `CommandManager.executeCommand` `catch (Exception)` are unreachable through `EssentialCommands` (no essential command returns `Partial`; `CommandSystem` swallows exceptions first). `Partial` is covered at the `CommandSystem` layer in Task 2.2a.

#### Story 2.2: `CommandSystemRegistryTest`
**As a** maintainer, **I want** `CommandSystem` and `CommandRegistry` exercised with custom commands, **so that** the `async`, history-cap, overwrite and exception branches `CommandManager` cannot reach are covered.
**Acceptance Criteria**:
- History is capped at 100, newest first.
  - *Given* a `CommandSystem(CommandRegistry())` with command `"c"` registered, *When* `executeCommand("c", ctx)` runs 101 times with `currentText = "run-$i"`, *Then* `getHistory().value.size == 100`, the first entry's `context.currentText == "run-100"`, and no entry has `"run-0"`.
- A throwing command becomes an `Error`, and `CancellationException` propagates.
  - *Given* a command whose `execute` throws `IllegalStateException("boom")`, *When* executed, *Then* the result is `CommandResult.Error("Unexpected error: boom", <the exception>)`.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands/CommandSystemRegistryTest.kt`

##### Task 2.2a: Registry and system tests (S)
Registry (`runTest`, `val r = CommandRegistry()`):
1. `register(a)`; `getCommand("a") == a`; `getCommand("zzz") == null`.
2. `register` same id twice with different labels: `getAllCommands().size == 1` and label is the second (overwrite branch).
3. `unregister("a")` existing then `getCommand("a") == null`; `unregister("never")` does not throw and registry unchanged (both branches).
4. `registerAll(listOf(a, b, c))` then `registerAll(emptyList())`; size 3.
5. `getCommandsByType(CommandType.TEXT)` returns only TEXT commands (register one TEXT, one BLOCK).
6. `getAvailableCommands(ctx)` returns only commands whose `isAvailable(ctx)` is true (one hidden, one `requiresSelection`).

System (`CommandSystem(registry)`):
7. Unknown id: `Error("Command not found: nope")`; history stays empty.
8. `async = false` command returns its `Success("ok")`; `async = true` command returns the same (covers both `if (command.config.async)` arms).
9. Result passthrough for `Partial("m", 2, 5)`, `Nothing`, `Error("e", null, "E1")`: returned unchanged and each recorded in history.
10. `IllegalStateException("boom")` becomes `Error("Unexpected error: boom", it)`, history not appended for the failed run.
11. `execute` throws `CancellationException("c")` with `config.async = true`: `assertFailsWith<CancellationException>`.
12. Unavailable command (`hidden = true`) executed through the system returns `Error("Command not available in current context")` and is still recorded in history (the system records whatever `executeWithContext` returned).
13. History cap and order (101 runs, see AC).
14. `clearHistory()` empties; `getCommand` and `getAvailableCommands` delegate to the registry.

#### Story 2.3: `EssentialCommandsTest`
**As a** maintainer, **I want** the built-in command set pinned, **so that** the Phase H guarantee "only `media.image` is palette-visible" is a test, not a comment.
**Acceptance Criteria**:
- Palette visibility.
  - *Given* `PaletteContext` (`currentText = ""`, `selectionStart = 0`, `selectionEnd = 0`, `currentBlockId = "page-uuid"`), *When* filtering `EssentialCommands.getAll()` by `isAvailable`, *Then* the ids are exactly `{"media.image"}`.
- Formatting output.
  - *Given* `CommandContext(currentText = "hello world", selectionStart = 0, selectionEnd = 5)`, *When* `text.italic` executes, *Then* `data["formattedText"] == "*hello*"`.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands/EssentialCommandsTest.kt`

##### Task 2.3a: Built-in command tests (S)
Because `EssentialCommands.kt` is mostly object initialisers, its branches come from the lambdas' bodies. These are driven by calling each command's `execute` directly (bypassing `isAvailable`) across a context matrix, not by hand-picking per command:
- Contexts: `C0 = CommandContext()`; `Csel = ("hello world", 0, 5)`; `Cblock = ("x", currentBlockId = "b1", currentPageId = "p1")`; `Cbad = ("hi", 0, 99)` (forces `substring` to throw); heading texts `"plain"`, `"## a"`, `"### a"`, `"#### a"`, `"##### a"`, `"###### a"`, and multi-line `"first\nsecond"` with cursor at `0` and at `8`.
- Sweep test: for every `cmd` in `getAll()` and every context, `cmd.execute(ctx)` returns a `CommandResult` and never throws (an `Error` result is acceptable and expected for `Cbad`).

Targeted cases (assert on `data` / `message`):

| # | Command and context | Expected |
|---|---------------------|----------|
| 1 | `getAll()` | ids unique (`size == toSet().size`); every id non-blank; 24 entries is not asserted (brittle), uniqueness and the palette set below are the contract |
| 2 | `PaletteContext` availability | `{"media.image"}` |
| 3 | `Csel` availability | `{"media.image","text.bold","text.italic","text.code","text.strikethrough","text.highlight"}` |
| 4 | `text.bold/italic/code/strikethrough/highlight` on `Csel` | `formattedText` = `**hello**`, `*hello*`, `` `hello` ``, `~~hello~~`, `==hello==`; `selectionStart == 0`, `selectionEnd == 5` |
| 5 | those five on `Cbad` | `Error` with message starting `Failed to` and `exception != null` |
| 6 | `text.link` on `Csel` / on `C0` | `formattedText == "[hello](https://example.com)"` / `"[link text](https://example.com)"`, `data["requiresUrlInput"] == true` (both arms of the selection `if` and of `isNotEmpty`) |
| 7 | `text.heading` on `"plain"`, `"## a"`, `"### a"`, `"#### a"`, `"##### a"`, `"###### a"` (cursor 0) | message `"Heading level set to N"` with N = 1, 2, 3, 4, 5, 6 (covers every `when` arm) |
| 8 | `text.heading` on `"first\nsecond"` cursor 0 / cursor 8 | `data["lineStart"],["lineEnd"]` = `0,5` / `6,12` (covers `lineEnd == -1` both arms) |
| 9 | `block.new`, `block.delete`, `block.move-up`, `block.move-down`, `block.indent`, `block.outdent` on `Cblock` / `C0` | `Success`, `data["blockId"] == "b1"` / `""` (both arms of each `?:`) |
| 10 | `block.toggle-todo` | `CommandResult.Nothing` |
| 11 | `navigation.search`, `navigation.goto-page`, `navigation.back`, `navigation.forward` | `Success`, `data["action"]` non-null (read exact keys from source when writing) |
| 12 | `media.image` | `Success("Image attachment requested", mapOf("action" to "attachImage"))` |
| 13 | `system.save`, `system.undo`, `system.redo`, `system.settings`, `system.help` | `Success` with non-null message; `system.save` `data["action"] == "save"` |
| 14 | `system.save` `config.async` | `true` (the one `async = true` built-in) |

Heading note: assert the level in `message`, not `newLine`; the current `newLine` repeats an existing prefix (see Tech Debt Disposition).

#### Story 2.4: Register, measure, close the gap
**As a** maintainer, **I want** the new classes to run under Bazel and the coverage number measured, **so that** AC-3 is a reported measurement.
**Acceptance Criteria**:
- Registered and running.
  - *Given* `CommandManagerTest`, `CommandSystemRegistryTest`, `EssentialCommandsTest` exist in `businessTest`, *When* `bazel test //kmp:business_tests` runs, *Then* `AllBusinessTestsCompletenessTest` passes and the three classes appear in the test log.
- Coverage threshold.
  - *Given* the baseline of `editor.commands` branches recorded in Task 1.1c (for example `covered=0 / total=<N>`), *When* `jacocoTestReport` runs with the three classes, *Then* the package `BRANCH` counter shows `covered / (covered + missed) >= 0.30`.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt`

##### Task 2.4a: Register the three classes (XS)
- Add `import dev.stapler.stelekit.editor.commands.CommandManagerTest` (and the other two) next to the existing `editor.LinkInsertionTest` import (line 38), and the three `::class` entries in `@Suite.SuiteClasses` near `LinkInsertionTest::class` (line 237).
- Verify: `timeout 30m bazel test //kmp:business_tests` PASSED (this is the AC-2 and AC-4 run too).

##### Task 2.4b: Measure `BranchCoverage` (S; wall-clock blocker: Gradle + JaCoCo)
- `timeout 40m ./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.editor.commands.*' :kmp:jacocoTestReport` (filter keeps UI tests out; use the `jvm-display-check.sh` wrapper if Task 1.1c showed it is needed).
- Extract with the script below; record package and per-class `missed/covered`. This number is a lower bound: it excludes `CommandPaletteFocusRetentionTest` and every other test that touches `commands`.
- Pass: `covered / (covered + missed) >= 0.30`.

##### Task 2.4c: Close the gap (S, conditional on 2.4b < 30%)
1. Read the per-class table; target classes with the most `missed` first (behaviour-bearing: `CommandTypes`, `CommandSystem`, `CommandManager`, `EssentialCommands$...$1` lambdas).
2. If the only large `missed` counts left are generated data-class `equals`/`hashCode`/`copy` of `CommandContext`, `CommandConfig`, `EditorCommand`, `CommandResult.*`, `CommandHistoryEntry`: add one small value-semantics test (equal field values give equal `==` and equal `hashCode`; each single differing field breaks equality). That is a legitimate contract for data classes the registry and history rely on, and is labelled as such in the test and the notes. Decision (resolving the reviewer's padding concern): use this lever only after the behavioural cases are in and measured, and report the final number with and without it, so the reader can see how much of the 30% is behavioural.

**Pre-mortem patches (P1/P2):**
- Coverage before/after compare different sets: the item's 6.8% is the whole `editor/` tree including dead files. Record whole-`editor` and `editor.commands` separately in `verification-notes.md`.
- `bazel-ci.yml:146` runs `//kmp:business_tests` on every PR, so a flaky new test blocks unrelated PRs. No wall-clock assertions; `system.save` returns `Clock.System.now()`, so never assert its whole result; run history tests sequentially.
- AC-4's `./gradlew testDebugUnitTest` alternative does not execute `businessTest` classes (those run under `jvmTest`/Bazel). It proves no Android regression only; the new tests are proven by `bazel test //kmp:business_tests`.
- Additional unreachable arms to list in the F-1 fallback: `CommandManager.handleResult`'s `Nothing` arm and the `Success` null-message arm (`block.toggle-todo` is hidden and returns `Error`).
- Host is contended: before heavy runs check free memory and running Gradle/Bazel servers; cap cold runs at 60m.
- Also update `kmp/README.md:34` (still describes `editor/` as "state, input handling, persistence").

**Observations logged, not fixed (out of scope):** after deletion `ICommandSystem` and `ICommandRegistry` have a single implementer and no consumers; the `editor.commands` name sits beside an existing top-level `command/` package and is easy to confuse with it; `docs/archive/tasks/*` (2 files) and `docs/tasks/*` cite deleted files as reference code and are left as historic.
3. Do not edit production code, do not add JaCoCo excludes, do not add assertion-free tests.
4. If still under 30% after step 2, stop and report the measured number and the remaining unreachable branches (heading `newLevel > 0` else arm, `CommandManager` outer `catch`, `Partial` notification via the manager). AC-3 is then "measured, not met" and says so.

---

## Phase 3: Verify and close

### Epic 3: AC-4 and hygiene
**Goal**: no new failures; clean, minimal commits; Bazel servers stopped.

#### Story 3.1:
**As a** maintainer, **I want** the final run compared to the baseline, **so that** "zero new failures" is a diff.
**Acceptance Criteria**:
- AC-4.
  - *Given* the Task 1.1b baseline lists its failing targets (possibly none), *When* the same commands re-run post-change, *Then* the failing set is a subset of the baseline set.
**Files**: `project_plans/editor-subsystem-coverage/implementation/verification-notes.md`

##### Task 3.1a: Final test runs (XS agent work; wall-clock blockers: Bazel, display)
- `timeout 60m scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY` if the display check passed in 1.1b; otherwise `timeout 40m ./gradlew testDebugUnitTest` (Robolectric, headless).
- `timeout 30m bazel test //kmp:business_tests`.
- Record results beside the baseline. A bare, un-wrapped `jvm_tests` UI failure is "unverified", not a regression (CLAUDE.md).

##### Task 3.1b: Hygiene and commits (XS)
- `bazel shutdown` from this worktree (teardown does not stop its server).
- `git status`, then stage only touched paths by name: the 26 deleted files, the three test files, `AllBusinessTests.kt`, `kmp/config/detekt/baseline.xml`, `docs/reference/hotspot-ranking.md`, and the `project_plans/editor-subsystem-coverage/` files. Never `git add -A` / `.` (the tree has an untracked `project_plans/editor-subsystem-coverage/` that is intentional here, but other sessions share the repo).
- Suggested commits: (1) `refactor(editor): delete 24 unreachable editor.* files`, (2) `test(editor): cover editor.commands`, (3) `chore: drop stale detekt baseline entries and hotspot row`. Push only this branch. Any PR is `gh pr create --draft`.

---

## Acceptance Criteria traceability

| AC | Task(s) | Verification command | Pass condition |
|----|---------|----------------------|----------------|
| AC-1 | 1.1a, 1.2c | For each `p` in `persistence viewmodel text state blocks input format`: Grep pattern `stelekit\.editor\.<p>\b` over `kmp androidApp benchmarks`, excluding `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/**`. Census: Grep `stelekit\.editor\.[A-Za-z]+` outside `editor/` | All seven return 0 matches. Census second segments are a subset of `{commands, LinkInsertionTest, ImageAttachCallbackContractTest}` |
| AC-2 | 1.2a, 1.2b, 2.4a, 3.1a | `git ls-files kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor \| wc -l`; `git diff --name-only --diff-filter=D HEAD \| wc -l`; `timeout 30m bazel test //kmp:business_tests` | `6`; `26` for the deletion batch (plus the 8-line baseline edit is a modification, not a deletion); target `PASSED`, 0 failures |
| AC-3 | 1.1c, 2.1a-2.4c | Gradle JaCoCo command in 2.4b, then the extraction script | package `BRANCH` ratio `>= 0.30`, with the baseline and per-class table in the notes; otherwise "measured, not met" or "not measurable" with reason |
| AC-4 | 1.1b, 3.1a | `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests ...` or `./gradlew testDebugUnitTest` | Failing set is a subset of the baseline set (zero new failures) |
| AC-5 | 1.2a, 1.2c | `test ! -e kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/format/FormatModels.kt && test ! -e .../format/IFormatProcessor.kt` | exit 0 |

### Coverage extraction

```
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
r = ET.parse("kmp/build/reports/jacoco/jacoco.xml").getroot()
for p in r.findall("package"):
    if p.get("name") != "dev/stapler/stelekit/editor/commands":
        continue
    def br(e):
        c = next((x for x in e.findall("counter") if x.get("type") == "BRANCH"), None)
        return (int(c.get("covered")), int(c.get("missed"))) if c is not None else (0, 0)
    cov, mis = br(p)
    print(f"PACKAGE branch covered={cov} missed={mis} ratio={cov/max(cov+mis,1):.3f}")
    for c in p.findall("class"):
        k, m = br(c)
        if k + m:
            print(f"  {c.get('name').split('/')[-1]:60s} covered={k:4d} missed={m:4d}")
PY
```

Package-level `<counter>` elements are direct children of `<package>`; iterate only `findall("counter")` on it, not `.iter()`.

### If measured coverage is under 30%
Report the measured number, the per-class table and the unreachable list; mark AC-3 "measured, not met". Do not lower the bar, add excludes or pad with assertion-free tests.

### If JaCoCo cannot run locally
Report AC-3 as "not measurable here" with the exact error output and the command tried. Provide a traceability table (source branch site by `file:line` against the test case that reaches it) clearly labelled as a proxy, not a measurement. Offer the CI route (`jvmTest` + `jacocoTestReport` on a runner with a display) as the way to obtain the number. Everything else (AC-1, AC-2, AC-4, AC-5) still closes.

---

## Effort estimate

Bands and weights INFERRED (`ESTIMATION.md`); recalibrate after the first implementer run. CU = raw x (1 + 4 x output share).

| Story | Tasks | Cost class | Est. raw tokens | Output share | Est. CU | Wall-clock blocker |
|-------|-------|-----------|-----------------|--------------|---------|--------------------|
| 1.1 Baseline + JaCoCo spike | 3 | XS, XS, S | 60k | 5% | 72k | Cold Bazel run; Gradle JaCoCo run |
| 1.2 Delete + compile proof | 3 | XS x3 | 60k | 5% | 72k | JVM + Android compile |
| 1.3 Reference cleanup | 2 | XS x2 | 35k | 10% | 49k | detekt run |
| 2.1 `CommandManagerTest` | 2 | S, S | 110k | 15% | 176k | None |
| 2.2 `CommandSystemRegistryTest` | 1 | S | 50k | 15% | 80k | None |
| 2.3 `EssentialCommandsTest` | 1 | S | 60k | 15% | 96k | None |
| 2.4 Register, measure, close gap | 3 | XS, S, S (2.4c conditional, counted) | 120k | 10% | 168k | Gradle JaCoCo run |
| 3.1 Final verification + hygiene | 2 | XS x2 | 40k | 5% | 48k | `jvm_tests` needs a display |
| **Sum** | **17** | | **535k** | | **761k** | |

- Verification multiplier x1.5 (spec and quality review per story): about 1.14M CU; x2: about 1.52M CU. Size band: Medium (0.5-3M). Informational only.
- Critical path: 4 agent waves (W0 baseline, W1 parallel delete and three test files, W2 register then measure, W3 verify), plus up to 2 conditional gap-closing passes in W2 if the first measure is under 30%.
- Overrun checkpoint: pause and report if cumulative CU on completed stories exceeds the table by more than 25% (about 1.43M CU at the x1.5 multiplier).
- Wall-clock blockers: (1) cold Bazel build/test, owner: machine (no decision needed); (2) Gradle `jacocoTestReport` run, owner: machine; (3) X11 display or Xvfb for `jvm_tests`, owner: Tyler (`sudo pacman -S xorg-server-xvfb`, or accept Robolectric `testDebugUnitTest` plus `business_tests`); (4) Android SDK at `$HOME/Android/Sdk` for the Android compile, owner: machine; (5) CI queue and human review if a draft PR is opened, owner: Tyler.

## Bazel and git hygiene

- Wrap every Bazel invocation in `timeout <N>` (`.bazelrc` has `max_idle_secs=1800` but no wall-clock cap on actions) and run `bazel shutdown` from this worktree before ending the session (Task 3.1b).
- Run `scripts/jvm-display-check.sh` before treating any `jvmTest`/`jvm_tests` UI failure as real.
- Use `./gradlew`, never system `gradle`.
- Stage specific paths only; check `git status` first; push only `backlog/stelekit-editor-subsystem-dead-code`; PRs are drafts.
