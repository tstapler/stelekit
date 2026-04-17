package dev.stapler.stelekit.model

import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelTest {

    @Test
    fun testBlockCreation() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val block = Block(
            id = 1,
            uuid = "12345678-1234-1234-1234-123456789abc",
            pageId = 1,
            content = "Test block content",
            position = 0,
            createdAt = now,
            updatedAt = now
        )

        assertEquals(1L, block.id)
        assertEquals("Test block content", block.content)
        assertTrue(block.refs.isEmpty())
        assertTrue(block.tags.isEmpty())
        assertEquals(Block.FORMAT_MARKDOWN, block.format)
    }

    @Test
    fun testPageCreation() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val page = Page(
            id = 1,
            uuid = "12345678-1234-1234-1234-123456789abc",
            name = "test-page",
            title = "Test Page",
            createdAt = now,
            updatedAt = now
        )

        assertEquals("test-page", page.name)
        assertEquals("Test Page", page.title)
        assertEquals("test-page", page.fullName)
        assertTrue(page.aliases.isEmpty())
    }

    @Test
    fun testGraphCreation() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val graph = Graph(
            uuid = "12345678-1234-1234-1234-123456789abc",
            name = "Test Graph",
            createdAt = now,
            rootPath = "/test/graph"
        )

        assertEquals("Test Graph", graph.name)
        assertEquals("/test/graph", graph.rootPath)
        assertEquals("Test Graph", graph.displayName)
        assertFalse(graph.isRtcEnabled)
        assertFalse(graph.isE2eeEnabled)
    }

    @Test
    fun testFileCreation() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val file = File(
            path = "/test/file.md",
            content = "# Test Content",
            createdAt = now,
            lastModifiedAt = now,
            size = 15
        )

        assertEquals("/test/file.md", file.path)
        assertEquals("md", file.extension)
        assertEquals("file.md", file.fileName)
        assertTrue(file.isMarkdown)
    }
}