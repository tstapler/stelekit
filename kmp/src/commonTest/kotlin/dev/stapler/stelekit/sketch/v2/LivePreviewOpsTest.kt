// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.LivePreviewOps
import kotlin.test.Test
import kotlin.test.assertNotEquals

class LivePreviewOpsTest {
    private fun rect(size: Int) =
        RectElement(ElementBase(ElementId("preview")), SceneUnit.ofInt(0), SceneUnit.ofInt(0), SceneUnit.ofInt(size), SceneUnit.ofInt(size))

    @Test
    fun aGrowingPreview_should_DrawEachSampleAtItsOwnSize_NotTheFirstSamplesCachedShape() {
        val metrics = FixedAdvanceTextMetricsProvider()
        val small = LivePreviewOps.build(metrics, listOf(rect(10)), RenderStyle.Clean)
        val large = LivePreviewOps.build(metrics, listOf(rect(200)), RenderStyle.Clean)
        assertNotEquals(small, large, "same id and version, different geometry")
    }
}
