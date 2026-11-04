// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitAttributesParserTest {
    @Test
    fun parsesAttributeStatesInWrittenOrder() {
        val file = GitAttributesParser.parse("*.bin filter=lfs -text !diff merge binary=yes")

        val rule = file.rules.single()
        assertEquals("*.bin", rule.pattern)
        assertEquals(
            listOf(
                "filter" to AttrState.Value("lfs"),
                "text" to AttrState.Unset,
                "diff" to AttrState.Unspecified,
                "merge" to AttrState.Set,
                "binary" to AttrState.Value("yes"),
            ),
            rule.attributes,
        )
    }

    @Test
    fun skipsBlankCommentAndNegativePatternLines() {
        val file = GitAttributesParser.parse("\n   \n# comment\n!*.tmp -text\n*.md text\n")

        assertEquals(listOf("*.md"), file.rules.map { it.pattern })
    }

    @Test
    fun recordsOneBasedLineNumbersIncludingSkippedLines() {
        val file = GitAttributesParser.parse("# header\n\n*.a text\r\n  *.b -text")

        assertEquals(listOf("*.a" to 3, "*.b" to 4), file.rules.map { it.pattern to it.line })
    }

    @Test
    fun stripsByteOrderMarkBeforeFirstLine() {
        val file = GitAttributesParser.parse("﻿*.png filter=lfs")

        assertEquals("*.png", file.rules.single().pattern)
    }

    @Test
    fun quotedPatternsUnescapeAndKeepSpaces() {
        val file = GitAttributesParser.parse("\"my file.txt\" text\n\"tab\\there\" -text\n\"q\\\"uote\" text")

        assertEquals(listOf("my file.txt", "tab\there", "q\"uote"), file.rules.map { it.pattern })
    }

    @Test
    fun unterminatedQuoteIsIgnored() {
        val file = GitAttributesParser.parse("\"broken pattern text\n*.ok text")

        assertEquals(listOf("*.ok"), file.rules.map { it.pattern })
    }

    @Test
    fun patternWithoutAttributesHasEmptyList() {
        val rule = GitAttributesParser.parse("lonely").rules.single()

        assertTrue(rule.attributes.isEmpty())
    }

    @Test
    fun macrosAreHonouredInRootFileOnly() {
        val text = "[attr]lfs filter=lfs diff=lfs -text\n*.bin lfs"

        val root = GitAttributesParser.parse(text)
        val nested = GitAttributesParser.parse(text, dirPrefix = "sub")

        assertEquals(
            listOf("filter" to AttrState.Value("lfs"), "diff" to AttrState.Value("lfs"), "text" to AttrState.Unset),
            root.macros["lfs"],
        )
        assertTrue(nested.macros.isEmpty())
        assertEquals("*.bin", nested.rules.single().pattern)
    }

    @Test
    fun macroWithoutNameIsDropped() {
        assertTrue(GitAttributesParser.parse("[attr]   ").macros.isEmpty())
    }

    @Test
    fun explicitAllowMacrosOverridesDirectoryDefault() {
        val file = GitAttributesParser.parse("[attr]m text", dirPrefix = "sub", allowMacros = true)

        assertEquals(listOf("text" to AttrState.Set), file.macros["m"])
    }

    @Test
    fun sourceNameDefaultsFromDirectoryPrefix() {
        assertEquals(".gitattributes", GitAttributesParser.parse("").sourceName)
        assertEquals("sub/.gitattributes", GitAttributesParser.parse("", dirPrefix = "sub").sourceName)
        assertEquals("info", GitAttributesParser.parse("", sourceName = "info").sourceName)
    }

    @Test
    fun hasSlashIgnoresTrailingSlash() {
        val rules = GitAttributesParser.parse("dir/ text\na/b text\n*.c text").rules

        assertFalse(rules[0].hasSlash)
        assertTrue(rules[1].hasSlash)
        assertFalse(rules[2].hasSlash)
    }
}
