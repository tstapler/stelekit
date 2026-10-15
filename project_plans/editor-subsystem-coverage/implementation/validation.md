# Validation Plan: editor-subsystem-coverage

**Date**: 2026-10-14
**Complexity**: 2
**Inputs**: `requirements.md`, `implementation/plan.md`, `adversarial-review.md`, `architecture-review.md`, `decisions/ADR-001-test-placement-businesstest.md`; every expected value re-read from `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/*.kt` at `8f4818d6`.

Paths below are relative to the worktree root. `COMMANDS = kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands`, `TESTS = kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands`.

Labels: **VERIFIED** = expected value read from the cited source line (or a command run during this validation). **HYPOTHESIS** = could not be confirmed from source alone; the implementer records the observed value and reports, never edits production code to match (constraint: tests only).

## Happy Path Scenario

Given the branch at `8f4818d6` with 24 unreachable `.kt` files under `editor/` and no direct tests for `editor.commands`, when the 26 dead paths are removed in one batch and the three new test classes are registered in `AllBusinessTests`, then `timeout 30m bazel test //kmp:business_tests` reports PASSED, `git ls-files COMMANDS/..` lists 6 files, and the JaCoCo `BRANCH` ratio for package `dev/stapler/stelekit/editor/commands` is at least 0.30.

## Test Stack

- **Unit**: `kotlin.test` (`@Test`, `assertEquals`, `assertFailsWith`, `assertTrue`) plus `kotlinx.coroutines.test.runTest`. `kotest-assertions-core` is not on the Bazel `business_tests` deps (plan, section Phase 2 preamble).
- **Integration / verification tier**: compile proof (Gradle + Bazel + wasmJs), the full `//kmp:business_tests` run, grep/`test -e` checks, JaCoCo extraction script. There is no data store or external call; the deletion task has no UI acceptance surface (no `design/ux.md` exists), so no UX tests are invented.
- **E2E / UX**: none. Not applicable (pure deletion + test-only code).
- **Doubles**: recording lambda `MutableList<Triple<String, NotificationType, Long?>>` for the `notificationManager` callback; `command(...)` test-data builder for custom `EditorCommand`s (plan, Pattern Decisions).

## Requirement to Test Mapping

AC-1 and AC-5 are properties of the file tree, AC-2 and AC-4 are properties of build/test runs, so their "unit" row is a check command rather than a JUnit case (marked "Check"). AC-3 carries all JUnit cases.

### Summary table

| Requirement | Test File / Command | Test Name or ID | Type | Scenario |
|-------------|---------------------|-----------------|------|----------|
| AC-1 no refs to deleted packages | AC-1 grep (V-02) | `ac1_should_FindZeroMatches_When_GrepForEachDeletedPackage` | Check (happy) | 7 packages, 0 lines each |
| AC-1 | root-symbol grep + census (V-03) | `ac1_should_FindNoSameRootSymbolUse_When_CompilerAndSymbolGrepRun` | Check (error path: grep blind spot) | same-package use needs no import |
| AC-1 | V-01 | `ac1_baseline_should_ShowMatchesOnlyInsideEditor_When_PreChange` | Integration | before-state recorded |
| AC-2 24 files deleted, business_tests 0 failures | V-04, V-08 | `ac2_should_LeaveSixFiles_And_PassBusinessTests` | Integration | counts + PASSED |
| AC-2 | V-05/V-06/V-07 | `ac2_should_Compile_When_DeadFilesRemoved` | Integration (error path: type-inferred consumer) | compile fails on any live reference |
| AC-3 `EditorCommand.isAvailable` | `CommandManagerTest.kt` | A-01..A-13 | Unit | happy and error permutations |
| AC-3 `CommandManager` paths | `CommandManagerTest.kt` | M-01..M-11 | Unit | success / error / unknown id |
| AC-3 `CommandSystem` + `CommandRegistry` | `CommandSystemRegistryTest.kt` | R-01..R-06, S-01..S-08 | Unit | custom commands, history cap, exception |
| AC-3 `EssentialCommands` | `EssentialCommandsTest.kt` | E-01..E-15 | Unit | palette set, formatting, heading arms |
| AC-3 coverage >= 30% | V-10 | `ac3_should_Report30PercentBranch_When_JacocoRuns` | Integration | measured, not met, or not measurable |
| AC-4 zero new failures | V-11, V-12 | `ac4_should_HaveFailingSetSubsetOfBaseline` | Integration | post vs pre diff |
| AC-5 format files deleted | V-13 | `ac5_should_BeAbsent_When_DeletionBatchApplied` | Check | `test ! -e` both |

### Unit cases: `CommandManagerTest.kt` (AC-3)

