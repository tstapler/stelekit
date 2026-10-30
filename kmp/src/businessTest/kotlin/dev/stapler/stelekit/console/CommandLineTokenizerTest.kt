// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandLineTokenizerTest {
    private fun tokens(line: String) = CommandLineTokenizer.tokenize(line).getOrNull()

    @Test
    fun tokenize_should_SplitOnWhitespace_And_IgnoreRepeatedSpaces() {
        assertEquals(listOf("logs", "20", "warn"), tokens("  logs   20\twarn "))
        assertEquals(emptyList(), tokens("   "))
    }

    @Test
    fun tokenize_should_KeepQuotedTextTogether_And_AllowEmptyTokens() {
        assertEquals(listOf("sql", "select 1", ""), tokens("sql \"select 1\" \"\""))
        assertEquals(listOf("a b", "c"), tokens("'a b' c"))
        assertEquals(listOf("say \"hi\"", "back\\slash"), tokens("""  "say \"hi\"" "back\\slash" """))
    }

    @Test
    fun tokenize_should_JoinAdjacentQuotedAndBareParts() {
        assertEquals(listOf("ab cd"), tokens("""a"b c"d"""))
        assertEquals(listOf("a b"), tokens("""a\ b"""))
    }

    @Test
    fun tokenize_should_ReportUsageError_When_QuoteOrBackslashIsUnterminated() {
        assertIs<ConsoleError.Usage>(CommandLineTokenizer.tokenize("echo \"oops").leftOrNull())
        assertIs<ConsoleError.Usage>(CommandLineTokenizer.tokenize("echo 'oops").leftOrNull())
        assertIs<ConsoleError.Usage>(CommandLineTokenizer.tokenize("echo oops\\").leftOrNull())
    }

    @Test
    fun tokenize_should_InvertQuote_ForAnyTokenList() = runTest {
        checkAll(Arb.list(Arb.string(0..12), 0..8)) { original ->
            val line = original.joinToString(" ") { CommandLineTokenizer.quote(it) }
            assertEquals(original, tokens(line), "line: $line")
        }
    }
}
