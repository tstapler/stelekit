// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import kotlin.test.Test
import kotlin.test.assertTrue

class DiskConflictDiffTest {

    @Test
    fun computeDiskDiffState_should_classifyInputs() {
        assertTrue(computeDiskDiffState("", "x") is DiskDiffState.NoLocalEdit)
        assertTrue(computeDiskDiffState("x", "x") is DiskDiffState.Identical)
        assertTrue(computeDiskDiffState("x", "y") is DiskDiffState.Different)
    }

    @Test
    fun buildDiffLines_should_handleInsertDeleteChange() {
        val local = "a\nb\nc\nd"
        val disk = "a\nB\nc\nd\ne"
        val state = computeDiskDiffState(local, disk) as DiskDiffState.Different
        val lines = buildDiffLines(state.patch, local.lines())
        assertTrue(lines.contains(DiffLineItem("b", DiffLineKind.REMOVED)))
        assertTrue(lines.contains(DiffLineItem("B", DiffLineKind.ADDED)))
        assertTrue(lines.contains(DiffLineItem("e", DiffLineKind.ADDED)))
        assertTrue(lines.contains(DiffLineItem("a", DiffLineKind.UNCHANGED)))

        val delOnly = computeDiskDiffState("x\ny", "x") as DiskDiffState.Different
        assertTrue(buildDiffLines(delOnly.patch, "x\ny".lines()).contains(DiffLineItem("y", DiffLineKind.REMOVED)))
    }
}
