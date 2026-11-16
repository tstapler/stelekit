// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.settings.FingerDrawingSettings
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.1.2 (UX-80, UX-102; Tasks 3.1.2d and 3.1.2e): the Finger drawing default, the StylusSeen flip, the hints and the Select-tool pan. */
class FingerDrawingDefaultsTest {
    private val strings = ChromeBannerStrings(ChromeTableParser.parseFixture())

    private class Rig(strings: ChromeBannerStrings) {
        val raw = InMemorySettings()
        val store = FingerDrawingSettings(raw)
        val port = InMemoryDiagnosticsPort(strings)
        val sequencer = OnboardingSequencer(OnboardingCounters(raw))
        val changes = mutableListOf<Boolean>()
        val controller = FingerDrawingController(store, port, sequencer, strings) { changes += it }
    }

    private fun rig() = Rig(strings)

    @Test
    fun ux80_fingerDrawing_should_DefaultOnUntilStylusSeenThenOff_NeverOverrideChoice_AndHintOnce() {
        val a = rig()
        assertTrue(a.controller.on, "a finger-only phone starts with Finger drawing ON")
        assertFalse(a.store.stylusSeen)

        a.controller.onStylusStrokeEnd()
        assertTrue(a.store.stylusSeen)
        assertFalse(a.controller.on, "after a stylus was seen the default flips to OFF")

        val b = rig()
        b.controller.choose(true)
        b.controller.onStylusStrokeEnd()
        assertTrue(b.controller.on, "an explicit ON is never overridden by StylusSeen")
        assertTrue(b.port.diagnostics.value.entries.isEmpty(), "an explicit choice raises no notice")

        val c = rig()
        c.controller.choose(false)
        assertFalse(c.controller.on)
        c.controller.onStylusStrokeEnd()
        assertFalse(c.controller.on)
        assertTrue(c.port.diagnostics.value.entries.isEmpty(), "an explicit OFF is not a flip either")
    }

    @Test
    fun hint_should_ShowTheOnHintFirst_TheOffHintAfterAFlip_AndRetireOnTheSettingChoice() {
        val a = rig()
        a.controller.onOneFingerDrag()
        assertEquals(OnboardingItem.FingerHintOn, a.sequencer.visible)
        assertEquals("Finger draws. 2 fingers pan.", a.sequencer.visibleCandidate()?.summary)
        assertEquals(FingerDrawingController.ON_DETAILS, a.sequencer.visibleCandidate()?.details)

        a.controller.choose(false)
        assertNull(a.sequencer.visible, "choosing the setting closes the hint")
        a.controller.choose(true)
        a.controller.onOneFingerDrag()
        assertNull(a.sequencer.visible, "an ON hint retired by the choice never returns")

        val b = rig()
        b.controller.onStylusStrokeEnd()
        b.controller.onOneFingerDrag()
        assertEquals(OnboardingItem.FingerHintOff, b.sequencer.visible)
        assertEquals("Finger drags move the view.", b.sequencer.visibleCandidate()?.summary)
    }

    @Test
    fun offHint_should_ShowOnceMoreAfterAnImplicitDismissal_ThenNeverAgain() {
        val a = rig()
        a.controller.onStylusStrokeEnd()
        repeat(THREE_TRIGGERS) {
            a.controller.onOneFingerDrag()
            a.controller.gotIt(OnboardingItem.FingerHintOff)
        }
        // Got it is an action button: retired after the first showing.
        assertNull(a.sequencer.visible)
        assertEquals(1, OnboardingCounters(a.raw).read(OnboardingItem.FingerHintOff).shown)

        val b = rig()
        b.controller.onStylusStrokeEnd()
        repeat(THREE_TRIGGERS) {
            b.controller.onOneFingerDrag()
            b.sequencer.dismiss(OnboardingItem.FingerHintOff, Dismissal.NavigateAway)
        }
        assertEquals(2, OnboardingCounters(b.raw).read(OnboardingItem.FingerHintOff).shown, "shown twice, never a third time")
    }

    @Test
    fun flip_should_ChangeTheStateAndRaiseTheNoticeOnly() {
        val a = rig()
        a.controller.onStylusStrokeEnd()
        assertEquals(listOf(false), a.changes)
        val notice = a.port.diagnostics.value.entries.single()
        assertEquals(BannerRowId.PenDetected, notice.row)
        assertEquals("Pen found. Finger off.", notice.summary)
        assertEquals("Back on", notice.primaryLabel)
        assertEquals(FingerDrawingController.PEN_DETECTED_DETAILS, notice.details)

        a.controller.onStylusStrokeEnd()
        assertEquals(1, a.port.diagnostics.value.entries.size, "once per device")
        assertEquals(listOf(false), a.changes)

        a.controller.backOn()
        assertTrue(a.controller.on)
        assertTrue(a.port.diagnostics.value.entries.isEmpty(), "Back on settles the notice")
        a.controller.onStylusStrokeEnd()
        assertTrue(a.controller.on, "the explicit Back on is never flipped again")
    }

