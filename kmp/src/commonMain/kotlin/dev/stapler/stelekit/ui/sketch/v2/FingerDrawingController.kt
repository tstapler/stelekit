// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.settings.FingerDrawingSettings

/**
 * The Finger drawing state, its setting and its notices (Story 3.1.2, Tasks 3.1.2d and 3.1.2e; UX-80, UX-102; D-1, D-9).
 * It exposes only the state, the setting and the notices; the top-bar chip that shows them is Task 3.1.4d.
 *
 * - [on] is the effective value: ON until a stylus has been seen, then OFF, and an explicit choice always wins.
 * - The first stylus pen-up flips ON to OFF at most once per device and raises the `PenDetected` banner (never a silent flip).
 * - The first one-finger drag with a drawing tool offers the matching hint through the [OnboardingSequencer], which owns
 *   the "twice or until acted" recurrence.
 *
 * [onChanged] receives every change of the effective value so the editor state can follow.
 */
class FingerDrawingController(
    private val settings: FingerDrawingSettings,
    private val diagnostics: DiagnosticsPort,
    private val sequencer: OnboardingSequencer,
    private val strings: BannerStringSource,
    private val onChanged: (Boolean) -> Unit = {},
) : PointerGestureListener {
    val on: Boolean get() = settings.effective

    /** The user chose a value (chip, view cluster, overflow, the notice's Turn on or Back on): stored and never overridden. */
    fun choose(value: Boolean) {
        settings.choose(value)
        diagnostics.resolve(BannerRowId.PenDetected)
        HINTS.forEach { sequencer.dismiss(it, Dismissal.ChoseSetting) }
        onChanged(value)
    }

    /** `[Back on]` of the `PenDetected` banner. */
    fun backOn() = choose(true)

    /** `Got it` of the `PenDetected` banner or of a hint: the item is settled without changing the setting. */
    fun gotIt(hint: OnboardingItem? = null) {
        if (hint == null) diagnostics.resolve(BannerRowId.PenDetected) else sequencer.dismiss(hint, Dismissal.ActionButton)
    }

    override fun onStylusStrokeEnd() {
        if (!settings.markStylusSeen()) return
        diagnostics.raise(BannerRowId.PenDetected, PEN_DETECTED_DETAILS)
        onChanged(false)
    }

    override fun onOneFingerDrag() {
        val (item, row) = if (on) OnboardingItem.FingerHintOn to BannerRowId.FingerHintOn else OnboardingItem.FingerHintOff to BannerRowId.FingerHintOff
        sequencer.trigger(item, BannerCandidate.of(row, strings, if (on) ON_DETAILS else OFF_DETAILS).getOrNull())
    }

    companion object {
        private val HINTS = listOf(OnboardingItem.FingerHintOn, OnboardingItem.FingerHintOff)

        const val PEN_DETECTED_DETAILS = SketchStrings.FingerDrawing.PEN_DETECTED_DETAILS
        const val ON_DETAILS = SketchStrings.FingerDrawing.ON_DETAILS
        const val OFF_DETAILS = SketchStrings.FingerDrawing.OFF_DETAILS

        /** The tool hint while Finger drawing is OFF and a drawing tool is chosen (UX-80). */
        const val TOOL_HINT_OFF = SketchStrings.FingerDrawing.TOOL_HINT_OFF
    }
}
