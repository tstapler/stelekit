// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.merge.MergePolicy
import dev.stapler.stelekit.git.merge.SidecarConflictResolver
import dev.stapler.stelekit.git.model.ConflictFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidecarConflictResolverTest {

    private fun sidecar(raw: String?, path: String = ".stelekit/pages/2026_11_13.meta.json") =
        ConflictFile(filePath = "/repo/$path", wikiRelativePath = path, hunks = emptyList(), rawContent = raw)

    private val a = """{"uuid":"u1","hash":"h1","pos":"a0","parent":null}"""
    private val b = """{"uuid":"u2","hash":"h2","pos":"a1","parent":null}"""
    private val c = """{"uuid":"u3","hash":"h3","pos":"a2","parent":null}"""

    @Test
    fun `keeps both sides local first and drops exact duplicates`() {
        val raw = "$a\n<<<<<<< HEAD\n$b\n=======\n$b\n$c\n>>>>>>> origin/main\n"

        val resolved = SidecarConflictResolver.resolve(sidecar(raw))!!

        assertEquals("$a\n$b\n$c", resolved.rawContent)
        assertEquals(MergePolicy.Union, resolved.autoResolvedBy)
    }

    @Test
    fun `handles several hunks in one file`() {
        val raw = "<<<<<<< HEAD\n$a\n=======\n$b\n>>>>>>> r\n$c\n<<<<<<< HEAD\n$b\n=======\n$a\n>>>>>>> r\n"

        assertEquals("$a\n$b\n$c", SidecarConflictResolver.resolve(sidecar(raw))!!.rawContent)
    }

    @Test
    fun `ignores the base section of diff3-style markers`() {
        val base = """{"uuid":"old","hash":"hOld","pos":"a0","parent":null}"""
        val raw = "<<<<<<< HEAD\n$a\n||||||| base\n$base\n=======\n$b\n>>>>>>> r\n"

        val merged = SidecarConflictResolver.resolve(sidecar(raw))!!.rawContent!!

        assertEquals("$a\n$b", merged)
        assertFalse("old" in merged)
    }

    @Test
    fun `an unterminated hunk is left for the user`() {
        assertNull(SidecarConflictResolver.resolve(sidecar("<<<<<<< HEAD\n$a\n=======\n$b\n")))
    }

    @Test
    fun `a conflict without markers or content is left for the user`() {
        assertNull(SidecarConflictResolver.resolve(sidecar(null)))
        assertNull(SidecarConflictResolver.resolve(sidecar("$a\n$b\n")), "no markers means nothing to union")
    }

    @Test
    fun `only app-owned sidecars qualify`() {
        val raw = "<<<<<<< HEAD\n$a\n=======\n$b\n>>>>>>> r\n"

        assertNull(SidecarConflictResolver.resolve(sidecar(raw, "pages/Note.md")))
        assertNull(SidecarConflictResolver.resolve(sidecar(raw, "data/other.meta.json")))
        assertNull(SidecarConflictResolver.resolve(sidecar(raw, ".stelekit/config.json")))
    }

    @Test
    fun `recognises sidecars under a wiki subdirectory and with backslashes`() {
        assertTrue(SidecarConflictResolver.isSidecar("logseq/.stelekit/pages/x.meta.json"))
        assertTrue(SidecarConflictResolver.isSidecar("logseq\\.stelekit\\pages\\x.meta.json"))
    }
}
