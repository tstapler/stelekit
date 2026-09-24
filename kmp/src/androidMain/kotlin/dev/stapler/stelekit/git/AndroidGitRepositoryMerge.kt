// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.ConflictHunk
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.wikiRoot
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import org.eclipse.jgit.api.MergeResult as JGitMergeResult
import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * Merge-conflict derivation and shadow/SAF write-back for [AndroidGitRepository.merge] and
 * [AndroidGitRepository.checkoutFile], split out to keep that class under the file-size
 * guideline and its `merge()` under the long-function/deep-nesting thresholds.
 */
internal class AndroidGitMergeSupport(
    private val fileSystem: FileSystem,
    private val logger: Logger,
    private val resolveForJGit: (String) -> String,
) {
    /** One [ConflictFile] per path JGit reported as conflicting, or empty if the merge was clean. */
    suspend fun buildConflictFiles(
        repo: Repository,
        mergeResult: JGitMergeResult,
        config: GitConfig,
        worktree: GitShadowWorktree?,
    ): List<ConflictFile> {
        if (mergeResult.mergeStatus != JGitMergeResult.MergeStatus.CONFLICTING) return emptyList()
        return mergeResult.conflicts?.keys?.map { filePath ->
            buildConflictFile(repo, filePath, config, worktree)
        } ?: emptyList()
    }

    private suspend fun buildConflictFile(
        repo: Repository,
        filePath: String,
        config: GitConfig,
        worktree: GitShadowWorktree?,
    ): ConflictFile {
        // filePath is git-relative (e.g. "pages/foo.md"), relative to whatever directory JGit
        // actually opened — the shadow worktree root in shadow-mirror mode, not config.repoRoot
        // (the SAF root string). toUserFacingPath() strips worktreeRootPath, so it must be given
        // a shadow-absolute path, never a repoRoot-prefixed one (fixed after Phase 3 review
        // flagged the repoRoot-prefixed form as a no-op strip that doubled the SAF root).
        val absolutePath = worktree?.toUserFacingPath("${worktree.worktreeRootPath}/$filePath")
            ?: "${config.repoRoot}/$filePath"
        val wikiRelPath = if (!config.wikiSubdir.isNullOrEmpty() &&
            filePath.startsWith("${config.wikiSubdir}/")) {
            filePath.removePrefix("${config.wikiSubdir}/")
        } else {
            filePath
        }

        val content = deriveConflictContent(repo, filePath, absolutePath, config, worktree)
        writeBackConflictContent(worktree, filePath, config, content)

        return ConflictFile(
            filePath = absolutePath,
            wikiRelativePath = wikiRelPath,
            hunks = content.hunks,
            rawContent = content.markerContent,
            duplicateBlockIds = content.blockAware?.duplicateBlockIds ?: emptyList(),
        )
    }

    private data class ConflictContent(
        val markerContent: String?,
        val hunks: List<ConflictHunk>,
        val blockAware: BlockAwareConflictResolution?,
    )

    /**
     * Reads the real conflict-marker content JGit just wrote into the working tree — the shadow
     * tree in shadow-mirror mode, the real repoRoot-relative file otherwise (mirrors
     * [JvmGitRepository], which has no shadow indirection at all). For markdown, prefers
     * re-deriving that content via the block-aware merge ([tryBlockAwareConflict]) over JGit's
     * own line-level markers — see that function's doc. Falls back to JGit's line-level marker
     * content, parsed the same way, for non-markdown/unparseable files or binary/rename-only
     * conflicts.
     */
    private fun deriveConflictContent(
        repo: Repository,
        filePath: String,
        absolutePath: String,
        config: GitConfig,
        worktree: GitShadowWorktree?,
    ): ConflictContent {
        val jgitMarkerContent = worktree?.readShadowFile(filePath)
            ?: runCatching { File(resolveForJGit(config.repoRoot), filePath).readText() }.getOrNull()
        val blockAware = tryBlockAwareConflict(repo, filePath, absolutePath, config.wikiRoot)
        val markerContent = blockAware?.markerText ?: jgitMarkerContent
        val hunks = blockAware?.hunks ?: markerContent?.let {
            ConflictResolver().parseConflictFile(absolutePath, it, config.wikiRoot).getOrNull()?.hunks
        } ?: emptyList()
        return ConflictContent(markerContent, hunks, blockAware)
    }

    /**
     * Keeps the shadow tree, and best-effort SAF, in sync with what the app will resolve
     * against. The shadow write is seeded with the block-merge text instead of JGit's own when
     * one was derived; the SAF write-back (through the same actor Phase 3's clean-merge path
     * uses, so a concurrent SAF edit is detected rather than clobbered) makes the file externally
     * visible with real conflict markers, matching what a direct-filesystem git working tree
     * already shows for free. Resolution itself never depends on the SAF write succeeding — it
     * uses [ConflictContent.markerContent] captured above via [ConflictFile.rawContent], not a
     * later SAF re-read.
     */
    private suspend fun writeBackConflictContent(
        worktree: GitShadowWorktree?,
        filePath: String,
        config: GitConfig,
        content: ConflictContent,
    ) {
        if (worktree != null && content.blockAware != null) {
            worktree.writeShadowFile(filePath, content.blockAware.markerText)
        }
        if (worktree != null && content.markerContent != null) {
            worktree.writeBackQueue.enqueue(filePath)
            GitShadowFlushActor(fileSystem, worktree, worktree.writeBackQueue, config.repoRoot).flush()
        }
    }

    /**
     * Flushes [worktree]'s pending write-back queue for [paths] to SAF and returns a
     * [DomainError.GitError.WorkingTreeConcurrentEditDetected] if the flush surfaced one — the
     * only write-back failure that must abort the caller's operation rather than retry silently.
     * A [DomainError.GitError.WorkingTreeWriteBackFailed] is logged as retry-pending and
     * otherwise ignored: the caller's own JGit-side result has already succeeded, and a transient
     * write-back failure is left queued for the next sync's drain. [callerName] identifies the
     * caller in that log line (e.g. "merge", "checkoutFile").
     */
    suspend fun flushAndCheckConcurrentEdit(
        worktree: GitShadowWorktree,
        paths: List<String>,
        repoRoot: String,
        callerName: String,
    ): DomainError.GitError.WorkingTreeConcurrentEditDetected? {
        paths.forEach { worktree.writeBackQueue.enqueue(it) }
        val flushErrors = GitShadowFlushActor(fileSystem, worktree, worktree.writeBackQueue, repoRoot)
            .flush()
            .flushErrors()

        val concurrentEdit = flushErrors
            .filterIsInstance<DomainError.GitError.WorkingTreeConcurrentEditDetected>()
            .firstOrNull()
        if (concurrentEdit != null) return concurrentEdit

        flushErrors.filterIsInstance<DomainError.GitError.WorkingTreeWriteBackFailed>().forEach {
            logger.warn("$callerName: write-back failed for ${it.path}, will retry on next sync")
        }
        return null
    }

    private fun List<Either<DomainError.GitError, Unit>>.flushErrors(): List<DomainError.GitError> =
        mapNotNull { (it as? Either.Left)?.value }
}
