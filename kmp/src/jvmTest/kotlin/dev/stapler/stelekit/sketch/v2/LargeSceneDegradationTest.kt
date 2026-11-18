// jvmTest-reason: pixels
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.SketchyAvailability
import dev.stapler.stelekit.sketch.v2.render.SketchyMeasurement
import dev.stapler.stelekit.sketch.v2.render.SketchyStatus
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.LargeSceneDegradation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 2.2.3 on the JVM rasteriser: the architecture-15 reference mask, degradation at 4,000 elements, and the build-time ratio. */
class LargeSceneDegradationTest {
    private val limits = SceneLimits.Default

    private fun grid(count: Int): SketchSceneV2 = (0 until count).fold(SketchSceneV2.empty()) { s, i ->
        val e = RectElement(
            ElementBase(ElementId("g$i"), style = ElementStyle(roughness = 1), seed = i + 1),
            SceneUnit.ofInt((i % COLUMNS) * STEP), SceneUnit.ofInt((i / COLUMNS) * STEP), SceneUnit.ofInt(CELL), SceneUnit.ofInt(CELL),
        )
        s.add(e).getOrNull()!!
    }

    private fun builder() = DrawListBuilder(FixedAdvanceTextMetricsProvider())

    private val port = InMemoryDiagnosticsPort(
        object : BannerStringSource {
            override fun summary(row: BannerRowId) = "Large drawing: effects off."
            override fun shortTitle(row: BannerRowId) = "Large drawing"
            override fun primaryLabel(row: BannerRowId) = "Learn more"
            override fun moreItems(row: BannerRowId) = listOf("Dismiss")
        },
    )

    @Test
    fun architecture15_should_MatchTheReferenceMask_When_RenderedSketchyOnJvm() {
        val mask = Architecture15Pixels.inkMask(Architecture15Pixels.render())
        assertEquals(0.0, Architecture15Pixels.differingFraction(Architecture15Golden.INK_MASK, mask), "the JVM render is the reference")
    }

    @Test
    fun sketchyRequest_should_DrawAsClean_When_TheSceneHoldsTheSoftThresholdOfElements() {
        val policy = LargeSceneDegradation(port)
        val large = grid(limits.softElementThreshold)
        val degradation = policy.onCounts(large.size, 0)
        val drawn = builder().build(large, degradation.effectiveStyle(RenderStyle.Sketchy))
        assertEquals(builder().build(large, RenderStyle.Clean), drawn, "degraded Sketchy is the Clean draw list")
        assertEquals(1, port.diagnostics.value.entries.count { it.row == BannerRowId.LargeDrawing })
    }

    @Test
    fun sketchyRequest_should_StayHandDrawn_When_OneElementUnderTheThreshold() {
        val policy = LargeSceneDegradation(port)
        val almost = grid(limits.softElementThreshold - 1)
        val degradation = policy.onCounts(almost.size, 0)
        assertNotEquals(builder().build(almost, RenderStyle.Clean), builder().build(almost, degradation.effectiveStyle(RenderStyle.Sketchy)))
        assertTrue(port.diagnostics.value.entries.none { it.row == BannerRowId.LargeDrawing })
    }

    @Test
    fun sketchyBuild_should_StayUnderTheAbsoluteBudget_For500Elements() {
        val scene = grid(SAMPLE_ELEMENTS)
        // One sample is BUILDS_PER_SAMPLE builds so JIT/GC jitter cannot dominate a sub-millisecond build; each build gets a
        // fresh builder because the builder caches per element id (reusing one would time the cache).
        fun best(style: RenderStyle): Long = (0 until ROUNDS).minOf {
            val start = System.nanoTime()
            repeat(BUILDS_PER_SAMPLE) { builder().build(scene, style) }
            (System.nanoTime() - start) / BUILDS_PER_SAMPLE
        }
        best(RenderStyle.Clean)
        best(RenderStyle.Sketchy)
        val measurement = SketchyMeasurement(0.0, sketchyBuildNanos = best(RenderStyle.Sketchy), cleanBuildNanos = best(RenderStyle.Clean))
        println("SKETCHY_BUILD_RATIO sketchy=${measurement.sketchyBuildNanos} clean=${measurement.cleanBuildNanos} ratio=${measurement.ratio} sketchyMs=${measurement.sketchyBuildMillis} budgetMs=${SketchyAvailability.SKETCHY_BUILD_BUDGET_MS}")
        assertEquals(SketchyStatus.Available, SketchyAvailability.assess(measurement), "$measurement")
    }

    private companion object {
        const val COLUMNS = 100
        const val STEP = 24
        const val CELL = 16
        const val SAMPLE_ELEMENTS = 500
        const val ROUNDS = 15
        const val BUILDS_PER_SAMPLE = 25
    }
}