Package `dev.stapler.stelekit.editor.commands`; file `TESTS/CommandManagerTest.kt`. Helper (signature matches `CommandTypes.kt:101-111`): `command(id = "t", config = CommandConfig(), condition: (CommandContext) -> Boolean = { true }, execute: suspend (CommandContext) -> CommandResult = { CommandResult.Success() }) = EditorCommand(id, CommandType.CUSTOM, id, config = config, condition = condition, execute = execute)`. `EditorCommand.execute` has no default, so the helper must always pass it.

**Availability**: `EditorCommand.isAvailable(context: CommandContext): Boolean` (`CommandTypes.kt:115-122`) = `enabled && !hidden && condition(ctx) && (!requiresSelection || selectionStart < selectionEnd) && (!requiresBlock || currentBlockId != null) && (!requiresPage || currentPageId != null)`. All VERIFIED.

| ID | Test name | Setup | Expected | Scenario | Status |
|----|-----------|-------|----------|----------|--------|
| A-01 | `isAvailable_should_BeTrue_When_ConfigAndContextAreDefaults` | `command()`, `CommandContext()` | `true` | Happy | VERIFIED |
| A-02 | `isAvailable_should_BeFalse_When_EnabledIsFalse` | `CommandConfig(enabled = false)` | `false` | Error | VERIFIED |
| A-03 | `isAvailable_should_BeFalse_When_HiddenIsTrue` | `CommandConfig(hidden = true)` | `false` | Error | VERIFIED |
| A-04 | `isAvailable_should_BeFalse_When_ConditionReturnsFalse` | `condition = { false }` | `false` | Error | VERIFIED |
| A-05 | `isAvailable_should_FollowCondition_When_ConditionReadsContext` | `condition = { it.currentText == "x" }`; text `"x"` then `"y"` | `true` then `false` | Happy + error | VERIFIED |
| A-06 | `isAvailable_should_RequireNonEmptyOrderedRange_When_RequiresSelection` | `requiresSelection = true`; `(0,0)`, `(5,3)` reversed, `(3,3)`, `(0,5)` | `false`, `false`, `false`, `true` | Error arms + happy | VERIFIED (`selectionStart < selectionEnd`) |
| A-07 | `isAvailable_should_RequireBlockId_When_RequiresBlock` | `requiresBlock = true`; `currentBlockId = null` then `"b1"` | `false` then `true` | Error + happy | VERIFIED |
| A-08 | `isAvailable_should_RequirePageId_When_RequiresPage` | `requiresPage = true`; `currentPageId = null` then `"p1"` | `false` then `true` | Error + happy | VERIFIED |
| A-09 | `isAvailable_should_BeFalse_When_AnyOneOfThreeRequirementsIsMissing` | all three `requires*`, exactly one fact absent (3 variants) | `false` each | Error | VERIFIED |
| A-10 | `isAvailable_should_BeTrue_When_AllThreeRequirementsPresent` | all three `requires*`, selection `(0,5)`, block `"b1"`, page `"p1"` | `true` | Happy | VERIFIED |
| A-11 | `isAvailable_should_MatchPreconditionFormula_When_All512CombinationsEnumerated` | loop over `(enabled, hidden, conditionResult, reqSel, reqBlock, reqPage, hasSel, hasBlock, hasPage)`; `selectionEnd = 5` iff `hasSel` | equals `enabled && !hidden && conditionResult && (!reqSel \|\| hasSel) && (!reqBlock \|\| hasBlock) && (!reqPage \|\| hasPage)`; failure message names the combination | Exhaustive | VERIFIED (2^9 = 512) |
| A-12 | `executeWithContext_should_RunExecute_When_Available` | counter-incrementing `execute` returning `Success("ok")`, default context | result `Success("ok")`, counter `== 1` | Happy | VERIFIED (`CommandTypes.kt:127-129`) |
| A-13 | `executeWithContext_should_ReturnNotAvailableError_When_Unavailable` | `hidden = true`, counter `execute` | `CommandResult.Error("Command not available in current context")`, counter `== 0` | Error | VERIFIED (`CommandTypes.kt:131`) |

**Manager**: `CommandManager(scope: CoroutineScope, notificationManager: ((String, NotificationType, Long?) -> Unit)? = null)`, `suspend executeCommand(commandId: String, context: CommandContext = CommandContext()): CommandResult`, `suspend getAvailableCommands(context = CommandContext()): List<EditorCommand>`, `CommandManager.create(scope, notificationManager)` (`CommandManager.kt:19,39,57,98`). Setup per test: `runTest { val events = mutableListOf<Triple<String, NotificationType, Long?>>(); val m = CommandManager(backgroundScope) { msg, t, d -> events += Triple(msg, t, d) }; advanceUntilIdle(); ... }`. `advanceUntilIdle()` is required on every variant (the registry is seeded by `scope.launch` in `init`, `CommandManager.kt:30-33`); omit it and every id returns "Command not found".

