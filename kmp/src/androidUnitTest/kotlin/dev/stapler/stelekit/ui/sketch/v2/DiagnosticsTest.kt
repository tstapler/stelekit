// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Story 3.6.4 (Task 3.6.4b; UX-48): the host composable and its live regions over the port and the notice queue. Virtual time:
 * the host's clock is a variable the test sets. Robolectric; Gradle-only like the other Compose host tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-mdpi")
class DiagnosticsTest {
    @get:Rule
    val rule = createComposeRule()

    private var now = 0L
    private val port = InMemoryDiagnosticsPort(ChromeBannerStrings(ChromeTableParser.parseFixture()))
    private val queue = SketchNoticeQueue(sequencer = OnboardingSequencer(OnboardingCounters(InMemorySettings())))
    private val state = DiagnosticsHostState(port, queue) { now }

    private fun polite() = rule.onNodeWithTag(DiagnosticsTags.POLITE)
    private fun assertive() = rule.onNodeWithTag(DiagnosticsTags.ASSERTIVE)

    @Test
    fun entryRaisedBeforeTheHost_should_BeRetained_ShownWhenItAttaches_And_AnnouncedOnceAssertively() {
        port.raise(BannerRowId.SaveFailed)
        rule.setContent { DiagnosticsHost(state) }
        rule.waitForIdle()
        assertEquals(BannerRowId.SaveFailed, queue.bannerHead()?.row)
        assertive().assertContentDescriptionEquals("Couldn't save. Keep tab open.")
        assertive().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        polite().assertContentDescriptionEquals("")
        assertEquals(BannerTier.Blocking, queue.bannerHead()?.tier)
    }

    @Test
    fun ux48_warningAndSolverConflict_should_BePolite_And_NameOnlyConstraintTypes() {
        rule.setContent { DiagnosticsHost(state) }
        port.raise(BannerRowId.WarningNotice, "Horizontal and Distance conflict")
        rule.waitForIdle()
        polite().assertContentDescriptionEquals("Constraint conflict.")
        polite().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertive().assertContentDescriptionEquals("")
        assertEquals("Horizontal and Distance conflict", queue.bannerHead()?.details)
    }

    @Test
    fun resolvedEntry_should_LeaveTheSlot() {
        port.raise(BannerRowId.SaveFailed)
        rule.setContent { DiagnosticsHost(state) }
        rule.waitForIdle()
        port.resolve(BannerRowId.SaveFailed)
        rule.waitForIdle()
        assertEquals(null, queue.bannerHead())
    }

    @Test
    fun noticePostedToTheQueue_should_BeAnnouncedWhenTheHostIsTold() {
        rule.setContent { DiagnosticsHost(state) }
        rule.waitForIdle()
        queue.post(SketchNotice("AddedConstraint", BannerTier.Info, "Constraint added.", action = "Undo", confirmsUndoableEdit = true), now)
        state.queueChanged()
        rule.waitForIdle()
        polite().assertContentDescriptionEquals("Constraint added.")
    }
}
