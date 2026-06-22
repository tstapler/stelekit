package dev.stapler.stelekit.ui.components

import coil3.PlatformContext
import coil3.request.Options
import dev.stapler.stelekit.platform.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SteleKitAssetMapperTest {

    private val options = Options(context = PlatformContext.INSTANCE)

    @Test fun `rewrites relative asset path to file uri`() {
        val result = SteleKitAssetMapper("/home/user/graph").map("../assets/photo.jpg", options)
        assertEquals("file:///home/user/graph/assets/photo.jpg", result?.toString())
    }

    @Test fun `returns null for https urls`() {
        val result = SteleKitAssetMapper("/home/user/graph").map("https://example.com/img.png", options)
        assertNull(result)
    }

    @Test fun `returns null for unrelated strings`() {
        val result = SteleKitAssetMapper("/home/user/graph").map("some text", options)
        assertNull(result)
    }

    @Test fun `returns null for empty string`() {
        val result = SteleKitAssetMapper("/home/user/graph").map("", options)
        assertNull(result)
    }

    @Test fun `handles nested path in asset filename`() {
        val result = SteleKitAssetMapper("/graph/root").map("../assets/subdir/image.png", options)
        assertEquals("file:///graph/root/assets/subdir/image.png", result?.toString())
    }

    @Test fun `returns null for path traversal attempt`() {
        val result = SteleKitAssetMapper("/home/user/graph").map("../assets/../../../etc/passwd", options)
        assertNull(result)
    }

    @Test fun `returns null for backslash path traversal`() {
        val result = SteleKitAssetMapper("/home/user/graph").map("../assets/..\\secret", options)
        assertNull(result)
    }

    @Test fun `trims trailing slash from graph root to avoid double-slash URI`() {
        val result = SteleKitAssetMapper("/home/user/graph/").map("../assets/photo.jpg", options)
        assertEquals("file:///home/user/graph/assets/photo.jpg", result?.toString())
    }

    @Test fun `trims multiple trailing slashes`() {
        val result = SteleKitAssetMapper("/home/user/graph///").map("../assets/photo.jpg", options)
        assertEquals("file:///home/user/graph/assets/photo.jpg", result?.toString())
    }

    // Minimal FileSystem stub for testing resolveAssetUri delegation.
    // Only resolveAssetUri is overridden — all other methods return safe no-op defaults.
    private class StubFileSystem(private val resolvedUri: String?) : FileSystem {
        override fun getDefaultGraphPath() = ""
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun resolveAssetUri(graphRoot: String, relativePath: String): String? = resolvedUri
    }

    @Test fun `delegates to fileSystem resolveAssetUri when it returns a content uri`() {
        val contentUri = "content://com.android.externalstorage.documents/tree/primary%3Alogseq/assets/photo.jpg"
        val fs = StubFileSystem(contentUri)
        val result = SteleKitAssetMapper("saf://content%3A%2F%2Flogseq", fs).map("../assets/photo.jpg", options)
        assertEquals(contentUri, result?.toString())
    }

    @Test fun `falls back to file uri when fileSystem resolveAssetUri returns null`() {
        val fs = StubFileSystem(null)
        val result = SteleKitAssetMapper("/home/user/graph", fs).map("../assets/photo.jpg", options)
        assertEquals("file:///home/user/graph/assets/photo.jpg", result?.toString())
    }

    @Test fun `security check still runs before fileSystem delegation`() {
        val fs = StubFileSystem("content://anything")
        val result = SteleKitAssetMapper("/home/user/graph", fs).map("../assets/../../../etc/passwd", options)
        assertNull(result)
    }
}
