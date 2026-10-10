package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownSourceGraphReaderParseTest {
    private val graph = GraphInfo(id = GraphId("src"), path = "/graphs/src", displayName = "Src", addedAt = 0L)
    private val fs = FakeFileSystem()
    private val reader = MarkdownSourceGraphReader(fs)

    private fun seed(rel: String, text: String) { fs.writeFileBytes("${graph.path}/$rel", text.encodeToByteArray()) }

    private suspend fun listAll(): List<SourceEntry> =
        (reader.listEntries(graph, null, 100) as Either.Right).value

    private suspend fun read(rel: String): Either<ReadError, StagedPage> {
        val entry = listAll().first { it.fileName == rel }
        return reader.readPage(graph, entry)
    }

    private suspend fun readOk(rel: String, text: String): MergePage {
        seed(rel, text)
        return (read(rel) as Either.Right).value.toMergePage()
    }

    @Test
    fun crlfTabsFenceCollapsedAndOrgBlocksMatchTheProductionParser() = runTest {
        val text = MergeFixtures.REAL_CRLF_TAB
        val expected = MergeConverters.parseMarkdown(text, "${graph.path}/pages/Notes.md", "Notes", false).mergePage
        assertEquals(expected, readOk("pages/Notes.md", text))
    }

    @Test
    fun fencedDashLinesMatchTheProductionParser() = runTest {
        val text = MergeFixtures.FENCE_WITH_DASH_LINES
        val expected = MergeConverters.parseMarkdown(text, "${graph.path}/pages/Fence.md", "Fence", false).mergePage
        assertEquals(expected, readOk("pages/Fence.md", text))
    }

    @Test
    fun collapsedPropertyIsKept() = runTest {
        val page = readOk("pages/C.md", "- Parent\n  collapsed:: true\n\t- child\n")
        assertEquals("true", page.blocks.single().properties["collapsed"])
        assertEquals("child", page.blocks.single().children.single().content)
    }

    @Test
    fun explicitIdIsVerbatimAndAbsentIdStaysNull() = runTest {
        val page = readOk("pages/Ids.md", MergeFixtures.MIXED_LABELED)
        assertEquals("11111111-1111-1111-1111-111111111111", page.blocks[0].uuid)
        assertNull(page.blocks[1].uuid)
        assertEquals("22222222-2222-2222-2222-222222222222", page.blocks[2].uuid)
        assertFalse("id" in page.blocks[0].properties)
    }

    @Test
    fun journalFilenameDecodesToDateAndKind() = runTest {
        seed("journals/2026_10_07.md", "- today\n")
        seed("journals/2026-10-08.md", "- tomorrow\n")
        seed("journals/notes.md", "- not a date\n")
        seed("pages/Plain.md", "- p\n")
        val byFile = listAll().associateBy { it.fileName }
        assertEquals(SourceKind.Journal, byFile.getValue("journals/2026_10_07.md").kind)
        assertEquals(SourceKind.Journal, byFile.getValue("journals/2026-10-08.md").kind)
        assertEquals(SourceKind.Page, byFile.getValue("journals/notes.md").kind)
        assertEquals(SourceKind.Page, byFile.getValue("pages/Plain.md").kind)
        val staged = (read("journals/2026_10_07.md") as Either.Right).value
        assertTrue(staged.isJournal)
        assertEquals(LocalDate(2026, 10, 7).toString(), staged.journalDate)
        assertEquals("2026_10_07", staged.name)
    }

    @Test
    fun pageNamesAreDecodedFromFileNames() = runTest {
        seed("pages/a%2Fb.md", "- x\n")
        assertEquals("a/b", listAll().single().name)
    }

    @Test
    fun listingIsAProjectionPagedByNameCursor() = runTest {
        (1..250).forEach { seed("pages/P${it.toString().padStart(3, '0')}.md", "- b$it\n") }
        val first = (reader.listEntries(graph, null, 1000) as Either.Right).value
        assertEquals(SourceGraphReader.MAX_PAGE_SIZE, first.size)
        val second = (reader.listEntries(graph, first.last().name, 100) as Either.Right).value
        val third = (reader.listEntries(graph, second.last().name, 100) as Either.Right).value
        assertEquals(listOf("P001", "P101", "P201"), listOf(first, second, third).map { it.first().name })
        assertEquals(50, third.size)
        assertEquals((1..250).map { "P" + it.toString().padStart(3, '0') }, (first + second + third).map { it.name })
        assertTrue(first.all { it.sizeBytes > 0 })
    }

    @Test
    fun overCapFileIsTooLargeWithoutReadingIt() = runTest {
        val small = MarkdownSourceGraphReader(fs, maxFileBytes = 10)
        seed("pages/Big.md", "- " + "x".repeat(50) + "\n")
        val entry = (small.listEntries(graph, null, 10) as Either.Right).value.single()
        val err = (small.readPage(graph, entry) as Either.Left).value
        assertIs<ReadError.TooLarge>(err)
    }

    @Test
    fun staleEntrySizeCannotBypassTheCap() = runTest {
        val small = MarkdownSourceGraphReader(fs, maxFileBytes = 10)
        seed("pages/Grew.md", "- " + "x".repeat(50) + "\n")
        val stale = SourceEntry("Grew", SourceKind.Page, "pages/Grew.md", sizeBytes = 1, modifiedAtMs = null)
        assertIs<ReadError.TooLarge>((small.readPage(graph, stale) as Either.Left).value)
    }

    @Test
    fun garbageAndNonUtf8AreUnreadableNotACrash() = runTest {
        fs.writeFileBytes("${graph.path}/pages/Bin.md", byteArrayOf(0x2D, 0x20, 0xC3.toByte(), 0x28, 0xFF.toByte(), 0xFE.toByte()))
        fs.writeFileBytes("${graph.path}/pages/Nul.md", "- a\u0000b".encodeToByteArray())
        seed("pages/Ok.md", "- fine\n")
        assertIs<ReadError.Unreadable>((read("pages/Bin.md") as Either.Left).value)
        assertIs<ReadError.Unreadable>((read("pages/Nul.md") as Either.Left).value)
        assertTrue(read("pages/Ok.md").isRight())
    }

    @Test
    fun missingFileAndForgedPathsAreUnreadable() = runTest {
        val gone = SourceEntry("Gone", SourceKind.Page, "pages/Gone.md", 1, null)
        assertIs<ReadError.Unreadable>((reader.readPage(graph, gone) as Either.Left).value)
        for (bad in listOf("pages/../../etc/passwd", "../x.md", "assets/x.md", "pages/../config.md")) {
            val forged = SourceEntry("x", SourceKind.Page, bad, 1, null)
            assertIs<ReadError.Unreadable>((reader.readPage(graph, forged) as Either.Left).value, bad)
        }
    }

    @Test
    fun encryptedGraphAndMissingFolderAreReportedPerSource() = runTest {
        val paranoid = graph.copy(isParanoidMode = true)
        assertEquals(ReadError.Encrypted, (reader.listEntries(paranoid, null, 10) as Either.Left).value)
        val missing = graph.copy(path = "/nowhere")
        assertEquals(ReadError.FolderMissing, (reader.listEntries(missing, null, 10) as Either.Left).value)
    }

    @Test
    fun renderThenReadEqualsOriginal() = runTest {
        checkAll(200, MergeArbs.cases(0.4)) { c ->
            for (page in listOf(c.target, c.source)) {
                seed("pages/P.md", MergeRenderer.renderNewPage(page))
                val staged = (read("pages/P.md") as Either.Right).value
                assertEquals(page, staged.toMergePage())
            }
        }
    }
}
