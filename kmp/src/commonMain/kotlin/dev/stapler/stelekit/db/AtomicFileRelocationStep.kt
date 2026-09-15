// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem

/**
 * One rename within an [AtomicFileRelocationStep.relocate] call. [optional] marks a sidecar-style
 * move (e.g. a WAL/SHM file) whose absence at [from] is not a failure — SQLite only writes those
 * sidecars when a WAL checkpoint hasn't run yet, so a missing one is not evidence of a problem.
 */
data class FileMove(val from: String, val to: String, val optional: Boolean = false)

/**
 * Renames [FileMove]s in order, rolling back every already-applied rename (in reverse order) the
 * instant a later one fails, so a partial relocate never strands a file at a path nothing
 * references. Extracted byte-for-byte from `GraphManager.moveGraphFilesAndCredentials`'s DB/WAL/SHM
 * move (Story 3.1.4, Tech Debt Disposition "Isolate via seam") so `GraphRelocationCoordinator`'s
 * markdown-repoint step (Story 3.1.5) can reuse the same correctness-critical algorithm without
 * growing that 40-commit-churn file further.
 */
object AtomicFileRelocationStep {
    private val logger = Logger("AtomicFileRelocationStep")

    fun relocate(fileSystem: FileSystem, files: List<FileMove>): Either<DomainError.StorageError, Unit> {
        val applied = mutableListOf<FileMove>()
        for (move in files) {
            val skippedAsAbsentOptional = move.optional && !fileSystem.fileExists(move.from)
            val succeeded = skippedAsAbsentOptional || fileSystem.renameFile(move.from, move.to)
            if (!succeeded) {
                // Best-effort rollback: a rename failing here means a file is left stranded at
                // `done.to` with nothing referencing it there — log which ones so a partial
                // recovery state (some files back at their original path, some not) is at least
                // diagnosable instead of silently swallowed.
                for (done in applied.asReversed()) {
                    if (!fileSystem.renameFile(done.to, done.from)) {
                        logger.error(
                            "relocate: rollback rename failed after ${move.from} -> ${move.to} failed — " +
                                "${done.to} could not be moved back to ${done.from}, file may be stranded",
                        )
                    }
                }
                return DomainError.StorageError.RelocationFailed(move.from).left()
            }
            if (!skippedAsAbsentOptional) applied.add(move)
        }
        return Unit.right()
    }
}