| ID | Test name | Call | Expected result | Expected notification | Status |
|----|-----------|------|-----------------|-----------------------|--------|
| M-01 | `executeCommand_should_ReturnSuccessAndNotify_When_MediaImage` | `executeCommand("media.image")` | `Success("Image attachment requested", mapOf("action" to "attachImage"))` | `("Image attachment requested", SUCCESS, 3000L)`, one event | VERIFIED (`EssentialCommands.kt:695-698`, `CommandManager.kt:76`) |
| M-02 | `executeCommand_should_ReturnNotFoundAndNotify_When_IdUnknown` | `executeCommand("does.not.exist")` | `Error("Command not found: does.not.exist")` | `(msg, ERROR, 5000L)` | VERIFIED (`CommandSystem.kt:26`, `CommandManager.kt:80`) |
| M-03 | `executeCommand_should_ReturnNotAvailable_When_RequiresSelectionAndNoneGiven` | `executeCommand("text.bold")` | `Error("Command not available in current context")` | `(msg, ERROR, 5000L)` | VERIFIED |
| M-04 | `executeCommand_should_FormatBold_When_SelectionPresent` | `executeCommand("text.bold", CommandContext(currentText = "hello world", selectionStart = 0, selectionEnd = 5))` | `Success` with `message == "Text formatted as bold"`, `data["formattedText"] == "**hello**"`, `data["selectionStart"] == 0`, `data["selectionEnd"] == 5` | `("Text formatted as bold", SUCCESS, 3000L)` | VERIFIED (`EssentialCommands.kt:53-62`) |
| M-05 | `executeCommand_should_ReturnFormatError_When_SelectionOutOfBounds` | `executeCommand("text.bold", CommandContext(currentText = "hi", selectionStart = 0, selectionEnd = 99))`; available because `0 < 99`, `substring` throws | `Error` whose `message` starts with `"Failed to format text as bold:"` and `exception != null` (assert prefix and non-null only, not the JVM exception text) | `(msg, ERROR, 5000L)` | VERIFIED (`EssentialCommands.kt:63-67`); exception message text is JVM-specific, so not asserted |
| M-06 | `executeCommand_should_ReturnNotAvailable_When_CommandIsHidden` | `executeCommand("block.toggle-todo", CommandContext(currentBlockId = "b1"))` | `Error("Command not available in current context")` | ERROR | VERIFIED (`hidden = true`, `EssentialCommands.kt:530`) |
| M-07 | `executeCommand_should_ReturnNotAvailable_When_SystemHelpHidden` | `executeCommand("system.help")` | same `Error` | ERROR | VERIFIED (`EssentialCommands.kt:833`) |
| M-08 | `executeCommand_should_NotThrow_When_NoNotificationCallback` | `CommandManager.create(backgroundScope)`, `advanceUntilIdle()`, `executeCommand("media.image")` | `Success(...)` as M-01 | none (covers `notificationManager == null`) | VERIFIED (`CommandManager.kt:72`) |
| M-09 | `getAvailableCommands_should_ReturnOnlyMediaImage_When_DefaultContext` | `getAvailableCommands()` | `ids == setOf("media.image")` | n/a | VERIFIED (only non-hidden command without `requiresSelection`) |
| M-10 | `getAvailableCommands_should_IncludeFiveTextCommands_When_SelectionPresent` | `getAvailableCommands(CommandContext(currentText = "hello", selectionStart = 0, selectionEnd = 5))` | `ids == setOf("media.image","text.bold","text.italic","text.code","text.strikethrough","text.highlight")` | n/a | VERIFIED (`link`/`heading` are `hidden`) |
| M-11 | `executeCommand_should_ReturnNotFound_When_RegistryNotYetSeeded` (harness control, optional) | `CommandManager(backgroundScope)` then `executeCommand("media.image")` with no `advanceUntilIdle()` | `Error("Command not found: media.image")` | n/a | **HYPOTHESIS**: relies on `executeCommand` having no suspension point before the registry lookup so the queued `init` coroutine has not run under `StandardTestDispatcher`. If it passes it proves the `advanceUntilIdle()` in M-01..M-10 is load-bearing; if it fails, drop it and note the observed behaviour. |

Accepted gaps (unreachable through `EssentialCommands` + `CommandManager`, not faked): `handleResult` arms `Partial`, `Nothing` (`block.toggle-todo` is hidden, so it returns `Error`, never `Nothing`) and `Success` with `message == null` (every essential command supplies a message); `executeCommand` outer `catch (Exception)` (`CommandSystem` already swallows `Exception`, `CommandSystem.kt:69`); `getAvailableCommands` catch arm. `Partial` and `Nothing` are covered at the system layer (S-03).

### Unit cases: `CommandSystemRegistryTest.kt` (AC-3)

