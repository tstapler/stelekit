package dev.stapler.stelekit.integration

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.parsing.OutlinerParser
import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Integration test to reproduce ordering issues.
 * Updated to use UUID-native storage.
 */
class PipelineReproductionTest {

    private val fileSystem = object : FileSystem {
        val files = mutableMapOf<String, String>()
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = files[path]
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = files.keys
            .filter { it.startsWith(path) && it != path }
            .map { it.substringAfterLast("/") }
            .distinct()
            
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val pageRepository = InMemoryPageRepository()
    private val blockRepository = InMemoryBlockRepository()
    private val graphLoader = GraphLoader(fileSystem, pageRepository, blockRepository)

    @Test
    fun `reproduce ordering issue`() = runBlocking {
        val content = """
- Till Listening to [[The Knowledge: How to Rebuild Our World from Scratch]]
  - xTool MetalFab Laser Welder
  - Perhaps we need something like [[Fiver]]
  - [[Rediscovering Paper]]
    - You need to bathe the contents
    - [[iron Gall Ink]]
    - The movable type printing press
- Next Block
        """.trimIndent()

        fileSystem.files["/graph/pages/test.md"] = content
        
        graphLoader.loadGraph("/graph") { }
        
        val pagesResult = pageRepository.getAllPages().first()
        val pages = pagesResult.getOrNull()!!
        val page = pages[0]
        val blocksResult = blockRepository.getBlocksForPage(page.uuid).first()
        val blocks = blocksResult.getOrNull()!!
        
        println("Loaded ${blocks.size} blocks from repository")
        
        // Check "Rediscovering Paper" block
        val rediscovering = blocks.find { it.content == "[[Rediscovering Paper]]" }
        assertNotNull(rediscovering, "Rediscovering Paper block not found")
        
        val root = blocks.find { it.content.startsWith("Till Listening") }!!
        
        println("Root UUID: ${root.uuid}")
        println("Rediscovering UUID: ${rediscovering.uuid}, Parent UUID: ${rediscovering.parentUuid}")
        
        // Verify parentage in DB
        assertEquals(root.uuid, rediscovering.parentUuid, "Rediscovering Paper should be child of Root in DB")
        
        // Verify Sorting
        val sorted = BlockSorter.sort(blocks)
        
        println("Sorted Order:")
        sorted.forEach { 
            val indent = "  ".repeat(it.level)
            println("$indent- ${it.content} (UUID: ${it.uuid}, Parent: ${it.parentUuid})")
        }
        
        // Verify position in sorted list
        // Root should be first (or early)
        val rootIndex = sorted.indexOf(root)
        val rediscoveringIndex = sorted.indexOf(rediscovering)
        
        // Rediscovering should be AFTER Root
        assert(rediscoveringIndex > rootIndex)
        
        // Check if "xTool" and "Perhaps" come before "Rediscovering" (siblings)
        val xtool = blocks.find { it.content.startsWith("xTool") }!!
        val perhaps = blocks.find { it.content.startsWith("Perhaps") }!!
        
        val xtoolIndex = sorted.indexOf(xtool)
        val perhapsIndex = sorted.indexOf(perhaps)
        
        assert(xtoolIndex > rootIndex)
        assert(perhapsIndex > xtoolIndex)
        assert(rediscoveringIndex > perhapsIndex)
        
        // Check children of Rediscovering
        val bathing = blocks.find { it.content.startsWith("You need to bathe") }!!
        val ironGall = blocks.find { it.content.startsWith("[[iron Gall Ink]]") }!!
        val movable = blocks.find { it.content.startsWith("The movable") }!!
        
        val bathingIndex = sorted.indexOf(bathing)
        val ironGallIndex = sorted.indexOf(ironGall)
        val movableIndex = sorted.indexOf(movable)
        
        assert(bathingIndex > rediscoveringIndex)
        assertEquals(rediscovering.uuid, bathing.parentUuid, "Bathing should be child of Rediscovering")
        assertEquals(2, bathing.level, "Bathing should be level 2")
        
        assert(ironGallIndex > bathingIndex)
        assertEquals(rediscovering.uuid, ironGall.parentUuid, "Iron Gall should be child of Rediscovering")
        assertEquals(2, ironGall.level, "Iron Gall should be level 2")
        
        assert(movableIndex > ironGallIndex)
        assertEquals(rediscovering.uuid, movable.parentUuid, "Movable should be child of Rediscovering")
        assertEquals(2, movable.level, "Movable should be level 2")
    }
}
