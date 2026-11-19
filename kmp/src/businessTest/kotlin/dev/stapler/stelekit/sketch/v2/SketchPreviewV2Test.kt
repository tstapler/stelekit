// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import dev.stapler.stelekit.ui.sketch.SketchPreview
import dev.stapler.stelekit.ui.sketch.SketchPreviewLoader
import dev.stapler.stelekit.ui.sketch.notShownText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Story 1.4.5 (review C4), updated by Story 6.1.1: a converted sheet's journal embed draws its own v2 scene and never reads empty. */
class SketchPreviewV2Test {
    private val slug = MixedProjectFixtures.slug

    @Test
    fun convertedSheet_should_LoadAsModernDrawing_NotEmpty() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true)
        val loader = SketchPreviewLoader(d.repos, SheetProjectionSource { d.store })

        val drawing = assertIs<SketchPreview.ModernDrawing>(loader.load(slug))

        assertEquals(MixedProjectFixtures.sheetAShapes.size, drawing.scene.size)
    }

    @Test
    fun convertedSheet_should_DrawTheSamePrimitivesAsBeforeConversion() = runBlocking<Unit> {
        val plain = MixedProjectFixtures.device(convertA = false)
        val mixed = MixedProjectFixtures.device(convertA = true)

        val before = assertIs<SketchPreview.Drawing>(SketchPreviewLoader(plain.repos).load(slug))
        val after = assertIs<SketchPreview.ModernDrawing>(SketchPreviewLoader(mixed.repos, SheetProjectionSource { mixed.store }).load(slug))

        assertEquals(before.document.scene.primitives, V2ToV1.projectScene(after.scene).primitives)
    }

    @Test
    fun elementsThatV1CannotHold_should_BeDrawnByTheV2Preview() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true, extraInA = listOf(MixedProjectFixtures.ellipse("e1"), MixedProjectFixtures.ellipse("e2")))
        val loader = SketchPreviewLoader(d.repos, SheetProjectionSource { d.store })

        val drawing = assertIs<SketchPreview.ModernDrawing>(loader.load(slug))

        assertEquals(MixedProjectFixtures.sheetAShapes.size + 2, drawing.scene.size)
        assertEquals("2 elements not shown in this preview", notShownText(2))
    }

    @Test
    fun v2SheetWithoutAProjectionSource_should_BeEmptyNotACrash() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true)

        assertIs<SketchPreview.Empty>(SketchPreviewLoader(d.repos).load(slug))
    }
}
