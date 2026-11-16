// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.ReconcileOutcome
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionCrashPoint
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionStep
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 1.4.2: the converter dies (or the git sync turns busy) at every step boundary and in the middle of a file
 * write. The project must always read `Present` (never `Corrupt`), a retry must finish, and the finished state must equal
 * an uninterrupted conversion.
 */
class SheetConversionCrashInjectionTest {
    private val f = ConversionFixtures
    private val s1 = SheetUuid(ConversionFixtures.S1)

    private class Crash : RuntimeException("process died")

    private suspend fun reference(): Pair<Map<String, String>, Map<String, Long>> {
        val d = f.device()
        assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
        return f.disk(d) to f.schemas(d)
    }

    private fun crashAfter(step: ConversionStep) = ConversionCrashPoint { if (it == step) throw Crash() }

    /** "Reopen": a new process over the same files and database. */
    private suspend fun assertReadsPresent(d: Device) {
        d.fs.revive()
        assertIs<SidecarRead.Present>(d.store.read(f.slug))
    }

    @Test
    fun crashAfterEveryStep_should_LeaveARecoverableProject_AndRetryShouldFinishIdentically() = runBlocking<Unit> {
        val expected = reference()
        for (step in ConversionStep.entries) {
            val d = f.device()
            assertFailsWith<Crash>("$step") { f.converter(d, crashPoint = crashAfter(step)).convert(SketchTestSupport.P, s1) }

            assertReadsPresent(d)
            val v2Referenced = step >= ConversionStep.ModelFlipped
            assertEquals(v2Referenced, d.store.read(f.slug).let { (it as SidecarRead.Present).files.containsKey("sheets/${f.S1}.v2.json") }, "$step")

            val retry = f.converter(d).convert(SketchTestSupport.P, s1)
            assertTrue(retry is ConversionOutcome.Converted || retry == ConversionOutcome.AlreadyConverted, "$step: $retry")
            assertEquals(expected, f.disk(d) to f.schemas(d), "$step: final state differs from an uninterrupted conversion")
        }
    }

    @Test
    fun crashBeforeTheDatabaseRowMoves_should_BeReconciledFromTheFlippedModel() = runBlocking<Unit> {
        val d = f.device()
        assertFailsWith<Crash> { f.converter(d, crashPoint = crashAfter(ConversionStep.V1Removed)).convert(SketchTestSupport.P, s1) }
        assertEquals(1L, f.schemas(d).getValue(f.S1), "the row is still v1: the process died before the database step")

        val project = d.repos.projects.loadProject(SketchTestSupport.P).getOrNull()!!.project
        val outcome = d.reconciler.reconcile(project)

        assertEquals(ReconcileOutcome.Pulled(f.slug), outcome)
        assertEquals(2L, f.schemas(d).getValue(f.S1))
    }

    @Test
    fun crashAfterTheFlipButBeforeTheSweep_should_LeaveV1FileThatTheNextWriteRemoves() = runBlocking<Unit> {
        val d = f.device()
        assertFailsWith<Crash> { f.converter(d, crashPoint = crashAfter(ConversionStep.ModelFlipped)).convert(SketchTestSupport.P, s1) }
        d.fs.revive()
        assertTrue(d.fs.fileExists(f.file("sheets/${f.S1}.json")), "the leftover v1 file is still there")

        val files = (d.store.read(f.slug) as SidecarRead.Present).files
        d.store.write(f.slug, files)

        assertFalse(d.fs.fileExists(f.file("sheets/${f.S1}.json")))
    }

    @Test
    fun aTornTempFile_should_NeverBeRenamedIntoPlace() = runBlocking<Unit> {
        val expected = reference()
        for (arm in listOf(Torn.Backup, Torn.Stage, Torn.Flip)) {
            val d = f.device()
            if (arm == Torn.Backup) d.fs.tearNextWriteEndingWith = ".tmp"
            val point = ConversionCrashPoint {
                if (arm == Torn.Stage && it == ConversionStep.BackupWritten) d.fs.tearNextWriteEndingWith = ".tmp"
                if (arm == Torn.Flip && it == ConversionStep.V2Verified) d.fs.tearNextWriteEndingWith = ".tmp"
            }
            // The atomic writer catches the dying write and reports it, so the converter stops with Failed(Write).
            assertIs<ConversionOutcome.Failed>(f.converter(d, crashPoint = point).convert(SketchTestSupport.P, s1), "$arm")

            assertReadsPresent(d)
            assertTrue(d.fs.allFilePaths().filter { it.endsWith(".json") }.all { looksComplete(d.fs.readFileBytes(it)!!) }, "$arm: a torn file was renamed into place")
            assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
            assertEquals(expected, f.disk(d) to f.schemas(d), "$arm")
        }
    }

    private enum class Torn { Backup, Stage, Flip }

    private fun looksComplete(bytes: ByteArray): Boolean = bytes.decodeToString().trimEnd().let { it.endsWith("}") }

    @Test
    fun gitSyncBusy_should_DeferWithoutQueueing_AndALaterRetryShouldFinishIdentically() = runBlocking<Unit> {
        val expected = reference()
        for (busyAfter in listOf(ConversionStep.BackupWritten, ConversionStep.V2Verified)) {
            val d = f.device()
            val point = ConversionCrashPoint { if (it == busyAfter) d.busy = true }

            val first = f.converter(d, crashPoint = point).convert(SketchTestSupport.P, s1)

            assertEquals(ConversionOutcome.Deferred, first, "$busyAfter")
            assertEquals(mapOf(f.S1 to 1L, f.S2 to 1L), f.schemas(d), "$busyAfter: nothing flipped")
            assertIs<SidecarRead.Present>(d.store.read(f.slug))
            d.busy = false
            assertEquals(0, d.store.flushPending(), "$busyAfter: a deferred conversion parks nothing to replay")
            assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
            assertEquals(expected, f.disk(d) to f.schemas(d), "$busyAfter")
        }
    }

    @Test
    fun aV2FileThatChangedAfterStaging_should_FailVerificationAndFlipNothing() = runBlocking<Unit> {
        val d = f.device()
        val point = ConversionCrashPoint {
            if (it == ConversionStep.V2Staged) d.fs.seed(f.file("sheets/${f.S1}.v2.json"), "{\"schema\":2,\"sheetUuid\":\"x\"}".encodeToByteArray())
        }

        val outcome = f.converter(d, crashPoint = point).convert(SketchTestSupport.P, s1)

        assertIs<ConversionOutcome.Failed>(outcome)
        assertEquals(1L, f.schemas(d).getValue(f.S1))
        assertIs<SidecarRead.Present>(d.store.read(f.slug))
    }
}
