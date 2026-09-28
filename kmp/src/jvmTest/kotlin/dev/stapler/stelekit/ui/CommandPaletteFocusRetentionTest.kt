package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.components.BlockItem
import dev.stapler.stelekit.ui.components.CommandPalette
import dev.stapler.stelekit.ui.components.ShortcutTable
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * Epic F.1 — Empirical focus/blur risk spike (plan.md Story F.1.1).
 *
 * BEFORE wiring any command-palette entry that calls `requestFormat`/`requestTodoToggle`, this
 * test empirically confirms whether opening [CommandPalette]'s [androidx.compose.ui.window.Dialog]
 * while a [BlockItem] is `isEditing == true` tears down `BlockItem`'s
 * `LaunchedEffect(isEditing, formatEvents)` collector scope (`BlockItem.kt:220-231`/`240-250`)
 * before an emission can be collected — `formatEvents`/`todoToggleEvents` have no replay cache
 * (`extraBufferCapacity = 1` only), so a torn-down collector silently drops the emission
 * (architecture.md §3).
 *
 * Harness mirrors the real production wiring found in `BlockEditor.kt:210-216`: focus loss on
 * the block's text field calls `onStopEditing()`, which flips `isEditing` to `false` in the
 * parent (ViewModel-level) state — exactly as `BlockStateManager.stopEditingBlock` does in
 * production. `CommandPalette` itself (`CommandPalette.kt:65-69`) requests focus onto its own
 * search `TextField` as soon as it becomes visible, which is the trigger this spike measures.
 *
 * Recorded result (see plan.md Epic F.1 section for the narrative writeup):
 *   - FormatAction.BOLD case:      PASS (emission applied to the editing block's content)
 *   - requestTodoToggle() case:    PASS (emission applied to the editing block's content)
 * In both cases `isEditing` remained `true` throughout — opening `CommandPalette`'s `Dialog`
 * alongside an editing `BlockItem` did not blur the block's focus in this Compose UI test
 * harness, so `LaunchedEffect(isEditing, formatEvents)` / `LaunchedEffect(isEditing,
 * todoToggleEvents)` kept collecting and consumed the emission. No fix was required; Epic F.2
 * proceeds using the existing `requestFormat`/`requestTodoToggle()` SharedFlow dispatch path.
 */
@OptIn(ExperimentalTestApi::class)
class CommandPaletteFocusRetentionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun testBlock(content: String) = Block(
        uuid = BlockUuid("block-7"),
        pageUuid = PageUuid("page-1"),
        content = content,
        position = "a0",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `formatActionEmission should applyToEditingBlock when commandPaletteDialogIsOpenSimultaneously`() {
        val formatEvents = MutableSharedFlow<FormatAction>(extraBufferCapacity = 1)
        val todoToggleEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var lastContent: String? = null
        var isEditingRef: androidx.compose.runtime.MutableState<Boolean>? = null

        composeTestRule.setContent {
            val isEditingState = remember { mutableStateOf(true) }
            isEditingRef = isEditingState
            var block by remember { mutableStateOf(testBlock("hello")) }
            var paletteVisible by remember { mutableStateOf(true) }

            Box(modifier = Modifier.fillMaxSize()) {
                BlockItem(
                    block = block,
                    isEditing = isEditingState.value,
                    onStartEditing = { isEditingState.value = true },
                    // Mirrors BlockEditor.kt:210-216: focus-loss flips isEditing to false.
                    onStopEditing = { isEditingState.value = false },
                    onContentChange = { newContent, newVersion ->
                        block = block.copy(content = newContent, version = newVersion)
                        lastContent = newContent
                    },
                    onLinkClick = {},
                    onNewBlock = {},
                    onSplitBlock = { _, _ -> },
                    formatEvents = formatEvents.asSharedFlow(),
                    todoToggleEvents = todoToggleEvents.asSharedFlow(),
                    modifier = Modifier.testTag("block-item"),
                )

                CommandPalette(
                    visible = paletteVisible,
                    commands = emptyList(),
                    onDismiss = { paletteVisible = false },
                )
            }
        }

        composeTestRule.waitForIdle()

        // Emit FormatAction.BOLD from a "test harness" — i.e. the future command-palette action.
        formatEvents.tryEmit(FormatAction.BOLD)
        composeTestRule.waitForIdle()

        // Recorded verdict (plan.md Story F.1.1) — empirically observed: collapsed cursor at
        // position 0 (BlockItem's default TextFieldValue for a freshly-loaded block has no
        // selection), so FormatAction.BOLD inserts markers at the cursor rather than wrapping
        // the whole word: "hello" -> "****hello" (prefix+suffix inserted at pos 0).
        assertEquals(
            "****hello",
            lastContent,
            "F.1 spike record (PASS case): FormatAction.BOLD IS applied to the editing block even " +
                "while CommandPalette's Dialog is open simultaneously — isEditing did not flip to " +
                "false in this scenario, so BlockItem's LaunchedEffect(isEditing, formatEvents) " +
                "collector kept running and consumed the emission.",
        )
        assertTrue(isEditingRef?.value ?: false, "isEditing remained true — CommandPalette's Dialog did not blur the editing block in this scenario")
    }

    @Test
    fun `todoToggleEmission should applyToEditingBlock when commandPaletteDialogIsOpenSimultaneously`() {
        val formatEvents = MutableSharedFlow<FormatAction>(extraBufferCapacity = 1)
        val todoToggleEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var lastContent: String? = null
        var isEditingRef: androidx.compose.runtime.MutableState<Boolean>? = null

        composeTestRule.setContent {
            val isEditingState = remember { mutableStateOf(true) }
            isEditingRef = isEditingState
            var block by remember { mutableStateOf(testBlock("Buy milk")) }
            var paletteVisible by remember { mutableStateOf(true) }

            Box(modifier = Modifier.fillMaxSize()) {
                BlockItem(
                    block = block,
                    isEditing = isEditingState.value,
                    onStartEditing = { isEditingState.value = true },
                    onStopEditing = { isEditingState.value = false },
                    onContentChange = { newContent, newVersion ->
                        block = block.copy(content = newContent, version = newVersion)
                        lastContent = newContent
                    },
                    onLinkClick = {},
                    onNewBlock = {},
                    onSplitBlock = { _, _ -> },
                    formatEvents = formatEvents.asSharedFlow(),
                    todoToggleEvents = todoToggleEvents.asSharedFlow(),
                    modifier = Modifier.testTag("block-item"),
                )

                CommandPalette(
                    visible = paletteVisible,
                    commands = emptyList(),
                    onDismiss = { paletteVisible = false },
                )
            }
        }

        composeTestRule.waitForIdle()

        // Emit a todo-toggle request from a "test harness" — i.e. the future command-palette action.
        todoToggleEvents.tryEmit(Unit)
        composeTestRule.waitForIdle()

        // Recorded verdict (plan.md Story F.1.1) — empirically observed PASS: requestTodoToggle()
        // IS applied to the editing block even while CommandPalette's Dialog is open simultaneously.
        assertEquals(
            "TODO Buy milk",
            lastContent,
            "F.1 spike record (PASS case): requestTodoToggle() IS applied to the editing block even " +
                "while CommandPalette's Dialog is open simultaneously — isEditing did not flip to " +
                "false in this scenario, so BlockItem's LaunchedEffect(isEditing, todoToggleEvents) " +
                "collector kept running and consumed the emission.",
        )
        assertTrue(isEditingRef?.value ?: false, "isEditing remained true — CommandPalette's Dialog did not blur the editing block in this scenario")
    }

    /**
     * Control case: confirms the collector applies the FormatAction when isEditing stays true
     * with no palette present at all — establishes the baseline behavior the two tests above are
     * compared against (both of which also observed isEditing staying true and the emission
     * being applied).
     */
    @Test
    fun `formatActionEmission should applyToEditingBlock when noDialogStealsFocus (control case)`() {
        val formatEvents = MutableSharedFlow<FormatAction>(extraBufferCapacity = 1)
        val todoToggleEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var lastContent: String? = null

        composeTestRule.setContent {
            var block by remember { mutableStateOf(testBlock("hello")) }
            BlockItem(
                block = block,
                isEditing = true,
                onStartEditing = {},
                onStopEditing = {},
                onContentChange = { newContent, newVersion ->
                    block = block.copy(content = newContent, version = newVersion)
                    lastContent = newContent
                },
                onLinkClick = {},
                onNewBlock = {},
                onSplitBlock = { _, _ -> },
                formatEvents = formatEvents.asSharedFlow(),
                todoToggleEvents = todoToggleEvents.asSharedFlow(),
                modifier = Modifier.testTag("block-item"),
            )
        }
        composeTestRule.waitForIdle()

        formatEvents.tryEmit(FormatAction.BOLD)
        composeTestRule.waitForIdle()

        assertEquals("****hello", lastContent, "Control case: with no Dialog present, the collector must apply the FormatAction (collapsed cursor at pos 0 inserts markers rather than wrapping)")
    }

    // ------------------------------------------------------------------------------------
    // Epic F.2 / Task F.2.1c: integration test confirming the FULL palette-select ->
    // content-mutation path — a real BlockStateManager wired the same way
    // StelekitViewModel.updateCommands() wires its "format.*" Command entries
    // (action = { bsm.requestFormat(action) } / { bsm.requestTodoToggle() }), with the actual
    // CommandPalette row clicked via the Compose semantics tree (not a direct tryEmit call).
    // ------------------------------------------------------------------------------------

    private fun testBlockStateManager(): BlockStateManager {
        val fileSystem = FakeFileSystem()
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return BlockStateManager(blockRepo, graphLoader, scope)
    }

    @Test
    fun `paletteFormatBoldCommand should wrapContentInAsterisks when selectedFromFilteredCommandList`() {
        val bsm = testBlockStateManager()
        var lastContent: String? = null

        composeTestRule.setContent {
            var block by remember { mutableStateOf(testBlock("hello")) }

            Box(modifier = Modifier.fillMaxSize()) {
                BlockItem(
                    block = block,
                    isEditing = true,
                    onStartEditing = {},
                    onStopEditing = {},
                    onContentChange = { newContent, newVersion ->
                        block = block.copy(content = newContent, version = newVersion)
                        lastContent = newContent
                    },
                    onLinkClick = {},
                    onNewBlock = {},
                    onSplitBlock = { _, _ -> },
                    formatEvents = bsm.formatEvents,
                    todoToggleEvents = bsm.todoToggleEvents,
                    modifier = Modifier.testTag("block-item"),
                )

                CommandPalette(
                    visible = true,
                    // Mirrors StelekitViewModel.updateCommands()'s Epic F.2 Command entry exactly.
                    commands = listOf(
                        Command(
                            id = "format.bold",
                            label = "Format: Bold",
                            shortcut = ShortcutTable.forAction(FormatAction.BOLD),
                            action = { bsm.requestFormat(FormatAction.BOLD) },
                        )
                    ),
                    onDismiss = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Format: Bold").performClick()
        composeTestRule.waitForIdle()

        // BlockItem's default TextFieldValue has a collapsed cursor at position 0 (no selection
        // captured on initial load), so FormatAction.BOLD inserts markers at the cursor rather
        // than wrapping the whole word — same collapsed-cursor behavior verified by the direct
        // tryEmit tests above, now exercised through a real CommandPalette row click instead.
        assertEquals(
            "****hello",
            lastContent,
            "Selecting 'Format: Bold' from the real CommandPalette must mutate the editing block's content via requestFormat(BOLD) -> applyFormatAction, exactly as Ctrl+B does",
        )
    }

    @Test
    fun `paletteToggleTodoCommand should mutateBlockContentToTodoPrefix when selectedFromCommandPalette`() {
        val bsm = testBlockStateManager()
        var lastContent: String? = null

        composeTestRule.setContent {
            var block by remember { mutableStateOf(testBlock("Buy milk")) }

            Box(modifier = Modifier.fillMaxSize()) {
                BlockItem(
                    block = block,
                    isEditing = true,
                    onStartEditing = {},
                    onStopEditing = {},
                    onContentChange = { newContent, newVersion ->
                        block = block.copy(content = newContent, version = newVersion)
                        lastContent = newContent
                    },
                    onLinkClick = {},
                    onNewBlock = {},
                    onSplitBlock = { _, _ -> },
                    formatEvents = bsm.formatEvents,
                    todoToggleEvents = bsm.todoToggleEvents,
                    modifier = Modifier.testTag("block-item"),
                )

                CommandPalette(
                    visible = true,
                    // Mirrors StelekitViewModel.updateCommands()'s Epic F.2 "Toggle Todo" entry exactly.
                    commands = listOf(
                        Command(
                            id = "format.toggle-todo",
                            label = "Format: Toggle Todo",
                            shortcut = ShortcutTable.TODO_TOGGLE,
                            action = { bsm.requestTodoToggle() },
                        )
                    ),
                    onDismiss = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Format: Toggle Todo").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "TODO Buy milk",
            lastContent,
            "Selecting 'Format: Toggle Todo' from the real CommandPalette must mutate the editing block's content via requestTodoToggle() -> applyTodoToggle, exactly as Ctrl+Enter does",
        )
    }

    // ------------------------------------------------------------------------------------
    // Epic H.2 / Task H.2.1c: integration test confirming the REPOINTED legacy
    // "block.toggle-todo" id — sourced from the EssentialCommands/CommandManager bridge before
    // Phase H, silently discarding its computed CommandResult — now actually mutates content
    // when routed through StelekitViewModel.executeCommand() (ADR-001).
    // ------------------------------------------------------------------------------------

    private fun testViewModel(blockStateManager: BlockStateManager): StelekitViewModel {
        val fileSystem = FakeFileSystem()
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val graphWriter = GraphWriter(PlatformFileSystem())
        val searchRepo = InMemorySearchRepository()
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = graphLoader,
                graphWriter = graphWriter,
                platformSettings = InMemorySettings(),
                scope = scope,
                blockStateManager = blockStateManager,
            )
        )
    }

    @Test
    fun `executeCommand should mutateBlockContentToTodoPrefix when invokedWithLegacyBlockToggleTodoId`() {
        val bsm = testBlockStateManager()
        val viewModel = testViewModel(bsm)
        var lastContent: String? = null

        composeTestRule.setContent {
            var block by remember { mutableStateOf(testBlock("Buy milk")) }

            BlockItem(
                block = block,
                isEditing = true,
                onStartEditing = {},
                onStopEditing = {},
                onContentChange = { newContent, newVersion ->
                    block = block.copy(content = newContent, version = newVersion)
                    lastContent = newContent
                },
                onLinkClick = {},
                onNewBlock = {},
                onSplitBlock = { _, _ -> },
                formatEvents = bsm.formatEvents,
                todoToggleEvents = bsm.todoToggleEvents,
                modifier = Modifier.testTag("block-item"),
            )
        }

        composeTestRule.waitForIdle()

        // Exercises the exact call StelekitViewModel.updateCommands() makes for every OTHER
        // (non-special-cased) EssentialCommands-bridged command: scope.launch { executeCommand(cmd.id) }.
        // Pre-Phase-H, "block.toggle-todo" reached commandManager.executeCommand(), computed a
        // newContent string, and discarded it (features.md §2). Post-Phase-H, executeCommand()
        // intercepts this id and calls the real requestTodoToggle() mutation instead.
        runBlocking { viewModel.executeCommand("block.toggle-todo") }
        composeTestRule.waitForIdle()

        assertEquals(
            "TODO Buy milk",
            lastContent,
            "StelekitViewModel.executeCommand(\"block.toggle-todo\") must mutate the editing " +
                "block's content via requestTodoToggle() -> applyTodoToggle — the Phase H fix for " +
                "the legacy id's previously-discarded CommandResult (ADR-001, Epic H.2.1)",
        )
    }

    @Test
    fun `getAvailableCommands should notIncludeLegacyBlockToggleTodo_becauseFormatToggleTodoIsTheCanonicalPaletteEntry`() {
        // Epic H.2.2 / toggle-todo consolidation: EssentialCommands.kt's BlockCommands.toggleTodo
        // now sets config.hidden = true, so it must never surface via getAvailableCommands() (the
        // exact source StelekitViewModel.updateCommands() bridges into the real palette) — this
        // guards against the palette ever showing two "Toggle Todo"-ish rows simultaneously.
        val available = dev.stapler.stelekit.editor.commands.EssentialCommands.getAll()
            .filter { it.isAvailable(dev.stapler.stelekit.editor.commands.CommandContext()) }

        assertTrue(
            available.none { it.id == "block.toggle-todo" },
            "The legacy 'block.toggle-todo' EssentialCommands entry must be hidden from " +
                "getAvailableCommands() — 'format.toggle-todo' (StelekitViewModel.updateCommands(), " +
                "Phase F.2) is the one canonical, user-visible Toggle Todo palette entry",
        )
    }
}
