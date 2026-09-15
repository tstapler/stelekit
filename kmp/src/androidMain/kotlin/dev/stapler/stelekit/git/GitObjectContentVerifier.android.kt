// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.BulkCopyVerifier
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.util.ContentHasher
import java.io.File

/**
 * Content-hash verification for a git-cloned graph's `.git/objects/` tree — loose objects plus
 * the `pack` directory's `.pack`/`.idx` files — after `BulkCopyVerifier` has already relocated
 * the markdown content (Story 3.1.1 Task 3.1.1f).
 *
 * Not an object/ref-count comparison: a torn-pack-file race (`research/pitfalls.md` §3 — git
 * writes pack files via temp-name-then-atomic-rename, so a concurrent fetch/push mid-copy can
 * leave a torn or stale pack file) can produce a copy where object/ref counts still match but the
 * bytes are corrupted. `BulkCopyVerifier`'s same [ContentHasher.sha256] bar closes that gap here
 * too, in the same bounded-batch style ([BulkCopyVerifier.COPY_BATCH_SIZE]).
 *
 * **Not currently wired into `GraphRelocationCoordinator.relocate()`.** The bug this class was
 * built to guard against (Task 3.1.1f) turned out to have an earlier root cause: `.git` was never
 * copied into the destination at all (`listFilesRecursiveWithModTimes`'s shared, Android-shadow-
 * worktree-motivated root-`.git` exclusion silently carried into relocate too — see
 * `BulkCopyVerifier.copyAndVerifyPaths`'s `includeGitDirectory` doc). That fix makes
 * `BulkCopyVerifier`'s own per-file [ContentHasher.sha256] loop cover every byte under `.git`
 * (objects, packs, refs, `HEAD`, `config`, `index`, hooks — a strict superset of what this class
 * checks) on every platform, not just Android's `java.io.File`-based one. This class is kept —
 * still correct, still tested ([dev.stapler.stelekit.git.GitObjectContentVerifierTest]) — as a
 * narrower, Android-specific objects-only check available for a future call site, but wiring it in
 * as a *second* pass alongside `BulkCopyVerifier`'s now-universal verification would be redundant
 * work with no additional safety margin.
 */
class GitObjectContentVerifier {
    /** Verifies every loose object and pack file under [sourceGitDir]/objects exists, byte-identical, under [destinationGitDir]/objects. */
    suspend fun verify(sourceGitDir: File, destinationGitDir: File): Either<DomainError.StorageError, Int> {
        val sourceObjectsDir = File(sourceGitDir, "objects")
        if (!sourceObjectsDir.isDirectory) return 0.right()

        val relativePaths = looseObjectPaths(sourceObjectsDir) + packFilePaths(sourceObjectsDir)

        var verifiedCount = 0
        for (batch in relativePaths.chunked(BulkCopyVerifier.COPY_BATCH_SIZE)) {
            for (relativePath in batch) {
                when (val result = verifyOne(sourceObjectsDir, File(destinationGitDir, "objects"), relativePath)) {
                    is Either.Left -> return result
                    is Either.Right -> verifiedCount++
                }
            }
        }
        return verifiedCount.right()
    }

    private fun verifyOne(
        sourceObjectsDir: File,
        destinationObjectsDir: File,
        relativePath: String,
    ): Either<DomainError.StorageError, Unit> {
        val sourceFile = File(sourceObjectsDir, relativePath)
        val destinationFile = File(destinationObjectsDir, relativePath)
        if (!destinationFile.isFile) {
            return DomainError.StorageError.VerificationFailed(relativePath, "missing at destination").left()
        }
        val sourceHash = ContentHasher.sha256(sourceFile.readBytes())
        val destinationHash = ContentHasher.sha256(destinationFile.readBytes())
        if (sourceHash != destinationHash) {
            return DomainError.StorageError.VerificationFailed(relativePath, "content hash mismatch").left()
        }
        return Unit.right()
    }

    /** `objects/<2-hex-fanout>/<38-hex-remainder>` — git's loose-object layout. */
    private fun looseObjectPaths(objectsDir: File): List<String> {
        val fanoutDirs = objectsDir.listFiles { f -> f.isDirectory && f.name.length == 2 } ?: emptyArray()
        return fanoutDirs.flatMap { fanoutDir ->
            val objects = fanoutDir.listFiles { f -> f.isFile } ?: emptyArray()
            objects.map { "${fanoutDir.name}/${it.name}" }
        }
    }

    private fun packFilePaths(objectsDir: File): List<String> {
        val packDir = File(objectsDir, "pack")
        val packFiles = packDir.listFiles { f -> f.isFile && (f.name.endsWith(".pack") || f.name.endsWith(".idx")) }
            ?: emptyArray()
        return packFiles.map { "pack/${it.name}" }
    }
}
