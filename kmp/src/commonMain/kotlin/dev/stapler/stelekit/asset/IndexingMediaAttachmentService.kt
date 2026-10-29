package dev.stapler.stelekit.asset

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.MediaAttachmentService

/**
 * Decorates the platform [MediaAttachmentService] so every successful attach (picker, drop,
 * bytes, clipboard) also registers the new file with the asset index. The delegate's result is
 * returned unchanged; an indexing failure never reaches the caller.
 */
class IndexingMediaAttachmentService(
    private val delegate: MediaAttachmentService,
    private val indexing: AssetIndexingController,
) : MediaAttachmentService {

    override suspend fun pickAndAttach(
        notesPath: EffectiveNotesPath,
        pageRelativePath: String,
    ): Either<DomainError, AttachmentResult>? = delegate.pickAndAttach(notesPath, pageRelativePath).also { index(it) }

    override suspend fun attachFilePath(
        filePath: String,
        notesPath: EffectiveNotesPath,
    ): Either<DomainError, AttachmentResult>? = delegate.attachFilePath(filePath, notesPath).also { index(it) }

    override suspend fun attachBytes(
        bytes: ByteArray,
        suggestedName: String,
        notesPath: EffectiveNotesPath,
    ): Either<DomainError, AttachmentResult>? = delegate.attachBytes(bytes, suggestedName, notesPath).also { index(it) }

    override fun hasClipboardImage(): Boolean = delegate.hasClipboardImage()

    override suspend fun pasteFromClipboard(notesPath: EffectiveNotesPath): Either<DomainError, AttachmentResult>? =
        delegate.pasteFromClipboard(notesPath).also { index(it) }

    private suspend fun index(result: Either<DomainError, AttachmentResult>?) {
        result?.onRight { indexing.registerAttachment(it) }
    }
}
