// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.library.ElementLibrary
import dev.stapler.stelekit.sketch.v2.library.LibraryItem
import dev.stapler.stelekit.sketch.v2.library.StarterLibrary
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The built-in floor-plan symbols: complete, at true size, and inserted like any library item. */
class StarterLibraryTest {
    private fun item(slug: String): LibraryItem = StarterLibrary.items.single { it.id == "${StarterLibrary.ID_PREFIX}$slug" }

    private fun size(item: LibraryItem): Pair<Double, Double> {
        val boxes = item.elements.map { ElementBounds.geometry(it) }
        return (boxes.maxOf { it.maxX } - boxes.minOf { it.minX }) to (boxes.maxOf { it.maxY } - boxes.minOf { it.minY })
    }

    private fun idSource(): ElementIdSource {
        var n = 0
        return ElementIdSource { ElementId("n${n++}") }
    }

    @Test
    fun starter_should_CoverTheRequestedSymbols_WithUniqueIdsAndNames() {
        val slugs = StarterLibrary.items.map { it.id.removePrefix(StarterLibrary.ID_PREFIX) }
        for (needed in listOf("door", "window", "stairs", "toilet", "sink", "range", "refrigerator", "bed", "table", "chair", "north", "callout")) {
            assertTrue(needed in slugs, "missing $needed")
        }
        assertEquals(slugs.size, slugs.toSet().size)
        assertTrue(StarterLibrary.items.all { !it.name.isNullOrBlank() && it.elements.isNotEmpty() })
        val everyElementId = StarterLibrary.items.flatMap { it.elements }.map { it.id.value }
        assertEquals(everyElementId.size, everyElementId.toSet().size, "element ids never collide between symbols")
    }

    @Test
    fun symbols_should_BeTrueSize_InSixteenthsOfAnInch() {
        fun inches(units: Double) = units / 16.0
        val threshold = item("door").elements.filterIsInstance<LineElement>().first().points
        assertEquals(36.0, inches(hypot(threshold[1].x.value - threshold[0].x.value, threshold[1].y.value - threshold[0].y.value)), 0.01)
        assertEquals(36.0 to 4.5, size(item("window")).let { inches(it.first) to inches(it.second) })
        assertEquals(36.0 to 100.0, size(item("stairs")).let { inches(it.first) to inches(it.second) })
        assertEquals(20.0, inches(size(item("sink")).first), 0.01)
        assertEquals(60.0 to 80.0, size(item("bed")).let { inches(it.first) to inches(it.second) })
        assertEquals(60.0 to 36.0, size(item("table")).let { inches(it.first) to inches(it.second) })
        assertEquals(18.0 to 18.0, size(item("chair")).let { inches(it.first) to inches(it.second) })
        assertEquals(30.0 to 26.0, size(item("range")).let { inches(it.first) to inches(it.second) })
    }

    @Test
    fun library_should_MeasureEveryItem_And_KeepIdsSoInsertFindsThem() {
        val library = StarterLibrary.library
        assertEquals(StarterLibrary.items.map { it.id }, library.items.map { it.id })
        assertTrue(library.items.all { it.bytes > 0L })
        assertTrue(library.totalBytes < 64 * 1024, "the starter set stays small")
    }

    @Test
    fun insert_should_GiveFreshIds_EachTime_And_LandAtTheOffset() {
        val ids = idSource()
        val library = StarterLibrary.library
        val one = assertNotNull(library.insert("${StarterLibrary.ID_PREFIX}bed", ids, 100.0, 50.0))
        val two = assertNotNull(library.insert("${StarterLibrary.ID_PREFIX}bed", ids, 100.0, 50.0))
        val doc = listOf(one, two).fold(SketchDocumentV2()) { d, c -> (c.apply(d).getOrNull() ?: error("insert failed")).doc }
        val bed = item("bed")
        assertEquals(bed.elements.size * 2, doc.scene.size)
        assertTrue(reconcile(doc.scene).isClean)
        val first = doc.scene.elementsInOrder.take(bed.elements.size).map { ElementBounds.geometry(it) }
        assertEquals(100.0, first.minOf { it.minX }, 1e-9)
        assertEquals(50.0, first.minOf { it.minY }, 1e-9)
    }

    @Test
    fun starter_should_NotUseTheUsersLibraryQuota() {
        val user = ElementLibrary.empty()
        assertTrue(user.items.isEmpty())
        assertTrue(StarterLibrary.isStarter(StarterLibrary.items.first().id))
        assertTrue(!StarterLibrary.isStarter("item-1"))
    }

    @Test
    fun everySymbol_should_SurviveTheSheetCodec() {
        for (item in StarterLibrary.items) {
            val scene = item.elements.fold(dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull() ?: error("add ${e.id}") }
            val doc = SketchDocumentV2(scene, "starter-${item.id.removePrefix(StarterLibrary.ID_PREFIX)}")
            val back = dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2.decode(SheetFixtures.bytes(doc)).getOrNull()
            assertEquals(item.elements, back?.scene?.elementsInOrder, item.id)
        }
    }
}
