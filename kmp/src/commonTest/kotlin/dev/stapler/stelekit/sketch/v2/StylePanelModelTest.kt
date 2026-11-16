// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.W6Kit.step
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.SetLayerOpacity
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleElements
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.RevealResult
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SelectionReveal
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.ColorPalette
import dev.stapler.stelekit.ui.sketch.v2.EdgesChoice
import dev.stapler.stelekit.ui.sketch.v2.ElementStats
import dev.stapler.stelekit.ui.sketch.v2.ElementStatsEdits
import dev.stapler.stelekit.ui.sketch.v2.SloppinessChoice
import dev.stapler.stelekit.ui.sketch.v2.StatField
import dev.stapler.stelekit.ui.sketch.v2.StrokeWidthChoice
import dev.stapler.stelekit.ui.sketch.v2.StyleActions
import dev.stapler.stelekit.ui.sketch.v2.StylePanelModel
import dev.stapler.stelekit.ui.sketch.v2.StyleValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.5.4: style model, Mixed values, colour names, stats edits and the selection reveal (UX-15, UX-125). */
class StylePanelModelTest {
    private val defaults = ElementStyle()

    private fun doc() = run(SketchDocumentV2(), AddElements(listOf(rect("r1", 0, 0), rect("r2", 20, 0), circle("c", 50, 50))))

    private fun model(vararg ids: String, scene: SketchDocumentV2 = doc()) =
        StylePanelModel.of(ids.map { scene.scene[ElementId(it)]!! }, defaults, ToolId.Rectangle, scene.scene)

    @Test
    fun heading_should_NameTheSelectionOrTheToolDefaults() {
        assertEquals("Selection (2)", model("r1", "r2").heading)
        assertEquals("Defaults for Rectangle", model().heading)
        assertFalse(model().editsSelection)
    }

    @Test
    fun boldStroke_should_UpdateTheElementInOneStep_And_DefaultsWhenNothingSelected() {
        val d = step(doc(), StyleElements(listOf(ElementId("r1")), StyleActions.strokeWidth(StrokeWidthChoice.Bold)))
        assertEquals(StrokeWidthChoice.Bold.width, d.scene[ElementId("r1")]!!.base.style.strokeWidth)
        val patched = StyleActions.applyToDefaults(defaults, StyleActions.strokeWidth(StrokeWidthChoice.ExtraBold))
        assertEquals(4.0, patched.strokeWidth)
        assertEquals(ElementStyle().strokeColor, patched.strokeColor)
    }

    @Test
    fun mixedValues_should_ShowMixedAndNoChoice() {
        val styled = step(doc(), StyleElements(listOf(ElementId("r2")), StylePatch(strokeColor = "#e03131", fillStyle = FillStyle.CrossHatch)))
        val m = model("r1", "r2", scene = styled)
        assertEquals(StyleValue.Mixed, m.strokeColor)
        assertEquals(StyleValue.Mixed, m.fillStyle)
        assertEquals(StyleValue.Same(ColorPalette.stroke.first()), model("r1", scene = styled).strokeColor)
    }

    @Test
    fun everySwatch_should_HaveATextName_And_UnknownColoursAreNamedCustom() {
        for (c in ColorPalette.stroke + ColorPalette.background) assertTrue(ColorPalette.nameOf(c.css).isNotBlank() && !ColorPalette.nameOf(c.css).startsWith("#"))
        assertEquals("Red 600", ColorPalette.nameOf("#E03131"))
        assertEquals("Transparent", ColorPalette.nameOf("transparent"))
        assertEquals("Custom #123456", ColorPalette.nameOf("#123456"))
    }

    @Test
    fun edgesAndSloppiness_should_MapToTheStyleFields() {
        val rounded = step(doc(), StyleElements(listOf(ElementId("r1")), StyleActions.edges(EdgesChoice.Round)))
        assertEquals(StyleValue.Same(EdgesChoice.Round), model("r1", scene = rounded).edges)
        val sharp = step(rounded, StyleElements(listOf(ElementId("r1")), StyleActions.edges(EdgesChoice.Sharp)))
        assertNull(sharp.scene[ElementId("r1")]!!.base.style.roundness)
        assertEquals(SloppinessChoice.Clean.roughness, StyleActions.sloppiness(SloppinessChoice.Clean).roughness)
    }

