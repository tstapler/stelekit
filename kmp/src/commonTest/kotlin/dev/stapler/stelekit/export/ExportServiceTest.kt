package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class ExportServiceTest {

    private val now = Clock.System.now()

    private val noOpClipboard = object : ClipboardProvider {
        override fun writeText(text: String) {}
        override fun writeHtml(html: String, plainFallback: String) {}
    }

    private fun block(
        uuid: String,
        content: String,
        level: Int = 0,
        position: String = "a0",
        parentUuid: String? = null,
        pageUuid: String = "page-test"
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        parentUuid = parentUuid?.let { BlockUuid(it) },
        content = content,
        level = level,
        position = position,
        createdAt = now,
        updatedAt = now
    )

    private fun page(uuid: String = "page-test", name: String = "Test Page") = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now,
        updatedAt = now
    )

    /** Creates a service with an empty block repository and the given exporters. */
    private fun serviceWith(vararg exporters: PageExporter): ExportService =
        ExportService(exporters.toList(), noOpClipboard, InMemoryBlockRepository())

    // -------------------------------------------------------------------------
    // U-ES-01: collectBlockRefUuids returns UUIDs from block ref syntax
    // -------------------------------------------------------------------------

    @Test
    fun uES01_collectBlockRefUuids_returnsUuidsFromBlockRefSyntax() {
        val service = serviceWith()
        val blocks = listOf(block("b1", "See ((abc-123)) for details"))
        assertEquals(setOf("abc-123"), service.collectBlockRefUuids(blocks))
    }

    // -------------------------------------------------------------------------
    // U-ES-02: collectBlockRefUuids returns empty set for plain text block
    // -------------------------------------------------------------------------

    @Test
    fun uES02_collectBlockRefUuids_returnsEmptySetForPlainText() {
        val service = serviceWith()
        val blocks = listOf(block("b1", "Just plain text with no block refs"))
        assertTrue(service.collectBlockRefUuids(blocks).isEmpty())
    }

    // -------------------------------------------------------------------------
    // U-ES-03: collectBlockRefUuids aggregates across multiple blocks
    // -------------------------------------------------------------------------

    @Test
    fun uES03_collectBlockRefUuids_aggregatesAcrossMultipleBlocks() {
        val service = serviceWith()
        val blocks = listOf(
            block("b1", "First ref ((uuid-aaa))"),
            block("b2", "Second ref ((uuid-bbb)) and also ((uuid-ccc))"),
            block("b3", "No refs here")
        )
        assertEquals(setOf("uuid-aaa", "uuid-bbb", "uuid-ccc"), service.collectBlockRefUuids(blocks))
    }

    // -------------------------------------------------------------------------
    // U-ES-04: subtreeBlocks with single root returns root and all descendants
    // -------------------------------------------------------------------------

    @Test
    fun uES04_subtreeBlocks_singleRootReturnsRootAndAllDescendants() {
        val service = serviceWith()
        val root = block("root", "Root", level = 0, position = "a0")
        val child = block("child", "Child", level = 1, position = "a0", parentUuid = "root")
        val grandchild = block("grand", "Grandchild", level = 2, position = "a0", parentUuid = "child")
        val sibling = block("sibling", "Sibling", level = 0, position = "a1")

        val allBlocks = listOf(root, child, grandchild, sibling)
        val result = service.subtreeBlocks(allBlocks, setOf("root"))
        val resultUuids = result.map { it.uuid.value }.toSet()

        assertTrue("root" in resultUuids)
        assertTrue("child" in resultUuids)
        assertTrue("grand" in resultUuids)
        assertFalse("sibling" in resultUuids)
        assertEquals(3, result.size)
    }

    // -------------------------------------------------------------------------
    // U-ES-05: subtreeBlocks with leaf root returns only that block
    // -------------------------------------------------------------------------

    @Test
    fun uES05_subtreeBlocks_leafRootReturnsOnlyThatBlock() {
        val service = serviceWith()
        val root = block("root", "Root", level = 0, position = "a0")
        val leaf = block("leaf", "Leaf", level = 1, position = "a0", parentUuid = "root")

        val result = service.subtreeBlocks(listOf(root, leaf), setOf("leaf"))
        val resultUuids = result.map { it.uuid.value }.toSet()

        assertTrue("leaf" in resultUuids)
        assertFalse("root" in resultUuids)
        assertEquals(1, result.size)
    }

    // -------------------------------------------------------------------------
    // U-ES-06: subtreeBlocks with two non-adjacent roots returns both subtrees merged
    // -------------------------------------------------------------------------

    @Test
    fun uES06_subtreeBlocks_twoNonAdjacentRootsReturnsBothSubtreesMerged() {
        val service = serviceWith()
        val rootA = block("rootA", "Root A", level = 0, position = "a0")
        val childA = block("childA", "Child A", level = 1, position = "a0", parentUuid = "rootA")
        val rootB = block("rootB", "Root B", level = 0, position = "a1")
        val childB = block("childB", "Child B", level = 1, position = "a0", parentUuid = "rootB")
        val unrelated = block("unrelated", "Unrelated", level = 0, position = "a2")

        val result = service.subtreeBlocks(
            listOf(rootA, childA, rootB, childB, unrelated),
            setOf("rootA", "rootB")
        )
        val resultUuids = result.map { it.uuid.value }.toSet()

        assertTrue("rootA" in resultUuids)
        assertTrue("childA" in resultUuids)
        assertTrue("rootB" in resultUuids)
        assertTrue("childB" in resultUuids)
        assertFalse("unrelated" in resultUuids)
        assertEquals(4, result.size)
    }

    // -------------------------------------------------------------------------
    // U-ES-07: subtreeBlocks with empty set returns empty list
    // -------------------------------------------------------------------------

    @Test
    fun uES07_subtreeBlocks_emptyRootUuidsReturnsEmptyList() {
        val service = serviceWith()
        val result = service.subtreeBlocks(listOf(block("b1", "Some block")), emptySet())
        assertTrue(result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // U-ES-08: exportToString dispatches to registered exporter by formatId
    // -------------------------------------------------------------------------

    @Test
    fun uES08_exportToString_dispatchesToRegisteredExporterByFormatId() = runTest {
        val stubExporter = object : PageExporter {
            override val formatId = "stub"
            override val displayName = "Stub"
            override fun export(page: Page, blocks: List<Block>, resolvedRefs: Map<String, String>) =
                "stub-output"
        }
        val service = serviceWith(stubExporter)
        val result = service.exportToString(page(), emptyList(), "stub")
        assertTrue(result.isRight())
        assertEquals("stub-output", result.getOrNull())
    }

    // -------------------------------------------------------------------------
    // U-ES-09: exportToString returns failure for unknown formatId
    // -------------------------------------------------------------------------

    @Test
    fun uES09_exportToString_returnsFailureForUnknownFormatId() = runTest {
        val service = serviceWith()
        val result = service.exportToString(page(), emptyList(), "nonexistent-format")
        assertTrue(result.isLeft())
    }

    // -------------------------------------------------------------------------
    // U-ES-10: resolveBlockRefs returns resolved text for known UUID
    // -------------------------------------------------------------------------

    @Test
    fun uES10_resolveBlockRefs_returnsResolvedTextForKnownUuid() = runTest {
        val repo = InMemoryBlockRepository()
        repo.saveBlocks(listOf(block("target-uuid", "The resolved content")))
        val service = ExportService(emptyList(), noOpClipboard, repo)

        val resolved = service.resolveBlockRefs(setOf("target-uuid"))

        assertEquals(mapOf("target-uuid" to "The resolved content"), resolved)
    }

    // -------------------------------------------------------------------------
    // U-ES-11: resolveBlockRefs returns empty map for dangling UUID
    // -------------------------------------------------------------------------

    @Test
    fun uES11_resolveBlockRefs_returnsEmptyMapForDanglingUuid() = runTest {
        val service = serviceWith()
        val resolved = service.resolveBlockRefs(setOf("does-not-exist"))
        assertTrue(resolved.isEmpty())
    }
}
