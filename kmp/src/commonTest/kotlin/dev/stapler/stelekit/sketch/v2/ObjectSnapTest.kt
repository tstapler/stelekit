// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.snap.GridSettings
import dev.stapler.stelekit.sketch.v2.snap.GuideAxis
import dev.stapler.stelekit.sketch.v2.snap.ObjectSnap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 3.5.5: object snapping with guides, grid, and the sixteenth snap of technical sheets. */
class ObjectSnapTest {
    private fun scene(): Pair<SketchDocumentV2, SpatialIndex> {
        val doc = run(SketchDocumentV2(), AddElements(listOf(rect("r1", 100, 0, 40, 40), rect("r2", 103, 200, 20, 20))))
        return doc to SpatialIndex().also { it.rebuild(doc.scene) }
    }

    private val moving = setOf(ElementId("r2"))

    @Test
    fun release_should_SnapTheLeftEdgeTo100_And_ShowOneGuide_When_Within8px() {
        val (doc, index) = scene()
        val outcome = ObjectSnap.snapMove(doc.scene, index, moving, dx = 0.0, dy = -150.0, zoom = 1.0)
        assertEquals(-3.0, outcome.dx)
        assertEquals(1, outcome.guides.size)
        assertEquals(GuideAxis.Vertical, outcome.guides.single().axis)
        assertEquals(100.0, outcome.guides.single().position)
        assertEquals(SnapKind.ENDPOINT, outcome.guides.single().kind)
        val moved = run(doc, MoveElements(moving.toList(), outcome.dx, outcome.dy))
        assertEquals(100.0, (moved.scene[ElementId("r2")] as RectElement).x.value)
    }

    @Test
    fun ctrl_should_DisableSnapping() {
        val (doc, index) = scene()
        val outcome = ObjectSnap.snapMove(doc.scene, index, moving, dx = 0.0, dy = -150.0, zoom = 1.0, disabled = true)
        assertEquals(0.0, outcome.dx)
        assertFalse(outcome.snapped)
    }

    @Test
    fun radius_should_ShrinkInSceneUnitsAsTheViewZoomsIn() {
        val (doc, index) = scene()
        // 3 units away: inside 8 px at zoom 1, outside 8 px at zoom 4 (2 units).
        assertTrue(ObjectSnap.snapMove(doc.scene, index, moving, 0.0, -150.0, zoom = 1.0).snapped)
        assertFalse(ObjectSnap.snapMove(doc.scene, index, moving, 0.0, -150.0, zoom = 4.0).snapped)
    }

    @Test
    fun centres_should_SnapAsMidpoints() {
        val doc = run(SketchDocumentV2(), AddElements(listOf(rect("r1", 100, 0, 40, 40), rect("r2", 0, 200, 20, 20))))
        val index = SpatialIndex().also { it.rebuild(doc.scene) }
        // r2 centre x is 10; r1 centre x is 120. Move so its centre lands 2 units short of 120.
        val outcome = ObjectSnap.snapMove(doc.scene, index, moving, dx = 108.0, dy = -150.0, zoom = 1.0)
        assertEquals(110.0, outcome.dx)
        assertEquals(SnapKind.MIDPOINT, outcome.guides.first { it.axis == GuideAxis.Vertical }.kind)
    }

    @Test
    fun grid_should_SnapAxesThatFoundNoElement_And_TechnicalSheetsUseSixteenths() {
        val doc = run(SketchDocumentV2(), AddElements(listOf(rect("r2", 0, 0, 20, 20))))
        val index = SpatialIndex().also { it.rebuild(doc.scene) }
        val tech = ObjectSnap.snapMove(doc.scene, index, moving, 3.4, 5.6, zoom = 1.0, grid = GridSettings.technical())
        assertEquals(3.0, tech.dx)
        assertEquals(6.0, tech.dy)
        assertTrue(tech.guides.all { it.kind == SnapKind.GRID })
        val diagram = ObjectSnap.snapMove(doc.scene, index, moving, 13.0, 27.0, zoom = 1.0, grid = GridSettings.diagram(true))
        assertEquals(20.0, diagram.dx)
        assertEquals(20.0, diagram.dy)
        assertEquals(3.4, ObjectSnap.snapMove(doc.scene, index, moving, 3.4, 5.6, zoom = 1.0, grid = GridSettings.technical(enabled = false)).dx)
    }
}
