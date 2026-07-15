// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared [Json] instance for decoding GitHub Git Data API responses.
 * Tolerates fields GitHub adds to responses that this client does not model.
 */
val gitApiJson: Json = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GitBlobRequest(
    val content: String,
    @EncodeDefault val encoding: String = "base64",
)

@Serializable
data class GitBlobResponse(
    val sha: String,
    val url: String? = null,
)

@Serializable
data class GitTreeEntry(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String? = null,
    val content: String? = null,
)

@Serializable
data class GitTreeRequest(
    @SerialName("base_tree") val baseTree: String? = null,
    val tree: List<GitTreeEntry>,
)

@Serializable
data class GitTreeResponse(
    val sha: String,
)

@Serializable
data class GitCommitAuthor(
    val name: String,
    val email: String,
    val date: String,
)

@Serializable
data class GitCommitRequest(
    val message: String,
    val tree: String,
    val parents: List<String>,
    val author: GitCommitAuthor,
)

@Serializable
data class GitCommitResponse(
    val sha: String,
)

@Serializable
data class GitRefObject(
    val sha: String,
)

@Serializable
data class GitRefResponse(
    @SerialName("object") val obj: GitRefObject,
)

@Serializable
data class GitRefUpdateRequest(
    val sha: String,
    val force: Boolean = false,
)

@Serializable
data class GitCompareFile(
    val filename: String,
)

@Serializable
data class GitCompareResponse(
    @SerialName("ahead_by") val aheadBy: Int,
    val files: List<GitCompareFile> = emptyList(),
)

// ── Epic 4.1 (Task 4.1.1e): GET .../commits response shape, used by WasmGitRepository.log() ──

@Serializable
data class GitCommitLogAuthor(
    val name: String,
    val date: String,
)

@Serializable
data class GitCommitLogDetail(
    val message: String,
    val author: GitCommitLogAuthor,
)

@Serializable
data class GitCommitLogEntry(
    val sha: String,
    val commit: GitCommitLogDetail,
)
