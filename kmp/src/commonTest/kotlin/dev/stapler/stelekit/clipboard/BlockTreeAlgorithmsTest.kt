package dev.stapler.stelekit.clipboard

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [BlockTreeAlgorithms] pure tree-traversal algorithms (Epic 6, Task 6.1).
 *
 * All tests operate on plain [Block] lists with no coroutines, state, or repositories.
 */
class BlockTreeAlgorithmsTest {

    private val now = Clock.System.now()

    private fun block(
        uuid: String,
        position: String = "a0",
        parentUuid: String? = null,
        level: Int = 0,
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid("test-page"),
        content = uuid,
        position = position,
        parentUuid = parentUuid?.let { BlockUuid(it) },
        level = level,
        createdAt = now,
        updatedAt = now,
    )

    // ---- collectSubtree tests ----

    @Test
    fun collectSubtree_single_block_no_children() {
        val b = block("b1")
        val byUuid = BlockTreeAlgorithms.indexByUuid(listOf(b))
        val childrenByParent = BlockTreeAlgorithms.indexChildren(listOf(b))

        val result = BlockTreeAlgorithms.collectSubtree("b1", byUuid, childrenByParent)

        assertEquals(listOf(b), result)
    }

    @Test
    fun collectSubtree_two_level_tree() {
        val root = block("root", position = "a0")
        val c1 = block("c1", position = "a0", parentUuid = "root")
        val c2 = block("c2", position = "a1", parentUuid = "root")
        val all = listOf(root, c1, c2)
        val byUuid = BlockTreeAlgorithms.indexByUuid(all)
        val childrenByParent = BlockTreeAlgorithms.indexChildren(all)

        val result = BlockTreeAlgorithms.collectSubtree("root", byUuid, childrenByParent)

        assertEquals(3, result.size)
        assertEquals("root", result[0].uuid.value)
        assertTrue(result.any { it.uuid.value == "c1" })
        assertTrue(result.any { it.uuid.value == "c2" })
    }

    @Test
    fun collectSubtree_deep_tree() {
        val root = block("root", position = "a0")
        val child = block("child", position = "a0", parentUuid = "root")
        val grandchild = block("grandchild", position = "a0", parentUuid = "child")
        val all = listOf(root, child, grandchild)
        val byUuid = BlockTreeAlgorithms.indexByUuid(all)
        val childrenByParent = BlockTreeAlgorithms.indexChildren(all)

        val result = BlockTreeAlgorithms.collectSubtree("root", byUuid, childrenByParent)

        assertEquals(3, result.size)
        assertEquals("root", result[0].uuid.value)
        assertEquals("child", result[1].uuid.value)
        assertEquals("grandchild", result[2].uuid.value)
    }

    @Test
    fun collectSubtree_unknown_uuid_returns_empty() {
        val b = block("b1")
        val byUuid = BlockTreeAlgorithms.indexByUuid(listOf(b))
        val childrenByParent = BlockTreeAlgorithms.indexChildren(listOf(b))

        val result = BlockTreeAlgorithms.collectSubtree("nonexistent", byUuid, childrenByParent)

        assertTrue(result.isEmpty())
    }

    // ---- buildPastedTree tests ----

    @Test
    fun buildPastedTree_single_block() {
        val clipBlock = block("clip1", position = "a0")
        val afterBlock = block("after", position = "a0")
        val uuidMap = mapOf("clip1" to "new-clip1")

        val result = BlockTreeAlgorithms.buildPastedTree(
            clipBlocks = listOf(clipBlock),
            rootBlocks = listOf(clipBlock),
            uuidMap = uuidMap,
            afterBlock = afterBlock,
            insertionParentUuid = null,
            nextSiblingPos = null,
            now = now,
        )

        assertEquals(1, result.size)
        assertNotEquals("clip1", result[0].uuid.value, "Pasted block must have the remapped UUID")
        assertEquals("new-clip1", result[0].uuid.value)
        assertTrue(result[0].position > afterBlock.position, "Pasted block must sort after afterBlock")
    }

    @Test
    fun buildPastedTree_uuid_uniqueness() {
        val clipBlocks = (1..5).map { i -> block("b$i", position = "a${i - 1}") }
        val uuidMap = clipBlocks.associate { it.uuid.value to "new-${it.uuid.value}" }
        val afterBlock = block("after", position = "z0")
        val originalUuids = clipBlocks.map { it.uuid.value }.toSet()

        val result = BlockTreeAlgorithms.buildPastedTree(
            clipBlocks = clipBlocks,
            rootBlocks = clipBlocks,
            uuidMap = uuidMap,
            afterBlock = afterBlock,
            insertionParentUuid = null,
            nextSiblingPos = null,
            now = now,
        )

        val newUuids = result.map { it.uuid.value }
        assertEquals(5, newUuids.toSet().size, "All 5 new UUIDs must be distinct")
        assertTrue(newUuids.none { it in originalUuids }, "New UUIDs must differ from originals")
    }

