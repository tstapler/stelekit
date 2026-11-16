// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.binding

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.sceneUnit
import dev.stapler.stelekit.sketch.v2.commands.touched
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

/** The scene after bound arrows followed their targets, and the arrows that moved. */
data class FollowResult(val scene: SketchSceneV2, val arrows: Set<ElementId>)

/**
 * Keeps arrow ends on the shapes they are bound to (Story 3.3.1, ux.md section 5 flow step 3): after a command moved,
 * resized or rotated the shapes in [changed], every arrow end bound to one of them is put back at its bound point. It runs
 * inside the same command, so the follow is part of the one undo step. An arrow whose own end is already there is left
 * untouched, so an arrow moved together with its target keeps its bytes. A binding whose target is gone is left for
 * `reconcile` to clear.
 */
fun SketchSceneV2.followBoundArrows(changed: Set<ElementId>): Either<CommandError, FollowResult> = either {
    var out = this@followBoundArrows
    val moved = LinkedHashSet<ElementId>()
    for (e in elementsInOrder) {
        if (e !is ArrowElement) continue
        val points = followedPoints(out, e, changed).bind()
        if (points == e.points) continue
        out = out.replace(e.copy(points = points).touched()).bind()
        moved += e.id
    }
    FollowResult(out, moved)
}

private fun followedPoints(scene: SketchSceneV2, arrow: ArrowElement, changed: Set<ElementId>): Either<CommandError, List<ScenePoint>> = either {
    if (arrow.points.isEmpty()) return@either arrow.points
    val points = arrow.points.toMutableList()
    arrow.startBinding?.takeIf { it.elementId in changed }?.let { b -> boundPoint(scene, b).bind()?.let { points[0] = it } }
    arrow.endBinding?.takeIf { it.elementId in changed }?.let { b -> boundPoint(scene, b).bind()?.let { points[points.lastIndex] = it } }
    points
}

/** The point [binding] puts its arrow end at, or null when the target is gone or cannot be bound to. */
private fun boundPoint(scene: SketchSceneV2, binding: Binding): Either<CommandError, ScenePoint?> = either {
    val target = scene[binding.elementId] ?: return@either null
    val (x, y) = BindingGeometry.pointOf(target, binding) ?: return@either null
    ScenePoint(sceneUnit(x, scene.limits).bind(), sceneUnit(y, scene.limits).bind())
}
