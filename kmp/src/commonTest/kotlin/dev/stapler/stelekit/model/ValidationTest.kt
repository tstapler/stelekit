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

    // --- sanitizeContent: direct coverage ---

    @Test
    fun testSanitizeContentStripsC0ControlCharacters() {
        assertEquals("helloworld", Validation.sanitizeContent("hello\u0001world"))
        assertEquals("helloworld", Validation.sanitizeContent("hello\u001Fworld"))
    }

    @Test
    fun testSanitizeContentStripsC1ControlCharacters() {
        assertEquals("helloworld", Validation.sanitizeContent("hello\u0080world"))
        assertEquals("helloworld", Validation.sanitizeContent("hello\u009Fworld"))
    }

    @Test
    fun testSanitizeContentPreservesAllowedWhitespace() {
        assertEquals("hello\nworld", Validation.sanitizeContent("hello\nworld"))
        assertEquals("hello\rworld", Validation.sanitizeContent("hello\rworld"))
        assertEquals("hello\tworld", Validation.sanitizeContent("hello\tworld"))
    }

    @Test
    fun testSanitizeContentPreservesBoundaryPrintableCharacters() {
        // 0x20 (space) and 0x7E ('~') are the printable ASCII boundaries just outside
        // the C0/C1 ranges — must survive untouched.
        assertEquals(" hello~world ", Validation.sanitizeContent(" hello~world "))
        // 0x7F (DEL) is C0-adjacent but outside the stripped 0x00-0x1F range, and 0xA0
        // (NBSP) is just past the stripped 0x80-0x9F C1 range — neither is restricted.
        assertEquals("hello\u007Fworld", Validation.sanitizeContent("hello\u007Fworld"))
        assertEquals("hello\u00A0world", Validation.sanitizeContent("hello\u00A0world"))
    }

    @Test
    fun testSanitizeContentOnCleanStringIsNoOp() {
        val clean = "Just a normal sentence with punctuation, numbers (123), and emoji 🎉."
        assertEquals(clean, Validation.sanitizeContent(clean))
    }

    @Test
    fun testValidateContentSanitizesInsteadOfThrowing() {
        assertEquals("helloworld", Validation.validateContent("hello\u0001world"))
        assertEquals("helloworld", Validation.validateContent("hello\u0080world"))
    }
}
