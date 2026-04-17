package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphLoaderProgressiveTest {

    private val fileSystem = object : FileSystem {
        val files = mutableMapOf<String, String>()
        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String) = files[path]
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = files.keys.filter { it.startsWith(path) }.map { it.substringAfterLast("/") }
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
    fun `test progressive loading phases`() = runTest {
        // Setup 15 journals (immediate count is 10, so 5 should be background loaded)
        val journalsDir = "/graph/journals"
        for (i in 1..15) {
            val day = i.toString().padStart(2, '0')
            val content = "- Block $i"
            fileSystem.files["$journalsDir/2026_01_$day.md"] = content
        }

        var phase1Called = false
        var fullyLoadedCalled = false
        
        graphLoader.loadGraphProgressive(
            graphPath = "/graph",
            immediateJournalCount = 10,
            onProgress = {},
            onPhase1Complete = { 
                phase1Called = true
                // Verify immediate journals (10 most recent) are loaded FULLY
                // Recent: 15..06
                // Background: 05..01
            },
            onFullyLoaded = {
                fullyLoadedCalled = true
            }
        )
        
        // Wait for coroutines
        testScheduler.advanceUntilIdle()
        
        assertTrue(phase1Called, "Phase 1 callback should be called")
        assertTrue(fullyLoadedCalled, "Fully loaded callback should be called")
        
        // Check a recent journal (Phase 1)
        val recentPage = pageRepository.getPageByName("2026_01_15").first().getOrNull()
        assertTrue(recentPage != null, "Recent journal should be loaded")
        val recentBlocks = blockRepository.getBlocksForPage(recentPage!!.uuid).first().getOrNull()!!
        assertTrue(recentBlocks.isNotEmpty())
        assertTrue(recentBlocks[0].isLoaded, "Phase 1 blocks should be fully loaded")
        
        // Check an older journal (Phase 2 - Background)
        // 2026_01_01 should be in background
        val oldPage = pageRepository.getPageByName("2026_01_01").first().getOrNull()
        assertTrue(oldPage != null, "Background journal should be loaded")
        
        val oldBlocks = blockRepository.getBlocksForPage(oldPage!!.uuid).first().getOrNull()!!
        assertTrue(oldBlocks.isNotEmpty())
        // Note: GraphLoader.loadRemainingJournals currently uses METADATA_ONLY?
        // Let's check the code.
        // Yes: `parseAndSavePage(filePath, content, ParseMode.METADATA_ONLY)`
        assertFalse(oldBlocks[0].isLoaded, "Phase 2 background blocks should be METADATA_ONLY")
        
        // Test lazy loading
        graphLoader.loadFullPage(oldPage.uuid)
        testScheduler.advanceUntilIdle()
        
        val reloadedBlocks = blockRepository.getBlocksForPage(oldPage.uuid).first().getOrNull()!!
        assertTrue(reloadedBlocks[0].isLoaded, "Block should be fully loaded after loadFullPage")
    }
}
