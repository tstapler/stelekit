// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

enum class BindingEnd { Start, End }

/** Why [reconcile] changed an element. Carries ids only, never user text. */
sealed interface ReconcileReason {
    val elementId: ElementId

    /** An arrow endpoint pointed at an element that does not exist; the binding was cleared. */
    data class DanglingBinding(override val elementId: ElementId, val end: BindingEnd, val target: ElementId) : ReconcileReason

    /** An arrow endpoint pointed at itself or at another arrow; the binding was cleared. */
    data class InvalidBindingTarget(override val elementId: ElementId, val end: BindingEnd, val target: ElementId) : ReconcileReason

    /** A container listed an element that does not exist; the entry was removed. */
    data class DanglingBoundElement(override val elementId: ElementId, val ref: ElementId) : ReconcileReason

    data class DuplicateBoundElement(override val elementId: ElementId, val ref: ElementId) : ReconcileReason

    /** An arrow or label points at the container but the container did not list it; the entry was added. */
    data class MissingReciprocalRef(override val elementId: ElementId, val ref: ElementId) : ReconcileReason

    /** A container entry named the wrong kind (arrow versus text); the kind was corrected. */
    data class BoundKindFixed(override val elementId: ElementId, val ref: ElementId) : ReconcileReason

    /** A label's `containerId` names a missing element; it was cleared. */
    data class DanglingContainer(override val elementId: ElementId, val container: ElementId) : ReconcileReason

    /** A label's `containerId` names itself or an element kind that cannot hold a label; it was cleared. */
    data class InvalidContainer(override val elementId: ElementId, val container: ElementId) : ReconcileReason

    /** `frameId` names the element itself; it was cleared. */
    data class CyclicFrame(override val elementId: ElementId) : ReconcileReason

    data class DanglingFrame(override val elementId: ElementId, val frame: ElementId) : ReconcileReason

    data class NotAFrame(override val elementId: ElementId, val frame: ElementId) : ReconcileReason

    /** A frame was itself inside another frame; frames do not nest, so the link was cleared. */
    data class FrameInFrame(override val elementId: ElementId, val frame: ElementId) : ReconcileReason

    data class DuplicateGroupId(override val elementId: ElementId, val group: GroupId) : ReconcileReason

    /** A group that only one element belongs to is no group; the id was removed from that element. */
    data class SingletonGroup(override val elementId: ElementId, val group: GroupId) : ReconcileReason
}

/** [scene] satisfies every invariant; [reasons] says what was repaired and [changed] which elements were touched. */
data class ReconcileResult(val scene: SketchSceneV2, val reasons: List<ReconcileReason>, val changed: Set<ElementId>) {
    val isClean: Boolean get() = reasons.isEmpty()
}

/**
 * The one invariant pass run after every command and import (Story 1.2.3). It repairs, never rejects:
 * - an arrow binding must name an existing element other than itself or an arrow, else it is cleared;
 * - a container's `boundElements` loses entries for missing elements and duplicates, gains an entry for every arrow
 *   bound to it and every label whose `containerId` names it, and carries the right kind. An entry for an element that
 *   exists but no longer points back is kept: Excalidraw files in the wild contain them and the next rebind drops them;
 * - a label's `containerId` must name an existing rectangle, diamond, ellipse or arrow;
 * - `frameId` must name an existing frame other than the element itself, and frames do not nest;
 * - `groupIds` has no duplicates and no group of one.
 * A changed element's `version` is bumped once. The pass is idempotent: running it on its own output reports nothing.
 */
fun reconcile(scene: SketchSceneV2): ReconcileResult {
    val original = scene.elementsInOrder
    val byId = LinkedHashMap<ElementId, SceneElement>().also { m -> original.forEach { m[it.id] = it } }
    val reasons = mutableListOf<ReconcileReason>()

    for (id in scene.order) byId[id] = repairOwnReferences(byId.getValue(id), byId, reasons)
    pruneSingletonGroups(byId, reasons)
    for (id in scene.order) byId[id] = repairBoundElements(byId.getValue(id), byId, reasons)

    var result = scene
    val changed = LinkedHashSet<ElementId>()
    for (before in original) {
        val after = byId.getValue(before.id)
        if (after == before) continue
        changed += before.id
        val bumped = after.withBase(after.base.copy(version = after.base.version + 1))
        result = result.replace(bumped).fold({ result }, { it })
    }
    return ReconcileResult(result, reasons, changed)
}

/** The repaired scene. */
fun SketchSceneV2.reconciled(): SketchSceneV2 = reconcile(this).scene

