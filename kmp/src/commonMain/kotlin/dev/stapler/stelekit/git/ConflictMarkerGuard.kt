// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.db.ConflictMarkerDetector
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.model.wikiRoot
import dev.stapler.stelekit.platform.FileSystem

/** What [ConflictMarkerGuard] found: the files, plus the sync state and error that refusing to commit them produces. */
class UnresolvedMarkers(val files: List<ConflictFile>) {
    val state = SyncState.ConflictPending(files)
    val error = DomainError.GitError.MergeConflict(conflictCount = files.size, conflictPaths = files.map { it.filePath })
}

/** Finds files a half-finished merge left with `<<<<<<<` markers, so they are never committed and pushed. */
class ConflictMarkerGuard(private val fileSystem: FileSystem) {

    fun findUnresolved(config: GitConfig, status: GitStatus): UnresolvedMarkers? =
        (status.modifiedFiles + status.untrackedFiles)
            .filter { it.endsWith(".md") }
            .mapNotNull { relPath ->
                val absPath = "${config.repoRoot}/$relPath"
                val content = fileSystem.readFile(absPath) ?: return@mapNotNull null
                if (!ConflictMarkerDetector.hasConflictMarkers(content)) return@mapNotNull null
                ConflictResolver().parseConflictFile(absPath, content, config.wikiRoot).getOrNull()
                    ?: ConflictFile(absPath, relPath, emptyList(), rawContent = content)
            }
            .takeIf { it.isNotEmpty() }
            ?.let(::UnresolvedMarkers)

    fun firstWithMarkers(paths: List<String>): String? =
        paths.firstOrNull { path -> fileSystem.readFile(path)?.let(ConflictMarkerDetector::hasConflictMarkers) == true }

    fun refusalIfMarkers(path: String, content: String) =
        if (ConflictMarkerDetector.hasConflictMarkers(content)) refusal(path) else null

    fun refusal(path: String) =
        DomainError.GitError.CommitFailed("Refusing to commit $path: it still contains conflict markers")
}
