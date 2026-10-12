// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differential test: every case's `expected` is the byte-exact output of the real python
 * `merge=logseq-union` driver (see scripts/gen-logseq-union-golden.py). No intentional deviations
 * in merged text; the only behavioral deviation (surfacing embedded conflict markers instead of
 * committing them) is above this layer and covered in [MarkdownMergePolicyTest].
 */
class LogseqUnionMergeDriverTest {
    @Test
    fun `port matches the python driver byte for byte over the whole corpus`() {
        val mismatches = logseqUnionGoldenCases.filter {
            LogseqUnionMergeDriver.merge(it.base, it.local, it.remote).text != it.expected
        }
        assertTrue(mismatches.isEmpty(), "mismatching cases: ${mismatches.map { it.name }}")
    }

    @Test
    fun `each case reports markers exactly when the python output embeds them`() {
        for (case in logseqUnionGoldenCases) {
            val out = LogseqUnionMergeDriver.merge(case.base, case.local, case.remote)
            assertEquals(case.expected.lineSequence().any { it.startsWith("<<<<<<<") }, out.hasConflictMarkers, case.name)
        }
    }

    @Test
    fun `corpus covers every strategy outcome`() {
        val names = logseqUnionGoldenCases.map { it.name }
        listOf("simple-add", "same-count", "android-loss", "local-loss", "ref-", "fallback-", "long-autojunk")
            .forEach { prefix -> assertTrue(names.any { it.startsWith(prefix) }, prefix) }
        assertTrue(logseqUnionGoldenCases.any { LogseqUnionMergeDriver.merge(it.base, it.local, it.remote).hasConflictMarkers })
    }

    @Test
    fun `splitLines follows python str splitlines`() {
        assertEquals(emptyList(), PyStr.splitLines(""))
        assertEquals(listOf(""), PyStr.splitLines("\n"))
        assertEquals(listOf("a", ""), PyStr.splitLines("a\n\n"))
        assertEquals(listOf("a", "b", "c", "d"), PyStr.splitLines("a\r\nb\rc\nd"))
        assertEquals(listOf("a", "b", "c"), PyStr.splitLines("a b\u0085c\n"))
    }

    @Test
    fun `strip removes python whitespace including nbsp and unit separator`() {
        assertEquals("x", PyStr.strip("  x\u001f\t"))
        assertEquals("", PyStr.strip(" 　 "))
    }

    @Test
    fun `identical inputs round-trip without trailing newline`() {
        val out = LogseqUnionMergeDriver.merge("- a\n", "- a\n", "- a\n")
        assertEquals("- a", out.text)
        assertFalse(out.hasConflictMarkers)
    }
}
