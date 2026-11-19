// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint

/**
 * Replaces the vertices of one line (Task 3.2.1b, point editing: move, insert or delete a vertex is one undo step).
 * A line keeps at least two vertices and at most `SceneLimits.maxPointsPerElement`.
 */
data class SetLinePoints(val id: ElementId, val points: List<ScenePoint>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val line = scene[id] as? LineElement ?: raise(CommandError.Unsupported(id.value, "set line points"))
        if (points.size < MIN_VERTICES || points.size > scene.limits.maxPointsPerElement) raise(CommandError.Unsupported(id.value, "line vertex count"))
        for (p in points) {
            sceneUnit(p.x.value, scene.limits).bind()
            sceneUnit(p.y.value, scene.limits).bind()
        }
        val next = line.copy(points = points)
        finish(doc, if (next == line) scene else scene.replace(next.touched()).bind(), setOf(id))
    }

    private companion object {
        const val MIN_VERTICES = 2
    }
}
