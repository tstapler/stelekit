// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.binding.followBoundArrows
import dev.stapler.stelekit.sketch.v2.constraints.withoutOrphans
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ValueError
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.sketch.v2.scene.reconcile

// Element commands (Story 1.3.2a, 1.3.2c). Every command is pure and returns the new document plus the ids it touched;
// `Transaction` runs `reconcile` after each, and the commands that can orphan a reference run it themselves so they are
// also correct standalone. Commands do not enforce `locked`: the tools decide what a locked element may do.

internal fun sceneUnit(value: Double, limits: SceneLimits): Either<CommandError, SceneUnit> =
    SceneUnit.of(value, limits).mapLeft { CommandError.ValueRejected(it) }

internal fun SceneElement.touched(): SceneElement = withBase(base.copy(version = base.version + 1))

/** Paint order of the whole scene: layer order, then z-key, then array order (the order `DrawListBuilder` paints in). */
internal fun effectiveOrder(scene: SketchSceneV2): List<SceneElement> {
    val rank = scene.layers.associate { it.id to it.order }
    return scene.elementsInOrder.withIndex()
        .sortedWith(compareBy({ rank[it.value.base.layerId] ?: Layer.DefaultLayer.order }, { it.value.base.zKey }, { it.index }))
        .map { it.value }
}

/** The key that puts a new element on top of [layer]: after the highest key it holds. */
internal fun topKey(scene: SketchSceneV2, layer: LayerId): ZKey {
    val highest = scene.elementsInOrder.filter { it.base.layerId == layer }.maxOfOrNull { it.base.zKey }
    return ZKeys.after(highest)
}

internal fun finish(doc: SketchDocumentV2, scene: SketchSceneV2, touched: Set<ElementId>): Applied {
    val repaired = reconcile(scene)
    return Applied(doc.copy(scene = repaired.scene).withoutOrphans(), touched + repaired.changed)
}

private fun SketchSceneV2.element(id: ElementId): Either<CommandError, SceneElement> =
    this[id]?.right() ?: CommandError.UnknownElement(id.value).left()

private fun SketchSceneV2.requireAll(ids: Collection<ElementId>): Either<CommandError, List<SceneElement>> = either {
    ids.distinct().map { element(it).bind() }
}

/** Puts [elements] on top of their layers, in the given order, each with a fresh z-key. */
private fun addOnTop(scene: SketchSceneV2, elements: List<SceneElement>): Either<CommandError, SketchSceneV2> = either {
    var out = scene
    val tops = HashMap<LayerId, ZKey?>()
    for (e in elements) {
        val layer = e.base.layerId
        if (out.layer(layer) == null) raise(CommandError.UnknownLayer(layer.value))
        val previous = if (layer in tops) tops[layer] else out.elementsInOrder.filter { it.base.layerId == layer }.maxOfOrNull { it.base.zKey }
        val key = ZKeys.after(previous)
        tops[layer] = key
        out = out.add(e.withBase(e.base.copy(zKey = key))).bind()
    }
    out
}

/** Adds [element] on top of its layer; its own `zKey` is replaced. */
data class AddElement(val element: SceneElement) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = AddElements(listOf(element)).apply(doc)
}

data class AddElements(val elements: List<SceneElement>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val (named, counters) = doc.nameCounters.assign(elements)
        finish(doc.copy(nameCounters = counters), addOnTop(doc.scene, named).bind(), named.mapTo(LinkedHashSet()) { it.id })
    }
}

private fun withBox(e: BoxElement, x: SceneUnit, y: SceneUnit, w: SceneUnit, h: SceneUnit): SceneElement = when (e) {
    is RectElement -> e.copy(x = x, y = y, width = w, height = h)
    is DiamondElement -> e.copy(x = x, y = y, width = w, height = h)
    is TextElement -> e.copy(x = x, y = y, width = w, height = h)
    is ImageElement -> e.copy(x = x, y = y, width = w, height = h)
    is FrameElement -> e.copy(x = x, y = y, width = w, height = h)
}

