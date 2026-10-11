package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class LinkMatchingTest {
    private val now = Clock.System.now()
    private val patterns = compileLinkPatterns("X")

    private fun block(id: String, content: String) = Block(
        uuid = BlockUuid(id), pageUuid = PageUuid("p"), content = content,
        position = "a0", createdAt = now, updatedAt = now,
    )

    /** Fake table: serves windows of [rows]; records each requested (batchSize, sqlOffset). */
    private class FakeTable(val rows: List<Block>) {
        val calls = mutableListOf<Pair<Int, Int>>()
        suspend fun load(batchSize: Int, sqlOffset: Int): LinkBatch {
            calls += batchSize to sqlOffset
            val window = rows.drop(sqlOffset).take(batchSize)
            return LinkBatch(window, exhausted = window.size < batchSize)
        }
    }

    @Test
    fun `stops once offset plus limit true matches are found`() = runTest {
        val table = FakeTable((1..300).map { block("b$it", "see [[X]]") })
        val r = overfetchLinkedReferences(limit = 5, offset = 0, patterns = patterns, loadBatch = table::load)
        assertEquals(5, r.size)
        assertEquals(1, table.calls.size)
        assertEquals(100, table.calls[0].first) // max(need*4, 100)
    }

    @Test
    fun `offset and limit slice the accumulated matches`() = runTest {
        val table = FakeTable((1..300).map { block("b$it", "see [[X]]") })
        val r = overfetchLinkedReferences(limit = 3, offset = 2, patterns = patterns, loadBatch = table::load)
        assertEquals(listOf("b3", "b4", "b5"), r.map { it.uuid.value })
    }

    @Test
    fun `batch doubles when yield is low and is capped`() = runTest {
        // Only false positives: yield stays under 25%, so the batch doubles until the cap.
        val table = FakeTable((1..20_000).map { block("b$it", "[[XYZ]] no match") })
        overfetchLinkedReferences(limit = 5, offset = 0, patterns = patterns, loadBatch = table::load)
        val sizes = table.calls.map { it.first }
        assertEquals(listOf(100, 200, 400, 800, 1600, 2000, 2000), sizes.take(7))
        assertTrue(sizes.all { it <= MAX_LINKED_REF_BATCH })
        assertEquals(listOf(0, 100, 300, 700), table.calls.take(4).map { it.second })
    }

    @Test
    fun `iteration count is capped`() = runTest {
        val table = FakeTable((1..500_000).map { block("b$it", "[[XYZ]] no match") })
        val r = overfetchLinkedReferences(limit = 5, offset = 0, patterns = patterns, loadBatch = table::load)
        assertTrue(r.isEmpty())
        assertEquals(MAX_LINKED_REF_ITERATIONS, table.calls.size)
    }

    @Test
    fun `terminates when the table is exhausted`() = runTest {
        val table = FakeTable(listOf(block("b1", "[[X]]"), block("b2", "nothing")))
        val r = overfetchLinkedReferences(limit = 10, offset = 0, patterns = patterns, loadBatch = table::load)
        assertEquals(listOf("b1"), r.map { it.uuid.value })
        assertEquals(1, table.calls.size)
    }

    @Test
    fun `duplicate candidates across windows are counted once`() = runTest {
        val dup = block("dup", "[[X]]")
        var call = 0
        val r = overfetchLinkedReferences(limit = 5, offset = 0, patterns = patterns) { size, _ ->
            call++
            LinkBatch(List(size) { dup }, exhausted = call >= 3)
        }
        assertEquals(1, r.size)
    }
}
