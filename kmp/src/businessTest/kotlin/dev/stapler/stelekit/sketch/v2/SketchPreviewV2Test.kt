// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import dev.stapler.stelekit.ui.sketch.SketchPreview
import dev.stapler.stelekit.ui.sketch.SketchPreviewLoader
import dev.stapler.stelekit.ui.sketch.notShownText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Story 1.4.5 (review C4): a converted sheet's journal embed draws its projection instead of reading empty. */
class SketchPreviewV2Test {
    private val slug = MixedProjectFixtures.slug

    @Test
    fun convertedSheet_should_LoadAsDrawing_NotEmpty() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true)
        val loader = SketchPreviewLoader(d.repos, SheetProjectionSource { d.store })

        val drawing = assertIs<SketchPreview.Drawing>(loader.load(slug))

        assertEquals(MixedProjectFixtures.sheetAShapes.size, drawing.document.scene.size)
        assertEquals(0, drawing.notShownCount)
    }

    @Test
    fun convertedSheet_should_DrawTheSamePrimitivesAsBeforeConversion() = runBlocking<Unit> {
        val plain = MixedProjectFixtures.device(convertA = false)
        val mixed = MixedProjectFixtures.device(convertA = true)

        val before = assertIs<SketchPreview.Drawing>(SketchPreviewLoader(plain.repos).load(slug))
        val after = assertIs<SketchPreview.Drawing>(SketchPreviewLoader(mixed.repos, SheetProjectionSource { mixed.store }).load(slug))

        assertEquals(before.document.scene.primitives, after.document.scene.primitives)
    }

    @Test
    fun elementsThatDoNotProject_should_BeCounted() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true, extraInA = listOf(MixedProjectFixtures.ellipse("e1"), MixedProjectFixtures.ellipse("e2")))
        val loader = SketchPreviewLoader(d.repos, SheetProjectionSource { d.store })

        val drawing = assertIs<SketchPreview.Drawing>(loader.load(slug))

        assertEquals(MixedProjectFixtures.sheetAShapes.size, drawing.document.scene.size)
        assertEquals(2, drawing.notShownCount)
        assertEquals("2 elements not shown in this preview", notShownText(drawing.notShownCount))
    }

    @Test
    fun v2SheetWithoutAProjectionSource_should_BeEmptyNotACrash() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true)

        assertIs<SketchPreview.Empty>(SketchPreviewLoader(d.repos).load(slug))
    }
}