File `TESTS/CommandSystemRegistryTest.kt`. Constructors: `CommandRegistry()` (`CommandRegistry.kt:57`), `CommandSystem(registry: CommandRegistry)` (`CommandSystem.kt:13`). Use the concrete types, not the interfaces (architecture-review). All `suspend`; wrap in `runTest`.

| ID | Test name | Setup and action | Expected | Scenario | Status |
|----|-----------|------------------|----------|----------|--------|
| R-01 | `getCommand_should_ReturnRegistered_And_NullForUnknown` | `register(a)`; `getCommand("a")`, `getCommand("zzz")` | `a`, `null` | Happy + error | VERIFIED (`CommandRegistry.kt:89-91`) |
| R-02 | `register_should_Overwrite_When_SameIdRegisteredTwice` | register id `"a"` label `"one"` then label `"two"` | `getAllCommands().size == 1`, label `"two"` | Edge | VERIFIED (`:65-69`) |
| R-03 | `unregister_should_RemoveExisting_And_NotThrow_When_Unknown` | `unregister("a")` then `unregister("never")` | `getCommand("a") == null`; second call no exception, size unchanged | Happy + error | VERIFIED (`:79-87`) |
| R-04 | `registerAll_should_RegisterEach_And_AcceptEmptyList` | `registerAll(listOf(a,b,c))`, `registerAll(emptyList())` | size `3` | Happy + edge | VERIFIED |
| R-05 | `getCommandsByType_should_ReturnOnlyMatchingType` | one `CommandType.TEXT`, one `CommandType.BLOCK` | `getCommandsByType(TEXT)` has only the TEXT command | Happy | VERIFIED (`:97-99`) |
| R-06 | `getAvailableCommands_should_FilterByIsAvailable_When_ContextGiven` | one `hidden`, one `requiresSelection`, one plain; default `CommandContext()` | only the plain one | Error arms + happy | VERIFIED (`:101-103`) |
| S-01 | `executeCommand_should_ReturnNotFound_And_LeaveHistoryEmpty_When_IdUnknown` | `system.executeCommand("nope", CommandContext())` | `Error("Command not found: nope")`, `getHistory().value.isEmpty()` | Error | VERIFIED (`CommandSystem.kt:25-26`; early `return` skips `addToHistory`) |
| S-02 | `executeCommand_should_ReturnSuccess_When_AsyncAndNonAsync` | two commands, `async = false` and `async = true`, each returning `Success("ok")` | both return `Success("ok")` | Happy (both arms of `if (command.config.async)`) | VERIFIED (`:30-36`) |
| S-03 | `executeCommand_should_PassThroughAndRecord_When_PartialNothingOrError` | commands returning `Partial("m", 2, 5)`, `CommandResult.Nothing`, `Error("e", null, "E1")` | each returned unchanged (`assertEquals`) and each appended to history (size 3) | Happy/edge (covers `when` arms) | VERIFIED (`:49-66`) |
| S-04 | `executeCommand_should_WrapException_And_SkipHistory_When_ExecuteThrows` | `execute = { throw IllegalStateException("boom") }` | `Error("Unexpected error: boom", <same exception instance>)`; history empty | Error | VERIFIED (`:69-73`; exception thrown before `addToHistory`) |
| S-05 | `executeCommand_should_Rethrow_When_CancellationException` | `async = true`, `execute = { throw CancellationException("c") }` | `assertFailsWith<CancellationException>` | Error | VERIFIED (`:67-68`). `async = true` is chosen so the throw is not routed through `withContext`; **HYPOTHESIS**: with `async = false` the failure could be a different `CancellationException` instance, so do not add that variant. |
| S-06 | `executeCommand_should_ReturnNotAvailable_And_StillRecord_When_CommandHidden` | `hidden = true` command through the system | `Error("Command not available in current context")`; `getHistory().value.size == 1` | Error | VERIFIED (`executeWithContext` result is recorded by `addToHistory`) |
| S-07 | `history_should_CapAt100_NewestFirst_When_101Runs` | register `"c"`; run 101 times with `currentText = "run-$i"` (i = 0..100) | `size == 100`, `first().context.currentText == "run-100"`, no entry with `"run-0"` | Boundary | VERIFIED (`:92-100`) |
| S-08 | `clearHistory_should_Empty_And_Delegates_Should_ReachRegistry` | after S-07-style runs, `clearHistory()`; then `getCommand("c")`, `getAvailableCommands(ctx)` | history empty; `getCommand` returns the registered command; `getAvailableCommands` equals registry's filtered list | Happy | VERIFIED (`:76-89`) |

### Unit cases: `EssentialCommandsTest.kt` (AC-3)

