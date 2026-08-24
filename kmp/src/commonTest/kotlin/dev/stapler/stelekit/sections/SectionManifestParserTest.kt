package dev.stapler.stelekit.sections

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SectionManifestParserTest {

    @Test
    fun `valid two-section TOML parses all fields correctly`() {
        val toml = """
            version = 1

            [[section]]
            id = "acme-work"
            displayName = "Acme Work"
            color = "#4A90D9"
            pagePathPrefix = "pages/acme-work"
            journalPathPrefix = "journals/acme-work"
            sensitivity = "sensitive"

            [[section]]
            id = "personal"
            displayName = "Personal"
            pagePathPrefix = "pages/personal"
            journalPathPrefix = "journals/personal"
        """.trimIndent()

        val fs = FakeFileSystem().apply { writeFile("/graph/${SectionManifest.FILENAME}", toml) }
        val manifest = SectionManifestParser(fs).parse("/graph").getOrNull()

        assertNotNull(manifest)
        assertEquals(1, manifest.version)
        assertEquals(2, manifest.sections.size)

        val acme = manifest.sections[0]
        assertEquals("acme-work", acme.id)
        assertEquals("Acme Work", acme.displayName)
        assertEquals("#4A90D9", acme.color)
        assertEquals("pages/acme-work", acme.pagePathPrefix)
        assertEquals("journals/acme-work", acme.journalPathPrefix)
        assertEquals("sensitive", acme.sensitivity)

        val personal = manifest.sections[1]
        assertEquals("personal", personal.id)
        assertEquals("Personal", personal.displayName)
        assertNull(personal.color)
        assertEquals("pages/personal", personal.pagePathPrefix)
        assertEquals("journals/personal", personal.journalPathPrefix)
        assertEquals("normal", personal.sensitivity)
    }

    @Test
    fun `missing file returns empty manifest`() {
        // Implementation returns SectionManifest().right() (not null) when file is absent.
        val manifest = SectionManifestParser(FakeFileSystem()).parse("/graph").getOrNull()
        assertNotNull(manifest)
        assertTrue(manifest.sections.isEmpty(), "Expected empty sections for missing file")
    }

    @Test
    fun `unknown TOML fields are silently ignored`() {
        val toml = """
            version = 1
            unknown_root_field = "ignored"

            [[section]]
            id = "notes"
            displayName = "Notes"
            pagePathPrefix = "pages/notes"
            journalPathPrefix = "journals/notes"
            unknown_section_field = "also ignored"
        """.trimIndent()

        val fs = FakeFileSystem().apply { writeFile("/graph/${SectionManifest.FILENAME}", toml) }
        val result = SectionManifestParser(fs).parse("/graph")
        assertTrue(result.isRight(), "Parse should succeed despite unknown fields; got: $result")
        assertEquals(1, result.getOrNull()!!.sections.size)
    }

    @Test
    fun `sensitivity defaults to normal when omitted`() {
        val toml = """
            version = 1

            [[section]]
            id = "notes"
            displayName = "Notes"
            pagePathPrefix = "pages/notes"
            journalPathPrefix = "journals/notes"
        """.trimIndent()

        val fs = FakeFileSystem().apply { writeFile("/graph/${SectionManifest.FILENAME}", toml) }
        val manifest = SectionManifestParser(fs).parse("/graph").getOrNull()

        assertNotNull(manifest)
        assertEquals("normal", manifest.sections[0].sensitivity)
    }
}
