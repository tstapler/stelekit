@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * [UndoManager] against a real in-memory SQLite database. Operations are logged through
 * [OperationLogger] directly and the actor has no logger of its own, so undo/redo writes never
 * add operations to the log being inverted.
 */
class UndoManagerTest {

    private val page = "page-1"
    private val session = "session-1"

    private inner class Harness {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val blocks = SqlDelightBlockRepository(database, driver)
        val pages = SqlDelightPageRepository(database)
        val actor = DatabaseWriteActor(blocks, pages)
        val opLogger = OperationLogger(database, session)
        val undo = UndoManager(database, actor, session)

        init {
            runBlocking { pages.savePage(Page(uuid = PageUuid(page), name = page, createdAt = Clock.System.now(), updatedAt = Clock.System.now())) }
        }

        fun block(uuid: String, content: String, position: String = "a0") = Block(
            uuid = BlockUuid(uuid), pageUuid = PageUuid(page), content = content, level = 0, position = position,
            createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
        )

        suspend fun get(uuid: String): Block? = blocks.getBlockByUuid(BlockUuid(uuid)).first().getOrNull()

        suspend fun saveAndLogInsert(b: Block) { blocks.saveBlock(b); opLogger.logInsert(b) }

        fun restart() = UndoManager(database, actor, session)

        fun close() = actor.close()
    }

    private fun harness(body: suspend Harness.() -> Unit) = runBlocking {
        val h = Harness()
        try { h.body() } finally { h.close() }
    }

    @Test
    fun `undo and redo of UPDATE_BLOCK restore before and after content`() = harness {
        val v1 = block("A", "v1"); val v2 = block("A", "v2")
        blocks.saveBlock(v1)
        blocks.saveBlock(v2)
        opLogger.logUpdate(v1, v2)

        undo.undo()
        assertEquals("v1", get("A")!!.content)
        undo.redo()
        assertEquals("v2", get("A")!!.content)
    }

    @Test
    fun `undo of INSERT_BLOCK deletes the block and redo re-inserts it`() = harness {
        saveAndLogInsert(block("A", "hello"))

        undo.undo()
        assertNull(get("A"))
        undo.redo()
        assertEquals("hello", get("A")!!.content)
    }

    @Test
    fun `undo of DELETE_BLOCK restores the block and redo deletes it again`() = harness {
        val b = block("A", "keep me")
        blocks.saveBlock(b)
        opLogger.logDelete(b)
        blocks.deleteBlock(BlockUuid("A"))
        assertNull(get("A"))

        undo.undo()
        assertEquals("keep me", get("A")!!.content)
        undo.redo()
        assertNull(get("A"))
    }

    @Test
    fun `undo inverts a whole batch as one unit`() = harness {
        opLogger.logBatchStart("batch-1")
        saveAndLogInsert(block("A", "a", "a0"))
        saveAndLogInsert(block("B", "b", "a1"))
        opLogger.logBatchEnd("batch-1")

        undo.undo()
        assertNull(get("A"))
        assertNull(get("B"))
    }

    // Known gap, pinned so a fix is a visible test change: redo replays only the highest-seq undone op,
    // which for a batch is the BATCH_END marker (a no-op), so the batch is not re-applied.
    @Test
    fun `redo of an undone batch is currently a no-op (known gap)`() = harness {
        opLogger.logBatchStart("batch-1")
        saveAndLogInsert(block("A", "a", "a0"))
        saveAndLogInsert(block("B", "b", "a1"))
        opLogger.logBatchEnd("batch-1")

        undo.undo()
        undo.redo()
        assertNull(get("A"))
        assertNull(get("B"))
    }

    @Test
    fun `undo stops at a SYNC_BARRIER`() = harness {
        saveAndLogInsert(block("A", "a"))
        opLogger.logSyncBarrier()

        undo.undo()
        assertNotNull(get("A"), "undo must not cross a sync barrier")
        undo.refreshState()
        assertFalse(undo.canUndo.value)
        assertFalse(undo.canRedo.value)
    }

    @Test
    fun `canUndo and canRedo follow the log and undo redo transitions`() = harness {
        undo.refreshState()
        assertFalse(undo.canUndo.value)
        assertFalse(undo.canRedo.value)

        saveAndLogInsert(block("A", "a"))
        undo.refreshState()
        assertTrue(undo.canUndo.value)
        assertFalse(undo.canRedo.value)

        undo.undo()
        assertFalse(undo.canUndo.value)
        assertTrue(undo.canRedo.value)

        undo.redo()
        assertTrue(undo.canUndo.value)
        assertFalse(undo.canRedo.value)
    }

    @Test
    fun `undo with an empty log is a no-op`() = harness {
        undo.undo()
        undo.redo()
        assertFalse(undo.canUndo.value)
        assertFalse(undo.canRedo.value)
    }

    @Test
    fun `persisted operations are undoable after a simulated restart but redo history is lost`() = harness {
        saveAndLogInsert(block("A", "a"))
        undo.undo()
        assertTrue(undo.canRedo.value)

        // New instance over the same database: undoneOpIds is in-memory only.
        val restarted = restart()
        restarted.refreshState()
        assertTrue(restarted.canUndo.value, "the log persists, so the op is undoable again")
        assertFalse(restarted.canRedo.value, "redo history lived in memory and is gone")

        // The pre-restart log still drives undo on the new instance.
        saveAndLogInsert(block("B", "b"))
        restarted.undo()
        assertNull(get("B"))
    }

    @Test
    fun `other sessions are not undone`() = harness {
        val other = OperationLogger(database, "other-session")
        val b = block("X", "x")
        blocks.saveBlock(b)
        other.logInsert(b)

        undo.undo()
        assertNotNull(get("X"))
    }
}