    @Test
    fun buildPastedTree_level_normalization() {
        val clipRoot = block("clip-root", position = "a0", level = 2)
        val clipChild = block("clip-child", position = "a0", parentUuid = "clip-root", level = 3)
        val clipBlocks = listOf(clipRoot, clipChild)
        val uuidMap = clipBlocks.associate { it.uuid.value to "new-${it.uuid.value}" }
        val afterBlock = block("after", position = "a0", level = 0)

        val result = BlockTreeAlgorithms.buildPastedTree(
            clipBlocks = clipBlocks,
            rootBlocks = listOf(clipRoot),
            uuidMap = uuidMap,
            afterBlock = afterBlock,
            insertionParentUuid = null,
            nextSiblingPos = null,
            now = now,
        )

        val pastedRoot = result.find { it.uuid.value == "new-clip-root" }!!
        val pastedChild = result.find { it.uuid.value == "new-clip-child" }!!
        assertEquals(0, pastedRoot.level, "Root level must normalize to insertionLevel (0)")
        assertEquals(1, pastedChild.level, "Child level must normalize to insertionLevel + 1")
    }

    @Test
    fun buildPastedTree_left_uuid_chain() {
        val r1 = block("r1", position = "a0")
        val r2 = block("r2", position = "a1")
        val clipBlocks = listOf(r1, r2)
        val uuidMap = mapOf("r1" to "new-r1", "r2" to "new-r2")
        val afterBlock = block("after", position = "a0")

        val result = BlockTreeAlgorithms.buildPastedTree(
            clipBlocks = clipBlocks,
            rootBlocks = listOf(r1, r2),
            uuidMap = uuidMap,
            afterBlock = afterBlock,
            insertionParentUuid = null,
            nextSiblingPos = null,
            now = now,
        )

        val pastedR1 = result.find { it.uuid.value == "new-r1" }!!
        val pastedR2 = result.find { it.uuid.value == "new-r2" }!!
        assertEquals(afterBlock.uuid.value, pastedR1.leftUuid?.value, "First root leftUuid must equal afterBlock.uuid")
        assertEquals("new-r1", pastedR2.leftUuid?.value, "Second root leftUuid must equal first root's new UUID")
    }

    // ---- mergeBlocks tests ----

    @Test
    fun mergeBlocks_dirty_block_newer_than_incoming_retains_local() {
        val local = block("b1").copy(content = "local content", version = 2L)
        val incoming = block("b1").copy(content = "db content", version = 1L)
        val dirtyBlocks = mapOf("b1" to 2L)

        val result = BlockTreeAlgorithms.mergeBlocks(
            localBlocks = listOf(local),
            incomingBlocks = listOf(incoming),
            dirtyBlocks = dirtyBlocks,
            pendingNewBlockUuids = emptySet(),
        )

        assertEquals("local content", result.mergedBlocks.first().content)
        assertTrue(result.clearedDirtyUuids.isEmpty())
    }

    @Test
    fun mergeBlocks_clean_block_accepts_incoming_and_clears_dirty() {
        val local = block("b1").copy(content = "local content", version = 1L)
        val incoming = block("b1").copy(content = "db content", version = 2L)
        val dirtyBlocks = mapOf("b1" to 1L)

        val result = BlockTreeAlgorithms.mergeBlocks(
            localBlocks = listOf(local),
            incomingBlocks = listOf(incoming),
            dirtyBlocks = dirtyBlocks,
            pendingNewBlockUuids = emptySet(),
        )

        assertEquals("db content", result.mergedBlocks.first().content)
        assertTrue(result.clearedDirtyUuids.contains("b1"))
    }

    @Test
    fun mergeBlocks_appends_pending_new_blocks() {
        val pendingBlock = block("p1").copy(content = "pending")
        val incoming = block("b1").copy(content = "db content")

        val result = BlockTreeAlgorithms.mergeBlocks(
            localBlocks = listOf(incoming, pendingBlock),
            incomingBlocks = listOf(incoming),
            dirtyBlocks = emptyMap(),
            pendingNewBlockUuids = setOf("p1"),
        )

        assertEquals(2, result.mergedBlocks.size)
        assertEquals("p1", result.mergedBlocks[1].uuid.value)
    }

    @Test
    fun mergeIncomingBlock_appends_new_block_when_absent() {
        val pageBlocks = listOf(block("b1"))
        val incoming = block("b2")

        val result = BlockTreeAlgorithms.mergeIncomingBlock(
            pageBlocks = pageBlocks,
            incoming = incoming,
            dirtyBlocks = emptyMap(),
        )

        assertEquals(2, result.mergedBlocks.size)
        assertEquals("b2", result.mergedBlocks[1].uuid.value)
    }
}
