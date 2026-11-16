// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.SketchWriterLeaseState
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.HasTechnicalData
import dev.stapler.stelekit.ui.sketch.ConversionState
import dev.stapler.stelekit.ui.sketch.NoEditorReason
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.UpgradeAffordance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The router matrix of Story 1.4.3 over (mode, schema, conversionState) and the storage precedence. */
class EditorRouterTest {
    private fun caps(storage: V2Storage = V2Storage.Available, offered: Boolean = true, ready: Boolean = true) =
        V2Capabilities(ready, offered, storage)

    private fun route(
        mode: V2Mode,
        schema: SheetSchema,
        conversion: ConversionState = ConversionState.NotConverted,
        capabilities: V2Capabilities = caps(),
        refusal: HasTechnicalData? = null,
        lease: SketchWriterLeaseState = SketchWriterLeaseState.Held,
    ) = SketchEditorRouter.route(RouteInput(mode, capabilities, schema, conversion, refusal, lease))

    @Test
    fun v1_should_OpenLegacyWithNoChip_When_ModeIsOffOrNewProjectsOnly() {
        for (mode in listOf(V2Mode.Off, V2Mode.NewProjectsOnly)) {
            assertEquals(RouteOutcome.Legacy(UpgradeAffordance.None), route(mode, SheetSchema.V1), "$mode")
        }
    }

    @Test
    fun v1_should_OfferUpgrade_OnlyInModeOn_AndNeverConvertOnOpen() {
        assertEquals(RouteOutcome.Legacy(UpgradeAffordance.Chip), route(V2Mode.On, SheetSchema.V1))
        assertEquals(
            RouteOutcome.Legacy(UpgradeAffordance.Disabled(HasTechnicalData)),
            route(V2Mode.On, SheetSchema.V1, refusal = HasTechnicalData),
        )
    }

    @Test
    fun v1_should_ConvertOnlyOnAnExplicitUpgradeRequest() {
        assertEquals(RouteOutcome.ConvertThenV2, route(V2Mode.On, SheetSchema.V1, ConversionState.UpgradeRequested))
        assertEquals(
            RouteOutcome.Legacy(UpgradeAffordance.Disabled(HasTechnicalData)),
            route(V2Mode.On, SheetSchema.V1, ConversionState.UpgradeRequested, refusal = HasTechnicalData),
        )
        assertEquals(RouteOutcome.Legacy(UpgradeAffordance.None), route(V2Mode.On, SheetSchema.V1, ConversionState.Declined))
    }

    @Test
    fun v1_should_OpenLegacyWithTheReason_When_ConversionFailed() {
        val failed = ConversionState.Failed(ConversionFailure.HasTechnicalData)
        assertEquals(
            RouteOutcome.Legacy(UpgradeAffordance.None, ConversionFailure.HasTechnicalData),
            route(V2Mode.On, SheetSchema.V1, failed),
        )
    }

    @Test
    fun v1_should_NeverOfferUpgrade_When_TheBuildCapsOnToNewProjectsOnly() {
        assertEquals(RouteOutcome.Legacy(UpgradeAffordance.None), route(V2Mode.On, SheetSchema.V1, capabilities = caps(offered = false)))
        assertEquals(RouteOutcome.Legacy(UpgradeAffordance.None), route(V2Mode.On, SheetSchema.V1, capabilities = caps(ready = false)))
    }

    @Test
    fun v2_should_OpenReadOnlyWithTheReason_When_ModeIsOff() {
        assertEquals(RouteOutcome.V2ReadOnly(SketchReasonText.READ_ONLY_TURN_ON), route(V2Mode.Off, SheetSchema.V2))
        assertEquals("Turn on the new editor to edit.", SketchReasonText.READ_ONLY_TURN_ON)
    }

    @Test
    fun v2_should_OpenEditable_When_ModeAllowsIt() {
        for (mode in listOf(V2Mode.NewProjectsOnly, V2Mode.On)) assertEquals(RouteOutcome.V2Editable, route(mode, SheetSchema.V2))
    }

    @Test
    fun newerSchema_should_NeverOpenAnEditor_InAnyMode() {
        for (mode in V2Mode.entries) {
            assertEquals(RouteOutcome.NoEditor(NoEditorReason.NewerSchema), route(mode, SheetSchema.Newer), "$mode")
        }
        assertEquals("Update SteleKit to view this sketch", SketchReasonText.NEWER_SCHEMA)
    }

    @Test
    fun v2_should_ShowNoEditor_BeforeEveryOtherRow_When_StorageIsUnavailable() {
        for (storage in listOf(V2Storage.PlatformUnsupported, V2Storage.NoStore, V2Storage.Browser(StorageReason.NoOpfs))) {
            for (mode in V2Mode.entries) {
                assertEquals(
                    RouteOutcome.NoEditor(NoEditorReason.StorageUnavailable(storage)),
                    route(mode, SheetSchema.V2, capabilities = caps(storage = storage)),
                    "$mode $storage",
                )
            }
        }
    }

    @Test
    fun v2_should_BePending_NotNoEditor_WhileStorageIsChecking() {
        for (mode in V2Mode.entries) {
            assertEquals(RouteOutcome.Pending, route(mode, SheetSchema.V2, capabilities = caps(storage = V2Storage.Checking)))
        }
    }

    @Test
    fun v1_should_StayEditableInTheLegacyEditor_WhenStorageIsUnavailable() {
        assertEquals(
            RouteOutcome.Legacy(UpgradeAffordance.None),
            route(V2Mode.On, SheetSchema.V1, capabilities = caps(storage = V2Storage.PlatformUnsupported)),
        )
    }

    @Test
    fun banner_should_BeRaisedForReadOnlyAndLeaseConflictOnly() {
        assertEquals(BannerRowId.ReadOnly, SketchEditorRouter.bannerRow(route(V2Mode.Off, SheetSchema.V2)))
        assertEquals(
            BannerRowId.LeaseConflict,
            SketchEditorRouter.bannerRow(route(V2Mode.On, SheetSchema.V2, lease = SketchWriterLeaseState.HeldElsewhere)),
        )
        assertNull(SketchEditorRouter.bannerRow(route(V2Mode.On, SheetSchema.V2)))
        assertIs<RouteOutcome.V2Editable>(route(V2Mode.On, SheetSchema.V2))
    }
}
