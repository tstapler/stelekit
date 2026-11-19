// jvmTest-reason: pixels
// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.export.PngExporter
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.ui.sketch.NewSheetKind
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 6.2.4g, the rendered slice and the input to the A1 gate (Task 6.2.4e): the headless scenario created as a NEW v2 sheet
 * through the router with the flag on, saved, reopened, and Clean-rendered. The check is draw-list equality between the
 * in-memory and the reloaded scene, plus a non-blank pixel check. The owner's real diagram is NOT part of this test: the gate
 * result stays UNMEASURED until `a1-gate.md` records it.
 */
class RenderedSliceScenarioTest {
    private object StubMetrics : TextMetricsProvider {
        override val fontStatus: FontStatus = FontStatus.Loaded
        override fun lineHeight(style: TextStyle): Double = style.fontSize
        override fun advance(text: String, style: TextStyle): Double = text.length * style.fontSize
        override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = true
    }

    private val resources = PainterResources(
        textMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr),
        image = { null },
    )

    private fun u(v: Int) = SceneUnit.ofInt(v)

    private val boxA = RectElement(ElementBase(ElementId("box-a")), u(0), u(0), u(120), u(80))
    private val boxB = RectElement(ElementBase(ElementId("box-b")), u(300), u(0), u(120), u(80))
    private val label = TextElement(ElementBase(ElementId("label")), u(10), u(30), u(100), u(25), text = "Auth", containerId = ElementId("box-a"))
    private val arrow = ArrowElement(
        base = ElementBase(ElementId("link")),
        points = listOf(ScenePoint(u(120), u(40)), ScenePoint(u(300), u(40))),
        startBinding = Binding(ElementId("box-a"), 1.0, 0.5),
        endBinding = Binding(ElementId("box-b"), 0.0, 0.5),
    )

    private fun scenario(vararg commands: Command): SketchDocumentV2 =
        commands.fold(SketchDocumentV2(sheetUuid = MixedProjectFixtures.SHEET_A)) { doc, command ->
            Transaction.begin(doc).apply(command).getOrNull()!!.commit().getOrNull()!!.after
        }

    private fun routeInput(mode: V2Mode, schema: SheetSchema) =
        RouteInput(mode, V2Capabilities(conversionReady = true, conversionOffered = true, storage = V2Storage.Available), schema)

    @Test
    fun newV2Sheet_should_RouteEditable_SaveReopenAndRenderTheSameCleanDrawList() = runBlocking<Unit> {
        val flagOn = routeInput(V2Mode.On, SheetSchema.V1)
        assertEquals(NewSheetKind.V2, SketchEditorRouter.newSheetKind(flagOn, projectIsNew = true), "a new sheet is v2 with the flag on")
        assertEquals(RouteOutcome.V2Editable, SketchEditorRouter.route(routeInput(V2Mode.On, SheetSchema.V2)))

        val rig = SaverRig(MixedProjectFixtures.device(convertA = true), coroutineContext)
        val doc = scenario(AddElement(boxA), AddElement(boxB), AddElement(label), AddElement(arrow))
        rig.saver.submit(doc)
        assertEquals(SaveState.Saved, rig.saver.flush())
        val reopened = SheetCodecV2.decode(rig.diskBytes()).getOrNull()!!

        val builder = DrawListBuilder(StubMetrics)
        val before = builder.build(doc.scene, RenderStyle.Clean)
        val after = DrawListBuilder(StubMetrics).build(reopened.scene, RenderStyle.Clean)
        assertTrue(before.isNotEmpty(), "the scene draws something")
        assertEquals(before, after, "the reopened sheet draws the same Clean draw list")

        val png = PngExporter(resources = resources).export(reopened.scene, builder, RenderStyle.Clean).getOrNull()!!
        val image = ImageIO.read(ByteArrayInputStream(png))
        val inked = (0 until image.height).sumOf { y -> (0 until image.width).count { x -> (image.getRGB(x, y) and RGB_MASK) != WHITE_RGB } }
        assertTrue(inked > MIN_INKED_PIXELS, "the render is not blank: $inked inked pixels")
        rig.saver.close()
    }

    private companion object {
        const val RGB_MASK = 0xFFFFFF
        const val WHITE_RGB = 0xFFFFFF
        const val MIN_INKED_PIXELS = 100
    }
}
