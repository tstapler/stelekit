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
import dev.stapler.stelekit.logging.LogLevel

/** `logs [count] [level]` prints the newest in-memory log entries, oldest first. */
class LogsCommand : ConsoleCommand {
    override val name = "logs"
    override val summary = "Show recent log entries (default 50)"
    override val usage = "logs [count] [debug|info|warn|error]"

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        var count = DEFAULT_COUNT
        var minLevel = LogLevel.DEBUG
        for (arg in args) {
            val number = arg.toIntOrNull()
            val level = LogLevel.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }
            when {
                number != null && number in 1..MAX_COUNT -> count = number
                level != null -> minLevel = level
                else -> return ConsoleError.Usage("Usage: $usage (count 1-$MAX_COUNT)").left()
            }
        }
        // LogManager keeps newest first; show chronologically so the latest line is at the bottom.
        val entries = context.logs().filter { it.level >= minLevel }.take(count).asReversed()
        if (entries.isEmpty()) out.info("No log entries.")
        entries.forEach { e ->
            val throwable = e.throwable?.let { " | ${it::class.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
            out.info("[${e.level}] ${e.tag}: ${e.message}$throwable")
        }
        return Unit.right()
    }

    private companion object {
        const val DEFAULT_COUNT = 50
        const val MAX_COUNT = 1_000
    }
}
