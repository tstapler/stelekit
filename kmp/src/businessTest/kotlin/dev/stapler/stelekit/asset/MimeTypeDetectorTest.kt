package dev.stapler.stelekit.asset

import kotlin.test.Test
import kotlin.test.assertEquals

class MimeTypeDetectorTest {
    @Test fun `JPEG magic bytes detected`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        assertEquals("image/jpeg", MimeTypeDetector.detect(bytes, "photo.jpg"))
    }

    @Test fun `PNG magic bytes detected`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        assertEquals("image/png", MimeTypeDetector.detect(bytes, "image.png"))
    }

    @Test fun `PDF magic bytes detected`() {
        val bytes = byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte())
        assertEquals("application/pdf", MimeTypeDetector.detect(bytes, "doc.pdf"))
    }

    @Test fun `MP4 ftyp magic bytes detected`() {
        val bytes = byteArrayOf(0, 0, 0, 0, 0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte())
        assertEquals("video/mp4", MimeTypeDetector.detect(bytes, "video.mp4"))
    }

    @Test fun `extension fallback for mp3`() {
        val bytes = byteArrayOf(0, 0, 0, 0)
        assertEquals("audio/mpeg", MimeTypeDetector.detect(bytes, "song.mp3"))
    }

    @Test fun `0-byte file falls back to extension`() {
        val bytes = ByteArray(0)
        assertEquals("application/pdf", MimeTypeDetector.detect(bytes, "doc.pdf"))
    }

    @Test fun `4-byte file falls back to extension no exception`() {
        val bytes = byteArrayOf(0, 0, 0, 0)
        assertEquals("audio/mpeg", MimeTypeDetector.detect(bytes, "song.mp3"))
    }

    @Test fun `7-byte file shorter than MP4 pattern falls back to extension`() {
        val bytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0)
        assertEquals("video/mp4", MimeTypeDetector.detect(bytes, "video.mp4"))
    }

    @Test fun `unknown extension returns octet-stream`() {
        val mime = MimeTypeDetector.detect(ByteArray(0), "file.xyz123")
        assertEquals("application/octet-stream", mime)
    }
}
