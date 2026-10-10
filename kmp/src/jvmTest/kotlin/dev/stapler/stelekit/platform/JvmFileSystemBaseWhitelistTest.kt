package dev.stapler.stelekit.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JvmFileSystemBaseWhitelistTest {

    @Test
    fun `readFile returns null for path outside whitelist`() {
        // Write the file directly via Java IO, bypassing PlatformFileSystem,
        // so the test is not vacuous (file genuinely exists on disk).
        val tmpFile = File("/tmp/stelekit_whitelist_test_${System.currentTimeMillis()}.txt")
        tmpFile.writeText("hello")
        try {
            val fs = PlatformFileSystem()
            // /tmp is not in the default whitelist — must block and return null.
            assertNull(fs.readFile(tmpFile.absolutePath))
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `readFile succeeds for path inside user home`() {
        val dir = File(System.getProperty("user.home"), "stelekit_whitelist_test_${System.currentTimeMillis()}")
        val file = File(dir, "test.txt")
        dir.mkdirs()
        file.writeText("hello")
        try {
            val fs = PlatformFileSystem()
            // user.home is in the default whitelist — read must succeed.
            assertNotNull(fs.readFile(file.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writeFile returns false for path outside whitelist`() {
        val fs = PlatformFileSystem()
        val result = fs.writeFile("/tmp/stelekit_whitelist_write_${System.currentTimeMillis()}.txt", "hello")
        // /tmp not whitelisted — write must be denied.
        assertEquals(false, result)
    }
}