    @Test
    fun adapter_should_FlipOnlyAtPenUp_NeverMidStroke() {
        val a = rig()
        val adapter = SketchPointerAdapter(fingerDrawing = a.controller.on)
        adapter.listener = a.controller
        fun stylus(phase: RawPhase) = adapter.onPointer(RawPointer(1, PointerKind.Stylus, phase, ScreenPx(10.0, 10.0), pressure = 0.5))
        stylus(RawPhase.Down)
        stylus(RawPhase.Move)
        assertTrue(a.controller.on, "mid-gesture nothing flips")
        stylus(RawPhase.Up)
        assertFalse(a.controller.on)
        assertEquals(1, a.port.diagnostics.value.entries.size)
    }

    @Test
    fun oneFingerDrag_should_RaiseTheHintOncePerDrag_OnlyForADrawingTool() {
        var drags = 0
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        adapter.listener = object : PointerGestureListener {
            override fun onOneFingerDrag() {
                drags += 1
            }
        }
        fun finger(phase: RawPhase, x: Double) = adapter.onPointer(RawPointer(1, PointerKind.Touch, phase, ScreenPx(x, 5.0)))
        finger(RawPhase.Down, 0.0)
        finger(RawPhase.Move, 5.0)
        finger(RawPhase.Move, 9.0)
        finger(RawPhase.Up, 9.0)
        assertEquals(0, drags, "no drawing tool: no hint")

        adapter.drawingTool = true
        finger(RawPhase.Down, 0.0)
        finger(RawPhase.Move, 5.0)
        finger(RawPhase.Move, 9.0)
        finger(RawPhase.Up, 9.0)
        assertEquals(1, drags, "one notification per drag, drawing with the finger")

        adapter.fingerDrawing = false
        finger(RawPhase.Down, 0.0)
        finger(RawPhase.Move, 5.0)
        assertEquals(1, drags, "a tap-sized move is not a drag")
        finger(RawPhase.Move, 40.0)
        finger(RawPhase.Move, 60.0)
        finger(RawPhase.Up, 60.0)
        assertEquals(2, drags, "panning with the finger notifies too")
    }

    @Test
    fun selectTool_should_PanOnEmptyCanvasWithOneFinger_And_ReachTheToolOnAnElement() {
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        adapter.selectTool = true
        adapter.isEmptyCanvas = { it.x > EMPTY_FROM_X }
        val onEmpty = adapter.onPointer(RawPointer(1, PointerKind.Touch, RawPhase.Down, ScreenPx(EMPTY_FROM_X + 10.0, 5.0)))
        assertTrue(onEmpty.isEmpty(), "no tool input: the finger pans")
        adapter.onPointer(RawPointer(1, PointerKind.Touch, RawPhase.Move, ScreenPx(EMPTY_FROM_X + 40.0, 5.0)))
        assertEquals(30.0, adapter.viewport.panX, 1e-9)
        adapter.onPointer(RawPointer(1, PointerKind.Touch, RawPhase.Up, ScreenPx(EMPTY_FROM_X + 40.0, 5.0)))

        val onElement = adapter.onPointer(RawPointer(2, PointerKind.Touch, RawPhase.Down, ScreenPx(5.0, 5.0)))
        assertTrue(onElement.single() is PointerAction.ToolInput, "a finger on an element moves it")
        adapter.onPointer(RawPointer(2, PointerKind.Touch, RawPhase.Up, ScreenPx(5.0, 5.0)))

        adapter.selectTool = false
        val drawing = adapter.onPointer(RawPointer(3, PointerKind.Touch, RawPhase.Down, ScreenPx(EMPTY_FROM_X + 10.0, 5.0)))
        assertTrue(drawing.single() is PointerAction.ToolInput, "other tools keep drawing with the finger")
    }

    @Test
    fun selectTool_should_StillMarqueeByPenOrMouse() {
        val adapter = SketchPointerAdapter(fingerDrawing = true)
        adapter.selectTool = true
        adapter.isEmptyCanvas = { true }
        for (kind in listOf(PointerKind.Stylus, PointerKind.Mouse)) {
            val down = adapter.onPointer(RawPointer(1, kind, RawPhase.Down, ScreenPx(10.0, 10.0)))
            assertTrue(down.single() is PointerAction.ToolInput, "$kind reaches the tool")
            adapter.onPointer(RawPointer(1, kind, RawPhase.Up, ScreenPx(10.0, 10.0)))
        }
    }

    private companion object {
        const val THREE_TRIGGERS = 3
        const val EMPTY_FROM_X = 100.0
    }
}
