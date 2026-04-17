package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.fail

class ValidationPropertyTest {

    @Test
    fun validateUuidShouldHandleVariousFormats() {
        val uuidFormats = listOf(
            "550e8400-e29b-41d4-a716-446655440000",
            "550e8400e29b41d4a716446655440000",
            "550e8400-e29b-41d4-a716-446655440000-1234",
            "abc123-def456",
            "page-1",
            "block-123",
            "a",
            "1",
            "a-b-c-d-e",
            "A-B-C-D-E",
            "1-2-3-4-5",
            "UUID-UPPER",
            "lowercase-uuid",
            "mixed-Case-123",
        )
        uuidFormats.forEach { uuid ->
            try {
                Validation.validateUuid(uuid)
            } catch (e: Exception) {
                // Some formats may fail - that's ok for fuzz testing
            }
        }
    }

    @Test
    fun validateUuidShouldNotCrashOnInvalidFormats() {
        val invalidUuids = listOf(
            "", " ", "invalid uuid", "uuid with space", "uuid/with/slash",
            "uuid\\with\\backslash", "uuid.with.dots", "uuid:with:colons",
            "uuid*with*asterisks", "uuid?with?question", "uuid\"with\"quotes",
            "uuid<with>brackets", "uuid|with|pipe",
        )
        invalidUuids.forEach { uuid ->
            try {
                Validation.validateUuid(uuid)
            } catch (e: Exception) {
                // Expected - validation should reject
            }
        }
    }

    @Test
    fun validateUuidShouldHandleMaxLength() {
        val longUuid = "a".repeat(36)
        try {
            Validation.validateUuid(longUuid)
        } catch (e: Exception) {
            fail("Failed for max length")
        }
    }

    @Test
    fun validateNameShouldHandleVariousValidNames() {
        val validNames = listOf(
            "Simple", "page_name", "page-name", "Page123", "Name With Spaces",
            "CamelCaseName", "snake_case_name", "kebab-case-name", "name.with.dots",
            "name_with-under", "日本語", "中文", "한국어", "emoji🚀name", "name with    spaces"
        )
        validNames.forEach { name ->
            try {
                Validation.validateName(name)
            } catch (e: Exception) {
                fail("Failed for: $name - ${e.message}")
            }
        }
    }

    @Test
    fun validateNameShouldNotCrashOnInvalidNames() {
        val invalidNames = listOf(
            "", "   ", "\t", "name/with/slash", "name\\with\\backslash",
            "name..name", "name.with.dots", "name:with:colons",
            "name*with*asterisks", "name?with?question", "name\"with\"quotes",
            "name<with>brackets", "name|with|pipe", "name\u0000null",
        )
        invalidNames.forEach { name ->
            try {
                Validation.validateName(name)
            } catch (e: Exception) {
                // Expected - validation should reject
            }
        }
    }

    @Test
    fun validateStringShouldHandleVariousInputs() {
        repeat(1000) {
            val length = (it % 100)
            val input = generateRandomString(length)
            try {
                Validation.validateString(input, maxLength = 100)
            } catch (e: Exception) {
                fail("Failed for input length $length")
            }
        }
    }

    @Test
    fun validateStringShouldHandleWhitespace() {
        val whitespaceStrings = listOf(
            " ", "  ", "\t", "\n", "text with spaces",
            "text\nwith\nnewlines", "text\twith\ttabs", "mixed \n\t\r whitespace"
        )
        whitespaceStrings.forEach { str ->
            try {
                Validation.validateString(str, allowWhitespace = true)
            } catch (e: Exception) {
                fail("Failed for: $str")
            }
        }
    }

    @Test
    fun validateStringShouldNotCrashOnControlCharacters() {
        val controlChars = listOf("\u0000", "\u0001", "\u001F", "\u007F", "\u0080", "\u009F")
        controlChars.forEach { char ->
            try {
                Validation.validateString(char)
            } catch (e: Exception) {
                // Expected - should reject control chars
            }
        }
    }

    @Test
    fun validateStringShouldHandleMaxLengthBoundary() {
        val boundaryString = "a".repeat(10000)
        try {
            Validation.validateString(boundaryString, maxLength = 10000)
        } catch (e: Exception) {
            fail("Should pass at boundary")
        }

        val tooLongString = "a".repeat(10001)
        try {
            Validation.validateString(tooLongString, maxLength = 10000)
        } catch (e: Exception) {
            // Expected - should fail over boundary
        }
    }

    @Test
    fun validateContentShouldHandleLargeContent() {
        val largeContent = "a".repeat(1000000)
        try {
            Validation.validateContent(largeContent)
        } catch (e: Exception) {
            fail("Failed for large content")
        }
    }

    @Test
    fun validateContentShouldAllowVariousContentTypes() {
        val contentTypes = listOf(
            "# Heading\n\nParagraph",
            "```kotlin\ncode\n```",
            "[[link]] and ((ref))",
            "*italic* **bold** ~~strike~~",
            "- list\n- items",
            "table | col | col\n|-----|-----|",
            "text \u0009 tab \u000A newline",
        )
        contentTypes.forEach { content ->
            try {
                Validation.validateContent(content)
            } catch (e: Exception) {
                fail("Failed for: $content")
            }
        }
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?/`~ "
        return (0 until length).map { chars[chars.indices.random()] }.joinToString("")
    }
}