    @Test
    fun effectiveOpacity_should_NameTheLayerShare() {
        val layer = LayerId("walls")
        val d = run(SketchDocumentV2(), AddLayer(layer, "Walls"), SetLayerOpacity(layer, 0.5), AddElements(listOf(rect("w", 0, 0, layer = layer))))
        val m = StylePanelModel.of(listOf(d.scene[ElementId("w")]!!), defaults, ToolId.Select, d.scene)
        assertEquals("Effective 50% (layer 50%)", m.effectiveOpacityNote())
        assertNull(model("r1").effectiveOpacityNote())
    }

    @Test
    fun strokeWidth_should_BeDisabledWithAReasonForAnImageOnlySelection() {
        val image = ImageElement(rect("x", 0, 0).base, W6Kit.u(0), W6Kit.u(0), W6Kit.u(5), W6Kit.u(5), fileId = null)
        val scene = SketchDocumentV2().scene
        assertEquals("Stroke width does not apply to images", StylePanelModel.of(listOf(image), defaults, ToolId.Select, scene).strokeWidthDisabledReason)
        assertNull(model("r1").strokeWidthDisabledReason)
    }

    @Test
    fun stats_should_EditPositionSizeAndAngle_And_RefuseNonNumbers() {
        var d = doc()
        val r1 = d.scene[ElementId("r1")]!!
        d = step(d, assertIs<ElementStatsEdits.CommitResult.Edit>(ElementStatsEdits.commit(r1, StatField.X, "40")).command)
        assertEquals(40.0, (d.scene[ElementId("r1")] as RectElement).x.value)
        d = step(d, assertIs<ElementStatsEdits.CommitResult.Edit>(ElementStatsEdits.commit(d.scene[ElementId("r1")]!!, StatField.Width, "25")).command)
        assertEquals(25.0, (d.scene[ElementId("r1")] as RectElement).width.value)
        val invalid = assertIs<ElementStatsEdits.CommitResult.Invalid>(ElementStatsEdits.commit(r1, StatField.Y, "12 3/16\""))
        assertEquals(ElementStatsEdits.NOT_A_NUMBER, invalid.message)
        assertEquals(90.0, ElementStats.of(step(d, assertIs<ElementStatsEdits.CommitResult.Edit>(ElementStatsEdits.commit(d.scene[ElementId("r1")]!!, StatField.Angle, "90")).command).scene[ElementId("r1")]!!)!!.angleDegrees, 1e-9)
    }

    @Test
    fun reveal_should_PanButNeverZoom_WhenTheSheetCoversTheSelection() {
        val view = SketchViewport.of()
        val visible = SelectionReveal.aboveBottomSheet(width = 360.0, height = 640.0, sheetHeightPx = 320.0)
        val selection = SceneBounds(100.0, 400.0, 200.0, 480.0)
        val result = SelectionReveal.reveal(view, selection, visible)
        assertTrue(result.panned)
        assertFalse(result.selectionLargerThanVisible)
        assertEquals(view.zoom, result.viewport.zoom)
        assertTrue(selection.maxY * result.viewport.zoom + result.viewport.panY <= visible.bottom)
        val restored = result.restoreOnClose(result.viewport)
        assertEquals(view, restored, "the previous view returns when the user did not move meanwhile")
        val moved = result.viewport.panBy(5.0, 0.0)
        assertEquals(moved, result.restoreOnClose(moved), "a user pan wins")
    }

    @Test
    fun reveal_should_KeepTheTopEdge_And_Say_WhenTheSelectionIsTallerThanTheVisibleArea() {
        val visible = ScreenRect(0.0, 0.0, 360.0, 300.0)
        val result = SelectionReveal.reveal(SketchViewport.of(), SceneBounds(10.0, 50.0, 100.0, 650.0), visible)
        assertTrue(result.selectionLargerThanVisible)
        assertEquals(0.0, 50.0 * result.viewport.zoom + result.viewport.panY, 1e-9)
        assertEquals("Selection is larger than the visible area", RevealResult.LARGER_THAN_VISIBLE)
        val inside = SelectionReveal.reveal(SketchViewport.of(), SceneBounds(10.0, 10.0, 50.0, 50.0), visible)
        assertFalse(inside.panned)
    }
}
