// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.render.CleanStrokeStyle
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.SketchTarget
import dev.stapler.stelekit.sketch.v2.render.SketchyAvailability
import dev.stapler.stelekit.sketch.v2.render.SketchyMeasurement
import dev.stapler.stelekit.sketch.v2.render.SketchyStatus
import dev.stapler.stelekit.sketch.v2.render.SketchyStrokeStyle
import dev.stapler.stelekit.sketch.v2.render.StrokeStyleProvider
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.v2.Degradation
import dev.stapler.stelekit.ui.sketch.v2.DegradationStep
import dev.stapler.stelekit.ui.sketch.v2.LargeSceneDegradation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tasks 2.2.3b and the availability rule of 2.2.3 (UX-73): one soft threshold, the banner, the step order and the per-target pivot. */
class LargeSceneDegradationPolicyTest {
    private val table = ChromeTableParser.parseFixture()
    private val limits = SceneLimits.Default

    private val strings = object : BannerStringSource {
        private fun row(id: BannerRowId) = table.banners.single { it.id == id.name }
        override fun summary(row: BannerRowId) = row(row).summary
        override fun shortTitle(row: BannerRowId) = row(row).shortTitle
        override fun primaryLabel(row: BannerRowId) = row(row).primaryLabel ?: ""
        override fun moreItems(row: BannerRowId) = row(row).moreItems
    }

    private val port = InMemoryDiagnosticsPort(strings)
    private val policy = LargeSceneDegradation(port)

    private fun banner() = port.diagnostics.value.entries.singleOrNull { it.row == BannerRowId.LargeDrawing }

    @Test
    fun belowTheSoftThreshold_should_ShowNoBannerAndDegradeNothing() {
        val d = policy.onCounts(limits.softElementThreshold - 1, limits.softConstraintThreshold - 1)
        assertEquals(Degradation.None, d)
        assertNull(banner())
        assertEquals(RenderStyle.Sketchy, d.effectiveStyle(RenderStyle.Sketchy))
    }

    @Test
    fun atTheSoftElementThreshold_should_RaiseTheBannerOnce_AndTurnEveryStepOn() {
        val d = policy.onCounts(limits.softElementThreshold, 0)
        assertEquals(DegradationStep.entries.toSet(), d.active)
        val b = banner() ?: error("no LargeDrawing banner")
        assertEquals("Large drawing: effects off.", b.summary)
        assertEquals(LargeSceneDegradation.DETAILS, b.details)
        assertTrue("Dismiss" in b.moreItems)
        assertEquals(RenderStyle.Clean, d.effectiveStyle(RenderStyle.Sketchy))
        policy.onCounts(limits.softElementThreshold + 1, 0)
        assertEquals(1, port.diagnostics.value.entries.count { it.row == BannerRowId.LargeDrawing })
    }

    @Test
    fun atTheSoftConstraintThreshold_should_Degrade_EvenWithFewElements() {
        assertTrue(policy.onCounts(10, limits.softConstraintThreshold).degraded)
        assertTrue(banner() != null)
    }

    @Test
    fun stepOrder_should_FollowTheUxOrder() {
        assertEquals(
            listOf("SketchyOff", "ShadowsSkipped", "OffscreenCulling", "LowResThumbnails", "ConstraintGlyphsHidden"),
            DegradationStep.entries.map { it.name },
        )
    }

    @Test
    fun reenableEffects_should_RestoreEffectsAndWithdrawTheBanner_UntilTheDrawingShrinksAndGrowsAgain() {
        policy.onCounts(limits.softElementThreshold, 0)
        policy.reenableEffects()
        assertNull(banner())
        val d = policy.onCounts(limits.softElementThreshold, 0)
        assertEquals(RenderStyle.Sketchy, d.effectiveStyle(RenderStyle.Sketchy), "effects stay back for the session")
        assertTrue(d.has(DegradationStep.OffscreenCulling) && d.has(DegradationStep.LowResThumbnails))
        assertFalse(d.has(DegradationStep.SketchyOff) || d.has(DegradationStep.ConstraintGlyphsHidden) || d.has(DegradationStep.ShadowsSkipped))
        assertNull(banner(), "no second banner while overridden")
        policy.onCounts(1, 0)
        policy.onCounts(limits.softElementThreshold, 0)
        assertTrue(banner() != null, "a new crossing raises it again")
    }

    @Test
    fun shrinkingBelowTheThreshold_should_WithdrawTheBanner() {
        policy.onCounts(limits.softElementThreshold, 0)
        policy.onCounts(limits.softElementThreshold - 1, 0)
        assertNull(banner())
    }

    @Test
    fun hardCaps_should_SitAboveTheSoftThresholds_AsEightyPercent() {
        assertEquals(limits.maxElements * 4 / 5, limits.softElementThreshold)
        assertEquals(limits.maxConstraints * 4 / 5, limits.softConstraintThreshold)
    }

    @Test
    fun assess_should_MarkATargetUnavailable_When_PixelsMissToleranceOrTheBuildIsOverTheAbsoluteBudget() {
        val budgetNanos = (SketchyAvailability.SKETCHY_BUILD_BUDGET_MS * NANOS_PER_MILLI).toLong()
        val ok = SketchyMeasurement(0.001, sketchyBuildNanos = budgetNanos, cleanBuildNanos = 100)
        assertSame(SketchyStatus.Available, SketchyAvailability.assess(ok))
        assertIs<SketchyStatus.Unavailable>(SketchyAvailability.assess(ok.copy(pixelDiffFraction = SketchyAvailability.PIXEL_TOLERANCE_FRACTION + 0.001)))
        assertIs<SketchyStatus.Unavailable>(SketchyAvailability.assess(ok.copy(sketchyBuildNanos = budgetNanos + 1)))
        // The Sketchy/Clean ratio is informational only (owner decision 2026-11-17): a 1000x ratio under the budget stays Available.
        assertSame(SketchyStatus.Available, SketchyAvailability.assess(ok.copy(sketchyBuildNanos = 100_000, cleanBuildNanos = 100)))
    }

    @Test
    fun unavailableTarget_should_ResolveToCleanWithTheNotice_AndKeepOtherTargetsSketchy() {
        val availability = SketchyAvailability(mapOf(SketchTarget.Ios to SketchyStatus.Unavailable("pixel diff")))
        assertEquals(RenderStyle.Clean, availability.effective(RenderStyle.Sketchy, SketchTarget.Ios))
        assertEquals(RenderStyle.Sketchy, availability.effective(RenderStyle.Sketchy, SketchTarget.Web))
        assertEquals("Hand-drawn style is not available on this device yet", availability.notice(RenderStyle.Sketchy, SketchTarget.Ios))
        assertNull(availability.notice(RenderStyle.Clean, SketchTarget.Ios))
        assertNull(availability.notice(RenderStyle.Sketchy, SketchTarget.Desktop))
        assertSame(CleanStrokeStyle, StrokeStyleProvider.forTarget(RenderStyle.Sketchy, availability, SketchTarget.Ios))
        assertSame(SketchyStrokeStyle, StrokeStyleProvider.forTarget(RenderStyle.Sketchy, availability, SketchTarget.Android))
    }

    @Test
    fun recordedTargets_should_DrawSketchyEverywhere_WhenNoMeasurementSaidOtherwise() {
        for (target in SketchTarget.entries) assertSame(SketchyStatus.Available, SketchyAvailability().status(target), "$target")
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
