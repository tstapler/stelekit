package dev.stapler.stelekit.ui.screens.git

import kotlin.test.Test
import kotlin.test.assertEquals

class HunkBlockPickerTest {
    private val local = listOf("- Rampart link", "  | https://x", "", "  ---", "- second")
    private val remote = listOf("- Kyrgies", "  - Created Pages", "    - [[Kyrgies]]")

    @Test
    fun `blocks start at each bullet and own continuation lines`() {
        val blocks = splitIntoBlocks(local)
        assertEquals(2, blocks.size)
        assertEquals(listOf("- Rampart link", "  | https://x", "", "  ---"), blocks[0])
        assertEquals(3, splitIntoBlocks(remote).size)
    }

    @Test
    fun `keep both composes local then remote`() {
        val l = splitIntoBlocks(local)
        val r = splitIntoBlocks(remote)
        val text = composeResult(l, r, BlockPicks.both(l.size, r.size))
        assertEquals((local + remote).joinToString("\n"), text)
    }

    @Test
    fun `toggle drops a single block and none yields empty`() {
        val l = splitIntoBlocks(local)
        val r = splitIntoBlocks(remote)
        val picks = BlockPicks.both(l.size, r.size).toggleLocal(0).toggleRemote(1).toggleRemote(2)
        assertEquals("- second\n- Kyrgies", composeResult(l, r, picks))
        assertEquals("", composeResult(l, r, BlockPicks.NONE))
    }

    @Test
    fun `identical block on both sides is emitted once`() {
        val shared = splitIntoBlocks(listOf("- same"))
        assertEquals("- same", composeResult(shared, shared, BlockPicks.both(1, 1)))
    }
}
