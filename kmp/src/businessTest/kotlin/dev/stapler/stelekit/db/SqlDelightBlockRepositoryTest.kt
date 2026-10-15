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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Write-path coverage for [SqlDelightBlockRepository] against a real in-memory SQLite database.
 *
 * Sibling chains follow production convention: a first child's `leftUuid` is its parent's uuid
 * (null for root blocks: left_uuid is a FK to blocks), and every later sibling points at the sibling before it.
 * Positions are explicit so fractional-index output is deterministic.
 */
class SqlDelightBlockRepositoryTest {

    private val page = "page-1"

    private class Fixture(val blocks: SqlDelightBlockRepository, val pages: SqlDelightPageRepository)

    private fun fixture(): Fixture {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val f = Fixture(SqlDelightBlockRepository(database, driver), SqlDelightPageRepository(database))
        runBlocking { f.pages.savePage(Page(uuid = PageUuid(page), name = page, createdAt = now(), updatedAt = now())) }
        return f
    }

    private fun now() = Clock.System.now()

    private fun block(
        uuid: String,
        parent: String? = null,
        left: String? = parent,
        position: String = "a0",
        level: Int = if (parent == null) 0 else 1,
        content: String = "content-$uuid",
        pageUuid: String = page,
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        parentUuid = parent?.let { BlockUuid(it) },
        leftUuid = left?.let { BlockUuid(it) },
        content = content,
        level = level,
        position = position,
        createdAt = now(),
        updatedAt = now(),
    )

    private suspend fun Fixture.get(uuid: String): Block? =
        blocks.getBlockByUuid(BlockUuid(uuid)).first().getOrNull()

    private suspend fun Fixture.childOrder(parent: String?): List<String> =
        blocks.getBlocksForPage(PageUuid(page)).first().getOrNull()!!
            .filter { it.parentUuid?.value == parent }
            .sortedBy { it.position }
            .map { it.uuid.value }

