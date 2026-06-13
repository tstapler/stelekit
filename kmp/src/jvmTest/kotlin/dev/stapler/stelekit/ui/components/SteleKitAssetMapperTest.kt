package dev.stapler.stelekit.ui.components

import coil3.PlatformContext
import coil3.request.Options
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
}
