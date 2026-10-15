package dev.stapler.stelekit.editor.commands

import dev.stapler.stelekit.model.NotificationType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun command(
    id: String = "t",
    config: CommandConfig = CommandConfig(),
    condition: (CommandContext) -> Boolean = { true },
    execute: suspend (CommandContext) -> CommandResult = { CommandResult.Success() },
) = EditorCommand(
    id = id,
    type = CommandType.CUSTOM,
    label = id,
    config = config,
    condition = condition,
    execute = execute,
)

private const val NOT_AVAILABLE = "Command not available in current context"

/** Typed with `Long?` so expected lists unify with the recorded `notificationManager` events. */
private fun notice(message: String, type: NotificationType, durationMs: Long?): Triple<String, NotificationType, Long?> =
    Triple(message, type, durationMs)

private val SELECTION = CommandContext(currentText = "hello world", selectionStart = 0, selectionEnd = 5)

/**
 * `EditorCommand.isAvailable` and `executeWithContext` on hand-built commands, then `CommandManager`
 * execute/availability paths against the real `EssentialCommands` it seeds itself with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandManagerTest {

    // ---- EditorCommand.isAvailable ----

    @Test
    fun isAvailable_should_BeTrue_When_ConfigAndContextAreDefaults() {
        assertTrue(command().isAvailable(CommandContext()))
    }

    @Test
    fun isAvailable_should_BeFalse_When_EnabledIsFalse() {
        assertFalse(command(config = CommandConfig(enabled = false)).isAvailable(CommandContext()))
    }

    @Test
    fun isAvailable_should_BeFalse_When_HiddenIsTrue() {
        assertFalse(command(config = CommandConfig(hidden = true)).isAvailable(CommandContext()))
    }

    @Test
    fun isAvailable_should_BeFalse_When_ConditionReturnsFalse() {
        assertFalse(command(condition = { false }).isAvailable(CommandContext()))
    }

    @Test
    fun isAvailable_should_FollowCondition_When_ConditionReadsContext() {
        val cmd = command(condition = { it.currentText == "x" })
        assertTrue(cmd.isAvailable(CommandContext(currentText = "x")))
        assertFalse(cmd.isAvailable(CommandContext(currentText = "y")))
    }

    @Test
    fun isAvailable_should_RequireNonEmptyOrderedRange_When_RequiresSelection() {
        val cmd = command(config = CommandConfig(requiresSelection = true))
        assertFalse(cmd.isAvailable(CommandContext(selectionStart = 0, selectionEnd = 0)))
        assertFalse(cmd.isAvailable(CommandContext(selectionStart = 5, selectionEnd = 3)))
        assertFalse(cmd.isAvailable(CommandContext(selectionStart = 3, selectionEnd = 3)))
        assertTrue(cmd.isAvailable(CommandContext(selectionStart = 0, selectionEnd = 5)))
    }

    @Test
    fun isAvailable_should_RequireBlockId_When_RequiresBlock() {
        val cmd = command(config = CommandConfig(requiresBlock = true))
        assertFalse(cmd.isAvailable(CommandContext(currentBlockId = null)))
        assertTrue(cmd.isAvailable(CommandContext(currentBlockId = "b1")))
    }

    @Test
    fun isAvailable_should_RequirePageId_When_RequiresPage() {
        val cmd = command(config = CommandConfig(requiresPage = true))
        assertFalse(cmd.isAvailable(CommandContext(currentPageId = null)))
        assertTrue(cmd.isAvailable(CommandContext(currentPageId = "p1")))
    }

    @Test
    fun isAvailable_should_BeFalse_When_AnyOneOfThreeRequirementsIsMissing() {
        val cmd = command(config = CommandConfig(requiresSelection = true, requiresBlock = true, requiresPage = true))
        val full = CommandContext(selectionStart = 0, selectionEnd = 5, currentBlockId = "b1", currentPageId = "p1")
        assertFalse(cmd.isAvailable(full.copy(selectionEnd = 0)), "no selection")
        assertFalse(cmd.isAvailable(full.copy(currentBlockId = null)), "no block")
        assertFalse(cmd.isAvailable(full.copy(currentPageId = null)), "no page")
    }

    @Test
    fun isAvailable_should_BeTrue_When_AllThreeRequirementsPresent() {
        val cmd = command(config = CommandConfig(requiresSelection = true, requiresBlock = true, requiresPage = true))
        assertTrue(
            cmd.isAvailable(
                CommandContext(selectionStart = 0, selectionEnd = 5, currentBlockId = "b1", currentPageId = "p1"),
            ),
        )
    }

    // kibitzer:ignore deep-nesting -- exhaustive 2^9 truth table; flattening would hide the enumeration
    @Test
    fun isAvailable_should_MatchPreconditionFormula_When_All512CombinationsEnumerated() {
        val bools = listOf(false, true)
        var checked = 0
        for (enabled in bools) for (hidden in bools) for (cond in bools)
            for (reqSel in bools) for (reqBlock in bools) for (reqPage in bools)
                for (hasSel in bools) for (hasBlock in bools) for (hasPage in bools) {
                    val cmd = command(
                        config = CommandConfig(
                            enabled = enabled,
                            hidden = hidden,
                            requiresSelection = reqSel,
                            requiresBlock = reqBlock,
                            requiresPage = reqPage,
                        ),
                        condition = { cond },
                    )
                    val ctx = CommandContext(
                        selectionStart = 0,
                        selectionEnd = if (hasSel) 5 else 0,
                        currentBlockId = if (hasBlock) "b1" else null,
                        currentPageId = if (hasPage) "p1" else null,
                    )
                    val expected = enabled && !hidden && cond &&
                        (!reqSel || hasSel) && (!reqBlock || hasBlock) && (!reqPage || hasPage)
                    assertEquals(
                        expected,
                        cmd.isAvailable(ctx),
                        "enabled=$enabled hidden=$hidden cond=$cond reqSel=$reqSel reqBlock=$reqBlock " +
                            "reqPage=$reqPage hasSel=$hasSel hasBlock=$hasBlock hasPage=$hasPage",
                    )
                    checked++
                }
        assertEquals(512, checked)
    }

    @Test
    fun executeWithContext_should_RunExecute_When_Available() = runTest {
        var runs = 0
        val cmd = command(execute = { runs++; CommandResult.Success("ok") })
        assertEquals(CommandResult.Success("ok"), cmd.executeWithContext(CommandContext()))
        assertEquals(1, runs)
    }

    @Test
    fun executeWithContext_should_ReturnNotAvailableError_When_Unavailable() = runTest {
        var runs = 0
        val cmd = command(config = CommandConfig(hidden = true), execute = { runs++; CommandResult.Success("ok") })
        assertEquals(CommandResult.Error(NOT_AVAILABLE), cmd.executeWithContext(CommandContext()))
        assertEquals(0, runs)
    }

    // ---- CommandManager ----

    @Test
    fun executeCommand_should_ReturnSuccessAndNotify_When_MediaImage() = runTest {
        val events = mutableListOf<Triple<String, NotificationType, Long?>>()
        val manager = CommandManager(this) { msg, type, ms -> events += Triple(msg, type, ms) }
        advanceUntilIdle()

        val result = manager.executeCommand("media.image")

        assertEquals(
            CommandResult.Success("Image attachment requested", mapOf("action" to "attachImage")),
            result,
        )
        assertEquals(listOf(notice("Image attachment requested", NotificationType.SUCCESS, 3000L)), events)
    }

    @Test
    fun executeCommand_should_ReturnNotFoundAndNotify_When_IdUnknown() = runTest {
        val events = mutableListOf<Triple<String, NotificationType, Long?>>()
        val manager = CommandManager(this) { msg, type, ms -> events += Triple(msg, type, ms) }
        advanceUntilIdle()

        val result = manager.executeCommand("does.not.exist")

        assertEquals(CommandResult.Error("Command not found: does.not.exist"), result)
        assertEquals(listOf(notice("Command not found: does.not.exist", NotificationType.ERROR, 5000L)), events)
    }

    @Test
    fun executeCommand_should_ReturnNotAvailable_When_RequiresSelectionAndNoneGiven() = runTest {
        val events = mutableListOf<Triple<String, NotificationType, Long?>>()
        val manager = CommandManager(this) { msg, type, ms -> events += Triple(msg, type, ms) }
        advanceUntilIdle()

        val result = manager.executeCommand("text.bold")

        assertEquals(CommandResult.Error(NOT_AVAILABLE), result)
        assertEquals(listOf(notice(NOT_AVAILABLE, NotificationType.ERROR, 5000L)), events)
    }

    @Test
    fun executeCommand_should_FormatBold_When_SelectionPresent() = runTest {
        val events = mutableListOf<Triple<String, NotificationType, Long?>>()
        val manager = CommandManager(this) { msg, type, ms -> events += Triple(msg, type, ms) }
        advanceUntilIdle()

        val result = manager.executeCommand("text.bold", SELECTION)

        val success = result as CommandResult.Success
        assertEquals("Text formatted as bold", success.message)
        assertEquals("**hello**", success.data["formattedText"])
        assertEquals(0, success.data["selectionStart"])
        assertEquals(5, success.data["selectionEnd"])
        assertEquals(listOf(notice("Text formatted as bold", NotificationType.SUCCESS, 3000L)), events)
    }

    @Test
    fun executeCommand_should_ReturnFormatError_When_SelectionOutOfBounds() = runTest {
        val events = mutableListOf<Triple<String, NotificationType, Long?>>()
        val manager = CommandManager(this) { msg, type, ms -> events += Triple(msg, type, ms) }
        advanceUntilIdle()

        val result = manager.executeCommand(
            "text.bold",
            CommandContext(currentText = "hi", selectionStart = 0, selectionEnd = 99),
        )

        val error = result as CommandResult.Error
        assertTrue(error.message.startsWith("Failed to format text as bold:"), error.message)
        assertNotNull(error.exception)
        assertEquals(NotificationType.ERROR, events.single().second)
        assertEquals(5000L, events.single().third)
    }

    @Test
    fun executeCommand_should_ReturnNotAvailable_When_CommandIsHidden() = runTest {
        val manager = CommandManager(this)
        advanceUntilIdle()

        val result = manager.executeCommand("block.toggle-todo", CommandContext(currentBlockId = "b1"))

        assertEquals(CommandResult.Error(NOT_AVAILABLE), result)
    }

    @Test
    fun executeCommand_should_ReturnNotAvailable_When_SystemHelpHidden() = runTest {
        val manager = CommandManager(this)
        advanceUntilIdle()

        assertEquals(CommandResult.Error(NOT_AVAILABLE), manager.executeCommand("system.help"))
    }

    @Test
    fun executeCommand_should_NotThrow_When_NoNotificationCallback() = runTest {
        val manager = CommandManager.create(this)
        advanceUntilIdle()

        val result = manager.executeCommand("media.image")

        assertEquals(
            CommandResult.Success("Image attachment requested", mapOf("action" to "attachImage")),
            result,
        )
    }

    @Test
    fun getAvailableCommands_should_ReturnOnlyMediaImage_When_DefaultContext() = runTest {
        val manager = CommandManager(this)
        advanceUntilIdle()

        assertEquals(setOf("media.image"), manager.getAvailableCommands().map { it.id }.toSet())
    }

    @Test
    fun getAvailableCommands_should_IncludeFiveTextCommands_When_SelectionPresent() = runTest {
        val manager = CommandManager(this)
        advanceUntilIdle()

        assertEquals(
            setOf("media.image", "text.bold", "text.italic", "text.code", "text.strikethrough", "text.highlight"),
            manager.getAvailableCommands(SELECTION).map { it.id }.toSet(),
        )
    }
}
