// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.image

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

/** Which asset files a scene needs; consumed by export, copy and the missing-file placeholder (Stories 3.4.3, 5.1.3, 5.2.1, 6.1.2). */
object ReferencedAssets {
    /** The `fileId`s of the live image elements, in scene order, each once. A removed element's file is not listed. */
    fun referencedFileIds(scene: SketchSceneV2): Set<String> =
        scene.elementsInOrder.filterIsInstance<ImageElement>().mapNotNullTo(LinkedHashSet()) { it.fileId }

    /**
     * Image elements that must draw a placeholder: no `fileId` at all, or a file the store cannot supply
     * ([available] false). The element is kept so the sheet round-trips; only its picture is missing.
     */
    fun placeholders(scene: SketchSceneV2, available: (String) -> Boolean): List<ElementId> =
        scene.elementsInOrder.filterIsInstance<ImageElement>()
            .filter { it.fileId == null || !available(it.fileId) }
            .map { it.id }
}
