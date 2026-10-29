@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 4.1: the backfill is batched, streaming, idempotent and cancellable. */
class AssetIndexBackfillBoundedTest {
    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val notes = EffectiveNotesPath("/graph")

    /** FakeFileSystem with real subdirectories, counting reads and directory listings. */
    private class TreeFileSystem : FakeFileSystem() {
        val paths = mutableSetOf<String>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val listings = java.util.concurrent.atomic.AtomicInteger()
        val sizes = mutableMapOf<String, Long>()

        fun put(path: String, bytes: ByteArray) { paths += path; writeFileBytes(path, bytes) }

        override fun listFiles(path: String): List<String> {
            listings.incrementAndGet()
            return super.listFiles(path)
        }

        override fun listDirectories(path: String): List<String> {
            val prefix = "$path/"
            return paths.filter { it.startsWith(prefix) && it.removePrefix(prefix).contains('/') }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        }

        override fun getFileSize(path: String): Long? = sizes[path] ?: super.readFileBytes(path)?.size?.toLong()

        override fun readFileBytes(path: String): ByteArray? {
            reads.incrementAndGet()
            return super.readFileBytes(path)
        }
    }

    private class BatchRecordingRepository(private val delegate: InMemoryAssetRepository) : AssetRepository by delegate {
        val lookupSizes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        override suspend fun getIndexedFilePaths(filePaths: List<String>) =
            delegate.getIndexedFilePaths(filePaths).also { lookupSizes += filePaths.size }
    }

    private fun threeThousandFiles(fs: TreeFileSystem) {
        repeat(3_000) { fs.put("/graph/assets/d${it % 30}/f$it.png", pngMagic) }
    }

    private suspend fun awaitCount(repo: AssetRepository, expected: Long) = withTimeout(30_000) {
        while (repo.countAssets().getOrNull() != expected) delay(20)
    }

    @Test
    fun backfill_indexesThreeThousandFilesInBatchesOfFifty_andFinishesWithProgress() = runBlocking {
        val inner = InMemoryAssetRepository()
        val repo = BatchRecordingRepository(inner)
        val fs = TreeFileSystem().also(::threeThousandFiles)
        val service = AssetIndexService(repo)

        service.startBackfill(notes, writeActor = null, fileSystem = fs)
        awaitCount(inner, 3_000)
        withTimeout(10_000) { while (service.progress.value !is AssetIndexProgress.Finished) delay(10) }

        assertEquals(AssetIndexProgress.Finished(3_000), service.progress.value)
        assertEquals(60, repo.lookupSizes.size, "3,000 files / 50 per batch")
        assertTrue(repo.lookupSizes.all { it <= AssetIndexService.BACKFILL_BATCH_SIZE }, "batches: ${repo.lookupSizes}")
        val rel = inner.getAssets(10, 0).first().getOrNull()!!.first().relativePath
        assertTrue(Regex("""\.\./assets/d\d+/f\d+\.png""").matches(rel), "relative path mirrors the real location: $rel")
        service.close()
    }

    @Test
    fun walk_isLazy_listsOnlyTheDirectoriesNeededForTheFirstPath() {
        val fs = TreeFileSystem().also(::threeThousandFiles)

        val first = AssetIndexService.walkFiles("/graph/assets", fs).first()

        assertNotNull(first)
        // The root holds no files itself, so the first path costs the root plus one subdirectory;
        // an eager walk would have listed all 31 directories.
        assertTrue(fs.listings.get() <= 2, "taking the first path listed ${fs.listings.get()} directories")
    }

    @Test
    fun secondBackfill_readsNoFileContents_becausePathsAreAlreadyIndexed() = runBlocking {
        val inner = InMemoryAssetRepository()
        val fs = TreeFileSystem()
        repeat(120) { fs.put("/graph/assets/images/p$it.png", pngMagic) }
        val first = AssetIndexService(inner)
        first.startBackfill(notes, null, fs)
        awaitCount(inner, 120)
        withTimeout(10_000) { while (first.progress.value !is AssetIndexProgress.Finished) delay(10) }
        first.close()
        val readsAfterFirst = fs.reads.get()

        val second = AssetIndexService(inner)
        second.startBackfill(notes, null, fs)
        withTimeout(10_000) { while (second.progress.value !is AssetIndexProgress.Finished) delay(10) }

        assertEquals(readsAfterFirst, fs.reads.get(), "re-run must not read files that already have a row")
        assertEquals(120L, inner.countAssets().getOrNull())
        second.close()
    }

    @Test
    fun filesAboveTheSizeCap_areIndexedByNameAndSize_withoutReadingContent() = runBlocking {
        val inner = InMemoryAssetRepository()
        val fs = TreeFileSystem()
        fs.put("/graph/assets/video/big.mp4", byteArrayOf(1, 2, 3))
        fs.sizes["/graph/assets/video/big.mp4"] = AssetIndexService.MAX_HASHED_FILE_BYTES + 1
        val service = AssetIndexService(inner)

        service.startBackfill(notes, null, fs)
        awaitCount(inner, 1)

        val row = inner.getAssets(1, 0).first().getOrNull()!!.single()
        assertEquals(0, fs.reads.get(), "an oversized file must not be read into memory")
        assertEquals(AssetIndexService.MAX_HASHED_FILE_BYTES + 1, row.sizeBytes)
        assertNull(row.contentHash)
        assertEquals(AssetMediaType.VIDEO, row.mediaType)
        service.close()
    }

    @Test
    fun stopBackfill_cancelsTheRun_andProgressReturnsToIdle() = runBlocking {
        val inner = InMemoryAssetRepository()
        val fs = TreeFileSystem().also(::threeThousandFiles)
        val service = AssetIndexService(inner)

        service.startBackfill(notes, null, fs)
        withTimeout(10_000) { while ((inner.countAssets().getOrNull() ?: 0L) == 0L) delay(1) }
        service.stopBackfill()
        delay(200)
        val settled = inner.countAssets().getOrNull()
        delay(200)

        assertEquals(settled, inner.countAssets().getOrNull(), "no rows are added after stop")
        assertTrue(settled!! in 0L until 3_000L || service.progress.value is AssetIndexProgress.Finished)
        service.close()
    }

    @Test
    fun registerAsset_isIdempotentOnContentHash() = runBlocking {
        val repo = InMemoryAssetRepository()
        val fs = TreeFileSystem()
        fs.put("/graph/assets/photo.png", pngMagic)
        fs.put("/graph/assets/photo-1.png", pngMagic)
        val service = AssetIndexService(repo)

        val a = service.registerAsset("/graph/assets/photo.png", notes, writeActor = null, fileSystem = fs).getOrNull()!!
        val b = service.registerAsset("/graph/assets/photo-1.png", notes, writeActor = null, fileSystem = fs).getOrNull()!!

        assertEquals(1L, repo.countAssets().getOrNull())
        assertEquals(a.uuid, b.uuid)
        assertEquals("../assets/photo.png", a.relativePath)
        assertNotNull(a.contentHash)
        service.close()
    }
}
