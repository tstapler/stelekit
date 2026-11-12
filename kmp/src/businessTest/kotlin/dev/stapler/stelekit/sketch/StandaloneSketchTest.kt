// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch

import arrow.core.left
import dev.stapler.stelekit.asset.DrawingLocation
import dev.stapler.stelekit.asset.DrawingLocationRule
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.platform.AtomicFileWriter
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.export.SketchExporter
import dev.stapler.stelekit.sketch.export.SketchSvgWriter
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(DirectRepositoryWrite::class)
class StandaloneSketchTest {
    private class Fs(private val tempRename: Boolean = true) : FakeRelocationFileSystem() {
        override val supportsTempRenameWrites get() = tempRename
    }

    private val repos = inMemorySketchRepositories()
    private fun exporter(fs: Fs) = SketchExporter(fs, AtomicFileWriter(fs, { it }, ".tmp") { 1L }, "/g")
    private fun service(fs: Fs = Fs(), exporter: SketchExporter? = exporter(fs), random: Random = Random(7)) =
        StandaloneSketchService(repos.projects, { it() }, exporter, { 5L }, random)

    @Test
    fun createStoresAStandaloneProjectAndWritesABlankPreviewNextToTheSidecar() = runBlocking<Unit> {
        val fs = Fs()
        val sketch = service(fs).create().getOrNull()!!
        val stored = repos.projects.loadProject(ProjectUuid(sketch.projectUuid)).getOrNull()!!.project
        assertTrue(stored.isStandalone)
        assertEquals(sketch.slug, stored.slug)
        val preview = fs.readFile("/g/assets/stelekit-sketch/${sketch.slug}/preview.svg")
        assertTrue(preview!!.contains(SketchSvgWriter.MARKER))
        assertEquals("![sketch](../assets/stelekit-sketch/${sketch.slug}/preview.svg)", sketch.embedMarkdown)
    }

    @Test
    fun previewStaysOutOfAssetRowsSoItIsNeverSentToCloudImageDescription() {
        val rule = DrawingLocationRule.classify("/g/assets/stelekit-sketch/proj-0a1b2c/preview.svg", EffectiveNotesPath("/g"))
        assertEquals(DrawingLocation.Skip, rule)
    }

    @Test
    fun noEmbedIsOfferedWhenThePlatformCannotWriteTheFile() = runBlocking<Unit> {
        val sketch = service(Fs(tempRename = false)).create().getOrNull()!!
        assertNull(sketch.embedMarkdown)
        assertNotNull(repos.projects.loadProject(ProjectUuid(sketch.projectUuid)).getOrNull())
    }

    @Test
    fun noEmbedWithoutAnExporter() = runBlocking<Unit> {
        assertNull(service(exporter = null).create().getOrNull()!!.embedMarkdown)
    }

    @Test
    fun aFailedWriteIsReportedAfterRetryingWithFreshSlugs() = runBlocking<Unit> {
        var attempts = 0
        val failing = StandaloneSketchService(
            repos.projects, { attempts++; DomainError.DatabaseError.WriteFailed("boom").left() }, null, { 1L },
        )
        assertTrue(failing.create().isLeft())
        assertEquals(3, attempts)
    }

    @Test
    fun aSlugCollisionRetriesAndSucceeds() = runBlocking<Unit> {
        var attempts = 0
        val flaky = StandaloneSketchService(
            repos.projects,
            { op -> if (attempts++ == 0) DomainError.DatabaseError.WriteFailed("UNIQUE").left() else op() },
            null, { 1L },
        )
        assertTrue(flaky.create().isRight())
        assertEquals(2, attempts)
    }

    @Test
    fun embedReferenceRoundTripsToTheSlug() {
        val md = SketchEmbed.markdown("proj-0a1b2c")
        assertEquals("proj-0a1b2c", SketchEmbed.slugOf(md))
        assertEquals("proj-0a1b2c", SketchEmbed.slugOf("file:///g/assets/stelekit-sketch/proj-0a1b2c/preview.svg"))
        assertNull(SketchEmbed.slugOf("../assets/photo.png"))
        assertNull(SketchEmbed.slugOf("../assets/stelekit-sketch/proj-0a1b2c/exports/x.svg"))
    }

    @Test
    fun findBySlugLocatesTheSketchAndReturnsNullForUnknownSlugs() = runBlocking<Unit> {
        val sketch = service().create().getOrNull()!!
        assertEquals(sketch.projectUuid, repos.projects.findUuidBySlug(sketch.slug)?.value)
        assertNull(repos.projects.findUuidBySlug("proj-ffffff"))
    }

    @Test
    fun findBySlugPagesPastTheFirstPage() = runBlocking<Unit> {
        repeat(205) { i ->
            val slug = "proj-" + i.toString(16).padStart(6, '0')
            repos.projects.saveProject(ProjectFactory.create("p$i", slug, ProjectFactory.newUuid(), i.toLong()))
        }
        assertNotNull(repos.projects.findUuidBySlug("proj-000000"))
        assertNull(repos.projects.findUuidBySlug("proj-abcdef"))
    }

    @Test
    fun ordinaryProjectsAreNotStandalone() {
        assertFalse(ProjectFactory.create("Kitchen", "proj-0a1b2c", ProjectFactory.newUuid(), 1L).isStandalone)
    }

    @Test
    fun previewIsRewrittenWithTheDrawingAndStaysIdempotent() {
        val fs = Fs()
        val exporter = exporter(fs)
        val path = exporter.exportPreview(SketchDocument(), "proj-0a1b2c").getOrNull()!!
        assertEquals(path, exporter.exportPreview(SketchDocument(), "proj-0a1b2c").getOrNull())
        assertTrue(exporter.exportPreview(SketchDocument(), "../x").isLeft())
        assertEquals(emptyList(), fs.allFilePaths().filter { it.endsWith(".tmp") })
    }
}
