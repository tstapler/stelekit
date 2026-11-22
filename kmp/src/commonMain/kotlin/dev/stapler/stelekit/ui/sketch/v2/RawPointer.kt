// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.tools.Modifiers
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.view.ScreenPx

// Lives in the sketch_core module (not ui_sketch) so the platform input seam can name it without importing the UI layer.

enum class RawPhase { Down, Move, Up, Cancel, Hover }

/** One platform pointer sample in screen pixels. [secondaryButton] is the right mouse button or a stylus barrel button. */
data class RawPointer(
    val id: Int,
    val kind: PointerKind,
    val phase: RawPhase,
    val position: ScreenPx,
    val pressure: Double? = null,
    val secondaryButton: Boolean = false,
    /** The platform classified this contact as a palm (Android `TOOL_TYPE_PALM`, palm `ACTION_CANCEL`; Task 3.1.2h). */
    val palm: Boolean = false,
    val modifiers: Modifiers = Modifiers.None,
    val timestamp: Long = 0L,
    /** 2 for the second press of a double click (the platform's own count), else 1. */
    val clickCount: Int = 1,
)
