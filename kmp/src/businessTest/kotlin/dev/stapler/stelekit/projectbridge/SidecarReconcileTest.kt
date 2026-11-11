// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 2.5 three-way reconcile: the pure decision, then every row of it against real repositories and a fake file system. */
class SidecarReconcileTest {
    private val P = SketchTestSupport.P
    private val slug = SketchTestSupport.project().slug
    private val model = sidecarPath(slug, "project-model.json")

    // ── the pure decision ─────────────────────────────────────────────────────────────────────

    @Test
    fun decide_should_FollowTheThreeWayTable() {
        val d = ReconcileDecision
        assertEquals(ReconcileAction.SetBase("L"), d.decide("L", "B", DiskState.Bundle("L")))
        assertEquals(ReconcileAction.SetBase("L"), d.decide("L", null, DiskState.Bundle("L")))
        assertEquals(ReconcileAction.Rebuild, d.decide("B", "B", DiskState.Bundle("P")))
        assertEquals(ReconcileAction.WriteLocal, d.decide("L", "B", DiskState.Bundle("B")))
        assertEquals(ReconcileAction.Conflict, d.decide("L", "B", DiskState.Bundle("P")))
        assertEquals(ReconcileAction.Conflict, d.decide("L", null, DiskState.Bundle("P")))
        assertEquals(ReconcileAction.WriteLocal, d.decide("L", "B", DiskState.Absent))
        assertEquals(ReconcileAction.WriteLocal, d.decide("L", null, DiskState.Absent))
        assertEquals(ReconcileAction.KeepCorrupt, d.decide("L", "B", DiskState.Corrupt))
    }

    @Test
    fun decide_should_NeverLoseAPulledSidecarSilently_Property() = runTest {
        val hashes = Arb.element("a", "b", "c")
        checkAll(hashes, Arb.element("a", "b", "c", null), hashes) { l, b, p ->
            val action = ReconcileDecision.decide(l, b, DiskState.Bundle(p))
            assertEquals(action, ReconcileDecision.decide(l, b, DiskState.Bundle(p)), "deterministic")
            if (action == ReconcileAction.WriteLocal) assertTrue(p == b && l != p, "local overwrites pulled only when pulled is the old base")
            if (action == ReconcileAction.Rebuild) assertTrue(l == b && p != l, "pulled rebuilds local only when local is untouched")
            if (p == l) assertEquals(ReconcileAction.SetBase(p), action)
        }
    }

    @Test
    fun decide_should_BeSymmetricBetweenTheTwoSidesOfAOneSidedChange_Property() = runTest {
        checkAll(Arb.element("a", "b"), Arb.element("c", "d")) { base, changed ->
            // Device X changed, Y did not: X pushes, Y pulls; both agree on the end state.
            assertEquals(ReconcileAction.WriteLocal, ReconcileDecision.decide(changed, base, DiskState.Bundle(base)))
            assertEquals(ReconcileAction.Rebuild, ReconcileDecision.decide(base, base, DiskState.Bundle(changed)))
        }
    }

    // ── against repositories ─────────────────────────────────────────────────────────────────

    private suspend fun Device.seed(bundle: dev.stapler.stelekit.model.ProjectBundle = SketchTestSupport.bundle()) {
        repos.projects.replaceProjectContents(P, bundle).getOrNull()
    }

    private suspend fun Device.editLocal(uuid: String = "edit") {
        val token = repos.projects.loadProject(P).getOrNull()!!.token
        repos.sheets.savePrimitives(listOf(SketchTestSupport.primitive(uuid, "p1-s0", 9)), token).getOrNull()
    }

    private suspend fun Device.primitiveIds() =
        repos.sheets.getPrimitives(SheetUuid("p1-s0")).first().getOrNull()!!.map { it.uuid }

    private suspend fun Device.base() = repos.projects.loadProject(P).getOrNull()!!.project.lastSyncedSidecarHash

    private suspend fun Device.outcome() = reconciler.reconcileAll().outcomes.single()

