// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.platform.SketchWriterLeaseState
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseHolder
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseRefusal
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One lease per sheet in a UI-free layer (Task 1.4.3b). */
class SheetLeaseRegistryTest {
    private val sheet = SheetUuid("s1")

    @Test
    fun v2Request_should_BeRefusedAsSheetBusy_WhileTheLegacyEditorHoldsTheSheet() = runBlocking<Unit> {
        val registry = SheetLeaseRegistry(TestSketchWriterLease())
        val legacy = registry.acquire(sheet, LeaseHolder.Legacy).getOrNull()!!
        assertEquals(LeaseRefusal.SheetBusy(LeaseHolder.Legacy), registry.acquire(sheet, LeaseHolder.V2).leftOrNull())
        assertEquals(LeaseHolder.Legacy, registry.holderOf(sheet))
        legacy.release()
        assertTrue(registry.acquire(sheet, LeaseHolder.V2).isRight())
    }

    @Test
    fun legacyRequest_should_BeRefused_WhileTheV2EditorHoldsTheSheet() = runBlocking<Unit> {
        val registry = SheetLeaseRegistry(TestSketchWriterLease())
        registry.acquire(sheet, LeaseHolder.V2).getOrNull()!!
        assertEquals(LeaseRefusal.SheetBusy(LeaseHolder.V2), registry.acquire(sheet, LeaseHolder.Legacy).leftOrNull())
    }

    @Test
    fun release_should_BeIdempotent_AndNeverFreeAnotherHoldersLease() = runBlocking<Unit> {
        val registry = SheetLeaseRegistry(TestSketchWriterLease())
        val first = registry.acquire(sheet, LeaseHolder.Legacy).getOrNull()!!
        first.release()
        val second = registry.acquire(sheet, LeaseHolder.V2).getOrNull()!!
        first.release()
        assertEquals(LeaseHolder.V2, registry.holderOf(sheet))
        second.release()
        assertNull(registry.holderOf(sheet))
    }

    @Test
    fun differentSheets_should_NotBlockEachOther() = runBlocking<Unit> {
        val registry = SheetLeaseRegistry(TestSketchWriterLease())
        assertTrue(registry.acquire(SheetUuid("a"), LeaseHolder.V2).isRight())
        assertTrue(registry.acquire(SheetUuid("b"), LeaseHolder.Legacy).isRight())
    }

    @Test
    fun legacyRequest_should_NotAskTheWriterLease() = runBlocking<Unit> {
        val registry = SheetLeaseRegistry(TestSketchWriterLease(SketchWriterLeaseState.HeldElsewhere))
        assertTrue(registry.acquire(sheet, LeaseHolder.Legacy).isRight())
    }
}
