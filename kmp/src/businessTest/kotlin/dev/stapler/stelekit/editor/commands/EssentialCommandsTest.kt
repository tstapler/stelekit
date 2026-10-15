package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val C0 = CommandContext()
private val CSEL = CommandContext(currentText = "hello world", selectionStart = 0, selectionEnd = 5)
private val CBLOCK = CommandContext(currentText = "x", currentBlockId = "b1", currentPageId = "p1")
private val CBAD = CommandContext(currentText = "hi", selectionStart = 0, selectionEnd = 99)
private val PALETTE = CommandContext(currentText = "", selectionStart = 0, selectionEnd = 0, currentBlockId = "page-uuid")

private val FIVE_TEXT_IDS = setOf("text.bold", "text.italic", "text.code", "text.strikethrough", "text.highlight")

/**
 * Behaviour of the built-in command set. Each command is invoked via `execute` directly (bypassing
 * `isAvailable`) unless the case is about palette visibility. The palette-visibility tests intentionally
 * pin the visible set to `media.image` (plus the five selection-gated text commands): production documents
 * every other command as hidden because it has no working mutation route, so a change to that set should be
 * a deliberate decision that updates these tests.
 */
class EssentialCommandsTest {

    private fun all() = EssentialCommands.getAll()

    private suspend fun run(command: EditorCommand, context: CommandContext): CommandResult.Success =
        assertIs<CommandResult.Success>(command.execute(context))

    // ---- registry shape and palette visibility ----