    /** root A -> B (right sibling), both on [page]. */
    private suspend fun Fixture.twoRoots() {
        blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("B", left = "A", position = "a1"),
        ))
    }

    // ---- indent / outdent ----

    @Test
    fun `indentBlock makes block the last child of its previous sibling`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        assertTrue(f.blocks.indentBlock(BlockUuid("B")).isRight())
        val b = f.get("B")!!
        assertEquals("A", b.parentUuid?.value)
        assertEquals(1, b.level)
        assertEquals("A", b.leftUuid?.value)
        assertEquals(listOf("B"), f.childOrder("A"))
    }

    @Test
    fun `indentBlock appends after existing children and repairs the chain`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("A1", parent = "A", position = "a0"),
            block("B", left = "A", position = "a1"),
            block("C", left = "B", position = "a2"),
        ))
        f.blocks.indentBlock(BlockUuid("B"))
        assertEquals(listOf("A1", "B"), f.childOrder("A"))
        assertEquals("A1", f.get("B")!!.leftUuid?.value)
        // C used to follow B at root; it must now follow A.
        assertEquals("A", f.get("C")!!.leftUuid?.value)
    }

    @Test
    fun `indentBlock is a no-op for the first sibling`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        assertTrue(f.blocks.indentBlock(BlockUuid("A")).isRight())
        assertNull(f.get("A")!!.parentUuid)
        assertEquals(0, f.get("A")!!.level)
    }

    @Test
    fun `indentBlock on a missing block is a no-op`() = runBlocking<Unit> {
        val f = fixture()
        assertTrue(f.blocks.indentBlock(BlockUuid("nope")).isRight())
    }

    @Test
    fun `outdentBlock places block right after its parent and shifts descendant levels`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("B", parent = "A", position = "a0"),
            block("B1", parent = "B", level = 2, position = "a0"),
            block("C", parent = "A", left = "B", position = "a1"),
            block("D", left = "A", position = "a1"),
        ))
        assertTrue(f.blocks.outdentBlock(BlockUuid("B")).isRight())

        val b = f.get("B")!!
        assertNull(b.parentUuid)
        assertEquals(0, b.level)
        assertEquals("A", b.leftUuid?.value)
        assertEquals(1, f.get("B1")!!.level)
        assertEquals(listOf("A", "B", "D"), f.childOrder(null))
        // C followed B inside A; now it is A's first child.
        assertEquals("B", f.get("D")!!.leftUuid?.value)
        assertEquals("A", f.get("C")!!.leftUuid?.value)
    }

    @Test
    fun `outdentBlock is a no-op at root level`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        assertTrue(f.blocks.outdentBlock(BlockUuid("B")).isRight())
        assertNull(f.get("B")!!.parentUuid)
        assertEquals("A", f.get("B")!!.leftUuid?.value)
    }

    // ---- moveBlock / up / down ----

    @Test
    fun `moveBlock under a new parent recomputes level and left`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("B", left = "A", position = "a1"),
            block("C", left = "B", position = "a2"),
        ))
        assertTrue(f.blocks.moveBlock(BlockUuid("C"), BlockUuid("A"), "a0").isRight())
        val c = f.get("C")!!
        assertEquals("A", c.parentUuid?.value)
        assertEquals(1, c.level)
        assertEquals("A", c.leftUuid?.value)
        assertEquals(listOf("A", "B"), f.childOrder(null))
    }

    @Test
    fun `moveBlock to a position between siblings rewires neighbours`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("B", left = "A", position = "a2"),
            block("C", left = "B", position = "a4"),
        ))
        f.blocks.moveBlock(BlockUuid("C"), null, "a1")
        assertEquals(listOf("A", "C", "B"), f.childOrder(null))
        assertEquals("A", f.get("C")!!.leftUuid?.value)
        assertEquals("C", f.get("B")!!.leftUuid?.value)
    }

    @Test
    fun `moveBlock to the head of root leaves left null (left_uuid FK targets blocks)`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        val moved = f.blocks.moveBlock(BlockUuid("B"), null, "A")
        assertTrue(moved.isRight(), moved.toString())
        // "A" sorts before "a0": B becomes first.
        assertEquals(listOf("B", "A"), f.childOrder(null))
        assertNull(f.get("B")!!.leftUuid)
        assertEquals("B", f.get("A")!!.leftUuid?.value)
    }

    @Test
    fun `moveBlock of a missing block is a no-op`() = runBlocking<Unit> {
        val f = fixture()
        assertTrue(f.blocks.moveBlock(BlockUuid("nope"), null, "a0").isRight())
    }

    @Test
    fun `moveBlockUp and moveBlockDown swap adjacent siblings`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("B", left = "A", position = "a1"),
            block("C", left = "B", position = "a2"),
        ))
        f.blocks.moveBlockUp(BlockUuid("B"))
        assertEquals(listOf("B", "A", "C"), f.childOrder(null))
        assertEquals("A", f.get("C")!!.leftUuid?.value)

        f.blocks.moveBlockDown(BlockUuid("B"))
        assertEquals(listOf("A", "B", "C"), f.childOrder(null))
        assertEquals("B", f.get("C")!!.leftUuid?.value)
    }

    @Test
    fun `moveBlockUp on first and moveBlockDown on last are no-ops`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        assertTrue(f.blocks.moveBlockUp(BlockUuid("A")).isRight())
        assertTrue(f.blocks.moveBlockDown(BlockUuid("B")).isRight())
        assertTrue(f.blocks.moveBlockUp(BlockUuid("nope")).isRight())
        assertTrue(f.blocks.moveBlockDown(BlockUuid("nope")).isRight())
        assertEquals(listOf("A", "B"), f.childOrder(null))
    }

    // ---- merge / split ----

    @Test
    fun `mergeBlocks concatenates content, reparents children and deletes the second block`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0", content = "foo"),
            block("A1", parent = "A", position = "a0"),
            block("B", left = "A", position = "a1", content = "bar"),
            block("B1", parent = "B", position = "a0"),
            block("C", left = "B", position = "a2"),
        ))
        assertTrue(f.blocks.mergeBlocks(BlockUuid("A"), BlockUuid("B"), " + ").isRight())

        assertEquals("foo + bar", f.get("A")!!.content)
        assertNull(f.get("B"))
        assertEquals("A", f.get("B1")!!.parentUuid?.value)
        assertEquals(listOf("A1", "B1"), f.childOrder("A"))
        assertEquals("A", f.get("C")!!.leftUuid?.value)
    }

    @Test
    fun `mergeBlocks with a missing block changes nothing`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        assertTrue(f.blocks.mergeBlocks(BlockUuid("A"), BlockUuid("nope"), " ").isRight())
        assertEquals("content-A", f.get("A")!!.content)
    }

    @Test
    fun `splitBlock creates a following sibling and repairs the chain`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0", content = "hello world"),
            block("B", left = "A", position = "a1"),
        ))
        val created = f.blocks.splitBlock(BlockUuid("A"), 5).getOrNull()
        assertNotNull(created)
        assertEquals("hello", f.get("A")!!.content)
        assertEquals("world", created.content)
        assertEquals("A", created.leftUuid?.value)
        assertEquals(listOf("A", created.uuid.value, "B"), f.childOrder(null))
        assertEquals(created.uuid.value, f.get("B")!!.leftUuid?.value)
    }

    @Test
    fun `splitBlock honours a caller-supplied uuid`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlock(block("A", content = "ab"))
        val created = f.blocks.splitBlock(BlockUuid("A"), 1, BlockUuid("fixed-uuid")).getOrNull()
        assertEquals("fixed-uuid", created?.uuid?.value)
        assertEquals("b", f.get("fixed-uuid")?.content)
    }

    @Test
    fun `splitBlock of a missing block returns a failure`() = runBlocking<Unit> {
        val f = fixture()
        assertTrue(f.blocks.splitBlock(BlockUuid("nope"), 0).isLeft())
    }

    // ---- batch writes ----

    @Test
    fun `saveBlocksDiff inserts new blocks and updates existing ones in one call`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        val updated = f.get("A")!!.copy(content = "changed", version = 1)
        val result = f.blocks.saveBlocksDiff(
            toInsert = listOf(block("C", left = "B", position = "a2")),
            toUpdate = listOf(updated),
        )
        assertTrue(result.isRight())
        assertEquals("changed", f.get("A")!!.content)
        assertNotNull(f.get("C"))
        assertTrue(f.blocks.saveBlocksDiff(emptyList(), emptyList()).isRight())
    }

    @Test
    fun `saveBlocksAtomicWithChainRepair inserts and repairs in one transaction`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        val newBlock = block("N", left = "A", position = "a05", content = "see [[Some Page]]")
        val repairedB = f.get("B")!!.copy(leftUuid = BlockUuid("N"))
        assertTrue(f.blocks.saveBlocksAtomicWithChainRepair(listOf(newBlock), listOf(repairedB)).isRight())
        assertEquals("N", f.get("B")!!.leftUuid?.value)
        assertEquals(listOf("A", "N", "B"), f.childOrder(null))
        assertTrue(f.blocks.saveBlocksAtomicWithChainRepair(emptyList(), emptyList()).isRight())
    }

    @Test
    fun `saveBlocksUpdate rewrites content of existing blocks`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        f.blocks.saveBlocksUpdate(listOf(f.get("A")!!.copy(content = "u1"), f.get("B")!!.copy(content = "u2")))
        assertEquals("u1", f.get("A")!!.content)
        assertEquals("u2", f.get("B")!!.content)
    }

    // ---- deletes ----

    @Test
    fun `deleteBulk with deleteChildren removes whole subtrees and repairs chain`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("A1", parent = "A", position = "a0"),
            block("A2", parent = "A1", level = 2, position = "a0"),
            block("B", left = "A", position = "a1"),
            block("C", left = "B", position = "a2"),
        ))
        val deleted = f.blocks.deleteBulk(listOf(BlockUuid("A"), BlockUuid("B")), deleteChildren = true)
        assertTrue(deleted.isRight(), deleted.toString())
        assertNull(f.get("A")); assertNull(f.get("A1")); assertNull(f.get("A2")); assertNull(f.get("B"))
        // C's chain must not keep pointing at the deleted B (or A): both predecessors are gone.
        assertNull(f.get("C")!!.leftUuid)
    }

    @Test
    fun `deleteBulk without deleteChildren removes only the listed blocks`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("B", left = "A", position = "a1"),
            block("C", left = "B", position = "a2"),
        ))
        f.blocks.deleteBulk(listOf(BlockUuid("B")), deleteChildren = false)
        assertNull(f.get("B"))
        assertEquals("A", f.get("C")!!.leftUuid?.value)
        assertTrue(f.blocks.deleteBulk(emptyList(), deleteChildren = false).isRight())
    }

    @Test
    fun `deleteBlock handles both child modes and missing blocks`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("A1", parent = "A", position = "a0"),
            block("B", left = "A", position = "a1"),
            block("C", left = "B", position = "a2"),
        ))
        f.blocks.deleteBlock(BlockUuid("A"), deleteChildren = true)
        assertNull(f.get("A")); assertNull(f.get("A1"))
        f.blocks.deleteBlock(BlockUuid("B"), deleteChildren = false)
        assertNull(f.get("B"))
        assertTrue(f.blocks.deleteBlock(BlockUuid("nope"), deleteChildren = false).isRight())
        assertNotNull(f.get("C"))
    }

    @Test
    fun `deleteBlocksForPage deleteBlocksForPages and clear remove blocks`() = runBlocking<Unit> {
        val f = fixture()
        f.pages.savePage(Page(uuid = PageUuid("page-2"), name = "page-2", createdAt = now(), updatedAt = now()))
        f.pages.savePage(Page(uuid = PageUuid("page-3"), name = "page-3", createdAt = now(), updatedAt = now()))
        f.blocks.saveBlocks(listOf(
            block("A"), block("B", pageUuid = "page-2"), block("C", pageUuid = "page-3"),
        ))
        f.blocks.deleteBlocksForPage(PageUuid(page))
        assertNull(f.get("A"))
        f.blocks.deleteBlocksForPages(listOf(PageUuid("page-2")))
        assertNull(f.get("B"))
        assertTrue(f.blocks.deleteBlocksForPages(emptyList()).isRight())
        assertNotNull(f.get("C"))
        f.blocks.clear()
        assertNull(f.get("C"))
    }

    // ---- content-only updates, references ----

    @Test
    fun `updateBlockContentOnly changes content but not position or parent`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(block("A", position = "a0"), block("B", parent = "A", position = "a7")))
        assertTrue(f.blocks.updateBlockContentOnly(BlockUuid("B"), "new [[Target]] text").isRight())
        val b = f.get("B")!!
        assertEquals("new [[Target]] text", b.content)
        assertEquals("a7", b.position)
        assertEquals("A", b.parentUuid?.value)
        val refs = f.blocks.getLinkedReferences("Target").first().getOrNull()!!
        assertEquals(listOf("B"), refs.map { it.uuid.value })
    }

    @Test
    fun `updateBlockContentOnly on a missing block succeeds without creating it`() = runBlocking<Unit> {
        val f = fixture()
        assertTrue(f.blocks.updateBlockContentOnly(BlockUuid("nope"), "x").isRight())
        assertNull(f.get("nope"))
    }

    @Test
    fun `updateBlockContentsForRename moves wikilink references to the new page name`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", content = "see [[Old]]"),
            block("B", left = "A", position = "a1", content = "unrelated"),
        ))
        val result = f.blocks.updateBlockContentsForRename(
            listOf(BlockUuid("A") to "see [[New]]"), "Old", "New",
        )
        assertTrue(result.isRight())
        assertEquals("see [[New]]", f.get("A")!!.content)
        assertEquals("unrelated", f.get("B")!!.content)
        assertTrue(f.blocks.getLinkedReferences("Old").first().getOrNull()!!.isEmpty())
        assertEquals(listOf("A"), f.blocks.getLinkedReferences("New").first().getOrNull()!!.map { it.uuid.value })
    }

    @Test
    fun `updateBlockPropertiesOnly persists properties`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        assertTrue(f.blocks.updateBlockPropertiesOnly(BlockUuid("A"), mapOf("status" to "done")).isRight())
        assertEquals("done", f.get("A")!!.properties["status"])
    }

    @Test
    fun `getLinkedReferences paginates and countLinkedReferences counts`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks((0 until 5).map {
            block("R$it", left = if (it == 0) null else "R${it - 1}", position = "a$it", content = "ref [[Hub]] $it")
        })
        assertEquals(5L, f.blocks.countLinkedReferences("Hub").first().getOrNull())
        val p1 = f.blocks.getLinkedReferences("Hub", limit = 2, offset = 0).first().getOrNull()!!
        val p2 = f.blocks.getLinkedReferences("Hub", limit = 2, offset = 2).first().getOrNull()!!
        val p3 = f.blocks.getLinkedReferences("Hub", limit = 2, offset = 4).first().getOrNull()!!
        assertEquals(listOf(2, 2, 1), listOf(p1.size, p2.size, p3.size))
        assertEquals(5, (p1 + p2 + p3).map { it.uuid }.distinct().size)
    }

    // ---- reads ----

    @Test
    fun `hierarchy reads return children siblings parent and ancestors`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0"),
            block("A1", parent = "A", position = "a0"),
            block("A2", parent = "A", left = "A1", position = "a1"),
            block("A11", parent = "A1", level = 2, position = "a0"),
        ))
        assertEquals(listOf("A1", "A2"), f.blocks.getBlockChildren(BlockUuid("A")).first().getOrNull()!!.map { it.uuid.value })
        assertEquals(listOf("A2"), f.blocks.getBlockSiblings(BlockUuid("A1")).first().getOrNull()!!.map { it.uuid.value })
        assertEquals("A", f.blocks.getBlockParent(BlockUuid("A1")).first().getOrNull()?.uuid?.value)
        assertNull(f.blocks.getBlockParent(BlockUuid("A")).first().getOrNull())
        val ancestors = f.blocks.getBlockAncestors(BlockUuid("A11")).first().getOrNull()!!.map { it.uuid.value }
        assertEquals(setOf("A", "A1"), ancestors.toSet())
        val hierarchy = f.blocks.getBlockHierarchy(BlockUuid("A")).first().getOrNull()!!
        assertEquals(4, hierarchy.size)
    }

    @Test
    fun `batch reads count and fetch by uuid`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        assertEquals(2, f.blocks.countBlocksForPages(listOf(PageUuid(page))).getOrNull()?.get(PageUuid(page)))
        val got = f.blocks.getBlocksByUuids(listOf(BlockUuid("A"), BlockUuid("B"), BlockUuid("nope"))).getOrNull()!!
        assertEquals(setOf("A", "B"), got.map { it.uuid.value }.toSet())
    }

    @Test
    fun `searchBlocksByContent and findBlocksWithTaskMarker`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("A", position = "a0", content = "alpha needle"),
            block("B", left = "A", position = "a1", content = "TODO write tests"),
            block("C", left = "B", position = "a2", content = "DONE shipped"),
        ))
        assertEquals(listOf("A"), f.blocks.searchBlocksByContent("needle", 10, 0).first().getOrNull()!!.map { it.uuid.value })
        val todo = f.blocks.findBlocksWithTaskMarker(setOf("TODO"), 10, 0).first().getOrNull()!!
        assertEquals(listOf("B"), todo.map { it.uuid.value })
        assertTrue(f.blocks.findBlocksWithTaskMarker(emptySet(), 10, 0).first().getOrNull()!!.isEmpty())
    }

    @Test
    fun `reads after cache eviction still return the stored block`() = runBlocking<Unit> {
        val f = fixture(); f.twoRoots()
        f.get("A")
        f.blocks.cacheEvictPage(PageUuid(page))
        f.blocks.cacheEvictAll()
        assertNotNull(f.get("A"))
        assertEquals("content-A", f.get("A")!!.content)
    }

    private suspend fun Fixture.referenceFixture() {
        blocks.saveBlocks(listOf(
            block("L1", content = "see [[Target]] for details", position = "a0"),
            block("L2", left = "L1", content = "tagged #Target here", position = "a1"),
            block("L3", left = "L2", content = "aliased [[Target|the target]]", position = "a2"),
            block("U1", left = "L3", content = "Target mentioned in plain text", position = "a3"),
            block("N1", left = "U1", content = "nothing relevant", position = "a4"),
        ))
    }

    @Test
    fun `linked references match wikilinks hashtags and aliases but not plain mentions`() = runBlocking<Unit> {
        val f = fixture(); f.referenceFixture()
        val all = f.blocks.getLinkedReferences("Target").first().getOrNull()!!.map { it.uuid.value }.toSet()
        assertEquals(setOf("L1", "L2", "L3"), all)
        val paged = f.blocks.getLinkedReferences("Target", limit = 2, offset = 0).first().getOrNull()!!
        assertEquals(2, paged.size)
        assertTrue(paged.all { it.uuid.value.startsWith("L") })
        assertEquals(3L, f.blocks.countLinkedReferences("Target").first().getOrNull())
        assertTrue(f.blocks.getLinkedReferences("Absent").first().getOrNull()!!.isEmpty())
        assertEquals(0L, f.blocks.countLinkedReferences("Absent").first().getOrNull())
    }

    @Test
    fun `unlinked references exclude only wikilinks so a hashtag mention still counts`() = runBlocking<Unit> {
        val f = fixture(); f.referenceFixture()
        // Only [[name]] / [[name|alias]] count as linked here; "#Target" (L2) is still "unlinked".
        val expected = setOf("L2", "U1")
        assertEquals(expected, f.blocks.getUnlinkedReferences("Target").first().getOrNull()!!.map { it.uuid.value }.toSet())
        assertEquals(expected, f.blocks.getUnlinkedReferences("Target", limit = 10, offset = 0).first().getOrNull()!!.map { it.uuid.value }.toSet())
        assertTrue(f.blocks.getUnlinkedReferences("Absent", limit = 10, offset = 0).first().getOrNull()!!.isEmpty())
    }

    @Test
    fun `hierarchy ancestor parent and sibling reads handle repeat reads and missing blocks`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("R", position = "a0"),
            block("C", parent = "R", position = "a0"),
            block("G", parent = "C", level = 2, position = "a0"),
        ))
        // hierarchy: miss, then cache hit, then unknown root
        val first = f.blocks.getBlockHierarchy(BlockUuid("R")).first().getOrNull()!!
        val second = f.blocks.getBlockHierarchy(BlockUuid("R")).first().getOrNull()!!
        assertEquals(listOf("R", "C", "G"), first.map { it.block.uuid.value })
        assertEquals(first.map { it.depth }, second.map { it.depth })
        assertTrue(f.blocks.getBlockHierarchy(BlockUuid("nope")).first().getOrNull()!!.isEmpty())

        // ancestors: nested, cached, root (none), unknown
        val anc = f.blocks.getBlockAncestors(BlockUuid("G")).first().getOrNull()!!
        assertEquals(listOf("R", "C"), anc.map { it.uuid.value })
        assertEquals(listOf("R", "C"), f.blocks.getBlockAncestors(BlockUuid("G")).first().getOrNull()!!.map { it.uuid.value })
        assertTrue(f.blocks.getBlockAncestors(BlockUuid("R")).first().getOrNull()!!.isEmpty())
        assertTrue(f.blocks.getBlockAncestors(BlockUuid("nope")).first().getOrNull()!!.isEmpty())

        // parent / siblings / children edge cases
        assertNull(f.blocks.getBlockParent(BlockUuid("R")).first().getOrNull())
        assertNull(f.blocks.getBlockParent(BlockUuid("nope")).first().getOrNull())
        assertEquals("R", f.blocks.getBlockParent(BlockUuid("C")).first().getOrNull()!!.uuid.value)
        assertTrue(f.blocks.getBlockSiblings(BlockUuid("nope")).first().getOrNull()!!.isEmpty())
        assertTrue(f.blocks.getBlockChildren(BlockUuid("G")).first().getOrNull()!!.isEmpty())
        assertNull(f.get("nope"))
    }

    @Test
    fun `moves and deletes keep hierarchy reads fresh after the cache was warmed`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("P", position = "a0"),
            block("Q", left = "P", position = "a1"),
            block("Q1", parent = "Q", position = "a0"),
        ))
        f.blocks.getBlockHierarchy(BlockUuid("Q")).first()
        f.blocks.getBlockAncestors(BlockUuid("Q1")).first()
        f.blocks.moveBlock(BlockUuid("Q1"), BlockUuid("P"), "a0")
        assertEquals("P", f.blocks.getBlockParent(BlockUuid("Q1")).first().getOrNull()!!.uuid.value)
        assertEquals(listOf("P"), f.blocks.getBlockAncestors(BlockUuid("Q1")).first().getOrNull()!!.map { it.uuid.value })
        f.blocks.deleteBlock(BlockUuid("Q"), deleteChildren = true)
        assertTrue(f.blocks.getBlockHierarchy(BlockUuid("Q")).first().getOrNull()!!.isEmpty())
    }

    @Test
    fun `findDuplicateBlocks groups identical content and ignores unique blocks`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(
            block("D1", content = "same text", position = "a0"),
            block("D2", left = "D1", content = "same text", position = "a1"),
            block("D3", left = "D2", content = "different text", position = "a2"),
        ))
        val groups = f.blocks.findDuplicateBlocks(10).first().getOrNull()!!
        assertEquals(1, groups.size)
        assertEquals(setOf("D1", "D2"), groups.single().blocks.map { it.uuid.value }.toSet())
    }

    @Test
    fun `cache eviction and maintenance calls leave stored blocks intact`() = runBlocking<Unit> {
        val f = fixture()
        f.blocks.saveBlocks(listOf(block("E1", position = "a0"), block("E2", parent = "E1", position = "a0")))
        f.blocks.getBlockHierarchy(BlockUuid("E1")).first()
        f.blocks.getBlockAncestors(BlockUuid("E2")).first()
        f.blocks.evictBlock("E1")
        f.blocks.cacheEvictPage(PageUuid(page))
        f.blocks.evictHierarchyForPage("unknown-page")
        f.blocks.cacheEvictAll()
        assertEquals(2, f.blocks.getBlocksForPage(PageUuid(page)).first().getOrNull()!!.size)
        f.blocks.walCheckpoint()
        f.blocks.compactFtsIndex()
    }

    @Test
    fun `reactive referencing blocks emits matches and clear empties the table`() = runBlocking<Unit> {
        val f = fixture(); f.referenceFixture()
        val hits = f.blocks.findReferencingBlocksReactive("Target", limit = 10, offset = 0).first().getOrNull()!!
        assertEquals(setOf("L1", "L2", "L3"), hits.map { it.uuid.value }.toSet())
        assertTrue(f.blocks.findReferencingBlocksReactive("Absent", limit = 10, offset = 0).first().getOrNull()!!.isEmpty())
        assertTrue(f.blocks.clear().isRight())
        assertTrue(f.blocks.getBlocksForPage(PageUuid(page)).first().getOrNull()!!.isEmpty())
    }
}
