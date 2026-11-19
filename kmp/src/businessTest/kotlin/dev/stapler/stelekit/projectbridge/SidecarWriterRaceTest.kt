// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 1.1.3e: the slug lock and the opaque contract keep a reconcile, a saver and a converter from undoing each
 * other, including a parked write replayed after a conversion (pre-mortem P1 #2).
 */
class SidecarWriterRaceTest {
    private val slug = SketchTestSupport.project().slug
    private val p = SketchTestSupport.P
    private val v2 = SheetUuid(V2Fixtures.V2_SHEET)
    private val b0 = V2Fixtures.bytes
    private val b1 = "{\"schema\":2,\"sheetUuid\":\"${V2Fixtures.V2_SHEET}\",\"scene\":{\"elements\":[1]}}\n".encodeToByteArray()

    /** Runs [hook] once, right after the reconciler's first read of the sidecar (so after `loadLocal`'s inputs are fixed). */
    private class HookedStore(private val inner: ProjectSidecarStore, private val hook: suspend () -> Unit) : ProjectSidecarStore by inner {
        private var fired = false
        override suspend fun read(slug: String): SidecarRead = inner.read(slug).also {
            if (!fired) { fired = true; hook() }
        }
    }

    private suspend fun settled(device: Device = Device(), bundle: ProjectBundle = V2Fixtures.mixedBundle()): Device {
        device.store.write(slug, SidecarCodec.encode(bundle))
        device.repos.projects.replaceProjectContents(p, bundle).getOrNull()
        assertIs<ReconcileOutcome.Unchanged>(device.reconciler.reconcileAll().outcomes.single())
        return device
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun Device.editDimension() {
        val token = repos.projects.loadProject(p).getOrNull()!!.token
        val dim = repos.dimensions.getDimensions(p).first().getOrNull()!!.single()
        repos.dimensions.saveDimension(dim.copy(locked = !dim.locked), token).getOrNull()
    }

    /** The saver's side: new v2 bytes plus the model naming them, one locked operation, base moved with the disk. */
    private suspend fun Device.saveV2(bytes: ByteArray, lock: SidecarSlugLock, order: MutableList<String>) = lock.withSlug(slug) {
        val bundle = V2Fixtures.mixedBundle(bytes)
        val files = SidecarCodec.encode(bundle)
        assertEquals(SidecarWriteOutcome.Written, store.writeNow(slug, files))
        val fresh = repos.projects.loadProject(p).getOrNull()!!.project
        repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = SidecarCodec.bundleHash(files), baseRevision = fresh.modelRevision)).getOrNull()
        order += "saver"
    }

    @Test
    fun aSaverStartedMidReconcile_should_WaitForTheSlugLock_AndItsBytesStayFinal() = runBlocking<Unit> {
        val d = settled()
        d.editDimension()
        val lock = SidecarSlugLock()
        val order = mutableListOf<String>()
        lateinit var saver: kotlinx.coroutines.Deferred<Unit>
        val store = HookedStore(d.store) { saver = kotlinx.coroutines.CoroutineScope(coroutineContext).async { d.saveV2(b1, lock, order) } }
        val reconciler = SidecarReconciler(d.repos, store, null, lock)

        val outcome = reconciler.reconcileAll().outcomes.single()
        order += "reconcile-done"
        saver.await()

        assertIs<ReconcileOutcome.Pushed>(outcome)
        assertEquals(listOf("reconcile-done", "saver"), order)
        assertContentEquals(b1, d.fs.readFileBytes(sidecarPath(slug, V2Fixtures.v2Path)))
        assertEquals(ContentHasher.sha256(b1), SidecarCodec.v2SheetShas(d.fs.readFileBytes(sidecarPath(slug, SidecarCodec.MODEL_PATH))!!)!!.getValue(V2Fixtures.v2Path))
    }

    @Test
    fun aV2WriteThatBypassesTheLock_should_MakeThePushStaleAndTheReconcilerReplan() = runBlocking<Unit> {
        val d = settled()
        d.editDimension()
        val order = mutableListOf<String>()
        val store = HookedStore(d.store) { d.saveV2(b1, SidecarSlugLock(), order) }
        val reconciler = SidecarReconciler(d.repos, store)

        val outcome = reconciler.reconcileAll().outcomes.single()

        assertIs<ReconcileOutcome.Pushed>(outcome)
        assertContentEquals(b1, d.fs.readFileBytes(sidecarPath(slug, V2Fixtures.v2Path)), "the stale first push did not overwrite or revert the newer v2 save")
        val files = assertIs<SidecarRead.Present>(d.store.read(slug)).files
        assertEquals(ContentHasher.sha256(b1), SidecarCodec.v2SheetShas(files.getValue(SidecarCodec.MODEL_PATH))!!.getValue(V2Fixtures.v2Path))
        assertTrue(SidecarCodec.decode(files).isRight(), "no Corrupt(Truncated)")
    }

    @Test
    fun aConverterFlipUnderTheSlugLock_should_NotBeRevertedByAReconcileThatPlannedBeforeIt() = runBlocking<Unit> {
        val v1Only = SketchTestSupport.bundle(sheets = 1)
        val d = settled(bundle = v1Only)
        d.editDimension()
        val lock = SidecarSlugLock()
        val flipped = v1Only.copy(
            sheets = v1Only.sheets.map { it.copy(schemaVersion = 2) },
            primitives = emptyList(),
            sheetPayloads = mapOf(v1Only.sheets.single().uuid to SheetPayload.V2Opaque.of("{\"schema\":2,\"sheetUuid\":\"x\"}\n".encodeToByteArray())),
        )
        lateinit var converter: kotlinx.coroutines.Deferred<Unit>
        val store = HookedStore(d.store) {
            converter = kotlinx.coroutines.CoroutineScope(coroutineContext).async {
                lock.withSlug(slug) { assertEquals(SidecarWriteOutcome.Written, d.store.writeNow(slug, SidecarCodec.encode(flipped))) }
            }
        }

        SidecarReconciler(d.repos, store, null, lock).reconcileAll()
        converter.await()

        val model = d.fs.readFileBytes(sidecarPath(slug, SidecarCodec.MODEL_PATH))!!.decodeToString()
        assertTrue("sheets/p1-s0.v2.json" in model, "the model still references the v2 file after the flip")
    }

    @Test
    fun aParkedProjectWriteReplayedAfterAConversion_should_BeDropped_AndAReplanWritesTheEditThroughTheCurrentModel() = runBlocking<Unit> {
        val v1Only = SketchTestSupport.bundle(sheets = 1)
        val d = settled(bundle = v1Only)
        val sheet = v1Only.sheets.single()
        d.editDimension()
        d.busy = true
        assertIs<ReconcileOutcome.Deferred>(d.reconciler.reconcileAll().outcomes.single(), "the dimension edit is parked")

        // git sync goes idle; the converter runs S1-S4 from the on-disk state and moves the base.
        d.busy = false
        val v2Bytes = "{\"schema\":2,\"sheetUuid\":\"${sheet.uuid.value}\",\"scene\":{}}\n".encodeToByteArray()
        val onDisk = SidecarCodec.decode(assertIs<SidecarRead.Present>(d.store.read(slug)).files).getOrNull()!!
        val converted = onDisk.copy(
            sheets = onDisk.sheets.map { it.copy(schemaVersion = 2) },
            primitives = emptyList(),
            sheetPayloads = mapOf(sheet.uuid to SheetPayload.V2Opaque.of(v2Bytes)),
        )
        val convertedFiles = SidecarCodec.encode(converted)
        assertEquals(SidecarWriteOutcome.Written, d.store.writeNow(slug, convertedFiles))
        val token = d.repos.projects.loadProject(p).getOrNull()!!.token
        d.repos.sheets.saveSheet(sheet.copy(schemaVersion = 2), token).getOrNull()
        val fresh = d.repos.projects.loadProject(p).getOrNull()!!.project
        d.repos.projects.saveProject(fresh.copy(lastSyncedSidecarHash = SidecarCodec.bundleHash(convertedFiles), baseRevision = fresh.modelRevision)).getOrNull()
        val modelAfterConversion = d.fs.readFileBytes(sidecarPath(slug, SidecarCodec.MODEL_PATH))!!

        assertEquals(0, d.store.flushPending())

        assertEquals(1, (d.store as FileProjectSidecarStore).staleOpaqueDropped)
        assertContentEquals(modelAfterConversion, d.fs.readFileBytes(sidecarPath(slug, SidecarCodec.MODEL_PATH)))
        assertContentEquals(v2Bytes, d.fs.readFileBytes(sidecarPath(slug, "sheets/${sheet.uuid.value}.v2.json")))
        assertTrue(SidecarCodec.decode(assertIs<SidecarRead.Present>(d.store.read(slug)).files).isRight(), "no Corrupt(Truncated)")

        assertIs<ReconcileOutcome.Pushed>(d.reconciler.reconcileAll().outcomes.single())
        val after = SidecarCodec.decode(assertIs<SidecarRead.Present>(d.store.read(slug)).files).getOrNull()!!
        assertEquals(2L, after.sheets.single().schemaVersion)
        assertEquals(!v1Only.dimensions.single().locked, after.dimensions.single().locked, "the dimension edit reached the model")
        assertContentEquals(v2Bytes, d.fs.readFileBytes(sidecarPath(slug, "sheets/${sheet.uuid.value}.v2.json")))
    }

    @Test
    fun aParkedWriteBuiltAfterTheFlipButReplayedAfterASecondSave_should_AlsoBeDropped() = runBlocking<Unit> {
        val d = settled()
        d.editDimension()
        d.busy = true
        assertIs<ReconcileOutcome.Deferred>(d.reconciler.reconcileAll().outcomes.single())
        d.busy = false
        d.saveV2(b1, SidecarSlugLock(), mutableListOf())

        assertEquals(0, d.store.flushPending())

        assertEquals(1, (d.store as FileProjectSidecarStore).staleOpaqueDropped)
        assertContentEquals(b1, d.fs.readFileBytes(sidecarPath(slug, V2Fixtures.v2Path)))
        assertTrue(SidecarCodec.decode(assertIs<SidecarRead.Present>(d.store.read(slug)).files).isRight())
        assertIs<Either.Right<*>>(SidecarCodec.decode(assertIs<SidecarRead.Present>(d.store.read(slug)).files))
    }
}
