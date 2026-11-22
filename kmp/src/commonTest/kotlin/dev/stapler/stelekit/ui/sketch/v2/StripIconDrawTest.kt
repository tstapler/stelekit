// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StripIconDrawTest {
    private fun pixelsOf(icon: StripIcon): IntArray {
        val bitmap = ImageBitmap(PX, PX)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(PX.toFloat(), PX.toFloat())) {
            drawStripIcon(icon, Color.Black, PX / GRID)
        }
        val pixels = IntArray(PX * PX)
        bitmap.readPixels(pixels)
        return pixels
    }

    private fun inked(icon: StripIcon): Int = pixelsOf(icon).count { (it ushr ALPHA_SHIFT) != 0 }

    @Test
    fun everyIcon_should_PutInkOnTheCanvas() {
        StripIcon.entries.forEach { assertTrue(inked(it) > 0, "$it drew nothing") }
    }

    @Test
    fun everyIcon_should_LookDifferentFromEveryOther() {
        val drawn = StripIcon.entries.associateWith { pixelsOf(it) }
        for ((icon, pixels) in drawn) {
            val twin = drawn.entries.firstOrNull { it.key != icon && it.value.contentEquals(pixels) }?.key
            assertTrue(twin == null, "$icon draws exactly the same pixels as $twin (${inked(icon)} inked)")
        }
    }

    @Test
    fun everyIcon_should_StayInsideItsCanvas_SoNothingIsClippedAtTheEdge() {
        for (icon in StripIcon.entries) {
            val px = pixelsOf(icon)
            val edge = (0 until PX).any { i -> listOf(i, (PX - 1) * PX + i, i * PX, i * PX + PX - 1).any { (px[it] ushr ALPHA_SHIFT) > EDGE_ALPHA } }
            assertTrue(!edge, "$icon touches the edge of its 24 unit grid")
        }
    }

    @Test
    fun everyTool_should_HaveItsOwnIcon() {
        val tools = ToolId.entries
        assertEquals(tools.size, tools.map { StripIcon.of(it) }.toSet().size, "two tools share an icon")
    }

    private companion object {
        const val PX = 48
        const val GRID = 24f
        const val ALPHA_SHIFT = 24
        const val EDGE_ALPHA = 40
    }
}
