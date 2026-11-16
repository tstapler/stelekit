// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.image

import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 3.4.3: referencedFileIds returns exactly the live set; placeholders cover missing files. */
class ReferencedAssetsTest {
    private fun image(id: String, fileId: String?) =
        ImageElement(ElementBase(ElementId(id)), SceneUnit.ZERO, SceneUnit.ZERO, SceneUnit.ofInt(10), SceneUnit.ofInt(10), fileId = fileId)

    private fun scene(vararg e: ImageElement): SketchSceneV2 = e.fold(SketchSceneV2.empty()) { s, el -> s.add(el).getOrElse { error("add") } }

    @Test
    fun referencedFileIds_should_ReturnLiveSetOnce() {
        val full = scene(image("a", "f1"), image("b", "f1"), image("gone", "f2"))
        val live = full.remove(ElementId("gone")).getOrElse { error("remove") }
        assertEquals(setOf("f1"), ReferencedAssets.referencedFileIds(live))
        assertEquals(listOf("f1", "f2"), ReferencedAssets.referencedFileIds(full).toList())
    }

    @Test
    fun placeholders_should_ListElementsWithNoFileOrAMissingFile() {
        val s = scene(image("a", "f1"), image("b", null), image("c", "f3"))
        assertEquals(listOf(ElementId("b"), ElementId("c")), ReferencedAssets.placeholders(s) { it == "f1" })
    }
}
