// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/**
 * A single LLM-sourced proposal awaiting explicit user accept/reject before anything is
 * written to the graph. Mirrors the shipped [dev.stapler.stelekit.git.model.SyncState.JournalMergeReady]
 * pattern — a subsystem proposes a change, the UI shows it for review, and an explicit accept
 * re-validates before writing.
 *
 * [graphId] is mandatory on every variant (pitfalls research §5.1's multi-graph concern) — a
 * proposal must never be applied to a graph other than the one it was generated against, since
 * this codebase supports multiple simultaneously open graphs ([dev.stapler.stelekit.db.GraphManager]).
 *
 * [BlockEdit]/[TagChange] carry [BlockEdit.currentContentSnapshot] — the target block's content
 * at propose time — so the write path can re-validate staleness (the block may have changed or
 * been deleted between proposal and accept) rather than blind-applying a possibly-stale edit.
 */
sealed interface PendingLlmSuggestion {
    /** UUIDv7, generated at propose time — see [dev.stapler.stelekit.util.UuidGenerator.generateV7]. */
    val id: String

    /** Mandatory — which [dev.stapler.stelekit.db.GraphManager]-scoped graph this targets. */
    val graphId: String
    val sourceProviderId: String
    val proposedAtEpochMs: Long
    val rationale: String?

    data class BlockEdit(
        override val id: String,
        override val graphId: String,
        override val sourceProviderId: String,
        override val proposedAtEpochMs: Long,
        override val rationale: String?,
        val pageUuid: String,
        val blockUuid: String,
        /** Content at propose time — staleness check compares this against the live block. */
        val currentContentSnapshot: String,
        val proposedContent: String,
    ) : PendingLlmSuggestion

    data class TagChange(
        override val id: String,
        override val graphId: String,
        override val sourceProviderId: String,
        override val proposedAtEpochMs: Long,
        override val rationale: String?,
        val pageUuid: String,
        val blockUuid: String,
        val currentContentSnapshot: String,
        val addedTerms: List<String>,
        val removedTerms: List<String>,
    ) : PendingLlmSuggestion

    data class NewPage(
        override val id: String,
        override val graphId: String,
        override val sourceProviderId: String,
        override val proposedAtEpochMs: Long,
        override val rationale: String?,
        val proposedTitle: String,
        val proposedBlocks: List<ProposedBlock>,
    ) : PendingLlmSuggestion
}

data class ProposedBlock(val content: String, val depth: Int, val order: Int)
