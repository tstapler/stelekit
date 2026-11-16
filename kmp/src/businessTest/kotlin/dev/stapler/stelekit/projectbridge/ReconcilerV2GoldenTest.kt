// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.1.3c/e: a v2 sheet survives every project-level write, restore and pull unchanged; corrupt v2 is never repaired. */
class ReconcilerV2GoldenTest {
    private val slug = SketchTestSupport.project().slug
    private val p = SketchTestSupport.P
    private val v2 = SheetUuid(V2Fixtures.V2_SHEET)
    private val v2Rel = V2Fixtures.v2Path

    /** A device whose disk and database both hold the mixed project, already reconciled once. */
    private suspend fun settledDevice(): Device {
        val d = Device()
        val bundle = V2Fixtures.mixedBundle()
        d.store.write(slug, SidecarCodec.encode(bundle))
        d.repos.projects.replaceProjectContents(p, bundle).getOrNull()
        assertIs<ReconcileOutcome.Unchanged>(d.reconciler.reconcileAll().outcomes.single())
        return d
    }

    private fun Device.v2Bytes() = fs.readFileBytes(sidecarPath(slug, v2Rel))!!

    private fun Device.modelV2Sha() = SidecarCodec.v2SheetShas(fs.readFileBytes(sidecarPath(slug, SidecarCodec.MODEL_PATH))!!)!!.getValue(v2Rel)

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun Device.editDimension() {
        val token = repos.projects.loadProject(p).getOrNull()!!.token
        val dim = repos.dimensions.getDimensions(p).first().getOrNull()!!.single()
        repos.dimensions.saveDimension(dim.copy(locked = !dim.locked), token).getOrNull()
    }

    @Test
    fun reconcile_should_BeUnchangedWithZeroWrites_WhenNothingChangedAroundAV2Sheet() = runBlocking<Unit> {
        val d = settledDevice()
        val writes = d.fs.writeCount

        assertIs<ReconcileOutcome.Unchanged>(d.reconciler.reconcileAll().outcomes.single())

        assertEquals(writes, d.fs.writeCount)
        assertContentEquals(V2Fixtures.bytes, d.v2Bytes())
    }

    @Test
    fun aDimensionEdit_should_PushTheModelAndLeaveTheV2BytesAndShaAlone() = runBlocking<Unit> {
        val d = settledDevice()
        d.editDimension()

        assertIs<ReconcileOutcome.Pushed>(d.reconciler.reconcileAll().outcomes.single())

        assertContentEquals(V2Fixtures.bytes, d.v2Bytes())
        assertEquals(ContentHasher.sha256(V2Fixtures.bytes), d.modelV2Sha())
        assertIs<ReconcileOutcome.Unchanged>(d.reconciler.reconcileAll().outcomes.single())
    }

    @Test
    fun aDbRestoreIntoAnEmptyDb_should_StoreTheV2SheetRowWithNoPrimitivesAndNotRewriteTheFile() = runBlocking<Unit> {
        val a = settledDevice()
        val b = Device()
        b.pullFrom(a)

        assertIs<ReconcileOutcome.Pulled>(b.reconciler.reconcileAll().outcomes.single())

        val sheets = b.repos.sheets.getSheets(p).first().getOrNull()!!.items.associateBy { it.uuid }
        assertEquals(2L, sheets.getValue(v2).schemaVersion)
        assertEquals(1L, sheets.getValue(SheetUuid("p1-s0")).schemaVersion)
        assertEquals(emptyList(), b.repos.sheets.getPrimitives(v2).first().getOrNull())
        assertEquals(0L, b.fs.writeCount, "the disk bundle was not rewritten")
        assertContentEquals(V2Fixtures.bytes, b.v2Bytes())
        assertIs<ReconcileOutcome.Unchanged>(b.reconciler.reconcileAll().outcomes.single())
    }

