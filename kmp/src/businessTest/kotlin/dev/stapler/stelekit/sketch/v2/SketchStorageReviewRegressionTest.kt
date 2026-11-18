// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import dev.stapler.stelekit.projectbridge.ReconcileOutcome
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.V2Fixtures
import dev.stapler.stelekit.projectbridge.sidecarPath
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreCrashPoint
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreStep
import dev.stapler.stelekit.sketch.v2.runtime.convert.SketchBackupRestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the persistence review of PR 685 (findings A1-A4): the upgrade backup is the scene the user saw,
 * a sheet that exists only in the database upgrades, a restore crash never strands the project's reconcile, and a parked
 * reconcile bundle is never replayed over a model that gained a sheet.
 */
class SketchStorageReviewRegressionTest {
    private val f = ConversionFixtures
    private val s1 = SheetUuid(ConversionFixtures.S1)

    private class Crash : RuntimeException("process died")

    private fun restorer(d: Device, crashPoint: RestoreCrashPoint = RestoreCrashPoint.None) =
        SketchBackupRestore(d.repos, d.store, SidecarSlugLock(), crashPoint = crashPoint)

    private suspend fun primitiveIds(d: Device, sheet: SheetUuid): Set<String> =
        d.repos.sheets.getPrimitives(sheet).first().getOrNull()!!.map { it.uuid }.toSet()

    private suspend fun token(d: Device) = d.repos.projects.loadProject(SketchTestSupport.P).getOrNull()!!.token

    @Test
    fun a1_restoreAfterUpgrade_should_ReturnTheSceneTheUserSawBeforeUpgrading_NotTheOlderDiskFile() = runBlocking<Unit> {
        val d = f.device()
        // Classic-editor edit: reaches the database only; no reconcile has pushed it to the sidecar yet.
        val extra = SketchPrimitive("s1-extra", s1, "line", 5, 5, 700, 700, layer = "walls", z = 99)
        d.repos.sheets.savePrimitives(listOf(extra), token(d)).getOrNull()!!
        val seen = primitiveIds(d, s1)
        assertTrue("s1-extra" in seen)

        assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
        assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))

        assertEquals(seen, primitiveIds(d, s1), "Restore original returns the scene as it was when Upgrade was tapped")
    }

    @Test
    fun a2_aSheetThatIsInTheDatabaseButNotOnDisk_should_Upgrade() = runBlocking<Unit> {
        val d = f.device()
        val s3 = SheetUuid("p1-s3")
        d.repos.sheets.saveSheet(SketchTestSupport.sheet("p1-s3", position = 2), token(d)).getOrNull()!!
        d.repos.sheets.savePrimitives(
            listOf(SketchPrimitive("s3-l0", s3, "line", 0, 0, 160, 480, layer = "walls", z = 0)),
            token(d),
        ).getOrNull()!!

        val outcome = f.converter(d).convert(SketchTestSupport.P, s3)

        assertIs<ConversionOutcome.Converted>(outcome)
        assertEquals(2L, f.schemas(d).getValue("p1-s3"))
        val files = assertIs<SidecarRead.Present>(d.store.read(f.slug)).files
        assertTrue("sheets/p1-s3.v2.json" in files)
    }

    @Test
    fun a3_aRestoreCrashAtAnyStep_should_NeverStrandTheReconcile_OrLeaveAV2RowWithoutItsFile() = runBlocking<Unit> {
        for (step in RestoreStep.entries) {
            val d = f.device()
            assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
            assertFailsWith<Crash>("$step") {
                restorer(d, RestoreCrashPoint { if (it == step) throw Crash() }).restore(f.slug, s1)
            }
            d.fs.revive()

            val project = d.repos.projects.loadProject(SketchTestSupport.P).getOrNull()!!.project
            val outcome = d.reconciler.reconcile(project)

            assertTrue(outcome !is ReconcileOutcome.Failed, "$step: reconcile must not fail after the crash: $outcome")
            val files = assertIs<SidecarRead.Present>(d.store.read(f.slug)).files
            for ((uuid, schema) in f.schemas(d)) {
                if (schema == 2L) assertTrue("sheets/$uuid.v2.json" in files, "$step: row $uuid is v2 but its file is gone")
            }
        }
    }

    @Test
    fun a3_afterACrashAtAnyStep_aRetryShouldFinishWithTheSameFinalState() = runBlocking<Unit> {
        val expected = f.device().let { d ->
            assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
            assertIs<RestoreOutcome.Restored>(restorer(d).restore(f.slug, s1))
            f.disk(d) to f.schemas(d)
        }
        for (step in RestoreStep.entries) {
            val d = f.device()
            assertIs<ConversionOutcome.Converted>(f.converter(d).convert(SketchTestSupport.P, s1))
            assertFailsWith<Crash>("$step") { restorer(d, RestoreCrashPoint { if (it == step) throw Crash() }).restore(f.slug, s1) }
            d.fs.revive()

            restorer(d).restore(f.slug, s1)

            assertEquals(expected, f.disk(d) to f.schemas(d), "$step")
        }
    }

    @Test
    fun a4_aParkedReconcileBundle_should_NotBeReplayedOverAModelThatGainedASheet() = runBlocking<Unit> {
        val slug = SketchTestSupport.project().slug
        val p = SketchTestSupport.P
        val v1Only = SketchTestSupport.bundle(sheets = 1)
        val d = Device()
        d.store.write(slug, SidecarCodec.encode(v1Only))
        d.repos.projects.replaceProjectContents(p, v1Only).getOrNull()
        assertIs<ReconcileOutcome.Unchanged>(d.reconciler.reconcileAll().outcomes.single())

        val dim = d.repos.dimensions.getDimensions(p).first().getOrNull()!!.single()
        d.repos.dimensions.saveDimension(dim.copy(locked = !dim.locked), token(d)).getOrNull()
        d.busy = true
        assertIs<ReconcileOutcome.Deferred>(d.reconciler.reconcileAll().outcomes.single(), "the edit is parked")
        d.busy = false

        // "Import into a new sketch": a v2 sheet is added to the model, its file written, its row saved.
        val mixed = V2Fixtures.mixedBundle()
        assertEquals(dev.stapler.stelekit.projectbridge.SidecarWriteOutcome.Written, d.store.writeNow(slug, SidecarCodec.encode(mixed)))
        d.repos.projects.replaceProjectContents(p, mixed).getOrNull()

        d.store.flushPending()

        val model = d.fs.readFileBytes(sidecarPath(slug, SidecarCodec.MODEL_PATH))!!.decodeToString()
        assertTrue(V2Fixtures.v2Path in model, "the parked bundle dropped the new sheet from the model")
        assertEquals(1, (d.store as FileProjectSidecarStore).staleOpaqueDropped)
        assertNotEquals(
            ReconcileOutcome.Failed::class,
            d.reconciler.reconcile(d.repos.projects.loadProject(p).getOrNull()!!.project)::class,
        )
    }
}
