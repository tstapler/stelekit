// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 0.1.1b: `backup/`, `conflicts/` and unreferenced sheet files beside a bundle. */
class SidecarBackupAndStagingTest {
    private val slug = "kitchen"
    private val sheets = mapOf("sheets/s1.json" to OldReaderFixtures.s1)
    private val bundle = sheets + (SidecarCodec.MODEL_PATH to OldReaderFixtures.model(listOf("s1" to null), sheets))

    private fun p(rel: String) = sidecarPath(slug, rel)

    @Test
    fun backup_should_NotChangePresentFilesOrBundleHash_AndSurviveLaterWrites() = runBlocking<Unit> {
        val plainFs = SidecarFs()
        val plain = newStore(plainFs).also { it.write(slug, bundle) }
        val backupFs = SidecarFs()
        val withBackup = newStore(backupFs).also { it.write(slug, bundle) }

        val path = assertIs<Either.Right<String>>(withBackup.writeBackup(slug, "s1", OldReaderFixtures.s1)).value
        withBackup.write(slug, bundle)

        assertEquals(p("backup/s1.v1.json"), path)
        assertContentEquals(OldReaderFixtures.s1, backupFs.readFileBytes(path))
        val a = assertIs<SidecarRead.Present>(plain.read(slug)).files
        val b = assertIs<SidecarRead.Present>(withBackup.read(slug)).files
        assertEquals(a.keys, b.keys)
        assertEquals(SidecarCodec.bundleHash(a), SidecarCodec.bundleHash(b))
        assertEquals(emptyList(), backupFs.tmps())
    }

    @Test
    fun backup_should_NeverOverwriteTheOriginal() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.writeBackup(slug, "s1", OldReaderFixtures.s1)
        assertIs<Either.Right<String>>(store.writeBackup(slug, "s1", OldReaderFixtures.s3))
        assertContentEquals(OldReaderFixtures.s1, fs.readFileBytes(p("backup/s1.v1.json")))
    }

    @Test
    fun unreferencedSheets_should_NeverBeOpened_AndSweepDeletesOnlySchema1() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.write(slug, bundle)
        fs.seed(p("sheets/s1.v2.json"), OldReaderFixtures.s2)
        fs.seed(p("sheets/new.json"), OldReaderFixtures.s3)
        fs.seed(p("sheets/garbage.json"), "not json".encodeToByteArray())
        fs.seed(p("sheets/old.json"), OldReaderFixtures.s1)

        val files = assertIs<SidecarRead.Present>(store.read(slug)).files
        assertEquals(bundle.keys, files.keys)

        store.write(slug, bundle)
        assertTrue(fs.fileExists(p("sheets/s1.v2.json")))
        assertTrue(fs.fileExists(p("sheets/new.json")))
        assertTrue(fs.fileExists(p("sheets/garbage.json")))
        assertFalse(fs.fileExists(p("sheets/old.json")))
    }

    @Test
    fun sheetConflictCopy_should_BeInvisibleToReadAndHash_AndKeepTheBytes() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.write(slug, bundle)
        val hashBefore = SidecarCodec.bundleHash(assertIs<SidecarRead.Present>(store.read(slug)).files)

        val path = assertIs<Either.Right<String>>(store.writeSheetConflictCopy(slug, "s2", OldReaderFixtures.s2)).value

        assertTrue(Regex(".*/kitchen/conflicts/s2\\.v2\\.conflict-[0-9a-f]{8}\\.json").matches(path), path)
        assertContentEquals(OldReaderFixtures.s2, fs.readFileBytes(path))
        store.write(slug, bundle)
        assertTrue(fs.fileExists(path))
        val after = assertIs<SidecarRead.Present>(store.read(slug)).files
        assertEquals(bundle.keys, after.keys)
        assertEquals(hashBefore, SidecarCodec.bundleHash(after))
    }

    @Test
    fun deleteV2Sheet_should_RemoveOnlyTheStagedV2File() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.write(slug, bundle)
        fs.seed(p("sheets/s1.v2.json"), OldReaderFixtures.s2)

        assertIs<Either.Right<Unit>>(store.deleteV2Sheet(slug, "s1"))

        assertFalse(fs.fileExists(p("sheets/s1.v2.json")))
        assertTrue(fs.fileExists(p("sheets/s1.json")))
        assertIs<Either.Left<*>>(store.deleteV2Sheet(slug, "../x"))
    }

    @Test
    fun deleteProject_should_RemoveEveryFileOfTheProject_AndLeaveOtherProjectsAlone() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.write(slug, bundle)
        store.write("garage", bundle)
        store.writeBackup(slug, "s1", OldReaderFixtures.s1)
        store.writeSheetConflictCopy(slug, "s2", OldReaderFixtures.s2)
        fs.seed(p("sheets/s1.v2.json"), OldReaderFixtures.s2)
        fs.seed(p("preview.svg"), OldReaderFixtures.s1)

        val deleted = assertIs<Either.Right<Int>>(store.deleteProject(slug)).value

        assertTrue(deleted >= 6, "model, sheet, v2 sheet, backup, conflict copy and preview went: $deleted")
        for (rel in listOf(SidecarCodec.MODEL_PATH, "sheets/s1.json", "sheets/s1.v2.json", "backup/s1.v1.json", "preview.svg")) assertFalse(fs.fileExists(p(rel)), rel)
        assertEquals(SidecarRead.Absent, store.read(slug))
        assertIs<SidecarRead.Present>(store.read("garage"))
        assertIs<Either.Left<*>>(store.deleteProject("../x"))
    }

    @Test
    fun deletingASketch_should_RemoveItsFilesBeforeItsRows_AndKeepTheRowsWhenTheFilesCannotGo() = runBlocking<Unit> {
        val opaque = "proj-abc123"
        val repos = dev.stapler.stelekit.repository.inMemorySketchRepositories()
        val project = ProjectFactory.create("Sketch", opaque, ProjectFactory.newUuid(), 1L)
        @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
        repos.projects.saveProject(project)
        val fs = SidecarFs()
        val store = newStore(fs)
        store.write(opaque, bundle)
        val service = dev.stapler.stelekit.sketch.SketchDeleteService(repos.projects, { store }, { op -> op() })

        assertIs<Either.Right<Unit>>(service.delete(project.uuid.value, opaque))

        assertEquals(SidecarRead.Absent, store.read(opaque))
        assertEquals(null, repos.projects.loadProject(project.uuid).let { (it as Either.Right).value })

        @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
        repos.projects.saveProject(project)
        val refusing = object : ProjectSidecarStore by store {
            override suspend fun deleteProject(slug: String) = Either.Left(dev.stapler.stelekit.error.DomainError.FileSystemError.WriteFailed("x", "locked"))
        }
        val failing = dev.stapler.stelekit.sketch.SketchDeleteService(repos.projects, { refusing }, { op -> op() })
        assertIs<Either.Left<*>>(failing.delete(project.uuid.value, opaque))
        assertTrue((repos.projects.loadProject(project.uuid) as Either.Right).value != null, "the project stays listed when its files could not be removed")
    }
}
