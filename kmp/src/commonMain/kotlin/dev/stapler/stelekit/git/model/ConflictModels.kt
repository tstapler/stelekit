// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import dev.stapler.stelekit.git.merge.DuplicateBlockId
import kotlinx.serialization.Serializable

/**
 * @property filePath A platform-internal working-tree path. May be shadow-absolute on Android in
 * shadow-mirror mode — never assume it is directly SAF/filesystem-openable without going through
 * the platform's [dev.stapler.stelekit.git.GitRepository] implementation.
 * @property wikiRelativePath Always safe to display/write against the user-facing wiki root,
 * regardless of platform.
 * @property hunks Parsed conflict-marker sections for line-level resolution. Empty when the file
 * couldn't be parsed as a text-based conflict (binary content, a rename-only conflict, or no
 * conflict markers at all) — the UI falls back to whole-file resolution for such files.
 * @property rawContent The conflict-marker file content exactly as JGit produced it at merge
 * time (`git.merge()`), captured so resolution doesn't depend on re-reading it back from SAF —
 * a write-back of this content to SAF (Android shadow-mirror mode) is best-effort and may lose a
 * race against a concurrent user edit, whereas this captured copy is always available. Null only
 * when hunks is also empty (nothing to resolve at the hunk level for this file).
 * @property duplicateBlockIds `id::` values reused by more than one distinct block across this
 * file's merge inputs, from [dev.stapler.stelekit.git.merge.BlockDiff3]'s block-aware merge — see
 * [dev.stapler.stelekit.git.merge.findDuplicateBlockIds]'s doc. Always empty when this conflict
 * went through the line-level fallback instead (non-markdown path, or a block-parse failure).
 * Informational only: resolving the conflict normally does not clear a duplicate id, since the
 * two colliding blocks may both survive into the merged result.
 */
data class ConflictFile(
    val filePath: String,
    val wikiRelativePath: String,
    val hunks: List<ConflictHunk>,
    val rawContent: String? = null,
    val duplicateBlockIds: List<DuplicateBlockId> = emptyList(),
)

data class ConflictHunk(
    val id: String,
    val localLines: List<String>,
    val remoteLines: List<String>,
    val resolution: HunkResolution = HunkResolution.Unresolved,
    val manualContent: String? = null,
)

sealed class HunkResolution {
    data object Unresolved : HunkResolution()
    data object AcceptLocal : HunkResolution()
    data object AcceptRemote : HunkResolution()
    data object Manual : HunkResolution()
}

@Serializable
data class ConflictResolutionState(
    val graphId: String,
    val conflictFiles: List<SerializableConflictFile>,
    val startedAt: Long,
)

@Serializable
data class SerializableConflictFile(
    val filePath: String,
    val wikiRelativePath: String,
    val hunks: List<SerializableConflictHunk>,
)

@Serializable
data class SerializableConflictHunk(
    val id: String,
    val localLines: List<String>,
    val remoteLines: List<String>,
    val resolutionType: String = "Unresolved",
    val manualContent: String? = null,
)
