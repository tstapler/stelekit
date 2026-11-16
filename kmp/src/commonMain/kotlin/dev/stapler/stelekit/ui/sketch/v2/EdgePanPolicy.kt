// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.EdgeExclusionRect
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSettings
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSide

/** The target classes Task 0.1.4d marks Supported or not. */
enum class EdgeTarget { Android, Ios, Web, TouchDesktop }

/**
 * The recorded result of Task 0.1.4d per target class. Supported only when a real drag that started in the protected span
 * reached the app on every attempt of at least 20 (research/input-spike.md). [Unmeasured] counts as not Supported.
 */
enum class EdgeSupport { Supported, NotSupported, Unmeasured }

/** The spike result the policy reads; the constant below mirrors research/input-spike.md and is the one place to edit after a device run. */
fun interface EdgeSpikeResults {
    fun of(target: EdgeTarget): EdgeSupport

    companion object {
        /** Every target is UNMEASURED until the owner's device runs of Task 0.1.4d, so the strip ships Off everywhere (D-32). */
        val Recorded = EdgeSpikeResults { EdgeSupport.Unmeasured }
    }
}

/** Which side a strip is on. */
enum class StripEdge { Left, Right }

/** A rectangle in dp, top-left origin. */
data class DpRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

/** One strip: [hit] is the 44 dp hit area, [band] the 24 dp visible band next to the inset, both limited to the protected span. */
data class EdgeStrip(val edge: StripEdge, val hit: DpRect, val band: DpRect)

/**
 * The ONE strip geometry (UX-121, D-18): it starts at the system gesture inset, is 44 dp wide (24 dp visible band next to the
 * inset plus 20 dp hit-only) and covers only a protected span of at most the height the platform grants, centred on its edge.
 */
object EdgePanGeometry {
    const val HIT_DP = 44
    const val BAND_DP = 24

    /** The span the platform grants per edge on Android since API 29; Task 0.1.4d re-verifies it on a device. */
    const val EXPECTED_SPAN_DP = 200

    /** The canvas host in dp with the system gesture insets at its left and right edge (0 where there is none). */
    data class Host(val widthDp: Double, val heightDp: Double, val insetStartDp: Double = 0.0, val insetEndDp: Double = 0.0)

    fun strips(side: EdgePanSide, host: Host, grantedSpanDp: Double = EXPECTED_SPAN_DP.toDouble()): List<EdgeStrip> {
        val span = minOf(grantedSpanDp, host.heightDp).coerceAtLeast(0.0)
        val top = (host.heightDp - span) / 2.0
        val bottom = top + span
        val left = EdgeStrip(
            StripEdge.Left,
            DpRect(host.insetStartDp, top, host.insetStartDp + HIT_DP, bottom),
            DpRect(host.insetStartDp, top, host.insetStartDp + BAND_DP, bottom),
        )
        val rightStart = host.widthDp - host.insetEndDp
        val right = EdgeStrip(
            StripEdge.Right,
            DpRect(rightStart - HIT_DP, top, rightStart, bottom),
            DpRect(rightStart - BAND_DP, top, rightStart, bottom),
        )
        return when (side) {
            EdgePanSide.Off -> emptyList()
            EdgePanSide.Left -> listOf(left)
            EdgePanSide.Right -> listOf(right)
            EdgePanSide.Both -> listOf(left, right)
        }
    }

    /** The hit areas in screen pixels, for the pointer adapter. */
    fun hitRects(strips: List<EdgeStrip>, pxPerDp: Double): List<ScreenRect> =
        strips.map { ScreenRect(it.hit.left * pxPerDp, it.hit.top * pxPerDp, it.hit.right * pxPerDp, it.hit.bottom * pxPerDp) }

    /**
     * The rectangles to ask the system to leave to the app: the protected span only, from the screen edge through the strip's
     * hit area so the gesture inset itself is included (never the whole edge).
     */
    fun exclusionRects(strips: List<EdgeStrip>, viewWidthDp: Double, pxPerDp: Double): List<EdgeExclusionRect> = strips.map {
        val edgeLeft = if (it.edge == StripEdge.Left) 0.0 else it.hit.left
        val edgeRight = if (it.edge == StripEdge.Left) it.hit.right else viewWidthDp
        EdgeExclusionRect((edgeLeft * pxPerDp).toInt(), (it.hit.top * pxPerDp).toInt(), (edgeRight * pxPerDp).toInt(), (it.hit.bottom * pxPerDp).toInt())
    }
}

/**
 * The safe default for "Edge pan side" (D-32). Off on every target Task 0.1.4d did not mark Supported, where the platform
 * cannot be asked to exclude the span (Android below API 29), and on a device marked `ExclusionFailed`; where Supported, Right on
 * Compact width (two 44 dp strips would cost 88 of 360 dp) and Both from [BOTH_FROM_DP]. Two-finger pan, the Hand tool and
 * the pan arrows never depend on the strip.
 */
object EdgePanPolicy {
    /** The Compact width limit of the chrome table (`compactWidth`, asserted by `EdgePanStripTest`). */
    const val BOTH_FROM_DP = 600

    fun defaultSide(
        target: EdgeTarget,
        widthDp: Int,
        canRequestExclusion: Boolean,
        exclusionFailed: Boolean,
        spike: EdgeSpikeResults = EdgeSpikeResults.Recorded,
    ): EdgePanSide = when {
        !canRequestExclusion || exclusionFailed || spike.of(target) != EdgeSupport.Supported -> EdgePanSide.Off
        widthDp >= BOTH_FROM_DP -> EdgePanSide.Both
        else -> EdgePanSide.Right
    }

    /** The user's choice when there is one, else [defaultSide]. */
    fun effectiveSide(
        settings: EdgePanSettings,
        target: EdgeTarget,
        widthDp: Int,
        canRequestExclusion: Boolean,
        spike: EdgeSpikeResults = EdgeSpikeResults.Recorded,
    ): EdgePanSide = settings.choice ?: defaultSide(target, widthDp, canRequestExclusion, settings.exclusionFailed, spike)
}

/**
 * The reactive backstop (Task 3.1.2g), only meaningful where the strip is on: when the system cancels a one-finger strip drag
 * twice in one session, the strip hides for the session, the device is marked `ExclusionFailed` (the next session starts Off)
 * and the one-time `EdgePanStopped` notice goes to the onboarding sequencer.
 */
class EdgePanBackstop(
    private val settings: EdgePanSettings,
    private val sequencer: OnboardingSequencer,
    private val strings: BannerStringSource,
) {
    private var cancels = 0

    /** True after the second cancel: the host stops drawing and hit-testing the strip for the rest of the session. */
    var hiddenForSession: Boolean = false
        private set

    fun onStripDragCancelled() {
        if (hiddenForSession) return
        cancels += 1
        if (cancels < CANCELS_TO_HIDE) return
        hiddenForSession = true
        settings.markExclusionFailed()
        sequencer.trigger(OnboardingItem.EdgePanStopped, BannerCandidate.of(BannerRowId.EdgePanStopped, strings, DETAILS).getOrNull())
    }

    companion object {
        const val CANCELS_TO_HIDE = 2
        const val DETAILS = "Edge pan stopped working here. Use two fingers, the Hand tool or the pan arrows."
    }
}
