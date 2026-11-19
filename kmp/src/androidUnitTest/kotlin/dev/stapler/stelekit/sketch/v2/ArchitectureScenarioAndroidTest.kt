// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 6.2.4a, requirements metric 2 (a) on Android: the same event script as the JVM test (`ArchitectureScenarioTest`) run
 * under Robolectric must produce the same scene as the golden, the same Clean and Sketchy draw lists and stay within 60 steps.
 * Gradle-only: it needs the commonTest `ArchitectureScenario` fixtures, which the Bazel android target does not carry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchitectureScenarioAndroidTest {
    @Test
    fun architectureScenario_should_MatchGoldenScene_When_RunUnderRobolectric() {
        val diagram = ArchitectureScenario.run()
        assertEquals(ArchitectureScenarioGolden.SCENE_JSON, ArchitectureScenario.sceneJson(diagram.editor))
        assertEquals(ArchitectureScenarioGolden.CLEAN_DRAW_LIST_DIGEST, ArchitectureScenario.drawListDigest(diagram.editor.scene, RenderStyle.Clean))
        assertEquals(ArchitectureScenarioGolden.SKETCHY_DRAW_LIST_DIGEST, ArchitectureScenario.drawListDigest(diagram.editor.scene, RenderStyle.Sketchy))
        assertTrue(diagram.editor.steps <= ArchitectureScenario.STEP_BUDGET, "${diagram.editor.steps} steps")
    }
}
