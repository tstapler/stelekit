package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class ExportIntegrationTest {

    private val now = Clock.System.now()

    // -------------------------------------------------------------------------
    // Test 1: Markdown export
    // -------------------------------------------------------------------------

    @Test
    fun testMarkdownExportProducesCleanOutput() {
        val page = Page(
            uuid = "page-md",
            name = "My Notes",
            createdAt = now,
            updatedAt = now,
            properties = mapOf("id" to "some-uuid", "author" to "Tyler")
        )

        val blocks = listOf(
            Block(
                uuid = "block-1",
                pageUuid = "page-md",
                content = "[[WikiLink]] is a cool page",
                level = 0,
                position = 0,
                createdAt = now,
                updatedAt = now
            ),
            Block(
                uuid = "block-2",
                pageUuid = "page-md",
                content = "((ref-uuid))",
                level = 1,
                position = 1,
                parentUuid = "block-1",
                createdAt = now,
                updatedAt = now
            ),
            Block(
                uuid = "block-3",
                pageUuid = "page-md",
                content = "key:: value",
                level = 1,
                position = 2,
                parentUuid = "block-1",
                createdAt = now,
                updatedAt = now
            )
        )

        val output = MarkdownExporter().export(
            page,
            blocks,
            resolvedRefs = mapOf("ref-uuid" to "referenced text")
        )

        // Heading must be present
        assertContains(output, "# My Notes")

        // Block ref should be resolved — raw ((ref-uuid)) must not appear
        assertFalse(output.contains("((ref-uuid))"), "Raw block-ref syntax must be resolved")
        assertContains(output, "referenced text")

        // YAML frontmatter: author present, id filtered out
        assertContains(output, "author: Tyler")
        assertFalse(output.contains("id:"), "The 'id' property must be filtered from frontmatter")

        // The inline "key:: value" text is valid block content; exporter should not strip it
        // from the body (the exporter doesn't strip property syntax from content — only
        // blank property-only blocks are skipped). Assert it appears as-is in the body.
        assertContains(output, "key:: value")
    }

    // -------------------------------------------------------------------------
    // Test 2: Plain-text export strips all markup
    // -------------------------------------------------------------------------

    @Test
    fun testPlainTextExportStripsAllMarkup() {
        val page = Page(
            uuid = "page-pt",
            name = "Plain Page",
            createdAt = now,
            updatedAt = now
        )

        val blocks = listOf(
            Block(
                uuid = "b-pt-1",
                pageUuid = "page-pt",
                content = "**bold** and *italic* text",
                level = 0,
                position = 0,
                createdAt = now,
                updatedAt = now
            ),
            Block(
                uuid = "b-pt-2",
                pageUuid = "page-pt",
                content = "[[Link Name]] and ==highlight==",
                level = 0,
                position = 1,
                createdAt = now,
                updatedAt = now
            )
        )

        val output = PlainTextExporter().export(page, blocks, resolvedRefs = emptyMap())

        // Markup tokens must be absent
        assertFalse(output.contains("**"), "Bold markers must be stripped")
        assertFalse(output.contains("[["), "WikiLink open brackets must be stripped")
        assertFalse(output.contains("]]"), "WikiLink close brackets must be stripped")
        assertFalse(output.contains("==highlight=="), "Highlight markers must be stripped")

        // Plain text content must be present
        assertContains(output, "bold")
        assertContains(output, "italic")
        assertContains(output, "Link Name")
        assertContains(output, "highlight")

        // Italic asterisks: *italic* — after stripping, no lone * should remain
        // The PlainTextExporter strips ItalicNode markers, so no * in output
        assertFalse(output.contains("*"), "Italic markers must be stripped")
    }

    // -------------------------------------------------------------------------
    // Test 3: HTML export escapes code-block content
    // -------------------------------------------------------------------------

    @Test
    fun testHtmlExportEscapesCodeBlocks() {
        val page = Page(
            uuid = "page-html",
            name = "Code Page",
            createdAt = now,
            updatedAt = now
        )

        // A block with a newline triggers the code-fence path in HtmlExporter
        val scriptContent = "<script>alert(1)</script>\nmore content"
        val block = Block(
            uuid = "b-html-1",
            pageUuid = "page-html",
            content = scriptContent,
            level = 0,
            position = 0,
            createdAt = now,
            updatedAt = now
        )

        val output = HtmlExporter().export(page, listOf(block))

        assertContains(output, "&lt;script&gt;", message = "Script tag must be HTML-escaped")
        assertFalse(output.contains("<script>"), "Literal <script> must not appear in output")
    }

    // -------------------------------------------------------------------------
    // Test 4: JSON export round-trips through kotlinx.serialization
    // -------------------------------------------------------------------------

    @Test
    fun testJsonExportIsValidJson() {
        val pageName = "JSON Test Page"
        val page = Page(
            uuid = "page-json",
            name = pageName,
            createdAt = now,
            updatedAt = now
        )

        val rootBlock = Block(
            uuid = "b-json-root",
            pageUuid = "page-json",
            content = "Root block",
            level = 0,
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        val childA = Block(
            uuid = "b-json-childA",
            pageUuid = "page-json",
            parentUuid = "b-json-root",
            content = "Child A",
            level = 1,
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        val childB = Block(
            uuid = "b-json-childB",
            pageUuid = "page-json",
            parentUuid = "b-json-root",
            content = "Child B",
            level = 1,
            position = 1,
            createdAt = now,
            updatedAt = now
        )

        val output = JsonExporter().export(page, listOf(rootBlock, childA, childB))

        val root = Json.decodeFromString<ExportRoot>(output)

        assertEquals(1, root.version, "version must be 1")
        assertEquals(pageName, root.page.name, "page name must round-trip correctly")
        assertTrue(root.blocks.isNotEmpty(), "blocks list must not be empty")
        assertNotNull(root.exportedAt, "exportedAt must be present")
    }

    // -------------------------------------------------------------------------
    // Test 5: Subtree export filters correctly
    // -------------------------------------------------------------------------

    @Test
    fun testSubtreeExportFiltersCorrectly() {
        val pageUuid = "page-sub"

        // Block tree:
        //   root (level 0, pos 0)
        //     childA (level 1, pos 0, parent=root)
        //       grandchild (level 2, pos 0, parent=childA)
        //     childB (level 1, pos 1, parent=root)
        //   sibling (level 0, pos 1)  ← different root
        val root = Block(
            uuid = "sub-root",
            pageUuid = pageUuid,
            content = "Root",
            level = 0,
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        val childA = Block(
            uuid = "sub-childA",
            pageUuid = pageUuid,
            parentUuid = "sub-root",
            content = "Child A",
            level = 1,
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        val grandchild = Block(
            uuid = "sub-grand",
            pageUuid = pageUuid,
            parentUuid = "sub-childA",
            content = "Grandchild",
            level = 2,
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        val childB = Block(
            uuid = "sub-childB",
            pageUuid = pageUuid,
            parentUuid = "sub-root",
            content = "Child B",
            level = 1,
            position = 1,
            createdAt = now,
            updatedAt = now
        )
        val sibling = Block(
            uuid = "sub-sibling",
            pageUuid = pageUuid,
            content = "Sibling root",
            level = 0,
            position = 1,
            createdAt = now,
            updatedAt = now
        )

        val allBlocks = listOf(root, childA, grandchild, childB, sibling)

        val service = ExportService(
            exporters = emptyList(),
            clipboard = object : ClipboardProvider {
                override fun writeText(text: String) {}
                override fun writeHtml(html: String, plainFallback: String) {}
            },
            blockRepository = FakeBlockRepository(mapOf(pageUuid to allBlocks))
        )

        val result = service.subtreeBlocks(allBlocks, setOf("sub-childA"))
        val resultUuids = result.map { it.uuid }.toSet()

        assertTrue("sub-childA" in resultUuids, "childA must be in subtree result")
        assertTrue("sub-grand" in resultUuids, "grandchild must be in subtree result")

        assertFalse("sub-root" in resultUuids, "root must NOT be in subtree result")
        assertFalse("sub-childB" in resultUuids, "childB must NOT be in subtree result")
        assertFalse("sub-sibling" in resultUuids, "sibling root must NOT be in subtree result")
    }
}
