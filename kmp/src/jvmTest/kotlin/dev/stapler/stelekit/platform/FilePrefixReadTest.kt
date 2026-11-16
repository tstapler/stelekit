package dev.stapler.stelekit.platform

import dev.stapler.stelekit.db.FakeRelocationFileSystem
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePrefixReadTest {
    private fun withDir(block: (java.io.File, PlatformFileSystem) -> Unit) {
        val dir = Files.createTempDirectory("prefix_").toFile()
        try {
            block(dir, PlatformFileSystem.withRoot(dir.path))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun shortFile_should_ReturnAllOfIt() = withDir { dir, fs ->
        val f = dir.resolve("a.json").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertContentEquals(byteArrayOf(1, 2, 3), fs.readFilePrefix(f.path, 10))
    }

    @Test
    fun longerFile_should_ReturnExactlyMaxBytes() = withDir { dir, fs ->
        val f = dir.resolve("a.json").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val prefix = fs.readFilePrefix(f.path, 16)
        assertContentEquals(ByteArray(16) { it.toByte() }, prefix)
    }

    @Test
    fun absentFile_should_BeNull() = withDir { dir, fs ->
        assertNull(fs.readFilePrefix(dir.resolve("nope.json").path, 16))
    }

    @Test
    fun hugeFile_should_NeverReadMoreThanAskedFor() = withDir { dir, fs ->
        // Sparse 2 GiB file: a whole-file read would fail the size cap (or exhaust memory); a prefix read must not.
        val f = dir.resolve("huge.json")
        RandomAccessFile(f, "rw").use { it.setLength(2L * 1024 * 1024 * 1024); it.seek(0); it.write(byteArrayOf(9, 8, 7)) }
        val prefix = fs.readFilePrefix(f.path, 3)
        assertContentEquals(byteArrayOf(9, 8, 7), prefix)
    }

    @Test
    fun pathOutsideTheRoot_should_BeNull() = withDir { _, fs ->
        assertNull(fs.readFilePrefix("/etc/hostname", 16))
    }

    @Test
    fun fileSystemWithoutReadFileBytes_should_ReturnNull() {
        val bare = object : FakeRelocationFileSystem() {
            override fun readFileBytes(path: String): ByteArray? = throw UnsupportedOperationException("no byte reads")
        }
        assertNull(bare.readFilePrefix("/x", 4))
    }

    @Test
    fun fileSystemWithReadFileBytes_should_TruncateIt() {
        val withBytes = object : FakeRelocationFileSystem() {
            override fun readFileBytes(path: String): ByteArray? = byteArrayOf(1, 2, 3, 4, 5)
        }
        assertEquals(listOf<Byte>(1, 2), withBytes.readFilePrefix("/x", 2)!!.toList())
    }
}