private fun pointsOf(e: SceneElement): List<ScenePoint>? = when (e) {
    is LineElement -> e.points
    is ArrowElement -> e.points
    is FreedrawElement -> e.points
    else -> null
}

private fun withPoints(e: SceneElement, points: List<ScenePoint>): SceneElement = when (e) {
    is LineElement -> e.copy(points = points)
    is ArrowElement -> e.copy(points = points)
    is FreedrawElement -> e.copy(points = points)
    else -> e
}

internal fun SceneElement.shifted(dx: Double, dy: Double, limits: SceneLimits): Either<CommandError, SceneElement> = either {
    fun u(v: SceneUnit, d: Double) = sceneUnit(v.value + d, limits).bind()
    fun p(pt: ScenePoint) = ScenePoint(u(pt.x, dx), u(pt.y, dy))
    when (val e = this@shifted) {
        is BoxElement -> withBox(e, u(e.x, dx), u(e.y, dy), e.width, e.height)
        is EllipseElement -> e.copy(cx = u(e.cx, dx), cy = u(e.cy, dy))
        is PointElement -> e.copy(x = u(e.x, dx), y = u(e.y, dy))
        is ArcElement -> e.copy(cx = u(e.cx, dx), cy = u(e.cy, dy))
        is LineElement -> e.copy(points = e.points.map(::p))
        is ArrowElement -> e.copy(points = e.points.map(::p))
        is FreedrawElement -> e.copy(points = e.points.map(::p))
    }
}

/** [ids] plus, for a frame, its members, and for any container the text labels bound to it. */
private fun moveSet(scene: SketchSceneV2, ids: Collection<ElementId>): Either<CommandError, Set<ElementId>> = either {
    val out = LinkedHashSet<ElementId>()
    for (e in scene.requireAll(ids).bind()) {
        out += e.id
        if (e is FrameElement) scene.elementsInOrder.filter { it.base.frameId == e.id }.forEach { out += it.id }
    }
    scene.elementsInOrder.filter { it is TextElement && it.containerId in out }.forEach { out += it.id }
    out
}

/** Translates [ids] by ([dx], [dy]) scene units; a frame takes its members and a container its bound labels along, and arrow ends bound to a moved shape follow it. */
data class MoveElements(val ids: List<ElementId>, val dx: Double, val dy: Double) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        var out = scene
        val moved = moveSet(scene, ids).bind()
        for (id in moved) out = out.replace(scene.element(id).bind().shifted(dx, dy, scene.limits).bind().touched()).bind()
        val followed = out.followBoundArrows(moved).bind()
        finish(doc, followed.scene, moved + followed.arrows)
    }
}

