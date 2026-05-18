package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertTrue

class ImageStoragePathResolverTest {

    @Test
    fun resolvePath_should_returnCorrectPath_when_graphPathAndUuidProvided() {
        val graphPath = "/home/user/my-graph"
        val uuid = "a3f8b2c1-d4e5-6789-abcd-ef0123456789"
        val result = ImageStoragePathResolver.resolvePath(graphPath, uuid)

        // Must start with graphPath/assets/images/
        assertTrue(result.startsWith("$graphPath/assets/images/"), "Path should start with $graphPath/assets/images/")
        // Must end with .jpg
        assertTrue(result.endsWith(".jpg"), "Path should end with .jpg")
        // UUID prefix (first 8 non-dash chars): a3f8b2c1
        assertTrue(result.contains("a3f8b2c1"), "Path should contain uuid prefix")
    }

    @Test
    fun assetsImagesDir_should_returnCorrectDirectory() {
        val graphPath = "/tmp/test-graph"
        val dir = ImageStoragePathResolver.assetsImagesDir(graphPath)
        assertTrue(dir == "$graphPath/assets/images")
    }
}
