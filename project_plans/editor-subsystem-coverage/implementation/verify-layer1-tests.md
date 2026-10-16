# Layer 1 test review: editor.commands (CommandManagerTest, CommandSystemRegistryTest, EssentialCommandsTest)

Verdict: PASS with suggestions. 0 MUST FIX, 8 SUGGEST, 7 NITPICK.
Basis: VERIFIED by reading the three test files and the production sources in
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/`. Tests were not executed or compiled by this review.
Paths below are relative to `kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands/`.

## Strengths
- Almost every test asserts a concrete observable result (exact `CommandResult`, notification triple, history size/order, formatted text), not just "ran".
- Determinism is handled well: `Clock.System` is only used for `timestamp`/`executionTimeMs`, which are never asserted (system.save timestamp is presence-only, with a comment). History ordering uses a sequential `repeat(101)` and the `context.currentText` marker, no timing.
- `CommandManager(this)` with `StandardTestDispatcher` plus `advanceUntilIdle()` is correct and necessary: the init `scope.launch` registration only runs once the test body suspends. The registered child coroutine completes, so `runTest` does not hang.
- The `Dispatchers.Default` hop in `CommandSystem` (non-async commands) is awaited by `withContext`, so results are deterministic. It is real-thread, but nothing asserts on thread or timing.
- Cancellation is rethrown and tested (CommandSystemRegistryTest.kt:147-156); `CancellationException` is not swallowed.
- The stack-trace-recovery caveat on `IllegalStateException` identity is handled (CommandSystemRegistryTest.kt:141-143).
- Private top-level helpers: `command`/`NOT_AVAILABLE`/`notice`/`SELECTION` (CommandManagerTest), `cmd`/`NOT_AVAILABLE` (CommandSystemRegistryTest), `C0/CSEL/CBLOCK/CBAD/PALETTE/FIVE_TEXT_IDS` (EssentialCommandsTest) are all `private` file-scope. Same-named `NOT_AVAILABLE` in two files is legal in Kotlin, and there is no clash (VERIFIED by reading; not compiled).
- Imports: no unused imports found in any of the three files.

## Findings

### MUST FIX
None.

### SUGGEST
1. **Sweep test is coverage-only and tautological.** EssentialCommandsTest.kt:203-215.
   `assertIs<CommandResult>(command.execute(context))` can never fail (static type is already `CommandResult`), and
   `assertEquals(all().size * contexts.size, results)` only checks the loop counter. All 24 commands are already
   executed by the targeted tests (text loops, link, heading, 6 block, toggleTodo, 4 nav, image, 5 system), so it adds
   no branch the others miss. The only real assertion is "does not throw", which the harness already provides.
   Delete it, or make it earn its place by asserting an invariant (e.g. result is `Success`/`Error`/`Nothing` and
   `Error.message` is non-blank, `Success.message` non-blank for all but toggle-todo).
2. **512-combination loop mirrors the implementation and has a vacuous count assertion.** CommandManagerTest.kt:110-145.
   The expected formula (L134-135) is the same boolean expression as `EditorCommand.isAvailable`
   (CommandTypes.kt:116-121), so it can only catch typos, not misreadings. `assertEquals(512, checked)` guards the loop
   nest, not behaviour. Single-factor tests at L41-108 already catch operator flips. Either drop it or convert to a
   kotest-property `checkAll` (per repo CLAUDE.md guidance) with the oracle written as the documented spec rather than the code.
3. **Heading test name claims "next level" but pins an off-by-one and omits a production bug.**
   EssentialCommandsTest.kt:109-117 vs EssentialCommands.kt:272-283.
   `"## a"` (h2) reports "Heading level set to 2", i.e. it re-emits h2, not the next level; `"# a"` (h1) is not recognised
   (maps to 0, so it would become `# # a`); and `######` can never wrap back to 0 (5+1 = 6 forever). The test's comment
   says prefix handling is "not pinned", yet the level numbers it does pin are the accidental ones. The command is
   `hidden`, so the blast radius is small, but a future fix of the level math will fail this test for a right reason
   with a wrong-looking name. Rename to describe the pinned mapping (e.g. `heading_should_MapExistingHashCountToCurrentBehaviour`)
   and add a one-line comment that `# a` and wrap-around are known-wrong, or file a bug and add an `@Ignore`d expected-behaviour test.
4. **`clearHistory` test is eager and misnamed.** CommandSystemRegistryTest.kt:184-199.
   Name `clearHistory_should_Empty_And_Delegates_Should_ReachRegistry` bundles three behaviours (clear, `getCommand`
   delegation, `getAvailableCommands` delegation, the last proving hidden is filtered). Split into three tests.
5. **Duplicated cases across Manager and Essential tests.**
   - Palette visibility: CommandManagerTest.kt:269-286 vs EssentialCommandsTest.kt:39-49 (same sets; `SELECTION` and `CSEL` are the same literal defined twice).
   - media.image result: CommandManagerTest.kt:165-178 and 256-267 vs EssentialCommandsTest.kt:177-183 (same `Success` asserted three times).
   - bold formatting: CommandManagerTest.kt:204-218 vs the loop at EssentialCommandsTest.kt:58-74.
   - Hidden-via-manager: CommandManagerTest.kt:238-254 repeats the same mechanism for toggle-todo and system.help.
   Keep the manager tests focused on manager behaviour (notification mapping, not-found/not-available, wrapper); keep
   command-output assertions in EssentialCommandsTest.
