package dev.stapler.stelekit.git.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalMergeServiceTest {

    // ---- isJournalFile ----

    @Test
    fun isJournalFile_dashSeparated_returnsTrue() {
        assertTrue(JournalMergeService.isJournalFile("2026-06-12.md"))
    }

    @Test
    fun isJournalFile_underscoreSeparated_returnsTrue() {
        assertTrue(JournalMergeService.isJournalFile("2026_06_12.md"))
    }

    @Test
    fun isJournalFile_nonJournalPage_returnsFalse() {
        assertFalse(JournalMergeService.isJournalFile("my-page.md"))
    }

    // ---- extractConflictSides ----

    @Test
    fun extractConflictSides_standardMarkers_correctLocalRemote() {
        val content = """
            <<<<<<< LOCAL
            local content
            =======
            remote content
            >>>>>>> REMOTE
        """.trimIndent()

        val fileSystem = StubFileSystem()
        val service = JournalMergeService(fileSystem = fileSystem)
        val (base, local, remote) = service.extractConflictSides(content)

        assertEquals("local content", local)
        assertEquals("remote content", remote)
        assertEquals("", base) // no base section
    }

    @Test
    fun extractConflictSides_diff3StyleMarkers_correctBaseExtracted() {
        val content = """
            <<<<<<< LOCAL
            local content
            ||||||| BASE
            base content
            =======
            remote content
            >>>>>>> REMOTE
        """.trimIndent()

        val fileSystem = StubFileSystem()
        val service = JournalMergeService(fileSystem = fileSystem)
        val (base, local, remote) = service.extractConflictSides(content)

        assertEquals("local content", local)
        assertEquals("base content", base)
        assertEquals("remote content", remote)
    }

    // ---- Stub FileSystem ----

    private class StubFileSystem : dev.stapler.stelekit.platform.FileSystem {
        val written = mutableMapOf<String, String>()

        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean {
            written[path] = content
            return true
        }
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }
}
