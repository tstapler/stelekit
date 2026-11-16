// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError

/**
 * Maps what a finished gesture asked for to the undoable [Command] that does it (Story 1.3.2). A tool command with no
 * mapping is refused with [CommandError.Unsupported] rather than dropped, so a tool added without its mapping fails loudly.
 */
fun ToolCommand.toCommand(): Either<CommandError, Command> = when (this) {
    is AddElementCommand -> AddElement(element).right()
    else -> CommandError.Unsupported(this::class.simpleName.orEmpty(), "toCommand").left()
}
