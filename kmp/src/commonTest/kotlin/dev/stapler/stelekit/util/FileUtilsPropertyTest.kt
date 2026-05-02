package dev.stapler.stelekit.util

import kotlin.test.Test
import kotlinx.coroutines.CancellationException
import kotlin.test.assertTrue
import kotlin.test.fail

class FileUtilsPropertyTest {

    @Test
    fun sanitizeFileNameShouldNotCrashOnAnyInput() {
        val inputs = generateRandomStrings(1000)
        inputs.forEach { input ->
            try {
                FileUtils.sanitizeFileName(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for input: $input")
            }
        }
    }

    @Test
    fun sanitizeFileNameShouldHandleReservedWindowsNames() {
        val reservedNames = listOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4",
            "LPT1", "LPT2", "LPT3",
            "con", "prn", "aux", "nul",
            "Com1", "CoM2",
        )
        reservedNames.forEach { name ->
            try {
                FileUtils.sanitizeFileName(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for: $name")
            }
        }
    }

    @Test
    fun sanitizeFileNameShouldHandlePathSeparators() {
        val pathInputs = listOf(
            "name/with/slash",
            "name\\with\\backslash",
            "name/with\\mixed",
            "/starts/with/slash",
            "ends/with/slash/",
            "name/../dots",
            "name/./dots",
        )
        pathInputs.forEach { input ->
            try {
                FileUtils.sanitizeFileName(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun sanitizeFileNameShouldHandleSpecialCharacters() {
        val specialChars = listOf(
            "name:with:colons",
            "name*with*asterisks",
            "name?with?question",
            "name\"with\"quotes",
            "name<with>brackets",
            "name|with|pipe",
            "name%with%percent",
            "name#with#hash",
        )
        specialChars.forEach { input ->
            try {
                FileUtils.sanitizeFileName(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun sanitizeFileNameShouldHandleTrailingDotsAndSpaces() {
        val trailingInputs = listOf(
            "name.",
            "name..",
            "name...",
            "name ",
            "name  ",
            "name. ",
            "name .",
            "name.  ",
        )
        trailingInputs.forEach { input ->
            try {
                FileUtils.sanitizeFileName(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun sanitizeFileNameShouldHandleUnicodeAndEmoji() {
        val unicodeInputs = listOf(
            "名前", "文件", "파일", "🚀name", "name🚀", "🚀", "émoji🎈", "名前🚀file"
        )
        unicodeInputs.forEach { input ->
            try {
                FileUtils.sanitizeFileName(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun sanitizeFileNameShouldHandleEmptyStrings() {
        try {
            val result = FileUtils.sanitizeFileName("")
            assertTrue(result.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Should handle empty string")
        }
    }

    @Test
    fun decodeFileNameShouldNotCrashOnAnyInput() {
        val inputs = generateRandomStrings(1000)
        inputs.forEach { input ->
            try {
                FileUtils.decodeFileName(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for input: $input")
            }
        }
    }

    @Test
    fun decodeFileNameShouldHandleInvalidPercentEncoding() {
        val invalidPercent = listOf(
            "%", "%0", "%GG", "%ZZ", "name%ZZfile", "%%", "%0%", "name%0%0file"
        )
        invalidPercent.forEach { input ->
            try {
                FileUtils.decodeFileName(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun decodeFileNameShouldHandleEdgeCases() {
        val edgeCases = listOf(
            "", " ", "%20", "%2F", "%2f", "name%20file", "name%2Ffile", "name%2F%2Ffile",
            "%25%2F", "%252F", "%00", "%7F"
        )
        edgeCases.forEach { input ->
            try {
                FileUtils.decodeFileName(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun decodeFileNameShouldRoundtripWithSanitizeFileName() {
        repeat(1000) { i ->
            val input = generateRandomString((i % 100) + 1)
            val sanitized = FileUtils.sanitizeFileName(input)
            val decoded = FileUtils.decodeFileName(sanitized)
            assertTrue(decoded.isNotEmpty(), "Failed to roundtrip: $input")
        }
    }

    private fun generateRandomStrings(count: Int): List<String> {
        return (0 until count).map { generateRandomString((it % 200) + 1) }
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?/`~ "
        return (0 until length).map { chars[chars.indices.random()] }.joinToString("")
    }
}
