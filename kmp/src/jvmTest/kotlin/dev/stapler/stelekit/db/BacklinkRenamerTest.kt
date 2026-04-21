package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for [BacklinkRenamer.execute].
 * Uses in-memory repositories and a real [PlatformFileSystem] with a temp directory
 * so file-move operations are exercised without touching the real graph.
 */
class BacklinkRenamerTest {

    private val now = Clock.System.now()

    private fun makePage(uuid: String, name: String, filePath: String? = null) = Page(
        uuid = uuid,
        name = name,
        createdAt = now,
        updatedAt = now,
        filePath = filePath
    )

    private fun makeBlock(uuid: String, pageUuid: String, content: String) = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = 0,
        createdAt = now,
        updatedAt = now
    )

    private fun buildRenamer(
        pageRepo: InMemoryPageRepository,
        blockRepo: InMemoryBlockRepository,
        graphPath: String
    ): BacklinkRenamer {
        val fs = PlatformFileSystem()
        val graphWriter = GraphWriter(fs)
        val actor = DatabaseWriteActor(blockRepo, pageRepo)
        return BacklinkRenamer(pageRepo, blockRepo, graphWriter, actor)
    }

    // ---- happy path ----

    @Test
    fun rename_with_no_backlinks_succeeds(): Unit = runBlocking {
        val tempDir = File(System.getProperty("user.home"), "stelekit_renamer_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            // Create a markdown file for the page to be renamed
            val pagesDir = File(tempDir, "pages")
            pagesDir.mkdirs()
            File(pagesDir, "Alpha.md").writeText("- Some content")

            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()
            val alphaPage = makePage("page-alpha", "Alpha", filePath = "${pagesDir.absolutePath}/Alpha.md")
            pageRepo.savePage(alphaPage)

            val renamer = buildRenamer(pageRepo, blockRepo, tempDir.absolutePath)
            val result = renamer.execute(alphaPage, "Beta", tempDir.absolutePath)

            assertIs<RenameResult.Success>(result)
            assertEquals("Alpha", result.oldName)
            assertEquals("Beta", result.newName)
            assertEquals(0, result.updatedBlockCount)

            // Verify page was renamed in the repository
            val renamedPage = pageRepo.getPageByUuid("page-alpha").first().getOrNull()
            assertEquals("Beta", renamedPage?.name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rename_rewrites_backlinks_in_other_pages(): Unit = runBlocking {
        val tempDir = File(System.getProperty("user.home"), "stelekit_renamer_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val pagesDir = File(tempDir, "pages")
            pagesDir.mkdirs()
            File(pagesDir, "Alpha.md").writeText("- Content")
            File(pagesDir, "Referrer.md").writeText("- See [[Alpha]]")

            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()

            val alphaPage = makePage("page-alpha", "Alpha", filePath = "${pagesDir.absolutePath}/Alpha.md")
            val referrerPage = makePage("page-referrer", "Referrer")
            pageRepo.savePage(alphaPage)
            pageRepo.savePage(referrerPage)

            // Block in the referrer page that links to Alpha
            val linkBlock = makeBlock("block-1", "page-referrer", "See [[Alpha]]")
            blockRepo.saveBlock(linkBlock)

            val renamer = buildRenamer(pageRepo, blockRepo, tempDir.absolutePath)
            val result = renamer.execute(alphaPage, "Beta", tempDir.absolutePath)

            assertIs<RenameResult.Success>(result)
            assertEquals(1, result.updatedBlockCount)

            // Block content should now reference Beta
            val updatedBlock = blockRepo.getBlockByUuid("block-1").first().getOrNull()
            assertEquals("See [[Beta]]", updatedBlock?.content)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rename_rewrites_multiple_backlinks_in_same_block(): Unit = runBlocking {
        val tempDir = File(System.getProperty("user.home"), "stelekit_renamer_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val pagesDir = File(tempDir, "pages")
            pagesDir.mkdirs()
            File(pagesDir, "Alpha.md").writeText("- Content")
            File(pagesDir, "Referrer.md").writeText("- See [[Alpha]] and [[Alpha]] again")

            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()

            val alphaPage = makePage("page-alpha", "Alpha", filePath = "${pagesDir.absolutePath}/Alpha.md")
            val referrerPage = makePage("page-referrer", "Referrer")
            pageRepo.savePage(alphaPage)
            pageRepo.savePage(referrerPage)

            val linkBlock = makeBlock("block-1", "page-referrer", "See [[Alpha]] and [[Alpha]] again")
            blockRepo.saveBlock(linkBlock)

            val renamer = buildRenamer(pageRepo, blockRepo, tempDir.absolutePath)
            val result = renamer.execute(alphaPage, "Beta", tempDir.absolutePath)

            assertIs<RenameResult.Success>(result)

            val updatedBlock = blockRepo.getBlockByUuid("block-1").first().getOrNull()
            assertEquals("See [[Beta]] and [[Beta]] again", updatedBlock?.content)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rename_rewrites_aliased_backlinks_end_to_end(): Unit = runBlocking {
        val tempDir = File(System.getProperty("user.home"), "stelekit_renamer_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val pagesDir = File(tempDir, "pages")
            pagesDir.mkdirs()
            File(pagesDir, "Alpha.md").writeText("- Content")
            File(pagesDir, "Referrer.md").writeText("- See [[Alpha|the alpha page]]")

            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()

            val alphaPage = makePage("page-alpha", "Alpha", filePath = "${pagesDir.absolutePath}/Alpha.md")
            val referrerPage = makePage("page-referrer", "Referrer")
            pageRepo.savePage(alphaPage)
            pageRepo.savePage(referrerPage)

            val linkBlock = makeBlock("block-1", "page-referrer", "See [[Alpha|the alpha page]]")
            blockRepo.saveBlock(linkBlock)

            val renamer = buildRenamer(pageRepo, blockRepo, tempDir.absolutePath)
            val result = renamer.execute(alphaPage, "Beta", tempDir.absolutePath)

            assertIs<RenameResult.Success>(result)
            assertEquals(1, result.updatedBlockCount)

            val updatedBlock = blockRepo.getBlockByUuid("block-1").first().getOrNull()
            assertEquals("See [[Beta|the alpha page]]", updatedBlock?.content)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---- failure path ----

    @Test
    fun rename_returns_failure_when_page_not_found_in_repo(): Unit = runBlocking {
        val tempDir = File(System.getProperty("user.home"), "stelekit_renamer_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()
            // Page is NOT saved to repo — renamePage will fail
            val missingPage = makePage("page-missing", "Ghost")

            val renamer = buildRenamer(pageRepo, blockRepo, tempDir.absolutePath)
            val result = renamer.execute(missingPage, "NewName", tempDir.absolutePath)

            assertIs<RenameResult.Failure>(result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---- preview ----

    @Test
    fun preview_returns_affected_block_count_without_mutating(): Unit = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val alphaPage = makePage("page-alpha", "Alpha")
        val referrerPage = makePage("page-referrer", "Referrer")
        pageRepo.savePage(alphaPage)
        pageRepo.savePage(referrerPage)

        blockRepo.saveBlock(makeBlock("block-1", "page-referrer", "See [[Alpha]]"))
        blockRepo.saveBlock(makeBlock("block-2", "page-referrer", "Also [[Alpha|alias]]"))

        val fs = PlatformFileSystem()
        val actor = DatabaseWriteActor(blockRepo, pageRepo)
        val renamer = BacklinkRenamer(pageRepo, blockRepo, GraphWriter(fs), actor)
        val preview = renamer.preview(alphaPage, "Beta")

        assertEquals("Alpha", preview.oldName)
        assertEquals("Beta", preview.newName)
        assertEquals(2, preview.affectedBlockCount)
        assertTrue(preview.affectedPageUuids.contains("page-referrer"))

        // Page name must NOT have changed
        val unchanged = pageRepo.getPageByUuid("page-alpha").first().getOrNull()
        assertEquals("Alpha", unchanged?.name)
    }

    @Test
    fun rename_rewrites_hashtag_references_end_to_end(): Unit = runBlocking {
        val tempDir = File(System.getProperty("user.home"), "stelekit_renamer_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val pagesDir = File(tempDir, "pages")
            pagesDir.mkdirs()
            File(pagesDir, "Alpha.md").writeText("- Content")
            File(pagesDir, "Referrer.md").writeText("- #Alpha and #[[Alpha]] here")

            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()

            val alphaPage = makePage("page-alpha", "Alpha", filePath = "${pagesDir.absolutePath}/Alpha.md")
            val referrerPage = makePage("page-referrer", "Referrer")
            pageRepo.savePage(alphaPage)
            pageRepo.savePage(referrerPage)

            // Block containing both hashtag forms
            val linkBlock = makeBlock("block-1", "page-referrer", "#Alpha and #[[Alpha]] here")
            blockRepo.saveBlock(linkBlock)

            val renamer = buildRenamer(pageRepo, blockRepo, tempDir.absolutePath)
            val result = renamer.execute(alphaPage, "Beta", tempDir.absolutePath)

            assertIs<RenameResult.Success>(result)
            assertEquals(1, result.updatedBlockCount)

            val updatedBlock = blockRepo.getBlockByUuid("block-1").first().getOrNull()
            assertEquals("#Beta and #[[Beta]] here", updatedBlock?.content)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rename_returns_failure_when_page_has_no_file_path(): Unit = runBlocking {
        val tempDir = File(System.getProperty("user.home"), "stelekit_renamer_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()
            // Page exists in repo but has no filePath — graphWriter.renamePage returns false
            val noFilePage = makePage("page-alpha", "Alpha", filePath = null)
            pageRepo.savePage(noFilePage)

            val renamer = buildRenamer(pageRepo, blockRepo, tempDir.absolutePath)
            val result = renamer.execute(noFilePage, "Beta", tempDir.absolutePath)

            assertIs<RenameResult.Failure>(result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---- replaceHashtag unit tests ----

    @Test
    fun `replaceHashtag replaces bracket form`() {
        assertEquals(
            "See #[[new-meeting]] today",
            replaceHashtag("See #[[meeting]] today", "meeting", "new-meeting")
        )
    }

    @Test
    fun `replaceHashtag replaces simple form followed by space`() {
        assertEquals(
            "#new-meeting notes",
            replaceHashtag("#meeting notes", "meeting", "new-meeting")
        )
    }

    @Test
    fun `replaceHashtag replaces simple form at end of string`() {
        assertEquals(
            "read #new-meeting",
            replaceHashtag("read #meeting", "meeting", "new-meeting")
        )
    }

    @Test
    fun `replaceHashtag does NOT replace simple form that is a prefix`() {
        assertEquals(
            "#meetings are fun",
            replaceHashtag("#meetings are fun", "meeting", "new-meeting")
        )
    }

    @Test
    fun `replaceHashtag replaces simple form followed by punctuation`() {
        assertEquals(
            "done #new-meeting, proceed",
            replaceHashtag("done #meeting, proceed", "meeting", "new-meeting")
        )
    }

    @Test
    fun `replaceHashtag replaces both bracket and simple forms in same string`() {
        assertEquals(
            "#new-meeting and #[[new-meeting]] and #meetings",
            replaceHashtag("#meeting and #[[meeting]] and #meetings", "meeting", "new-meeting")
        )
    }
}
