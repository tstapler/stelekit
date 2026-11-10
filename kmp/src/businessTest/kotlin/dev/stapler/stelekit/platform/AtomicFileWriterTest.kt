package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicFileWriterTest {
    private val dest = "/g/assets/stelekit-sketch/proj-abc123/project-model.json"
    private val old = "old".encodeToByteArray()
    private val new = "new".encodeToByteArray()

    /** In-memory file system with a switchable crash: once [dead], every mutation throws, like a killed process. */
    private class FaultFs : FakeRelocationFileSystem() {
        var atomic = false
        var tempRenameSupported = true
        var byteWritesThrow = false
        var failRename = false
        var corruptTemp = false
        var crashAtRename = false
        var crashAtReplace = false
        var dead = false
        val mtimes = mutableMapOf<String, Long>()

        override val supportsTempRenameWrites get() = tempRenameSupported
        override fun supportsAtomicReplace(path: String) = atomic
        override fun getLastModifiedTime(path: String): Long? = mtimes[path] ?: super.getLastModifiedTime(path)

        override fun writeFileBytes(path: String, data: ByteArray): Boolean {
            if (dead) error("process is gone")
            if (byteWritesThrow) throw UnsupportedOperationException("no byte writes")
            return super.writeFileBytes(path, if (corruptTemp && path.endsWith(".tmp")) data.copyOf(data.size - 1) else data)
        }

        override fun deleteFile(path: String): Boolean {
            if (dead) error("process is gone")
            return super.deleteFile(path)
        }

        override fun renameFile(from: String, to: String): Boolean {
            if (dead) error("process is gone")
            if (crashAtRename) { dead = true; error("crash between delete and rename") }
            return !failRename && super.renameFile(from, to)
        }

        override fun replaceFileAtomically(from: String, to: String): Boolean {
            if (crashAtReplace) { dead = true; error("crash before the swap") }
            val bytes = readFileBytes(from) ?: return false
            super.writeFileBytes(to, bytes)
            super.deleteFile(from)
            return true
        }

        fun seed(path: String, bytes: ByteArray) { super.writeFileBytes(path, bytes) }
        fun tmps() = allFilePaths().filter { it.endsWith(SUFFIX) }
    }

    private val fs = FaultFs()
    private var now = 1_000_000_000L
    private fun writer(canonicalize: (String) -> String = { it }, saf: Boolean = false) =
        AtomicFileWriter(fs, canonicalize, SUFFIX, saf) { now }

    @Test
    fun write_should_CreateAFileThatDidNotExist() {
        assertEquals(Unit.right(), writer().write(dest, new, original = null))
        assertContentEquals(new, fs.readFileBytes(dest))
        assertEquals(emptyList(), fs.tmps())
    }

    @Test
    fun write_should_ReplaceViaAtomicSwapWhenSupported() {
        fs.atomic = true
        fs.seed(dest, old)
        assertEquals(AtomicWriteMode.ATOMIC_REPLACE, writer().mode(dest))
        assertEquals(Unit.right(), writer().write(dest, new, old))
        assertContentEquals(new, fs.readFileBytes(dest))
        assertEquals(emptyList(), fs.tmps())
    }

    @Test
    fun write_should_ReplaceByDeleteAndRenameWhenNotAtomic() {
        fs.seed(dest, old)
        assertEquals(AtomicWriteMode.TEMP_RENAME, writer().mode(dest))
        assertEquals(Unit.right(), writer().write(dest, new, old))
        assertContentEquals(new, fs.readFileBytes(dest))
    }

    @Test
    fun write_should_LeaveTheOldFileIntactWhenItCrashesAfterTheTempWriteBeforeTheSwap() {
        fs.atomic = true
        fs.crashAtReplace = true
        fs.seed(dest, old)

        val result = writer().write(dest, new, old)

        assertIs<DomainError.FileSystemError.WriteFailed>(result.leftValue())
        assertContentEquals(old, fs.readFileBytes(dest))
    }

    @Test
    fun write_should_LeaveTheDestinationAbsentWhenItCrashesBetweenDeleteAndRename() {
        fs.crashAtRename = true
        fs.seed(dest, old)

        writer().write(dest, new, old)

        assertNull(fs.readFileBytes(dest), "documented crash window: the reconciler sees Absent and restores from the database")
        assertContentEquals(new, fs.readFileBytes(fs.tmps().single()), "the complete new bytes survive in the temp file")
    }

    @Test
    fun write_should_ReturnRetryableAndDeleteTheTempWhenThePreconditionFails() {
        fs.seed(dest, old)

        val result = writer().write(dest, new, old, precondition = { false.right() })

        assertIs<DomainError.MergeError.Retryable>(result.leftValue())
        assertContentEquals(old, fs.readFileBytes(dest))
        assertEquals(emptyList(), fs.tmps())
    }

    @Test
    fun write_should_RunThePreconditionAfterTheTempWrite() {
        var tempExistedAtCheck = false
        writer().write(dest, new, null, precondition = { tempExistedAtCheck = fs.tmps().isNotEmpty(); true.right() })
        assertTrue(tempExistedAtCheck)
    }

    @Test
    fun write_should_PropagateAPreconditionErrorAndCleanUp() {
        val boom = DomainError.FileSystemError.ReadFailed(dest, "unreadable")
        val result = writer().write(dest, new, null, precondition = { Either.Left(boom) })
        assertEquals(boom, result.leftValue())
        assertEquals(emptyList(), fs.tmps())
    }

    @Test
    fun write_should_RestoreTheOriginalWhenTheRenameFails() {
        fs.failRename = true
        fs.seed(dest, old)

        val result = writer().write(dest, new, old)

        assertIs<DomainError.FileSystemError.WriteFailed>(result.leftValue())
        assertContentEquals(old, fs.readFileBytes(dest))
        assertEquals(emptyList(), fs.tmps())
    }

    @Test
    fun write_should_RefuseWhenTheTempReadBackDiffers() {
        fs.corruptTemp = true
        fs.seed(dest, old)

        val result = writer().write(dest, new, old)

        assertIs<DomainError.FileSystemError.WriteFailed>(result.leftValue())
        assertContentEquals(old, fs.readFileBytes(dest), "verification runs before the original is deleted")
    }

    @Test
    fun write_should_RefuseASymlinkedDestination() {
        fs.seed(dest, old)
        val link = writer(canonicalize = { if (it == dest) "/elsewhere/target.json" else it })

        val result = link.write(dest, new, old)

        assertEquals(DomainError.FileSystemError.SymlinkRefused(dest), result.leftValue())
        assertContentEquals(old, fs.readFileBytes(dest))
        assertEquals(emptyList(), fs.tmps())
    }

    @Test
    fun write_should_ReturnUnsupportedAndWriteNothingWhereTempRenameIsUnavailable() {
        fs.tempRenameSupported = false
        val w = writer()

        assertEquals(AtomicWriteMode.UNSUPPORTED, w.mode(dest))
        assertEquals(DomainError.FileSystemError.AtomicWriteUnsupported(dest), w.write(dest, new).leftValue())
        assertFalse(fs.fileExists(dest), "there is no direct-overwrite fallback")
    }

    @Test
    fun write_should_ReturnUnsupportedWhenByteWritesThrow() {
        fs.byteWritesThrow = true
        assertEquals(DomainError.FileSystemError.AtomicWriteUnsupported(dest), writer().write(dest, new).leftValue())
    }

    @Test
    fun mode_should_NotTrustAtomicReplaceOnSafPathsUntilVerified() {
        fs.atomic = true
        val saf = "content://tree/primary%3Anotes/assets/x.json"
        assertEquals(AtomicWriteMode.TEMP_RENAME, writer(saf = false).mode(saf))
        assertEquals(AtomicWriteMode.ATOMIC_REPLACE, writer(saf = true).mode(saf))
        assertEquals(AtomicWriteMode.ATOMIC_REPLACE, writer(saf = false).mode(dest))
    }

    @Test
    fun sweep_should_RemoveTempsOlderThanMaxAgeAndKeepYoungerOnes() {
        val dir = dest.substringBeforeLast('/')
        val stale = "$dir/a.json.1$SUFFIX"
        val borderline = "$dir/b.json.2$SUFFIX"
        val fresh = "$dir/c.json.3$SUFFIX"
        listOf(stale, borderline, fresh).forEach { fs.seed(it, old) }
        fs.mtimes[stale] = now - StaleTempSweeper.MAX_AGE.inWholeMilliseconds - 1
        fs.mtimes[borderline] = now - StaleTempSweeper.MAX_AGE.inWholeMilliseconds
        fs.mtimes[fresh] = now - 1_000

        writer().write(dest, new)

        assertFalse(fs.fileExists(stale))
        assertTrue(fs.fileExists(borderline), "exactly MAX_AGE old is not yet stale")
        assertTrue(fs.fileExists(fresh))
    }

    private fun Either<DomainError, Unit>.leftValue(): DomainError = (this as Either.Left).value

    private companion object {
        const val SUFFIX = ".stele-sidecar.tmp"
    }
}
