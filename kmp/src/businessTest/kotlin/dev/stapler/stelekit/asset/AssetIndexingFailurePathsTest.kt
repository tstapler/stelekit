@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.wiring.FakeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Failure branches of the indexing entry points: failures are contained, cancellation is not. */
class AssetIndexingFailurePathsTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val notes = EffectiveNotesPath("/graph")

    private class ThrowingRepository(
        private val delegate: InMemoryAssetRepository,
        private val failure: () -> Throwable,
    ) : AssetRepository by delegate {
        override suspend fun findUuidByContentHash(contentHash: String): arrow.core.Either<DomainError, AssetUuid?> =
            throw failure()

        override suspend fun getIndexedFilePaths(filePaths: List<String>): arrow.core.Either<DomainError, Set<String>> =
            throw failure()
    }

    private fun fsWithAsset() = FakeFileSystem().apply { writeFileBytes("/graph/assets/a0.png", png) }

    private fun activeController(repo: AssetRepository): AssetIndexingController {
        val settings = FakeSettings().apply { putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true) }
        return AssetIndexingController(settings, AssetIndexService(repo), toggleVisibleProvider = { true })
    }

    @Test
    fun settingsReadCancellation_isRethrown_notTreatedAsOff() {
        val cancelling = object : Settings by FakeSettings() {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            AssetIndexingController(cancelling, AssetIndexService(InMemoryAssetRepository()), toggleVisibleProvider = { true })
        }
    }

    @Test
    fun registerAttachment_swallowsIndexFailure() = runBlocking {
        val repo = InMemoryAssetRepository()
        val c = activeController(ThrowingRepository(repo) { IllegalStateException("db down") })
        c.onGraphReady(notes, writeActor = null, fileSystem = fsWithAsset())

        c.registerAttachment(AttachmentResult("../assets/a0.png", "a0.png"))

        assertEquals(0L, repo.countAssets().getOrNull(), "failed registration indexes nothing")
        c.close()
    }

    @Test
    fun registerAttachment_rethrowsCancellation() = runBlocking {
        val c = activeController(ThrowingRepository(InMemoryAssetRepository()) { CancellationException("cancelled") })
        c.onGraphReady(notes, writeActor = null, fileSystem = fsWithAsset())

        assertFailsWith<CancellationException> {
            c.registerAttachment(AttachmentResult("../assets/a0.png", "a0.png"))
        }
        c.close()
    }

    @Test
    fun registerAsset_outsideNotesDir_usesResolvedSubfolderPath() = runBlocking {
        val repo = InMemoryAssetRepository()
        val fs = FakeFileSystem().apply { writeFileBytes("/elsewhere/pic.png", png) }

        val entry = AssetIndexService(repo)
            .registerAsset("/elsewhere/pic.png", notes, writeActor = null, fileSystem = fs)
            .getOrNull()

        assertTrue(entry != null)
        assertEquals(AssetStoragePathResolver.relativeMarkdownPath(entry.subfolder, "pic.png"), entry.relativePath)
    }

    @Test
    fun backfillFailure_isContained_and_progressReturnsToIdle() = runBlocking {
        val service = AssetIndexService(ThrowingRepository(InMemoryAssetRepository()) { IllegalStateException("db down") })

        service.startBackfill(notes, writeActor = null, fileSystem = fsWithAsset())

        withTimeout(10_000) { service.progress.first { it is AssetIndexProgress.Idle } }
        service.close()
    }
}
