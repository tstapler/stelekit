// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.ui.sketch.v2.ProjectTechnicalData
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The technical tab's data source (Story 4.2.5): reads the project bundle and saves only the named dimensions and this sheet's zones. */
class ProjectTechnicalDataTest {
    private val slug = "proj-abc123"
    private val sheet = SheetUuid("s1")

    private class Fixture(val repos: SketchRepositories, val store: ProjectSidecarStore, val source: ProjectTechnicalData, val bundle: ProjectBundle)

    private suspend fun fixture(): Fixture {
        val repos = inMemorySketchRepositories()
        val project = ProjectFactory.create("Kitchen", slug, ProjectFactory.newUuid(), 1L)
        val row = SketchSheet(sheet, project.uuid, "plan", "top", "in", 16)
        val width = SketchDimension("d1", project.uuid, "width", "length", Rational(32), null, "user")
        val bundle = ProjectBundle(project, listOf(row), emptyList(), listOf(width))
        @OptIn(DirectRepositoryWrite::class)
        repos.projects.replaceProjectContents(project.uuid, bundle)
        val store = newStore(SidecarFs())
        return Fixture(repos, store, ProjectTechnicalData(repos, store, SidecarSlugLock(), null, slug, project.uuid, sheet), bundle)
    }

    @Test
    fun saving_should_WriteTheEditedDimensionToTheFilesAndTheDatabase_AndMoveTheSyncBase() = runBlocking<Unit> {
        val f = fixture()
        val loaded = assertNotNull(f.source.load())
        val edited = (TechnicalData.withDimensionValue(loaded, "width", Rational(48)) as arrow.core.Either.Right).value

        assertTrue(f.source.save(edited))

        assertEquals(Rational(48), assertNotNull(f.source.load()).dimensions.single().value)
        val read = assertIs<SidecarRead.Present>(f.store.read(slug))
        assertEquals(Rational(48), SidecarCodec.decode(read.files).getOrNull()!!.dimensions.single().value)
        val project = f.repos.projects.loadProject(f.bundle.project.uuid).getOrNull()!!.project
        assertEquals(SidecarCodec.bundleHash(read.files), project.lastSyncedSidecarHash)
    }

    @Test
    fun saving_should_RefuseWhenTheSidecarMovedPastTheLastSync_AndChangeNothing() = runBlocking<Unit> {
        val f = fixture()
        f.store.write(slug, SidecarCodec.encode(f.bundle))
        @OptIn(DirectRepositoryWrite::class)
        f.repos.projects.saveProject(f.bundle.project.copy(lastSyncedSidecarHash = "not-the-disk-hash"))
        val loaded = assertNotNull(f.source.load())
        val edited = (TechnicalData.withDimensionValue(loaded, "width", Rational(48)) as arrow.core.Either.Right).value

        assertFalse(f.source.save(edited))

        assertEquals(Rational(32), assertNotNull(f.source.load()).dimensions.single().value)
    }

    @Test
    fun savingAnUnchangedBundle_should_SucceedWithoutWritingFiles() = runBlocking<Unit> {
        val f = fixture()
        val loaded = assertNotNull(f.source.load())

        assertTrue(f.source.save(loaded))

        assertEquals(SidecarRead.Absent, f.store.read(slug))
    }
}
