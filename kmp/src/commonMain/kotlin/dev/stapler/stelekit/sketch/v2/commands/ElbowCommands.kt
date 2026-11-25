// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.binding.ElbowMid
import dev.stapler.stelekit.sketch.v2.binding.ElbowRouting
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ValueError

/**
 * Moves the middle segment of the elbow arrow [id] to [coord] (x for a vertical middle segment, y for a horizontal one; null puts it
 * back where the router chooses) and re-routes the arrow. One undo step; the choice is kept in the arrow's `extras`.
 */
data class SetElbowMid(val id: ElementId, val coord: Double?) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        if (coord != null && !coord.isFinite()) raise(CommandError.ValueRejected(ValueError.NonFinite(coord)))
        val scene = doc.scene
        val arrow = scene[id] as? ArrowElement ?: raise(CommandError.Unsupported(id.value, "elbow middle segment"))
        if (!arrow.elbowed) raise(CommandError.Unsupported(id.value, "elbow middle segment"))
        val kept = arrow.copy(base = arrow.base.copy(extras = ElbowMid.write(arrow.base.extras, coord)))
        val next = ElbowRouting.pointsOf(scene, kept)?.let { kept.copy(points = it) } ?: kept
        finish(doc, if (next == arrow) scene else scene.replace(next.touched()).bind(), setOf(id))
    }
}
