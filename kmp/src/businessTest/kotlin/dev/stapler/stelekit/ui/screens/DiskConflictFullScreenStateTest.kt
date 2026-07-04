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

    @Test
    fun `buildDiffLines reconstructs a pure insert with unchanged lines surrounding the addition`() {
        // Disk adds two lines ("x", "y") between the two local lines — a pure INSERT delta.
        val localContent = "a\nb"
        val diskContent = "a\nx\ny\nb"

        val different = assertIs<DiskDiffState.Different>(computeDiskDiffState(localContent, diskContent))
        val lines = buildDiffLines(different.patch, localContent.lines())

        assertEquals(
            listOf(
                DiffLineItem("a", DiffLineKind.UNCHANGED),
                DiffLineItem("x", DiffLineKind.ADDED),
                DiffLineItem("y", DiffLineKind.ADDED),
                DiffLineItem("b", DiffLineKind.UNCHANGED),
            ),
            lines,
        )
    }

    @Test
    fun `buildDiffLines reconstructs a pure delete with unchanged lines intact`() {
        // Disk drops the middle line ("b") — a pure DELETE delta.
        val localContent = "a\nb\nc"
        val diskContent = "a\nc"

        val different = assertIs<DiskDiffState.Different>(computeDiskDiffState(localContent, diskContent))
        val lines = buildDiffLines(different.patch, localContent.lines())

        assertEquals(
            listOf(
                DiffLineItem("a", DiffLineKind.UNCHANGED),
                DiffLineItem("b", DiffLineKind.REMOVED),
                DiffLineItem("c", DiffLineKind.UNCHANGED),
            ),
            lines,
        )
    }

    @Test
    fun `buildDiffLines reconstructs a middle change with unchanged prefix and suffix`() {
        // The middle line changes ("b" -> "X"); "a" and "c" are unchanged on either side.
        val localContent = "a\nb\nc"
        val diskContent = "a\nX\nc"

        val different = assertIs<DiskDiffState.Different>(computeDiskDiffState(localContent, diskContent))
        val lines = buildDiffLines(different.patch, localContent.lines())

        assertEquals(
            listOf(
                DiffLineItem("a", DiffLineKind.UNCHANGED),
                DiffLineItem("b", DiffLineKind.REMOVED),
                DiffLineItem("X", DiffLineKind.ADDED),
                DiffLineItem("c", DiffLineKind.UNCHANGED),
            ),
            lines,
        )
    }

    @Test
    fun `buildDiffLines reconstructs multiple non-adjacent deltas without dropping or duplicating lines`() {
        // Two separate single-line changes ("b" -> "X" and "d" -> "Y") with unchanged lines
        // around and between them — exercises the cursor gap-fill loop running more than once.
        val localContent = "a\nb\nc\nd\ne"
        val diskContent = "a\nX\nc\nY\ne"

        val different = assertIs<DiskDiffState.Different>(computeDiskDiffState(localContent, diskContent))
        val lines = buildDiffLines(different.patch, localContent.lines())

        assertEquals(
            listOf(
                DiffLineItem("a", DiffLineKind.UNCHANGED),
                DiffLineItem("b", DiffLineKind.REMOVED),
                DiffLineItem("X", DiffLineKind.ADDED),
                DiffLineItem("c", DiffLineKind.UNCHANGED),
                DiffLineItem("d", DiffLineKind.REMOVED),
                DiffLineItem("Y", DiffLineKind.ADDED),
                DiffLineItem("e", DiffLineKind.UNCHANGED),
            ),
            lines,
        )
    }
}
