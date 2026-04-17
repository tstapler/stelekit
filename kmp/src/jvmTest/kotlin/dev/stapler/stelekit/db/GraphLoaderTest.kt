package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.DatascriptBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for GraphLoader using self-contained fixture graphs written to a temp
 * directory, so the tests have no dependency on external paths or submodules.
 */
class GraphLoaderTest {

    /** Creates a minimal fixture graph and returns its root path. Caller must delete it. */
    private fun createFixtureGraph(): File {
        val tempDir = File(System.getProperty("user.home"), "graphloader_test_${System.currentTimeMillis()}")
        val pagesDir = File(tempDir, "pages").also { it.mkdirs() }
        val journalsDir = File(tempDir, "journals").also { it.mkdirs() }

        // "contents" page with two nested blocks
        File(pagesDir, "contents.md").writeText(
            """
            - First item on the contents page
            	- Nested child of first item
            - Second item on the contents page
            """.trimIndent()
        )

        // "new page"
        File(pagesDir, "new page.md").writeText(
            """
            - Block on the new page
            - Another block
            """.trimIndent()
        )

        // "some page"
        File(pagesDir, "some_page.md").writeText(
            """
            - Block on some page
            """.trimIndent()
        )

        // A journal entry so the journals directory is non-empty
        File(journalsDir, "2026_01_01.md").writeText(
            """
            - Happy new year
            """.trimIndent()
        )

        return tempDir
    }

    @Test
    fun testLoadDemoGraph() = runBlocking {
        val graphDir = createFixtureGraph()
        try {
            val fileSystem = PlatformFileSystem()
            val pageRepository = InMemoryPageRepository()
            val blockRepository = DatascriptBlockRepository()
            val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)

            graphLoader.loadGraph(graphDir.absolutePath) {}

            val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
            assertTrue(pages.isNotEmpty(), "No pages loaded")

            // Verify the fixture pages were loaded (name matching is case-insensitive in loader)
            val pageNames = pages.map { it.name.lowercase() }
            assertTrue(pageNames.any { it.contains("contents") }, "Expected 'contents' page, got: $pageNames")
            assertTrue(pageNames.any { it.contains("new page") || it.contains("new_page") }, "Expected 'new page', got: $pageNames")

            val contentsPage = pages.first { it.name.lowercase().contains("contents") }
            val blocks = blockRepository.getBlocksForPage(contentsPage.uuid).first().getOrNull() ?: emptyList()
            assertTrue(blocks.isNotEmpty(), "No blocks loaded for 'contents' page")
        } finally {
            graphDir.deleteRecursively()
        }
    }

    @Test
    fun testLoadGraphProgressive() = runBlocking {
        val graphDir = createFixtureGraph()
        try {
            val fileSystem = PlatformFileSystem()
            val pageRepository = InMemoryPageRepository()
            val blockRepository = DatascriptBlockRepository()
            val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)

            var phase1Complete = false
            var fullyLoaded = false

            graphLoader.loadGraphProgressive(
                graphPath = graphDir.absolutePath,
                immediateJournalCount = 5,
                onProgress = {},
                onPhase1Complete = { phase1Complete = true },
                onFullyLoaded = { fullyLoaded = true }
            )

            assertTrue(phase1Complete, "Phase 1 should be complete")
            assertTrue(fullyLoaded, "Graph should be fully loaded")

            val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
            assertTrue(pages.isNotEmpty(), "Pages should be loaded")

            val contentsPage = pages.firstOrNull { it.name.lowercase().contains("contents") }
            assertNotNull(contentsPage, "contents page should exist")

            val blocks = blockRepository.getBlocksForPage(contentsPage.uuid).first().getOrNull() ?: emptyList()
            assertTrue(blocks.isNotEmpty(), "Blocks should be loaded for contents page")

            // Blocks loaded via loadGraphProgressive start as stubs (isLoaded = false)
            val firstBlock = blocks.first()
            assertEquals(false, firstBlock.isLoaded, "Block should be a stub initially")

            // Loading the full page promotes stubs to real blocks
            graphLoader.loadFullPage(contentsPage.uuid)

            val reloadedBlocks = blockRepository.getBlocksForPage(contentsPage.uuid).first().getOrNull() ?: emptyList()
            assertTrue(reloadedBlocks.isNotEmpty(), "Blocks should still be present after full load")
            val reloadedFirst = reloadedBlocks.first()
            assertEquals(true, reloadedFirst.isLoaded, "Block should be fully loaded after loadFullPage")
        } finally {
            graphDir.deleteRecursively()
        }
    }
}