6. **CommandManager.handleResult branches are untested.** CommandManager.kt:75-88 (Success with null message, Partial
   `"msg (c/t)"` INFO 2000, Nothing no-op) and the `catch (Exception)` paths at :46-51 and :61-65. The registry is
   private and populated only from `EssentialCommands`, so these are unreachable through the public API without a seam.
   Both the "75% branch" number and the gap are consistent with this. Either accept and document the ceiling, or add an
   internal constructor/registry seam. The Partial format string is the only user-visible logic uncovered.
7. **History entry contents and one accidental pin.** CommandSystemRegistryTest.kt:116-129, 158-168.
   Tests assert only history size; none checks a recorded entry's `command`, `context`, `result`, or
   `executionTimeMs >= 0`. Separately, `..._And_StillRecord_When_CommandHidden` pins that an unavailable (hidden)
   command is written to history with a `NOT_AVAILABLE` error. That is probably an implementation accident in
   `CommandSystem.executeCommand`; there is no comment saying it is intended. Add a comment, or drop the history assertion.
8. **Notification durations and types are hard-coded in five places.** CommandManagerTest.kt:177, 189, 201, 217, 235-236
   (`3000L`/`5000L`). These are the callback contract, so pinning type is reasonable; the millisecond values are UI timing
   constants. Prefer asserting `NotificationType` and message, or hoist `SUCCESS_MS`/`ERROR_MS` constants in the test with a
   one-line "UI timing, update with CommandManager" comment.

### NITPICK
1. Names missing the `_When_` clause or compound: `getCommand_should_ReturnRegistered_And_NullForUnknown`,
   `registerAll_should_RegisterEach_And_AcceptEmptyList`, `getCommandsByType_should_ReturnOnlyMatchingType`,
   `link_should_UseSelectionOrPlaceholder`, `blockCommands_should_EchoBlockIdOrEmpty`, `navigationCommands_should_ReturnActionKeys`,
   `toggleTodo_should_ReturnNothing`, `mediaImage_should_ReturnAttachImageAction`. The repo's `subject_should_X_When_Y` form is otherwise followed.
2. EssentialCommandsTest.kt:27 private member `run(...)` shadows `kotlin.run`; rename `runOk`/`executeOk`.
3. CommandManagerTest.kt:36 `@OptIn(ExperimentalCoroutinesApi::class)`: INFERRED to be needed for `advanceUntilIdle`
   on `TestScope`; confirm by removing it once and compiling, otherwise it is dead.
4. EssentialCommandsTest.kt:52-54 `systemSave_should_BeTheOnlyAsyncCommand` pins `config.async` of every command. It
   duplicates config rather than behaviour and has no stated reason; consider dropping or documenting why `async` matters.
5. EssentialCommandsTest.kt:97,102 pins the placeholder `https://example.com` and `"link text"` literals from a hidden
   command; those are placeholders that `requiresUrlInput = true` explicitly says will be replaced.
6. EssentialCommandsTest.kt:19-22 KDoc says tests pin what hidden commands return "not whether they should be exposed",
   but L39-49 and CommandManagerTest.kt:238-254 do pin visibility (`{media.image}` is a deliberate tripwire; production
   docs at CommandTypes.kt:82-86 and EssentialCommands.kt:671-675 say media.image is the one intentionally visible command).
   Reword the KDoc to say the visibility set is an intentional guard and point at the Phase H audit comments.
7. CommandSystemRegistryTest.kt:104-113 `ReturnSuccess_When_AsyncAndNonAsync` exercises both dispatcher branches but
   asserts only the result; it cannot tell which branch ran. Acceptable (a thread assertion would be JVM-only), but the
   name overstates; `..._When_AsyncFlagIsSetOrNot` is accurate.

## Question-by-question answers
1. Behaviour vs coverage: behaviour for 95% of tests. Coverage-only: the sweep test (SUGGEST 1). Marginal value: the 512 loop (SUGGEST 2).
2. Flakiness: low risk. `Dispatchers.Default` inside `runTest` is awaited, no assertions on thread/time; `Clock` outputs unasserted;
   history is sequential; passing `this` (TestScope) is fine since registration completes under `advanceUntilIdle`. Only residual:
   `runTest`'s wall-clock timeout on a starved CI box, no worse than other suite members.
3. Pins: exact strings `"Command not found: x"`, `NOT_AVAILABLE` (also duplicated as a literal in two files), `"Text formatted as bold"`;
   `{media.image}` is intentional (production-documented), but test docs do not say so (NITPICK 6). Accidental pins: SUGGEST 3, 7, 8, NITPICK 5.
4. Missing: SUGGEST 6, 7; also not covered: bold/italic with an empty selection when `execute` is called directly
   (`selectionStart == selectionEnd` path in formatting commands), `selectionStart > selectionEnd` on `link` (the `else ""` branch is
   hit only via `C0`, which has 0 == 0), concurrent `executeCommand` calls racing the non-atomic read-modify-write in `addToHistory`
   (CommandSystem.kt:91-101). Duplicates: SUGGEST 5.
5. Idiom: see NITPICK 1-3. No helper clash, no unused imports.
