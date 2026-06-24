package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.TestFixtures
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphWriterTest {

    @Test
    fun testSavePageMaintainsHierarchy() {
        runBlocking {
            val fileSystem = PlatformFileSystem()
            val writer = GraphWriter(fileSystem)
            
            val tempDir = java.nio.file.Files.createTempDirectory(
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
                "stelekit_test_",
            ).toFile()
            val graphPath = tempDir.absolutePath
            fileSystem.registerGraphRoot(graphPath)

            val now = Clock.System.now()
            
            try {
                // Create a page
                val pageUuid = "00000000-0000-0000-0000-000000000001"
                val page = Page(
                    uuid = PageUuid(pageUuid),
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
                    uuid = BlockUuid(blockAUuid),
                    pageUuid = PageUuid(pageUuid),
                    content = "Block A",
                    level = 0,
                    position = "a0",
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap()
                )
                
                val blockA1 = Block(
                    uuid = BlockUuid("00000000-0000-0000-0000-000000000011"),
                    pageUuid = PageUuid(pageUuid),
                    content = "Block A1",
                    level = 1,
                    position = "a0",
                    parentUuid = blockAUuid,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap()
                )
                
                val blockBUuid = "00000000-0000-0000-0000-000000000012"
                val blockB = Block(
                    uuid = BlockUuid(blockBUuid),
                    pageUuid = PageUuid(pageUuid),
                    content = "Block B",
                    level = 0,
                    position = "a1",
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap()
                )
                
                val blockB1 = Block(
                    uuid = BlockUuid("00000000-0000-0000-0000-000000000013"),
                    pageUuid = PageUuid(pageUuid),
                    content = "Block B1",
                    level = 1,
                    position = "a0",
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

    @Test
    fun `writeFile failure logs error without false success message`() {
        runBlocking {
            val failingFileSystem = object : FakeFileSystem() {
                override fun writeFile(path: String, content: String): Boolean = false
            }

            LogManager.clearLogs()

            val page = TestFixtures.samplePage()
            val writer = GraphWriter(fileSystem = failingFileSystem)
            writer.startAutoSave(this)
            writer.queueSave(page, emptyList(), "/tmp/graph")
            writer.flush()

            val writerLogs = LogManager.logs.value.filter { it.tag == "GraphWriter" }

            assertTrue(
                writerLogs.any { it.level == LogLevel.ERROR && it.message.contains("Failed to write file") },
                "Expected ERROR log when writeFile fails"
            )
            assertTrue(
                writerLogs.none { it.level == LogLevel.INFO && it.message.contains("Flushed page") },
                "Expected no success log when writeFile fails, got: $writerLogs"
            )
        }
    }

    @Test
    fun `writeFile success logs success message without error`() {
        runBlocking {
            LogManager.clearLogs()

            val page = TestFixtures.samplePage()
            val writer = GraphWriter(fileSystem = FakeFileSystem())
            writer.startAutoSave(this)
            writer.queueSave(page, emptyList(), "/tmp/graph")
            writer.flush()

            val writerLogs = LogManager.logs.value.filter { it.tag == "GraphWriter" }

            assertTrue(
                writerLogs.any { it.level == LogLevel.INFO && it.message.contains("Flushed page") },
                "Expected success log when writeFile succeeds"
            )
            assertTrue(
                writerLogs.none { it.level == LogLevel.ERROR },
                "Expected no error log when writeFile succeeds"
            )
        }
    }

    @Test
    fun `PlatformFileSystem writeFile logs exception when write fails`() {
        val tempDir = java.nio.file.Files.createTempDirectory(
            java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
            "stelekit_test_fs_",
        ).toFile()
        // Create a directory with the same name as the target file — forces an IOException
        val dirNamedAsFile = File(tempDir, "cannotwrite.md")
        tempDir.mkdirs()
        dirNamedAsFile.mkdirs()

        try {
            val fileSystem = PlatformFileSystem()
            fileSystem.registerGraphRoot(tempDir.absolutePath)

            LogManager.clearLogs()

            val result = fileSystem.writeFile("${tempDir.absolutePath}/cannotwrite.md", "content")

            assertFalse(result, "writeFile should return false when write fails")

            val errorLogs = LogManager.logs.value.filter {
                it.level == LogLevel.ERROR && it.tag == "FileSystem"
            }
            assertTrue(errorLogs.isNotEmpty(), "Expected error log from FileSystem when writeFile fails")
            assertNotNull(errorLogs.first().throwable, "Error log should include the exception for diagnosis")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ── TC-16/17: Pre-write hash conflict check ────────────────────────────────

    /**
     * TC-16: When [checkPreWriteConflict] returns true (external change detected), the write
     * must be aborted — [onPreWriteConflict] is called and the file is not written to disk.
     * [onPreWrite] must NOT be called (we bail before the saga starts, so no sentinel is set).
     */
    @Test
    fun `TC-16 pre-write hash conflict aborts write and calls onPreWriteConflict without setting sentinel`() {
        runBlocking {
            val writtenPaths = mutableListOf<String>()
            var preWriteCount = 0
            var conflictCallCount = 0
            var capturedPendingContent = ""
            var capturedDiskContent = ""

            val trackingFs = object : FakeFileSystem() {
                override fun readFile(path: String): String? = "existing disk content"
                override fun writeFile(path: String, content: String): Boolean {
                    writtenPaths.add(path)
                    return true
                }
            }

            val writer = GraphWriter(
                fileSystem = trackingFs,
                onPreWrite = { preWriteCount++ },
                checkPreWriteConflict = { _, _ -> true },  // always report conflict
                onPreWriteConflict = { _, pendingContent, diskContent ->
                    conflictCallCount++
                    capturedPendingContent = pendingContent
                    capturedDiskContent = diskContent
                },
            )

            val now = Clock.System.now()
            val page = Page(
                uuid = PageUuid("tc16-uuid-1"),
                name = "TestPageTC16",
                createdAt = now,
                updatedAt = now,
                journalDate = null,
                properties = emptyMap(),
                filePath = "/tmp/pages/TestPageTC16.md",
            )

            writer.savePage(page, emptyList(), "/tmp")

            assertTrue(writtenPaths.isEmpty(), "File must not be written when conflict detected")
            assertEquals(0, preWriteCount, "onPreWrite (sentinel) must not be called — bail occurs before the saga")
            assertEquals(1, conflictCallCount, "onPreWriteConflict must be called exactly once")
            assertEquals("existing disk content", capturedDiskContent, "diskContent must be the on-disk content")
            assertEquals("", capturedPendingContent,
                "pendingContent for an empty-block page must be empty string")
        }
    }

    /**
     * TC-17: When [checkPreWriteConflict] returns false (hashes match, no external change),
     * the write must proceed normally — file is written and [onPreWriteConflict] is not called.
     */
    @Test
    fun `TC-17 pre-write hash check passes through when no conflict`() {
        runBlocking {
            val writtenPaths = mutableListOf<String>()
            var conflictCallCount = 0

            val trackingFs = object : FakeFileSystem() {
                override fun readFile(path: String): String? = "disk content"
                override fun writeFile(path: String, content: String): Boolean {
                    writtenPaths.add(path)
                    return true
                }
            }

            val writer = GraphWriter(
                fileSystem = trackingFs,
                checkPreWriteConflict = { _, _ -> false },  // no conflict
                onPreWriteConflict = { _, _, _ -> conflictCallCount++ },
            )

            val now = Clock.System.now()
            val page = Page(
                uuid = PageUuid("tc17-uuid-1"),
                name = "TestPageTC17",
                createdAt = now,
                updatedAt = now,
                journalDate = null,
                properties = emptyMap(),
                filePath = "/tmp/pages/TestPageTC17.md",
            )

            writer.savePage(page, emptyList(), "/tmp")

            assertEquals(1, writtenPaths.size, "File must be written when no conflict detected")
            assertEquals(0, conflictCallCount, "onPreWriteConflict must not be called when no conflict")
        }
    }

    // ── TC-15: GraphWriter saga compensation calls clearPendingWrite ──────────

    /**
     * TC-15: When savePageInternal fails (saga compensation triggered), onClearPendingWrite
     * must be called to remove the Long.MAX_VALUE sentinel from FileRegistry modTimes.
     * Without this, subsequent external edits are permanently suppressed.
     *
     * Fails against pre-fix code: onClearPendingWrite does not exist.
     */
    @Test
    fun `TC-15 savePageInternal calls clearPendingWrite in saga compensation when write fails`() {
        runBlocking {
            var preWriteCount = 0
            var clearPendingWriteCount = 0

            // FakeFileSystem with all writes disabled — triggers saga compensation
            val failingFs = object : FakeFileSystem() {
                override fun fileExists(path: String) = false
                override fun readFile(path: String): String? = null
                // writeFile, markDirty, and writeFileBytes return false by default in FakeFileSystem
                // (writeFile returns true by default — override to fail)
                override fun writeFile(path: String, content: String): Boolean = false
            }

            val writer = GraphWriter(
                fileSystem = failingFs,
                onPreWrite = { preWriteCount++ },
                onClearPendingWrite = { clearPendingWriteCount++ },
            )
            writer.startAutoSave(500L)

            val now = Clock.System.now()
            val page = Page(
                uuid = PageUuid("tc15-uuid-1"),
                name = "TestPageTC15",
                createdAt = now,
                updatedAt = now,
                journalDate = null,
                properties = emptyMap(),
                filePath = "/tmp/pages/TestPageTC15.md",
            )

            // Attempt to save — write fails → saga compensation runs
            writer.savePage(page, emptyList(), "/tmp")

            // preMarkPendingWrite should have been called (Step 0)
            assertEquals(1, preWriteCount,
                "onPreWrite must be called before the write attempt")

            // clearPendingWrite must have been called in saga compensation
            assertEquals(1, clearPendingWriteCount,
                "onClearPendingWrite must be called in saga compensation when write fails")
        }
    }
}
