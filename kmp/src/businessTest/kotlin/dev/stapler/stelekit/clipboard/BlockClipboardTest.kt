package dev.stapler.stelekit.clipboard

import dev.stapler.stelekit.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class BlockClipboardTest {

    private val now = Clock.System.now()

    private fun makeBlock(uuid: String, content: String = "content"): Block = Block(
        uuid = uuid,
        pageUuid = "page-1",
        content = content,
        level = 0,
        position = 0,
        createdAt = now,
        updatedAt = now
    )

    // ── isEmpty ───────────────────────────────────────────────────────────────

    @Test
    fun newClipboard_isEmpty() {
        assertTrue(BlockClipboard().isEmpty)
    }

    @Test
    fun clipboardWithBlock_isNotEmpty() {
        val clipboard = BlockClipboard().withBlock(makeBlock("block-1"), ClipboardOperation.COPY, "graph-1")
        assertFalse(clipboard.isEmpty)
    }

    // ── withBlock replaces previous contents ──────────────────────────────────

    @Test
    fun withBlock_replacesPreviousEntry() {
        val block1 = makeBlock("block-1", "first")
        val block2 = makeBlock("block-2", "second")
        val clipboard = BlockClipboard()
            .withBlock(block1, ClipboardOperation.COPY, "graph-1")
            .withBlock(block2, ClipboardOperation.CUT, "graph-1")
        assertEquals(1, clipboard.entries.size)
        assertEquals(block2, clipboard.entries.first().block)
    }

    @Test
    fun withBlock_storesOperationAndGraphUuid() {
        val block = makeBlock("block-1")
        val clipboard = BlockClipboard().withBlock(block, ClipboardOperation.CUT, "graph-42")
        val entry = clipboard.entries.first()
        assertEquals(ClipboardOperation.CUT, entry.operation)
        assertEquals("graph-42", entry.sourceGraphUuid)
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun clear_producesEmptyClipboard() {
        val clipboard = BlockClipboard()
            .withBlock(makeBlock("block-1"), ClipboardOperation.COPY, "graph-1")
            .clear()
        assertTrue(clipboard.isEmpty)
        assertEquals(0, clipboard.entries.size)
    }

    // ── isCut ─────────────────────────────────────────────────────────────────

    @Test
    fun isCut_trueWhenTopmostEntryIsCut() {
        val clipboard = BlockClipboard().withBlock(makeBlock("block-1"), ClipboardOperation.CUT, "graph-1")
        assertTrue(clipboard.isCut)
    }

    @Test
    fun isCut_falseWhenTopmostEntryIsCopy() {
        val clipboard = BlockClipboard().withBlock(makeBlock("block-1"), ClipboardOperation.COPY, "graph-1")
        assertFalse(clipboard.isCut)
    }

    @Test
    fun isCut_falseOnEmptyClipboard() {
        assertFalse(BlockClipboard().isCut)
    }
}
