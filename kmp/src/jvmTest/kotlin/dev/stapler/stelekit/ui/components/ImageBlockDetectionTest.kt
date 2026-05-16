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
}
