package dev.stapler.stelekit.ui.assets

import androidx.compose.runtime.RememberObserver
import arrow.core.Either
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.ImageAnnotationRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class AssetDetailUiState(
    val asset: AssetEntry? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class AssetDetailViewModel(
    private val assetRepository: AssetRepository,
    private val assetUuid: AssetUuid,
    private val imageAnnotationRepository: ImageAnnotationRepository = InMemoryImageAnnotationRepository(),
    private val blockRepository: BlockRepository = InMemoryBlockRepository(),
    private val writeActor: DatabaseWriteActor? = null,
    private val graphPath: String = "",
) : RememberObserver {
    private val logger = Logger("AssetDetailViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error("AssetDetailViewModel uncaught: ${throwable.message}")
            }
    )
    private var loadJob: Job? = null

    private val _uiState = MutableStateFlow(AssetDetailUiState())
    val uiState: StateFlow<AssetDetailUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.value = AssetDetailUiState(isLoading = true)
            try {
                assetRepository.getAssetByUuid(assetUuid).collect { result ->
                    result.fold(
                        ifLeft = { err ->
                            _uiState.value = AssetDetailUiState(isLoading = false, error = err.message)
                        },
                        ifRight = { asset ->
                            _uiState.value = AssetDetailUiState(asset = asset, isLoading = false)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.value = AssetDetailUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Resolves the [ImageAnnotation] uuid to open the annotation editor with for [asset]:
     * reuses an existing annotation row matching [AssetEntry.filePath] on the asset's own page
     * if one exists (e.g. the asset was already imported via the measurement flow), otherwise
     * creates a new annotation backed by a fresh `image_annotation` [Block].
     *
     * Returns null (and creates nothing) for assets with no page association — annotation rows
     * require a real [ImageAnnotation.pageUuid]; `AssetDetailScreen` already hides the
     * "Annotate" action for orphan assets, so this is a defensive no-op. Also returns null (and
     * logs) if the underlying block or annotation write fails.
     *
     * Write order is block-then-annotation (mirrors [dev.stapler.stelekit.db.ImageImportService]'s
     * shape for the created [Block]) so a block-write failure never leaves an orphaned
     * [ImageAnnotation] row referencing a block that doesn't exist.
     */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun resolveOrCreateAnnotation(asset: AssetEntry): String? {
        val pageUuid = asset.pageUuids.firstOrNull() ?: return null

        val existing = imageAnnotationRepository.getImageAnnotationsByPage(pageUuid).first()
            .getOrNull()
            ?.firstOrNull { it.filePath == asset.filePath }
        if (existing != null) return existing.uuid

        val pageBlocks = blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull().orEmpty()
        val lastRootPosition = pageBlocks
            .filter { it.parentUuid == null }
            .maxByOrNull { it.position }
            ?.position
        val position = FractionalIndexing.generateKeyBetween(lastRootPosition, null)

        val annotationUuid = UuidGenerator.generateV7()
        val blockUuid = UuidGenerator.generateV7()
        val now = Clock.System.now()
        val block = Block(
            uuid = BlockUuid(blockUuid),
            pageUuid = PageUuid(pageUuid),
            content = "![](${asset.relativePath})",
            position = position,
            createdAt = now,
            updatedAt = now,
            properties = mapOf(
                "image-id" to annotationUuid,
                "calibration" to "none",
                "unit" to MeasurementUnit.METERS.name.lowercase(),
            ),
            blockType = BlockType.ImageAnnotation,
        )

        saveBlock(block).fold(
            ifLeft = { err ->
                logger.error("Failed to create block for new image annotation: ${err.message}")
                return null
            },
            ifRight = { /* continue */ },
        )

        val annotation = ImageAnnotation(
            uuid = annotationUuid,
            blockUuid = blockUuid,
            pageUuid = pageUuid,
            graphPath = graphPath,
            filePath = asset.filePath,
            tags = asset.tags,
        )
        return imageAnnotationRepository.saveImageAnnotation(annotation).fold(
            ifLeft = { err ->
                // Block already exists on the page (visible as a plain image); the annotation
                // row is what failed, so there is nothing left to roll back.
                logger.error("Failed to save image annotation for block $blockUuid: ${err.message}")
                null
            },
            ifRight = { annotationUuid },
        )
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun saveBlock(block: Block): Either<DomainError, Unit> {
        val actor = writeActor
        return if (actor != null) actor.saveBlock(block) else blockRepository.saveBlock(block)
    }

    override fun onRemembered() {}
    override fun onForgotten() { scope.cancel() }
    override fun onAbandoned() { scope.cancel() }
}