/** Sets the bounding box of one element; lines and freedraw strokes are scaled into it, an ellipse is fitted to it, arrow ends bound to it follow. */
data class ResizeElement(val id: ElementId, val x: Double, val y: Double, val width: Double, val height: Double) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val limits = scene.limits
        val e = scene.element(id).bind()
        val ux = sceneUnit(x, limits).bind()
        val uy = sceneUnit(y, limits).bind()
        val uw = sceneUnit(width, limits).bind()
        val uh = sceneUnit(height, limits).bind()
        if (width < 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(width)))
        if (height < 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(height)))
        val next: SceneElement = when (e) {
            is BoxElement -> withBox(e, ux, uy, uw, uh)
            is EllipseElement -> {
                if (width <= 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(width)))
                if (height <= 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(height)))
                e.copy(
                    cx = sceneUnit(x + width / HALF_DIVISOR, limits).bind(), cy = sceneUnit(y + height / HALF_DIVISOR, limits).bind(),
                    rx = sceneUnit(width / HALF_DIVISOR, limits).bind(), ry = sceneUnit(height / HALF_DIVISOR, limits).bind(),
                )
            }
            is LineElement, is ArrowElement, is FreedrawElement -> withPoints(e, fitPoints(pointsOf(e).orEmpty(), x, y, width, height, limits).bind())
            is PointElement, is ArcElement -> raise(CommandError.Unsupported(id.value, "resize"))
        }
        val resized = if (next == e) scene else scene.replace(next.touched()).bind()
        val followed = resized.followBoundArrows(setOf(id)).bind()
        finish(doc, followed.scene, setOf(id) + followed.arrows)
    }

    private fun fitPoints(points: List<ScenePoint>, x: Double, y: Double, w: Double, h: Double, limits: SceneLimits): Either<CommandError, List<ScenePoint>> = either {
        if (points.isEmpty()) return@either points
        val minX = points.minOf { it.x.value }
        val minY = points.minOf { it.y.value }
        val spanX = points.maxOf { it.x.value } - minX
        val spanY = points.maxOf { it.y.value } - minY
        fun map(v: Double, min: Double, span: Double, to: Double, size: Double) = if (span == 0.0) to + (v - min) else to + (v - min) / span * size
        points.map { ScenePoint(sceneUnit(map(it.x.value, minX, spanX, x, w), limits).bind(), sceneUnit(map(it.y.value, minY, spanY, y, h), limits).bind()) }
    }

    private companion object {
        const val HALF_DIVISOR = 2.0
    }
}

/** Sets the absolute rotation, in radians about the element's centre, of one rectangle, diamond, ellipse, text, image or frame. */
data class RotateElement(val id: ElementId, val angle: Double) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        if (!angle.isFinite()) raise(CommandError.ValueRejected(ValueError.NonFinite(angle)))
        val e = doc.scene.element(id).bind()
        val next: SceneElement = when (e) {
            is RectElement -> e.copy(angle = angle)
            is DiamondElement -> e.copy(angle = angle)
            is TextElement -> e.copy(angle = angle)
            is ImageElement -> e.copy(angle = angle)
            is FrameElement -> e.copy(angle = angle)
            is EllipseElement -> e.copy(angle = angle)
            is LineElement, is ArrowElement, is FreedrawElement, is PointElement, is ArcElement -> raise(CommandError.Unsupported(id.value, "rotate"))
        }
        val rotated = if (next == e) doc.scene else doc.scene.replace(next.touched()).bind()
        val followed = rotated.followBoundArrows(setOf(id)).bind()
        finish(doc, followed.scene, setOf(id) + followed.arrows)
    }
}

/** Removes [ids] and the text labels bound to them; arrows bound to a removed element are unbound where they end. */
data class DeleteElements(val ids: List<ElementId>) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val doomed = LinkedHashSet<ElementId>()
        scene.requireAll(ids).bind().forEach { doomed += it.id }
        scene.elementsInOrder.filter { it is TextElement && it.containerId in doomed }.forEach { doomed += it.id }
        var out = scene
        for (id in doomed) out = out.remove(id).bind()
        finish(doc, out, doomed)
    }
}

/** A partial style change; a null field leaves the style alone. `sharpCorners` removes roundness, [roundness] sets it. */
data class StylePatch(
    val strokeColor: String? = null,
    val backgroundColor: String? = null,
    val fillStyle: FillStyle? = null,
    val strokeWidth: Double? = null,
    val strokeStyle: StrokeStyle? = null,
    val roughness: Int? = null,
    val roundness: Roundness? = null,
    val sharpCorners: Boolean = false,
    val opacity: Int? = null,
)