private fun repairOwnReferences(e: SceneElement, byId: Map<ElementId, SceneElement>, reasons: MutableList<ReconcileReason>): SceneElement {
    var out = e
    val base = e.base
    val deduped = base.groupIds.distinct()
    if (deduped.size != base.groupIds.size) {
        base.groupIds.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { reasons += ReconcileReason.DuplicateGroupId(e.id, it) }
    }
    val frameId = base.frameId?.let { repairFrame(e, it, byId, reasons) }
    if (deduped != base.groupIds || frameId != base.frameId) out = out.withBase(base.copy(groupIds = deduped, frameId = frameId))

    return when (out) {
        is ArrowElement -> out.copy(
            startBinding = out.startBinding?.takeIf { validTarget(out, BindingEnd.Start, it, byId, reasons) },
            endBinding = out.endBinding?.takeIf { validTarget(out, BindingEnd.End, it, byId, reasons) },
        )
        is TextElement -> out.copy(containerId = out.containerId?.takeIf { validContainer(out, it, byId, reasons) })
        else -> out
    }
}

private fun repairFrame(e: SceneElement, frame: ElementId, byId: Map<ElementId, SceneElement>, reasons: MutableList<ReconcileReason>): ElementId? {
    val target = byId[frame]
    val reason = when {
        frame == e.id -> ReconcileReason.CyclicFrame(e.id)
        target == null -> ReconcileReason.DanglingFrame(e.id, frame)
        target !is FrameElement -> ReconcileReason.NotAFrame(e.id, frame)
        e is FrameElement -> ReconcileReason.FrameInFrame(e.id, frame)
        else -> return frame
    }
    reasons += reason
    return null
}

private fun validTarget(arrow: ArrowElement, end: BindingEnd, binding: Binding, byId: Map<ElementId, SceneElement>, reasons: MutableList<ReconcileReason>): Boolean {
    val target = byId[binding.elementId]
    return when {
        target == null -> false.also { reasons += ReconcileReason.DanglingBinding(arrow.id, end, binding.elementId) }
        target.id == arrow.id || target is ArrowElement -> false.also { reasons += ReconcileReason.InvalidBindingTarget(arrow.id, end, binding.elementId) }
        else -> true
    }
}

private fun validContainer(text: TextElement, container: ElementId, byId: Map<ElementId, SceneElement>, reasons: MutableList<ReconcileReason>): Boolean {
    val target = byId[container]
    return when {
        target == null -> false.also { reasons += ReconcileReason.DanglingContainer(text.id, container) }
        target is RectElement || target is DiamondElement || target is EllipseElement || target is ArrowElement -> true
        else -> false.also { reasons += ReconcileReason.InvalidContainer(text.id, container) }
    }
}

private fun pruneSingletonGroups(byId: MutableMap<ElementId, SceneElement>, reasons: MutableList<ReconcileReason>) {
    val members = HashMap<GroupId, Int>()
    for (e in byId.values) e.base.groupIds.forEach { members[it] = (members[it] ?: 0) + 1 }
    val singletons = members.filterValues { it == 1 }.keys
    if (singletons.isEmpty()) return
    for ((id, e) in byId.entries.toList()) {
        val lonely = e.base.groupIds.filter { it in singletons }
        if (lonely.isEmpty()) continue
        lonely.forEach { reasons += ReconcileReason.SingletonGroup(id, it) }
        byId[id] = e.withBase(e.base.copy(groupIds = e.base.groupIds.filterNot { it in singletons }))
    }
}

private fun repairBoundElements(e: SceneElement, byId: Map<ElementId, SceneElement>, reasons: MutableList<ReconcileReason>): SceneElement {
    val current = when (e) {
        is RectElement -> e.boundElements
        is DiamondElement -> e.boundElements
        is EllipseElement -> e.boundElements
        else -> return e
    }
    val expected = LinkedHashMap<ElementId, BoundKind>()
    for (other in byId.values) {
        when {
            other is ArrowElement && (other.startBinding?.elementId == e.id || other.endBinding?.elementId == e.id) -> expected[other.id] = BoundKind.Arrow
            other is TextElement && other.containerId == e.id -> expected[other.id] = BoundKind.Text
        }
    }
    val kept = mutableListOf<BoundRef>()
    val seen = HashSet<ElementId>()
    for (ref in current) {
        val target = byId[ref.id]
        when {
            target == null -> reasons += ReconcileReason.DanglingBoundElement(e.id, ref.id)
            !seen.add(ref.id) -> reasons += ReconcileReason.DuplicateBoundElement(e.id, ref.id)
            else -> {
                val kind = actualKind(target) ?: ref.kind
                if (kind != ref.kind) reasons += ReconcileReason.BoundKindFixed(e.id, ref.id)
                kept += BoundRef(ref.id, kind)
            }
        }
    }
    for ((id, kind) in expected) if (seen.add(id)) {
        reasons += ReconcileReason.MissingReciprocalRef(e.id, id)
        kept += BoundRef(id, kind)
    }
    if (kept == current) return e
    return when (e) {
        is RectElement -> e.copy(boundElements = kept)
        is DiamondElement -> e.copy(boundElements = kept)
        is EllipseElement -> e.copy(boundElements = kept)
        else -> e
    }
}

private fun actualKind(e: SceneElement): BoundKind? = when (e) {
    is ArrowElement -> BoundKind.Arrow
    is TextElement -> BoundKind.Text
    else -> null
}
