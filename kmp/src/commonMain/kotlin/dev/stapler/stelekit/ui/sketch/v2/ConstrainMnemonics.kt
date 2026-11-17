// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEngineStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/**
 * The Constrain menu entries and their mnemonics (Story 3.6.1, UX-111, D-8): C Coincident, H Horizontal, V Vertical, L
 * Parallel, P Perpendicular, E Equal, T Tangent, S Symmetric, F Fixed, D Distance, A Angle, R Radius/Diameter and X Reference
 * toggle. The declaration order is the menu order.
 */
enum class ConstrainMenuItem(val mnemonic: Char, val label: String) {
    Coincident('C', SketchStrings.Constrain.COINCIDENT),
    Horizontal('H', SketchStrings.Constrain.HORIZONTAL),
    Vertical('V', SketchStrings.Constrain.VERTICAL),
    Parallel('L', SketchStrings.Constrain.PARALLEL),
    Perpendicular('P', SketchStrings.Constrain.PERPENDICULAR),
    Equal('E', SketchStrings.Constrain.EQUAL),
    Tangent('T', SketchStrings.Constrain.TANGENT),
    Symmetric('S', SketchStrings.Constrain.SYMMETRIC),
    Fixed('F', SketchStrings.Constrain.FIXED),
    Distance('D', SketchStrings.Constrain.DISTANCE),
    Angle('A', SketchStrings.Constrain.ANGLE),
    RadiusOrDiameter('R', SketchStrings.Constrain.RADIUS_OR_DIAMETER),
    ReferenceToggle('X', SketchStrings.Constrain.REFERENCE_TOGGLE),
    HorizontalDistance('W', SketchEngineStrings.Constrain.HORIZONTAL_DISTANCE),
    VerticalDistance('G', SketchEngineStrings.Constrain.VERTICAL_DISTANCE),
}

/**
 * Menu navigation, not canvas single-key shortcuts: the menu's own key handler calls [pick] while the menu is open, and
 * [KeyDispatcher] never maps these letters (C is the Arc tool on the canvas, H the Hand tool).
 */
object ConstrainMnemonics {
    /** The enabled item a typed character picks, or null when the character is no mnemonic or its item is disabled. */
    fun pick(typed: String, enabled: Set<ConstrainMenuItem>): ConstrainMenuItem? =
        ConstrainMenuItem.entries.firstOrNull { it.mnemonic.toString().equals(typed, ignoreCase = true) && it in enabled }

    /** Where focus lands when the menu opens: the first enabled item, or null when none is enabled. */
    fun firstFocus(enabled: Set<ConstrainMenuItem>): ConstrainMenuItem? = ConstrainMenuItem.entries.firstOrNull { it in enabled }
}
