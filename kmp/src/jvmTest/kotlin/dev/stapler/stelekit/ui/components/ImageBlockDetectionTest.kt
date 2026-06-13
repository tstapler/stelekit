package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImageBlockDetectionTest {

    @Test fun `image-only block returns url and alt`() {
        val result = extractSingleImageNode("![My Photo](../assets/photo.jpg)")
        assertNotNull(result)
        assertEquals("../assets/photo.jpg", result.first)
        assertEquals("My Photo", result.second)
    }

    @Test fun `https url image-only block`() {
        val result = extractSingleImageNode("![](https://example.com/img.png)")
        assertNotNull(result)
        assertEquals("https://example.com/img.png", result.first)
    }

    @Test fun `text only block returns null`() {
        assertNull(extractSingleImageNode("just some text"))
    }

    @Test fun `mixed content returns null`() {
        assertNull(extractSingleImageNode("text ![photo](url) more text"))
    }

    @Test fun `empty string returns null`() {
        assertNull(extractSingleImageNode(""))
    }

    @Test fun `blank string returns null`() {
        assertNull(extractSingleImageNode("   "))
    }

    @Test fun `two images returns null`() {
        assertNull(extractSingleImageNode("![a](url1) ![b](url2)"))
    }

    // Logseq size-hint annotations — {:height 300, :width 400} appended after the image
    @Test fun `logseq height and width size hint is ignored`() {
        val result = extractSingleImageNode("![photo.png](../assets/photo.png){:height 300, :width 400}")
        assertNotNull(result)
        assertEquals("../assets/photo.png", result.first)
        assertEquals("photo.png", result.second)
    }

    @Test fun `logseq height-only size hint is ignored`() {
        val result = extractSingleImageNode("![img.png](../assets/img.png){:height 150}")
        assertNotNull(result)
        assertEquals("../assets/img.png", result.first)
    }

    @Test fun `logseq width-only size hint is ignored`() {
        val result = extractSingleImageNode("![img.png](../assets/img.png){:width 600}")
        assertNotNull(result)
        assertEquals("../assets/img.png", result.first)
    }

    @Test fun `size hint with surrounding whitespace is ignored`() {
        val result = extractSingleImageNode("  ![photo.png](../assets/photo.png){:height 300, :width 400}  ")
        assertNotNull(result)
        assertEquals("../assets/photo.png", result.first)
    }

    @Test fun `non-size-hint trailing text still returns null`() {
        assertNull(extractSingleImageNode("![photo](url) some caption text"))
    }

    @Test fun `image followed by second image returns null even with size hint`() {
        assertNull(extractSingleImageNode("![a](url1)![b](url2){:height 300}"))
    }
}
