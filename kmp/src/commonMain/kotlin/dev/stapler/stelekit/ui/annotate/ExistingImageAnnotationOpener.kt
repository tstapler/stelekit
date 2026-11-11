// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.DEFAULT_MEASUREMENT_UNIT
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.ImageAnnotationRepository
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

private const val GRAPH_ASSET_PREFIX = "../assets/"
private const val URI_SCHEME_SEPARATOR = "://"

/** An already-stored image the user wants to annotate. [sourceBlock] is the inline block showing it, when there is one. */
data class ExistingImageRequest(
    val pageUuid: String?,
    val imageRef: String,
    val tags: List<String> = emptyList(),
    val sourceBlock: Block? = null,
)

/** Why an existing image could not be opened for annotation; [message] is shown to the user as is. */
sealed interface OpenForAnnotationError {
    val message: String

    data class FileMissing(val path: String) : OpenForAnnotationError {
        override val message: String get() = "This image's file is missing, so it can't be annotated."
    }

    data object NoPage : OpenForAnnotationError {
        override val message: String get() = "Add this image to a page before annotating it."
    }

    data class WriteFailed(override val message: String) : OpenForAnnotationError
}

/** Turns an inline `../assets/x.png` reference or a path into the absolute file path an [ImageAnnotation] stores. */
fun resolveImageFilePath(graphPath: String, imageRef: String): String = when {
    imageRef.startsWith(GRAPH_ASSET_PREFIX) -> "${graphPath.trimEnd('/')}/assets/${imageRef.removePrefix(GRAPH_ASSET_PREFIX)}"
    else -> imageRef
}

/**
 * Finds or creates the [ImageAnnotation] for an image that is already in the graph, so the annotation
 * editor can open it. Reuses a row for the same file on the page (keeping its calibration and
 * measurements); otherwise creates one on the first open. Never registers an asset, so hash dedupe
 * ([dev.stapler.stelekit.db.ImageImportService]) is untouched.
 */
class ExistingImageAnnotationOpener(
    private val imageAnnotationRepository: ImageAnnotationRepository,
    private val blockRepository: BlockRepository,
    private val writeActor: DatabaseWriteActor?,
    private val graphPath: String,
    private val defaultUnit: () -> MeasurementUnit = { DEFAULT_MEASUREMENT_UNIT },
    private val fileExists: (String) -> Boolean = { true },
) {
    private val logger = Logger("ExistingImageAnnotationOpener")

    suspend fun open(request: ExistingImageRequest): Either<OpenForAnnotationError, String> {
        val pageUuid = request.pageUuid ?: return OpenForAnnotationError.NoPage.left()
        val filePath = resolveImageFilePath(graphPath, request.imageRef)
        // Remote/content URIs cannot be probed from here; only plain absolute paths are checked.
        if (filePath.startsWith("/") && !filePath.contains(URI_SCHEME_SEPARATOR) && !fileExists(filePath)) {
            return OpenForAnnotationError.FileMissing(filePath).left()
        }
        val existing = imageAnnotationRepository.getImageAnnotationsByPage(pageUuid).first()
            .getOrNull()
            ?.firstOrNull { it.filePath == filePath }
        if (existing != null) return existing.uuid.right()
        return create(request, pageUuid, filePath)
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun create(request: ExistingImageRequest, pageUuid: String, filePath: String): Either<OpenForAnnotationError, String> {
        val annotationUuid = UuidGenerator.generateV7()
        val unit = defaultUnit()
        val annotationProps = mapOf(
            "image-id" to annotationUuid,
            "calibration" to "none",
            "unit" to unit.name.lowercase(),
        )
        val now = Clock.System.now()
        val source = request.sourceBlock
        val block = if (source != null) {
            // Convert the inline block in place so the page does not gain a duplicate image.
            source.copy(blockType = BlockType.ImageAnnotation, properties = source.properties + annotationProps, updatedAt = now)
        } else {
            val lastRootPosition = blockRepository.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull().orEmpty()
                .filter { it.parentUuid == null }
                .maxByOrNull { it.position }
                ?.position
            Block(
                uuid = BlockUuid(UuidGenerator.generateV7()),
                pageUuid = PageUuid(pageUuid),
                content = "![](${request.imageRef})",
                position = FractionalIndexing.generateKeyBetween(lastRootPosition, null),
                createdAt = now,
                updatedAt = now,
                properties = annotationProps,
                blockType = BlockType.ImageAnnotation,
            )
        }
        // Block first, annotation second: a block-write failure never leaves an orphaned annotation row.
        val saved = writeActor?.saveBlock(block) ?: blockRepository.saveBlock(block)
        saved.onLeft { err ->
            logger.error("Failed to save block for image annotation: ${err.message}")
            return OpenForAnnotationError.WriteFailed(err.message).left()
        }
        val annotation = ImageAnnotation(
            uuid = annotationUuid,
            blockUuid = block.uuid.value,
            pageUuid = pageUuid,
            graphPath = graphPath,
            filePath = filePath,
            tags = request.tags,
            unit = unit,
        )
        return imageAnnotationRepository.saveImageAnnotation(annotation).fold(
            ifLeft = { err ->
                logger.error("Failed to save image annotation for block ${block.uuid.value}: ${err.message}")
                OpenForAnnotationError.WriteFailed(err.message).left()
            },
            ifRight = { annotationUuid.right() },
        )
    }
}

