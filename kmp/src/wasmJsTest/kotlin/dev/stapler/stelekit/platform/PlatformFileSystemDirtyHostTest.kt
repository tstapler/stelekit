// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtyOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 1.4.7g (D-121, D-137): the dirty set, the host write-through and the caches after a temp write, a temp delete, a
 * replace, a rename and an abandoned temp, against the real wasm file system and a fake host directory.
 * (The fake host directory is flat, so every path here is a top-level file of the graph.)
 */
class PlatformFileSystemDirtyHostTest {
    private class Fixture(val graph: String, val fs: PlatformFileSystem, val host: JsAny)

    private suspend fun fixture(): Fixture {
        val graph = "/stelekit/it-dirtyhost-${Random.nextInt(0, Int.MAX_VALUE)}"
        val fs = PlatformFileSystem()
        fs.preload(graph)
        val host = makeWritableHostRoot()
        fs.hostDirectorySync.hostDirHandle = host
        fs.hostDirectorySync.hostGraphOpfsPath = graph
        return Fixture(graph, fs, host)
    }

    private suspend fun settle(block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < SETTLE_LIMIT_MS) {
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MS) }
            waited += SETTLE_STEP_MS
        }
    }

    private suspend fun quiet() = withContext(Dispatchers.Default) { delay(QUIET_MS) }

    @Test
    fun tempWrite_should_NotBeDirty_AndNotBeHostWritten() = runTest {
        val f = fixture()
        val tmp = "${f.graph}/model.json.tmp"
        assertTrue(f.fs.writeTempBytes(tmp, "{}".encodeToByteArray()))
        quiet()
        assertContentEquals("{}".encodeToByteArray(), f.fs.readFileBytes(tmp))
        assertTrue(f.fs.getDirtySnapshot().isEmpty())
        assertEquals(0, writableRootCreateWritableCallCount(f.host))
        assertFalse(writableRootHasFile(f.host, "model.json.tmp"))
    }

    @Test
    fun tempDelete_should_NotRecordADeleteOrHostDelete() = runTest {
        val f = fixture()
        writableRootSetContent(f.host, "model.json.tmp", "a host file that is not ours")
        val tmp = "${f.graph}/model.json.tmp"
        f.fs.writeTempBytes(tmp, byteArrayOf(1))
        assertTrue(f.fs.deleteTempBytes(tmp))
        quiet()
        assertNull(f.fs.readFileBytes(tmp))
        assertTrue(f.fs.getDirtySnapshot().isEmpty(), "a temp delete is not a DirtyOp.DELETE")
        assertTrue(writableRootHasFile(f.host, "model.json.tmp"), "the host folder never receives a Delete for a temp")
    }

    @Test
    fun deleteOfARealFile_should_StillRecordTheDelete() = runTest {
        val f = fixture()
        writableRootSetContent(f.host, "Old.md", "x")
        f.fs.writeFile("${f.graph}/Old.md", "x")
        assertTrue(f.fs.deleteFile("${f.graph}/Old.md"))
        assertEquals(DirtyOp.DELETE, f.fs.getDirtySnapshot().getValue("Old.md").op)
        settle { !writableRootHasFile(f.host, "Old.md") }
        assertFalse(writableRootHasFile(f.host, "Old.md"))
    }

    @Test
    fun abandonedTemp_should_LeaveNoDirtyEntry() = runTest {
        val f = fixture()
        f.fs.writeTempBytes("${f.graph}/a.json.tmp", byteArrayOf(7))
        quiet()
        assertTrue(f.fs.getDirtySnapshot().isEmpty())
        assertEquals(0, writableRootCreateWritableCallCount(f.host))
        assertEquals(0, f.fs.hostDirectorySync.hostWritePending.size)
    }

    @Test
    fun replace_should_MarkTheTargetDirtyOnce_DropTheTempAndEvictTheStaleCacheEntry() = runTest {
        val f = fixture()
        val tmp = "${f.graph}/model.json.tmp"
        val dest = "${f.graph}/model.json"
        val json = """{"a":1}"""
        f.fs.writeTempBytes(tmp, json.encodeToByteArray())
        assertTrue(f.fs.replaceFileAtomically(tmp, dest))

        assertEquals(json, f.fs.readFile(dest), "a .json target is kept as decoded text")
        assertFalse(f.fs.fileExists(tmp))
        assertEquals(setOf("model.json"), f.fs.getDirtySnapshot().keys)
        assertEquals(DirtyOp.WRITE, f.fs.getDirtySnapshot().getValue("model.json").op)
        settle { writableRootCreateWritableCallCount(f.host) >= 1 }
        quiet()
        assertEquals(1, writableRootCreateWritableCallCount(f.host), "one host write of the final bytes")
        assertTrue(writableRootHasBuffer(f.host, "model.json"))
        assertFalse(writableRootHasFile(f.host, "model.json.tmp"))
    }

    @Test
    fun replace_should_KeepANonJsonTargetAsBytesAndDropTheStaleTextEntry() = runTest {
        val f = fixture()
        val dest = "${f.graph}/blob.bin"
        f.fs.writeFile(dest, "stale text")
        f.fs.writeTempBytes("${f.graph}/blob.bin.tmp", byteArrayOf(0, -1, 5))
        assertTrue(f.fs.replaceFileAtomically("${f.graph}/blob.bin.tmp", dest))
        assertContentEquals(byteArrayOf(0, -1, 5), f.fs.readFileBytes(dest))
        assertNull(f.fs.readFile(dest), "the other cache's stale entry is removed")
        assertFalse(f.fs.fileExists("${f.graph}/blob.bin.tmp"))
    }

    @Test
    fun replace_should_ReturnFalseWhenTheTempIsMissing() = runTest {
        val f = fixture()
        assertFalse(f.fs.replaceFileAtomically("${f.graph}/none.tmp", "${f.graph}/x.json"))
        assertTrue(f.fs.getDirtySnapshot().isEmpty())
    }

    @Test
    fun rename_should_NotRecordADeleteForTheSource_AndHostWriteTheTarget() = runTest {
        val f = fixture()
        val from = "${f.graph}/a.bin.tmp"
        val to = "${f.graph}/a.bin"
        writableRootSetContent(f.host, "a.bin.tmp", "host file named like the source")
        f.fs.writeTempBytes(from, byteArrayOf(4, 2))
        assertTrue(f.fs.renameFile(from, to))

        assertContentEquals(byteArrayOf(4, 2), f.fs.readFileBytes(to))
        assertFalse(f.fs.fileExists(from))
        val dirty = f.fs.getDirtySnapshot()
        assertEquals(setOf("a.bin"), dirty.keys)
        assertEquals(DirtyOp.WRITE, dirty.getValue("a.bin").op)
        settle { writableRootHasBuffer(f.host, "a.bin") }
        assertTrue(writableRootHasBuffer(f.host, "a.bin"), "one host write of the target")
        assertTrue(writableRootHasFile(f.host, "a.bin.tmp"), "no host Delete of the source")
    }

    private companion object {
        const val SETTLE_LIMIT_MS = 2_000L
        const val SETTLE_STEP_MS = 10L
        const val QUIET_MS = 50L
    }
}