/** Applies [patch] to [ids]. */
data class StyleElements(val ids: List<ElementId>, val patch: StylePatch) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        patch.strokeWidth?.let { w ->
            if (!w.isFinite()) raise(CommandError.ValueRejected(ValueError.NonFinite(w)))
            if (w < 0.0) raise(CommandError.ValueRejected(ValueError.NotPositive(w)))
        }
        patch.opacity?.let { o ->
            if (o !in 0..ElementBase.FULL_OPACITY) raise(CommandError.ValueRejected(ValueError.OutOfRange(o.toDouble(), ElementBase.FULL_OPACITY.toDouble())))
        }
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (e in out.requireAll(ids).bind()) {
            val s = e.base.style
            val style = s.copy(
                strokeColor = patch.strokeColor ?: s.strokeColor,
                backgroundColor = patch.backgroundColor ?: s.backgroundColor,
                fillStyle = patch.fillStyle ?: s.fillStyle,
                strokeWidth = patch.strokeWidth ?: s.strokeWidth,
                strokeStyle = patch.strokeStyle ?: s.strokeStyle,
                roughness = patch.roughness ?: s.roughness,
                roundness = if (patch.sharpCorners) null else patch.roundness ?: s.roundness,
            )
            val base = e.base.copy(style = style, opacity = patch.opacity ?: e.base.opacity)
            if (base == e.base) continue
            out = out.replace(e.withBase(base).touched()).bind()
            changed += e.id
        }
        finish(doc, out, changed)
    }
}

enum class ZOrderMove { ToFront, Forward, Backward, ToBack }

/** Moves [ids] within their layers; the relative order of the moved elements is kept. */
data class ReorderElements(val ids: List<ElementId>, val move: ZOrderMove) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        val selected = scene.requireAll(ids).bind().mapTo(LinkedHashSet()) { it.id }
        var out = scene
        val changed = LinkedHashSet<ElementId>()
        val byLayer = effectiveOrder(scene).groupBy { it.base.layerId }
        for ((_, layer) in byLayer) {
            if (layer.none { it.id in selected }) continue
            val target = arranged(layer, selected)
            if (target.map { it.id } == layer.map { it.id }) continue
            for ((element, key) in rekeyed(target, selected)) {
                if (element.base.zKey == key) continue
                out = out.replace(element.withBase(element.base.copy(zKey = key)).touched()).bind()
                changed += element.id
            }
        }
        finish(doc, out, changed)
    }

    private fun arranged(layer: List<SceneElement>, selected: Set<ElementId>): List<SceneElement> = when (move) {
        ZOrderMove.ToFront -> layer.filter { it.id !in selected } + layer.filter { it.id in selected }
        ZOrderMove.ToBack -> layer.filter { it.id in selected } + layer.filter { it.id !in selected }
        ZOrderMove.Forward -> layer.toMutableList().also { l ->
            for (i in l.size - 2 downTo 0) if (l[i].id in selected && l[i + 1].id !in selected) l[i] = l[i + 1].also { l[i + 1] = l[i] }
        }
        ZOrderMove.Backward -> layer.toMutableList().also { l ->
            for (i in 1 until l.size) if (l[i].id in selected && l[i - 1].id !in selected) l[i] = l[i - 1].also { l[i - 1] = l[i] }
        }
    }

    /** New keys for the moved elements only; the whole layer is renumbered when two neighbours leave no room. */
    private fun rekeyed(target: List<SceneElement>, selected: Set<ElementId>): List<Pair<SceneElement, ZKey>> {
        val result = ArrayList<Pair<SceneElement, ZKey>>()
        var i = 0
        while (i < target.size) {
            if (target[i].id !in selected) { i++; continue }
            var j = i
            while (j < target.size && target[j].id in selected) j++
            val lo = target.getOrNull(i - 1)?.base?.zKey
            val hi = target.getOrNull(j)?.base?.zKey
            var prev = lo
            for (k in i until j) {
                val key = (if (prev == null && hi == null) ZKeys.after(null) else ZKeys.between(prev, hi)) ?: return renumbered(target)
                result += target[k] to key
                prev = key
            }
            i = j
        }
        return result
    }

    private fun renumbered(target: List<SceneElement>): List<Pair<SceneElement, ZKey>> =
        target.zip(ZKeys.sequence(target.size))
}

