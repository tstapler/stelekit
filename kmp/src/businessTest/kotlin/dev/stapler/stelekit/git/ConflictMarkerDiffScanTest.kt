// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConflictMarkerDiffScanTest {

    private fun added(vararg text: String) = text.map { DiffLine(true, it) }

    @Test
    fun `an added open and close marker pair is found`() {
        assertTrue(ConflictMarkerDiffScan.hasAddedMarkerPair(added("- a", "<<<<<<< HEAD", "- b", "=======", "- c", ">>>>>>> branch")))
    }

    @Test
    fun `an open marker alone or a close marker alone is not a pair`() {
        assertFalse(ConflictMarkerDiffScan.hasAddedMarkerPair(added("<<<<<<< HEAD", "- b")))
        assertFalse(ConflictMarkerDiffScan.hasAddedMarkerPair(added(">>>>>>> branch", "- b")))
    }

    @Test
    fun `a close marker before the open marker is not a pair`() {
        assertFalse(ConflictMarkerDiffScan.hasAddedMarkerPair(added(">>>>>>> branch", "<<<<<<< HEAD")))
    }

    @Test
    fun `markers inside a backtick or tilde fence are ignored`() {
        assertFalse(ConflictMarkerDiffScan.hasAddedMarkerPair(added("```", "<<<<<<< HEAD", ">>>>>>> x", "```")))
        assertFalse(ConflictMarkerDiffScan.hasAddedMarkerPair(added("~~~", "<<<<<<< HEAD", ">>>>>>> x", "~~~")))
    }

    @Test
    fun `a marker after a closed fence is found again`() {
        assertTrue(ConflictMarkerDiffScan.hasAddedMarkerPair(added("```", "x", "```", "<<<<<<< HEAD", ">>>>>>> x")))
    }

    @Test
    fun `context lines that carry markers are not added markers`() {
        val lines = listOf(DiffLine(false, "<<<<<<< HEAD"), DiffLine(false, ">>>>>>> x"), DiffLine(true, "- new"))
        assertFalse(ConflictMarkerDiffScan.hasAddedMarkerPair(lines))
    }

    @Test
    fun `an indented marker is not at line start`() {
        assertFalse(ConflictMarkerDiffScan.hasAddedMarkerPair(added("  <<<<<<< HEAD", "  >>>>>>> x")))
    }

    @Test
    fun `an open marker with no trailing space is not a marker`() {
        assertFalse(ConflictMarkerDiffScan.hasAddedMarkerPair(added("<<<<<<<HEAD", ">>>>>>>x")))
    }
}
