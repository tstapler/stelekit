package dev.stapler.stelekit.util

import kotlin.test.Test
import kotlin.test.assertEquals

class StringUtilsTest {

    // -------------------------------------------------------------------------
    // toTitleCase — basic capitalisation
    // -------------------------------------------------------------------------

    @Test
    fun allLowercase_capitaliseEachWord() {
        assertEquals("The Quick Brown Fox", "the quick brown fox".toTitleCase())
    }

    @Test
    fun minorWords_lowercasedMidSentence() {
        assertEquals("Lord of the Rings", "lord of the rings".toTitleCase())
    }

    @Test
    fun firstWord_alwaysCapitalisedEvenIfMinorWord() {
        assertEquals("The End", "the end".toTitleCase())
        assertEquals("A Brief History", "a brief history".toTitleCase())
    }

    @Test
    fun allUppercase_firstLetterPreserved() {
        // toTitleCase capitalises [0] and preserves the rest — does not downcase remaining
        val result = "KOTLIN MULTIPLATFORM".toTitleCase()
        assertEquals("KOTLIN MULTIPLATFORM", result)
    }

    @Test
    fun mixedCase_firstLetterCapitalised() {
        assertEquals("Hello World", "hello world".toTitleCase())
    }

    @Test
    fun emptyString_returnsEmpty() {
        assertEquals("", "".toTitleCase())
    }

    @Test
    fun singleWord_capitalised() {
        assertEquals("Kotlin", "kotlin".toTitleCase())
    }

    @Test
    fun singleMinorWord_capitalised() {
        assertEquals("The", "the".toTitleCase())
    }

    @Test
    fun allMinorWords_onlyFirstCapitalised() {
        // "or" and "but" are minor words — they stay lowercase mid-sentence
        assertEquals("And or but", "and or but".toTitleCase())
    }

    @Test
    fun conjunctions_lowercasedMidSentence() {
        assertEquals("Romeo and Juliet", "romeo and juliet".toTitleCase())
    }

    @Test
    fun prepositions_lowercasedMidSentence() {
        assertEquals("War of the Worlds", "war of the worlds".toTitleCase())
    }

    @Test
    fun doubleSpace_emptyTokenPreserved() {
        // split(" ") on "a  b" → ["a", "", "b"]; empty word passes through unchanged
        val result = "a  b".toTitleCase()
        assertEquals("A  B", result)
    }

    @Test
    fun pageNameWithNumber_capitalised() {
        assertEquals("Chapter 1 the Beginning", "chapter 1 the beginning".toTitleCase())
    }
}
