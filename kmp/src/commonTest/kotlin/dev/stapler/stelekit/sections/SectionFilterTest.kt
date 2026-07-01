package dev.stapler.stelekit.sections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SectionFilterTest {

    private val acmeSection = SectionDefinition(
        id = "acme-work",
        displayName = "Acme Work",
        pagePathPrefix = "pages/acme-work",
        journalPathPrefix = "journals/acme-work",
    )
    private val personalSection = SectionDefinition(
        id = "personal",
        displayName = "Personal",
        pagePathPrefix = "pages/personal",
        journalPathPrefix = "journals/personal",
    )
    private val manifest = SectionManifest(sections = listOf(acmeSection, personalSection))

    private fun filter(vararg states: Pair<String, SectionState>) =
        SectionFilter(manifest, mapOf(*states))

    // ── excludes() ───────────────────────────────────────────────────────────

    @Test
    fun `excludes returns false for global page not matching any section prefix`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.REMOVED)
        assertFalse(f.excludes("/graph/pages/My Global Page.md"))
    }

    @Test
    fun `excludes returns false for page under ACTIVE section`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.REMOVED)
        assertFalse(f.excludes("/graph/pages/acme-work/Work Page.md"))
    }

    @Test
    fun `excludes returns true for page under REMOVED section`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.REMOVED)
        assertTrue(f.excludes("/graph/pages/personal/Private Page.md"))
    }

    @Test
    fun `excludes returns true for page under HIDDEN section`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.HIDDEN)
        assertTrue(f.excludes("/graph/pages/personal/Private Page.md"))
    }

    @Test
    fun `excludes returns true for journal under REMOVED section`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.REMOVED)
        assertTrue(f.excludes("/graph/journals/personal/2026-06-30.md"))
    }

    @Test
    fun `excludes returns false for journal under ACTIVE section`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.REMOVED)
        assertFalse(f.excludes("/graph/journals/acme-work/2026-06-30.md"))
    }

    @Test
    fun `excludes returns false when path does not match any prefix`() {
        val f = filter("acme-work" to SectionState.REMOVED, "personal" to SectionState.REMOVED)
        assertFalse(f.excludes("/graph/attachments/photo.png"))
    }

    // ── sectionIdForPath() ───────────────────────────────────────────────────

    @Test
    fun `sectionIdForPath returns section id for page under section prefix`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.ACTIVE)
        assertEquals("acme-work", f.sectionIdForPath("/graph/pages/acme-work/My Work Page.md"))
    }

    @Test
    fun `sectionIdForPath returns empty string for global page not matching any prefix`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.ACTIVE)
        assertEquals("", f.sectionIdForPath("/graph/pages/My Global Page.md"))
    }

    @Test
    fun `sectionIdForPath returns section id for journal under section prefix`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.ACTIVE)
        assertEquals("acme-work", f.sectionIdForPath("/graph/journals/acme-work/2026-06-30.md"))
    }

    @Test
    fun `sectionIdForPath returns empty string for unmatched path`() {
        val f = filter("acme-work" to SectionState.ACTIVE, "personal" to SectionState.ACTIVE)
        assertEquals("", f.sectionIdForPath("/graph/attachments/photo.png"))
    }
}
