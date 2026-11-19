package dev.stapler.stelekit.platform

import arrow.core.Either
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemStoragePortsTest {
    private val fs = FakeRelocationFileSystem()

    @Test
    fun defaults_should_BeReadyRightAndPlainWrites() = runTest {
        assertEquals(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false), fs.storageStatus)
        assertTrue(fs.awaitDurable("/g/a.json") is Either.Right)

        val data = byteArrayOf(1, 2, 3)
        assertTrue(fs.writeTempBytes("/g/a.json.tmp", data))
        assertContentEquals(data, fs.readFileBytes("/g/a.json.tmp"))
        assertContentEquals(data, fs.readFileBytesSuspend("/g/a.json.tmp"))
        assertTrue(fs.deleteTempBytes("/g/a.json.tmp"))
        assertFalse(fs.fileExists("/g/a.json.tmp"))
        assertNull(fs.readFileBytesSuspend("/g/a.json.tmp"))
    }

    @Test
    fun alwaysHeldLease_should_StayHeld() = runTest {
        val lease = AlwaysHeldSketchWriterLease()
        assertEquals(SketchWriterLeaseState.Held, lease.state.value)
        assertEquals(SketchWriterLeaseState.Held, lease.acquire())
        lease.release()
        assertEquals(SketchWriterLeaseState.Held, lease.state.value)
    }

    @Test
    fun alwaysHeldLease_should_KeepAConstantEpoch_AcrossAcquireAndRelease() = runTest {
        val lease = AlwaysHeldSketchWriterLease()
        val before = lease.epoch
        lease.acquire()
        lease.release()
        lease.acquire()
        assertEquals(before, lease.epoch)
    }

    @Test
    fun readFilePrefix_default_should_TruncateAndReturnNullWhenAbsent() {
        fs.writeFileBytes("/g/a.json", byteArrayOf(1, 2, 3, 4, 5))
        assertContentEquals(byteArrayOf(1, 2), fs.readFilePrefix("/g/a.json", 2))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), fs.readFilePrefix("/g/a.json", 99))
        assertNull(fs.readFilePrefix("/g/absent.json", 2))
    }
}
