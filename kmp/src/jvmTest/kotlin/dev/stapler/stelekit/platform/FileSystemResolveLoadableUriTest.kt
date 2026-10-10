package dev.stapler.stelekit.platform

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSystemResolveLoadableUriTest {

    private val fs = FakeFileSystem()

    @Test fun `file scheme returned as-is`() {
        assertEquals("file:///home/user/graph/photo.jpg", fs.resolveLoadableUri("file:///home/user/graph/photo.jpg"))
    }

    @Test fun `content scheme returned as-is`() {
        val uri = "content://com.android.providers.media/image/42"
        assertEquals(uri, fs.resolveLoadableUri(uri))
    }

    @Test fun `http scheme returned as-is`() {
        assertEquals("http://example.com/img.png", fs.resolveLoadableUri("http://example.com/img.png"))
    }

    @Test fun `https scheme returned as-is`() {
        assertEquals("https://example.com/img.png", fs.resolveLoadableUri("https://example.com/img.png"))
    }

    @Test fun `absolute path gets file scheme prepended`() {
        assertEquals("file:///home/user/graph/assets/image.jpg", fs.resolveLoadableUri("/home/user/graph/assets/image.jpg"))
    }

    @Test fun `saf scheme returns null on base FileSystem`() {
        // saf:// handling requires Android platform override; default returns null.
        assertNull(fs.resolveLoadableUri("saf://content%3A%2F%2Fsome.provider/tree/primary"))
    }

    @Test fun `relative path returns null`() {
        assertNull(fs.resolveLoadableUri("../assets/photo.jpg"))
    }

    @Test fun `empty string returns null`() {
        assertNull(fs.resolveLoadableUri(""))
    }
}
