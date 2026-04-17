package dev.stapler.stelekit.parsing

import dev.stapler.stelekit.parsing.lexer.Lexer
import dev.stapler.stelekit.parsing.lexer.TokenType
import kotlin.test.Test
import kotlin.test.fail

class ParsingPropertyTest {

    @Test
    fun inlineParserShouldNotCrashOnAnyString() {
        repeat(1000) { i ->
            val input = generateRandomString((i % 500))
            try {
                InlineParser(input).parse()
            } catch (e: Exception) {
                fail("Failed for input length ${input.length}: ${e.message}")
            }
        }
    }

    @Test
    fun inlineParserShouldHandleVariousDelimiters() {
        val inputs = listOf(
            "*text*", "**text**", "***text***", "_text_", "__text__", "~~text~~",
            "`code`", "#tag", "*text", "text*", "**text", "text**", "*text**",
            "****text", "*_text_*",
        )
        inputs.forEach { input ->
            try {
                InlineParser(input).parse()
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun inlineParserShouldHandleDeeplyNestedEmphasis() {
        repeat(10) { depth ->
            val input = "*".repeat(depth) + "text" + "*".repeat(depth)
            try {
                InlineParser(input).parse()
            } catch (e: Exception) {
                fail("Failed for depth: $depth")
            }
        }
    }

    @Test
    fun inlineParserShouldHandleUnclosedBrackets() {
        val inputs = listOf(
            "[", "[[", "[[]", "[[text", "[[text]", "[text", "[text]",
            "[[text]]extra",
        )
        inputs.forEach { input ->
            try {
                InlineParser(input).parse()
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun inlineParserShouldHandleBlockRefEdgeCases() {
        val inputs = listOf(
            "(", "((", "((ref", "((ref)", "((ref))", "((ref))extra",
        )
        inputs.forEach { input ->
            try {
                InlineParser(input).parse()
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun lexerShouldNotCrashOnAnyString() {
        repeat(1000) { i ->
            val input = generateRandomString((i % 500))
            try {
                val lexer = Lexer(input)
                var token = lexer.nextToken()
                while (token.type != TokenType.EOF) {
                    token = lexer.nextToken()
                }
            } catch (e: Exception) {
                fail("Failed for input length ${input.length}")
            }
        }
    }

    @Test
    fun lexerShouldHandleSpecialCharacters() {
        val specialChars = listOf(
            "\u0000", "\u0001", "\u001F", "\u007F", "\u0080", "\u009F",
            "\u2000", "\u2028", "\u2029", "\uFEFF"
        )
        specialChars.forEach { char ->
            try {
                Lexer(char).nextToken()
            } catch (e: Exception) {
                fail("Failed for: $char")
            }
        }
    }

    @Test
    fun blockParserShouldHandleVariousInputs() {
        val inputs = listOf(
            "", " ", "\t", "  ", "\t\t", " \t", "\t ", "- item", "* bullet", "+ plus",
            "key:: value", "key::", "key::   ", "multiple::values::here",
            "- key:: value", "  - nested:: prop", "property:: [[link]]", "property:: ```code```",
        )
        inputs.forEach { input ->
            try {
                BlockParser(input).parse()
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    @Test
    fun outlinerParserShouldNotCrashOnArbitraryMarkdown() {
        repeat(1000) { i ->
            val input = generateMarkdown((i % 500))
            try {
                OutlinerParser().parse(input)
            } catch (e: Exception) {
                fail("Failed for input length ${input.length}")
            }
        }
    }

    @Test
    fun outlinerParserShouldHandleEdgeCaseMarkdown() {
        val edgeCases = listOf(
            "", "#", "##", "###", "****", "____", "~~~~", "[[]]", "[[[]]]", "((()))",
            "```", "````", "```\n", "\n\n\n", "   \n   \n   ",
            "text```code```text", "*italic**bold*", "**bold*italic**",
        )
        edgeCases.forEach { input ->
            try {
                OutlinerParser().parse(input)
            } catch (e: Exception) {
                fail("Failed for: $input")
            }
        }
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?/`~\n\t "
        return (0 until length).map { chars[chars.indices.random()] }.joinToString("")
    }

    private fun generateMarkdown(length: Int): String {
        val elements = listOf(
            "# Heading", "## Subheading", "**bold**", "*italic*", "~~strike~~",
            "`code`", "```block```", "[[wiki link]]", "((block ref))", "- list item",
            "1. ordered", "| table | col |", "---"
        )
        return (0 until (length / 20)).map { elements[elements.indices.random()] }.joinToString("\n")
    }
}
