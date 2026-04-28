package dev.stapler.stelekit.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnglishInflectorTest {

    private fun forms(base: String) = EnglishInflector.inflect(base)
    private fun texts(base: String) = forms(base).map { it.text }

    // CVC doubling: run → running, runned, runner (final consonant doubled before vowel suffixes)

    @Test
    fun cvcDoubling_running() {
        val t = texts("run")
        assertTrue("running" in t, "run → running via CVC doubling")
        assertFalse("runing" in t, "runing must NOT appear")
    }

    @Test
    fun cvcDoubling_baseLengthIsBaseLen() {
        val f = forms("run").first { it.text == "running" }
        assertEquals(3, f.baseLength, "baseLength for 'running' from 'run' should be 3")
    }

    @Test
    fun cvcDoubling_runner() {
        assertTrue("runner" in texts("run"), "run → runner via CVC doubling")
    }

    @Test
    fun cvcDoubling_plan() {
        assertTrue("planning" in texts("plan"), "plan → planning via CVC doubling")
        assertTrue("planned" in texts("plan"), "plan → planned via CVC doubling")
    }

    // E-dropping: make → making, maker; hope → hoped

    @Test
    fun eDrop_making() {
        val t = texts("make")
        assertTrue("making" in t, "make → making via e-drop")
        assertFalse("makeing" in t, "makeing must NOT appear")
    }

    @Test
    fun eDrop_making_baseLength() {
        val f = forms("make").first { it.text == "making" }
        assertEquals(3, f.baseLength, "baseLength for 'making' from 'make' should be 3 (drop the e)")
    }

    @Test
    fun eDrop_maker_baseLength() {
        val f = forms("make").first { it.text == "maker" }
        assertEquals(4, f.baseLength, "baseLength for 'maker' from 'make' should be 4 (keep the e, add r)")
    }

    @Test
    fun eDrop_hoped() {
        assertTrue("hoped" in texts("hope"), "hope → hoped via e-drop (just add d)")
    }

    // Y → I alternation: cry → cries, cried; happy → happily, happier

    @Test
    fun yToI_cries() {
        assertTrue("cries" in texts("cry"), "cry → cries via y→ies")
    }

    @Test
    fun yToI_cried() {
        assertTrue("cried" in texts("cry"), "cry → cried via y→ied")
    }

    @Test
    fun yToI_crying() {
        // -ing: no y→i for crying (plain append or CVC check)
        assertTrue("crying" in texts("cry"), "cry → crying")
    }

    @Test
    fun yToI_happily() {
        assertTrue("happily" in texts("happy"), "happy → happily via y→ily")
    }

    @Test
    fun yToI_happier() {
        assertTrue("happier" in texts("happy"), "happy → happier via y→ier")
    }

    // LE → LY: simple → simply

    @Test
    fun leToLy_simply() {
        assertTrue("simply" in texts("simple"), "simple → simply via le→ly")
        assertFalse("simply" !in texts("simple"), "simply must appear")
    }

    // IE → YING: die → dying

    @Test
    fun ieToYing_dying() {
        assertTrue("dying" in texts("die"), "die → dying via ie→ying")
        assertFalse("dieing" in texts("die"), "dieing must NOT appear")
    }

    // Sibilant +ES: watch → watches

    @Test
    fun sibilant_watches() {
        assertTrue("watches" in texts("watch"), "watch → watches via sibilant +es")
        assertFalse("watchs" in texts("watch"), "watchs must NOT appear")
    }

    // No variant equals the base

    @Test
    fun noSelfVariant() {
        listOf("run", "make", "cry", "plan", "test").forEach { base ->
            assertFalse(texts(base).contains(base), "Base '$base' must not appear as its own variant")
        }
    }

    // Vowel-ending w/x/y not doubled

    @Test
    fun noCvcDoubling_fix() {
        val t = texts("fix")
        assertTrue("fixing" in t, "fix → fixing (x in wxy, no doubling)")
        assertFalse("fixxing" in t, "fixxing must NOT appear")
    }
}
