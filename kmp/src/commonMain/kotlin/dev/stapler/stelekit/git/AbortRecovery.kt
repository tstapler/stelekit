// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.FileSystem

/** What the abort's hard reset would discard, and where the copies are kept. */
data class AbortPreservation(val paths: List<String>, val recoveryDir: String)

/**
 * Copies of working-tree edits taken before `abortMerge`'s hard reset (Task 2.4i), kept under
 * `<root>/<graphId>/<timestamp>/` until the next successful sync. Writing fails closed: a copy that
 * cannot be written and read back identically means the reset must not run.
 */
class AbortRecovery(
    private val fileSystem: FileSystem,
    private val root: String,
    private val clock: () -> Long,
) {
    /** Writes [files] (repo-relative path to bytes) and returns their directory, or null when any copy failed verification. */
    fun snapshot(graphId: String, files: Map<String, ByteArray>): String? {
        val dir = "$root/$graphId/${clock()}"
        return try {
            files.all { (path, bytes) ->
                val target = "$dir/$path"
                ensureDirectory(target.substringBeforeLast('/'))
                fileSystem.writeFileBytes(target, bytes) && fileSystem.readFileBytes(target)?.contentEquals(bytes) == true
            }.let { ok -> if (ok) dir else null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Removes every kept copy for [graphId]; best effort, never throws. */
    fun clear(graphId: String) {
        runCatching { deleteTree("$root/$graphId") }
    }

    private fun ensureDirectory(path: String) {
        if (!fileSystem.directoryExists(path)) fileSystem.createDirectory(path)
    }

    private fun deleteTree(path: String) {
        fileSystem.listFiles(path).forEach { fileSystem.deleteFile("$path/$it") }
        fileSystem.listDirectories(path).forEach { deleteTree("$path/$it") }
        fileSystem.deleteFile(path)
    }
}

/**
 * The user-initiated abort: copies aside every edit the hard reset would discard, asks for confirmation,
 * resets, then restores and re-reads those edits. A copy that cannot be verified, or a refusal, stops before the reset.
 */
class GuardedMergeAbort(
    private val gitRepository: GitRepository,
    private val recovery: AbortRecovery,
) {
    companion object {
        fun create(gitRepository: GitRepository, fileSystem: FileSystem, clock: () -> Long) =
            GuardedMergeAbort(gitRepository, AbortRecovery(fileSystem, fileSystem.expandTilde("~/.stelekit/git-abort-recovery"), clock))
    }

    /** Drops the kept copies once a sync has succeeded. */
    fun clearRecovery(graphId: String) = recovery.clear(graphId)

    suspend fun abort(
        graphId: String,
        config: GitConfig,
        confirm: suspend (AbortPreservation) -> Boolean,
    ): Either<DomainError.GitError, Unit> {
        val atRisk = gitRepository.editsAtRiskOfReset(config).getOrElse { return it.left() }
        val preserved = if (atRisk.isEmpty()) emptyMap() else {
            val bytes = atRisk.associateWith { path ->
                gitRepository.readWorkingBytes(config, path)
                    ?: return DomainError.GitError.CommitFailed("Could not read $path to keep a copy; merge not aborted").left()
            }
            val dir = recovery.snapshot(graphId, bytes)
                ?: return DomainError.GitError.CommitFailed("Could not save copies of your edits; merge not aborted").left()
            if (!confirm(AbortPreservation(atRisk, dir))) return DomainError.GitError.CommitFailed("Abort cancelled").left()
            bytes
        }
        return gitRepository.abortMerge(config).flatMap { restore(config, preserved) }
    }

    /** Puts the pre-abort bytes back over the reset tree and re-reads them; the recovery copy is kept on any mismatch. */
    private suspend fun restore(config: GitConfig, preserved: Map<String, ByteArray>): Either<DomainError.GitError, Unit> {
        val failed = preserved.filter { (path, bytes) ->
            !gitRepository.writeWorkingBytes(config, path, bytes) ||
                gitRepository.readWorkingBytes(config, path)?.contentEquals(bytes) != true
        }.keys
        return if (failed.isEmpty()) Unit.right()
        else DomainError.GitError.CommitFailed("Merge aborted, but could not restore ${failed.joinToString()}; your copies are kept in the recovery folder").left()
    }
}
