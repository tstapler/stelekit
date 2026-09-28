package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.components.ShortcutTable
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.screens.FormatAction
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story F.3.1's structural guarantee — [ShortcutTable] is the single source of truth mapping
 * each [FormatAction] to its canonical display string, consumed by BOTH `BlockEditor.kt`'s
 * `handleKeyEvent` cascade (`ShortcutTable.actionForKeyEvent`) and `StelekitViewModel.kt`'s
 * `Command.shortcut` assembly (`ShortcutTable.forAction`) — was never pinned by a regression
 * test even though it exists specifically to prevent the command-palette's displayed shortcut
 * badge from drifting away from the actual live keyboard binding.
 *
 * This test pins two invariants:
 *  1. Round-trip consistency of [ShortcutTable] itself: for every [FormatAction] with a hardware
 *     shortcut, the key/shift combination that [ShortcutTable.actionForKeyEvent] resolves back to
 *     that action is the SAME combination [ShortcutTable.forAction]'s display string describes.
 *  2. `StelekitViewModel`'s real, live-assembled `Command` list (not a hand-mirrored copy) has a
 *     `shortcut` value for every format/toggle-todo entry that is exactly what
 *     `ShortcutTable.forAction`/`ShortcutTable.TODO_TOGGLE` produce right now — so a future
 *     refactor that reintroduces a hardcoded shortcut string (drifting from a subsequent
 *     [ShortcutTable] binding change) fails here.
 */
class ShortcutTableConsistencyTest {

    // ─── Invariant 1: ShortcutTable round-trip (key combo -> action -> display string) ──────

    @Test
    fun `every FormatAction with a shortcut should roundTripThroughActionForKeyEventAndForAction`() {
        val actionsWithShortcuts = FormatAction.values().filter { ShortcutTable.forAction(it) != null }

        // Guard against a future no-op refactor where every binding is silently removed and this
        // test would vacuously pass on an empty list.
        assertTrue(actionsWithShortcuts.isNotEmpty(), "Expected at least one FormatAction to carry a hardware-keyboard shortcut")

        for (action in actionsWithShortcuts) {
            val binding = ShortcutTable.bindingFor(action)
            assertNotNull(binding, "ShortcutTable.forAction($action) returned non-null but bindingFor($action) was null")

            // The exact shiftPressed state the binding requires ("true" -> must be held;
            // "null" -> irrelevant, use false as the representative case).
            val shiftPressed = binding.requireShift == true

            val resolvedAction = ShortcutTable.actionForKeyEvent(binding.key, shiftPressed)
            assertEquals(
                action,
                resolvedAction,
                "actionForKeyEvent(${binding.key}, shiftPressed=$shiftPressed) must resolve back to " +
                    "$action — this is the exact key event handleKeyEvent's cascade would see for " +
                    "$action's binding",
            )

            val displayString = ShortcutTable.forAction(action)
            assertNotNull(displayString)
            assertEquals(
                binding.requireShift == true,
                displayString.contains("Shift+"),
                "forAction($action)'s display string ('$displayString') must mention Shift iff the " +
                    "underlying binding requires it — otherwise the palette badge would describe a " +
                    "different key combo than the one actionForKeyEvent actually matches",
            )
            assertTrue(displayString.startsWith("Ctrl+"), "All bindings require Ctrl/Cmd; display string '$displayString' must start with 'Ctrl+'")
        }
    }

    @Test
    fun `FormatAction entries with no hardware shortcut should haveNoBindingAndNeverMatchAnyKeyEvent`() {
        // CODE_BLOCK and TABLE_INSERT are documented (ShortcutTable.kt) as having no
        // hardware-keyboard binding yet — pin that absence explicitly so a future accidental
        // removal of an *existing* binding elsewhere doesn't get masked by this test only
        // checking the "has a shortcut" half of the invariant.
        val actionsWithoutShortcuts = FormatAction.values().filter { ShortcutTable.forAction(it) == null }

        assertEquals(
            setOf(FormatAction.CODE_BLOCK, FormatAction.TABLE_INSERT),
            actionsWithoutShortcuts.toSet(),
            "Only CODE_BLOCK/TABLE_INSERT are expected to have no hardware shortcut; if this set " +
                "changed, ShortcutTable.forAction and BlockEditor's cascade behavior have drifted apart",
        )

        for (action in actionsWithoutShortcuts) {
            assertNull(ShortcutTable.bindingFor(action), "$action must have no KeyBinding since it has no display string")
        }
    }