    @Test
    fun reconcile_should_PushALocalOnlyProject_ThenReportUnchanged() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        assertIs<ReconcileOutcome.Pushed>(a.outcome())
        val files = a.store.read(slug).let { assertIs<SidecarRead.Present>(it).files }
        assertEquals(SidecarCodec.bundleHash(files), a.base())
        assertIs<ReconcileOutcome.Unchanged>(a.outcome())
        assertEquals(emptyList(), a.fs.tmps())
    }

    @Test
    fun reconcile_should_PullWhenOnlyTheOtherDeviceChanged_AndRebuildInOneInvalidation() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        val b = Device()
        b.pullFrom(a)
        assertIs<ReconcileOutcome.Pulled>(b.outcome()) // unknown slug: imported
        assertEquals(a.primitiveIds(), b.primitiveIds())

        a.editLocal()
        a.outcome() // pushes
        b.pullFrom(a)
        val before = b.repos.projects.loadProject(P).getOrNull()!!.token
        assertIs<ReconcileOutcome.Pulled>(b.outcome())
        assertEquals(a.primitiveIds(), b.primitiveIds())
        assertTrue("edit" in b.primitiveIds())
        assertEquals(a.base(), b.base())
        assertTrue(b.repos.projects.loadProject(P).getOrNull()!!.token.value > before.value)
    }

    @Test
    fun reconcile_should_PushWhenOnlyTheLocalSideChanged() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        a.editLocal()
        assertIs<ReconcileOutcome.Pushed>(a.outcome())
        val decoded = SidecarCodec.decode(assertIs<SidecarRead.Present>(a.store.read(slug)).files).getOrNull()!!
        assertTrue(decoded.primitives.any { it.uuid == "edit" })
    }

    @Test
    fun reconcile_should_KeepThePulledFilesAndTheLocalState_WhenBothChanged() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        val b = Device()
        b.pullFrom(a)
        b.outcome()
        a.editLocal("from-a")
        a.outcome()
        b.editLocal("from-b")
        b.pullFrom(a)
        val pulledModel = a.fs.readFileBytes(model)!!

        val conflict = assertIs<ReconcileOutcome.Conflict>(b.outcome())

        assertContentEquals(pulledModel, b.fs.readFileBytes(model), "pulled files are never overwritten")
        val kept = assertNotNull(conflict.conflictPath)
        assertTrue(kept.contains("project-model.conflict-"))
        assertTrue(assertNotNull(b.fs.readFile(kept)).contains("\"schema\": 1"))
        assertTrue("from-a" in b.primitiveIds() && "from-b" !in b.primitiveIds())
        assertIs<ReconcileOutcome.Unchanged>(b.outcome(), "the next pass must not push the lost local state over the pulled files")
        assertContentEquals(pulledModel, b.fs.readFileBytes(model))
        assertTrue(b.reconciler.reconcileAll().needsResolution.not())
    }

    @Test
    fun reconcile_should_TreatANullBaseWithDifferentFilesAsBothChanged() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        val b = Device().also { it.seed(SketchTestSupport.bundle(primitivesPerSheet = 5)) }
        b.pullFrom(a)
        assertIs<ReconcileOutcome.Conflict>(b.outcome())
    }

    @Test
    fun reconcile_should_RestoreFromTheDatabase_WhenTheFileIsAbsentAfterATornWrite() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        a.editLocal()
        a.fs.crashAtRename = true

        assertIs<ReconcileOutcome.Failed>(a.outcome())
        assertFalse(a.fs.fileExists(model), "a crash between delete and rename leaves the destination absent")

        a.fs.revive()
        assertIs<ReconcileOutcome.Pushed>(a.outcome())
        val decoded = SidecarCodec.decode(assertIs<SidecarRead.Present>(a.store.read(slug)).files).getOrNull()!!
        assertTrue(decoded.primitives.any { it.uuid == "edit" })
    }

    @Test
    fun store_should_LeaveTheOldFileIntact_WhenTheTempWriteDiesBeforeTheSwap() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        val old = a.fs.readFileBytes(model)!!
        a.fs.dead = true
        val outcome = a.store.write(slug, SidecarCodec.encode(SketchTestSupport.bundle(primitivesPerSheet = 4)))
        a.fs.dead = false
        assertIs<SidecarWriteOutcome.Failed>(outcome)
        assertContentEquals(old, a.fs.readFileBytes(model))
    }

    @Test
    fun reconcile_should_NeverOverwriteAConflictMarkerFile_OrReadItAsAbsent() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        val marked = "{\n<<<<<<< HEAD\n\"schema\": 1\n=======\n\"schema\": 1\n>>>>>>> origin\n}\n".encodeToByteArray()
        a.fs.seed(model, marked)
        a.editLocal()

        val out = assertIs<ReconcileOutcome.CorruptKept>(a.outcome())

        assertEquals(CorruptReason.ConflictMarkers, out.reason)
        assertContentEquals(marked, a.fs.readFileBytes(model))
        assertTrue(assertNotNull(out.conflictPath).contains("project-model.conflict-"))
        assertTrue(a.reconciler.reconcileAll().needsResolution)
    }

    @Test
    fun reconcile_should_KeepAnInvalidUtf8SheetFile() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        val sheet = sidecarPath(slug, "sheets/p1-s0.json")
        val garbage = byteArrayOf(0x7b, 0xFF.toByte(), 0x7d)
        a.fs.seed(sheet, garbage)
        val out = assertIs<ReconcileOutcome.CorruptKept>(a.outcome())
        assertEquals(CorruptReason.InvalidUtf8, out.reason)
        assertContentEquals(garbage, a.fs.readFileBytes(sheet))
    }

    @Test
    fun reconcile_should_DeferTheWrite_WhileAGitSyncIsBusy_AndFlushItOnTheNextTrigger() = runBlocking<Unit> {
        val a = Device(busy = true).also { it.seed() }
        assertIs<ReconcileOutcome.Deferred>(a.outcome())
        assertFalse(a.fs.fileExists(model))

        a.busy = false
        assertIs<ReconcileOutcome.Unchanged>(a.outcome()) // the flush wrote it; P = L
        assertTrue(a.fs.fileExists(model))
        assertNotNull(a.base())
    }

    @Test
    fun reconcile_should_ReportDeviceLocal_WhereAtomicWritesAreUnsupported() = runBlocking<Unit> {
        val a = Device().also { it.seed(); it.fs.tempRenameSupported = false }
        assertIs<ReconcileOutcome.DeviceLocal>(a.outcome())
        assertEquals(emptyList(), a.fs.allFilePaths())
        assertEquals(SidecarWriteOutcome.Unavailable, a.store.write(slug, emptyMap()))
        assertNull(a.base())
    }

    @Test
    fun aPull_should_FailAStaleEditorSave() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        val b = Device()
        b.pullFrom(a)
        b.outcome()
        val stale = b.repos.projects.loadProject(P).getOrNull()!!.token
        a.editLocal()
        a.outcome()
        b.pullFrom(a)
        b.outcome()
        val err = b.repos.sheets.savePrimitives(listOf(SketchTestSupport.primitive("late", "p1-s0")), stale).leftOrNull()
        assertTrue(err is DomainError.DatabaseError.WriteFailed && err.message == "stale-editor")
    }

    @Test
    fun reconcileAll_should_ImportASlugThatHasNoLocalProject_AndKeepACorruptOneUntouched() = runBlocking<Unit> {
        val a = Device().also { it.seed() }
        a.outcome()
        val b = Device()
        b.pullFrom(a)
        assertEquals(setOf(slug), b.store.slugsOnDisk().toSet())
        assertIs<ReconcileOutcome.Pulled>(b.outcome())
        assertEquals(SidecarCodec.bundleHash(assertIs<SidecarRead.Present>(b.store.read(slug)).files), b.base())

        val c = Device()
        c.fs.seed(model, "{\"schema\":2}".encodeToByteArray())
        assertEquals(CorruptReason.NewerSchema, assertIs<ReconcileOutcome.CorruptKept>(c.outcome()).reason)
    }
}
