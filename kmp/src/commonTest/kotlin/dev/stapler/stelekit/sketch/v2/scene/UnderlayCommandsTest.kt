// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.CalibrateUnderlay
import dev.stapler.stelekit.sketch.v2.commands.RemoveUnderlay
import dev.stapler.stelekit.sketch.v2.commands.SetLayerLocked
import dev.stapler.stelekit.sketch.v2.commands.SetUnderlay
import dev.stapler.stelekit.sketch.v2.commands.SetUnderlayPlacement
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epic E5: the underlay lives on its own locked bottom layer, is excluded from exports unless asked for, and saves additively. */
class UnderlayCommandsTest {
    private val file = "a".repeat(64)
    private val widthPx = 4000
    private val heightPx = 3000
    private val start = UnderlayPlacement(0.0, 0.0, 800.0, 600.0, 0.0)

    private fun doc(): SketchDocumentV2 {
        val rect = RectElement(ElementBase(ElementId("r1")), SceneUnit.ofInt(10), SceneUnit.ofInt(20), SceneUnit.ofInt(30), SceneUnit.ofInt(40))
        return AddElement(rect).apply(SketchDocumentV2(sheetUuid = "11111111-1111-4111-8111-111111111111")).getOrNull()!!.doc
    }

    private fun withUnderlay(): SketchDocumentV2 = SetUnderlay(file, widthPx, heightPx, start).apply(doc()).getOrNull()!!.doc

    @Test
    fun setUnderlay_should_AddALockedHalfOpaqueLayerBelowEveryOtherLayer() {
        val d = withUnderlay()
        val layer = assertNotNull(d.scene.layer(UnderlayIds.LAYER))
        assertTrue(layer.locked)
        assertEquals(UnderlayIds.DEFAULT_OPACITY, layer.opacity)
        assertTrue(layer.order < Layer.DefaultLayer.order, "the underlay paints first")
        val state = assertNotNull(d.scene.underlay)
        assertEquals(file, state.element.fileId)
        assertEquals(UnderlayMeta(widthPx, heightPx), state.meta)
        assertEquals(800.0, state.placement.width)
    }

    @Test
    fun setUnderlay_should_KeepTheLayersLockAndOpacity_When_ThePhotoIsReplaced() {
        val unlocked = SetLayerLocked(UnderlayIds.LAYER, false).apply(withUnderlay()).getOrNull()!!.doc
        val replaced = SetUnderlay("b".repeat(64), 1000, 500, start).apply(unlocked).getOrNull()!!.doc
        assertFalse(assertNotNull(replaced.scene.layer(UnderlayIds.LAYER)).locked)
        assertEquals("b".repeat(64), replaced.scene.underlay?.element?.fileId)
        assertEquals(1, replaced.scene.elementsInOrder.count { it.base.layerId == UnderlayIds.LAYER })
    }

    @Test
    fun calibrate_should_ScaleThePhotoSoTheReferenceIsTrue_AndRecordIt() {
        val d = withUnderlay()
        // Two points 400 scene units apart on a photo drawn 800 wide, calibrated to 1,920 units (10 ft).
        val a = start.toScene(ImagePx(1000.0, 1500.0), widthPx, heightPx)
        val b = start.toScene(ImagePx(3000.0, 1500.0), widthPx, heightPx)
        val done = CalibrateUnderlay(a.first, a.second, b.first, b.second, 1_920L).apply(d).getOrNull()!!.doc
        val state = assertNotNull(done.scene.underlay)
        val p = state.placement
        val (ax, ay) = p.toScene(state.meta.refs.single().a, widthPx, heightPx)
        val (bx, by) = p.toScene(state.meta.refs.single().b, widthPx, heightPx)
        assertEquals(1_920.0, hypot(bx - ax, by - ay), 1.0e-6)
        assertEquals(CalibrationNote.Calibrated(1_920L, perspective = false), CalibrationNote.of(state))
    }

    @Test
    fun calibrate_should_RefuseTwoPointsOnTopOfEachOther_AndKeepTheDocument() {
        val d = withUnderlay()
        assertTrue(CalibrateUnderlay(100.0, 100.0, 100.0, 100.0, 192L).apply(d).isLeft())
        assertTrue(CalibrateUnderlay(100.0, 100.0, 300.0, 100.0, 0L).apply(d).isLeft())
        assertTrue(CalibrateUnderlay(Double.NaN, 100.0, 300.0, 100.0, 192L).apply(d).isLeft())
        assertTrue(CalibrateUnderlay(0.0, 0.0, 1.0, 1.0, 192L).apply(doc()).isLeft(), "no underlay")
    }

