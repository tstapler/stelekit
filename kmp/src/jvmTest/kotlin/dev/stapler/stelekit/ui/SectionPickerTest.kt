package dev.stapler.stelekit.ui

import arrow.core.Either
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.sections.getSectionStates
import dev.stapler.stelekit.sections.putSectionStates
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Story 6.5 — Section picker and subscription-toggle tests.
 *
 * 1. File movement: selecting a new section in the picker calls
 *    [GraphWriter.movePageToSection] which renames the backing .md file to the
 *    new section's pagePathPrefix directory.
 *
 * 2. Subscription toggle persistence: [SectionState] changes are stored as lowercase
 *    JSON under the "section_states" key and survive a simulated app restart
 *    (re-reading from the same [Settings] instance).
 */
class SectionPickerTest {

    // ─── 1. GraphWriter.movePageToSection: file movement ─────────────────────

    @Test
    fun `movePageToSection renames backing file to new section pagePathPrefix directory`() =
        runBlocking {
            val renames = mutableListOf<Pair<String, String>>()
            val fs = object : FakeFileSystem() {
                override fun renameFile(from: String, to: String): Boolean {
                    renames.add(from to to)
                    return true
                }
            }
            val writer = GraphWriter(fileSystem = fs, graphPath = "/graph")
            val now = Clock.System.now()
            val page = Page(
                uuid = PageUuid("test-page-1"),
                name = "Meeting Notes",
                filePath = "/graph/pages/Meeting Notes.md",
                createdAt = now,
                updatedAt = now,
                sectionId = "",
            )

            val result = writer.movePageToSection(page, "acme-work", "pages/acme-work")

            assertTrue(result.isRight(), "movePageToSection must succeed; got: $result")
            assertEquals(1, renames.size, "Exactly one rename should be performed")
            val (from, to) = renames.first()
            assertEquals("/graph/pages/Meeting Notes.md", from, "Source path must match old filePath")
            assertEquals("/graph/pages/acme-work/Meeting Notes.md", to,
                "Destination must be under new section's pagePathPrefix")
            val updated = (result as Either.Right).value
            assertEquals("acme-work", updated.sectionId)
            assertEquals("/graph/pages/acme-work/Meeting Notes.md", updated.filePath)
        }

    @Test
    fun `movePageToSection with null filePath returns page with updated sectionId and no rename`() =
        runBlocking {
            val renames = mutableListOf<Pair<String, String>>()
            val fs = object : FakeFileSystem() {
                override fun renameFile(from: String, to: String): Boolean {
                    renames.add(from to to)
                    return true
                }
            }
            val writer = GraphWriter(fileSystem = fs, graphPath = "/graph")
            val now = Clock.System.now()
            val page = Page(
                uuid = PageUuid("test-page-2"),
                name = "Idea",
                filePath = null,
                createdAt = now,
                updatedAt = now,
                sectionId = "",
            )

            val result = writer.movePageToSection(page, "personal", "pages/personal")

            assertTrue(result.isRight())
            assertEquals(0, renames.size, "No rename when source filePath is null")
            assertEquals("personal", (result as Either.Right).value.sectionId)
        }

    @Test
    fun `movePageToSection to global root uses pages directory`() = runBlocking {
        val renames = mutableListOf<Pair<String, String>>()
        val fs = object : FakeFileSystem() {
            override fun renameFile(from: String, to: String): Boolean {
                renames.add(from to to)
                return true
            }
        }
        val writer = GraphWriter(fileSystem = fs, graphPath = "/graph")
        val now = Clock.System.now()
        val page = Page(
            uuid = PageUuid("test-page-3"),
            name = "Note",
            filePath = "/graph/pages/acme-work/Note.md",
            createdAt = now,
            updatedAt = now,
            sectionId = "acme-work",
        )

        val result = writer.movePageToSection(page, "", "pages")

        assertTrue(result.isRight())
        assertEquals(1, renames.size)
        val (_, to) = renames.first()
        assertEquals("/graph/pages/Note.md", to,
            "Moving to global must put file directly in pages/ root")
        assertEquals("", (result as Either.Right).value.sectionId)
    }

    // ─── 2. Subscription toggle persistence ──────────────────────────────────

    @Test
    fun `putSectionStates persists HIDDEN as lowercase json`() {
        val settings = InMemorySettings()

        settings.putSectionStates(mapOf("acme-work" to SectionState.HIDDEN))

        val stored = settings.getString("section_states", "")
        assertTrue(stored.isNotBlank(), "section_states must not be empty")
        assertTrue(stored.contains("acme-work"), "JSON must contain section id")
        assertTrue(stored.contains("hidden"), "State must be stored as lowercase 'hidden'")
        assertFalse(stored.contains("HIDDEN"), "State must NOT be stored as uppercase")
    }

    @Test
    fun `getSectionStates reads back HIDDEN after simulated restart`() {
        val settings = InMemorySettings()
        settings.putSectionStates(mapOf("acme-work" to SectionState.HIDDEN))

        // Simulate restart: re-read from the same Settings store
        val reloaded = settings.getSectionStates()

        assertEquals(SectionState.HIDDEN, reloaded["acme-work"],
            "acme-work must be HIDDEN after re-reading from the same Settings instance")
    }

    @Test
    fun `getSectionStates round-trips all three states`() {
        val settings = InMemorySettings()
        val original = mapOf(
            "acme-work" to SectionState.ACTIVE,
            "personal" to SectionState.HIDDEN,
            "health" to SectionState.REMOVED,
        )

        settings.putSectionStates(original)
        val reloaded = settings.getSectionStates()

        assertEquals(SectionState.ACTIVE, reloaded["acme-work"])
        assertEquals(SectionState.HIDDEN, reloaded["personal"])
        assertEquals(SectionState.REMOVED, reloaded["health"])
    }

    @Test
    fun `getSectionStates on empty settings returns empty map`() {
        val settings = InMemorySettings()

        val result = settings.getSectionStates()

        assertTrue(result.isEmpty(), "Fresh settings must return empty map")
    }

    @Test
    fun `putSectionStates overwrites previous states`() {
        val settings = InMemorySettings()
        settings.putSectionStates(mapOf("acme-work" to SectionState.ACTIVE))

        settings.putSectionStates(mapOf("acme-work" to SectionState.REMOVED))

        val reloaded = settings.getSectionStates()
        assertEquals(SectionState.REMOVED, reloaded["acme-work"],
            "Second write must overwrite first")
    }
}
