// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.MoveLayer
import dev.stapler.stelekit.sketch.v2.commands.SetUnderlay
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.render.BitmapSourceRect
import dev.stapler.stelekit.sketch.v2.render.CropSourceRect
import dev.stapler.stelekit.ui.sketch.v2.LayersPanelModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E5 leftovers: the underlay layer stays the bottom layer, and a crop maps onto the downsampled bitmap that is actually held. */
class UnderlayPinAndCropTest {
    private val file = "a".repeat(64)
    private val sheet = "11111111-1111-4111-8111-111111111111"

    private fun docWithLayers(): SketchDocumentV2 {
        var d = SketchDocumentV2(sheetUuid = sheet)
        d = SetUnderlay(file, 4000, 3000, UnderlayPlacement(0.0, 0.0, 800.0, 600.0, 0.0)).apply(d).getOrNull()!!.doc
        d = AddLayer(LayerId("walls"), "Walls").apply(d).getOrNull()!!.doc
        return AddLayer(LayerId("notes"), "Notes").apply(d).getOrNull()!!.doc
    }

    private fun bottomToTop(d: SketchDocumentV2): List<LayerId> = d.scene.layers.sortedBy { it.order }.map { it.id }

    @Test
    fun moveLayer_should_KeepUnderlayAtBottom_When_AnotherLayerIsMovedToIndexZero() {
        val d = MoveLayer(LayerId("notes"), 0).apply(docWithLayers()).getOrNull()!!.doc
        assertEquals(UnderlayIds.LAYER, bottomToTop(d).first())
    }

    @Test
    fun moveLayer_should_KeepUnderlayAtBottom_When_TheUnderlayIsMovedUp() {
        val d = MoveLayer(UnderlayIds.LAYER, 2).apply(docWithLayers()).getOrNull()!!.doc
        assertEquals(UnderlayIds.LAYER, bottomToTop(d).first())
    }

    @Test
    fun layersPanel_should_OfferNoMoveThatBreaksThePin() {
        val scene = docWithLayers().scene
        assertNull(LayersPanelModel.moveUp(scene, UnderlayIds.LAYER))
        assertNull(LayersPanelModel.moveDown(scene, LayerId.Default))
        val rows = LayersPanelModel.rows(scene, LayerId.Default).associateBy { it.layer.id }
        assertTrue(!rows.getValue(UnderlayIds.LAYER).canMoveUp && !rows.getValue(UnderlayIds.LAYER).canMoveDown)
        assertTrue(!rows.getValue(LayerId.Default).canMoveDown)
        assertTrue(rows.getValue(LayerId("notes")).canMoveDown)
    }

    @Test
    fun cropSourceRect_should_ScaleTheCropToTheDownsampledBitmap() {
        val crop = ImageCrop(1000.0, 500.0, 2000.0, 1000.0, 4000.0, 3000.0)
        assertEquals(BitmapSourceRect(500, 256, 1000, 512), CropSourceRect.map(crop, 2000, 1536))
    }

    @Test
    fun cropSourceRect_should_BeTheCropItself_When_TheBitmapIsFullSize() {
        val crop = ImageCrop(10.0, 20.0, 30.0, 40.0, 100.0, 100.0)
        assertEquals(BitmapSourceRect(10, 20, 30, 40), CropSourceRect.map(crop, 100, 100))
        assertEquals(BitmapSourceRect(0, 0, 100, 100), CropSourceRect.map(null, 100, 100))
    }

    @Test
    fun cropSourceRect_should_StayInsideTheBitmap_AtEveryDownsample() {
        for (bw in listOf(1, 7, 640, 2048)) {
            val r = CropSourceRect.map(ImageCrop(3900.0, 2900.0, 500.0, 500.0, 4000.0, 3000.0), bw, bw)
            assertTrue(r.x >= 0 && r.y >= 0 && r.width >= 1 && r.height >= 1 && r.x + r.width <= bw && r.y + r.height <= bw, "$bw -> $r")
        }
    }
}
