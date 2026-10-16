@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetMoveServiceTest {

    private class RecordingFileSystem : FakeFileSystem() {
        var renameCalls = 0
        var createDirCalls = 0
        override fun renameFile(from: String, to: String): Boolean { renameCalls++; return super.renameFile(from, to) }
        override fun createDirectory(path: String): Boolean { createDirCalls++; return super.createDirectory(path) }
    }

    private val oldPath = "/graph/assets/images/doc.pdf"
    private val newPath = "/graph/assets/pdfs/doc.pdf"

    private fun fixture(): Triple<RecordingFileSystem, InMemoryAssetRepository, AssetMoveService> {
        val fs = RecordingFileSystem()
        val repo = InMemoryAssetRepository()
        return Triple(fs, repo, AssetMoveService(repo, restrictedQueries = null))
    }

    private suspend fun move(
        svc: AssetMoveService,
        fs: RecordingFileSystem,
        asset: AssetEntry,
        subfolder: String,
    ) = svc.moveAsset(asset, subfolder, "/graph", fs, GraphWriter(fileSystem = fs), writeActor = null)

    @Test
    fun moveAsset_happyPath_renamesFileAndUpdatesDb() = runBlocking {
        val (fs, repo, svc) = fixture()
        val asset = testAsset("a1", filePath = oldPath, mediaType = AssetMediaType.PDF)
        fs.writeFileBytes(oldPath, byteArrayOf(1, 2, 3))
        repo.saveAsset(asset)

        val result = move(svc, fs, asset, "pdfs")

        assertTrue(result.isRight())
        assertTrue(fs.fileExists(newPath))
        assertFalse(fs.fileExists(oldPath))
        val saved = repo.getAssetByUuid(asset.uuid).first().getOrNull()
        assertEquals(newPath, saved?.filePath)
        assertEquals("../assets/pdfs/doc.pdf", saved?.relativePath)
    }

    @Test
    fun moveAsset_renameFails_returnsErrorAndLeavesDbUntouched() = runBlocking {
        val (fs, repo, svc) = fixture()
        val asset = testAsset("a2", filePath = oldPath, mediaType = AssetMediaType.PDF)
        // file deliberately absent from the filesystem -> renameFile returns false
        repo.saveAsset(asset)

        val result = move(svc, fs, asset, "pdfs")

        assertTrue(result.isLeft())
        assertEquals(oldPath, repo.getAssetByUuid(asset.uuid).first().getOrNull()?.filePath)
    }

    @Test
    fun moveAsset_samePath_isNoOp() = runBlocking {
        val (fs, repo, svc) = fixture()
        val asset = testAsset("a3", filePath = oldPath)
        fs.writeFileBytes(oldPath, byteArrayOf(1))
        repo.saveAsset(asset)

        val result = move(svc, fs, asset, "images")

        assertTrue(result.isRight())
        assertEquals(0, fs.renameCalls)
        assertEquals(0, fs.createDirCalls)
        assertEquals(oldPath, repo.getAssetByUuid(asset.uuid).first().getOrNull()?.filePath)
    }

    @Test
    fun moveAsset_invalidSubfolder_throws() {
        val (fs, repo, svc) = fixture()
        val asset = testAsset("a4", filePath = oldPath)
        runBlocking { repo.saveAsset(asset) }

        assertFailsWith<IllegalArgumentException> {
            runBlocking { move(svc, fs, asset, "../etc") }
        }
        assertEquals(0, fs.renameCalls)
    }

    @Test
    fun fakeFileSystem_renameFile_movesContentAndReportsMissingSource() {
        val fs = FakeFileSystem()
        fs.writeFile("/a.txt", "text")
        fs.writeFileBytes("/b.bin", byteArrayOf(1, 2))

        assertTrue(fs.renameFile("/a.txt", "/c.txt"))
        assertFalse(fs.fileExists("/a.txt"))
        assertEquals("text", fs.readFile("/c.txt"))
        assertTrue(fs.renameFile("/b.bin", "/d.bin"))
        assertFalse(fs.fileExists("/b.bin"))
        assertEquals(listOf<Byte>(1, 2), fs.readFileBytes("/d.bin")?.toList())
        assertFalse(fs.renameFile("/missing", "/x"))
        assertFalse(fs.fileExists("/x"))
    }
}
