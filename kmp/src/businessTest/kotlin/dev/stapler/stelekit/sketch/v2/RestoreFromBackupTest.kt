// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreCrashPoint
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreStep
import dev.stapler.stelekit.sketch.v2.runtime.convert.SketchBackupRestore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 6.2.6: `SketchBackupRestore` puts a converted sheet back to the original v1 bytes, keeps the new-editor version,
 * leaves a `Present` project after a crash at any step, and refuses (changing nothing) when the backup is missing.
 */
class RestoreFromBackupTest {
    private val f = ConversionFixtures
    private val s1 = SheetUuid(ConversionFixtures.S1)

    private class Crash : RuntimeException("process died")

    private fun restorer(d: Device, crashPoint: RestoreCrashPoint = RestoreCrashPoint.None) =
        SketchBackupRestore(d.repos, d.store, SidecarSlugLock(), crashPoint = crashPoint)

    private fun crashAfter(step: RestoreStep) = RestoreCrashPoint { if (it == step) throw Crash() }

    private fun v1Path() = f.file("sheets/${f.S1}.json")
    private fun v2Path() = f.file("sheets/${f.S1}.v2.json")
    private fun backupPath() = f.file("backup/${f.S1}.v1.json")
    private fun keptPath() = f.file("backup/${f.S1}.v2-before-restore.json")

    /** A device holding the original project, with `s1` converted. Returns the v1 sheet bytes as they were before. */
    private suspend fun converted(): Pair<Device, ByteArray> {
        val d = f.device()
        val original = d.fs.readFileBytes(v1Path())!!
        assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
        return d to original
    }

