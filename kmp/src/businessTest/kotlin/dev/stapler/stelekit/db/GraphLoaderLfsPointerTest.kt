// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.git.lfs.LfsOid
import dev.stapler.stelekit.git.lfs.LfsPointer
import dev.stapler.stelekit.git.lfs.LfsPointerCodec
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Git LFS pointer left in the working tree is bookkeeping, never a page. */
class GraphLoaderLfsPointerTest {
    private val pointerText = LfsPointerCodec.serialize(
        LfsPointer(LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"), 3_000_000),
    ).decodeToString()

    /** Just enough of a graph directory for [GraphLoader.loadGraph]. */
    private class GraphFs(val files: MutableMap<String, String>) : StubFileSystem() {
        override fun readFile(path: String): String? = files[path]
        override fun fileExists(path: String) = path in files
        override fun directoryExists(path: String) = path == "/g" || files.keys.any { it.startsWith("$path/") }
        override fun listFiles(path: String) =
            files.keys.filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }.map { it.substringAfterLast('/') }
        override fun listDirectories(path: String) =
            files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }
                .filter { it.isNotEmpty() && files.keys.any { k -> k.startsWith("$path/$it/") } }.distinct()
        override fun getLastModifiedTime(path: String): Long? = if (path in files) 1L else null
    }

    @Test
    fun loadGraph_should_SkipAPointerFile_AndLeaveItsBytesUntouched() = runTest {
        val fs = GraphFs(mutableMapOf("/g/pages/Big.md" to pointerText, "/g/pages/Real.md" to "- hello\n"))
        val pages = InMemoryPageRepository()
        val loader = GraphLoader(fs, pages, InMemoryBlockRepository())

        loader.loadGraph("/g") {}

        val names = pages.getAllPagesSnapshot().getOrNull().orEmpty().map { it.name.lowercase() }
        assertTrue("real" in names, "ordinary pages still load: $names")
        assertFalse("big" in names, "the pointer must not become a page: $names")
        assertEquals(pointerText, fs.files["/g/pages/Big.md"], "the pointer bytes are never rewritten")
        assertEquals("- hello\n", fs.files["/g/pages/Real.md"])
    }

    @Test
    fun loadGraph_should_StillLoadAPageThatMerelyMentionsLfs() = runTest {
        val fs = GraphFs(mutableMapOf("/g/pages/Notes.md" to "- version https://git-lfs.github.com/spec/v1 is the pointer format\n"))
        val pages = InMemoryPageRepository()

        GraphLoader(fs, pages, InMemoryBlockRepository()).loadGraph("/g") {}

        assertTrue("notes" in pages.getAllPagesSnapshot().getOrNull().orEmpty().map { it.name.lowercase() })
    }
}
