// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StrayPageMergeTest {
    private fun merged(notes: String, stray: String) = assertIs<StrayPageMergeResult.Merged>(StrayPageMerge.merge(notes, stray))

    @Test
    fun merge_should_KeepEveryBlockOfBothCopies_When_TheyAreDisjoint() {
        val result = merged(notes = "- from the desktop\n- second desktop note\n", stray = "- garage door spring 28.5 in\n- called the supplier\n")

        val lines = result.text.lines().filter { it.isNotBlank() }
        assertEquals(
            listOf("- from the desktop", "- second desktop note", "- garage door spring 28.5 in", "- called the supplier"),
            lines,
            "the notes copy keeps its order and the stray blocks follow",
        )
        assertEquals(2, result.addedFromStray)
        assertTrue(result.text.endsWith("\n"))
    }

    @Test
    fun merge_should_KeepSharedBlocksOnce() {
        val result = merged(notes = "- shared\n- only desktop\n", stray = "- shared\n- only phone\n")

        assertEquals(listOf("- shared", "- only desktop", "- only phone"), result.text.lines().filter { it.isNotBlank() })
        assertEquals(1, result.addedFromStray)
    }

    @Test
    fun merge_should_KeepNestedChildrenWithTheirParent() {
        val result = merged(notes = "- desktop\n", stray = "- phone parent\n\t- phone child\n\t\t- phone grandchild\n")

        assertEquals(
            listOf("- desktop", "- phone parent", "\t- phone child", "\t\t- phone grandchild"),
            result.text.lines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun merge_should_ReturnNotesUnchanged_When_StrayIsBlank() {
        assertEquals(StrayPageMergeResult.Merged("- keep\n", 0), StrayPageMerge.merge("- keep\n", "  \n"))
    }

    @Test
    fun merge_should_ReturnStrayVerbatim_When_NotesHasNoBlocks() {
        assertEquals(StrayPageMergeResult.Merged("- phone note\n", 1), StrayPageMerge.merge("", "- phone note\n"))
    }

    @Test
    fun merge_should_Refuse_When_EitherCopyHasContentOutsideItsBlocks() {
        assertIs<StrayPageMergeResult.Refused>(StrayPageMerge.merge("title:: a\n\n- x\n", "- y\n"))
        assertIs<StrayPageMergeResult.Refused>(StrayPageMerge.merge("- x\n", "title:: b\n\n- y\n"))
    }

    @Test
    fun merge_should_Refuse_When_OneBlockIdHasDifferentContentOnEachSide() {
        val id = "00000000-0000-4000-8000-000000000001"

        assertIs<StrayPageMergeResult.Refused>(
            StrayPageMerge.merge(notesText = "- desktop edit\n  id:: $id\n", strayText = "- phone edit\n  id:: $id\n"),
        )
    }

    @Test
    fun merge_should_NeverDropScheduledMetadata() {
        val scheduled = "- TODO call the supplier\n  SCHEDULED: <2026-10-17 Sat>\n"

        when (val result = StrayPageMerge.merge("- desktop\n", scheduled)) {
            is StrayPageMergeResult.Merged -> assertTrue(result.text.contains("SCHEDULED: <2026-10-17 Sat>"), result.text)
            is StrayPageMergeResult.Refused -> Unit
        }
    }
}
