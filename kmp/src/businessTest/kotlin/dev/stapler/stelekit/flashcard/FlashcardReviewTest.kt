package dev.stapler.stelekit.flashcard

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SM-2-lite pass/fail calculation logic in [FlashcardScheduler].
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

    private val now = Clock.System.now()
    private val today = LocalDate(2026, 4, 15)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeBlock(
        card: String? = null,
        nextReview: String? = null,
        ease: String? = null,
        interval: String? = null
    ): Block {
        val props = buildMap<String, String> {
            if (card != null) put("card", card)
            if (nextReview != null) put("card-next-review", nextReview)
            if (ease != null) put("card-ease", ease)
            if (interval != null) put("card-interval", interval)
        }
        return Block(
            uuid = BlockUuid("test-block-1"),
            pageUuid = PageUuid("test-page-1"),
            content = "What is the capital of France?",
            level = 0,
            position = "a0",
            createdAt = now,
            updatedAt = now,
            properties = props
        )
    }

    // -----------------------------------------------------------------------
    // Pass tests
    // -----------------------------------------------------------------------

    @Test
    fun `pass with default values ease=2-5 interval=1 produces interval=2 ease=2-5`() {
        val block = makeBlock(card = "true", ease = "2.5", interval = "1")
        val result = FlashcardScheduler.computeNextReview(block, pass = true, today = today)
        assertEquals("2", result["card-interval"])
        assertEquals("2.50", result["card-ease"])
    }

    @Test
    fun `pass with ease=2-0 interval=3 produces interval=6 ease=2-1`() {
        val block = makeBlock(card = "true", ease = "2.0", interval = "3")
        val result = FlashcardScheduler.computeNextReview(block, pass = true, today = today)
        assertEquals("6", result["card-interval"])
        val newEase = result["card-ease"]!!.toDouble()
        assertEquals(2.1, newEase, absoluteTolerance = 0.0001)
    }

    @Test
    fun `ease is capped at 2-5 on pass`() {
        // Start at 2.5 — adding 0.1 should still yield 2.50
        val atCap = makeBlock(card = "true", ease = "2.5", interval = "1")
        assertEquals("2.50", FlashcardScheduler.computeNextReview(atCap, pass = true, today = today)["card-ease"])

        // Start slightly below cap — sum exceeds 2.5, should be capped at 2.50
        val nearCap = makeBlock(card = "true", ease = "2.45", interval = "1")
        assertEquals("2.50", FlashcardScheduler.computeNextReview(nearCap, pass = true, today = today)["card-ease"])
    }

    @Test
    fun `pass interval is at least 1 even when ease times interval rounds to 0`() {
        // interval=0 would produce 0*ease=0 → toInt()=0 → max(1,0)=1
        val block = makeBlock(card = "true", ease = "0.5", interval = "0")
        val result = FlashcardScheduler.computeNextReview(block, pass = true, today = today)
        assertEquals("1", result["card-interval"])
    }

    // -----------------------------------------------------------------------
    // Fail tests
    // -----------------------------------------------------------------------

    @Test
    fun `fail with ease=2-5 produces ease=2-3 and interval=1`() {
        val block = makeBlock(card = "true", ease = "2.5", interval = "5")
        val result = FlashcardScheduler.computeNextReview(block, pass = false, today = today)
        val newEase = result["card-ease"]!!.toDouble()
        assertEquals(2.3, newEase, absoluteTolerance = 0.0001)
        assertEquals("1", result["card-interval"])
    }

    @Test
    fun `fail with ease=1-4 floors ease at 1-3`() {
        val block = makeBlock(card = "true", ease = "1.4", interval = "2")
        val result = FlashcardScheduler.computeNextReview(block, pass = false, today = today)
        val newEase = result["card-ease"]!!.toDouble()
        assertEquals(1.3, newEase, absoluteTolerance = 0.0001)
    }

    @Test
    fun `ease floor on fail is 1-3`() {
        val atFloor = makeBlock(card = "true", ease = "1.3", interval = "1")
        val newEaseAtFloor = FlashcardScheduler.computeNextReview(atFloor, pass = false, today = today)["card-ease"]!!.toDouble()
        assertEquals(1.3, newEaseAtFloor, absoluteTolerance = 0.0001)

        val belowFloor = makeBlock(card = "true", ease = "1.0", interval = "1")
        val newEaseBelowFloor = FlashcardScheduler.computeNextReview(belowFloor, pass = false, today = today)["card-ease"]!!.toDouble()
        assertEquals(1.3, newEaseBelowFloor, absoluteTolerance = 0.0001)
    }

    // -----------------------------------------------------------------------
    // Due-card filter tests
    // -----------------------------------------------------------------------

    @Test
    fun `due-card filter includes block with card=true and no next-review`() {
        val block = makeBlock(card = "true")
        assertTrue(FlashcardScheduler.isDue(block, today), "New card with no next-review should be due")
    }

    @Test
    fun `due-card filter includes block with card=true and next-review on today`() {
        val block = makeBlock(card = "true", nextReview = today.toString())
        assertTrue(FlashcardScheduler.isDue(block, today), "Card due today should be included")
    }

    @Test
    fun `due-card filter includes block with card=true and next-review in the past`() {
        val block = makeBlock(card = "true", nextReview = "2026-04-14")
        assertTrue(FlashcardScheduler.isDue(block, today), "Card with past next-review should be due")
    }

    @Test
    fun `due-card filter excludes block with card=true and next-review in the future`() {
        val block = makeBlock(card = "true", nextReview = "2026-04-16")
        assertFalse(FlashcardScheduler.isDue(block, today), "Card with future next-review should not be due")
    }

    @Test
    fun `due-card filter excludes block without card=true`() {
        val block = makeBlock()
        assertFalse(FlashcardScheduler.isDue(block, today), "Block without card=true should never be due")
    }
}

private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "Expected $expected ± $absoluteTolerance but was $actual"
    )
}
