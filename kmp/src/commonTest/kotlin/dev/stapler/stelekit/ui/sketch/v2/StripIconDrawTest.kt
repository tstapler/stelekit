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
    private fun inked(icon: StripIcon): Int {
        val bitmap = ImageBitmap(PX, PX)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(PX.toFloat(), PX.toFloat())) {
            drawStripIcon(icon, Color.Black, PX / GRID)
        }
        val pixels = IntArray(PX * PX)
        bitmap.readPixels(pixels)
        return pixels.count { (it ushr ALPHA_SHIFT) != 0 }
    }

    @Test
    fun everyIcon_should_PutInkOnTheCanvas() {
        StripIcon.entries.forEach { assertTrue(inked(it) > 0, "$it drew nothing") }
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
    }
}
