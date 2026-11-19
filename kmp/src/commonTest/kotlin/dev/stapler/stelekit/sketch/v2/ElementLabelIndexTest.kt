// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.search.ElementLabelIndex
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGoToStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 3.6.5, Task 3.6.5a: the pure search index behind "Go to element...". */
class ElementLabelIndexTest {
    private fun u(d: Double) = SheetFixtures.u(d)

    private fun rect(id: String, ordinal: Int, x: Double = 0.0, layer: LayerId = LayerId.Default, labelId: String? = null) =
        RectElement(
            SheetFixtures.base(id, ordinal, layer), u(x), u(0.0), u(40.0), u(20.0),
            boundElements = labelId?.let { listOf(BoundRef(ElementId(it), BoundKind.Text)) }.orEmpty(),
        )

    private fun label(id: String, ordinal: Int, text: String, container: String?) =
        TextElement(SheetFixtures.base(id, ordinal), u(0.0), u(0.0), u(30.0), u(10.0), text = text, containerId = container?.let(::ElementId))

    private fun scene(elements: List<SceneElement>, layers: List<Layer>? = null): SketchSceneV2 {
        var s = SketchSceneV2.empty()
        layers?.let { s = s.withLayers(it) }
        for (e in elements) s = s.add(e).fold({ error("fixture $it") }, { it })
        return s
    }

    private fun manyWithBilling(): SketchSceneV2 {
        val billing = rect("e2999", 3000, x = 90_000.0, labelId = "e2999-label")
        val filler = (0 until 2998).map { rect("e$it", it + 1, x = it * 50.0) }
        return scene(filler + billing + label("e2999-label", 3001, "Billing API", container = "e2999"))
    }

    @Test
    fun search_should_FindAnElementByItsContainerText_When_3000Elements() {
        val index = ElementLabelIndex.of(manyWithBilling())
        val found = index.search("billing")
        assertEquals(listOf(ElementId("e2999")), found.shown.map { it.id })
        assertEquals("Rectangle", found.shown.single().kind)
        assertEquals("Billing API", found.shown.single().text)
        assertEquals(0, found.more)
    }

    @Test
    fun search_should_ShowAtMostTheLimit_AndCountTheRest_When_ManyMatch() {
        val index = ElementLabelIndex.of(manyWithBilling())
        val limit = SketchSceneV2.empty().limits.maxGoToResults
        val found = index.search("rectangle")
        assertEquals(limit, found.shown.size)
        assertEquals(2999 - limit, found.more)
        assertEquals("$limit shown, ${2999 - limit} more", SketchGoToStrings.resultCount(found.shown.size, found.more))
        assertEquals("2949 more, refine the search", SketchGoToStrings.more(found.more))
    }

    @Test
    fun search_should_MatchEveryWordInAnyOrderAndCase() {
        val index = ElementLabelIndex.of(manyWithBilling())
        assertEquals(1, index.search("API  BILLING").shown.size)
        assertTrue(index.search("billing zzz").shown.isEmpty())
    }

    @Test
    fun search_should_RankPrefixMatchesBeforeInnerMatches() {
        val s = scene(
            listOf(rect("a", 1, labelId = "al"), label("al", 2, "Edit billing", "a"), rect("b", 3, x = 50.0, labelId = "bl"), label("bl", 4, "Billing core", "b")),
        )
        assertEquals(listOf("b", "a"), ElementLabelIndex.of(s).search("billing").shown.map { it.id.value })
    }

    @Test
    fun search_should_NotListABoundLabelAsASecondResult() {
        val s = scene(listOf(rect("a", 1, labelId = "al"), label("al", 2, "Cache", "a"), label("free", 3, "Cache note", null)))
        val ids = ElementLabelIndex.of(s).search("cache").shown.map { it.id.value }
        assertEquals(listOf("a", "free"), ids)
        assertFalse("al" in ids)
    }

    @Test
    fun search_should_FlagLockedAndHiddenLayerElementsRatherThanDropThem() {
        val hidden = LayerId("h")
        val s = scene(
            listOf(rect("a", 1), rect("b", 2, x = 50.0, layer = hidden)),
            layers = listOf(Layer(LayerId.Default, "Default"), Layer(hidden, "Walls", visible = false)),
        )
        val shown = ElementLabelIndex.of(s).search("").shown
        assertEquals(listOf(false, true), shown.map { it.layerHidden })
    }

    @Test
    fun search_should_ListTheFirstElementsInPaintOrder_When_TheQueryIsBlank() {
        val s = scene(listOf(rect("a", 1), rect("b", 2, x = 50.0)))
        assertEquals(listOf("a", "b"), ElementLabelIndex.of(s).search("   ").shown.map { it.id.value })
    }
}
