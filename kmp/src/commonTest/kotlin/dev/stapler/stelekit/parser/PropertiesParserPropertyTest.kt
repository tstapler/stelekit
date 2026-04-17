package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.fail

class PropertiesParserPropertyTest {

    @Test
    fun parseShouldNotCrashOnArbitraryStrings() {
        repeat(1000) { i ->
            val input = generateRandomString((i % 500))
            try {
                PropertiesParser.parse(input)
            } catch (e: Exception) {
                fail("Failed for input length ${input.length}")
            }
        }
    }

    @Test
    fun parseShouldHandleVariousPropertyFormats() {
        val propertyFormats = listOf(
            "key::value", "key:: value", "key::  value with spaces",
            "key::value with :: inside", "key:: [[link]]", "key:: ```code```",
            "key:: **bold**", "key::", "key:: ", "multiple::properties::here",
            "k-e-y::value", "k_e_y::value", "K_E_Y::value", "KEY::value",
        )
        propertyFormats.forEach { format ->
            try {
                PropertiesParser.parse(format)
            } catch (e: Exception) {
                fail("Failed for: $format")
            }
        }
    }

    @Test
    fun parseShouldHandleDrawerEdgeCases() {
        val drawerInputs = listOf(
            ":PROPERTIES:", ":PROPERTIES:\n:END:", ":PROPERTIES:\n:PROPERTIES:\n:END:",
            ":PROPERTIES:\nkey::value\n:END:", ":PROPERTIES:\nkey1::val1\nkey2::val2\n:END:",
            ":PROPERTIES:\n:END:\n:PROPERTIES:\n:END:", ":properties:", ":END:",
            ":END:\n:PROPERTIES:", ":PROPERTIES:\ninvalid",
            "outside\n:PROPERTIES:\n:END:\ninside",
        )
        drawerInputs.forEach { input ->
            try {
                PropertiesParser.parse(input)
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun parseShouldHandleMultiLineContent() {
        val multiLineInputs = listOf(
            "key::value\ncontent", "key::value\n\ncontent", "key::value\n\nkey2::value2\ncontent",
            "content\nkey::value", "key:: [[page name]]\ncontent", "key::\nmultiline\nvalue",
            "---\nkey::value\n---\n",
        )
        multiLineInputs.forEach { input ->
            try {
                PropertiesParser.parse(input)
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun parseShouldHandleUnicodeInValues() {
        val unicodeInputs = listOf(
            "key:: 日本語", "key:: 中文", "key:: 한국어", "key:: 🚀emoji", "key:: àéïõù", "key:: Ωμέγα",
        )
        unicodeInputs.forEach { input ->
            try {
                PropertiesParser.parse(input)
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun parseShouldHandleSpecialCharactersInValues() {
        val specialInputs = listOf(
            "key:: <tag>", "key:: [link](url)", "key:: " + '$' + "variable", "key:: 100%",
            "key:: 50/50", "key:: {{template}}", "key:: (expr)", "key:: [array[0]]",
        )
        specialInputs.forEach { input ->
            try {
                PropertiesParser.parse(input)
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun isPropertyLineEdgeCases() {
        val edgeCases = listOf(
            "", " ", "::", ":::", "key", "key:", "key :", "key : value",
            "key::", "key::::value", "123::value", "key123::value", "key~::value",
            "key@::value", "key.::value",
        )
        edgeCases.forEach { input ->
            try {
                PropertiesParser.parse(input)
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun timestampParserShouldNotCrashOnArbitraryInput() {
        repeat(500) { i ->
            val input = generateRandomString((i % 200))
            try {
                TimestampParser.parse(input)
            } catch (e: Exception) {
                fail("Failed for input length ${input.length}")
            }
        }
    }

    @Test
    fun timestampParserShouldHandleMalformedTimestamps() {
        val malformedTimestamps = listOf(
            "SCHEDULED:", "SCHEDULED: ", "SCHEDULED: <", "SCHEDULED: >", "SCHEDULED: <>",
            "SCHEDULED: < >", "SCHEDULED: <2021>", "SCHEDULED: <2021-13-45>", "DEADLINE:",
            "DEADLINE: <2024-02-29>", "SCHEDULED: <2024-02-29>", "SCHEDULED: DEADLINE: <2024-01-01>",
            "SCHEDULED:\nDEADLINE: <2024-01-01>", "SCHEDULED:\n\nDEADLINE: <2024-01-01>",
            "<2024-01-01>", "SCHEDULED", "DEADLINE",
        )
        malformedTimestamps.forEach { input ->
            try {
                TimestampParser.parse(input)
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun markdownPreprocessorShouldNotCrashOnArbitraryStrings() {
        repeat(1000) { i ->
            val input = generateRandomString((i % 500))
            try {
                MarkdownPreprocessor.normalize(input)
            } catch (e: Exception) {
                fail("Failed for input length ${input.length}")
            }
        }
    }

    @Test
    fun markdownPreprocessorShouldHandleIndentationEdgeCases() {
        val indentEdgeCases = listOf(
            "", "\t", " ", "  ", "\t\t", "\t ", " \t", "\t\n\t", " \n ", "\n ",
            "\t\n\t", "  \n  ", "\u0009\u0020",
        )
        indentEdgeCases.forEach { input ->
            try {
                MarkdownPreprocessor.normalize(input)
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?/`~ \n\t"
        return (0 until length).map { chars[chars.indices.random()] }.joinToString("")
    }
}
