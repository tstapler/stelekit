package dev.stapler.stelekit.testing

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exploratory automated test that performs randomized outliner operations
 * and verifies tree invariants after each step.
 * 
 * This is designed to find edge cases in hierarchy logic, positioning, 
 * and chain repair that scripted tests might miss.
 */
class OutlinerMonkeyTest {

    private class MockFileSystem : FileSystem {
        override fun getDefaultGraphPath(): String = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = true
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = false
        override fun directoryExists(path: String): Boolean = true
        override fun createDirectory(path: String): Boolean = true
        override fun deleteFile(path: String): Boolean = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    @Test
    fun `perform randomized outliner operations and verify invariants`() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(MockFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val blockStateManager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope
        )
        val viewModel = JournalsViewModel(journalService, blockStateManager, scope)
        
        // 1. Setup: Ensure we have at least one journal page
        val today = Clock.System.now().let { 
            val tz = TimeZone.currentSystemDefault()
            it.toLocalDateTime(tz).date 
        }
        val pageUuid = "test-page-uuid"
        pageRepo.savePage(Page(
            uuid = pageUuid,
            name = today.toString(),
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            isJournal = true,
            journalDate = today
        ))
        
        // Start with one block
        val initialBlockUuid = "block-0"
        blockRepo.saveBlock(Block(
            uuid = initialBlockUuid,
            pageUuid = pageUuid,
            content = "Root block",
            position = 0,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        ))
        
        viewModel.refresh() // Load initial state
        val randomGenerator = Random(42) // Fixed seed for reproducibility
        val operationsCount = 50

        println("Starting Monkey Test with $operationsCount operations...")

        repeat(operationsCount) { i ->
            val currentState = viewModel.uiState.value
            val allBlocks = viewModel.blocks.value[pageUuid] ?: emptyList()

            if (allBlocks.isEmpty()) {
                println("Operation $i: Add block to empty page")
                viewModel.addBlockToPage(pageUuid).join()
                verifyInvariants(pageUuid, blockRepo)
                return@repeat
            }

            val randomBlock = allBlocks[randomGenerator.nextInt(allBlocks.size)]
            val opType = randomGenerator.nextInt(7)

            
            val job = when (opType) {
                0 -> { 
                    println("Operation $i: addNewBlock after ${randomBlock.uuid} (level ${randomBlock.level})")
                    viewModel.addNewBlock(randomBlock.uuid)
                }
                1 -> { 
                    println("Operation $i: indentBlock ${randomBlock.uuid}")
                    viewModel.indentBlock(randomBlock.uuid)
                }
                2 -> { 
                    println("Operation $i: outdentBlock ${randomBlock.uuid}")
                    viewModel.outdentBlock(randomBlock.uuid)
                }
                3 -> { 
                    println("Operation $i: moveBlockUp ${randomBlock.uuid}")
                    viewModel.moveBlockUp(randomBlock.uuid)
                }
                4 -> { 
                    println("Operation $i: moveBlockDown ${randomBlock.uuid}")
                    viewModel.moveBlockDown(randomBlock.uuid)
                }
                5 -> { 
                    if (randomBlock.content.length > 2) {
                        println("Operation $i: splitBlock ${randomBlock.uuid}")
                        viewModel.splitBlock(randomBlock.uuid, randomBlock.content.length / 2)
                    } else {
                        println("Operation $i: updateBlockContent ${randomBlock.uuid}")
                        viewModel.updateBlockContent(randomBlock.uuid, "Some text to split", 1)
                    }
                }
                6 -> { 
                    println("Operation $i: handleBackspace ${randomBlock.uuid} (parent=${randomBlock.parentUuid}, level=${randomBlock.level})")
                    viewModel.handleBackspace(randomBlock.uuid)
                }
                else -> null
            }
            
            job?.join()
            
            // Verify invariants after each operation
            try {
                verifyInvariants(pageUuid, blockRepo)
            } catch (e: Throwable) {
                println("Invariants failed after operation $i")
                throw e
            }
        }
        
        println("Monkey Test completed successfully!")
    }

    private suspend fun verifyInvariants(pageUuid: String, blockRepo: InMemoryBlockRepository) {
        val blocks = blockRepo.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
        if (blocks.isEmpty()) return
        
        try {
            // 1. No duplicate UUIDs
            val uuids = blocks.map { it.uuid }
            assertEquals(uuids.size, uuids.toSet().size, "Found duplicate UUIDs in repository")
            
            // 2. Hierarchy Consistency
            val blocksByUuid = blocks.associateBy { it.uuid }
            blocks.forEach { block ->
                if (block.parentUuid != null) {
                    val parent = blocksByUuid[block.parentUuid]
                    assertTrue(parent != null, "Block ${block.uuid} references non-existent parent ${block.parentUuid}")
                    assertEquals(parent!!.level + 1, block.level, "Block ${block.uuid} level (${block.level}) is inconsistent with parent level (${parent.level})")
                } else {
                    assertEquals(0, block.level, "Root block ${block.uuid} must be at level 0")
                }
            }
            
            // 3. Sorting and Chain Integrity
            val sorted = BlockSorter.sort(blocks)
            assertEquals(blocks.size, sorted.size, "BlockSorter lost blocks during sorting")
            
            // Check that sorted order maintains relative sibling positions
            val childrenByParent = blocks.groupBy { it.parentUuid }
            childrenByParent.forEach { (parentUuid, children) ->
                val sortedChildren = children.sortedBy { it.position }
                val positions = sortedChildren.map { it.position }
                // Positions should be distinct (we don't strictly require sequential 0,1,2 in memory repo, but they should be ordered)
                assertEquals(positions.size, positions.toSet().size, "Duplicate positions found among siblings of parent $parentUuid")
            }
        } catch (e: Throwable) {
            println("=== CURRENT BLOCKS STATE ===")
            blocks.sortedBy { it.position }.forEach { 
                println("Block: uuid=${it.uuid}, parent=${it.parentUuid}, pos=${it.position}, level=${it.level}, content='${it.content}'")
            }
            throw e
        }
    }
}
