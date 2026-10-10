package dev.stapler.stelekit.git.merge

import dev.stapler.stelekit.git.model.ConflictFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test
    fun extractConflictSides_withNonConflictingLines_preservedInAllThreeSides() {
        val content = """
            - shared before
            <<<<<<< LOCAL
            - local only
            =======
            - remote only
            >>>>>>> REMOTE
            - shared after
        """.trimIndent()

        val fileSystem = StubFileSystem()
        val service = JournalMergeService(fileSystem = fileSystem)
        val (base, local, remote) = service.extractConflictSides(content)

        assertEquals("- shared before\n- local only\n- shared after", local)
        assertEquals("- shared before\n- remote only\n- shared after", remote)
        assertEquals("- shared before\n- shared after", base)
    }

    @Test
    fun extractConflictSides_multipleConflictHunks_allExtractedCorrectly() {
        val content = """
            - shared line A
            <<<<<<< LOCAL
            - local hunk 1
            =======
            - remote hunk 1
            >>>>>>> REMOTE
            - shared line B
            <<<<<<< LOCAL
            - local hunk 2
            =======
            - remote hunk 2
            >>>>>>> REMOTE
            - shared line C
        """.trimIndent()

        val service = JournalMergeService(fileSystem = StubFileSystem())
        val (base, local, remote) = service.extractConflictSides(content)

        assertTrue(local.contains("- local hunk 1"), "local must include first hunk: $local")
        assertTrue(local.contains("- local hunk 2"), "local must include second hunk: $local")
        assertTrue(remote.contains("- remote hunk 1"), "remote must include first hunk: $remote")
        assertTrue(remote.contains("- remote hunk 2"), "remote must include second hunk: $remote")
        assertTrue(base.contains("- shared line A"), "base must include shared lines: $base")
        assertTrue(base.contains("- shared line B"), "base must include middle shared line: $base")
        assertTrue(base.contains("- shared line C"), "base must include trailing shared line: $base")
        assertFalse(local.contains("<<<<<<< LOCAL"), "local must not contain markers: $local")
        assertFalse(remote.contains(">>>>>>> REMOTE"), "remote must not contain markers: $remote")
    }

    // ---- propose() ----

    @Test
    fun propose_standardConflict_returnsProposalWithCorrectSides() = runTest {
        val conflictContent = """
            - shared before
            <<<<<<< LOCAL
            - local entry
            =======
            - remote entry
            >>>>>>> REMOTE
            - shared after
        """.trimIndent()

        val fs = StubFileSystem(readResult = conflictContent)
        val service = JournalMergeService(fileSystem = fs)
        val conflictFile = ConflictFile(
            filePath = "/graph/journals/2026-06-12.md",
            wikiRelativePath = "journals/2026-06-12.md",
            hunks = emptyList(),
        )

        val proposal = service.propose(conflictFile, "/graph", writeBackupToDisk = false)

        assertEquals("/graph/journals/2026-06-12.md", proposal.filePath)
        assertTrue(proposal.localContent.contains("- local entry"), "localContent must include local-side lines")
        assertTrue(proposal.remoteContent.contains("- remote entry"), "remoteContent must include remote-side lines")
        assertTrue(proposal.proposedMerge.contains("- shared before"), "proposedMerge must include shared lines")
        assertNotNull(proposal.backupPath, "backupPath must be set")
        assertFalse(proposal.backupPath.contains(".md.md"), "backupPath must not have double .md extension")
        assertTrue(proposal.backupPath.endsWith(".md"), "backupPath must end with .md")
        // confidenceWarning should be false when proposed merge is close in size to both sides
        assertFalse(proposal.confidenceWarning, "confidenceWarning must be false for a well-merged proposal")
    }

    @Test
    fun propose_mergeProducesFewerLinesThanSides_confidenceWarningTrue() = runTest {
        // local deleted almost everything from a 20-line base (kept only the first 2 lines);
        // remote left that region untouched. Diff3MergeStrategy correctly honors local's
        // deletion (remote made no change there, so local's change wins cleanly, no conflict) —
        // the resulting merge is legitimately much smaller than remote's 20 lines, which is
        // exactly the case confidenceWarning exists to flag for user review.
        //
        // (This test previously relied on FallbackMergeStrategy's dedup bug — 20 *identical*
        // lines on both sides collapsed to 1 — as a stand-in for "merge much smaller than
        // inputs." Diff3MergeStrategy fixed that bug (identical content on both sides now
        // correctly stays as all 20 lines, not 1), so that scenario no longer produces a small
        // merge at all; this test now exercises a real content-loss scenario instead.)
        val base = (1..20).joinToString("\n") { "line $it" }
        val local = "line 1\nline 2"
        val baseLines = base.lines()
        val remote = baseLines.joinToString("\n") // unchanged
        val conflictContent = "<<<<<<< LOCAL\n$local\n||||||| BASE\n$base\n=======\n$remote\n>>>>>>> REMOTE"
        val fs = StubFileSystem(readResult = conflictContent)
        val service = JournalMergeService(fileSystem = fs)
        val conflictFile = ConflictFile(
            filePath = "/graph/journals/2026-06-01.md",
            wikiRelativePath = "journals/2026-06-01.md",
            hunks = emptyList(),
        )
        val proposal = service.propose(conflictFile, "/graph", writeBackupToDisk = false)
        // proposedMerge = "line 1\nline 2" (2 lines); remote has 20 lines. 2 < 20*0.9=18 → true
        assertTrue(proposal.confidenceWarning, "Expected confidenceWarning=true when merge is much smaller than inputs")
    }

    @Test
    fun propose_emptyConflict_returnsEmptyProposedMerge() = runTest {
        val fs = StubFileSystem(readResult = "")
        val service = JournalMergeService(fileSystem = fs)
        val conflictFile = ConflictFile(
            filePath = "/graph/journals/2026-06-01.md",
            wikiRelativePath = "journals/2026-06-01.md",
            hunks = emptyList(),
        )
        val proposal = service.propose(conflictFile, "/graph")
        assertEquals("", proposal.proposedMerge)
        assertFalse(proposal.backupPath.contains(".md.md"))
    }

    // ---- Stub FileSystem ----

    private class StubFileSystem(private val readResult: String? = null) : dev.stapler.stelekit.platform.FileSystem {
        val written = mutableMapOf<String, String>()

        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = readResult
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
