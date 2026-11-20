// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.model.SheetPayload
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
import kotlin.test.assertContentEquals
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
    fun saving_should_WriteAnAddedZoneToTheFilesAndTheDatabase() = runBlocking<Unit> {
        val f = fixture()
        val loaded = assertNotNull(f.source.load())
        val edited = (TechnicalData.withZoneAdded(loaded, sheet, "front") as arrow.core.Either.Right).value

        assertTrue(f.source.save(edited))

        assertEquals(1, TechnicalData.zones(assertNotNull(f.source.load()), sheet).size)
        val read = assertIs<SidecarRead.Present>(f.store.read(slug))
        assertEquals(1, TechnicalData.zones(SidecarCodec.decode(read.files).getOrNull()!!, sheet).size)
    }

    @Test
    fun saving_should_KeepEveryV2SheetFileOfTheProject() = runBlocking<Unit> {
        val f = fixture()
        val v2 = SheetUuid("s2")
        val bytes = SheetCodecV2.encode(SketchDocumentV2(sheetUuid = "s2"), SceneLimits.Default).getOrNull()!!
        val row = SketchSheet(v2, f.bundle.project.uuid, "plan", "top", "in", 16, schemaVersion = SheetPaths.SCHEMA_V2)
        val seeded = f.bundle.copy(sheets = f.bundle.sheets + row, sheetPayloads = mapOf(v2 to SheetPayload.V2Opaque.of(bytes)))
        @OptIn(DirectRepositoryWrite::class)
        f.repos.projects.replaceProjectContents(seeded.project.uuid, seeded)
        val path = SheetPaths.pathFor(v2, SheetPaths.SCHEMA_V2)
        val opaque = mapOf(path to (seeded.sheetPayloads.getValue(v2) as SheetPayload.V2Opaque).sha256)
        val staged = f.store.writeNow(slug, mapOf(path to bytes))
        val flipped = f.store.writeNow(slug, SidecarCodec.encode(seeded) - opaque.keys, opaque)
        assertEquals(SidecarWriteOutcome.Written, staged, "staged")
        assertEquals(SidecarWriteOutcome.Written, flipped, "flipped")
        assertIs<SidecarRead.Present>(f.store.read(slug), f.store.read(slug).toString())
        val edited = (TechnicalData.withDimensionValue(assertNotNull(f.source.load()), "width", Rational(48)) as arrow.core.Either.Right).value

        assertTrue(f.source.save(edited))

        val read = assertIs<SidecarRead.Present>(f.store.read(slug))
        assertContentEquals(bytes, read.files[path], "the v2 sheet file survives a technical-data save")
    }

    @Test
    fun aStalePanel_should_NotUndoADimensionChangedMeanwhile() = runBlocking<Unit> {
        val f = fixture()
        val stale = assertNotNull(f.source.load())
        val other = ProjectTechnicalData(f.repos, f.store, SidecarSlugLock(), null, slug, f.bundle.project.uuid, sheet)
        val moved = (TechnicalData.withDimensionValue(assertNotNull(other.load()), "width", Rational(48)) as arrow.core.Either.Right).value
        assertTrue(other.save(moved))

        val zoned = (TechnicalData.withZoneAdded(stale, sheet, "front") as arrow.core.Either.Right).value
        assertTrue(f.source.save(zoned))

        val now = assertNotNull(f.source.load())
        assertEquals(Rational(48), now.dimensions.single().value, "the dimension another save changed is kept")
        assertEquals(1, TechnicalData.zones(now, sheet).size)
    }

    @Test
    fun revertingAnEdit_should_BeSaved_AfterTheFirstSave() = runBlocking<Unit> {
        val f = fixture()
        val original = assertNotNull(f.source.load())
        val changed = (TechnicalData.withDimensionValue(original, "width", Rational(48)) as arrow.core.Either.Right).value
        assertTrue(f.source.save(changed))

        assertTrue(f.source.save(original))

        assertEquals(Rational(32), assertNotNull(f.source.load()).dimensions.single().value)
    }

    @Test
    fun aCorruptSidecar_should_GiveNoBundleAndRefuseASave() = runBlocking<Unit> {
        val f = fixture()
        val loaded = assertNotNull(f.source.load())
        f.store.writeNow(slug, mapOf(SidecarCodec.MODEL_PATH to "{not json".encodeToByteArray()))
        assertIs<SidecarRead.Corrupt>(f.store.read(slug))

        assertEquals(null, f.source.load())
        assertFalse(f.source.save(loaded))
    }

    @Test
    fun savingAnUnchangedBundle_should_SucceedWithoutWritingFiles() = runBlocking<Unit> {
        val f = fixture()
        val loaded = assertNotNull(f.source.load())

        assertTrue(f.source.save(loaded))

        assertEquals(SidecarRead.Absent, f.store.read(slug))
    }
}
