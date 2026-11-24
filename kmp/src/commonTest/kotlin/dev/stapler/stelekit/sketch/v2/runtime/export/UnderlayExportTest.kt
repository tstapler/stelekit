// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2.runtime.export

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.SetUnderlay
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.export.SvgImage
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.hit.HitTester
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.UnderlayPlacement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.platform.PngEncoder
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epic E5: an export leaves the photo underlay out unless "include underlay" is on; the editor's hit testing ignores it once locked. */
class UnderlayExportTest {
    private val fileId = "c".repeat(64)
    private val png = ByteArray(16) { it.toByte() }
    private val images = SvgImageSource { id -> if (id == fileId) SvgImage("image/png", png) else null }
    private val runner = SketchExportRunner(PngExporter(PngEncoder(), PainterResources()), DrawListBuilder(FixedAdvanceTextMetricsProvider()))

    private fun source(): ExportSource {
        val room = RectElement(ElementBase(ElementId("room")), SceneUnit.ofInt(100), SceneUnit.ofInt(100), SceneUnit.ofInt(200), SceneUnit.ofInt(150))
        val doc = AddElement(room).apply(SketchDocumentV2(sheetUuid = "s")).getOrNull()!!.doc
        val withPhoto = SetUnderlay(fileId, 4000, 3000, UnderlayPlacement(-2_000.0, -1_500.0, 4_000.0, 3_000.0, 0.0)).apply(doc).getOrNull()!!.doc
        return ExportSource(withPhoto.scene, emptySet(), images, baseName = "plan")
    }

    private fun text(request: ExportRequest): String = (runner.run(source(), request) as Either.Right).value.bytes.decodeToString()

    @Test
    fun svg_should_LeaveOutThePhoto_ByDefault_AndDrawItWhenIncluded() {
        val plain = text(ExportRequest(format = ExportFormat.Svg))
        assertFalse("data:image/png" in plain, "the photo is a tracing aid, not part of the drawing")
        val included = text(ExportRequest(format = ExportFormat.Svg, includeUnderlay = true))
        assertTrue("data:image/png;base64" in included)
    }

    @Test
    fun svg_should_FitTheDrawing_NotThePhoto_When_TheUnderlayIsLeftOut() {
        fun viewBoxWidth(svg: String) = Regex("viewBox=\"[-0-9. ]+ ([0-9.]+) [0-9.]+\"").find(svg)!!.groupValues[1].toDouble()
        val plain = viewBoxWidth(text(ExportRequest(format = ExportFormat.Svg)))
        val included = viewBoxWidth(text(ExportRequest(format = ExportFormat.Svg, includeUnderlay = true)))
        assertTrue(plain < 400.0, "the room is 200 wide plus padding: $plain")
        assertTrue(included > 4_000.0, "the photo is 4000 wide: $included")
    }

    @Test
    fun excalidraw_should_NotCarryThePhoto_UnlessIncluded() {
        assertFalse(fileId in text(ExportRequest(format = ExportFormat.Excalidraw)))
        assertTrue(fileId in text(ExportRequest(format = ExportFormat.Excalidraw, includeUnderlay = true)))
    }

    @Test
    fun includeUnderlay_should_DefaultToOff() {
        assertFalse(ExportRequest().includeUnderlay)
    }

    @Test
    fun aLockedUnderlay_should_NeverBeHit_AndNeverCountAsLocked() {
        val scene = source().scene
        val index = SpatialIndex().also { it.rebuild(scene) }
        val tester = HitTester(scene, index)
        val inside = ScenePoint(SceneUnit.ofInt(1_000), SceneUnit.ofInt(1_000))
        assertNull(tester.hit(inside, 1.0), "a locked photo does not take presses")
        assertTrue(tester.hitLocked(inside, 1.0).isEmpty(), "and does not make the eraser say a locked element was skipped")
        assertEquals("room", tester.hit(ScenePoint(SceneUnit.ofInt(200), SceneUnit.ofInt(100)), 1.0)?.value)
    }
}
