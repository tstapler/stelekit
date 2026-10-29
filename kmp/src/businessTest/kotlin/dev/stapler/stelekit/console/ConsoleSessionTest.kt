// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.console.commands.DiagCommand
import dev.stapler.stelekit.console.commands.HelpCommand
import dev.stapler.stelekit.console.commands.LogsCommand
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every session here runs on the test scheduler, so batching delays and "slow" commands use virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleSessionTest {
    private class MapSettings(var throwOnRead: Boolean = false) : Settings {
        private val store = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            if (throwOnRead) throw IllegalStateException("settings store unavailable")
            return store[key] ?: defaultValue
        }
        override fun putBoolean(key: String, value: Boolean) { store[key] = value }
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) = Unit
        override fun containsKey(key: String) = key in store
    }

    private class ProbeCommand(
        override val name: String,
        private val body: suspend (ConsoleOutput) -> Unit,
    ) : ConsoleCommand {
        var runs = 0
        override val summary = "probe"
        override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
            runs++
            body(out)
            return Unit.right()
        }
    }

    private fun TestScope.session(
        settings: MapSettings = MapSettings().also { it.putBoolean(DeveloperMode.KEY, true) },
        vararg extra: ConsoleCommand,
    ): Pair<ConsoleSession, DeveloperMode> {
        val mode = DeveloperMode(settings)
        val registry = ConsoleRegistry(listOf(HelpCommand(), DiagCommand { "diag report" }, LogsCommand()) + extra)
        val session = ConsoleSession(mode, registry, batchMillis = 5, dispatcher = StandardTestDispatcher(testScheduler))
        return session to mode
    }

    private fun ConsoleSession.text() = scrollback.lines.value.map { it.text }

    private fun ConsoleSession.errors() = scrollback.lines.value.filter { it.kind == ScrollbackKind.ERROR }.map { it.text }

    @Test
    fun run_should_DenyAndExecuteNothing_When_DeveloperModeIsOff() = runTest {
        val probe = ProbeCommand("probe") { it.info("ran") }
        val (session, _) = session(MapSettings(), probe)
        val result = session.run("probe")
        assertEquals(ConsoleError.Denied("developer mode off"), result.leftOrNull())
        assertEquals(0, probe.runs)
        assertTrue(session.text().isEmpty())
        session.close()
    }

    @Test
    fun run_should_TreatTheFlagAsOff_When_TheSettingsStoreThrows() = runTest {
        val settings = MapSettings().also { it.putBoolean(DeveloperMode.KEY, true) }
        val probe = ProbeCommand("probe") { it.info("ran") }
        val (session, mode) = session(settings, probe)
        settings.throwOnRead = true
        assertFalse(mode.isEnabled())
        assertEquals(ConsoleError.Denied("developer mode off"), session.run("probe").leftOrNull())
        assertEquals(0, probe.runs)
        session.close()
    }

    @Test
    fun run_should_PrintEveryVerbInOrder_When_HelpRuns() = runTest {
        val (session, _) = session()
        assertTrue(session.run("help").isRight())
        val out = session.text()
        assertEquals("help", out.first())
        assertEquals(listOf("diag", "help", "logs"), out.drop(1).map { it.substringBefore(' ') })
        session.close()
    }

    @Test
    fun run_should_RenderDiagReport_And_ErrBlockForUnknownVerbs() = runTest {
        val (session, _) = session()
        session.run("diag")
        session.run("nope")
        assertTrue("diag report" in session.text())
        assertEquals(listOf("Unknown command 'nope'. Type 'help' for the list."), session.errors())
        session.close()
    }

    @Test
    fun logs_should_ListNewestEntriesOldestFirst_And_RejectBadArguments() = runTest {
        val command = LogsCommand()
        val registry = ConsoleRegistry(listOf(command))
        val entries = listOf("third", "second", "first").map { LogEntry(LogLevel.INFO, "T", it) }
        val lines = mutableListOf<String>()
        val out = object : ConsoleOutput {
            override fun info(text: String) { lines += text }
            override fun error(text: String) { lines += "ERR $text" }
        }
        val ctx = ConsoleContext(registry, logs = { entries })
        assertTrue(command.run(listOf("2"), ctx, out).isRight())
        assertEquals(listOf("[INFO] T: second", "[INFO] T: third"), lines)
        assertIs<ConsoleError.Usage>(command.run(listOf("banana"), ctx, out).leftOrNull())
    }

    @Test
    fun run_should_ShowErrBlockAndSurvive_When_ACommandThrowsOutOfMemoryError() = runTest {
        val boom = ProbeCommand("boom") { throw OutOfMemoryError("synthetic") }
        val (session, _) = session(extra = arrayOf(boom))
        val result = session.run("boom")
        assertIs<ConsoleError.Failed>(result.leftOrNull())
        assertTrue(session.errors().single().contains("OutOfMemoryError"))
        assertTrue(session.run("help").isRight(), "session must keep working after an Error")
        session.close()
    }

    @Test
    fun output_should_BeRedacted_And_NeverReachHistoryUnredacted() = runTest {
        val leak = ProbeCommand("remote") { it.info("origin https://u:ghp_x1y2z3@github.com/o/r.git") }
        val (session, _) = session(extra = arrayOf(leak))
        session.run("remote --token ghp_abcdefghij1234567890")
        val everything = session.text() + session.history.value
        assertTrue(everything.any { "https://[redacted]@github.com/o/r.git" in it }, everything.toString())
        assertTrue(everything.none { "ghp_" in it || "x1y2z3" in it }, "secret reached scrollback or history: $everything")
        session.close()
    }

    @Test
    fun turningDeveloperModeOff_should_CancelTheRunningCommand_And_ClearScrollback() = runTest {
        val started = CompletableDeferred<Unit>()
        val slow = ProbeCommand("slow") { out ->
            out.info("working")
            started.complete(Unit)
            awaitCancellation()
        }
        val (session, mode) = session(extra = arrayOf(slow))
        session.submit("slow")
        advanceUntilIdle()
        assertTrue(started.isCompleted)
        assertTrue("working" in session.text())
        assertTrue(session.isRunning.value)

        mode.set(false)
        advanceUntilIdle()

        assertFalse(session.isRunning.value)
        assertTrue(session.text().isEmpty(), "late 'Cancelled.' output must not repopulate scrollback: ${session.text()}")
        assertTrue(session.history.value.isEmpty())
        session.close()
    }

    @Test
    fun cancel_should_StopTheCommand_And_ReportCancelled() = runTest {
        val slow = ProbeCommand("slow") { awaitCancellation() }
        val (session, _) = session(extra = arrayOf(slow))
        session.submit("slow")
        runCurrent()
        assertTrue(session.isRunning.value)
        session.cancel()
        advanceUntilIdle()
        assertFalse(session.isRunning.value)
        assertTrue("Cancelled." in session.errors(), session.errors().toString())
        session.close()
    }

    @Test
    fun run_should_RefuseASecondCommand_While_OneIsRunning() = runTest {
        val slow = ProbeCommand("slow") { awaitCancellation() }
        val (session, _) = session(extra = arrayOf(slow))
        session.submit("slow")
        runCurrent()
        assertIs<ConsoleError.Failed>(session.run("help").leftOrNull())
        session.cancel()
        advanceUntilIdle()
        session.close()
    }

    @Test
    fun registry_should_RejectDuplicateVerbs() {
        val a = ProbeCommand("same") {}
        val b = ProbeCommand("SAME") {}
        val error = runCatching { ConsoleRegistry(listOf(a, b)) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(error)
    }

    @Test
    fun exportFileBaseName_should_FollowTheDocumentedPattern() = runTest {
        val (session, _) = session()
        val name = session.exportFileBaseName(kotlin.time.Instant.fromEpochSeconds(0), kotlinx.datetime.TimeZone.UTC)
        assertEquals("console-19700101-000000", name)
        session.close()
    }
}
