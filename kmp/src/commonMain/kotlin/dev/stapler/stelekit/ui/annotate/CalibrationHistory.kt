// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.PriorBasis

/**
 * Undo snapshots of the calibration, including its known distances, fit record and replaced basis
 * (they live in `calibration.geometry`), plus the measurements derived from it. Immutable so the
 * ViewModel can hold it in an atomic reference; [maxDepth] 1 keeps today's single-level undo.
 */
class CalibrationHistory private constructor(
    private val entries: List<Entry>,
    private val maxDepth: Int,
) {
    data class Entry(val calibration: Calibration, val annotations: List<MeasurementAnnotation>) {
        val constraints: List<CalibrationConstraint> get() = calibration.geometry?.constraints.orEmpty()
        val fit: ConstraintFitRecord? get() = calibration.geometry?.fit
        val priorBasis: PriorBasis? get() = fit?.priorBasis
    }

    val canUndo: Boolean get() = entries.isNotEmpty()

    /** Adds [entry] as the newest snapshot, dropping the oldest beyond [maxDepth]. */
    fun push(entry: Entry): CalibrationHistory = CalibrationHistory((entries + entry).takeLast(maxDepth), maxDepth)

    /** The newest snapshot and the history without it; null when there is nothing to undo. */
    fun pop(): Pair<Entry, CalibrationHistory>? =
        entries.lastOrNull()?.let { it to CalibrationHistory(entries.dropLast(1), maxDepth) }

    companion object {
        const val DEFAULT_DEPTH = 1

        fun empty(maxDepth: Int = DEFAULT_DEPTH): CalibrationHistory {
            require(maxDepth >= 1) { "maxDepth must be at least 1" }
            return CalibrationHistory(emptyList(), maxDepth)
        }
    }
}
