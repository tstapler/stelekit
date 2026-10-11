package dev.stapler.stelekit.ui.annotate

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JpegDataUrlTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parseJpegDataUrl_jpegPrefixed_decodesBytes() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val dataUrl = JPEG_DATA_URL_PREFIX + Base64.Default.encode(bytes)

        val result = parseJpegDataUrl(dataUrl)

        assertEquals(bytes.toList(), result?.toList())
    }

    @Test
    fun parseJpegDataUrl_pngPrefixed_returnsNull() {
        val result = parseJpegDataUrl("data:image/png;base64,iVBOR...")

        assertNull(result)
    }

    @Test
    fun parseJpegDataUrl_garbage_returnsNull() {
        val result = parseJpegDataUrl("not a data url at all")

        assertNull(result)
    }
}
