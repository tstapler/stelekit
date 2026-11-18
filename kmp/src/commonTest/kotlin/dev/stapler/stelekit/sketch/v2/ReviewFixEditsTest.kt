// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.W6Kit.u
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.ContainerText
import dev.stapler.stelekit.sketch.v2.commands.DeleteLayer
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.commands.MoveElementsToLayer
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextLayoutPolicy
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review-fix D8 and D9: layer moves carry labels, layer deletion and import terminate cleanly, a fit measures once, snapshots restore name counters. */
class ReviewFixEditsTest {
    private val layerB = Layer(LayerId("B"), "B", order = 1)

    private fun withLayerB(vararg elements: dev.stapler.stelekit.sketch.v2.scene.SceneElement): SketchDocumentV2 {
        val base = SketchDocumentV2()
        return run(base.copy(scene = base.scene.withLayers(base.scene.layers + layerB)), AddElements(elements.toList()))
    }

    @Test
    fun moveToLayer_should_CarryTheBoundLabelOfAContainer() {
        val label = TextElement(ElementBase(ElementId("t")), u(1), u(1), u(5), u(5), text = "l", containerId = ElementId("r"))
        val doc = withLayerB(rect("r", 0, 0), label)

        val after = run(doc, MoveElementsToLayer(listOf(ElementId("r")), LayerId("B")))

        assertEquals(LayerId("B"), after.scene[ElementId("r")]!!.base.layerId)
        assertEquals(LayerId("B"), after.scene[ElementId("t")]!!.base.layerId, "the label stays with its container")
    }

    @Test
    fun deleteLayer_should_RefuseToMoveItsElementsIntoItself() {
        val doc = withLayerB(rect("r", 0, 0))

        val refused = DeleteLayer(LayerId("B"), into = LayerId("B")).apply(doc).fold({ it }, { error("applied") })

        assertIs<CommandError.Unsupported>(refused)
    }

    @Test
    fun decode_should_MoveAnElementOnAnUnknownLayerToTheDefaultLayer() {
        val doc = withLayerB(rect("r", 0, 0, layer = LayerId("B")))
        val bytes = SheetCodecV2.encode(doc).get().decodeToString().replace(",{\"id\":\"B\"", ",{\"id\":\"gone\"")

        val back = SheetCodecV2.decode(bytes.encodeToByteArray()).get()

        assertTrue(bytes.contains("\"gone\""), "the fixture dropped layer B from the sheet")
        assertEquals(LayerId.Default, back.scene[ElementId("r")]!!.base.layerId)
    }

    @Test
    fun importScene_should_Terminate_When_TheImportedNameIsItsOwnSuffixedForm() {
        val max = SceneLimits.Default.maxNameChars
        val collided = "a".repeat(max - " (imported)".length) + " (imported)"
        val base = SketchDocumentV2()
        val doc = base.copy(scene = base.scene.withLayers(base.scene.layers + Layer(LayerId("X"), collided, order = 1)))

        val after = ImportScene(emptyList(), listOf(Layer(LayerId("Y"), collided))).apply(doc).get().doc

        val names = after.scene.layers.map { it.name }
        assertEquals(names.size, names.toSet().size, "every layer name is unique: $names")
        assertTrue(names.all { it.length <= max })
    }

    @Test
    fun containerFit_should_MeasureOnce_BecauseTheWrapWidthNeverChanges() {
        val inner = FixedAdvanceTextMetricsProvider(advancePerCodePoint = 8.0, lineHeightUnits = 20.0)
        var advances = 0
        val counting = object : TextMetricsProvider by inner {
            override fun advance(text: String, style: TextStyle): Double = inner.advance(text, style).also { advances++ }
        }
        val style = TextStyle(1, 20.0)
        val text = "Authentication Service"
        TextLayoutPolicy.forEdit(text, 80.0 - ContainerText.PADDING * 2, style, counting)
        val onePass = advances
        advances = 0

        ContainerText.fit(rect("r", 0, 0, 80, 40), text, style, counting).get()

        assertEquals(onePass, advances, "one layout pass, not ${advances / onePass}")
    }

    @Test
    fun history_should_RestoreNameCountersFromTheSnapshots_Because_DocPatchDoesNotCarryThem() {
        val history = W6Kit.history(AddElements(listOf(rect("a", 0, 0))))
        val added = history.present

        assertTrue(added.nameCounters != history.undo().present.nameCounters, "adding a named element advanced the counters")
        assertEquals(added.nameCounters, history.undo().redo().present.nameCounters)
    }
}
