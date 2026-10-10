// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-disk schema for `/stelekit/{graphId}/.stele-dirty-set.json` — the OPFS checkpoint that
 * tracks which repo-relative paths have changed since the last successful push, plus an
 * in-flight commit staged by [dev.stapler.stelekit.git.WasmGitWriteService] between its
 * `commit()` and `push()` steps.
 *
 * See `project_plans/web-git-writeback/implementation/plan.md` §4 for the reference schema
 * and the crash-safety rationale for [PendingCommit] being a single sum-typed field rather
 * than two independently-nullable strings.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DirtySetMarker(
    @EncodeDefault val version: Int = 1,
    val graphId: String,
    val baseSha: String,
    @EncodeDefault val pendingCommit: PendingCommit = PendingCommit.None,
    val checkpointedAtMillis: Long,
    @EncodeDefault val dirtyFiles: Map<String, DirtyEntry> = emptyMap(),
)

/** The kind of mutation recorded for a dirty path since the last checkpoint. */
@Serializable
enum class DirtyOp {
    @SerialName("write") WRITE,
    @SerialName("delete") DELETE,
}

/** A single dirty-path record: what happened to it, and when. */
@Serializable
data class DirtyEntry(
    val op: DirtyOp,
    val updatedAtMillis: Long,
)

/**
 * The GitHub commit created by `commit()` before `push()` runs, persisted so a reload between
 * the two steps doesn't lose track of the already-created (but not yet ref-pointed) commit.
 *
 * Deliberately a sealed sum type rather than two independently-nullable `String?` fields
 * (`pendingCommitSha`/`pendingTreeSha`): that shape would let a hand-edited or corrupted OPFS
 * file — or a future bug — construct a marker with one SHA set and the other `null`, which is
 * semantically illegal since both are always set together by `commit()` and cleared together
 * by `push()`/`clearDirtySet`. With this type there is no constructor path that produces that
 * state: the only two shapes are [None] (zero-arg) and [Staged] (both SHAs required, non-null).
 */
@Serializable
sealed interface PendingCommit {
    @Serializable
    @SerialName("none")
    data object None : PendingCommit

    @Serializable
    @SerialName("staged")
    data class Staged(val commitSha: String, val treeSha: String) : PendingCommit
}
