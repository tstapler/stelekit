// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRunner
import dev.stapler.stelekit.sketch.v2.runtime.settings.FingerDrawingSettings
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.EditorExtras
import dev.stapler.stelekit.ui.sketch.v2.EditorPorts
import dev.stapler.stelekit.ui.sketch.v2.HostPlatform
import dev.stapler.stelekit.ui.sketch.v2.HostServices
import dev.stapler.stelekit.ui.sketch.v2.InMemorySettings
import dev.stapler.stelekit.ui.sketch.v2.OnboardingCounters
import dev.stapler.stelekit.ui.sketch.v2.OnboardingSequencer
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.RawPhase
import dev.stapler.stelekit.ui.sketch.v2.RawPointer
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorController
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review finding 5: Finger drawing and the touch-or-pen signal reach the editor's own pointer path, not only their unit tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class SketchEditorInputWiringTest {
    private class Rig(val controller: SketchEditorController, val extras: EditorExtras, val port: InMemoryDiagnosticsPort, val settings: FingerDrawingSettings)

    private fun TestScope.rig(): Rig {
        val raw = InMemorySettings()
        val settings = FingerDrawingSettings(raw)
        val dispatcher = StandardTestDispatcher(testScheduler)
        var n = 0
        val metrics = FixedAdvanceTextMetricsProvider()
        val controller = SketchEditorController(
            SketchDocumentV2(sheetUuid = "s"),
            EditorPorts(
                StandardToolRegistry.create(metrics),
                solver = SolveRunner(context = dispatcher),
                ids = ElementIdSource { ElementId("n${n++}") },
                sequencer = OnboardingSequencer(OnboardingCounters(raw)),
            ),
            dispatcher,
        )
        val port = InMemoryDiagnosticsPort(SketchEditorStrings.Banners)
        val services = HostServices(platform = HostPlatform(fingerDrawing = settings))
        return Rig(controller, EditorExtras(controller, services, ModernPreviewEnvironment(metrics, PainterResources()), port), port, settings)
    }

    private fun Rig.pen(kind: PointerKind) {
        var t = 0L
        fun p(phase: RawPhase) = controller.tools.onPointer(RawPointer(1, kind, phase, ScreenPx(10.0, 10.0), timestamp = t++))
        p(RawPhase.Down)
        p(RawPhase.Move)
        p(RawPhase.Up)
    }

    @Test
    fun fingerDrawing_should_StartOnAndFlipOffWithAnExplainingBanner_When_AStylusStrokeEnds() = runTest {
        val rig = rig()
        assertTrue(rig.controller.ui.fingerDrawing, "a device that has shown no stylus starts with Finger drawing on")

        rig.pen(PointerKind.Stylus)

        assertFalse(rig.controller.ui.fingerDrawing)
        assertTrue(rig.settings.stylusSeen)
        assertTrue(rig.port.diagnostics.value.entries.any { it.row == BannerRowId.PenDetected }, "the flip is never silent")
        rig.extras.close()
        rig.controller.close()
    }

    @Test
    fun adapter_should_ReportTheKindOfEveryNonPalmContact() = runTest {
        val rig = rig()
        val seen = mutableListOf<PointerKind>()
        rig.controller.tools.adapter.onKindSeen = { seen += it }

        rig.pen(PointerKind.Touch)
        rig.pen(PointerKind.Mouse)

        assertEquals(listOf(PointerKind.Touch, PointerKind.Mouse), seen)
        rig.extras.close()
        rig.controller.close()
    }

    @Test
    fun fingerDrag_should_ShowALivePreviewFromTheFirstMove_When_ARectangleToolIsChosen() = runTest {
        val rig = rig()
        rig.controller.tools.chooseTool(ToolId.Rectangle)
        rig.controller.tools.syncInput()
        var t = 0L
        fun touch(phase: RawPhase, x: Double, y: Double) = rig.controller.tools.onPointer(RawPointer(1, PointerKind.Touch, phase, ScreenPx(x, y), timestamp = t++))
        touch(RawPhase.Down, 100.0, 100.0)
        assertTrue(rig.controller.tools.preview == null, "a press alone draws nothing yet")
        touch(RawPhase.Move, 140.0, 130.0)
        assertTrue(rig.controller.tools.preview != null, "the first move already shows the rectangle")
        touch(RawPhase.Up, 140.0, 130.0)
        assertEquals(1, rig.controller.doc.scene.elementsInOrder.size)
        rig.extras.close()
        rig.controller.close()
    }
}
