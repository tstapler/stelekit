// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.flashcard

import dev.stapler.stelekit.model.Block
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Pure SM-2-lite scheduling logic for the flashcard feature.
 *
 * All functions are stateless and free of Compose dependencies so they can be
 * unit-tested in the `businessTest` source set.
 */
object FlashcardScheduler {

    /** Starting ease factor for a card that has never been reviewed. */
    const val DEFAULT_EASE: Double = 2.5

    /** Maximum ease factor — ease is capped here on every pass. */
    const val MAX_EASE: Double = 2.5

    /** Minimum ease factor — ease is floored here on every fail. */
    const val MIN_EASE: Double = 1.3

    /** Amount added to ease on a successful review. */
    const val EASE_PASS_DELTA: Double = 0.1

    /** Amount subtracted from ease on a failed review. */
    const val EASE_FAIL_DELTA: Double = 0.2

    /**
     * Swipe distance (in pixels) required to register a pass or fail gesture.
     * Positive = pass (right swipe), negative = fail (left swipe).
     */
    const val SWIPE_THRESHOLD: Float = 200f

    /**
     * Swipe distance (in pixels) at which the card background starts to change
     * colour to indicate the current gesture direction.
     */
    const val SWIPE_COLOR_THRESHOLD: Float = 80f

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns `true` when [block] is a flashcard that is due on or before [today].
     *
     * A block is due when:
     * - It carries `card:: true` in its properties, AND
     * - Either it has no `card-next-review` property (new card) or that date ≤ [today].
     */
    fun isDue(block: Block, today: LocalDate): Boolean {
        if (block.properties["card"] != "true") return false
        val dateStr = block.properties["card-next-review"]
        return if (dateStr == null) {
            true // new card — always due
        } else {
            runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { it <= today } ?: true
        }
    }

    /**
     * Computes the updated flashcard properties after a review of [block].
     *
     * @param block The card being reviewed.
     * @param pass  `true` for a successful recall ("Good"), `false` for a failure ("Again").
     * @param today The review date used to compute the next-review date.
     * @return A new map containing the full set of card properties that should
     *         replace the current ones on the block (merged with any non-card properties
     *         at the call site).
     */
    fun computeNextReview(block: Block, pass: Boolean, today: LocalDate): Map<String, String> {
        val ease = block.properties["card-ease"]?.toDoubleOrNull() ?: DEFAULT_EASE
        return if (pass) {
            val interval = block.properties["card-interval"]?.toIntOrNull() ?: 1
            val newInterval = maxOf(1, (interval * ease).toInt())
            val newEase = minOf(MAX_EASE, ease + EASE_PASS_DELTA)
            val nextReview = today.plus(newInterval, DateTimeUnit.DAY)
            block.properties.toMutableMap().apply {
                put("card-next-review", nextReview.toString())
                put("card-ease", formatEase(newEase))
                put("card-interval", newInterval.toString())
            }
        } else {
            val newEase = maxOf(MIN_EASE, ease - EASE_FAIL_DELTA)
            val nextReview = today.plus(1, DateTimeUnit.DAY)
            block.properties.toMutableMap().apply {
                put("card-next-review", nextReview.toString())
                put("card-ease", formatEase(newEase))
                put("card-interval", "1")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Formats an ease factor as a two-decimal-place string (e.g. `2.50`, `1.30`).
     *
     * Avoids floating-point formatting inconsistencies by rounding to the nearest
     * hundredth using integer arithmetic.
     */
    private fun formatEase(value: Double): String {
        val rounded = kotlin.math.round(value * 100).toLong()
        return "${rounded / 100}.${(rounded % 100).toString().padStart(2, '0')}"
    }
}
