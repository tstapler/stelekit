// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.binding

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.finish
import dev.stapler.stelekit.sketch.v2.commands.sceneUnit
import dev.stapler.stelekit.sketch.v2.commands.touched
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

/** The scene after an edit to arrow bindings, and the elements it changed. */
data class BindingEdit(val scene: SketchSceneV2, val changed: Set<ElementId>)

/**
 * Binding, rebinding and unbinding of arrow ends (Story 3.3.2), the write half of what `followBoundArrows` reads.
 *
 * `reconcile` adds a missing `boundElements` entry on a target but keeps one that no longer points back (files in the
 * wild carry them), so an unbind must remove the target's entry itself or it would outlive the binding. Pure scene
 * functions return [BindingEdit]; the commands wrap them so one edit is one undo step.
 */
object BindingEngine {
    /** Clears the binding of [ends] on [arrowId] and the matching `boundElements` entry of the old target. */
    fun unbind(scene: SketchSceneV2, arrowId: ElementId, ends: Set<BindingEnd>): Either<CommandError, BindingEdit> = either {
        val arrow = scene[arrowId] as? ArrowElement ?: raise(CommandError.UnknownElement(arrowId.value))
        val dropped = ends.mapNotNull { if (it == BindingEnd.Start) arrow.startBinding else arrow.endBinding }.map { it.elementId }.toSet()
        if (dropped.isEmpty()) return@either BindingEdit(scene, emptySet())
        val next = arrow.copy(
            startBinding = arrow.startBinding.takeUnless { BindingEnd.Start in ends },
            endBinding = arrow.endBinding.takeUnless { BindingEnd.End in ends },
        )
        var out = scene.replace(next.touched()).bind()
        val changed = linkedSetOf(arrowId)
        for (target in dropped) if (!references(next, target)) out = withoutBoundRef(out, target, arrowId, changed).bind()
        BindingEdit(out, changed)
    }

    /**
     * Binds [end] of [arrowId] to [targetId] at the point of its outline nearest the end's current position, and puts the
     * end there. Fails when the target cannot be bound to (an arrow, a text, a point) or the end does not exist.
     */
    fun bind(scene: SketchSceneV2, arrowId: ElementId, end: BindingEnd, targetId: ElementId): Either<CommandError, BindingEdit> = either {
        val arrow = scene[arrowId] as? ArrowElement ?: raise(CommandError.UnknownElement(arrowId.value))
        val target = scene[targetId] ?: raise(CommandError.UnknownElement(targetId.value))
        if (target is ArrowElement || target.id == arrowId) raise(CommandError.Unsupported(targetId.value, "bind"))
        val at = (if (end == BindingEnd.Start) arrow.points.firstOrNull() else arrow.points.lastOrNull()) ?: raise(CommandError.Unsupported(arrowId.value, "bind"))
        val binding = BindingGeometry.bindingAt(target, at.x.value, at.y.value) ?: raise(CommandError.Unsupported(targetId.value, "bind"))
        val (x, y) = BindingGeometry.pointOf(target, binding) ?: raise(CommandError.Unsupported(targetId.value, "bind"))
        val snapped = ScenePoint(sceneUnit(x, scene.limits).bind(), sceneUnit(y, scene.limits).bind())
        val points = arrow.points.toMutableList().also { it[if (end == BindingEnd.Start) 0 else it.lastIndex] = snapped }
        val previous = if (end == BindingEnd.Start) arrow.startBinding else arrow.endBinding
        val next = if (end == BindingEnd.Start) arrow.copy(points = points, startBinding = binding) else arrow.copy(points = points, endBinding = binding)
        var out = scene.replace(next.touched()).bind()
        val changed = linkedSetOf(arrowId)
        previous?.elementId?.takeIf { it != targetId && !references(next, it) }?.let { out = withoutBoundRef(out, it, arrowId, changed).bind() }
        BindingEdit(out, changed)
    }

    /**
     * Unbinds every arrow end bound to a shape that was not among [moved] while its arrow was: an arrow dragged, nudged or
     * pasted-and-moved without its target leaves the target, so it must stop following it. An arrow whose bound shapes
     * moved with it keeps its bindings.
     */
    fun unbindStranded(scene: SketchSceneV2, moved: Set<ElementId>): Either<CommandError, BindingEdit> = either {
        var out = scene
        val changed = LinkedHashSet<ElementId>()
        for (id in moved) {
            val arrow = out[id] as? ArrowElement ?: continue
            val stranded = strandedEnds(arrow, moved)
            if (stranded.isEmpty()) continue
            val edit = unbind(out, id, stranded).bind()
            out = edit.scene
            changed += edit.changed
        }
        BindingEdit(out, changed)
    }

    private fun strandedEnds(arrow: ArrowElement, moved: Set<ElementId>): Set<BindingEnd> = buildSet {
        if (arrow.startBinding.targetsOutside(moved)) add(BindingEnd.Start)
        if (arrow.endBinding.targetsOutside(moved)) add(BindingEnd.End)
    }

    private fun Binding?.targetsOutside(moved: Set<ElementId>): Boolean = this != null && elementId !in moved

    private fun references(arrow: ArrowElement, target: ElementId): Boolean =
        arrow.startBinding?.elementId == target || arrow.endBinding?.elementId == target

    private fun withoutBoundRef(scene: SketchSceneV2, targetId: ElementId, arrowId: ElementId, changed: MutableSet<ElementId>): Either<CommandError, SketchSceneV2> {
        val target = scene[targetId] ?: return scene.right()
        val kept = boundRefsOf(target)?.filter { it.id != arrowId } ?: return scene.right()
        if (kept.size == boundRefsOf(target)?.size) return scene.right()
        val next: SceneElement = when (target) {
            is RectElement -> target.copy(boundElements = kept)
            is DiamondElement -> target.copy(boundElements = kept)
            is EllipseElement -> target.copy(boundElements = kept)
            else -> return scene.right()
        }
        changed += targetId
        return scene.replace(next.touched())
    }

    private fun boundRefsOf(e: SceneElement) = when (e) {
        is RectElement -> e.boundElements
        is DiamondElement -> e.boundElements
        is EllipseElement -> e.boundElements
        else -> null
    }
}

/** Binds one arrow end to a shape (the non-drag "Bind end" path and a rebind): one undo step, the end snaps onto the outline. */
data class BindArrowEnd(val arrow: ElementId, val end: BindingEnd, val target: ElementId) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val edit = BindingEngine.bind(doc.scene, arrow, end, target).bind()
        finish(doc, edit.scene, edit.changed)
    }
}

/** Frees one arrow end: it stays where it is and no longer follows its shape. */
data class UnbindArrowEnd(val arrow: ElementId, val end: BindingEnd) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val edit = BindingEngine.unbind(doc.scene, arrow, setOf(end)).bind()
        finish(doc, edit.scene, edit.changed)
    }
}
