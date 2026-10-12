// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownMergePolicyTest {
    @Test
    fun `logseq-union attribute selects the port policy and unknown drivers are Strict`() {
        val attrs = GitAttributes.parse("*.md merge=logseq-union\n*.org merge=my-driver\n*.txt merge=union")
        assertEquals(MergePolicy.LogseqUnion, attrs.policyFor("a.md", MergePolicy.Strict))
        assertEquals(MergePolicy.Strict, attrs.policyFor("a.org", MergePolicy.Union))
        assertEquals(MergePolicy.Union, attrs.policyFor("a.txt", MergePolicy.Strict))
    }

    @Test
    fun `LogseqUnion policy output is the python driver text`() {
        val outcome = mergeMarkdownWithPolicy("- a\n", "- a\n- mine\n", "- a\n", MergePolicy.LogseqUnion)
        assertEquals("- a\n- mine", outcome.markerText)
        assertFalse(outcome.hasConflicts)
    }

    @Test
    fun `same-line edits surface as a conflict instead of being committed`() {
        val outcome = mergeMarkdownWithPolicy("- a\n- b\n", "- a\n- mine\n", "- a\n- theirs\n", MergePolicy.LogseqUnion)
        assertTrue(outcome.hasConflicts)
        assertEquals("- a\n<<<<<<< LOCAL\n- mine\n=======\n- theirs\n>>>>>>> REMOTE", outcome.markerText)
    }

    @Test
    fun `non port policies still use the block merge`() {
        val outcome = mergeMarkdownWithPolicy("- A\n- B\n", "- A\n- B-mine\n", "- A\n- B-theirs\n", MergePolicy.Insertions)
        assertTrue(outcome.hasConflicts)
    }
}
