// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/**
 * Bounded undo/redo over committed transactions (ADR-002: snapshots that share structure, so an entry costs about
 * 0.6 KB at 5,000 elements). One entry is one whole transaction; when more than `SceneLimits.maxHistoryEntries` are
 * held the oldest are dropped entry by entry, never part of one. Immutable: every operation returns a new history.
 */
class HistoryV2 private constructor(
    private val past: List<Committed>,
    val present: SketchDocumentV2,
    private val future: List<Committed>,
    private val limit: Int,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val undoDepth: Int get() = past.size
    val redoDepth: Int get() = future.size

    /** Records [step] as the newest entry and drops the redo branch. [step] must start from [present]. */
    fun commit(step: Committed): Either<CommandError, HistoryV2> =
        if (step.before != present) CommandError.PatchConflict("history").left()
        else HistoryV2((past + step).takeLast(limit), step.after, emptyList(), limit).right()

    fun undo(): HistoryV2 =
        if (past.isEmpty()) this else past.last().let { HistoryV2(past.dropLast(1), it.before, listOf(it) + future, limit) }

    fun redo(): HistoryV2 =
        if (future.isEmpty()) this else future.first().let { HistoryV2(past + it, it.after, future.drop(1), limit) }

    companion object {
        fun of(initial: SketchDocumentV2 = SketchDocumentV2(), limits: SceneLimits = SceneLimits.Default): HistoryV2 =
            HistoryV2(emptyList(), initial, emptyList(), limits.maxHistoryEntries)
    }
}
