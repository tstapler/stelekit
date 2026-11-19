// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 3.6.6b (UX-47): every transition in the v2 UI goes through `MotionPolicy`, so reduced motion replaces all of them by an
 * instant change. The grep fails on an animation API, an infinite transition or a Material progress indicator (which animates
 * itself when indeterminate) in any `ui/sketch/v2` source except `MotionPolicy.kt`. A comment is exempt.
 */
class MotionPolicyGrepTest {
    private val scope = "ui/sketch/v2/"
    private val gateway = "ui/sketch/v2/MotionPolicy.kt"

    private val forbidden = Regex(
        """\banimate\w*As\w*State\b|\bAnimatedVisibility\b|\bAnimatedContent\b|\bAnimatable\b|\brememberInfiniteTransition\b|""" +
            """\bupdateTransition\b|\bCrossfade\b|\banimateContentSize\b|\banimateScroll\w*|\banimateItem\b|""" +
            """\b(?:Linear|Circular)ProgressIndicator\b""",
    )

    private fun violating(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") && forbidden.containsMatchIn(t)
    }

    private fun found(): List<String> {
        val sources = WiringInputs.productionSources
        assertTrue(sources.isNotEmpty(), "no production sources loaded")
        val out = mutableListOf<String>()
        for ((key, text) in sources.toSortedMap()) {
            val rel = key.substringAfter("dev/stapler/stelekit/", "")
            if (!rel.startsWith(scope) || rel == gateway) continue
            for (line in text.lineSequence()) if (violating(line)) out += "$rel :: ${line.trim()}"
        }
        return out
    }

    @Test
    fun noAnimationApi_should_AppearInV2Ui_OutsideMotionPolicy() {
        val hits = found()
        assertTrue(hits.isEmpty(), "animation outside MotionPolicy (route it through MotionPolicy so reduced motion applies):\n" + hits.joinToString("\n"))
    }

    @Test
    fun theGateway_should_BeScanned_AndOwnTheAnimationApis() {
        val keys = WiringInputs.productionSources.keys.map { it.substringAfter("dev/stapler/stelekit/", "") }
        assertTrue(gateway in keys, "MotionPolicy.kt was not loaded")
        val text = WiringInputs.productionSources.entries.first { it.key.endsWith("ui/sketch/v2/MotionPolicy.kt") }.value
        assertTrue(text.lines().any { violating(it) }, "the gateway is the one place that uses the animation APIs")
    }

    @Test
    fun detector_should_FlagPlantedAnimations_AndIgnoreComments() {
        val flagged = listOf(
            "val x by animateFloatAsState(1f)",
            "AnimatedVisibility(visible) { Text(\"a\") }",
            "val a = Animatable(0f)",
            "LinearProgressIndicator()",
            "scroll.animateScrollTo(0)",
            "val t = rememberInfiniteTransition()",
        )
        for (line in flagged) assertTrue(violating(line), "missed: $line")
        val benign = listOf("// AnimatedVisibility is banned", " * Animatable", "fun SolveProgressIndicator(x: Int) {}", "scroll.scrollTo(0)")
        for (line in benign) assertEquals(false, violating(line), "wrongly flagged: $line")
    }
}
