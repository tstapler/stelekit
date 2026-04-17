package dev.stapler.stelekit.outliner

import dev.stapler.stelekit.model.Block
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTest {

    private val pipeline = OutlinerPipeline()

    @Test
    fun testExtractReferences() {
        val block = Block(
            uuid = "00000000-0000-0000-0000-000000000001",
            pageUuid = "page-1",
            content = "This is a [[page]] with a ((00000000-0000-0000-0000-000000000002)) reference and a #tag",
            position = 0,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            properties = mapOf("tags" to "property-tag")
        )

        val refs = pipeline.extractReferences(block)

        assertTrue(refs.pages.contains("page"))
        assertTrue(refs.blocks.contains("00000000-0000-0000-0000-000000000002"))
        assertTrue(refs.tags.contains("tag"))
        assertTrue(refs.tags.contains("property-tag"))
        assertEquals(1, refs.pages.size)
        assertEquals(1, refs.blocks.size)
        assertEquals(2, refs.tags.size)
    }

    @Test
    fun testProcessBlock() {
        val block = Block(
            uuid = "00000000-0000-0000-0000-000000000001",
            pageUuid = "page-1",
            content = """
                title:: My Block
                tags:: tag1, tag2
                
                  Some content with spaces  
            """.trimIndent(),
            position = 0,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        val processed = pipeline.processBlock(block)

        assertEquals("Some content with spaces", processed.content)
        assertEquals("My Block", processed.properties["title"])
        assertEquals("tag1, tag2", processed.properties["tags"])
    }

    @Test
    fun testReferenceExtractionWithProperties() {
        val block = Block(
            uuid = "00000000-0000-0000-0000-000000000001",
            pageUuid = "page-1",
            content = """
                tags:: [[work]], #urgent
                
                Check ((00000000-0000-0000-0000-000000000002))
            """.trimIndent(),
            position = 0,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        val processed = pipeline.processBlock(block)
        val refs = pipeline.extractReferences(processed)

        assertTrue(refs.pages.contains("work"))
        assertTrue(refs.tags.contains("urgent"))
        assertTrue(refs.tags.contains("work"))
        assertTrue(refs.blocks.contains("00000000-0000-0000-0000-000000000002"))
    }
}
