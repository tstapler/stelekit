package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.SketchyAvailability
import dev.stapler.stelekit.sketch.v2.render.SketchyMeasurement
import dev.stapler.stelekit.sketch.v2.render.SketchyStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 2.2.3a, wasmJs side: architecture-15 in Sketchy drawn offscreen on wasm is compared with the JVM reference mask
 * ([Architecture15Golden]); it must stay within `SketchyAvailability.PIXEL_TOLERANCE_FRACTION`. No CJK glyphs are
 * involved (text is not painted), so the wasm font gap of the determinism spike does not enter.
 */
class LargeSceneDegradationWasmTest {
    @Test
    fun architecture15_should_MatchTheJvmMask_WithinTolerance_When_RenderedOffscreenOnWasm() {
        val mask = Architecture15Pixels.inkMask(Architecture15Pixels.render())
        val fraction = Architecture15Pixels.differingFraction(Architecture15Golden.INK_MASK, mask)
        println("ARCH15_WASM_DIFF fraction=$fraction")
        assertTrue(fraction <= SketchyAvailability.PIXEL_TOLERANCE_FRACTION, "wasm differs from JVM in $fraction of pixels")
        assertEquals(SketchyStatus.Available, SketchyAvailability.assess(SketchyMeasurement(fraction, 1L, 1L)))
    }

    @Test
    fun architecture15_should_BeNonBlank_When_RenderedOffscreenOnWasm() {
        val mask = Architecture15Pixels.inkMask(Architecture15Pixels.render())
        assertTrue(mask.any { it != '0' && it != '\n' }, "blank render")
    }
}
