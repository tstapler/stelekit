// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

/**
 * The draw ops of a tool's live preview. Every preview element shares one id and version, so a caching [DrawListBuilder]
 * would serve the first drag sample's ops for the whole drag; each call therefore builds with a fresh builder.
 */
object LivePreviewOps {
    fun build(metrics: TextMetricsProvider, elements: List<SceneElement>, style: RenderStyle): List<DrawOp> {
        val scene = elements.fold(SketchSceneV2.empty()) { acc, e -> acc.add(e).getOrNull() ?: acc }
        return DrawListBuilder(metrics).build(scene, style)
    }
}
