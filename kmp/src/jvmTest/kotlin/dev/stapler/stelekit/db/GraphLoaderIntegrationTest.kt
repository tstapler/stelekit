package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
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
        assertEquals(parent.uuid, child1.parentUuid)
        assertEquals(child1.uuid, grandchild.parentUuid)
        assertEquals(parent.uuid, child2.parentUuid)
        assertEquals(null, root2.parentUuid)
        
        // Verify Levels
        assertEquals(0, parent.level)
        assertEquals(1, child1.level)
        assertEquals(2, grandchild.level)
        assertEquals(1, child2.level)
        assertEquals(0, root2.level)
    }
}
