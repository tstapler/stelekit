// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.platform.SketchWriterLeaseState
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseHolder
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseRefusal
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 1.4.3n: the writer lease decides `LeaseConflict:OtherTab` for a v2 sheet. */
class WriterLeaseRouterTest {
    private val caps = V2Capabilities(conversionReady = true, conversionOffered = true, storage = V2Storage.Available)

    private fun route(lease: SketchWriterLeaseState) =
        SketchEditorRouter.route(RouteInput(V2Mode.On, caps, SheetSchema.V2, lease = lease))

    @Test
    fun heldElsewhere_should_RouteToLeaseConflictOtherTab() {
        assertEquals(RouteOutcome.LeaseConflict("OtherTab"), route(SketchWriterLeaseState.HeldElsewhere))
        assertEquals(BannerRowId.LeaseConflict, SketchEditorRouter.bannerRow(route(SketchWriterLeaseState.HeldElsewhere)))
    }

    @Test
    fun held_should_RouteToEditable() {
        assertEquals(RouteOutcome.V2Editable, route(SketchWriterLeaseState.Held))
    }

    @Test
    fun acquiring_should_RouteToPending() {
        assertEquals(RouteOutcome.Pending, route(SketchWriterLeaseState.Acquiring))
    }

    @Test
    fun registry_should_RefuseAV2Lease_WithOtherTab_WhenTheWriterLeaseIsHeldElsewhere() = runBlocking<Unit> {
        val registry = SheetLeaseRegistry(TestSketchWriterLease(SketchWriterLeaseState.HeldElsewhere))
        assertEquals(LeaseRefusal.OtherTab, registry.acquire(SheetUuid("s1"), LeaseHolder.V2).leftOrNull())
    }
}