    @Test
    fun secondReference_should_MeasurePerspective_And_ReplaceShouldForgetIt() {
        val d = withUnderlay()
        val a = start.toScene(ImagePx(500.0, 500.0), widthPx, heightPx)
        val b = start.toScene(ImagePx(1500.0, 500.0), widthPx, heightPx)
        val first = CalibrateUnderlay(a.first, a.second, b.first, b.second, 960L).apply(d).getOrNull()!!.doc
        val p = first.scene.underlay!!.placement
        val c = p.toScene(ImagePx(500.0, 2000.0), widthPx, heightPx)
        val e = p.toScene(ImagePx(500.0, 2800.0), widthPx, heightPx)
        // The second length (800 px) is 12 % longer than the first reference's scale predicts: the photo has perspective.
        val predicted = 960.0 / 1000.0 * 800.0
        val second = CalibrateUnderlay(c.first, c.second, e.first, e.second, (predicted * 1.12).toLong(), addReference = true).apply(first).getOrNull()!!.doc
        val state = second.scene.underlay!!
        assertEquals(2, state.meta.refs.size)
        assertTrue(UnderlayCalibration.hasPerspective(state.meta.refs))
        assertEquals(CalibrationNote.Calibrated(960L, perspective = true), CalibrationNote.of(state))
        val again = CalibrateUnderlay(c.first, c.second, e.first, e.second, 500L).apply(second).getOrNull()!!.doc
        assertEquals(1, again.scene.underlay!!.meta.refs.size)
    }

    @Test
    fun setPlacement_should_MoveThePhoto_WithoutTouchingItsMeta() {
        val d = withUnderlay()
        val moved = SetUnderlayPlacement(start.copy(x = 50.0, y = -20.0, angle = 0.5)).apply(d).getOrNull()!!.doc
        val state = assertNotNull(moved.scene.underlay)
        assertEquals(50.0, state.placement.x)
        assertEquals(0.5, state.placement.angle)
        assertEquals(d.scene.underlay?.meta, state.meta)
    }

    @Test
    fun remove_should_RestoreTheSheetExactly() {
        val before = doc()
        val after = RemoveUnderlay.apply(withUnderlay()).getOrNull()!!.doc
        assertEquals(before.scene, after.scene)
        assertNull(after.scene.layer(UnderlayIds.LAYER))
        assertEquals(before.scene.layers, after.scene.layers)
    }

    @Test
    fun withoutUnderlay_should_DropOnlyTheUnderlay_AndNeverTouchASheetThatHasNone() {
        val d = withUnderlay()
        val stripped = d.scene.withoutUnderlay()
        assertNull(stripped[UnderlayIds.ELEMENT])
        assertNotNull(stripped[ElementId("r1")])
        assertEquals(doc().scene.order, stripped.order)
        assertEquals(doc().scene, doc().scene.withoutUnderlay())
    }

    @Test
    fun sheetWithUnderlay_should_RoundTripThroughTheCanonicalCodec_And_AnOldSheetShouldNotMentionIt() {
        val a = start.toScene(ImagePx(1000.0, 1500.0), widthPx, heightPx)
        val b = start.toScene(ImagePx(3000.0, 1500.0), widthPx, heightPx)
        val calibrated = CalibrateUnderlay(a.first, a.second, b.first, b.second, 1_920L).apply(withUnderlay()).getOrNull()!!.doc
        val bytes = SheetCodecV2.encode(calibrated).getOrNull()!!
        val back = SheetCodecV2.decode(bytes).getOrNull()!!
        val written = assertNotNull(calibrated.scene.underlay)
        val read = assertNotNull(back.scene.underlay)
        assertEquals(written.placement, read.placement)
        assertEquals(written.meta, read.meta)
        assertEquals(written.layer, read.layer)
        assertEquals(written.element.fileId, read.element.fileId)
        assertEquals(calibrated.scene.layers, back.scene.layers)
        assertTrue(SheetCodecV2.encode(back).getOrNull()!!.contentEquals(bytes), "canonical bytes are stable")
        val old = SheetCodecV2.encode(doc()).getOrNull()!!.decodeToString()
        assertFalse("underlay" in old, "a sheet without an underlay carries no trace of one")
    }
}
