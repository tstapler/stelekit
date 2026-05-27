package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.model.ParsedPage
import dev.stapler.stelekit.parsing.ParseMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class MarkdownPageParserTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val fixedNow: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun parsedBlock(
        content: String = "hello",
        properties: Map<String, String> = emptyMap(),
        scheduled: String? = null,
        deadline: String? = null,
        children: List<ParsedBlock> = emptyList(),
    ): ParsedBlock = ParsedBlock(
        content = content,
        properties = properties,
        level = 0,
        children = children,
        scheduled = scheduled,
        deadline = deadline,
        blockType = BlockType.Bullet,
    )

    private fun emptyParsedPage(): ParsedPage =
        ParsedPage(title = null, properties = emptyMap(), blocks = emptyList())

    private fun pageWithFirstPropertyBlock(props: Map<String, String>): ParsedPage {
        val propertyBlock = parsedBlock(content = "  ", properties = props)
        return ParsedPage(title = null, properties = emptyMap(), blocks = listOf(propertyBlock))
    }

    private fun minimalExistingPage(uuid: String = "00000000-0000-0000-0000-000000000001"): Page =
        Page(
            uuid = uuid,
            name = "test-page",
            createdAt = fixedNow,
            updatedAt = fixedNow,
            version = 1L,
        )

    // -------------------------------------------------------------------------
    // generateUuid
    // -------------------------------------------------------------------------

    @Test
    fun generateUuid_returns_id_property_when_present() {
        val block = parsedBlock(properties = mapOf("id" to "explicit-uuid-abc"))
        val result = MarkdownPageParser.generateUuid(block, "/graph/pages/note.md", 0)
        assertEquals("explicit-uuid-abc", result)
    }

    @Test
    fun generateUuid_ignores_blank_id_property_and_uses_deterministic_hash() {
        val block = parsedBlock(properties = mapOf("id" to "  "))
        val result = MarkdownPageParser.generateUuid(block, "/graph/pages/note.md", 0)
        // Must not be blank and must not be the whitespace string
        assertNotEquals("  ", result)
        assertTrue(result.isNotBlank())
    }

    @Test
    fun generateUuid_is_deterministic_from_path_and_index() {
        val block = parsedBlock()
        val path = "/graph/pages/note.md"
        val first = MarkdownPageParser.generateUuid(block, path, 3)
        val second = MarkdownPageParser.generateUuid(block, path, 3)
        assertEquals(first, second)
    }

    @Test
    fun generateUuid_differs_for_different_indices() {
        val block = parsedBlock()
        val path = "/graph/pages/note.md"
        val uuid0 = MarkdownPageParser.generateUuid(block, path, 0)
        val uuid1 = MarkdownPageParser.generateUuid(block, path, 1)
        assertNotEquals(uuid0, uuid1)
    }

    @Test
    fun generateUuid_differs_for_different_parent_uuids() {
        val block = parsedBlock()
        val path = "/graph/pages/note.md"
        val uuidNoParent = MarkdownPageParser.generateUuid(block, path, 0, parentUuid = null)
        val uuidWithParent = MarkdownPageParser.generateUuid(block, path, 0, parentUuid = "some-parent-uuid")
        assertNotEquals(uuidNoParent, uuidWithParent)
    }

    // -------------------------------------------------------------------------
    // processParsedBlocks — version tracking
    // -------------------------------------------------------------------------

    @Test
    fun processParsedBlocks_version_unchanged_when_content_identical() {
        val block = parsedBlock(content = "same content")
        val path = "/graph/pages/note.md"
        val blockUuid = MarkdownPageParser.generateUuid(block, path, 0)

        val existingVersions = mapOf(blockUuid to 5L)
        val existingContent = mapOf(blockUuid to "same content")

        val destination = mutableListOf<dev.stapler.stelekit.model.Block>()
        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = listOf(block),
            pagePath = path,
            pageUuid = "00000000-0000-0000-0000-000000000001",
            parentUuid = null,
            baseLevel = 0,
            now = fixedNow,
            destinationList = destination,
            mode = ParseMode.FULL,
            existingVersions = existingVersions,
            existingContent = existingContent,
        )

        assertEquals(1, destination.size)
        assertEquals(5L, destination[0].version, "Version must not change when content is identical")
    }

    @Test
    fun processParsedBlocks_version_incremented_when_content_changes_and_prior_version_positive() {
        val block = parsedBlock(content = "updated content")
        val path = "/graph/pages/note.md"
        val blockUuid = MarkdownPageParser.generateUuid(block, path, 0)

        val existingVersions = mapOf(blockUuid to 3L)
        val existingContent = mapOf(blockUuid to "original content")

        val destination = mutableListOf<dev.stapler.stelekit.model.Block>()
        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = listOf(block),
            pagePath = path,
            pageUuid = "00000000-0000-0000-0000-000000000001",
            parentUuid = null,
            baseLevel = 0,
            now = fixedNow,
            destinationList = destination,
            mode = ParseMode.FULL,
            existingVersions = existingVersions,
            existingContent = existingContent,
        )

        assertEquals(1, destination.size)
        assertEquals(4L, destination[0].version, "Version must be incremented when content changes and prior version > 0")
    }

    @Test
    fun processParsedBlocks_version_stays_zero_when_prior_version_is_zero_and_content_changes() {
        val block = parsedBlock(content = "new content")
        val path = "/graph/pages/note.md"
        val blockUuid = MarkdownPageParser.generateUuid(block, path, 0)

        val existingVersions = mapOf(blockUuid to 0L)
        val existingContent = mapOf(blockUuid to "old content")

        val destination = mutableListOf<dev.stapler.stelekit.model.Block>()
        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = listOf(block),
            pagePath = path,
            pageUuid = "00000000-0000-0000-0000-000000000001",
            parentUuid = null,
            baseLevel = 0,
            now = fixedNow,
            destinationList = destination,
            mode = ParseMode.FULL,
            existingVersions = existingVersions,
            existingContent = existingContent,
        )

        assertEquals(1, destination.size)
        assertEquals(0L, destination[0].version, "Version must remain 0 when prior version is 0, even if content changed")
    }

    // -------------------------------------------------------------------------
    // buildPageModel — returning PageBuildResult
    // -------------------------------------------------------------------------

    @Test
    fun buildPageModel_uses_fileModTime_when_non_null_and_non_zero() {
        val fileModTime = 1_600_000_000_000L
        val result = MarkdownPageParser.buildPageModel(
            filePath = "/graph/pages/note.md",
            name = "note",
            isJournal = false,
            journalDate = null,
            existingPage = null,
            now = fixedNow,
            mode = ParseMode.FULL,
            parsedPage = emptyParsedPage(),
            fileModTime = fileModTime,
        )

        assertEquals(
            Instant.fromEpochMilliseconds(fileModTime),
            result.page.updatedAt,
            "updatedAt must reflect fileModTime when it is non-null and non-zero",
        )
    }

    @Test
    fun buildPageModel_falls_back_to_now_when_fileModTime_is_null() {
        val result = MarkdownPageParser.buildPageModel(
            filePath = "/graph/pages/note.md",
            name = "note",
            isJournal = false,
            journalDate = null,
            existingPage = null,
            now = fixedNow,
            mode = ParseMode.FULL,
            parsedPage = emptyParsedPage(),
            fileModTime = null,
        )

        assertEquals(fixedNow, result.page.updatedAt, "updatedAt must fall back to 'now' when fileModTime is null")
    }

    @Test
    fun buildPageModel_falls_back_to_now_when_fileModTime_is_zero() {
        val result = MarkdownPageParser.buildPageModel(
            filePath = "/graph/pages/note.md",
            name = "note",
            isJournal = false,
            journalDate = null,
            existingPage = null,
            now = fixedNow,
            mode = ParseMode.FULL,
            parsedPage = emptyParsedPage(),
            fileModTime = 0L,
        )

        assertEquals(fixedNow, result.page.updatedAt, "updatedAt must fall back to 'now' when fileModTime is zero")
    }

    @Test
    fun buildPageModel_isJournal_true_when_passed_true() {
        val journalDate = LocalDate(2024, 1, 15)
        val result = MarkdownPageParser.buildPageModel(
            filePath = "/graph/journals/2024-01-15.md",
            name = "2024-01-15",
            isJournal = true,
            journalDate = journalDate,
            existingPage = null,
            now = fixedNow,
            mode = ParseMode.FULL,
            parsedPage = emptyParsedPage(),
            fileModTime = null,
        )

        assertTrue(result.page.isJournal, "isJournal must be true when passed as true")
        assertEquals(journalDate, result.page.journalDate)
    }

    @Test
    fun buildPageModel_firstBlockSkipped_false_when_no_blocks() {
        val result = MarkdownPageParser.buildPageModel(
            filePath = "/graph/pages/note.md",
            name = "note",
            isJournal = false,
            journalDate = null,
            existingPage = null,
            now = fixedNow,
            mode = ParseMode.FULL,
            parsedPage = emptyParsedPage(),
            fileModTime = null,
        )

        assertEquals(false, result.firstBlockSkipped)
    }

    @Test
    fun buildPageModel_firstBlockSkipped_true_when_first_block_is_property_block() {
        val parsedPage = pageWithFirstPropertyBlock(mapOf("alias" to "my-alias"))
        val result = MarkdownPageParser.buildPageModel(
            filePath = "/graph/pages/note.md",
            name = "note",
            isJournal = false,
            journalDate = null,
            existingPage = null,
            now = fixedNow,
            mode = ParseMode.FULL,
            parsedPage = parsedPage,
            fileModTime = null,
        )

        assertTrue(result.firstBlockSkipped, "firstBlockSkipped must be true when first block has properties and empty content")
        assertEquals(mapOf("alias" to "my-alias"), result.page.properties)
    }

    // -------------------------------------------------------------------------
    // mergedProperties (exercised indirectly via processParsedBlocks)
    // -------------------------------------------------------------------------

    @Test
    fun mergedProperties_includes_scheduled_when_present() {
        val block = parsedBlock(
            content = "Do something",
            properties = mapOf("priority" to "A"),
            scheduled = "2024-01-20",
        )
        val path = "/graph/pages/note.md"
        val destination = mutableListOf<dev.stapler.stelekit.model.Block>()

        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = listOf(block),
            pagePath = path,
            pageUuid = "00000000-0000-0000-0000-000000000001",
            parentUuid = null,
            baseLevel = 0,
            now = fixedNow,
            destinationList = destination,
            mode = ParseMode.FULL,
        )

        val props = destination[0].properties
        assertEquals("2024-01-20", props["scheduled"], "scheduled must be merged into block properties")
        assertEquals("A", props["priority"], "existing properties must be preserved")
    }

    @Test
    fun mergedProperties_includes_deadline_when_present() {
        val block = parsedBlock(
            content = "Finish this",
            properties = emptyMap(),
            deadline = "2024-02-28",
        )
        val path = "/graph/pages/note.md"
        val destination = mutableListOf<dev.stapler.stelekit.model.Block>()

        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = listOf(block),
            pagePath = path,
            pageUuid = "00000000-0000-0000-0000-000000000001",
            parentUuid = null,
            baseLevel = 0,
            now = fixedNow,
            destinationList = destination,
            mode = ParseMode.FULL,
        )

        val props = destination[0].properties
        assertEquals("2024-02-28", props["deadline"], "deadline must be merged into block properties")
    }

    @Test
    fun mergedProperties_omits_scheduled_when_null() {
        val block = parsedBlock(content = "No scheduled date", scheduled = null)
        val path = "/graph/pages/note.md"
        val destination = mutableListOf<dev.stapler.stelekit.model.Block>()

        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = listOf(block),
            pagePath = path,
            pageUuid = "00000000-0000-0000-0000-000000000001",
            parentUuid = null,
            baseLevel = 0,
            now = fixedNow,
            destinationList = destination,
            mode = ParseMode.FULL,
        )

        val props = destination[0].properties
        assertTrue("scheduled" !in props, "scheduled key must be absent when ParsedBlock.scheduled is null")
    }

    @Test
    fun mergedProperties_omits_deadline_when_null() {
        val block = parsedBlock(content = "No deadline", deadline = null)
        val path = "/graph/pages/note.md"
        val destination = mutableListOf<dev.stapler.stelekit.model.Block>()

        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = listOf(block),
            pagePath = path,
            pageUuid = "00000000-0000-0000-0000-000000000001",
            parentUuid = null,
            baseLevel = 0,
            now = fixedNow,
            destinationList = destination,
            mode = ParseMode.FULL,
        )

        val props = destination[0].properties
        assertTrue("deadline" !in props, "deadline key must be absent when ParsedBlock.deadline is null")
    }
}
