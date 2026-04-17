package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphWriterTest {

    @Test
    fun testSavePageMaintainsHierarchy() {
        runBlocking {
            val fileSystem = PlatformFileSystem()
            val writer = GraphWriter(fileSystem)
            
            // Create a temp directory
            val userHome = System.getProperty("user.home")
            val tempDir = File(userHome, "stelekit_test_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            val graphPath = tempDir.absolutePath
            
            val now = Clock.System.now()
            
            try {
                // Create a page
                val pageUuid = "00000000-0000-0000-0000-000000000001"
                val page = Page(
                    uuid = pageUuid,
                    name = "TestPage",
                    createdAt = now,
                    updatedAt = now,
                    journalDate = null,
                    properties = emptyMap()
                )
                
                // Create nested blocks
                // A (pos 0)
                //   A1 (pos 0, parent A)
                // B (pos 1)
                //   B1 (pos 0, parent B)
                
                val blockAUuid = "00000000-0000-0000-0000-000000000010"
                val blockA = Block(
                    uuid = blockAUuid,
                    pageUuid = pageUuid,
                    content = "Block A",
                    level = 0,
                    position = 0,
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap()
                )
                
                val blockA1 = Block(
                    uuid = "00000000-0000-0000-0000-000000000011",
                    pageUuid = pageUuid,
                    content = "Block A1",
                    level = 1,
                    position = 0,
                    parentUuid = blockAUuid,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap()
                )
                
                val blockBUuid = "00000000-0000-0000-0000-000000000012"
                val blockB = Block(
                    uuid = blockBUuid,
                    pageUuid = pageUuid,
                    content = "Block B",
                    level = 0,
                    position = 1,
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap()
                )
                
                val blockB1 = Block(
                    uuid = "00000000-0000-0000-0000-000000000013",
                    pageUuid = pageUuid,
                    content = "Block B1",
                    level = 1,
                    position = 0,
                    parentUuid = blockBUuid,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap()
                )
                
                val blocks = listOf(blockA, blockA1, blockB, blockB1)
                
                // Save
                writer.savePage(page, blocks, graphPath)
                
                // Read file content
                val filePath = File(tempDir, "pages/TestPage.md").absolutePath
                val content = fileSystem.readFile(filePath)
                
                println("Saved content:\n$content")
                
                // Expected content (using tabs for indentation)
                assertTrue(content!!.contains("- Block B\n\t- Block B1"), "Block B1 should be nested under Block B. Actual:\n$content")
                
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
