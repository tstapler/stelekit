// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.SketchTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 1.4.7n (D-153): bounds, read-only and v2-sheets-only of the synced-v2 discovery scan and its import. */
class SyncedV2ProjectDiscoveryTest {
    private val slug = SketchTestSupport.project().slug
    private val p = SketchTestSupport.P

    /** Counts what the scan opens, so "reads no classic sheet and no model body" is an assertion, not a hope. */
    private class SpyStore(private val inner: ProjectSidecarStore) : ProjectSidecarStore by inner {
        val prefixReads = mutableListOf<Pair<String, Int>>()
        var bodyReads = 0

        override suspend fun readSheetPrefix(slug: String, relPath: String, maxBytes: Int): ByteArray? {
            prefixReads += relPath to maxBytes
            return inner.readSheetPrefix(slug, relPath, maxBytes)
        }

        override suspend fun read(slug: String): SidecarRead { bodyReads++; return inner.read(slug) }
        override suspend fun readModel(slug: String): SidecarRead { bodyReads++; return inner.readModel(slug) }
    }

    private suspend fun synced(bundle: dev.stapler.stelekit.model.ProjectBundle): Device {
        val desktop = Device()
        desktop.store.write(slug, SidecarCodec.encode(bundle))
        return Device().also { it.pullFrom(desktop) }
    }

    @Test
    fun aV2Project_should_BeFoundWithOneBoundedProbeAndNoWrites() = runBlocking<Unit> {
        val web = synced(V2Fixtures.mixedBundle())
        val spy = SpyStore(web.store)
        val writesBefore = web.fs.writeCount

        val result = SyncedV2ProjectDiscovery(spy).scan(listOf(slug))

        assertEquals(listOf(slug), result.found)
        assertEquals(listOf(V2Fixtures.v2Path to SyncedV2ProjectDiscovery.PROBE_BYTES), spy.prefixReads)
        assertEquals(0, spy.bodyReads, "no model body and no whole sheet is read")
        assertEquals(writesBefore, web.fs.writeCount, "the scan writes nothing")
    }

    @Test
    fun aClassicOnlyProject_should_NotBeFoundAndNoSheetIsOpened() = runBlocking<Unit> {
        val web = synced(SketchTestSupport.bundle(sheets = 2))
        val spy = SpyStore(web.store)

        val result = SyncedV2ProjectDiscovery(spy).scan(listOf(slug))

        assertTrue(result.found.isEmpty())
        assertTrue(spy.prefixReads.isEmpty(), "no classic sheet file is read")
    }

    @Test
    fun moreUnknownProjectsThanTheBound_should_LeaveTheRestForTheNextOpen() = runBlocking<Unit> {
        val web = synced(SketchTestSupport.bundle(sheets = 1))
        val slugs = List(SyncedV2ProjectDiscovery.MAX_UNKNOWN_PROJECTS + 1) { "p$it" }

        val result = SyncedV2ProjectDiscovery(web.store).scan(slugs)

        assertEquals(SyncedV2ProjectDiscovery.MAX_UNKNOWN_PROJECTS, result.scanned)
        assertEquals(1, result.deferred)
    }

    @Test
    fun anExhaustedTimeBudget_should_StopTheScanAndDeferTheRest() = runBlocking<Unit> {
        val web = synced(V2Fixtures.mixedBundle())
        var now = 0L
        val discovery = SyncedV2ProjectDiscovery(web.store) { now.also { now += SyncedV2ProjectDiscovery.TIME_BUDGET_MS } }

        val result = discovery.scan(listOf(slug, "other", "third"))

        assertEquals(1, result.scanned)
        assertEquals(2, result.deferred)
        assertEquals(listOf(slug), result.found)
    }

    @Test
    fun theImport_should_TakeV2SheetsOnlyAndLeaveTheBundleUntouched() = runBlocking<Unit> {
        val web = synced(V2Fixtures.mixedBundle())
        val before = web.fs.allFilePaths().associateWith { web.fs.readFileBytes(it)!!.decodeToString() }
        val reconciler = SidecarReconciler(web.repos, web.store, null, v2Discovery = SyncedV2ProjectDiscovery(web.store))

        val first = reconciler.reconcileAll().outcomes.single()
        assertIs<ReconcileOutcome.Pulled>(first)
        val loaded = web.repos.projects.loadProject(p).getOrNull()!!
        val sheets = web.repos.sheets.getSheets(p).first().getOrNull()!!.items
        assertEquals(listOf(SheetUuid(V2Fixtures.V2_SHEET)), sheets.map { it.uuid })
        assertTrue(loaded.project.slug == slug)

        val second = reconciler.reconcileAll().outcomes.single()
        assertIs<ReconcileOutcome.Unchanged>(second, "a v2-only view never pushes over the classic sheets of the bundle")
        val after = web.fs.allFilePaths().associateWith { web.fs.readFileBytes(it)!!.decodeToString() }
        assertEquals(before, after, "nothing under the sidecar root changed")
    }

    @Test
    fun aClassicOnlyBundle_should_NotBeImportedUnderOff() = runBlocking<Unit> {
        val web = synced(SketchTestSupport.bundle(sheets = 2))
        val reconciler = SidecarReconciler(web.repos, web.store, null, v2Discovery = SyncedV2ProjectDiscovery(web.store))

        assertTrue(reconciler.reconcileAll().outcomes.isEmpty())
        assertTrue(web.repos.projects.loadProject(p).getOrNull() == null)
    }
}
