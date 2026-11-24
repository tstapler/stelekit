// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.commands.CalibrateUnderlay
import dev.stapler.stelekit.sketch.v2.commands.SetUnderlay
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ImagePx
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.sketch.v2.scene.UnderlayPlacement
import dev.stapler.stelekit.sketch.v2.scene.underlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epic E5: the calibrate tool collects two points and asks for a length; the adjust tool moves, turns and scales the photo. */
class UnderlayToolsTest {
    private val file = "a".repeat(64)
    private val widthPx = 4000
    private val heightPx = 3000
    private val placed = UnderlayPlacement(0.0, 0.0, 800.0, 600.0, 0.0)

    private fun ctx(calibrated: Boolean = false) = testToolContext(scene(calibrated))

    private fun scene(calibrated: Boolean) = SetUnderlay(file, widthPx, heightPx, placed).apply(SketchDocumentV2()).getOrNull()!!.doc.let { d ->
        if (!calibrated) d.scene else {
            val a = placed.toScene(ImagePx(1000.0, 1000.0), widthPx, heightPx)
            val b = placed.toScene(ImagePx(3000.0, 1000.0), widthPx, heightPx)
            CalibrateUnderlay(a.first, a.second, b.first, b.second, 960L).apply(d).getOrNull()!!.doc.scene
        }
    }

    @Test
    fun calibrate_should_AskForALength_OnlyOnceBothPointsAreDown() {
        val r = runScenario(UnderlayCalibrateTool(), ctx()) { down(100, 100); up(100, 100); down(300, 100); up(300, 100) }
        val ask = assertIs<CalibrationPointsCommand>(r.commands.single())
        assertEquals(100.0, ask.a.x.value)
        assertEquals(300.0, ask.b.x.value)
    }

    @Test
    fun calibrate_should_ShowTheLoupeCrosshairOnTheDraggedPoint_AndDrawBothMarks() {
        val r = runScenario(UnderlayCalibrateTool(), ctx()) { down(100, 100); move(120, 110); up(120, 110); down(300, 100); move(310, 100) }
        val preview = assertNotNull(r.previews.last())
        assertEquals(310.0, preview.cursor?.x?.value)
        assertTrue(preview.elements.size >= 3, "a line and two marks")
    }

    @Test
    fun calibrate_should_LetAPlacedPointBePickedUpAndMoved_ThenAskAgain() {
        val r = runScenario(UnderlayCalibrateTool(), ctx()) {
            down(100, 100); up(100, 100); down(300, 100); up(300, 100)
            down(102, 101); move(150, 120); up(150, 120)
        }
        assertEquals(2, r.commands.size)
        val moved = assertIs<CalibrationPointsCommand>(r.commands.last())
        assertEquals(150.0, moved.a.x.value)
        assertEquals(300.0, moved.b.x.value)
    }

    @Test
    fun calibrate_should_IgnoreAThirdPointFarFromTheOthers_AndEndOnEscape() {
        val r = runScenario(UnderlayCalibrateTool(), ctx()) { down(100, 100); up(100, 100); down(300, 100); up(300, 100); down(600, 600); up(600, 600); escape() }
        assertEquals(2, r.commands.size)
        assertEquals(EndUnderlayModeCommand, r.commands.last())
        assertEquals(ToolId.Select, r.finalTool.id)
    }

    @Test
    fun adjust_should_MoveThePhotoWithOneFinger_AsOneCommand() {
        val r = runScenario(UnderlayAdjustTool(), ctx()) { down(10, 10); move(40, 30); move(60, 50); up(60, 50) }
        val adjust = assertIs<AdjustUnderlayCommand>(r.commands.single())
        assertEquals(50.0, adjust.placement.x, 1.0e-9)
        assertEquals(40.0, adjust.placement.y, 1.0e-9)
        assertEquals(800.0, adjust.placement.width, 1.0e-9)
        val shown = assertNotNull(r.previews[1]).elements.single()
        assertEquals(UnderlayIds.ELEMENT, shown.id)
        assertIs<ImageElement>(shown)
    }

    @Test
    fun adjust_should_TurnAndScaleWithTwoFingers_WhenUncalibrated() {
        val r = runScenario(UnderlayAdjustTool(), ctx()) {
            down(0, 100, pointer = 0); down(100, 100, pointer = 1)
            move(0, 100, pointer = 0); move(200, 100, pointer = 1)
            up(0, 100, pointer = 0); up(200, 100, pointer = 1)
        }
        val adjust = assertIs<AdjustUnderlayCommand>(r.commands.single())
        assertEquals(1_600.0, adjust.placement.width, 1.0e-6)
    }

    @Test
    fun adjust_should_NotRescaleACalibratedPhoto_ButStillTurnAndMoveIt() {
        val before = assertNotNull(scene(true).underlay).placement
        val r = runScenario(UnderlayAdjustTool(), ctx(calibrated = true)) {
            down(0, 100, pointer = 0); down(100, 100, pointer = 1)
            move(0, 100, pointer = 0); move(100, 200, pointer = 1)
            up(0, 100, pointer = 0); up(100, 200, pointer = 1)
        }
        val adjust = assertIs<AdjustUnderlayCommand>(r.commands.single())
        assertEquals(before.width, adjust.placement.width, 1.0e-9)
        assertTrue(adjust.placement.angle != before.angle, "the pinch turned it")
    }

    @Test
    fun adjust_should_CommitNothing_When_TheGestureIsCancelledOrOnlyTaps() {
        val cancelled = runScenario(UnderlayAdjustTool(), ctx()) { down(10, 10); move(80, 80); cancel() }
        assertTrue(cancelled.commands.isEmpty())
        assertNull(cancelled.previews.last())
        val tap = runScenario(UnderlayAdjustTool(), ctx()) { down(10, 10); up(10, 10) }
        assertTrue(tap.commands.isEmpty())
    }

    @Test
    fun adjust_should_ContinueWithTheSecondFinger_When_TheFirstLifts() {
        val r = runScenario(UnderlayAdjustTool(), ctx()) {
            down(0, 0, pointer = 0); down(100, 0, pointer = 1)
            up(0, 0, pointer = 0)
            move(130, 20, pointer = 1); up(130, 20, pointer = 1)
        }
        val adjust = assertIs<AdjustUnderlayCommand>(r.commands.single())
        assertEquals(30.0, adjust.placement.x, 1.0e-9)
        assertEquals(20.0, adjust.placement.y, 1.0e-9)
    }

    @Test
    fun toolCommands_should_MapToUndoableCommands_OnlyForAdjust() {
        val adjust = AdjustUnderlayCommand(placed.copy(x = 5.0)).toCommand()
        assertTrue(adjust.isRight())
        assertTrue(EndUnderlayModeCommand.toCommand().isLeft(), "the host swallows this one; it is never a document edit")
    }

    @Test
    fun calibrate_should_DropThePointAFingerPinchStarted_AndStayUsable() {
        val r = runScenario(UnderlayCalibrateTool(), ctx()) {
            down(100, 100, pointer = 0); down(200, 100, pointer = 1)
            up(100, 100, pointer = 0); up(200, 100, pointer = 1)
            down(100, 100); up(100, 100); down(300, 100); up(300, 100)
        }
        val ask = assertIs<CalibrationPointsCommand>(r.commands.single())
        assertEquals(100.0, ask.a.x.value)
        assertEquals(300.0, ask.b.x.value)
    }
}
