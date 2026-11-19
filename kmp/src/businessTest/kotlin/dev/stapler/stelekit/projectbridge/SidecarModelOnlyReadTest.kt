// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.platform.V2StorageStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Counts what the sidecar store reads, so a test can assert "one model open, no listing, no sheet file". */
internal class CountingSidecarFs : SidecarFs() {
    val fullReads = mutableListOf<String>()
    val prefixReads = mutableListOf<Pair<String, Int>>()
    var listCalls = 0
    var status: V2StorageStatus = V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false)

    override val storageStatus: V2StorageStatus get() = status

    override fun readFileBytes(path: String): ByteArray? {
        fullReads += path
        return super.readFileBytes(path)
    }

    override fun readFilePrefix(path: String, maxBytes: Int): ByteArray? {
        prefixReads += path to maxBytes
        return super.readFileBytes(path)?.let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
    }

    override fun listFiles(path: String): List<String> {
        listCalls++
        return super.listFiles(path)
    }
}

/** Task 1.4.3h: `readModel` opens ONE file; `isAvailable` follows the platform. */
class SidecarModelOnlyReadTest {
    private val slug = "proj-0a1b2c"

    private fun model(sheets: Int): ByteArray {
        val refs = (0 until sheets).joinToString(",") { "{\"uuid\":\"s$it\"}" }
        return "{\"schema\":1,\"sheets\":[$refs]}".encodeToByteArray()
    }

    @Test
    fun readModel_should_OpenExactlyOneFile_ForAProjectWithFiveHundredSheets() = runBlocking<Unit> {
        val fs = CountingSidecarFs()
        fs.seed(sidecarPath(slug, "project-model.json"), model(500))
        val read = assertIs<SidecarRead.Present>(newStore(fs).readModel(slug))
        assertEquals(listOf("project-model.json"), read.files.keys.toList())
        assertEquals(listOf(sidecarPath(slug, "project-model.json")), fs.fullReads)
        assertEquals(0, fs.listCalls)
        assertTrue(fs.prefixReads.isEmpty())
    }

    @Test
    fun readModel_should_ReportAbsent_ForAMissingModel_AndCorrupt_ForAnUnclassifiableOne() = runBlocking<Unit> {
        val fs = CountingSidecarFs()
        val store = newStore(fs)
        assertEquals(SidecarRead.Absent, store.readModel(slug))
        fs.seed(sidecarPath(slug, "project-model.json"), "not json".encodeToByteArray())
        assertIs<SidecarRead.Corrupt>(store.readModel(slug))
    }

    @Test
    fun readModel_should_ReturnUnavailable_When_TheFileSystemCannotRenameTemps() = runBlocking<Unit> {
        val fs = CountingSidecarFs().also { it.tempRenameSupported = false }
        fs.seed(sidecarPath(slug, "project-model.json"), model(1))
        assertEquals(SidecarRead.Unavailable, newStore(fs).readModel(slug))
        assertFalse(newStore(fs).isAvailable())
    }

    @Test
    fun isAvailable_should_FollowTheStorageStatus() {
        val fs = CountingSidecarFs()
        val store = newStore(fs)
        assertTrue(store.isAvailable())
        assertEquals(V2Storage.Available, store.v2Storage())
        fs.status = V2StorageStatus.Checking
        assertFalse(store.isAvailable())
        assertEquals(V2Storage.Checking, store.v2Storage())
        fs.status = V2StorageStatus.Unavailable(StorageReason.NoOpfs)
        assertEquals(V2Storage.Browser(StorageReason.NoOpfs), store.v2Storage())
    }

    @Test
    fun aNullStore_should_NeverBeAvailable() {
        val none: ProjectSidecarStore? = null
        assertFalse(none.v2StorageAvailable())
        assertEquals(V2Storage.NoStore, none.v2Storage())
    }

    @Test
    fun anAtomicWriteIncapableDisk_should_BePlatformUnsupported() {
        val fs = CountingSidecarFs().also { it.tempRenameSupported = false }
        assertEquals(V2Storage.PlatformUnsupported, newStore(fs).v2Storage())
    }

    @Test
    fun readSheetPrefix_should_ReadOnlySheetPaths_AndNeverTheWholeFile() = runBlocking<Unit> {
        val fs = CountingSidecarFs()
        fs.seed(sidecarPath(slug, "sheets/s0.v2.json"), ByteArray(1000) { 'a'.code.toByte() })
        val store = newStore(fs)
        assertEquals(256, store.readSheetPrefix(slug, "sheets/s0.v2.json", 256)?.size)
        assertNull(store.readSheetPrefix(slug, "../x.json", 256))
        assertNull(store.readSheetPrefix(slug, "project-model.json", 256))
        assertTrue(fs.fullReads.isEmpty())
    }
}
