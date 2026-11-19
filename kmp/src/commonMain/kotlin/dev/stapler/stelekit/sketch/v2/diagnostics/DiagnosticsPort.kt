// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.diagnostics

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What `RT` producers (saver, converter, importer) call to surface a problem, usable before any composable
 * exists: entries are retained here and the host (Task 3.6.4b) reads [diagnostics] when it attaches.
 * A producer raises a row; the port builds the [BannerCandidate] from fixed strings, so free text never
 * reaches a banner summary.
 */
interface DiagnosticsPort {
    val diagnostics: StateFlow<SketchDiagnostics>

    /** Retains the row, replacing an earlier entry of the same row. Left means the strings broke the budget; nothing is retained. */
    fun raise(row: BannerRowId, details: String = ""): Either<BannerRejection, BannerCandidate>

    /** Removes the entry of [row], if any. */
    fun resolve(row: BannerRowId)
}

/** Retains entries in memory, capped at [SceneLimits.maxDiagnosticsEntries] (the oldest is dropped first). */
class InMemoryDiagnosticsPort(
    private val strings: BannerStringSource,
    private val limits: SceneLimits = SceneLimits.Default,
) : DiagnosticsPort {
    private val state = MutableStateFlow(SketchDiagnostics())
    override val diagnostics: StateFlow<SketchDiagnostics> = state

    override fun raise(row: BannerRowId, details: String): Either<BannerRejection, BannerCandidate> =
        BannerCandidate.of(row, strings, details).onRight { candidate ->
            state.update { current ->
                SketchDiagnostics((current.entries.filter { it.row != row } + candidate).takeLast(limits.maxDiagnosticsEntries))
            }
        }

    override fun resolve(row: BannerRowId) {
        state.update { current -> SketchDiagnostics(current.entries.filter { it.row != row }) }
    }
}
