package dev.stapler.stelekit.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplaceFileAtomicallyTest {
    @Test
    fun overwritesAnExistingDestinationAndRemovesTheSource() {
        val dir = Files.createTempDirectory("replace_").toFile()
        try {
            val fs = PlatformFileSystem.withRoot(dir.path)
            val dest = dir.resolve("page.md").apply { writeText("old") }
            val src = dir.resolve("page.md.tmp").apply { writeText("new") }

            assertTrue(fs.supportsAtomicReplace(dest.path))
            assertTrue(fs.replaceFileAtomically(src.path, dest.path))

            assertEquals("new", dest.readText())
            assertFalse(src.exists(), "unlike renameFile, the source is consumed")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingSourceFailsAndLeavesTheDestination() {
        val dir = Files.createTempDirectory("replace_").toFile()
        try {
            val fs = PlatformFileSystem.withRoot(dir.path)
            val dest = dir.resolve("page.md").apply { writeText("old") }
            assertFalse(fs.replaceFileAtomically(dir.resolve("nope.tmp").path, dest.path))
            assertEquals("old", dest.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
