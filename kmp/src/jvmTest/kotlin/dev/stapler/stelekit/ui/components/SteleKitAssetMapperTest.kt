package dev.stapler.stelekit.ui.components

import coil3.PlatformContext
import coil3.request.Options
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
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

    @Test fun `returns null for percent-encoded dot traversal — lowercase`() {
        // %2e%2e decodes to ".." — bypasses the literal ".." guard without this fix.
        val result = SteleKitAssetMapper("/home/user/graph").map("../assets/%2e%2e/%2e%2e/etc/passwd", options)
        assertNull(result)
    }

    @Test fun `returns null for percent-encoded dot traversal — uppercase`() {
        val result = SteleKitAssetMapper("/home/user/graph").map("../assets/%2E%2E/secret", options)
        assertNull(result)
    }

    @Test fun `returns null for mixed literal and encoded traversal`() {
        val result = SteleKitAssetMapper("/home/user/graph").map("../assets/%2e./secret", options)
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

    // SAF branch: fileSystem.buildAssetUri() provides a content:// URI

    @Test fun `uses buildAssetUri result when fileSystem provides one`() {
        val fakeFs = object : FakeFileSystem() {
            override fun buildAssetUri(graphRoot: String, relativePath: String) =
                "content://com.example/document/$relativePath"
        }
        val result = SteleKitAssetMapper("saf://root", fakeFs).map("../assets/images/photo.jpg", options)
        assertEquals("content://com.example/document/assets/images/photo.jpg", result?.toString())
    }

    @Test fun `falls back to file uri when buildAssetUri returns null`() {
        val result = SteleKitAssetMapper("/graph/root", FakeFileSystem()).map("../assets/photo.jpg", options)
        assertEquals("file:///graph/root/assets/photo.jpg", result?.toString())
    }

    @Test fun `buildAssetUri not called for percent-encoded traversal attempt`() {
        var called = false
        val fakeFs = object : FakeFileSystem() {
            override fun buildAssetUri(graphRoot: String, relativePath: String): String? {
                called = true
                return "content://should-not-reach"
            }
        }
        val result = SteleKitAssetMapper("saf://root", fakeFs).map("../assets/%2e%2e/secret", options)
        assertNull(result)
        assertEquals(false, called, "buildAssetUri must not be reached for traversal inputs")
    }
}

class SteleKitSafPathMapperTest {

    private val options = Options(context = PlatformContext.INSTANCE)

    @Test fun `returns null for non-saf uri`() {
        val mapper = SteleKitSafPathMapper(FakeFileSystem())
        assertNull(mapper.map("file:///some/path.jpg", options))
        assertNull(mapper.map("../assets/foo.jpg", options))
        assertNull(mapper.map("https://example.com/img.png", options))
    }

    @Test fun `delegates to resolveLoadableUri for saf uri`() {
        val resolved = "content://com.android.externalstorage.documents/document/primary%3Asomefile.jpg"
        val fakeFs = object : FakeFileSystem() {
            override fun resolveLoadableUri(path: String): String? = resolved
        }
        val result = SteleKitSafPathMapper(fakeFs).map("saf://com.android.externalstorage.documents/tree/primary", options)
        assertEquals(resolved, result?.toString())
    }

    @Test fun `returns null when resolveLoadableUri returns null`() {
        val fakeFs = object : FakeFileSystem() {
            override fun resolveLoadableUri(path: String): String? = null
        }
        assertNull(SteleKitSafPathMapper(fakeFs).map("saf://some/path", options))
    }
}
