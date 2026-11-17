// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.4.7b: the byte API, the sidecar byte preload and `reloadSidecarRoot()` over the real wasm file system and OPFS. */
class PlatformFileSystemBytesTest {
    private fun freshGraph(): String = "/stelekit/it-bytes-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun sidecar(graph: String, rel: String) = "${SidecarLiveSet.rootOf(graph)}/$rel"

    @Test
    fun readFileBytes_should_ReturnBytesOrEncodedText_AndNullWhenAbsent() = runTest {
        val fs = PlatformFileSystem()
        val graph = freshGraph()
        fs.preload(graph)
        fs.writeFile("$graph/t.txt", "héllo")
        fs.writeFileBytes("$graph/b.bin", byteArrayOf(1, 2, 3))
        assertContentEquals("héllo".encodeToByteArray(), fs.readFileBytes("$graph/t.txt"))
        assertContentEquals(byteArrayOf(1, 2, 3), fs.readFileBytes("$graph/b.bin"))
        assertNull(fs.readFileBytes("$graph/missing"))
    }

    @Test
    fun readFilePrefix_should_CutTextAndBytesToMaxBytes() = runTest {
        val fs = PlatformFileSystem()
        val graph = freshGraph()
        fs.preload(graph)
        fs.writeFile("$graph/t.txt", "abcdefgh")
        fs.writeFileBytes("$graph/b.bin", byteArrayOf(1, 2, 3, 4, 5))
        assertContentEquals("abc".encodeToByteArray(), fs.readFilePrefix("$graph/t.txt", 3))
        assertContentEquals(byteArrayOf(1, 2), fs.readFilePrefix("$graph/b.bin", 2))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), fs.readFilePrefix("$graph/b.bin", 99))
        assertNull(fs.readFilePrefix("$graph/missing", 4))
    }

    @Test
    fun renameFile_should_MoveByteAndTextEntries() = runTest {
        val fs = PlatformFileSystem()
        val graph = freshGraph()
        fs.preload(graph)
        fs.writeFileBytes("$graph/a.bin", byteArrayOf(9, 8))
        fs.writeFile("$graph/a.txt", "text")
        assertTrue(fs.renameFile("$graph/a.bin", "$graph/c.bin"))
        assertTrue(fs.renameFile("$graph/a.txt", "$graph/c.txt"))
        assertContentEquals(byteArrayOf(9, 8), fs.readFileBytes("$graph/c.bin"))
        assertEquals("text", fs.readFile("$graph/c.txt"))
        assertFalse(fs.fileExists("$graph/a.bin"))
        assertFalse(fs.fileExists("$graph/a.txt"))
        assertFalse(fs.renameFile("$graph/none", "$graph/x"))
    }

    @Test
    fun listingAndExistence_should_SeeByteEntries() = runTest {
        val fs = PlatformFileSystem()
        val graph = freshGraph()
        fs.preload(graph)
        fs.writeFileBytes("$graph/dir/b.bin", byteArrayOf(1))
        fs.writeFile("$graph/dir/t.txt", "x")
        assertTrue(fs.fileExists("$graph/dir/b.bin"))
        assertEquals(setOf("b.bin", "t.txt"), fs.listFiles("$graph/dir").toSet())
        assertTrue(fs.directoryExists("$graph/dir"))
        assertEquals(listOf("dir"), fs.listDirectories(graph))
    }

    @Test
    fun preload_should_ReadLiveFilesAndListEverythingElseByNameOnly() = runTest {
        val graph = freshGraph()
        opfsWriteFile(sidecar(graph, "kitchen/project-model.json"), "{\"m\":1}")
        opfsWriteFile(sidecar(graph, "kitchen/sheets/a.v2.json"), "{\"s\":1}")
        opfsWriteFile(sidecar(graph, "kitchen/backup/a.v1.json"), "{\"b\":1}")
        opfsWriteFile(sidecar(graph, "kitchen/conflicts/a.json"), "{\"c\":1}")
        val fs = PlatformFileSystem()
        fs.preload(graph)

        assertEquals("{\"m\":1}", fs.readFile(sidecar(graph, "kitchen/project-model.json")))
        assertNotNull(fs.readFileBytes(sidecar(graph, "kitchen/sheets/a.v2.json")))
        assertNull(fs.readFile(sidecar(graph, "kitchen/backup/a.v1.json")), "a backup is never preloaded")
        assertNull(fs.readFileBytes(sidecar(graph, "kitchen/conflicts/a.json")), "a conflict copy is never preloaded")
        assertTrue(fs.fileExists(sidecar(graph, "kitchen/backup/a.v1.json")))
        assertEquals(listOf("a.v1.json"), fs.listFiles(sidecar(graph, "kitchen/backup")))
        assertIs<SidecarPreloadOutcome.Loaded>(fs.sidecarPreloadOutcome)

        assertContentEquals("{\"b\":1}".encodeToByteArray(), fs.readFileBytesSuspend(sidecar(graph, "kitchen/backup/a.v1.json")))
        assertNull(fs.readFileBytes(sidecar(graph, "kitchen/backup/a.v1.json")), "the on-demand read caches nothing")
    }

    @Test
    fun missingSidecarRoot_should_BeLoadedWithNothing() = runTest {
        val fs = PlatformFileSystem()
        fs.preload(freshGraph())
        assertEquals(SidecarPreloadOutcome.Loaded(0L), fs.sidecarPreloadOutcome)
    }

    @Test
    fun reloadSidecarRoot_should_ReplaceAStaleCacheWithTheFilesOnDisk() = runTest {
        val graph = freshGraph()
        val model = sidecar(graph, "kitchen/project-model.json")
        opfsWriteFile(model, "old")
        val fs = PlatformFileSystem()
        fs.preload(graph)
        assertEquals("old", fs.readFile(model))

        opfsWriteFile(model, "newer")
        opfsWriteFile(sidecar(graph, "kitchen/sheets/b.v2.json"), "sheet")
        fs.reloadSidecarRoot()

        assertEquals("newer", fs.readFile(model))
        assertEquals("sheet", fs.readFile(sidecar(graph, "kitchen/sheets/b.v2.json")))
    }
}
