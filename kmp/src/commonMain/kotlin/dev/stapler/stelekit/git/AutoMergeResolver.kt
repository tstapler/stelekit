// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem

/**
 * Writes and stages each cleanly auto-merged markdown file; returns those that fully succeeded.
 * A failure leaves the file unresolved so it surfaces to the user instead of being committed.
 */
internal suspend fun autoResolveCleanMerges(
    gitRepository: GitRepository,
    fileSystem: FileSystem,
    config: GitConfig,
    candidates: List<ConflictFile>,
    logger: Logger,
): List<ConflictFile> = candidates.filter { file ->
    val content = file.rawContent ?: return@filter false
    if (!fileSystem.writeFile(file.filePath, content)) {
        logger.warn("auto-merge write failed, leaving ${file.wikiRelativePath} for the user")
        return@filter false
    }
    val staged = gitRepository.markResolved(config, file.filePath)
    if (staged is Either.Left) {
        logger.warn("auto-merge markResolved failed for ${file.wikiRelativePath}: ${staged.value.message}")
        return@filter false
    }
    logger.info("auto-merged ${file.wikiRelativePath} (${file.autoResolvedBy})")
    true
}
