// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.SketchWriterLease
import dev.stapler.stelekit.platform.SketchWriterLeaseState
import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import dev.stapler.stelekit.projectbridge.GRAPH
import dev.stapler.stelekit.projectbridge.SidecarFs
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.projectbridge.newStore
import dev.stapler.stelekit.projectbridge.sidecarPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A lease the test drives: [lose] moves it to `HeldElsewhere`, [reacquire] makes it `Held` under a new epoch. */
internal class TestSketchWriterLease(initial: SketchWriterLeaseState = SketchWriterLeaseState.Held) : SketchWriterLease {
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<SketchWriterLeaseState> = mutable
    override var epoch: Long = 0
        private set

    fun lose() { mutable.value = SketchWriterLeaseState.HeldElsewhere }
    fun reacquire() { epoch++; mutable.value = SketchWriterLeaseState.Held }
    override suspend fun acquire(): SketchWriterLeaseState = mutable.value
    override fun release() { mutable.value = SketchWriterLeaseState.HeldElsewhere }
}

/** Task 1.4.3o: every write under the sidecar root is refused without the lease, and a stale parked bundle is never replayed. */
class WriterLeaseRefusalTest {
    private val slug = "proj-0a1b2c"
    private val files = mapOf(
        "project-model.json" to "{\"schema\":1,\"sheets\":[{\"uuid\":\"s1\"}]}".encodeToByteArray(),
        "sheets/s1.json" to "{\"schema\":1}".encodeToByteArray(),
    )

    private fun noFiles(fs: SidecarFs) = fs.allFilePaths().none { it.contains("/assets/stelekit-sketch/") }

    private fun assertRefused(error: Any?) = assertIs<DomainError.FileSystemError.WriterLeaseNotHeld>(error)

    @Test
    fun writeWithoutLease_should_ReturnFailedAndWriteNothing() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs, lease = TestSketchWriterLease(SketchWriterLeaseState.HeldElsewhere))
        assertRefused(assertIs<SidecarWriteOutcome.Failed>(store.write(slug, files)).error)
        assertTrue(noFiles(fs))
        assertFalse(fs.fileExists("$GRAPH/.gitignore"))
    }

    @Test
    fun writeSourceSnapshotWithoutLease_should_ReturnFailedAndWriteNothing() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs, lease = TestSketchWriterLease(SketchWriterLeaseState.Acquiring))
        assertRefused(assertIs<SidecarWriteOutcome.Failed>(store.writeSourceSnapshot(slug, "abcd1234", byteArrayOf(1))).error)
        assertTrue(noFiles(fs))
    }

    @Test
    fun writeConflictWithoutLease_should_ReturnLeft() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs, lease = TestSketchWriterLease(SketchWriterLeaseState.HeldElsewhere))
        assertRefused(store.writeConflict(slug, files).leftOrNull())
        assertTrue(noFiles(fs))
    }

    @Test
    fun writeNowWithoutLease_should_ReturnFailed() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs, lease = TestSketchWriterLease(SketchWriterLeaseState.HeldElsewhere))
        assertRefused(assertIs<SidecarWriteOutcome.Failed>(store.writeNow(slug, files)).error)
        assertTrue(noFiles(fs))
    }

    @Test
    fun writeBackupWithoutLease_should_ReturnLeftAndCreateNoBackupDirectory() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs, lease = TestSketchWriterLease(SketchWriterLeaseState.HeldElsewhere))
        assertRefused(store.writeBackup(slug, "s1", byteArrayOf(1)).leftOrNull())
        assertFalse(fs.directoryExists(sidecarPath(slug, FileProjectSidecarStore.BACKUP_DIR)))
        assertTrue(noFiles(fs))
    }

    @Test
    fun writeSheetConflictCopyWithoutLease_should_ReturnLeftAndWriteNoCopy() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs, lease = TestSketchWriterLease(SketchWriterLeaseState.HeldElsewhere))
        assertRefused(store.writeSheetConflictCopy(slug, "s1", byteArrayOf(1)).leftOrNull())
        assertTrue(noFiles(fs))
    }

    @Test
    fun deleteAndPruneWithoutLease_should_ReturnLeft() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val fs = SidecarFs()
        val store = newStore(fs, lease = lease)
        store.write(slug, files)
        lease.lose()
        assertRefused(store.deleteV2Sheet(slug, "s1").leftOrNull())
        assertRefused(store.pruneConflictCopies(slug, "s1", emptySet()).leftOrNull())
    }

    @Test
    fun modelWriteFromStaleTab_should_BeRefused() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val fs = SidecarFs()
        val store = newStore(fs, lease = lease)
        store.write(slug, files)
        val before = fs.readFileBytes(sidecarPath(slug, "project-model.json"))!!
        lease.lose()
        val newModel = files + ("project-model.json" to "{\"schema\":1,\"sheets\":[]}".encodeToByteArray())
        assertRefused(assertIs<SidecarWriteOutcome.Failed>(store.write(slug, newModel)).error)
        assertTrue(before.contentEquals(fs.readFileBytes(sidecarPath(slug, "project-model.json"))!!))
    }

    @Test
    fun heldLease_should_StillWrite_AndRead() = runBlocking<Unit> {
        val store = newStore(SidecarFs(), lease = TestSketchWriterLease())
        assertEquals(SidecarWriteOutcome.Written, store.write(slug, files))
        assertIs<SidecarRead.Present>(store.read(slug))
    }

    @Test
    fun flushPendingAfterLeaseLoss_should_KeepWritesParked() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val fs = SidecarFs()
        var busy = true
        val store = newStore(fs, lease) { busy }
        assertEquals(SidecarWriteOutcome.Deferred, store.write(slug, files))
        lease.lose()
        busy = false
        assertEquals(0, store.flushPending())
        assertTrue(noFiles(fs))
        lease.reacquire()
        // Parked under epoch 0, flushed under epoch 1: the bundle was parked before the loss.
        assertEquals(0, store.flushPending())
    }

    @Test
    fun flushPendingWithTheSameEpoch_should_WriteTheParkedBundle() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        var busy = true
        val store = newStore(SidecarFs(), lease) { busy }
        store.write(slug, files)
        busy = false
        assertEquals(1, store.flushPending())
    }

    @Test
    fun flushPendingAfterLeaseReacquire_should_NotReplayStaleBundle() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val fs = SidecarFs()
        var busy = true
        val store = newStore(fs, lease) { busy }
        store.write(slug, files)
        lease.lose()
        lease.reacquire()
        busy = false
        assertEquals(0, store.flushPending())
        assertFalse(fs.fileExists(sidecarPath(slug, "sheets/s1.json")))
        assertEquals(SidecarRead.Absent, store.read(slug))
    }

    @Test
    fun staleParkedBundle_should_BecomeConflictCopy() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val fs = SidecarFs()
        var busy = true
        val store = newStore(fs, lease) { busy }
        store.write(slug, files)
        lease.lose()
        lease.reacquire()
        busy = false
        store.flushPending()
        val copies = fs.allFilePaths().filter { it.contains(".conflict-") }
        assertTrue(copies.any { it.contains("/conflicts/s1.v2.conflict-") }, "sheet copy in $copies")
        assertEquals(1, (store as FileProjectSidecarStore).staleBundlesKept)
    }

    @Test
    fun staleParkedModel_should_BecomeConflictCopy() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val fs = SidecarFs()
        var busy = true
        val store = newStore(fs, lease) { busy }
        store.write(slug, files)
        lease.lose()
        lease.reacquire()
        busy = false
        store.flushPending()
        assertTrue(fs.allFilePaths().any { it.contains("/project-model.conflict-") })
    }
}