    @Test
    fun aPullWhereOnlyTheV1SheetChanged_should_KeepTheV2BytesAndRow() = runBlocking<Unit> {
        val a = settledDevice()
        val b = Device().also { it.pullFrom(a) }
        b.reconciler.reconcileAll()

        val token = a.repos.projects.loadProject(p).getOrNull()!!.token
        a.repos.sheets.savePrimitives(listOf(SketchTestSupport.primitive("edit", "p1-s0", 9)), token).getOrNull()
        assertIs<ReconcileOutcome.Pushed>(a.reconciler.reconcileAll().outcomes.single())
        b.pullFrom(a)

        assertIs<ReconcileOutcome.Pulled>(b.reconciler.reconcileAll().outcomes.single())
        assertTrue(b.repos.sheets.getPrimitives(SheetUuid("p1-s0")).first().getOrNull()!!.any { it.uuid == "edit" })
        assertContentEquals(V2Fixtures.bytes, b.v2Bytes())
        assertEquals(2L, b.repos.sheets.getSheets(p).first().getOrNull()!!.items.single { it.uuid == v2 }.schemaVersion)
        assertEquals(ContentHasher.sha256(V2Fixtures.bytes), b.modelV2Sha())
    }

    @Test
    fun aV2FileWhoseShaDiffersFromTheModel_should_BeKeepCorruptWithNoWrite() = runBlocking<Unit> {
        val d = settledDevice()
        d.editDimension() // a push would be due: the corrupt file must still stop it
        val tampered = V2Fixtures.bytes + " ".encodeToByteArray()
        d.fs.seed(sidecarPath(slug, v2Rel), tampered)
        val writes = d.fs.writeCount

        val outcome = d.reconciler.reconcileAll().outcomes.single()

        assertEquals(CorruptReason.Truncated, assertIs<ReconcileOutcome.CorruptKept>(outcome).reason)
        assertEquals(writes + 1, d.fs.writeCount, "only the kept local model copy was written")
        assertContentEquals(tampered, d.v2Bytes(), "the corrupt v2 file is not repaired")
    }

    @Test
    fun v2SheetMetadataEdit_should_RoundTripAsUnchanged_WhenTheSaverMirroredItInFileModelAndRow() = runBlocking<Unit> {
        val d = settledDevice()
        val layers = listOf("Base", "Walls")
        // The saver's locked operation: DB row, file and model mirror together, then the base moves with the disk.
        val edited = V2Fixtures.mixedBundle().let { b -> b.copy(sheets = b.sheets.map { if (it.uuid == v2) it.copy(layers = layers) else it }) }
        val files = SidecarCodec.encode(edited)
        d.store.write(slug, files)
        val token = d.repos.projects.loadProject(p).getOrNull()!!.token
        d.repos.sheets.saveSheet(edited.sheets.single { it.uuid == v2 }, token).getOrNull()
        val fresh = d.repos.projects.loadProject(p).getOrNull()!!.project
        d.repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = SidecarCodec.bundleHash(files), baseRevision = fresh.modelRevision)).getOrNull()
        val writes = d.fs.writeCount

        assertIs<ReconcileOutcome.Unchanged>(d.reconciler.reconcileAll().outcomes.single())

        assertEquals(writes, d.fs.writeCount)
        val model = d.fs.readFileBytes(sidecarPath(slug, SidecarCodec.MODEL_PATH))!!
        assertContentEquals(files.getValue(SidecarCodec.MODEL_PATH), model)
        assertTrue("\"Walls\"" in model.decodeToString())
    }

    @Test
    fun aV2SheetWithNoFileOnThisDevice_should_FailTheReconcileRatherThanInventBytes() = runBlocking<Unit> {
        val d = Device()
        d.repos.projects.replaceProjectContents(p, V2Fixtures.mixedBundle()).getOrNull()

        assertIs<ReconcileOutcome.Failed>(d.reconciler.reconcileAll().outcomes.single())
        assertEquals(emptyList(), d.fs.allFilePaths().filter { it.contains("/sheets/") })
    }
}
