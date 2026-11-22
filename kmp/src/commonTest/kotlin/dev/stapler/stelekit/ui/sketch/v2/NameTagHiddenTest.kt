// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.tools.HandleTier
import dev.stapler.stelekit.sketch.v2.tools.SelectTool
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.elId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.sketch.v2.tools.testToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A selected shape's name chip is hidden only where compact corner handles are really drawn over it: touch, Compact tier, a shape with corners. */
class NameTagHiddenTest {
    private fun hidden(ctx: ToolContext, id: String, touch: Boolean): Boolean {
        val tool = SelectTool(listOf(elId(id)), touch)
        return NameTagsModel.hiddenByCompactHandles(ctx.scene, setOf(elId(id)), tool.handleLayout(ctx))
    }

    private val tinyRect get() = testToolContext(sceneOfElements(filledRectEl("a", 0, 0, 20, 20)))

    @Test
    fun touchAndATinyShape_should_HideTheChip() {
        val ctx = tinyRect
        assertEquals(HandleTier.Compact, SelectTool(listOf(elId("a")), true).handleLayout(ctx)?.tier)
        assertTrue(hidden(ctx, "a", touch = true))
    }

    @Test
    fun aMouseAndATinyShape_should_KeepTheChip() {
        assertFalse(hidden(tinyRect, "a", touch = false))
    }

    @Test
    fun aBigShape_should_KeepTheChip_OnTouchToo() {
        val ctx = testToolContext(sceneOfElements(filledRectEl("a", 0, 0, 400, 400)))
        assertFalse(hidden(ctx, "a", touch = true))
    }

    @Test
    fun aHorizontalLine_should_KeepItsChip_OnTouchAndMouse() {
        val line = LineElement(ElementBase(elId("l")), listOf(sp(0, 10), sp(30, 10)))
        val ctx = testToolContext(sceneOfElements(line))
        assertFalse(hidden(ctx, "l", touch = true))
        assertFalse(hidden(ctx, "l", touch = false))
    }

    @Test
    fun aSeveralShapeSelection_should_NeverHideAChip() {
        val ctx = testToolContext(sceneOfElements(filledRectEl("a", 0, 0, 20, 20), filledRectEl("b", 50, 0, 20, 20)))
        val tool = SelectTool(listOf(elId("a"), elId("b")), true)
        assertFalse(NameTagsModel.hiddenByCompactHandles(ctx.scene, setOf(elId("a"), elId("b")), tool.handleLayout(ctx)))
    }
}
