package dev.stapler.stelekit.stats

import dev.stapler.stelekit.model.EffectiveNotesPath
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileLibraryStatsProviderTest {

    private fun notesFolder(): File = Files.createTempDirectory("stats-notes").toFile().apply { deleteOnExit() }

    @Test fun `counts pages and journals under the notes folder`() = runBlocking {
        val notes = notesFolder()
        File(notes, "pages").mkdirs()
        File(notes, "journals").mkdirs()
        File(notes, "pages/Alpha.md").writeText("- [[Beta]]")
        File(notes, "pages/Beta.md").writeText("- content")
        File(notes, "journals/2026_10_15.md").writeText("- [[Alpha]]")

        val report = FileLibraryStatsProvider().collect(EffectiveNotesPath(notes.absolutePath))!!

        assertEquals(notes.absolutePath, report.graphPath)
        assertEquals(2, report.pageCount)
        assertEquals(1, report.journalCount)
    }

    @Test fun `a missing notes folder yields no report`() = runBlocking {
        val missing = File(notesFolder(), "absent")

        assertNull(FileLibraryStatsProvider().collect(EffectiveNotesPath(missing.absolutePath)))
    }
}
