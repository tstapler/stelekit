// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.wikiRoot
import org.eclipse.jgit.api.MergeResult as JGitMergeResult
import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * Merge-conflict derivation for [JvmGitRepository.merge], split out to keep that class under the
 * file-size guideline and its `merge()` under the long-function/deep-nesting thresholds. Desktop
 * has no shadow-worktree indirection (JGit reads/writes the real working tree directly), so this
 * is simpler than [AndroidGitMergeSupport]'s equivalent.
 */
internal object JvmGitConflictSupport {
    /** One [ConflictFile] per path JGit reported as conflicting, or empty if the merge was clean. */
    fun buildConflictFiles(repo: Repository, mergeResult: JGitMergeResult, config: GitConfig): List<ConflictFile> {
        if (mergeResult.mergeStatus != JGitMergeResult.MergeStatus.CONFLICTING) return emptyList()
        return mergeResult.conflicts?.keys?.map { filePath ->
            buildConflictFile(repo, filePath, config)
        } ?: emptyList()
    }

    private fun buildConflictFile(repo: Repository, filePath: String, config: GitConfig): ConflictFile {
        val absolutePath = "${config.repoRoot}/$filePath"
        val wikiRelPath = if (!config.wikiSubdir.isNullOrEmpty() &&
            filePath.startsWith("${config.wikiSubdir}/")) {
            filePath.removePrefix("${config.wikiSubdir}/")
        } else {
            filePath
        }
        // JGit already wrote real conflict-marker content directly into the working tree at
        // absolutePath (Desktop has no shadow indirection). For markdown, prefer re-deriving that
        // content via the block-aware merge (tryBlockAwareConflict) over JGit's own line-level
        // markers — see that function's doc. Falls back to JGit's line-level marker content,
        // parsed the same way, for non-markdown/unparseable files.
        val jgitMarkerContent = runCatching { File(absolutePath).readText() }.getOrNull()
        val blockAware = tryBlockAwareConflict(repo, filePath, absolutePath, config.wikiRoot)
        val markerContent = blockAware?.markerText ?: jgitMarkerContent
        val hunks = blockAware?.hunks ?: markerContent?.let {
            ConflictResolver().parseConflictFile(absolutePath, it, config.wikiRoot).getOrNull()?.hunks
        } ?: emptyList()
        // Keep the working-tree file in sync with what the app will resolve against — otherwise a
        // block-merge conflict re-derivation would be invisible to anything reading the file
        // directly off disk.
        if (blockAware != null) {
            runCatching { File(absolutePath).writeText(blockAware.markerText) }
        }
        return ConflictFile(
            filePath = absolutePath,
            wikiRelativePath = wikiRelPath,
            hunks = hunks,
            rawContent = markerContent,
            duplicateBlockIds = blockAware?.duplicateBlockIds ?: emptyList(),
        )
    }
}