    private suspend fun reference(): Triple<Map<String, String>, Map<String, Long>, ByteArray> {
        val (d, original) = converted()
        assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))
        return Triple(f.disk(d), f.schemas(d), original)
    }

    @Test
    fun restore_should_ReturnTheOriginalV1Bytes_KeepTheV2Copy_AndFlipTheRow() = runBlocking<Unit> {
        val (d, original) = converted()
        val v2 = d.fs.readFileBytes(v2Path())!!

        val outcome = restorer(d).restore(f.slug, s1)

        assertEquals(RestoreOutcome.Restored(s1, "backup/${f.S1}.v2-before-restore.json"), outcome)
        assertContentEquals(original, d.fs.readFileBytes(v1Path()), "sheets/s1.json is the original v1 bytes")
        assertFalse(d.fs.fileExists(v2Path()), "the v2 file is removed")
        assertContentEquals(v2, d.fs.readFileBytes(keptPath()), "the new-editor version is kept")
        assertContentEquals(original, d.fs.readFileBytes(backupPath()), "the original backup is untouched")
        assertEquals(mapOf(f.S1 to 1L, f.S2 to 1L), f.schemas(d))
        val read = d.store.read(f.slug)
        assertIs<SidecarRead.Present>(read)
        assertTrue(read.files.containsKey("sheets/${f.S1}.json") && !read.files.containsKey("sheets/${f.S1}.v2.json"))
    }

    @Test
    fun restoreTwice_should_ChangeNothingTheSecondTime() = runBlocking<Unit> {
        val (d, _) = converted()
        assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))
        val after = f.disk(d) to f.schemas(d)

        assertEquals(RestoreOutcome.NotConverted, restorer(d).restore(f.slug, s1))

        assertEquals(after, f.disk(d) to f.schemas(d))
    }

    @Test
    fun aSheetThatWasNeverConverted_should_BeRefusedWithNoWrites() = runBlocking<Unit> {
        val d = f.device()
        val before = f.disk(d) to f.schemas(d)

        assertEquals(RestoreOutcome.NotConverted, restorer(d).restore(f.slug, s1))

        assertEquals(before, f.disk(d) to f.schemas(d))
    }

    @Test
    fun aMissingBackup_should_RefuseAndChangeNothing() = runBlocking<Unit> {
        val (d, _) = converted()
        d.fs.deleteFile(backupPath())
        val before = f.disk(d) to f.schemas(d)

        assertEquals(RestoreOutcome.BackupMissing, restorer(d).restore(f.slug, s1))

        assertEquals(before, f.disk(d) to f.schemas(d))
        assertTrue(d.fs.fileExists(v2Path()), "the v2 file is still the referenced one")
    }

    @Test
    fun aBackupThatIsNotAV1Sheet_should_RefuseAndChangeNothing() = runBlocking<Unit> {
        val (d, _) = converted()
        d.fs.seed(backupPath(), "not a sheet".encodeToByteArray())
        val before = f.disk(d) to f.schemas(d)

        assertEquals(RestoreOutcome.BackupMissing, restorer(d).restore(f.slug, s1))

        assertEquals(before, f.disk(d) to f.schemas(d))
    }

    @Test
    fun crashAfterEveryStep_should_LeaveAPresentProject_AndRetryShouldFinishIdentically() = runBlocking<Unit> {
        val expected = reference()
        for (step in RestoreStep.entries) {
            val (d, _) = converted()
            assertFailsWith<Crash>("$step") { restorer(d, crashAfter(step)).restore(f.slug, s1) }

            d.fs.revive()
            assertIs<SidecarRead.Present>(d.store.read(f.slug), "$step: the project must still read Present")

            val retry = restorer(d).restore(f.slug, s1)
            assertTrue(retry is RestoreOutcome.Restored || retry == RestoreOutcome.NotConverted, "$step: $retry")
            assertEquals(expected.first to expected.second, f.disk(d) to f.schemas(d), "$step: final state differs from an uninterrupted restore")
            assertContentEquals(expected.third, d.fs.readFileBytes(v1Path()), "$step: original bytes")
        }
    }

    @Test
    fun aTornTempFile_should_NeverBeRenamedIntoPlace() = runBlocking<Unit> {
        val expected = reference()
        val (d, _) = converted()
        d.fs.tearNextWriteEndingWith = ".tmp"

        val first = restorer(d).restore(f.slug, s1)

        assertIs<RestoreOutcome.Failed>(first)
        assertEquals(RestoreFailure.Write, first.reason)
        d.fs.revive()
        assertIs<SidecarRead.Present>(d.store.read(f.slug))
        assertTrue(d.fs.allFilePaths().filter { it.endsWith(".json") }.all { d.fs.readFileBytes(it)!!.decodeToString().trimEnd().endsWith("}") })
        assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))
        assertEquals(expected.first to expected.second, f.disk(d) to f.schemas(d))
    }

    @Test
    fun gitSyncBusy_should_DeferWithoutQueueing_AndALaterRetryShouldFinishIdentically() = runBlocking<Unit> {
        val expected = reference()
        val (d, _) = converted()
        d.busy = true

        assertEquals(RestoreOutcome.Deferred, restorer(d).restore(f.slug, s1))

        assertIs<SidecarRead.Present>(d.store.read(f.slug))
        d.busy = false
        assertEquals(0, d.store.flushPending(), "a deferred restore parks nothing to replay")
        assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))
        assertEquals(expected.first to expected.second, f.disk(d) to f.schemas(d))
    }

    @Test
    fun aSecondRestoreAfterANewConversion_should_ReplaceTheKeptV2Copy() = runBlocking<Unit> {
        val (d, _) = converted()
        assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))
        assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
        val secondV2 = d.fs.readFileBytes(v2Path())!!

        assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))

        assertContentEquals(secondV2, d.fs.readFileBytes(keptPath()))
    }

    @Test
    fun deleteBackups_should_EmptyTheBackupDirectory_AndRetentionSizeShouldShowIt() = runBlocking<Unit> {
        val (d, _) = converted()
        assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))
        val size = d.store.retentionSize(f.slug)
        assertEquals(2, size.backupFiles)
        assertTrue(size.backupBytes > 0)

        val deleted = d.store.deleteBackups(f.slug).getOrNull()

        assertEquals(2, deleted)
        assertEquals(0, d.store.retentionSize(f.slug).backupFiles)
        assertFalse(d.fs.fileExists(backupPath()))
        assertIs<SidecarRead.Present>(d.store.read(f.slug))
        assertEquals(RestoreOutcome.NotConverted, restorer(d).restore(f.slug, s1))
    }
}
