// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.Committed
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

/**
 * The shapes a solved edit changed that the edit did not ask for: constrained shapes the solver moved or resized to keep a
 * constraint true. A typed size or dimension names what it targets; anything else a constraint pulled along is reported.
 */
object ConstraintCollateral {
    /** Names of the shapes [step] changed beyond what [command] (a typed resize, or null for a dimension label edit) targeted. */
    fun adjusted(command: Command?, step: Committed): List<String> {
        val after = step.after
        val named = HashSet<ElementId>()
        (command as? ResizeElement)?.let { named += it.id }
        val before = step.before.constraints.associateBy { it.id }
        after.constraints.filter { c -> before[c.id] != c }.forEach { named += it.elementIds }
        val constrained = after.constraints.flatMapTo(HashSet()) { it.elementIds }
        return step.changedIds
            .filter { it !in named && it in constrained && step.before.scene[it] != null }
            .mapNotNull { id -> after.scene[id]?.let(ElementNames::nameOf) }
            .sorted()
    }
}

/** A typed value changed constrained shapes it did not name: say so, since the solver moved them without being asked. */
internal fun EditorNotifier.announceAdjusted(command: Command?, step: Committed) {
    val names = ConstraintCollateral.adjusted(command, step)
    if (names.isNotEmpty()) toast(SketchConstraintStrings.Dimension.ADJUSTED_KEY, SketchConstraintStrings.Dimension.adjusted(names))
}
