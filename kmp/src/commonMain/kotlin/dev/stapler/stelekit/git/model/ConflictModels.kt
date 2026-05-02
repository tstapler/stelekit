// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.Serializable

data class ConflictFile(
    val filePath: String,
    val wikiRelativePath: String,
    val hunks: List<ConflictHunk>,
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
