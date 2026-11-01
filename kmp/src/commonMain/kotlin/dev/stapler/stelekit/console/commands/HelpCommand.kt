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

/** `help` lists every registered verb; `help <verb>` shows one verb's usage. Disabled verbs say why. */
class HelpCommand : ConsoleCommand {
    override val name = "help"
    override val summary = "List commands, or show usage for one"
    override val usage = "help [command]"

    override suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit> {
        if (args.size > 1) return ConsoleError.Usage("Usage: $usage").left()
        val wanted = args.firstOrNull()
        if (wanted == null) {
            context.registry.all.forEach { out.info(describe(it)) }
            return Unit.right()
        }
        val command = context.registry.find(wanted) ?: return ConsoleError.UnknownCommand(wanted).left()
        out.info(describe(command))
        out.info("usage: ${command.usage}")
        return Unit.right()
    }

    private fun describe(command: ConsoleCommand): String {
        val note = command.disabledReason?.let { " (disabled: $it)" }.orEmpty()
        return "${command.name.padEnd(NAME_WIDTH)} ${command.summary}$note"
    }

    private companion object {
        const val NAME_WIDTH = 10
    }
}
