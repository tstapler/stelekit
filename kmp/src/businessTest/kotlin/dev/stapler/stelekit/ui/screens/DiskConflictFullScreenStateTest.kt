package dev.stapler.stelekit.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [computeDiskDiffState], the pure function backing [DiskConflictFullScreen]'s
 * three-way disk-vs-local comparison. No Compose dependency — safe for businessTest.
 */
class DiskConflictFullScreenStateTest {

    @Test
    fun `identical local and disk content returns Identical`() {
        val result = computeDiskDiffState("- same", "- same")

        assertEquals(DiskDiffState.Identical, result)
    }

    @Test
    fun `blank local content returns NoLocalEdit`() {
        val result = computeDiskDiffState("", "- disk content")

        assertEquals(DiskDiffState.NoLocalEdit, result)
    }

    @Test
    fun `differing local and disk content returns Different with a non-empty patch`() {
        val result = computeDiskDiffState("- local", "- disk")

        val different = assertIs<DiskDiffState.Different>(result)
        assertTrue(different.patch.deltas.isNotEmpty())
    }
}
