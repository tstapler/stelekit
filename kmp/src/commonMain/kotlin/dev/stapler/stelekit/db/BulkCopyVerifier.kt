// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.FileUtils

/**
 * Outcome of a successful [BulkCopyVerifier.copyAndVerify] run. Every path in [verifiedPaths] was
 * independently re-read from the destination and content-hash-matched against the source — this
 * is not a count carried over from the copy loop, it is the actual verified set (Story 3.1.1's
 * "no further files are marked verified past [a mismatch] without being independently checked").
 */
data class CopyReport(
    val filesCopied: Int,
    val bytesCopied: Long,
    val verifiedPaths: List<String>,
)

/**
 * Best-effort pre-flight check: does the destination volume have enough free space for a bulk
 * copy? A plain (non-`expect`) port, mirroring [StorageLocationResolver]'s precedent — only
 * Android ([AndroidInsufficientSpaceCheck], `StatFs`) and Web ([WasmJsInsufficientSpaceCheck],
 * `navigator.storage.estimate()`) have a meaningful check today (Task 3.1.1d); Desktop/iOS have
 * no cheap free-space API wired yet and are not required to provide one just to satisfy an
 * `expect` declaration.
 */
fun interface InsufficientSpaceCheck {
    /**
     * Returns [DomainError.StorageError.InsufficientSpace] when [destinationRootPath]'s volume
     * has fewer than [requiredBytes] free, or null when there's enough room, or when this
     * platform can't cheaply determine free space (the default [NONE] always returns null).
     */
    suspend fun check(destinationRootPath: String, requiredBytes: Long): DomainError.StorageError.InsufficientSpace?

    companion object {
        /** No-op default: always proceeds. Desktop/iOS/tests use this until a real check exists. */
        val NONE = InsufficientSpaceCheck { _, _ -> null }
    }
}

/**
 * Chunked recursive copy-then-verify primitive for relocating a graph's content between two
 * [StorageLocation]s (Story 3.1.1, `research/pitfalls.md`'s torn-write rationale). Walks the
 * source tree, copies each file, then re-reads the destination and compares
 * [ContentHasher.sha256] against the source before marking that file verified — mtime/size
 * shortcuts (as used by steady-state sync) are never sufficient here, since the whole point is
 * catching the encoding/path bugs already shipped once in this codebase (`e087959cb1`).
 *
 * Deliberately built on generic [FileSystem] path strings rather than resolving
 * [StorageLocation.AppOwned]/[StorageLocation.HostFolder] to a root path itself — those two kinds
 * have no path derivable from their own fields alone (an app-owned root is an opaque path
 * allocated once via `FileSystem.newAppOwnedGraphPath()` and tracked by `GraphManager`, not
 * recomputable from `graphId`; a Web host folder is an opaque `FileSystemDirectoryHandle`, not a
 * string). Resolving those two is the calling coordinator's job once it exists (Epic 3.2/3.3's
 * per-platform relocate wiring, per this epic's own framing in plan.md); [StorageLocation.DirectAccessFolder]
 * and [StorageLocation.SafFolder] already carry a path directly ([StorageLocation.DirectAccessFolder.realPath],
 * and the `saf://` convention `PlatformFileSystem`'s generic path-based API already understands),
 * so this class resolves those two itself.
 */