File `TESTS/EssentialCommandsTest.kt`. Contexts: `C0 = CommandContext()`; `Csel = CommandContext(currentText = "hello world", selectionStart = 0, selectionEnd = 5)`; `Cblock = CommandContext(currentText = "x", currentBlockId = "b1", currentPageId = "p1")`; `Cbad = CommandContext(currentText = "hi", selectionStart = 0, selectionEnd = 99)`; `PaletteContext = CommandContext(currentText = "", selectionStart = 0, selectionEnd = 0, currentBlockId = "page-uuid")`. Reference by `EssentialCommands.TextFormatting.bold`, `EssentialCommands.BlockCommands.newBlock`, etc.; call `cmd.execute(ctx)` directly (bypasses `isAvailable`) unless the case says otherwise.

| ID | Test name | Input | Expected | Status |
|----|-----------|-------|----------|--------|
| E-01 | `getAll_should_HaveUniqueNonBlankIds` | `EssentialCommands.getAll()` | `ids.size == ids.toSet().size`; all non-blank. (Do not assert a count; currently 24 per `EssentialCommands.kt:856-891`, deliberately not pinned.) | VERIFIED |
| E-02 | `getAll_should_ExposeOnlyMediaImage_When_PaletteContext` | filter `getAll()` by `isAvailable(PaletteContext)` | ids `== setOf("media.image")` | VERIFIED (every other command is `hidden` or `requiresSelection` with `0,0`) |
| E-03 | `getAll_should_ExposeMediaImageAndFiveTextCommands_When_Selection` | filter by `isAvailable(Csel)` | ids `== setOf("media.image","text.bold","text.italic","text.code","text.strikethrough","text.highlight")` | VERIFIED |
| E-04 | `textFormatting_should_WrapSelection_When_SelectionPresent` | bold/italic/code/strikethrough/highlight on `Csel` | `formattedText` = `**hello**`, `*hello*`, `` `hello` ``, `~~hello~~`, `==hello==`; `selectionStart == 0`, `selectionEnd == 5`; messages `"Text formatted as bold"`, `"Text formatted as italic"`, `"Text formatted as code"`, `"Text formatted as strikethrough"`, `"Text highlighted"` | VERIFIED (`:54-60,87,120,153,186`) |
| E-05 | `textFormatting_should_ReturnError_When_SelectionOutOfBounds` | the five on `Cbad` | `Error`, `exception != null`, `message` starts with `"Failed to format text as bold:"`, `"Failed to format text as italic:"`, `"Failed to format text as code:"`, `"Failed to format text as strikethrough:"`, `"Failed to highlight text:"` respectively | VERIFIED (`:66,99,132,165,198`); note highlight's prefix differs |
| E-06 | `link_should_UseSelectionOrPlaceholder` | `text.link` on `Csel`; on `C0`; on `Cbad` | `formattedText == "[hello](https://example.com)"`; `"[link text](https://example.com)"`; both `data["requiresUrlInput"] == true`, message `"Link created"`; `Cbad` gives `Error` starting `"Failed to create link:"` | VERIFIED (`:223-245`; `Cbad` has `0 < 99` so `substring` throws) |
| E-07 | `heading_should_ReportNextLevel_When_ExistingPrefix` | `text.heading` with `currentText` `"plain"`, `"## a"`, `"### a"`, `"#### a"`, `"##### a"`, `"###### a"`, selection `0,0` | messages `"Heading level set to 1"` .. `"Heading level set to 6"` in that order | VERIFIED (`:272-286`; `(h+1) % 7`, h in 0..5). Assert the level in `message`, not `newLine` (the quirk `"## ## a"` is not enshrined). |
| E-08 | `heading_should_ComputeLineBounds_When_MultiLine` | `currentText = "first\nsecond"` with `selectionStart = selectionEnd = 0`, then `= 8` | `lineStart/lineEnd` `0,5` then `6,12` | VERIFIED (`:267-269`; both `lineEnd == -1` arms) |
| E-09 | `blockCommands_should_EchoBlockIdOrEmpty` | `block.new`, `.delete`, `.move-up`, `.move-down`, `.indent`, `.outdent` on `Cblock` and `C0` | `Success`; `data["blockId"] == "b1"` / `""`; extra keys: new `position == "after"`, move-up `direction == "up"`, move-down `"down"`, indent `level == "increment"`, outdent `"decrement"` | VERIFIED (`:330-336,362-366,393-399,424-428,456-460,487-491`) |
| E-10 | `toggleTodo_should_ReturnNothing` | `block.toggle-todo` on `Cblock` | `CommandResult.Nothing` (`assertEquals(CommandResult.Nothing, result)`) | VERIFIED (`:541`) |
| E-11 | `navigationCommands_should_ReturnActionKeys` | four navigation commands on `C0` | `Success`, `data["action"]` = `"openSearch"` (plus `data["query"] == ""`), `"openPageSelector"`, `"navigateBack"`, `"navigateForward"`; messages `"Search opened"`, `"Go to page opened"`, `"Navigated back"`, `"Navigated forward"` | VERIFIED (`:571-577,599-603,626-630,653-657`) |
| E-12 | `mediaImage_should_ReturnAttachImageAction` | `media.image` on `C0` | `Success("Image attachment requested", mapOf("action" to "attachImage"))` | VERIFIED (`:695-698`) |
| E-13 | `systemCommands_should_ReturnActionKeys` | `system.save/undo/redo/settings/help` on `C0` | `Success`; `data["action"]` = `"save"`, `"undo"`, `"redo"`, `"openSettings"`, `"openHelp"`; messages `"Document saved"`, `"Undo performed"`, `"Redo performed"`, `"Settings opened"`, `"Help opened"`; `system.save` also has non-null `data["timestamp"]` (assert presence only) | VERIFIED (`:729-734,757-761,784-788,811-815,838-842`) |
| E-14 | `systemSave_should_BeTheOnlyAsyncCommand` | `getAll().filter { it.config.async }.map { it.id }` | `listOf("system.save")` | VERIFIED (only `async = true` at `:725`) |
| E-15 | `execute_should_NeverThrow_When_SweepAllCommandsAcrossContexts` | for each `cmd` in `getAll()`, each of `C0`, `Csel`, `Cblock`, `Cbad` and the heading texts: `cmd.execute(ctx)` | returns a `CommandResult`; no exception (an `Error` result is acceptable and expected for `Cbad`) | VERIFIED (every non-trivial lambda has `catch (e: Exception)`; `toggleTodo` and `image` have no body that can throw) |

