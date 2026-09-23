// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GitLabCommitAction(
    val action: String,
    @SerialName("file_path") val filePath: String,
    val content: String? = null,
    @EncodeDefault val encoding: String = "base64",
    @SerialName("last_commit_id") val lastCommitId: String? = null,
    @SerialName("previous_path") val previousPath: String? = null,
)

@Serializable
data class GitLabCommitRequest(
    val branch: String,
    @SerialName("commit_message") val commitMessage: String,
    @SerialName("start_sha") val startSha: String? = null,
    val actions: List<GitLabCommitAction>,
)

@Serializable
data class GitLabCommitResponse(
    val id: String,
    @SerialName("short_id") val shortId: String? = null,
)

@Serializable
data class GitLabDiffEntry(
    @SerialName("new_path") val newPath: String,
)

@Serializable
data class GitLabCompareResponse(
    val commits: List<GitLabCommitResponse> = emptyList(),
    val diffs: List<GitLabDiffEntry> = emptyList(),
)

// ── BLOCKER fix (web-git-writeback architecture review): GET .../repository/tree response
// shape, used by WasmGitWriteService.fetchGitLabTreePaths to determine real base-tree
// membership (replacing the broken `dirty.keys` stand-in that misclassified every WRITE as
// "update") ──

@Serializable
data class GitLabTreeEntry(
    @SerialName("path") val path: String,
)

// ── Epic 4.1 (Task 4.1.1e): GET .../repository/commits response shape, used by
// WasmGitRepository.log() ──

@Serializable
data class GitLabCommitLogEntry(
    val id: String,
    val title: String,
    @SerialName("author_name") val authorName: String,
    @SerialName("authored_date") val authoredDate: String,
)
