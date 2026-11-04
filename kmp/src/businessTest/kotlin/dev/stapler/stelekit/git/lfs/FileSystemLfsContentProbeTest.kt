// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileSystemLfsContentProbeTest {
    private val root = RepoRoot("/repo")

    private fun fsWith(path: String, bytes: ByteArray) = FakeFileSystem().apply { writeFileBytes("/repo/$path", bytes) }

    private class ThrowingFileSystem(private val failure: Throwable) : FakeFileSystem() {
        override fun getFileSize(path: String): Long? = throw failure
        override fun readFileBytes(path: String): ByteArray? = throw failure
        override fun readFile(path: String): String? = throw failure
    }

    @Test
    fun size_reportsByteLengthOfTheRepoRelativeFile() = runBlocking {
        val probe = FileSystemLfsContentProbe(fsWith("a/big.bin", ByteArray(1234)), root)

        assertEquals(1234L, probe.size("a/big.bin"))
    }

    @Test
    fun readHead_truncatesToMaxBytesAndReturnsSmallFilesWhole() = runBlocking {
        val bytes = ByteArray(100) { it.toByte() }
        val probe = FileSystemLfsContentProbe(fsWith("f", bytes), root)

        assertContentEquals(bytes.copyOf(10), probe.readHead("f", 10))
        assertContentEquals(bytes, probe.readHead("f", 100))
        assertContentEquals(bytes, probe.readHead("f", 500))
    }

    @Test
    fun missingFileYieldsNullForBothProbes() = runBlocking {
        val probe = FileSystemLfsContentProbe(FakeFileSystem(), root)

        assertNull(probe.size("nope"))
        assertNull(probe.readHead("nope", 8))
    }

    @Test
    fun filesystemFailureIsSwallowedAsNull() = runBlocking {
        val probe = FileSystemLfsContentProbe(ThrowingFileSystem(IllegalStateException("io")), root)

        assertNull(probe.size("f"))
        assertNull(probe.readHead("f", 8))
    }

    @Test
    fun cancellationIsNeverSwallowed() {
        val probe = FileSystemLfsContentProbe(ThrowingFileSystem(CancellationException("stop")), root)

        assertFailsWith<CancellationException> { runBlocking { probe.size("f") } }
        assertFailsWith<CancellationException> { runBlocking { probe.readHead("f", 8) } }
    }

    @Test
    fun rulesLoader_treatsUnreadableAttributeFilesAsAbsent() {
        val loader = LfsRulesLoader(ThrowingFileSystem(IllegalStateException("io")), root)

        val policy = LfsPathPolicy(loader.load(listOf("a/x.png")))

        assertFalse(policy.isTracked("a/x.png"))
    }

    @Test
    fun rulesLoader_propagatesCancellation() {
        val loader = LfsRulesLoader(ThrowingFileSystem(CancellationException("stop")), root)

        assertFailsWith<CancellationException> { loader.load(listOf("a/x.png")) }
    }
}
