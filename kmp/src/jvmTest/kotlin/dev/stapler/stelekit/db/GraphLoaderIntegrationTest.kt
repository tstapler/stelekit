package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphLoaderIntegrationTest {

    private val fileSystem = object : FileSystem {
        val files = mutableMapOf<String, String>()

        override fun getDefaultGraphPath(): String = "/docs"
        override fun expandTilde(path: String): String = path
        override fun pickDirectory(): String? = "/graph"
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = files.containsKey(path)

        override fun directoryExists(path: String): Boolean = true
        override fun listFiles(path: String): List<String> = files.keys.filter { it.startsWith(path) }.map { it.substringAfterLast("/") }
        override fun readFile(path: String): String? = files[path]
        override fun writeFile(path: String, content: String): Boolean { files[path] = content; return true }
        override fun deleteFile(path: String): Boolean { files.remove(path); return true }
        override fun createDirectory(path: String): Boolean { return true }
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val pageRepository = InMemoryPageRepository()
    private val blockRepository = InMemoryBlockRepository()
    private val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)

    @Test
    fun `test full loading pipeline with hierarchy`() = runBlocking {
        val content = """
- Parent Block
  - Child 1
    - Grandchild 1
  - Child 2
- Root 2
        """.trimIndent()
        
        val path = "/graph/pages/testpage.md"
        fileSystem.files[path] = content
        
        graphLoader.loadGraph("/graph") { _ -> }
        
        // Verify Page
        val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
        assertEquals(1, pages.size)
        val page = pages[0]
        assertEquals("testpage", page.name)
        
        // Verify Blocks
        val blocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        
        // Should have: Parent, Child 1, Grandchild 1, Child 2, Root 2 (5 blocks)
        assertEquals(5, blocks.size, "Should have 5 blocks saved")
        
        val parent = blocks.find { it.content == "Parent Block" }!!
        val child1 = blocks.find { it.content == "Child 1" }!!
        val grandchild = blocks.find { it.content == "Grandchild 1" }!!
        val child2 = blocks.find { it.content == "Child 2" }!!
        val root2 = blocks.find { it.content == "Root 2" }!!
        
        // Verify Hierarchy
        assertEquals(null, parent.parentUuid)
        assertEquals(parent.uuid.value, child1.parentUuid)
        assertEquals(child1.uuid.value, grandchild.parentUuid)
        assertEquals(parent.uuid.value, child2.parentUuid)
        assertEquals(null, root2.parentUuid)
        
        // Verify Levels
        assertEquals(0, parent.level)
        assertEquals(1, child1.level)
        assertEquals(2, grandchild.level)
        assertEquals(1, child2.level)
        assertEquals(0, root2.level)
    }

    /**
     * REQ-2: warm-reload (same page loaded twice, second load with modified content) completes
     * correctly via the composite execute path (Fix B). Ensures the composite actor.execute { }
     * in dispatchFullBlockWrites saves both page and updated blocks without error.
     */
    @Test
    fun `parseAndSavePage warm reload completes without error`() = runBlocking {
        val path = "/graph/pages/warmreload.md"
        val initialContent = """
- Block One
- Block Two
        """.trimIndent()

        // Cold load
        fileSystem.files[path] = initialContent
        graphLoader.parseAndSavePage(FilePath(path), initialContent)

        val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
        val page = pages.find { it.name == "warmreload" }
        assertTrue(page != null, "Page must be saved on cold load")

        val coldBlocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertEquals(2, coldBlocks.size, "Cold load must save 2 blocks")

        // Warm load — same page, modified content
        val modifiedContent = """
- Block One Updated
- Block Two
- Block Three New
        """.trimIndent()
        fileSystem.files[path] = modifiedContent
        graphLoader.parseAndSavePage(FilePath(path), modifiedContent)

        val warmBlocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertEquals(3, warmBlocks.size, "Warm load must result in 3 blocks after adding Block Three New")
        assertTrue(warmBlocks.any { it.content == "Block One Updated" }, "Block One must be updated")
        assertTrue(warmBlocks.any { it.content == "Block Three New" }, "Block Three New must be inserted")
    }

    /**
     * REQ-2: METADATA_ONLY path is not broken by the composite-execute refactor.
     * The METADATA_ONLY branch retains its own standalone writeActor.savePage call
     * before saving stub blocks.
     */
    @Test
    fun `parseAndSavePage saves page and stub blocks when mode is METADATA_ONLY`() = runBlocking {
        val pageRepo2 = InMemoryPageRepository()
        val blockRepo2 = InMemoryBlockRepository()
        val loader2 = GraphLoader(fileSystem, pageRepo2, blockRepo2)

        val path = "/graph/pages/metadataonly.md"
        val content = """
- Block A
- Block B
        """.trimIndent()
        fileSystem.files[path] = content

        loader2.parseAndSavePage(FilePath(path), content, mode = ParseMode.METADATA_ONLY)

        val pages = pageRepo2.getAllPages().first().getOrNull() ?: emptyList()
        val page = pages.find { it.name == "metadataonly" }
        assertTrue(page != null, "Page must be saved in METADATA_ONLY mode")

        val blocks = blockRepo2.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        // METADATA_ONLY saves stub blocks — must be non-empty
        assertTrue(blocks.isNotEmpty(), "METADATA_ONLY must save at least stub blocks")
    }
}