### Unit tier counts

| File | Cases |
|------|-------|
| `CommandManagerTest.kt` | 24 (A-01..A-13, M-01..M-11) |
| `CommandSystemRegistryTest.kt` | 14 (R-01..R-06, S-01..S-08) |
| `EssentialCommandsTest.kt` | 15 (E-01..E-15) |
| **Total** | **53** |

## Integration and Verification Cases (compile proof + full business run)

Every case has an exact command and a pass condition. Wrap Bazel in `timeout`; run `bazel shutdown` from the worktree last. Gradle commands use `./gradlew` (never system `gradle`) with `export ANDROID_HOME=$HOME/Android/Sdk`.

| ID | AC | Command | Pass condition | Failure meaning / fallback |
|----|----|---------|----------------|----------------------------|
| V-01 | AC-1 (baseline) | `find kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor -type f \| sort \| wc -l`; Grep `stelekit\.editor\.(persistence\|viewmodel\|text\|state\|blocks\|input\|format)\b` over `kmp androidApp benchmarks` | file count `32` (26 outside `commands/` + 6 inside; recount, do not trust); matches exist only under `editor/` | A different count means the plan's list is stale: stop and re-derive the deletion list |
| V-02 | AC-1 | for each `p` in `persistence viewmodel text state blocks input format`: `grep -rEn "stelekit\.editor\.$p\b" kmp androidApp benchmarks --include='*.kt' --include='*.kts' --include='*.bazel' --include='*.xml' \| grep -v '^kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/'` (or the Grep tool with the same pattern and exclusion) | each of the 7 prints 0 lines. Pre-change run during this validation printed 0 lines outside `editor/` (VERIFIED) | any line means a live consumer: restore that file and report |
| V-03 | AC-1 (blind spot) | (a) Grep `\b(TextFormat\|FormatState\|CursorState\|SearchState\|SearchResult\|TextAffinity\|ValidationError\|ValidationSeverity\|IFormatProcessor\|IEditor)\b` over `kmp androidApp benchmarks` excluding the deleted dirs, inspected by hand; (b) census Grep `^import dev\.stapler\.stelekit\.editor\.` outside `editor/` | (b) second segments subset of `{commands}` (the two keeper tests are in package `editor`, not imports); (a) no hit refers to the deleted types. The compiler (V-05..V-07) is the authoritative proof for same-package use | symbol hits that are unrelated homonyms must be listed as such in the notes |
| V-04 | AC-2 | `git ls-files kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor \| wc -l`; `git diff --cached --name-only --diff-filter=D \| wc -l` (after `git rm`) | `6`; `26` (24 `.kt` + `persistence/README.md` + `state/README.md`) | `6` with `26` confirms nothing extra was removed; `commands/` untouched |
| V-05 | AC-2 | `export ANDROID_HOME=$HOME/Android/Sdk && timeout 40m ./gradlew :kmp:compileKotlinJvm :kmp:compileDebugKotlinAndroid` | exit 0 | An `Unresolved reference` naming a deleted symbol = type-inferred consumer grep missed; restore that file, report (never paper over) |
| V-06 | AC-2 | `timeout 30m bazel build //kmp/src/jvmMain/kotlin:jvm_main_lib` | exit 0 | same as V-05 |
| V-07 | AC-2 | confirm task with `./gradlew :kmp:tasks --all \| grep -i compileKotlinWasmJs`, then `timeout 40m ./gradlew :kmp:compileKotlinWasmJs` (`enableJs=true` in `gradle.properties`) | exit 0 | If not runnable here, record the reason; iOS is CI-only (macOS), say so in notes |
| V-08 | AC-2 | `timeout 30m bazel test //kmp:business_tests` (cold cache may need `timeout 60m`) | exit 0, target `PASSED`, 0 failures | A timeout is "no baseline", not a failure and not a pass; retry once with `timeout 60m`, record cold-cache cause |
| V-09 | AC-3 | after V-08: confirm the three classes ran. HYPOTHESIS path: `grep -c 'classname="dev.stapler.stelekit.editor.commands.CommandManagerTest"' bazel-testlogs/kmp/business_tests/test.xml` (and the other two); `AllBusinessTestsCompletenessTest` is part of the same run | each count `> 0`; completeness test passes | Count 0 with PASSED = class compiled but not in `@Suite.SuiteClasses` (imports at `AllBusinessTests.kt:38`-adjacent, entries near `:237`); path/classname format is a HYPOTHESIS, fall back to `bazel-testlogs/.../test.log` |
| V-10 | AC-3 | `timeout 40m ./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.editor.commands.*' :kmp:jacocoTestReport` (wrap in `scripts/jvm-display-check.sh --` if a display is needed), then the "Coverage extraction" script from `plan.md` over `kmp/build/reports/jacoco/jacoco.xml` | line `PACKAGE branch covered=<c> missed=<m> ratio=<r>` with `r >= 0.300` | See fallbacks F-1 and F-2 |
| V-11 | AC-4 | If `scripts/jvm-display-check.sh` reports a display or Xvfb: `timeout 60m scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`; else `timeout 40m ./gradlew testDebugUnitTest` | failing set after change is a subset of the baseline failing set recorded in V-pre (Task 1.1b) | See F-3 |
| V-12 | AC-4 | `./gradlew :kmp:jvmTest --tests '*CommandPaletteFocusRetentionTest'` (display needed) | PASSED, same as baseline | The one existing consumer of `editor.commands` in tests; if no display, record "unverified", it is compile-covered by V-05 |
| V-13 | AC-5 | `test ! -e kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/format/FormatModels.kt && test ! -e kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/format/IFormatProcessor.kt; echo $?` | prints `0`; `format/` directory absent | non-zero = file still present |
| V-14 | AC-2 | `git ls-files kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor`; Grep `LinkInsertionTest\|ImageAttachCallbackContractTest` in `AllBusinessTests.kt` | both keeper files present and still listed; V-08 green includes them | A deleted keeper = over-deletion |
| V-15 | plan Story 1.3 | `grep -c '<ID>' kmp/config/detekt/baseline.xml` before and after; `timeout 30m ./gradlew :kmp:detekt` (run after V-05, not in parallel) | count drops by exactly 8; line naming `AnnotationEditorViewModel` still present; detekt exit 0 | Dropped by 7 means one `EditorViewModel` entry (two exist: `LargeClass` and `TooManyFunctions`) was missed |
| V-16 | Constraint: tests only | `git diff HEAD --stat -- kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands` | empty output | any diff = production change, revert |
| V-17 | Constraint: hygiene | `git status --short`; `bazel shutdown` | staged paths are exactly: the 26 deletions, three `TESTS/*.kt`, `AllBusinessTests.kt`, `baseline.xml`, `hotspot-ranking.md`, the `project_plans/editor-subsystem-coverage/` files; no `git add -A` | stray path = unstage |