    @Test
    fun getAll_should_HaveUniqueNonBlankIds() {
        val ids = all().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate command ids: $ids")
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun getAll_should_ExposeOnlyMediaImage_When_PaletteContext() {
        val visible = all().filter { it.isAvailable(PALETTE) }.map { it.id }.toSet()
        assertEquals(setOf("media.image"), visible)
    }

    @Test
    fun getAll_should_ExposeMediaImageAndFiveTextCommands_When_Selection() {
        val visible = all().filter { it.isAvailable(CSEL) }.map { it.id }.toSet()
        assertEquals(FIVE_TEXT_IDS + "media.image", visible)
    }

    @Test
    fun systemSave_should_BeTheOnlyAsyncCommand() {
        assertEquals(listOf("system.save"), all().filter { it.config.async }.map { it.id })
    }

    // ---- text formatting ----

    @Test
    fun textFormatting_should_WrapSelection_When_SelectionPresent() = runTest {
        val expected = listOf(
            Triple(EssentialCommands.TextFormatting.bold, "**hello**", "Text formatted as bold"),
            Triple(EssentialCommands.TextFormatting.italic, "*hello*", "Text formatted as italic"),
            Triple(EssentialCommands.TextFormatting.code, "`hello`", "Text formatted as code"),
            Triple(EssentialCommands.TextFormatting.strikethrough, "~~hello~~", "Text formatted as strikethrough"),
            Triple(EssentialCommands.TextFormatting.highlight, "==hello==", "Text highlighted"),
        )
        for ((command, formatted, message) in expected) {
            val result = run(command, CSEL)
            assertEquals(message, result.message, command.id)
            assertEquals(formatted, result.data["formattedText"], command.id)
            assertEquals(0, result.data["selectionStart"], command.id)
            assertEquals(5, result.data["selectionEnd"], command.id)
        }
    }

    @Test
    fun textFormatting_should_ReturnError_When_SelectionOutOfBounds() = runTest {
        val expected = listOf(
            EssentialCommands.TextFormatting.bold to "Failed to format text as bold:",
            EssentialCommands.TextFormatting.italic to "Failed to format text as italic:",
            EssentialCommands.TextFormatting.code to "Failed to format text as code:",
            EssentialCommands.TextFormatting.strikethrough to "Failed to format text as strikethrough:",
            EssentialCommands.TextFormatting.highlight to "Failed to highlight text:",
        )
        for ((command, prefix) in expected) {
            val error = assertIs<CommandResult.Error>(command.execute(CBAD), command.id)
            assertTrue(error.message.startsWith(prefix), "${command.id}: ${error.message}")
            assertNotNull(error.exception, command.id)
        }
    }

    @Test
    fun link_should_UseSelectionOrPlaceholder() = runTest {
        val link = EssentialCommands.TextFormatting.link

        val withSelection = run(link, CSEL)
        assertEquals("[hello](https://example.com)", withSelection.data["formattedText"])
        assertEquals(true, withSelection.data["requiresUrlInput"])
        assertEquals("Link created", withSelection.message)

        val placeholder = run(link, C0)
        assertEquals("[link text](https://example.com)", placeholder.data["formattedText"])
        assertEquals(true, placeholder.data["requiresUrlInput"])

        val error = assertIs<CommandResult.Error>(link.execute(CBAD))
        assertTrue(error.message.startsWith("Failed to create link:"), error.message)
    }

    @Test
    fun heading_should_ReportNextLevel_When_ExistingPrefix() = runTest {
        // Asserts the reported level only; the prefix handling of an existing heading is not pinned.
        val texts = listOf("plain", "## a", "### a", "#### a", "##### a", "###### a")
        val levels = texts.map { text ->
            run(EssentialCommands.TextFormatting.heading, CommandContext(currentText = text)).message
        }
        assertEquals((1..6).map { "Heading level set to $it" }, levels)
    }

    @Test
    fun heading_should_ComputeLineBounds_When_MultiLine() = runTest {
        val heading = EssentialCommands.TextFormatting.heading
        val text = "first\nsecond"

        val firstLine = run(heading, CommandContext(currentText = text, selectionStart = 0, selectionEnd = 0))
        assertEquals(0, firstLine.data["lineStart"])
        assertEquals(5, firstLine.data["lineEnd"])

        val secondLine = run(heading, CommandContext(currentText = text, selectionStart = 8, selectionEnd = 8))
        assertEquals(6, secondLine.data["lineStart"])
        assertEquals(12, secondLine.data["lineEnd"])
    }

    // ---- block, navigation, media, system ----

    @Test
    fun blockCommands_should_EchoBlockIdOrEmpty() = runTest {
        val extras = listOf(
            EssentialCommands.BlockCommands.newBlock to ("position" to "after"),
            EssentialCommands.BlockCommands.delete to null,
            EssentialCommands.BlockCommands.moveUp to ("direction" to "up"),
            EssentialCommands.BlockCommands.moveDown to ("direction" to "down"),
            EssentialCommands.BlockCommands.indent to ("level" to "increment"),
            EssentialCommands.BlockCommands.outdent to ("level" to "decrement"),
        )
        for ((command, extra) in extras) {
            assertEquals("b1", run(command, CBLOCK).data["blockId"], command.id)
            assertEquals("", run(command, C0).data["blockId"], command.id)
            if (extra != null) assertEquals(extra.second, run(command, CBLOCK).data[extra.first], command.id)
        }
    }

    @Test
    fun toggleTodo_should_ReturnNothing() = runTest {
        assertEquals(CommandResult.Nothing, EssentialCommands.BlockCommands.toggleTodo.execute(CBLOCK))
    }

    @Test
    fun navigationCommands_should_ReturnActionKeys() = runTest {
        val search = run(EssentialCommands.NavigationCommands.search, C0)
        assertEquals("openSearch", search.data["action"])
        assertEquals("", search.data["query"])
        assertEquals("Search opened", search.message)

        val goto = run(EssentialCommands.NavigationCommands.gotoPage, C0)
        assertEquals("openPageSelector", goto.data["action"])
        assertEquals("Go to page opened", goto.message)

        val back = run(EssentialCommands.NavigationCommands.back, C0)
        assertEquals("navigateBack", back.data["action"])
        assertEquals("Navigated back", back.message)

        val forward = run(EssentialCommands.NavigationCommands.forward, C0)
        assertEquals("navigateForward", forward.data["action"])
        assertEquals("Navigated forward", forward.message)
    }

    @Test
    fun mediaImage_should_ReturnAttachImageAction() = runTest {
        assertEquals(
            CommandResult.Success("Image attachment requested", mapOf("action" to "attachImage")),
            EssentialCommands.MediaCommands.image.execute(C0),
        )
    }

    @Test
    fun systemCommands_should_ReturnActionKeys() = runTest {
        val expected = listOf(
            Triple(EssentialCommands.SystemCommands.save, "save", "Document saved"),
            Triple(EssentialCommands.SystemCommands.undo, "undo", "Undo performed"),
            Triple(EssentialCommands.SystemCommands.redo, "redo", "Redo performed"),
            Triple(EssentialCommands.SystemCommands.settings, "openSettings", "Settings opened"),
            Triple(EssentialCommands.SystemCommands.help, "openHelp", "Help opened"),
        )
        for ((command, action, message) in expected) {
            val result = run(command, C0)
            assertEquals(action, result.data["action"], command.id)
            assertEquals(message, result.message, command.id)
        }
        // Presence only: the value is the current clock reading.
        assertNotNull(run(EssentialCommands.SystemCommands.save, C0).data["timestamp"])
    }

    @Test
    fun execute_should_NeverThrow_When_SweepAllCommandsAcrossContexts() = runTest {
        val contexts = listOf(C0, CSEL, CBLOCK, CBAD) +
            listOf("plain", "## a", "first\nsecond").map { CommandContext(currentText = it) }
        // An exception escaping `execute` fails the test; every command must turn bad input into a result.
        for (command in all()) {
            for (context in contexts) {
                assertIs<CommandResult>(command.execute(context), "${command.id} with ${context.currentText}")
            }
        }
    }
}
