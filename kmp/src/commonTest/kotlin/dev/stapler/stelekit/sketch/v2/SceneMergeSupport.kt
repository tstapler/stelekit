// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.ZKeys
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlin.random.Random

/** Documents and random edit streams for the E14 merge tests. Elements carry no references, so the laws are about content alone. */
internal object SceneMergeSupport {
    const val SHEET = "sheet-1"
    val EXTRA_LAYER = LayerId("L1")

    fun u(i: Int) = SceneUnit.ofInt(i)

    fun rect(id: String, x: Int = 0, y: Int = 0, w: Int = 10, h: Int = 10, z: String = "a1", ver: Int = 1) =
        RectElement(ElementBase(ElementId(id), zKey = ZKey(z), version = ver), u(x), u(y), u(w), u(h))

    fun text(id: String, body: String, z: String = "a1", ver: Int = 1) =
        TextElement(ElementBase(ElementId(id), zKey = ZKey(z), version = ver), u(0), u(0), u(10), u(10), text = body)

    /** Elements in (layer, z, id) order, the order every merged scene has. */
    fun doc(vararg elements: SceneElement, layers: List<Layer> = listOf(Layer.DefaultLayer, Layer(EXTRA_LAYER, "Second", order = 1))): SketchDocumentV2 =
        SketchDocumentV2(scene(elements.toList(), layers), SHEET)

    fun scene(elements: List<SceneElement>, layers: List<Layer>): SketchSceneV2 {
        var s = SketchSceneV2.empty().withLayers(layers)
        val order = layers.associate { it.id to it.order }
        for (e in elements.sortedWith(compareBy({ order[it.base.layerId] ?: 0 }, { it.base.zKey }, { it.id }))) s = s.add(e).getOrNull()!!
        return s
    }

    fun withElements(doc: SketchDocumentV2, elements: List<SceneElement>): SketchDocumentV2 = doc.copy(scene = scene(elements, doc.scene.layers))

    fun bytes(doc: SketchDocumentV2): String = SheetCodecV2.encode(doc).getOrNull()!!.decodeToString()

    /** [doc] with every element version zeroed: versions are cache stamps, not content, and are the one thing associativity does not preserve. */
    fun contentOnly(doc: SketchDocumentV2): SketchDocumentV2 =
        withElements(doc, doc.scene.elementsInOrder.map { it.withBase(it.base.copy(version = 0)) })

    fun ids(doc: SketchDocumentV2): Set<String> = doc.scene.elementsInOrder.map { it.id.value }.toSet()

    fun byId(doc: SketchDocumentV2, id: String): SceneElement = doc.scene[ElementId(id)]!!

    private fun touched(e: SceneElement): SceneElement = e.withBase(e.base.copy(version = e.base.version + 1))

    /** A base sheet of [count] elements of mixed kinds with distinct z keys. */
    fun baseDoc(r: Random, count: Int): SketchDocumentV2 {
        val keys = ZKeys.sequence(count)
        val elements = List(count) { i -> randomElement(r, "e$i", keys[i].value, if (i % 2 == 0) LayerId.Default else EXTRA_LAYER) }
        return doc(*elements.toTypedArray())
    }

    private fun randomElement(r: Random, id: String, z: String, layer: LayerId): SceneElement {
        val base = ElementBase(ElementId(id), layerId = layer, zKey = ZKey(z))
        return when (r.nextInt(KINDS)) {
            0 -> RectElement(base, u(r.nextInt(COORD)), u(r.nextInt(COORD)), u(r.nextInt(SIZE) + 1), u(r.nextInt(SIZE) + 1))
            1 -> EllipseElement(base, u(r.nextInt(COORD)), u(r.nextInt(COORD)), u(r.nextInt(SIZE) + 1), u(r.nextInt(SIZE) + 1))
            2 -> LineElement(base, List(r.nextInt(3) + 2) { ScenePoint(u(r.nextInt(COORD)), u(r.nextInt(COORD))) })
            3 -> TextElement(base, u(r.nextInt(COORD)), u(r.nextInt(COORD)), u(SIZE), u(SIZE), text = "t${r.nextInt(WORDS)}")
            else -> FreedrawElement(base, List(r.nextInt(3) + 2) { ScenePoint(u(r.nextInt(COORD)), u(r.nextInt(COORD))) })
        }
    }

    /** One user edit by device [device]: a field edit, an add, a delete or a layer tweak. New ids are unique per device. */
    fun randomEdit(doc: SketchDocumentV2, r: Random, device: String, serial: Int): SketchDocumentV2 {
        val elements = doc.scene.elementsInOrder
        return when (if (elements.isEmpty()) ADD else r.nextInt(EDIT_KINDS)) {
            ADD -> {
                val z = ZKeys.after(elements.lastOrNull()?.base?.zKey).value
                withElements(doc, elements + randomElement(r, "$device$serial", z, LayerId.Default))
            }
            DELETE -> withElements(doc, elements - elements[r.nextInt(elements.size)])
            LAYER -> doc.copy(scene = doc.scene.withLayers(doc.scene.layers.map { if (it.id == EXTRA_LAYER) it.copy(visible = r.nextBoolean(), name = "L${r.nextInt(WORDS)}") else it }))
            else -> {
                val target = elements[r.nextInt(elements.size)]
                withElements(doc, elements.map { if (it.id == target.id) editField(it, r) else it })
            }
        }
    }

    private fun editField(e: SceneElement, r: Random): SceneElement = touched(
        when (r.nextInt(FIELD_KINDS)) {
            0 -> e.withBase(e.base.copy(opacity = r.nextInt(PERCENT) + 1))
            1 -> e.withBase(e.base.copy(style = e.base.style.copy(strokeColor = "#" + r.nextInt(COLORS).toString().padStart(COLOR_WIDTH, '0'))))
            else -> when (e) {
                is RectElement -> if (r.nextBoolean()) e.copy(x = u(r.nextInt(COORD)), y = u(r.nextInt(COORD))) else e.copy(width = u(r.nextInt(SIZE) + 1), height = u(r.nextInt(SIZE) + 1))
                is EllipseElement -> e.copy(cx = u(r.nextInt(COORD)), cy = u(r.nextInt(COORD)))
                is TextElement -> if (r.nextBoolean()) e.copy(text = "t${r.nextInt(WORDS)}") else e.copy(x = u(r.nextInt(COORD)), y = u(r.nextInt(COORD)))
                is LineElement -> e.copy(points = e.points.map { ScenePoint(u(r.nextInt(COORD)), it.y) })
                is FreedrawElement -> e.copy(points = e.points.map { ScenePoint(it.x, u(r.nextInt(COORD))) })
                else -> e
            }
        },
    )

    private const val KINDS = 5
    private const val COORD = 400
    private const val SIZE = 60
    private const val WORDS = 5
    private const val ADD = 0
    private const val DELETE = 1
    private const val LAYER = 2
    private const val EDIT_KINDS = 7
    private const val FIELD_KINDS = 5
    private const val PERCENT = 100
    private const val COLORS = 8
    private const val COLOR_WIDTH = 6
}
