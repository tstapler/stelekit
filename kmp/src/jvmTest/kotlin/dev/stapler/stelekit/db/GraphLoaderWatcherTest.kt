@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.db

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import java.io.File

/**
 * Tests for the file-watcher loop fix (Fix 2):
 *
 * 1. markFileWrittenByUs records mod-time + content hash
 * 2. Content-hash guard prevents re-parse when only mtime changes
 * 3. GraphWriter's onFileWritten callback calls markFileWrittenByUs
 * 4. ExternalFileChange data class has correct structure
 */
class GraphLoaderWatcherTest {

    private fun tempGraphDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "watcher_test_${System.currentTimeMillis()}")
        dir.mkdirs()
        File(dir, "pages").mkdirs()
        File(dir, "journals").mkdirs()
        return dir
    }

    @Test
    fun markFileWrittenByUs_prevents_reparse_on_mtime_change() = runBlocking {
        val graphDir = tempGraphDir()
        try {
            val fileSystem = PlatformFileSystem()
            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()
            // Use a 100ms poll interval so the watcher cycles fast enough for the test to
            // observe whether suppression works within a short delay.
            val loader = GraphLoader(fileSystem, pageRepo, blockRepo, watcherPollIntervalMs = 100L)

            // Load the (empty) graph so the watcher has a base state
            loader.loadGraph(graphDir.absolutePath) {}

            // Write a file directly (simulating GraphWriter)
            val pagePath = File(graphDir, "pages/TestPage.md").absolutePath
            fileSystem.writeFile(pagePath, "- Block A\n")

            // Mark it as written by us
            loader.markFileWrittenByUs(pagePath)

            // No blocks should exist yet — markFileWrittenByUs doesn't parse
            val blocksBeforeTick: List<Block> = blockRepo.getBlocksForPage("any").first().getOrNull() ?: emptyList()
            assertTrue(blocksBeforeTick.isEmpty(), "No blocks should exist before the watcher runs")

            // Capture baseline: page count right after loadGraph (empty graph = 0 pages)
            val pageCountBaseline = pageRepo.getAllPages().first().getOrNull()?.size ?: 0

            // Touch file (change mtime without changing content)
            delay(50)
            File(pagePath).setLastModified(System.currentTimeMillis())

            // Wait for at least 2 watcher poll cycles (100ms each) so the watcher has a
            // real chance to detect the mtime change and trigger — or correctly suppress it.
            delay(300L)


            // If markFileWrittenByUs is working, the watcher must not re-parse the file
            // on an mtime-only change, so the page count must equal the baseline.
            val pageCountAfter = pageRepo.getAllPages().first().getOrNull()?.size ?: 0
            assertEquals(pageCountBaseline, pageCountAfter,
                "markFileWrittenByUs should suppress re-parse on mtime-only change; " +
                "page count must not grow from $pageCountBaseline to $pageCountAfter")
        } finally {
            graphDir.deleteRecursively()
        }
    }

    @Test
    fun graphWriter_onFileWritten_callback_fires() = runBlocking {
        val graphDir = tempGraphDir()
        try {
            val fileSystem = PlatformFileSystem()
            fileSystem.registerGraphRoot(graphDir.absolutePath)
            val pageRepo = InMemoryPageRepository()

            // Track callback invocations
            var callbackFilePath: String? = null
            @Suppress("DEPRECATION")
            val writer = GraphWriter(
                fileSystem,
                pageRepository = pageRepo,
                onFileWritten = { path -> callbackFilePath = path }
            )

            val now = Clock.System.now()
            val page = Page(
                uuid = "test-page-uuid",
                name = "WriterTestPage",
                createdAt = now,
                updatedAt = now
            )
            val blocks = listOf(
                Block(
                    uuid = "block-uuid-1",
                    pageUuid = page.uuid,
                    content = "Block written by GraphWriter",
                    level = 0,
                    position = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )

            writer.savePage(page, blocks, graphDir.absolutePath)

            // Verify the callback was invoked with the correct path
            assertNotNull(callbackFilePath, "onFileWritten callback should have been called")
            assertTrue(
                callbackFilePath!!.endsWith("pages/WriterTestPage.md"),
                "Callback should receive the page file path, got: $callbackFilePath"
            )

            // Verify the file exists and has correct content
            val content = fileSystem.readFile(callbackFilePath!!)
            assertNotNull(content, "File should have been written")
            assertTrue(content.contains("Block written by GraphWriter"), "File should contain block content")
        } finally {
            graphDir.deleteRecursively()
        }
    }

    @Test
    fun externalFileChange_data_class_structure() {
        var suppressCalled = false
        val event = ExternalFileChange(
            filePath = "/tmp/test.md",
            content = "- External content\n",
            suppress = { suppressCalled = true }
        )

        assertEquals("/tmp/test.md", event.filePath)
        assertEquals("- External content\n", event.content)
        assertTrue(!suppressCalled, "suppress should not be called yet")
        event.suppress()
        assertTrue(suppressCalled, "suppress should have been called")
    }

    @Test
    fun markFileWrittenByUs_then_savePage_integration() = runBlocking {
        val graphDir = tempGraphDir()
        try {
            val fileSystem = PlatformFileSystem()
            fileSystem.registerGraphRoot(graphDir.absolutePath)
            val pageRepo = InMemoryPageRepository()
            val blockRepo = InMemoryBlockRepository()
            val loader = GraphLoader(fileSystem, pageRepo, blockRepo)
            @Suppress("DEPRECATION")
            val writer = GraphWriter(fileSystem, pageRepository = pageRepo, onFileWritten = loader::markFileWrittenByUs)

            loader.loadGraph(graphDir.absolutePath) {}

            // Save a page via GraphWriter (onFileWritten fires → markFileWrittenByUs)
            val now = Clock.System.now()
            val page = Page(uuid = "p1", name = "IntegPage", createdAt = now, updatedAt = now)
            val blocks = listOf(
                Block(uuid = "b1", pageUuid = "p1", content = "Hello", level = 0, position = 0, createdAt = now, updatedAt = now)
            )
            writer.savePage(page, blocks, graphDir.absolutePath)

            // Verify the file was written
            val filePath = File(graphDir, "pages/IntegPage.md").absolutePath
            assertTrue(File(filePath).exists(), "Page file should exist on disk")

            // Verify the file content is correct
            val content = fileSystem.readFile(filePath)
            assertNotNull(content, "Should be able to read the file back")
            assertTrue(content.contains("Hello"), "File should contain the block content")
        } finally {
            graphDir.deleteRecursively()
        }
    }
}
