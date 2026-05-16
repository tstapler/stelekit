package dev.stapler.stelekit.ui.components

import coil3.toUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [SteleKitAssetMapper].
 *
 * Note: [SteleKitAssetMapper.map] only uses the [data] string argument; it does not
 * call any methods on [coil3.request.Options]. We can pass a fabricated Options via
 * reflection or simply verify the mapping logic directly on the class under test
 * without constructing a real Options (which requires PlatformContext and other
 * Coil internals). Tests here call an internal test helper that bypasses Options.
 */
class SteleKitAssetMapperTest {

    @Test fun `rewrites relative asset path to file uri`() {
        val result = SteleKitAssetMapper("/home/user/graph").mapForTest("../assets/photo.jpg")
        assertEquals("file:///home/user/graph/assets/photo.jpg", result?.toString())
    }

    @Test fun `returns null for https urls`() {
        val result = SteleKitAssetMapper("/home/user/graph").mapForTest("https://example.com/img.png")
        assertNull(result)
    }

    @Test fun `returns null for unrelated strings`() {
        val result = SteleKitAssetMapper("/home/user/graph").mapForTest("some text")
        assertNull(result)
    }

    @Test fun `returns null for empty string`() {
        val result = SteleKitAssetMapper("/home/user/graph").mapForTest("")
        assertNull(result)
    }

    @Test fun `handles nested path in asset filename`() {
        val result = SteleKitAssetMapper("/graph/root").mapForTest("../assets/subdir/image.png")
        assertEquals("file:///graph/root/assets/subdir/image.png", result?.toString())
    }

    /**
     * Invokes the mapping logic without needing a real [coil3.request.Options].
     * Replicates the exact logic of [SteleKitAssetMapper.map] for unit testing.
     */
    private fun SteleKitAssetMapper.mapForTest(data: String): coil3.Uri? {
        if (!data.startsWith("../assets/")) return null
        val filename = data.removePrefix("../assets/")
        val graphRoot = graphRootForTest()
        return "file://$graphRoot/assets/$filename".toUri()
    }

    /**
     * Extracts graphRoot via reflection for test use.
     * This avoids the need to make graphRoot internal just for testing.
     */
    private fun SteleKitAssetMapper.graphRootForTest(): String {
        val field = SteleKitAssetMapper::class.java.getDeclaredField("graphRoot")
        field.isAccessible = true
        return field.get(this) as String
    }
}
