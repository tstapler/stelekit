package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationTest {

    @Test
    fun testValidateContentLength() {
        val largeContent = "a".repeat(150_000)
        val validated = Validation.validateContent(largeContent)
        assertEquals(largeContent, validated)
    }

    // --- validateName: directory traversal ---

    @Test
    fun testEllipsisInPageNameIsAllowed() {
        // Logseq page names with "..." (ellipsis) must not be rejected as traversal
        val name = "Start with NO...The Negotiating Tools that the Pros Don't Want You to Know"
        assertEquals(name, Validation.validateName(name))
    }

    @Test
    fun testTrailingEllipsisIsAllowed() {
        val name = "And Another Thing... (The Hitchhiker's Guide to the Galaxy, #6)"
        assertEquals(name, Validation.validateName(name))
    }

    @Test
    fun testActualPathTraversalIsRejected() {
        assertFailsWith<IllegalArgumentException> { Validation.validateName("../secret") }
        assertFailsWith<IllegalArgumentException> { Validation.validateName("foo/../bar") }
        assertFailsWith<IllegalArgumentException> { Validation.validateName("something/..") }
    }

    @Test
    fun testDoubleDotAloneIsRejected() {
        assertFailsWith<IllegalArgumentException> { Validation.validateName("..") }
    }

    // --- validateName: slashes in page names ---

    @Test
    fun testSlashInPageNameIsAllowed() {
        // Logseq encodes "Cordless/Corded" as "Cordless%2FCorded" in filenames;
        // after decoding the page name contains a slash, which is valid.
        assertEquals("Cordless/Corded Wet/Dry Vacuum", Validation.validateName("Cordless/Corded Wet/Dry Vacuum"))
    }

    @Test
    fun testBackslashIsRejected() {
        assertFailsWith<IllegalArgumentException> { Validation.validateName("foo\\bar") }
    }

    // --- validateName: blank / null ---

    @Test
    fun testBlankNameIsRejected() {
        assertFailsWith<IllegalArgumentException> { Validation.validateName("") }
        assertFailsWith<IllegalArgumentException> { Validation.validateName("   ") }
    }
}
