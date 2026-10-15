package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun cmd(
    id: String,
    type: CommandType = CommandType.CUSTOM,
    label: String = id,
    config: CommandConfig = CommandConfig(),
    execute: suspend (CommandContext) -> CommandResult = { CommandResult.Success("ok") },
) = EditorCommand(id = id, type = type, label = label, config = config, execute = execute)

private const val NOT_AVAILABLE = "Command not available in current context"

/** `CommandRegistry` storage semantics and `CommandSystem` execution, history and error handling. */
class CommandSystemRegistryTest {

    // ---- CommandRegistry ----

    @Test
    fun getCommand_should_ReturnRegistered_And_NullForUnknown() = runTest {
        val registry = CommandRegistry()
        val a = cmd("a")
        registry.register(a)

        assertEquals(a, registry.getCommand("a"))
        assertNull(registry.getCommand("zzz"))
    }

    @Test
    fun register_should_Overwrite_When_SameIdRegisteredTwice() = runTest {
        val registry = CommandRegistry()
        registry.register(cmd("a", label = "one"))
        registry.register(cmd("a", label = "two"))

        assertEquals(1, registry.getAllCommands().size)
        assertEquals("two", registry.getCommand("a")?.label)
    }

    @Test
    fun unregister_should_RemoveExisting_And_NotThrow_When_Unknown() = runTest {
        val registry = CommandRegistry()
        registry.register(cmd("a"))

        registry.unregister("a")
        assertNull(registry.getCommand("a"))

        registry.unregister("never")
        assertEquals(0, registry.getAllCommands().size)
    }

    @Test
    fun registerAll_should_RegisterEach_And_AcceptEmptyList() = runTest {
        val registry = CommandRegistry()
        registry.registerAll(listOf(cmd("a"), cmd("b"), cmd("c")))
        registry.registerAll(emptyList())

        assertEquals(3, registry.getAllCommands().size)
    }

    @Test
    fun getCommandsByType_should_ReturnOnlyMatchingType() = runTest {
        val registry = CommandRegistry()
        registry.registerAll(listOf(cmd("t", type = CommandType.TEXT), cmd("b", type = CommandType.BLOCK)))

        assertEquals(listOf("t"), registry.getCommandsByType(CommandType.TEXT).map { it.id })
        assertEquals(emptyList(), registry.getCommandsByType(CommandType.SYSTEM))
    }

    @Test
    fun getAvailableCommands_should_FilterByIsAvailable_When_ContextGiven() = runTest {
        val registry = CommandRegistry()
        registry.registerAll(
            listOf(
                cmd("hidden", config = CommandConfig(hidden = true)),
                cmd("needsSel", config = CommandConfig(requiresSelection = true)),
                cmd("plain"),
            ),
        )

        assertEquals(listOf("plain"), registry.getAvailableCommands(CommandContext()).map { it.id })
    }

    // ---- CommandSystem ----

    @Test
    fun executeCommand_should_ReturnNotFound_And_LeaveHistoryEmpty_When_IdUnknown() = runTest {
        val system = CommandSystem(CommandRegistry())

        val result = system.executeCommand("nope", CommandContext())

        assertEquals(CommandResult.Error("Command not found: nope"), result)
        assertTrue(system.getHistory().value.isEmpty())
    }

    @Test
    fun executeCommand_should_ReturnSuccess_When_AsyncAndNonAsync() = runTest {
        val registry = CommandRegistry()
        registry.register(cmd("sync", config = CommandConfig(async = false)))
        registry.register(cmd("async", config = CommandConfig(async = true)))
        val system = CommandSystem(registry)

        assertEquals(CommandResult.Success("ok"), system.executeCommand("sync", CommandContext()))
        assertEquals(CommandResult.Success("ok"), system.executeCommand("async", CommandContext()))
    }

    @Test
    fun executeCommand_should_PassThroughAndRecord_When_PartialNothingOrError() = runTest {
        val partial = CommandResult.Partial("m", 2, 5)
        val error = CommandResult.Error("e", null, "E1")
        val registry = CommandRegistry()
        registry.register(cmd("partial") { partial })
        registry.register(cmd("nothing") { CommandResult.Nothing })
        registry.register(cmd("error") { error })
        val system = CommandSystem(registry)

        assertEquals(partial, system.executeCommand("partial", CommandContext()))
        assertEquals(CommandResult.Nothing, system.executeCommand("nothing", CommandContext()))
        assertEquals(error, system.executeCommand("error", CommandContext()))
        assertEquals(3, system.getHistory().value.size)
    }

    @Test
    fun executeCommand_should_WrapException_And_SkipHistory_When_ExecuteThrows() = runTest {
        val registry = CommandRegistry()
        registry.register(cmd("boom") { throw IllegalStateException("boom") })
        val system = CommandSystem(registry)

        val result = system.executeCommand("boom", CommandContext())

        val error = assertIs<CommandResult.Error>(result)
        assertEquals("Unexpected error: boom", error.message)
        // Type and message, not identity: coroutine stack-trace recovery may hand back a copy.
        assertIs<IllegalStateException>(error.exception)
        assertEquals("boom", error.exception?.message)
        assertTrue(system.getHistory().value.isEmpty())
    }

    @Test
    fun executeCommand_should_Rethrow_When_CancellationException() = runTest {
        val registry = CommandRegistry()
        registry.register(
            cmd("cancel", config = CommandConfig(async = true)) { throw CancellationException("c") },
        )
        val system = CommandSystem(registry)

        assertFailsWith<CancellationException> { system.executeCommand("cancel", CommandContext()) }
    }

    @Test
    fun executeCommand_should_ReturnNotAvailable_And_StillRecord_When_CommandHidden() = runTest {
        val registry = CommandRegistry()
        registry.register(cmd("h", config = CommandConfig(hidden = true)))
        val system = CommandSystem(registry)

        val result = system.executeCommand("h", CommandContext())

        assertEquals(CommandResult.Error(NOT_AVAILABLE), result)
        assertEquals(1, system.getHistory().value.size)
    }

    @Test
    fun history_should_CapAt100_NewestFirst_When_101Runs() = runTest {
        val registry = CommandRegistry()
        registry.register(cmd("c"))
        val system = CommandSystem(registry)

        repeat(101) { i -> system.executeCommand("c", CommandContext(currentText = "run-$i")) }

        val history = system.getHistory().value
        assertEquals(100, history.size)
        assertEquals("run-100", history.first().context.currentText)
        assertFalse(history.any { it.context.currentText == "run-0" })
    }

    @Test
    fun clearHistory_should_Empty_And_Delegates_Should_ReachRegistry() = runTest {
        val registry = CommandRegistry()
        val c = cmd("c")
        val hidden = cmd("h", config = CommandConfig(hidden = true))
        registry.registerAll(listOf(c, hidden))
        val system = CommandSystem(registry)
        system.executeCommand("c", CommandContext())
        assertEquals(1, system.getHistory().value.size)

        system.clearHistory()

        assertTrue(system.getHistory().value.isEmpty())
        assertEquals(c, system.getCommand("c"))
        assertEquals(listOf(c), system.getAvailableCommands(CommandContext()))
    }
}