V-pre = the same V-08/V-11 commands run before any edit on `8f4818d6`, recorded in `implementation/verification-notes.md`.

Integration/verification tier: **17 cases**.

## Fallbacks (honest outcomes, defined before implementation)

| ID | Situation | Required report | What does not change |
|----|-----------|-----------------|----------------------|
| F-1 | JaCoCo ratio `< 0.30` after the behavioural cases (V-10) | Run Task 2.4c once: per-class table, then (only if the remaining `missed` is generated data-class `equals`/`hashCode`/`copy`) add the small value-semantics test, labelled as a contract test in code and notes; report the ratio **with and without** it. If still `< 0.30`: AC-3 = "measured, not met" with the number, the table and the unreachable list (`heading` `newLevel > 0` else arm, `CommandManager` outer `catch`, manager `Partial`/`Nothing`/null-message arms). | No production edits, no JaCoCo excludes, no assertion-free tests, bar is not lowered |
| F-2 | JaCoCo cannot run (task fails, needs display with none, OOM) | AC-3 = "not measurable here" with the exact error output and command tried; a traceability table of source branch site (`file:line`) to test ID, labelled **proxy, not a measurement**; name the CI route (`jvmTest` + `jacocoTestReport` on a runner with a display) | AC-1, AC-2, AC-4, AC-5 still close; the 53 unit cases still run under Bazel (V-08) |
| F-3 | No display and no Xvfb (V-11) | State "jvm_tests not runnable here: <script verdict>"; run `./gradlew testDebugUnitTest` (Robolectric, headless) plus V-08; Tyler may install Xvfb (`sudo pacman -S xorg-server-xvfb`). A bare, unwrapped `jvm_tests` UI failure is "unverified", not a regression | The new tests live in `businessTest` (no UI), so V-08 gives real signal without a display |
| F-4 | Cold Bazel exceeds `timeout 30m` (V-08) | "timeout is not a baseline": retry once at `timeout 60m`, record cache state | AC-4 baseline remains required |
| F-5 | An expected value in this plan disagrees with observed behaviour | Change the test to the observed value, list the discrepancy in the final report; never edit `editor.commands` (M-05/M-06 messages, S-05, M-11 are the likeliest) | Production behaviour unchanged |
| F-6 | Compile fails naming a deleted symbol (V-05..V-07) | Restore that file, re-run, report which consumer the grep missed | The batch deletion is not partially kept silently |

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|-------|------------------|--------|
| Kotlin/JVM (JaCoCo 0.8.12, `kmp/build.gradle.kts:1517-1533`) | `./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.editor.commands.*' :kmp:jacocoTestReport`, then the extraction script in `plan.md` | `BRANCH` ratio of package `dev/stapler/stelekit/editor/commands` >= 30% (requirement AC-3; the generic 80% line template target does not apply: this task's bar is the requirement text) |

- Baseline: no `kmp/build/reports/jacoco/` exists in this worktree today (VERIFIED, `ls` failed), so "0%" is the item's measured figure and is re-measured in V-pre only if a display is available; otherwise report "baseline not re-measured".
- The number is a lower bound: the filter excludes `CommandPaletteFocusRetentionTest` and any other test touching `commands`.
- Unresolved before measurement (HYPOTHESIS): whether data-class generated branches (`CommandContext`, `CommandConfig`, `EditorCommand`, `CommandResult.*`, `CommandHistoryEntry`) sit in the `BRANCH` denominator. The per-class table in V-10 settles it.
- Bazel PR CI runs only `//kmp:jvm_tests`; these `businessTest` classes are guarded by Gradle `ciCheck`/`jvmTest` and release gates, not by Bazel PR CI (adversarial-review minor). Note this in `verification-notes.md`.

## Requirement Coverage Check

| Requirement | Unit cases | Verification cases | Error / fallback path | Covered |
|-------------|------------|--------------------|------------------------|---------|
| AC-1 | n/a (file-tree property) | V-01, V-02, V-03 | V-03 blind spot, F-6 | Yes |
| AC-2 | n/a at unit level; A-/M-/S-/E- cases run inside V-08 | V-04, V-05, V-06, V-07, V-08, V-14 | F-4, F-6 | Yes |
| AC-3 | 53 (A, M, R, S, E) | V-09, V-10 | F-1, F-2, F-5 | Yes |
| AC-4 | n/a | V-11, V-12 | F-3, F-4 | Yes |
| AC-5 | n/a | V-13 (and V-04 for batch count) | none needed (binary check) | Yes |

**Requirements covered: 5/5.**

## Plan observations from this validation (not blockers)

1. **More unreachable manager arms than the plan lists.** Plan Story 2.1 names `Partial` and the outer `catch`. Also unreachable through `EssentialCommands`: `handleResult` `Nothing` arm (`block.toggle-todo` is `hidden`, so manager gets `Error`) and `Success` with null message. They count in the denominator; add them to the F-1 unreachable list.
2. **Glossary imprecision.** `plan.md` says `CommandResult.Nothing` "shadows `kotlin.Nothing` inside the package". It is a nested object (`CommandTypes.kt:46`), shadowing only inside `CommandResult`'s own body. Tests must still write `CommandResult.Nothing` explicitly, but `throw` expressions and lambda types elsewhere in the test file are unaffected.
3. **E-14 is new.** The plan asserts `system.save` async inside row #14 only as a config note; made an explicit case so the `async` arm of `CommandSystem` has a real-command counterpart.
4. **`data["timestamp"]` is a `kotlin.time.Instant`** (`EssentialCommands.kt:733`); E-13 asserts presence only to avoid a clock-dependent value.
5. **Heading quirk (observation only, out of scope):** `text.heading` prepends a prefix without stripping an existing one (`"## a"` becomes `"## ## a"`, `EssentialCommands.kt:283`); tests assert the level in `message`, not `newLine`. Surface in the final report.
