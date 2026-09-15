package dev.stapler.stelekit.ui.assets

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.ImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AssetDetailViewModel.resolveOrCreateAnnotation].
 *
 * All tests use [InMemoryImageAnnotationRepository] / [InMemoryBlockRepository] /
 * [InMemoryAssetRepository] so no DB setup is needed.
 */
@OptIn(DirectRepositoryWrite::class)
class AssetDetailViewModelTest {

    private fun makeAsset(
        pageUuids: List<String> = listOf("page-001"),
        filePath: String = "/graph/assets/images/photo.jpg",
        relativePath: String = "../assets/images/photo.jpg",
    ) = AssetEntry(
        uuid = AssetUuid("asset-001"),
        filePath = filePath,
        relativePath = relativePath,
        mediaType = AssetMediaType.IMAGE,
        subfolder = "images",
        tags = listOf("tag-a"),
        autoLabels = emptyList(),
        ocrText = null,
        cloudDescription = null,
        pageUuids = pageUuids,
        sizeBytes = 1024L,
        importedAtMs = 0L,
        mlProcessed = false,
        mlAttemptedAt = null,
        mlFailed = false,
        contentHash = null,
        isOrphan = pageUuids.isEmpty(),
        mlTagsSource = "none",
    )

    private fun makeViewModel(
        imageAnnotationRepository: ImageAnnotationRepository = InMemoryImageAnnotationRepository(),
        blockRepository: InMemoryBlockRepository = InMemoryBlockRepository(),
        graphPath: String = "/graph",
    ) = AssetDetailViewModel(
        assetRepository = InMemoryAssetRepository(),
        assetUuid = AssetUuid("asset-001"),
        imageAnnotationRepository = imageAnnotationRepository,
        blockRepository = blockRepository,
        writeActor = null,
        graphPath = graphPath,
    )

    /** Always fails [saveImageAnnotation]; delegates every read to [delegate]. */
    @OptIn(DirectRepositoryWrite::class)
    private class SaveFailingImageAnnotationRepository(
        private val delegate: ImageAnnotationRepository = InMemoryImageAnnotationRepository(),
    ) : ImageAnnotationRepository by delegate {
        override suspend fun saveImageAnnotation(annotation: ImageAnnotation): Either<DomainError, Unit> =
            DomainError.DatabaseError.WriteFailed("simulated failure").left()
    }

    @Test
    fun resolveOrCreateAnnotation_reusesExistingAnnotation_whenFilePathMatches() = runTest {
        val annotationRepo = InMemoryImageAnnotationRepository()
        val existing = ImageAnnotation(
            uuid = "annotation-existing",
            blockUuid = "block-existing",
            pageUuid = "page-001",
            graphPath = "/graph",
            filePath = "/graph/assets/images/photo.jpg",
        )
        annotationRepo.saveImageAnnotation(existing)

        val blockRepo = InMemoryBlockRepository()
        val vm = makeViewModel(imageAnnotationRepository = annotationRepo, blockRepository = blockRepo)

        val result = vm.resolveOrCreateAnnotation(makeAsset())

        assertEquals("annotation-existing", result)
        // No new block should have been created for the reused annotation.
        assertTrue(blockRepo.getBlocksForPage(PageUuid("page-001")).first().getOrNull().orEmpty().isEmpty())
    }

    @Test
    fun resolveOrCreateAnnotation_createsBlockAndAnnotation_whenNoneExists() = runTest {
        val annotationRepo = InMemoryImageAnnotationRepository()
        val blockRepo = InMemoryBlockRepository()
        val vm = makeViewModel(imageAnnotationRepository = annotationRepo, blockRepository = blockRepo)

        val asset = makeAsset()
        val result = vm.resolveOrCreateAnnotation(asset)

        assertNotNull(result, "expected a new annotation uuid")

        val savedAnnotation = annotationRepo.getImageAnnotationByUuid(result).first().getOrNull()
        assertNotNull(savedAnnotation)
        assertEquals(asset.filePath, savedAnnotation.filePath)
        assertEquals("page-001", savedAnnotation.pageUuid)
        assertEquals("/graph", savedAnnotation.graphPath)

        val pageBlocks = blockRepo.getBlocksForPage(PageUuid("page-001")).first().getOrNull().orEmpty()
        assertEquals(1, pageBlocks.size)
        val block = pageBlocks.first()
        assertEquals(BlockType.ImageAnnotation, block.blockType)
        assertEquals("![](${asset.relativePath})", block.content)
        assertEquals(result, block.properties["image-id"])
        assertEquals(savedAnnotation.blockUuid, block.uuid.value)
    }

    @Test
    fun resolveOrCreateAnnotation_returnsNull_forOrphanAssetWithNoPageUuids() = runTest {
        val annotationRepo = InMemoryImageAnnotationRepository()
        val blockRepo = InMemoryBlockRepository()
        val vm = makeViewModel(imageAnnotationRepository = annotationRepo, blockRepository = blockRepo)

        val result = vm.resolveOrCreateAnnotation(makeAsset(pageUuids = emptyList()))

        assertNull(result)
        assertTrue(annotationRepo.getAllImageAnnotations().first().getOrNull().orEmpty().isEmpty())
    }

    @Test
    fun resolveOrCreateAnnotation_returnsNull_whenAnnotationSaveFails() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val annotationRepo = SaveFailingImageAnnotationRepository()
        val vm = makeViewModel(imageAnnotationRepository = annotationRepo, blockRepository = blockRepo)

        val asset = makeAsset()
        val result = vm.resolveOrCreateAnnotation(asset)

        assertNull(result)
        // The block write happens before the (failing) annotation write, so it is still present —
        // matching ImageImportService's no-rollback-on-second-step-failure convention. The
        // failure here is the annotation row, which must not exist.
        val pageBlocks = blockRepo.getBlocksForPage(PageUuid("page-001")).first().getOrNull().orEmpty()
        assertEquals(1, pageBlocks.size)
        assertTrue(annotationRepo.getAllImageAnnotations().first().getOrNull().orEmpty().isEmpty())
    }
}
