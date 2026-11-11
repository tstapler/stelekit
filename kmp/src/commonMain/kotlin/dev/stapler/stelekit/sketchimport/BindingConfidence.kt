// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import kotlin.math.abs

/** Confidence scale for [LabelBinder]; pure so the thresholds are unit-testable. */
object BindingConfidence {
    /** A label whose text names exactly one dimension through a registry alias. */
    const val ALIAS = 0.95

    /** Observations are emitted only at or above this; below it the label goes to manual tagging. */
    const val ACCEPT = 0.8

    /** A label with candidate keys but no single answer. */
    const val AMBIGUOUS = 0.3

    private const val PROXIMITY_BASE = 0.95
    private const val PROXIMITY_PENALTY = 4.0

    /** Relative span/label disagreement above which a drawn span is not evidence for the label. */
    const val MAX_SPAN_DISAGREEMENT = 0.05

    /**
     * Confidence that a drawn span of [spanInches] is the dimension line a label of [labelInches]
     * states: 0.95 for an exact match, dropping 0.04 per 1% of disagreement, 0 beyond 5%.
     */
    fun forSpan(spanInches: Double, labelInches: Double): Double {
        if (labelInches <= 0.0 || spanInches <= 0.0) return 0.0
        val disagreement = abs(spanInches - labelInches) / labelInches
        if (disagreement > MAX_SPAN_DISAGREEMENT) return 0.0
        return (PROXIMITY_BASE - PROXIMITY_PENALTY * disagreement).coerceIn(0.0, PROXIMITY_BASE)
    }
}
