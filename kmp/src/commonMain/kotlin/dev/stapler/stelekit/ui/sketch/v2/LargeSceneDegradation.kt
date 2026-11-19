// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/**
 * The degradation steps in the order the UX applies them (ux.md section 15): Sketchy is drawn as Clean (the stored
 * `roughness` stays), shadows are skipped, off-screen elements are culled, thumbnails are low-resolution, and live
 * constraint glyphs are hidden. [isEffect] marks the ones "Re-enable effects" turns back on; culling and low-resolution
 * thumbnails are performance-only and stay while the drawing is large.
 */
enum class DegradationStep(val isEffect: Boolean) {
    SketchyOff(true),
    ShadowsSkipped(true),
    OffscreenCulling(false),
    LowResThumbnails(false),
    ConstraintGlyphsHidden(true),
}

/** The steps in force for the current size of the scene. */
data class Degradation(val active: Set<DegradationStep>) {
    /** True when any step is on. */
    val degraded: Boolean get() = active.isNotEmpty()

    fun has(step: DegradationStep): Boolean = step in active

    /** The style to hand the draw-list builder: Sketchy becomes Clean while [DegradationStep.SketchyOff] is in force. */
    fun effectiveStyle(requested: RenderStyle): RenderStyle =
        if (requested == RenderStyle.Sketchy && has(DegradationStep.SketchyOff)) RenderStyle.Clean else requested

    companion object {
        val None = Degradation(emptySet())
    }
}

/**
 * Large-drawing degradation at ONE threshold (Story 2.2.3b, UX-73): the soft thresholds of [SceneLimits] (80% of the
 * element and constraint caps). Crossing either raises the `LargeDrawing` banner candidate once and turns every step on;
 * "Re-enable effects" turns the effects back on for the session; dropping below both thresholds ends the session
 * override and withdraws the banner. The drawing itself is never changed. Not thread-safe: call from the UI thread.
 */
class LargeSceneDegradation(
    private val diagnostics: DiagnosticsPort,
    private val limits: SceneLimits = SceneLimits.Default,
) {
    private var reenabled = false
    private var bannerRaised = false

    /** Applies the counts of the current scene and returns the steps now in force. */
    fun onCounts(elements: Int, constraints: Int): Degradation {
        val large = elements >= limits.softElementThreshold || constraints >= limits.softConstraintThreshold
        if (!large) {
            reenabled = false
            withdrawBanner()
            return Degradation.None
        }
        if (reenabled) return Degradation(DegradationStep.entries.filterNot { it.isEffect }.toSet())
        raiseBanner()
        return Degradation(DegradationStep.entries.toSet())
    }

    /** The [Re-enable effects] action: effects come back for this session, whatever the size. */
    fun reenableEffects() {
        reenabled = true
        withdrawBanner()
    }

    private fun raiseBanner() {
        if (bannerRaised) return
        bannerRaised = diagnostics.raise(BannerRowId.LargeDrawing, DETAILS).isRight()
    }

    private fun withdrawBanner() {
        if (!bannerRaised) return
        diagnostics.resolve(BannerRowId.LargeDrawing)
        bannerRaised = false
    }

    companion object {
        /** The Details line of the banner; [Re-enable effects] sits under it. */
        const val DETAILS = "Sketchy style is off, shadows are skipped, live constraint glyphs are hidden. Your drawing is unchanged."
        const val REENABLE_LABEL = "Re-enable effects (may be slow)"
    }
}
