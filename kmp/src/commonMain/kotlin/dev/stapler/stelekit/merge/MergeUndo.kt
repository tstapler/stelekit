// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.flatMap
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId

enum class UndoUnavailableReason { NotFound, Expired }

/** Something undo could not (or chose not to) revert for one page. */
sealed interface UndoIssue {
    val pageName: String

    /** The created file was edited after the copy; it stays. */
    data class FileChangedSinceCopy(override val pageName: String, val path: String) : UndoIssue

    /** [uuids] were edited after the copy; they stay. */
    data class BlocksChangedSinceCopy(override val pageName: String, val uuids: Set<String>) : UndoIssue

    data class Failed(override val pageName: String, val error: DomainError) : UndoIssue
}

sealed interface UndoResult {
    data class Unavailable(val reason: UndoUnavailableReason) : UndoResult

    data class Done(
        val filesDeleted: Int,
        val blocksRemoved: Int,
        val issues: List<UndoIssue>,
    ) : UndoResult {
        /** A repeat run: nothing reverted and nothing blocking. */
        val nothingLeft: Boolean get() = filesDeleted == 0 && blocksRemoved == 0 && issues.isEmpty()
    }
}

/**
 * Reverts one merge run from its manifest (ADR-003): deletes created files and removes added
 * blocks only while they still hash as they did at copy time; pre-existing content is never touched.
 * Idempotent: the manifest is kept, so a second run finds nothing left to revert.
 */
class MergeUndo(
    private val manifests: MergeManifestStore,
    private val writerFor: (GraphId) -> TargetWriter,
    private val nowEpochMs: () -> Long,
) {
    suspend fun undo(mergeId: MergeId): UndoResult {
        val manifest = manifests.load(mergeId) ?: return UndoResult.Unavailable(UndoUnavailableReason.NotFound)
        if (nowEpochMs() - manifest.startedAtEpochMs > MERGE_UNDO_WINDOW_MILLIS) {
            return UndoResult.Unavailable(UndoUnavailableReason.Expired)
        }
        val writer = writerFor(GraphId(manifest.targetGraphId))
        var filesDeleted = 0
        var blocksRemoved = 0
        val issues = ArrayList<UndoIssue>()

        for (entry in manifest.pages) {
            val key = PageKey(entry.pageName, entry.isJournal)
            if (entry.createdFiles.isNotEmpty()) {
                val file = entry.createdFiles.first()
                when (val r = undoFile(writer, key, file)) {
                    is Either.Right -> when (r.value) {
                        true -> filesDeleted++
                        false -> issues.add(UndoIssue.FileChangedSinceCopy(entry.pageName, file.path))
                        null -> Unit
                    }
                    is Either.Left -> issues.add(UndoIssue.Failed(entry.pageName, r.value))
                }
            }
            if (entry.addedBlockUuids.isNotEmpty()) {
                val uuids = entry.addedBlockUuids.toSet()
                when (val r = writer.removeBlocks(key, uuids, entry.addedBlockHashes)) {
                    is Either.Right -> {
                        blocksRemoved += r.value.removed.size
                        if (r.value.skippedEdited.isNotEmpty()) {
                            issues.add(UndoIssue.BlocksChangedSinceCopy(entry.pageName, r.value.skippedEdited))
                        }
                    }
                    is Either.Left -> issues.add(UndoIssue.Failed(entry.pageName, r.value))
                }
            }
        }
        return UndoResult.Done(filesDeleted, blocksRemoved, issues)
    }

    /** Right(true) deleted, Right(false) changed since copy, Right(null) already gone. */
    private suspend fun undoFile(writer: TargetWriter, key: PageKey, file: CreatedFile): Either<DomainError, Boolean?> =
        writer.fileHash(key).flatMap { current ->
            when {
                current == null -> Either.Right(null)
                current != file.hash -> Either.Right(false)
                else -> writer.deletePageFile(key, file.hash).map { true }
            }
        }
}
