// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.HunkResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictResolverTest {

    private val resolver = ConflictResolver()

    @Test
    fun `parseConflictFile extracts local and remote lines for a single hunk`() {
        val content = """
            # Journal
            <<<<<<< HEAD
            local line one
            local line two
            =======
            remote line one
            >>>>>>> origin/main
            trailing content
        """.trimIndent()

        val result = resolver.parseConflictFile("/repo/journal.md", content, wikiRoot = "/repo")
        val file = (result as arrow.core.Either.Right).value

        assertEquals(1, file.hunks.size)
        val hunk = file.hunks.single()
        assertEquals(listOf("local line one", "local line two"), hunk.localLines)
        assertEquals(listOf("remote line one"), hunk.remoteLines)
        assertEquals(HunkResolution.Unresolved, hunk.resolution)
        assertEquals("journal.md", file.wikiRelativePath)
    }

    @Test
    fun `parseConflictFile extracts multiple hunks in order`() {
        val content = """
            <<<<<<< HEAD
            local A
            =======
            remote A
            >>>>>>> origin/main
            unchanged middle line
            <<<<<<< HEAD
            local B
            =======
            remote B
            >>>>>>> origin/main
        """.trimIndent()

        val result = resolver.parseConflictFile("/repo/pages/foo.md", content, wikiRoot = "/repo")
        val file = (result as arrow.core.Either.Right).value

        assertEquals(2, file.hunks.size)
        assertEquals(listOf("local A"), file.hunks[0].localLines)
        assertEquals(listOf("local B"), file.hunks[1].localLines)
    }

    @Test
    fun `parseConflictFile returns error for content with no conflict markers`() {
        val result = resolver.parseConflictFile("/repo/clean.md", "no markers here\n", wikiRoot = "/repo")
        assertTrue(result.isLeft())
    }

    @Test
    fun `parseConflictFile returns error for empty content`() {
        val result = resolver.parseConflictFile("/repo/empty.md", "", wikiRoot = "/repo")
        assertTrue(result.isLeft())
    }

    @Test
    fun `applyResolutions reconstructs content using AcceptLocal AcceptRemote and Manual per hunk`() {
        val content = """
            # Title
            <<<<<<< HEAD
            local A
            =======
            remote A
            >>>>>>> origin/main
            middle
            <<<<<<< HEAD
            local B
            =======
            remote B
            >>>>>>> origin/main
        """.trimIndent()

        val parsed = (resolver.parseConflictFile("/repo/f.md", content, "/repo") as arrow.core.Either.Right).value
        val resolvedHunks = listOf(
            parsed.hunks[0].copy(resolution = HunkResolution.AcceptLocal),
            parsed.hunks[1].copy(resolution = HunkResolution.AcceptRemote),
        )

        val result = resolver.applyResolutions(content, resolvedHunks)
        val resolved = (result as arrow.core.Either.Right).value

        assertEquals("# Title\nlocal A\nmiddle\nremote B", resolved)
    }

    @Test
    fun `applyResolutions substitutes manualContent when resolution is Manual`() {
        val content = """
            <<<<<<< HEAD
            local only
            =======
            remote only
            >>>>>>> origin/main
        """.trimIndent()

        val parsed = (resolver.parseConflictFile("/repo/f.md", content, "/repo") as arrow.core.Either.Right).value
        val resolvedHunks = listOf(
            parsed.hunks[0].copy(resolution = HunkResolution.Manual, manualContent = "hand-merged line"),
        )

        val result = resolver.applyResolutions(content, resolvedHunks)
        assertEquals("hand-merged line", (result as arrow.core.Either.Right).value)
    }

    @Test
    fun `applyResolutions fails when any hunk is still Unresolved`() {
        val content = """
            <<<<<<< HEAD
            local
            =======
            remote
            >>>>>>> origin/main
        """.trimIndent()

        val parsed = (resolver.parseConflictFile("/repo/f.md", content, "/repo") as arrow.core.Either.Right).value

        val result = resolver.applyResolutions(content, parsed.hunks)
        assertTrue(result.isLeft())
    }
}
