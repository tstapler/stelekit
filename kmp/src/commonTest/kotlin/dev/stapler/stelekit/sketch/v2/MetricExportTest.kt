// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.MetricScene
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExportOptions
import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.su
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.MetricLength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A metric display flows through the shared annotation engine: the SVG shows metric values, exact to 0.1 mm. */
class MetricExportTest {
    private val builder = DrawListBuilder(FixedAdvanceTextMetricsProvider())

    private fun svg(scene: SketchSceneV2, format: LengthFormat) =
        SvgExporter.export(scene, builder, RenderStyle.Clean, options = SvgExportOptions(background = "#ffffff", dimensions = DimensionExportOptions(format = format)))

    private fun scene(vararg e: SceneElement) = e.fold(SketchSceneV2.empty()) { s, x -> s.add(x).getOrNull()!! }

    private fun labels(svg: String): List<String> = Regex("<tspan[^>]*>([^<]*)</tspan>").findAll(svg).map { it.groupValues[1] }.toList()

    @Test
    fun svg_should_ShowTheRoomInMetricUnits_Golden() {
        val room = scene(
            filledRectEl("room", 0, 0, 1920, 1440),
            filledRectEl("table", 400, 400, 768, 384),
            EllipseElement(ElementBase(ElementId("rug")), su(1500), su(1000), su(240), su(240)),
        )
        assertEquals(listOf("3048 mm", "2286 mm", "1219.2 mm", "609.6 mm", "Ø 762 mm"), labels(svg(room, LengthFormat.METRIC_MM)))
        assertEquals(listOf("3.048 m", "2.286 m", "1.2192 m", "60.96 cm", "Ø 76.2 cm"), labels(svg(room, LengthFormat.METRIC_CM_M)))
    }

    @Test
    fun svg_should_ShowASubSixteenthShapeToTenthsOfAMillimetre() {
        val shape = filledRectEl("r", 0, 0, 100, 100).copy(width = SceneUnit.of(MetricScene.toUnits(1230)).getOrNull()!!)
        val out = labels(svg(scene(shape), LengthFormat.METRIC_MM))
        assertTrue("123 mm" in out, out.toString())
    }

    @Test
    fun svg_should_StayInInches_ByDefault() {
        assertEquals(listOf("10' 0\"", "7' 6\""), labels(svg(scene(filledRectEl("r", 0, 0, 1920, 1440)), LengthFormat.FEET_INCHES)))
    }
}
