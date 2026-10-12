// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException

/** Outcome of staging under the LFS guard: what was held back and whether anything is left to commit. */
class LfsStaging(val held: List<LfsViolation>, val commitNeeded: Boolean)

/**
 * The only place [dev.stapler.stelekit.git.GitSyncService] stages files. [stage] applies the safety floor (guard
 * before staging, authoritative index check after); [refusalFor] is the merge-resolution flavour, which cannot
 * leave a file half-resolved and therefore refuses instead of holding back. Kept out of the service so the
 * service does not grow another concern, and so one structural test can pin "nothing stages outside here".
 */
class LfsGuardedStaging(
    private val gitRepository: GitRepository,
    private val lfsGuard: LfsGuardCoordinator,
) {
    private val logger = Logger("LfsGuardedStaging")

    /**
     * Stages the wiki subdirectory. Violating files are left out of the index and reported in [LfsStaging.held];
     * when [LfsGuardCoordinator.holdBack] is off the whole commit is refused instead (owner decision O6).
     */
    suspend fun stage(config: GitConfig, status: GitStatus): Either<DomainError.GitError, LfsStaging> {
        val changed = (status.modifiedFiles + status.untrackedFiles).distinct()
        val verdict = lfsGuard.evaluate(config, changed, gitRepository.cleanFilterActive)
        if (!lfsGuard.holdBack && verdict.held.isNotEmpty()) {
            return DomainError.GitError.LfsRawBinaryRefused(verdict.held.map { it.path }).left()
        }
        gitRepository.stageSubdir(config, verdict.heldPaths).onLeft { return it.left() }
        // Authoritative index check: also catches staged-new files the status lists above do not show.
        val escaped = try {
            gitRepository.stagedLfsViolations(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail closed: if the index cannot be verified, nothing is committed.
            return DomainError.GitError.CommitFailed("Could not check staged files against Git LFS rules: ${e.message}").left()
        }
        val held = verdict.held + escaped.filter { it.path !in verdict.heldPaths }
        if (held.isNotEmpty()) logger.warn("lfs: held back ${held.size} file(s) at LFS-tracked paths; the rest still syncs")
        return LfsStaging(held, commitNeeded = held.isEmpty() || gitRepository.hasStagedChanges(config)).right()
    }

    /** Merge-resolution flows cannot leave a file half-resolved, so a violating file refuses the step outright. */
    suspend fun refusalFor(config: GitConfig, filePath: String): DomainError.GitError? {
        val relative = filePath.removePrefix(config.repoRoot).trimStart('/')
        val verdict = lfsGuard.evaluate(config, listOf(relative), gitRepository.cleanFilterActive)
        return if (verdict.held.isEmpty()) null else DomainError.GitError.LfsRawBinaryRefused(verdict.held.map { it.path })
    }
}
