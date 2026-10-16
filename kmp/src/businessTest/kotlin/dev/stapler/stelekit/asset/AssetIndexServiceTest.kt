@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssetIndexServiceTest {

    /** Ordered "R"(ead)/"S"(ave) log shared by the fs and repo spies; written from Dispatchers.Default. */
    private val events: MutableList<Char> = java.util.Collections.synchronizedList(mutableListOf())

    private inner class CountingRepository(private val delegate: InMemoryAssetRepository) : AssetRepository by delegate {
        @Volatile var saveCalls = 0
        override suspend fun saveAsset(asset: AssetEntry) = delegate.saveAsset(asset).also { saveCalls++; events += 'S' }
    }

    private inner class ReadLoggingFileSystem : FakeFileSystem() {
        val assetsDirChecked = CompletableDeferred<Unit>()
        override fun readFileBytes(path: String): ByteArray? = super.readFileBytes(path).also { events += 'R' }
        override fun directoryExists(path: String): Boolean = super.directoryExists(path).also { assetsDirChecked.complete(Unit) }
    }

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    @Test
    fun registerAsset_detectsMimeBuildsEntryAndSaves() = runBlocking {
        val repo = InMemoryAssetRepository()
        val fs = FakeFileSystem()
        val path = "/graph/assets/images/photo.png"
        fs.writeFileBytes(path, pngMagic)

        val entry = AssetIndexService(repo)
            .registerAsset(path, "/graph", pageUuid = "page-1", writeActor = null, fileSystem = fs)
            .getOrNull()!!

        assertEquals(AssetMediaType.IMAGE, entry.mediaType)
        assertEquals("images", entry.subfolder)
        assertEquals("../assets/images/photo.png", entry.relativePath)
        assertEquals(pngMagic.size.toLong(), entry.sizeBytes)
        assertEquals(listOf("page-1"), entry.pageUuids)
        assertEquals(entry, repo.getAssetByUuid(entry.uuid).first().getOrNull())
    }

    @Test
    fun registerAsset_mimeHintOverridesDetection() = runBlocking {
        val repo = InMemoryAssetRepository()
        val fs = FakeFileSystem()
        fs.writeFileBytes("/graph/assets/files/blob.bin", byteArrayOf(1, 2, 3))

        val entry = AssetIndexService(repo)
            .registerAsset("/graph/assets/files/blob.bin", "/graph", mimeHint = "application/pdf", writeActor = null, fileSystem = fs)
            .getOrNull()!!

        assertEquals(AssetMediaType.PDF, entry.mediaType)
        assertEquals("pdfs", entry.subfolder)
    }

    @Test
    fun startBackfill_noAssetsDir_makesNoRepositoryCalls() = runBlocking {
        val repo = CountingRepository(InMemoryAssetRepository())
        val fs = ReadLoggingFileSystem()

        AssetIndexService(repo).startBackfill("/graph", writeActor = null, fileSystem = fs)

        // fire-and-forget on Dispatchers.Default: wait until the job has hit the missing-dir early return
        withTimeout(5_000) { fs.assetsDirChecked.await() }
        delay(50)
        assertEquals(0, repo.saveCalls)
        assertEquals(0L, repo.countAssets().getOrNull())
        assertTrue(events.isEmpty())
    }

    @Test
    fun startBackfill_indexesEveryFileInBatchesOfAtMostBatchSize() = runBlocking {
        val inner = InMemoryAssetRepository()
        val repo = CountingRepository(inner)
        val fs = ReadLoggingFileSystem()
        // FakeFileSystem.listDirectories is empty, so files sit directly under assets/
        val total = AssetIndexService.BACKFILL_BATCH_SIZE * 2 + 20
        repeat(total) { fs.writeFileBytes("/graph/assets/f$it.png", pngMagic) }

        AssetIndexService(repo).startBackfill("/graph", writeActor = null, fileSystem = fs)

        withTimeout(10_000) {
            while (repo.countAssets().getOrNull() != total.toLong()) delay(20)
        }
        assertTrue(inner.getAssets(limit = total + 1, offset = 0).first().getOrNull()!!.all { it.mediaType == AssetMediaType.IMAGE })

        // processBatch reads every file of a batch before saving any, so reads form one run per batch
        val log = synchronized(events) { events.joinToString("") }
        val readRuns = Regex("R+").findAll(log).map { it.value.length }.toList()
        assertEquals(listOf(50, 50, 20), readRuns, "reads per batch in event log: $log")
    }
}
