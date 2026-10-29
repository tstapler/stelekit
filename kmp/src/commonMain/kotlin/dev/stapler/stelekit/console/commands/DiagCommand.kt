// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.commands

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.console.ConsoleCommand
import dev.stapler.stelekit.console.ConsoleContext
import dev.stapler.stelekit.console.ConsoleError
import dev.stapler.stelekit.console.ConsoleOutput
import dev.stapler.stelekit.console.ConsoleWriteLog
import kotlinx.coroutines.CancellationException

/**
 * `diag` prints the same graph diagnostics report the Logs screen offers
 * (`GraphDiagnosticsCollector.collect`, already redacted). [collect] is null when no graph is active.
 */
class DiagCommand(
    private val writeLog: ConsoleWriteLog? = null,
    private val collect: (suspend () -> String)?,
) : ConsoleCommand {
    override val name = "diag"
    override val summary = "Print the graph diagnostics report"

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        if (args.isNotEmpty()) return ConsoleError.Usage("Usage: $usage").left()
        val collector = collect ?: return ConsoleError.Failed("No graph is open, so there is nothing to diagnose.").left()
        val report = try {
            collector()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ConsoleError.Failed("Diagnostics failed: ${e::class.simpleName}: ${e.message.orEmpty()}").left()
        }
        out.info(report)
        val writes = writeLog?.entries.orEmpty()
        if (writes.isNotEmpty()) {
            out.info("Console writes this session (DB-only, not in markdown):")
            writes.forEach { out.info("  $it") }
        }
        return Unit.right()
    }
}