    @Test
    fun `shift-required bindings should notMatch when shiftIsNotPressed`() {
        // NUMBERED_LIST (Ctrl+Shift+7) and HEADING (Ctrl+Shift+1) require Shift — confirm
        // actionForKeyEvent refuses to match without it, so Ctrl+7/Ctrl+1 alone (no Shift)
        // cannot silently fire these actions, matching the display string's "Shift+" claim.
        val shiftRequiredActions = listOf(FormatAction.NUMBERED_LIST, FormatAction.HEADING)
        for (action in shiftRequiredActions) {
            val binding = ShortcutTable.bindingFor(action)
            assertNotNull(binding)
            assertEquals(true, binding.requireShift, "$action is expected to require Shift")
            assertNull(
                ShortcutTable.actionForKeyEvent(binding.key, shiftPressed = false),
                "${binding.key} without Shift must not resolve to any action ($action requires Shift)",
            )
        }
    }

    // ─── Invariant 2: StelekitViewModel's live Command.shortcut values track ShortcutTable ──

    private fun testBlockStateManager(): BlockStateManager {
        val fileSystem = FakeFileSystem()
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return BlockStateManager(blockRepo, graphLoader, scope)
    }

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

    /** Every [FormatAction] that StelekitViewModel.updateCommands() bridges into the palette (mirrors the list literal in StelekitViewModel.kt's updateCommands()). */
    private val paletteFormatActions = listOf(
        FormatAction.BOLD,
        FormatAction.ITALIC,
        FormatAction.STRIKETHROUGH,
        FormatAction.HIGHLIGHT,
        FormatAction.CODE,
        FormatAction.LINK,
        FormatAction.QUOTE,
        FormatAction.NUMBERED_LIST,
        FormatAction.HEADING,
        FormatAction.CODE_BLOCK,
        FormatAction.TABLE_INSERT,
    )

    @Test
    fun `StelekitViewModel format commands should haveShortcutEqualToShortcutTableForAction`() = runBlocking {
        val bsm = testBlockStateManager()
        val viewModel = testViewModel(bsm)

        // updateCommands() runs on the ViewModel's scope asynchronously (init{} calls it, and it
        // is a `scope.launch { ... }` body) — wait for the format commands to land rather than
        // racing the coroutine.
        withTimeout(5_000) {
            viewModel.uiState.first { state -> state.commands.any { it.id == "format.bold" } }
        }

        val commands = viewModel.uiState.value.commands

        for (action in paletteFormatActions) {
            val id = "format.${action.name.lowercase()}"
            val command = commands.firstOrNull { it.id == id }
            assertNotNull(command, "Expected a Command with id '$id' for $action in StelekitViewModel's assembled command list")

            assertEquals(
                ShortcutTable.forAction(action),
                command.shortcut,
                "Command '$id'.shortcut ('${command.shortcut}') must be exactly ShortcutTable.forAction($action) " +
                    "('${ShortcutTable.forAction(action)}') — a hardcoded literal here would silently drift from " +
                    "ShortcutTable (and therefore from BlockEditor's live handleKeyEvent binding) the next time " +
                    "ShortcutTable's binding for $action changes",
            )
        }

        val toggleTodoCommand = commands.firstOrNull { it.id == "format.toggle-todo" }
        assertNotNull(toggleTodoCommand, "Expected a Command with id 'format.toggle-todo'")
        assertEquals(
            ShortcutTable.TODO_TOGGLE,
            toggleTodoCommand.shortcut,
            "Command 'format.toggle-todo'.shortcut must be exactly ShortcutTable.TODO_TOGGLE, not a separately hardcoded literal",
        )
    }

    @Test
    fun `every command whose id maps to a FormatAction with no hardware shortcut should haveNullShortcut`() = runBlocking {
        val bsm = testBlockStateManager()
        val viewModel = testViewModel(bsm)

        withTimeout(5_000) {
            viewModel.uiState.first { state -> state.commands.any { it.id == "format.bold" } }
        }

        val commands = viewModel.uiState.value.commands

        // CODE_BLOCK/TABLE_INSERT have no hardware binding (ShortcutTable.forAction returns
        // null) — pin that their palette Command entries surface that same null rather than a
        // stray fallback string, keeping the palette's "no shortcut" badge state consistent with
        // ShortcutTable's own notion of "no shortcut" for these two actions.
        for (action in listOf(FormatAction.CODE_BLOCK, FormatAction.TABLE_INSERT)) {
            val id = "format.${action.name.lowercase()}"
            val command = commands.firstOrNull { it.id == id }
            assertNotNull(command, "Expected a Command with id '$id'")
            assertNull(ShortcutTable.forAction(action), "Sanity check: ShortcutTable.forAction($action) should be null")
            assertNull(command.shortcut, "Command '$id'.shortcut should be null, matching ShortcutTable.forAction($action)")
        }
    }
}
