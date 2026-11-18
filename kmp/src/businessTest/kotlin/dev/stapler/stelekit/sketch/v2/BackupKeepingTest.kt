// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.projectbridge.SidecarFs
import dev.stapler.stelekit.projectbridge.newStore
import dev.stapler.stelekit.projectbridge.sidecarPath
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review finding B6: backups are never overwritten, a kept v2 copy is never replaced or deleted by "Delete backups", and both need the lease. */
class BackupKeepingTest {
    private val slug = "proj-0a1b2c"
    private val first = "first".encodeToByteArray()
    private val second = "second".encodeToByteArray()

    private fun path(rel: String) = sidecarPath(slug, rel)

    @Test
    fun aSecondBackupWithDifferentBytes_should_BeKeptBesideTheFirst_AndBeTheOneRestoreReads() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.writeBackup(slug, "s1", first)
        store.writeBackup(slug, "s1", second)

        assertContentEquals(first, fs.readFileBytes(path("backup/s1.v1.json")), "the original is never overwritten")
        assertContentEquals(second, fs.readFileBytes(path("backup/s1.v1.1.json")))
        assertContentEquals(second, store.readBackup(slug, "s1"), "a restore reads the newest v1")
    }

    @Test
    fun aBackupWithIdenticalBytes_should_ReuseTheFile() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.writeBackup(slug, "s1", first)
        store.writeBackup(slug, "s1", first)

        assertEquals(1, store.retentionSize(slug).backupFiles)
    }

    @Test
    fun aSecondV2BeforeRestore_should_NeverReplaceTheFirst() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.writeV2BeforeRestore(slug, "s1", first)
        store.writeV2BeforeRestore(slug, "s1", second)

        assertContentEquals(first, fs.readFileBytes(path("backup/s1.v2-before-restore.json")))
        assertContentEquals(second, fs.readFileBytes(path("backup/s1.v2-before-restore.1.json")))
    }

    @Test
    fun deleteBackups_should_KeepTheKeptV2Copies() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.writeBackup(slug, "s1", first)
        store.writeBackup(slug, "s1", second)
        store.writeV2BeforeRestore(slug, "s1", first)

        assertEquals(2, store.deleteBackups(slug).getOrNull())

        assertFalse(fs.fileExists(path("backup/s1.v1.json")))
        assertTrue(fs.fileExists(path("backup/s1.v2-before-restore.json")), "the only copy of new-editor work stays")
    }

    @Test
    fun v2BeforeRestoreAndDeleteBackups_should_BeRefusedWithoutTheLease() = runBlocking<Unit> {
        val lease = TestSketchWriterLease()
        val fs = SidecarFs()
        val store = newStore(fs, lease = lease)
        store.writeBackup(slug, "s1", first)
        lease.lose()

        assertIs<DomainError.FileSystemError.WriterLeaseNotHeld>(store.writeV2BeforeRestore(slug, "s1", first).leftOrNull())
        assertIs<DomainError.FileSystemError.WriterLeaseNotHeld>(store.deleteBackups(slug).leftOrNull())
        assertTrue(fs.fileExists(path("backup/s1.v1.json")))
    }
}
