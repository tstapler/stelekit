// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Vectors follow git's `wildmatch.c` semantics (the cases in git's t3070-wildmatch). */
class GitWildmatchTest {
    private fun check(expected: Boolean, pattern: String, text: String, pathname: Boolean = true) =
        assertEquals(expected, GitWildmatch.matches(pattern, text, pathname), "pattern=[$pattern] text=[$text] pathname=$pathname")

    @Test
    fun literalsAndQuestionMark() {
        check(true, "foo", "foo")
        check(false, "foo", "bar")
        check(true, "", "")
        check(true, "???", "foo")
        check(false, "??", "foo")
        check(false, "????", "foo")
    }

    @Test
    fun starWithoutSlashes() {
        check(true, "*", "foo")
        check(true, "f*", "foo")
        check(false, "*f", "foo")
        check(true, "*foo*", "foo")
        check(true, "*ob*a*r*", "foobar")
        check(true, "*ab", "aaaaaaabababab")
        check(false, "*ab", "aaaaaaabababac")
    }

    @Test
    fun backslashEscapesTheNextCharacter() {
        check(true, "foo\\*", "foo*")
        check(false, "foo\\*bar", "foobar")
        check(true, "\\?", "?")
        check(false, "\\?", "x")
    }

    @Test
    fun bracketSetsRangesAndNegation() {
        check(true, "[ab]", "a")
        check(false, "[ab]", "c")
        check(true, "[!ab]", "c")
        check(false, "[!ab]", "a")
        check(false, "[^ab]", "b")
        check(true, "[a-c]", "b")
        check(false, "[a-c]", "d")
        check(true, "[]a]", "]")
        check(true, "[\\]]", "]")
        check(true, "[a-\\c]", "b")
        check(true, "[a-]", "-")
        check(false, "[a-]", "b")
    }

    @Test
    fun unterminatedBracketNeverMatches() {
        check(false, "[ab", "a")
        check(false, "[", "a")
        check(false, "[\\", "a")
    }

    @Test
    fun posixCharacterClasses() {
        val members = mapOf(
            "alpha" to ("a" to "1"),
            "digit" to ("7" to "a"),
            "alnum" to ("Z" to "-"),
            "upper" to ("Q" to "q"),
            "lower" to ("q" to "Q"),
            "blank" to ("\t" to "x"),
            "space" to ("\n" to "x"),
            "cntrl" to ("\u0001" to "a"),
            "graph" to ("!" to " "),
            "print" to (" " to "\u0001"),
            "punct" to ("." to "a"),
            "xdigit" to ("f" to "g"),
        )
        for ((name, samples) in members) {
            check(true, "[[:$name:]]", samples.first)
            check(false, "[[:$name:]]", samples.second)
            check(true, "[![:$name:]]", samples.second)
        }
    }

    @Test
    fun classesCombineWithOtherMembers() {
        check(true, "[[:digit:][:upper:][:space:]]", "A")
        check(true, "[[:digit:][:upper:][:space:]]", "1")
        check(true, "[[:digit:][:upper:][:space:]]", " ")
        check(false, "[[:digit:][:upper:][:space:]]", "a")
        check(true, "[a[:digit:]]", "a")
    }

    @Test
    fun unknownOrUnterminatedClassNeverMatches() {
        check(false, "[[:foo:]]", "a")
        check(false, "[[:alpha:]", "a")
    }

    @Test
    fun classWithoutClosingColonIsLiteralMembers() {
        check(true, "[[:alpha]", "[")
        check(true, "[[:alpha]", ":")
        check(true, "[[:alpha]", "p")
        check(false, "[[:alpha]", "x")
    }

    @Test
    fun pathnameModeKeepsWildcardsInsideOneSegment() {
        check(false, "*", "foo/bar")
        check(true, "*", "foo/bar", pathname = false)
        check(true, "foo/*", "foo/bar")
        check(false, "foo/*", "foo/bar/baz")
        check(true, "foo/*", "foo/bar/baz", pathname = false)
        check(true, "*/bar", "foo/bar")
        check(false, "?", "/")
        check(true, "?", "/", pathname = false)
        check(false, "[a-z]", "/")
        check(false, "a*b", "a/b")
        check(true, "a*b", "a/b", pathname = false)
    }

    @Test
    fun doubleStarSpansDirectories() {
        check(true, "**/foo", "foo")
        check(true, "**/foo", "a/b/foo")
        check(false, "**/foo", "afoo")
        check(true, "a/**/b", "a/b")
        check(true, "a/**/b", "a/x/y/b")
        check(false, "a/**/b", "a/xb")
        check(true, "a/**", "a/b/c")
        check(false, "a/**", "a")
    }

    @Test
    fun doubleStarGluedToTextActsLikeSingleStar() {
        check(false, "a**b", "a/b")
        check(true, "a**b", "axxb")
        check(true, "a**b", "a/b", pathname = false)
    }

    @Test
    fun literalPatternMatchesOnlyItself() = runTest {
        checkAll(arbWord(), arbWord()) { pattern, text ->
            assertEquals(pattern == text, GitWildmatch.matches(pattern, text, pathname = false), "[$pattern] vs [$text]")
        }
    }

    @Test
    fun leadingStarMatchesExactlyTheSuffix() = runTest {
        checkAll(arbWord(), arbWord()) { literal, text ->
            assertEquals(text.endsWith(literal), GitWildmatch.matches("*$literal", text, pathname = false), "*$literal vs [$text]")
        }
    }

    @Test
    fun surroundingStarsMatchExactlyTheSubstring() = runTest {
        checkAll(arbWord(), arbWord()) { literal, text ->
            assertEquals(text.contains(literal), GitWildmatch.matches("*$literal*", text, pathname = false), "*$literal* vs [$text]")
        }
    }

    // Alphabet of three characters so generated words overlap often enough to stress backtracking.
    private fun arbWord(): Arb<String> = Arb.list(Arb.element('a', 'b', 'c'), 0..8).map { it.joinToString("") }
}
