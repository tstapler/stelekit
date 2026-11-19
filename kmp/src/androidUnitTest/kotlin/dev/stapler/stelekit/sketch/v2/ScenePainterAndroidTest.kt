package dev.stapler.stelekit.sketch.v2

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Story 2.1.3, Task 2.1.3c: the painter's pixel fixture under Robolectric native graphics. ADR-004 (Spike 0.1.3e) found
 * shapes exact against the JVM, so the fixture's colour samples are asserted with the same tolerance as on the JVM and
 * wasmJs. Real-device parity stays an owner check (Story 6.2.4c). Gradle-only: needs commonTest fixtures.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScenePainterAndroidTest {
    private fun at(px: IntArray, x: Int, y: Int) = px[y * ScenePainterFixture.WIDTH + x]

    @Test
    fun fixture_should_MatchTheJvmSamples_WithinTheChannelTolerance() {
        val px = ScenePainterFixture.pixels(ScenePainterFixture.render(ScenePainterFixture.ops()))
        assertTrue(ScenePainterFixture.close(ScenePainterFixture.RECT_FILL, at(px, 50, 35)), "rectangle fill")
        assertTrue(ScenePainterFixture.close(ScenePainterFixture.ELLIPSE_FILL, at(px, 150, 35)), "ellipse fill")
        assertTrue(ScenePainterFixture.close(0xFFFFFFFF.toInt(), at(px, 195, 115)), "background")
    }

    @Test
    fun render_should_BeStable_WhenRepeated() {
        val a = ScenePainterFixture.pixels(ScenePainterFixture.render(ScenePainterFixture.ops()))
        val b = ScenePainterFixture.pixels(ScenePainterFixture.render(ScenePainterFixture.ops()))
        assertTrue(ScenePainterFixture.differingFraction(a, b) <= ScenePainterFixture.MAX_DIFFERING_FRACTION)
    }
}
