// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager

/**
 * One console verb. Implementations are constructed in a composition root
 * (`GraphContentConsoleSetup.kt`) and registered in a [ConsoleRegistry]; `WiringCompletenessTest`
 * fails an implementation that no root constructs. Not related to `editor/commands`.
 */
interface ConsoleCommand {
    val name: String
    val summary: String
    val usage: String get() = name

    /** Non-null when the verb exists but cannot run in this build; `help` lists it as disabled with this reason. */
    val disabledReason: String? get() = null

    /** [args] excludes the verb. Output goes to [out]; the Left value is rendered as an ERR block. */
    suspend fun run(args: List<String>, context: ConsoleContext, out: ConsoleOutput): Either<ConsoleError, Unit>
}

/** What a command may reach. Deliberately small: no repositories until the command that needs one is built. */
class ConsoleContext(
    val registry: ConsoleRegistry,
    val logs: () -> List<LogEntry> = { LogManager.logs.value },
)

/** Where a command writes. Everything written is redacted by the session before it reaches scrollback. */
interface ConsoleOutput {
    fun info(text: String)
    fun error(text: String)
}
