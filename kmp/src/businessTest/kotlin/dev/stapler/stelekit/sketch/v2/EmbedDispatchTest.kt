// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.projectbridge.sidecarPath
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.SketchPreview
import dev.stapler.stelekit.ui.sketch.SketchPreviewLoader
import dev.stapler.stelekit.ui.sketch.SketchRenderRoute
import kotlinx.coroutines.runBlocking
import kotlin.time.TimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 6.1.1: one dispatch per sheet, the missing / newer / no-storage states, and the 500-element thumbnail budget. */
class EmbedDispatchTest {
    private val slug = MixedProjectFixtures.slug

    @Test
    fun route_should_KeyOnTheSchemaVersionMirror() {
        assertEquals(SketchRenderRoute.Classic, SketchRenderRoute.of(1))
        assertEquals(SketchRenderRoute.Classic, SketchRenderRoute.of(0))
        assertEquals(SketchRenderRoute.Modern, SketchRenderRoute.of(2))
        assertEquals(SketchRenderRoute.Newer, SketchRenderRoute.of(3))
        assertEquals(SketchRenderRoute.Newer, SketchRenderRoute.of(Long.MAX_VALUE))
    }

    @Test
    fun v1AndV2Sheets_should_BothRender_EachByItsOwnRenderer() = runBlocking<Unit> {
        val v1 = MixedProjectFixtures.device(convertA = false)
        val v2 = MixedProjectFixtures.device(convertA = true)

        assertIs<SketchPreview.Drawing>(SketchPreviewLoader(v1.repos, SheetProjectionSource { v1.store }).load(slug))
        assertIs<SketchPreview.ModernDrawing>(SketchPreviewLoader(v2.repos, SheetProjectionSource { v2.store }).load(slug))
    }

    @Test
    fun deletedSketch_should_ReadAsMissing_AndWriteNothing() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true)
        val writes = d.fs.writeCount

        val preview = SketchPreviewLoader(d.repos, SheetProjectionSource { d.store }).load("proj-gone")

        assertEquals(SketchPreview.Missing, preview)
        assertEquals(writes, d.fs.writeCount, "no sidecar file is created for a missing sketch")
    }

    @Test
    fun sheetFileFromANewerApp_should_ShowNewerSchema_InsteadOfCrashing() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true)
        val path = sidecarPath(slug, "sheets/${MixedProjectFixtures.SHEET_A}.v2.json")
        val text = d.fs.readFileBytes(path)!!.decodeToString()
        assertTrue("\"schema\":2" in text, "the fixture file leads with its schema")
        d.fs.seed(path, text.replaceFirst("\"schema\":2", "\"schema\":3").encodeToByteArray())

        val preview = SketchPreviewLoader(d.repos, SheetProjectionSource { d.store }).load(slug)

        assertEquals(SketchPreview.NewerSchema, preview)
    }

    @Test
    fun v2SheetWithoutFileStorage_should_ShowThePlaceholder_ReadNothingAndWriteNothing() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = true)
        val writes = d.fs.writeCount
        var reads = 0
        val loader = SketchPreviewLoader(d.repos, SheetProjectionSource { reads++; d.store }, v2StorageAvailable = { false })

        assertEquals(SketchPreview.StorageUnavailable, loader.load(slug))
        assertEquals(0, reads, "the sidecar is never opened")
        assertEquals(writes, d.fs.writeCount)
    }

    @Test
    fun v1Sheet_should_StillRenderWithoutFileStorage() = runBlocking<Unit> {
        val d = MixedProjectFixtures.device(convertA = false)

        assertIs<SketchPreview.Drawing>(SketchPreviewLoader(d.repos, SheetProjectionSource { d.store }, v2StorageAvailable = { false }).load(slug))
    }

    @Test
    fun thumbnailOf500Elements_should_BuildWithinTheLoadingStateThreshold() = runBlocking<Unit> {
        val extra = (0 until ELEMENTS).map { MixedProjectFixtures.ellipse("e$it") }
        val d = MixedProjectFixtures.device(convertA = true, extraInA = extra)
        val drawing = assertIs<SketchPreview.ModernDrawing>(SketchPreviewLoader(d.repos, SheetProjectionSource { d.store }).load(slug))
        assertTrue(drawing.scene.size >= ELEMENTS)

        var best = Long.MAX_VALUE
        repeat(RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            val ops = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(drawing.scene, RenderStyle.Sketchy)
            best = minOf(best, mark.elapsedNow().inWholeMilliseconds)
            assertTrue(ops.size >= ELEMENTS)
        }

        assertTrue(best < LOADING_STATE_MS, "building a 500-element thumbnail took $best ms (the loading indicator starts at $LOADING_STATE_MS ms, UX-94)")
    }

    private companion object {
        const val ELEMENTS = 500
        const val RUNS = 5
        const val LOADING_STATE_MS = 200L
    }
}
