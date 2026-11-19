// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames

/**
 * Gives [id] the user name [name], or clears it (null or blank) so it reads "Kind n" again (Story 3.5.7). A name
 * another element already shows is refused with [CommandError.NameTaken]; the cap is `SceneLimits.maxNameChars`.
 */
data class RenameElement(val id: ElementId, val name: String?) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val element = doc.scene[id] ?: raise(CommandError.UnknownElement(id.value))
        val cleaned = name?.trim()?.takeIf { it.isNotEmpty() }
        val limit = doc.scene.limits.maxNameChars
        if (cleaned != null && cleaned.length > limit) raise(CommandError.NameTooLong(limit))
        if (cleaned != null && ElementNames.isTaken(doc.scene, cleaned, except = id)) raise(CommandError.NameTaken(cleaned))
        val renamed = element.withBase(element.base.copy(name = cleaned)).let { if (it == element) it else it.touched() }
        finish(doc, doc.scene.replace(renamed).bind(), setOf(id))
    }
}
