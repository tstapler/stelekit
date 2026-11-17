// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.runtime.convert.HasTechnicalData
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.ui.sketch.ConversionState
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.UpgradeAffordance
import dev.stapler.stelekit.ui.sketch.v2.OnboardingCounters
import dev.stapler.stelekit.ui.sketch.v2.OnboardingSequencer
import dev.stapler.stelekit.ui.sketch.v2.migration.StartDecision
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeOfferController
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.4.6g (UX-104, UX-130; D-12, D-98): the chip is offered, never converts by itself, and is never offered for an action that cannot succeed. */
class UpgradeOfferTest {
    private val settings = MapSettings()
    private val acks = ProjectAckStore(settings) { "g1" }
    private val sequencer = OnboardingSequencer(OnboardingCounters(settings))
    private val controller = UpgradeOfferController(sequencer, acks)
    private val caps = V2Capabilities(conversionReady = true, conversionOffered = true, storage = V2Storage.Available)

    private fun route(refusal: HasTechnicalData? = null, state: ConversionState = ConversionState.NotConverted) =
        SketchEditorRouter.route(RouteInput(V2Mode.On, caps, SheetSchema.V1, state, refusal))

    private fun affordance(outcome: RouteOutcome): UpgradeAffordance = (outcome as RouteOutcome.Legacy).upgrade

    @Test
    fun openingASketch_should_OfferTheChip_AndWriteNothing() = runTest {
        controller.offer(affordance(route()), "sheetA")
        val candidate = sequencer.visibleCandidate()
        assertEquals(BannerRowId.UpgradeChip, candidate?.row)
        assertEquals(SketchMigrationStrings.Banners.UPGRADE_SUMMARY, candidate?.summary)
        assertEquals(SketchConsentCopy.CHIP_DETAILS, candidate?.details)
        assertFalse(acks.isAcknowledged("kitchen"), "nothing is acknowledged by opening")
        assertTrue(settings.store.isEmpty() || settings.store.keys.none { "ack" in it }, "no acknowledgement record is written")
    }

    @Test
    fun refusedSheet_should_NotOfferTheChip_AndDisableTheMenuEntryWithTheReason() {
        val upgrade = affordance(route(refusal = HasTechnicalData))
        assertEquals(UpgradeAffordance.Disabled(HasTechnicalData), upgrade)
        controller.offer(upgrade, "sheetA")
        assertNull(sequencer.visibleCandidate())
        assertTrue("dimensions or zones" in dev.stapler.stelekit.ui.sketch.SketchReasonText.noticeReason(dev.stapler.stelekit.ui.sketch.ClassicNoticeReason.TechnicalData))
    }

    @Test
    fun refusalAppearingBeforeStart_should_WithdrawTheChip() = runTest {
        controller.offer(UpgradeAffordance.Chip, "sheetA")
        assertEquals(BannerRowId.UpgradeChip, sequencer.visibleCandidate()?.row)
        val decision = controller.start("kitchen") { HasTechnicalData }
        assertEquals(StartDecision.Withdrawn(HasTechnicalData), decision)
        assertNull(sequencer.visibleCandidate(), "the chip is withdrawn")
        assertFalse(acks.isAcknowledged("kitchen"))
    }

    @Test
    fun start_should_AskForConfirmationInAnUnacknowledgedProject_AndConvertNowInAnAcknowledgedOne() = runTest {
        controller.offer(UpgradeAffordance.Chip, "sheetA")
        assertEquals(StartDecision.NeedsFirstWriteDialog, controller.start("kitchen") { null })
        acks.acknowledge("kitchen")
        controller.offer(UpgradeAffordance.Chip, "sheetB")
        assertEquals(StartDecision.ConvertNow, controller.start("kitchen") { null })
        assertEquals(StartDecision.NeedsFirstWriteDialog, controller.start("pantry") { null }, "another project asks again")
    }

    @Test
    fun notNow_should_HideTheChipForTheSessionForEverySketch_AndANewSessionBringsItBack() {
        controller.offer(UpgradeAffordance.Chip, "sheetA")
        controller.notNow()
        assertNull(sequencer.visibleCandidate())
        controller.offer(UpgradeAffordance.Chip, "sheetB")
        assertNull(sequencer.visibleCandidate(), "not for any sketch of the project this session")
        sequencer.newSession()
        controller.offer(UpgradeAffordance.Chip, "sheetB")
        assertEquals(BannerRowId.UpgradeChip, sequencer.visibleCandidate()?.row)
    }

    @Test
    fun keepClassic_should_DeclinePerSheet_AndTheRouterStopsOfferingIt() {
        controller.offer(UpgradeAffordance.Chip, "sheetA")
        controller.keepClassic("sheetA")
        assertTrue(acks.isDeclined("sheetA"))
        assertFalse(acks.isDeclined("sheetB"))
        val state = if (acks.isDeclined("sheetA")) ConversionState.Declined else ConversionState.NotConverted
        assertEquals(UpgradeAffordance.None, affordance(route(state = state)))
        acks.clearDecline("sheetA")
        assertFalse(acks.isDeclined("sheetA"), "the sheet menu entry lifts the mark")
    }

    @Test
    fun theOfferedChip_should_BeAConsentRowWithNoShortTitle() {
        controller.offer(UpgradeAffordance.Chip, "sheetA")
        val candidate = assertIs<dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate>(sequencer.visibleCandidate())
        assertNull(candidate.shortTitle)
        assertEquals(listOf("Details", "Not now", "Keep this sketch classic"), candidate.moreItems)
    }
}
