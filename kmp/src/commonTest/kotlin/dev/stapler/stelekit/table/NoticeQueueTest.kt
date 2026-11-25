package dev.stapler.stelekit.table

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoticeQueueTest {
    private fun info(id: Long) = Notice(id, "info $id", NoticeType.Info)
    private fun undo(id: Long) = Notice(id, "undo $id", NoticeType.Undo, "Undo")
    private fun retry(id: Long) = Notice(id, "retry $id", NoticeType.Retry, "Retry")
    private fun why(id: Long) = Notice(id, "why $id", NoticeType.Why, "Why?")

    @Test
    fun queue_should_ReplaceInformationalAndNeverDropActionBearing_When_Mixed() {
        val q = NoticeQueue()
        q.offer(info(1))
        assertEquals(OfferOutcome.Replaced, q.offer(info(2)))
        assertEquals(2L, q.visible?.id)

        q.advance()
        q.offer(undo(10))
        assertEquals(OfferOutcome.LiveRegionOnly, q.offer(info(3)))
        assertEquals(10L, q.visible?.id)
        assertEquals(0, q.moreCount)
        assertEquals(listOf("info 3"), q.liveRegionOnly)

        assertEquals(OfferOutcome.Queued, q.offer(undo(11)))
        assertEquals(1, q.moreCount)
        assertEquals(11L, q.advance()?.id)
        assertNull(q.advance())
    }

    @Test
    fun queue_should_PutRetryFirstAndKeepPreempted() {
        val q = NoticeQueue()
        q.offer(undo(1))
        q.offer(undo(2))
        q.offer(retry(3))
        assertEquals(3L, q.visible?.id)
        assertEquals(listOf(1L, 2L), q.pending.map { it.id })
    }

    @Test
    fun queue_should_ReplaceWhy_When_SecondWhyArrives() {
        val q = NoticeQueue()
        q.offer(why(1))
        assertEquals(OfferOutcome.Replaced, q.offer(why(2)))
        assertEquals(2L, q.visible?.id)

        val q2 = NoticeQueue()
        q2.offer(undo(1))
        q2.offer(why(2))
        q2.offer(why(3))
        assertEquals(listOf(3L), q2.pending.map { it.id })
    }

    @Test
    fun queue_should_RetireOldestUndoIntoControl_When_PastFive() {
        val q = NoticeQueue()
        for (i in 1L..5L) q.offer(undo(i))
        assertEquals(0, q.retiredUndos.size)
        assertEquals(OfferOutcome.RetiredOldestUndo, q.offer(undo(6)))
        assertEquals(listOf(2L), q.retiredUndos.map { it.id })
        assertEquals(1L, q.visible?.id)
        assertEquals(listOf(3L, 4L, 5L, 6L), q.pending.map { it.id })
    }

    @Test
    fun heightLimit_should_ExposeTwoLinesPortraitOneLandscapeAndQuarterOfSpace() {
        val q = NoticeQueue()
        assertEquals(NoticeHeightLimit(2, 0.25f), q.heightLimit(landscape = false))
        assertEquals(NoticeHeightLimit(1, 0.25f), q.heightLimit(landscape = true))
    }

    @Test
    fun queue_should_NeverLoseActionBearingNotice_When_AnyOfferAndAdvanceSequence() = runTest {
        // op: 0 info, 1 undo, 2 retry, 3 action, 4 advance
        checkAll(Arb.list(Arb.int(0..4), 0..60)) { ops ->
            val q = NoticeQueue()
            val offered = mutableSetOf<Long>()
            val dismissed = mutableSetOf<Long>()
            var id = 0L
            for (op in ops) {
                id++
                when (op) {
                    0 -> q.offer(info(id))
                    1 -> { q.offer(undo(id)); offered += id }
                    2 -> { q.offer(retry(id)); offered += id }
                    3 -> { q.offer(Notice(id, "a$id", NoticeType.Action, "Go")); offered += id }
                    else -> q.visible?.let { dismissed += it.id; q.advance() }
                }
            }
            val alive = (listOfNotNull(q.visible) + q.pending + q.retiredUndos).map { it.id }.toSet()
            val lost = offered - alive - dismissed
            // A Retry or Action may have replaced a visible informational notice, which is the one allowed drop.
            assertTrue(lost.isEmpty(), "lost action-bearing notices: $lost")
        }
    }
}
