package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class JsonExporterTest {

    private val now = Clock.System.now()

    private fun makePage(
        uuid: String = "page-uuid",
        name: String = "Test Page",
        isJournal: Boolean = false,
        journalDate: LocalDate? = null,
        properties: Map<String, String> = emptyMap()
    ) = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now,
        updatedAt = now,
        isJournal = isJournal,
        journalDate = journalDate,
        properties = properties
    )

    private fun makeBlock(
        uuid: String,
        content: String,
        level: Int,
        position: String,
        parentUuid: String? = null,
        properties: Map<String, String> = emptyMap()
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid("page-uuid"),
        parentUuid = parentUuid?.let { BlockUuid(it) },
        content = content,
        level = level,
        position = position,
        createdAt = now,
        updatedAt = now,
        properties = properties
    )

    // U-JS-01: output is valid JSON parseable as ExportRoot
    @Test
    fun outputIsValidJson() {
        val page = makePage()
        val block = makeBlock("b1", "Hello world", level = 0, position = "a0")
        val output = JsonExporter().export(page, listOf(block))
        // Must not throw
        Json.decodeFromString<ExportRoot>(output)
    }

    // U-JS-02: ExportRoot.version == 1
    @Test
    fun versionIsOne() {
        val output = JsonExporter().export(makePage(), emptyList())
        val root = Json.decodeFromString<ExportRoot>(output)
        assertEquals(1, root.version)
    }

    // U-JS-03: ExportRoot.exportedAt is not blank
    @Test
    fun exportedAtIsNotBlank() {
        val output = JsonExporter().export(makePage(), emptyList())
        val root = Json.decodeFromString<ExportRoot>(output)
        assertFalse(root.exportedAt.isBlank(), "exportedAt must not be blank")
    }

    // U-JS-04: page metadata included — name and uuid match
    @Test
    fun pageMetadataIncluded() {
        val page = makePage(uuid = "my-page-uuid", name = "Test Page")
        val output = JsonExporter().export(page, emptyList())
        val root = Json.decodeFromString<ExportRoot>(output)
        assertEquals("Test Page", root.page.name)
        assertEquals("my-page-uuid", root.page.uuid)
    }

    // U-JS-05: nested blocks appear as children, not flat — child appears inside blocks[0].children
    @Test
    fun nestedBlocksAppearAsChildren() {
        val page = makePage()
        val parent = makeBlock("parent-uuid", "Parent block", level = 0, position = "a0")
        val child = makeBlock("child-uuid", "Child block", level = 1, position = "a0", parentUuid = "parent-uuid")
        val output = JsonExporter().export(page, listOf(parent, child))
        val root = Json.decodeFromString<ExportRoot>(output)
        assertEquals(1, root.blocks.size, "Only one top-level block expected")
        val parentDto = root.blocks[0]
        assertEquals("parent-uuid", parentDto.uuid)
        assertEquals(1, parentDto.children.size, "Parent must have one child")
        val childDto = parentDto.children[0]
        assertEquals("child-uuid", childDto.uuid)
        assertEquals("parent-uuid", childDto.parentUuid)
    }

    // U-JS-06: block content preserves raw syntax — [[Page]] is preserved verbatim
    @Test
    fun blockContentPreservesRawSyntax() {
        val page = makePage()
        val block = makeBlock("b-raw", "[[Page]]", level = 0, position = "a0")
        val output = JsonExporter().export(page, listOf(block))
        val root = Json.decodeFromString<ExportRoot>(output)
        assertContains(root.blocks[0].content, "[[Page]]")
    }

    // U-JS-07: block properties are included
    @Test
    fun blockPropertiesIncluded() {
        val page = makePage()
        val block = makeBlock(
            uuid = "b-props",
            content = "Block with props",
            level = 0,
            position = "a0",
            properties = mapOf("tag" to "important", "status" to "active")
        )
        val output = JsonExporter().export(page, listOf(block))
        val root = Json.decodeFromString<ExportRoot>(output)
        val props = root.blocks[0].properties
        assertEquals("important", props["tag"])
        assertEquals("active", props["status"])
    }

    // U-JS-08: id key excluded from page properties
    @Test
    fun idKeyExcludedFromPageProperties() {
        val page = makePage(properties = mapOf("id" to "some-uuid", "author" to "Tyler"))
        val output = JsonExporter().export(page, emptyList())
        val root = Json.decodeFromString<ExportRoot>(output)
        assertFalse(root.page.properties.containsKey("id"), "id key must be excluded from page properties")
        assertEquals("Tyler", root.page.properties["author"])
    }

    // U-JS-09: id key excluded from block properties
    @Test
    fun idKeyExcludedFromBlockProperties() {
        val page = makePage()
        val block = makeBlock(
            uuid = "b-id-filter",
            content = "Block",
            level = 0,
            position = "a0",
            properties = mapOf("id" to "block-id-value", "key" to "value")
        )
        val output = JsonExporter().export(page, listOf(block))
        val root = Json.decodeFromString<ExportRoot>(output)
        val props = root.blocks[0].properties
        assertFalse(props.containsKey("id"), "id key must be excluded from block properties")
        assertEquals("value", props["key"])
    }

    // U-JS-10: output is pretty-printed (contains newlines)
    @Test
    fun outputIsPrettyPrinted() {
        val output = JsonExporter().export(makePage(), emptyList())
        assertTrue(output.contains('\n'), "Output must be pretty-printed and contain newlines")
    }

    // U-JS-11: subtree export — passed subset of blocks, ExportRoot.page is full metadata
    @Test
    fun subtreeExportContainsFullPageMetadataAndOnlyPassedBlocks() {
        val page = makePage(uuid = "page-sub", name = "Subtree Page")
        val blockA = makeBlock("sub-a", "Block A", level = 0, position = "a0")
        val blockB = makeBlock("sub-b", "Block B", level = 0, position = "a1")
        // Pass only blockA as the subtree
        val output = JsonExporter().export(page, listOf(blockA))
        val root = Json.decodeFromString<ExportRoot>(output)
        assertEquals("Subtree Page", root.page.name, "Full page metadata must be present")
        assertEquals("page-sub", root.page.uuid)
        assertEquals(1, root.blocks.size, "Only passed blocks must appear")
        assertEquals("sub-a", root.blocks[0].uuid)
    }

    // U-JS-12: journal page — isJournal == true and journalDate is non-null
    @Test
    fun journalPageHasIsJournalTrueAndJournalDate() {
        val date = LocalDate(2024, 3, 15)
        val page = makePage(
            uuid = "journal-page",
            name = "2024-03-15",
            isJournal = true,
            journalDate = date
        )
        val output = JsonExporter().export(page, emptyList())
        val root = Json.decodeFromString<ExportRoot>(output)
        assertTrue(root.page.isJournal, "isJournal must be true for journal page")
        assertNotNull(root.page.journalDate, "journalDate must be non-null for journal page")
    }

    // U-JS-13: block position field matches input order
    @Test
    fun blockPositionMatchesInputOrder() {
        val page = makePage()
        val blockFirst = makeBlock("b-pos-0", "First", level = 0, position = "a0")
        val blockSecond = makeBlock("b-pos-1", "Second", level = 0, position = "a1")
        val blockThird = makeBlock("b-pos-2", "Third", level = 0, position = "a2")
        val output = JsonExporter().export(page, listOf(blockFirst, blockSecond, blockThird))
        val root = Json.decodeFromString<ExportRoot>(output)
        assertEquals(3, root.blocks.size)
        assertEquals("a0", root.blocks[0].position)
        assertEquals("a1", root.blocks[1].position)
        assertEquals("a2", root.blocks[2].position)
    }
}