class BulkCopyVerifier(
    private val fileSystem: FileSystem,
    private val spaceCheck: InsufficientSpaceCheck = InsufficientSpaceCheck.NONE,
) {
    /**
     * Copies every file under [source] into [destination] and verifies each one by content hash.
     * Returns as soon as the first file fails verification — everything reported in
     * [CopyReport.verifiedPaths] on success was independently checked; nothing is left half-marked
     * on failure.
     */
    suspend fun copyAndVerify(
        source: StorageLocation,
        destination: StorageLocation,
        graphId: String,
    ): Either<DomainError.StorageError, CopyReport> {
        val sourceRoot = source.resolveRootPathOrNull()
            ?: return DomainError.StorageError.DestinationNotWritable(
                "Cannot resolve a filesystem root path for source location of graph $graphId: $source",
            ).left()
        val destinationRoot = destination.resolveRootPathOrNull()
            ?: return DomainError.StorageError.DestinationNotWritable(
                "Cannot resolve a filesystem root path for destination location of graph $graphId: $destination",
            ).left()
        return copyAndVerifyPaths(sourceRoot, destinationRoot)
    }

    /**
     * Path-based core of [copyAndVerify], exposed `internal` so callers that already hold a plain
     * root path — a relocation staging directory (`RelocationStagingDirectory.stagingPath`, Story
     * 3.1.2), which is always a real filesystem path and never itself wrapped in a
     * [StorageLocation] — can invoke the primitive directly without inventing a fake
     * [StorageLocation] just to satisfy [copyAndVerify]'s signature. Also what tests exercise
     * directly.
     */
    internal suspend fun copyAndVerifyPaths(
        sourceRoot: String,
        destinationRoot: String,
        onBatchProgress: (filesProcessed: Int, batchSize: Int) -> Unit = { _, _ -> },
    ): Either<DomainError.StorageError, CopyReport> {
        val relativePaths = fileSystem.listFilesRecursiveWithModTimes(sourceRoot).map { it.first }

        if (spaceCheck !== InsufficientSpaceCheck.NONE) {
            val requiredBytes = relativePaths.sumOf { fileSystem.getFileSize("$sourceRoot/$it") ?: 0L }
            spaceCheck.check(destinationRoot, requiredBytes)?.let { return it.left() }
        }

        val verified = mutableListOf<String>()
        var totalBytes = 0L
        val createdDirs = mutableSetOf<String>()
        var processed = 0

        for (batch in relativePaths.chunked(COPY_BATCH_SIZE)) {
            for (relativePath in batch) {
                when (val result = copyAndVerifyOneFile(sourceRoot, destinationRoot, relativePath, createdDirs)) {
                    is Either.Left -> return result
                    is Either.Right -> {
                        verified += relativePath
                        totalBytes += result.value
                    }
                }
            }
            processed += batch.size
            onBatchProgress(processed, batch.size)
        }

        return CopyReport(filesCopied = verified.size, bytesCopied = totalBytes, verifiedPaths = verified).right()
    }

    /** Copies and verifies one file; returns its byte count on success. */
    private suspend fun copyAndVerifyOneFile(
        sourceRoot: String,
        destinationRoot: String,
        relativePath: String,
        createdDirs: MutableSet<String>,
    ): Either<DomainError.StorageError, Int> {
        val sourcePath = "$sourceRoot/$relativePath"
        val destinationPath = "$destinationRoot/$relativePath"

        val sourceBytes = fileSystem.readFileBytes(sourcePath)
            ?: return DomainError.StorageError.VerificationFailed(relativePath, "source file unreadable").left()

        val parentDir = destinationPath.substringBeforeLast("/", missingDelimiterValue = destinationRoot)
        if (createdDirs.add(parentDir)) {
            fileSystem.createDirectory(parentDir)
        }

        if (!fileSystem.writeFileBytes(destinationPath, sourceBytes)) {
            return DomainError.StorageError.DestinationNotWritable(destinationPath).left()
        }

        if (!filenameIdentityMatches(sourcePath, destinationPath)) {
            return DomainError.StorageError.VerificationFailed(
                relativePath,
                "destination filename does not decode to the same page identity as the source",
            ).left()
        }

        val destinationBytes = fileSystem.readFileBytes(destinationPath)
            ?: return DomainError.StorageError.VerificationFailed(relativePath, "destination unreadable after write").left()

        if (ContentHasher.sha256(sourceBytes) != ContentHasher.sha256(destinationBytes)) {
            return DomainError.StorageError.VerificationFailed(relativePath, "content hash mismatch").left()
        }

        return sourceBytes.size.right()
    }

    companion object {
        /** Mirrors `GraphLoader.indexRemainingPages`'s `INDEX_BATCH_SIZE = 100` bounded-drain precedent. */
        const val COPY_BATCH_SIZE = 100
    }
}

/**
 * Page-identity check using this codebase's own sanctioned filename helpers ([FileUtils.decodeFileName])
 * rather than a bespoke transform — the `e087959cb1` regression class this story exists to not
 * repeat. Compares the *decoded* basenames rather than the raw (possibly percent-encoded)
 * strings, so a future destination-naming step that re-derives a name independently (rather than
 * reusing the literal source relative path, as this implementation does today) is still caught if
 * it diverges from the source's page identity.
 */
private fun filenameIdentityMatches(sourcePath: String, destinationPath: String): Boolean {
    val sourceName = sourcePath.substringAfterLast("/")
    val destinationName = destinationPath.substringAfterLast("/")
    return FileUtils.decodeFileName(sourceName) == FileUtils.decodeFileName(destinationName)
}

/**
 * `internal` (not `private`) so `GraphRelocationCoordinator` (Story 3.1.5) can resolve the same
 * two directly-resolvable [StorageLocation] kinds for its own staging-path computation without
 * duplicating this logic — see this file's class doc for why [StorageLocation.AppOwned]/
 * [StorageLocation.HostFolder] resolution is deferred to the per-platform coordinator wiring
 * (Epic 3.2/3.3) instead.
 */
internal fun StorageLocation.resolveRootPathOrNull(): String? = when (this) {
    is StorageLocation.DirectAccessFolder -> realPath
    is StorageLocation.SafFolder -> "saf://$treeUri"
    is StorageLocation.AppOwned -> null
    is StorageLocation.HostFolder -> null
}
