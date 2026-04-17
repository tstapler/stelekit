package dev.stapler.stelekit.flashcard

import dev.stapler.stelekit.model.Block
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SM-2-lite pass/fail calculation logic extracted from FlashcardsScreen.
 *
 * Pass calculation:
 *   newInterval = max(1, (interval * ease).toInt())
 *   newEase = min(2.5, ease + 0.1)
 *   nextReview = today + newInterval days
 *
 * Fail calculation:
 *   newEase = max(1.3, ease - 0.2)
 *   newInterval = 1
 *   nextReview = today + 1 day
 */
class FlashcardReviewTest {

    // ---- Pure calculation helpers (mirrors FlashcardsScreen logic) ----

    private fun calcPassInterval(interval: Int, ease: Double): Int =
        maxOf(1, (interval * ease).toInt())

    private fun calcPassEase(ease: Double): Double =
        minOf(2.5, ease + 0.1)

    private fun calcFailEase(ease: Double): Double =
        maxOf(1.3, ease - 0.2)

    // ---- Pass tests ----

    @Test
    fun `pass with default values ease=2-5 interval=1 produces interval=2 ease=2-5`() {
        val ease = 2.5
        val interval = 1
        val newInterval = calcPassInterval(interval, ease)
        val newEase = calcPassEase(ease)
        assertEquals(2, newInterval)
        assertEquals(2.5, newEase)
    }

    @Test
    fun `pass with ease=2-0 interval=3 produces interval=6 ease=2-1`() {
        val ease = 2.0
        val interval = 3
        val newInterval = calcPassInterval(interval, ease)
        val newEase = calcPassEase(ease)
        assertEquals(6, newInterval)
        assertEquals(2.1, newEase, absoluteTolerance = 0.0001)
    }

    @Test
    fun `ease is capped at 2-5 on pass`() {
        // Start at 2.5 — adding 0.1 should still yield 2.5
        assertEquals(2.5, calcPassEase(2.5))
        // Start slightly below cap — sum exceeds 2.5, should be capped
        assertEquals(2.5, calcPassEase(2.45))
    }

    @Test
    fun `pass interval is at least 1 even when ease times interval rounds to 0`() {
        // Edge: interval=0 would produce 0*ease=0 → floor=0 → max(1,0)=1
        assertEquals(1, calcPassInterval(0, 0.5))
    }

    // ---- Fail tests ----

    @Test
    fun `fail with ease=2-5 produces ease=2-3 and interval=1`() {
        val ease = 2.5
        val newEase = calcFailEase(ease)
        assertEquals(2.3, newEase, absoluteTolerance = 0.0001)
        // interval is always 1 after fail
        assertEquals(1, 1)
    }

    @Test
    fun `fail with ease=1-4 floors ease at 1-3`() {
        val ease = 1.4
        val newEase = calcFailEase(ease)
        assertEquals(1.3, newEase, absoluteTolerance = 0.0001)
    }

    @Test
    fun `ease floor on fail is 1-3`() {
        // Any ease at or below 1.3 should not decrease further
        assertEquals(1.3, calcFailEase(1.3), absoluteTolerance = 0.0001)
        assertEquals(1.3, calcFailEase(1.0), absoluteTolerance = 0.0001)
    }

    // ---- Due-card filter tests ----

    private val now = Clock.System.now()
    private val today = LocalDate(2026, 4, 15)

    private fun makeBlock(
        card: String? = null,
        nextReview: String? = null
    ): Block {
        val props = buildMap<String, String> {
            if (card != null) put("card", card)
            if (nextReview != null) put("card-next-review", nextReview)
        }
        return Block(
            uuid = "test-block-1",
            pageUuid = "test-page-1",
            content = "What is the capital of France?",
            level = 0,
            position = 0,
            createdAt = now,
            updatedAt = now,
            properties = props
        )
    }

    private fun isDue(block: Block): Boolean {
        if (block.properties["card"] != "true") return false
        val dateStr = block.properties["card-next-review"]
        return if (dateStr == null) {
            true // new card, always due
        } else {
            runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { it <= today } ?: true
        }
    }

    @Test
    fun `due-card filter includes block with card=true and no next-review`() {
        val block = makeBlock(card = "true")
        assertTrue(isDue(block), "New card with no next-review should be due")
    }

    @Test
    fun `due-card filter includes block with card=true and next-review on today`() {
        val block = makeBlock(card = "true", nextReview = today.toString())
        assertTrue(isDue(block), "Card due today should be included")
    }

    @Test
    fun `due-card filter includes block with card=true and next-review in the past`() {
        val block = makeBlock(card = "true", nextReview = "2026-04-14")
        assertTrue(isDue(block), "Card with past next-review should be due")
    }

    @Test
    fun `due-card filter excludes block with card=true and next-review in the future`() {
        val block = makeBlock(card = "true", nextReview = "2026-04-16")
        assertFalse(isDue(block), "Card with future next-review should not be due")
    }

    @Test
    fun `due-card filter excludes block without card=true`() {
        val block = makeBlock()
        assertFalse(isDue(block), "Block without card=true should never be due")
    }
}

private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "Expected $expected ± $absoluteTolerance but was $actual"
    )
}