/** Locks or unlocks [ids] (Excalidraw's per-element lock). */
data class LockElements(val ids: List<ElementId>, val locked: Boolean) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        var out = doc.scene
        val changed = LinkedHashSet<ElementId>()
        for (e in out.requireAll(ids).bind()) {
            if (e.base.locked == locked) continue
            out = out.replace(e.withBase(e.base.copy(locked = locked)).touched()).bind()
            changed += e.id
        }
        finish(doc, out, changed)
    }
}

// Duplicate and paste (Story 1.3.2c)

/** Maps each old group id to a new one derived from the new ids, so a remap is data and a replay is exact. */
private fun groupRemap(sources: List<SceneElement>, ids: Map<ElementId, ElementId>): Map<GroupId, GroupId> {
    val out = LinkedHashMap<GroupId, GroupId>()
    for (e in sources) for (g in e.base.groupIds) out.getOrPut(g) { GroupId(g.value + "~" + ids.getValue(e.id).value) }
    return out
}

private fun remapped(e: SceneElement, ids: Map<ElementId, ElementId>, groups: Map<GroupId, GroupId>, layer: LayerId): SceneElement {
    fun id(old: ElementId): ElementId? = ids[old]
    fun binding(b: Binding?): Binding? = b?.let { id(it.elementId) }?.let { b.copy(elementId = it) }
    fun bound(refs: List<BoundRef>): List<BoundRef> = refs.mapNotNull { r -> id(r.id)?.let { r.copy(id = it) } }
    val base = e.base.copy(
        id = ids.getValue(e.id),
        layerId = layer,
        groupIds = e.base.groupIds.map { groups.getValue(it) },
        frameId = e.base.frameId?.let { ids[it] ?: it },
        version = 1,
    )
    return when (val moved = e.withBase(base)) {
        is RectElement -> moved.copy(boundElements = bound(moved.boundElements))
        is DiamondElement -> moved.copy(boundElements = bound(moved.boundElements))
        is EllipseElement -> moved.copy(boundElements = bound(moved.boundElements))
        is ArrowElement -> moved.copy(startBinding = binding(moved.startBinding), endBinding = binding(moved.endBinding))
        is TextElement -> moved.copy(containerId = moved.containerId?.let { id(it) })
        else -> moved
    }
}

/**
 * Pastes copies of [elements] with new ids from [idMap], moved by ([dx], [dy]), on top of their layers (or the default
 * layer when the sheet has no such layer). References between copies are remapped; a binding, container or bound entry
 * that points outside the copied set is dropped so a copy never alters an element it was not copied with.
 */
data class PasteElements(val elements: List<SceneElement>, val idMap: Map<ElementId, ElementId>, val dx: Double = 0.0, val dy: Double = 0.0) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val scene = doc.scene
        elements.firstOrNull { it.id !in idMap }?.let { raise(CommandError.MissingRemap(it.id.value)) }
        val groups = groupRemap(elements, idMap)
        val copies = elements.map { e ->
            val layer = if (scene.layer(e.base.layerId) != null) e.base.layerId else LayerId.Default
            remapped(e, idMap, groups, layer).shifted(dx, dy, scene.limits).bind()
        }
        val ordered = copies.sortedWith(compareBy({ it.base.layerId.value }, { it.base.zKey }))
        // A copy is a new element: it takes the next ordinal of its kind and drops the source's user name.
        val (named, counters) = doc.nameCounters.assign(ordered.map { it.withBase(it.base.copy(ordinal = 0, name = if (it is FrameElement) it.base.name else null)) })
        finish(doc.copy(nameCounters = counters), addOnTop(scene, named).bind(), copies.mapTo(LinkedHashSet()) { it.id })
    }
}

/** Duplicates [ids] in place with the new ids of [idMap], moved by ([dx], [dy]). */
data class DuplicateElements(val ids: List<ElementId>, val idMap: Map<ElementId, ElementId>, val dx: Double = 0.0, val dy: Double = 0.0) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val sources = doc.scene.requireAll(ids).bind()
        PasteElements(sources, idMap, dx, dy).apply(doc).bind()
    }
}
