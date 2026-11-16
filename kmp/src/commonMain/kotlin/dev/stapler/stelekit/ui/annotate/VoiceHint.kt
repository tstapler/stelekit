// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.platform.Settings

internal const val VOICE_HINT_COUNT_KEY = "annotator_voice_hint_count"
internal const val VOICE_HINT_MAX_SHOWS = 3
internal const val VOICE_HINT_TEXT = "Voice notes: go Back to use the mic"

internal fun annotatorVoiceHintCount(settings: Settings?): Int =
    settings?.getString(VOICE_HINT_COUNT_KEY, "0")?.toIntOrNull() ?: 0

/** [openCount] is the 1-based editor open number, i.e. the counter after [recordEditorOpen]. */
internal fun shouldShowVoiceHint(openCount: Int): Boolean = openCount in 1..VOICE_HINT_MAX_SHOWS

/** Counts one editor open and returns the new count; call once per editor open, not per recomposition. */
internal fun recordEditorOpen(settings: Settings?): Int {
    val next = annotatorVoiceHintCount(settings) + 1
    settings?.putString(VOICE_HINT_COUNT_KEY, next.toString())
    return next
}
